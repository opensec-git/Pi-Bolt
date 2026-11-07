package opensec.pibolt.app.ui.markdown

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Report
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import opensec.pibolt.app.ui.components.CodeBlock
import opensec.pibolt.app.ui.components.MdTable
import opensec.pibolt.app.ui.diagram.MermaidBlock
import opensec.pibolt.app.ui.theme.Mono
import opensec.pibolt.app.ui.theme.MonoFeatures
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiColors
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Tnum

private const val CODE_TAG = "code"
private const val IMAGE_TAG = "image"

/** Width of a list level: the marker slot, and how far nested content steps in. */
val LIST_INDENT: Dp = 26.dp

/** Prose ink: ink1, ink2 inside block quotes. */
private val LocalProseInk = staticCompositionLocalOf<Color?> { null }

/** How many quotes enclose this block; past [MAX_QUOTE_DEPTH] a quote shows its text flat (hostile `> > > …`). */
private val LocalQuoteDepth = staticCompositionLocalOf { 0 }

private const val MAX_QUOTE_DEPTH = 6

/** Lists deeper than this stop stepping in, so deep nesting cannot push text off a phone screen. */
private const val MAX_LIST_DEPTH = 8

/**
 * One markdown block. [streaming] marks the block still being written: newly revealed text fades in and a small block
 * pulses at its end. [onOpenCode] opens a long code block in the full-screen viewer with a title the viewer resolves
 * back to its language ([CodeInfo.viewerTitle]). [proseInk] colours paragraphs and list items (the transcript's ink2;
 * headings stay ink1); [title] draws a heading as the turn's bold title (subhead, ink1).
 */
@Composable
fun MdBlockView(block: MdBlock, streaming: Boolean, onOpenCode: (code: String, language: String) -> Unit, modifier: Modifier = Modifier, proseInk: Color? = null, title: Boolean = false) {
    val colors = Pi.colors
    val type = Pi.type
    val ink = LocalProseInk.current ?: colors.ink1
    val prose = proseInk ?: ink
    when (block) {
        is MdBlock.Heading -> {
            // The turn's title takes subhead, but an h1 keeps its larger size, so an h2 under it still reads as lower.
            val style = if (title && block.level > 1) type.subhead else when (block.level) {
                1 -> type.headline
                2 -> type.subhead
                3 -> type.prose.copy(fontWeight = FontWeight.SemiBold)
                4 -> type.body.copy(fontWeight = FontWeight.SemiBold)
                5 -> type.label.copy(fontWeight = FontWeight.SemiBold)
                else -> type.label.copy(fontWeight = FontWeight.SemiBold, color = colors.ink2)
            }
            MdText(block.text, style.copy(color = if (block.level == 6 && !title) colors.ink2 else ink), streaming, modifier)
        }
        is MdBlock.Paragraph -> MdText(block.text, type.prose.copy(color = prose), streaming, modifier.indent(block.indent))
        is MdBlock.Code -> {
            val closed = block.closed || !streaming
            if (block.language.equals("mermaid", ignoreCase = true)) {
                // Draws a skeleton while the fence streams (debounced) and at once when it is closed.
                MermaidBlock(block.code, modifier.indent(block.indent), closed = closed)
            } else {
                CodeBlock(block.code, block.language, block.info, closed = closed, onOpen = onOpenCode, modifier = modifier.indent(block.indent))
            }
        }
        is MdBlock.ListItem -> Row(modifier.padding(start = LIST_INDENT * minOf(block.depth, MAX_LIST_DEPTH))) {
            ListMarker(block)
            MdText(block.text, type.prose.copy(color = prose), streaming)
        }
        is MdBlock.Quote -> QuoteView(block, streaming, onOpenCode, modifier.indent(block.indent))
        MdBlock.Rule -> Box(
            modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .height(1.dp)
                .background(colors.line),
        )
        is MdBlock.Table -> MdTable(block, modifier.indent(block.indent))
        is MdBlock.Math -> MathBlock(block.tex, closed = block.closed || !streaming, modifier = modifier.indent(block.indent))
        is MdBlock.Footnote -> Row(modifier) {
            Text(
                block.label,
                style = type.meta.copy(color = colors.accent, fontFeatureSettings = Tnum),
                maxLines = 1,
                modifier = Modifier.widthIn(min = LIST_INDENT).padding(end = 6.dp, top = 3.dp),
            )
            MdText(block.text, type.body.copy(color = colors.ink2), streaming)
        }
    }
}

private fun Modifier.indent(levels: Int): Modifier = if (levels <= 0) this else padding(start = LIST_INDENT * minOf(levels, MAX_LIST_DEPTH + 1))

/** The bullet, number or checkbox of a list item, in a [LIST_INDENT] slot on the first line. */
@Composable
private fun ListMarker(block: MdBlock.ListItem) {
    val colors = Pi.colors
    val type = Pi.type
    val lineHeight = with(LocalDensity.current) { type.prose.lineHeight.toDp() }
    val checked = block.checked
    if (checked != null) {
        Box(Modifier.widthIn(min = LIST_INDENT).height(lineHeight), contentAlignment = Alignment.CenterStart) {
            val box = colors.ink3
            val tick = colors.success
            Canvas(Modifier.size(16.dp)) {
                val r = 3.5.dp.toPx()
                val stroke = 1.5.dp.toPx()
                if (checked) {
                    drawRoundRect(tick.copy(alpha = 0.18f), cornerRadius = CornerRadius(r))
                    drawRoundRect(tick, cornerRadius = CornerRadius(r), style = Stroke(stroke))
                    val path = Path().apply {
                        moveTo(size.width * 0.26f, size.height * 0.52f)
                        lineTo(size.width * 0.44f, size.height * 0.7f)
                        lineTo(size.width * 0.76f, size.height * 0.32f)
                    }
                    drawPath(path, tick, style = Stroke(stroke * 1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                } else {
                    drawRoundRect(box, cornerRadius = CornerRadius(r), style = Stroke(stroke))
                }
            }
        }
        return
    }
    val marker = when {
        block.ordered -> block.marker
        block.depth % 3 == 1 -> "◦"
        block.depth % 3 == 2 -> "▪"
        else -> "•"
    }
    Text(
        marker,
        style = type.prose.copy(color = colors.ink3, fontFeatureSettings = Tnum),
        maxLines = 1,
        modifier = Modifier.widthIn(min = LIST_INDENT).padding(end = 6.dp),
    )
}

private enum class AlertKind(val title: String) { Note("Note"), Tip("Tip"), Important("Important"), Warning("Warning"), Caution("Caution") }

/** GitHub alerts: a quote whose first line is `[!NOTE]`, `[!TIP]`, `[!IMPORTANT]`, `[!WARNING]` or `[!CAUTION]`. */
private fun alertOf(text: String): Pair<AlertKind, String>? {
    val first = text.substringBefore('\n').trim()
    if (!first.startsWith("[!") || !first.endsWith("]")) return null
    val kind = when (first.substring(2, first.length - 1).uppercase()) {
        "NOTE" -> AlertKind.Note
        "TIP" -> AlertKind.Tip
        "IMPORTANT" -> AlertKind.Important
        "WARNING" -> AlertKind.Warning
        "CAUTION" -> AlertKind.Caution
        else -> return null
    }
    return kind to text.substringAfter('\n', "")
}

/** A block quote: a 2dp rail and its own blocks (nested quotes, lists, code), parsed incrementally while it streams. */
@Composable
private fun QuoteView(block: MdBlock.Quote, streaming: Boolean, onOpenCode: (String, String) -> Unit, modifier: Modifier) {
    val colors = Pi.colors
    val depth = LocalQuoteDepth.current
    if (depth >= MAX_QUOTE_DEPTH) {
        MdText(block.text, Pi.type.prose.copy(color = colors.ink2), streaming, modifier)
        return
    }
    val alert = remember(block.text) { alertOf(block.text) }
    val body = alert?.second ?: block.text
    val memo = remember { arrayOfNulls<MdDoc>(1) }
    val doc = remember(body) { Markdown.parse(body, memo[0]).also { memo[0] = it } }
    val (rail, icon) = when (alert?.first) {
        null -> colors.lineStrong to null
        AlertKind.Note -> colors.syntax.function to Icons.Rounded.Info
        AlertKind.Tip -> colors.success to Icons.Rounded.Lightbulb
        AlertKind.Important -> colors.syntax.keyword to Icons.Rounded.NewReleases
        AlertKind.Warning -> colors.warning to Icons.Rounded.WarningAmber
        AlertKind.Caution -> colors.danger to Icons.Rounded.Report
    }
    Column(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val w = 2.dp.toPx()
                drawRoundRect(rail, Offset(0f, 2.dp.toPx()), Size(w, size.height - 4.dp.toPx()), CornerRadius(w / 2f))
            }
            .padding(start = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (alert != null && icon != null) AlertTitle(alert.first, icon, rail)
        CompositionLocalProvider(LocalProseInk provides colors.ink2, LocalQuoteDepth provides depth + 1) {
            doc.blocks.forEachIndexed { i, b ->
                MdBlockView(b, streaming && i == doc.blocks.lastIndex, onOpenCode)
            }
        }
    }
}

@Composable
private fun AlertTitle(kind: AlertKind, icon: ImageVector, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(kind.title, style = Pi.type.label.copy(color = color))
    }
}

/**
 * Prose with inline styles. Inline code gets a rounded surface3 chip drawn behind its glyphs (one per line segment,
 * computed once per layout); inline math is drawn natively in placeholders; images show as tap-to-load cards under
 * the text.
 */
@Composable
private fun MdText(text: String, style: TextStyle, streaming: Boolean, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val density = LocalDensity.current
    val annotated = remember(text, colors) { inlineAnnotated(text, colors, colors.accent) }
    val math = remember(annotated, style.fontSize, density, style.color) { inlineMath(annotated, style, style.color, density) }
    val images = remember(annotated) { annotated.getStringAnnotations(IMAGE_TAG, 0, annotated.length) }
    if (images.isNotEmpty()) {
        val onlyImages = remember(annotated, images) { onlyImages(annotated.text, images.map { it.start until it.end }) }
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!onlyImages) ProseText(annotated, math, style, streaming, Modifier)
            for (image in images) MdImage(annotated.text.substring(image.start, image.end), image.item)
        }
        return
    }
    ProseText(annotated, math, style, streaming, modifier)
}

private fun onlyImages(text: String, ranges: List<IntRange>): Boolean {
    var k = 0
    for (r in ranges.sortedBy { it.first }) {
        for (j in k until r.first) if (!text[j].isWhitespace()) return false
        k = maxOf(k, r.last + 1)
    }
    for (j in k until text.length) if (!text[j].isWhitespace()) return false
    return true
}

@Composable
private fun ProseText(
    annotated: AnnotatedString,
    math: Map<String, InlineTextContent>,
    style: TextStyle,
    streaming: Boolean,
    modifier: Modifier,
) {
    val colors = Pi.colors
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var chips by remember { mutableStateOf(NO_CHIPS) }
    val codeRanges = remember(annotated) { codeRanges(annotated) }
    val reveal = rememberReveal(annotated.length, streaming)
    val pulse = if (streaming && !Pi.reduceMotion) {
        rememberInfiniteTransition(label = "caret").animateFloat(0.3f, 1f, infiniteRepeatable(tween(450, easing = LinearEasing), RepeatMode.Reverse), label = "caret")
    } else {
        null
    }
    Text(
        annotated,
        style = style,
        inlineContent = math,
        onTextLayout = {
            layout = it
            chips = if (codeRanges.isEmpty()) NO_CHIPS else chipRects(it, codeRanges)
        },
        modifier = modifier
            .drawBehind { drawChips(chips, colors.surface3, Radius.xs.toPx()) }
            .drawWithContent {
                drawContent()
                val l = layout ?: return@drawWithContent
                if (l.layoutInput.text.length != annotated.length) return@drawWithContent
                // Newly revealed text fades in: cover it with the canvas colour at a falling alpha.
                val now = reveal.now.longValue
                if (streaming && reveal.active) {
                    if (reveal.length in 0 until annotated.length) {
                        drawPath(l.getPathForRange(reveal.length, annotated.length), colors.canvas)
                    }
                    for (segment in reveal.segments) {
                        val a = 1f - ((now - segment.at) / 120f).coerceIn(0f, 1f)
                        if (a <= 0f || segment.start >= annotated.length) continue
                        drawPath(l.getPathForRange(segment.start, minOf(segment.end, annotated.length)), colors.canvas, alpha = a)
                    }
                }
                if (streaming && annotated.isNotEmpty()) {
                    // Right after the last visible glyph: text still covered by the reveal and trailing spaces do
                    // not count, so the block never floats away from the words.
                    var end = if (reveal.active && reveal.length in 1 until annotated.length) reveal.length else annotated.length
                    if (reveal.active) {
                        for (segment in reveal.segments) {
                            // Words still mostly hidden by their fade do not count either.
                            if (1f - ((now - segment.at) / 120f).coerceIn(0f, 1f) > 0.5f) end = minOf(end, segment.start)
                        }
                    }
                    while (end > 0 && annotated[end - 1].isWhitespace()) end--
                    if (end == 0) return@drawWithContent
                    val line = l.getLineForOffset(end - 1)
                    val x = l.getHorizontalPosition(end, usePrimaryDirection = true).coerceAtMost(l.getLineRight(line))
                    val h = 12.dp.toPx()
                    val w = 6.dp.toPx()
                    val cy = (l.getLineTop(line) + l.getLineBottom(line)) / 2f
                    drawRoundRect(
                        colors.ink3,
                        topLeft = Offset(x + 3.dp.toPx(), cy - h / 2f),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(1.5.dp.toPx()),
                        alpha = pulse?.value ?: 0.7f,
                    )
                }
            },
    )
}

private class Segment(val start: Int, var end: Int, val at: Long)

private class Reveal {
    val now = mutableLongStateOf(0L)
    val segments = ArrayList<Segment>()
    var length = -1
    var active = false
}

/**
 * Tracks text revealed while streaming so it can fade in over 120 ms. Text past [Reveal.length] is new this frame and
 * drawn fully covered until the effect records it as a fading segment. Draw-phase only: no recomposition per frame.
 */
@Composable
private fun rememberReveal(length: Int, streaming: Boolean): Reveal {
    val reveal = remember { Reveal() }
    val active = streaming && !Pi.reduceMotion
    LaunchedEffect(length, active) {
        if (!active || reveal.length < 0) {
            reveal.segments.clear()
            reveal.length = length
            reveal.now.longValue = System.nanoTime() / 1_000_000
            return@LaunchedEffect
        }
        if (length > reveal.length) reveal.segments += Segment(reveal.length, length, System.nanoTime() / 1_000_000)
        reveal.length = length
        while (reveal.segments.isNotEmpty()) {
            val t = withFrameMillis { System.nanoTime() / 1_000_000 }
            reveal.now.longValue = t
            reveal.segments.removeAll { t - it.at >= 120 }
        }
    }
    reveal.active = active
    return reveal
}

/**
 * Inline markdown as styled text: emphasis, code (with chip pads, see [InlineDisplay]), links, footnote references,
 * sub/superscripts, highlights, image alt text (annotated with its address for [MdImage]) and inline math as
 * placeholders ([inlineMath]); math that does not parse stays as its source in mono.
 */
fun inlineAnnotated(text: String, colors: PiColors, linkColor: Color): AnnotatedString {
    val display = InlineDisplay.build(Inline.parse(text))
    val source = display.text
    return buildAnnotatedString {
        var pos = 0
        for (span in display.spans) {
            if (span.kind != InlineSpan.Kind.Math || span.start < pos || span.end <= span.start) continue
            append(source, pos, span.start)
            val tex = source.substring(span.start, span.end)
            if (mathSupported(tex)) appendInlineContent(MATH_ID + tex, tex) else append(tex)
            pos = span.end
        }
        append(source, pos, source.length)
        for (span in display.spans) {
            if (span.end <= span.start) continue
            when (span.kind) {
                InlineSpan.Kind.Bold -> addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), span.start, span.end)
                InlineSpan.Kind.Italic -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), span.start, span.end)
                InlineSpan.Kind.Strike -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), span.start, span.end)
                InlineSpan.Kind.Underline -> addStyle(SpanStyle(textDecoration = TextDecoration.Underline), span.start, span.end)
                InlineSpan.Kind.Code -> addStyle(SpanStyle(fontFamily = Mono, fontSize = 0.88.em, fontFeatureSettings = MonoFeatures, color = colors.ink1), span.start, span.end)
                InlineSpan.Kind.Highlight -> addStyle(SpanStyle(background = colors.warning.copy(alpha = 0.22f)), span.start, span.end)
                InlineSpan.Kind.Superscript -> addStyle(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.75.em), span.start, span.end)
                InlineSpan.Kind.Subscript -> addStyle(SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 0.75.em), span.start, span.end)
                InlineSpan.Kind.FootnoteRef -> addStyle(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.72.em, color = linkColor, fontFeatureSettings = Tnum), span.start, span.end)
                InlineSpan.Kind.Math -> if (!mathSupported(source.substring(span.start, span.end))) {
                    addStyle(SpanStyle(fontFamily = Mono, fontSize = 0.88.em, fontFeatureSettings = MonoFeatures, color = colors.ink2), span.start, span.end)
                }
                InlineSpan.Kind.Image -> {
                    addStyle(SpanStyle(color = colors.ink2, fontStyle = FontStyle.Italic), span.start, span.end)
                    addStringAnnotation(IMAGE_TAG, span.url.orEmpty(), span.start, span.end)
                }
                InlineSpan.Kind.Link -> {
                    val url = span.url.orEmpty()
                    if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("mailto:")) {
                        addLink(
                            LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))),
                            span.start,
                            span.end,
                        )
                    } else {
                        addStyle(SpanStyle(color = linkColor), span.start, span.end)
                    }
                }
            }
        }
        // The pads take the code size (in the prose font) so the chip edges sit evenly around the code.
        for (chip in display.chips) {
            addStyle(SpanStyle(fontSize = 0.88.em), chip.first, chip.first + 1)
            addStyle(SpanStyle(fontSize = 0.88.em), chip.last, chip.last + 1)
            addStringAnnotation(CODE_TAG, "", chip.first, chip.last + 1)
        }
    }
}

/** Ranges of inline code chips (code plus its pads) in text built by [inlineAnnotated]. */
fun codeRanges(text: AnnotatedString): List<IntRange> = text.getStringAnnotations(CODE_TAG, 0, text.length).map { it.start until it.end }

private val NO_CHIPS = FloatArray(0)

private fun isChipGap(c: Char) = c == ' ' || c == '\n' || c == '\t' || c == InlineDisplay.BREAK

/**
 * Chip rectangles (left, top, right, bottom per chip segment) for [ranges] in a laid-out text: one per line a span
 * covers, trimmed of the spaces where it wraps, inset 14% of the line box top and bottom.
 */
private fun chipRects(l: TextLayoutResult, ranges: List<IntRange>): FloatArray {
    val text = l.layoutInput.text
    var out = FloatArray(ranges.size * 4)
    var n = 0
    for (range in ranges) {
        val endEx = minOf(range.last + 1, text.length)
        var start = range.first
        while (start < endEx) {
            while (start < endEx && isChipGap(text[start])) start++
            if (start >= endEx) break
            val line = l.getLineForOffset(start)
            val lineEnd = l.getLineEnd(line)
            var segEnd = minOf(endEx, lineEnd)
            while (segEnd > start && isChipGap(text[segEnd - 1])) segEnd--
            if (segEnd > start) {
                val top = l.getLineTop(line)
                val bottom = l.getLineBottom(line)
                val inset = (bottom - top) * 0.14f
                if (n + 4 > out.size) out = out.copyOf(out.size * 2)
                out[n] = l.getBoundingBox(start).left
                out[n + 1] = top + inset
                out[n + 2] = l.getBoundingBox(segEnd - 1).right
                out[n + 3] = bottom - inset
                n += 4
            }
            if (lineEnd <= start) break
            start = lineEnd
        }
    }
    return if (n == out.size) out else out.copyOf(n)
}

private fun DrawScope.drawChips(rects: FloatArray, color: Color, radius: Float) {
    var i = 0
    while (i + 3 < rects.size) {
        drawRoundRect(color, Offset(rects[i], rects[i + 1]), Size(rects[i + 2] - rects[i], rects[i + 3] - rects[i + 1]), CornerRadius(radius))
        i += 4
    }
}

/** Text with inline markdown already applied by [inlineAnnotated]: inline code gets its chips, math its drawing (table cells). */
@Composable
fun ChipText(text: AnnotatedString, style: TextStyle, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val density = LocalDensity.current
    val ranges = remember(text) { codeRanges(text) }
    val math = remember(text, style.fontSize, density, style.color) { inlineMath(text, style, style.color, density) }
    var chips by remember { mutableStateOf(NO_CHIPS) }
    Text(
        text,
        style = style,
        inlineContent = math,
        onTextLayout = { chips = if (ranges.isEmpty()) NO_CHIPS else chipRects(it, ranges) },
        modifier = modifier.drawBehind { drawChips(chips, colors.surface3, Radius.xs.toPx()) },
    )
}
