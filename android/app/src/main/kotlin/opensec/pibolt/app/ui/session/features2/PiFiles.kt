package opensec.pibolt.app.ui.session.features2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.long
import opensec.pibolt.protocol.obj
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Pi's JSON files in the agent dir (`settings.json`, `trust.json`), changed the way Pi does: read, change, written
 * atomically as `JSON.stringify(value, null, 2)` under Pi's lock (proper-lockfile: a `<file>.lock` directory, stale
 * after 10 s). Other keys are kept as they are.
 */
object PiJsonFile {
    /** The object in [file], empty when the file is missing; null when it is not a JSON object. */
    fun read(file: File): JsonObject? {
        if (!file.isFile) return JsonObject(emptyMap())
        return runCatching { ProtocolJson.parseToJsonElement(file.readText().removePrefix(BOM)) as? JsonObject }.getOrNull()
    }

    /** Applies [change] to [file]. False when it could not be read, locked or written. Blocking. */
    fun update(file: File, change: (JsonObject) -> JsonObject): Boolean {
        file.parentFile?.mkdirs()
        val lockDir = File(file.parentFile, file.name + ".lock")
        if (!lock(lockDir)) return false
        try {
            val current = read(file) ?: return false
            val next = change(current)
            if (next == current && file.isFile) return true
            val temp = File(file.parentFile, "${file.name}.${System.nanoTime()}.tmp")
            temp.writeText(Pretty.encodeToString(JsonElement.serializer(), next) + "\n")
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return true
        } catch (e: IOException) {
            return false
        } finally {
            lockDir.delete()
        }
    }

    /** [obj] with [path] (dotted) set to [value], or removed when [value] is null; empty parents are dropped. */
    fun set(obj: JsonObject, path: String, value: JsonElement?): JsonObject {
        val key = path.substringBefore('.')
        if ('.' !in path) return if (value == null || value is JsonNull) JsonObject(obj - key) else JsonObject(obj + (key to value))
        val child = set(obj.obj(key) ?: JsonObject(emptyMap()), path.substringAfter('.'), value)
        return if (child.isEmpty()) JsonObject(obj - key) else JsonObject(obj + (key to child))
    }

    fun get(obj: JsonObject, path: String): JsonElement? {
        var at: JsonElement = obj
        for (part in path.split('.')) at = (at as? JsonObject)?.get(part) ?: return null
        return at
    }

    private fun lock(lockDir: File): Boolean {
        repeat(50) {
            if (lockDir.mkdir()) return true
            val age = System.currentTimeMillis() - lockDir.lastModified()
            if (lockDir.exists() && age > 10_000) lockDir.delete() else Thread.sleep(20)
        }
        return false
    }

    private val BOM = Char(0xFEFF).toString()
    private val Pretty = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }
}

/**
 * Retry and compaction numbers in Pi's settings.json (docs/settings.md). A null number is unset: Pi's default applies.
 * The on/off switches live in Settings (AgentDefaults).
 */
data class AgentTuning(
    val reserveTokens: Long? = null,
    val keepRecentTokens: Long? = null,
    val thresholdPercent: Long? = null,
    val maxRetries: Long? = null,
    val baseDelayMs: Long? = null,
    val maxAgentDelayMs: Long? = null,
) {
    /** A list of problems; empty when every set value is in range. */
    fun problems(): List<String> = buildList {
        reserveTokens?.let { if (it < 0) add("Reserve tokens can't be negative") }
        keepRecentTokens?.let { if (it < 0) add("Keep-recent tokens can't be negative") }
        thresholdPercent?.let { if (it !in 1..100) add("Threshold must be 1 to 100 %") }
        maxRetries?.let { if (it !in 0..20) add("Retries must be 0 to 20") }
        baseDelayMs?.let { if (it < 0) add("Delay can't be negative") }
        maxAgentDelayMs?.let { if (it < 0) add("Delay can't be negative") }
    }

    fun applyTo(settings: JsonObject): JsonObject {
        var s = settings
        fun num(path: String, v: Long?) {
            s = PiJsonFile.set(s, path, v?.let(::JsonPrimitive))
        }
        num("compaction.reserveTokens", reserveTokens)
        num("compaction.keepRecentTokens", keepRecentTokens)
        num("compaction.thresholdPercent", thresholdPercent)
        num("retry.maxRetries", maxRetries)
        num("retry.baseDelayMs", baseDelayMs)
        num("retry.maxAgentDelayMs", maxAgentDelayMs)
        return s
    }

    companion object {
        fun from(settings: JsonObject): AgentTuning {
            val c = settings.obj("compaction") ?: JsonObject(emptyMap())
            val r = settings.obj("retry") ?: JsonObject(emptyMap())
            return AgentTuning(
                reserveTokens = c.long("reserveTokens"),
                keepRecentTokens = c.long("keepRecentTokens"),
                thresholdPercent = c.long("thresholdPercent"),
                maxRetries = r.long("maxRetries"),
                baseDelayMs = r.long("baseDelayMs"),
                maxAgentDelayMs = r.long("maxAgentDelayMs"),
            )
        }
    }
}

/**
 * `enabledModels` (docs/settings.md): patterns that pick the models for cycling and, in the app, the model picker.
 * Patterns are `provider/id` or a bare id, with `*` matching anything; an empty list means every model.
 */
object ModelScope {
    fun read(settings: JsonObject): List<String> =
        (settings["enabledModels"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

    fun write(settings: JsonObject, patterns: List<String>): JsonObject =
        PiJsonFile.set(settings, "enabledModels", if (patterns.isEmpty()) null else JsonArray(patterns.distinct().map(::JsonPrimitive)))

    fun matches(pattern: String, provider: String, id: String): Boolean {
        val p = pattern.trim().substringBefore(':')
        if (p.isEmpty()) return false
        val target = if ('/' in p) "$provider/$id" else id
        if ('*' !in p) return p.equals(target, ignoreCase = true)
        val regex = p.split('*').joinToString(".*") { Regex.escape(it) }
        return Regex(regex, RegexOption.IGNORE_CASE).matches(target)
    }

    fun enabled(patterns: List<String>, provider: String, id: String): Boolean = patterns.isEmpty() || patterns.any { matches(it, provider, id) }

    /** [patterns] with `provider/id` switched on or off; the other patterns are kept. */
    fun toggle(patterns: List<String>, provider: String, id: String, on: Boolean, all: List<Pair<String, String>>): List<String> {
        // From "every model" (empty), switching one off lists every other model explicitly.
        val base = if (patterns.isEmpty()) all.map { "${it.first}/${it.second}" } else patterns
        val exact = "$provider/$id"
        return if (on) {
            if (enabled(base, provider, id)) base else base + exact
        } else {
            val left = base.filterNot { matches(it, provider, id) }
            // A wildcard that matched it is kept out as a whole; the other models it matched stay as exact entries.
            val widened = base.filter { '*' in it && matches(it, provider, id) }
                .flatMap { w -> all.filter { (p, i) -> matches(w, p, i) && !(p == provider && i == id) }.map { "${it.first}/${it.second}" } }
            (left + widened).distinct()
        }
    }
}

/**
 * Project trust (docs/security.md): saved decisions in `<agentDir>/trust.json`, a map of canonical directory to true,
 * false or null; the closest saved decision for a directory or one of its parents applies.
 */
object ProjectTrust {
    private val GATED = listOf("settings.json", "mcp.json", "extensions", "skills", "prompts", "themes", "SYSTEM.md", "APPEND_SYSTEM.md")

    /** The project has resources that load only when trusted (`.pi/...` or `.agents/skills`). */
    fun gatedResources(project: File): List<String> = buildList {
        val pi = File(project, ".pi")
        for (name in GATED) if (File(pi, name).exists()) add(".pi/$name")
        if (File(project, ".agents/skills").exists()) add(".agents/skills")
    }

    data class Decision(val path: String, val trusted: Boolean)

    /** The saved decision that applies to [dir] (it or the closest parent), or null. */
    fun decision(trust: JsonObject, dir: String): Decision? {
        var at: File? = File(dir)
        while (at != null) {
            val value = (trust[at.path] as? JsonPrimitive)?.booleanOrNull
            if (value != null) return Decision(at.path, value)
            at = at.parentFile
        }
        return null
    }

    /** [trust] with [dir]'s own decision set ([value] null clears it). */
    fun set(trust: JsonObject, dir: String, value: Boolean?): JsonObject =
        JsonObject((if (value == null) trust - dir else trust + (dir to JsonPrimitive(value))).toSortedMap())
}
