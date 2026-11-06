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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.data.Host
import opensec.pibolt.protocol.Base64Url
import opensec.pibolt.protocol.backend.ConnectionState
import opensec.pibolt.protocol.backend.DisconnectReason
import opensec.pibolt.protocol.backend.RemoteBackend
import opensec.pibolt.protocol.backend.RemoteConfig
import opensec.pibolt.protocol.rpc.ImageAttachment
import opensec.pibolt.protocol.rpc.ModelInfo
import opensec.pibolt.protocol.rpc.RpcClient
import opensec.pibolt.protocol.rpc.StreamingBehavior
import opensec.pibolt.protocol.rpc.clearedQueue
import opensec.pibolt.protocol.rpc.thinkingLevels
import opensec.pibolt.protocol.session.AssistantTextItem
import opensec.pibolt.protocol.session.SessionReducer
import opensec.pibolt.protocol.session.SessionState
import opensec.pibolt.protocol.bool
import opensec.pibolt.protocol.str
import opensec.pibolt.protocol.type

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

/**
 * One live session with one computer: connection with automatic reconnect, the reduced [SessionState] (published at
 * most once per frame), commands, and side effects for the UI.
 */
class SessionController(
    private val app: PiBoltApp,
    val hostId: String,
    hostName: String,
    endpoints: List<String>,
    /** Present while pairing: sent in the first handshake only. */
    private val pairSecret: ByteArray?,
    /** A known host scanned again: tried only if the host refuses this phone without it. */
    private val fallbackSecret: ByteArray? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val pairing: Boolean = pairSecret != null

    private val _hostName = MutableStateFlow(hostName)
    val hostName: StateFlow<String> = _hostName.asStateFlow()

    private var backend = newBackend(endpoints, pairSecret)
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

    private sealed interface Input {
        class Event(val json: JsonObject) : Input

        class Mutate(val change: (SessionState) -> SessionState) : Input
    }

    private val inputs = Channel<Input>(Channel.UNLIMITED)
    private val retrySignal = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    var stopped = false
        private set

    private var usedFallback = false

    fun start() {
        scope.launch { reduceLoop() }
        scope.launch { connectLoop() }
        pumpEvents()
    }

    fun stop() {
        stopped = true
        backend.close()
        rpc.stop()
        scope.cancel()
    }

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
        val endpoints = backend.config.endpoints
        rpc.stop()
        backend = newBackend(endpoints, secret)
        rpc = RpcClient(backend, scope)
        pumpEvents()
    }

    private fun explain(end: ConnectionState.Disconnected): String = when (end.reason) {
        DisconnectReason.Unauthorized -> if (pairing || usedFallback) {
            "The computer refused this pairing code. It is used once and expires after 10 minutes: run /remote-control for a new one."
        } else {
            "This phone is no longer paired with ${_hostName.value}. Scan a new code from /remote-control to pair again."
        }
        DisconnectReason.HostStopped -> "Remote control stopped on ${_hostName.value}."
        DisconnectReason.Malformed -> "The computer could not read this app's messages. Update Pi-Bolt or the app."
        DisconnectReason.RateLimited -> "Too many failed attempts from this network. Wait a minute and retry."
        DisconnectReason.Unreachable -> "Can't reach ${_hostName.value}. Is /remote-control running, and is the phone on the same network (or a tunnel on)?"
        DisconnectReason.HandshakeTimeout -> "The computer took too long to answer."
        DisconnectReason.Lost -> "The connection dropped."
        DisconnectReason.Closed -> "Disconnected."
    }

    private fun onConnected(connected: ConnectionState.Connected?) {
        connected ?: return
        _link.value = LinkState.Connected(connected.endpoint)
        val name = connected.host.name.ifBlank { _hostName.value }
        _hostName.value = name
        val now = System.currentTimeMillis()
        val existing = app.hosts.get(hostId)
        app.hosts.upsert(
            (existing ?: Host(id = hostId, name = name, endpoints = backend.config.endpoints, pairedAt = now)).copy(
                name = name,
                endpoints = backend.config.endpoints,
                version = connected.host.version.ifBlank { existing?.version.orEmpty() },
                lastConnectedAt = now,
            ),
        )
    }

    // State

    private suspend fun reduceLoop() {
        var s = _state.value
        var lastPublish = 0L
        while (scope.isActive) {
            s = apply(s, inputs.receive())
            while (true) s = apply(s, inputs.tryReceive().getOrNull() ?: break)
            // Publish at most once per frame: a burst of deltas becomes one recomposition.
            val wait = FRAME_MS - (SystemClock.uptimeMillis() - lastPublish)
            if (wait > 0) {
                delay(wait)
                while (true) s = apply(s, inputs.tryReceive().getOrNull() ?: break)
            }
            val previous = _state.value
            _state.value = s
            lastPublish = SystemClock.uptimeMillis()
            afterPublish(previous, s)
        }
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
        when (event.type) {
            "extension_ui_request" -> when (event.str("method")) {
                "notify" -> _effects.tryEmit(SessionEffect.Notify(event.str("message").orEmpty(), event.str("notifyType") ?: "info"))
                "set_editor_text" -> _effects.tryEmit(SessionEffect.EditorText(event.str("text").orEmpty()))
                "select", "confirm", "input", "editor" -> if (!app.isForeground) {
                    Notifications.needsAnswer(app, _hostName.value, event.str("title").orEmpty())
                }
            }
            "agent_settled" -> {
                if (!app.isForeground && before.isRunning) {
                    val last = (before.allItems.lastOrNull { it is AssistantTextItem } as? AssistantTextItem)?.text.orEmpty()
                    Notifications.settled(app, _hostName.value, last, aborted = event.bool("aborted") == true)
                }
                scope.launch { refreshState() }
            }
            "remote_hello" -> {
                val host = (event["host"] as? JsonObject)
                app.hosts.update(hostId) { it.copy(cwd = host?.str("cwd") ?: it.cwd, version = host?.str("version") ?: it.version) }
                _models.value = emptyList()
                _thinkingLevels.value = emptyList()
            }
        }
    }

    private fun afterPublish(previous: SessionState, now: SessionState) {
        val needsService = now.isRunning || now.dialogs.isNotEmpty()
        if (needsService && !(previous.isRunning || previous.dialogs.isNotEmpty())) SessionService.ensureRunning(app)
        if (previous.dialogs.isNotEmpty() && now.dialogs.isEmpty()) Notifications.clearNeedsAnswer(app)
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
        scope.launch {
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

    fun answer(id: String, value: String) = answered(id, rpc.respondValue(id, value))

    fun confirm(id: String, confirmed: Boolean) = answered(id, rpc.respondConfirm(id, confirmed))

    fun cancelDialog(id: String) = answered(id, rpc.respondCancel(id))

    private fun answered(id: String, sent: Boolean) {
        if (sent) mutate { s -> s.copy(dialogs = s.dialogs.filterNot { it.id == id }) }
        else _effects.tryEmit(SessionEffect.Error("Not connected"))
    }

    private companion object {
        const val FRAME_MS = 16L
    }
}
