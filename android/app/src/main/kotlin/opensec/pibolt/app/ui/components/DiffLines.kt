package opensec.pibolt.app.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.markdown.UnifiedDiff
import opensec.pibolt.app.ui.markdown.highlighted
import opensec.pibolt.app.ui.session.DiffKind
import opensec.pibolt.app.ui.session.DiffLine
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiColors

private val BAR = 3.dp
private val SIGN = 14.dp

/**
 * Diff rows that do not rely on hue alone: a `+` / `−` sign column, a solid gutter bar for additions and a hatched one
 * for deletions, line numbers in ink3, context in ink2 and the changed words of paired lines marked. Without [wrap] the
 * whole diff scrolls horizontally as one, with edge fades into [fade]. Shared by markdown ```diff blocks and the
 * viewer; the edit tool card can use it too.
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
    val numWidth = remember(lines) { lines.maxOfOrNull { it.number.length } ?: 0 }
    val words = remember(lines) { UnifiedDiff.wordRanges(lines) }
    if (wrap) {
        Column(modifier.fillMaxWidth()) { lines.forEachIndexed { i, line -> DiffRow(line, numWidth, words[i], wrap = true, language = language) } }
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
        ) { lines.forEachIndexed { i, line -> DiffRow(line, numWidth, words[i], wrap = false, language = language) } }
    }
}

/**
 * One diff row; [word] is the changed range inside [DiffLine.text] for paired lines. With a [language] (from the
 * diff's file name) the row's code keeps its syntax colours over the add/delete background.
 */
@Composable
fun DiffRow(line: DiffLine, numWidth: Int, word: IntRange?, wrap: Boolean, language: String = "") {
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
                if (numWidth > 0) {
                    Text(
                        line.number.padStart(numWidth),
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
                        .padding(start = if (numWidth > 0) 0.dp else BAR + 4.dp)
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
    val base = if (syntax != null) highlighted(text, language, syntax) else AnnotatedString(text)
    if (word == null || word.isEmpty() || word.last >= text.length) return base
    return buildAnnotatedString {
        append(base)
        addStyle(SpanStyle(background = wordBg), word.first, word.last + 1)
    }
}

/** A [width] gutter bar of diagonal stripes (2dp every 4dp): deletions differ from additions by more than hue. */
private fun DrawScope.drawHatch(color: Color, width: Dp) {
    val w = width.toPx()
    val step = 4.dp.toPx()
    val stroke = 2.dp.toPx()
    clipRect(0f, 0f, w, size.height) {
        var y = -w
        while (y < size.height + w) {
            drawLine(color, Offset(0f, y + w), Offset(w, y), strokeWidth = stroke)
            y += step
        }
    }
}
