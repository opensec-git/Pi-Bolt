package opensec.pibolt.app.engine

import android.content.Context
import android.content.res.AssetManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import opensec.pibolt.protocol.bool
import opensec.pibolt.protocol.obj
import opensec.pibolt.protocol.str
import opensec.pibolt.app.ui.session.features2.AppExtension
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.cost.Cost
import opensec.pibolt.app.checkpoints.Git
import opensec.pibolt.app.checkpoints.ShadowRepo
import opensec.pibolt.app.checkpoints.TurnCheckpoints
import opensec.pibolt.app.data.SafeFiles
import opensec.pibolt.app.preview.PreviewExtension
import opensec.pibolt.app.secrets.SecretEnv
import opensec.pibolt.app.secrets.SecretStore
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.protocol.backend.ConnectionState
import opensec.pibolt.protocol.backend.DisconnectReason
import opensec.pibolt.protocol.backend.EngineLauncher
import opensec.pibolt.protocol.backend.EngineStartException
import opensec.pibolt.protocol.backend.LocalBackend
import opensec.pibolt.protocol.backend.LocalConfig
import opensec.pibolt.protocol.backend.SessionTarget
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import java.util.UUID
import java.util.WeakHashMap
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The engine on this phone (android/engine/CONTRACT.md): sets up the prefix, runs `libpibolt.so --mode rpc` processes
 * and hands sessions to the UI as [SessionController]s over [LocalBackend]s.
 *
 * Fast opens: once ready, idle engines are kept started ([IdlePool]): a fresh spare (a new empty session in the last
 * project) and the last session that ended while idle, parked on its session. An open takes the engine that already has
 * the wanted session (no RPC at all: tens of milliseconds), else the spare, which moves there over RPC (`switch_session`,
 * which rebuilds Pi's runtime: seconds on a phone); no process start on the user's tap either way. A new spare is started
 * after each open. Idle engines stop on memory pressure and after [BACKGROUND_IDLE_MS] in the background.
 *
 * Engines belong to the application object (the process), not to an Activity: configuration changes and backgrounding
 * do not touch them, and SessionService keeps the process in the foreground while a turn runs. Everything that touches
 * the disk or spawns runs on [Dispatchers.IO]; nothing here blocks the main thread.
 */
class LocalEngine(private val app: PiBoltApp) : EngineApi {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val paths = EnginePaths(
        prefix = EnginePaths.prefixFor(app.packageName),
        dataDir = EnginePaths.dataFor(app.packageName),
        nativeLibDir = File(app.applicationInfo.nativeLibraryDir),
    )
    private val index = SessionIndex(paths.agentDir)
    private val dns = NetworkDns(app, paths.etc)
    private val reaper = ProcessReaper()

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Idle)
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()

    override val projectsDir: File get() = paths.projects

    override val agentDir: File get() = paths.agentDir

    override val auth: AuthFile = AuthFile(paths.agentDir, scope)

    private val setupMutex = Mutex()

    @Volatile
    private var manifest: EngineManifest? = null

    @Volatile
    private var unavailable: String? = null

    private val prefs by lazy { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** Idle engines for the next opens. */
    private val idle = IdlePool<LocalBackend>(maxParked = 1) { it.sessionFile }

    /**
     * The last chat's engine, parked for the next chat open. Kept apart from [idle]: a chat engine runs with the chats'
     * tools only (no shell, no files) and must never serve a project, nor a project's engine a chat.
     */
    private val chatIdle = IdlePool<LocalBackend>(maxParked = 1) { it.sessionFile }

    override val chatDir: File get() = ChatSpace.dir(paths.home)
    private val watchers = HashMap<LocalBackend, Job>()

    /** Empty session files made to start a session in another project, deleted again if never used. */
    private val emptySessions: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var prepareJob: Job? = null

    @Volatile
    private var backgroundJob: Job? = null

    @Volatile
    private var inBackground = false

    /** Opens still connecting: no spare starts meanwhile, so it does not compete with them for the CPU. */
    private val opening = AtomicInteger()

    /** The last open: milliseconds from [open] to the first snapshot, and whether a warm engine served it. */
    @Volatile
    var lastOpen: OpenTiming? = null
        private set

    data class OpenTiming(val project: String, val readyMs: Long, val warm: Boolean, val engineStartMs: Long?)

    override fun prepare() {
        if (prepareJob?.isActive == true) return
        // Credentials first: Home decides on the first-run key sheet from them, and they need no prefix.
        auth.refresh()
        prepareJob = scope.launch {
            if (ensureSetup() == null) return@launch
            ensureSpare()
        }
    }

    override fun open(project: File, sessionFile: String?, fresh: Boolean): SessionController {
        val started = SystemClock.elapsedRealtime()
        val dir = engineForm(project)
        // The chats' directory is no project: its engines run with the chats' tools only.
        if (isChat(dir.path)) return openChat(sessionFile.takeUnless { fresh })
        val target = when {
            sessionFile != null -> SessionTarget.Resume(sessionFile)
            fresh -> SessionTarget.Fresh
            else -> SessionTarget.Continue
        }
        retireStale(dir)
        // The project's live sessions: several when one was busy as another opened (LivePolicy.planOpen).
        val here = livesIn(dir)
        val plan = LivePolicy.planOpen(
            here.map { LivePolicy.Here(it.hostId, it.sessionFile, it.isBusy, app.lastUsed(it)) },
            when (target) {
                is SessionTarget.Resume -> LivePolicy.Want.Session(target.sessionFile)
                SessionTarget.Fresh -> LivePolicy.Want.New
                else -> LivePolicy.Want.Latest
            },
        )
        val chosen = (plan as? LivePolicy.Plan.Show)?.key ?: (plan as? LivePolicy.Plan.Move)?.key
        val live = here.firstOrNull { it.hostId == chosen }
        if (plan is LivePolicy.Plan.Show && live != null) {
            app.focus(live)
            return live
        }
        // "Continue" is the project's newest saved session (subagents' sessions skipped), or a new one.
        val resolved = if (target == SessionTarget.Continue) sessions(dir).firstOrNull()?.let { SessionTarget.Resume(it.file) } ?: SessionTarget.Fresh else target
        val want = when (resolved) {
            is SessionTarget.Resume -> IdlePool.Want.Session(resolved.sessionFile)
            else -> IdlePool.Want.New(dir.path)
        }
        // Another live session (every one here is busy, or there is none): at the cap, an idle session elsewhere makes
        // room; when all of them run, the user decides (never a busy one is stopped).
        if (live == null) {
            when (val room = LivePolicy.roomFor(liveRules())) {
                LivePolicy.Room.Free -> Unit
                is LivePolicy.Room.Stop -> localSessions().firstOrNull { it.hostId == room.key }?.stop()
                is LivePolicy.Room.Full -> throw IllegalStateException(room.message)
            }
            if (here.isNotEmpty()) Log.i(TAG, "open in ${dir.name}: ${here.size} sessions busy there, opening another beside them")
        }
        // An idle live session moves: an idle engine that already has the wanted session takes over (the live one is
        // parked for the way back), else the live engine moves over RPC. Only an idle one ever gets here (planOpen).
        val taken = (if (live == null || live.isIdle) idle.take(want, exactOnly = live != null) else null)?.let { t ->
            // Secrets are in an engine's environment from its start: one from another project is not moved here.
            if (SecretStore.get(app).engineFits(t.engine.engineCwd, dir) && !stale(t.engine)) return@let t
            forget(t.engine)
            t.engine.close()
            null
        }
        if (live != null && taken == null) {
            when (resolved) {
                is SessionTarget.Resume -> live.switchSession(resolved.sessionFile)
                else -> live.newSession()
            }
            app.focus(live)
            return live
        }
        val config = configFor(dir)
        val backend = taken?.engine?.also { it.assign(config, resolved) } ?: newBackend(config, resolved)
        // Taking a live session's place keeps its slot; a session beside busy ones takes the first free one.
        val slot = live?.slot ?: LivePolicy.freeSlot(here.map { it.slot })
        val controller = SessionController.local(app, dir, backend, ::release, checkpointsFor(dir), slot = slot)
        app.addLocalSession(controller, replacing = live)
        opening.incrementAndGet()
        scope.launch {
            prefs.edit { putString(KEY_LAST_PROJECT, dir.path) }
            val end = withTimeoutOrNull(OPEN_TIMEOUT_MS) {
                backend.state.first { it is ConnectionState.Connected || it is ConnectionState.Disconnected }
            }
            if (end is ConnectionState.Connected) {
                val timing = OpenTiming(dir.name, SystemClock.elapsedRealtime() - started, taken != null, backend.lastStartMs)
                lastOpen = timing
                val kind = when {
                    taken == null -> "cold"
                    taken.exact -> "warm, same session"
                    else -> "warm, moved"
                }
                Log.i(TAG, "open ${timing.project}: ready in ${timing.readyMs} ms ($kind, engine start ${timing.engineStartMs} ms, pid ${backend.pid})")
            }
            // The next open is warm too; started only now so it does not compete with this one.
            opening.decrementAndGet()
            ensureSpare()
            deleteUnusedEmptySessions()
        }
        return controller
    }

    override fun openChat(sessionFile: String?): SessionController {
        val started = SystemClock.elapsedRealtime()
        val dir = chatDir
        val target = sessionFile?.let { SessionTarget.Resume(it) } ?: SessionTarget.Fresh
        val want = when (target) {
            is SessionTarget.Resume -> IdlePool.Want.Session(target.sessionFile)
            else -> IdlePool.Want.New(dir.path)
        }
        retireStale(dir)
        val live = liveIn(dir)
        if (live != null) {
            val same = target is SessionTarget.Resume && target.sessionFile == live.sessionFile
            // A new chat while the live one is still empty: it already is one.
            val alreadyNew = target == SessionTarget.Fresh && live.isIdle && live.state.value.let { it.hello && it.items.isEmpty() }
            if (same || alreadyNew) {
                app.focus(live)
                return live
            }
            // Chats share one live engine, which moves between chats; Pi would abort a running reply to move it.
            if (!LivePolicy.maySwitch(live.isBusy)) throw IllegalStateException(LivePolicy.BUSY_SWITCH)
        } else {
            when (val room = LivePolicy.roomFor(liveRules())) {
                LivePolicy.Room.Free -> Unit
                is LivePolicy.Room.Stop -> localSessions().firstOrNull { it.hostId == room.key }?.stop()
                is LivePolicy.Room.Full -> throw IllegalStateException(room.message)
            }
        }
        // As for a project: an idle live chat gives way to a parked engine that already has the wanted chat; otherwise
        // the live engine moves over RPC.
        val taken = (if (live == null || live.isIdle) chatIdle.take(want, exactOnly = live != null) else null)?.let { t ->
            if (!stale(t.engine)) return@let t
            forget(t.engine)
            t.engine.close()
            null
        }
        if (live != null && taken == null) {
            when (target) {
                is SessionTarget.Resume -> live.switchSession(target.sessionFile)
                else -> live.newSession()
            }
            app.focus(live)
            return live
        }
        val config = configFor(dir).copy(name = ChatSpace.NAME)
        val backend = taken?.engine?.also { it.assign(config, target) } ?: newBackend(config, target)
        val controller = SessionController.local(app, dir, backend, ::release, checkpoints = null, chat = true)
        app.addLocalSession(controller, replacing = live)
        opening.incrementAndGet()
        scope.launch {
            val end = withTimeoutOrNull(OPEN_TIMEOUT_MS) {
                backend.state.first { it is ConnectionState.Connected || it is ConnectionState.Disconnected }
            }
            if (end is ConnectionState.Connected) {
                Log.i(TAG, "open chat: ready in ${SystemClock.elapsedRealtime() - started} ms (${if (taken == null) "cold" else "warm"}, pid ${backend.pid})")
            }
            opening.decrementAndGet()
            ensureSpare()
        }
        return controller
    }

    override suspend fun providers(): ProviderReport? = withContext(Dispatchers.IO) {
        ensureSetup() ?: return@withContext null
        val backend = probeEngine() ?: return@withContext null
        val rpc = backend.query("get_providers")
        if (rpc != null && rpc.bool("success") == true) return@withContext ProviderReport.from(rpc.obj("data"), complete = true)
        // Engines before 1.1.7 have no get_providers: the helper extension describes them.
        AppExtension.runWith(File(app.cacheDir, "pibolt-app"), "providers") { message -> helperPrompt(backend, message, HELPER_TIMEOUT_MS) }
            .onFailure { Log.i(TAG, "providers: ${it.message}") }
            .getOrNull()
            ?.let { ProviderReport.from(it, complete = false) }
    }

    /**
     * An idle engine to ask about providers: the spare, else a parked one; started (the spare) when there is none.
     * Never a live session's engine.
     */
    private suspend fun probeEngine(): LocalBackend? {
        fun idleEngine() = idle.fresh()?.engine?.takeIf { it.isAlive } ?: idle.engines().firstOrNull { it.isAlive }
        idleEngine()?.let { return it }
        ensureSpare()
        // Another caller may be starting the spare: wait for it.
        return withTimeoutOrNull(PROBE_WAIT_MS) {
            var found = idleEngine()
            while (found == null) {
                delay(250)
                found = idleEngine()
            }
            found
        }
    }

    /** Sends the helper's [message] as a prompt to [backend]; null once answered, else why it was not. */
    private suspend fun helperPrompt(backend: LocalBackend, message: String, timeoutMs: Long): String? {
        val response = backend.query("prompt", timeoutMs) { put("message", message) } ?: return "The engine stopped or did not answer"
        return if (response.bool("success") == true) null else response.str("error") ?: "The engine refused the command"
    }

    override fun login(provider: String, method: String): ProviderLogin? {
        if (unavailable != null) return null
        val login = ProviderLogin(provider)
        val job = scope.launch {
            if (ensureSetup() == null) return@launch login.finish(unavailable ?: "The engine is not available")
            if (login.cancelled) return@launch
            // The spare runs it (no engine start on the tap), else a new engine; it goes back to the pool afterwards.
            val spare = idle.takeFresh()?.takeIf { it.engine.isAlive }
            val backend = spare?.engine ?: newBackend(configFor(paths.projects), SessionTarget.Fresh)
            val cwd = spare?.cwd ?: paths.projects.path
            // Only an engine whose helper command came to its end goes back to the pool: one that timed out may still
            // be in the flow (an open dialog, a callback server), and is stopped instead.
            var ended = false
            try {
                login.attach(send = backend::send)
                if (login.cancelled || !backend.connect() || login.cancelled) {
                    return@launch login.finish(if (login.cancelled) ProviderLogin.CANCELLED else "The engine could not start")
                }
                coroutineScope {
                    val messages = launch { backend.incoming.collect(login::on) }
                    val args = "$provider $method ${File(paths.agentDir, "auth.json").path}"
                    val result = AppExtension.runWith(File(app.cacheDir, "pibolt-app"), "login", args) { message ->
                        val response = backend.query("prompt", LOGIN_TIMEOUT_MS) { put("message", message) }
                        ended = response != null
                        when {
                            response == null -> if (login.cancelled) ProviderLogin.CANCELLED else "The sign-in did not finish in time"
                            response.bool("success") != true -> response.str("error") ?: "The engine refused the sign-in"
                            else -> null
                        }
                    }
                    messages.cancel()
                    login.finish(if (login.cancelled) ProviderLogin.CANCELLED else result.exceptionOrNull()?.message)
                }
            } finally {
                val reuse = ended && !login.cancelled && backend.isAlive && backend.messageCount == 0 && !stale(backend)
                if (reuse) backend.park()
                if (!reuse || !idle.addFresh(backend, cwd)) {
                    forget(backend)
                    backend.close()
                }
                auth.refresh()
                scope.launch { ensureSpare() }
            }
        }
        // Cancelling stops this job wherever it is (setup, the engine's start, the command): its engine is then closed,
        // and a flow cancelled while it starts never sends its command.
        login.attach(stop = { job.cancel() })
        return login
    }

    override fun packagesChanged() {
        // The idle engines loaded the old packages: the next session, chat and provider list need new ones.
        stopIdleEngines("packages changed")
        scope.launch { ensureSpare() }
    }

    /** The engine's git (`PREFIX/bin/git`) once the prefix is set up; null where the engine cannot run. */
    suspend fun gitBinary(): File? = ensureSetup()?.let { File(paths.bin, "git").takeIf(File::exists) }

    /** The environment engine processes get, for tools the app runs itself (git). */
    suspend fun toolEnvironment(): Map<String, String> =
        engineEnvironment(paths, System.getenv(), timeZone(), ensureSetup()?.env.orEmpty(), app.github.gitEnvironment())

    /**
     * Turn checkpoints of [project]: a shadow repository under the agent dir, run with the pack's git and the engine's
     * environment (read when git runs: the pack may still be setting up now).
     */
    private fun checkpointsFor(project: File): TurnCheckpoints {
        val git = Git(File(paths.bin, "git").path, { engineEnvironment(paths, System.getenv(), TimeZone.getDefault().id, manifest?.env.orEmpty()) }, ShadowRepo.dirFor(paths.agentDir, project), project)
        return TurnCheckpoints(ShadowRepo(git))
    }

    override fun runtimes(): RuntimesReport {
        // The manifest without the setup: the screen works before the engine first starts (and when it cannot run).
        val shipped = manifest?.runtimes
            ?: runCatching { app.assets.open(EnginePrefix.MANIFEST_ASSET).use { EngineManifest.parse(it.reader().readText()) }.runtimes }.getOrDefault(emptyList())
        return RuntimesReport(shipped, RuntimeStore.usage(paths, shipped))
    }

    override fun clearRuntimeStorage(kind: RuntimeStorage.Kind): Boolean = RuntimeStore.clear(paths, kind)

    /** Saved sessions with at least one message, newest first. Blocking file I/O: call off the main thread. */
    override fun sessions(project: File): List<SessionSummary> {
        val form = engineForm(project)
        if (isChat(form.path)) return chats()
        return index.list(form).filter { it.messageCount > 0 }
    }

    override fun sessionCost(sessionFile: String): Cost = index.cost(File(sessionFile))

    override fun chats(): List<SessionSummary> = index.listIn(listOf(ChatSpace.sessionDir(paths.agentDir))).filter { it.messageCount > 0 }

    private fun isChat(cwd: String): Boolean = ChatSpace.isChat(cwd, paths.home)

    override fun deleteSessions(project: File) {
        val form = engineForm(project)
        val dirs = linkedSetOf(index.sessionDir(form.absolutePath), index.sessionDir(project.absolutePath))
        runCatching { project.canonicalPath }.getOrNull()?.let { dirs += index.sessionDir(engineForm(File(it)).absolutePath) }
        for (dir in dirs) SafeFiles.deleteTree(dir)
    }

    override suspend fun cli(args: List<String>, cwd: File?, timeoutMs: Long, env: Map<String, String>): CliResult = withContext(Dispatchers.IO) {
        val m = ensureSetup() ?: return@withContext CliResult(-1, "", unavailable ?: "The engine is not available")
        val dir = cwd?.let(::engineForm) ?: paths.home
        val process = runCatching {
            ProcessBuilder(listOf(paths.engine.path) + args).directory(dir).apply {
                val current = environment()
                val wanted = engineEnvironment(paths, current.toMap(), timeZone(), m.env) + ("NO_COLOR" to "1") + env.filterKeys { it in EngineApi.CLI_ENV }
                current.clear()
                current.putAll(wanted)
            }.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).start()
        }.getOrElse { return@withContext CliResult(-1, "", it.message ?: "Could not start the engine") }
        val out = async { process.inputStream.bufferedReader().readText() }
        val err = async { process.errorStream.bufferedReader().readText() }
        val finished = runInterruptible { process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
        if (!finished) process.destroyForcibly()
        CliResult(if (finished) process.exitValue() else -1, out.await(), err.await().ifBlank { if (finished) "" else "Timed out" })
    }

    /** [file] with `ApplicationInfo.dataDir` (`/data/user/0/<id>`) written as the engine sees it (`/data/data/<id>`). */
    private fun engineForm(file: File): File {
        val path = file.absolutePath
        val userForm = app.applicationInfo.dataDir
        return if (path == userForm || path.startsWith("$userForm/")) File(paths.dataDir.path + path.removePrefix(userForm)) else File(path)
    }

    /**
     * Memory pressure, or long in the background: stop the idle engines, then idle live sessions off screen
     * ([LivePolicy.trim]; all of them when [critical]). A busy session is never stopped.
     */
    fun trimMemory(reason: String, critical: Boolean = false) {
        stopIdleEngines(reason)
        val sessions = localSessions()
        val victims = LivePolicy.trim(liveRules(), critical, SystemClock.elapsedRealtime()).mapNotNull { key -> sessions.firstOrNull { it.hostId == key } }
        for (session in victims) session.stop(keepEngine = false)
        if (victims.isNotEmpty()) Log.i(TAG, "memory ($reason): stopped ${victims.size} idle sessions")
    }

    /**
     * Stops the parked and spare engines only (live sessions keep theirs), so the next engine starts with fresh state:
     * new credentials, secrets or settings.
     */
    fun stopIdleEngines(reason: String) {
        val stopped = idle.drain() + chatIdle.drain()
        stopped.forEach(::stopIdle)
        if (stopped.isNotEmpty()) Log.i(TAG, "stopping ${stopped.size} idle engines ($reason)")
    }

    /**
     * A setting that engines read at their start changed ("Web search": their tool flags, ChatSpace): the idle engines
     * stop, and so does every live on-device session that is not running a turn ([LivePolicy.restartsForSettings]),
     * so the next open, or New chat in an open chat, starts an engine with the new flags. A session in the middle of a
     * turn keeps its engine until it ends.
     */
    fun restartForSettings(reason: String) {
        stopIdleEngines(reason)
        val stopped = localSessions().filter { LivePolicy.restartsForSettings(it.state.value) }
        for (session in stopped) session.stop(keepEngine = false)
        Log.i(TAG, "stopped ${stopped.size} live sessions not in a turn ($reason)")
    }

    private fun stopIdle(backend: LocalBackend) {
        forget(backend)
        backend.close()
    }

    private fun localSessions(): List<SessionController> = app.sessions.value.filter { it.isLocal && !it.stopped }

    private fun liveIn(dir: File): SessionController? = localSessions().firstOrNull { it.project == dir }

    private fun livesIn(dir: File): List<SessionController> = localSessions().filter { it.project == dir }

    /** The live sessions on this phone as [LivePolicy] sees them. */
    private fun liveRules(): List<LivePolicy.Live> {
        val shown = app.session.value.takeIf { app.isForeground }
        return localSessions().map { LivePolicy.Live(it.hostId, it.isBusy, it === shown, app.lastUsed(it)) }
    }

    /** No activity is visible. The spare stops after a while unless the app comes back. */
    fun onBackground() {
        inBackground = true
        stopSpareIn(BACKGROUND_IDLE_MS, "idle in the background")
    }

    /** The system asked for memory while the app is in the background: stop the spare after [TRIM_GRACE_MS]. */
    fun trimSoon(reason: String) {
        if (!inBackground) return trimMemory(reason)
        stopSpareIn(TRIM_GRACE_MS, reason)
    }

    /** When the scheduled stop runs (elapsedRealtime); a later request never postpones an earlier stop. */
    @Volatile
    private var stopAt = 0L

    private fun stopSpareIn(ms: Long, reason: String) {
        val at = SystemClock.elapsedRealtime() + ms
        if (backgroundJob?.isActive == true && stopAt <= at) return
        stopAt = at
        backgroundJob?.cancel()
        backgroundJob = scope.launch {
            delay(ms)
            trimMemory(reason)
        }
    }

    fun onForeground() {
        inBackground = false
        backgroundJob?.cancel()
        backgroundJob = null
        prepare()
    }

    /**
     * Read the engine binary once at low priority so the first spawn finds it in the page cache (it is large, and a
     * cold start otherwise faults it in from flash while the user waits).
     */
    fun prefetch() {
        if ("arm64-v8a" !in Build.SUPPORTED_ABIS) return
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val file = paths.engine
            if (!file.isFile) return@Thread
            val started = SystemClock.elapsedRealtime()
            val bytes = runCatching {
                FileInputStream(file).use { input ->
                    val buffer = ByteArray(1 shl 20)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                    }
                    total
                }
            }.getOrDefault(0L)
            Log.i(TAG, "prefetched ${bytes / (1 shl 20)} MB of the engine in ${SystemClock.elapsedRealtime() - started} ms")
        }, "pibolt-engine-prefetch").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Checks the device and the pack, and brings the prefix up to date: after the first run that is one small stamp
     * file compared with the manifest's fingerprint. Null when the engine cannot run.
     */
    private suspend fun ensureSetup(): EngineManifest? = setupMutex.withLock {
        manifest?.let { return it }
        unavailable?.let { return null }
        val started = SystemClock.elapsedRealtime()
        val loaded = runCatching { app.assets.open(EnginePrefix.MANIFEST_ASSET).use { EngineManifest.parse(it.reader().readText()) } }
        val reason = engineUnavailableReason(
            applicationId = app.packageName,
            abis = Build.SUPPORTED_ABIS.toList(),
            sdk = Build.VERSION.SDK_INT,
            manifest = loaded.getOrNull(),
            engineShipped = paths.engine.isFile,
            dataDirReal = File(app.applicationInfo.dataDir).let { runCatching { it.canonicalPath }.getOrDefault(it.path) },
            prefixDataReal = runCatching { paths.prefix.parentFile!!.parentFile!!.canonicalPath }.getOrNull(),
        )
        if (reason != null) {
            loaded.exceptionOrNull()?.let { Log.w(TAG, "engine manifest: $it") }
            Log.i(TAG, "engine unavailable: $reason")
            unavailable = reason
            _status.value = EngineStatus.Unavailable(reason)
            return null
        }
        val m = loaded.getOrThrow()
        _status.value = EngineStatus.Preparing("Setting up the engine")
        try {
            val prefix = EnginePrefix(paths, AndroidAssets(app.assets))
            val full = prefix.ensure(m)
            // Leftovers of a previous app process (its engines are gone with it).
            runCatching { reaper.reapOrphans() }
            dns.start()
            AppExtension.install(paths.agentDir)
            BackgroundJobs.install(paths.agentDir, app.assets)
            PreviewExtension.install(paths.agentDir) { app.assets.open(PreviewExtension.ASSET) }
            val how = if (full) "installed (kept ${prefix.kept.size} unchanged trees)" else "up to date"
            Log.i(TAG, "prepare: prefix $how, ${SystemClock.elapsedRealtime() - started} ms (engine ${m.engine}, glibc ${m.glibc})")
        } catch (e: Exception) {
            Log.e(TAG, "prefix setup failed", e)
            _status.value = EngineStatus.Failed(setupFailure(e), e.toString(), canRetry = true)
            return null
        }
        manifest = m
        if (_status.value !is EngineStatus.Ready) _status.value = EngineStatus.Ready(m.engine)
        m
    }

    /** Start the spare engine unless there is one (or the app is in the background). */
    private suspend fun ensureSpare() {
        if (inBackground || opening.get() > 0 || ensureSetup() == null) return
        val home = prefs.getString(KEY_LAST_PROJECT, null)?.let(::File)?.takeIf { it.isDirectory && !isChat(it.path) } ?: paths.projects
        if (idle.hasFresh()) return
        val backend = newBackend(configFor(home), SessionTarget.Fresh)
        if (!idle.addFresh(backend, home.path)) {
            forget(backend)
            return
        }
        if (_status.value !is EngineStatus.Ready) _status.value = EngineStatus.Preparing("Starting the engine")
        if (backend.warmUp()) {
            Log.i(TAG, "spare engine ready: cold spawn to first answer ${backend.lastStartMs} ms (pid ${backend.pid}, in ${home.name})")
        } else {
            idle.remove(backend)
            forget(backend)
        }
    }

    /**
     * A session stopped. An idle engine is parked on its session for a later open of it (pushing out an older parked
     * one); a busy one is stopped.
     */
    private fun release(backend: LocalBackend, wasIdle: Boolean) {
        // An engine started with other settings ("Web search" changed during its turn) is not parked: it would serve a
        // later open with the old tools.
        if (wasIdle && backend.isAlive && manifest != null && !stale(backend)) {
            backend.park()
            val pool = if (isChat(backend.config.cwd)) chatIdle else idle
            for (old in pool.park(backend, backend.engineCwd ?: backend.config.cwd)) {
                forget(old)
                old.close()
            }
            scope.launch { deleteUnusedEmptySessions() }
            return
        }
        forget(backend)
        scope.launch {
            backend.closeAndAwait()
            deleteUnusedEmptySessions()
            ensureSpare()
        }
    }

    /** The page engine's folder: no project, no chats; Pi works here without a session ([PageEngine]). */
    val pagesDir: File get() = File(paths.home, ".pages")

    /**
     * A backend for the page engine ([PageEngine]): Pi in RPC mode without a session (`--no-session`) in [pagesDir],
     * started with the same launch as a session's engine (prefix, environment, the user's packages). Not pooled: the
     * page engine owns and stops it. Null when the engine is not available on this phone.
     */
    suspend fun pageBackend(): LocalBackend? {
        ensureSetup() ?: return null
        val dir = pagesDir.apply { mkdirs() }
        return newBackend(LocalConfig(name = "Pages", version = manifest?.engine.orEmpty(), cwd = dir.path), SessionTarget.NoSession)
    }

    /** The page engine's backend stopped: its status no longer follows it. */
    fun releasePageBackend(backend: LocalBackend) {
        forget(backend)
        backend.close()
    }

    private fun configFor(project: File) = LocalConfig(name = project.name, version = manifest?.engine.orEmpty(), cwd = project.path)

    private fun newBackend(config: LocalConfig, target: SessionTarget): LocalBackend {
        val webSearch = app.settings.settings.value.webSearch
        val backend = LocalBackend(
            config = config,
            target = target,
            // The flags read at start ("Web search") are fixed per engine, relaunches included; [launchedWith] keeps them.
            launcher = EngineLauncher { t, cwd -> launch(t, cwd, webSearch) },
            newestSession = { cwd -> sessions(File(cwd)).firstOrNull()?.file },
            freshSessionFile = ::emptySession,
            killer = reaper,
            onStderr = { Log.i(ENGINE_TAG, it) },
        )
        launchedWith[backend] = webSearch
        watch(backend)
        return backend
    }

    /** The "Web search" setting each engine started with (its tool flags, ChatSpace). */
    private val launchedWith: MutableMap<LocalBackend, Boolean> = Collections.synchronizedMap(WeakHashMap())

    /** [backend] started with another "Web search" than the current one: it is replaced instead of reused or parked. */
    private fun stale(backend: LocalBackend): Boolean = LivePolicy.staleEngine(launchedWith[backend], app.settings.settings.value.webSearch)

    /**
     * The live session in [dir] stops when its engine is stale and it is not in a turn, so this open starts or takes a
     * current engine (a turn in progress keeps its engine; the next open after it ends replaces it).
     */
    private fun retireStale(dir: File) {
        for (live in livesIn(dir)) {
            val backend = live.localBackend ?: continue
            if (stale(backend) && LivePolicy.restartsForSettings(live.state.value)) {
                Log.i(TAG, "replacing the engine of ${dir.name}: started with other settings")
                live.stop(keepEngine = false)
            }
        }
    }

    private suspend fun launch(target: SessionTarget, cwd: String, webSearch: Boolean): java.lang.Process {
        val m = ensureSetup() ?: throw EngineStartException(unavailable ?: (_status.value as? EngineStatus.Failed)?.message ?: "The engine is not available")
        val project = File(cwd)
        project.mkdirs()
        if (!project.isDirectory) throw EngineStartException("Cannot create the project folder $cwd")
        // In the chats' directory: the chats' tools, session folder and prompt; the web tools only while [webSearch] (the
        // setting when this engine was made, [launchedWith]) is on (ChatSpace).
        val command = listOf(paths.engine.path) + ChatSpace.engineArgs(target, cwd, paths.home, paths.agentDir, webSearch)
        Log.i(TAG, "starting engine ${m.engine} in ${project.name} ${target.cliArgs().joinToString(" ")}${if (isChat(cwd)) " (chat)" else ""}")
        // Marks this engine's processes, so what it leaves running is stopped after it, and only then (ProcessReaper).
        val token = reaper.track()
        try {
            return ProcessBuilder(command).directory(project).apply {
                val env = environment()
                // Project engines on this phone get the preview tools' socket; chats do not (no preview_* tools there).
                val preview = if (isChat(cwd)) emptyMap() else app.previewBroker.environment()
                val wanted = engineEnvironment(paths, env.toMap(), timeZone(), m.env, app.github.gitEnvironment() + preview)
                env.clear()
                // The project's secrets as variables (never in the model's messages).
                env.putAll(SecretEnv.inject(wanted, SecretStore.get(app).environment(project)))
                env[ProcessReaper.ENV] = token
            }.start().also { reaper.attach(token, it) }
        } catch (e: Exception) {
            reaper.untrack(token)
            throw e
        }
    }

    /**
     * A new session file for [cwd] holding only Pi's header (session-manager.ts `newSession`), so an engine running in
     * another directory can `switch_session` to it and work in [cwd].
     */
    private fun emptySession(cwd: String): String? = runCatching {
        val dir = (if (isChat(cwd)) ChatSpace.sessionDir(paths.agentDir) else index.sessionDir(cwd)).apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val timestamp = ISO_MILLIS.format(Instant.now())
        val file = File(dir, "${timestamp.replace(':', '-').replace('.', '-')}_$id.jsonl")
        val header = buildJsonObject {
            put("type", "session")
            put("version", 3)
            put("id", id)
            put("timestamp", timestamp)
            put("cwd", cwd)
        }
        file.writeText("$header\n")
        runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
        emptySessions += file.path
        file.path
    }.onFailure { Log.w(TAG, "could not create a session file in $cwd: $it") }.getOrNull()

    @Volatile
    private var zoneWritten: String? = null

    /**
     * The phone's time zone for an engine's TZ, its file written to the prefix first (ZoneInfo: the pack has no tz
     * database, and glibc and Python would use UTC without it). Blocking file I/O.
     */
    private fun timeZone(): String {
        val id = TimeZone.getDefault().id
        if (zoneWritten != id && ZoneInfo.ensure(paths.zoneinfo, id)) zoneWritten = id
        return id
    }

    /** Empty session files this engine made that no engine has open and that never got a message. */
    private fun deleteUnusedEmptySessions() {
        val inUse = buildSet {
            for (backend in idle.engines() + chatIdle.engines()) backend.sessionFile?.let(::add)
            for (session in app.sessions.value) session.sessionFile?.let(::add)
        }
        for (path in emptySessions.toList()) {
            if (path in inUse) continue
            val file = File(path)
            if ((index.summary(file)?.messageCount ?: 0) == 0) file.delete()
            emptySessions -= path
        }
    }

    /** Engine status follows the backends: ready once one answers, failed with its stderr when one cannot start. */
    private fun watch(backend: LocalBackend) {
        val job = scope.launch {
            backend.state.collect { s ->
                when {
                    s is ConnectionState.Connected -> _status.value = EngineStatus.Ready(manifest?.engine.orEmpty())
                    s is ConnectionState.Disconnected && s.reason == DisconnectReason.EngineFailed && unavailable == null ->
                        _status.value = EngineStatus.Failed(s.detail ?: "The engine could not start", backend.stderr.text(60).ifBlank { null }, canRetry = true)
                }
            }
        }
        synchronized(watchers) { watchers[backend] = job }
    }

    private fun forget(backend: LocalBackend) {
        synchronized(watchers) { watchers.remove(backend) }?.cancel()
    }

    private class AndroidAssets(private val assets: AssetManager) : AssetSource {
        override fun open(path: String): InputStream = assets.open(path)

        override fun list(path: String): List<String> = assets.list(path)?.toList().orEmpty()
    }

    companion object {
        const val TAG = "PiBoltEngine"

        /** Engine stderr in logcat. */
        const val ENGINE_TAG = "PiBoltEngineErr"
        private const val PREFS = "engine"
        private const val KEY_LAST_PROJECT = "lastProject"
        private const val OPEN_TIMEOUT_MS = 180_000L
        private const val BACKGROUND_IDLE_MS = 5 * 60_000L
        private const val TRIM_GRACE_MS = 60_000L

        /** How long a provider list waits for an idle engine to start. */
        private const val PROBE_WAIT_MS = 90_000L

        /** The helper's provider list (it checks every provider's credentials). */
        private const val HELPER_TIMEOUT_MS = 60_000L

        /** A login flow: the user signs in in the browser meanwhile. */
        private const val LOGIN_TIMEOUT_MS = 10 * 60_000L

        /** `Date.toISOString()`: always milliseconds, always Z. */
        private val ISO_MILLIS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    }
}
