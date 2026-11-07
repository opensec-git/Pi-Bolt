package opensec.pibolt.app.ui.update

import opensec.pibolt.app.ui.components.Notice
import opensec.pibolt.app.ui.components.SnackKind
import opensec.pibolt.app.ui.theme.Tnum
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.ui.components.ButtonRow
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.ProgressLine
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Space
import opensec.pibolt.app.update.NetworkKind
import opensec.pibolt.app.update.UpdateInfo
import opensec.pibolt.app.update.UpdateJob
import opensec.pibolt.app.update.UpdateNotifications
import opensec.pibolt.app.update.UpdatePrompt
import opensec.pibolt.app.update.UpdateRules
import opensec.pibolt.app.update.UpdateState

/**
 * The update card on Home: an offer ("Pi-Bolt 0.2.2 is available", Later / Update) while nothing runs, then the
 * download, the check, the install and any failure in the same place. Hidden when there is nothing to say.
 */
@Composable
fun UpdateCard(app: PiBoltApp, modifier: Modifier = Modifier) {
    val state by app.updates.state.collectAsStateWithLifecycle()
    val updates = app.updates
    val colors = Pi.colors
    val offer = (state.decision as? UpdateRules.Decision.Available)?.takeIf { !it.snoozed }?.info
    when (val job = state.job) {
        UpdateJob.Idle -> offer?.let { info ->
            UpdateRow(
                title = "Pi-Bolt ${info.version}",
                detail = "Update available · ${UpdateRules.formatSize(info.size)}",
                modifier = modifier,
            ) {
                TextAction("Later", onClick = { updates.snooze(info.version) }, color = colors.ink2)
                TextAction("Update", onClick = { updates.requestUpdate(info) }, color = colors.accent)
            }
        }
        is UpdateJob.Downloading -> UpdateRow(
            title = "Downloading Pi-Bolt ${job.info.version}",
            detail = UpdateNotifications.progressText(job),
            progress = job.bytes.toFloat() / job.total.coerceAtLeast(1),
            modifier = modifier,
        ) {
            TextAction("Cancel", onClick = updates::cancelDownload, color = colors.ink2)
        }
        is UpdateJob.Verifying -> UpdateRow(
            title = "Checking Pi-Bolt ${job.info.version}",
            detail = "Checksum and signature",
            modifier = modifier,
        )
        is UpdateJob.Ready -> UpdateRow(
            title = "Pi-Bolt ${job.info.version} is ready",
            detail = job.message ?: "Signed by OpenSec · checksum matches",
            modifier = modifier,
        ) {
            TextAction("Install", onClick = updates::requestInstall, color = colors.accent)
        }
        is UpdateJob.Installing -> UpdateRow(
            title = "Installing Pi-Bolt ${job.info.version}",
            detail = "Confirm in Android's dialog",
            modifier = modifier,
        )
        is UpdateJob.Failed -> UpdateRow(
            title = if (job.refused) "Update refused" else "Update failed",
            detail = job.message,
            danger = true,
            modifier = modifier,
        ) {
            TextAction("Close", onClick = updates::dismissFailure, color = colors.ink2)
            if (!job.refused) TextAction("Retry", onClick = { updates.requestUpdate(job.info) }, color = colors.accent)
        }
    }
}

/**
 * One slim line on Home, on the canvas: the title (body ink1) over one meta line, its actions at the end, and the 2dp
 * progress line under it while a download runs. Its text sits on the 20dp page gutter.
 * Only a failure is filled: dangerSoft with a danger @ 40% line.
 */
@Composable
private fun UpdateRow(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    danger: Boolean = false,
    actions: (@Composable () -> Unit)? = null,
) {
    val colors = Pi.colors
    val shape = RoundedCornerShape(Radius.md)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.sm)
            .then(if (danger) Modifier.background(colors.dangerSoft, shape).border(1.dp, colors.danger.copy(alpha = 0.4f), shape) else Modifier)
            .padding(start = Space.page - Space.sm, end = Space.xs, top = Space.xs, bottom = Space.xs),
    ) {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(title, style = Pi.type.rowTitle, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = Pi.type.rowMeta.copy(fontFeatureSettings = Tnum), color = if (danger) colors.ink2 else colors.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (actions != null) Row(verticalAlignment = Alignment.CenterVertically) { actions() }
        }
        if (progress != null) ProgressLine(progress, Modifier.padding(top = 4.dp, end = 12.dp, bottom = 4.dp))
    }
}

/** Settings → About: what the last check found, or what the update is doing now. */
fun updateStatus(state: UpdateState): Pair<String, String?> {
    val job = state.job
    return when {
        job is UpdateJob.Downloading -> "Downloading ${job.info.version}" to UpdateNotifications.progressText(job)
        job is UpdateJob.Verifying -> "Checking ${job.info.version}" to "Checksum and signature"
        job is UpdateJob.Ready -> "${job.info.version} is ready to install" to (job.message ?: "Signed by OpenSec · checksum matches")
        job is UpdateJob.Installing -> "Installing ${job.info.version}" to "Confirm in Android's dialog"
        job is UpdateJob.Failed -> job.message to null
        state.checking -> "Checking for updates…" to null
        state.checkError != null -> state.checkError to null
        else -> when (val d = state.decision) {
            is UpdateRules.Decision.Available -> "${d.info.version} is available · ${UpdateRules.formatSize(d.info.size)}" to null
            is UpdateRules.Decision.TestBuild -> "Test build · updates from the website" to "${d.info.version} is on pi-bolt.opensec.in"
            UpdateRules.Decision.UpToDate -> if (state.latest != null) "Up to date (${state.installed})" to null else "Not checked yet" to null
        }
    }
}

/** True while a turn runs in a session on this phone, or on a computer this phone is connected to. */
private fun piWorking(app: PiBoltApp): String? {
    if (app.sessions.value.any { it.isLocal && !it.stopped && it.state.value.isRunning }) return "Pi is working; updating stops it."
    val remote = app.remote.value?.takeIf { !it.stopped }
    if (remote?.state?.value?.isRunning == true) return "Pi is working on ${remote.hostName.value}. Updating disconnects this phone; the computer keeps going."
    return null
}

/** The update sheets, over any screen: confirm the download (size and network), then install. */
@Composable
fun UpdateSheets(app: PiBoltApp) {
    val prompt by app.updates.prompt.collectAsStateWithLifecycle()
    when (val p = prompt) {
        is UpdatePrompt.Download -> DownloadSheet(app, p.info)
        is UpdatePrompt.Install -> InstallSheet(app, p.info)
        null -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadSheet(app: PiBoltApp, info: UpdateInfo) {
    val colors = Pi.colors
    val updates = app.updates
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    val uri = LocalUriHandler.current
    var network by remember { mutableStateOf(updates.network()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { network = updates.network() }
    val size = UpdateRules.formatSize(info.size)
    PiSheet(onDismiss = updates::dismissPrompt, state = state) {
        SheetHeader("Update to Pi-Bolt ${info.version}")
        Spacer(Modifier.height(8.dp))
        val (icon, text, tint) = when (network) {
            NetworkKind.Wifi -> Triple(Icons.Rounded.Wifi, "$size over Wi-Fi", colors.ink2)
            NetworkKind.MeteredWifi -> Triple(Icons.Rounded.Wifi, "$size over a metered Wi-Fi network", colors.warning)
            NetworkKind.Mobile -> Triple(Icons.Rounded.SignalCellularAlt, "$size over mobile data", colors.warning)
            NetworkKind.MeteredOther -> Triple(Icons.Rounded.SignalCellularAlt, "$size over a metered network", colors.warning)
            NetworkKind.None -> Triple(Icons.Rounded.CloudOff, "$size · starts when you're back online", colors.warning)
            NetworkKind.Unknown -> Triple(Icons.Rounded.Wifi, "$size download", colors.ink2)
        }
        InfoBox(icon, text, tint)
        if (info.page?.startsWith("https://github.com/opensec-git/Pi-Bolt/releases/") == true) {
            TextAction("Release notes", onClick = { runCatching { uri.openUri(info.page) } }, color = colors.accent, modifier = Modifier.padding(start = 8.dp))
        }
        Spacer(Modifier.height(12.dp))
        ButtonRow(Modifier.padding(horizontal = 20.dp)) {
            SecondaryButton("Cancel", onClick = { close(updates::dismissPrompt) })
            PrimaryButton("Download", onClick = { close { updates.startDownload(info) } })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstallSheet(app: PiBoltApp, info: UpdateInfo) {
    val colors = Pi.colors
    val updates = app.updates
    val context = LocalContext.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    val haptics = Pi.haptics
    var allowed by remember { mutableStateOf(updates.canInstall()) }
    var askedForPermission by remember { mutableStateOf(false) }
    val working = remember { piWorking(app) }
    // Back from "Install unknown apps": carry on by itself once it is allowed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        allowed = updates.canInstall()
        if (allowed && askedForPermission && working == null) {
            askedForPermission = false
            updates.install(context)
        }
    }
    PiSheet(onDismiss = updates::dismissPrompt, state = state) {
        SheetHeader("Install Pi-Bolt ${info.version}")
        Spacer(Modifier.height(8.dp))
        InfoBox(Icons.Rounded.GppGood, "Signed by OpenSec · checksum matches", colors.success)
        if (working != null) InfoBox(Icons.Rounded.WarningAmber, working, colors.warning)
        if (!allowed) {
            InfoBox(
                Icons.Rounded.SystemUpdate,
                "Turn on “Allow from this source” for Pi-Bolt, then come back.",
                colors.ink2,
            )
        }
        Spacer(Modifier.height(12.dp))
        ButtonRow(Modifier.padding(horizontal = 20.dp)) {
            SecondaryButton("Cancel", onClick = { close(updates::dismissPrompt) })
            if (allowed) {
                PrimaryButton(if (working != null) "Update anyway" else "Install", onClick = {
                    haptics.confirm()
                    close { updates.install(context) }
                })
            } else {
                PrimaryButton("Open settings", onClick = {
                    askedForPermission = true
                    openInstallPermission(context)
                })
            }
        }
    }
}

/** A [Notice] in a sheet: the network, the signature or a warning; [tint] picks the kind. */
@Composable
private fun InfoBox(icon: ImageVector, text: String, tint: Color) {
    val colors = Pi.colors
    val kind = when (tint) {
        colors.success -> SnackKind.Success
        colors.warning -> SnackKind.Warning
        colors.danger -> SnackKind.Error
        else -> SnackKind.Info
    }
    Notice(text, kind = kind, icon = icon, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

private fun openInstallPermission(context: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
    runCatching { context.startActivity(intent) }
}
