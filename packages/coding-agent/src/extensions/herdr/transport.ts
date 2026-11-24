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

/** Herdr's answer to one request: taken, or not, with Herdr's error code when it refused the request. */
export type HerdrAnswer = { taken: true } | { taken: false; code?: string };

/**
 * Send one request and return Herdr's answer. Never throws; waits at most `timeoutMs`, also when Pi-Bolt is quitting (the
 * release on quit must get through), and never longer. No answer in time is `{ taken: false }` without a code.
 */
export type HerdrTransport = (request: HerdrRequest, timeoutMs: number) => Promise<HerdrAnswer>;

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

const NOT_TAKEN: HerdrAnswer = { taken: false };

/**
 * What one line from Herdr's socket says about request `id`: taken for `{"id", "result"}`, not taken for `{"id", "error"}`
 * (with its code) or any other line with that id, undefined for a line that is not about this request.
 */
export function herdrAnswer(id: string, line: string): HerdrAnswer | undefined {
	const answer = parseLine(line);
	if (!answer || answer.id !== id) return undefined;
	const { result, error } = answer;
	return error == null && result !== undefined ? { taken: true } : refused(error);
}

/**
 * The error code in the stderr of a `herdr pane` command that failed: the last line holding Herdr's answer
 * `{"id", "error": {"code"}}`, also with text around it, so a warning or log line before or after it does not hide it.
 */
export function herdrCommandError(output: string): HerdrAnswer {
	const lines = output.split(/\r?\n/);
	for (let i = lines.length - 1; i >= 0; i--) {
		const line = lines[i];
		const start = line.indexOf("{");
		const end = line.lastIndexOf("}");
		if (start < 0 || end < start) continue;
		const answer = refused(parseLine(line.slice(start, end + 1))?.error);
		if (!answer.taken && answer.code) return answer;
	}
	return NOT_TAKEN;
}

// Wire input from Herdr: checked here, typed after.
function parseLine(line: string): { id?: unknown; result?: unknown; error?: unknown } | undefined {
	try {
		const value: unknown = JSON.parse(line);
		return typeof value === "object" && value !== null ? value : undefined;
	} catch {
		return undefined;
	}
}

function refused(error: unknown): HerdrAnswer {
	const code = typeof error === "object" && error !== null ? (error as { code?: unknown }).code : undefined;
	return typeof code === "string" ? { taken: false, code } : NOT_TAKEN;
}

export function socketTransport(endpoint: string): HerdrTransport {
	return (request, timeoutMs) =>
		new Promise((resolve) => {
			let done = false;
			let socket: Socket | undefined;
			const timeout = setTimeout(() => finish(NOT_TAKEN), timeoutMs);
			timeout.unref?.();
			function finish(answer: HerdrAnswer) {
				if (done) return;
				done = true;
				clearTimeout(timeout);
				socket?.destroy();
				resolve(answer);
			}
			try {
				// node:net loads on first report: only a Herdr pane needs it.
				socket = process.getBuiltinModule("node:net").createConnection(endpoint);
			} catch {
				finish(NOT_TAKEN);
				return;
			}
			const id = `pi-bolt:${process.pid}:${++requestCount}`;
			const line = JSON.stringify({ id, ...request });
			// The answer is one line with this id: Herdr took the request only when it carries a result, not an error.
			let buffered = "";
			socket.setEncoding("utf8");
			socket.on("error", () => finish(NOT_TAKEN));
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
				if (buffered.length > MAX_ANSWER_LENGTH) finish(NOT_TAKEN);
			});
			socket.on("end", () => finish(herdrAnswer(id, buffered) ?? NOT_TAKEN));
		});
}

/** How long a failed `herdr` command's stderr may stay open after it exited. */
const STDERR_GRACE_MS = 100;

export function executableTransport(bin: string): HerdrTransport {
	return (request, timeoutMs) =>
		new Promise((resolve) => {
			let done = false;
			let child: ReturnType<typeof spawn> | undefined;
			let grace: ReturnType<typeof setTimeout> | undefined;
			const finish = (answer: HerdrAnswer) => {
				if (done) return;
				done = true;
				clearTimeout(timeout);
				clearTimeout(grace);
				// A process the command started may still hold the pipe; Pi-Bolt does not wait for it.
				child?.stderr?.destroy();
				resolve(answer);
			};
			const timeout = setTimeout(() => {
				child?.kill();
				finish(NOT_TAKEN);
			}, timeoutMs);
			timeout.unref?.();
			try {
				child = spawn(bin, herdrArguments(request), { stdio: ["ignore", "ignore", "pipe"], windowsHide: true });
			} catch {
				finish(NOT_TAKEN);
				return;
			}
			// A refused request exits non-zero with Herdr's error answer on stderr.
			let stderr = "";
			child.stderr?.setEncoding("utf8");
			child.stderr?.on("data", (chunk: string) => {
				if (stderr.length < MAX_ANSWER_LENGTH) stderr += chunk;
			});
			child.on("error", () => finish(NOT_TAKEN));
			// The answer is the exit status. A refusal's error is on stderr, which ends with the command unless a process it
			// started keeps the pipe open: that gets a short grace, not the whole timeout.
			child.on("exit", (code) => {
				if (code === 0) return finish({ taken: true });
				grace = setTimeout(() => finish(herdrCommandError(stderr)), STDERR_GRACE_MS);
				grace.unref?.();
			});
			child.on("close", (code) => finish(code === 0 ? { taken: true } : herdrCommandError(stderr)));
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
