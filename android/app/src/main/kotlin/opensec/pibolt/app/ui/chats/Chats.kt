package opensec.pibolt.app.ui.chats

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.engine.ChatSpace
import opensec.pibolt.app.engine.EngineApi
import opensec.pibolt.app.engine.SessionSummary
import opensec.pibolt.app.ui.projects.LocalSessions
import opensec.pibolt.app.ui.projects.ProjectRules
import opensec.pibolt.app.ui.session.features2.SessionBrowse
import java.io.File

/** A saved chat as the Chats list shows it. */
@Immutable
data class ChatSummary(val file: String, val title: String, val updatedAt: Long, val messageCount: Int) {
    companion object {
        fun of(s: SessionSummary): ChatSummary = ChatSummary(s.file, ChatSpace.title(s.name, s.preview), s.updatedAt, s.messageCount)
    }
}

/** The Chats list's text and search, kept apart so they can be tested. */
object ChatList {
    fun messages(n: Int): String = if (n == 1) "1 message" else "$n messages"

    /** The row's second line: "2h · 12 messages". */
    fun meta(chat: ChatSummary, now: Long): String = ProjectRules.agoShort(chat.updatedAt, now) + " · " + messages(chat.messageCount)

    /** [chats] whose title contains every word of [query] (any case), in their order. */
    fun filter(chats: List<ChatSummary>, query: String): List<ChatSummary> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return chats
        return chats.filter { c -> val t = c.title.lowercase(); words.all { it in t } }
    }
}

/**
 * Plain chats (engine.ChatSpace) for the UI: the live chat, the saved ones as a [Flow], and start, resume, rename and
 * delete. One chat is live at a time, kept here and not in [LocalSessions.all], so chats never show where projects'
 * sessions do (Home's running list, the session switcher). [follow] keeps [current] in step with the app's live
 * sessions. Disk and engine work runs on [Dispatchers.IO].
 */
object Chats {
    private val _current = MutableStateFlow<LocalSessions.Live?>(null)

    /** The live chat: a new [LocalSessions.Live] for every open, so its screen starts fresh for it. */
    val current: StateFlow<LocalSessions.Live?> = _current.asStateFlow()

    /** Bumped when this phone changed the saved chats (open, rename, delete) or asked to look again. */
    private val changes = MutableStateFlow(0L)

    @Volatile
    private var scope: CoroutineScope? = null

    /** Keeps [current] in step with the app's live sessions (a chat that stops leaves). Call once. */
    fun follow(app: PiBoltApp) {
        scope = app.appScope
        app.appScope.launch {
            app.sessions.collect { list ->
                _current.update { live -> live?.takeIf { l -> list.any { it === l.controller } } }
            }
        }
    }

    /** Starts a new chat; [firstPrompt], when given, is sent once it can take it. */
    suspend fun start(engine: EngineApi, firstPrompt: String? = null): Result<LocalSessions.Live> = open(engine, null, firstPrompt?.takeIf { it.isNotBlank() })

    /** Opens the saved chat [file] (a path from [list]). */
    suspend fun resume(engine: EngineApi, file: String): Result<LocalSessions.Live> = open(engine, file, null)

    private suspend fun open(engine: EngineApi, file: String?, firstPrompt: String?): Result<LocalSessions.Live> {
        val before = _current.value?.controller?.takeIf { !it.stopped }?.state?.value?.sessionId
        val result = withContext(Dispatchers.IO) { runCatching { engine.openChat(file) } }
        return result.mapCatching { controller ->
            // The live chat moves to a new session over RPC: a prompt waits for the new session's snapshot, or it would
            // go to the chat it is leaving. If the switch does not arrive (failed, cancelled, too slow), the open fails.
            if (firstPrompt != null && before != null && controller.state.value.let { it.sessionId == before && it.items.isNotEmpty() }) {
                withTimeoutOrNull(SWITCH_TIMEOUT_MS) { controller.state.first { it.hello && it.sessionId != before && it.items.isEmpty() } }
                    ?: throw IllegalStateException("The new chat did not open")
            }
            LocalSessions.Live(engine.chatDir, controller, file).also {
                it.firstPrompt = firstPrompt
                _current.value = it
                refresh()
            }
        }
    }

    /** Look at the saved chats again ([list] emits). */
    fun refresh() = changes.update { it + 1 }

    /**
     * The saved chats, newest first: read again after every change from this phone and as the live chat's conversation
     * changes (its name, its messages, a turn's end).
     */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun list(engine: EngineApi): Flow<List<ChatSummary>> {
        val live = _current.flatMapLatest { l ->
            l?.controller?.state?.map { s -> listOf(s.sessionId, s.sessionName, s.isRunning, s.items.size) }?.distinctUntilChanged() ?: flowOf(null)
        }
        return combine(changes, live) { n, s -> n to s }
            .debounce(LIST_DEBOUNCE_MS)
            .mapLatest { withContext(Dispatchers.IO) { runCatching { engine.chats() }.getOrDefault(emptyList()).map(ChatSummary::of) } }
    }

    /** Renames [chat]: over RPC when it is the live chat, else in its file. False when it could not be written. */
    suspend fun rename(chat: ChatSummary, name: String): Boolean {
        val trimmed = name.trim()
        val live = liveWith(chat.file)
        val ok = if (live != null) {
            live.controller.rename(trimmed)
            true
        } else {
            withContext(Dispatchers.IO) { SessionBrowse.rename(File(chat.file), trimmed) }
        }
        refresh()
        return ok
    }

    /** Deletes [chat]'s file; the live chat stops first (its engine with it). False when the file could not be deleted. */
    suspend fun delete(chat: ChatSummary): Boolean {
        liveWith(chat.file)?.let { live ->
            live.controller.stop(keepEngine = false)
            _current.update { it.takeUnless { c -> c === live } }
        }
        val file = File(chat.file)
        val ok = withContext(Dispatchers.IO) { file.delete() || !file.exists() }
        // An engine that was stopping may still write its last entry, which would leave a file without a header.
        scope?.launch {
            delay(STRAY_WRITE_MS)
            withContext(Dispatchers.IO) { if (file.exists()) file.delete() }
            refresh()
        }
        refresh()
        return ok
    }

    private fun liveWith(file: String): LocalSessions.Live? = _current.value?.takeIf { !it.controller.stopped && it.controller.sessionFile == file }

    private const val SWITCH_TIMEOUT_MS = 15_000L
    private const val LIST_DEBOUNCE_MS = 250L
    private const val STRAY_WRITE_MS = 2_000L
}
