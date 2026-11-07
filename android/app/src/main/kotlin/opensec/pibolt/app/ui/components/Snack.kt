package opensec.pibolt.app.ui.components

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Space

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

/**
 * Snackbars, in the redesign's glass: a floating capsule ([Radius.pill], glass fill and ring) 20dp from the window's
 * sides, a 20dp kind glyph, the message in rowMeta ink1 (four lines at most) and an ink1 text action.
 */
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
    val shape = RoundedCornerShape(Radius.pill)
    Row(
        Modifier
            .padding(horizontal = Space.page, vertical = Space.sm)
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .glass(shape, colors)
            .padding(start = Space.lg + Space.xxs, end = Space.sm, top = Space.md - Space.xxs, bottom = Space.md - Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(IconSize.md))
        Spacer(Modifier.width(Space.md))
        Text(data.visuals.message, style = Pi.type.rowMeta, color = colors.ink1, modifier = Modifier.weight(1f).padding(end = Space.sm), maxLines = 4, overflow = TextOverflow.Ellipsis)
        data.visuals.actionLabel?.let { TextAction(it, onClick = { data.performAction() }, color = colors.ink1) }
    }
}
