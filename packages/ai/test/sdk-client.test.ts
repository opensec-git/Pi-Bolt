import { beforeEach, describe, expect, it, vi } from "vitest";
import { stream } from "../src/api/openai-completions.ts";
import { normalizeContext } from "../src/compat.ts";
import type { Context, Model } from "../src/types.ts";
import { forgetReusableClients, reusableClient } from "../src/utils/sdk-client.ts";

beforeEach(() => forgetReusableClients());

const config = (apiKey: string, baseUrl = "https://a", headers: unknown = { h: "1" }, fetch?: unknown) => ({
	apiKey,
	baseUrl,
	headers,
	fetch,
});

describe("reusableClient", () => {
	it("makes a client once per configuration", () => {
		const make = vi.fn(() => ({}));
		const a = reusableClient(config("key"), make);
		expect(reusableClient(config("key"), make)).toBe(a);
		expect(reusableClient(config("key", "https://a", { h: "2" }), make)).not.toBe(a);
		expect(reusableClient(config("key", "https://b"), make)).not.toBe(a);
		expect(make).toHaveBeenCalledTimes(3);
		// The global fetch itself, passed explicitly, is the same configuration.
		expect(reusableClient(config("key", "https://a", { h: "1" }, globalThis.fetch), make)).toBe(a);
	});

	it("does not keep a client made with a fetch of the caller's", () => {
		const fetch = () => undefined;
		const make = vi.fn(() => ({}));
		const a = reusableClient(config("key", "https://a", {}, fetch), make);
		expect(reusableClient(config("key", "https://a", {}, fetch), make)).not.toBe(a);
		expect(make).toHaveBeenCalledTimes(2);
	});

	it("drops the clients of another key for the same base URL", () => {
		const make = vi.fn(() => ({}));
		const old = reusableClient(config("old"), make);
		const other = reusableClient(config("x", "https://b"), make);
		reusableClient(config("new"), make);
		// The old key's client is gone; another base URL's stays.
		expect(reusableClient(config("old"), make)).not.toBe(old);
		expect(reusableClient(config("x", "https://b"), make)).toBe(other);
	});

	it("keeps the four latest configurations", () => {
		const make = vi.fn(() => ({}));
		const first = reusableClient(config("k", "0"), make);
		for (const url of ["1", "2", "3"]) reusableClient(config("k", url), make);
		// Using the first again makes "1" the oldest.
		expect(reusableClient(config("k", "0"), make)).toBe(first);
		reusableClient(config("k", "4"), make);
		expect(reusableClient(config("k", "0"), make)).toBe(first);
		expect(make).toHaveBeenCalledTimes(5);
		reusableClient(config("k", "1"), make);
		expect(make).toHaveBeenCalledTimes(6);
	});
});

describe("openai-completions", () => {
	const model: Model<"openai-completions"> = {
		id: "m",
		name: "m",
		api: "openai-completions",
		provider: "test",
		baseUrl: "https://example.invalid/v1",
		reasoning: false,
		input: ["text"],
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
		contextWindow: 1000,
		maxTokens: 100,
	};
	const context = normalizeContext({ messages: [{ role: "user", content: "hi", timestamp: 0 }] } satisfies Context);
	const sse = () =>
		new Response(
			'data: {"id":"c","object":"chat.completion.chunk","created":0,"model":"m","choices":[{"index":0,"delta":{"content":"ok"},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n',
			{ status: 200, headers: { "content-type": "text/event-stream" } },
		);

	it("sends each request through the fetch in effect when it is made", async () => {
		const first = vi.fn(async () => sse());
		const second = vi.fn(async () => sse());
		vi.stubGlobal("fetch", first);
		try {
			await stream(model, context, { apiKey: "k" }).result();
			await stream(model, context, { apiKey: "k" }).result();
			vi.stubGlobal("fetch", second);
			await stream(model, context, { apiKey: "k" }).result();
		} finally {
			vi.unstubAllGlobals();
		}
		expect(first).toHaveBeenCalledTimes(2);
		expect(second).toHaveBeenCalledTimes(1);
	});

	it("uses a fetch passed with the request for that request", async () => {
		const own = vi.fn(async () => sse());
		const other = vi.fn(async () => sse());
		await stream(model, context, { apiKey: "k", fetch: own as unknown as typeof fetch }).result();
		await stream(model, context, { apiKey: "k", fetch: other as unknown as typeof fetch }).result();
		expect(own).toHaveBeenCalledTimes(1);
		expect(other).toHaveBeenCalledTimes(1);
	});
});
