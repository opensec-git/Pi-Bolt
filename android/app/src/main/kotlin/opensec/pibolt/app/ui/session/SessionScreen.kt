package opensec.pibolt.app.ui.session

import kotlinx.coroutines.flow.update
import android.Manifest
import android.net.Uri
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import java.io.File
import opensec.pibolt.app.ui.providers.ProviderCatalog
import opensec.pibolt.app.ui.providers.ProviderSheet
import opensec.pibolt.protocol.session.NoticeItem
import opensec.pibolt.protocol.session.ThinkingItem
import opensec.pibolt.protocol.session.TodoStatus
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Html
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import opensec.pibolt.app.engine.SessionSummary
import opensec.pibolt.app.session.ForkPoint
import opensec.pibolt.app.ui.session.ask.AskQuestions
import opensec.pibolt.app.ui.session.ask.QuestionSheet
import opensec.pibolt.app.ui.session.features.Builtin
import opensec.pibolt.app.ui.session.review.LocalTurnReview
import opensec.pibolt.app.ui.session.review.TurnReviewOverlay
import opensec.pibolt.app.ui.session.review.rememberTurnReview
import opensec.pibolt.app.ui.session.features2.F2Sheet
import opensec.pibolt.protocol.str
import opensec.pibolt.app.ui.session.features2.AppExtension
import opensec.pibolt.app.ui.session.tree.SessionTree
import opensec.pibolt.app.ui.session.features2.Features2
import opensec.pibolt.app.ui.session.features2.Features2Host
import opensec.pibolt.app.ui.session.features2.Features2Sheets
import opensec.pibolt.app.ui.session.features2.rememberFeatures2
import opensec.pibolt.app.ui.session.features2.rememberImportPicker
import opensec.pibolt.app.ui.session.features2.rememberScopedModels
import opensec.pibolt.app.ui.session.features2.run
import opensec.pibolt.app.ui.session.features.BuiltinCall
import opensec.pibolt.app.ui.session.features.CommandPalette
import opensec.pibolt.app.ui.session.features.Drafts
import opensec.pibolt.app.ui.session.features.ForkSheet
import opensec.pibolt.app.ui.session.features.ImageViewer
import opensec.pibolt.app.ui.session.features.MessageText
import opensec.pibolt.app.ui.session.features.ProjectFileIndex
import opensec.pibolt.app.ui.session.features.ResumeSheet
import opensec.pibolt.app.ui.session.features.SearchBar
import opensec.pibolt.app.ui.session.features.SearchHit
import opensec.pibolt.app.ui.session.features.SearchJump
import opensec.pibolt.app.ui.session.features.SessionFacts
import opensec.pibolt.app.ui.session.features.SessionMarkdown
import opensec.pibolt.app.ui.session.features.SessionStats
import opensec.pibolt.app.ui.session.features.SessionStatsSheet
import opensec.pibolt.app.ui.session.features.ShareFiles
import opensec.pibolt.app.ui.session.features.ShellCommand
import opensec.pibolt.app.ui.session.features.TranscriptSearch
import opensec.pibolt.app.ui.session.features.ViewerImages
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.first
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.ui.terminal.PreviewChipRow
import opensec.pibolt.app.data.ProjectStore
import opensec.pibolt.app.ui.projects.LocalSessions
import opensec.pibolt.app.ui.projects.FileViewer
import opensec.pibolt.app.ui.projects.editor.AskPi
import opensec.pibolt.app.ui.projects.editor.EditorTabs
import opensec.pibolt.app.ui.projects.editor.viewerPlace
import opensec.pibolt.app.ui.session.voice.VoiceModeEffects
import opensec.pibolt.app.ui.session.voice.VoiceRationale
import opensec.pibolt.app.ui.session.voice.rememberVoiceMode
import opensec.pibolt.app.ui.session.voice.rememberVoiceInput
import opensec.pibolt.app.ui.session.remote.RemoteChangesPill
import opensec.pibolt.app.ui.session.remote.RemoteReviewOverlays
import opensec.pibolt.app.ui.session.remote.RemoteView
import opensec.pibolt.app.ui.session.remote.rememberRemoteReview
import opensec.pibolt.app.ui.session.review.SessionChangesPill
import opensec.pibolt.app.data.RecentPrompts
import opensec.pibolt.app.session.LinkState
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.app.session.SessionEffect
import opensec.pibolt.app.cost.Cost
import opensec.pibolt.app.cost.Money
import opensec.pibolt.app.ui.components.ConfirmSheet
import opensec.pibolt.app.ui.cost.CapNotice
import opensec.pibolt.app.ui.cost.SpendLimitsSheet
import opensec.pibolt.app.ui.cost.SpendSheet
import opensec.pibolt.app.ui.components.PiSnackHost
import opensec.pibolt.app.ui.components.QuadMath
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.SharedKeys
import opensec.pibolt.app.ui.components.SnackKind
import opensec.pibolt.app.ui.components.sharedHostBounds
import opensec.pibolt.app.ui.components.show
import opensec.pibolt.app.ui.components.snackKindOf
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Space
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.protocol.rpc.ModelInfo
import opensec.pibolt.protocol.rpc.StreamingBehavior
import opensec.pibolt.protocol.session.AssistantTextItem
import opensec.pibolt.protocol.session.UserItem
import opensec.pibolt.protocol.session.SessionState

/**
 * A session running on this phone (the on-device engine): shown under its project's [title], with the engine's own
 * actions instead of the remote ones (no Disconnect or Forget computer).
 */
class LocalUi(
    /** The project's folder name. */
    val project: String,
    val title: String,
    /** A new session in the project; beside this one if it is busy (both keep running). */
    val onNewSession: () -> Unit,
    /** Open a saved session of the project; beside this one if it is busy. Null: this engine switches to it. */
    val onOpenFile: ((String) -> Unit)? = null,
    /** Restart the engine and reconnect. */
    val onRestart: () -> Unit,
    /** A prompt queued when the session was created ("Clone from git"); returned once. */
    val takeFirstPrompt: () -> String?,
    /** Open a dev server's preview (the "Preview: localhost:3000" chip). */
    val onPreview: (Int) -> Unit = {},
    /** Text and images waiting for the composer ("Ask Pi" in the editor, shares from other apps). */
    val composerDrop: MutableStateFlow<LocalSessions.ComposerDrop?>? = null,
    /** A plain chat (ui/chats): no project, no tools, so none of the project-only parts ([SessionParts.CHAT]). */
    val chat: Boolean = false,
    /** The empty session's own greeting and suggestions (a chat's), instead of the project's. */
    val welcome: WelcomeContent? = null,
    /** A chat's top bar: the Chats list. */
    val onChats: (() -> Unit)? = null,
    /** A chat's top bar: delete this chat (after a confirmation) and leave. */
    val onDelete: (() -> Unit)? = null,
    /** The user picked a model in the model sheet (a chat remembers it for the next new chat). */
    val onModelPicked: (ModelInfo) -> Unit = {},
    /** The user picked a thinking level. */
    val onThinkingPicked: (String) -> Unit = {},
)

/** A welcome of its own: [title] over [question] (both largeTitle, ink1 and ink3), then [starters]. */
class WelcomeContent(val title: String, val question: String, val starters: List<StarterPrompt>)

/**
 * The remote session ([local] null) or the on-device one in project [local]. [onOpenSession] shows another live
 * on-device session (the switcher), by project name; this one keeps running.
 */
@Composable
fun SessionScreen(
    app: PiBoltApp,
    onBack: () -> Unit,
    onForgotten: () -> Unit,
    local: String? = null,
    onOpenSession: (String) -> Unit = {},
    onPreview: (Int) -> Unit = {},
) {
    if (local != null) {
        LocalSessionScreen(app, local, onBack, onOpenSession, onPreview)
        return
    }
    val controller by app.remote.collectAsStateWithLifecycle()
    val c = controller
    if (c == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    LaunchedEffect(c) { app.focus(c) }
    SessionContent(
        app = app,
        controller = c,
        onBack = onBack,
        onDisconnect = {
            app.closeSession()
            onBack()
        },
        onForget = {
            app.forget(c.hostId)
            onForgotten()
        },
        local = null,
        onOpenSession = onOpenSession,
    )
}

@Composable
private fun LocalSessionScreen(app: PiBoltApp, key: String, onBack: () -> Unit, onOpenSession: (String) -> Unit, onPreview: (Int) -> Unit) {
    // Each screen shows one live session ([key]: LocalSessions.Live.key); the others keep running meanwhile.
    val all by LocalSessions.all.collectAsStateWithLifecycle()
    val live = all[key]
    if (live == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    LaunchedEffect(live.controller) { app.focus(live.controller) }
    // While this screen shows the session, it sends a queued first prompt itself (LocalSessions.Live.queueFirstPrompt).
    DisposableEffect(live) {
        live.screens.update { it + 1 }
        onDispose { live.screens.update { it - 1 } }
    }
    val store = remember { ProjectStore.get(app) }
    val meta by store.state.collectAsStateWithLifecycle()
    val project = live.project.name
    val title = meta.projects[project]?.title ?: project
    val scope = rememberCoroutineScope()
    val engine = app.engine
    // A new session replaces the controller: everything below starts fresh for it.
    key(live.controller) {
        SessionContent(
            app = app,
            controller = live.controller,
            onBack = onBack,
            onDisconnect = onBack,
            onForget = onBack,
            local = LocalUi(
                project = project,
                title = title,
                // Opened beside this session when it is busy: the result may live under another key.
                onNewSession = { scope.launch { opened(LocalSessions.open(engine, store, live.project, fresh = true), key, live, onOpenSession) } },
                onOpenFile = { file -> scope.launch { opened(LocalSessions.open(engine, store, live.project, sessionFile = file), key, live, onOpenSession) } },
                onRestart = {
                    engine.prepare()
                    live.controller.retryNow()
                },
                takeFirstPrompt = live::takeFirstPrompt,
                onPreview = onPreview,
                composerDrop = live.composerDrop,
            ),
            onOpenSession = onOpenSession,
        )
    }
}

/** After an open from session [from] (shown under [key]): show the result where it lives, or say why it did not open. */
private fun opened(result: Result<LocalSessions.Live>, key: String, from: LocalSessions.Live, onOpenSession: (String) -> Unit) {
    result
        .onSuccess { if (it.key != key) onOpenSession(it.key) }
        .onFailure { from.controller.showError(it.message ?: "Could not open the session") }
}

private enum class Sheet { None, Model, Thinking, Info, Rename, Compact, Forget, Export, Resume, Fork, Attach, History, Key, DeleteChat, Spend, SpendLimits }

/**
 * The session screen. It never reads the whole [SessionState] itself: each part derives the few values it shows, so a
 * streamed frame recomposes only the transcript's live rows and whatever value actually changed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SessionContent(
    app: PiBoltApp,
    controller: SessionController,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
    local: LocalUi?,
    onOpenSession: (String) -> Unit,
) {
    val state: State<SessionState> = controller.state.collectAsStateWithLifecycle()
    val projectStore = remember { ProjectStore.get(app) }
    val projectMeta = projectStore.state.collectAsStateWithLifecycle().value.projects
    val linkState = controller.link.collectAsStateWithLifecycle()
    val hostName by controller.hostName.collectAsStateWithLifecycle()
    val sessionId by remember { derivedStateOf { state.value.sessionId } }
    val memory = remember(controller.memoryKey, sessionId) { SessionMemory.of(controller.memoryKey, sessionId) }
    val colors = Pi.colors
    val haptics = Pi.haptics
    val settings = Pi.settings
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable(controller.hostId) { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(StreamingBehavior.Steer) }
    var sheet by remember { mutableStateOf(Sheet.None) }
    var remoteSessions by remember { mutableStateOf(false) }
    val service by remember { derivedStateOf { state.value.host?.service == true } }
    // The computer's working tree, read-only (`host.review`): Changes and Files in More, the "+N −M" pill.
    val remoteReview = rememberRemoteReview(controller, state)
    val f2 = rememberFeatures2()
    var viewer by remember { mutableStateOf<ViewerContent?>(null) }
    // A file on this phone tapped in a tool card opens in the full file viewer (tabs kept while the screen lives).
    var openedFile by remember { mutableStateOf<File?>(null) }
    val fileTabs = remember { EditorTabs() }
    var actionsFor by remember { mutableStateOf<TRow?>(null) }
    var statusOnScreen by remember { mutableStateOf(false) }
    val images = remember(controller.hostId) { mutableStateListOf<ComposerImage>() }
    var nextImageId by remember { mutableLongStateOf(1L) }
    // The text and images of the last send, given back to the composer if it fails.
    var lastSent by remember { mutableStateOf<Pair<String, List<ComposerImage>>?>(null) }
    val focus = remember { FocusRequester() }
    val recentAll by app.recents.prompts.collectAsStateWithLifecycle()
    // Recent prompts are per computer, or per project for on-device sessions.
    val recentKey = local?.let { "project:" + it.project } ?: controller.hostId
    val title = local?.title ?: hostName
    val isLocal = local != null
    val chat = local?.chat == true
    val parts = SessionParts.of(isLocal, chat)
    // What this screen calls itself in its sheets and messages.
    val noun = if (chat) "chat" else "session"
    val drafts = remember { Drafts(app) }
    val files = remember(controller) { ProjectFileIndex(controller.project) }
    val f2Host = remember(controller) {
        Features2Host(
            app = app,
            controller = controller,
            isLocal = isLocal,
            insert = {
                draft = it
                focus.requestFocus()
            },
            message = { m -> scope.launch { snackbar.show(m) } },
            view = { viewer = it },
            openSession = { local?.onOpenFile?.invoke(it.file) ?: controller.switchSession(it.file) },
        )
    }
    val pickImport = rememberImportPicker(f2Host)
    var modelQuery by remember { mutableStateOf("") }
    var stats by remember { mutableStateOf<SessionStats?>(null) }
    var statsLoading by remember { mutableStateOf(false) }
    var forkPoints by remember { mutableStateOf<List<ForkPoint>?>(null) }
    var savedSessions by remember { mutableStateOf<List<SessionSummary>?>(null) }
    var imageViewer by remember { mutableStateOf<ViewerImages?>(null) }
    // Search in the transcript: the query, its hits (oldest first) and the hit shown.
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var hitIndex by remember { mutableIntStateOf(-1) }
    var jump by remember { mutableStateOf<SearchJump?>(null) }
    var jumpSeq by remember { mutableIntStateOf(0) }

    // The draft is kept per session across restarts and saved as it changes. [draftOwner] is the session whose draft the
    // composer holds: on a switch (Resume, Fork) the outgoing draft is saved under it and the incoming one is loaded,
    // or the text a fork hands over ([forkDraft]).
    val draftKey = Drafts.key(controller.memoryKey, sessionId)
    var draftOwner by rememberSaveable(controller.hostId) { mutableStateOf<String?>(null) }
    var forkDraft by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(draftKey) {
        if (sessionId != null && draftKey != draftOwner) {
            val previous = draftOwner
            if (previous != null) drafts.put(previous, draft)
            val incoming = forkDraft
            forkDraft = null
            draft = incoming ?: if (previous != null || draft.isEmpty()) drafts.get(draftKey) else draft
            draftOwner = draftKey
        }
        snapshotFlow { draft }.drop(1).collectLatest {
            delay(400)
            if (sessionId != null) drafts.put(draftKey, it)
        }
    }
    // Leaving the screen (Back, New chat on a new engine) within the 400 ms above kept the last keystrokes unsaved.
    DisposableEffect(draftKey) {
        onDispose { if (sessionId != null && draftOwner == draftKey) drafts.put(draftKey, draft) }
    }
    LaunchedEffect(searching, query) {
        if (!searching || query.isBlank()) {
            hits = emptyList()
            hitIndex = -1
            return@LaunchedEffect
        }
        delay(150)
        val items = state.value.allItems
        val found = withContext(Dispatchers.Default) { TranscriptSearch.search(items, query) }
        hits = found
        // The reader is at the bottom: start from the newest hit.
        hitIndex = found.lastIndex
        found.lastOrNull()?.let { jump = SearchJump(it, ++jumpSeq) }
    }
    fun step(by: Int) {
        if (hits.isEmpty()) return
        hitIndex = Math.floorMod(hitIndex + by, hits.size)
        jump = SearchJump(hits[hitIndex], ++jumpSeq)
        haptics.tick()
    }

    LaunchedEffect(memory, controller) { memory.attach(controller.state, app.appScope) }
    LaunchedEffect(controller) {
        controller.effects.collect { effect ->
            when (effect) {
                is SessionEffect.Notify -> scope.launch { snackbar.show(effect.message, snackKindOf(effect.kind)) }
                is SessionEffect.Error -> {
                    haptics.reject()
                    scope.launch { snackbar.show(effect.message, SnackKind.Error) }
                }
                is SessionEffect.EditorText -> {
                    // A failed send or the queue given back joins what was typed meanwhile (it used to replace it).
                    draft = effect.into(draft)
                    // A send that failed comes back to the composer: its "Sending" bubble goes, its images return.
                    SessionMemory.of(controller.memoryKey, controller.state.value.sessionId).pending.let { p -> if (p.value?.text == effect.text) p.value = null }
                    lastSent?.takeIf { it.first == effect.text && images.isEmpty() }?.let { images.addAll(it.second) }
                }
            }
        }
    }

    // Asked once, the first time a prompt is sent from this phone (so Pi can say when it finishes); never again.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun maybeAskNotifications() {
        if (Build.VERSION.SDK_INT < 33 || settings.notificationsAsked) return
        app.settings.update { it.copy(notificationsAsked = true) }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun share(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, null))
    }

    // Dictation (the pill's mic and voice surface). [dictated]: the draft holds dictated words, so the message it
    // becomes carries the waveform badge.
    var dictated by remember { mutableStateOf(false) }
    val voice = rememberVoiceInput(draft, onDraft = { draft = it }, onMessage = { m -> scope.launch { snackbar.show(m) } }, onHeard = { dictated = true })

    fun copy(text: String) {
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Pi-Bolt", text))) }
        haptics.confirm()
        if (Build.VERSION.SDK_INT < 33) scope.launch { snackbar.show("Copied") }
    }

    val hello by remember { derivedStateOf { state.value.hello } }
    val running by remember { derivedStateOf { state.value.isRunning } }
    val compacting by remember { derivedStateOf { state.value.isCompacting } }
    val shellRuns by controller.shellRuns.collectAsStateWithLifecycle()
    val connected by remember { derivedStateOf { linkState.value is LinkState.Connected } }
    val enabled = connected && hello
    val quadMode by remember {
        derivedStateOf {
            val s = state.value
            val activity = TranscriptModel.activity(s.items, s.streaming)
            QuadMath.modeFor(
                dialogOpen = s.dialogs.isNotEmpty(),
                compacting = s.isCompacting,
                subagentRunning = activity.subagent,
                toolRunning = activity.toolRunning,
                writing = activity.writing,
                connected = linkState.value is LinkState.Connected,
            )
        }
    }
    val busy by remember { derivedStateOf { state.value.isRunning || state.value.isCompacting || state.value.dialogs.isNotEmpty() || linkState.value !is LinkState.Connected } }
    // Keyed on the session's memory: after a fork or a resume the status follows the new session's pending send.
    val status = remember(memory) {
        derivedStateOf {
            val s = state.value
            val sending = memory.pending.value != null && !s.isRunning
            val m = if (sending) QuadMode.Think else quadMode
            val activity = TranscriptModel.activity(s.items, s.streaming)
            val retry = s.retry
            val thinks = ThinkingRules.on(s.thinkingLevel, s.model?.reasoning)
            val thinkingLive = (s.streaming.lastOrNull() as? ThinkingItem)?.let { it.streaming && ThinkingRules.shows(it, thinks) } == true
            val text = when {
                retry != null -> StatusText("Retrying", "attempt ${retry.attempt} of ${retry.maxAttempts}")
                s.isCompacting && m == QuadMode.Compact -> StatusText(compactionLabel(s.compactionReason))
                // The live thinking row already says "Thinking": the status line keeps only the elapsed time.
                thinkingLive && m == QuadMode.Think && !sending -> StatusText("")
                else -> TranscriptModel.statusText(m, activity, sending, thinking = thinks)
            }
            LiveStatus(visible = s.isRunning || s.isCompacting || sending, mode = if (retry != null) QuadMode.Connect else m, text = text)
        }
    }
    // A send the host never answers (no echo, no run) stops showing "Sending" after a while.
    val pending = memory.pending.value
    LaunchedEffect(pending) {
        if (pending == null) return@LaunchedEffect
        delay(PendingSend.TIMEOUT_MS)
        // A prompt held for a manual compaction (SessionController.send) is still on its way.
        if (memory.pending.value === pending && !state.value.isRunning && !state.value.isCompacting) memory.pending.value = null
    }
    val subtitle by remember {
        derivedStateOf {
            val s = state.value
            // The session name, or for a computer its folder. The model and thinking level live in the composer chip,
            // the context in the top bar's meter. On this phone the title already is the project folder.
            (if (!parts.subtitle) null else if (local != null) s.sessionName else s.sessionName ?: s.host?.cwd?.takeIf { it.isNotBlank() }?.let(::baseName)).orEmpty()
        }
    }
    // Context use: hidden until a turn reports usage, and kept through a turn that reports none (a failed request
    // reports 0 tokens, which used to reset the meter to 0%).
    var knownTokens by remember(sessionId) { mutableStateOf<Long?>(null) }
    LaunchedEffect(state, sessionId) {
        snapshotFlow { state.value.lastUsage?.totalTokens?.takeIf { it > 0 } }.collect { if (it != null) knownTokens = it }
    }
    val contextWindow by remember { derivedStateOf { state.value.model?.contextWindow } }
    val contextPct = knownTokens?.let { contextPercent(it, contextWindow) }
    val modelLabel by remember { derivedStateOf { state.value.model?.label } }
    val sessionCost by controller.cost.collectAsStateWithLifecycle()
    val capCard by controller.capCard.collectAsStateWithLifecycle()
    // The maker's mark for the run-settings chip: "anthropic/claude-…" through OpenRouter is Anthropic's.
    val modelBrand by remember { derivedStateOf { state.value.model?.let { ProviderCatalog.modelBrand(it.id, it.provider) } } }
    val cwd by remember { derivedStateOf { state.value.host?.cwd } }
    val thinkingLevel by remember { derivedStateOf { state.value.thinkingLevel } }
    // The composer's thinking chip is left out for a model that does not reason.
    val reasons by remember { derivedStateOf { state.value.model?.reasoning != false } }
    val commands by remember { derivedStateOf { state.value.commands } }
    val palette by remember(local != null, chat) { derivedStateOf { CommandPalette.entries(state.value.commands, local != null, chat) } }
    val queue by remember { derivedStateOf { state.value.queue } }
    // Changes only when a todo result or a run boundary changes the list.
    val todos by remember { derivedStateOf { state.value.todos } }
    val firstDialog by remember { derivedStateOf { state.value.dialogs.firstOrNull() } }
    val empty by remember(memory) { derivedStateOf { state.value.hello && state.value.items.isEmpty() && state.value.streaming.isEmpty() && !state.value.isRunning && memory.pending.value == null } }

    val modelReadsImages by remember { derivedStateOf { state.value.model?.acceptsImages } }
    val modelProvider by remember { derivedStateOf { state.value.model?.provider } }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // The project's traits for the empty session's suggestions (on-device sessions; read once, on IO).
    val traits by produceState<ProjectTraits?>(null, controller.project) {
        value = controller.project?.takeIf { parts.projectStarters }?.let { dir -> withContext(Dispatchers.IO) { runCatching { StarterPrompts.traits(dir) }.getOrNull() } }
    }
    val branch by produceState<String?>(null, controller.project) {
        value = controller.project?.let { dir ->
            withContext(Dispatchers.IO) { runCatching { File(dir, ".git/HEAD").takeIf { it.isFile }?.readText()?.trim()?.removePrefix("ref: refs/heads/") }.getOrNull() }
        }
    }

    /** Sends [text] as typed (a prompt when idle, else per [mode]) with [attached] images and shows it at once. */
    fun send(text: String, attached: List<ComposerImage> = emptyList(), behavior: StreamingBehavior = mode, fromDraft: Boolean = false) {
        // The controller's state, not this composition's: right after a fork (Regenerate) the controller already holds the
        // new session, and the send belongs in that session's memory, or its "Sending" row would never resolve.
        val now = controller.state.value
        val running = now.isRunning
        // A spending limit holds it back: the limit's card says so (nothing shows as sending).
        if (!controller.send(text, behavior, attached.mapNotNull { it.image })) return
        SessionMemory.of(controller.memoryKey, now.sessionId).sent(text, now, running, attached.mapNotNull { it.thumb }, attached.mapNotNull { it.image }, dictated = fromDraft && dictated)
        if (fromDraft) dictated = false
        if (text.isNotBlank()) app.recents.record(recentKey, text)
        lastSent = text to attached
        maybeAskNotifications()
    }

    fun noImages(): String = "${modelLabel ?: "This model"} can't read images"

    // Hands-free voice mode (ui/session/voice): what it hears is sent as a dictated message.
    val voiceMode = rememberVoiceMode(
        voice,
        running = { controller.state.value.isRunning },
        send = { text ->
            dictated = true
            send(text, fromDraft = true)
        },
        submit = controller::submitAsk,
        settings = settings,
        onMessage = { m -> scope.launch { snackbar.show(m) } },
    )

    // "Clone from git" queues the clone prompt: it goes out once the new session can take it.
    if (local != null) {
        LaunchedEffect(enabled) {
            if (enabled) local.takeFirstPrompt()?.let { send(it) }
        }
    }

    /**
     * Puts [text] in the composer of the session a fork opened. The fork's reply and the new session's snapshot come in
     * either order: when the composer still holds [from]'s draft, the text waits for the switch.
     */
    fun forkedDraft(from: String?, text: String) {
        if (draftOwner == from) forkDraft = text else draft = text
    }

    /**
     * A chat keeps one conversation, as ChatGPT's does: Regenerate and Edit and resend move the chat back to before the
     * [occurrence]-th user message [text] in the same file (Pi's session tree, `navigateTree`; the old answer stays a
     * branch, reachable from /tree), instead of forking into a new chat that would crowd the Chats list. Returns the
     * message's text.
     *
     * The target is found on the active branch (`get_entries`, [SessionTree.rewindTarget]), counting copies of [text]
     * back from the newest as the transcript shows them: `get_fork_messages` also lists the prompts of branches an
     * earlier regenerate left, so counting there picked an older copy.
     */
    suspend fun rewind(text: String, occurrence: Int): Result<String> {
        val shown = state.value.items.filterIsInstance<UserItem>().count { it.text.trim() == text.trim() }
        val fromEnd = shown - 1 - occurrence
        val entries = controller.request("get_entries", timeoutMs = 60_000)
        val id = SessionTree.rewindTarget(entries.dataObject?.takeIf { entries.success }, text, fromEnd).takeIf { fromEnd >= 0 }
            ?: return Result.failure(IllegalStateException("That message can't be edited"))
        return AppExtension.run(controller, File(context.cacheDir, "pibolt-app"), "goto", id).map { r ->
            controller.resync()
            r.str("editorText")?.takeIf { it.isNotBlank() } ?: text
        }
    }

    /**
     * Forks the session from a user message ([text], its [occurrence]-th copy in the transcript); the text comes back
     * to the composer to edit and send. A chat rewinds in place instead ([rewind]), and forks only when that fails.
     */
    fun fork(text: String, occurrence: Int) {
        val from = draftOwner
        scope.launch {
            if (chat) {
                val rewound = rewind(text, occurrence).getOrNull()
                if (rewound != null) {
                    draft = rewound
                    focus.requestFocus()
                    haptics.confirm()
                    snackbar.show("Edit your message, then send.", SnackKind.Success)
                    return@launch
                }
            }
            controller.forkFrom(text, occurrence)
                .onSuccess {
                    forkedDraft(from, it)
                    haptics.confirm()
                    snackbar.show("Forked into a new $noun. Edit the message and send.", SnackKind.Success)
                }
                .onFailure {
                    haptics.reject()
                    snackbar.show(it.message ?: "Fork failed", SnackKind.Error)
                }
        }
    }

    val review = rememberTurnReview(controller.checkpoints, state, message = { m, kind -> scope.launch { snackbar.show(m, kind) } }, forkAt = ::fork)

    fun openStats() {
        sheet = Sheet.Info
        statsLoading = true
        scope.launch {
            controller.sessionStats().onSuccess { stats = it }
            statsLoading = false
        }
    }

    fun openModels(search: String = "") {
        modelQuery = search
        controller.loadModels()
        // The model sheet also sets the thinking level (one run-settings chip, one sheet).
        if (reasons) controller.loadThinkingLevels()
        sheet = Sheet.Model
    }

    fun openThinking() {
        controller.loadThinkingLevels()
        sheet = Sheet.Thinking
    }

    fun cycleModel() {
        scope.launch {
            controller.cycleModel()
                .onSuccess { label ->
                    haptics.tick()
                    snackbar.show(label?.let { "Model: $it" } ?: "No other model to switch to")
                }
                .onFailure { snackbar.show(it.message ?: "Could not switch model", SnackKind.Error) }
        }
    }

    fun cycleThinking() {
        scope.launch {
            controller.cycleThinking()
                .onSuccess { level ->
                    haptics.tick()
                    snackbar.show(level?.let { thinkingChipLabel(it) } ?: "This model does not reason")
                }
                .onFailure { snackbar.show(it.message ?: "Could not change thinking level", SnackKind.Error) }
        }
    }

    fun openResume() {
        if (controller.project == null) return
        // Sort, filter, search inside messages, rename and delete (features2).
        f2.sheet = F2Sheet.Sessions
    }

    fun openFork() {
        forkPoints = null
        sheet = Sheet.Fork
        scope.launch { forkPoints = controller.forkPoints() }
    }

    fun lastReply(): String? {
        val items = state.value.items
        val lastUser = items.indexOfLast { it is UserItem }
        return items.drop(lastUser + 1).filterIsInstance<AssistantTextItem>().map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n").ifEmpty { null }
    }

    /** Runs one of the app's own commands (typed and sent, or picked from the palette). */
    fun runBuiltin(call: BuiltinCall) {
        if (Features2.handles(call.builtin) && f2.run(call.builtin, call.args, f2Host, scope, pickImport)) return
        when (call.builtin) {
            Builtin.New -> local?.onNewSession?.invoke()
            Builtin.Resume -> openResume()
            Builtin.Fork -> openFork()
            Builtin.Clone -> controller.cloneSession()
            Builtin.Compact -> if (call.args.isBlank()) sheet = Sheet.Compact else controller.compact(call.args)
            Builtin.Name -> if (call.args.isBlank()) sheet = Sheet.Rename else controller.rename(call.args)
            Builtin.Session -> openStats()
            Builtin.Model -> openModels(call.args)
            Builtin.Thinking -> if (call.args.isBlank()) openThinking() else controller.setThinkingLevel(call.args.lowercase())
            Builtin.Copy -> lastReply()?.let(::copy) ?: scope.launch { snackbar.show("No reply to copy yet") }
            Builtin.Export -> sheet = Sheet.Export
            Builtin.Search -> {
                query = call.args
                searching = true
            }
            Builtin.Shell -> {
                val shell = ShellCommand.parse(call.args)
                if (shell == null) {
                    scope.launch { snackbar.show("Type a command after !") }
                } else {
                    haptics.send()
                    controller.runShell(shell)
                    memory.sends.intValue++
                    if (shell.command.isNotBlank()) app.recents.record(recentKey, "!" + call.args)
                }
            }
            Builtin.Share -> sheet = Sheet.Export
            else -> Unit
        }
    }

    fun exportMarkdown(share: Boolean) {
        val s = state.value
        val name = s.sessionName ?: title
        val markdown = SessionMarkdown.render(s.items, name, s.model?.let { "${it.provider}/${it.id}" })
        if (!share) {
            copy(markdown)
            return
        }
        scope.launch {
            val file = ShareFiles.write(context, SessionMarkdown.fileName(name, "md"), markdown)
            ShareFiles.share(context, file, "text/markdown", name)
        }
    }

    fun exportJsonl() {
        val path = controller.sessionFile ?: return
        scope.launch {
            runCatching { ShareFiles.copy(context, java.io.File(path), SessionMarkdown.fileName(state.value.sessionName ?: title, "jsonl")) }
                .onSuccess { ShareFiles.share(context, it, "application/x-ndjson") }
                .onFailure { snackbar.show("Could not read the session file", SnackKind.Error) }
        }
    }

    fun exportHtml() {
        val file = java.io.File(ShareFiles.exportsDir(context), SessionMarkdown.fileName(state.value.sessionName ?: title, "html"))
        scope.launch {
            snackbar.show("Exporting…")
            controller.exportHtml(file.absolutePath)
                .onSuccess { ShareFiles.share(context, java.io.File(it), "text/html") }
                .onFailure { snackbar.show(it.message ?: "Export failed", SnackKind.Error) }
        }
    }

    /**
     * Regenerates the answer to [user] (its [occurrence]-th copy): forks before it and sends it again. Text only, so it
     * is offered only for prompts without images.
     */
    fun regenerate(user: UserItem, occurrence: Int) {
        val before = controller.state.value.sessionId
        scope.launch {
            val lastUser = PendingSend.lastUserKey(controller.state.value.items)
            if (chat && rewind(user.text, occurrence).isSuccess) {
                // The rewound transcript is only queued (resync): sent against the old one, the "Sending" row took the
                // answered message as its base and vanished as soon as the rewind showed, before the run started.
                withTimeoutOrNull(FORK_SWITCH_MS) { controller.state.first { PendingSend.lastUserKey(it.items) != lastUser } }
                send(user.text)
                return@launch
            }
            controller.forkFrom(user.text, occurrence)
                .onSuccess {
                    // The prompt goes to the fork: wait for its session before sending (the reply and its snapshot come
                    // in either order).
                    withTimeoutOrNull(FORK_SWITCH_MS) { controller.state.first { s -> s.sessionId != before } }
                    send(it.ifEmpty { user.text })
                }
                .onFailure {
                    haptics.reject()
                    snackbar.show(it.message ?: "Could not regenerate", SnackKind.Error)
                }
        }
    }

    fun addImages(uris: List<Uri>) {
        val room = ImageSizing.slotsLeft(images.size)
        if (uris.size > room) scope.launch { snackbar.show("Up to ${ImageSizing.MAX_IMAGES} images per message") }
        for (uri in uris.take(room)) {
            val id = nextImageId++
            images += ComposerImage(id)
            scope.launch {
                when (val result = encodeImage(context.contentResolver, uri)) {
                    is Encoded.Ok -> {
                        val i = images.indexOfFirst { it.id == id }
                        if (i >= 0) images[i] = ComposerImage(id, result.image, result.thumb)
                    }
                    is Encoded.Failed -> {
                        images.removeAll { it.id == id }
                        snackbar.show(result.message, SnackKind.Error)
                    }
                }
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ImageSizing.MAX_IMAGES)) { addImages(it) }

    // What the editor or another app left for the composer, once this session's draft is loaded (it would be
    // overwritten otherwise), and later drops while the screen stays open.
    LaunchedEffect(draftOwner) {
        val inbox = local?.composerDrop ?: return@LaunchedEffect
        if (draftOwner == null) return@LaunchedEffect
        inbox.filterNotNull().collect { d ->
            inbox.value = null
            d.text?.let { draft = AskPi.appendTo(draft, it).first }
            if (d.images.isNotEmpty()) addImages(d.images)
        }
    }

    /** Ctrl+V or "Paste image": attaches the clipboard's image; false when there is none (text paste goes on). */
    fun pasteImage(): Boolean {
        val uri = clipboardImage(context) ?: return false
        when {
            modelReadsImages == false -> scope.launch { snackbar.show(noImages()) }
            ImageSizing.slotsLeft(images.size) == 0 -> scope.launch { snackbar.show("Up to ${ImageSizing.MAX_IMAGES} images per message") }
            else -> {
                haptics.tick()
                addImages(listOf(uri))
            }
        }
        return true
    }

    fun attach() {
        when {
            modelReadsImages == false -> scope.launch { snackbar.show(noImages()) }
            ImageSizing.slotsLeft(images.size) == 0 -> scope.launch { snackbar.show("Up to ${ImageSizing.MAX_IMAGES} images per message") }
            else -> picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    /** The prompt of the turn that ended in [notice], to send again. */
    fun promptBefore(notice: NoticeItem): UserItem? {
        val items = state.value.items
        val at = items.indexOfFirst { it.key == notice.key }.takeIf { it >= 0 } ?: items.size
        return items.subList(0, at).lastOrNull { it is UserItem } as? UserItem
    }

    fun onErrorAction(action: ErrorAction, notice: NoticeItem) {
        when (action) {
            ErrorAction.Retry -> {
                val prompt = promptBefore(notice)
                if (prompt == null || prompt.text.isBlank()) {
                    scope.launch { snackbar.show("Nothing to retry") }
                } else if (prompt.imageCount > 0) {
                    // Its images are not kept: sent again, the prompt went without them. The text waits in the composer.
                    draft = prompt.text
                    focus.requestFocus()
                    scope.launch { snackbar.show("Attach the images again, then send") }
                } else {
                    haptics.send()
                    send(prompt.text)
                }
            }
            ErrorAction.SwitchModel -> openModels()
            ErrorAction.Compact -> {
                haptics.confirm()
                controller.compact(null)
            }
            ErrorAction.UpdateKey -> sheet = Sheet.Key
            ErrorAction.NewSession -> local?.onNewSession?.invoke()
            ErrorAction.Stop -> {
                haptics.confirm()
                // Stops only the retry (`abort_retry`); an engine without it aborts the run.
                scope.launch { if (!controller.request("abort_retry").success) controller.abort() }
            }
        }
    }

    /** Puts [text] in the composer and focuses it (never sends). */
    fun fill(text: String) {
        draft = text
        focus.requestFocus()
    }
    // The empty session's content, and the composer's entrance as its last step: when a new session first shows its
    // welcome, the composer fades in and rises after the welcome's elements (PiMotion.stagger). Once per screen.
    val reduceMotion = Pi.reduceMotion
    val welcome = local?.welcome
    val welcomeStarters = welcome?.starters ?: if (isLocal) traits?.let { StarterPrompts.pick(it) }.orEmpty() else StarterPrompts.pick(null)
    val welcomeRecent = recentAll[recentKey].orEmpty().take(Welcome.RECENT)
    val welcomeCommands = { fill("/") }.takeIf { palette.isNotEmpty() }
    val composerIn = remember { Animatable(1f) }
    val welcomeStep by rememberUpdatedState(Welcome.composerStep(welcomeStarters.size, welcomeRecent.isNotEmpty(), welcomeCommands != null))
    LaunchedEffect(Unit) {
        if (reduceMotion) return@LaunchedEffect
        snapshotFlow { empty }.first { it }
        val step = welcomeStep
        composerIn.snapTo(0f)
        composerIn.animateTo(1f, PiMotion.enter(PiMotion.SHEET, PiMotion.stagger(step)))
    }

    val extras by remember {
        derivedStateOf {
            val s = state.value
            buildList {
                // Retries are told by the error notice (with Stop retrying) and the status line, not here.
                addAll(s.statuses.filterKeys { it != opensec.pibolt.app.engine.BackgroundJobs.STATUS_KEY }.values.map { it to false })
                s.widgets.values.forEach { lines -> addAll(lines.map { it to false }) }
            }
        }
    }

    /**
     * The composer's send: an app command runs; otherwise the text and images go out as [behavior] (Steer or
     * Follow-up while Pi works). A prompt that starts a run hides the keyboard so the reply has room; a steer keeps it.
     */
    fun submit(behavior: StreamingBehavior) {
        // The mic still listening would put the sent text back with its last words.
        if (voice.listening) voice.cancel()
        val text = draft.trim()
        val call = if (images.isEmpty()) CommandPalette.parse(text, commands, isLocal, chat) else null
        when {
            call != null -> {
                draft = ""
                runBuiltin(call)
            }
            text.isEmpty() && images.isEmpty() -> Unit
            images.any { !it.ready } -> Unit
            images.isNotEmpty() && modelReadsImages == false -> scope.launch { snackbar.show(noImages()) }
            // At a spending limit the draft stays, for Continue once on the limit's card.
            !controller.spendAllows() -> Unit
            else -> {
                val startsRun = !state.value.isRunning
                send(text, images.toList(), behavior, fromDraft = true)
                draft = ""
                images.clear()
                if (startsRun) {
                    keyboard?.hide()
                    focusManager.clearFocus()
                }
            }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .sharedHostBounds(SharedKeys.card(controller.hostId), card = false)
            .background(colors.canvas),
    ) {
        val windowHeight = maxHeight
        val density = LocalDensity.current
        val imeVisible = WindowInsets.isImeVisible
        // Room above the keyboard: under 360dp (landscape with the keyboard up) the composer is one row. The command /
        // `@` panel shares that room with the status and top bars and the composer: capped so it never pushes the
        // composer under the keyboard (in landscape a 264dp panel did). Read through a derived state: the keyboard's
        // height changes every frame of its animation, and reading it here recomposed the whole screen on each.
        val ime = WindowInsets.ime
        val statusBars = WindowInsets.statusBars
        val room by remember(windowHeight, density, ime, statusBars) {
            derivedStateOf {
                with(density) { ImeRoom.of(windowHeight, ime.getBottom(density).toDp(), statusBars.getTop(density).toDp()) }
            }
        }
        // The bottom stack's budget: with the keyboard up, or in a short window, the todo card, status extras, a waiting
        // question and the queue fold into one row, so the transcript keeps its room and the composer stays on the
        // keyboard. The swap is instant: it follows the keyboard frame by frame.
        val foldStack = imeVisible || windowHeight < 480.dp
        val oneRowComposer = room.oneRowComposer
        val composerLines = room.composerLines
        val suggestionsMax = room.suggestionsMax
        Column(Modifier.fillMaxSize().imePadding()) {
            if (searching) {
                SearchBar(
                    query = query,
                    onQuery = { query = it },
                    hits = hits.size,
                    current = hitIndex.coerceAtLeast(0),
                    snippet = hits.getOrNull(hitIndex)?.snippet,
                    onPrevious = { step(-1) },
                    onNext = { step(1) },
                    onClose = {
                        searching = false
                        query = ""
                    },
                )
                BackHandler {
                    searching = false
                    query = ""
                }
            } else SessionTopBar(
                hostId = controller.hostId,
                hostName = title,
                local = local != null,
                subtitle = subtitle,
                link = linkState.value,
                mode = quadMode,
                busy = busy,
                spin = busy,
                onBack = onBack,
                onInfo = ::openStats,
                onDisconnect = onDisconnect,
                onForget = { sheet = Sheet.Forget },
                onNewSession = { local?.onNewSession?.invoke() },
                onRename = { sheet = Sheet.Rename },
                onSearch = { searching = true },
                onExport = { sheet = Sheet.Export },
                onResume = ::openResume,
                onFork = ::openFork,
                onClone = controller::cloneSession,
                onProjects = { remoteSessions = true }.takeIf { local == null && service },
                onChanges = remoteReview?.let { r -> { r.view = RemoteView.Changes } },
                onFiles = remoteReview?.let { r -> { r.view = RemoteView.Files } },
                contextPercent = contextPct,
                // One spinner at a time: the top bar's Quad steps aside while the live status line is on screen.
                quadVisible = !(status.value.visible && statusOnScreen),
                status = status.value.text.let { t -> listOf(t.label.ifBlank { status.value.mode.label }, t.detail).filter { it.isNotBlank() }.joinToString(" ") },
                switcher = { SessionSwitcher(controller, projectMeta, onOpen = onOpenSession) },
                chat = chat,
                onChats = local?.onChats,
                onDelete = local?.onDelete?.let { { sheet = Sheet.DeleteChat } },
                cost = Money.label(sessionCost),
                costSpoken = Money.spoken(sessionCost),
                onCost = { sheet = Sheet.Spend },
            )
            // Before the first snapshot the loading state already says "Starting…" with its own spinner: the banner
            // would say it twice. It still shows a stopped engine or a lost link (with Restart / Retry).
            if (hello || linkState.value !is LinkState.Connecting) {
                ConnectionBanner(linkState.value, controller.pairing, local = local != null, onRetry = local?.onRestart ?: controller::retryNow)
            }
            Box(Modifier.weight(1f)) {
                if (!hello) {
                    LoadingState(title, linkState.value, local = local != null, onRetry = local?.onRestart ?: controller::retryNow, chat = chat)
                } else {
                    CompositionLocalProvider(LocalTurnReview provides review, LocalPlainChat provides chat) {
                        Transcript(
                            state = state,
                            memory = memory,
                            status = status,
                            onOpen = { viewer = it },
                            onActions = {
                                if (it !is ActionsRow) haptics.longPress()
                                actionsFor = it
                            },
                            onAskAgain = { prompt ->
                                haptics.send()
                                // A chat regenerates the last answer in place; elsewhere the prompt goes again as a new turn.
                                val users = state.value.items.filterIsInstance<UserItem>()
                                val last = users.lastOrNull()?.takeIf { chat && !running && it.text == prompt }
                                if (last != null) regenerate(last, users.dropLast(1).count { it.text == prompt }) else send(prompt)
                            },
                            onStatusOnScreen = { statusOnScreen = it },
                            jump = jump,
                            onImage = { key, index ->
                                val thumbs = memory.localImages[key].orEmpty()
                                if (thumbs.isNotEmpty()) imageViewer = ViewerImages(memory.localFull[key].orEmpty(), thumbs, index)
                            },
                            onToolImages = { imageViewer = it },
                            errorContext = ErrorContext(modelLabel, modelProvider?.let(ProviderCatalog::name), local = isLocal, running = running),
                            onErrorAction = ::onErrorAction,
                            onOpenLocalFile = { openedFile = it },
                        )
                    }
                    if (empty) {
                        EmptySession(
                            title = welcome?.title ?: if (isLocal) title else cwd?.takeIf { it.isNotBlank() }?.let(::baseName) ?: title,
                            subtitle = if (isLocal) branch else hostName,
                            question = welcome?.question,
                            starters = welcomeStarters,
                            recent = welcomeRecent,
                            onStarter = { fill(it.prompt) },
                            onRecent = { fill(it) },
                            onCommands = welcomeCommands,
                            play = !reduceMotion,
                        )
                    }
                }
                PiSnackHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
            // Past 840dp the bottom stack keeps the transcript's 720dp reading width, centred.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                    val hiddenId = memory.hiddenDialog.value
                    val askingTitle = firstDialog?.takeIf { it.id == hiddenId }?.title?.ifBlank { "Answer needed" }
                    if (foldStack) {
                        val tasks = todos.visible
                        val now = tasks.firstOrNull { it.status == TodoStatus.InProgress } ?: tasks.firstOrNull { it.status == TodoStatus.Pending }
                        CompactStack(
                            todoDone = tasks.count { it.status == TodoStatus.Completed },
                            todoTotal = tasks.size,
                            todoNow = now?.let { todoText(it, running) },
                            queued = queue.size,
                            asking = askingTitle,
                            extras = extras.size,
                            onExpand = {
                                if (askingTitle != null && !imeVisible) {
                                    memory.hiddenDialog.value = null
                                } else {
                                    keyboard?.hide()
                                    focusManager.clearFocus()
                                }
                            },
                        )
                    } else {
                        StatusExtras(extras)
                        TodoPanel(todos, running, autoCollapse = !running || windowHeight < 560.dp, quiet = status.value.visible && statusOnScreen)
                        if (local != null && parts.preview) PreviewChipRow(local.onPreview, Modifier.padding(horizontal = Space.page))
                        if (local != null && parts.preview) BackgroundJobsPill(controller.localBackend?.pid, Modifier.padding(horizontal = Space.page))
                        PendingDialogStrip(askingTitle) { memory.hiddenDialog.value = null }
                        QueueStrip(queue, onEdit = controller::clearQueue)
                    }
                    capCard?.let { card ->
                        CapNotice(
                            card,
                            onContinue = {
                                controller.continueOnce()
                                // The held-back draft goes now; a stopped run picks up where it stopped.
                                if (draft.isNotBlank() || images.isNotEmpty()) submit(mode) else if (card.stopped) send(CONTINUE_PROMPT)
                            },
                            onDismiss = controller::dismissCapCard,
                            modifier = Modifier.padding(horizontal = Space.page, vertical = Space.xs),
                        )
                    }
                    Composer(
                        text = draft,
                        onText = {
                            // Typing while the mic listens: its next result would rebuild the draft over the typed words.
                            if (voice.listening) voice.cancel()
                            draft = it
                            if (it.isBlank()) dictated = false
                        },
                        enabled = enabled,
                        running = running,
                        mode = mode,
                        onMode = { mode = it },
                        modelLabel = modelLabel,
                        modelBrand = modelBrand,
                        thinkingLevel = thinkingLevel.takeIf { reasons },
                        palette = palette,
                        onPick = { entry ->
                            val builtin = entry.builtin
                            if (builtin != null && entry.hint == null) {
                                draft = ""
                                runBuiltin(BuiltinCall(builtin, ""))
                            } else {
                                fill(entry.insert)
                            }
                        },
                        files = files.takeIf { parts.files },
                        shell = parts.shell,
                        onCycleModel = ::cycleModel,
                        onCycleThinking = ::cycleThinking,
                        onOpenImage = { index ->
                            val ready = images.filter { it.ready }
                            val thumbs = ready.mapNotNull { it.thumb }
                            if (thumbs.isNotEmpty()) imageViewer = ViewerImages(ready.map { it.image }, thumbs, index.coerceIn(0, thumbs.lastIndex))
                        },
                        onSend = { submit(mode) },
                        images = images,
                        attachAllowed = modelReadsImages != false,
                        onAttach = { sheet = Sheet.Attach },
                        onRemoveImage = { id -> images.removeAll { it.id == id } },
                        voice = voice,
                        voiceMode = voiceMode,
                        changes = if (isLocal) ({ SessionChangesPill(review) }) else if (remoteReview != null) ({ RemoteChangesPill(remoteReview) }) else null,
                        focusRequester = focus,
                        // A user `!` command started while Pi is idle stops through abort_bash; the agent and a
                        // compaction (manual ones run with Pi idle) through abort.
                        stoppable = composerStops(running, compacting, shellRuns),
                        onStop = { if (shellRuns > 0) controller.abortBash() else controller.abort() },
                        onModel = { openModels() },
                        compact = oneRowComposer,
                        maxLines = composerLines,
                        suggestionsMaxHeight = suggestionsMax,
                        history = recentAll[recentKey].orEmpty(),
                        onKey = { key ->
                            when (key) {
                                ComposerKeys.Action.FollowUp -> submit(StreamingBehavior.FollowUp)
                                ComposerKeys.Action.OpenModels -> openModels()
                                ComposerKeys.Action.Search -> searching = true
                                ComposerKeys.Action.RestoreQueue -> if (!queue.isEmpty) controller.clearQueue()
                                else -> Unit
                            }
                        },
                        onPasteImage = ::pasteImage,
                        modifier = Modifier.graphicsLayer {
                            val p = composerIn.value
                            alpha = p
                            translationY = (1f - p) * 8.dp.toPx()
                        },
                    )
                }
            }
            Spacer(Modifier.navigationBarsPadding().padding(bottom = if (imeVisible) 4.dp else 8.dp).height(0.dp))
        }

        AnimatedVisibility(
            visible = viewer != null,
            enter = fadeIn(PiMotion.enter(PiMotion.SHEET)) + slideInVertically(PiMotion.enter(PiMotion.SHEET)) { it / 10 },
            exit = fadeOut(PiMotion.leave(PiMotion.SHEET)) + scaleOut(PiMotion.leave(PiMotion.SHEET), targetScale = 0.94f),
        ) {
            val content = remember { mutableStateOf(viewer) }
            viewer?.let { content.value = it }
            content.value?.let { Viewer(it, onClose = { viewer = null }) }
        }
        TurnReviewOverlay(review)
        remoteReview?.let { r ->
            RemoteReviewOverlays(app, r, cwd.orEmpty(), running, onAsk = { draft = AskPi.appendTo(draft, it).first })
        }
        AnimatedVisibility(
            visible = openedFile != null,
            enter = fadeIn(PiMotion.enter(PiMotion.SHEET)) + slideInVertically(PiMotion.enter(PiMotion.SHEET)) { it / 10 },
            exit = fadeOut(PiMotion.leave(PiMotion.SHEET)) + scaleOut(PiMotion.leave(PiMotion.SHEET), targetScale = 0.94f),
        ) {
            val shown = remember { mutableStateOf(openedFile) }
            openedFile?.let { shown.value = it }
            shown.value?.let { f ->
                val (root, rel) = remember(f) { viewerPlace(f, state.value.host?.cwd) }
                FileViewer(
                    root,
                    fileTabs,
                    rel,
                    onSelect = { openedFile = File(root, it) },
                    onClose = { openedFile = null },
                    onChanged = {},
                    onAsk = {
                        draft = AskPi.appendTo(draft, it).first
                        openedFile = null
                    },
                )
            }
        }
        AnimatedVisibility(visible = imageViewer != null, enter = fadeIn(PiMotion.enter(PiMotion.SHEET)), exit = fadeOut(PiMotion.leave(PiMotion.SHEET))) {
            val shown = remember { mutableStateOf(imageViewer) }
            imageViewer?.let { shown.value = it }
            shown.value?.let { ImageViewer(it, onClose = { imageViewer = null }, onMessage = { m -> scope.launch { snackbar.show(m) } }) }
        }
    }

    // Pi's dialogs: one at a time. Swiping away hides the sheet; only an explicit answer or Cancel responds.
    // ask_user_question's dialogs are one question card instead (ui/session/ask): its answers go out on Submit.
    val askOwner = firstDialog?.let { dialog -> remember(dialog.id) { AskQuestions.owner(state.value, dialog) } }
    if (askOwner != null && firstDialog != null) {
        val (call, questions) = askOwner
        if (!controller.askSubmitting(call.toolCallId) && !voiceMode.claims(call.toolCallId)) {
            key(call.toolCallId) {
                QuestionSheet(
                    toolCallId = call.toolCallId,
                    questions = questions,
                    first = AskQuestions.stepOf(firstDialog!!, questions)?.question ?: 0,
                    onSubmit = { controller.submitAsk(it) },
                )
            }
        }
    } else firstDialog?.let { dialog ->
        val shownAt = remember(dialog.id) { memory.dialogShownAt.getOrPut(dialog.id) { SystemClock.uptimeMillis() } }
        if (memory.hiddenDialog.value != dialog.id) {
            key(dialog.id) {
                DialogSheet(
                    dialog = dialog,
                    shownAt = shownAt,
                    onValue = { controller.answer(dialog.id, it) },
                    onConfirm = { controller.confirm(dialog.id, it) },
                    onCancel = { controller.cancelDialog(dialog.id) },
                    onHide = { memory.hiddenDialog.value = dialog.id },
                    cwd = cwd,
                )
            }
        }
    }

    VoiceRationale(voice)
    VoiceModeEffects(voiceMode, state, status, controller.effects)

    if (remoteSessions) RemoteSessionsSheet(controller, onDismiss = { remoteSessions = false })
    when (sheet) {
        Sheet.None -> Unit
        Sheet.Attach -> {
            // The clip's description only: reading the clip itself shows Android's "pasted from your clipboard" toast.
            val clip = remember { clipboardHasImage(context) }
            ActionsSheet(
                title = null,
                actions = listOfNotNull(
                    SheetAction(if (modelReadsImages == false) "Photos (not available for this model)" else "Photos", Icons.Rounded.Image) { attach() },
                    SheetAction("Paste image", Icons.Rounded.ContentPaste) { pasteImage() }.takeIf { clip },
                    SheetAction("Mention a file", Icons.Rounded.AlternateEmail) { fill(if (draft.isEmpty() || draft.endsWith(" ")) draft + "@" else "$draft @") }.takeIf { parts.files },
                    SheetAction("Commands", Icons.Rounded.Code) { fill("/") }.takeIf { palette.isNotEmpty() && draft.isEmpty() },
                    SheetAction("Prompt history", Icons.Rounded.History) { sheet = Sheet.History },
                ),
                onDismiss = { if (sheet == Sheet.Attach) sheet = Sheet.None },
            )
        }
        Sheet.History -> HistorySheet(recentAll[recentKey].orEmpty(), onPick = {
            sheet = Sheet.None
            fill(it)
        }, onDismiss = { sheet = Sheet.None })
        Sheet.Key -> {
            val configured by app.engine.auth.configured.collectAsStateWithLifecycle()
            ProviderSheet(
                auth = app.engine.auth,
                configured = configured,
                firstRun = false,
                initial = modelProvider?.let(ProviderCatalog::get),
                onSaved = { p ->
                    sheet = Sheet.None
                    scope.launch { snackbar.show("${p.name} key updated. Retry to use it.", SnackKind.Success) }
                },
                onDismiss = { sheet = Sheet.None },
            )
        }
        Sheet.Model -> {
            val models by controller.models.collectAsStateWithLifecycle()
            val current by remember { derivedStateOf { state.value.model } }
            val shown = if (isLocal) rememberScopedModels(f2, app.engine.agentDir, models, current) else models
            val levels by controller.thinkingLevels.collectAsStateWithLifecycle()
            ModelSheet(
                shown,
                current,
                onPick = {
                    controller.setModel(it)
                    local?.onModelPicked?.invoke(it)
                    sheet = Sheet.None
                },
                onDismiss = { sheet = Sheet.None },
                initialQuery = modelQuery,
                thinking = ThinkingPick(levels, thinkingLevel, onPick = {
                    controller.setThinkingLevel(it)
                    local?.onThinkingPicked?.invoke(it)
                }).takeIf { reasons },
            )
        }
        Sheet.Thinking -> {
            val levels by controller.thinkingLevels.collectAsStateWithLifecycle()
            ThinkingSheet(levels, thinkingLevel, onPick = {
                controller.setThinkingLevel(it)
                local?.onThinkingPicked?.invoke(it)
                sheet = Sheet.None
            }, onDismiss = { sheet = Sheet.None })
        }
        Sheet.Info -> {
            val facts by remember {
                derivedStateOf {
                    val s = state.value
                    SessionFacts(
                        model = s.model,
                        thinkingLevel = s.thinkingLevel,
                        sessionName = s.sessionName,
                        cwd = s.host?.cwd?.takeIf { parts.folder },
                        version = s.host?.version,
                        compacting = s.isCompacting,
                        connected = linkState.value is LinkState.Connected,
                        lastTotalTokens = s.lastUsage?.totalTokens,
                    )
                }
            }
            SessionStatsSheet(
                facts,
                stats,
                statsLoading,
                onModel = { openModels() },
                onThinking = ::openThinking,
                onCycleModel = ::cycleModel,
                onCycleThinking = ::cycleThinking,
                onCompact = { sheet = Sheet.Compact },
                onRename = { sheet = Sheet.Rename },
                onExport = { sheet = Sheet.Export },
                onDismiss = { sheet = Sheet.None },
            )
        }
        Sheet.Spend -> SpendSheet(
            app,
            session = sessionCost,
            // A project's sessions on this phone, added up (a chat is its own row in the Chats list).
            project = produceState<Cost?>(null) {
                value = controller.project?.takeIf { isLocal && !chat }?.let { dir -> withContext(Dispatchers.IO) { runCatching { app.engine.sessions(dir).fold(Cost.NONE) { sum, s -> sum + s.cost } }.getOrNull() } }
            }.value,
            onLimits = { sheet = Sheet.SpendLimits },
            onDismiss = { if (sheet == Sheet.Spend) sheet = Sheet.None },
            note = "Subagents on the computer aren't counted.".takeIf { !isLocal },
        )
        Sheet.SpendLimits -> SpendLimitsSheet(app, onDismiss = { if (sheet == Sheet.SpendLimits) sheet = Sheet.None })
        Sheet.Export -> ActionsSheet(
            title = "Export or share this $noun",
            actions = listOfNotNull(
                SheetAction("Share as Markdown", Icons.Rounded.Share) { exportMarkdown(share = true) },
                SheetAction("Copy as Markdown", Icons.Rounded.ContentCopy) { exportMarkdown(share = false) },
                controller.sessionFile?.let { SheetAction("Share session file (JSONL)", Icons.AutoMirrored.Rounded.InsertDriveFile) { exportJsonl() } },
                SheetAction("Share as HTML", Icons.Rounded.Html) { exportHtml() }.takeIf { isLocal },
            ),
            onDismiss = { sheet = Sheet.None },
        )
        Sheet.Resume -> ResumeSheet(savedSessions, controller.sessionFile, onPick = {
            local?.onOpenFile?.invoke(it.file) ?: controller.switchSession(it.file)
            sheet = Sheet.None
        }, onDismiss = { sheet = Sheet.None })
        Sheet.Fork -> ForkSheet(forkPoints, onPick = { point ->
            sheet = Sheet.None
            val from = draftOwner
            scope.launch {
                controller.fork(point.entryId)
                    .onSuccess {
                        forkedDraft(from, it.ifEmpty { point.text })
                        haptics.confirm()
                        snackbar.show("Forked into a new $noun. Edit the message and send.", SnackKind.Success)
                    }
                    .onFailure { snackbar.show(it.message ?: "Fork failed", SnackKind.Error) }
            }
        }, onDismiss = { sheet = Sheet.None })
        Sheet.Rename -> TextPromptSheet(
            title = if (chat) "Rename chat" else "Rename session",
            message = null,
            initial = remember { state.value.sessionName.orEmpty() },
            placeholder = if (chat) "Chat name" else "Session name",
            confirm = "Rename",
            onConfirm = {
                controller.rename(it.trim())
                sheet = Sheet.None
            },
            onDismiss = { sheet = Sheet.None },
        )
        Sheet.Compact -> TextPromptSheet(
            title = "Compact context",
            message = "Summarises the conversation to free context.",
            initial = "",
            placeholder = "Optional: what to focus the summary on",
            confirm = "Compact",
            onConfirm = {
                controller.compact(it.trim().ifBlank { null })
                sheet = Sheet.None
            },
            onDismiss = { sheet = Sheet.None },
        )
        Sheet.DeleteChat -> ConfirmSheet(
            title = "Delete this chat?",
            message = "This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                sheet = Sheet.None
                local?.onDelete?.invoke()
            },
            onDismiss = { sheet = Sheet.None },
        )
        Sheet.Forget -> ConfirmSheet(
            title = "Forget $hostName?",
            message = "To revoke this phone on the computer too, run /\u2060remote-control devices there.",
            confirmLabel = "Forget",
            onConfirm = {
                sheet = Sheet.None
                onForget()
            },
            onDismiss = { sheet = Sheet.None },
        )
    }

    Features2Sheets(f2, f2Host, state)

    actionsFor?.let { row ->
        val actions = when (row) {
            is UserRow -> {
                val users = state.value.items.filterIsInstance<UserItem>()
                val at = users.indexOfFirst { it.key == row.item.key }
                val occurrence = if (at >= 0) users.take(at).count { it.text == row.item.text } else -1
                listOfNotNull(
                    SheetAction("Copy", Icons.Rounded.ContentCopy) { copy(row.item.text) },
                    SheetAction("Send again", Icons.Rounded.Replay) {
                        haptics.send()
                        send(row.item.text)
                    }.takeIf { row.item.text.isNotBlank() && row.item.imageCount == 0 },
                    // On-device sessions only: Pi forks from a user message on the active branch. The fork hands back
                    // the text only, so a message with images is not offered (its images would be lost).
                    SheetAction("Edit and resend", Icons.Rounded.Edit) { fork(row.item.text, occurrence) }.takeIf { isLocal && occurrence >= 0 && row.item.imageCount == 0 },
                    SheetAction("Edit in composer", Icons.Rounded.EditNote) { fill(row.item.text) },
                    SheetAction("Select text", Icons.Rounded.TextFields) { viewer = ViewerContent("Message", null, row.item.text, selectable = true) },
                    SheetAction("Share", Icons.Rounded.Share) { share(row.item.text) },
                )
            }
            is TextRow -> {
                val text = { (state.value.allItems.firstOrNull { it.key == row.itemKey } as? AssistantTextItem)?.text.orEmpty() }
                val prompt = MessageText.promptOf(state.value.items, row.itemKey)
                listOfNotNull(
                    SheetAction("Copy", Icons.Rounded.ContentCopy) { copy(MessageText.plain(text())) },
                    SheetAction("Copy as Markdown", Icons.Rounded.Code) { copy(text()) },
                    SheetAction("Regenerate", Icons.Rounded.Replay) { prompt?.let { (u, n) -> regenerate(u, n) } }.takeIf { isLocal && prompt != null && prompt.first.imageCount == 0 && !running },
                    SheetAction("Select text", Icons.Rounded.TextFields) { viewer = ViewerContent("Reply", null, text(), selectable = true) },
                    SheetAction("View as Markdown", Icons.AutoMirrored.Rounded.Notes) { viewer = ViewerContent("Markdown", null, text()) },
                    SheetAction("Share", Icons.Rounded.Share) { share(text()) },
                )
            }
            is ActionsRow -> {
                val prompt = state.value.items.firstOrNull { it.key == row.turnKey } as? UserItem
                val occurrence = prompt?.let { p -> state.value.items.filterIsInstance<UserItem>().takeWhile { it.key != p.key }.count { it.text == p.text } }
                listOfNotNull(
                    SheetAction("Copy as plain text", Icons.Rounded.ContentCopy) { copy(MessageText.plain(row.reply)) },
                    SheetAction("Copy as Markdown", Icons.Rounded.Code) { copy(row.reply) },
                    SheetAction("Regenerate", Icons.Rounded.Replay) { if (prompt != null && occurrence != null) regenerate(prompt, occurrence) }.takeIf { isLocal && prompt != null && prompt.imageCount == 0 && occurrence != null && !running },
                    SheetAction("Select text", Icons.Rounded.TextFields) { viewer = ViewerContent("Reply", null, row.reply, selectable = true) },
                    SheetAction("View as Markdown", Icons.AutoMirrored.Rounded.Notes) { viewer = ViewerContent("Markdown", null, row.reply) },
                    SheetAction("Share", Icons.Rounded.Share) { share(row.reply) },
                )
            }
            is ToolRow -> {
                val item = row.item
                val label = toolLabel(item)
                listOfNotNull(
                    label.detail.takeIf { it.isNotBlank() }?.let { SheetAction(if (label.kind == ToolKind.Bash) "Copy command" else "Copy argument", Icons.Rounded.ContentCopy) { copy(it) } },
                    (item.result ?: item.output).takeIf { it.isNotBlank() }?.let { SheetAction("Copy output", Icons.Rounded.ContentCopy) { copy(it) } },
                    SheetAction("Open output", Icons.Rounded.OpenInFull) {
                        viewer = ViewerContent(label.verb, label.detail, toolBody(item, full = true, previewLines = settings.toolPreviewLines)?.text.orEmpty(), diff = toolBody(item, true, 0)?.isDiff == true)
                    },
                )
            }
            else -> emptyList()
        }
        if (actions.isEmpty()) {
            actionsFor = null
        } else {
            ActionsSheet(title = null, actions = actions, onDismiss = { actionsFor = null })
        }
    }
}

/** How long Regenerate waits for the fork's session before it sends anyway. */
private const val FORK_SWITCH_MS = 5_000L

/** What "Continue once" sends after a spending limit stopped a run. */
private const val CONTINUE_PROMPT = "Continue."

/**
 * The composer offers Stop: while Pi runs, while a compaction runs (a manual one has Pi idle, and could not be cancelled
 * as Esc does in the terminal), or while a user `!` command runs.
 */
fun composerStops(running: Boolean, compacting: Boolean, shellRuns: Int): Boolean = running || compacting || shellRuns > 0

/** The status line while compacting: why it runs, as the terminal says it. */
fun compactionLabel(reason: String?): String = when (reason) {
    "threshold" -> "Auto-compacting"
    "overflow" -> "Context full, compacting"
    else -> "Compacting"
}
