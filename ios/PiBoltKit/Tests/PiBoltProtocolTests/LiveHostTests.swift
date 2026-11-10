import Foundation
@testable import PiBoltProtocol
import Testing

/// Pi-Bolt's own remote control server (ios/fixtures/rc-host.ts: `RemoteControlServer` in front of faux-model
/// sessions), run with Node from this checkout. These are the Android `RemoteBackendTest` cases against the real host.
///
/// Needs `node` (22.15 or later) and `npm ci --ignore-scripts && npm run generate:catalog-modules` at the repository
/// root. Without them the suite is skipped, unless `PIBOLT_LIVE_HOST=1` (CI) makes it run and fail.
final class FixtureHost: @unchecked Sendable {
    static let repoRoot = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()

    static var available: Bool {
        if ProcessInfo.processInfo.environment["PIBOLT_LIVE_HOST"] == "1" { return true }
        if ProcessInfo.processInfo.environment["PIBOLT_LIVE_HOST"] == "0" { return false }
        let fm = FileManager.default
        return fm.fileExists(atPath: repoRoot.appendingPathComponent("node_modules/ws").path)
            && fm.fileExists(atPath: repoRoot.appendingPathComponent("packages/ai/src/providers/openai.catalog.generated.ts").path)
    }

    private let process = Process()
    private let stdin = Pipe()
    private let stdout = Pipe()
    private let lock = NSLock()
    private var buffer = Data()
    private var events: [JSON] = []

    private(set) var ready: JSON = .null
    var uri: String { ready.str("uri")! }
    var endpoint: String { "ws://127.0.0.1:\(ready.int("port")!)/rc" }
    var floodEndpoint: String { "ws://127.0.0.1:\(ready.int("floodPort")!)/rc" }
    var silentEndpoint: String { "ws://127.0.0.1:\(ready.int("silentPort")!)/rc" }
    var hostKey: Data { try! PairingPayload.parse(uri).hostKey }

    init() async throws {
        process.executableURL = URL(fileURLWithPath: "/usr/bin/env")
        process.arguments = ["node", "--import", "./ios/fixtures/workspace-hooks.mjs", "ios/fixtures/rc-host.ts"]
        process.currentDirectoryURL = Self.repoRoot
        var env = ProcessInfo.processInfo.environment
        env["PI_OFFLINE"] = "1"
        process.environment = env
        process.standardInput = stdin
        process.standardOutput = stdout
        stdout.fileHandleForReading.readabilityHandler = { [weak self] handle in
            self?.take(handle.availableData)
        }
        try process.run()
        ready = try await Self.waitFor(host: nil, timeout: 90, events: { [self] in self.snapshot() }) { $0.str("event") == "ready" }
    }

    deinit {
        stop()
    }

    func stop() {
        stdout.fileHandleForReading.readabilityHandler = nil
        try? stdin.fileHandleForWriting.close()
        if process.isRunning {
            process.terminate()
        }
    }

    private func take(_ data: Data) {
        lock.withLock {
            buffer.append(data)
            while let newline = buffer.firstIndex(of: UInt8(ascii: "\n")) {
                let line = buffer.subdata(in: buffer.startIndex ..< newline)
                buffer.removeSubrange(buffer.startIndex ... newline)
                if let json = try? JSON.parse(line) { events.append(json) }
            }
        }
    }

    private func snapshot() -> [JSON] { lock.withLock { events } }

    /// Sends one stdin command and waits for its answer event.
    @discardableResult func command(_ line: String, answer: String) async throws -> JSON {
        let before = snapshot().count
        stdin.fileHandleForWriting.write(Data("\(line)\n".utf8))
        return try await Self.waitFor(host: nil, timeout: 20, events: { [self] in Array(self.snapshot().dropFirst(before)) }) { $0.str("event") == answer }
    }

    /// A pairing link with an unused secret.
    func freshPairing() async throws -> PairingPayload {
        try PairingPayload.parse(try await command("pair", answer: "pair").str("uri")!)
    }

    struct Timeout: Error {}

    static func waitFor(host: FixtureHost?, timeout: TimeInterval, events: () -> [JSON], _ predicate: (JSON) -> Bool) async throws -> JSON {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let match = events().first(where: predicate) { return match }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        throw Timeout()
    }
}

/// Collects a client's events so tests can wait for one.
final class EventLog: @unchecked Sendable {
    private let lock = NSLock()
    private var events: [JSON] = []
    private var task: Task<Void, Never>?

    init(_ rpc: RpcClient) {
        task = Task { [weak self] in
            for await e in rpc.events { self?.lock.withLock { self?.events.append(e) } }
        }
    }

    var all: [JSON] { lock.withLock { events } }

    func waitFor(timeout: TimeInterval = 15, after: Int = 0, _ predicate: @escaping (JSON) -> Bool) async throws -> JSON {
        try await FixtureHost.waitFor(host: nil, timeout: timeout, events: { [self] in Array(self.all.dropFirst(after)) }, predicate)
    }

    /// The state the app would show: every event so far through the reducer.
    var state: SessionState { all.reduce(SessionState()) { SessionReducer.reduce($0, $1) } }
}

/// One fixture process for the whole suite.
actor SharedFixture {
    static let shared = SharedFixture()
    private var host: FixtureHost?

    func get() async throws -> FixtureHost {
        if let host { return host }
        let started = try await FixtureHost()
        host = started
        return started
    }
}

@Suite(.serialized, .enabled(if: FixtureHost.available))
struct LiveHostTests {
    private func config(_ host: FixtureHost, identity: KeyPair, pair: Data?, endpoints: [String], hostKey: Data? = nil) -> RemoteConfig {
        RemoteConfig(hostKey: hostKey ?? host.hostKey, endpoints: endpoints, identity: identity, deviceName: "iPhone Test", appVersion: "0.1.0", pairSecret: pair)
    }

    /// A paired, connected client and its event log.
    private func connected(_ host: FixtureHost) async throws -> (RemoteBackend, RpcClient, EventLog) {
        let pairing = try await host.freshPairing()
        let backend = RemoteBackend(config: config(host, identity: .generate(), pair: pairing.secret, endpoints: [host.endpoint]))
        let rpc = RpcClient(backend: backend)
        let log = EventLog(rpc)
        #expect(await backend.connect())
        _ = try await log.waitFor { $0.type == "remote_hello" }
        return (backend, rpc, log)
    }

    @Test func pairsThenReconnectsWithoutSecretAndRunsCommands() async throws {
        let host = try await SharedFixture.shared.get()
        let pairing = try await host.freshPairing()
        let paired = Counter()
        // The first endpoint refuses connections: the client moves on to the next one.
        let backend = RemoteBackend(
            config: config(host, identity: .generate(), pair: pairing.secret, endpoints: ["ws://127.0.0.1:1/rc", host.endpoint]),
            onPaired: { _ in paired.increment() }
        )
        let rpc = RpcClient(backend: backend)
        let log = EventLog(rpc)

        #expect(await backend.connect())
        guard case let .connected(endpoint, hello) = backend.state else {
            Issue.record("not connected: \(backend.state)")
            return
        }
        #expect(endpoint == host.endpoint)
        #expect(hello.name == "fixture-host")
        #expect(hello.version == "0.0.0-fixture")
        #expect(hello.paired)
        #expect(paired.value == 1)
        #expect(backend.config.pairSecret == nil, "the single-use secret is dropped after pairing")
        let remoteHello = try await log.waitFor { $0.type == "remote_hello" }
        // Stored once the client confirmed the keys (remote_ready), before remote_hello.
        #expect(try await host.command("devices", answer: "devices").strings("names").contains("iPhone Test"))
        #expect(remoteHello.int("protocol") == 1)
        #expect(remoteHello.obj("host")?.bool("service") == true)
        // remote_endpoints follows remote_hello on connect.
        let endpoints = try await log.waitFor { $0.type == RemoteEndpoints.type }
        #expect(RemoteEndpoints.from(endpoints, connected: backend.lastEndpoint) == [host.endpoint])

        let mark = log.all.count
        let response = await rpc.prompt("hi there")
        #expect(response.success, "\(response.error ?? "")")
        _ = try await log.waitFor(after: mark) { $0.type == "agent_settled" }
        #expect(log.all.dropFirst(mark).contains { $0.type == "agent_start" })
        #expect(log.all.dropFirst(mark).contains { $0.type == "message_update" && $0.obj("assistantMessageEvent")?.type == "text_delta" })
        let state = log.state
        #expect(!state.isRunning)
        let texts = state.items.compactMap(\.assistantText).map(\.text)
        #expect(texts.last == "Echo: hi there. Some **bold** and `code`.")
        #expect(state.items.contains { if case let .thinking(t) = $0 { return t.text == "Reading the prompt." }; return false })

        let refused = await rpc.request("bash", ["command": "ls"])
        #expect(!refused.success)
        #expect(refused.error == "Not available over remote control")

        backend.close()
        #expect(backend.state == .disconnected(reason: .closed, detail: nil))

        // Reconnect: no secret this time, still accepted, and the conversation is in the new snapshot.
        let before = log.all.count
        #expect(await backend.connect())
        guard case let .connected(_, again) = backend.state else {
            Issue.record("not connected again")
            return
        }
        #expect(!again.paired)
        #expect(paired.value == 1)
        let second = try await log.waitFor(after: before) { $0.type == "remote_hello" }
        #expect((second.arr("messages") ?? []).count >= 2)
        backend.close()
        rpc.stop()
    }

    @Test func unknownDeviceIsRefusedWith4403() async throws {
        let host = try await SharedFixture.shared.get()
        let backend = RemoteBackend(config: config(host, identity: .generate(), pair: Data(count: 32), endpoints: [host.endpoint, host.silentEndpoint]))
        #expect(!(await backend.connect()))
        // A refusal is final: the other endpoint (a silent peer that would take the whole timeout) is not tried.
        #expect(backend.state == .disconnected(reason: .unauthorized, detail: nil))
    }

    @Test func wrongHostKeyNeverConnects() async throws {
        let host = try await SharedFixture.shared.get()
        let pairing = try await host.freshPairing()
        let backend = RemoteBackend(config: config(host, identity: .generate(), pair: pairing.secret, endpoints: [host.endpoint], hostKey: KeyPair.generate().publicKey))
        #expect(!(await backend.connect()))
        guard case .disconnected = backend.state else {
            Issue.record("expected disconnected, got \(backend.state)")
            return
        }
    }

    @Test func anOversizedFrameBeforeTheHandshakeIsRefused() async throws {
        let host = try await SharedFixture.shared.get()
        let backend = RemoteBackend(config: config(host, identity: .generate(), pair: nil, endpoints: [host.floodEndpoint]))
        #expect(!(await backend.connect()))
        guard case let .disconnected(reason, _) = backend.state else {
            Issue.record("expected disconnected")
            return
        }
        #expect(reason == .malformed)
    }

    @Test func closeWhileConnectingAbandonsTheHandshake() async throws {
        let host = try await SharedFixture.shared.get()
        var cfg = config(host, identity: .generate(), pair: nil, endpoints: [host.silentEndpoint])
        cfg.lanTimeout = 60
        let backend = RemoteBackend(config: cfg)
        let connecting = Task { await backend.connect() }
        try await Task.sleep(nanoseconds: 500_000_000)
        backend.close()
        let started = Date()
        #expect(!(await connecting.value))
        #expect(Date().timeIntervalSince(started) < 5)
        #expect(backend.state == .disconnected(reason: .closed, detail: nil))
        #expect(backend.lastEndpoint == nil)
    }

    @Test func toolCallsStreamIntoToolRows() async throws {
        let host = try await SharedFixture.shared.get()
        let (backend, rpc, log) = try await connected(host)
        let mark = log.all.count
        #expect((await rpc.prompt("use the tool please")).success)
        _ = try await log.waitFor(after: mark) { $0.type == "agent_settled" }
        let events = log.all.dropFirst(mark)
        #expect(events.contains { $0.type == "tool_execution_update" })
        let state = log.state
        let tool = try #require(state.items.compactMap(\.tool).last)
        #expect(tool.name == "shout")
        #expect(tool.status == .done)
        #expect(tool.result == "HELLO")
        #expect(tool.args == ["text": "hello"])
        #expect(state.items.compactMap(\.assistantText).last?.text == "The tool said HELLO.")
        // Every row has its own key.
        #expect(Set(state.items.map(\.key)).count == state.items.count)
        backend.close()
        rpc.stop()
    }

    @Test func abortStopsARunningTool() async throws {
        let host = try await SharedFixture.shared.get()
        let (backend, rpc, log) = try await connected(host)
        let mark = log.all.count
        let prompt = Task { await rpc.prompt("please wait") }
        _ = try await log.waitFor(after: mark) { $0.type == "tool_execution_start" }
        #expect(log.state.isRunning)
        let steer = await rpc.request("prompt", ["message": "queued", "streamingBehavior": "followUp"])
        #expect(steer.success, "\(steer.error ?? "")")
        let abort = await rpc.abort()
        #expect(abort.success)
        let settled = try await log.waitFor(after: mark) { $0.type == "agent_settled" }
        #expect(settled.bool("aborted") == true)
        _ = await prompt.value
        #expect(!log.state.isRunning)
        backend.close()
        rpc.stop()
    }

    @Test func serviceListsSwitchesAndStartsSessions() async throws {
        let host = try await SharedFixture.shared.get()
        let (backend, rpc, log) = try await connected(host)
        let projects = RemoteProject.list(from: await rpc.listProjects())
        #expect(projects.count == 1)
        #expect(projects.first?.name == "fixture")
        let first = RemoteSessionInfo.list(from: await rpc.listSessions(cwd: projects.first?.cwd))
        #expect(!first.isEmpty)

        var mark = log.all.count
        let created = await rpc.newSession(cwd: projects.first?.cwd)
        #expect(created.success)
        #expect(created.data?.bool("cancelled") == false)
        let fresh = try await log.waitFor(after: mark) { $0.type == "remote_hello" }
        #expect((fresh.arr("messages") ?? []).isEmpty)
        let sessions = RemoteSessionInfo.list(from: await rpc.listSessions())
        #expect(sessions.count == first.count + 1)
        #expect(sessions.first?.title == "Session 1")

        mark = log.all.count
        let switched = await rpc.switchSession(sessions[0].path)
        #expect(switched.success)
        _ = try await log.waitFor(after: mark) { $0.type == "remote_hello" }
        backend.close()
        rpc.stop()
    }

    @Test func helloAdvertisesSubagentStreamsAndReview() async throws {
        let host = try await SharedFixture.shared.get()
        let (backend, rpc, log) = try await connected(host)
        let hello = try await log.waitFor { $0.type == "remote_hello" }
        #expect(hello.strings("features").contains("subagent_stream"))
        #expect(hello.obj("host")?.bool("review") == true)
        backend.close()
        rpc.stop()
    }

    /// The `Agent` call's own conversation (docs/remote-control.md, "Subagent streams"): the fixture runs a child session
    /// as the subagents extension does, saved next to the parent's file.
    @Test func streamsASubagentRun() async throws {
        let host = try await SharedFixture.shared.get()
        let (backend, rpc, log) = try await connected(host)
        let unknown = await rpc.request("subagent_subscribe", ["runId": "no-such-run"])
        #expect(!unknown.success)

        let mark = log.all.count
        let prompt = Task { await rpc.prompt("run a subagent") }
        let start = try await log.waitFor(after: mark) { $0.type == "tool_execution_start" && $0.str("toolName") == "Agent" }
        let runId = try #require(start.str("toolCallId"))
        let subscribed = await rpc.request("subagent_subscribe", ["runId": .string(runId)])
        #expect(subscribed.success, "\(subscribed.error ?? "")")

        func entries() -> [JSON] { log.all.dropFirst(mark).filter { $0.type == "subagent_entries" && $0.str("runId") == runId } }
        func text(_ message: JSON) -> String {
            (message.arr("content") ?? []).filter { $0.type == "text" }.compactMap { $0.str("text") }.joined()
        }
        _ = try await log.waitFor(timeout: 20, after: mark) { $0.type == "subagent_entries" && $0.str("runId") == runId }
        #expect(entries().first?.bool("reset") == true)
        _ = try await log.waitFor(timeout: 20, after: mark) { event in
            event.type == "subagent_entries" && event.str("runId") == runId
                && (event.arr("messages") ?? []).contains { $0.str("role") == "assistant" && text($0).contains("Subagent found two files.") }
        }
        let messages = entries().flatMap { $0.arr("messages") ?? [] }
        #expect(messages.first?.str("role") == "user")
        #expect(messages.contains { $0.str("role") == "toolResult" && $0.str("toolName") == "shout" })
        let live = log.all.dropFirst(mark).filter { $0.type == "subagent_events" && $0.str("runId") == runId }
        #expect(!live.isEmpty, "the child's streaming message arrives as subagent_events")
        #expect(live.flatMap { $0.arr("events") ?? [] }.contains { $0.type == "message_update" })

        _ = try await log.waitFor(timeout: 20, after: mark) { $0.type == "agent_settled" }
        _ = await prompt.value
        let end = log.all.dropFirst(mark).first { $0.type == "tool_execution_end" && $0.str("toolCallId") == runId }
        #expect(end?.obj("result")?.obj("details")?.str("status") == "completed")
        // Contains, not last: a follow-up an earlier test queued in this session may run after this turn.
        #expect(log.state.items.compactMap(\.assistantText).contains { $0.text == "The subagent said: Subagent found two files." })
        // The reducer builds the run and its streamed conversation from what the host sent.
        let run = try #require(log.state.subagents[runId])
        #expect(run.status == .done)
        #expect(run.type == "general-purpose")
        #expect(run.description == "List files")
        #expect(run.result == "Subagent found two files.")
        let transcript = try #require(run.transcript)
        if case .user = transcript.items.first {} else { Issue.record("the transcript starts with the prompt") }
        #expect(transcript.items.contains { $0.tool?.name == "shout" && $0.tool?.status == .done })
        #expect(transcript.items.last?.assistantText?.text == "Subagent found two files.")
        #expect(transcript.live.isEmpty)
        let unsubscribed = await rpc.request("subagent_unsubscribe", ["runId": .string(runId)])
        #expect(unsubscribed.success)
        backend.close()
        rpc.stop()
    }

    /// The read-only review commands (docs/remote-control.md, "Review") on the fixture's repository: one commit, then
    /// README.md modified, notes.txt untracked and a .env.
    @Test func reviewsTheWorkingTree() async throws {
        let host = try await SharedFixture.shared.get()
        let (backend, rpc, _) = try await connected(host)

        let status = await rpc.request("rc_git_status")
        #expect(status.success, "\(status.error ?? "")")
        #expect(status.data?.bool("repo") == true)
        #expect(status.data?.obj("branch")?.str("head") == "main")
        let entries = status.data?.arr("entries") ?? []
        #expect(entries.contains { $0.str("path") == "README.md" && $0.str("worktree") == "M" })
        #expect(entries.contains { $0.str("path") == "notes.txt" && $0.str("worktree") == "?" })

        let diff = await rpc.request("rc_git_diff", ["path": "README.md", "staged": false])
        #expect(diff.success, "\(diff.error ?? "")")
        #expect((diff.data?.str("diff") ?? "").split(separator: "\n").contains { $0.hasPrefix("+") && !$0.hasPrefix("+++") })

        let log = await rpc.request("rc_git_log", ["limit": 10, "skip": 0])
        #expect(log.success)
        let commits = log.data?.arr("commits") ?? []
        #expect(commits.count >= 1)
        let hash = try #require(commits.first?.str("hash"))
        #expect(hash.count == 40 && hash.allSatisfy(\.isHexDigit))
        #expect(commits.first?.str("subject") == "Initial commit")
        let show = await rpc.request("rc_git_show", ["hash": .string(hash)])
        #expect(show.success, "\(show.error ?? "")")
        #expect(show.data?.obj("commit")?.str("hash") == hash)
        #expect((show.data?.str("diff") ?? "").contains("src/app.ts"))

        let listing = await rpc.request("rc_list_dir", ["path": ""])
        #expect(listing.success)
        let names = (listing.data?.arr("entries") ?? []).compactMap { $0.str("name") }
        #expect(names.contains("README.md"))
        #expect((listing.data?.arr("entries") ?? []).contains { $0.str("name") == "src" && $0.str("kind") == "dir" })

        let file = await rpc.request("rc_read_file", ["path": "README.md"])
        #expect(file.success)
        #expect(file.data?.str("content")?.contains("A new line.") == true)
        #expect(file.data?.bool("binary") == false)

        let secret = await rpc.request("rc_read_file", ["path": ".env"])
        #expect(!secret.success)
        let outside = await rpc.request("rc_read_file", ["path": "../outside.txt"])
        #expect(!outside.success)
        backend.close()
        rpc.stop()
    }

    /// A 1.1.6 computer's mobile UI kit (docs/mobile-ui.md). This app does not declare "views" (its renderer is for
    /// 0.2.0), so it gets no views; what reaches every phone anyway (a sheet, `views` and the open sheet in
    /// `remote_hello`) shows nothing, raises no alert and breaks nothing.
    @Test func ignoresTheMobileUIKitItDoesNotDeclare() async throws {
        let host = try await SharedFixture.shared.get()
        let (plainBackend, plain, plainLog) = try await connected(host)
        let (viewsBackend, views, viewsLog) = try await connected(host)
        let declared = await views.request("set_client_features", ["features": ["views"]])
        #expect(declared.success, "\(declared.error ?? "")")
        let hello = try #require(plainLog.all.first { $0.type == "remote_hello" })
        #expect(hello.strings("features").contains("views"))
        #expect(hello.arr("views") != nil)

        let mark = plainLog.all.count
        let viewsMark = viewsLog.all.count
        #expect(await plain.prompt("/panel").success)
        _ = try await viewsLog.waitFor(after: viewsMark) { $0.str("method") == "setView" && $0.str("viewKey") == "deploy" }
        #expect(!plainLog.all.dropFirst(mark).contains { ["setView", "updateView"].contains($0.str("method") ?? "") })

        let sheetDone = Task { await plain.prompt("/sheet") }
        let sheet = try await plainLog.waitFor(after: mark) { $0.str("method") == "showView" }
        #expect(plainLog.state.dialogs.isEmpty)
        #expect(SessionAlerts.alerts(before: plainLog.state, event: sheet, hostId: "h", hostName: "fixture").isEmpty)
        // A phone that connects now gets the open sheet among its dialogs: still nothing to show or to announce.
        let (lateBackend, late, lateLog) = try await connected(host)
        let lateHello = try #require(lateLog.all.first { $0.type == "remote_hello" })
        #expect((lateHello.arr("dialogs") ?? []).contains { $0.str("method") == "showView" })
        #expect(SessionReducer.fromHello(lateHello).dialogs.isEmpty)
        #expect(SessionReducer.missedWhileAway(plainLog.state, lateHello).isEmpty)
        // The sheet closes by itself; the session goes on.
        _ = try await plainLog.waitFor(after: mark) { $0.type == "extension_ui_dismissed" && $0.str("id") == sheet.str("id") }
        _ = await sheetDone.value
        #expect(plainLog.state.dialogs.isEmpty)
        #expect(await plain.getState().success)
        for (backend, rpc) in [(plainBackend, plain), (viewsBackend, views), (lateBackend, late)] {
            backend.close()
            rpc.stop()
        }
    }

    /// Last: it restarts the host's listener. URLSession reports the 1012 close as 1005 (no status: it knows only the
    /// close codes it names, and 4000-4999), so on iOS a handoff reads as a dropped connection. Both reconnect at once,
    /// which is what a handoff needs; only the Android app's "terminal took over" notice is missing.
    @Test func closeCode1012EndsTheConnectionAsAHandoff() async throws {
        let host = try await SharedFixture.shared.get()
        let (backend, rpc, _) = try await connected(host)
        let ended = Task { () -> ConnectionState? in
            for await state in backend.states() {
                if case .disconnected = state { return state }
            }
            return nil
        }
        try await host.command("handoff", answer: "handoff")
        let state = try #require(await ended.value)
        guard case let .disconnected(reason, _) = state else { return }
        #expect(reason == .handoff || reason == .lost, "\(state)")
        #expect(reason.retryable, "the app reconnects at once, to the same addresses")
        #expect(await backend.connect())
        backend.close()
        rpc.stop()
    }
}

final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    func increment() { lock.withLock { count += 1 } }
    var value: Int { lock.withLock { count } }
}

struct BackendLifecycleTests {
    @Test func aShutDownBackendRefusesToConnect() async {
        let backend = RemoteBackend(config: RemoteConfig(hostKey: KeyPair.generate().publicKey, endpoints: ["ws://127.0.0.1:1/rc"], identity: .generate(), deviceName: "t", appVersion: "0"))
        backend.shutdown()
        #expect(!(await backend.connect()))
        #expect(backend.state == .disconnected(reason: .closed, detail: nil))
    }

    @Test func aRefusedPortIsUnreachableAndRetryable() async {
        let backend = RemoteBackend(config: RemoteConfig(hostKey: KeyPair.generate().publicKey, endpoints: ["ws://127.0.0.1:1/rc", "not a url"], identity: .generate(), deviceName: "t", appVersion: "0"))
        #expect(!(await backend.connect()))
        guard case let .disconnected(reason, _) = backend.state else {
            Issue.record("expected disconnected")
            return
        }
        #expect(reason == .unreachable)
        #expect(reason.retryable)
        backend.shutdown()
    }
}
