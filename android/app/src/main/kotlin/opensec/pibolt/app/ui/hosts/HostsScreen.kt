package opensec.pibolt.app.ui.hosts

import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.data.Host
import opensec.pibolt.app.session.LinkState
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.app.ui.theme.PiTheme
import opensec.pibolt.protocol.PairingPayload

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostsScreen(
    app: PiBoltApp,
    externalError: String?,
    onErrorShown: () -> Unit,
    onOpen: (Host) -> Unit,
    onScan: () -> Unit,
    onPairingText: (String) -> String?,
    onResume: () -> Unit,
) {
    val hosts by app.hosts.hosts.collectAsStateWithLifecycle()
    val session by app.session.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showPaste by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf<Host?>(null) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(externalError) {
        externalError ?: return@LaunchedEffect
        snackbar.showSnackbar(externalError)
        onErrorShown()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pi-Bolt")
                    }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background, scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (hosts.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    PairButtons(
                        onScan = onScan,
                        onPaste = { showPaste = true },
                        modifier = Modifier
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (hosts.isEmpty()) {
            EmptyState(Modifier.padding(padding), onScan = onScan, onPaste = { showPaste = true })
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = padding.calculateBottomPadding() + 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "header") {
                    Text(
                        "Your computers",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                    )
                }
                items(hosts, key = { it.id }) { host ->
                    val live = session?.takeIf { it.hostId == host.id && !it.stopped }
                    HostCard(
                        host = host,
                        live = live,
                        onClick = { if (live != null) onResume() else onOpen(host) },
                        onForget = { forgetting = host },
                    )
                }
            }
        }
    }

    if (showPaste) {
        PasteDialog(
            onDismiss = { showPaste = false },
            onSubmit = { text ->
                val error = onPairingText(text)
                if (error == null) showPaste = false
                error
            },
        )
    }

    forgetting?.let { host ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget ${host.name}?") },
            text = {
                Text(
                    "This phone stops connecting to it. To also revoke this phone on the computer, run /remote-control devices there.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    app.forget(host.id)
                    forgetting = null
                }) { Text("Forget", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PairButtons(onScan: () -> Unit, onPaste: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onScan, modifier = Modifier.weight(1f).height(52.dp)) {
            Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Scan QR code")
        }
        FilledTonalButton(onClick = onPaste, modifier = Modifier.weight(1f).height(52.dp)) {
            Icon(Icons.Outlined.ContentPaste, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Paste link", maxLines = 1)
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, onScan: () -> Unit, onPaste: () -> Unit) {
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(96.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(52.dp))
        }
        Spacer(Modifier.height(28.dp))
        Text("Drive Pi from your phone", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            "On your computer, run /remote-control in Pi-Bolt. Scan the code it shows, or run /remote-control copy and paste the link here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        PairButtons(onScan, onPaste)
        Spacer(Modifier.height(48.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostCard(host: Host, live: SessionController?, onClick: () -> Unit, onForget: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val link = live?.link?.collectAsStateWithLifecycle()?.value
    val running = live?.state?.collectAsStateWithLifecycle()?.value?.isRunning == true
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { menu = true }),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(46.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Computer, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                if (link != null) {
                    val color = when {
                        link is LinkState.Connected && running -> PiTheme.colors.running
                        link is LinkState.Connected -> PiTheme.colors.success
                        link is LinkState.Failed -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.outline
                    }
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(14.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
                            .padding(2.5.dp)
                            .background(color, CircleShape),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(host.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val detail = when {
                    link is LinkState.Connected && running -> "Connected · working"
                    link is LinkState.Connected -> "Connected"
                    link is LinkState.Connecting || link is LinkState.Retrying -> "Connecting…"
                    else -> lastSeen(host)
                }
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                val where = buildList {
                    if (host.cwd.isNotBlank()) add(host.cwd.substringAfterLast('/').ifBlank { host.cwd })
                    if (host.hasLan) add("LAN")
                    if (host.hasTunnel) add("tunnel")
                    if (host.version.isNotBlank()) add("v${host.version}")
                }.joinToString(" · ")
                if (where.isNotBlank()) {
                    Text(where, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (live != null) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Open session", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 4.dp))
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Forget computer") },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                        onClick = {
                            menu = false
                            onForget()
                        },
                    )
                }
            }
        }
    }
}

private fun lastSeen(host: Host): String {
    if (host.lastConnectedAt <= 0) return "Paired"
    if (System.currentTimeMillis() - host.lastConnectedAt < DateUtils.MINUTE_IN_MILLIS) return "Last connected just now"
    return "Last connected " + DateUtils.getRelativeTimeSpanString(host.lastConnectedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
}

@Composable
private fun PasteDialog(onDismiss: () -> Unit, onSubmit: (String) -> String?) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(clipboardPairingLink(context) ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.ContentPaste, contentDescription = null) },
        title = { Text("Paste pairing link") },
        text = {
            Column {
                Text(
                    "Run /remote-control copy on the computer, then paste the pibolt://pair link.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    placeholder = { Text("pibolt://pair#…") },
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    maxLines = 4,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = {
                    clipboardText(context)?.let {
                        text = it
                        error = null
                    }
                }) {
                    Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Paste from clipboard")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { error = onSubmit(text) }) {
                Text("Connect", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun clipboardText(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()?.trim()
}

private fun clipboardPairingLink(context: Context): String? =
    clipboardText(context)?.takeIf { PairingPayload.looksLikePairing(it) }
