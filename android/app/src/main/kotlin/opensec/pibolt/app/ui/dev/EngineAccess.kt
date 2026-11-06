package opensec.pibolt.app.ui.dev

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import opensec.pibolt.app.BuildConfig
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.engine.EngineApi
import opensec.pibolt.app.engine.EngineStatus
import opensec.pibolt.app.engine.ProviderAuth
import opensec.pibolt.app.engine.SessionSummary
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.protocol.arr
import opensec.pibolt.protocol.rpc.RpcClient
import opensec.pibolt.protocol.str
import java.io.File

/*
 * TEMPORARY (native/ui branch only): delete this file when PiBoltApp gains the real `engine: EngineApi`.
 *
 * - `app.engine` resolves to PiBoltApp's own property once it exists; this extension then is shadowed and unused.
 * - `forkFrom` stands in for a `SessionController.fork(...)` the engine branch should add (RPC `get_fork_messages` +
 *   `fork`, then re-reading the session). It reaches the controller's private RpcClient by reflection, which is only
 *   acceptable as a stop-gap.
 */

/** The engine the UI talks to: the debug fake on debug builds, an "unavailable" stub on release builds. */
val PiBoltApp.engine: EngineApi
    get() = DevEngine.of(this)

object DevEngine {
    @Volatile
    private var engine: EngineApi? = null

    fun of(app: PiBoltApp): EngineApi = engine ?: synchronized(this) {
        engine ?: (if (BuildConfig.DEBUG) FakeEngine(app) else NoEngine(app)).also { engine = it }
    }
}

/**
 * Fork the live session from the user message with [text] (its [occurrence]-th copy on the active branch, counting
 * from 0). Returns the forked message's text for the composer, or a failure with a message to show.
 */
suspend fun SessionController.forkFrom(text: String, occurrence: Int): Result<String> = runCatching {
    val field = SessionController::class.java.getDeclaredField("rpc").apply { isAccessible = true }
    val rpc = field.get(this) as RpcClient
    val list = rpc.request("get_fork_messages")
    check(list.success) { list.error ?: "Could not list messages" }
    val messages = (list.data as? JsonObject)?.arr("messages").orEmpty().mapNotNull { it as? JsonObject }
    val entry = messages.filter { it.str("text")?.trim() == text.trim() }.getOrNull(occurrence) ?: error("That message can't be forked")
    val entryId = entry.str("entryId") ?: error("That message can't be forked")
    val fork = rpc.request("fork") { put("entryId", entryId) }
    check(fork.success) { fork.error ?: "Fork failed" }
    val data = fork.data as? JsonObject
    check((data?.get("cancelled") as? JsonPrimitive)?.content != "true") { "An extension cancelled the fork" }
    data?.str("text") ?: entry.str("text").orEmpty()
}

/** Release builds of this branch: no engine. */
private class NoEngine(app: PiBoltApp) : EngineApi {
    override val status: StateFlow<EngineStatus> = MutableStateFlow(EngineStatus.Unavailable("This build does not include the on-device engine yet."))
    override val projectsDir: File = File(app.filesDir, "home/projects")

    override fun prepare() = Unit

    override fun open(project: File, sessionFile: String?, fresh: Boolean): SessionController = error("No engine in this build")

    override fun sessions(project: File): List<SessionSummary> = emptyList()

    override val auth: ProviderAuth = object : ProviderAuth {
        private val set = MutableStateFlow<Set<String>>(emptySet())
        override val configured: StateFlow<Set<String>> = set.asStateFlow()

        override fun setApiKey(provider: String, key: String) = Unit

        override fun remove(provider: String) = Unit
    }
}
