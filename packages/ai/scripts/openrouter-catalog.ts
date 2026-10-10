import type { ClassifierModel, ImageModel, Model, ModelCost, ModelCostTier } from "../src/types.ts";
import { getOpenRouterThinkingLevelMap, type OpenRouterReasoningMetadata } from "./openrouter-reasoning-options.ts";

export interface OpenRouterModelListItem {
	id: string;
	name: string;
	supported_parameters?: string[];
	architecture?: { modality?: string; input_modalities?: string[]; output_modalities?: string[] };
	pricing?: {
		prompt?: string;
		completion?: string;
		input_cache_read?: string;
		input_cache_write?: string;
		overrides?: OpenRouterPricingOverride[];
	};
	top_provider?: {
		context_length?: number;
		max_completion_tokens?: number;
	};
	context_length?: number;
	reasoning?: OpenRouterReasoningMetadata;
}

/**
 * A conditional price. `min_prompt_tokens` selects prompt-length pricing; `utc_*` fields select
 * time-of-day or weekday pricing. Missing rates keep the base price.
 */
export interface OpenRouterPricingOverride {
	min_prompt_tokens?: number;
	utc_start?: number;
	utc_end?: number;
	utc_days?: string[];
	prompt?: string;
	completion?: string;
	input_cache_read?: string;
	input_cache_write?: string;
}

export interface OpenRouterCatalog {
	chat: Model<"anthropic-messages" | "openai-completions">[];
	images: ImageModel<"openrouter-images">[];
	classifiers: ClassifierModel<"typesafe-system-one">[];
}

function roundCost(value: number): number {
	return Number(value.toFixed(6));
}

function modalities(values: string[] | undefined): ("text" | "image")[] {
	return Array.from(
		new Set((values ?? []).filter((value): value is "text" | "image" => value === "text" || value === "image")),
	);
}

// Convert pricing from $/token to $/million tokens
function perMillion(value: string | undefined, fallback: number): number {
	return value ? roundCost(parseFloat(value) * 1_000_000) : fallback;
}

function cost(model: OpenRouterModelListItem): ModelCost {
	const pricing = model.pricing;
	const base = {
		input: perMillion(pricing?.prompt, 0),
		output: perMillion(pricing?.completion, 0),
		cacheRead: perMillion(pricing?.input_cache_read, 0),
		cacheWrite: perMillion(pricing?.input_cache_write, 0),
	};
	// Prompt-length overrides become request-wide tiers. Time-of-day overrides are skipped
	// because ModelCost cannot express them.
	const tiers = (pricing?.overrides ?? []).flatMap((override): ModelCostTier[] => {
		if (
			override.min_prompt_tokens === undefined ||
			override.utc_start !== undefined ||
			override.utc_end !== undefined ||
			override.utc_days !== undefined
		) {
			return [];
		}
		return [
			{
				inputTokensAbove: override.min_prompt_tokens,
				input: perMillion(override.prompt, base.input),
				output: perMillion(override.completion, base.output),
				cacheRead: perMillion(override.input_cache_read, base.cacheRead),
				cacheWrite: perMillion(override.input_cache_write, base.cacheWrite),
			},
		];
	});
	return tiers.length > 0 ? { ...base, tiers } : base;
}

/**
 * Build the OpenRouter catalog from the default listing and the
 * `output_modalities=image` and `output_modalities=decisions` listings. The
 * default listing omits image-only and decision models, so those come from
 * the other listings. An upstream model may appear in several results; it then
 * gets separate entries per operation.
 */
export function buildOpenRouterCatalog(
	listed: readonly OpenRouterModelListItem[],
	imageListed: readonly OpenRouterModelListItem[],
	decisionListed: readonly OpenRouterModelListItem[],
): OpenRouterCatalog {
	const chat: OpenRouterCatalog["chat"] = [];

	for (const model of listed) {
		// Only include models that support tools
		if (!model.supported_parameters?.includes("tools")) continue;
		// Parse input modalities
		const input: ("text" | "image")[] = ["text"];
		if (model.architecture?.modality?.includes("image")) {
			input.push("image");
		}

		const thinkingLevelMap = getOpenRouterThinkingLevelMap(model.reasoning);
		const useAnthropicMessages = /^anthropic\//.test(model.id) && !model.id.endsWith(":batch");
		chat.push({
			type: "chat",
			id: model.id,
			name: model.name,
			api: useAnthropicMessages ? "anthropic-messages" : "openai-completions",
			baseUrl: useAnthropicMessages ? "https://openrouter.ai/api" : "https://openrouter.ai/api/v1",
			provider: "openrouter",
			reasoning: model.supported_parameters?.includes("reasoning") || false,
			...(thinkingLevelMap && { thinkingLevelMap }),
			input,
			cost: cost(model),
			contextWindow: model.top_provider?.context_length || model.context_length || 4096,
			maxTokens: model.top_provider?.max_completion_tokens || 4096,
		});
	}

	const images: OpenRouterCatalog["images"] = [];
	for (const model of imageListed) {
		if (images.some((entry) => entry.id === model.id)) continue;
		const output = modalities(model.architecture?.output_modalities);
		if (!output.includes("image")) continue;
		const input = modalities(model.architecture?.input_modalities);
		images.push({
			type: "image",
			id: model.id,
			name: model.name,
			api: "openrouter-images",
			provider: "openrouter",
			baseUrl: "https://openrouter.ai/api/v1",
			input: input.length > 0 ? input : ["text"],
			output,
			cost: cost(model),
		});
	}

	// Decision models such as TypeSafe's Jev are served through OpenRouter's
	// TypeSafe-compatible System One endpoint.
	const classifiers: OpenRouterCatalog["classifiers"] = [];
	for (const model of decisionListed) {
		if (classifiers.some((entry) => entry.id === model.id)) continue;
		if (!model.architecture?.output_modalities?.includes("decisions")) continue;
		const input = modalities(model.architecture.input_modalities);
		classifiers.push({
			type: "classifier",
			id: model.id,
			name: model.name,
			api: "typesafe-system-one",
			provider: "openrouter",
			baseUrl: "https://openrouter.ai/api/v1",
			input: input.length > 0 ? input : ["text"],
			cost: cost(model),
			contextWindow: model.top_provider?.context_length || model.context_length || 4096,
		});
	}

	return { chat, images, classifiers };
}
