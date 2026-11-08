package opensec.pibolt.app.ui.providers

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import opensec.pibolt.app.engine.EngineApi
import opensec.pibolt.app.engine.EngineProvider
import opensec.pibolt.app.engine.ModelsFile
import opensec.pibolt.app.engine.ProviderReport
import opensec.pibolt.app.engine.ProviderRules
import opensec.pibolt.protocol.ProtocolJson
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The providers on this phone as the app shows them: keys in Pi's auth.json ([EngineApi.auth]), custom endpoints in
 * models.json, and what the engine reports ([EngineApi.providers]: the providers Pi packages register, the models that
 * work). [connected] is "a model works" for Home, the chat pill and the default-model picker. The last report is kept
 * in [cache] (no credentials in it), so a cold start knows about extension providers before any engine answers.
 *
 * All disk and engine work runs on [scope] (an I/O scope).
 */
class ProviderDirectory(private val engine: EngineApi, private val cache: File, private val scope: CoroutineScope) {
    private val _report = MutableStateFlow<ProviderReport?>(null)
    val report: StateFlow<ProviderReport?> = _report.asStateFlow()

    /** The custom endpoints' ids (models.json). */
    private val _custom = MutableStateFlow<Set<String>>(emptySet())
    val custom: StateFlow<Set<String>> = _custom.asStateFlow()

    private val cacheRead = MutableStateFlow(false)

    /** True once the stored keys and the last report are read: Home decides "no model yet" only then. */
    val loaded: StateFlow<Boolean> = combine(engine.auth.loaded, cacheRead) { a, c -> a && c }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /** Ids of the providers with a model that works ([ProviderRules.connected]). */
    val connected: StateFlow<Set<String>> = combine(engine.auth.configured, _report, _custom) { stored, report, custom ->
        ProviderRules.connected(stored, report, ProviderCatalog.ids + custom)
    }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** The providers extensions add, for Settings and the provider sheet. */
    val extensions: StateFlow<List<EngineProvider>> = combine(_report, _custom) { report, custom ->
        ProviderRules.extensionProviders(report, ProviderCatalog.ids, custom)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private var job: Job? = null
    private var again = false
    private var lastAsk = 0L

    init {
        scope.launch {
            val cached = runCatching { ProviderReport.from(ProtocolJson.parseToJsonElement(cache.readText()) as? JsonObject) }.getOrNull()
            cached?.let { ProviderCatalog.registerExtensions(it.providers) }
            // A report that came in meanwhile is newer.
            if (_report.value == null) _report.value = cached
            cacheRead.value = true
            readCustom()
        }
    }

    /** Asks the engine again (after a key, a sign-in or a package changed). Calls during a request run it once more after. */
    @Synchronized
    fun refresh() {
        if (job?.isActive == true) {
            again = true
            return
        }
        lastAsk = SystemClock.elapsedRealtime()
        job = scope.launch {
            do {
                synchronized(this@ProviderDirectory) { again = false }
                readCustom()
                val next = runCatching { engine.providers() }.onFailure { Log.w(TAG, "providers: ${it.message}") }.getOrNull()
                if (next != null) {
                    _report.value = next
                    save(next)
                    ProviderCatalog.registerExtensions(next.providers)
                }
            } while (synchronized(this@ProviderDirectory) { again })
        }
    }

    /** [refresh] unless the engine was asked less than [maxAgeMs] ago (Home on resume). */
    fun refreshIfOlder(maxAgeMs: Long = 60_000) {
        if (lastAsk == 0L || SystemClock.elapsedRealtime() - lastAsk > maxAgeMs) refresh()
    }

    private suspend fun readCustom() {
        val list = ModelsFile.custom(engine.agentDir)
        ProviderCatalog.registerCustom(list)
        _custom.value = list.map { it.id }.toSet()
    }

    private fun save(report: ProviderReport) {
        runCatching {
            cache.parentFile?.mkdirs()
            val temp = File(cache.parentFile, "${cache.name}.${System.nanoTime()}.tmp")
            temp.writeText(report.toJson().toString())
            Files.move(temp.toPath(), cache.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private companion object {
        const val TAG = "PiBoltProviders"
    }
}
