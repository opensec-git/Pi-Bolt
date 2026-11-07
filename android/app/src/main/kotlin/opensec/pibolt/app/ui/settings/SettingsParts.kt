package opensec.pibolt.app.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.CardSection
import opensec.pibolt.app.ui.components.ControlLabel
import opensec.pibolt.app.ui.components.ListRow
import opensec.pibolt.app.ui.components.RowEnd
import opensec.pibolt.app.ui.components.RowInsetCard
import opensec.pibolt.app.ui.components.SwitchRow
import opensec.pibolt.app.ui.components.ValueRow
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Tnum
import opensec.pibolt.app.ui.components.ProgressLine
import opensec.pibolt.app.ui.update.updateStatus
import opensec.pibolt.app.update.UpdateJob
import opensec.pibolt.app.update.UpdateRules

/** A group: its glyph and label above a radius-lg surface1 card ([CardSection]). */
@Composable
internal fun Section(group: SettingsGroup, content: @Composable ColumnScope.() -> Unit) = CardSection(group.title, icon = groupIcon(group), content = content)

/** Each Settings group's 14dp glyph, as the desktop's settings sections carry one. */
internal fun groupIcon(group: SettingsGroup): ImageVector = when (group) {
    SettingsGroup.Providers -> Icons.Rounded.Key
    SettingsGroup.Agent -> Icons.Rounded.PhoneAndroid
    SettingsGroup.GitHub -> Icons.Rounded.Code
    SettingsGroup.Runtimes -> Icons.Rounded.Terminal
    SettingsGroup.Appearance -> Icons.Rounded.Palette
    SettingsGroup.Motion -> Icons.Rounded.Vibration
    SettingsGroup.SessionDisplay -> Icons.Rounded.ViewAgenda
    SettingsGroup.Notifications -> Icons.Rounded.Notifications
    SettingsGroup.Security -> Icons.Rounded.Shield
    SettingsGroup.About -> Icons.Rounded.Info
}

/** A control's heading inside a group card. */
@Composable
internal fun Label(text: String) = ControlLabel(text)

/** A control's heading with its current value beside it ("Text size  Large"). */
@Composable
internal fun LabelWithValue(text: String, value: String) = ControlLabel(text, value = value)

@Composable
internal fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) =
    SwitchRow(title, checked, onChange, meta = subtitle, padding = RowInsetCard)

/**
 * A 56dp row that opens something: an optional 20dp leading [icon] (ink3) or [leading] slot (a provider's mark), the
 * label, a value in meta beneath, and a chevron (or open-in-new for [external]).
 */
@Composable
internal fun NavRow(
    label: String,
    value: String?,
    onClick: () -> Unit,
    valueColor: Color = Pi.colors.ink3,
    external: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    iconTint: Color = Pi.colors.ink3,
    leading: (@Composable () -> Unit)? = null,
) =
    ListRow(
        label,
        meta = value,
        metaColor = valueColor,
        icon = icon,
        iconTint = iconTint,
        leading = leading,
        onClick = onClick,
        enabled = enabled,
        end = if (external) RowEnd.External else RowEnd.Chevron,
        padding = RowInsetCard,
        metaMaxLines = 3,
    )

@Composable
internal fun InfoLine(label: String, value: String) = ValueRow(label, value)

/** A key id: mono only, since in the UI font "l" and "I" look alike. */
@Composable
internal fun KeyLine(label: String, id: String, color: Color) = ValueRow(label, "[$id]", valueStyle = Pi.type.monoSmall, valueColor = color)

/** Notifications: allowed (success) or off (warning), and the system page as the row's action. */
@Composable
internal fun NotificationsRow() {
    val context = LocalContext.current
    val colors = Pi.colors
    var allowed by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = NotificationManagerCompat.from(context).areNotificationsEnabled() }
    NavRow(
        label = "Notifications",
        value = if (allowed) "Allowed" else "Off · no alert when Pi finishes",
        valueColor = if (allowed) colors.success else colors.warning,
        onClick = { openNotificationSettings(context) },
        external = true,
    )
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** About → updates: the last check's result or the running update, its one action, and the automatic check toggle. */
@Composable
internal fun UpdateBlock(app: PiBoltApp, autoCheck: Boolean, onAutoCheck: (Boolean) -> Unit) {
    val colors = Pi.colors
    val updates = app.updates
    val state by updates.state.collectAsStateWithLifecycle()
    val (title, detail) = updateStatus(state)
    val job = state.job
    val failed = job is UpdateJob.Failed || (state.checkError != null && !state.checking && job == UpdateJob.Idle)
    val checked = state.lastCheckAt.takeIf { it > 0 && job == UpdateJob.Idle && !state.checking }
        ?.let { at ->
            val now = System.currentTimeMillis()
            if (now - at < DateUtils.MINUTE_IN_MILLIS) "Checked just now" else "Checked " + DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS)
        }
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = Pi.type.bodyStrong, color = if (failed) colors.danger else colors.ink1)
            (detail ?: checked)?.let { Text(it, style = Pi.type.meta.copy(fontFeatureSettings = Tnum), color = colors.ink3) }
        }
        if (state.checking || job is UpdateJob.Verifying || job is UpdateJob.Installing) QuadSpinner(QuadMode.Think, size = 16.dp, color = colors.ink2)
    }
    if (job is UpdateJob.Downloading) ProgressLine(job.bytes.toFloat() / job.total.coerceAtLeast(1), Modifier.padding(vertical = 6.dp))
    Spacer(Modifier.height(8.dp))
    val available = (state.decision as? UpdateRules.Decision.Available)?.info
    when {
        job is UpdateJob.Downloading -> SecondaryButton("Cancel download", onClick = updates::cancelDownload, modifier = Modifier.fillMaxWidth())
        job is UpdateJob.Ready -> PrimaryButton("Install ${job.info.version}", onClick = updates::requestInstall, modifier = Modifier.fillMaxWidth())
        job is UpdateJob.Verifying || job is UpdateJob.Installing -> Unit
        available != null -> {
            PrimaryButton("Update to ${available.version}", onClick = { updates.requestUpdate(available) }, modifier = Modifier.fillMaxWidth())
            // The offer can be a cached or snoozed release: a newer one stays discoverable with automatic checks off.
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Check for updates", onClick = updates::checkNow, enabled = !state.checking, modifier = Modifier.fillMaxWidth())
        }
        else -> SecondaryButton("Check for updates", onClick = updates::checkNow, enabled = !state.checking, modifier = Modifier.fillMaxWidth())
    }
    Spacer(Modifier.height(8.dp))
    ToggleRow("Check for updates automatically", "Once a day · sends no identifiers", autoCheck, onAutoCheck)
}
