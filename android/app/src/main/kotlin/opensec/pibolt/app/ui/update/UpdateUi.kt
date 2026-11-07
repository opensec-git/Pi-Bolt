package opensec.pibolt.app.ui.update

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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.projects.LocalSessions
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius
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
                icon = { Glyph(Icons.Rounded.SystemUpdate, colors.ink2) },
                title = "Pi-Bolt ${info.version} is available",
                detail = UpdateRules.formatSize(info.size),
                modifier = modifier,
            ) {
                TextAction("Later", onClick = { updates.snooze(info.version) }, color = colors.ink2)
                TextAction("Update", onClick = { updates.requestUpdate(info) }, color = colors.accent)
            }
        }
        is UpdateJob.Downloading -> UpdateRow(
            icon = { QuadSpinner(if (job.waitingForNetwork) QuadMode.Connect else QuadMode.Write, size = 20.dp, color = colors.ink2) },
            title = "Downloading Pi-Bolt ${job.info.version}",
            detail = UpdateNotifications.progressText(job),
            progress = job.bytes.toFloat() / job.total.coerceAtLeast(1),
            modifier = modifier,
        ) {
            TextAction("Cancel", onClick = updates::cancelDownload, color = colors.ink2)
        }
        is UpdateJob.Verifying -> UpdateRow(
            icon = { QuadSpinner(QuadMode.Run, size = 20.dp, color = colors.ink2) },
            title = "Checking Pi-Bolt ${job.info.version}",
            detail = "Checksum and signature",
            modifier = modifier,
        )
        is UpdateJob.Ready -> UpdateRow(
            icon = { Glyph(Icons.Rounded.GppGood, colors.success) },
            title = "Pi-Bolt ${job.info.version} is ready to install",
            detail = job.message ?: "Signed by OpenSec · checksum matches",
            modifier = modifier,
        ) {
            TextAction("Install", onClick = updates::requestInstall, color = colors.accent)
        }
        is UpdateJob.Installing -> UpdateRow(
            icon = { QuadSpinner(QuadMode.Connect, size = 20.dp, color = colors.ink2) },
            title = "Installing Pi-Bolt ${job.info.version}",
            detail = "Confirm in Android's dialog",
            modifier = modifier,
        )
        is UpdateJob.Failed -> UpdateRow(
            icon = { Glyph(Icons.Rounded.ErrorOutline, colors.danger) },
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

@Composable
private fun Glyph(icon: ImageVector, tint: Color) = Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))

/** A 64dp card like the engine banner: glyph, title, one meta line, optional 2dp progress, text actions. */
@Composable
private fun UpdateRow(
    icon: @Composable () -> Unit,
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
            .heightIn(min = 64.dp)
            .background(if (danger) colors.dangerSoft else colors.surface1, shape)
            .border(1.dp, if (danger) colors.danger.copy(alpha = 0.4f) else colors.line, shape)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { icon() }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(title, style = Pi.type.bodyStrong, color = colors.ink1, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(detail, style = Pi.type.meta.copy(fontFeatureSettings = "tnum"), color = if (danger) colors.ink2 else colors.ink3, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (actions != null) Row(verticalAlignment = Alignment.CenterVertically) { actions() }
        }
        if (progress != null) {
            Spacer(Modifier.height(10.dp))
            ProgressLine(progress, Modifier.padding(start = 34.dp, end = 12.dp))
        }
    }
}

/** A 2dp bar: surface3 track, accent fill. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val f = fraction.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(colors.surface3, RoundedCornerShape(1.dp))
            .semantics { contentDescription = "${(f * 100).toInt()} percent" },
    ) {
        Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(colors.accent, RoundedCornerShape(1.dp)))
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
            UpdateRules.Decision.UpToDate -> if (state.latest != null) "Up to date (${state.installed})" to null else "Not checked yet" to null
        }
    }
}

/** True while a turn runs in a session on this phone, or on a computer this phone is connected to. */
private fun piWorking(app: PiBoltApp): String? {
    val local = LocalSessions.current.value?.controller?.takeIf { !it.stopped }
    if (local?.state?.value?.isRunning == true) return "Pi is working; updating stops it."
    val remote = app.session.value?.takeIf { !it.stopped }
    if (remote?.state?.value?.isRunning == true) return "Pi is working on ${remote.hostName.value}; updating disconnects this phone, the computer keeps going."
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
    val type = Pi.type
    val updates = app.updates
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    val uri = LocalUriHandler.current
    var network by remember { mutableStateOf(updates.network()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { network = updates.network() }
    val size = UpdateRules.formatSize(info.size)
    PiSheet(onDismiss = updates::dismissPrompt, state = state) {
        SheetHeader(
            "Update to Pi-Bolt ${info.version}",
            subtitle = "Pi-Bolt downloads it from GitHub, checks that OpenSec signed it, then Android asks you to install it.",
        )
        Spacer(Modifier.height(8.dp))
        val (icon, text, tint) = when (network) {
            NetworkKind.Wifi -> Triple(Icons.Rounded.Wifi, "$size over Wi-Fi", colors.ink2)
            NetworkKind.MeteredWifi -> Triple(Icons.Rounded.Wifi, "$size over a metered Wi-Fi network", colors.warning)
            NetworkKind.Mobile -> Triple(Icons.Rounded.SignalCellularAlt, "$size over mobile data", colors.warning)
            NetworkKind.MeteredOther -> Triple(Icons.Rounded.SignalCellularAlt, "$size over a metered network", colors.warning)
            NetworkKind.None -> Triple(Icons.Rounded.CloudOff, "$size · no connection now; it starts when you are back online", colors.warning)
            NetworkKind.Unknown -> Triple(Icons.Rounded.Wifi, "$size download", colors.ink2)
        }
        InfoBox(icon, text, tint)
        if (info.page?.startsWith("https://github.com/opensec-git/Pi-Bolt/releases/") == true) {
            TextAction("Release notes", onClick = { runCatching { uri.openUri(info.page) } }, color = colors.accent, modifier = Modifier.padding(start = 8.dp))
        }
        Text(
            "It keeps downloading if you leave the app; the notification shows progress.",
            style = type.meta,
            color = colors.ink3,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("Cancel", onClick = { close(updates::dismissPrompt) }, modifier = Modifier.weight(1f))
            PrimaryButton("Download", onClick = { close { updates.startDownload(info) } }, modifier = Modifier.weight(1f))
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
        SheetHeader("Install Pi-Bolt ${info.version}", subtitle = "Android asks you to confirm, then offers to open the new version.")
        Spacer(Modifier.height(8.dp))
        InfoBox(Icons.Rounded.GppGood, "Signed by OpenSec · checksum matches", colors.success)
        if (working != null) InfoBox(Icons.Rounded.WarningAmber, working, colors.warning)
        if (!allowed) {
            InfoBox(
                Icons.Rounded.SystemUpdate,
                "Android needs your permission once: turn on “Allow from this source” for Pi-Bolt, then come back here.",
                colors.ink2,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("Cancel", onClick = { close(updates::dismissPrompt) }, modifier = Modifier.weight(1f))
            if (allowed) {
                PrimaryButton(if (working != null) "Update anyway" else "Install", onClick = {
                    haptics.confirm()
                    close { updates.install(context) }
                }, modifier = Modifier.weight(1f))
            } else {
                PrimaryButton("Open settings", onClick = {
                    askedForPermission = true
                    openInstallPermission(context)
                }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun InfoBox(icon: ImageVector, text: String, tint: Color) {
    val colors = Pi.colors
    val shape = RoundedCornerShape(Radius.md)
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .background(colors.surface1, shape)
            .border(1.dp, colors.line, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = Pi.type.body, color = if (tint == colors.ink2) colors.ink1 else tint)
    }
}

private fun openInstallPermission(context: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
    runCatching { context.startActivity(intent) }
}
