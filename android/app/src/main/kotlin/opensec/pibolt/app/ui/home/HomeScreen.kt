package opensec.pibolt.app.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import opensec.pibolt.app.ui.projects.transfer.SafTransfer
import opensec.pibolt.app.ui.projects.transfer.TransferBar
import opensec.pibolt.app.ui.projects.transfer.TransferRunner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import java.io.File
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.data.Host
import opensec.pibolt.app.data.ProjectStore
import opensec.pibolt.app.engine.EngineStatus
import opensec.pibolt.app.git.GitRepo
import opensec.pibolt.app.ui.components.BrandMark
import opensec.pibolt.app.ui.components.ButtonRow
import opensec.pibolt.app.ui.components.ConfirmSheet
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.PiSnackHost
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.SnackKind
import opensec.pibolt.app.ui.components.StatusBarScrim
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.components.show
import opensec.pibolt.app.ui.hosts.HostCard
import opensec.pibolt.app.ui.hosts.PasteSheet
import opensec.pibolt.app.ui.projects.LocalSessions
import opensec.pibolt.app.ui.session.RunningRow
import opensec.pibolt.app.ui.session.runningSessions
import opensec.pibolt.app.ui.projects.NewProjectSheet
import opensec.pibolt.app.ui.projects.ProjectAction
import opensec.pibolt.app.ui.projects.ProjectActionSheets
import opensec.pibolt.app.ui.projects.ContinueRow
import opensec.pibolt.app.ui.projects.GroupPos
import opensec.pibolt.app.ui.projects.ListHead
import opensec.pibolt.app.ui.projects.ProjectCard
import opensec.pibolt.app.ui.projects.ProjectEntry
import opensec.pibolt.app.ui.projects.ProjectRules
import opensec.pibolt.app.ui.projects.Projects
import opensec.pibolt.app.ui.providers.ProviderCatalog
import opensec.pibolt.app.ui.providers.ProviderSheet
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Tnum
import opensec.pibolt.app.ui.update.UpdateCard
import opensec.pibolt.app.update.UpdateJob
import opensec.pibolt.app.update.UpdateRules

/**
 * Home: projects on this phone first (Pi-Bolt runs here), then the computers this phone controls, folded away by
 * default once there are projects. Disk work (listing folders, reading sessions) runs off the main thread; the list
 * is ordered and decorated in memory, so pins and renames apply without touching the disk again.
 */
@Composable
fun HomeScreen(
    app: PiBoltApp,
    externalError: String?,
    onErrorShown: () -> Unit,
    onOpenProject: (ProjectEntry) -> Unit,
    onOpenLocalSession: (String) -> Unit,
    onOpenHost: (Host) -> Unit,
    onResumeHost: () -> Unit,
    onScan: () -> Unit,
    onSettings: () -> Unit,
    onPairingText: (String) -> String?,
) {
    val engine = app.engine
    val store = remember { ProjectStore.get(app) }
    val projects = remember(engine) { Projects(engine, store) }
    val colors = Pi.colors
    val type = Pi.type
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val status by engine.status.collectAsStateWithLifecycle()
    val configured by engine.auth.configured.collectAsStateWithLifecycle()
    val authLoaded by engine.auth.loaded.collectAsStateWithLifecycle()
    val meta by store.state.collectAsStateWithLifecycle()
    val metaLoaded by store.loaded.collectAsStateWithLifecycle()
    val hosts by app.hosts.hosts.collectAsStateWithLifecycle()
    val remote by app.remote.collectAsStateWithLifecycle()
    val local by LocalSessions.current.collectAsStateWithLifecycle()
    // Sessions working now, in every project: each keeps its engine while the user is elsewhere.
    val running by remember { runningSessions() }.collectAsStateWithLifecycle(emptyList())
    val liveAll by LocalSessions.all.collectAsStateWithLifecycle()
    val update by app.updates.state.collectAsStateWithLifecycle()
    val showUpdate = update.job != UpdateJob.Idle || (update.decision as? UpdateRules.Decision.Available)?.snoozed == false
    val unavailable = status is EngineStatus.Unavailable

    var reload by remember { mutableIntStateOf(0) }
    // "Import a zip" in the new-project sheet: the archive becomes a project, which then opens.
    val context = LocalContext.current
    val transfers = remember { TransferRunner(app.appScope) }
    val importZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        transfers.run(
            "Importing the zip",
            { p -> SafTransfer.importZipAsProject(context, uri, engine.projectsDir, p) },
            onDone = { name ->
                reload++
                onOpenProject(ProjectEntry(name, File(engine.projectsDir, name).absolutePath, store.meta(name), null, 0))
            },
            onError = { m -> scope.launch { snackbar.show(m, SnackKind.Error) } },
        )
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { reload++ }
    LaunchedEffect(Unit) { engine.prepare() }
    // Folders and sessions from disk (IO); meta (pins, titles) is applied below in memory.
    val raw by produceState<List<ProjectEntry>?>(null, reload, local, status is EngineStatus.Ready) { value = projects.list() }
    val list = remember(raw, meta) {
        raw?.let { r -> ProjectRules.order(r.map { it.copy(meta = meta.projects[it.name] ?: it.meta) }) }
    }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }

    val runningNames = remember(running) { running.map { it.project.name }.toSet() }
    val recent = remember(list, local, runningNames) {
        list?.let { ProjectRules.continueList(it, local?.takeIf { l -> !l.controller.stopped }?.project?.name, running = runningNames) }.orEmpty()
    }
    // Coming back to Home from a session: what it shows now has been seen. A run that is still going and writes
    // later turns the session unread, so it gets a dot once it finishes here or in the background.
    LaunchedEffect(Unit) {
        LocalSessions.current.value?.takeIf { !it.controller.stopped }?.let { live -> (live.controller.sessionFile ?: live.sessionFile)?.let(store::seen) }
    }
    var resuming by remember { mutableStateOf(false) }
    fun resume(entry: ProjectEntry, file: String, isLive: Boolean) {
        if (isLive) {
            onOpenLocalSession(entry.name)
            return
        }
        if (resuming) return
        resuming = true
        scope.launch {
            LocalSessions.open(engine, store, File(entry.path), sessionFile = file)
                .onSuccess { onOpenLocalSession(entry.name) }
                .onFailure { snackbar.show(it.message ?: "Could not open the session", SnackKind.Error) }
            resuming = false
        }
    }

    var computersOpen by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val showComputers = computersOpen ?: (unavailable || (list != null && list.isEmpty() && hosts.isNotEmpty()) || remote?.stopped == false)
    var newProject by remember { mutableStateOf<Boolean?>(null) }
    var cloneProgress by remember { mutableStateOf<String?>(null) }
    var action by remember { mutableStateOf<ProjectAction?>(null) }
    var showPaste by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf<Host?>(null) }
    var details by remember { mutableStateOf(false) }
    var addProvider by remember { mutableStateOf(false) }
    var onboarding by remember { mutableStateOf(false) }

    // First run with no provider: a calm sheet after the first frame, once. Afterwards Home shows a quiet card.
    LaunchedEffect(metaLoaded, authLoaded, meta.providerPromptSeen, configured.isEmpty(), unavailable) {
        if (metaLoaded && authLoaded && !meta.providerPromptSeen && configured.isEmpty() && !unavailable) {
            delay(450)
            onboarding = true
        }
    }
    // First launch after an update: say so once. The snackbar runs in Home's scope: consuming the flag changes this
    // effect's key, which would cancel it.
    LaunchedEffect(update.justUpdated) {
        val version = update.justUpdated ?: return@LaunchedEffect
        scope.launch { snackbar.show("Updated to $version", SnackKind.Success) }
        app.updates.consumeJustUpdated()
    }
    LaunchedEffect(externalError) {
        externalError ?: return@LaunchedEffect
        snackbar.show(externalError, SnackKind.Error)
        onErrorShown()
    }
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val hasBottomBar = !unavailable && !list.isNullOrEmpty() && configured.isNotEmpty()

    // Landscape: the side navigation bar and a display cutout keep their side; the canvas still fills the window.
    Box(Modifier.fillMaxSize().background(colors.canvas).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = (if (hasBottomBar) 104.dp else 24.dp) + bottomInset),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header", contentType = "header") {
                // A compact 56dp bar: the mark and the name, settings on the right.
                Row(Modifier.statusBarsPadding().fillMaxWidth().height(56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BrandMark(size = 24.dp, play = false)
                    Spacer(Modifier.width(10.dp))
                    Text("Pi-Bolt", style = type.title, color = colors.ink1, modifier = Modifier.weight(1f).semantics { heading() })
                    IconAction(Icons.Rounded.Settings, "Settings", onClick = onSettings)
                }
            }
            if (status.needsBanner()) {
                item(key = "engine", contentType = "engine") {
                    EngineBanner(status, onRetry = engine::prepare, onDetails = { details = true }, modifier = Modifier.animateItem().padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
            if (showUpdate) {
                item(key = "update", contentType = "update") {
                    UpdateCard(app, modifier = Modifier.animateItem().padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
            // No model yet, or no project yet: one setup card whose only primary action is the next step.
            val needsModel = !unavailable && authLoaded && configured.isEmpty()
            if (!unavailable && metaLoaded && authLoaded && list != null && (needsModel || list.isEmpty())) {
                item(key = "setup", contentType = "setup") {
                    SetupCard(
                        modelName = configured.minOrNull()?.let(ProviderCatalog::name),
                        onConnect = { addProvider = true },
                        onNewProject = { newProject = false },
                        onClone = { newProject = true },
                        modifier = Modifier.animateItem().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            if (running.isNotEmpty()) {
                item(key = "running-label", contentType = "label") {
                    ListHead("Running", Modifier.animateItem().padding(top = 12.dp), sub = running.size.toString())
                }
                items(running, key = { "r:" + it.project.name }, contentType = { "running" }) { live ->
                    RunningRow(live, meta.projects[live.project.name], onClick = { onOpenLocalSession(live.project.name) }, modifier = Modifier.animateItem().padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
            if (!unavailable && recent.isNotEmpty()) {
                item(key = "continue-label", contentType = "label") {
                    ListHead("Continue", Modifier.animateItem().padding(top = 12.dp))
                }
                itemsIndexed(recent, key = { _, it -> "c:" + it.name }, contentType = { _, _ -> "continue" }) { i, entry ->
                    val session = entry.lastSession ?: return@itemsIndexed
                    val liveHere = liveAll[entry.name]?.takeIf { !it.controller.stopped }
                    val liveFile = liveHere?.let { it.controller.sessionFile ?: it.sessionFile }
                    val isLive = liveHere != null && (liveFile == null || liveFile == session.file)
                    ContinueRow(
                        entry = entry,
                        session = session,
                        pos = GroupPos.of(i, recent.size),
                        live = if (isLive) liveHere?.controller else null,
                        unread = !isLive && ProjectRules.unread(session, meta.seen[session.file]),
                        now = now,
                        onClick = { resume(entry, session.file, isLive) },
                        modifier = Modifier.animateItem().padding(horizontal = 16.dp),
                    )
                }
            }
            if (!unavailable && !list.isNullOrEmpty()) {
                item(key = "projects-label", contentType = "label") {
                    ListHead("Projects", Modifier.animateItem().padding(top = 12.dp), sub = list.size.toString())
                }
            }
            if (!unavailable) {
                when {
                    list == null || list.isEmpty() -> Unit
                    else -> itemsIndexed(list, key = { _, it -> "p:" + it.name }, contentType = { _, _ -> "project" }) { i, entry ->
                        ProjectCard(
                            entry = entry,
                            pos = GroupPos.of(i, list.size),
                            live = liveAll[entry.name]?.takeIf { !it.controller.stopped }?.controller,
                            now = now,
                            onClick = { onOpenProject(entry) },
                            onPin = { store.setPinned(entry.name, it) },
                            onAction = { action = it },
                            modifier = Modifier.animateItem().padding(horizontal = 16.dp),
                        )
                    }
                }
            }
            // Until the folders are read (a few ms, off the main thread) nothing below the title is placed, so the
            // computers section does not jump down when the projects arrive.
            val settled = list != null || unavailable
            if (settled) {
                item(key = "computers", contentType = "label") {
                    ComputersHeader(count = hosts.size, open = showComputers, onToggle = { computersOpen = !showComputers }, modifier = Modifier.animateItem())
                }
            }
            if (settled && showComputers) {
                items(hosts, key = { "h:" + it.id }, contentType = { "host" }) { host ->
                    val live = remote?.takeIf { it.hostId == host.id && !it.stopped }
                    HostCard(
                        host = host,
                        live = live,
                        now = now,
                        onClick = { if (live != null) onResumeHost() else onOpenHost(host) },
                        onForget = { forgetting = host },
                        modifier = Modifier.animateItem().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                item(key = "computers-actions", contentType = "actions") {
                    Column(Modifier.animateItem().padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text(
                            if (hosts.isEmpty()) "Control Pi-Bolt on a computer: run /⁠remote-control there, then scan its code." else "Run /⁠remote-control on another computer to add it.",
                            style = type.meta,
                            color = colors.ink3,
                            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
                        )
                        ButtonRow {
                            SecondaryButton("Paste link", onClick = { showPaste = true }, icon = Icons.Rounded.ContentPaste)
                            SecondaryButton("Scan code", onClick = onScan, icon = Icons.Rounded.QrCodeScanner)
                        }
                    }
                }
            }
        }

        StatusBarScrim()
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(0f to colors.canvas.copy(alpha = 0f), 0.3f to colors.canvas)),
        ) {
            TransferBar(transfers, if (hasBottomBar) Modifier else Modifier.navigationBarsPadding())
            PiSnackHost(snackbar, if (hasBottomBar) Modifier else Modifier.navigationBarsPadding())
            if (hasBottomBar) {
                PrimaryButton(
                    "New project",
                    onClick = { newProject = false },
                    icon = Icons.Rounded.Add,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp),
                )
            }
        }
    }

    newProject?.let { clone ->
        val names = remember(list) { list?.map { it.name }.orEmpty() }
        NewProjectSheet(
            existing = names,
            startWithClone = clone,
            github = app.github,
            progress = cloneProgress,
            onCreate = { name, url ->
                projects.create(name).fold(
                    onSuccess = { dir ->
                        if (url != null) {
                            cloneProgress = "Cloning…"
                            val cloned = runCatching { GitRepo.clone(app.git, url, dir) { cloneProgress = it.label } }
                            cloneProgress = null
                            cloned.exceptionOrNull()?.let { e ->
                                projects.delete(dir.name)
                                return@NewProjectSheet e.message ?: "Could not clone the repository"
                            }
                        }
                        LocalSessions.open(engine, store, dir, fresh = true, firstPrompt = url?.let(ProjectRules::clonePrompt)).fold(
                            onSuccess = {
                                reload++
                                onOpenLocalSession(dir.name)
                                null
                            },
                            onFailure = {
                                reload++
                                "Created, but the session did not open: ${it.message ?: "engine not ready"}"
                            },
                        )
                    },
                    onFailure = { it.message ?: "Could not create the project" },
                )
            },
            onDismiss = { newProject = null },
            onImportZip = {
                newProject = null
                importZip.launch(arrayOf("application/zip", "application/x-zip-compressed"))
            },
        )
    }

    ProjectActionSheets(
        action = action,
        projects = projects,
        store = store,
        scope = app.appScope,
        onDone = { action = null },
        onDeleted = { reload++ },
        onError = { scope.launch { snackbar.show(it, SnackKind.Error) } },
    )

    if (details) EngineDetailsSheet(status, onRetry = engine::prepare, onDismiss = { details = false })

    if (onboarding || addProvider) {
        ProviderSheet(
            auth = engine.auth,
            configured = configured,
            firstRun = onboarding,
            onSaved = { p ->
                onboarding = false
                addProvider = false
                store.providerPromptSeen()
                scope.launch { snackbar.show("${p.name} added", SnackKind.Success) }
            },
            onDismiss = {
                onboarding = false
                addProvider = false
                store.providerPromptSeen()
            },
        )
    }

    if (showPaste) {
        PasteSheet(
            onDismiss = { showPaste = false },
            onSubmit = { text ->
                val error = onPairingText(text)
                if (error == null) showPaste = false
                error
            },
        )
    }

    forgetting?.let { host ->
        ConfirmSheet(
            title = "Forget ${host.name}?",
            message = "This phone stops connecting to it. To also revoke this phone on the computer, run /⁠remote-control devices there.",
            confirmLabel = "Forget",
            onConfirm = {
                app.forget(host.id)
                forgetting = null
            },
            onDismiss = { forgetting = null },
        )
    }
}

/** "Computers  2" with a chevron, styled like the other section heads ([ListHead]); folds the remote-control section. */
@Composable
private fun ComputersHeader(count: Int, open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val turn by animateFloatAsState(if (open) 180f else 0f, PiMotion.inPlace(PiMotion.QUICK), label = "computers-chevron")
    Row(
        modifier
            .padding(start = 12.dp, end = 12.dp, top = 12.dp)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
            .pressable(onToggle, shape = RoundedCornerShape(Radius.sm), pressedScale = 1f, role = Role.Button, label = if (open) "Hide computers" else "Show computers")
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Computers", style = Pi.type.metaStrong, color = colors.ink2, modifier = Modifier.semantics { heading() })
        if (count > 0) {
            Spacer(Modifier.width(10.dp))
            Text("$count", style = Pi.type.meta.copy(fontFeatureSettings = Tnum), color = colors.ink3)
        }
        Spacer(Modifier.weight(1f))
        Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = turn })
    }
}
