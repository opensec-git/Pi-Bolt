package opensec.pibolt.app.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.foundation.layout.height
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import opensec.pibolt.app.ui.components.GlassChip
import opensec.pibolt.app.ui.components.GlassIconButton
import opensec.pibolt.app.ui.components.GlassPill
import opensec.pibolt.app.ui.components.LineCounts
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Space
import opensec.pibolt.app.ui.components.ErrorState
import opensec.pibolt.app.ui.components.LoadingRow
import opensec.pibolt.app.ui.projects.EmptyCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.git.FileDiff
import opensec.pibolt.app.git.GitException
import opensec.pibolt.app.git.Hunk
import opensec.pibolt.app.ui.components.ConfirmSheet
import opensec.pibolt.app.ui.components.DiffBlock
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.ui.components.diffText
import opensec.pibolt.app.ui.markdown.CodeInfo
import opensec.pibolt.app.ui.theme.Pi

/**
 * One file's diff, staged or not, hunk by hunk in the rows renderer (two gutters, syntax colours, word marks). Each
 * hunk stages or unstages on its own; unstaged hunks can be discarded after a confirmation. The file-level action in
 * the bar stages or unstages the whole file.
 */
@Composable
fun DiffViewer(ui: ChangesUi, path: String, staged: Boolean, onClose: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val model = ui.model
    val entry = model.status?.entries?.firstOrNull { it.path == path }
    var diff by remember(path, staged) { mutableStateOf<FileDiff?>(null) }
    var loaded by remember(path, staged) { mutableStateOf(false) }
    // Git failed to read the diff (as opposed to this side having no changes left).
    var readFailed by remember(path, staged) { mutableStateOf(false) }
    var discardHunk by remember { mutableStateOf<Int?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(path, staged, model.version, entry, attempt) {
        val e = entry ?: run {
            diff = null
            loaded = true
            return@LaunchedEffect
        }
        readFailed = false
        diff = try {
            model.source.diff(e, staged)
        } catch (ex: GitException) {
            ui.error(ex.message ?: "Could not read the diff")
            readFailed = true
            null
        }
        loaded = true
    }
    val language = remember(path) { CodeInfo.languageOfFile(path).orEmpty() }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val initial = model.status?.branch?.initial == true
    val idle = model.busy == null

    Column(Modifier.fillMaxSize().background(colors.canvas)) {
        OverlayBar(splitPath(path).first, path, onBack = onClose) {
            if (entry != null && !entry.conflicted && !model.readOnly) {
                if (staged) {
                    GlassChip("Unstage", onClick = { model.run("Unstaging") { unstage(listOf(path), initial) } }, icon = Icons.Rounded.Remove, enabled = idle)
                } else {
                    GlassIconButton(Icons.AutoMirrored.Rounded.Undo, "Discard changes", onClick = { ui.sheet = GitSheet.Discard(entry, staged = false) }, enabled = idle)
                    GlassChip("Stage", onClick = { model.run("Staging") { stage(listOf(path)) } }, icon = Icons.Rounded.Add, enabled = idle)
                }
            }
        }
        val d = diff
        when {
            !loaded -> LoadingRow("Loading diff…", Modifier.padding(top = Space.sm))
            entry == null -> EmptyCard(Icons.Rounded.Check, "No changes left")
            // Every hunk staged (or unstaged) from here: the file's changes are now all on the other side.
            d == null && !readFailed -> EmptyCard(Icons.Rounded.Check, if (staged) "Nothing staged in this file" else "No unstaged changes left")
            d == null -> ErrorState("Could not read the diff", Modifier.padding(horizontal = Space.page, vertical = Space.sm), onRetry = {
                loaded = false
                attempt++
            })
            d.binary -> EmptyCard(Icons.AutoMirrored.Rounded.InsertDriveFile, "Binary file", "No text diff")
            d.hunks.isEmpty() -> EmptyCard(Icons.AutoMirrored.Rounded.InsertDriveFile, if (d.oldPath != null) "Renamed" else "Mode change", d.oldPath?.let { "From $it, contents unchanged" })
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp + bottom)) {
                item(key = "summary") {
                    // Where the file stands, and its "+N −M" on a glass pill, at the gutter.
                    Row(Modifier.fillMaxWidth().padding(start = Space.page, end = Space.page, bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (staged) "Staged" else if (entry.untracked) "New file" else "Not staged", style = type.rowMeta, color = colors.ink3, modifier = Modifier.weight(1f))
                        if (d.added > 0 || d.removed > 0) {
                            GlassPill(Modifier.semantics(mergeDescendants = true) { contentDescription = ChangesRules.countsSpoken(d.added, d.removed) }) { LineCounts(d.added, d.removed) }
                        }
                    }
                }
                var rows = 0
                val shown = d.hunks.takeWhile { h -> (rows < MAX_ROWS).also { rows += h.lines.size } }
                itemsIndexed(shown, key = { i, h -> "$i:${h.header}" }) { i, hunk ->
                    HunkCard(
                        hunk = hunk,
                        language = language,
                        action = when {
                            model.readOnly || entry.untracked || entry.conflicted || d.hunks.size < 1 -> null
                            staged -> "Unstage hunk" to { model.run("Unstaging") { unstageHunk(d, i) } }
                            else -> "Stage hunk" to { model.run("Staging") { stageHunk(d, i) } }
                        },
                        onDiscard = if (!model.readOnly && !staged && !entry.untracked && !entry.conflicted) ({ discardHunk = i }) else null,
                        enabled = idle,
                    )
                }
                if (shown.size < d.hunks.size) {
                    item(key = "more") { Note("${d.hunks.size - shown.size} more hunks not shown") }
                }
            }
        }
    }
    discardHunk?.let { i ->
        val d = diff
        ConfirmSheet(
            title = "Discard this hunk?",
            message = "These lines go back to their staged version. This can't be undone.",
            confirmLabel = "Discard",
            onConfirm = {
                discardHunk = null
                if (d != null && i < d.hunks.size) model.run("Discarding") { discardHunk(d, i) }
            },
            onDismiss = { discardHunk = null },
        )
    }
}

@Composable
private fun HunkCard(hunk: Hunk, language: String, action: Pair<String, () -> Unit>?, onDiscard: (() -> Unit)?, enabled: Boolean) {
    val colors = Pi.colors
    val rows = remember(hunk) { hunkRows(hunk) }
    val text = remember(rows, language, colors) { diffText(rows, language, colors) }
    GitCard(Modifier.padding(vertical = Space.xs)) {
        // The `@@` line as the card's header (monoMeta ink3 on surface2), with the hunk's own actions at its end.
        Row(Modifier.fillMaxWidth().background(colors.surface2).padding(start = Space.lg, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Text(hunk.header, style = Pi.type.monoMeta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (onDiscard != null) IconAction(Icons.AutoMirrored.Rounded.Undo, "Discard hunk", onClick = onDiscard, enabled = enabled, size = IconSize.md)
            if (action != null) TextAction(action.first, onClick = action.second, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
        Box(Modifier.fillMaxWidth()) { DiffBlock(text) }
    }
}

@Composable
internal fun Note(text: String) {
    Text(text, style = Pi.type.rowMeta, color = Pi.colors.ink3, modifier = Modifier.padding(horizontal = Space.page, vertical = Space.lg))
}
