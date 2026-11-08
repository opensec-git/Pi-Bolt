package opensec.pibolt.app.ui.chats

import android.content.Context
import android.content.SharedPreferences
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
import kotlinx.serialization.json.put
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.cost.Cost
import opensec.pibolt.app.engine.ChatSpace
import opensec.pibolt.app.engine.EngineApi
import opensec.pibolt.app.engine.SessionSummary
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.protocol.obj
import opensec.pibolt.protocol.rpc.ModelInfo
import opensec.pibolt.app.ui.cost.SpendText
import opensec.pibolt.app.ui.projects.LocalSessions
import opensec.pibolt.app.ui.projects.ProjectRules
import opensec.pibolt.app.ui.session.features2.SessionBrowse
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** A saved chat as the Chats list shows it. */
@Immutable
data class ChatSummary(val file: String, val title: String, val updatedAt: Long, val messageCount: Int, val cost: Cost = Cost.NONE) {
    companion object {
        fun of(s: SessionSummary): ChatSummary = ChatSummary(s.file, ChatSpace.title(s.name, s.preview), s.updatedAt, s.messageCount, s.cost)
    }
}

/** The Chats list's text and search, kept apart so they can be tested. */
object ChatList {
    fun messages(n: Int): String = if (n == 1) "1 message" else "$n messages"

    /** The row's second line: "2h · 12 messages", and with [showCost] its cost ("2h · 12 messages · $0.42"). */
    fun meta(chat: ChatSummary, now: Long, showCost: Boolean = false): String =
        SpendText.withCost(ProjectRules.agoShort(chat.updatedAt, now) + " · " + messages(chat.messageCount), chat.cost, showCost)

    /**
     * [chats] (newest first) under date heads, as ChatGPT's list: "Today", "Yesterday", "Previous 7 days", "Previous 30
     * days", then the month ("October", with the year when it is not this year's). Days are [zone]'s calendar days.
     */
    fun sections(chats: List<ChatSummary>, now: Long, zone: ZoneId): List<Pair<String, List<ChatSummary>>> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun head(at: Long): String {
            val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
            val days = ChronoUnit.DAYS.between(day, today)
            return when {
                days <= 0 -> "Today"
                days == 1L -> "Yesterday"
                days < 7 -> "Previous 7 days"
                days < 30 -> "Previous 30 days"
                else -> day.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + if (day.year != today.year) " ${day.year}" else ""
            }
        }
        val out = LinkedHashMap<String, MutableList<ChatSummary>>()
        for (c in chats) out.getOrPut(head(c.updatedAt)) { ArrayList() } += c
        return out.map { (k, v) -> k to v }
    }

    /** [chats] whose title contains every word of [query] (any case), in their order. */
    fun filter(chats: List<ChatSummary>, query: String): List<ChatSummary> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return chats
        return chats.filter { c -> val t = c.title.lowercase(); words.all { it in t } }
    }
}

/** What a chat suggestion is about: the welcome shows its glyph before the label. */
enum class ChatTopic { Explain, Write, Ideas, Travel, Learn, Cook, Decide, Summarise, Quiz, Edit }

/** A suggestion on a new chat's welcome: [label] on the row, [prompt] put in the composer for the user to finish. */
@Immutable
data class ChatStarter(val label: String, val prompt: String, val topic: ChatTopic)

/**
 * A new chat's welcome (ChatGPT's and Claude's): a greeting for the time of day over "What's on your mind?", and four
 * everyday suggestions (not a project's: a chat has no files or tools). Each prompt ends where the user goes on typing,
 * so a tap fills the composer and never sends. The four rotate daily through [POOL], one topic each.
 */
object ChatWelcome {
    const val QUESTION = "What's on your mind?"

    /** How many suggestions the welcome shows. */
    const val COUNT = 4

    /** "Good morning" (5 to 11), "Good afternoon" (12 to 16), "Good evening" (17 to 22), else "Hello". */
    fun greeting(hour: Int): String = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..22 -> "Good evening"
        else -> "Hello"
    }

    val POOL = listOf(
        ChatStarter("Explain something simply", "Explain this simply, with an everyday example: ", ChatTopic.Explain),
        ChatStarter("Help me write a message", "Help me write a short, friendly message to ", ChatTopic.Write),
        ChatStarter("Brainstorm ideas", "Give me ten fresh ideas for ", ChatTopic.Ideas),
        ChatStarter("Plan a trip", "Plan a relaxed three-day trip to ", ChatTopic.Travel),
        ChatStarter("Teach me something new", "Teach me the basics of ", ChatTopic.Learn),
        ChatStarter("Cook with what I have", "Suggest a simple recipe I can make with ", ChatTopic.Cook),
        ChatStarter("Weigh a decision", "Help me decide, with the pros and cons of each option: ", ChatTopic.Decide),
        ChatStarter("Summarise a text", "Summarise this in five short bullet points:\n\n", ChatTopic.Summarise),
        ChatStarter("Quiz me", "Quiz me with five questions, one at a time, on ", ChatTopic.Quiz),
        ChatStarter("Polish my writing", "Make this clearer and more concise, keeping my voice:\n\n", ChatTopic.Edit),
    )

    /** [COUNT] suggestions for [epochDay]: the pool rotated by the day, so they change daily but not while you look. */
    fun pick(epochDay: Long): List<ChatStarter> {
        val start = Math.floorMod(epochDay * COUNT, POOL.size.toLong()).toInt()
        return List(COUNT) { POOL[(start + it) % POOL.size] }
    }
}

/**
 * The model a new chat starts on: the last chat's (and its thinking level), not Pi's global default,
 * which is the project's choice. Kept apart so it can be tested.
 */
@Immutable
data class ChatModel(val provider: String, val id: String, val thinking: String?) {
    companion object {
        /**
         * The session a new chat's model carry waits to leave: [before] (the live chat the engine moves on from), or
         * null when the engine handed back that same chat ([reused]) because it was already new, ready and empty
         * ([readyEmpty]); waiting for a switch that never comes would hold the first prompt for the whole timeout.
         */
        fun leaving(before: String?, reused: Boolean, readyEmpty: Boolean): String? = if (reused && readyEmpty) null else before

        /**
         * The model a chat on [current] should switch to: [remembered], or null when it already runs it (or none), or when
         * it is not among the engine's [available] models (null: unknown), such as one whose provider key was removed.
         */
        fun switchTo(remembered: ChatModel?, current: ModelInfo?, available: List<ModelInfo>? = null): ChatModel? =
            remembered?.takeIf { current == null || it.provider != current.provider || it.id != current.id }
                ?.takeIf { r -> available == null || available.any { it.provider == r.provider && it.id == r.id } }
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

    @Volatile
    private var prefs: SharedPreferences? = null

    /** Keeps [current] in step with the app's live sessions (a chat that stops leaves). Call once. */
    fun follow(app: PiBoltApp) {
        scope = app.appScope
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        app.appScope.launch {
            app.sessions.collect { list ->
                _current.update { live -> live?.takeIf { l -> list.any { it === l.controller } } }
            }
        }
    }

    private fun remembered(): ChatModel? = prefs?.let { p ->
        val provider = p.getString(KEY_PROVIDER, null) ?: return null
        val id = p.getString(KEY_MODEL, null) ?: return null
        ChatModel(provider, id, p.getString(KEY_THINKING, null))
    }

    /**
     * The user picked [model] in a chat (the model sheet): new chats start on it. Only a pick counts, never the model a
     * chat opens with (an old chat's, or Pi's default).
     */
    fun pickedModel(model: ModelInfo) {
        // A new model starts on its own default level until one is picked for it.
        prefs?.edit()?.putString(KEY_PROVIDER, model.provider)?.putString(KEY_MODEL, model.id)?.remove(KEY_THINKING)?.apply()
    }

    /** The user picked the thinking [level] in a chat: new chats start on it. */
    fun pickedThinking(level: String) {
        prefs?.edit()?.putString(KEY_THINKING, level)?.apply()
    }

    /**
     * A new chat starts on the last chat's model: once it is ready (a live engine moving on from the session [before]
     * first reports the new one), a different model (Pi's default) is switched to [wanted], then its thinking level.
     */
    private suspend fun carryModel(controller: SessionController, wanted: ChatModel, before: String?) {
        val ready = withTimeoutOrNull(MODEL_WAIT_MS) { controller.state.first { it.hello && (before == null || it.sessionId != before) } } ?: return
        // The engine's own word: right after a new session the state can still show the last session's model.
        val actual = ModelInfo.from(controller.request("get_state").dataObject?.obj("model")) ?: ready.model
        // Pi refuses a model it cannot use ("Model not found": its key removed): waiting for that switch held a first
        // prompt from Home for the whole timeout, on every new chat. Such a model is skipped; the chat keeps Pi's default.
        if (ChatModel.switchTo(wanted, actual) != null) {
            val available = controller.request("get_available_models").takeIf { it.success }?.let(ModelInfo::listFrom)
            val to = ChatModel.switchTo(wanted, actual, available) ?: return
            controller.setModel(ModelInfo(to.id, to.id, to.provider, reasoning = true, contextWindow = null))
            withTimeoutOrNull(MODEL_WAIT_MS) { controller.state.first { s -> s.model?.let { it.provider == to.provider && it.id == to.id } == true } }
        }
        val level = wanted.thinking
        if (level != null && controller.state.value.model?.reasoning != false) controller.setThinkingLevel(level)
    }

    /** Starts a new chat; [firstPrompt], when given, is sent once it can take it. */
    suspend fun start(engine: EngineApi, firstPrompt: String? = null): Result<LocalSessions.Live> = open(engine, null, firstPrompt?.takeIf { it.isNotBlank() })

    /** Opens the saved chat [file] (a path from [list]). */
    suspend fun resume(engine: EngineApi, file: String): Result<LocalSessions.Live> = open(engine, file, null)

    private suspend fun open(engine: EngineApi, file: String?, firstPrompt: String?): Result<LocalSessions.Live> {
        val previous = _current.value?.controller?.takeIf { !it.stopped }
        val before = previous?.state?.value?.sessionId
        // Read before the new chat reports its own (default) model, which [follow] would remember instead.
        val carry = if (file == null) remembered() else null
        val result = withContext(Dispatchers.IO) { runCatching { engine.openChat(file) } }
        return result.mapCatching { controller ->
            // The live chat moves to a new session over RPC: a prompt waits for the new session's snapshot, or it would
            // go to the chat it is leaving. If the switch does not arrive (failed, cancelled, too slow), the open fails.
            if (firstPrompt != null && before != null && controller.state.value.let { it.sessionId == before && it.items.isNotEmpty() }) {
                withTimeoutOrNull(SWITCH_TIMEOUT_MS) { controller.state.first { it.hello && it.sessionId != before && it.items.isEmpty() } }
                    ?: throw IllegalStateException("The new chat did not open")
            }
            // A first prompt (sent from Home) waits for the model, so it goes to the one the user chose. The engine may hand
            // back the live chat itself when it is already new and empty: then there is no session to leave.
            val leaving = ChatModel.leaving(before, reused = controller === previous, controller.state.value.let { it.hello && it.items.isEmpty() && it.sessionId == before })
            if (carry != null) {
                if (firstPrompt != null) carryModel(controller, carry, leaving) else scope?.launch { carryModel(controller, carry, leaving) }
            }
            LocalSessions.Live(engine.chatDir, controller, file).also {
                _current.value = it
                it.queueFirstPrompt(firstPrompt, _current.map { live -> live === it })
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

    /**
     * Renames [chat]: over RPC when it is the live chat, else in its file. False when it could not be written, or [name]
     * is blank (Pi refuses an empty name: the live chat's refusal went to no screen, and a saved chat lost its name).
     */
    suspend fun rename(chat: ChatSummary, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val live = liveWith(chat.file)
        val ok = if (live != null) {
            // Pi's answer, not the fire-and-forget rename: its error has no session screen to show on here.
            live.controller.request("set_session_name") { put("name", trimmed) }.success
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
    private const val MODEL_WAIT_MS = 15_000L
    private const val PREFS = "chats"
    private const val KEY_PROVIDER = "model_provider"
    private const val KEY_MODEL = "model_id"
    private const val KEY_THINKING = "thinking"
    private const val LIST_DEBOUNCE_MS = 250L
    private const val STRAY_WRITE_MS = 2_000L
}
