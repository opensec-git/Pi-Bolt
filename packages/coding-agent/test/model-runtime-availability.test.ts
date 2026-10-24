import { createProvider, fauxProvider, InMemoryModelsStore, type Provider } from "@earendil-works/pi-ai";
import { describe, expect, it } from "vitest";
import { AuthStorage } from "../src/core/auth-storage.ts";
import { ModelRuntime } from "../src/core/model-runtime.ts";

const MODELS = [
	{ id: "one", contextWindow: 1000, maxTokens: 100, input: ["text" as const] },
	{ id: "two", contextWindow: 1000, maxTokens: 100, input: ["text" as const] },
	{ id: "three", contextWindow: 1000, maxTokens: 100, input: ["text" as const] },
];

/** A faux provider whose auth needs a stored key, optionally narrowing its models by that key. */
function provider(id: string, filter: boolean): Provider {
	const faux = fauxProvider({ provider: id, models: MODELS });
	return createProvider({
		id,
		auth: {
			apiKey: {
				name: id,
				resolve: async ({ credential }) => (credential?.key ? { auth: { apiKey: credential.key } } : undefined),
			},
		},
		models: faux.models,
		api: { stream: faux.provider.stream, streamSimple: faux.provider.streamSimple },
		...(filter
			? { filterModels: (models, credential) => (credential?.key === "limited" ? models.slice(0, 2) : models) }
			: {}),
	});
}

describe("ModelRuntime availability", () => {
	it("lists what getAvailable() of each provider lists, in provider order", async () => {
		const runtime = await ModelRuntime.create({
			credentials: AuthStorage.inMemory({
				plain: { type: "api_key", key: "k" },
				narrowed: { type: "api_key", key: "limited" },
				wide: { type: "api_key", key: "full" },
			}),
			modelsStore: new InMemoryModelsStore(),
			modelsPath: null,
			allowModelNetwork: false,
		});
		for (const [id, filter] of [
			["plain", false],
			["narrowed", true],
			["unconfigured", false],
			["wide", true],
			["unconfiguredFiltered", true],
		] as const) {
			runtime.registerNativeProvider(provider(id, filter));
		}
		await runtime.refresh({ allowNetwork: false });

		const expected = [];
		for (const { id } of runtime.getProviders()) expected.push(...(await runtime.getAvailable(id)));
		const available = await runtime.getAvailable();
		expect(available).toEqual(expected);
		const ours = available.filter((model) => MODELS.some((m) => m.id === model.id));
		expect(ours.map((model) => `${model.provider}/${model.id}`)).toEqual([
			"plain/one",
			"plain/two",
			"plain/three",
			"narrowed/one",
			"narrowed/two",
			"wide/one",
			"wide/two",
			"wide/three",
		]);
		expect(runtime.hasConfiguredAuth("plain")).toBe(true);
		expect(runtime.hasConfiguredAuth("unconfigured")).toBe(false);
		expect(runtime.hasConfiguredAuth("unconfiguredFiltered")).toBe(false);
	});
});
