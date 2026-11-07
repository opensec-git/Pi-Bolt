package opensec.pibolt.app.ui.session

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.WrapText
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import opensec.pibolt.app.ui.components.GlassBar
import opensec.pibolt.app.ui.components.GlassCopyButton
import opensec.pibolt.app.ui.components.GlassIconButton
import opensec.pibolt.app.ui.components.DiffGutter
import opensec.pibolt.app.ui.components.DiffRow
import opensec.pibolt.app.ui.markdown.CodeFold
import opensec.pibolt.app.ui.markdown.CodeInfo
import opensec.pibolt.app.ui.markdown.UnifiedDiff
import opensec.pibolt.app.ui.markdown.highlightedCached
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius

private const val MAX_LINE = 2_000

/**
 * Full-screen output, diff or text viewer: lines are lazy (long output never lays out at once), wrap is a toggle,
 * copy takes the whole text. Code opened from a markdown block (its title is a language or file name, see
 * [CodeInfo.viewerLanguage]) keeps its syntax colours; a `diff` title renders diff rows. Predictive back previews the
 * close.
 */
@Composable
fun Viewer(content: ViewerContent, onClose: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val language = remember(content) { if (content.diff || content.selectable) null else CodeInfo.viewerLanguage(content.title) }
    val diffRows = remember(content, language) {
        when {
            content.diff -> parseDiff(content.text)
            language == "diff" -> UnifiedDiff.parse(content.text.trimEnd('\n'))
            else -> null
        }
    }
    val subtitle = remember(content, language, diffRows) {
        content.subtitle?.takeIf { it.isNotBlank() } ?: if (language != null && diffRows == null) linesLabel(content.text) else null
    }
    var wrap by rememberSaveable { mutableStateOf(content.wrap) }
    var progress by remember { mutableFloatStateOf(0f) }
    var fromLeft by remember { mutableStateOf(true) }

    PredictiveBackHandler { events ->
        try {
            events.collect { e ->
                progress = e.progress
                fromLeft = e.swipeEdge == BackEventCompat.EDGE_LEFT
            }
            onClose()
        } catch (e: CancellationException) {
            progress = 0f
            throw e
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val s = 1f - 0.1f * progress
                scaleX = s
                scaleY = s
                translationX = (if (fromLeft) 1 else -1) * 8.dp.toPx() * progress
                shape = RoundedCornerShape(Radius.xl * progress)
                clip = progress > 0f
            }
            .background(colors.canvas),
    ) {
        GlassBar(
            title = content.title,
            subtitle = subtitle,
            leading = { GlassIconButton(Icons.Rounded.Close, "Close", onClick = onClose) },
            actions = {
                if (!content.selectable) {
                    GlassIconButton(Icons.AutoMirrored.Rounded.WrapText, if (wrap) "Do not wrap lines" else "Wrap lines", onClick = { wrap = !wrap }, tint = if (wrap) colors.accent else colors.ink1)
                }
                GlassCopyButton({ content.text }, clipLabel = content.title, description = "Copy all")
            },
        )
        when {
            content.selectable -> SelectionContainer(Modifier.weight(1f)) {
                Text(
                    content.text,
                    style = type.prose,
                    color = colors.ink1,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                        .navigationBarsPadding(),
                )
            }
            diffRows != null -> {
                val gutter = remember(diffRows) { DiffGutter.of(diffRows) }
                val words = remember(diffRows) { UnifiedDiff.wordRanges(diffRows) }
                // A tool diff's subtitle is its path: the rows keep that file's syntax colours.
                val diffLanguage = remember(content) { content.subtitle?.let(CodeInfo::languageOfFile).orEmpty() }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
                    itemsIndexed(diffRows, contentType = { _, l -> l.kind }) { i, line -> DiffRow(line, gutter, words[i], wrap = true, language = diffLanguage) }
                    item { Spacer(Modifier.navigationBarsPadding()) }
                }
            }
            else -> TextLines(content.text, wrap, language, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TextLines(text: String, wrap: Boolean, language: String?, modifier: Modifier) {
    val colors = Pi.colors
    val style = Pi.type.monoSmall
    // Line ranges without trailing blank lines, so the last numbered line has content.
    val ranges = remember(text) { lineRanges(text) }
    val coloured = remember(text, language, colors) { language?.let { highlightedCached(text, it, colors) } }
    val numWidth = ranges.size.toString().length
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val charWidth = remember(style) { measurer.measure("0", style).size.width }
    val longest = remember(ranges) { ranges.maxOfOrNull { minOf(it.last - it.first + 1, MAX_LINE + 2) } ?: 0 }
    val contentWidth = with(density) { (charWidth * (longest + numWidth + 2)).toDp() + 48.dp }
    val list = @Composable { m: Modifier ->
        LazyColumn(m, contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp)) {
            itemsIndexed(ranges) { i, range ->
                val line = remember(range, coloured) { lineText(text, coloured, range) }
                Row(Modifier.padding(horizontal = 14.dp)) {
                    Text((i + 1).toString().padStart(numWidth), style = style, color = colors.ink3)
                    Spacer(Modifier.width(12.dp))
                    Text(line, style = style, color = colors.ink1, softWrap = wrap)
                }
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
    if (wrap) {
        list(modifier.fillMaxWidth())
    } else {
        Box(modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            list(Modifier.width(contentWidth).fillMaxSize())
        }
    }
}

/** `[start, end)` of each line as an IntRange (end exclusive as `last + 1`), trailing blank lines dropped. */
private fun lineRanges(text: String): List<IntRange> {
    val out = ArrayList<IntRange>()
    var start = 0
    while (true) {
        val nl = text.indexOf('\n', start)
        val end = if (nl < 0) text.length else nl
        out += start until end
        if (nl < 0) break
        start = nl + 1
    }
    while (out.size > 1 && text.substring(out.last().first, out.last().last + 1).isBlank()) out.removeAt(out.lastIndex)
    return out
}

private fun lineText(text: String, coloured: AnnotatedString?, range: IntRange): AnnotatedString {
    val end = range.last + 1
    val cut = minOf(end, range.first + MAX_LINE)
    var line = coloured?.subSequence(range.first, cut) ?: AnnotatedString(text.substring(range.first, cut).removeSuffix("\r"))
    if (cut < end) line += AnnotatedString(" …")
    return line
}

private fun linesLabel(text: String): String {
    val n = lineRanges(text).size
    return "${CodeFold.count(n)} ${if (n == 1) "line" else "lines"}"
}
