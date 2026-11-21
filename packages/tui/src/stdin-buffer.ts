/**
 * StdinBuffer buffers input and emits complete sequences.
 *
 * This is necessary because stdin data events can arrive in partial chunks,
 * especially for escape sequences like mouse events. Without buffering,
 * partial sequences can be misinterpreted as regular keypresses.
 *
 * For example, the mouse SGR sequence `\x1b[<35;20;5m` might arrive as:
 * - Event 1: `\x1b`
 * - Event 2: `[<35`
 * - Event 3: `;20;5m`
 *
 * The buffer accumulates these until a complete sequence is detected.
 * Call the `process()` method to feed input data.
 *
 * Based on code from OpenTUI (https://github.com/anomalyco/opentui)
 * MIT License - Copyright (c) 2025 opentui
 */

import { EventEmitter } from "events";

const ESC = "\x1b";
const DEFAULT_SEQUENCE_TIMEOUT_MS = 50;
const DEFAULT_ESCAPE_TIMEOUT_MS = 10;
const BRACKETED_PASTE_START = "\x1b[200~";
const BRACKETED_PASTE_END = "\x1b[201~";
// Color and program-status OSC replies, XTVersion, and Kitty graphics replies.
// Until their header is complete, the input may be batched legacy Alt keystrokes.
const TERMINAL_REPLY_HEADER_PATTERN = /^\x1b(?:\](?:1[01];|4;\d{1,3};|7501;\?)|P>\||_G(?:[a-z]=\d+,)*[a-z]=\d+;)/;

function isControlString(data: string): boolean {
	return data.startsWith(`${ESC}]`) || data.startsWith(`${ESC}P`) || data.startsWith(`${ESC}_`);
}

const SGR_MOUSE = /\x1b\[<\d+;\d+;\d+[Mm]/y;

function parseUnmodifiedKittyPrintableCodepoint(sequence: string): number | undefined {
	const match = sequence.match(/^\x1b\[(\d+)(?::\d*)?(?::\d+)?u$/);
	if (!match) return undefined;

	const codepoint = parseInt(match[1]!, 10);
	return codepoint >= 32 ? codepoint : undefined;
}

function extractCompleteSequences(buffer: string): { sequences: string[]; remainder: string } {
	const sequences: string[] = [];
	let pos = 0;

	while (pos < buffer.length) {
		if (buffer[pos] !== ESC) {
			sequences.push(buffer[pos++]!);
			continue;
		}
		const kind = buffer[pos + 1];
		if (kind === undefined) break;
		let end = pos + 2;
		if (kind === "[") {
			if (buffer[end] === "M") {
				end = pos + 6; // X10 mouse embeds three raw bytes, even when they look like a key.
			} else if (buffer[end] === "<") {
				SGR_MOUSE.lastIndex = pos;
				if (!SGR_MOUSE.test(buffer)) break;
				end = SGR_MOUSE.lastIndex;
			} else {
				while (end < buffer.length) {
					const code = buffer.charCodeAt(end);
					if (code >= 0x40 && code <= 0x7e) break;
					end++;
				}
				end++;
			}
		} else if (kind === "]" || kind === "P" || kind === "_") {
			// A recognized terminal reply can be cancelled. Legacy Alt keys that share
			// these prefixes retain their timeout and do not consume a following key.
			const reply = TERMINAL_REPLY_HEADER_PATTERN.test(pos === 0 ? buffer : buffer.slice(pos));
			let cancelled = false;
			for (; end < buffer.length; end++) {
				const char = buffer[end];
				if (reply && (char === "\x18" || char === "\x1a")) {
					end++;
					cancelled = true;
					break;
				}
				if (char === ESC) {
					if (buffer[end + 1] === "\\") {
						end++; // include both bytes of ST
						break;
					}
					if (reply && end + 1 < buffer.length) {
						cancelled = true; // parse the new ESC on the next iteration
						break;
					}
				}
				if (kind === "]" && char === "\x07") break;
			}
			if (cancelled) {
				pos = end;
				continue;
			}
			end++;
		} else if (kind === "O") {
			end++; // SS3
		} else if (kind === ESC && end < buffer.length && "[]OP_".includes(buffer[end]!)) {
			// WezTerm may send Escape followed by a separate Kitty key-release sequence.
			end--;
		}
		if (end > buffer.length) break;
		sequences.push(buffer.slice(pos, end));
		pos = end;
	}
	if (pos < buffer.length) return { sequences, remainder: buffer.slice(pos) };

	return { sequences, remainder: "" };
}

export type StdinBufferOptions = {
	/**
	 * Maximum time to wait for an incomplete keyboard or mouse sequence
	 * (default: 50ms). Strings with a recognized terminal reply header wait for
	 * their terminator instead, so delayed replies cannot become keypresses.
	 * Ambiguous legacy Alt-key input still uses this timeout.
	 */
	timeout?: number;
	/**
	 * Maximum time to wait after a lone ESC before treating it as Escape
	 * (default: 10ms). Increase for high-latency Alt+key input (SSH).
	 */
	escapeTimeout?: number;
};

export type StdinBufferEventMap = {
	data: [string];
	paste: [string];
};

/**
 * Buffers stdin input and emits complete sequences via the 'data' event.
 * Handles partial escape sequences that arrive across multiple chunks.
 */
export class StdinBuffer extends EventEmitter<StdinBufferEventMap> {
	private buffer: string = "";
	private timeout: ReturnType<typeof setTimeout> | null = null;
	private readonly timeoutMs: number;
	private readonly escapeTimeoutMs: number;
	private pasteMode: boolean = false;
	/**
	 * The paste so far, as it came in, and its last characters, which the end sequence may begin in. A paste comes in
	 * chunks of a few kilobytes: joining it and searching all of it for the end with each chunk took seconds for a paste
	 * of megabytes.
	 */
	private pasteChunks: string[] = [];
	private pasteTail: string = "";
	private pendingKittyPrintableCodepoint: number | undefined;

	constructor(options: StdinBufferOptions = {}) {
		super();
		this.timeoutMs = options.timeout ?? DEFAULT_SEQUENCE_TIMEOUT_MS;
		this.escapeTimeoutMs = options.escapeTimeout ?? DEFAULT_ESCAPE_TIMEOUT_MS;
	}

	public process(data: string | Buffer): void {
		// Clear any pending timeout
		if (this.timeout) {
			clearTimeout(this.timeout);
			this.timeout = null;
		}

		// Handle high-byte conversion (for compatibility with parseKeypress)
		// If buffer has single byte > 127, convert to ESC + (byte - 128)
		let str: string;
		if (Buffer.isBuffer(data)) {
			if (data.length === 1 && data[0]! > 127) {
				const byte = data[0]! - 128;
				str = `\x1b${String.fromCharCode(byte)}`;
			} else {
				str = data.toString();
			}
		} else {
			str = data;
		}

		if (str.length === 0 && this.buffer.length === 0) {
			this.emitDataSequence("");
			return;
		}

		this.buffer += str;

		if (this.pasteMode) {
			const chunk = this.buffer;
			this.buffer = "";
			this.continuePaste(chunk);
			return;
		}

		const startIndex = this.buffer.indexOf(BRACKETED_PASTE_START);
		if (startIndex !== -1) {
			if (startIndex > 0) {
				const beforePaste = this.buffer.slice(0, startIndex);
				const result = extractCompleteSequences(beforePaste);
				for (const sequence of result.sequences) {
					this.emitDataSequence(sequence);
				}
			}

			this.pendingKittyPrintableCodepoint = undefined;
			const chunk = this.buffer.slice(startIndex + BRACKETED_PASTE_START.length);
			this.pasteMode = true;
			this.buffer = "";
			this.continuePaste(chunk);
			return;
		}

		const result = extractCompleteSequences(this.buffer);
		this.buffer = result.remainder;

		for (const sequence of result.sequences) {
			this.emitDataSequence(sequence);
		}

		if (this.buffer.length > 0) {
			// Only a recognizable reply header establishes a delimited string.
			// Flushing it would turn a delayed tail into text and an OSC's BEL
			// terminator into Ctrl+G (the external editor shortcut). Other input,
			// including Alt+] followed by text, must retain bounded recovery.
			if (TERMINAL_REPLY_HEADER_PATTERN.test(this.buffer)) return;
			const timeoutMs = this.buffer === ESC ? this.escapeTimeoutMs : this.timeoutMs;
			this.timeout = setTimeout(() => {
				const flushed = this.flush();

				for (const sequence of flushed) {
					if (sequence.length > 2 && isControlString(sequence)) {
						// Recover the Alt key and reparse its tail so batched text,
						// Enter, and control keys are not swallowed as one event.
						this.emitDataSequence(sequence.slice(0, 2));
						this.process(sequence.slice(2));
					} else {
						this.emitDataSequence(sequence);
					}
				}
			}, timeoutMs);
		}
	}

	/** Add a chunk to the paste, and emit the paste once its end sequence has come. */
	private continuePaste(chunk: string): void {
		const window = this.pasteTail + chunk;
		const end = window.indexOf(BRACKETED_PASTE_END);
		if (end === -1) {
			this.pasteChunks.push(chunk);
			this.pasteTail = window.slice(-(BRACKETED_PASTE_END.length - 1));
			return;
		}
		const inChunk = end - this.pasteTail.length;
		if (inChunk > 0) this.pasteChunks.push(chunk.slice(0, inChunk));
		let pastedContent = this.pasteChunks.join("");
		// The delimiter can start in an earlier chunk, leaving up to five bytes to trim.
		if (inChunk < 0) pastedContent = pastedContent.slice(0, inChunk);
		const remaining = chunk.slice(inChunk + BRACKETED_PASTE_END.length);
		this.pasteChunks = [];
		this.pasteTail = "";

		this.pasteMode = false;
		this.pendingKittyPrintableCodepoint = undefined;

		this.emit("paste", pastedContent);

		if (remaining.length > 0) {
			this.process(remaining);
		}
	}

	private emitDataSequence(sequence: string): void {
		const rawCodepoint = sequence.length === 1 ? sequence.codePointAt(0) : undefined;
		if (rawCodepoint !== undefined && rawCodepoint === this.pendingKittyPrintableCodepoint) {
			this.pendingKittyPrintableCodepoint = undefined;
			return;
		}

		this.pendingKittyPrintableCodepoint = sequence.startsWith("\x1b[")
			? parseUnmodifiedKittyPrintableCodepoint(sequence)
			: undefined;
		this.emit("data", sequence);
	}

	flush(): string[] {
		if (this.timeout) {
			clearTimeout(this.timeout);
			this.timeout = null;
		}

		if (this.buffer.length === 0) {
			return [];
		}

		const sequences = [this.buffer];
		this.buffer = "";
		this.pendingKittyPrintableCodepoint = undefined;
		return sequences;
	}

	clear(): void {
		if (this.timeout) {
			clearTimeout(this.timeout);
			this.timeout = null;
		}
		this.buffer = "";
		this.pasteMode = false;
		this.pasteChunks = [];
		this.pasteTail = "";
		this.pendingKittyPrintableCodepoint = undefined;
	}

	getBuffer(): string {
		return this.buffer;
	}

	destroy(): void {
		this.clear();
	}
}
