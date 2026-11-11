package opensec.pibolt.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius

/**
 * The redesign's popup menu: surface3, radius tile (16), a 1dp lineStrong ring, no tonal elevation. Items are
 * [PiMenuItem]s. For the long-press menus of rows and cards (a host, a running session, a workspace row).
 */
@Composable
fun PiMenu(expanded: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = Pi.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = colors.surface3,
        shape = RoundedCornerShape(Radius.tile),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, colors.lineStrong),
        content = content,
    )
}

/**
 * One menu item: a 20dp glyph and the label in rowMeta, both ink1, or [color] (danger for a destructive item).
 * [checked] marks the current choice of a picker menu with a 20dp accent check at the end (read as Selected).
 */
@Composable
fun PiMenuItem(text: String, onClick: () -> Unit, icon: ImageVector? = null, color: Color = Pi.colors.ink1, checked: Boolean = false) {
    DropdownMenuItem(
        text = { Text(text, style = Pi.type.rowMeta, color = color) },
        leadingIcon = icon?.let { { Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(IconSize.md)) } },
        trailingIcon = if (checked) {
            { Icon(Icons.Rounded.Check, contentDescription = null, tint = Pi.colors.accent, modifier = Modifier.size(IconSize.md)) }
        } else {
            null
        },
        onClick = onClick,
        modifier = if (checked) Modifier.semantics { selected = true } else Modifier,
    )
}
