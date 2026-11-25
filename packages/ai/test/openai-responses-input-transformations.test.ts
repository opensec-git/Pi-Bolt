import type { ResponseStreamEvent } from "openai/resources/responses/responses.js";
import { afterEach, describe, expect, it, vi } from "vitest";
import { streamSimple as streamOpenAICodexResponses } from "../src/api/openai-codex-responses.ts";
import {
	convertResolvedResponsesInput,
	convertResponsesMessages,
	processResponsesStream,
} from "../src/api/openai-responses-shared.ts";
import type { AssistantMessage, Message, Model, Usage } from "../src/types.ts";
import { AssistantMessageEventStream } from "../src/utils/event-stream.ts";
import { beginRequestCache } from "../src/utils/request-cache.ts";
import { normalizeContext } from "../src/utils/transcript.ts";

afterEach(() => {
	vi.unstubAllGlobals();
});

const model: Model<"openai-responses"> = {
	id: "gpt-6.2-sol",
	name: "GPT-6.2 Sol",
	api: "openai-responses",
	provider: "openai",
	baseUrl: "https://api.openai.com/v1",
	reasoning: true,
	input: ["text"],
	cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
	contextWindow: 272000,
	maxTokens: 128000,
};

function createOutput(): AssistantMessage {
	return {
		role: "assistant",
		content: [],
		api: model.api,
		provider: model.provider,
		model: model.id,
		usage: {
			input: 0,
			output: 0,
			cacheRead: 0,
			cacheWrite: 0,
			totalTokens: 0,
			cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
		},
		stopReason: "pending",
		timestamp: Date.now(),
	};
}

/** Run one terminal response event carrying `inputTransformations` through the shared stream processor. */
async function processTerminalResponse(
	inputTransformations: unknown,
	status: "completed" | "incomplete" = "completed",
): Promise<AssistantMessage> {
	const response: Record<string, unknown> = {
		id: "resp_transformations",
		status,
		output: [],
		...(status === "incomplete" ? { incomplete_details: { reason: "max_output_tokens" } } : {}),
	};
	if (inputTransformations !== undefined) response.input_transformations = inputTransformations;
	async function* events(): AsyncIterable<ResponseStreamEvent> {
		yield {
			type: status === "incomplete" ? "response.incomplete" : "response.completed",
			sequence_number: 0,
			response,
		} as unknown as ResponseStreamEvent;
	}
	const output = createOutput();
	await processResponsesStream(events(), output, new AssistantMessageEventStream(), model);
	return output;
}

const prefixMismatch = { type: "reasoning", id: "rs_edited", action: "removed", reason: "prefix_mismatch" };
const organizationMismatch = { type: "reasoning", id: "rs_org", action: "removed", reason: "organization_mismatch" };
const turnBoundary = { type: "reasoning", id: "rs_old_turn", action: "removed", reason: "turn_boundary" };

describe("OpenAI Responses reasoning removals", () => {
	it("records removed reasoning items with their reasons", async () => {
		const output = await processTerminalResponse([prefixMismatch, turnBoundary, organizationMismatch]);

		expect(output.stopReason).toBe("stop");
		expect(output.diagnostics).toEqual([
			{
				type: "openai_input_transformations",
				timestamp: expect.any(Number),
				details: { transformations: [prefixMismatch, organizationMismatch] },
			},
		]);
	});

	it("records removals on incomplete responses", async () => {
		const output = await processTerminalResponse([prefixMismatch], "incomplete");

		expect(output.stopReason).toBe("length");
		expect(output.diagnostics?.[0]?.details).toEqual({ transformations: [prefixMismatch] });
	});

	it("keeps reasons the SDK does not know yet", async () => {
		const futureReason = { type: "reasoning", id: "rs_future", action: "removed", reason: "future_reason" };
		const output = await processTerminalResponse([futureReason]);

		expect(output.diagnostics?.[0]?.details).toEqual({ transformations: [futureReason] });
	});

	it("ignores turn boundaries, other transformations and malformed reports", async () => {
		// Models before GPT-5.6 report every earlier turn's reasoning as a turn boundary on each request.
		const reports: unknown[] = [
			undefined,
			null,
			[],
			[turnBoundary, turnBoundary],
			{ malformed: true },
			[null, "rs_text", { type: "message", id: "msg_1", action: "removed", reason: "prefix_mismatch" }],
			[{ type: "reasoning", id: "rs_changed", action: "changed", reason: "prefix_mismatch" }],
		];
		for (const report of reports) {
			const output = await processTerminalResponse(report);
			expect(output.stopReason, JSON.stringify(report)).toBe("stop");
			expect(output.diagnostics, JSON.stringify(report)).toBeUndefined();
		}
	});

	it("records removals that Codex reports in response.done", async () => {
		const encoder = new TextEncoder();
		const terminal = {
			type: "response.done",
			response: {
				id: "resp_codex",
				status: "completed",
				output: [],
				input_transformations: [prefixMismatch, turnBoundary],
			},
		};
		vi.stubGlobal(
			"fetch",
			vi.fn(
				async () =>
					new Response(
						new ReadableStream<Uint8Array>({
							start(controller) {
								controller.enqueue(encoder.encode(`data: ${JSON.stringify(terminal)}\n\n`));
								controller.close();
							},
						}),
						{ status: 200, headers: { "content-type": "text/event-stream" } },
					),
			),
		);
		const payload = Buffer.from(
			JSON.stringify({ "https://api.openai.com/auth": { chatgpt_account_id: "acc_test" } }),
			"utf8",
		).toString("base64");
		const codexModel: Model<"openai-codex-responses"> = {
			...model,
			api: "openai-codex-responses",
			provider: "openai-codex",
			baseUrl: "https://chatgpt.com/backend-api",
		};

		const result = await streamOpenAICodexResponses(
			codexModel,
			normalizeContext({ messages: [{ role: "user", content: "Hello", timestamp: 1 }] }),
			{ apiKey: `aaa.${payload}.bbb`, transport: "sse" },
		).result();

		expect(result.stopReason, result.errorMessage).toBe("stop");
		expect(result.diagnostics).toEqual([
			{
				type: "openai_input_transformations",
				timestamp: expect.any(Number),
				details: { transformations: [prefixMismatch] },
			},
		]);
	});
});

const usage: Usage = {
	input: 0,
	output: 0,
	cacheRead: 0,
	cacheWrite: 0,
	totalTokens: 0,
	cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
};

const reasoningSignature = (id: string): string =>
	JSON.stringify({ type: "reasoning", id, encrypted_content: `enc-${id}`, summary: [] });

function assistantTurn(id: string, removedIds?: string[], reason = "prefix_mismatch"): AssistantMessage {
	const message: AssistantMessage = {
		role: "assistant",
		content: [
			{ type: "thinking", thinking: `thought ${id}`, thinkingSignature: reasoningSignature(`rs_${id}`) },
			{ type: "text", text: `reply ${id}` },
		],
		api: model.api,
		provider: model.provider,
		model: model.id,
		usage,
		stopReason: "stop",
		timestamp: 1,
	};
	if (removedIds) {
		message.diagnostics = [
			{
				type: "openai_input_transformations",
				timestamp: 2,
				details: {
					transformations: removedIds.map((rid) => ({
						type: "reasoning",
						id: rid,
						action: "removed",
						reason,
					})),
				},
			},
		];
	}
	return message;
}

const userTurn = (text: string): Message => ({ role: "user", content: text, timestamp: 1 });

const reasoningIdsIn = (input: unknown[]): string[] =>
	input
		.filter((item) => (item as { type?: string }).type === "reasoning")
		.map((item) => (item as { id?: string }).id ?? "");

describe("OpenAI Responses replay of reasoning reported as dropped", () => {
	it("stops replaying a reasoning item the API removed for prefix_mismatch", () => {
		const input = convertResponsesMessages(
			model,
			normalizeContext({
				systemPrompt: "s",
				messages: [
					userTurn("a"),
					assistantTurn("older"),
					userTurn("b"),
					assistantTurn("reporter", ["rs_older"]),
					userTurn("c"),
				],
			}),
			new Set(["openai"]),
		);
		const ids = reasoningIdsIn(input);
		expect(ids).toContain("rs_reporter");
		expect(ids).not.toContain("rs_older");
	});

	it("keeps replaying reasoning when the recorded reason is a turn boundary", () => {
		const input = convertResponsesMessages(
			model,
			normalizeContext({
				systemPrompt: "s",
				messages: [
					userTurn("a"),
					assistantTurn("older"),
					userTurn("b"),
					assistantTurn("reporter", ["rs_older"], "turn_boundary"),
					userTurn("c"),
				],
			}),
			new Set(["openai"]),
		);
		const ids = reasoningIdsIn(input);
		expect(ids).toContain("rs_older");
		expect(ids).toContain("rs_reporter");
	});

	it("drops a cached serialization when a removal is first reported, without losing untouched items", () => {
		const older = assistantTurn("older");
		const later = assistantTurn("later");
		const messages: Message[] = [userTurn("a"), older, userTurn("b"), later];
		const cacheOptions = { sessionId: "prefix-mismatch-prune", immutableMessages: true };
		const cacheContext = [model.id, model.provider, model.api];

		const firstRun = beginRequestCache("openai-responses", cacheOptions, cacheContext);
		if (!firstRun) throw new Error("expected an active request cache");
		const first = convertResolvedResponsesInput(
			model,
			normalizeContext({ systemPrompt: "s", messages }),
			new Set(["openai"]),
			{ cache: firstRun },
		).input;

		// The removal is reported by the reply to the last request, after its units were cached.
		const reporter = assistantTurn("reporter", ["rs_older"]);
		const secondRun = beginRequestCache("openai-responses", cacheOptions, cacheContext);
		if (!secondRun) throw new Error("expected an active request cache");
		const second = convertResolvedResponsesInput(
			model,
			normalizeContext({ systemPrompt: "s", messages: [...messages, userTurn("c"), reporter] }),
			new Set(["openai"]),
			{ cache: secondRun },
		).input;

		expect(reasoningIdsIn(first)).toContain("rs_older");
		expect(reasoningIdsIn(second)).not.toContain("rs_older");
		expect(reasoningIdsIn(second)).toContain("rs_later");
		expect(reasoningIdsIn(second)).toContain("rs_reporter");
	});
});
