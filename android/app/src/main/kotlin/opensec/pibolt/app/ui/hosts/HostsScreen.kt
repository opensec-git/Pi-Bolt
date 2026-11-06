package opensec.pibolt.app.ui.hosts

import android.text.format.DateUtils
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.data.Host
import opensec.pibolt.app.session.LinkState
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.app.ui.components.BrandMark
import opensec.pibolt.app.ui.components.ConfirmSheet
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.PiSnackHost
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.QuadMath
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.SectionLabel
import opensec.pibolt.app.ui.components.SharedKeys
import opensec.pibolt.app.ui.components.SnackKind
import opensec.pibolt.app.ui.components.StatusBarScrim
import opensec.pibolt.app.ui.components.StatusDot
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.components.rememberSettledMode
import opensec.pibolt.app.ui.components.sharedHostBounds
import opensec.pibolt.app.ui.components.sharedHostElement
import opensec.pibolt.app.ui.components.show
import opensec.pibolt.app.ui.session.TranscriptModel
import opensec.pibolt.app.ui.session.baseName
import opensec.pibolt.app.ui.session.linkColor
import opensec.pibolt.app.ui.theme.Mono
import opensec.pibolt.app.ui.theme.MonoFeatures
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius

@Composable
fun HostsScreen(
    app: PiBoltApp,
    externalError: String?,
    onErrorShown: () -> Unit,
    onOpen: (Host) -> Unit,
    onScan: () -> Unit,
    onSettings: () -> Unit,
    onPairingText: (String) -> String?,
    onResume: () -> Unit,
) {
    val hosts by app.hosts.hosts.collectAsStateWithLifecycle()
    val session by app.session.collectAsStateWithLifecycle()
    val colors = Pi.colors
    val type = Pi.type
    val snackbar = remember { SnackbarHostState() }
    var showPaste by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf<Host?>(null) }
    // "last seen" refreshes while the screen is open.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }

    LaunchedEffect(externalError) {
        externalError ?: return@LaunchedEffect
        snackbar.show(externalError, SnackKind.Error)
        onErrorShown()
    }

    Box(Modifier.fillMaxSize().background(colors.canvas)) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 120.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") {
                Column(Modifier.statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().height(56.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        IconAction(Icons.Outlined.Settings, "Settings", onClick = onSettings)
                    }
                    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (hosts.isNotEmpty()) {
                            BrandMark(size = 28.dp, play = false)
                            Spacer(Modifier.width(12.dp))
                        }
                        Text("Pi-Bolt", style = type.display.copy(fontSize = type.display.fontSize * 1.15f), color = colors.ink1)
                    }
                }
            }
            if (hosts.isEmpty()) {
                item(key = "empty") { EmptyHome() }
            } else {
                item(key = "label") { SectionLabel("Computers", Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) }
                items(hosts, key = { it.id }) { host ->
                    val live = session?.takeIf { it.hostId == host.id && !it.stopped }
                    HostCard(
                        host = host,
                        live = live,
                        now = now,
                        onClick = { if (live != null) onResume() else onOpen(host) },
                        onForget = { forgetting = host },
                        modifier = Modifier.animateItem().padding(horizontal = 16.dp, vertical = 5.dp),
                    )
                }
            }
        }

        StatusBarScrim()
        // Bottom actions on a canvas fade so the list scrolls under them.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(0f to colors.canvas.copy(alpha = 0f), 0.3f to colors.canvas)),
        ) {
            PiSnackHost(snackbar)
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PrimaryButton("Scan QR code", onClick = onScan, icon = Icons.Rounded.QrCodeScanner, modifier = Modifier.weight(1f))
                SecondaryButton("Paste link", onClick = { showPaste = true }, icon = Icons.Rounded.ContentPaste, modifier = Modifier.weight(1f))
            }
        }
    }

    if (showPaste) {
        PasteSheet(
            onDismiss = { showPaste = false },
            onSubmit = { text ->
                val error = onPairingText(text)
                if (error == null) showPaste = false
                error
            },
        )
    }

    forgetting?.let { host ->
        ConfirmSheet(
            title = "Forget ${host.name}?",
            message = "This phone stops connecting to it. To also revoke this phone on the computer, run /\u2060remote-control devices there.",
            confirmLabel = "Forget",
            onConfirm = {
                app.forget(host.id)
                forgetting = null
            },
            onDismiss = { forgetting = null },
        )
    }
}

@Composable
private fun EmptyHome() {
    val colors = Pi.colors
    val type = Pi.type
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        BrandMark(size = 44.dp)
        Spacer(Modifier.height(24.dp))
        Text("Control Pi-Bolt from your phone", style = type.display, color = colors.ink1)
        Spacer(Modifier.height(8.dp))
        Text("Pair once with a computer running Pi-Bolt, then follow and steer its sessions from here.", style = type.body, color = colors.ink2)
        Spacer(Modifier.height(28.dp))
        Step(1, buildAnnotatedString {
            append("On your computer, run ")
            code("/remote-control")
            append(" in Pi-Bolt.")
        })
        Step(2, buildAnnotatedString {
            append("Scan the QR code it shows, or paste the link from ")
            code("/remote-control copy")
            append(".")
        })
        Step(3, buildAnnotatedString { append("Done. This phone remembers the computer and reconnects by itself.") })
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.code(text: String) {
    withStyle(SpanStyle(fontFamily = Mono, fontFeatureSettings = MonoFeatures)) { append(text) }
}

@Composable
private fun Step(n: Int, text: androidx.compose.ui.text.AnnotatedString) {
    val colors = Pi.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Box(Modifier.size(26.dp).background(colors.surface2, CircleShape).border(1.dp, colors.line, CircleShape), contentAlignment = Alignment.Center) {
            Text("$n", style = Pi.type.metaStrong, color = colors.ink1)
        }
        Spacer(Modifier.width(14.dp))
        Text(text, style = Pi.type.body, color = colors.ink2, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun HostCard(host: Host, live: SessionController?, now: Long, onClick: () -> Unit, onForget: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    var menu by remember { mutableStateOf(false) }
    val link = live?.link?.collectAsStateWithLifecycle()?.value
    val liveState = live?.state?.collectAsStateWithLifecycle()
    val running = liveState?.value?.isRunning == true
    val dialog = liveState?.value?.dialogs?.isNotEmpty() == true
    val shape = RoundedCornerShape(Radius.md)
    val detail = buildList {
        when {
            link is LinkState.Connected && dialog -> add("Waiting for you")
            link is LinkState.Connected && running -> add("Working")
            link is LinkState.Connected -> add("Connected")
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
            val busy = running || dialog || link !is LinkState.Connected
            val mode = rememberSettledMode(
                if (liveState == null) {
                    QuadMath.modeFor(false, false, false, false, false, link is LinkState.Connected)
                } else {
                    val s = liveState.value
                    val activity = TranscriptModel.activity(s.items, s.streaming)
                    QuadMath.modeFor(s.dialogs.isNotEmpty(), s.isCompacting, activity.subagent, activity.toolRunning, activity.writing, link is LinkState.Connected)
                },
            )
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
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.lineStrong),
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
