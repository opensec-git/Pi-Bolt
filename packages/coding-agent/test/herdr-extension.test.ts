import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import net from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, test, vi } from "vitest";
import { COMMAND_NAME } from "../src/config.ts";
import type { ExtensionAPI } from "../src/core/extensions/types.ts";
import { createHerdrExtension, quietOtherHerdrReporters, resumeArgv } from "../src/extensions/herdr/index.ts";
import { isHerdrPiIntegration } from "../src/extensions/herdr/integration-file.ts";

type Request = { id: string; method: string; params: Record<string, unknown> };

const cleanups: Array<() => void | Promise<void>> = [];
afterEach(async () => {
	for (const cleanup of cleanups.splice(0).reverse()) await cleanup();
	delete (globalThis as { PIBOLT_BUILD?: string }).PIBOLT_BUILD;
	vi.resetModules();
});

function tempDir(): string {
	const dir = mkdtempSync(join(tmpdir(), "herdr-"));
	cleanups.push(() => rmSync(dir, { recursive: true, force: true }));
	return dir;
}

/** A Herdr socket that answers every request and keeps what it got. */
async function fakeHerdr(): Promise<{ socketPath: string; requests: Request[] }> {
	const socketPath = join(tempDir(), "herdr.sock");
	const requests: Request[] = [];
	const server = net.createServer((socket) => {
		let buffered = "";
		socket.on("data", (chunk) => {
			buffered += chunk.toString();
			for (let newline = buffered.indexOf("\n"); newline >= 0; newline = buffered.indexOf("\n")) {
				const request = JSON.parse(buffered.slice(0, newline)) as Request;
				buffered = buffered.slice(newline + 1);
				requests.push(request);
				socket.write(`${JSON.stringify({ id: request.id, result: {} })}\n`);
			}
		});
	});
	await new Promise<void>((resolve) => server.listen(socketPath, resolve));
	cleanups.push(() => new Promise<void>((resolve) => server.close(() => resolve())));
	return { socketPath, requests };
}

function fakePi() {
	const handlers = new Map<string, Array<(event: unknown, ctx: unknown) => unknown>>();
	const listeners = new Map<string, Array<(data: unknown) => void>>();
	const pi = {
		on: (name: string, handler: (event: unknown, ctx: unknown) => unknown) => {
			handlers.set(name, [...(handlers.get(name) ?? []), handler]);
		},
		events: {
			on: (name: string, listener: (data: unknown) => void) => {
				listeners.set(name, [...(listeners.get(name) ?? []), listener]);
			},
		},
	};
	return {
		pi: pi as unknown as ExtensionAPI,
		handlers,
		fire: async (name: string, event: unknown, ctx?: unknown) => {
			for (const handler of handlers.get(name) ?? []) await handler(event, ctx);
		},
		emit: (name: string, data: unknown) => {
			for (const listener of listeners.get(name) ?? []) listener(data);
		},
	};
}

function context(options: { mode?: string; idle?: () => boolean; sessionFile?: string } = {}) {
	return {
		mode: options.mode ?? "tui",
		isIdle: options.idle ?? (() => true),
		sessionManager: {
			getSessionFile: () => options.sessionFile,
			getSessionId: () => "session-id",
		},
	};
}

async function until(check: () => boolean) {
	for (let i = 0; i < 200 && !check(); i++) await new Promise((resolve) => setTimeout(resolve, 5));
	expect(check()).toBe(true);
}

async function startedInHerdr(options: { mode?: string; sessionFile?: string } = {}) {
	const herdr = await fakeHerdr();
	const env = { HERDR_ENV: "1", HERDR_SOCKET_PATH: herdr.socketPath, HERDR_PANE_ID: "w1:p2" };
	const fake = fakePi();
	let idle = true;
	createHerdrExtension(env)(fake.pi);
	const ctx = context({ mode: options.mode, idle: () => idle, sessionFile: options.sessionFile ?? "/work/s.jsonl" });
	await fake.fire("session_start", { type: "session_start", reason: "startup" }, ctx);
	const states = () => herdr.requests.filter((r) => r.method === "pane.report_agent").map((r) => r.params.state);
	return {
		...fake,
		herdr,
		ctx,
		states,
		setIdle: (value: boolean) => {
			idle = value;
		},
	};
}

describe("Herdr extension", () => {
	test("does nothing outside Herdr", () => {
		const fake = fakePi();
		createHerdrExtension({})(fake.pi);
		expect(fake.handlers.size).toBe(0);
	});

	test("reports the session, its resume command, and each state change", async () => {
		const run = await startedInHerdr();
		await until(() => run.states().length === 1);
		expect(run.herdr.requests[0].params).toMatchObject({
			pane_id: "w1:p2",
			source: "pi-bolt",
			agent: "pi-bolt",
			state: "idle",
			agent_session_path: "/work/s.jsonl",
			resume_argv: [COMMAND_NAME, "--session", "/work/s.jsonl"],
		});

		run.setIdle(false);
		await run.fire("agent_start", { type: "agent_start" }, run.ctx);
		await until(() => run.states().length === 2);
		// A dialog in the middle of a run is the agent waiting for the user.
		await run.fire("ui_prompt_start", { type: "ui_prompt_start", reason: "ui_prompt", kind: "confirm" });
		await until(() => run.states().length === 3);
		await run.fire("ui_prompt_end", { type: "ui_prompt_end", reason: "ui_prompt", kind: "confirm" });
		await until(() => run.states().length === 4);
		run.setIdle(true);
		await run.fire("agent_settled", { type: "agent_settled", aborted: false }, run.ctx);
		await until(() => run.states().length === 5);
		expect(run.states()).toEqual(["idle", "working", "blocked", "working", "idle"]);

		const seqs = run.herdr.requests.map((r) => r.params.seq as number);
		expect([...seqs].sort((a, b) => a - b)).toEqual(seqs);
		expect(new Set(seqs).size).toBe(seqs.length);
	});

	test("a dialog opened at the prompt is not a block", async () => {
		const run = await startedInHerdr();
		await until(() => run.states().length === 1);
		await run.fire("ui_prompt_start", { type: "ui_prompt_start", reason: "ui_prompt", kind: "select" });
		await new Promise((resolve) => setTimeout(resolve, 50));
		expect(run.states()).toEqual(["idle"]);
	});

	test("herdr:blocked and background subagents", async () => {
		const run = await startedInHerdr();
		await until(() => run.states().length === 1);
		run.emit("herdr:blocked", { active: true, label: "Question for you" });
		await until(() => run.states().length === 2);
		expect(run.herdr.requests[1].params).toMatchObject({ state: "blocked", message: "Question for you" });
		run.emit("herdr:blocked", { active: false });
		await until(() => run.states().length === 3);

		run.emit("subagents:started", { id: "a1" });
		await until(() => run.states().length === 4);
		run.emit("subagents:completed", { id: "a1" });
		await until(() => run.states().length === 5);
		expect(run.states()).toEqual(["idle", "blocked", "idle", "working", "idle"]);
	});

	test("print, JSON and RPC runs and subagent sessions do not report", async () => {
		const run = await startedInHerdr({ mode: "print" });
		await run.fire("agent_start", { type: "agent_start" }, run.ctx);
		await new Promise((resolve) => setTimeout(resolve, 50));
		expect(run.herdr.requests).toEqual([]);
	});

	test("releases the pane on quit only", async () => {
		const run = await startedInHerdr();
		await until(() => run.states().length === 1);
		await run.fire("session_shutdown", { type: "session_shutdown", reason: "new" });
		expect(run.herdr.requests.map((r) => r.method)).toEqual(["pane.report_agent"]);

		const quitting = await startedInHerdr();
		await until(() => quitting.states().length === 1);
		await quitting.fire("session_shutdown", { type: "session_shutdown", reason: "quit" });
		await until(() => quitting.herdr.requests.length === 2);
		expect(quitting.herdr.requests[1]).toMatchObject({
			method: "pane.release_agent",
			params: { pane_id: "w1:p2", source: "pi-bolt", agent: "pi-bolt" },
		});
	});

	test("a Herdr that is not there does not hold anything up", async () => {
		const fake = fakePi();
		const env = { HERDR_ENV: "1", HERDR_SOCKET_PATH: join(tempDir(), "gone.sock"), HERDR_PANE_ID: "w1:p1" };
		createHerdrExtension(env)(fake.pi);
		const ctx = context({ sessionFile: "/work/s.jsonl" });
		await fake.fire("session_start", { type: "session_start", reason: "startup" }, ctx);
		const started = Date.now();
		await fake.fire("session_shutdown", { type: "session_shutdown", reason: "quit" });
		expect(Date.now() - started).toBeLessThan(400);
	});

	test("pi-herdr's own report of the pane is turned off in Herdr only", () => {
		const inHerdr: NodeJS.ProcessEnv = { HERDR_ENV: "1", HERDR_SOCKET_PATH: "/tmp/h.sock", HERDR_PANE_ID: "w1:p1" };
		quietOtherHerdrReporters(inHerdr);
		expect(inHerdr.PI_HERDR_NO_SELF_REPORT).toBe("1");
		const outside: NodeJS.ProcessEnv = {};
		quietOtherHerdrReporters(outside);
		expect(outside.PI_HERDR_NO_SELF_REPORT).toBeUndefined();
	});

	test("resume commands Herdr would refuse are left out", () => {
		expect(resumeArgv("/home/me/s.jsonl")).toEqual([COMMAND_NAME, "--session", "/home/me/s.jsonl"]);
		expect(resumeArgv(undefined)).toBeUndefined();
		expect(resumeArgv("relative.jsonl")).toBeUndefined();
		expect(resumeArgv("/home/o'brien/s.jsonl")).toBeUndefined();
		expect(resumeArgv("/home/me/a\nb.jsonl")).toBeUndefined();
		expect(resumeArgv("/home/me/a\u0085b.jsonl")).toBeUndefined();
		expect(resumeArgv(`/${"a".repeat(8200)}`)).toBeUndefined();
	});
});

const HERDR_PI_FILE = `// installed by herdr
// managed by herdr; reinstalling or updating the integration overwrites this file.
// add custom hooks/plugins beside this file instead of editing it.
// HERDR_INTEGRATION_ID=pi
// HERDR_INTEGRATION_VERSION=9
// @ts-nocheck
export default function (pi) {
	pi.registerCommand("herdr-file-loaded", { handler: async () => {} });
}
`;

describe("Herdr's own Pi integration file", () => {
	function extensionsDir() {
		const dir = join(tempDir(), "extensions");
		mkdirSync(dir);
		const herdrFile = join(dir, "herdr-agent-state.ts");
		writeFileSync(herdrFile, HERDR_PI_FILE);
		const other = join(dir, "other.ts");
		writeFileSync(
			other,
			`export default function (pi) { pi.registerCommand("other", { handler: async () => {} }); }\n`,
		);
		return { dir, herdrFile, other };
	}

	test("is recognized by its header", () => {
		const { dir, herdrFile, other } = extensionsDir();
		expect(isHerdrPiIntegration(herdrFile)).toBe(true);
		expect(isHerdrPiIntegration(other)).toBe(false);
		const lookalike = join(dir, "sub", "herdr-agent-state.ts");
		mkdirSync(join(dir, "sub"));
		writeFileSync(lookalike, "// my own herdr hook\nexport default function () {}\n");
		expect(isHerdrPiIntegration(lookalike)).toBe(false);
		expect(isHerdrPiIntegration(join(dir, "missing", "herdr-agent-state.ts"))).toBe(false);
	});

	async function loadedCommands(build: string | undefined, paths: string[], cwd: string) {
		vi.resetModules();
		(globalThis as { PIBOLT_BUILD?: string }).PIBOLT_BUILD = build;
		const { loadExtensions } = await import("../src/core/extensions/loader.ts");
		const result = await loadExtensions(paths, cwd);
		expect(result.errors).toEqual([]);
		return result.extensions.flatMap((extension) => [...extension.commands.keys()]);
	}

	test("Pi loads it as before", async () => {
		const { dir, herdrFile, other } = extensionsDir();
		expect(await loadedCommands(undefined, [herdrFile, other], dir)).toEqual(["herdr-file-loaded", "other"]);
	});

	test("Pi-Bolt leaves it out, and loads the rest", async () => {
		const { dir, herdrFile, other } = extensionsDir();
		expect(await loadedCommands("0.7.5 arm64 jit-off", [herdrFile, other], dir)).toEqual(["other"]);
	});

	test("only Pi-Bolt has the built-in Herdr extension", async () => {
		vi.resetModules();
		const pi = await import("../src/extensions/index.ts");
		expect(pi.builtInExtensions.map((e) => (typeof e === "function" ? "" : e.name))).not.toContain("herdr");
		vi.resetModules();
		(globalThis as { PIBOLT_BUILD?: string }).PIBOLT_BUILD = "0.7.5 arm64 jit-off";
		const bolt = await import("../src/extensions/index.ts");
		expect(bolt.builtInExtensions.map((e) => (typeof e === "function" ? "" : e.name))).toContain("herdr");
	});
});
