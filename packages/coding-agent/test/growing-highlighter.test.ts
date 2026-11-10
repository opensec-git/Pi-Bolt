import { readFileSync } from "node:fs";
import { join } from "node:path";
import hljs from "highlight.js/lib/core.js";
import { beforeAll, describe, expect, it } from "vitest";
import { highlightCode, initTheme } from "../src/modes/interactive/theme/theme.ts";
import { analyse } from "../src/utils/regex-reach.ts";
import {
	GROWING_HIGHLIGHT_MIN_LENGTH,
	GrowingHighlighter,
	type GrowingHighlightLimits,
	growingHighlightExpressions,
	type HighlightTheme,
	highlight,
	INCREMENTAL_HIGHLIGHT_LANGUAGES,
	loadAllHighlightLanguages,
} from "../src/utils/syntax-highlight.ts";
import { findBlowups } from "./regex-blowup.ts";

// GrowingHighlighter highlights a text that grows at its end (a code block streaming in) from where the lines before are
// final, instead of from its start. Its result must be the full highlighting of the text, byte for byte, at every length.
// Most tests follow texts of any length (FOLLOW_ALL: no threshold), so that short ones exercise the incremental path too;
// those of the threshold and of the work bound use the real limits.

const FOLLOW_ALL: GrowingHighlightLimits = { minLength: 0 };

const CORPUS = join(import.meta.dirname, "fixtures", "highlight-corpus");

/** A file of code per language Pi's users see most (`.txt`: not checked as source). */
const CORPUS_FILES: Record<string, string> = {
	typescript: "typescript.txt",
	javascript: "javascript.txt",
	python: "python.txt",
	bash: "bash.txt",
	json: "json.txt",
	rust: "rust.txt",
	go: "go.txt",
	c: "c.txt",
	cpp: "cpp.txt",
	java: "java.txt",
	xml: "xml.txt",
	css: "css.txt",
	yaml: "yaml.txt",
	markdown: "markdown.txt",
	diff: "diff.txt",
	sql: "sql.txt",
};

/** Every scope drawn with its name, so that a scope that differs shows. */
const MARKED: HighlightTheme = new Proxy(
	{},
	{
		get: (_target, key) =>
			typeof key === "string" && key !== "default" ? (text: string) => `<${key}>${text}</${key}>` : undefined,
	},
);

function full(code: string, language: string): string[] {
	return highlight(code, { language, ignoreIllegals: true, theme: MARKED }).split("\n");
}

function corpus(language: string): string {
	return readFileSync(join(CORPUS, CORPUS_FILES[language]!), "utf8");
}

/** Feeds the lengths of `code` in order to one highlighter; each result must be the full highlighting. */
function expectEveryLength(
	code: string,
	language: string,
	lengths: Iterable<number>,
	limits: GrowingHighlightLimits | undefined = FOLLOW_ALL,
): GrowingHighlighter {
	const highlighter = new GrowingHighlighter(language, MARKED, limits);
	for (const length of lengths) {
		const text = code.slice(0, length);
		const lines = highlighter.lines(text);
		const expected = full(text, language);
		if (lines.join("\n") !== expected.join("\n")) {
			const line = lines.findIndex((value, index) => value !== expected[index]);
			expect({ length, line, got: lines[line] }).toEqual({ length, line, got: expected[line] });
		}
		expect(lines.length).toBe(expected.length);
	}
	return highlighter;
}

function* range(from: number, to: number): Iterable<number> {
	for (let length = from; length <= to; length++) yield length;
}

/** Every length up to `every`, then every `step`-th, then `to`. */
function* everyThenSteps(every: number, to: number, step: number): Iterable<number> {
	let length = 0;
	for (; length <= Math.min(every, to); length++) yield length;
	for (; length < to; length += step) yield length;
	if (every < to) yield to;
}

/** A small deterministic generator (mulberry32). */
function random(seed: number): () => number {
	let state = seed;
	return () => {
		state = (state + 0x6d2b79f5) | 0;
		let t = Math.imul(state ^ (state >>> 15), 1 | state);
		t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
		return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
	};
}

function* chunks(length: number, seed: number, largest: number): Iterable<number> {
	const next = random(seed);
	let at = 0;
	while (at < length) {
		at = Math.min(length, at + 1 + Math.floor(next() * largest));
		yield at;
	}
}

beforeAll(async () => {
	await loadAllHighlightLanguages();
	initTheme("dark");
});

describe("GrowingHighlighter: every length of a corpus per language is highlighted as a whole", () => {
	for (const language of Object.keys(CORPUS_FILES)) {
		it(`${language}: every prefix, and random chunks`, () => {
			const code = corpus(language);
			const highlighter = expectEveryLength(code, language, range(0, code.length));
			// It did not highlight from the start each time: lines became final along the way. (Not in a JSON document:
			// it is one object, and the grammar is in the object's mode on every line.)
			// (Nor in Markdown: it is not among INCREMENTAL_HIGHLIGHT_LANGUAGES, and is highlighted in full.)
			if (language === "json" || !INCREMENTAL_HIGHLIGHT_LANGUAGES.includes(language)) {
				expect(highlighter.finalLength).toBe(0);
			} else {
				expect(highlighter.finalLength).toBeGreaterThan(0);
			}
			expect(highlighter.highlightsInFull).toBe(false);
			for (const seed of [1, 2, 3]) expectEveryLength(code, language, chunks(code.length, seed, 120));
		});
	}
});

/** Code where what comes later changes how earlier lines are highlighted, or that is otherwise awkward. */
const AWKWARD: Array<[string, string]> = [
	// A JSX tag is a tag only if its closing tag comes somewhere later (a callback that searches the rest of the text).
	["typescript", "const a = <Foo>\nlet b = 1;\nlet c = 2;\nlet d = 3;\nlet e = 4;\n</Foo>;\nlet f = 5;\nlet g = 6;\n"],
	["javascript", "x = <Item>\nfoo();\nbar();\nbaz();\n</Item>\nqux();\nquux();\n"],
	// A method definition's parameters over many lines: one expression that reaches as far as the parentheses go.
	[
		"typescript",
		"class A {\n  run(\n    a: number,\n    b: string,\n    c: boolean,\n  ) {\n    return a;\n  }\n}\nlet x = 1;\n",
	],
	["javascript", "call(\n  first,\n  second,\n  third\n)\nnext();\nother();\n"],
	["typescript", "const f = (\n  a,\n  b,\n  c\n) => a + b;\nconst g = 1;\nconst h = 2;\n"],
	// Comments and strings left open, then closed many lines later.
	["typescript", "/* open\nline one\nline two\nline three\n*/ code();\nmore();\nmore();\n"],
	["typescript", "x = `template\nline $" + "{a +\n b}\nline\nline`;\ny();\nz();\n"],
	["typescript", "const o = {\n  // a comment\n  key: 1,\n  /* block */ other: 2,\n  last\n    : 3,\n};\nnext();\n"],
	["python", 'x = """\nline\nline\nline\n"""\ny = 1\nz = 2\n'],
	["python", "def f(\n    a,\n    b,\n):\n    return a\n\n\nclass B:\n    pass\n"],
	["bash", "cat <<EOF\nline $x\nline\nEOF\necho done\nfoo() {\n  bar\n}\n"],
	["bash", "name\n(\n)\n{\n  echo hi\n}\nnext\nother\n"],
	["cpp", 'auto s = R"x(\nline\n)" still\n)x";\nint y = 1;\nint z = 2;\n'],
	["cpp", "static\nconst\nunsigned\nint\nfunc\n(int a) {\n  return a;\n}\nint b;\n"],
	["c", "int\nmain\n(void)\n{\n  return 0;\n}\nint x;\nint y;\n"],
	["java", "public\nstatic\nvoid\nmain\n(String[] args) {\n}\nint x;\nint y;\n"],
	["rust", 'let s = r##"\nline "# not yet\nline\n"##;\nlet y = 1;\nlet z = 2;\nlet user = "user";\n'],
	["yaml", "key: |\n  line one\n  line two\nnext: 1\nother: >\n  folded\n  text\nlast: 2\n"],
	["markdown", "text\n\n```js\ncode();\n```\n\nmore *em\nphasis* here\n\n~~~\nx\n~~~\nend\n"],
	["xml", "<div>\n<script>\nlet a = 1;\nlet b = 2;\n</script>\n<style>\n.a { b: c }\n</style>\n<p>x</p>\n<p>y</p>\n"],
	["sql", "select 'a\nstring', \"b\nc\" from t\n-- comment\n/* block\ncomment */\nwhere x = 1;\nselect 2;\n"],
	["css", "a {\n  color: red; /* note\n  more */\n}\n@media x {\n  b { c: d }\n}\ne { f: g }\n"],
	["go", "x := `raw\nstring\n`\ny := 1\nfunc f(\n\ta int,\n) {\n}\n"],
	["diff", "@@ -1,2 +1,2 @@\n-a\n+b\n c\n--- x\n+++ y\n@@ -3 +3 @@\n-d\n+e\n"],
	["json", '{\n  "a": [\n    1,\n    2\n  ],\n  "b": "c\\"d"\n}\n'],
	// A shebang is one only at the very start; a line that starts with one later is not.
	["javascript", "x;\n#!/usr/bin/env node\ny;\nz;\nw;\n"],
	["bash", "echo a\n#!/bin/sh\necho b\necho c\necho d\n"],
	// A keyword after a dot is not one; the dot ends the line before.
	["javascript", "a.\nclass\nB {}\nfoo.\nfunction\nbar() {}\nbaz();\n"],
	// Line ends, blank and white space only lines, tabs, CRLF, non-ASCII text.
	["python", "a = 1\r\nb = 2\r\n\r\n\r\nc = 'é中😀'\r\n\t\td = 3\r\n   \r\ne = 4\r\n"],
	["typescript", "\n\n\nlet a = 1;\n\n\n   \n\t\nlet b = 2;\n\n\n"],
	// A YAML document whose block scalar's hit-end expression took exponential time (it did not finish at 412 characters).
	[
		"yaml",
		"        run: npm ci --ignore-scripts\n      - name: Test\n        run: |\n  disabled: no\n  nothing: ~\n  date: 2026-11-10\n  multi: \"a\n      - name: Anchors\n        with: &defaults\n          retries: 3\n          timeout: 1.5\n          a folded\n          scalar\n      - name: Anchors\n        with: &defaults\n          a folded\n      matrix:\n        os: [ubuntu-latest, macos-latest]\n        node: [18, 20]\nanchors:\n  enabled: true\n  disabled: no\n  nothing: ~\nenv:\n  NODE_VERSION: 20\n  GREETING: 'hello: world'\n",
	],
	// An attribute selector whose `[` is open at a line end: the CSS family's `illegal` matches no text there, and
	// highlight.js then drops the line break (the drawn lines are fewer than the lines of the text).
	...(["css", "less", "scss", "stylus"] as const).flatMap(
		(language): Array<[string, string]> => [
			[language, ".btn[data-x\n] {\n  a: b;\n}\n.next {\n  c: d;\n}\n.last {\n  e: f;\n}\n"],
			[language, '[\n""]\n?\ndi\n'],
			[language, "a {\n  b: c;\n}\nd {\n  e: f;\n}\ng {\n  h: i;\n}\n.x[y\n] {\n  z: 1;\n}\nk {\n  l: m;\n}\n"],
		],
	),
];

describe("GrowingHighlighter: awkward code, at every length", () => {
	for (const [index, [language, code]] of AWKWARD.entries()) {
		it(`${language} #${index}`, () => {
			expectEveryLength(code, language, range(0, code.length));
			// Each line given whole, as a stream of lines.
			const lineEnds = [...code.matchAll(/\n/g)].map((match) => match.index + 1);
			expectEveryLength(code, language, lineEnds);
		});
	}
});

describe("GrowingHighlighter: text that does not only grow", () => {
	it("a text that is not the last one with text added is highlighted as itself", () => {
		const code = corpus("typescript");
		const highlighter = new GrowingHighlighter("typescript", MARKED, FOLLOW_ALL);
		const texts = [
			code.slice(0, 900),
			code.slice(0, 1200),
			// Shorter, then changed before the end, then changed early, then grown again.
			code.slice(0, 1000),
			`${code.slice(0, 1100)}// changed\n${code.slice(1100, 1300)}`,
			`x${code.slice(1, 1400)}`,
			`x${code.slice(1, 1700)}`,
			"",
			code,
		];
		for (const text of texts) expect(highlighter.lines(text)).toEqual(full(text, "typescript"));
	});

	it("a language no grammar knows throws as highlight() does", () => {
		expect(() => new GrowingHighlighter("no-such-language").lines("x")).toThrow();
	});
});

describe("highlightCode: a code block that grows is highlighted as a whole at every length", () => {
	it("typescript, through the theme's highlighter", () => {
		const code = corpus("typescript");
		const growing: string[][] = [];
		for (let length = 1; length <= code.length; length++)
			growing.push(highlightCode(code.slice(0, length), "typescript"));
		// Each length again, each after code that it does not extend: highlighted in full.
		for (let length = 1; length <= code.length; length++) {
			highlightCode("unrelated", "typescript");
			expect(highlightCode(code.slice(0, length), "typescript")).toEqual(growing[length - 1]);
		}
	});
});

describe("highlightCode: a CSS block whose attribute selector is open at a line end", () => {
	it("is drawn with the lines a full highlight draws, while it grows and once it is complete", () => {
		const code = "a {\n  b: c;\n}\nd {\n  e: f;\n}\n.btn[data-x\n] {\n  a: b;\n}\ng {\n  h: i;\n}\n";
		for (const language of ["css", "less", "scss", "stylus"]) {
			highlightCode("unrelated", language);
			for (let length = 1; length <= code.length; length++) {
				const text = code.slice(0, length);
				const lines = highlightCode(text, language);
				const whole = highlight(text, { language, ignoreIllegals: true }).split("\n");
				expect({ language, length, lines: lines.length }).toEqual({ language, length, lines: whole.length });
			}
			// The line after the selector is drawn once in the finished block.
			const done = highlightCode(code, language).map((line) => line.replace(/\x1b\[[0-9;]*m/g, ""));
			expect(done.filter((line) => line === "  a: b;")).toHaveLength(1);
		}
	});
});

/** A text long enough to be followed, in a language: its corpus, or the TypeScript one for a grammar without one. */
function longText(language: string): string {
	return CORPUS_FILES[language] === undefined ? corpus("typescript") : corpus(language);
}

describe("GrowingHighlighter: only the languages proven on their own corpus are followed", () => {
	it("each language followed has a corpus here", () => {
		for (const language of INCREMENTAL_HIGHLIGHT_LANGUAGES) expect(CORPUS_FILES[language]).toBeDefined();
	});

	it("each language followed, by its name and its aliases, sets checkpoints", () => {
		for (const language of INCREMENTAL_HIGHLIGHT_LANGUAGES) {
			if (language === "json") continue;
			for (const name of [language, ...(hljs.getLanguage(language)?.aliases ?? [])]) {
				const highlighter = expectEveryLength(longText(language), name, chunks(longText(language).length, 5, 80));
				expect({ name, followed: highlighter.finalLength > 0 }).toEqual({ name, followed: true });
			}
		}
	});

	it("every other language is highlighted in full", () => {
		for (const language of ["markdown", "less", "scss", "ruby", "php", "kotlin", "plaintext", "dockerfile"]) {
			const highlighter = expectEveryLength(longText(language), language, chunks(longText(language).length, 6, 80));
			expect({ language, finalLength: highlighter.finalLength }).toEqual({ language, finalLength: 0 });
		}
	});
});

describe("GrowingHighlighter: a text shorter than the threshold is highlighted in full", () => {
	it("each corpus with the real threshold (every length to past it, then steps): below it in full, then followed", () => {
		let crossedMidLine = false;
		for (const language of Object.keys(CORPUS_FILES)) {
			const code = corpus(language);
			const highlighter = new GrowingHighlighter(language, MARKED);
			for (const length of everyThenSteps(GROWING_HIGHLIGHT_MIN_LENGTH + 400, code.length, 37)) {
				const text = code.slice(0, length);
				expect(highlighter.lines(text)).toEqual(full(text, language));
				if (length < GROWING_HIGHLIGHT_MIN_LENGTH) expect(highlighter.finalLength).toBe(0);
			}
			if (code[GROWING_HIGHLIGHT_MIN_LENGTH - 1] !== "\n") crossedMidLine = true;
			if (language !== "json" && INCREMENTAL_HIGHLIGHT_LANGUAGES.includes(language)) {
				expect(highlighter.finalLength).toBeGreaterThan(0);
			}
		}
		// (The switch is at a number of characters: in most texts it falls inside a line.)
		expect(crossedMidLine).toBe(true);
	});

	it("a text that grows across the threshold, shrinks below it and grows again", () => {
		const code = corpus("typescript");
		const at = GROWING_HIGHLIGHT_MIN_LENGTH;
		const lengths = [
			at - 1,
			at,
			at + 1,
			at + 300,
			at + 301,
			at - 40,
			at - 39,
			at + 7,
			at + 900,
			3,
			at + 1,
			code.length,
			at - 1,
			code.length,
		];
		const highlighter = new GrowingHighlighter("typescript", MARKED);
		for (const length of lengths) {
			const text = code.slice(0, length);
			expect({ length, lines: highlighter.lines(text) }).toEqual({ length, lines: full(text, "typescript") });
		}
	});
});

describe("GrowingHighlighter: the work bound", () => {
	it("real code stays far within it", () => {
		for (const language of INCREMENTAL_HIGHLIGHT_LANGUAGES) {
			for (const largest of [1, 7, 64]) {
				const code = corpus(language);
				const highlighter = new GrowingHighlighter(language, MARKED, FOLLOW_ALL);
				for (const length of chunks(code.length, largest, largest)) highlighter.lines(code.slice(0, length));
				expect({ language, largest, fellBack: highlighter.highlightsInFull }).toEqual({
					language,
					largest,
					fellBack: false,
				});
			}
		}
	});

	it("past it, the text is highlighted in full from then on, and stays exact", () => {
		// (No multiple: only the allowance, which following a text this long uses up on the way.)
		const code = corpus("typescript").repeat(2);
		const highlighter = new GrowingHighlighter("typescript", MARKED, { minLength: 0, workMultiple: 0 });
		let fellBackAt: number | undefined;
		for (const length of chunks(code.length, 9, 60)) {
			const text = code.slice(0, length);
			expect({ length, lines: highlighter.lines(text) }).toEqual({ length, lines: full(text, "typescript") });
			if (highlighter.highlightsInFull) fellBackAt ??= length;
			else expect(fellBackAt).toBeUndefined();
		}
		expect(fellBackAt).toBeGreaterThan(0);
		expect(highlighter.finalLength).toBe(0);
		// (For the rest of its life: a shorter text, then a longer one, are still highlighted in full.)
		for (const text of [code.slice(0, 500), code]) {
			expect(highlighter.lines(text)).toEqual(full(text, "typescript"));
			expect(highlighter.finalLength).toBe(0);
		}
	});
});

describe("regex-blowup: no expression derived for a language followed can take more than linear time per attempt", () => {
	it("finds the shapes it looks for", () => {
		const blowups: string[] = [
			"(a|a)*b",
			"(a+)+b",
			String.raw`(\w+,?)*;`,
			String.raw`\s*\s*x`,
			"a*[ab]*c",
			String.raw`(a)(?:\1b)*c`,
			// YAML's block scalar, whose hit-end expression froze a terminal (exponential).
			String.raw`[\|>]([1-9]?[+-])?[ ]*\n( +)[^ ][^\n]*\n(?:\2[^\n]+\n?)*(?![\s\S])`,
			// Markdown's code fence: a lazy repetition, then an overlapping one (why Markdown is not followed).
			"(`{3,})(?:(?:(?!\\1`*[ ]*)(.|\\n))*?(?:`*(?![\\s\\S])))",
		];
		for (const source of blowups)
			expect({ source, found: findBlowups(source, "m").length > 0 }).toEqual({ source, found: true });
		const linear: string[] = [
			"a*b*c",
			String.raw`"(?:[^"\\]|\\.)*"`,
			String.raw`(?:[ ][^\n]*\n)*[^\n]*(?![\s\S])`,
			// (`$` holds only before a line break: `.*` cannot stop before the end of its line.)
			String.raw`(?:\/\/.*$\s*)*x`,
			// (A back-reference in a lookahead, to a group captured before the repetition, divides no text.)
			String.raw`r(#*)"(?:(?!"\1)(?:.|\n))*?(?![\s\S])`,
		];
		for (const source of linear) expect({ source, found: findBlowups(source, "m") }).toEqual({ source, found: [] });
	});

	it("passes every hit-end expression of every language followed", () => {
		for (const language of INCREMENTAL_HIGHLIGHT_LANGUAGES) {
			const expressions = growingHighlightExpressions(language);
			expect(expressions).not.toBeNull();
			for (const expression of expressions ?? []) {
				expect({
					language,
					from: expression.sources,
					found: findBlowups(expression.hitEnd, expression.flags),
				}).toEqual({
					language,
					from: expression.sources,
					found: [],
				});
			}
		}
	});

	it("languages not followed have none", () => {
		for (const language of ["markdown", "ruby", "less"]) expect(growingHighlightExpressions(language)).toBeNull();
	});
});

describe("regex-reach: the expressions that find where an attempt reads the end", () => {
	const EXPRESSIONS = [
		String.raw`[a-zA-Z_]\w*\([^()]*(\([^()]*(\([^()]*\)[^()]*)*\)[^()]*)*\)\s*\{`,
		String.raw`[{,\n]\s*(?=(((\/\/.*$)|(\/\*(\*[^/]|[^*])*\*\/))\s*)*[A-Za-z$_][0-9A-Za-z$_]*\s*:)`,
		String.raw`r(#*)"(.|\n)*?"\1(?!#)`,
		"(`{3,})[^`](.|\\n)*?\\1`*[ ]*",
		String.raw`((decltype\(auto\)|([a-zA-Z_]\w*::)?[a-zA-Z_]\w*(<[^<>]+>)?)[\*&\s]+)+([a-zA-Z_]\w*::)?[a-zA-Z]\w*\s*\(`,
		String.raw`\)([^()\\ ]{0,16})"`,
		String.raw`[\|>]([1-9]?[+-])?[ ]*\n( +)[^ ][^\n]*\n(\2[^\n]+\n?)*`,
	];
	const ALPHABET = [
		"a",
		"r",
		"#",
		'"',
		"(",
		")",
		"{",
		" ",
		"\n",
		",",
		":",
		"/",
		"*",
		">",
		"<",
		"`",
		"|",
		"-",
		"1",
		"&",
	];

	it("where none finds an attempt reading the end, the expression matches the same for any text added", () => {
		const next = random(7);
		const text = (length: number) => {
			let value = "";
			for (let i = 0; i < length; i++) value += ALPHABET[Math.floor(next() * ALPHABET.length)];
			return value;
		};
		for (const source of EXPRESSIONS) {
			const analysis = analyse(source);
			expect(analysis?.hitEnd).toBeDefined();
			const expression = new RegExp(source, "my");
			const hitEnd = new RegExp(analysis!.hitEnd!, "my");
			for (let round = 0; round < 1500; round++) {
				const before = text(1 + Math.floor(next() * 12));
				for (let at = 0; at <= before.length; at++) {
					hitEnd.lastIndex = at;
					if (hitEnd.test(before)) continue;
					expression.lastIndex = at;
					const alone = expression.exec(before)?.[0] ?? null;
					for (let extension = 0; extension < 4; extension++) {
						expression.lastIndex = at;
						const grown = expression.exec(before + text(1 + Math.floor(next() * 6)))?.[0] ?? null;
						expect(grown).toBe(alone);
					}
				}
			}
		}
	});

	it("no hit-end expression for a repetition whose text can be cut into repetitions in many ways", () => {
		// YAML's block scalar: `(\2[^\n]+\n?)*` followed by a part that fails made the engine try every way of cutting the
		// lines into repetitions (a 400-character document did not finish). At the end of the expression, its lines are
		// described one way instead (lines that start with its first character, then any line); elsewhere it has none.
		const blockScalar = analyse(String.raw`[\|>]([1-9]?[+-])?[ ]*\n( +)[^ ][^\n]*\n(\2[^\n]+\n?)*`)?.hitEnd;
		expect(blockScalar).toContain(String.raw`(?: [^\n]*\n)*[^\n]*(?![\s\S])`);
		expect(findBlowups(blockScalar!, "m")).toEqual([]);
		expect(analyse(String.raw`( +)[^ ][^\n]*\n(\1[^\n]+\n?)*:`)?.hitEnd).toBeUndefined();
		expect(analyse(String.raw`(?:( +)(\1[^\n]+\n?)*)x`)?.hitEnd).toBeUndefined();
		// Repetitions that cannot be cut up two ways keep theirs.
		expect(
			analyse(String.raw`[a-zA-Z_]\w*\([^()]*(\([^()]*(\([^()]*\)[^()]*)*\)[^()]*)*\)\s*\{`)?.hitEnd,
		).toBeDefined();
		expect(analyse(String.raw`^[ ]*[a-zA-Z]+([\s_-]+[a-zA-Z]+)*[ ]*=`)?.hitEnd).toBeDefined();
	});

	it("a raw string reads to the end only while it is not closed", () => {
		const analysis = analyse(String.raw`r(#*)"(.|\n)*?"\1(?!#)`);
		const hitEnd = new RegExp(analysis!.hitEnd!, "gm");
		// (`r"` at the end of "user" starts one: closed by the next quote.)
		const closed = 'let user = "user";\nlet x = "y";';
		expect(hitEnd.exec(closed)?.index).toBe(closed.length);
		hitEnd.lastIndex = 0;
		expect(hitEnd.exec('let user = "user";\nlet x = 1;')?.index).toBe(15);
		hitEnd.lastIndex = 0;
		// (A quote without the hash does not close it.)
		expect(hitEnd.exec('let s = r#"open\nstill open" no\n')?.index).toBe(8);
		hitEnd.lastIndex = 0;
		const closedLater = 'let s = r#"open\nnow closed"# no\n';
		expect(hitEnd.exec(closedLater)?.index).toBe(closedLater.length);
	});

	it("reach: bounded expressions count the line breaks they can cross", () => {
		expect(analyse(String.raw`\w+`)?.lines).toBe(0);
		expect(analyse(String.raw`function\s+\w+`)?.lines).toBe(1);
		expect(analyse(String.raw`\w[\w\d_]*\s*\(\s*\)\s*\{`)?.lines).toBe(3);
		expect(analyse('[^"]*"')?.lines).toBe(Number.POSITIVE_INFINITY);
		expect(analyse("(?<=a)b")).toBeUndefined();
	});
});
