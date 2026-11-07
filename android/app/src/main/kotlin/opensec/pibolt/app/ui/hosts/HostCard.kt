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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
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
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
    // Status first, then the folder; transport and version are details, not meta, so the line never cuts the status.
    val status = when {
        connected && dialog -> "Waiting for you"
        connected && running -> "Working"
        connected -> "Connected"
        link is LinkState.Connecting || link is LinkState.Retrying -> "Connecting…"
        link is LinkState.Failed -> "Not connected"
        else -> lastSeen(host, now).replaceFirstChar { it.uppercase() }
    }
    val folder = host.cwd.takeIf { it.isNotBlank() }?.let(::baseName)
    val transport = listOfNotNull("LAN".takeIf { host.hasLan }, "tunnel".takeIf { host.hasTunnel }, host.version.takeIf { it.isNotBlank() }?.let { "v$it" }).joinToString(" · ")
    Row(
        modifier
            .fillMaxWidth()
            // At least the card height: at font scale 1.3 the three lines need a few dp more.
            .heightIn(min = Pi.dims.hostCard)
            .sharedHostBounds(SharedKeys.card(host.id), card = true)
            .pressable(onClick, shape = shape, onLongClick = { menu = true }, label = "Open computer")
            .semantics { customActions = listOf(CustomAccessibilityAction("Forget computer") { menu = true; true }) }
            .background(colors.surface1, shape)
            .border(1.dp, colors.line, shape)
            .padding(start = 18.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // One live indicator: the Quad while a session is live, the status dot otherwise.
        val showQuad = live != null && link != null
        if (!showQuad) StatusDot(linkColor(link, colors), Modifier.sharedHostElement(SharedKeys.dot(host.id)))
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
            Row {
                Text(status, style = type.meta, color = colors.ink3, maxLines = 1)
                if (folder != null) Text(" · $folder", style = type.meta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            if (transport.isNotEmpty() && !Pi.dims.compact) {
                Text(transport, style = type.monoMeta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (showQuad) {
            val busy = running || dialog || !connected
            val mode: QuadMode = rememberSettledMode(liveMode)
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                QuadSpinner(mode, size = 20.dp, color = if (dialog) colors.warning else if (busy) colors.ink1 else colors.ink3, animate = busy)
            }
        }
        Box {
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
