/**
 * How Pi-Bolt's reports reach Herdr: through Herdr's socket, or by running the `herdr` executable that owns the pane
 * (HERDR_BIN_PATH). Herdr's "Add Herdr support to your agent" names the executable as the portable way, Windows included;
 * the socket costs no process per report. Pi-Bolt uses the executable on Windows and the socket elsewhere.
 */

import { spawn } from "node:child_process";
import type { Socket } from "node:net";

export type HerdrRequest =
	| {
			method: "pane.report_agent";
			params: {
				pane_id: string;
				source: string;
				agent: string;
				state: "working" | "blocked" | "idle";
				message?: string;
				seq: number;
				agent_session_path?: string;
				agent_session_id?: string;
				resume_argv?: string[];
			};
	  }
	| {
			method: "pane.report_agent_session";
			params: {
				pane_id: string;
				source: string;
				agent: string;
				seq: number;
				agent_session_path?: string;
				agent_session_id?: string;
				session_start_source?: string;
				resume_argv?: string[];
			};
	  }
	| {
			method: "pane.report_metadata";
			params: {
				pane_id: string;
				source: string;
				agent?: string;
				applies_to_source?: string;
				display_agent?: string;
				seq?: number;
			};
	  }
	| { method: "pane.release_agent"; params: { pane_id: string; source: string; agent: string; seq: number } };

/**
 * Send one request; true when Herdr took it. Never throws; waits at most `timeoutMs`, also when Pi-Bolt is quitting (the
 * release on quit must get through), and never longer.
 */
export type HerdrTransport = (request: HerdrRequest, timeoutMs: number) => Promise<boolean>;

export interface HerdrEnvironment {
	paneId: string;
	transport: HerdrTransport;
}

/** Where to reach the Herdr of this pane, or undefined outside Herdr. */
export function herdrFromEnvironment(
	env: NodeJS.ProcessEnv = process.env,
	platform: NodeJS.Platform = process.platform,
): HerdrEnvironment | undefined {
	const paneId = env.HERDR_PANE_ID;
	if (env.HERDR_ENV !== "1" || !paneId) return undefined;
	const bin = env.HERDR_BIN_PATH;
	if (platform === "win32" && bin) return { paneId, transport: executableTransport(bin) };
	const socketPath = env.HERDR_SOCKET_PATH;
	if (!socketPath) return undefined;
	// On Windows Herdr's socket is a named pipe of that name.
	const endpoint = platform === "win32" ? `\\\\.\\pipe\\${socketPath}` : socketPath;
	return { paneId, transport: socketTransport(endpoint) };
}

let requestCount = 0;

/** Longest unanswered input read before giving up; Herdr's answers are a few hundred bytes. */
const MAX_ANSWER_LENGTH = 64 * 1024;

/**
 * What one line from Herdr's socket says about request `id`: true for `{"id", "result"}`, false for `{"id", "error"}` (or
 * any other line with that id), undefined for a line that is not about this request.
 */
export function herdrAnswer(id: string, line: string): boolean | undefined {
	let answer: unknown;
	try {
		answer = JSON.parse(line);
	} catch {
		return undefined;
	}
	if (typeof answer !== "object" || answer === null || (answer as { id?: unknown }).id !== id) return undefined;
	const { result, error } = answer as { result?: unknown; error?: unknown };
	return error == null && result !== undefined;
}

export function socketTransport(endpoint: string): HerdrTransport {
	return (request, timeoutMs) =>
		new Promise((resolve) => {
			let done = false;
			let socket: Socket | undefined;
			const timeout = setTimeout(() => finish(false), timeoutMs);
			timeout.unref?.();
			function finish(taken: boolean) {
				if (done) return;
				done = true;
				clearTimeout(timeout);
				socket?.destroy();
				resolve(taken);
			}
			try {
				// node:net loads on first report: only a Herdr pane needs it.
				socket = process.getBuiltinModule("node:net").createConnection(endpoint);
			} catch {
				finish(false);
				return;
			}
			const id = `pi-bolt:${process.pid}:${++requestCount}`;
			const line = JSON.stringify({ id, ...request });
			// The answer is one line with this id: Herdr took the request only when it carries a result, not an error.
			let buffered = "";
			socket.setEncoding("utf8");
			socket.on("error", () => finish(false));
			socket.on("connect", () => socket?.write(`${line}\n`));
			socket.on("data", (chunk: string) => {
				buffered += chunk;
				for (let newline = buffered.indexOf("\n"); newline >= 0; newline = buffered.indexOf("\n")) {
					const answer = herdrAnswer(id, buffered.slice(0, newline));
					buffered = buffered.slice(newline + 1);
					if (answer !== undefined) {
						finish(answer);
						return;
					}
				}
				if (buffered.length > MAX_ANSWER_LENGTH) finish(false);
			});
			socket.on("end", () => finish(herdrAnswer(id, buffered) ?? false));
		});
}

export function executableTransport(bin: string): HerdrTransport {
	return (request, timeoutMs) =>
		new Promise((resolve) => {
			let done = false;
			let child: ReturnType<typeof spawn> | undefined;
			const finish = (taken: boolean) => {
				if (done) return;
				done = true;
				clearTimeout(timeout);
				resolve(taken);
			};
			const timeout = setTimeout(() => {
				child?.kill();
				finish(false);
			}, timeoutMs);
			timeout.unref?.();
			try {
				child = spawn(bin, herdrArguments(request), { stdio: "ignore", windowsHide: true });
			} catch {
				finish(false);
				return;
			}
			child.on("error", () => finish(false));
			child.on("exit", (code) => finish(code === 0));
		});
}

/**
 * The `herdr pane report-agent` / `pane release-agent` arguments of a request, in the form Herdr's guide uses: the pane
 * first, then `--name value` pairs (release-agent takes no other form). Each value is the argument after its option, so a
 * message that starts with `-` stays a value.
 */
export function herdrArguments(request: HerdrRequest): string[] {
	const { pane_id, source } = request.params;
	if (request.method === "pane.report_metadata") {
		const { agent, applies_to_source, display_agent, seq } = request.params;
		return [
			"pane",
			"report-metadata",
			pane_id,
			"--source",
			source,
			...(agent ? ["--agent", agent] : []),
			...(applies_to_source ? ["--applies-to-source", applies_to_source] : []),
			...(display_agent ? ["--display-agent", display_agent] : []),
			...(seq !== undefined ? ["--seq", String(seq)] : []),
		];
	}
	const { agent, seq } = request.params;
	const common = ["--source", source, "--agent", agent, "--seq", String(seq)];
	if (request.method === "pane.release_agent") return ["pane", "release-agent", pane_id, ...common];
	const { agent_session_path, agent_session_id, resume_argv } = request.params;
	if (request.method === "pane.report_agent_session") {
		const { session_start_source } = request.params;
		return [
			"pane",
			"report-agent-session",
			pane_id,
			...common,
			...(agent_session_path ? ["--agent-session-path", agent_session_path] : []),
			...(agent_session_id ? ["--agent-session-id", agent_session_id] : []),
			...(session_start_source ? ["--session-start-source", session_start_source] : []),
			...(resume_argv ? ["--", ...resume_argv] : []),
		];
	}
	const { state, message } = request.params;
	return [
		"pane",
		"report-agent",
		pane_id,
		...common,
		"--state",
		state,
		...(message ? ["--message", message] : []),
		...(agent_session_path ? ["--agent-session-path", agent_session_path] : []),
		...(agent_session_id ? ["--agent-session-id", agent_session_id] : []),
		...(resume_argv ? ["--", ...resume_argv] : []),
	];
}
