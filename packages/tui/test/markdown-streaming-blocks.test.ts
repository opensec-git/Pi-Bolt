import assert from "node:assert";
import { afterEach, describe, it } from "node:test";
import type { Token, Tokens } from "marked";
import {
	type DefaultTextStyle,
	lexMarkdownForTest,
	Markdown,
	type MarkdownOptions,
	type MarkdownTheme,
	setMarkdownCachingForTest,
} from "../src/components/markdown.ts";
import { resetCapabilitiesCache, setCapabilities } from "../src/terminal-image.ts";
import { defaultMarkdownTheme } from "./test-themes.ts";

// A list or table that streams in is lexed again from its last items or rows, and rendered again from the lines of the
// items or rows before them; a long paragraph is lexed without searching the rest of it for LaTeX at each token. At every
// length a reply passes through, the lines must be those a fresh full render gives, escape for escape.

function repeat(count: number, line: (i: number) => string): string {
	return Array.from({ length: count }, (_, i) => line(i)).join("");
}

/** Documents like the streaming benchmark's (perf-work/tools/stream), shorter. */
const FIXTURES: Record<string, string> = {
	list: `Steps:\n\n${repeat(40, (i) => `- item ${i}: update the \`config${i}.json\` file and **restart** the service before moving on\n`)}\nDone list.\n`,
	table: `Results:\n\n| name | value | status | note |\n|---|---|---|---|\n${repeat(40, (i) => `| row${i} | ${i * 37} | ok | the quick brown fox number ${i} |\n`)}\nEnd.\n`,
	para: `Intro.\n\n${repeat(30, (i) => `This is sentence number ${i} of a single very long paragraph with \`code${i}\` and *emphasis*. `)}\n\nEnd.\n`,
	prose: repeat(
		12,
		(i) =>
			`Paragraph ${i}: the quick brown fox jumps over the lazy dog and keeps running through the field until the sun sets behind the hills, again and again.\n\n`,
	),
	code: `Here is the module.\n\n\`\`\`ts\n${repeat(20, (i) => `export const value${i} = await ctx.db.query<Item>("select * from items where id > $1", [${i}]);\n`)}\`\`\`\n\nDone.\n`,
};

const DOCUMENTS: Record<string, string> = {
	nestedMixed: `Intro\n\n- one\n- two\n  1. two a\n  2. two b\n     - deep *x*\n     - deeper \`y\`\n  3. two c\n- three\n  - three a\n\n  continued three\n- four\n* star list\n* star two\n+ plus\n\n1) paren one\n2) paren two\n10) paren ten\n\n7. seven\n8. eight\n9. nine\n10. ten\n11. eleven\n`,
	tightThenLoose: `- a\n- b\n- c\n- d\n\n- e after a blank line\n- f\n- g\n`,
	looseFromStart: `- a\n\n- b\n\n- c\n\n- d\n- e\n\n  para in e\n- f\n`,
	looseLate: `1. first\n2. second\n3. third\n4. fourth\n5. fifth\n   \n   still fifth after a blank\n6. sixth\n`,
	tasks: `- [ ] open task\n- [x] done task\n- [X] done again\n- [ ] task with \`code\`\n  - [ ] nested task\n- not a task\n- [ ]\n- [x] last\n\n1. [ ] ordered task\n2. [x] ordered done\n\n- [ ] loose task\n\n- [x] loose done\n`,
	listEdges: `- a\n- b\n-\n- c\n--\n- d\n---\n- e\n- - -\n- f\n2\n- g\n  \`\`\`sh\n  ls -la\n  \`\`\`\n- h\n  > quoted in item\n- i\n# heading after list\n- j\n<div>html</div>\n- k\n    indented code after item\n`,
	listHtml: `- a <a href="https://example.com">open link\n- b www.example.com\n- c </a> closed\n- d www.example.org\n- e <pre>raw\n- f text\n- g </pre>\n- h https://example.net\n- i <br> j\n- k <b>bold</b>\n- l www.last.example\n`,
	tables: `| a | b | c |\n|:--|:-:|--:|\n| \`x | y\` | z \\| w | 1 |\n| **bold** | [link](https://example.com) | $x^2$ |\n| short | s | 2 |\n| this one is much longer than the others | s | 3 |\n| s | s |\n| s | s | s | extra |\n| <br> | a<br>b | <a href="x">open |\n| www.example.com | c | d |\n|\n\n| h |\n|---|\n| 1 |\n- list line ends the table\n\n| p | q |\n|---|---|\n| 1 | 2 |\n> quote ends it\n\n| p | q |\n|---|---|\n| 1 | 2 |\n   \n`,
	tableWidening: `| key | value |\n|-----|-------|\n| a | 1 |\n| b | 2 |\n| c | 3 |\n| d | a value that is a great deal wider than every other value in this table so far and keeps going |\n| e | 5 |\n| ffffffffffffffffffffffffffffffffffffffffffffffffff | 6 |\n| g | 7 |\n`,
	paragraphs: `A long paragraph with *emphasis that closes late, after many words and \`code spans\` and [links](https://example.com) and more words* and **strong text with $x^2 + y^2$ inside** and \`code with * star\` and a link that [closes late after a while](https://example.com/late) and LaTeX \\(a + b\\) and \\[c\\] and $5 and $6 and $y = mx + b$ done. Escaped \\* star and \\_ underscore and ~~strike~~ and ~single~ and an email a@b.co and https://example.org/path?q=1 at the end.\nSecond line with hard break  \nthird line with backslash break\\\nfourth line.\n\nAnother *one\n\nwith an unclosed star and \`unclosed code\n\nand [unclosed link\n\nand $unclosed math\n`,
	unicode: `- 日本語のリスト項目 😀 emoji\n- 中文 **粗体** 和 *斜体*\n- 한국어 \`코드\`\n\n| 列 | 值 😀 |\n|---|---|\n| 一 | 二 |\n| 三三三三三三 | 四 🇮🇳 |\n\n日本語の段落、**強調**と\`コード\`。😀😀😀 の後に続く長い文章がここにあります。\n`,
	quotes: `> - quoted list a\n> - quoted list b\n>   - nested in quote\n> - quoted list c\n>\n> | t | u |\n> |---|---|\n> | 1 | 2 |\n> | 3 | 4 |\n\n> 1. one\n> 2. two\n`,
	hardBreaks: `- item with hard break  \n  continued line\n- item with backslash\\\n  continued\n- plain\n`,
	latex: `$$\nE = mc^2\n$$\n\n- item with $a^2$ math\n- item with \\(b\\) math\n- item with $$c$$ display\n\n| m | n |\n|---|---|\n| $x$ | \\(y\\) |\n| $$z$$ | w |\n`,
	// A `$$` or `\[` line lexes as a paragraph until the math block's closer arrives, which then takes the blank lines and
	// the paragraphs after it: such a paragraph is not settled by the blank line after it.
	displayMath: `The formula is:\n\n$$\n\n\\frac{a}{b} = c\n\n$$\n\nwhere the fraction is reduced.\n\nAnd also:\n\n   \\[\n\nx^2 + y^2 = z^2\n\n\\]\n\nthen more text.\n`,
	// A paragraph that leaves an inline <a> open: the paragraph after it is lexed in that state, so it is not settled by
	// the blank line after it. (Only two: a block further back is lexed again from the lexer's first state, on the base too.)
	openLink: `Use <a href="x">docs\n\nsee www.example.com for more and https://example.org at the end.\n`,
	// Comment-only HTML blocks render no line (earendil-works/pi#11710): they are left out of the tokens rendered, not the
	// tokens lexed, which a streaming reply is lexed from.
	htmlComments: `<!-- start -->\n\n## Notes\n\n<!-- a -->\n\n- item one\n- item <!-- inline --> two\n\n  <!-- in item -->\n\n  still two\n\n> q\n>\n> <!-- quoted -->\n>\n> r\n\nSee <!-- todo --> the docs.\n\n<!-- multi\nline\n-->\ntext after\n\n<!-- unclosed at the end`,
};

/** Lines a fresh component gives with caching off: everything lexed and rendered from scratch. */
function freshRender(
	source: string,
	width: number,
	paddingX = 1,
	style?: DefaultTextStyle,
	options?: MarkdownOptions,
	theme: MarkdownTheme = defaultMarkdownTheme,
): string[] {
	setMarkdownCachingForTest(false);
	try {
		return new Markdown(source, paddingX, 0, theme, style, options).render(width);
	} finally {
		setMarkdownCachingForTest(true);
	}
}

function freshTokens(source: string): string {
	setMarkdownCachingForTest(false);
	try {
		return JSON.stringify(lexMarkdownForTest(source));
	} finally {
		setMarkdownCachingForTest(true);
	}
}

function random(seed: number): () => number {
	let state = seed >>> 0 || 1;
	return () => {
		state = (Math.imul(state ^ (state >>> 15), 2246822507) + 0x9e3779b9) >>> 0;
		return state / 4294967296;
	};
}

/** A prefix of its own, so that nothing kept from another test is taken up. */
function unique(name: string, text: string): string {
	return `<!-- ${name} ${Math.random()} -->\n\n${text}`;
}

/**
 * Streams `document` into one component, as a reply streams in: setText() for each chunk, renderLive() after some of
 * them. At each render the lines are those of a fresh full render, and the lines before the first one the component says
 * changed are the lines of the render before.
 */
function streamLive(
	name: string,
	document: string,
	chunk: () => number,
	width: number,
	options: {
		renders?: () => boolean;
		paddingX?: number;
		style?: DefaultTextStyle;
		markdown?: MarkdownOptions;
		theme?: MarkdownTheme;
	} = {},
): void {
	const theme = options.theme ?? defaultMarkdownTheme;
	const component = new Markdown("", options.paddingX ?? 1, 0, theme, options.style, options.markdown);
	let before: { lines: string[]; revision: number } | undefined;
	for (let length = 0; length < document.length; ) {
		length = Math.min(document.length, length + chunk());
		const source = document.slice(0, length);
		component.setText(source);
		if (length < document.length && options.renders?.() === false) continue;
		const live = component.renderLive(width);
		const lines = live.lines.slice();
		const fresh = freshRender(source, width, options.paddingX ?? 1, options.style, options.markdown, theme);
		if (lines.join("\n") !== fresh.join("\n")) {
			assert.deepStrictEqual(
				lines,
				fresh,
				`${name}: lines differ at length ${length}: ${JSON.stringify(source.slice(-60))}`,
			);
		}
		if (before !== undefined) {
			const changed = Math.min(live.changedSince(before.revision), lines.length, before.lines.length);
			for (let i = 0; i < changed; i++) {
				if (lines[i] !== before.lines[i]) {
					assert.fail(
						`${name}: line ${i} changed before the first line said to change (${changed}) at length ${length}`,
					);
				}
			}
		}
		before = { lines, revision: live.revision };
	}
}

/** Lexes and renders each prefix as a new component (lexMarkdown()'s own memory only), and compares tokens and lines. */
function streamPrefixes(name: string, document: string, chunk: () => number, width: number): void {
	for (let length = Math.min(20, document.length); ; length = Math.min(document.length, length + chunk())) {
		const source = document.slice(0, length);
		const tokens = JSON.stringify(lexMarkdownForTest(source));
		const rendered = new Markdown(source, 1, 0, defaultMarkdownTheme).render(width);
		const fresh = freshTokens(source);
		if (tokens !== fresh) {
			assert.strictEqual(
				tokens,
				fresh,
				`${name}: tokens differ at length ${length}: ${JSON.stringify(source.slice(-60))}`,
			);
		}
		assert.deepStrictEqual(rendered, freshRender(source, width), `${name}: lines differ at length ${length}`);
		if (length >= document.length) break;
	}
}

describe("Markdown streaming: lists, tables and paragraphs at every length", () => {
	const all = { ...DOCUMENTS, ...FIXTURES };
	for (const [name, text] of Object.entries(all)) {
		it(`${name}: every prefix, tokens and lines`, () => {
			streamPrefixes(name, unique(name, text), () => 1, 72);
		});
		it(`${name}: every prefix into one component, at three widths`, () => {
			for (const width of [100, 40, 14]) {
				streamLive(`${name}@${width}`, unique(name, text), () => 1, width);
			}
		});
	}

	it("random chunks, renders skipped now and then, other widths, styles and options", () => {
		const names = Object.keys(all);
		const styles: Array<DefaultTextStyle | undefined> = [
			undefined,
			{ color: (text) => `\x1b[33m${text}\x1b[39m`, italic: true },
			{ bgColor: (text) => `\x1b[44m${text}\x1b[49m` },
		];
		let cases = 0;
		for (let seed = 1; seed <= 60; seed++) {
			const next = random(seed * 6151);
			const name = names[seed % names.length]!;
			const width = 8 + Math.floor(next() * 110);
			const markdown: MarkdownOptions | undefined =
				seed % 5 === 0 ? { preserveOrderedListMarkers: true } : seed % 7 === 0 ? { renderLatex: false } : undefined;
			const maxChunk = seed % 3 === 0 ? 4 : 60;
			const document = unique(`${name}#${seed}`, all[name]!);
			streamLive(`${name}#${seed}`, document, () => 1 + Math.floor(next() * maxChunk), width, {
				renders: () => next() < 0.7,
				paddingX: seed % 4,
				style: styles[seed % styles.length],
				markdown,
			});
			cases++;
		}
		assert.strictEqual(cases, 60);
	});

	it("random documents made of the corpus's blocks, in random order and random chunks", () => {
		// (Not the blocks that leave an inline `<a>` or `<pre>` open: a top-level block lexed again is lexed from the inline
		// lexer's first state, not from the one the blocks before it leave, and a link after such a block reads differently.
		// That is so of all the blocks after the last two, before lists and tables were lexed from their last items.
		// Nor a `$$` or `\[` block cut from its math: put in front of other blocks, a paragraph holding it is kept as the
		// blocks before the last two are when the math block that takes them opens later, on the base as well.)
		const blocks = Object.values(all)
			.join("\n\n")
			.split(/\n{2,}/)
			.filter((block) => block.trim() && !/<a |<pre>/.test(block) && !/(?:^|\n) {0,3}(?:\$\$|\\\[)/.test(block));
		for (let seed = 1; seed <= 40; seed++) {
			const next = random(seed * 92821);
			let text = "";
			for (let i = 0; i < 12; i++) {
				text += blocks[Math.floor(next() * blocks.length)] + (next() < 0.7 ? "\n\n" : "\n");
			}
			const document = unique(`blocks#${seed}`, text);
			streamPrefixes(`blocks#${seed}`, document, () => 1 + Math.floor(next() * 30), 64);
			streamLive(`blocks#${seed}`, document, () => 1 + Math.floor(next() * 30), 50, { renders: () => next() < 0.6 });
		}
	});
});

describe("Markdown streaming: what is reused", () => {
	it("a list's settled items are the same objects, and the lines before the last items do not change", () => {
		const document = unique("reuse list", FIXTURES.list!);
		const component = new Markdown("", 1, 0, defaultMarkdownTheme);
		let items: Tokens.ListItem[] | undefined;
		let revision: number | undefined;
		let late = 0;
		let checks = 0;
		for (let length = 200; length <= document.length; length += 7) {
			const source = document.slice(0, length);
			const list = lexMarkdownForTest(source).find((token): token is Tokens.List => token.type === "list");
			if (list !== undefined && items !== undefined && list.items.length > 3) {
				for (let i = 0; i < Math.min(items.length, list.items.length) - 2; i++) {
					assert.strictEqual(list.items[i], items[i], `item ${i} at length ${length}`);
					checks++;
				}
			}
			items = list?.items;
			component.setText(source);
			const live = component.renderLive(80);
			if (revision !== undefined && list !== undefined && list.items.length > 10) {
				// (Two lines a item at most, and the comment and the first lines before the list.)
				if (live.changedSince(revision) >= 2 * (list.items.length - 4)) late++;
			}
			revision = live.revision;
		}
		assert.ok(checks > 1000, `only ${checks} items were compared`);
		assert.ok(late > 300, `only ${late} renders changed late in the list`);
	});

	it("a table's settled rows are the same objects", () => {
		const document = unique("reuse table", FIXTURES.table!);
		let rows: Tokens.TableCell[][] | undefined;
		let checks = 0;
		for (let length = 150; length <= document.length; length += 5) {
			const table = lexMarkdownForTest(document.slice(0, length)).find(
				(token): token is Tokens.Table => token.type === "table",
			);
			if (table !== undefined && rows !== undefined) {
				for (let i = 0; i < Math.min(rows.length, table.rows.length) - 1; i++) {
					assert.strictEqual(table.rows[i], rows[i], `row ${i} at length ${length}`);
					checks++;
				}
			}
			rows = table?.rows;
		}
		assert.ok(checks > 500, `only ${checks} rows were compared`);
	});

	it("a list that turns loose late is lexed in full again", () => {
		const head = unique(
			"loose late",
			repeat(10, (i) => `- item ${i}\n`),
		);
		lexMarkdownForTest(head);
		lexMarkdownForTest(`${head}- item 10`);
		const tokens: Token[] = lexMarkdownForTest(`${head}- item 10\n\n- item 11`);
		const list = tokens.find((token): token is Tokens.List => token.type === "list")!;
		assert.strictEqual(list.loose, true);
		assert.strictEqual(JSON.stringify(tokens), freshTokens(`${head}- item 10\n\n- item 11`));
	});

	it("items and rows rendered before a resize or a theme change are rendered again", () => {
		let mark = "a";
		const theme = {
			...defaultMarkdownTheme,
			listBullet: (text: string) => `${mark}${text}`,
			bold: (text: string) => `${mark}${text}`,
		};
		const document = unique("resize", `${FIXTURES.list}\n${FIXTURES.table}`);
		const component = new Markdown("", 1, 0, theme);
		const widths = [80, 80, 80, 50, 50, 120, 120, 120, 33];
		let step = 0;
		for (let length = 100; length <= document.length; length += 13) {
			const source = document.slice(0, length);
			component.setText(source);
			if (step % 17 === 16) {
				mark = mark === "a" ? "b" : "a";
				component.invalidate();
			}
			const width = widths[step++ % widths.length]!;
			const lines = component.renderLive(width).lines.slice();
			setMarkdownCachingForTest(false);
			const fresh = new Markdown(source, 1, 0, theme).render(width);
			setMarkdownCachingForTest(true);
			assert.deepStrictEqual(lines, fresh, `at length ${length}, width ${width}, mark ${mark}`);
		}
	});
});

// Issue #297: links that show their URL, links that are hyperlinks, and local file links that are both, in paragraphs, a
// list and a table, the first blocks reused while the last ones stream in.
const LINKS = `Open [the image](file:///C:/Temp/example.png) or [a local one](file://localhost/C:/Temp/example.png), [x.png](file:///tmp/x.png), [GitHub](https://github.com) and [settings](x-apple.systempreferences:com.apple.preference.security).\n\n- [${"long ".repeat(6)}label](file:///C:/Temp/${"deep/".repeat(12)}example.png)\n- <file:///tmp/y.png> and [run](javascript:alert(1))\n- [share](file://server/share/x.png)\n\n| file | web |\n|---|---|\n| [image](file:///tmp/x.png) | [docs](https://example.com/docs) |\n| [other](vscode://file/etc/passwd) | [mail](mailto:a@example.com) |\n\nThe end, with [an open link](file:///tmp/open`;

describe("Markdown streaming: links, with hyperlinks on and off", () => {
	afterEach(() => resetCapabilitiesCache());

	for (const hyperlinks of [true, false]) {
		it(`every prefix into one component, at three widths (hyperlinks ${hyperlinks ? "on" : "off"})`, () => {
			setCapabilities({ images: null, trueColor: false, hyperlinks });
			for (const width of [100, 40, 14]) {
				streamLive(`links@${width}`, unique("links", LINKS), () => 1, width);
			}
		});
	}
});

/**
 * A highlighter with state from line to line, as highlight.js has: a block comment colours every line up to its end, and
 * the lines of a comment not closed yet are drawn as code until it is (so earlier lines change when later text comes).
 */
function highlightWithState(code: string): string[] {
	const closed = code.lastIndexOf("*/");
	let comment = false;
	return code.split("\n").map((line, index, lines) => {
		const start = lines.slice(0, index).reduce((sum, before) => sum + before.length + 1, 0);
		let drawn = "";
		for (let i = 0; i < line.length; i++) {
			if (!comment && line.startsWith("/*", i) && code.indexOf("*/", start + i + 2) !== -1) comment = true;
			const char = line[i]!;
			drawn += comment ? `\x1b[2m${char}\x1b[22m` : /[A-Z]/.test(char) ? `\x1b[33m${char}\x1b[39m` : char;
			if (comment && line.startsWith("*/", i - 1) && start + i <= closed + 1) comment = false;
		}
		return drawn;
	});
}

const HIGHLIGHTING: MarkdownTheme = { ...defaultMarkdownTheme, highlightCode: highlightWithState };
const SHADED: MarkdownTheme = { ...HIGHLIGHTING, codeBlockBg: (text) => `\x1b[48;5;236m${text}\x1b[49m` };

const CODE_DOCUMENTS: Record<string, string> = {
	codeBlocks: `Before.\n\n\`\`\`ts\n${repeat(12, (i) => `const Value${i} = "a string ${i}"; // A comment that is long enough to wrap at a narrow width ${i}\n`)}/* A block comment\nover Three lines\nClosed here */ after();\n/* Never closed\nStill Open\n\`\`\`\n\nAfter the block.\n\n\`\`\`py\nx = 1\n\ty = 2\n\`\`\`\n\n~~~\nPlain fence\n~~~\n\n- item\n\n  \`\`\`sh\n  echo In a list\n  \`\`\`\n\nThe end, with an open fence:\n\n\`\`\`js\nlet A = 1;\nlet B = 2;\n`,
	codeEmptyLines: `\`\`\`\n\n\n   \nX\n\n\`\`\`\n\`\`\`go\n\`\`\`\n\`\`\`\`md\n\`\`\`inner\`\`\`\n\`\`\`\`\n`,
};

describe("Markdown streaming: code blocks, highlighted and shaded, at every length", () => {
	const themes: Array<[string, MarkdownTheme]> = [
		["plain", defaultMarkdownTheme],
		["highlighted", HIGHLIGHTING],
		["shaded", SHADED],
	];
	for (const [name, text] of Object.entries({ ...CODE_DOCUMENTS, code: FIXTURES.code! })) {
		for (const [themeName, theme] of themes) {
			it(`${name}, ${themeName}: every prefix into one component, at three widths`, () => {
				for (const width of [100, 40, 14]) {
					streamLive(`${name}/${themeName}@${width}`, unique(name, text), () => 1, width, { theme });
				}
			});
		}
	}

	it("random chunks, renders skipped, resizes, styles and padding", () => {
		const names = Object.keys(CODE_DOCUMENTS);
		for (let seed = 1; seed <= 30; seed++) {
			const next = random(seed * 7919);
			const name = names[seed % names.length]!;
			const document = unique(`${name}#${seed}`, CODE_DOCUMENTS[name]!);
			streamLive(`${name}#${seed}`, document, () => 1 + Math.floor(next() * 40), 10 + Math.floor(next() * 100), {
				renders: () => next() < 0.7,
				paddingX: seed % 3,
				style: seed % 2 === 0 ? { bgColor: (line) => `\x1b[44m${line}\x1b[49m` } : undefined,
				theme: themes[seed % themes.length]![1],
			});
		}
	});

	it("while a long block streams in, its lines before the last few are not drawn again", () => {
		for (const theme of [HIGHLIGHTING, SHADED]) {
			const document = unique("reuse code", `\`\`\`ts\n${repeat(150, (i) => `const Value${i} = compute(${i});\n`)}`);
			const component = new Markdown("", 1, 0, theme);
			let before: string[] | undefined;
			let revision: number | undefined;
			let late = 0;
			let same = 0;
			for (let length = 200; length <= document.length; length += 23) {
				component.setText(document.slice(0, length));
				const live = component.renderLive(80);
				if (before !== undefined && revision !== undefined) {
					if (live.changedSince(revision) >= live.lines.length - 4) late++;
					// (The same strings: drawn once, taken again.)
					for (let i = 0; i < Math.min(before.length, live.lines.length) - 4; i++) {
						if (live.lines[i] === before[i]) same++;
					}
				}
				before = live.lines.slice();
				revision = live.revision;
			}
			assert.ok(late > 150, `only ${late} renders changed only the last lines`);
			assert.ok(same > 10000, `only ${same} lines were kept`);
		}
	});
});
