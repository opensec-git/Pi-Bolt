import assert from "node:assert";
import { describe, it } from "node:test";
import { lexMarkdownForTest, setMarkdownCachingForTest, setSetextGuardForTest } from "../src/components/markdown.ts";

// marked's lheading rule is skipped where it cannot match (mayStartSetextHeading()). The tokens must be the same as with the
// rule tried at every block.

const LINES = [
	"Some text with **bold** and `code`",
	"a longer line of a paragraph that goes on for a while, as the paragraphs of an answer do",
	"",
	"",
	"   ",
	"\t",
	" ",
	" 　 ",
	"\r",
	"text\r",
	"===",
	"=",
	"---",
	"-",
	"   ---",
	"   ===",
	"    ---",
	"    ===",
	"--- ",
	"===   ",
	"---\t",
	"-- -",
	"=-=",
	"- - -",
	"***",
	"___",
	"- item",
	"* item",
	"1. item",
	"2) item",
	"  - nested item",
	"  continued",
	"> quote",
	"> ---",
	"```",
	"```ts",
	"~~~",
	"# heading",
	"###### heading",
	"<div>",
	"</div>",
	"| a | b |",
	"|---|---|",
	"    indented code",
	"$$",
	"\\[",
	"[ref]: https://example.com",
	"text with a [ref] link",
	" ",
	"line ",
	"Setext",
];

function randomDocument(random: () => number, lines: number): string {
	const out: string[] = [];
	for (let i = 0; i < lines; i++) out.push(LINES[Math.floor(random() * LINES.length)]!);
	const tail = random();
	return out.join(random() < 0.1 ? "\r\n" : "\n") + (tail < 0.3 ? "\n" : tail < 0.4 ? "\n\n" : "");
}

function mulberry32(seed: number): () => number {
	let a = seed;
	return () => {
		a = (a + 0x6d2b79f5) | 0;
		let t = Math.imul(a ^ (a >>> 15), 1 | a);
		t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
		return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
	};
}

function lexBoth(source: string): { guarded: string; plain: string } {
	setMarkdownCachingForTest(false);
	try {
		setSetextGuardForTest(true);
		const guarded = JSON.stringify(lexMarkdownForTest(source, true));
		setSetextGuardForTest(false);
		const plain = JSON.stringify(lexMarkdownForTest(source, true));
		return { guarded, plain };
	} finally {
		setSetextGuardForTest(true);
		setMarkdownCachingForTest(true);
	}
}

describe("markdown setext guard", () => {
	it("random documents lex to the same tokens with and without the guard", () => {
		const random = mulberry32(20261027);
		let headings = 0;
		for (let n = 0; n < 4000; n++) {
			const source = randomDocument(random, 1 + Math.floor(random() * 14));
			const { guarded, plain } = lexBoth(source);
			assert.strictEqual(guarded, plain, JSON.stringify(source));
			if (plain.includes('"type":"heading"') && /\n {0,3}(=+|-+) *(\n|$)/.test(source)) headings++;
		}
		// (The documents do have setext headings, which the guard must leave to the rule.)
		assert.ok(headings > 100, `only ${headings} documents with an underline`);
	});

	it("an underline far below the start, past the lines the guard reads", () => {
		for (const at of [1, 30, 63, 64, 65, 100]) {
			for (const kind of ["===", "---", "   ---", "    ---"]) {
				const lines = Array.from({ length: 120 }, (_, i) => `line ${i} of a paragraph`);
				lines[at] = kind;
				const source = lines.join("\n");
				const { guarded, plain } = lexBoth(source);
				assert.strictEqual(guarded, plain, `${kind} at ${at}`);
			}
		}
	});

	it("blank lines that end the search", () => {
		const cases = [
			"Title\n===",
			"Title\n===\n",
			"Title\n  ---  \n\nafter",
			"Title\n \nnot a heading\n===",
			"Title\n \n===",
			"Title\n\t\n---",
			"Title\nmore\n\n===",
			"Title\nmore\n===\n\n",
			"Title\r\n===",
			"Title\n===\r\n",
			"\nTitle\n===",
			"\n\n===",
			"Title\n=== x",
			"- item\n  Title\n  ---",
			"> Title\n> ===",
			"Title\n    ===",
			"Title\n \n===",
		];
		for (const source of cases) {
			const { guarded, plain } = lexBoth(source);
			assert.strictEqual(guarded, plain, JSON.stringify(source));
		}
	});
});
