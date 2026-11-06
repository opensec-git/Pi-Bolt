package opensec.pibolt.app.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import opensec.pibolt.app.ui.components.Segmented
import opensec.pibolt.app.ui.session.Viewer
import opensec.pibolt.app.ui.session.ViewerContent
import opensec.pibolt.app.ui.theme.PiMotion
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.data.ProjectMeta
import opensec.pibolt.app.data.ProjectStore
import opensec.pibolt.app.engine.EngineStatus
import opensec.pibolt.app.engine.SessionSummary
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.PiSnackHost
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.QuadMath
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.SharedKeys
import opensec.pibolt.app.ui.components.SnackKind
import opensec.pibolt.app.ui.components.StatusBarScrim
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.components.rememberSettledMode
import opensec.pibolt.app.ui.components.sharedHostBounds
import opensec.pibolt.app.ui.components.sharedHostElement
import opensec.pibolt.app.ui.components.show
import opensec.pibolt.app.ui.session.TranscriptModel
import opensec.pibolt.app.ui.session.features2.SessionBrowserSheet
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius
import java.io.File

/**
 * One project: its saved sessions (newest first), "New session", tap to resume. The live session, if it belongs
 * here, is marked and resumes without reopening. Opens with shared bounds from the project card on Home.
 */
@Composable
fun ProjectScreen(app: PiBoltApp, name: String, onBack: () -> Unit, onOpenSession: () -> Unit) {
    val engine = app.engine
    val store = remember { ProjectStore.get(app) }
    val projects = remember(engine) { Projects(engine, store) }
    val colors = Pi.colors
    val type = Pi.type
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val dir = remember(name) { File(engine.projectsDir, name) }
    val meta by store.state.collectAsStateWithLifecycle()
    val pm = meta.projects[name] ?: ProjectMeta()
    val title = pm.title ?: name
    val status by engine.status.collectAsStateWithLifecycle()
    val live by LocalSessions.current.collectAsStateWithLifecycle()
    val liveHere = live?.takeIf { it.project.name == name && !it.controller.stopped }

    var reload by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reload++ }
    val sessions by produceState<List<SessionSummary>?>(null, reload, live) { value = projects.sessions(dir) }
    var menu by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf<ProjectAction?>(null) }
    var showFiles by rememberSaveable { mutableStateOf(false) }
    // Folder shown in Files, relative to the project ("" is its root).
    var path by rememberSaveable { mutableStateOf("") }
    var viewer by remember { mutableStateOf<ViewerContent?>(null) }
    val branch by produceState<String?>(null, dir) { value = ProjectFiles.branch(dir) }
    val folder = remember(dir, path) { if (path.isEmpty()) dir else File(dir, path) }
    val files by produceState<List<FileEntry>?>(null, folder, showFiles, reload) { if (showFiles) value = ProjectFiles.list(folder) }
    var opening by remember { mutableStateOf(false) }
    var browse by remember { mutableStateOf(false) }
    val unavailable = status is EngineStatus.Unavailable
    val now = remember(sessions) { System.currentTimeMillis() }

    fun open(file: String?, fresh: Boolean) {
        if (opening) return
        val current = liveHere
        if (!fresh && current != null && file != null && current.sessionFile == file) {
            onOpenSession()
            return
        }
        opening = true
        scope.launch {
            LocalSessions.open(engine, store, dir, sessionFile = file, fresh = fresh)
                .onSuccess { onOpenSession() }
                .onFailure { snackbar.show(it.message ?: "Could not open the session", SnackKind.Error) }
            opening = false
        }
    }

    val entry = ProjectEntry(name, dir.absolutePath, pm, sessions?.firstOrNull(), sessions?.size ?: 0)
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        Modifier
            .fillMaxSize()
            .sharedHostBounds(SharedKeys.project(name), card = false)
            .background(colors.canvas),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp + bottom)) {
            item(key = "top", contentType = "top") {
                Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Projects", onClick = onBack, tint = colors.ink1)
                    Spacer(Modifier.weight(1f))
                    // Sort, filter, search inside messages, rename and delete (features2).
                    IconAction(Icons.Rounded.Search, "Search and manage sessions", onClick = { browse = true }, enabled = !unavailable)
                    Box {
                        IconAction(Icons.Rounded.MoreVert, "More", onClick = { menu = true })
                        ProjectMenu(
                            menu,
                            pm.pinned,
                            onDismiss = { menu = false },
                            onPin = { store.setPinned(name, it) },
                            onRename = { action = ProjectAction.Rename(entry) },
                            onDelete = { action = ProjectAction.Delete(entry) },
                        )
                    }
                }
            }
            item(key = "head", contentType = "head") {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            style = type.display,
                            color = colors.ink1,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).sharedHostElement(SharedKeys.projectName(name)),
                        )
                        if (pm.pinned) {
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Rounded.PushPin, contentDescription = "Pinned", tint = colors.ink3, modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val shape = RoundedCornerShape(Radius.sm)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "projects/$name",
                            style = type.monoMeta,
                            color = colors.ink2,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                            modifier = Modifier.weight(1f, fill = false).background(colors.surface1, shape).border(1.dp, colors.line, shape).padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                        branch?.let {
                            Spacer(Modifier.width(10.dp))
                            Icon(Icons.AutoMirrored.Rounded.CallSplit, contentDescription = "Branch", tint = colors.ink3, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(it, style = type.monoMeta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    PrimaryButton(
                        if (opening) "Opening…" else "New session",
                        onClick = { open(null, fresh = true) },
                        icon = Icons.Rounded.Add,
                        enabled = !unavailable && !opening,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (unavailable) {
                        Text("Pi-Bolt can't run on this phone, so sessions here can't be opened.", style = type.meta, color = colors.ink3, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            item(key = "tabs", contentType = "tabs") {
                Segmented(
                    options = listOf(false to (sessions?.size?.let { "Sessions · $it" } ?: "Sessions"), true to "Files"),
                    selected = showFiles,
                    onSelect = { showFiles = it },
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
                    height = 40.dp,
                )
            }
            if (showFiles) {
                if (path.isNotEmpty()) {
                    item(key = "up", contentType = "up") {
                        Row(
                            Modifier
                                .padding(horizontal = 16.dp)
                                .fillMaxWidth()
                                .height(48.dp)
                                .pressable({ path = path.substringBeforeLast('/', "") }, shape = RoundedCornerShape(Radius.sm), pressedScale = 1f, label = "Up one folder")
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(14.dp))
                            Text(path, style = type.monoSmall, color = colors.ink2, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        }
                    }
                }
                val entries = files
                if (entries != null && entries.isEmpty()) {
                    item(key = "files-empty", contentType = "empty") {
                        Text("This folder is empty.", style = type.body, color = colors.ink3, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    }
                }
                if (entries != null) {
                    items(entries, key = { "f:" + it.name }, contentType = { "file" }) { entry ->
                        FileRow(entry, folder, onClick = {
                            val rel = if (path.isEmpty()) entry.name else path + "/" + entry.name
                            if (entry.dir) {
                                path = rel
                            } else {
                                scope.launch { viewer = ProjectFiles.view(File(dir, rel), rel) }
                            }
                        }, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
            val list = sessions
            if (list != null && !showFiles) {
                if (list.isEmpty()) {
                    item(key = "empty", contentType = "empty") {
                        Text(
                            "No sessions yet. Start one and ask Pi to look around, set the project up, or clone a repository.",
                            style = type.body,
                            color = colors.ink3,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                }
                // The engine's own session file: a new session or a fork is not the file the session was opened with.
                val liveFile = liveHere?.controller?.sessionFile ?: liveHere?.sessionFile
                items(list, key = { it.file }, contentType = { "session" }) { s ->
                    val isLive = liveHere != null && (if (liveFile != null) liveFile == s.file else s == list.first())
                    SessionRow(
                        s,
                        live = if (isLive) liveHere?.controller else null,
                        now = now,
                        enabled = !unavailable,
                        onClick = { if (isLive) onOpenSession() else open(s.file, fresh = false) },
                        modifier = Modifier.animateItem().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }
        StatusBarScrim()
        PiSnackHost(snackbar, Modifier.align(Alignment.BottomCenter))

        AnimatedVisibility(
            visible = viewer != null,
            enter = fadeIn(PiMotion.enter(PiMotion.SHEET)) + slideInVertically(PiMotion.enter(PiMotion.SHEET)) { it / 10 },
            exit = fadeOut(PiMotion.leave(PiMotion.SHEET)) + scaleOut(PiMotion.leave(PiMotion.SHEET), targetScale = 0.94f),
        ) {
            val shown = remember { mutableStateOf(viewer) }
            viewer?.let { shown.value = it }
            shown.value?.let { Viewer(it, onClose = { viewer = null }) }
        }
    }
    // Back inside Files goes up a folder first.
    BackHandler(enabled = showFiles && path.isNotEmpty() && viewer == null) { path = path.substringBeforeLast('/', "") }

    if (browse) {
        SessionBrowserSheet(
            engine = engine,
            project = dir,
            live = liveHere?.controller,
            onOpen = {
                browse = false
                open(it.file, fresh = false)
            },
            onMessage = { m -> scope.launch { snackbar.show(m) } },
            onDismiss = {
                browse = false
                reload++
            },
        )
    }

    ProjectActionSheets(
        action = action,
        projects = projects,
        store = store,
        scope = app.appScope,
        onDone = { action = null },
        onDeleted = { onBack() },
        onError = { scope.launch { snackbar.show(it, SnackKind.Error) } },
    )
}

/** 64dp row: chat glyph (or the live Quad), the session's name or first prompt, "24 messages · 2 h ago". */
@Composable
private fun SessionRow(s: SessionSummary, live: SessionController?, now: Long, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    val shape = RoundedCornerShape(Radius.md)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .pressable(onClick, shape = shape, enabled = enabled)
            .background(colors.surface1, shape)
            .border(1.dp, if (live != null) colors.lineStrong else colors.line, shape)
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (live != null) {
                LiveMark(live)
            } else {
                Icon(Icons.AutoMirrored.Rounded.Chat, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(ProjectRules.sessionTitle(s), style = type.body, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            val parts = buildList {
                if (live != null) add("Open")
                if (s.messageCount > 0) add(if (s.messageCount == 1) "1 message" else "${s.messageCount} messages")
                add(ProjectRules.ago(s.updatedAt, now))
            }
            Text(parts.joinToString(" · "), style = type.meta, color = colors.ink3, maxLines = 1)
        }
    }
}

@Composable
private fun LiveMark(live: SessionController) {
    val colors = Pi.colors
    val state = live.state.collectAsStateWithLifecycle()
    val busy by remember { derivedStateOf { state.value.isRunning || state.value.isCompacting || state.value.dialogs.isNotEmpty() } }
    val raw by remember {
        derivedStateOf {
            val st = state.value
            val a = TranscriptModel.activity(st.items, st.streaming)
            QuadMath.modeFor(st.dialogs.isNotEmpty(), st.isCompacting, a.subagent, a.toolRunning, a.writing, connected = true)
        }
    }
    QuadSpinner(rememberSettledMode(raw), size = 16.dp, color = if (busy) colors.ink1 else colors.success, animate = busy)
}
