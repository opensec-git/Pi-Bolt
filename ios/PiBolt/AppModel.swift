import BackgroundTasks
import Foundation
import Observation
import PiBoltProtocol
import UIKit

/// The app's state across screens: this install's identity, the paired computers, and the one live session.
@MainActor
@Observable
final class AppModel {
    static let refreshTask = "in.opensec.pibolt.ios.refresh"

    @ObservationIgnored let identity: DeviceIdentity
    @ObservationIgnored let hosts: HostStore
    @ObservationIgnored let recents: RecentStore
    @ObservationIgnored let notifier: Notifier
    /// The open session with a computer, if any.
    private(set) var live: SessionController?
    /// The session screen is shown (Home's rows, a pairing, a notification's tap).
    var sessionShown = false
    /// A pairing link waiting for the user's confirmation (from the camera, the clipboard or a `pibolt://` link).
    var pendingLink: PairingPayload?
    var toast: Toast?
    /// The app is on the screen. Alerts are posted only while it is not.
    private(set) var isForeground = true

    let deviceName: String
    let appVersion: String

    @ObservationIgnored private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    @ObservationIgnored private var refreshScheduled = false
    @ObservationIgnored private let watchFile: URL

    init(identity: DeviceIdentity = DeviceIdentity(), hosts: HostStore? = nil, recents: RecentStore? = nil, notifier: Notifier? = nil, watchFile: URL? = nil) {
        self.identity = identity
        self.hosts = hosts ?? HostStore()
        self.recents = recents ?? RecentStore()
        self.notifier = notifier ?? Notifier()
        self.watchFile = watchFile ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("watch.json")
        // A Background App Refresh launch starts in the background, with no scene transition to say so.
        isForeground = UIApplication.shared.applicationState != .background
        deviceName = UIDevice.current.name
        appVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.0.0"
        self.notifier.onOpen = { [weak self] hostId, sessionId in self?.openFromNotification(hostId: hostId, sessionId: sessionId) }
    }

    /// A scanned, pasted or opened link: parsed and held for confirmation, or explained.
    func receive(link text: String) {
        do {
            let payload = try PairingPayload.parse(text)
            if hosts.get(payload.hostKeyId) == nil, payload.isExpired() {
                toast = Toast(text: "This code has expired. Run /remote-control or pi-bolt service pair for a new one.", kind: .error)
                return
            }
            pendingLink = payload
        } catch let error as PairingParseError {
            toast = Toast(text: error.description, kind: .error)
        } catch {
            toast = Toast(text: "Not a Pi-Bolt pairing code", kind: .error)
        }
    }

    /// Use a pairing code: pair with a new computer, or reconnect to a known one with the code's (possibly new)
    /// addresses. A known computer that no longer knows this phone is paired again with the code's secret.
    @discardableResult
    func pair(_ payload: PairingPayload) -> SessionController {
        let controller: SessionController
        if let known = hosts.get(payload.hostKeyId) {
            // Nothing in the stored host changes until the handshake proves the host key: the controller stores the
            // endpoints and name that worked once connected. The link's addresses are tried first.
            var endpoints = payload.endpoints
            for e in known.endpoints where !endpoints.contains(e) { endpoints.append(e) }
            controller = SessionController(app: self, hostId: known.id, hostName: known.name, endpoints: endpoints, pairSecret: nil, fallbackSecret: payload.isExpired() ? nil : payload.secret)
        } else {
            controller = SessionController(app: self, hostId: payload.hostKeyId, hostName: payload.hostName, endpoints: payload.endpoints, pairSecret: payload.secret)
        }
        replaceLive(controller)
        return controller
    }

    /// Open a session with a paired computer (the live one when it is that computer).
    @discardableResult
    func open(_ host: PairedHost) -> SessionController {
        if let live, live.hostId == host.id, !live.stopped { return live }
        let controller = SessionController(app: self, hostId: host.id, hostName: host.name, endpoints: host.endpoints, pairSecret: nil)
        replaceLive(controller)
        return controller
    }

    private func replaceLive(_ controller: SessionController) {
        live?.stop()
        live = controller
        controller.start()
    }

    func closeSession() {
        live?.stop()
        live = nil
    }

    /// A Recents row: that computer, then that session once connected (the background service switches to it).
    func openRecent(_ recent: RecentSession) {
        guard let host = hosts.get(recent.hostId) else {
            recents.remove(recent)
            toast = Toast(text: "This computer is no longer paired.", kind: .warning)
            return
        }
        let controller = open(host)
        controller.openWhenConnected(recent)
        sessionShown = true
    }

    /// A notification's tap: its computer's session, and the very session when Recents knows its file.
    func openFromNotification(hostId: String, sessionId: String?) {
        guard let host = hosts.get(hostId) else { return }
        let controller = open(host)
        if let sessionId, let recent = recents.sessions.first(where: { $0.hostId == hostId && $0.sessionId == sessionId }) {
            controller.openWhenConnected(recent)
        }
        sessionShown = true
    }

    /// Forget a computer on this phone. The computer still lists the phone until revoked in /remote-control devices.
    func forget(_ host: PairedHost) {
        if live?.hostId == host.id { closeSession() }
        hosts.remove(host.id)
        recents.removeHost(host.id)
    }

    /// A new device key: no paired computer knows it, so every computer is forgotten and has to be paired again.
    func resetDeviceKey() {
        closeSession()
        identity.reset()
        hosts.removeAll()
        recents.removeAll()
    }

    // MARK: Foreground and background

    // iOS suspends an app soon after it leaves the screen, and its connection with it; there is no push server here.
    // So: the link is kept for the short time iOS grants (a background task, about 30 seconds), long enough for a
    // quick run to end or ask; what the session showed is saved (`AlertWatch`); Background App Refresh, when iOS runs
    // it, reconnects briefly and posts what changed; and coming back to the app reconnects at once.

    func appBecameActive() {
        isForeground = true
        endBackgroundTask()
        saveWatch(nil)
        live?.resume()
    }

    func appEnteredBackground() {
        isForeground = false
        refreshScheduled = false
        let watch = live.flatMap { $0.stopped ? nil : $0.alertWatch }
        saveWatch(watch)
        if watch != nil { keepWatching() }
    }

    /// The session changed while the app was away: a run that starts (its `agent_start` or snapshot came after the
    /// app left) is watched from now on; one that ended with nothing left to answer needs no more time.
    func sessionChangedInBackground(_ controller: SessionController) {
        guard !isForeground, controller === live else { return }
        let watch = controller.alertWatch
        saveWatch(watch)
        if watch == nil { endBackgroundTask() } else { keepWatching() }
    }

    /// Background time while the link is up, and a refresh asked for once per stay in the background.
    private func keepWatching() {
        if backgroundTask == .invalid {
            // The expiration handler runs on the main thread, and the task must end before it returns.
            backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "pibolt.session") { [weak self] in
                MainActor.assumeIsolated { self?.endBackgroundTask() }
            }
        }
        if !refreshScheduled { scheduleRefresh() }
    }

    private func endBackgroundTask() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
    }

    /// Asks iOS for a refresh in 15 minutes at the earliest; iOS decides when (or whether) it runs.
    private func scheduleRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: Self.refreshTask)
        request.earliestBeginDate = Date().addingTimeInterval(15 * 60)
        refreshScheduled = (try? BGTaskScheduler.shared.submit(request)) != nil
    }

    /// Background App Refresh: reconnect to the watched session, post what changed, and ask for another refresh while
    /// it still works or waits for an answer. Bounded well inside the time iOS gives a refresh.
    func backgroundRefresh() async {
        // This refresh was the one asked for.
        refreshScheduled = false
        guard let watch = loadWatch() else { return }
        var next: AlertWatch?
        if let live, live.hostId == watch.hostId, !live.stopped {
            await live.checkInBackground(timeout: 20)
            next = live.alertWatch
        } else if let host = hosts.get(watch.hostId) {
            let config = RemoteConfig(hostKey: Base64Url.decode(host.id) ?? Data(), endpoints: host.endpoints, identity: identity.keyPair(), deviceName: deviceName, appVersion: appVersion)
            let result = await BackgroundCheck.run(watch: watch, config: config, timeout: 20)
            notifier.post(result.alerts)
            next = result.next
        }
        // The user opened the app meanwhile: it reconnects and shows everything itself.
        guard UIApplication.shared.applicationState != .active else { return }
        saveWatch(next)
        if next != nil { scheduleRefresh() }
    }

    private func saveWatch(_ watch: AlertWatch?) {
        guard let watch else {
            try? FileManager.default.removeItem(at: watchFile)
            return
        }
        try? FileManager.default.createDirectory(at: watchFile.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? JSONEncoder.iso.encode(watch).write(to: watchFile, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    private func loadWatch() -> AlertWatch? {
        guard let data = try? Data(contentsOf: watchFile) else { return nil }
        return try? JSONDecoder.iso.decode(AlertWatch.self, from: data)
    }
}
