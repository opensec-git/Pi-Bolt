package opensec.pibolt.app.ui.session.features2

import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import opensec.pibolt.app.engine.EngineApi
import opensec.pibolt.app.engine.SessionSummary
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.app.ui.components.ConfirmSheet
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.PiTextField
import opensec.pibolt.app.ui.components.Segmented
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.projects.ProjectRules
import opensec.pibolt.app.ui.session.ActionsSheet
import opensec.pibolt.app.ui.session.SheetAction
import opensec.pibolt.app.ui.session.TextPromptSheet
import opensec.pibolt.app.ui.theme.Pi
import java.io.File

/**
 * The project's saved sessions (the terminal's /resume picker): search by name, or inside messages; sort by recent,
 * name or size; open, rename or delete one. [live] is the session open in the engine now: it is renamed over RPC and
 * cannot be deleted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionBrowserSheet(
    engine: EngineApi,
    project: File,
    live: SessionController?,
    onOpen: (SessionSummary) -> Unit,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var all by remember { mutableStateOf<List<BrowsedSession>?>(null) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SessionSort.Recent) }
    var inside by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<ContentHit>?>(null) }
    var actionsFor by remember { mutableStateOf<BrowsedSession?>(null) }
    var renaming by remember { mutableStateOf<BrowsedSession?>(null) }
    var deleting by remember { mutableStateOf<BrowsedSession?>(null) }
    val liveFile = live?.sessionFile
    val now = remember(all) { System.currentTimeMillis() }

    LaunchedEffect(reload) {
        all = withContext(Dispatchers.IO) {
            runCatching { engine.sessions(project).map { BrowsedSession(it, File(it.file).length()) } }.getOrDefault(emptyList())
        }
    }
    LaunchedEffect(query, inside, all) {
        val list = all ?: return@LaunchedEffect
        if (!inside || query.trim().length < 2) {
            hits = null
            return@LaunchedEffect
        }
        hits = null
        delay(250)
        hits = withContext(Dispatchers.IO) { SessionBrowse.search(list, query) }
    }

    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader("Sessions", subtitle = all?.let { "${it.size} in ${project.name} · ${SessionBrowse.size(it.sumOf { s -> s.bytes })}" })
        PiTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = if (inside) "Search inside messages" else "Filter by name or first prompt",
            singleLine = true,
            leading = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Pi.colors.ink3) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        )
        // Two rows, not one: side by side at 360dp, "Messages" lost its last letters at font scale 1.3. 48dp, as forms.
        Segmented(
            options = listOf(false to "Names", true to "Messages"),
            selected = inside,
            onSelect = { inside = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            height = 48.dp,
        )
        Segmented(
            options = SessionSort.entries.map { it to it.label },
            selected = sort,
            onSelect = { sort = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            height = 48.dp,
        )
        val list = all
        when {
            list == null -> F2Loading("Loading sessions")
            list.isEmpty() -> F2Empty("No saved sessions yet", Icons.AutoMirrored.Rounded.Chat)
            inside && query.trim().length >= 2 -> {
                val found = hits
                when {
                    found == null -> F2Loading("Searching ${list.size} sessions")
                    found.isEmpty() -> F2Empty("No message mentions “${query.trim()}”", Icons.Rounded.SearchOff)
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                        items(found, key = { it.session.summary.file }) { hit ->
                            val s = hit.session
                            F2Row(
                                title = s.title,
                                meta = "${hit.count} ${if (hit.count == 1) "match" else "matches"} · ${hit.snippet}",
                                icon = Icons.AutoMirrored.Rounded.Chat,
                                onClick = { close { onOpen(s.summary) } },
                                onLongClick = { actionsFor = s },
                                titleMaxLines = 1,
                            )
                        }
                    }
                }
            }
            else -> {
                val shown = remember(list, sort, query) { SessionBrowse.arrange(list, sort, if (inside) "" else query) }
                if (shown.isEmpty()) F2Empty("No session matches “${query.trim()}”", Icons.Rounded.SearchOff)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    items(shown, key = { it.summary.file }) { s ->
                        val open = s.summary.file == liveFile
                        val meta = buildList {
                            if (open) add("Open")
                            add(if (s.summary.messageCount == 1) "1 message" else "${s.summary.messageCount} messages")
                            add(SessionBrowse.size(s.bytes))
                            add(ProjectRules.ago(s.summary.updatedAt, now))
                        }.joinToString(" · ")
                        F2Row(
                            title = ProjectRules.sessionTitle(s.summary),
                            meta = meta,
                            icon = Icons.AutoMirrored.Rounded.Chat,
                            iconTint = if (open) Pi.colors.accent else Pi.colors.ink3,
                            onClick = { if (open) close(onDismiss) else close { onOpen(s.summary) } },
                            onLongClick = { actionsFor = s },
                        ) {
                            IconAction(Icons.Rounded.MoreVert, "Actions for ${ProjectRules.sessionTitle(s.summary)}", onClick = { actionsFor = s })
                        }
                    }
                }
            }
        }
    }

    actionsFor?.let { s ->
        val open = s.summary.file == liveFile
        ActionsSheet(
            title = ProjectRules.sessionTitle(s.summary),
            actions = listOfNotNull(
                SheetAction("Open", Icons.AutoMirrored.Rounded.OpenInNew) { close { onOpen(s.summary) } }.takeIf { !open },
                SheetAction("Rename", Icons.Rounded.DriveFileRenameOutline) { renaming = s },
                SheetAction("Delete", Icons.Rounded.Delete) { deleting = s }.takeIf { !open },
            ),
            onDismiss = { actionsFor = null },
        )
    }
    renaming?.let { s ->
        TextPromptSheet(
            title = "Rename session",
            message = null,
            initial = s.summary.name.orEmpty(),
            placeholder = "Session name",
            confirm = "Rename",
            onConfirm = { name ->
                renaming = null
                val trimmed = name.trim()
                if (trimmed.isEmpty()) return@TextPromptSheet
                if (s.summary.file == live?.sessionFile) {
                    live.rename(trimmed)
                    scope.launch {
                        delay(300)
                        reload++
                    }
                } else {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { SessionBrowse.rename(File(s.summary.file), trimmed) }
                        if (!ok) onMessage("Could not rename that session")
                        reload++
                    }
                }
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { s ->
        ConfirmSheet(
            title = "Delete this session?",
            message = "\"${ProjectRules.sessionTitle(s.summary)}\" and its ${s.summary.messageCount} messages are removed from this phone. This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                deleting = null
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { File(s.summary.file).delete() }
                    onMessage(if (ok) "Session deleted" else "Could not delete that session")
                    reload++
                }
            },
            onDismiss = { deleting = null },
        )
    }
}
