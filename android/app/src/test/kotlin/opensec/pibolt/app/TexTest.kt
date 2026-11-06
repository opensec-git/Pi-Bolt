package opensec.pibolt.app

import opensec.pibolt.app.ui.markdown.Markdown
import opensec.pibolt.app.ui.markdown.MdBlock
import opensec.pibolt.app.ui.markdown.math.GlyphMetrics
import opensec.pibolt.app.ui.markdown.math.MathFont
import opensec.pibolt.app.ui.markdown.math.MathLayout
import opensec.pibolt.app.ui.markdown.math.MathMetrics
import opensec.pibolt.app.ui.markdown.math.MathOp
import opensec.pibolt.app.ui.markdown.math.Tex
import opensec.pibolt.app.ui.markdown.math.TexNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TexTest {
    /** Every glyph is 0.5 em wide, 0.7 em above and 0.2 em below the baseline. */
    private val fixed = object : MathMetrics {
        override fun measure(text: String, font: MathFont, size: Float) = GlyphMetrics(text.length * size * 0.5f, size * 0.7f, size * 0.2f)
    }

    private fun layout(tex: String, display: Boolean = true) = MathLayout(fixed, 20f).layout(Tex.parse(tex), display)

    @Test
    fun everyFormulaInTheFixtureIsSupported() {
        val fixture = javaClass.getResource("/markdown/fixture.md")!!.readText()
        val blocks = Markdown.parse(fixture).blocks.filterIsInstance<MdBlock.Math>()
        assertEquals(4, blocks.size)
        for (b in blocks) assertNotNull(Tex.parseOrNull(b.tex), b.tex)
        for (inline in listOf("E = mc^2", "a^2 + b^2 = c^2")) assertNotNull(Tex.parseOrNull(inline))
    }

    @Test
    fun commonConstructsParse() {
        val ok = listOf(
            "\\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}", "\\sum_{i=1}^{n} i", "\\int_0^\\infty e^{-x^2}\\,dx", "\\lim_{x \\to 0} \\frac{\\sin x}{x}",
            "\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}", "\\begin{cases} 1 & x > 0 \\\\ 0 & \\text{otherwise} \\end{cases}",
            "\\begin{aligned} a &= b \\\\ &= c \\end{aligned}", "\\left( \\frac{a}{b} \\right)^2", "\\sqrt[3]{x}", "\\hat{y} + \\vec{v} + \\bar{x}",
            "\\mathbb{R}^n \\to \\mathcal{O}(\\log n)", "\\alpha\\beta\\Gamma\\Omega", "x' + f''(x)", "\\binom{n}{k}", "\\overbrace{a+b}^{2}",
            "\\operatorname{argmax}_x f(x)", "\\{x \\mid x > 0\\}", "\\|v\\|_2", "a \\leq b \\neq c \\approx d", "\\text{if } x \\in A",
            "\\begin{array}{cc} 1 & 2 \\end{array}", "\\boxed{x = 1}", "\\underset{n}{\\max}", "\\big( x \\big)", "{a \\over b}",
            "\\displaystyle\\sum_i x_i", "\\mathbf{v}\\cdot\\mathbf{w}", "\\not= \\not\\in", "\\begin{bmatrix}1\\\\2\\end{bmatrix}",
        )
        for (t in ok) assertNotNull(Tex.parseOrNull(t), t)
    }

    @Test
    fun unsupportedOrBrokenTexIsRefused() {
        for (t in listOf("\\unknowncommand{x}", "\\frac{a}", "{a", "a}", "\\left( x", "\\begin{tikzpicture}\\end{tikzpicture}", "\\begin{pmatrix} a \\end{bmatrix}", "a & b")) {
            assertNull(Tex.parseOrNull(t), t)
        }
    }

    @Test
    fun scriptsAttachToTheirBase() {
        val node = Tex.parse("x_i^2") as TexNode.Row
        val s = node.items.single()
        assertIs<TexNode.Scripts>(s)
        assertNotNull(s.sub)
        assertNotNull(s.sup)
    }

    @Test
    fun aFractionIsTallerThanItsParts() {
        val plain = layout("a")
        val frac = layout("\\frac{a}{b}")
        assertTrue(frac.ascent > plain.ascent && frac.descent > plain.descent)
        assertTrue(frac.ops.any { it is MathOp.Rule })
        // Inline fractions use smaller parts than display ones.
        assertTrue(layout("\\frac{a}{b}", display = false).height < frac.height)
    }

    @Test
    fun displayLimitsGoAboveAndBelow() {
        val sum = layout("\\sum_{i=1}^{n} i")
        val inline = layout("\\sum_{i=1}^{n} i", display = false)
        assertTrue(sum.height > inline.height)
        assertTrue(sum.width < inline.width + 40f)
    }

    @Test
    fun matricesStackRows() {
        val one = layout("\\begin{pmatrix} a \\end{pmatrix}")
        val two = layout("\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}")
        assertTrue(two.height > one.height * 1.5f)
        assertTrue(two.width > one.width)
    }

    @Test
    fun allOpsAreFinite() {
        val box = layout("\\left[ \\sqrt[3]{\\frac{\\alpha_1^2}{\\beta}} \\right\\} + \\begin{cases} a & b \\end{cases} + \\vec{x} + \\overbrace{ab}")
        for (op in box.ops) {
            when (op) {
                is MathOp.Glyph -> assertTrue(op.x.isFinite() && op.y.isFinite())
                is MathOp.Rule -> assertTrue(op.x.isFinite() && op.y.isFinite() && op.width >= 0f)
                is MathOp.Stroke -> assertTrue(op.points.all { it.isFinite() })
            }
        }
        assertTrue(box.width > 0f && box.ascent > 0f)
    }

    @Test
    fun deepOrLongInputIsRefusedQuickly() {
        assertNull(Tex.parseOrNull("{".repeat(5_000) + "}".repeat(5_000)))
        assertNull(Tex.parseOrNull("x^".repeat(3_000) + "x"))
        assertNotNull(Tex.parseOrNull("x+".repeat(500) + "x"))
    }
}
