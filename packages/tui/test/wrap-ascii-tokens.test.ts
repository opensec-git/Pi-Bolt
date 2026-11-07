import assert from "node:assert";
import { describe, it } from "node:test";
import {
	scanPrintableAsciiForTest,
	splitIntoTokensWithAnsiForTest,
	visibleWidth,
	wrapTextWithAnsi,
} from "../src/utils.ts";

// A line of printable ASCII and escape sequences is measured and cut into tokens in one pass, and wrapped from those. The
// tokens and widths must be what the general tokenizer gives, the line's width what visibleWidth() gives, and any other
// line must be left to the general path.

/** A deterministic pseudo-random sequence (mulberry32), so a failure can be reproduced from its seed. */
function random(seed: number): () => number {
	let a = seed;
	return () => {
		a = (a + 0x6d2b79f5) | 0;
		let t = Math.imul(a ^ (a >>> 15), 1 | a);
		t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
		return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
	};
}

const ASCII_PIECES = [
	"a",
	"word",
	"x",
	"(",
	"...",
	"-",
	"https://example.com/a/b/c/d/e/f",
	" ",
	"  ",
	"    ",
	"\x1b[1m",
	"\x1b[22m",
	"\x1b[3m\x1b[90m",
	"\x1b[39m\x1b[23m",
	"\x1b[38;2;10;20;30m",
	"\x1b[48;5;236m",
	"\x1b[0m",
	"\x1b[4m",
	"\x1b[24m",
	"\x1b[9m",
	"\x1b[K",
	"\x1b]8;;https://example.com\x07",
	"\x1b]8;;\x07",
	"\x1b]8;;https://example.com\x1b\\",
	"\x1b]8;;\x1b\\",
	"\x1b_pi:c\x07",
];
const OTHER_PIECES = ["\t", "é", "日本", "😀", "\x1b", "\x1b[", "\x1bx", "\x7f", "\x00", " "];

function line(next: () => number, pieces: number, other: number): string {
	let text = "";
	for (let i = 0; i < pieces; i++) {
		const source = next() < other ? OTHER_PIECES : ASCII_PIECES;
		text += source[Math.floor(next() * source.length)];
	}
	return text;
}

describe("scanPrintableAscii", () => {
	it("gives the general tokenizer's tokens and widths, and the line's visible width", () => {
		const next = random(1);
		for (let n = 0; n < 20_000; n++) {
			const text = line(next, 1 + Math.floor(next() * 80), 0);
			const scanned = scanPrintableAsciiForTest(text);
			assert.ok(scanned, JSON.stringify(text));
			const widths: number[] = [];
			const tokens = splitIntoTokensWithAnsiForTest(text, widths);
			assert.deepStrictEqual(
				scanned.tokens.map((token) => token.text),
				tokens,
				JSON.stringify(text),
			);
			assert.deepStrictEqual(
				scanned.tokens.map((token) => token.width),
				widths,
				JSON.stringify(text),
			);
			assert.strictEqual(scanned.width, visibleWidth(text), JSON.stringify(text));
			for (const token of scanned.tokens) {
				// Spaces: no visible character other than a space. Codes: an escape sequence in it.
				assert.strictEqual(
					token.spaces,
					!/[^ ]/.test(token.text.replace(/\x1b(\[[^mGKHJ]*[mGKHJ]|[\]_][^\x07\x1b]*(\x07|\x1b\\))/g, "")),
					JSON.stringify(token),
				);
				assert.strictEqual(token.codes, token.text.includes("\x1b"), JSON.stringify(token));
			}
		}
	});

	it("leaves any other line to the general path", () => {
		const next = random(2);
		let left = 0;
		for (let n = 0; n < 20_000; n++) {
			const text = line(next, 1 + Math.floor(next() * 40), 0.15);
			const scanned = scanPrintableAsciiForTest(text);
			if (scanned === undefined) {
				left++;
				continue;
			}
			// (Only lines that turned out to be all printable ASCII and complete escape sequences.)
			assert.deepStrictEqual(
				scanned.tokens.map((token) => token.text),
				splitIntoTokensWithAnsiForTest(text),
				JSON.stringify(text),
			);
		}
		assert.ok(left > 10_000);
	});

	it("wraps such lines within the width, keeping every visible character", () => {
		const next = random(3);
		const visible = (s: string) => s.replace(/\x1b(\[[^mGKHJ]*[mGKHJ]|[\]_][^\x07\x1b]*(\x07|\x1b\\))/g, "");
		for (let n = 0; n < 2_000; n++) {
			const text = line(next, 1 + Math.floor(next() * 120), 0);
			for (const width of [1, 2, 5, 13, 40, 80]) {
				const wrapped = wrapTextWithAnsi(text, width);
				for (const out of wrapped) assert.ok(visible(out).length <= width, JSON.stringify({ text, width, out }));
				assert.strictEqual(
					wrapped.map((out) => visible(out).replace(/ /g, "")).join(""),
					visible(text).replace(/ /g, ""),
					JSON.stringify({ text, width }),
				);
			}
		}
	});
});
