import { appendFileSync, mkdirSync, mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { AgentTool } from "@earendil-works/pi-agent-core";
import { fauxAssistantMessage, fauxText, fauxThinking, fauxToolCall } from "@earendil-works/pi-ai";
import { Type } from "typebox";
import { afterEach, describe, expect, it } from "vitest";
import { SessionManager } from "../../src/core/session-manager.ts";
import { capMessage, findRun, SUBAGENT_STREAM_FEATURE, SubagentStreams } from "../../src/core/subagent-stream.ts";
import { generateKeyPair } from "../../src/remote-control/noise.ts";
import { RemoteControlServer } from "../../src/remote-control/server.ts";
import { RemoteControlStore } from "../../src/remote-control/store.ts";
import { createHarness, type Harness } from "./harness.ts";
import { connectTestClient, type TestClient } from "./remote-control-client.ts";

const AGENT_ID = "abcdef1234567890x";
const CHILD_PROMPT = "CHILD: list the files";

type Message = Record<string, unknown>;

function streamMessages(client: TestClient, runId: string): Message[] {
	return client.received.filter(
		(message) =>
			(message.type === "subagent_entries" || message.type === "subagent_events") && message.runId === runId,
	);
}

function entryMessages(client: TestClient, runId: string): Message[] {
	return streamMessages(client, runId)
		.filter((message) => message.type === "subagent_entries")
		.flatMap((message) => message.messages as Message[]);
}

function events(client: TestClient, runId: string): Message[] {
	return streamMessages(client, runId)
		.filter((message) => message.type === "subagent_events")
		.flatMap((message) => message.events as Message[]);
}

async function until(check: () => boolean, timeoutMs = 5000): Promise<void> {
	const deadline = Date.now() + timeoutMs;
	while (!check()) {
		if (Date.now() > deadline) throw new Error("timed out");
		await new Promise((resolve) => setTimeout(resolve, 20));
	}
}

describe("remote control subagent streams", () => {
	const cleanups: Array<() => unknown> = [];

	afterEach(async () => {
		while (cleanups.length > 0) await cleanups.pop()?.();
	});

	/**
	 * A parent session whose `Agent` tool runs a child session the way the subagents extension does: saved next to the
	 * parent's file, naming it as `parentSession`, named `<type>#<id>`. The child's reply waits for [release].
	 */
	async function setup() {
		const dir = mkdtempSync(join(tmpdir(), "pi-subagent-stream-"));
		cleanups.push(() => rmSync(dir, { recursive: true, force: true }));
		const sessionDir = join(dir, "sessions");
		mkdirSync(sessionDir);
		let release: () => void = () => {};
		const gate = new Promise<void>((resolve) => {
			release = resolve;
		});
		let child: Harness | undefined;
		let parent: Harness | undefined;
		const agentTool: AgentTool = {
			name: "Agent",
			label: "Agent",
			description: "Runs a subagent",
			parameters: Type.Object({ prompt: Type.String(), description: Type.String() }),
			execute: async (_id, params) => {
				const sessionManager = SessionManager.create(dir, sessionDir, {
					parentSession: parent?.session.sessionFile,
				});
				const childHarness = await createHarness({ provider: "faux-child", sessionManager });
				cleanups.push(() => childHarness.cleanup());
				childHarness.session.setSessionName(`general-purpose#${AGENT_ID.slice(0, 8)}`);
				childHarness.setResponses([
					async () => {
						await gate;
						return fauxAssistantMessage([fauxThinking("thinking about the files"), fauxText("two files")]);
					},
				]);
				child = childHarness;
				await childHarness.session.prompt((params as { prompt: string }).prompt);
				return {
					content: [{ type: "text", text: "two files" }],
					details: { agentId: AGENT_ID, status: "completed" },
				};
			},
		};
		parent = await createHarness({ sessionManager: SessionManager.create(dir, sessionDir), tools: [agentTool] });
		cleanups.push(() => parent?.cleanup());
		parent.setResponses([
			fauxAssistantMessage(fauxToolCall("Agent", { prompt: CHILD_PROMPT, description: "List" }, { id: "run-1" }), {
				stopReason: "toolUse",
			}),
			fauxAssistantMessage("parent done"),
		]);
		const store = new RemoteControlStore(join(dir, "rc"));
		const server = new RemoteControlServer({
			store,
			getSession: () => parent?.session,
			hostName: "test-host",
			version: "9.9.9",
			bindAddress: "127.0.0.1",
			subagentStreams: { pollMs: 20, eventBatchMs: 10, limits: { maxStreams: 2 } },
		});
		const port = await server.start();
		cleanups.push(() => server.stop());
		const uri = () => server.pairingUri([`ws://127.0.0.1:${port}/rc`]);
		const connect = async () => {
			const client = await connectTestClient(uri(), generateKeyPair());
			cleanups.push(() => client.close());
			return client;
		};
		return {
			parent,
			server,
			connect,
			release: () => release(),
			child: () => child,
			sessionDir,
		};
	}

	it("advertises the feature in remote_hello", async () => {
		const { connect } = await setup();
		const phone = await connect();
		const hello = await phone.waitFor((message) => message.type === "remote_hello");
		expect(hello.features).toEqual([SUBAGENT_STREAM_FEATURE]);
	});

	it("streams a running subagent: its saved messages, then its thinking and text as they stream", async () => {
		const { parent, connect, release, child } = await setup();
		const phone = await connect();
		const done = parent.session.prompt("go");
		await until(() => child()?.session.messages.some((message) => message.role === "user") === true);

		const subscribed = await phone.request({ type: "subagent_subscribe", runId: "run-1" });
		expect(subscribed).toMatchObject({ success: true, command: "subagent_subscribe" });
		const first = await phone.waitFor((message) => message.type === "subagent_entries" && message.runId === "run-1");
		expect(first.reset).toBe(true);
		expect(first.file).toBe(child()?.session.sessionFile);
		expect((first.messages as Message[]).map((message) => message.role)).toEqual(["user"]);

		release();
		await until(() => events(phone, "run-1").some((event) => event.type === "message_end"));
		const types = events(phone, "run-1").map((event) => event.type);
		expect(types).toContain("message_start");
		const deltas = events(phone, "run-1")
			.filter((event) => event.type === "message_update")
			.map((event) => (event.assistantMessageEvent as { type: string }).type);
		expect(deltas).toContain("thinking_delta");
		expect(deltas).toContain("text_delta");
		// Deltas carry no cumulative snapshot (as in the RPC event stream).
		expect(events(phone, "run-1").every((event) => event.type !== "message_update" || !("message" in event))).toBe(
			true,
		);

		// The finished message arrives from the session file, after the stream.
		await until(() => entryMessages(phone, "run-1").some((message) => message.role === "assistant"));
		await done;
		const assistant = entryMessages(phone, "run-1").find((message) => message.role === "assistant");
		expect(JSON.stringify(assistant)).toContain("two files");
	});

	it("reads a finished run from its file, matched by the agent id of its result", async () => {
		const { parent, connect, release } = await setup();
		release();
		await parent.session.prompt("go");
		const phone = await connect();
		expect((await phone.request({ type: "subagent_subscribe", runId: "run-1" })).success).toBe(true);
		await until(() => entryMessages(phone, "run-1").length >= 2);
		expect(entryMessages(phone, "run-1").map((message) => message.role)).toEqual(["user", "assistant"]);
	});

	it("follows lines appended to the file and waits for a line's end", async () => {
		const { parent, connect, release, child } = await setup();
		release();
		await parent.session.prompt("go");
		const file = child()?.session.sessionFile as string;
		// Closed: the stream then reads the file only.
		child()?.session.dispose();
		const phone = await connect();
		await phone.request({ type: "subagent_subscribe", runId: "run-1" });
		await until(() => entryMessages(phone, "run-1").length === 2);
		const line = JSON.stringify({
			type: "message",
			id: "x1",
			parentId: null,
			timestamp: new Date().toISOString(),
			message: { role: "user", content: "appended later", timestamp: 1 },
		});
		appendFileSync(file, line.slice(0, 20));
		await new Promise((resolve) => setTimeout(resolve, 100));
		expect(entryMessages(phone, "run-1")).toHaveLength(2);
		appendFileSync(file, `${line.slice(20)}\n`);
		await until(() => entryMessages(phone, "run-1").length === 3);
		expect(entryMessages(phone, "run-1")[2]).toMatchObject({ role: "user", content: "appended later" });
	});

	it("stops on unsubscribe and refuses unknown runs and too many streams", async () => {
		const { parent, connect, release, child } = await setup();
		release();
		await parent.session.prompt("go");
		const phone = await connect();

		expect(await phone.request({ type: "subagent_subscribe" })).toMatchObject({
			success: false,
			error: "runId is required",
		});
		expect(await phone.request({ type: "subagent_subscribe", runId: "nope" })).toMatchObject({ success: false });

		await phone.request({ type: "subagent_subscribe", runId: "run-1" });
		await until(() => entryMessages(phone, "run-1").length === 2);
		expect((await phone.request({ type: "subagent_unsubscribe", runId: "run-1" })).success).toBe(true);
		const before = streamMessages(phone, "run-1").length;
		child()?.setResponses([fauxAssistantMessage("more")]);
		await child()?.session.prompt("again");
		await new Promise((resolve) => setTimeout(resolve, 200));
		expect(streamMessages(phone, "run-1")).toHaveLength(before);
	});

	it("caps streams per phone", async () => {
		const { parent, release } = await setup();
		release();
		await parent.session.prompt("go");
		const streams = new SubagentStreams({
			getSession: () => parent.session,
			send: () => {},
			limits: { maxStreams: 1 },
		});
		cleanups.push(() => streams.clear());
		streams.subscribe("run-1");
		streams.subscribe("run-1");
		expect(streams.size).toBe(1);
		parent.session.messages.push({
			role: "assistant",
			content: [{ type: "toolCall", id: "run-2", name: "Agent", arguments: { prompt: "other" } }],
		} as never);
		expect(() => streams.subscribe("run-2")).toThrow("At most 1 subagent streams at once");
		expect(streams.unsubscribe("run-1")).toBe(true);
		expect(streams.size).toBe(0);
	});
});

describe("subagent stream helpers", () => {
	it("finds an Agent call's prompt and the agent id of its result", () => {
		const messages = [
			{ role: "assistant", content: [{ type: "toolCall", id: "c1", name: "Agent", arguments: { prompt: "p" } }] },
			{ role: "toolResult", toolCallId: "c1", details: { agentId: "id-1" } },
			{ role: "assistant", content: [{ type: "toolCall", id: "c2", name: "read", arguments: {} }] },
			{
				role: "assistant",
				content: [{ type: "toolCall", id: "c3", name: "Agent", arguments: { prompt: "q", resume: "id-0" } }],
			},
		];
		expect(findRun(messages, "c1")).toEqual({ prompt: "p", agentId: "id-1" });
		expect(findRun(messages, "c2")).toBeUndefined();
		expect(findRun(messages, "c3")).toEqual({ prompt: "q", agentId: "id-0" });
	});

	it("cuts large messages and keeps small ones as they are", () => {
		const small = { role: "assistant", content: [{ type: "text", text: "hi" }] };
		expect(capMessage(small, 1024)).toBe(small);
		const big = {
			role: "toolResult",
			details: { blob: "x".repeat(10_000) },
			content: [
				{ type: "text", text: "y".repeat(50_000) },
				{ type: "image", data: "z".repeat(50_000), mimeType: "image/png" },
			],
		};
		const capped = capMessage(big, 8 * 1024);
		expect(JSON.stringify(capped).length).toBeLessThan(8 * 1024);
		expect(capped.details).toBeUndefined();
		expect((capped.content as Array<{ type: string }>).map((block) => block.type)).toEqual(["text", "text"]);
	});
});
