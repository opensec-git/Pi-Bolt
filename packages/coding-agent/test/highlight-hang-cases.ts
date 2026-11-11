/**
 * Adversarial texts for the incremental highlighter's hang fuzz (highlight-hang-fuzz.ts) and work check
 * (growing-highlighter-work.test.ts): for each language, texts made of its grammar's own delimiters, which is where an
 * expression that backtracks would blow up: (at the fuzz's sizes, thousands of) repeats of an opening delimiter, of a
 * separator, of white space and line breaks, of a nearly closing sequence; long single lines; deep nesting; token soup.
 */

/** The delimiters and words of each language's grammar that the texts are made of. */
const DELIMITERS: Record<string, { open: string[]; close: string[]; separators: string[]; words: string[] }> = {
	typescript: {
		open: ["(", "{", "[", "<A>", "`", "${", '"', "'", "/*", "//", "/", "<A", "a(", "function f(", "class A {", "=> "],
		close: [")", "}", "]", "</A>", "`", "*/", '"', "'", "/>", "*"],
		separators: [",", ";", ":", "?", "|", "&", ".", "=", "\\", "@"],
		words: ["const", "let", "interface", "type", "async", "return", "x", "a1", "$_", "0x1f", "1e9"],
	},
	javascript: {
		open: ["(", "{", "[", "<A>", "`", "${", '"', "'", "/*", "//", "/", "<A", "a(", "function f(", "class A {", "#!"],
		close: [")", "}", "]", "</A>", "`", "*/", '"', "'", "/>", "*"],
		separators: [",", ";", ":", "?", "|", "&", ".", "=", "\\"],
		words: ["const", "let", "var", "async", "return", "x", "$", "0b1", "1n"],
	},
	python: {
		open: ['"""', "'''", '"', "'", 'f"', 'r"', "{", "(", "[", "#", "def f(", "class A(", "@"],
		close: ['"""', "'''", '"', "'", "}", ")", "]", ":"],
		separators: [",", ":", ".", "=", "\\", "->"],
		words: ["def", "lambda", "self", "None", "x", "0o7", "1j", "async"],
	},
	bash: {
		open: ['"', "'", "$(", "${", "`", "<<EOF", "<<-'X'", "(", "{", "[[", "#", "f()"],
		close: ['"', "'", ")", "}", "`", "EOF", "X", "]]", "fi", "done"],
		separators: [";", "|", "&&", "\\", "=", "$"],
		words: ["if", "then", "for", "echo", "x", "$1", "local"],
	},
	json: {
		open: ["{", "[", '"', '"\\'],
		close: ["}", "]", '"'],
		separators: [",", ":", "\\"],
		words: ["true", "null", "1.5e3", "-0", "x"],
	},
	rust: {
		open: ['r#"', 'r"', '"', "'", "/*", "//", "(", "{", "[", "<", "fn f(", "#[", "b'", 'r##"'],
		close: ['"#', '"', "'", "*/", ")", "}", "]", ">", '"##'],
		separators: [",", ";", "::", ":", "->", "&", "|", "#"],
		words: ["fn", "let", "mut", "impl", "x", "'a", "0u8", "Self"],
	},
	go: {
		open: ["`", '"', "'", "/*", "//", "(", "{", "[", "func f(", "map["],
		close: ["`", '"', "'", "*/", ")", "}", "]"],
		separators: [",", ";", ":=", ".", "*", "&"],
		words: ["func", "var", "go", "chan", "x", "0x1", "nil"],
	},
	c: {
		open: [
			'"',
			"'",
			"/*",
			"//",
			"(",
			"{",
			"[",
			"#include <",
			"#define X(",
			'R"x(',
			"int f(",
			"static const unsigned ",
		],
		close: ['"', "'", "*/", ")", "}", "]", ">", ')x"'],
		separators: [",", ";", "*", "&", "::", "\\", " "],
		words: ["int", "char", "struct", "x", "main", "0x1fUL", "decltype(auto)"],
	},
	cpp: {
		open: ['"', "'", "/*", "//", "(", "{", "[", "<", "#include <", 'R"x(', 'u8R"(', "template <", "auto f(", "A<B<"],
		close: ['"', "'", "*/", ")", "}", "]", ">", ')x"', ')"'],
		separators: [",", ";", "*", "&", "::", "\\", " "],
		words: ["int", "class", "namespace", "x", "std", "0x1fULL", "decltype(auto)"],
	},
	java: {
		open: ['"', "'", '"""', "/*", "/**", "//", "(", "{", "[", "<", "@", "public static void f(", "class A<"],
		close: ['"', "'", '"""', "*/", ")", "}", "]", ">"],
		separators: [",", ";", ".", "<", ">", "&"],
		words: ["public", "class", "void", "x", "String", "List<A>", "0L"],
	},
	xml: {
		// (Not `<script>` repeated: highlight.js's own full highlight is exponential in the number of unclosed `<script>` tags,
		// the content of each being auto-detected among languages that include XML. One `<script>` is in EXTRA.)
		open: ["<", "<a ", "<!--", "<![CDATA[", "<?xml", "<style>", '="', "='", "<!DOCTYPE "],
		close: [">", "/>", "-->", "]]>", "?>", "</style>", '"', "'", "</a>"],
		separators: [" ", "=", "&amp;", "&"],
		words: ["div", "x", "a:b", "data-x"],
	},
	css: {
		open: ["{", "(", "[", '"', "'", "/*", "@media ", "url(", ":", "::", "@import "],
		close: ["}", ")", "]", '"', "'", "*/", ";"],
		separators: [",", ";", ">", "+", "~", " ", "!important"],
		words: ["a", ".b", "#c", "color", "1px", "#fff", "--x"],
	},
	markdown: {
		open: ["```", "~~~", "`", "**", "*", "_", "[", "](", "<", "#", "> ", "- ", "1. ", "    "],
		close: ["```", "~~~", "`", "**", "*", "_", "]", ")", ">"],
		separators: [" ", "|", "\\", "---", "==="],
		words: ["text", "x", "http://a.b", "js"],
	},
	diff: {
		open: ["@@ -1,2 +1,2 @@", "+", "-", " ", "--- ", "+++ ", "diff --git ", "Index: ", "***", "<", ">"],
		close: ["@@", "\n"],
		separators: [" ", ",", "!"],
		words: ["a", "x", "1,2"],
	},
	sql: {
		open: ["'", '"', "`", "(", "--", "/*", "[", "$$", "CREATE FUNCTION f("],
		close: ["'", '"', "`", ")", "*/", "]", "$$"],
		separators: [",", ";", ".", "=", "::", "||"],
		words: ["select", "from", "where", "x", "1.5", "null", "e'"],
	},
	yaml: {
		open: ["key: |", "key: >-", "- ", "'", '"', "{", "[", "&a ", "*a", "!!str ", "# ", "---", "? "],
		close: ["'", '"', "}", "]", "..."],
		separators: [": ", ", ", "\n  ", "\n    ", "|", ">"],
		words: ["a", "x", "true", "1.5", "~", "2026-11-10"],
	},
};

/** Texts besides the generated ones: a delimiter that may not be repeated, followed by a long text. */
const EXTRA: Record<string, Array<[string, string]>> = {
	xml: [
		["<script>", "if (a < b) { x = `$" + "{y}`; } // </scr\n"],
		["<script>", "<"],
		["<style>", "a { b: c } /* "],
	],
	rust: [
		// A raw string with a long delimiter, nearly closed many times.
		[`r${"#".repeat(200)}"`, `"${"#".repeat(199)}x`],
	],
	cpp: [['R"abcdefghijklmnop(', ')abcdefghijklmno"']],
	c: [['R"abcdefghijklmnop(', ')abcdefghijklmno"']],
	yaml: [
		// Block scalars: many lines that each could be cut into repetitions (what froze a terminal), and one left open.
		["key: |\n  a\n", "  b c d e f g\n"],
		["key: >-\n   x\n", "   y   z \n"],
		["key: |\n  a\n", "  b\n x: 1\n"],
		["- run: |\n    a\n", "    b: c: d\n"],
		// (Two spaces inside the lines: where a repetition of the block scalar's body could also start; each scalar ends.)
		["key: |\n  a\n", `${"  a  b  c  d\n".repeat(12)}z: 1\nk: |\n  a\n`],
	],
	bash: [
		["cat <<EOF\n", "EO\n"],
		["cat <<'END'\n", "$x END\n"],
	],
};

export interface HangCase {
	name: string;
	text: string;
	/** Written by hand (EXTRA), not generated from the delimiters. */
	extra?: boolean;
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

/** `unit` repeated to about `size` characters. */
function repeat(unit: string, size: number): string {
	return unit.repeat(Math.max(1, Math.ceil(size / Math.max(1, unit.length))));
}

/** The adversarial texts for a language, each about `size` characters long (soup: `soups` of them). */
export function hangCases(language: string, size: number, soups = 2): HangCase[] {
	const d = DELIMITERS[language];
	if (d === undefined) throw new Error(`no delimiters for ${language}`);
	const cases: HangCase[] = [];
	const add = (name: string, text: string) => cases.push({ name, text });
	for (const open of d.open) {
		add(`open ${JSON.stringify(open)} repeated`, repeat(open, size));
		add(`open ${JSON.stringify(open)} on each line`, repeat(`${open}\n`, size));
		add(`open ${JSON.stringify(open)} then a long line`, `${open}${repeat("a ", size)}`);
		add(`open ${JSON.stringify(open)} then spaces and lines`, `${open}${repeat(" \n\t", size)}`);
		for (const close of d.close.slice(0, 4)) {
			// Nearly closed: the closing sequence without its last character, many times, inside an open one.
			if (close.length > 1)
				add(
					`${JSON.stringify(open)} then nearly ${JSON.stringify(close)}`,
					open + repeat(close.slice(0, -1), size),
				);
		}
	}
	for (const close of d.close) add(`close ${JSON.stringify(close)} repeated`, repeat(close, size));
	for (const separator of d.separators) {
		add(`separator ${JSON.stringify(separator)} repeated`, repeat(separator, size));
		add(`separator ${JSON.stringify(separator)} with words`, repeat(`${d.words[0]}${separator}`, size));
		add(`separator ${JSON.stringify(separator)} with space`, repeat(`${separator} `, size));
	}
	add("spaces", repeat(" ", size));
	add("line breaks", repeat("\n", size));
	add("spaces and line breaks", repeat("  \n", size));
	add("tabs and CRLF", repeat("\t\r\n", size));
	add("one long word", repeat("a", size));
	add("one long line of words", repeat(`${d.words.join(" ")} `, size));
	// Deep nesting: each opening delimiter many times, then each closing one.
	const depth = Math.floor(size / 4);
	for (let i = 0; i < Math.min(d.open.length, d.close.length); i++) {
		add(
			`nested ${JSON.stringify(d.open[i])} ${JSON.stringify(d.close[i])}`,
			repeat(d.open[i]!, depth * 2) + repeat(d.close[i]!, depth * 2),
		);
	}
	for (const [start, unit] of EXTRA[language] ?? []) {
		const name = `${JSON.stringify(start)} then ${JSON.stringify(unit)} repeated`;
		cases.push({ name, text: start + repeat(unit, size), extra: true });
	}
	const tokens = [...d.open, ...d.close, ...d.separators, ...d.words, " ", "  ", "\n", "\n  ", "\t", "\\"];
	for (let seed = 1; seed <= soups; seed++) {
		const next = random(seed);
		let text = "";
		while (text.length < size) text += tokens[Math.floor(next() * tokens.length)];
		add(`soup ${seed}`, text);
	}
	return cases;
}
