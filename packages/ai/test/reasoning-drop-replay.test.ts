// #346: reasoning a provider removed for good after a change of the request head is not replayed again.
import { zstdDecompressSync } from "node:zlib";
import { describe, expect, it } from "vitest";
import { completeSimple, getModel } from "../src/compat.ts";
import type { Api, AssistantMessage, Context, Model, SimpleStreamOptions } from "../src/types.ts";
import { shortHash } from "../src/utils/hash.ts";
import { requestCacheStats } from "../src/utils/request-cache.ts";

/**
 * A fake provider with the binding rule of #346: a replayed reasoning block binds while the request head (instructions
 * and tools) is the one it was minted under. Otherwise the provider removes it and reports it, OpenAI by item id,
 * Anthropic by its place in the request. Every reply mints one new block.
 */
interface FakeProvider {
	fetch: typeof globalThis.fetch;
	/** Per request: the reasoning blocks sent, by name. */
	sent: string[][];
	/** Per request: the reasoning blocks reported as removed, by name. */
	removed: string[][];
}

type Body = Record<string, unknown>;

async function readBody(input: string | URL | Request, init: RequestInit | undefined): Promise<Body> {
	const request = input instanceof Request ? input : new Request(input, init);
	const bytes = new Uint8Array(await request.arrayBuffer());
	// Codex compresses its request bodies.
	const text =
		request.headers.get("content-encoding") === "zstd"
			? zstdDecompressSync(bytes).toString()
			: new TextDecoder().decode(bytes);
	return JSON.parse(text) as Body;
}

function sse(events: unknown[], named = false): Response {
	const text = events
		.map((event) =>
			named
				? `event: ${(event as { type: string }).type}\ndata: ${JSON.stringify(event)}\n\n`
				: `data: ${JSON.stringify(event)}\n\n`,
		)
		.join("");
	return new Response(text, { status: 200, headers: { "content-type": "text/event-stream" } });
}

function fakeOpenAI(): FakeProvider {
	const minted = new Map<string, string>();
	const provider: FakeProvider = { sent: [], removed: [], fetch: undefined as unknown as typeof globalThis.fetch };
	provider.fetch = async (input, init) => {
		const body = await readBody(input, init);
		const items = body.input as Array<{ type?: string; role?: string; id?: string; content?: unknown }>;
		const leading = items.filter((item, index) => index < 2 && (item.role === "developer" || item.role === "system"));
		const head = JSON.stringify([body.instructions, body.tools, leading.map((item) => item.content)]);
		const sent: string[] = [];
		const removed: string[] = [];
		for (const item of items) {
			if (item.type !== "reasoning" || item.id === undefined) continue;
			sent.push(item.id);
			if (minted.get(item.id) !== head) removed.push(item.id);
		}
		provider.sent.push(sent);
		provider.removed.push(removed);
		const n = provider.sent.length;
		const id = `rs_${n}`;
		minted.set(id, head);
		return sse([
			{
				type: "response.output_item.done",
				output_index: 0,
				sequence_number: 0,
				item: {
					type: "reasoning",
					id,
					summary: [{ type: "summary_text", text: `thought ${n}` }],
					encrypted_content: `enc_${n}`,
				},
			},
			{
				type: "response.output_item.done",
				output_index: 1,
				sequence_number: 1,
				item: {
					type: "message",
					id: `msg_${n}`,
					role: "assistant",
					status: "completed",
					content: [{ type: "output_text", text: `answer ${n}`, annotations: [] }],
				},
			},
			{
				type: "response.completed",
				sequence_number: 2,
				response: {
					id: `resp_${n}`,
					status: "completed",
					output: [],
					usage: { input_tokens: 10, output_tokens: 5, total_tokens: 15 },
					input_transformations: removed.map((removedId) => ({
						type: "reasoning",
						id: removedId,
						action: "removed",
						reason: "prefix_mismatch",
					})),
				},
			},
		]);
	};
	return provider;
}

type AnthropicMessage = { role: string; content: string | Array<{ type: string; signature?: string }> };

/** Also keeps message threads: a `continue` sends only the messages after the thread's last reply. */
function fakeAnthropic(): FakeProvider {
	const minted = new Map<string, string>();
	const threads = new Map<string, { head: string; messages: AnthropicMessage[] }>();
	const provider: FakeProvider = { sent: [], removed: [], fetch: undefined as unknown as typeof globalThis.fetch };
	provider.fetch = async (input, init) => {
		const body = await readBody(input, init);
		const thread = body.thread as { type: string; previous_message_id?: string } | undefined;
		const held = thread?.type === "continue" ? threads.get(thread.previous_message_id ?? "") : undefined;
		if (thread?.type === "continue" && !held) throw new Error("unknown thread");
		const head = held?.head ?? JSON.stringify([body.system, body.tools]);
		const messages = body.messages as AnthropicMessage[];
		const conversation = [...(held?.messages ?? []), ...messages];
		const sent: string[] = [];
		const removed: string[] = [];
		const paths: string[] = [];
		conversation.forEach((message, i) => {
			if (typeof message.content === "string") return;
			message.content.forEach((block, j) => {
				if (block.type !== "thinking" || block.signature === undefined) return;
				sent.push(block.signature);
				// The thread's messages were checked when they were sent; paths index the messages of this request.
				if (held && i < held.messages.length) return;
				if (minted.get(block.signature) === head) return;
				removed.push(block.signature);
				paths.push(`messages.${i - (held?.messages.length ?? 0)}.content.${j}`);
			});
		});
		provider.sent.push(sent);
		provider.removed.push(removed);
		const n = provider.sent.length;
		const signature = `sig_${n}`;
		minted.set(signature, head);
		if (thread) {
			threads.set(`msg_${n}`, {
				head,
				messages: [
					...conversation,
					{ role: "assistant", content: [{ type: "thinking", signature }, { type: "text" }] },
				],
			});
		}
		const usage = { input_tokens: 10, output_tokens: 0, cache_read_input_tokens: 0, cache_creation_input_tokens: 0 };
		return sse(
			[
				{
					type: "message_start",
					message: {
						id: `msg_${n}`,
						type: "message",
						role: "assistant",
						model: body.model,
						content: [],
						usage,
						input_transformations: paths.map((path) => ({
							type: "thinking_dropped",
							path,
							reason: "prefix_binding_mismatch",
						})),
					},
				},
				{ type: "content_block_start", index: 0, content_block: { type: "thinking", thinking: "" } },
				{ type: "content_block_delta", index: 0, delta: { type: "thinking_delta", thinking: `thought ${n}` } },
				{ type: "content_block_delta", index: 0, delta: { type: "signature_delta", signature } },
				{ type: "content_block_stop", index: 0 },
				{ type: "content_block_start", index: 1, content_block: { type: "text", text: "" } },
				{ type: "content_block_delta", index: 1, delta: { type: "text_delta", text: `answer ${n}` } },
				{ type: "content_block_stop", index: 1 },
				{ type: "message_delta", delta: { stop_reason: "end_turn" }, usage: { ...usage, output_tokens: 5 } },
				{ type: "message_stop" },
			],
			true,
		);
	};
	return provider;
}

const azureModel: Model<"azure-openai-responses"> = {
	id: "gpt-6.2-sol",
	name: "GPT-6.2 Sol",
	api: "azure-openai-responses",
	provider: "azure-openai-responses",
	baseUrl: "https://example.openai.azure.com/openai/v1",
	reasoning: true,
	input: ["text"],
	cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
	contextWindow: 272000,
	maxTokens: 128000,
};

const codexToken = `aaa.${Buffer.from(JSON.stringify({ "https://api.openai.com/auth": { chatgpt_account_id: "acc_test" } })).toString("base64")}.bbb`;

interface Target {
	name: string;
	model: Model<Api>;
	fake: () => FakeProvider;
	options?: SimpleStreamOptions;
}

const targets: Target[] = [
	{ name: "OpenAI Responses", model: getModel("openai", "gpt-6.2-sol"), fake: fakeOpenAI },
	{ name: "Azure OpenAI Responses", model: azureModel, fake: fakeOpenAI },
	{ name: "Copilot over OpenAI Responses", model: getModel("github-copilot", "gpt-5.6-sol"), fake: fakeOpenAI },
	{
		name: "Codex over SSE",
		model: getModel("openai-codex", "gpt-6.2-sol"),
		fake: fakeOpenAI,
		options: { apiKey: codexToken, transport: "sse" },
	},
	{ name: "Anthropic Messages", model: getModel("anthropic", "claude-fable-5-1"), fake: fakeAnthropic },
	{
		name: "Copilot over Anthropic Messages",
		model: getModel("github-copilot", "claude-haiku-5.5"),
		fake: fakeAnthropic,
	},
];

/** The blocks a reply's diagnostics report as removed. */
function reported(message: AssistantMessage): number {
	let count = 0;
	for (const diagnostic of message.diagnostics ?? []) {
		if (diagnostic.type !== "openai_input_transformations" && diagnostic.type !== "anthropic_input_transformations")
			continue;
		count += (diagnostic.details?.transformations as unknown[]).length;
	}
	return count;
}

/**
 * The repro of #346: three turns under one head, then the instructions change, then four more turns. Runs once with the
 * request cache (as the coding agent sends: a session id and immutable messages) and once without.
 */
async function runSession(target: Target, cached: boolean, turnsAfterChange = 4) {
	const fake = target.fake();
	const context: Context = { systemPrompt: "Instructions under Pi-Bolt 1.3.1.", messages: [] };
	const reports: number[] = [];
	for (let turn = 1; turn <= 3 + turnsAfterChange; turn++) {
		if (turn === 4) context.systemPrompt = "Instructions under Pi-Bolt 1.3.2.";
		context.messages.push({ role: "user", content: `turn ${turn}`, timestamp: turn });
		const reply = await completeSimple(target.model, context, {
			apiKey: "test-key",
			reasoning: "high",
			fetch: fake.fetch,
			...(cached ? { sessionId: `reasoning-drop-${target.name}`, immutableMessages: true } : {}),
			...target.options,
		});
		expect(reply.stopReason, reply.errorMessage).toBe("stop");
		reports.push(reported(reply));
		context.messages.push(reply);
	}
	return { fake, context, reports };
}

describe.each(targets)("$name: reasoning removed after a head change (#346)", (target) => {
	it.each([true, false])(
		"reports the removed blocks once and keeps the later ones (request cache %s)",
		async (cached) => {
			const { fake, reports } = await runSession(target, cached);
			const name = (n: number) => (target.model.api === "anthropic-messages" ? `sig_${n}` : `rs_${n}`);

			// Turn 4 replays the three blocks minted under the old head; they are removed, reported once, and never sent again.
			expect(reports).toEqual([0, 0, 0, 3, 0, 0, 0]);
			expect(fake.removed.map((removed) => removed.length)).toEqual([0, 0, 0, 3, 0, 0, 0]);
			expect(fake.removed[3]).toEqual([name(1), name(2), name(3)]);
			// Blocks minted under the new head keep binding and are replayed on every later request.
			expect(fake.sent.slice(4)).toEqual([[name(4)], [name(4), name(5)], [name(4), name(5), name(6)]]);
		},
	);

	it("keeps the removals after a reload of the session file", async () => {
		const { context } = await runSession(target, true, 1);
		// The session file holds the messages as JSON.
		const reloaded: Context = { ...context, messages: JSON.parse(JSON.stringify(context.messages)) };
		const fake = target.fake();
		reloaded.messages.push({ role: "user", content: "after reload", timestamp: 9 });
		const reply = await completeSimple(target.model, reloaded, {
			apiKey: "test-key",
			reasoning: "high",
			fetch: fake.fetch,
			...target.options,
		});
		expect(reply.stopReason, reply.errorMessage).toBe("stop");
		const name = (n: number) => (target.model.api === "anthropic-messages" ? `sig_${n}` : `rs_${n}`);
		expect(fake.sent[0]).toEqual([name(4)]);
	});
});

/** Two turns, then the reply of turn 2 reports the block of turn 1 removed with `reason`; returns what turn 3 sends. */
async function sentAfterReport(target: Target, reason: string, reporterModel?: string): Promise<string[]> {
	const fake = target.fake();
	const anthropic = target.model.api === "anthropic-messages";
	const context: Context = { systemPrompt: "Instructions.", messages: [] };
	for (let turn = 1; turn <= 3; turn++) {
		context.messages.push({ role: "user", content: `turn ${turn}`, timestamp: turn });
		const reply = await completeSimple(target.model, context, {
			apiKey: "test-key",
			reasoning: "high",
			fetch: fake.fetch,
			...target.options,
		});
		expect(reply.stopReason, reply.errorMessage).toBe("stop");
		if (turn === 2) {
			reply.diagnostics = [
				anthropic
					? {
							type: "anthropic_input_transformations",
							timestamp: 1,
							details: {
								transformations: [
									{
										type: "thinking_dropped",
										path: "messages.1.content.0",
										reason,
										signatureHash: shortHash("sig_1"),
									},
								],
							},
						}
					: {
							type: "openai_input_transformations",
							timestamp: 1,
							details: { transformations: [{ type: "reasoning", id: "rs_1", action: "removed", reason }] },
						},
			];
			if (reporterModel) reply.model = reporterModel;
		}
		context.messages.push(reply);
	}
	return fake.sent[2];
}

describe.each([targets[0], targets[4]])("$name: which removals stop the replay (#346)", (target) => {
	const anthropic = target.model.api === "anthropic-messages";
	const name = (n: number) => (anthropic ? `sig_${n}` : `rs_${n}`);
	const reasons = anthropic
		? {
				prefix: "prefix_binding_mismatch",
				model: "model_binding_mismatch",
				other: ["organization_binding_mismatch", "end_user_binding_mismatch"],
			}
		: {
				prefix: "prefix_mismatch",
				model: "model_mismatch",
				other: ["organization_mismatch", "end_user_mismatch", "turn_boundary"],
			};

	it("leaves out a block removed because earlier input changed", async () => {
		expect(await sentAfterReport(target, reasons.prefix)).toEqual([name(2)]);
	});

	it("keeps replaying blocks removed for the credentials, which can change back", async () => {
		for (const reason of reasons.other)
			expect(await sentAfterReport(target, reason), reason).toEqual([name(1), name(2)]);
	});

	it("leaves out a block removed for the model only for requests to the model that reported it", async () => {
		expect(await sentAfterReport(target, reasons.model)).toEqual([name(2)]);
		// The reply of turn 2 comes from another model id; its thinking is not replayed, and its report does not apply.
		expect(await sentAfterReport(target, reasons.model, "another-model")).toEqual([name(1)]);
	});
});

describe.each([targets[0], targets[4]])("$name: request cache after a removal (#346)", (target) => {
	it("converts again only the message that has the removed block", async () => {
		const fake = target.fake();
		const anthropic = target.model.api === "anthropic-messages";
		const options = { sessionId: `reasoning-drop-cache-${target.name}`, immutableMessages: true };
		const context: Context = { systemPrompt: "Instructions.", messages: [] };
		const reused: number[] = [];
		for (let turn = 1; turn <= 8; turn++) {
			context.messages.push({ role: "user", content: `turn ${turn}`, timestamp: turn });
			const before = requestCacheStats().units;
			const reply = await completeSimple(target.model, context, {
				apiKey: "test-key",
				reasoning: "high",
				fetch: fake.fetch,
				...options,
				...target.options,
			});
			reused.push(requestCacheStats().units - before);
			if (turn === 6) {
				// The reply of turn 6 reports the block of turn 2 removed (an edit of the input before it).
				reply.diagnostics = [
					anthropic
						? {
								type: "anthropic_input_transformations",
								timestamp: 1,
								details: {
									transformations: [
										{
											type: "thinking_dropped",
											path: "messages.3.content.0",
											reason: "prefix_binding_mismatch",
											signatureHash: shortHash("sig_2"),
										},
									],
								},
							}
						: {
								type: "openai_input_transformations",
								timestamp: 1,
								details: {
									transformations: [
										{ type: "reasoning", id: "rs_2", action: "removed", reason: "prefix_mismatch" },
									],
								},
							},
				];
			}
			context.messages.push(reply);
		}
		const name = (n: number) => (anthropic ? `sig_${n}` : `rs_${n}`);
		expect(fake.sent[6]).toEqual([1, 3, 4, 5, 6].map(name));
		// A message is kept from the second request that converts it on, so request n reuses the 2n - 5 messages up to
		// user message n - 2. Request 7 converts the reply of turn 2 again, and only that one.
		expect(reused).toEqual([0, 0, 1, 3, 5, 7, 9 - 1, 11]);
	});
});
