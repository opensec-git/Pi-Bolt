package opensec.pibolt.app.ui.chats

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
import opensec.pibolt.protocol.session.UserItem

/**
 * The live chat ([Chats.current]) on the session screen, as a chat: titled by its name or first prompt, without the
 * project-only parts ([LocalUi.chat]). "New session" starts a new chat. [onOpenSession] shows a project's live session
 * from the switcher.
 */
@Composable
fun ChatSessionScreen(app: PiBoltApp, onBack: () -> Unit, onOpenSession: (String) -> Unit) {
    val current by Chats.current.collectAsStateWithLifecycle()
    val live = current
    if (live == null || live.controller.stopped) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    LaunchedEffect(live.controller) { app.focus(live.controller) }
    val scope = rememberCoroutineScope()
    val engine = app.engine
    // Every open is a new Live: the screen starts fresh for it (a first prompt is sent once the new chat can take it).
    key(live) {
        val state = live.controller.state.collectAsStateWithLifecycle()
        val title by remember {
            derivedStateOf {
                val s = state.value
                ChatSpace.title(s.sessionName, (s.items.firstOrNull { it is UserItem } as? UserItem)?.text)
            }
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
                takeFirstPrompt = { live.firstPrompt.also { live.firstPrompt = null } },
                composerDrop = live.composerDrop,
                chat = true,
            ),
            onOpenSession = onOpenSession,
        )
    }
}
