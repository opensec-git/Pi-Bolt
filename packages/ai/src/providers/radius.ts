import { piDecisionApi } from "../api/pi-decision.lazy.ts";
import { piImageApi } from "../api/pi-image.lazy.ts";
import { piMessagesApi } from "../api/pi-messages.lazy.ts";
import { envApiKeyAuth, lazyOAuth } from "../auth/helpers.ts";
import { loadRadiusOAuth } from "../auth/oauth/load.ts";
import type { Provider } from "../models.ts";
import type { ClassifierModel, ImageModel, Model } from "../types.ts";
import { isModelType } from "../utils/model-operations.ts";
import { namedModelListing, setModelListing } from "./model-listing.ts";
import { RADIUS_MODEL_CATALOG } from "./radius.catalog.generated.ts";
import {
	DEFAULT_RADIUS_GATEWAY,
	getRadiusAllModelsFromConfig,
	getRadiusModels,
	loadRadiusGatewayConfig,
	normalizeRadiusGatewayUrl,
} from "./radius-config.ts";

export interface RadiusProviderOptions {
	id?: string;
	name?: string;
	gateway?: string;
}

type RadiusModel = Model<"pi-messages"> | ClassifierModel<"pi-decision"> | ImageModel<"pi-image">;

/** Radius gateway provider with a persisted, dynamically refreshed catalog. */
export function radiusProvider(options: RadiusProviderOptions = {}): Provider<"pi-messages"> {
	const id = options.id ?? "radius";
	const name = options.name ?? "Radius";
	const gateway = normalizeRadiusGatewayUrl(options.gateway ?? DEFAULT_RADIUS_GATEWAY);
	const defaultGateway = gateway === normalizeRadiusGatewayUrl(DEFAULT_RADIUS_GATEWAY);
	// (Made when first asked for.)
	let baselineModels: RadiusModel[] | undefined;
	const baseline = (): RadiusModel[] => {
		baselineModels ??= defaultGateway
			? [
					...(Object.values(RADIUS_MODEL_CATALOG.chat()) as Model<"pi-messages">[]),
					...(Object.values(RADIUS_MODEL_CATALOG.classifier()) as ClassifierModel<"pi-decision">[]),
					...(Object.values(RADIUS_MODEL_CATALOG.image()) as ImageModel<"pi-image">[]),
				].map((model) => ({ ...model, provider: id }))
			: [];
		return baselineModels;
	};
	// Gateway catalog for this account. Radius org owners can disable models, so once known it replaces
	// the shipped baseline instead of overlaying it. The baseline only covers the time before any catalog exists.
	let dynamicModels: RadiusModel[] | undefined;
	let currentChatModels: { from: RadiusModel[]; chat: Model<"pi-messages">[] } | undefined;
	// Filtered once per catalog, not per read: getModels() is on hot paths such as Models.getModel() (#12311).
	const chatModels = (): Model<"pi-messages">[] => {
		const all = dynamicModels ?? baseline();
		if (currentChatModels?.from !== all) {
			currentChatModels = {
				from: all,
				chat: all.filter((model): model is Model<"pi-messages"> => isModelType(model, "chat")),
			};
		}
		return currentChatModels.chat;
	};
	const streams = piMessagesApi();
	const classifiers = piDecisionApi();
	const images = piImageApi();

	const provider: Provider<"pi-messages"> = {
		id,
		name,
		auth: {
			apiKey: envApiKeyAuth("Radius API key", ["RADIUS_API_KEY"]),
			oauth: lazyOAuth({ name, load: () => loadRadiusOAuth({ name, gateway }) }),
		},
		getModels: chatModels,
		getAllModels: () => dynamicModels ?? baseline(),
		refreshModels: async (context) => {
			const stored = context.stored;
			if (stored) {
				const restored = stored.models.filter(
					(model): model is RadiusModel =>
						model.provider === id &&
						(isModelType(model, "chat") || isModelType(model, "classifier") || isModelType(model, "image")),
				);
				if (
					!(await context.publish({
						update: () => {
							dynamicModels = restored;
						},
					}))
				) {
					return;
				}
			}

			// Import catalogs cached by the pre-ModelsStore Radius implementation.
			if (!stored && context.credential?.type === "oauth") {
				const legacy = getRadiusModels(id, context.credential);
				if (legacy.length > 0) {
					if (
						!(await context.publish({
							persist: { models: legacy, checkedAt: Date.now() },
							update: () => {
								dynamicModels = legacy;
							},
						}))
					) {
						return;
					}
				}
			}

			if (!context.allowNetwork || context.signal.aborted) return;
			const apiKey = context.credential?.type === "oauth" ? context.credential.access : context.credential?.key;
			const config = await loadRadiusGatewayConfig(gateway, apiKey, context.signal);
			if (context.signal.aborted) return;
			const refreshed = getRadiusAllModelsFromConfig(id, config);
			await context.publish({
				persist: { models: refreshed, checkedAt: Date.now() },
				update: () => {
					dynamicModels = refreshed;
				},
			});
		},
		stream: (model, context, streamOptions) => streams.stream(model, context, streamOptions),
		streamSimple: (model, context, streamOptions) => streams.streamSimple(model, context, streamOptions),
		classify: (model, context, classifyOptions) => classifiers.classify(model, context, classifyOptions),
		generateImages: (model, context, imageOptions) => images.generateImages(model, context, imageOptions),
	};
	// The shipped baseline can be named without building it until a gateway catalog replaces it.
	const listing = namedModelListing(id, defaultGateway ? RADIUS_MODEL_CATALOG.chatNames : () => [], chatModels);
	return setModelListing(provider, () => (dynamicModels === undefined ? listing : undefined));
}
