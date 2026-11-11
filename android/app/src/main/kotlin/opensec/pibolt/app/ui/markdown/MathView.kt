package opensec.pibolt.app.ui.markdown

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.components.horizontalEdgeFades
import opensec.pibolt.app.ui.markdown.math.GlyphMetrics
import opensec.pibolt.app.ui.markdown.math.MathBox
import opensec.pibolt.app.ui.markdown.math.MathFont
import opensec.pibolt.app.ui.markdown.math.MathLayout
import opensec.pibolt.app.ui.markdown.math.MathMetrics
import opensec.pibolt.app.ui.markdown.math.MathOp
import opensec.pibolt.app.ui.markdown.math.Tex
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius
import java.util.EnumMap

/** Measures and draws math with platform paints: serif italic for variables, serif upright for the rest. */
private object PaintMath : MathMetrics {
    private val paints = EnumMap<MathFont, Paint>(MathFont::class.java)
    private val bounds = Rect()

    fun paint(font: MathFont): Paint = paints.getOrPut(font) {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = when (font) {
                MathFont.Italic -> Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                MathFont.Bold -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
                MathFont.BoldItalic -> Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
                MathFont.Sans -> Typeface.SANS_SERIF
                MathFont.Mono -> Typeface.MONOSPACE
                else -> Typeface.SERIF
            }
        }
    }

    @Synchronized
    override fun measure(text: String, font: MathFont, size: Float): GlyphMetrics {
        val p = paint(font)
        p.textSize = size
        val w = p.measureText(text)
        p.getTextBounds(text, 0, text.length, bounds)
        val blank = text.isBlank()
        return GlyphMetrics(w, if (blank) 0f else maxOf(0f, -bounds.top.toFloat()), if (blank) 0f else maxOf(0f, bounds.bottom.toFloat()))
    }

    @Synchronized
    override fun ink(text: String, font: MathFont, size: Float): GlyphMetrics {
        val p = paint(font)
        p.textSize = size
        p.getTextBounds(text, 0, text.length, bounds)
        return GlyphMetrics(p.measureText(text), -bounds.top.toFloat(), bounds.bottom.toFloat())
    }

    @Synchronized
    override fun hasGlyph(text: String, font: MathFont): Boolean = paint(font).hasGlyph(text)
}

private data class MathKey(val tex: String, val display: Boolean, val size: Float)

/** A laid-out formula, or none when its TeX is not supported. */
private class MathResult(val box: MathBox?)

private val mathCache = LruCache<MathKey, MathResult>(256)

/** [tex] laid out at [sizePx], cached; null when the TeX is not supported (callers show the source). */
fun layoutMath(tex: String, display: Boolean, sizePx: Float): MathBox? {
    val key = MathKey(tex, display, sizePx)
    mathCache.get(key)?.let { return it.box }
    val box = Tex.parseOrNull(tex)?.let { node ->
        try {
            MathLayout(PaintMath, sizePx).layout(node, display)
        } catch (_: RuntimeException) {
            null
        }
    }
    mathCache.put(key, MathResult(box))
    return box
}

/** True when [tex] parses: inline math that does not falls back to a code-styled span. */
fun mathSupported(tex: String): Boolean = Tex.parseOrNull(tex) != null

private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}
private val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG)

/** Draws [box] with its origin at ([x], [baseline]). Main thread only (shared paints). */
fun DrawScope.drawMath(box: MathBox, x: Float, baseline: Float, color: Color) {
    val argb = color.toArgb()
    drawIntoCanvas { canvas ->
        val c = canvas.nativeCanvas
        for (op in box.ops) {
            when (op) {
                is MathOp.Glyph -> {
                    val p = PaintMath.paint(op.font)
                    p.textSize = op.size
                    p.color = argb
                    c.drawText(op.text, x + op.x, baseline + op.y, p)
                }
                is MathOp.Rule -> {
                    rulePaint.color = argb
                    c.drawRect(x + op.x, baseline + op.y, x + op.x + op.width, baseline + op.y + op.height, rulePaint)
                }
                is MathOp.Stroke -> {
                    strokePaint.color = argb
                    strokePaint.strokeWidth = op.width
                    val path = Path()
                    val pts = op.points
                    path.moveTo(x + pts[0], baseline + pts[1])
                    var k = 2
                    if (op.curve) {
                        while (k + 3 < pts.size) {
                            path.quadTo(x + pts[k], baseline + pts[k + 1], x + pts[k + 2], baseline + pts[k + 3])
                            k += 4
                        }
                    }
                    while (k + 1 < pts.size) {
                        path.lineTo(x + pts[k], baseline + pts[k + 1])
                        k += 2
                    }
                    c.drawPath(path, strokePaint)
                }
            }
        }
    }
}

/**
 * Display math: the formula centred (scrolling sideways when wider than the column), drawn natively. While it
 * streams, or when its TeX is not supported, the source shows in a math-styled box instead.
 */
@Composable
fun MathBlock(tex: String, closed: Boolean, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    val density = LocalDensity.current
    val sizePx = with(density) { type.prose.fontSize.toPx() } * 1.12f
    val box = remember(tex, sizePx, closed) { if (closed) layoutMath(tex, display = true, sizePx = sizePx) else null }
    if (box == null) {
        MathSource(tex, modifier)
        return
    }
    val scroll = rememberScrollState()
    BoxWithConstraints(modifier.fillMaxWidth().semantics { contentDescription = tex }) {
        val min = maxWidth
        Box(
            Modifier
                .horizontalEdgeFades(scroll, colors.canvas)
                .horizontalScroll(scroll),
        ) {
            Box(Modifier.widthIn(min = min).padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                val w = with(density) { (box.width + 4f).toDp() }
                val h = with(density) { (box.height + 4f).toDp() }
                val ink = colors.ink1
                Canvas(Modifier.size(w, h)) { drawMath(box, 2f, box.ascent + 2f, ink) }
            }
        }
    }
}

/** The TeX source in a quiet mono box: unsupported or still-streaming math. */
@Composable
fun MathSource(tex: String, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val shape = RoundedCornerShape(Radius.md)
    Box(
        modifier
            .fillMaxWidth()
            .background(colors.surface1, shape)
            .border(1.dp, colors.line, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(tex, style = Pi.type.monoSmall.copy(color = colors.ink2))
        Text("TeX", style = Pi.type.micro.copy(color = colors.ink3), modifier = Modifier.align(Alignment.TopEnd))
    }
}

/** Distance of the text's vertical centre above its baseline, in ems (Inter: (ascent − descent) / 2). */
private const val TEXT_CENTER_EM = 0.36f

/**
 * Placeholders for the inline math in [text] (built by [inlineAnnotated]): each formula drawn natively and aligned
 * on the text baseline. Empty when the text has none.
 */
fun inlineMath(text: AnnotatedString, style: TextStyle, color: Color, density: Density): Map<String, InlineTextContent> {
    val ids = text.getStringAnnotations(INLINE_CONTENT_TAG, 0, text.length)
    if (ids.isEmpty()) return emptyMap()
    val out = HashMap<String, InlineTextContent>()
    val fontPx = with(density) { style.fontSize.toPx() }
    val sizePx = fontPx * 1.06f
    for (a in ids) {
        if (a.item in out || !a.item.startsWith(MATH_ID)) continue
        val tex = a.item.removePrefix(MATH_ID)
        val box = layoutMath(tex, display = false, sizePx = sizePx) ?: continue
        val c = fontPx * TEXT_CENTER_EM
        // Symmetric around the text centre, so PlaceholderVerticalAlign.TextCenter puts the math baseline on the text's.
        val half = maxOf(box.ascent - c, box.descent + c, fontPx * 0.3f)
        val w = box.width + 2f
        val h = 2f * half
        val placeholder = with(density) { Placeholder(w.toSp(), h.toSp(), PlaceholderVerticalAlign.TextCenter) }
        out[a.item] = InlineTextContent(placeholder) {
            Canvas(Modifier.fillMaxSize()) { drawMath(box, 1f, half + c, color) }
        }
    }
    return out
}

/** The tag Compose's appendInlineContent uses for placeholders. */
const val INLINE_CONTENT_TAG = "androidx.compose.foundation.text.inlineContent"

const val MATH_ID = "math:"
