package opensec.pibolt.app.ui.session.review

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import opensec.pibolt.app.checkpoints.ChangeKind
import opensec.pibolt.app.checkpoints.FileDelta
import opensec.pibolt.app.checkpoints.RestoreItem
import opensec.pibolt.app.checkpoints.RestoreKind
import opensec.pibolt.app.checkpoints.RestorePlan
import opensec.pibolt.app.checkpoints.TurnRecord
import opensec.pibolt.app.ui.components.DiffGutter
import opensec.pibolt.app.ui.components.DiffRow
import opensec.pibolt.app.ui.components.GlassBar
import opensec.pibolt.app.ui.components.GlassIconButton
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.Segmented
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.markdown.CodeInfo
import opensec.pibolt.app.ui.markdown.UnifiedDiff
import opensec.pibolt.app.ui.session.Chevron
import opensec.pibolt.app.ui.session.DiffKind
import opensec.pibolt.app.ui.session.DiffLine
import opensec.pibolt.app.ui.session.baseName
import opensec.pibolt.app.ui.session.parseDiff
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Tnum

/** A file's diff as the screen shows it: rows, their gutter and changed words, and whether rows were left out. */
private class FileDiff(val lines: List<DiffLine>, val gutter: DiffGutter, val words: Array<IntRange?>, val hidden: Int, val binary: Boolean)

/** Files shown open when the screen opens (more start closed). */
private const val OPEN_FILES = 4

/** Rows of one file's diff shown at most. */
private const val MAX_ROWS = 1500

/**
 * What a turn changed, from the snapshot when its message arrived to the one when the agent settled: per file the
 * diff (the rows renderer of tool diffs), "Undo" per file, and for the whole turn "Undo this turn" and "Restore to
 * before this turn" (which also forks the conversation), plus "Redo" after an undo. Undo stays off while Pi works.
 */
@Composable
fun TurnReviewScreen(review: TurnReview, turnId: String, onClose: () -> Unit) {
    val checkpoints = review.checkpoints ?: return
    val colors = Pi.colors
    val turns by checkpoints.turns.collectAsState()
    val record = turns.firstOrNull { it.id == turnId }
    LaunchedEffect(record == null) { if (record == null) onClose() }
    record ?: return
    var changes by remember(record.id) { mutableStateOf<List<FileDelta>?>(null) }
    var failure by remember(record.id) { mutableStateOf<String?>(null) }
    val diffs = remember(record.id) { mutableStateMapOf<String, FileDiff>() }
    val open = remember(record.id) { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(record.id, record.end) {
        runCatching { checkpoints.changes(record) }
            .onSuccess { changes = it }
            .onFailure { failure = it.message ?: "Could not read the changes" }
    }
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
    val locked = review.busy || review.running
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
            title = "Changes",
            subtitle = summary(record),
            leading = { GlassIconButton(Icons.Rounded.Close, "Close", onClick = onClose) },
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (record.prompt.isNotBlank() || record.lastUndo != null) item(key = "head") { TurnHeader(review, record, locked) }
            val list = changes
            when {
                failure != null -> item(key = "failure") { Note(failure.orEmpty()) }
                list == null -> item(key = "loading") { Note("Loading changes…") }
                list.isEmpty() -> item(key = "none") { Note("No file changes in this turn") }
                else -> list.forEachIndexed { index, delta ->
                    val expanded = open[delta.path] ?: (index < OPEN_FILES)
                    item(key = "f:" + delta.path, contentType = "file") {
                        FileHeader(delta, expanded, locked, onToggle = { open[delta.path] = !expanded }, onUndo = { review.undo(record, listOf(delta.path)) })
                    }
                    if (expanded) {
                        val diff = diffs[delta.path]
                        if (diff == null) {
                            item(key = "l:" + delta.path, contentType = "note") {
                                LaunchedEffect(delta.path) {
                                    diffs[delta.path] = runCatching { load(checkpoints.patch(record, delta.path), delta.binary) }.getOrElse { load("", true) }
                                }
                                Note("Loading diff…")
                            }
                        } else {
                            fileDiff(delta.path, diff)
                        }
                    }
                }
            }
            item(key = "end") { Spacer(Modifier.height(8.dp)) }
        }
        ActionBar(review, record, locked)
    }
}

/**
 * The turn's actions at the bottom, in thumb reach: a 1dp line, then "Restore to before" (secondary) and "Undo this
 * turn" (primary: it can be redone). While Pi works both are off and a meta line says why.
 */
@Composable
private fun ActionBar(review: TurnReview, record: TurnRecord, locked: Boolean) {
    val colors = Pi.colors
    Column(Modifier.fillMaxWidth().background(colors.canvas).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
        if (review.running) {
            Text(
                "Undo is available when Pi finishes",
                style = Pi.type.meta,
                color = colors.ink3,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Restore to before", onClick = { review.restoreBefore(record) }, modifier = Modifier.weight(1f), icon = Icons.Rounded.History, enabled = !locked)
            PrimaryButton("Undo this turn", onClick = { review.undo(record) }, modifier = Modifier.weight(1f), icon = Icons.AutoMirrored.Rounded.Undo, enabled = !locked)
        }
    }
}

@Composable
private fun TurnHeader(review: TurnReview, record: TurnRecord, locked: Boolean) {
    val colors = Pi.colors
    val type = Pi.type
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        if (record.prompt.isNotBlank()) {
            Text(record.prompt, style = type.body, color = colors.ink2, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        record.lastUndo?.let { undo ->
            val n = undo.paths.size
            val shape = RoundedCornerShape(Radius.md)
            if (record.prompt.isNotBlank()) Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().background(colors.surface1, shape).border(1.dp, colors.line, shape).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Undid ${if (n == 1) "1 file" else "$n files"} of this turn.",
                    style = type.body,
                    color = colors.ink2,
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                )
                TextAction("Redo", onClick = { review.redo(record, undo) }, color = colors.accent, enabled = !locked)
            }
        }
    }
}

@Composable
private fun FileHeader(delta: FileDelta, expanded: Boolean, locked: Boolean, onToggle: () -> Unit, onUndo: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val name = baseName(delta.path)
    val dir = delta.path.removeSuffix(name).trimEnd('/')
    val num = type.meta.copy(fontFeatureSettings = Tnum)
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .pressable(onToggle, shape = RoundedCornerShape(0.dp), pressedScale = 1f, label = if (expanded) "Hide diff" else "Show diff")
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chevron(expanded)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = fileDescription(delta) }) {
                Text(name, style = type.monoMeta, color = colors.ink1, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                if (dir.isNotEmpty()) Text(dir, style = type.meta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            }
            when (delta.kind) {
                ChangeKind.Added -> Badge("new", colors.success)
                ChangeKind.Deleted -> Badge("deleted", colors.danger)
                ChangeKind.Modified -> Unit
            }
            if (delta.binary) {
                Text("binary", style = type.meta, color = colors.ink3)
            } else {
                if (delta.added > 0) Text("+${delta.added}", style = num, color = colors.success)
                if (delta.removed > 0) Text(" −${delta.removed}", style = num, color = colors.danger)
            }
            IconAction(Icons.AutoMirrored.Rounded.Undo, "Undo changes to $name", onClick = onUndo, enabled = !locked, size = 20.dp)
        }
    }
}

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, style = Pi.type.meta, color = color, modifier = Modifier.padding(end = 8.dp))
}

/** A file's diff rows, one lazy item each (a long diff never lays out at once), on surface1. */
private fun LazyListScope.fileDiff(path: String, diff: FileDiff) {
    when {
        diff.binary -> item(key = "b:$path", contentType = "note") { DiffNote("A binary file: no line diff.") }
        diff.lines.isEmpty() -> item(key = "e:$path", contentType = "note") { DiffNote("Only the file's mode changed.") }
        else -> items(diff.lines.size, key = { "d:$path:$it" }, contentType = { "row" }) { i ->
            val language = remember(path) { CodeInfo.languageOfFile(path).orEmpty() }
            Box(Modifier.fillMaxWidth().background(Pi.colors.surface1).padding(top = if (i == 0) 6.dp else 0.dp, bottom = if (i == diff.lines.lastIndex) 6.dp else 0.dp)) {
                DiffRow(diff.lines[i], diff.gutter, diff.words[i], wrap = true, language = language)
            }
        }
    }
    if (diff.hidden > 0) item(key = "h:$path", contentType = "note") { DiffNote("${diff.hidden} more rows not shown.") }
}

@Composable
private fun DiffNote(text: String) {
    Box(Modifier.fillMaxWidth().background(Pi.colors.surface1)) { Note(text) }
}

@Composable
private fun Note(text: String) {
    Text(text, style = Pi.type.meta, color = Pi.colors.ink3, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))
}

/**
 * The sheet of a restore that needs an answer: for files changed since (a later turn or the user), keep them or
 * restore them anyway; "Restore to before this turn" always asks, since it also forks the conversation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreSheet(review: TurnReview, plan: RestorePlan) {
    val colors = Pi.colors
    val type = Pi.type
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    val choices = remember(plan) { mutableStateMapOf<String, Boolean>().apply { for (item in plan.items) put(item.path, !item.conflict) } }
    val conflicts = plan.conflicts
    val safe = plan.items.size - conflicts.size
    val chosen = plan.items.count { choices[it.path] == true }
    val restoreBefore = plan.kind == RestoreKind.RestoreBefore
    val title = if (restoreBefore) "Restore to before this turn" else "Changed since"
    val subtitle = when {
        restoreBefore && plan.items.isEmpty() -> "No files differ. The conversation forks here and the message returns to the composer."
        restoreBefore -> "Files go back to how they were when this message was sent, including later turns' changes. The conversation forks here and the message returns to the composer."
        plan.kind == RestoreKind.Redo -> "These files changed after the undo. Put them back anyway, or keep them as they are."
        else -> "These files changed after this turn. Undo them anyway, or keep them as they are."
    }
    PiSheet(onDismiss = { review.dismiss(plan) }, state = state) {
        SheetHeader(title, subtitle = subtitle)
        Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            for (item in conflicts) {
                ConflictRow(item, choices[item.path] == true, plan.kind) { choices[item.path] = it }
            }
            if (restoreBefore && conflicts.isEmpty()) {
                for (item in plan.items.take(8)) PlainRow(item)
                if (plan.items.size > 8) Text("and ${plan.items.size - 8} more", style = type.meta, color = colors.ink3, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
        }
        if (conflicts.isNotEmpty() && safe > 0) {
            Text(
                if (safe == 1) "1 other file goes back as it is." else "$safe other files go back as they are.",
                style = type.meta,
                color = colors.ink3,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("Cancel", onClick = { close { review.dismiss(plan) } }, modifier = Modifier.weight(1f))
            val files = if (chosen == 1) "1 file" else "$chosen files"
            val label = when {
                restoreBefore -> if (chosen == 0) "Fork here" else "Restore and fork"
                plan.kind == RestoreKind.Redo -> "Put back $files"
                else -> "Undo $files"
            }
            PrimaryButton(
                label,
                onClick = { close { review.confirm(plan, plan.items.filter { choices[it.path] == true }.map { it.path }) } },
                modifier = Modifier.weight(1f),
                icon = if (plan.kind == RestoreKind.Redo) Icons.AutoMirrored.Rounded.Redo else null,
                enabled = restoreBefore || chosen > 0,
            )
        }
    }
}

@Composable
private fun ConflictRow(item: RestoreItem, restore: Boolean, kind: RestoreKind, onChoose: (Boolean) -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(baseName(item.path), style = type.monoMeta, color = colors.ink1, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(actionText(item.action), style = type.meta, color = colors.warning, maxLines = 1)
        }
        Segmented(
            options = listOf(false to "Keep", true to if (kind == RestoreKind.Redo) "Put back" else "Restore"),
            selected = restore,
            onSelect = onChoose,
            modifier = Modifier.width(176.dp),
            height = 48.dp,
        )
    }
}

@Composable
private fun PlainRow(item: RestoreItem) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(item.path, style = Pi.type.monoMeta, color = Pi.colors.ink1, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
        Text(actionText(item.action), style = Pi.type.meta, color = Pi.colors.ink3, maxLines = 1)
    }
}

/** What a restore does to a file, in words. */
private fun actionText(action: ChangeKind): String = when (action) {
    ChangeKind.Added -> "comes back"
    ChangeKind.Deleted -> "is deleted"
    ChangeKind.Modified -> "goes back"
}

private fun summary(record: TurnRecord): String {
    val files = if (record.files == 1) "1 file" else "${record.files} files"
    return "$files · +${record.added} −${record.removed}"
}

private fun fileDescription(delta: FileDelta): String = buildString {
    append(delta.path)
    when (delta.kind) {
        ChangeKind.Added -> append(", new file")
        ChangeKind.Deleted -> append(", deleted")
        ChangeKind.Modified -> Unit
    }
    if (delta.binary) append(", binary") else append(", ${delta.added} lines added, ${delta.removed} removed")
}

/** Parses a unified diff off the main thread, capped at [MAX_ROWS] rows. */
private suspend fun load(patch: String, binary: Boolean): FileDiff = withContext(Dispatchers.Default) {
    if (binary || "Binary files " in patch.take(4096) && "@@" !in patch) return@withContext FileDiff(emptyList(), DiffGutter(0, 0), emptyArray(), 0, true)
    val all = parseDiff(patch).filter { it.kind != DiffKind.Hunk || it.text.isNotBlank() }
    val lines = all.take(MAX_ROWS)
    FileDiff(lines, DiffGutter.of(lines), UnifiedDiff.wordRanges(lines), all.size - lines.size, false)
}
