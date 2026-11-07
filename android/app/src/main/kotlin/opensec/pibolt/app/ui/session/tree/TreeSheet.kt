package opensec.pibolt.app.ui.session.tree

import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.automirrored.rounded.Label
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.Segmented
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.session.ActionsSheet
import opensec.pibolt.app.ui.session.SheetAction
import opensec.pibolt.app.ui.session.TextPromptSheet
import opensec.pibolt.app.ui.session.features2.F2Empty
import opensec.pibolt.app.ui.session.features2.F2Loading
import opensec.pibolt.app.ui.session.features2.F2Tag
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius

/**
 * The session tree (the terminal's `/tree`): every branch of this session, the active one marked, forks indented.
 * Tap an entry to continue from there (a prompt comes back to the composer, as in the terminal); long-press or the
 * bookmark to label it. [data] is `get_entries` data, null while loading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TreeSheet(
    data: JsonObject?,
    error: String?,
    busy: Boolean,
    onRetry: () -> Unit,
    onGoto: (TreeRow) -> Unit,
    onLabel: (TreeRow, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var filter by remember { mutableStateOf(TreeFilter.Default) }
    val rows = remember(data, filter) { SessionTree.rows(data, filter) }
    var actionsFor by remember { mutableStateOf<TreeRow?>(null) }
    var labelFor by remember { mutableStateOf<TreeRow?>(null) }
    val colors = Pi.colors
    PiSheet(onDismiss = onDismiss, state = state) {
        val forks = rows.count { it.branches > 1 }
        SheetHeader(
            "Session tree",
            subtitle = when {
                error != null || data == null -> null
                forks == 0 -> null
                else -> "$forks ${if (forks == 1) "fork" else "forks"} · Pi sees the marked path"
            },
        )
        if (error != null) {
            TreeError(error, onRetry)
            return@PiSheet
        }
        Segmented(
            options = TreeFilter.entries.map { it to it.label },
            selected = filter,
            onSelect = { filter = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            height = 40.dp,
        )
        when {
            data == null -> F2Loading("Loading tree")
            rows.isEmpty() -> F2Empty(if (filter == TreeFilter.Labeled) "No labels yet. Long-press an entry to label it." else "Nothing here yet", if (filter == TreeFilter.Labeled) Icons.AutoMirrored.Rounded.Label else Icons.Rounded.AccountTree)
            else -> {
                val list = rememberLazyListState()
                // Open at the leaf: where the reader is.
                LaunchedEffect(rows) {
                    val at = rows.indexOfFirst { it.leaf }
                    if (at > 2) list.scrollToItem(at - 2)
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), state = list) {
                    items(rows, key = { it.id }) { row ->
                        TreeRowView(row, enabled = !busy, onClick = { actionsFor = row }, onLongClick = { labelFor = row })
                    }
                }
            }
        }
        if (busy) Text("Wait for Pi to finish to switch branches", style = Pi.type.meta, color = colors.warning, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
    }
    actionsFor?.let { row ->
        ActionsSheet(
            title = row.text.ifBlank { row.kind.name },
            actions = listOfNotNull(
                SheetAction(if (row.kind == TreeKind.User) "Continue from before this prompt" else "Continue from here", Icons.Rounded.NearMe) { onGoto(row) }.takeIf { !row.leaf },
                SheetAction(if (row.label == null) "Add a label" else "Change the label", Icons.Rounded.BookmarkBorder) { labelFor = row },
                row.label?.let { SheetAction("Remove the label", Icons.Rounded.Bookmark) { onLabel(row, "") } },
            ),
            onDismiss = { actionsFor = null },
        )
    }
    labelFor?.let { row ->
        TextPromptSheet(
            title = "Label this entry",
            message = null,
            initial = row.label.orEmpty(),
            placeholder = "checkpoint",
            confirm = "Save",
            onConfirm = {
                onLabel(row, it.trim())
                labelFor = null
            },
            onDismiss = { labelFor = null },
        )
    }
}

@Composable
private fun TreeRowView(row: TreeRow, enabled: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val indent = (row.depth.coerceAtMost(8) * 14).dp
    val described = buildString {
        append(row.kind.name).append(": ").append(row.text)
        if (row.label != null) append(". Label ").append(row.label)
        if (row.branches > 1) append(". ").append(row.branches).append(" branches")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .pressable(onClick, shape = RoundedCornerShape(0.dp), pressedScale = 1f, enabled = enabled, onLongClick = onLongClick, label = "Branch actions")
            .background(if (row.leaf) colors.selected else colors.surface2)
            .semantics(mergeDescendants = true) {
                contentDescription = described
                stateDescription = when {
                    row.leaf -> "Current position"
                    row.active -> "On the active branch"
                    else -> "Other branch"
                }
            }
            .padding(start = 16.dp + indent, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The rail: filled on the active path, hollow elsewhere.
        Box(Modifier.size(10.dp).background(if (row.active) colors.accent else colors.surface3, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (row.kind) {
                        TreeKind.User -> "You"
                        TreeKind.Assistant -> "Pi"
                        TreeKind.Tool -> "Tool"
                        TreeKind.Compaction -> "Compaction"
                        TreeKind.BranchSummary -> "Branch summary"
                        TreeKind.Model -> "Setting"
                        TreeKind.Other -> "Entry"
                    },
                    style = type.metaStrong,
                    color = if (row.kind == TreeKind.User) colors.ink1 else colors.ink3,
                )
                if (row.label != null) {
                    Spacer(Modifier.width(6.dp))
                    F2Tag(row.label, color = colors.onAccent, fill = colors.accent)
                }
                if (row.leaf) {
                    Spacer(Modifier.width(6.dp))
                    F2Tag("here")
                }
            }
            Text(
                row.text.ifBlank { "(empty)" },
                style = type.body,
                color = if (row.active) colors.ink1 else colors.ink2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.branches > 1) {
            Spacer(Modifier.width(8.dp))
            Row(
                Modifier.background(colors.surface3, RoundedCornerShape(Radius.xs)).padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Rounded.CallSplit, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(3.dp))
                Text("${row.branches}", style = type.micro, color = colors.ink2)
            }
        }
    }
}

/**
 * The tree could not be read: said as an error (danger glyph, a plain title, Pi's own words in meta ink3) with Retry,
 * never as an empty list, which would read as "this session has no history".
 */
@Composable
private fun TreeError(message: String, onRetry: () -> Unit) {
    val colors = Pi.colors
    val shape = RoundedCornerShape(Radius.md)
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .background(colors.dangerSoft, shape)
            .border(1.dp, colors.danger.copy(alpha = 0.4f), shape)
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = colors.danger, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("Could not read the session tree", style = Pi.type.bodyStrong, color = colors.ink1, modifier = Modifier.weight(1f).semantics { heading() })
        }
        Text(message, style = Pi.type.meta, color = colors.ink3, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 28.dp, top = 6.dp, end = 8.dp))
        Box(Modifier.padding(start = 16.dp)) { TextAction("Retry", onClick = onRetry, color = colors.accent) }
    }
}
