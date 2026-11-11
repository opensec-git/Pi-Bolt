import {
	type AnyModel,
	getModelType,
	isModelType,
	type ModelsStoreEntry,
	type ModelType,
	type Provider,
	type RefreshModelsContext,
} from "@earendil-works/pi-ai";
import { getModelListing, setModelListing } from "@earendil-works/pi-ai/providers/model-listing";
import { VERSION } from "../config.ts";
import { fetchWithRetry } from "../utils/management-http.ts";
import { getPiUserAgent } from "../utils/pi-user-agent.ts";

const DEFAULT_CATALOG_BASE_URL = "https://pi.dev";
const REMOTE_CATALOG_ATTEMPT_TIMEOUT_MS = 4_000;
export const REMOTE_CATALOG_REFRESH_INTERVAL_MS = 4 * 60 * 60 * 1000;
/**
 * Model types this client can consume. Sent as `?types=` so the catalog server
 * returns the full-type shard instead of the chat-only one served to clients
 * that predate model types. A server that ignores the parameter still returns
 * the chat-only shard, which this client handles unchanged.
 */
export const REMOTE_CATALOG_MODEL_TYPES: readonly ModelType[] = ["chat", "image", "classifier"];

function isSupportedModelType(model: { type?: unknown }): boolean {
	return (
		model.type === undefined ||
		(typeof model.type === "string" && REMOTE_CATALOG_MODEL_TYPES.includes(model.type as ModelType))
	);
}

function mergeModels<TModel extends AnyModel>(
	baseline: readonly TModel[],
	dynamic: readonly TModel[],
): readonly TModel[] {
	// Nothing to merge in (no catalog newer than the built-in one): the provider's models as they are.
	if (dynamic.length === 0) return baseline;
	const merged = new Map<string, TModel>();
	for (const model of [...baseline, ...dynamic]) merged.set(`${getModelType(model)}\0${model.id}`, model);
	return [...merged.values()];
}

function sameModels(a: readonly AnyModel[], b: readonly AnyModel[]): boolean {
	if (a.length !== b.length) return false;
	for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
	return true;
}

/**
 * mergeModels(), done again only when what it merges has changed: Pi asks every provider for its models hundreds of times as
 * it starts. The provider's models are compared one by one (they come in an array of their own each time); the dynamic ones
 * change only by being replaced. Each caller gets an array of its own.
 */
function memoizedMerge<TModel extends AnyModel>(): (
	baseline: readonly TModel[],
	dynamic: readonly AnyModel[],
) => TModel[] {
	let last: { baseline: readonly TModel[]; dynamic: readonly AnyModel[]; merged: readonly TModel[] } | undefined;
	return (baseline, dynamic) => {
		if (!last || last.dynamic !== dynamic || !sameModels(last.baseline, baseline)) {
			last = { baseline, dynamic, merged: mergeModels(baseline, dynamic as readonly TModel[]) };
		}
		return [...last.merged];
	};
}

function parseCatalog(providerId: string, value: unknown): AnyModel[] {
	const entries = Array.isArray(value)
		? value
		: typeof value === "object" && value !== null && "models" in value && Array.isArray(value.models)
			? value.models
			: typeof value === "object" && value !== null
				? Object.values(value)
				: undefined;
	if (!entries) throw new Error(`Invalid model catalog for provider "${providerId}"`);
	return entries
		.filter((entry): entry is Record<string, unknown> => typeof entry === "object" && entry !== null && "id" in entry)
		.filter(isSupportedModelType)
		.map((model) => ({ ...model, provider: providerId }) as AnyModel);
}

/** Read a successful pi.dev catalog response. A missing or invalid Last-Modified header is 0. */
async function readCatalog(
	providerId: string,
	response: Response,
): Promise<{ models: AnyModel[]; lastModified: number; etag: string | undefined }> {
	const lastModified = Date.parse(response.headers.get("last-modified") ?? "");
	return {
		models: parseCatalog(providerId, await response.json()),
		lastModified: Number.isNaN(lastModified) ? 0 : lastModified,
		etag: response.headers.get("etag") ?? undefined,
	};
}

/** Whether a pi.dev catalog is newer than the model data shipped with pi. */
function isNewer(lastModified: number | undefined, localGeneratedAt: number | undefined): boolean {
	return localGeneratedAt === undefined || (lastModified !== undefined && lastModified > localGeneratedAt);
}

function remoteModels(entry: ModelsStoreEntry | undefined, localGeneratedAt: number | undefined): readonly AnyModel[] {
	return entry && isNewer(entry.lastModified, localGeneratedAt) ? entry.models : [];
}

function requestCatalog(
	providerId: string,
	catalogBaseUrl: string,
	signal: AbortSignal,
	validator?: string,
): Promise<Response> {
	const url = new URL(`/api/models/providers/${encodeURIComponent(providerId)}`, catalogBaseUrl);
	url.searchParams.set("types", REMOTE_CATALOG_MODEL_TYPES.join(","));
	return fetchWithRetry(
		url,
		{
			headers: {
				accept: "application/json",
				"User-Agent": getPiUserAgent(VERSION),
				...(validator ? { "if-none-match": validator } : {}),
			},
			signal,
		},
		{ attemptTimeoutMs: REMOTE_CATALOG_ATTEMPT_TIMEOUT_MS },
	);
}

/** Replaces the catalog with a credential-specific model list. */
export type PersonalizeCatalog = (
	catalog: readonly AnyModel[],
	context: RefreshModelsContext,
) => Promise<readonly AnyModel[]>;

/** Add a persisted pi.dev catalog overlay to a static built-in provider. */
export function withRemoteCatalog(
	provider: Provider,
	catalogBaseUrl: string = DEFAULT_CATALOG_BASE_URL,
	localGeneratedAt?: number,
): Provider {
	let dynamicModels: readonly AnyModel[] = [];
	// (The chat models of the dynamic ones, kept as long as they are.)
	let dynamicChat: { of: readonly AnyModel[]; models: readonly AnyModel[] } | undefined;
	const dynamicChatModels = () => {
		if (dynamicChat?.of !== dynamicModels) {
			dynamicChat = { of: dynamicModels, models: dynamicModels.filter((model) => isModelType(model, "chat")) };
		}
		return dynamicChat.models;
	};
	const mergeChat = memoizedMerge<ReturnType<Provider["getModels"]>[number]>();
	const mergeAll = memoizedMerge<AnyModel>();

	const wrapped: Provider = {
		...provider,
		getModels: () => mergeChat(provider.getModels(), dynamicChatModels()),
		getAllModels: () => mergeAll(provider.getAllModels?.() ?? provider.getModels(), dynamicModels),
		// pi.dev serves the catalog, so a stale provider login must not block or fail its refresh.
		refreshModelsAuth: "configured",
		refreshModels: async (context) => {
			const stored = context.stored;
			const restored = remoteModels(stored, localGeneratedAt).filter((model) => model.provider === provider.id);
			// Offline with nothing to restore and nothing to clear (every provider without a newer stored catalog, at every
			// start), publishing would change nothing.
			if (!context.allowNetwork && restored.length === 0 && dynamicModels.length === 0) return;
			if (
				!(await context.publish({
					update: () => {
						dynamicModels = restored;
					},
				}))
			) {
				return;
			}
			if (!context.allowNetwork || context.signal.aborted) return;
			// Skip the request if pi.dev was checked less than REMOTE_CATALOG_REFRESH_INTERVAL_MS ago.
			// An entry without lastModified (saved by an older pi, or after a failed first request) is
			// always refetched: remoteModels() ignores it, so waiting would leave the overlay empty.
			if (
				!context.force &&
				stored?.checkedAt !== undefined &&
				stored.lastModified !== undefined &&
				Date.now() - stored.checkedAt < REMOTE_CATALOG_REFRESH_INTERVAL_MS
			) {
				return;
			}

			// Only revalidate when a cached body backs the validator, so a 304 can never
			// leave the overlay empty.
			const validator = stored && stored.models.length > 0 ? stored.etag : undefined;
			const response = await requestCatalog(provider.id, catalogBaseUrl, context.signal, validator);
			if (context.signal.aborted) return;
			const checkedAt = Date.now();
			// Unchanged: dynamicModels already holds the stored overlay, so only the
			// freshness window moves.
			if (response.status === 304 && stored) {
				await context.publish({ persist: { ...stored, checkedAt } });
				return;
			}
			if (response.status === 404 || response.status === 501) {
				await context.publish({
					persist: {
						...(stored ?? { models: [] }),
						checkedAt,
						lastModified: 0,
						etag: undefined,
					},
				});
				return;
			}
			if (!response.ok) {
				// Transient failure: the cached body and its validator stay valid, so keep the
				// etag and let the next refresh revalidate instead of downloading the catalog.
				await context.publish({ persist: { ...(stored ?? { models: [] }), checkedAt } });
				throw new Error(`Model catalog request failed for ${provider.id}: ${response.status}`);
			}
			const catalog = await readCatalog(provider.id, response);
			if (context.signal.aborted) return;
			const entry: ModelsStoreEntry = { ...catalog, checkedAt };
			const published = remoteModels(entry, localGeneratedAt);
			await context.publish({
				persist: entry,
				update: () => {
					dynamicModels = published;
				},
			});
		},
	};
	// Without newer chat models it lists the provider's own.
	return setModelListing(wrapped, () => (dynamicChatModels().length === 0 ? getModelListing(provider) : undefined));
}

/**
 * Like `withRemoteCatalog`, but on each network refresh the catalog (built-in models plus a newer
 * pi.dev catalog) is passed through `personalize` and the result is saved. The saved entry holds
 * the personalized list, not the pi.dev catalog, and replaces the catalog, including offline.
 * The refresh interval and etag revalidation are skipped. An empty list (nothing saved yet, or an
 * empty catalog saved by an older pi after a failed or missing pi.dev catalog) falls back to the
 * built-in models.
 */
export function withPersonalizedCatalog(
	provider: Provider,
	personalize: PersonalizeCatalog,
	catalogBaseUrl: string = DEFAULT_CATALOG_BASE_URL,
	localGeneratedAt?: number,
): Provider {
	let personalModels: readonly AnyModel[] = [];
	const builtinModels = (): readonly AnyModel[] => provider.getAllModels?.() ?? provider.getModels();
	const getAllModels = (): readonly AnyModel[] => (personalModels.length > 0 ? personalModels : builtinModels());

	const wrapped: Provider = {
		...provider,
		getModels: () => getAllModels().filter((model) => isModelType(model, "chat")),
		getAllModels,
		refreshModels: async (context) => {
			const restored = (context.stored?.models ?? []).filter((model) => model.provider === provider.id);
			// (As in withRemoteCatalog: offline, nothing to restore or clear changes nothing.)
			if (!context.allowNetwork && restored.length === 0 && personalModels.length === 0) return;
			if (
				!(await context.publish({
					update: () => {
						personalModels = restored;
					},
				}))
			) {
				return;
			}
			if (!context.allowNetwork || context.signal.aborted) return;

			const response = await requestCatalog(provider.id, catalogBaseUrl, context.signal);
			if (context.signal.aborted) return;
			let remote: readonly AnyModel[] = [];
			if (response.status !== 404 && response.status !== 501) {
				if (!response.ok) throw new Error(`Model catalog request failed for ${provider.id}: ${response.status}`);
				const catalog = await readCatalog(provider.id, response);
				if (isNewer(catalog.lastModified, localGeneratedAt)) remote = catalog.models;
			}
			const personalized = await personalize(mergeModels(builtinModels(), remote), context);
			if (context.signal.aborted) return;
			await context.publish({
				persist: { models: personalized, checkedAt: Date.now() },
				update: () => {
					personalModels = personalized;
				},
			});
		},
	};
	// Until a personalized catalog replaces them, it lists the provider's own models.
	return setModelListing(wrapped, () => (personalModels.length === 0 ? getModelListing(provider) : undefined));
}
