package opensec.pibolt.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.protocol.ProtocolJson
import java.io.File

/**
 * Prompts sent from this phone, per computer, most recent first, in a small JSON file (`{ hostId: [prompt, …] }`).
 * The app's files are excluded from backup.
 */
class RecentPrompts(context: Context) {
    private val file = File(context.filesDir, "recent-prompts.json")
    private val _prompts = MutableStateFlow(load())
    val prompts: StateFlow<Map<String, List<String>>> = _prompts.asStateFlow()

    @Synchronized
    fun record(hostId: String, prompt: String) {
        val current = _prompts.value[hostId].orEmpty()
        val next = add(current, prompt)
        if (next != current) save(_prompts.value + (hostId to next))
    }

    @Synchronized
    fun forget(hostId: String) {
        if (hostId in _prompts.value) save(_prompts.value - hostId)
    }

    private fun save(all: Map<String, List<String>>) {
        _prompts.value = all
        val text = JsonObject(all.mapValues { (_, list) -> JsonArray(list.map(::JsonPrimitive)) }).toString()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    private fun load(): Map<String, List<String>> = runCatching {
        if (!file.exists()) return emptyMap()
        (ProtocolJson.parseToJsonElement(file.readText()) as JsonObject).mapValues { (_, value) ->
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
