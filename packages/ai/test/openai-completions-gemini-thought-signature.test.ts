import { Type } from "typebox";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { stream as streamOpenAICompletions } from "../src/api/openai-completions.ts";
import type { AssistantMessage, Message, Model, Tool } from "../src/types.ts";
import { normalizeContext } from "../src/utils/transcript.ts";

// #12613, #10157: Gemini behind an OpenAI-compatible endpoint sends its thought signature in
// tool_calls[].extra_content.google.thought_signature and rejects the next request with "Function call is missing a
// thought_signature in functionCall parts" when the call is replayed without it.

const mockState = vi.hoisted(() => ({
	chunkSets: [] as unknown[][],
	payloads: [] as unknown[],
}));

vi.mock("openai", () => {
	class FakeOpenAI {
		chat = {
			completions: {
				create: (payload: unknown) => {
					mockState.payloads.push(payload);
					const chunks = mockState.chunkSets.shift() ?? [];
					const stream = {
						async *[Symbol.asyncIterator]() {
							for (const chunk of chunks) yield chunk;
						},
					};
					const result = Promise.resolve(stream) as Promise<typeof stream> & {
						withResponse: () => Promise<{ data: typeof stream; response: { status: number; headers: Headers } }>;
					};
					result.withResponse = async () => ({ data: stream, response: { status: 200, headers: new Headers() } });
					return result;
				},
			},
		};
	}
	return { default: FakeOpenAI };
});

const SIGNATURE = "AY89a18tFAnVDb0ig_7rIyoV";
const readTool: Tool = { name: "read", description: "Read a file", parameters: Type.Object({ path: Type.String() }) };

function model(id = "gemini-3.1-pro-preview"): Model<"openai-completions"> {
	return {
		id,
		name: id,
		api: "openai-completions",
		provider: "gemini-openai",
		baseUrl: "https://generativelanguage.googleapis.com/v1beta/openai",
		reasoning: true,
		input: ["text"],
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
		contextWindow: 100_000,
		maxTokens: 4096,
	};
}

function chunk(delta: Record<string, unknown>, finishReason: string | null = null): unknown {
	return { id: "chatcmpl-test", model: "gemini", choices: [{ index: 0, delta, finish_reason: finishReason }] };
}

async function run(messages: Message[], m = model()): Promise<AssistantMessage> {
	return await streamOpenAICompletions(m, normalizeContext({ messages, tools: [readTool] }), { apiKey: "k" }).result();
}

function replayedToolCalls(payload: unknown): Record<string, unknown>[] | undefined {
	const messages = (payload as { messages: Array<{ role: string; tool_calls?: Record<string, unknown>[] }> }).messages;
	return messages.find((message) => message.role === "assistant")?.tool_calls;
}

function followUp(reply: AssistantMessage): Message[] {
	return [
		{ role: "user", content: "read it", timestamp: 1 },
		reply,
		{
			role: "toolResult",
			toolCallId: "call_1",
			toolName: "read",
			content: [{ type: "text", text: "x" }],
			isError: false,
			timestamp: 3,
		},
	];
}

describe("openai-completions: Gemini tool call thought signatures (#12613, #10157)", () => {
	beforeEach(() => {
		mockState.chunkSets = [];
		mockState.payloads = [];
	});

	it("keeps the signature of a streamed tool call and replays it as extra_content", async () => {
		mockState.chunkSets = [
			[
				chunk({
					tool_calls: [
						{
							index: 0,
							id: "call_1",
							type: "function",
							function: { name: "read", arguments: '{"path":"a"}' },
							extra_content: { google: { thought_signature: SIGNATURE } },
						},
					],
				}),
				chunk({}, "tool_calls"),
			],
			[chunk({ content: "ok" }), chunk({}, "stop")],
		];
		const reply = await run([{ role: "user", content: "read it", timestamp: 1 }]);
		expect(reply.content).toEqual([
			{ type: "toolCall", id: "call_1", name: "read", arguments: { path: "a" }, thoughtSignature: SIGNATURE },
		]);

		await run(followUp(reply));
		expect(replayedToolCalls(mockState.payloads[1])).toEqual([
			{
				id: "call_1",
				type: "function",
				function: { name: "read", arguments: '{"path":"a"}' },
				extra_content: { google: { thought_signature: SIGNATURE } },
			},
		]);
	});

	it("takes a signature sent in a later delta of the call", async () => {
		mockState.chunkSets = [
			[
				chunk({ tool_calls: [{ index: 0, id: "call_1", type: "function", function: { name: "read" } }] }),
				chunk({ tool_calls: [{ index: 0, function: { arguments: '{"path":"a"}' } }] }),
				chunk({ tool_calls: [{ index: 0, extra_content: { google: { thought_signature: SIGNATURE } } }] }),
				chunk({}, "tool_calls"),
			],
		];
		const reply = await run([{ role: "user", content: "read it", timestamp: 1 }]);
		expect(reply.content).toEqual([
			{ type: "toolCall", id: "call_1", name: "read", arguments: { path: "a" }, thoughtSignature: SIGNATURE },
		]);
	});

	it("sends no extra_content for an unsigned call, an OpenRouter detail or a call from another model", async () => {
		const reply = (thoughtSignature: string | undefined, sender = model()): AssistantMessage => ({
			role: "assistant",
			content: [{ type: "toolCall", id: "call_1", name: "read", arguments: { path: "a" }, thoughtSignature }],
			api: sender.api,
			provider: sender.provider,
			model: sender.id,
			usage: {
				input: 0,
				output: 0,
				cacheRead: 0,
				cacheWrite: 0,
				totalTokens: 0,
				cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
			},
			stopReason: "toolUse",
			timestamp: 2,
		});
		const legacy = JSON.stringify({ type: "reasoning.encrypted", id: "call_1", data: "enc" });
		for (const message of [reply(undefined), reply(legacy), reply(SIGNATURE, model("gemini-other"))]) {
			mockState.chunkSets = [[chunk({ content: "ok" }), chunk({}, "stop")]];
			mockState.payloads = [];
			await run(followUp(message));
			expect(replayedToolCalls(mockState.payloads[0])?.[0]).not.toHaveProperty("extra_content");
		}
	});
});
