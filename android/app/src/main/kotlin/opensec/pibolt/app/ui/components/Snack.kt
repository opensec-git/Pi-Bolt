package opensec.pibolt.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius

enum class SnackKind { Info, Success, Warning, Error }

private class PiSnackVisuals(
    override val message: String,
    val kind: SnackKind,
    override val actionLabel: String?,
) : SnackbarVisuals {
    override val withDismissAction: Boolean = false
    override val duration: SnackbarDuration = if (kind == SnackKind.Error || actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
}

suspend fun SnackbarHostState.show(message: String, kind: SnackKind = SnackKind.Info, action: String? = null) =
    showSnackbar(PiSnackVisuals(message, kind, action))

fun snackKindOf(notifyType: String): SnackKind = when (notifyType.lowercase()) {
    "error" -> SnackKind.Error
    "warning", "warn" -> SnackKind.Warning
    "success" -> SnackKind.Success
    else -> SnackKind.Info
}

/** Snackbars: surface3, radius 12, a line, kind-coloured icon. */
@Composable
fun PiSnackHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data -> PiSnack(data) }
}

@Composable
private fun PiSnack(data: SnackbarData) {
    val colors = Pi.colors
    val kind = (data.visuals as? PiSnackVisuals)?.kind ?: SnackKind.Info
    val (icon, tint) = when (kind) {
        SnackKind.Info -> Icons.Rounded.Info to colors.ink2
        SnackKind.Success -> Icons.Rounded.CheckCircle to colors.success
        SnackKind.Warning -> Icons.Rounded.WarningAmber to colors.warning
        SnackKind.Error -> Icons.Rounded.ErrorOutline to colors.danger
    }
    val shape = RoundedCornerShape(Radius.md)
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(colors.surface3, shape)
            .border(1.dp, colors.lineStrong, shape)
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(data.visuals.message, style = Pi.type.body, color = colors.ink1, modifier = Modifier.weight(1f).padding(end = 8.dp), maxLines = 4)
        data.visuals.actionLabel?.let { TextAction(it, onClick = { data.performAction() }, color = colors.ink1) }
    }
}
