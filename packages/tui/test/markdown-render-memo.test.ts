import assert from "node:assert";
import { describe, it } from "node:test";
import { Chalk } from "chalk";
import {
	lexMarkdownForTest,
	Markdown,
	type MarkdownTheme,
	setMarkdownCachingForTest,
	setMarkdownMemosForTest,
} from "../src/components/markdown.ts";
import { renderLiveLines } from "../src/tui.ts";
import { wrapTextWithAnsi } from "../src/utils.ts";
import { defaultMarkdownTheme } from "./test-themes.ts";

// A reply that streams in is lexed and rendered again for each chunk. Its Markdown component keeps the lines it wrapped the
// time before, and the lexer keeps the inline tokens of the texts it lexed the time before (with the lexer's state before
// and after each). What it renders must be what rendering from nothing gives (no caches, marked's own lexer), and what it
// lexes must be what marked lexes.

const chalk = new Chalk({ level: 3 });

/** The default theme, with code highlighted into many colored spans per line as Pi's is. */
const highlightingTheme: MarkdownTheme = {
	...defaultMarkdownTheme,
	highlightCode: (code) =>
		code.split("\n").map((line) => line.replace(/\w+|[^\w\s]+/g, (word) => chalk.hex("#a0c0e0").bold(word))),
};

function random(seed: number): () => number {
	let state = seed >>> 0 || 1;
	return () => {
		state = (Math.imul(state ^ (state >>> 15), 2246822507) + 0x9e3779b9) >>> 0;
		return state / 4294967296;
	};
}

// Pieces that change how the inline texts after them lex (a raw HTML <a> or <pre> left open, a link, link definitions),
// texts that repeat (list items, table cells), and lines long enough to be wrapped.
const PIECES = [
	"- item {w}\n",
	"- item {w} with [a link](https://example.com/{w}) and **bold**\n",
	"  - nested {w}\n",
	"1. one {w}\n",
	"2. two\n",
	"lazy {w}\n",
	"Text {w} with **bold**, *it*, `code`, ~~del~~ and [a link](https://example.com/{w}).\n",
	"A long line of text {w} that has to be wrapped at the narrower widths this test renders the reply at, twice over.\n",
	"\n",
	"\n\n",
	"```ts\nconst {w} = 1; // a comment that is long enough to be wrapped at a narrow width\nlet b = `x`;\n```\n",
	"```\nunclosed {w}\n",
	"> quote {w} with a [link](https://example.com)\n",
	"| a | b |\n",
	"|---|:-:|\n",
	"| {w} | [x](https://x.y) |\n",
	"| same | cell |\n",
	"# Heading {w}\n",
	"Setext {w}\n",
	"===\n",
	'<a href="https://example.com">open link {w} ',
	"</a> closed\n",
	"<pre>raw {w} ",
	"</pre>\n",
	"<b>bold {w}</b> inline html\n",
	"[ref {w}]\n",
	"[ref 1] and [ref 2][ref 1] again\n",
	"[ref 1]: https://example.com/one\n",
	'[ref 2]: https://example.com/two "Two"\n',
	"![image {w}](https://example.com/i.png)\n",
	"[outer [inner](https://a.b) text](https://c.d)\n",
	"*[emphasized link](https://e.f)* and https://auto.link/{w}\n",
	"- [ ] task {w}\n",
	"- [x] done {w}\n\n",
	"$x^{w}$ and \\(y\\)\n",
	"trailing spaces  \n",
	"日本語のテキスト {w} 😀 naïve\n",
];

function randomDocument(next: () => number): string {
	let text = "";
	const pieces = 8 + Math.floor(next() * 30);
	for (let i = 0; i < pieces; i++) {
		text += PIECES[Math.floor(next() * PIECES.length)]!.replaceAll("{w}", String(Math.floor(next() * 50)));
	}
	return text;
}

/** What `source` renders to and lexes to with nothing kept: no caches, marked's own lexer. */
function fresh(source: string, width: number, theme: MarkdownTheme) {
	setMarkdownCachingForTest(false);
	try {
		return {
			lines: new Markdown(source, 1, 0, theme).render(width),
			tokens: JSON.stringify(lexMarkdownForTest(source)),
		};
	} finally {
		setMarkdownCachingForTest(true);
	}
}

/**
 * Streams `text` into one Markdown component in chunks of `sizes`, and returns what it renders to and the tokens it renders
 * from after each chunk, then once complete.
 */
function streamedRenders(
	text: string,
	sizes: readonly number[],
	widths: readonly number[],
	theme: MarkdownTheme,
): string[] {
	const width = widths[0]!;
	// Nothing lexed for an earlier render is taken up: the sources kept (lexMarkdown()) are pushed out by others.
	for (let i = 0; i < 16; i++) new Markdown(`flush ${i} ${Math.random()}`, 1, 0, theme).render(width);
	const component = new Markdown("", 1, 0, theme);
	const renders: string[] = [];
	let step = 0;
	for (let length = 1; ; length = Math.min(text.length, length + sizes[step++ % sizes.length]!)) {
		const source = text.slice(0, length);
		component.setText(source);
		renders.push(JSON.stringify(component.render(widths[step % widths.length]!)));
		renders.push(JSON.stringify(lexMarkdownForTest(source)));
		if (length >= text.length) break;
	}
	const complete = new Markdown(text, 1, 0, theme, undefined, { complete: true });
	renders.push(JSON.stringify(complete.render(width)));
	renders.push(JSON.stringify(lexMarkdownForTest(text, true)));
	return renders;
}

/** What streaming `text` renders and lexes with the memos must be what it renders and lexes without them. */
function streamAndCompare(
	text: string,
	sizes: readonly number[],
	widths: readonly number[],
	theme: MarkdownTheme,
	label: string,
): void {
	setMarkdownMemosForTest(false);
	let before: string[];
	try {
		before = streamedRenders(text, sizes, widths, theme);
	} finally {
		setMarkdownMemosForTest(true);
	}
	const after = streamedRenders(text, sizes, widths, theme);
	assert.strictEqual(after.length, before.length, label);
	for (let i = 0; i < before.length; i++) {
		if (after[i] !== before[i]) {
			const what = i % 2 === 0 ? "lines" : "tokens";
			assert.deepStrictEqual(JSON.parse(after[i]!), JSON.parse(before[i]!), `${label}: ${what}, step ${i >> 1}`);
		}
	}
}

describe("Markdown wrap and inline memos", () => {
	for (let seed = 1; seed <= 30; seed++) {
		it(`random document ${seed}, streamed into one component`, () => {
			const next = random(seed * 7919);
			for (let index = 0; index < 8; index++) {
				const text = randomDocument(next);
				const width = [20, 47, 160][Math.floor(next() * 3)]!;
				// (A third of the documents are drawn at another width every few chunks, as when the terminal is resized.)
				const widths = next() < 0.33 ? [width, width, width, 33, 33, 90] : [width];
				const theme = next() < 0.5 ? highlightingTheme : defaultMarkdownTheme;
				const sizes = Array.from({ length: 64 }, () =>
					next() < 0.3 ? 1 : 1 + Math.floor(next() * (next() < 0.5 ? 8 : 40)),
				);
				streamAndCompare(text, sizes, widths, theme, `seed ${seed}, document ${index}, widths ${widths.join(",")}`);
			}
		});
	}

	it("lexes the same texts in documents with and without link definitions", () => {
		// The same inline texts, one after the other in documents whose link definitions differ: a text with a "[" is
		// lexed again in a document with definitions, and what the last lex kept is not taken for it.
		const texts = [
			"See [ref 1] and [ref 2][ref 1].\n\n- [ref 1]\n- plain item\n",
			"See [ref 1] and [ref 2][ref 1].\n\n- [ref 1]\n- plain item\n\n[ref 1]: https://example.com/one\n",
			"See [ref 1] and [ref 2][ref 1].\n\n- [ref 1]\n- plain item\n",
			"[ref 1]: https://example.com/one\n\nSee [ref 1] and [ref 2][ref 1].\n\n- [ref 1]\n- plain item\n",
		];
		for (const [index, text] of texts.entries()) {
			for (const width of [30, 80]) {
				const source = `${text}\n<!-- ${index} ${width} -->`;
				assert.deepStrictEqual(
					new Markdown(source, 1, 0, defaultMarkdownTheme).render(width),
					fresh(source, width, defaultMarkdownTheme).lines,
					`text ${index}, width ${width}`,
				);
				assert.strictEqual(
					JSON.stringify(lexMarkdownForTest(source)),
					fresh(source, width, defaultMarkdownTheme).tokens,
				);
			}
		}
	});

	it("carries the lexer's state from one text to the next as marked does", () => {
		// An <a> or <pre> left open changes how the texts after it lex (inLink: no autolinks; inRawBlock: escaped text), and
		// a link sets linkEmitted: the same texts are lexed after each, and again, twice.
		const tail = "\n\nhttps://example.com/x and *em*\n\n- https://example.org item\n- *same*\n";
		for (const head of ['<a href="u">open', "<pre>open", "[a link](https://l.k)", "plain", "<a href='u'>x</a>"]) {
			for (let round = 0; round < 2; round++) {
				const source = `${head}${tail}<!-- ${round} -->`;
				assert.strictEqual(
					JSON.stringify(lexMarkdownForTest(source)),
					fresh(source, 80, defaultMarkdownTheme).tokens,
					head,
				);
			}
		}
	});

	it("lexes as marked does with the memos off", () => {
		setMarkdownMemosForTest(false);
		try {
			const source = "Text with *em* and [a](https://b.c)\n\n- one\n- two\n";
			assert.strictEqual(JSON.stringify(lexMarkdownForTest(source)), fresh(source, 80, defaultMarkdownTheme).tokens);
		} finally {
			setMarkdownMemosForTest(true);
		}
	});

	it("wraps a line of its own as the line followed by a line break", () => {
		// wrapTextWithAnsi() wraps a text without line breaks without splitting it into lines: what it gives is what it
		// gives for the same line followed by another.
		const next = random(5);
		const styles = [
			(text: string) => chalk.bold(text),
			(text: string) => chalk.red.underline(text),
			(text: string) => chalk.bgBlue(text),
			(text: string) => `\x1b]8;;https://x.y\x07${text}\x1b]8;;\x07`,
			(text: string) => text,
		];
		const words = ["a", "word", "longer-word", "日本語", "😀", "  ", "supercalifragilisticexpialidocious", "\t"];
		for (let i = 0; i < 2000; i++) {
			let line = "";
			const count = Math.floor(next() * 30);
			for (let w = 0; w < count; w++) {
				const word = words[Math.floor(next() * words.length)]!;
				line += `${styles[Math.floor(next() * styles.length)]!(word)} `;
			}
			const width = 1 + Math.floor(next() * 60);
			const single = wrapTextWithAnsi(line, width);
			const withBreak = wrapTextWithAnsi(`${line}\n`, width);
			assert.deepStrictEqual(single, withBreak.slice(0, single.length), JSON.stringify({ line, width }));
			assert.strictEqual(withBreak.length, single.length + 1);
		}
	});
});

describe("Markdown memos of components rendered long ago", () => {
	// Of a long transcript, only the components that rendered last keep what makes rendering their text again faster (the
	// lines they wrapped; a complete text's tokens); the others render again, at any width, the lines they rendered.
	const memos = (markdown: Markdown) => {
		const fields = markdown as unknown as { wraps?: unknown; live: { tokens?: unknown }; grown?: unknown };
		return {
			wraps: fields.wraps !== undefined,
			tokens: fields.live.tokens !== undefined || fields.grown !== undefined,
		};
	};
	const fresh = (text: string, complete: boolean, width: number, theme: MarkdownTheme) =>
		new Markdown(text, 1, 0, theme, undefined, { complete }).render(width);

	for (let seed = 1; seed <= 6; seed++) {
		it(`renders what a new component renders after its memos are dropped (seed ${seed})`, () => {
			const next = random(seed * 104729);
			const theme = seed % 2 === 0 ? highlightingTheme : defaultMarkdownTheme;
			const texts = Array.from({ length: 60 }, () => randomDocument(next));
			const components = texts.map(
				(text, i) => new Markdown(text, 1, 0, theme, undefined, { complete: i % 5 !== 0 }),
			);
			for (const component of components) renderLiveLines(component, 80);
			// (The first ones rendered dropped theirs; a text that is not complete keeps its tokens.)
			for (let i = 0; i < 20; i++) {
				assert.strictEqual(memos(components[i]!).wraps, false, `component ${i}`);
				assert.strictEqual(memos(components[i]!).tokens, i % 5 === 0, `component ${i}`);
			}
			assert.strictEqual(memos(components[components.length - 1]!).tokens, true);
			for (const width of [80, 47, 80, 120]) {
				for (let i = 0; i < components.length; i++) {
					const lines = renderLiveLines(components[i]!, width)!.lines;
					assert.deepStrictEqual(lines, fresh(texts[i]!, i % 5 !== 0, width, theme), `component ${i} at ${width}`);
				}
			}
		});
	}
});
