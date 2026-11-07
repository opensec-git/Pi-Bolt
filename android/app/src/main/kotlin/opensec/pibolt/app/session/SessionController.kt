package opensec.pibolt.app.session

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.checkpoints.TurnCheckpoints
import opensec.pibolt.app.data.Host
import opensec.pibolt.protocol.Base64Url
import opensec.pibolt.protocol.backend.ConnectionState
import opensec.pibolt.protocol.backend.DisconnectReason
import opensec.pibolt.protocol.backend.LocalBackend
import opensec.pibolt.protocol.backend.RemoteBackend
import opensec.pibolt.protocol.backend.RemoteConfig
import opensec.pibolt.protocol.backend.SessionBackend
import opensec.pibolt.protocol.rpc.ImageAttachment
import opensec.pibolt.protocol.rpc.ModelInfo
import opensec.pibolt.protocol.rpc.RpcClient
import opensec.pibolt.protocol.rpc.RpcResponse
import opensec.pibolt.protocol.rpc.StreamingBehavior
import opensec.pibolt.protocol.rpc.clearedQueue
import opensec.pibolt.protocol.rpc.thinkingLevels
import opensec.pibolt.protocol.session.AssistantTextItem
import opensec.pibolt.protocol.session.SessionReducer
import opensec.pibolt.protocol.session.SessionState
import opensec.pibolt.protocol.arr
import opensec.pibolt.protocol.bool
import opensec.pibolt.protocol.str
import opensec.pibolt.protocol.type
import java.io.File
import opensec.pibolt.app.data.AgentDefaults
import opensec.pibolt.app.ui.session.features.SessionStats
import opensec.pibolt.app.ui.session.features.ShellCommand
import opensec.pibolt.protocol.session.ToolItem
import opensec.pibolt.protocol.session.ToolStatus
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject

sealed interface SessionEffect {
    data class Notify(val message: String, val kind: String) : SessionEffect

    /** Put text into the composer (extension `set_editor_text`, a failed send, or a cleared queue). */
    data class EditorText(val text: String) : SessionEffect

    data class Error(val message: String) : SessionEffect
}

/** The connection as the session screen explains it. */
sealed interface LinkState {
    data object Connecting : LinkState

    data class Connected(val endpoint: String) : LinkState

    data class Retrying(val reason: DisconnectReason, val detail: String?, val inMs: Long) : LinkState

    /** Will not retry by itself: the user has to act (re-pair, retry). */
    data class Failed(val reason: DisconnectReason, val message: String) : LinkState
}

/** A user message a session can be forked from (`get_fork_messages`). */
data class ForkPoint(val entryId: String, val text: String)

/**
 * One live session: with a computer over remote control, or with the engine on this phone. Connection with automatic
 * reconnect (for a local session: respawn into the same session), the reduced [SessionState] (published at most once
 * per frame), commands, and side effects for the UI.
 */
class SessionController private constructor(
    private val app: PiBoltApp,
    val hostId: String,
    hostName: String,
    private val origin: Origin,
) {
    /** A session with a paired computer. */
    constructor(
        app: PiBoltApp,
        hostId: String,
        hostName: String,
        endpoints: List<String>,
        /** Present while pairing: sent in the first handshake only. */
        pairSecret: ByteArray?,
        /** A known host scanned again: tried only if the host refuses this phone without it. */
        fallbackSecret: ByteArray? = null,
    ) : this(app, hostId, hostName, Origin.Remote(endpoints, pairSecret, fallbackSecret))

    private sealed interface Origin {
        class Remote(val endpoints: List<String>, val pairSecret: ByteArray?, val fallbackSecret: ByteArray?) : Origin

        /** [release] gets the backend back when the session stops, and whether the engine was idle then. */
        class Local(val project: File, val backend: LocalBackend, val release: (LocalBackend, Boolean) -> Unit, val checkpoints: TurnCheckpoints?) : Origin
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val pairing: Boolean = origin is Origin.Remote && origin.pairSecret != null

    /** Runs on the engine on this phone; every RPC command is available (remote control allows a subset). */
    val isLocal: Boolean = origin is Origin.Local

    /** The project directory of a local session. */
    val project: File? = (origin as? Origin.Local)?.project

    /** Snapshots of the project per turn, for review and undo (on this phone only: remote files are on the computer). */
    val checkpoints: TurnCheckpoints? = (origin as? Origin.Local)?.checkpoints

    /** The session file the local engine has open, from its last state. */
    val sessionFile: String? get() = (origin as? Origin.Local)?.backend?.sessionFile

    /** The engine's recent stderr for a local session (for the error sheet), else null. */
    fun engineLog(): String? = (origin as? Origin.Local)?.backend?.stderr?.text()

    private val fallbackSecret: ByteArray? = (origin as? Origin.Remote)?.fallbackSecret

    private val _hostName = MutableStateFlow(hostName)
    val hostName: StateFlow<String> = _hostName.asStateFlow()

    private var backend: SessionBackend = when (origin) {
        is Origin.Remote -> newBackend(origin.endpoints, origin.pairSecret)
        is Origin.Local -> origin.backend
    }
    private var rpc = RpcClient(backend, scope)

    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _link = MutableStateFlow<LinkState>(LinkState.Connecting)
    val link: StateFlow<LinkState> = _link.asStateFlow()

    private val _effects = MutableSharedFlow<SessionEffect>(extraBufferCapacity = 64)
    val effects: SharedFlow<SessionEffect> = _effects.asSharedFlow()

    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val models: StateFlow<List<ModelInfo>> = _models.asStateFlow()

    private val _thinkingLevels = MutableStateFlow<List<String>>(emptyList())
    val thinkingLevels: StateFlow<List<String>> = _thinkingLevels.asStateFlow()

    /** User `!` commands ([runShell]) still running. They run outside agent turns, so [SessionState.isRunning] misses them. */
    private val _shellRuns = MutableStateFlow(0)
    val shellRuns: StateFlow<Int> = _shellRuns.asStateFlow()

    private sealed interface Input {
        class Event(val json: JsonObject) : Input

        class Mutate(val change: (SessionState) -> SessionState) : Input
    }

    private val inputs = Channel<Input>(Channel.UNLIMITED)
    private val retrySignal = Channel<Unit>(Channel.CONFLATED)

    /** On this phone, subagents' session files are readable: their messages stream into [SessionState.subagents]. */
    private val subagentTail: SubagentTail? =
        if (origin is Origin.Local) SubagentTail(state, { sessionFile }) { inputs.trySend(Input.Event(it)) } else null

    /** True when a subagent's own messages can be shown (a session on this phone); remote control sends only progress. */
    val readsSubagentFiles: Boolean get() = subagentTail != null

    @Volatile
    var stopped = false
        private set

    private var usedFallback = false

    /** Perf log (tag [PERF_TAG]): when this controller started, and when the last prompt was sent (0: none waiting). */
    private val createdAt = SystemClock.elapsedRealtime()

    @Volatile
    private var sentAt = 0L
    private var readyLogged = false

    fun start() {
        scope.launch { reduceLoop() }
        scope.launch { connectLoop() }
        pumpEvents()
        subagentTail?.start(scope)
        checkpoints?.let {
            it.onNotice = { message -> _effects.tryEmit(SessionEffect.Notify(message, "warning")) }
            it.start()
        }
    }

    /** Keep reading subagent [key]'s session file while its detail screen is open, also after it finished. */
    fun watchSubagent(key: String, on: Boolean) {
        subagentTail?.watch(key, on)
    }

    /**
     * Ends this session. A local engine that is idle is kept for a later open of the same session unless
     * [keepEngine] is false (memory pressure).
     */
    fun stop(keepEngine: Boolean = true) {
        if (stopped) return
        stopped = true
        checkpoints?.close()
        app.sessionStopped(this)
        when (origin) {
            is Origin.Remote -> {
                backend.close()
                rpc.stop()
                scope.cancel()
            }
            is Origin.Local -> {
                // An idle engine can serve the next open of this project (the engine decides); stop reading it first.
                // Not idle while the subagents extension runs background agents (its "subagents" status): a parked
                // engine would keep them running unseen, and their completion would start a new turn of a closed session.
                val idle = isIdle && keepEngine
                rpc.stop()
                scope.cancel()
                origin.release(origin.backend, idle)
            }
        }
    }

    /** A turn, a compaction, an open dialog or background subagents: never stopped to make room or free memory. */
    val isBusy: Boolean
        get() = _state.value.let { it.isRunning || it.isCompacting || it.dialogs.isNotEmpty() || SUBAGENTS_STATUS in it.statuses }

    /** Connected, and nothing runs: no turn, compaction, open dialog or background subagent. */
    val isIdle: Boolean
        get() = _state.value.let { !it.isRunning && !it.isCompacting && it.dialogs.isEmpty() && SUBAGENTS_STATUS !in it.statuses } &&
            _link.value is LinkState.Connected

    fun retryNow() {
        retrySignal.trySend(Unit)
    }

    private fun newBackend(endpoints: List<String>, secret: ByteArray?) = RemoteBackend(
        RemoteConfig(
            hostKey = Base64Url.decode(hostId),
            endpoints = endpoints,
            identity = app.identity.keyPair(),
            deviceName = app.deviceName,
            appVersion = app.appVersion,
            pairSecret = secret,
        ),
        client = app.http,
    )

    private fun pumpEvents() {
        val client = rpc
        scope.launch { client.events.collect { inputs.send(Input.Event(it)) } }
    }

    // Connection

    private suspend fun connectLoop() {
        var backoff = 1_000L
        while (scope.isActive && !stopped) {
            _link.value = LinkState.Connecting
            if (backend.connect()) {
                backoff = 1_000L
                val connected = backend.state.value as? ConnectionState.Connected
                onConnected(connected)
                val end = backend.state.first { it is ConnectionState.Disconnected } as ConnectionState.Disconnected
                if (stopped) return
                if (end.reason.retryable || end.reason == DisconnectReason.HostStopped) {
                    // Reconnect at once after a drop; back off only if that fails.
                    _link.value = LinkState.Retrying(end.reason, end.detail, 0)
                    continue
                }
                fail(end)
                continue
            }
            val end = backend.state.value as? ConnectionState.Disconnected ?: continue
            if (end.reason == DisconnectReason.Unauthorized && fallbackSecret != null && !usedFallback) {
                usedFallback = true
                swapBackend(fallbackSecret)
                continue
            }
            if (!end.reason.retryable) {
                fail(end)
                continue
            }
            _link.value = LinkState.Retrying(end.reason, end.detail, backoff)
            withTimeoutOrNull(backoff) { retrySignal.receive() }
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    private suspend fun fail(end: ConnectionState.Disconnected) {
        _link.value = LinkState.Failed(end.reason, explain(end))
        retrySignal.receive()
    }

    private fun swapBackend(secret: ByteArray) {
        val endpoints = (backend as RemoteBackend).config.endpoints
        rpc.stop()
        backend = newBackend(endpoints, secret)
        rpc = RpcClient(backend, scope)
        pumpEvents()
    }

    private fun explain(end: ConnectionState.Disconnected): String = if (isLocal) {
        when (end.reason) {
            DisconnectReason.EngineFailed -> end.detail ?: "The engine could not start."
            DisconnectReason.Closed -> "Stopped."
            else -> end.detail ?: "The engine stopped."
        }
    } else {
        explainRemote(end)
    }

    private fun explainRemote(end: ConnectionState.Disconnected): String = when (end.reason) {
        DisconnectReason.Unauthorized -> if (pairing || usedFallback) {
            "The computer refused this pairing code. Codes work once and expire after 10 minutes. Run /remote-control for a new one."
        } else {
            "This phone is no longer paired with ${_hostName.value}. Scan a new code from /remote-control to pair again."
        }
        DisconnectReason.HostStopped -> "Remote control stopped on ${_hostName.value}."
        DisconnectReason.Malformed -> "The computer could not read this app's messages. Update Pi on the computer, or this app."
        DisconnectReason.RateLimited -> "Too many failed attempts from this network. Wait a minute and retry."
        DisconnectReason.Unreachable -> "Can't reach ${_hostName.value}. Check that /remote-control is running and the phone is on the same network."
        DisconnectReason.HandshakeTimeout -> "The computer took too long to answer."
        DisconnectReason.Lost -> "The connection dropped."
        DisconnectReason.Closed -> "Disconnected."
        DisconnectReason.EngineFailed -> end.detail ?: "The engine could not start."
    }

    private fun onConnected(connected: ConnectionState.Connected?) {
        connected ?: return
        _link.value = LinkState.Connected(connected.endpoint)
        val remote = backend as? RemoteBackend ?: return
        val name = connected.host.name.ifBlank { _hostName.value }
        _hostName.value = name
        val now = System.currentTimeMillis()
        val existing = app.hosts.get(hostId)
        app.hosts.upsert(
            (existing ?: Host(id = hostId, name = name, endpoints = remote.config.endpoints, pairedAt = now)).copy(
                name = name,
                endpoints = remote.config.endpoints,
                version = connected.host.version.ifBlank { existing?.version.orEmpty() },
                lastConnectedAt = now,
            ),
        )
    }

    // State

    private suspend fun reduceLoop() {
        var s = _state.value
        var lastPublish = 0L
        val batch = ArrayList<Input>()
        while (scope.isActive) {
            batch += inputs.receive()
            while (true) batch += inputs.tryReceive().getOrNull() ?: break
            // Publish at most once per frame: a burst of deltas becomes one recomposition.
            val wait = FRAME_MS - (SystemClock.uptimeMillis() - lastPublish)
            if (wait > 0) {
                delay(wait)
                while (true) batch += inputs.tryReceive().getOrNull() ?: break
            }
            s = applyBatch(s, batch)
            batch.clear()
            val previous = _state.value
            _state.value = s
            lastPublish = SystemClock.uptimeMillis()
            afterPublish(previous, s)
        }
    }

    /** Apply a frame's inputs, with consecutive token deltas of one block merged into one event first. */
    private fun applyBatch(state: SessionState, batch: List<Input>): SessionState {
        var s = state
        var pending: JsonObject? = null
        for (input in batch) {
            if (input is Input.Event) {
                val merged = pending?.let { SessionReducer.mergeDeltas(it, input.json) }
                if (merged != null) {
                    pending = merged
                    continue
                }
                pending?.let { s = apply(s, Input.Event(it)) }
                pending = input.json
                continue
            }
            pending?.let { s = apply(s, Input.Event(it)) }
            pending = null
            s = apply(s, input)
        }
        pending?.let { s = apply(s, Input.Event(it)) }
        return s
    }

    /**
     * One input, never fatal: an event the reducer cannot apply (a host sending nonsense) is dropped, keeping the
     * state. An exception here would end reduceLoop's coroutine and the app with it.
     */
    private fun apply(state: SessionState, input: Input): SessionState = try {
        when (input) {
            is Input.Mutate -> input.change(state)
            is Input.Event -> {
                val event = input.json
                sideEffects(state, event)
                SessionReducer.reduce(state, event)
            }
        }
    } catch (e: RuntimeException) {
        Log.w("PiBolt", "dropped an event the session could not apply", e)
        state
    }

    private fun sideEffects(before: SessionState, event: JsonObject) {
        checkpoints?.onEvent(event, before.sessionId)
        when (event.type) {
            "extension_ui_request" -> when (event.str("method")) {
                "notify" -> _effects.tryEmit(SessionEffect.Notify(event.str("message").orEmpty(), event.str("notifyType") ?: "info"))
                "set_editor_text" -> _effects.tryEmit(SessionEffect.EditorText(event.str("text").orEmpty()))
                "select", "confirm", "input", "editor" -> if (!app.isForeground) {
                    SessionReducer.dialogFrom(event)?.let { Notifications.needsAnswer(app, hostId, project?.name, _hostName.value, it) }
                }
            }
            "agent_settled" -> {
                if (!app.isForeground && before.isRunning) {
                    val last = (before.allItems.lastOrNull { it is AssistantTextItem } as? AssistantTextItem)?.text.orEmpty()
                    Notifications.settled(app, hostId, project?.name, _hostName.value, last, aborted = event.bool("aborted") == true)
                }
                scope.launch { refreshState() }
            }
            "remote_hello" -> {
                val host = (event["host"] as? JsonObject)
                if (!isLocal) app.hosts.update(hostId) { it.copy(cwd = host?.str("cwd") ?: it.cwd, version = host?.str("version") ?: it.version) }
                _models.value = emptyList()
                _thinkingLevels.value = emptyList()
            }
        }
    }

    private fun afterPublish(previous: SessionState, now: SessionState) {
        if (!readyLogged && now.hello) {
            readyLogged = true
            Log.i(PERF_TAG, "session ready ${SystemClock.elapsedRealtime() - createdAt} ms (${now.items.size} items)")
        }
        val sent = sentAt
        if (sent != 0L && now.streaming.isNotEmpty()) {
            sentAt = 0L
            Log.i(PERF_TAG, "first token ${SystemClock.elapsedRealtime() - sent} ms")
        }
        val needsService = now.isRunning || now.dialogs.isNotEmpty()
        if (needsService && !(previous.isRunning || previous.dialogs.isNotEmpty())) SessionService.ensureRunning(app)
        if (previous.dialogs.isNotEmpty() && now.dialogs.isEmpty()) Notifications.clearNeedsAnswer(app, hostId)
    }

    private fun mutate(change: (SessionState) -> SessionState) {
        inputs.trySend(Input.Mutate(change))
    }

    private suspend fun refreshState() {
        val response = rpc.getState()
        if (response.success) mutate { SessionReducer.applyRpcState(it, response.dataObject) }
    }

    // Commands

    /** Send a prompt. While the agent runs, [behavior] says whether it steers or waits as a follow-up. */
    fun send(text: String, behavior: StreamingBehavior, images: List<ImageAttachment> = emptyList()) {
        val running = _state.value.isRunning
        sentAt = SystemClock.elapsedRealtime()
        scope.launch {
            // The turn's start state is recorded before the agent can edit (a steer joins a running turn instead).
            if (!running) checkpoints?.prepareStart()
            // An extension command answers only when it finishes, after its dialogs: nothing waits on this.
            val response = rpc.prompt(text, images, streamingBehavior = if (running) behavior else null)
            if (!response.success && !stopped) {
                _effects.tryEmit(SessionEffect.EditorText(text))
                _effects.tryEmit(SessionEffect.Error(response.error ?: "Not sent"))
            }
        }
    }

    fun abort() {
        scope.launch {
            val response = rpc.abort()
            if (!response.success) _effects.tryEmit(SessionEffect.Error(response.error ?: "Abort failed"))
        }
    }

    fun clearQueue() {
        scope.launch {
            val response = rpc.clearQueue()
            if (response.success) {
                val (steering, followUp) = response.clearedQueue()
                val text = (steering + followUp).joinToString("\n\n")
                if (text.isNotBlank()) _effects.tryEmit(SessionEffect.EditorText(text))
            } else {
                _effects.tryEmit(SessionEffect.Error(response.error ?: "Could not clear the queue"))
            }
        }
    }

    fun compact(instructions: String?) {
        scope.launch {
            val response = rpc.compact(instructions)
            if (!response.success) _effects.tryEmit(SessionEffect.Error(response.error ?: "Compaction failed"))
        }
    }

    fun rename(name: String) {
        scope.launch {
            val response = rpc.setSessionName(name)
            if (response.success) mutate { it.copy(sessionName = name.ifBlank { null }) }
            else _effects.tryEmit(SessionEffect.Error(response.error ?: "Rename failed"))
        }
    }

    fun loadModels() {
        scope.launch {
            val response = rpc.getAvailableModels()
            if (response.success) _models.value = ModelInfo.listFrom(response)
            else _effects.tryEmit(SessionEffect.Error(response.error ?: "Could not list models"))
        }
    }

    fun setModel(model: ModelInfo) {
        scope.launch {
            val response = rpc.setModel(model.provider, model.id)
            if (response.success) {
                val chosen = ModelInfo.from(response.dataObject) ?: model
                mutate { it.copy(model = chosen) }
                _thinkingLevels.value = emptyList()
                refreshState()
            } else {
                _effects.tryEmit(SessionEffect.Error(response.error ?: "Could not switch model"))
            }
        }
    }

    fun loadThinkingLevels() {
        scope.launch {
            val response = rpc.getAvailableThinkingLevels()
            if (response.success) _thinkingLevels.value = response.thinkingLevels()
        }
    }

    fun setThinkingLevel(level: String) {
        scope.launch {
            val response = rpc.setThinkingLevel(level)
            if (response.success) mutate { it.copy(thinkingLevel = level) }
            else _effects.tryEmit(SessionEffect.Error(response.error ?: "Could not change thinking level"))
        }
    }

    fun refreshCommands() {
        scope.launch {
            val response = rpc.getCommands()
            if (response.success) {
                val commands = SessionReducer.commandsFrom(response.dataObject)
                mutate { it.copy(commands = commands) }
            }
        }
    }

    // Sessions and shell. A local engine runs all of these; remote control answers "Not available over remote control".
    // A session switch replaces the conversation through a new `remote_hello`, as on a computer.

    fun newSession() = sessionCommand("Could not start a new session") { rpc.newSession() }

    /** Open a saved session ([opensec.pibolt.app.engine.EngineApi.sessions]) in this engine. */
    fun switchSession(sessionFile: String) = sessionCommand("Could not open that session") { rpc.switchSession(sessionFile) }

    /** Duplicate the current session (same history, new file). */
    fun cloneSession() = sessionCommand("Could not clone the session") { rpc.clone() }

    /**
     * Fork from the user message [entryId] (from [forkPoints]) into a new session holding the conversation before it.
     * The engine then sends the new session's snapshot, which replaces the transcript. Returns the message's text for
     * the composer.
     */
    suspend fun fork(entryId: String): Result<String> {
        val response = rpc.fork(entryId)
        if (!response.success) return Result.failure(IllegalStateException(response.error ?: "Fork failed"))
        val data = response.dataObject
        if ((data?.get("cancelled") as? JsonPrimitive)?.content == "true") return Result.failure(IllegalStateException("An extension cancelled the fork"))
        return Result.success(data?.str("text").orEmpty())
    }

    /** [fork] from the [occurrence]-th user message (counting from 0) whose text is [text], as the transcript shows it. */
    suspend fun forkFrom(text: String, occurrence: Int): Result<String> {
        val point = forkPoints().filter { it.text.trim() == text.trim() }.getOrNull(occurrence)
            ?: return Result.failure(IllegalStateException("That message can't be forked"))
        return fork(point.entryId).map { it.ifEmpty { point.text } }
    }

    suspend fun forkPoints(): List<ForkPoint> {
        val response = rpc.getForkMessages()
        return response.dataObject?.arr("messages").orEmpty().mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            ForkPoint(o.str("entryId") ?: return@mapNotNull null, o.str("text").orEmpty())
        }
    }

    /** Run [command] in the project's shell; its output becomes context for the next prompt unless [excludeFromContext]. */
    suspend fun runBash(command: String, excludeFromContext: Boolean = false): RpcResponse = rpc.bash(command, excludeFromContext)

    fun abortBash() {
        scope.launch { rpc.abortBash() }
    }

    // Model and thinking cycling, stats, export, the shell and engine settings.

    /** Next model (`cycle_model`, the terminal's Ctrl+P). Returns its label, or null when there is no other model. */
    suspend fun cycleModel(): Result<String?> {
        val response = rpc.request("cycle_model")
        if (!response.success) return Result.failure(IllegalStateException(response.error ?: "Could not switch model"))
        val data = response.dataObject ?: return Result.success(null)
        val model = ModelInfo.from(data["model"] as? JsonObject)
        val level = data.str("thinkingLevel")
        mutate { it.copy(model = model ?: it.model, thinkingLevel = level ?: it.thinkingLevel) }
        _thinkingLevels.value = emptyList()
        return Result.success(model?.label)
    }

    /** Next thinking level (`cycle_thinking_level`, Shift+Tab). Null when the model does not reason. */
    suspend fun cycleThinking(): Result<String?> {
        val response = rpc.request("cycle_thinking_level")
        if (!response.success) return Result.failure(IllegalStateException(response.error ?: "Could not change thinking level"))
        val level = response.dataObject?.str("level") ?: return Result.success(null)
        mutate { it.copy(thinkingLevel = level) }
        return Result.success(level)
    }

    /** The whole session's tokens, cost and context use (`get_session_stats`). */
    suspend fun sessionStats(): Result<SessionStats> {
        val response = rpc.getSessionStats()
        val stats = SessionStats.from(response.dataObject)
        return if (response.success && stats != null) Result.success(stats) else Result.failure(IllegalStateException(response.error ?: "No statistics"))
    }

    /** `export_html` to [path] (on this phone's engine). Returns the written file's path. */
    suspend fun exportHtml(path: String): Result<String> {
        val response = rpc.exportHtml(path)
        val written = response.dataObject?.str("path")
        return if (response.success && written != null) Result.success(written) else Result.failure(IllegalStateException(response.error ?: "Export failed"))
    }

    /**
     * Runs [shell] in the project like the terminal's `!` (`!!` keeps the output out of the model's context). The run
     * shows at once as a step; its output streams in (`bash_execution_update`) and the result replaces it.
     */
    fun runShell(shell: ShellCommand) {
        val key = SessionReducer.SHELL_PREFIX + System.currentTimeMillis()
        val args = JsonObject(mapOf("command" to JsonPrimitive(shell.command)))
        mutate { it.copy(items = it.items + ToolItem(key, key, "bash", args, status = ToolStatus.Running)) }
        _shellRuns.update { it + 1 }
        scope.launch {
            val response = try {
                rpc.bash(shell.command, shell.excludeFromContext)
            } finally {
                _shellRuns.update { it - 1 }
            }
            val data = response.dataObject
            val exit = data?.let { (it["exitCode"] as? JsonPrimitive)?.content?.toIntOrNull() }
            val cancelled = data?.bool("cancelled") == true
            val output = data?.str("output").orEmpty().trimEnd()
            val status = when {
                !response.success -> ToolStatus.Error
                cancelled -> ToolStatus.Cancelled
                exit != null && exit != 0 -> ToolStatus.Error
                else -> ToolStatus.Done
            }
            val result = buildString {
                append(if (response.success) output else response.error ?: "The command did not run")
                if (exit != null && exit != 0) append("\n\nCommand exited with code ").append(exit)
                if (data?.bool("truncated") == true) data.str("fullOutputPath")?.let { append("\n\n(output truncated; full output in ").append(it).append(')') }
            }
            mutate { s -> s.copy(items = s.items.map { if (it is ToolItem && it.key == key) it.copy(status = status, result = result) else it }) }
        }
    }

    /** Applies the engine settings to this session at once (on-device only: remote control refuses them). */
    fun applyAgentDefaults(defaults: AgentDefaults) {
        if (!isLocal) return
        scope.launch {
            rpc.request("set_steering_mode") { put("mode", defaults.steeringMode.wire) }
            rpc.request("set_follow_up_mode") { put("mode", defaults.followUpMode.wire) }
            rpc.request("set_auto_compaction") { put("enabled", defaults.autoCompaction) }
            rpc.request("set_auto_retry") { put("enabled", defaults.autoRetry) }
        }
    }

    /**
     * Reads the session again (state, messages, commands) and replaces the transcript with it, as a session switch does:
     * for changes RPC does not announce, such as moving the leaf of the session tree. Open dialogs are kept.
     */
    suspend fun resync(): Boolean {
        val state = rpc.getState()
        val messages = rpc.getMessages()
        val commands = rpc.getCommands()
        if (!state.success || !messages.success) return false
        val host = _state.value.host
        val hello = buildJsonObject {
            put("type", "remote_hello")
            put("protocol", 1)
            put("host", buildJsonObject {
                put("name", host?.name.orEmpty())
                put("version", host?.version.orEmpty())
                put("cwd", host?.cwd.orEmpty())
            })
            put("state", state.dataObject ?: JsonObject(emptyMap()))
            put("messages", messages.dataObject?.arr("messages") ?: JsonArray(emptyList()))
            put("commands", commands.dataObject?.arr("commands") ?: JsonArray(emptyList()))
        }
        val dialogs = _state.value.dialogs
        inputs.send(Input.Event(hello))
        if (dialogs.isNotEmpty()) mutate { it.copy(dialogs = dialogs) }
        return true
    }

    /** Any other RPC command (packages/coding-agent/docs/rpc-commands.md). */
    suspend fun request(type: String, timeoutMs: Long = 30_000, fields: JsonObjectBuilder.() -> Unit = {}): RpcResponse =
        rpc.request(type, timeoutMs, fields)

    private fun sessionCommand(failure: String, run: suspend () -> RpcResponse) {
        scope.launch {
            val response = run()
            when {
                !response.success -> _effects.tryEmit(SessionEffect.Error(response.error ?: failure))
                (response.dataObject?.get("cancelled") as? JsonPrimitive)?.content == "true" ->
                    _effects.tryEmit(SessionEffect.Notify("Cancelled by an extension", "info"))
            }
        }
    }

    fun answer(id: String, value: String) = answered(id, rpc.respondValue(id, value))

    fun confirm(id: String, confirmed: Boolean) = answered(id, rpc.respondConfirm(id, confirmed))

    fun cancelDialog(id: String) = answered(id, rpc.respondCancel(id))

    private fun answered(id: String, sent: Boolean) {
        if (sent) mutate { s -> s.copy(dialogs = s.dialogs.filterNot { it.id == id }) }
        else _effects.tryEmit(SessionEffect.Error("Not connected"))
    }

    companion object {
        /** Status key of the bundled subagents extension, set while background agents run or wait. */
        private const val SUBAGENTS_STATUS = "subagents"
        private const val FRAME_MS = 16L
        const val PERF_TAG = "PiBoltPerf"

        /** A session with the engine on this phone, in [project]. [release] takes the backend back when it stops. */
        fun local(app: PiBoltApp, project: File, backend: LocalBackend, release: (LocalBackend, Boolean) -> Unit, checkpoints: TurnCheckpoints? = null) =
            SessionController(app, localId(project), project.name, Origin.Local(project, backend, release, checkpoints))

        /** [hostId] of a local session: per project, so drafts and recent prompts stay with it. */
        fun localId(project: File): String = "local:${project.absolutePath}"
    }
}
