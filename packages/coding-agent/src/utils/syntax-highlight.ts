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
import { analyse, type RegexAnalysis } from "./regex-reach.ts";

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
	return renderResult(result, options.theme);
}

/** The text of a highlight.js result, its scopes formatted by `theme`. */
function renderResult(result: { value: string; emitter: unknown }, theme: HighlightTheme | undefined): string {
	// The HTML is what the emitter made of its tree, unless the result is not (an illegal match or an error gives the code
	// escaped) or a plugin changed it: the tree is rendered then as the HTML would be.
	const emitter = result.emitter as TreeEmitter;
	const emitted = emittedHtml.get(emitter);
	if (emitted !== undefined && emitted.html === result.value) {
		const rendered = renderHighlightedTree(emitter.rootNode, emitted.classPrefix, theme ?? {});
		if (rendered !== undefined) return rendered;
	}
	return renderHighlightedHtml(result.value, theme);
}

// ============================================================================
// Highlighting code that grows at its end
// ============================================================================

/** A compiled mode of a highlight.js grammar (CompiledMode in highlight.js/lib/core.js), as far as it is read here. */
interface CompiledMode {
	matcher?: ModeMatcher;
	contains?: CompiledMode[];
	starts?: CompiledMode | null;
	endRe?: RegExp;
	keywordPatternRe?: RegExp;
	subLanguage?: string | string[] | null;
	endSameAsBegin?: boolean;
	case_insensitive?: boolean;
	"on:begin"?: ModeCallback;
	"on:end"?: ModeCallback;
	__beforeBegin?: ModeCallback | null;
}

/** A mode's ResumableMultiRegex: the union of the expressions the mode looks for, from `lastIndex`. */
interface ModeMatcher {
	rules: Array<[RegExp | string, { type: string }]>;
	lastIndex: number;
	regexIndex: number;
	exec(text: string): MatcherMatch | null;
}

type MatcherMatch = RegExpExecArray & { rule?: unknown; type?: string };

interface CallbackResponse {
	data: unknown;
	isMatchIgnored: boolean;
	ignoreMatch(): void;
}

type ModeCallback = (match: MatcherMatch, response: CallbackResponse) => void;

const CALLBACK_KEYS = ["on:begin", "on:end", "__beforeBegin"] as const;

/** What the grammar of a language allows the incremental highlighter (GrowingHighlighter) to rely on. */
interface GrammarFacts {
	root: CompiledMode;
	/**
	 * The lines with text there must be after a line for it to be final: one more than the most line breaks an expression
	 * of the grammar that reads across at most MAX_MARGIN_LINES of them (analyse()) can read across.
	 */
	margin: number;
	/** For each other expression: where an attempt of it reads the end of the input. */
	hitEnd: HitEndCheck[];
}

/**
 * An expression of the grammar that can read across more lines than the margin: its "hit end" expression (regex-reach.ts),
 * sticky, and the modes that look for it (an attempt of it is made only where one of them looks for its next match), or
 * every mode (a mode's end expression is also tried where a mode inside it ends).
 */
interface HitEndCheck {
	expression: RegExp;
	modes: Set<CompiledMode> | "all";
	/** The grammar's expressions it was made for. */
	sources: string[];
}

/** Expressions that can read across more line breaks than this are searched for where they read the end instead. */
const MAX_MARGIN_LINES = 2;

/**
 * The grammars GrowingHighlighter follows incrementally, by the names highlight.js registers them under (an alias, such as
 * `ts`, `sh` or `html`, resolves to one of these grammars): each has passed the every-prefix test on a corpus of its own
 * (growing-highlighter.test.ts), and its "hit end" expressions pass the checks there (no expression whose attempt can take
 * more than linear time, and no hang in the fuzz). A grammar joins only with a corpus. Every other grammar is highlighted in
 * full. (Not Markdown: the hit-end expression of its code fence is a lazy repetition followed by an overlapping one, which
 * can take time proportional to the text times the fence's length.)
 */
export const INCREMENTAL_HIGHLIGHT_LANGUAGES: readonly string[] = [
	"typescript",
	"javascript",
	"python",
	"bash",
	"json",
	"rust",
	"go",
	"c",
	"cpp",
	"java",
	"xml",
	"css",
	"diff",
	"sql",
	"yaml",
];

/** Whether a compiled grammar is one of INCREMENTAL_HIGHLIGHT_LANGUAGES. */
function isIncrementalGrammar(grammar: object): boolean {
	for (const name of INCREMENTAL_HIGHLIGHT_LANGUAGES) {
		if (hljs.getLanguage(name) === grammar) return true;
	}
	return false;
}

/** Grammar facts by compiled language, or null for a grammar the incremental highlighter does not take. */
const grammarFacts = new WeakMap<object, GrammarFacts | null>();

/** The modes whose matcher and callbacks traceMode() wrapped. */
const wrappedModes = new WeakSet<CompiledMode>();

/**
 * One run of highlight.js on the end of a text (`tail`, from `offset` on), recording where the run was in no mode at all and
 * what else the result depends on (GrowingHighlighter).
 */
interface Trace {
	root: CompiledMode;
	tail: string;
	text: string;
	offset: number;
	/**
	 * The stretches the run went over in the grammar's top mode, looking for the next match: pairs of the first and the last
	 * position (in `tail`) at which a run started there would have done the same.
	 */
	top: number[];
	/** Every stretch any mode looked for a match over: pairs of the first and the last position an attempt started at. */
	scans: number[];
	/** The mode of each stretch of `scans`. */
	scanModes: CompiledMode[];
	/** The first position of a mode whose text another grammar highlights (the result depends on what it left open). */
	barrier: number;
	/** The callbacks that read the input, at a position in `tail`, and what they decided. */
	callbacks: CallbackCheck[];
}

/** A callback of a mode that read the text it was called for, and what it decided: called again for a longer text. */
interface CallbackCheck {
	position: number;
	callback: ModeCallback;
	groups: Array<string | undefined>;
	index: number;
	rule: unknown;
	type: string | undefined;
	ignored: boolean;
}

let activeTrace: Trace | undefined;

/** The grammar facts of a compiled language (highlight.js compiles it on its first use), finding them the first time. */
function getGrammarFacts(language: object): GrammarFacts | null {
	let facts = grammarFacts.get(language);
	if (facts === undefined) {
		facts = findGrammarFacts(language as CompiledMode);
		grammarFacts.set(language, facts);
	}
	return facts;
}

function findGrammarFacts(root: CompiledMode): GrammarFacts | null {
	if (root.matcher === undefined || !isIncrementalGrammar(root)) return null;
	const flags = `my${root.case_insensitive ? "i" : ""}`;
	let margin = 0;
	const hitEnd = new Map<string, HitEndCheck>();
	const modes = new Set<CompiledMode>();
	const pending: Array<CompiledMode | null> = [root];
	while (pending.length > 0) {
		const mode = pending.pop()!;
		if (mode === null || modes.has(mode)) continue;
		modes.add(mode);
		// (The top mode as a mode inside another: the run would be in it without being at the top.)
		if (mode !== root && mode.contains?.includes(root)) return null;
		// (An end made from the text that began the mode: not an expression of the grammar.)
		if (mode.endSameAsBegin || mode.starts?.endSameAsBegin) return null;
		// (Keyword expressions are not among them: they look through a mode's text, which ends where a match was found;
		// the top mode's is checked below.)
		const sources: Array<[string, boolean]> = [];
		for (const [rule] of mode.matcher?.rules ?? [])
			sources.push([typeof rule === "string" ? rule : rule.source, false]);
		if (mode.endRe !== undefined) sources.push([mode.endRe.source, true]);
		for (const [source, end] of sources) {
			const analysis = analyseRegex(source);
			if (analysis === undefined) return null;
			if (analysis.lines <= MAX_MARGIN_LINES) {
				margin = Math.max(margin, analysis.lines);
				continue;
			}
			if (analysis.hitEnd === undefined) return null;
			let check = hitEnd.get(analysis.hitEnd);
			if (check === undefined) {
				check = { expression: new RegExp(analysis.hitEnd, flags), modes: new Set(), sources: [] };
				hitEnd.set(analysis.hitEnd, check);
			}
			if (!check.sources.includes(source)) check.sources.push(source);
			if (end) check.modes = "all";
			else if (check.modes !== "all") check.modes.add(mode);
		}
		for (const child of mode.contains ?? []) pending.push(child);
		if (mode.starts) pending.push(mode.starts);
	}
	// The text before a match in the top mode is looked through for keywords as one: they must not cross a line break.
	if (root.keywordPatternRe !== undefined && analyseRegex(root.keywordPatternRe.source)?.lines !== 0) return null;
	for (const mode of modes) traceMode(mode);
	return { root, margin: margin + 1, hitEnd: [...hitEnd.values()] };
}

/**
 * The "hit end" expressions GrowingHighlighter derives for a language's grammar (with the flags they run with), each with
 * the grammar's expressions it was made for; null when it highlights the language in full. For the checks in the tests.
 */
export function growingHighlightExpressions(
	language: string,
): Array<{ hitEnd: string; flags: string; sources: string[] }> | null {
	// (Compiles the grammar.)
	highlight("", { language, ignoreIllegals: true });
	const grammar = hljs.getLanguage(language);
	const facts = grammar === undefined ? null : getGrammarFacts(grammar);
	if (facts === null) return null;
	return facts.hitEnd.map((check) => ({
		hitEnd: check.expression.source,
		flags: check.expression.flags,
		sources: check.sources.slice(),
	}));
}

const regexAnalyses = new Map<string, RegexAnalysis | undefined>();

function analyseRegex(source: string): RegexAnalysis | undefined {
	if (regexAnalyses.has(source)) return regexAnalyses.get(source);
	const analysis = analyse(source);
	regexAnalyses.set(source, analysis);
	return analysis;
}

/**
 * Has the mode's matcher and callbacks record what a traced run (activeTrace) does: where the top mode looked for its next
 * match, where a mode whose text another grammar highlights was, and what each callback that read the input decided. A
 * callback of a traced run sees the whole text and its position in it, as in a run over the whole text. Runs that are
 * not traced, and those of another grammar on a mode's text, go through unchanged.
 */
function traceMode(mode: CompiledMode): void {
	if (wrappedModes.has(mode)) return;
	wrappedModes.add(mode);
	const matcher = mode.matcher;
	if (matcher !== undefined) {
		const exec = matcher.exec;
		matcher.exec = function (this: ModeMatcher, text: string): MatcherMatch | null {
			const trace = activeTrace;
			if (trace === undefined || text !== trace.tail) return exec.call(this, text);
			// (After a match was ignored, the scan goes on at the position with the rules after it: not as a new run would.)
			const from = this.regexIndex === 0 ? this.lastIndex : this.lastIndex + 1;
			const result = exec.call(this, text);
			const to = result === null ? text.length : result.index;
			if (mode === trace.root) trace.top.push(from, to);
			trace.scans.push(this.lastIndex, to);
			trace.scanModes.push(mode);
			if (mode.subLanguage !== undefined && mode.subLanguage !== null && this.lastIndex < trace.barrier) {
				trace.barrier = this.lastIndex;
			}
			return result;
		};
	}
	for (const key of CALLBACK_KEYS) {
		const callback = mode[key];
		if (typeof callback === "function") mode[key] = traceCallback(callback, isPrecedingCharacterCheck(key, callback));
	}
}

/**
 * Whether a callback is highlight.js's own check that a keyword does not follow a dot: it reads the character before its
 * match only (always before the checkpoint), so what it decides is final. highlight.js sets `__beforeBegin` (private) to
 * that function only (beginKeywords in core.js); by its name, so that anything else is checked again.
 */
function isPrecedingCharacterCheck(key: (typeof CALLBACK_KEYS)[number], callback: ModeCallback): boolean {
	return key === "__beforeBegin" && callback.name === "skipIfhasPrecedingDot";
}

function traceCallback(callback: ModeCallback, final: boolean): ModeCallback {
	return (match, response) => {
		const trace = activeTrace;
		if (trace === undefined || match.input !== trace.tail) {
			callback(match, response);
			return;
		}
		const index = match.index;
		const input = match.input;
		const data = response.data;
		let readInput = false;
		let readData = false;
		Object.defineProperty(match, "input", {
			configurable: true,
			enumerable: true,
			get: () => {
				readInput = true;
				return trace.text;
			},
		});
		Object.defineProperty(response, "data", {
			configurable: true,
			enumerable: true,
			get: () => {
				readData = true;
				return data;
			},
		});
		match.index = index + trace.offset;
		try {
			callback(match, response);
		} finally {
			Object.defineProperty(match, "input", { configurable: true, enumerable: true, writable: true, value: input });
			Object.defineProperty(response, "data", { configurable: true, enumerable: true, writable: true, value: data });
			match.index = index;
		}
		if (!readInput || final) return;
		if (readData) {
			// (What it decides depends on state kept in the mode: not called again on its own.)
			if (index < trace.barrier) trace.barrier = index;
			return;
		}
		trace.callbacks.push({
			position: index,
			callback,
			groups: Array.from(match),
			index: index + trace.offset,
			rule: match.rule,
			type: match.type,
			ignored: response.isMatchIgnored,
		});
	};
}

/** Whether a callback that read the input decides for `text` what it decided when it was recorded. */
function callbackHolds(check: CallbackCheck, text: string): boolean {
	const match = Object.assign(check.groups.slice(), {
		index: check.index,
		input: text,
		rule: check.rule,
		type: check.type,
	}) as unknown as MatcherMatch;
	const response: CallbackResponse = {
		data: {},
		isMatchIgnored: false,
		ignoreMatch() {
			this.isMatchIgnored = true;
		},
	};
	check.callback(match, response);
	return response.isMatchIgnored === check.ignored;
}

/** Highlights `tail`, the text from `offset` on, recording a trace. Undefined when highlight.js gave up on it. */
function traceHighlight(
	language: string,
	root: CompiledMode,
	text: string,
	offset: number,
	tail: string,
	theme: HighlightTheme | undefined,
): { output: string; trace: Trace } | undefined {
	const trace: Trace = {
		root,
		tail,
		text,
		offset,
		top: [],
		scans: [],
		scanModes: [],
		barrier: tail.length,
		callbacks: [],
	};
	activeTrace = trace;
	let result: ReturnType<typeof hljs.highlight>;
	try {
		result = hljs.highlight(tail, { language, ignoreIllegals: true });
	} finally {
		activeTrace = undefined;
	}
	if (result.illegal === true || result.errorRaised !== undefined) return undefined;
	return { output: renderResult(result, theme), trace };
}

/**
 * Texts shorter than this (characters) are highlighted in full: following one pays for tracing and checks before any of its
 * lines is final. Measured on the runtime without JIT, a block streamed 24 or 53 characters at a time, with the theme's
 * colours: following costs less from about 200 characters in TypeScript, 140 in Python, 100-140 in C, Bash and Rust, and up
 * to ~10% more below; a block that is followed only for its last few texts gains nothing.
 */
export const GROWING_HIGHLIGHT_MIN_LENGTH = 256;

/**
 * The bound on following a text: the work it does may not exceed this multiple of the work highlighting each text in full
 * would have done, plus WORK_ALLOWANCE. Work is counted in characters highlighted (by highlight.js) and calls made besides
 * (an attempt of a "hit end" expression, a callback called again), one character each. On the runtime without JIT
 * highlight.js costs ~17-20 K instructions per character and such a call ~5 K on average, so a call is counted at about
 * three times its cost.
 */
export const GROWING_HIGHLIGHT_WORK_MULTIPLE = 2;
const WORK_ALLOWANCE = 4096;

/** Overrides of the thresholds of a GrowingHighlighter (tests). */
export interface GrowingHighlightLimits {
	/** GROWING_HIGHLIGHT_MIN_LENGTH. */
	minLength?: number;
	/** GROWING_HIGHLIGHT_WORK_MULTIPLE. */
	workMultiple?: number;
}

/**
 * Highlights a text that grows at its end, such as a code block streaming in, without highlighting all of it each time:
 * lines() gives what `highlight(code, { language, ignoreIllegals: true, theme }).split("\n")` gives, the same lines.
 *
 * highlight.js reads a text from its start, in modes (a string, a comment, a function's parameters) whose matches it looks
 * for with regular expressions. Where it is at the start of a line in no mode, looking for the next match of its top mode,
 * the lines before are final once nothing it did before the line can turn out otherwise for a longer text:
 * - no attempt of an expression before the line read the end of the text (bounded expressions: there are enough lines
 *   with text after it; others: their "hit end" expression finds no such attempt, regex-reach.ts);
 * - each callback that read the text decides the same for the longer text (checked again each time);
 * - no mode whose text another grammar highlights was entered before it (the other grammar's state is carried along).
 * The run from such a line on, over the rest of the text alone, is then the run over the whole text from there: only that
 * part is highlighted again. A grammar whose expressions cannot be analysed is highlighted in full each time.
 *
 * Many grammars take the line break and the next line's indentation into the match before them (in JavaScript, everything
 * after `;`, `{`, `,` or `(` that may start an expression): then no line start is such a place. A line start then also
 * counts when the place after its indentation is one, nothing was looked for in the indentation and the indentation is
 * drawn as it is; the run from the line start must then find nothing in the indentation and draw it so too (checked each
 * time).
 */
export class GrowingHighlighter {
	private readonly language: string;
	private readonly theme: HighlightTheme | undefined;
	/** The compiled language the state is for (it is replaced when every grammar is loaded). */
	private grammar: object | undefined;
	/**
	 * Where the lines that are final end (a line start, or 0), and the text when that was found. (What was done before the
	 * checkpoint read no further than the end of that text: it holds for that text with anything after it, not for one
	 * that only starts as it does up to the checkpoint.)
	 */
	private checkpoint = 0;
	private checkpointText = "";
	/** The lines before the checkpoint. */
	private settled: string[] = [];
	/** The callbacks before the checkpoint that read the input. */
	private checks: CallbackCheck[] = [];
	/**
	 * The length of the indentation of the line at the checkpoint when the place after the indentation was where the run
	 * was in the top mode (0 when the line start itself was): the run from the line start must go over the indentation in
	 * the top mode, finding nothing, and draw it as it is.
	 */
	private indent = 0;
	/**
	 * For each "hit end" expression of the grammar, a position (at most the checkpoint) before which no attempt of its
	 * expression reads the end of the text. An attempt that does not read the end of a text reads the same in any longer
	 * one: each position before the checkpoint is looked at once while the text grows.
	 */
	private hitFrom: number[] = [];
	private readonly minLength: number;
	private readonly workMultiple: number;
	/**
	 * The work done following the text, and the work highlighting each text in full would have done, over the highlighter's
	 * life (GROWING_HIGHLIGHT_WORK_MULTIPLE).
	 */
	private work = 0;
	private fullWork = 0;
	/** The work bound was reached: every text is highlighted in full from then on. */
	private fellBack = false;

	constructor(language: string, theme?: HighlightTheme, limits?: GrowingHighlightLimits) {
		this.language = language;
		this.theme = theme;
		this.minLength = limits?.minLength ?? GROWING_HIGHLIGHT_MIN_LENGTH;
		this.workMultiple = limits?.workMultiple ?? GROWING_HIGHLIGHT_WORK_MULTIPLE;
	}

	/** The length of the text before the checkpoint: the lines there are not highlighted again. */
	get finalLength(): number {
		return this.checkpoint;
	}

	/** Pays for an attempt of a "hit end" expression (firstHitEnd()). */
	private readonly spendOnAttempt = (): boolean => this.spend(1);

	/** Whether the work bound was reached and every text is highlighted in full now (for tests). */
	get highlightsInFull(): boolean {
		return this.fellBack;
	}

	/** The lines of `code` highlighted. The array is the caller's; its lines before the checkpoint are shared. */
	lines(code: string): string[] {
		registerEagerLanguages();
		installFastHtmlEmitter();
		const grammar = hljs.getLanguage(this.language);
		if (grammar === undefined) throw new Error(`Unknown language: "${this.language}"`);
		// (A short text costs less highlighted in full than followed: what following costs before any line is final.)
		if (this.fellBack || code.length < this.minLength) return this.full(code, undefined);
		// (The grammar is compiled on its first use, and its facts can be found only then.)
		const facts = (grammar as CompiledMode).matcher === undefined ? null : getGrammarFacts(grammar);
		if (facts === null) {
			const lines = this.full(code, undefined);
			// (A grammar is compiled by its first use: the next text can be traced.)
			if ((grammar as CompiledMode).matcher !== undefined) getGrammarFacts(grammar);
			return lines;
		}
		this.fullWork += code.length;
		if (grammar !== this.grammar) {
			this.reset(grammar);
		} else if (!code.startsWith(this.checkpointText) || !this.callbacksHold(code)) {
			if (this.fellBack) return this.full(code, undefined);
			this.reset(grammar);
		}
		const offset = this.checkpoint;
		const tail = offset === 0 ? code : code.slice(offset);
		this.work += tail.length;
		const traced = traceHighlight(this.language, facts.root, code, offset, tail, this.theme);
		if (traced === undefined) return this.full(code, undefined);
		const tailLines = traced.output.split("\n");
		if (tailLines.length !== lineCount(tail)) {
			// highlight.js skipped a line break without drawing it: an `illegal` that matches no text (CSS, LESS, SCSS and
			// Stylus have `$`) makes it skip one character under ignoreIllegals. The lines drawn are then not the lines of the
			// text, and a checkpoint (a line of the text) would settle the wrong ones: the whole text is highlighted, with
			// no checkpoint, as long as that holds.
			return this.full(code, grammar);
		}
		const indent = this.indent;
		if (indent > 0) {
			const top = traced.trace.top;
			if (!(top[0] === 0 && top[1]! >= indent && tailLines[0]!.startsWith(tail.slice(0, indent)))) {
				return this.full(code, grammar);
			}
		}
		const lines = offset === 0 ? tailLines : this.settled.concat(tailLines);
		this.advance(code, tailLines, traced.trace, facts);
		return lines;
	}

	/** The full highlight of `code`, from no checkpoint (kept for `grammar`, or for none). */
	private full(code: string, grammar: object | undefined): string[] {
		this.reset(grammar);
		return highlight(code, { language: this.language, ignoreIllegals: true, theme: this.theme }).split("\n");
	}

	/**
	 * Counts work about to be done that a full highlight would not do (`cost`). Past the bound (the work multiple of the full
	 * highlights' work, plus WORK_ALLOWANCE) it is not to be done: the highlighter highlights in full from then on, and false
	 * is returned.
	 */
	private spend(cost: number): boolean {
		if (this.fellBack) return false;
		this.work += cost;
		if (this.work <= this.workMultiple * this.fullWork + WORK_ALLOWANCE) return true;
		this.fellBack = true;
		return false;
	}

	private reset(grammar: object | undefined): void {
		this.grammar = grammar;
		this.checkpoint = 0;
		this.checkpointText = "";
		this.settled = [];
		this.checks = [];
		this.hitFrom = [];
		this.indent = 0;
	}

	/** Whether each callback before the checkpoint that read the text decides for `code` what it decided. */
	private callbacksHold(code: string): boolean {
		for (const check of this.checks) {
			if (!this.spend(1) || !callbackHolds(check, code)) return false;
		}
		return true;
	}

	/** Moves the checkpoint to the last line start of the traced tail before which the lines are final. */
	private advance(code: string, tailLines: string[], trace: Trace, facts: GrammarFacts): void {
		const offset = this.checkpoint;
		const tail = trace.tail;
		// The line with the `margin`-th line with text counted from the end: bounded expressions cannot read the end from
		// before its start.
		let withText = 0;
		let lineEnd = tail.length;
		let marginStart = 0;
		for (;;) {
			const lineStart = lineEnd === 0 ? 0 : tail.lastIndexOf("\n", lineEnd - 1) + 1;
			if (/\S/.test(tail.slice(lineStart, lineEnd)) && ++withText === facts.margin) {
				marginStart = lineStart;
				break;
			}
			if (lineStart === 0) break;
			lineEnd = lineStart - 1;
		}
		let found = checkpointBefore(trace, tailLines, marginStart);
		// Then no attempt of the other expressions before it may read the end: looked for where the modes that try them
		// looked for a match. (Where none does before a position, none will for a longer text: the next time the search
		// goes on from there.)
		for (let i = 0; i < facts.hitEnd.length && found !== undefined; i++) {
			const from = Math.max(this.hitFrom[i] ?? 0, offset) - offset;
			if (from >= found.start) continue;
			const hit = firstHitEnd(facts.hitEnd[i]!, trace, code, from, found.start, this.spendOnAttempt);
			// (Past the work bound: the text was highlighted, the highlighter highlights in full from now on.)
			if (hit === null) return;
			if (hit === undefined) {
				this.hitFrom[i] = offset + found.start;
			} else {
				this.hitFrom[i] = offset + hit;
				found = checkpointBefore(trace, tailLines, hit);
				i = -1;
			}
		}
		// (Where nothing was looked for is known only before the checkpoint: after it the run can change.)
		const final = offset + (found?.start ?? 0);
		for (let i = 0; i < this.hitFrom.length; i++) this.hitFrom[i] = Math.min(this.hitFrom[i]!, final);
		if (found === undefined) return;
		const checkpoint = found.start;
		for (let i = 0; i < found.line; i++) this.settled.push(tailLines[i]!);
		for (const check of trace.callbacks) {
			if (check.position < checkpoint) this.checks.push({ ...check, position: check.position + offset });
		}
		this.checkpoint = offset + checkpoint;
		this.checkpointText = code;
		this.indent = found.indent;
	}
}

/** The number of lines of `text` (line breaks plus one). */
function lineCount(text: string): number {
	let count = 1;
	for (let i = text.indexOf("\n"); i !== -1; i = text.indexOf("\n", i + 1)) count++;
	return count;
}

/** How many line starts before a limit checkpointBefore() looks at. */
const CHECKPOINT_CANDIDATES = 16;

/**
 * The last line start (in the traced tail, above 0) up to `limit` from which a run over the rest of the text alone is the
 * traced run (GrowingHighlighter): `start`, its line number in the tail (`line`), and `indent`, 0 when the run was in the
 * top mode at the line start, else the length of the indentation after which it was. Undefined for none.
 */
function checkpointBefore(
	trace: Trace,
	tailLines: readonly string[],
	limit: number,
): { start: number; line: number; indent: number } | undefined {
	const tail = trace.tail;
	if (limit <= 0) return undefined;
	let start = tail.lastIndexOf("\n", limit - 1) + 1;
	if (start === 0) return undefined;
	let line = 0;
	for (let i = tail.indexOf("\n"); i !== -1 && i < start; i = tail.indexOf("\n", i + 1)) line++;
	for (let candidates = 0; start > 0 && candidates < CHECKPOINT_CANDIDATES; candidates++) {
		if (start <= trace.barrier && inStretch(trace.top, start)) return { start, line, indent: 0 };
		let after = start;
		while (tail[after] === " " || tail[after] === "\t") after++;
		if (
			after > start &&
			after <= trace.barrier &&
			after < tail.length &&
			tail[after] !== "\n" &&
			inStretch(trace.top, after) &&
			!scannedIn(trace.scans, start, after) &&
			tailLines[line]!.startsWith(tail.slice(start, after))
		) {
			return { start, line, indent: after - start };
		}
		start = tail.lastIndexOf("\n", start - 2) + 1;
		line--;
	}
	return undefined;
}

/** Whether `position` is in one of the stretches (pairs of first and last position). */
function inStretch(stretches: readonly number[], position: number): boolean {
	for (let i = stretches.length - 2; i >= 0; i -= 2) {
		if (stretches[i]! <= position && position <= stretches[i + 1]!) return true;
	}
	return false;
}

/**
 * The first position from `from` to before `to` (in the traced tail) at which an attempt of the check's expression reads
 * the end of `text`, among those where a mode that tries the expression looked for a match; undefined for none. Each
 * attempt is paid for first (`spend`): null when one could not be.
 */
function firstHitEnd(
	check: HitEndCheck,
	trace: Trace,
	text: string,
	from: number,
	to: number,
	spend: () => boolean,
): number | null | undefined {
	const { expression, modes } = check;
	const scans = trace.scans;
	let first: number | undefined;
	for (let i = 0; i < scans.length; i += 2) {
		if (modes !== "all" && !modes.has(trace.scanModes[i / 2]!)) continue;
		const end = Math.min(scans[i + 1]!, to - 1, first === undefined ? Number.POSITIVE_INFINITY : first - 1);
		for (let position = Math.max(scans[i]!, from); position <= end; position++) {
			expression.lastIndex = trace.offset + position;
			if (!spend()) return null;
			if (expression.test(text)) {
				first = position;
				break;
			}
		}
	}
	return first;
}

/** Whether an attempt started anywhere from `from` to before `to`, by the stretches (pairs of first and last position). */
function scannedIn(stretches: readonly number[], from: number, to: number): boolean {
	for (let i = 0; i < stretches.length; i += 2) {
		if (stretches[i]! < to && stretches[i + 1]! >= from) return true;
	}
	return false;
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

/**
 * Number of registered grammars. It grows when the deferred grammars load, which can change the highlighting of
 * code that embeds another language, such as HTML in a JavaScript template literal.
 */
export function getHighlightLanguageCount(): number {
	return hljs.listLanguages().length;
}
