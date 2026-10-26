import { readFileSync } from "node:fs";
import { join } from "node:path";
import type { AgentTool } from "@earendil-works/pi-agent-core";
import {
	type AssistantMessage,
	fauxAssistantMessage,
	fauxToolCall,
	getCurrentSystemPrompt,
	type ToolResultMessage,
	type TranscriptContext,
} from "@earendil-works/pi-ai";
import { Type } from "typebox";
import { afterEach, describe, expect, it } from "vitest";
import { SUMMARIZATION_SYSTEM_PROMPT } from "../../src/core/compaction/index.ts";
import { createHarness, type Harness, type HarnessOptions } from "./harness.ts";

function isSummaryRequest(context: TranscriptContext): boolean {
	return getCurrentSystemPrompt(context.messages) === SUMMARIZATION_SYSTEM_PROMPT;
}

function assistant(harness: Harness, content: AssistantMessage["content"], timestamp: number, tokens = 110) {
	const model = harness.getModel();
	return {
		...fauxAssistantMessage(""),
		content,
		api: model.api,
		provider: model.provider,
		model: model.id,
		stopReason: content.some((block) => block.type === "toolCall") ? "toolUse" : "stop",
		usage: {
			input: tokens - 10,
			output: 10,
			cacheRead: 0,
			cacheWrite: 0,
			totalTokens: tokens,
			cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
		},
		timestamp,
	} satisfies AssistantMessage;
}

/** Append one user turn with a tool call per entry of `resultChars`, each returning that many characters. */
function seedTurn(harness: Harness, turn: number, resultChars: number[], closed = true): void {
	const timestamp = Date.now() - 100_000 + turn * 1000;
	harness.sessionManager.appendMessage({
		role: "user",
		content: [{ type: "text", text: `turn ${turn}: please work on it` }],
		timestamp,
	});
	harness.sessionManager.appendMessage(
		assistant(
			harness,
			resultChars.map((_, index) => ({
				type: "toolCall" as const,
				id: `call-${turn}-${index}`,
				name: "read",
				arguments: { path: `src/turn-${turn}-${index}.ts` },
			})),
			timestamp + 1,
		),
	);
	resultChars.forEach((chars, index) => {
		harness.sessionManager.appendMessage({
			role: "toolResult",
			toolCallId: `call-${turn}-${index}`,
			toolName: "read",
			content: [{ type: "text", text: `result ${turn}-${index}\n${"x".repeat(chars)}` }],
			isError: false,
			timestamp: timestamp + 2,
		} satisfies ToolResultMessage);
	});
	if (closed) {
		harness.sessionManager.appendMessage(
			assistant(harness, [{ type: "text", text: `done with turn ${turn}` }], timestamp + 3),
		);
	}
}

/** Append an assistant message whose provider usage reports `tokens` of context. */
function seedUsage(harness: Harness, tokens: number): void {
	const timestamp = Date.now() - 1000;
	harness.sessionManager.appendMessage({
		role: "user",
		content: [{ type: "text", text: "status?" }],
		timestamp: timestamp - 1,
	});
	harness.sessionManager.appendMessage(assistant(harness, [{ type: "text", text: "working" }], timestamp, tokens));
	harness.session.agent.state.messages = harness.sessionManager.buildSessionContext().messages;
}

/**
 * History of about 45K estimated tokens whose last turn holds 24K tokens of tool results, so a
 * 20K-token tail cuts inside it (a split turn). `olderTurns` 6K-token turns come first.
 */
function seedHistory(harness: Harness, olderTurns = 0): void {
	for (let turn = 1; turn <= olderTurns + 4; turn++) seedTurn(harness, turn, [24_000]);
	seedTurn(harness, olderTurns + 5, [32_000, 32_000, 32_000]);
}

interface Captured {
	calls: unknown[];
	compactions: unknown[];
}

/** Answer every provider call, recording summary requests in full and agent requests by kind. */
function record(harness: Harness, captured: Captured): void {
	harness.setResponses(
		Array.from({ length: 12 }, (_, request) => (context: TranscriptContext, options) => {
			if (!isSummaryRequest(context)) {
				captured.calls.push({ kind: "agent" });
				return fauxAssistantMessage(`reply ${request}`);
			}
			captured.calls.push({
				kind: "summary",
				messages: context.messages.map((message) => ({ ...message, timestamp: 0 })),
				maxTokens: options?.maxTokens,
				reasoning: options?.reasoning,
				cacheRetention: options?.cacheRetention,
			});
			return fauxAssistantMessage(`summary ${request}`);
		}),
	);
}

function recordCompactions(harness: Harness, captured: Captured): void {
	const branch = harness.sessionManager.getBranch();
	for (const event of harness.eventsOfType("compaction_end")) {
		const result = event.result;
		captured.compactions.push({
			reason: event.reason,
			willRetry: event.willRetry,
			errorMessage: event.errorMessage,
			summary: result?.summary,
			tokensBefore: result?.tokensBefore,
			details: result?.details,
			firstKeptIndex: branch.findIndex((entry) => entry.id === result?.firstKeptEntryId),
		});
	}
}

describe("compaction scaled with the context window", () => {
	const harnesses: Harness[] = [];
	afterEach(() => {
		while (harnesses.length > 0) harnesses.pop()?.cleanup();
	});

	async function harnessWith(options: HarnessOptions): Promise<Harness> {
		const harness = await createHarness({ tools: [], ...options });
		harnesses.push(harness);
		return harness;
	}

	// A 128K session with default settings must compact at the same point and send exactly the
	// requests it sent before the defaults scaled with the context window. The fixture was captured
	// from pi-bolt before that change.
	it("leaves 128K default compaction unchanged", async () => {
		const harness = await harnessWith({});
		expect(harness.getModel().contextWindow).toBe(128_000);
		const captured: Captured = { calls: [], compactions: [] };
		seedHistory(harness);
		record(harness, captured);

		// 128000 - 16384 = 111616: one token above it triggers, the threshold itself does not.
		seedUsage(harness, 111_616);
		await harness.session.prompt("go");
		seedUsage(harness, 111_617);
		await harness.session.prompt("go");
		recordCompactions(harness, captured);

		const expected = JSON.parse(
			readFileSync(join(import.meta.dirname, "../fixtures/compaction-128k-default-requests.json"), "utf-8"),
		);
		expect(captured).toEqual(expected);
	});

	it.each([
		{ window: 200_000, settings: {}, threshold: 180_000 },
		{ window: 1_000_000, settings: {}, threshold: 900_000 },
		{ window: 1_000_000, settings: { reserveTokens: 16_384 }, threshold: 983_616 },
		{ window: 1_000_000, settings: { reserveTokens: 16_384, thresholdPercent: 95 }, threshold: 950_000 },
	])("compacts before a prompt above $threshold tokens of a $window-token window with $settings", async (row) => {
		const harness = await harnessWith({
			models: [{ id: "faux-large", contextWindow: row.window, maxTokens: 128_000 }],
			settings: { compaction: row.settings },
		});
		const captured: Captured = { calls: [], compactions: [] };
		// Above the 50K tokens a 1M window keeps, so there is history to summarize.
		seedHistory(harness, 10);
		record(harness, captured);

		seedUsage(harness, row.threshold);
		await harness.session.prompt("go");
		expect(harness.eventsOfType("compaction_end")).toHaveLength(0);
		seedUsage(harness, row.threshold + 1);
		await harness.session.prompt("go");
		expect(harness.eventsOfType("compaction_end").map((event) => [event.reason, event.errorMessage])).toEqual([
			["threshold", undefined],
		]);
	});

	it.each([
		{ window: 128_000, settings: {}, keptFrom: 21, summary: 13_107, turnPrefix: 8_192 },
		{ window: 200_000, settings: {}, keptFrom: 21, summary: 13_107, turnPrefix: 8_192 },
		// 5% of 1M keeps 50K tokens: the whole 45K-token history, so nothing is summarized but the 1M
		// window still sets the caps of the history summary below.
		{ window: 1_000_000, settings: { keepRecentTokens: 20_000 }, keptFrom: 21, summary: 32_000, turnPrefix: 16_000 },
		{
			window: 1_000_000,
			settings: { keepRecentTokens: 20_000, reserveTokens: 16_384 },
			keptFrom: 21,
			summary: 13_107,
			turnPrefix: 8_192,
		},
		{
			window: 1_000_000,
			settings: { keepRecentTokens: 20_000, summaryMaxTokens: 20_000 },
			keptFrom: 21,
			summary: 20_000,
			turnPrefix: 10_000,
		},
	])("caps summaries of a $window-token window with $settings", async (row) => {
		const harness = await harnessWith({
			models: [{ id: "faux-large", contextWindow: row.window, maxTokens: 128_000 }],
			settings: { compaction: row.settings },
		});
		const captured: Captured = { calls: [], compactions: [] };
		seedHistory(harness);
		record(harness, captured);
		await harness.session.compact();
		recordCompactions(harness, captured);

		expect(captured.calls.map((call) => (call as { maxTokens?: number }).maxTokens)).toEqual([
			row.summary,
			row.turnPrefix,
		]);
		expect(captured.compactions).toMatchObject([{ reason: "manual", firstKeptIndex: row.keptFrom }]);
	});

	it("keeps 5% of a 1M window as recent context", async () => {
		const harness = await harnessWith({
			models: [{ id: "faux-1m", contextWindow: 1_000_000, maxTokens: 128_000 }],
		});
		// Ten more 6K-token turns: 105K estimated tokens in all, of which the newest 50K are kept.
		seedHistory(harness, 10);
		const captured: Captured = { calls: [], compactions: [] };
		record(harness, captured);
		await harness.session.compact();

		const branch = harness.sessionManager.getBranch();
		const firstKept = branch.findIndex(
			(entry) => entry.id === harness.eventsOfType("compaction_end")[0]?.result?.firstKeptEntryId,
		);
		const keptTokens = Math.ceil(JSON.stringify(branch.slice(firstKept)).length / 4);
		expect(keptTokens).toBeGreaterThan(50_000);
		expect(keptTokens).toBeLessThan(60_000);
		expect(captured.calls.map((call) => (call as { maxTokens?: number }).maxTokens)).toEqual([32_000, 16_000]);
	});

	// Post-run and next-turn checks use the same threshold as the pre-prompt check.
	it.each([
		{ settings: { thresholdPercent: 50 }, compacts: true },
		{ settings: {}, compacts: false },
	])("applies thresholdPercent after a tool result with $settings", async (row) => {
		const largeTool: AgentTool = {
			name: "large_result",
			label: "Large result",
			description: "Returns a large result",
			parameters: Type.Object({}),
			execute: async () => ({ content: [{ type: "text", text: "z".repeat(40_000) }], details: {} }),
		};
		// 40000 - 16384 = 23616 tokens without thresholdPercent; 50% of 40000 = 20000 with it.
		const harness = await harnessWith({
			models: [{ id: "faux-40k", contextWindow: 40_000, maxTokens: 4096 }],
			settings: { compaction: { keepRecentTokens: 2000, ...row.settings } },
			tools: [largeTool],
		});
		// About 12K tokens of history; the 10K-token tool result takes the context to about 22K.
		for (let turn = 1; turn <= 3; turn++) seedTurn(harness, turn, [16_000]);
		harness.session.agent.state.messages = harness.sessionManager.buildSessionContext().messages;
		const requestTokens: number[] = [];
		harness.setResponses(
			Array.from({ length: 8 }, (_, request) => (context: TranscriptContext) => {
				if (isSummaryRequest(context)) return fauxAssistantMessage(`summary ${request}`);
				requestTokens.push(Math.ceil(JSON.stringify(context.messages).length / 4));
				return requestTokens.length === 1
					? fauxAssistantMessage(fauxToolCall("large_result", {}), { stopReason: "toolUse" })
					: fauxAssistantMessage("done");
			}),
		);

		await harness.session.prompt("run the large tool");

		// The check before the second request compacts, so that request is below the threshold.
		expect(harness.eventsOfType("compaction_end").map((event) => event.reason)).toEqual(
			row.compacts ? ["threshold"] : [],
		);
		expect(requestTokens).toHaveLength(2);
		expect(requestTokens[1] < 20_000).toBe(row.compacts);
		expect(harness.session.getLastAssistantText()).toBe("done");
	});
});
