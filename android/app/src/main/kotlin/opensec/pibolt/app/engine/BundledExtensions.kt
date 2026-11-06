package opensec.pibolt.app.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.protocol.ProtocolJson
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * The Pi extensions the pack bundles (android/engine/CONTRACT.md, `extensions[]`), registered as local-path packages in
 * Pi's global settings.json (packages/coding-agent/docs/packages.md): each bundled directory `<root>/<name>` is one
 * `packages` entry. The file is merged, never replaced: other settings and packages stay, a bundled path is never added
 * twice, and paths under `<root>` that are no longer bundled (an older pack's) are dropped. Written the way Pi does
 * (`JSON.stringify(settings, null, 2)`), atomically, under Pi's lock (proper-lockfile: a directory `settings.json.lock`,
 * stale after 10 s).
 */
object BundledExtensions {
    /**
     * [settings] with exactly the [wanted] paths among the `packages` entries under [root] (a string, or an object's
     * `source`), the user's entries for them (resource filters) kept as they are. Null when nothing changes, or when
     * `packages` is not a list (a file Pi would reject is left alone).
     */
    fun merge(settings: JsonObject, wanted: List<String>, root: String): JsonObject? {
        val current = settings["packages"]
        val packages = when (current) {
            null -> emptyList()
            is JsonArray -> current
            else -> return null
        }
        val base = root.trimEnd('/')
        val seen = HashSet<String>()
        val kept = packages.filter { entry ->
            val source = sourceOf(entry)?.trimEnd('/') ?: return@filter true
            if (source != base && !source.startsWith("$base/")) return@filter true
            source in wanted && seen.add(source)
        }
        val next = kept + wanted.filter { it !in seen }.map(::JsonPrimitive)
        if (current != null && next == packages) return null
        if (current == null && next.isEmpty()) return null
        return JsonObject(settings + ("packages" to JsonArray(next)))
    }

    /** Applies [merge] to `agentDir/settings.json`. False when the file could not be read, parsed or written. Blocking. */
    fun register(agentDir: File, wanted: List<String>, root: String): Boolean {
        if (!agentDir.isDirectory) {
            agentDir.mkdirs()
            runCatching { Files.setPosixFilePermissions(agentDir.toPath(), PosixFilePermissions.fromString("rwx------")) }
        }
        val file = File(agentDir, "settings.json")
        val lockDir = File(agentDir, "settings.json.lock")
        if (!lock(lockDir)) return false
        try {
            val current = if (file.isFile) {
                ProtocolJson.parseToJsonElement(file.readText().removePrefix(BOM)) as? JsonObject ?: return false
            } else {
                JsonObject(emptyMap())
            }
            val next = merge(current, wanted, root) ?: return true
            val temp = File(agentDir, "settings.json.${System.nanoTime()}.tmp")
            temp.writeText(PrettyJson.encodeToString(JsonElement.serializer(), next))
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return true
        } catch (e: IOException) {
            return false
        } catch (e: IllegalArgumentException) {
            return false
        } finally {
            lockDir.delete()
        }
    }

    private fun sourceOf(entry: JsonElement): String? = when (entry) {
        is JsonPrimitive -> entry.takeIf { it.isString }?.content
        is JsonObject -> (entry["source"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        else -> null
    }

    private fun lock(lockDir: File): Boolean {
        repeat(LOCK_ATTEMPTS) {
            if (lockDir.mkdir()) return true
            val age = System.currentTimeMillis() - lockDir.lastModified()
            if (lockDir.exists() && age > STALE_MS) lockDir.delete() else Thread.sleep(LOCK_RETRY_MS)
        }
        return false
    }

    private const val LOCK_ATTEMPTS = 50
    private const val LOCK_RETRY_MS = 20L
    private const val STALE_MS = 10_000L
    private val BOM = Char(0xFEFF).toString()

    /** Same text as `JSON.stringify(value, null, 2)`. */
    private val PrettyJson = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }
}
