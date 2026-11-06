package opensec.pibolt.app.ui.hosts

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import opensec.pibolt.app.data.Host
import opensec.pibolt.app.session.LinkState
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.QuadMath
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.SharedKeys
import opensec.pibolt.app.ui.components.StatusDot
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.components.rememberSettledMode
import opensec.pibolt.app.ui.components.sharedHostBounds
import opensec.pibolt.app.ui.components.sharedHostElement
import opensec.pibolt.app.ui.session.TranscriptModel
import opensec.pibolt.app.ui.session.baseName
import opensec.pibolt.app.ui.session.linkColor
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius

/**
 * A paired computer: status dot, name, "Connected · repo · LAN · v1.1.1" and, for the live one, a Quad in its state.
 * The live session's state is reduced to the three values the card shows, so streamed frames do not recompose it.
 */
@Composable
fun HostCard(host: Host, live: SessionController?, now: Long, onClick: () -> Unit, onForget: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    var menu by remember { mutableStateOf(false) }
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
    val shape = RoundedCornerShape(Radius.md)
    val detail = buildList {
        when {
            connected && dialog -> add("Waiting for you")
            connected && running -> add("Working")
            connected -> add("Connected")
            link is LinkState.Connecting || link is LinkState.Retrying -> add("Connecting…")
            link is LinkState.Failed -> add("Not connected")
        }
        if (host.cwd.isNotBlank()) add(baseName(host.cwd))
        if (host.hasLan) add("LAN")
        if (host.hasTunnel) add("tunnel")
        if (host.version.isNotBlank()) add("v${host.version}")
        if (link == null) add(lastSeen(host, now))
    }.joinToString(" · ")
    Row(
        modifier
            .fillMaxWidth()
            .height(Pi.dims.hostCard)
            .sharedHostBounds(SharedKeys.card(host.id), card = true)
            .pressable(onClick, shape = shape, onLongClick = { menu = true })
            .background(colors.surface1, shape)
            .border(1.dp, colors.line, shape)
            .padding(start = 18.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(linkColor(link, colors), Modifier.sharedHostElement(SharedKeys.dot(host.id)))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                host.name,
                style = type.bodyStrong,
                color = colors.ink1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.sharedHostElement(SharedKeys.name(host.id)),
            )
            Spacer(Modifier.height(2.dp))
            Text(detail, style = type.meta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (live != null && link != null) {
            val busy = running || dialog || !connected
            val mode: QuadMode = rememberSettledMode(liveMode)
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                QuadSpinner(mode, size = 20.dp, color = if (dialog) colors.warning else if (busy) colors.ink1 else colors.ink3, animate = busy)
            }
        }
        Box {
            IconAction(Icons.Rounded.MoreVert, "More", onClick = { menu = true })
            DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                containerColor = colors.surface3,
                shape = RoundedCornerShape(Radius.md),
                tonalElevation = 0.dp,
                border = BorderStroke(1.dp, colors.lineStrong),
            ) {
                DropdownMenuItem(
                    text = { Text("Forget computer", style = type.body, color = colors.danger) },
                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = colors.danger, modifier = Modifier.size(20.dp)) },
                    onClick = {
                        menu = false
                        onForget()
                    },
                )
            }
        }
    }
}

private fun lastSeen(host: Host, now: Long): String {
    if (host.lastConnectedAt <= 0) return "paired"
    if (now - host.lastConnectedAt < DateUtils.MINUTE_IN_MILLIS) return "last seen just now"
    return "last seen " + DateUtils.getRelativeTimeSpanString(host.lastConnectedAt, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
}
