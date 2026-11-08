import Foundation

/// A command's answer. `data` is the raw `data` field; typed helpers read it.
public struct RpcResponse: Equatable, Sendable {
    public var command: String
    public var success: Bool
    public var data: JSON?
    public var error: String?

    public static func failure(_ command: String, _ error: String) -> RpcResponse {
        RpcResponse(command: command, success: false, data: nil, error: error)
    }
}

public enum StreamingBehavior: String, Sendable {
    case steer
    case followUp
}

/// Pi's `ImageContent[]` for `prompt`, `steer` and `follow_up`: `{ type: "image", data: <base64>, mimeType }`.
public func imagesJSON(_ images: [ImageAttachment]) -> JSON {
    .array(images.map { ["type": "image", "data": .string($0.base64), "mimeType": .string($0.mimeType)] })
}

/// A model from `get_available_models`, `set_model` or the session state.
public struct ModelInfo: Equatable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var provider: String
    public var reasoning: Bool
    public var contextWindow: Int64?
    /// Input modalities (`["text", "image"]`), nil when the host did not say.
    public var input: [String]?

    public var label: String { name.isEmpty ? id : name }
    /// Whether the model reads images; nil when unknown.
    public var acceptsImages: Bool? { input.map { $0.contains("image") } }

    public static func from(_ json: JSON?) -> ModelInfo? {
        guard let json, json.isObject, let id = json.str("id") else { return nil }
        return ModelInfo(
            id: id,
            name: json.str("name") ?? id,
            provider: json.str("provider") ?? "",
            reasoning: json.bool("reasoning") ?? false,
            contextWindow: json["contextWindow"]?.primitiveText.flatMap { Int64($0) },
            input: json.arr("input")?.compactMap(\.primitiveText)
        )
    }

    public static func list(from response: RpcResponse) -> [ModelInfo] {
        (response.data?.arr("models") ?? []).compactMap(from)
    }
}

/// A folder with Pi-Bolt sessions on the computer, from the background service's `list_projects`.
public struct RemoteProject: Equatable, Sendable, Identifiable {
    public var cwd: String
    public var name: String
    public var sessions: Int
    public var updatedAt: String
    public var id: String { cwd }

    /// `data.projects`, newest first as sent. Entries without a `cwd` are dropped.
    public static func list(from response: RpcResponse) -> [RemoteProject] {
        (response.data?.arr("projects") ?? []).compactMap { o in
            guard let cwd = o.str("cwd"), !cwd.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
            let fallback = cwd.split(whereSeparator: { $0 == "/" || $0 == "\\" }).last.map(String.init) ?? cwd
            let name = o.str("name").flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 } ?? fallback
            return RemoteProject(cwd: cwd, name: name, sessions: o.int("sessions") ?? 0, updatedAt: o.str("updatedAt") ?? "")
        }
    }
}

/// A saved session on the computer, from `list_sessions`. `path` is what `switch_session` takes.
public struct RemoteSessionInfo: Equatable, Sendable, Identifiable {
    public var path: String
    public var sessionId: String
    public var cwd: String
    public var name: String?
    public var preview: String
    public var updatedAt: String
    public var messageCount: Int
    public var running: Bool = false
    public var open: Bool = false
    public var id: String { path }

    /// What a list shows: the session's name, else its first message, else its id.
    public var title: String {
        if let name, !name.trimmingCharacters(in: .whitespaces).isEmpty { return name }
        if !preview.trimmingCharacters(in: .whitespaces).isEmpty { return preview }
        return sessionId
    }

    /// `data.sessions`, newest first as sent. Entries without a `path` are dropped.
    public static func list(from response: RpcResponse) -> [RemoteSessionInfo] {
        (response.data?.arr("sessions") ?? []).compactMap { o in
            guard let path = o.str("path"), !path.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
            return RemoteSessionInfo(
                path: path,
                sessionId: o.str("id") ?? "",
                cwd: o.str("cwd") ?? "",
                name: o.str("name"),
                preview: o.str("preview") ?? "",
                updatedAt: o.str("updatedAt") ?? "",
                messageCount: o.int("messageCount") ?? 0,
                running: o.bool("running") == true,
                open: o.bool("open") == true
            )
        }
    }
}

/// An ISO-8601 instant (`Date.toISOString()`), or nil when it is missing or unreadable.
public func isoDate(_ text: String) -> Date? {
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    if let d = f.date(from: text) { return d }
    f.formatOptions = [.withInternetDateTime]
    return f.date(from: text)
}

/// Pi's RPC protocol over a `RemoteBackend`: commands with ids, responses matched to them, and everything else
/// (events, extension UI requests) on `events`.
public final class RpcClient: @unchecked Sendable {
    private let backend: RemoteBackend
    private let lock = NSLock()
    private var nextId: Int64 = 0
    private var pending: [String: CheckedContinuation<RpcResponse, Never>] = [:]
    private var pump: Task<Void, Never>?
    private var watcher: Task<Void, Never>?

    /// Events and extension UI requests in arrival order. Iterate from one place only.
    public let events: AsyncStream<JSON>
    private let eventSink: AsyncStream<JSON>.Continuation

    public init(backend: RemoteBackend) {
        self.backend = backend
        (events, eventSink) = AsyncStream<JSON>.makeStream()
        pump = Task { [weak self, backend] in
            for await message in backend.incoming {
                guard let self else { return }
                if message.type == "response", let id = message.str("id"), let waiter = self.take(id) {
                    waiter.resume(returning: RpcResponse(
                        command: message.str("command") ?? "",
                        success: message.bool("success") ?? false,
                        data: message["data"],
                        error: message.str("error")
                    ))
                    continue
                }
                self.eventSink.yield(message)
            }
        }
        watcher = Task { [weak self, backend] in
            for await state in backend.states() {
                guard let self else { return }
                if case .connected = state { continue }
                self.failPending("Disconnected")
            }
        }
    }

    private func take(_ id: String) -> CheckedContinuation<RpcResponse, Never>? {
        lock.lock()
        defer { lock.unlock() }
        return pending.removeValue(forKey: id)
    }

    private func failPending(_ reason: String) {
        lock.lock()
        let waiters = Array(pending.values)
        pending.removeAll()
        lock.unlock()
        for w in waiters { w.resume(returning: .failure("", reason)) }
    }

    public func stop() {
        pump?.cancel()
        watcher?.cancel()
        eventSink.finish()
        failPending("Stopped")
    }

    /// Send `type` with `fields`; waits up to `timeout` seconds (nil: until the connection drops) for the response.
    public func request(_ type: String, timeout: TimeInterval? = 30, _ fields: [String: JSON] = [:]) async -> RpcResponse {
        let id = lock.withLock { () -> String in
            nextId += 1
            return "i\(nextId)"
        }
        var command = fields
        command["id"] = .string(id)
        command["type"] = .string(type)
        let message = JSON.object(command)
        return await withCheckedContinuation { (continuation: CheckedContinuation<RpcResponse, Never>) in
            lock.withLock { pending[id] = continuation }
            if !backend.send(message) {
                take(id)?.resume(returning: .failure(type, "Not connected"))
                return
            }
            if let timeout {
                Task { [weak self] in
                    try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                    self?.take(id)?.resume(returning: .failure(type, "No answer from the computer"))
                }
            }
        }
    }

    // MARK: Prompting

    /// For an extension command (`/name`) the response arrives only after the command finishes, and its dialogs
    /// arrive before that: callers must not block anything else on this response. It waits until the connection drops.
    public func prompt(_ message: String, images: [ImageAttachment] = [], streamingBehavior: StreamingBehavior? = nil) async -> RpcResponse {
        var fields: [String: JSON] = ["message": .string(message)]
        if !images.isEmpty { fields["images"] = imagesJSON(images) }
        if let streamingBehavior { fields["streamingBehavior"] = .string(streamingBehavior.rawValue) }
        return await request("prompt", timeout: nil, fields)
    }

    public func steer(_ message: String) async -> RpcResponse { await request("steer", ["message": .string(message)]) }

    public func followUp(_ message: String) async -> RpcResponse { await request("follow_up", ["message": .string(message)]) }

    /// Abort waits until the session is idle, which can take a while.
    public func abort() async -> RpcResponse { await request("abort", timeout: 120) }

    public func clearQueue() async -> RpcResponse { await request("clear_queue") }

    public func getState() async -> RpcResponse { await request("get_state") }

    public func getMessages() async -> RpcResponse { await request("get_messages", timeout: 60) }

    public func getAvailableModels() async -> RpcResponse { await request("get_available_models") }

    public func setModel(provider: String, modelId: String) async -> RpcResponse {
        await request("set_model", ["provider": .string(provider), "modelId": .string(modelId)])
    }

    public func setThinkingLevel(_ level: String) async -> RpcResponse { await request("set_thinking_level", ["level": .string(level)]) }

    public func setSessionName(_ name: String) async -> RpcResponse { await request("set_session_name", ["name": .string(name)]) }

    /// Compaction runs a model call: give it time.
    public func compact() async -> RpcResponse { await request("compact", timeout: 600) }

    // MARK: Sessions (the background service, `host.service`)

    public func listProjects() async -> RpcResponse { await request("list_projects") }

    public func listSessions(cwd: String? = nil) async -> RpcResponse {
        await request("list_sessions", cwd.map { ["cwd": .string($0)] } ?? [:])
    }

    /// `data.cancelled` is true when an extension cancelled it. A `remote_hello` with the new session follows.
    public func switchSession(_ sessionPath: String) async -> RpcResponse {
        await request("switch_session", timeout: 60, ["sessionPath": .string(sessionPath)])
    }

    public func newSession(cwd: String? = nil) async -> RpcResponse {
        await request("new_session", timeout: 60, cwd.map { ["cwd": .string($0)] } ?? [:])
    }

    // MARK: Extension UI

    @discardableResult public func respond(_ id: String, value: String) -> Bool {
        backend.send(["type": "extension_ui_response", "id": .string(id), "value": .string(value)])
    }

    @discardableResult public func respond(_ id: String, confirmed: Bool) -> Bool {
        backend.send(["type": "extension_ui_response", "id": .string(id), "confirmed": .bool(confirmed)])
    }

    @discardableResult public func cancelDialog(_ id: String) -> Bool {
        backend.send(["type": "extension_ui_response", "id": .string(id), "cancelled": true])
    }
}
