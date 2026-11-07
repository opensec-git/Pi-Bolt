package opensec.pibolt.app.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.ui.components.GhostButton
import opensec.pibolt.app.ui.components.GroupControl
import opensec.pibolt.app.ui.components.GroupEnd
import opensec.pibolt.app.ui.components.GroupRow
import opensec.pibolt.app.ui.components.GroupSwitchRow
import opensec.pibolt.app.ui.components.InsetGroup
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.ProgressLine
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Space
import opensec.pibolt.app.ui.update.updateStatus
import opensec.pibolt.app.update.UpdateJob
import opensec.pibolt.app.update.UpdateRules

/** One row of a Settings group: drawn with a divider under it unless it is the group's last. */
internal typealias SettingsRow = @Composable (divider: Boolean) -> Unit

/**
 * An [InsetGroup] of the rows that search left visible, with hairlines between them; nothing at all when search hid
 * every row, so an empty card never shows.
 */
@Composable
internal fun SettingsBlock(rows: List<SettingsRow>, header: String? = null, footer: String? = null) {
    if (rows.isEmpty()) return
    InsetGroup(header = header, footer = footer) {
        rows.forEachIndexed { i, row -> row(i < rows.lastIndex) }
    }
}

/** Notifications: Allowed (success) or Off (warning); the row opens the system page. */
@Composable
internal fun NotificationsRow(divider: Boolean) {
    val context = LocalContext.current
    val colors = Pi.colors
    var allowed by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = NotificationManagerCompat.from(context).areNotificationsEnabled() }
    GroupRow(
        "Notifications",
        icon = Icons.Rounded.Notifications,
        value = if (allowed) "Allowed" else "Off",
        valueColor = if (allowed) colors.success else colors.warning,
        onClick = { openNotificationSettings(context) },
        end = GroupEnd.External,
        divider = divider,
    )
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/**
 * About → updates, as rows of the About group: the last check's result or the running update (a Quad while it
 * works, a progress line while it downloads), its one action as a button, and the automatic check switch.
 */
internal fun updateRows(app: PiBoltApp, autoCheck: Boolean, onAutoCheck: (Boolean) -> Unit): List<SettingsRow> = listOf(
    { d -> UpdateStatusRow(app, d) },
    { d -> UpdateActionRow(app, d) },
    { d ->
        GroupSwitchRow(
            "Check automatically",
            autoCheck,
            onAutoCheck,
            meta = "Daily, with no identifiers",
            icon = Icons.Rounded.Autorenew,
            divider = d,
        )
    },
)

@Composable
private fun UpdateStatusRow(app: PiBoltApp, divider: Boolean) {
    val colors = Pi.colors
    val state by app.updates.state.collectAsStateWithLifecycle()
    val (title, detail) = updateStatus(state)
    val job = state.job
    val failed = job is UpdateJob.Failed || (state.checkError != null && !state.checking && job == UpdateJob.Idle)
    val checked = state.lastCheckAt.takeIf { it > 0 && job == UpdateJob.Idle && !state.checking }
        ?.let { at ->
            val now = System.currentTimeMillis()
            if (now - at < DateUtils.MINUTE_IN_MILLIS) "Checked just now" else "Checked " + DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS)
        }
    val busy = state.checking || job is UpdateJob.Verifying || job is UpdateJob.Installing
    val spinner: (@Composable RowScope.() -> Unit)? = if (busy) {
        { QuadSpinner(QuadMode.Think, size = IconSize.sm, color = colors.ink2) }
    } else {
        null
    }
    GroupRow(
        title,
        icon = Icons.Rounded.SystemUpdate,
        iconTint = if (failed) colors.danger else colors.ink1,
        titleColor = if (failed) colors.danger else colors.ink1,
        meta = detail ?: checked,
        divider = divider && job !is UpdateJob.Downloading,
        trailing = spinner,
    )
    if (job is UpdateJob.Downloading) {
        GroupControl(divider = divider) { ProgressLine(job.bytes.toFloat() / job.total.coerceAtLeast(1)) }
    }
}

@Composable
private fun UpdateActionRow(app: PiBoltApp, divider: Boolean) {
    val updates = app.updates
    val state by updates.state.collectAsStateWithLifecycle()
    val job = state.job
    val decision = state.decision
    val available = (decision as? UpdateRules.Decision.Available)?.info
    val uri = LocalUriHandler.current
    if (job is UpdateJob.Verifying || job is UpdateJob.Installing) return
    GroupControl(divider = divider) {
        when {
            // A test build cannot take the release in place: the website, never a download that is refused at the end.
            job == UpdateJob.Idle && decision is UpdateRules.Decision.TestBuild ->
                SecondaryButton("Open pi-bolt.opensec.in", onClick = { uri.openUri(UpdateRules.WEBSITE) }, modifier = Modifier.fillMaxWidth())
            job is UpdateJob.Downloading -> SecondaryButton("Cancel download", onClick = updates::cancelDownload, modifier = Modifier.fillMaxWidth())
            job is UpdateJob.Ready -> PrimaryButton("Install ${job.info.version}", onClick = updates::requestInstall, modifier = Modifier.fillMaxWidth())
            // The offer can be a cached or snoozed release: a newer one stays discoverable with automatic checks off. One
            // row, the check as a quiet text button, so the group holds one strong action rather than two stacked capsules.
            available != null -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
                GhostButton("Check again", onClick = updates::checkNow, enabled = !state.checking, modifier = Modifier.weight(1f))
                PrimaryButton("Update to ${available.version}", onClick = { updates.requestUpdate(available) }, modifier = Modifier.weight(1f))
            }
            else -> SecondaryButton("Check for updates", onClick = updates::checkNow, enabled = !state.checking, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** A key id in mono, since in the UI font "l" and "I" look alike. */
@Composable
internal fun KeyId(id: String, current: Boolean) {
    Text("[$id]", style = Pi.type.monoSmall, color = if (current) Pi.colors.ink1 else Pi.colors.ink3, maxLines = 1, modifier = Modifier.padding(end = 4.dp))
}
