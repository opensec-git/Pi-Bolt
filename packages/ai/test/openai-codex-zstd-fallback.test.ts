import { zstdDecompressSync } from "node:zlib";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
	closeOpenAICodexWebSocketSessions,
	resetOpenAICodexWebSocketDebugStats,
	stream as streamOpenAICodexResponses,
} from "../src/api/openai-codex-responses.ts";
import { cleanupSessionResources } from "../src/session-resources.ts";
import type { Model } from "../src/types.ts";
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

describe("openai-codex: zstd request body rejected (#12060)", () => {
	const rejected = () =>
		new Response('{"detail":"Bad Request"}', { status: 400, headers: { "content-type": "application/json" } });

	// #12060: the backend sometimes answers a valid zstd body with 400 {"detail":"Bad Request"}. It was not retryable,
	// so the run ended.
	it("sends the body once more uncompressed and keeps the session uncompressed", async () => {
		const sent = stubFetch((request) => (request.compressed ? rejected() : textReply()));
		const context = normalizeContext({ messages: [{ role: "user", content: "hi", timestamp: 1 }] });
		const first = await streamOpenAICodexResponses(model, context, {
			apiKey: token,
			sessionId: "zstd-session",
			transport: "sse",
			maxRetries: 0,
		}).result();
		expect(first.stopReason).toBe("stop");
		expect(sent.map((request) => request.compressed)).toEqual([true, false]);
		expect(sent[1]?.body).toEqual(sent[0]?.body);

		const second = await streamOpenAICodexResponses(model, context, {
			apiKey: token,
			sessionId: "zstd-session",
			transport: "sse",
		}).result();
		expect(second.stopReason).toBe("stop");
		expect(sent.map((request) => request.compressed)).toEqual([true, false, false]);

		// Another session still compresses, and so does this one after it ended.
		await streamOpenAICodexResponses(model, context, {
			apiKey: token,
			sessionId: "other",
			transport: "sse",
		}).result();
		cleanupSessionResources("zstd-session");
		await streamOpenAICodexResponses(model, context, {
			apiKey: token,
			sessionId: "zstd-session",
			transport: "sse",
		}).result();
		expect(sent.slice(3).map((request) => request.compressed)).toEqual([true, false, true, false]);
	});

	it.each([
		'{"detail":"Bad Request","param":"input"}',
		'{"error":{"message":"Invalid value for input","type":"invalid_request_error"}}',
	])("does not resend another 400: %s", async (errorBody) => {
		const sent = stubFetch(() => new Response(errorBody, { status: 400 }));
		const result = await streamOpenAICodexResponses(
			model,
			normalizeContext({ messages: [{ role: "user", content: "hi", timestamp: 1 }] }),
			{ apiKey: token, sessionId: "plain-400", transport: "sse", maxRetries: 0 },
		).result();
		expect(result.stopReason).toBe("error");
		expect(sent.map((request) => request.compressed)).toEqual([true]);
	});

	it("fails as before when the uncompressed body is rejected too", async () => {
		const sent = stubFetch(() => rejected());
		const result = await streamOpenAICodexResponses(
			model,
			normalizeContext({ messages: [{ role: "user", content: "hi", timestamp: 1 }] }),
			{ apiKey: token, transport: "sse", maxRetries: 0 },
		).result();
		expect(result.stopReason).toBe("error");
		expect(sent.map((request) => request.compressed)).toEqual([true, false]);
	});
});
