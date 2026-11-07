package opensec.pibolt.app.ui.session

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.session.ChatItem
import opensec.pibolt.protocol.session.NoticeItem
import opensec.pibolt.protocol.session.NoticeKind
import opensec.pibolt.protocol.session.RetryInfo

/** What went wrong, as far as the user can act on it. */
enum class ErrorClass { ContextOverflow, Auth, Billing, RateLimit, Overloaded, Network, Other }

/** One-tap ways out of an error, in the order the notice shows them. */
enum class ErrorAction(val label: String) {
    Retry("Retry"),
    SwitchModel("Switch model"),
    Compact("Compact"),
    UpdateKey("Update key"),
    NewSession("New session"),
    Stop("Stop retrying"),
}

/**
 * A provider error read from the engine's error text, which is usually `"<status>: <provider JSON body>"` (for example
 * `503: {"message":"The server is overloaded.","type":"server_error"}`) or `"<status> <message>"`, sometimes a bare
 * message ("fetch failed"). [message] is the human part of it; [raw] is kept for Details and Copy.
 */
data class ProviderError(val kind: ErrorClass, val status: Int?, val message: String, val code: String?, val raw: String)

object ErrorText {
    /** Context overflow wording of the providers Pi supports (packages/ai/src/utils/overflow.ts). */
    private val OVERFLOW = listOf(
        "prompt (?:is )?too long", "prompt exceeds max length", "request_too_large", "input is too long for requested model",
        "exceeds the context window", "maximum context length", "context window exceeded", "input token count.*exceeds the maximum",
        "maximum prompt length is \\d+", "reduce the length of the messages", "exceeds (?:the )?maximum allowed input length",
        "is longer than the model'?s context length", "exceeds the limit of \\d+", "exceeds the available context size",
        "greater than the context length", "context window exceeds limit", "exceeded model token limit",
        "too large for model with \\d+ maximum context length", "configured context size", "model_context_window_exceeded",
        "exceeded (?:max )?context length", "range of input length should be", "context[_ ]length[_ ]exceeded", "too many tokens",
        "token limit exceeded",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }
    private val NOT_OVERFLOW = Regex("rate limit|too many requests|throttl", RegexOption.IGNORE_CASE)
    private val AUTH = Regex(
        "invalid[_ ]api[_ ]key|incorrect api key|invalid x-api-key|authentication|unauthori[sz]ed|permission[_ ]denied|api key not valid|no api key|missing api key|forbidden",
        RegexOption.IGNORE_CASE,
    )
    private val BILLING = Regex("insufficient[_ ]quota|insufficient (?:credits|balance|funds)|billing|payment required|credit balance|exceeded your current quota|out of credits", RegexOption.IGNORE_CASE)
    private val RATE = Regex("rate[_ ]limit|too many requests|throttl|resource[_ ]exhausted", RegexOption.IGNORE_CASE)
    private val OVERLOADED = Regex("overloaded|server[_ ]error|service unavailable|bad gateway|gateway time-?out|internal server error|temporarily unavailable|upstream", RegexOption.IGNORE_CASE)
    private val NETWORK = Regex(
        "fetch failed|econnrefused|econnreset|enotfound|etimedout|eai_again|getaddrinfo|socket hang up|network|connection (?:error|refused|reset|closed)|timed? ?out|unable to connect|unreachable",
        RegexOption.IGNORE_CASE,
    )
    private val LEADING_STATUS = Regex("^\\s*(?:[\\w .-]*?(?:API )?error\\s*\\(?)?(\\d{3})\\)?(?:\\s*status code)?\\s*[:\\s-]\\s*", RegexOption.IGNORE_CASE)
    private val STATUS_FIELD = Regex("\"status(?:Code)?\"\\s*:\\s*(\\d{3})|status(?: code)?:?\\s+(\\d{3})\\b", RegexOption.IGNORE_CASE)

    fun parse(raw: String): ProviderError {
        val text = raw.trim()
        var status: Int? = null
        var rest = text
        LEADING_STATUS.find(text)?.takeIf { it.range.first == 0 }?.let { m ->
            m.groupValues[1].toIntOrNull()?.takeIf { it in 100..599 }?.let {
                status = it
                rest = text.substring(m.range.last + 1).trim()
            }
        }
        if (status == null) {
            STATUS_FIELD.find(text)?.let { m -> status = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull()?.takeIf { it in 100..599 } }
        }
        var message = rest
        var code: String? = null
        val brace = rest.indexOf('{')
        if (brace >= 0) {
            val json = runCatching { ProtocolJson.parseToJsonElement(rest.substring(brace)) as? JsonObject }.getOrNull()
            if (json != null) {
                val body = json["error"] as? JsonObject ?: json
                message = (body.string("message") ?: json.string("message") ?: (json["error"] as? JsonPrimitive)?.content ?: rest).trim()
                code = body.string("code") ?: body.string("type") ?: json.string("type")
                if (status == null) status = (body["status"] as? JsonPrimitive)?.intOrNull ?: (body["code"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 100..599 }
                if (brace > 0 && message == rest) message = rest.substring(0, brace).trim().trimEnd(':').trim()
            }
        }
        if (message.isBlank()) message = text
        val haystack = "$text ${code.orEmpty()}"
        val s = status
        val kind = when {
            s == 413 || (OVERFLOW.any { it.containsMatchIn(haystack) } && !NOT_OVERFLOW.containsMatchIn(haystack)) -> ErrorClass.ContextOverflow
            s == 401 || s == 403 || AUTH.containsMatchIn(haystack) -> ErrorClass.Auth
            s == 402 || BILLING.containsMatchIn(haystack) -> ErrorClass.Billing
            s == 429 || RATE.containsMatchIn(haystack) -> ErrorClass.RateLimit
            (s != null && s >= 500) || OVERLOADED.containsMatchIn(haystack) -> ErrorClass.Overloaded
            s == null && NETWORK.containsMatchIn(haystack) -> ErrorClass.Network
            else -> ErrorClass.Other
        }
        return ProviderError(kind, s, message.take(MESSAGE_MAX), code, raw)
    }

    /** The title: what happened, in plain words. [model] and [provider] are display names when known. */
    fun title(e: ProviderError, model: String?, provider: String?): String {
        val who = provider ?: "The provider"
        return when (e.kind) {
            ErrorClass.ContextOverflow -> if (model != null) "Too long for $model" else "The conversation is too long for this model"
            ErrorClass.Auth -> "$who rejected the key"
            ErrorClass.Billing -> "$who needs billing attention"
            ErrorClass.RateLimit -> "Rate limited"
            ErrorClass.Overloaded -> if (e.status == 500 || e.status == 502) "$who had a server error" else "$who is overloaded"
            ErrorClass.Network -> "Can't reach ${provider ?: "the provider"}"
            ErrorClass.Other -> "The model returned an error"
        }
    }

    /** One sentence on what to do next. */
    fun advice(e: ProviderError, local: Boolean): String = when (e.kind) {
        ErrorClass.ContextOverflow -> "Compact the conversation, or switch to a model with a larger context."
        ErrorClass.Auth -> if (local) "The API key is missing, wrong or revoked. Update it, or switch model." else "The API key on the computer is missing, wrong or revoked. Fix it there, or switch model."
        ErrorClass.Billing -> "The account is out of credit or at its spending limit. Check billing, or switch model."
        ErrorClass.RateLimit -> "Wait a moment, then retry."
        ErrorClass.Overloaded -> "Retry, or switch model."
        ErrorClass.Network -> if (local) "Check this phone's connection, then retry." else "Check the computer's connection, then retry."
        ErrorClass.Other -> "Retry, or switch model."
    }

    /** "503 · The server is overloaded." The provider's own words, with the HTTP status when there is one. */
    fun detail(e: ProviderError): String = listOfNotNull(e.status?.toString(), e.message.takeIf { it.isNotBlank() }).joinToString(" · ")

    /**
     * The actions for [e]: at most three. [local] sessions can update a key on this phone and start a new session;
     * [running] hides Retry (a turn is under way); [retrying] offers only Stop.
     */
    fun actions(e: ProviderError, local: Boolean, running: Boolean, retrying: Boolean): List<ErrorAction> {
        if (retrying) return listOf(ErrorAction.Stop)
        val all = when (e.kind) {
            ErrorClass.ContextOverflow -> listOf(ErrorAction.Compact, ErrorAction.SwitchModel, ErrorAction.NewSession)
            ErrorClass.Auth -> listOf(ErrorAction.UpdateKey, ErrorAction.SwitchModel)
            ErrorClass.Billing -> listOf(ErrorAction.SwitchModel, ErrorAction.UpdateKey, ErrorAction.Retry)
            ErrorClass.RateLimit -> listOf(ErrorAction.Retry, ErrorAction.SwitchModel)
            ErrorClass.Overloaded -> listOf(ErrorAction.Retry, ErrorAction.SwitchModel)
            ErrorClass.Network -> listOf(ErrorAction.Retry)
            ErrorClass.Other -> listOf(ErrorAction.Retry, ErrorAction.SwitchModel)
        }
        return all.filter {
            when (it) {
                ErrorAction.UpdateKey, ErrorAction.NewSession -> local
                ErrorAction.Retry, ErrorAction.Compact -> !running
                else -> true
            }
        }.take(3)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private const val MESSAGE_MAX = 300
}

/** Provider error notices: the titles the engine gives them (a model error, a retry that ran out, a compaction). */
private val PROVIDER_ERROR_TITLES = setOf("Error", "Retry failed", "Compaction failed")

/** Errors merged into one notice: how many attempts failed, every attempt's text, and the live retry if any. */
data class ErrorGroup(val attempts: Int, val raws: List<String>, val retry: RetryInfo?) {
    /** The class that explains the group best: an overflow anywhere wins, else the last error that says more than "Other". */
    fun error(): ProviderError {
        val parsed = raws.map(ErrorText::parse)
        return parsed.firstOrNull { it.kind == ErrorClass.ContextOverflow }
            ?: parsed.lastOrNull { it.kind != ErrorClass.Other }
            ?: parsed.lastOrNull()
            ?: ErrorText.parse("")
    }
}

object ErrorNotices {
    fun isProviderError(item: ChatItem): Boolean = item is NoticeItem && item.kind == NoticeKind.Error && item.title in PROVIDER_ERROR_TITLES

    /**
     * Consecutive provider errors (each failed attempt of an automatic retry, then "Retry failed", or an overflow
     * followed by "Compaction failed") become one notice. It keeps the first notice's key, so its row stays put while
     * attempts are added, and the last text. [retry] is the live retry: it belongs to the last group when that group
     * ends the transcript.
     */
    fun collapse(items: List<ChatItem>, retry: RetryInfo?): Pair<List<ChatItem>, Map<String, ErrorGroup>> {
        if (items.none(::isProviderError)) {
            return items to emptyMap()
        }
        val out = ArrayList<ChatItem>(items.size)
        val groups = HashMap<String, ErrorGroup>()
        var openKey: String? = null
        for (item in items) {
            if (isProviderError(item)) {
                item as NoticeItem
                val key = openKey
                val attempt = if (item.title == "Error") 1 else 0
                if (key != null) {
                    val g = groups.getValue(key)
                    groups[key] = g.copy(attempts = g.attempts + attempt, raws = g.raws + item.text)
                    val i = out.indexOfLast { it.key == key }
                    out[i] = (out[i] as NoticeItem).copy(text = item.text)
                } else {
                    openKey = item.key
                    groups[item.key] = ErrorGroup(attempt.coerceAtLeast(1), listOf(item.text), null)
                    out += item
                }
            } else {
                openKey = null
                out += item
            }
        }
        if (retry != null && out.lastOrNull()?.let(::isProviderError) == true) {
            val key = out.last().key
            groups[key] = groups.getValue(key).copy(retry = retry)
        }
        return out to groups
    }
}
