package opensec.pibolt.app.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
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
import opensec.pibolt.app.ui.components.SectionLabel
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.update.ProgressLine
import opensec.pibolt.app.ui.update.updateStatus
import opensec.pibolt.app.update.UpdateJob
import opensec.pibolt.app.update.UpdateRules

/** A group: its label above a surface1 card. */
@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        SectionLabel(title, Modifier.padding(bottom = 10.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .background(Pi.colors.surface1, RoundedCornerShape(Radius.lg))
                .border(1.dp, Pi.colors.line, RoundedCornerShape(Radius.lg))
                .padding(16.dp),
            content = content,
        )
    }
}

@Composable
internal fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Pi.type.bodyStrong, color = Pi.colors.ink1, modifier = modifier.padding(bottom = 10.dp))
}

/** A label with its current value beside it ("Text size  Larger"), the value in label ink2. */
@Composable
internal fun LabelWithValue(text: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
        Text(text, style = Pi.type.bodyStrong, color = Pi.colors.ink1)
        Spacer(Modifier.width(8.dp))
        Text(value, style = Pi.type.label, color = Pi.colors.ink2)
    }
}

@Composable
internal fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = Pi.colors
    val haptics = Pi.haptics
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .pressable({
                haptics.toggle(!checked)
                onChange(!checked)
            }, shape = RoundedCornerShape(Radius.sm), pressedScale = 1f, role = Role.Switch),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = Pi.type.bodyStrong, color = colors.ink1)
            Text(subtitle, style = Pi.type.meta, color = colors.ink3)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onAccent,
                checkedTrackColor = colors.accent,
                checkedBorderColor = colors.accent,
                uncheckedThumbColor = colors.ink3,
                uncheckedTrackColor = colors.surface3,
                uncheckedBorderColor = colors.lineStrong,
            ),
        )
    }
}

/** A 56dp row that opens something: label, a value in meta beneath, and a chevron (or [trailingIcon]). */
@Composable
internal fun NavRow(label: String, value: String?, onClick: () -> Unit, valueColor: Color = Pi.colors.ink3, external: Boolean = false, enabled: Boolean = true) {
    val colors = Pi.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .pressable(onClick, shape = RoundedCornerShape(Radius.sm), pressedScale = 1f, enabled = enabled, label = label),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = Pi.type.bodyStrong, color = colors.ink1)
            value?.let { Text(it, style = Pi.type.meta, color = valueColor, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Icon(
            if (external) Icons.AutoMirrored.Rounded.OpenInNew else Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = colors.ink3,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
internal fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Pi.type.body, color = Pi.colors.ink2, modifier = Modifier.width(110.dp))
        Text(value, style = Pi.type.body, color = Pi.colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun KeyLine(label: String, id: String, color: Color) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Pi.type.body, color = Pi.colors.ink2, modifier = Modifier.width(110.dp))
        Text("[$id]", style = Pi.type.mono, color = color)
    }
}

/** Notifications: allowed (success) or off (warning), and the system page as the row's action. */
@Composable
internal fun NotificationsRow() {
    val context = LocalContext.current
    val colors = Pi.colors
    var allowed by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = NotificationManagerCompat.from(context).areNotificationsEnabled() }
    NavRow(
        label = "Notifications",
        value = if (allowed) "Allowed · when Pi finishes or needs an answer" else "Off · Pi can't tell you when it finishes",
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
            (detail ?: checked)?.let { Text(it, style = Pi.type.meta.copy(fontFeatureSettings = "tnum"), color = colors.ink3) }
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
    ToggleRow("Check for updates automatically", "Once a day when the app opens: a plain request, no identifiers", autoCheck, onAutoCheck)
}
