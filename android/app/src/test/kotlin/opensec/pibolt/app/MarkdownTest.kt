package opensec.pibolt.app

import opensec.pibolt.app.ui.markdown.Highlighter
import opensec.pibolt.app.ui.markdown.Inline
import opensec.pibolt.app.ui.markdown.InlineSpan
import opensec.pibolt.app.ui.markdown.Markdown
import opensec.pibolt.app.ui.markdown.MdBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MarkdownTest {
    private val sample = """
        # Plan

        I will **fix** the `parser` and run [tests](https://example.com).

        - first item
        - second item
          continues here
          - nested
        1. one
        2. two

        > quoted
        > text

        ```kotlin
        fun main() {
            println("hi") // greet
        }
        ```

        | a | b |
        |---|---|
        | 1 | 2 |

        ---
        Done.
    """.trimIndent()

    @Test
    fun parsesBlocks() {
        val doc = Markdown.parse(sample)
        val kinds = doc.blocks.map { it::class.simpleName }
        assertEquals(
            listOf("Heading", "Paragraph", "ListItem", "ListItem", "ListItem", "ListItem", "ListItem", "Quote", "Code", "Table", "Rule", "Paragraph"),
            kinds,
        )
        val code = doc.blocks[8] as MdBlock.Code
        assertEquals("kotlin", code.language)
        assertTrue(code.closed)
        assertEquals("second item\ncontinues here", (doc.blocks[3] as MdBlock.ListItem).text)
        assertEquals(1, (doc.blocks[4] as MdBlock.ListItem).depth)
        assertTrue((doc.blocks[5] as MdBlock.ListItem).ordered)
        assertEquals(listOf(listOf("1", "2")), (doc.blocks[9] as MdBlock.Table).rows)
    }

    @Test
    fun incrementalParseMatchesFullParseAtEveryLength() {
        var previous = Markdown.parse("")
        for (n in 1..sample.length) {
            val text = sample.substring(0, n)
            val incremental = Markdown.parse(text, previous)
            assertEquals(Markdown.parse(text).blocks, incremental.blocks, "at length $n")
            previous = incremental
        }
    }

    @Test
    fun incrementalParseReusesFinishedBlocks() {
        val first = Markdown.parse("# Title\n\nSome text")
        val second = Markdown.parse("# Title\n\nSome text that grows", first)
        assertSame(first.blocks[0], second.blocks[0])
        assertSame(first, Markdown.parse("# Title\n\nSome text", first))
    }

    @Test
    fun openFenceWhileStreaming() {
        val code = Markdown.parse("```py\nprint(1)").blocks.single()
        assertIs<MdBlock.Code>(code)
        assertFalse(code.closed)
        assertEquals("print(1)", code.code)
    }

    @Test
    fun inlineSpans() {
        val parsed = Inline.parse("a **b** *c* `d` ~~e~~ [f](https://x.y) snake_case_name")
        assertEquals("a b c d e f snake_case_name", parsed.text)
        assertEquals(
            listOf(InlineSpan.Kind.Bold, InlineSpan.Kind.Italic, InlineSpan.Kind.Code, InlineSpan.Kind.Strike, InlineSpan.Kind.Link),
            parsed.spans.map { it.kind },
        )
        assertEquals("https://x.y", parsed.spans.last().url)
        assertEquals("2 * 3 = 6", Inline.parse("2 * 3 = 6").text)
    }

    @Test
    fun highlighterFindsTokens() {
        val code = "val x = \"hi\" // note\nfun f(1)"
        val tokens = Highlighter.tokenize(code, "kotlin")
        fun kindAt(s: String) = tokens.first { code.substring(it.start, it.end) == s }.kind
        assertEquals(Highlighter.Kind.Keyword, kindAt("val"))
        assertEquals(Highlighter.Kind.String, kindAt("\"hi\""))
        assertEquals(Highlighter.Kind.Comment, kindAt("// note"))
        assertEquals(Highlighter.Kind.Function, kindAt("f"))
        assertEquals(Highlighter.Kind.Number, kindAt("1"))
        val shell = Highlighter.tokenize("ls # list", "bash")
        assertEquals(listOf(Highlighter.Kind.Function, Highlighter.Kind.Comment), shell.map { it.kind })
    }

    @Test
    fun hostileInlineTextParsesInLinearTime() {
        // PR #243 review: openers that never close made each one scan to the end (quadratic).
        val unit = 100_000
        for (opener in listOf("*a ", "_a ", "**a ", "__a ", "~~a ", "[a ", "`a ", "[a](b ")) {
            val text = opener.repeat(unit / opener.length)
            val started = System.nanoTime()
            val parsed = Inline.parse(text)
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue(parsed.text.isNotEmpty(), opener)
            assertTrue(ms < 1500, "$opener took $ms ms")
        }
    }

    @Test
    fun emphasisStillMatchesItsFirstCloser() {
        val parsed = Inline.parse("*a* b *c **d** e* f_g_h [x](u) [y] (z)")
        assertEquals("a b c d e f_g_h x [y] (z)", parsed.text)
        assertEquals(
            listOf(InlineSpan.Kind.Italic, InlineSpan.Kind.Bold, InlineSpan.Kind.Italic, InlineSpan.Kind.Link),
            parsed.spans.map { it.kind },
        )
    }

}
