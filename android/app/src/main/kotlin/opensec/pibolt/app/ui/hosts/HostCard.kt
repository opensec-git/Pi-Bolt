package opensec.pibolt.app.ui.hosts

import android.text.format.DateUtils
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import opensec.pibolt.app.data.Host
import opensec.pibolt.app.session.LinkState
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.app.ui.components.QuadMath
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.SharedKeys
import opensec.pibolt.app.ui.components.StatusDot
import opensec.pibolt.app.ui.components.rememberSettledMode
import opensec.pibolt.app.ui.components.sharedHostBounds
import opensec.pibolt.app.ui.components.sharedHostElement
import opensec.pibolt.app.ui.session.TranscriptModel
import opensec.pibolt.app.ui.session.baseName
import opensec.pibolt.app.ui.session.linkColor
import opensec.pibolt.app.ui.components.InsetRow
import opensec.pibolt.app.ui.components.Tag
import opensec.pibolt.app.ui.theme.Space
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.rounded.MoreHoriz
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi

/**
 * A paired computer, one [InsetRow] of Home's "Computers": its status dot (the Quad while its session is live), its
 * name (the one given on this phone first) over "Connected · repo · LAN · Tailscale · v1.1.1", and Manage (also a long
 * press) for its connection, a name and Forget. The live session's state is reduced to the three values the card shows, so streamed frames do not recompose it.
 */
@Composable
fun HostCard(host: Host, live: SessionController?, now: Long, onClick: () -> Unit, onManage: (status: String) -> Unit, modifier: Modifier = Modifier, divider: Boolean = true) {
    val colors = Pi.colors
    val link = live?.link?.collectAsStateWithLifecycle()?.value
    val liveState = live?.state?.collectAsStateWithLifecycle()
    val connected = link is LinkState.Connected
    val running by remember(liveState) { derivedStateOf { liveState?.value?.isRunning == true } }
    val dialog by remember(liveState) { derivedStateOf { liveState?.value?.dialogs?.isNotEmpty() == true } }
    val liveMode by remember(liveState, connected) {
        derivedStateOf {
            val s = liveState?.value
            if (s == null) {
                QuadMath.modeFor(false, false, false, false, false, connected)
            } else {
                val activity = TranscriptModel.activity(s.items, s.streaming)
                QuadMath.modeFor(s.dialogs.isNotEmpty(), s.isCompacting, activity.subagent, activity.toolRunning, activity.writing, connected)
            }
        }
    }
    // Status first, then the folder and how this phone reaches it; only the part after the status gives way.
    val status = when {
        connected && dialog -> "Waiting for you"
        connected && running -> "Working"
        connected -> "Connected"
        link is LinkState.Connecting || link is LinkState.Retrying -> "Connecting…"
        link is LinkState.Failed -> "Not connected"
        else -> lastSeen(host, now).replaceFirstChar { it.uppercase() }
    }
    val folder = host.cwd.takeIf { it.isNotBlank() }?.let(::baseName)
    val rest = (listOfNotNull(folder) + host.connections() + listOfNotNull(host.version.takeIf { it.isNotBlank() }?.let { "v$it" })).joinToString(" · ")
    InsetRow(
        title = host.displayName,
        meta = if (rest.isEmpty()) status else "$status · $rest",
        onClick = onClick,
        onLongClick = { onManage(status) },
        label = "Open computer",
        divider = divider,
        modifier = modifier
            .sharedHostBounds(SharedKeys.card(host.id), card = true)
            .semantics { customActions = listOf(CustomAccessibilityAction("Manage computer") { onManage(status); true }) },
        titleModifier = Modifier.sharedHostElement(SharedKeys.name(host.id)),
        leading = {
            // One live indicator: the Quad while a session is live, the status dot otherwise.
            if (live != null && link != null) {
                val busy = running || dialog || !connected
                val mode: QuadMode = rememberSettledMode(liveMode)
                QuadSpinner(mode, size = 16.dp, color = if (dialog) colors.warning else if (busy) colors.ink1 else colors.ink3, animate = busy)
            } else {
                StatusDot(linkColor(link, colors), Modifier.sharedHostElement(SharedKeys.dot(host.id)))
            }
        },
        trailing = {
            // The background service answers for this computer: it reaches every project, with no terminal open.
            if (host.service) Tag("Service", Modifier.padding(end = Space.xs))
            IconAction(Icons.Rounded.MoreHoriz, "Manage ${host.displayName}", onClick = { onManage(status) }, tint = colors.ink3, size = IconSize.md)
        },
    )
}

private fun lastSeen(host: Host, now: Long): String {
    if (host.lastConnectedAt <= 0) return "paired"
    if (now - host.lastConnectedAt < DateUtils.MINUTE_IN_MILLIS) return "last seen just now"
    return "last seen " + DateUtils.getRelativeTimeSpanString(host.lastConnectedAt, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
}
