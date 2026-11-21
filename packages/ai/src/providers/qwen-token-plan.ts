import { openAICompletionsApi } from "../api/openai-completions.lazy.ts";
import { envApiKeyAuth } from "../auth/helpers.ts";
import { createProvider, type Provider } from "../models.ts";
import { withCatalogListing } from "./model-listing.ts";
import { QWEN_TOKEN_PLAN_MODEL_CATALOG } from "./qwen-token-plan.catalog.generated.ts";

export function qwenTokenPlanProvider(): Provider<"openai-completions"> {
	return withCatalogListing(
		createProvider({
			id: "qwen-token-plan",
			name: "Qwen Token Plan Team",
			baseUrl: "https://token-plan.maas.qwencloudapi.com/compatible-mode/v1",
			auth: {
				apiKey: envApiKeyAuth("Qwen Token Plan Team API key", [
					"QWEN_TOKEN_PLAN_TEAM_API_KEY",
					"QWEN_TOKEN_PLAN_API_KEY",
				]),
			},
			get models() {
				return Object.values(QWEN_TOKEN_PLAN_MODEL_CATALOG.chat());
			},
			api: openAICompletionsApi(),
		}),
		QWEN_TOKEN_PLAN_MODEL_CATALOG,
	);
}
