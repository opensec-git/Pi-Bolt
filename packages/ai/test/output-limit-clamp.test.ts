import { describe, expect, it } from "vitest";
import { buildBaseOptions } from "../src/api/simple-options.ts";
import { streamSimple } from "../src/compat.ts";
import type { AssistantMessage, Model, Usage } from "../src/types.ts";
import { normalizeContext } from "../src/utils/transcript.ts";

class PayloadCaptured extends Error {
	constructor() {
		super("payload captured");
		this.name = "PayloadCaptured";
	}
}

function createUsage(totalTokens: number): Usage {
	return {
		input: totalTokens,
		output: 0,
		cacheRead: 0,
		cacheWrite: 0,
		totalTokens,
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
	};
}

function createAssistant(timestamp: number, totalTokens: number): AssistantMessage {
	return {
		role: "assistant",
		content: [{ type: "text", text: "kept" }],
		api: "anthropic-messages",
		provider: "test-anthropic",
		model: "claude-test",
		usage: createUsage(totalTokens),
		stopReason: "stop",
		timestamp,
	};
}

function createModel(contextWindow: number, maxTokens: number): Model<"anthropic-messages"> {
	return {
		id: "claude-test",
		name: "Claude Test",
		api: "anthropic-messages",
		provider: "test-anthropic",
		baseUrl: "http://127.0.0.1:9",
		reasoning: true,
		input: ["text"],
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
		contextWindow,
		maxTokens,
	};
}

/** A context whose only message estimates to `tokens` (3.5 chars per token). */
function estimatedContext(tokens: number) {
	return normalizeContext({ messages: [{ role: "user", content: "x".repeat(tokens * 3.5), timestamp: 1 }] });
}

describe("output limit clamp", () => {
	const largeModel = createModel(1_000_000, 128_000);

	it("keeps the model output limit far from the context window", () => {
		expect(buildBaseOptions(largeModel, estimatedContext(100_000)).maxTokens).toBe(128_000);
	});

	// earendil-works/pi#10497, earendil-works/pi#11161
	it("keeps a margin that grows with the estimated input", () => {
		// 800K estimated tokens. If the text really runs 3.2 chars per token it is 875K, and the old fixed margin
		// (1M - 800K - 4096, capped to 128K) asked for 128K output: 1.003M in total, over the window.
		expect(buildBaseOptions(largeModel, estimatedContext(800_000)).maxTokens).toBe(1_000_000 - 800_000 - 160_000);
	});

	it("does not scale the margin for tokens the provider reported", () => {
		const context = normalizeContext({
			messages: [
				{ role: "user", content: "start", timestamp: 1 },
				createAssistant(2, 900_000),
				{ role: "user", content: "x".repeat(35_000), timestamp: 3 },
			],
		});

		// Only the 10K estimated tail gets the scaled margin (2K), so the fixed 4096 applies.
		expect(buildBaseOptions(largeModel, context).maxTokens).toBe(1_000_000 - 910_000 - 4096);
	});

	// earendil-works/pi#11236
	it("never sends an output limit that cannot produce an answer", () => {
		const model = createModel(10_000, 8_000);
		const context = estimatedContext(10_000);

		expect(buildBaseOptions(model, context).maxTokens).toBe(4096);
		expect(buildBaseOptions(model, context, { maxTokens: 512 }).maxTokens).toBe(512);
	});

	// earendil-works/pi#11236
	it("keeps the floor after the Anthropic thinking budget is added", async () => {
		let payload: { max_tokens?: number; thinking?: { budget_tokens?: number } } | undefined;
		const stream = streamSimple(createModel(10_000, 8_000), estimatedContext(10_000), {
			apiKey: "test-key",
			reasoning: "high",
			onPayload: (value) => {
				payload = value as typeof payload;
				throw new PayloadCaptured();
			},
		});
		await stream.result();

		expect(payload?.max_tokens).toBe(4096);
		expect(payload?.thinking?.budget_tokens).toBe(4096 - 1024);
	});
});
