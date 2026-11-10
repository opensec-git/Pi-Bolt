package opensec.pibolt.app.ui.providers

import java.net.URI

/**
 * A provider or model maker with a mark in `res/drawable/ic_provider_<slug>.xml` (monochrome Lobe Icons marks, MIT,
 * converted by android/tools/provider-logos.mjs). [Generic] has no mark of its own: it is drawn with a server glyph,
 * for custom endpoints and providers the app does not know. [title] is the maker's name as the model sheet groups by it
 * (Pi-Bolt Desktop's family labels: Gemini and Gemma are both "Google", Kimi is "Moonshot").
 */
enum class Brand(val slug: String, val title: String) {
    Anthropic("anthropic", "Anthropic"),
    Claude("claude", "Anthropic"),
    OpenAI("openai", "OpenAI"),
    Gemini("gemini", "Google"),
    Google("google", "Google"),
    XAi("xai", "xAI"),
    Mistral("mistral", "Mistral"),
    DeepSeek("deepseek", "DeepSeek"),
    Groq("groq", "Groq"),
    OpenRouter("openrouter", "OpenRouter"),
    Ollama("ollama", "Ollama"),
    Moonshot("moonshot", "Moonshot AI"),
    Kimi("kimi", "Moonshot"),
    Qwen("qwen", "Qwen"),
    Meta("meta", "Meta"),
    Cerebras("cerebras", "Cerebras"),
    Bedrock("bedrock", "Amazon Bedrock"),
    Amazon("aws", "Amazon"),
    Azure("azure", "Azure"),
    GitHubCopilot("githubcopilot", "GitHub Copilot"),
    HuggingFace("huggingface", "Hugging Face"),
    Together("together", "Together AI"),
    Fireworks("fireworks", "Fireworks"),
    Perplexity("perplexity", "Perplexity"),
    Cohere("cohere", "Cohere"),
    Zai("zai", "Z.ai"),
    MiniMax("minimax", "MiniMax"),
    Vercel("vercel", "Vercel"),
    Nvidia("nvidia", "NVIDIA"),
    Baseten("baseten", "Baseten"),
    OpenCode("opencode", "OpenCode"),
    LmStudio("lmstudio", "LM Studio"),
    Vllm("vllm", "vLLM"),
    Microsoft("microsoft", "Microsoft"),
    Cloudflare("cloudflare", "Cloudflare"),
    Xiaomi("xiaomi", "Xiaomi MiMo"),
    Ling("ling", "Ling"),
    Generic("generic", "Custom endpoint"),
}

/**
 * Which mark a provider id or a model id gets. Pure: no Android, no state; [ProviderCatalog.brand] knows the custom
 * endpoints and picks [forEndpoint] for them.
 */
object ProviderLogos {
    /** Pi's provider ids (packages/ai/src/types.ts KnownProvider) and common custom names. */
    private val providers: Map<String, Brand> = mapOf(
        "anthropic" to Brand.Anthropic,
        "openai" to Brand.OpenAI,
        "openai-codex" to Brand.OpenAI,
        "google" to Brand.Gemini,
        "google-vertex" to Brand.Gemini,
        "google-gemini-cli" to Brand.Gemini,
        "google-antigravity" to Brand.Gemini,
        "gemini" to Brand.Gemini,
        "xai" to Brand.XAi,
        "grok" to Brand.XAi,
        "mistral" to Brand.Mistral,
        "mistralai" to Brand.Mistral,
        "deepseek" to Brand.DeepSeek,
        "groq" to Brand.Groq,
        "openrouter" to Brand.OpenRouter,
        "ollama" to Brand.Ollama,
        "moonshotai" to Brand.Moonshot,
        "moonshot" to Brand.Moonshot,
        "kimi" to Brand.Kimi,
        "kimi-coding" to Brand.Kimi,
        "qwen" to Brand.Qwen,
        "alibaba" to Brand.Qwen,
        "dashscope" to Brand.Qwen,
        "bailian" to Brand.Qwen,
        "meta" to Brand.Meta,
        "llama" to Brand.Meta,
        "cerebras" to Brand.Cerebras,
        "amazon-bedrock" to Brand.Bedrock,
        "bedrock" to Brand.Bedrock,
        "amazon" to Brand.Amazon,
        "aws" to Brand.Amazon,
        "azure" to Brand.Azure,
        "azure-openai" to Brand.Azure,
        "github-copilot" to Brand.GitHubCopilot,
        "copilot" to Brand.GitHubCopilot,
        "huggingface" to Brand.HuggingFace,
        "hf" to Brand.HuggingFace,
        "together" to Brand.Together,
        "togetherai" to Brand.Together,
        "fireworks" to Brand.Fireworks,
        "perplexity" to Brand.Perplexity,
        "cohere" to Brand.Cohere,
        "zai" to Brand.Zai,
        "z-ai" to Brand.Zai,
        "zhipu" to Brand.Zai,
        "zhipuai" to Brand.Zai,
        "glm" to Brand.Zai,
        "minimax" to Brand.MiniMax,
        "vercel" to Brand.Vercel,
        "vercel-ai-gateway" to Brand.Vercel,
        "nvidia" to Brand.Nvidia,
        "baseten" to Brand.Baseten,
        "opencode" to Brand.OpenCode,
        "opencode-go" to Brand.OpenCode,
        "lmstudio" to Brand.LmStudio,
        "lm-studio" to Brand.LmStudio,
        "vllm" to Brand.Vllm,
        "microsoft" to Brand.Microsoft,
        "cloudflare" to Brand.Cloudflare,
        "xiaomi" to Brand.Xiaomi,
        "ant-ling" to Brand.Ling,
    )

    /**
     * The mark for a provider id: a known id ("anthropic"), else its longest known hyphen prefix ("qwen-token-plan-cn"
     * is Qwen, "azure-openai-responses" Azure), else a local server named in it ("home-ollama"), else [Brand.Generic].
     */
    fun forProvider(id: String?): Brand {
        val key = id?.trim()?.lowercase().orEmpty()
        if (key.isEmpty()) return Brand.Generic
        providers[key]?.let { return it }
        var cut = key
        while ('-' in cut) {
            cut = cut.substringBeforeLast('-')
            providers[cut]?.let { return it }
        }
        return localServer(key) ?: Brand.Generic
    }

    /**
     * A custom endpoint (models.json): its base URL decides (a known API host, Ollama's or LM Studio's port, a server
     * named in the host), then its name or id ("Home Ollama"); else [Brand.Generic]. Never a prefix guess from the id:
     * an endpoint named "openai-proxy" is not OpenAI.
     */
    fun forEndpoint(id: String, name: String?, baseUrl: String): Brand =
        forUrl(baseUrl) ?: localServer(listOfNotNull(name, id).joinToString(" ").lowercase()) ?: Brand.Generic

    /** A custom endpoint's server, from its base URL: a known API host, or Ollama's and LM Studio's default ports. */
    fun forUrl(url: String): Brand? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removeSuffix(".") ?: return null
        hosts.firstOrNull { (domain, _) -> host == domain || host.endsWith(".$domain") }?.let { return it.second }
        return when (uri.port) {
            11434 -> Brand.Ollama
            1234 -> Brand.LmStudio
            else -> localServer(host)
        }
    }

    /** A name or host that says which local server it is ("home-ollama", "lmstudio.lan"). */
    private fun localServer(text: String): Brand? = when {
        "ollama" in text -> Brand.Ollama
        "lmstudio" in text || "lm-studio" in text || "lm studio" in text -> Brand.LmStudio
        "vllm" in text -> Brand.Vllm
        else -> null
    }

    private val hosts: List<Pair<String, Brand>> = listOf(
        "anthropic.com" to Brand.Anthropic,
        "openai.com" to Brand.OpenAI,
        "openai.azure.com" to Brand.Azure,
        "azure.com" to Brand.Azure,
        "generativelanguage.googleapis.com" to Brand.Gemini,
        "x.ai" to Brand.XAi,
        "mistral.ai" to Brand.Mistral,
        "deepseek.com" to Brand.DeepSeek,
        "groq.com" to Brand.Groq,
        "openrouter.ai" to Brand.OpenRouter,
        "ollama.com" to Brand.Ollama,
        "moonshot.ai" to Brand.Moonshot,
        "moonshot.cn" to Brand.Moonshot,
        "dashscope.aliyuncs.com" to Brand.Qwen,
        "dashscope-intl.aliyuncs.com" to Brand.Qwen,
        "cerebras.ai" to Brand.Cerebras,
        "huggingface.co" to Brand.HuggingFace,
        "together.xyz" to Brand.Together,
        "together.ai" to Brand.Together,
        "fireworks.ai" to Brand.Fireworks,
        "perplexity.ai" to Brand.Perplexity,
        "cohere.com" to Brand.Cohere,
        "cohere.ai" to Brand.Cohere,
        "z.ai" to Brand.Zai,
        "bigmodel.cn" to Brand.Zai,
        "minimax.io" to Brand.MiniMax,
        "minimaxi.com" to Brand.MiniMax,
        "nvidia.com" to Brand.Nvidia,
        "baseten.co" to Brand.Baseten,
        "opencode.ai" to Brand.OpenCode,
        "vercel.sh" to Brand.Vercel,
        "githubcopilot.com" to Brand.GitHubCopilot,
        "cloudflare.com" to Brand.Cloudflare,
    )

    /** Organisation namespaces in model ids: "meta-llama/Llama-3.3-70B", Bedrock's "us.anthropic.claude-…". */
    private val orgs: Map<String, Brand> = mapOf(
        "anthropic" to Brand.Claude,
        "openai" to Brand.OpenAI,
        "google" to Brand.Gemini,
        "x-ai" to Brand.XAi,
        "xai" to Brand.XAi,
        "mistral" to Brand.Mistral,
        "mistralai" to Brand.Mistral,
        "deepseek" to Brand.DeepSeek,
        "deepseek-ai" to Brand.DeepSeek,
        "meta" to Brand.Meta,
        "meta-llama" to Brand.Meta,
        "qwen" to Brand.Qwen,
        "alibaba" to Brand.Qwen,
        "moonshotai" to Brand.Kimi,
        "moonshot" to Brand.Kimi,
        "zai-org" to Brand.Zai,
        "z-ai" to Brand.Zai,
        "zai" to Brand.Zai,
        "thudm" to Brand.Zai,
        "zhipuai" to Brand.Zai,
        "minimax" to Brand.MiniMax,
        "minimaxai" to Brand.MiniMax,
        "cohere" to Brand.Cohere,
        "coherelabs" to Brand.Cohere,
        "coherforai" to Brand.Cohere,
        "nvidia" to Brand.Nvidia,
        "microsoft" to Brand.Microsoft,
        "perplexity" to Brand.Perplexity,
        "amazon" to Brand.Amazon,
        "xiaomimimo" to Brand.Xiaomi,
        "xiaomi" to Brand.Xiaomi,
        "inclusionai" to Brand.Ling,
    )

    /** Bedrock's cross-region inference prefixes: "us.anthropic.claude-…", "global.anthropic.claude-…". */
    private val regions = setOf("us", "eu", "apac", "global", "au", "jp", "ca", "us-gov")

    /**
     * Model families by name, each matched at the start of a word ("deepseek-r1-distill-llama" is DeepSeek: the
     * earliest match wins). `o1`, `o3`, `o4` count only as a whole word ("o3-mini", not "gpt-4o").
     */
    private val families: List<Pair<Regex, Brand>> = listOf(
        "claude|opus|sonnet|haiku" to Brand.Claude,
        "chatgpt|gpt|o[1-9](?=$|[-:._ ])|codex|dall-e|whisper|text-embedding|davinci|babbage" to Brand.OpenAI,
        "gemini" to Brand.Gemini,
        "gemma|codegemma" to Brand.Google,
        "grok" to Brand.XAi,
        "mistral|mixtral|codestral|devstral|magistral|pixtral|ministral|voxtral" to Brand.Mistral,
        "deepseek" to Brand.DeepSeek,
        "llama|codellama" to Brand.Meta,
        "qwen|qwq|qvq" to Brand.Qwen,
        "kimi|moonshot" to Brand.Kimi,
        "glm|chatglm|codegeex" to Brand.Zai,
        "minimax|abab" to Brand.MiniMax,
        "command(?=$|[-:._ ])|c4ai|aya(?=$|[-:._ ])" to Brand.Cohere,
        "sonar|r1-1776" to Brand.Perplexity,
        "phi-?[0-9]" to Brand.Microsoft,
        "nemotron" to Brand.Nvidia,
        "nova(?=$|[-:._ ])|titan" to Brand.Amazon,
        "mimo" to Brand.Xiaomi,
        "ling(?=$|[-:._ ])" to Brand.Ling,
    ).map { (pattern, brand) -> Regex("(?<![a-z0-9])(?:$pattern)") to brand }

    /**
     * Who made [modelId], from its organisation namespace ("anthropic/claude-…", "us.anthropic.claude-…") or its
     * family name ("gpt-5", "llama-3.3-70b", "qwen2.5-coder:7b"); null when neither says.
     */
    fun maker(modelId: String?): Brand? {
        val id = modelId?.trim()?.lowercase().orEmpty()
        if (id.isEmpty()) return null
        // The family names the model best ("google/gemma-3" is Gemma, "us.anthropic.claude-…" Claude).
        val name = id.substringAfterLast('/')
        families
            .mapNotNull { (regex, brand) -> regex.find(name)?.let { it.range.first to brand } }
            .minByOrNull { it.first }
            ?.let { return it.second }
        // Then the organisation: "openrouter/auto", "writer/…"; Fireworks' "accounts/x/models/m" is a path, not one.
        if ('/' in id) {
            val org = id.substringBefore('/')
            if (org == "openrouter") return Brand.OpenRouter
            orgs[org]?.let { return it }
        }
        // Bedrock: "amazon.titan-…", "us.ai21.jamba-…" (region prefix first).
        val dotted = id.split('.')
        if (dotted.size >= 2) {
            val vendor = if (dotted[0] in regions && dotted.size >= 3) dotted[1] else dotted[0]
            orgs[vendor]?.let { return it }
        }
        return null
    }

    /** The mark for a model: its maker, else [providerBrand] (an Ollama model without a known family is Ollama's). */
    fun forModel(modelId: String?, providerBrand: Brand = Brand.Generic): Brand = maker(modelId) ?: providerBrand
}
