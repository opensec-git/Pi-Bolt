package opensec.pibolt.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import opensec.pibolt.app.background.BackgroundSetup
import opensec.pibolt.app.background.OemSetup
import opensec.pibolt.app.ui.components.GroupRow
import opensec.pibolt.app.ui.components.GhostButton
import opensec.pibolt.app.ui.components.ListRow
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.RowEnd
import opensec.pibolt.app.ui.components.RowInsetSheet
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.theme.Pi

/**
 * Settings → Run in background: one row with the current state; it opens [BackgroundRunSheet]. Self-contained, for any
 * Settings card: `BackgroundRunRow()`.
 */
@Composable
fun BackgroundRunRow(divider: Boolean = false) {
    val state = rememberBackgroundState()
    var open by remember { mutableStateOf(false) }
    val colors = Pi.colors
    val (value, color) = when {
        !state.ready -> "Not set up" to colors.warning
        state.steps.isNotEmpty() -> "${state.steps.size} to check" to colors.ink3
        else -> "Ready" to colors.success
    }
    GroupRow("Run in background", icon = Icons.Rounded.Bedtime, value = value, valueColor = color, onClick = { open = true }, divider = divider)
    if (open) BackgroundRunSheet(onDismiss = { open = false })
}

/**
 * The one-time prompt, the first time a run on this phone continued with the app away: shown when the user is back.
 * Mount once at the app root: `BackgroundRunPrompt()`.
 */
@Composable
fun BackgroundRunPrompt() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { BackgroundSetup.restore(context) }
    val pending by BackgroundSetup.prompt.collectAsStateWithLifecycle()
    if (pending) BackgroundRunSheet(onDismiss = { BackgroundSetup.promptDone(context) }, prompt = true)
}

/** The state, read again whenever the app resumes (back from a system screen). */
@Composable
private fun rememberBackgroundState(): BackgroundSetup.State {
    val context = LocalContext.current
    var state by remember { mutableStateOf(BackgroundSetup.state(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { state = BackgroundSetup.state(context) }
    return state
}

/** Each switch that keeps a run going, with its state where Android reports one, and the screen that changes it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackgroundRunSheet(onDismiss: () -> Unit, prompt: Boolean = false) {
    val context = LocalContext.current
    val colors = Pi.colors
    val state = rememberBackgroundState()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(sheet)
    PiSheet(onDismiss = onDismiss, state = sheet) {
        SheetHeader("Run in background", subtitle = "Keep Pi working with the screen off.")
        Spacer(Modifier.height(4.dp))
        SetupRow(
            "Battery optimization",
            when {
                state.unrestricted -> "Off"
                state.battery != null -> "On · " + state.battery.hint
                else -> "On · Android may pause Pi"
            },
            if (state.unrestricted) colors.success else colors.warning,
            Icons.Rounded.BatteryFull,
        ) { BackgroundSetup.openBattery(context, state) }
        if (state.restricted) {
            SetupRow("Background use", "Restricted · Allow it under Battery", colors.danger, Icons.Rounded.Block) { BackgroundSetup.openAppDetails(context) }
        }
        for (step in state.steps) {
            SetupRow(step.label, step.hint, colors.ink3, stepIcon(step)) { BackgroundSetup.open(context, step) }
        }
        SetupRow(
            "Notifications",
            if (state.notifications) "Allowed" else "Off · No alert when Pi finishes",
            if (state.notifications) colors.success else colors.warning,
            Icons.Rounded.Notifications,
        ) { BackgroundSetup.openNotifications(context) }
        if (prompt) {
            Spacer(Modifier.height(8.dp))
            GhostButton("Done", onClick = { close(onDismiss) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        }
    }
}

private fun stepIcon(step: OemSetup.Step): ImageVector = when (step.id) {
    "autostart", "auto-launch", "app-launch" -> Icons.Rounded.RocketLaunch
    else -> Icons.Rounded.Tune
}

@Composable
private fun SetupRow(title: String, meta: String, color: Color, icon: ImageVector, onClick: () -> Unit) =
    ListRow(title, meta = meta, metaColor = color, icon = icon, onClick = onClick, end = RowEnd.External, padding = RowInsetSheet)
