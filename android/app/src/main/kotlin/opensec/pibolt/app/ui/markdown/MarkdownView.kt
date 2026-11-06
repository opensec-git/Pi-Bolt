package opensec.pibolt.app.ui.markdown

import android.content.ClipData
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
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
import kotlinx.coroutines.launch
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.theme.Mono
import opensec.pibolt.app.ui.theme.MonoFeatures
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiColors
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Tnum

private const val CODE_TAG = "code"

/** Code blocks longer than this fold to [CODE_FOLDED_LINES] with a "Show all" bar. */
const val CODE_FOLD_AT = 30
const val CODE_FOLDED_LINES = 16

/**
 * One markdown block. [streaming] marks the block still being written: newly revealed text fades in and a small block
 * pulses at its end. [onOpenCode] opens a long code block in the full-screen viewer.
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
        is MdBlock.Code -> CodeBlock(block.code, block.language, closed = block.closed || !streaming, onOpen = onOpenCode, modifier = modifier)
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
        is MdBlock.Table -> TableBlock(block, modifier)
    }
}

/** Prose with inline styles. Inline code gets a rounded surface3 background drawn behind its glyphs. */
@Composable
private fun MdText(text: String, style: TextStyle, streaming: Boolean, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val annotated = remember(text, colors) { inlineAnnotated(text, colors, colors.accent) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val codeRanges = remember(annotated) { annotated.getStringAnnotations(CODE_TAG, 0, annotated.length).map { it.start until it.end } }
    val reveal = rememberReveal(annotated.length, streaming)
    val pulse = if (streaming && !Pi.reduceMotion) {
        rememberInfiniteTransition(label = "caret").animateFloat(0.3f, 1f, infiniteRepeatable(tween(450, easing = LinearEasing), RepeatMode.Reverse), label = "caret")
    } else {
        null
    }
    Text(
        annotated,
        style = style,
        onTextLayout = { layout = it },
        modifier = modifier
            .drawBehind {
                val l = layout ?: return@drawBehind
                if (codeRanges.isEmpty() || l.layoutInput.text.length != annotated.length) return@drawBehind
                val padX = 3.dp.toPx()
                val radius = Radius.xs.toPx()
                for (range in codeRanges) {
                    if (range.isEmpty()) continue
                    var start = range.first
                    while (start <= range.last) {
                        val line = l.getLineForOffset(start)
                        val lineEnd = minOf(range.last, l.getLineEnd(line, visibleEnd = true) - 1)
                        if (lineEnd < start) break
                        val left = l.getHorizontalPosition(start, usePrimaryDirection = true)
                        val right = l.getBoundingBox(lineEnd).right
                        val top = l.getLineTop(line)
                        val bottom = l.getLineBottom(line)
                        val inset = (bottom - top) * 0.14f
                        drawRoundRect(
                            colors.surface3,
                            topLeft = Offset(left - padX, top + inset),
                            size = Size(right - left + padX * 2, bottom - top - inset * 2),
                            cornerRadius = CornerRadius(radius),
                        )
                        start = lineEnd + 1
                    }
                }
            }
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
                    val cursor = l.getCursorRect(annotated.length)
                    val h = 12.dp.toPx()
                    val w = 6.dp.toPx()
                    val cy = (cursor.top + cursor.bottom) / 2f
                    drawRoundRect(
                        colors.ink3,
                        topLeft = Offset(cursor.left + 3.dp.toPx(), cy - h / 2f),
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
    val parsed = Inline.parse(text)
    return buildAnnotatedString {
        append(parsed.text)
        for (span in parsed.spans) {
            when (span.kind) {
                InlineSpan.Kind.Bold -> addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), span.start, span.end)
                InlineSpan.Kind.Italic -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), span.start, span.end)
                InlineSpan.Kind.Strike -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), span.start, span.end)
                InlineSpan.Kind.Code -> {
                    addStyle(SpanStyle(fontFamily = Mono, fontSize = 0.88.em, fontFeatureSettings = MonoFeatures, color = colors.ink1), span.start, span.end)
                    addStringAnnotation(CODE_TAG, "", span.start, span.end)
                }
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
    }
}

/** Syntax colours: keywords ink1 SemiBold, strings, numbers, calls in the type colour, comments ink3 italic. */
fun highlighted(code: String, language: String, colors: PiColors): AnnotatedString = buildAnnotatedString {
    append(code)
    for (token in Highlighter.tokenize(code, language)) {
        val style = when (token.kind) {
            Highlighter.Kind.Keyword -> SpanStyle(color = colors.ink1, fontWeight = FontWeight.SemiBold)
            Highlighter.Kind.String -> SpanStyle(color = colors.synString)
            Highlighter.Kind.Comment -> SpanStyle(color = colors.ink3, fontStyle = FontStyle.Italic)
            Highlighter.Kind.Number -> SpanStyle(color = colors.synNumber)
            Highlighter.Kind.Function -> SpanStyle(color = colors.synType)
        }
        addStyle(style, token.start, token.end)
    }
}

/**
 * Highlight for a code block that may still be streaming: complete lines are highlighted once per new line (not per
 * chunk); the line being written stays plain until it ends.
 */
@Composable
private fun rememberHighlight(code: String, language: String, closed: Boolean, colors: PiColors): AnnotatedString {
    val cut = if (closed) code.length else code.lastIndexOf('\n') + 1
    val prefix = remember(cut, closed, language, colors) { highlighted(code.substring(0, cut), language, colors) }
    return if (cut == code.length) prefix else prefix + AnnotatedString(code.substring(cut))
}

/** Fenced code: surface1, radius 12, 36dp header (language, open, copy), horizontal scroll, folded past 30 lines. */
@Composable
fun CodeBlock(code: String, language: String, closed: Boolean, onOpen: (String, String) -> Unit, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val haptics = Pi.haptics
    val lines = remember(code) { code.count { it == '\n' } + 1 }
    val folded = lines > CODE_FOLD_AT
    val shown = if (folded) remember(code) { headLines(code, CODE_FOLDED_LINES) } else code
    val text = rememberHighlight(shown, language, closed || folded, colors)
    val shape = RoundedCornerShape(Radius.md)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface1)
            .border(1.dp, colors.line, shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                language.ifBlank { "code" },
                style = type.monoMeta.copy(color = colors.ink3),
                modifier = Modifier.weight(1f),
            )
            if (folded) IconAction(Icons.Rounded.OpenInFull, "Open code", onClick = { onOpen(code, language) }, size = 16.dp, tint = colors.ink3)
            IconAction(Icons.Rounded.ContentCopy, "Copy code", onClick = {
                haptics.confirm()
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", code))) }
            }, size = 16.dp, tint = colors.ink3)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
        Text(
            text,
            style = type.mono.copy(color = colors.ink1),
            softWrap = false,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
        if (folded) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .pressable({ onOpen(code, language) }, shape = RoundedCornerShape(0.dp), pressedScale = 1f)
                    .background(colors.surface2),
                contentAlignment = Alignment.Center,
            ) {
                Text("Show all $lines lines", style = type.label, color = colors.ink2)
            }
        }
    }
}

private fun headLines(text: String, n: Int): String {
    var i = 0
    repeat(n) {
        val nl = text.indexOf('\n', i)
        if (nl < 0) return text
        i = nl + 1
    }
    return text.substring(0, i).trimEnd('\n')
}

@Composable
private fun TableBlock(table: MdBlock.Table, modifier: Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    val columns = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    val shape = RoundedCornerShape(Radius.md)
    Box(
        modifier
            .clip(shape)
            .border(1.dp, colors.line, shape)
            .horizontalScroll(rememberScrollState()),
    ) {
        Column(Modifier.width(IntrinsicSize.Max)) {
            val rows = listOf(table.header) + table.rows
            rows.forEachIndexed { r, row ->
                Row(Modifier.fillMaxWidth().background(if (r == 0) colors.surface1 else Color.Transparent)) {
                    for (c in 0 until columns) {
                        val cell = row.getOrElse(c) { "" }
                        val styled = remember(cell, colors) { inlineAnnotated(cell, colors, colors.accent) }
                        Text(
                            styled,
                            style = (if (r == 0) type.label.copy(fontWeight = FontWeight.SemiBold) else type.body).copy(color = colors.ink1),
                            modifier = Modifier
                                .widthIn(min = 80.dp, max = 260.dp)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
                if (r < rows.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
            }
        }
    }
}
