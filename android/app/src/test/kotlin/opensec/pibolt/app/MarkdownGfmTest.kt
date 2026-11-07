package opensec.pibolt.app

import opensec.pibolt.app.ui.markdown.Inline
import opensec.pibolt.app.ui.markdown.InlineSpan
import opensec.pibolt.app.ui.markdown.Markdown
import opensec.pibolt.app.ui.markdown.MdBlock
import opensec.pibolt.app.ui.markdown.TableAlign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** CommonMark + GFM constructs as models write them, block and inline, and the streaming guarantees. */
class MarkdownGfmTest {
    private val fixture = javaClass.getResource("/markdown/fixture.md")!!.readText()

    private fun blocks(md: String) = Markdown.parse(md.trimIndent()).blocks

    private inline fun <reified T : MdBlock> List<MdBlock>.only(): List<T> = filterIsInstance<T>()

    // Lists

    @Test
    fun nestedMixedListsTakeTheirDepthFromIndentation() {
        val items = blocks(
            """
            - a
              - b
                1. c
                2. d
                   - e
              - f
            - g
            """,
        ).only<MdBlock.ListItem>()
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g"), items.map { it.text })
        assertEquals(listOf(0, 1, 2, 2, 3, 1, 0), items.map { it.depth })
        assertEquals(listOf("1.", "2."), items.filter { it.ordered }.map { it.marker })
    }

    @Test
    fun fourSpaceNestingUnderABulletIsOneLevel() {
        val items = blocks("- a\n    - b\n        - c").only<MdBlock.ListItem>()
        assertEquals(listOf(0, 1, 2), items.map { it.depth })
    }

    @Test
    fun orderedListsAreRenumberedFromTheirStart() {
        val items = blocks("1. a\n1. b\n1. c").only<MdBlock.ListItem>()
        assertEquals(listOf("1.", "2.", "3."), items.map { it.marker })
        val seven = blocks("7. a\n8. b\n\ntext\n\n3) c\n9) d").only<MdBlock.ListItem>()
        assertEquals(listOf("7.", "8.", "3)", "4)"), seven.map { it.marker })
    }

    @Test
    fun continuationParagraphsAndCodeStayInsideTheirItem() {
        val doc = blocks(
            """
            1. Install

               Then run it:

               ```bash
               npm test
               ```
            2. Done
            """,
        )
        assertEquals(listOf("ListItem", "Paragraph", "Code", "ListItem"), doc.map { it::class.simpleName })
        assertEquals(1, (doc[1] as MdBlock.Paragraph).indent)
        val code = doc[2] as MdBlock.Code
        assertEquals(1, code.indent)
        assertEquals("npm test", code.code)
        assertEquals("2.", (doc[3] as MdBlock.ListItem).marker)
    }

    @Test
    fun lazyContinuationLinesJoinTheItem() {
        val item = blocks("- first\nsecond line\n    third").single() as MdBlock.ListItem
        assertEquals("first\nsecond line\nthird", item.text)
    }

    @Test
    fun aParagraphEndsTheListAndItsNumbering() {
        val doc = blocks("1. a\n2. b\n\nBetween.\n\n1. c")
        assertEquals("Between.", (doc[2] as MdBlock.Paragraph).text)
        assertEquals(0, (doc[2] as MdBlock.Paragraph).indent)
        assertEquals("1.", (doc[3] as MdBlock.ListItem).marker)
    }

    @Test
    fun orderedItemsInterruptAParagraphOnlyFromOne() {
        assertEquals(1, blocks("In 2024.\n2025. was next").size)
        assertEquals(2, blocks("Steps:\n1. go").size)
    }

    @Test
    fun taskItems() {
        val items = blocks("- [x] done\n- [ ] open\n  - [X] nested\n- [ ]").only<MdBlock.ListItem>()
        assertEquals(listOf(true, false, true, false), items.map { it.checked })
        assertEquals(listOf("done", "open", "nested", ""), items.map { it.text })
        assertEquals(1, items[2].depth)
    }

    // Quotes and headings

    @Test
    fun quotesKeepTheirInnerMarkdownForNestedRendering() {
        val quote = blocks("> outer **bold**\n>\n> > inner\n> - item").single() as MdBlock.Quote
        val inner = Markdown.parse(quote.text).blocks
        assertEquals(listOf("Paragraph", "Quote", "ListItem"), inner.map { it::class.simpleName })
        assertEquals("inner", (inner[1] as MdBlock.Quote).text)
    }

    @Test
    fun headingsAtEveryLevelAndSetext() {
        val doc = blocks("# 1\n## 2\n### 3\n#### 4\n##### 5\n###### 6\n####### 7\n#hashtag\n\nTitle\n=====\n\nSub\n---")
        val headings = doc.only<MdBlock.Heading>()
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 1, 2), headings.map { it.level })
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "Title", "Sub"), headings.map { it.text })
        assertEquals(listOf("####### 7\n#hashtag"), doc.only<MdBlock.Paragraph>().map { it.text })
        assertEquals("Closed", (blocks("## Closed ##").single() as MdBlock.Heading).text)
    }

    @Test
    fun aRuleAfterABlankLineIsNotASetextHeading() {
        assertEquals(listOf("Paragraph", "Rule"), blocks("Text\n\n---").map { it::class.simpleName })
    }

    // Tables

    @Test
    fun tablesWithAlignmentEscapedPipesAndCode() {
        val t = blocks(
            """
            | Left | Center | Right | Code |
            |:-----|:------:|------:|------|
            | a | b | 1.5 | `x | y` |
            | c \| d | e | 2 | `z` |
            """,
        ).single() as MdBlock.Table
        assertEquals(listOf(TableAlign.Start, TableAlign.Center, TableAlign.End, TableAlign.None), t.align)
        assertEquals(listOf("a", "b", "1.5", "`x | y`"), t.rows[0])
        assertEquals("c | d", t.rows[1][0])
    }

    @Test
    fun tablesWithoutOuterPipesAndAfterAParagraph() {
        val doc = blocks("Here is a table:\na | b\n--- | ---\n1 | 2")
        assertEquals("Here is a table:", (doc[0] as MdBlock.Paragraph).text)
        val t = doc[1] as MdBlock.Table
        assertEquals(listOf("a", "b"), t.header)
        assertEquals(listOf(listOf("1", "2")), t.rows)
    }

    @Test
    fun aSeparatorWithTheWrongNumberOfCellsIsNotATable() {
        assertTrue(blocks("| a | b | c |\n|---|---|").none { it is MdBlock.Table })
    }

    // Footnotes, math, HTML, indented code

    @Test
    fun footnoteDefinitions() {
        val doc = blocks("Text[^1].\n\n[^1]: The note\n  continues.\n[^x]: Second")
        val notes = doc.only<MdBlock.Footnote>()
        assertEquals(listOf("1", "x"), notes.map { it.label })
        assertEquals("The note\ncontinues.", notes[0].text)
    }

    @Test
    fun displayMathForms() {
        val doc = blocks(
            """
            $$
            a^2 + b^2
            $$

            $$\sum_i i$$

            \[
            x = 1
            \]

            \begin{align}
            a &= b \\
            c &= d
            \end{align}

            ```math
            e^{i\pi}
            ```
            """,
        )
        val math = doc.only<MdBlock.Math>()
        assertEquals(listOf("a^2 + b^2", "\\sum_i i", "x = 1", "\\begin{align}\na &= b \\\\\nc &= d\n\\end{align}", "e^{i\\pi}"), math.map { it.tex })
        assertTrue(math.all { it.closed })
    }

    @Test
    fun streamingMathIsOpenAndProseWithAnEscapedBracketIsNotMath() {
        val open = blocks("$$\n\\frac{1}{2").single() as MdBlock.Math
        assertFalse(open.closed)
        assertIs<MdBlock.Paragraph>(blocks("\\[not a link\\] in prose").single())
    }

    @Test
    fun htmlWrappersAreDroppedAndCommentsHidden() {
        val doc = blocks("<details>\n<summary>More</summary>\n\nInside\n\n</details>\n<!-- hidden\nstill -->\nAfter")
        assertEquals(listOf("**More**", "Inside", "After"), doc.map { (it as MdBlock.Paragraph).text })
    }

    @Test
    fun indentedCodeOutsideLists() {
        val code = blocks("Intro\n\n    let a = 1\n\n    let b = 2\n\nAfter")[1] as MdBlock.Code
        assertEquals("let a = 1\n\nlet b = 2", code.code)
    }

    // Streaming

    @Test
    fun incrementalParseMatchesFullParseOnTheFixture() {
        var previous = Markdown.parse("")
        var n = 0
        while (n < fixture.length) {
            n = minOf(fixture.length, n + 3)
            val text = fixture.substring(0, n)
            val incremental = Markdown.parse(text, previous)
            assertEquals(Markdown.parse(text).blocks, incremental.blocks, "at length $n")
            previous = incremental
        }
    }

    @Test
    fun finishedBlocksAreReusedWhileStreaming() {
        val first = Markdown.parse(fixture.substring(0, fixture.length / 2))
        val second = Markdown.parse(fixture, first)
        for (i in 0 until first.blocks.size - 1) assertSame(first.blocks[i], second.blocks[i])
    }

    @Test
    fun aLargeMessageParsesQuickly() {
        val big = buildString { while (length < 100_000) append(fixture).append("\n\n") }
        Markdown.parse(big)
        val started = System.nanoTime()
        val doc = Markdown.parse(big)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(doc.blocks.size > 1000)
        assertTrue(ms < 1500, "100 KB took $ms ms")
        // Appending a chunk re-parses only the tail.
        val grown = big + "more text"
        val t2 = System.nanoTime()
        Markdown.parse(grown, doc)
        assertTrue((System.nanoTime() - t2) / 1_000_000 < 50)
    }

    @Test
    fun hostileBlockInputStaysLinear() {
        for (unit in listOf("- ", "> ", "| a ", "1. ", "    ", "\$\$\n", "```\n", "<!--\n", "[^a]: ")) {
            val text = (unit + "x\n").repeat(100_000 / (unit.length + 2))
            val started = System.nanoTime()
            Markdown.parse(text)
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue(ms < 2000, "'$unit' took $ms ms")
        }
        val deep = (0 until 2000).joinToString("\n") { " ".repeat(it * 2) + "- x" }
        assertTrue(Markdown.parse(deep).blocks.size == 2000)
    }

    @Test
    fun theFixtureParsesIntoEveryBlockKind() {
        val kinds = Markdown.parse(fixture).blocks.map { it::class.simpleName }.toSet()
        assertEquals(setOf("Heading", "Paragraph", "Code", "ListItem", "Quote", "Rule", "Table", "Math", "Footnote"), kinds)
    }

    // Inline

    private fun kinds(s: String) = Inline.parse(s).spans.map { it.kind }

    @Test
    fun everyAsciiPunctuationCanBeEscaped() {
        val punct = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
        val escaped = punct.map { "\\$it" }.joinToString("")
        assertEquals(punct, Inline.parse(escaped).text)
        assertEquals("\\a", Inline.parse("\\a").text)
        // An escaped bracket in prose is not display math.
        val p = Inline.parse("\\[no link\\] and \\(x\\)")
        assertEquals("[no link] and x", p.text)
        assertEquals(listOf(InlineSpan.Kind.Math), p.spans.map { it.kind })
    }

    @Test
    fun backslashHardBreak() {
        assertEquals("one\ntwo", Inline.parse("one\\\ntwo").text)
    }

    @Test
    fun htmlEntities() {
        assertEquals("© — Tom & Jerry <tag> © \uD83D\uDE80 \u00A0", Inline.parse("&copy; &mdash; Tom &amp; Jerry &lt;tag&gt; &#169; &#x1F680; &nbsp;").text)
        assertEquals("&unknown; & &#xZZ;", Inline.parse("&unknown; & &#xZZ;").text)
        assertEquals("`&amp;`".removeSurrounding("`"), Inline.parse("`&amp;`").text)
    }

    @Test
    fun autolinks() {
        val p = Inline.parse("see <https://pi.dev/x>, https://a.b/c(d)). and www.ex.org! or <me@ex.com>")
        assertEquals("see https://pi.dev/x, https://a.b/c(d)). and www.ex.org! or me@ex.com", p.text)
        assertEquals(listOf("https://pi.dev/x", "https://a.b/c(d)", "https://www.ex.org", "mailto:me@ex.com"), p.spans.map { it.url })
        assertTrue(kinds("http://").isEmpty())
        assertTrue(kinds("a.www.b").isEmpty())
    }

    @Test
    fun linksWithTitlesAndParenthesesInTheirAddress() {
        val p = Inline.parse("[wiki](https://en.wikipedia.org/wiki/Foo_(bar) \"Title\") and [x](<a b>)")
        assertEquals("wiki and x", p.text)
        assertEquals(listOf("https://en.wikipedia.org/wiki/Foo_(bar)", "a b"), p.spans.map { it.url })
    }

    @Test
    fun linkTextHoldsNoAutolinks() {
        val p = Inline.parse("[https://a.b](https://a.b)")
        assertEquals(listOf(InlineSpan.Kind.Link), p.spans.map { it.kind })
    }

    @Test
    fun imagesKeepAltTextAndAddress() {
        val p = Inline.parse("![Pi *logo*](https://pi.dev/logo.png \"t\")")
        assertEquals("Pi logo", p.text)
        val image = p.spans.single()
        assertEquals(InlineSpan.Kind.Image, image.kind)
        assertEquals("https://pi.dev/logo.png", image.url)
    }

    @Test
    fun footnoteReferences() {
        val p = Inline.parse("a[^1] b[^note] c[^ x]")
        assertEquals("a1 bnote c[^ x]", p.text)
        assertEquals(listOf(InlineSpan.Kind.FootnoteRef, InlineSpan.Kind.FootnoteRef), p.spans.map { it.kind })
    }

    @Test
    fun inlineMathAndCurrency() {
        val p = Inline.parse("\$E = mc^2\$ and \\(a^2\\) and \$\$x\$\$ costs \$5 and \$10, or \$5. Then \$y\$.")
        val math = p.spans.filter { it.kind == InlineSpan.Kind.Math }.map { p.text.substring(it.start, it.end) }
        assertEquals(listOf("E = mc^2", "a^2", "x", "y"), math)
        assertTrue(p.text.contains("costs \$5 and \$10, or \$5. Then"))
        assertTrue(kinds("\$ 5 \$").isEmpty())
        assertTrue(kinds("\\\$5 and \\\$6").isEmpty())
    }

    @Test
    fun boldItalicTogether() {
        val p = Inline.parse("***both*** and **b** *i*")
        assertEquals("both and b i", p.text)
        assertEquals(setOf(InlineSpan.Kind.Bold, InlineSpan.Kind.Italic), p.spans.filter { it.start == 0 }.map { it.kind }.toSet())
    }

    @Test
    fun inlineHtmlTags() {
        val p = Inline.parse("a<br>b H<sub>2</sub>O x<sup>2</sup> <kbd>Ctrl</kbd> <b>bold</b> <span>kept</span> <!-- gone --><custom>")
        assertEquals("a\nb H2O x2 Ctrl bold kept <custom>", p.text)
        assertEquals(
            listOf(InlineSpan.Kind.Subscript, InlineSpan.Kind.Superscript, InlineSpan.Kind.Code, InlineSpan.Kind.Bold),
            p.spans.map { it.kind },
        )
    }

    @Test
    fun codeSpansNeedAMatchingRun() {
        val p = Inline.parse("``a ` b`` and `x``")
        assertEquals("a ` b and `x``", p.text)
        assertEquals(listOf(InlineSpan.Kind.Code), p.spans.map { it.kind })
    }

    @Test
    fun emojiAndRtlPassThrough() {
        val s = "🚀 ✨ 👩‍💻 🇮🇳 مرحبا עברית"
        assertEquals(s, Inline.parse(s).text)
        assertNull(Inline.parse(s).spans.firstOrNull())
    }

    @Test
    fun inlineMathOpenersSharingOneCloserStayLinear() {
        // PR #254 review: every `\(` used to walk to the shared closer.
        assertTrue(Inline.parse("\\(".repeat(20000) + "\\)").spans.none { it.kind == InlineSpan.Kind.Math })
        assertLinear("\\(", 20_000) { size -> Inline.parse("\\(".repeat(size) + "\\)") }
        assertEquals(listOf(InlineSpan(2, 5, InlineSpan.Kind.Math)), Inline.parse("a \\(x+1\\) b").spans)
    }

    @Test
    fun hostileNewInlineConstructsStayLinear() {
        val unit = 100_000
        for (opener in listOf("\$a ", "\\(a ", "<a ", "![a](", "[a](", "&a ", "http://a ", "[^a ", "<b>a ", "\$\$a ", "<!--a ", "``a ")) {
            val text = opener.repeat(unit / opener.length)
            val started = System.nanoTime()
            val parsed = Inline.parse(text)
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue(parsed.text.isNotEmpty(), opener)
            assertTrue(ms < 1500, "$opener took $ms ms")
        }
    }

    @Test
    fun aListOfAnotherKindRightAfterAListStartsANewList() {
        // Bullets, then numbered steps, then tasks, as a model writes them in one reply.
        val items = blocks(
            """
            - a
              - a1
                - a2
            1. b
               1. b1
            2. c
            - [x] d
            - [ ] e
            """,
        ).only<MdBlock.ListItem>()
        assertEquals(listOf("a", "a1", "a2", "b", "b1", "c", "d", "e"), items.map { it.text })
        // a starts the bullets, b the numbered list, d the task list. Nested items, a return from nesting (c) and a
        // second task do not.
        assertEquals(listOf(true, false, false, true, false, false, true, false), items.map { it.startsList })
        // An item that goes on after a paragraph inside its list is no new list.
        assertEquals(listOf(true, false), blocks("1. a\n\n   more\n2. b").only<MdBlock.ListItem>().map { it.startsList })
    }
}
