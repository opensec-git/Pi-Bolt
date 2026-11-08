import Foundation
@testable import PiBoltProtocol
import Testing

/// The Android `SubagentReducerTest` (android/protocol): `SessionState.subagents`, runs of the subagents extension from
/// its tool calls, results and notifications, and the conversation a computer streams for one run. The Android tests
/// that feed a session file the phone read itself (`subagent_transcript`) feed the computer's `subagent_entries` here.
struct SubagentReducerTests {
    private func run(_ state: SessionState, _ lines: String...) -> SessionState {
        lines.reduce(state) { SessionReducer.reduce($0, SessionReducer.parse($1)) }
    }

    private func run(_ state: SessionState, _ lines: [String]) -> SessionState {
        lines.reduce(state) { SessionReducer.reduce($0, SessionReducer.parse($1)) }
    }

    private func q(_ text: String) -> String { JSON.string(text).text }

    private func args(_ type: String, _ description: String, _ prompt: String, background: Bool? = nil) -> String {
        #"{"subagent_type":"\#(type)","description":"\#(description)","prompt":"\#(prompt)""# + (background.map { #","run_in_background":\#($0)"# } ?? "") + "}"
    }

    private func call(_ calls: (String, String)...) -> String {
        let blocks = calls.map { #"{"type":"toolCall","id":"\#($0.0)","name":"Agent","arguments":\#($0.1)}"# }.joined(separator: ",")
        return #"{"role":"assistant","content":[\#(blocks)],"stopReason":"toolUse"}"#
    }

    private func result(_ id: String, _ text: String, _ details: String?, isError: Bool = false, name: String = "Agent", at: Int = 100_000) -> String {
        #"{"role":"toolResult","toolCallId":"\#(id)","toolName":"\#(name)","content":[{"type":"text","text":\#(q(text))}],"# + (details.map { #""details":\#($0),"# } ?? "") + #""isError":\#(isError),"timestamp":\#(at)}"#
    }

    private func hello(_ running: Bool, _ messages: [String] = []) -> String {
        #"{"type":"remote_hello","host":{"name":"phone","version":"1","cwd":"/p"},"state":{"isStreaming":\#(running),"sessionId":"s1"},"messages":[\#(messages.joined(separator: ","))]}"#
    }

    private func start(_ id: String, _ a: String) -> String {
        #"{"type":"tool_execution_start","toolCallId":"\#(id)","toolName":"Agent","args":\#(a)}"#
    }

    private func update(_ id: String, _ details: String) -> String {
        #"{"type":"tool_execution_update","toolCallId":"\#(id)","toolName":"Agent","partialResult":{"content":[{"type":"text","text":"2 tool uses..."}],"details":\#(details)}}"#
    }

    private func end(_ id: String, _ text: String, _ details: String?, isError: Bool = false) -> String {
        #"{"type":"tool_execution_end","toolCallId":"\#(id)","toolName":"Agent","result":{"content":[{"type":"text","text":\#(q(text))}]"# + (details.map { #","details":\#($0)"# } ?? "") + #"},"isError":\#(isError)}"#
    }

    private func notification(_ details: String, at: Int = 200_000) -> String {
        #"{"role":"custom","customType":"subagent-notification","content":"<task-notification>…</task-notification>","display":true,"details":\#(details),"timestamp":\#(at)}"#
    }

    private func entries(_ runId: String, reset: Bool?, _ messages: String...) -> String {
        #"{"type":"subagent_entries","runId":"\#(runId)","file":"/s/x.jsonl","# + (reset.map { #""reset":\#($0),"# } ?? "") + #""messages":[\#(messages.joined(separator: ","))]}"#
    }

    private let user = #"{"role":"user","content":"go"}"#
    private var explore: String { args("Explore", "Find auth files", "Find the authentication code") }
    private var bg: String { args("Explore", "Slow scan", "Scan slowly", background: true) }
    private var bgHistory: [String] { [user, call(("b1", bg)), result("b1", "Agent started in background.", #"{"status":"background","agentId":"bg-1"}"#)] }

    @Test func historyReplayBuildsFinishedRuns() throws {
        let s = run(SessionState(), hello(false, [
            user, call(("a1", explore)),
            result("a1", "Agent completed in 9.1s (4 tool uses, 1.2k token).\n\nAuth lives in src/auth.\n\nAgent ID: abc123 (pass it as `resume` to continue this agent)",
                   #"{"displayName":"Explore","description":"Find auth files","subagentType":"Explore","toolUses":4,"tokens":"1.2k token","turnCount":3,"durationMs":9100,"status":"completed","agentId":"abc123"}"#),
        ]))
        let agent = try #require(s.subagents["a1"])
        #expect(agent.status == .done)
        #expect(agent.type == "Explore")
        #expect(agent.description == "Find auth files")
        #expect(agent.prompt == "Find the authentication code")
        #expect(agent.agentId == "abc123")
        #expect(agent.result == "Auth lives in src/auth.")
        #expect(agent.toolUses == 4)
        #expect(agent.tokens == "1.2k")
        #expect(agent.turns == 3)
        #expect(agent.durationMs == 9100)
        #expect(agent.startedAt == Int64(100_000 - 9100))
        #expect(!agent.background)
    }

    @Test func liveUpdatesMoveTheRunAlong() throws {
        var s = run(SessionState(), #"{"type":"agent_start"}"#, #"{"type":"message_end","message":\#(call(("a1", explore)))}"#)
        #expect(s.subagents["a1"]?.status == .queued)
        s = run(s, start("a1", explore))
        #expect(s.subagents["a1"]?.status == .running)
        s = run(s, update("a1", #"{"displayName":"Explore","status":"running","activity":"queued — waiting for a foreground slot (1 ahead)","toolUses":0,"durationMs":40}"#))
        #expect(s.subagents["a1"]?.status == .queued)
        s = run(s, update("a1", #"{"displayName":"Explore","status":"running","activity":"reading 3 files…","toolUses":2,"turnCount":2,"tokens":"3.4k token","durationMs":4321,"spinnerFrame":3}"#))
        let running = try #require(s.subagents["a1"])
        #expect(running.status == .running)
        #expect(running.activity == "reading 3 files…")
        #expect(running.toolUses == 2)
        #expect(running.turns == 2)
        #expect(running.tokens == "3.4k")
        // Whole seconds: an update that only moves the clock within the second changes nothing.
        #expect(running.durationMs == 4000)
        let same = run(s, update("a1", #"{"displayName":"Explore","status":"running","activity":"reading 3 files…","toolUses":2,"turnCount":2,"tokens":"3.4k token","durationMs":4399,"spinnerFrame":4}"#))
        #expect(same.subagents == s.subagents)
        s = run(s, end("a1", "Agent completed in 6.0s (5 tool uses).\n\nDone it.\n\nAgent ID: id-1 (pass it as `resume` to continue this agent)", #"{"status":"completed","agentId":"id-1","toolUses":5,"durationMs":6012}"#))
        let done = try #require(s.subagents["a1"])
        #expect(done.status == .done)
        #expect(done.activity == nil)
        #expect(done.result == "Done it.")
        #expect(done.durationMs == 6012)
        #expect(done.agentId == "id-1")
    }

    @Test func parallelRunsStayApart() {
        let plan = args("Plan", "Plan the change", "Plan it")
        let general = args("general-purpose", "Write tests", "Write the tests")
        var s = run(SessionState(), #"{"type":"agent_start"}"#, #"{"type":"message_end","message":\#(call(("a1", explore), ("a2", plan), ("a3", general)))}"#)
        #expect(s.subagents.keys == ["a1", "a2", "a3"])
        s = run(s,
                start("a1", explore), start("a2", plan), start("a3", general),
                update("a2", #"{"status":"running","activity":"thinking…","toolUses":1}"#),
                end("a1", "Agent completed in 1s.\n\nA", #"{"status":"completed","agentId":"x1"}"#))
        #expect(s.subagents["a1"]?.status == .done)
        #expect(s.subagents["a2"]?.status == .running)
        #expect(s.subagents["a2"]?.activity == "thinking…")
        #expect(s.subagents["a3"]?.status == .running)
        #expect(s.subagents["a2"]?.type == "Plan")
        // Pi stops: the foreground runs still open were cut off with the turn.
        s = run(s, #"{"type":"agent_settled"}"#)
        #expect(s.subagents["a1"]?.status == .done)
        #expect(s.subagents["a2"]?.status == .stopped)
        #expect(s.subagents["a3"]?.status == .stopped)
    }

    @Test func failuresKeepTheirError() {
        var s = run(SessionState(), start("a1", explore))
        s = run(s, end("a1", "Agent failed: model exploded\n\nSession file (every turn of this agent): /x.jsonl\n\nAgent ID: f1 (pass it as `resume` to continue this agent)", #"{"status":"error","error":"model exploded","agentId":"f1"}"#, isError: true))
        #expect(s.subagents["a1"]?.status == .failed)
        #expect(s.subagents["a1"]?.error == "model exploded")
        #expect(s.subagents["a1"]?.result == "Agent failed: model exploded")
        // A call the extension refused outright has no details: its text is the error.
        s = run(s, start("a2", args("Nope", "x", "y")), end("a2", "Unknown or disabled agent type: \"Nope\".", nil, isError: true))
        #expect(s.subagents["a2"]?.status == .failed)
        #expect(s.subagents["a2"]?.error == "Unknown or disabled agent type: \"Nope\".")
        let stopped = run(s, start("a3", explore), end("a3", "Agent was stopped by the user after 2s.\n\npartial", #"{"status":"stopped","agentId":"s3"}"#, isError: true))
        #expect(stopped.subagents["a3"]?.status == .stopped)
    }

    @Test func backgroundRunsEndThroughTheirNotification() throws {
        var s = run(SessionState(),
                    start("b1", bg),
                    end("b1", "Agent started in background.\nAgent ID: bg-1\nType: Explore\n", #"{"displayName":"Explore","status":"background","agentId":"bg-1","toolUses":0,"tokens":"","durationMs":0}"#),
                    #"{"type":"agent_settled"}"#)
        let started = try #require(s.subagents["b1"])
        #expect(started.background)
        #expect(started.agentId == "bg-1")
        // Settling the main turn does not stop a background run.
        #expect(started.status == .running)
        #expect(started.tokens == nil)
        s = run(s, #"{"type":"message_end","message":\#(notification(#"{"id":"bg-1","description":"Slow scan","status":"completed","toolUses":7,"turnCount":0,"totalTokens":15200,"durationMs":61000,"resultPreview":"Found 3 files."}"#))}"#)
        let done = try #require(s.subagents["b1"])
        #expect(done.status == .done)
        #expect(done.toolUses == 7)
        #expect(done.tokens == "15k")
        #expect(done.durationMs == 61000)
        #expect(done.result == "Found 3 files.")
        guard case let .subagentNotice(notice)? = s.items.last else { Issue.record("no notice"); return }
        #expect(notice.agentIds == ["bg-1"])
        #expect(notice.runKeys == ["b1"])
        // A later resume of the same agent is another run: the notice still names the run it ended.
        let resumed = run(s, start("b2", #"{"resume":"bg-1","prompt":"again"}"#))
        #expect(resumed.subagents["b2"]?.agentId == "bg-1")
        guard case let .subagentNotice(still)? = resumed.items.last(where: { if case .subagentNotice = $0 { true } else { false } }) else { Issue.record("no notice"); return }
        #expect(still.runKeys == ["b1"])
        // Pi fetches the full answer: it replaces the preview.
        s = run(s, #"{"type":"message_end","message":\#(result("r1", "Agent: bg-1\nType: Explore | Status: completed | Tool uses: 7 | Duration: 1m 1s\nDescription: Slow scan\n\nFound 3 files.\n\n- a\n- b\n- c", nil, name: "get_subagent_result"))}"#)
        #expect(s.subagents["b1"]?.result == "Found 3 files.\n\n- a\n- b\n- c")
    }

    @Test func aGroupNotificationEndsEveryRunItNames() {
        var s = SessionState()
        for i in 1 ... 3 {
            s = run(s, start("b\(i)", args("Explore", "Task \(i)", "p\(i)", background: true)), end("b\(i)", "Agent started in background.", #"{"status":"background","agentId":"g\#(i)"}"#))
        }
        s = run(s, #"{"type":"message_end","message":\#(notification(#"{"id":"g1","status":"completed","resultPreview":"one","others":[{"id":"g2","status":"error","error":"boom"},{"id":"g3","status":"stopped"}]}"#))}"#)
        #expect(s.subagents["b1"]?.status == .done)
        #expect(s.subagents["b2"]?.status == .failed)
        #expect(s.subagents["b2"]?.error == "boom")
        #expect(s.subagents["b3"]?.status == .stopped)
        guard case let .subagentNotice(notice)? = s.items.last else { Issue.record("no notice"); return }
        #expect(notice.agentIds == ["g1", "g2", "g3"])
    }

    @Test func transcriptFromTheComputer() throws {
        var s = run(SessionState(), start("b1", bg), end("b1", "Agent started in background.", #"{"status":"background","agentId":"bg-1"}"#))
        s = run(s, entries("b1", reset: true,
                           #"{"role":"user","content":"Scan slowly","timestamp":1000}"#,
                           #"{"role":"assistant","content":[{"type":"thinking","thinking":"Let me look."},{"type":"toolCall","id":"t1","name":"read","arguments":{"path":"src/a.kt"}}],"usage":{"input":100,"output":20,"cacheWrite":5},"timestamp":1100}"#))
        var agent = try #require(s.subagents["b1"])
        let t = try #require(agent.transcript)
        #expect(t.file == "/s/x.jsonl")
        if case .user = t.items.first {} else { Issue.record("first row is not the prompt") }
        #expect(t.items.last?.tool?.status == .pending)
        #expect(agent.activity == "reading src/a.kt")
        #expect(agent.tokens == "125")
        #expect(agent.startedAt == 1000)
        // More messages of the same file are appended; their tool results close the calls.
        s = run(s, entries("b1", reset: nil,
                           #"{"role":"toolResult","toolCallId":"t1","toolName":"read","content":[{"type":"text","text":"x"}],"isError":false}"#,
                           #"{"role":"assistant","content":[{"type":"text","text":"All good."}],"usage":{"input":200,"output":10}}"#))
        agent = try #require(s.subagents["b1"])
        #expect(agent.transcript?.items.compactMap(\.tool).map(\.status) == [.done])
        #expect(agent.toolUses == 1)
        #expect(agent.turns == 2)
        // Between messages nothing names what it does: it works on its next message.
        #expect(agent.activity == "working…")
        #expect(agent.result == nil)
        // Done: the last reply in the transcript is the whole answer, longer than a notification's preview.
        s = run(s, #"{"type":"message_end","message":\#(notification(#"{"id":"bg-1","status":"completed","resultPreview":"All…"}"#))}"#)
        #expect(s.subagents["b1"]?.result == "All…")
        s = run(s, entries("b1", reset: nil))
        #expect(s.subagents["b1"]?.result == "All good.")
        #expect(s.subagents["b1"]?.transcript?.items.contains { $0.assistantText?.text == "All good." } == true)
    }

    @Test func aReconnectKeepsTranscriptsAndBackgroundProgress() {
        var s = run(SessionState(), hello(false, bgHistory))
        #expect(s.subagents["b1"]?.status == .running)
        s = run(s, entries("b1", reset: true, #"{"role":"user","content":"Scan slowly"}"#))
        let again = run(s, hello(false, bgHistory))
        #expect(again.subagents["b1"]?.transcript == s.subagents["b1"]?.transcript)
        #expect(again.subagents["b1"]?.activity == "working…")
        // An idle snapshot of an open foreground call: it ended with its turn.
        let cut = run(SessionState(), hello(false, [user, call(("a1", explore))]))
        #expect(cut.subagents["a1"]?.status == .stopped)
        let live = run(SessionState(), hello(true, [user, call(("a1", explore))]))
        #expect(live.subagents["a1"]?.status == .queued)
    }

    @Test func aResumeContinuesTheSameAgent() throws {
        var s = run(SessionState(), start("a1", explore), end("a1", "Agent completed in 1s.\n\nfirst", #"{"status":"completed","agentId":"r-1","displayName":"Explore"}"#))
        s = run(s, start("a2", #"{"resume":"r-1","prompt":"Go deeper"}"#))
        let resumed = try #require(s.subagents["a2"])
        #expect(resumed.resumed)
        #expect(resumed.agentId == "r-1")
        #expect(resumed.type == "Explore")
        #expect(resumed.description == "Find auth files")
        #expect(resumed.prompt == "Go deeper")
        #expect(SubagentReducer.byAgentId(s, "r-1")?.agentId == "r-1")
    }

    @Test func activityNamesOpenToolCalls() {
        func tool(_ name: String, _ args: String, _ status: ToolStatus, id: String? = nil) -> ChatItem {
            .tool(ToolItem(key: "tool:\(name)", toolCallId: id ?? name, name: name, args: SessionReducer.parse(args), status: status))
        }
        #expect(SubagentReducer.activityOf([tool("bash", #"{"command":"npm test\nmore"}"#, .pending)]) == "running npm test")
        #expect(SubagentReducer.activityOf([tool("read", #"{"path":"a"}"#, .pending), tool("read", #"{"path":"b"}"#, .pending, id: "r2")]) == "reading 2 files…")
        #expect(SubagentReducer.activityOf([tool("bash", #"{"command":"ls"}"#, .done)]) == "working…")
        #expect(SubagentReducer.activityOf([.thinking(ThinkingItem(key: "t", text: "hm", streaming: true))]) == "thinking…")
        #expect(SubagentReducer.activityOf([.assistant(AssistantTextItem(key: "x", text: "Here", streaming: true))]) == "writing…")
    }

    @Test func theComputerAdvertisesSubagentStreams() {
        let old = run(SessionState(), hello(false))
        #expect(old.host?.streamsSubagents == false)
        let new = run(SessionState(), #"{"type":"remote_hello","host":{"name":"mac","version":"1.1.5","cwd":"/p"},"features":["subagent_stream","later"],"state":{},"messages":[]}"#)
        #expect(new.host?.streamsSubagents == true)
        #expect(new.host?.features == ["subagent_stream", "later"])
    }

    @Test func streamedMessageShowsLiveThenTakesItsSavedPlace() throws {
        var s = run(SessionState(), hello(true, bgHistory))
        s = run(s, #"{"type":"subagent_entries","runId":"b1","file":"/c/x.jsonl","reset":true,"messages":[{"role":"user","content":"Scan slowly"}]}"#)
        #expect(s.subagents["b1"]?.transcript?.items.count == 1)
        // Its message streams: thinking, then text.
        s = run(s, """
        {"type":"subagent_events","runId":"b1","events":[
            {"type":"message_start","message":{"role":"assistant","content":[]}},
            {"type":"message_update","assistantMessageEvent":{"type":"thinking_start","contentIndex":0}},
            {"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","contentIndex":0,"delta":"Let me "}},
            {"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","contentIndex":0,"delta":"look."}}
        ]}
        """)
        var t = try #require(s.subagents["b1"]?.transcript)
        #expect(t.live.count == 1)
        let thinking = try #require(t.live.first?.thinking)
        #expect(thinking.text == "Let me look.")
        #expect(thinking.streaming)
        #expect(s.subagents["b1"]?.activity == "thinking…")
        s = run(s, """
        {"type":"subagent_events","runId":"b1","events":[
            {"type":"message_update","assistantMessageEvent":{"type":"thinking_end","contentIndex":0,"content":"Let me look."}},
            {"type":"message_update","assistantMessageEvent":{"type":"text_start","contentIndex":1}},
            {"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":1,"delta":"Two files"}},
            {"type":"message_end","message":{"role":"assistant","content":[]}}
        ]}
        """)
        t = try #require(s.subagents["b1"]?.transcript)
        #expect(s.subagents["b1"]?.activity == "writing…")
        let liveKeys = t.live.map(\.key)
        // message_end leaves it: the saved message replaces it, with the same row keys.
        #expect(t.live.count == 2)
        s = run(s, #"{"type":"subagent_entries","runId":"b1","file":"/c/x.jsonl","reset":false,"messages":[{"role":"assistant","content":[{"type":"thinking","thinking":"Let me look."},{"type":"text","text":"Two files"}]}]}"#)
        t = try #require(s.subagents["b1"]?.transcript)
        #expect(t.live.isEmpty)
        #expect(t.stream == nil)
        #expect(Array(t.items.dropFirst()).map(\.key) == liveKeys)
        #expect(t.items.last?.assistantText?.text == "Two files")
        // Keys of streamed and saved messages never meet.
        #expect(Set(t.items.map(\.key)).count == t.items.count)
    }

    @Test func aResetKeepsTheStreamAndAToolStartMarksItsCallRunning() throws {
        var s = run(SessionState(), hello(true, bgHistory))
        s = run(s, #"{"type":"subagent_entries","runId":"b1","file":"/c/x.jsonl","reset":true,"messages":[{"role":"user","content":"Scan"},{"role":"assistant","content":[{"type":"toolCall","id":"t1","name":"bash","arguments":{"command":"ls"}}]}]}"#)
        #expect(s.subagents["b1"]?.transcript?.items.compactMap(\.tool).map(\.status) == [.pending])
        s = run(s, #"{"type":"subagent_events","runId":"b1","events":[{"type":"tool_execution_start","toolCallId":"t1","toolName":"bash","args":{"command":"ls"}}]}"#)
        #expect(s.subagents["b1"]?.transcript?.items.compactMap(\.tool).map(\.status) == [.running])
        s = run(s, #"{"type":"subagent_events","runId":"b1","events":[{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"Hi"}}]}"#)
        s = run(s, #"{"type":"subagent_entries","runId":"b1","file":"/c/x.jsonl","reset":true,"messages":[{"role":"user","content":"Scan"}]}"#)
        let t = try #require(s.subagents["b1"]?.transcript)
        #expect(t.items.count == 1)
        #expect(t.live.count == 1)
        #expect(t.live.first?.assistantText?.text == "Hi")
        // Events for a run this session does not have change nothing.
        #expect(run(s, #"{"type":"subagent_events","runId":"nope","events":[]}"#) == s)
    }
}
