import { anthropicMessagesApi } from "../api/anthropic-messages.lazy.ts";
import { openAICompletionsApi } from "../api/openai-completions.lazy.ts";
import { openAIResponsesApi } from "../api/openai-responses.lazy.ts";
import { typesafeSystemOneApi } from "../api/typesafe-system-one.lazy.ts";
import { envApiKeyAuth, lazyOAuth } from "../auth/helpers.ts";
import { loadGitHubCopilotOAuth } from "../auth/oauth/load.ts";
import { createProvider, type Provider } from "../models.ts";
import type { ClassifierModel, ProviderClassifier } from "../types.ts";
import { GITHUB_COPILOT_MODEL_CATALOG } from "./github-copilot.catalog.generated.ts";
import { withCatalogListing } from "./model-listing.ts";

type GitHubCopilotApi = "anthropic-messages" | "openai-completions" | "openai-responses";

/**
 * Copilot serves System One at `/v1/systemone`, while its chat APIs have no version prefix.
 * The login replaces the catalog base URL with the account's endpoint, so the prefix is added per request.
 */
function copilotSystemOne(classifier: ProviderClassifier): ProviderClassifier {
	return {
		classify: (model, context, options) =>
			classifier.classify({ ...model, baseUrl: `${model.baseUrl.replace(/\/+$/u, "")}/v1` }, context, options),
	};
}

export function githubCopilotProvider(): Provider<GitHubCopilotApi> {
	return withCatalogListing(
		createProvider<GitHubCopilotApi>({
			id: "github-copilot",
			name: "GitHub Copilot",
			baseUrl: "https://api.individual.githubcopilot.com",
			auth: {
				apiKey: envApiKeyAuth("GitHub Copilot token", ["COPILOT_GITHUB_TOKEN"]),
				oauth: lazyOAuth({ name: "GitHub Copilot", isSubscription: true, load: loadGitHubCopilotOAuth }),
			},
			get models() {
				return [
					...Object.values(GITHUB_COPILOT_MODEL_CATALOG.chat()),
					...Object.values<ClassifierModel<"typesafe-system-one">>(GITHUB_COPILOT_MODEL_CATALOG.classifier()),
				];
			},
			filterModels: (models, credential) => {
				if (credential?.type !== "oauth") return models;
				const availableModelIds = credential.availableModelIds;
				if (!Array.isArray(availableModelIds) || !availableModelIds.every((id) => typeof id === "string")) {
					return models;
				}
				const available = new Set(availableModelIds);
				return models.filter((model) => available.has(model.id));
			},
			api: {
				"anthropic-messages": anthropicMessagesApi(),
				"openai-completions": openAICompletionsApi(),
				"openai-responses": openAIResponsesApi(),
			},
			classifiers: { "typesafe-system-one": copilotSystemOne(typesafeSystemOneApi()) },
		}),
		GITHUB_COPILOT_MODEL_CATALOG,
	);
}
