package opensec.pibolt.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import opensec.pibolt.app.data.AccentId
import opensec.pibolt.app.data.ThemeId
import opensec.pibolt.app.git.DiffStats
import opensec.pibolt.app.git.LineCount
import opensec.pibolt.app.git.NumStat
import opensec.pibolt.app.ui.git.ChangesRules
import opensec.pibolt.app.ui.projects.DOT_FILE_ALPHA
import opensec.pibolt.app.ui.projects.SessionRowRules
import opensec.pibolt.app.ui.projects.SessionRowRules.Live
import opensec.pibolt.app.ui.theme.PiColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The workspace redesign's rules: session rows, the Changes header, git's line counts and the colours they use. */
class WorkspaceRedesignTest {
    @Test
    fun sessionRowSaysWhatTheOpenSessionDoesAndWhere() {
        assertEquals("Working · main", SessionRowRules.meta(Live.Working, "main", "now", 24))
        assertEquals("Working", SessionRowRules.meta(Live.Working, null, "now", 24), "outside a repository")
        assertEquals("Waiting for you · main", SessionRowRules.meta(Live.Waiting, "main", "2m", 3))
        assertEquals("Open · 24 messages", SessionRowRules.meta(Live.Open, "main", "2m", 24))
        assertEquals("Open", SessionRowRules.meta(Live.Open, "main", "2m", 0))
    }

    @Test
    fun savedSessionRowSaysWhenAndHowLong() {
        assertEquals("2h · 24 messages", SessionRowRules.meta(null, "main", "2h", 24))
        assertEquals("5m · 1 message", SessionRowRules.meta(null, null, "5m", 1))
        assertEquals("3d", SessionRowRules.meta(null, null, "3d", 0), "an empty session has no count")
    }

    @Test
    fun sessionRowReadsInWords() {
        assertEquals("Fix login, working on main, 24 messages, 2 hours ago", SessionRowRules.spoken("Fix login", Live.Working, false, "main", "2 hours ago", 24))
        assertEquals("Fix login, new activity, 1 message, 5 minutes ago", SessionRowRules.spoken("Fix login", null, true, null, "5 minutes ago", 1))
        assertEquals("Fix login, waiting for you, yesterday", SessionRowRules.spoken("Fix login", Live.Waiting, false, "main", "yesterday", 0))
    }

    @Test
    fun branchHeaderTracksTheRemote() {
        assertEquals("origin/main · 2 to push · 1 to pull", ChangesRules.tracking("origin/main", true, 2, 1))
        assertEquals("origin/main", ChangesRules.tracking("origin/main", true, 0, 0))
        assertEquals("Not pushed yet", ChangesRules.tracking(null, true, 0, 0))
        assertEquals("No remote · 3 to push", ChangesRules.tracking(null, false, 3, 0))
    }

    @Test
    fun countsPillReadsInWords() {
        assertEquals("12 lines added, 1 line removed", ChangesRules.countsSpoken(12, 1))
        assertEquals("1 line added", ChangesRules.countsSpoken(1, 0))
        assertEquals("4 lines removed", ChangesRules.countsSpoken(0, 4))
    }

    @Test
    fun numStatParsesPlainRenamedAndBinaryFiles() {
        // `git diff --numstat -z`: a rename has an empty path, then old and new as their own records.
        val raw = "12\t3\tsrc/Main.kt\u0000-\t-\tlogo.png\u00001\t1\t\u0000old/name.kt\u0000new/name.kt\u00000\t4\tpath with\ttab.txt\u0000"
        val parsed = NumStat.parse(raw)
        assertEquals(LineCount(12, 3), parsed["src/Main.kt"])
        assertEquals(LineCount(0, 0), parsed["logo.png"], "binary files count as zero")
        assertEquals(LineCount(1, 1), parsed["new/name.kt"], "a rename counts under its new path")
        assertNull(parsed["old/name.kt"])
        assertEquals(LineCount(0, 4), parsed["path with\ttab.txt"], "only the first two tabs split a record")
        assertTrue(NumStat.parse("").isEmpty())
    }

    @Test
    fun diffStatsSumBothSidesAndHideZeroRows() {
        val stats = DiffStats(staged = mapOf("a.kt" to LineCount(3, 1)), unstaged = mapOf("a.kt" to LineCount(2, 0), "b.png" to LineCount(0, 0)))
        assertEquals(LineCount(5, 1), stats.total)
        assertEquals(LineCount(3, 1), stats.of("a.kt", staged = true))
        assertEquals(LineCount(2, 0), stats.of("a.kt", staged = false))
        assertNull(stats.of("b.png", staged = false), "a binary file shows no counts")
        assertNull(stats.of("c.kt", staged = true))
        assertEquals(LineCount.ZERO, DiffStats.EMPTY.total)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun dimmedDotFilesAndLineCountsStayReadable() {
        val failures = ArrayList<String>()
        for (theme in ThemeId.entries) {
            val c = PiColors.of(theme, AccentId.Theme)
            // A dot file's name: ink1 at DOT_FILE_ALPHA on the canvas the Files list sits on.
            val dot = c.ink1.copy(alpha = DOT_FILE_ALPHA).compositeOver(c.canvas)
            contrast(dot, c.canvas).let { if (it < 4.5) failures += "$theme dot file name: ${"%.2f".format(it)}" }
            // "+N −M" on the canvas (file rows), surface2 (a glass pill's lower half, the open row's highlight).
            for ((ground, bg) in listOf("canvas" to c.canvas, "surface2" to c.surface2)) {
                for ((role, fg) in listOf("success" to c.success, "danger" to c.danger)) {
                    contrast(fg, bg).let { if (it < 4.5) failures += "$theme $role on $ground: ${"%.2f".format(it)}" }
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
