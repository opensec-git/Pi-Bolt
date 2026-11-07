package opensec.pibolt.app.ui.settings

import android.text.format.DateUtils
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.data.Host
import opensec.pibolt.app.session.LinkState
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.app.ui.components.ConfirmSheet
import opensec.pibolt.app.ui.components.GroupEnd
import opensec.pibolt.app.ui.components.GroupRow
import opensec.pibolt.app.ui.hosts.PasteSheet
import opensec.pibolt.app.ui.theme.Pi

/** The line under a paired computer in Settings > Remote control (unit-tested). */
object RemoteRows {
    /**
     * "Connected", "Connecting…" or "Not connected" while this phone has a session with it ([link] non-null), else
     * when it was last seen ("Last seen 5 min. ago", "Paired"). [relative] formats an age; Android's in the app.
     */
    fun status(host: Host, link: LinkState?, now: Long, relative: (at: Long, now: Long) -> String): String = when (link) {
        is LinkState.Connected -> "Connected"
        is LinkState.Connecting, is LinkState.Retrying -> "Connecting…"
        is LinkState.Failed -> "Not connected"
        null -> when {
            host.lastConnectedAt <= 0 -> "Paired"
            now - host.lastConnectedAt < 60_000 -> "Last seen just now"
            else -> "Last seen " + relative(host.lastConnectedAt, now)
        }
    }
}

/**
 * Settings > Remote control: the computers this phone is paired with (a tap connects, or returns to the live
 * session; long press or the TalkBack action forgets one), Pair with a code (the scanner) or a pasted link, then this
 * phone's device key and its reset. Forgetting and resetting are confirmed first and say what the computer keeps.
 */
@Composable
internal fun RemoteSection(
    app: PiBoltApp,
    show: (String) -> Boolean,
    onScan: () -> Unit,
    onOpenHost: (Host) -> Unit,
    onResumeHost: () -> Unit,
    onPairingText: (String) -> String?,
) {
    val colors = Pi.colors
    val hosts by app.hosts.hosts.collectAsStateWithLifecycle()
    val remote by app.remote.collectAsState()
    var forgetting by remember { mutableStateOf<Host?>(null) }
    var pasting by remember { mutableStateOf(false) }
    var keyId by remember { mutableStateOf(app.deviceKeyId()) }
    var oldKeyId by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val now = remember(hosts) { System.currentTimeMillis() }

    val computerRows = buildList<SettingsRow> {
        if (!show("computers")) return@buildList
        for (host in hosts) add { d ->
            val live: SessionController? = remote?.takeIf { it.hostId == host.id && !it.stopped }
            val link = live?.link?.collectAsStateWithLifecycle()?.value
            GroupRow(
                host.displayName,
                icon = Icons.Rounded.DesktopWindows,
                iconTint = if (link is LinkState.Connected) colors.success else colors.tint1,
                meta = RemoteRows.status(host, link, now) { at, n ->
                    DateUtils.getRelativeTimeSpanString(at, n, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
                },
                metaMaxLines = 1,
                value = if (host.service) "Service" else null,
                onClick = { if (live != null) onResumeHost() else onOpenHost(host) },
                onLongClick = { forgetting = host },
                label = "Connect",
                divider = d,
                modifier = Modifier.semantics { customActions = listOf(CustomAccessibilityAction("Forget computer") { forgetting = host; true }) },
            )
        }
        add { d -> GroupRow("Pair with a code", icon = Icons.Rounded.QrCodeScanner, iconTint = colors.tint1, onClick = onScan, divider = d) }
        add { d -> GroupRow("Paste a pairing link", icon = Icons.Rounded.ContentPaste, iconTint = colors.tint1, onClick = { pasting = true }, end = GroupEnd.None, divider = d) }
    }
    SettingsBlock(
        computerRows,
        header = SettingsGroup.RemoteControl.title,
        footer = if (hosts.isEmpty()) "Run /\u2060remote-control on a computer, then scan its code." else null,
    )

    val keyRows = buildList<SettingsRow> {
        if (!show("device-key")) return@buildList
        add { d -> GroupRow("Device key", icon = Icons.Rounded.Key, iconTint = colors.tint1, divider = d, trailing = { KeyId(keyId, current = true) }) }
        oldKeyId?.let { old -> add { d -> GroupRow("Old key", divider = d, trailing = { KeyId(old, current = false) }) } }
        add { d -> GroupRow("Reset device key", icon = Icons.Rounded.Refresh, iconTint = colors.danger, onClick = { confirmReset = true }, end = GroupEnd.None, divider = d) }
    }
    SettingsBlock(
        keyRows,
        footer = oldKeyId?.let { "New key made. Pair each computer again, then revoke the old key there with /\u2060remote-control devices." },
    )

    if (pasting) {
        PasteSheet(
            onDismiss = { pasting = false },
            onSubmit = { text ->
                val error = onPairingText(text)
                if (error == null) pasting = false
                error
            },
        )
    }
    forgetting?.let { host ->
        ConfirmSheet(
            title = "Forget ${host.displayName}?",
            message = "This phone stops connecting to it. To also revoke this phone on the computer, run /\u2060remote-control devices there.",
            confirmLabel = "Forget",
            onConfirm = {
                app.forget(host.id)
                forgetting = null
            },
            onDismiss = { forgetting = null },
        )
    }
    if (confirmReset) {
        ConfirmSheet(
            title = "Reset this phone's key?",
            message = "Every paired computer will refuse this phone, so they are removed here and the current session ends. " +
                "Pair each one again with a new code, then revoke the old key on it with /\u2060remote-control devices.",
            confirmLabel = "Reset key",
            onConfirm = {
                confirmReset = false
                oldKeyId = keyId
                keyId = app.resetDeviceKey()
            },
            onDismiss = { confirmReset = false },
        )
    }
}
