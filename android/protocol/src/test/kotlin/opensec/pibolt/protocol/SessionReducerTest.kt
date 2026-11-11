package opensec.pibolt.protocol

import opensec.pibolt.protocol.session.AssistantTextItem
import opensec.pibolt.protocol.session.DialogKind
import opensec.pibolt.protocol.session.NoticeItem
import opensec.pibolt.protocol.session.NoticeKind
import opensec.pibolt.protocol.session.SessionReducer
import opensec.pibolt.protocol.session.SessionState
import opensec.pibolt.protocol.session.ThinkingItem
import opensec.pibolt.protocol.session.ToolItem
import opensec.pibolt.protocol.session.ToolStatus
import opensec.pibolt.protocol.session.UserItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionReducerTest {
    private fun run(state: SessionState, vararg lines: String): SessionState =
        lines.fold(state) { s, line -> SessionReducer.reduce(s, SessionReducer.parse(line)) }

    private val hello = """
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
           {"role":"toolResult","toolCallId":"c1","toolName":"bash","content":[{"type":"text","text":"a.txt\nb.txt"}],"isError":false,"timestamp":4},
           {"role":"unknownRole","whatever":1}
         ],
         "dialogs":[{"type":"extension_ui_request","id":"d1","method":"confirm","title":"Run?","message":"rm -rf build"}],
         "commands":[{"name":"fix-tests","description":"Fix failing tests","source":"prompt","sourceInfo":{}}],
         "futureField":{"x":1}}
    """.trimIndent().replace("\n", "")

    @Test
    fun helloBuildsSnapshot() {
        val s = run(SessionState(), hello)
        assertTrue(s.hello)
        assertEquals("macbook", s.host?.name)
        assertEquals("/work/app", s.host?.cwd)
        assertEquals("Claude Sonnet 4.5", s.model?.label)
        assertEquals("medium", s.thinkingLevel)
        assertEquals("fix tests", s.sessionName)
        assertFalse(s.isRunning)
        assertEquals(4, s.items.size)
        assertEquals("list files", (s.items[0] as UserItem).text)
        // The message's own timestamp ties the row to its turn checkpoints.
        assertEquals(2L, (s.items[0] as UserItem).timestamp)
        assertEquals("Use ls.", (s.items[1] as ThinkingItem).text)
        assertEquals("Listing.", (s.items[2] as AssistantTextItem).text)
        val tool = s.items[3] as ToolItem
        assertEquals(ToolStatus.Done, tool.status)
        assertEquals("a.txt\nb.txt", tool.result)
        assertEquals(DialogKind.Confirm, s.dialogs.single().kind)
        assertEquals("fix-tests", s.commands.single().name)
        // Keys are unique.
        assertEquals(s.items.size, s.items.map { it.key }.toSet().size)
    }

    @Test
    fun streamingRunWithToolCall() {
        var s = run(SessionState(), hello)
        s = run(
            s,
            """{"type":"agent_start"}""",
            """{"type":"turn_start"}""",
            """{"type":"message_start","message":{"role":"user","content":"now run the tests","timestamp":10}}""",
            """{"type":"message_end","message":{"role":"user","content":"now run the tests","timestamp":10}}""",
            """{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending","timestamp":11}}""",
            """{"type":"message_update","usage":{"input":1,"output":1,"cacheRead":0,"cacheWrite":0,"totalTokens":2,"cost":{"total":0}},"assistantMessageEvent":{"type":"thinking_start","contentIndex":0}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","contentIndex":0,"delta":"Run "}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","contentIndex":0,"delta":"npm test."}}""",
        )
        assertTrue(s.isRunning)
        assertEquals("now run the tests", (s.items.last() as UserItem).text)
        assertEquals(10L, (s.items.last() as UserItem).timestamp)
        val thinking = s.streaming.single() as ThinkingItem
        assertEquals("Run npm test.", thinking.text)
        assertTrue(thinking.streaming)
        val itemsBefore = s.items

        s = run(
            s,
            """{"type":"message_update","assistantMessageEvent":{"type":"thinking_end","contentIndex":0,"content":"Run npm test."}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"text_start","contentIndex":1}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":1,"delta":"Running "}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":1,"delta":"the **tests**."}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_start","contentIndex":2,"id":"c2","toolName":"bash"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_delta","contentIndex":2,"delta":"{\"command\":\"npm"}}""",
        )
        // Deltas only touch the streaming tail.
        assertTrue(itemsBefore === s.items)
        assertEquals(3, s.streaming.size)
        assertEquals("Running the **tests**.", (s.streaming[1] as AssistantTextItem).text)
        val streamingTool = s.streaming[2] as ToolItem
        assertEquals(ToolStatus.Streaming, streamingTool.status)
        assertEquals("{\"command\":\"npm", streamingTool.argsText)
        val streamKeys = s.streaming.map { it.key }

        s = run(
            s,
            """{"type":"message_update","assistantMessageEvent":{"type":"toolcall_end","contentIndex":2,"toolCall":{"type":"toolCall","id":"c2","name":"bash","arguments":{"command":"npm test"}}}}""",
            """{"type":"message_end","message":{"role":"assistant","content":[{"type":"thinking","thinking":"Run npm test."},{"type":"text","text":"Running the **tests**."},{"type":"toolCall","id":"c2","name":"bash","arguments":{"command":"npm test"}}],"stopReason":"toolUse","usage":{"input":100,"output":20,"cacheRead":50,"cacheWrite":0,"totalTokens":170,"cost":{"total":0.002}},"timestamp":12}}""",
        )
        assertTrue(s.streaming.isEmpty())
        assertNull(s.stream)
        assertEquals(streamKeys, s.items.takeLast(3).map { it.key }, "rows keep their keys when the message completes")
        assertFalse((s.items[s.items.size - 2] as AssistantTextItem).streaming)
        assertEquals(170, s.lastUsage?.totalTokens)
        assertEquals(ToolStatus.Pending, (s.items.last() as ToolItem).status)

        s = run(
            s,
            """{"type":"tool_execution_start","toolCallId":"c2","toolName":"bash","args":{"command":"npm test"}}""",
            """{"type":"tool_execution_update","toolCallId":"c2","toolName":"bash","args":{},"partialResult":{"content":[{"type":"text","text":"PASS a.test.ts"}],"details":{}}}""",
        )
        var tool = s.items.last() as ToolItem
        assertEquals(ToolStatus.Running, tool.status)
        assertEquals("PASS a.test.ts", tool.output)

        s = run(
            s,
            """{"type":"queue_update","steering":["also lint"],"followUp":[]}""",
            """{"type":"tool_execution_end","toolCallId":"c2","toolName":"bash","result":{"content":[{"type":"text","text":"PASS a.test.ts\nPASS b.test.ts"}],"details":{"exitCode":0}},"isError":false,"durationMs":1234}""",
            """{"type":"message_start","message":{"role":"toolResult","toolCallId":"c2","toolName":"bash","content":[{"type":"text","text":"PASS a.test.ts\nPASS b.test.ts"}],"isError":false,"timestamp":13}}""",
            """{"type":"message_end","message":{"role":"toolResult","toolCallId":"c2","toolName":"bash","content":[{"type":"text","text":"PASS a.test.ts\nPASS b.test.ts"}],"isError":false,"timestamp":13}}""",
        )
        tool = s.items.last() as ToolItem
        assertEquals(ToolStatus.Done, tool.status)
        assertEquals(1234, tool.durationMs)
        assertEquals("PASS a.test.ts\nPASS b.test.ts", tool.result)
        assertEquals(listOf("also lint"), s.queue.steering)

        s = run(
            s,
            """{"type":"turn_end","message":{},"toolResults":[]}""",
            """{"type":"queue_update","steering":[],"followUp":[]}""",
            """{"type":"auto_retry_start","attempt":1,"maxAttempts":3,"delayMs":2000,"errorMessage":"529 overloaded"}""",
        )
        assertEquals(3, s.retry?.maxAttempts)
        s = run(
            s,
            """{"type":"auto_retry_end","success":true,"attempt":2}""",
            """{"type":"message_start","message":{"role":"assistant","content":[],"stopReason":"pending","timestamp":14}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"text_start","contentIndex":0}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"All green."}}""",
            """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"All green."}],"stopReason":"stop","timestamp":15}}""",
            """{"type":"agent_end","messages":[],"willRetry":false}""",
            """{"type":"some_future_event","x":1}""",
            """{"type":"agent_settled","aborted":false}""",
        )
        assertFalse(s.isRunning)
        assertNull(s.retry)
        assertTrue(s.queue.isEmpty)
        assertEquals("All green.", (s.items.last() as AssistantTextItem).text)
        assertEquals(s.items.size, s.items.map { it.key }.toSet().size)
    }

    @Test
    fun dialogsStatusesAndErrors() {
        var s = run(SessionState(), hello)
        s = run(
            s,
            """{"type":"extension_ui_request","id":"d2","method":"select","title":"Pick","options":["A","B"],"timeout":10000}""",
            """{"type":"extension_ui_request","id":"n1","method":"notify","message":"hi"}""",
            """{"type":"extension_ui_request","id":"s1","method":"setStatus","statusKey":"ext","statusText":"Turn 3"}""",
        )
        assertEquals(listOf("d1", "d2"), s.dialogs.map { it.id })
        assertEquals(listOf("A", "B"), s.dialogs[1].options)
        assertEquals("Turn 3", s.statuses["ext"])
        s = run(
            s,
            """{"type":"extension_ui_dismissed","id":"d1"}""",
            """{"type":"extension_ui_request","id":"s2","method":"setStatus","statusKey":"ext"}""",
        )
        assertEquals(listOf("d2"), s.dialogs.map { it.id })
        assertTrue(s.statuses.isEmpty())

        s = run(
            s,
            """{"type":"agent_start"}""",
            """{"type":"message_start","message":{"role":"assistant","content":[]}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"partial"}}""",
            """{"type":"message_end","message":{"role":"assistant","content":[{"type":"text","text":"partial"}],"stopReason":"aborted","errorMessage":"Request was aborted"}}""",
            """{"type":"agent_settled","aborted":true}""",
            """{"type":"compaction_start","reason":"manual"}""",
        )
        assertIs<NoticeItem>(s.items.last()).also { assertEquals(NoticeKind.Aborted, it.kind) }
        assertTrue(s.isCompacting)
        s = run(s, """{"type":"compaction_end","reason":"manual","result":{"summary":"S","firstKeptEntryId":"e","tokensBefore":150000,"estimatedTokensAfter":32000},"aborted":false,"willRetry":false}""")
        assertFalse(s.isCompacting)
        val notice = s.items.last() as NoticeItem
        assertEquals(NoticeKind.Compaction, notice.kind)
        assertEquals("Compacted 150k to ~32k tokens", notice.title)
    }

    @Test
    fun secondHelloReplacesEverything() {
        var s = run(SessionState(), hello, """{"type":"agent_start"}""")
        s = run(s, """{"type":"remote_hello","protocol":1,"host":{"name":"macbook"},"state":{"isStreaming":false},"messages":[],"dialogs":[],"commands":[]}""")
        assertTrue(s.items.isEmpty())
        assertTrue(s.dialogs.isEmpty())
        assertFalse(s.isRunning)
    }

    @Test
    fun helloMidStreamResumesStreaming() {
        val s = run(
            SessionState(),
            """{"type":"remote_hello","protocol":1,"host":{"name":"m"},"state":{"isStreaming":true},"messages":[{"role":"user","content":"go"},{"role":"assistant","content":[{"type":"toolCall","id":"t9","name":"read","arguments":{"path":"a"}}],"stopReason":"toolUse"}],"streamingMessage":{"role":"assistant","content":[{"type":"text","text":"Half"}]},"dialogs":[],"commands":[]}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":" done"}}""",
        )
        assertTrue(s.isRunning)
        assertEquals(ToolStatus.Pending, (s.items.last() as ToolItem).status)
        assertEquals("Half done", (s.streaming.single() as AssistantTextItem).text)
    }

    @Test
    fun outOfRangeContentIndexIsIgnored() {
        // PR #243 review note: -1 threw IndexOutOfBounds; a huge index padded the block list until OOM.
        val state = run(
            SessionReducer.reduce(SessionState(), SessionReducer.parse(hello)),
            """{"type":"message_start","message":{"role":"assistant","content":[]}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":-1,"delta":"x"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":2000000000,"delta":"x"}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"text_start","contentIndex":0}}""",
            """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"ok"}}""",
        )
        assertEquals(listOf("ok"), state.streaming.filterIsInstance<AssistantTextItem>().map { it.text })
    }


    @Test
    fun userShellOutputStreamsIntoTheRunningShellStep() {
        val running = ToolItem("shell:1", "shell:1", "bash", null, status = ToolStatus.Running)
        val start = SessionState(hello = true, items = listOf(ToolItem("tool:a", "a", "bash", null, status = ToolStatus.Running), running))
        val state = run(
            start,
            """{"type":"bash_execution_update","id":"a7","delta":"line 1\n"}""",
            """{"type":"bash_execution_update","id":"a7","delta":"line 2\n"}""",
        )
        assertEquals("line 1\nline 2\n", (state.items[1] as ToolItem).output)
        // The agent's own running tool is not touched.
        assertEquals("", (state.items[0] as ToolItem).output)
        // Without a running shell step the update is ignored.
        val done = SessionState(items = listOf(running.copy(status = ToolStatus.Done)))
        assertEquals(done, run(done, """{"type":"bash_execution_update","delta":"x"}"""))
    }

    @Test
    fun compactionKeepsItsReasonWhileItRuns() {
        val started = run(SessionState(hello = true), """{"type":"compaction_start","reason":"threshold"}""")
        assertTrue(started.isCompacting)
        assertEquals("threshold", started.compactionReason)
        val ended = run(started, """{"type":"compaction_end","reason":"threshold","result":{"summary":"s","tokensBefore":150000,"estimatedTokensAfter":30000}}""")
        assertFalse(ended.isCompacting)
        assertNull(ended.compactionReason)
    }

    @Test
    fun commandsKeepAnArgumentHintWhenTheHostSendsOne() {
        val commands = SessionReducer.commandsFrom(
            SessionReducer.parse("""{"commands":[{"name":"review","source":"prompt","argumentHint":"<pr>"},{"name":"todo","source":"extension","argumentHint":" "}]}"""),
        )
        assertEquals("<pr>", commands[0].argumentHint)
        assertNull(commands[1].argumentHint)
    }

    @Test
    fun longSessionSaysEarlierMessagesStayOnTheComputer() {
        // remote_hello of a long session: only the newest messages, omittedMessages counts the rest.
        val s = run(
            SessionState(),
            """{"type":"remote_hello","protocol":1,"host":{"name":"m"},"state":{"isStreaming":false},"messages":[{"role":"assistant","content":[{"type":"text","text":"tail"}],"stopReason":"stop"}],"dialogs":[],"commands":[],"omittedMessages":412}""",
        )
        val notice = s.items.first() as NoticeItem
        assertEquals(NoticeKind.Info, notice.kind)
        assertTrue("412 older messages" in notice.text)
        assertEquals("tail", (s.items[1] as AssistantTextItem).text)
        // One huge message alone over the budget: the session is not shown as empty.
        val empty = run(SessionState(), """{"type":"remote_hello","protocol":1,"host":{"name":"m"},"state":{},"messages":[],"dialogs":[],"commands":[],"omittedMessages":1}""")
        assertTrue("1 older message is" in (empty.items.single() as NoticeItem).text)
        // Nothing left out: no notice.
        assertTrue(run(SessionState(), hello).items.none { it is NoticeItem })
    }

    @Test
    fun reconnectAfterTheRunEndedReportsTheMissedSettle() {
        val running = run(SessionState(), """{"type":"remote_hello","protocol":1,"host":{"name":"m"},"state":{"isStreaming":true,"sessionId":"s1"},"messages":[],"dialogs":[],"commands":[]}""")
        assertTrue(running.isRunning)
        // The link dropped; the turn ended and a dialog opened before the phone came back.
        val back = SessionReducer.parse("""{"type":"remote_hello","protocol":1,"host":{"name":"m"},"state":{"isStreaming":false,"sessionId":"s1"},"messages":[],"dialogs":[{"type":"extension_ui_request","id":"d1","method":"confirm","title":"Deploy?"}],"commands":[]}""")
        val missed = SessionReducer.missedWhileAway(running, back)
        assertEquals(listOf("agent_settled", "extension_ui_request"), missed.map { it.type })
        assertEquals("d1", SessionReducer.dialogFrom(missed[1])?.id)
        // A dialog already shown before the drop is not announced again.
        val withDialog = SessionReducer.reduce(running, back)
        assertTrue(SessionReducer.missedWhileAway(withDialog, back).isEmpty())
    }

    @Test
    fun firstSnapshotOrAnotherSessionMissesNothing() {
        val back = SessionReducer.parse("""{"type":"remote_hello","protocol":1,"host":{"name":"m"},"state":{"isStreaming":false,"sessionId":"s2"},"messages":[],"dialogs":[{"id":"d1","method":"confirm","title":"Deploy?"}],"commands":[]}""")
        assertTrue(SessionReducer.missedWhileAway(SessionState(), back).isEmpty())
        val running = run(SessionState(), """{"type":"remote_hello","protocol":1,"host":{"name":"m"},"state":{"isStreaming":true,"sessionId":"s1"},"messages":[],"dialogs":[],"commands":[]}""")
        // The computer switched to session s2 (/resume in the terminal): no run of s1 "finished".
        assertTrue(SessionReducer.missedWhileAway(running, back).isEmpty())
    }
}
