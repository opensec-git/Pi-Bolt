import { createServer, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";
import { afterEach, describe, expect, it, vi } from "vitest";
import { stream as streamAnthropic } from "../src/api/anthropic-messages.ts";
import type { FetchFunction, Model } from "../src/types.ts";
import { isRetryableAssistantError } from "../src/utils/retry.ts";
import { normalizeContext } from "../src/utils/transcript.ts";

// #10019: Anthropic subscription requests can get a 200 followed only by `ping` events, for minutes. The pings reset
// the HTTP idle timeout, so the turn hung without an error and auto retry never ran.

const context = normalizeContext({ messages: [{ role: "user", content: "hello", timestamp: 1 }] });

function anthropicModel(baseUrl: string): Model<"anthropic-messages"> {
	return {
		id: "claude-test",
		name: "Claude Test",
		api: "anthropic-messages",
		provider: "anthropic",
		baseUrl,
		reasoning: false,
		input: ["text"],
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
		contextWindow: 200000,
		maxTokens: 1000,
	};
}

const PING = 'event: ping\ndata: {"type": "ping"}\n\n';

const frame = (event: Record<string, unknown> & { type: string }) =>
	`event: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`;

const reply = [
	frame({
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
	}),
	frame({ type: "content_block_start", index: 0, content_block: { type: "text", text: "" } }),
	frame({ type: "content_block_delta", index: 0, delta: { type: "text_delta", text: "Hi" } }),
	frame({ type: "content_block_stop", index: 0 }),
	frame({
		type: "message_delta",
		delta: { stop_reason: "end_turn", stop_sequence: null },
		usage: { output_tokens: 1 },
	}),
	frame({ type: "message_stop" }),
];

/** A server that answers 200, writes `steps` (frames, or a pause in ms) in order and then ends the response. */
async function sseServer(steps: (string | number)[]) {
	const responses: ServerResponse[] = [];
	const server = createServer(async (request, response) => {
		request.resume();
		responses.push(response);
		response.writeHead(200, { "content-type": "text/event-stream" });
		for (const step of steps) {
			if (typeof step === "number") await new Promise((resolve) => setTimeout(resolve, step));
			else if (!response.destroyed) response.write(step);
		}
		response.end();
	});
	await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
	return {
		baseUrl: `http://127.0.0.1:${(server.address() as AddressInfo).port}`,
		async close() {
			for (const response of responses) response.destroy();
			server.closeAllConnections();
			await new Promise<void>((resolve) => server.close(() => resolve()));
		},
	};
}

describe("anthropic-messages: a response that sends only pings (#10019)", () => {
	afterEach(() => {
		vi.useRealTimers();
	});

	it("fails with a retryable timeout when no message event follows the headers within the request timeout", async () => {
		const pings = Array.from({ length: 40 }, (_, i) => (i % 2 === 0 ? PING : 50));
		const server = await sseServer(pings);
		try {
			const started = Date.now();
			const result = await streamAnthropic(anthropicModel(server.baseUrl), context, {
				apiKey: "test-key",
				cacheRetention: "none",
				timeoutMs: 300,
			}).result();
			expect(result.stopReason).toBe("error");
			expect(result.errorMessage).toContain("timed out");
			expect(isRetryableAssistantError(result)).toBe(true);
			expect(Date.now() - started).toBeLessThan(1500);
		} finally {
			await server.close();
		}
	});

	it("leaves gaps after the first message event to the HTTP idle timeout", async () => {
		const [start, ...rest] = reply;
		const server = await sseServer([start, PING, 200, PING, 200, PING, 200, ...rest]);
		try {
			const result = await streamAnthropic(anthropicModel(server.baseUrl), context, {
				apiKey: "test-key",
				cacheRetention: "none",
				timeoutMs: 300,
			}).result();
			expect(result.stopReason).toBe("stop");
			expect(result.content).toEqual([{ type: "text", text: "Hi" }]);
		} finally {
			await server.close();
		}
	});

	it("waits at most 60 s on api.anthropic.com, also without a request timeout", async () => {
		vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
		const encoder = new TextEncoder();
		let cancelled = false;
		const fetch: FetchFunction = async (_url, init) => {
			const body = new ReadableStream<Uint8Array>({
				start(controller) {
					controller.enqueue(encoder.encode(PING));
					init?.signal?.addEventListener("abort", () => {
						cancelled = true;
						controller.error(new DOMException("aborted", "AbortError"));
					});
				},
			});
			return new Response(body, { status: 200, headers: { "content-type": "text/event-stream" } });
		};
		const pending = streamAnthropic(anthropicModel("https://api.anthropic.com"), context, {
			apiKey: "test-key",
			cacheRetention: "none",
			fetch,
		}).result();
		await vi.advanceTimersByTimeAsync(59_000);
		expect(cancelled).toBe(false);
		await vi.advanceTimersByTimeAsync(1_000);
		const result = await pending;
		expect(cancelled).toBe(true);
		expect(result.stopReason).toBe("error");
		expect(result.errorMessage).toContain("for 60 s after the response headers (timed out)");
	});
});
