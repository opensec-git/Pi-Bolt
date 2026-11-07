package opensec.pibolt.app

import opensec.pibolt.app.engine.CustomProvider
import opensec.pibolt.app.ui.providers.Brand
import opensec.pibolt.app.ui.providers.ProviderCatalog
import opensec.pibolt.app.ui.providers.ProviderLogos
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Provider id -> mark and model id -> maker mark (ui/providers/ProviderLogos.kt), and the drawables behind them. */
class ProviderLogosTest {
    private val drawables = File("src/main/res/drawable")

    @Test
    fun everyPiProviderIdHasItsMark() {
        val expected = mapOf(
            "anthropic" to Brand.Anthropic,
            "openai" to Brand.OpenAI,
            "openai-codex" to Brand.OpenAI,
            "google" to Brand.Gemini,
            "google-vertex" to Brand.Gemini,
            "google-gemini-cli" to Brand.Gemini,
            "xai" to Brand.XAi,
            "mistral" to Brand.Mistral,
            "deepseek" to Brand.DeepSeek,
            "groq" to Brand.Groq,
            "openrouter" to Brand.OpenRouter,
            "ollama" to Brand.Ollama,
            "moonshotai" to Brand.Moonshot,
            "moonshotai-cn" to Brand.Moonshot,
            "kimi-coding" to Brand.Kimi,
            "qwen-token-plan" to Brand.Qwen,
            "qwen-token-plan-cn" to Brand.Qwen,
            "qwen-token-plan-individual" to Brand.Qwen,
            "meta" to Brand.Meta,
            "cerebras" to Brand.Cerebras,
            "amazon-bedrock" to Brand.Bedrock,
            "azure" to Brand.Azure,
            "azure-openai-responses" to Brand.Azure,
            "github-copilot" to Brand.GitHubCopilot,
            "huggingface" to Brand.HuggingFace,
            "together" to Brand.Together,
            "fireworks" to Brand.Fireworks,
            "perplexity" to Brand.Perplexity,
            "cohere" to Brand.Cohere,
            "zai" to Brand.Zai,
            "zai-coding-cn" to Brand.Zai,
            "minimax" to Brand.MiniMax,
            "minimax-cn" to Brand.MiniMax,
            "vercel-ai-gateway" to Brand.Vercel,
            "nvidia" to Brand.Nvidia,
            "baseten" to Brand.Baseten,
            "opencode" to Brand.OpenCode,
            "opencode-go" to Brand.OpenCode,
            "cloudflare-workers-ai" to Brand.Cloudflare,
            "cloudflare-ai-gateway" to Brand.Cloudflare,
            "xiaomi" to Brand.Xiaomi,
            "xiaomi-token-plan-sgp" to Brand.Xiaomi,
            "ant-ling" to Brand.Ling,
            "lmstudio" to Brand.LmStudio,
            "vllm" to Brand.Vllm,
        )
        for ((id, brand) in expected) assertEquals(brand, ProviderLogos.forProvider(id), id)
        // Case and spaces do not matter.
        assertEquals(Brand.Anthropic, ProviderLogos.forProvider(" Anthropic "))
    }

    @Test
    fun everyCatalogProviderHasARealMark() {
        for (p in ProviderCatalog.all) assertTrue(p.brand != Brand.Generic, "${p.id} has no mark")
    }

    @Test
    fun unknownProvidersGetTheGenericGlyph() {
        assertEquals(Brand.Generic, ProviderLogos.forProvider(null))
        assertEquals(Brand.Generic, ProviderLogos.forProvider(""))
        assertEquals(Brand.Generic, ProviderLogos.forProvider("radius"))
        assertEquals(Brand.Generic, ProviderLogos.forProvider("custom-192-168-1-20-8080"))
        assertEquals(Brand.Generic, ProviderLogos.forProvider("faux"))
        // A local server named in the id.
        assertEquals(Brand.Ollama, ProviderLogos.forProvider("home-ollama"))
        assertEquals(Brand.LmStudio, ProviderLogos.forProvider("my-lm-studio"))
        assertEquals(Brand.LmStudio, ProviderLogos.forEndpoint("desk", "My LM Studio", "http://10.0.0.3:8080/v1"))
    }

    @Test
    fun customEndpointsAreKnownByTheirServer() {
        assertEquals(Brand.Ollama, ProviderLogos.forEndpoint("custom-192-168-1-20-11434", null, "http://192.168.1.20:11434/v1"))
        assertEquals(Brand.LmStudio, ProviderLogos.forEndpoint("custom-localhost-1234", null, "http://localhost:1234/v1"))
        assertEquals(Brand.Vllm, ProviderLogos.forEndpoint("gpu-box", "vLLM box", "http://10.0.0.5:8000/v1"))
        assertEquals(Brand.Ollama, ProviderLogos.forEndpoint("home", "Home Ollama", "http://nas.local:8080/v1"))
        assertEquals(Brand.OpenRouter, ProviderLogos.forEndpoint("proxy", null, "https://openrouter.ai/api/v1"))
        assertEquals(Brand.OpenAI, ProviderLogos.forEndpoint("proxy", null, "https://api.openai.com/v1"))
        assertEquals(Brand.Azure, ProviderLogos.forEndpoint("work", null, "https://my-team.openai.azure.com/openai/v1"))
        assertEquals(Brand.Qwen, ProviderLogos.forEndpoint("qw", null, "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"))
        assertEquals(Brand.Groq, ProviderLogos.forEndpoint("g", null, "https://api.groq.com/openai/v1"))
        // The id alone never decides for an endpoint: "openai-proxy" on a home server is not OpenAI.
        assertEquals(Brand.Generic, ProviderLogos.forEndpoint("openai-proxy", "OpenAI proxy", "http://10.0.0.2:8080/v1"))
        assertEquals(Brand.Generic, ProviderLogos.forEndpoint("x", null, "not a url"))
        // Lookalike hosts do not match: "notopenai.com" is not openai.com.
        assertEquals(Brand.Generic, ProviderLogos.forEndpoint("x", null, "https://notopenai.com/v1"))
    }

    @Test
    fun theCatalogUsesTheEndpointsServer() {
        ProviderCatalog.registerCustom(listOf(CustomProvider("home-box", "Home box", "http://192.168.1.20:11434/v1", listOf("qwen2.5-coder:7b"))))
        try {
            assertEquals(Brand.Ollama, ProviderCatalog.brand("home-box"))
            // The model's maker still wins over its server.
            assertEquals(Brand.Qwen, ProviderCatalog.modelBrand("qwen2.5-coder:7b", "home-box"))
            assertEquals(Brand.Ollama, ProviderCatalog.modelBrand("my-finetune", "home-box"))
        } finally {
            ProviderCatalog.registerCustom(emptyList())
        }
        assertEquals(Brand.Generic, ProviderCatalog.brand("home-box"))
        assertEquals(Brand.Anthropic, ProviderCatalog.brand("anthropic"))
    }

    @Test
    fun modelsGetTheirMakersMark() {
        val expected = mapOf(
            // Direct ids. Claude models carry Claude's mark, the Anthropic provider Anthropic's (as on the desktop).
            "claude-sonnet-4-5" to Brand.Claude,
            "sonnet" to Brand.Claude,
            "claude-opus-4-1-20250805" to Brand.Claude,
            "gpt-5" to Brand.OpenAI,
            "gpt-4o-mini" to Brand.OpenAI,
            "chatgpt-4o-latest" to Brand.OpenAI,
            "o3" to Brand.OpenAI,
            "o3-mini" to Brand.OpenAI,
            "o4-mini" to Brand.OpenAI,
            "o1-preview" to Brand.OpenAI,
            "codex-mini-latest" to Brand.OpenAI,
            "gpt-5.1-codex" to Brand.OpenAI,
            "gemini-2.5-pro" to Brand.Gemini,
            "gemini-2.5-flash-lite" to Brand.Gemini,
            "gemma-3-27b-it" to Brand.Google,
            "grok-4" to Brand.XAi,
            "grok-code-fast-1" to Brand.XAi,
            "mistral-large-latest" to Brand.Mistral,
            "codestral-latest" to Brand.Mistral,
            "devstral-medium-2507" to Brand.Mistral,
            "magistral-medium" to Brand.Mistral,
            "open-mixtral-8x22b" to Brand.Mistral,
            "deepseek-chat" to Brand.DeepSeek,
            "deepseek-reasoner" to Brand.DeepSeek,
            "llama-3.3-70b-versatile" to Brand.Meta,
            "llama3.1-8b" to Brand.Meta,
            "codellama:13b" to Brand.Meta,
            "qwen-3-235b-a22b-instruct-2507" to Brand.Qwen,
            "qwen3-coder-plus" to Brand.Qwen,
            "qwq-32b" to Brand.Qwen,
            "kimi-k2-0905-preview" to Brand.Kimi,
            "kimi-k2-thinking" to Brand.Kimi,
            "moonshot-v1-8k" to Brand.Kimi,
            "glm-4.6" to Brand.Zai,
            "glm-4.5-air" to Brand.Zai,
            "zai-glm-4.6" to Brand.Zai,
            "MiniMax-M2" to Brand.MiniMax,
            "command-r-plus" to Brand.Cohere,
            "command-a-03-2025" to Brand.Cohere,
            "sonar-pro" to Brand.Perplexity,
            "phi-4" to Brand.Microsoft,
            "phi4-mini" to Brand.Microsoft,
            "mimo-v2-flash" to Brand.Xiaomi,
            "ling-1t" to Brand.Ling,
            // Ollama tags.
            "qwen2.5-coder:7b" to Brand.Qwen,
            "gpt-oss:20b" to Brand.OpenAI,
            "deepseek-r1:14b" to Brand.DeepSeek,
            "gemma3:4b" to Brand.Google,
            "llama3.2" to Brand.Meta,
            // OpenRouter, Vercel, Groq, Together, Hugging Face: organisation namespaces.
            "anthropic/claude-sonnet-4.5" to Brand.Claude,
            "openai/gpt-oss-120b" to Brand.OpenAI,
            "google/gemini-2.5-pro" to Brand.Gemini,
            "google/gemma-3-27b-it" to Brand.Google,
            "x-ai/grok-4-fast" to Brand.XAi,
            "xai/grok-4" to Brand.XAi,
            "meta-llama/Llama-3.3-70B-Instruct-Turbo" to Brand.Meta,
            "meta-llama/llama-4-scout-17b-16e-instruct" to Brand.Meta,
            "Qwen/Qwen3-Coder-480B-A35B-Instruct" to Brand.Qwen,
            "deepseek-ai/DeepSeek-V3.1" to Brand.DeepSeek,
            "moonshotai/Kimi-K2-Instruct" to Brand.Kimi,
            "zai-org/GLM-4.6" to Brand.Zai,
            "z-ai/glm-4.6" to Brand.Zai,
            "MiniMaxAI/MiniMax-M2" to Brand.MiniMax,
            "mistralai/mistral-small-3.2-24b-instruct" to Brand.Mistral,
            "microsoft/phi-4" to Brand.Microsoft,
            "cohere/command-a" to Brand.Cohere,
            "perplexity/sonar" to Brand.Perplexity,
            "openrouter/auto" to Brand.OpenRouter,
            "nvidia/nemotron-nano-9b-v2" to Brand.Nvidia,
            // Fireworks paths: the family names the maker, not the path.
            "accounts/fireworks/models/llama-v3p1-405b-instruct" to Brand.Meta,
            "accounts/fireworks/models/qwen3-coder-480b-a35b-instruct" to Brand.Qwen,
            "accounts/fireworks/models/kimi-k2-instruct" to Brand.Kimi,
            "accounts/fireworks/models/deepseek-v3p1" to Brand.DeepSeek,
            "accounts/fireworks/models/glm-4p5" to Brand.Zai,
            // Bedrock: vendor prefixes, with and without a cross-region prefix.
            "anthropic.claude-3-5-sonnet-20241022-v2:0" to Brand.Claude,
            "us.anthropic.claude-sonnet-4-20250514-v1:0" to Brand.Claude,
            "global.anthropic.claude-sonnet-4-5-20250929-v1:0" to Brand.Claude,
            "eu.anthropic.claude-3-7-sonnet-20250219-v1:0" to Brand.Claude,
            "us.meta.llama3-3-70b-instruct-v1:0" to Brand.Meta,
            "meta.llama4-maverick-17b-instruct-v1:0" to Brand.Meta,
            "amazon.nova-pro-v1:0" to Brand.Amazon,
            "us.amazon.nova-premier-v1:0" to Brand.Amazon,
            "amazon.titan-text-express-v1" to Brand.Amazon,
            "mistral.mistral-large-2407-v1:0" to Brand.Mistral,
            "cohere.command-r-plus-v1:0" to Brand.Cohere,
            "deepseek.r1-v1:0" to Brand.DeepSeek,
            "us.deepseek.r1-v1:0" to Brand.DeepSeek,
            "qwen.qwen3-coder-480b-a35b-v1:0" to Brand.Qwen,
            "openai.gpt-oss-120b-1:0" to Brand.OpenAI,
            // A distill names its own family first.
            "deepseek-r1-distill-llama-70b" to Brand.DeepSeek,
            "deepseek-ai/DeepSeek-R1-Distill-Qwen-32B" to Brand.DeepSeek,
        )
        for ((id, brand) in expected) assertEquals(brand, ProviderLogos.maker(id), id)
    }

    @Test
    fun wordsInsideOtherWordsAreNotFamilies() {
        // "o3" only as a word; "gpt-4o" is GPT by its own name, "llava" is not Llama, "pro" is nothing.
        assertNull(ProviderLogos.maker("polo3"))
        assertNull(ProviderLogos.maker("llava:7b"))
        assertNull(ProviderLogos.maker("starcoder2:15b"))
        assertNull(ProviderLogos.maker("my-finetune"))
        assertNull(ProviderLogos.maker("commander-1"))
        assertNull(ProviderLogos.maker("supernova-lite"))
        assertNull(ProviderLogos.maker(""))
        assertNull(ProviderLogos.maker(null))
        assertNull(ProviderLogos.maker("accounts/fireworks/models/x-1"))
        assertNull(ProviderLogos.maker("us.ai21.jamba-1-5-large-v1:0"))
    }

    @Test
    fun aModelWithoutAKnownMakerTakesItsProvidersMark() {
        assertEquals(Brand.Ollama, ProviderLogos.forModel("starcoder2:15b", ProviderLogos.forProvider("ollama")))
        assertEquals(Brand.Bedrock, ProviderLogos.forModel("us.ai21.jamba-1-5-large-v1:0", ProviderLogos.forProvider("amazon-bedrock")))
        assertEquals(Brand.Generic, ProviderLogos.forModel("mystery"))
        assertEquals(Brand.Claude, ProviderCatalog.modelBrand("anthropic/claude-sonnet-4.5", "openrouter"))
        assertEquals(Brand.OpenRouter, ProviderCatalog.modelBrand("openrouter/auto", "openrouter"))
        assertEquals(Brand.GitHubCopilot, ProviderCatalog.modelBrand("some-preview", "github-copilot"))
        assertEquals(Brand.Claude, ProviderCatalog.modelBrand("claude-sonnet-4.5", "github-copilot"))
    }

    @Test
    fun everyBrandHasOneDrawableAndEveryDrawableABrand() {
        assertTrue(drawables.isDirectory, "run from the app module (${drawables.absolutePath})")
        val files = drawables.listFiles().orEmpty().map { it.name }.filter { it.startsWith("ic_provider_") }.toSet()
        val wanted = Brand.entries.filter { it != Brand.Generic }.map { "ic_provider_${it.slug}.xml" }.toSet()
        assertEquals(wanted, files)
        for (name in files) {
            val xml = File(drawables, name).readText()
            assertTrue("Lobe Icons" in xml && "MIT" in xml, "$name names its source and licence")
            assertTrue("android:fillColor=\"#FFFFFFFF\"" in xml, "$name is a monochrome mark, tinted at use")
            assertTrue(Regex("""fillColor="#(?!FFFFFFFF)""").find(xml) == null, "$name has another colour")
        }
    }

    @Test
    fun theConverterAndTheEnumListTheSameBrands() {
        val script = File("../tools/provider-logos.mjs").readText()
        val block = script.substringAfter("const BRANDS = {").substringBefore("};")
        val slugs = Regex("""^\s*(\w+):""", RegexOption.MULTILINE).findAll(block).map { it.groupValues[1] }.toSet()
        assertEquals(Brand.entries.filter { it != Brand.Generic }.map { it.slug }.toSet(), slugs)
    }
}
