import assert from "node:assert";
import { describe, it } from "node:test";
import {
	cjkBreakRegex,
	extractAnsiCode,
	splitIntoTokensWithAnsiForTest,
	visibleWidth,
	wrapTextWithAnsi,
} from "../src/utils.ts";

// Printable ASCII is split into runs of spaces and of other characters at once. The tokens must be those of going through
// it one character at a time, as before (this copy).

const segmenter = new Intl.Segmenter(undefined, { granularity: "grapheme" });

function tokensOneByOne(text: string): string[] {
	const tokens: string[] = [];
	let current = "";
	let pendingAnsi = "";
	let currentKind: "space" | "word" | null = null;
	const flush = () => {
		if (!current) return;
		tokens.push(current);
		current = "";
		currentKind = null;
	};
	let i = 0;
	while (i < text.length) {
		const ansi = extractAnsiCode(text, i);
		if (ansi) {
			pendingAnsi += ansi.code;
			i += ansi.length;
			continue;
		}
		let end = text.indexOf("\x1b", i + 1);
		while (end !== -1 && !extractAnsiCode(text, end)) end = text.indexOf("\x1b", end + 1);
		if (end === -1) end = text.length;
		const chunk = text.slice(i, end);
		const ascii = /^[\x20-\x7e]*$/.test(chunk);
		const segments = ascii ? [...chunk] : [...segmenter.segment(chunk)].map((s) => s.segment);
		for (const segment of segments) {
			const isSpace = segment === " ";
			if (!ascii && !isSpace && cjkBreakRegex.test(segment)) {
				flush();
				tokens.push(pendingAnsi + segment);
				pendingAnsi = "";
				continue;
			}
			if (pendingAnsi && isSpace && currentKind !== "space") {
				if (current) {
					current += pendingAnsi;
					pendingAnsi = "";
				} else if (tokens.length > 0) {
					tokens[tokens.length - 1] += pendingAnsi;
					pendingAnsi = "";
				}
			}
			const kind = isSpace ? "space" : "word";
			if (current && currentKind !== kind) flush();
			if (pendingAnsi) {
				current += pendingAnsi;
				pendingAnsi = "";
			}
			currentKind = kind;
			current += segment;
		}
		i = end;
	}
	if (pendingAnsi) {
		if (current) current += pendingAnsi;
		else if (tokens.length > 0) tokens[tokens.length - 1] += pendingAnsi;
		else current = pendingAnsi;
	}
	if (current) tokens.push(current);
	return tokens;
}

const PIECES = [
	"word",
	"x",
	"longerword",
	"a,b.",
	"(paren)",
	" ",
	" ",
	"  ",
	"   ",
	"\t",
	"\x1b[1m",
	"\x1b[22m",
	"\x1b[4m",
	"\x1b[24m",
	"\x1b[38;5;208m",
	"\x1b[39m",
	"\x1b[0m",
	"\x1b]8;;https://example.com\x1b\\",
	"\x1b]8;;\x1b\\",
	"\x1b_pi:c\x07",
	"\x1b",
	"\x1bx",
	"é",
	"naïve",
	"日本語",
	"한국어",
	"😀",
	"👍🏽",
	" ́",
	" ",
	"~",
	"-",
];

function mulberry32(seed: number): () => number {
	let a = seed;
	return () => {
		a = (a + 0x6d2b79f5) | 0;
		let t = Math.imul(a ^ (a >>> 15), 1 | a);
		t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
		return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
	};
}

describe("splitIntoTokensWithAnsi on runs of ASCII", () => {
	it("gives the tokens of going one character at a time", () => {
		const random = mulberry32(1027);
		const known = { all: 0, unknown: 0 };
		for (let n = 0; n < 20000; n++) {
			let text = "";
			const length = Math.floor(random() * 24);
			for (let k = 0; k < length; k++) text += PIECES[Math.floor(random() * PIECES.length)];
			const widths: number[] = [];
			const tokens = splitIntoTokensWithAnsiForTest(text, widths);
			assert.deepStrictEqual(tokens, tokensOneByOne(text), JSON.stringify(text));
			// The widths it knows are those visibleWidth() measures.
			assert.strictEqual(widths.length, tokens.length);
			for (let k = 0; k < tokens.length; k++) {
				if (widths[k] !== -1) assert.strictEqual(widths[k], visibleWidth(tokens[k]!), JSON.stringify(tokens[k]));
				else known.unknown++;
			}
			known.all += tokens.length;
		}
		// (Many tokens are of ASCII text, whose widths are known.)
		assert.ok(known.all - known.unknown > 5000, JSON.stringify(known));
	});

	it("wraps styled paragraphs as before", () => {
		const text =
			"\x1b[1mBold start\x1b[22m and then   several   spaces, \x1b[4mhttps://example.com/a/long/link\x1b[24m end. " +
			"A word ".repeat(30);
		for (const width of [1, 2, 5, 10, 17, 40, 80]) {
			const lines = wrapTextWithAnsi(text, width);
			assert.ok(lines.length > 0);
			assert.strictEqual(
				lines
					.join("")
					.replace(/\x1b\[[0-9;]*m/g, "")
					.replace(/\s/g, ""),
				text.replace(/\x1b\[[0-9;]*m/g, "").replace(/\s/g, ""),
			);
		}
	});
});
