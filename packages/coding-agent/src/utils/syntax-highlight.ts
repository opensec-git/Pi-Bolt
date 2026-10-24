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
import { decodeHtmlEntityAt } from "./html.ts";

const eagerLanguages = {
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

let eagerLanguagesRegistered = false;

/** The eager set registers on first use, not at every start: running its grammar definitions costs about 1 ms. */
function registerEagerLanguages(): void {
	if (eagerLanguagesRegistered) return;
	eagerLanguagesRegistered = true;
	for (const [name, language] of Object.entries(eagerLanguages)) {
		hljs.registerLanguage(name, language);
	}
}

let allLanguagesPromise: Promise<void> | undefined;
/** Every grammar is registered (or loading them failed: there is nothing more to wait for). */
let allLanguagesLoaded = false;

export function loadAllHighlightLanguages(): Promise<void> {
	registerEagerLanguages();
	if (!allLanguagesPromise) {
		allLanguagesPromise = new Promise((resolve) => {
			setImmediate(() => {
				void import("highlight.js/lib/index.js").then(
					() => {
						allLanguagesLoaded = true;
						resolve();
					},
					() => {
						// Eager languages and plaintext fallback remain available.
						allLanguagesLoaded = true;
						resolve();
					},
				);
			});
		});
	}
	return allLanguagesPromise;
}

export type HighlightFormatter = (text: string) => string;
export type HighlightTheme = Partial<Record<string, HighlightFormatter>>;

export interface HighlightOptions {
	language?: string;
	ignoreIllegals?: boolean;
	languageSubset?: string[];
	theme?: HighlightTheme;
}

const SPAN_CLOSE = "</span>";
const HIGHLIGHT_CLASS_PREFIX = "hljs-";

function getScopeFromSpanTag(tag: string): string | undefined {
	const match = /\sclass\s*=\s*(?:"([^"]*)"|'([^']*)')/.exec(tag);
	const classValue = match?.[1] ?? match?.[2];
	if (!classValue) {
		return undefined;
	}

	for (const className of classValue.split(/\s+/)) {
		if (className.startsWith(HIGHLIGHT_CLASS_PREFIX)) {
			return className.slice(HIGHLIGHT_CLASS_PREFIX.length);
		}
	}

	return undefined;
}

function getScopeFormatter(scope: string, theme: HighlightTheme): HighlightFormatter | undefined {
	const exact = theme[scope];
	if (exact) {
		return exact;
	}

	const dotIndex = scope.indexOf(".");
	if (dotIndex !== -1) {
		const prefixFormatter = theme[scope.slice(0, dotIndex)];
		if (prefixFormatter) {
			return prefixFormatter;
		}
	}

	const dashIndex = scope.indexOf("-");
	if (dashIndex !== -1) {
		const prefixFormatter = theme[scope.slice(0, dashIndex)];
		if (prefixFormatter) {
			return prefixFormatter;
		}
	}

	return undefined;
}

function getActiveFormatter(scopes: Array<string | undefined>, theme: HighlightTheme): HighlightFormatter | undefined {
	for (let i = scopes.length - 1; i >= 0; i--) {
		const scope = scopes[i];
		if (!scope) {
			continue;
		}
		const formatter = getScopeFormatter(scope, theme);
		if (formatter) {
			return formatter;
		}
	}
	return theme.default;
}

function isSpanOpenTagStart(html: string, index: number): boolean {
	if (!html.startsWith("<span", index)) {
		return false;
	}
	const nextChar = html[index + "<span".length];
	return nextChar === ">" || nextChar === " " || nextChar === "\t" || nextChar === "\n" || nextChar === "\r";
}

export function renderHighlightedHtml(html: string, theme: HighlightTheme = {}): string {
	let output = "";
	let textBuffer = "";
	const scopes: Array<string | undefined> = [];

	const flushText = () => {
		if (!textBuffer) {
			return;
		}
		const formatter = getActiveFormatter(scopes, theme);
		output += formatter
			? textBuffer
					.split("\n")
					.map((line) => (line ? formatter(line) : line))
					.join("\n")
			: textBuffer;
		textBuffer = "";
	};

	let index = 0;
	while (index < html.length) {
		if (isSpanOpenTagStart(html, index)) {
			const tagEndIndex = html.indexOf(">", index + 5);
			if (tagEndIndex !== -1) {
				flushText();
				const tag = html.slice(index, tagEndIndex + 1);
				const scope = getScopeFromSpanTag(tag);
				scopes.push(scope);
				index = tagEndIndex + 1;
				continue;
			}
		}

		if (html.startsWith(SPAN_CLOSE, index)) {
			flushText();
			if (scopes.length > 0) {
				scopes.pop();
			}
			index += SPAN_CLOSE.length;
			continue;
		}

		if (html[index] === "&") {
			const decoded = decodeHtmlEntityAt(html, index);
			if (decoded) {
				textBuffer += decoded.text;
				index += decoded.length;
				continue;
			}
		}

		textBuffer += html[index];
		index++;
	}

	flushText();
	return output;
}

/** A node of highlight.js's token tree (TokenTree in highlight.js/lib/core.js): a text, or a scope and what is in it. */
type TokenNode = string | { kind?: string; sublanguage?: boolean; children?: TokenNode[] };

interface TreeEmitter {
	rootNode: TokenNode;
	options: { classPrefix: string };
	toHTML(): string;
}

const HTML_SPECIAL = /[&<>"']/;

/** escapeHTML() of highlight.js (not exported): its five replacements, made only for a text that has one of the characters. */
function escapeHtml(text: string): string {
	return HTML_SPECIAL.test(text)
		? text
				.replace(/&/g, "&amp;")
				.replace(/</g, "&lt;")
				.replace(/>/g, "&gt;")
				.replace(/"/g, "&quot;")
				.replace(/'/g, "&#x27;")
		: text;
}

/** The class of the span HTMLRenderer (highlight.js/lib/core.js) wraps a node in, or undefined for a node it does not wrap. */
function spanClass(node: { kind?: string; sublanguage?: boolean }, classPrefix: string): string | undefined {
	if (!node.kind) return undefined;
	return node.sublanguage ? node.kind : `${classPrefix}${node.kind}`;
}

/** What HTMLRenderer makes of a token tree: each text escaped, each node with a kind in a span with its class. */
function tokenTreeHtml(node: TokenNode, classPrefix: string): string {
	if (typeof node === "string") return escapeHtml(node);
	if (!node.children) return "";
	const className = spanClass(node, classPrefix);
	let html = className === undefined ? "" : `<span class="${className}">`;
	for (const child of node.children) html += tokenTreeHtml(child, classPrefix);
	return className === undefined ? html : `${html}${SPAN_CLOSE}`;
}

/** The HTML each emitter's toHTML() returned, and the class prefix it was made with (FastHtmlEmitter). */
const emittedHtml = new WeakMap<object, { html: string; classPrefix: string }>();
let fastEmitterInstalled = false;

/**
 * Has highlight.js make its HTML with tokenTreeHtml(): the same HTML as its own renderer, which escapes every text with five
 * regular expression replacements, made in a fraction of the time, and kept with the emitter, whose token tree highlight()
 * then renders from instead of reading the HTML back. Only when the emitter in use is highlight.js's own (TokenTreeEmitter).
 */
function installFastHtmlEmitter(): void {
	if (fastEmitterInstalled) return;
	fastEmitterInstalled = true;
	// (The emitter highlight.js is set up with: highlightAuto() of no languages makes one and runs no plugin.)
	const base = (hljs.highlightAuto("", []).emitter as { constructor: new (options: unknown) => TreeEmitter })
		.constructor;
	if (base.name !== "TokenTreeEmitter" || typeof base.prototype.toHTML !== "function") return;
	class FastHtmlEmitter extends base {
		override toHTML(): string {
			const classPrefix = this.options.classPrefix;
			const html = tokenTreeHtml(this.rootNode, classPrefix);
			emittedHtml.set(this, { html, classPrefix });
			return html;
		}
	}
	hljs.configure({ __emitter: FastHtmlEmitter });
}

const scopesOfClasses = new Map<string, string | undefined>();

/**
 * renderHighlightedHtml() of the HTML tokenTreeHtml() makes of `root`, from the tree: the same texts with the same scopes
 * open. Undefined when a class has a ">", which would end the span tag where the class does not.
 */
function renderHighlightedTree(root: TokenNode, classPrefix: string, theme: HighlightTheme): string | undefined {
	let output = "";
	let textBuffer = "";
	const scopes: Array<string | undefined> = [];
	const flushText = () => {
		if (!textBuffer) return;
		const formatter = getActiveFormatter(scopes, theme);
		if (!formatter) output += textBuffer;
		else if (!textBuffer.includes("\n")) output += formatter(textBuffer);
		else
			output += textBuffer
				.split("\n")
				.map((line) => (line ? formatter(line) : line))
				.join("\n");
		textBuffer = "";
	};
	const walk = (node: TokenNode): boolean => {
		if (typeof node === "string") {
			textBuffer += node;
			return true;
		}
		if (!node.children) return true;
		const className = spanClass(node, classPrefix);
		if (className !== undefined) {
			if (className.includes(">")) return false;
			let scope = scopesOfClasses.get(className);
			if (scope === undefined && !scopesOfClasses.has(className)) {
				scope = getScopeFromSpanTag(`<span class="${className}">`);
				scopesOfClasses.set(className, scope);
			}
			flushText();
			scopes.push(scope);
		}
		for (const child of node.children) {
			if (!walk(child)) return false;
		}
		if (className !== undefined) {
			flushText();
			scopes.pop();
		}
		return true;
	};
	if (!walk(root)) return undefined;
	flushText();
	return output;
}

export function highlight(code: string, options: HighlightOptions = {}): string {
	registerEagerLanguages();
	installFastHtmlEmitter();
	const result = options.language
		? hljs.highlight(code, {
				language: options.language,
				ignoreIllegals: options.ignoreIllegals,
			})
		: hljs.highlightAuto(code, options.languageSubset);
	// The HTML is what the emitter made of its tree, unless the result is not (an illegal match or an error gives the code
	// escaped) or a plugin changed it: the tree is rendered then as the HTML would be.
	const emitter = result.emitter as TreeEmitter;
	const emitted = emittedHtml.get(emitter);
	if (emitted !== undefined && emitted.html === result.value) {
		const rendered = renderHighlightedTree(emitter.rootNode, emitted.classPrefix, options.theme ?? {});
		if (rendered !== undefined) return rendered;
	}
	return renderHighlightedHtml(result.value, options.theme);
}

let onDemandLoaded: ((languages: ReadonlySet<string>) => void) | undefined;
// A language outside the eager set was asked for before loadAllHighlightLanguagesOnDemand() (a resumed session's messages are
// drawn before it): they load as soon as it is called.
let askedBeforeOnDemand = false;
/** Names asked for that were not there (lowercase): what was drawn for them may be drawn differently once all load. */
const missedLanguages = new Set<string>();

function loadOnDemand(): void {
	void loadAllHighlightLanguages().then(() => {
		// Only names that are languages now: a name that is still none (`console`, `output`) is drawn as it was.
		const languages = new Set([...missedLanguages].filter((name) => hljs.getLanguage(name) !== undefined));
		missedLanguages.clear();
		if (languages.size > 0) onDemandLoaded?.(languages);
	});
}

/**
 * Has the grammars outside the eager set load when a language among them is first asked for, instead of at every start
 * (190 grammar definitions run, about 1.4 ms and 1 MB). `onLoaded` runs once they are there, with the names asked for
 * before that are languages now (lowercase): what was drawn with those names without highlighting can then be drawn
 * again. It does not run when none of them is.
 */
export function loadAllHighlightLanguagesOnDemand(onLoaded: (languages: ReadonlySet<string>) => void): void {
	onDemandLoaded = onLoaded;
	if (askedBeforeOnDemand && !allLanguagesPromise) {
		loadOnDemand();
	}
}

export function supportsLanguage(name: string): boolean {
	registerEagerLanguages();
	if (hljs.getLanguage(name) !== undefined) {
		return true;
	}
	if (!allLanguagesLoaded) missedLanguages.add(name.toLowerCase());
	if (!onDemandLoaded) {
		askedBeforeOnDemand = true;
	} else if (!allLanguagesPromise) {
		loadOnDemand();
	}
	return false;
}
