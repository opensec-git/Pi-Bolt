// Regression tests for the outside review's claims A1-A3: SSE bodies cancelled on an early stop, Google delta options,
// Codex WebSocket bookkeeping freed with the session.
import { createServer, type ServerResponse } from "node:http";
import type { AddressInfo, Socket } from "node:net";
import { afterEach, describe, expect, it, vi } from "vitest";
import { stream as streamAnthropic } from "../src/api/anthropic-messages.ts";
import { streamSimple as streamGoogleGenerativeAI } from "../src/api/google-generative-ai.ts";
import { streamSimple as streamGoogleVertex } from "../src/api/google-vertex.ts";
import {
	closeOpenAICodexWebSocketSessions,
	getOpenAICodexWebSocketDebugStats,
	resetOpenAICodexWebSocketDebugStats,
	streamSimple as streamSimpleOpenAICodexResponses,
} from "../src/api/openai-codex-responses.ts";
import { stream as streamPiMessages } from "../src/api/pi-messages.ts";
import { cleanupSessionResources } from "../src/session-resources.ts";
import type { Api, FetchFunction, Model } from "../src/types.ts";
import { normalizeContext } from "../src/utils/transcript.ts";

const context = normalizeContext({ messages: [{ role: "user", content: "hello", timestamp: 1 }] });

function anthropicModel(baseUrl: string): Model<"anthropic-messages"> {
	return {
		id: "claude-test",
		name: "Claude Test",
		api: "anthropic-messages",
		provider: "test-anthropic",
		baseUrl,
		reasoning: false,
		input: ["text"],
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
		contextWindow: 200000,
		maxTokens: 1000,
	};
}

const messageStart = {
	type: "message_start",
	message: {
		id: "msg_1",
		type: "message",
		role: "assistant",
		model: "claude-test",
		content: [],
		stop_reason: null,
		stop_sequence: null,
		usage: { input_tokens: 1, output_tokens: 0 },
	},
};

/** A server that answers with `frames` and then keeps the response open; reports when the client closes the socket. */
async function openSseServer(frames: string) {
	const sockets: Socket[] = [];
	const closed = Promise.withResolvers<void>();
	const server = createServer((request, response: ServerResponse) => {
		request.resume();
		sockets.push(request.socket);
		request.socket.once("close", () => closed.resolve());
		response.writeHead(200, { "content-type": "text/event-stream" });
		response.write(frames);
	});
	await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
	return {
		baseUrl: `http://127.0.0.1:${(server.address() as AddressInfo).port}`,
		clientClosed: closed.promise,
		async close() {
			for (const socket of sockets) socket.destroy();
			await new Promise<void>((resolve) => server.close(() => resolve()));
		},
	};
}

const settledWithin = <T>(promise: Promise<T>, ms: number) =>
	Promise.race([promise.then(() => true), new Promise<boolean>((resolve) => setTimeout(() => resolve(false), ms))]);

describe("claim A1: iterateSseMessages does not cancel the body", () => {
	// claim A1 (reader part): an `error` event ends the iteration early. The reader is only released, the body is not
	// cancelled, so the HTTP response (and its connection) stays open after the request has already failed.
	it("closes the connection when the stream ends early on an error event", async () => {
		const server = await openSseServer(
			`event: message_start\ndata: ${JSON.stringify(messageStart)}\n\n` +
				`event: error\ndata: ${JSON.stringify({ type: "error", error: { type: "overloaded_error", message: "busy" } })}\n\n`,
		);
		try {
			const result = await streamAnthropic(anthropicModel(server.baseUrl), context, {
				apiKey: "test-key",
				cacheRetention: "none",
			}).result();
			expect(result.stopReason).toBe("error");
			expect(await settledWithin(server.clientClosed, 1000)).toBe(true);
		} finally {
			await server.close();
		}
	});

	// claim A1, the same reader in pi-messages: a stream that stops on unparsable data leaves the response open.
	it("closes the connection when a pi-messages stream stops on unparsable data", async () => {
		const server = await openSseServer("data: {not json\n\n");
		try {
			const model: Model<"pi-messages"> = {
				id: "pi-test",
				name: "Pi Test",
				api: "pi-messages",
				provider: "test-pi",
				baseUrl: server.baseUrl,
				reasoning: false,
				input: ["text"],
				cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
				contextWindow: 200000,
				maxTokens: 1000,
			};
			const result = await streamPiMessages(model, context, { apiKey: "test-key" }).result();
			expect(result.stopReason).toBe("error");
			expect(await settledWithin(server.clientClosed, 1000)).toBe(true);
		} finally {
			await server.close();
		}
	});

	// claim A1 (abort part), disproved: the request's signal also goes to fetch, so an abort ends a pending read.
	it("ends promptly when the signal aborts during a pending read", async () => {
		const server = await openSseServer(`event: message_start\ndata: ${JSON.stringify(messageStart)}\n\n`);
		try {
			const controller = new AbortController();
			const pending = streamAnthropic(anthropicModel(server.baseUrl), context, {
				apiKey: "test-key",
				cacheRetention: "none",
				signal: controller.signal,
			}).result();
			setTimeout(() => controller.abort(), 100);
			expect(await settledWithin(pending, 1000)).toBe(true);
			expect((await pending).stopReason).toBe("aborted");
			expect(await settledWithin(server.clientClosed, 1000)).toBe(true);
		} finally {
			await server.close();
		}
	});
});

function googleModel<TApi extends Api>(api: TApi): Model<TApi> {
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

const googleBody = [
	...["Hel", "lo ", "there"].map((text) => ({ candidates: [{ content: { role: "model", parts: [{ text }] } }] })),
	{
		candidates: [{ content: { role: "model", parts: [{ text: "!" }] }, finishReason: "STOP" }],
		usageMetadata: { promptTokenCount: 1, candidatesTokenCount: 4, totalTokenCount: 5 },
	},
]
	.map((chunk) => `data: ${JSON.stringify(chunk)}\n\n`)
	.join("");

const googleFetch: FetchFunction = async () =>
	new Response(googleBody, { status: 200, headers: { "content-type": "text/event-stream" } });

describe("claim A2: Google providers ignore the delta stream options", () => {
	// claim A2: anthropic, openai-*, bedrock, mistral and codex pass these options to their event stream; Google does not.
	it.each([
		["google-generative-ai", () => streamGoogleGenerativeAI],
		["google-vertex", () => streamGoogleVertex],
	] as const)("%s emits no text deltas when skipTextDeltas is set", async (api, getStream) => {
		const stream = getStream()(googleModel(api as never) as never, context, {
			apiKey: "test-key",
			fetch: googleFetch,
			skipTextDeltas: true,
		});
		let deltas = 0;
		for await (const event of stream) if (event.type === "text_delta") deltas++;
		const result = await stream.result();
		expect(result.stopReason).toBe("stop");
		expect(result.content).toEqual([expect.objectContaining({ type: "text", text: "Hello there!" })]);
		expect(deltas).toBe(0);
	});
});

class MockCodexWebSocket {
	private listeners = new Map<string, Set<(event: unknown) => void>>();

	constructor() {
		queueMicrotask(() => this.dispatch("open", {}));
	}

	addEventListener(type: string, listener: (event: unknown) => void): void {
		let listeners = this.listeners.get(type);
		if (!listeners) {
			listeners = new Set();
			this.listeners.set(type, listeners);
		}
		listeners.add(listener);
	}

	removeEventListener(type: string, listener: (event: unknown) => void): void {
		this.listeners.get(type)?.delete(listener);
	}

	send(): void {
		const events = [
			{
				type: "response.output_item.added",
				item: { type: "message", id: "msg_1", role: "assistant", status: "in_progress", content: [] },
			},
			{ type: "response.content_part.added", part: { type: "output_text", text: "" } },
			{ type: "response.output_text.delta", delta: "Hi" },
			{
				type: "response.output_item.done",
				item: {
					type: "message",
					id: "msg_1",
					role: "assistant",
					status: "completed",
					content: [{ type: "output_text", text: "Hi" }],
				},
			},
			{
				type: "response.done",
				response: {
					status: "completed",
					usage: {
						input_tokens: 5,
						output_tokens: 1,
						total_tokens: 6,
						input_tokens_details: { cached_tokens: 0 },
					},
				},
			},
		];
		queueMicrotask(() => {
			for (const event of events) this.dispatch("message", { data: JSON.stringify(event) });
		});
	}

	close(): void {}

	private dispatch(type: string, event: unknown): void {
		for (const listener of this.listeners.get(type) ?? []) listener(event);
	}
}

function codexToken(): string {
	const payload = Buffer.from(
		JSON.stringify({ "https://api.openai.com/auth": { chatgpt_account_id: "acc_test" } }),
		"utf8",
	).toString("base64");
	return `aaa.${payload}.bbb`;
}

describe("claim A3: Codex WebSocket bookkeeping outlives the session", () => {
	afterEach(() => {
		vi.unstubAllGlobals();
		closeOpenAICodexWebSocketSessions();
		resetOpenAICodexWebSocketDebugStats();
	});

	// claim A3: cleanupSessionResources() (AgentSession.dispose) closes the session's sockets but keeps its debug
	// stats entry, so one entry per ended session stays in the module-level map for the life of the process.
	it("forgets a session's stats when its resources are cleaned up", async () => {
		vi.stubGlobal(
			"fetch",
			vi.fn(async () => new Response("unexpected fetch", { status: 500 })),
		);
		vi.stubGlobal("WebSocket", MockCodexWebSocket);
		const model: Model<"openai-codex-responses"> = {
			id: "gpt-5.1-codex",
			name: "GPT-5.1 Codex",
			api: "openai-codex-responses",
			provider: "openai-codex",
			baseUrl: "https://chatgpt.com/backend-api",
			reasoning: true,
			input: ["text"],
			cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
			contextWindow: 400000,
			maxTokens: 128000,
		};
		const retained: string[] = [];
		for (let index = 0; index < 20; index++) {
			const sessionId = `ended-session-${index}`;
			const result = await streamSimpleOpenAICodexResponses(model, context, {
				apiKey: codexToken(),
				sessionId,
				transport: "websocket",
			}).result();
			expect(result.stopReason).toBe("stop");
			cleanupSessionResources(sessionId);
			if (getOpenAICodexWebSocketDebugStats(sessionId) !== undefined) retained.push(sessionId);
		}
		expect(retained).toEqual([]);
	});
});
