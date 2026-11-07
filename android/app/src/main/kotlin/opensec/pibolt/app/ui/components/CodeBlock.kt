package opensec.pibolt.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.WrapText
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import opensec.pibolt.app.ui.markdown.CodeFold
import opensec.pibolt.app.ui.markdown.CodeInfo
import opensec.pibolt.app.ui.markdown.Highlighter
import opensec.pibolt.app.ui.markdown.Languages
import opensec.pibolt.app.ui.markdown.UnifiedDiff
import opensec.pibolt.app.ui.markdown.applyTokens
import opensec.pibolt.app.ui.markdown.highlightedCached
import opensec.pibolt.app.ui.markdown.rememberHighlighted
import opensec.pibolt.app.ui.session.DiffLine
import opensec.pibolt.app.ui.session.diffStats
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiColors
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Tnum

/**
 * A fenced code block: surface1 card, 40dp header (language · file, then Lines, Wrap, Open and Copy as icons), code in
 * Mono 12.5/19 with optional line numbers behind a 1dp rule, horizontal scroll with edge fades. Blocks over
 * [CodeFold.FOLD_AT] lines fold to their first [CodeFold.FOLDED] with a bottom fade and a "Show all" bar that expands in
 * place (up to [CodeFold.INLINE_MAX] lines) or opens the viewer. While streaming ([closed] false) the header shows a
 * Quad and a ticking line count, and a long block follows its last lines at a fixed height. ```diff blocks render as
 * diff rows.
 *
 * Highlighting runs on complete lines only (once per new line, not per chunk), and closed blocks are cached by content.
 */
@Composable
fun CodeBlock(
    code: String,
    language: String,
    info: String,
    closed: Boolean,
    onOpen: ((code: String, title: String) -> Unit)?,
    modifier: Modifier = Modifier,
    lineNumbers: Boolean = true,
    title: String? = null,
) {
    val colors = Pi.colors
    val type = Pi.type
    val reduced = Pi.reduceMotion
    val meta = remember(language, info) { CodeInfo.of(language, info) }
    // Complete lines: the whole block once closed, else everything up to the last newline. Streaming only appends, so
    // the prefix changes exactly when its end moves.
    val stableEnd = if (closed) code.length else code.lastIndexOf('\n') + 1
    val stable = remember(stableEnd, closed) { code.substring(0, stableEnd) }
    val diff = remember(stable, meta) { meta.diff || meta.language.isEmpty() && UnifiedDiff.looksLikeDiff(stable) }
    val rows = remember(stable, closed, diff) { if (diff) UnifiedDiff.parse(if (closed) stable else stable.removeSuffix("\n")) else null }
    val lines = remember(stable, rows) { rows?.size ?: CodeFold.lineCount(stable) }
    // Untagged code: the language from its first lines (settles after a few lines while streaming).
    val detected = remember(meta.language, diff, closed, minOf(lines, 6)) { if (meta.language.isEmpty() && !diff) Languages.detect(stable) else "" }
    val lang = meta.language.ifEmpty { detected }
    val label = remember(stable, meta, diff, detected, title) {
        when {
            title != null -> title
            diff -> UnifiedDiff.path(stable) ?: meta.filename ?: "diff"
            detected.isNotEmpty() -> CodeInfo.displayLanguage(detected)
            else -> meta.label
        }
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var wrap by rememberSaveable { mutableStateOf(false) }
    var numbersOn by rememberSaveable { mutableStateOf(lineNumbers) }
    val mode = CodeFold.mode(lines, closed, expanded)
    val footer = CodeFold.footer(lines, closed, expanded)
    val scroll = rememberScrollState()
    val header = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val open = { onOpen?.invoke(code, if (meta.viewerTitle.isEmpty() && detected.isNotEmpty()) detected else meta.viewerTitle) ?: Unit }
    val shape = RoundedCornerShape(RadiusMedia)
    Column(
        modifier
            .fillMaxWidth()
            .then(if (closed && !reduced) Modifier.animateContentSize(PiMotion.inPlace(PiMotion.STANDARD)) else Modifier)
            .clip(shape)
            .background(colors.surface1)
            .border(1.dp, colors.line, shape),
    ) {
        CodeHeader(
            meta = meta,
            language = when {
                diff -> "diff"
                lang.isNotEmpty() -> CodeInfo.displayLanguage(lang)
                else -> "code"
            },
            label = label,
            closed = closed,
            lines = lines,
            rows = rows,
            wrap = wrap,
            // maxValue is Int.MAX_VALUE until the body is measured.
            showWrap = closed && (wrap || scroll.maxValue in 1 until Int.MAX_VALUE),
            showOpen = onOpen != null && closed && (mode == CodeFold.Mode.Head || lines >= CodeFold.OPEN_FROM),
            numbers = if (rows == null && lineNumbers && closed && lines >= CodeFold.NUMBERS_FROM) numbersOn else null,
            onNumbers = { numbersOn = !numbersOn },
            onWrap = { wrap = !wrap },
            onOpen = open,
            copy = { code },
            modifier = Modifier.bringIntoViewRequester(header),
        )
        Crossfade(
            targetState = mode == CodeFold.Mode.Tail,
            animationSpec = if (reduced) snap() else tween(PiMotion.QUICK),
            label = "codeWindow",
        ) { tail ->
            val tailScroll = rememberScrollState()
            val shownMode = if (tail) CodeFold.Mode.Tail else if (mode == CodeFold.Mode.Tail) CodeFold.Mode.Head else mode
            val fade = Modifier.verticalEdgeFade(
                colors.surface1,
                if (tail) 32.dp else 40.dp,
                atTop = tail,
                enabled = shownMode != CodeFold.Mode.Full,
            )
            if (rows != null) {
                val shown = when (shownMode) {
                    CodeFold.Mode.Full -> rows
                    CodeFold.Mode.Head -> rows.take(CodeFold.FOLDED)
                    CodeFold.Mode.Tail -> rows.takeLast(CodeFold.FOLDED)
                }
                DiffLines(
                    shown,
                    fade.padding(vertical = 6.dp),
                    wrap = wrap && closed,
                    scroll = if (tail) tailScroll else scroll,
                    language = remember(label) { CodeInfo.languageOfFile(label).orEmpty() },
                )
            } else {
                CodeBody(
                    code = code,
                    stable = stable,
                    language = lang,
                    mode = shownMode,
                    lines = lines,
                    closed = closed,
                    wrap = wrap && closed,
                    numbers = lineNumbers && numbersOn && lines >= CodeFold.NUMBERS_FROM,
                    scroll = if (tail) tailScroll else scroll,
                    modifier = fade,
                )
            }
        }
        if (footer != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
            val turn by animateFloatAsState(
                if (footer.action == CodeFold.Action.Collapse) 180f else 0f,
                if (reduced) snap() else PiMotion.inPlace(PiMotion.QUICK),
                label = "chevron",
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .pressable(
                        onClick = {
                            when (footer.action) {
                                CodeFold.Action.Open -> if (onOpen != null) open() else expanded = !expanded
                                CodeFold.Action.Expand -> expanded = true
                                CodeFold.Action.Collapse -> {
                                    expanded = false
                                    scope.launch { header.bringIntoView() }
                                }
                            }
                        },
                        shape = RectangleShape,
                        pressedScale = 1f,
                        label = footer.label,
                    ),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(footer.label, style = type.label.copy(fontFeatureSettings = Tnum), color = colors.ink2)
                Spacer(Modifier.width(4.dp))
                if (footer.action == CodeFold.Action.Open) {
                    Icon(Icons.Rounded.OpenInFull, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(14.dp))
                } else {
                    Icon(
                        Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null,
                        tint = colors.ink3,
                        modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = turn },
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeHeader(
    meta: CodeInfo,
    language: String,
    label: String,
    closed: Boolean,
    lines: Int,
    rows: List<DiffLine>?,
    wrap: Boolean,
    showWrap: Boolean,
    showOpen: Boolean,
    numbers: Boolean?,
    onNumbers: () -> Unit,
    onWrap: () -> Unit,
    onOpen: () -> Unit,
    copy: () -> String,
    modifier: Modifier = Modifier,
) {
    val colors = Pi.colors
    val type = Pi.type
    val reduced = Pi.reduceMotion
    val named = if (rows != null) label != "diff" else meta.filename != null
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // As on the desktop: the language, then " · " and the file when the fence names one (cut in the middle so
        // the file name stays), the actions as icons at the end.
        Text(language, style = type.monoMeta, color = colors.ink3, maxLines = 1)
        if (named && label != language) {
            Text(" · ", style = type.monoMeta, color = colors.ink3, maxLines = 1)
            Text(label, style = type.monoMeta, color = colors.ink2, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        AnimatedContent(
            targetState = closed,
            transitionSpec = {
                if (reduced) {
                    fadeIn(snap()).togetherWith(fadeOut(snap()))
                } else {
                    fadeIn(tween(PiMotion.QUICK)).togetherWith(fadeOut(tween(PiMotion.QUICK)))
                }
            },
            // Anchored at the end: the actions replace "12 lines" where it stood, instead of sliding in from the left
            // while the width animates.
            contentAlignment = Alignment.CenterEnd,
            label = "codeHeader",
        ) { done ->
            Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!done) {
                    QuadSpinner(QuadMode.Write, size = IconSize.xs, color = colors.ink2)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${CodeFold.count(lines)} ${if (lines == 1) "line" else "lines"}",
                        style = type.monoMeta.copy(fontFeatureSettings = Tnum),
                        color = colors.ink3,
                        modifier = Modifier.padding(end = 10.dp),
                    )
                } else {
                    if (rows != null) {
                        val stats = remember(rows) { diffStats(rows) }
                        if (stats.added > 0) Text("+${stats.added}", style = type.meta.copy(fontFeatureSettings = Tnum), color = colors.success)
                        if (stats.removed > 0) {
                            Spacer(Modifier.width(6.dp))
                            Text("−${stats.removed}", style = type.meta.copy(fontFeatureSettings = Tnum), color = colors.danger)
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    if (numbers != null) {
                        HeaderIcon(
                            Icons.Rounded.FormatListNumbered,
                            if (numbers) "Hide line numbers" else "Show line numbers",
                            tint = if (numbers) colors.ink2 else colors.ink3,
                            onClick = onNumbers,
                        )
                    }
                    if (showWrap) {
                        HeaderIcon(
                            Icons.AutoMirrored.Rounded.WrapText,
                            if (wrap) "Do not wrap lines" else "Wrap lines",
                            tint = if (wrap) colors.accent else colors.ink3,
                            onClick = onWrap,
                        )
                    }
                    if (showOpen) HeaderIcon(Icons.Rounded.OpenInFull, "Open code", tint = colors.ink3, onClick = onOpen)
                    CopyButton(copy, Modifier.height(40.dp), label = false, clipLabel = meta.filename ?: meta.label, description = "Copy code")
                }
            }
        }
    }
}

/** A 40dp header action (the touch target extends to 48dp), 16dp icon. */
@Composable
private fun HeaderIcon(icon: ImageVector, description: String, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .pressable(onClick, shape = RoundedCornerShape(Radius.sm), pressedScale = 0.92f, label = description),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(16.dp))
    }
}

/** The code text of a block in [mode]: the lines shown, with their first line number. */
@Composable
private fun CodeBody(
    code: String,
    stable: String,
    language: String,
    mode: CodeFold.Mode,
    lines: Int,
    closed: Boolean,
    wrap: Boolean,
    numbers: Boolean,
    scroll: ScrollState,
    modifier: Modifier,
) {
    val colors = Pi.colors
    val type = Pi.type
    val codeStyle = remember(type, colors) { type.monoSmall.copy(lineHeight = type.monoSmall.lineHeight * (19f / 18f), color = colors.ink1) }
    val numStyle = remember(codeStyle, colors) { codeStyle.copy(fontSize = codeStyle.fontSize * 0.92f, color = colors.ink3, textAlign = TextAlign.End) }
    val text: AnnotatedString
    val first: Int
    val count: Int
    // While streaming, complete lines are tokenised once each, resuming from the previous line's state.
    val stream = remember(language) { StreamHolder(language) }
    when (mode) {
        CodeFold.Mode.Full -> {
            val done = if (closed) {
                rememberHighlighted(stable, language, colors)
            } else {
                remember(stable.length, language, colors) { stream.window(stable, 0, colors) }
            }
            text = if (closed || stable.length == code.length) done else done + AnnotatedString(code.substring(stable.length))
            first = 1
            count = lines
        }
        CodeFold.Mode.Head -> {
            val head = remember(stable) { CodeFold.head(stable, CodeFold.FOLDED) }
            text = remember(head, language, colors) { highlightedCached(head, language, colors) }
            first = 1
            count = minOf(lines, CodeFold.FOLDED)
        }
        CodeFold.Mode.Tail -> {
            val from = CodeFold.tailStart(code, CodeFold.FOLDED).coerceAtMost(stable.length)
            val done = remember(from, stable.length, language, colors) { stream.window(stable, from, colors) }
            text = if (stable.length == code.length) done else done + AnnotatedString(code.substring(stable.length))
            var n = 1
            for (i in from until code.length) if (code[i] == '\n') n++
            count = n
            first = lines - count + 1
        }
    }
    val digits = maxOf(lines, first + count - 1).toString().length
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val gutter: Dp = remember(numStyle, digits, density) { with(density) { measurer.measure("0".repeat(digits), numStyle).size.width.toDp() } }
    val codeStart = if (numbers) 10.dp else 14.dp
    val ruleX = 12.dp + gutter + 8.dp
    val lineColor = colors.line
    if (wrap) {
        val starts = remember(text) { lineStarts(text.text) }
        Column(
            modifier
                .fillMaxWidth()
                .drawBehind { if (numbers) drawRect(lineColor, Offset(ruleX.toPx(), 0f), Size(1.dp.toPx(), size.height)) }
                .padding(vertical = 10.dp),
        ) {
            for (i in starts.indices) {
                val end = if (i + 1 < starts.size) starts[i + 1] - 1 else text.length
                Row {
                    if (numbers) Text((first + i).toString(), style = numStyle, modifier = Modifier.padding(start = 12.dp, end = 8.dp).width(gutter))
                    Text(text.subSequence(starts[i], end), style = codeStyle, modifier = Modifier.weight(1f).padding(start = codeStart, end = 14.dp))
                }
            }
        }
        return
    }
    val gutterText = remember(first, count, digits) { (first until first + count).joinToString("\n") { it.toString().padStart(digits) } }
    Row(modifier.fillMaxWidth()) {
        if (numbers) {
            Text(
                gutterText,
                style = numStyle,
                softWrap = false,
                modifier = Modifier
                    .drawBehind { drawRect(lineColor, Offset(size.width - 1.dp.toPx(), 0f), Size(1.dp.toPx(), size.height)) }
                    .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
                    .width(gutter),
            )
        }
        Box(
            Modifier
                .weight(1f)
                .horizontalEdgeFades(scroll, colors.surface1)
                .horizontalScroll(scroll),
        ) {
            CodeText(text, codeStyle, streaming = !closed, modifier = Modifier.padding(start = codeStart, end = 14.dp, top = 10.dp, bottom = 10.dp))
        }
    }
}

/** Non-wrapping code text; while [streaming], a 6x12dp ink3 block pulses after the last glyph, as in prose. */
@Composable
private fun CodeText(text: AnnotatedString, style: TextStyle, streaming: Boolean, modifier: Modifier) {
    val colors = Pi.colors
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val pulse: State<Float>? = if (streaming && !Pi.reduceMotion) {
        rememberInfiniteTransition(label = "codeCaret").animateFloat(0.3f, 1f, infiniteRepeatable(tween(PiMotion.PULSE, easing = LinearEasing), RepeatMode.Reverse), label = "codeCaret")
    } else {
        null
    }
    Text(
        text,
        style = style,
        softWrap = false,
        onTextLayout = { layout = it },
        modifier = modifier.drawWithContent {
            drawContent()
            if (!streaming) return@drawWithContent
            val l = layout ?: return@drawWithContent
            if (l.lineCount == 0) return@drawWithContent
            val line = l.lineCount - 1
            val h = 12.dp.toPx()
            val cy = (l.getLineTop(line) + l.getLineBottom(line)) / 2f
            val x = if (l.getLineEnd(line) > l.getLineStart(line)) l.getLineRight(line) + 3.dp.toPx() else 0f
            drawRoundRect(colors.ink3, Offset(x, cy - h / 2f), Size(6.dp.toPx(), h), CornerRadius(1.5.dp.toPx()), alpha = pulse?.value ?: 0.7f)
        },
    )
}

/** A [Highlighter.Stream] for a block that grows; starts over if the text stops being an extension of what it saw. */
private class StreamHolder(private val language: String) {
    private var stream = Highlighter.Stream(language)
    private var tail = ""

    /** [stable] (complete lines) from [from] on, highlighted with the streamed tokens. */
    fun window(stable: String, from: Int, colors: PiColors): AnnotatedString {
        // A cheap check that the text still extends what was tokenised: its length and the last characters seen.
        val n = stream.length
        if (stable.length < n || !stable.regionMatches(n - tail.length, tail, 0, tail.length)) stream = Highlighter.Stream(language)
        val tokens = stream.update(stable)
        tail = stable.takeLast(32)
        return applyTokens(stable.substring(from), tokens, colors, from)
    }
}

private fun lineStarts(text: String): IntArray {
    var n = 1
    for (c in text) if (c == '\n') n++
    val out = IntArray(n)
    var k = 1
    for (i in text.indices) if (text[i] == '\n') out[k++] = i + 1
    return out
}
