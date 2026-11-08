package opensec.pibolt.app.ui.home

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.ui.graphics.vector.ImageVector
import opensec.pibolt.app.ui.components.EmptyState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import opensec.pibolt.app.ui.projects.transfer.SafTransfer
import opensec.pibolt.app.ui.projects.transfer.TransferBar
import opensec.pibolt.app.ui.projects.transfer.TransferRunner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import java.io.File
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import opensec.pibolt.app.engine.PageCatalog
import opensec.pibolt.app.ui.packages.PackagePageSheet
import opensec.pibolt.app.ui.session.views.ViewHomeTile
import opensec.pibolt.protocol.session.PageInfo
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import opensec.pibolt.app.ui.components.GlassIconButton
import opensec.pibolt.app.ui.components.glass
import opensec.pibolt.app.ui.components.StatusTile
import opensec.pibolt.app.ui.components.InsetRow
import opensec.pibolt.app.ui.components.PillComposerShell
import opensec.pibolt.app.ui.components.ComposerPrimary
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.session.entrance
import opensec.pibolt.app.ui.session.waitingSessions
import androidx.compose.foundation.layout.Arrangement
import opensec.pibolt.app.ui.components.SOFT_PRESS
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.widthIn
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.projects.WorkspaceMenu
import opensec.pibolt.app.ui.projects.WorkspaceMenuRow
import opensec.pibolt.app.ui.hosts.AddComputerSheet
import opensec.pibolt.app.ui.hosts.ManageComputerSheet
import opensec.pibolt.app.data.ProjectMeta
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.foundation.layout.width
import opensec.pibolt.app.ui.components.GlassPill
import opensec.pibolt.app.ui.components.ComposerCircle
import opensec.pibolt.app.ui.components.PiMenu
import opensec.pibolt.app.ui.components.PiMenuItem
import opensec.pibolt.app.ui.projects.PillPlus
import opensec.pibolt.app.ui.session.ImageSizing
import opensec.pibolt.app.ui.session.clipboardHasImage
import opensec.pibolt.app.ui.session.clipboardImage
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.data.Host
import opensec.pibolt.app.data.ProjectStore
import opensec.pibolt.app.engine.EngineStatus
import opensec.pibolt.app.ui.components.BrandMark
import opensec.pibolt.app.ui.components.ConfirmSheet
import opensec.pibolt.app.ui.components.PiSnackHost
import opensec.pibolt.app.ui.components.SnackKind
import opensec.pibolt.app.ui.components.StatusBarScrim
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.ui.chats.ChatList
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Forum
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.cost.SpendLimitsSheet
import opensec.pibolt.app.ui.cost.SpendSheet
import opensec.pibolt.app.ui.cost.rememberTodayLine
import opensec.pibolt.app.ui.components.show
import opensec.pibolt.app.ui.hosts.HostCard
import opensec.pibolt.app.ui.hosts.PasteSheet
import opensec.pibolt.app.ui.projects.LocalSessions
import opensec.pibolt.app.ui.chats.Chats
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import opensec.pibolt.app.engine.ChatSpace
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import opensec.pibolt.app.ui.session.RunningRow
import opensec.pibolt.app.ui.session.runningSessions
import opensec.pibolt.app.ui.projects.NewProjectSheet
import opensec.pibolt.app.ui.projects.ProjectClone
import opensec.pibolt.app.ui.projects.ProjectAction
import opensec.pibolt.app.ui.projects.ProjectActionSheets
import opensec.pibolt.app.ui.projects.ContinueRow
import androidx.compose.foundation.layout.height
import opensec.pibolt.app.secrets.SecretStore
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.app.ui.projects.HomeHead
import androidx.compose.material.icons.rounded.Add
import opensec.pibolt.app.ui.projects.ProjectCard
import opensec.pibolt.app.ui.projects.ProjectEntry
import opensec.pibolt.app.ui.projects.ProjectRules
import opensec.pibolt.app.ui.projects.Projects
import opensec.pibolt.app.ui.providers.ProviderCatalog
import opensec.pibolt.app.ui.providers.ProviderSheet
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Tnum
import opensec.pibolt.app.ui.theme.Space
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
    onOpenChats: () -> Unit = {},
    onOpenChat: () -> Unit = {},
) {
    val engine = app.engine
    val store = remember { ProjectStore.get(app) }
    val projects = remember(engine) { Projects(engine, store, SecretStore.get(app)) }
    val colors = Pi.colors
    val type = Pi.type
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val status by engine.status.collectAsStateWithLifecycle()
    // Providers with a working model: a key, a custom endpoint, or a provider a Pi package registers (ProviderDirectory).
    val configured by app.providers.connected.collectAsStateWithLifecycle()
    val stored by engine.auth.configured.collectAsStateWithLifecycle()
    val authLoaded by app.providers.loaded.collectAsStateWithLifecycle()
    val meta by store.state.collectAsStateWithLifecycle()
    val metaLoaded by store.loaded.collectAsStateWithLifecycle()
    val hosts by app.hosts.hosts.collectAsStateWithLifecycle()
    val remote by app.remote.collectAsStateWithLifecycle()
    val local by LocalSessions.current.collectAsStateWithLifecycle()
    // Sessions working now, in every project: each keeps its engine while the user is elsewhere.
    val running by remember { runningSessions() }.collectAsStateWithLifecycle(emptyList())
    val waiting by remember { waitingSessions() }.collectAsStateWithLifecycle(emptyList())
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
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        reload++
        Chats.refresh()
        app.providers.refreshIfOlder()
    }
    // Plain chats (ui/chats): the newest in Recents, all of them behind the Chats row.
    val chats by remember(engine) { Chats.list(engine) }.collectAsStateWithLifecycle(emptyList())
    val liveChat by Chats.current.collectAsStateWithLifecycle()
    val liveChatController = liveChat?.controller?.takeIf { !it.stopped }
    val chatWorking by remember(liveChatController) {
        liveChatController?.state?.map { it.isRunning }?.distinctUntilChanged() ?: flowOf(false)
    }.collectAsStateWithLifecycle(false)
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
            onOpenLocalSession(LocalSessions.find(entry.name, file)?.key ?: entry.name)
            return
        }
        if (resuming) return
        resuming = true
        scope.launch {
            LocalSessions.open(engine, store, File(entry.path), sessionFile = file)
                .onSuccess { onOpenLocalSession(it.key) }
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
    var plusMenu by remember { mutableStateOf(false) }
    var spendSheet by remember { mutableStateOf(false) }
    var spendLimits by remember { mutableStateOf(false) }
    var sheetList by remember { mutableStateOf<HomeList?>(null) }
    // Packages' live tiles (docs/mobile-ui.md#package-pages-and-home-tiles): only those turned on in Packages, served by
    // the page engine while Home is in front; a tap opens the tile's page.
    val appSettings by app.settings.settings.collectAsStateWithLifecycle()
    val registered by app.pages.pages.collectAsStateWithLifecycle()
    val pageState by app.pages.state.collectAsStateWithLifecycle()
    val packageTiles = remember(registered, appSettings.homeTiles) { PageCatalog.shownTiles(registered.orEmpty(), appSettings.homeTiles) }
    var packagePage by remember { mutableStateOf<PageInfo?>(null) }
    LaunchedEffect(appSettings.homeTiles.isNotEmpty()) { if (appSettings.homeTiles.isNotEmpty()) app.pages.refreshPages() }
    val tileKeys = packageTiles.map { it.key }.toSet()
    LifecycleResumeEffect(tileKeys) {
        app.pages.showTiles(tileKeys)
        onPauseOrDispose { app.pages.showTiles(emptySet()) }
    }
    var addComputer by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf<Pair<String, String>?>(null) }
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
    // No model or no project yet: the setup steps lead, and the title's line stays quiet.
    val needsModel = !unavailable && authLoaded && configured.isEmpty()
    val setup = !unavailable && metaLoaded && authLoaded && list != null && (needsModel || list.isEmpty())
    val hasProjects = !unavailable && !list.isNullOrEmpty()
    // Home is usable once the projects and the key and meta state are read: the startup's "fully drawn" (logcat
    // "Fully drawn", Macrobenchmark's timeToFullDisplay).
    ReportDrawnWhen { list != null && metaLoaded && authLoaded }

    // Landscape: the side navigation bar and a display cutout keep their side; the canvas still fills the window.
    val listState = rememberLazyListState()
    // The pill composer starts a session in the project worked in last (the newest session's, else the first listed).
    // "Chat" ([ChatSpace.KEY]) starts a plain chat instead: the default with no project, or when a chat was the last
    // thing worked on (ProjectRules.pillDefaultsToChat).
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val chatPicked = chosen == ChatSpace.KEY ||
        (chosen == null && list != null && ProjectRules.pillDefaultsToChat(list.isEmpty(), chats.firstOrNull()?.updatedAt, list.maxOfOrNull { it.lastActive }))
    val target = if (chatPicked) null else list?.firstOrNull { it.name == chosen } ?: recent.firstOrNull() ?: list?.firstOrNull()
    // The pill shows once a model is connected and the engine can run; with no project its chip offers to create one.
    val pillShown = !unavailable && authLoaded && configured.isNotEmpty() && list != null
    var ask by rememberSaveable { mutableStateOf("") }
    var starting by remember { mutableStateOf(false) }
    // Voice input: Android's own speech recognizer; what it heard fills the field (never sends by itself).
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { heard ->
            ask = if (ask.isBlank()) heard else ask.trimEnd() + " " + heard
        }
    }
    fun voice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        runCatching { speech.launch(intent) }.onFailure { scope.launch { snackbar.show("Voice input isn't available on this phone", SnackKind.Error) } }
    }
    /**
     * The + of the pill with Chat picked: a new chat opens with the draft and [images] waiting in its composer
     * (nothing is sent).
     */
    fun newChatWith(images: List<Uri>) {
        if (starting) return
        starting = true
        val text = ask.trim().takeIf { it.isNotEmpty() }
        scope.launch {
            Chats.start(engine)
                .onSuccess { live ->
                    if (text != null || images.isNotEmpty()) live.drop(text, images)
                    ask = ""
                    onOpenChat()
                }
                .onFailure { snackbar.show(it.message ?: "The chat didn't open", SnackKind.Error) }
            starting = false
        }
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ImageSizing.MAX_IMAGES)) { uris ->
        if (uris.isNotEmpty()) newChatWith(uris)
    }
    fun openChat(file: String?) {
        if (starting) return
        starting = true
        scope.launch {
            (if (file == null) Chats.start(engine) else Chats.resume(engine, file))
                .onSuccess { onOpenChat() }
                .onFailure { snackbar.show(it.message ?: "The chat didn't open", SnackKind.Error) }
            starting = false
        }
    }
    fun startSession() {
        if (chatPicked) {
            val text = ask.trim()
            if (text.isEmpty() || starting) return
            starting = true
            scope.launch {
                Chats.start(engine, firstPrompt = text)
                    .onSuccess {
                        ask = ""
                        onOpenChat()
                    }
                    .onFailure { snackbar.show(it.message ?: "The chat didn't open", SnackKind.Error) }
                starting = false
            }
            return
        }
        val project = target ?: run {
            newProject = false
            return
        }
        val text = ask.trim()
        if (text.isEmpty() || starting) return
        starting = true
        scope.launch {
            LocalSessions.open(engine, store, File(project.path), fresh = true, firstPrompt = text)
                .onSuccess {
                    ask = ""
                    onOpenLocalSession(it.key)
                }
                .onFailure { snackbar.show(it.message ?: "The session didn't open", SnackKind.Error) }
            starting = false
        }
    }
    val reduceMotion = Pi.reduceMotion
    val play = remember { !reduceMotion && HomeEntrance.claim() }
    // Where the tiles scroll to: the indices of the section heads, from the same conditions that build the list.
    val idleRecent = if (unavailable) emptyList() else recent
    val recentChats = if (unavailable) emptyList() else chats.take(RECENT_CHATS)
    // The Chats row, under Recents, once the folders are read.
    val chatsRow = !unavailable && list != null
    // 2: the header and today's spending line (an empty item when nothing was spent).
    val recentsAt = 2 + listOf(status.needsBanner(), showUpdate, setup, hasProjects).count { it }
    val projectsAt = recentsAt + (if (running.isNotEmpty() || idleRecent.isNotEmpty()) 1 + running.size + idleRecent.size else 0) +
        (if (chatsRow) 1 + recentChats.size + (if (chats.size > recentChats.size || chats.isEmpty()) 1 else 0) else 0)
    val computersAt = projectsAt + if (hasProjects) 1 + list.orEmpty().size else 0
    Box(Modifier.fillMaxSize().background(colors.canvas).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        LazyColumn(
            state = listState,
            // The list clears the whole bottom stack: the 48dp chip, 4dp, the 56dp pill, 16dp above and 12dp under it,
            // and 24dp of air, so no row is ever under it.
            contentPadding = PaddingValues(bottom = (if (pillShown) 160.dp else Space.x4) + bottomInset),
            // A wide window (landscape, tablets) keeps Home in a 760dp column, centred.
            modifier = Modifier.align(Alignment.TopCenter).fillMaxHeight().widthIn(max = HOME_MAX_WIDTH).fillMaxWidth(),
        ) {
            item(key = "header", contentType = "header") {
                // The brand is the title: the bare bolt at the title's cap height and "Pi-Bolt" in largeTitle, with
                // Settings and + as glass buttons on the same centre line. Today's spending, when there is any, follows.
                val spent = rememberTodayLine(app) != null
                Row(
                    Modifier.statusBarsPadding().fillMaxWidth().heightIn(min = if (spent) 56.dp else 72.dp).padding(start = Space.page, end = Space.page - Space.xxs, top = Space.sm, bottom = if (spent) 0.dp else Space.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BrandMark(size = 26.dp, play = false, modifier = Modifier.clearAndSetSemantics { })
                    Spacer(Modifier.width(Space.sm))
                    Text("Pi-Bolt", style = type.largeTitle, color = colors.ink1, maxLines = 1, modifier = Modifier.weight(1f).semantics { heading() })
                    GlassIconButton(Icons.Rounded.Settings, "Settings", onClick = onSettings)
                    if (hasProjects) {
                        Box {
                            GlassIconButton(Icons.Rounded.Add, "Add", onClick = { plusMenu = true })
                            WorkspaceMenu(plusMenu, onDismiss = { plusMenu = false }) {
                                WorkspaceMenuRow("New project", Icons.Rounded.CreateNewFolder, colors.ink1) { plusMenu = false; newProject = false }
                                WorkspaceMenuRow("Clone from git", Icons.AutoMirrored.Rounded.CallSplit, colors.ink1) { plusMenu = false; newProject = true }
                                WorkspaceMenuRow("Import a zip", Icons.Rounded.Archive, colors.ink1) {
                                    plusMenu = false
                                    importZip.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                                }
                                WorkspaceMenuRow("Add a computer", Icons.Rounded.Computer, colors.ink1) { plusMenu = false; addComputer = true }
                            }
                        }
                    }
                }
            }
            // Today's spending: a quiet meta line under "Pi-Bolt" once Pi used anything today; a tap opens the details.
            item(key = "today", contentType = "today") {
                val today = rememberTodayLine(app) ?: return@item
                Text(
                    today,
                    style = type.rowMeta.copy(fontFeatureSettings = Tnum),
                    color = colors.ink3,
                    maxLines = 1,
                    modifier = Modifier
                        // Past the 26dp mark and its gap, less this line's own press padding.
                        .padding(start = Space.page + 26.dp + Space.sm - Space.xs, bottom = Space.xs)
                        .pressable({ spendSheet = true }, shape = RoundedCornerShape(Radius.sm), pressedScale = 1f, label = "Spending")
                        .padding(Space.xs),
                )
            }
            if (status.needsBanner()) {
                item(key = "engine", contentType = "engine") {
                    EngineBanner(status, onRetry = engine::prepare, onDetails = { details = true }, modifier = Modifier.animateItem().padding(horizontal = Space.page, vertical = Space.xs))
                }
            }
            if (showUpdate) {
                item(key = "update", contentType = "update") {
                    UpdateCard(app, modifier = Modifier.animateItem().padding(bottom = Space.md))
                }
            }
            // No model or no project yet: the setup steps, whose only primary action is the next step.
            if (setup) {
                item(key = "setup", contentType = "setup") {
                    SetupCard(
                        modelName = configured.minOrNull()?.let(ProviderCatalog::name),
                        onConnect = { addProvider = true },
                        onNewProject = { newProject = false },
                        onClone = { newProject = true },
                        modifier = Modifier.animateItem().padding(top = Space.sm),
                    )
                }
            }
            if (hasProjects) {
                item(key = "tiles", contentType = "tiles") {
                    // Two flows: for a frame one may still hold a session the other dropped (never -1).
                    val working = running.count { it !in waiting }
                    // The 2x2 status grid from real state (one row of four in a wide window); each tile opens its
                    // list (Projects scrolls to its section).
                    // One tint per tile, every glyph 20dp: the Quads move only while their count is not zero.
                    val tiles: List<@Composable (Modifier) -> Unit> = listOf(
                        { m ->
                            StatusTile("Working", working, onClick = { sheetList = HomeList.Working }, modifier = m.entrance(0, play), active = working > 0) {
                                QuadSpinner(if (working > 0) QuadMode.Run else QuadMode.Think, size = IconSize.md, color = colors.tint4, animate = working > 0)
                            }
                        },
                        { m ->
                            StatusTile("Waiting for you", waiting.size, onClick = { sheetList = HomeList.Waiting }, modifier = m.entrance(1, play), active = waiting.isNotEmpty()) {
                                QuadSpinner(if (waiting.isNotEmpty()) QuadMode.Wait else QuadMode.Think, size = IconSize.md, color = colors.tint0, animate = waiting.isNotEmpty())
                            }
                        },
                        { m ->
                            StatusTile("Projects", list.orEmpty().size, onClick = { scope.launch { listState.animateScrollToItem(projectsAt) } }, modifier = m.entrance(2, play)) {
                                Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = colors.tint3, modifier = Modifier.size(IconSize.md))
                            }
                        },
                        { m ->
                            StatusTile("Computers", hosts.size, onClick = {
                                if (hosts.isEmpty()) {
                                    addComputer = true
                                } else {
                                    computersOpen = true
                                    scope.launch { listState.animateScrollToItem(computersAt) }
                                }
                            }, modifier = m.entrance(3, play)) {
                                Icon(Icons.Rounded.Computer, contentDescription = null, tint = colors.tint1, modifier = Modifier.size(IconSize.md))
                            }
                        },
                    )
                    BoxWithConstraints(Modifier.padding(horizontal = Space.page).padding(bottom = Space.sm)) {
                        val perRow = if (maxWidth >= 600.dp) 4 else 2
                        Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                            for (row in tiles.chunked(perRow)) {
                                // One height per row, so a label that wraps at a large font scale keeps the pair even.
                                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                                    for (tile in row) tile(Modifier.weight(1f).fillMaxHeight())
                                }
                            }
                        }
                    }
                }
            }
            if (packageTiles.isNotEmpty()) {
                item(key = "package-tiles", contentType = "package-tiles") {
                    BoxWithConstraints(Modifier.animateItem().padding(horizontal = Space.page).padding(bottom = Space.sm)) {
                        val perRow = if (maxWidth >= 600.dp) 4 else 2
                        Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                            for (row in packageTiles.chunked(perRow)) {
                                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                                    for (tile in row) {
                                        val page = tile.page?.let { key -> registered.orEmpty().firstOrNull { it.key == key && !it.isTile } }
                                        ViewHomeTile(
                                            tile.title,
                                            tile.icon,
                                            pageState.views[tile.key]?.view,
                                            onOpen = page?.let { p -> { packagePage = p } },
                                            modifier = Modifier.weight(1f).fillMaxHeight(),
                                        )
                                    }
                                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
            }
            fun liveFor(entry: ProjectEntry, file: String): SessionController? {
                LocalSessions.find(entry.name, file)?.let { return it.controller }
                val liveHere = liveAll[entry.name]?.takeIf { !it.controller.stopped } ?: return null
                val liveFile = liveHere.controller.sessionFile ?: liveHere.sessionFile
                return liveHere.controller.takeIf { liveFile == null || liveFile == file }
            }
            val idle = idleRecent
            if (running.isNotEmpty() || idle.isNotEmpty()) {
                item(key = "recents-label", contentType = "label") {
                    HomeHead("Recents", Modifier.animateItem().padding(top = Space.lg).entrance(4, play))
                }
                items(running, key = { "r:" + it.key }, contentType = { "recent" }) { live ->
                    RunningRow(live, meta.projects[live.project.name], onClick = { onOpenLocalSession(live.key) }, modifier = Modifier.animateItem(), divider = live != running.last() || idle.isNotEmpty())
                }
                items(idle, key = { "c:" + it.name }, contentType = { "recent" }) { entry ->
                    val session = entry.lastSession ?: return@items
                    val live = liveFor(entry, session.file)
                    ContinueRow(
                        entry = entry,
                        session = session,
                        live = live,
                        unread = live == null && ProjectRules.unread(session, meta.seen[session.file]),
                        now = now,
                        onClick = { resume(entry, session.file, live != null) },
                        modifier = Modifier.animateItem(),
                        divider = entry != idle.last(),
                    )
                }
            }
            // Chats, a section of their own under Recents: the head opens them all and starts a new one, then the
            // newest few; with none yet, one row that starts the first.
            if (chatsRow) {
                item(key = "chats-label", contentType = "label") {
                    HomeHead(
                        "Chats",
                        Modifier
                            .animateItem()
                            .padding(top = if (running.isEmpty() && idle.isEmpty()) Space.lg else Space.xl)
                            .heightIn(min = 48.dp)
                            .pressable(onOpenChats, shape = RoundedCornerShape(Radius.md), pressedScale = 1f, role = Role.Button, label = "All chats"),
                        count = chats.size.takeIf { it > 0 },
                        end = {
                            TextAction("New chat", onClick = { openChat(null) }, color = colors.ink1, enabled = !starting)
                            if (chats.isNotEmpty()) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(IconSize.md))
                        },
                    )
                }
                items(recentChats, key = { "chat:" + it.file }, contentType = { "chat" }) { chat ->
                    val working = chatWorking && liveChatController?.sessionFile == chat.file
                    InsetRow(
                        chat.title,
                        onClick = { openChat(chat.file) },
                        meta = if (working) "Working" else ChatList.meta(chat, now, Pi.settings.spend.showInLists),
                        leading = {
                            if (working) {
                                QuadSpinner(QuadMode.Write, size = IconSize.md, color = colors.tint2)
                            } else {
                                Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(IconSize.md))
                            }
                        },
                        divider = chat != recentChats.last() || chats.size > recentChats.size,
                        current = liveChatController?.sessionFile == chat.file && working,
                        label = "Open chat",
                        modifier = Modifier.animateItem(),
                    )
                }
                if (chats.size > recentChats.size) {
                    item(key = "chats-all", contentType = "actions") {
                        InsetRow(
                            "All chats",
                            onClick = onOpenChats,
                            count = chats.size,
                            chevron = true,
                            divider = false,
                            leading = { Icon(Icons.Rounded.Forum, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(IconSize.md)) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
                if (chats.isEmpty()) {
                    item(key = "chats-empty", contentType = "actions") {
                        InsetRow(
                            "Start a chat",
                            onClick = { openChat(null) },
                            meta = "Ask anything, without a project",
                            divider = false,
                            leading = { Icon(Icons.Rounded.Add, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(IconSize.md)) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
            if (hasProjects) {
                val projectsList = list.orEmpty()
                item(key = "projects-label", contentType = "label") {
                    HomeHead("Projects", Modifier.animateItem().padding(top = Space.xl), count = projectsList.size)
                }
                items(projectsList, key = { "p:" + it.name }, contentType = { "project" }) { entry ->
                    ProjectCard(
                        entry = entry,
                        live = liveAll[entry.name]?.takeIf { !it.controller.stopped }?.controller,
                        now = now,
                        onClick = { onOpenProject(entry) },
                        onPin = { store.setPinned(entry.name, it) },
                        onAction = { action = it },
                        modifier = Modifier.animateItem(),
                        divider = entry != projectsList.last(),
                    )
                }
            }
            // Until the folders are read (a few ms, off the main thread) nothing below the title is placed, so the
            // computers section does not jump down when the projects arrive.
            val settled = list != null || unavailable
            if (settled) {
                item(key = "computers", contentType = "label") {
                    ComputersHeader(count = hosts.size, open = showComputers, onToggle = { computersOpen = !showComputers }, onAdd = { addComputer = true }, modifier = Modifier.animateItem())
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
                        onManage = { status -> managing = host.id to status },
                        modifier = Modifier.animateItem(),
                    )
                }
                if (hosts.isEmpty()) {
                    item(key = "computers-empty", contentType = "actions") {
                        // The empty state: one row that adds the first computer.
                        InsetRow(
                            "Add a computer",
                            onClick = { addComputer = true },
                            meta = "Run Pi-Bolt there, from this phone",
                            divider = false,
                            modifier = Modifier.animateItem(),
                            leading = { Icon(Icons.Rounded.Add, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(IconSize.md)) },
                        )
                    }
                }
            }
        }

        StatusBarScrim()
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding(),
        ) {
            TransferBar(transfers, if (pillShown) Modifier else Modifier.navigationBarsPadding())
            PiSnackHost(snackbar, if (pillShown) Modifier else Modifier.navigationBarsPadding())
            if (pillShown) {
                // The persistent pill, with the project it starts in as a chip above it (left edges aligned, 8dp
                // apart): + (New project; with Chat picked, a chat's actions), the field, and the primary circle
                // (voice while empty, Send with text).
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(0f to colors.canvas.copy(alpha = 0f), 0.12f to colors.canvas))
                        .navigationBarsPadding()
                        .padding(start = Space.page - Space.xs, end = Space.page - Space.xs, top = Space.lg, bottom = Space.md),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(Modifier.widthIn(max = HOME_MAX_WIDTH).fillMaxWidth()) {
                      ProjectChip(
                          title = target?.title,
                          chat = chatPicked,
                          projects = list.orEmpty(),
                          onPick = { chosen = it.name },
                          onChat = { chosen = ChatSpace.KEY },
                          onNew = { newProject = false },
                      )
                      // The chip's 48dp target leaves 4dp under its 40dp face: 4dp more makes the 8dp gap.
                      Spacer(Modifier.height(Space.xs))
                      PillComposerShell(
                          leading = {
                              // Chat picked: a chat's actions (photo, pasted image, voice, New chat); a project: New project.
                              var plusOpen by remember { mutableStateOf(false) }
                              var actions by remember { mutableStateOf(emptyList<PillPlus>()) }
                              Box {
                                  ComposerCircle(Icons.Rounded.Add, if (chatPicked) "Add to a new chat" else "New project", enabled = !starting, onClick = {
                                      if (chatPicked) {
                                          actions = ProjectRules.pillPlus(chat = true, clipboardImage = clipboardHasImage(context))
                                          plusOpen = true
                                      } else {
                                          newProject = false
                                      }
                                  })
                                  PiMenu(plusOpen && chatPicked, onDismiss = { plusOpen = false }) {
                                      for (a in actions) {
                                          when (a) {
                                              PillPlus.Photos -> PiMenuItem("Photos", icon = Icons.Rounded.Image, onClick = {
                                                  plusOpen = false
                                                  photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                              })
                                              PillPlus.PasteImage -> PiMenuItem("Paste image", icon = Icons.Rounded.ContentPaste, onClick = {
                                                  plusOpen = false
                                                  clipboardImage(context)?.let { newChatWith(listOf(it)) }
                                              })
                                              PillPlus.Voice -> PiMenuItem("Voice", icon = Icons.Rounded.Mic, onClick = {
                                                  plusOpen = false
                                                  voice()
                                              })
                                              PillPlus.NewChat -> PiMenuItem("New chat", icon = Icons.Rounded.ChatBubbleOutline, onClick = {
                                                  plusOpen = false
                                                  newChatWith(emptyList())
                                              })
                                              PillPlus.NewProject -> Unit
                                          }
                                      }
                                  }
                              }
                          },
                          trailing = {
                              ComposerPrimary(hasText = ask.isNotBlank(), onSend = ::startSession, onVoice = ::voice, enabled = !starting)
                          },
                      ) {
                          BasicTextField(
                              value = ask,
                              onValueChange = { ask = it },
                              textStyle = type.rowTitle.copy(color = colors.ink1),
                              cursorBrush = SolidColor(colors.accent),
                              maxLines = 4,
                              modifier = Modifier.weight(1f).padding(vertical = Space.sm).semantics { contentDescription = if (chatPicked) "Ask Pi in a new chat" else if (target != null) "Ask Pi in ${target.title}" else "Ask Pi" },
                              decorationBox = { field ->
                                  Box(contentAlignment = Alignment.CenterStart) {
                                      if (ask.isEmpty()) Text(if (chatPicked) "Ask anything…" else "Ask Pi…", style = type.rowTitle, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                      field()
                                  }
                              },
                          )
                      }
                    }
                }
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
                            val cloned = runCatching { ProjectClone.run(app.git, url, dir) { cloneProgress = it.label } }
                            cloneProgress = null
                            cloned.exceptionOrNull()?.let { e ->
                                projects.delete(dir.name)
                                return@NewProjectSheet e.message ?: "Could not clone the repository"
                            }
                        }
                        LocalSessions.open(engine, store, dir, fresh = true, firstPrompt = url?.let(ProjectRules::clonePrompt)).fold(
                            onSuccess = {
                                reload++
                                onOpenLocalSession(it.key)
                                null
                            },
                            onFailure = {
                                reload++
                                "Project created. The session didn't open."
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
    if (spendSheet) SpendSheet(app, session = null, onLimits = { spendSheet = false; spendLimits = true }, onDismiss = { spendSheet = false })
    if (spendLimits) SpendLimitsSheet(app, onDismiss = { spendLimits = false })

    if (onboarding || addProvider) {
        ProviderSheet(
            auth = engine.auth,
            configured = stored,
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

    if (addComputer) AddComputerSheet(onScan = onScan, onPaste = { showPaste = true }, onDismiss = { addComputer = false })
    managing?.let { (id, status) ->
        val host = hosts.firstOrNull { it.id == id }
        if (host == null) {
            managing = null
        } else {
            ManageComputerSheet(
                host = host,
                status = status,
                onRename = { name -> app.hosts.update(host.id) { it.copy(alias = if (name == it.name) "" else name) } },
                onForget = { forgetting = host },
                onDismiss = { managing = null },
            )
        }
    }
    packagePage?.let { page -> PackagePageSheet(app.pages, page.key, page.title, onClose = { packagePage = null }) }
    sheetList?.let { which ->
        HomeListSheet(
            which = which,
            lives = if (which == HomeList.Working) running.filter { it !in waiting } else waiting,
            titles = meta.projects,
            onOpenLive = { onOpenLocalSession(it.key) },
            onDismiss = { sheetList = null },
        )
    }

    forgetting?.let { host ->
        ConfirmSheet(
            title = "Forget ${host.displayName}?",
            message = "To revoke this phone on the computer too, run /⁠remote-control devices there.",
            confirmLabel = "Forget",
            onConfirm = {
                app.forget(host.id)
                forgetting = null
            },
            onDismiss = { forgetting = null },
        )
    }
}

/**
 * "Computers  2", a [HomeHead] with Add at the end and a chevron that turns 180° in a graphicsLayer; the head folds the
 * remote-control section, and TalkBack reads it as Expanded / Collapsed.
 */
@Composable
private fun ComputersHeader(count: Int, open: Boolean, onToggle: () -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val turn by animateFloatAsState(if (open) 180f else 0f, PiMotion.inPlace(PiMotion.QUICK), label = "computers-chevron")
    HomeHead(
        "Computers",
        modifier
            .padding(top = Space.xl)
            .heightIn(min = 48.dp)
            .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
            .pressable(onToggle, shape = RoundedCornerShape(Radius.md), pressedScale = 1f, role = Role.Button, label = if (open) "Hide computers" else "Show computers"),
        count = count.takeIf { it > 0 },
        end = {
            TextAction("Add", onClick = onAdd, color = colors.ink1)
            Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(IconSize.md).graphicsLayer { rotationZ = turn })
        },
    )
}

/** The lists the status tiles open. */
enum class HomeList(val title: String, val empty: String, val icon: ImageVector) {
    Working("Working", "Nothing is running", Icons.Rounded.PlayArrow),
    Waiting("Waiting for you", "No questions waiting", Icons.Rounded.NotificationsNone),
}

/** A tile's list in a sheet: the live sessions working, or waiting for an answer; an [EmptyState] when there are none. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeListSheet(which: HomeList, lives: List<LocalSessions.Live>, titles: Map<String, ProjectMeta>, onOpenLive: (LocalSessions.Live) -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader(which.title)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            items(lives, key = { it.key }) { live ->
                RunningRow(live, titles[live.project.name], onClick = { close { onDismiss(); onOpenLive(live) } }, divider = live != lives.last())
            }
        }
        if (lives.isEmpty()) EmptyState(which.empty, icon = which.icon)
        Spacer(Modifier.navigationBarsPadding().height(Space.lg))
    }
}

/**
 * The pill's project, a glass chip above the pill: a folder glyph, the project's name (label ink1, at most 220dp) and
 * a chevron; [chat] shows a chat glyph and "Chat" (a plain chat, without a project). Its menu ([PiMenu]) lists Chat
 * first (chat glyph), the projects (folder glyphs), and New project; a check marks the current one.
 */
@Composable
private fun ProjectChip(title: String?, chat: Boolean, projects: List<ProjectEntry>, onPick: (ProjectEntry) -> Unit, onChat: () -> Unit, onNew: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    var open by remember { mutableStateOf(false) }
    val shown = if (chat) ChatSpace.NAME else title
    Box(modifier) {
        GlassPill(
            onClick = { if (shown == null) onNew() else open = true },
            label = if (shown == null) "New project" else "Change project",
            modifier = Modifier.semantics { contentDescription = if (chat) "Chat, no project" else if (title == null) "New project" else "Project: $title" },
        ) {
            Icon(if (chat) Icons.Rounded.ChatBubbleOutline else if (title == null) Icons.Rounded.Add else Icons.Rounded.FolderOpen, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(IconSize.sm))
            Text(shown ?: "New project", style = Pi.type.label, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
            if (shown != null) Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(IconSize.sm))
        }
        PiMenu(open, onDismiss = { open = false }) {
            PiMenuItem(ChatSpace.NAME, icon = Icons.Rounded.ChatBubbleOutline, checked = chat, onClick = {
                open = false
                onChat()
            })
            for (p in projects) {
                PiMenuItem(p.title, icon = Icons.Rounded.FolderOpen, checked = !chat && p.title == title, onClick = {
                    open = false
                    onPick(p)
                })
            }
            PiMenuItem("New project", icon = Icons.Rounded.CreateNewFolder, onClick = {
                open = false
                onNew()
            })
        }
    }
}

/** Chats shown in Recents; the rest are behind the Chats row. */
private const val RECENT_CHATS = 3

/** Home's widest column: a wide window (landscape, a tablet) centres Home at this width. */
private val HOME_MAX_WIDTH = 760.dp

/** Home's entrance plays once per launch: the tiles and the first head rise in, 120 ms + 70 ms apart. */
private object HomeEntrance {
    private var played = false

    fun claim(): Boolean = synchronized(this) { (!played).also { played = true } }
}
