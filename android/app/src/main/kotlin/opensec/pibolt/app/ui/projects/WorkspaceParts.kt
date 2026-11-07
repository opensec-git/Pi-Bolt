package opensec.pibolt.app.ui.projects

import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius

/**
 * One tab of the workspace's tab strips (open files in the editor, shells in the terminal): 36dp drawn inside a
 * 48dp row, radius 8, surface2 and a lineStrong border when shown, a line border otherwise. A 7dp warning dot when
 * [dirty], the name in monoSmall (ink1 shown, ink2 otherwise, ink3 when [ended]; at most 160dp) and a 16dp close
 * glyph. The name and the close button each have a 48dp-tall target.
 */
@Composable
fun WorkspaceTab(
    name: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    description: String,
    closeDescription: String,
    modifier: Modifier = Modifier,
    dirty: Boolean = false,
    ended: Boolean = false,
) {
    val colors = Pi.colors
    val fill by animateColorAsState(if (selected) colors.surface2 else Color.Transparent, PiMotion.effects(), label = "tab-fill")
    val edge = if (selected) colors.lineStrong else colors.line
    Row(
        modifier
            .height(48.dp)
            .drawBehind {
                val inset = 6.dp.toPx()
                val r = CornerRadius(Radius.sm.toPx())
                val size = Size(this.size.width, this.size.height - 2 * inset)
                drawRoundRect(fill, Offset(0f, inset), size, r)
                val stroke = 1.dp.toPx()
                drawRoundRect(edge, Offset(stroke / 2, inset + stroke / 2), Size(size.width - stroke, size.height - stroke), r, style = Stroke(stroke))
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .pressable(onSelect, shape = RoundedCornerShape(Radius.sm), pressedScale = 1f, label = description)
                .semantics {
                    this.selected = selected
                    contentDescription = description
                }
                .padding(start = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (dirty) {
                    Box(Modifier.size(7.dp).background(colors.warning, CircleShape))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    name,
                    style = Pi.type.monoSmall,
                    color = when {
                        ended -> colors.ink3
                        selected -> colors.ink1
                        else -> colors.ink2
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp),
                )
            }
        }
        IconAction(Icons.Rounded.Close, closeDescription, onClick = onClose, size = 16.dp, tint = colors.ink3)
    }
}

/** The workspace's dropdown menus (project, files, viewer, terminal): surface3, radius 12, a lineStrong border. */
@Composable
fun WorkspaceMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val colors = Pi.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = colors.surface3,
        shape = RoundedCornerShape(Radius.md),
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, colors.lineStrong),
    ) { content() }
}

/** A 48dp menu row: a 20dp glyph slot (empty when [icon] is null, so labels line up) and the label in body. */
@Composable
fun WorkspaceMenuRow(text: String, icon: ImageVector?, color: Color, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, style = Pi.type.body, color = color) },
        leadingIcon = { Box(Modifier.size(20.dp)) { icon?.let { Icon(it, contentDescription = null, tint = color, modifier = Modifier.size(20.dp)) } } },
        onClick = onClick,
        modifier = Modifier.width(220.dp).heightIn(min = 48.dp),
    )
}
