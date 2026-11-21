import type { AgentTool } from "@earendil-works/pi-agent-core";
import { fauxAssistantMessage, fauxToolCall } from "@earendil-works/pi-ai";
import { Type } from "typebox";
import { afterEach, describe, expect, it } from "vitest";
import type { AgentSessionEvent } from "../../src/core/agent-session.ts";
import { createHarness, type Harness } from "./harness.ts";

const echoTool: AgentTool = {
	name: "echo",
	label: "Echo",
	description: "Echo the text back",
	parameters: Type.Object({ text: Type.String() }),
	execute: async (_toolCallId, params) => ({
		content: [{ type: "text", text: (params as { text: string }).text }],
		details: {},
	}),
};

function describeEvent(event: AgentSessionEvent): string {
	if (event.type === "message_start" || event.type === "message_end") return `${event.type}:${event.message.role}`;
	if (event.type === "message_update") return `message_update:${event.assistantMessageEvent.type}`;
	return event.type;
}

/** The events of one prompt that makes a tool call and then answers. */
const TOOL_RUN = [
	"agent_start",
	"turn_start",
	"message_start:system",
	"message_end:system",
	"message_start:user",
	"message_end:user",
	"message_start:assistant",
	"message_update:toolcall_start",
	"message_update:toolcall_delta",
	"message_update:toolcall_end",
	"message_end:assistant",
	"tool_execution_start",
	"tool_execution_end",
	"message_start:toolResult",
	"message_end:toolResult",
	"turn_end",
	"turn_start",
	"message_start:assistant",
	"message_update:text_start",
	"message_update:text_delta",
	"message_update:text_end",
	"message_end:assistant",
	"turn_end",
	"agent_end",
	"agent_settled",
];

describe("AgentSession event dispatch", () => {
	let harness: Harness | undefined;

	afterEach(() => {
		harness?.cleanup();
		harness = undefined;
	});

	it("delivers agent events to session listeners in order when no extension handles them", async () => {
		harness = await createHarness({ tools: [echoTool] });
		harness.setResponses([
			fauxAssistantMessage([fauxToolCall("echo", { text: "x" })], { stopReason: "toolUse" }),
			fauxAssistantMessage("done"),
		]);

		await harness.session.prompt("go");

		const events = harness.events.filter((event) => event.type !== "entry_appended").map(describeEvent);
		expect(events).toEqual(TOOL_RUN);
		expect(harness.session.messages.map((message) => message.role)).toEqual([
			"system",
			"user",
			"assistant",
			"toolResult",
			"assistant",
		]);
	});

	it("runs extension handlers first and waits for them before session listeners see the event", async () => {
		const order: string[] = [];
		let pendingHandlers = 0;
		harness = await createHarness({
			tools: [echoTool],
			extensionFactories: [
				(pi) => {
					const record = async (event: { type: string }) => {
						pendingHandlers++;
						order.push(`extension:${event.type}`);
						await new Promise((resolve) => setTimeout(resolve, 1));
						pendingHandlers--;
					};
					pi.on("agent_start", record);
					pi.on("turn_start", record);
					pi.on("message_start", record);
					pi.on("message_update", record);
					pi.on("message_end", record);
					pi.on("tool_execution_start", record);
					pi.on("tool_execution_end", record);
					pi.on("agent_end", record);
				},
			],
		});
		harness.session.subscribe((event) => {
			if (event.type === "entry_appended" || event.type === "turn_end" || event.type === "agent_settled") return;
			expect(pendingHandlers).toBe(0);
			order.push(`session:${event.type}`);
		});
		harness.setResponses([
			fauxAssistantMessage([fauxToolCall("echo", { text: "x" })], { stopReason: "toolUse" }),
			fauxAssistantMessage("done"),
		]);

		await harness.session.prompt("go");

		const expected = TOOL_RUN.filter((event) => event !== "turn_end" && event !== "agent_settled").flatMap(
			(event) => {
				const type = event.split(":")[0];
				return [`extension:${type}`, `session:${type}`];
			},
		);
		expect(order).toEqual(expected);
	});

	it("picks up a message_update handler registered and removed during the stream", async () => {
		const seen: string[] = [];
		let listenForNextUpdate: () => void;
		harness = await createHarness({
			extensionFactories: [
				(pi) => {
					listenForNextUpdate = () => {
						const unsubscribe = pi.on("message_update", async (event) => {
							await Promise.resolve();
							seen.push(`extension:${event.assistantMessageEvent.type}`);
							unsubscribe();
						});
					};
				},
			],
		});
		harness.session.subscribe((event) => {
			if (event.type !== "message_update") return;
			seen.push(`session:${event.assistantMessageEvent.type}`);
			if (event.assistantMessageEvent.type === "text_start") listenForNextUpdate();
		});
		harness.setResponses([fauxAssistantMessage("hi")]);

		await harness.session.prompt("go");

		expect(seen).toEqual(["session:text_start", "extension:text_delta", "session:text_delta", "session:text_end"]);
	});

	it("applies a message_end handler's replacement before session listeners and persistence see the message", async () => {
		harness = await createHarness({
			extensionFactories: [
				(pi) => {
					pi.on("message_end", (event) => {
						if (event.message.role !== "assistant") return undefined;
						return { message: { ...event.message, content: [{ type: "text", text: "replaced" }] } };
					});
				},
			],
		});
		harness.setResponses([fauxAssistantMessage("original")]);
		const seen: string[] = [];
		harness.session.subscribe((event) => {
			if (event.type === "message_end" && event.message.role === "assistant") {
				const content = event.message.content[0];
				seen.push(content?.type === "text" ? content.text : "");
			}
		});

		await harness.session.prompt("go");

		expect(seen).toEqual(["replaced"]);
		const persisted = harness.sessionManager
			.getEntries()
			.flatMap((entry) => (entry.type === "message" && entry.message.role === "assistant" ? [entry.message] : []));
		expect(persisted.map((message) => message.content)).toEqual([[{ type: "text", text: "replaced" }]]);
	});
});
