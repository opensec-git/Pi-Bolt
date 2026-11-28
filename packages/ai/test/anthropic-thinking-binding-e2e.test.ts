import { describe, expect, it } from "vitest";
import { stream } from "../src/api/anthropic-messages.ts";
import { getModel, normalizeContext } from "../src/compat.ts";
import type { AssistantMessage, Context } from "../src/types.ts";

const enabled = Boolean(process.env.ANTHROPIC_API_KEY);
const model = getModel("anthropic", "claude-fable-5-1");
const user = (content: string, timestamp: number) => ({ role: "user" as const, content, timestamp });

function strictBinding(payload: unknown): unknown {
	const params = payload as {
		thinking?: {
			block_binding?: { prefix_mismatch_behavior?: "drop_block" | "error" };
		};
	};
	if (params.thinking?.block_binding) {
		params.thinking.block_binding.prefix_mismatch_behavior = "error";
	}
	return params;
}

async function request(context: Context, effort: "low" | "high"): Promise<AssistantMessage> {
	return stream(model, normalizeContext(context), {
		apiKey: process.env.ANTHROPIC_API_KEY,
		cacheRetention: "none",
		maxTokens: 1536,
		thinkingEnabled: true,
		thinkingDisplay: "summarized",
		effort,
		onPayload: strictBinding,
	}).result();
}

describe.skipIf(!enabled)("Anthropic thinking binding conformance", () => {
	it("replays managed effort markers required by signed Fable thinking", { timeout: 120000 }, async () => {
		const firstUser = user("Compute 982451653 multiplied by 961748941. Return only the integer.", 1);
		const first = await request({ messages: [firstUser] }, "low");
		expect(first.stopReason, first.errorMessage).toBe("stop");
		expect(
			first.content.some(
				(block) =>
					block.type === "thinking" &&
					typeof block.thinkingSignature === "string" &&
					block.thinkingSignature.length > 0,
			),
		).toBe(true);
		expect(first.providerThinkingLevel).toBe("low");

		const secondUser = user("Reply with exactly: ok", 2);
		const exact = await request({ messages: [firstUser, first, secondUser] }, "high");
		expect(exact.stopReason, exact.errorMessage).toBe("stop");

		const unmanagedHistory: AssistantMessage = { ...first };
		delete unmanagedHistory.providerThinkingLevel;
		const missingMarker = await request({ messages: [firstUser, unmanagedHistory, secondUser] }, "high");
		expect(missingMarker.stopReason).toBe("error");
		expect(missingMarker.errorMessage).toContain("Invalid `signature`");
	});

	// #346
	it("stops replaying thinking removed after a system prompt change", { timeout: 180000 }, async () => {
		const send = (context: Context) =>
			stream(model, normalizeContext(context), {
				apiKey: process.env.ANTHROPIC_API_KEY,
				cacheRetention: "none",
				maxTokens: 1536,
				thinkingEnabled: true,
				thinkingDisplay: "summarized",
				effort: "low",
			}).result();
		const dropped = (message: AssistantMessage) =>
			(message.diagnostics ?? [])
				.filter((diagnostic) => diagnostic.type === "anthropic_input_transformations")
				.flatMap(
					(diagnostic) => diagnostic.details?.transformations as Array<{ type?: string; signatureHash?: string }>,
				)
				.filter((transformation) => transformation.type === "thinking_dropped");
		const context: Context = {
			systemPrompt: "You are a terse assistant. Version 1.",
			messages: [user("Compute 982451653 multiplied by 961748941. Return only the integer.", 1)],
		};
		const first = await send(context);
		expect(first.stopReason, first.errorMessage).toBe("stop");

		context.systemPrompt = "You are a terse assistant. Version 2.";
		context.messages.push(first, user("Add 7 to that. Only the integer.", 2));
		const afterChange = await send(context);
		expect(afterChange.stopReason, afterChange.errorMessage).toBe("stop");
		const reported = dropped(afterChange);
		expect(reported.length).toBeGreaterThan(0);
		expect(reported.every((transformation) => typeof transformation.signatureHash === "string")).toBe(true);

		context.messages.push(afterChange, user("Reply with exactly: ok", 3));
		const next = await send(context);
		expect(next.stopReason, next.errorMessage).toBe("stop");
		expect(dropped(next)).toEqual([]);
	});
});
