import { describe, expect, it } from "vitest";
import {
	applyEditsToNormalizedContent,
	type Edit,
	fuzzyFindText,
	generateDiffString,
	generateUnifiedPatch,
	normalizeForFuzzyMatch,
	normalizeToLF,
} from "../src/core/tools/edit-diff.ts";
import * as reference from "./edit-diff-reference.ts";

/** Deterministic PRNG (mulberry32) so failures reproduce. */
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

type Random = () => number;

function pick<T>(random: Random, items: readonly T[]): T {
	return items[Math.floor(random() * items.length)];
}

function int(random: Random, max: number): number {
	return Math.floor(random() * (max + 1));
}

const ASCII_WORDS = ["const", "x", "return", "foo(bar)", "'a'", '"b"', "-", "if (y) {", "}", "a-b", "\t", "  ", "z;"];
// Lines that repeat often, so line alignment is ambiguous and the diff algorithm's tie-breaking matters.
const REPEATED_LINES = ["", "}", "\t}", "x", "  return x;", " "];
const UNICODE_FRAGMENTS = [
	"‘q’",
	"“dq”",
	"‚",
	"‟",
	"a b",
	"‐",
	"‑",
	"–",
	"—",
	"−",
	" ",
	" ",
	" ",
	" ",
	"　",
	"café",
	"café",
	"ﬁ",
	"Ａ",
	"\u{1F600}",
	"﻿",
	" ",
];
const TRAILING = ["", "", "", " ", "\t", "  ", "\v", "\f", " ", "　", " \t "];
const ODD_ASCII = ["\r", "\x1c", "\x00", "\x7f", "\x0b"];

function randomLine(random: Random, unicode: boolean): string {
	if (random() < 0.3) return pick(random, REPEATED_LINES);
	const parts: string[] = [];
	const count = 1 + int(random, 4);
	for (let i = 0; i < count; i++) {
		const roll = random();
		if (unicode && roll < 0.2) parts.push(pick(random, UNICODE_FRAGMENTS));
		else if (roll < 0.23) parts.push(pick(random, ODD_ASCII));
		else parts.push(pick(random, ASCII_WORDS));
	}
	let line = parts.join(" ");
	const trailing = pick(random, TRAILING);
	if (unicode || trailing.charCodeAt(0) < 128 || trailing === "") line += trailing;
	return line;
}

function randomLines(random: Random, count: number, unicode: boolean): string[] {
	const lines: string[] = [];
	for (let i = 0; i < count; i++) lines.push(randomLine(random, unicode));
	return lines;
}

function joinLines(random: Random, lines: string[]): string {
	const ending = random() < 0.15 ? "\r\n" : "\n";
	let text = lines.join(ending);
	if (lines.length > 0 && random() < 0.7) text += ending;
	if (random() < 0.05) text = `﻿${text}`;
	return text;
}

function randomLineCount(random: Random): number {
	const roll = random();
	if (roll < 0.1) return int(random, 2);
	if (roll < 0.75) return int(random, 40);
	return 100 + int(random, 400);
}

/** Apply 1-4 line-level mutations, often clustered so their context windows overlap. */
function mutateLines(random: Random, lines: string[], unicode: boolean): string[] {
	const result = [...lines];
	const mutations = 1 + int(random, 3);
	let anchor = int(random, result.length);
	for (let m = 0; m < mutations; m++) {
		const roll = random();
		const position =
			roll < 0.15
				? 0
				: roll < 0.3
					? result.length
					: Math.min(result.length, Math.max(0, anchor + int(random, 12) - 6));
		anchor = position;
		const op = random();
		if (op < 0.3) {
			result.splice(position, 0, ...randomLines(random, 1 + int(random, 3), unicode));
		} else if (op < 0.55) {
			result.splice(position, 1 + int(random, 3));
		} else if (op < 0.8) {
			result.splice(position, 1 + int(random, 2), ...randomLines(random, int(random, 3), unicode));
		} else if (op < 0.9 && result.length > 0) {
			// Duplicate a neighbouring line, the classic ambiguous alignment.
			const source = Math.min(position, result.length - 1);
			result.splice(position, 0, result[source]);
		} else if (result.length > 0) {
			const index = Math.min(position, result.length - 1);
			result[index] = `${result[index]}${pick(random, ["x", " ", "’", ";"])}`;
		}
	}
	return result;
}

function randomDiffPair(random: Random): { oldText: string; newText: string } {
	const unicode = random() < 0.5;
	if (random() < 0.08) {
		// Unrelated small texts.
		return {
			oldText: joinLines(random, randomLines(random, int(random, 8), unicode)),
			newText: joinLines(random, randomLines(random, int(random, 8), unicode)),
		};
	}
	const lines = randomLines(random, randomLineCount(random), unicode);
	const ending = random() < 0.15 ? "\r\n" : "\n";
	const finalNewline = random() < 0.7;
	const join = (items: string[], withFinal: boolean): string =>
		items.join(ending) + (withFinal && items.length > 0 ? ending : "");
	const oldText = join(lines, finalNewline);
	const roll = random();
	if (roll < 0.05) return { oldText, newText: oldText };
	if (roll < 0.1) return { oldText, newText: join(lines, !finalNewline) };
	const mutated = mutateLines(random, lines, unicode);
	return { oldText, newText: join(mutated, random() < 0.1 ? !finalNewline : finalNewline) };
}

const CONTEXT_VALUES = [4, 4, 4, 0, 1, 2, 3, 5, 10, 1000];

describe("normalizeForFuzzyMatch fast path", () => {
	it("matches the original implementation", () => {
		const random = createRandom(1);
		for (let i = 0; i < 4000; i++) {
			const unicode = random() < 0.5;
			const text = joinLines(random, randomLines(random, int(random, 30), unicode));
			expect(normalizeForFuzzyMatch(text)).toBe(reference.normalizeForFuzzyMatch(text));
		}
	});

	it("matches the original implementation for every ASCII character at a line end", () => {
		for (let code = 0; code < 128; code++) {
			const c = String.fromCharCode(code);
			for (const text of [`a${c}`, `a${c}\nb`, `${c}`, `a${c}${c}\n${c}`, `a ${c} \n`]) {
				expect(normalizeForFuzzyMatch(text)).toBe(reference.normalizeForFuzzyMatch(text));
			}
		}
	});
});

describe("windowed line diff", () => {
	it("generateDiffString and generateUnifiedPatch match the original implementation", () => {
		const random = createRandom(2);
		for (let i = 0; i < 6000; i++) {
			const { oldText, newText } = randomDiffPair(random);
			const contextLines = pick(random, CONTEXT_VALUES);
			const label = JSON.stringify({ i, contextLines, oldText, newText });
			// Order varies so both functions are exercised with and without the cached line diff.
			if (random() < 0.5) {
				expect(generateDiffString(oldText, newText, contextLines), label).toEqual(
					reference.generateDiffString(oldText, newText, contextLines),
				);
				expect(generateUnifiedPatch("a/file.ts", oldText, newText, contextLines), label).toBe(
					reference.generateUnifiedPatch("a/file.ts", oldText, newText, contextLines),
				);
			} else {
				expect(generateUnifiedPatch("file.ts", oldText, newText), label).toBe(
					reference.generateUnifiedPatch("file.ts", oldText, newText),
				);
				expect(generateDiffString(oldText, newText), label).toEqual(reference.generateDiffString(oldText, newText));
			}
		}
	});

	it("matches the original implementation for unusual context values", () => {
		const random = createRandom(3);
		for (let i = 0; i < 300; i++) {
			const { oldText, newText } = randomDiffPair(random);
			for (const contextLines of [-1, -3, 1.5, 2.7, Number.NaN, Number.POSITIVE_INFINITY]) {
				const label = JSON.stringify({ i, contextLines, oldText, newText });
				expect(generateDiffString(oldText, newText, contextLines), label).toEqual(
					reference.generateDiffString(oldText, newText, contextLines),
				);
				expect(generateUnifiedPatch("f", oldText, newText, contextLines), label).toBe(
					reference.generateUnifiedPatch("f", oldText, newText, contextLines),
				);
			}
		}
	});

	it("matches the original implementation on edge-case texts", () => {
		const texts = ["", "\n", "\n\n", "a", "a\n", "a\nb", "a\nb\n", "b\na\n", "a\r\nb", "\r\n", "x\nx\n", "x\n", "x"];
		for (const oldText of texts) {
			for (const newText of texts) {
				expect(generateDiffString(oldText, newText)).toEqual(reference.generateDiffString(oldText, newText));
				expect(generateUnifiedPatch("f", oldText, newText)).toBe(
					reference.generateUnifiedPatch("f", oldText, newText),
				);
			}
		}
	});

	it("matches the original implementation for one edit in a large file", () => {
		const random = createRandom(4);
		for (let i = 0; i < 40; i++) {
			const lines = randomLines(random, 3000 + int(random, 3000), random() < 0.5);
			const mutated = mutateLines(random, lines, false);
			const oldText = `${lines.join("\n")}\n`;
			const newText = `${mutated.join("\n")}\n`;
			expect(generateDiffString(oldText, newText)).toEqual(reference.generateDiffString(oldText, newText));
			expect(generateUnifiedPatch("f", oldText, newText)).toBe(
				reference.generateUnifiedPatch("f", oldText, newText),
			);
		}
	});
});

function captureResult<T>(fn: () => T): { value?: T; error?: string } {
	try {
		return { value: fn() };
	} catch (error) {
		return { error: error instanceof Error ? error.message : String(error) };
	}
}

/** Make `text` differ in ways fuzzy matching ignores. */
function fuzzify(random: Random, text: string): string {
	let result = text;
	if (random() < 0.5) result = result.replace(/'/g, pick(random, ["‘", "’"]));
	if (random() < 0.5) result = result.replace(/"/g, pick(random, ["“", "”"]));
	if (random() < 0.5) result = result.replace(/-/g, pick(random, ["–", "—", "−"]));
	if (random() < 0.5) result = result.replace(/[‘’]/g, "'").replace(/[“”]/g, '"');
	if (random() < 0.5) result = result.replace(/[–—−]/g, "-");
	if (random() < 0.5) result = result.replace(/ +\n/g, "\n");
	if (random() < 0.3) result = result.replace(/\n/g, " \n");
	return result;
}

function randomEdits(random: Random, content: string): Edit[] {
	const edits: Edit[] = [];
	const count = 1 + int(random, 3);
	let anchor = int(random, content.length);
	for (let i = 0; i < count; i++) {
		const roll = random();
		if (roll < 0.05) {
			edits.push({ oldText: "", newText: "x" });
			continue;
		}
		if (roll < 0.1) {
			edits.push({ oldText: "definitely not present ☃", newText: "x" });
			continue;
		}
		const start = Math.min(content.length, Math.max(0, anchor + int(random, 80) - 40));
		const end = Math.min(content.length, start + 1 + int(random, 60));
		anchor = end + int(random, 20);
		let oldText = content.slice(start, end);
		if (random() < 0.4) oldText = fuzzify(random, oldText);
		if (random() < 0.1) oldText = oldText.replace(/\n/g, "\r\n");
		const newText = random() < 0.1 ? oldText : randomLines(random, int(random, 3), random() < 0.5).join("\n");
		edits.push({ oldText, newText });
	}
	return edits;
}

describe("applyEditsToNormalizedContent with shared normalization", () => {
	it("matches the original implementation, including errors", () => {
		const random = createRandom(5);
		for (let i = 0; i < 4000; i++) {
			const unicode = random() < 0.5;
			const content = normalizeToLF(joinLines(random, randomLines(random, int(random, 60), unicode)));
			const edits = randomEdits(random, content);
			const label = JSON.stringify({ i, content, edits });
			const expected = captureResult(() => reference.applyEditsToNormalizedContent(content, edits, "f.ts"));
			// (The reference predates `replacements`, which upstream's windowed edit diff added; they are checked to give
			// newContent.)
			const applied = (text: string) => {
				const { baseContent, newContent, replacements } = applyEditsToNormalizedContent(text, edits, "f.ts");
				let replaced = "";
				let at = 0;
				for (const replacement of replacements) {
					replaced += baseContent.slice(at, replacement.matchIndex) + replacement.newText;
					at = replacement.matchIndex + replacement.matchLength;
				}
				expect(replaced + baseContent.slice(at), label).toBe(newContent);
				return { baseContent, newContent };
			};
			expect(
				captureResult(() => applied(content)),
				label,
			).toEqual(expected);
			// Same content again, as the edit preview and the edit itself do.
			expect(
				captureResult(() => applied(content.slice(0))),
				label,
			).toEqual(expected);
			for (const edit of edits) {
				expect(fuzzyFindText(content, edit.oldText), label).toEqual(reference.fuzzyFindText(content, edit.oldText));
			}
		}
	});
});
