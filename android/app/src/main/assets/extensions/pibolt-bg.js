// pibolt-bg v1: written by the Pi-Bolt Android app; do not edit (it is rewritten).
// Background shell jobs for the agent: bash_background, bash_output, bash_kill, bash_jobs. Each job runs in the shell
// Pi's bash tool uses, with the engine's environment, in its own process group (stop kills its children too). Output
// goes to a log file and a 1 MB ring buffer; the job list is mirrored to jobs.json beside the logs for the app, under
// $TMPDIR/pibolt-bg/<engine pid>/. Jobs end with the session (session_shutdown) and with the engine (exit).
import { spawn } from "node:child_process";
import { closeSync, existsSync, mkdirSync, openSync, readdirSync, readSync, realpathSync, renameSync, rmSync, statSync, writeFileSync, writeSync } from "node:fs";
import { tmpdir } from "node:os";
import { isAbsolute, join, relative, resolve } from "node:path";
import { StringDecoder } from "node:string_decoder";
import { getShellConfig } from "@earendil-works/pi-coding-agent";

const MAX_RUNNING = 5;
const RING_BYTES = 1024 * 1024;
const READ_CAP = 32 * 1024;
const KILL_GRACE_MS = 5000;
const STATUS_KEY = "bg-jobs";
// ESC sequences (CSI, OSC, two-byte) and control characters other than tab and newline.
const ANSI = /\x1b\[[0-?]*[ -/]*[@-~]|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)|\x1b[@-Z\\-_]|[\x00-\x08\x0b-\x1f\x7f]/g;

const root = join(process.env.TMPDIR || tmpdir(), "pibolt-bg");
const dir = join(root, String(process.pid));
let jobs = [];
let nextId = 1;
let lastCtx;
// Pi loads the extension again on every session switch; the module (and so this listener) stays one.
let exitHooked = false;

function alive(pid) {
	try {
		process.kill(pid, 0);
		return true;
	} catch (e) {
		return e.code === "EPERM";
	}
}

/** Logs of engines that are gone (killed without an exit handler). */
function sweep() {
	try {
		for (const name of readdirSync(root)) {
			const pid = Number(name);
			if (pid && pid !== process.pid && !alive(pid)) rmSync(join(root, name), { recursive: true, force: true });
		}
	} catch {}
}

function killGroup(job, signal) {
	try {
		process.kill(-job.pid, signal);
	} catch {
		try {
			process.kill(job.pid, signal);
		} catch {}
	}
}

function publicJob(j) {
	return {
		id: j.id,
		name: j.name,
		command: j.command,
		cwd: j.cwd,
		pid: j.pid,
		status: j.status,
		exitCode: j.exitCode,
		signal: j.signal,
		startedAt: j.startedAt,
		endedAt: j.endedAt,
		bytes: j.total,
		logFile: j.logFile,
	};
}

function save() {
	try {
		mkdirSync(dir, { recursive: true });
		const tmp = join(dir, `.jobs.${Date.now()}.tmp`);
		writeFileSync(tmp, JSON.stringify({ version: 1, enginePid: process.pid, jobs: jobs.map(publicJob) }));
		renameSync(tmp, join(dir, "jobs.json"));
	} catch {}
	const running = jobs.filter((j) => j.status === "running").length;
	try {
		lastCtx?.ui?.setStatus(STATUS_KEY, running > 0 ? `${running} background job${running === 1 ? "" : "s"} running` : undefined);
	} catch {}
}

function append(job, text) {
	if (!text) return;
	const buf = Buffer.from(text, "utf8");
	try {
		writeSync(job.fd, buf);
	} catch {}
	job.ring = job.ring.length + buf.length > RING_BYTES ? Buffer.concat([job.ring, buf]).subarray(-RING_BYTES) : Buffer.concat([job.ring, buf]);
	job.total += buf.length;
}

function onData(job, decoder, chunk) {
	let text = job.carry + decoder.write(chunk);
	// Keep an escape sequence cut by the chunk's end for the next chunk.
	const esc = text.lastIndexOf("\x1b");
	job.carry = "";
	if (esc >= 0 && esc > text.length - 32 && !/[@-~\x07]/.test(text.slice(esc + 2))) {
		job.carry = text.slice(esc);
		text = text.slice(0, esc);
	}
	append(job, text.replace(/\r\n/g, "\n").replace(ANSI, ""));
}

/** Bytes [from, to) of the job's output: from the ring, else from the log file. */
function readRange(job, from, to) {
	const ringStart = job.total - job.ring.length;
	let buf;
	if (from >= ringStart) {
		buf = job.ring.subarray(from - ringStart, to - ringStart);
	} else {
		buf = Buffer.alloc(to - from);
		let fd;
		try {
			fd = openSync(job.logFile, "r");
			const n = readSync(fd, buf, 0, buf.length, from);
			buf = buf.subarray(0, n);
		} catch {
			buf = Buffer.alloc(0);
		} finally {
			if (fd !== undefined) closeSync(fd);
		}
	}
	let start = 0;
	while (start < buf.length && (buf[start] & 0xc0) === 0x80) start++;
	return buf.subarray(start).toString("utf8");
}

function uptime(job) {
	const s = Math.round(((job.endedAt ?? Date.now()) - job.startedAt) / 1000);
	return s < 60 ? `${s}s` : s < 3600 ? `${Math.floor(s / 60)}m ${s % 60}s` : `${Math.floor(s / 3600)}h ${Math.floor((s % 3600) / 60)}m`;
}

function describe(job) {
	const state =
		job.status === "running"
			? "running"
			: job.signal
				? `stopped by ${job.signal}`
				: `exited with code ${job.exitCode}`;
	return `Job #${job.id} (${job.name}): ${state}, pid ${job.pid}, ${uptime(job)}`;
}

function find(id) {
	const job = jobs.find((j) => j.id === Number(id));
	if (!job) throw new Error(`No background job #${id}. Jobs: ${jobs.map((j) => `#${j.id}`).join(", ") || "none"}.`);
	return job;
}

function stopAll() {
	for (const j of jobs) if (j.status === "running") killGroup(j, "SIGKILL");
}

function reset() {
	stopAll();
	for (const j of jobs) {
		try {
			closeSync(j.fd);
		} catch {}
	}
	jobs = [];
	try {
		rmSync(dir, { recursive: true, force: true });
	} catch {}
}

function text(t, details, isError = false) {
	return { content: [{ type: "text", text: t }], details, ...(isError ? { isError: true } : {}) };
}

export default function (pi) {
	if (!exitHooked) {
		exitHooked = true;
		process.on("exit", () => {
			stopAll();
			try {
				rmSync(dir, { recursive: true, force: true });
			} catch {}
		});
	}

	pi.on("session_start", (_event, ctx) => {
		lastCtx = ctx;
		sweep();
	});

	pi.on("session_shutdown", () => {
		reset();
		try {
			lastCtx?.ui?.setStatus(STATUS_KEY, undefined);
		} catch {}
		lastCtx = undefined;
	});

	pi.registerTool({
		name: "bash_background",
		label: "bash_background",
		description:
			"Start a shell command in the background and return at once with its job id. Use it for dev servers, watchers, long builds or tests: " +
			"the command keeps running while you work. Read its output with bash_output, stop it with bash_kill, list jobs with bash_jobs. " +
			`At most ${MAX_RUNNING} jobs run at a time. Runs in the project directory (or cwd inside it) with the same shell and environment as bash.`,
		promptSnippet: "Run a long-lived shell command (dev server, watcher, long build) in the background",
		promptGuidelines: [
			"Use bash_background, not bash, for commands that do not end on their own (dev servers, watchers) or that take minutes; then check them with bash_output and stop them with bash_kill when done.",
		],
		parameters: {
			type: "object",
			properties: {
				command: { type: "string", description: "Shell command to run" },
				cwd: { type: "string", description: "Working directory inside the project (default: the project directory)" },
				name: { type: "string", description: "Short name for the job, e.g. 'dev server'" },
			},
			required: ["command"],
		},
		async execute(_toolCallId, params, _signal, _onUpdate, ctx) {
			lastCtx = ctx;
			const running = jobs.filter((j) => j.status === "running");
			if (running.length >= MAX_RUNNING) {
				throw new Error(
					`${MAX_RUNNING} background jobs are already running (${running.map((j) => `#${j.id} ${j.name}`).join(", ")}). Stop one with bash_kill first.`,
				);
			}
			const project = realpathSync(ctx.cwd);
			const target = params.cwd ? resolve(project, params.cwd) : project;
			let cwd;
			try {
				cwd = realpathSync(target);
			} catch {
				throw new Error(`Working directory does not exist: ${target}`);
			}
			const rel = relative(project, cwd);
			if (rel.startsWith("..") || isAbsolute(rel)) throw new Error(`cwd must be inside the project (${project}): ${cwd}`);
			if (!statSync(cwd).isDirectory()) throw new Error(`Not a directory: ${cwd}`);

			const shell = getShellConfig();
			const env = { ...process.env };
			const binDir = join(process.env.HOME || "", ".pi", "agent", "bin");
			if (existsSync(binDir) && !(env.PATH || "").split(":").includes(binDir)) env.PATH = `${binDir}:${env.PATH || ""}`;
			try {
				env.PI_SESSION_ID = ctx.sessionManager.getSessionId();
				const file = ctx.sessionManager.getSessionFile();
				if (file) env.PI_SESSION_FILE = file;
			} catch {}

			mkdirSync(dir, { recursive: true });
			const id = nextId++;
			const logFile = join(dir, `${id}.log`);
			const fd = openSync(logFile, "w", 0o600);
			const fromStdin = shell.commandTransport === "stdin";
			const child = spawn(shell.shell, fromStdin ? shell.args : [...shell.args, params.command], {
				cwd,
				env,
				detached: true,
				stdio: [fromStdin ? "pipe" : "ignore", "pipe", "pipe"],
			});
			if (fromStdin) {
				child.stdin?.on("error", () => {});
				child.stdin?.end(params.command);
			}
			const name = (params.name || "").trim() || params.command.split("\n")[0].slice(0, 60);
			const job = {
				id,
				name,
				command: params.command,
				cwd,
				pid: child.pid ?? -1,
				status: "running",
				exitCode: null,
				signal: null,
				startedAt: Date.now(),
				endedAt: null,
				fd,
				logFile,
				ring: Buffer.alloc(0),
				total: 0,
				read: 0,
				carry: "",
				exited: undefined,
			};
			job.exited = new Promise((res) => {
				const finish = (code, signal) => {
					if (job.status !== "running") return res();
					job.status = signal ? "killed" : "exited";
					job.exitCode = code;
					job.signal = signal;
					job.endedAt = Date.now();
					if (job.carry) append(job, job.carry.replace(ANSI, ""));
					job.carry = "";
					try {
						closeSync(job.fd);
					} catch {}
					save();
					res();
				};
				child.on("error", (e) => {
					append(job, `\n[failed to start: ${e.message}]\n`);
					finish(127, null);
				});
				child.on("close", finish);
			});
			const out = new StringDecoder("utf8");
			const err = new StringDecoder("utf8");
			child.stdout.on("data", (c) => onData(job, out, c));
			child.stderr.on("data", (c) => onData(job, err, c));
			jobs.push(job);
			save();
			return text(
				`Started job #${id} (${name}) in the background, pid ${job.pid}. Read its output with bash_output {"id": ${id}}; stop it with bash_kill {"id": ${id}}. Full log: ${logFile}`,
				{ id, pid: job.pid, name, command: params.command, cwd, logFile },
			);
		},
	});

	pi.registerTool({
		name: "bash_output",
		label: "bash_output",
		description:
			"Read a background job's new output (stdout and stderr) since the last read, or from byte offset `since`, plus whether it still runs and its exit status. " +
			`At most ${READ_CAP / 1024} KB per call; the full log file path is given.`,
		promptSnippet: "Read new output and the status of a background job",
		parameters: {
			type: "object",
			properties: {
				id: { type: "number", description: "Job id from bash_background" },
				since: { type: "number", description: "Byte offset to read from (default: where the last read ended)" },
			},
			required: ["id"],
		},
		async execute(_toolCallId, params, _signal, _onUpdate, ctx) {
			lastCtx = ctx;
			const job = find(params.id);
			const explicit = typeof params.since === "number" && params.since >= 0;
			let from = Math.min(explicit ? Math.floor(params.since) : job.read, job.total);
			let to = job.total;
			let skipped = 0;
			if (to - from > READ_CAP) {
				if (explicit) {
					to = from + READ_CAP;
				} else {
					skipped = to - READ_CAP - from;
					from = to - READ_CAP;
				}
			}
			const body = readRange(job, from, to);
			job.read = Math.max(job.read, to);
			const notes = [];
			if (skipped > 0) notes.push(`[${skipped} earlier bytes not shown: read them with since=${from - skipped}, or from ${job.logFile}]`);
			if (to < job.total) notes.push(`[more output: call again with since=${to}]`);
			const t = `${describe(job)}\n${body ? `${body.replace(/\n$/, "")}\n` : "(no new output)\n"}${notes.join("\n")}${notes.length ? "\n" : ""}[output offset ${to} of ${job.total}]`;
			return text(t, {
				id: job.id,
				name: job.name,
				status: job.status,
				running: job.status === "running",
				exitCode: job.exitCode,
				signal: job.signal,
				from,
				to,
				total: job.total,
				skipped,
				logFile: job.logFile,
			});
		},
	});

	pi.registerTool({
		name: "bash_kill",
		label: "bash_kill",
		description: `Stop a background job and every process it started: SIGTERM, then SIGKILL after ${KILL_GRACE_MS / 1000} s.`,
		promptSnippet: "Stop a background job",
		parameters: {
			type: "object",
			properties: { id: { type: "number", description: "Job id from bash_background" } },
			required: ["id"],
		},
		async execute(_toolCallId, params, _signal, _onUpdate, ctx) {
			lastCtx = ctx;
			const job = find(params.id);
			if (job.status !== "running") return text(`${describe(job)} (already ended)`, { id: job.id, status: job.status });
			killGroup(job, "SIGTERM");
			let timer;
			const ended = await Promise.race([
				job.exited.then(() => true),
				new Promise((r) => {
					timer = setTimeout(() => r(false), KILL_GRACE_MS);
				}),
			]);
			clearTimeout(timer);
			if (!ended) {
				killGroup(job, "SIGKILL");
				await Promise.race([job.exited, new Promise((r) => setTimeout(r, 2000))]);
			}
			// Children that ignored SIGTERM after the shell ended.
			killGroup(job, "SIGKILL");
			return text(describe(job), { id: job.id, status: job.status, exitCode: job.exitCode, signal: job.signal });
		},
	});

	pi.registerTool({
		name: "bash_jobs",
		label: "bash_jobs",
		description: "List background jobs (running and finished) with their status, uptime and output size.",
		promptSnippet: "List background jobs",
		parameters: { type: "object", properties: {} },
		async execute(_toolCallId, _params, _signal, _onUpdate, ctx) {
			lastCtx = ctx;
			const lines = jobs.map((j) => `${describe(j)}, ${j.total} bytes of output: ${j.command.split("\n")[0].slice(0, 120)}`);
			return text(lines.length ? lines.join("\n") : "No background jobs.", { jobs: jobs.map(publicJob) });
		},
	});
}
