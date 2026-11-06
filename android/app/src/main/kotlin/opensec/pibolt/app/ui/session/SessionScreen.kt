package opensec.pibolt.app.ui.session

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.session.LinkState
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.app.session.SessionEffect
import opensec.pibolt.app.ui.theme.Mono
import opensec.pibolt.app.ui.theme.PiTheme
import opensec.pibolt.protocol.rpc.StreamingBehavior
import opensec.pibolt.protocol.session.SessionReducer
import opensec.pibolt.protocol.session.SessionState
import opensec.pibolt.protocol.session.SlashCommand

@Composable
fun SessionScreen(app: PiBoltApp, onBack: () -> Unit, onForgotten: () -> Unit) {
    val controller by app.session.collectAsStateWithLifecycle()
    val c = controller
    if (c == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    SessionContent(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionContent(controller: SessionController, onBack: () -> Unit, onDisconnect: () -> Unit, onForget: () -> Unit) {
    val state by controller.state.collectAsStateWithLifecycle()
    val link by controller.link.collectAsStateWithLifecycle()
    val hostName by controller.hostName.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable(controller.hostId) { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(StreamingBehavior.Steer) }
    var menu by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var showThinking by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showCompact by remember { mutableStateOf(false) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    LaunchedEffect(controller) {
        controller.effects.collect { effect ->
            when (effect) {
                is SessionEffect.Notify -> scope.launch { snackbar.showSnackbar(effect.message) }
                is SessionEffect.Error -> scope.launch { snackbar.showSnackbar(effect.message) }
                is SessionEffect.EditorText -> draft = effect.text
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Computers") }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinkDot(link, state.isRunning)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(hostName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val subtitle = state.sessionName ?: state.host?.cwd?.let(::shortPath)
                            if (!subtitle.isNullOrBlank()) {
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Compact context") },
                                leadingIcon = { Icon(Icons.Outlined.Compress, null) },
                                enabled = link is LinkState.Connected && !state.isCompacting,
                                onClick = { menu = false; showCompact = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Rename session") },
                                leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                                enabled = link is LinkState.Connected,
                                onClick = { menu = false; showRename = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Disconnect") },
                                leadingIcon = { Icon(Icons.Outlined.LinkOff, null) },
                                onClick = { menu = false; onDisconnect() },
                            )
                            DropdownMenuItem(
                                text = { Text("Forget computer", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; onForget() },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .imePadding(),
        ) {
            LinkBanner(link, controller.pairing, onRetry = controller::retryNow)
            Box(Modifier.weight(1f)) {
                if (!state.hello) {
                    LoadingState(hostName, link)
                } else {
                    MessageList(state)
                }
            }
            StatusStrip(state)
            QueuePanel(state, onClear = controller::clearQueue)
            Composer(
                text = draft,
                onText = { draft = it },
                state = state,
                enabled = link is LinkState.Connected && state.hello,
                mode = mode,
                onMode = { mode = it },
                onSend = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        controller.send(text, mode)
                        draft = ""
                    }
                },
                onAbort = controller::abort,
                onModel = {
                    controller.loadModels()
                    showModels = true
                },
                onThinking = {
                    controller.loadThinkingLevels()
                    showThinking = true
                },
            )
        }
    }

    state.dialogs.firstOrNull()?.let { dialog ->
        androidx.compose.runtime.key(dialog.id) {
            DialogSheet(
                dialog = dialog,
                onValue = { controller.answer(dialog.id, it) },
                onConfirm = { controller.confirm(dialog.id, it) },
                onCancel = { controller.cancelDialog(dialog.id) },
            )
        }
    }

    if (showModels) {
        val models by controller.models.collectAsStateWithLifecycle()
        ModelSheet(models, state.model, onPick = {
            controller.setModel(it)
            showModels = false
        }, onDismiss = { showModels = false })
    }
    if (showThinking) {
        val levels by controller.thinkingLevels.collectAsStateWithLifecycle()
        ThinkingSheet(levels, state.thinkingLevel, onPick = {
            controller.setThinkingLevel(it)
            showThinking = false
        }, onDismiss = { showThinking = false })
    }
    if (showRename) {
        TextPromptDialog(
            title = "Rename session",
            initial = state.sessionName.orEmpty(),
            placeholder = "Session name",
            confirm = "Rename",
            onDismiss = { showRename = false },
            onConfirm = {
                controller.rename(it.trim())
                showRename = false
            },
        )
    }
    if (showCompact) {
        TextPromptDialog(
            title = "Compact context",
            initial = "",
            placeholder = "Optional: what to focus the summary on",
            confirm = "Compact",
            onDismiss = { showCompact = false },
            onConfirm = {
                controller.compact(it.trim().ifBlank { null })
                showCompact = false
            },
        )
    }
}

private fun shortPath(path: String): String {
    val parts = path.trimEnd('/').split('/').filter { it.isNotEmpty() }
    return if (parts.size <= 2) path else "…/" + parts.takeLast(2).joinToString("/")
}

@Composable
private fun LinkDot(link: LinkState, running: Boolean) {
    val color = when {
        link is LinkState.Connected && running -> PiTheme.colors.running
        link is LinkState.Connected -> PiTheme.colors.success
        link is LinkState.Failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    Box(
        Modifier
            .size(10.dp)
            .then(if (link !is LinkState.Connected && link !is LinkState.Failed || running) Modifier.pulse() else Modifier)
            .background(color, CircleShape),
    )
}

@Composable
private fun LinkBanner(link: LinkState, pairing: Boolean, onRetry: () -> Unit) {
    val (text, action) = when (link) {
        is LinkState.Connected -> return
        LinkState.Connecting -> (if (pairing) "Pairing…" else "Connecting…") to false
        is LinkState.Retrying -> (if (link.inMs > 0) "Connection lost. Retrying in ${(link.inMs + 999) / 1000} s" else "Reconnecting…") to true
        is LinkState.Failed -> link.message to true
    }
    val failed = link is LinkState.Failed
    Surface(
        color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!failed) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (action) {
                TextButton(onClick = onRetry) {
                    Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Retry")
                }
            }
        }
    }
}

@Composable
private fun LoadingState(hostName: String, link: LinkState) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        if (link !is LinkState.Failed) {
            CircularProgressIndicator(strokeWidth = 3.dp)
            Spacer(Modifier.size(16.dp))
            Text("Opening the session on $hostName", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Icon(Icons.Outlined.LinkOff, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(40.dp))
        }
    }
}

@Composable
private fun MessageList(state: SessionState) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val items = state.items
    val streaming = state.streaming
    // LazyColumn crashes on duplicate keys: fall back to positions if the reducer ever produced one.
    val keysUnique = remember(items, streaming) {
        val seen = HashSet<String>(items.size + streaming.size)
        items.all { seen.add(it.key) } && streaming.all { seen.add(it.key) }
    }
    val showWorking = state.isRunning && streaming.isEmpty()
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 40 } }

    Box(Modifier.fillMaxSize()) {
        if (items.isEmpty() && streaming.isEmpty() && !state.isRunning) {
            EmptySession(state)
        }
        // Reversed: index 0 is the newest row, so a growing reply stays pinned to the bottom while the user reads there,
        // and stays put when they scrolled up.
        LazyColumn(
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (showWorking) item(key = "working", contentType = "working") { WorkingIndicator(if (state.isCompacting) "Compacting" else "Working") }
            items(
                count = streaming.size,
                key = { i -> if (keysUnique) streaming[streaming.size - 1 - i].key else "s$i" },
                contentType = { i -> streaming[streaming.size - 1 - i]::class.simpleName },
            ) { i -> ChatItemView(streaming[streaming.size - 1 - i]) }
            items(
                count = items.size,
                key = { i -> if (keysUnique) items[items.size - 1 - i].key else "i$i" },
                contentType = { i -> items[items.size - 1 - i]::class.simpleName },
            ) { i -> ChatItemView(items[items.size - 1 - i]) }
        }
        AnimatedVisibility(
            visible = !atBottom,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            SmallFloatingActionButton(
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Jump to latest") }
        }
    }
}

@Composable
private fun EmptySession(state: SessionState) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("New session", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(6.dp))
        Text(
            state.host?.cwd?.takeIf { it.isNotBlank() }?.let { "Pi is ready in ${shortPath(it)}" } ?: "Pi is ready",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (state.commands.isNotEmpty()) {
            Spacer(Modifier.size(10.dp))
            Text(
                "Type / for ${state.commands.size} commands",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun StatusStrip(state: SessionState) {
    val lines = buildList {
        state.retry?.let { add("Retrying ${it.attempt}/${it.maxAttempts}" + (it.error?.let { e -> ": $e" } ?: "")) }
        if (state.isCompacting) add("Compacting context…")
        addAll(state.statuses.values)
        state.widgets.values.forEach { addAll(it) }
    }
    if (lines.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        for (line in lines.take(6)) {
            Text(
                line,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = Mono),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun QueuePanel(state: SessionState, onClear: () -> Unit) {
    val queue = state.queue
    if (queue.isEmpty) return
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Queued (${queue.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClear) { Text("Clear and edit") }
            }
            val entries = queue.steering.map { "Steer" to it } + queue.followUp.map { "Follow-up" to it }
            for ((kind, text) in entries.take(4)) {
                Row(Modifier.padding(bottom = 4.dp, end = 10.dp)) {
                    Text(
                        kind,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(72.dp),
                    )
                    Text(text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Composer(
    text: String,
    onText: (String) -> Unit,
    state: SessionState,
    enabled: Boolean,
    mode: StreamingBehavior,
    onMode: (StreamingBehavior) -> Unit,
    onSend: () -> Unit,
    onAbort: () -> Unit,
    onModel: () -> Unit,
    onThinking: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = scheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        ) {
            val suggestions = remember(text, state.commands) { slashSuggestions(text, state.commands) }
            if (suggestions.isNotEmpty()) {
                Column(Modifier.padding(bottom = 6.dp)) {
                    for (command in suggestions) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onText("/${command.name} ") }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("/${command.name}", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono), color = scheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                command.description.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick = onModel,
                    enabled = enabled,
                    label = { Text(state.model?.label ?: "Model", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthInMax(180)) },
                    leadingIcon = { Icon(Icons.Outlined.SmartToy, null, Modifier.size(AssistChipDefaults.IconSize)) },
                    shape = CircleShape,
                )
                AssistChip(
                    onClick = onThinking,
                    enabled = enabled,
                    label = { Text(state.thinkingLevel ?: "thinking") },
                    leadingIcon = { Icon(Icons.Outlined.Lightbulb, null, Modifier.size(AssistChipDefaults.IconSize)) },
                    shape = CircleShape,
                )
                state.lastUsage?.let { usage ->
                    val window = state.model?.contextWindow
                    val used = usage.totalTokens
                    val label = if (window != null && window > 0) "${(used * 100 / window).coerceIn(0, 100)}% context" else "${SessionReducer.formatTokens(used)} tokens"
                    Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.outline, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }

            if (state.isRunning) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                        SegmentedButton(
                            selected = mode == StreamingBehavior.Steer,
                            onClick = { onMode(StreamingBehavior.Steer) },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) { Text("Steer") }
                        SegmentedButton(
                            selected = mode == StreamingBehavior.FollowUp,
                            onClick = { onMode(StreamingBehavior.FollowUp) },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) { Text("Follow-up") }
                    }
                    Spacer(Modifier.width(10.dp))
                    FilledIconButton(
                        onClick = onAbort,
                        enabled = enabled,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = scheme.errorContainer, contentColor = scheme.onErrorContainer),
                    ) { Icon(Icons.Outlined.Stop, contentDescription = "Abort") }
                }
            }

            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .background(scheme.surfaceContainerHighest, RoundedCornerShape(22.dp))
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) {
                        Text(
                            when {
                                !enabled -> "Not connected"
                                state.isRunning && mode == StreamingBehavior.Steer -> "Steer Pi…"
                                state.isRunning -> "Queue a follow-up…"
                                else -> "Message Pi…"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = onText,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                        cursorBrush = SolidColor(scheme.primary),
                        maxLines = 8,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = onSend,
                    enabled = enabled && text.isNotBlank(),
                    modifier = Modifier.size(48.dp),
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
            }
        }
    }
}

private fun Modifier.widthInMax(dp: Int): Modifier = this.then(Modifier.widthIn(max = dp.dp))

private fun slashSuggestions(text: String, commands: List<SlashCommand>): List<SlashCommand> {
    if (!text.startsWith("/") || text.contains(' ') || text.contains('\n')) return emptyList()
    val query = text.drop(1).lowercase()
    return commands.filter { it.name.lowercase().startsWith(query) }.take(5)
}

@Composable
private fun TextPromptDialog(
    title: String,
    initial: String,
    placeholder: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = { Text(placeholder) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

