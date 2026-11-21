import { isUtf8 } from "node:buffer";
import { open } from "node:fs/promises";
import { createOutputFile, type OutputFile } from "../../utils/output-files.ts";
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
	fullOutputPath?: string;
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

	private tempFilePath: string | undefined;
	private tempFile: OutputFile | undefined;
	/** The full output file was asked for and is missing output: not created, or a write to it failed. */
	private tempFileFailed = false;
	/** The full output file reached its size limit: the end of the output is only in memory. */
	private tempFileCapped = false;

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
			this.ensureTempFile();
			this.tempFile?.write(data);
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
		// Clipping drops the partial first line of the retained tail. When the tail is only the end of
		// one over-long line, that is everything, so show the end of that line instead.
		const snapshotText = this.getSnapshotText() || this.tailText;
		const tailTruncation = truncateTail(snapshotText, {
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

		return {
			content: truncation.content,
			truncation,
			fullOutputPath: this.tempFilePath,
		};
	}

	/** Close the full output file. Throws the error of a failed write to it. */
	closeTempFile(): void {
		const file = this.tempFile;
		this.tempFile = undefined;
		if (file?.failed) this.tempFileFailed = true;
		if (file?.capped) this.tempFileCapped = true;
		file?.close();
	}

	/**
	 * The complete output, for callers that can take more than the display snapshot. Call after
	 * `finish()` and `closeTempFile()`. Output longer than `maxBytes` raw bytes keeps its first and
	 * last `maxBytes / 2` bytes around an omission marker.
	 */
	async readFullOutput(maxBytes: number): Promise<FullOutput> {
		if (this.tempFileFailed) {
			return this.readTailOutput(maxBytes);
		}
		if (!this.tempFilePath) {
			return { content: new TextDecoder().decode(Buffer.concat(this.rawChunks)), truncated: false };
		}
		let file: Awaited<ReturnType<typeof open>>;
		try {
			file = await open(this.tempFilePath, "r");
		} catch {
			return this.readTailOutput(maxBytes);
		}
		try {
			const size = (await file.stat()).size;
			if (size <= maxBytes && !this.tempFileCapped) {
				return { content: new TextDecoder().decode(await file.readFile()), truncated: false };
			}
			const headBytes = Math.floor(maxBytes / 2);
			const tailBytes = maxBytes - headBytes;
			const head = Buffer.alloc(headBytes);
			const { bytesRead } = await file.read(head, 0, headBytes, 0);
			// Cut at character boundaries: streaming decode holds back an incomplete trailing sequence,
			// and the tail skips leading continuation bytes.
			const headText = new TextDecoder().decode(head.subarray(0, bytesRead), { stream: true });
			if (this.tempFileCapped) {
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

	/** What is kept in memory, the end of the output, for when the full output file cannot be read. */
	private readTailOutput(maxBytes: number): FullOutput {
		const tail = truncateTail(this.getSnapshotText(), { maxLines: Number.MAX_SAFE_INTEGER, maxBytes });
		return { content: tail.content, truncated: true };
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

	private ensureTempFile(): void {
		if (this.tempFilePath || this.tempFileFailed) {
			return;
		}
		const file = createOutputFile(this.tempFilePrefix, ".log");
		if (!file.opened) {
			// Not created (no temp directory, no free descriptor): the output keeps its tail in memory, and says so.
			this.tempFileFailed = true;
			this.rawChunks = [];
			return;
		}
		this.tempFilePath = file.path;
		this.tempFile = file;
		for (const chunk of this.rawChunks) {
			file.write(chunk);
		}
		this.rawChunks = [];
	}
}
