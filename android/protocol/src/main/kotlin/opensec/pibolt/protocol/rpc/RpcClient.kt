package opensec.pibolt.protocol.rpc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import opensec.pibolt.protocol.arr
import opensec.pibolt.protocol.backend.ConnectionState
import opensec.pibolt.protocol.backend.SessionBackend
import opensec.pibolt.protocol.bool
import opensec.pibolt.protocol.str
import opensec.pibolt.protocol.type
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A command's answer. [data] is the raw `data` field; typed helpers below read it. */
data class RpcResponse(val command: String, val success: Boolean, val data: JsonElement?, val error: String?) {
    val dataObject: JsonObject? get() = data as? JsonObject

    companion object {
        fun failure(command: String, error: String) = RpcResponse(command, false, null, error)
    }
}

enum class StreamingBehavior(val wire: String) {
    Steer("steer"),
    FollowUp("followUp"),
}

data class ImageAttachment(val base64: String, val mimeType: String)

/**
 * Pi's RPC protocol over a [SessionBackend]: commands with ids, responses matched to them, and everything else
 * (events, extension UI requests) on [events].
 */
class RpcClient(private val backend: SessionBackend, scope: CoroutineScope) {
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<RpcResponse>>()
    private val eventChannel = Channel<JsonObject>(Channel.UNLIMITED)

    /** Events and extension UI requests in arrival order. Collect from one place only. */
    val events: Flow<JsonObject> = eventChannel.receiveAsFlow()

    private val pump: Job = scope.launch {
        backend.incoming.collect { message ->
            if (message.type == "response") {
                val id = message.str("id")
                val waiter = id?.let { pending.remove(it) }
                if (waiter != null) {
                    waiter.complete(
                        RpcResponse(
                            command = message.str("command") ?: "",
                            success = message.bool("success") ?: false,
                            data = message["data"],
                            error = message.str("error"),
                        ),
                    )
                    return@collect
                }
            }
            eventChannel.send(message)
        }
    }

    private val watcher: Job = scope.launch {
        backend.state.collect { state ->
            if (state !is ConnectionState.Connected) failPending("Disconnected")
        }
    }

    private fun failPending(reason: String) {
        val waiters = pending.values.toList()
        pending.clear()
        waiters.forEach { it.complete(RpcResponse.failure("", reason)) }
    }

    fun stop() {
        pump.cancel()
        watcher.cancel()
        failPending("Stopped")
    }

    /** Send [type] with fields from [fields]; waits up to [timeoutMs] for the response. */
    suspend fun request(type: String, timeoutMs: Long = 30_000, fields: JsonObjectBuilder.() -> Unit = {}): RpcResponse {
        val id = "a${ids.incrementAndGet()}"
        val command = buildJsonObject {
            put("id", id)
            put("type", type)
            fields()
        }
        val waiter = CompletableDeferred<RpcResponse>()
        pending[id] = waiter
        if (!backend.send(command)) {
            pending.remove(id)
            return RpcResponse.failure(type, "Not connected")
        }
        return withTimeoutOrNull(timeoutMs) { waiter.await() } ?: run {
            pending.remove(id)
            RpcResponse.failure(type, "No answer from the computer")
        }
    }

    // Prompting

    /**
     * For an extension command (`/name`) the response arrives only after the command finishes, and its dialogs arrive
     * before that: callers must not block anything else on this response. It waits until the connection drops.
     */
    suspend fun prompt(message: String, images: List<ImageAttachment> = emptyList(), streamingBehavior: StreamingBehavior? = null) =
        request("prompt", timeoutMs = Long.MAX_VALUE) {
            put("message", message)
            putImages(images)
            streamingBehavior?.let { put("streamingBehavior", it.wire) }
        }

    suspend fun steer(message: String, images: List<ImageAttachment> = emptyList()) =
        request("steer") { put("message", message); putImages(images) }

    suspend fun followUp(message: String, images: List<ImageAttachment> = emptyList()) =
        request("follow_up") { put("message", message); putImages(images) }

    /** Abort waits until the session is idle, which can take a while. */
    suspend fun abort() = request("abort", timeoutMs = 120_000)

    suspend fun clearQueue() = request("clear_queue")

    // State

    suspend fun getState() = request("get_state")

    suspend fun getMessages() = request("get_messages", timeoutMs = 60_000)

    suspend fun getCommands() = request("get_commands")

    suspend fun setSessionName(name: String) = request("set_session_name") { put("name", name) }

    // Model and thinking

    suspend fun getAvailableModels() = request("get_available_models")

    suspend fun setModel(provider: String, modelId: String) = request("set_model") {
        put("provider", provider)
        put("modelId", modelId)
    }

    suspend fun getAvailableThinkingLevels() = request("get_available_thinking_levels")

    suspend fun setThinkingLevel(level: String) = request("set_thinking_level") { put("level", level) }

    // Compaction

    /** Compaction runs a model call: give it time. */
    suspend fun compact(customInstructions: String? = null) = request("compact", timeoutMs = 600_000) {
        customInstructions?.takeIf { it.isNotBlank() }?.let { put("customInstructions", it) }
    }

    // Sessions and shell (a local engine accepts these; remote control refuses them)

    /**
     * `data.cancelled` is true when an extension cancelled it. A `remote_hello` with the new session follows. [cwd]: a
     * project from [listProjects] (the background service only; default: the open session's folder).
     */
    suspend fun newSession(parentSession: String? = null, cwd: String? = null) = request("new_session", timeoutMs = 60_000) {
        parentSession?.let { put("parentSession", it) }
        cwd?.let { put("cwd", it) }
    }

    /** The background service's projects (`host.service`): [RemoteProject.listFrom] reads the answer. */
    suspend fun listProjects() = request("list_projects")

    /** The background service's sessions in [cwd] (default: the open session's folder): [RemoteSessionInfo.listFrom]. */
    suspend fun listSessions(cwd: String? = null) = request("list_sessions") { cwd?.let { put("cwd", it) } }

    suspend fun switchSession(sessionPath: String) = request("switch_session", timeoutMs = 60_000) { put("sessionPath", sessionPath) }

    /** Fork from a user message ([getForkMessages] lists them); `data.text` is that message, for the composer. */
    suspend fun fork(entryId: String) = request("fork", timeoutMs = 60_000) { put("entryId", entryId) }

    suspend fun clone() = request("clone", timeoutMs = 60_000)

    suspend fun getForkMessages() = request("get_fork_messages")

    suspend fun getSessionStats() = request("get_session_stats")

    suspend fun exportHtml(outputPath: String? = null) = request("export_html", timeoutMs = 120_000) {
        outputPath?.let { put("outputPath", it) }
    }

    /**
     * Run a shell command in the project (output also streams as `bash_execution_update`). Waits until it finishes or the
     * engine stops; [abortBash] cancels it.
     */
    suspend fun bash(command: String, excludeFromContext: Boolean = false) = request("bash", timeoutMs = Long.MAX_VALUE) {
        put("command", command)
        if (excludeFromContext) put("excludeFromContext", true)
    }

    suspend fun abortBash() = request("abort_bash")

    // Extension UI

    fun respondValue(id: String, value: String): Boolean =
        backend.send(buildJsonObject { put("type", "extension_ui_response"); put("id", id); put("value", value) })

    fun respondConfirm(id: String, confirmed: Boolean): Boolean =
        backend.send(buildJsonObject { put("type", "extension_ui_response"); put("id", id); put("confirmed", confirmed) })

    fun respondCancel(id: String): Boolean =
        backend.send(buildJsonObject { put("type", "extension_ui_response"); put("id", id); put("cancelled", true) })

    private fun JsonObjectBuilder.putImages(images: List<ImageAttachment>) {
        if (images.isNotEmpty()) put("images", imagesJson(images))
    }
}

/** Pi's `ImageContent[]` for `prompt`, `steer` and `follow_up`: `{ type: "image", data: <base64>, mimeType }`. */
fun imagesJson(images: List<ImageAttachment>): JsonArray = JsonArray(
    images.map {
        buildJsonObject {
            put("type", "image")
            put("data", it.base64)
            put("mimeType", it.mimeType)
        }
    },
)

/** A model from `get_available_models`, `set_model` or the session state. */
data class ModelInfo(
    val id: String,
    val name: String,
    val provider: String,
    val reasoning: Boolean,
    val contextWindow: Long?,
    /** Input modalities (`["text", "image"]`), null when the host did not say. */
    val input: List<String>? = null,
) {
    val label: String get() = name.ifBlank { id }

    /** Whether the model reads images; null when unknown (an older host that sends no `input`). */
    val acceptsImages: Boolean? get() = input?.contains("image")

    companion object {
        fun from(json: JsonObject?): ModelInfo? {
            json ?: return null
            val id = json.str("id") ?: return null
            return ModelInfo(
                id = id,
                name = json.str("name") ?: id,
                provider = json.str("provider") ?: "",
                reasoning = json.bool("reasoning") ?: false,
                contextWindow = (json["contextWindow"] as? JsonPrimitive)?.content?.toLongOrNull(),
                input = json.arr("input")?.mapNotNull { (it as? JsonPrimitive)?.content },
            )
        }

        fun listFrom(response: RpcResponse): List<ModelInfo> =
            response.dataObject?.arr("models")?.mapNotNull { from(it as? JsonObject) }.orEmpty()
    }
}

fun RpcResponse.thinkingLevels(): List<String> =
    dataObject?.arr("levels")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()

fun RpcResponse.clearedQueue(): Pair<List<String>, List<String>> {
    val data = dataObject
    fun list(key: String) = data?.arr(key)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
    return list("steering") to list("followUp")
}

