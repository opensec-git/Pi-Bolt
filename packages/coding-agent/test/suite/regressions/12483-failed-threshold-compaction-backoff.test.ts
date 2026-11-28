import type { AgentTool } from "@earendil-works/pi-agent-core";
import {
	type AssistantMessage,
	createAssistantMessageEventStream,
	fauxAssistantMessage,
	fauxToolCall,
	getCurrentSystemPrompt,
} from "@earendil-works/pi-ai";
import { Type } from "typebox";
import { afterEach, describe, expect, it } from "vitest";
import { createHarness, type Harness } from "../harness.ts";

const largeTool: AgentTool = {
	name: "large_result",
	label: "Large result",
	description: "Returns enough content to cross the compaction threshold",
	parameters: Type.Object({}),
	execute: async () => ({ content: [{ type: "text", text: "x".repeat(2000) }], details: {} }),
};

function usage(input: number) {
	return {
		input,
		output: 10,
		cacheRead: 0,
		cacheWrite: 0,
		totalTokens: input + 10,
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
	};
}

/**
 * Model stub: summary requests stop at the token cap; ordinary turns call `large_result` until
 * `toolTurns` turns have run, then answer. Returns live counters.
 */
// Turn usage 2400 is above the threshold (2600 - 400) but inside the window, so only threshold compaction runs.
function useFailingSummaryModel(harness: Harness, toolTurns: number, summaryStop: "length" | "stop" = "length") {
	const counts = { summaries: 0, turns: 0 };
	harness.session.agent.streamFunction = (model, context) => {
		const stream = createAssistantMessageEventStream();
		const isSummary = getCurrentSystemPrompt(context.messages).startsWith(
			"You are a context summarization assistant",
		);
		let message: AssistantMessage;
		if (isSummary) {
			counts.summaries++;
			message = { ...fauxAssistantMessage("partial summary"), stopReason: summaryStop };
		} else {
			counts.turns++;
			message =
				counts.turns % (toolTurns + 1) !== 0
					? fauxAssistantMessage(fauxToolCall("large_result", {}), { stopReason: "toolUse" })
					: fauxAssistantMessage("done");
		}
		message = {
			...message,
			api: model.api,
			provider: model.provider,
			model: model.id,
			usage: usage(isSummary ? 10 : 2400),
		};
		queueMicrotask(() => stream.push({ type: "done", reason: message.stopReason, message } as never));
		return stream;
	};
	return counts;
}

// upstream #12483 / #12242: a failed threshold compaction must not be retried before every turn.
describe("issue #12483 failed threshold compaction backs off", () => {
	const harnesses: Harness[] = [];

	afterEach(() => {
		while (harnesses.length > 0) harnesses.pop()?.cleanup();
	});

	async function setup(): Promise<Harness> {
		const harness = await createHarness({
			models: [{ id: "faux-1", contextWindow: 2600, maxTokens: 100 }],
			settings: { compaction: { enabled: true, reserveTokens: 400, keepRecentTokens: 1 } },
			tools: [largeTool],
		});
		harnesses.push(harness);
		return harness;
	}

	it("sends one failed summary per user prompt and reports the error once", async () => {
		const harness = await setup();
		const counts = useFailingSummaryModel(harness, 6);
		// One compaction attempt may send two summary requests (history and split turn prefix).
		const summariesPerAttempt: number[] = [];
		harness.session.subscribe((event) => {
			if (event.type === "compaction_end") summariesPerAttempt.push(counts.summaries);
		});

		await harness.session.prompt("start");

		expect(counts.turns).toBe(7);
		expect(summariesPerAttempt).toHaveLength(1);
		expect(counts.summaries).toBe(summariesPerAttempt[0]);
		const failures = harness.eventsOfType("compaction_end").filter((event) => event.errorMessage);
		expect(failures).toHaveLength(1);
		expect(failures[0]?.reason).toBe("threshold");
		expect(failures[0]?.errorMessage).toContain("Auto-compaction failed");

		// The next user prompt may try again, once.
		await harness.session.prompt("again");
		expect(summariesPerAttempt).toHaveLength(2);
		expect(harness.eventsOfType("compaction_end").filter((event) => event.errorMessage)).toHaveLength(2);
	});

	it("still compacts when the summary succeeds", async () => {
		const harness = await setup();
		const counts = useFailingSummaryModel(harness, 2, "stop");

		await harness.session.prompt("start");

		expect(counts.summaries).toBeGreaterThan(0);
		expect(harness.eventsOfType("compaction_end").filter((event) => event.errorMessage)).toEqual([]);
		expect(harness.sessionManager.getEntries().some((entry) => entry.type === "compaction")).toBe(true);
	});
});
