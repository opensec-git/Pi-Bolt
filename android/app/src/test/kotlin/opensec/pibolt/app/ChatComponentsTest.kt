package opensec.pibolt.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.session.ActionsRow
import opensec.pibolt.app.ui.session.FileChange
import opensec.pibolt.app.ui.session.NEXT_EDIT
import opensec.pibolt.app.ui.session.FoldRow
import opensec.pibolt.app.ui.session.MdCache
import opensec.pibolt.app.ui.session.PendingSend
import opensec.pibolt.app.ui.session.PendingUserRow
import opensec.pibolt.app.ui.session.StatusText
import opensec.pibolt.app.ui.session.TextRow
import opensec.pibolt.app.ui.session.ThinkingRow
import opensec.pibolt.app.ui.session.ThinkingRules
import opensec.pibolt.app.ui.session.ToolGroupRow
import opensec.pibolt.app.ui.session.ToolRow
import opensec.pibolt.app.ui.session.TranscriptModel
import opensec.pibolt.app.ui.session.UserRow
import opensec.pibolt.app.ui.session.fileChange
import opensec.pibolt.app.ui.session.formatStepElapsed
import opensec.pibolt.app.ui.session.groupDetail
import opensec.pibolt.app.ui.session.groupSummary
import opensec.pibolt.app.ui.session.mergeChanges
import opensec.pibolt.app.ui.session.previewStart
import opensec.pibolt.protocol.session.AssistantTextItem
import opensec.pibolt.protocol.session.ChatItem
import opensec.pibolt.protocol.session.ThinkingItem
import opensec.pibolt.protocol.session.ToolItem
import opensec.pibolt.protocol.session.ToolStatus
import opensec.pibolt.protocol.session.UserItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatComponentsTest {
    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    private fun tool(key: String, name: String, status: ToolStatus = ToolStatus.Done, vararg args: Pair<String, String>, details: JsonObject? = null) =
        ToolItem("tool:$key", key, name, args(*args), status = status, details = details)

    private fun read(key: String, path: String, status: ToolStatus = ToolStatus.Done) = tool(key, "read", status, "path" to path)

    private fun rows(items: List<ChatItem>, live: Boolean = false, folds: Set<String> = emptySet(), groups: Set<String> = emptySet(), pending: PendingSend? = null, aliases: Map<String, String> = emptyMap()) =
        TranscriptModel.rows(items, live, folds, emptyMap(), MdCache(), groups, pending, aliases)

    // Status line

    @Test
    fun statusLineNamesTheRunningTool() {
        val bash = tool("b", "bash", ToolStatus.Running, "command" to "npm test\necho done")
        val activity = TranscriptModel.activity(listOf(UserItem("u", "go"), bash), emptyList())
        assertEquals(StatusText("Run", "npm test", path = false, mono = true), TranscriptModel.statusText(QuadMode.Run, activity, sending = false))

        val reading = TranscriptModel.activity(listOf(UserItem("u", "go"), read("r", "src/a.kt", ToolStatus.Running)), emptyList())
        assertEquals(StatusText("Read", "src/a.kt", path = true, mono = true), TranscriptModel.statusText(QuadMode.Run, reading, sending = false))
    }

    @Test
    fun statusLineForSubagentsParallelToolsSendingAndIdleModes() {
        val agent = tool("t", "task", ToolStatus.Running, "description" to "Find the flaky test")
        val other = tool("b", "bash", ToolStatus.Running, "command" to "ls")
        val activity = TranscriptModel.activity(listOf(UserItem("u", "go"), other, agent), emptyList())
        val text = TranscriptModel.statusText(QuadMode.Agent, activity, sending = false)
        assertEquals("Agent working", text.label)
        assertEquals("Find the flaky test", text.detail)
        // An agent's task is prose: not in mono.
        assertFalse(text.mono)
        assertEquals(1, text.more)

        assertEquals(StatusText("Sending"), TranscriptModel.statusText(QuadMode.Think, TranscriptModel.Activity(false, false, false), sending = true))
        assertEquals(StatusText("Writing"), TranscriptModel.statusText(QuadMode.Write, TranscriptModel.Activity(false, false, true), sending = false))
        // A tool that is not running (only its arguments stream) leaves the mode's label.
        assertEquals(StatusText("Thinking"), TranscriptModel.statusText(QuadMode.Think, activity.copy(running = null, runningCount = 0), sending = false))
    }

    @Test
    fun withThinkingOffTheStatusLineSaysWorking() {
        val idle = TranscriptModel.Activity(false, false, false)
        assertEquals(StatusText("Working"), TranscriptModel.statusText(QuadMode.Think, idle, sending = false, thinking = false))
        // Other modes keep their words; sending still says so.
        assertEquals(StatusText("Writing"), TranscriptModel.statusText(QuadMode.Write, idle.copy(writing = true), sending = false, thinking = false))
        assertEquals(StatusText("Sending"), TranscriptModel.statusText(QuadMode.Think, idle, sending = true, thinking = false))
    }

    @Test
    fun thinkingIsOffForLevelOffOrAModelThatDoesNotReason() {
        assertTrue(ThinkingRules.on("medium", modelReasons = true))
        // Not reported yet: on.
        assertTrue(ThinkingRules.on(null, modelReasons = null))
        assertFalse(ThinkingRules.on("off", modelReasons = true))
        assertFalse(ThinkingRules.on("high", modelReasons = false))
        assertFalse(ThinkingRules.on(null, modelReasons = false))
    }

    @Test
    fun withThinkingOffAnEmptyReasoningBlockGetsNoRow() {
        val empty = ThinkingItem("th1", "", streaming = true)
        val redacted = ThinkingItem("th2", " ", streaming = false, redacted = true)
        val real = ThinkingItem("th3", "Check the guard first", streaming = false)
        val items = listOf(UserItem("u", "go"), empty, redacted, real, AssistantTextItem("a", "Done", streaming = false))
        assertEquals(listOf(UserItem("u", "go"), real, AssistantTextItem("a", "Done", streaming = false)), ThinkingRules.visible(items, on = false))
        // On: every block keeps its row (the same list, no copy).
        assertTrue(ThinkingRules.visible(items, on = true) === items)
        assertFalse(ThinkingRules.shows(empty, on = false))
        assertTrue(ThinkingRules.shows(real, on = false))
        assertTrue(ThinkingRules.shows(empty, on = true))
        // The transcript then has no thinking row for it.
        assertTrue(rows(ThinkingRules.visible(listOf(UserItem("u", "go"), empty), on = false), live = true).none { it is ThinkingRow })
    }

    // Elapsed time

    @Test
    fun stepElapsedIsQuietForTheFirstSecond() {
        assertNull(formatStepElapsed(0))
        assertNull(formatStepElapsed(999))
        assertEquals("1s", formatStepElapsed(1_000))
        assertEquals("59s", formatStepElapsed(59_999))
        assertEquals("1m 3s", formatStepElapsed(63_000))
        assertEquals("1h 2m", formatStepElapsed(3_720_000))
    }

    // Grouping read-only steps

    @Test
    fun threeOrMoreFinishedReadOnlyStepsFoldIntoOneRow() {
        val items = listOf(
            UserItem("u", "look around"),
            read("a", "src/a.kt"),
            AssistantTextItem("blank", "  ", streaming = false),
            read("b", "src/b.kt"),
            tool("g", "grep", ToolStatus.Done, "pattern" to "TODO"),
            tool("e", "edit", ToolStatus.Done, "path" to "src/a.kt"),
        )
        val live = rows(items, live = true)
        val group = live.filterIsInstance<ToolGroupRow>().single()
        assertEquals(listOf("tool:a", "tool:b", "tool:g"), group.tools.map { it.key })
        assertEquals("group:tool:a", group.key)
        // The edit is never grouped.
        assertEquals(listOf("tool:e"), live.filterIsInstance<ToolRow>().map { it.key })
        assertEquals("Read 2 files, ran 1 search", groupSummary(group.tools))
        assertEquals("a.kt, b.kt, TODO", groupDetail(group.tools))
        assertTrue(TranscriptModel.keysUnique(live))
    }

    @Test
    fun shortRunsFailedAndRunningStepsStayOnTheirOwn() {
        val two = listOf(UserItem("u", "x"), read("a", "a"), read("b", "b"))
        assertTrue(rows(two, live = true).none { it is ToolGroupRow })

        val failed = listOf(UserItem("u", "x"), read("a", "a"), read("b", "b", ToolStatus.Error), read("c", "c"), read("d", "d"))
        assertTrue(rows(failed, live = true).none { it is ToolGroupRow })

        // The run grows as its last member finishes; the running one waits beside the group.
        val growing = listOf(UserItem("u", "x"), read("a", "a"), read("b", "b"), read("c", "c"), read("d", "d", ToolStatus.Running))
        val r = rows(growing, live = true)
        assertEquals(3, r.filterIsInstance<ToolGroupRow>().single().tools.size)
        assertEquals(listOf("tool:d"), r.filterIsInstance<ToolRow>().map { it.key })
    }

    @Test
    fun anOpenGroupListsItsStepsOnARail() {
        val items = listOf(UserItem("u", "x"), read("a", "a"), read("b", "b"), read("c", "c"))
        val r = rows(items, live = true, groups = setOf("group:tool:a"))
        val group = r.filterIsInstance<ToolGroupRow>().single()
        assertTrue(group.expanded)
        assertNull(group.rail)
        val members = r.filterIsInstance<ToolRow>()
        assertEquals(listOf("tool:a", "tool:b", "tool:c"), members.map { it.key })
        assertEquals(listOf(true, false, false), members.map { it.rail!!.first })
        assertEquals(listOf(false, false, true), members.map { it.rail!!.last })
        assertEquals(listOf(0, 1, 2), members.map { it.rail!!.index })
        assertTrue(members.all { it.rail!!.stagger == group.key })
    }

    @Test
    fun anOpenedFoldPutsItsStepsOnOneRailAndLeavesTheAnswerOff() {
        val items = listOf(
            UserItem("u", "x"),
            tool("b", "bash", ToolStatus.Done, "command" to "ls"),
            AssistantTextItem("mid", "Now the edit.", streaming = false),
            tool("e", "edit", ToolStatus.Done, "path" to "a.kt"),
            AssistantTextItem("answer", "Done.", streaming = false),
        )
        val r = rows(items, folds = setOf("u"))
        val bash = r.first { it.key == "tool:b" } as ToolRow
        val mid = r.first { it.key == "mid#0" } as TextRow
        val edit = r.first { it.key == "tool:e" } as ToolRow
        val answer = r.first { it.key == "answer#0" } as TextRow
        assertTrue(bash.rail!!.first)
        val midRail = mid.rail!!
        assertFalse(midRail.first || midRail.last)
        assertTrue(edit.rail!!.last)
        assertEquals(listOf(0, 1, 2), listOf(bash, mid, edit).map { (it as? ToolRow)?.rail?.index ?: (it as TextRow).rail!!.index })
        assertNull(answer.rail)
        // Closed, nothing carries a rail.
        assertTrue(rows(items).filterIsInstance<TextRow>().all { it.rail == null })
    }

    // Optimistic send

    @Test
    fun aPendingSendShowsAsItsOwnTurnUntilEchoed() {
        val before = listOf(UserItem("m0", "hi"), AssistantTextItem("m1", "Hello.", streaming = false))
        val pending = PendingSend(1, "fix the test", lastUserKey = "m0", at = 1_000)
        val r = rows(before, pending = pending)
        assertTrue(r.last() is PendingUserRow)
        assertEquals("pending:1", r.last().key)
        // The previous reply is no longer the last turn: no action bar while sending.
        assertTrue(r.none { it is ActionsRow })

        // Not echoed yet: same newest user message, still running or recent.
        assertFalse(PendingSend.resolved(pending, "m0", settledAt = 0, running = false, now = 2_000))
        assertFalse(PendingSend.resolved(pending, "m0", settledAt = 0, running = true, now = 1_000 + PendingSend.TIMEOUT_MS + 1))
        // Echoed: a newer user message.
        assertTrue(PendingSend.resolved(pending, "m2", settledAt = 0, running = true, now = 1_500))
        // The run it started ended, or it timed out while idle.
        assertTrue(PendingSend.resolved(pending, "m0", settledAt = 1_200, running = false, now = 1_300))
        assertTrue(PendingSend.resolved(pending, "m0", settledAt = 0, running = false, now = 1_000 + PendingSend.TIMEOUT_MS + 1))

        // The echo takes over the pending row's key, so the list keeps the row in place.
        val echoed = before + UserItem("m2", "fix the test")
        val user = rows(echoed, live = true, aliases = mapOf("m2" to pending.rowKey)).filterIsInstance<UserRow>().last()
        assertEquals("pending:1", user.key)
    }

    @Test
    fun onlyPlainIdlePromptsAreShownBeforeTheEcho() {
        assertTrue(PendingSend.eligible("fix it", running = false))
        assertFalse(PendingSend.eligible("fix it", running = true))
        assertFalse(PendingSend.eligible("/compact", running = false))
        assertFalse(PendingSend.eligible("   ", running = false))
        assertEquals("m2", PendingSend.lastUserKey(listOf(UserItem("m0", "a"), AssistantTextItem("m1", "b", false), UserItem("m2", "c"), AssistantTextItem("m3", "d", false))))
        assertNull(PendingSend.lastUserKey(emptyList()))
    }

    // Action bar

    @Test
    fun theLastFinishedReplyGetsActions() {
        val items = listOf(
            UserItem("m0", "first"),
            AssistantTextItem("m1", "One.", streaming = false),
            UserItem("m2", "second"),
            tool("b", "bash", ToolStatus.Done, "command" to "ls"),
            AssistantTextItem("m3", "Looking.", streaming = false),
            tool("c", "bash", ToolStatus.Done, "command" to "ls -la"),
            AssistantTextItem("m4", "Two.\n\nThree.", streaming = false),
        )
        val actions = rows(items).filterIsInstance<ActionsRow>().single()
        assertEquals("m2", actions.turnKey)
        // The answer only: text after the last step.
        assertEquals("Two.\n\nThree.", actions.reply)
        assertEquals("second", actions.prompt)
        assertTrue(rows(items, live = true).none { it is ActionsRow })

        val withImage = items.map { if (it.key == "m2") UserItem("m2", "what is this", imageCount = 1) else it }
        assertNull(rows(withImage).filterIsInstance<ActionsRow>().single().prompt)
    }

    // Edited files under the fold

    @Test
    fun theFoldListsEditedFilesWithTheirLineCounts() {
        val diff1 = buildJsonObject { put("diff", "+1 a\n-2 b\n+2 c") }
        val diff2 = buildJsonObject { put("diff", "+9 d") }
        val items = listOf(
            UserItem("u", "edit"),
            tool("e1", "edit", ToolStatus.Done, "path" to "src/a.kt", details = diff1),
            tool("w", "write", ToolStatus.Done, "path" to "src/new.kt", "content" to "one\ntwo\n"),
            tool("e2", "edit", ToolStatus.Done, "path" to "src/a.kt", details = diff2),
            tool("bad", "edit", ToolStatus.Error, "path" to "src/c.kt", details = diff2),
            AssistantTextItem("a", "Done.", streaming = false),
        )
        val fold = rows(items).filterIsInstance<FoldRow>().single()
        assertEquals(listOf("src/a.kt" to (3 to 1), "src/new.kt" to (2 to 0)), fold.files.map { it.path to (it.added to it.removed) })
        assertEquals("+1 a\n-2 b\n+2 c\n$NEXT_EDIT\n+9 d", fold.files[0].diff)
        assertEquals("+1 one\n+2 two", fold.files[1].diff)
        assertNull(fileChange(tool("r", "read", ToolStatus.Done, "path" to "src/a.kt")))
        assertEquals(listOf(FileChange("x", 1, 0, "+a")), mergeChanges(listOf(FileChange("x", 1, 0, "+a"))))
    }

    // Live reasoning window

    @Test
    fun theReasoningWindowMovesRarelyAndNeverBack() {
        val short = (1..40).joinToString("\n") { "line $it" }
        assertEquals(0, previewStart(short, 0))
        val long = (1..70).joinToString("\n") { "line $it" }
        val start = previewStart(long, 0)
        assertTrue(long.substring(start).startsWith("line 41"))
        // Stays put while fewer than 60 lines follow it.
        val longer = long + "\nline 71"
        assertEquals(start, previewStart(longer, start))
        assertEquals(start, previewStart(longer, start + 0))
    }
}
