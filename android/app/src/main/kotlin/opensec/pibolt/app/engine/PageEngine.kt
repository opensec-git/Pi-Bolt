package opensec.pibolt.app.engine

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.app.ui.session.features2.PiJsonFile
import opensec.pibolt.protocol.backend.ConnectionState
import opensec.pibolt.protocol.backend.LocalBackend
import opensec.pibolt.protocol.rpc.RpcClient
import opensec.pibolt.protocol.session.DialogKind
import opensec.pibolt.protocol.session.MobileViews
import opensec.pibolt.protocol.session.PageInfo
import opensec.pibolt.protocol.session.SessionReducer
import opensec.pibolt.protocol.session.SessionState
import opensec.pibolt.protocol.session.VIEWS_FEATURE
import java.io.File

/**
 * The page engine (docs/mobile-ui.md#package-pages-and-home-tiles): Pi without a session, on this phone, that builds
 * packages' pages for Settings → Packages and their tiles for Home. [LocalEngine.pageBackend] starts it with the same
 * launch as a session's engine; this class decides when it runs ([PageRules]): while something holds it, never while
 * the phone is low on memory, and until 30 s after the last hold ended. The page list is kept in `pages.json`
 * ([PageCatalog]) and read again when the packages change.
 */
class PageEngine(private val context: Context, private val engine: LocalEngine) {
    sealed interface Status {
        data object Idle : Status
        data object Starting : Status
        data object Ready : Status

        /** Not started: the phone is low on memory. */
        data object LowMemory : Status
        data class Failed(val message: String) : Status
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val cacheFile = File(context.filesDir, "pages.json")

    private var backend: LocalBackend? = null
    private var rpc: RpcClient? = null
    private var pump: Job? = null

    /** The packages the running engine loaded ([packagesKey] when it started): its page list is for these. */
    private var launchedKey: String? = null
    private var stopJob: Job? = null
    private val holders = LinkedHashSet<String>()

    /** Exits of the engine soon after it started, in a row: past [MAX_QUICK_EXITS] it is not restarted by itself. */
    private var quickExits = 0

    /** Page closes still running, by key: a reopen waits for its close. */
    private val closing = HashMap<String, Job>()

    /** The tiles Home wants now (the latest [showTiles]); [tileMutex] runs one reconcile at a time. */
    @Volatile
    private var wantedTiles: Set<String> = emptySet()
    private val tileMutex = Mutex()

    /** Keys opened on the running engine: opened again after a restart. */
    private val opened = LinkedHashSet<String>()

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** The engine's views (pages and tiles) and open sheets, reduced as a session's are. */
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _pages = MutableStateFlow<List<PageInfo>?>(null)

    /** The registered pages and tiles; null until read (from the cache or the engine). */
    val pages: StateFlow<List<PageInfo>?> = _pages.asStateFlow()

    private fun lowMemory(): Boolean {
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.lowMemory
    }

    private fun packagesKey(): String =
        PageCatalog.key(PiJsonFile.read(File(engine.agentDir, "settings.json")), engineVersion(), PageCatalog.installStamp(engine.agentDir))

    private suspend fun currentKey(): String = withContext(Dispatchers.IO) { packagesKey() }

    private fun engineVersion(): String = (engine.status.value as? EngineStatus.Ready)?.engineVersion.orEmpty()

    /**
     * The page list for the current packages: the cache when it is for them, else read from the engine (started for
     * it and stopped again after the delay). [force] reads it again anyway.
     */
    fun refreshPages(force: Boolean = false) {
        scope.launch {
            val key = packagesKey()
            if (!force) {
                val cached = PageCatalog.valid(PageCatalog.decode(runCatching { cacheFile.readText() }.getOrNull()), key)
                if (cached != null) {
                    _pages.value = cached
                    return@launch
                }
            }
            hold(PageRules.HOLD_LIST)
            try {
                if (ensureRunning()) readPages()
            } finally {
                release(PageRules.HOLD_LIST)
            }
        }
    }

    private suspend fun readPages() {
        val response = rpc?.getPages() ?: return
        if (!response.success) {
            Log.w(TAG, "get_pages: ${response.error}")
            _pages.value = emptyList()
            return
        }
        val pages = MobileViews.pages(response.dataObject)
        _pages.value = pages
        // Cached for the packages the engine loaded, not those of now: a change since then misses the cache.
        val key = launchedKey ?: return
        val text = PageCatalog.encode(PageCatalog.Cached(key, pages))
        runCatching { cacheFile.writeText(text) }
    }

    /** Opens the registered page or tile [key] (holding the engine while it is open). False when it could not. */
    suspend fun open(key: String): Boolean {
        // A close of this page still running finishes first: its release must not drop the hold taken below.
        synchronized(closing) { closing[key] }?.join()
        hold(PageRules.HOLD_PAGE + key)
        if (!ensureRunning()) {
            release(PageRules.HOLD_PAGE + key)
            return false
        }
        val response = rpc?.openPage(key)
        if (response?.success != true) {
            Log.w(TAG, "open_page $key: ${response?.error}")
            // A timed-out open may still finish on the engine: closed first, so no late view shows without a hold.
            rpc?.closePage(key)
            _state.update { it.copy(views = it.views - key) }
            release(PageRules.HOLD_PAGE + key)
            return false
        }
        synchronized(opened) { opened += key }
        return true
    }

    /** Closes [key] on the engine and lets the engine stop when nothing else holds it. */
    fun close(key: String) {
        synchronized(opened) { opened -= key }
        val job = scope.launch { closeNow(key) }
        synchronized(closing) { closing[key] = job }
        job.invokeOnCompletion { synchronized(closing) { if (closing[key] === job) closing.remove(key) } }
    }

    private suspend fun closeNow(key: String) {
        rpc?.closePage(key)
        // Opened again while this close ran: the newer open owns the view.
        if (synchronized(opened) { key !in opened }) _state.update { it.copy(views = it.views - key) }
        release(PageRules.HOLD_PAGE + key)
    }

    /**
     * Home is on screen with [keys] turned on (empty: Home left or shows none): opens the tiles not open yet, closes
     * the others, and holds the engine while any is shown.
     */
    fun showTiles(keys: Set<String>) {
        wantedTiles = keys
        scope.launch { tileMutex.withLock { reconcileTiles() } }
    }

    /**
     * Brings the open tiles to the latest [wantedTiles], one call at a time: a call that runs late still uses the latest
     * set, and a tile opened while Home changed is closed by the call queued behind it.
     */
    private suspend fun reconcileTiles() {
        val tiles = _pages.value.orEmpty().filter { it.isTile }.map { it.key }.toSet()
        val wanted = wantedTiles intersect tiles
        val open = synchronized(opened) { opened.filter { it in tiles }.toSet() }
        // Closed here, not launched: a reopen queued behind this reconcile comes after the close.
        for (key in open - wanted) {
            synchronized(opened) { opened -= key }
            closeNow(key)
        }
        if (wanted.isEmpty()) {
            release(PageRules.HOLD_HOME)
            return
        }
        hold(PageRules.HOLD_HOME)
        if (!ensureRunning()) return
        for (key in wanted - open) {
            if (key !in wantedTiles) continue
            val response = rpc?.openPage(key)
            if (response?.success == true) {
                synchronized(opened) { opened += key }
            } else {
                Log.w(TAG, "open_page $key: ${response?.error}")
                rpc?.closePage(key)
                _state.update { it.copy(views = it.views - key) }
            }
        }
        // Home changed while these opened: a call is queued behind this one and closes what is no longer wanted.
    }

    /** A view's event, for the extension. */
    fun event(viewKey: String, nodeId: String, kind: String, value: JsonPrimitive?, values: JsonObject?) {
        rpc?.viewEvent(viewKey, nodeId, kind, value, values)
    }

    fun answerSheet(id: String, nodeId: String, values: JsonObject) {
        rpc?.respondView(id, nodeId, values)
        _state.update { s -> s.copy(dialogs = s.dialogs.filterNot { it.id == id }) }
    }

    fun cancelSheet(id: String) {
        rpc?.respondCancel(id)
        _state.update { s -> s.copy(dialogs = s.dialogs.filterNot { it.id == id }) }
    }

    /** Memory pressure: stops at once unless a page is open (and [critical] is false). */
    fun trim(critical: Boolean) {
        scope.launch {
            mutex.withLock {
                if (backend == null || !PageRules.stopsOnTrim(synchronized(holders) { holders.toSet() }, critical)) return@withLock
                Log.i(TAG, "memory: stopping the page engine")
                stopLocked()
            }
        }
    }

    /**
     * A page sheet is on screen again: an engine stopped for memory while the app was away starts again and reopens
     * the pages still held, instead of leaving the sheet on "Opening".
     */
    fun resume() {
        if (synchronized(holders) { holders.none { it.startsWith(PageRules.HOLD_PAGE) } }) return
        scope.launch { if (backend == null) ensureRunning() }
    }

    private fun hold(reason: String) {
        synchronized(holders) { holders += reason }
        stopJob?.cancel()
        stopJob = null
    }

    private fun release(reason: String) {
        synchronized(holders) { holders -= reason }
        stopLaterIfIdle()
    }

    /** Nothing holds the running engine: it stops after the delay unless something holds it again. */
    private fun stopLaterIfIdle() {
        val holds = synchronized(holders) { holders.size }
        if (PageRules.decide(holds, backend != null, lowMemory = false) != PageRules.Decision.StopLater) return
        stopJob?.cancel()
        stopJob = scope.launch {
            delay(PageRules.STOP_DELAY_MS)
            mutex.withLock {
                if (synchronized(holders) { holders.isEmpty() }) stopLocked()
            }
        }
    }

    /**
     * Starts the engine if needed; true when it is connected and renders views. The start runs in the engine's own
     * scope: a caller that goes away meanwhile (a sheet dismissed during a cold start) does not cut it off half way.
     */
    private suspend fun ensureRunning(): Boolean = scope.async { startIfNeeded() }.await()

    private suspend fun startIfNeeded(): Boolean = mutex.withLock {
        val holds = synchronized(holders) { holders.size }
        val key = currentKey()
        val decision = PageRules.decide(holds, backend?.isAlive == true, lowMemory())
        // Packages installed, removed or turned off since the engine started: it starts again to load them.
        val stale = decision == PageRules.Decision.Keep && launchedKey != key
        when {
            decision == PageRules.Decision.Keep && !stale -> return@withLock true
            decision == PageRules.Decision.Refuse || (stale && lowMemory()) -> {
                _status.value = Status.LowMemory
                return@withLock false
            }
            decision == PageRules.Decision.Start || stale -> Unit
            else -> return@withLock false
        }
        stopLocked()
        _status.value = Status.Starting
        launchedKey = key
        val b = engine.pageBackend()
        if (b == null) {
            _status.value = Status.Failed("The engine is not available on this phone")
            return@withLock false
        }
        val client = RpcClient(b, scope)
        backend = b
        rpc = client
        pump = scope.launch { client.events.collect(::onEvent) }
        val connected = try {
            b.connect()
        } catch (e: CancellationException) {
            stopLocked()
            throw e
        } catch (e: Exception) {
            // A start that throws leaves nothing half made: the next open starts afresh.
            Log.w(TAG, "page engine start failed", e)
            stopLocked()
            _status.value = Status.Failed(e.message ?: "The page engine could not start")
            return@withLock false
        }
        if (!connected) {
            _status.value = Status.Failed(b.stderr.text(3).ifBlank { "The page engine could not start" })
            stopLocked()
            return@withLock false
        }
        val features = client.setClientFeatures(listOf(VIEWS_FEATURE))
        if (!features.success) {
            _status.value = Status.Failed(features.error ?: "The engine does not render views")
            stopLocked()
            return@withLock false
        }
        _status.value = Status.Ready
        Log.i(TAG, "page engine ready (pid ${b.pid})")
        watchExit(b, System.currentTimeMillis())
        // A restart (the engine stopped while held): what was open opens again.
        val again = synchronized(opened) { opened.toList() }
        synchronized(opened) { opened.clear() }
        for (key in again) if (client.openPage(key).success) synchronized(opened) { opened += key }
        // Whoever asked for it may have gone while it started (a sheet dismissed): it stops after the delay then.
        stopLaterIfIdle()
        true
    }

    /**
     * The engine process ended by itself (not stopped here): its views go, and while pages or tiles still hold it, it
     * starts again and reopens them. One that keeps exiting soon after starting is left stopped, with the reason shown.
     */
    private fun watchExit(b: LocalBackend, startedAt: Long) {
        scope.launch {
            val end = b.state.first { it is ConnectionState.Disconnected } as ConnectionState.Disconnected
            val restart = mutex.withLock {
                if (backend !== b) return@launch
                Log.w(TAG, "page engine exited: ${end.reason} ${end.detail.orEmpty()}")
                stopLocked()
                quickExits = if (System.currentTimeMillis() - startedAt < QUICK_EXIT_MS) quickExits + 1 else 0
                val again = quickExits < MAX_QUICK_EXITS && synchronized(holders) { holders.isNotEmpty() }
                if (!again) _status.value = Status.Failed(end.detail ?: "The page engine stopped")
                again
            }
            if (restart) ensureRunning()
        }
    }

    private fun onEvent(event: JsonObject) {
        val before = _state.value
        val next = SessionReducer.reduce(before, event)
        _state.value = next
        // The page engine shows the kit's sheets; other dialogs (select, confirm, input) have no screen here.
        for (dialog in next.dialogs) {
            if (dialog.kind != DialogKind.View && before.dialogs.none { it.id == dialog.id }) cancelSheet(dialog.id)
        }
    }

    private fun stopLocked() {
        val b = backend ?: return
        backend = null
        launchedKey = null
        pump?.cancel()
        pump = null
        rpc?.stop()
        rpc = null
        engine.releasePageBackend(b)
        _state.value = SessionState()
        if (_status.value !is Status.Failed && _status.value !is Status.LowMemory) _status.value = Status.Idle
        Log.i(TAG, "page engine stopped")
    }

    companion object {
        private const val TAG = "PiBoltPages"
        private const val QUICK_EXIT_MS = 30_000L
        private const val MAX_QUICK_EXITS = 3
    }
}
