package opensec.pibolt.app.ui.projects

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import opensec.pibolt.app.cost.Money
import opensec.pibolt.app.engine.SessionSummary
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.app.ui.components.InsetRow
import opensec.pibolt.app.ui.cost.SpendText
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi

/** What a saved session's row says under its title (unit-tested). */
object SessionRowRules {
    /** Where the open session stands. */
    enum class Live { Working, Waiting, Open }

    /**
     * The meta line: an open session says what it does and where ("Working · main", "Waiting for you · main", "Open ·
     * 24 messages", the project name outside a repository); any other one when it was last active and how long it is
     * ("2h · 24 messages", "5m · 1 message", "3d").
     */
    fun meta(live: Live?, where: String?, ago: String, messages: Int): String {
        val count = when {
            messages <= 0 -> null
            messages == 1 -> "1 message"
            else -> "$messages messages"
        }
        return when (live) {
            Live.Working -> listOfNotNull("Working", where).joinToString(" · ")
            Live.Waiting -> listOfNotNull("Waiting for you", where).joinToString(" · ")
            Live.Open -> listOfNotNull("Open", count).joinToString(" · ")
            null -> listOfNotNull(ago, count).joinToString(" · ")
        }
    }

    /** The whole row for TalkBack: title, its state and the meta in words ("2 hours ago" rather than "2h"). */
    fun spoken(title: String, live: Live?, unread: Boolean, where: String?, agoLong: String, messages: Int): String = listOfNotNull(
        title,
        when (live) {
            Live.Working -> "working" + (where?.let { " on $it" } ?: "")
            Live.Waiting -> "waiting for you"
            Live.Open -> "open"
            null -> null
        },
        "new activity".takeIf { unread },
        (if (messages == 1) "1 message" else "$messages messages").takeIf { messages > 0 },
        agoLong,
    ).joinToString(", ")
}

/**
 * One saved session of the project, an [InsetRow]: a Quad in the leading slot (live and in its mode while Pi works,
 * warning while it waits for an answer, a still ink4 frame otherwise), the session's name or first prompt, and
 * [SessionRowRules.meta] under it. The open session sits on the row highlight; new activity is the unread dot.
 */
@Composable
fun SessionRow(s: SessionSummary, live: SessionController?, unread: Boolean, now: Long, where: String?, onClick: () -> Unit, modifier: Modifier = Modifier, divider: Boolean = true) {
    // Settings > Spending > Show costs in lists: a row that is not working ends its meta with the session's cost.
    val showCost = Pi.settings.spend.showInLists
    val title = ProjectRules.sessionTitle(s)
    if (live != null) {
        val state by live.state.collectAsStateWithLifecycle()
        val phase by remember(live) {
            derivedStateOf {
                when {
                    state.dialogs.isNotEmpty() -> SessionRowRules.Live.Waiting
                    state.isRunning || state.isCompacting -> SessionRowRules.Live.Working
                    else -> SessionRowRules.Live.Open
                }
            }
        }
        SessionInsetRow(s, title, phase, unread = false, now, where, onClick, modifier, divider, showCost && phase == SessionRowRules.Live.Open, lead = { SessionMark(live, size = IconSize.md) })
    } else {
        SessionInsetRow(s, title, null, unread, now, where, onClick, modifier, divider, showCost, lead = { QuadSpinner(QuadMode.Think, size = IconSize.md, color = Pi.colors.ink4, animate = false) })
    }
}

@Composable
private fun SessionInsetRow(
    s: SessionSummary,
    title: String,
    live: SessionRowRules.Live?,
    unread: Boolean,
    now: Long,
    where: String?,
    onClick: () -> Unit,
    modifier: Modifier,
    divider: Boolean,
    showCost: Boolean,
    lead: @Composable () -> Unit,
) {
    val spoken = SessionRowRules.spoken(title, live, unread, where, ProjectRules.ago(s.updatedAt, now), s.messageCount) +
        (Money.spoken(s.cost)?.takeIf { showCost }?.let { ", cost $it" } ?: "")
    InsetRow(
        title = title,
        onClick = onClick,
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
        meta = SpendText.withCost(SessionRowRules.meta(live, where, ProjectRules.agoShort(s.updatedAt, now), s.messageCount), s.cost, showCost),
        leading = { Box(Modifier.clearAndSetSemantics { }, contentAlignment = Alignment.Center) { lead() } },
        unread = unread,
        divider = divider,
        current = live != null,
        label = if (live != null) "Open session" else "Resume session",
    )
}
