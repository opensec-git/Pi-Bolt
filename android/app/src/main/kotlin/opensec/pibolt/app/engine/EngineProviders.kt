package opensec.pibolt.app.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import opensec.pibolt.protocol.arr
import opensec.pibolt.protocol.bool
import opensec.pibolt.protocol.obj
import opensec.pibolt.protocol.rpc.ModelInfo
import opensec.pibolt.protocol.str

/** A chat model of an [EngineProvider]. */
data class EngineModel(val id: String, val name: String, val reasoning: Boolean, val input: List<String>?, val contextWindow: Long?)

/**
 * A provider as the engine describes it: Pi's `get_providers` (engine 1.1.7 and later), or the app's helper extension
 * on older engines ([AppExtension]'s `providers`, which lists the providers extensions register and those with a usable
 * model). Never holds a credential value.
 */
data class EngineProvider(
    val id: String,
    val name: String,
    /** "builtin", "extension", "models_json" or "virtual"; null when the engine did not say (the helper). */
    val origin: String?,
    /** An extension registers this provider or changes it. */
    val extension: Boolean,
    /** Its auth works now. */
    val configured: Boolean,
    /** It takes an API key: the key's name, e.g. "API key"; null when it does not. */
    val keyName: String?,
    /** The variables the provider reads its key from, when it declares them (`$ACME_API_KEY`). */
    val keyEnv: List<String>,
    /** Its login flow's name, e.g. "Acme SSO"; null without one. */
    val loginName: String?,
    /** The login option's own label, when the provider has one. */
    val loginLabel: String?,
    val models: List<EngineModel>,
    /** Ids of the models that work now. */
    val available: List<String>,
) {
    val acceptsKey: Boolean get() = keyName != null
    val canLogin: Boolean get() = loginName != null
    val usable: Boolean get() = available.isNotEmpty()

    companion object {
        fun from(json: JsonObject): EngineProvider? {
            val id = json.str("id")?.takeIf { it.isNotBlank() } ?: return null
            val models = json.arr("models").orEmpty().mapNotNull { m ->
                val o = m as? JsonObject ?: return@mapNotNull null
                val mid = o.str("id") ?: return@mapNotNull null
                EngineModel(
                    id = mid,
                    name = o.str("name") ?: mid,
                    reasoning = o.bool("reasoning") ?: false,
                    input = o.arr("input")?.mapNotNull { (it as? JsonPrimitive)?.content },
                    contextWindow = (o["contextWindow"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong(),
                )
            }
            val key = json.obj("apiKey")
            val login = json.obj("oauth")
            return EngineProvider(
                id = id,
                name = json.str("name")?.takeIf { it.isNotBlank() } ?: id,
                origin = json.str("origin"),
                extension = json.bool("extension") ?: false,
                configured = json.bool("configured") ?: false,
                keyName = key?.let { it.str("name") ?: "API key" },
                keyEnv = key?.arr("env")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
                loginName = login?.let { it.str("name") ?: "Sign in" },
                loginLabel = login?.str("loginLabel"),
                models = models,
                available = json.arr("available")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
            )
        }
    }

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        origin?.let { put("origin", it) }
        put("extension", extension)
        put("configured", configured)
        keyName?.let { k ->
            putJsonObject("apiKey") {
                put("name", k)
                putJsonArray("env") { keyEnv.forEach { add(JsonPrimitive(it)) } }
            }
        }
        loginName?.let { n ->
            putJsonObject("oauth") {
                put("name", n)
                loginLabel?.let { put("loginLabel", it) }
            }
        }
        putJsonArray("models") {
            for (m in models) {
                add(
                    buildJsonObject {
                        put("id", m.id)
                        put("name", m.name)
                        put("reasoning", m.reasoning)
                        m.input?.let { i -> put("input", JsonArray(i.map(::JsonPrimitive))) }
                        m.contextWindow?.let { put("contextWindow", it) }
                    },
                )
            }
        }
        putJsonArray("available") { available.forEach { add(JsonPrimitive(it)) } }
    }
}

/**
 * What the engine said about its providers. [complete]: from `get_providers`, every provider; else the helper's list
 * (extension providers and those with a usable model only).
 */
data class ProviderReport(val providers: List<EngineProvider>, val complete: Boolean) {
    private val byId = providers.associateBy { it.id }

    operator fun get(id: String): EngineProvider? = byId[id]

    /** The models that work now, every provider's, as the model picker lists them. */
    val availableModels: List<ModelInfo>
        get() = providers.flatMap { p ->
            val usable = p.available.toSet()
            p.models.filter { it.id in usable }.map { m -> ModelInfo(m.id, m.name, p.id, m.reasoning, m.contextWindow, m.input) }
        }

    fun toJson(): JsonObject = buildJsonObject {
        put("complete", complete)
        put("providers", buildJsonArray { providers.forEach { add(it.toJson()) } })
    }

    companion object {
        /** A `get_providers` answer's data, the helper's result, or [toJson]'s output. */
        fun from(json: JsonObject?, complete: Boolean = json?.bool("complete") ?: false): ProviderReport? {
            val list = json?.arr("providers") ?: return null
            return ProviderReport(list.mapNotNull { (it as? JsonObject)?.let(EngineProvider::from) }, complete)
        }
    }
}

/**
 * How Settings > Providers, Home and the model pickers read the providers: Pi's auth.json, the custom endpoints of
 * models.json, the app's catalog of built-in providers and the engine's [ProviderReport]. Pure, for tests.
 */
object ProviderRules {
    enum class Kind { BuiltIn, Custom, Extension, Other }

    /** One row of Settings > Providers. */
    data class Row(
        val id: String,
        val name: String,
        val kind: Kind,
        /** A credential for it is in auth.json (Remove deletes it). */
        val stored: Boolean,
        /** It has a model that works now (or a stored credential the engine has not checked yet). */
        val connected: Boolean,
        /** The engine's description, when it gave one. */
        val engine: EngineProvider?,
    ) {
        /** An extension provider still waiting for a key or a sign-in. */
        val needsSetup: Boolean get() = !connected && engine != null && (engine.acceptsKey || engine.canLogin)
    }

    /** The provider ids with a working model: Home's "a model is connected", the chat pill, the default-model picker. */
    fun connected(stored: Set<String>, report: ProviderReport?, known: Set<String>): Set<String> {
        if (report == null) return stored
        val usable = report.providers.filter { it.usable }.map { it.id }
        // A stored credential counts until the engine has checked it; one for a provider no package registers any more
        // (removed) does not, unless the app knows the provider itself.
        val kept = stored.filter { id -> id in known || report[id] != null }
        return (usable + kept).toSet()
    }

    /**
     * The providers an extension adds (not a built-in of the catalog, not a custom endpoint): what Settings lists and the
     * provider sheet offers beside the catalog. One with no models yet shows only when the user can do something about
     * it (sign in, or a key it names): Pi's own llama.cpp provider waits for a local server, not for a key.
     */
    fun extensionProviders(report: ProviderReport?, catalog: Set<String>, custom: Set<String>): List<EngineProvider> =
        report?.providers.orEmpty().filter { p ->
            p.id !in catalog && p.id !in custom && (p.origin == "extension" || (p.origin == null && p.extension)) &&
                (p.models.isNotEmpty() || p.canLogin || p.keyEnv.isNotEmpty())
        }

    /**
     * Settings > Providers: every provider with a stored credential, then the extension providers without one (to add
     * a key or sign in), by name. [name] gives a display name for an id the engine did not describe.
     */
    fun rows(
        stored: Set<String>,
        report: ProviderReport?,
        catalog: Set<String>,
        custom: Set<String>,
        name: (String) -> String,
    ): List<Row> {
        val connected = connected(stored, report, catalog + custom)
        val extensions = extensionProviders(report, catalog, custom)
        val extensionIds = extensions.map { it.id }.toSet()
        fun kind(id: String) = when (id) {
            in custom -> Kind.Custom
            in catalog -> Kind.BuiltIn
            in extensionIds -> Kind.Extension
            else -> Kind.Other
        }
        val fromStore = stored.map { id ->
            val e = report?.get(id)
            Row(id, e?.name?.takeIf { kind(id) == Kind.Extension } ?: name(id), kind(id), stored = true, connected = id in connected, engine = e)
        }
        val fromEngine = extensions.filter { it.id !in stored }.map { e ->
            Row(e.id, e.name, Kind.Extension, stored = false, connected = e.id in connected, engine = e)
        }
        return (fromStore + fromEngine).sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
    }

    /** "Needs a key", "Sign in", "2 models", for an extension provider's row. */
    fun extensionNote(p: EngineProvider, stored: Boolean): String = when {
        p.usable -> if (p.available.size == 1) "1 model" else "${p.available.size} models"
        stored -> "No model works with this credential"
        p.acceptsKey && p.canLogin -> "Add a key or sign in"
        p.acceptsKey -> "Needs a key" + (p.keyEnv.firstOrNull()?.let { " ($it)" } ?: "")
        p.canLogin -> "Sign in to use it"
        p.models.isEmpty() -> "No models yet"
        else -> "Not available"
    }
}
