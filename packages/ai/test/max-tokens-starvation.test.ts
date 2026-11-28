import type Anthropic from "@anthropic-ai/sdk";
import { describe, expect, it } from "vitest";
import { stream as streamAnthropic } from "../src/api/anthropic-messages.ts";
import { clampMaxTokensToContext } from "../src/api/simple-options.ts";
import { getModel, normalizeContext } from "../src/compat.ts";
import type { AssistantMessage, Message, Model } from "../src/types.ts";
import { estimateContextTokens } from "../src/utils/estimate.ts";
import { isContextOverflow } from "../src/utils/overflow.ts";

function usage(input: number) {
	return {
		input,
		output: 0,
		cacheRead: 0,
		cacheWrite: 0,
		totalTokens: input,
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
	};
}

function assistant(input: number, timestamp: number): AssistantMessage {
	return {
		role: "assistant",
		content: [{ type: "text", text: "ok" }],
		api: "openai-completions",
		provider: "openai",
		model: "gpt-4o-mini",
		usage: usage(input),
		stopReason: "stop",
		timestamp,
	};
}

function modelWith(maxTokens: number, contextWindow: number): Model<"openai-completions"> {
	const { compat: _compat, ...base } = getModel("openai", "gpt-4o-mini")!;
	return { ...base, api: "openai-completions", contextWindow, maxTokens };
}

describe("max_tokens is never clamped to a starved request", () => {
	// upstream #12626: one message without a timestamp turned the usage anchor into NaN, so the estimate fell back to
	// counting characters of the whole transcript.
	it("keeps the usage anchor when a message has no timestamp", () => {
		const messages = [
			{ role: "user", content: "x".repeat(400_000), timestamp: 1 },
			{ role: "user", content: "injected without a timestamp" } as unknown as Message,
			assistant(60_000, 3),
			{ role: "user", content: "next", timestamp: 4 },
		] as Message[];
		const estimate = estimateContextTokens(messages);
		expect(estimate.lastUsageIndex).toBe(2);
		expect(estimate.usageTokens).toBe(60_000);
		expect(clampMaxTokensToContext(modelWith(32_768, 200_000), normalizeContext({ messages }), 32_768)).toBe(32_768);
	});

	// upstream #12482: with almost no room left the request is not sent with a cap of a few tokens; it fails as a
	// context overflow, so overflow recovery compacts first.
	it("fails as a context overflow when less than the least usable output fits", () => {
		const context = normalizeContext({
			messages: [{ role: "user", content: "hi", timestamp: 1 }, assistant(28_000, 2)],
		});
		let error: unknown;
		try {
			clampMaxTokensToContext(modelWith(32_768, 32_768), context, 32_768);
		} catch (caught) {
			error = caught;
		}
		expect(error).toBeInstanceOf(Error);
		const message: AssistantMessage = {
			...assistant(0, 3),
			stopReason: "error",
			errorMessage: (error as Error).message,
		};
		expect(isContextOverflow(message, 32_768)).toBe(true);
	});

	it("still sends a small explicit cap when it fits", () => {
		const context = normalizeContext({
			messages: [{ role: "user", content: "hi", timestamp: 1 }, assistant(28_000, 2)],
		});
		expect(clampMaxTokensToContext(modelWith(32_768, 32_768), context, 1)).toBe(1);
	});

	// upstream #12293: after a server-tool loop Anthropic reports usage summed over the loop's iterations; the context
	// is the last iteration's.
	it("takes the Anthropic context size from the last sampling iteration", async () => {
		const iterations = [
			{
				type: "message",
				input_tokens: 199_000,
				output_tokens: 400,
				cache_read_input_tokens: 0,
				cache_creation_input_tokens: 0,
			},
			{
				type: "message",
				input_tokens: 203_000,
				output_tokens: 406,
				cache_read_input_tokens: 0,
				cache_creation_input_tokens: 0,
			},
		];
		const summed = {
			input_tokens: 402_000,
			output_tokens: 806,
			cache_read_input_tokens: 0,
			cache_creation_input_tokens: 0,
		};
		const events = [
			{ type: "message_start", message: { id: "msg", usage: { ...summed, output_tokens: 0 } } },
			{ type: "content_block_start", index: 0, content_block: { type: "text", text: "" } },
			{ type: "content_block_delta", index: 0, delta: { type: "text_delta", text: "done" } },
			{ type: "content_block_stop", index: 0 },
			{ type: "message_delta", delta: { stop_reason: "end_turn" }, usage: { ...summed, iterations } },
			{ type: "message_stop" },
		];
		const body = events.map((event) => `event: ${event.type}\ndata: ${JSON.stringify(event)}\n`).join("\n");
		const response = new Response(body, { status: 200, headers: { "content-type": "text/event-stream" } });
		const client = {
			beta: { messages: { create: () => ({ asResponse: async () => response }) } },
		} as unknown as Anthropic;
		const model = { ...getModel("anthropic", "claude-haiku-4-5"), contextWindow: 400_000, maxTokens: 65_536 };

		const result = await streamAnthropic(
			model,
			normalizeContext({ messages: [{ role: "user", content: "search", timestamp: 1 }] }),
			{ client },
		).result();

		// Cost keeps the billed sums; the context reading is the last iteration.
		expect(result.usage.input).toBe(402_000);
		expect(result.usage.totalTokens).toBe(203_406);
		const next = normalizeContext({
			messages: [
				{ role: "user", content: "search", timestamp: 1 },
				{ ...result, timestamp: 2 },
				{ role: "user", content: "next", timestamp: 3 },
			],
		});
		expect(clampMaxTokensToContext(model, next, 65_536)).toBe(65_536);
	});
});
