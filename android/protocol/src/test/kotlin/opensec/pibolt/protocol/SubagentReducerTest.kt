package opensec.pibolt.protocol

import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.protocol.session.AssistantTextItem
import opensec.pibolt.protocol.session.SessionReducer
import opensec.pibolt.protocol.session.SessionState
import opensec.pibolt.protocol.session.SubagentNoticeItem
import opensec.pibolt.protocol.session.SubagentReducer
import opensec.pibolt.protocol.session.SubagentStatus
import opensec.pibolt.protocol.session.ThinkingItem
import opensec.pibolt.protocol.session.ToolItem
import opensec.pibolt.protocol.session.ToolStatus
import opensec.pibolt.protocol.session.UserItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** [SessionState.subagents]: runs of the subagents extension from its tool calls, results and notifications. */
class SubagentReducerTest {
    private fun run(state: SessionState, vararg lines: String): SessionState =
        lines.fold(state) { s, line -> SessionReducer.reduce(s, SessionReducer.parse(line)) }

    private fun args(type: String, description: String, prompt: String, background: Boolean? = null) =
        """{"subagent_type":"$type","description":"$description","prompt":"$prompt"${background?.let { ",\"run_in_background\":$it" } ?: ""}}"""

    private fun call(vararg calls: Pair<String, String>) =
        """{"role":"assistant","content":[${calls.joinToString(",") { (id, a) -> """{"type":"toolCall","id":"$id","name":"Agent","arguments":$a}""" }}],"stopReason":"toolUse"}"""

    private fun result(id: String, text: String, details: String?, isError: Boolean = false, name: String = "Agent", at: Long = 100_000) =
        """{"role":"toolResult","toolCallId":"$id","toolName":"$name","content":[{"type":"text","text":${json(text)}}],${details?.let { "\"details\":$it," } ?: ""}"isError":$isError,"timestamp":$at}"""

    private fun json(text: String) = JsonPrimitive(text).toString()

    private fun hello(running: Boolean, vararg messages: String) =
        """{"type":"remote_hello","host":{"name":"phone","version":"1","cwd":"/p"},"state":{"isStreaming":$running,"sessionId":"s1"},"messages":[${messages.joinToString(",")}]}"""

    private fun start(id: String, a: String) = """{"type":"tool_execution_start","toolCallId":"$id","toolName":"Agent","args":$a}"""

    private fun update(id: String, details: String) =
        """{"type":"tool_execution_update","toolCallId":"$id","toolName":"Agent","partialResult":{"content":[{"type":"text","text":"2 tool uses..."}],"details":$details}}"""

    private fun end(id: String, text: String, details: String?, isError: Boolean = false) =
        """{"type":"tool_execution_end","toolCallId":"$id","toolName":"Agent","result":{"content":[{"type":"text","text":${json(text)}}]${details?.let { ",\"details\":$it" } ?: ""}},"isError":$isError}"""

    private fun notification(details: String, at: Long = 200_000) =
        """{"role":"custom","customType":"subagent-notification","content":"<task-notification>…</task-notification>","display":true,"details":$details,"timestamp":$at}"""

    private val user = """{"role":"user","content":"go"}"""
    private val explore = args("Explore", "Find auth files", "Find the authentication code")

    @Test
    fun historyReplayBuildsFinishedRuns() {
        val s = run(
            SessionState(),
            hello(
                false, user, call("a1" to explore),
                result(
                    "a1",
                    "Agent completed in 9.1s (4 tool uses, 1.2k token).\n\nAuth lives in src/auth.\n\nAgent ID: abc123 (pass it as `resume` to continue this agent)",
                    """{"displayName":"Explore","description":"Find auth files","subagentType":"Explore","toolUses":4,"tokens":"1.2k token","turnCount":3,"durationMs":9100,"status":"completed","agentId":"abc123"}""",
                ),
            ),
        )
        val agent = s.subagents.getValue("a1")
        assertEquals(SubagentStatus.Done, agent.status)
        assertEquals("Explore", agent.type)
        assertEquals("Find auth files", agent.description)
        assertEquals("Find the authentication code", agent.prompt)
        assertEquals("abc123", agent.agentId)
        assertEquals("Auth lives in src/auth.", agent.result)
        assertEquals(4, agent.toolUses)
        assertEquals("1.2k", agent.tokens)
        assertEquals(3, agent.turns)
        assertEquals(9100L, agent.durationMs)
        assertEquals(100_000L - 9100, agent.startedAt)
        assertTrue(!agent.background)
    }

    @Test
    fun liveUpdatesMoveTheRunAlong() {
        var s = run(SessionState(), """{"type":"agent_start"}""", """{"type":"message_end","message":${call("a1" to explore)}}""")
        assertEquals(SubagentStatus.Queued, s.subagents.getValue("a1").status)
        s = run(s, start("a1", explore))
        assertEquals(SubagentStatus.Running, s.subagents.getValue("a1").status)
        s = run(s, update("a1", """{"displayName":"Explore","status":"running","activity":"queued — waiting for a foreground slot (1 ahead)","toolUses":0,"durationMs":40}"""))
        assertEquals(SubagentStatus.Queued, s.subagents.getValue("a1").status)
        s = run(s, update("a1", """{"displayName":"Explore","status":"running","activity":"reading 3 files…","toolUses":2,"turnCount":2,"tokens":"3.4k token","durationMs":4321,"spinnerFrame":3}"""))
        val running = s.subagents.getValue("a1")
        assertEquals(SubagentStatus.Running, running.status)
        assertEquals("reading 3 files…", running.activity)
        assertEquals(2, running.toolUses)
        assertEquals(2, running.turns)
        assertEquals("3.4k", running.tokens)
        // Whole seconds: an update that only moves the clock within the second changes nothing.
        assertEquals(4000L, running.durationMs)
        val same = run(s, update("a1", """{"displayName":"Explore","status":"running","activity":"reading 3 files…","toolUses":2,"turnCount":2,"tokens":"3.4k token","durationMs":4399,"spinnerFrame":4}"""))
        assertSame(s.subagents, same.subagents)
        s = run(s, end("a1", "Agent completed in 6.0s (5 tool uses).\n\nDone it.\n\nAgent ID: id-1 (pass it as `resume` to continue this agent)", """{"status":"completed","agentId":"id-1","toolUses":5,"durationMs":6012}"""))
        val done = s.subagents.getValue("a1")
        assertEquals(SubagentStatus.Done, done.status)
        assertNull(done.activity)
        assertEquals("Done it.", done.result)
        assertEquals(6012L, done.durationMs)
        assertEquals("id-1", done.agentId)
    }

    @Test
    fun parallelRunsStayApart() {
        val plan = args("Plan", "Plan the change", "Plan it")
        val general = args("general-purpose", "Write tests", "Write the tests")
        var s = run(SessionState(), """{"type":"agent_start"}""", """{"type":"message_end","message":${call("a1" to explore, "a2" to plan, "a3" to general)}}""")
        assertEquals(listOf("a1", "a2", "a3"), s.subagents.keys.toList())
        s = run(
            s,
            start("a1", explore), start("a2", plan), start("a3", general),
            update("a2", """{"status":"running","activity":"thinking…","toolUses":1}"""),
            end("a1", "Agent completed in 1s.\n\nA", """{"status":"completed","agentId":"x1"}"""),
        )
        assertEquals(SubagentStatus.Done, s.subagents.getValue("a1").status)
        assertEquals(SubagentStatus.Running, s.subagents.getValue("a2").status)
        assertEquals("thinking…", s.subagents.getValue("a2").activity)
        assertEquals(SubagentStatus.Running, s.subagents.getValue("a3").status)
        assertEquals("Plan", s.subagents.getValue("a2").type)
        // Pi stops: the foreground runs still open were cut off with the turn.
        s = run(s, """{"type":"agent_settled"}""")
        assertEquals(SubagentStatus.Done, s.subagents.getValue("a1").status)
        assertEquals(SubagentStatus.Stopped, s.subagents.getValue("a2").status)
        assertEquals(SubagentStatus.Stopped, s.subagents.getValue("a3").status)
    }

    @Test
    fun failuresKeepTheirError() {
        var s = run(SessionState(), start("a1", explore))
        s = run(s, end("a1", "Agent failed: model exploded\n\nSession file (every turn of this agent): /x.jsonl\n\nAgent ID: f1 (pass it as `resume` to continue this agent)", """{"status":"error","error":"model exploded","agentId":"f1"}""", isError = true))
        val failed = s.subagents.getValue("a1")
        assertEquals(SubagentStatus.Failed, failed.status)
        assertEquals("model exploded", failed.error)
        assertEquals("Agent failed: model exploded", failed.result)
        // A call the extension refused outright has no details: its text is the error.
        s = run(s, start("a2", args("Nope", "x", "y")), end("a2", "Unknown or disabled agent type: \"Nope\".", null, isError = true))
        assertEquals(SubagentStatus.Failed, s.subagents.getValue("a2").status)
        assertEquals("Unknown or disabled agent type: \"Nope\".", s.subagents.getValue("a2").error)
        val stopped = run(s, start("a3", explore), end("a3", "Agent was stopped by the user after 2s.\n\npartial", """{"status":"stopped","agentId":"s3"}""", isError = true))
        assertEquals(SubagentStatus.Stopped, stopped.subagents.getValue("a3").status)
    }

    @Test
    fun backgroundRunsEndThroughTheirNotification() {
        val bg = args("Explore", "Slow scan", "Scan slowly", background = true)
        var s = run(
            SessionState(),
            start("b1", bg),
            end("b1", "Agent started in background.\nAgent ID: bg-1\nType: Explore\n", """{"displayName":"Explore","status":"background","agentId":"bg-1","toolUses":0,"tokens":"","durationMs":0}"""),
            """{"type":"agent_settled"}""",
        )
        val started = s.subagents.getValue("b1")
        assertTrue(started.background)
        assertEquals("bg-1", started.agentId)
        // Settling the main turn does not stop a background run.
        assertEquals(SubagentStatus.Running, started.status)
        assertNull(started.tokens)
        s = run(
            s,
            """{"type":"message_end","message":${notification("""{"id":"bg-1","description":"Slow scan","status":"completed","toolUses":7,"turnCount":0,"totalTokens":15200,"durationMs":61000,"resultPreview":"Found 3 files."}""")}}""",
        )
        val done = s.subagents.getValue("b1")
        assertEquals(SubagentStatus.Done, done.status)
        assertEquals(7, done.toolUses)
        assertEquals("15k", done.tokens)
        assertEquals(61000L, done.durationMs)
        assertEquals("Found 3 files.", done.result)
        val notice = s.items.last() as SubagentNoticeItem
        assertEquals(listOf("bg-1"), notice.agentIds)
        assertEquals(listOf("b1"), notice.runKeys)
        // A later resume of the same agent is another run: the notice still names the run it ended.
        val resumed = run(s, start("b2", """{"resume":"bg-1","prompt":"again"}"""))
        assertEquals("bg-1", resumed.subagents.getValue("b2").agentId)
        assertEquals(listOf("b1"), (resumed.items.last { it is SubagentNoticeItem } as SubagentNoticeItem).runKeys)
        // Pi fetches the full answer: it replaces the preview.
        s = run(
            s,
            """{"type":"message_end","message":${result("r1", "Agent: bg-1\nType: Explore | Status: completed | Tool uses: 7 | Duration: 1m 1s\nDescription: Slow scan\n\nFound 3 files.\n\n- a\n- b\n- c", null, name = "get_subagent_result")}}""",
        )
        assertEquals("Found 3 files.\n\n- a\n- b\n- c", s.subagents.getValue("b1").result)
    }

    @Test
    fun aGroupNotificationEndsEveryRunItNames() {
        var s = SessionState()
        for (i in 1..3) {
            s = run(s, start("b$i", args("Explore", "Task $i", "p$i", background = true)), end("b$i", "Agent started in background.", """{"status":"background","agentId":"g$i"}"""))
        }
        s = run(
            s,
            """{"type":"message_end","message":${notification("""{"id":"g1","status":"completed","resultPreview":"one","others":[{"id":"g2","status":"error","error":"boom"},{"id":"g3","status":"stopped"}]}""")}}""",
        )
        assertEquals(SubagentStatus.Done, s.subagents.getValue("b1").status)
        assertEquals(SubagentStatus.Failed, s.subagents.getValue("b2").status)
        assertEquals("boom", s.subagents.getValue("b2").error)
        assertEquals(SubagentStatus.Stopped, s.subagents.getValue("b3").status)
        assertEquals(listOf("g1", "g2", "g3"), (s.items.last() as SubagentNoticeItem).agentIds)
    }

    @Test
    fun transcriptFromTheSessionFile() {
        val bg = args("Explore", "Slow scan", "Scan slowly", background = true)
        var s = run(SessionState(), start("b1", bg), end("b1", "Agent started in background.", """{"status":"background","agentId":"bg-1"}"""))
        val messages = listOf(
            """{"role":"user","content":"Scan slowly","timestamp":1000}""",
            """{"role":"assistant","content":[{"type":"thinking","thinking":"Let me look."},{"type":"toolCall","id":"t1","name":"read","arguments":{"path":"src/a.kt"}}],"usage":{"input":100,"output":20,"cacheWrite":5},"timestamp":1100}""",
        )
        s = run(s, """{"type":"subagent_transcript","key":"b1","file":"/s/x.jsonl","reset":true,"messages":[${messages.joinToString(",")}]}""")
        var agent = s.subagents.getValue("b1")
        val t = agent.transcript!!
        assertEquals("/s/x.jsonl", t.file)
        assertTrue(t.items.first() is UserItem)
        assertEquals(ToolStatus.Pending, (t.items.last() as ToolItem).status)
        assertEquals("reading src/a.kt", agent.activity)
        assertEquals("125", agent.tokens)
        assertEquals(1000L, agent.startedAt)
        // More lines of the same file are appended; their tool results close the calls.
        s = run(
            s,
            """{"type":"subagent_transcript","key":"b1","file":"/s/x.jsonl","messages":[{"role":"toolResult","toolCallId":"t1","toolName":"read","content":[{"type":"text","text":"x"}],"isError":false},{"role":"assistant","content":[{"type":"text","text":"All good."}],"usage":{"input":200,"output":10}}]}""",
        )
        agent = s.subagents.getValue("b1")
        assertEquals(ToolStatus.Done, agent.transcript!!.items.filterIsInstance<ToolItem>().single().status)
        assertEquals(1, agent.toolUses)
        assertEquals(2, agent.turns)
        // Between messages nothing names what it does: it works on its next message.
        assertEquals("working…", agent.activity)
        assertNull(agent.result)
        // Done: the last reply in the file is the whole answer, longer than a notification's preview.
        s = run(s, """{"type":"message_end","message":${notification("""{"id":"bg-1","status":"completed","resultPreview":"All…"}""")}}""")
        assertEquals("All…", s.subagents.getValue("b1").result)
        s = run(s, """{"type":"subagent_transcript","key":"b1","file":"/s/x.jsonl","messages":[]}""")
        assertEquals("All good.", s.subagents.getValue("b1").result)
        assertTrue(s.subagents.getValue("b1").transcript!!.items.any { it is AssistantTextItem && it.text == "All good." })
    }

    @Test
    fun aReconnectKeepsTranscriptsAndBackgroundProgress() {
        val bg = args("Explore", "Slow scan", "Scan slowly", background = true)
        val history = arrayOf(user, call("b1" to bg), result("b1", "Agent started in background.", """{"status":"background","agentId":"bg-1"}"""))
        var s = run(SessionState(), hello(false, *history))
        assertEquals(SubagentStatus.Running, s.subagents.getValue("b1").status)
        s = run(s, """{"type":"subagent_transcript","key":"b1","file":"/s/x.jsonl","reset":true,"messages":[{"role":"user","content":"Scan slowly"}]}""")
        val again = run(s, hello(false, *history))
        assertEquals(s.subagents.getValue("b1").transcript, again.subagents.getValue("b1").transcript)
        assertEquals("working…", again.subagents.getValue("b1").activity)
        // An idle snapshot of an open foreground call: it ended with its turn.
        val cut = run(SessionState(), hello(false, user, call("a1" to explore)))
        assertEquals(SubagentStatus.Stopped, cut.subagents.getValue("a1").status)
        val live = run(SessionState(), hello(true, user, call("a1" to explore)))
        assertEquals(SubagentStatus.Queued, live.subagents.getValue("a1").status)
    }

    @Test
    fun aResumeContinuesTheSameAgent() {
        var s = run(SessionState(), start("a1", explore), end("a1", "Agent completed in 1s.\n\nfirst", """{"status":"completed","agentId":"r-1","displayName":"Explore"}"""))
        s = run(s, start("a2", """{"resume":"r-1","prompt":"Go deeper"}"""))
        val resumed = s.subagents.getValue("a2")
        assertTrue(resumed.resumed)
        assertEquals("r-1", resumed.agentId)
        assertEquals("Explore", resumed.type)
        assertEquals("Find auth files", resumed.description)
        assertEquals("Go deeper", resumed.prompt)
        assertEquals("r-1", SubagentReducer.byAgentId(s, "r-1")?.agentId)
    }

    @Test
    fun activityNamesOpenToolCalls() {
        fun tool(name: String, args: String, status: ToolStatus) =
            ToolItem("tool:$name", name, name, SessionReducer.parse(args), status = status)
        assertEquals("running npm test", SubagentReducer.activityOf(listOf(tool("bash", """{"command":"npm test\nmore"}""", ToolStatus.Pending))))
        assertEquals("reading 2 files…", SubagentReducer.activityOf(listOf(tool("read", """{"path":"a"}""", ToolStatus.Pending), tool("read", """{"path":"b"}""", ToolStatus.Pending).copy(toolCallId = "r2"))))
        assertEquals("working…", SubagentReducer.activityOf(listOf(tool("bash", """{"command":"ls"}""", ToolStatus.Done))))
        assertEquals("thinking…", SubagentReducer.activityOf(listOf(ThinkingItem("t", "hm", streaming = true))))
        assertEquals("writing…", SubagentReducer.activityOf(listOf(AssistantTextItem("x", "Here", streaming = true))))
    }

    private val bg = args("Explore", "Slow scan", "Scan slowly", background = true)
    private val bgHistory = arrayOf(user, call("b1" to bg), result("b1", "Agent started in background.", """{"status":"background","agentId":"bg-1"}"""))

    @Test
    fun theComputerAdvertisesSubagentStreams() {
        val old = run(SessionState(), hello(false))
        assertFalse(old.host!!.streamsSubagents)
        val new = run(SessionState(), """{"type":"remote_hello","host":{"name":"mac","version":"1.1.5","cwd":"/p"},"features":["subagent_stream","later"],"state":{},"messages":[]}""")
        assertTrue(new.host!!.streamsSubagents)
        assertEquals(setOf("subagent_stream", "later"), new.host.features)
    }

    @Test
    fun streamedMessageShowsLiveThenTakesItsSavedPlace() {
        var s = run(SessionState(), hello(true, *bgHistory))
        // The computer's saved messages: subagent_entries with runId.
        s = run(s, """{"type":"subagent_entries","runId":"b1","file":"/c/x.jsonl","reset":true,"messages":[{"role":"user","content":"Scan slowly"}]}""")
        assertEquals(1, s.subagents.getValue("b1").transcript!!.items.size)
        // Its message streams: thinking, then text.
        s = run(
            s,
            """{"type":"subagent_events","runId":"b1","events":[
                {"type":"message_start","message":{"role":"assistant","content":[]}},
                {"type":"message_update","assistantMessageEvent":{"type":"thinking_start","contentIndex":0}},
                {"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","contentIndex":0,"delta":"Let me "}},
                {"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","contentIndex":0,"delta":"look."}}
            ]}""",
        )
        var t = s.subagents.getValue("b1").transcript!!
        val thinking = t.live.single() as ThinkingItem
        assertEquals("Let me look.", thinking.text)
        assertTrue(thinking.streaming)
        assertEquals("thinking…", s.subagents.getValue("b1").activity)
        s = run(
            s,
            """{"type":"subagent_events","runId":"b1","events":[
                {"type":"message_update","assistantMessageEvent":{"type":"thinking_end","contentIndex":0,"content":"Let me look."}},
                {"type":"message_update","assistantMessageEvent":{"type":"text_start","contentIndex":1}},
                {"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":1,"delta":"Two files"}},
                {"type":"message_end","message":{"role":"assistant","content":[]}}
            ]}""",
        )
        t = s.subagents.getValue("b1").transcript!!
        assertEquals("writing…", s.subagents.getValue("b1").activity)
        val liveKeys = t.live.map { it.key }
        // message_end leaves it: the saved message replaces it, with the same row keys.
        assertEquals(2, t.live.size)
        s = run(
            s,
            """{"type":"subagent_entries","runId":"b1","file":"/c/x.jsonl","reset":false,"messages":[{"role":"assistant","content":[{"type":"thinking","thinking":"Let me look."},{"type":"text","text":"Two files"}]}]}""",
        )
        t = s.subagents.getValue("b1").transcript!!
        assertTrue(t.live.isEmpty())
        assertNull(t.stream)
        assertEquals(liveKeys, t.items.drop(1).map { it.key })
        assertEquals("Two files", (t.items.last() as AssistantTextItem).text)
        // Keys of streamed and saved messages never meet.
        assertEquals(t.items.size, t.items.map { it.key }.toSet().size)
    }

    @Test
    fun aResetKeepsTheStreamAndAToolStartMarksItsCallRunning() {
        var s = run(SessionState(), hello(true, *bgHistory))
        s = run(s, """{"type":"subagent_entries","runId":"b1","file":"/c/x.jsonl","reset":true,"messages":[{"role":"user","content":"Scan"},{"role":"assistant","content":[{"type":"toolCall","id":"t1","name":"bash","arguments":{"command":"ls"}}]}]}""")
        assertEquals(ToolStatus.Pending, s.subagents.getValue("b1").transcript!!.items.filterIsInstance<ToolItem>().single().status)
        s = run(s, """{"type":"subagent_events","runId":"b1","events":[{"type":"tool_execution_start","toolCallId":"t1","toolName":"bash","args":{"command":"ls"}}]}""")
        assertEquals(ToolStatus.Running, s.subagents.getValue("b1").transcript!!.items.filterIsInstance<ToolItem>().single().status)
        s = run(s, """{"type":"subagent_events","runId":"b1","events":[{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"Hi"}}]}""")
        s = run(s, """{"type":"subagent_entries","runId":"b1","file":"/c/x.jsonl","reset":true,"messages":[{"role":"user","content":"Scan"}]}""")
        val t = s.subagents.getValue("b1").transcript!!
        assertEquals(1, t.items.size)
        assertEquals("Hi", (t.live.single() as AssistantTextItem).text)
        // Events for a run this session does not have change nothing.
        assertSame(s, run(s, """{"type":"subagent_events","runId":"nope","events":[]}"""))
    }
}
