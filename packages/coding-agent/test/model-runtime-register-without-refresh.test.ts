import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createAssistantMessageEventStream, createProvider, InMemoryCredentialStore } from "@earendil-works/pi-ai";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ModelRuntime } from "../src/core/model-runtime.ts";
import { InMemoryCodingAgentModelsStore } from "../src/core/models-store.ts";
import type { ProviderConfigInput } from "../src/core/provider-composer.ts";
import { createInMemoryModelRegistry, getModelRuntime } from "./model-runtime-test-utils.ts";

const provider: ProviderConfigInput = {
	baseUrl: "https://provider.test/v1",
	apiKey: "test-key",
	api: "openai-completions",
	models: [
		{
			id: "demo-model",
			name: "Demo Model",
			reasoning: false,
			input: ["text"],
			cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
			contextWindow: 128000,
			maxTokens: 4096,
		},
	],
};

describe("ModelRuntime.registerWithoutRefresh", () => {
	it("registers at once and leaves the refresh to the caller", async () => {
		const runtime = getModelRuntime(await createInMemoryModelRegistry(new InMemoryCredentialStore()));
		const refresh = vi.spyOn(runtime, "refresh");

		runtime.registerWithoutRefresh(() => runtime.registerProvider("batched", provider));
		expect(refresh).not.toHaveBeenCalled();
		expect(runtime.getModel("batched", "demo-model")?.provider).toBe("batched");
		expect(runtime.hasConfiguredAuth("batched")).toBe(true);

		runtime.registerProvider("single", provider);
		expect(refresh).toHaveBeenCalledTimes(1);
	});

	it("refreshes after registrations again once its callback throws", async () => {
		const runtime = getModelRuntime(await createInMemoryModelRegistry(new InMemoryCredentialStore()));
		const refresh = vi.spyOn(runtime, "refresh");

		expect(() =>
			runtime.registerWithoutRefresh(() => {
				throw new Error("registration failed");
			}),
		).toThrow("registration failed");
		runtime.registerProvider("single", provider);
		expect(refresh).toHaveBeenCalledTimes(1);
	});
});

describe("ModelRuntime.refresh after create without refresh", () => {
	const dirs: string[] = [];
	afterEach(() => {
		for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true });
	});

	async function createRuntime(customName: string): Promise<{ runtime: ModelRuntime; modelsPath: string }> {
		const dir = mkdtempSync(join(tmpdir(), "pi-model-runtime-refresh-"));
		dirs.push(dir);
		const modelsPath = join(dir, "models.json");
		writeFileSync(modelsPath, JSON.stringify({ providers: { custom: { ...provider, name: customName } } }));
		const runtime = await ModelRuntime.create({
			credentials: new InMemoryCredentialStore(),
			modelsPath,
			modelsStore: new InMemoryCodingAgentModelsStore(),
			refreshOnCreate: false,
		});
		return { runtime, modelsPath };
	}

	it("keeps the providers composed from an unchanged models.json, and composes them again once it changes", async () => {
		const { runtime, modelsPath } = await createRuntime("Custom");
		const composed = runtime.getProvider("custom");
		expect(composed?.name).toBe("Custom");

		await runtime.refresh({ allowNetwork: false });
		expect(runtime.getProvider("custom")).toBe(composed);
		expect(runtime.hasConfiguredAuth("custom")).toBe(true);

		writeFileSync(modelsPath, JSON.stringify({ providers: { custom: { ...provider, name: "Renamed" } } }));
		await runtime.refresh({ allowNetwork: false });
		expect(runtime.getProvider("custom")).not.toBe(composed);
		expect(runtime.getProvider("custom")?.name).toBe("Renamed");
	});

	it("lists the providers in the order a fresh composition gives them after registrations", async () => {
		const { runtime } = await createRuntime("Custom");
		const native = createProvider({
			id: "native",
			auth: { apiKey: { name: "Native", resolve: async () => ({ auth: { apiKey: "key" } }) } },
			models: [],
			api: {
				stream: () => createAssistantMessageEventStream(),
				streamSimple: () => createAssistantMessageEventStream(),
			},
		});
		runtime.registerWithoutRefresh(() => {
			runtime.registerProvider("extension", provider);
			runtime.registerNativeProvider(native);
		});
		expect(
			runtime
				.getProviders()
				.slice(-3)
				.map((entry) => entry.id),
		).toEqual(["custom", "extension", "native"]);

		await runtime.refresh({ allowNetwork: false });
		// Built-in providers, then extension-defined native providers, models.json providers and extension configurations.
		expect(
			runtime
				.getProviders()
				.slice(-3)
				.map((entry) => entry.id),
		).toEqual(["native", "custom", "extension"]);
	});
});
