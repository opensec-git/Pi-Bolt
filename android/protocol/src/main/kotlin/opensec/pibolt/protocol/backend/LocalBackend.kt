package opensec.pibolt.protocol.backend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.arr
import opensec.pibolt.protocol.bool
import opensec.pibolt.protocol.int
import opensec.pibolt.protocol.obj
import opensec.pibolt.protocol.str
import opensec.pibolt.protocol.transport.JsonLines
import opensec.pibolt.protocol.type
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Which session a local engine opens. */
sealed interface SessionTarget {
    /** The project's most recent session (Pi's `--continue`), or a new one when it has none. */
    data object Continue : SessionTarget

    /** A saved session file. */
    data class Resume(val sessionFile: String) : SessionTarget

    /** A new session. */
    data object Fresh : SessionTarget

    /** Pi CLI arguments that open this target when the engine starts (packages/coding-agent/src/cli/args.ts). */
    fun cliArgs(): List<String> = when (this) {
        Continue -> listOf("--continue")
        is Resume -> listOf("--session", sessionFile)
        Fresh -> emptyList()
    }
}

data class LocalConfig(
    /** Shown where a remote session shows the computer's name: the project's name. */
    val name: String,
    /** Engine version, reported as the host version. */
    val version: String,
    /** The project directory the engine runs in. */
    val cwd: String,
    /** Spawn to first answers. A cold start pages in a large binary: give it time. */
    val helloTimeoutMs: Long = 90_000,
    val stderrLines: Int = 300,
    /** This many exits within [crashWindowMs] and the backend stops respawning on its own. */
    val maxQuickExits: Int = 3,
    val crashWindowMs: Long = 60_000,
)

/** Starts an engine process for a target in [cwd]. Runs on [Dispatchers.IO] and may block; throws when it cannot start. */
fun interface EngineLauncher {
    suspend fun launch(target: SessionTarget, cwd: String): Process
}

/**
 * Stops what an engine leaves running. Pi starts tool commands detached (their own process group and session), so
 * killing the engine alone leaves `bash`, `git` or `rg` running; the platform counts each against its limit on child
 * processes of apps.
 */
interface ProcessKiller {
    /** Kill [pid] and every process that descends from it, now. */
    fun killTree(pid: Long)

    /** Kill processes of this app that lost their parent (an engine that ended) and were adopted by init. */
    fun reapOrphans()
}

class EngineStartException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The last lines of the engine's stderr, for the error sheet and the log. */
class StderrTail(private val maxLines: Int) {
    private val lines = ArrayDeque<String>()

    fun add(line: String) {
        val clipped = if (line.length > MAX_LINE) line.take(MAX_LINE) + "…" else line
        synchronized(lines) {
            lines.addLast(clipped)
            while (lines.size > maxLines) lines.removeFirst()
        }
    }

    fun text(lastLines: Int = maxLines): String = synchronized(lines) { lines.toList().takeLast(lastLines).joinToString("\n") }

    private companion object {
        const val MAX_LINE = 2000
    }
}

/**
 * A Pi session in an engine process on this device: RPC JSON lines over the process's stdin/stdout (docs/rpc.md), no
 * Noise. It looks like [RemoteBackend] to [opensec.pibolt.protocol.rpc.RpcClient] and the session reducer: after the
 * engine starts, and again after a command switched the session (`new_session`, `switch_session`, `fork`, `clone`), it
 * asks for `get_state`, `get_messages` and `get_commands` and emits a `remote_hello`-shaped snapshot.
 *
 * Threads per engine: one reads stdout continuously (Pi is never stalled by a slow consumer: records go to an unbounded
 * channel), one reads stderr into [stderr], one writes stdin (so [send] never blocks the caller), one waits for exit.
 *
 * A warm engine: [warmUp] starts the process and prepares the snapshot without publishing it; [connect] later publishes
 * it after moving to [target] if needed. [park] takes the engine back from its consumer so another can [connect] to it.
 * Command ids are prefixed per consumer, so a late answer to a previous consumer never completes a new one's request.
 */
class LocalBackend(
    config: LocalConfig,
    target: SessionTarget,
    private val launcher: EngineLauncher,
    /** The newest saved session file of a project (blocking I/O is fine): "continue" on a warm engine. */
    private val newestSession: (cwd: String) -> String? = { null },
    /**
     * A new, empty session file for a project (just Pi's header line, with that cwd): how a warm engine running
     * elsewhere starts a new session in another project, since `new_session` keeps the engine's current directory.
     */
    private val freshSessionFile: (cwd: String) -> String? = { null },
    private val killer: ProcessKiller? = null,
    private val onStderr: (String) -> Unit = {},
) : SessionBackend {
    /** The project and names this backend reports; [assign] moves a warm engine to another project. */
    @Volatile
    var config: LocalConfig = config
        private set

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val inbox = Channel<JsonObject>(Channel.UNLIMITED)
    override val incoming: Flow<JsonObject> = inbox.receiveAsFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectMutex = Mutex()

    /** What the next [connect] opens. Set it before connecting a warm or parked engine. */
    @Volatile
    var target: SessionTarget = target

    /** Before [connect]ing a warm or parked engine: the project ([LocalConfig.cwd]) and session it should open. */
    fun assign(config: LocalConfig, target: SessionTarget) {
        this.config = config
        this.target = target
    }

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var attempt = 0

    /** Consumer number, part of every forwarded command id. */
    @Volatile
    private var lease = 0

    private val exitTimes = ArrayDeque<Long>()

    val stderr = StderrTail(config.stderrLines)

    /** Spawn to first answer (`get_state`) of the last engine start, in milliseconds. */
    @Volatile
    var lastStartMs: Long? = null
        private set

    /** The session file the engine has open, from the last `get_state`. */
    val sessionFile: String? get() = engine?.sessionFile

    val isAlive: Boolean get() = engine?.alive == true

    /** The directory the engine works in for its current session (a switch to another project's session moves it). */
    val engineCwd: String? get() = engine?.cwd

    /** The engine process id, for diagnostics. */
    val pid: Long? get() = engine?.pid

    /** Starts the engine and prepares its snapshot without publishing it. True when it answered. */
    suspend fun warmUp(): Boolean = connectMutex.withLock {
        if (engine?.alive == true) return true
        try {
            withContext(Dispatchers.IO) { startEngine(target) }.warm = true
            true
        } catch (e: EngineStartException) {
            _state.value = ConnectionState.Disconnected(DisconnectReason.EngineFailed, e.message)
            false
        }
    }

    override suspend fun connect(): Boolean = connectMutex.withLock {
        val previous = _state.value
        if (previous is ConnectionState.Disconnected && previous.reason == DisconnectReason.EngineFailed) {
            synchronized(exitTimes) { exitTimes.clear() }
        }
        val running = engine?.takeIf { it.alive }
        if (running != null && running.delivered) return true
        attempt++
        _state.value = ConnectionState.Connecting(config.cwd, attempt)
        try {
            val e = withContext(Dispatchers.IO) {
                val e = running ?: startEngine(target)
                activate(e)
                e
            }
            _state.value = ConnectionState.Connected(config.cwd, HostHello(config.name, config.version, paired = true))
            // It may have stopped between publishing and here; its exit handler saw it as not yet connected.
            if (!e.alive && engine == null) _state.value = ConnectionState.Disconnected(DisconnectReason.Lost, "The engine stopped")
            true
        } catch (e: EngineStartException) {
            _state.value = ConnectionState.Disconnected(DisconnectReason.EngineFailed, e.message)
            false
        }
    }

    override fun send(message: JsonObject): Boolean {
        val e = engine ?: return false
        if (!e.delivered || !e.alive) return false
        val id = message.str("id")
        val outgoing = if (id != null && message.type != "extension_ui_response") {
            if (message.type in SWITCHES) e.switches[id] = message.str("sessionPath").orEmpty()
            JsonObject(message + ("id" to JsonPrimitive("L$lease.$id")))
        } else {
            message
        }
        if (message.type == "extension_ui_response") message.str("id")?.let(e::dialogClosed)
        return e.enqueue(outgoing)
    }

    /** Take the engine back from its consumer (it stays running) so a later [connect] can hand it to another. */
    fun park() {
        val e = engine ?: return
        e.park()
        lease++
        _state.value = ConnectionState.Idle
    }

    override fun close() {
        val e = engine
        engine = null
        e?.terminate()
        _state.value = ConnectionState.Disconnected(DisconnectReason.Closed)
    }

    /** [close], then wait up to [timeoutMs] for the process to exit. */
    suspend fun closeAndAwait(timeoutMs: Long = 5_000) {
        val e = engine
        close()
        if (e != null) withTimeoutOrNull(timeoutMs) { e.exit.await() }
    }

    private suspend fun startEngine(target: SessionTarget): Engine {
        val started = System.nanoTime()
        val cwd = config.cwd
        // "Continue" is resolved here, not by Pi's `--continue`: Pi takes the newest file of the session folder, which
        // can be a subagent's own session (saved next to its parent, often after it); [newestSession] skips those.
        val t = if (target == SessionTarget.Continue) newestSession(cwd)?.let { SessionTarget.Resume(it) } ?: SessionTarget.Fresh else target
        val process = try {
            launcher.launch(t, cwd)
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineStartException) {
            throw e
        } catch (e: Exception) {
            throw EngineStartException("The engine could not start: ${e.message ?: e.javaClass.simpleName}", e)
        }
        val e = Engine(process)
        e.cwd = cwd
        engine = e
        e.begin()
        val hello = withTimeoutOrNull(config.helloTimeoutMs) { e.buildHello() }
        if (hello == null) {
            val code = e.exitCode
            if (engine === e) engine = null
            e.terminate()
            val tail = stderr.text(3).takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
            throw EngineStartException(
                if (code != null) "The engine stopped while starting (${describeExit(code)})$tail"
                else "The engine did not answer within ${config.helloTimeoutMs / 1000} s$tail",
            )
        }
        e.held = hello
        lastStartMs = (System.nanoTime() - started) / 1_000_000
        return e
    }

    /**
     * Move a started engine to [target] in [LocalConfig.cwd] if needed, and publish its snapshot to a new consumer. A
     * warm engine may run anywhere: a saved session opens with `switch_session` (Pi then works in that session's
     * directory), a new one with `new_session` when the engine is already in the project, else by switching to a new
     * empty session file of the project.
     */
    private suspend fun activate(e: Engine) {
        val wanted = target
        val cwd = config.cwd
        var hello = e.held
        suspend fun open(file: String) {
            e.switchTo("switch_session") { put("sessionPath", file) }
            e.cwd = headerCwd(file) ?: cwd
            hello = null
        }
        suspend fun fresh() {
            if (e.cwd == cwd) {
                if (e.messageCount == 0) return
                e.switchTo("new_session")
                hello = null
            } else {
                open(freshSessionFile(cwd) ?: throw EngineStartException("Could not create a session in $cwd"))
            }
        }
        when (wanted) {
            SessionTarget.Continue -> if (e.warm) {
                val newest = newestSession(cwd)
                when {
                    newest == null -> fresh()
                    newest != e.sessionFile -> open(newest)
                }
            }
            is SessionTarget.Resume -> if (wanted.sessionFile != e.sessionFile) open(wanted.sessionFile)
            SessionTarget.Fresh -> fresh()
        }
        val snapshot = hello ?: e.buildHello() ?: throw EngineStartException("The engine stopped")
        e.held = null
        lease++
        e.deliver(snapshot, dropUnread = true)
        target = SessionTarget.Continue
    }

    private fun onExit(e: Engine, code: Int) {
        if (engine !== e) return
        engine = null
        if (e.stopping || !e.delivered) return
        val now = System.nanoTime() / 1_000_000
        val quick = synchronized(exitTimes) {
            exitTimes.addLast(now)
            while (exitTimes.isNotEmpty() && now - exitTimes.first() > config.crashWindowMs) exitTimes.removeFirst()
            exitTimes.size
        }
        // Respawn into the same session; a session that never reached disk had nothing to keep.
        target = e.sessionFile?.takeIf { File(it).isFile }?.let { SessionTarget.Resume(it) } ?: SessionTarget.Fresh
        _state.value = if (quick >= config.maxQuickExits) {
            ConnectionState.Disconnected(DisconnectReason.EngineFailed, "The engine keeps stopping (${describeExit(code)})")
        } else {
            ConnectionState.Disconnected(DisconnectReason.Lost, "The engine stopped (${describeExit(code)})")
        }
    }

    private inner class Engine(val process: Process) {
        private val writes = LinkedBlockingQueue<ByteArray>()
        private val internal = ConcurrentHashMap<String, CompletableDeferred<JsonObject?>>()
        /** Session-switching commands of the consumer by id, with the path for `switch_session`. */
        val switches = ConcurrentHashMap<String, String>()

        /** The directory Pi works in for the current session (a switch to another project's session changes it). */
        @Volatile
        var cwd: String = ""

        private val ids = AtomicLong()
        private val lock = Any()

        /** While true, records are kept in [buffered] instead of reaching the consumer. Guarded by [lock]. */
        private var holding = true
        private val buffered = ArrayList<JsonObject>()

        /** Open extension dialogs by id, for the snapshot. Guarded by [lock]. */
        private val dialogs = LinkedHashMap<String, JsonObject>()

        val exit = CompletableDeferred<Int>()

        @Volatile
        var exitCode: Int? = null

        /** The snapshot prepared by [warmUp], not yet published. */
        @Volatile
        var held: JsonObject? = null

        /** Started before the current consumer asked for it (warm or parked): its session may need to move. */
        @Volatile
        var warm = false

        @Volatile
        var delivered = false

        @Volatile
        var stopping = false

        @Volatile
        var sessionFile: String? = null

        @Volatile
        var messageCount = 0

        private var reader: Thread? = null
        private var errors: Thread? = null

        val alive: Boolean get() = !exit.isCompleted && !stopping

        val pid: Long? = processId(process)

        fun begin() {
            reader = thread("out") { readStdout() }
            errors = thread("err") { readStderr() }
            thread("in") { writeStdin() }
            thread("wait") {
                val code = process.waitFor()
                // Let the reader drain what the engine wrote before it exited.
                reader?.join(500)
                errors?.join(200)
                exitCode = code
                // Tool commands it started outlive it otherwise.
                killer?.let { runCatching { it.reapOrphans() } }
                // Exit first: a request made after this sees it and returns at once instead of waiting forever.
                exit.complete(code)
                internal.values.forEach { it.complete(null) }
                internal.clear()
                onExit(this, code)
            }
        }

        fun enqueue(json: JsonObject): Boolean {
            if (!alive) return false
            writes.put((json.toString() + "\n").toByteArray(Charsets.UTF_8))
            return true
        }

        fun dialogClosed(id: String) {
            synchronized(lock) { dialogs.remove(id) }
        }

        fun park() {
            synchronized(lock) { holding = true }
            delivered = false
            warm = true
        }

        /** Publish [hello] and everything held since. [dropUnread]: discard what a previous consumer left unread. */
        fun deliver(hello: JsonObject, dropUnread: Boolean) {
            synchronized(lock) {
                if (dropUnread) while (inbox.tryReceive().isSuccess) Unit
                inbox.trySend(hello)
                val shown = hello.arr("dialogs").orEmpty().mapNotNull { (it as? JsonObject)?.str("id") }.toSet()
                for (m in buffered) {
                    if (m.type == "extension_ui_request" && m.str("id") in shown) continue
                    inbox.trySend(m)
                }
                buffered.clear()
                holding = false
            }
            delivered = true
            warm = false
        }

        /**
         * The engine's answer, or null when it stopped or did not answer within [REQUEST_TIMEOUT_MS] (an extension
         * dialog held during a session switch, say): callers hold connectMutex, so an unanswered request must not
         * block every later connect.
         */
        suspend fun request(type: String, fields: JsonObjectBuilder.() -> Unit = {}): JsonObject? {
            val id = "i${ids.incrementAndGet()}-$type"
            val waiter = CompletableDeferred<JsonObject?>()
            internal[id] = waiter
            if (exit.isCompleted || !enqueue(buildJsonObject { put("id", id); put("type", type); fields() })) {
                internal.remove(id)
                return null
            }
            return withTimeoutOrNull(REQUEST_TIMEOUT_MS) { waiter.await() }.also { if (it == null) internal.remove(id) }
        }

        /** Run a session-switching command for the engine's own purposes (not a consumer's). */
        suspend fun switchTo(type: String, fields: JsonObjectBuilder.() -> Unit = {}) {
            val response = request(type, fields) ?: throw EngineStartException("The engine stopped or did not answer")
            if (response.bool("success") != true) {
                throw EngineStartException(response.str("error") ?: "The engine could not open that session")
            }
            if (response.obj("data")?.bool("cancelled") == true) throw EngineStartException("An extension cancelled opening that session")
        }

        /** The `remote_hello`-shaped snapshot: state, messages, commands and open dialogs. Null if the engine stopped. */
        suspend fun buildHello(): JsonObject? {
            val (state, messages, commands) = coroutineScope {
                listOf("get_state", "get_messages", "get_commands").map { async { request(it) } }.awaitAll()
            }
            if (state == null || state.bool("success") != true) return null
            val data = state.obj("data") ?: JsonObject(emptyMap())
            noteState(data)
            val open = synchronized(lock) { dialogs.values.toList() }
            return buildJsonObject {
                put("type", "remote_hello")
                put("protocol", 1)
                put(
                    "host",
                    buildJsonObject {
                        put("name", config.name)
                        put("version", config.version)
                        put("cwd", config.cwd)
                    },
                )
                put("state", data)
                put("messages", messages?.obj("data")?.arr("messages") ?: JsonArray(emptyList()))
                put("dialogs", JsonArray(open))
                put("commands", commands?.obj("data")?.arr("commands") ?: JsonArray(emptyList()))
            }
        }

        private fun noteState(data: JsonObject?) {
            data ?: return
            data.str("sessionFile")?.let { sessionFile = it }
            data.int("messageCount")?.let { messageCount = it }
        }

        private fun route(text: String) {
            val parsed = try {
                ProtocolJson.parseToJsonElement(text) as? JsonObject
            } catch (e: Exception) {
                null
            }
            if (parsed == null) {
                stderr.add("[stdout] ${text.take(500)}")
                return
            }
            var message: JsonObject = parsed
            val id = message.str("id")
            if (id != null && message.type == "response") {
                internal.remove(id)?.let {
                    // Events before this answer are in the message list: the snapshot must not get them twice.
                    if (id.endsWith("-get_messages")) synchronized(lock) { buffered.removeAll { m -> m.type !in KEEP_WHILE_HOLDING } }
                    it.complete(message)
                    return
                }
            }
            var switched = false
            if (id != null && id.startsWith("L")) {
                val dot = id.indexOf('.')
                val owner = id.substring(1, dot.coerceAtLeast(1)).toIntOrNull()
                // An answer for a previous consumer: nobody is waiting for it here.
                if (dot < 0 || owner != lease) return
                val original = id.substring(dot + 1)
                message = JsonObject(message + ("id" to JsonPrimitive(original)))
                if (message.type == "response" && message.bool("success") == true) {
                    if (message.str("command") == "get_state") noteState(message.obj("data"))
                    val path = switches.remove(original)
                    switched = path != null && message.obj("data")?.bool("cancelled") != true
                    if (switched && !path.isNullOrEmpty()) headerCwd(path)?.let { cwd = it }
                } else {
                    switches.remove(original)
                }
            }
            when (message.type) {
                "extension_ui_request" -> if (message.str("method") in DIALOG_METHODS) {
                    message.str("id")?.let { synchronized(lock) { dialogs[it] = message } }
                }
                "extension_ui_dismissed" -> message.str("id")?.let(::dialogClosed)
            }
            synchronized(lock) {
                if (holding) buffered.add(message) else inbox.trySend(message)
                // The session changed: hold its events until the new snapshot is out.
                if (switched) holding = true
            }
            if (switched) scope.launch { republish() }
        }

        private suspend fun republish() {
            connectMutex.withLock {
                if (engine !== this || !delivered) return
                val hello = buildHello() ?: return
                deliver(hello, dropUnread = false)
            }
        }

        private fun readStdout() {
            val lines = JsonLines(
                maxRecord = MAX_RECORD_BYTES,
                onOversized = { stderr.add("[app] skipped an RPC record of $it bytes") },
                onRecord = { record ->
                    // A record that parses into more than the heap holds is dropped, not allowed to end the app.
                    try {
                        route(record)
                    } catch (e: OutOfMemoryError) {
                        stderr.add("[app] dropped an RPC record of ${record.length} characters: out of memory")
                    }
                },
            )
            val buffer = ByteArray(64 * 1024)
            try {
                process.inputStream.use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        lines.feed(buffer, n)
                    }
                }
                lines.finish()
            } catch (e: IOException) {
                // The process ended or was killed; the waiter reports it.
            }
        }

        private fun readStderr() {
            try {
                process.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach {
                        stderr.add(it)
                        onStderr(it)
                    }
                }
            } catch (e: IOException) {
                // Ended with the process.
            }
        }

        private fun writeStdin() {
            val out = process.outputStream.buffered(64 * 1024)
            try {
                while (true) {
                    val bytes = writes.take()
                    if (bytes === CLOSE_STDIN) break
                    out.write(bytes)
                    if (writes.isEmpty()) out.flush()
                }
            } catch (e: IOException) {
                // The engine closed stdin (it exited).
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                runCatching { out.close() }
            }
        }

        /**
         * Ask the engine to stop (closing stdin makes Pi shut down in order), then kill it and everything it started.
         * Session entries are on disk as they happen, so the grace period is short.
         */
        fun terminate() {
            if (stopping && exit.isCompleted) return
            stopping = true
            writes.put(CLOSE_STDIN)
            thread("stop") {
                if (process.waitFor(STOP_GRACE_MS, TimeUnit.MILLISECONDS)) return@thread
                val id = pid
                if (killer != null && id != null) runCatching { killer.killTree(id) }
                if (!process.waitFor(KILL_GRACE_MS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            }
        }

        private fun thread(name: String, body: () -> Unit): Thread =
            Thread(body, "pibolt-engine-$name").apply {
                isDaemon = true
                start()
            }
    }

    companion object {
        private val CLOSE_STDIN = ByteArray(0)
        private val SWITCHES = setOf("new_session", "switch_session", "fork", "clone")

        /** The `cwd` in a session file's header line, or null. */
        fun headerCwd(file: String): String? = runCatching {
            File(file).bufferedReader(Charsets.UTF_8).use { it.readLine() }
                ?.let { ProtocolJson.parseToJsonElement(it) as? JsonObject }
                ?.takeIf { it.type == "session" }
                ?.str("cwd")
        }.getOrNull()

        private fun processId(process: Process): Long? =
            runCatching { process.javaClass.getMethod("pid").invoke(process) as Long }.getOrNull()
                ?: runCatching { process.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(process).toLong() }.getOrNull()
        private val DIALOG_METHODS = setOf("select", "confirm", "input", "editor")
        private val KEEP_WHILE_HOLDING = setOf("response", "extension_ui_request", "extension_ui_dismissed")
        private const val REQUEST_TIMEOUT_MS = 30_000L

        /** RPC records over this are skipped: parsing one holds the bytes, a string and a JSON tree at once. */
        private const val MAX_RECORD_BYTES = 32 * 1024 * 1024
        private const val STOP_GRACE_MS = 500L
        private const val KILL_GRACE_MS = 1_000L

        /** A process exit status as people read it (128 + n is death by signal n). */
        fun describeExit(code: Int): String = if (code > 128) "signal ${code - 128}" else "exit code $code"
    }
}
