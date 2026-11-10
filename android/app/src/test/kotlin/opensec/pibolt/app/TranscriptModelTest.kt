package opensec.pibolt.app

import opensec.pibolt.app.ui.session.FoldRow
import opensec.pibolt.app.ui.session.MdCache
import opensec.pibolt.app.ui.session.NoticeRow
import opensec.pibolt.app.ui.session.ActionsRow
import opensec.pibolt.app.ui.session.TextRow
import opensec.pibolt.app.ui.session.ToolRow
import opensec.pibolt.app.ui.session.TranscriptModel
import opensec.pibolt.app.ui.session.TurnEnd
import opensec.pibolt.app.ui.session.TurnMetaRow
import opensec.pibolt.app.ui.session.UserRow
import opensec.pibolt.protocol.session.AssistantTextItem
import opensec.pibolt.protocol.session.ChatItem
import opensec.pibolt.protocol.session.NoticeItem
import opensec.pibolt.protocol.session.NoticeKind
import opensec.pibolt.protocol.session.ThinkingItem
import opensec.pibolt.protocol.session.ToolItem
import opensec.pibolt.protocol.session.ToolStatus
import opensec.pibolt.protocol.session.UserItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranscriptModelTest {
    private val turn: List<ChatItem> = listOf(
        UserItem("m0", "fix it"),
        ThinkingItem("m1.0", "hmm", streaming = false),
        AssistantTextItem("m1.1", "Running a command.", streaming = false),
        ToolItem("tool:a", "a", "bash", null, status = ToolStatus.Done),
        NoticeItem("m2", NoticeKind.Info, "Note"),
        AssistantTextItem("m3.0", "Done.\n\n- one\n- two", streaming = false),
    )

    @Test
    fun finishedTurnFoldsItsSteps() {
        val rows = TranscriptModel.rows(turn, liveTurn = false, expanded = emptySet(), durations = mapOf("m0" to 12_000L), md = MdCache())
        assertTrue(rows[0] is UserRow)
        assertEquals(TurnMetaRow("m0", 12_000L, TurnEnd.Finished), rows[1])
        val fold = rows[2] as FoldRow
        assertEquals(2, fold.steps)
        assertEquals(1, fold.tools, "Used 1 tool: the thinking is a step, not a tool")
        assertEquals(12_000L, fold.durationMs)
        assertFalse(rows.any { it is ToolRow })
        assertTrue(rows.any { it is NoticeRow })
        // The answer stays visible, one row per markdown block.
        assertEquals(listOf("m3.0#0", "m3.0#1", "m3.0#2"), rows.filterIsInstance<TextRow>().map { it.key })
    }

    @Test
    fun anUnwatchedTurnShowsNoDurationRatherThanToolTime() {
        // Tool run times leave out the model's time: 5 s of tools in a turn that took far longer.
        val timed = turn.map { if (it is ToolItem) it.copy(durationMs = 5_000) else it }
        val fold = TranscriptModel.rows(timed, liveTurn = false, expanded = emptySet(), durations = emptyMap(), md = MdCache()).first { it is FoldRow } as FoldRow
        assertEquals(null, fold.durationMs)
    }

    @Test
    fun aFailedStepThatALaterStepFixedDoesNotFailTheTurn() {
        val failedThenFixed = listOf(
            UserItem("u", "run the tests and fix them"),
            ToolItem("tool:t1", "t1", "bash", null, status = ToolStatus.Error),
            ToolItem("tool:e", "e", "edit", null, status = ToolStatus.Done),
            ToolItem("tool:t2", "t2", "bash", null, status = ToolStatus.Done),
            AssistantTextItem("a", "Tests pass.", streaming = false),
        )
        val fold = TranscriptModel.rows(failedThenFixed, liveTurn = false, expanded = emptySet(), durations = emptyMap(), md = MdCache()).first { it is FoldRow } as FoldRow
        assertEquals(1, fold.failedSteps)
        assertFalse(fold.endedInFailure)

        val endsFailing = failedThenFixed.map { if (it.key == "tool:t2") (it as ToolItem).copy(status = ToolStatus.Error) else it }
        val failing = TranscriptModel.rows(endsFailing, liveTurn = false, expanded = emptySet(), durations = emptyMap(), md = MdCache()).first { it is FoldRow } as FoldRow
        assertEquals(2, failing.failedSteps)
        // A failing last step is information the answer explains: only an error or abort fails the turn.
        assertFalse(failing.endedInFailure)
        val errored = TranscriptModel.rows(endsFailing + NoticeItem("n", NoticeKind.Error, "Error"), liveTurn = false, expanded = emptySet(), durations = emptyMap(), md = MdCache()).first { it is FoldRow } as FoldRow
        assertTrue(errored.endedInFailure)
    }

    @Test
    fun liveOrOpenedTurnsShowEverything() {
        val live = TranscriptModel.rows(turn, liveTurn = true, expanded = emptySet(), durations = emptyMap(), md = MdCache())
        assertFalse(live.any { it is FoldRow })
        assertTrue(live.all { it.live })
        val opened = TranscriptModel.rows(turn, liveTurn = false, expanded = setOf("m0"), durations = emptyMap(), md = MdCache())
        assertTrue((opened[2] as FoldRow).expanded)
        assertTrue(opened.any { it is ToolRow })
        assertTrue(TranscriptModel.keysUnique(opened))
    }

    @Test
    fun eachFinishedTurnStartsWithAMetaLineAndTheLiveTurnHasNone() {
        fun meta(items: List<ChatItem>) = TranscriptModel.rows(items, liveTurn = false, expanded = emptySet(), durations = emptyMap(), md = MdCache()).filterIsInstance<TurnMetaRow>()
        assertEquals(listOf(TurnMetaRow("m0", null, TurnEnd.Finished)), meta(turn), "no time when this phone did not watch the turn")
        assertEquals(TurnEnd.Stopped, meta(turn + NoticeItem("x", NoticeKind.Aborted, "Stopped")).single().end)
        assertEquals(TurnEnd.Failed, meta(turn + NoticeItem("x", NoticeKind.Error, "Error")).single().end)
        // A turn with nothing after the prompt yet, and the live turn, have none.
        assertTrue(meta(listOf(UserItem("u", "hi"))).isEmpty())
        val live = TranscriptModel.rows(turn, liveTurn = true, expanded = emptySet(), durations = emptyMap(), md = MdCache())
        assertTrue(live.none { it is TurnMetaRow })
        // Two turns: one line each, keys unique.
        val two = TranscriptModel.rows(turn + listOf(UserItem("m4", "again"), AssistantTextItem("m5", "ok", streaming = false)), liveTurn = false, expanded = emptySet(), durations = emptyMap(), md = MdCache())
        assertEquals(listOf("meta:m0", "meta:m4"), two.filterIsInstance<TurnMetaRow>().map { it.key })
        assertTrue(TranscriptModel.keysUnique(two))
    }

    @Test
    fun aReplyThatStartsWithAHeadingReadsAsTheTurnsTitle() {
        fun rows(reply: String, expanded: Boolean = false) = TranscriptModel.rows(
            listOf(
                UserItem("u", "walk me through it"),
                AssistantTextItem("a0", "# Looking around", streaming = false),
                ToolItem("tool:r", "r", "bash", null, status = ToolStatus.Done),
                AssistantTextItem("a1", reply, streaming = false),
            ),
            liveTurn = false,
            expanded = if (expanded) setOf("u") else emptySet(),
            durations = emptyMap(),
            md = MdCache(),
        ).filterIsInstance<TextRow>()
        val titled = rows("## Walkthrough\n\nThe toggle stays put.")
        assertEquals(listOf(true, false), titled.map { it.title })
        assertTrue(rows("The toggle stays put.\n\n## Notes").none { it.title }, "only a reply that starts with a heading")
        // Opened, the text between steps sits on the rail and is never the title; the answer's heading still is.
        val opened = rows("## Walkthrough\n\nDone.", expanded = true)
        assertFalse(opened.first { it.itemKey == "a0" }.title)
        assertTrue(opened.first { it.itemKey == "a1" }.title)
    }

    @Test
    fun onlyTheLastTurnIsLive() {
        val two = turn + listOf(UserItem("m4", "again"), AssistantTextItem("m5.0", "ok", streaming = true))
        val rows = TranscriptModel.rows(two, liveTurn = true, expanded = emptySet(), durations = emptyMap(), md = MdCache())
        assertTrue(rows.any { it is FoldRow })
        assertTrue(rows.last().live)
        assertTrue((rows.last() as TextRow).tail)
        assertFalse(rows.first().live)
    }

    @Test
    fun theActionsOfAReplyWithoutStepsCopyOnlyThatReply() {
        // The last turn has no tool or thinking step: its answer is its own text, not every earlier turn's as well.
        val two = turn + listOf(UserItem("m4", "again"), AssistantTextItem("m5.0", "ok", streaming = false))
        val actions = TranscriptModel.rows(two, liveTurn = false, expanded = emptySet(), durations = emptyMap(), md = MdCache()).last() as ActionsRow
        assertEquals("ok", actions.reply)
    }

    @Test
    fun activityLooksAtTheCurrentTurnOnly() {
        val running = ToolItem("tool:b", "b", "task", null, status = ToolStatus.Running)
        val old = ToolItem("tool:c", "c", "bash", null, status = ToolStatus.Running)
        val a = TranscriptModel.activity(listOf(old, UserItem("u", "x"), running), emptyList())
        assertTrue(a.toolRunning)
        assertTrue(a.subagent)
        val b = TranscriptModel.activity(listOf(old, UserItem("u", "x")), listOf(AssistantTextItem("s", "hi", streaming = true)))
        assertFalse(b.toolRunning)
        assertTrue(b.writing)
    }
}
