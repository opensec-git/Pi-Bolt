package opensec.pibolt.app

import opensec.pibolt.app.ui.markdown.CodeFold
import opensec.pibolt.app.ui.markdown.CodeInfo
import opensec.pibolt.app.ui.markdown.Highlighter
import opensec.pibolt.app.ui.markdown.Inline
import opensec.pibolt.app.ui.markdown.InlineDisplay
import opensec.pibolt.app.ui.markdown.InlineSpan
import opensec.pibolt.app.ui.markdown.Markdown
import opensec.pibolt.app.ui.markdown.MdBlock
import opensec.pibolt.app.ui.markdown.TableAlign
import opensec.pibolt.app.ui.markdown.TableMath
import opensec.pibolt.app.ui.markdown.UnifiedDiff
import opensec.pibolt.app.ui.session.DiffKind
import opensec.pibolt.app.ui.session.DiffLine
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlockMathTest {
    // Fence info

    @Test
    fun fenceTagAndInfo() {
        val code = Markdown.parse("```ts title=\"src/auth.ts\" {3}\nlet a = 1\n```").blocks.single() as MdBlock.Code
        assertEquals("ts", code.language)
        assertEquals("title=\"src/auth.ts\" {3}", code.info)
        val info = CodeInfo.of(code.language, code.info)
        assertEquals("ts", info.language)
        assertEquals("src/auth.ts", info.filename)
        assertEquals("src/auth.ts", info.label)
        assertEquals("src/auth.ts", info.viewerTitle)
    }

    @Test
    fun fenceFilenameForms() {
        CodeInfo.of("ts:src/a.ts").let {
            assertEquals("ts", it.language)
            assertEquals("src/a.ts", it.filename)
        }
        CodeInfo.of("src/Main.kt").let {
            assertEquals("kotlin", it.language)
            assertEquals("src/Main.kt", it.filename)
        }
        CodeInfo.of("", "app/build.gradle.kts").let {
            assertEquals("kotlin", it.language)
            assertEquals("app/build.gradle.kts", it.filename)
        }
        CodeInfo.of("py", "{1,3-5}").let {
            assertNull(it.filename)
            assertEquals("py", it.label)
        }
        CodeInfo.of("Dockerfile").let { assertEquals("dockerfile", it.language) }
    }

    @Test
    fun fenceLabels() {
        assertEquals("shell", CodeInfo.of("bash").label)
        assertEquals("shell", CodeInfo.of("console").label)
        assertEquals("kotlin", CodeInfo.of("Kotlin").label)
        assertEquals("code", CodeInfo.of("").label)
        assertEquals("", CodeInfo.of("").viewerTitle)
        assertEquals("shell", CodeInfo.of("zsh").viewerTitle)
        assertTrue(CodeInfo.of("diff").diff)
        assertTrue(CodeInfo.of("patch").diff)
        assertEquals("diff", CodeInfo.of("diff").viewerTitle)
    }

    @Test
    fun viewerLanguageFromTitle() {
        assertEquals("", CodeInfo.viewerLanguage("Code"))
        assertEquals("kotlin", CodeInfo.viewerLanguage("kotlin"))
        assertEquals("shell", CodeInfo.viewerLanguage("shell"))
        assertEquals("ts", CodeInfo.viewerLanguage("src/auth.ts"))
        assertEquals("diff", CodeInfo.viewerLanguage("diff"))
        assertNull(CodeInfo.viewerLanguage("Output"))
        assertNull(CodeInfo.viewerLanguage("Markdown"))
        assertNull(CodeInfo.viewerLanguage("Read"))
        assertNull(CodeInfo.viewerLanguage("JetBrains Mono"))
        assertNull(CodeInfo.viewerLanguage("notes.md"))
    }

    // Folding

    @Test
    fun foldModes() {
        assertEquals(CodeFold.Mode.Full, CodeFold.mode(20, closed = true, expanded = false))
        assertEquals(CodeFold.Mode.Full, CodeFold.mode(20, closed = false, expanded = false))
        assertEquals(CodeFold.Mode.Head, CodeFold.mode(21, closed = true, expanded = false))
        assertEquals(CodeFold.Mode.Tail, CodeFold.mode(21, closed = false, expanded = false))
        assertEquals(CodeFold.Mode.Tail, CodeFold.mode(500, closed = false, expanded = true))
        assertEquals(CodeFold.Mode.Full, CodeFold.mode(150, closed = true, expanded = true))
        assertEquals(CodeFold.Mode.Head, CodeFold.mode(151, closed = true, expanded = true))
    }

    @Test
    fun foldFooter() {
        assertNull(CodeFold.footer(20, closed = true, expanded = false))
        assertEquals(CodeFold.Footer("Show all 84 lines", CodeFold.Action.Expand), CodeFold.footer(84, closed = true, expanded = false))
        assertEquals(CodeFold.Footer("Show less", CodeFold.Action.Collapse), CodeFold.footer(84, closed = true, expanded = true))
        assertEquals(CodeFold.Footer("Show all 84 lines", CodeFold.Action.Open), CodeFold.footer(84, closed = false, expanded = false))
        assertEquals(CodeFold.Footer("Open all 1,240 lines", CodeFold.Action.Open), CodeFold.footer(1240, closed = true, expanded = false))
    }

    @Test
    fun headAndTail() {
        val text = (1..30).joinToString("\n") { "line $it" }
        assertEquals((1..14).joinToString("\n") { "line $it" }, CodeFold.head(text, 14))
        assertEquals("a\nb", CodeFold.head("a\nb", 14))
        assertEquals("a\n", CodeFold.head("a\n\nb", 2))
        assertEquals((17..30).joinToString("\n") { "line $it" }, text.substring(CodeFold.tailStart(text, 14)))
        assertEquals(0, CodeFold.tailStart("a\nb", 14))
        // A streaming block ending in a newline: its empty last line is one of the 14.
        assertEquals("b\nc\n", "a\nb\nc\n".substring(CodeFold.tailStart("a\nb\nc\n", 3)))
        assertEquals(31, CodeFold.lineCount("$text\n"))
    }

    // Diffs

    private val unified = """
        diff --git a/src/a.ts b/src/a.ts
        index 1234..5678 100644
        --- a/src/a.ts
        +++ b/src/a.ts
        @@ -10,4 +10,4 @@ export function f() {
         const a = 1
        -const b = 2
        +const b = 3
         return a + b
         }
    """.trimIndent()

    @Test
    fun unifiedDiffRows() {
        val rows = UnifiedDiff.parse(unified)
        assertEquals(
            listOf(DiffKind.Hunk, DiffKind.Hunk, DiffKind.Hunk, DiffKind.Hunk, DiffKind.Hunk, DiffKind.Context, DiffKind.Del, DiffKind.Add, DiffKind.Context, DiffKind.Context),
            rows.map { it.kind },
        )
        assertEquals(listOf("10", "11", "11", "12", "13"), rows.drop(5).map { it.number })
        assertEquals("const b = 2", rows[6].text)
        assertEquals("src/a.ts", UnifiedDiff.path(unified))
        assertTrue(UnifiedDiff.looksLikeDiff(unified))
    }

    @Test
    fun deletedSqlCommentInsideHunkIsADeletion() {
        val rows = UnifiedDiff.parse("@@ -1,2 +1,1 @@\n--- old comment\n keep")
        assertEquals(listOf(DiffKind.Hunk, DiffKind.Del, DiffKind.Context), rows.map { it.kind })
        assertEquals("-- old comment", rows[1].text)
    }

    @Test
    fun plainDiffWithoutHunksHasNoNumbers() {
        val rows = UnifiedDiff.parse("+added\n-removed\n kept")
        assertEquals(listOf(DiffKind.Add, DiffKind.Del, DiffKind.Context), rows.map { it.kind })
        assertTrue(rows.all { it.number.isEmpty() })
        assertFalse(UnifiedDiff.looksLikeDiff("+added\n-removed\n kept"))
        assertFalse(UnifiedDiff.looksLikeDiff("val a = 1\nval b = 2\n@@ not really"))
        assertEquals("gone.txt", UnifiedDiff.path("--- a/gone.txt\n+++ /dev/null\n"))
    }

    @Test
    fun wordRangesPairDeletesWithAdds() {
        val lines = listOf(
            DiffLine(DiffKind.Del, "1", "const b = 2"),
            DiffLine(DiffKind.Add, "1", "const b = 3"),
            DiffLine(DiffKind.Context, "2", "x"),
            DiffLine(DiffKind.Del, "3", "abc"),
            DiffLine(DiffKind.Add, "3", "xyz"),
        )
        val ranges = UnifiedDiff.wordRanges(lines)
        assertEquals(10..10, ranges[0])
        assertEquals(10..10, ranges[1])
        assertNull(ranges[2])
        // Nothing in common: no word marks (the whole line is the change).
        assertNull(ranges[3])
        assertNull(ranges[4])
    }

    // Tables

    @Test
    fun tableAlignmentsAreKept() {
        val table = Markdown.parse("| a | b | c | d |\n|:--|:--:|--:|---|\n| 1 | 2 | 3 | 4 |").blocks.single() as MdBlock.Table
        assertEquals(listOf(TableAlign.Start, TableAlign.Center, TableAlign.End, TableAlign.None), table.align)
    }

    @Test
    fun tableWidthsFillTheCard() {
        // Natural widths fit: spare width is spread in proportion and the sum is exactly the card width.
        val w = TableMath.widths(intArrayOf(100, 300), available = 1000, min = 64, max = 240)
        assertEquals(1000, w.sum())
        assertEquals(294, w[0])
        assertEquals(706, w[1])
        // Too wide: natural (clamped) widths, the table scrolls.
        assertContentEquals(intArrayOf(64, 240, 240, 240, 240), TableMath.widths(intArrayOf(10, 900, 240, 240, 240), available = 900, min = 64, max = 240))
    }

    @Test
    fun tableCellKinds() {
        assertTrue(TableMath.isNumeric(listOf("276 B", "1.2 kB", "", "45%", "-3", "120ms")))
        assertFalse(TableMath.isNumeric(listOf("12", "twelve")))
        assertFalse(TableMath.isNumeric(listOf("", " ")))
        assertEquals(TableMath.Glyph.Yes, TableMath.glyph("✓"))
        assertEquals(TableMath.Glyph.Yes, TableMath.glyph("Yes"))
        assertEquals(TableMath.Glyph.No, TableMath.glyph("✗"))
        assertEquals(TableMath.Glyph.Dash, TableMath.glyph("—"))
        assertNull(TableMath.glyph("maybe"))
    }

    // Inline code display

    @Test
    fun inlineCodeGetsPadsInsideItsChip() {
        val d = InlineDisplay.build(Inline.parse("run `npm test` now"))
        val pad = InlineDisplay.PAD
        assertEquals("run ${pad}npm test$pad now", d.text)
        val code = d.spans.single { it.kind == InlineSpan.Kind.Code }
        assertEquals("npm test", d.text.substring(code.start, code.end))
        val chip = d.chips.single()
        assertEquals("${pad}npm test$pad", d.text.substring(chip.first, chip.last + 1))
    }

    @Test
    fun otherSpansFollowTheInsertedPads() {
        val d = InlineDisplay.build(Inline.parse("**a `b` c** [link](https://x.y)"))
        val bold = d.spans.single { it.kind == InlineSpan.Kind.Bold }
        val link = d.spans.single { it.kind == InlineSpan.Kind.Link }
        assertEquals("a ${InlineDisplay.PAD}b${InlineDisplay.PAD} c", d.text.substring(bold.start, bold.end))
        assertEquals("link", d.text.substring(link.start, link.end))
    }

    @Test
    fun longUnbrokenCodeGetsBreakOpportunities() {
        val d = InlineDisplay.build(Inline.parse("see `packages/coding-agent/src/config.ts`"))
        val code = d.spans.single { it.kind == InlineSpan.Kind.Code }
        val shown = d.text.substring(code.start, code.end)
        assertEquals("packages/coding-agent/src/config.ts", shown.replace(InlineDisplay.BREAK.toString(), ""))
        assertEquals(5, shown.count { it == InlineDisplay.BREAK })
        assertFalse(shown.endsWith(InlineDisplay.BREAK))
        // Short spans and spans with spaces are left alone.
        assertFalse(InlineDisplay.build(Inline.parse("`a/b.c`")).text.contains(InlineDisplay.BREAK))
    }

    @Test
    fun textWithoutCodeIsUnchanged() {
        val parsed = Inline.parse("plain **bold**")
        val d = InlineDisplay.build(parsed)
        assertEquals(parsed.text, d.text)
        assertEquals(parsed.spans, d.spans)
    }

    // Highlighter

    @Test
    fun highlighterTypesAndPunctuation() {
        val code = "val client: HttpClient = HttpClient(MAX_SIZE)\nlet s: string = f(1);"
        val tokens = Highlighter.tokenize(code, "kotlin")
        fun kinds(s: String) = tokens.filter { code.substring(it.start, it.end) == s }.map { it.kind }
        assertEquals(listOf(Highlighter.Kind.Type, Highlighter.Kind.Type), kinds("HttpClient"))
        assertEquals(listOf(Highlighter.Kind.Type), kinds("string"))
        assertEquals(listOf(Highlighter.Kind.Constant), kinds("MAX_SIZE"))
        assertEquals(listOf(Highlighter.Kind.Function), kinds("f"))
        assertTrue(kinds(":").all { it == Highlighter.Kind.Punctuation } && kinds(":").size == 2)
        assertEquals(listOf(Highlighter.Kind.Punctuation), kinds(");"))
    }

    @Test
    fun punctuationStopsBeforeComments() {
        val code = "a =// note"
        val tokens = Highlighter.tokenize(code, "js")
        assertEquals(listOf("=", "// note"), tokens.map { code.substring(it.start, it.end) })
        assertEquals(listOf(Highlighter.Kind.Operator, Highlighter.Kind.Comment), tokens.map { it.kind })
    }

    @Test
    fun shellPromptAndJsonKeys() {
        val sh = "$ npm test\necho \$HOME"
        val tokens = Highlighter.tokenize(sh, "console")
        assertEquals(Highlighter.Kind.Prompt, tokens.first().kind)
        assertEquals(1, tokens.count { it.kind == Highlighter.Kind.Prompt })
        val json = "{\"name\": \"pi\", \"n\": 2}"
        val strings = Highlighter.tokenize(json, "json").filter { it.kind == Highlighter.Kind.String }.map { json.substring(it.start, it.end) }
        assertEquals(listOf("\"pi\""), strings)
    }

    @Test
    fun plainLanguagesAreNotHighlighted() {
        assertTrue(Highlighter.tokenize("if this is true, return", "text").isEmpty())
        assertTrue(Highlighter.tokenize("# Title", "log").isEmpty())
        assertEquals(listOf(Highlighter.Kind.Heading), Highlighter.tokenize("# Title", "markdown").map { it.kind })
        assertFalse(Highlighter.tokenize("if (x) return", "").isEmpty())
    }

    @Test
    fun hunkNumbersOutOfRangeDoNotThrow() {
        // PR #243 review: digits past Int made toInt() throw while rendering model output.
        val rows = UnifiedDiff.parse("@@ -99999999999999999999,2 +1,2 @@\n-old\n+new\n keep")
        assertEquals(listOf(DiffKind.Hunk, DiffKind.Del, DiffKind.Add, DiffKind.Context), rows.map { it.kind })
        assertTrue(rows.all { it.number.isEmpty() })
        val huge = UnifiedDiff.parse("@@ -1,2147483647 +1,999999999 @@\n keep")
        assertEquals("", huge[1].number)
    }

}
