package opensec.pibolt.app.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import opensec.pibolt.app.ui.session.features2.AppExtension
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.arr
import opensec.pibolt.protocol.session.DialogRequest
import opensec.pibolt.protocol.session.SessionReducer
import opensec.pibolt.protocol.str
import opensec.pibolt.protocol.type

/**
 * A provider's own login flow (an extension provider's OAuth, say), run by the app's helper extension in an engine the
 * app keeps for itself ([LocalEngine.login]). Its prompts arrive as Pi's extension dialogs, its steps (a URL to open, a
 * device code) under the helper's status key ([AppExtension.LOGIN_STATUS]); the credential goes to Pi's auth.json and
 * never through the app.
 */
class ProviderLogin(val provider: String) {
    /** A step the flow reports (Pi's `AuthEvent`). */
    sealed interface Step {
        /** Open [url] in the browser; the provider redirects back to the engine on this phone. */
        data class OpenUrl(val url: String, val instructions: String?) : Step

        /** Enter [code] at [url]. */
        data class DeviceCode(val code: String, val url: String) : Step

        data class Info(val message: String, val links: List<String>) : Step
    }

    data class State(
        /** The open prompt, if any. */
        val dialog: DialogRequest? = null,
        /** The latest step, kept until the flow ends. */
        val step: Step? = null,
        /** The latest progress line. */
        val progress: String? = null,
        val finished: Boolean = false,
        /** Why it failed or was cancelled; null on success. */
        val error: String? = null,
    ) {
        val succeeded: Boolean get() = finished && error == null
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var send: (JsonObject) -> Boolean = { false }

    @Volatile
    private var stop: () -> Unit = {}

    @Volatile
    var cancelled = false
        private set

    /** Wires the flow to its engine: [send] answers dialogs, [stop] ends the flow and its engine (cancel). */
    fun attach(send: ((JsonObject) -> Boolean)? = null, stop: (() -> Unit)? = null) {
        send?.let { this.send = it }
        stop?.let { this.stop = it }
    }

    /** A message from the engine. */
    fun on(message: JsonObject) = _state.update { reduce(it, message) }

    fun finish(error: String?) = _state.update { it.copy(finished = true, error = error, dialog = null) }

    /** Answers the open prompt with [value]. */
    fun answer(id: String, value: String) {
        _state.update { if (it.dialog?.id == id) it.copy(dialog = null) else it }
        send(buildJsonObject { put("type", "extension_ui_response"); put("id", id); put("value", value) })
    }

    /** Declines the open prompt: the provider's flow ends as cancelled. */
    fun decline(id: String) {
        _state.update { if (it.dialog?.id == id) it.copy(dialog = null) else it }
        send(buildJsonObject { put("type", "extension_ui_response"); put("id", id); put("cancelled", true) })
    }

    /** Stops the flow: the engine running it ends (nothing is saved). */
    fun cancel() {
        if (_state.value.finished) return
        cancelled = true
        stop()
        finish(CANCELLED)
    }

    companion object {
        const val CANCELLED = "Sign-in cancelled"

        /** [state] after the engine's [message]: a prompt, a step under the helper's status key, progress, a closed prompt. */
        fun reduce(state: State, message: JsonObject): State = when (message.type) {
            "extension_ui_request" -> when (message.str("method")) {
                "select", "input", "confirm", "editor" -> SessionReducer.dialogFrom(message)?.let { state.copy(dialog = it) } ?: state
                "setStatus" -> if (message.str("statusKey") == AppExtension.LOGIN_STATUS) event(state, message.str("statusText")) else state
                "notify" -> message.str("message")?.let { state.copy(progress = it) } ?: state
                else -> state
            }
            "extension_ui_dismissed" -> if (state.dialog?.id == message.str("id")) state.copy(dialog = null) else state
            else -> state
        }

        /** Pi's `AuthEvent` as JSON. */
        private fun event(state: State, text: String?): State {
            val json = text?.let { runCatching { ProtocolJson.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return state
            return when (json.type) {
                "auth_url" -> json.str("url")?.takeIf(::isWebUrl)?.let { state.copy(step = Step.OpenUrl(it, json.str("instructions"))) } ?: state
                "device_code" -> {
                    val code = json.str("userCode")
                    val url = json.str("verificationUri")?.takeIf(::isWebUrl)
                    if (code != null && url != null) state.copy(step = Step.DeviceCode(code, url)) else state
                }
                "info" -> json.str("message")?.let { m ->
                    val links = json.arr("links").orEmpty().mapNotNull { (it as? JsonObject)?.str("url")?.takeIf(::isWebUrl) }
                    state.copy(step = Step.Info(m, links))
                } ?: state
                "progress" -> json.str("message")?.let { state.copy(progress = it) } ?: state
                else -> state
            }
        }

        /** Only http(s) links are opened: a provider's flow never makes the app open another scheme. */
        fun isWebUrl(url: String): Boolean = url.startsWith("https://") || url.startsWith("http://")
    }
}
