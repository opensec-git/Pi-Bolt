package opensec.pibolt.app.ui.projects

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.QuadMath
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.SharedKeys
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.components.rememberSettledMode
import opensec.pibolt.app.ui.components.sharedHostBounds
import opensec.pibolt.app.ui.components.sharedHostElement
import opensec.pibolt.app.ui.session.TranscriptModel
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius

/** What a project's menu can do. */
sealed interface ProjectAction {
    data class Rename(val entry: ProjectEntry) : ProjectAction

    data class Delete(val entry: ProjectEntry) : ProjectAction
}

/**
 * A project folder on Home: a 36dp tile (folder glyph, or the live session's Quad), the title, and the last
 * session with when it was active. Long press or the overflow opens Pin, Rename and Delete.
 */
@Composable
fun ProjectCard(
    entry: ProjectEntry,
    live: SessionController?,
    now: Long,
    onClick: () -> Unit,
    onPin: (Boolean) -> Unit,
    onAction: (ProjectAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Pi.colors
    val type = Pi.type
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radius.md)
    val lastTitle = entry.lastSession?.let(ProjectRules::sessionTitle) ?: "No sessions yet"
    val ago = if (entry.lastActive > 0) ProjectRules.ago(entry.lastActive, now) else null
    Row(
        modifier
            .fillMaxWidth()
            .height(Pi.dims.hostCard)
            .sharedHostBounds(SharedKeys.project(entry.name), card = true)
            .pressable(onClick, shape = shape, onLongClick = { menu = true })
            .background(colors.surface1, shape)
            .border(1.dp, colors.line, shape)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProjectTile(live)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.title,
                    style = type.bodyStrong,
                    color = colors.ink1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).sharedHostElement(SharedKeys.projectName(entry.name)),
                )
                if (entry.meta.pinned) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.PushPin, contentDescription = "Pinned", tint = colors.ink3, modifier = Modifier.size(13.dp))
                }
            }
            Spacer(Modifier.height(2.dp))
            // The session title gives way; when it was active always stays readable.
            Row {
                Text(lastTitle, style = type.meta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (ago != null) Text(" · $ago", style = type.meta, color = colors.ink3, maxLines = 1)
            }
        }
        Box {
            IconAction(Icons.Rounded.MoreVert, "More", onClick = { menu = true })
            ProjectMenu(menu, entry.meta.pinned, onDismiss = { menu = false }, onPin = onPin, onRename = { onAction(ProjectAction.Rename(entry)) }, onDelete = { onAction(ProjectAction.Delete(entry)) })
        }
    }
}

/** Folder glyph on surface2; the live session's Quad (animated while it works) with a status pip when open. */
@Composable
private fun ProjectTile(live: SessionController?) {
    val colors = Pi.colors
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.size(40.dp).background(colors.surface2, shape).border(1.dp, colors.line, shape), contentAlignment = Alignment.Center) {
        if (live == null) {
            Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(20.dp))
        } else {
            LiveQuad(live)
        }
    }
}

/** Reads only what the tile shows from the live session, so streamed frames do not recompose the card. */
@Composable
private fun LiveQuad(live: SessionController) {
    val colors = Pi.colors
    val state = live.state.collectAsStateWithLifecycle()
    val busy by remember { derivedStateOf { state.value.isRunning || state.value.isCompacting || state.value.dialogs.isNotEmpty() } }
    val dialog by remember { derivedStateOf { state.value.dialogs.isNotEmpty() } }
    val raw by remember {
        derivedStateOf {
            val s = state.value
            val a = TranscriptModel.activity(s.items, s.streaming)
            QuadMath.modeFor(s.dialogs.isNotEmpty(), s.isCompacting, a.subagent, a.toolRunning, a.writing, connected = true)
        }
    }
    val mode = rememberSettledMode(raw)
    Box(contentAlignment = Alignment.Center) {
        QuadSpinner(mode, size = 20.dp, color = if (dialog) colors.warning else if (busy) colors.ink1 else colors.ink3, animate = busy)
    }
    Box(
        Modifier
            .offset(15.dp, (-15).dp)
            .size(10.dp)
            .background(colors.surface2, CircleShape)
            .padding(2.dp)
            .background(if (dialog) colors.warning else colors.success, CircleShape),
    )
}

@Composable
fun ProjectMenu(expanded: Boolean, pinned: Boolean, onDismiss: () -> Unit, onPin: (Boolean) -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val colors = Pi.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = colors.surface3,
        shape = RoundedCornerShape(Radius.md),
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, colors.lineStrong),
    ) {
        MenuItem(if (pinned) "Unpin" else "Pin to top", if (pinned) Icons.Outlined.PushPin else Icons.Rounded.PushPin, colors.ink1) {
            onDismiss()
            onPin(!pinned)
        }
        MenuItem("Rename", Icons.Rounded.DriveFileRenameOutline, colors.ink1) {
            onDismiss()
            onRename()
        }
        MenuItem("Delete", Icons.Rounded.DeleteOutline, colors.danger) {
            onDismiss()
            onDelete()
        }
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, style = Pi.type.body, color = color) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
        modifier = Modifier.width(200.dp),
    )
}
