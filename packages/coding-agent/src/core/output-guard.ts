interface StdoutTakeoverState {
	rawStdoutWrite: (chunk: string, callback?: (error?: Error | null) => void) => boolean;
	rawStderrWrite: (chunk: string, callback?: (error?: Error | null) => void) => boolean;
	originalStdoutWrite: typeof process.stdout.write;
	/** Console methods replaced while stdout is taken over, to put back at the end. */
	originalConsoleMethods: Map<string, unknown>;
}

/**
 * The console methods that print to stdout on Bun without calling `process.stdout.write`
 * (https://github.com/oven-sh/bun/issues/47982). Node's console calls the replaced `process.stdout.write`, so its
 * output goes to stderr; on Bun it went straight to fd 1, into the JSON stream. `trace` and Bun's `write` print to fd 1
 * too and are replaced separately. Bun's time* methods already print to stderr; count/countReset and the group methods
 * are replaced together so their state stays in one console.
 */
const BUN_STDOUT_CONSOLE_METHODS = [
	"log",
	"info",
	"debug",
	"dir",
	"dirxml",
	"table",
	"count",
	"countReset",
	"group",
	"groupCollapsed",
	"groupEnd",
] as const;

let stdoutTakeoverState: StdoutTakeoverState | undefined;

const RAW_STDOUT_RETRY_DELAY_MS = 10;

/**
 * Output that may wait to be written before writers are held back (`waitForRawStdoutBackpressure`). Streaming writes a
 * line per event: holding the agent back for each line's write would cost a promise chain and an await per event.
 */
const RAW_STDOUT_HIGH_WATER_MARK = 64 * 1024;

interface StdoutBatch {
	lines: string[];
	length: number;
	next?: StdoutBatch;
}

let firstBatch: StdoutBatch | undefined;
let lastBatch: StdoutBatch | undefined;
/** Characters queued or in flight. */
let rawStdoutPendingLength = 0;
let rawStdoutWriting = false;
/** A write of the queue is scheduled for when the current task ends. */
let rawStdoutScheduled = false;
let rawStdoutExitHookInstalled = false;
let rawStdoutDrainWaiters: Array<() => void> = [];

function getRawStdoutWrite(): StdoutTakeoverState["rawStdoutWrite"] {
	if (stdoutTakeoverState) {
		return stdoutTakeoverState.rawStdoutWrite;
	}
	return process.stdout.write.bind(process.stdout) as StdoutTakeoverState["rawStdoutWrite"];
}

async function writeRawStdoutChunk(text: string): Promise<void> {
	while (true) {
		try {
			await new Promise<void>((resolve, reject) => {
				try {
					getRawStdoutWrite()(text, (error) => {
						if (error) reject(error);
						else resolve();
					});
				} catch (error) {
					reject(error instanceof Error ? error : new Error(String(error)));
				}
			});
			return;
		} catch (error) {
			const writeError = error instanceof Error ? error : new Error(String(error));
			const code = (writeError as Error & { code?: unknown }).code;
			if (code !== "ENOBUFS" && code !== "EAGAIN" && code !== "EWOULDBLOCK") {
				throw writeError;
			}
			await new Promise<void>((resolve) => setTimeout(resolve, RAW_STDOUT_RETRY_DELAY_MS));
		}
	}
}

export function takeOverStdout(): void {
	if (stdoutTakeoverState) {
		return;
	}

	const rawStdoutWrite = process.stdout.write.bind(process.stdout) as StdoutTakeoverState["rawStdoutWrite"];
	const rawStderrWrite = process.stderr.write.bind(process.stderr) as StdoutTakeoverState["rawStderrWrite"];
	const originalStdoutWrite = process.stdout.write;

	process.stdout.write = ((
		chunk: string | Uint8Array,
		encodingOrCallback?: BufferEncoding | ((error?: Error | null) => void),
		callback?: (error?: Error | null) => void,
	): boolean => {
		if (typeof encodingOrCallback === "function") {
			return rawStderrWrite(String(chunk), encodingOrCallback);
		}
		return rawStderrWrite(String(chunk), callback);
	}) as typeof process.stdout.write;

	stdoutTakeoverState = {
		rawStdoutWrite,
		rawStderrWrite,
		originalStdoutWrite,
		originalConsoleMethods: process.versions.bun ? routeBunConsoleThroughStdoutWrite() : new Map(),
	};
}

/**
 * Point Bun's stdout console methods at a `Console` over `process.stdout`, which calls the replaced write as Node's
 * console does, so the output and its formatting match Node. Bun's `console.write` (raw, no newline) goes the same way.
 */
function routeBunConsoleThroughStdoutWrite(): Map<string, unknown> {
	const target = console as unknown as Record<string, unknown>;
	const originals = new Map<string, unknown>();
	// Made at the first call, not at the takeover: node:console and node:util cost a start ~3.5 M instructions to load
	// (`pi -p` takes stdout over), and most runs never print through the console.
	let nodeConsole: Console | undefined;
	const getNodeConsole = (): Console => {
		nodeConsole ??= new (process.getBuiltinModule("node:console").Console)({
			stdout: process.stdout,
			stderr: process.stderr,
		});
		return nodeConsole;
	};
	for (const name of BUN_STDOUT_CONSOLE_METHODS) {
		originals.set(name, target[name]);
		target[name] = (...data: unknown[]): void => (getNodeConsole()[name] as (...data: unknown[]) => void)(...data);
	}
	// Bun's Console prints a trace's stack without its "Trace: <message>" line; Node prints it to stderr with the line.
	const trace = (...data: unknown[]): void => {
		const error = new Error(process.getBuiltinModule("node:util").format(...data));
		error.name = "Trace";
		Error.captureStackTrace(error, trace);
		getNodeConsole().error(error.stack);
	};
	originals.set("trace", target.trace);
	target.trace = trace;
	if (typeof target.write === "function") {
		originals.set("write", target.write);
		target.write = (...data: Array<string | ArrayBufferView>): number => {
			let bytes = 0;
			for (const chunk of data) {
				const buffer =
					typeof chunk === "string"
						? Buffer.from(chunk)
						: Buffer.from(chunk.buffer, chunk.byteOffset, chunk.byteLength);
				process.stdout.write(buffer.toString());
				bytes += buffer.byteLength;
			}
			return bytes;
		};
	}
	return originals;
}

export function restoreStdout(): void {
	if (!stdoutTakeoverState) {
		return;
	}

	process.stdout.write = stdoutTakeoverState.originalStdoutWrite;
	const target = console as unknown as Record<string, unknown>;
	for (const [name, method] of stdoutTakeoverState.originalConsoleMethods) {
		target[name] = method;
	}
	stdoutTakeoverState = undefined;
}

export function isStdoutTakenOver(): boolean {
	return stdoutTakeoverState !== undefined;
}

/**
 * Write `text` to the real stdout, after the lines written before it. The lines of one task (for example every event of
 * the stream chunks one network read delivered) go out together in one write when the task ends: JSON and RPC modes
 * write a line per streamed delta, and a write per line cost more than the rest of the delta's handling. One write is in
 * flight at a time; small lines that arrive meanwhile share bounded batches. A write that fails with
 * ENOBUFS/EAGAIN/EWOULDBLOCK is retried; any other failure exits the process.
 */
export function writeRawStdout(text: string): void {
	if (text.length === 0) {
		return;
	}
	if (lastBatch && lastBatch.length + text.length <= RAW_STDOUT_HIGH_WATER_MARK) {
		lastBatch.lines.push(text);
		lastBatch.length += text.length;
	} else {
		// A large record owns its write. Joining several of them would retain one
		// large encoded buffer for as long as the consumer takes to drain it.
		const batch = { lines: [text], length: text.length };
		if (lastBatch) lastBatch.next = batch;
		else firstBatch = batch;
		lastBatch = batch;
	}
	rawStdoutPendingLength += text.length;
	if (!rawStdoutWriting && !rawStdoutScheduled) {
		rawStdoutScheduled = true;
		setImmediate(writeScheduledRawStdout);
		if (!rawStdoutExitHookInstalled) {
			rawStdoutExitHookInstalled = true;
			process.on("exit", writeQueuedRawStdoutAtExit);
		}
	}
}

function writeScheduledRawStdout(): void {
	rawStdoutScheduled = false;
	if (!rawStdoutWriting && firstBatch) startRawStdoutWrite();
}

function takeStdoutBatch(): string {
	const batch = firstBatch!;
	firstBatch = batch.next;
	if (!firstBatch) lastBatch = undefined;
	return batch.lines.length === 1 ? batch.lines[0] : batch.lines.join("");
}

/** Submit any pending lines in order if the process exits before the next scheduled write. */
function writeQueuedRawStdoutAtExit(): void {
	if (!firstBatch) return;
	const remaining: string[] = [];
	do {
		remaining.push(takeStdoutBatch());
	} while (firstBatch);
	const text = remaining.length === 1 ? remaining[0] : remaining.join("");
	rawStdoutPendingLength -= text.length;
	try {
		getRawStdoutWrite()(text);
	} catch {
		// Exiting anyway.
	}
}

function startRawStdoutWrite(): void {
	rawStdoutWriting = true;
	writeQueuedRawStdout(takeStdoutBatch());
}

function writeQueuedRawStdout(text: string): void {
	let settled = false;
	let synchronous = true;
	const onWritten = (error?: Error | null): void => {
		if (settled) return;
		settled = true;
		if (error) {
			const code = (error as Error & { code?: unknown }).code;
			if (code === "ENOBUFS" || code === "EAGAIN" || code === "EWOULDBLOCK") {
				setTimeout(() => writeQueuedRawStdout(text), RAW_STDOUT_RETRY_DELAY_MS);
				return;
			}
			queueMicrotask(() => process.exit(1));
			return;
		}
		rawStdoutPendingLength -= text.length;
		if (synchronous && firstBatch) queueMicrotask(advanceRawStdoutQueue);
		else advanceRawStdoutQueue();
	};
	try {
		getRawStdoutWrite()(text, onWritten);
	} catch (error) {
		onWritten(error instanceof Error ? error : new Error(String(error)));
	} finally {
		synchronous = false;
	}
}

function advanceRawStdoutQueue(): void {
	if (firstBatch) {
		startRawStdoutWrite();
		return;
	}
	rawStdoutWriting = false;
	const waiters = rawStdoutDrainWaiters;
	rawStdoutDrainWaiters = [];
	for (const resolve of waiters) resolve();
}

function waitForRawStdoutDrain(): Promise<void> {
	return new Promise((resolve) => rawStdoutDrainWaiters.push(resolve));
}

/**
 * Hold a writer back while more than the high-water mark of output waits to be written. Returns a promise that resolves
 * once everything queued so far is written, or undefined when the writer may go on right away.
 */
export function waitForRawStdoutBackpressure(): Promise<void> | undefined {
	return rawStdoutPendingLength > RAW_STDOUT_HIGH_WATER_MARK ? waitForRawStdoutDrain() : undefined;
}

/** Resolve once everything written so far has reached stdout. */
export async function flushRawStdout(): Promise<void> {
	if (rawStdoutScheduled) writeScheduledRawStdout();
	while (rawStdoutWriting) {
		await waitForRawStdoutDrain();
	}
	await writeRawStdoutChunk("");
}
