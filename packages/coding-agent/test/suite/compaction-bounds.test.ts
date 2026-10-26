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
import {
	type CompactionDetails,
	computeFileLists,
	createFileOps,
	MAX_FILE_LIST_ENTRIES,
	SUMMARIZATION_SYSTEM_PROMPT,
} from "../../src/core/compaction/index.ts";
import { createHarness, type Harness } from "./harness.ts";

/** The provider error from a Pi-Bolt session whose recovery gave up after one compact-and-retry. */
const CONTEXT_LENGTH_EXCEEDED =
	'400: {"message":"[code=context_length_exceeded] Input is too long: the request exceeds the model\'s token context limit. Compact the conversation and retry. If compaction also exceeds the limit, shorten the history or start a new session.","type":"invalid_request_error","code":"context_length_exceeded","param":"messages"}';

const usage = {
	input: 100,
	output: 10,
	cacheRead: 0,
	cacheWrite: 0,
	totalTokens: 110,
	cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
};

function assistant(harness: Harness, content: AssistantMessage["content"], timestamp: number): AssistantMessage {
	const model = harness.getModel();
	return {
		...fauxAssistantMessage(""),
		content,
		api: model.api,
		provider: model.provider,
		model: model.id,
		stopReason: content.some((block) => block.type === "toolCall") ? "toolUse" : "stop",
		usage,
		timestamp,
	};
}

/** Append one user turn that reads even-indexed and edits odd-indexed `files`. */
function seedTurn(harness: Harness, turn: number, files: string[], text = `turn ${turn}: please work on`): void {
	const timestamp = Date.now() - 100_000 + turn * 1000;
	harness.sessionManager.appendMessage({
		role: "user",
		content: [{ type: "text", text: files.length > 0 ? `${text} ${files.join(", ")}` : text }],
		timestamp,
	});
	if (files.length > 0) {
		const toolName = (index: number) => (index % 2 === 0 ? "read" : "edit");
		harness.sessionManager.appendMessage(
			assistant(
				harness,
				files.map((path, index) => ({
					type: "toolCall" as const,
					id: `call-${turn}-${index}`,
					name: toolName(index),
					arguments: { path },
				})),
				timestamp + 1,
			),
		);
		files.forEach((path, index) => {
			harness.sessionManager.appendMessage({
				role: "toolResult",
				toolCallId: `call-${turn}-${index}`,
				toolName: toolName(index),
				content: [{ type: "text", text: `contents of ${path}\n${"line\n".repeat(30 * (index + 1))}` }],
				isError: false,
				timestamp: timestamp + 2,
			} satisfies ToolResultMessage);
		});
	}
	harness.sessionManager.appendMessage(
		assistant(harness, [{ type: "text", text: `done with turn ${turn}` }], timestamp + 3),
	);
	harness.session.agent.state.messages = harness.sessionManager.buildSessionContext().messages;
}

/** Estimated tokens of a provider request, with the same chars/4 rule Pi uses. */
function requestTokens(context: TranscriptContext): number {
	return Math.ceil(JSON.stringify(context.messages).length / 4);
}

function isSummaryRequest(context: TranscriptContext): boolean {
	return getCurrentSystemPrompt(context.messages) === SUMMARIZATION_SYSTEM_PROMPT;
}

function summaryRequestText(context: TranscriptContext): string {
	const user = context.messages.find((message) => message.role === "user");
	const content = user?.role === "user" ? user.content : "";
	return typeof content === "string"
		? content
		: content.map((block) => (block.type === "text" ? block.text : "")).join("");
}

describe("compaction bounds", () => {
	const harnesses: Harness[] = [];

	afterEach(() => {
		while (harnesses.length > 0) harnesses.pop()?.cleanup();
	});

	// Normal compactions must send exactly the requests they sent before the bounds were added.
	// The fixture was captured from pi-bolt before this change; update it only when the summary
	// prompts change on purpose.
	it("sends unchanged summary requests for normal compactions", async () => {
		const captured: unknown[] = [];
		const record = (harness: Harness, scenario: string) => {
			harness.setResponses(
				Array.from({ length: 6 }, (_, request) => (context: TranscriptContext, options) => {
					captured.push({
						scenario,
						request,
						messages: context.messages.map((message) => ({ ...message, timestamp: 0 })),
						maxTokens: options?.maxTokens,
						reasoning: options?.reasoning,
						cacheRetention: options?.cacheRetention,
					});
					return fauxAssistantMessage(`summary ${scenario} ${request}`);
				}),
			);
		};

		const harness = await createHarness({ settings: { compaction: { keepRecentTokens: 1 } } });
		harnesses.push(harness);
		seedTurn(harness, 1, ["src/a.ts", "src/b.ts", "src/c.ts"]);
		seedTurn(harness, 2, ["src/d.ts", "src/a.ts"]);
		record(harness, "initial");
		const initial = await harness.session.compact();
		captured.push({ scenario: "initial", summary: initial.summary, details: initial.details });
		seedTurn(harness, 3, ["src/e.ts", "src/b.ts", "src/f.ts"]);
		record(harness, "update");
		const update = await harness.session.compact("focus on tests");
		captured.push({ scenario: "update", summary: update.summary, details: update.details });

		const large = await createHarness({
			models: [{ id: "faux-1m", contextWindow: 1_000_000, maxTokens: 64_000 }],
			settings: { compaction: { keepRecentTokens: 600 } },
		});
		harnesses.push(large);
		seedTurn(large, 1, ["src/a.ts", "src/b.ts"]);
		seedTurn(large, 2, ["src/c.ts", "src/d.ts", "src/e.ts", "src/f.ts", "src/g.ts", "src/h.ts"]);
		record(large, "split");
		const split = await large.session.compact();
		captured.push({ scenario: "split", summary: split.summary, details: split.details });

		const expected = JSON.parse(
			readFileSync(join(import.meta.dirname, "../fixtures/compaction-normal-requests.json"), "utf-8"),
		);
		expect(captured).toEqual(expected);
	});

	// earendil-works/pi#11297
	it("keeps the appended file lists bounded across compactions", async () => {
		const harness = await createHarness({ settings: { compaction: { keepRecentTokens: 1 } } });
		harnesses.push(harness);
		const pad = (index: number) => String(index).padStart(4, "0");
		const oldRead = Array.from({ length: 300 }, (_, index) => `/tmp/old/read-${pad(index)}.txt`);
		const oldModified = Array.from({ length: 250 }, (_, index) => `/tmp/old/modified-${pad(index)}.txt`);
		seedTurn(harness, 1, []);
		const keptId = harness.sessionManager.getEntries().at(-1)!.id;
		harness.sessionManager.appendCompaction("earlier summary", keptId, 100, {
			readFiles: oldRead,
			modifiedFiles: oldModified,
		} satisfies CompactionDetails);
		// Touching an old read file again makes it recent; the new paths are the most recent.
		seedTurn(harness, 2, ["src/new-read.ts", "src/new-edit.ts", oldRead[0]]);
		seedTurn(harness, 3, ["src/last.ts"]);
		harness.setResponses(Array.from({ length: 4 }, () => fauxAssistantMessage("summary")));

		const first = await harness.session.compact();
		const firstDetails = first.details as CompactionDetails;
		expect(firstDetails.readFiles).toHaveLength(MAX_FILE_LIST_ENTRIES);
		expect(firstDetails.modifiedFiles).toHaveLength(MAX_FILE_LIST_ENTRIES);
		expect(firstDetails.readFiles).toEqual(expect.arrayContaining(["src/new-read.ts", "src/last.ts", oldRead[0]]));
		expect(firstDetails.readFiles).not.toContain(oldRead[1]);
		expect(firstDetails.modifiedFiles).toContain("src/new-edit.ts");
		expect(firstDetails.readFiles).toEqual([...firstDetails.readFiles].sort());
		// 300 old + 2 new read-only files, 100 kept; 250 old + 1 new modified files, 100 kept.
		expect(first.summary).toContain("[202 older files omitted]\n</read-files>");
		expect(first.summary).toContain("[151 older files omitted]\n</modified-files>");

		seedTurn(harness, 4, ["src/after.ts"]);
		harness.setResponses(Array.from({ length: 4 }, () => fauxAssistantMessage("summary")));
		const second = await harness.session.compact();
		const secondDetails = second.details as CompactionDetails;
		expect(secondDetails.readFiles).toHaveLength(MAX_FILE_LIST_ENTRIES);
		expect(secondDetails.readFiles).toContain("src/after.ts");
		expect(second.summary.length).toBeLessThanOrEqual(first.summary.length + 100);
	});

	// earendil-works/pi#11297
	it("caps file lists by most recent touch, including branch-summary style input", () => {
		const fileOps = createFileOps();
		for (let index = 0; index < 150; index++) fileOps.read.add(`r${index}`);
		for (let index = 0; index < 120; index++) fileOps.edited.add(`e${index}`);
		for (let index = 0; index < 10; index++) fileOps.written.add(`w${index}`);
		const lists = computeFileLists(fileOps);
		expect(lists.readFiles).toHaveLength(MAX_FILE_LIST_ENTRIES);
		expect(lists.omittedReadFiles).toBe(50);
		expect(lists.readFiles).toContain("r149");
		expect(lists.readFiles).not.toContain("r0");
		expect(lists.modifiedFiles).toHaveLength(MAX_FILE_LIST_ENTRIES);
		expect(lists.omittedModifiedFiles).toBe(30);
		expect(lists.modifiedFiles).toEqual(expect.arrayContaining(["e119", "w0", "w9"]));
		expect(lists.modifiedFiles).not.toContain("e0");
	});

	// earendil-works/pi#11281: the summary request itself overflows the summarizer's window.
	it("splits a summary request the provider rejects as too long", async () => {
		const harness = await createHarness({
			models: [{ id: "faux-1", contextWindow: 128_000, maxTokens: 4096 }],
			settings: { compaction: { keepRecentTokens: 1 } },
		});
		harnesses.push(harness);
		// One Han character is one token for this provider; Pi estimates four characters per token.
		const han = (text: string) => text.split("你").length - 1;
		harness.sessionManager.appendMessage({
			role: "user",
			content: [{ type: "text", text: "你".repeat(200_000) }],
			timestamp: Date.now() - 4000,
		});
		harness.sessionManager.appendMessage(assistant(harness, [{ type: "text", text: "ok" }], Date.now() - 3000));
		seedTurn(harness, 2, []);
		const sent: number[] = [];
		const rejected: number[] = [];
		harness.setResponses(
			Array.from({ length: 12 }, (_, index) => (context: TranscriptContext) => {
				const tokens = han(JSON.stringify(context.messages));
				if (tokens > 128_000) {
					rejected.push(tokens);
					return fauxAssistantMessage("", {
						stopReason: "error",
						errorMessage: "maximum context length exceeded",
					});
				}
				sent.push(tokens);
				return fauxAssistantMessage(`summary ${index}`);
			}),
		);

		const result = await harness.session.compact();

		expect(rejected).toEqual([200_000]);
		expect(sent.length).toBeGreaterThan(2);
		// Every character of the oversized message reached the summarizer.
		expect(sent.reduce((sum, tokens) => sum + tokens, 0)).toBe(200_000);
		expect(result.summary).toMatch(/^summary \d+\n\n---\n\n\*\*Turn Context \(split turn\):\*\*/);
	});

	// earendil-works/pi#11281: a request estimated above the window is split before it is sent.
	it("splits a summary request estimated above the summarizer window without sending it", async () => {
		const harness = await createHarness({
			models: [{ id: "faux-1", contextWindow: 32_000, maxTokens: 4096 }],
			settings: { compaction: { keepRecentTokens: 1 } },
		});
		harnesses.push(harness);
		for (let turn = 1; turn <= 6; turn++) seedTurn(harness, turn, [], `turn ${turn}: ${"y".repeat(40_000)}`);
		const requests: Array<{ tokens: number; text: string }> = [];
		harness.setResponses(
			Array.from({ length: 12 }, (_, index) => (context: TranscriptContext) => {
				requests.push({ tokens: requestTokens(context), text: summaryRequestText(context) });
				if (requestTokens(context) > 32_000) {
					return fauxAssistantMessage("", { stopReason: "error", errorMessage: CONTEXT_LENGTH_EXCEEDED });
				}
				return fauxAssistantMessage(`summary ${index}`);
			}),
		);

		await harness.session.compact();

		const history = requests.filter((request) => request.text.startsWith("<conversation>"));
		expect(history.length).toBeGreaterThan(1);
		expect(requests.every((request) => request.tokens <= 32_000)).toBe(true);
		// Each later request updates the summary of the requests before it.
		expect(history[1].text).toContain("<previous-summary>\nsummary 0\n</previous-summary>");
	});

	// earendil-works/pi#11281: overflow recovery gave up when the request after one compaction still
	// overflowed ("Context overflow recovery failed after one compact-and-retry attempt").
	it("keeps recovering when the request after the first compaction still overflows", async () => {
		const largeTool: AgentTool = {
			name: "large_result",
			label: "Large result",
			description: "Returns a result larger than the provider's limit",
			parameters: Type.Object({}),
			execute: async () => ({
				content: [{ type: "text", text: `result-start ${"x".repeat(60_000)} result-end` }],
				details: {},
			}),
		};
		const harness = await createHarness({
			// The declared window is far above what the provider serves (earendil-works/pi#11387).
			models: [{ id: "faux-1", contextWindow: 1_000_000, maxTokens: 4096 }],
			settings: { compaction: { keepRecentTokens: 8000 } },
			tools: [largeTool],
		});
		harnesses.push(harness);
		let limit = Number.POSITIVE_INFINITY;
		const agentRequests: Array<{ tokens: number; rejected: boolean; text: string }> = [];
		const replies: AssistantMessage[] = [
			fauxAssistantMessage("seeded"),
			fauxAssistantMessage(fauxToolCall("large_result", {}), { stopReason: "toolUse" }),
			fauxAssistantMessage("finished after recovery"),
		];
		harness.setResponses(
			Array.from({ length: 20 }, () => (context: TranscriptContext) => {
				if (isSummaryRequest(context)) return fauxAssistantMessage("summary");
				const tokens = requestTokens(context);
				const rejected = tokens > limit;
				agentRequests.push({ tokens, rejected, text: JSON.stringify(context.messages) });
				if (rejected) {
					return fauxAssistantMessage("", {
						stopReason: "error",
						errorMessage: CONTEXT_LENGTH_EXCEEDED,
						timestamp: Date.now() + 10_000,
					});
				}
				return replies.shift()!;
			}),
		);

		await harness.session.prompt("seed");
		limit = agentRequests[0].tokens + 4000;
		await harness.session.prompt("run the large tool");

		expect(harness.eventsOfType("compaction_end").filter((event) => event.errorMessage)).toEqual([]);
		expect(harness.session.getLastAssistantText()).toBe("finished after recovery");
		const rejected = agentRequests.filter((request) => request.rejected);
		expect(rejected).toHaveLength(2);
		const final = agentRequests.at(-1)!;
		expect(final.rejected).toBe(false);
		expect(final.tokens).toBeLessThan(rejected[1].tokens);
		expect(final.text).toContain("result-start");
		expect(final.text).toContain("result-end");
		expect(final.text).toContain("characters omitted to fit the model's context window");
	});

	// earendil-works/pi#11281: recovery is bounded and says so when even the smallest request is too big.
	it("stops after the last recovery step when every retry still overflows", async () => {
		const largeTool: AgentTool = {
			name: "large_result",
			label: "Large result",
			description: "Returns a large result",
			parameters: Type.Object({}),
			execute: async () => ({ content: [{ type: "text", text: "z".repeat(60_000) }], details: {} }),
		};
		const harness = await createHarness({
			models: [{ id: "faux-1", contextWindow: 1_000_000, maxTokens: 4096 }],
			settings: { compaction: { keepRecentTokens: 50 } },
			tools: [largeTool],
		});
		harnesses.push(harness);
		let limit = Number.POSITIVE_INFINITY;
		const agentRequests: Array<{ tokens: number; rejected: boolean }> = [];
		const replies: AssistantMessage[] = [
			fauxAssistantMessage("first"),
			fauxAssistantMessage("second"),
			fauxAssistantMessage(fauxToolCall("large_result", {}), { stopReason: "toolUse" }),
		];
		harness.setResponses(
			Array.from({ length: 20 }, () => (context: TranscriptContext) => {
				if (isSummaryRequest(context)) return fauxAssistantMessage("summary");
				const tokens = requestTokens(context);
				const rejected = tokens > limit;
				agentRequests.push({ tokens, rejected });
				if (rejected) {
					return fauxAssistantMessage("", {
						stopReason: "error",
						errorMessage: CONTEXT_LENGTH_EXCEEDED,
						timestamp: Date.now() + 10_000,
					});
				}
				const reply = replies.shift()!;
				// From here on the provider serves less than the history without the tool result, so even
				// the smallest request recovery can build (summary, tool call, shortened result) is too big.
				if (reply.stopReason === "toolUse") limit = tokens - 1500;
				return reply;
			}),
		);

		await harness.session.prompt(`first ${"a".repeat(4000)}`);
		await harness.session.prompt(`second ${"b".repeat(4000)}`);
		await harness.session.prompt("run the large tool");

		const rejected = agentRequests.filter((request) => request.rejected);
		expect(rejected).toHaveLength(4);
		for (let index = 1; index < rejected.length; index++) {
			expect(rejected[index].tokens).toBeLessThan(rejected[index - 1].tokens);
		}
		expect(harness.eventsOfType("compaction_end").at(-1)?.errorMessage).toMatch(
			/^Context overflow recovery failed after 3 compact-and-retry attempts: even the smallest context/,
		);
	});
});
