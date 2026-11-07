package opensec.pibolt.app

import android.view.KeyEvent
import opensec.pibolt.app.ui.markdown.Highlighter
import opensec.pibolt.app.ui.projects.editor.AskPi
import opensec.pibolt.app.ui.projects.editor.EditState
import opensec.pibolt.app.ui.projects.editor.EditorTab
import opensec.pibolt.app.ui.projects.editor.EditorTabs
import opensec.pibolt.app.ui.projects.editor.viewerPlace
import opensec.pibolt.app.ui.projects.editor.EditorCommand
import opensec.pibolt.app.ui.projects.editor.EditorInput
import opensec.pibolt.app.ui.projects.editor.EditorShortcuts
import opensec.pibolt.app.ui.projects.editor.KeyChord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class EditorInputTest {
    /** Types [c] at the cursor of [s] the way a text field would, then applies the editor's rules. */
    private fun type(s: EditState, c: Char): EditState {
        val a = minOf(s.start, s.end)
        val b = maxOf(s.start, s.end)
        val raw = EditState(s.text.substring(0, a) + c + s.text.substring(b), a + 1)
        return EditorInput.transform(s, raw) ?: raw
    }

    private fun backspace(s: EditState): EditState {
        val raw = EditState(s.text.removeRange(s.cursor - 1, s.cursor), s.cursor - 1)
        return EditorInput.transform(s, raw) ?: raw
    }

    @Test
    fun enterKeepsTheIndent() {
        val s = type(EditState("    val a = 1", 13), '\n')
        assertEquals("    val a = 1\n    ", s.text)
        assertEquals(s.text.length, s.cursor)
    }

    @Test
    fun enterAfterAnOpenerIndentsOneStep() {
        val s = type(EditState("fun f() {", 9), '\n')
        assertEquals("fun f() {\n    ", s.text)
        val py = type(EditState("def f():\n  return 1\nif x:", 25), '\n')
        assertEquals("def f():\n  return 1\nif x:\n  ", py.text)
    }

    @Test
    fun enterBetweenBracesSplitsIntoThreeLines() {
        val s = type(EditState("  if (x) {}", 10), '\n')
        assertEquals("  if (x) {\n    \n  }", s.text)
        assertEquals("  if (x) {\n    ".length, s.cursor)
    }

    @Test
    fun indentUnitFollowsTheFile() {
        assertEquals("\t", EditorInput.indentUnit("a\n\tb\n\t\tc\n"))
        assertEquals("  ", EditorInput.indentUnit("a:\n  b:\n    c: 1\n  d: 2\n"))
        assertEquals("    ", EditorInput.indentUnit("class A {\n    fun b() {\n        c()\n    }\n}\n"))
        assertEquals("    ", EditorInput.indentUnit("no indent"))
    }

    @Test
    fun bracketsAndQuotesPair() {
        assertEquals(EditState("f()", 2), type(EditState("f", 1), '('))
        assertEquals(EditState("[]", 1), type(EditState("", 0), '['))
        assertEquals(EditState("x = \"\"", 5), type(EditState("x = ", 4), '"'))
        // Not before a word, and quotes not inside one ("don't").
        assertEquals(EditState("(abc", 1), type(EditState("abc", 0), '('))
        assertEquals(EditState("don'", 4), type(EditState("don", 3), '\''))
    }

    @Test
    fun typingACloserStepsOverIt() {
        val open = type(EditState("f", 1), '(')
        val closed = type(open, ')')
        assertEquals(EditState("f()", 3), closed)
        val q = type(type(EditState("", 0), '"'), '"')
        assertEquals(EditState("\"\"", 2), q)
    }

    @Test
    fun aSelectionIsWrapped() {
        assertEquals(EditState("(abc) d", 1, 4), type(EditState("abc d", 0, 3), '('))
    }

    @Test
    fun backspaceRemovesAnEmptyPair() {
        assertEquals(EditState("f", 1), backspace(EditState("f()", 2)))
        assertEquals(EditState("a", 1), backspace(EditState("ab", 2)))
    }

    @Test
    fun otherChangesAreLeftToTheField() {
        // A paste or an IME replacing a word is not one typed character.
        assertNull(EditorInput.transform(EditState("ab", 2), EditState("ab(c)", 5)))
        assertNull(EditorInput.transform(EditState("teh", 3), EditState("the", 3)))
        assertNull(EditorInput.transform(EditState("a", 1), EditState("ab", 2)))
    }

    @Test
    fun bracketPartnersAreFoundBothWays() {
        val text = "f(a[1], {b})"
        assertEquals(1 to 11, EditorInput.matchingBracket(text, 2))
        assertEquals(1 to 11, EditorInput.matchingBracket(text, 12))
        assertEquals(3 to 5, EditorInput.matchingBracket(text, 3))
        assertEquals(8 to 10, EditorInput.matchingBracket(text, 9))
        assertNull(EditorInput.matchingBracket(text, 0))
        assertNull(EditorInput.matchingBracket("(((", 1))
    }

    @Test
    fun linesAndColumns() {
        val text = "ab\ncde\n\nf"
        assertEquals(1 to 1, EditorInput.lineCol(text, 0))
        assertEquals(2 to 3, EditorInput.lineCol(text, 5))
        assertEquals(4 to 2, EditorInput.lineCol(text, text.length))
        assertEquals(0, EditorInput.lineStart(text, 0))
        assertEquals(3, EditorInput.lineStart(text, 1))
        assertEquals(8, EditorInput.lineStart(text, 3))
        assertEquals(text.length, EditorInput.lineStart(text, 99))
        assertEquals(4, EditorInput.lineCount(text))
    }

    @Test
    fun shortcutsMapToCommands() {
        assertEquals(EditorCommand.Save, EditorShortcuts.command(KeyChord(KeyEvent.KEYCODE_S, ctrl = true)))
        assertEquals(EditorCommand.Find, EditorShortcuts.command(KeyChord(KeyEvent.KEYCODE_F, ctrl = true)))
        assertEquals(EditorCommand.Replace, EditorShortcuts.command(KeyChord(KeyEvent.KEYCODE_H, ctrl = true)))
        assertEquals(EditorCommand.GoToLine, EditorShortcuts.command(KeyChord(KeyEvent.KEYCODE_G, ctrl = true)))
        assertEquals(EditorCommand.Undo, EditorShortcuts.command(KeyChord(KeyEvent.KEYCODE_Z, ctrl = true)))
        assertEquals(EditorCommand.Redo, EditorShortcuts.command(KeyChord(KeyEvent.KEYCODE_Z, ctrl = true, shift = true)))
        assertEquals(EditorCommand.CloseTab, EditorShortcuts.command(KeyChord(KeyEvent.KEYCODE_W, ctrl = true)))
        assertNull(EditorShortcuts.command(KeyChord(KeyEvent.KEYCODE_S)))
        assertEquals("Ctrl+Shift+Z", EditorShortcuts.label(EditorCommand.Redo))
        assertEquals("Ctrl+S", EditorShortcuts.label(EditorCommand.Save))
    }

    // ---- Tabs

    @Test
    fun tabsOpenOnceCloseToANeighbourAndFollowRenames() {
        val tabs = EditorTabs()
        tabs.open("a.kt")
        tabs.open("src/b.kt")
        tabs.open("src/c.kt")
        tabs.open("a.kt")
        assertEquals(3, tabs.tabs.size)
        assertEquals("src/c.kt", tabs.neighbour("src/b.kt", 1)?.rel)
        assertEquals("src/c.kt", tabs.neighbour("a.kt", -1)?.rel)
        assertEquals("src/c.kt", tabs.close("src/b.kt")?.rel)
        tabs.moved("src", "lib")
        assertEquals(listOf("a.kt", "lib/c.kt"), tabs.tabs.map { it.rel })
        tabs.moved("lib/c.kt", null)
        assertNull(tabs.close("a.kt"))
        assertTrue(tabs.tabs.isEmpty())
        assertNull(tabs.close("nothing"))
    }

    @Test
    fun aTabIsDirtyOnlyWhileItsTextDiffersFromTheSavedOne() {
        val tab = EditorTab("x.txt")
        tab.startEditing("hello")
        assertFalse(tab.dirty)
        tab.replace(5, 5, " world")
        assertTrue(tab.dirty)
        assertTrue(tab.undo())
        assertFalse(tab.dirty)
        assertTrue(tab.redo())
        assertEquals("hello world", tab.value.text)
    }

    @Test
    fun toolCardFilesOpenRelativeToTheSessionFolder() {
        val (root, rel) = viewerPlace(File("/data/projects/calc/src/../src/Main.kt"), "/data/projects/calc")
        assertEquals(File("/data/projects/calc"), root)
        assertEquals("src/Main.kt", rel)
        val (other, name) = viewerPlace(File("/tmp/notes.md"), "/data/projects/calc")
        assertEquals(File("/tmp"), other)
        assertEquals("notes.md", name)
    }

    // ---- Ask Pi

    @Test
    fun askAboutAFileIsAMention() {
        assertEquals("@src/Main.kt ", AskPi.prompt("src/Main.kt", "x", 0, 0, "kotlin"))
        assertEquals("@\"my docs/a b.md\" ", AskPi.prompt("my docs/a b.md", "x", 0, 0, null))
    }

    @Test
    fun askAboutASelectionQuotesWholeLines() {
        val text = "line1\nline2\nline3\nline4\n"
        // From the middle of line 2 to just after line 3's newline: lines 2-3.
        val p = AskPi.prompt("a.kt", text, 8, 18, "kotlin")
        assertEquals("@a.kt lines 2-3:\n```kotlin\nline2\nline3\n```\n", p)
        assertEquals("@a.kt line 4:\n```\nline4\n```\n", AskPi.prompt("a.kt", text, 19, 20, null))
    }

    @Test
    fun longSelectionsAreCut() {
        val text = (1..500).joinToString("\n") { "l$it" }
        val p = AskPi.prompt("big.txt", text, 0, text.length, null)
        assertTrue(p.startsWith("@big.txt lines 1-500:"))
        assertTrue(p.contains("l200\n```"))
        assertTrue(p.endsWith("(selection cut; read the file for the rest)\n"))
    }

    @Test
    fun fencesInTheSelectionGetALongerFence() {
        val p = AskPi.prompt("README.md", "```js\nx\n```", 0, 11, "markdown")
        assertTrue(p.contains("````markdown\n```js"))
    }

    @Test
    fun askAppendsOnANewLine() {
        assertEquals("@a " to 3, AskPi.appendTo("", "@a "))
        assertEquals("why?\n@a " to 8, AskPi.appendTo("why?", "@a "))
    }

    // ---- Incremental highlighting

    private val kotlin = """
        package demo

        /* A block
           comment */
        class Parser(val input: String) {
            fun parse(): Int {
                val s = "text ${'$'}{input.length} done"
                return 42 // answer
            }
        }
    """.trimIndent()

    /** A whole pass of the plain tokenizer: what the incremental one must equal after any edits. */
    private fun fresh(text: String, language: String) = Highlighter.tokenize(text, language, limit = Int.MAX_VALUE)

    @Test
    fun incrementalMatchesAFreshPassAfterEdits() {
        val inc = Highlighter.Incremental("kotlin", every = 2)
        var text = kotlin
        assertEquals(fresh(text, "kotlin"), inc.update(text))
        val rnd = Random(7)
        val alphabet = "abc /*\"\n{}()'x1".toCharArray()
        repeat(300) {
            val at = rnd.nextInt(text.length + 1)
            text = if (rnd.nextBoolean() && text.isNotEmpty() && at < text.length) {
                text.removeRange(at, minOf(text.length, at + rnd.nextInt(1, 4)))
            } else {
                text.substring(0, at) + alphabet[rnd.nextInt(alphabet.size)] + text.substring(at)
            }
            assertEquals("after edit $it", fresh(text, "kotlin"), inc.update(text))
        }
    }

    @Test
    fun incrementalReusesTokensAfterALocalEdit() {
        val body = (1..2_000).joinToString("\n") { "val x$it = \"v$it\" // c$it" }
        val inc = Highlighter.Incremental("kotlin")
        inc.update(body)
        val edited = body.replaceFirst("val x1000 =", "val y1000 =")
        val tokens = inc.update(edited)
        assertTrue("re-tokenised ${inc.lastWork} chars", inc.lastWork < 10_000)
        assertEquals(fresh(edited, "kotlin"), tokens)
    }

    @Test
    fun openingABlockCommentRecolorsToTheEnd() {
        val text = (1..300).joinToString("\n") { "val a$it = $it" }
        val inc = Highlighter.Incremental("kotlin")
        inc.update(text)
        val opened = "/*\n$text"
        val tokens = inc.update(opened)
        assertTrue(tokens.all { it.kind == Highlighter.Kind.Comment })
        assertEquals(fresh(opened, "kotlin"), tokens)
    }

    @Test
    fun shiftedTokensFollowTheEdit() {
        val old = "val a = 1"
        val tokens = Highlighter.tokenize(old, "kotlin")
        val next = "val abc = 1"
        val shifted = Highlighter.shiftTokens(tokens, old, next)
        val number = shifted.single { it.kind == Highlighter.Kind.Number }
        assertEquals("1", next.substring(number.start, number.end))
        val keyword = shifted.first()
        assertEquals("val", next.substring(keyword.start, keyword.end))
    }

    @Test
    fun plainLanguagesHaveNoTokens() {
        assertTrue(Highlighter.Incremental("text").update("anything").isEmpty())
    }
}
