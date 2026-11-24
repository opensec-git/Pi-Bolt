import { describe, expect, test } from "vitest";
import { REGEX_WORK_BUDGET, regexWork } from "../src/core/tools/regex-work.ts";

// #342: the synthetic file of the issue, a 327 KB line with 800 "get" and 800 "key" and no "operation".
const issueLine = `get ${"x".repeat(200)} key ${"y".repeat(200)}`.repeat(800);
const issuePattern = "Retrieve.*key.*operation|Get.*Key.*Operation|only key operation";

describe("the bound", () => {
	// #342
	test("the issue's pattern does not fit its line; ordinary lines do", () => {
		const work = regexWork(issuePattern, true);
		expect(work.fits(issueLine)).toBe(false);
		expect(work.fits(`const value = cache.get(key); // get the key for the operation`)).toBe(true);
		// A long line with few of the words: each "get" is tried with each place after it, but only up to the "key"s.
		expect(work.fits(`${"z".repeat(100_000)} get a key ${"z".repeat(100_000)}`)).toBe(true);
	});

	test("alternatives of plain words fit any line", () => {
		const work = regexWork("TODO|FIXME|XXX", false);
		expect(work.maxLength).toBeGreaterThan(500_000);
		expect(work.fits("TODO ".repeat(100_000))).toBe(true);
	});

	test("classes that cannot run long, on a long line", () => {
		const work = regexWork("function\\s+\\w+\\s*\\(", false);
		const minified = "function a(b){return b}var c=function d(e){return e};".repeat(4000);
		expect(minified.length).toBeGreaterThan(200_000);
		expect(work.fits(minified)).toBe(true);
		// Only one place goes past "function" there; without the word, \w+ runs from each place of a long word to its end.
		expect(work.fits(`function ${"w".repeat(20_000)}`)).toBe(true);
		expect(regexWork("\\w+\\s+\\w+\\(", false).fits("w".repeat(20_000))).toBe(false);
	});

	test("nested repeats fit only short texts; what the bound does not follow fits none", () => {
		for (const [pattern, text] of [
			["(a+)+b", "a".repeat(100)],
			["(x|xx)*y", "x".repeat(100)],
			["^(\\w+\\s?)*$", `${"a".repeat(100)}!`],
			["(\\w+\\.)+com", "a.".repeat(50)],
		]) {
			const work = regexWork(pattern, false);
			expect(work.maxLength, pattern).toBeLessThan(40);
			expect(work.fits(text), pattern).toBe(false);
		}
		// Without the characters it repeats, a nested repeat matches in one way; and it repeats only within their runs.
		expect(regexWork("(x|xx)*y", false).fits("a".repeat(10_000))).toBe(true);
		expect(regexWork("(o|n)+ly", false).fits("only key operation ".repeat(1000))).toBe(true);
		// A group repeated in one way only (fixed width, no alternative inside) is linear.
		expect(regexWork("(?:ab)+c", false).maxLength).toBeGreaterThan(500);
		// Inline flags are not followed.
		expect(regexWork("(?i:abc)", false).maxLength).toBe(-1);
		expect(regexWork("(?i:abc)", false).fits("x")).toBe(false);
	});

	test("the bound grows with the length, and the text's own bound is never above it", () => {
		for (const pattern of [issuePattern, "a.*b", "\\s*=\\s*", "x{2,5}y?z", "(?<=a)b(?=c)", "(a)\\1", '[^"]*"']) {
			const work = regexWork(pattern, false);
			let last = 0;
			for (const length of [0, 10, 100, 1000]) {
				const steps = work.steps(length);
				expect(steps, pattern).toBeGreaterThanOrEqual(last);
				last = steps;
			}
			const text = 'ab = "c" a b c'.repeat(70);
			expect(work.steps(text), pattern).toBeLessThanOrEqual(work.steps(text.length));
		}
	});

	// The bound is of the worst case: an expression that fits runs within about the budget's time, whatever the text holds.
	test("what fits runs quickly", () => {
		const cases: Array<[string, boolean, string]> = [
			["x+ needle", false, `${"x".repeat(1500)} needlf`],
			["a.*a.*a.*b", false, "a".repeat(40)],
			["\\w+\\s+\\w+\\(", false, "abcdefghij".repeat(100)],
			["foo|bar|baz|qux", true, "q".repeat(400_000)],
			[issuePattern, true, issueLine.slice(0, 2000)],
		];
		for (const [pattern, ignoreCase, text] of cases) {
			const work = regexWork(pattern, ignoreCase);
			expect(work.steps(text), pattern).toBeLessThanOrEqual(REGEX_WORK_BUDGET);
			const expression = new RegExp(pattern, ignoreCase ? "gim" : "gm");
			const start = performance.now();
			expression.exec(text);
			expect(performance.now() - start, pattern).toBeLessThan(500);
		}
	});
});
