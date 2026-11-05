import { anthropicMessagesApi } from "../api/anthropic-messages.lazy.ts";
import { googleGenerativeAIApi } from "../api/google-generative-ai.lazy.ts";
import { openAICompletionsApi } from "../api/openai-completions.lazy.ts";
import { openAIResponsesApi } from "../api/openai-responses.lazy.ts";
import { typesafeSystemOneApi } from "../api/typesafe-system-one.lazy.ts";
import { envApiKeyAuth } from "../auth/helpers.ts";
import { createProvider, type Provider } from "../models.ts";
import { withCatalogListing } from "./model-listing.ts";
import { OPENCODE_MODEL_CATALOG } from "./opencode.catalog.generated.ts";
import { withOpenCodeSessionHeader } from "./opencode-headers.ts";

type OpenCodeApi = "anthropic-messages" | "google-generative-ai" | "openai-completions" | "openai-responses";

export function opencodeProvider(): Provider<OpenCodeApi> {
	return withCatalogListing(
		createProvider<OpenCodeApi>({
			id: "opencode",
			name: "OpenCode Zen",
			auth: { apiKey: envApiKeyAuth("OpenCode API key", ["OPENCODE_API_KEY"]) },
			get models() {
				return [
					...Object.values(OPENCODE_MODEL_CATALOG.chat()),
					...Object.values(OPENCODE_MODEL_CATALOG.classifier()),
				];
			},
			api: {
				"anthropic-messages": withOpenCodeSessionHeader(anthropicMessagesApi()),
				"google-generative-ai": withOpenCodeSessionHeader(googleGenerativeAIApi()),
				"openai-completions": withOpenCodeSessionHeader(openAICompletionsApi()),
				"openai-responses": withOpenCodeSessionHeader(openAIResponsesApi()),
			},
			// OpenCode Zen serves TypeSafe's System One protocol at /inference/systemone/v1/systemone.
			classifiers: { "typesafe-system-one": typesafeSystemOneApi() },
		}),
		OPENCODE_MODEL_CATALOG,
	);
}
