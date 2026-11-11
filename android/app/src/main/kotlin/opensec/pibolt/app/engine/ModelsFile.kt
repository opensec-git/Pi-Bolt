package opensec.pibolt.app.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.str
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Locale

/** A custom OpenAI-compatible endpoint as the provider sheet collects it. */
data class CustomEndpoint(
    /** Pi's provider id: the key under `providers` in models.json and in auth.json. */
    val id: String,
    val baseUrl: String,
    val modelId: String,
    /** Shown in the app instead of the id; stored as the provider's `name`. */
    val displayName: String? = null,
)

/** A custom provider found in models.json (one the app wrote, or any with its own `baseUrl` and models). */
data class CustomProvider(val id: String, val name: String?, val baseUrl: String, val models: List<String>)

/**
 * Pi's models.json in the agent dir (packages/coding-agent/docs/models.md, models-configuration.md): custom providers
 * under `providers`, each `{ baseUrl, api, apiKey?, models: [{ id }] }`. The app only adds or removes its own provider
 * entries; `$schema`, other providers, `modelOverrides` and unknown fields are kept. A file that does not parse is
 * never replaced: the merge refuses with an error instead.
 *
 * Keys: a real key goes to auth.json (mode 0600, where Pi looks first), never here. An endpoint without a key gets the
 * placeholder [NO_KEY] as its `apiKey` (the docs' Ollama pattern), so Pi lists its models.
 */
object ModelsFile {
    const val API = "openai-completions"
    const val NO_KEY = "pi-bolt-no-key"
    const val SCHEMA = "https://pi.dev/schemas/models.schema.json"

    class DamagedFile(message: String) : IllegalStateException(message)

    /** Parses models.json text; blank or absent is an empty object, anything else that is not an object throws [DamagedFile]. */
    fun parse(text: String?): JsonObject {
        val body = text?.removePrefix(BOM)?.trim().orEmpty()
        if (body.isEmpty()) return JsonObject(emptyMap())
        val element = runCatching { ProtocolJson.parseToJsonElement(body) }.getOrNull()
        return element as? JsonObject ?: throw DamagedFile("models.json could not be read. Fix or remove it, then try again.")
    }

    /**
     * [root] with [endpoint] added: its provider gets the base URL, API and (when [keyless]) the placeholder key; the
     * model is added, or replaces the one with the same id. Other fields of that provider and its other models stay.
     */
    fun merge(root: JsonObject, endpoint: CustomEndpoint, keyless: Boolean): JsonObject {
        val providers = root["providers"] as? JsonObject ?: JsonObject(emptyMap())
        val existing = providers[endpoint.id] as? JsonObject ?: JsonObject(emptyMap())
        val models = (existing["models"] as? JsonArray).orEmpty()
        val model = (models.firstOrNull { (it as? JsonObject)?.str("id") == endpoint.modelId } as? JsonObject)
            ?.let { JsonObject(it + ("id" to JsonPrimitive(endpoint.modelId))) }
            ?: buildJsonObject { put("id", endpoint.modelId) }
        val nextModels = models.filterNot { (it as? JsonObject)?.str("id") == endpoint.modelId } + model
        val provider = LinkedHashMap<String, JsonElement>(existing)
        endpoint.displayName?.trim()?.takeIf { it.isNotEmpty() }?.let { provider["name"] = JsonPrimitive(it) }
        provider["baseUrl"] = JsonPrimitive(endpoint.baseUrl)
        provider["api"] = JsonPrimitive(API)
        if (keyless) provider["apiKey"] = JsonPrimitive(NO_KEY) else if (provider["apiKey"]?.let { (it as? JsonPrimitive)?.content } == NO_KEY) provider.remove("apiKey")
        provider["models"] = JsonArray(nextModels)
        val out = LinkedHashMap<String, JsonElement>()
        if (root.isEmpty()) out["\$schema"] = JsonPrimitive(SCHEMA)
        out.putAll(root)
        out["providers"] = JsonObject(providers + (endpoint.id to JsonObject(provider)))
        return JsonObject(out)
    }

    /**
     * True when the provider [id] is already in [root] with a real key (its key in auth.json, so no placeholder here).
     * Adding another model to it with the key field left empty keeps that key instead of replacing it with [NO_KEY].
     */
    fun hasSavedKey(root: JsonObject, id: String): Boolean {
        val provider = (root["providers"] as? JsonObject)?.get(id) as? JsonObject ?: return false
        return (provider["apiKey"] as? JsonPrimitive)?.content != NO_KEY
    }

    /** [root] without the provider [id]; everything else stays. */
    fun remove(root: JsonObject, id: String): JsonObject {
        val providers = root["providers"] as? JsonObject ?: return root
        if (id !in providers) return root
        return JsonObject(root + ("providers" to JsonObject(providers - id)))
    }

    /** Providers with their own `baseUrl` and at least one model: endpoints, not proxied built-ins. */
    fun customProviders(root: JsonObject): List<CustomProvider> {
        val providers = root["providers"] as? JsonObject ?: return emptyList()
        return providers.mapNotNull { (id, value) ->
            val p = value as? JsonObject ?: return@mapNotNull null
            val base = p.str("baseUrl") ?: return@mapNotNull null
            val models = (p["models"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("id") }
            if (models.isEmpty()) null else CustomProvider(id, p.str("name")?.takeIf { it.isNotBlank() }, base, models)
        }
    }

    /** Why [url] is not a usable base URL, or null when it is (http or https with a host). */
    fun urlError(url: String): String? {
        val u = url.trim()
        if (u.isEmpty()) return "Enter the endpoint's base URL"
        val parsed = runCatching { URI(u) }.getOrNull() ?: return "That is not a URL"
        if (parsed.scheme?.lowercase() !in setOf("http", "https")) return "Use an http:// or https:// URL"
        if (parsed.host.isNullOrBlank()) return "The URL needs a host"
        if (u.any { it.isWhitespace() }) return "The URL has spaces"
        return null
    }

    /** The base URL as stored: trimmed, without a trailing slash. */
    fun cleanUrl(url: String): String = url.trim().trimEnd('/')

    /**
     * A provider id for a new endpoint: a slug of [name] when given, else `custom-<host>[-<port>]`. Never the id of a
     * built-in provider in [reserved].
     */
    fun providerId(url: String, name: String?, reserved: Set<String>): String {
        val fromName = name?.let(::slug)?.takeIf { it.isNotEmpty() }
        val base = fromName ?: run {
            val uri = runCatching { URI(url.trim()) }.getOrNull()
            val host = uri?.host?.let(::slug).orEmpty().ifEmpty { "endpoint" }
            val port = uri?.port?.takeIf { it > 0 }?.let { "-$it" }.orEmpty()
            "custom-$host$port"
        }
        return if (base in reserved) "custom-$base" else base
    }

    private fun slug(text: String): String =
        text.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48)

    /** Reads models.json from [agentDir] (IO). Throws [DamagedFile] for unparseable content. */
    suspend fun read(agentDir: File): JsonObject = withContext(Dispatchers.IO) {
        val file = File(agentDir, NAME)
        parse(if (file.isFile) file.readText() else null)
    }

    /** Custom providers in models.json; empty when the file is absent or damaged. */
    suspend fun custom(agentDir: File): List<CustomProvider> = runCatching { customProviders(read(agentDir)) }.getOrDefault(emptyList())

    /** Applies [change] to models.json in [agentDir] and writes it atomically (temp file, then rename). */
    suspend fun update(agentDir: File, change: (JsonObject) -> JsonObject): Unit = withContext(Dispatchers.IO) {
        val file = File(agentDir, NAME)
        val current = parse(if (file.isFile) file.readText() else null)
        val next = change(current)
        if (next == current && file.isFile) return@withContext
        if (!agentDir.isDirectory) agentDir.mkdirs()
        val temp = File(agentDir, "$NAME.${System.nanoTime()}.tmp")
        temp.writeText(Pretty.encodeToString(JsonObject.serializer(), next) + "\n")
        runCatching { Files.setPosixFilePermissions(temp.toPath(), PosixFilePermissions.fromString("rw-------")) }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private const val NAME = "models.json"
    private val BOM = Char(0xFEFF).toString()
    private val Pretty = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }
}
