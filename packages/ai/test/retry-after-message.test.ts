import { describe, expect, it, vi } from "vitest";
import { streamSimple as streamAnthropic } from "../src/api/anthropic-messages.ts";
import { streamSimple as streamAzureOpenAIResponses } from "../src/api/azure-openai-responses.ts";
import { streamSimple as streamOpenAICompletions } from "../src/api/openai-completions.ts";
import { streamSimple as streamOpenAIResponses } from "../src/api/openai-responses.ts";
import type { Api, AssistantMessage, FetchFunction, Model } from "../src/types.ts";
import { normalizeContext } from "../src/utils/transcript.ts";

const context = normalizeContext({ messages: [{ role: "user", content: "hello", timestamp: 1 }] });

function createModel<TApi extends Api>(api: TApi): Model<TApi> {
	return {
		id: "test-model",
		name: "Test Model",
		api,
		provider: "test-provider",
		baseUrl: "https://upstream.test/v1",
		reasoning: false,
		input: ["text"],
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
		contextWindow: 10_000,
		maxTokens: 1_000,
	};
}

function rateLimited(headers: Record<string, string>): FetchFunction {
	return vi.fn<FetchFunction>(
		async () =>
			new Response(JSON.stringify({ error: { message: "rate limit exceeded" } }), {
				status: 429,
				headers: { "content-type": "application/json", ...headers },
			}),
	);
}

function runAll(fetch: FetchFunction): Promise<AssistantMessage[]> {
	const options = { apiKey: "test-key", fetch, maxRetries: 0 };
	return Promise.all([
		streamAnthropic(createModel("anthropic-messages"), context, options).result(),
		streamOpenAICompletions(createModel("openai-completions"), context, options).result(),
		streamOpenAIResponses(createModel("openai-responses"), context, options).result(),
		streamAzureOpenAIResponses(createModel("azure-openai-responses"), context, options).result(),
	]);
}

// earendil-works/pi#10601
describe("server retry delay on failed responses", () => {
	it("records retry-after seconds on the error message", async () => {
		const messages = await runAll(rateLimited({ "retry-after": "30" }));

		for (const message of messages) {
			expect(message.stopReason).toBe("error");
			expect(message.retryAfterMs).toBe(30_000);
		}
	});

	it("prefers retry-after-ms", async () => {
		const messages = await runAll(rateLimited({ "retry-after-ms": "1500", "retry-after": "30" }));

		expect(messages.map((message) => message.retryAfterMs)).toEqual([1500, 1500, 1500, 1500]);
	});

	it("leaves the field out when the response has no delay", async () => {
		const messages = await runAll(rateLimited({}));

		for (const message of messages) {
			expect(message.stopReason).toBe("error");
			expect("retryAfterMs" in message).toBe(false);
		}
	});
});
