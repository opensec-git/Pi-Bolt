package opensec.pibolt.app.ui.session.review

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import opensec.pibolt.app.checkpoints.TurnCheckpoints
import opensec.pibolt.app.checkpoints.TurnRecord
import opensec.pibolt.app.ui.components.GlassPill
import opensec.pibolt.app.ui.components.InsetRow
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Space
import opensec.pibolt.app.ui.theme.Tnum
import opensec.pibolt.protocol.session.UserItem

/**
 * What this session changed in the project, from the turn checkpoints: the turns whose files changed (oldest first, an
 * undone turn left out, since its changes are gone) and their lines added and removed.
 */
@Immutable
data class SessionChanges(val turns: List<TurnRecord>, val added: Int, val removed: Int) {
    val files: Int get() = turns.sumOf { it.files }

    companion object {
        /** The changes of the turns [users] started; null when none changed a file. */
        fun of(records: List<TurnRecord>, users: Collection<UserItem>): SessionChanges? {
            val seen = HashSet<String>()
            val turns = users.mapNotNull { user ->
                TurnCheckpoints.find(records, user.timestamp, user.text)?.takeIf { it.changed && it.lastUndo == null && seen.add(it.id) }
            }
            if (turns.isEmpty()) return null
            return SessionChanges(turns, turns.sumOf { it.added }, turns.sumOf { it.removed })
        }

        /** The review row of [record]: "3 files · +40 −12". */
        fun meta(record: TurnRecord): String = (if (record.files == 1) "1 file" else "${record.files} files") + " · +${record.added} −${record.removed}"
    }
}

/**
 * The session's changes as a floating glass pill at the end of the composer's slim row: "Changes +12 −3" (label ink1,
 * the counts in success and danger, tabular). One changed turn opens its review; several open the list of turns
 * ([TurnReview.chooser]). Nothing over remote control (the files are on the computer) or before a turn changed a file.
 */
@Composable
fun SessionChangesPill(review: TurnReview) {
    val checkpoints = review.checkpoints ?: return
    val records by checkpoints.turns.collectAsState()
    val users = review.users.value
    val changes = remember(records, users) { SessionChanges.of(records, users.values) } ?: return
    val colors = Pi.colors
    val type = Pi.type
    val num = type.label.copy(fontFeatureSettings = Tnum)
    val turns = changes.turns.size
    val spoken = "View changes, ${if (changes.files == 1) "1 file" else "${changes.files} files"}, ${changes.added} lines added, ${changes.removed} removed"
    GlassPill(
        onClick = { if (turns == 1) review.open(changes.turns[0]) else review.chooser = true },
        label = "View changes",
        modifier = Modifier.semantics { contentDescription = spoken },
    ) {
        Text("Changes", style = type.label, color = colors.ink1, maxLines = 1)
        Text("+${changes.added}", style = num, color = colors.success, maxLines = 1)
        Text("−${changes.removed}", style = num, color = colors.danger, maxLines = 1)
    }
}

/** The session's changed turns, newest first, when the changes pill covers several: each opens its review. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChangesSheet(review: TurnReview, onDismiss: () -> Unit) {
    val checkpoints = review.checkpoints ?: return
    val records by checkpoints.turns.collectAsState()
    val changes = remember(records, review.users.value) { SessionChanges.of(records, review.users.value.values) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader("Changes", subtitle = changes?.let { "+${it.added} −${it.removed} in ${it.turns.size} turns" })
        val turns = changes?.turns.orEmpty().asReversed()
        turns.forEachIndexed { i, record ->
            InsetRow(
                title = record.prompt.lineSequence().firstOrNull()?.trim().orEmpty().ifEmpty { "Turn" },
                meta = SessionChanges.meta(record),
                onClick = {
                    close {
                        onDismiss()
                        review.open(record)
                    }
                },
                chevron = true,
                divider = i < turns.lastIndex,
                label = "Review changes",
            )
        }
        Spacer(Modifier.height(Space.lg))
    }
}
