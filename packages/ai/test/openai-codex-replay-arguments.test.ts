import { zstdDecompressSync } from "node:zlib";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
	closeOpenAICodexWebSocketSessions,
	resetOpenAICodexWebSocketDebugStats,
	stream as streamOpenAICodexResponses,
} from "../src/api/openai-codex-responses.ts";
import { cleanupSessionResources } from "../src/session-resources.ts";
import type { Context, Message, Model } from "../src/types.ts";
import { normalizeContext } from "../src/utils/transcript.ts";

afterEach(() => {
	vi.unstubAllGlobals();
	closeOpenAICodexWebSocketSessions();
	resetOpenAICodexWebSocketDebugStats();
	cleanupSessionResources();
});

const token = `aaa.${Buffer.from(JSON.stringify({ "https://api.openai.com/auth": { chatgpt_account_id: "acc" } })).toString("base64")}.bbb`;

const model: Model<"openai-codex-responses"> = {
	id: "gpt-6.2-astra",
	name: "gpt-6.2-astra",
	api: "openai-codex-responses",
	provider: "openai-codex",
	baseUrl: "https://chatgpt.com/backend-api",
	reasoning: true,
	input: ["text"],
	cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
	contextWindow: 272000,
	maxTokens: 128000,
};

interface SentRequest {
	compressed: boolean;
	body: { input: Array<Record<string, unknown>> };
}

function decode(init: RequestInit | undefined): SentRequest {
	const body = init?.body;
	const compressed = new Headers(init?.headers).get("content-encoding") === "zstd";
	const text =
		body instanceof Uint8Array
			? Buffer.from(compressed ? zstdDecompressSync(body) : body).toString("utf8")
			: String(body);
	return { compressed, body: JSON.parse(text) };
}

const sse = (events: unknown[]) =>
	new Response(events.map((event) => `data: ${JSON.stringify(event)}\n\n`).join(""), {
		status: 200,
		headers: { "content-type": "text/event-stream" },
	});

const completed = {
	type: "response.completed",
	response: { status: "completed", usage: { input_tokens: 5, output_tokens: 1, total_tokens: 6 } },
};

const textReply = () =>
	sse([
		{
			type: "response.output_item.added",
			item: { type: "message", id: "msg_1", role: "assistant", status: "in_progress", content: [] },
		},
		{ type: "response.output_text.delta", delta: "OK" },
		{
			type: "response.output_item.done",
			item: {
				type: "message",
				id: "msg_1",
				role: "assistant",
				status: "completed",
				content: [{ type: "output_text", text: "OK" }],
			},
		},
		completed,
	]);

function stubFetch(answer: (request: SentRequest, index: number) => Response): SentRequest[] {
	const sent: SentRequest[] = [];
	vi.stubGlobal(
		"fetch",
		vi.fn(async (_url: string | URL, init?: RequestInit) => {
			const request = decode(init);
			sent.push(request);
			return answer(request, sent.length - 1);
		}),
	);
	return sent;
}

describe("openai-codex: replayed tool call arguments (#12444)", () => {
	// #12444: arguments were replayed as JSON.stringify of the parsed object. When the model's text had other spacing,
	// the replayed item no longer matched the one OpenAI stored: the prompt cache broke at the call, and the reasoning
	// after it was dropped (prefix_mismatch).
	it("replays the arguments text the model streamed, byte for byte", async () => {
		const raw = '{"command": "ls -la" , "cwd":"/tmp/é" }';
		const sent = stubFetch((_request, index) =>
			index === 0
				? sse([
						{
							type: "response.output_item.added",
							output_index: 0,
							item: { type: "function_call", id: "fc_1", call_id: "call_1", name: "bash", arguments: "" },
						},
						{ type: "response.function_call_arguments.delta", output_index: 0, delta: raw.slice(0, 12) },
						{ type: "response.function_call_arguments.delta", output_index: 0, delta: raw.slice(12) },
						{ type: "response.function_call_arguments.done", output_index: 0, arguments: raw },
						{
							type: "response.output_item.done",
							output_index: 0,
							item: {
								type: "function_call",
								id: "fc_1",
								call_id: "call_1",
								name: "bash",
								arguments: raw,
								status: "completed",
							},
						},
						completed,
					])
				: textReply(),
		);
		const messages: Context["messages"] = [{ role: "user", content: "list", timestamp: 1 }];
		const reply = await streamOpenAICodexResponses(model, normalizeContext({ messages }), {
			apiKey: token,
			sessionId: "replay",
			transport: "sse",
		}).result();
		expect(reply.content).toEqual([
			{
				type: "toolCall",
				id: "call_1|fc_1",
				name: "bash",
				arguments: { command: "ls -la", cwd: "/tmp/é" },
				rawArguments: raw,
			},
		]);

		const toolResult: Message = {
			role: "toolResult",
			toolCallId: "call_1|fc_1",
			toolName: "bash",
			content: [{ type: "text", text: "a b" }],
			isError: false,
			timestamp: 3,
		};
		messages.push(reply, toolResult);
		await streamOpenAICodexResponses(model, normalizeContext({ messages }), {
			apiKey: token,
			sessionId: "replay",
			transport: "sse",
		}).result();
		const call = sent[1]?.body.input.find((item) => item.type === "function_call");
		expect(call?.arguments).toBe(raw);
	});

	it("keeps no copy when the text serializes back the same", async () => {
		const raw = '{"command":"ls"}';
		stubFetch(() =>
			sse([
				{
					type: "response.output_item.added",
					output_index: 0,
					item: { type: "function_call", id: "fc_1", call_id: "call_1", name: "bash", arguments: "" },
				},
				{ type: "response.function_call_arguments.done", output_index: 0, arguments: raw },
				{
					type: "response.output_item.done",
					output_index: 0,
					item: {
						type: "function_call",
						id: "fc_1",
						call_id: "call_1",
						name: "bash",
						arguments: raw,
						status: "completed",
					},
				},
				completed,
			]),
		);
		const reply = await streamOpenAICodexResponses(
			model,
			normalizeContext({ messages: [{ role: "user", content: "list", timestamp: 1 }] }),
			{ apiKey: token, transport: "sse" },
		).result();
		expect(reply.content).toEqual([
			{ type: "toolCall", id: "call_1|fc_1", name: "bash", arguments: { command: "ls" } },
		]);
	});
});
