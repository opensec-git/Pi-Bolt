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

import { closeSync, type FSWatcher, fstatSync, openSync, readdirSync, readSync, statSync, watch } from "node:fs";
import { basename, dirname, join } from "node:path";
import { StringDecoder } from "node:string_decoder";
import { toJsonEvent } from "../modes/json-event.ts";
import type { AgentSession, AgentSessionEvent } from "./agent-session.ts";
import { liveSessions } from "./live-sessions.ts";
import { nativeByteScans } from "./native-byte-scans.ts";

/** The `remote_hello.features` entry of hosts that answer `subagent_subscribe`. */
export const SUBAGENT_STREAM_FEATURE = "subagent_stream";

const POLL_MS = 250;
/** How often an unbound stream lists the session folder again. */
const SCAN_MS = 1_000;
const EVENT_BATCH_MS = 80;
/** How long live events wait for the saved copy of a message that ended before them. */
const BOUNDARY_WAIT_MS = 5_000;
/** Bytes of the session file read per poll: a long transcript arrives in steps. */
const READ_CHUNK = 1024 * 1024;
/** A `subagent_entries` message is sent once its messages reach this size. */
const ENTRIES_BATCH_BYTES = 512 * 1024;
/** Lines of a session file read to find its header, name and first prompt. */
const HEADER_LINES = 40;
const HEADER_BYTES = 256 * 1024;
const HEADER_PREFIX_BYTES = 4 * 1024;
// Linux reports each neighboring write to a directory watch. macOS directory events reliably cover replacement.
const WATCH_INODE = process.platform === "linux";

interface FileIdentity {
	dev: number;
	ino: number;
}

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
	/** From `resume`, or from the result once it names one. Without it the run is matched by its prompt. */
	agentId?: string;
	/** When the `Agent` call's message was made (Unix ms): the run's own agent session is created later. */
	since?: number;
}

interface Header {
	parent?: string;
	/** When the session was created (Unix ms). */
	created?: number;
	name?: string;
	firstUser?: string;
}

/** Shared only while one timer callback polls the client's active subscriptions. */
interface StreamPoll {
	parent?: AgentSession;
	runs?: Map<string, RunInfo>;
	listing?: { parentFile: string; paths: string[] };
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

/** Record a parent message's Agent calls or attach the agent ID from a later tool result. */
function collectRun(message: unknown, runs: Map<string, RunInfo>): void {
	if (!isRecord(message)) return;
	if (message.role === "assistant" && Array.isArray(message.content)) {
		for (const block of message.content) {
			if (!isRecord(block) || block.type !== "toolCall" || typeof block.id !== "string") continue;
			if (typeof block.name !== "string" || block.name.toLowerCase() !== "agent") continue;
			const args = isRecord(block.arguments) ? block.arguments : {};
			const resume = typeof args.resume === "string" && args.resume.trim() ? args.resume.trim() : undefined;
			// Seen again (the saved branch, then the session's messages): the first keeps what its result added.
			if (runs.has(block.id)) continue;
			runs.set(block.id, {
				prompt: typeof args.prompt === "string" ? args.prompt : "",
				agentId: resume,
				since: typeof message.timestamp === "number" ? message.timestamp : undefined,
			});
		}
	} else if (message.role === "toolResult" && typeof message.toolCallId === "string" && isRecord(message.details)) {
		const run = runs.get(message.toolCallId);
		const agentId = message.details.agentId;
		if (run && typeof agentId === "string" && agentId && run.agentId !== agentId) {
			runs.set(message.toolCallId, { ...run, agentId });
		}
	}
}

/** The `Agent` call `runId` of the parent's messages: its prompt, and its agent id once its result names one. */
export function findRun(messages: readonly unknown[], runId: string): RunInfo | undefined {
	const runs = new Map<string, RunInfo>();
	for (const message of messages) collectRun(message, runs);
	return runs.get(runId);
}

/** The agents of the other runs, by the 8 characters their sessions' names end with: they are not this run's. */
function otherAgents(runs: ReadonlyMap<string, RunInfo>, runId: string): Set<string> {
	const agents = new Set<string>();
	for (const [id, run] of runs) if (id !== runId && run.agentId) agents.add(run.agentId.slice(0, 8));
	return agents;
}

/** A session that may be the agent of a run: its name, first prompt and creation time. */
interface Candidate {
	name?: string;
	firstUser?: string;
	created?: number;
}

/**
 * True when [candidate] is the agent of [run]. With an agent id that is exact. By prompt alone it is a guess, so a
 * session made before the call, or named for another run's agent ([others]), is not taken: a repeated prompt does not
 * bind to an earlier run's agent.
 */
function matches(run: RunInfo, candidate: Candidate, others: ReadonlySet<string>): boolean {
	if (run.agentId) return candidate.name?.endsWith(`#${run.agentId.slice(0, 8)}`) === true;
	const prompt = run.prompt.trim();
	if (!prompt || candidate.firstUser?.trimEnd().endsWith(prompt) !== true) return false;
	if (run.since !== undefined && candidate.created !== undefined && candidate.created < run.since) return false;
	const hash = candidate.name?.lastIndexOf("#") ?? -1;
	return !(candidate.name && hash >= 0 && others.has(candidate.name.slice(hash + 1, hash + 9)));
}

function parseTime(value: unknown): number | undefined {
	const time = typeof value === "string" ? Date.parse(value) : Number.NaN;
	return Number.isFinite(time) ? time : undefined;
}

function readHeader(file: string, buffer: Buffer): Header | undefined {
	let fd: number | undefined;
	try {
		fd = openSync(file, "r");
		let limit = HEADER_PREFIX_BYTES;
		let bytes = buffer.subarray(0, readSync(fd, buffer, 0, limit, 0));
		let start = 0;
		let header: Header | undefined;
		for (let i = 0; i < HEADER_LINES; i++) {
			let end = bytes.indexOf(0x0a, start);
			// Most files identify the child on the first page. A long setup or prompt may span several reads.
			while (end < 0 && bytes.length === limit && limit < HEADER_BYTES) {
				const offset = limit;
				limit = Math.min(limit * 4, HEADER_BYTES);
				const read = readSync(fd, buffer, offset, limit - offset, offset);
				bytes = buffer.subarray(0, offset + read);
				end = bytes.indexOf(0x0a, offset);
			}
			if (start >= bytes.length) break;
			if (end < 0) end = bytes.length;
			const line = bytes.toString("utf8", start, end);
			start = end + 1;
			let entry: unknown;
			try {
				entry = JSON.parse(line);
			} catch {
				continue;
			}
			if (!isRecord(entry)) continue;
			if (entry.type === "session") {
				header = {
					parent: typeof entry.parentSession === "string" ? entry.parentSession : undefined,
					created: parseTime(entry.timestamp),
				};
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

function collectSavedLine(line: string, messages: Json[]): void {
	if (!line) return;
	let entry: unknown;
	try {
		entry = JSON.parse(line);
	} catch {
		return;
	}
	const message = messageOfEntry(entry);
	if (message) messages.push(message);
}

/**
 * Find an open string value in the first 32 KiB. Only a value after a colon is eligible: shortening a key could
 * change the object. capMessage cuts or omits every string value longer than the budget, including strings inside
 * serialized tool arguments. Keeping more than the budget also preserves every size decision in the fallback.
 */
function previewStringStart(first: string): number {
	const head = first.slice(0, 32 * 1024);
	let open = -1;
	for (let quote = head.indexOf('"'); quote >= 0; quote = head.indexOf('"', quote + 1)) {
		if (open < 0) {
			open = quote;
			continue;
		}
		let slash = quote;
		while (slash > open && head.charCodeAt(slash - 1) === 0x5c) slash--;
		if ((quote - slash) % 2 === 0) open = -1;
	}
	let before = open - 1;
	while (before >= 0) {
		const c = head.charCodeAt(before);
		if (c === 0x3a) return open + 1;
		if (c !== 0x20 && c !== 0x09 && c !== 0x0a && c !== 0x0d) break;
		before--;
	}
	return -1;
}

/** Number of decoded code units in an entire JSON string body, or -1 for a delimiter or invalid escape. */
function stringBodyLength(piece: string): number {
	try {
		const value = JSON.parse(`"${piece}"`);
		return typeof value === "string" ? value.length : -1;
	} catch {
		return -1;
	}
}

interface PartialSavedLine {
	decoder: StringDecoder;
	pieces: string[];
	readBuffer?: Buffer;
	skipping?: boolean;
	pendingEscape?: Buffer;
}

/** A connection with a fixed cap can discard the middle of a string once its retained prefix is itself oversized.
 * A delimiter, invalid escape or escape split across reads ends the shortcut. Keep that piece and the rest verbatim
 * so the regular parser still validates the complete structure, including later keys and duplicate properties. */
function appendSavedPiece(line: PartialSavedLine, bytes: Buffer, limits: SubagentStreamLimits, final = false): void {
	if (line.skipping) {
		const scan = nativeByteScans()?.jsonStringBodyEnd;
		if (scan) {
			if (line.pendingEscape) {
				bytes = Buffer.concat([line.pendingEscape, bytes]);
				line.pendingEscape = undefined;
			}
			const end = scan(bytes);
			if (end === bytes.length) return;
			if (!final && bytes[end] === 0x5c && bytes.length - end < 6) {
				// A JSON escape occupies up to six ASCII bytes. Validate it with the next read.
				line.pendingEscape = Buffer.from(bytes.subarray(end));
				return;
			}
			// The retained suffix begins at an ASCII delimiter, control or backslash. Any split UTF-8
			// code point before it belongs solely to the already capped string.
			bytes = bytes.subarray(end);
			line.skipping = false;
		}
	}
	const piece = final ? line.decoder.end(bytes) : line.decoder.write(bytes);
	if (line.skipping) {
		if (stringBodyLength(piece) >= 0) return;
		line.skipping = false;
	}
	line.pieces.push(piece);
	// Inspect one disk block, or two if the record started near the end of the previous block.
	const count = line.pieces.length;
	if (final || count > 2 || line.skipping !== undefined || !Object.isFrozen(limits)) return;
	const minLength = Math.max(1024, limits.maxMessageBytes);
	const first = line.pieces[0]!;
	if (count === 1 && first.length <= minLength) return;
	line.skipping = false;
	const prefix = count === 1 ? first : first + piece;
	if (!(prefix.length > minLength)) return;
	const start = previewStringStart(prefix);
	if (start >= 0) line.skipping = stringBodyLength(prefix.slice(start)) > minLength;
}

const CUT_NOTE = "\n… (cut for the phone)";

function cut(text: string, max: number): string {
	return text.length > max ? text.slice(0, Math.max(0, max)) + CUT_NOTE : text;
}

function jsonBytes(value: unknown): number {
	return Buffer.byteLength(JSON.stringify(value) ?? "");
}

/** [value] in about [max] characters: a long string is cut, and a large array or object becomes its JSON, cut. */
function capValue(value: unknown, max: number): unknown {
	if (typeof value === "string") return cut(value, max);
	if (typeof value !== "object" || value === null) return value;
	const json = JSON.stringify(value);
	return json.length > max ? cut(json, max) : value;
}

/** A content block in about [share] characters: text, thinking and each tool argument cut, an image dropped. */
function capBlock(block: unknown, share: number): unknown {
	if (!isRecord(block)) return capValue(block, share);
	if (block.type === "text" && typeof block.text === "string") return { ...block, text: cut(block.text, share) };
	if (block.type === "thinking" && typeof block.thinking === "string") {
		return { ...block, thinking: cut(block.thinking, share) };
	}
	if (block.type === "image") return { type: "text", text: "(image)" };
	if (block.type === "toolCall") {
		if (!isRecord(block.arguments)) return { ...block, arguments: capValue(block.arguments, share) };
		const args: Json = {};
		for (const [key, value] of Object.entries(block.arguments)) args[key] = capValue(value, share);
		return { ...block, arguments: args };
	}
	return block;
}

/**
 * [message] as at most [maxBytes] of JSON: long text, thinking and tool arguments (nested values too) are cut, images
 * and `details` dropped. When that is not enough (many blocks or argument keys, wide characters), only the message's
 * small top-level fields and its text are kept, cut to fit. Messages that fit are returned unchanged.
 */
export function capMessage(message: Json, maxBytes: number): Json {
	return jsonBytes(message) <= maxBytes ? message : capOversizedMessage(message, maxBytes);
}

function capOversizedMessage(message: Json, maxBytes: number): Json {
	const capped: Json = { ...message };
	delete capped.details;
	const content = message.content;
	if (typeof content === "string") {
		capped.content = cut(content, Math.floor(maxBytes / 2));
	} else if (Array.isArray(content)) {
		const share = Math.max(1024, Math.floor(maxBytes / 2 / Math.max(1, content.length)));
		capped.content = content.map((block) => capBlock(block, share));
	}
	if (typeof message.summary === "string") capped.summary = cut(message.summary, Math.floor(maxBytes / 2));
	if (jsonBytes(capped) <= maxBytes) return capped;
	const small: Json = {};
	for (const [key, value] of Object.entries(capped)) {
		if (key === "content" || key === "summary") continue;
		if (typeof value === "string") small[key] = cut(value, 256);
		else if (typeof value === "number" || typeof value === "boolean") small[key] = value;
		else if (isRecord(value) && JSON.stringify(value).length <= 1024) small[key] = value;
	}
	const text = typeof message.summary === "string" ? message.summary : textOf(content);
	// JSON takes at most 6 bytes per UTF-16 unit (`\uXXXX`).
	const room = Math.max(0, Math.floor((maxBytes - jsonBytes(small) - 64) / 6) - CUT_NOTE.length);
	if (typeof message.summary === "string") small.summary = cut(text, room);
	else small.content = typeof content === "string" ? cut(text, room) : [{ type: "text", text: cut(text, room) }];
	return small;
}

/** A parsed file record has plain JSON values. Each UTF-16 unit of a string needs at least one serialized byte. */
function savedMessageSize(message: Json, maxBytes: number): number {
	const content = message.content;
	if (typeof content === "string" && content.length > maxBytes) return content.length;
	if (typeof message.summary === "string" && message.summary.length > maxBytes) return message.summary.length;
	if (Array.isArray(content)) {
		for (const block of content) {
			if (!isRecord(block)) continue;
			const value = block.type === "text" ? block.text : block.type === "thinking" ? block.thinking : block.data;
			if (typeof value === "string" && value.length > maxBytes) return value.length;
		}
	}
	return jsonBytes(message);
}

/** [value] in at most [maxBytes] of JSON: as it is when it fits, else cut (an array or object as its JSON). */
function fitJson(value: unknown, maxBytes: number): unknown {
	if (!(typeof value === "string" && value.length > maxBytes) && jsonBytes(value) <= maxBytes) return value;
	const capped = capValue(value, Math.floor(maxBytes / 2));
	if (jsonBytes(capped) <= maxBytes) return capped;
	const text = typeof value === "string" ? value : (JSON.stringify(value) ?? "");
	// JSON takes at most 6 bytes per UTF-16 unit (`\uXXXX`).
	return cut(text, Math.max(0, Math.floor((maxBytes - 2) / 6) - CUT_NOTE.length));
}

/**
 * A `message_update` event in at most about [maxBytes] of JSON. Its large parts (a delta, a block's content, a tool
 * call's arguments, a final message or error) get a fifth each; one of them is large at a time.
 */
export function capUpdate(event: Json, maxBytes: number): Json {
	const inner = event.assistantMessageEvent;
	if (!isRecord(inner) || jsonBytes(event) <= maxBytes) return event;
	return capOversizedUpdate(event, inner, maxBytes);
}

function capOversizedUpdate(event: Json, update: Json, maxBytes: number): Json {
	const share = Math.floor(maxBytes / 5);
	const inner: Json = { ...update };
	for (const key of ["delta", "content"]) if (inner[key] !== undefined) inner[key] = fitJson(inner[key], share);
	if (isRecord(inner.toolCall)) {
		const call: Json = {};
		for (const [key, value] of Object.entries(inner.toolCall))
			call[key] = fitJson(value, key === "arguments" ? share : 1024);
		inner.toolCall = call;
	}
	for (const key of ["message", "error"]) if (isRecord(inner[key])) inner[key] = capMessage(inner[key], share);
	return { ...event, assistantMessageEvent: inner };
}

/** One client's view of one subagent run. */
class SubagentStream {
	readonly runId: string;
	private readonly owner: SubagentStreams;
	private run: RunInfo;
	/** See `otherAgents`. */
	private others: Set<string>;
	private readonly parentFile: string | undefined;
	private file: string | undefined;
	private live: AgentSession | undefined;
	private unsubscribeLive: (() => void) | undefined;
	private offset = 0;
	private fileIdentity?: FileIdentity;
	private unwatch?: () => void;
	/** Pieces of an unfinished JSONL record, joined once its newline arrives. */
	private partialLine?: PartialSavedLine;
	private lineContinuation?: ReturnType<typeof setImmediate>;
	/** For a live session without a file: messages already sent. */
	private sentMessages = 0;
	private resetPending = true;
	/**
	 * True once the phone has every saved message (the file read to its end). Until then live events are not sent: an
	 * older saved message arriving after them would clear the phone's copy of the streaming one.
	 */
	private caughtUp = false;
	/** Set by `stop()`: a poll deferred before it does nothing, so a stopped stream never listens again. */
	private stopped = false;
	private lastScan = 0;
	// subscribe() has just read the parent's runs.
	private lastRefresh = Date.now();
	/**
	 * Live events not sent yet, in order. A `boundary` marks where a streamed assistant message ended: the events after
	 * it wait until that message's saved copy has been sent (`sendEntries`), so the phone gets them in the order they
	 * happened. A boundary whose saved message does not come within `BOUNDARY_WAIT_MS` is dropped.
	 */
	private queue: Array<{ json: string; bytes: number } | { boundary: number }> = [];
	private eventTimer: ReturnType<typeof setTimeout> | undefined;

	constructor(
		owner: SubagentStreams,
		runId: string,
		run: RunInfo,
		others: Set<string>,
		parentFile: string | undefined,
	) {
		this.owner = owner;
		this.runId = runId;
		this.run = run;
		this.others = others;
		this.parentFile = parentFile;
	}

	/** The file this stream reads, once found. */
	get boundFile(): string | undefined {
		return this.file;
	}

	/** True when the run's agent id is known, so its session is found by name and not guessed from the prompt. */
	get identified(): boolean {
		return this.run.agentId !== undefined;
	}

	stop(): void {
		this.stopped = true;
		this.unwatch?.();
		this.unwatch = undefined;
		this.partialLine = undefined;
		clearImmediate(this.lineContinuation);
		this.lineContinuation = undefined;
		this.clearEvents();
		this.unsubscribeLive?.();
		this.unsubscribeLive = undefined;
		this.live = undefined;
	}

	poll(snapshot?: StreamPoll): void {
		if (this.stopped) return;
		if (this.lineContinuation) {
			clearImmediate(this.lineContinuation);
			this.lineContinuation = undefined;
		}
		try {
			this.refresh(snapshot);
			this.bindLive();
			if (!this.file && !this.live) this.scan(snapshot);
			if (this.file) this.readFile();
			else if (this.live) this.readLiveMessages();
		} catch {
			// A file removed or unreadable for a moment: the next poll tries again.
		}
	}

	/**
	 * While the run is matched by its prompt, reads the parent's messages again (once a second): when the result names
	 * the agent and the session found by prompt is another agent's, the stream lets go of it and finds the right one.
	 */
	private refresh(snapshot?: StreamPoll): void {
		if (this.run.agentId) return;
		const now = Date.now();
		if (now - this.lastRefresh < SCAN_MS) return;
		this.lastRefresh = now;
		const runs = this.owner.runs(this.parentFile, snapshot);
		const run = runs?.get(this.runId);
		if (!runs || !run) return;
		this.run = run;
		this.others = otherAgents(runs, this.runId);
		if (!run.agentId || !this.file) return;
		const name = this.live ? this.live.sessionName : this.owner.header(this.file)?.name;
		if (name !== undefined && !matches(run, { name }, this.others)) this.unbind();
	}

	/** Forgets the session found so far; the next batch the phone gets starts over (`reset`). */
	private unbind(): void {
		this.unwatch?.();
		this.unwatch = undefined;
		this.fileIdentity = undefined;
		this.unsubscribeLive?.();
		this.unsubscribeLive = undefined;
		this.live = undefined;
		this.file = undefined;
		this.offset = 0;
		this.partialLine = undefined;
		this.sentMessages = 0;
		this.resetPending = true;
		this.caughtUp = false;
		this.lastScan = 0;
		this.clearEvents();
	}

	/** The agent's session open in this process, if it is: its streaming message comes from its events. */
	private bindLive(): void {
		const open = liveSessions();
		if (this.live && !open.includes(this.live)) {
			this.unsubscribeLive?.();
			this.unsubscribeLive = undefined;
			this.live = undefined;
			if (this.file && this.fileIdentity) this.followWrites(this.file, this.fileIdentity);
		}
		if (this.live || !this.parentFile) return;
		// Newest first: of two sessions with the same prompt, the later one is the later run's.
		for (const session of open.reverse()) {
			const header = session.sessionManager.getHeader();
			if (header?.parentSession !== this.parentFile) continue;
			if (this.file && session.sessionFile !== this.file) continue;
			if (!this.file && this.owner.taken(session.sessionFile, this)) continue;
			// The session of the file already found is this run's. Otherwise it is matched like a file; after a compaction
			// its messages no longer hold the first prompt, which its saved file still does.
			if (!this.file) {
				const firstUser = session.messages.find((message) => message.role === "user");
				const candidate: Candidate = {
					name: session.sessionName,
					firstUser: firstUser
						? textOf(firstUser.content)
						: session.sessionFile
							? this.owner.header(session.sessionFile)?.firstUser
							: undefined,
					created: parseTime(header.timestamp),
				};
				if (!matches(this.run, candidate, this.others)) continue;
			}
			this.live = session;
			this.unwatch?.();
			this.unwatch = undefined;
			this.file ??= session.sessionFile;
			this.unsubscribeLive = session.subscribe((event) => this.onLiveEvent(event));
			if (this.caughtUp) this.sendSnapshot();
			return;
		}
	}

	/** Joined while tools run or a message streams: starts the phone's copy from what is happening now. */
	private sendSnapshot(): void {
		// Tools running now (their starts came before the phone had caught up), with the name and arguments of their call.
		const running = this.live?.agent.state.pendingToolCalls;
		if (this.live && running && running.size > 0) {
			const calls = new Map<string, Json>();
			for (const message of this.live.messages) {
				if (message.role !== "assistant" || !Array.isArray(message.content)) continue;
				for (const block of message.content as unknown[]) {
					if (isRecord(block) && block.type === "toolCall" && typeof block.id === "string")
						calls.set(block.id, block);
				}
			}
			for (const toolCallId of running) {
				const call = calls.get(toolCallId);
				this.queueEvent({
					type: "tool_execution_start",
					toolCallId,
					toolName: typeof call?.name === "string" ? call.name : "",
					args: fitJson(call?.arguments ?? {}, Math.floor(this.owner.limits.maxMessageBytes / 2)),
				});
			}
		}
		const streaming = this.live?.agent.state.streamingMessage;
		if (this.live?.isStreaming && streaming?.role === "assistant") {
			this.queueEvent({
				type: "message_start",
				message: capMessage(streaming as unknown as Json, this.owner.limits.maxMessageBytes),
			});
		}
	}

	/** The phone has every saved message now: live events follow, from a snapshot of the streaming message. */
	private markCaughtUp(): void {
		if (this.caughtUp) return;
		this.caughtUp = true;
		this.sendSnapshot();
	}

	private scan(snapshot?: StreamPoll): void {
		const now = Date.now();
		if (!this.parentFile || now - this.lastScan < SCAN_MS) return;
		this.lastScan = now;
		const candidates: Array<{ path: string; mtime: number }> = [];
		for (const path of this.owner.candidateFiles(this.parentFile, snapshot)) {
			const known = this.owner.cachedHeader(path);
			if (known && (known.parent !== this.parentFile || !matches(this.run, known, this.others))) continue;
			if (this.owner.taken(path, this)) continue;
			candidates.push({ path, mtime: statSync(path, { throwIfNoEntry: false })?.mtimeMs ?? 0 });
		}
		candidates.sort((a, b) => b.mtime - a.mtime);
		for (const { path } of candidates) {
			const header = this.owner.header(path);
			if (!header || header.parent !== this.parentFile) continue;
			if (matches(this.run, header, this.others)) {
				this.file = path;
				return;
			}
		}
	}

	private appendPartialLine(bytes: Buffer): void {
		this.partialLine ??= { decoder: new StringDecoder("utf8"), pieces: [] };
		appendSavedPiece(this.partialLine, bytes, this.owner.limits);
	}

	private scheduleRead(): void {
		if (this.lineContinuation || this.stopped) return;
		this.lineContinuation = setImmediate(() => {
			this.lineContinuation = undefined;
			this.poll();
		});
	}

	private followWrites(file: string, identity: FileIdentity): void {
		this.unwatch?.();
		this.unwatch = this.owner.watchFile(file, identity, () => {
			if (this.caughtUp) this.scheduleRead();
		});
	}

	private readFile(): void {
		const file = this.file;
		if (!file) return;
		const stat = statSync(file, { throwIfNoEntry: false });
		if (!stat) return;
		const { dev, ino, size } = stat;
		const replaced = this.fileIdentity && (this.fileIdentity.dev !== dev || this.fileIdentity.ino !== ino);
		if (!this.fileIdentity || replaced) {
			this.fileIdentity = { dev, ino };
			if (!this.live && (!this.unwatch || WATCH_INODE)) this.followWrites(file, this.fileIdentity);
		}
		if (size < this.offset || replaced) {
			// Rewritten (a new branch): read it again.
			this.offset = 0;
			this.partialLine = undefined;
			this.resetPending = true;
			this.caughtUp = false;
			this.clearEvents();
		}
		if (size === this.offset && !this.resetPending) return;
		const messages: Json[] = [];
		if (size > this.offset) {
			const fd = openSync(file, "r");
			let data: Buffer;
			try {
				const opened = fstatSync(fd);
				if (opened.ino !== ino || opened.dev !== dev) {
					this.scheduleRead();
					return;
				}
				const length = Math.min(READ_CHUNK, size - this.offset);
				const buffer = this.partialLine?.readBuffer ?? Buffer.allocUnsafe(length);
				const read = readSync(fd, buffer, 0, length, this.offset);
				this.offset += read;
				data = buffer.subarray(0, read);
			} finally {
				closeSync(fd);
			}
			const end = data.lastIndexOf(0x0a);
			if (end >= 0) {
				let start = 0;
				if (this.partialLine) {
					const first = data.indexOf(0x0a);
					appendSavedPiece(this.partialLine, data.subarray(0, first), this.owner.limits, true);
					const line = this.partialLine.pieces.join("");
					this.partialLine = undefined;
					collectSavedLine(line, messages);
					start = first + 1;
				}
				if (start < end) {
					for (const line of data.toString("utf8", start, end).split("\n")) collectSavedLine(line, messages);
				}
				if (end + 1 < data.length) this.appendPartialLine(data.subarray(end + 1));
			} else if (data.length > 0) {
				this.appendPartialLine(data);
				// A complete record may already be on disk. Yield between blocks without waiting a poll interval
				// for every block of a single line. Ordinary batches of complete records keep their usual pacing.
				if (this.offset < size) this.scheduleRead();
			}
			// The decoder owns the text and any unfinished UTF-8 sequence. The next block can overwrite these bytes.
			if (this.partialLine && data.length === READ_CHUNK) this.partialLine.readBuffer = data;
		}
		this.sendEntries(messages, "file");
		if (this.offset >= size) this.markCaughtUp();
	}

	private readLiveMessages(): void {
		const all = this.live?.messages ?? [];
		if (all.length < this.sentMessages) {
			this.sentMessages = 0;
			this.resetPending = true;
			this.caughtUp = false;
			this.clearEvents();
		}
		const fresh = all.slice(this.sentMessages).filter((message) => message.role !== "system") as unknown as Json[];
		this.sentMessages = all.length;
		this.sendEntries(fresh, "live");
		this.markCaughtUp();
	}

	private sendEntries(messages: Json[], source: "file" | "live"): void {
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
			// The saved copy of a message seen streaming: the batch so far, then the events up to its end, go first (a
			// phone given the saved answer before them would start a live copy of it again).
			if (!this.resetPending && message.role === "assistant" && this.queue.some((item) => "boundary" in item)) {
				if (batch.length > 0) flush();
				this.flushEvents();
				this.queue.shift();
				// The wait for this saved message is over. A later boundary has its own deadline.
				clearTimeout(this.eventTimer);
				this.eventTimer = undefined;
			}
			const size = source === "file" ? savedMessageSize(message, max) : jsonBytes(message);
			const capped = size <= max ? message : capOversizedMessage(message, max);
			bytes += source === "file" && capped === message ? size : jsonBytes(capped);
			batch.push(capped);
			if (bytes >= ENTRIES_BATCH_BYTES) flush();
		}
		if (batch.length > 0 || this.resetPending) flush();
		// The events that came after the saved messages.
		this.flushEvents();
		this.armTimer();
	}

	private onLiveEvent(event: AgentSessionEvent): void {
		// Still sending saved messages: the snapshot taken once they are all sent covers what streams meanwhile.
		if (!this.caughtUp) {
			if (event.type === "message_end") setImmediate(() => this.poll());
			return;
		}
		switch (event.type) {
			case "message_start":
			case "message_end":
				if (event.message.role !== "assistant") return;
				this.queueEvent({
					type: event.type,
					message: capMessage(event.message as unknown as Json, this.owner.limits.maxMessageBytes),
				});
				// The message is saved right after its listeners ran: read it from the file at once.
				if (event.type === "message_end") {
					if (!this.stopped) this.queue.push({ boundary: Date.now() });
					setImmediate(() => this.poll());
				}
				return;
			case "message_update":
				if (event.message.role !== "assistant") return;
				this.queueEvent(toJsonEvent(event) as unknown as Json);
				return;
			case "tool_execution_start":
				this.queueEvent({
					type: event.type,
					toolCallId: event.toolCallId,
					toolName: event.toolName,
					args: fitJson(event.args, Math.floor(this.owner.limits.maxMessageBytes / 2)),
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

	private queueEvent(event: Json): void {
		if (this.stopped) return;
		if (event.type === "message_update" && isRecord(event.assistantMessageEvent)) {
			const update = event.assistantMessageEvent;
			const max = this.owner.limits.maxMessageBytes;
			// A string longer than the whole budget cannot fit, even before JSON escaping.
			if (!(typeof update.delta === "string" && update.delta.length > max)) {
				const json = JSON.stringify(event);
				const bytes = Buffer.byteLength(json);
				if (bytes <= max) {
					this.enqueueJson(json, bytes);
					return;
				}
			}
			event = capOversizedUpdate(event, update, max);
		}
		this.enqueueJson(JSON.stringify(event));
	}

	private enqueueJson(json: string, bytes = Buffer.byteLength(json)): void {
		// Providers keep mutating usage, content and arguments. Capture exactly the JSON visible at this event.
		this.queue.push({ json, bytes });
		this.armTimer();
	}

	private clearEvents(): void {
		this.queue = [];
		clearTimeout(this.eventTimer);
		this.eventTimer = undefined;
	}

	private armTimer(): void {
		const head = this.queue[0];
		if (this.eventTimer || !head || this.stopped) return;
		// File notifications and the fallback poll deliver a saved message as soon as it arrives.
		// Until then this timer only needs to enforce the boundary's expiry.
		const delay =
			"boundary" in head ? Math.max(1, head.boundary + BOUNDARY_WAIT_MS - Date.now()) : this.owner.eventBatchMs;
		this.eventTimer = setTimeout(() => {
			this.eventTimer = undefined;
			this.flushEvents(true);
			this.armTimer();
		}, delay);
		this.eventTimer.unref?.();
	}

	/**
	 * Sends the queued events up to the first boundary. [timed] (the batch timer): a boundary older than
	 * `BOUNDARY_WAIT_MS` is dropped, so events never wait forever for a saved message that does not come.
	 */
	private flushEvents(timed = false): void {
		let events: string[] = [];
		let bytes = 0;
		const send = () => {
			if (events.length > 0) this.owner.sendEvents(this.runId, events);
			events = [];
			bytes = 0;
		};
		while (this.queue.length > 0) {
			const head = this.queue[0];
			if ("boundary" in head) {
				if (!timed || Date.now() - head.boundary < BOUNDARY_WAIT_MS) break;
			} else {
				// Each event is capped, a burst of them is not: a message holds about `ENTRIES_BATCH_BYTES` of them.
				const size = head.bytes;
				if (events.length > 0 && bytes + size > ENTRIES_BATCH_BYTES) send();
				events.push(head.json);
				bytes += size;
			}
			this.queue.shift();
		}
		send();
	}
}

export interface SubagentStreamsOptions {
	/** The session whose subagents are followed. */
	getSession: () => AgentSession | undefined;
	send: (message: object) => void;
	/** Send an already serialized JSON message through the usual transport. */
	sendJson?: (json: string) => void;
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
	private savedRuns = new WeakMap<AgentSession["sessionManager"], { revision: number; runs: Map<string, RunInfo> }>();
	private readonly watches = new Map<string, { watcher: FSWatcher; files: Map<string, Set<() => void>> }>();
	private headerBuffer?: Buffer;
	private pollTimer?: ReturnType<typeof setInterval>;

	constructor(options: SubagentStreamsOptions) {
		this.options = options;
		this.limits = { ...DEFAULT_SUBAGENT_STREAM_LIMITS, ...options.limits };
		this.pollMs = options.pollMs ?? POLL_MS;
		this.eventBatchMs = options.eventBatchMs ?? EVENT_BATCH_MS;
	}

	/** Wire connections use a fixed preview limit, so readers can release oversized string tails as they arrive. */
	withFixedLimits(): this {
		Object.freeze(this.limits);
		return this;
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
		const runs = this.sessionRuns(session);
		const run = runs.get(runId);
		if (!run) throw new Error(`No subagent run ${runId} in this session`);
		if (this.streams.size >= this.limits.maxStreams) {
			throw new Error(`At most ${this.limits.maxStreams} subagent streams at once`);
		}
		const stream = new SubagentStream(this, runId, run, otherAgents(runs, runId), session.sessionFile);
		this.streams.set(runId, stream);
		if (!this.pollTimer) {
			this.pollTimer = setInterval(() => this.pollStreams(), this.pollMs);
			this.pollTimer.unref?.();
		}
		stream.poll();
	}

	/** Pending runs on the same parent share its history and directory listing during this synchronous poll. */
	private pollStreams(): void {
		const snapshot: StreamPoll = {};
		for (const stream of this.streams.values()) stream.poll(snapshot);
	}

	/** The `Agent` runs of the session phones see now, when it is still the one saved in [parentFile]. */
	runs(parentFile: string | undefined, snapshot?: StreamPoll): Map<string, RunInfo> | undefined {
		const session = this.options.getSession();
		if (!session || session.sessionFile !== parentFile) return undefined;
		if (!snapshot) return this.sessionRuns(session);
		if (snapshot.parent !== session) {
			snapshot.runs = this.sessionRuns(session);
			snapshot.parent = session;
		}
		return snapshot.runs;
	}

	/** Known sessions of other parents can be excluded for every pending run before ranking the remaining files. */
	candidateFiles(parentFile: string, snapshot?: StreamPoll): string[] {
		if (snapshot?.listing?.parentFile === parentFile) return snapshot.listing.paths;
		const dir = dirname(parentFile);
		const paths: string[] = [];
		for (const name of readdirSync(dir)) {
			if (!name.endsWith(".jsonl")) continue;
			const path = join(dir, name);
			if (path === parentFile) continue;
			const known = this.headers.get(path);
			if (!known || known.parent === parentFile) paths.push(path);
		}
		if (snapshot) snapshot.listing = { parentFile, paths };
		return paths;
	}

	/** Keep runs from the saved branch, including compacted calls. Unsaved live messages can change between polls. */
	private sessionRuns(session: AgentSession): Map<string, RunInfo> {
		const manager = session.sessionManager;
		const revision = manager.getProjectionRevision();
		let saved = this.savedRuns.get(manager);
		if (saved?.revision !== revision) {
			const runs = new Map<string, RunInfo>();
			for (const entry of manager.getBranch()) {
				if (entry.type === "message") collectRun(entry.message, runs);
			}
			saved = { revision, runs };
			this.savedRuns.set(manager, saved);
		}
		const runs = new Map(saved.runs);
		for (const message of session.messages) collectRun(message, runs);
		return runs;
	}

	unsubscribe(runId: unknown): boolean {
		if (typeof runId !== "string") return false;
		const stream = this.streams.get(runId);
		if (!stream) return false;
		stream.stop();
		this.streams.delete(runId);
		if (this.streams.size === 0) this.clear();
		return true;
	}

	/** Stops every stream: the client left, or the session it showed was replaced. */
	clear(): void {
		for (const stream of this.streams.values()) stream.stop();
		this.streams.clear();
		this.headers.clear();
		this.savedRuns = new WeakMap();
		this.headerBuffer = undefined;
		clearInterval(this.pollTimer);
		this.pollTimer = undefined;
	}

	send(message: object): void {
		this.options.send(message);
	}

	/** Queue entries are complete JSON values serialized at event time. Only the envelope is new. */
	sendEvents(runId: string, events: string[]): void {
		const json = `{"type":"subagent_events","runId":${JSON.stringify(runId)},"events":[${events.join(",")}]}`;
		if (this.options.sendJson) this.options.sendJson(json);
		else this.send(JSON.parse(json));
	}

	/** Share watches by inode generation on Linux, or by containing directory on other platforms. */
	watchFile(file: string, identity: FileIdentity, changed: () => void): (() => void) | undefined {
		const path = WATCH_INODE ? file : dirname(file);
		const key = WATCH_INODE ? `${path}\0${identity.dev}:${identity.ino}` : path;
		const name = basename(file);
		let entry = this.watches.get(key);
		if (!entry) {
			const files = new Map<string, Set<() => void>>();
			try {
				const watcher = watch(path, { persistent: false }, (_event, filename) => {
					if (!WATCH_INODE && filename) {
						const listeners = files.get(filename);
						if (listeners) for (const notify of listeners) notify();
					} else {
						for (const listeners of files.values()) for (const notify of listeners) notify();
					}
				});
				watcher.on("error", () => watcher.close());
				entry = { watcher, files };
				this.watches.set(key, entry);
			} catch {
				// The periodic poll still catches writes on filesystems without notifications.
				return undefined;
			}
		}
		let listeners = entry.files.get(name);
		if (!listeners) {
			listeners = new Set();
			entry.files.set(name, listeners);
		}
		listeners.add(changed);
		return () => {
			if (!listeners.delete(changed) || listeners.size) return;
			entry.files.delete(name);
			if (entry.files.size) return;
			entry.watcher.close();
			this.watches.delete(key);
		};
	}

	/**
	 * True when [file] is read by another stream of this client, so a run matched by its prompt leaves it alone (two
	 * runs with the same prompt). A run whose agent id is known may share it: a resumed run reads its agent's file too.
	 */
	taken(file: string | undefined, by: SubagentStream): boolean {
		if (!file || by.identified) return false;
		for (const stream of this.streams.values()) if (stream !== by && stream.boundFile === file) return true;
		return false;
	}

	/** A known header can rule out an unrelated file before reading its modification time. */
	cachedHeader(file: string): Header | undefined {
		return this.headers.get(file);
	}

	/** A session file's header, cached once its first prompt is known (until then it is read again). */
	header(file: string): Header | undefined {
		const cached = this.headers.get(file);
		if (cached) return cached;
		this.headerBuffer ??= Buffer.allocUnsafe(HEADER_BYTES);
		const header = readHeader(file, this.headerBuffer);
		if (header?.firstUser !== undefined) this.headers.set(file, header);
		return header;
	}
}
