package opensec.pibolt.app.ui.session

import android.Manifest
import android.content.ClipData
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.session.LinkState
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.app.session.SessionEffect
import opensec.pibolt.app.ui.components.ConfirmSheet
import opensec.pibolt.app.ui.components.PiSnackHost
import opensec.pibolt.app.ui.components.QuadMath
import opensec.pibolt.app.ui.components.SharedKeys
import opensec.pibolt.app.ui.components.SnackKind
import opensec.pibolt.app.ui.components.sharedHostBounds
import opensec.pibolt.app.ui.components.show
import opensec.pibolt.app.ui.components.snackKindOf
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.protocol.rpc.StreamingBehavior
import opensec.pibolt.protocol.session.AssistantTextItem
import opensec.pibolt.protocol.session.SessionState

@Composable
fun SessionScreen(app: PiBoltApp, onBack: () -> Unit, onForgotten: () -> Unit) {
    val controller by app.session.collectAsStateWithLifecycle()
    val c = controller
    if (c == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
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
    )
}

private enum class Sheet { None, Model, Thinking, Info, Rename, Compact, Forget }

/**
 * The session screen. It never reads the whole [SessionState] itself: each part derives the few values it shows, so a
 * streamed frame recomposes only the transcript's live rows and whatever value actually changed.
 */
@Composable
private fun SessionContent(app: PiBoltApp, controller: SessionController, onBack: () -> Unit, onDisconnect: () -> Unit, onForget: () -> Unit) {
    val state: State<SessionState> = controller.state.collectAsStateWithLifecycle()
    val linkState = controller.link.collectAsStateWithLifecycle()
    val hostName by controller.hostName.collectAsStateWithLifecycle()
    val sessionId by remember { derivedStateOf { state.value.sessionId } }
    val memory = remember(controller.hostId, sessionId) { SessionMemory.of(controller.hostId, sessionId) }
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
    var viewer by remember { mutableStateOf<ViewerContent?>(null) }
    var actionsFor by remember { mutableStateOf<TRow?>(null) }
    var statusOnScreen by remember { mutableStateOf(false) }

    LaunchedEffect(memory) { controller.state.collect { memory.observe(it) } }
    LaunchedEffect(controller) {
        controller.effects.collect { effect ->
            when (effect) {
                is SessionEffect.Notify -> scope.launch { snackbar.show(effect.message, snackKindOf(effect.kind)) }
                is SessionEffect.Error -> {
                    haptics.reject()
                    scope.launch { snackbar.show(effect.message, SnackKind.Error) }
                }
                is SessionEffect.EditorText -> draft = effect.text
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

    fun copy(text: String) {
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Pi-Bolt", text))) }
        haptics.confirm()
        if (Build.VERSION.SDK_INT < 33) scope.launch { snackbar.show("Copied") }
    }

    val hello by remember { derivedStateOf { state.value.hello } }
    val running by remember { derivedStateOf { state.value.isRunning } }
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
    val status = remember {
        derivedStateOf {
            val s = state.value
            val m = quadMode
            LiveStatus(visible = s.isRunning || s.isCompacting, mode = m, label = m.label)
        }
    }
    val subtitle by remember {
        derivedStateOf {
            val s = state.value
            val parts = buildList {
                // Folder and context only: the model and thinking level live in the composer chips.
                (s.sessionName ?: s.host?.cwd?.takeIf { it.isNotBlank() }?.let(::baseName))?.let(::add)
                s.lastUsage?.let { u -> contextPercent(u.totalTokens, s.model?.contextWindow)?.let { add("$it% context") } }
            }
            parts.joinToString(" · ")
        }
    }
    val modelLabel by remember { derivedStateOf { state.value.model?.label } }
    val cwd by remember { derivedStateOf { state.value.host?.cwd } }
    val thinkingLevel by remember { derivedStateOf { state.value.thinkingLevel } }
    val commands by remember { derivedStateOf { state.value.commands } }
    val queue by remember { derivedStateOf { state.value.queue } }
    val firstDialog by remember { derivedStateOf { state.value.dialogs.firstOrNull() } }
    val empty by remember { derivedStateOf { state.value.hello && state.value.items.isEmpty() && state.value.streaming.isEmpty() && !state.value.isRunning } }
    val extras by remember {
        derivedStateOf {
            val s = state.value
            buildList {
                s.retry?.let { add("Retrying ${it.attempt}/${it.maxAttempts}" + (it.error?.let { e -> ": $e" } ?: "") to true) }
                addAll(s.statuses.values.map { it to false })
                s.widgets.values.forEach { lines -> addAll(lines.map { it to false }) }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .sharedHostBounds(SharedKeys.card(controller.hostId), card = false)
            .background(colors.canvas),
    ) {
        Column(Modifier.fillMaxSize().imePadding()) {
            SessionTopBar(
                hostId = controller.hostId,
                hostName = hostName,
                subtitle = subtitle,
                link = linkState.value,
                mode = quadMode,
                busy = busy,
                // One spinner at a time: the top bar animates only while the live status line is off screen.
                spin = busy && !(status.value.visible && statusOnScreen),
                onBack = onBack,
                onInfo = { sheet = Sheet.Info },
                onDisconnect = onDisconnect,
                onForget = { sheet = Sheet.Forget },
            )
            ConnectionBanner(linkState.value, controller.pairing, onRetry = controller::retryNow)
            Box(Modifier.weight(1f)) {
                if (!hello) {
                    LoadingState(hostName, linkState.value, onRetry = controller::retryNow)
                } else {
                    Transcript(
                        state = state,
                        memory = memory,
                        status = status,
                        onOpen = { viewer = it },
                        onActions = {
                            haptics.longPress()
                            actionsFor = it
                        },
                        onCopy = ::copy,
                        onStatusOnScreen = { statusOnScreen = it },
                    )
                    if (empty) EmptySession(cwd, commands.size)
                }
                PiSnackHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
            StatusExtras(extras)
            val hiddenId = memory.hiddenDialog.value
            PendingDialogStrip(firstDialog?.takeIf { it.id == hiddenId }?.title?.ifBlank { "Answer needed" }) { memory.hiddenDialog.value = null }
            QueueStrip(queue, onEdit = controller::clearQueue)
            Composer(
                text = draft,
                onText = { draft = it },
                enabled = enabled,
                running = running,
                mode = mode,
                onMode = { mode = it },
                modelLabel = modelLabel,
                thinkingLevel = thinkingLevel,
                commands = commands,
                onSend = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        controller.send(text, mode)
                        draft = ""
                        maybeAskNotifications()
                    }
                },
                onStop = controller::abort,
                onModel = {
                    controller.loadModels()
                    sheet = Sheet.Model
                },
                onThinking = {
                    controller.loadThinkingLevels()
                    sheet = Sheet.Thinking
                },
            )
            Spacer(Modifier.navigationBarsPadding().padding(bottom = 8.dp).height(0.dp))
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
    }

    // Pi's dialogs: one at a time. Swiping away hides the sheet; only an explicit answer or Cancel responds.
    firstDialog?.let { dialog ->
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
                )
            }
        }
    }

    when (sheet) {
        Sheet.None -> Unit
        Sheet.Model -> {
            val models by controller.models.collectAsStateWithLifecycle()
            val current by remember { derivedStateOf { state.value.model } }
            ModelSheet(models, current, onPick = {
                controller.setModel(it)
                sheet = Sheet.None
            }, onDismiss = { sheet = Sheet.None })
        }
        Sheet.Thinking -> {
            val levels by controller.thinkingLevels.collectAsStateWithLifecycle()
            ThinkingSheet(levels, thinkingLevel, onPick = {
                controller.setThinkingLevel(it)
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
                        totalTokens = s.lastUsage?.totalTokens,
                        input = s.lastUsage?.input,
                        output = s.lastUsage?.output,
                        cacheRead = s.lastUsage?.cacheRead,
                        cost = s.lastUsage?.cost,
                        cwd = s.host?.cwd,
                        version = s.host?.version,
                        sessionName = s.sessionName,
                        compacting = s.isCompacting,
                        connected = linkState.value is LinkState.Connected,
                    )
                }
            }
            SessionInfoSheet(
                facts,
                onModel = {
                    controller.loadModels()
                    sheet = Sheet.Model
                },
                onThinking = {
                    controller.loadThinkingLevels()
                    sheet = Sheet.Thinking
                },
                onCompact = { sheet = Sheet.Compact },
                onRename = { sheet = Sheet.Rename },
                onDismiss = { sheet = Sheet.None },
            )
        }
        Sheet.Rename -> TextPromptSheet(
            title = "Rename session",
            message = null,
            initial = remember { state.value.sessionName.orEmpty() },
            placeholder = "Session name",
            confirm = "Rename",
            onConfirm = {
                controller.rename(it.trim())
                sheet = Sheet.None
            },
            onDismiss = { sheet = Sheet.None },
        )
        Sheet.Compact -> TextPromptSheet(
            title = "Compact context",
            message = "Pi summarises the conversation so far to free context.",
            initial = "",
            placeholder = "Optional: what to focus the summary on",
            confirm = "Compact",
            onConfirm = {
                controller.compact(it.trim().ifBlank { null })
                sheet = Sheet.None
            },
            onDismiss = { sheet = Sheet.None },
        )
        Sheet.Forget -> ConfirmSheet(
            title = "Forget $hostName?",
            message = "This phone stops connecting to it. To also revoke this phone on the computer, run /\u2060remote-control devices there.",
            confirmLabel = "Forget",
            onConfirm = {
                sheet = Sheet.None
                onForget()
            },
            onDismiss = { sheet = Sheet.None },
        )
    }

    actionsFor?.let { row ->
        val actions = when (row) {
            is UserRow -> listOf(
                SheetAction("Copy", Icons.Rounded.ContentCopy) { copy(row.item.text) },
                SheetAction("Edit in composer", Icons.Rounded.Edit) { draft = row.item.text },
                SheetAction("Select text", Icons.Rounded.TextFields) { viewer = ViewerContent("Message", null, row.item.text, selectable = true) },
            )
            is TextRow -> {
                val text = { (state.value.allItems.firstOrNull { it.key == row.itemKey } as? AssistantTextItem)?.text.orEmpty() }
                listOf(
                    SheetAction("Copy", Icons.Rounded.ContentCopy) { copy(text()) },
                    SheetAction("Select text", Icons.Rounded.TextFields) { viewer = ViewerContent("Reply", null, text(), selectable = true) },
                    SheetAction("View as Markdown", Icons.AutoMirrored.Rounded.Notes) { viewer = ViewerContent("Markdown", null, text()) },
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
