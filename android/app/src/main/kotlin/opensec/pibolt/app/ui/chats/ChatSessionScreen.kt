package opensec.pibolt.app.ui.chats

import kotlinx.coroutines.flow.update
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Balance
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Quiz
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.automirrored.rounded.ShortText
import androidx.compose.material.icons.rounded.Spellcheck
import androidx.compose.material.icons.rounded.TipsAndUpdates
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.engine.ChatSpace
import opensec.pibolt.app.ui.session.LocalUi
import opensec.pibolt.app.ui.session.SessionContent
import opensec.pibolt.app.ui.session.StarterPrompt
import opensec.pibolt.app.ui.session.WelcomeContent
import opensec.pibolt.protocol.session.UserItem
import androidx.compose.ui.graphics.vector.ImageVector
import java.time.LocalDateTime

/**
 * The live chat ([Chats.current]) on the session screen, as a chat: titled by its name or first prompt, without the
 * project-only parts ([LocalUi.chat]). Its top bar has New chat and All chats ([onOpenChats]); a new chat greets with
 * [ChatWelcome]. [onOpenSession] shows a project's live session from the switcher.
 */
@Composable
fun ChatSessionScreen(app: PiBoltApp, onBack: () -> Unit, onOpenSession: (String) -> Unit, onOpenChats: () -> Unit = {}) {
    val current by Chats.current.collectAsStateWithLifecycle()
    val live = current
    if (live == null || live.controller.stopped) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    LaunchedEffect(live.controller) { app.focus(live.controller) }
    val scope = rememberCoroutineScope()
    val engine = app.engine
    // The greeting and the day's suggestions, fixed while the screen lives.
    val welcome = remember {
        val now = LocalDateTime.now()
        WelcomeContent(
            title = ChatWelcome.greeting(now.hour),
            question = ChatWelcome.QUESTION,
            starters = ChatWelcome.pick(now.toLocalDate().toEpochDay()).map { StarterPrompt(it.label, it.prompt, topicIcon(it.topic)) },
        )
    }
    // Every open is a new Live: the screen starts fresh for it (a first prompt is sent once the new chat can take it).
    key(live) {
        val state = live.controller.state.collectAsStateWithLifecycle()
        val title by remember {
            derivedStateOf {
                val s = state.value
                ChatSpace.title(s.sessionName, (s.items.firstOrNull { it is UserItem } as? UserItem)?.text)
            }
        }
        // While this screen shows the chat, it sends a queued first prompt itself (LocalSessions.Live.queueFirstPrompt).
        DisposableEffect(live) {
            live.screens.update { it + 1 }
            onDispose { live.screens.update { it - 1 } }
        }
        SessionContent(
            app = app,
            controller = live.controller,
            onBack = onBack,
            onDisconnect = onBack,
            onForget = onBack,
            local = LocalUi(
                project = ChatSpace.KEY,
                title = title,
                onNewSession = { scope.launch { Chats.start(engine) } },
                onRestart = {
                    engine.prepare()
                    live.controller.retryNow()
                },
                takeFirstPrompt = live::takeFirstPrompt,
                composerDrop = live.composerDrop,
                chat = true,
                welcome = welcome,
                onChats = onOpenChats,
                onModelPicked = Chats::pickedModel,
                onThinkingPicked = Chats::pickedThinking,
                onDelete = {
                    val file = live.controller.sessionFile
                    scope.launch {
                        if (file != null) Chats.delete(ChatSummary(file, title, 0L, 0)) else live.controller.stop(keepEngine = false)
                    }
                },
            ),
            onOpenSession = onOpenSession,
        )
    }
}

/** The glyph before a chat suggestion: outline marks in the row's leading slot. */
private fun topicIcon(topic: ChatTopic): ImageVector = when (topic) {
    ChatTopic.Explain -> Icons.Rounded.TipsAndUpdates
    ChatTopic.Write -> Icons.Rounded.EditNote
    ChatTopic.Ideas -> Icons.Rounded.AutoAwesome
    ChatTopic.Travel -> Icons.Rounded.TravelExplore
    ChatTopic.Learn -> Icons.Rounded.School
    ChatTopic.Cook -> Icons.Rounded.Restaurant
    ChatTopic.Decide -> Icons.Rounded.Balance
    ChatTopic.Summarise -> Icons.AutoMirrored.Rounded.ShortText
    ChatTopic.Quiz -> Icons.Rounded.Quiz
    ChatTopic.Edit -> Icons.Rounded.Spellcheck
}
