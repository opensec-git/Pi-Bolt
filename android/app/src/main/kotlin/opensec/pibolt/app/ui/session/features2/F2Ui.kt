package opensec.pibolt.app.ui.session.features2

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius

/** A list row: icon, title, meta line, optional trailing content. 56 dp tall at least. */
@Composable
fun F2Row(
    title: String,
    meta: String?,
    icon: ImageVector?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    iconTint: Color = Pi.colors.ink3,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    titleMaxLines: Int = 2,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = Pi.colors
    val base = modifier.fillMaxWidth().heightIn(min = 56.dp)
    Row(
        (if (onClick != null) base.pressable(onClick, shape = RoundedCornerShape(0.dp), enabled = enabled, pressedScale = 1f, onLongClick = onLongClick) else base)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Pi.type.body, color = if (enabled) colors.ink1 else colors.ink2, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
            if (!meta.isNullOrBlank()) Text(meta, style = Pi.type.meta, color = colors.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** A switch row (the whole row toggles; TalkBack reads it as a switch). */
@Composable
fun F2Toggle(title: String, meta: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val colors = Pi.colors
    val haptics = Pi.haptics
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .pressable({
                haptics.toggle(!checked)
                onChange(!checked)
            }, shape = RoundedCornerShape(0.dp), pressedScale = 1f, enabled = enabled, role = Role.Switch)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = Pi.type.body, color = colors.ink1, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!meta.isNullOrBlank()) Text(meta, style = Pi.type.meta, color = colors.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
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

@Composable
fun F2Loading(label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        QuadSpinner(QuadMode.Think, size = 18.dp, color = Pi.colors.ink2)
        Spacer(Modifier.width(12.dp))
        Text(label, style = Pi.type.body, color = Pi.colors.ink3)
    }
}

@Composable
fun F2Empty(text: String) {
    Text(text, style = Pi.type.body, color = Pi.colors.ink3, modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp))
}

/** A small rounded tag (source, state). */
@Composable
fun F2Tag(text: String, color: Color = Pi.colors.ink2, fill: Color = Pi.colors.surface3) {
    Box(
        Modifier
            .background(fill, RoundedCornerShape(Radius.xs))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .semantics { contentDescription = text },
    ) {
        Text(text, style = Pi.type.micro, color = color, maxLines = 1)
    }
}

/** A labelled block inside a sheet: surface1 well with a line. */
@Composable
fun F2Well(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(Radius.md)
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(Pi.colors.surface1, shape)
            .border(1.dp, Pi.colors.line, shape)
            .padding(12.dp),
    ) { content() }
}

@Composable
fun F2Section(text: String) {
    Text(text, style = Pi.type.metaStrong, color = Pi.colors.ink3, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp))
}
