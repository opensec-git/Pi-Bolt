import Foundation

/// `SessionState.subagents` from what reaches the app of the subagents extension (opensec-pi-subagents). A port of the
/// Android app's `SubagentReducer.kt` (android/protocol), without the session files a phone's own engine reads:
/// - the `Agent` call's arguments (type, task, prompt, background);
/// - its partial results while a foreground run works: `details` carries the activity line, tool uses, turns, tokens
///   and run time (the run time is kept in whole seconds so the state changes less often);
/// - its final result: the answer, or for a background start the agent id;
/// - `subagent-notification` messages when background runs end (status, metrics, a preview of the answer);
/// - `get_subagent_result` results (the full answer of a background run, once Pi fetches it);
/// - from a computer with the `subagent_stream` feature, while a run's screen is open: its saved messages
///   (`subagent_entries`) and its streaming message (`subagent_events`).
public enum SubagentReducer {
    public static let notification = "subagent-notification"
    public static let interrupted = "subagents-interrupted"
    /// From the computer: messages of a subagent's session file, with `runId`.
    public static let entriesEvent = "subagent_entries"
    /// From the computer: a subagent's streaming message and tool starts (`events`).
    public static let eventsEvent = "subagent_events"

    public static func isAgentCall(_ name: String?) -> Bool { name?.lowercased() == "agent" }

    /// An `Agent` call is known (its arguments): the run exists from now on, queued until the tool starts (`started`).
    static func called(_ state: SessionState, _ id: String, _ name: String?, _ args: JSON?, started: Bool = false) -> SessionState {
        guard isAgentCall(name) else { return state }
        let old = state.subagents[id]
        var next = old ?? Subagent(key: id)
        if let args, args.isObject { next = withArgs(state, next, args) }
        if started, next.status == .queued { next.status = .running }
        return put(state, old, next)
    }

    private static func withArgs(_ state: SessionState, _ run: Subagent, _ args: JSON) -> Subagent {
        let resume = nonBlank(args.str("resume"))
        // A resumed run is the same agent: it keeps the type and task of the run it continues.
        let previous = resume.flatMap { id in state.subagents.last { $0.agentId == id && $0.key != run.key } }
        var r = run
        if run.type == "Agent" {
            r.type = nonBlank(args.str("subagent_type")) ?? previous?.type ?? run.type
        }
        if r.description.isEmpty { r.description = args.str("description")?.trimmingCharacters(in: .whitespacesAndNewlines) ?? previous?.description ?? "" }
        if r.prompt.isEmpty { r.prompt = args.str("prompt") ?? "" }
        r.background = run.background || args.bool("run_in_background") == true
        r.resumed = resume != nil
        r.agentId = run.agentId ?? resume
        return r
    }

    /// A partial result of a foreground run (`tool_execution_update`).
    static func progress(_ state: SessionState, _ id: String, _ name: String?, _ details: JSON?) -> SessionState {
        guard let details, details.isObject, isAgentCall(name) || state.subagents.contains(id) else { return state }
        let old = state.subagents[id]
        var run = withDetails(old ?? Subagent(key: id), details, whole: true)
        let activity = details.str("activity")?.trimmingCharacters(in: .whitespacesAndNewlines)
        let act = activity?.isEmpty == false ? activity : nil
        run.status = act?.hasPrefix("queued") == true ? .queued : .running
        run.activity = act ?? run.activity
        return put(state, old, run)
    }

    /// Fields every `details` of the Agent tool may carry. `whole` keeps the run time in whole seconds.
    private static func withDetails(_ run: Subagent, _ d: JSON, whole: Bool) -> Subagent {
        var r = run
        // The display name, unless it is the generic "Agent" (general-purpose): then the type itself.
        if let display = nonBlank(d.str("displayName")), display.lowercased() != "agent" {
            r.type = display
        } else if let type = nonBlank(d.str("subagentType")) {
            r.type = type
        }
        if let description = nonBlank(d.str("description")) { r.description = description.trimmingCharacters(in: .whitespacesAndNewlines) }
        r.model = nonBlank(d.str("modelName")) ?? run.model
        r.toolUses = max(run.toolUses, d.int("toolUses") ?? 0)
        if let turns = d.int("turnCount"), turns > 0 { r.turns = turns }
        r.maxTurns = d.int("maxTurns") ?? run.maxTurns
        if let tokens = d.str("tokens") { r.tokens = tokensText(tokens) ?? run.tokens }
        if let ms = d.long("durationMs"), ms > 0 { r.durationMs = whole ? ms / 1000 * 1000 : ms }
        r.agentId = nonBlank(d.str("agentId")) ?? run.agentId
        return r
    }

    /// A tool result (`tool_execution_end`, or a `toolResult` message in the history): an `Agent` call ended, or Pi
    /// fetched a background run's answer with `get_subagent_result`. `at` is the message's timestamp when known.
    static func ended(_ state: SessionState, _ id: String, _ name: String?, _ text: String, _ details: JSON?, _ isError: Bool, at: Int64?) -> SessionState {
        if isAgentCall(name) || (name == nil && state.subagents.contains(id)) { return agentEnded(state, id, text, details, isError, at) }
        if name == "get_subagent_result", !isError { return fetched(state, text) }
        return state
    }

    private static func agentEnded(_ state: SessionState, _ id: String, _ text: String, _ details: JSON?, _ isError: Bool, _ at: Int64?) -> SessionState {
        let old = state.subagents[id]
        var run = old ?? Subagent(key: id)
        let details = details?.isObject == true ? details : nil
        if let details { run = withDetails(run, details, whole: false) }
        let status = details?.str("status")
        if status == "background" {
            run.background = true
            run.status = text.contains("queued in background") ? .queued : .running
            run.activity = nil
            // The background start's result carries no run time: it starts about now.
            run.durationMs = nil
            run.startedAt = run.startedAt ?? at
        } else {
            let reported = statusOf(status).flatMap { $0.isActive ? nil : $0 }
            run.status = reported ?? (isError ? .failed : .done)
            run.activity = nil
            let answer = answerOf(text)
            if !answer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { run.result = answer }
            run.resultComplete = true
            if let error = details?.str("error") {
                run.error = error
            } else if isError, details == nil {
                run.error = text.trimmingCharacters(in: .whitespacesAndNewlines)
            }
            if run.startedAt == nil, let d = run.durationMs, let at { run.startedAt = at - d }
        }
        return put(state, old, run)
    }

    private static let agentLine = try! NSRegularExpression(pattern: #"(?m)^Agent: (\S+)\s*\nType: .*?\| Status: (\w+)"#)
    private static let conversationMarker = "\n\n--- Agent Conversation ---"

    /// `get_subagent_result`: "Agent: <id>", "Type: … | Status: …", …, "Description: …", a blank line, the answer.
    private static func fetched(_ state: SessionState, _ text: String) -> SessionState {
        let ns = text as NSString
        guard let m = agentLine.firstMatch(in: text, range: NSRange(location: 0, length: ns.length)) else { return state }
        let agentId = ns.substring(with: m.range(at: 1))
        guard let final = statusOf(ns.substring(with: m.range(at: 2))), !final.isActive,
              let run = state.subagents.last(where: { $0.agentId == agentId }),
              let afterDescription = text.range(of: "\nDescription: "),
              let bodyStart = text.range(of: "\n\n", range: afterDescription.lowerBound ..< text.endIndex)
        else { return state }
        var body = String(text[bodyStart.upperBound...])
        if let marker = body.range(of: conversationMarker) { body = String(body[..<marker.lowerBound]) }
        body = answerOf(body)
        if body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || body.hasPrefix("Agent is still") || body.hasPrefix("Agent was stopped and is still") { return state }
        var next = run
        next.status = final
        next.activity = nil
        next.result = body
        next.resultComplete = true
        return put(state, run, next)
    }

    /// A custom message of the extension: background runs ended, or the session's previous end interrupted some.
    static func custom(_ state: SessionState, _ customType: String?, _ details: JSON?, at: Int64?) -> SessionState {
        switch customType {
        case notification:
            guard let details, details.isObject else { return state }
            return notified(state, details, at)
        case interrupted:
            var s = state
            for e in details?.arr("ids") ?? [] {
                guard let id = e.primitiveText, let run = s.subagents.last(where: { $0.agentId == id && $0.isActive }) else { continue }
                var next = run
                next.status = .stopped
                next.activity = nil
                next.error = "Interrupted when the session ended"
                s = put(s, run, next)
            }
            return s
        default:
            return state
        }
    }

    /// The agent ids a notification's details name: the first, then `others` (a group of parallel runs).
    public static func notifiedIds(_ details: JSON?) -> [String] { notifications(details).compactMap { $0.str("id") } }

    private static func notifications(_ details: JSON?) -> [JSON] {
        guard let details, details.isObject else { return [] }
        return [details] + (details.arr("others") ?? []).filter(\.isObject)
    }

    private static func notified(_ state: SessionState, _ details: JSON, _ at: Int64?) -> SessionState {
        var s = state
        for d in notifications(details) {
            guard let id = d.str("id"), let run = s.subagents.last(where: { $0.agentId == id }) else { continue }
            let duration = d.long("durationMs").flatMap { $0 > 0 ? $0 : nil }
            let preview = d.str("resultPreview")?.trimmingCharacters(in: .whitespacesAndNewlines)
            let usefulPreview = (preview?.isEmpty == false && preview != "No output.") ? preview : nil
            var next = run
            next.status = statusOf(d.str("status")).flatMap { $0.isActive ? nil : $0 } ?? .done
            next.activity = nil
            next.toolUses = max(run.toolUses, d.int("toolUses") ?? 0)
            if let turns = d.int("turnCount"), turns > 0 { next.turns = turns }
            if let total = d.long("totalTokens"), total > 0 { next.tokens = SessionReducer.formatTokens(total) }
            next.durationMs = duration ?? run.durationMs
            if run.startedAt == nil, let duration, let at { next.startedAt = at - duration }
            next.error = d.str("error") ?? run.error
            if !run.resultComplete { next.result = usefulPreview ?? run.result }
            s = put(s, run, next)
        }
        return s
    }

    /// `subagent_entries`: `{"runId":…,"file":…,"messages":[…],"reset":true}`. `reset` (or another file) starts the
    /// transcript over; the message being streamed stays. A finished assistant message takes the streamed one's keys,
    /// so its rows stay in place. A run that reports no progress of its own (a background run) takes its activity and
    /// metrics from these messages.
    static func transcript(_ state: SessionState, _ event: JSON) -> SessionState {
        guard let key = event.str("key") ?? event.str("runId"), let old = state.subagents[key] else { return state }
        let file = event.str("file") ?? old.transcript?.file ?? ""
        let previous = old.transcript
        let base: SubagentTranscript
        if let previous, previous.file == file, event.bool("reset") != true {
            base = previous
        } else {
            var fresh = SubagentTranscript(file: file)
            fresh.stream = previous?.stream
            fresh.live = previous?.live ?? []
            fresh.liveSeq = previous?.liveSeq ?? 0
            base = fresh
        }
        var scratch = SessionState()
        scratch.items = base.items
        scratch.seq = base.seq
        var tokens = base.tokens
        var assistants = base.assistantMessages
        var firstAt = base.firstAt
        var stream = base.stream
        var live = base.live
        for message in event.arr("messages") ?? [] where message.isObject {
            if firstAt == nil { firstAt = message.long("timestamp") }
            var commitKey: String?
            if message.str("role") == "assistant" {
                assistants += 1
                if let u = message.obj("usage") { tokens += (u.long("input") ?? 0) + (u.long("output") ?? 0) + (u.long("cacheWrite") ?? 0) }
                commitKey = stream?.key
                stream = nil
                live = []
            }
            scratch = SessionReducer.commitMessage(scratch, message, key: commitKey)
        }
        var t = SubagentTranscript(file: file)
        t.items = scratch.items
        t.seq = scratch.seq
        t.tokens = tokens
        t.assistantMessages = assistants
        t.firstAt = firstAt
        t.stream = stream
        t.live = live
        t.liveSeq = base.liveSeq
        return withTranscript(state, old, t)
    }

    /// `subagent_events`: `{"runId":…,"events":[…]}`, the subagent's assistant message while it streams
    /// (`message_start`, `message_update`; `message_end` is left to the saved message, see `transcript`) and
    /// `tool_execution_start` (its call runs). Applied to `SubagentTranscript.live`, apart from the saved rows.
    static func events(_ state: SessionState, _ event: JSON) -> SessionState {
        guard let key = event.str("runId"), let old = state.subagents[key] else { return state }
        var t = old.transcript ?? SubagentTranscript(file: "")
        for e in event.arr("events") ?? [] where e.isObject {
            switch e.type {
            case "message_start":
                guard let message = e.obj("message"), message.str("role") == "assistant" else { continue }
                let s = SessionReducer.startStream(SessionState(), message, key: "live\(t.liveSeq)")
                t.stream = s.stream
                t.live = s.streaming
                t.liveSeq += 1
            case "message_update":
                var s = SessionState()
                s.stream = t.stream
                s.streaming = t.live
                var seq = t.liveSeq
                if s.stream == nil {
                    s = SessionReducer.startStream(s, [:], key: "live\(seq)")
                    seq += 1
                }
                s = SessionReducer.messageUpdate(s, e)
                t.stream = s.stream
                t.live = s.streaming
                t.liveSeq = seq
            case "tool_execution_start":
                guard let id = e.str("toolCallId") else { continue }
                t.items = t.items.map { item in
                    if var tool = item.tool, tool.toolCallId == id, tool.status == .pending || tool.status == .streaming {
                        tool.status = .running
                        return .tool(tool)
                    }
                    return item
                }
            default:
                continue
            }
        }
        return withTranscript(state, old, t)
    }

    private static func withTranscript(_ state: SessionState, _ old: Subagent, _ transcript: SubagentTranscript) -> SessionState {
        var run = old
        run.transcript = transcript
        run.startedAt = old.startedAt ?? transcript.firstAt
        let finished = transcript.items.filter { $0.tool.map { !$0.isActive } ?? false }.count
        run.toolUses = max(run.toolUses, finished)
        run.turns = max(run.turns, transcript.assistantMessages)
        if run.background || run.tokens == nil {
            run.tokens = transcript.tokens > 0 ? SessionReducer.formatTokens(transcript.tokens) : run.tokens
        }
        if run.isActive, run.background {
            run.status = .running
            run.activity = activityOf(transcript.allItems)
        }
        if !run.isActive, !run.resultComplete,
           let text = transcript.items.last(where: { $0.assistantText != nil })?.assistantText?.text.trimmingCharacters(in: .whitespacesAndNewlines),
           !text.isEmpty
        {
            run.result = text
            run.resultComplete = true
        }
        return put(state, old, run)
    }

    /// Pi stopped (`agent_settled`, or an idle snapshot): a foreground run still open was cut off with its turn.
    static func settled(_ state: SessionState) -> SessionState {
        guard state.subagents.values.contains(where: { !$0.background && $0.isActive }) else { return state }
        var s = state
        for run in state.subagents.values where !run.background && run.isActive {
            var next = run
            next.status = .stopped
            next.activity = nil
            s.subagents.put(next)
        }
        return s
    }

    /// A snapshot of the session it already shows (a reconnect): runs keep the transcripts streamed to them, and
    /// background runs the progress the history does not have.
    static func keep(_ before: SessionState, _ after: SessionState) -> SessionState {
        guard !before.subagents.isEmpty, let sessionId = after.sessionId, sessionId == before.sessionId else { return after }
        var s = after
        for run in after.subagents.values {
            guard let old = before.subagents[run.key] else { continue }
            var next = run
            if next.transcript == nil, old.transcript != nil { next.transcript = old.transcript }
            if next.background, next.isActive, old.isActive {
                next.activity = old.activity
                next.toolUses = max(next.toolUses, old.toolUses)
                next.turns = max(next.turns, old.turns)
                next.tokens = next.tokens ?? old.tokens
            }
            if next != run { s.subagents.put(next) }
        }
        return s
    }

    // MARK: Helpers

    public static func statusOf(_ status: String?) -> SubagentStatus? {
        switch status {
        case "queued": .queued
        case "running": .running
        case "completed", "steered": .done
        case "error", "aborted": .failed
        case "stopped": .stopped
        default: nil
        }
    }

    private static func nonBlank(_ s: String?) -> String? {
        guard let s, !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return s
    }

    /// "12.3k token" -> "12.3k"; empty -> nil.
    private static func tokensText(_ text: String) -> String? {
        var t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        for suffix in ["tokens", "token"] where t.hasSuffix(suffix) {
            t = String(t.dropLast(suffix.count))
            break
        }
        t = t.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }

    private static let resultNoise = [#"(?m)^Agent ID: .*$"#, #"(?m)^Agent completed in .*$"#, #"(?m)^Session file \(every turn of this agent\): .*$"#]
        .map { try! NSRegularExpression(pattern: $0) }

    /// A result's answer: without the stats headline of a completed foreground run (the card shows the run time and
    /// metrics), the resume hint and the session file line. Stops, aborts and failures keep their headline.
    public static func answerOf(_ text: String) -> String {
        var out = text
        for regex in resultNoise {
            out = regex.stringByReplacingMatches(in: out, range: NSRange(location: 0, length: (out as NSString).length), withTemplate: "")
        }
        return out.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static let toolVerbs = ["read": "reading", "bash": "running", "edit": "editing", "write": "writing", "grep": "searching", "find": "finding", "ls": "listing"]

    /// What a subagent does, from the end of its transcript: its open tool calls; the message it streams (thinking or
    /// writing); else "working…" (between messages: the model is on its next one).
    public static func activityOf(_ items: [ChatItem]) -> String {
        var open: [ToolItem] = []
        for item in items.reversed() {
            if let tool = item.tool {
                if tool.isActive { open.append(tool) } else { break }
            } else if item.assistantText == nil, item.thinking == nil {
                break
            }
        }
        if open.isEmpty {
            switch items.last {
            case let .thinking(t)?: return t.streaming ? "thinking…" : "working…"
            case let .assistant(t)?: return t.streaming ? "writing…" : "working…"
            default: return "working…"
            }
        }
        if open.count > 1 {
            var names: [String] = []
            for t in open where !names.contains(t.name.lowercased()) { names.append(t.name.lowercased()) }
            let single = names.count == 1 ? names[0] : nil
            let verb = single.flatMap { toolVerbs[$0] } ?? "running"
            return "\(verb) \(open.count) \(single == "read" ? "files" : "tools")…"
        }
        let tool = open[0]
        let verb = toolVerbs[tool.name.lowercased()] ?? "running \(tool.name)"
        let keys = ["command", "path", "file_path", "pattern"]
        guard let raw = keys.lazy.compactMap({ tool.args?.str($0) }).first else { return "\(verb)…" }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let line = trimmed.split(separator: "\n", omittingEmptySubsequences: false).first.map(String.init) ?? ""
        let arg: String = line.count > 60 ? String(line.prefix(59)) + "…" : line
        return "\(verb) \(arg)"
    }

    private static func put(_ state: SessionState, _ old: Subagent?, _ next: Subagent) -> SessionState {
        if old == next { return state }
        var s = state
        s.subagents.put(next)
        return s
    }

    /// The run a notification or result names by agent id: the newest one.
    public static func byAgentId(_ state: SessionState, _ agentId: String) -> Subagent? {
        state.subagents.last { $0.agentId == agentId }
    }
}
