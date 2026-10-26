import { type ChildProcessWithoutNullStreams, spawn } from "node:child_process";
import {
	existsSync,
	mkdirSync,
	mkdtempSync,
	readdirSync,
	readFileSync,
	realpathSync,
	rmSync,
	writeFileSync,
} from "node:fs";
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { ENV_AGENT_DIR } from "../src/config.ts";
import type {
	ConfigOption,
	ContentBlock,
	InitializeResponse,
	RequestPermissionRequest,
	SessionInfo,
	SessionModeState,
	SessionNotification,
	SessionUpdate,
	StopReason,
	ToolCallContent,
} from "../src/extensions/acp/types.ts";

const cliPath = resolve(__dirname, "../src/cli.ts");
// --import takes a module specifier, not a filesystem path.
const sourceResolverUrl = pathToFileURL(resolve(__dirname, "../src/experimental/source-resolver.ts")).href;
/** A built executable to test instead of the sources, such as Pi-Bolt's `out/pi-bolt/pi`. */
const executable = process.env.PI_ACP_TEST_EXECUTABLE;

// ============================================================================
// A fake OpenAI-compatible model: the last user message picks a script, one step per model turn.
// ============================================================================

type Step = { text?: string; tool?: { name: string; args: Record<string, unknown> }; hang?: boolean };

const SCRIPTS: Record<string, Step[]> = {
	tools: [
		{ text: "Writing. ", tool: { name: "write", args: { path: "notes.txt", content: "alpha\nbeta\n" } } },
		{ tool: { name: "edit", args: { path: "notes.txt", edits: [{ oldText: "beta", newText: "gamma" }] } } },
		{ tool: { name: "bash", args: { command: "echo from-bash" } } },
		{ text: "All done." },
	],
	hello: [{ text: "Hello from the fake model." }],
	live: [{ tool: { name: "bash", args: { command: "echo first; sleep 1; echo second" } } }, { text: "Ran it." }],
	terminal: [
		{
			tool: {
				name: "bash",
				args: { command: "echo first; sleep 1; printf '\\033[31msecond\\033[0m\\nno newline'; exit 3" },
			},
		},
		{ text: "Ran it." },
	],
	timeout: [{ tool: { name: "bash", args: { command: "echo started; sleep 5", timeout: 1 } } }, { text: "Ran it." }],
	touch: [{ tool: { name: "touch", args: {} } }, { text: "Touched." }],
	slow: [{ hang: true }],
};

function sse(delta: Record<string, unknown>, finish: string | null = null): string {
	return `data: ${JSON.stringify({
		id: "fake",
		object: "chat.completion.chunk",
		model: "fake-model",
		choices: [{ index: 0, delta, finish_reason: finish }],
	})}\n\n`;
}

/** The tools the model was offered in its latest request. */
let offeredTools: string[] = [];

function startFakeModel(): Promise<Server> {
	const server = createServer((req, res) => {
		let body = "";
		req.on("data", (chunk) => {
			body += chunk;
		});
		req.on("end", () => {
			if (req.method !== "POST") {
				res.writeHead(200, { "content-type": "application/json" });
				res.end(JSON.stringify({ object: "list", data: [{ id: "fake-model", object: "model" }] }));
				return;
			}
			const request = JSON.parse(body || "{}") as {
				messages?: Array<{ role: string; content: unknown }>;
				tools?: Array<{ function: { name: string } }>;
			};
			offeredTools = (request.tools ?? []).map((tool) => tool.function.name);
			let turn = 0;
			let prompt = "";
			for (const message of request.messages ?? []) {
				if (message.role === "user") {
					turn = 0;
					prompt = typeof message.content === "string" ? message.content : JSON.stringify(message.content);
				} else if (message.role === "tool") {
					turn++;
				}
			}
			const script = SCRIPTS[Object.keys(SCRIPTS).find((name) => prompt.includes(`[${name}]`)) ?? "hello"];
			const step = script[Math.min(turn, script.length - 1)];
			res.writeHead(200, { "content-type": "text/event-stream" });
			res.write(sse({ role: "assistant", content: "" }));
			if (step.hang) return; // Never answers: the client cancels.
			if (step.text) res.write(sse({ content: step.text }));
			if (step.tool) {
				res.write(
					sse({
						tool_calls: [
							{
								index: 0,
								id: `call_${turn}`,
								type: "function",
								function: { name: step.tool.name, arguments: JSON.stringify(step.tool.args) },
							},
						],
					}),
				);
				res.write(sse({}, "tool_calls"));
			} else {
				res.write(sse({}, "stop"));
			}
			res.write(
				`data: ${JSON.stringify({ id: "fake", object: "chat.completion.chunk", model: "fake-model", choices: [], usage: { prompt_tokens: 12, completion_tokens: 3, total_tokens: 15 } })}\n\n`,
			);
			res.end("data: [DONE]\n\n");
		});
	});
	return new Promise((resolvePromise) => server.listen(0, "127.0.0.1", () => resolvePromise(server)));
}

// ============================================================================
// A minimal ACP client over the child's stdio
// ============================================================================

type Message = {
	jsonrpc: "2.0";
	id?: number;
	method?: string;
	params?: unknown;
	result?: unknown;
	error?: { code: number; message: string };
};

type UpdateOf<K extends SessionUpdate["sessionUpdate"]> = Extract<SessionUpdate, { sessionUpdate: K }>;
type SessionResponse = { sessionId: string; configOptions: ConfigOption[] };
type PromptResponse = { stopReason: StopReason };

function blockText(block: ContentBlock): string {
	return block.type === "text" ? block.text : "";
}

function toolText(content: ToolCallContent[] | undefined): string {
	const first = content?.[0];
	return first?.type === "content" ? blockText(first.content) : "";
}

function commandNames(client: AcpTestClient): string[] {
	return client.updatesOf("available_commands_update")[0].availableCommands.map((command) => command.name);
}

class AcpTestClient {
	readonly child: ChildProcessWithoutNullStreams;
	readonly updates: SessionNotification[] = [];
	readonly clientRequests: Message[] = [];
	stderr = "";
	private buffer = "";
	private nextId = 1;
	private readonly pending = new Map<number, { resolve: (m: Message) => void }>();
	/** Answers the agent's requests (permission, elicitation). */
	onRequest: (message: Message) => unknown = () => ({ outcome: { outcome: "cancelled" } });

	constructor(cwd: string, agentDir: string, args: string[] = [], env: Record<string, string> = {}) {
		const command = executable
			? { file: executable, args: ["--acp", ...args] }
			: { file: process.execPath, args: ["--import", sourceResolverUrl, cliPath, "--acp", ...args] };
		this.child = spawn(command.file, command.args, {
			cwd,
			env: { ...process.env, [ENV_AGENT_DIR]: agentDir, PI_OFFLINE: "1", ...env },
			stdio: ["pipe", "pipe", "pipe"],
		});
		this.child.stdout.setEncoding("utf8");
		this.child.stdout.on("data", (chunk: string) => {
			this.buffer += chunk;
			for (let index = this.buffer.indexOf("\n"); index !== -1; index = this.buffer.indexOf("\n")) {
				const line = this.buffer.slice(0, index);
				this.buffer = this.buffer.slice(index + 1);
				if (line.trim()) this.handle(JSON.parse(line) as Message);
			}
		});
		this.child.stderr.on("data", (chunk) => {
			this.stderr += chunk.toString();
		});
	}

	private handle(message: Message): void {
		if (message.method && message.id !== undefined) {
			this.clientRequests.push(message);
			const result = this.onRequest(message);
			this.write({ jsonrpc: "2.0", id: message.id, result });
		} else if (message.method === "session/update") {
			this.updates.push(message.params as SessionNotification);
		} else if (message.id !== undefined) {
			this.pending.get(message.id)?.resolve(message);
			this.pending.delete(message.id);
		}
	}

	private write(message: Message): void {
		this.child.stdin.write(`${JSON.stringify(message)}\n`);
	}

	call(method: string, params: unknown): Promise<Message> {
		const id = this.nextId++;
		return new Promise((resolvePromise) => {
			this.pending.set(id, { resolve: resolvePromise });
			this.write({ jsonrpc: "2.0", id, method, params });
		});
	}

	async request<T>(method: string, params: unknown): Promise<T> {
		const response = await this.call(method, params);
		if (response.error) throw new Error(`${method}: ${JSON.stringify(response.error)}\n${this.stderr}`);
		return response.result as T;
	}

	notify(method: string, params: unknown): void {
		this.write({ jsonrpc: "2.0", method, params });
	}

	async waitFor(predicate: () => boolean, timeoutMs = 10_000): Promise<void> {
		const deadline = Date.now() + timeoutMs;
		while (!predicate()) {
			if (Date.now() > deadline) throw new Error(`timed out\n${this.stderr}`);
			await new Promise((r) => setTimeout(r, 20));
		}
	}

	updatesOf<K extends SessionUpdate["sessionUpdate"]>(kind: K): UpdateOf<K>[] {
		return this.updates
			.map((notification) => notification.update)
			.filter((update): update is UpdateOf<K> => update.sessionUpdate === kind);
	}

	async stop(): Promise<number | null> {
		this.child.stdin.end();
		return new Promise((resolvePromise) => {
			if (this.child.exitCode !== null) resolvePromise(this.child.exitCode);
			else this.child.once("exit", (code) => resolvePromise(code));
		});
	}
}

// biome-ignore lint/suspicious/noTemplateCurlyInString: a value from the editor, which must reach the server unexpanded
const EDITOR_TOKEN = "!a$b${HOME}";

const CLIENT_CAPABILITIES = { session: { notices: {} }, elicitation: { form: {} } };

describe("ACP mode", () => {
	let server: Server;
	let root: string;
	let agentDir: string;
	let projectDir: string;
	const clients: AcpTestClient[] = [];

	const startClient = async (
		args: string[] = [],
		clientCapabilities: object = CLIENT_CAPABILITIES,
		env: Record<string, string> = {},
	) => {
		const client = new AcpTestClient(projectDir, agentDir, args, env);
		clients.push(client);
		const init = await client.request<InitializeResponse>("initialize", {
			protocolVersion: 1,
			clientCapabilities,
			clientInfo: { name: "test-client", version: "1.0.0" },
		});
		return { client, init };
	};

	beforeAll(async () => {
		server = await startFakeModel();
		const { port } = server.address() as AddressInfo;
		root = mkdtempSync(join(tmpdir(), "pi-acp-"));
		agentDir = join(root, "agent");
		projectDir = join(root, "project");
		mkdirSync(agentDir, { recursive: true });
		mkdirSync(projectDir, { recursive: true });
		const models = [
			{ id: "fake-model", contextWindow: 200000, maxTokens: 8192 },
			{ id: "other-model", contextWindow: 200000, maxTokens: 8192 },
		];
		writeFileSync(
			join(agentDir, "models.json"),
			JSON.stringify({
				providers: {
					fake: { baseUrl: `http://127.0.0.1:${port}/v1`, api: "openai-completions", apiKey: "fake", models },
				},
			}),
		);
		writeFileSync(
			join(agentDir, "settings.json"),
			JSON.stringify({
				lastChangelogVersion: "9999.0.0",
				defaultProvider: "fake",
				defaultModel: "fake-model",
				// Small sessions can be compacted.
				compaction: { keepRecentTokens: 1 },
			}),
		);
		writeFileSync(join(agentDir, "auth.json"), "{}");
	});

	afterEach(async () => {
		await Promise.all(clients.splice(0).map((client) => client.stop()));
	});

	afterAll(() => {
		server?.close();
		rmSync(root, { recursive: true, force: true });
	});

	it("initializes with Pi's capabilities", async () => {
		const { init } = await startClient();
		expect(init.protocolVersion).toBe(1);
		expect(init.agentCapabilities).toMatchObject({
			loadSession: true,
			promptCapabilities: { image: true, embeddedContext: true },
			mcpCapabilities: { http: true },
			sessionCapabilities: { list: {}, resume: {}, close: {}, delete: {}, fork: {} },
		});
		expect(init.authMethods).toEqual([]);
	});

	it("runs a prompt: text, tool calls with diffs and output, and the stop reason", async () => {
		const { client } = await startClient();
		const { sessionId, configOptions } = await client.request<SessionResponse>("session/new", {
			cwd: projectDir,
			mcpServers: [],
		});
		expect(typeof sessionId).toBe("string");
		expect(configOptions.find((option) => option.id === "model")).toMatchObject({
			category: "model",
			currentValue: "fake/fake-model",
		});
		await client.waitFor(() => client.updatesOf("available_commands_update").length > 0);
		expect(commandNames(client)).toContain("compact");

		const result = await client.request<PromptResponse>("session/prompt", {
			sessionId,
			prompt: [{ type: "text", text: "[tools] please work" }],
		});
		expect(result).toEqual({ stopReason: "end_turn" });

		const text = client
			.updatesOf("agent_message_chunk")
			.map((u) => blockText(u.content))
			.join("");
		expect(text).toContain("Writing.");
		expect(text).toContain("All done.");

		const calls = client.updatesOf("tool_call");
		expect(calls.map((c) => [c.name, c.kind, c.status])).toEqual([
			["write", "edit", "in_progress"],
			["edit", "edit", "in_progress"],
			["bash", "execute", "in_progress"],
		]);
		const notesPath = join(projectDir, "notes.txt");
		expect(calls[0].locations).toEqual([{ path: notesPath }]);
		expect(calls[2].title).toBe("echo from-bash");

		const done = client.updatesOf("tool_call_update").filter((u) => u.status !== "in_progress");
		expect(done.map((u) => u.status)).toEqual(["completed", "completed", "completed"]);
		expect(done[0].content).toEqual([{ type: "diff", path: notesPath, oldText: null, newText: "alpha\nbeta\n" }]);
		expect(done[1].content).toEqual([
			{ type: "diff", path: notesPath, oldText: "alpha\nbeta\n", newText: "alpha\ngamma\n" },
		]);
		expect(toolText(done[2].content)).toContain("from-bash");
		expect(toolText(done[2].content).startsWith("```")).toBe(true);
		expect(readFileSync(notesPath, "utf8")).toBe("alpha\ngamma\n");
		expect(client.updatesOf("usage_update").length).toBeGreaterThan(0);

		const listed = await client.request<{ sessions: SessionInfo[] }>("session/list", { cwd: projectDir });
		expect(listed.sessions.map((s) => s.sessionId)).toContain(sessionId);
	});

	it("shows a command's output while it runs", async () => {
		const { client } = await startClient();
		const { sessionId } = await client.request<SessionResponse>("session/new", { cwd: projectDir, mcpServers: [] });
		await client.request("session/prompt", { sessionId, prompt: [{ type: "text", text: "[live] run it" }] });
		const updates = client.updatesOf("tool_call_update");
		const live = updates.filter(
			(u) => toolText(u.content).includes("first") && !toolText(u.content).includes("second"),
		);
		expect(live.length).toBeGreaterThan(0);
		// A status on every update: some clients take an update without one for a finished call.
		expect(live.every((u) => u.status === "in_progress")).toBe(true);
		expect(updates.at(-1)?.status).toBe("completed");
		expect(toolText(updates.at(-1)?.content)).toContain("second");
	});

	it("shows a command in a terminal for clients that take terminal output", async () => {
		const runCommand = async (capabilities: object, script: string) => {
			const { client } = await startClient([], capabilities);
			const { sessionId } = await client.request<SessionResponse>("session/new", {
				cwd: projectDir,
				mcpServers: [],
			});
			await client.request("session/prompt", { sessionId, prompt: [{ type: "text", text: `[${script}] run it` }] });
			const [call] = client.updatesOf("tool_call");
			return { call, updates: client.updatesOf("tool_call_update").filter((u) => u.toolCallId === call.toolCallId) };
		};
		const zed = { ...CLIENT_CAPABILITIES, terminal: true, _meta: { terminal_output: true } };

		const { call, updates } = await runCommand(zed, "terminal");
		const id = call.toolCallId;
		expect(call.content).toEqual([{ type: "terminal", terminalId: id }]);
		expect(call._meta).toEqual({ terminal_info: { terminal_id: id, cwd: projectDir } });
		// Updates append only the new output, and carry no content (which would replace the terminal).
		expect(updates.every((u) => u.content === undefined)).toBe(true);
		const output = updates.slice(0, -1);
		expect(output.every((u) => u.status === "in_progress")).toBe(true);
		const data = output.map((u) => (u._meta?.terminal_output as { terminal_id: string; data: string }).data);
		expect(output.every((u) => (u._meta?.terminal_output as { terminal_id: string }).terminal_id === id)).toBe(true);
		expect(data[0]).toBe("first\n");
		expect(data.join("")).toBe("first\n\x1b[31msecond\x1b[0m\nno newline");
		expect(updates.at(-1)).toEqual({
			sessionUpdate: "tool_call_update",
			toolCallId: id,
			status: "failed",
			_meta: { terminal_exit: { terminal_id: id, exit_code: 3 } },
		});

		// A command that does not finish has no exit code; the terminal says why it stopped.
		const timedOut = await runCommand(zed, "timeout");
		const timedOutData = timedOut.updates
			.slice(0, -1)
			.map((u) => (u._meta?.terminal_output as { data: string }).data)
			.join("");
		expect(timedOutData).toBe("started\nCommand timed out after 1 seconds\n");
		expect(timedOut.updates.at(-1)?._meta).toEqual({
			terminal_exit: { terminal_id: timedOut.call.toolCallId, exit_code: null },
		});

		// Other clients get the output as text.
		const plain = await runCommand(CLIENT_CAPABILITIES, "terminal");
		expect(plain.call.content).toBeUndefined();
		expect(plain.call._meta).toBeUndefined();
		expect(plain.updates.every((u) => u._meta === undefined)).toBe(true);
		expect(plain.updates.at(-1)?.status).toBe("failed");
		expect(toolText(plain.updates.at(-1)?.content)).toContain("Command exited with code 3");
	});

	it("cancels a running prompt", async () => {
		const { client } = await startClient();
		const { sessionId } = await client.request<SessionResponse>("session/new", { cwd: projectDir, mcpServers: [] });
		const prompt = client.request<PromptResponse>("session/prompt", {
			sessionId,
			prompt: [{ type: "text", text: "[slow] wait" }],
		});
		await new Promise((r) => setTimeout(r, 500));
		client.notify("session/cancel", { sessionId });
		expect(await prompt).toEqual({ stopReason: "cancelled" });
	});

	it("loads a session from an earlier process and replays it", async () => {
		const first = await startClient();
		const { sessionId } = await first.client.request<SessionResponse>("session/new", {
			cwd: projectDir,
			mcpServers: [],
		});
		await first.client.request("session/prompt", { sessionId, prompt: [{ type: "text", text: "[hello] hi there" }] });
		expect(await first.client.stop()).toBe(0);

		const { client } = await startClient();
		const loaded = await client.request<{ configOptions: ConfigOption[] }>("session/load", {
			sessionId,
			cwd: projectDir,
			mcpServers: [],
		});
		expect(loaded.configOptions.length).toBeGreaterThan(0);
		expect(client.updatesOf("user_message_chunk").map((u) => blockText(u.content))).toEqual(["[hello] hi there"]);
		expect(client.updatesOf("agent_message_chunk").map((u) => blockText(u.content))).toEqual([
			"Hello from the fake model.",
		]);

		const resumed = await startClient();
		await resumed.client.request("session/resume", { sessionId, cwd: projectDir, mcpServers: [] });
		await new Promise((r) => setTimeout(r, 100));
		expect(resumed.client.updatesOf("user_message_chunk")).toEqual([]);

		const missing = await client.call("session/load", { sessionId: "nope", cwd: projectDir, mcpServers: [] });
		expect(missing.error?.code).toBe(-32002);
	});

	it("asks the editor when an extension asks the user", async () => {
		const extensionPath = join(root, "ask-extension.ts");
		writeFileSync(
			extensionPath,
			`export default function (pi) {
	pi.registerCommand("ask", {
		description: "Ask a question",
		handler: async (_args, ctx) => {
			const ok = await ctx.ui.confirm("Proceed?", "Shall I go on?");
			const name = await ctx.ui.input("Your name");
			ctx.ui.notify("answer: " + ok + " " + name, "info");
		},
	});
}
`,
		);
		const { client } = await startClient(["-e", extensionPath]);
		client.onRequest = (message) =>
			message.method === "session/request_permission"
				? {
						outcome: {
							outcome: "selected",
							optionId: (message.params as RequestPermissionRequest).options[0].optionId,
						},
					}
				: { action: "accept", content: { value: "Ada" } };
		const { sessionId } = await client.request<SessionResponse>("session/new", { cwd: projectDir, mcpServers: [] });
		await client.waitFor(() => client.updatesOf("available_commands_update").length > 0);
		expect(commandNames(client)).toContain("ask");
		expect(
			await client.request<PromptResponse>("session/prompt", {
				sessionId,
				prompt: [{ type: "text", text: "/ask" }],
			}),
		).toEqual({
			stopReason: "end_turn",
		});
		expect(client.clientRequests.map((m) => m.method)).toEqual(["session/request_permission", "elicitation/create"]);
		const dialog = (client.clientRequests[0].params as RequestPermissionRequest).toolCall;
		expect(dialog.title).toBe("Proceed?");
		// The dialog's own entry ends once answered.
		expect(client.updatesOf("tool_call_update").filter((u) => u.toolCallId === dialog.toolCallId)).toEqual([
			{ sessionUpdate: "tool_call_update", toolCallId: dialog.toolCallId, status: "completed" },
		]);
		expect(client.updatesOf("notice").map((u) => u.title)).toContain("answer: true Ada");
	});

	it("asks about a running tool on its own entry and gives the entry back", async () => {
		const extensionPath = join(root, "gate-extension.ts");
		writeFileSync(
			extensionPath,
			`export default function (pi) {
	pi.on("tool_call", async (event, ctx) => {
		if (!(await ctx.ui.confirm("Allow bash?", "It runs a command"))) return { block: true, reason: "denied" };
	});
}
`,
		);
		const { client } = await startClient(["-e", extensionPath]);
		client.onRequest = (message) => ({
			outcome: { outcome: "selected", optionId: (message.params as RequestPermissionRequest).options[0].optionId },
		});
		const { sessionId } = await client.request<SessionResponse>("session/new", { cwd: projectDir, mcpServers: [] });
		await client.request("session/prompt", { sessionId, prompt: [{ type: "text", text: "[live] run it" }] });
		const [call] = client.updatesOf("tool_call");
		const asked = (client.clientRequests[0].params as RequestPermissionRequest).toolCall;
		expect(asked).toMatchObject({ toolCallId: call.toolCallId, title: "Allow bash?" });
		const updates = client.updatesOf("tool_call_update").filter((u) => u.toolCallId === call.toolCallId);
		expect(updates[0]).toEqual({
			sessionUpdate: "tool_call_update",
			toolCallId: call.toolCallId,
			title: call.title,
			content: [],
		});
		expect(updates.at(-1)?.status).toBe("completed");
	});

	it("connects the MCP servers the editor passes, with their environment as given", async () => {
		const serverPath = join(root, "mcp-server.mjs");
		const marker = join(root, "mcp-marker.json");
		writeFileSync(
			serverPath,
			`import { writeFileSync } from "node:fs";
let buffer = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", (chunk) => {
	buffer += chunk;
	let index;
	while ((index = buffer.indexOf("\\n")) !== -1) {
		const message = JSON.parse(buffer.slice(0, index));
		buffer = buffer.slice(index + 1);
		if (message.id === undefined) continue;
		let result = {};
		if (message.method === "initialize") {
			writeFileSync(process.env.MARKER, JSON.stringify({ token: process.env.TOKEN }));
			result = { protocolVersion: message.params.protocolVersion, capabilities: { tools: {} }, serverInfo: { name: "ide", version: "1" } };
		} else if (message.method === "tools/list") {
			result = { tools: [] };
		}
		process.stdout.write(JSON.stringify({ jsonrpc: "2.0", id: message.id, result }) + "\\n");
	}
});
`,
		);
		const { client } = await startClient();
		await client.request<SessionResponse>("session/new", {
			cwd: projectDir,
			mcpServers: [
				{
					name: "IDE tools",
					command: process.execPath,
					args: [serverPath],
					env: [
						{ name: "MARKER", value: marker },
						{ name: "TOKEN", value: EDITOR_TOKEN },
					],
				},
			],
		});
		await client.waitFor(() => {
			try {
				return JSON.parse(readFileSync(marker, "utf8")).token === EDITOR_TOKEN;
			} catch {
				return false;
			}
		});
	});

	it("changes the thinking level and model through config options", async () => {
		const { client } = await startClient();
		const { sessionId } = await client.request<SessionResponse>("session/new", { cwd: projectDir, mcpServers: [] });
		const result = await client.request<{ configOptions: ConfigOption[] }>("session/set_config_option", {
			sessionId,
			configId: "model",
			value: "fake/fake-model",
		});
		expect(result.configOptions.find((option) => option.id === "model")?.currentValue).toBe("fake/fake-model");
		const bad = await client.call("session/set_config_option", { sessionId, configId: "model", value: "x/y" });
		expect(bad.error?.code).toBe(-32602);
	});

	it("offers only the models of --models", async () => {
		const { client } = await startClient(["--models", "fake/fake-model"]);
		const { sessionId, configOptions } = await client.request<SessionResponse>("session/new", {
			cwd: projectDir,
			mcpServers: [],
		});
		const model = configOptions.find((option) => option.id === "model");
		expect(model?.options.map((choice) => choice.value)).toEqual(["fake/fake-model"]);
		const outside = await client.call("session/set_config_option", {
			sessionId,
			configId: "model",
			value: "fake/other-model",
		});
		expect(outside.error?.code).toBe(-32602);
	});

	it("fails a session whose runtime reports an error", async () => {
		const { client } = await startClient(["--provider", "no-such-provider", "--model", "fake-model"]);
		const response = await client.call("session/new", { cwd: projectDir, mcpServers: [] });
		expect(response.error?.code).toBe(-32603);
		expect(response.error?.message).toContain("no-such-provider");
	});

	it("lists the sessions of --session-dir without a directory filter", async () => {
		const sessionDir = join(root, "custom-sessions");
		const { client } = await startClient(["--session-dir", sessionDir]);
		const { sessionId } = await client.request<SessionResponse>("session/new", { cwd: projectDir, mcpServers: [] });
		await client.request<PromptResponse>("session/prompt", {
			sessionId,
			prompt: [{ type: "text", text: "[hello] stored elsewhere" }],
		});
		const listed = await client.request<{ sessions: SessionInfo[] }>("session/list", {});
		expect(listed.sessions.map((session) => session.sessionId)).toContain(sessionId);
	});

	it("offers a read-only mode, as modes and as a config option", async () => {
		const { client } = await startClient();
		const created = await client.request<SessionResponse & { modes: SessionModeState }>("session/new", {
			cwd: projectDir,
			mcpServers: [],
		});
		const { sessionId } = created;
		expect(created.modes.currentModeId).toBe("default");
		expect(created.modes.availableModes.map((mode) => mode.id)).toEqual(["default", "read-only"]);
		expect(created.configOptions[0]).toMatchObject({ id: "mode", category: "mode", currentValue: "default" });

		await client.request("session/prompt", { sessionId, prompt: [{ type: "text", text: "[hello] tools?" }] });
		const usual = offeredTools;
		expect(usual).toContain("bash");

		const set = await client.request<{ configOptions: ConfigOption[] }>("session/set_config_option", {
			sessionId,
			configId: "mode",
			value: "read-only",
		});
		expect(set.configOptions[0].currentValue).toBe("read-only");
		await client.request("session/prompt", { sessionId, prompt: [{ type: "text", text: "[hello] tools?" }] });
		expect(offeredTools.sort()).toEqual(["find", "grep", "ls", "read"]);

		expect(await client.request("session/set_mode", { sessionId, modeId: "default" })).toEqual({});
		await client.request("session/prompt", { sessionId, prompt: [{ type: "text", text: "[hello] tools?" }] });
		expect(offeredTools).toEqual(usual);

		const bad = await client.call("session/set_mode", { sessionId, modeId: "yolo" });
		expect(bad.error?.code).toBe(-32602);
		// The editor's own changes are answered by their responses, not by updates.
		expect(client.updatesOf("config_option_update")).toEqual([]);
	});

	it("holds read-only mode for a tool registered after the mode was set", async () => {
		const marker = join(root, "late-tool-ran");
		const extensionPath = join(root, "late-tool-extension.ts");
		writeFileSync(
			extensionPath,
			`import { writeFileSync } from "node:fs";
export default function (pi) {
	let registered = false;
	pi.on("input", () => {
		if (registered) return;
		registered = true;
		pi.registerTool({
			name: "touch",
			label: "touch",
			description: "Writes a file",
			parameters: { type: "object", properties: {} },
			execute: async () => {
				writeFileSync(${JSON.stringify(marker)}, "ran");
				return { content: [{ type: "text", text: "touched" }], details: {} };
			},
		});
	});
}
`,
		);
		const { client } = await startClient(["-e", extensionPath]);
		const { sessionId } = await client.request<SessionResponse>("session/new", { cwd: projectDir, mcpServers: [] });
		await client.request("session/set_mode", { sessionId, modeId: "read-only" });
		await client.request("session/prompt", { sessionId, prompt: [{ type: "text", text: "[touch] go" }] });
		expect(existsSync(marker)).toBe(false);
		const [call] = client.updatesOf("tool_call").filter((update) => update.name === "touch");
		const result = client.updatesOf("tool_call_update").find((update) => update.toolCallId === call?.toolCallId);
		expect(result?.status).toBe("failed");
		expect(toolText(result?.content)).toContain("Read-only mode");

		// The next prompt does not offer it.
		await client.request("session/prompt", { sessionId, prompt: [{ type: "text", text: "[hello] tools?" }] });
		expect(offeredTools.sort()).toEqual(["find", "grep", "ls", "read"]);
	});

	it("decides project trust for each new session, not once when the process started", async () => {
		// (The real path: the process's directory is, and the startup check is of it.)
		const dir = join(realpathSync(root), "late-project");
		mkdirSync(dir, { recursive: true });
		const marker = join(root, "late-project-extension-ran");
		const client = new AcpTestClient(dir, agentDir);
		clients.push(client);
		await client.request("initialize", { protocolVersion: 1, clientCapabilities: CLIENT_CAPABILITIES });
		await client.request<SessionResponse>("session/new", { cwd: dir, mcpServers: [] });
		// The project's extension arrives after the process started (a pull, a branch switch). Nothing answered a trust
		// prompt, so it does not load.
		mkdirSync(join(dir, ".pi", "extensions"), { recursive: true });
		writeFileSync(
			join(dir, ".pi", "extensions", "marker.ts"),
			`import { writeFileSync } from "node:fs";\nexport default function () { writeFileSync(${JSON.stringify(marker)}, "ran"); }\n`,
		);
		await client.request<SessionResponse>("session/new", { cwd: dir, mcpServers: [] });
		expect(existsSync(marker)).toBe(false);
	});

	it("exports to a file of the session's directory whatever the session id says", async () => {
		const sessionDir = join(root, "export-sessions");
		const dir = join(root, "export-project", "nested");
		mkdirSync(sessionDir, { recursive: true });
		mkdirSync(dir, { recursive: true });
		const id = "/../../escaped";
		const header = { type: "session", version: 3, id, timestamp: new Date().toISOString(), cwd: dir };
		const message = {
			type: "message",
			id: "m1",
			parentId: null,
			timestamp: new Date().toISOString(),
			message: { role: "user", content: [{ type: "text", text: "hi" }], timestamp: Date.now() },
		};
		writeFileSync(
			join(sessionDir, "2026-01-01T00-00-00-000Z_crafted.jsonl"),
			`${JSON.stringify(header)}\n${JSON.stringify(message)}\n`,
		);
		const { client } = await startClient(["--session-dir", sessionDir]);
		await client.request("session/load", { sessionId: id, cwd: dir, mcpServers: [] });
		await client.request("session/prompt", { sessionId: id, prompt: [{ type: "text", text: "/export" }] });
		expect(existsSync(join(root, "export-project", "escaped.html"))).toBe(false);
		expect(readdirSync(dir).filter((file) => file.endsWith(".html"))).toEqual(["pi-session-_.._.._escaped.html"]);
	});

	it("runs Pi's commands that need no terminal", async () => {
		const { client } = await startClient();
		const { sessionId } = await client.request<SessionResponse>("session/new", { cwd: projectDir, mcpServers: [] });
		await client.waitFor(() => client.updatesOf("available_commands_update").length > 0);
		expect(commandNames(client)).toEqual(
			expect.arrayContaining(["compact", "name", "model", "thinking", "session", "export", "reload"]),
		);
		const run = async (text: string) => {
			const before = client.updates.length;
			await client.request<PromptResponse>("session/prompt", { sessionId, prompt: [{ type: "text", text }] });
			return client.updates.slice(before).map((notification) => notification.update);
		};
		const replies = (updates: SessionUpdate[]) =>
			updates
				.filter(
					(update): update is UpdateOf<"agent_message_chunk"> => update.sessionUpdate === "agent_message_chunk",
				)
				.map((update) => blockText(update.content))
				.join("");

		await run("[hello] hi");
		expect(replies(await run("/session"))).toContain(`ID: ${sessionId}`);

		const exported = await run("/export transcript.jsonl");
		const exportPath = join(projectDir, "transcript.jsonl");
		expect(replies(exported)).toContain(exportPath);
		expect(exported).toContainEqual(
			expect.objectContaining({
				sessionUpdate: "agent_message_chunk",
				content: { type: "resource_link", uri: pathToFileURL(exportPath).href, name: "transcript.jsonl" },
			}),
		);
		expect(readFileSync(exportPath, "utf8")).toContain("[hello] hi");

		const switched = await run("/model fake/other-model");
		expect(replies(switched)).toContain("Model: fake/other-model");
		const update = switched.find(
			(u): u is UpdateOf<"config_option_update"> => u.sessionUpdate === "config_option_update",
		);
		expect(update?.configOptions.find((option) => option.id === "model")?.currentValue).toBe("fake/other-model");
		expect(replies(await run("/model"))).toContain("fake/fake-model");

		const reloaded = await run("/reload");
		expect(reloaded.some((u) => u.sessionUpdate === "available_commands_update")).toBe(true);
		expect(replies(reloaded)).toContain("Reloaded");

		const unknown = await client.call("session/prompt", {
			sessionId,
			prompt: [{ type: "text", text: "/thinking extreme" }],
		});
		expect(unknown.error?.message).toContain("Unknown thinking level");
	});

	it("shows compactions to clients that take them, live and when loading", async () => {
		const capabilities = { ...CLIENT_CAPABILITIES, session: { notices: {}, compaction: {} } };
		const first = await startClient([], capabilities);
		const { sessionId } = await first.client.request<SessionResponse>("session/new", {
			cwd: projectDir,
			mcpServers: [],
		});
		for (const text of ["[hello] one", "[hello] two"]) {
			await first.client.request("session/prompt", { sessionId, prompt: [{ type: "text", text }] });
		}
		await first.client.request("session/prompt", { sessionId, prompt: [{ type: "text", text: "/compact" }] });
		const live = first.client.updatesOf("compaction_update");
		expect(live.map((u) => u.status)).toEqual(["in_progress", "completed"]);
		expect(live[1].compactionId).toBe(live[0].compactionId);
		expect(live[1].summary?.[0]).toMatchObject({ type: "text" });
		// The compaction entry says it all: no reply of its own.
		expect(first.client.updatesOf("agent_message_chunk").map((u) => blockText(u.content))).not.toContainEqual(
			expect.stringContaining("Compacted"),
		);
		await first.client.stop();

		const { client } = await startClient([], capabilities);
		await client.request("session/load", { sessionId, cwd: projectDir, mcpServers: [] });
		expect(client.updatesOf("compaction_update")).toEqual([
			expect.objectContaining({ status: "completed", summary: live[1].summary }),
		]);
	});

	it("forks a session into a new one with its history", async () => {
		const { client } = await startClient();
		const { sessionId } = await client.request<SessionResponse>("session/new", { cwd: projectDir, mcpServers: [] });
		await client.request("session/prompt", {
			sessionId,
			prompt: [{ type: "text", text: "[hello] before the fork" }],
		});
		const fork = await client.request<SessionResponse & { modes: SessionModeState }>("session/fork", {
			sessionId,
			cwd: projectDir,
			mcpServers: [],
		});
		expect(fork.sessionId).not.toBe(sessionId);
		expect(fork.modes.currentModeId).toBe("default");
		await client.request("session/prompt", {
			sessionId: fork.sessionId,
			prompt: [{ type: "text", text: "[hello] after the fork" }],
		});

		const other = await startClient();
		await other.client.request("session/load", { sessionId: fork.sessionId, cwd: projectDir, mcpServers: [] });
		expect(other.client.updatesOf("user_message_chunk").map((u) => blockText(u.content))).toEqual([
			"[hello] before the fork",
			"[hello] after the fork",
		]);
		const missing = await client.call("session/fork", { sessionId: "nope", cwd: projectDir, mcpServers: [] });
		expect(missing.error?.code).toBe(-32002);
	});

	it("deletes a session, to the trash when the trash command is there", async () => {
		const bin = join(root, "bin");
		const trashDir = join(root, "trash");
		mkdirSync(bin, { recursive: true });
		mkdirSync(trashDir, { recursive: true });
		writeFileSync(join(bin, "trash"), `#!/bin/sh\n[ "$1" = "--" ] && shift\nmv "$1" "${trashDir}/"\n`, {
			mode: 0o755,
		});
		const { client } = await startClient([], CLIENT_CAPABILITIES, { PATH: `${bin}:${process.env.PATH}` });
		const { sessionId } = await client.request<SessionResponse>("session/new", { cwd: projectDir, mcpServers: [] });
		await client.request("session/prompt", { sessionId, prompt: [{ type: "text", text: "[hello] delete me" }] });
		const listed = await client.request<{ sessions: SessionInfo[] }>("session/list", { cwd: projectDir });
		expect(listed.sessions.map((session) => session.sessionId)).toContain(sessionId);

		expect(await client.request("session/delete", { sessionId })).toEqual({});
		const after = await client.request<{ sessions: SessionInfo[] }>("session/list", { cwd: projectDir });
		expect(after.sessions.map((session) => session.sessionId)).not.toContain(sessionId);
		expect(readdirSync(trashDir).some((file) => file.includes(sessionId))).toBe(true);
		const prompt = await client.call("session/prompt", { sessionId, prompt: [{ type: "text", text: "hi" }] });
		expect(prompt.error?.code).toBe(-32002);
		// Deleting it again, or a session that never was, succeeds.
		expect(await client.request("session/delete", { sessionId })).toEqual({});
		expect(await client.request("session/delete", { sessionId: "never-was" })).toEqual({});
	});
});
