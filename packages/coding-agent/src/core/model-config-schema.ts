/** The definition of models.json, built with TypeBox: where its types come from, and what model-config-schema.generated.ts,
 * the same schema as plain JSON Schema, is generated from (scripts/generate-model-config-schema.ts). Building it costs a few
 * milliseconds, which a start does not pay: it is used only by the generator and the test that the two agree. */

import { Type } from "typebox";
import {
	ModelCostSchema,
	ModelInputLimitsSchema,
	ModelInputModalitySchema,
	ModelPromptCacheSchema,
	PartialModelCostSchema,
	ProviderCompatSchema,
	ThinkingLevelMapSchema,
} from "./pi-ai-schemas.ts";

export function buildModelsConfigSchemas() {
	const SamplingParamsSchema = Type.Record(Type.String(), Type.Unknown());
	const SamplingParamsByThinkingLevelSchema = Type.Object(
		{
			off: Type.Optional(SamplingParamsSchema),
			minimal: Type.Optional(SamplingParamsSchema),
			low: Type.Optional(SamplingParamsSchema),
			medium: Type.Optional(SamplingParamsSchema),
			high: Type.Optional(SamplingParamsSchema),
			xhigh: Type.Optional(SamplingParamsSchema),
			max: Type.Optional(SamplingParamsSchema),
		},
		{ description: "samplingParams per thinking level, merged over samplingParams." },
	);

	const SamplingParamsFieldSchema = Type.Record(Type.String(), Type.Unknown(), {
		description: "Fields merged into every request body. Applies only to OpenAI-compatible APIs.",
	});
	const HeadersSchema = Type.Record(Type.String(), Type.String(), {
		description: "Request headers. Values resolve like apiKey.",
	});

	/** Model fields shared by models entries and modelOverrides entries. Defaults apply only to models entries. */
	const ModelFieldDescriptions = {
		name: "Display name, also matched by --model patterns.",
		reasoning: "Whether the model supports thinking.",
		contextWindow: "Context window in tokens.",
		maxTokens: "Maximum output tokens.",
	} as const;

	const ModelDefinitionSchema = Type.Object({
		id: Type.String({ minLength: 1, description: "Model ID sent to the API." }),
		name: Type.Optional(Type.String({ minLength: 1, description: ModelFieldDescriptions.name })),
		api: Type.Optional(Type.String({ minLength: 1, description: "Overrides the provider api." })),
		baseUrl: Type.Optional(Type.String({ minLength: 1, description: "Overrides the provider baseUrl." })),
		reasoning: Type.Optional(Type.Boolean({ default: false, description: ModelFieldDescriptions.reasoning })),
		thinkingLevelMap: Type.Optional(ThinkingLevelMapSchema),
		input: Type.Optional(Type.Array(ModelInputModalitySchema, { default: ["text"] })),
		inputLimits: Type.Optional(ModelInputLimitsSchema),
		cost: Type.Optional(ModelCostSchema),
		promptCache: Type.Optional(ModelPromptCacheSchema),
		contextWindow: Type.Optional(
			Type.Number({ exclusiveMinimum: 0, default: 128000, description: ModelFieldDescriptions.contextWindow }),
		),
		maxTokens: Type.Optional(
			Type.Number({ exclusiveMinimum: 0, default: 16384, description: ModelFieldDescriptions.maxTokens }),
		),
		samplingParams: Type.Optional(SamplingParamsFieldSchema),
		samplingParamsByThinkingLevel: Type.Optional(SamplingParamsByThinkingLevelSchema),
		headers: Type.Optional(HeadersSchema),
		compat: Type.Optional(ProviderCompatSchema),
	});

	const ModelOverrideSchema = Type.Object({
		name: Type.Optional(Type.String({ minLength: 1, description: ModelFieldDescriptions.name })),
		reasoning: Type.Optional(Type.Boolean({ description: ModelFieldDescriptions.reasoning })),
		thinkingLevelMap: Type.Optional(ThinkingLevelMapSchema),
		input: Type.Optional(Type.Array(ModelInputModalitySchema)),
		inputLimits: Type.Optional(ModelInputLimitsSchema),
		cost: Type.Optional(PartialModelCostSchema),
		promptCache: Type.Optional(ModelPromptCacheSchema),
		contextWindow: Type.Optional(
			Type.Number({ exclusiveMinimum: 0, description: ModelFieldDescriptions.contextWindow }),
		),
		maxTokens: Type.Optional(Type.Number({ exclusiveMinimum: 0, description: ModelFieldDescriptions.maxTokens })),
		samplingParams: Type.Optional(SamplingParamsFieldSchema),
		samplingParamsByThinkingLevel: Type.Optional(SamplingParamsByThinkingLevelSchema),
		headers: Type.Optional(HeadersSchema),
		compat: Type.Optional(ProviderCompatSchema),
	});

	const ProviderConfigSchema = Type.Object({
		name: Type.Optional(Type.String({ minLength: 1, description: "Display name." })),
		baseUrl: Type.Optional(
			Type.String({ minLength: 1, description: "API base URL. Also applies to the provider's built-in models." }),
		),
		apiKey: Type.Optional(
			Type.String({
				minLength: 1,
				description: 'Literal key, "$NAME" environment interpolation, or "!command" output.',
			}),
		),
		api: Type.Optional(
			Type.String({
				minLength: 1,
				description:
					'API for the provider\'s models, such as "openai-completions", "openai-responses", "anthropic-messages", or "google-generative-ai".',
			}),
		),
		oauth: Type.Optional(Type.Literal("radius", { description: "OAuth login flow. Requires baseUrl." })),
		headers: Type.Optional(HeadersSchema),
		compat: Type.Optional(ProviderCompatSchema),
		authHeader: Type.Optional(
			Type.Boolean({
				default: false,
				description: "Whether to send the resolved API key as Authorization: Bearer.",
			}),
		),
		models: Type.Optional(Type.Array(ModelDefinitionSchema)),
		modelOverrides: Type.Optional(Type.Record(Type.String(), ModelOverrideSchema)),
	});

	const ModelsConfigSchema = Type.Object({
		$schema: Type.Optional(Type.String()),
		providers: Type.Record(Type.String(), ProviderConfigSchema),
	});

	return { ModelDefinitionSchema, ModelOverrideSchema, ProviderConfigSchema, ModelsConfigSchema };
}
