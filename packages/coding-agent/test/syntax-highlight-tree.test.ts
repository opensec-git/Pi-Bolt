import hljs from "highlight.js/lib/core.js";
import { beforeAll, describe, expect, it } from "vitest";
import {
	type HighlightTheme,
	highlight,
	loadAllHighlightLanguages,
	renderHighlightedHtml,
} from "../src/utils/syntax-highlight.ts";

// highlight() renders highlight.js's token tree instead of reading back the HTML made of it, and has highlight.js make that
// HTML with a faster renderer. Both must give exactly what highlight.js's own HTML, read back, gives.

interface Api {
	listLanguages(): string[];
	highlight(code: string, options: { language: string; ignoreIllegals?: boolean }): { value: string; emitter: object };
	highlightAuto(code: string, subset?: string[]): { value: string; emitter: object };
}
const api = hljs as unknown as Api;

/** highlight.js's own HTML for the result: TokenTreeEmitter.prototype.toHTML, which the installed emitter overrides. */
function ownHtml(emitter: object): string {
	const base = Object.getPrototypeOf(Object.getPrototypeOf(emitter)) as { toHTML(this: object): string };
	return base.toHTML.call(emitter);
}

const SCOPES = [
	"keyword",
	"built_in",
	"literal",
	"number",
	"string",
	"subst",
	"comment",
	"doctag",
	"meta",
	"meta-keyword",
	"meta-string",
	"function",
	"title",
	"class",
	"type",
	"tag",
	"name",
	"attr",
	"attribute",
	"variable",
	"params",
	"operator",
	"punctuation",
	"selector",
	"selector-tag",
	"template-variable",
	"regexp",
	"symbol",
	"emphasis",
	"strong",
	"link",
	"addition",
	"deletion",
];

function themeWith(defaultFormatter: boolean): HighlightTheme {
	const theme: HighlightTheme = {};
	// Every other scope, so that nested scopes fall back to their parents' and prefixes ("meta-keyword" -> "meta") apply.
	SCOPES.forEach((scope, index) => {
		if (index % 2 === 0) theme[scope] = (text) => `<${scope}|${text}>`;
	});
	if (defaultFormatter) theme.default = (text) => `{${text}}`;
	return theme;
}

const SNIPPETS: Record<string, string> = {
	typescript: `export function parse(input: string): Node[] {\n\tconst out: Node[] = []; // a "comment" & <tag>\n\tfor (let i = 0; i < input.length; i++) {\n\t\tif (input.charCodeAt(i) === 0x5b) out.push({ kind: 'open', at: i, t: \`a\${i}b\` });\n\t}\n\treturn out;\n}\n`,
	xml: `<?xml version="1.0"?>\n<!DOCTYPE html>\n<html lang='en'>\n<head><style>body { color: red; } /* a & b */</style>\n<script>const x = "<b>" && 1 < 2;</script></head>\n<body class="a b">&amp; &lt; text &#x41;</body></html>\n`,
	python: `def g(x: int) -> str:\n    """doc 'quoted' & <b>"""\n    return f"{x!r} {{x}}" if x > 0 else '&lt;'\n@decorator\nclass A(B):\n    pass\n`,
	bash: `#!/bin/bash\nfor f in *.txt; do echo "$f & \${HOME}" > /dev/null 2>&1; done\ncat <<EOF\n<here> & 'doc'\nEOF\n`,
	markdown: "# Title\n\n*em* **strong** `code` [link](http://x?a=1&b=2)\n\n```js\nconst a = '<x>';\n```\n> quote\n",
	json: `{"a": [1, 2.5e3, true, null], "b": "<&>\\"'", "c": {"d": "\\u0041"}}`,
	css: `@media (max-width: 600px) { .a > b::after { content: "<&>"; color: #fff !important; } }`,
	php: `<?php\n$a = "x & y"; echo htmlspecialchars('<b>'); ?>\n<div><?= $a ?></div>\n`,
	sql: `SELECT a, 'x''y' AS "q" FROM t WHERE b < 3 AND c <> '&'; -- comment`,
	diff: `--- a/x\n+++ b/x\n@@ -1,2 +1,2 @@\n-old <line>\n+new & line\n context\n`,
};

/** A random string over a text full of characters that are special to highlight.js grammars and to HTML. */
function randomCode(seed: number, length: number): string {
	const pieces = [
		"<",
		">",
		"&",
		'"',
		"'",
		"`",
		"/*",
		"*/",
		"//",
		"#",
		"\n",
		"\t",
		" ",
		"${",
		"}",
		"{",
		"(",
		")",
		"[",
		"]",
		"function ",
		"class ",
		"return ",
		"if ",
		"0x1f",
		"3.14",
		"&amp;",
		"&lt;",
		"&#x27;",
		"<span>",
		"</span>",
		"<span class='hljs-keyword'>",
		"naïve 日本語 😀",
		"\\",
		"$x",
		"@deco",
		"--",
		"<!--",
		"-->",
		"<?",
		"?>",
		"<script>",
		"</script>",
		"\r\n",
		"abc",
		"_x_",
	];
	let state = seed * 2654435761 + 1;
	const next = () => {
		state = (state * 1103515245 + 12345) % 2147483648;
		return state;
	};
	let out = "";
	while (out.length < length) out += pieces[next() % pieces.length];
	return out;
}

describe("syntax highlight from the token tree", () => {
	beforeAll(async () => {
		await loadAllHighlightLanguages();
		// The emitter is installed by the first highlight().
		highlight("x", { language: "typescript" });
	});

	it("installs the faster emitter", () => {
		expect(api.highlight("x", { language: "typescript" }).emitter.constructor.name).toBe("FastHtmlEmitter");
	});

	const check = (code: string, language: string | undefined) => {
		const result = language ? api.highlight(code, { language, ignoreIllegals: true }) : api.highlightAuto(code);
		const html = ownHtml(result.emitter);
		// Unless the result is not the tree's (illegal matches with ignoreIllegals off, errors).
		expect(result.value).toBe(html);
		for (const theme of [themeWith(false), themeWith(true), {}]) {
			expect(highlight(code, { language, ignoreIllegals: true, theme })).toBe(renderHighlightedHtml(html, theme));
		}
	};

	it("gives what the HTML gives, for every language and snippet", () => {
		const languages = api.listLanguages();
		expect(languages.length).toBeGreaterThan(150);
		const snippets = Object.values(SNIPPETS);
		for (const language of languages) {
			for (const code of snippets) check(code, language);
		}
	});

	it("gives what the HTML gives, for random code in the common languages", () => {
		const languages = ["typescript", "javascript", "xml", "python", "bash", "php", "markdown", "rust", "cpp", "json"];
		for (let seed = 1; seed <= 60; seed++) {
			const code = randomCode(seed, 40 + (seed % 7) * 60);
			for (const language of languages) check(code, language);
		}
	});

	it("gives what the HTML gives with automatic language detection", () => {
		for (const code of Object.values(SNIPPETS)) check(code, undefined);
		for (let seed = 1; seed <= 5; seed++) check(randomCode(seed, 200), undefined);
	});

	it("gives highlight.js's escaped code for an illegal match it does not ignore", () => {
		const code = "SELECT 1; <<<< ]]]] ???";
		const result = api.highlight(code, { language: "json", ignoreIllegals: false });
		expect(highlight(code, { language: "json", ignoreIllegals: false })).toBe(renderHighlightedHtml(result.value));
	});
});
