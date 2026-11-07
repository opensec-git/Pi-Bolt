/**
 * Live views of subagents for remote control and RPC mode (`subagent_subscribe`): while a phone shows one subagent run,
 * it gets that agent's own conversation as it happens. Read-only: nothing here changes a session.
 *
 * The subagents extension (opensec-pi-subagents) runs each `Agent` call as a session of its own in this process, saved
 * next to the parent's file (its header names the parent in `parentSession`, its name ends with `#<first 8 characters
 * of the agent id>`, its first user message ends with the call's prompt). A stream finds that session and sends:
 * - `subagent_entries`: the finished messages of its session file, read from the start and then as lines are appended
 *   (`reset: true` on the first batch);
 * - `subagent_events`: the agent's assistant message while it streams (`message_start`, `message_update`, `message_end`)
 *   and its tool starts and ends, batched, when the session is open in this process.
 * A message too large for a phone is cut (`capMessage`).
 */

import { closeSync, openSync, readdirSync, readSync, statSync } from "node:fs";
import { dirname, join } from "node:path";
import { toJsonEvent } from "../modes/json-event.ts";
import type { AgentSession, AgentSessionEvent } from "./agent-session.ts";
import { liveSessions } from "./live-sessions.ts";

/** The `remote_hello.features` entry of hosts that answer `subagent_subscribe`. */
export const SUBAGENT_STREAM_FEATURE = "subagent_stream";

const POLL_MS = 250;
/** How often an unbound stream lists the session folder again. */
const SCAN_MS = 1_000;
const EVENT_BATCH_MS = 80;
/** Bytes of the session file read per poll: a long transcript arrives in steps. */
const READ_CHUNK = 1024 * 1024;
/** A `subagent_entries` message is sent once its messages reach this size. */
const ENTRIES_BATCH_BYTES = 512 * 1024;
/** Lines of a session file read to find its header, name and first prompt. */
const HEADER_LINES = 40;
const HEADER_BYTES = 256 * 1024;

export interface SubagentStreamLimits {
	/** Streams one client may hold at once. */
	maxStreams: number;
	/** The largest message sent, in bytes of JSON; larger ones are cut. */
	maxMessageBytes: number;
}

export const DEFAULT_SUBAGENT_STREAM_LIMITS: SubagentStreamLimits = { maxStreams: 8, maxMessageBytes: 256 * 1024 };

type Json = Record<string, unknown>;

/** What a subagent run is, from the parent's `Agent` call and its result. */
interface RunInfo {
	prompt: string;
	agentId?: string;
}

interface Header {
	parent?: string;
	name?: string;
	firstUser?: string;
}

function isRecord(value: unknown): value is Json {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}

function textOf(content: unknown): string {
	if (typeof content === "string") return content;
	if (!Array.isArray(content)) return "";
	return content
		.filter((block): block is Json => isRecord(block) && block.type === "text" && typeof block.text === "string")
		.map((block) => block.text as string)
		.join("\n");
}

/** The `Agent` call `runId` of the parent's messages: its prompt, and its agent id once its result names one. */
export function findRun(messages: readonly unknown[], runId: string): RunInfo | undefined {
	let run: RunInfo | undefined;
	for (const message of messages) {
		if (!isRecord(message)) continue;
		if (message.role === "assistant" && Array.isArray(message.content)) {
			for (const block of message.content) {
				if (!isRecord(block) || block.type !== "toolCall" || block.id !== runId) continue;
				if (typeof block.name !== "string" || block.name.toLowerCase() !== "agent") continue;
				const args = isRecord(block.arguments) ? block.arguments : {};
				const resume = typeof args.resume === "string" && args.resume.trim() ? args.resume.trim() : undefined;
				run = { prompt: typeof args.prompt === "string" ? args.prompt : "", agentId: resume };
			}
		} else if (run && message.role === "toolResult" && message.toolCallId === runId && isRecord(message.details)) {
			const agentId = message.details.agentId;
			if (typeof agentId === "string" && agentId) run.agentId = agentId;
		}
	}
	return run;
}

/** True when a session named [name] whose first prompt is [firstUser] is the agent of [run]. */
function matches(run: RunInfo, name: string | undefined, firstUser: string | undefined): boolean {
	if (run.agentId) return name?.endsWith(`#${run.agentId.slice(0, 8)}`) === true;
	const prompt = run.prompt.trim();
	return prompt.length > 0 && firstUser?.trimEnd().endsWith(prompt) === true;
}

function readHeader(file: string): Header | undefined {
	let fd: number | undefined;
	try {
		fd = openSync(file, "r");
		const buffer = Buffer.alloc(HEADER_BYTES);
		const length = readSync(fd, buffer, 0, HEADER_BYTES, 0);
		const lines = buffer.subarray(0, length).toString("utf8").split("\n").slice(0, HEADER_LINES);
		let header: Header | undefined;
		for (const line of lines) {
			let entry: unknown;
			try {
				entry = JSON.parse(line);
			} catch {
				continue;
			}
			if (!isRecord(entry)) continue;
			if (entry.type === "session") {
				header = { parent: typeof entry.parentSession === "string" ? entry.parentSession : undefined };
			} else if (header && entry.type === "session_info" && typeof entry.name === "string") {
				header.name = entry.name;
			} else if (header && entry.type === "message" && isRecord(entry.message) && entry.message.role === "user") {
				header.firstUser = textOf(entry.message.content);
				break;
			}
		}
		return header;
	} catch {
		return undefined;
	} finally {
		if (fd !== undefined) closeSync(fd);
	}
}

/** The message a phone shows for a session file line: a message, or a compaction as its summary. */
export function messageOfEntry(entry: unknown): Json | undefined {
	if (!isRecord(entry)) return undefined;
	if (entry.type === "message" && isRecord(entry.message)) {
		// The system prompt is the agent's setup, not its conversation, and often large.
		return entry.message.role === "system" ? undefined : entry.message;
	}
	if (entry.type === "compaction") {
		return { role: "compactionSummary", summary: entry.summary ?? "", tokensBefore: entry.tokensBefore ?? 0 };
	}
	return undefined;
}

const CUT_NOTE = "\n… (cut for the phone)";

function cut(text: string, max: number): string {
	return text.length > max ? text.slice(0, Math.max(0, max)) + CUT_NOTE : text;
}

/**
 * [message] as at most [maxBytes] of JSON (roughly): long text, thinking and tool arguments are cut, images and
 * `details` dropped. Messages that fit are returned unchanged.
 */
export function capMessage(message: Json, maxBytes: number): Json {
	if (Buffer.byteLength(JSON.stringify(message)) <= maxBytes) return message;
	const capped: Json = { ...message };
	delete capped.details;
	const content = message.content;
	if (typeof content === "string") {
		capped.content = cut(content, Math.floor(maxBytes / 2));
	} else if (Array.isArray(content)) {
		const share = Math.max(1024, Math.floor(maxBytes / 2 / Math.max(1, content.length)));
		capped.content = content.map((block) => {
			if (!isRecord(block)) return block;
			if (block.type === "text" && typeof block.text === "string") return { ...block, text: cut(block.text, share) };
			if (block.type === "thinking" && typeof block.thinking === "string") {
				return { ...block, thinking: cut(block.thinking, share) };
			}
			if (block.type === "image") return { type: "text", text: "(image)" };
			if (block.type === "toolCall" && isRecord(block.arguments)) {
				const args: Json = {};
				for (const [key, value] of Object.entries(block.arguments)) {
					args[key] = typeof value === "string" ? cut(value, share) : value;
				}
				return { ...block, arguments: args };
			}
			return block;
		});
	}
	if (typeof message.summary === "string") capped.summary = cut(message.summary, Math.floor(maxBytes / 2));
	return capped;
}

/** One client's view of one subagent run. */
class SubagentStream {
	readonly runId: string;
	private readonly owner: SubagentStreams;
	private readonly run: RunInfo;
	private readonly parentFile: string | undefined;
	private file: string | undefined;
	private live: AgentSession | undefined;
	private unsubscribeLive: (() => void) | undefined;
	private offset = 0;
	private rest = Buffer.alloc(0);
	/** For a live session without a file: messages already sent. */
	private sentMessages = 0;
	private resetPending = true;
	private lastScan = 0;
	private events: unknown[] = [];
	private eventTimer: ReturnType<typeof setTimeout> | undefined;
	private readonly timer: ReturnType<typeof setInterval>;

	constructor(owner: SubagentStreams, runId: string, run: RunInfo, parentFile: string | undefined) {
		this.owner = owner;
		this.runId = runId;
		this.run = run;
		this.parentFile = parentFile;
		this.timer = setInterval(() => this.poll(), owner.pollMs);
		this.timer.unref?.();
		this.poll();
	}

	/** The file this stream reads, once found. */
	get boundFile(): string | undefined {
		return this.file;
	}

	stop(): void {
		clearInterval(this.timer);
		if (this.eventTimer) clearTimeout(this.eventTimer);
		this.eventTimer = undefined;
		this.unsubscribeLive?.();
		this.unsubscribeLive = undefined;
		this.live = undefined;
	}

	poll(): void {
		try {
			this.bindLive();
			if (!this.file && !this.live) this.scan();
			if (this.file) this.readFile();
			else if (this.live) this.readLiveMessages();
		} catch {
			// A file removed or unreadable for a moment: the next poll tries again.
		}
	}

	/** The agent's session open in this process, if it is: its streaming message comes from its events. */
	private bindLive(): void {
		const open = liveSessions();
		if (this.live && !open.includes(this.live)) {
			this.unsubscribeLive?.();
			this.unsubscribeLive = undefined;
			this.live = undefined;
		}
		if (this.live || !this.parentFile) return;
		for (const session of open) {
			if (session.sessionManager.getHeader()?.parentSession !== this.parentFile) continue;
			if (this.file && session.sessionFile !== this.file) continue;
			if (!this.file && this.owner.taken(session.sessionFile, this)) continue;
			const firstUser = session.messages.find((message) => message.role === "user");
			if (!matches(this.run, session.sessionName, firstUser ? textOf(firstUser.content) : undefined)) continue;
			this.live = session;
			this.file ??= session.sessionFile;
			this.unsubscribeLive = session.subscribe((event) => this.onLiveEvent(event));
			// Joined while a message streams: start the phone's copy from what it has so far.
			const streaming = session.agent.state.streamingMessage;
			if (session.isStreaming && streaming?.role === "assistant") {
				this.queueEvent({
					type: "message_start",
					message: capMessage(streaming as unknown as Json, this.owner.limits.maxMessageBytes),
				});
			}
			return;
		}
	}

	private scan(): void {
		const now = Date.now();
		if (!this.parentFile || now - this.lastScan < SCAN_MS) return;
		this.lastScan = now;
		const dir = dirname(this.parentFile);
		const candidates = readdirSync(dir)
			.filter((name) => name.endsWith(".jsonl"))
			.map((name) => join(dir, name))
			.filter((path) => path !== this.parentFile && !this.owner.taken(path, this))
			.map((path) => ({ path, mtime: statSync(path, { throwIfNoEntry: false })?.mtimeMs ?? 0 }))
			.sort((a, b) => b.mtime - a.mtime);
		for (const { path } of candidates) {
			const header = this.owner.header(path);
			if (!header || header.parent !== this.parentFile) continue;
			if (matches(this.run, header.name, header.firstUser)) {
				this.file = path;
				return;
			}
		}
	}

	private readFile(): void {
		const file = this.file;
		if (!file) return;
		const size = statSync(file, { throwIfNoEntry: false })?.size;
		if (size === undefined) return;
		if (size < this.offset) {
			// Rewritten (a new branch): read it again.
			this.offset = 0;
			this.rest = Buffer.alloc(0);
			this.resetPending = true;
		}
		if (size === this.offset && !this.resetPending) return;
		const messages: Json[] = [];
		if (size > this.offset) {
			const fd = openSync(file, "r");
			let data: Buffer;
			try {
				const length = Math.min(READ_CHUNK, size - this.offset);
				const buffer = Buffer.alloc(length);
				const read = readSync(fd, buffer, 0, length, this.offset);
				this.offset += read;
				data =
					this.rest.length > 0 ? Buffer.concat([this.rest, buffer.subarray(0, read)]) : buffer.subarray(0, read);
			} finally {
				closeSync(fd);
			}
			const end = data.lastIndexOf(0x0a);
			this.rest = Buffer.from(end < 0 ? data : data.subarray(end + 1));
			if (end >= 0) {
				for (const line of data.subarray(0, end).toString("utf8").split("\n")) {
					if (!line) continue;
					let entry: unknown;
					try {
						entry = JSON.parse(line);
					} catch {
						continue;
					}
					const message = messageOfEntry(entry);
					if (message) messages.push(message);
				}
			}
		}
		this.sendEntries(messages);
	}

	private readLiveMessages(): void {
		const all = this.live?.messages ?? [];
		if (all.length < this.sentMessages) {
			this.sentMessages = 0;
			this.resetPending = true;
		}
		const fresh = all.slice(this.sentMessages).filter((message) => message.role !== "system") as unknown as Json[];
		this.sentMessages = all.length;
		this.sendEntries(fresh);
	}

	private sendEntries(messages: Json[]): void {
		if (messages.length === 0 && !this.resetPending) return;
		const max = this.owner.limits.maxMessageBytes;
		let batch: Json[] = [];
		let bytes = 0;
		const flush = () => {
			this.owner.send({
				type: "subagent_entries",
				runId: this.runId,
				...(this.file ? { file: this.file } : {}),
				reset: this.resetPending,
				messages: batch,
			});
			this.resetPending = false;
			batch = [];
			bytes = 0;
		};
		for (const message of messages) {
			const capped = capMessage(message, max);
			bytes += Buffer.byteLength(JSON.stringify(capped));
			batch.push(capped);
			if (bytes >= ENTRIES_BATCH_BYTES) flush();
		}
		if (batch.length > 0 || this.resetPending) flush();
	}

	private onLiveEvent(event: AgentSessionEvent): void {
		switch (event.type) {
			case "message_start":
			case "message_end":
				if (event.message.role !== "assistant") return;
				this.queueEvent({
					type: event.type,
					message: capMessage(event.message as unknown as Json, this.owner.limits.maxMessageBytes),
				});
				// The message is saved right after its listeners ran: read it from the file at once.
				if (event.type === "message_end") setImmediate(() => this.poll());
				return;
			case "message_update":
				if (event.message.role !== "assistant") return;
				this.queueEvent(toJsonEvent(event));
				return;
			case "tool_execution_start":
				this.queueEvent({
					type: event.type,
					toolCallId: event.toolCallId,
					toolName: event.toolName,
					args: event.args,
				});
				return;
			case "tool_execution_end":
				this.queueEvent({
					type: event.type,
					toolCallId: event.toolCallId,
					toolName: event.toolName,
					isError: event.isError,
				});
				return;
			default:
				return;
		}
	}

	private queueEvent(event: unknown): void {
		this.events.push(event);
		if (this.eventTimer) return;
		this.eventTimer = setTimeout(() => {
			this.eventTimer = undefined;
			const events = this.events;
			this.events = [];
			if (events.length > 0) this.owner.send({ type: "subagent_events", runId: this.runId, events });
		}, this.owner.eventBatchMs);
		this.eventTimer.unref?.();
	}
}

export interface SubagentStreamsOptions {
	/** The session whose subagents are followed. */
	getSession: () => AgentSession | undefined;
	send: (message: object) => void;
	limits?: Partial<SubagentStreamLimits>;
	/** Tests shorten these. */
	pollMs?: number;
	eventBatchMs?: number;
}

/** The subagent streams of one client (a phone over remote control, or the RPC client). */
export class SubagentStreams {
	readonly limits: SubagentStreamLimits;
	readonly pollMs: number;
	readonly eventBatchMs: number;
	private readonly options: SubagentStreamsOptions;
	private readonly streams = new Map<string, SubagentStream>();
	private readonly headers = new Map<string, Header>();

	constructor(options: SubagentStreamsOptions) {
		this.options = options;
		this.limits = { ...DEFAULT_SUBAGENT_STREAM_LIMITS, ...options.limits };
		this.pollMs = options.pollMs ?? POLL_MS;
		this.eventBatchMs = options.eventBatchMs ?? EVENT_BATCH_MS;
	}

	get size(): number {
		return this.streams.size;
	}

	/** Starts following run [runId] (the id of its `Agent` call). Throws when the run is unknown or too many are open. */
	subscribe(runId: unknown): void {
		if (typeof runId !== "string" || !runId) throw new Error("runId is required");
		if (this.streams.has(runId)) return;
		const session = this.options.getSession();
		if (!session) throw new Error("No session is open yet");
		const run = findRun(session.messages, runId);
		if (!run) throw new Error(`No subagent run ${runId} in this session`);
		if (this.streams.size >= this.limits.maxStreams) {
			throw new Error(`At most ${this.limits.maxStreams} subagent streams at once`);
		}
		this.streams.set(runId, new SubagentStream(this, runId, run, session.sessionFile));
	}

	unsubscribe(runId: unknown): boolean {
		if (typeof runId !== "string") return false;
		const stream = this.streams.get(runId);
		if (!stream) return false;
		stream.stop();
		this.streams.delete(runId);
		return true;
	}

	/** Stops every stream: the client left, or the session it showed was replaced. */
	clear(): void {
		for (const stream of this.streams.values()) stream.stop();
		this.streams.clear();
		this.headers.clear();
	}

	send(message: object): void {
		this.options.send(message);
	}

	/** True when [file] is read by another stream of this client (two runs with the same prompt). */
	taken(file: string | undefined, by: SubagentStream): boolean {
		if (!file) return false;
		for (const stream of this.streams.values()) if (stream !== by && stream.boundFile === file) return true;
		return false;
	}

	/** A session file's header, cached once its first prompt is known (until then it is read again). */
	header(file: string): Header | undefined {
		const cached = this.headers.get(file);
		if (cached) return cached;
		const header = readHeader(file);
		if (header?.firstUser !== undefined) this.headers.set(file, header);
		return header;
	}
}
