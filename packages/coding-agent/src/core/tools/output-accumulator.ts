import { isUtf8 } from "node:buffer";
import { open } from "node:fs/promises";
import { createOutputFile, type OutputFile, type OutputFileResult } from "../../utils/output-files.ts";
import { nativeByteScans } from "../native-byte-scans.ts";
import { DEFAULT_MAX_BYTES, DEFAULT_MAX_LINES, type TruncationResult, truncateTail } from "./truncate.ts";

export interface OutputAccumulatorOptions {
	maxLines?: number;
	maxBytes?: number;
	tempFilePrefix?: string;
}

export interface OutputSnapshot {
	content: string;
	truncation: TruncationResult;
	/** Set once the full output goes to a file. While output streams in, `saved` means the file is being written. */
	fullOutputFile?: OutputFileResult;
}

export interface FullOutput {
	content: string;
	/** Whether `content` omits part of the output. */
	truncated: boolean;
}

const useBunLineCount = Boolean(process.versions.bun);

function byteLength(text: string): number {
	return Buffer.byteLength(text, "utf-8");
}

/**
 * Incrementally tracks streaming output with bounded memory.
 *
 * Appends handle streaming UTF-8, keep a bounded tail for display snapshots,
 * and open a temp file when the full output needs
 * to be preserved.
 */
export class OutputAccumulator {
	private readonly maxLines: number;
	private readonly maxBytes: number;
	private readonly maxRollingBytes: number;
	private readonly tempFilePrefix: string;
	private readonly decoder = new TextDecoder();

	private rawChunks: Buffer[] = [];
	private tailText = "";
	// In byte mode each code unit holds one UTF-8 byte until a display snapshot is requested.
	private tailIsBytes = false;
	private sawMultibyteText = false;
	private tailBytes = 0;
	private tailStartsAtLineBoundary = true;
	private totalRawBytes = 0;
	private totalDecodedBytes = 0;
	private completedLines = 0;
	// Average code units per LF in the last chunk. Sparse lines only need a few indexOf calls.
	private previousCharsPerLf = Infinity;
	private totalLines = 0;
	private currentLineBytes = 0;
	private hasOpenLine = false;
	private finished = false;

	/** The full output file, kept after it is closed for its result. */
	private tempFile: OutputFile | undefined;

	constructor(options: OutputAccumulatorOptions = {}) {
		this.maxLines = options.maxLines ?? DEFAULT_MAX_LINES;
		this.maxBytes = options.maxBytes ?? DEFAULT_MAX_BYTES;
		this.maxRollingBytes = Math.max(this.maxBytes * 2, 1);
		this.tempFilePrefix = options.tempFilePrefix ?? "pi-output";
	}

	append(data: Buffer): void {
		if (this.finished) {
			throw new Error("Cannot append to a finished output accumulator");
		}

		this.totalRawBytes += data.length;
		this.appendChunk(data);

		if (this.tempFile || this.shouldUseTempFile()) {
			this.ensureTempFile().write(data);
		} else if (data.length > 0) {
			this.rawChunks.push(data);
		}
	}

	finish(): void {
		if (this.finished) {
			return;
		}
		this.finished = true;
		this.appendDecodedText(this.decoder.decode());
		if (this.shouldUseTempFile()) {
			this.ensureTempFile();
		}
	}

	snapshot(options: { persistIfTruncated?: boolean } = {}): OutputSnapshot {
		const tailTruncation = truncateTail(this.getRetainedText(), {
			maxLines: this.maxLines,
			maxBytes: this.maxBytes,
		});
		const truncated = this.totalLines > this.maxLines || this.totalDecodedBytes > this.maxBytes;
		const truncatedBy = truncated
			? (tailTruncation.truncatedBy ?? (this.totalDecodedBytes > this.maxBytes ? "bytes" : "lines"))
			: null;
		const truncation: TruncationResult = {
			...tailTruncation,
			truncated,
			truncatedBy,
			totalLines: this.totalLines,
			totalBytes: this.totalDecodedBytes,
			maxLines: this.maxLines,
			maxBytes: this.maxBytes,
		};

		if (options.persistIfTruncated && truncation.truncated) {
			this.ensureTempFile();
		}

		return { content: truncation.content, truncation, fullOutputFile: this.tempFile?.result };
	}

	/** Close the full output file. Never throws: a failed file shows up as `failed` in later snapshots. */
	closeTempFile(): void {
		this.tempFile?.close();
	}

	/**
	 * The complete output, for callers that can take more than the display snapshot. Call after
	 * `finish()` and `closeTempFile()`. Output longer than `maxBytes` raw bytes keeps its first and
	 * last `maxBytes / 2` bytes around an omission marker. When the temp file could not be saved, only
	 * the retained tail is left: up to its last `maxBytes` bytes, behind the same marker when output is
	 * missing.
	 */
	async readFullOutput(maxBytes: number): Promise<FullOutput> {
		const fullOutputFile = this.tempFile?.result;
		if (!fullOutputFile) {
			return { content: new TextDecoder().decode(Buffer.concat(this.rawChunks)), truncated: false };
		}
		if (fullOutputFile.kind === "failed") {
			return this.readRetainedOutput(maxBytes);
		}
		let file: Awaited<ReturnType<typeof open>>;
		try {
			file = await open(fullOutputFile.path, "r");
		} catch {
			return this.readRetainedOutput(maxBytes);
		}
		const capped = this.tempFile?.capped ?? false;
		try {
			const size = (await file.stat()).size;
			if (size <= maxBytes && !capped) {
				return { content: new TextDecoder().decode(await file.readFile()), truncated: false };
			}
			const headBytes = Math.floor(maxBytes / 2);
			const tailBytes = maxBytes - headBytes;
			const head = Buffer.alloc(headBytes);
			const { bytesRead } = await file.read(head, 0, headBytes, 0);
			// Cut at character boundaries: streaming decode holds back an incomplete trailing sequence,
			// and the tail skips leading continuation bytes.
			const headText = new TextDecoder().decode(head.subarray(0, bytesRead), { stream: true });
			if (capped) {
				// The file stops at its size limit, so its end is not the output's: that is the tail kept in memory.
				const tailText = truncateTail(this.getSnapshotText(), {
					maxLines: Number.MAX_SAFE_INTEGER,
					maxBytes: tailBytes,
				}).content;
				const omitted = this.totalRawBytes - bytesRead - byteLength(tailText);
				return { content: `${headText}\n\n[... ${omitted} bytes omitted ...]\n\n${tailText}`, truncated: true };
			}
			const tail = Buffer.alloc(tailBytes);
			await file.read(tail, 0, tailBytes, size - tailBytes);
			let tailStart = 0;
			while (tailStart < tail.length && (tail[tailStart] & 0xc0) === 0x80) tailStart++;
			const tailText = new TextDecoder().decode(tail.subarray(tailStart));
			const omitted = size - headBytes - tailBytes;
			return { content: `${headText}\n\n[... ${omitted} bytes omitted ...]\n\n${tailText}`, truncated: true };
		} finally {
			await file.close();
		}
	}

	/** The retained tail within `maxBytes`, marked as truncated only when output is missing. */
	private readRetainedOutput(maxBytes: number): FullOutput {
		let bytes = Buffer.from(this.getRetainedText(), "utf-8");
		if (bytes.length > maxBytes) {
			let start = bytes.length - maxBytes;
			while (start < bytes.length && (bytes[start] & 0xc0) === 0x80) start++;
			bytes = bytes.subarray(start);
		}
		const tail = bytes.toString("utf-8");
		const omitted = this.totalDecodedBytes - bytes.length;
		if (omitted === 0) {
			return { content: tail, truncated: false };
		}
		return { content: `[... ${omitted} bytes omitted ...]\n\n${tail}`, truncated: true };
	}

	getLastLineBytes(): number {
		return this.currentLineBytes;
	}

	private appendChunk(data: Buffer): void {
		// UTF-8 decoding can replace a malformed sequence or hold a partial character.
		// Neither operation adds or removes an ASCII LF from this chunk.
		const lineFeeds =
			this.previousCharsPerLf <= 256 && data.length >= 8192 && this.totalRawBytes > this.maxRollingBytes
				? nativeByteScans()?.(data)
				: undefined;
		if (
			(this.sawMultibyteText || this.tailIsBytes) &&
			data.length >= 8192 &&
			this.totalRawBytes > this.maxRollingBytes
		) {
			const start = data.indexOf(0x0a) + 1;
			const end = data.lastIndexOf(0x0a) + 1;
			if (start > 0 && end - start >= 4096 && isUtf8(data.subarray(start, end))) {
				// LF ends a UTF-8 sequence. The streaming decoder handles the initial BOM and
				// split characters at the edges. Valid complete lines already have the exact
				// bytes that decoding and encoding again would produce.
				const head = this.decoder.decode(data.subarray(0, start), { stream: true });
				const tail = this.decoder.decode(data.subarray(end), { stream: true });
				if (!this.tailIsBytes) {
					this.tailText = Buffer.from(this.tailText).toString("latin1");
					this.tailIsBytes = true;
				}
				const text =
					Buffer.from(head).toString("latin1") +
					data.toString("latin1", start, end) +
					Buffer.from(tail).toString("latin1");
				this.appendText(text, text.length, lineFeeds);
				return;
			}
		}
		this.appendDecodedText(this.decoder.decode(data, { stream: true }), lineFeeds);
	}

	private appendDecodedText(text: string, lineFeeds?: number): void {
		if (!text) return;
		if (this.tailIsBytes) {
			const encoded = Buffer.from(text).toString("latin1");
			this.appendText(encoded, encoded.length, lineFeeds);
		} else {
			const bytes = byteLength(text);
			this.sawMultibyteText = bytes !== text.length && !text.includes("\ufffd");
			this.appendText(text, bytes, lineFeeds);
		}
	}

	private appendText(text: string, bytes: number, lineFeeds?: number): void {
		this.totalDecodedBytes += bytes;
		const tailLength = this.tailText.length;
		if (this.tailBytes + bytes > this.maxRollingBytes * 2 && bytes === text.length && this.tailBytes === tailLength) {
			// In byte mode and in ASCII text, indices are UTF-8 byte offsets. When the
			// entire retained suffix is in this chunk, skip joining it with the old tail.
			let start = tailLength + bytes - this.maxRollingBytes;
			while (this.tailIsBytes) {
				const byte = start < tailLength ? this.tailText.charCodeAt(start) : text.charCodeAt(start - tailLength);
				if ((byte & 0xc0) !== 0x80) break;
				start++;
			}
			const before =
				start - 1 < tailLength ? this.tailText.charCodeAt(start - 1) : text.charCodeAt(start - 1 - tailLength);
			this.tailStartsAtLineBoundary = before === 0x0a;
			this.tailText = start >= tailLength ? text.slice(start - tailLength) : this.tailText.slice(start) + text;
			this.tailBytes = tailLength + bytes - start;
		} else {
			this.tailText += text;
			this.tailBytes += bytes;
			if (this.tailBytes > this.maxRollingBytes * 2) {
				this.trimTail();
			}
		}

		let newlines = 0;
		let lastNewline = -1;
		if (lineFeeds !== undefined) {
			newlines = lineFeeds;
			if (newlines > 0) lastNewline = text.lastIndexOf("\n");
		} else if (useBunLineCount && this.previousCharsPerLf <= 16 && text.length >= 8192) {
			// JSC removes matching characters in native code. On short-line logs this
			// avoids a JS-to-native indexOf call per line. Limit the temporary to one chunk.
			newlines = text.length - text.replace(/\n/g, "").length;
			lastNewline = text.lastIndexOf("\n");
		} else {
			for (let i = text.indexOf("\n"); i !== -1; i = text.indexOf("\n", i + 1)) {
				newlines++;
				lastNewline = i;
			}
		}
		this.previousCharsPerLf = text.length / newlines;
		if (newlines === 0) {
			this.currentLineBytes += bytes;
			this.hasOpenLine = true;
		} else {
			this.completedLines += newlines;
			const tail = text.slice(lastNewline + 1);
			this.currentLineBytes = this.tailIsBytes ? tail.length : byteLength(tail);
			this.hasOpenLine = tail.length > 0;
		}
		this.totalLines = this.completedLines + (this.hasOpenLine ? 1 : 0);
	}

	private trimTail(): void {
		const buffer = Buffer.from(this.tailText, "utf-8");
		if (buffer.length <= this.maxRollingBytes) {
			this.tailBytes = buffer.length;
			return;
		}

		let start = buffer.length - this.maxRollingBytes;
		while (start < buffer.length && (buffer[start] & 0xc0) === 0x80) {
			start++;
		}

		this.tailStartsAtLineBoundary = start === 0 ? this.tailStartsAtLineBoundary : buffer[start - 1] === 0x0a;
		this.tailText = buffer.subarray(start).toString("utf-8");
		this.tailBytes = byteLength(this.tailText);
	}

	/**
	 * The retained tail, for display and as the fallback of `readFullOutput()`. Clipping drops the partial
	 * first line of the tail. When the tail is only the end of one over-long line (plus blank lines),
	 * nothing is left, so it keeps the end of that line instead.
	 */
	private getRetainedText(): string {
		const clippedText = this.getSnapshotText();
		if (clippedText.trim().length > 0) return clippedText;
		return this.tailIsBytes ? Buffer.from(this.tailText, "latin1").toString("utf8") : this.tailText;
	}

	private getSnapshotText(): string {
		const firstNewline = this.tailStartsAtLineBoundary ? -1 : this.tailText.indexOf("\n");
		const tail = this.tailText.slice(firstNewline + 1);
		return this.tailIsBytes ? Buffer.from(tail, "latin1").toString("utf8") : tail;
	}

	private shouldUseTempFile(): boolean {
		return (
			this.totalRawBytes > this.maxBytes || this.totalDecodedBytes > this.maxBytes || this.totalLines > this.maxLines
		);
	}

	private ensureTempFile(): OutputFile {
		if (this.tempFile) {
			return this.tempFile;
		}
		// A file that could not be created (no temp directory, no free descriptor) takes no writes and reports why: the
		// output keeps its tail in memory.
		const file = createOutputFile(this.tempFilePrefix, ".log");
		this.tempFile = file;
		for (const chunk of this.rawChunks) {
			file.write(chunk);
		}
		this.rawChunks = [];
		return file;
	}
}
