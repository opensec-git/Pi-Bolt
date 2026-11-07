package opensec.pibolt.app

import opensec.pibolt.app.ui.projects.editor.EditHistory
import opensec.pibolt.app.ui.projects.editor.EditKind
import opensec.pibolt.app.ui.projects.editor.FindOptions
import opensec.pibolt.app.ui.projects.editor.FindReplace
import opensec.pibolt.app.ui.projects.editor.Interruptible
import opensec.pibolt.app.ui.projects.editor.TextEdit
import opensec.pibolt.app.ui.projects.editor.TextMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorFindReplaceTest {
    private fun spans(text: String, options: FindOptions) = FindReplace.find(text, options).matches.map { text.substring(it.start, it.end) }

    @Test
    fun plainQueriesAreLiteralAndCaseInsensitiveByDefault() {
        val text = "a.b A.B axb"
        assertEquals(listOf("a.b", "A.B"), spans(text, FindOptions("a.b")))
        assertEquals(listOf("a.b"), spans(text, FindOptions("a.b", matchCase = true)))
        // As a regex the dot matches any character.
        assertEquals(listOf("a.b", "A.B", "axb"), spans(text, FindOptions("a.b", regex = true)))
    }

    @Test
    fun wholeWordNeedsNonWordCharactersAround() {
        val text = "val value = valid(val_x, val)"
        assertEquals(listOf(0, 25), FindReplace.find(text, FindOptions("val", wholeWord = true)).matches.map { it.start })
        assertEquals(5, FindReplace.find(text, FindOptions("val")).matches.size)
        // Whole word with a regex alternation applies to the whole alternation.
        assertEquals(listOf("val", "value", "val"), spans(text, FindOptions("val|value", regex = true, wholeWord = true)))
    }

    @Test
    fun invalidRegexReportsAnErrorAndNoMatches() {
        val r = FindReplace.find("abc", FindOptions("a(b", regex = true))
        assertTrue(r.matches.isEmpty())
        assertNotNull(r.error)
    }

    @Test
    fun emptyMatchesAreSkipped() {
        assertEquals(listOf("aa", "a"), spans("aa b a", FindOptions("a*", regex = true)))
        assertTrue(FindReplace.find("abc", FindOptions("^", regex = true)).matches.isEmpty())
    }

    @Test
    fun matchesAreCapped() {
        val r = FindReplace.find("x".repeat(50), FindOptions("x"), cap = 10)
        assertEquals(10, r.matches.size)
        assertTrue(r.capped)
        assertEquals("1 of 10+", FindReplace.counter(0, 10, true))
        assertEquals("No matches", FindReplace.counter(0, 0, false))
    }

    @Test
    fun runawayRegexStopsAtTheDeadline() {
        // A back-reference keeps java.util.regex from optimising the nested loop: about 16 s without the deadline.
        val text = "a".repeat(26) + "!"
        val started = System.nanoTime()
        val r = FindReplace.find(text, FindOptions("(a+)+\\1b", regex = true), deadlineMs = 100)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals("The pattern took too long", r.error)
        assertTrue("took $ms ms", ms < 5_000)
    }

    @Test
    fun interruptibleReadsThroughUntilTheDeadline() {
        val live = Interruptible("hello", System.nanoTime() + 60_000_000_000)
        assertEquals("ell", live.subSequence(1, 4).toString())
        assertEquals('o', live[4])
    }

    @Test
    fun replaceOneExpandsRegexGroups() {
        val text = "let a = 1; let bb = 22;"
        val options = FindOptions("let (\\w+) = (\\d+)", regex = true)
        val m = FindReplace.find(text, options).matches
        assertEquals(2, m.size)
        val r = FindReplace.replacementFor(text, options, m[1], "const $1: number = $2")!!
        assertEquals("const bb: number = 22", r.text)
        assertEquals(m[1].start, r.start)
        assertEquals(m[1].end, r.end)
    }

    @Test
    fun replacementSupportsNamedGroupsWholeMatchAndEscapes() {
        val text = "2026-11-07"
        val options = FindOptions("(?<y>\\d{4})-(?<m>\\d{2})-(?<d>\\d{2})", regex = true)
        val m = FindReplace.find(text, options).matches.single()
        assertEquals("07/11/2026 \$0=2026-11-07 \$", FindReplace.replacementFor(text, options, m, "\${d}/\${m}/\${y} \\\$0=$0 \\$")!!.text)
    }

    @Test
    fun plainReplacementIsLiteral() {
        val text = "price"
        val options = FindOptions("price")
        val m = FindReplace.find(text, options).matches.single()
        assertEquals("\$1 \\n", FindReplace.replacementFor(text, options, m, "\$1 \\n")!!.text)
    }

    @Test
    fun replacementForAStaleMatchIsNull() {
        val options = FindOptions("foo")
        assertNull(FindReplace.replacementFor("bar foo", options, TextMatch(0, 3), "x"))
        // Whole-word context is checked with the text around the match.
        assertNull(FindReplace.replacementFor("xfoo", FindOptions("foo", wholeWord = true), TextMatch(1, 4), "y"))
    }

    @Test
    fun replacementErrorsNameMissingGroups() {
        assertEquals("There is no group 2", FindReplace.replacementError(FindOptions("(a)", regex = true), "$2"))
        assertEquals("There is no group named x", FindReplace.replacementError(FindOptions("(?<y>a)", regex = true), "\${x}"))
        assertNull(FindReplace.replacementError(FindOptions("(a)", regex = true), "$1 \\$2"))
        assertNull(FindReplace.replacementError(FindOptions("(a)"), "$9"))
    }

    @Test
    fun replaceAllIsOneSpanCoveringFirstToLastMatch() {
        val text = "head foo1 mid foo22 tail"
        val (r, count) = FindReplace.replaceAll(text, FindOptions("foo(\\d+)", regex = true), "bar<$1>")!!
        assertEquals(2, count)
        val result = text.substring(0, r.start) + r.text + text.substring(r.end)
        assertEquals("head bar<1> mid bar<22> tail", result)
        assertEquals(5, r.start)
        assertEquals(19, r.end)
    }

    @Test
    fun replaceAllWithoutMatchesChangesNothing() {
        val (r, count) = FindReplace.replaceAll("abc", FindOptions("z"), "y")!!
        assertEquals(0, count)
        assertEquals(r.start, r.end)
        assertNull(FindReplace.replaceAll("abc", FindOptions("(", regex = true), "y"))
    }

    @Test
    fun replaceAllThenUndoRestoresTheTextInOneStep() {
        val text = "one two one two one"
        val history = EditHistory()
        val (r, count) = FindReplace.replaceAll(text, FindOptions("one"), "1")!!
        assertEquals(3, count)
        val replaced = text.substring(0, r.start) + r.text + text.substring(r.end)
        history.push(TextEdit(r.start, text.substring(r.start, r.end), r.text, 0..0, 0..0, kind = EditKind.Other))
        assertEquals("1 two 1 two 1", replaced)
        val (undone, _) = history.undo(replaced)!!
        assertEquals(text, undone)
        val (redone, _) = history.redo(undone)!!
        assertEquals(replaced, redone)
    }

    @Test
    fun nearestMatchWrapsAround() {
        val m = listOf(TextMatch(2, 3), TextMatch(10, 12), TextMatch(20, 21))
        assertEquals(0, FindReplace.nearest(m, 0))
        assertEquals(1, FindReplace.nearest(m, 3))
        assertEquals(2, FindReplace.nearest(m, 20))
        assertEquals(0, FindReplace.nearest(m, 25))
        assertEquals(-1, FindReplace.nearest(emptyList(), 5))
    }

    // ---- Undo history

    private fun type(history: EditHistory, start: String, chars: String, at0: Long = 0, step: Long = 100): String {
        var text = start
        var t = at0
        for (c in chars) {
            val next = text + c
            history.record(text, next, text.length..text.length, next.length..next.length, t)
            text = next
            t += step
        }
        return text
    }

    @Test
    fun typingCoalescesIntoOneStepUntilAPauseOrNewline() {
        val h = EditHistory()
        var text = type(h, "", "hello")
        assertEquals(1, h.size)
        text = type(h, text, " world", at0 = 5_000)
        assertEquals(2, h.size)
        text = type(h, text, "\nnext", at0 = 5_700)
        assertEquals("hello world\nnext", text)
        assertEquals(4, h.size) // "\n" is a step of its own, "next" another.
        text = h.undo(text)!!.first
        assertEquals("hello world\n", text)
        text = h.undo(text)!!.first
        assertEquals("hello world", text)
        text = h.undo(text)!!.first
        assertEquals("hello", text)
        text = h.undo(text)!!.first
        assertEquals("", text)
        assertNull(h.undo(text))
        text = h.redo(text)!!.first
        assertEquals("hello", text)
    }

    @Test
    fun backspaceJoinsTheTypingStep() {
        val h = EditHistory()
        var text = type(h, "x", "abc")
        h.record(text, "xab", 4..4, 3..3, 400)
        text = "xab"
        assertEquals(1, h.size)
        assertEquals("x", h.undo(text)!!.first)
    }

    @Test
    fun pasteIsItsOwnStepAndNewEditsClearRedo() {
        val h = EditHistory()
        var text = type(h, "", "ab")
        h.record(text, "abPASTE", 2..2, 7..7, 150, EditKind.Other)
        text = "abPASTE"
        assertEquals(2, h.size)
        text = h.undo(text)!!.first
        assertTrue(h.canRedo)
        h.record(text, text + "z", 2..2, 3..3, 10_000)
        assertFalse(h.canRedo)
    }

    @Test
    fun undoRestoresTheSelection() {
        val h = EditHistory()
        h.record("hello world", "hello there", 6..11, 11..11, 0, EditKind.Other)
        val (text, sel) = h.undo("hello there")!!
        assertEquals("hello world", text)
        assertEquals(6..11, sel)
    }

    @Test
    fun diffKeepsOnlyTheChangedMiddle() {
        val e = EditHistory.diff("abcdef", "abXYef", 0..0, 0..0)!!
        assertEquals(2, e.start)
        assertEquals("cd", e.removed)
        assertEquals("XY", e.inserted)
        assertNull(EditHistory.diff("same", "same", 0..0, 0..0))
    }

    @Test
    fun historyIsBounded() {
        val h = EditHistory(limit = 3)
        var text = ""
        for (i in 0 until 5) {
            val next = "$text$i\n"
            h.record(text, next, 0..0, 0..0, i * 10_000L, EditKind.Other)
            text = next
        }
        assertEquals(3, h.size)
    }
}
