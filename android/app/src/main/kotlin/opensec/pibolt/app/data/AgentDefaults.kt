package opensec.pibolt.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * delivered, automatic compaction and retry (and how often), the startup model and thinking level, skill commands and
 * image blocking. They live in the engine's
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
    /** `retry.maxRetries`: agent-level retries for an overloaded or rate-limited provider. */
    val maxRetries: Int = 3,
    /** `enableSkillCommands`: skills appear as `/skill:name` commands. */
    val skillCommands: Boolean = true,
    /** `images.blockImages`: never send images to a model. */
    val blockImages: Boolean = false,
) {
    companion object {
        val THINKING_LEVELS = listOf("off", "minimal", "low", "medium", "high", "xhigh")
        val RETRY_CHOICES = listOf(1, 3, 5, 10)

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
                maxRetries = (json.obj("retry")?.get("maxRetries") as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it >= 0 } ?: d.maxRetries,
                skillCommands = json.bool("enableSkillCommands") ?: d.skillCommands,
                blockImages = json.obj("images")?.bool("blockImages") ?: d.blockImages,
            )
        }

        /** [json] with this app's keys set from [defaults]; every other key (and nested key) is kept. */
        fun merge(json: JsonObject?, defaults: AgentDefaults): JsonObject {
            val out = LinkedHashMap<String, JsonElement>(json ?: emptyMap())
            out["steeringMode"] = JsonPrimitive(defaults.steeringMode.wire)
            out["followUpMode"] = JsonPrimitive(defaults.followUpMode.wire)
            out["defaultThinkingLevel"] = JsonPrimitive(defaults.defaultThinking)
            out["compaction"] = nested(json?.obj("compaction"), "enabled", defaults.autoCompaction)
            out["retry"] = nested(nested(json?.obj("retry"), "enabled", JsonPrimitive(defaults.autoRetry)), "maxRetries", JsonPrimitive(defaults.maxRetries))
            out["enableSkillCommands"] = JsonPrimitive(defaults.skillCommands)
            out["images"] = nested(json?.obj("images"), "blockImages", JsonPrimitive(defaults.blockImages))
            // `provider/model` is split at the first slash: model ids may contain slashes (`openrouter/anthropic/claude`).
            // Written only when it changed here, so a default Pi saved in another form is left as it is.
            defaults.defaultModel?.takeIf { it != from(json).defaultModel }?.let { full ->
                val slash = full.indexOf('/')
                if (slash > 0 && slash < full.length - 1) {
                    out["defaultProvider"] = JsonPrimitive(full.substring(0, slash))
                    out["defaultModel"] = JsonPrimitive(full.substring(slash + 1))
                }
            }
            return JsonObject(out)
        }

        /**
         * [json] without its default model when that model is [provider]'s (the provider was removed: Pi would start with
         * another model anyway, and Settings would still name the removed one). Null when nothing changes.
         */
        fun withoutDefaultFrom(json: JsonObject?, provider: String): JsonObject? {
            if (json == null || json.str("defaultProvider") != provider) return null
            return JsonObject(json - "defaultProvider" - "defaultModel")
        }

        private fun nested(existing: JsonObject?, key: String, value: Boolean): JsonObject = nested(existing, key, JsonPrimitive(value))

        private fun nested(existing: JsonObject?, key: String, value: JsonPrimitive): JsonObject =
            JsonObject(LinkedHashMap<String, JsonElement>(existing ?: emptyMap()).apply { put(key, value) })
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

    // One read-merge-write at a time, in call order (the lock is taken before the switch to IO, and a Mutex is fair):
    // two switches flipped in quick succession wrote the same .tmp file at once, and the older change could land last.
    suspend fun write(defaults: AgentDefaults) = LOCK.withLock { withContext(Dispatchers.IO) { save(AgentDefaults.merge(load(), defaults)) } }

    /** Drops the default model when it is [provider]'s (Settings > Providers removed it). */
    suspend fun forgetDefaultModelOf(provider: String) = LOCK.withLock {
        withContext(Dispatchers.IO) { AgentDefaults.withoutDefaultFrom(load(), provider)?.let(::save) }
    }

    private fun save(json: JsonObject) {
        val text = pretty.encodeToString(JsonObject.serializer(), json)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text + "\n")
        if (!tmp.renameTo(file)) {
            file.writeText(text + "\n")
            tmp.delete()
        }
    }

    private val pretty = Json { prettyPrint = true }

    private companion object {
        /** Every [AgentSettingsFile] writes the one agent/settings.json. */
        val LOCK = Mutex()
    }

    private fun load(): JsonObject? = runCatching { if (file.isFile) ProtocolJson.parseToJsonElement(file.readText()) as? JsonObject else null }.getOrNull()
}
