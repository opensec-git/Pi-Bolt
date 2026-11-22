import hljs from "highlight.js/lib/core.js";
import { expect, it } from "vitest";
import { highlight, loadAllHighlightLanguages, supportsLanguage } from "../src/utils/syntax-highlight.ts";

// The order the common grammars had when all of them were registered at the first code block.
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

it("detects languages with the grammars in their usual order, whatever was shown first", async () => {
	for (const name of ["lua", "kotlin", "go"]) expect(supportsLanguage(name)).toBe(true);
	expect(hljs.listLanguages()).toEqual(["lua", "kotlin", "go"]);

	// Automatic detection keeps the first of two grammars that score the same, in registration order.
	highlight("x");
	expect(hljs.listLanguages()).toEqual(EAGER);
	expect(supportsLanguage("lua")).toBe(true);
	expect(hljs.getLanguage("lua")).toBeDefined();

	await loadAllHighlightLanguages();
	expect(hljs.listLanguages().slice(0, EAGER.length)).toEqual(EAGER);
	// An http body is highlighted by detection over every grammar (subLanguage: []).
	const body = highlight("POST /x HTTP/1.1\nHost: a\n\ndef f(x):\n    return x\n", { language: "http" });
	expect(body).toContain("def");
});
