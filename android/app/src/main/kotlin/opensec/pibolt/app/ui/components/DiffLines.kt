package opensec.pibolt.app.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.markdown.UnifiedDiff
import opensec.pibolt.app.ui.markdown.highlightedCached
import opensec.pibolt.app.ui.session.DiffKind
import opensec.pibolt.app.ui.session.DiffLine
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiColors

private val BAR = 3.dp
private val SIGN = 14.dp

/**
 * The line-number gutters of a diff: [old] and [new] are the widths in digits of the old- and new-file columns. A diff
 * whose rows carry one number only (markdown fences) has [old] 0 and one column.
 */
@Immutable
data class DiffGutter(val old: Int, val new: Int) {
    val any: Boolean get() = old > 0 || new > 0

    companion object {
        fun of(lines: List<DiffLine>): DiffGutter {
            var old = 0
            var new = 0
            for (l in lines) {
                if (l.kind == DiffKind.Skip || l.kind == DiffKind.Hunk) continue
                if (l.old.length > old) old = l.old.length
                if (l.number.length > new) new = l.number.length
            }
            return DiffGutter(old, new)
        }
    }
}

/**
 * Diff rows that do not rely on hue alone: a `+` / `−` sign column, a solid gutter bar for additions and a hatched one
 * for deletions, old and new line numbers in ink3, context in ink2 and the changed words of paired lines marked. Without
 * [wrap] the whole diff scrolls horizontally as one, with edge fades into [fade]. Shared by markdown ```diff blocks and
 * the viewer; tool cards use [DiffBlock].
 */
@Composable
fun DiffLines(
    lines: List<DiffLine>,
    modifier: Modifier = Modifier,
    wrap: Boolean = false,
    fade: Color = Pi.colors.surface1,
    scroll: ScrollState = rememberScrollState(),
    language: String = "",
) {
    val gutter = remember(lines) { DiffGutter.of(lines) }
    val words = remember(lines) { UnifiedDiff.wordRanges(lines) }
    if (wrap) {
        Column(modifier.fillMaxWidth()) { lines.forEachIndexed { i, line -> DiffRow(line, gutter, words[i], wrap = true, language = language) } }
        return
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val min = maxWidth
        Column(
            Modifier
                .horizontalEdgeFades(scroll, fade)
                .horizontalScroll(scroll)
                .width(IntrinsicSize.Max)
                .widthIn(min = min),
        ) { lines.forEachIndexed { i, line -> DiffRow(line, gutter, words[i], wrap = false, language = language) } }
    }
}

/** "123 456": the gutter text of one row, each column right-aligned to its width. */
fun gutterText(line: DiffLine, gutter: DiffGutter): String = buildString {
    if (gutter.old > 0) {
        append(line.old.padStart(gutter.old))
        append(' ')
    }
    if (gutter.new > 0) append(line.number.padStart(gutter.new))
}

/**
 * One diff row; [word] is the changed range inside [DiffLine.text] for paired lines. With a [language] (from the
 * diff's file name) the row's code keeps its syntax colours over the add/delete background.
 */
@Composable
fun DiffRow(line: DiffLine, gutter: DiffGutter, word: IntRange?, wrap: Boolean, language: String = "") {
    val colors = Pi.colors
    val type = Pi.type
    val style = type.monoSmall
    when (line.kind) {
        DiffKind.Skip -> Text(
            if (line.number.isNotEmpty()) "···  ${line.number} unchanged lines" else "···",
            style = type.meta,
            color = colors.ink3,
            modifier = Modifier.padding(start = BAR + 14.dp, top = 4.dp, bottom = 4.dp),
        )
        DiffKind.Hunk -> Text(
            line.text,
            style = type.monoMeta,
            color = colors.ink3,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 24.dp)
                .background(colors.surface2)
                .padding(start = BAR + 14.dp, end = 12.dp, top = 3.dp, bottom = 3.dp),
        )
        else -> {
            val add = line.kind == DiffKind.Add
            val del = line.kind == DiffKind.Del
            val bg = if (add) colors.diffAddBg else if (del) colors.diffDelBg else Color.Transparent
            val fg = if (add) colors.diffAddFg else if (del) colors.diffDelFg else colors.ink2
            val text = remember(line, word, colors, language) {
                rowText(line.text, word, if (add) colors.diffAddWord else colors.diffDelWord, if (language.isEmpty()) null else colors, language)
            }
            val bar = if (add) colors.success.copy(alpha = 0.6f) else colors.danger.copy(alpha = 0.7f)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(bg)
                    .drawBehind { if (add) drawRect(bar, size = Size(BAR.toPx(), size.height)) else if (del) drawHatch(bar, BAR) },
            ) {
                if (gutter.any) {
                    Text(
                        gutterText(line, gutter),
                        style = style,
                        color = colors.ink3,
                        softWrap = false,
                        modifier = Modifier.padding(start = BAR + 8.dp, end = 4.dp),
                    )
                }
                Text(
                    if (add) "+" else if (del) "−" else "",
                    style = style.merge(TextStyle(textAlign = TextAlign.Center)),
                    color = if (add) colors.success else colors.danger,
                    modifier = Modifier
                        .padding(start = if (gutter.any) 0.dp else BAR + 4.dp)
                        .width(SIGN)
                        .align(Alignment.Top),
                )
                Text(
                    text,
                    style = style,
                    color = fg,
                    softWrap = wrap,
                    modifier = (if (wrap) Modifier.weight(1f) else Modifier).padding(start = 4.dp, end = 12.dp),
                )
            }
        }
    }
}

private fun rowText(text: String, word: IntRange?, wordBg: Color, syntax: PiColors?, language: String): AnnotatedString {
    val base = if (syntax != null) highlightedCached(text, language, syntax) else AnnotatedString(text)
    if (word == null || word.isEmpty() || word.last >= text.length) return base
    return buildAnnotatedString {
        append(base)
        addStyle(SpanStyle(background = wordBg), word.first, word.last + 1)
    }
}

/** A diff laid out as two texts (gutters, code) plus the row tints drawn behind them. See [DiffBlock]. */
@Immutable
class DiffText(val gutter: AnnotatedString, val code: AnnotatedString, val kinds: List<DiffKind>)

/**
 * [lines] as one gutter text and one code text: each row is a line of both, so a long diff lays out as two paragraphs
 * instead of a composable per row. Code keeps the syntax colours of [language]; changed words of paired lines get the
 * word tint.
 */
fun diffText(source: List<DiffLine>, language: String, colors: PiColors): DiffText {
    // Tabs as four spaces, before the word ranges are measured.
    val lines = source.map { if (it.text.indexOf('\t') >= 0) it.copy(text = it.text.replace("\t", "    ")) else it }
    val gutter = DiffGutter.of(lines)
    val words = UnifiedDiff.wordRanges(lines)
    val gutterOut = StringBuilder()
    val code = AnnotatedString.Builder()
    lines.forEachIndexed { i, line ->
        if (i > 0) {
            gutterOut.append('\n')
            code.append('\n')
        }
        when (line.kind) {
            DiffKind.Skip -> {
                val start = code.length
                code.append(if (line.number.isNotEmpty()) "⋯ ${line.number} unchanged lines" else "⋯")
                code.addStyle(SpanStyle(color = colors.ink3), start, code.length)
            }
            DiffKind.Hunk -> {
                val start = code.length
                code.append(line.text)
                code.addStyle(SpanStyle(color = colors.ink3), start, code.length)
            }
            else -> {
                gutterOut.append(gutterText(line, gutter))
                val add = line.kind == DiffKind.Add
                val del = line.kind == DiffKind.Del
                val signStart = code.length
                code.append(if (add) "+ " else if (del) "− " else "  ")
                if (add || del) code.addStyle(SpanStyle(color = if (add) colors.success else colors.danger), signStart, signStart + 1)
                val start = code.length
                val text = line.text
                code.append(if (language.isEmpty() || text.isEmpty()) AnnotatedString(text) else highlightedCached(text, language, colors))
                if (language.isEmpty()) code.addStyle(SpanStyle(color = if (add) colors.diffAddFg else if (del) colors.diffDelFg else colors.ink2), start, code.length)
                val word = words[i]
                if (word != null && !word.isEmpty() && word.last < text.length) {
                    code.addStyle(SpanStyle(background = if (add) colors.diffAddWord else colors.diffDelWord), start + word.first, start + word.last + 1)
                }
            }
        }
    }
    return DiffText(AnnotatedString(gutterOut.toString()), code.toAnnotatedString(), lines.map { it.kind })
}

/**
 * A tool card's diff: [DiffText] in two texts, the code scrolling sideways, with each added or deleted row tinted
 * across the full width and marked by a solid (added) or hatched (deleted) bar.
 */
@Composable
fun DiffBlock(diff: DiffText, modifier: Modifier = Modifier, scroll: ScrollState = rememberScrollState()) {
    val colors = Pi.colors
    val style = Pi.type.monoSmall
    val layout = remember { arrayOfNulls<TextLayoutResult>(1) }
    val addBar = colors.success.copy(alpha = 0.6f)
    val delBar = colors.danger.copy(alpha = 0.7f)
    val pad = 6.dp
    Row(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val l = layout[0] ?: return@drawBehind
                val top = pad.toPx()
                val n = minOf(l.lineCount, diff.kinds.size)
                for (i in 0 until n) {
                    val kind = diff.kinds[i]
                    if (kind != DiffKind.Add && kind != DiffKind.Del && kind != DiffKind.Hunk) continue
                    val y = top + l.getLineTop(i)
                    val h = l.getLineBottom(i) - l.getLineTop(i)
                    when (kind) {
                        DiffKind.Add -> {
                            drawRect(colors.diffAddBg, Offset(0f, y), Size(size.width, h))
                            drawRect(addBar, Offset(0f, y), Size(BAR.toPx(), h))
                        }
                        DiffKind.Del -> {
                            drawRect(colors.diffDelBg, Offset(0f, y), Size(size.width, h))
                            clipRect(0f, y, BAR.toPx(), y + h) { drawHatch(delBar, BAR, y, h) }
                        }
                        else -> drawRect(colors.surface2, Offset(0f, y), Size(size.width, h))
                    }
                }
            }
            .padding(vertical = pad),
    ) {
        if (diff.gutter.text.isNotBlank()) {
            Text(diff.gutter, style = style, color = colors.ink3, softWrap = false, modifier = Modifier.padding(start = BAR + 8.dp, end = 6.dp))
        }
        Box(Modifier.weight(1f).horizontalEdgeFades(scroll, colors.surface1).horizontalScroll(scroll)) {
            Text(
                diff.code,
                style = style,
                color = colors.ink2,
                softWrap = false,
                onTextLayout = { layout[0] = it },
                modifier = Modifier.padding(start = if (diff.gutter.text.isNotBlank()) 0.dp else BAR + 8.dp, end = 14.dp),
            )
        }
    }
}

/** A [width] gutter bar of diagonal stripes (2dp every 4dp): deletions differ from additions by more than hue. */
private fun DrawScope.drawHatch(color: Color, width: Dp, top: Float = 0f, height: Float = size.height) {
    val w = width.toPx()
    val step = 4.dp.toPx()
    val stroke = 2.dp.toPx()
    clipRect(0f, top, w, top + height) {
        var y = top - w
        while (y < top + height + w) {
            drawLine(color, Offset(0f, y + w), Offset(w, y), strokeWidth = stroke)
            y += step
        }
    }
}
