package opensec.pibolt.app.ui.home

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Key
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
import opensec.pibolt.app.ui.components.BrandMark
import opensec.pibolt.app.ui.components.ConfirmSheet
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.PiSnackHost
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.SectionLabel
import opensec.pibolt.app.ui.components.SnackKind
import opensec.pibolt.app.ui.components.StatusBarScrim
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.components.show
import opensec.pibolt.app.ui.dev.engine
import opensec.pibolt.app.ui.hosts.HostCard
import opensec.pibolt.app.ui.hosts.PasteSheet
import opensec.pibolt.app.ui.projects.LocalSessions
import opensec.pibolt.app.ui.projects.NewProjectSheet
import opensec.pibolt.app.ui.projects.ProjectAction
import opensec.pibolt.app.ui.projects.ProjectActionSheets
import opensec.pibolt.app.ui.projects.ProjectCard
import opensec.pibolt.app.ui.projects.ProjectEntry
import opensec.pibolt.app.ui.projects.ProjectRules
import opensec.pibolt.app.ui.projects.Projects
import opensec.pibolt.app.ui.providers.ProviderCatalog
import opensec.pibolt.app.ui.providers.ProviderSheet
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius

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
    val meta by store.state.collectAsStateWithLifecycle()
    val metaLoaded by store.loaded.collectAsStateWithLifecycle()
    val hosts by app.hosts.hosts.collectAsStateWithLifecycle()
    val remote by app.session.collectAsStateWithLifecycle()
    val local by LocalSessions.current.collectAsStateWithLifecycle()
    val unavailable = status is EngineStatus.Unavailable

    var reload by remember { mutableIntStateOf(0) }
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

    var computersOpen by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val showComputers = computersOpen ?: (unavailable || (list != null && list.isEmpty() && hosts.isNotEmpty()) || remote?.stopped == false)
    var newProject by remember { mutableStateOf<Boolean?>(null) }
    var action by remember { mutableStateOf<ProjectAction?>(null) }
    var showPaste by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf<Host?>(null) }
    var details by remember { mutableStateOf(false) }
    var addProvider by remember { mutableStateOf(false) }
    var onboarding by remember { mutableStateOf(false) }

    // First run with no provider: a calm sheet after the first frame, once. Afterwards Home shows a quiet card.
    LaunchedEffect(metaLoaded, meta.providerPromptSeen, configured.isEmpty(), unavailable) {
        if (metaLoaded && !meta.providerPromptSeen && configured.isEmpty() && !unavailable) {
            delay(450)
            onboarding = true
        }
    }
    LaunchedEffect(externalError) {
        externalError ?: return@LaunchedEffect
        snackbar.show(externalError, SnackKind.Error)
        onErrorShown()
    }
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val hasBottomBar = !unavailable && !list.isNullOrEmpty()

    Box(Modifier.fillMaxSize().background(colors.canvas)) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = (if (hasBottomBar) 104.dp else 24.dp) + bottomInset),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header", contentType = "header") {
                Column(Modifier.statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().height(56.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        IconAction(Icons.Outlined.Settings, "Settings", onClick = onSettings)
                    }
                    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        BrandMark(size = 28.dp, play = false)
                        Spacer(Modifier.width(12.dp))
                        Text("Pi-Bolt", style = type.display.copy(fontSize = type.display.fontSize * 1.15f), color = colors.ink1)
                    }
                }
            }
            if (status.needsBanner()) {
                item(key = "engine", contentType = "engine") {
                    EngineBanner(status, onRetry = engine::prepare, onDetails = { details = true }, modifier = Modifier.animateItem().padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
            if (!unavailable && metaLoaded && meta.providerPromptSeen && configured.isEmpty()) {
                item(key = "provider", contentType = "provider") {
                    ProviderNudge(onAdd = { addProvider = true }, modifier = Modifier.animateItem().padding(horizontal = 16.dp, vertical = 4.dp))
                }
            }
            if (!unavailable) {
                item(key = "projects-label", contentType = "label") {
                    SectionLabel("On this phone", Modifier.animateItem().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp))
                }
                when {
                    list == null -> Unit
                    list.isEmpty() -> item(key = "projects-empty", contentType = "empty") {
                        EmptyProjects(onNew = { newProject = false }, onClone = { newProject = true }, modifier = Modifier.animateItem())
                    }
                    else -> items(list, key = { "p:" + it.name }, contentType = { "project" }) { entry ->
                        ProjectCard(
                            entry = entry,
                            live = local?.takeIf { it.project.name == entry.name && !it.controller.stopped }?.controller,
                            now = now,
                            onClick = { onOpenProject(entry) },
                            onPin = { store.setPinned(entry.name, it) },
                            onAction = { action = it },
                            modifier = Modifier.animateItem().padding(horizontal = 16.dp, vertical = 5.dp),
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
                        modifier = Modifier.animateItem().padding(horizontal = 16.dp, vertical = 5.dp),
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
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SecondaryButton("Scan QR code", onClick = onScan, icon = Icons.Rounded.QrCodeScanner, modifier = Modifier.weight(1f))
                            SecondaryButton("Paste link", onClick = { showPaste = true }, icon = Icons.Rounded.ContentPaste, modifier = Modifier.weight(1f))
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
            PiSnackHost(snackbar)
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
            onCreate = { name, url ->
                projects.create(name).fold(
                    onSuccess = { dir ->
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

/** No projects yet: what a project is, and the two ways to start one. */
@Composable
private fun EmptyProjects(onNew: () -> Unit, onClone: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    val shape = RoundedCornerShape(Radius.lg)
    Column(
        modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .background(colors.surface1, shape)
            .border(1.dp, colors.line, shape)
            .padding(20.dp),
    ) {
        BrandMark(size = 40.dp, playKey = "home")
        Spacer(Modifier.height(18.dp))
        Text("Code on this phone", style = type.title, color = colors.ink1)
        Spacer(Modifier.height(6.dp))
        Text(
            "A project is a folder Pi works in: it reads and edits files, runs git and your tests, here, without a computer.",
            style = type.body,
            color = colors.ink2,
        )
        Spacer(Modifier.height(20.dp))
        PrimaryButton("New project", onClick = onNew, icon = Icons.Rounded.Add, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        SecondaryButton("Clone from git", onClick = onClone, modifier = Modifier.fillMaxWidth())
    }
}

/** Set up after the first-run sheet was closed without a key. */
@Composable
private fun ProviderNudge(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val shape = RoundedCornerShape(Radius.md)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .pressable(onAdd, shape = shape, label = "Add an API key")
            .background(colors.surface1, shape)
            .border(1.dp, colors.line, shape)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Key, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text("Connect a model", style = Pi.type.bodyStrong, color = colors.ink1)
            Text("Add an API key from ${ProviderCatalog.all.take(3).joinToString(", ") { it.name }} or others", style = Pi.type.meta, color = colors.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        TextAction("Add key", onClick = onAdd, color = colors.accent)
    }
}

/** "Computers · 2" with a chevron; folds the remote-control section. */
@Composable
private fun ComputersHeader(count: Int, open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val turn by animateFloatAsState(if (open) 180f else 0f, PiMotion.inPlace(PiMotion.QUICK), label = "computers-chevron")
    Row(
        modifier
            .padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 4.dp)
            .fillMaxWidth()
            .height(44.dp)
            .pressable(onToggle, shape = RoundedCornerShape(Radius.sm), pressedScale = 1f, role = Role.Button, label = if (open) "Hide computers" else "Show computers")
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Computers", style = Pi.type.metaStrong, color = colors.ink3)
        if (count > 0) {
            Text(" · $count", style = Pi.type.meta, color = colors.ink3)
        }
        Spacer(Modifier.weight(1f))
        Text("Remote control", style = Pi.type.meta, color = colors.ink3)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = turn })
    }
}
