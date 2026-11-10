import Foundation
@testable import PiBoltProtocol
import Testing

/// The Android `SessionReducerTest`, `DeltaMergeTest` and `ModelInfoTest` (android/protocol), on the Swift reducer.
struct SessionReducerTests {
    private func run(_ state: SessionState, _ lines: String...) -> SessionState {
        lines.reduce(state) { SessionReducer.reduce($0, SessionReducer.parse($1)) }
    }

    private let hello = """
    {"type":"remote_hello","protocol":1,"host":{"name":"macbook","version":"1.1.1","cwd":"/work/app"},
     "state":{"model":{"id":"claude-sonnet-4-5","name":"Claude Sonnet 4.5","provider":"anthropic","reasoning":true,"contextWindow":200000},
              "thinkingLevel":"medium","isStreaming":false,"isCompacting":false,"steeringMode":"all","followUpMode":"one-at-a-time",
              "sessionId":"s1","sessionName":"fix tests","autoCompactionEnabled":true,"messageCount":4,"pendingMessageCount":0},
     "messages":[
       {"role":"system","content":"You are Pi","timestamp":1},
       {"role":"user","content":"list files","timestamp":2},
       {"role":"assistant","content":[{"type":"thinking","thinking":"Use ls."},{"type":"text","text":"Listing."},
          {"type":"toolCall","id":"c1","name":"bash","arguments":{"command":"ls"}}],"api":"x","provider":"anthropic","model":"m",
          "usage":{"input":10,"output":5,"cacheRead":0,"cacheWrite":0,"totalTokens":15,"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"total":0.001}},
          "stopReason":"toolUse","timestamp":3},
       {"role":"toolResult","toolCallId":"c1","toolName":"bash","content":[{"type":"text","text":"a.txt\\nb.txt"}],"isError":false,"timestamp":4},
       {"role":"unknownRole","whatever":1}
     ],
     "dialogs":[{"type":"extension_ui_request","id":"d1","method":"confirm","title":"Run?","message":"rm -rf build"}],
     "commands":[{"name":"fix-tests","description":"Fix failing tests","source":"prompt","sourceInfo":{}}],
     "futureField":{"x":1}}
    """.replacingOccurrences(of: "\n", with: "")

    @Test func helloBuildsSnapshot() throws {
        let s = run(SessionState(), hello)
        #expect(s.hello)
        #expect(s.host?.name == "macbook")
        #expect(s.host?.cwd == "/work/app")
        #expect(s.model?.label == "Claude Sonnet 4.5")
        #expect(s.model?.contextWindow == 200_000)
        #expect(s.thinkingLevel == "medium")
        #expect(s.sessionName == "fix tests")
        #expect(!s.isRunning)
        #expect(s.items.count == 4)
        guard case let .user(user) = s.items[0] else { Issue.record("not a user row"); return }
        #expect(user.text == "list files")
        // The message's own timestamp ties the row to its turn.
        #expect(user.timestamp == 2)
        guard case let .thinking(thinking) = s.items[1] else { Issue.record("not thinking"); return }
        #expect(thinking.text == "Use ls.")
        #expect(s.items[2].assistantText?.text == "Listing.")
        let tool = try #require(s.items[3].tool)
        #expect(tool.status == .done)
        #expect(tool.result == "a.txt\nb.txt")
        #expect(s.dialogs.map(\.kind) == [.confirm])
        #expect(s.commands.map(\.name) == ["fix-tests"])
        // Keys are unique.
        #expect(Set(s.items.map(\.key)).count == s.items.count)
    }

    @Test func streamingRunWithToolCall() throws {
        var s = run(SessionState(), hello)
        s = run(
            s,
            #"{"type":"agent_start"}"#,
            #"{"type":"turn_start"}"#,
            #"{"type":"message_start","message":{"role":"user","content":"now run the tests","timestamp":10}}"#,
            #"{"type":"message_end","message":{"role":"user","content":"now run the tests","timestamp":10}}"#,
            #"{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending","timestamp":11}}"#,
            #"{"type":"message_update","usage":{"input":1,"output":1,"cacheRead":0,"cacheWrite":0,"totalTokens":2,"cost":{"total":0}},"assistantMessageEvent":{"type":"thinking_start","contentIndex":0}}"#,
            #"{"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","contentIndex":0,"delta":"Run "}}"#,
            #"{"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","contentIndex":0,"delta":"npm test."}}"#
        )
        #expect(s.isRunning)
        guard case let .user(user) = s.items.last else { Issue.record("no user row"); return }
        #expect(user.text == "now run the tests")
        #expect(user.timestamp == 10)
        guard s.streaming.count == 1, case let .thinking(thinking) = s.streaming[0] else { Issue.record("no thinking"); return }
        #expect(thinking.text == "Run npm test.")
        #expect(thinking.streaming)
        let itemsBefore = s.items

        s = run(
            s,
            #"{"type":"message_update","assistantMessageEvent":{"type":"thinking_end","contentIndex":0,"content":"Run npm test."}}"#,
            #"{"type":"message_update","assistantMessageEvent":{"type":"text_start","contentIndex":1}}"#,
            #"{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":1,"delta":"Running "}}"#,
            #"{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":1,"delta":"the **tests**."}}"#,
            #"{"type":"message_update","assistantMessageEvent":{"type":"toolcall_start","contentIndex":2,"id":"c2","toolName":"bash"}}"#,
            #"{"type":"message_update","assistantMessageEvent":{"type":"toolcall_delta","contentIndex":2,"delta":"{\"command\":\"npm"}}"#
        )
        // Deltas only touch the streaming tail.
        #expect(itemsBefore == s.items)
        #expect(s.streaming.count == 3)
        #expect(s.streaming[1].assistantText?.text == "Running the **tests**.")
        let streamingTool = try #require(s.streaming[2].tool)
        #expect(streamingTool.status == .streaming)
        #expect(streamingTool.argsText == "{\"command\":\"npm")
        let streamKeys = s.streaming.map(\.key)

        s = run(
            s,
            #"{"type":"message_update","assistantMessageEvent":{"type":"toolcall_end","contentIndex":2,"toolCall":{"type":"toolCall","id":"c2","name":"bash","arguments":{"command":"npm test"}}}}"#,
            #"{"type":"message_end","message":{"role":"assistant","content":[{"type":"thinking","thinking":"Run npm test."},{"type":"text","text":"Running the **tests**."},{"type":"toolCall","id":"c2","name":"bash","arguments":{"command":"npm test"}}],"stopReason":"toolUse","usage":{"input":100,"output":20,"cacheRead":50,"cacheWrite":0,"totalTokens":170,"cost":{"total":0.002}},"timestamp":12}}"#
        )
        #expect(s.streaming.isEmpty)
        #expect(s.stream == nil)
        #expect(Array(s.items.suffix(3).map(\.key)) == streamKeys, "rows keep their keys when the message completes")
        #expect(s.items[s.items.count - 2].assistantText?.streaming == false)
        #expect(s.lastUsage?.totalTokens == 170)
        #expect(s.items.last?.tool?.status == .pending)

        s = run(
            s,
            #"{"type":"tool_execution_start","toolCallId":"c2","toolName":"bash","args":{"command":"npm test"}}"#,
            #"{"type":"tool_execution_update","toolCallId":"c2","toolName":"bash","args":{},"partialResult":{"content":[{"type":"text","text":"PASS a.test.ts"}],"details":{}}}"#
        )
        var tool = try #require(s.items.last?.tool)
        #expect(tool.status == .running)
        #expect(tool.output == "PASS a.test.ts")

        s = run(
            s,
            #"{"type":"queue_update","steering":["also lint"],"followUp":[]}"#,
            #"{"type":"tool_execution_end","toolCallId":"c2","toolName":"bash","result":{"content":[{"type":"text","text":"PASS a.test.ts\nPASS b.test.ts"}],"details":{"exitCode":0}},"isError":false,"durationMs":1234}"#,
            #"{"type":"message_start","message":{"role":"toolResult","toolCallId":"c2","toolName":"bash","content":[{"type":"text","text":"PASS a.test.ts\nPASS b.test.ts"}],"isError":false,"timestamp":13}}"#,
            #"{"type":"message_end","message":{"role":"toolResult","toolCallId":"c2","toolName":"bash","content":[{"type":"text","text":"PASS a.test.ts\nPASS b.test.ts"}],"isError":false,"timestamp":13}}"#
        )
        tool = try #require(s.items.last?.tool)
        #expect(tool.status == .done)
        #expect(tool.durationMs == 1234)
        #expect(tool.result == "PASS a.test.ts\nPASS b.test.ts")
        #expect(s.queue.steering == ["also lint"])

        s = run(
            s,
            #"{"type":"turn_end","message":{},"toolResults":[]}"#,
            #"{"type":"queue_update","steering":[],"followUp":[]}"#,
            #"{"type":"auto_retry_start","attempt":1,"maxAttempts":3,"delayMs":2000,"errorMessage":"529 overloaded"}"#
        )
        #expect(s.retry?.maxAttempts == 3)
        s = run(
            s,
            #"{"type":"auto_retry_end","success":true,"attempt":2}"#,
            #"{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending","timestamp":14}}"#,
            #"{"type":"message_update","assistantMessageEvent":{"type":"text_start","contentIndex":0}}"#,
            #"{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"All green."}}"#,
            #"{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"All green."}],"stopReason":"stop","timestamp":15}}"#,
            #"{"type":"agent_end","messages":[],"willRetry":false}"#,
            #"{"type":"some_future_event","x":1}"#,
            #"{"type":"agent_settled","aborted":false}"#
        )
        #expect(!s.isRunning)
        #expect(s.retry == nil)
        #expect(s.queue.isEmpty)
        #expect(s.items.last?.assistantText?.text == "All green.")
        #expect(Set(s.items.map(\.key)).count == s.items.count)
    }

    @Test func dialogsStatusesAndErrors() throws {
        var s = run(SessionState(), hello)
        s = run(
            s,
            #"{"type":"extension_ui_request","id":"d2","method":"select","title":"Pick","options":["A","B"],"timeout":10000}"#,
            #"{"type":"extension_ui_request","id":"n1","method":"notify","message":"hi"}"#,
            #"{"type":"extension_ui_request","id":"s1","method":"setStatus","statusKey":"ext","statusText":"Turn 3"}"#
        )
        #expect(s.dialogs.map(\.id) == ["d1", "d2"])
        #expect(s.dialogs[1].options == ["A", "B"])
        #expect(s.statuses["ext"] == "Turn 3")
        s = run(
            s,
            #"{"type":"extension_ui_dismissed","id":"d1"}"#,
            #"{"type":"extension_ui_request","id":"s2","method":"setStatus","statusKey":"ext"}"#
        )
        #expect(s.dialogs.map(\.id) == ["d2"])
        #expect(s.statuses.isEmpty)

        s = run(
            s,
            #"{"type":"agent_start"}"#,
            #"{"type":"message_start","message":{"role":"assistant","content":[]}}"#,
            #"{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"partial"}}"#,
            #"{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"partial"}],"stopReason":"aborted","errorMessage":"Request was aborted"}}"#,
            #"{"type":"agent_settled","aborted":true}"#,
            #"{"type":"compaction_start","reason":"manual"}"#
        )
        guard case let .notice(aborted) = s.items.last else { Issue.record("no notice"); return }
        #expect(aborted.kind == .aborted)
        #expect(s.isCompacting)
        s = run(s, #"{"type":"compaction_end","reason":"manual","result":{"summary":"S","firstKeptEntryId":"e","tokensBefore":150000,"estimatedTokensAfter":32000},"aborted":false,"willRetry":false}"#)
        #expect(!s.isCompacting)
        guard case let .notice(notice) = s.items.last else { Issue.record("no notice"); return }
        #expect(notice.kind == .compaction)
        #expect(notice.title == "Compacted 150k to ~32k tokens")
    }

    @Test func secondHelloReplacesEverything() {
        var s = run(SessionState(), hello, #"{"type":"agent_start"}"#)
        s = run(s, #"{"type":"remote_hello","protocol":1,"host":{"name":"macbook"},"state":{"isStreaming":false},"messages":[],"dialogs":[],"commands":[]}"#)
        #expect(s.items.isEmpty)
        #expect(s.dialogs.isEmpty)
        #expect(!s.isRunning)
    }

    @Test func helloMidStreamResumesStreaming() {
        let s = run(
            SessionState(),
            #"{"type":"remote_hello","protocol":1,"host":{"name":"m"},"state":{"isStreaming":true},"messages":[{"role":"user","content":"go"},{"role":"assistant","content":[{"type":"toolCall","id":"t9","name":"read","arguments":{"path":"a"}}],"stopReason":"toolUse"}],"streamingMessage":{"role":"assistant","content":[{"type":"text","text":"Half"}]},"dialogs":[],"commands":[]}"#,
            #"{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":" done"}}"#
        )
        #expect(s.isRunning)
        #expect(s.items.last?.tool?.status == .pending)
        #expect(s.streaming.count == 1)
        #expect(s.streaming.first?.assistantText?.text == "Half done")
    }

    @Test func outOfRangeContentIndexIsIgnored() {
        // A negative index trapped; a huge one padded the block list until the app ran out of memory.
        let state = run(
            run(SessionState(), hello),
            #"{"type":"message_start","message":{"role":"assistant","content":[]}}"#,
            #"{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":-1,"delta":"x"}}"#,
            #"{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":2000000000,"delta":"x"}}"#,
            #"{"type":"message_update","assistantMessageEvent":{"type":"text_start","contentIndex":0}}"#,
            #"{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"ok"}}"#
        )
        #expect(state.streaming.compactMap(\.assistantText).map(\.text) == ["ok"])
    }

    @Test func userShellOutputStreamsIntoTheRunningShellStep() {
        let running = ToolItem(key: "shell:1", toolCallId: "shell:1", name: "bash", args: nil, status: .running)
        var start = SessionState()
        start.hello = true
        start.items = [.tool(ToolItem(key: "tool:a", toolCallId: "a", name: "bash", args: nil, status: .running)), .tool(running)]
        let state = run(
            start,
            #"{"type":"bash_execution_update","id":"a7","delta":"line 1\n"}"#,
            #"{"type":"bash_execution_update","id":"a7","delta":"line 2\n"}"#
        )
        #expect(state.items[1].tool?.output == "line 1\nline 2\n")
        // The agent's own running tool is not touched.
        #expect(state.items[0].tool?.output == "")
        // Without a running shell step the update is ignored.
        var done = SessionState()
        var finished = running
        finished.status = .done
        done.items = [.tool(finished)]
        #expect(run(done, #"{"type":"bash_execution_update","delta":"x"}"#) == done)
    }

    @Test func compactionKeepsItsReasonWhileItRuns() {
        var initial = SessionState()
        initial.hello = true
        let started = run(initial, #"{"type":"compaction_start","reason":"threshold"}"#)
        #expect(started.isCompacting)
        #expect(started.compactionReason == "threshold")
        let ended = run(started, #"{"type":"compaction_end","reason":"threshold","result":{"summary":"s","tokensBefore":150000,"estimatedTokensAfter":30000}}"#)
        #expect(!ended.isCompacting)
        #expect(ended.compactionReason == nil)
    }

    @Test func commandsKeepAnArgumentHintWhenTheHostSendsOne() {
        let commands = SessionReducer.commandsFrom(SessionReducer.parse(#"{"commands":[{"name":"review","source":"prompt","argumentHint":"<pr>"},{"name":"todo","source":"extension","argumentHint":" "}]}"#))
        #expect(commands[0].argumentHint == "<pr>")
        #expect(commands[1].argumentHint == nil)
    }

    @Test func longSessionSaysEarlierMessagesStayOnTheComputer() {
        let s = run(
            SessionState(),
            #"{"type":"remote_hello","protocol":1,"host":{"name":"m"},"state":{"isStreaming":false},"messages":[{"role":"assistant","content":[{"type":"text","text":"tail"}],"stopReason":"stop"}],"dialogs":[],"commands":[],"omittedMessages":412}"#
        )
        guard case let .notice(notice) = s.items.first else { Issue.record("no notice"); return }
        #expect(notice.kind == .info)
        #expect(notice.text.contains("412 older messages"))
        #expect(s.items[1].assistantText?.text == "tail")
        // One huge message alone over the budget: the session is not shown as empty.
        let empty = run(SessionState(), #"{"type":"remote_hello","protocol":1,"host":{"name":"m"},"state":{},"messages":[],"dialogs":[],"commands":[],"omittedMessages":1}"#)
        guard empty.items.count == 1, case let .notice(one) = empty.items[0] else { Issue.record("no notice"); return }
        #expect(one.text.contains("1 older message is"))
        // Nothing left out: no notice.
        #expect(!run(SessionState(), hello).items.contains { if case .notice = $0 { return true }; return false })
    }

    @Test func reconnectAfterTheRunEndedReportsTheMissedSettle() {
        let running = run(SessionState(), #"{"type":"remote_hello","protocol":1,"host":{"name":"m"},"state":{"isStreaming":true,"sessionId":"s1"},"messages":[],"dialogs":[],"commands":[]}"#)
        #expect(running.isRunning)
        // The link dropped; the turn ended and a dialog opened before the phone came back.
        let back = SessionReducer.parse(#"{"type":"remote_hello","protocol":1,"host":{"name":"m"},"state":{"isStreaming":false,"sessionId":"s1"},"messages":[],"dialogs":[{"type":"extension_ui_request","id":"d1","method":"confirm","title":"Deploy?"}],"commands":[]}"#)
        let missed = SessionReducer.missedWhileAway(running, back)
        #expect(missed.map(\.type) == ["agent_settled", "extension_ui_request"])
        #expect(SessionReducer.dialogFrom(missed[1])?.id == "d1")
        // A dialog already shown before the drop is not announced again.
        let withDialog = SessionReducer.reduce(running, back)
        #expect(SessionReducer.missedWhileAway(withDialog, back).isEmpty)
    }

    @Test func firstSnapshotOrAnotherSessionMissesNothing() {
        let back = SessionReducer.parse(#"{"type":"remote_hello","protocol":1,"host":{"name":"m"},"state":{"isStreaming":false,"sessionId":"s2"},"messages":[],"dialogs":[{"id":"d1","method":"confirm","title":"Deploy?"}],"commands":[]}"#)
        #expect(SessionReducer.missedWhileAway(SessionState(), back).isEmpty)
        let running = run(SessionState(), #"{"type":"remote_hello","protocol":1,"host":{"name":"m"},"state":{"isStreaming":true,"sessionId":"s1"},"messages":[],"dialogs":[],"commands":[]}"#)
        // The computer switched to session s2 (/resume in the terminal): no run of s1 "finished".
        #expect(SessionReducer.missedWhileAway(running, back).isEmpty)
    }
}

struct DeltaMergeTests {
    private func delta(_ kind: String, _ text: String, index: Int = 0, output: Int = 1) -> JSON {
        ["type": "message_update", "usage": ["output": .number(Double(output))], "assistantMessageEvent": ["type": .string(kind), "contentIndex": .number(Double(index)), "delta": .string(text)]]
    }

    private func reduceAll(_ events: [JSON]) -> SessionState {
        var s = SessionReducer.reduce(SessionState(), ["type": "agent_start"])
        s = SessionReducer.reduce(s, ["type": "message_start", "message": ["role": "assistant"]])
        for e in events { s = SessionReducer.reduce(s, e) }
        return s
    }

    @Test func mergedDeltasReduceToTheSameState() {
        let events = [
            delta("text_start", "").with("usage", nil),
            delta("text_delta", "Hel", output: 1),
            delta("text_delta", "lo ", output: 2),
            delta("text_delta", "world ", output: 3),
        ]
        var merged: [JSON] = []
        for e in events {
            if let last = merged.last, let m = SessionReducer.mergeDeltas(last, e) { merged[merged.count - 1] = m } else { merged.append(e) }
        }
        #expect(merged.count == 2)
        var a = reduceAll(events)
        var b = reduceAll(merged)
        a.seq = 0
        b.seq = 0
        #expect(a == b)
        #expect(reduceAll(merged).lastUsage?.output == 3)
    }

    @Test func differentBlocksOrKindsDoNotMerge() {
        #expect(SessionReducer.mergeDeltas(delta("text_delta", "a", index: 0), delta("text_delta", "b", index: 1)) == nil)
        #expect(SessionReducer.mergeDeltas(delta("text_delta", "a"), delta("thinking_delta", "b")) == nil)
        #expect(SessionReducer.mergeDeltas(delta("text_delta", "a"), ["type": "agent_settled"]) == nil)
        #expect(SessionReducer.mergeDeltas(delta("toolcall_delta", "a"), delta("toolcall_delta", "b"))?.obj("assistantMessageEvent")?.str("delta") == "ab")
    }
}

struct ModelInfoTests {
    private func model(_ json: String) -> ModelInfo { ModelInfo.from(SessionReducer.parse(json))! }

    @Test func inputModalitiesSayWhetherTheModelReadsImages() {
        let vision = model(#"{"id":"claude-sonnet-4-5","name":"Sonnet","provider":"anthropic","input":["text","image"]}"#)
        #expect(vision.input == ["text", "image"])
        #expect(vision.acceptsImages == true)
        let textOnly = model(#"{"id":"deepseek","provider":"x","input":["text"]}"#)
        #expect(textOnly.acceptsImages == false)
        // An older host that sends no input list: unknown.
        #expect(model(#"{"id":"m","provider":"x"}"#).acceptsImages == nil)
    }

    @Test func imagesAreSentAsPiImageContent() {
        let json = imagesJSON([ImageAttachment(base64: "QUJD", mimeType: "image/jpeg"), ImageAttachment(base64: "REVG", mimeType: "image/png")])
        let items = json.arrayValue ?? []
        #expect(items.count == 2)
        #expect(items[0].str("type") == "image")
        #expect(items[0].str("data") == "QUJD")
        #expect(items[0].str("mimeType") == "image/jpeg")
        #expect(items[1].str("mimeType") == "image/png")
    }
}

struct JSONTests {
    @Test func roundTripsAndKeepsBooleansApartFromNumbers() throws {
        let text = #"{"a":true,"b":1,"c":[0,false,null,"x\n\"y\""],"d":{"e":1.5},"f":-3}"#
        let json = try JSON.parse(text)
        #expect(json.bool("a") == true)
        #expect(json.bool("b") == nil)
        #expect(json.int("b") == 1)
        #expect(json.str("b") == nil)
        #expect(json.arr("c")?[1] == .bool(false))
        #expect(json.obj("d")?.double("e") == 1.5)
        #expect(try JSON.parse(json.text) == json)
        #expect(JSON.object("[1]") == nil)
        #expect(JSON.string("\u{1}").text == #""\u0001""#)
    }
}
