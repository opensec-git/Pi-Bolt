import assert from "node:assert";
import { describe, it } from "node:test";
import {
	graphemesOfForTest,
	standaloneClustersForTest,
	truncateToWidth,
	visibleWidth,
	wrapTextWithAnsi,
} from "../src/utils.ts";

const segmenter = new Intl.Segmenter(undefined, { granularity: "grapheme" });
const reference = (text: string) => [...segmenter.segment(text)].map(({ segment }) => segment);

// Code points that graphemesOf splits off without Intl.Segmenter when their neighbours are standalone too.
const PICTOGRAPH_RANGES: [number, number][] = [
	[0x1f300, 0x1f3fa],
	[0x1f400, 0x1f64f],
	[0x1f680, 0x1f6ff],
	[0x1f900, 0x1f9ff],
	[0x1fa70, 0x1faff],
];

describe("standalone grapheme clusters", () => {
	it("every standalone pictograph is a cluster of its own between standalone characters", () => {
		for (const [low, high] of PICTOGRAPH_RANGES) {
			for (let cp = low; cp <= high; cp++) {
				const c = String.fromCodePoint(cp);
				for (const text of [`a${c}b`, `${c}${c}`, `→${c}中`, `é${c}${c}x`]) {
					assert.deepStrictEqual(graphemesOfForTest(text), reference(text), `U+${cp.toString(16)} in ${text}`);
					assert.deepStrictEqual(
						standaloneClustersForTest(text),
						reference(text),
						`U+${cp.toString(16)} in ${text}`,
					);
				}
			}
		}
	});

	it("matches Intl.Segmenter for every BMP character next to an emoji", () => {
		for (let cp = 0; cp <= 0xffff; cp++) {
			if (cp >= 0xd800 && cp <= 0xdfff) continue;
			const c = String.fromCharCode(cp);
			const text = `a${c}😀${c}b`;
			assert.deepStrictEqual(graphemesOfForTest(text), reference(text), `U+${cp.toString(16)}`);
			const split = standaloneClustersForTest(text);
			if (split) assert.deepStrictEqual(split, reference(text), `U+${cp.toString(16)}`);
		}
	});

	it("keeps emoji sequences, modifiers, flags and combining marks whole", () => {
		const pieces = [
			"a",
			" ",
			"é",
			"中",
			"→",
			"😀",
			"👨",
			"❤",
			"‍",
			"️",
			"\u{1f3fb}",
			"\u{1f1fa}",
			"\u{1f1f8}",
			"é",
			"́",
			"क",
			"्",
			"\r",
			"\n",
			"ᄀ",
			"ᅡ",
			"가",
			"\u{e0067}",
			"\ud83d",
		];
		let seed = 1;
		const next = (n: number) => {
			seed = (seed * 1103515245 + 12345) % 2147483648;
			return seed % n;
		};
		for (let round = 0; round < 5000; round++) {
			let text = "";
			const length = 1 + next(10);
			for (let i = 0; i < length; i++) text += pieces[next(pieces.length)];
			assert.deepStrictEqual(graphemesOfForTest(text), reference(text), JSON.stringify(text));
			const split = standaloneClustersForTest(text);
			if (split) assert.deepStrictEqual(split, reference(text), JSON.stringify(text));
		}
	});

	it("gives a run that needs Intl.Segmenter to it whole", () => {
		// Measuring, truncating and wrapping split only runs of standalone characters themselves.
		assert.strictEqual(standaloneClustersForTest("Привет — мир"), undefined);
		assert.strictEqual(standaloneClustersForTest("a → e\u0301 b"), undefined);
		assert.strictEqual(standaloneClustersForTest("x👍\u{1f3fd}"), undefined);
		assert.deepStrictEqual(standaloneClustersForTest("⠋ café 日本 😀"), [..."⠋ café 日本 😀"]);
	});

	it("measures and truncates a line with an emoji as before", () => {
		const line = "unicode: naïve café 日本語 😀 end";
		assert.strictEqual(visibleWidth(line), 33);
		assert.strictEqual(truncateToWidth(line, 29, ""), "unicode: naïve café 日本語 😀\x1b[0m");
		assert.strictEqual(truncateToWidth(line, 28, ""), "unicode: naïve café 日本語 \x1b[0m");
		assert.strictEqual(visibleWidth("x👍\u{1f3fd}y"), 4);
		assert.deepStrictEqual(wrapTextWithAnsi("ab 😀 cd 😀😀 日本語 é", 5), ["ab 😀", "cd", "😀😀", "日本", "語 é"]);
	});
});
