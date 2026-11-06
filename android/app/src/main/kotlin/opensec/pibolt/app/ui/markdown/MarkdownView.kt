package opensec.pibolt.app.ui.markdown

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import opensec.pibolt.app.ui.components.CodeBlock
import opensec.pibolt.app.ui.components.MdTable
import opensec.pibolt.app.ui.theme.Mono
import opensec.pibolt.app.ui.theme.MonoFeatures
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiColors
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Tnum

private const val CODE_TAG = "code"

/**
 * One markdown block. [streaming] marks the block still being written: newly revealed text fades in and a small block
 * pulses at its end. [onOpenCode] opens a long code block in the full-screen viewer with a title the viewer resolves
 * back to its language ([CodeInfo.viewerTitle]).
 */
@Composable
fun MdBlockView(block: MdBlock, streaming: Boolean, onOpenCode: (code: String, language: String) -> Unit, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    when (block) {
        is MdBlock.Heading -> {
            val style = when (block.level) {
                1 -> type.headline
                2, 3 -> type.subhead
                else -> type.label.copy(fontWeight = FontWeight.SemiBold)
            }
            MdText(block.text, style.copy(color = colors.ink1), streaming, modifier)
        }
        is MdBlock.Paragraph -> MdText(block.text, type.prose.copy(color = colors.ink1), streaming, modifier)
        is MdBlock.Code -> CodeBlock(block.code, block.language, block.info, closed = block.closed || !streaming, onOpen = onOpenCode, modifier = modifier)
        is MdBlock.ListItem -> Row(modifier.padding(start = (block.depth * 18).dp)) {
            val marker = when {
                block.checked == true -> "☑"
                block.checked == false -> "☐"
                block.ordered -> block.marker
                block.depth % 2 == 1 -> "◦"
                else -> "•"
            }
            Text(
                marker,
                style = type.prose.copy(color = colors.ink3, fontFeatureSettings = Tnum),
                modifier = Modifier.widthIn(min = 22.dp).padding(end = 6.dp),
            )
            MdText(block.text, type.prose.copy(color = colors.ink1), streaming)
        }
        is MdBlock.Quote -> Row(modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(2.dp)
                    .padding(vertical = 2.dp)
                    .fillMaxHeight()
                    .background(colors.lineStrong, RoundedCornerShape(1.dp)),
            )
            MdText(block.text, type.prose.copy(color = colors.ink2), streaming, Modifier.padding(start = 14.dp))
        }
        MdBlock.Rule -> Box(
            modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .height(1.dp)
                .background(colors.line),
        )
        is MdBlock.Table -> MdTable(block, modifier)
    }
}

/**
 * Prose with inline styles. Inline code gets a rounded surface3 chip drawn behind its glyphs (one per line segment,
 * computed once per layout).
 */
@Composable
private fun MdText(text: String, style: TextStyle, streaming: Boolean, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val annotated = remember(text, colors) { inlineAnnotated(text, colors, colors.accent) }
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

fun inlineAnnotated(text: String, colors: PiColors, linkColor: Color): AnnotatedString {
    val display = InlineDisplay.build(Inline.parse(text))
    return buildAnnotatedString {
        append(display.text)
        for (span in display.spans) {
            when (span.kind) {
                InlineSpan.Kind.Bold -> addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), span.start, span.end)
                InlineSpan.Kind.Italic -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), span.start, span.end)
                InlineSpan.Kind.Strike -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), span.start, span.end)
                InlineSpan.Kind.Code -> addStyle(SpanStyle(fontFamily = Mono, fontSize = 0.88.em, fontFeatureSettings = MonoFeatures, color = colors.ink1), span.start, span.end)
                InlineSpan.Kind.Link -> {
                    val url = span.url.orEmpty()
                    if (url.startsWith("http://") || url.startsWith("https://")) {
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

/** Text with inline markdown already applied by [inlineAnnotated]: inline code gets its chips (table cells). */
@Composable
fun ChipText(text: AnnotatedString, style: TextStyle, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val ranges = remember(text) { codeRanges(text) }
    var chips by remember { mutableStateOf(NO_CHIPS) }
    Text(
        text,
        style = style,
        onTextLayout = { chips = if (ranges.isEmpty()) NO_CHIPS else chipRects(it, ranges) },
        modifier = modifier.drawBehind { drawChips(chips, colors.surface3, Radius.xs.toPx()) },
    )
}

/**
 * Syntax colours (DESIGN.md): keywords ink1 SemiBold, calls ink1 Medium, types synType, strings, numbers, punctuation
 * ink2, comments ink3 italic, the shell prompt in success.
 */
fun highlighted(code: String, language: String, colors: PiColors): AnnotatedString {
    val tokens = Highlighter.tokenize(code, language)
    if (tokens.isEmpty()) return AnnotatedString(code)
    return buildAnnotatedString {
        append(code)
        for (token in tokens) {
            val style = when (token.kind) {
                Highlighter.Kind.Keyword -> SpanStyle(color = colors.ink1, fontWeight = FontWeight.SemiBold)
                Highlighter.Kind.String -> SpanStyle(color = colors.synString)
                Highlighter.Kind.Comment -> SpanStyle(color = colors.ink3, fontStyle = FontStyle.Italic)
                Highlighter.Kind.Number -> SpanStyle(color = colors.synNumber)
                Highlighter.Kind.Function -> SpanStyle(color = colors.ink1, fontWeight = FontWeight.Medium)
                Highlighter.Kind.Type -> SpanStyle(color = colors.synType)
                Highlighter.Kind.Punctuation -> SpanStyle(color = colors.ink2)
                Highlighter.Kind.Prompt -> SpanStyle(color = colors.success, fontWeight = FontWeight.SemiBold)
            }
            addStyle(style, token.start, token.end)
        }
    }
}

private data class HighlightKey(val code: String, val language: String, val colors: PiColors)

/** Recently highlighted texts, so blocks scrolled back into view (or re-opened in the viewer) are not re-tokenised. */
private val highlightCache = object : LinkedHashMap<HighlightKey, AnnotatedString>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<HighlightKey, AnnotatedString>?): Boolean = size > 64
}

/** [highlighted], cached by content, language and palette. */
fun highlightedCached(code: String, language: String, colors: PiColors): AnnotatedString {
    if (code.isEmpty()) return AnnotatedString("")
    val key = HighlightKey(code, language, colors)
    synchronized(highlightCache) { highlightCache[key]?.let { return it } }
    val value = highlighted(code, language, colors)
    synchronized(highlightCache) { highlightCache[key] = value }
    return value
}
