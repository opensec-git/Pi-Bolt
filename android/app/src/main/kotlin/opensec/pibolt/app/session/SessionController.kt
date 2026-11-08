package opensec.pibolt.app.session

import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.checkpoints.TurnCheckpoints
import opensec.pibolt.app.cost.CapCard
import opensec.pibolt.app.cost.Cost
import opensec.pibolt.app.cost.SessionSpending
import opensec.pibolt.app.cost.SpendCaps
import opensec.pibolt.app.data.Host
import opensec.pibolt.app.engine.ChatSpace
import opensec.pibolt.app.engine.LivePolicy
import opensec.pibolt.protocol.Base64Url
import opensec.pibolt.protocol.backend.ConnectionState
import opensec.pibolt.protocol.backend.DisconnectReason
import opensec.pibolt.protocol.backend.LocalBackend
import opensec.pibolt.protocol.backend.RemoteBackend
import opensec.pibolt.protocol.backend.RemoteConfig
import opensec.pibolt.protocol.backend.SessionBackend
import opensec.pibolt.protocol.rpc.ImageAttachment
import opensec.pibolt.protocol.RemoteEndpoints
import opensec.pibolt.protocol.rpc.ModelInfo
import opensec.pibolt.protocol.rpc.RemoteProject
import opensec.pibolt.protocol.rpc.RemoteSessionInfo
import opensec.pibolt.protocol.rpc.RpcClient
import opensec.pibolt.protocol.rpc.RpcResponse
import opensec.pibolt.protocol.rpc.StreamingBehavior
import opensec.pibolt.protocol.rpc.clearedQueue
import opensec.pibolt.protocol.rpc.thinkingLevels
import opensec.pibolt.protocol.session.AssistantTextItem
import opensec.pibolt.protocol.session.SUBAGENT_STREAM_FEATURE
import opensec.pibolt.protocol.session.SessionReducer
import opensec.pibolt.protocol.session.VIEWS_FEATURE
import opensec.pibolt.protocol.session.MobileViews
import opensec.pibolt.protocol.session.PageInfo
import opensec.pibolt.protocol.session.SubagentReducer
import opensec.pibolt.app.ui.session.ask.AskReply
import opensec.pibolt.app.ui.session.ask.AskResponder
import opensec.pibolt.app.ui.session.ask.AskSubmission
import opensec.pibolt.protocol.session.SessionState
import opensec.pibolt.protocol.arr
import opensec.pibolt.protocol.bool
import opensec.pibolt.protocol.obj
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

    /**
     * Put text into the composer: an extension's `set_editor_text` replaces the draft; a failed send or a cleared queue
     * ([restore]) gives text back without losing what was typed since, as the terminal does: [into].
     */
    data class EditorText(val text: String, val restore: Boolean = false) : SessionEffect {
        /** The composer's text after this effect, from [draft]. */
        fun into(draft: String): String =
            if (!restore || draft.isBlank() || draft.trim() == text.trim()) text else listOf(text, draft).filter { it.isNotBlank() }.joinToString("\n\n")
    }

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
        class Local(val project: File, val backend: LocalBackend, val release: (LocalBackend, Boolean) -> Unit, val checkpoints: TurnCheckpoints?, val chat: Boolean, val slot: Int) : Origin
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val pairing: Boolean = origin is Origin.Remote && origin.pairSecret != null

    /** Runs on the engine on this phone; every RPC command is available (remote control allows a subset). */
    val isLocal: Boolean = origin is Origin.Local

    /** The engine of a session on this phone (null for a computer's). */
    val localBackend: LocalBackend? get() = (origin as? Origin.Local)?.backend

    /** The project directory of a local session (for a chat: the chats' directory, which is no project). */
    val project: File? = (origin as? Origin.Local)?.project

    /**
     * This session's place among its project's live sessions (0 for the first; another one opens beside a busy one, see
     * [LivePolicy.planOpen]). Part of [hostId], so notifications and their answers reach this very session.
     */
    val slot: Int = (origin as? Origin.Local)?.slot ?: 0

    /** Drafts and per-session memory: per project on this phone (a session keeps them whichever slot shows it), else [hostId]. */
    val memoryKey: String = (origin as? Origin.Local)?.project?.let { localId(it) } ?: hostId

    /** A plain chat (engine.ChatSpace): on this phone, in no project, without tools. */
    val isChat: Boolean = (origin as? Origin.Local)?.chat == true

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

    private val _registeredPages = MutableStateFlow<List<PageInfo>>(emptyList())

    /** The pages packages registered in this session (`get_pages`): the session's menu opens them with [openPage]. */
    val registeredPages: StateFlow<List<PageInfo>> = _registeredPages.asStateFlow()

    private val _effects = MutableSharedFlow<SessionEffect>(extraBufferCapacity = 64)
    val effects: SharedFlow<SessionEffect> = _effects.asSharedFlow()

    private val _models = MutableStateFlow<List<ModelInfo>>(emptyList())
    val models: StateFlow<List<ModelInfo>> = _models.asStateFlow()

    private val _thinkingLevels = MutableStateFlow<List<String>>(emptyList())
    val thinkingLevels: StateFlow<List<String>> = _thinkingLevels.asStateFlow()

    /** User `!` commands ([runShell]) still running. They run outside agent turns, so [SessionState.isRunning] misses them. */
    private val _shellRuns = MutableStateFlow(0)
    val shellRuns: StateFlow<Int> = _shellRuns.asStateFlow()

    /**
     * User actions on their way that start work: a prompt until its turn shows (or the engine refused it), a manual
     * compaction until it ends. [SessionService] counts them as work, so the service starts at the tap, while Android
     * still allows it, and holds the app's network until the work itself keeps it ([KeepAlive]).
     */
    private val _actions = MutableStateFlow(0)
    val actions: StateFlow<Int> = _actions.asStateFlow()

    private sealed interface Input {
        class Event(val json: JsonObject) : Input

        class Mutate(val change: (SessionState) -> SessionState) : Input
    }

    private val inputs = Channel<Input>(Channel.UNLIMITED)

    /** The question card's answers on their way to `ask_user_question`'s dialogs (ui/session/ask). */
    private val ask = AskResponder()
    private val retrySignal = Channel<Unit>(Channel.CONFLATED)

    /** On this phone, subagents' session files are readable: their messages stream into [SessionState.subagents]. */
    private val subagentTail: SubagentTail? =
        if (origin is Origin.Local) SubagentTail(state, { sessionFile }) { inputs.trySend(Input.Event(it)) } else null

    /** This session's cost, its share of the day's and month's spending, and the limits (cost/). */
    private val spending = SessionSpending(app.spend, { app.settings.settings.value.spend }, hostId)

    /** What this session cost so far, its subagents included (on this phone). */
    val cost: StateFlow<Cost> get() = spending.cost

    /** A spending limit held a prompt back or stopped a run: the card the session shows. */
    val capCard: StateFlow<CapCard?> get() = spending.card

    /**
     * Runs whose detail screen is open. The computer (remote control, `subagent_stream`) or this phone's engine streams
     * their own conversation while they are watched (`subagent_subscribe`); subscribed again after every `remote_hello`
     * (a reconnect or another session), since the computer drops a phone's streams with its connection.
     */
    private val watchedSubagents = MutableStateFlow<Set<String>>(emptySet())

    /** A local engine that refused `subagent_subscribe` (before Pi-Bolt 1.1.5): not asked again. */
    @Volatile
    private var engineStreamsSubagents = true

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
        if (origin is Origin.Remote) {
            val cm = connectivity
            networkRegistered = cm != null && runCatching { cm.registerDefaultNetworkCallback(networkCallback) }.isSuccess
        }
        pumpEvents()
        subagentTail?.start(scope)
        checkpoints?.let {
            it.onNotice = { message -> _effects.tryEmit(SessionEffect.Notify(message, "warning")) }
            it.start()
        }
    }

    /**
     * Subagent [key]'s detail screen opened ([on]) or closed: on this phone its session file is read while it is open,
     * also after it finished; the computer (or an engine that can) streams its conversation until it closes.
     */
    fun watchSubagent(key: String, on: Boolean) {
        subagentTail?.watch(key, on)
        val before = watchedSubagents.value
        watchedSubagents.update { if (on) it + key else it - key }
        if ((key in before) == on) return
        if (on) subscribeSubagent(key) else unsubscribeSubagent(key)
    }

    /** The computer's (or engine's) answer to `subagent_subscribe`; [computer]: its `remote_hello` lists the feature. */
    private fun canStreamSubagents(computer: Boolean = _state.value.host?.streamsSubagents == true): Boolean =
        if (isLocal) engineStreamsSubagents else computer

    private fun subscribeSubagent(key: String, computer: Boolean = _state.value.host?.streamsSubagents == true) {
        if (!canStreamSubagents(computer)) return
        val client = rpc
        scope.launch {
            // A call still being written is not a run yet on the computer: asked again a few times while it is watched.
            // An engine without the command says so once. Neither is an error the reader sees: the screen keeps what
            // it has (the file on this phone, the prompt and progress over remote control).
            repeat(SUBSCRIBE_TRIES) {
                val response = client.request("subagent_subscribe", timeoutMs = 15_000) { put("runId", key) }
                if (response.success) return@launch
                if (isLocal && response.error?.startsWith("Unknown command") == true) {
                    engineStreamsSubagents = false
                    return@launch
                }
                if (response.error?.startsWith("No subagent run") != true) return@launch
                delay(SUBSCRIBE_RETRY_MS)
                if (key !in watchedSubagents.value || client !== rpc) return@launch
            }
        }
    }

    private fun unsubscribeSubagent(key: String) {
        if (!canStreamSubagents()) return
        val client = rpc
        scope.launch { client.request("subagent_unsubscribe", timeoutMs = 15_000) { put("runId", key) } }
    }

    /**
     * Ends this session. A local engine that is idle is kept for a later open of the same session unless
     * [keepEngine] is false (memory pressure).
     */
    fun stop(keepEngine: Boolean = true) {
        if (stopped) return
        stopped = true
        checkpoints?.close()
        // Stopped on purpose: a turn it was running did not end by a process death.
        if (isLocal) app.runMarks.unmark(hostId)
        app.sessionStopped(this)
        when (origin) {
            is Origin.Remote -> {
                if (networkRegistered) runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
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

    /**
     * A turn, a compaction, an open dialog, background subagents or jobs, or a `!` command: never stopped to make room
     * or free memory.
     */
    val isBusy: Boolean
        get() = KeepAlive.busy(_state.value) || _shellRuns.value > 0

    /** Connected, and nothing runs: no turn, compaction, open dialog, background subagent or job, or `!` command. */
    val isIdle: Boolean
        get() = !isBusy && _link.value is LinkState.Connected

    /** Work or a user action on its way: [SessionService] must run (see [KeepAlive]). */
    val keepsService: Boolean
        get() = isBusy || _actions.value > 0

    fun retryNow() {
        retrySignal.trySend(Unit)
    }

    /**
     * The app is back in front. A computer's connection dropped while the app was away (Android freezes a backgrounded
     * app without a run, and from Android 14 closes its sockets) and may be waiting out a backoff of up to 30 s: retried
     * now, as on a new network.
     */
    fun onAppForeground() {
        if (_link.value is LinkState.Retrying) retryNow()
    }

    private val connectivity: ConnectivityManager? by lazy { app.getSystemService(ConnectivityManager::class.java) }
    private var networkRegistered = false

    /**
     * A computer's session waiting out its backoff (up to 30 s) retries as soon as a network comes up: back in Wi-Fi
     * range, mobile data after Wi-Fi, out of airplane mode.
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (_link.value is LinkState.Retrying) retryNow()
        }
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
                if (end.reason == DisconnectReason.Handoff) _effects.tryEmit(SessionEffect.Notify(HANDOFF_NOTICE, "info"))
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
        DisconnectReason.Handoff -> HANDOFF_NOTICE
        DisconnectReason.Malformed -> "The computer could not read this app's messages. Update Pi-Bolt or the app."
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
                // On this phone the app reads the subagent's file itself; the engine's stream adds only the message
                // being written (`subagent_events`).
                if (isLocal && event.type == SubagentReducer.ENTRIES_EVENT) state else SessionReducer.reduce(state, event)
            }
        }
    } catch (e: RuntimeException) {
        Log.w("PiBolt", "dropped an event the session could not apply", e)
        state
    }

    private fun sideEffects(before: SessionState, event: JsonObject) {
        checkpoints?.onEvent(event, before.sessionId)
        val spent = spending.onEvent(before, event)
        if (spent.abort) abort()
        spent.warning?.let { _effects.tryEmit(SessionEffect.Notify(it, "warning")) }
        if (spent.snapshot) readCost(event.obj("state")?.str("sessionFile"))
        when (event.type) {
            "extension_ui_request" -> when (event.str("method")) {
                "notify" -> _effects.tryEmit(SessionEffect.Notify(event.str("message").orEmpty(), event.str("notifyType") ?: "info"))
                "set_editor_text" -> _effects.tryEmit(SessionEffect.EditorText(event.str("text").orEmpty()))
                "select", "confirm", "input", "editor", "showView" -> if (!app.isForeground) {
                    SessionReducer.dialogFrom(event)?.let { Notifications.needsAnswer(app, this, it) }
                }
            }
            "agent_settled" -> {
                if (!app.isForeground && before.isRunning) {
                    val last = (before.allItems.lastOrNull { it is AssistantTextItem } as? AssistantTextItem)?.text.orEmpty()
                    val capped = spending.card.value?.takeIf { it.stopped }?.let { SpendCaps.reached(it.kind, it.limit) }
                    Notifications.settled(app, hostId, Notifications.projectOf(project, isChat), _hostName.value, last, aborted = event.bool("aborted") == true, chat = isChat, sessionId = before.sessionId, capped = capped)
                }
                scope.launch { refreshState() }
            }
            "remote_hello" -> {
                // Extensions' views (the mobile UI kit) drawn natively: Pi sends them all again after this, as the
                // snapshot of a rebuilt transcript lacks them. A computer before the kit refuses, and keeps text.
                scope.launch {
                    val features = rpc.setClientFeatures(listOf(VIEWS_FEATURE))
                    // Packages' registered pages, for the session's menu. A host before them refuses `get_pages`.
                    val pages = if (features.success) rpc.getPages().takeIf { it.success }?.let { MobileViews.pages(it.dataObject) } else null
                    _registeredPages.value = pages.orEmpty().filterNot { it.isTile }
                }
                // The computer forgot this phone's subagent streams (a new connection or session): ask again.
                val watched = watchedSubagents.value
                if (watched.isNotEmpty()) {
                    val streams = event.arr("features")?.any { (it as? JsonPrimitive)?.content == SUBAGENT_STREAM_FEATURE } == true
                    watched.forEach { subscribeSubagent(it, streams) }
                }
                val host = (event["host"] as? JsonObject)
                if (!isLocal) {
                    app.hosts.update(hostId) {
                        it.copy(cwd = host?.str("cwd") ?: it.cwd, version = host?.str("version") ?: it.version, service = host?.bool("service") == true)
                    }
                }
                _models.value = emptyList()
                _thinkingLevels.value = emptyList()
                // A reconnect after the run ended or a dialog opened: the "finished" and "needs an answer" alerts of the
                // events the drop swallowed, with the snapshot's own text.
                if (!isLocal) {
                    val missed = SessionReducer.missedWhileAway(before, event)
                    if (missed.isNotEmpty()) {
                        val after = SessionReducer.reduce(before, event).copy(isRunning = true)
                        missed.forEach { sideEffects(after, it) }
                    }
                }
            }
            RemoteEndpoints.TYPE -> onEndpoints(event)
        }
    }

    /** `remote_endpoints`: the computer's addresses now (a quick tunnel's new one). Used for reconnects and saved. */
    private fun onEndpoints(event: JsonObject) {
        val remote = backend as? RemoteBackend ?: return
        val endpoints = RemoteEndpoints.from(event, remote.lastEndpoint) ?: return
        if (endpoints == remote.config.endpoints) return
        remote.updateEndpoints(endpoints)
        app.hosts.update(hostId) { it.copy(endpoints = endpoints) }
    }

    /**
     * Reads the session's own total (its file and its subagents' on this phone, `get_session_stats` from a computer):
     * it has what the snapshot's context does not (compacted turns, summaries, finished subagents). [file]: the session
     * file a snapshot named, else the engine's current one.
     */
    private fun readCost(file: String?) {
        val mark = spending.mark()
        scope.launch {
            val total = if (isLocal) {
                val f = file ?: sessionFile ?: return@launch
                withContext(Dispatchers.IO) { runCatching { app.engine.sessionCost(f) }.getOrNull() }
            } else {
                sessionStats().getOrNull()?.let { Cost(it.cost, it.assistantMessages.toInt()) }
            }
            total?.let { spending.rebase(it, mark) }
        }
    }

    /** "Continue once" on the limit's card: the next run goes to its end. */
    fun continueOnce() = spending.continueOnce()

    fun dismissCapCard() = spending.dismissCard()

    /**
     * False when a spending limit holds prompts back: the card says so, and a prompt sent from a notification while the
     * app is away is answered by one.
     */
    fun spendAllows(): Boolean {
        if (spending.maySend()) return true
        if (!app.isForeground) {
            spending.card.value?.let { Notifications.settled(app, hostId, Notifications.projectOf(project, isChat), _hostName.value, "", aborted = true, chat = isChat, sessionId = _state.value.sessionId, capped = SpendCaps.reached(it.kind, it.limit)) }
        }
        return false
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
        // A turn on this phone is on disk while it runs: a process killed meanwhile says so on the next launch (RunMarks).
        if (isLocal && now.isRunning != previous.isRunning) {
            if (now.isRunning) app.runMarks.mark(RunMarks.Run(hostId, project?.name?.takeUnless { isChat }, _hostName.value, sessionFile, isChat))
            else app.runMarks.unmark(hostId)
        }
        if (KeepAlive.starts(previous, now)) SessionService.ensureRunning(app)
        if (previous.dialogs.isNotEmpty() && now.dialogs.isEmpty()) Notifications.clearNeedsAnswer(app, hostId)
        if (now.dialogs !== previous.dialogs || ask.submission != null) answerAsk(now)
    }

    /** True while [toolCallId]'s card answers are being sent (its dialogs are answered as they come, not shown). */
    fun askSubmitting(toolCallId: String): Boolean = ask.submitting(toolCallId)

    /** Submit (or, with `cancel`, dismiss) the question card: its dialogs are answered from [submission] from now on. */
    fun submitAsk(submission: AskSubmission) {
        ask.submit(submission)
        answerAsk(_state.value)
    }

    private fun answerAsk(state: SessionState) {
        val (id, reply) = ask.next(state) ?: return
        when (reply) {
            is AskReply.Value -> answer(id, reply.value)
            AskReply.Cancel -> cancelDialog(id)
        }
    }

    private fun mutate(change: (SessionState) -> SessionState) {
        inputs.trySend(Input.Mutate(change))
    }

    private suspend fun refreshState() {
        val response = rpc.getState()
        if (response.success) mutate { SessionReducer.applyRpcState(it, response.dataObject) }
    }

    // Commands

    /**
     * Send a prompt. While the agent runs, [behavior] says whether it steers or waits as a follow-up. False when a
     * spending limit held it back ([spendAllows]).
     */
    fun send(text: String, behavior: StreamingBehavior, images: List<ImageAttachment> = emptyList()): Boolean {
        if (!spendAllows()) return false
        sentAt = SystemClock.elapsedRealtime()
        launchHeld {
            // Pi refuses a prompt while a manual compaction runs ("Wait for compaction to finish and retry"); the terminal
            // holds it until the compaction ends, and so does this.
            if (holdsForCompaction(_state.value)) withTimeoutOrNull(COMPACTION_HOLD_MS) { _state.first { !holdsForCompaction(it) } }
            val running = _state.value.isRunning
            // The turn's start state is recorded before the agent can edit (a steer joins a running turn instead).
            if (!running) checkpoints?.prepareStart()
            // An extension command answers only when it finishes, after its dialogs: nothing waits on this.
            val response = rpc.prompt(text, images, streamingBehavior = if (running) behavior else null)
            if (!response.success && !stopped) {
                _effects.tryEmit(SessionEffect.EditorText(text, restore = true))
                _effects.tryEmit(SessionEffect.Error(response.error ?: "Not sent"))
            }
            // The turn's agent_start may still be on its way behind the response: the hold ends once the turn shows.
            if (response.success && KeepAlive.waitsForTurn(response.dataObject?.str("disposition"), _state.value.isRunning)) {
                withTimeoutOrNull(TURN_START_HOLD_MS) { _state.first { it.isRunning } }
            }
        }
        return true
    }

    /**
     * Runs [block] as a user action ([actions]): counted from now, on the caller's (main) thread, and the service started
     * here, before the user can leave. Started atomically, so the count is given back even when the session stops first.
     */
    private fun launchHeld(block: suspend () -> Unit) {
        _actions.update { it + 1 }
        SessionService.ensureRunning(app)
        scope.launch(start = CoroutineStart.ATOMIC) {
            try {
                block()
            } finally {
                _actions.update { it - 1 }
            }
        }
    }

    /** Shows [message] as an error on this session's screen (an open beside it that failed). */
    fun showError(message: String) {
        _effects.tryEmit(SessionEffect.Error(message))
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
                if (text.isNotBlank()) _effects.tryEmit(SessionEffect.EditorText(text, restore = true))
            } else {
                _effects.tryEmit(SessionEffect.Error(response.error ?: "Could not clear the queue"))
            }
        }
    }

    fun compact(instructions: String?) {
        launchHeld {
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
            if (response.success) {
                mutate { it.copy(thinkingLevel = level) }
                // Pi clamps a level the model lacks ("/thinking xhigh" on a model up to high): show the level it set.
                refreshState()
            } else _effects.tryEmit(SessionEffect.Error(response.error ?: "Could not change thinking level"))
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
    // A session switch replaces the conversation through a new `remote_hello`, as on a computer. Pi aborts the turn of
    // the session it replaces, so these are refused while this one is busy ([switchRefusal]): on this phone another
    // session opens beside it instead (LocalEngine), and a computer's service with `parallelSessions` keeps it running.

    /** Why this session may not move to another one now, or null ([LivePolicy.switchRefusal]). */
    fun switchRefusal(): String? = LivePolicy.switchRefusal(isLocal, isBusy, _state.value.host?.parallelSessions == true)

    fun newSession() = sessionCommand("Could not start a new session") { rpc.newSession() }

    // The background service (`host.service`) lists the computer's projects and sessions, and opens any of them.

    suspend fun listProjects(): Result<List<RemoteProject>> {
        val response = rpc.listProjects()
        return if (response.success) Result.success(RemoteProject.listFrom(response)) else Result.failure(IllegalStateException(response.error ?: "Could not list projects"))
    }

    suspend fun listSessions(cwd: String): Result<List<RemoteSessionInfo>> {
        val response = rpc.listSessions(cwd)
        return if (response.success) Result.success(RemoteSessionInfo.listFrom(response)) else Result.failure(IllegalStateException(response.error ?: "Could not list sessions"))
    }

    /** A new session in the computer's project [cwd] (from [listProjects]). */
    fun newSessionIn(cwd: String) = sessionCommand("Could not start a new session") { rpc.newSession(cwd = cwd) }

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
        switchRefusal()?.let { return Result.failure(IllegalStateException(it)) }
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
    suspend fun runBash(command: String, excludeFromContext: Boolean = false): RpcResponse =
        if (isChat) RpcResponse.failure("bash", NO_COMMANDS) else rpc.bash(command, excludeFromContext)

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
        // A chat runs nothing on the phone: no tools, no shell.
        if (isChat) {
            _effects.tryEmit(SessionEffect.Error(NO_COMMANDS))
            return
        }
        val key = SessionReducer.SHELL_PREFIX + System.currentTimeMillis()
        val args = JsonObject(mapOf("command" to JsonPrimitive(shell.command)))
        mutate { it.copy(items = it.items + ToolItem(key, key, "bash", args, status = ToolStatus.Running)) }
        _shellRuns.update { it + 1 }
        // Not a turn: without this a download (`!npm install`, `!git clone`) lost the network once the user left.
        SessionService.ensureRunning(app)
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
                if (host?.service == true) put("service", true)
                if (host?.parallelSessions == true) put("parallelSessions", true)
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

    /** A command that replaces this session's runtime (new, switch, clone): never sent while it is busy. */
    private fun sessionCommand(failure: String, run: suspend () -> RpcResponse) {
        switchRefusal()?.let { why ->
            Log.i("PiBolt", "refused a session switch while busy ($hostId)")
            _effects.tryEmit(SessionEffect.Error(why))
            return
        }
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

    /** Closes a sheet of the mobile UI kit with its submitted form or pressed action [nodeId] and its field [values]. */
    fun answerView(id: String, nodeId: String, values: JsonObject) = answered(id, rpc.respondView(id, nodeId, values))

    /** Builds the registered page [key]; its view arrives in [state] (slot `page`). False with an error effect when not. */
    suspend fun openPage(key: String): Boolean {
        val response = rpc.openPage(key)
        if (!response.success) _effects.tryEmit(SessionEffect.Error(response.error ?: "Could not open the page"))
        return response.success
    }

    /** The registered page [key] closed on the phone: Pi removes its view and tells the extension. */
    fun closePage(key: String) {
        scope.launch { rpc.closePage(key) }
    }

    /** A view's tap, committed field value or form submit, for the extension (docs/mobile-ui.md#events). */
    fun viewEvent(viewKey: String, nodeId: String, kind: String, value: JsonPrimitive? = null, values: JsonObject? = null) {
        if (!rpc.viewEvent(viewKey, nodeId, kind, value, values)) _effects.tryEmit(SessionEffect.Error("Not connected"))
    }

    private fun answered(id: String, sent: Boolean) {
        if (sent) mutate { s -> s.copy(dialogs = s.dialogs.filterNot { it.id == id }) }
        else _effects.tryEmit(SessionEffect.Error("Not connected"))
    }

    companion object {
        private const val SUBSCRIBE_TRIES = 5
        private const val SUBSCRIBE_RETRY_MS = 1_500L

        /** The longest a sent prompt holds the service waiting for its turn's `agent_start` after Pi accepted it. */
        private const val TURN_START_HOLD_MS = 30_000L
        private const val FRAME_MS = 16L
        const val PERF_TAG = "PiBoltPerf"

        /** Close code 1012: the background service gave the port to `/remote-control` in a terminal on the computer. */
        const val HANDOFF_NOTICE = "The computer's terminal took over. Reconnecting…"

        /** The longest a prompt waits for a manual compaction ([holdsForCompaction]): the compact request's own timeout. */
        private const val COMPACTION_HOLD_MS = 600_000L

        /**
         * A prompt sent now waits for the compaction to end: a manual one (or one whose reason a resync lost). Pi takes a
         * prompt during an automatic compaction (it queues it), but refuses one during `compact`.
         */
        fun holdsForCompaction(state: SessionState): Boolean = state.isCompacting && state.compactionReason != "threshold" && state.compactionReason != "overflow"

        /** Why a chat refuses a shell command. */
        const val NO_COMMANDS = "Chats can't run commands"

        /**
         * A session with the engine on this phone, in [project]. [release] takes the backend back when it stops. A [chat]
         * runs in the chats' directory (engine.ChatSpace), named [opensec.pibolt.app.engine.ChatSpace.NAME].
         */
        fun local(app: PiBoltApp, project: File, backend: LocalBackend, release: (LocalBackend, Boolean) -> Unit, checkpoints: TurnCheckpoints? = null, chat: Boolean = false, slot: Int = 0) =
            SessionController(app, localId(project, slot), if (chat) ChatSpace.NAME else project.name, Origin.Local(project, backend, release, checkpoints, chat, slot))

        /**
         * [hostId] of a local session: per project and [slot] (several sessions of one project may run at once), so
         * notifications reach the right one. The first slot's id is the project's.
         */
        fun localId(project: File, slot: Int = 0): String = "local:${project.absolutePath}" + if (slot > 0) "#$slot" else ""
    }
}
