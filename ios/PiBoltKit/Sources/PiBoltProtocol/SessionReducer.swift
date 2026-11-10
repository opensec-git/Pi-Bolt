import Foundation

/// Pure reducer: `remote_hello` snapshot + session events -> `SessionState`. Unknown types and fields are ignored.
/// A port of the Android app's `SessionReducer.kt` (android/protocol).
/// See docs/json.md (event stream) and docs/remote-control.md (`remote_hello`).
public enum SessionReducer {
    /// More content blocks than any real message has; a larger contentIndex from the wire is ignored.
    static let maxContentBlocks = 1024

    public static func reduce(_ state: SessionState, _ event: JSON) -> SessionState {
        switch event.type {
        case "remote_hello":
            return SubagentReducer.keep(state, keepTodos(state, fromHello(event)))
        case "agent_start":
            // A retried request starts a run: its backoff is over. The completed rows of the previous run leave the
            // todo panel.
            var s = state
            s.isRunning = true
            s.retry = nil
            s.todos = hideCompleted(state.todos)
            return s
        case "agent_settled":
            return settle(state)
        case "message_start":
            guard let message = event.obj("message") else { return state }
            return message.str("role") == "assistant" ? startStream(state, message) : state
        case "message_update":
            return messageUpdate(state, event)
        case "message_end":
            return messageEnd(state, event)
        case "tool_execution_start":
            return toolStart(state, event)
        case "tool_execution_update":
            return toolUpdate(state, event)
        case "tool_execution_end":
            return toolEnd(state, event)
        case "queue_update":
            var s = state
            s.queue = QueueState(steering: event.strings("steering"), followUp: event.strings("followUp"))
            return s
        case "compaction_start":
            var s = state
            s.isCompacting = true
            s.compactionReason = event.str("reason")
            return s
        case "compaction_end":
            return compactionEnd(state, event)
        case "auto_retry_start":
            var s = state
            s.retry = RetryInfo(
                attempt: event.int("attempt") ?? 1,
                maxAttempts: event.int("maxAttempts") ?? 1,
                delayMs: event.long("delayMs") ?? 0,
                error: event.str("errorMessage")
            )
            return s
        case "auto_retry_end":
            var s = state
            s.retry = nil
            if event.bool("success") == false { return notice(s, .error, "Retry failed", event.str("finalError") ?? "") }
            return s
        case "thinking_level_changed":
            var s = state
            s.thinkingLevel = event.str("level") ?? state.thinkingLevel
            return s
        case "session_info_changed":
            var s = state
            s.sessionName = event.str("name")
            return s
        case "extension_ui_request":
            return extensionRequest(state, event)
        case "extension_ui_dismissed":
            guard let id = event.str("id") else { return state }
            var s = state
            s.dialogs.removeAll { $0.id == id }
            return s
        case "extension_error":
            return notice(state, .error, "Extension error", event.str("error") ?? "")
        case "bash_execution_update":
            return shellOutput(state, event.str("delta") ?? "")
        case SubagentReducer.entriesEvent:
            return SubagentReducer.transcript(state, event)
        case SubagentReducer.eventsEvent:
            return SubagentReducer.events(state, event)
        default:
            return state
        }
    }

    /// Apply a `get_state` result (also the `state` field of `remote_hello`).
    public static func applyRpcState(_ state: SessionState, _ rpc: JSON?) -> SessionState {
        guard let rpc, rpc.isObject else { return state }
        var s = state
        s.model = ModelInfo.from(rpc.obj("model")) ?? state.model
        s.thinkingLevel = rpc.str("thinkingLevel") ?? state.thinkingLevel
        s.isRunning = rpc.bool("isStreaming") ?? state.isRunning
        s.isCompacting = rpc.bool("isCompacting") ?? state.isCompacting
        s.sessionName = rpc.str("sessionName")
        s.sessionId = rpc.str("sessionId") ?? state.sessionId
        s.sessionFile = rpc.str("sessionFile") ?? state.sessionFile
        return s
    }

    public static func fromHello(_ hello: JSON) -> SessionState {
        let host = hello.obj("host")
        var state = SessionState()
        state.hello = true
        state.host = HostInfo(
            name: host?.str("name") ?? "Computer",
            version: host?.str("version") ?? "",
            cwd: host?.str("cwd") ?? "",
            service: host?.bool("service") == true,
            parallelSessions: host?.bool("parallelSessions") == true,
            review: host?.bool("review") == true,
            features: Set((hello.arr("features") ?? []).compactMap(\.primitiveText))
        )
        state = applyRpcState(state, hello.obj("state"))
        // A long session sends only its newest messages: say so, or the transcript seems to start midway.
        let omitted = hello.long("omittedMessages") ?? 0
        if omitted > 0 {
            state = notice(state, .info, "Earlier messages are on the computer", "\(omitted) older \(omitted == 1 ? "message is" : "messages are") not shown here.")
        }
        for message in hello.arr("messages") ?? [] where message.isObject {
            // Each prompt in the history started a run, which hid the completed todos of the run before.
            if message.str("role") == "user" { state.todos = hideCompleted(state.todos) }
            state = commitMessage(state, message, key: nil)
        }
        if let streaming = hello.obj("streamingMessage"), streaming.str("role") == "assistant" {
            state = startStream(state, streaming)
        }
        if !state.isRunning {
            state = closeDanglingTools(state)
            state.todos = hideFinished(state.todos)
            state = SubagentReducer.settled(state)
        }
        state.dialogs = (hello.arr("dialogs") ?? []).compactMap(dialogFrom)
        state.commands = (hello.arr("commands") ?? []).compactMap(commandFrom)
        return state
    }

    /// What happened while the link was down, as the events that would have announced it: a reconnect's
    /// `remote_hello` shows the result but sends none of them. An `agent_settled` when the run `before` showed has
    /// ended in the same session, and each dialog the snapshot opens that `before` did not have. Empty for the first
    /// snapshot or another session's.
    public static func missedWhileAway(_ before: SessionState, _ hello: JSON) -> [JSON] {
        guard before.hello else { return [] }
        let state = hello.obj("state")
        guard let sessionId = state?.str("sessionId"), sessionId == before.sessionId else { return [] }
        var missed: [JSON] = []
        if before.isRunning, state?.bool("isStreaming") == false { missed.append(["type": "agent_settled"]) }
        let known = Set(before.dialogs.map(\.id))
        for d in hello.arr("dialogs") ?? [] {
            guard let id = d.str("id"), !known.contains(id), dialogFrom(d) != nil else { continue }
            missed.append(d.with("type", "extension_ui_request"))
        }
        return missed
    }

    /// Two consecutive `message_update` deltas of the same block as one (text, thinking or tool-call arguments), or
    /// nil when they cannot merge. Applying the merged event equals applying both.
    public static func mergeDeltas(_ first: JSON, _ second: JSON) -> JSON? {
        guard first.type == "message_update", second.type == "message_update",
              let a = first.obj("assistantMessageEvent"), let b = second.obj("assistantMessageEvent"),
              let kind = a.type, kind == b.type, mergeableDeltas.contains(kind), a.int("contentIndex") == b.int("contentIndex"),
              let da = a.str("delta"), let db = b.str("delta")
        else { return nil }
        // The later event's usage is the newer one.
        return second.with("assistantMessageEvent", b.with("delta", .string(da + db)))
    }

    static let mergeableDeltas: Set<String> = ["text_delta", "thinking_delta", "toolcall_delta"]

    public static func commandsFrom(_ data: JSON?) -> [SlashCommand] {
        (data?.arr("commands") ?? []).compactMap(commandFrom)
    }

    // MARK: Messages

    static func startStream(_ state: SessionState, _ message: JSON, key: String? = nil) -> SessionState {
        let k = key ?? "m\(state.seq)"
        let blocks = (message.arr("content") ?? []).enumerated().map { blockItem(k, $0.offset, $0.element, streaming: true) }
        var s = state
        s.seq += 1
        s.stream = StreamingMessage(key: k, blocks: blocks)
        s.streaming = blocks.compactMap { $0 }
        return s
    }

    static func messageUpdate(_ state: SessionState, _ event: JSON) -> SessionState {
        guard let delta = event.obj("assistantMessageEvent") else { return state }
        var s = state
        if s.stream == nil { s = startStream(s, [:]) }
        let stream = s.stream!
        let usage = event.obj("usage").map(usageFrom) ?? s.lastUsage
        // From the wire: a negative or absurd index is ignored rather than trapping or padding the list.
        guard let index = delta.int("contentIndex"), index >= 0, index < maxContentBlocks else {
            s.lastUsage = usage
            return s
        }
        var blocks = stream.blocks
        while blocks.count <= index { blocks.append(nil) }
        let current = blocks[index]
        let key = "\(stream.key).\(index)"
        let currentText: String = { if case let .assistant(t)? = current { return t.text }; return "" }()
        let currentThinking: String = { if case let .thinking(t)? = current { return t.text }; return "" }()
        let currentTool: ToolItem? = { if case let .tool(t)? = current { return t }; return nil }()
        let updated: ChatItem?
        switch delta.type {
        case "text_start":
            updated = .assistant(AssistantTextItem(key: key, text: "", streaming: true))
        case "text_delta":
            updated = .assistant(AssistantTextItem(key: key, text: currentText + (delta.str("delta") ?? ""), streaming: true))
        case "text_end":
            updated = .assistant(AssistantTextItem(key: key, text: delta.str("content") ?? currentText, streaming: true))
        case "thinking_start":
            updated = .thinking(ThinkingItem(key: key, text: "", streaming: true))
        case "thinking_delta":
            updated = .thinking(ThinkingItem(key: key, text: currentThinking + (delta.str("delta") ?? ""), streaming: true))
        case "thinking_end":
            updated = .thinking(ThinkingItem(key: key, text: delta.str("content") ?? currentThinking, streaming: false))
        case "toolcall_start":
            let id = delta.str("id") ?? key
            updated = .tool(ToolItem(key: "tool:\(id)", toolCallId: id, name: delta.str("toolName") ?? "tool", args: nil, status: .streaming))
        case "toolcall_delta":
            if var t = currentTool {
                t.argsText += delta.str("delta") ?? ""
                updated = .tool(t)
            } else {
                updated = current
            }
        case "toolcall_end":
            let call = delta.obj("toolCall")
            let id = call?.str("id") ?? currentTool?.toolCallId ?? key
            updated = .tool(ToolItem(
                key: "tool:\(id)",
                toolCallId: id,
                name: call?.str("name") ?? currentTool?.name ?? "tool",
                args: call?.obj("arguments"),
                argsText: currentTool?.argsText ?? "",
                status: .pending
            ))
        default:
            updated = current
        }
        blocks[index] = updated
        s.stream = StreamingMessage(key: stream.key, blocks: blocks)
        s.streaming = blocks.compactMap { $0 }
        s.lastUsage = usage
        return s
    }

    static func messageEnd(_ state: SessionState, _ event: JSON) -> SessionState {
        guard let message = event.obj("message") else { return state }
        if message.str("role") == "assistant" {
            let key = state.stream?.key
            var cleared = state
            cleared.stream = nil
            cleared.streaming = []
            if let usage = message.obj("usage") { cleared.lastUsage = usageFrom(usage) }
            return commitMessage(cleared, message, key: key)
        }
        return commitMessage(state, message, key: nil)
    }

    /// Add a finished message to `items`. `key` reuses the streaming key so rows keep their identity.
    static func commitMessage(_ state: SessionState, _ message: JSON, key: String?) -> SessionState {
        let k = key ?? "m\(state.seq)"
        var s = state
        if key == nil { s.seq += 1 }
        switch message.str("role") {
        case "user":
            let (text, images) = contentText(message["content"])
            if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, images == 0 { return s }
            s.items.append(.user(UserItem(key: k, text: text, imageCount: images, timestamp: message.long("timestamp"))))
            return s
        case "assistant":
            var items = s.items
            for (i, block) in (message.arr("content") ?? []).enumerated() {
                guard let item = blockItem(k, i, block, streaming: false) else { continue }
                if case let .tool(tool) = item {
                    s = SubagentReducer.called(s, tool.toolCallId, tool.name, tool.args)
                    // A tool call already known (from execution events) keeps its progress.
                    if let existing = items.lastIndex(where: { $0.tool?.toolCallId == tool.toolCallId }), var old = items[existing].tool {
                        old.name = tool.name
                        old.args = tool.args ?? old.args
                        items[existing] = .tool(old)
                        continue
                    }
                }
                if case let .assistant(t) = item, t.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { continue }
                items.append(item)
            }
            switch message.str("stopReason") {
            case "error":
                items.append(.notice(NoticeItem(key: "\(k).error", kind: .error, title: "Error", text: message.str("errorMessage") ?? "The model returned an error")))
            case "aborted":
                items.append(.notice(NoticeItem(key: "\(k).aborted", kind: .aborted, title: "Aborted", text: message.str("errorMessage") ?? "")))
            case "length":
                items.append(.notice(NoticeItem(key: "\(k).length", kind: .info, title: "Stopped at the output limit")))
            default:
                break
            }
            s.items = items
            return s
        case "toolResult":
            guard let id = message.str("toolCallId") else { return s }
            let (text, images) = contentText(message["content"])
            let result = images > 0
                ? [text, "[\(images) image\(images > 1 ? "s" : "")]"].filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }.joined(separator: "\n")
                : text
            let isError = message.bool("isError") ?? false
            let toolName = message.str("toolName")
            if toolName == todoTool, !isError { s.todos = applyTodo(s.todos, message.obj("details")) }
            s = SubagentReducer.ended(s, id, toolName, text, message.obj("details"), isError, at: message.long("timestamp"))
            return updateTool(s, id, toolName) {
                var t = $0
                t.status = isError ? .error : .done
                t.result = result
                t.details = message.obj("details") ?? t.details
                t.durationMs = t.durationMs ?? message.long("durationMs")
                t.images = contentImages(message["content"])
                return t
            }
        case "bashExecution":
            let exit = message.int("exitCode")
            let cancelled = message.bool("cancelled") ?? false
            let command = message.str("command") ?? ""
            s.items.append(.tool(ToolItem(
                key: "\(k).bash",
                toolCallId: "\(k).bash",
                name: "bash",
                args: ["command": .string(command)],
                status: cancelled ? .cancelled : (exit != nil && exit != 0 ? .error : .done),
                result: message.str("output") ?? ""
            )))
            return s
        case "custom":
            let customType = message.str("customType")
            s = SubagentReducer.custom(s, customType, message.obj("details"), at: message.long("timestamp"))
            if message.bool("display") == false { return s }
            let (text, _) = contentText(message["content"])
            if customType == SubagentReducer.notification {
                let ids = SubagentReducer.notifiedIds(message.obj("details"))
                let runs = ids.compactMap { SubagentReducer.byAgentId(s, $0)?.key }
                s.items.append(.subagentNotice(SubagentNoticeItem(key: k, agentIds: ids, text: text, runKeys: runs)))
            } else {
                s.items.append(.notice(NoticeItem(key: k, kind: .custom, title: customType ?? "Message", text: text)))
            }
            return s
        case "compactionSummary":
            s.items.append(.notice(NoticeItem(key: k, kind: .compaction, title: compactedTitle(message.long("tokensBefore"), nil), text: message.str("summary") ?? "")))
            return s
        case "branchSummary":
            s.items.append(.notice(NoticeItem(key: k, kind: .summary, title: "Branch summary", text: message.str("summary") ?? "")))
            return s
        default:
            return state
        }
    }

    private static func blockItem(_ messageKey: String, _ index: Int, _ block: JSON?, streaming: Bool) -> ChatItem? {
        guard let block, block.isObject else { return nil }
        let key = "\(messageKey).\(index)"
        switch block.type {
        case "text":
            return .assistant(AssistantTextItem(key: key, text: block.str("text") ?? "", streaming: streaming))
        case "thinking":
            let text = block.str("thinking") ?? ""
            let redacted = block.bool("redacted") ?? false
            if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !redacted { return nil }
            return .thinking(ThinkingItem(key: key, text: text, streaming: false, redacted: redacted))
        case "toolCall":
            let id = block.str("id") ?? key
            return .tool(ToolItem(key: "tool:\(id)", toolCallId: id, name: block.str("name") ?? "tool", args: block.obj("arguments"), status: streaming ? .streaming : .pending))
        default:
            return nil
        }
    }

    // MARK: Tools

    private static func toolStart(_ state: SessionState, _ event: JSON) -> SessionState {
        guard let id = event.str("toolCallId") else { return state }
        let withRun = SubagentReducer.called(state, id, event.str("toolName"), event.obj("args"), started: true)
        return updateTool(withRun, id, event.str("toolName")) {
            var t = $0
            t.status = .running
            t.args = event.obj("args") ?? t.args
            return t
        }
    }

    private static func toolUpdate(_ state: SessionState, _ event: JSON) -> SessionState {
        guard let id = event.str("toolCallId") else { return state }
        let (text, _) = contentText(event.obj("partialResult")?["content"])
        // The partial details too: a subagent reports what it is doing there.
        let details = event.obj("partialResult")?.obj("details")
        let withRun = SubagentReducer.progress(state, id, event.str("toolName"), details)
        return updateTool(withRun, id, event.str("toolName")) {
            var t = $0
            t.status = .running
            t.output = text
            t.details = details ?? t.details
            return t
        }
    }

    private static func toolEnd(_ state: SessionState, _ event: JSON) -> SessionState {
        guard let id = event.str("toolCallId") else { return state }
        let result = event.obj("result")
        let (text, _) = contentText(result?["content"])
        let isError = event.bool("isError") ?? false
        let name = event.str("toolName") ?? state.allItems.last(where: { $0.tool?.toolCallId == id })?.tool?.name
        var s = state
        if name == todoTool, !isError { s.todos = applyTodo(s.todos, result?.obj("details")) }
        s = SubagentReducer.ended(s, id, name, text, result?.obj("details"), isError, at: nil)
        return updateTool(s, id, event.str("toolName")) {
            var t = $0
            t.status = isError ? .error : .done
            t.result = text
            t.details = result?.obj("details") ?? t.details
            t.durationMs = event.long("durationMs") ?? t.durationMs
            t.images = contentImages(result?["content"])
            return t
        }
    }

    /// Update the tool row for `id`: in the finished items (searched from the end), the stream, or a new row.
    private static func updateTool(_ state: SessionState, _ id: String, _ name: String?, _ change: (ToolItem) -> ToolItem) -> SessionState {
        var s = state
        if let index = s.items.lastIndex(where: { $0.tool?.toolCallId == id }), let t = s.items[index].tool {
            s.items[index] = .tool(change(t))
            return s
        }
        if var stream = s.stream, let blockIndex = stream.blocks.firstIndex(where: { $0?.tool?.toolCallId == id }), let t = stream.blocks[blockIndex]?.tool {
            stream.blocks[blockIndex] = .tool(change(t))
            s.stream = stream
            s.streaming = stream.blocks.compactMap { $0 }
            return s
        }
        let fresh = ToolItem(key: "tool:\(id)", toolCallId: id, name: name ?? "tool", args: nil, status: .pending)
        s.items.append(.tool(change(fresh)))
        return s
    }

    private static func settle(_ state: SessionState) -> SessionState {
        var s = state
        s.isRunning = false
        s.retry = nil
        s.isCompacting = false
        s.compactionReason = nil
        s.todos = hideFinished(state.todos)
        if let stream = s.stream {
            // A stream without message_end (aborted mid-flight): keep what arrived.
            let blocks: [ChatItem] = stream.blocks.compactMap { $0 }.map {
                switch $0 {
                case var .assistant(t):
                    t.streaming = false
                    return .assistant(t)
                case var .thinking(t):
                    t.streaming = false
                    return .thinking(t)
                default:
                    return $0
                }
            }
            s.items += blocks
            s.stream = nil
            s.streaming = []
        }
        return SubagentReducer.settled(closeDanglingTools(s))
    }

    private static func closeDanglingTools(_ state: SessionState) -> SessionState {
        guard state.items.contains(where: { $0.tool?.isActive == true }) else { return state }
        var s = state
        s.items = s.items.map {
            if var t = $0.tool, t.isActive {
                t.status = .cancelled
                return .tool(t)
            }
            return $0
        }
        return s
    }

    // MARK: The user's shell (`!command`)

    /// Key prefix of a shell command the user ran from the app; its output streams in as `bash_execution_update`.
    public static let shellPrefix = "shell:"
    static let maxShellOutput = 64000

    private static func shellOutput(_ state: SessionState, _ delta: String) -> SessionState {
        guard !delta.isEmpty,
              let index = state.items.lastIndex(where: { $0.tool.map { $0.key.hasPrefix(shellPrefix) && $0.status == .running } ?? false }),
              var t = state.items[index].tool
        else { return state }
        var s = state
        t.output = String((t.output + delta).suffix(maxShellOutput))
        s.items[index] = .tool(t)
        return s
    }

    // MARK: Todos

    /// The todo extension's tool (opensec-pi-todo, rpiv-todo). Its result's `details.tasks` is the whole list after
    /// the call.
    public static let todoTool = "todo"

    /// The list from a `todo` result's details; ids completed and already hidden stay hidden.
    private static func applyTodo(_ todos: TodoState, _ details: JSON?) -> TodoState {
        guard let details, let list = details.arr("tasks") else { return todos }
        let tasks = list.compactMap(todoTask)
        let nextId = details.int("nextId") ?? todos.nextId
        // A cleared list numbers its tasks from 1 again: the hidden ids meant other tasks.
        let hidden = nextId < todos.nextId ? [] : todos.hidden
        let completed = Set(tasks.filter { $0.status == .completed }.map(\.id))
        return TodoState(tasks: tasks, hidden: hidden.intersection(completed), nextId: nextId)
    }

    /// One task, or nil for a deleted or malformed one.
    private static func todoTask(_ json: JSON) -> TodoTask? {
        guard json.isObject, let id = json.int("id"), let status = json.str("status").flatMap(TodoStatus.init(rawValue:)) else { return nil }
        let active = json.str("activeForm").flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
        return TodoTask(id: id, subject: json.str("subject") ?? "", status: status, activeForm: active)
    }

    private static func hideCompleted(_ todos: TodoState) -> TodoState {
        let completed = todos.tasks.filter { $0.status == .completed }.map(\.id)
        if todos.hidden.isSuperset(of: completed) { return todos }
        var t = todos
        t.hidden.formUnion(completed)
        return t
    }

    /// A run that ends with every task done closes the panel.
    private static func hideFinished(_ todos: TodoState) -> TodoState {
        !todos.tasks.isEmpty && todos.tasks.allSatisfy { $0.status == .completed } ? hideCompleted(todos) : todos
    }

    /// A snapshot of the session it already shows (a reconnect) without any todo result, as after a compaction dropped
    /// them from the messages, keeps the list it had.
    private static func keepTodos(_ before: SessionState, _ after: SessionState) -> SessionState {
        guard after.todos == TodoState(), before.todos != TodoState(), let id = after.sessionId, id == before.sessionId else { return after }
        var s = after
        s.todos = after.isRunning ? before.todos : hideFinished(before.todos)
        return s
    }

    // MARK: Compaction

    private static func compactionEnd(_ state: SessionState, _ event: JSON) -> SessionState {
        var s = state
        s.isCompacting = false
        s.compactionReason = nil
        let result = event.obj("result")
        let key = "m\(s.seq)"
        var next = s
        next.seq += 1
        let overflow = event.str("reason") == "overflow"
        if let result {
            next.items.append(.notice(NoticeItem(key: key, kind: .compaction, title: compactedTitle(result.long("tokensBefore"), result.long("estimatedTokensAfter")), text: result.str("summary") ?? "")))
            return next
        }
        // Only an overflow compaction ends the turn: a manual or threshold one failing is no model error.
        if event.bool("aborted") == true {
            next.items.append(.notice(NoticeItem(key: key, kind: overflow ? .aborted : .info, title: "Compaction aborted")))
            return next
        }
        if let error = event.str("errorMessage") {
            next.items.append(.notice(NoticeItem(key: key, kind: overflow ? .error : .info, title: "Compaction failed", text: error)))
            return next
        }
        return s
    }

    static func compactedTitle(_ before: Int64?, _ after: Int64?) -> String {
        if let before, let after { return "Compacted \(formatTokens(before)) to ~\(formatTokens(after)) tokens" }
        if let before { return "Compacted \(formatTokens(before)) tokens" }
        return "Compacted"
    }

    public static func formatTokens(_ n: Int64) -> String {
        if n >= 1_000_000 { return String(format: "%.1fM", Double(n) / 1_000_000) }
        if n >= 10000 { return "\(n / 1000)k" }
        if n >= 1000 { return String(format: "%.1fk", Double(n) / 1000) }
        return "\(n)"
    }

    // MARK: Extension UI

    private static func extensionRequest(_ state: SessionState, _ event: JSON) -> SessionState {
        var s = state
        switch event.str("method") {
        case "select", "confirm", "input", "editor":
            guard let dialog = dialogFrom(event), !state.dialogs.contains(where: { $0.id == dialog.id }) else { return state }
            s.dialogs.append(dialog)
        case "setStatus":
            guard let key = event.str("statusKey") else { return state }
            let text = event.str("statusText")
            if let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { s.statuses[key] = text } else { s.statuses[key] = nil }
        case "setWidget":
            guard let key = event.str("widgetKey") else { return state }
            let lines = event.arr("widgetLines")?.compactMap(\.primitiveText)
            if let lines, !lines.isEmpty { s.widgets[key] = lines } else { s.widgets[key] = nil }
        default:
            return state
        }
        return s
    }

    public static func dialogFrom(_ event: JSON) -> DialogRequest? {
        guard let id = event.str("id"), let method = event.str("method"), let kind = DialogKind(rawValue: method) else { return nil }
        return DialogRequest(
            id: id,
            kind: kind,
            title: event.str("title") ?? "",
            message: event.str("message"),
            options: event.strings("options"),
            placeholder: event.str("placeholder"),
            prefill: event.str("prefill"),
            timeoutMs: event.long("timeout")
        )
    }

    private static func commandFrom(_ json: JSON) -> SlashCommand? {
        guard let name = json.str("name") else { return nil }
        let hint = json.str("argumentHint").flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
        return SlashCommand(name: name, description: json.str("description"), source: json.str("source") ?? "", argumentHint: hint)
    }

    // MARK: Helpers

    private static func notice(_ state: SessionState, _ kind: NoticeKind, _ title: String, _ text: String) -> SessionState {
        var s = state
        s.items.append(.notice(NoticeItem(key: "m\(s.seq)", kind: kind, title: title, text: text)))
        s.seq += 1
        return s
    }

    private static func usageFrom(_ json: JSON) -> Usage {
        Usage(
            input: json.long("input") ?? 0,
            output: json.long("output") ?? 0,
            cacheRead: json.long("cacheRead") ?? 0,
            totalTokens: json.long("totalTokens") ?? 0,
            cost: json.obj("cost")?.double("total") ?? 0
        )
    }

    /// Text of a `string | (TextContent | ImageContent)[]` content field, and how many images it had.
    public static func contentText(_ content: JSON?) -> (String, Int) {
        switch content {
        case let .string(s)?:
            return (s, 0)
        case let .array(blocks)?:
            var images = 0
            var text = ""
            for b in blocks where b.isObject {
                switch b.type {
                case "text":
                    if !text.isEmpty { text += "\n" }
                    text += b.str("text") ?? ""
                case "image":
                    images += 1
                default:
                    break
                }
            }
            return (text, images)
        default:
            return ("", 0)
        }
    }

    /// The images of a content field (`{type: "image", data, mimeType}`), base64 as sent.
    public static func contentImages(_ content: JSON?) -> [ImageAttachment] {
        guard case let .array(blocks)? = content else { return [] }
        return blocks.compactMap { b in
            guard b.type == "image", let data = b.str("data") else { return nil }
            return ImageAttachment(base64: data, mimeType: b.str("mimeType") ?? "image/png")
        }
    }

    /// Parse one JSON line, for tests.
    public static func parse(_ line: String) -> JSON {
        (try? JSON.parse(line)) ?? .null
    }
}
