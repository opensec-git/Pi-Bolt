import hljs from "highlight.js/lib/core.js";
import bash from "highlight.js/lib/languages/bash.js";
import c from "highlight.js/lib/languages/c.js";
import cpp from "highlight.js/lib/languages/cpp.js";
import csharp from "highlight.js/lib/languages/csharp.js";
import dart from "highlight.js/lib/languages/dart.js";
import go from "highlight.js/lib/languages/go.js";
import groovy from "highlight.js/lib/languages/groovy.js";
import java from "highlight.js/lib/languages/java.js";
import javascript from "highlight.js/lib/languages/javascript.js";
import json from "highlight.js/lib/languages/json.js";
import kotlin from "highlight.js/lib/languages/kotlin.js";
import lua from "highlight.js/lib/languages/lua.js";
import nix from "highlight.js/lib/languages/nix.js";
import perl from "highlight.js/lib/languages/perl.js";
import php from "highlight.js/lib/languages/php.js";
import python from "highlight.js/lib/languages/python.js";
import ruby from "highlight.js/lib/languages/ruby.js";
import rust from "highlight.js/lib/languages/rust.js";
import scala from "highlight.js/lib/languages/scala.js";
import swift from "highlight.js/lib/languages/swift.js";
import typescript from "highlight.js/lib/languages/typescript.js";
import { expect, it } from "vitest";
import {
	EAGER_LANGUAGE_ALIASES,
	getHighlightLanguageCount,
	highlight,
	supportsLanguage,
} from "../src/utils/syntax-highlight.ts";

/** The common grammars' definitions, by name. Importing a definition does not register it (checked below). */
const DEFINITIONS: Record<keyof typeof EAGER_LANGUAGE_ALIASES, typeof python> = {
	python,
	java,
	go,
	javascript,
	json,
	cpp,
	typescript,
	php,
	ruby,
	c,
	csharp,
	nix,
	bash,
	rust,
	scala,
	kotlin,
	swift,
	dart,
	groovy,
	perl,
	lua,
};

const EAGER = Object.keys(DEFINITIONS) as (keyof typeof DEFINITIONS)[];

it("registers a common grammar when it is first asked for, not when the module loads", () => {
	// Nothing is registered by loading the module or by importing the definitions.
	expect(hljs.listLanguages()).toEqual([]);
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
		const aliases = (DEFINITIONS[name](hljs).aliases ?? []).map((alias) => alias.toLowerCase());
		expect(EAGER_LANGUAGE_ALIASES[name], name).toEqual(aliases);
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
