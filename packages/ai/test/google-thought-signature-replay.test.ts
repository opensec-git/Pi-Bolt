import type { Content } from "@google/genai";
import { describe, expect, it } from "vitest";
import { convertMessages } from "../src/api/google-shared.ts";
import type { AssistantMessage, Message, Model, ToolCall } from "../src/types.ts";
import { normalizeContext } from "../src/utils/transcript.ts";

type GoogleApi = "google-generative-ai" | "google-vertex";

function googleModel<TApi extends GoogleApi>(api: TApi, provider: string, id: string): Model<TApi> {
	return {
		id,
		name: id,
		api,
		provider,
		baseUrl: "https://example.com",
		reasoning: true,
		input: ["text"],
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
		contextWindow: 128000,
		maxTokens: 8192,
	};
}

const usage = {
	input: 0,
	output: 0,
	cacheRead: 0,
	cacheWrite: 0,
	totalTokens: 0,
	cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
};

function assistant(sender: { api: string; provider: string; id: string }, calls: ToolCall[]): AssistantMessage {
	return {
		role: "assistant",
		content: calls,
		api: sender.api,
		provider: sender.provider,
		model: sender.id,
		usage,
		stopReason: "toolUse",
		timestamp: 2,
	};
}

function call(id: string, thoughtSignature?: string): ToolCall {
	return {
		type: "toolCall",
		id,
		name: "read",
		arguments: { path: id },
		...(thoughtSignature && { thoughtSignature }),
	};
}

function result(toolCallId: string): Message {
	return {
		role: "toolResult",
		toolCallId,
		toolName: "read",
		content: [{ type: "text", text: "x" }],
		isError: false,
		timestamp: 3,
	};
}

function functionCallSignatures(contents: Content[]): (string | undefined)[][] {
	return contents
		.filter((content) => content.role === "model")
		.map((content) => (content.parts ?? []).filter((part) => part.functionCall).map((part) => part.thoughtSignature));
}

describe("google-shared convertMessages: thought signatures replayed for the same model", () => {
	// #12637: some Gemini hosts return URL-safe base64 signatures; dropping them gave a 400 after every tool call.
	it.each([
		["URL-safe, padded to a group of 4", "AY89a18tFAnVDb0ig_7rIyoV"],
		["URL-safe with '-'", "AY89a18t-AnVDb0ig_7rIy=="],
		["URL-safe, unpadded", "AY89a18tFAnVDb0ig_7rIy"],
		["standard", "AY89a18tFAnVDb0ig/7rIyoV"],
	])("keeps a %s signature (#12637)", (_name, signature) => {
		const model = googleModel("google-generative-ai", "google", "gemini-3-pro-preview");
		const contents = convertMessages(
			model,
			normalizeContext({
				messages: [
					{ role: "user", content: "hi", timestamp: 1 },
					assistant(model, [call("c1", signature)]),
					result("c1"),
				],
			}),
		);
		expect(functionCallSignatures(contents)).toEqual([[signature]]);
	});

	it.each([
		["a single trailing character", "AY89a18tFAnVDb0ig_7rIyoVA"],
		["padding on a short group", "AY89a18tFAnVDb0ig_7rI="],
		["characters outside base64", "not a signature!"],
	])("drops a signature with %s", (_name, signature) => {
		const model = googleModel("google-generative-ai", "google", "gemini-3-pro-preview");
		const contents = convertMessages(
			model,
			normalizeContext({
				messages: [
					{ role: "user", content: "hi", timestamp: 1 },
					assistant(model, [call("c1", signature)]),
					result("c1"),
				],
			}),
		);
		expect(functionCallSignatures(contents)).toEqual([[undefined]]);
	});
});

describe("google-shared convertMessages: unsigned calls in the current turn for Gemini 4 (#12420)", () => {
	const claude = { api: "anthropic-messages", provider: "anthropic", id: "claude-sonnet-5-5" };

	// #12420: a switch from Claude to Gemini 4 on Vertex mid tool loop gave "Function call is missing a
	// thought_signature in functionCall parts".
	it.each([
		googleModel("google-vertex", "google-vertex", "gemini-4-neon"),
		googleModel("google-generative-ai", "google", "gemini-4-argon"),
	])("signs the first call of each step of the current turn for $api", (model) => {
		const contents = convertMessages(
			model,
			normalizeContext({
				messages: [
					{ role: "user", content: "earlier", timestamp: 1 },
					assistant(claude, [call("old")]),
					result("old"),
					{ role: "user", content: "now", timestamp: 1 },
					assistant(claude, [call("a1"), call("a2")]),
					result("a1"),
					result("a2"),
					assistant(model, [call("b1", "c2lnbmF0dXJl"), call("b2")]),
					result("b1"),
					result("b2"),
					assistant(claude, [call("c1")]),
					result("c1"),
				],
			}),
		);
		expect(functionCallSignatures(contents)).toEqual([
			// Before the current turn: not validated, left unsigned.
			[undefined],
			// Parallel siblings stay unsigned, as Gemini sends them.
			["skip_thought_signature_validator", undefined],
			// Gemini's own signature is kept.
			["c2lnbmF0dXJl", undefined],
			["skip_thought_signature_validator"],
		]);
	});

	it("leaves Gemini 3 unsigned calls as they are (#4032)", () => {
		const model = googleModel("google-vertex", "google-vertex", "gemini-3-pro-preview");
		const contents = convertMessages(
			model,
			normalizeContext({
				messages: [{ role: "user", content: "now", timestamp: 1 }, assistant(claude, [call("a1")]), result("a1")],
			}),
		);
		expect(functionCallSignatures(contents)).toEqual([[undefined]]);
	});
});
