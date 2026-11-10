package opensec.pibolt.app.ui.providers

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import opensec.pibolt.app.R
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi

/** The brand's drawable (`ic_provider_<slug>`); null for [Brand.Generic], which is drawn with the server glyph. */
@DrawableRes
fun Brand.drawable(): Int? = when (this) {
    Brand.Anthropic -> R.drawable.ic_provider_anthropic
    Brand.Claude -> R.drawable.ic_provider_claude
    Brand.OpenAI -> R.drawable.ic_provider_openai
    Brand.Gemini -> R.drawable.ic_provider_gemini
    Brand.Google -> R.drawable.ic_provider_google
    Brand.XAi -> R.drawable.ic_provider_xai
    Brand.Mistral -> R.drawable.ic_provider_mistral
    Brand.DeepSeek -> R.drawable.ic_provider_deepseek
    Brand.Groq -> R.drawable.ic_provider_groq
    Brand.OpenRouter -> R.drawable.ic_provider_openrouter
    Brand.Ollama -> R.drawable.ic_provider_ollama
    Brand.Moonshot -> R.drawable.ic_provider_moonshot
    Brand.Kimi -> R.drawable.ic_provider_kimi
    Brand.Qwen -> R.drawable.ic_provider_qwen
    Brand.Meta -> R.drawable.ic_provider_meta
    Brand.Cerebras -> R.drawable.ic_provider_cerebras
    Brand.Bedrock -> R.drawable.ic_provider_bedrock
    Brand.Amazon -> R.drawable.ic_provider_aws
    Brand.Azure -> R.drawable.ic_provider_azure
    Brand.GitHubCopilot -> R.drawable.ic_provider_githubcopilot
    Brand.HuggingFace -> R.drawable.ic_provider_huggingface
    Brand.Together -> R.drawable.ic_provider_together
    Brand.Fireworks -> R.drawable.ic_provider_fireworks
    Brand.Perplexity -> R.drawable.ic_provider_perplexity
    Brand.Cohere -> R.drawable.ic_provider_cohere
    Brand.Zai -> R.drawable.ic_provider_zai
    Brand.MiniMax -> R.drawable.ic_provider_minimax
    Brand.Vercel -> R.drawable.ic_provider_vercel
    Brand.Nvidia -> R.drawable.ic_provider_nvidia
    Brand.Baseten -> R.drawable.ic_provider_baseten
    Brand.OpenCode -> R.drawable.ic_provider_opencode
    Brand.LmStudio -> R.drawable.ic_provider_lmstudio
    Brand.Vllm -> R.drawable.ic_provider_vllm
    Brand.Microsoft -> R.drawable.ic_provider_microsoft
    Brand.Cloudflare -> R.drawable.ic_provider_cloudflare
    Brand.Xiaomi -> R.drawable.ic_provider_xiaomi
    Brand.Ling -> R.drawable.ic_provider_ling
    Brand.Generic -> null
}

/**
 * A provider's or model maker's mark, monochrome in [tint] at an icon-size token: ink2 in rows, ink1 where it marks the
 * current choice (the composer chip, the current model), never a brand colour. Decorative: the name beside it says
 * who it is.
 */
@Composable
fun LogoMark(brand: Brand, modifier: Modifier = Modifier, size: Dp = IconSize.sm, tint: Color = Pi.colors.ink2) {
    val res = brand.drawable()
    if (res != null) {
        Icon(painterResource(res), contentDescription = null, tint = tint, modifier = modifier.size(size))
    } else {
        Icon(Icons.Rounded.Dns, contentDescription = null, tint = tint, modifier = modifier.size(size))
    }
}

/**
 * A provider row's leading mark: 16dp in ink2 (Pi-Bolt Desktop's settings rows), centred in the rows' 20dp glyph slot
 * so names line up with rows that lead with a Material glyph. No tile: the mark is quiet, like a glyph. Decorative for
 * TalkBack.
 */
@Composable
fun ProviderBadge(brand: Brand, modifier: Modifier = Modifier, tint: Color = Pi.colors.ink2) {
    Box(modifier.size(IconSize.md).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        LogoMark(brand, size = IconSize.sm, tint = tint)
    }
}
