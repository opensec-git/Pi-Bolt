import {
	appendFileSync,
	mkdirSync,
	mkdtempSync,
	readFileSync,
	renameSync,
	rmSync,
	utimesSync,
	writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { AgentTool } from "@earendil-works/pi-agent-core";
import { fauxAssistantMessage, fauxText, fauxThinking, fauxToolCall } from "@earendil-works/pi-ai";
import { Type } from "typebox";
import { afterEach, describe, expect, it, vi } from "vitest";
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
	async function files(pollMs = 20) {
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
			pollMs,
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

	it("reads the saved branch once across subscriptions and still sees live results while waiting", async () => {
		const { parent, streams, call, result, agent, replies } = await files();
		call("first", { prompt: "one" });
		call("second", { prompt: "two" });
		vi.useFakeTimers();
		const branch = vi.spyOn(parent.session.sessionManager, "getBranch");
		const listing = vi.spyOn(streams, "candidateFiles");
		try {
			streams.subscribe("first");
			streams.subscribe("second");
			expect(branch).toHaveBeenCalledTimes(1);
			branch.mockClear();
			listing.mockClear();
			result("first", "aaaaaaaa");
			result("second", "bbbbbbbb");
			agent("aaaaaaaa", "different saved prompt", "first answer", 100);
			agent("bbbbbbbb", "different saved prompt", "second answer", 200);
			vi.advanceTimersByTime(1000);
			expect(branch).not.toHaveBeenCalled();
			expect(listing).toHaveBeenCalledTimes(2);
			expect(listing.mock.results[0].value).toBe(listing.mock.results[1].value);
			expect(replies("first")).toEqual([expect.stringContaining("first answer")]);
			expect(replies("second")).toEqual([expect.stringContaining("second answer")]);
			streams.unsubscribe("first");
			streams.unsubscribe("second");
			expect(vi.getTimerCount()).toBe(0);
		} finally {
			branch.mockRestore();
			listing.mockRestore();
			streams.clear();
			vi.useRealTimers();
		}
	});

	it("invalidates saved runs on a branch switch and keeps temporary live results out of the saved index", async () => {
		const { parent, streams, result } = await files();
		const manager = parent.session.sessionManager;
		const file = parent.session.sessionFile;
		const savedId = manager.appendMessage({
			role: "assistant",
			content: [{ type: "toolCall", id: "saved", name: "Agent", arguments: { prompt: "original" } }],
		} as never);
		expect(streams.runs(file)?.get("saved")?.prompt).toBe("original");
		result("saved", "temporary-agent");
		expect(streams.runs(file)?.get("saved")?.agentId).toBe("temporary-agent");
		parent.session.messages.pop();
		expect(streams.runs(file)?.get("saved")?.agentId).toBeUndefined();
		manager.resetLeaf();
		expect(streams.runs(file)?.has("saved")).toBe(false);
		manager.branch(savedId);
		expect(streams.runs(file)?.get("saved")?.prompt).toBe("original");
	});

	it("delivers appends and same-size atomic replacements before the fallback poll", async () => {
		const { streams, call, agent, replies, resets } = await files(60_000);
		call("run", { prompt: "hello" });
		const file = agent("child", "hello", "earlier", 100);
		streams.subscribe("run");
		const line = JSON.stringify({ type: "message", message: { role: "assistant", content: "one" } });
		appendFileSync(file, `${line}\n`);
		await until(() => replies("run").at(-1) === '"one"');
		writeFileSync(`${file}.tmp`, readFileSync(file, "utf8").replace('"one"', '"two"'));
		renameSync(`${file}.tmp`, file);
		await until(() => replies("run").at(-1) === '"two"');
		expect(resets("run")).toHaveLength(2);
		appendFileSync(file, `${line}\n`);
		await until(() => replies("run").at(-1) === '"one"');
		expect(replies("run")).toHaveLength(5);
	});

	it("rediscovers a replaced child file after the last subscription ends", async () => {
		const { streams, call, agent, replies } = await files();
		call("first", { prompt: "one" });
		agent("childxyz", "one", "old answer", 100);
		streams.subscribe("first");
		await until(() => replies("first").length > 0);
		expect(replies("first")).toEqual([expect.stringContaining("old answer")]);
		expect(streams.unsubscribe("first")).toBe(true);

		call("second", { prompt: "two" });
		agent("childxyz", "two", "new answer", 200);
		streams.subscribe("second");
		await until(() => replies("second").length > 0);
		expect(replies("second")).toEqual([expect.stringContaining("new answer")]);
	});

	it("rebinds both shared-file subscriptions on replacement and keeps the remaining listener after unsubscribe", async () => {
		const { streams, call, agent, replies } = await files(60_000);
		const resume = "abcdefgh1234";
		const file = agent(resume, "hello", "initial", 100);
		call("a", { prompt: "hello", resume });
		call("b", { prompt: "again", resume });
		streams.subscribe("a");
		streams.subscribe("b");
		writeFileSync(`${file}.tmp`, readFileSync(file, "utf8").replace('"initial"', '"updated"'));
		renameSync(`${file}.tmp`, file);
		await until(() => ["a", "b"].every((id) => replies(id).at(-1)?.includes("updated")));
		streams.unsubscribe("a");
		appendFileSync(file, '{"type":"message","message":{"role":"assistant","content":"new"}}\n');
		await until(() => replies("b").at(-1) === '"new"');
		expect(replies("a")).toHaveLength(2);
	});

	it("follows saved appends through the periodic poll when watching is unavailable", async () => {
		const { streams, call, agent, replies } = await files();
		vi.spyOn(streams, "watchFile").mockReturnValue(undefined);
		call("run", { prompt: "hello" });
		const file = agent("child", "hello", "initial", 100);
		streams.subscribe("run");
		appendFileSync(file, '{"type":"message","message":{"role":"assistant","content":"fallback"}}\n');
		await until(() => replies("run").at(-1) === '"fallback"');
	});

	it("reads complete UTF-8 header lines across pages and at the byte limit", () => {
		const dir = mkdtempSync(join(tmpdir(), "pi-subagent-header-"));
		cleanups.push(() => rmSync(dir, { recursive: true, force: true }));
		const streams = new SubagentStreams({ getSession: () => undefined, send: () => {} });
		const prefix =
			'{"type":"session","parentSession":"parent"}\n{"type":"message","message":{"role":"user","content":"';
		for (const page of [4096, 16384, 65536]) {
			const file = join(dir, String(page));
			const prompt = `${"x".repeat(page - 1 - prefix.length)}📎task`;
			writeFileSync(file, `${prefix}${prompt}"}}`);
			expect(streams.header(file)?.firstUser).toBe(prompt);
		}
		const file = join(dir, "limit");
		const suffix = '"}}';
		const prompt = "x".repeat(256 * 1024 - prefix.length - suffix.length);
		writeFileSync(file, `${prefix}${prompt}x${suffix}\n`);
		expect(streams.header(file)).toMatchObject({ parent: "parent" });
		expect(streams.header(file)?.firstUser).toBeUndefined();
		writeFileSync(file, `${prefix}${prompt}${suffix}`);
		expect(streams.header(file)?.firstUser).toBe(prompt);
	});

	it("counts malformed and empty header lines toward the 40-line limit", () => {
		const dir = mkdtempSync(join(tmpdir(), "pi-subagent-header-lines-"));
		cleanups.push(() => rmSync(dir, { recursive: true, force: true }));
		const streams = new SubagentStreams({ getSession: () => undefined, send: () => {} });
		const file = join(dir, "child.jsonl");
		const header = '{"type":"session","parentSession":"parent"}\n';
		const user = '{"type":"message","message":{"role":"user","content":"task"}}';
		const filler = "invalid\n\n".repeat(19);
		writeFileSync(file, `${header}${filler}\n${user}\n`);
		expect(streams.header(file)?.firstUser).toBeUndefined();
		writeFileSync(file, `${header}${filler}${user}\n`);
		expect(streams.header(file)?.firstUser).toBe("task");
	});

	it("joins split transcript records once, retains trailing bytes and drops partial records on truncation", async () => {
		const { streams, call, agent, replies, resets } = await files();
		call("run", { prompt: "hello" });
		const file = agent("child", "hello", "initial", 100);
		vi.useFakeTimers();
		try {
			streams.subscribe("run");
			const line = (text: string) =>
				JSON.stringify({ type: "message", message: { role: "assistant", content: text } });
			const split = Buffer.from(line("left📎right"));
			const firstByte = split.indexOf(Buffer.from("📎"));
			for (const piece of [split.subarray(0, firstByte + 1), split.subarray(firstByte + 1, firstByte + 3)]) {
				appendFileSync(file, piece);
				vi.advanceTimersByTime(20);
				expect(replies("run")).toHaveLength(1);
			}
			appendFileSync(file, split.subarray(firstByte + 3));
			appendFileSync(file, `\nmalformed\n${line("second")}\n{"type":`);
			vi.advanceTimersByTime(20);
			expect(replies("run").slice(1)).toEqual(['"left📎right"', '"second"']);
			appendFileSync(file, '"message","message":{"role":"assistant","content":"third"}}\n{"incomplete":');
			vi.advanceTimersByTime(20);
			expect(replies("run").at(-1)).toBe('"third"');
			writeFileSync(file, `${line("replacement")}\n`);
			vi.advanceTimersByTime(20);
			expect(replies("run").at(-1)).toBe('"replacement"');
			expect(resets("run")).toHaveLength(2);
		} finally {
			streams.clear();
			vi.useRealTimers();
		}
	});

	it("yields between blocks of a large saved line and delivers it before the next scheduled poll", async () => {
		const { streams, call, agent, replies } = await files();
		call("run", { prompt: "hello" });
		const file = agent("child", "hello", "initial", 100);
		vi.useFakeTimers();
		try {
			streams.subscribe("run");
			const entry = { type: "message", message: { role: "assistant", content: "a".repeat(5 * 1024 * 1024) } };
			appendFileSync(file, `${JSON.stringify(entry)}\n`);
			vi.advanceTimersByTime(20);
			let countBetweenBlocks = 0;
			setImmediate(() => {
				countBetweenBlocks = replies("run").length;
			});
			vi.advanceTimersByTime(10);
			expect(countBetweenBlocks).toBe(1);
			expect(replies("run")).toHaveLength(2);
			expect(replies("run").at(-1)).toContain("cut for the phone");
			appendFileSync(file, `${JSON.stringify(entry)}\n`);
			vi.advanceTimersByTime(10);
			streams.unsubscribe("run");
			expect(vi.getTimerCount()).toBe(0);
		} finally {
			streams.clear();
			vi.useRealTimers();
		}
	});

	it("matches whole-record UTF-8 decoding across incomplete and malformed sequences", async () => {
		const { streams, call, agent, replies } = await files();
		call("run", { prompt: "hello" });
		const file = agent("child", "hello", "initial", 100);
		vi.useFakeTimers();
		try {
			streams.subscribe("run");
			const prefix = '{"type":"message","message":{"role":"assistant","content":"';
			const chunks = [Buffer.from([0xef]), Buffer.from([0xbb, 0xbf, 0xf0, 0x9f]), Buffer.from([0x41, 0xe2])];
			appendFileSync(file, prefix);
			for (const chunk of chunks) {
				appendFileSync(file, chunk);
				vi.advanceTimersByTime(20);
			}
			appendFileSync(file, '"}}\n');
			vi.advanceTimersByTime(20);
			expect(replies("run").at(-1)).toBe(JSON.stringify(Buffer.concat(chunks).toString("utf8")));
		} finally {
			streams.clear();
			vi.useRealTimers();
		}
	});

	it("preserves saved preview bytes and batch boundaries for text, thinking, images and escaped content", async () => {
		const { streams, call, agent, sent } = await files();
		call("run", { prompt: "hello" });
		const file = agent("child", "hello", "initial", 100);
		const max = 256 * 1024;
		const text = "a".repeat(max + 1);
		const inputs = [
			{ role: "assistant", content: text },
			{
				role: "assistant",
				content: [
					{ type: "text", text },
					{ type: "text", text: "tail" },
				],
			},
			{ role: "assistant", content: [{ type: "thinking", thinking: text }] },
			{ role: "toolResult", content: [{ type: "image", data: text, mimeType: "image/png" }] },
			{ role: "compactionSummary", summary: text, tokensBefore: 123 },
			{ role: "assistant", content: "界".repeat(max / 4) },
			{ role: "assistant", content: "\u0001".repeat(max / 4) },
			{ role: "assistant", content: "short", details: { output: text } },
		];
		const expected = inputs.map((m) => capMessage(m, max));
		vi.useFakeTimers();
		try {
			streams.subscribe("run");
			for (let i = 0; i < inputs.length; i++) {
				const from = sent.length;
				const line = JSON.stringify({ type: "message", message: inputs[i] }) + "\n";
				appendFileSync(file, line);
				vi.advanceTimersByTime(60);
				const batches = sent.slice(from).filter((m) => m.type === "subagent_entries");
				expect(batches.map((m) => m.messages)).toEqual([[expected[i]]]);
			}
			// Count the serialized UTF-8 bytes. The third record fills the first batch.
			const record = { role: "assistant", content: "界".repeat(64000) };
			const from = sent.length;
			const line = JSON.stringify({ type: "message", message: record }) + "\n";
			appendFileSync(file, line.repeat(4));
			vi.advanceTimersByTime(20);
			const batches = sent.slice(from).filter((m) => m.type === "subagent_entries");
			expect(batches.flatMap((m) => m.messages)).toEqual([record, record, record, record]);
			expect(batches.map((m) => (m.messages as Message[]).length)).toEqual([3, 1]);
		} finally {
			streams.clear();
			vi.useRealTimers();
		}
	});

	it("projects oversized saved fields with fixed limits and validates the rest of each record", async () => {
		const { streams, call, agent, sent } = await files();
		streams.withFixedLimits();
		call("run", { prompt: "hello" });
		const file = agent("child", "hello", "initial", 100);
		const max = streams.limits.maxMessageBytes;
		const block = 1024 * 1024;
		const prefix = '{"type":"message","message":{"role":"assistant","content":"';
		// Place the backslash of a Unicode escape at the last byte of a disk block.
		const long = "a".repeat(3 * block - Buffer.byteLength(prefix) - 1);
		const record = (message: Message) => JSON.stringify({ type: "message", message });
		const subject = 'é漢\\"\n'.repeat(550_000);
		const sample = {
			role: "assistant",
			content: [
				{ type: "thinking", thinking: subject },
				{ type: "text", text: "ok" },
			],
		};
		const details = { role: "toolResult", details: "a".repeat(4 * block), content: "kept" };
		// A similarly named field in another object can affect the fallback for oversized messages.
		const nested = { role: "user", content: "small", holder: { content: "a".repeat(4 * block) } };
		const edit = {
			role: "assistant",
			content: [{ type: "toolCall", arguments: { edits: [{ oldText: "before", newText: subject }] } }],
		};
		// A named key can survive in the oversized-message fallback. Its entire name matters.
		const longKey = { role: "assistant", content: "ok", ["key".repeat(block)]: 7 };
		const following = { role: "assistant", content: "next" };
		vi.useFakeTimers();
		try {
			streams.subscribe("run");
			const from = sent.length;
			for (const line of [
				record(sample),
				record(details),
				record(nested),
				record(edit),
				record(longKey),
				prefix + long + String.fromCharCode(92) + 'u754cend"}}',
				prefix + long + String.fromCharCode(92) + 'ud83d", "content":"replacement"}}',
				prefix + long + String.fromCharCode(92) + 'u0z00"}}',
				prefix + long + String.fromCharCode(92) + 'q"}}',
				prefix + long + String.fromCharCode(92) + "u1234" + String.fromCharCode(1) + '"}}',
				prefix + long + '","meta": } }',
				record(following),
			]) {
				appendFileSync(file, `${line}\n`);
				vi.advanceTimersByTime(120);
			}
			const received = sent
				.slice(from)
				.filter((m) => m.type === "subagent_entries")
				.flatMap((m) => m.messages);
			expect(received).toEqual([
				capMessage(sample, max),
				capMessage(details, max),
				capMessage(nested, max),
				capMessage(edit, max),
				capMessage(longKey, max),
				capMessage({ role: "assistant", content: `${long}界end` }, max),
				{ role: "assistant", content: "replacement" },
				following,
			]);
		} finally {
			streams.clear();
			vi.useRealTimers();
		}
	});

	it("honors a mutable preview cap raised before a partial line finishes", async () => {
		const { streams, call, agent, sent } = await files();
		call("run", { prompt: "hello" });
		const file = agent("child", "hello", "initial", 100);
		const text = "z".repeat(4 * 1024 * 1024);
		vi.useFakeTimers();
		try {
			streams.subscribe("run");
			appendFileSync(file, '{"type":"message","message":{"role":"assistant","content":"' + text);
			vi.advanceTimersByTime(120);
			streams.limits.maxMessageBytes = 5 * 1024 * 1024;
			const from = sent.length;
			appendFileSync(file, '"}}\n');
			vi.advanceTimersByTime(40);
			expect(sent.slice(from).flatMap((m) => m.messages)).toEqual([{ role: "assistant", content: text }]);
		} finally {
			streams.clear();
			vi.useRealTimers();
		}
	});

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

	it("sleeps until a missing saved message is due and resumes normal batching as soon as it arrives", async () => {
		const { streams, call, result, agent, sent, live } = await files(100);
		call("run", { prompt: "task" });
		result("run", "11111111aaaa");
		const file = agent("11111111aaaa", "task", "earlier", 100);
		const emit = live(file, "general-purpose#11111111", [{ role: "user", content: "task" }], 100);
		vi.useFakeTimers();
		const timers = vi.spyOn(globalThis, "setTimeout");
		try {
			streams.subscribe("run");
			vi.advanceTimersByTime(100);
			const message = { role: "assistant", content: [] };
			const end = () => emit({ type: "message_end", message });
			const tool = (id: string) =>
				emit({ type: "tool_execution_end", toolCallId: id, toolName: "read", isError: false });
			const delivered = (id: string) =>
				sent.some((m) => (m.events as Message[] | undefined)?.some((e) => e.toolCallId === id));
			timers.mockClear();
			end();
			tool("waiting");
			vi.advanceTimersByTime(1400);
			expect(delivered("waiting")).toBe(false);
			expect(timers.mock.calls.length).toBeLessThanOrEqual(2);
			appendFileSync(file, JSON.stringify({ type: "message", message }) + "\n");
			vi.advanceTimersByTime(100);
			expect(delivered("waiting")).toBe(true);
			tool("next");
			vi.advanceTimersByTime(80);
			expect(delivered("next")).toBe(true);

			end();
			tool("expired");
			vi.advanceTimersByTime(4999);
			expect(delivered("expired")).toBe(false);
			vi.advanceTimersByTime(1);
			expect(delivered("expired")).toBe(true);
		} finally {
			streams.clear();
			timers.mockRestore();
			vi.useRealTimers();
		}
	});

	it("sends exact JSON snapshots and splits batches by encoded UTF-8 bytes", async () => {
		const { parent, call, result, agent, live } = await files();
		const runId = 'quote"\n\ud800';
		call(runId, { prompt: "task" });
		result(runId, "11111111aaaa");
		const file = agent("11111111aaaa", "task", "earlier", 100);
		const emit = live(file, "general-purpose#11111111", [{ role: "user", content: "task" }], 100, {
			streaming: false,
		});
		const wire: string[] = [];
		const streams = new SubagentStreams({
			getSession: () => parent.session,
			send: () => {},
			sendJson: (json) => wire.push(json),
		});
		vi.useFakeTimers();
		try {
			streams.subscribe(runId);
			const usage = { output: 0 };
			const expected: Message[] = [];
			for (let i = 0; i < 7; i++) {
				usage.output = i;
				const assistantMessageEvent = { type: "thinking_delta", contentIndex: 0, delta: "界".repeat(70_000) };
				emit({ type: "message_update", message: { role: "assistant", content: [], usage }, assistantMessageEvent });
				expected.push({ type: "message_update", usage: { ...usage }, assistantMessageEvent });
			}
			usage.output = 999;
			vi.advanceTimersByTime(80);
			expect(wire).toEqual(
				[0, 2, 4, 6].map((start) =>
					JSON.stringify({ type: "subagent_events", runId, events: expected.slice(start, start + 2) }),
				),
			);
		} finally {
			streams.clear();
			vi.useRealTimers();
		}
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
