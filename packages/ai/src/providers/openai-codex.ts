import { openAICodexResponsesApi } from "../api/openai-codex-responses.lazy.ts";
import { lazyOAuth } from "../auth/helpers.ts";
import { loadOpenAICodexOAuth } from "../auth/oauth/load.ts";
import { createProvider, type Provider } from "../models.ts";
import { withCatalogListing } from "./model-listing.ts";
import { OPENAI_CODEX_MODEL_CATALOG } from "./openai-codex.catalog.generated.ts";

export function openaiCodexProvider(): Provider<"openai-codex-responses"> {
	return withCatalogListing(
		createProvider({
			id: "openai-codex",
			name: "OpenAI Codex",
			baseUrl: "https://chatgpt.com/backend-api",
			auth: {
				oauth: lazyOAuth({
					name: "OpenAI (ChatGPT Plus/Pro)",
					isSubscription: true,
					load: loadOpenAICodexOAuth,
				}),
			},
			get models() {
				return Object.values(OPENAI_CODEX_MODEL_CATALOG.chat());
			},
			api: openAICodexResponsesApi(),
		}),
		OPENAI_CODEX_MODEL_CATALOG,
	);
}
