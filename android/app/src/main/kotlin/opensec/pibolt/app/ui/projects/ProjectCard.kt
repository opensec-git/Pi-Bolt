package opensec.pibolt.app.ui.projects

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Terminal
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import opensec.pibolt.app.engine.SessionSummary
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.app.ui.components.QuadMath
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.SharedKeys
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.components.rememberSettledMode
import opensec.pibolt.app.ui.components.sharedHostBounds
import opensec.pibolt.app.ui.components.sharedHostElement
import opensec.pibolt.app.ui.session.TranscriptModel
import opensec.pibolt.app.ui.theme.Pi

/** What a project's menu can do. */
sealed interface ProjectAction {
    data class Rename(val entry: ProjectEntry) : ProjectAction

    data class Delete(val entry: ProjectEntry) : ProjectAction
}

/**
 * A project on Home, one row of the "Projects" group ([GroupRow]): the folder glyph (the live session's Quad while one
 * is open), the title (a 13dp pin when pinned) and the short time of its last activity, or "open". TalkBack reads the
 * full line ("3 sessions · 2 h ago"). Long press opens Pin, Rename and Delete (no overflow button on every row).
 */
@Composable
fun ProjectCard(
    entry: ProjectEntry,
    pos: GroupPos,
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
    val spoken = listOfNotNull(entry.title, "pinned".takeIf { entry.meta.pinned }, "session open".takeIf { live != null }, ProjectRules.projectMeta(entry, now)).joinToString(", ")
    Box(modifier) {
        GroupRow(
            pos = pos,
            interaction = Modifier
                .sharedHostBounds(SharedKeys.project(entry.name), card = true)
                .pressable(onClick, shape = groupShape(pos), pressedScale = 1f, onLongClick = { menu = true }, label = "Open project")
                .semantics(mergeDescendants = true) {
                    contentDescription = spoken
                    customActions = listOf(CustomAccessibilityAction("Project actions") { menu = true; true })
                },
            lead = {
                if (live != null) SessionMark(live) else Icon(Icons.Rounded.Folder, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(18.dp))
            },
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.title,
                    style = type.body,
                    color = colors.ink1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).sharedHostElement(SharedKeys.projectName(entry.name)),
                )
                if (entry.meta.pinned) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.PushPin, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(13.dp))
                }
            }
            val at = maxOf(entry.lastActive, entry.meta.lastOpenedAt)
            RowMeta(if (live != null) "open" else if (at > 0) ProjectRules.agoShort(at, now) else "new", color = if (live != null) colors.ink2 else colors.ink3)
        }
        ProjectMenu(menu, entry.meta.pinned, onDismiss = { menu = false }, onPin = onPin, onRename = { onAction(ProjectAction.Rename(entry)) }, onDelete = { onAction(ProjectAction.Delete(entry)) })
    }
}

/** The live session's Quad in its settled mode: animated while Pi works, warning while it waits for an answer. */
@Composable
fun SessionMark(live: SessionController, size: Dp = 16.dp) {
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
    QuadSpinner(rememberSettledMode(raw), size = size, color = if (dialog) colors.warning else if (busy) colors.ink1 else colors.ink3, animate = busy)
}

/**
 * A row of Home's "Continue" group (desktop "Recent"): the state slot (the live Quad, an accent dot for new
 * activity, else a quiet ink4 dot), the session title (ink2 when nothing is new, as on the desktop sidebar), the project
 * dimmed after it (it gives way first), and the short time.
 */
@Composable
fun ContinueRow(entry: ProjectEntry, session: SessionSummary, pos: GroupPos, live: SessionController?, unread: Boolean, now: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    val title = ProjectRules.sessionTitle(session)
    val state = when {
        live != null -> "open"
        unread -> "new activity"
        else -> null
    }
    val spoken = listOfNotNull(title, entry.title, ProjectRules.ago(maxOf(session.updatedAt, 0), now), state).joinToString(", ")
    GroupRow(
        pos = pos,
        modifier = modifier,
        interaction = Modifier
            .pressable(onClick, shape = groupShape(pos), pressedScale = 1f, label = "Resume session")
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        lead = {
            when {
                live != null -> SessionMark(live)
                unread -> StateDot(colors.accent)
                else -> StateDot(null)
            }
        },
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = if (unread) type.bodyStrong else type.body,
                color = if (unread || live != null) colors.ink1 else colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Text(entry.title, style = type.meta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.45f, fill = false))
        }
        RowMeta(ProjectRules.agoShort(maxOf(session.updatedAt, 0), now), color = if (unread) colors.ink2 else colors.ink3)
    }
}

@Composable
fun ProjectMenu(
    expanded: Boolean,
    pinned: Boolean,
    onDismiss: () -> Unit,
    onPin: (Boolean) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExport: (() -> Unit)? = null,
    onRuntimes: (() -> Unit)? = null,
    onSecrets: (() -> Unit)? = null,
) {
    val colors = Pi.colors
    WorkspaceMenu(expanded, onDismiss) {
        WorkspaceMenuRow(if (pinned) "Unpin" else "Pin to top", if (pinned) Icons.Outlined.PushPin else Icons.Rounded.PushPin, colors.ink1) {
            onDismiss()
            onPin(!pinned)
        }
        WorkspaceMenuRow("Rename", Icons.Rounded.DriveFileRenameOutline, colors.ink1) {
            onDismiss()
            onRename()
        }
        onExport?.let { export ->
            WorkspaceMenuRow("Export as zip", Icons.Rounded.Archive, colors.ink1) {
                onDismiss()
                export()
            }
        }
        onSecrets?.let { open ->
            WorkspaceMenuRow("Secrets", Icons.Rounded.Key, colors.ink1) {
                onDismiss()
                open()
            }
        }
        onRuntimes?.let { open ->
            WorkspaceMenuRow("Runtimes", Icons.Rounded.Terminal, colors.ink1) {
                onDismiss()
                open()
            }
        }
        WorkspaceMenuRow("Delete", Icons.Rounded.DeleteOutline, colors.danger) {
            onDismiss()
            onDelete()
        }
    }
}
