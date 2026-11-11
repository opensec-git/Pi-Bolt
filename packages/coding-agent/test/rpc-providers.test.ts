import { InMemoryModelsStore } from "@earendil-works/pi-ai";
import { describe, expect, it } from "vitest";
import { AuthStorage } from "../src/core/auth-storage.ts";
import { ModelRuntime } from "../src/core/model-runtime.ts";
import { describeProviders } from "../src/modes/rpc/rpc-providers.ts";

const COST = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 };

function chatModel(id: string) {
	return {
		type: "chat" as const,
		id,
		name: `Model ${id}`,
		reasoning: false,
		input: ["text" as const],
		cost: COST,
		contextWindow: 128000,
		maxTokens: 8192,
	};
}

async function runtimeWith(storage: AuthStorage): Promise<ModelRuntime> {
	const runtime = await ModelRuntime.create({
		credentials: storage,
		modelsStore: new InMemoryModelsStore(),
		modelsPath: null,
		allowModelNetwork: false,
	});
	runtime.registerProvider("acme", {
		name: "Acme AI",
		baseUrl: "http://127.0.0.1:9/v1",
		apiKey: "$PI_TEST_ACME_KEY_UNSET",
		api: "openai-completions",
		models: [chatModel("acme-chat")],
	});
	runtime.registerProvider("sso", {
		name: "SSO Corp",
		baseUrl: "http://127.0.0.1:9/v1",
		api: "openai-completions",
		models: [chatModel("sso-chat")],
		oauth: {
			name: "SSO Corp login",
			login: async () => ({ access: "a", refresh: "r", expires: Date.now() + 60_000 }),
			refreshToken: async (credentials) => credentials,
			getApiKey: (credentials) => credentials.access,
		},
	});
	await runtime.refresh({ allowNetwork: false });
	return runtime;
}

describe("get_providers", () => {
	it("describes extension providers: name, origin, key variable, login and models", async () => {
		const runtime = await runtimeWith(AuthStorage.inMemory({}));
		const providers = await describeProviders(runtime);
		const acme = providers.find((p) => p.id === "acme");
		expect(acme).toEqual({
			id: "acme",
			name: "Acme AI",
			origin: "extension",
			extension: true,
			configured: false,
			apiKey: { name: "API key", env: ["PI_TEST_ACME_KEY_UNSET"] },
			models: [
				{ id: "acme-chat", name: "Model acme-chat", reasoning: false, input: ["text"], contextWindow: 128000 },
			],
			available: [],
		});
		const sso = providers.find((p) => p.id === "sso");
		expect(sso?.oauth).toEqual({ name: "SSO Corp login" });
		// OAuth-only: no API key method is made up for it.
		expect(sso?.apiKey).toBeUndefined();
		const anthropic = providers.find((p) => p.id === "anthropic");
		expect(anthropic?.origin).toBe("builtin");
		expect(anthropic?.extension).toBe(false);
	});

	it("sees a key stored after startup, without the value", async () => {
		const storage = AuthStorage.inMemory({});
		const runtime = await runtimeWith(storage);
		await storage.modify("acme", async () => ({ type: "api_key", key: "secret-test-key" }));
		const providers = await describeProviders(runtime);
		const acme = providers.find((p) => p.id === "acme");
		expect(acme?.configured).toBe(true);
		expect(acme?.credential).toBe("api_key");
		expect(acme?.authSource).toBe("stored");
		expect(acme?.available).toEqual(["acme-chat"]);
		expect(JSON.stringify(providers)).not.toContain("secret-test-key");
	});

	it("never reports a literal key as a variable", async () => {
		const runtime = await runtimeWith(AuthStorage.inMemory({}));
		runtime.registerProvider("local", {
			name: "Local",
			baseUrl: "http://127.0.0.1:9/v1",
			apiKey: "literal-secret",
			api: "openai-completions",
			models: [chatModel("local-chat")],
		});
		const providers = await describeProviders(runtime);
		const local = providers.find((p) => p.id === "local");
		expect(local?.apiKey?.env).toEqual([]);
		expect(local?.configured).toBe(true);
		expect(JSON.stringify(providers)).not.toContain("literal-secret");
	});
});
