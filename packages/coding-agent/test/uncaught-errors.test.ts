// An unhandled rejection ends every mode as an uncaught exception does on Node: the error on stderr, the tool processes
// Pi started killed, exit status 1. Bun ends the process on a rejection without calling `uncaughtException` listeners
// (up-issues item 1, upstream #12566), and the modes without a terminal UI had no cleanup for either.
import { spawn } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { ENV_AGENT_DIR } from "../src/config.ts";
import { InteractiveMode } from "../src/modes/interactive/interactive-mode.ts";
import { exitOnUncaughtErrors, onUnhandledRejection } from "../src/modes/uncaught-errors.ts";

const cliPath = resolve(__dirname, "../src/cli.ts");
const sourceResolverUrl = pathToFileURL(resolve(__dirname, "../src/experimental/source-resolver.ts")).href;
/** A Bun to run the sources with instead of Node (the runtime this is about), such as `bun`. */
const bun = process.env.PI_UNCAUGHT_TEST_BUN;

/** Runs `run` with only the error listeners it adds (vitest's own would take every error). */
function withOwnListeners(run: () => void): void {
	const exceptions = process.listeners("uncaughtException");
	const rejections = process.listeners("unhandledRejection");
	process.removeAllListeners("uncaughtException");
	process.removeAllListeners("unhandledRejection");
	try {
		run();
	} finally {
		process.removeAllListeners("uncaughtException");
		process.removeAllListeners("unhandledRejection");
		for (const listener of exceptions) process.on("uncaughtException", listener);
		for (const listener of rejections) process.on("unhandledRejection", listener);
	}
}

describe("unhandled rejections", () => {
	afterEach(() => {
		vi.restoreAllMocks();
	});

	it("reach interactive mode's crash handler", () => {
		const uncaughtCrash = vi.fn();
		const mode = { signalCleanupHandlers: [] as Array<() => void>, uncaughtCrash, unregisterSignalHandlers() {} };
		mode.unregisterSignalHandlers = () => {
			for (const cleanup of mode.signalCleanupHandlers) cleanup();
			mode.signalCleanupHandlers = [];
		};
		const register = (InteractiveMode.prototype as unknown as { registerSignalHandlers(this: typeof mode): void })
			.registerSignalHandlers;
		withOwnListeners(() => {
			register.call(mode);
			try {
				const error = new Error("boom-rejection");
				process.emit("unhandledRejection", error, Promise.resolve());
				expect(uncaughtCrash).toHaveBeenCalledWith(error);
				// A reason that is not an Error becomes the error Node raises for it.
				process.emit("unhandledRejection", "text", Promise.resolve());
				expect(uncaughtCrash.mock.calls[1][0]).toMatchObject({ code: "ERR_UNHANDLED_REJECTION" });
				expect(String(uncaughtCrash.mock.calls[1][0].message)).toContain(`reason "'text'"`);
			} finally {
				mode.unregisterSignalHandlers();
			}
			expect(process.listenerCount("unhandledRejection")).toBe(0);
		});
	});

	it("are left to an extension's own listener, as on Node", () => {
		const crash = vi.fn();
		withOwnListeners(() => {
			const off = onUnhandledRejection(crash);
			const extensionListener = () => {};
			process.on("unhandledRejection", extensionListener);
			process.emit("unhandledRejection", new Error("taken"), Promise.resolve());
			process.off("unhandledRejection", extensionListener);
			off();
		});
		expect(crash).not.toHaveBeenCalled();
	});

	it("end a mode without a terminal UI with status 1, unless an extension's listener takes them", () => {
		const exit = vi.spyOn(process, "exit").mockImplementation((() => undefined) as never);
		const stderr = vi.spyOn(console, "error").mockImplementation(() => {});
		withOwnListeners(() => {
			const off = exitOnUncaughtErrors();
			try {
				const extensionListener = () => {};
				process.on("uncaughtException", extensionListener);
				process.emit("unhandledRejection", new Error("taken"), Promise.resolve());
				process.off("uncaughtException", extensionListener);
				expect(exit).not.toHaveBeenCalled();

				const error = new Error("boom-rejection");
				process.emit("unhandledRejection", error, Promise.resolve());
				expect(stderr).toHaveBeenCalledWith(error);
				expect(exit).toHaveBeenCalledWith(1);
			} finally {
				off();
			}
		});
	});
});

describe("unhandled rejections in each mode", () => {
	let server: Server;
	let root: string;
	let agentDir: string;
	let projectDir: string;
	let extensionPath: string;

	beforeAll(async () => {
		// A model whose first answer runs a long command; the extension rejects a promise while it runs.
		server = createServer((req, res) => {
			req.resume();
			req.on("end", () => {
				res.writeHead(200, { "content-type": "text/event-stream" });
				const chunk = (delta: object, finish: string | null = null) =>
					res.write(
						`data: ${JSON.stringify({ id: "x", object: "chat.completion.chunk", model: "fake-model", choices: [{ index: 0, delta, finish_reason: finish }] })}\n\n`,
					);
				chunk({ role: "assistant", content: "" });
				chunk({
					tool_calls: [
						{
							index: 0,
							id: "call_0",
							type: "function",
							function: {
								name: "bash",
								arguments: JSON.stringify({ command: 'echo $$ > "$PIDFILE"; sleep 30' }),
							},
						},
					],
				});
				chunk({}, "tool_calls");
				res.end("data: [DONE]\n\n");
			});
		});
		await new Promise<void>((done) => server.listen(0, "127.0.0.1", () => done()));
		const { port } = server.address() as AddressInfo;
		root = mkdtempSync(join(tmpdir(), "pi-uncaught-"));
		agentDir = join(root, "agent");
		projectDir = join(root, "project");
		mkdirSync(agentDir, { recursive: true });
		mkdirSync(projectDir, { recursive: true });
		writeFileSync(
			join(agentDir, "models.json"),
			JSON.stringify({
				providers: {
					fake: {
						baseUrl: `http://127.0.0.1:${port}/v1`,
						api: "openai-completions",
						apiKey: "fake",
						models: [{ id: "fake-model", contextWindow: 200000, maxTokens: 8192 }],
					},
				},
			}),
		);
		writeFileSync(
			join(agentDir, "settings.json"),
			JSON.stringify({ lastChangelogVersion: "9999.0.0", defaultProvider: "fake", defaultModel: "fake-model" }),
		);
		writeFileSync(join(agentDir, "auth.json"), "{}");
		extensionPath = join(root, "reject-extension.ts");
		writeFileSync(
			extensionPath,
			`export default function (pi) {
	pi.on("tool_execution_start", () => {
		setTimeout(() => {
			void Promise.reject(new Error("boom-rejection"));
		}, 500);
	});
}
`,
		);
	});

	afterAll(() => {
		server?.close();
		rmSync(root, { recursive: true, force: true });
	});

	const modes: { name: string; args: string[]; input: (write: (message: object) => void, lines: string[]) => void }[] =
		[
			{ name: "print", args: ["-p", "go"], input: () => {} },
			{ name: "json", args: ["--mode", "json", "go"], input: () => {} },
			{ name: "rpc", args: ["--mode", "rpc"], input: (write) => write({ type: "prompt", message: "go" }) },
			{
				name: "acp",
				args: ["--acp"],
				input: (write, lines) => {
					write({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: 1 } });
					write({ jsonrpc: "2.0", id: 2, method: "session/new", params: { cwd: projectDir, mcpServers: [] } });
					const timer = setInterval(() => {
						const created = lines.map((line) => JSON.parse(line)).find((message) => message.id === 2);
						if (!created) return;
						clearInterval(timer);
						write({
							jsonrpc: "2.0",
							id: 3,
							method: "session/prompt",
							params: { sessionId: created.result.sessionId, prompt: [{ type: "text", text: "go" }] },
						});
					}, 50);
				},
			},
			{
				name: "mcp",
				args: ["--mcp"],
				input: (write) => {
					write({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: "2025-06-18" } });
					write({ jsonrpc: "2.0", method: "notifications/initialized" });
					write({
						jsonrpc: "2.0",
						id: 2,
						method: "tools/call",
						params: { name: "prompt", arguments: { prompt: "go" } },
					});
				},
			},
		];

	for (const mode of modes) {
		it(`end ${mode.name} mode with the error, status 1, and the tool's processes killed`, async () => {
			const pidFile = join(root, `${mode.name}.pid`);
			const args = ["--no-session", "-e", extensionPath, ...mode.args];
			const child = spawn(
				bun ?? process.execPath,
				bun ? [cliPath, ...args] : ["--import", sourceResolverUrl, cliPath, ...args],
				{
					cwd: projectDir,
					env: { ...process.env, [ENV_AGENT_DIR]: agentDir, PI_OFFLINE: "1", PIDFILE: pidFile },
					stdio: ["pipe", "pipe", "pipe"],
				},
			);
			let stderr = "";
			child.stderr.on("data", (chunk) => {
				stderr += chunk.toString();
			});
			const lines: string[] = [];
			let buffer = "";
			child.stdout.setEncoding("utf8");
			child.stdout.on("data", (chunk: string) => {
				buffer += chunk;
				for (let index = buffer.indexOf("\n"); index !== -1; index = buffer.indexOf("\n")) {
					if (buffer.slice(0, index).trim().startsWith("{")) lines.push(buffer.slice(0, index));
					buffer = buffer.slice(index + 1);
				}
			});
			const exited = new Promise<number | null>((done) => child.once("exit", (code) => done(code)));
			mode.input((message) => child.stdin.write(`${JSON.stringify(message)}\n`), lines);
			if (mode.name === "print" || mode.name === "json") child.stdin.end();
			const code = await exited;
			expect(stderr).toContain("boom-rejection");
			expect(code, stderr).toBe(1);
			expect(existsSync(pidFile)).toBe(true);
			const pid = Number(readFileSync(pidFile, "utf8").trim());
			await vi.waitFor(() => expect(isAlive(pid)).toBe(false), { timeout: 3000 });
		}, 60_000);
	}
});

function isAlive(pid: number): boolean {
	try {
		process.kill(pid, 0);
		return true;
	} catch {
		return false;
	}
}
