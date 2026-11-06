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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The engine on this phone (android/engine/CONTRACT.md): sets up the prefix, runs `libpibolt.so --mode rpc` processes
 * and hands sessions to the UI as [SessionController]s over [LocalBackend]s.
 *
 * Fast opens: once ready, one spare engine is kept started and idle. Opening a project or creating a session takes the
 * spare and moves it there over RPC (`switch_session`, `new_session`): no process start on the user's tap. A new spare is
 * started after each open; a session that ends while idle becomes the spare. The spare is stopped on memory pressure and
 * after [BACKGROUND_IDLE_MS] in the background.
 *
 * Engines belong to the application object (the process), not to an Activity: configuration changes and backgrounding
 * do not touch them, and SessionService keeps the process in the foreground while a turn runs. Everything that touches
 * the disk or spawns runs on [Dispatchers.IO]; nothing here blocks the main thread.
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
    private val reaper = ProcessReaper()

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

    /** The idle engine for the next open. Guarded by `this`. */
    private var spare: LocalBackend? = null
    private val watchers = HashMap<LocalBackend, Job>()

    /** Empty session files made to start a session in another project, deleted again if never used. */
    private val emptySessions: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var prepareJob: Job? = null

    @Volatile
    private var backgroundJob: Job? = null

    @Volatile
    private var inBackground = false

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
            ensureSpare()
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
        // The live session is in this project: move it over RPC.
        app.session.value?.takeIf { it.isLocal && it.project == dir && !it.stopped }?.let { live ->
            when (target) {
                SessionTarget.Continue -> Unit
                is SessionTarget.Resume -> if (target.sessionFile != live.sessionFile) live.switchSession(target.sessionFile)
                SessionTarget.Fresh -> live.newSession()
            }
            return live
        }
        val config = configFor(dir)
        val taken = synchronized(this) { spare.also { spare = null } }
        val backend = taken?.also { it.assign(config, target) } ?: newBackend(config, target)
        val controller = SessionController.local(app, dir, backend, ::release)
        app.replaceSession(controller)
        scope.launch {
            prefs.edit { putString(KEY_LAST_PROJECT, dir.path) }
            val end = withTimeoutOrNull(OPEN_TIMEOUT_MS) {
                backend.state.first { it is ConnectionState.Connected || it is ConnectionState.Disconnected }
            }
            if (end is ConnectionState.Connected) {
                val timing = OpenTiming(dir.name, SystemClock.elapsedRealtime() - started, taken != null, backend.lastStartMs)
                lastOpen = timing
                Log.i(TAG, "open ${timing.project}: ready in ${timing.readyMs} ms (${if (timing.warm) "warm" else "cold"}, engine start ${timing.engineStartMs} ms, pid ${backend.pid})")
            }
            // The next open is warm too; started only now so it does not compete with this one.
            ensureSpare()
            deleteUnusedEmptySessions()
        }
        return controller
    }

    /** Saved sessions with at least one message, newest first. Blocking file I/O: call off the main thread. */
    override fun sessions(project: File): List<SessionSummary> = index.list(project).filter { it.messageCount > 0 }

    /** Stop the spare engine (memory pressure, or long in the background). */
    fun trimMemory() {
        val idle = synchronized(this) { spare.also { spare = null } } ?: return
        Log.i(TAG, "stopping the spare engine")
        forget(idle)
        idle.close()
    }

    /** No activity is visible. The spare stops after a while unless the app comes back. */
    fun onBackground() {
        inBackground = true
        backgroundJob?.cancel()
        backgroundJob = scope.launch {
            delay(BACKGROUND_IDLE_MS)
            trimMemory()
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
        try {
            val full = EnginePrefix(paths, AndroidAssets(app.assets)).ensure(m)
            dns.start()
            Log.i(TAG, "prepare: prefix ${if (full) "installed" else "up to date"}, ${SystemClock.elapsedRealtime() - started} ms (engine ${m.engine}, glibc ${m.glibc})")
        } catch (e: Exception) {
            Log.e(TAG, "prefix setup failed", e)
            _status.value = EngineStatus.Failed("Could not set up the engine", e.toString(), canRetry = true)
            return null
        }
        manifest = m
        if (_status.value !is EngineStatus.Ready) _status.value = EngineStatus.Ready(m.engine)
        m
    }

    /** Start the spare engine unless there is one (or the app is in the background). */
    private suspend fun ensureSpare() {
        if (inBackground || ensureSetup() == null) return
        val home = prefs.getString(KEY_LAST_PROJECT, null)?.let(::File)?.takeIf { it.isDirectory } ?: paths.projects
        val backend = synchronized(this) {
            if (spare != null) return
            newBackend(configFor(home), SessionTarget.Fresh).also { spare = it }
        }
        if (_status.value !is EngineStatus.Ready) _status.value = EngineStatus.Preparing("Starting the engine")
        if (backend.warmUp()) {
            Log.i(TAG, "spare engine ready: cold spawn to first answer ${backend.lastStartMs} ms (pid ${backend.pid}, in ${home.name})")
        } else {
            synchronized(this) { if (spare === backend) spare = null }
            forget(backend)
        }
    }

    /** A session stopped. An idle engine becomes the spare (replacing the old one); a busy one is stopped. */
    private fun release(backend: LocalBackend, idle: Boolean) {
        if (idle && backend.isAlive && manifest != null) {
            backend.park()
            val previous = synchronized(this) { spare.also { spare = backend } }
            previous?.takeIf { it !== backend }?.let {
                forget(it)
                it.close()
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

    private fun configFor(project: File) = LocalConfig(name = project.name, version = manifest?.engine.orEmpty(), cwd = project.path)

    private fun newBackend(config: LocalConfig, target: SessionTarget): LocalBackend {
        val backend = LocalBackend(
            config = config,
            target = target,
            launcher = launcher,
            newestSession = { cwd -> sessions(File(cwd)).firstOrNull()?.file },
            freshSessionFile = ::emptySession,
            killer = reaper,
            onStderr = { Log.i(ENGINE_TAG, it) },
        )
        watch(backend)
        return backend
    }

    private val launcher = EngineLauncher { target, cwd ->
        val m = ensureSetup() ?: throw EngineStartException(unavailable ?: (_status.value as? EngineStatus.Failed)?.message ?: "The engine is not available")
        val project = File(cwd)
        project.mkdirs()
        if (!project.isDirectory) throw EngineStartException("Cannot create the project folder $cwd")
        val command = listOf(paths.engine.path, "--mode", "rpc") + target.cliArgs()
        Log.i(TAG, "starting engine ${m.engine} in ${project.name} ${target.cliArgs().joinToString(" ")}")
        ProcessBuilder(command).directory(project).apply {
            val env = environment()
            val wanted = engineEnvironment(paths, env.toMap(), TimeZone.getDefault().id, m.env)
            env.clear()
            env.putAll(wanted)
        }.start()
    }

    /**
     * A new session file for [cwd] holding only Pi's header (session-manager.ts `newSession`), so an engine running in
     * another directory can `switch_session` to it and work in [cwd].
     */
    private fun emptySession(cwd: String): String? = runCatching {
        val dir = index.sessionDir(cwd).apply { mkdirs() }
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

    /** Empty session files this engine made that no engine has open and that never got a message. */
    private fun deleteUnusedEmptySessions() {
        val inUse = buildSet {
            synchronized(this@LocalEngine) { spare?.sessionFile?.let(::add) }
            app.session.value?.sessionFile?.let(::add)
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

        /** `Date.toISOString()`: always milliseconds, always Z. */
        private val ISO_MILLIS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    }
}
