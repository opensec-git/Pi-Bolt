import { rmSync } from "node:fs";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import type { AgentMessage } from "@earendil-works/pi-agent-core";
import { describe, expect, it } from "vitest";
import { OutputAccumulator } from "../src/core/tools/output-accumulator.ts";
import { truncationDetails } from "../src/core/tools/truncate.ts";
import { literalConfigValue, mcpServerName, toMcpServerConfig } from "../src/extensions/acp/extension.ts";
import {
	bashExitCode,
	bashOutputTail,
	fenced,
	promptToPi,
	replayMessages,
	stopReasonOf,
	TERMINAL_START,
	type TerminalSent,
	terminalOutputDelta,
	toolCallInfo,
	toolResultContent,
} from "../src/extensions/acp/translate.ts";
import { JsonRpcConnection } from "../src/extensions/json-rpc.ts";

const cwd = join("/", "work", "project");
const file = join(cwd, "src", "main.ts");

describe("promptToPi", () => {
	it("keeps text and links in place and puts embedded files first", () => {
		const prompt = promptToPi([
			{ type: "text", text: "Explain" },
			{ type: "resource_link", uri: pathToFileURL(file).href, name: "main.ts" },
			{ type: "text", text: " please" },
			{ type: "resource", resource: { uri: pathToFileURL(file).href, text: "export {}" } },
			{ type: "image", data: "aGk=", mimeType: "image/png" },
		]);
		expect(prompt.text).toBe(`<file name="${file}">\nexport {}\n</file>\nExplain ${file} please`);
		expect(prompt.images).toEqual([{ type: "image", data: "aGk=", mimeType: "image/png" }]);
	});

	it("passes slash commands through unchanged", () => {
		expect(promptToPi([{ type: "text", text: "/skill:review now" }]).text).toBe("/skill:review now");
	});
});

describe("toolCallInfo", () => {
	it("describes Pi's tools", () => {
		expect(toolCallInfo("read", { path: "src/main.ts", offset: 10 }, cwd)).toEqual({
			title: "Read src/main.ts",
			kind: "read",
			locations: [{ path: file, line: 9 }],
		});
		expect(toolCallInfo("edit", { path: file, edits: [] }, cwd).kind).toBe("edit");
		expect(toolCallInfo("bash", { command: "npm   test\n--watch" }, cwd)).toEqual({
			title: "npm test --watch",
			kind: "execute",
			locations: [],
		});
		expect(toolCallInfo("grep", { pattern: "TODO" }, cwd).kind).toBe("search");
		expect(toolCallInfo("my_tool", {}, cwd)).toEqual({ title: "my_tool", kind: "other", locations: [] });
	});
});

describe("tool results", () => {
	it("keeps text and images", () => {
		expect(
			toolResultContent({
				content: [
					{ type: "text", text: "ok" },
					{ type: "image", data: "aGk=", mimeType: "image/png" },
					{ type: "text", text: "" },
				],
			}),
		).toEqual([
			{ type: "content", content: { type: "text", text: "ok" } },
			{ type: "content", content: { type: "image", data: "aGk=", mimeType: "image/png" } },
		]);
	});

	it("fences output with a fence longer than any it contains", () => {
		expect(fenced("a ``` b")).toBe("````\na ``` b\n````");
	});
});

describe("history", () => {
	const messages = [
		{ role: "user", content: "hi", timestamp: 0 },
		{
			role: "assistant",
			content: [
				{ type: "thinking", thinking: "hmm" },
				{ type: "text", text: "Reading." },
				{ type: "toolCall", id: "t1", name: "read", arguments: { path: "src/main.ts" } },
			],
			stopReason: "toolUse",
		},
		{
			role: "toolResult",
			toolCallId: "t1",
			toolName: "read",
			content: [{ type: "text", text: "code" }],
			isError: false,
		},
		{ role: "assistant", content: [{ type: "text", text: "Done." }], stopReason: "length" },
	] as unknown as AgentMessage[];

	it("replays messages and tool calls with their results", () => {
		const updates = replayMessages(messages, cwd).map((update) => update.sessionUpdate);
		expect(updates).toEqual([
			"user_message_chunk",
			"agent_thought_chunk",
			"agent_message_chunk",
			"tool_call",
			"agent_message_chunk",
		]);
		const call = replayMessages(messages, cwd)[3];
		expect(call).toMatchObject({ status: "completed", locations: [{ path: file }] });
	});

	it("replays a compaction summary only for clients that show compactions", () => {
		const compacted = [
			{ role: "compactionSummary", summary: "Earlier: set things up.", tokensBefore: 5000, timestamp: 0 },
			...messages,
		] as AgentMessage[];
		const kinds = (updates: { sessionUpdate: string }[]) => updates.map((update) => update.sessionUpdate);
		expect(kinds(replayMessages(compacted, cwd))).toEqual(kinds(replayMessages(messages, cwd)));
		expect(replayMessages(compacted, cwd, { compaction: true })[0]).toEqual({
			sessionUpdate: "compaction_update",
			compactionId: "compaction_msg_0",
			status: "completed",
			summary: [{ type: "text", text: "Earlier: set things up." }],
		});
	});

	it("maps the last assistant stop reason", () => {
		expect(stopReasonOf(messages, false)).toBe("max_tokens");
		expect(stopReasonOf(messages, true)).toBe("cancelled");
		expect(stopReasonOf([], false)).toBe("end_turn");
	});
});

describe("editor MCP servers", () => {
	it("keeps values literal and names valid", () => {
		// biome-ignore lint/suspicious/noTemplateCurlyInString: literal config value reference
		expect(literalConfigValue("!rm -rf $HOME ${X}")).toBe("$!rm -rf $$HOME $${X}");
		expect(mcpServerName("IDE tools (JetBrains)")).toBe("IDE-tools-JetBrains");
		expect(mcpServerName("***")).toBe("server");
		expect(toMcpServerConfig({ type: "sse", name: "x", url: "http://localhost", headers: [] })).toBeUndefined();
		expect(
			toMcpServerConfig({ type: "http", name: "x", url: "http://localhost", headers: [{ name: "A", value: "$b" }] }),
		).toEqual({ type: "http", url: "http://localhost", headers: { A: "$$b" } });
	});
});

describe("JsonRpcConnection", () => {
	const connect = () => {
		const sent: Array<{ id: number | null }> = [];
		const connection = new JsonRpcConnection({
			write: (line) => sent.push(JSON.parse(line)),
			onRequest: async (method) => {
				if (method === "boom") throw new Error("broken");
				return { ok: method };
			},
			onNotification: () => {},
		});
		return { connection, sent };
	};

	it("answers requests and errors", async () => {
		const { connection, sent } = connect();
		connection.receive('{"jsonrpc":"2.0","id":1,"method":"ping"}');
		connection.receive('{"jsonrpc":"2.0","id":2,"method":"boom"}');
		connection.receive("not json");
		await new Promise((r) => setTimeout(r, 0));
		expect(sent).toEqual([
			{ jsonrpc: "2.0", id: null, error: { code: -32700, message: expect.any(String) } },
			{ jsonrpc: "2.0", id: 1, result: { ok: "ping" } },
			{ jsonrpc: "2.0", id: 2, error: { code: -32603, message: "broken" } },
		]);
	});

	it("matches the client's responses to the agent's requests", async () => {
		const { connection, sent } = connect();
		const answer = connection.request("session/request_permission", {});
		connection.receive(
			JSON.stringify({ jsonrpc: "2.0", id: sent[0].id, result: { outcome: { outcome: "cancelled" } } }),
		);
		expect(await answer).toEqual({ outcome: { outcome: "cancelled" } });
		const failed = connection.request("x", {});
		connection.close();
		await expect(failed).rejects.toThrow("closed");
	});
});

describe("terminalOutputDelta", () => {
	/** Command output as the bash tool reports it while the command runs: the end of it, with small limits. */
	const command = () => {
		const output = new OutputAccumulator({ maxLines: 4, maxBytes: 40, tempFilePrefix: "pi-acp-test" });
		let full = "";
		let sent: TerminalSent = TERMINAL_START;
		let shown = "";
		return {
			write(text: string) {
				output.append(Buffer.from(text, "utf8"));
				full += text;
			},
			/** What the terminal is sent for the bash tool's next report. */
			report(): string {
				const snapshot = output.snapshot();
				const tail = bashOutputTail({
					content: [{ type: "text", text: snapshot.content }],
					details: {
						truncation: snapshot.truncation.truncated ? truncationDetails(snapshot.truncation) : undefined,
					},
				});
				const delta = terminalOutputDelta(sent, tail);
				sent = delta.sent;
				shown += delta.data;
				return delta.data;
			},
			get full() {
				return full;
			},
			get shown() {
				return shown;
			},
			close() {
				output.closeTempFile();
				const file = output.snapshot().fullOutputFile;
				if (file?.kind === "saved") rmSync(file.path, { force: true });
			},
		};
	};

	it("sends only new output, through the bash tool's truncated tail", () => {
		const run = command();
		const chunks = [
			"one\n",
			"two\nthr",
			"ee\n",
			"y\ny\n",
			"y\ny\n",
			"y\ny\n",
			"été\n",
			"\x1b[32mok\x1b[0m\n",
			"x".repeat(30),
			"x".repeat(30),
			"\nlast",
			" line",
		];
		for (const chunk of chunks) {
			run.write(chunk);
			run.report();
		}
		expect(run.report()).toBe("");
		expect(run.shown).toBe(run.full);
		run.close();
	});

	it("marks output that went by between two reports, and goes on from there", () => {
		const run = command();
		run.write("start\n");
		expect(run.report()).toBe("start\n");
		run.write(Array.from({ length: 20 }, (_, i) => `line ${i}\n`).join(""));
		// The tail leaves out the trailing newline, so where the terminal stands is known only to a byte.
		expect(run.report()).toBe("[… output skipped …]\nline 16\nline 17\nline 18\nline 19");
		run.write("more");
		expect(run.report()).toBe("\nmore");
		run.write(" and more\n");
		expect(run.report()).toBe(" and more");
		run.close();
	});

	it("starts over when the output changes instead of growing", () => {
		const sent = { text: "progress 50%", end: 12, exact: true };
		const delta = terminalOutputDelta(sent, { text: "done", totalBytes: 4, truncated: false });
		expect(delta.data).toBe("\x1b[H\x1b[2J\x1b[3Jdone");
		expect(delta.sent).toEqual({ text: "done", end: 4, exact: true });
	});

	it("takes the exit code of a command that finished", () => {
		expect(bashExitCode({ content: [], structuredContent: { exit_code: 3 } }, true)).toBe(3);
		expect(bashExitCode({ content: [{ type: "text", text: "Command aborted" }] }, true)).toBeNull();
		expect(bashExitCode({ content: [] }, false)).toBe(0);
	});
});
