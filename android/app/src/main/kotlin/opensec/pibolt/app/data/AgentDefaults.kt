package opensec.pibolt.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.bool
import opensec.pibolt.protocol.obj
import opensec.pibolt.protocol.str
import java.io.File

/**
 * The on-device engine's own settings that a phone user would change (docs/settings.md): how queued messages are
 * delivered, automatic compaction and retry, and the startup thinking level. They live in the engine's
 * `agent/settings.json`, as in the terminal, so every new engine starts with them; a running session also gets them
 * over RPC (which saves them the same way).
 */
data class AgentDefaults(
    val steeringMode: QueueMode = QueueMode.OneAtATime,
    val followUpMode: QueueMode = QueueMode.OneAtATime,
    val autoCompaction: Boolean = true,
    val autoRetry: Boolean = true,
    val defaultThinking: String = "medium",
    /** `provider/model` the engine starts with (Pi saves the last model picked), or null when unset. */
    val defaultModel: String? = null,
) {
    companion object {
        val THINKING_LEVELS = listOf("off", "minimal", "low", "medium", "high", "xhigh")

        fun from(json: JsonObject?): AgentDefaults {
            val d = AgentDefaults()
            json ?: return d
            val provider = json.str("defaultProvider")
            val model = json.str("defaultModel")
            return AgentDefaults(
                steeringMode = QueueMode.of(json.str("steeringMode")) ?: d.steeringMode,
                followUpMode = QueueMode.of(json.str("followUpMode")) ?: d.followUpMode,
                autoCompaction = json.obj("compaction")?.bool("enabled") ?: d.autoCompaction,
                autoRetry = json.obj("retry")?.bool("enabled") ?: d.autoRetry,
                defaultThinking = json.str("defaultThinkingLevel")?.takeIf { it in THINKING_LEVELS || it == "max" } ?: d.defaultThinking,
                defaultModel = if (provider != null && model != null) "$provider/$model" else model,
            )
        }

        /** [json] with this app's keys set from [defaults]; every other key (and nested key) is kept. */
        fun merge(json: JsonObject?, defaults: AgentDefaults): JsonObject {
            val out = LinkedHashMap<String, JsonElement>(json ?: emptyMap())
            out["steeringMode"] = JsonPrimitive(defaults.steeringMode.wire)
            out["followUpMode"] = JsonPrimitive(defaults.followUpMode.wire)
            out["defaultThinkingLevel"] = JsonPrimitive(defaults.defaultThinking)
            out["compaction"] = nested(json?.obj("compaction"), "enabled", defaults.autoCompaction)
            out["retry"] = nested(json?.obj("retry"), "enabled", defaults.autoRetry)
            return JsonObject(out)
        }

        private fun nested(existing: JsonObject?, key: String, value: Boolean): JsonObject =
            JsonObject(LinkedHashMap<String, JsonElement>(existing ?: emptyMap()).apply { put(key, JsonPrimitive(value)) })
    }
}

enum class QueueMode(val wire: String, val label: String) {
    OneAtATime("one-at-a-time", "One at a time"),
    All("all", "All at once"),
    ;

    companion object {
        fun of(wire: String?): QueueMode? = entries.firstOrNull { it.wire == wire }
    }
}

/** Reads and writes [AgentDefaults] in `<agentDir>/settings.json`, keeping every other setting. */
class AgentSettingsFile(private val file: File) {
    suspend fun read(): AgentDefaults = withContext(Dispatchers.IO) { AgentDefaults.from(load()) }

    suspend fun write(defaults: AgentDefaults) = withContext(Dispatchers.IO) {
        val text = pretty.encodeToString(JsonObject.serializer(), AgentDefaults.merge(load(), defaults))
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text + "\n")
        if (!tmp.renameTo(file)) {
            file.writeText(text + "\n")
            tmp.delete()
        }
    }

    private val pretty = Json { prettyPrint = true }

    private fun load(): JsonObject? = runCatching { if (file.isFile) ProtocolJson.parseToJsonElement(file.readText()) as? JsonObject else null }.getOrNull()
}
