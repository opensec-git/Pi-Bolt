package opensec.pibolt.app.ui.session

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.app.ui.components.InsetRow
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.projects.ProjectRules
import opensec.pibolt.app.ui.session.features2.F2Empty
import opensec.pibolt.app.ui.session.features2.F2Loading
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.protocol.rpc.RemoteProject
import opensec.pibolt.protocol.rpc.RemoteSessionInfo

/**
 * The computer's projects and their sessions, from its background service (`host.service`): pick a project, then one
 * of its sessions or "New session". The pick opens on the computer (`switch_session` / `new_session`), and its
 * `remote_hello` replaces this transcript. [onDismiss] runs on every close, a pick's too: hiding the sheet alone left it
 * composed, so the next tap on Projects opened nothing. A service with `host.parallelSessions` keeps a running session
 * working meanwhile (its row says "Running"; picking it again shows it); an older one cannot, so while this session
 * works the pick is refused with a message (SessionController.switchRefusal) instead of stopping the run.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteSessionsSheet(controller: SessionController, onDismiss: () -> Unit) {
    val session = controller.state.collectAsStateWithLifecycle()
    // Only the open session's id: streamed frames do not recompose the sheet.
    val currentSessionId by remember { derivedStateOf { session.value.sessionId } }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    val colors = Pi.colors
    val now = System.currentTimeMillis()
    var project by remember { mutableStateOf<RemoteProject?>(null) }
    // null while loading; a failure keeps its message.
    var projects by remember { mutableStateOf<Result<List<RemoteProject>>?>(null) }
    var sessions by remember { mutableStateOf<Result<List<RemoteSessionInfo>>?>(null) }
    LaunchedEffect(Unit) { projects = controller.listProjects() }
    LaunchedEffect(project) {
        sessions = null
        project?.let { sessions = controller.listSessions(it.cwd) }
    }
    PiSheet(onDismiss = onDismiss, state = state) {
        val open = project
        if (open == null) {
            SheetHeader("Projects on this computer")
            val list = projects
            when {
                list == null -> F2Loading("Reading projects")
                list.isFailure -> F2Empty(list.exceptionOrNull()?.message ?: "Could not list projects", Icons.Rounded.Folder)
                list.getOrThrow().isEmpty() -> F2Empty("No projects yet. Start Pi-Bolt in a folder on the computer first.", Icons.Rounded.Folder)
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    items(list.getOrThrow(), key = { it.cwd }) { p ->
                        InsetRow(
                            title = p.name,
                            meta = listOf(count(p.sessions, "session"), ProjectRules.ago(p.updatedAtMs, now)).joinToString(" · "),
                            onClick = { project = p },
                            chevron = true,
                            leading = { Icon(Icons.Rounded.Folder, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(IconSize.md)) },
                        )
                    }
                }
            }
            return@PiSheet
        }
        BackHandler { project = null }
        SheetHeader(open.name, subtitle = open.cwd)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            item(key = "back") {
                InsetRow(
                    title = "All projects",
                    onClick = { project = null },
                    leading = { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(IconSize.md)) },
                )
            }
            item(key = "new") {
                InsetRow(
                    title = "New session",
                    onClick = { close { onDismiss(); controller.newSessionIn(open.cwd) } },
                    leading = { Icon(Icons.Rounded.Add, contentDescription = null, tint = colors.accent, modifier = Modifier.size(IconSize.md)) },
                )
            }
            val list = sessions
            when {
                list == null -> item(key = "loading") { F2Loading("Reading sessions") }
                list.isFailure -> item(key = "error") { F2Empty(list.exceptionOrNull()?.message ?: "Could not list sessions", Icons.AutoMirrored.Rounded.Chat) }
                list.getOrThrow().isEmpty() -> item(key = "empty") { F2Empty("No saved sessions in this project.", Icons.AutoMirrored.Rounded.Chat) }
                else -> items(list.getOrThrow(), key = { it.path }) { s ->
                    val current = s.id.isNotEmpty() && s.id == currentSessionId
                    val meta = listOfNotNull(
                        "Open".takeIf { current },
                        "Running".takeIf { s.running && !current },
                        count(s.messageCount, "message"),
                        ProjectRules.ago(s.updatedAtMs, now),
                    ).joinToString(" · ")
                    InsetRow(
                        title = s.title,
                        meta = meta,
                        current = current,
                        onClick = { if (current) close(onDismiss) else close { onDismiss(); controller.switchSession(s.path) } },
                        leading = { Icon(Icons.AutoMirrored.Rounded.Chat, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(IconSize.md)) },
                    )
                }
            }
        }
    }
}

private fun count(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"
