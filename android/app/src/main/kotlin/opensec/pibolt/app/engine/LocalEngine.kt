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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import opensec.pibolt.app.PiBoltApp
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
import java.util.TimeZone

/**
 * The engine on this phone (android/engine/CONTRACT.md): sets up the prefix, starts `libpibolt.so --mode rpc` per
 * project and hands sessions to the UI as [SessionController]s over a [LocalBackend].
 *
 * Fast opens: Pi's working directory is fixed per process, so the engine keeps at most one idle warm engine, for the
 * most recently opened project: [prepare] starts it, and a session that ends while idle is kept as the warm engine
 * instead of being stopped. Opening that project then only publishes a snapshot (or switches the session over RPC).
 * Everything that touches the disk or spawns runs on [Dispatchers.IO]; nothing here blocks the main thread.
 */
class LocalEngine(private val app: PiBoltApp) : EngineApi {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val paths = EnginePaths(
        prefix = EnginePaths.prefixFor(app.packageName),
        dataDir = File(app.applicationInfo.dataDir),
        nativeLibDir = File(app.applicationInfo.nativeLibraryDir),
    )
    private val index = SessionIndex(paths.agentDir)
    private val dns = NetworkDns(app, paths.etc, scope)

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Idle)
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()

    override val projectsDir: File get() = paths.projects

    override val auth: AuthFile = AuthFile(paths.agentDir, scope)

    private val setupMutex = Mutex()

    @Volatile
    private var manifest: EngineManifest? = null

    @Volatile
    private var unavailable: String? = null

    private val prefs by lazy { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** The idle warm engine and the project it runs in. Guarded by `this`. */
    private var warm: Pair<String, LocalBackend>? = null
    private val watchers = HashMap<LocalBackend, Job>()

    @Volatile
    private var prepareJob: Job? = null

    /** The last open: milliseconds from [open] to the first snapshot, and whether a warm engine served it. */
    @Volatile
    var lastOpen: OpenTiming? = null
        private set

    data class OpenTiming(val project: String, val readyMs: Long, val warm: Boolean, val engineStartMs: Long?)

    override fun prepare() {
        if (prepareJob?.isActive == true) return
        prepareJob = scope.launch {
            if (ensureSetup() == null) return@launch
            auth.refresh()
            warmUp()
        }
    }

    override fun open(project: File, sessionFile: String?, fresh: Boolean): SessionController {
        val started = SystemClock.elapsedRealtime()
        val dir = project.absoluteFile
        val target = when {
            sessionFile != null -> SessionTarget.Resume(sessionFile)
            fresh -> SessionTarget.Fresh
            else -> SessionTarget.Continue
        }
        // The live session is in this project: move it over RPC instead of starting an engine.
        app.session.value?.takeIf { it.isLocal && it.project == dir && !it.stopped }?.let { live ->
            when (target) {
                SessionTarget.Continue -> Unit
                is SessionTarget.Resume -> if (target.sessionFile != live.sessionFile) live.switchSession(target.sessionFile)
                SessionTarget.Fresh -> live.newSession()
            }
            return live
        }
        val reused = takeWarm(dir.path)
        val backend = reused?.also { it.target = target } ?: newBackend(dir, target)
        val controller = SessionController.local(app, dir, backend, ::release)
        app.replaceSession(controller)
        scope.launch {
            prefs.edit { putString(KEY_LAST_PROJECT, dir.path) }
            val end = withTimeoutOrNull(OPEN_TIMEOUT_MS) {
                backend.state.first { it is ConnectionState.Connected || it is ConnectionState.Disconnected }
            }
            if (end is ConnectionState.Connected) {
                val timing = OpenTiming(dir.name, SystemClock.elapsedRealtime() - started, reused != null, backend.lastStartMs)
                lastOpen = timing
                Log.i(TAG, "open ${timing.project}: ready in ${timing.readyMs} ms (${if (timing.warm) "warm" else "cold"}, engine start ${timing.engineStartMs} ms, pid ${backend.pid})")
            }
        }
        return controller
    }

    override fun sessions(project: File): List<SessionSummary> = index.list(project)

    /** Stop the idle warm engine (memory pressure). */
    fun trimMemory() {
        val idle = synchronized(this) { warm.also { warm = null } } ?: return
        Log.i(TAG, "stopping the warm engine for ${idle.first} (memory)")
        forget(idle.second)
        idle.second.close()
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

    /** Checks the device and the pack, and brings the prefix up to date. Null when the engine cannot run. */
    private suspend fun ensureSetup(): EngineManifest? = setupMutex.withLock {
        manifest?.let { return it }
        unavailable?.let { return null }
        val loaded = runCatching { app.assets.open(EnginePrefix.MANIFEST_ASSET).use { EngineManifest.parse(it.reader().readText()) } }
        val reason = engineUnavailableReason(
            applicationId = app.packageName,
            abis = Build.SUPPORTED_ABIS.toList(),
            sdk = Build.VERSION.SDK_INT,
            manifest = loaded.getOrNull(),
            engineShipped = paths.engine.isFile,
            dataDirReal = runCatching { paths.dataDir.canonicalPath }.getOrDefault(paths.dataDir.path),
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
        val started = SystemClock.elapsedRealtime()
        try {
            val full = EnginePrefix(paths, AndroidAssets(app.assets)).ensure(m)
            dns.start()
            Log.i(TAG, "prefix ${if (full) "installed" else "up to date"} in ${SystemClock.elapsedRealtime() - started} ms (engine ${m.engine}, glibc ${m.glibc})")
        } catch (e: Exception) {
            Log.e(TAG, "prefix setup failed", e)
            _status.value = EngineStatus.Failed("Could not set up the engine", e.toString(), canRetry = true)
            return null
        }
        manifest = m
        if (_status.value !is EngineStatus.Ready) _status.value = EngineStatus.Ready(m.engine)
        m
    }

    /** Start a warm engine for the most recently opened project, unless one is live or idle already. */
    private suspend fun warmUp() {
        val project = prefs.getString(KEY_LAST_PROJECT, null)?.let(::File)?.takeIf { it.isDirectory } ?: return
        val live = app.session.value
        if (live != null && live.isLocal && !live.stopped) return
        val backend = synchronized(this) {
            if (warm != null) return
            newBackend(project, SessionTarget.Continue).also { warm = project.path to it }
        }
        if (_status.value !is EngineStatus.Ready) _status.value = EngineStatus.Preparing("Starting the engine")
        if (backend.warmUp()) {
            Log.i(TAG, "warm engine for ${project.name} ready in ${backend.lastStartMs} ms (pid ${backend.pid})")
        } else {
            synchronized(this) { if (warm?.second === backend) warm = null }
            forget(backend)
        }
    }

    /** The warm engine when it runs in [project] (still starting is fine; a dead one respawns on connect). */
    private fun takeWarm(project: String): LocalBackend? = synchronized(this) {
        val (path, backend) = warm ?: return null
        if (path != project) return null
        warm = null
        backend
    }

    /** A session stopped. An idle engine becomes the warm engine (replacing any other); a busy one is stopped. */
    private fun release(backend: LocalBackend, idle: Boolean) {
        val project = backend.config.cwd
        if (idle && backend.isAlive && manifest != null) {
            backend.park()
            val previous = synchronized(this) { warm.also { warm = project to backend } }
            previous?.second?.takeIf { it !== backend }?.let {
                forget(it)
                it.close()
            }
            return
        }
        forget(backend)
        scope.launch {
            backend.closeAndAwait()
            warmUp()
        }
    }

    private fun newBackend(project: File, target: SessionTarget): LocalBackend {
        val backend = LocalBackend(
            config = LocalConfig(name = project.name, version = manifest?.engine.orEmpty(), cwd = project.path),
            target = target,
            launcher = launcher(project),
            newestSession = { index.newest(project) },
            onStderr = { Log.i(ENGINE_TAG, it) },
        )
        watch(backend)
        return backend
    }

    private fun launcher(project: File) = EngineLauncher { target ->
        val m = ensureSetup() ?: throw EngineStartException(unavailable ?: (_status.value as? EngineStatus.Failed)?.message ?: "The engine is not available")
        project.mkdirs()
        if (!project.isDirectory) throw EngineStartException("Cannot create the project folder ${project.path}")
        val command = listOf(paths.engine.path, "--mode", "rpc") + target.cliArgs()
        Log.i(TAG, "starting engine ${m.engine} in ${project.name} ${target.cliArgs().joinToString(" ")}")
        ProcessBuilder(command).directory(project).apply {
            val env = environment()
            val wanted = engineEnvironment(paths, env.toMap(), TimeZone.getDefault().id, m.env)
            env.clear()
            env.putAll(wanted)
        }.start()
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
    }
}
