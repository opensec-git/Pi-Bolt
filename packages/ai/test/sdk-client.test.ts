import { beforeEach, describe, expect, it, vi } from "vitest";
import { stream } from "../src/api/openai-completions.ts";
import { normalizeContext } from "../src/compat.ts";
import type { Context, Model } from "../src/types.ts";
import { clientKey, forgetReusableClients, reusableClient } from "../src/utils/sdk-client.ts";

beforeEach(() => forgetReusableClients());

describe("reusableClient", () => {
	it("makes a client once per configuration and fetch", () => {
		const fetchA = () => undefined;
		const fetchB = () => undefined;
		const make = vi.fn(() => ({}));
		const a = reusableClient(clientKey("key", "https://a", { h: "1" }), fetchA, make);
		expect(reusableClient(clientKey("key", "https://a", { h: "1" }), fetchA, make)).toBe(a);
		expect(make).toHaveBeenCalledTimes(1);
		expect(reusableClient(clientKey("key", "https://a", { h: "1" }), fetchB, make)).not.toBe(a);
		expect(reusableClient(clientKey("other", "https://a", { h: "1" }), fetchB, make)).not.toBe(a);
		expect(reusableClient(clientKey("key", "https://a", { h: "2" }), fetchB, make)).not.toBe(a);
		expect(make).toHaveBeenCalledTimes(4);
	});

	it("keeps the four latest configurations", () => {
		const make = vi.fn(() => ({}));
		const first = reusableClient("0", undefined, make);
		for (const key of ["1", "2", "3"]) reusableClient(key, undefined, make);
		// Using the first again makes "1" the oldest.
		expect(reusableClient("0", undefined, make)).toBe(first);
		reusableClient("4", undefined, make);
		expect(reusableClient("0", undefined, make)).toBe(first);
		expect(make).toHaveBeenCalledTimes(5);
		reusableClient("1", undefined, make);
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
});
