import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import net from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, test, vi } from "vitest";
import { COMMAND_NAME, PRODUCT_NAME } from "../src/config.ts";
import type { ExtensionAPI } from "../src/core/extensions/types.ts";
import {
	createHerdrExtension,
	quietOtherHerdrReporters,
	RESUME_RETRY_MS,
	resumeArgv,
} from "../src/extensions/herdr/index.ts";
import { isHerdrPiIntegration } from "../src/extensions/herdr/integration-file.ts";
import {
	executableTransport,
	type HerdrRequest,
	herdrAnswer,
	herdrArguments,
	herdrCommandError,
	socketTransport,
} from "../src/extensions/herdr/transport.ts";

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

type Answer = (request: Request) => Record<string, unknown> | undefined | Promise<Record<string, unknown> | undefined>;

/**
 * A Herdr socket that answers every request (by default, that it took it) and keeps what it got. An answer of undefined
 * closes the connection without one.
 */
async function fakeHerdr(
	answer: Answer = () => ({ result: {} }),
): Promise<{ socketPath: string; requests: Request[] }> {
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
				void Promise.resolve(answer(request)).then((reply) =>
					reply ? socket.write(`${JSON.stringify({ id: request.id, ...reply })}\n`) : socket.end(),
				);
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

async function startedInHerdr(
	options: { mode?: string; sessionFile?: string; answer?: Answer; piIntegration?: boolean; now?: () => number } = {},
) {
	const herdr = await fakeHerdr(options.answer);
	const env = { HERDR_ENV: "1", HERDR_SOCKET_PATH: herdr.socketPath, HERDR_PANE_ID: "w1:p2" };
	const fake = fakePi();
	let idle = true;
	createHerdrExtension(env, process.platform, () => options.piIntegration === true, options.now)(fake.pi);
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

	// #201: pi-subagents (nicobailon) reports its background runs with herdr:busy only.
	test("herdr:busy keeps the pane working, under a block", async () => {
		const run = await startedInHerdr();
		await until(() => run.states().length === 1);
		run.emit("herdr:busy", { active: true, label: "⏳ 1 subagent (worker)" });
		await until(() => run.states().length === 2);
		// A relabel is false then true at once: no idle report in between.
		run.emit("herdr:busy", { active: false });
		run.emit("herdr:busy", { active: true, label: "⏳ 2 subagents (worker, reviewer)" });
		await new Promise((resolve) => setTimeout(resolve, 50));
		expect(run.states()).toEqual(["idle", "working"]);

		run.emit("herdr:blocked", { active: true, label: "Question for you" });
		await until(() => run.states().length === 3);
		run.emit("herdr:blocked", { active: false });
		await until(() => run.states().length === 4);
		run.emit("herdr:busy", { active: false });
		await until(() => run.states().length === 5);
		expect(run.states()).toEqual(["idle", "working", "blocked", "working", "idle"]);
	});

	// #201: herdr:busy and subagents:* about the same runs count once; the pane is idle when both say done.
	test("herdr:busy and subagents events together", async () => {
		const run = await startedInHerdr();
		await until(() => run.states().length === 1);
		run.emit("subagents:started", { id: "a1" });
		await until(() => run.states().length === 2);
		run.emit("herdr:busy", { active: true, label: "⏳ 1 subagent" });
		await new Promise((resolve) => setTimeout(resolve, 20));
		run.emit("herdr:busy", { active: false });
		await new Promise((resolve) => setTimeout(resolve, 20));
		expect(run.states()).toEqual(["idle", "working"]);
		run.emit("subagents:completed", { id: "a1" });
		await until(() => run.states().length === 3);

		run.emit("herdr:busy", { active: true, label: "⏳ 1 subagent" });
		await until(() => run.states().length === 4);
		run.emit("subagents:started", { id: "a2" });
		run.emit("subagents:completed", { id: "a2" });
		await new Promise((resolve) => setTimeout(resolve, 20));
		expect(run.states()).toEqual(["idle", "working", "idle", "working"]);
		run.emit("herdr:busy", { active: false });
		await until(() => run.states().length === 5);
		expect(run.states()).toEqual(["idle", "working", "idle", "working", "idle"]);
	});

	test("print, JSON and RPC runs and subagent sessions do not report", async () => {
		const run = await startedInHerdr({ mode: "print" });
		await run.fire("agent_start", { type: "agent_start" }, run.ctx);
		await new Promise((resolve) => setTimeout(resolve, 50));
		expect(run.herdr.requests).toEqual([]);
	});

	test("--herdr-pi reports as the official Pi integration and keeps the Pi-Bolt name", async () => {
		const run = await startedInHerdr({ piIntegration: true });
		await until(() => run.herdr.requests.length === 3);
		expect(run.herdr.requests[0].params).toMatchObject({
			pane_id: "w1:p2",
			source: "herdr:pi",
			agent: "pi",
			agent_session_path: "/work/s.jsonl",
			session_start_source: "startup",
			resume_argv: [COMMAND_NAME, "--herdr-pi", "--session", "/work/s.jsonl"],
		});
		expect(run.herdr.requests[1].params).toMatchObject({
			pane_id: "w1:p2",
			source: "herdr:pi",
			agent: "pi",
			state: "idle",
			agent_session_path: "/work/s.jsonl",
			resume_argv: [COMMAND_NAME, "--herdr-pi", "--session", "/work/s.jsonl"],
		});
		expect(run.herdr.requests[2]).toMatchObject({
			method: "pane.report_metadata",
			params: {
				pane_id: "w1:p2",
				source: "pi-bolt",
				agent: "pi",
				applies_to_source: "herdr:pi",
				display_agent: PRODUCT_NAME,
			},
		});
	});

	// PR #157 review: a refused session report left the state report queued behind it, so no state ever reached Herdr.
	test("--herdr-pi still reports the state when Herdr refuses the session report", async () => {
		const refused = { error: { code: "method_not_found", message: "unknown method" } };
		const run = await startedInHerdr({
			piIntegration: true,
			answer: (request) => (request.method === "pane.report_agent_session" ? refused : { result: {} }),
		});
		await until(() => run.states().length === 1);
		run.setIdle(false);
		await run.fire("agent_start", { type: "agent_start" }, run.ctx);
		await until(() => run.states().length === 2);
		expect(run.states()).toEqual(["idle", "working"]);
		// The session report goes first each time, two tries each, until Herdr takes one.
		const methods = run.herdr.requests.map((r) => r.method);
		expect(methods.slice(0, 3)).toEqual([
			"pane.report_agent_session",
			"pane.report_agent_session",
			"pane.report_agent",
		]);
		expect(methods.filter((m) => m === "pane.report_agent_session").length).toBeGreaterThanOrEqual(4);
	});

	test("--herdr-pi releases the official Pi agent", async () => {
		const run = await startedInHerdr({ piIntegration: true });
		await until(() => run.herdr.requests.length === 3);
		await run.fire("session_shutdown", { type: "session_shutdown", reason: "quit" });
		await until(() => run.herdr.requests.length === 4);
		expect(run.herdr.requests[3]).toMatchObject({
			method: "pane.release_agent",
			params: { pane_id: "w1:p2", source: "herdr:pi", agent: "pi" },
		});
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

	// #200: a report Herdr refused counted as delivered, so it was neither tried a second time nor sent again.
	test("a report Herdr refuses is tried twice, and sent again on the next publish", async () => {
		let refuse = true;
		const run = await startedInHerdr({
			answer: () => (refuse ? { error: { code: "internal_error", message: "report rejected" } } : { result: {} }),
		});
		await until(() => run.states().length === 2);
		// Each try has its own seq, and nothing is retried until something publishes again.
		await new Promise((resolve) => setTimeout(resolve, 50));
		expect(run.states()).toEqual(["idle", "idle"]);
		const [first, second] = run.herdr.requests.map((r) => r.params.seq as number);
		expect(second).toBeGreaterThan(first);

		refuse = false;
		// A dialog at the prompt leaves the state as it was; the state Herdr refused is sent again.
		await run.fire("ui_prompt_start", { type: "ui_prompt_start", reason: "ui_prompt", kind: "select" });
		await until(() => run.states().length === 3);
		// Once Herdr took it, the same state is not sent again.
		await run.fire("ui_prompt_end", { type: "ui_prompt_end", reason: "ui_prompt", kind: "select" });
		await new Promise((resolve) => setTimeout(resolve, 50));
		expect(run.states()).toEqual(["idle", "idle", "idle"]);
	});

	// #200 (PR #214 review): a report refused after the release on quit was tried again with a seq above the release's,
	// which took the pane back.
	test("nothing is reported after the release on quit", async () => {
		let releaseArrived: (() => void) | undefined;
		const refused = { error: { code: "resume_not_accepted", message: "report rejected" } };
		const run = await startedInHerdr({
			// The first report is refused only once the release is in.
			answer: (request) => {
				if (request.method === "pane.release_agent") {
					setTimeout(() => releaseArrived?.(), 0);
					return { result: {} };
				}
				return new Promise((resolve) => {
					releaseArrived = () => resolve(refused);
				});
			},
		});
		await until(() => run.herdr.requests.length === 1);
		await run.fire("session_shutdown", { type: "session_shutdown", reason: "quit" });
		await new Promise((resolve) => setTimeout(resolve, 100));
		expect(run.herdr.requests.map((r) => r.method)).toEqual(["pane.report_agent", "pane.release_agent"]);
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
		expect(resumeArgv("/home/me/s.jsonl", true)).toEqual([
			COMMAND_NAME,
			"--herdr-pi",
			"--session",
			"/home/me/s.jsonl",
		]);
		expect(resumeArgv(undefined)).toBeUndefined();
		expect(resumeArgv("relative.jsonl")).toBeUndefined();
		expect(resumeArgv("/home/o'brien/s.jsonl")).toBeUndefined();
		expect(resumeArgv("/home/me/a\nb.jsonl")).toBeUndefined();
		expect(resumeArgv("/home/me/a\u0085b.jsonl")).toBeUndefined();
		expect(resumeArgv(`/${"a".repeat(8200)}`)).toBeUndefined();
	});
});

type Owner = { source: string; agent: string };

/**
 * #330: the part of Herdr's pane authority (src/terminal/state.rs and src/app/api/panes.rs, 0.9.2 to 0.9.5) that the
 * reporter depends on:
 * - A report's state or session is applied (or ignored) first; its resume command is checked after that and recorded
 *   only for the source that then holds the pane, else the answer is resume_not_accepted.
 * - Herdr keeps a session only for its official integrations (`herdr:*` of a known agent such as pi or cursor).
 * - While another source's session is on the pane, a report does not take it, unless the agent Herdr sees in the
 *   foreground is the reporter's (`foreground`) and the report carries a session of its own; for a session report, also a
 *   session_start_source. Any report is still answered ok.
 * - release_agent clears what belongs to the releasing source and keeps another source's session.
 */
function herdrPane(options: { session?: Owner & { value: string }; foreground?: string } = {}) {
	const pane: { holder?: Owner; state?: unknown; session?: Owner & { value: string }; resume?: unknown } = {};
	if (options.session) pane.session = options.session;
	const rejected: Request[] = [];
	const answer = (request: Request): Record<string, unknown> => {
		const { source, agent, state, resume_argv, agent_session_path, agent_session_id, session_start_source } =
			request.params as Record<string, string | undefined> & { resume_argv?: string[] };
		const owner = { source: source as string, agent: agent as string };
		const same = (other?: Owner) => other?.source === owner.source && other.agent === owner.agent;
		if (request.method === "pane.release_agent") {
			if (same(pane.holder)) pane.holder = pane.state = pane.resume = undefined;
			if (same(pane.session)) pane.session = undefined;
			return { result: {} };
		}
		const official = owner.source.startsWith("herdr:") && ["pi", "cursor"].includes(owner.agent);
		const value = official ? (agent_session_path ?? agent_session_id) : undefined;
		const conflict = pane.session !== undefined && !same(pane.session);
		const takeover =
			conflict &&
			value !== undefined &&
			options.foreground === owner.agent &&
			(request.method === "pane.report_agent" || session_start_source !== undefined);
		if (!conflict || takeover) {
			if (value !== undefined) pane.session = { ...owner, value };
			if (request.method === "pane.report_agent") {
				if (!same(pane.holder)) pane.resume = undefined;
				pane.holder = owner;
				pane.state = state;
			} else if (takeover) {
				pane.holder = pane.state = pane.resume = undefined;
			}
		}
		if (!resume_argv) return { result: {} };
		if (!(same(pane.holder) || (!pane.holder && options.foreground === owner.agent))) {
			rejected.push(request);
			return {
				error: {
					code: "resume_not_accepted",
					message:
						"resume_argv requires the reporter to hold the pane; report its state with pane.report_agent first",
				},
			};
		}
		pane.resume = resume_argv;
		return { result: {} };
	};
	return { pane, rejected, answer };
}

const CURSOR_SESSION = { source: "herdr:cursor", agent: "cursor", value: "probe-sess" };
const settle = () => new Promise((resolve) => setTimeout(resolve, 50));
const carriesResume = (requests: Request[]) => requests.map((r) => "resume_argv" in r.params);

/** One turn of the session in `ctx`: working, then idle, each reported (a refused report and its resend count once). */
async function turn(run: Awaited<ReturnType<typeof startedInHerdr>>, ctx: unknown = run.ctx) {
	const reported = (state: string) => {
		const states = run.states();
		return () => run.states().length > states.length && run.states().at(-1) === state;
	};
	const working = reported("working");
	run.setIdle(false);
	await run.fire("agent_start", { type: "agent_start" }, ctx);
	await until(working);
	const idle = reported("idle");
	run.setIdle(true);
	await run.fire("agent_settled", { type: "agent_settled", aborted: false }, ctx);
	await until(idle);
	// A refused report's resend follows its refusal.
	await new Promise((resolve) => setTimeout(resolve, 10));
}

describe("Herdr pane with another agent's session", () => {
	// #330: Herdr refused every report for its resume command, and each one was sent again: the reporter retried the
	// same refused request after every state change, for as long as Pi-Bolt ran.
	test("one refusal, then state reports without the command until the retry window ends", async () => {
		const herdr = herdrPane({ session: CURSOR_SESSION });
		const clock = { now: 0 };
		const run = await startedInHerdr({ answer: herdr.answer, now: () => clock.now });
		await until(() => run.herdr.requests.length === 2);
		await settle();
		// The refused report, then the same report without the command, as Herdr's answer asks.
		expect(carriesResume(run.herdr.requests)).toEqual([true, false]);
		for (let i = 0; i < 5; i++) {
			clock.now += 60_000;
			await turn(run);
		}
		await settle();
		expect(run.herdr.requests.length).toBe(12);
		expect(carriesResume(run.herdr.requests.slice(2))).toEqual(Array(10).fill(false));
		expect(herdr.rejected.length).toBe(1);
		// Herdr keeps the other agent's session: a source it has no session for cannot take the pane from it.
		expect(herdr.pane).toEqual({ session: CURSOR_SESSION });

		// Another session file is not held back by this session's refusal.
		await run.fire("session_shutdown", { type: "session_shutdown", reason: "new" });
		const next = context({ idle: () => run.ctx.isIdle(), sessionFile: "/work/t.jsonl" });
		await run.fire("session_start", { type: "session_start", reason: "new" }, next);
		await until(() => run.herdr.requests.length === 14);
		await turn(run, next);
		await settle();
		expect(carriesResume(run.herdr.requests.slice(12))).toEqual([true, false, false, false]);
		expect(herdr.rejected.length).toBe(2);
	});

	// #330: a fresh pane is held with the command from the first report, as before.
	test("a fresh pane takes every report with the command", async () => {
		const herdr = herdrPane();
		const run = await startedInHerdr({ answer: herdr.answer });
		await until(() => herdr.pane.state === "idle");
		await turn(run);
		await settle();
		expect(carriesResume(run.herdr.requests)).toEqual([true, true, true]);
		expect(herdr.rejected).toEqual([]);
		expect(herdr.pane).toEqual({
			holder: { source: "pi-bolt", agent: "pi-bolt" },
			state: "idle",
			resume: [COMMAND_NAME, "--session", "/work/s.jsonl"],
		});

		// A session switch moves the command to the new session; nothing is released.
		await run.fire("session_shutdown", { type: "session_shutdown", reason: "resume" });
		await run.fire(
			"session_start",
			{ type: "session_start", reason: "resume" },
			context({ sessionFile: "/work/t.jsonl" }),
		);
		await until(() => run.herdr.requests.length === 4);
		await settle();
		expect(herdr.pane.resume).toEqual([COMMAND_NAME, "--session", "/work/t.jsonl"]);
		expect(run.herdr.requests.map((r) => r.method)).not.toContain("pane.release_agent");

		await run.fire("session_shutdown", { type: "session_shutdown", reason: "quit" });
		expect(run.herdr.requests[4].method).toBe("pane.release_agent");
		expect(herdr.pane).toEqual({});
	});

	// #330 (review): once the other session is gone, state reports take the pane, and the same session's command
	// follows with the first state report after the retry window.
	test("a pane that becomes free gets the command within the retry window", async () => {
		const herdr = herdrPane({ session: CURSOR_SESSION });
		const clock = { now: 0 };
		const run = await startedInHerdr({ answer: herdr.answer, now: () => clock.now });
		await until(() => run.herdr.requests.length === 2);
		herdr.answer({ id: "x", method: "pane.release_agent", params: { ...CURSOR_SESSION, pane_id: "w1:p2" } });
		clock.now = RESUME_RETRY_MS - 1;
		await turn(run);
		expect(herdr.pane).toEqual({ holder: { source: "pi-bolt", agent: "pi-bolt" }, state: "idle" });
		clock.now = RESUME_RETRY_MS;
		await turn(run);
		await settle();
		expect(carriesResume(run.herdr.requests.slice(2))).toEqual([false, false, true, true]);
		expect(herdr.pane.resume).toEqual([COMMAND_NAME, "--session", "/work/s.jsonl"]);
		expect(herdr.rejected.length).toBe(1);
	});

	// #330 (review): a pane that stays with the other agent costs one refused request per window, not one per report.
	test("refusals are bounded by the retry window", async () => {
		const herdr = herdrPane({ session: CURSOR_SESSION });
		const clock = { now: 0 };
		const run = await startedInHerdr({ answer: herdr.answer, now: () => clock.now });
		await until(() => run.herdr.requests.length === 2);
		// Two hours, a turn every two minutes: 60 turns, 120 state reports.
		for (let i = 0; i < 60; i++) {
			clock.now += 2 * 60_000;
			await turn(run);
		}
		await settle();
		// The first refusal, then one per ten minutes.
		expect(herdr.rejected.length).toBe(13);
		expect(run.herdr.requests.length).toBe(2 + 120 + 12);
	});

	// #330 (review): a resend without an answer cleared `last`, and the next report took that as a session change and
	// offered the refused command again at once.
	test("a report without an answer does not reset the retry window", async () => {
		const herdr = herdrPane({ session: CURSOR_SESSION });
		const clock = { now: 0 };
		let silent = true;
		const run = await startedInHerdr({
			now: () => clock.now,
			// The plain resend gets no answer (both tries); everything else is answered as Herdr would.
			answer: (request) => (silent && !("resume_argv" in request.params) ? undefined : herdr.answer(request)),
		});
		await until(() => run.herdr.requests.length === 3);
		await settle();
		expect(carriesResume(run.herdr.requests)).toEqual([true, false, false]);
		silent = false;
		clock.now = RESUME_RETRY_MS - 1;
		await turn(run);
		await settle();
		expect(carriesResume(run.herdr.requests.slice(3))).toEqual([false, false]);
		expect(herdr.rejected.length).toBe(1);
	});

	// #330: as Herdr's Pi integration, with `pi` in the foreground, Pi-Bolt takes the pane as stock pi does.
	test("--herdr-pi takes it with the session report", async () => {
		const herdr = herdrPane({ session: CURSOR_SESSION, foreground: "pi" });
		const run = await startedInHerdr({ piIntegration: true, answer: herdr.answer });
		await until(() => run.herdr.requests.length === 3);
		await settle();
		expect(run.herdr.requests.map((r) => r.method)).toEqual([
			"pane.report_agent_session",
			"pane.report_agent",
			"pane.report_metadata",
		]);
		expect(herdr.pane).toEqual({
			holder: { source: "herdr:pi", agent: "pi" },
			state: "idle",
			session: { source: "herdr:pi", agent: "pi", value: "/work/s.jsonl" },
			resume: [COMMAND_NAME, "--herdr-pi", "--session", "/work/s.jsonl"],
		});
		expect(herdr.rejected).toEqual([]);
	});

	// #330: a session report whose command Herdr refuses goes again without it; the state report still carries it.
	test("--herdr-pi keeps the command on the state report when the session report's is refused", async () => {
		const herdr = herdrPane({ foreground: "cursor" });
		const run = await startedInHerdr({
			piIntegration: true,
			answer: (request) =>
				request.method === "pane.report_agent_session" && "resume_argv" in request.params
					? {
							error: {
								code: "resume_not_accepted",
								message: "resume_argv requires the reporter to hold the pane",
							},
						}
					: herdr.answer(request),
		});
		await until(() => run.herdr.requests.length === 4);
		await settle();
		expect(run.herdr.requests.map((r) => [r.method, "resume_argv" in r.params])).toEqual([
			["pane.report_agent_session", true],
			["pane.report_agent_session", false],
			["pane.report_agent", true],
			["pane.report_metadata", false],
		]);
		expect(herdr.pane.resume).toEqual([COMMAND_NAME, "--herdr-pi", "--session", "/work/s.jsonl"]);
	});
});

describe("Herdr socket", () => {
	/** A socket that answers the first request line with `reply(id)`, as raw chunks, then closes. */
	async function answering(reply: (id: string) => string[]): Promise<string> {
		const socketPath = join(tempDir(), "herdr.sock");
		const server = net.createServer((socket) => {
			let buffered = "";
			socket.on("data", async (chunk) => {
				buffered += chunk.toString();
				const newline = buffered.indexOf("\n");
				if (newline < 0) return;
				const { id } = JSON.parse(buffered.slice(0, newline)) as Request;
				for (const part of reply(id)) {
					socket.write(part);
					await new Promise((resolve) => setTimeout(resolve, 5));
				}
				socket.end();
			});
		});
		await new Promise<void>((resolve) => server.listen(socketPath, resolve));
		cleanups.push(() => new Promise<void>((resolve) => server.close(() => resolve())));
		return socketPath;
	}

	const report: HerdrRequest = {
		method: "pane.report_agent",
		params: { pane_id: "w1:p1", source: "pi-bolt", agent: "pi-bolt", state: "idle", seq: 1 },
	};

	// #200: an error answer counted as success.
	test("an error answer is not success", async () => {
		const endpoint = await answering((id) => [
			`${JSON.stringify({ id, error: { code: "resume_not_accepted", message: "report rejected" } })}\n`,
		]);
		expect(await socketTransport(endpoint)(report, 500)).toEqual({ taken: false, code: "resume_not_accepted" });
	});

	// #200: any data counted as success, before the answer was complete or even about this request.
	test("only the complete answer with this request's id counts", async () => {
		const taken = await answering((id) => {
			const answer = JSON.stringify({ id, result: {} });
			return [
				`${JSON.stringify({ id: "other", error: { code: "x" } })}\n`,
				answer.slice(0, 5),
				`${answer.slice(5)}\n`,
			];
		});
		expect(await socketTransport(taken)(report, 500)).toEqual({ taken: true });
		const cut = await answering((id) => [JSON.stringify({ id, result: {} }).slice(0, 10)]);
		expect(await socketTransport(cut)(report, 500)).toEqual({ taken: false });
		const unterminated = await answering((id) => [JSON.stringify({ id, result: {} })]);
		expect(await socketTransport(unterminated)(report, 500)).toEqual({ taken: true });
	});

	test("answer lines", () => {
		expect(herdrAnswer("a", JSON.stringify({ id: "a", result: {} }))).toEqual({ taken: true });
		expect(herdrAnswer("a", JSON.stringify({ id: "a", result: null }))).toEqual({ taken: true });
		expect(herdrAnswer("a", JSON.stringify({ id: "a", error: { code: "x" } }))).toEqual({ taken: false, code: "x" });
		expect(herdrAnswer("a", JSON.stringify({ id: "a", error: { code: 1 } }))).toEqual({ taken: false });
		expect(herdrAnswer("a", JSON.stringify({ id: "a" }))).toEqual({ taken: false });
		expect(herdrAnswer("a", JSON.stringify({ id: "b", result: {} }))).toBeUndefined();
		expect(herdrAnswer("a", "not json")).toBeUndefined();
		expect(herdrAnswer("a", "null")).toBeUndefined();
	});
});

describe("Herdr executable (Windows)", () => {
	test("requests become `herdr pane` arguments", () => {
		expect(
			herdrArguments({
				method: "pane.report_agent",
				params: {
					pane_id: "w1:p2",
					source: "pi-bolt",
					agent: "pi-bolt",
					state: "blocked",
					message: "-y to continue?",
					seq: 7,
					agent_session_path: "C:\\Users\\me\\s.jsonl",
					resume_argv: ["pi-bolt", "--session", "C:\\Users\\me\\s.jsonl"],
				},
			}),
		).toEqual([
			"pane",
			"report-agent",
			"w1:p2",
			"--source",
			"pi-bolt",
			"--agent",
			"pi-bolt",
			"--seq",
			"7",
			"--state",
			"blocked",
			"--message",
			"-y to continue?",
			"--agent-session-path",
			"C:\\Users\\me\\s.jsonl",
			"--",
			"pi-bolt",
			"--session",
			"C:\\Users\\me\\s.jsonl",
		]);
		expect(
			herdrArguments({
				method: "pane.release_agent",
				params: { pane_id: "w1:p2", source: "pi-bolt", agent: "pi-bolt", seq: 8 },
			}),
		).toEqual(["pane", "release-agent", "w1:p2", "--source", "pi-bolt", "--agent", "pi-bolt", "--seq", "8"]);
		expect(
			herdrArguments({
				method: "pane.report_agent_session",
				params: {
					pane_id: "w1:p2",
					source: "herdr:pi",
					agent: "pi",
					seq: 9,
					agent_session_path: "/work/s.jsonl",
					session_start_source: "startup",
					resume_argv: ["pi-bolt", "--herdr-pi", "--session", "/work/s.jsonl"],
				},
			}),
		).toEqual([
			"pane",
			"report-agent-session",
			"w1:p2",
			"--source",
			"herdr:pi",
			"--agent",
			"pi",
			"--seq",
			"9",
			"--agent-session-path",
			"/work/s.jsonl",
			"--session-start-source",
			"startup",
			"--",
			"pi-bolt",
			"--herdr-pi",
			"--session",
			"/work/s.jsonl",
		]);
		expect(
			herdrArguments({
				method: "pane.report_metadata",
				params: {
					pane_id: "w1:p2",
					source: "pi-bolt",
					agent: "pi",
					applies_to_source: "herdr:pi",
					display_agent: PRODUCT_NAME,
					seq: 9,
				},
			}),
		).toEqual([
			"pane",
			"report-metadata",
			"w1:p2",
			"--source",
			"pi-bolt",
			"--agent",
			"pi",
			"--applies-to-source",
			"herdr:pi",
			"--display-agent",
			PRODUCT_NAME,
			"--seq",
			"9",
		]);
	});

	test.skipIf(process.platform === "win32")("on Windows, reports run HERDR_BIN_PATH", async () => {
		const dir = tempDir();
		const log = join(dir, "calls");
		const bin = join(dir, "herdr");
		// One line per call: its arguments, separated by |.
		writeFileSync(bin, `#!/bin/sh\nIFS='|'; printf '%s\\n' "$*" >> '${log}'\n`);
		chmodSync(bin, 0o755);
		const fake = fakePi();
		const env = { HERDR_ENV: "1", HERDR_BIN_PATH: bin, HERDR_PANE_ID: "w1:p2" };
		createHerdrExtension(env, "win32")(fake.pi);
		await fake.fire(
			"session_start",
			{ type: "session_start", reason: "startup" },
			context({ sessionFile: "/w/s.jsonl" }),
		);
		const calls = () => (existsSync(log) ? readFileSync(log, "utf8").trim().split("\n") : []);
		await until(() => calls().length === 1);
		await fake.fire("session_shutdown", { type: "session_shutdown", reason: "quit" });
		await until(() => calls().length === 2);
		expect(calls()[0]).toMatch(
			new RegExp(
				`^pane\\|report-agent\\|w1:p2\\|--source\\|pi-bolt\\|--agent\\|pi-bolt\\|--seq\\|\\d+\\|--state\\|idle\\|--agent-session-path\\|/w/s.jsonl\\|--\\|${COMMAND_NAME}\\|--session\\|/w/s.jsonl$`,
			),
		);
		expect(calls()[1]).toMatch(/^pane\|release-agent\|w1:p2\|--source\|pi-bolt\|--agent\|pi-bolt\|--seq\|\d+$/);
	});

	// #330: the executable prints Herdr's error answer on stderr and exits 1, as `herdr pane report-agent` does.
	test.skipIf(process.platform === "win32")("a resume command the executable refuses is not sent again", async () => {
		const dir = tempDir();
		const log = join(dir, "calls");
		const bin = join(dir, "herdr");
		const refusal = JSON.stringify({
			id: "cli:request",
			error: { code: "resume_not_accepted", message: "resume_argv requires the reporter to hold the pane" },
		});
		// Refuses every resume command (after `--`), as Herdr does while another source's session is on the pane.
		writeFileSync(
			bin,
			[
				"#!/bin/sh",
				`IFS='|'; printf '%s\\n' "$*" >> '${log}'`,
				`case "$*" in *'|--|'*) echo '${refusal}' >&2; exit 1 ;; esac`,
				"",
			].join("\n"),
		);
		chmodSync(bin, 0o755);
		const fake = fakePi();
		const env = { HERDR_ENV: "1", HERDR_BIN_PATH: bin, HERDR_PANE_ID: "w1:p2" };
		createHerdrExtension(env, "win32")(fake.pi);
		let idle = true;
		const ctx = context({ idle: () => idle, sessionFile: "/w/s.jsonl" });
		await fake.fire("session_start", { type: "session_start", reason: "startup" }, ctx);
		const calls = () => (existsSync(log) ? readFileSync(log, "utf8").trim().split("\n") : []);
		await until(() => calls().length === 2);
		idle = false;
		await fake.fire("agent_start", { type: "agent_start" }, ctx);
		await until(() => calls().length === 3);
		await new Promise((resolve) => setTimeout(resolve, 100));
		expect(calls().map((call) => call.includes("|--|"))).toEqual([true, false, false]);
	});

	// #330 (review): the answer waited for stderr to close, so a process the command left running with stderr open held
	// every report until its timeout, which then counted as no answer.
	test.skipIf(process.platform === "win32")("a process holding stderr open does not hold up the answer", async () => {
		const dir = tempDir();
		const refusal = JSON.stringify({ id: "cli:request", error: { code: "resume_not_accepted", message: "m" } });
		const script = (exit: number, line: string) => {
			const bin = join(dir, `herdr-${exit}`);
			writeFileSync(bin, `#!/bin/sh\nsleep 3 &\n${line}\nexit ${exit}\n`);
			chmodSync(bin, 0o755);
			return bin;
		};
		const report: HerdrRequest = {
			method: "pane.report_agent",
			params: { pane_id: "w1:p1", source: "pi-bolt", agent: "pi-bolt", state: "idle", seq: 1 },
		};
		for (const [bin, expected] of [
			[script(1, `echo '${refusal}' >&2`), { taken: false, code: "resume_not_accepted" }],
			[script(0, ":"), { taken: true }],
		] as const) {
			const started = Date.now();
			expect(await executableTransport(bin)(report, 2500)).toEqual(expected);
			expect(Date.now() - started).toBeLessThan(1000);
		}
	});

	test("the error code of a failed command", () => {
		const answer = JSON.stringify({ id: "cli:request", error: { code: "resume_not_accepted", message: "m" } });
		expect(herdrCommandError(`${answer}\n`)).toEqual({ taken: false, code: "resume_not_accepted" });
		expect(herdrCommandError(`warning\n${answer}`)).toEqual({ taken: false, code: "resume_not_accepted" });
		expect(herdrCommandError("")).toEqual({ taken: false });
		expect(herdrCommandError("error: unexpected argument")).toEqual({ taken: false });
		// #330 (review): a line after the answer hid its code, and the refused command was sent again.
		const refused = { taken: false, code: "resume_not_accepted" };
		expect(herdrCommandError(`${answer}\nwarning: something else\n`)).toEqual(refused);
		expect(herdrCommandError(`${answer}\r\nlog line\r\n`)).toEqual(refused);
		expect(herdrCommandError(`Error: ${answer}`)).toEqual(refused);
		expect(herdrCommandError(`${answer}\n${JSON.stringify({ id: "x", result: {} })}\n{not json}`)).toEqual(refused);
		const other = JSON.stringify({ id: "cli:request", error: { code: "pane_not_found", message: "m" } });
		expect(herdrCommandError(`${answer}\n${other}`)).toEqual({ taken: false, code: "pane_not_found" });
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

	test("only Pi-Bolt has the built-in Herdr extension, and only in a Herdr pane", async () => {
		const names = (list: { builtInExtensions: unknown[] }) =>
			list.builtInExtensions.map((e) => (typeof e === "function" ? "" : (e as { name: string }).name));
		vi.stubEnv("HERDR_ENV", "1");
		vi.stubEnv("HERDR_PANE_ID", "p1");
		vi.stubEnv("HERDR_SOCKET_PATH", "/tmp/herdr.sock");
		vi.resetModules();
		expect(names(await import("../src/extensions/index.ts"))).not.toContain("herdr");
		vi.resetModules();
		(globalThis as { PIBOLT_BUILD?: string }).PIBOLT_BUILD = "0.7.5 arm64 jit-off";
		expect(names(await import("../src/extensions/index.ts"))).toContain("herdr");
		vi.unstubAllEnvs();
		vi.stubEnv("HERDR_ENV", undefined);
		vi.resetModules();
		expect(names(await import("../src/extensions/index.ts"))).not.toContain("herdr");
		vi.unstubAllEnvs();
	});
});
