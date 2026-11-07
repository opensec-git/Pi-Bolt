package opensec.pibolt.app.ui.session.features

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.components.glass
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius

/**
 * The suggestion panel above the composer pill: glass (as the pill), radius tile, at most [maxHeight] tall, scrolls. The row
 * at [highlighted] (chosen with Up / Down on a hardware keyboard) is kept in view.
 */
@Composable
private fun SuggestionPanel(maxHeight: Dp, highlighted: Int, modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    val colors = Pi.colors
    val shape = RoundedCornerShape(Radius.tile)
    val state = rememberLazyListState()
    LaunchedEffect(highlighted) {
        if (highlighted < 0) return@LaunchedEffect
        val visible = state.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: return@LaunchedEffect
        val last = visible.lastOrNull { it.offset + it.size <= state.layoutInfo.viewportEndOffset }?.index ?: first
        when {
            highlighted < first -> state.scrollToItem(highlighted)
            highlighted > last -> state.scrollToItem((highlighted - (last - first)).coerceAtLeast(0))
        }
    }
    LazyColumn(
        modifier
            .padding(bottom = 8.dp)
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .clip(shape)
            .glass(shape, colors)
            .padding(vertical = 4.dp),
        state = state,
        content = content,
    )
}

/** A suggestion row's fill: the keyboard's current row is lifted like a selected row. */
@Composable
private fun Modifier.highlight(on: Boolean): Modifier = if (on) background(Pi.colors.selected) else this

/**
 * Slash commands for what is typed: 48dp rows with the command in mono, its argument hint, its description and where
 * it comes from (app, extension, prompt, skill).
 */
@Composable
fun CommandList(entries: List<PaletteEntry>, onPick: (PaletteEntry) -> Unit, modifier: Modifier = Modifier, highlighted: Int = -1, maxHeight: Dp = 264.dp) {
    val colors = Pi.colors
    val type = Pi.type
    SuggestionPanel(maxHeight, highlighted, modifier) {
        itemsIndexed(entries, key = { _, it -> it.source.name + "/" + it.name }) { index, entry ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .highlight(index == highlighted)
                    .pressable({ onPick(entry) }, shape = RoundedCornerShape(Radius.sm), pressedScale = 1f, label = "${entry.label}, ${entry.source.label}")
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.label, style = type.mono, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
                        entry.hint?.let {
                            Spacer(Modifier.width(6.dp))
                            Text(it, style = type.monoMeta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (entry.description.isNotBlank()) {
                        Text(entry.description, style = type.rowMeta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.width(8.dp))
                SourceBadge(entry.source)
            }
        }
    }
}

@Composable
private fun SourceBadge(source: CommandSource) {
    val colors = Pi.colors
    Text(
        source.label,
        style = Pi.type.micro,
        color = if (source == CommandSource.App) colors.ink2 else colors.ink3,
        modifier = Modifier
            .background(colors.surface3, RoundedCornerShape(Radius.xs))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** `@` suggestions: the project's files and folders matching what follows the `@`. */
@Composable
fun MentionList(paths: List<String>?, query: String, onPick: (String) -> Unit, modifier: Modifier = Modifier, highlighted: Int = -1, maxHeight: Dp = 264.dp) {
    val colors = Pi.colors
    val type = Pi.type
    SuggestionPanel(maxHeight, highlighted, modifier) {
        if (paths == null) {
            item { Text("Loading files…", style = type.meta, color = colors.ink3, modifier = Modifier.padding(14.dp)) }
            return@SuggestionPanel
        }
        if (paths.isEmpty()) {
            item { Text(if (query.isEmpty()) "No files in this project" else "No file matches “$query”", style = type.meta, color = colors.ink3, modifier = Modifier.padding(14.dp)) }
            return@SuggestionPanel
        }
        itemsIndexed(paths, key = { _, it -> it }) { index, path ->
            val dir = path.endsWith("/")
            val name = path.trimEnd('/').substringAfterLast('/')
            val parent = path.trimEnd('/').substringBeforeLast('/', "")
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .highlight(index == highlighted)
                    .pressable({ onPick(path) }, shape = RoundedCornerShape(Radius.sm), pressedScale = 1f)
                    .semantics { contentDescription = if (dir) "Folder $path" else "File $path" }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    Icon(if (dir) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile, contentDescription = null, tint = if (dir) colors.ink2 else colors.ink3, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(name + if (dir) "/" else "", style = type.mono, color = colors.ink1, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.widthIn(max = 180.dp))
                if (parent.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(parent, style = type.monoMeta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.StartEllipsis, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** One quiet line above the composer: a command's usage, or what shell mode does. */
@Composable
fun ComposerHint(text: String, mono: Boolean, modifier: Modifier = Modifier) {
    Text(
        text,
        style = if (mono) Pi.type.monoMeta else Pi.type.meta,
        color = Pi.colors.ink3,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
    )
}
