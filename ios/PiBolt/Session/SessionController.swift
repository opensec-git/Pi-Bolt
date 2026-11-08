import Foundation
import Network
import Observation
import PiBoltProtocol

/// The connection as the session screen explains it.
enum LinkState: Equatable {
    case connecting
    case connected(endpoint: String)
    /// Waiting `inSeconds` before the next attempt (0: right away).
    case retrying(reason: DisconnectReason, detail: String?, inSeconds: Int)
    /// Will not retry by itself: the user has to act (re-pair, retry).
    case failed(reason: DisconnectReason, message: String)

    var isConnected: Bool {
        if case .connected = self { return true }
        return false
    }
}

/// One live session with a paired computer over remote control: connection with automatic reconnect, the reduced
/// `SessionState` (published at most once per frame), commands, and messages for the screen. The Swift side of the
/// Android app's `SessionController` (remote sessions only).
@MainActor
@Observable
final class SessionController {
    static let handoffNotice = "The computer's terminal took over. Reconnecting…"

    let hostId: String
    private(set) var hostName: String
    private(set) var state = SessionState()
    private(set) var link: LinkState = .connecting
    private(set) var models: [ModelInfo] = []
    /// The thinking levels the session's model offers (`get_available_thinking_levels`); empty until asked.
    private(set) var thinkingLevels: [String] = []
    var toast: Toast?
    /// Text the composer should take back (a failed send, a cleared queue).
    var composerRestore: String?
    /// Images the composer should take back with a failed send.
    var composerRestoreImages: [ImageAttachment] = []
    /// A sheet or the review screen to open, asked for from outside the screen (the debug autopilot of CI's
    /// screenshots).
    var requestedSheet: SessionSheet?
    var requestedReview = false
    /// True while this controller pairs with a new computer (its secret goes in the first handshake only).
    let pairing: Bool

    @ObservationIgnored private weak var app: AppModel?
    @ObservationIgnored private var backend: RemoteBackend
    @ObservationIgnored private var rpc: RpcClient
    @ObservationIgnored private let fallbackSecret: Data?
    @ObservationIgnored private var usedFallback = false
    @ObservationIgnored private var tasks: [Task<Void, Never>] = []
    @ObservationIgnored private var pump: Task<Void, Never>?
    @ObservationIgnored private var pending: [JSON] = []
    @ObservationIgnored private var flushScheduled = false
    @ObservationIgnored private var retryWaiter: CheckedContinuation<Void, Never>?
    @ObservationIgnored private var pathMonitor: NWPathMonitor?
    @ObservationIgnored private(set) var stopped = false
    /// Runs whose screen is open: the computer streams their conversation while they are watched
    /// (`subagent_subscribe`), and is asked again after every `remote_hello` (a new connection or session forgets them).
    @ObservationIgnored private var watchedSubagents: Set<String> = []
    /// The session a Recents row asked for: opened with `switch_session` once connected, unless it is the one shown.
    @ObservationIgnored private var switchTarget: RecentSession?
    /// The last entry given to Recents, so one is written only when what it shows changes.
    @ObservationIgnored private var recorded: RecentSession?
    /// `get_commands` was asked since the last `remote_hello`.
    @ObservationIgnored private var commandsFresh = false
    /// Snapshots received, so a background refresh can wait for a new one.
    @ObservationIgnored private var helloCount = 0

    /// One frame: a burst of token deltas becomes one state change and one redraw.
    private static let frameNanos: UInt64 = 16_000_000

    init(app: AppModel, hostId: String, hostName: String, endpoints: [String], pairSecret: Data?, fallbackSecret: Data? = nil) {
        self.app = app
        self.hostId = hostId
        self.hostName = hostName
        pairing = pairSecret != nil
        self.fallbackSecret = fallbackSecret
        backend = SessionController.makeBackend(app: app, hostId: hostId, endpoints: endpoints, secret: pairSecret)
        rpc = RpcClient(backend: backend)
    }

    private static func makeBackend(app: AppModel, hostId: String, endpoints: [String], secret: Data?) -> RemoteBackend {
        RemoteBackend(config: RemoteConfig(
            hostKey: Base64Url.decode(hostId) ?? Data(),
            endpoints: endpoints,
            identity: app.identity.keyPair(),
            deviceName: app.deviceName,
            appVersion: app.appVersion,
            pairSecret: secret
        ))
    }

    func start() {
        pumpEvents()
        tasks.append(Task { [weak self] in await self?.connectLoop() })
        // Waiting out a backoff (up to 30 s), a session retries as soon as a network comes up.
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            guard path.status == .satisfied else { return }
            Task { @MainActor [weak self] in
                guard let self, case .retrying = self.link else { return }
                self.retryNow()
            }
        }
        monitor.start(queue: DispatchQueue(label: "pibolt.path"))
        pathMonitor = monitor
    }

    func stop() {
        guard !stopped else { return }
        stopped = true
        pathMonitor?.cancel()
        backend.shutdown()
        rpc.stop()
        pump?.cancel()
        tasks.forEach { $0.cancel() }
        retryNow()
    }

    func retryNow() {
        let waiter = retryWaiter
        retryWaiter = nil
        waiter?.resume()
    }

    /// The app came back to the foreground: a link that died while it was suspended is noticed now, not at the next
    /// missed ping.
    func resume() {
        switch link {
        case .retrying, .failed:
            retryNow()
        case .connected:
            Task { [weak self] in
                guard let self else { return }
                let response = await self.rpc.request("get_state", timeout: 4)
                if response.success {
                    self.mutate { SessionReducer.applyRpcState($0, response.data) }
                } else if self.link.isConnected {
                    self.backend.dropConnection()
                }
            }
        case .connecting:
            break
        }
    }

    // MARK: Connection

    private func pumpEvents() {
        let client = rpc
        pump = Task { [weak self] in
            for await event in client.events {
                self?.enqueue(event)
            }
        }
    }

    private func connectLoop() async {
        var backoff = 1
        while !Task.isCancelled, !stopped {
            link = .connecting
            if await backend.connect() {
                backoff = 1
                onConnected()
                var end: (DisconnectReason, String?)?
                for await state in backend.states() {
                    if case let .disconnected(reason, detail) = state {
                        end = (reason, detail)
                        break
                    }
                }
                if stopped { return }
                guard let end else { return }
                let (reason, detail) = end
                if reason == .handoff { toast = Toast(text: Self.handoffNotice) }
                if reason.retryable || reason == .hostStopped {
                    // Reconnect at once after a drop; back off only if that fails.
                    link = .retrying(reason: reason, detail: detail, inSeconds: 0)
                    continue
                }
                await fail(reason, detail)
                continue
            }
            if stopped { return }
            guard case let .disconnected(reason, detail) = backend.state else { continue }
            if reason == .unauthorized, let fallbackSecret, !usedFallback {
                usedFallback = true
                swapBackend(secret: fallbackSecret)
                continue
            }
            if !reason.retryable {
                await fail(reason, detail)
                continue
            }
            link = .retrying(reason: reason, detail: detail, inSeconds: backoff)
            await waitForRetry(seconds: backoff)
            backoff = min(backoff * 2, 30)
        }
    }

    private func fail(_ reason: DisconnectReason, _ detail: String?) async {
        link = .failed(reason: reason, message: explain(reason, detail))
        await waitForRetry(seconds: nil)
    }

    /// Sleeps `seconds` (nil: until `retryNow`), or less when `retryNow` runs.
    private func waitForRetry(seconds: Int?) async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            retryWaiter?.resume()
            retryWaiter = continuation
            if stopped {
                retryNow()
                return
            }
            if let seconds {
                Task { [weak self] in
                    try? await Task.sleep(nanoseconds: UInt64(seconds) * 1_000_000_000)
                    self?.retryNow()
                }
            }
        }
    }

    /// A known computer that refused this phone without a secret: pair again with the scanned code's secret.
    private func swapBackend(secret: Data) {
        guard let app else { return }
        let endpoints = backend.config.endpoints
        rpc.stop()
        pump?.cancel()
        backend.shutdown()
        backend = SessionController.makeBackend(app: app, hostId: hostId, endpoints: endpoints, secret: secret)
        rpc = RpcClient(backend: backend)
        pumpEvents()
    }

    func explain(_ reason: DisconnectReason, _ detail: String?) -> String {
        switch reason {
        case .unauthorized:
            return pairing || usedFallback
                ? "The computer refused this pairing code. Codes work once and expire after 10 minutes. Run /remote-control for a new one."
                : "This phone is no longer paired with \(hostName). Scan a new code from /remote-control to pair again."
        case .hostStopped: return "Remote control stopped on \(hostName)."
        case .handoff: return Self.handoffNotice
        case .malformed: return "The computer could not read this app's messages. Update Pi-Bolt or the app."
        case .rateLimited: return "Too many failed attempts from this network. Wait a minute and retry."
        case .unreachable: return "Can't reach \(hostName). Check that /remote-control or the Pi-Bolt service is running and the phone is on the same network."
        case .handshakeTimeout: return "The computer took too long to answer."
        case .lost: return "The connection dropped."
        case .closed: return "Disconnected."
        }
    }

    private func onConnected() {
        guard case let .connected(endpoint, hello) = backend.state, let app else { return }
        link = .connected(endpoint: endpoint)
        if !hello.name.isEmpty { hostName = hello.name }
        let existing = app.hosts.get(hostId)
        var host = existing ?? PairedHost(id: hostId, name: hostName, endpoints: backend.config.endpoints, pairedAt: Date())
        host.name = hostName
        host.endpoints = backend.config.endpoints
        if !hello.version.isEmpty { host.version = hello.version }
        host.lastConnectedAt = Date()
        app.hosts.upsert(host)
    }

    // MARK: State

    private func enqueue(_ event: JSON) {
        pending.append(event)
        guard !flushScheduled else { return }
        flushScheduled = true
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: SessionController.frameNanos)
            self?.flush()
        }
    }

    private func mutate(_ change: (SessionState) -> SessionState) {
        state = change(state)
    }

    /// Apply a frame's events, with consecutive token deltas of one block merged into one event first.
    private func flush() {
        flushScheduled = false
        let batch = pending
        pending = []
        var s = state
        var held: JSON?
        for event in batch {
            if let h = held, let merged = SessionReducer.mergeDeltas(h, event) {
                held = merged
                continue
            }
            if let h = held { s = apply(s, h) }
            held = event
        }
        if let h = held { s = apply(s, h) }
        state = s
        afterPublish()
    }

    /// Recents and a pending switch, after the state changed.
    private func afterPublish() {
        guard let app, state.hello else { return }
        if !app.isForeground { app.sessionChangedInBackground(self) }
        if let entry = RecentSession.of(state, hostId: hostId, hostName: hostName),
           recorded?.id != entry.id || recorded?.title != entry.title || recorded?.sessionFile != entry.sessionFile
        {
            recorded = entry
            app.recents.record(entry)
        }
        if let target = switchTarget, link.isConnected {
            switchTarget = nil
            guard target.sessionId != state.sessionId, let file = target.sessionFile, state.host?.service == true else { return }
            guard canSwitchNow else {
                toast = Toast(text: "Pi is working here. Stop it first to open that session.", kind: .warning)
                return
            }
            Task { [weak self] in _ = await self?.switchSession(file) }
        }
    }

    /// Open `recent` once connected (a Recents row), when it is not the session the computer shows.
    func openWhenConnected(_ recent: RecentSession) {
        switchTarget = recent
        afterPublish()
    }

    private func apply(_ before: SessionState, _ event: JSON) -> SessionState {
        sideEffects(before, event)
        return SessionReducer.reduce(before, event)
    }

    private func sideEffects(_ before: SessionState, _ event: JSON) {
        switch event.type {
        case "extension_ui_request":
            if event.str("method") == "notify" {
                let kind: Toast.Kind = switch event.str("notifyType") {
                case "error": .error
                case "warning": .warning
                default: .info
                }
                toast = Toast(text: event.str("message") ?? "", kind: kind)
            } else {
                alert(before, event)
            }
        case "extension_ui_dismissed":
            if before.dialogs.count <= 1 { app?.notifier.clearAnswer(hostId: hostId) }
        case "agent_settled":
            alert(before, event)
            // Recents show when a session last worked.
            recorded = nil
            Task { [weak self] in await self?.refreshState() }
        case "remote_hello":
            helloCount += 1
            models = []
            thinkingLevels = []
            commandsFresh = false
            // The computer forgot this phone's subagent streams (a new connection or session): ask again.
            let streams = (event.arr("features") ?? []).contains { $0.primitiveText == subagentStreamFeature }
            for key in watchedSubagents { subscribeSubagent(key, streams: streams) }
            // A reconnect after the run ended or a dialog opened: the alerts the drop swallowed.
            if app?.isForeground == false, let app {
                app.notifier.post(SessionAlerts.missed(before: before, hello: event, hostId: hostId, hostName: hostName))
            }
            let host = event.obj("host")
            app?.hosts.update(hostId) {
                $0.cwd = host?.str("cwd") ?? $0.cwd
                $0.version = host?.str("version") ?? $0.version
                $0.service = host?.bool("service") == true
            }
        case RemoteEndpoints.type:
            onEndpoints(event)
        default:
            break
        }
    }

    /// `remote_endpoints`: the computer's addresses now (a quick tunnel's new one). Used for reconnects and saved.
    private func onEndpoints(_ event: JSON) {
        guard let endpoints = RemoteEndpoints.from(event, connected: backend.lastEndpoint), endpoints != backend.config.endpoints else { return }
        backend.updateEndpoints(endpoints)
        app?.hosts.update(hostId) { $0.endpoints = endpoints }
    }

    /// A local notification for `event`, while the app is not in front.
    private func alert(_ before: SessionState, _ event: JSON) {
        guard let app, !app.isForeground else { return }
        app.notifier.post(SessionAlerts.alerts(before: before, event: event, hostId: hostId, hostName: hostName))
    }

    private func refreshState() async {
        let response = await rpc.getState()
        if response.success { mutate { SessionReducer.applyRpcState($0, response.data) } }
    }

    // MARK: Commands

    /// Send a prompt. While the agent runs, `behavior` says whether it steers or waits as a follow-up.
    func send(_ text: String, images: [ImageAttachment] = [], behavior: StreamingBehavior = .steer) {
        let running = state.isRunning
        app?.notifier.requestPermissionOnce()
        Task { [weak self] in
            guard let self else { return }
            // An extension command answers only when it finishes, after its dialogs: nothing waits on this.
            let response = await self.rpc.prompt(text, images: images, streamingBehavior: running ? behavior : nil)
            if !response.success, !self.stopped {
                self.composerRestore = text
                self.composerRestoreImages = images
                self.toast = Toast(text: response.error ?? "Not sent", kind: .error)
            }
        }
    }

    func abort() {
        Task { [weak self] in
            guard let self else { return }
            let response = await self.rpc.abort()
            if !response.success { self.toast = Toast(text: response.error ?? "Abort failed", kind: .error) }
        }
    }

    func clearQueue() {
        Task { [weak self] in
            guard let self else { return }
            let response = await self.rpc.clearQueue()
            if response.success {
                let text = (response.data?.strings("steering") ?? []) + (response.data?.strings("followUp") ?? [])
                if !text.isEmpty { self.composerRestore = text.joined(separator: "\n\n") }
            } else {
                self.toast = Toast(text: response.error ?? "Could not clear the queue", kind: .error)
            }
        }
    }

    func compact(instructions: String? = nil) {
        Task { [weak self] in
            guard let self else { return }
            let response = await self.rpc.compact(instructions: instructions)
            if !response.success { self.toast = Toast(text: response.error ?? "Compaction failed", kind: .error) }
        }
    }

    func loadModels() {
        Task { [weak self] in
            guard let self else { return }
            let response = await self.rpc.getAvailableModels()
            if response.success { self.models = ModelInfo.list(from: response) }
            else { self.toast = Toast(text: response.error ?? "Could not list models", kind: .error) }
        }
    }

    func setModel(_ model: ModelInfo) {
        Task { [weak self] in
            guard let self else { return }
            let response = await self.rpc.setModel(provider: model.provider, modelId: model.id)
            if response.success {
                self.mutate {
                    var s = $0
                    s.model = ModelInfo.from(response.data) ?? model
                    return s
                }
                // Another model may offer other thinking levels, and Pi may have clamped the level.
                self.thinkingLevels = []
                await self.refreshState()
            } else {
                self.toast = Toast(text: response.error ?? "Could not switch the model", kind: .error)
            }
        }
    }

    /// One command to the computer, for screens with their own commands (review). Waits up to `timeout` seconds.
    func request(_ type: String, timeout: TimeInterval? = 30, _ fields: [String: JSON] = [:]) async -> RpcResponse {
        await rpc.request(type, timeout: timeout, fields)
    }

    func rename(_ name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        Task { [weak self] in
            guard let self else { return }
            let response = await self.rpc.setSessionName(trimmed)
            if response.success {
                self.mutate {
                    var s = $0
                    s.sessionName = trimmed.isEmpty ? nil : trimmed
                    return s
                }
            } else {
                self.toast = Toast(text: response.error ?? "Rename failed", kind: .error)
            }
        }
    }

    func loadThinkingLevels() {
        Task { [weak self] in
            guard let self else { return }
            let response = await self.rpc.getAvailableThinkingLevels()
            if response.success { self.thinkingLevels = PiBoltProtocol.thinkingLevels(from: response) }
            else { self.toast = Toast(text: response.error ?? "Could not list thinking levels", kind: .error) }
        }
    }

    func setThinkingLevel(_ level: String) {
        Task { [weak self] in
            guard let self else { return }
            let response = await self.rpc.setThinkingLevel(level)
            if response.success {
                self.mutate {
                    var s = $0
                    s.thinkingLevel = level
                    return s
                }
                // Pi clamps a level the model lacks: show the level it set.
                await self.refreshState()
            } else {
                self.toast = Toast(text: response.error ?? "Could not change the thinking level", kind: .error)
            }
        }
    }

    /// The host's `/` commands, read again once per snapshot (an extension may have added some since).
    func refreshCommands() {
        guard !commandsFresh, link.isConnected else { return }
        commandsFresh = true
        Task { [weak self] in
            guard let self else { return }
            let response = await self.rpc.getCommands()
            if response.success {
                let commands = SessionReducer.commandsFrom(response.data)
                self.mutate {
                    var s = $0
                    s.commands = commands
                    return s
                }
            } else {
                self.commandsFresh = false
            }
        }
    }

    // MARK: Subagents

    /// Run `key`'s screen opened (`on`) or closed: a computer with `subagent_stream` streams its own conversation while
    /// it is open.
    func watchSubagent(_ key: String, _ on: Bool) {
        let was = watchedSubagents.contains(key)
        if on { watchedSubagents.insert(key) } else { watchedSubagents.remove(key) }
        guard was != on else { return }
        if on { subscribeSubagent(key, streams: state.host?.streamsSubagents == true) } else { unsubscribeSubagent(key) }
    }

    private static let subscribeTries = 5
    private static let subscribeRetryNanos: UInt64 = 1_500_000_000

    private func subscribeSubagent(_ key: String, streams: Bool) {
        guard streams else { return }
        let client = rpc
        Task { [weak self] in
            // A call still being written is not a run yet on the computer: asked again a few times while it is
            // watched. Not an error the reader sees: the screen keeps the prompt and progress it has.
            for _ in 0 ..< SessionController.subscribeTries {
                let response = await client.subagentSubscribe(key)
                if response.success { return }
                guard response.error?.hasPrefix("No subagent run") == true else { return }
                try? await Task.sleep(nanoseconds: SessionController.subscribeRetryNanos)
                guard let self, self.watchedSubagents.contains(key), client === self.rpc else { return }
            }
        }
    }

    private func unsubscribeSubagent(_ key: String) {
        guard state.host?.streamsSubagents == true else { return }
        let client = rpc
        Task { _ = await client.subagentUnsubscribe(key) }
    }

    // MARK: Sessions (the background service)

    func listProjects() async -> Result<[RemoteProject], SessionError> {
        let response = await rpc.listProjects()
        return response.success ? .success(RemoteProject.list(from: response)) : .failure(SessionError(response.error))
    }

    func listSessions(cwd: String?) async -> Result<[RemoteSessionInfo], SessionError> {
        let response = await rpc.listSessions(cwd: cwd)
        return response.success ? .success(RemoteSessionInfo.list(from: response)) : .failure(SessionError(response.error))
    }

    /// An older service has one runtime: switching it mid-turn would stop the run.
    var canSwitchNow: Bool { state.host?.parallelSessions == true || !state.isRunning }

    func switchSession(_ path: String) async -> Bool {
        let response = await rpc.switchSession(path)
        return finishSwitch(response)
    }

    func newSession(cwd: String?) async -> Bool {
        let response = await rpc.newSession(cwd: cwd)
        return finishSwitch(response)
    }

    private func finishSwitch(_ response: RpcResponse) -> Bool {
        if !response.success {
            toast = Toast(text: response.error ?? "Could not open the session", kind: .error)
            return false
        }
        if response.data?.bool("cancelled") == true {
            toast = Toast(text: "An extension cancelled it.", kind: .warning)
            return false
        }
        return true
    }

    // MARK: Dialogs

    func answer(_ dialog: DialogRequest, value: String) {
        rpc.respond(dialog.id, value: value)
        dismiss(dialog)
    }

    func answer(_ dialog: DialogRequest, confirmed: Bool) {
        rpc.respond(dialog.id, confirmed: confirmed)
        dismiss(dialog)
    }

    func cancel(_ dialog: DialogRequest) {
        rpc.cancelDialog(dialog.id)
        dismiss(dialog)
    }

    private func dismiss(_ dialog: DialogRequest) {
        mutate {
            var s = $0
            s.dialogs.removeAll { $0.id == dialog.id }
            return s
        }
        if state.dialogs.isEmpty { app?.notifier.clearAnswer(hostId: hostId) }
    }

    // MARK: Background

    /// What a background refresh should check later, or nil when nothing could change worth an alert.
    var alertWatch: AlertWatch? { AlertWatch.of(state, hostId: hostId, hostName: hostName) }

    /// A background refresh woke the app: reconnect if the link died while suspended, and wait (at most `timeout`
    /// seconds) for a fresh snapshot. Its `remote_hello` raises the alerts of whatever happened meanwhile.
    func checkInBackground(timeout: TimeInterval) async {
        let before = helloCount
        switch link {
        case .connected: backend.dropConnection()
        default: retryNow()
        }
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline, !stopped, helloCount == before {
            try? await Task.sleep(nanoseconds: 200_000_000)
        }
    }
}

struct SessionError: Error {
    let message: String
    init(_ message: String?) { self.message = message ?? "The computer did not answer" }
}
