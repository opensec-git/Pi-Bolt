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
    /** Two letters for the badge, unique in the catalog (four providers start with "O"). */
    val badge: String = ProviderCatalog.badgeFor(name),
)

/**
 * Providers that work with a pasted API key, most used first. Ids and names follow packages/ai/src/env-api-keys.ts
 * and docs/providers.md; OAuth-only providers and those needing more settings (Azure, Bedrock, Vertex, Cloudflare)
 * are left out: they are set up from Pi itself.
 */
object ProviderCatalog {
    val all: List<ProviderInfo> = listOf(
        ProviderInfo("anthropic", "Anthropic", "Claude models", "https://console.anthropic.com/settings/keys", "sk-ant-…", badge = "An"),
        ProviderInfo("openai", "OpenAI", "GPT models", "https://platform.openai.com/api-keys", "sk-…", badge = "Op"),
        ProviderInfo("google", "Google Gemini", "Gemini models", "https://aistudio.google.com/apikey", "AIza…", badge = "Gm"),
        ProviderInfo("openrouter", "OpenRouter", "Many providers through one key", "https://openrouter.ai/keys", "sk-or-…", badge = "OR"),
        ProviderInfo("deepseek", "DeepSeek", "DeepSeek models", "https://platform.deepseek.com/api_keys", "sk-…", badge = "DS"),
        ProviderInfo("opencode", "OpenCode Zen", "Curated coding models", "https://opencode.ai/auth", badge = "OZ"),
        ProviderInfo("opencode-go", "OpenCode Go", "Low-cost coding models", "https://opencode.ai/auth", badge = "OG"),
        ProviderInfo("groq", "Groq", "Fast open models", "https://console.groq.com/keys", "gsk_…", badge = "Gq"),
        ProviderInfo("xai", "xAI", "Grok models", "https://console.x.ai", "xai-…", badge = "xA"),
        ProviderInfo("mistral", "Mistral", "Mistral and Codestral", "https://console.mistral.ai/api-keys", badge = "Mi"),
        ProviderInfo("cerebras", "Cerebras", "Fast open models", "https://cloud.cerebras.ai", "csk-…", badge = "Cb"),
        ProviderInfo("moonshotai", "Moonshot AI", "Kimi models", "https://platform.moonshot.ai/console/api-keys", "sk-…", badge = "Ms"),
        ProviderInfo("kimi-coding", "Kimi For Coding", "Kimi coding plan", "https://www.kimi.com/coding", badge = "Ki"),
        ProviderInfo("zai", "Z.ai Coding Plan", "GLM models", "https://z.ai/manage-apikey/apikey-list", badge = "Za"),
        ProviderInfo("minimax", "MiniMax", "MiniMax models", "https://www.minimax.io/platform", badge = "MM"),
        ProviderInfo("vercel-ai-gateway", "Vercel AI Gateway", "Many providers through one key", "https://vercel.com/ai-gateway", badge = "Vc"),
        ProviderInfo("huggingface", "Hugging Face", "Inference providers", "https://huggingface.co/settings/tokens", "hf_…", badge = "HF"),
        ProviderInfo("fireworks", "Fireworks", "Open models", "https://fireworks.ai/account/api-keys", "fw_…", badge = "Fw"),
        ProviderInfo("together", "Together AI", "Open models", "https://api.together.ai/settings/api-keys", badge = "To"),
        ProviderInfo("nvidia", "NVIDIA NIM", "Hosted NIM models", "https://build.nvidia.com", "nvapi-…", badge = "NV"),
        ProviderInfo("baseten", "Baseten", "Hosted open models", "https://app.baseten.co/settings/api_keys", badge = "Bt"),
    )

    /** Shown first in the picker, in this order. */
    val popularIds: List<String> = listOf("anthropic", "openai", "google", "openrouter")

    val popular: List<ProviderInfo> get() = popularIds.mapNotNull { get(it) }

    val more: List<ProviderInfo> get() = all.filter { it.id !in popularIds }

    private val byId = all.associateBy { it.id }

    /** Names of custom endpoints (models.json), registered when that file is read. */
    @Volatile
    private var customNames: Map<String, String> = emptyMap()

    fun get(id: String): ProviderInfo? = byId[id]

    /** Remembers the display names of custom endpoints: their `name`, else their host. */
    fun registerCustom(providers: List<CustomProvider>) {
        customNames = providers.associate { p -> p.id to (p.name ?: hostOf(p.baseUrl) ?: p.id) }
    }

    /**
     * Display name for any provider id, including ones set up outside this app: the catalog name, a custom endpoint's
     * name, else the id in words ("amazon-bedrock" is "Amazon bedrock").
     */
    fun name(id: String): String = byId[id]?.name ?: customNames[id] ?: humanize(id)

    fun badge(id: String): String = byId[id]?.badge ?: badgeFor(name(id))

    /** "fake" is "Fake", "amazon-bedrock" is "Amazon bedrock". */
    fun humanize(id: String): String =
        id.replace('-', ' ').replace('_', ' ').trim().replaceFirstChar { it.titlecase() }.ifEmpty { id }

    /** Two letters from a name: the initials of its first two words, else its first two letters. */
    fun badgeFor(name: String): String {
        val words = name.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        return when {
            words.isEmpty() -> "?"
            words.size >= 2 -> (words[0].take(1) + words[1].take(1)).uppercase()
            else -> words[0].take(1).uppercase() + words[0].drop(1).take(1).lowercase()
        }
    }

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
