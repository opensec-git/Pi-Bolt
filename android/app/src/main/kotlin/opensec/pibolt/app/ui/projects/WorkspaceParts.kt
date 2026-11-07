package opensec.pibolt.app.ui.projects

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import opensec.pibolt.app.ui.components.glass
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.PiMenu
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion

/**
 * One tab of the workspace's tab strips (open files in the editor, shells in the terminal): a 36dp capsule drawn
 * inside a 48dp row; the shown tab is glass (gradient, ring and top highlight, fading in), the others have no fill or
 * border. A 7dp warning dot when [dirty], the name in monoSmall (ink1 shown, ink3 otherwise and for an ended shell; at
 * most 160dp) and a 16dp close glyph. The name and the close button each have a 48dp-tall target. The shown tab
 * scrolls itself into view (a file opened from Files, a new shell), so it is never off the strip's edge.
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
    val shown by animateFloatAsState(if (selected) 1f else 0f, PiMotion.effects(), label = "tab-glass")
    val inView = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) { if (selected) runCatching { inView.bringIntoView() } }
    Box(modifier.height(48.dp).bringIntoViewRequester(inView)) {
        Box(
            Modifier
                .matchParentSize()
                .padding(vertical = 6.dp)
                .graphicsLayer { alpha = shown }
                .glass(CircleShape, colors),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .pressable(onSelect, shape = CircleShape, pressedScale = 1f, label = description)
                    .semantics {
                        this.selected = selected
                        contentDescription = description
                    }
                    .padding(start = 14.dp),
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
                        color = if (selected && !ended) colors.ink1 else colors.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp),
                    )
                }
            }
            IconAction(Icons.Rounded.Close, closeDescription, onClick = onClose, size = 16.dp, tint = colors.ink3, modifier = Modifier.fillMaxHeight())
        }
    }
}

/** The workspace's dropdown menus (project, files, viewer, terminal): the kit's [PiMenu]. */
@Composable
fun WorkspaceMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    PiMenu(expanded = expanded, onDismiss = onDismiss) { content() }
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
