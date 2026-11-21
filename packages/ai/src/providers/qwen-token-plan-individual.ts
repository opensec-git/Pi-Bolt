import { openAICompletionsApi } from "../api/openai-completions.lazy.ts";
import { envApiKeyAuth } from "../auth/helpers.ts";
import { createProvider, type Provider } from "../models.ts";
import { withCatalogListing } from "./model-listing.ts";
import { QWEN_TOKEN_PLAN_INDIVIDUAL_MODEL_CATALOG } from "./qwen-token-plan-individual.catalog.generated.ts";

export function qwenTokenPlanIndividualProvider(): Provider<"openai-completions"> {
	return withCatalogListing(
		createProvider({
			id: "qwen-token-plan-individual",
			name: "Qwen Token Plan Individual",
			baseUrl: "https://token-plan.maas.qwencloudapi.com/compatible-mode/v1",
			auth: {
				apiKey: envApiKeyAuth("Qwen Token Plan Individual API key", [
					"QWEN_TOKEN_PLAN_INDIVIDUAL_API_KEY",
					"QWEN_TOKEN_PLAN_API_KEY",
				]),
			},
			get models() {
				return Object.values(QWEN_TOKEN_PLAN_INDIVIDUAL_MODEL_CATALOG.chat());
			},
			api: openAICompletionsApi(),
		}),
		QWEN_TOKEN_PLAN_INDIVIDUAL_MODEL_CATALOG,
	);
}
