package opensec.pibolt.app.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import opensec.pibolt.app.terminal.Terminals
import opensec.pibolt.app.secrets.SecretStore
import opensec.pibolt.app.ui.components.SecondaryButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import opensec.pibolt.app.ui.projects.transfer.ExportSheet
import opensec.pibolt.app.ui.projects.transfer.SafTransfer
import opensec.pibolt.app.ui.projects.transfer.TransferBar
import opensec.pibolt.app.ui.projects.transfer.TransferRunner
import opensec.pibolt.app.ui.session.features.ShareFiles
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import opensec.pibolt.app.ui.git.ChangesOverlays
import opensec.pibolt.app.ui.git.changesItems
import opensec.pibolt.app.ui.git.rememberChangesUi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.semantics.semantics
import opensec.pibolt.app.ui.theme.PiMotion
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.data.ProjectMeta
import opensec.pibolt.app.data.ProjectStore
import opensec.pibolt.app.engine.EngineStatus
import opensec.pibolt.app.engine.SessionSummary
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.derivedStateOf
import opensec.pibolt.app.ui.components.GlassBar
import opensec.pibolt.app.ui.components.GlassIconButton
import opensec.pibolt.app.ui.components.InsetSectionHead
import opensec.pibolt.app.ui.components.glass
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Space
import opensec.pibolt.app.ui.components.LoadingRow
import opensec.pibolt.app.ui.components.PiSnackHost
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.SharedKeys
import opensec.pibolt.app.ui.components.SnackKind
import opensec.pibolt.app.ui.components.StatusBarScrim
import opensec.pibolt.app.ui.components.sharedHostBounds
import opensec.pibolt.app.ui.components.sharedHostElement
import opensec.pibolt.app.ui.components.show
import opensec.pibolt.app.ui.projects.editor.EditorTabs
import opensec.pibolt.app.ui.projects.editor.EditorTabsHolder
import androidx.lifecycle.viewmodel.compose.viewModel
import opensec.pibolt.app.ui.components.ConfirmSheet
import opensec.pibolt.app.ui.session.features2.SessionBrowserSheet
import opensec.pibolt.app.ui.terminal.TerminalPane
import opensec.pibolt.app.ui.theme.Pi
import java.io.File

/**
 * One project: its saved sessions (newest first), "New session", tap to resume. The live session, if it belongs
 * here, is marked and resumes without reopening. Opens with shared bounds from the project card on Home.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ProjectScreen(
    app: PiBoltApp,
    name: String,
    onBack: () -> Unit,
    /** Shows a live session of this project ([LocalSessions.Live.key]). */
    onOpenSession: (String) -> Unit,
    onTerminal: () -> Unit = {},
    onSecrets: () -> Unit = {},
    onPreview: (Int) -> Unit = {},
    onRuntimes: () -> Unit = {},
) {
    val engine = app.engine
    val store = remember { ProjectStore.get(app) }
    val projects = remember(engine) { Projects(engine, store, SecretStore.get(app)) }
    val colors = Pi.colors
    val type = Pi.type
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val dir = remember(name) { File(engine.projectsDir, name) }
    val meta by store.state.collectAsStateWithLifecycle()
    val pm = meta.projects[name] ?: ProjectMeta()
    val title = pm.title ?: name
    val status by engine.status.collectAsStateWithLifecycle()
    // This project's own live session (others may run in other projects meanwhile).
    val liveAll by LocalSessions.all.collectAsStateWithLifecycle()
    val live = liveAll[name]
    // Every live session here (another opens beside a busy one); the front one is the one opened last.
    val lives = remember(liveAll, name) { liveAll.values.filter { it.project.name == name && !it.controller.stopped } }
    val liveHere = live?.takeIf { !it.controller.stopped } ?: lives.firstOrNull()

    var reload by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reload++ }
    val sessions by produceState<List<SessionSummary>?>(null, reload, live) { value = projects.sessions(dir) }
    var menu by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf<ProjectAction?>(null) }
    // ProjectTab.index: 0 sessions, 1 files, 2 changes (git), 3 terminal.
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val showFiles = tab == 1
    val changes = rememberChangesUi(app, dir, store, snackbar)
    // Folder shown in Files, relative to the project ("" is its root).
    var path by rememberSaveable { mutableStateOf("") }
    // The file open in the viewer, relative to the project, and the editor's tabs (open files).
    var openFile by rememberSaveable { mutableStateOf<String?>(null) }
    // Kept by the screen's navigation entry, so unsaved drafts survive "Ask Pi", the terminal, the secrets or a
    // session opened over the project, and rotation.
    val tabsHolder = viewModel(key = "editor-tabs:$name") { EditorTabsHolder() }
    val tabs = rememberSaveable(name, saver = EditorTabs.saver(tabsHolder)) { tabsHolder.tabs ?: EditorTabs().also { tabsHolder.tabs = it } }
    var leaving by remember { mutableStateOf(false) }
    // Leaving the project with unsaved edits asks first. Registered before the screen's other back handlers, so those
    // (a folder up, an open file, a diff) still come first.
    BackHandler(enabled = tabs.anyDirty && !leaving) { leaving = true }
    var sort by rememberSaveable { mutableStateOf(FileSort.Name) }
    var showHidden by rememberSaveable { mutableStateOf(false) }
    var fileAction by remember { mutableStateOf<FileAction?>(null) }
    // Read again after a switch in the Changes tab, so the header and the session rows name the branch checked out.
    val branch by produceState<String?>(null, dir, changes.model.status?.branch?.head, changes.model.status?.branch?.oid) { value = ProjectFiles.branch(dir) }
    val folder = remember(dir, path) { if (path.isEmpty()) dir else File(dir, path) }
    val files by produceState<List<FileEntry>?>(null, folder, showFiles, reload) { if (showFiles) value = ProjectFiles.list(folder) }
    val shownFiles = remember(files, sort, showHidden) { files?.let { ProjectFiles.visible(ProjectFiles.order(it, sort), showHidden) } }
    var opening by remember { mutableStateOf(false) }
    var browse by remember { mutableStateOf(false) }
    val unavailable = status is EngineStatus.Unavailable
    val now = remember(sessions) { System.currentTimeMillis() }

    fun open(file: String?, fresh: Boolean) {
        if (opening) return
        val current = file?.takeIf { !fresh }?.let { LocalSessions.find(name, it) }
        if (current != null) {
            onOpenSession(current.key)
            return
        }
        opening = true
        scope.launch {
            LocalSessions.open(engine, store, dir, sessionFile = file, fresh = fresh)
                .onSuccess { onOpenSession(it.key) }
                .onFailure { snackbar.show(it.message ?: "Could not open the session", SnackKind.Error) }
            opening = false
        }
    }

    // "Ask Pi" in the editor: the text goes into the composer of this project's session (the live one, else the
    // latest, else a new one), which then opens.
    fun ask(text: String) {
        val current = liveHere
        if (current != null) {
            current.drop(text)
            onOpenSession(current.key)
            return
        }
        if (opening) return
        opening = true
        scope.launch {
            val latest = sessions?.firstOrNull()?.file
            LocalSessions.open(engine, store, dir, sessionFile = latest, fresh = latest == null)
                .onSuccess {
                    it.drop(text)
                    onOpenSession(it.key)
                }
                .onFailure { snackbar.show(it.message ?: "Could not open the session", SnackKind.Error) }
            opening = false
        }
    }

    // Files in and out (ui/projects/transfer): SAF pickers, zips, the share sheet; one transfer at a time.
    val context = LocalContext.current
    val transfers = remember { TransferRunner(app.appScope) }
    // The folder (or project) an export is for, waiting for the export sheet or the "Save to…" document. Saved, so
    // the document picked after a rotation or a process death in the system picker still gets the zip.
    var exportPath by rememberSaveable { mutableStateOf<String?>(null) }
    var exportLabel by rememberSaveable { mutableStateOf("") }
    val exportOf = exportPath?.let { File(it) to exportLabel }
    var exportAsk by rememberSaveable { mutableStateOf(false) }
    var exportSkip by rememberSaveable { mutableStateOf(ArrayList<String>()) }
    fun failed(message: String) {
        scope.launch { snackbar.show(message, SnackKind.Error) }
    }
    val importDocs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val into = folder
        transfers.run(
            if (uris.size == 1) "Importing 1 file" else "Importing ${uris.size} files",
            { p -> SafTransfer.importDocuments(context, uris, into, p) },
            onDone = { names ->
                reload++
                scope.launch { snackbar.show(if (names.size == 1) "Imported ${names[0]}" else "Imported ${names.size} files", SnackKind.Success) }
            },
            onError = ::failed,
        )
    }
    val importTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val into = folder
        transfers.run(
            "Importing a folder",
            { p -> SafTransfer.importTree(context, uri, into, p) },
            onDone = { n ->
                reload++
                scope.launch { snackbar.show("Imported $n", SnackKind.Success) }
            },
            onError = ::failed,
        )
    }
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val (source, label) = exportOf ?: return@rememberLauncherForActivityResult
        val skip = exportSkip.toSet()
        exportPath = null
        if (uri == null) return@rememberLauncherForActivityResult
        transfers.run(
            "Exporting $label",
            { p -> SafTransfer.exportZip(context, source, uri, label, skip, p) },
            onDone = { n -> scope.launch { snackbar.show(if (n == 1) "Exported 1 file" else "Exported $n files", SnackKind.Success) } },
            onError = ::failed,
        )
    }
    fun export(source: File, label: String) {
        exportPath = source.path
        exportLabel = label
        exportAsk = true
    }
    fun shareFile(file: File) {
        scope.launch {
            runCatching { ShareFiles.share(context, ShareFiles.copy(context, file, file.name), FileTypes.mime(file.name), file.name) }
                .onFailure { failed("Could not share this file") }
        }
    }

    val entry = ProjectEntry(name, dir.absolutePath, pm, sessions?.firstOrNull(), sessions?.size ?: 0)
    val selected = ProjectTab.of(tab)
    // Each tab keeps its own scroll position while the others are shown.
    val sessionsList = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val filesList = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val changesList = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    // The Changes badge needs the status before its tab is opened, and again after a resume or a file change.
    LaunchedEffect(dir, reload) { if (selected != ProjectTab.Changes) changes.model.refresh() }
    // Only "working or not" from the live session, so streamed frames do not recompose the project screen.
    val busyFlow = remember(lives) {
        if (lives.isEmpty()) flowOf(false)
        else combine(lives.map { l -> l.controller.state.map { it.isRunning || it.isCompacting || it.dialogs.isNotEmpty() }.distinctUntilChanged() }) { busy -> busy.any { it } }
    }
    val liveBusy by busyFlow.collectAsStateWithLifecycle(false)
    val badges = ProjectBadges(
        changes = ProjectNavRules.changesBadge(changes.model.status),
        terminal = Terminals.inProject(dir).any { it.running },
        sessions = liveBusy,
    )
    val imeVisible = WindowInsets.isImeVisible
    val overlayOpen = openFile != null || (selected == ProjectTab.Changes && changes.overlay != null)

    Box(
        Modifier
            .fillMaxSize()
            .sharedHostBounds(SharedKeys.project(name), card = false)
            .background(colors.canvas)
            // Landscape: the side navigation bar and a display cutout keep their side, for the tabs and the overlays.
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        Column(Modifier.fillMaxSize().imePadding()) {
            // The list tabs open on the project's large title; the bar's compact title fades in once it scrolled away.
            // The terminal has no large title: its bar always names the project.
            val shownList = when (selected) {
                ProjectTab.Sessions -> sessionsList
                ProjectTab.Files -> filesList
                ProjectTab.Changes -> changesList
                ProjectTab.Terminal -> null
            }
            val pastTitle by remember(shownList) { derivedStateOf { shownList == null || shownList.firstVisibleItemIndex > 0 } }
            if (ProjectNavRules.showChrome(imeVisible, LocalConfiguration.current.screenHeightDp)) GlassBar(
                title = title,
                subtitle = if (shownList == null) branch?.let { "projects/$name · $it" } ?: "projects/$name" else null,
                titleVisible = pastTitle,
                titleModifier = if (shownList == null) Modifier.sharedHostElement(SharedKeys.projectName(name)) else Modifier,
                leading = { GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Projects", onClick = { if (tabs.anyDirty) leaving = true else onBack() }) },
                actions = {
                    when (selected) {
                        ProjectTab.Sessions -> GlassIconButton(Icons.Rounded.Search, "Search and manage sessions", onClick = { browse = true }, enabled = !unavailable)
                        ProjectTab.Terminal -> GlassIconButton(Icons.Rounded.Key, "Project secrets", onClick = onSecrets)
                        ProjectTab.Files, ProjectTab.Changes -> Unit
                    }
                    Box {
                        GlassIconButton(Icons.Rounded.MoreHoriz, "More", onClick = { menu = true })
                        ProjectMenu(
                            menu,
                            pm.pinned,
                            onDismiss = { menu = false },
                            onPin = { store.setPinned(name, it) },
                            onRename = { action = ProjectAction.Rename(entry) },
                            onDelete = { action = ProjectAction.Delete(entry) },
                            onExport = { export(dir, name) },
                            onRuntimes = onRuntimes,
                            onSecrets = onSecrets,
                        )
                    }
                },
            ) else Spacer(Modifier.statusBarsPadding())
            val header: @Composable () -> Unit = {
                ProjectHeader(title = title, name = name, pinned = pm.pinned, branch = branch, titleModifier = Modifier.sharedHostElement(SharedKeys.projectName(name)))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (selected) {
                    ProjectTab.Sessions -> LazyColumn(Modifier.fillMaxSize(), state = sessionsList, contentPadding = PaddingValues(bottom = Space.xxl)) {
                        item(key = "head", contentType = "head") { header() }
                        item(key = "new", contentType = "new") {
                            Column(Modifier.padding(horizontal = Space.page, vertical = Space.xs)) {
                                PrimaryButton(
                                    if (opening) "Opening…" else "New session",
                                    onClick = { open(null, fresh = true) },
                                    icon = Icons.Rounded.Add,
                                    enabled = !unavailable && !opening,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (unavailable) {
                                    Text("Sessions can't run on this phone", style = type.rowMeta, color = colors.ink3, modifier = Modifier.padding(top = Space.sm))
                                }
                            }
                        }
                        val list = sessions
                        when {
                            list == null -> item(key = "loading", contentType = "loading") { LoadingRow("Loading sessions…") }
                            list.isEmpty() -> item(key = "empty", contentType = "empty") {
                                EmptyCard(Icons.AutoMirrored.Rounded.Chat, "No sessions yet")
                            }
                            else -> {
                                item(key = "label", contentType = "label") { InsetSectionHead("Sessions", count = list.size) }
                                // The engine's own session file: a new session or a fork is not the file the session was opened with.
                                val liveFile = liveHere?.controller?.sessionFile ?: liveHere?.sessionFile
                                itemsIndexed(list, key = { _, it -> it.file }, contentType = { _, _ -> "session" }) { i, s ->
                                    // Every live session here is marked on its row (one still starting: the newest row).
                                    val rowLive = lives.firstOrNull { (it.controller.sessionFile ?: it.sessionFile) == s.file }
                                        ?: liveHere?.takeIf { liveFile == null && s == list.first() }
                                    val isLive = rowLive != null
                                    SessionRow(
                                        s,
                                        live = rowLive?.controller,
                                        unread = !isLive && ProjectRules.unread(s, meta.seen[s.file]),
                                        now = now,
                                        where = branch,
                                        onClick = {
                                            when {
                                                unavailable -> scope.launch { snackbar.show("Sessions can't run on this phone", SnackKind.Warning) }
                                                rowLive != null -> onOpenSession(rowLive.key)
                                                else -> open(s.file, fresh = false)
                                            }
                                        },
                                        divider = i < list.lastIndex,
                                        modifier = Modifier.animateItem(),
                                    )
                                }
                            }
                        }
                    }
                    ProjectTab.Files -> LazyColumn(Modifier.fillMaxSize(), state = filesList, contentPadding = PaddingValues(bottom = Space.xxl)) {
                        item(key = "head", contentType = "head") { header() }
                        stickyHeader(key = "files-bar", contentType = "files-bar") {
                            FilesBar(
                                root = title,
                                path = path,
                                sort = sort,
                                showHidden = showHidden,
                                onPath = { path = it },
                                onSort = { sort = it },
                                onHidden = { showHidden = it },
                                onCreate = { fileAction = FileAction.Create(it) },
                                onImport = { tree -> if (tree) importTree.launch(null) else importDocs.launch(arrayOf("*/*")) },
                                modifier = Modifier.background(colors.canvas),
                            )
                        }
                        val entries = shownFiles
                        when {
                            entries == null -> item(key = "files-loading", contentType = "loading") { LoadingRow("Loading files…") }
                            entries.isEmpty() -> item(key = "files-empty", contentType = "empty") {
                                val hidden = files.orEmpty().size
                                EmptyCard(
                                    Icons.Rounded.Folder,
                                    if (path.isEmpty()) "No files yet" else "This folder is empty",
                                    when {
                                        hidden == 1 -> "1 hidden file"
                                        hidden > 1 -> "$hidden hidden files"
                                        else -> null
                                    },
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                                        SecondaryButton("New file", onClick = { fileAction = FileAction.Create(false) }, icon = Icons.AutoMirrored.Rounded.NoteAdd, modifier = Modifier.weight(1f))
                                        SecondaryButton("Import", onClick = { importDocs.launch(arrayOf("*/*")) }, icon = Icons.Rounded.FileOpen, modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                            else -> itemsIndexed(entries, key = { _, it -> "f:" + it.name }, contentType = { _, _ -> "file" }) { i, entry ->
                                FileRow(entry, folder, onClick = {
                                    val rel = if (path.isEmpty()) entry.name else path + "/" + entry.name
                                    if (entry.dir) path = rel else openFile = rel
                                }, onLongClick = { fileAction = FileAction.Menu(entry) }, divider = i < entries.lastIndex, modifier = Modifier.animateItem())
                            }
                        }
                    }
                    ProjectTab.Changes -> LazyColumn(Modifier.fillMaxSize(), state = changesList, contentPadding = PaddingValues(bottom = Space.xxl)) {
                        item(key = "head", contentType = "head") { header() }
                        changesItems(changes)
                    }
                    ProjectTab.Terminal -> TerminalPane(app, dir, fullScreen = false, onToggleFullScreen = onTerminal, onPreview = onPreview, modifier = Modifier.fillMaxSize())
                }
                Column(Modifier.align(Alignment.BottomCenter)) {
                    TransferBar(transfers)
                    PiSnackHost(snackbar)
                }
            }
            if (ProjectNavRules.showBar(imeVisible, overlayOpen)) {
                ProjectNavBar(selected, badges, onSelect = { tab = it.index })
            } else if (!imeVisible) {
                Spacer(Modifier.navigationBarsPadding())
            }
        }
        StatusBarScrim()

        AnimatedVisibility(
            visible = openFile != null,
            enter = fadeIn(PiMotion.enter(PiMotion.SHEET)) + slideInVertically(PiMotion.enter(PiMotion.SHEET)) { it / 10 },
            exit = fadeOut(PiMotion.leave(PiMotion.SHEET)) + scaleOut(PiMotion.leave(PiMotion.SHEET), targetScale = 0.94f),
        ) {
            val shown = remember { mutableStateOf(openFile) }
            openFile?.let { shown.value = it }
            shown.value?.let { rel ->
                FileViewer(dir, tabs, rel, onSelect = { openFile = it }, onClose = { openFile = null }, onChanged = { reload++ }, onAsk = ::ask)
            }
        }
        ChangesOverlays(changes, visible = selected == ProjectTab.Changes)
    }
    // Back inside Files goes up a folder first.
    BackHandler(enabled = showFiles && path.isNotEmpty() && openFile == null) { path = path.substringBeforeLast('/', "") }
    if (leaving) {
        val dirty = tabs.tabs.count { it.dirty }
        ConfirmSheet(
            title = "Discard unsaved changes?",
            message = if (dirty == 1) "1 file has edits that are not saved." else "$dirty files have edits that are not saved.",
            confirmLabel = "Discard",
            onConfirm = {
                leaving = false
                onBack()
            },
            onDismiss = { leaving = false },
        )
    }

    FileActionSheets(
        action = fileAction,
        folder = folder,
        onMoved = { from, to ->
            fun rel(n: String) = if (path.isEmpty()) n else "$path/$n"
            tabs.moved(rel(from), to?.let(::rel))
        },
        onAction = { a ->
            when (a) {
                is FileAction.Export -> {
                    fileAction = null
                    export(File(folder, a.entry.name), a.entry.name)
                }
                is FileAction.Share -> {
                    fileAction = null
                    shareFile(File(folder, a.entry.name))
                }
                else -> fileAction = a
            }
        },
        onDone = {
            fileAction = null
            reload++
        },
        onError = { scope.launch { snackbar.show(it, SnackKind.Error) } },
    )

    if (exportAsk) {
        exportOf?.let { (source, label) ->
            ExportSheet(
                title = label,
                onChoose = { choice ->
                    exportAsk = false
                    exportSkip = ArrayList(choice.skip)
                    if (choice.share) {
                        exportPath = null
                        transfers.run(
                            "Packing $label",
                            { p -> SafTransfer.shareZip(context, source, "$label.zip", label, choice.skip, p) },
                            onDone = {},
                            onError = ::failed,
                        )
                    } else {
                        saveZip.launch("$label.zip")
                    }
                },
                onDismiss = {
                    exportAsk = false
                    exportPath = null
                },
            )
        }
    }

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

/**
 * The project's large title at the top of a list tab: the title (largeTitle ink1, the shared element from Home's
 * project row, a 16dp pin glyph when pinned) over `projects/x` and, in a git repository, the branch glyph and the branch
 * from `.git/HEAD` (monoMeta ink3).
 */
@Composable
private fun ProjectHeader(title: String, name: String, pinned: Boolean, branch: String?, titleModifier: Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    Column(Modifier.fillMaxWidth().padding(start = Space.page, end = Space.page, top = Space.xs, bottom = Space.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = type.largeTitle,
                color = colors.ink1,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = titleModifier.weight(1f, fill = false).semantics { heading() },
            )
            if (pinned) {
                Spacer(Modifier.width(Space.sm))
                Icon(Icons.Rounded.PushPin, contentDescription = "Pinned", tint = colors.ink3, modifier = Modifier.size(IconSize.sm))
            }
        }
        Spacer(Modifier.height(Space.xxs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("projects/$name", style = type.monoMeta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f, fill = false))
            if (branch != null) {
                Spacer(Modifier.width(Space.md))
                Icon(Icons.AutoMirrored.Rounded.CallSplit, contentDescription = "Branch", tint = colors.ink3, modifier = Modifier.size(IconSize.xs))
                Spacer(Modifier.width(Space.xs))
                Text(branch, style = type.monoMeta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
        }
    }
}

/**
 * The empty state of a workspace view, calm and unboxed at the 20dp gutter: a 24dp glyph (ink2) in a 48dp glass
 * circle, the title (rowTitle ink1), one line on what to do (rowMeta ink2) and, when given, the actions under it.
 */
@Composable
internal fun EmptyCard(icon: ImageVector, title: String, text: String? = null, actions: (@Composable () -> Unit)? = null) {
    val colors = Pi.colors
    val type = Pi.type
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.page, vertical = Space.lg),
    ) {
        Box(Modifier.size(48.dp).glass(CircleShape, colors), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(IconSize.lg))
        }
        Spacer(Modifier.height(Space.lg))
        Text(title, style = type.rowTitle, color = colors.ink1, modifier = Modifier.semantics { heading() })
        if (text != null) {
            Spacer(Modifier.height(Space.xs))
            Text(text, style = type.rowMeta, color = colors.ink2)
        }
        if (actions != null) {
            Spacer(Modifier.height(Space.lg))
            actions()
        }
    }
}
