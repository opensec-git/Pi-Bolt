import { appendFileSync, mkdirSync, mkdtempSync, rmSync, utimesSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { AgentTool } from "@earendil-works/pi-agent-core";
import { fauxAssistantMessage, fauxText, fauxThinking, fauxToolCall } from "@earendil-works/pi-ai";
import { Type } from "typebox";
import { afterEach, describe, expect, it } from "vitest";
import { registerLiveSession, unregisterLiveSession } from "../../src/core/live-sessions.ts";
import { SessionManager } from "../../src/core/session-manager.ts";
import {
	capMessage,
	capUpdate,
	findRun,
	SUBAGENT_STREAM_FEATURE,
	SubagentStreams,
} from "../../src/core/subagent-stream.ts";
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
		expect(hello.features).toContain(SUBAGENT_STREAM_FEATURE);
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

	it("sends message_start as it was when it happened, not as the provider changed it later", async () => {
		const { parent, connect, release, child } = await setup();
		const phone = await connect();
		const done = parent.session.prompt("go");
		await until(() => child()?.session.messages.some((message) => message.role === "user") === true);
		await phone.request({ type: "subagent_subscribe", runId: "run-1" });
		await phone.waitFor((message) => message.type === "subagent_entries" && message.runId === "run-1");
		// Providers append to the streaming message's content in place while a batch waits to be sent.
		child()?.session.subscribe((event) => {
			if (event.type === "message_start" && event.message.role === "assistant") {
				(event.message.content as unknown[]).push({ type: "text", text: "APPENDED LATER" });
			}
		});
		release();
		await until(() => events(phone, "run-1").some((event) => event.type === "message_start"));
		await done;
		const start = events(phone, "run-1").find((event) => event.type === "message_start")?.message as Message;
		expect(JSON.stringify(start)).not.toContain("APPENDED LATER");
	});

	it("sends the streamed events before the saved message they led to", async () => {
		const { parent, release, child } = await setup();
		const done = parent.session.prompt("go");
		await until(() => child()?.session.messages.some((message) => message.role === "user") === true);
		const sent: Message[] = [];
		// A batch that never fills on its own: only the saved message can push it out.
		const streams = new SubagentStreams({
			getSession: () => parent.session,
			send: (message) => sent.push(message as Message),
			pollMs: 20,
			eventBatchMs: 60_000,
		});
		cleanups.push(() => streams.clear());
		streams.subscribe("run-1");
		await until(() => sent.length > 0);
		release();
		await done;
		await until(() =>
			sent.some(
				(message) =>
					message.type === "subagent_entries" &&
					(message.messages as Message[]).some((entry) => entry.role === "assistant"),
			),
		);
		const savedAt = sent.findIndex(
			(message) =>
				message.type === "subagent_entries" &&
				(message.messages as Message[]).some((entry) => entry.role === "assistant"),
		);
		const endAt = sent.findIndex(
			(message) =>
				message.type === "subagent_events" &&
				(message.events as Message[]).some((event) => event.type === "message_end"),
		);
		expect(endAt).toBeGreaterThanOrEqual(0);
		expect(endAt).toBeLessThan(savedAt);
	});

	it("does not listen again when a stream stops right after a message ends", async () => {
		const { parent, release, child } = await setup();
		const done = parent.session.prompt("go");
		await until(() => child()?.session.messages.some((message) => message.role === "user") === true);
		const sent: Message[] = [];
		const streams = new SubagentStreams({
			getSession: () => parent.session,
			send: (message) => sent.push(message as Message),
			pollMs: 20,
			eventBatchMs: 10,
		});
		cleanups.push(() => streams.clear());
		streams.subscribe("run-1");
		await until(() => sent.length > 0);
		// Stops the stream between its message_end listener and the poll that listener deferred.
		child()?.session.subscribe((event) => {
			if (event.type === "message_end" && event.message.role === "assistant") streams.unsubscribe("run-1");
		});
		release();
		await done;
		await new Promise((resolve) => setTimeout(resolve, 100));
		const before = sent.length;
		child()?.setResponses([fauxAssistantMessage("more")]);
		await child()?.session.prompt("again");
		await new Promise((resolve) => setTimeout(resolve, 200));
		expect(streams.size).toBe(0);
		expect(sent).toHaveLength(before);
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

describe("subagent stream binding", () => {
	const cleanups: Array<() => unknown> = [];

	afterEach(async () => {
		while (cleanups.length > 0) await cleanups.pop()?.();
	});

	/** A parent session with no live children: its runs are pushed as messages, its agents written as files. */
	async function files() {
		const dir = mkdtempSync(join(tmpdir(), "pi-subagent-binding-"));
		cleanups.push(() => rmSync(dir, { recursive: true, force: true }));
		const sessionDir = join(dir, "sessions");
		mkdirSync(sessionDir);
		const parent = await createHarness({ sessionManager: SessionManager.create(dir, sessionDir) });
		cleanups.push(() => parent.cleanup());
		const parentFile = parent.session.sessionFile as string;
		const sent: Message[] = [];
		const streams = new SubagentStreams({
			getSession: () => parent.session,
			send: (message) => sent.push(message as Message),
			pollMs: 20,
		});
		cleanups.push(() => streams.clear());
		const call = (runId: string, args: Message, timestamp?: number) =>
			parent.session.messages.push({
				role: "assistant",
				content: [{ type: "toolCall", id: runId, name: "Agent", arguments: args }],
				...(timestamp === undefined ? {} : { timestamp }),
			} as never);
		const result = (runId: string, agentId: string) =>
			parent.session.messages.push({
				role: "toolResult",
				toolCallId: runId,
				toolName: "Agent",
				content: [],
				details: { agentId },
			} as never);
		/** An agent's session file, made at [created] and last written at [mtime] (Unix ms). */
		const agent = (agentId: string, prompt: string, reply: string, created: number, mtime = created) => {
			const file = join(sessionDir, `${agentId}.jsonl`);
			const lines = [
				{
					type: "session",
					id: agentId,
					timestamp: new Date(created).toISOString(),
					cwd: dir,
					parentSession: parentFile,
				},
				{ type: "session_info", name: `general-purpose#${agentId.slice(0, 8)}` },
				{ type: "message", message: { role: "user", content: prompt, timestamp: created } },
				{
					type: "message",
					message: { role: "assistant", content: [{ type: "text", text: reply }], timestamp: created },
				},
			];
			writeFileSync(file, `${lines.map((line) => JSON.stringify(line)).join("\n")}\n`);
			utimesSync(file, mtime / 1000, mtime / 1000);
			return file;
		};
		const replies = (runId: string) =>
			sent
				.filter((message) => message.type === "subagent_entries" && message.runId === runId)
				.flatMap((message) => message.messages as Message[])
				.filter((message) => message.role === "assistant")
				.map((message) => JSON.stringify(message.content));
		const resets = (runId: string) =>
			sent.filter((message) => message.type === "subagent_entries" && message.runId === runId && message.reset);
		/** An agent's session open in this process, streaming an answer, as the stream sees it. */
		const live = (
			file: string,
			name: string,
			messages: Message[],
			created: number,
			state: { streaming?: boolean; pendingToolCalls?: string[] } = {},
		) => {
			const listeners: Array<(event: unknown) => void> = [];
			const session = {
				sessionFile: file,
				sessionName: name,
				sessionManager: {
					getHeader: () => ({ parentSession: parentFile, timestamp: new Date(created).toISOString() }),
				},
				messages,
				isStreaming: true,
				agent: {
					state: {
						streamingMessage:
							state.streaming === false
								? undefined
								: { role: "assistant", content: [{ type: "text", text: "partial" }] },
						pendingToolCalls: new Set(state.pendingToolCalls ?? []),
					},
				},
				subscribe: (listener: (event: unknown) => void) => {
					listeners.push(listener);
					return () => listeners.splice(listeners.indexOf(listener), 1);
				},
			};
			registerLiveSession(session as never);
			cleanups.push(() => unregisterLiveSession(session as never));
			/** Sends [event] to the session's listeners, as the session would. */
			return (event: unknown) => {
				for (const listener of [...listeners]) listener(event);
			};
		};
		const isStart = (message: Message) =>
			message.type === "subagent_events" &&
			(message.events as Message[]).some((event) => event.type === "message_start");
		return { parent, streams, call, result, agent, replies, resets, sent, live, isStart };
	}

	it("does not bind a repeated prompt to an earlier run's agent", async () => {
		const { streams, call, result, agent, replies } = await files();
		const t = Date.now() - 60_000;
		call("run-1", { prompt: "same" }, t);
		result("run-1", "11111111aaaa");
		call("run-2", { prompt: "same" }, t + 10_000);
		// The earlier agent was written last, so it is the newest file.
		agent("11111111aaaa", "same", "first reply", t + 1_000, t + 30_000);
		agent("22222222bbbb", "same", "second reply", t + 11_000, t + 20_000);
		streams.subscribe("run-2");
		await until(() => replies("run-2").length > 0);
		expect(replies("run-2").join()).toContain("second reply");
		expect(replies("run-2").join()).not.toContain("first reply");
	});

	it("skips an agent made before the call even when the earlier run's result is gone (compacted)", async () => {
		const { streams, call, agent, replies } = await files();
		const t = Date.now() - 60_000;
		call("run-2", { prompt: "same" }, t + 10_000);
		agent("11111111aaaa", "same", "first reply", t + 1_000, t + 30_000);
		agent("22222222bbbb", "same", "second reply", t + 11_000, t + 20_000);
		streams.subscribe("run-2");
		await until(() => replies("run-2").length > 0);
		expect(replies("run-2")).toEqual([expect.stringContaining("second reply")]);
	});

	it("lets go of a session found by prompt once the result names another agent", async () => {
		const { streams, call, result, agent, replies, resets } = await files();
		const t = Date.now() - 60_000;
		// Two calls with the same prompt in one message: only the result tells them apart.
		call("run-a", { prompt: "same" }, t);
		call("run-b", { prompt: "same" }, t);
		agent("aaaaaaaa1111", "same", "reply a", t + 1_000, t + 30_000);
		agent("bbbbbbbb2222", "same", "reply b", t + 1_000, t + 20_000);
		streams.subscribe("run-b");
		await until(() => replies("run-b").length > 0);
		expect(replies("run-b")).toEqual([expect.stringContaining("reply a")]);
		result("run-b", "bbbbbbbb2222");
		await until(() => replies("run-b").some((reply) => reply.includes("reply b")));
		expect(resets("run-b")).toHaveLength(2);
	});

	it("sends the whole saved history before the streaming message's snapshot", async () => {
		const { streams, call, result, agent, sent, live, isStart } = await files();
		const t = Date.now() - 60_000;
		call("run-1", { prompt: "long" }, t);
		result("run-1", "11111111aaaa");
		const file = agent("11111111aaaa", "long", "first", t + 1_000);
		// Two saved answers of 700 KB: more than one read of the file.
		for (const text of ["a", "b"]) {
			const line = {
				type: "message",
				message: { role: "assistant", content: [{ type: "text", text: text.repeat(700_000) }] },
			};
			appendFileSync(file, `${JSON.stringify(line)}\n`);
		}
		// The agent's session, open in this process and streaming an answer.
		live(file, "general-purpose#11111111", [{ role: "user", content: "long" }], t);
		streams.subscribe("run-1");
		await until(() => sent.some(isStart));
		const startAt = sent.findIndex(isStart);
		const saved = sent
			.slice(0, startAt)
			.filter((message) => message.type === "subagent_entries")
			.flatMap((message) => message.messages as Message[])
			.filter((message) => message.role === "assistant");
		expect(saved).toHaveLength(3);
		expect(sent.slice(startAt).some((message) => message.type === "subagent_entries")).toBe(false);
	});

	it("follows a live agent whose messages no longer hold its first prompt (compacted)", async () => {
		const { streams, call, agent, sent, live, isStart } = await files();
		const t = Date.now() - 60_000;
		const compacted = [{ role: "compactionSummary", summary: "earlier work", tokensBefore: 1 }];
		// Found by the prompt saved in its file, open before the phone asks.
		call("run-1", { prompt: "first task" }, t);
		const first = agent("11111111aaaa", "first task", "one", t + 1_000);
		live(first, "general-purpose#11111111", compacted, t + 1_000);
		streams.subscribe("run-1");
		await until(() => sent.some((message) => message.runId === "run-1" && isStart(message)));
		// Its file found first, the session opened later.
		call("run-2", { prompt: "second task" }, t + 10_000);
		const second = agent("22222222bbbb", "second task", "two", t + 11_000);
		streams.subscribe("run-2");
		await until(() => sent.some((message) => message.runId === "run-2" && message.type === "subagent_entries"));
		live(second, "general-purpose#22222222", compacted, t + 11_000);
		await until(() => sent.some((message) => message.runId === "run-2" && isStart(message)));
	});

	it("finds a run that compaction took out of the parent's context in its saved branch", async () => {
		const { parent, streams, agent, replies } = await files();
		const t = Date.now() - 60_000;
		// Saved, but no longer among the session's messages (the model's context after a compaction).
		parent.session.sessionManager.appendMessage({
			role: "assistant",
			content: [{ type: "toolCall", id: "run-old", name: "Agent", arguments: { prompt: "old task" } }],
			timestamp: t,
		} as never);
		parent.session.sessionManager.appendMessage({
			role: "toolResult",
			toolCallId: "run-old",
			toolName: "Agent",
			content: [],
			details: { agentId: "33333333cccc" },
			isError: false,
			timestamp: t,
		} as never);
		expect(parent.session.messages.some((message) => JSON.stringify(message).includes("run-old"))).toBe(false);
		agent("33333333cccc", "old task", "old reply", t + 1_000);
		streams.subscribe("run-old");
		await until(() => replies("run-old").length > 0);
		expect(replies("run-old")).toEqual([expect.stringContaining("old reply")]);
	});

	it("keeps live events that follow a message after its saved copy", async () => {
		const { streams, call, result, agent, sent, live, isStart } = await files();
		const t = Date.now() - 60_000;
		call("run-1", { prompt: "task" }, t);
		result("run-1", "11111111aaaa");
		const file = agent("11111111aaaa", "task", "earlier", t + 1_000);
		const emit = live(file, "general-purpose#11111111", [{ role: "user", content: "task" }], t);
		streams.subscribe("run-1");
		await until(() => sent.some(isStart));
		const from = sent.length;
		const answer = {
			role: "assistant",
			content: [{ type: "toolCall", id: "tool-1", name: "read", arguments: {} }],
			stopReason: "toolUse",
		};
		emit({ type: "message_end", message: answer });
		// Before the poll the message's end asked for: the tool starts and the next message begins.
		emit({ type: "tool_execution_start", toolCallId: "tool-1", toolName: "read", args: {} });
		emit({ type: "message_start", message: { role: "assistant", content: [] } });
		appendFileSync(file, `${JSON.stringify({ type: "message", message: answer })}\n`);
		await until(() =>
			sent
				.slice(from)
				.some((message) =>
					(message.events as Message[] | undefined)?.some((e) => e.type === "tool_execution_start"),
				),
		);
		const order = sent
			.slice(from)
			.flatMap((message) =>
				message.type === "subagent_entries"
					? (message.messages as Message[]).map((entry) => `saved:${entry.role}`)
					: (message.events as Message[]).map((event) => String(event.type)),
			);
		expect(order).toEqual(["message_end", "saved:assistant", "tool_execution_start", "message_start"]);
	});

	it("splits a burst of live events into messages of bounded size", async () => {
		const { streams, call, result, agent, sent, live, isStart } = await files();
		const t = Date.now() - 60_000;
		call("run-1", { prompt: "task" }, t);
		result("run-1", "11111111aaaa");
		const file = agent("11111111aaaa", "task", "earlier", t + 1_000);
		const emit = live(file, "general-purpose#11111111", [{ role: "user", content: "task" }], t);
		streams.subscribe("run-1");
		await until(() => sent.some(isStart));
		const from = sent.length;
		const message = { role: "assistant", content: [] };
		for (let i = 0; i < 140; i++) {
			const delta = String(i % 10).repeat(260_000);
			emit({
				type: "message_update",
				message,
				assistantMessageEvent: { type: "text_delta", contentIndex: 0, delta },
			});
		}
		await until(() => sent.slice(from).flatMap((m) => (m.events as Message[] | undefined) ?? []).length === 140);
		const batches = sent.slice(from).filter((m) => m.type === "subagent_events");
		expect(batches.length).toBeGreaterThan(1);
		for (const batch of batches) expect(Buffer.byteLength(JSON.stringify(batch))).toBeLessThan(1024 * 1024);
		const order = batches.flatMap((batch) =>
			(batch.events as Message[]).map((event) => String((event.assistantMessageEvent as Message).delta)[0]),
		);
		expect(order).toEqual(Array.from({ length: 140 }, (_, i) => String(i % 10)));
	});

	it("tells a phone that joins while a tool runs that it is running", async () => {
		const { streams, call, result, agent, sent, live } = await files();
		const t = Date.now() - 60_000;
		call("run-1", { prompt: "task" }, t);
		result("run-1", "11111111aaaa");
		const file = agent("11111111aaaa", "task", "earlier", t + 1_000);
		const answer = {
			role: "assistant",
			content: [{ type: "toolCall", id: "tool-1", name: "bash", arguments: { command: "sleep 60" } }],
		};
		appendFileSync(file, `${JSON.stringify({ type: "message", message: answer })}\n`);
		live(file, "general-purpose#11111111", [{ role: "user", content: "task" }, answer], t, {
			streaming: false,
			pendingToolCalls: ["tool-1"],
		});
		streams.subscribe("run-1");
		const started = () =>
			sent
				.flatMap((message) => (message.type === "subagent_events" ? (message.events as Message[]) : []))
				.find((event) => event.type === "tool_execution_start");
		await until(() => started() !== undefined);
		expect(started()).toEqual({
			type: "tool_execution_start",
			toolCallId: "tool-1",
			toolName: "bash",
			args: { command: "sleep 60" },
		});
		expect(
			sent.some((message) => (message.events as Message[] | undefined)?.some((e) => e.type === "message_start")),
		).toBe(false);
	});

	it("lets a resumed run share its agent's file with the original run", async () => {
		const { streams, call, result, agent, replies } = await files();
		const t = Date.now() - 60_000;
		call("run-1", { prompt: "first" }, t);
		result("run-1", "11111111aaaa");
		call("run-2", { prompt: "more", resume: "11111111aaaa" }, t + 10_000);
		agent("11111111aaaa", "first", "shared reply", t + 1_000);
		streams.subscribe("run-1");
		streams.subscribe("run-2");
		await until(() => replies("run-1").length > 0 && replies("run-2").length > 0);
		expect(replies("run-2")).toEqual(replies("run-1"));
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

	it("bounds nested tool arguments, many blocks and wide characters by bytes", () => {
		const max = 256 * 1024;
		const nested = {
			role: "assistant",
			content: [
				{ type: "toolCall", id: "t1", name: "write", arguments: { files: [{ text: "x".repeat(400_000) }] } },
			],
		};
		const cappedNested = capMessage(nested, max);
		expect(Buffer.byteLength(JSON.stringify(cappedNested))).toBeLessThanOrEqual(max);
		expect((cappedNested.content as Message[])[0]).toMatchObject({ type: "toolCall", id: "t1", name: "write" });
		const manyKeys = {
			role: "assistant",
			content: [
				{
					type: "toolCall",
					id: "t2",
					name: "edit",
					arguments: Object.fromEntries(
						Array.from({ length: 20_000 }, (_, i) => [`k${i}`, { v: "y".repeat(20) }]),
					),
				},
			],
		};
		expect(Buffer.byteLength(JSON.stringify(capMessage(manyKeys, max)))).toBeLessThanOrEqual(max);
		const manyBlocks = {
			role: "assistant",
			content: Array.from({ length: 1000 }, () => ({ type: "text", text: "z".repeat(2000) })),
		};
		expect(Buffer.byteLength(JSON.stringify(capMessage(manyBlocks, max)))).toBeLessThanOrEqual(max);
		const wide = { role: "user", content: "\u0001".repeat(300_000) };
		expect(Buffer.byteLength(JSON.stringify(capMessage(wide, max)))).toBeLessThanOrEqual(max);
	});

	it("bounds a streamed tool call update with many large arguments by bytes", () => {
		const max = 256 * 1024;
		const toolCall = {
			type: "toolCall",
			id: "t1",
			name: "write",
			arguments: Object.fromEntries(Array.from({ length: 300 }, (_, i) => [`a${i}`, "x".repeat(140_000)])),
		};
		const update = {
			type: "message_update",
			usage: { input: 1, output: 2 },
			assistantMessageEvent: { type: "toolcall_end", contentIndex: 0, toolCall },
		};
		const capped = capUpdate(update, max);
		expect(Buffer.byteLength(JSON.stringify(capped))).toBeLessThanOrEqual(max);
		expect((capped.assistantMessageEvent as Message).toolCall).toMatchObject({
			type: "toolCall",
			id: "t1",
			name: "write",
		});
		const wideDelta = {
			type: "message_update",
			assistantMessageEvent: { type: "text_delta", contentIndex: 0, delta: "\u0001".repeat(300_000) },
		};
		expect(Buffer.byteLength(JSON.stringify(capUpdate(wideDelta, max)))).toBeLessThanOrEqual(max);
		const small = { type: "message_update", assistantMessageEvent: { type: "text_delta", delta: "hi" } };
		expect(capUpdate(small, max)).toBe(small);
	});
});
