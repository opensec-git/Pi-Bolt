package opensec.pibolt.app.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
    var discardHunk by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(path, staged, model.version, entry) {
        val e = entry ?: run {
            diff = null
            loaded = true
            return@LaunchedEffect
        }
        diff = try {
            model.repo.diff(e, staged)
        } catch (ex: GitException) {
            ui.error(ex.message ?: "Could not read the diff")
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
            if (entry != null && !entry.conflicted) {
                if (staged) {
                    TextAction("Unstage", onClick = { model.run("Unstaging") { unstage(listOf(path), initial) } }, enabled = idle, modifier = Modifier.heightIn(min = 48.dp))
                } else {
                    TextAction("Stage", onClick = { model.run("Staging") { stage(listOf(path)) } }, enabled = idle, modifier = Modifier.heightIn(min = 48.dp))
                    IconAction(Icons.AutoMirrored.Rounded.Undo, "Discard changes", onClick = { ui.sheet = GitSheet.Discard(entry, staged = false) }, enabled = idle)
                }
            }
        }
        val d = diff
        when {
            !loaded -> Unit
            entry == null || d == null -> Note(if (entry == null) "No changes left in this file." else "Nothing to show for this file.")
            d.binary -> Note("Binary file: no text diff.")
            d.hunks.isEmpty() -> Note(if (d.oldPath != null) "Renamed from ${d.oldPath}, contents unchanged." else "Only the file mode changed.")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp + bottom)) {
                item(key = "summary") {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (staged) "Staged" else if (entry.untracked) "New file" else "Not staged", style = type.meta, color = colors.ink3)
                        Spacer(Modifier.width(10.dp))
                        Counts(d.added, d.removed)
                    }
                }
                var rows = 0
                val shown = d.hunks.takeWhile { h -> (rows < MAX_ROWS).also { rows += h.lines.size } }
                itemsIndexed(shown, key = { i, h -> "$i:${h.header}" }) { i, hunk ->
                    HunkCard(
                        hunk = hunk,
                        language = language,
                        action = when {
                            entry.untracked || entry.conflicted || d.hunks.size < 1 -> null
                            staged -> "Unstage hunk" to { model.run("Unstaging") { unstageHunk(d, i) } }
                            else -> "Stage hunk" to { model.run("Staging") { stageHunk(d, i) } }
                        },
                        onDiscard = if (!staged && !entry.untracked && !entry.conflicted) ({ discardHunk = i }) else null,
                        enabled = idle,
                    )
                }
                if (shown.size < d.hunks.size) {
                    item(key = "more") { Note("${d.hunks.size - shown.size} more hunks are not shown: this diff is very large.") }
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
    GitCard(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().background(colors.surface2).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(hunk.header, style = Pi.type.monoMeta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (onDiscard != null) IconAction(Icons.AutoMirrored.Rounded.Undo, "Discard hunk", onClick = onDiscard, enabled = enabled, size = 18.dp)
            if (action != null) TextAction(action.first, onClick = action.second, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp))
        }
        Box(Modifier.fillMaxWidth()) { DiffBlock(text) }
    }
}

@Composable
internal fun Note(text: String) {
    Text(text, style = Pi.type.body, color = Pi.colors.ink3, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
}
