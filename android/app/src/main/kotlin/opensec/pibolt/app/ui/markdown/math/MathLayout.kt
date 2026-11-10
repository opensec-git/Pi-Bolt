package opensec.pibolt.app.ui.markdown.math

/** Ink metrics of a glyph run, in pixels: advance [width], [ascent] above and [descent] below the baseline. */
data class GlyphMetrics(val width: Float, val ascent: Float, val descent: Float)

/** Measures text for the layout; the app implements it with android.graphics.Paint, tests with fixed numbers. */
interface MathMetrics {
    fun measure(text: String, font: MathFont, size: Float): GlyphMetrics

    /** Like [measure] but not clamped at the baseline: an accent's ink lies wholly above it (negative descent). */
    fun ink(text: String, font: MathFont, size: Float): GlyphMetrics = measure(text, font, size)

    fun hasGlyph(text: String, font: MathFont): Boolean = true
}

/** What a laid-out formula draws, in pixels relative to its origin (x = 0 at the left, y = 0 on the baseline). */
sealed interface MathOp {
    data class Glyph(val text: String, val x: Float, val y: Float, val size: Float, val font: MathFont) : MathOp

    data class Rule(val x: Float, val y: Float, val width: Float, val height: Float) : MathOp

    /** A stroked path: [points] are x, y pairs; [curve] draws it as quadratic segments through every other point. */
    data class Stroke(val points: FloatArray, val width: Float, val curve: Boolean = false) : MathOp {
        override fun equals(other: Any?): Boolean = other is Stroke && points.contentEquals(other.points) && width == other.width && curve == other.curve

        override fun hashCode(): Int = points.contentHashCode() * 31 + width.hashCode()
    }
}

class MathBox(val width: Float, val ascent: Float, val descent: Float, val ops: List<MathOp>, val atom: Atom = Atom.Ord) {
    val height: Float get() = ascent + descent

    fun shifted(dx: Float, dy: Float): List<MathOp> = if (dx == 0f && dy == 0f) ops else ops.map { op ->
        when (op) {
            is MathOp.Glyph -> op.copy(x = op.x + dx, y = op.y + dy)
            is MathOp.Rule -> op.copy(x = op.x + dx, y = op.y + dy)
            is MathOp.Stroke -> MathOp.Stroke(FloatArray(op.points.size) { if (it % 2 == 0) op.points[it] + dx else op.points[it] + dy }, op.width, op.curve)
        }
    }

    companion object {
        val Empty = MathBox(0f, 0f, 0f, emptyList())
    }
}

/**
 * Lays out a [TexNode] tree in TeX's spirit: atom spacing by class, script sizes 70% / 50%, fractions on the math
 * axis, big operators with limits in display style, delimiters that grow with their content. [size] is the base font
 * size in pixels.
 */
class MathLayout(private val metrics: MathMetrics, private val size: Float) {
    fun layout(node: TexNode, display: Boolean): MathBox = box(node, if (display) Style.Display else Style.Text)

    private fun sizeOf(style: Style) = when (style) {
        Style.Display, Style.Text -> size
        Style.Script -> size * 0.7f
        Style.ScriptScript -> size * 0.5f
    }

    private fun Style.script() = when (this) {
        Style.Display, Style.Text -> Style.Script
        else -> Style.ScriptScript
    }

    private fun Style.fraction() = when (this) {
        Style.Display -> Style.Text
        Style.Text -> Style.Script
        else -> Style.ScriptScript
    }

    private fun axis(s: Float) = s * 0.25f
    private fun rule(s: Float) = maxOf(1f, s * 0.05f)

    private fun box(node: TexNode, style: Style): MathBox {
        val s = sizeOf(style)
        return when (node) {
            is TexNode.Sym -> sym(node, style, s)
            is TexNode.Text -> glyph(node.text, node.font, s, Atom.Ord)
            is TexNode.Row -> row(node.items, style).let { if (node.atom != Atom.Ord) MathBox(it.width, it.ascent, it.descent, it.ops, node.atom) else it }
            is TexNode.Space -> MathBox(node.em * s, 0f, 0f, emptyList())
            is TexNode.Scripts -> scripts(node, style, s)
            is TexNode.Frac -> frac(node, style, s)
            is TexNode.Sqrt -> sqrt(node, style, s)
            is TexNode.Fenced -> fenced(node.left, box(node.body, style), node.right, s, node.size)
            is TexNode.Delim -> {
                val h = if (node.size > 0f) s * node.size else s * 1.1f
                delimiter(node.text, h / 2f + axis(s), h / 2f - axis(s), s).let { MathBox(it.width, it.ascent, it.descent, it.ops, node.atom) }
            }
            is TexNode.Accent -> accent(node, style, s)
            is TexNode.OverUnder -> overUnder(node, style, s)
            is TexNode.Matrix -> matrix(node, style, s)
            is TexNode.Boxed -> {
                val b = box(node.body, style)
                val pad = s * 0.2f
                val t = rule(s)
                val w = b.width + 2 * pad
                val top = -(b.ascent + pad)
                val h = b.height + 2 * pad
                val ops = b.shifted(pad, 0f) + listOf(
                    MathOp.Rule(0f, top, w, t), MathOp.Rule(0f, top + h - t, w, t), MathOp.Rule(0f, top, t, h), MathOp.Rule(w - t, top, t, h),
                )
                MathBox(w, b.ascent + pad, b.descent + pad, ops)
            }
            is TexNode.Styled -> box(node.body, node.style)
        }
    }

    private fun glyph(text: String, font: MathFont, s: Float, atom: Atom): MathBox {
        if (text.isEmpty()) return MathBox(0f, 0f, 0f, emptyList(), atom)
        val m = metrics.measure(text, font, s)
        return MathBox(m.width, m.ascent, m.descent, listOf(MathOp.Glyph(text, 0f, 0f, s, font)), atom)
    }

    private fun sym(node: TexNode.Sym, style: Style, s: Float): MathBox {
        val (text, font) = styled(node.text, node.font)
        if (node.large) {
            val integral = !node.limits
            val scale = if (style == Style.Display) (if (integral) 1.7f else 1.45f) else if (style == Style.Text) 1.12f else 1f
            val gs = s * scale
            val m = metrics.measure(text, font, gs)
            // Centre the operator on the math axis.
            val dy = (m.ascent - m.descent) / 2f - axis(s)
            val italic = if (integral && style == Style.Display) s * 0.08f else 0f
            return MathBox(m.width + italic, m.ascent - dy, m.descent + dy, listOf(MathOp.Glyph(text, 0f, dy, gs, font)), Atom.Op)
        }
        val b = glyph(text, font, s, node.atom)
        // Italic letters lean past their advance: a little room so a following upright glyph does not touch.
        return if (font == MathFont.Italic && text.length == 1 && text[0].isLetter()) MathBox(b.width + s * 0.03f, b.ascent, b.descent, b.ops, b.atom) else b
    }

    /** Maps letters to the Unicode math alphabets (double-struck, script, fraktur) when the font has them. */
    private fun styled(text: String, font: MathFont): Pair<String, MathFont> {
        if (font != MathFont.DoubleStruck && font != MathFont.Script && font != MathFont.Fraktur) return text to font
        val out = StringBuilder()
        for (c in text) {
            val mapped = mathAlphabet(c, font)
            out.append(mapped ?: c.toString())
        }
        val s = out.toString()
        return if (s != text && metrics.hasGlyph(s, MathFont.Roman)) s to MathFont.Roman else text to (if (font == MathFont.DoubleStruck) MathFont.Bold else MathFont.Italic)
    }

    private fun mathAlphabet(c: Char, font: MathFont): String? {
        val upper = c in 'A'..'Z'
        val lower = c in 'a'..'z'
        val digit = c in '0'..'9'
        val bmp: Map<Char, Char> = when (font) {
            MathFont.DoubleStruck -> mapOf('C' to 'ℂ', 'H' to 'ℍ', 'N' to 'ℕ', 'P' to 'ℙ', 'Q' to 'ℚ', 'R' to 'ℝ', 'Z' to 'ℤ')
            MathFont.Script -> mapOf('B' to 'ℬ', 'E' to 'ℰ', 'F' to 'ℱ', 'H' to 'ℋ', 'I' to 'ℐ', 'L' to 'ℒ', 'M' to 'ℳ', 'R' to 'ℛ', 'e' to 'ℯ', 'g' to 'ℊ', 'o' to 'ℴ')
            else -> mapOf('C' to 'ℭ', 'H' to 'ℌ', 'I' to 'ℑ', 'R' to 'ℜ', 'Z' to 'ℨ')
        }
        bmp[c]?.let { return it.toString() }
        val base = when (font) {
            MathFont.DoubleStruck -> if (upper) 0x1D538 else if (lower) 0x1D552 else if (digit) 0x1D7D8 else return null
            MathFont.Script -> if (upper) 0x1D49C else if (lower) 0x1D4B6 else return null
            else -> if (upper) 0x1D504 else if (lower) 0x1D51E else return null
        }
        val offset = if (upper) c - 'A' else if (lower) c - 'a' else c - '0'
        return String(Character.toChars(base + offset))
    }

    private fun atomOf(node: TexNode): Atom = when (node) {
        is TexNode.Sym -> node.atom
        is TexNode.Row -> node.atom
        is TexNode.Scripts -> atomOf(node.base)
        is TexNode.Frac, is TexNode.Fenced, is TexNode.Matrix -> Atom.Inner
        is TexNode.Delim -> node.atom
        is TexNode.OverUnder -> atomOf(node.base)
        is TexNode.Accent -> atomOf(node.body).let { if (it == Atom.Op) Atom.Op else Atom.Ord }
        is TexNode.Space -> Atom.Ord
        else -> Atom.Ord
    }

    /** TeX's inter-atom spacing in mu (1/18 em); negative entries apply only in display and text styles. */
    private val spacing = arrayOf(
        //          Ord Op  Bin Rel Open Close Punct Inner
        intArrayOf(0, 3, -4, -5, 0, 0, 0, -3), // Ord
        intArrayOf(3, 3, 0, -5, 0, 0, 0, -3), // Op
        intArrayOf(-4, -4, 0, 0, -4, 0, 0, -4), // Bin
        intArrayOf(-5, -5, 0, 0, -5, 0, 0, -5), // Rel
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0), // Open
        intArrayOf(0, 3, -4, -5, 0, 0, 0, -3), // Close
        intArrayOf(-3, -3, 0, -3, -3, -3, -3, -3), // Punct
        intArrayOf(-3, 3, -4, -5, -3, 0, -3, -3), // Inner
    )

    private fun row(items: List<TexNode>, style: Style): MathBox {
        if (items.isEmpty()) return MathBox.Empty
        val s = sizeOf(style)
        val script = style == Style.Script || style == Style.ScriptScript
        // Binary operators become ordinary where they cannot be binary (a leading minus).
        val atoms = items.map { atomOf(it) }.toMutableList()
        for (k in atoms.indices) {
            if (items[k] is TexNode.Space) continue
            if (atoms[k] == Atom.Bin) {
                val prev = (k - 1 downTo 0).firstOrNull { items[it] !is TexNode.Space }?.let { atoms[it] }
                if (prev == null || prev in setOf(Atom.Bin, Atom.Op, Atom.Rel, Atom.Open, Atom.Punct)) atoms[k] = Atom.Ord
            }
        }
        for (k in atoms.indices.reversed()) {
            if (atoms[k] == Atom.Bin) {
                val next = (k + 1 until atoms.size).firstOrNull { items[it] !is TexNode.Space }?.let { atoms[it] }
                if (next == null || next in setOf(Atom.Rel, Atom.Close, Atom.Punct)) atoms[k] = Atom.Ord
            }
        }
        val ops = ArrayList<MathOp>()
        var x = 0f
        var asc = 0f
        var desc = 0f
        var prevAtom: Atom? = null
        for ((k, item) in items.withIndex()) {
            val b = box(item, style)
            if (item !is TexNode.Space) {
                val a = atoms[k]
                if (prevAtom != null) {
                    val mu = spacing[prevAtom.ordinal][a.ordinal]
                    if (mu > 0 || mu < 0 && !script) x += kotlin.math.abs(mu) / 18f * s
                }
                prevAtom = a
            }
            ops += b.shifted(x, 0f)
            x += b.width
            asc = maxOf(asc, b.ascent)
            desc = maxOf(desc, b.descent)
        }
        return MathBox(x, asc, desc, ops)
    }

    private fun isLimitsOp(node: TexNode): Boolean = node is TexNode.Sym && node.limits && node.atom == Atom.Op ||
        node is TexNode.Row && node.atom == Atom.Op && node.items.size == 1 && isLimitsOp(node.items[0])

    private fun scripts(node: TexNode.Scripts, style: Style, s: Float): MathBox {
        val base = box(node.base, style)
        val ss = style.script()
        val sub = node.sub?.let { box(it, ss) }
        val sup = node.sup?.let { box(it, ss) }
        val sz = sizeOf(ss)
        if (style == Style.Display && isLimitsOp(node.base)) {
            val gap = s * 0.12f
            val w = maxOf(base.width, sub?.width ?: 0f, sup?.width ?: 0f)
            val ops = ArrayList<MathOp>()
            ops += base.shifted((w - base.width) / 2f, 0f)
            var asc = base.ascent
            var desc = base.descent
            if (sup != null) {
                val y = -(base.ascent + gap + sup.descent)
                ops += sup.shifted((w - sup.width) / 2f, y)
                asc = -y + sup.ascent
            }
            if (sub != null) {
                val y = base.descent + gap + sub.ascent
                ops += sub.shifted((w - sub.width) / 2f, y)
                desc = y + sub.descent
            }
            return MathBox(w, asc, desc, ops, base.atom)
        }
        var supUp = maxOf(base.ascent - sz * 0.35f, s * 0.38f)
        var subDown = maxOf(base.descent + sz * 0.15f, s * 0.2f)
        if (sup != null) supUp = maxOf(supUp, sup.descent + s * 0.25f)
        if (sub != null && sup != null) {
            val gap = (supUp - sup.descent) - (sub.ascent - subDown)
            val min = sz * 0.25f
            if (gap < min) subDown += min - gap
        } else if (sub != null) {
            subDown = maxOf(subDown, sub.ascent - s * 0.4f)
        }
        val ops = ArrayList(base.ops)
        val x = base.width + s * 0.04f
        var w = base.width
        var asc = base.ascent
        var desc = base.descent
        if (sup != null) {
            ops += sup.shifted(x, -supUp)
            w = maxOf(w, x + sup.width)
            asc = maxOf(asc, supUp + sup.ascent)
        }
        if (sub != null) {
            // Under an integral's slant the subscript sits a little to the left.
            val sx = if (node.base is TexNode.Sym && node.base.large && !node.base.limits) x - s * 0.18f else x
            ops += sub.shifted(sx, subDown)
            w = maxOf(w, sx + sub.width)
            desc = maxOf(desc, subDown + sub.descent)
        }
        return MathBox(w + s * 0.03f, asc, desc, ops, base.atom)
    }

    private fun frac(node: TexNode.Frac, style: Style, s0: Float): MathBox {
        val outer = node.style ?: style
        val s = sizeOf(outer).let { if (node.style != null && style != Style.Display && style != Style.Text) s0 else it }
        val inner = outer.fraction()
        val num = box(node.num, inner)
        val den = box(node.den, inner)
        val t = rule(s)
        val gap = if (outer == Style.Display) s * 0.14f else s * 0.09f
        val pad = s * 0.12f
        val w = maxOf(num.width, den.width) + 2 * pad
        val a = axis(s)
        val numY = -(a + t / 2f + gap + num.descent)
        val denY = -a + t / 2f + gap + den.ascent
        val ops = ArrayList<MathOp>()
        ops += num.shifted((w - num.width) / 2f, numY)
        ops += den.shifted((w - den.width) / 2f, denY)
        if (node.bar) ops += MathOp.Rule(s * 0.04f, -a - t / 2f, w - s * 0.08f, t)
        return MathBox(w, -numY + num.ascent, denY + den.descent, ops, Atom.Inner)
    }

    private fun sqrt(node: TexNode.Sqrt, style: Style, s: Float): MathBox {
        val body = box(node.body, style)
        val t = rule(s)
        val gap = s * 0.12f
        val top = body.ascent + gap + t
        val bottom = maxOf(body.descent, s * 0.05f)
        val sign = s * 0.55f + (top + bottom) * 0.08f
        val index = node.index?.let { box(it, Style.ScriptScript) }
        val lead = if (index != null) maxOf(0f, index.width - sign * 0.45f) else 0f
        val x0 = lead
        val ops = ArrayList<MathOp>()
        // The radical: a short rise, the down stroke, the long up stroke, then the bar over the body.
        val h = top + bottom
        val pts = floatArrayOf(
            x0, -h * 0.42f + bottom,
            x0 + sign * 0.22f, -h * 0.52f + bottom,
            x0 + sign * 0.5f, bottom,
            x0 + sign, -top + t / 2f,
            x0 + sign + body.width + s * 0.1f, -top + t / 2f,
        )
        ops += MathOp.Stroke(pts, t)
        ops += body.shifted(x0 + sign + s * 0.05f, 0f)
        if (index != null) {
            ops += index.shifted(0f, -h * 0.55f + bottom - index.descent)
        }
        val asc = maxOf(top + t / 2f, if (index != null) h * 0.55f - bottom + index.descent + index.ascent else 0f)
        return MathBox(x0 + sign + body.width + s * 0.12f, asc, bottom + t, ops)
    }

    private fun fenced(left: String, body: MathBox, right: String, s: Float, fixed: Float): MathBox {
        val a = axis(s)
        val half = if (fixed > 0f) s * fixed / 2f else maxOf(body.ascent - a, body.descent + a, s * 0.5f) * 1.08f
        val l = delimiter(left, half + a, half - a, s)
        val r = delimiter(right, half + a, half - a, s)
        val pad = s * 0.04f
        val ops = ArrayList<MathOp>()
        ops += l.ops
        ops += body.shifted(l.width + pad, 0f)
        ops += r.shifted(l.width + pad + body.width + pad, 0f)
        return MathBox(l.width + body.width + r.width + 2 * pad, maxOf(body.ascent, l.ascent), maxOf(body.descent, l.descent), ops, Atom.Inner)
    }

    /** A delimiter spanning [ascent] above and [descent] below the baseline: a glyph when small, a drawn path when tall. */
    private fun delimiter(d: String, ascent: Float, descent: Float, s: Float): MathBox {
        if (d.isEmpty()) return MathBox(s * 0.05f, 0f, 0f, emptyList())
        val h = ascent + descent
        val m = metrics.measure(d, MathFont.Roman, s)
        if (h <= m.ascent + m.descent + s * 0.15f || d !in "()[]{}|‖⌊⌋⌈⌉⟨⟩") {
            val g = glyph(d, MathFont.Roman, s, Atom.Ord)
            return MathBox(g.width, g.ascent, g.descent, g.ops)
        }
        val t = maxOf(1f, s * 0.055f) * (1f + (h / s - 1f).coerceIn(0f, 3f) * 0.08f)
        val w = s * 0.32f + h * 0.04f
        val top = -ascent
        val bot = descent
        val mid = (top + bot) / 2f
        val inset = w * 0.2f
        val ops = ArrayList<MathOp>()
        when (d) {
            "(" -> ops += MathOp.Stroke(floatArrayOf(w - inset, top, inset * 0.6f, mid, w - inset, bot), t, curve = true)
            ")" -> ops += MathOp.Stroke(floatArrayOf(inset, top, w - inset * 0.6f, mid, inset, bot), t, curve = true)
            "[" -> ops += MathOp.Stroke(floatArrayOf(w - inset, top, inset + t, top, inset + t, bot, w - inset, bot), t)
            "]" -> ops += MathOp.Stroke(floatArrayOf(inset, top, w - inset - t, top, w - inset - t, bot, inset, bot), t)
            "⌊" -> ops += MathOp.Stroke(floatArrayOf(inset + t, top, inset + t, bot, w - inset, bot), t)
            "⌋" -> ops += MathOp.Stroke(floatArrayOf(w - inset - t, top, w - inset - t, bot, inset, bot), t)
            "⌈" -> ops += MathOp.Stroke(floatArrayOf(w - inset, top, inset + t, top, inset + t, bot), t)
            "⌉" -> ops += MathOp.Stroke(floatArrayOf(inset, top, w - inset - t, top, w - inset - t, bot), t)
            "|" -> ops += MathOp.Rule(w / 2f - t / 2f, top, t, h)
            "‖" -> {
                ops += MathOp.Rule(w / 2f - t * 1.8f, top, t, h)
                ops += MathOp.Rule(w / 2f + t * 0.8f, top, t, h)
            }
            "⟨" -> ops += MathOp.Stroke(floatArrayOf(w - inset, top, inset, mid, w - inset, bot), t)
            "⟩" -> ops += MathOp.Stroke(floatArrayOf(inset, top, w - inset, mid, inset, bot), t)
            "{", "}" -> {
                val left = d == "{"
                val xo = if (left) w - inset else inset
                val xi = if (left) inset * 1.6f else w - inset * 1.6f
                val xc = w / 2f
                val q = (bot - top) / 4f
                // Two quadratic halves, each with a waist at the middle.
                ops += MathOp.Stroke(floatArrayOf(xo, top, xc, top, xc, top + q, xc, mid - t, xi, mid), t, curve = true)
                ops += MathOp.Stroke(floatArrayOf(xi, mid, xc, mid + t, xc, bot - q, xc, bot, xo, bot), t, curve = true)
            }
        }
        return MathBox(w, ascent, descent, ops)
    }

    private fun accent(node: TexNode.Accent, style: Style, s: Float): MathBox {
        val body = box(node.body, style)
        val t = rule(s)
        val ops = ArrayList(body.ops)
        val gap = s * 0.08f
        val w = body.width
        when (node.accent) {
            AccentKind.Overline, AccentKind.Bar -> {
                val y = -(body.ascent + gap + t)
                val inset = if (node.accent == AccentKind.Bar) w * 0.1f else 0f
                ops += MathOp.Rule(inset, y, w - 2 * inset, t)
                return MathBox(w, -y, body.descent, ops)
            }
            AccentKind.Underline -> {
                val y = body.descent + gap
                ops += MathOp.Rule(0f, y, w, t)
                return MathBox(w, body.ascent, y + t, ops)
            }
            AccentKind.Vec, AccentKind.OverArrowLeft -> {
                val y = -(body.ascent + gap + s * 0.1f)
                val len = maxOf(w, s * 0.45f)
                val x0 = (w - len) / 2f
                val head = s * 0.12f
                val right = node.accent == AccentKind.Vec
                val tip = if (right) x0 + len else x0
                val dir = if (right) -1f else 1f
                ops += MathOp.Stroke(floatArrayOf(x0, y, x0 + len, y), t)
                ops += MathOp.Stroke(floatArrayOf(tip + dir * head, y - head * 0.8f, tip, y, tip + dir * head, y + head * 0.8f), t)
                return MathBox(w, -y + head, body.descent, ops)
            }
            AccentKind.OverBrace, AccentKind.UnderBrace -> {
                val over = node.accent == AccentKind.OverBrace
                val bh = s * 0.25f
                val y0 = if (over) -(body.ascent + gap) else body.descent + gap
                val y1 = if (over) y0 - bh else y0 + bh
                val ym = (y0 + y1) / 2f
                ops += MathOp.Stroke(floatArrayOf(0f, y0, 0f, ym, w * 0.25f, ym, w / 2f, ym, w / 2f, y1), t, curve = true)
                ops += MathOp.Stroke(floatArrayOf(w / 2f, y1, w / 2f, ym, w * 0.75f, ym, w, ym, w, y0), t, curve = true)
                return if (over) MathBox(w, -y1, body.descent, ops) else MathBox(w, body.ascent, y1, ops)
            }
            else -> {
                val mark = when (node.accent) {
                    AccentKind.Hat -> "ˆ"
                    AccentKind.Tilde -> "˜"
                    AccentKind.Dot -> "˙"
                    AccentKind.DDot -> "¨"
                    AccentKind.Acute -> "´"
                    AccentKind.Grave -> "`"
                    AccentKind.Breve -> "˘"
                    AccentKind.Check -> "ˇ"
                    else -> "˚"
                }
                val m = metrics.ink(mark, MathFont.Roman, s)
                // Spacing accents sit high in their em box: put the bottom of their ink just above the body.
                val y = -(body.ascent + gap) - m.descent
                val slant = if (node.body is TexNode.Sym && (node.body.font == MathFont.Italic)) s * 0.06f else 0f
                ops += MathOp.Glyph(mark, (w - m.width) / 2f + slant, y, s, MathFont.Roman)
                return MathBox(maxOf(w, m.width), maxOf(body.ascent, -y + m.ascent), body.descent, ops)
            }
        }
    }

    private fun overUnder(node: TexNode.OverUnder, style: Style, s: Float): MathBox {
        val base = box(node.base, style)
        val over = node.over?.let { box(it, style.script()) }
        val under = node.under?.let { box(it, style.script()) }
        val gap = s * 0.08f
        val w = maxOf(base.width, over?.width ?: 0f, under?.width ?: 0f)
        val ops = ArrayList<MathOp>()
        ops += base.shifted((w - base.width) / 2f, 0f)
        var asc = base.ascent
        var desc = base.descent
        if (over != null) {
            val y = -(base.ascent + gap + over.descent)
            ops += over.shifted((w - over.width) / 2f, y)
            asc = -y + over.ascent
        }
        if (under != null) {
            val y = base.descent + gap + under.ascent
            ops += under.shifted((w - under.width) / 2f, y)
            desc = y + under.descent
        }
        return MathBox(w, asc, desc, ops, atomOf(node.base))
    }

    private fun matrix(node: TexNode.Matrix, style: Style, s: Float): MathBox {
        val cellStyle = if (node.small) style.script() else if (style == Style.Display && node.colGap == 0f) Style.Display else if (style == Style.Display) Style.Text else style
        val cells = node.rows.map { row -> row.map { box(it, cellStyle) } }
        val cols = cells.maxOfOrNull { it.size } ?: 0
        if (cols == 0) return MathBox.Empty
        val widths = FloatArray(cols)
        for (row in cells) for ((c, b) in row.withIndex()) widths[c] = maxOf(widths[c], b.width)
        val cs = sizeOf(cellStyle)
        val aligned = node.colGap == 0f
        val gapAfter = FloatArray(cols) { c -> if (aligned) (if (c % 2 == 1) cs * 1.5f else 0f) else node.colGap * cs }
        val rowGap = if (node.small) cs * 0.2f else cs * 0.35f
        val ascents = cells.map { row -> maxOf(row.maxOfOrNull { it.ascent } ?: 0f, cs * 0.7f) }
        val descents = cells.map { row -> maxOf(row.maxOfOrNull { it.descent } ?: 0f, cs * 0.25f) }
        val total = ascents.sum() + descents.sum() + rowGap * (cells.size - 1)
        val ops = ArrayList<MathOp>()
        var y = -(total / 2f + axis(s))
        var width = 0f
        for ((r, row) in cells.withIndex()) {
            val baseline = y + ascents[r]
            var x = 0f
            for (c in 0 until cols) {
                val b = row.getOrNull(c)
                if (b != null) {
                    val dx = when (node.align.getOrElse(c) { 'c' }) {
                        'l' -> 0f
                        'r' -> widths[c] - b.width
                        else -> (widths[c] - b.width) / 2f
                    }
                    ops += b.shifted(x + dx, baseline)
                }
                x += widths[c] + if (c < cols - 1) gapAfter[c] else 0f
            }
            width = maxOf(width, x)
            y = baseline + descents[r] + rowGap
        }
        val asc = total / 2f + axis(s)
        val desc = total / 2f - axis(s)
        val inner = MathBox(width, asc, desc, ops)
        if (node.left.isEmpty() && node.right.isEmpty()) return inner
        return fenced(node.left, inner, node.right, s, 0f)
    }
}
