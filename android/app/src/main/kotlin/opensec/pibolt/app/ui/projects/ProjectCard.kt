package opensec.pibolt.app.ui.projects

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import opensec.pibolt.app.engine.SessionSummary
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.app.ui.components.QuadMath
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.InsetRow
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.SharedKeys
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.components.rememberSettledMode
import opensec.pibolt.app.ui.components.sharedHostBounds
import opensec.pibolt.app.ui.components.sharedHostElement
import opensec.pibolt.app.ui.session.TranscriptModel
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Space

/** What a project's menu can do. */
sealed interface ProjectAction {
    data class Rename(val entry: ProjectEntry) : ProjectAction

    data class Delete(val entry: ProjectEntry) : ProjectAction
}

/**
 * A project on Home, one [InsetRow] of "Projects": the live session's Quad while one is open, else a still ink4 Quad
 * (the app's dot-matrix bullet, never a folder glyph or a letter tile); the title; "3 sessions · 2h" (or "Open"
 * with a session open) under it; a 14dp pin when pinned; the chevron. Long press (and a TalkBack action) opens Pin,
 * Rename and Delete.
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
    divider: Boolean = true,
) {
    val colors = Pi.colors
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        InsetRow(
            title = entry.title,
            meta = if (live != null) "Open · " + ProjectRules.projectMetaParts(entry, now).first else ProjectRules.projectMeta(entry, now),
            onClick = onClick,
            onLongClick = { menu = true },
            label = "Open project",
            divider = divider,
            chevron = true,
            modifier = Modifier
                .sharedHostBounds(SharedKeys.project(entry.name), card = true)
                .semantics { customActions = listOf(CustomAccessibilityAction("Project actions") { menu = true; true }) },
            titleModifier = Modifier.sharedHostElement(SharedKeys.projectName(entry.name)),
            leading = { if (live != null) SessionMark(live) else RestMark() },
            trailing = {
                if (entry.meta.pinned) Icon(Icons.Rounded.PushPin, contentDescription = "Pinned", tint = colors.ink3, modifier = Modifier.size(14.dp))
            },
        )
        ProjectMenu(menu, entry.meta.pinned, onDismiss = { menu = false }, onPin = onPin, onRename = { onAction(ProjectAction.Rename(entry)) }, onDelete = { onAction(ProjectAction.Delete(entry)) })
    }
}

/** A row's Quad at rest: one still frame in ink4, the bullet of Home's lists. Decoration only. */
@Composable
fun RestMark(size: Dp = 16.dp) {
    Box(Modifier.clearAndSetSemantics { }) { QuadSpinner(QuadMode.Think, size = size, color = Pi.colors.ink4, animate = false) }
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
 * A session of Home's "Recents" ([InsetRow]): the live Quad while it is open (on the soft current highlight), else the
 * still bullet; the session's name or first prompt; "calc-parser · 3h" under it; an accent dot when something new
 * happened there.
 */
@Composable
fun ContinueRow(entry: ProjectEntry, session: SessionSummary, live: SessionController?, unread: Boolean, now: Long, onClick: () -> Unit, modifier: Modifier = Modifier, divider: Boolean = true) {
    val title = ProjectRules.sessionTitle(session)
    val ago = ProjectRules.agoShort(maxOf(session.updatedAt, 0), now)
    InsetRow(
        title = title,
        meta = if (live != null) "Open · ${entry.title}" else "${entry.title} · $ago",
        onClick = onClick,
        label = "Resume session",
        unread = unread,
        current = live != null,
        divider = divider,
        modifier = modifier,
        leading = { if (live != null) SessionMark(live) else RestMark() },
    )
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
