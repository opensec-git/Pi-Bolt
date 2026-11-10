package opensec.pibolt.app.ui.session.review

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Difference
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import opensec.pibolt.app.checkpoints.RestoreKind
import opensec.pibolt.app.checkpoints.RestorePlan
import opensec.pibolt.app.checkpoints.TurnCheckpoints
import opensec.pibolt.app.checkpoints.TurnRecord
import opensec.pibolt.app.checkpoints.UndoEntry
import opensec.pibolt.app.ui.components.SnackKind
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Tnum
import opensec.pibolt.protocol.session.ChatItem
import opensec.pibolt.protocol.session.SessionState
import opensec.pibolt.protocol.session.UserItem

/**
 * Review and undo of a session's turns (the transcript's "Review changes" entries, the review screen and its sheets).
 * [checkpoints] is null over remote control: the files are on the computer, so the entries become a short note.
 * [forkAt] forks the conversation at a user message ([UserItem.text], its occurrence), as "Edit and resend" does.
 */
@Stable
class TurnReview internal constructor(
    val checkpoints: TurnCheckpoints?,
    private val state: State<SessionState>,
    private val scope: CoroutineScope,
    private val message: (String, SnackKind) -> Unit,
    private val forkAt: (String, Int) -> Unit,
) {
    /** The turn whose review screen is open. */
    var openTurn by mutableStateOf<String?>(null)

    /** The list of the session's changed turns is open (the changes pill, when several turns changed files). */
    var chooser by mutableStateOf(false)

    /** A restore that needs the user's choices or confirmation. */
    var plan by mutableStateOf<RestorePlan?>(null)
        private set

    /** A plan is being worked out or applied. */
    var busy by mutableStateOf(false)
        private set

    val running: Boolean get() = state.value.isRunning

    private var usersOf: List<ChatItem>? = null
    private var usersMap: Map<String, UserItem> = emptyMap()

    /** The transcript's user messages by key, rebuilt only when the finished items change (not per streamed frame). */
    val users = derivedStateOf {
        val items = state.value.items
        if (items !== usersOf) {
            usersOf = items
            usersMap = items.filterIsInstance<UserItem>().associateByTo(LinkedHashMap()) { it.key }
        }
        usersMap
    }

    /** The turn record of the transcript turn [turnKey] (its user message's key). */
    fun record(turns: List<TurnRecord>, turnKey: String): TurnRecord? {
        val user = users.value[turnKey] ?: return null
        return TurnCheckpoints.find(turns, user.timestamp, user.text)
    }

    fun open(record: TurnRecord) {
        openTurn = record.id
    }

    /** Undo [record] (only [paths] of it when given). Files changed since ask first; the rest go back at once. */
    fun undo(record: TurnRecord, paths: Collection<String>? = null) = work {
        val p = cp().planUndo(record, paths)
        when {
            p.items.isEmpty() -> {
                cp().discard(p)
                message("Nothing to undo", SnackKind.Info)
            }
            p.conflicts.isNotEmpty() -> plan = p
            else -> applyNow(p, p.items.map { it.path })
        }
    }

    /** Roll the files back to before [record] (later turns too) and fork the conversation at its message: confirmed first. */
    fun restoreBefore(record: TurnRecord) = work {
        plan = cp().planRestoreBefore(record)
    }

    /** Undo the restore [undo] of [record]: its files go back to how they were just before it. */
    fun redo(record: TurnRecord, undo: UndoEntry) = work {
        val p = cp().planRedo(record, undo)
        when {
            p.items.isEmpty() -> {
                cp().discard(p)
                message("Nothing to put back", SnackKind.Info)
            }
            p.conflicts.isNotEmpty() -> plan = p
            else -> applyNow(p, p.items.map { it.path })
        }
    }

    /** The plan sheet's answer: restore [paths] (conflicting files the user kept are left out). */
    fun confirm(p: RestorePlan, paths: Collection<String>) {
        plan = null
        work { applyNow(p, paths) }
    }

    fun dismiss(p: RestorePlan) {
        plan = null
        checkpoints?.discard(p)
    }

    private suspend fun applyNow(p: RestorePlan, paths: Collection<String>) {
        val result = cp().apply(p, paths)
        val n = result.restored.size
        val files = if (n == 1) "1 file" else "$n files"
        val kept = if (result.kept.isEmpty()) "" else " · ${result.kept.size} too large to restore"
        when (p.kind) {
            RestoreKind.UndoTurn -> message(if (n == 0) "No files changed$kept" else "Undid $files$kept", SnackKind.Success)
            RestoreKind.Redo -> message(if (n == 0) "No files changed" else "Put back $files", SnackKind.Success)
            RestoreKind.RestoreBefore -> {
                if (n > 0) message("Restored $files$kept", SnackKind.Success)
                forkBefore(p.turnId)
            }
        }
    }

    /** Forks the conversation at the user message of turn [turnId]; its text returns to the composer. */
    private fun forkBefore(turnId: String) {
        val record = checkpoints?.turn(turnId) ?: return
        val users = state.value.items.filterIsInstance<UserItem>()
        val user = users.lastOrNull { TurnCheckpoints.find(listOf(record), it.timestamp, it.text) != null }
        if (user == null) {
            message("Files restored. The message is no longer in the conversation, so nothing was forked.", SnackKind.Warning)
            return
        }
        openTurn = null
        forkAt(user.text, users.takeWhile { it.key != user.key }.count { it.text == user.text })
    }

    private fun cp(): TurnCheckpoints = checkpoints ?: throw IllegalStateException("Review and undo run on this phone only")

    private fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                message(e.message ?: "Could not restore the files", SnackKind.Error)
            } finally {
                busy = false
            }
        }
    }
}

val LocalTurnReview = staticCompositionLocalOf<TurnReview?> { null }

@Composable
fun rememberTurnReview(
    checkpoints: TurnCheckpoints?,
    state: State<SessionState>,
    message: (String, SnackKind) -> Unit,
    forkAt: (String, Int) -> Unit,
): TurnReview {
    val scope = rememberCoroutineScope()
    return remember(checkpoints, state) { TurnReview(checkpoints, state, scope, message, forkAt) }
}

/**
 * Under a finished turn's fold: "Review changes (3 files, +40 −12)", which opens the review screen. Nothing for a turn
 * that changed no files. Over remote control, the newest turn that edited files says where review and undo live.
 */
@Composable
fun TurnReviewEntry(turnKey: String, editedFiles: Boolean) {
    val review = LocalTurnReview.current ?: return
    val checkpoints = review.checkpoints
    val colors = Pi.colors
    val type = Pi.type
    if (checkpoints == null) {
        val last by remember(turnKey) { derivedStateOf { review.users.value.keys.lastOrNull() == turnKey } }
        if (editedFiles && last) {
            Text(
                "Review and undo are available in sessions on this phone",
                style = type.meta,
                color = colors.ink3,
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
            )
        }
        return
    }
    val turns by checkpoints.turns.collectAsState()
    val record = remember(turns, review.users.value, turnKey) { review.record(turns, turnKey) }
    if (record == null || !record.changed) return
    val files = if (record.files == 1) "1 file" else "${record.files} files"
    val undone = record.lastUndo != null
    val description = buildString {
        append("Review changes, ").append(files).append(", ").append(record.added).append(" lines added, ").append(record.removed).append(" removed")
        if (undone) append(", undone")
    }
    val num = type.label.copy(fontFeatureSettings = Tnum)
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .pressable({ review.open(record) }, shape = RoundedCornerShape(Radius.sm), label = "Review changes")
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Difference, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text("Review changes", style = type.label, color = colors.ink2, maxLines = 1)
        Text(" ($files, ", style = type.label, color = colors.ink3, maxLines = 1)
        Text("+${record.added}", style = num, color = colors.success, maxLines = 1)
        Text(" −${record.removed}", style = num, color = colors.danger, maxLines = 1)
        Text(")", style = type.label, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (undone) Text(" · undone", style = type.label, color = colors.ink3, maxLines = 1)
    }
}

/** The review screen over the session, and the sheet of a restore waiting for an answer. */
@Composable
fun TurnReviewOverlay(review: TurnReview) {
    AnimatedVisibility(
        visible = review.openTurn != null,
        enter = fadeIn(PiMotion.enter(PiMotion.SHEET)) + slideInVertically(PiMotion.enter(PiMotion.SHEET)) { it / 10 },
        exit = fadeOut(PiMotion.leave(PiMotion.SHEET)) + scaleOut(PiMotion.leave(PiMotion.SHEET), targetScale = 0.94f),
    ) {
        val shown = remember { mutableStateOf(review.openTurn) }
        review.openTurn?.let { shown.value = it }
        shown.value?.let { TurnReviewScreen(review, it, onClose = { review.openTurn = null }) }
    }
    review.plan?.let { RestoreSheet(review, it) }
    if (review.chooser) ChangesSheet(review, onDismiss = { review.chooser = false })
}
