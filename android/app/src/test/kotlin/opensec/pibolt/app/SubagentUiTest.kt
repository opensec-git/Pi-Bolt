package opensec.pibolt.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import opensec.pibolt.app.engine.SessionIndex
import opensec.pibolt.app.session.SubagentTail
import opensec.pibolt.app.ui.session.FoldRow
import opensec.pibolt.app.ui.session.MdCache
import opensec.pibolt.app.ui.session.SubagentNoticeRow
import opensec.pibolt.app.ui.session.SubagentRow
import opensec.pibolt.app.ui.session.SubagentStackRow
import opensec.pibolt.app.ui.session.ToolRow
import opensec.pibolt.app.ui.session.TranscriptModel
import opensec.pibolt.app.ui.session.subagent.SubagentFormat
import opensec.pibolt.protocol.arr
import opensec.pibolt.protocol.bool
import opensec.pibolt.protocol.session.AssistantTextItem
import opensec.pibolt.protocol.session.ChatItem
import opensec.pibolt.protocol.session.NoticeItem
import opensec.pibolt.protocol.session.SessionReducer
import opensec.pibolt.protocol.session.SessionState
import opensec.pibolt.protocol.session.Subagent
import opensec.pibolt.protocol.session.SubagentNoticeItem
import opensec.pibolt.protocol.session.SubagentStatus
import opensec.pibolt.protocol.session.SubagentTranscript
import opensec.pibolt.protocol.session.ToolItem
import opensec.pibolt.protocol.session.ToolStatus
import opensec.pibolt.protocol.session.UserItem
import opensec.pibolt.protocol.str
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Subagent cards in the transcript, their texts, and reading a subagent's session file. */
class SubagentUiTest {
    private fun agent(id: String, status: ToolStatus = ToolStatus.Running) = ToolItem("tool:$id", id, "Agent", null, status = status)

    private fun run(key: String, status: SubagentStatus = SubagentStatus.Running, background: Boolean = false, agentId: String? = null) =
        Subagent(key = key, type = "Explore", description = "Task $key", status = status, background = background, agentId = agentId)

    @Test
    fun agentCallsBecomeCardsAndParallelOnesAStack() {
        val items: List<ChatItem> = listOf(
            UserItem("u", "go"),
            agent("a1"),
            AssistantTextItem("m1.1", "", streaming = false),
            agent("a2"),
            agent("a3"),
            AssistantTextItem("m2.0", "Waiting.", streaming = false),
            agent("a4"),
            ToolItem("tool:b", "b", "bash", null, status = ToolStatus.Done),
        )
        val runs = listOf("a1", "a2", "a3", "a4").associateWith { run(it) }
        val rows = TranscriptModel.rows(items, liveTurn = true, expanded = emptySet(), durations = emptyMap(), md = MdCache(), subagents = runs)
        val stack = rows.filterIsInstance<SubagentStackRow>().single()
        assertEquals(listOf("a1", "a2", "a3"), stack.runs.map { it.key })
        assertEquals("agents:tool:a1", stack.key)
        assertEquals(false, stack.expanded)
        assertEquals("a4", rows.filterIsInstance<SubagentRow>().single().run.key)
        assertEquals(1, rows.count { it is ToolRow })
        assertTrue(TranscriptModel.keysUnique(rows))
        // Inside a subagent's own transcript, calls stay tool rows.
        val plain = TranscriptModel.rows(items, liveTurn = true, expanded = emptySet(), durations = emptyMap(), md = MdCache(), agentCards = false)
        assertTrue(plain.none { it is SubagentRow || it is SubagentStackRow })
    }

    @Test
    fun aFinishedTurnFoldsItsStepsButKeepsAgentCards() {
        val items: List<ChatItem> = listOf(
            UserItem("u", "go"),
            agent("fg", ToolStatus.Done),
            agent("bg", ToolStatus.Done),
            AssistantTextItem("m1", "Started.", streaming = false),
            SubagentNoticeItem("n1", listOf("bg-1"), "<task-notification>", listOf("bg")),
        )
        val runs = mapOf(
            "fg" to run("fg", SubagentStatus.Done),
            "bg" to run("bg", SubagentStatus.Done, background = true, agentId = "bg-1").copy(transcript = SubagentTranscript("/x")),
        )
        val rows = TranscriptModel.rows(items, liveTurn = false, expanded = emptySet(), durations = emptyMap(), md = MdCache(), subagents = runs)
        assertTrue(rows.any { it is FoldRow })
        val cards = rows.filterIsInstance<SubagentRow>()
        assertEquals(listOf("agents:tool:fg"), rows.filterIsInstance<SubagentStackRow>().map { it.key })
        assertTrue(cards.isEmpty())
        val stacked = rows.filterIsInstance<SubagentStackRow>().single().runs
        assertEquals(listOf("fg", "bg"), stacked.map { it.key })
        // Rows carry runs without their transcript, so a frame that only grows a transcript compares cheaply.
        assertNull(stacked[1].transcript)
        val notice = rows.filterIsInstance<SubagentNoticeRow>().single()
        assertEquals(listOf("bg"), notice.runs.map { it.key })
    }

    @Test
    fun cardTexts() {
        val running = run("a").copy(activity = "reading 3 files…", toolUses = 4, tokens = "12.3k", turns = 2)
        assertEquals("Reading 3 files…", SubagentFormat.activityLine(running))
        assertEquals("4 tools · 12.3k tokens · turn 2", SubagentFormat.metrics(running))
        assertEquals("No tools yet", SubagentFormat.metrics(run("b")))
        assertEquals("1 tool · turn 3/10", SubagentFormat.metrics(run("c").copy(toolUses = 1, turns = 3, maxTurns = 10)))
        assertEquals("Starting…", SubagentFormat.activityLine(run("d", SubagentStatus.Queued)))
        assertEquals("Queued in the background", SubagentFormat.activityLine(run("d", SubagentStatus.Queued, background = true)))
        assertEquals("Auth lives in src/auth.", SubagentFormat.activityLine(run("e", SubagentStatus.Done).copy(result = "## Auth lives in src/auth.\n\nMore")))
        assertEquals("Failed: boom", SubagentFormat.activityLine(run("f", SubagentStatus.Failed).copy(error = "boom\ntrace")))
        assertEquals("9.1s", SubagentFormat.finalDuration(run("g", SubagentStatus.Done).copy(durationMs = 9100)))
        assertNull(SubagentFormat.finalDuration(run("h").copy(durationMs = 9100)))
        assertEquals("Explore finished", SubagentFormat.endedTitle(run("i", SubagentStatus.Done)))
        assertEquals("General-purpose", SubagentFormat.typeLabel("general-purpose"))
    }

    @Test
    fun cardsNameTheAgentAndItsToolNotItsNarration() {
        // The name is the task title, else the prompt's first line, else the type.
        assertEquals("Find the auth flow", SubagentFormat.title(run("a").copy(type = "Explore", description = "find the auth flow")))
        assertEquals("Look at src", SubagentFormat.title(run("b").copy(type = "general-purpose", description = "", prompt = "look at **src**\nmore")))
        assertEquals("General-purpose", SubagentFormat.title(run("c").copy(type = "general-purpose", description = "")))
        // The extension's tool lines and the app's own pass; the start of the agent's reply does not.
        assertTrue(SubagentFormat.isToolActivity("reading…"))
        assertTrue(SubagentFormat.isToolActivity("running command, searching 2 patterns…"))
        assertTrue(SubagentFormat.isToolActivity("web_search…"))
        assertTrue(SubagentFormat.isToolActivity("reading src/auth.ts"))
        assertTrue(!SubagentFormat.isToolActivity("Let me look at the auth flow first…"))
        val explore = run("d").copy(type = "Explore", description = "Find the auth flow")
        assertEquals("Explore · Reading src/auth.ts", SubagentFormat.cardMeta(explore.copy(activity = "reading src/auth.ts")))
        assertEquals("Explore · Writing…", SubagentFormat.cardMeta(explore.copy(activity = "I found the login handler in")))
        assertEquals("Explore · Thinking…", SubagentFormat.cardMeta(explore.copy(activity = "thinking…")))
        assertEquals("Explore · 4 tools · 5 turns", SubagentFormat.cardMeta(explore.copy(status = SubagentStatus.Done, toolUses = 4, turns = 5)))
        assertEquals("Explore · Failed: boom", SubagentFormat.cardMeta(explore.copy(status = SubagentStatus.Failed, error = "boom")))
        // The session file names the file; a reply being written keeps the report.
        assertEquals("reading src/auth.ts", SubagentFormat.liveActivity("reading…", "reading src/auth.ts"))
        assertEquals("Now I", SubagentFormat.liveActivity("Now I", "reading src/auth.ts"))
        assertEquals("reading…", SubagentFormat.liveActivity("reading…", "thinking…"))
        assertEquals("Find the auth flow, Explore agent, running, Reading src/auth.ts", SubagentFormat.spoken(explore.copy(activity = "reading src/auth.ts")))
    }

    @Test
    fun agentStatesAndColours() {
        assertEquals(SubagentFormat.AgentState.Starting, SubagentFormat.stateOf(run("a", SubagentStatus.Queued)))
        assertEquals(SubagentFormat.AgentState.Queued, SubagentFormat.stateOf(run("a", SubagentStatus.Queued).copy(activity = "queued — waiting for a foreground slot")))
        assertEquals(SubagentFormat.AgentState.Running, SubagentFormat.stackState(listOf(run("a", SubagentStatus.Done), run("b"))))
        assertEquals(SubagentFormat.AgentState.Failed, SubagentFormat.stackState(listOf(run("a", SubagentStatus.Done), run("b", SubagentStatus.Failed))))
        assertEquals(SubagentFormat.AgentState.Done, SubagentFormat.stackState(listOf(run("a", SubagentStatus.Done))))
        // Colours follow the order of the runs, not their type: parallel agents of one type differ.
        val keys = listOf("k0", "k1", "k2", "k3", "k4", "k5")
        assertEquals(listOf(0, 1, 2, 3, 4, 0), keys.map { SubagentFormat.hueSlot(it, keys) })
        val runs = mapOf("x" to run("x").copy(type = "general-purpose"), "y" to run("y").copy(type = "general-purpose"))
        val items = listOf(UserItem("u", "go"), agent("x"), agent("y"))
        val stack = TranscriptModel.rows(items, liveTurn = true, expanded = emptySet(), durations = emptyMap(), md = MdCache(), subagents = runs).filterIsInstance<SubagentStackRow>().single()
        assertEquals(listOf(0, 1), stack.slots)
    }

    @Test
    fun sameLineAndMetrics() {
        assertTrue(SubagentFormat.sameLine("Now", "Now I search"))
        assertTrue(!SubagentFormat.sameLine("Now I search", "Running ls…"))
        assertEquals("4 tools · 5 turns", SubagentFormat.metrics(run("a", SubagentStatus.Done).copy(toolUses = 4, turns = 5)))
    }

    @Test
    fun subagentSessionsAreNotProjectSessions() {
        val agentDir = Files.createTempDirectory("agent").toFile()
        val project = Files.createTempDirectory("project").toFile()
        val index = SessionIndex(agentDir)
        val dir = index.sessionDir(project.absolutePath).apply { mkdirs() }
        val parent = File(dir, "1_parent.jsonl").apply {
            writeText(line("""{"type":"session","id":"parent"}""") + line("""{"type":"message","message":{"role":"user","content":"hi"}}"""))
        }
        File(dir, "2_child.jsonl").writeText(
            line("""{"type":"session","id":"child","parentSession":"${parent.path}"}""") +
                line("""{"type":"session_info","name":"Explore#1669f14e"}""") +
                line("""{"type":"message","message":{"role":"user","content":"sub"}}"""),
        )
        // A fork names its parent too, but not with an agent's name: it stays.
        File(dir, "3_fork.jsonl").writeText(
            line("""{"type":"session","id":"fork","parentSession":"${parent.path}"}""") + line("""{"type":"message","message":{"role":"user","content":"hi"}}"""),
        )
        assertEquals(setOf("parent", "fork"), index.list(project).map { it.id }.toSet())
        agentDir.deleteRecursively()
        project.deleteRecursively()
    }

    @Test
    fun stackTitles() {
        assertEquals("3 agents running", SubagentFormat.stackTitle(List(3) { run("r$it") }))
        assertEquals("1 of 3 agents running", SubagentFormat.stackTitle(listOf(run("a"), run("b", SubagentStatus.Done), run("c", SubagentStatus.Done))))
        assertEquals("2 agents done", SubagentFormat.stackTitle(listOf(run("a", SubagentStatus.Done), run("b", SubagentStatus.Done))))
        assertEquals("2 agents · 1 failed", SubagentFormat.stackTitle(listOf(run("a", SubagentStatus.Failed), run("b", SubagentStatus.Done))))
    }

    @Test
    fun howTheDetailScreenReachesAnAgent() {
        assertEquals(SubagentFormat.Reach.StopOnly, SubagentFormat.reach(run("a")))
        assertEquals(SubagentFormat.Reach.Steer, SubagentFormat.reach(run("b", background = true, agentId = "id1")))
        assertEquals(SubagentFormat.Reach.Resume, SubagentFormat.reach(run("c", SubagentStatus.Done, agentId = "id2")))
        assertEquals(SubagentFormat.Reach.None, SubagentFormat.reach(run("d", SubagentStatus.Done)))
        val steer = SubagentFormat.steerPrompt(run("b", background = true, agentId = "id1"), "  look at tests  ")
        assertTrue(steer.startsWith("Use steer_subagent to send this message to the running agent id1 (Explore: Task b)"))
        assertTrue(steer.endsWith("\n\nlook at tests"))
        assertTrue(SubagentFormat.resumePrompt(run("c", agentId = "id2"), "more").contains("resume"))
    }

    @Test
    fun withoutItsFileTheDetailShowsPromptAndAnswer() {
        val done = run("a", SubagentStatus.Done).copy(prompt = "Find it", result = "Found.")
        val items = SubagentFormat.detailItems(done)
        assertEquals("Find it", (items[0] as UserItem).text)
        assertEquals("Found.", (items[1] as AssistantTextItem).text)
        val failed = run("b", SubagentStatus.Failed).copy(prompt = "x", error = "boom")
        assertTrue(SubagentFormat.detailItems(failed).last() is NoticeItem)
        assertEquals(1, SubagentFormat.detailItems(run("c").copy(prompt = "x", result = "partial")).size)
    }

    // Session files

    private fun line(json: String) = json + "\n"

    @Test
    fun readsTheSubagentsSessionFileAsItGrows() {
        val dir = Files.createTempDirectory("subagents").toFile()
        val parent = File(dir, "2026-01-01_parent.jsonl").apply { writeText(line("""{"type":"session","id":"parent"}""")) }
        // Another session of the project, and another parent's subagent with the same prompt: neither is ours.
        File(dir, "2026-01-01_other.jsonl").writeText(line("""{"type":"session","id":"o"}"""))
        File(dir, "2026-01-01_foreign.jsonl").writeText(
            line("""{"type":"session","id":"f","parentSession":"/elsewhere.jsonl"}""") + line("""{"type":"message","message":{"role":"user","content":"Scan slowly"}}"""),
        )
        val child = File(dir, "2026-01-02_child.jsonl")
        child.writeText(
            line("""{"type":"session","id":"c","parentSession":"${parent.path}"}""") +
                line("""{"type":"session_info","name":"Explore#bgabcdef"}""") +
                line("""{"type":"message","message":{"role":"user","content":"Scan slowly","timestamp":5}}"""),
        )
        val state = MutableStateFlow(SessionState(subagents = mapOf("k" to Subagent(key = "k", prompt = "Scan slowly", status = SubagentStatus.Running))))
        val events = ArrayList<JsonObject>()
        val tail = SubagentTail(state, { parent.path }) { e ->
            events += e
            state.value = SessionReducer.reduce(state.value, e)
        }
        assertTrue(tail.pass())
        assertEquals(1, events.size)
        assertEquals(child.path, events[0].str("file"))
        assertEquals(true, events[0].bool("reset"))
        assertEquals(1, events[0].arr("messages")!!.size)
        // A line still being written is not read until it ends.
        child.appendText(line("""{"type":"message","message":{"role":"assistant","content":[{"type":"text","text":"Half"}]}}""") + """{"type":"message","mess""")
        tail.pass()
        assertEquals(2, events.size)
        assertEquals(1, events[1].arr("messages")!!.size)
        assertEquals(false, events[1].bool("reset"))
        child.appendText("""age":{"role":"assistant","content":[{"type":"text","text":"Done."}]}}""" + "\n")
        tail.pass()
        assertEquals(3, events.size)
        val items = state.value.subagents.getValue("k").transcript!!.items
        assertEquals(listOf("Scan slowly", "Half", "Done."), items.map { (it as? UserItem)?.text ?: (it as AssistantTextItem).text })
        // Nothing new: no event.
        tail.pass()
        assertEquals(3, events.size)
        dir.deleteRecursively()
    }

    @Test
    fun aKnownAgentIdPicksItsFileByName() {
        val dir = Files.createTempDirectory("subagents").toFile()
        val parent = File(dir, "p.jsonl").apply { writeText(line("""{"type":"session","id":"p"}""")) }
        fun child(name: String, agent: String) = File(dir, "$name.jsonl").apply {
            writeText(
                line("""{"type":"session","id":"$name","parentSession":"${parent.path}"}""") +
                    line("""{"type":"session_info","name":"Explore#$agent"}""") +
                    line("""{"type":"message","message":{"role":"user","content":"same prompt"}}"""),
            )
        }
        child("one", "11111111")
        val two = child("two", "22222222")
        val state = MutableStateFlow(
            SessionState(subagents = mapOf("k" to Subagent(key = "k", agentId = "22222222-3333-4", prompt = "same prompt", status = SubagentStatus.Running, background = true))),
        )
        val events = ArrayList<JsonObject>()
        SubagentTail(state, { parent.path }) { events += it }.pass()
        assertEquals(two.path, events.single().str("file"))
        dir.deleteRecursively()
    }

    @Test
    fun finishedRunsAreReadOnlyWhenWatched() {
        val dir = Files.createTempDirectory("subagents").toFile()
        val parent = File(dir, "p.jsonl").apply { writeText(line("""{"type":"session","id":"p"}""")) }
        File(dir, "c.jsonl").writeText(
            line("""{"type":"session","id":"c","parentSession":"${parent.path}"}""") +
                line("""{"type":"session_info","name":"Plan#abcdefgh"}""") +
                line("""{"type":"message","message":{"role":"user","content":"plan"}}"""),
        )
        val state = MutableStateFlow(SessionState(subagents = mapOf("k" to Subagent(key = "k", agentId = "abcdefgh-1", status = SubagentStatus.Done))))
        val events = ArrayList<JsonObject>()
        val tail = SubagentTail(state, { parent.path }) { events += it }
        assertEquals(false, tail.pass())
        assertTrue(events.isEmpty())
        tail.watch("k", true)
        assertTrue(tail.pass())
        assertEquals(1, events.size)
        dir.deleteRecursively()
    }

    @Test
    fun theDetailScreenNeverLooksStuck() {
        val running = run("w")
        // Nothing reported yet: working, not a frozen line.
        assertEquals("Working…", SubagentFormat.liveLine(running.copy(activity = null)))
        assertEquals("Thinking…", SubagentFormat.liveLine(running.copy(activity = "thinking…")))
        assertEquals("Reading src/a.kt", SubagentFormat.liveLine(running.copy(activity = "reading src/a.kt")))
        // When it ends, it says so.
        assertEquals("Finished", SubagentFormat.liveLine(run("w", SubagentStatus.Done)))
        assertEquals("Failed: boom", SubagentFormat.liveLine(run("w", SubagentStatus.Failed).copy(error = "boom\nmore")))
        assertEquals("Stopped", SubagentFormat.liveLine(run("w", SubagentStatus.Stopped)))
        // A vague line from the file never hides the extension's tool report.
        assertEquals("reading…", SubagentFormat.liveActivity("reading…", "working…"))
        assertEquals("reading src/a.kt", SubagentFormat.liveActivity(null, "reading src/a.kt"))
    }

    @Test
    fun theNoteSaysWhereMessagesComeFrom() {
        val running = run("n")
        val withMessages = running.copy(transcript = SubagentTranscript("/f", items = listOf(UserItem("u", "go"))))
        assertEquals("Live view needs Pi-Bolt 1.1.5 on the computer", SubagentFormat.note(running, SubagentFormat.Source.Unavailable))
        assertEquals("Loading messages…", SubagentFormat.note(running, SubagentFormat.Source.Stream))
        assertEquals("Loading messages…", SubagentFormat.note(running, SubagentFormat.Source.Device))
        assertEquals(null, SubagentFormat.note(withMessages, SubagentFormat.Source.Stream))
        assertEquals("Messages weren't saved. Showing the prompt and answer.", SubagentFormat.note(run("n", SubagentStatus.Done), SubagentFormat.Source.Stream))
        // Only a streamed message so far: it shows, no loading note.
        val streamed = running.copy(transcript = SubagentTranscript("/f", live = listOf(AssistantTextItem("live0.0", "Hi", streaming = true))))
        assertEquals(null, SubagentFormat.note(streamed, SubagentFormat.Source.Stream))
    }

    @Test
    fun theStreamedMessageShowsOnlyWhileTheRunIsLive() {
        val t = SubagentTranscript("/f", items = listOf(UserItem("u", "go")), live = listOf(AssistantTextItem("live0.0", "Hi", streaming = true)))
        assertEquals(2, SubagentFormat.detailItems(run("s").copy(transcript = t)).size)
        assertEquals(1, SubagentFormat.detailItems(run("s", SubagentStatus.Stopped).copy(transcript = t)).size)
    }
}
