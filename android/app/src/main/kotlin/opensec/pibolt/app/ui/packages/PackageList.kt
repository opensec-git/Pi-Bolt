package opensec.pibolt.app.ui.packages

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.app.ui.session.features2.PiJsonFile
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.obj
import opensec.pibolt.protocol.str
import java.io.File

enum class PackageScope { User, Project }

/** One configured Pi package (docs/packages.md), as `pi list` and settings.json describe it. */
@Immutable
data class PiPackage(
    val source: String,
    val scope: PackageScope,
    /** Where it is installed (npm and git sources), or the local path. */
    val installedPath: String? = null,
    /** The settings entry narrows its resources (object form). */
    val filtered: Boolean = false,
    /** Every resource type is turned off (`[]`): the app's "disabled". */
    val disabled: Boolean = false,
    /** Shipped with the app (todo, subagents): it can be turned off but not removed. */
    val bundled: Boolean = false,
    val name: String? = null,
    val version: String? = null,
    val description: String? = null,
) {
    val title: String get() = name ?: source.substringAfterLast('/').removePrefix("npm:").ifBlank { source }
    val kind: String get() = when {
        bundled -> "bundled"
        source.startsWith("npm:") -> "npm"
        source.startsWith("git:") || source.startsWith("https://") || source.startsWith("http://") || source.startsWith("ssh://") -> "git"
        else -> "local"
    }
}

/** Pi's built-in extensions, switched off with `-builtin:<name>` in settings `extensions` (docs/settings.md). */
enum class BuiltinExtension(val id: String, val label: String, val description: String) {
    Mcp("builtin:mcp", "MCP", "Model Context Protocol servers from mcp.json"),
    Codemode("builtin:codemode", "Code mode", "Lets the model call tools from a script"),
    ToolSearch("builtin:tool-search", "Tool search", "Finds tools that are not loaded up front"),
    Llama("builtin:llama.cpp", "llama.cpp", "Models on a llama.cpp router"),
}

object PackageList {
    private val ANSI = Regex("\u001B\\[[0-9;]*[A-Za-z]")

    /**
     * `pi list` output (packages/coding-agent/src/package-manager-cli.ts): "User packages:" / "Project packages:"
     * headings, each package as two-space-indented source (with " (filtered)") and its installed path indented four.
     */
    fun parseCli(output: String): List<PiPackage> {
        val out = ArrayList<PiPackage>()
        var scope: PackageScope? = null
        for (raw in output.lines()) {
            val line = ANSI.replace(raw, "").trimEnd()
            when {
                line.isBlank() -> Unit
                line == "User packages:" -> scope = PackageScope.User
                line == "Project packages:" -> scope = PackageScope.Project
                line.startsWith("    ") && out.isNotEmpty() && scope != null -> {
                    val last = out.removeAt(out.lastIndex)
                    out += last.copy(installedPath = line.trim())
                }
                line.startsWith("  ") && scope != null -> {
                    val text = line.trim()
                    val filtered = text.endsWith(" (filtered)")
                    out += PiPackage(source = text.removeSuffix(" (filtered)"), scope = scope, filtered = filtered)
                }
            }
        }
        return out
    }

    /** The `packages` entries of a settings object. */
    fun fromSettings(settings: JsonObject, scope: PackageScope): List<PiPackage> =
        (settings["packages"] as? JsonArray).orEmpty().mapNotNull { entry ->
            when (entry) {
                is JsonPrimitive -> entry.takeIf { it.isString }?.content?.let { PiPackage(it, scope) }
                is JsonObject -> entry.str("source")?.let { PiPackage(it, scope, filtered = true, disabled = isDisabled(entry)) }
                else -> null
            }
        }

    /** Every resource type is `[]`. */
    fun isDisabled(entry: JsonObject): Boolean = RESOURCE_TYPES.all { (entry[it] as? JsonArray)?.isEmpty() == true }

    private val RESOURCE_TYPES = listOf("extensions", "skills", "prompts", "themes")

    /**
     * [settings] with [source]'s entry turned off (object form, every resource type `[]`) or back on (the plain source;
     * other filters are dropped). Null when there is no such entry.
     */
    fun setEnabled(settings: JsonObject, source: String, enabled: Boolean): JsonObject? {
        val packages = (settings["packages"] as? JsonArray) ?: return null
        var found = false
        val next = packages.map { entry ->
            val entrySource = when (entry) {
                is JsonPrimitive -> entry.content
                is JsonObject -> entry.str("source")
                else -> null
            }
            if (entrySource != source) return@map entry
            found = true
            if (enabled) {
                JsonPrimitive(source)
            } else {
                JsonObject(mapOf<String, JsonElement>("source" to JsonPrimitive(source)) + RESOURCE_TYPES.associateWith { JsonArray(emptyList()) })
            }
        }
        return if (found) JsonObject(settings + ("packages" to JsonArray(next))) else null
    }

    /** Builtins switched off in [settings] (`-builtin:<name>` in `extensions`). */
    fun disabledBuiltins(settings: JsonObject): Set<String> =
        (settings["extensions"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
            .filter { it.startsWith("-builtin:") }.map { it.removePrefix("-") }.toSet()

    fun setBuiltin(settings: JsonObject, id: String, enabled: Boolean): JsonObject {
        val current = (settings["extensions"] as? JsonArray).orEmpty().filterNot { (it as? JsonPrimitive)?.content in setOf("-$id", "+$id") }
        val next = if (enabled) current else current + JsonPrimitive("-$id")
        return PiJsonFile.set(settings, "extensions", if (next.isEmpty()) null else JsonArray(next))
    }

    /** A package's own name, version and description from its package.json, when installed. */
    fun withManifest(pkg: PiPackage): PiPackage {
        val dir = pkg.installedPath?.let(::File) ?: File(pkg.source).takeIf { it.isAbsolute } ?: return pkg
        val manifest = File(if (dir.isDirectory) dir else dir.parentFile ?: dir, "package.json")
        val json = runCatching { ProtocolJson.parseToJsonElement(manifest.readText()) as? JsonObject }.getOrNull() ?: return pkg
        return pkg.copy(name = json.str("name"), version = json.str("version"), description = json.str("description"))
    }

    /**
     * What to type for `pi install`: `npm:` is added to a bare npm name (`pi-foo`, `@scope/pi-foo@1.2`); git and URL
     * sources and absolute paths are kept. Null for an empty or unusable source.
     */
    fun normalizeSource(input: String): String? {
        val s = input.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        return when {
            s.startsWith("npm:") || s.startsWith("git:") || s.startsWith("/") -> s
            s.startsWith("https://") || s.startsWith("http://") || s.startsWith("ssh://") || s.startsWith("git@") -> s
            s.startsWith("github.com/") || s.startsWith("gitlab.com/") -> "git:$s"
            Regex("^(@[a-z0-9][a-z0-9._~-]*/)?[a-z0-9][a-z0-9._~-]*(@[^\\s]+)?$", RegexOption.IGNORE_CASE).matches(s) -> "npm:$s"
            else -> null
        }
    }
}

/** A command or tool that comes from a package, for its detail view. */
data class Provided(val name: String, val description: String, val kind: String)

object PackageResources {
    /** Commands (`get_commands`) and tools (the app helper's `tools`) whose source path lies inside [dir]. */
    fun of(dir: String?, commands: JsonObject?, tools: JsonObject?): List<Provided> {
        dir ?: return emptyList()
        val root = dir.trimEnd('/')
        fun inside(info: JsonObject?): Boolean {
            info ?: return false
            val path = info.str("baseDir") ?: info.str("path") ?: return false
            return path == root || path.startsWith("$root/")
        }
        val fromCommands = (commands?.get("commands") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { inside(it.obj("sourceInfo")) }
            .map { Provided("/" + (it.str("name") ?: ""), it.str("description").orEmpty(), it.str("source") ?: "command") }
        val fromTools = (tools?.get("tools") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .filter { inside(it.obj("sourceInfo")) }
            .map { Provided(it.str("name").orEmpty(), it.str("description").orEmpty().lineSequence().firstOrNull().orEmpty(), "tool") }
        return fromCommands + fromTools
    }
}
