import Foundation

public struct HostHello: Equatable, Sendable {
    public var name: String
    public var version: String
    public var paired: Bool
}

public enum DisconnectReason: String, Equatable, Sendable {
    /// The client closed the connection.
    case closed
    /// No endpoint answered.
    case unreachable
    /// The connection dropped after the handshake (network change, sleep, host crash).
    case lost
    /// 1001: Pi-Bolt stopped remote control.
    case hostStopped
    /// 1012: the background service handed the port to the computer's terminal (`/remote-control`). Reconnect at once.
    case handoff
    /// 4400: the host could not read the handshake or a message.
    case malformed
    /// 4403: this phone is not paired (revoked, or the pairing code was expired or used).
    case unauthorized
    /// 4408: the handshake took too long.
    case handshakeTimeout
    /// 4429: too many failed handshakes from this address.
    case rateLimited

    /// Worth retrying automatically.
    public var retryable: Bool { self == .unreachable || self == .lost || self == .handshakeTimeout || self == .handoff }

    public static func fromCloseCode(_ code: Int) -> DisconnectReason {
        switch code {
        case 1000: .closed
        case 1001: .hostStopped
        case 1012: .handoff
        case 4400: .malformed
        case 4403: .unauthorized
        case 4408: .handshakeTimeout
        case 4429: .rateLimited
        default: .lost
        }
    }
}

public enum ConnectionState: Equatable, Sendable {
    case idle
    case connecting(endpoint: String, attempt: Int)
    case connected(endpoint: String, host: HostHello)
    case disconnected(reason: DisconnectReason, detail: String?)

    public var isConnected: Bool {
        if case .connected = self { return true }
        return false
    }
}

public struct RemoteConfig: Sendable {
    /// Host static X25519 public key from the pairing code.
    public var hostKey: Data
    /// WebSocket endpoints in preference order: LAN first, then the tunnel.
    public var endpoints: [String]
    /// This install's static key pair.
    public var identity: KeyPair
    public var deviceName: String
    public var appVersion: String
    /// The pairing secret, only while this phone is not yet paired with the host.
    public var pairSecret: Data?
    /// Connect plus handshake budget for a LAN (`ws://`) endpoint, seconds.
    public var lanTimeout: TimeInterval = 3
    /// Connect plus handshake budget for a tunnel (`wss://`) endpoint, seconds.
    public var tunnelTimeout: TimeInterval = 15

    public init(hostKey: Data, endpoints: [String], identity: KeyPair, deviceName: String, appVersion: String, pairSecret: Data? = nil, lanTimeout: TimeInterval = 3, tunnelTimeout: TimeInterval = 15) {
        self.hostKey = hostKey
        self.endpoints = endpoints
        self.identity = identity
        self.deviceName = deviceName
        self.appVersion = appVersion
        self.pairSecret = pairSecret
        self.lanTimeout = lanTimeout
        self.tunnelTimeout = tunnelTimeout
    }

    /// The budget while pairing. The host spends the single-use secret when it reads handshake message 1, so a client
    /// that gives up before message 2 arrives (a slow first connection, iOS asking for Local Network access) has lost
    /// the code: give the pairing attempt time.
    public var pairingTimeout: TimeInterval = 20

    public func timeout(for endpoint: String) -> TimeInterval {
        let base = endpoint.hasPrefix("wss://") ? tunnelTimeout : lanTimeout
        return pairSecret == nil ? base : max(base, pairingTimeout)
    }
}

/// A value many tasks can watch: `states()` yields the current value, then every change.
final class Observed<T: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var current: T
    private var watchers: [UUID: AsyncStream<T>.Continuation] = [:]

    init(_ value: T) { current = value }

    var value: T {
        get { lock.withLock { current } }
        set {
            let targets = lock.withLock { () -> [AsyncStream<T>.Continuation] in
                current = newValue
                return Array(watchers.values)
            }
            for t in targets { t.yield(newValue) }
        }
    }

    func stream() -> AsyncStream<T> {
        let (stream, continuation) = AsyncStream<T>.makeStream(bufferingPolicy: .unbounded)
        let id = UUID()
        lock.withLock {
            watchers[id] = continuation
            continuation.yield(current)
        }
        continuation.onTermination = { [weak self] _ in
            guard let self else { return }
            _ = self.lock.withLock { self.watchers.removeValue(forKey: id) }
        }
        return stream
    }
}

/// Serializes async work, as Kotlin's `Mutex.withLock`.
actor AsyncLock {
    private var locked = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func lock() async {
        if !locked {
            locked = true
            return
        }
        await withCheckedContinuation { waiters.append($0) }
    }

    func unlock() {
        if waiters.isEmpty { locked = false } else { waiters.removeFirst().resume() }
    }
}

/// A result that is set once and awaited by one caller, with a deadline.
final class OneShot<T: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var result: T?
    private var waiter: CheckedContinuation<T?, Never>?

    var isCompleted: Bool { lock.withLock { result != nil } }

    /// True when this call set the result.
    @discardableResult func complete(_ value: T) -> Bool {
        let (set, w) = lock.withLock { () -> (Bool, CheckedContinuation<T?, Never>?) in
            guard result == nil else { return (false, nil) }
            result = value
            defer { waiter = nil }
            return (true, waiter)
        }
        w?.resume(returning: value)
        return set
    }

    /// The result, or nil when `timeout` seconds pass first or the task is cancelled.
    func wait(timeout: TimeInterval) async -> T? {
        let timer = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
            self?.expire()
        }
        defer { timer.cancel() }
        return await withTaskCancellationHandler {
            await withCheckedContinuation { (c: CheckedContinuation<T?, Never>) in
                lock.lock()
                if let result {
                    lock.unlock()
                    c.resume(returning: result)
                    return
                }
                waiter = c
                lock.unlock()
            }
        } onCancel: { [weak self] in
            self?.expire()
        }
    }

    private func expire() {
        let w: CheckedContinuation<T?, Never>? = lock.withLock {
            defer { waiter = nil }
            return waiter
        }
        w?.resume(returning: nil)
    }
}

/// A session on a computer, over remote control: WebSocket + Noise IK + chunked transport (docs/remote-control.md).
/// The Swift side of the Android app's `RemoteBackend.kt`, on `URLSessionWebSocketTask`.
///
/// `onPaired` runs once the host confirms that this connection paired the phone, so the caller can store the host and
/// stop sending the (single use) secret.
public final class RemoteBackend: NSObject, URLSessionWebSocketDelegate, @unchecked Sendable {
    static let maxHandshakeReply = 4096
    static let maxNoiseMessage = 65535
    static let readyMessage = Data(#"{"type":"remote_ready"}"#.utf8)
    /// The host pings every 20 s; the app pings too, so a dead link (Wi-Fi gone without a close) is noticed.
    static let pingInterval: TimeInterval = 20

    private let lock = NSLock()
    private var _config: RemoteConfig
    private let onPaired: @Sendable (HostHello) -> Void
    private let stateBox = Observed<ConnectionState>(.idle)
    private let connectLock = AsyncLock()
    private var session: URLSession!
    private var connections: [Int: Connection] = [:]
    private var live: Connection?
    private var pending: Connection?
    private var generation = 0
    private var attempt = 0
    private var _lastEndpoint: String?
    private var isShutDown = false

    /// Every JSON object from the session (events, responses, extension UI requests), in order. One consumer: the
    /// `RpcClient`.
    public let incoming: AsyncStream<JSON>
    private let inbox: AsyncStream<JSON>.Continuation

    public init(config: RemoteConfig, onPaired: @escaping @Sendable (HostHello) -> Void = { _ in }) {
        _config = config
        self.onPaired = onPaired
        (incoming, inbox) = AsyncStream<JSON>.makeStream(bufferingPolicy: .unbounded)
        super.init()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 15
        configuration.waitsForConnectivity = false
        let queue = OperationQueue()
        queue.maxConcurrentOperationCount = 1
        session = URLSession(configuration: configuration, delegate: self, delegateQueue: queue)
    }

    deinit {
        session.invalidateAndCancel()
    }

    public var config: RemoteConfig { lock.withLock { _config } }
    public var state: ConnectionState { stateBox.value }
    /// The current state, then every change.
    public func states() -> AsyncStream<ConnectionState> { stateBox.stream() }
    /// The endpoint of the current or last connection.
    public var lastEndpoint: String? { lock.withLock { _lastEndpoint } }

    /// Connect, or reconnect after a drop. Returns true when connected. Safe to call again after `close()`.
    public func connect() async -> Bool {
        await connectLock.lock()
        defer { Task { await connectLock.unlock() } }
        if lock.withLock({ live?.isOpen == true }) { return true }
        if lock.withLock({ isShutDown }) {
            stateBox.value = .disconnected(reason: .closed, detail: nil)
            return false
        }
        let cfg = config
        let startGeneration = lock.withLock { () -> Int in
            attempt += 1
            return generation
        }
        var lastFailure = ConnectionState.disconnected(reason: .unreachable, detail: "No address to try")
        for endpoint in cfg.endpoints {
            if Task.isCancelled { break }
            stateBox.value = .connecting(endpoint: endpoint, attempt: lock.withLock { attempt })
            guard let url = URL(string: endpoint), url.scheme == "ws" || url.scheme == "wss", url.host != nil else {
                lastFailure = .disconnected(reason: .unreachable, detail: "\(endpoint) is not a valid address")
                continue
            }
            // Created under the lock: a task from a session that `shutdown()` invalidated would raise.
            let created = lock.withLock { () -> Connection? in
                guard !isShutDown else { return nil }
                let connection = Connection(backend: self, endpoint: endpoint, url: url, config: cfg)
                pending = connection
                connections[connection.task.taskIdentifier] = connection
                return connection
            }
            guard let connection = created else {
                stateBox.value = .disconnected(reason: .closed, detail: nil)
                return false
            }
            connection.start()
            let result = await connection.handshake.wait(timeout: cfg.timeout(for: endpoint))
            var adopted = false
            defer {
                lock.withLock { if pending === connection { pending = nil } }
                if !adopted { connection.abandon() }
            }
            if lock.withLock({ generation }) != startGeneration { return false }
            switch result {
            case nil:
                lastFailure = .disconnected(reason: .unreachable, detail: "\(endpoint) did not answer")
            case let .ok(hello)?:
                if !connection.isOpen {
                    lastFailure = .disconnected(reason: .lost, detail: "\(endpoint) closed right after the handshake")
                    continue
                }
                adopted = true
                lock.withLock {
                    live = connection
                    _lastEndpoint = endpoint
                    if _config.pairSecret != nil { _config.pairSecret = nil }
                }
                if hello.paired { onPaired(hello) }
                stateBox.value = .connected(endpoint: endpoint, host: hello)
                connection.startPings()
                return true
            case let .failed(reason, detail)?:
                lastFailure = .disconnected(reason: reason, detail: detail)
                // Every endpoint reaches the same host: an authorization or protocol refusal will not change.
                if !reason.retryable { break }
            }
            if case let .disconnected(reason, _) = lastFailure, !reason.retryable { break }
        }
        stateBox.value = lastFailure
        return false
    }

    /// Queue one JSON object for the session. False when not connected.
    @discardableResult public func send(_ message: JSON) -> Bool {
        guard let connection = lock.withLock({ live }) else { return false }
        return connection.send(message.data)
    }

    /// Disconnect on purpose.
    public func close() {
        let (p, l) = lock.withLock { () -> (Connection?, Connection?) in
            generation += 1
            let result = (pending, live)
            pending = nil
            live = nil
            return result
        }
        p?.abandon()
        l?.closeByClient()
        stateBox.value = .disconnected(reason: .closed, detail: nil)
    }

    /// Close for good: the URLSession (which retains this backend as its delegate) is invalidated, so the backend can be
    /// released. `connect()` fails afterwards.
    public func shutdown() {
        lock.withLock { isShutDown = true }
        close()
        session.invalidateAndCancel()
    }

    /// Replace the endpoints, for example after `remote_endpoints` or a new pairing code from the same host.
    /// Ends the live connection as dropped (`lost`), so a reconnect loop connects again: for a link that may have died
    /// while the app was suspended. Nothing happens when there is no live connection.
    public func dropConnection() {
        lock.withLock { live }?.drop()
    }

    public func updateEndpoints(_ endpoints: [String]) {
        lock.withLock { _config.endpoints = endpoints }
    }

    // MARK: Connection callbacks

    fileprivate func deliver(_ message: JSON) {
        inbox.yield(message)
    }

    /// `connection` ended; `notify`: its first end, which reports the reason when it was the live connection.
    fileprivate func connectionEnded(_ connection: Connection, reason: DisconnectReason, detail: String?, notify: Bool) {
        let wasLive = lock.withLock { () -> Bool in
            connections[connection.task.taskIdentifier] = nil
            guard notify, live === connection else { return false }
            live = nil
            return true
        }
        if wasLive, !connection.closedByClient {
            stateBox.value = .disconnected(reason: reason, detail: detail)
        }
    }

    private func connection(for task: URLSessionTask) -> Connection? {
        lock.withLock { connections[task.taskIdentifier] }
    }

    // MARK: URLSessionWebSocketDelegate

    public func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        connection(for: webSocketTask)?.opened()
    }

    public func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        connection(for: webSocketTask)?.peerClosed(code: closeCode.rawValue, reason: reason.flatMap { String(data: $0, encoding: .utf8) })
    }

    public func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        connection(for: task)?.failed(error)
    }
}

enum AttemptResult: Sendable {
    case ok(HostHello)
    case failed(DisconnectReason, String?)
}

/// One WebSocket and its Noise state. Its receive loop is the only reader of `receiveCipher` and the reassembler; sends
/// encrypt and enqueue under one lock, so frame order matches nonce order.
private final class Connection: @unchecked Sendable {
    let endpoint: String
    let task: URLSessionWebSocketTask
    let handshake = OneShot<AttemptResult>()
    private weak var backend: RemoteBackend?
    private let noise: IkHandshake
    private let clientHello: JSON
    private let lock = NSLock()
    private var sendCipher: CipherState?
    private var receiveCipher: CipherState?
    private let reassembler = Reassembler()
    private var ended = false
    private var _closedByClient = false
    private var opened_ = false
    private var peerCloseCode: Int?
    private var pongPending = false
    private var receiver: Task<Void, Never>?
    private var pinger: Task<Void, Never>?

    init(backend: RemoteBackend, endpoint: String, url: URL, config: RemoteConfig) {
        self.backend = backend
        self.endpoint = endpoint
        noise = IkHandshake.initiator(localStatic: config.identity, remoteStatic: config.hostKey, prologue: noisePrologue)
        var hello: [String: JSON] = ["device": .string(config.deviceName), "app": .string(config.appVersion)]
        if let secret = config.pairSecret { hello["pair"] = .string(Base64Url.encode(secret)) }
        clientHello = .object(hello)
        task = backend.sessionForConnections.webSocketTask(with: url, protocols: [wsSubprotocol])
        // A transport frame is at most one Noise message; the larger limit lets an oversized frame reach the size
        // check below (and fail as malformed) instead of failing inside URLSession.
        task.maximumMessageSize = 1 << 20
    }

    var closedByClient: Bool { lock.withLock { _closedByClient } }
    var isOpen: Bool { lock.withLock { !ended && sendCipher != nil } }

    func start() {
        task.resume()
        receiver = Task { [weak self] in await self?.receiveLoop() }
    }

    func opened() {
        let first = lock.withLock { () -> Bool in
            defer { opened_ = true }
            return !opened_
        }
        guard first else { return }
        do {
            let message1 = try noise.writeMessage1(clientHello.data)
            task.send(.data(message1)) { _ in }
        } catch {
            fail(code: 4400, reason: .malformed, detail: "\(error)")
        }
    }

    private func receiveLoop() async {
        while true {
            let message: URLSessionWebSocketTask.Message
            do {
                message = try await task.receive()
            } catch {
                await receiveFailed(error)
                return
            }
            switch message {
            case let .data(data):
                if !handle(data) { return }
            case .string:
                fail(code: 4400, reason: .malformed, detail: "Text frame")
                return
            @unknown default:
                fail(code: 4400, reason: .malformed, detail: "Unknown frame")
                return
            }
        }
    }

    /// One binary frame; false when the connection ended.
    private func handle(_ data: Data) -> Bool {
        // Size checks before any work: handshake message 2 is a key, a tag and a small JSON payload; a transport frame
        // is at most one Noise message. An endpoint that is not the host cannot make us buffer more.
        let inHandshake = lock.withLock { receiveCipher == nil }
        let limit = inHandshake ? RemoteBackend.maxHandshakeReply : RemoteBackend.maxNoiseMessage
        if data.count > limit {
            fail(code: 4400, reason: .malformed, detail: "Frame too large")
            return false
        }
        if inHandshake {
            let hello: HostHello
            do {
                let payload = try noise.readMessage2(data)
                let ciphers = noise.split()
                let json = (try? JSON.parse(payload)).flatMap { $0.isObject ? $0 : nil } ?? [:]
                lock.withLock {
                    sendCipher = ciphers.send
                    receiveCipher = ciphers.receive
                }
                hello = HostHello(name: json.str("host") ?? "Computer", version: json.str("version") ?? "", paired: json.bool("paired") ?? false)
            } catch {
                fail(code: 4400, reason: .malformed, detail: "Bad handshake reply: \(error)")
                return false
            }
            // Key confirmation: the host sends remote_hello and accepts commands only after this first transport
            // message, so a replayed handshake message 1 gets nothing.
            if !send(RemoteBackend.readyMessage) {
                fail(code: 4400, reason: .lost, detail: "Could not confirm the handshake")
                return false
            }
            handshake.complete(.ok(hello))
            return true
        }
        do {
            let cipher = lock.withLock { receiveCipher! }
            let plaintext = try cipher.decrypt(data)
            guard let message = try reassembler.accept(plaintext) else { return true }
            let json = try JSON.parse(message)
            if json.isObject { backend?.deliver(json) }
            return true
        } catch {
            fail(code: 4400, reason: .malformed, detail: "Bad message: \(error)")
            return false
        }
    }

    func send(_ data: Data) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard let cipher = sendCipher, !ended else { return false }
        do {
            for frame in try Framing.seal(cipher, data) {
                task.send(.data(frame)) { _ in }
            }
        } catch {
            return false
        }
        return true
    }

    func startPings() {
        pinger = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: UInt64(RemoteBackend.pingInterval * 1_000_000_000))
                guard let self, !Task.isCancelled else { return }
                // The previous ping went unanswered for a whole interval: the link is dead.
                let missed = self.lock.withLock { () -> Bool in
                    if self.pongPending { return true }
                    self.pongPending = true
                    return false
                }
                if missed {
                    self.task.cancel()
                    self.ended(reason: .lost, detail: "No answer to ping")
                    return
                }
                self.task.sendPing { [weak self] error in
                    guard let self else { return }
                    if error == nil { self.lock.withLock { self.pongPending = false } }
                }
            }
        }
    }

    func closeByClient() {
        lock.withLock {
            _closedByClient = true
            ended = true
        }
        pinger?.cancel()
        task.cancel(with: .normalClosure, reason: nil)
    }

    func drop() {
        pinger?.cancel()
        task.cancel()
        ended(reason: .lost, detail: "Reconnecting")
    }

    func abandon() {
        lock.withLock {
            _closedByClient = true
            ended = true
        }
        pinger?.cancel()
        task.cancel()
        handshake.complete(.failed(.closed, nil))
    }

    func peerClosed(code: Int, reason: String?) {
        lock.withLock { peerCloseCode = code }
        closed(code: code, reason: reason)
    }

    func failed(_ error: Error?) {
        // didCompleteWithError follows didCloseWith for a clean close; only a close without one decides the reason.
        guard lock.withLock({ peerCloseCode == nil }) else { return }
        let code = task.closeCode.rawValue
        if code != 0 {
            closed(code: code, reason: nil)
            return
        }
        let reason: DisconnectReason = handshake.isCompleted ? .lost : .unreachable
        ended(reason: reason, detail: error?.localizedDescription)
    }

    private func receiveFailed(_ error: Error) async {
        // The close frame's code reaches the delegate around the time receive() fails: give it a moment, so a 4403
        // reads as a refusal rather than a dropped connection.
        for _ in 0 ..< 20 {
            if lock.withLock({ ended || peerCloseCode != nil }) { return }
            let code = task.closeCode.rawValue
            if code != 0 {
                closed(code: code, reason: nil)
                return
            }
            try? await Task.sleep(nanoseconds: 25_000_000)
        }
        failed(error)
    }

    /// The peer closed with `code`. An unknown code is a dropped connection that names it.
    private func closed(code: Int, reason: String?) {
        let mapped = DisconnectReason.fromCloseCode(code)
        let known = mapped != .lost || code == 1006
        ended(reason: mapped, detail: reason?.isEmpty == false ? reason : (known ? nil : "Closed with code \(code)"))
    }

    private func fail(code: Int, reason: DisconnectReason, detail: String?) {
        task.cancel(with: URLSessionWebSocketTask.CloseCode(rawValue: code) ?? .protocolError, reason: nil)
        ended(reason: reason, detail: detail)
    }

    private func ended(reason: DisconnectReason, detail: String?) {
        if !handshake.isCompleted {
            lock.withLock { ended = true }
            handshake.complete(.failed(reason, detail))
            backend?.connectionEnded(self, reason: reason, detail: detail, notify: false)
            return
        }
        let first = lock.withLock { () -> Bool in
            if ended { return false }
            ended = true
            return true
        }
        pinger?.cancel()
        backend?.connectionEnded(self, reason: reason, detail: detail, notify: first)
    }
}

extension RemoteBackend {
    fileprivate var sessionForConnections: URLSession { session }
}
