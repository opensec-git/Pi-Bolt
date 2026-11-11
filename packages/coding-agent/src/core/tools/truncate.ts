/**
 * Shared truncation utilities for tool outputs.
 *
 * Truncation is based on two independent limits - whichever is hit first wins:
 * - Line limit (default: 2000 lines)
 * - Byte limit (default: 50KB)
 *
 * Never returns partial lines (except bash tail truncation edge case).
 */

export const DEFAULT_MAX_LINES = 2000;
export const DEFAULT_MAX_BYTES = 50 * 1024; // 50KB
export const GREP_MAX_LINE_LENGTH = 500; // Max chars per grep match line

export interface TruncationResult {
	/** The truncated content */
	content: string;
	/** Whether truncation occurred */
	truncated: boolean;
	/** Which limit was hit: "lines", "bytes", or null if not truncated */
	truncatedBy: "lines" | "bytes" | null;
	/** Total number of lines in the original content */
	totalLines: number;
	/** Total number of bytes in the original content */
	totalBytes: number;
	/** Number of complete lines in the truncated output */
	outputLines: number;
	/** Number of bytes in the truncated output */
	outputBytes: number;
	/** Whether the last line was partially truncated (only for tail truncation edge case) */
	lastLinePartial: boolean;
	/** Whether the first line exceeded the byte limit (for head truncation) */
	firstLineExceedsLimit: boolean;
	/** The max lines limit that was applied */
	maxLines: number;
	/** The max bytes limit that was applied */
	maxBytes: number;
}

/**
 * `truncation` for a tool result's details: the same, without `content`, which is the tool result's text already (with a
 * notice after it). Details are kept with the session in memory and in its file, so the text would be kept twice.
 */
export function truncationDetails(truncation: TruncationResult): TruncationResult {
	return { ...truncation, content: "" };
}

export interface TruncationOptions {
	/** Maximum number of lines (default: 2000) */
	maxLines?: number;
	/** Maximum number of bytes (default: 50KB) */
	maxBytes?: number;
}

/**
 * Number of lines in `content`: one per "\n"-separated line, not counting the empty one after a trailing "\n".
 * Counted without splitting: callers truncate output that can be many megabytes to a few kilobytes, and splitting
 * would allocate a string per line of all of it.
 */
function countLines(content: string): number {
	if (content.length === 0) {
		return 0;
	}
	let lines = 1;
	for (let i = content.indexOf("\n"); i !== -1; i = content.indexOf("\n", i + 1)) {
		lines++;
	}
	return content.endsWith("\n") ? lines - 1 : lines;
}

const NON_ASCII = /[\u0080-\uffff]/g;

/** Index of the first non-ASCII character of `content` at or after `from`, or its length if there is none. */
function findNonAscii(content: string, from: number): number {
	NON_ASCII.lastIndex = from;
	return NON_ASCII.exec(content)?.index ?? content.length;
}

/**
 * A copy of `text` that shares no memory with the string it was cut from, so a short result does not keep a large
 * input alive. UTF-16 code units round-trip unchanged, lone surrogates included.
 */
function copyString(text: string): string {
	return Buffer.from(text, "utf16le").toString("utf16le");
}

/**
 * Format bytes as human-readable size.
 */
export function formatSize(bytes: number): string {
	if (bytes < 1024) {
		return `${bytes}B`;
	} else if (bytes < 1024 * 1024) {
		return `${(bytes / 1024).toFixed(1)}KB`;
	} else {
		return `${(bytes / (1024 * 1024)).toFixed(1)}MB`;
	}
}

/**
 * Truncate content from the head (keep first N lines/bytes).
 * Suitable for file reads where you want to see the beginning.
 *
 * Never returns partial lines. If first line exceeds byte limit,
 * returns empty content with firstLineExceedsLimit=true.
 */
export function truncateHead(content: string, options: TruncationOptions = {}): TruncationResult {
	const maxLines = options.maxLines ?? DEFAULT_MAX_LINES;
	const maxBytes = options.maxBytes ?? DEFAULT_MAX_BYTES;

	const totalBytes = Buffer.byteLength(content, "utf-8");
	const totalLines = countLines(content);

	// Check if no truncation needed
	if (totalLines <= maxLines && totalBytes <= maxBytes) {
		return {
			content,
			truncated: false,
			truncatedBy: null,
			totalLines,
			totalBytes,
			outputLines: totalLines,
			outputBytes: totalBytes,
			lastLinePartial: false,
			firstLineExceedsLimit: false,
			maxLines,
			maxBytes,
		};
	}

	// Check if first line alone exceeds byte limit
	// Every non-ASCII character takes more UTF-8 bytes than UTF-16 units, so equal lengths mean the content is ASCII and
	// a line's byte length is its length.
	const ascii = totalBytes === content.length;
	const firstNewline = content.indexOf("\n");
	const firstLineEnd = firstNewline === -1 ? content.length : firstNewline;
	const firstLineBytes = ascii ? firstLineEnd : Buffer.byteLength(content.slice(0, firstLineEnd), "utf-8");
	if (firstLineBytes > maxBytes) {
		return {
			content: "",
			truncated: true,
			truncatedBy: "bytes",
			totalLines,
			totalBytes,
			outputLines: 0,
			outputBytes: 0,
			lastLinePartial: false,
			firstLineExceedsLimit: true,
			maxLines,
			maxBytes,
		};
	}

	// Count the complete lines that fit. They are a prefix of `content`, which is cut once instead of slicing every
	// line and joining them again. Only lines that contain a non-ASCII character are measured with Buffer.byteLength.
	let outputLines = 0;
	let outputBytesCount = 0;
	let outputEnd = 0;
	let truncatedBy: "lines" | "bytes" = "lines";
	let nextNonAscii = ascii ? content.length : findNonAscii(content, 0);

	for (let start = 0; outputLines < totalLines && outputLines < maxLines; ) {
		const newline = content.indexOf("\n", start);
		const end = newline === -1 ? content.length : newline;
		let lineBytes: number;
		if (end <= nextNonAscii) {
			lineBytes = end - start;
		} else {
			lineBytes = Buffer.byteLength(content.slice(start, end), "utf-8");
			nextNonAscii = findNonAscii(content, end);
		}
		if (outputLines > 0) lineBytes++; // +1 for newline

		if (outputBytesCount + lineBytes > maxBytes) {
			truncatedBy = "bytes";
			break;
		}

		outputLines++;
		outputBytesCount += lineBytes;
		outputEnd = end;
		start = end + 1;
	}

	// If we exited due to line limit
	if (outputLines >= maxLines && outputBytesCount <= maxBytes) {
		truncatedBy = "lines";
	}

	return {
		content: copyString(content.slice(0, outputEnd)),
		truncated: true,
		truncatedBy,
		totalLines,
		totalBytes,
		outputLines,
		// A "\n" never splits a surrogate pair, so the lines' byte lengths add up to the output's.
		outputBytes: outputBytesCount,
		lastLinePartial: false,
		firstLineExceedsLimit: false,
		maxLines,
		maxBytes,
	};
}

/**
 * Truncate content from the tail (keep last N lines/bytes).
 * Suitable for bash output where you want to see the end (errors, final results).
 *
 * May return partial first line if the last line of original content exceeds byte limit.
 */
export function truncateTail(content: string, options: TruncationOptions = {}): TruncationResult {
	const maxLines = options.maxLines ?? DEFAULT_MAX_LINES;
	const maxBytes = options.maxBytes ?? DEFAULT_MAX_BYTES;

	const totalBytes = Buffer.byteLength(content, "utf-8");
	const totalLines = countLines(content);

	// Check if no truncation needed
	if (totalLines <= maxLines && totalBytes <= maxBytes) {
		return {
			content,
			truncated: false,
			truncatedBy: null,
			totalLines,
			totalBytes,
			outputLines: totalLines,
			outputBytes: totalBytes,
			lastLinePartial: false,
			firstLineExceedsLimit: false,
			maxLines,
			maxBytes,
		};
	}

	// Work backwards from the end. A trailing "\n" ends the last line. The kept lines are a suffix of the content up to
	// that "\n", which is cut once instead of slicing every line and joining them again.
	const ascii = totalBytes === content.length;
	const contentEnd = content.endsWith("\n") ? content.length - 1 : content.length;
	let outputLines = 0;
	let outputBytesCount = 0;
	let outputStart = contentEnd;
	let partialLine: string | undefined;
	let truncatedBy: "lines" | "bytes" = "lines";

	let end = contentEnd;
	for (let i = totalLines - 1; i >= 0 && outputLines < maxLines; i--) {
		const start = i === 0 ? 0 : content.lastIndexOf("\n", end - 1) + 1;
		const lineBytes =
			(ascii ? end - start : Buffer.byteLength(content.slice(start, end), "utf-8")) + (outputLines > 0 ? 1 : 0); // +1 for newline

		if (outputBytesCount + lineBytes > maxBytes) {
			truncatedBy = "bytes";
			// Edge case: if we haven't added ANY lines yet and this line exceeds maxBytes,
			// take the end of the line (partial)
			if (outputLines === 0) {
				partialLine = truncateStringToBytesFromEnd(content.slice(start, end), maxBytes);
				outputLines = 1;
				outputBytesCount = Buffer.byteLength(partialLine, "utf-8");
			}
			break;
		}

		outputLines++;
		outputBytesCount += lineBytes;
		outputStart = start;
		end = start - 1;
	}

	// If we exited due to line limit
	if (outputLines >= maxLines && outputBytesCount <= maxBytes) {
		truncatedBy = "lines";
	}

	return {
		content: partialLine ?? copyString(content.slice(outputStart, contentEnd)),
		truncated: true,
		truncatedBy,
		totalLines,
		totalBytes,
		outputLines,
		// A "\n" never splits a surrogate pair, so the lines' byte lengths add up to the output's.
		outputBytes: outputBytesCount,
		lastLinePartial: partialLine !== undefined,
		firstLineExceedsLimit: false,
		maxLines,
		maxBytes,
	};
}

/**
 * Truncate a string to fit within a byte limit (from the end).
 * Handles multi-byte UTF-8 characters correctly.
 */
function truncateStringToBytesFromEnd(str: string, maxBytes: number): string {
	const buf = Buffer.from(str, "utf-8");
	if (buf.length <= maxBytes) {
		return str;
	}

	// Start from the end, skip maxBytes back
	let start = buf.length - maxBytes;

	// Find a valid UTF-8 boundary (start of a character)
	while (start < buf.length && (buf[start] & 0xc0) === 0x80) {
		start++;
	}

	return buf.slice(start).toString("utf-8");
}

/**
 * Truncate a single line to max characters, adding [truncated] suffix.
 * Used for grep match lines.
 */
export function truncateLine(
	line: string,
	maxChars: number = GREP_MAX_LINE_LENGTH,
): { text: string; wasTruncated: boolean } {
	if (line.length <= maxChars) {
		return { text: line, wasTruncated: false };
	}
	return { text: `${sliceHead(line, maxChars)}... [truncated]`, wasTruncated: true };
}

/** Returns at most the first `maxUnits` UTF-16 code units of `text`, without splitting a surrogate pair. */
export function sliceHead(text: string, maxUnits: number): string {
	const end = Math.max(0, maxUnits);
	// A lone high surrogate before the cut would be invalid UTF-16; keep the whole character out instead.
	return isHighSurrogate(text.charCodeAt(end - 1)) && isLowSurrogate(text.charCodeAt(end))
		? text.slice(0, end - 1)
		: text.slice(0, end);
}

/** Returns at most the last `maxUnits` UTF-16 code units of `text`, without splitting a surrogate pair. */
export function sliceTail(text: string, maxUnits: number): string {
	if (maxUnits <= 0) return "";
	const start = Math.max(0, text.length - maxUnits);
	return isLowSurrogate(text.charCodeAt(start)) && isHighSurrogate(text.charCodeAt(start - 1))
		? text.slice(start + 1)
		: text.slice(start);
}

function isHighSurrogate(code: number): boolean {
	return code >= 0xd800 && code <= 0xdbff;
}

function isLowSurrogate(code: number): boolean {
	return code >= 0xdc00 && code <= 0xdfff;
}
