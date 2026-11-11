import { describe, expect, it } from "vitest";
import {
	type TruncationOptions,
	type TruncationResult,
	truncateHead,
	truncateTail,
} from "../src/core/tools/truncate.ts";

// Reference implementations: verbatim copies of truncate.ts before truncateHead and truncateTail stopped slicing and
// joining every line. The current ones must return deep-equal results. (truncateMiddle moved to pi-mcp's output-limit.ts,
// tested there.)

const DEFAULT_MAX_LINES = 2000;
const DEFAULT_MAX_BYTES = 50 * 1024;

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

function referenceTruncateHead(content: string, options: TruncationOptions = {}): TruncationResult {
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
	const firstNewline = content.indexOf("\n");
	const firstLineBytes = Buffer.byteLength(firstNewline === -1 ? content : content.slice(0, firstNewline), "utf-8");
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

	// Collect complete lines that fit
	const outputLinesArr: string[] = [];
	let outputBytesCount = 0;
	let truncatedBy: "lines" | "bytes" = "lines";

	for (let i = 0, start = 0; i < totalLines && i < maxLines; i++) {
		const end = content.indexOf("\n", start);
		const line = end === -1 ? content.slice(start) : content.slice(start, end);
		start = end + 1;
		const lineBytes = Buffer.byteLength(line, "utf-8") + (i > 0 ? 1 : 0); // +1 for newline

		if (outputBytesCount + lineBytes > maxBytes) {
			truncatedBy = "bytes";
			break;
		}

		outputLinesArr.push(line);
		outputBytesCount += lineBytes;
	}

	// If we exited due to line limit
	if (outputLinesArr.length >= maxLines && outputBytesCount <= maxBytes) {
		truncatedBy = "lines";
	}

	const outputContent = outputLinesArr.join("\n");
	const finalOutputBytes = Buffer.byteLength(outputContent, "utf-8");

	return {
		content: outputContent,
		truncated: true,
		truncatedBy,
		totalLines,
		totalBytes,
		outputLines: outputLinesArr.length,
		outputBytes: finalOutputBytes,
		lastLinePartial: false,
		firstLineExceedsLimit: false,
		maxLines,
		maxBytes,
	};
}

function referenceTruncateTail(content: string, options: TruncationOptions = {}): TruncationResult {
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

	// Work backwards from the end, collecting the kept lines last first. A trailing "\n" ends the last line.
	const outputLinesArr: string[] = [];
	let outputBytesCount = 0;
	let truncatedBy: "lines" | "bytes" = "lines";
	let lastLinePartial = false;

	let end = content.endsWith("\n") ? content.length - 1 : content.length;
	for (let i = totalLines - 1; i >= 0 && outputLinesArr.length < maxLines; i--) {
		const start = i === 0 ? 0 : content.lastIndexOf("\n", end - 1) + 1;
		const line = content.slice(start, end);
		end = start - 1;
		const lineBytes = Buffer.byteLength(line, "utf-8") + (outputLinesArr.length > 0 ? 1 : 0); // +1 for newline

		if (outputBytesCount + lineBytes > maxBytes) {
			truncatedBy = "bytes";
			// Edge case: if we haven't added ANY lines yet and this line exceeds maxBytes,
			// take the end of the line (partial)
			if (outputLinesArr.length === 0) {
				const truncatedLine = truncateStringToBytesFromEnd(line, maxBytes);
				outputLinesArr.push(truncatedLine);
				outputBytesCount = Buffer.byteLength(truncatedLine, "utf-8");
				lastLinePartial = true;
			}
			break;
		}

		outputLinesArr.push(line);
		outputBytesCount += lineBytes;
	}
	outputLinesArr.reverse();

	// If we exited due to line limit
	if (outputLinesArr.length >= maxLines && outputBytesCount <= maxBytes) {
		truncatedBy = "lines";
	}

	const outputContent = outputLinesArr.join("\n");
	const finalOutputBytes = Buffer.byteLength(outputContent, "utf-8");

	return {
		content: outputContent,
		truncated: true,
		truncatedBy,
		totalLines,
		totalBytes,
		outputLines: outputLinesArr.length,
		outputBytes: finalOutputBytes,
		lastLinePartial,
		firstLineExceedsLimit: false,
		maxLines,
		maxBytes,
	};
}

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

/** Seeded PRNG (mulberry32), so a failure reproduces. */
function createRandom(seed: number): () => number {
	let state = seed >>> 0;
	return () => {
		state = (state + 0x6d2b79f5) >>> 0;
		let t = state;
		t = Math.imul(t ^ (t >>> 15), t | 1);
		t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
		return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
	};
}

// ASCII, CR/LF variants, 2-, 3- and 4-byte UTF-8 characters, and lone surrogates (3 bytes each, as U+FFFD).
const PIECES = ["a", "xyz", " ", "\t", "\n", "\n", "\n\n", "\r\n", "\r", "é", "€", "😀", "\ud800", "\udc00", "中文"];

function randomText(random: () => number, maxPieces: number): string {
	const count = Math.floor(random() * maxPieces);
	let text = "";
	for (let i = 0; i < count; i++) {
		const roll = random();
		if (roll < 0.05) {
			text += "b".repeat(Math.floor(random() * 400));
		} else if (roll < 0.08) {
			text += "ü".repeat(Math.floor(random() * 100));
		} else {
			text += PIECES[Math.floor(random() * PIECES.length)];
		}
	}
	if (random() < 0.3) text += "\n";
	return text;
}

function pick<T>(random: () => number, values: T[]): T {
	return values[Math.floor(random() * values.length)];
}

function randomOptions(random: () => number, text: string): TruncationOptions {
	const totalLines = countLines(text);
	const totalBytes = Buffer.byteLength(text, "utf-8");
	const firstNewline = text.indexOf("\n");
	const firstLineBytes = Buffer.byteLength(firstNewline === -1 ? text : text.slice(0, firstNewline), "utf-8");
	const lastNewline = text.lastIndexOf("\n", text.length - 2);
	const lastLineBytes = Buffer.byteLength(text.slice(lastNewline + 1), "utf-8");
	const around = (value: number) => Math.max(0, value + Math.floor(random() * 7) - 3);
	const maxLines = pick(random, [
		undefined,
		0,
		1,
		2,
		around(totalLines),
		around(Math.floor(totalLines / 2)),
		Number.MAX_SAFE_INTEGER,
	]);
	const maxBytes = pick(random, [
		undefined,
		0,
		1,
		around(firstLineBytes),
		around(lastLineBytes),
		around(totalBytes),
		around(Math.floor(totalBytes / 2)),
		Math.floor(random() * (totalBytes + 10)),
	]);
	const options: TruncationOptions = {};
	if (maxLines !== undefined) options.maxLines = maxLines;
	if (maxBytes !== undefined) options.maxBytes = maxBytes;
	return options;
}

const FIXED_TEXTS = [
	"",
	"\n",
	"\n\n\n",
	"a",
	"a\n",
	"a\nb",
	"a\nb\n",
	"\r\n\r\n",
	"a\r\nb\r\n",
	"😀",
	"😀\n😀",
	"\ud800\n\udc00",
	"\ud83d\n\ude00",
	"é\n€\n😀\n",
];

describe("truncation fast paths match the reference implementations", () => {
	it("truncateHead and truncateTail on fixed edge cases", () => {
		for (const text of FIXED_TEXTS) {
			for (const maxLines of [0, 1, 2, 3, 100]) {
				for (let maxBytes = 0; maxBytes <= 20; maxBytes++) {
					const options = { maxLines, maxBytes };
					expect(truncateHead(text, options), JSON.stringify({ text, options })).toEqual(
						referenceTruncateHead(text, options),
					);
					expect(truncateTail(text, options), JSON.stringify({ text, options })).toEqual(
						referenceTruncateTail(text, options),
					);
				}
			}
			expect(truncateHead(text)).toEqual(referenceTruncateHead(text));
			expect(truncateTail(text)).toEqual(referenceTruncateTail(text));
		}
	});

	it("truncateHead and truncateTail on random text and limits", () => {
		const random = createRandom(0x5eed);
		for (let i = 0; i < 20000; i++) {
			const text = randomText(random, 60);
			const options = randomOptions(random, text);
			const label = JSON.stringify({ i, text, options });
			expect(truncateHead(text, options), label).toEqual(referenceTruncateHead(text, options));
			expect(truncateTail(text, options), label).toEqual(referenceTruncateTail(text, options));
		}
	});

	it("truncateHead and truncateTail on large text with the default limits", () => {
		const random = createRandom(0xb16);
		for (let i = 0; i < 60; i++) {
			const ascii = random() < 0.5;
			const lines: string[] = [];
			const lineCount = 1500 + Math.floor(random() * 1500);
			for (let line = 0; line < lineCount; line++) {
				lines.push(ascii ? "x".repeat(Math.floor(random() * 60)) : randomText(random, 12).replaceAll("\n", ""));
			}
			const text = lines.join(pick(random, ["\n", "\r\n"])) + (random() < 0.5 ? "\n" : "");
			for (const options of [{}, randomOptions(random, text)]) {
				expect(truncateHead(text, options)).toEqual(referenceTruncateHead(text, options));
				expect(truncateTail(text, options)).toEqual(referenceTruncateTail(text, options));
			}
		}
	});
});
