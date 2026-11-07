package opensec.pibolt.app.ui.chats

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.ui.components.ConfirmSheet
import opensec.pibolt.app.ui.components.EmptyState
import opensec.pibolt.app.ui.components.GlassBarInset
import opensec.pibolt.app.ui.components.GlassIconButton
import opensec.pibolt.app.ui.components.GlassSearchField
import opensec.pibolt.app.ui.components.InsetRow
import opensec.pibolt.app.ui.components.PiSnackHost
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.SnackKind
import opensec.pibolt.app.ui.components.show
import opensec.pibolt.app.ui.session.ActionsSheet
import opensec.pibolt.app.ui.session.SheetAction
import opensec.pibolt.app.ui.session.TextPromptSheet
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Space
import java.time.ZoneId

/**
 * Plain chats (engine.ChatSpace): a large "Chats" title, a glass search, + for a new chat, and the saved chats newest
 * first as inset rows ("Trip ideas" over "2h · 12 messages"; the live one highlighted). A tap opens a chat; a long
 * press offers Rename and Delete (confirmed). With no chats: one line and "New chat". [onOpenChat] shows the live
 * chat once [Chats] opened it.
 */
@Composable
fun ChatsScreen(app: PiBoltApp, onBack: () -> Unit, onOpenChat: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val haptics = Pi.haptics
    val engine = app.engine
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val chats by remember(engine) { Chats.list(engine) }.collectAsStateWithLifecycle(initialValue = null)
    val live by Chats.current.collectAsState()
    val liveFile = live?.controller?.takeIf { !it.stopped }?.sessionFile
    var query by rememberSaveable { mutableStateOf("") }
    var actionsFor by remember { mutableStateOf<ChatSummary?>(null) }
    var renaming by remember { mutableStateOf<ChatSummary?>(null) }
    var deleting by remember { mutableStateOf<ChatSummary?>(null) }
    var opening by remember { mutableStateOf(false) }
    // Back from a chat (or another app): its new messages and name show at once.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { Chats.refresh() }

    fun open(file: String?) {
        if (opening) return
        opening = true
        scope.launch {
            val result = if (file == null) Chats.start(engine) else Chats.resume(engine, file)
            opening = false
            result
                .onSuccess { onOpenChat() }
                .onFailure {
                    haptics.reject()
                    snackbar.show(it.message ?: "Could not open the chat", SnackKind.Error)
                }
        }
    }

    Box(Modifier.fillMaxSize().background(colors.canvas)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.statusBarsPadding().fillMaxWidth().height(56.dp).padding(horizontal = GlassBarInset),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
                Spacer(Modifier.weight(1f))
                GlassIconButton(Icons.Rounded.Add, "New chat", onClick = { open(null) }, enabled = !opening)
            }
            Text(
                "Chats",
                style = type.largeTitle,
                color = colors.ink1,
                modifier = Modifier.padding(start = Space.page, end = Space.page, top = Space.sm, bottom = Space.md).semantics { heading() },
            )
            val all = chats
            if (all != null && all.isNotEmpty()) {
                GlassSearchField(query, { query = it }, Modifier.padding(horizontal = Space.page).padding(bottom = Space.sm), placeholder = "Search chats")
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                when {
                    all == null -> Unit
                    all.isEmpty() -> EmptyState(
                        title = "No chats yet",
                        message = "Ask anything, without a project. Your chats show here.",
                        icon = Icons.Rounded.ChatBubbleOutline,
                        modifier = Modifier.padding(horizontal = Space.page, vertical = Space.xxl).widthIn(max = 560.dp),
                        action = { PrimaryButton("New chat", onClick = { open(null) }, icon = Icons.Rounded.Add, enabled = !opening) },
                    )
                    else -> {
                        val shown = remember(all, query) { ChatList.filter(all, query) }
                        val now = remember(all) { System.currentTimeMillis() }
                        val sections = remember(shown, now) { ChatList.sections(shown, now, ZoneId.systemDefault()) }
                        if (shown.isEmpty()) {
                            EmptyState(
                                title = "No chats match",
                                message = "Try other words, or start a new chat.",
                                icon = Icons.Rounded.SearchOff,
                                modifier = Modifier.padding(horizontal = Space.page, vertical = Space.xl).widthIn(max = 560.dp),
                            )
                        }
                        LazyColumn(
                            Modifier.fillMaxSize().widthIn(max = 720.dp),
                            contentPadding = PaddingValues(bottom = Space.xxl + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                            verticalArrangement = Arrangement.Top,
                        ) {
                            for ((head, inSection) in sections) {
                                item(key = "head:$head", contentType = "head") {
                                    Text(
                                        head,
                                        style = type.rowMeta,
                                        color = colors.ink3,
                                        modifier = Modifier.animateItem().padding(start = Space.page, end = Space.page, top = Space.lg, bottom = Space.xs).semantics { heading() },
                                    )
                                }
                                itemsIndexed(inSection, key = { _, c -> c.file }, contentType = { _, _ -> "chat" }) { i, chat ->
                                    InsetRow(
                                        title = chat.title,
                                        meta = ChatList.meta(chat, now, Pi.settings.spend.showInLists),
                                        onClick = { open(chat.file) },
                                        onLongClick = {
                                            haptics.longPress()
                                            actionsFor = chat
                                        },
                                        divider = i < inSection.lastIndex,
                                        current = chat.file == liveFile,
                                        label = "Open chat",
                                        modifier = Modifier.animateItem(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        PiSnackHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    actionsFor?.let { chat ->
        ActionsSheet(
            title = chat.title,
            actions = listOf(
                SheetAction("Rename", Icons.Rounded.DriveFileRenameOutline) {
                    actionsFor = null
                    renaming = chat
                },
                SheetAction("Delete", Icons.Rounded.Delete) {
                    actionsFor = null
                    deleting = chat
                },
            ),
            onDismiss = { actionsFor = null },
        )
    }
    renaming?.let { chat ->
        TextPromptSheet(
            title = "Rename chat",
            message = null,
            initial = remember(chat) { chat.title },
            placeholder = "Chat name",
            confirm = "Rename",
            onConfirm = { name ->
                renaming = null
                scope.launch { if (!Chats.rename(chat, name)) snackbar.show("Could not rename the chat", SnackKind.Error) }
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { chat ->
        ConfirmSheet(
            title = "Delete this chat?",
            message = "This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                deleting = null
                scope.launch {
                    if (Chats.delete(chat)) snackbar.show("Chat deleted") else snackbar.show("Could not delete the chat", SnackKind.Error)
                }
            },
            onDismiss = { deleting = null },
        )
    }
}
