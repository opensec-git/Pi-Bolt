// Regression test for the outside review's claim A9 (w132 verify-core), fixed in agent-loop.ts deliverUpdate().
import type { AssistantMessage } from "@earendil-works/pi-ai";
import { obj } from "@earendil-works/pi-schema";
import { describe, expect, it } from "vitest";
import { runToolCall } from "../src/agent-loop.ts";
import type { AgentTool, AgentToolCall } from "../src/types.ts";

const assistantMessage: AssistantMessage = {
	role: "assistant",
	content: [],
	api: "openai-responses",
	provider: "openai",
	model: "mock",
	usage: {
		input: 0,
		output: 0,
		cacheRead: 0,
		cacheWrite: 0,
		totalTokens: 0,
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
	},
	stopReason: "toolUse",
	timestamp: 0,
};

describe("claim A9: a rejected tool update while the tool still runs", () => {
	// claim A9: executePreparedToolCall() keeps the promise of each update in an array and attaches a handler only when
	// the tool has finished. An update whose listener rejects while the tool still runs is an unhandled rejection
	// in the meantime (Node and Bun end the process on it unless a handler is installed), and runToolCall() then
	// rejects, although it documents that it never rejects.
	it("handles the rejection and still returns the tool's outcome", async () => {
		const tool: AgentTool = {
			name: "slow",
			label: "Slow",
			description: "Reports progress, then works a little longer",
			parameters: obj({}),
			async execute(_id, _params, _signal, onUpdate) {
				onUpdate?.({ content: [{ type: "text", text: "progress" }], details: {} });
				await new Promise((resolve) => setTimeout(resolve, 50));
				return { content: [{ type: "text", text: "done" }], details: {} };
			},
		};
		const toolCall: AgentToolCall = { type: "toolCall", id: "call-1", name: "slow", arguments: {} };
		const unhandled: unknown[] = [];
		const onUnhandled = (reason: unknown) => unhandled.push(reason);
		const listeners = process.listeners("unhandledRejection");
		process.removeAllListeners("unhandledRejection");
		process.on("unhandledRejection", onUnhandled);
		let outcome: unknown;
		try {
			outcome = await runToolCall(toolCall, {
				tools: [tool],
				assistantMessage,
				context: { messages: [] },
				onUpdate: () => Promise.reject(new Error("listener failed")),
			}).catch((error: unknown) => ({ rejected: error instanceof Error ? error.message : String(error) }));
		} finally {
			process.off("unhandledRejection", onUnhandled);
			for (const listener of listeners) process.on("unhandledRejection", listener);
		}
		expect(unhandled.map((reason) => (reason instanceof Error ? reason.message : String(reason)))).toEqual([]);
		expect(outcome).toMatchObject({ result: { content: [{ type: "text", text: "done" }] }, isError: false });
	});
});
