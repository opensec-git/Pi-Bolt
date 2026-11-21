import { getEventListeners } from "node:events";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { constants as zlibConstants, zstdCompressSync, zstdDecompressSync } from "node:zlib";
import { Type } from "typebox";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
	closeOpenAICodexWebSocketSessions,
	getOpenAICodexWebSocketDebugStats,
	resetOpenAICodexWebSocketDebugStats,
	stream as streamOpenAICodexResponses,
	streamSimple as streamSimpleOpenAICodexResponses,
} from "../src/api/openai-codex-responses.ts";
import type { Api, AssistantMessage, Context, Message, Model, ToolResultMessage } from "../src/types.ts";
import { formatUserAgent } from "../src/utils/identity.ts";
import { requestCacheStats } from "../src/utils/request-cache.ts";
import { retryAssistantCall } from "../src/utils/retry.ts";
import { normalizeContext } from "../src/utils/transcript.ts";

const originalAgentDir = process.env.PI_CODING_AGENT_DIR;

afterEach(() => {
	vi.unstubAllGlobals();
	if (originalAgentDir === undefined) {
		delete process.env.PI_CODING_AGENT_DIR;
	} else {
		process.env.PI_CODING_AGENT_DIR = originalAgentDir;
	}
	closeOpenAICodexWebSocketSessions();
	resetOpenAICodexWebSocketDebugStats();
	vi.useRealTimers();
	vi.restoreAllMocks();
});

function mockToken(accountId = "acc_test"): string {
	const payload = Buffer.from(
		JSON.stringify({ "https://api.openai.com/auth": { chatgpt_account_id: accountId } }),
		"utf8",
	).toString("base64");
	return `aaa.${payload}.bbb`;
}

function decodeCodexRequestBody(body: RequestInit["body"] | undefined): Record<string, unknown> | null {
	if (typeof body === "string") {
		return JSON.parse(body) as Record<string, unknown>;
	}
	if (body instanceof Uint8Array) {
		return JSON.parse(Buffer.from(zstdDecompressSync(body)).toString("utf8")) as Record<string, unknown>;
	}
	return null;
}

function buildCodexModel(
	id: string,
	compat?: Model<"openai-codex-responses">["compat"],
	thinkingLevelMap?: Model<"openai-codex-responses">["thinkingLevelMap"],
): Model<"openai-codex-responses"> {
	return {
		id,
		name: id,
		api: "openai-codex-responses",
		provider: "openai-codex",
		baseUrl: "https://chatgpt.com/backend-api",
		reasoning: true,
		input: ["text"],
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
		contextWindow: 400000,
		maxTokens: 128000,
		...(compat ? { compat } : {}),
		...(thinkingLevelMap ? { thinkingLevelMap } : {}),
	};
}

/** Answers every SSE request with a short completed response and records the decoded request bodies. */
function captureSSERequestBodies(): Array<Record<string, unknown> | null> {
	const encoder = new TextEncoder();
	const requestBodies: Array<Record<string, unknown> | null> = [];
	vi.stubGlobal(
		"fetch",
		vi.fn(async (_input: string | URL, init?: RequestInit) => {
			requestBodies.push(decodeCodexRequestBody(init?.body));
			return new Response(
				new ReadableStream<Uint8Array>({
					start(controller) {
						controller.enqueue(encoder.encode(buildSSEPayload({ status: "completed" })));
						controller.close();
					},
				}),
				{ status: 200, headers: { "content-type": "text/event-stream" } },
			);
		}),
	);
	return requestBodies;
}

/** Input items as roles, with configuration_update items shown with their effort. */
function summarizeInput(body: Record<string, unknown> | null | undefined): string[] {
	const input = (body?.input ?? []) as Array<{ type?: string; role?: string; reasoning?: { effort?: string } }>;
	return input.map((item) =>
		item.type === "configuration_update"
			? `configuration_update:${item.reasoning?.effort}`
			: (item.role ?? `${item.type}`),
	);
}

function buildSSEPayload({
	status,
	includeDone = false,
	endTurn,
}: {
	status: "completed" | "incomplete";
	includeDone?: boolean;
	endTurn?: boolean;
}): string {
	const terminalType = status === "incomplete" ? "response.incomplete" : "response.completed";
	const events = [
		`data: ${JSON.stringify({
			type: "response.output_item.added",
			item: { type: "message", id: "msg_1", role: "assistant", status: "in_progress", content: [] },
		})}`,
		`data: ${JSON.stringify({ type: "response.content_part.added", part: { type: "output_text", text: "" } })}`,
		`data: ${JSON.stringify({ type: "response.output_text.delta", delta: "Hello" })}`,
		`data: ${JSON.stringify({
			type: "response.output_item.done",
			item: {
				type: "message",
				id: "msg_1",
				role: "assistant",
				status: "completed",
				content: [{ type: "output_text", text: "Hello" }],
			},
		})}`,
		`data: ${JSON.stringify({
			type: terminalType,
			response: {
				status,
				end_turn: endTurn,
				incomplete_details: status === "incomplete" ? { reason: "max_output_tokens" } : null,
				usage: {
					input_tokens: 5,
					output_tokens: 3,
					total_tokens: 8,
					input_tokens_details: { cached_tokens: 0 },
				},
			},
		})}`,
	];

	if (includeDone) {
		events.push("data: [DONE]");
	}

	return `${events.join("\n\n")}\n\n`;
}

describe("openai-codex streaming", () => {
	it("streams SSE responses and forwards raw provider events", async () => {
		const tempDir = mkdtempSync(join(tmpdir(), "pi-codex-stream-"));
		process.env.PI_CODING_AGENT_DIR = tempDir;

		const payload = Buffer.from(
			JSON.stringify({ "https://api.openai.com/auth": { chatgpt_account_id: "acc_test" } }),
			"utf8",
		).toString("base64");
		const token = `aaa.${payload}.bbb`;

		const sse = `${[
			`data: ${JSON.stringify({
				type: "response.output_item.added",
				item: { type: "message", id: "msg_1", role: "assistant", status: "in_progress", content: [] },
			})}`,
			`data: ${JSON.stringify({ type: "response.content_part.added", part: { type: "output_text", text: "" } })}`,
			`data: ${JSON.stringify({ type: "response.output_text.delta", delta: "Hello" })}`,
			`data: ${JSON.stringify({
				type: "response.output_item.done",
				item: {
					type: "message",
					id: "msg_1",
					role: "assistant",
					status: "completed",
					content: [{ type: "output_text", text: "Hello" }],
				},
			})}`,
			`data: ${JSON.stringify({
				type: "response.completed",
				response: {
					status: "completed",
					usage: {
						input_tokens: 5,
						output_tokens: 3,
						total_tokens: 8,
						input_tokens_details: { cached_tokens: 0 },
					},
				},
			})}`,
		].join("\n\n")}\n\n`;

		const encoder = new TextEncoder();
		const stream = new ReadableStream<Uint8Array>({
			start(controller) {
				controller.enqueue(encoder.encode(sse));
				controller.close();
			},
		});

		const fetchMock = vi.fn(async (input: string | URL, init?: RequestInit) => {
			const url = typeof input === "string" ? input : input.toString();
			if (url === "https://api.github.com/repos/openai/codex/releases/latest") {
				return new Response(JSON.stringify({ tag_name: "rust-v0.0.0" }), { status: 200 });
			}
			if (url.startsWith("https://raw.githubusercontent.com/openai/codex/")) {
				return new Response("PROMPT", { status: 200, headers: { etag: '"etag"' } });
			}
			if (url === "https://chatgpt.com/backend-api/codex/responses") {
				const headers = init?.headers instanceof Headers ? init.headers : undefined;
				expect(headers?.get("Authorization")).toBe(`Bearer ${token}`);
				expect(headers?.get("chatgpt-account-id")).toBe("acc_test");
				expect(headers?.get("OpenAI-Beta")).toBe("responses=experimental");
				expect(headers?.get("originator")).toBe("pi");
				expect(headers?.get("User-Agent")).toBe(formatUserAgent());
				expect(headers?.get("accept")).toBe("text/event-stream");
				expect(headers?.has("x-api-key")).toBe(false);
				return new Response(stream, {
					status: 200,
					headers: { "content-type": "text/event-stream" },
				});
			}
			return new Response("not found", { status: 404 });
		});

		vi.stubGlobal("fetch", fetchMock);

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

		const context = normalizeContext({
			systemPrompt: "You are a helpful assistant.",
			messages: [{ role: "user", content: "Say hello", timestamp: Date.now() }],
		});

		const providerEvents: unknown[] = [];
		const providerEventModels: Model<Api>[] = [];
		const streamResult = streamOpenAICodexResponses(model, context, {
			apiKey: token,
			transport: "sse",
			onProviderStreamEvent: (event, eventModel) => {
				providerEvents.push(event);
				providerEventModels.push(eventModel);
			},
		});
		let sawTextDelta = false;
		let sawDone = false;

		for await (const event of streamResult) {
			if (event.type === "text_delta") {
				sawTextDelta = true;
			}
			if (event.type === "done") {
				sawDone = true;
				expect(event.message.content.find((c) => c.type === "text")?.text).toBe("Hello");
			}
		}

		expect(sawTextDelta).toBe(true);
		expect(sawDone).toBe(true);
		expect(providerEvents.map((event) => (event as { type?: string }).type)).toEqual([
			"response.output_item.added",
			"response.content_part.added",
			"response.output_text.delta",
			"response.output_item.done",
			"response.completed",
		]);
		expect(providerEventModels).toEqual([model, model, model, model, model]);
	});

	// Regression test for https://github.com/earendil-works/pi/issues/9047
	it("processes a terminal SSE event without a trailing blank line", async () => {
		const token = mockToken();
		const sse = buildSSEPayload({ status: "completed" }).trimEnd();
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
		const context = normalizeContext({
			systemPrompt: "You are a helpful assistant.",
			messages: [{ role: "user", content: "Say hello", timestamp: Date.now() }],
		});
		const resultStream = streamOpenAICodexResponses(model, context, {
			apiKey: token,
			transport: "sse",
			fetch: async () => new Response(sse, { status: 200, headers: { "content-type": "text/event-stream" } }),
		});
		const result = await resultStream.result();

		expect(result.stopReason).toBe("stop");
		expect(result.content.find((content) => content.type === "text")?.text).toBe("Hello");
	});

	it("completes after response.completed even when the SSE body stays open", async () => {
		const tempDir = mkdtempSync(join(tmpdir(), "pi-codex-stream-"));
		process.env.PI_CODING_AGENT_DIR = tempDir;
		const token = mockToken();
		const encoder = new TextEncoder();
		const sse = buildSSEPayload({ status: "completed", includeDone: true, endTurn: false });

		const stream = new ReadableStream<Uint8Array>({
			start(controller) {
				controller.enqueue(encoder.encode(sse));
			},
		});

		const fetchMock = vi.fn(async (input: string | URL) => {
			const url = typeof input === "string" ? input : input.toString();
			if (url === "https://api.github.com/repos/openai/codex/releases/latest") {
				return new Response(JSON.stringify({ tag_name: "rust-v0.0.0" }), { status: 200 });
			}
			if (url.startsWith("https://raw.githubusercontent.com/openai/codex/")) {
				return new Response("PROMPT", { status: 200, headers: { etag: '"etag"' } });
			}
			if (url === "https://chatgpt.com/backend-api/codex/responses") {
				return new Response(stream, {
					status: 200,
					headers: { "content-type": "text/event-stream" },
				});
			}
			return new Response("not found", { status: 404 });
		});
		vi.stubGlobal("fetch", fetchMock);

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

		const context = normalizeContext({
			systemPrompt: "You are a helpful assistant.",
			messages: [{ role: "user", content: "Say hello", timestamp: Date.now() }],
		});

		const result = await Promise.race([
			streamOpenAICodexResponses(model, context, { apiKey: token, transport: "sse" }).result(),
			new Promise<never>((_, reject) => {
				setTimeout(() => reject(new Error("Timed out waiting for completed SSE stream")), 1000);
			}),
		]);

		expect(result.content.find((c) => c.type === "text")?.text).toBe("Hello");
		expect(result.stopReason).toBe("stop");
		expect(result.endTurn).toBe(false);
	});

	// Regression test for #11708
	it("removes SSE retry backoff abort listeners after the retry succeeds", async () => {
		const tempDir = mkdtempSync(join(tmpdir(), "pi-codex-stream-"));
		process.env.PI_CODING_AGENT_DIR = tempDir;
		const token = mockToken();
		let calls = 0;
		const fetchMock = vi.fn(async (input: string | URL) => {
			const url = typeof input === "string" ? input : input.toString();
			if (url !== "https://chatgpt.com/backend-api/codex/responses") {
				return new Response("not found", { status: 404 });
			}
			calls++;
			if (calls <= 3) {
				return new Response(JSON.stringify({ error: { message: "rate limited" } }), {
					status: 429,
					headers: { "retry-after": "0" },
				});
			}
			return new Response(buildSSEPayload({ status: "completed" }), {
				status: 200,
				headers: { "content-type": "text/event-stream" },
			});
		});
		vi.stubGlobal("fetch", fetchMock);

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
		const context = normalizeContext({
			systemPrompt: "You are a helpful assistant.",
			messages: [{ role: "user", content: "Say hello", timestamp: Date.now() }],
		});

		const controller = new AbortController();
		const result = await streamOpenAICodexResponses(model, context, {
			apiKey: token,
			transport: "sse",
			maxRetries: 3,
			signal: controller.signal,
		}).result();

		expect(result.stopReason).toBe("stop");
		expect(calls).toBe(4);
		expect(getEventListeners(controller.signal, "abort")).toHaveLength(0);
	});

	it("maps response.incomplete to stopReason length even when the SSE body stays open", async () => {
		const tempDir = mkdtempSync(join(tmpdir(), "pi-codex-stream-"));
		process.env.PI_CODING_AGENT_DIR = tempDir;
		const token = mockToken();
		const encoder = new TextEncoder();
		const sse = buildSSEPayload({ status: "incomplete" });

		const stream = new ReadableStream<Uint8Array>({
			start(controller) {
				controller.enqueue(encoder.encode(sse));
			},
		});

		const fetchMock = vi.fn(async (input: string | URL) => {
			const url = typeof input === "string" ? input : input.toString();
			if (url === "https://api.github.com/repos/openai/codex/releases/latest") {
				return new Response(JSON.stringify({ tag_name: "rust-v0.0.0" }), { status: 200 });
			}
			if (url.startsWith("https://raw.githubusercontent.com/openai/codex/")) {
				return new Response("PROMPT", { status: 200, headers: { etag: '"etag"' } });
			}
			if (url === "https://chatgpt.com/backend-api/codex/responses") {
				return new Response(stream, {
					status: 200,
					headers: { "content-type": "text/event-stream" },
				});
			}
			return new Response("not found", { status: 404 });
		});
		vi.stubGlobal("fetch", fetchMock);

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

		const context = normalizeContext({
			systemPrompt: "You are a helpful assistant.",
			messages: [{ role: "user", content: "Say hello", timestamp: Date.now() }],
		});

		const result = await Promise.race([
			streamOpenAICodexResponses(model, context, { apiKey: token, transport: "sse" }).result(),
			new Promise<never>((_, reject) => {
				setTimeout(() => reject(new Error("Timed out waiting for incomplete SSE stream")), 1000);
			}),
		]);

		expect(result.content.find((c) => c.type === "text")?.text).toBe("Hello");
		expect(result.stopReason).toBe("length");
	});

	it("aborts SSE fetch after the configured HTTP timeout when response headers do not arrive", async () => {
		const token = mockToken();

		const fetchMock = vi.fn((input: string | URL, init?: RequestInit) => {
			const url = typeof input === "string" ? input : input.toString();
			if (url !== "https://chatgpt.com/backend-api/codex/responses") {
				throw new Error(`Unexpected URL: ${url}`);
			}

			const signal = init?.signal;
			if (!signal) {
				throw new Error("Expected SSE fetch to receive an abort signal");
			}

			return new Promise<Response>((_, reject) => {
				const onAbort = () => {
					const reason = signal.reason;
					reject(reason instanceof Error ? reason : new Error("SSE fetch aborted"));
				};
				if (signal.aborted) {
					onAbort();
					return;
				}
				signal.addEventListener("abort", onAbort, { once: true });
			});
		});
		vi.stubGlobal("fetch", fetchMock);

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
		const context = normalizeContext({
			systemPrompt: "You are a helpful assistant.",
			messages: [{ role: "user", content: "Say hello", timestamp: Date.now() }],
		});

		const result = await streamOpenAICodexResponses(model, context, {
			apiKey: token,
			transport: "sse",
			timeoutMs: 10,
		}).result();

		expect(fetchMock).toHaveBeenCalledTimes(1);
		expect(result.stopReason).toBe("error");
		expect(result.errorMessage).toBe("Codex SSE response headers timed out after 10ms");
	});

	it("aborts SSE body reads after response headers arrive", async () => {
		const token = mockToken();
		const encoder = new TextEncoder();
		const timers: ReturnType<typeof setTimeout>[] = [];
		let cancelled = false;
		const stream = new ReadableStream<Uint8Array>({
			start(controller) {
				const enqueue = (chunk: string) => {
					if (!cancelled) controller.enqueue(encoder.encode(chunk));
				};
				enqueue(
					`${[
						`data: ${JSON.stringify({
							type: "response.output_item.added",
							item: { type: "message", id: "msg_1", role: "assistant", status: "in_progress", content: [] },
						})}`,
						`data: ${JSON.stringify({ type: "response.content_part.added", part: { type: "output_text", text: "" } })}`,
						`data: ${JSON.stringify({ type: "response.output_text.delta", delta: "one" })}`,
					].join("\n\n")}\n\n`,
				);
				timers.push(
					setTimeout(() => {
						enqueue(`data: ${JSON.stringify({ type: "response.output_text.delta", delta: "two" })}\n\n`);
					}, 10),
				);
				timers.push(
					setTimeout(() => {
						if (cancelled) return;
						enqueue(
							`${[
								`data: ${JSON.stringify({
									type: "response.output_item.done",
									item: {
										type: "message",
										id: "msg_1",
										role: "assistant",
										status: "completed",
										content: [{ type: "output_text", text: "onetwo" }],
									},
								})}`,
								`data: ${JSON.stringify({
									type: "response.completed",
									response: {
										status: "completed",
										usage: {
											input_tokens: 5,
											output_tokens: 3,
											total_tokens: 8,
											input_tokens_details: { cached_tokens: 0 },
										},
									},
								})}`,
							].join("\n\n")}\n\n`,
						);
						controller.close();
					}, 20),
				);
			},
			cancel() {
				cancelled = true;
				for (const timer of timers) clearTimeout(timer);
			},
		});

		vi.stubGlobal(
			"fetch",
			vi.fn(async () => new Response(stream, { status: 200, headers: { "content-type": "text/event-stream" } })),
		);

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
		const context = normalizeContext({
			systemPrompt: "You are a helpful assistant.",
			messages: [{ role: "user", content: "Say hello", timestamp: Date.now() }],
		});
		const controller = new AbortController();
		const events: string[] = [];

		const resultStream = streamOpenAICodexResponses(model, context, {
			apiKey: token,
			transport: "sse",
			signal: controller.signal,
		});
		for await (const event of resultStream) {
			events.push(event.type === "text_delta" ? `text_delta:${event.delta}` : event.type);
			if (event.type === "text_delta" && event.delta === "one") {
				controller.abort();
			}
		}

		const result = await resultStream.result();
		expect(result.stopReason).toBe("aborted");
		expect(result.errorMessage).toBe("Request was aborted");
		expect(events).toContain("text_delta:one");
		expect(events).not.toContain("text_delta:two");
		expect(cancelled).toBe(true);
	});

	it("sets session-id/x-client-request-id headers and prompt_cache_key when sessionId is provided", async () => {
		const tempDir = mkdtempSync(join(tmpdir(), "pi-codex-stream-"));
		process.env.PI_CODING_AGENT_DIR = tempDir;

		const payload = Buffer.from(
			JSON.stringify({ "https://api.openai.com/auth": { chatgpt_account_id: "acc_test" } }),
			"utf8",
		).toString("base64");
		const token = `aaa.${payload}.bbb`;

		const sse = `${[
			`data: ${JSON.stringify({
				type: "response.output_item.added",
				item: { type: "message", id: "msg_1", role: "assistant", status: "in_progress", content: [] },
			})}`,
			`data: ${JSON.stringify({ type: "response.content_part.added", part: { type: "output_text", text: "" } })}`,
			`data: ${JSON.stringify({ type: "response.output_text.delta", delta: "Hello" })}`,
			`data: ${JSON.stringify({
				type: "response.output_item.done",
				item: {
					type: "message",
					id: "msg_1",
					role: "assistant",
					status: "completed",
					content: [{ type: "output_text", text: "Hello" }],
				},
			})}`,
			`data: ${JSON.stringify({
				type: "response.completed",
				response: {
					status: "completed",
					usage: {
						input_tokens: 5,
						output_tokens: 3,
						total_tokens: 8,
						input_tokens_details: { cached_tokens: 0 },
					},
				},
			})}`,
		].join("\n\n")}\n\n`;

		const encoder = new TextEncoder();
		const stream = new ReadableStream<Uint8Array>({
			start(controller) {
				controller.enqueue(encoder.encode(sse));
				controller.close();
			},
		});

		const sessionId = "test-session-123";
		const fetchMock = vi.fn(async (input: string | URL, init?: RequestInit) => {
			const url = typeof input === "string" ? input : input.toString();
			if (url === "https://api.github.com/repos/openai/codex/releases/latest") {
				return new Response(JSON.stringify({ tag_name: "rust-v0.0.0" }), { status: 200 });
			}
			if (url.startsWith("https://raw.githubusercontent.com/openai/codex/")) {
				return new Response("PROMPT", { status: 200, headers: { etag: '"etag"' } });
			}
			if (url === "https://chatgpt.com/backend-api/codex/responses") {
				const headers = init?.headers instanceof Headers ? init.headers : undefined;
				// Verify sessionId is set in headers
				expect(headers?.get("session-id")).toBe(sessionId);
				expect(headers?.has("session_id")).toBe(false);
				expect(headers?.get("x-client-request-id")).toBe(sessionId);

				// Verify sessionId is set in request body as prompt_cache_key
				const body = decodeCodexRequestBody(init?.body);
				expect(body?.prompt_cache_key).toBe(sessionId);

				return new Response(stream, {
					status: 200,
					headers: { "content-type": "text/event-stream" },
				});
			}
			return new Response("not found", { status: 404 });
		});

		vi.stubGlobal("fetch", fetchMock);

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

		const context = normalizeContext({
			systemPrompt: "You are a helpful assistant.",
			messages: [{ role: "user", content: "Say hello", timestamp: Date.now() }],
		});

		const streamResult = streamOpenAICodexResponses(model, context, {
			apiKey: token,
			sessionId,
			transport: "sse",
		});
		await streamResult.result();
	});

	it("omits SSE cache affinity when cacheRetention is none", async () => {
		const token = mockToken();
		const encoder = new TextEncoder();
		let capturedHeaders: Headers | undefined;
		let capturedBody: Record<string, unknown> | null = null;
		vi.stubGlobal(
			"fetch",
			vi.fn(async (_input: string | URL, init?: RequestInit) => {
				capturedHeaders = init?.headers instanceof Headers ? init.headers : undefined;
				capturedBody = decodeCodexRequestBody(init?.body);
				return new Response(
					new ReadableStream<Uint8Array>({
						start(controller) {
							controller.enqueue(encoder.encode(buildSSEPayload({ status: "completed" })));
							controller.close();
						},
					}),
					{ status: 200, headers: { "content-type": "text/event-stream" } },
				);
			}),
		);

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
		const context = normalizeContext({
			systemPrompt: "You are a helpful assistant.",
			messages: [{ role: "user", content: "Say hello", timestamp: Date.now() }],
		});

		await streamOpenAICodexResponses(model, context, {
			apiKey: token,
			cacheRetention: "none",
			sessionId: "one-off-summary",
			transport: "sse",
		}).result();

		expect(capturedHeaders?.has("session-id")).toBe(false);
		expect(capturedHeaders?.has("x-client-request-id")).toBe(false);
		expect(capturedBody).not.toHaveProperty("prompt_cache_key");
	});

	it("clamps prompt_cache_key to OpenAI's 64-character limit", async () => {
		const token = mockToken();
		const sessionId = "x".repeat(67);
		let capturedPayload: { prompt_cache_key?: string } | undefined;
		const encoder = new TextEncoder();
		vi.stubGlobal(
			"fetch",
			vi.fn(
				async () =>
					new Response(
						new ReadableStream<Uint8Array>({
							start(controller) {
								controller.enqueue(encoder.encode(buildSSEPayload({ status: "completed" })));
								controller.close();
							},
						}),
						{ status: 200, headers: { "content-type": "text/event-stream" } },
					),
			),
		);

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
		const context = normalizeContext({
			systemPrompt: "You are a helpful assistant.",
			messages: [{ role: "user", content: "Say hello", timestamp: Date.now() }],
		});

		await streamOpenAICodexResponses(model, context, {
			apiKey: token,
			transport: "sse",
			sessionId,
			onPayload: (payload) => {
				capturedPayload = payload as { prompt_cache_key?: string };
			},
		}).result();

		expect(capturedPayload?.prompt_cache_key).toBe("x".repeat(64));
	});

	it("clamps Codex session-id header to 64 characters", async () => {
		const token = mockToken();
		const sessionId = "x".repeat(67);
		let capturedHeaders: Headers | undefined;
		const encoder = new TextEncoder();
		vi.stubGlobal(
			"fetch",
			vi.fn(async (_input: string | URL, init?: RequestInit) => {
				capturedHeaders = init?.headers instanceof Headers ? init.headers : undefined;
				return new Response(
					new ReadableStream<Uint8Array>({
						start(controller) {
							controller.enqueue(encoder.encode(buildSSEPayload({ status: "completed" })));
							controller.close();
						},
					}),
					{ status: 200, headers: { "content-type": "text/event-stream" } },
				);
			}),
		);

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
		const context = normalizeContext({
			systemPrompt: "You are a helpful assistant.",
			messages: [{ role: "user", content: "Say hello", timestamp: Date.now() }],
		});

		await streamOpenAICodexResponses(model, context, {
			apiKey: token,
			transport: "sse",
			sessionId,
		}).result();

		expect(capturedHeaders?.get("session-id")).toBe("x".repeat(64));
		expect(capturedHeaders?.get("x-client-request-id")).toBe("x".repeat(64));
	});

	it("lets model and caller headers override originator and User-Agent", async () => {
		const token = mockToken();
		let capturedHeaders: Headers | undefined;
		const encoder = new TextEncoder();
		vi.stubGlobal(
			"fetch",
			vi.fn(async (_input: string | URL, init?: RequestInit) => {
				capturedHeaders = init?.headers instanceof Headers ? init.headers : undefined;
				return new Response(
					new ReadableStream<Uint8Array>({
						start(controller) {
							controller.enqueue(encoder.encode(buildSSEPayload({ status: "completed" })));
							controller.close();
						},
					}),
					{ status: 200, headers: { "content-type": "text/event-stream" } },
				);
			}),
		);

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
			headers: { originator: "my-app" },
		};
		const context = normalizeContext({
			systemPrompt: "You are a helpful assistant.",
			messages: [{ role: "user", content: "Say hello", timestamp: Date.now() }],
		});

		await streamOpenAICodexResponses(model, context, {
			apiKey: token,
			transport: "sse",
			headers: { "user-agent": "my-app/1.0", Authorization: "Bearer ignored" },
		}).result();

		expect(capturedHeaders?.get("originator")).toBe("my-app");
		expect(capturedHeaders?.get("User-Agent")).toBe("my-app/1.0");
		expect(capturedHeaders?.get("Authorization")).toBe(`Bearer ${token}`);
	});

	it("preserves gpt-5.5 xhigh reasoning effort from simple options", async () => {
		const tempDir = mkdtempSync(join(tmpdir(), "pi-codex-stream-"));
		process.env.PI_CODING_AGENT_DIR = tempDir;
		const token = mockToken();
		const sse = buildSSEPayload({ status: "completed" });
		const encoder = new TextEncoder();
		const stream = new ReadableStream<Uint8Array>({
			start(controller) {
				controller.enqueue(encoder.encode(sse));
				controller.close();
			},
		});
		let requestedReasoning: unknown;

		const fetchMock = vi.fn(async (input: string | URL, init?: RequestInit) => {
			const url = typeof input === "string" ? input : input.toString();
			if (url === "https://api.github.com/repos/openai/codex/releases/latest") {
				return new Response(JSON.stringify({ tag_name: "rust-v0.0.0" }), { status: 200 });
			}
			if (url.startsWith("https://raw.githubusercontent.com/openai/codex/")) {
				return new Response("PROMPT", { status: 200, headers: { etag: '"etag"' } });
			}
			if (url === "https://chatgpt.com/backend-api/codex/responses") {
				const body = decodeCodexRequestBody(init?.body);
				requestedReasoning = body?.reasoning;
				return new Response(stream, {
					status: 200,
					headers: { "content-type": "text/event-stream" },
				});
			}
			return new Response("not found", { status: 404 });
		});
		vi.stubGlobal("fetch", fetchMock);

		const model: Model<"openai-codex-responses"> = {
			id: "gpt-5.5",
			name: "GPT-5.5",
			api: "openai-codex-responses",
			provider: "openai-codex",
			baseUrl: "https://chatgpt.com/backend-api",
			reasoning: true,
			thinkingLevelMap: { xhigh: "xhigh" },
			input: ["text"],
			cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
			contextWindow: 400000,
			maxTokens: 128000,
		};
		const context = normalizeContext({
			systemPrompt: "You are a helpful assistant.",
			messages: [{ role: "user", content: "Say hello", timestamp: Date.now() }],
		});

		await streamSimpleOpenAICodexResponses(model, context, {
			apiKey: token,
			reasoning: "xhigh",
			transport: "sse",
		}).result();

		expect(requestedReasoning).toEqual({ effort: "xhigh", summary: "auto" });
	});

	it("forwards required tool choice", async () => {
		const token = mockToken();
		const encoder = new TextEncoder();
		const sse = buildSSEPayload({ status: "completed" });
		let requestedToolChoice: unknown;

		vi.stubGlobal(
			"fetch",
			vi.fn(async (_input: string | URL, init?: RequestInit) => {
				requestedToolChoice = decodeCodexRequestBody(init?.body)?.tool_choice;
				return new Response(
					new ReadableStream<Uint8Array>({
						start(controller) {
							controller.enqueue(encoder.encode(sse));
							controller.close();
						},
					}),
					{ status: 200, headers: { "content-type": "text/event-stream" } },
				);
			}),
		);

		const model: Model<"openai-codex-responses"> = {
			id: "gpt-5.5",
			name: "GPT-5.5",
			api: "openai-codex-responses",
			provider: "openai-codex",
			baseUrl: "https://chatgpt.com/backend-api",
			reasoning: true,
			input: ["text"],
			cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
			contextWindow: 400000,
			maxTokens: 128000,
		};

		await streamOpenAICodexResponses(
			model,
			normalizeContext({
				messages: [
					{ role: "user", content: "Do not call ping. Respond with text instead.", timestamp: Date.now() },
				],
				tools: [
					{
						name: "ping",
						description: "Ping",
						parameters: Type.Object({ value: Type.String() }),
					},
				],
			}),
			{ apiKey: token, transport: "sse", toolChoice: "required" },
		).result();

		expect(requestedToolChoice).toBe("required");
	});

	// Regression test for https://github.com/earendil-works/pi/issues/12273
	it("sends the system prompt as a leading developer item instead of instructions", async () => {
		const token = mockToken();
		const requestBodies = captureSSERequestBodies();
		const model = buildCodexModel("gpt-6.2-sol");
		const messages = [{ role: "user" as const, content: "Say hello", timestamp: 1 }];

		await streamOpenAICodexResponses(model, normalizeContext({ systemPrompt: "Be terse.", messages }), {
			apiKey: token,
			transport: "sse",
		}).result();
		await streamOpenAICodexResponses(model, normalizeContext({ messages }), {
			apiKey: token,
			transport: "sse",
		}).result();

		const userItem = { role: "user", content: [{ type: "input_text", text: "Say hello" }] };
		expect(requestBodies[0]).not.toHaveProperty("instructions");
		expect(requestBodies[0]?.input).toEqual([{ role: "developer", content: "Be terse." }, userItem]);
		expect(requestBodies[1]).not.toHaveProperty("instructions");
		expect(requestBodies[1]?.input).toEqual([userItem]);
	});

	// Regression test for https://github.com/earendil-works/pi/issues/12269
	it("sends thinking level changes as configuration_update items with a fixed request effort", async () => {
		const token = mockToken();
		const requestBodies = captureSSERequestBodies();
		const model = buildCodexModel("gpt-6.2-sol", { supportsMidConvoEffort: true });
		const firstMessages: Context["messages"] = [{ role: "user", content: "Say hello", timestamp: 1 }];

		const first = await streamOpenAICodexResponses(model, normalizeContext({ messages: firstMessages }), {
			apiKey: token,
			transport: "sse",
			reasoningEffort: "low",
		}).result();
		const second = await streamOpenAICodexResponses(
			model,
			normalizeContext({ messages: [...firstMessages, first, { role: "user", content: "Again", timestamp: 2 }] }),
			{ apiKey: token, transport: "sse", reasoningEffort: "high" },
		).result();

		expect(first.providerThinkingLevel).toBe("low");
		expect(second.providerThinkingLevel).toBe("high");
		expect(requestBodies.map((body) => body?.reasoning)).toEqual([
			{ effort: "medium", summary: "auto" },
			{ effort: "medium", summary: "auto" },
		]);
		expect(requestBodies.map((body) => summarizeInput(body))).toEqual([
			["user", "configuration_update:low"],
			["user", "configuration_update:low", "assistant", "user", "configuration_update:high"],
		]);
	});

	it("sends the off level, an omitted reasoning option, as a configuration_update item", async () => {
		const token = mockToken();
		const requestBodies = captureSSERequestBodies();
		const model = buildCodexModel("gpt-6-sol", { supportsMidConvoEffort: true }, { off: "none" });

		const result = await streamSimpleOpenAICodexResponses(
			model,
			normalizeContext({ messages: [{ role: "user", content: "Say hello", timestamp: 1 }] }),
			{ apiKey: token, transport: "sse" },
		).result();

		expect(result.providerThinkingLevel).toBe("none");
		expect(requestBodies[0]?.reasoning).toEqual({ effort: "medium", summary: "auto" });
		expect(summarizeInput(requestBodies[0])).toEqual(["user", "configuration_update:none"]);
	});

	it("sends thinking levels as reasoning.effort without supportsMidConvoEffort", async () => {
		const token = mockToken();
		const requestBodies = captureSSERequestBodies();
		const model = buildCodexModel("gpt-5.6-sol");

		const result = await streamOpenAICodexResponses(
			model,
			normalizeContext({ messages: [{ role: "user", content: "Say hello", timestamp: 1 }] }),
			{ apiKey: token, transport: "sse", reasoningEffort: "high" },
		).result();

		expect(result.providerThinkingLevel).toBeUndefined();
		expect(requestBodies[0]?.reasoning).toEqual({ effort: "high", summary: "auto" });
		expect(summarizeInput(requestBodies[0])).toEqual(["user"]);
	});

	it("sends maxTokens as max_output_tokens unless the model opts out", async () => {
		const token = mockToken();
		const requestBodies = captureSSERequestBodies();
		const context = normalizeContext({ messages: [{ role: "user", content: "Say hello", timestamp: 1 }] });

		await streamSimpleOpenAICodexResponses(buildCodexModel("gpt-6.2-sol"), context, {
			apiKey: token,
			transport: "sse",
			maxTokens: 5000,
		}).result();
		await streamOpenAICodexResponses(buildCodexModel("gpt-6.2-sol"), context, {
			apiKey: token,
			transport: "sse",
			maxTokens: 4,
		}).result();
		await streamOpenAICodexResponses(buildCodexModel("gpt-6.2-sol", { supportsMaxOutputTokens: false }), context, {
			apiKey: token,
			transport: "sse",
			maxTokens: 5000,
		}).result();

		expect(requestBodies[0]?.max_output_tokens).toBe(5000);
		expect(requestBodies[1]?.max_output_tokens).toBe(16);
		expect(requestBodies[2]).not.toHaveProperty("max_output_tokens");
	});

	it("sets Codex strict mode explicitly and honors constrained sampling", async () => {
		const token = mockToken();
		const encoder = new TextEncoder();
		const sse = buildSSEPayload({ status: "completed" });
		let requestedTools: Array<{ type?: string; name?: string; strict?: boolean | null }> | undefined;

		vi.stubGlobal(
			"fetch",
			vi.fn(
				async () =>
					new Response(
						new ReadableStream<Uint8Array>({
							start(controller) {
								controller.enqueue(encoder.encode(sse));
								controller.close();
							},
						}),
						{ status: 200, headers: { "content-type": "text/event-stream" } },
					),
			),
		);

		const model: Model<"openai-codex-responses"> = {
			id: "gpt-5.5",
			name: "GPT-5.5",
			api: "openai-codex-responses",
			provider: "openai-codex",
			baseUrl: "https://chatgpt.com/backend-api",
			reasoning: true,
			input: ["text"],
			cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
			contextWindow: 400000,
			maxTokens: 128000,
		};

		await streamOpenAICodexResponses(
			model,
			normalizeContext({
				messages: [{ role: "user", content: "Use a tool", timestamp: Date.now() }],
				tools: [
					{
						name: "optional",
						description: "Optional constrained sampling",
						parameters: Type.Object({ value: Type.String() }),
						constrainedSampling: false,
					},
					{
						name: "strict",
						description: "Strict constrained sampling",
						parameters: Type.Object({ value: Type.String() }, { additionalProperties: false }),
						constrainedSampling: { type: "json_schema", strict: "prefer" },
					},
				],
			}),
			{
				apiKey: token,
				transport: "sse",
				onPayload: (payload) => {
					requestedTools = (payload as { tools?: typeof requestedTools }).tools;
				},
			},
		).result();

		expect(requestedTools).toMatchObject([
			{ type: "function", name: "optional", strict: null },
			{ type: "function", name: "strict", strict: true },
		]);
	});

	it.each(["gpt-5.3-codex", "gpt-5.4", "gpt-5.5"])("clamps %s minimal reasoning effort to low", async (modelId) => {
		const tempDir = mkdtempSync(join(tmpdir(), "pi-codex-stream-"));
		process.env.PI_CODING_AGENT_DIR = tempDir;

		const payload = Buffer.from(
			JSON.stringify({ "https://api.openai.com/auth": { chatgpt_account_id: "acc_test" } }),
			"utf8",
		).toString("base64");
		const token = `aaa.${payload}.bbb`;

		const sse = `${[
			`data: ${JSON.stringify({
				type: "response.output_item.added",
				item: { type: "message", id: "msg_1", role: "assistant", status: "in_progress", content: [] },
			})}`,
			`data: ${JSON.stringify({ type: "response.content_part.added", part: { type: "output_text", text: "" } })}`,
			`data: ${JSON.stringify({ type: "response.output_text.delta", delta: "Hello" })}`,
			`data: ${JSON.stringify({
				type: "response.output_item.done",
				item: {
					type: "message",
					id: "msg_1",
					role: "assistant",
					status: "completed",
					content: [{ type: "output_text", text: "Hello" }],
				},
			})}`,
			`data: ${JSON.stringify({
				type: "response.completed",
				response: {
					status: "completed",
					usage: {
						input_tokens: 5,
						output_tokens: 3,
						total_tokens: 8,
						input_tokens_details: { cached_tokens: 0 },
					},
				},
			})}`,
		].join("\n\n")}\n\n`;

		let requestedReasoning: unknown;
		const encoder = new TextEncoder();
		const stream = new ReadableStream<Uint8Array>({
			start(controller) {
				controller.enqueue(encoder.encode(sse));
				controller.close();
			},
		});

		const fetchMock = vi.fn(async (input: string | URL, init?: RequestInit) => {
			const url = typeof input === "string" ? input : input.toString();
			if (url === "https://api.github.com/repos/openai/codex/releases/latest") {
				return new Response(JSON.stringify({ tag_name: "rust-v0.0.0" }), { status: 200 });
			}
			if (url.startsWith("https://raw.githubusercontent.com/openai/codex/")) {
				return new Response("PROMPT", { status: 200, headers: { etag: '"etag"' } });
			}
			if (url === "https://chatgpt.com/backend-api/codex/responses") {
				const body = decodeCodexRequestBody(init?.body);
				requestedReasoning = body?.reasoning;

				return new Response(stream, {
					status: 200,
					headers: { "content-type": "text/event-stream" },
				});
			}
			return new Response("not found", { status: 404 });
		});

		vi.stubGlobal("fetch", fetchMock);

		const model: Model<"openai-codex-responses"> = {
			id: modelId,
			name: modelId,
			api: "openai-codex-responses",
			provider: "openai-codex",
			baseUrl: "https://chatgpt.com/backend-api",
			reasoning: true,
			thinkingLevelMap: { minimal: "low" },
			input: ["text"],
			cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
			contextWindow: 400000,
			maxTokens: 128000,
		};

		const context = normalizeContext({
			systemPrompt: "You are a helpful assistant.",
			messages: [{ role: "user", content: "Say hello", timestamp: Date.now() }],
		});

		const streamResult = streamOpenAICodexResponses(model, context, {
			apiKey: token,
			reasoningEffort: "minimal",
			transport: "sse",
		});
		await streamResult.result();
		expect(requestedReasoning).toEqual({ effort: "low", summary: "auto" });
	});

	it.each([
		["gpt-5.1-codex", "flex", 0.5],
		["gpt-5.1-codex", "priority", 2],
		["gpt-5.5", "flex", 0.5],
		["gpt-5.5", "priority", 2.5],
	] as const)(
		"uses the client-sent %s service tier for %s when Codex echoes default",
		async (modelId, serviceTier, multiplier) => {
			const tempDir = mkdtempSync(join(tmpdir(), "pi-codex-stream-"));
			process.env.PI_CODING_AGENT_DIR = tempDir;
			const token = mockToken();
			const sse = `${[
				`data: ${JSON.stringify({
					type: "response.output_item.added",
					item: { type: "message", id: "msg_1", role: "assistant", status: "in_progress", content: [] },
				})}`,
				`data: ${JSON.stringify({ type: "response.content_part.added", part: { type: "output_text", text: "" } })}`,
				`data: ${JSON.stringify({ type: "response.output_text.delta", delta: "Hello" })}`,
				`data: ${JSON.stringify({
					type: "response.output_item.done",
					item: {
						type: "message",
						id: "msg_1",
						role: "assistant",
						status: "completed",
						content: [{ type: "output_text", text: "Hello" }],
					},
				})}`,
				`data: ${JSON.stringify({
					type: "response.completed",
					response: {
						status: "completed",
						service_tier: "default",
						usage: {
							input_tokens: 1000000,
							output_tokens: 1000000,
							total_tokens: 2000000,
							input_tokens_details: { cached_tokens: 0 },
						},
					},
				})}`,
			].join("\n\n")}\n\n`;

			const encoder = new TextEncoder();
			const stream = new ReadableStream<Uint8Array>({
				start(controller) {
					controller.enqueue(encoder.encode(sse));
					controller.close();
				},
			});

			const fetchMock = vi.fn(async (input: string | URL) => {
				const url = typeof input === "string" ? input : input.toString();
				if (url === "https://api.github.com/repos/openai/codex/releases/latest") {
					return new Response(JSON.stringify({ tag_name: "rust-v0.0.0" }), { status: 200 });
				}
				if (url.startsWith("https://raw.githubusercontent.com/openai/codex/")) {
					return new Response("PROMPT", { status: 200, headers: { etag: '"etag"' } });
				}
				if (url === "https://chatgpt.com/backend-api/codex/responses") {
					return new Response(stream, {
						status: 200,
						headers: { "content-type": "text/event-stream" },
					});
				}
				return new Response("not found", { status: 404 });
			});
			vi.stubGlobal("fetch", fetchMock);

			const model: Model<"openai-codex-responses"> = {
				id: modelId,
				name: modelId === "gpt-5.5" ? "GPT-5.5" : "GPT-5.1 Codex",
				api: "openai-codex-responses",
				provider: "openai-codex",
				baseUrl: "https://chatgpt.com/backend-api",
				reasoning: true,
				input: ["text"],
				cost: { input: 1, output: 2, cacheRead: 0, cacheWrite: 0 },
				contextWindow: 400000,
				maxTokens: 128000,
			};

			const context = normalizeContext({
				systemPrompt: "You are a helpful assistant.",
				messages: [{ role: "user", content: "Say hello", timestamp: Date.now() }],
			});

			const result = await streamOpenAICodexResponses(model, context, {
				apiKey: token,
				serviceTier,
				transport: "sse",
			}).result();

			expect(result.usage.cost.input).toBe(1 * multiplier);
			expect(result.usage.cost.output).toBe(2 * multiplier);
			expect(result.usage.cost.total).toBe(3 * multiplier);
		},
	);

	it("does not set session-id/x-client-request-id headers when sessionId is not provided", async () => {
		const tempDir = mkdtempSync(join(tmpdir(), "pi-codex-stream-"));
		process.env.PI_CODING_AGENT_DIR = tempDir;

		const payload = Buffer.from(
			JSON.stringify({ "https://api.openai.com/auth": { chatgpt_account_id: "acc_test" } }),
			"utf8",
		).toString("base64");
		const token = `aaa.${payload}.bbb`;

		const sse = `${[
			`data: ${JSON.stringify({
				type: "response.output_item.added",
				item: { type: "message", id: "msg_1", role: "assistant", status: "in_progress", content: [] },
			})}`,
			`data: ${JSON.stringify({ type: "response.content_part.added", part: { type: "output_text", text: "" } })}`,
			`data: ${JSON.stringify({ type: "response.output_text.delta", delta: "Hello" })}`,
			`data: ${JSON.stringify({
				type: "response.output_item.done",
				item: {
					type: "message",
					id: "msg_1",
					role: "assistant",
					status: "completed",
					content: [{ type: "output_text", text: "Hello" }],
				},
			})}`,
			`data: ${JSON.stringify({
				type: "response.completed",
				response: {
					status: "completed",
					usage: {
						input_tokens: 5,
						output_tokens: 3,
						total_tokens: 8,
						input_tokens_details: { cached_tokens: 0 },
					},
				},
			})}`,
		].join("\n\n")}\n\n`;

		const encoder = new TextEncoder();
		const stream = new ReadableStream<Uint8Array>({
			start(controller) {
				controller.enqueue(encoder.encode(sse));
				controller.close();
			},
		});

		const fetchMock = vi.fn(async (input: string | URL, init?: RequestInit) => {
			const url = typeof input === "string" ? input : input.toString();
			if (url === "https://api.github.com/repos/openai/codex/releases/latest") {
				return new Response(JSON.stringify({ tag_name: "rust-v0.0.0" }), { status: 200 });
			}
			if (url.startsWith("https://raw.githubusercontent.com/openai/codex/")) {
				return new Response("PROMPT", { status: 200, headers: { etag: '"etag"' } });
			}
			if (url === "https://chatgpt.com/backend-api/codex/responses") {
				const headers = init?.headers instanceof Headers ? init.headers : undefined;
				// Verify headers are not set when sessionId is not provided
				expect(headers?.has("session-id")).toBe(false);
				expect(headers?.has("session_id")).toBe(false);
				expect(headers?.has("x-client-request-id")).toBe(false);

				return new Response(stream, {
					status: 200,
					headers: { "content-type": "text/event-stream" },
				});
			}
			return new Response("not found", { status: 404 });
		});

		vi.stubGlobal("fetch", fetchMock);

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

		const context = normalizeContext({
			systemPrompt: "You are a helpful assistant.",
			messages: [{ role: "user", content: "Say hello", timestamp: Date.now() }],
		});

		// No sessionId provided
		const streamResult = streamOpenAICodexResponses(model, context, {
			apiKey: token,
			transport: "sse",
		});
		await streamResult.result();
	});
	it.each(["text", "mixed binary", "mixed error"])("keeps raw %s provider events in arrival order", async (frames) => {
		const token = mockToken();
		const sentBodies: unknown[] = [];
		const providerEvents: unknown[] = [];
		const providerEventModels: Model<Api>[] = [];
		let capturedWebSocketHeaders: Record<string, string> | undefined;

		const fetchMock = vi.fn(async () => new Response("unexpected fetch", { status: 500 }));
		vi.stubGlobal("fetch", fetchMock);

		class MockWebSocket {
			private listeners = new Map<string, Set<(event: unknown) => void>>();

			constructor(_url: string, protocols?: string | string[] | { headers?: Record<string, string> }) {
				if (protocols && typeof protocols === "object" && !Array.isArray(protocols)) {
					capturedWebSocketHeaders = protocols.headers;
				}
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

			send(data: string): void {
				sentBodies.push(JSON.parse(data));
				const events = [
					{
						type: "response.output_item.added",
						item: { type: "message", id: "msg_1", role: "assistant", status: "in_progress", content: [] },
					},
					{ type: "response.content_part.added", part: { type: "output_text", text: "" } },
					{ type: "response.output_text.delta", delta: "Hello" },
					{
						type: "response.output_item.done",
						item: {
							type: "message",
							id: "msg_1",
							role: "assistant",
							status: "completed",
							content: [{ type: "output_text", text: "Hello" }],
						},
					},
					{
						type: "response.done",
						response: {
							status: "completed",
							end_turn: false,
							usage: {
								input_tokens: 5,
								output_tokens: 3,
								total_tokens: 8,
								input_tokens_details: { cached_tokens: 0 },
							},
						},
					},
				];
				queueMicrotask(() => {
					for (const event of events) {
						const text = JSON.stringify(event);
						if (frames !== "text" && event.type === "response.output_text.delta") {
							const data = new Blob([text]);
							this.dispatch("message", {
								data: {
									async arrayBuffer() {
										await new Promise<void>((resolve) => setTimeout(resolve, 5));
										return data.arrayBuffer();
									},
								},
							});
						} else if (frames !== "text" && event.type === "response.output_item.done") {
							this.dispatch("message", { data: new TextEncoder().encode(text) });
						} else {
							this.dispatch("message", { data: text });
						}
					}
					if (frames === "mixed error") this.dispatch("error", { message: "closed after completion" });
					if (frames !== "text") this.dispatch("close", { code: 1000, wasClean: true });
				});
			}

			close(): void {}

			private dispatch(type: string, event: unknown): void {
				for (const listener of this.listeners.get(type) ?? []) {
					listener(event);
				}
			}
		}

		vi.stubGlobal("WebSocket", MockWebSocket);

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
		const context = normalizeContext({
			systemPrompt: "You are a helpful assistant.",
			messages: [{ role: "user", content: "Say hello", timestamp: 1 }],
		});

		const result = await streamSimpleOpenAICodexResponses(model, context, {
			apiKey: token,
			sessionId: "session-auto",
			transport: "auto",
			onProviderStreamEvent: (event, eventModel) => {
				providerEvents.push(event);
				providerEventModels.push(eventModel);
			},
		}).result();

		expect(result.endTurn).toBe(false);
		expect(sentBodies).toHaveLength(1);
		expect(providerEvents.map((event) => (event as { type?: string }).type)).toEqual([
			"response.output_item.added",
			"response.content_part.added",
			"response.output_text.delta",
			"response.output_item.done",
			"response.done",
		]);
		expect(providerEventModels).toEqual([model, model, model, model, model]);
		expect(capturedWebSocketHeaders?.["session-id"]).toBe("session-auto");
		expect(capturedWebSocketHeaders?.session_id).toBeUndefined();
		expect(capturedWebSocketHeaders?.["x-client-request-id"]).toBe("session-auto");
		expect(global.fetch).not.toHaveBeenCalled();
		expect(getOpenAICodexWebSocketDebugStats("session-auto")).toMatchObject({
			cachedContextRequests: 1,
			fullContextRequests: 1,
		});
	});

	it("scopes cached websockets to the authenticated account", async () => {
		// Regression for #7284: rotating accounts must not reuse a socket authenticated by another account.
		const connectedHeaders: Record<string, string>[] = [];
		let responseId = 0;

		class MockWebSocket {
			static OPEN = 1;
			readyState = MockWebSocket.OPEN;
			private listeners = new Map<string, Set<(event: unknown) => void>>();

			constructor(_url: string, protocols?: string | string[] | { headers?: Record<string, string> }) {
				const headers =
					protocols && typeof protocols === "object" && !Array.isArray(protocols) ? protocols.headers : undefined;
				connectedHeaders.push(headers ?? {});
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
				queueMicrotask(() => {
					this.dispatch("message", {
						data: JSON.stringify({
							type: "response.completed",
							response: {
								id: `resp_${++responseId}`,
								status: "completed",
								usage: { input_tokens: 1, output_tokens: 1, total_tokens: 2 },
							},
						}),
					});
				});
			}

			close(): void {
				this.readyState = 3;
			}

			private dispatch(type: string, event: unknown): void {
				for (const listener of this.listeners.get(type) ?? []) listener(event);
			}
		}

		vi.stubGlobal("WebSocket", MockWebSocket);
		vi.stubGlobal(
			"fetch",
			vi.fn(async () => new Response("unexpected fetch", { status: 500 })),
		);

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
		const context = normalizeContext({ systemPrompt: "", messages: [] });
		const options = { sessionId: "shared-session", transport: "websocket-cached" as const };

		await streamOpenAICodexResponses(model, context, {
			...options,
			apiKey: mockToken("account-a"),
		}).result();
		await streamOpenAICodexResponses(model, context, {
			...options,
			apiKey: mockToken("account-b"),
		}).result();
		await streamOpenAICodexResponses(model, context, {
			...options,
			apiKey: mockToken("account-a"),
		}).result();

		expect(connectedHeaders.map((headers) => headers["chatgpt-account-id"])).toEqual(["account-a", "account-b"]);
		expect(connectedHeaders.map((headers) => headers.authorization)).toEqual([
			`Bearer ${mockToken("account-a")}`,
			`Bearer ${mockToken("account-b")}`,
		]);
		expect(global.fetch).not.toHaveBeenCalled();
		expect(getOpenAICodexWebSocketDebugStats("shared-session")).toMatchObject({
			connectionsCreated: 2,
			connectionsReused: 1,
		});
	});

	it("closes one-shot websockets when cacheRetention is none", async () => {
		const token = mockToken();
		const sentBodies: Array<{ prompt_cache_key?: string }> = [];
		let connections = 0;
		let closedConnections = 0;

		class MockWebSocket {
			private listeners = new Map<string, Set<(event: unknown) => void>>();

			constructor() {
				connections++;
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

			send(data: string): void {
				sentBodies.push(JSON.parse(data) as { prompt_cache_key?: string });
				queueMicrotask(() => {
					this.dispatch("message", {
						data: JSON.stringify({
							type: "response.completed",
							response: {
								id: `resp_${connections}`,
								status: "completed",
								usage: { input_tokens: 5, output_tokens: 3, total_tokens: 8 },
							},
						}),
					});
				});
			}

			close(): void {
				closedConnections++;
			}

			private dispatch(type: string, event: unknown): void {
				for (const listener of this.listeners.get(type) ?? []) {
					listener(event);
				}
			}
		}

		vi.stubGlobal("WebSocket", MockWebSocket);
		vi.stubGlobal(
			"fetch",
			vi.fn(async () => new Response("unexpected fetch", { status: 500 })),
		);

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
		const context = normalizeContext({
			systemPrompt: "You are a helpful assistant.",
			messages: [{ role: "user", content: "Say hello", timestamp: 1 }],
		});
		const options = {
			apiKey: token,
			cacheRetention: "none" as const,
			sessionId: "one-off-summary",
			transport: "auto" as const,
		};

		await streamOpenAICodexResponses(model, context, options).result();
		await streamOpenAICodexResponses(model, context, options).result();

		expect(connections).toBe(2);
		expect(closedConnections).toBe(2);
		expect(sentBodies).toHaveLength(2);
		expect(sentBodies.every((body) => body.prompt_cache_key === undefined)).toBe(true);
		expect(getOpenAICodexWebSocketDebugStats("one-off-summary")).toBeUndefined();
		expect(global.fetch).not.toHaveBeenCalled();
	});

	it("falls back to SSE when websocket connect does not open before the connect timeout", async () => {
		vi.useFakeTimers();
		const token = mockToken();
		const encoder = new TextEncoder();
		const sse = buildSSEPayload({ status: "completed" });

		const fetchMock = vi.fn(async (input: string | URL) => {
			const url = typeof input === "string" ? input : input.toString();
			if (url !== "https://chatgpt.com/backend-api/codex/responses") {
				throw new Error(`Unexpected URL: ${url}`);
			}

			return new Response(
				new ReadableStream<Uint8Array>({
					start(controller) {
						controller.enqueue(encoder.encode(sse));
						controller.close();
					},
				}),
				{ status: 200, headers: { "content-type": "text/event-stream" } },
			);
		});
		vi.stubGlobal("fetch", fetchMock);

		class MockWebSocket {
			private listeners = new Map<string, Set<(event: unknown) => void>>();

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
				throw new Error("send should not be called before websocket open");
			}

			close(): void {}
		}

		vi.stubGlobal("WebSocket", MockWebSocket);

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
		const context = normalizeContext({
			systemPrompt: "You are a helpful assistant.",
			messages: [{ role: "user", content: "Say hello", timestamp: 1 }],
		});

		const resultPromise = streamOpenAICodexResponses(model, context, {
			apiKey: token,
			sessionId: "ws-connect-timeout",
			transport: "auto",
			timeoutMs: 300_000,
			websocketConnectTimeoutMs: 50,
		}).result();

		await vi.advanceTimersByTimeAsync(50);

		const result = await resultPromise;
		expect(result.content.find((content) => content.type === "text")?.text).toBe("Hello");
		expect(fetchMock).toHaveBeenCalledTimes(1);
		expect(getOpenAICodexWebSocketDebugStats("ws-connect-timeout")).toMatchObject({
			websocketFailures: 1,
			sseFallbacks: 1,
			websocketFallbackActive: true,
			lastWebSocketError: "WebSocket connect timeout after 50ms",
		});
	});

	// earendil-works/pi#10721
	it("retries websocket after an SSE fallback cooldown that grows with consecutive failures", async () => {
		vi.useFakeTimers({ toFake: ["Date"] });
		vi.setSystemTime(new Date("2026-05-13T00:00:00Z"));
		const token = mockToken();
		const encoder = new TextEncoder();
		const sse = buildSSEPayload({ status: "completed" });
		const sessionId = "ws-fallback-cooldown";
		let websocketHealthy = false;
		let connections = 0;

		const fetchMock = vi.fn(
			async () =>
				new Response(
					new ReadableStream<Uint8Array>({
						start(controller) {
							controller.enqueue(encoder.encode(sse));
							controller.close();
						},
					}),
					{ status: 200, headers: { "content-type": "text/event-stream" } },
				),
		);
		vi.stubGlobal("fetch", fetchMock);

		class MockWebSocket extends EventTarget {
			static OPEN = 1;
			readyState = MockWebSocket.OPEN;

			constructor() {
				super();
				connections++;
				const healthy = websocketHealthy;
				queueMicrotask(() => {
					if (healthy) {
						this.dispatchEvent(new Event("open"));
					} else {
						this.readyState = 3;
						this.dispatchEvent(Object.assign(new Event("error"), { message: "network blip" }));
					}
				});
			}

			send(): void {
				const event = {
					type: "response.completed",
					response: {
						id: `resp_${connections}`,
						status: "completed",
						usage: { input_tokens: 5, output_tokens: 3, total_tokens: 8 },
					},
				};
				queueMicrotask(() => {
					this.dispatchEvent(Object.assign(new Event("message"), { data: JSON.stringify(event) }));
				});
			}

			close(): void {
				this.readyState = 3;
			}
		}
		vi.stubGlobal("WebSocket", MockWebSocket);

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
		const request = async () => {
			const result = await streamOpenAICodexResponses(model, normalizeContext({ systemPrompt: "", messages: [] }), {
				apiKey: token,
				sessionId,
				transport: "auto",
			}).result();
			expect(result.stopReason).toBe("stop");
		};
		const advance = (ms: number) => vi.setSystemTime(Date.now() + ms);
		const expectTransports = (expected: { connections: number; sseRequests: number; fallbackActive: boolean }) => {
			expect(connections).toBe(expected.connections);
			expect(fetchMock).toHaveBeenCalledTimes(expected.sseRequests);
			expect(getOpenAICodexWebSocketDebugStats(sessionId)?.websocketFallbackActive).toBe(expected.fallbackActive);
		};

		// First failure: SSE for the next minute without new WebSocket attempts.
		await request();
		expectTransports({ connections: 1, sseRequests: 1, fallbackActive: true });
		await request();
		expectTransports({ connections: 1, sseRequests: 2, fallbackActive: true });

		// The cooldown expires: the debug stats show it before the next request, which retries WebSocket.
		advance(60_000);
		expectTransports({ connections: 1, sseRequests: 2, fallbackActive: false });
		await request();
		expectTransports({ connections: 2, sseRequests: 3, fallbackActive: true });

		// The second consecutive failure doubles the cooldown to two minutes.
		advance(60_000);
		await request();
		expectTransports({ connections: 2, sseRequests: 4, fallbackActive: true });
		advance(60_000);
		websocketHealthy = true;
		await request();
		expectTransports({ connections: 3, sseRequests: 4, fallbackActive: false });
		expect(getOpenAICodexWebSocketDebugStats(sessionId)).toMatchObject({ websocketFailures: 2, sseFallbacks: 4 });

		// A WebSocket success resets the backoff, so the next failure waits one minute again.
		closeOpenAICodexWebSocketSessions(sessionId);
		websocketHealthy = false;
		await request();
		expectTransports({ connections: 4, sseRequests: 5, fallbackActive: true });
		advance(60_000);
		websocketHealthy = true;
		await request();
		expectTransports({ connections: 5, sseRequests: 5, fallbackActive: false });
	});

	it("reconnects once when the websocket connection limit is reached before output starts", async () => {
		const token = mockToken();
		let connections = 0;

		const fetchMock = vi.fn();
		vi.stubGlobal("fetch", fetchMock);

		class MockWebSocket extends EventTarget {
			private readonly limitReached = connections++ === 0;

			constructor() {
				super();
				queueMicrotask(() => this.dispatchEvent(new Event("open")));
			}

			send(): void {
				const event = this.limitReached
					? { type: "error", error: { code: "websocket_connection_limit_reached" } }
					: {
							type: "response.completed",
							response: {
								id: "resp_1",
								status: "completed",
								usage: { input_tokens: 5, output_tokens: 3, total_tokens: 8 },
							},
						};
				queueMicrotask(() => {
					this.dispatchEvent(Object.assign(new Event("message"), { data: JSON.stringify(event) }));
				});
			}

			close(): void {}
		}

		vi.stubGlobal("WebSocket", MockWebSocket);

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

		const result = await streamOpenAICodexResponses(model, normalizeContext({ systemPrompt: "", messages: [] }), {
			apiKey: token,
		}).result();

		expect(result.stopReason).toBe("stop");
		expect(connections).toBe(2);
		expect(fetchMock).not.toHaveBeenCalled();
	});

	// Regression for #12124: metadata and thinking already mark the provider stream as started.
	it.each([
		{
			name: "response metadata",
			events: [{ type: "response.created", response: { id: "resp_expired" } }],
		},
		{
			name: "rate-limit metadata",
			events: [{ type: "codex.rate_limits", rate_limits: { allowed: true, limit_reached: false } }],
		},
		{
			name: "partial thinking",
			events: [
				{ type: "response.output_item.added", item: { type: "reasoning", id: "rs_1", summary: [] } },
				{ type: "response.reasoning_summary_part.added", part: { type: "summary_text", text: "" } },
				{ type: "response.reasoning_summary_text.delta", delta: "unfinished thinking" },
			],
		},
	])("retries a connection lifetime limit after $name on a fresh websocket", async ({ name, events }) => {
		let connections = 0;
		const closed: number[] = [];
		const sentBodies: Array<{ previous_response_id?: string }> = [];
		const fetchMock = vi.fn();
		vi.stubGlobal("fetch", fetchMock);

		class MockWebSocket extends EventTarget {
			private readonly connectionId = ++connections;
			readyState = 1;

			constructor() {
				super();
				queueMicrotask(() => this.dispatchEvent(new Event("open")));
			}

			send(data: string): void {
				sentBodies.push(JSON.parse(data) as { previous_response_id?: string });
				const responseEvents =
					this.connectionId === 1
						? [
								...events,
								{
									type: "error",
									error: {
										code: "websocket_connection_limit_reached",
										message:
											"Responses websocket connection limit reached (60 minutes). Create a new websocket connection to continue.",
									},
								},
							]
						: [
								{
									type: "response.output_item.added",
									item: { type: "message", id: "msg_2", role: "assistant", content: [] },
								},
								{
									type: "response.output_item.done",
									item: {
										type: "message",
										id: "msg_2",
										role: "assistant",
										content: [{ type: "output_text", text: "Recovered" }],
									},
								},
								{
									type: "response.completed",
									response: {
										id: "resp_2",
										status: "completed",
										usage: { input_tokens: 5, output_tokens: 3, total_tokens: 8 },
									},
								},
							];
				queueMicrotask(() => {
					for (const event of responseEvents) {
						this.dispatchEvent(Object.assign(new Event("message"), { data: JSON.stringify(event) }));
					}
				});
			}

			close(): void {
				closed.push(this.connectionId);
				this.readyState = 3;
			}
		}

		vi.stubGlobal("WebSocket", MockWebSocket);
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
		const attempts: AssistantMessage[] = [];
		const result = await retryAssistantCall(
			async () => {
				const message = await streamOpenAICodexResponses(model, normalizeContext({ messages: [] }), {
					apiKey: mockToken(),
					sessionId: `connection-limit-${name}`,
					transport: "websocket-cached",
				}).result();
				attempts.push(message);
				return message;
			},
			{ enabled: true, maxRetries: 1, baseDelayMs: 0 },
			undefined,
		);

		expect(result.stopReason).toBe("stop");
		expect(result.content).toMatchObject([{ type: "text", text: "Recovered" }]);
		expect(attempts).toHaveLength(2);
		expect(attempts[0].stopReason).toBe("error");
		if (name === "partial thinking") {
			expect(attempts[0].content).toMatchObject([{ type: "thinking", thinking: "unfinished thinking" }]);
		}
		expect(connections).toBe(2);
		expect(closed).toContain(1);
		expect(sentBodies).toHaveLength(2);
		expect(sentBodies[1].previous_response_id).toBeUndefined();
		expect(fetchMock).not.toHaveBeenCalled();
	});

	it.each(["silent", "pending Blob"])("falls back to SSE on a %s socket timeout", async (frame) => {
		vi.useFakeTimers();
		const token = mockToken();
		const sentBodies: unknown[] = [];
		const encoder = new TextEncoder();
		const sse = buildSSEPayload({ status: "completed" });

		const fetchMock = vi.fn(async (input: string | URL) => {
			const url = typeof input === "string" ? input : input.toString();
			if (url !== "https://chatgpt.com/backend-api/codex/responses") {
				throw new Error(`Unexpected URL: ${url}`);
			}

			return new Response(
				new ReadableStream<Uint8Array>({
					start(controller) {
						controller.enqueue(encoder.encode(sse));
						controller.close();
					},
				}),
				{ status: 200, headers: { "content-type": "text/event-stream" } },
			);
		});
		vi.stubGlobal("fetch", fetchMock);

		class MockWebSocket {
			static OPEN = 1;
			readyState = MockWebSocket.OPEN;
			private listeners = new Map<string, Set<(event: unknown) => void>>();

			constructor(_url: string, _protocols?: string | string[] | { headers?: Record<string, string> }) {
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

			send(data: string): void {
				sentBodies.push(JSON.parse(data));
				if (frame === "pending Blob") {
					queueMicrotask(() => this.dispatch("message", { data: { arrayBuffer: () => new Promise(() => {}) } }));
				}
			}

			close(): void {
				this.readyState = 3;
			}

			private dispatch(type: string, event: unknown): void {
				for (const listener of this.listeners.get(type) ?? []) {
					listener(event);
				}
			}
		}

		vi.stubGlobal("WebSocket", MockWebSocket);

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
		const context = normalizeContext({
			systemPrompt: "You are a helpful assistant.",
			messages: [{ role: "user", content: "Say hello", timestamp: 1 }],
		});

		const resultPromise = streamOpenAICodexResponses(model, context, {
			apiKey: token,
			sessionId: "ws-idle-before-start",
			transport: "auto",
			timeoutMs: 50,
		}).result();

		await vi.advanceTimersByTimeAsync(0);
		expect(sentBodies).toHaveLength(1);
		await vi.advanceTimersByTimeAsync(50);

		const result = await resultPromise;
		expect(result.content.find((content) => content.type === "text")?.text).toBe("Hello");
		expect(fetchMock).toHaveBeenCalledTimes(1);
		expect(getOpenAICodexWebSocketDebugStats("ws-idle-before-start")).toMatchObject({
			websocketFailures: 1,
			sseFallbacks: 1,
			websocketFallbackActive: true,
		});
	});

	it("errors when a websocket is idle after the stream started", async () => {
		vi.useFakeTimers();
		const token = mockToken();

		const fetchMock = vi.fn(async () => new Response("unexpected fetch", { status: 500 }));
		vi.stubGlobal("fetch", fetchMock);

		class MockWebSocket {
			static OPEN = 1;
			readyState = MockWebSocket.OPEN;
			private listeners = new Map<string, Set<(event: unknown) => void>>();

			constructor(_url: string, _protocols?: string | string[] | { headers?: Record<string, string> }) {
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
				queueMicrotask(() => {
					this.dispatch("message", {
						data: JSON.stringify({
							type: "response.output_item.added",
							item: { type: "message", id: "msg_1", role: "assistant", status: "in_progress", content: [] },
						}),
					});
				});
			}

			close(): void {
				this.readyState = 3;
			}

			private dispatch(type: string, event: unknown): void {
				for (const listener of this.listeners.get(type) ?? []) {
					listener(event);
				}
			}
		}

		vi.stubGlobal("WebSocket", MockWebSocket);

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
		const context = normalizeContext({
			systemPrompt: "You are a helpful assistant.",
			messages: [{ role: "user", content: "Say hello", timestamp: 1 }],
		});

		const resultPromise = streamOpenAICodexResponses(model, context, {
			apiKey: token,
			transport: "auto",
			timeoutMs: 50,
		}).result();

		await vi.advanceTimersByTimeAsync(0);
		await vi.advanceTimersByTimeAsync(50);

		const result = await resultPromise;
		expect(result.stopReason).toBe("error");
		expect(result.errorMessage).toBe("WebSocket idle timeout after 50ms");
		expect(fetchMock).not.toHaveBeenCalled();
	});

	it("opens a fresh cached websocket before the backend connection age limit", async () => {
		vi.useFakeTimers();
		const startedAt = new Date("2026-07-03T00:00:00Z");
		vi.setSystemTime(startedAt);
		const token = mockToken();
		const sentConnectionIds: number[] = [];
		let connections = 0;

		class MockWebSocket {
			static OPEN = 1;
			static CLOSED = 3;
			readyState = MockWebSocket.OPEN;
			private readonly connectionId = ++connections;
			private listeners = new Map<string, Set<(event: unknown) => void>>();

			constructor(_url: string, _protocols?: string | string[] | { headers?: Record<string, string> }) {
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
				sentConnectionIds.push(this.connectionId);
				const responseId = `resp_${this.connectionId}`;
				queueMicrotask(() => {
					this.dispatch("message", {
						data: JSON.stringify({
							type: "response.completed",
							response: {
								id: responseId,
								status: "completed",
								usage: { input_tokens: 5, output_tokens: 3, total_tokens: 8 },
							},
						}),
					});
				});
			}

			close(): void {
				this.readyState = MockWebSocket.CLOSED;
			}

			private dispatch(type: string, event: unknown): void {
				for (const listener of this.listeners.get(type) ?? []) {
					listener(event);
				}
			}
		}

		vi.stubGlobal("WebSocket", MockWebSocket);

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
		const sessionId = "aged-ws-session";
		const firstContext = normalizeContext({
			systemPrompt: "You are a helpful assistant.",
			messages: [{ role: "user", content: "Say hello", timestamp: 1 }],
		});

		const first = await streamOpenAICodexResponses(model, firstContext, {
			apiKey: token,
			sessionId,
			transport: "websocket-cached",
		}).result();
		vi.setSystemTime(new Date(startedAt.getTime() + 46 * 60 * 1000));
		const secondContext = normalizeContext({
			messages: [...firstContext.messages, first, { role: "user", content: "Now finish", timestamp: 2 }],
		});

		await streamOpenAICodexResponses(model, secondContext, {
			apiKey: token,
			sessionId,
			transport: "websocket-cached",
		}).result();

		expect(connections).toBe(2);
		expect(sentConnectionIds).toEqual([1, 2]);
		expect(getOpenAICodexWebSocketDebugStats(sessionId)).toMatchObject({
			connectionsCreated: 2,
			connectionsReused: 0,
		});
	});

	// Regression test for https://github.com/earendil-works/pi/issues/12269
	it("keeps sending input deltas in websocket-cached mode when the thinking level changes", async () => {
		const token = mockToken();
		const sentBodies: Array<{ input: unknown[]; previous_response_id?: string; reasoning?: unknown }> = [];

		class MockWebSocket {
			static OPEN = 1;
			readyState = MockWebSocket.OPEN;
			private listeners = new Map<string, Set<(event: unknown) => void>>();

			constructor(_url: string, _protocols?: string | string[] | { headers?: Record<string, string> }) {
				queueMicrotask(() => this.dispatch("open", {}));
			}

			addEventListener(type: string, listener: (event: unknown) => void): void {
				const listeners = this.listeners.get(type) ?? new Set();
				listeners.add(listener);
				this.listeners.set(type, listeners);
			}

			removeEventListener(type: string, listener: (event: unknown) => void): void {
				this.listeners.get(type)?.delete(listener);
			}

			send(data: string): void {
				sentBodies.push(JSON.parse(data));
				const responseId = `resp_${sentBodies.length}`;
				const item = {
					type: "message",
					id: `msg_${sentBodies.length}`,
					role: "assistant",
					status: "completed",
					content: [{ type: "output_text", text: "OK" }],
				};
				const events = [
					{ type: "response.created", response: { id: responseId } },
					{ type: "response.output_item.added", item: { ...item, status: "in_progress", content: [] } },
					{ type: "response.content_part.added", part: { type: "output_text", text: "" } },
					{ type: "response.output_text.delta", delta: "OK" },
					{ type: "response.output_item.done", item },
					{
						type: "response.completed",
						response: {
							id: responseId,
							status: "completed",
							usage: { input_tokens: 5, output_tokens: 1, total_tokens: 6 },
						},
					},
				];
				queueMicrotask(() => {
					for (const event of events) this.dispatch("message", { data: JSON.stringify(event) });
				});
			}

			close(): void {
				this.readyState = 3;
			}

			private dispatch(type: string, event: unknown): void {
				for (const listener of this.listeners.get(type) ?? []) listener(event);
			}
		}

		vi.stubGlobal("WebSocket", MockWebSocket);

		const model = buildCodexModel("gpt-6.2-sol", { supportsMidConvoEffort: true });
		const sessionId = "session-effort-deltas";
		const messages: Context["messages"] = [];
		for (const [turn, reasoningEffort] of (["low", "high", "high"] as const).entries()) {
			messages.push({ role: "user", content: `Turn ${turn + 1}`, timestamp: turn });
			const reply = await streamOpenAICodexResponses(model, normalizeContext({ messages }), {
				apiKey: token,
				sessionId,
				transport: "websocket-cached",
				reasoningEffort,
			}).result();
			messages.push(reply);
		}

		expect(sentBodies.map((body) => body.reasoning)).toEqual([
			{ effort: "medium", summary: "auto" },
			{ effort: "medium", summary: "auto" },
			{ effort: "medium", summary: "auto" },
		]);
		expect(sentBodies.map((body) => body.previous_response_id)).toEqual([undefined, "resp_1", "resp_2"]);
		expect(sentBodies.map((body) => summarizeInput(body as Record<string, unknown>))).toEqual([
			["user", "configuration_update:low"],
			["user", "configuration_update:high"],
			["user", "configuration_update:high"],
		]);
		expect(getOpenAICodexWebSocketDebugStats(sessionId)).toMatchObject({ fullContextRequests: 1, deltaRequests: 2 });
	});

	it("sends only response input deltas in websocket-cached mode", async () => {
		const token = mockToken();
		const sentBodies: unknown[] = [];

		class MockWebSocket {
			static OPEN = 1;
			readyState = MockWebSocket.OPEN;
			private listeners = new Map<string, Set<(event: unknown) => void>>();

			constructor(_url: string, _protocols?: string | string[] | { headers?: Record<string, string> }) {
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

			send(data: string): void {
				sentBodies.push(JSON.parse(data));
				const responseId = `resp_${sentBodies.length}`;
				const outputEvents =
					sentBodies.length === 1
						? [
								{
									type: "response.output_item.added",
									item: {
										type: "custom_tool_call",
										id: "ctc_1",
										call_id: "call_1",
										name: "sample_tool",
										input: "",
									},
								},
								{ type: "response.custom_tool_call_input.delta", item_id: "ctc_1", delta: "abc" },
								{ type: "response.custom_tool_call_input.done", item_id: "ctc_1", input: "abc" },
								{
									type: "response.output_item.done",
									item: {
										type: "custom_tool_call",
										id: "ctc_1",
										call_id: "call_1",
										name: "sample_tool",
										input: "abc",
									},
								},
							]
						: [];
				const events = [
					{ type: "response.created", response: { id: responseId } },
					...outputEvents,
					{
						type: "response.completed",
						response: {
							id: responseId,
							status: "completed",
							usage: {
								input_tokens: 5,
								output_tokens: 3,
								total_tokens: 8,
								input_tokens_details: { cached_tokens: 0 },
							},
						},
					},
				];
				queueMicrotask(() => {
					for (const event of events) {
						this.dispatch("message", { data: JSON.stringify(event) });
					}
				});
			}

			close(): void {
				this.readyState = 3;
			}

			private dispatch(type: string, event: unknown): void {
				for (const listener of this.listeners.get(type) ?? []) {
					listener(event);
				}
			}
		}

		vi.stubGlobal("WebSocket", MockWebSocket);

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
			compat: { supportsOpenAIGrammarTools: true },
		};
		const firstContext: Context = {
			systemPrompt: "You are a helpful assistant.",
			messages: [{ role: "user", content: "Use the tool", timestamp: 1 }],
			tools: [
				{
					name: "sample_tool",
					description: "Sample tool",
					parameters: Type.Object({ payload: Type.String() }),
					constrainedSampling: { type: "grammar", variants: { openai_lark: "start: /[a-z]+/" } },
				},
			],
		};

		const first = await streamOpenAICodexResponses(model, normalizeContext(firstContext), {
			apiKey: token,
			sessionId: "session-1",
			transport: "websocket-cached",
		}).result();

		const secondContext = normalizeContext({
			...firstContext,
			messages: [
				...firstContext.messages,
				first,
				{
					role: "toolResult",
					toolCallId: "call_1|ctc_1",
					toolName: "sample_tool",
					content: [{ type: "text", text: "real result" }],
					isError: false,
					timestamp: 2,
				},
				{ role: "user", content: "Now finish", timestamp: 3 },
			],
		});
		await streamOpenAICodexResponses(model, secondContext, {
			apiKey: token,
			sessionId: "session-1",
			transport: "websocket-cached",
		}).result();

		expect(sentBodies).toHaveLength(2);
		const firstBody = sentBodies[0] as { input: unknown[]; previous_response_id?: string; store?: boolean };
		const secondBody = sentBodies[1] as { input: unknown[]; previous_response_id?: string; store?: boolean };
		expect(firstBody.store).toBe(false);
		expect(firstBody.previous_response_id).toBeUndefined();
		expect(firstBody.input).toEqual([
			{ role: "developer", content: "You are a helpful assistant." },
			{ role: "user", content: [{ type: "input_text", text: "Use the tool" }] },
		]);
		expect(secondBody.store).toBe(false);
		expect(secondBody.previous_response_id).toBe("resp_1");
		expect(secondBody.input).toEqual([
			{ type: "custom_tool_call_output", call_id: "call_1", output: "real result" },
			{ role: "user", content: [{ type: "input_text", text: "Now finish" }] },
		]);
		expect(getOpenAICodexWebSocketDebugStats("session-1")).toMatchObject({
			requests: 2,
			connectionsCreated: 1,
			connectionsReused: 1,
			cachedContextRequests: 2,
			storeTrueRequests: 0,
			fullContextRequests: 1,
			deltaRequests: 1,
			lastDeltaInputItems: 2,
			lastPreviousResponseId: "resp_1",
		});
	});

	it.each(["websocket", "sse"] as const)(
		"recovers a missing cached websocket continuation via %s",
		async (recoveryTransport) => {
			const token = mockToken();
			const sessionId = `missing-continuation-${recoveryTransport}`;
			const encoder = new TextEncoder();
			const fetchMock = vi.fn(
				async () =>
					new Response(
						new ReadableStream<Uint8Array>({
							start(controller) {
								controller.enqueue(encoder.encode(buildSSEPayload({ status: "completed" })));
								controller.close();
							},
						}),
						{ status: 200, headers: { "content-type": "text/event-stream" } },
					),
			);
			vi.stubGlobal("fetch", fetchMock);
			const sentBodies: Array<{
				connectionId: number;
				input: unknown[];
				previous_response_id?: string;
			}> = [];
			let connections = 0;

			class MockWebSocket {
				static OPEN = 1;
				static CLOSED = 3;
				readyState = MockWebSocket.OPEN;
				private readonly connectionId = ++connections;
				private listeners = new Map<string, Set<(event: unknown) => void>>();

				constructor(_url: string, _protocols?: string | string[] | { headers?: Record<string, string> }) {
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

				send(data: string): void {
					const body = JSON.parse(data) as { input: unknown[]; previous_response_id?: string };
					sentBodies.push({ ...body, connectionId: this.connectionId });
					if (sentBodies.length === 2) {
						this.dispatchEvents([
							{
								type: "codex.rate_limits",
								plan_type: "plus",
								rate_limits: {
									allowed: true,
									limit_reached: false,
									primary: {
										used_percent: 7,
										window_minutes: 10080,
										reset_after_seconds: 556112,
										reset_at: 1785269351,
									},
									secondary: null,
								},
								code_review_rate_limits: null,
								additional_rate_limits: null,
								credits: { has_credits: false, unlimited: false, balance: "0" },
								promo: null,
							},
							{
								type: "error",
								status: 400,
								error: {
									code: "previous_response_not_found",
									message: "Previous response with id 'resp_1' not found.",
									param: "previous_response_id",
								},
							},
						]);
						return;
					}
					if (sentBodies.length === 3 && recoveryTransport === "sse") {
						queueMicrotask(() => this.dispatch("error", { message: "retry websocket failed" }));
						return;
					}

					const response =
						sentBodies.length === 1
							? { responseId: "resp_1", messageId: "msg_1", text: "Hello" }
							: { responseId: "resp_2", messageId: "msg_2", text: "Recovered" };
					this.dispatchEvents([
						{ type: "response.created", response: { id: response.responseId } },
						{
							type: "response.output_item.added",
							output_index: 0,
							item: {
								type: "message",
								id: response.messageId,
								role: "assistant",
								status: "in_progress",
								content: [],
							},
						},
						{
							type: "response.output_item.done",
							output_index: 0,
							item: {
								type: "message",
								id: response.messageId,
								role: "assistant",
								status: "completed",
								content: [{ type: "output_text", text: response.text }],
							},
						},
						{
							type: "response.completed",
							response: {
								id: response.responseId,
								status: "completed",
								usage: { input_tokens: 5, output_tokens: 3, total_tokens: 8 },
							},
						},
					]);
				}

				close(): void {
					this.readyState = MockWebSocket.CLOSED;
				}

				private dispatchEvents(events: unknown[]): void {
					queueMicrotask(() => {
						for (const event of events) {
							this.dispatch("message", { data: JSON.stringify(event) });
						}
					});
				}

				private dispatch(type: string, event: unknown): void {
					for (const listener of this.listeners.get(type) ?? []) {
						listener(event);
					}
				}
			}

			vi.stubGlobal("WebSocket", MockWebSocket);

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
			const firstContext = normalizeContext({
				systemPrompt: "You are a helpful assistant.",
				messages: [{ role: "user", content: "Say hello", timestamp: 1 }],
			});

			const first = await streamOpenAICodexResponses(model, firstContext, {
				apiKey: token,
				sessionId,
				transport: "websocket-cached",
			}).result();
			const secondContext = normalizeContext({
				messages: [...firstContext.messages, first, { role: "user", content: "Now finish", timestamp: 2 }],
			});
			const eventTypes: string[] = [];
			const secondStream = streamOpenAICodexResponses(model, secondContext, {
				apiKey: token,
				sessionId,
				transport: "websocket-cached",
			});
			for await (const event of secondStream) {
				eventTypes.push(event.type);
			}
			const second = await secondStream.result();

			expect(second.stopReason).toBe("stop");
			expect(second.content.find((content) => content.type === "text")?.text).toBe(
				recoveryTransport === "sse" ? "Hello" : "Recovered",
			);
			expect(eventTypes.filter((type) => type === "start")).toHaveLength(1);
			expect(eventTypes).not.toContain("error");
			expect(connections).toBe(2);
			expect(sentBodies).toHaveLength(3);
			expect(sentBodies.map((body) => body.connectionId)).toEqual([1, 1, 2]);
			expect(sentBodies[1].previous_response_id).toBe("resp_1");
			expect(sentBodies[1].input).toEqual([{ role: "user", content: [{ type: "input_text", text: "Now finish" }] }]);
			expect(sentBodies[2].previous_response_id).toBeUndefined();
			expect(sentBodies[2].input).toHaveLength(4);
			expect(sentBodies[2].input.at(-1)).toEqual({
				role: "user",
				content: [{ type: "input_text", text: "Now finish" }],
			});
			expect(fetchMock).toHaveBeenCalledTimes(recoveryTransport === "sse" ? 1 : 0);
			expect(getOpenAICodexWebSocketDebugStats(sessionId)).toMatchObject({
				requests: 3,
				connectionsCreated: 2,
				connectionsReused: 1,
				fullContextRequests: 2,
				deltaRequests: 1,
				websocketFailures: recoveryTransport === "sse" ? 1 : 0,
				sseFallbacks: recoveryTransport === "sse" ? 1 : 0,
			});
		},
	);

	it.each([
		["retry-after-ms", () => ({ "content-type": "application/json", "retry-after-ms": "1500" }), 1500],
		["retry-after seconds", () => ({ "content-type": "application/json", "retry-after": "60" }), 60_000],
		[
			"retry-after HTTP date",
			() => ({ "content-type": "application/json", "retry-after": new Date(Date.now() + 45_000).toUTCString() }),
			45_000,
		],
	] as const)("uses %s for SSE retries", async (_name, makeHeaders, expectedDelay) => {
		vi.useFakeTimers();
		vi.setSystemTime(new Date("2026-05-13T00:00:00Z"));
		const setTimeoutSpy = vi.spyOn(globalThis, "setTimeout");
		const token = mockToken();
		const encoder = new TextEncoder();
		const sse = buildSSEPayload({ status: "completed" });
		let codexRequests = 0;

		const fetchMock = vi.fn(async (input: string | URL) => {
			const url = typeof input === "string" ? input : input.toString();
			if (url !== "https://chatgpt.com/backend-api/codex/responses") {
				throw new Error(`Unexpected URL: ${url}`);
			}

			codexRequests++;
			if (codexRequests === 1) {
				return new Response(JSON.stringify({ error: { code: "rate_limit_exceeded", message: "rate limited" } }), {
					status: 429,
					headers: makeHeaders(),
				});
			}

			return new Response(
				new ReadableStream<Uint8Array>({
					start(controller) {
						controller.enqueue(encoder.encode(sse));
						controller.close();
					},
				}),
				{ status: 200, headers: { "content-type": "text/event-stream" } },
			);
		});
		vi.stubGlobal("fetch", fetchMock);

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
		const context = normalizeContext({
			systemPrompt: "You are a helpful assistant.",
			messages: [{ role: "user", content: "Say hello", timestamp: Date.now() }],
		});

		const resultPromise = streamOpenAICodexResponses(model, context, {
			apiKey: token,
			transport: "sse",
			maxRetries: 1,
		}).result();
		await vi.advanceTimersByTimeAsync(0);
		expect(setTimeoutSpy).toHaveBeenCalledWith(expect.any(Function), expectedDelay);

		await vi.advanceTimersToNextTimerAsync();
		const result = await resultPromise;
		expect(result.content.find((content) => content.type === "text")?.text).toBe("Hello");
		expect(codexRequests).toBe(2);
	});

	it.each([429, 503])("fails immediately when a %i retry delay exceeds the limit", async (status) => {
		const token = mockToken();
		const fetchMock = vi.fn(
			async () =>
				new Response(JSON.stringify({ error: { code: "temporarily_unavailable", message: "retry later" } }), {
					status,
					headers: { "content-type": "application/json", "retry-after": "2" },
				}),
		);
		vi.stubGlobal("fetch", fetchMock);

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
		const context = normalizeContext({
			systemPrompt: "You are a helpful assistant.",
			messages: [{ role: "user", content: "Say hello", timestamp: Date.now() }],
		});

		const result = await streamOpenAICodexResponses(model, context, {
			apiKey: token,
			transport: "sse",
			maxRetries: 3,
			maxRetryDelayMs: 1000,
		}).result();

		expect(result.stopReason).toBe("error");
		expect(result.errorMessage).toBe("Server requested 2s retry delay (max: 1s)");
		expect(fetchMock).toHaveBeenCalledTimes(1);
	});

	it("zstd-compresses SSE request bodies", async () => {
		const token = mockToken();
		const encoder = new TextEncoder();
		const sse = buildSSEPayload({ status: "completed" });

		let capturedEncoding: string | null = null;
		let capturedBody: Uint8Array | string | undefined;

		const fetchMock = vi.fn(async (input: string | URL, init?: RequestInit) => {
			const url = typeof input === "string" ? input : input.toString();
			if (url !== "https://chatgpt.com/backend-api/codex/responses") {
				throw new Error(`Unexpected URL: ${url}`);
			}
			const headers = init?.headers instanceof Headers ? init.headers : undefined;
			capturedEncoding = headers?.get("content-encoding") ?? null;
			capturedBody = init?.body as Uint8Array | string | undefined;
			return new Response(
				new ReadableStream<Uint8Array>({
					start(controller) {
						controller.enqueue(encoder.encode(sse));
						controller.close();
					},
				}),
				{ status: 200, headers: { "content-type": "text/event-stream" } },
			);
		});
		vi.stubGlobal("fetch", fetchMock);

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

		const largeText = "compress me ".repeat(400);
		await streamOpenAICodexResponses(
			model,
			normalizeContext({
				systemPrompt: "You are a helpful assistant.",
				messages: [{ role: "user", content: largeText, timestamp: 1 }],
			}),
			{ apiKey: token, transport: "sse" },
		).result();

		expect(capturedEncoding).toBe("zstd");
		expect(capturedBody).toBeInstanceOf(Uint8Array);
		const decoded = JSON.parse(Buffer.from(zstdDecompressSync(capturedBody as Uint8Array)).toString("utf8")) as {
			input: Array<{ content: Array<{ text: string }> }>;
		};
		expect(decoded.input[1].content[0].text).toBe(largeText);

		capturedEncoding = null;
		capturedBody = undefined;
		await streamOpenAICodexResponses(
			model,
			normalizeContext({
				systemPrompt: "You are a helpful assistant.",
				messages: [{ role: "user", content: "hi", timestamp: 1 }],
			}),
			{ apiKey: token, transport: "sse" },
		).result();

		expect(capturedEncoding).toBe("zstd");
		expect(capturedBody).toBeInstanceOf(Uint8Array);
	});

	it("uses exponential backoff across repeated SSE retries without retry headers", async () => {
		vi.useFakeTimers();
		vi.setSystemTime(new Date("2026-05-13T00:00:00Z"));
		const setTimeoutSpy = vi.spyOn(globalThis, "setTimeout");
		const token = mockToken();
		const encoder = new TextEncoder();
		const sse = buildSSEPayload({ status: "completed" });
		let codexRequests = 0;

		const fetchMock = vi.fn(async (input: string | URL) => {
			const url = typeof input === "string" ? input : input.toString();
			if (url !== "https://chatgpt.com/backend-api/codex/responses") {
				throw new Error(`Unexpected URL: ${url}`);
			}

			codexRequests++;
			if (codexRequests <= 3) {
				return new Response(JSON.stringify({ error: { code: "rate_limit_exceeded", message: "rate limited" } }), {
					status: 429,
					headers: { "content-type": "application/json" },
				});
			}

			return new Response(
				new ReadableStream<Uint8Array>({
					start(controller) {
						controller.enqueue(encoder.encode(sse));
						controller.close();
					},
				}),
				{ status: 200, headers: { "content-type": "text/event-stream" } },
			);
		});
		vi.stubGlobal("fetch", fetchMock);

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
		const context = normalizeContext({
			systemPrompt: "You are a helpful assistant.",
			messages: [{ role: "user", content: "Say hello", timestamp: Date.now() }],
		});

		const retryTimeoutDelays = () =>
			setTimeoutSpy.mock.calls
				.map((call) => call[1])
				.filter((delay): delay is number => delay === 1000 || delay === 2000 || delay === 4000);

		const resultPromise = streamOpenAICodexResponses(model, context, {
			apiKey: token,
			transport: "sse",
			maxRetries: 3,
		}).result();
		await vi.advanceTimersByTimeAsync(0);
		expect(retryTimeoutDelays()).toEqual([1000]);

		await vi.advanceTimersToNextTimerAsync();
		expect(retryTimeoutDelays()).toEqual([1000, 2000]);

		await vi.advanceTimersToNextTimerAsync();
		expect(retryTimeoutDelays()).toEqual([1000, 2000, 4000]);

		await vi.advanceTimersToNextTimerAsync();
		const result = await resultPromise;
		expect(result.content.find((content) => content.type === "text")?.text).toBe("Hello");
		expect(codexRequests).toBe(4);
	});
});

describe("openai-codex request reuse", () => {
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

	const usage = {
		input: 1,
		output: 1,
		cacheRead: 0,
		cacheWrite: 0,
		totalTokens: 2,
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
	};

	/** One turn of a conversation: a question, a reply with reasoning and a tool call, and a long tool result. */
	function turn(n: number): Message[] {
		const reply: AssistantMessage = {
			role: "assistant",
			content: [
				{
					type: "thinking",
					thinking: `thought ${n}`,
					thinkingSignature: JSON.stringify({
						type: "reasoning",
						id: `rs_${n}`,
						summary: [{ type: "summary_text", text: `thought ${n}` }],
						encrypted_content: "gAAAA".padEnd(2000, "x"),
					}),
				},
				{ type: "text", text: `Reading f${n}.ts → "${n}"`, textSignature: `{"v":1,"id":"msg_${n}"}` },
				{ type: "toolCall", id: `call_${n}|fc_${n}`, name: "read", arguments: { path: `f${n}.ts` } },
			],
			api: "openai-codex-responses",
			provider: "openai-codex",
			model: "gpt-5.1-codex",
			usage,
			stopReason: "toolUse",
			timestamp: n,
		};
		const result: ToolResultMessage = {
			role: "toolResult",
			toolCallId: `call_${n}|fc_${n}`,
			toolName: "read",
			content: [{ type: "text", text: `const s${n} = "naïve \\ \t 漢字 \u{1F600} \ud800";\n`.repeat(60) }],
			isError: false,
			timestamp: n,
		};
		return [{ role: "user", content: `Question ${n}`, timestamp: n }, reply, result];
	}

	function sseResponse(): Response {
		return new Response(buildSSEPayload({ status: "completed" }), {
			status: 200,
			headers: { "content-type": "text/event-stream" },
		});
	}

	it("sends the zstd of JSON.stringify(body) over SSE while it reuses converted messages", async () => {
		const sent: Uint8Array[] = [];
		vi.stubGlobal(
			"fetch",
			vi.fn(async (_input: string | URL, init?: RequestInit) => {
				sent.push(init?.body as Uint8Array);
				return sseResponse();
			}),
		);
		const token = mockToken();
		const sessionId = "reuse-sse";
		const messages: Message[] = [];
		const before = requestCacheStats();
		for (let request = 0; request < 5; request++) {
			messages.push(...turn(request));
			const context = normalizeContext({ systemPrompt: "You are a helpful assistant.", messages: [...messages] });
			// The reference: a payload hook (which turns the reuse off) records the body as JSON.stringify gives it.
			let json = "";
			await streamOpenAICodexResponses(model, context, {
				apiKey: token,
				sessionId,
				transport: "sse",
				onPayload: (payload) => {
					json = JSON.stringify(payload);
					return undefined;
				},
			}).result();
			// The same messages, which the caller says are never changed in place: converted messages are reused.
			await streamOpenAICodexResponses(model, context, {
				apiKey: token,
				sessionId,
				transport: "sse",
				immutableMessages: true,
			}).result();
			const [reference, reused] = sent.splice(0, 2);
			const expected = zstdCompressSync(Buffer.from(json), {
				params: { [zlibConstants.ZSTD_c_compressionLevel]: 3 },
			});
			expect(Buffer.from(reference).equals(expected), `request ${request}: reference`).toBe(true);
			expect(Buffer.from(reused).equals(expected), `request ${request}: reused`).toBe(true);
		}
		// Not vacuous: messages of the earlier turns were reused.
		expect(requestCacheStats().units - before.units).toBeGreaterThan(5);
	});

	it("counts the UTF-8 bytes of the body it then sends over SSE when the WebSocket fails", async () => {
		const sent: Uint8Array[] = [];
		vi.stubGlobal(
			"fetch",
			vi.fn(async (_input: string | URL, init?: RequestInit) => {
				sent.push(init?.body as Uint8Array);
				return sseResponse();
			}),
		);
		class FailingWebSocket {
			private listeners = new Map<string, Set<(event: unknown) => void>>();
			constructor(_url: string, _protocols?: unknown) {
				queueMicrotask(() => {
					for (const listener of this.listeners.get("error") ?? []) listener({ message: "refused" });
				});
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
			send(): void {}
			close(): void {}
		}
		vi.stubGlobal("WebSocket", FailingWebSocket);
		const result = await streamOpenAICodexResponses(
			model,
			normalizeContext({ systemPrompt: "You are a helpful assistant.", messages: turn(1) }),
			{ apiKey: mockToken(), sessionId: "fallback-bytes", transport: "auto", immutableMessages: true },
		).result();
		expect(sent).toHaveLength(1);
		const json = zstdDecompressSync(sent[0]);
		// Not ASCII: the count is of bytes, not characters.
		expect(json.byteLength).toBeGreaterThan(json.toString("utf8").length);
		expect(result.diagnostics?.[0]?.details).toMatchObject({
			fallbackTransport: "sse",
			requestBytes: json.byteLength,
		});
	});

	/** A WebSocket that answers every request with an empty completed response `resp_<n>`. */
	function stubWebSocket(sent: Array<{ input: unknown[]; previous_response_id?: string }>) {
		let closeFromPeer = () => {};
		class MockWebSocket {
			static OPEN = 1;
			readyState = MockWebSocket.OPEN;
			private listeners = new Map<string, Set<(event: unknown) => void>>();

			constructor(_url: string, _protocols?: unknown) {
				closeFromPeer = () => {
					this.readyState = 3;
					this.dispatch("close", { code: 1000 });
				};
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

			send(data: string): void {
				sent.push(JSON.parse(data));
				const id = `resp_${sent.length}`;
				queueMicrotask(() => {
					for (const event of [
						{ type: "response.created", response: { id } },
						{ type: "response.completed", response: { id, status: "completed", usage: { input_tokens: 1 } } },
					]) {
						this.dispatch("message", { data: JSON.stringify(event) });
					}
				});
			}

			close(): void {
				this.readyState = 3;
			}

			private dispatch(type: string, event: unknown): void {
				for (const listener of this.listeners.get(type) ?? []) listener(event);
			}
		}
		vi.stubGlobal("WebSocket", MockWebSocket);
		return () => closeFromPeer();
	}

	it("releases the idle expiry timer when the server closes and reconnects with full history", async () => {
		vi.useFakeTimers();
		try {
			const sent: Array<{ input: unknown[]; previous_response_id?: string }> = [];
			const closeFromPeer = stubWebSocket(sent);
			const baseTimers = vi.getTimerCount();
			const request = () =>
				streamOpenAICodexResponses(model, normalizeContext({ messages: turn(1) }), {
					apiKey: mockToken(),
					sessionId: "peer-close",
					transport: "websocket-cached",
				}).result();
			expect((await request()).stopReason).toBe("stop");
			expect(vi.getTimerCount()).toBe(baseTimers + 1);
			closeFromPeer();
			expect(vi.getTimerCount()).toBe(baseTimers);
			expect((await request()).stopReason).toBe("stop");
			expect(sent[1]).toEqual(sent[0]);
			closeFromPeer();
			expect(vi.getTimerCount()).toBe(baseTimers);
		} finally {
			vi.useRealTimers();
		}
	});

	it("keeps one idle cache entry when two connections start together", async () => {
		vi.useFakeTimers();
		try {
			const sent: Array<{ input: unknown[]; previous_response_id?: string }> = [];
			stubWebSocket(sent);
			const start = vi.getTimerCount();
			const request = () =>
				streamOpenAICodexResponses(model, normalizeContext({ messages: turn(1) }), {
					apiKey: mockToken(),
					sessionId: "concurrent-open",
					transport: "websocket-cached",
				}).result();
			const replies = await Promise.all([request(), request()]);
			expect(replies.map((reply) => reply.stopReason)).toEqual(["stop", "stop"]);
			expect(sent.map((frame) => frame.previous_response_id)).toEqual([undefined, undefined]);
			expect(vi.getTimerCount()).toBe(start + 1);
			await request();
			expect(sent[2].previous_response_id).toBe("resp_1");
			closeOpenAICodexWebSocketSessions("concurrent-open");
			expect(vi.getTimerCount()).toBe(start);
		} finally {
			vi.useRealTimers();
		}
	});
	it("starts a full request when a shared tool schema changes after sending", async () => {
		const sent: Array<{ input: unknown[]; previous_response_id?: string }> = [];
		stubWebSocket(sent);
		const schema = Type.Object({ path: Type.String({ description: "original" }) });
		for (const description of ["original", "edited", "edited"]) {
			Object.assign(schema.properties.path, { description });
			await streamOpenAICodexResponses(
				model,
				normalizeContext({
					messages: turn(1),
					tools: [{ name: "lookup", description: "Lookup", parameters: schema }],
				}),
				{ apiKey: mockToken(), sessionId: "schema", transport: "websocket-cached" },
			).result();
		}
		expect(sent.map((r) => r.previous_response_id)).toEqual([undefined, undefined, "resp_2"]);
	});

	it("replays without consuming or replacing the conversation's continuation", async () => {
		const sent: Array<{ input: unknown[]; previous_response_id?: string }> = [];
		stubWebSocket(sent);
		for (const replay of [false, true, false]) {
			await streamOpenAICodexResponses(model, normalizeContext({ messages: turn(0) }), {
				apiKey: mockToken(),
				sessionId: "replay",
				transport: "websocket-cached",
				disableContinuation: replay,
			}).result();
		}
		expect(sent.map((r) => r.previous_response_id)).toEqual([undefined, undefined, "resp_1"]);
	});

	it.each(["input", "instructions"])("remembers the %s that a payload hook actually sent", async (field) => {
		const sent: Array<{ input: unknown[]; previous_response_id?: string }> = [];
		stubWebSocket(sent);
		const shared = { role: "user", content: "original" };
		const payload = { instructions: "original", input: [shared] };
		for (const text of ["original", "edited", "edited"]) {
			if (field === "input") shared.content = text;
			else payload.instructions = text;
			await streamOpenAICodexResponses(model, normalizeContext({ messages: turn(0) }), {
				apiKey: mockToken(),
				sessionId: "mutable-hook",
				transport: "websocket-cached",
				onPayload: () => payload,
			}).result();
		}
		expect(sent[1].previous_response_id).toBeUndefined();
		expect(sent[1]).toMatchObject(
			field === "input" ? { input: [{ content: "edited" }] } : { instructions: "edited" },
		);
		expect(sent[2].previous_response_id).toBe("resp_2");
	});

	it.each([
		{
			name: "a marker in the instructions",
			payload: { instructions: "\0pi-codex-ws-input", input: [] },
			wire: { instructions: "\0pi-codex-ws-input" },
		},
		{
			name: "a root serializer",
			payload: { input: [], toJSON: (key: string) => ({ input: [key] }) },
			wire: { input: [""] },
		},
		{
			name: "an array serializer",
			payload: { input: Object.assign([], { toJSON: (key: string) => [key] }) },
			wire: { input: ["input"] },
		},
		{
			name: "an explicit response id",
			payload: { previous_response_id: "external", input: [] },
			wire: { previous_response_id: "external" },
		},
	])("preserves $name and restarts automatic continuation afterwards", async ({ payload, wire }) => {
		const sent: Array<{ input: unknown[]; previous_response_id?: string }> = [];
		stubWebSocket(sent);
		for (const value of [{ input: [] }, payload, { input: [] }, { input: [] }]) {
			await streamOpenAICodexResponses(model, normalizeContext({ messages: turn(0) }), {
				apiKey: mockToken(),
				sessionId: "custom-frame",
				transport: "websocket-cached",
				onPayload: () => value,
			}).result();
		}
		expect(sent).toHaveLength(4);
		expect(sent[1]).toMatchObject(wire);
		expect(sent[2].previous_response_id).toBeUndefined();
		expect(sent[3].previous_response_id).toBe("resp_3");
	});

	it("keeps a detached history when a hook removes itself during its callback", async () => {
		const sent: Array<{ input: unknown[]; previous_response_id?: string }> = [];
		stubWebSocket(sent);
		const item = { role: "user", content: "original" };
		const options: NonNullable<Parameters<typeof streamOpenAICodexResponses>[2]> = {
			apiKey: mockToken(),
			sessionId: "self-removing-hook",
			transport: "websocket-cached",
		};
		for (const text of ["original", "edited", "edited"]) {
			item.content = text;
			options.onPayload = () => {
				options.onPayload = undefined;
				return { input: [item] };
			};
			await streamOpenAICodexResponses(model, normalizeContext({ messages: turn(0) }), options).result();
		}
		expect(sent.map((r) => r.previous_response_id)).toEqual([undefined, undefined, "resp_2"]);
	});

	it("runs input and metadata serializers in frame property order", async () => {
		const sent: Array<{ input: unknown[]; previous_response_id?: string }> = [];
		stubWebSocket(sent);
		let read = false;
		const payload = {
			instructions: { toJSON: () => (read ? "too late" : "before") },
			input: [
				{
					toJSON: () => {
						read = true;
						return "message";
					},
				},
			],
			user: { toJSON: () => (read ? "after" : "too early") },
		};
		await streamOpenAICodexResponses(model, normalizeContext({ messages: turn(0) }), {
			apiKey: mockToken(),
			sessionId: "serializer-order",
			transport: "websocket-cached",
			onPayload: () => payload,
		}).result();
		expect(sent[0]).toMatchObject({ instructions: "before", input: ["message"], user: "after" });
	});

	it("sends a full frame when a hook changes a raw number beyond JS integer precision", async () => {
		const sent: Array<{ input: unknown[]; previous_response_id?: string }> = [];
		stubWebSocket(sent);
		const { rawJSON } = JSON as unknown as { rawJSON: (text: string) => unknown };
		for (const n of ["9007199254740993", "9007199254740992", "9007199254740992"]) {
			await streamOpenAICodexResponses(model, normalizeContext({ messages: turn(0) }), {
				apiKey: mockToken(),
				sessionId: "raw",
				transport: "websocket-cached",
				onPayload: () => ({ input: [rawJSON(n)] }),
			}).result();
		}
		expect(sent.map((r) => r.previous_response_id)).toEqual([undefined, undefined, "resp_2"]);
	});

	it("serializes a hook item's toJSON at its full conversation index", async () => {
		const sent: Array<{ input: unknown[]; previous_response_id?: string }> = [];
		stubWebSocket(sent);
		const item = { toJSON: (key: string) => key };
		for (const input of [[undefined], [undefined, item], [undefined, item]]) {
			await streamOpenAICodexResponses(model, normalizeContext({ messages: turn(0) }), {
				apiKey: mockToken(),
				sessionId: "item-serializer",
				transport: "websocket-cached",
				onPayload: () => ({ input }),
			}).result();
		}
		expect(sent.map((r) => r.input)).toEqual([[null], ["1"], []]);
		expect(sent.map((r) => r.previous_response_id)).toEqual([undefined, "resp_1", "resp_2"]);
	});

	it.each([
		{ name: "the same message objects, converted once and reused", edit: undefined, delta: true },
		// JSON.stringify leaves out a property whose value is undefined: the input is the same.
		{
			name: "an earlier item with a property that is undefined",
			edit: (item: Record<string, unknown>) => {
				item.extra = undefined;
			},
			delta: true,
		},
		{
			name: "an earlier item changed",
			// (The first item is the system prompt, sent as a developer item since Pi 1.6.0.)
			edit: (item: Record<string, unknown>) => {
				item.role = "user";
			},
			delta: false,
		},
	])("compares the continued input as JSON: $name", async ({ edit, delta }) => {
		const sent: Array<{ input: unknown[]; previous_response_id?: string }> = [];
		stubWebSocket(sent);
		const token = mockToken();
		const sessionId = `reuse-ws-${delta}-${edit === undefined}`;
		const messages: Message[] = [];
		const before = requestCacheStats();
		for (let request = 0; request < 3; request++) {
			messages.push(...turn(request));
			const context = normalizeContext({ systemPrompt: "You are a helpful assistant.", messages: [...messages] });
			await streamOpenAICodexResponses(model, context, {
				apiKey: token,
				sessionId,
				transport: "websocket-cached",
				immutableMessages: true,
				onPayload:
					edit && request === 2
						? (payload) => {
								edit((payload as { input: Record<string, unknown>[] }).input[0]);
								return undefined;
							}
						: undefined,
			}).result();
		}
		expect(sent).toHaveLength(3);
		expect(sent[0].previous_response_id).toBeUndefined();
		// The second request continues the first (whose reply was empty): the new turn's five items.
		expect(sent[1].previous_response_id).toBe("resp_1");
		expect(sent[1].input).toHaveLength(5);
		if (delta) {
			expect(sent[2].previous_response_id).toBe("resp_2");
			expect(sent[2].input).toHaveLength(5);
		} else {
			expect(sent[2].previous_response_id).toBeUndefined();
			// The system prompt's developer item and the three turns' five items each.
			expect(sent[2].input).toHaveLength(16);
		}
		if (edit === undefined) expect(requestCacheStats().units - before.units).toBeGreaterThan(0);
	});
});
