import {
	type AssistantMessage,
	fauxAssistantMessage,
	type SimpleStreamOptions,
	type ToolResultMessage,
	type TranscriptContext,
} from "@earendil-works/pi-ai";
import { afterEach, describe, expect, it } from "vitest";
import { createHarness, type Harness } from "./harness.ts";

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

/** Append one user turn that reads `files`, one tool call each. */
function seedTurn(harness: Harness, turn: number, files: string[]): void {
	const timestamp = Date.now() - 100_000 + turn * 1000;
	harness.sessionManager.appendMessage({
		role: "user",
		content: [{ type: "text", text: `turn ${turn}: please read ${files.join(", ")}` }],
		timestamp,
	});
	harness.sessionManager.appendMessage(
		assistant(
			harness,
			files.map((path, index) => ({
				type: "toolCall" as const,
				id: `call-${turn}-${index}`,
				name: "read",
				arguments: { path },
			})),
			timestamp + 1,
		),
	);
	files.forEach((path, index) => {
		harness.sessionManager.appendMessage({
			role: "toolResult",
			toolCallId: `call-${turn}-${index}`,
			toolName: "read",
			content: [{ type: "text", text: `contents of ${path}\n${"line\n".repeat(30 * (index + 1))}` }],
			isError: false,
			timestamp: timestamp + 2,
		} satisfies ToolResultMessage);
	});
	harness.sessionManager.appendMessage(
		assistant(harness, [{ type: "text", text: `done with turn ${turn}` }], timestamp + 3),
	);
	harness.session.agent.state.messages = harness.sessionManager.buildSessionContext().messages;
}

/** A session whose compaction splits its last turn: a history summary and a turn prefix summary. */
async function createSplitTurnHarness(): Promise<Harness> {
	const harness = await createHarness({
		models: [{ id: "faux-1m", contextWindow: 1_000_000, maxTokens: 64_000 }],
		settings: { compaction: { keepRecentTokens: 600, reserveTokens: 16_384 } },
	});
	seedTurn(harness, 1, ["src/a.ts", "src/b.ts"]);
	seedTurn(harness, 2, ["src/c.ts", "src/d.ts", "src/e.ts", "src/f.ts", "src/g.ts", "src/h.ts"]);
	return harness;
}

/** Which of the two split-turn summary requests `context` is. */
function summaryKind(context: TranscriptContext): "history" | "turnPrefix" {
	const text = JSON.stringify(context.messages);
	return text.includes("# Conversation") ? "turnPrefix" : "history";
}

function waitForAbort(signal: AbortSignal | undefined): Promise<void> {
	return new Promise((resolve) => {
		if (!signal || signal.aborted) return resolve();
		signal.addEventListener("abort", () => resolve(), { once: true });
	});
}

describe("split-turn compaction", () => {
	const harnesses: Harness[] = [];

	afterEach(() => {
		while (harnesses.length > 0) harnesses.pop()?.cleanup();
	});

	it("sends the history and turn prefix summary requests at once and merges them in order", async () => {
		const harness = await createSplitTurnHarness();
		harnesses.push(harness);
		const started: string[] = [];
		let releaseBoth = () => {};
		const bothStarted = new Promise<void>((resolve) => {
			releaseBoth = resolve;
		});
		let inFlight = 0;
		let maxInFlight = 0;
		// Each response waits until both requests have started: run one after the other, the first never
		// completes, and the timeout fails the test.
		const respond = async (context: TranscriptContext) => {
			const kind = summaryKind(context);
			started.push(kind);
			inFlight++;
			maxInFlight = Math.max(maxInFlight, inFlight);
			if (started.length === 2) releaseBoth();
			await bothStarted;
			// The turn prefix answers first, so the merge order cannot come from the completion order.
			if (kind === "history") await new Promise((resolve) => setTimeout(resolve, 10));
			inFlight--;
			return fauxAssistantMessage(kind === "history" ? "HISTORY SUMMARY" : "PREFIX SUMMARY");
		};
		harness.setResponses([respond, respond]);

		const result = await harness.session.compact();

		expect(started.sort()).toEqual(["history", "turnPrefix"]);
		expect(maxInFlight).toBe(2);
		expect(
			result.summary.startsWith("HISTORY SUMMARY\n\n---\n\n**Turn Context (split turn):**\n\nPREFIX SUMMARY"),
		).toBe(true);
		expect(result.usage?.input).toBeGreaterThan(0);
	});

	it("fails with the first error and aborts the other request", async () => {
		const harness = await createSplitTurnHarness();
		harnesses.push(harness);
		let historySignalAborted = false;
		const respond = async (context: TranscriptContext, options: SimpleStreamOptions | undefined) => {
			if (summaryKind(context) === "turnPrefix") {
				return fauxAssistantMessage("", { stopReason: "error", errorMessage: "invalid request: prefix rejected" });
			}
			await waitForAbort(options?.signal);
			historySignalAborted = options?.signal?.aborted === true;
			return fauxAssistantMessage("HISTORY SUMMARY");
		};
		harness.setResponses([respond, respond]);

		await expect(harness.session.compact()).rejects.toThrow(
			"Turn prefix summarization failed: invalid request: prefix rejected",
		);
		expect(historySignalAborted).toBe(true);
		expect(harness.sessionManager.getEntries().some((entry) => entry.type === "compaction")).toBe(false);
	});
});
