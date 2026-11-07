package opensec.pibolt.app

import opensec.pibolt.app.checkpoints.TurnRecord
import opensec.pibolt.app.checkpoints.UndoEntry
import opensec.pibolt.app.ui.session.review.SessionChanges
import opensec.pibolt.app.ui.session.voice.VoiceLevel
import opensec.pibolt.app.ui.session.voice.VoiceText
import opensec.pibolt.protocol.session.UserItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The session screen's redesign: the changes pill's totals, the voice surface's level and text. */
class SessionRedesignTest {
    private fun record(id: String, at: Long?, files: Int, added: Int, removed: Int, finished: Boolean = true, undos: List<UndoEntry> = emptyList(), prompt: String = "p$id") =
        TurnRecord(id, "s", at, prompt, startedAt = 0, start = "a$id", end = if (finished) "b$id" else null, files = files, added = added, removed = removed, undos = undos)

    @Test
    fun changesAddUpTheTurnsOfThisSessionThatChangedFiles() {
        val records = listOf(
            record("1", 100, files = 2, added = 10, removed = 1),
            record("2", 200, files = 0, added = 0, removed = 0),
            record("3", 300, files = 1, added = 2, removed = 2),
            // Another session's turn: no user message of this transcript points at it.
            record("4", 999, files = 5, added = 50, removed = 5),
        )
        val users = listOf(UserItem("u1", "one", timestamp = 100), UserItem("u2", "two", timestamp = 200), UserItem("u3", "three", timestamp = 300))
        val changes = SessionChanges.of(records, users)!!
        assertEquals(listOf("1", "3"), changes.turns.map { it.id }, "oldest first, only turns that changed files")
        assertEquals(12, changes.added)
        assertEquals(3, changes.removed)
        assertEquals(3, changes.files)
    }

    @Test
    fun undoneRunningAndUnchangedTurnsAreLeftOut() {
        val undone = record("1", 100, files = 2, added = 10, removed = 1, undos = listOf(UndoEntry(1, "x", "y", listOf("a.ts"))))
        val redone = record("2", 200, files = 1, added = 3, removed = 0, undos = listOf(UndoEntry(1, "x", "y", listOf("b.ts"), redone = true)))
        val running = record("3", 300, files = 4, added = 4, removed = 4, finished = false)
        val users = listOf(UserItem("u1", "one", timestamp = 100), UserItem("u2", "two", timestamp = 200), UserItem("u3", "three", timestamp = 300))
        val changes = SessionChanges.of(listOf(undone, redone, running), users)!!
        assertEquals(listOf("2"), changes.turns.map { it.id }, "an undo that was redone counts again")
        assertNull(SessionChanges.of(listOf(undone, running), users), "nothing to show")
        assertNull(SessionChanges.of(emptyList(), users))
    }

    @Test
    fun aMessageWithoutATimestampMatchesItsTurnByPrompt() {
        val records = listOf(record("1", null, files = 1, added = 1, removed = 0, prompt = "fix it"))
        assertEquals(1, SessionChanges.of(records, listOf(UserItem("u", "fix it")))?.turns?.size)
        assertNull(SessionChanges.of(records, listOf(UserItem("u", "something else"))))
        // The same turn reached twice (a repeated prompt) counts once.
        assertEquals(1, SessionChanges.of(records, listOf(UserItem("u", "fix it"), UserItem("v", "fix it")))?.turns?.size)
    }

    @Test
    fun reviewRowsNameFilesAndLines() {
        assertEquals("1 file · +3 −0", SessionChanges.meta(record("1", 1, files = 1, added = 3, removed = 0)))
        assertEquals("4 files · +40 −12", SessionChanges.meta(record("2", 2, files = 4, added = 40, removed = 12)))
    }

    @Test
    fun micLevelMapsTheRecognisersDecibelsOntoTheWave() {
        assertEquals(0f, VoiceLevel.normalize(-10f), "silence")
        assertEquals(0f, VoiceLevel.normalize(VoiceLevel.FLOOR_DB))
        assertEquals(1f, VoiceLevel.normalize(VoiceLevel.CEILING_DB))
        assertEquals(1f, VoiceLevel.normalize(30f), "never past full scale")
        assertEquals(0.5f, VoiceLevel.normalize(4f), 0.001f)
        assertEquals(0f, VoiceLevel.normalize(Float.NaN))
        var last = -1f
        for (db in -2..10) {
            val v = VoiceLevel.normalize(db.toFloat())
            assertTrue(v >= last, "louder never reads quieter")
            last = v
        }
    }

    @Test
    fun voiceSurfaceShowsTheEndOfALongDictationFromAWordStart() {
        assertEquals("Fix the failing tests", VoiceText.tail("  Fix the failing tests "))
        val long = (1..80).joinToString(" ") { "word$it" }
        val shown = VoiceText.tail(long)
        assertTrue(shown.startsWith("…word"), shown)
        assertTrue(shown.endsWith("word80"))
        assertTrue(shown.length <= VoiceText.MAX + 1)
    }
}
