package opensec.pibolt.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.protocol.ProtocolJson

/**
 * Prompts sent from this phone, per computer, most recent first, in a small JSON file (`{ hostId: [prompt, …] }`),
 * read on a background thread. The app's files are excluded from backup.
 */
class RecentPrompts(context: Context) {
    private val _prompts = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val prompts: StateFlow<Map<String, List<String>>> = _prompts.asStateFlow()
    private val file = BackgroundFile(context, "recent-prompts.json") { text -> _prompts.value = parse(text) }

    @Synchronized
    fun record(hostId: String, prompt: String) {
        file.awaitLoaded()
        val current = _prompts.value[hostId].orEmpty()
        val next = add(current, prompt)
        if (next != current) save(_prompts.value + (hostId to next))
    }

    @Synchronized
    fun forget(hostId: String) {
        file.awaitLoaded()
        if (hostId in _prompts.value) save(_prompts.value - hostId)
    }

    private fun save(all: Map<String, List<String>>) {
        _prompts.value = all
        file.write(JsonObject(all.mapValues { (_, list) -> JsonArray(list.map(::JsonPrimitive)) }).toString())
    }

    private fun parse(text: String?): Map<String, List<String>> = runCatching {
        text ?: return emptyMap()
        (ProtocolJson.parseToJsonElement(text) as JsonObject).mapValues { (_, value) ->
            (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty().take(CAP)
        }
    }.getOrDefault(emptyMap())

    companion object {
        /** Prompts kept per computer. */
        const val CAP = 20

        /** Prompts the empty state shows. */
        const val SHOWN = 5

        /** [prompt] (trimmed) moves to the front; an equal older entry goes; at most [cap] are kept. Blank is ignored. */
        fun add(list: List<String>, prompt: String, cap: Int = CAP): List<String> {
            val p = prompt.trim()
            if (p.isEmpty()) return list
            return (listOf(p) + list.filter { it != p }).take(cap)
        }
    }
}
