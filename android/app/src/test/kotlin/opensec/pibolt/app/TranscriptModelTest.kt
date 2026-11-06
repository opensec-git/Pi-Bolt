package opensec.pibolt.app

import opensec.pibolt.app.ui.session.FoldRow
import opensec.pibolt.app.ui.session.MdCache
import opensec.pibolt.app.ui.session.NoticeRow
import opensec.pibolt.app.ui.session.TextRow
import opensec.pibolt.app.ui.session.ToolRow
import opensec.pibolt.app.ui.session.TranscriptModel
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
        val fold = rows[1] as FoldRow
        assertEquals(2, fold.steps)
        assertEquals(12_000L, fold.durationMs)
        assertFalse(rows.any { it is ToolRow })
        assertTrue(rows.any { it is NoticeRow })
        // The answer stays visible, one row per markdown block.
        assertEquals(listOf("m3.0#0", "m3.0#1", "m3.0#2"), rows.filterIsInstance<TextRow>().map { it.key })
    }

    @Test
    fun foldFallsBackToToolTimeWhenTheTurnWasNotMeasured() {
        val timed = turn.map { if (it is ToolItem) it.copy(durationMs = 5_000) else it }
        val fold = TranscriptModel.rows(timed, liveTurn = false, expanded = emptySet(), durations = emptyMap(), md = MdCache())[1] as FoldRow
        assertEquals(5_000L, fold.durationMs)
        val unknown = TranscriptModel.rows(turn, liveTurn = false, expanded = emptySet(), durations = emptyMap(), md = MdCache())[1] as FoldRow
        assertEquals(null, unknown.durationMs)
    }

    @Test
    fun liveOrOpenedTurnsShowEverything() {
        val live = TranscriptModel.rows(turn, liveTurn = true, expanded = emptySet(), durations = emptyMap(), md = MdCache())
        assertFalse(live.any { it is FoldRow })
        assertTrue(live.all { it.live })
        val opened = TranscriptModel.rows(turn, liveTurn = false, expanded = setOf("m0"), durations = emptyMap(), md = MdCache())
        assertTrue((opened[1] as FoldRow).expanded)
        assertTrue(opened.any { it is ToolRow })
        assertTrue(TranscriptModel.keysUnique(opened))
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
