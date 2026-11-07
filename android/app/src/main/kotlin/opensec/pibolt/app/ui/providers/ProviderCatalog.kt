package opensec.pibolt.app.ui.providers

import androidx.compose.runtime.Immutable
import opensec.pibolt.app.engine.CustomProvider
import java.net.URI

/** A provider that takes an API key. [id] is Pi's provider id (the key in auth.json). */
@Immutable
data class ProviderInfo(
    val id: String,
    val name: String,
    /** One line under the name. */
    val note: String,
    /** Where the user creates a key. */
    val keyUrl: String?,
    /** The key's usual prefix, shown as a placeholder hint. */
    val keyHint: String = "",
) {
    /** The provider's mark (see [ProviderLogos]). */
    val brand: Brand get() = ProviderCatalog.brand(id)
}

/**
 * Providers that work with a pasted API key, most used first. Ids and names follow packages/ai/src/env-api-keys.ts
 * and docs/providers.md; OAuth-only providers and those needing more settings (Azure, Bedrock, Vertex, Cloudflare)
 * are left out: they are set up from Pi itself.
 */
object ProviderCatalog {
    val all: List<ProviderInfo> = listOf(
        ProviderInfo("anthropic", "Anthropic", "Claude models", "https://console.anthropic.com/settings/keys", "sk-ant-…"),
        ProviderInfo("openai", "OpenAI", "GPT models", "https://platform.openai.com/api-keys", "sk-…"),
        ProviderInfo("google", "Google Gemini", "Gemini models", "https://aistudio.google.com/apikey", "AIza…"),
        ProviderInfo("openrouter", "OpenRouter", "Many providers through one key", "https://openrouter.ai/keys", "sk-or-…"),
        ProviderInfo("deepseek", "DeepSeek", "DeepSeek models", "https://platform.deepseek.com/api_keys", "sk-…"),
        ProviderInfo("opencode", "OpenCode Zen", "Curated coding models", "https://opencode.ai/auth"),
        ProviderInfo("opencode-go", "OpenCode Go", "Low-cost coding models", "https://opencode.ai/auth"),
        ProviderInfo("groq", "Groq", "Fast open models", "https://console.groq.com/keys", "gsk_…"),
        ProviderInfo("xai", "xAI", "Grok models", "https://console.x.ai", "xai-…"),
        ProviderInfo("mistral", "Mistral", "Mistral and Codestral", "https://console.mistral.ai/api-keys"),
        ProviderInfo("cerebras", "Cerebras", "Fast open models", "https://cloud.cerebras.ai", "csk-…"),
        ProviderInfo("moonshotai", "Moonshot AI", "Kimi models", "https://platform.moonshot.ai/console/api-keys", "sk-…"),
        ProviderInfo("kimi-coding", "Kimi For Coding", "Kimi coding plan", "https://www.kimi.com/coding"),
        ProviderInfo("zai", "Z.ai Coding Plan", "GLM models", "https://z.ai/manage-apikey/apikey-list"),
        ProviderInfo("minimax", "MiniMax", "MiniMax models", "https://www.minimax.io/platform"),
        ProviderInfo("vercel-ai-gateway", "Vercel AI Gateway", "Many providers through one key", "https://vercel.com/ai-gateway"),
        ProviderInfo("huggingface", "Hugging Face", "Inference providers", "https://huggingface.co/settings/tokens", "hf_…"),
        ProviderInfo("fireworks", "Fireworks", "Open models", "https://fireworks.ai/account/api-keys", "fw_…"),
        ProviderInfo("together", "Together AI", "Open models", "https://api.together.ai/settings/api-keys"),
        ProviderInfo("nvidia", "NVIDIA NIM", "Hosted NIM models", "https://build.nvidia.com", "nvapi-…"),
        ProviderInfo("baseten", "Baseten", "Hosted open models", "https://app.baseten.co/settings/api_keys"),
    )

    /** Shown first in the picker, in this order. */
    val popularIds: List<String> = listOf("anthropic", "openai", "google", "openrouter")

    val popular: List<ProviderInfo> get() = popularIds.mapNotNull { get(it) }

    val more: List<ProviderInfo> get() = all.filter { it.id !in popularIds }

    private val byId = all.associateBy { it.id }

    /** Names of custom endpoints (models.json), registered when that file is read. */
    @Volatile
    private var customNames: Map<String, String> = emptyMap()

    /** The custom endpoints' marks, from their base URLs (see [ProviderLogos.forEndpoint]). */
    @Volatile
    private var customBrands: Map<String, Brand> = emptyMap()

    fun get(id: String): ProviderInfo? = byId[id]

    /** Remembers the display names of custom endpoints: their `name`, else their host. */
    fun registerCustom(providers: List<CustomProvider>) {
        customNames = providers.associate { p -> p.id to (p.name ?: hostOf(p.baseUrl) ?: p.id) }
        customBrands = providers.associate { p -> p.id to ProviderLogos.forEndpoint(p.id, p.name, p.baseUrl) }
    }

    /** The mark for any provider id: a custom endpoint's from its server, else [ProviderLogos.forProvider]. */
    fun brand(id: String?): Brand = id?.let { customBrands[it] } ?: ProviderLogos.forProvider(id)

    /** The mark for a model: its maker, else its provider's ([ProviderLogos.forModel]). */
    fun modelBrand(modelId: String?, provider: String?): Brand = ProviderLogos.forModel(modelId, brand(provider))

    /**
     * Display name for any provider id, including ones set up outside this app: the catalog name, a custom endpoint's
     * name, else the id in words ("amazon-bedrock" is "Amazon bedrock").
     */
    fun name(id: String): String = byId[id]?.name ?: customNames[id] ?: humanize(id)

    /** "fake" is "Fake", "amazon-bedrock" is "Amazon bedrock". */
    fun humanize(id: String): String =
        id.replace('-', ' ').replace('_', ' ').trim().replaceFirstChar { it.titlecase() }.ifEmpty { id }

    fun hostOf(url: String): String? = runCatching { URI(url.trim()).host }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Providers whose name, note or id contains [query] (case-insensitive); all of them for a blank query. */
    fun search(query: String): List<ProviderInfo> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all
        return all.filter { q in it.name.lowercase() || q in it.id || q in it.note.lowercase() }
    }

    /** A pasted key without surrounding whitespace, quotes or an `export NAME=` prefix; null when empty. */
    fun cleanKey(raw: String): String? {
        var k = raw.trim()
        if (k.startsWith("export ")) k = k.removePrefix("export ").trim()
        val eq = k.indexOf('=')
        if (eq > 0 && k.substring(0, eq).all { it.isUpperCase() || it.isDigit() || it == '_' }) k = k.substring(eq + 1).trim()
        k = k.trim('"', '\'').trim()
        return k.takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }
    }

    /** "sk-ant-…a1b2": enough to recognise a key, never enough to use it. */
    fun mask(key: String): String = if (key.length <= 8) "•".repeat(key.length) else key.take(4) + "…" + key.takeLast(4)
}
