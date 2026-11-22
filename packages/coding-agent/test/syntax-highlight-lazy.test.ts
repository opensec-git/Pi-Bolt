import hljs from "highlight.js/lib/core.js";
import { expect, it } from "vitest";
import {
	EAGER_LANGUAGE_ALIASES,
	getHighlightLanguageCount,
	highlight,
	supportsLanguage,
} from "../src/utils/syntax-highlight.ts";

const EAGER = [
	"python",
	"java",
	"go",
	"javascript",
	"json",
	"cpp",
	"typescript",
	"php",
	"ruby",
	"c",
	"csharp",
	"nix",
	"bash",
	"rust",
	"scala",
	"kotlin",
	"swift",
	"dart",
	"groovy",
	"perl",
	"lua",
];

it("registers a common grammar when it is first asked for, not when the module loads", async () => {
	expect(hljs.getLanguage("python")).toBeUndefined();
	const count = getHighlightLanguageCount();
	expect(count).toBe(EAGER.length);
	expect(supportsLanguage("py")).toBe(true);
	expect(hljs.getLanguage("python")).toBeDefined();
	// The other common grammars wait for their own first use; the count already includes them.
	expect(hljs.getLanguage("typescript")).toBeUndefined();
	expect(getHighlightLanguageCount()).toBe(count);
	expect(highlight("const x = 1;", { language: "ts" })).toContain("const");
	expect(hljs.getLanguage("typescript")).toBeDefined();
	expect(getHighlightLanguageCount()).toBe(count);

	// The table of names is the grammars' own, and each grammar answers to every one of its names.
	expect(Object.keys(EAGER_LANGUAGE_ALIASES).sort()).toEqual([...EAGER].sort());
	for (const name of EAGER) {
		const definition = (await import(`highlight.js/lib/languages/${name}.js`)).default as (api: typeof hljs) => {
			aliases?: string[];
		};
		const aliases = (definition(hljs).aliases ?? []).map((alias) => alias.toLowerCase());
		expect(EAGER_LANGUAGE_ALIASES[name as keyof typeof EAGER_LANGUAGE_ALIASES], name).toEqual(aliases);
		for (const alias of [...aliases, name]) {
			expect(supportsLanguage(alias), `${name}: ${alias}`).toBe(true);
			expect(hljs.getLanguage(alias), `${name}: ${alias}`).toBe(hljs.getLanguage(name));
		}
	}
	expect(hljs.listLanguages().sort()).toEqual([...EAGER].sort());
	expect(getHighlightLanguageCount()).toBe(count);
	// Automatic detection still sees every common grammar.
	expect(highlight("def f(x):\n    return x\n").length).toBeGreaterThan(0);
});
