package opensec.pibolt.app.ui.session

import android.content.ClipData
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius

private const val MAX_LINE = 2_000

/**
 * Full-screen output, diff or text viewer: lines are lazy (long output never lays out at once), wrap is a toggle,
 * copy takes the whole text. Predictive back previews the close.
 */
@Composable
fun Viewer(content: ViewerContent, onClose: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val haptics = Pi.haptics
    var wrap by rememberSaveable { mutableStateOf(content.diff) }
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
            .background(colors.canvas)
            .statusBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.Rounded.Close, "Close", onClick = onClose, tint = colors.ink1)
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(content.title, style = type.subhead, color = colors.ink1, maxLines = 1)
                content.subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = type.monoMeta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                }
            }
            if (!content.selectable) {
                IconAction(Icons.AutoMirrored.Rounded.WrapText, if (wrap) "Do not wrap lines" else "Wrap lines", onClick = { wrap = !wrap }, tint = if (wrap) colors.accent else colors.ink2)
            }
            IconAction(Icons.Rounded.ContentCopy, "Copy all", onClick = {
                haptics.confirm()
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(content.title, content.text))) }
            })
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
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
            content.diff -> {
                val lines = remember(content.text) { parseDiff(content.text) }
                val numWidth = remember(lines) { lines.maxOfOrNull { it.number.length }?.coerceAtLeast(1) ?: 1 }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
                    itemsIndexed(lines, contentType = { _, l -> l.kind }) { _, line -> DiffLineRow(line, numWidth, wrap = true) }
                    item { Spacer(Modifier.navigationBarsPadding()) }
                }
            }
            else -> TextLines(content.text, wrap, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TextLines(text: String, wrap: Boolean, modifier: Modifier) {
    val colors = Pi.colors
    val style = Pi.type.monoSmall
    val lines = remember(text) { text.trimEnd('\n').lines().map { if (it.length > MAX_LINE) it.take(MAX_LINE) + " …" else it } }
    val numWidth = lines.size.toString().length
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val charWidth = remember(style) { measurer.measure("0", style).size.width }
    val longest = remember(lines) { lines.maxOfOrNull { it.length } ?: 0 }
    val contentWidth = with(density) { (charWidth * (longest + numWidth + 2)).toDp() + 48.dp }
    val list = @Composable { m: Modifier ->
        LazyColumn(m, contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp)) {
            itemsIndexed(lines) { i, line ->
                Row(Modifier.padding(horizontal = 14.dp)) {
                    Text((i + 1).toString().padStart(numWidth), style = style, color = colors.ink4)
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
