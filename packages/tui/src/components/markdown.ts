import { Lexer, Marked, type Token, Tokenizer, type TokenizerExtension, type Tokens } from "marked";
import markedCjkFriendly from "marked-cjk-friendly";
import { renderLatex } from "../latex.ts";
import { invalidateRenderedMarkdown, renderedMarkdownGeneration } from "../rendered-markdown.ts";
import { getCapabilities, hyperlink, isImageLine } from "../terminal-image.ts";
import {
	type Component,
	LiveLineArray,
	type LiveLines,
	markLiveRender,
	markReportsChanges,
	markStableRender,
	reportRenderChange,
} from "../tui.ts";
import {
	applyBackgroundToLine,
	flattenLines,
	isWebLink,
	type SanitizedTerminalText,
	sanitizeTerminalText,
	sanitizeTerminalTextFrom,
	startsWithText,
	visibleWidth,
	wrapTextWithAnsi,
} from "../utils.ts";

const SGR_SEQUENCE_ALL = /\x1b\[([0-9;:]*)m/g;

/**
 * Whether an SGR sequence with these parameters leaves the background reset: a full reset (0, or no parameters) or a
 * background reset (49) that no later background color in the same sequence replaces. In a shaded code block it would end
 * the shading.
 */
function endsBackground(params: string): boolean {
	const codes = params.split(/[;:]/);
	let reset = false;
	for (let i = 0; i < codes.length; i++) {
		const code = Number(codes[i] || 0);
		if (code === 0 || code === 49) reset = true;
		else if ((code >= 40 && code <= 47) || (code >= 100 && code <= 107)) reset = false;
		// (An extended color's operands are not codes: 38;5;N, 48;2;R;G;B.)
		if (code === 38 || code === 48 || code === 58) {
			if (code === 48) reset = false;
			i += codes[i + 1] === "5" ? 2 : 4;
		}
	}
	return reset;
}
const STRICT_STRIKETHROUGH_REGEX = /^(~~)(?=[^\s~])((?:\\.|[^\\])*?(?:\\.|[^\s~\\]))\1(?=[^~]|$)/;

/** A line that can end a setext heading: what follows the line break in marked's lheading rule, `\n {0,3}(=+|-+) *(?:\n+|$)`. */
const SETEXT_UNDERLINE = / {0,3}(?:=+|-+) *(?:\n|$)/y;
/** A line of whitespace ended by a line break: where marked's lheading rule stops looking (`\n(?!\s*?\n|...)`). */
const BLANK_LINE = /[^\S\n]*\n/y;
/** Lines looked at for an underline before leaving the answer to marked's own rule. */
const SETEXT_LOOKAHEAD_LINES = 64;
let setextGuard = true;

/**
 * Whether marked's lheading rule can match at the start of `src`. Its regular expression reads the text a character at a
 * time up to the first blank line, and marked tries it at the start of every block that is not a list, a fence, a heading
 * or a table: on a long transcript, about a tenth of the time Pi takes to lay it out. The rule needs a line of `=` or `-`
 * (up to 3 spaces before, spaces after) after one of the line breaks it reads, and it reads none past a line break that
 * begins a blank line. With no such line before that, it cannot match. The answer is the regular expression's when an
 * underline is found or the text goes on for more lines without a blank one.
 */
function mayStartSetextHeading(src: string): boolean {
	let lineBreak = src.indexOf("\n");
	for (let lines = 0; lineBreak !== -1; lines++) {
		if (lines === SETEXT_LOOKAHEAD_LINES) return true;
		SETEXT_UNDERLINE.lastIndex = lineBreak + 1;
		if (SETEXT_UNDERLINE.test(src)) return true;
		BLANK_LINE.lastIndex = lineBreak + 1;
		if (BLANK_LINE.test(src)) return false;
		lineBreak = src.indexOf("\n", lineBreak + 1);
	}
	return false;
}

class StrictStrikethroughTokenizer extends Tokenizer {
	override lheading(src: string): Tokens.Heading | undefined {
		return !setextGuard || mayStartSetextHeading(src) ? super.lheading(src) : undefined;
	}

	override del(src: string): Tokens.Del | undefined {
		const match = STRICT_STRIKETHROUGH_REGEX.exec(src);
		if (!match) {
			return undefined;
		}

		const text = match[2];
		return {
			type: "del",
			raw: match[0],
			text,
			tokens: this.lexer.inlineTokens(text),
		};
	}
}

interface LatexToken extends Tokens.Generic {
	type: "latex" | "latexBlock";
	text: string;
	pending?: boolean;
}

function isEscaped(source: string, index: number): boolean {
	let backslashes = 0;
	for (let position = index - 1; position >= 0 && source[position] === "\\"; position--) {
		backslashes++;
	}
	return backslashes % 2 === 1;
}

function findClosingDelimiter(source: string, closing: string, start: number): number {
	let index = source.indexOf(closing, start);
	while (index >= 0 && isEscaped(source, index)) {
		index = source.indexOf(closing, index + closing.length);
	}
	return index;
}

function looksLikePendingDollarMath(source: string): boolean {
	return /\\[A-Za-z]+|[_^=+*/<>()[\]|±≤≥≠≈∈→⇒∞∫∑√-]/.test(source);
}

function tokenizeInlineLatex(source: string): LatexToken | undefined {
	let opening = "";
	let closing = "";
	if (source.startsWith("$$")) {
		opening = "$$";
		closing = "$$";
	} else if (source.startsWith("\\(")) {
		opening = "\\(";
		closing = "\\)";
	} else if (source.startsWith("\\[")) {
		opening = "\\[";
		closing = "\\]";
	} else if (source.startsWith("$") && !/^\$\s/.test(source)) {
		opening = "$";
		closing = "$";
	} else {
		return undefined;
	}

	const closingIndex = findClosingDelimiter(source, closing, opening.length);
	if (
		closingIndex >= 0 &&
		opening === "$" &&
		(/\s$/.test(source.slice(opening.length, closingIndex)) ||
			/^\d/.test(source.slice(closingIndex + 1)) ||
			(/^[A-Z_][A-Z0-9_]*(?:[^A-Za-z0-9_\s])?$/.test(source.slice(opening.length, closingIndex)) &&
				/^[A-Za-z_][A-Za-z0-9_]*/.test(source.slice(closingIndex + 1))) ||
			source.slice(opening.length, closingIndex).includes("`"))
	) {
		return undefined;
	}

	if (closingIndex < 0) {
		const pendingSource = source.slice(opening.length);
		if (opening.startsWith("\\") || looksLikePendingDollarMath(pendingSource)) {
			return { type: "latex", raw: source, text: pendingSource, pending: true };
		}
		return undefined;
	}

	const text = source.slice(opening.length, closingIndex);
	if (!text || text.includes("\n")) {
		return undefined;
	}

	const raw = source.slice(0, closingIndex + closing.length);
	return { type: "latex", raw, text };
}

const TABLE_DELIMITER_ROW_REGEX = /^ {0,3}\|?[ \t]*:?-+:?[ \t]*(?:\|[ \t]*:?-+:?[ \t]*)*\|?[ \t]*$/;

// A GFM table starts with a header row followed by a delimiter row such as "|---|---|" or "| --- |".
// The header needs a "|" only when the delimiter has none: a pipe-free delimiter without a pipe in the
// header is a thematic break, not a table.
function containsTableStart(source: string): boolean {
	const lines = source.split("\n");
	for (let index = 0; index < lines.length - 1; index++) {
		const delimiterRow = lines[index + 1];
		if ((delimiterRow.includes("|") || lines[index].includes("|")) && TABLE_DELIMITER_ROW_REGEX.test(delimiterRow)) {
			return true;
		}
	}
	return false;
}

function tokenizeBlockLatex(source: string): LatexToken | undefined {
	// Display math never spans a table. Without this check, an unclosed "$$" or "\[" pairs with a later
	// opener or stays pending, swallowing the table. See https://github.com/earendil-works/pi/issues/11159.
	const dollarMatch = /^ {0,3}\$\$[ \t]*(?:\n)?([\s\S]*?)\$\$[ \t]*(?:\n|$)/.exec(source);
	if (dollarMatch?.[1] && !containsTableStart(dollarMatch[1])) {
		return { type: "latexBlock", raw: dollarMatch[0], text: dollarMatch[1].trim() };
	}

	const bracketMatch = /^ {0,3}\\\[[ \t]*(?:\n)?([\s\S]*?)\\\][ \t]*(?:\n|$)/.exec(source);
	if (bracketMatch?.[1] && !containsTableStart(bracketMatch[1])) {
		return { type: "latexBlock", raw: bracketMatch[0], text: bracketMatch[1].trim() };
	}

	const pendingBracket = /^ {0,3}\\\[[ \t]*(?:\n)?([\s\S]*)$/.exec(source);
	if (pendingBracket?.[1] && !containsTableStart(pendingBracket[1])) {
		return { type: "latexBlock", raw: pendingBracket[0], text: pendingBracket[1], pending: true };
	}
	const pendingDollar = /^ {0,3}\$\$[ \t]*(?:\n)?([\s\S]*)$/.exec(source);
	if (pendingDollar?.[1] && looksLikePendingDollarMath(pendingDollar[1]) && !containsTableStart(pendingDollar[1])) {
		return { type: "latexBlock", raw: pendingDollar[0], text: pendingDollar[1], pending: true };
	}
	return undefined;
}

const LATEX_MARKDOWN_EXTENSIONS: readonly TokenizerExtension[] = [
	{
		name: "latexBlock",
		level: "block",
		start(source) {
			const match = /(?:^|\n) {0,3}(?:\$\$|\\\[)/.exec(source);
			return match ? match.index + (match[0].startsWith("\n") ? 1 : 0) : undefined;
		},
		tokenizer: tokenizeBlockLatex,
	},
	{
		name: "latex",
		level: "inline",
		start(source) {
			const indices = [source.indexOf("$"), source.indexOf("\\("), source.indexOf("\\[")].filter(
				(index) => index >= 0,
			);
			return indices.length > 0 ? Math.min(...indices) : undefined;
		},
		tokenizer: tokenizeInlineLatex,
	},
];

function trimPartialClosingFences(tokens: readonly Token[]): void {
	const token = tokens[tokens.length - 1];
	if (token?.type === "list") {
		trimPartialClosingFences(token.items[token.items.length - 1]?.tokens ?? []);
		return;
	}
	if (token?.type === "blockquote") {
		trimPartialClosingFences(token.tokens ?? []);
		return;
	}
	if (token?.type !== "code") {
		return;
	}

	// Trim streamed partial closing fences so code blocks do not shrink/flicker
	// when the final fence character arrives. See https://github.com/earendil-works/pi/issues/5825.
	const marker = /^(`{3,}|~{3,})/.exec(token.raw)?.[1];
	// (The text after the last line break: the whole of a code block's raw text is not split into lines for it.)
	const lastLine = token.raw.slice(token.raw.lastIndexOf("\n") + 1);
	if (!marker || !lastLine || lastLine.length >= marker.length || lastLine !== marker[0]?.repeat(lastLine.length)) {
		return;
	}

	token.text = token.text.slice(0, -lastLine.length).replace(/\n$/, "");
}

const markdownParser = new Marked();
markdownParser.setOptions({
	tokenizer: new StrictStrikethroughTokenizer(),
});
markdownParser.use({
	tokenizer: { emStrong: markedCjkFriendly().tokenizer?.emStrong },
	extensions: [...LATEX_MARKDOWN_EXTENSIONS],
});

/** What inlineTokens() made of a text, and the lexer's state after it. */
interface LexedInline {
	tokens: Token[];
	inLink: boolean;
	inRawBlock: boolean;
	linkEmitted: boolean;
}

/**
 * The inline tokens of the texts lexed by the last lex and by this one so far, by the lexer's state before them (the bits
 * of inlineFlags()). While a reply streams in, the blocks it ends with are lexed again for each chunk, and all their texts
 * but the one that grew (a list's items, a table's cells, a block lexed again unchanged) are texts lexed the time before.
 */
let lexedInlines = new Map<number, Map<string, LexedInline>>();
let nextLexedInlines = new Map<number, Map<string, LexedInline>>();

function inlineFlags(state: Lexer["state"]): number {
	return (state.inLink ? 1 : 0) | (state.inRawBlock ? 2 : 0) | (state.linkEmitted ? 4 : 0);
}

/**
 * marked's lexer, taking the inline tokens of a text from the last lex when it lexed the same text from the same state.
 * What inlineTokens() makes of a text depends on the text, on the lexer's state before it (inLink, inRawBlock, linkEmitted;
 * it leaves them changed for the next text), and on the link definitions of the document, which only a text with a "["
 * can refer to. A text with a "[" in a document with link definitions is lexed as marked lexes it, and so are the texts
 * lexed inside a text (an emphasis's, a link's) and those given tokens to add to (a task's checkbox).
 * The tokens are shared by the token lists they are in: nothing changes inline tokens after lexing (trimPartialClosingFences()
 * changes code blocks only).
 */
class InlineMemoLexer extends Lexer {
	private inlineDepth = 0;

	override lex(src: string): ReturnType<Lexer["lex"]> {
		try {
			return super.lex(src);
		} finally {
			lexedInlines = nextLexedInlines;
			nextLexedInlines = new Map();
		}
	}

	override inlineTokens(src: string, tokens: Token[] = []): Token[] {
		if (
			this.inlineDepth > 0 ||
			tokens.length > 0 ||
			(src.includes("[") && Object.keys(this.tokens.links ?? {}).length > 0)
		) {
			this.inlineDepth++;
			try {
				return super.inlineTokens(src, tokens);
			} finally {
				this.inlineDepth--;
			}
		}
		const state = this.state;
		const flags = inlineFlags(state);
		let next = nextLexedInlines.get(flags);
		if (next === undefined) {
			next = new Map();
			nextLexedInlines.set(flags, next);
		}
		let lexed = next.get(src) ?? lexedInlines.get(flags)?.get(src);
		if (lexed === undefined) {
			this.inlineDepth++;
			try {
				super.inlineTokens(src, tokens);
			} finally {
				this.inlineDepth--;
			}
			lexed = {
				tokens: tokens.slice(),
				inLink: state.inLink,
				inRawBlock: state.inRawBlock,
				linkEmitted: state.linkEmitted,
			};
		} else {
			for (const token of lexed.tokens) tokens.push(token);
			state.inLink = lexed.inLink;
			state.inRawBlock = lexed.inRawBlock;
			state.linkEmitted = lexed.linkEmitted;
		}
		next.set(src, lexed);
		return tokens;
	}
}

let memos = true;

/** marked's lexer() for Pi's Markdown: InlineMemoLexer, or marked's own lexer with the memos off. */
function lexTokens(source: string): Token[] {
	return memos && markdownCaching
		? new InlineMemoLexer(markdownParser.defaults).lex(source)
		: markdownParser.lexer(source);
}

interface LexedMarkdown {
	source: string;
	/** For tokens lexed from those of a shorter source: the text lexed for them, and where in `source` it starts. */
	end?: { start: number; text: string };
	tokens: Token[];
	/** Whether lexing can go on from these tokens when text is added to the source. */
	resumable: boolean;
	/** Whether the tokens were lexed from those of a shorter source (lexAddedMarkdown()), not from all of this one. */
	pieced: boolean;
}

/** A source that is one lexed before (`from`) with `added` after it (replaceTabs()). */
interface ExtendedSource {
	from: LexedMarkdown;
	added: string;
}

/** What the last lexMarkdown() lexed or took, kept with the source (when it was kept). */
let lastLexed: LexedMarkdown | undefined;
/** The end of the source the last lexAddedMarkdown() lexed (LexedMarkdown.end). */
let lastEnd: { start: number; text: string } | undefined;

/** The sources lexed last, most recent last: a message that is streaming in is lexed again each time it grows. */
const lexedMarkdown: LexedMarkdown[] = [];
const LEXED_MARKDOWN_KEPT = 8;

/**
 * For tokens lexed from those of a shorter source (lexAddedMarkdown()): those tokens, and how many leading tokens the two
 * have in common (the same objects). Held weakly: the shorter source's tokens are not needed once these are rendered.
 */
const lexedFrom = new WeakMap<Token[], { tokens: WeakRef<Token[]>; shared: number }>();

function hasLinkDefinitions(tokens: Token[]): boolean {
	const links = (tokens as Partial<{ links: Record<string, unknown> }>).links;
	return links !== undefined && Object.keys(links).length > 0;
}

/**
 * A copy of `text` that shares no memory with the string it was cut from. (Engines make a part of a string, and a part of
 * a concatenation with it, out of the string's own memory; what JSON.parse() returns is a new string, the same text
 * whatever is in it.)
 */
function ownString(text: string): string {
	return JSON.parse(JSON.stringify(text)) as string;
}

/** Whether two tokens are the same in every field, in every token in them. */
function sameTokens(a: unknown, b: unknown): boolean {
	if (a === b) return true;
	if (typeof a !== "object" || typeof b !== "object" || a === null || b === null) return false;
	if (Array.isArray(a)) {
		if (!Array.isArray(b) || a.length !== b.length) return false;
		for (let i = 0; i < a.length; i++) if (!sameTokens(a[i], b[i])) return false;
		return true;
	}
	if (Array.isArray(b)) return false;
	const aFields = a as Record<string, unknown>;
	const bFields = b as Record<string, unknown>;
	const keys = Object.keys(aFields);
	if (keys.length !== Object.keys(bFields).length) return false;
	for (const key of keys) {
		if (!Object.hasOwn(bFields, key) || !sameTokens(aFields[key], bFields[key])) return false;
	}
	return true;
}

let fastPaths = true;

/** Block types that marked tries before a paragraph or a setext heading: what they are is known from their first line. */
const SELF_STARTING_BLOCKS = new Set(["code", "heading", "hr", "list", "blockquote", "table", "html"]);

/**
 * Whether the block at `before` ends where it does whatever text is added after the block at `last` (the last of
 * `tokens`, with only blank lines between the two): when `last` is past its first line that is not blank, that line is
 * known, and a block that starts as SELF_STARTING_BLOCKS do ends at the first line it cannot take. Not a paragraph: a
 * setext underline lines later can make a heading of it and of the lines after it that an extension's block (`$$`) took.
 */
function lastBlockSettlesOneBefore(tokens: readonly Token[], before: number, last: number): boolean {
	if (!fastPaths || before < 0 || !SELF_STARTING_BLOCKS.has(tokens[before]!.type)) return false;
	// (A block's raw text can begin with lines of spaces.)
	const raw = tokens[last]!.raw;
	const text = raw.search(/\S/);
	return text >= 0 && raw.includes("\n", text);
}

/**
 * The tokens of `source`, which is `previous.source` with text added at its end. Where a top-level block ends depends on the
 * line that follows it and on nothing after that line. The last block may still be on its first line ("2" turning into "2."
 * makes it an item of the list before it), so the block before it is not settled either: the blocks before those two are
 * what they were, and the text from the last two on is lexed. Once the last block is past that line, the block before it
 * is settled too when lastBlockSettlesOneBefore() says so, and only the last block is lexed.
 * Undefined when that would not give what lexing all of it gives: link definitions change how links before them read.
 */
function lexAddedMarkdown(previous: LexedMarkdown, source: string, added?: string): Token[] | undefined {
	let last = previous.tokens.length;
	let lastBlock = -1;
	for (let blocks = 0; blocks < 2; blocks++) {
		last--;
		while (last >= 0 && previous.tokens[last].type === "space") {
			last--;
		}
		if (blocks === 1 && lastBlock >= 0 && lastBlockSettlesOneBefore(previous.tokens, last, lastBlock)) {
			last = lastBlock;
		}
		if (blocks === 0) lastBlock = last;
	}
	if (last <= 0) {
		return undefined;
	}
	// (The tokens of a resumable source add up to it: where the kept ones end is counted from the end, over the last few.)
	let offset = previous.source.length;
	for (let i = last; i < previous.tokens.length; i++) {
		offset -= previous.tokens[i].raw.length;
	}
	// The text of a token is a part of the string that was lexed, and keeps all of that string in memory. Lexing the end
	// of `source` as a part of it would leave every block of a long message holding the whole message as it was when
	// the block was lexed, megabytes for each answer: the end is lexed as a string of its own.
	// (`added`: what `source` adds to the previous source, when that is known. The end is then taken from the end of the
	// previous source kept with it and the added text: `source` is not read, which for a source made of the last one and
	// the added text would copy all of it.)
	const kept = previous.end;
	const end = ownString(
		added !== undefined && kept !== undefined && offset >= kept.start
			? kept.text.slice(offset - kept.start) + added
			: source.slice(offset),
	);
	lastEnd = { start: offset, text: end };
	const lexedEnd = lexTokens(end);
	if (hasLinkDefinitions(lexedEnd)) {
		return undefined;
	}
	const tokens = previous.tokens.slice(0, last);
	let length = offset;
	// A block lexed again to what it was lexed to before (while a reply streams in, all of them but the last, which grows)
	// is the token it was lexed to before: the lines rendered for that one are kept with it (renderBlock()), and a block
	// such as a list or a code block is not rendered and highlighted again for each chunk added after it. The same text
	// can be lexed to a token with other contents (a paragraph whose lines were added to it one by one keeps their
	// indentation in one case and not in the other), so the tokens are compared in full. The last token is always the
	// new one: trimPartialClosingFences() may change it.
	let shared = last;
	for (let i = 0; i < lexedEnd.length; i++) {
		let token = lexedEnd[i]!;
		const before = previous.tokens[last + i];
		if (
			fastPaths &&
			shared === last + i &&
			i < lexedEnd.length - 1 &&
			before !== undefined &&
			before.raw === token.raw &&
			sameTokens(before, token)
		) {
			token = before;
			shared++;
		}
		tokens.push(token);
		length += token.raw.length;
	}
	// (As in lexMarkdown(): the next time, each token has to begin where the ones before it add up to.)
	if (length !== source.length) {
		return undefined;
	}
	lexedFrom.set(tokens, { tokens: new WeakRef(previous.tokens), shared });
	return tokens;
}

/**
 * Lexes Markdown, reusing what was lexed before when `source` is a source lexed earlier or one with text added at its end
 * (a message streaming in), so that the cost of a new chunk does not grow with the length of the message.
 * A `complete` source (one that will not grow any more) is lexed in full instead of from a shorter one's tokens: lexing in
 * pieces can split a block that a full lex keeps whole ("2. Install" after a paragraph line, or `$$` math across blank
 * lines), and the tokens kept for a source are what it renders to from then on.
 */
function lexMarkdown(source: string, complete = false, extended?: ExtendedSource): Token[] {
	lastLexed = undefined;
	lastEnd = undefined;
	if (!markdownCaching) {
		const tokens = markdownParser.lexer(source);
		trimPartialClosingFences(tokens);
		return tokens;
	}
	// (`extended`: `source` is a source lexed before with `added` after it, as replaceTabs() made it; that one needs no
	// comparing with `source`, which would copy all of the text made of pieces.)
	const known = extended !== undefined && lexedMarkdown.includes(extended.from) ? extended.from : undefined;
	let previous = -1;
	for (let i = lexedMarkdown.length - 1; i >= 0; i--) {
		const entry = lexedMarkdown[i];
		if (entry.source.length === source.length && entry !== known && entry.source === source) {
			lexedMarkdown.splice(i, 1);
			if (complete && entry.pieced) {
				break;
			}
			lexedMarkdown.push(entry);
			lastLexed = entry;
			return entry.tokens;
		}
		if (
			!complete &&
			entry.resumable &&
			source.length > entry.source.length &&
			(previous < 0 || entry.source.length > lexedMarkdown[previous].source.length) &&
			(entry === known || startsWithText(source, entry.source))
		) {
			previous = i;
		}
	}

	let tokens =
		previous >= 0
			? lexAddedMarkdown(
					lexedMarkdown[previous],
					source,
					lexedMarkdown[previous] === known ? extended?.added : undefined,
				)
			: undefined;
	const pieced = tokens !== undefined;
	let resumable = pieced;
	if (!tokens) {
		tokens = lexTokens(source);
		if (complete) {
			// The text will not be lexed again: what its texts lexed to is not kept (up to ten times its size).
			lexedInlines = new Map();
		}
		let length = 0;
		for (const token of tokens) {
			length += token.raw.length;
		}
		// (The lexer rewrites line endings: only when the tokens add up to the source is it known where each begins.)
		resumable = !hasLinkDefinitions(tokens) && length === source.length;
	}
	trimPartialClosingFences(tokens);

	if (previous >= 0) {
		// The shorter source is not coming back.
		lexedMarkdown.splice(previous, 1);
	}
	lastLexed = { source, tokens, resumable, pieced, ...(pieced && lastEnd !== undefined ? { end: lastEnd } : {}) };
	lexedMarkdown.push(lastLexed);
	if (lexedMarkdown.length > LEXED_MARKDOWN_KEPT) {
		lexedMarkdown.shift();
	}
	return tokens;
}

/**
 * Texts whose tabs were replaced last, most recent last, what they became, and how that was lexed (lexMarkdown(), when it
 * was kept).
 */
const textsWithoutTabs: Array<{ text: string; replaced: string; lexed?: LexedMarkdown }> = [];
const TEXTS_WITHOUT_TABS_KEPT = 4;

/** A text that is `from`, a text rendered before, with `added` after it (Markdown.grown). */
interface GrownText {
	from: string;
	added: string;
}

/**
 * `text` with each tab replaced by three spaces. A message that is streaming in is rendered again each time it grows: what
 * was replaced in it the last time is kept, and only the text added since is gone through. When the text is that one with
 * text added, the result is the last result with the added text after it: `extended` says so (for lexMarkdown()), and
 * `remember` keeps what the result was lexed to. `grown`, when known, says which text that is, and what was added to it:
 * the text is then not compared with the ones kept. `tabs`, when known, says whether the text has a tab.
 */
function replaceTabs(
	text: string,
	grown?: GrownText,
	tabs?: boolean,
): {
	text: string;
	extended?: ExtendedSource;
	remember: (lexed: LexedMarkdown | undefined) => void;
} {
	const none = () => {};
	if (tabs === false || (tabs === undefined && !text.includes("\t"))) {
		return { text, remember: none };
	}
	if (!markdownCaching) {
		return { text: text.replace(/\t/g, "   "), remember: none };
	}
	let replaced: string | undefined;
	let extended: ExtendedSource | undefined;
	let grownFrom = -1;
	if (grown !== undefined) {
		for (let i = textsWithoutTabs.length - 1; i >= 0 && grownFrom < 0; i--) {
			if (textsWithoutTabs[i].text === grown.from) grownFrom = i;
		}
	}
	for (let i = textsWithoutTabs.length - 1; i >= 0 && replaced === undefined; i--) {
		const entry = textsWithoutTabs[i];
		if (grownFrom >= 0 ? i === grownFrom : startsWithText(text, entry.text)) {
			if (text.length === entry.text.length) {
				replaced = entry.replaced;
			} else {
				// (The added text as a string of its own: a part of `text` keeps all of `text` in memory, and the result keeps
				// the part, as the next result keeps this one: every chunk of a reply would keep the whole reply as it was.)
				const added = ownString(
					grownFrom >= 0 && grown !== undefined ? grown.added : text.slice(entry.text.length),
				).replace(/\t/g, "   ");
				replaced = entry.replaced + added;
				// (What the last result was lexed to was kept with it: its source is that result.)
				if (entry.lexed !== undefined) extended = { from: entry.lexed, added };
			}
			textsWithoutTabs.splice(i, 1);
		}
	}
	replaced ??= text.replace(/\t/g, "   ");
	const kept: { text: string; replaced: string; lexed?: LexedMarkdown } = { text, replaced };
	textsWithoutTabs.push(kept);
	if (textsWithoutTabs.length > TEXTS_WITHOUT_TABS_KEPT) {
		textsWithoutTabs.shift();
	}
	return {
		text: replaced,
		...(extended === undefined ? {} : { extended }),
		remember: (lexed) => {
			kept.lexed = lexed;
		},
	};
}

/** Everything a block's lines depend on besides its token and the type of the token after it. */
interface RenderSettings {
	generation: number;
	width: number;
	paddingX: number;
	theme: MarkdownTheme;
	color: DefaultTextStyle["color"];
	bgColor: DefaultTextStyle["bgColor"];
	bold: boolean | undefined;
	italic: boolean | undefined;
	strikethrough: boolean | undefined;
	underline: boolean | undefined;
	preserveOrderedListMarkers: boolean | undefined;
	preserveBackslashEscapes: boolean | undefined;
	renderLatex: boolean | undefined;
	capabilities: ReturnType<typeof getCapabilities>;
}

function sameSettings(a: RenderSettings, b: RenderSettings): boolean {
	return (
		a === b ||
		(a.generation === b.generation &&
			a.width === b.width &&
			a.paddingX === b.paddingX &&
			a.theme === b.theme &&
			a.color === b.color &&
			a.bgColor === b.bgColor &&
			a.bold === b.bold &&
			a.italic === b.italic &&
			a.strikethrough === b.strikethrough &&
			a.underline === b.underline &&
			a.preserveOrderedListMarkers === b.preserveOrderedListMarkers &&
			a.preserveBackslashEscapes === b.preserveBackslashEscapes &&
			a.renderLatex === b.renderLatex &&
			a.capabilities === b.capabilities)
	);
}

/** What a top-level block was rendered to, and everything that went into it besides the token. */
interface RenderedBlock {
	settings: RenderSettings;
	nextTokenType: string | undefined;
	lines: string[];
}

const renderedBlocks = new WeakMap<Token, RenderedBlock>();

/** What the top-level tokens of a source were rendered to: the lines of all the blocks, and where each block ends. */
interface RenderedTokens {
	settings: RenderSettings;
	lines: string[];
	/** `ends[i]`: the number of lines of the blocks up to and including block `i`. */
	ends: number[];
}

const renderedTokens = new WeakMap<Token[], RenderedTokens>();

let markdownCaching = true;

/** For tests: with caching off, every render lexes and renders all of its text, taking and keeping nothing. */
export function setMarkdownCachingForTest(enabled: boolean): void {
	markdownCaching = enabled;
}

/**
 * For tests: with this off, a source with text added is lexed again from its last two blocks always, and every block
 * lexed again is a new token (lexAddedMarkdown()), as before either was kept.
 */
export function setMarkdownFastPathsForTest(enabled: boolean): void {
	fastPaths = enabled;
}

/**
 * For tests: with this off, inline texts are lexed by marked's own lexer each time (InlineMemoLexer), and lines are
 * wrapped each time they are rendered (Markdown.wrapLine()).
 */
export function setMarkdownMemosForTest(enabled: boolean): void {
	memos = enabled;
}

/** For tests: with this off, marked's lheading rule runs at the start of every block it is tried at (mayStartSetextHeading()). */
export function setSetextGuardForTest(enabled: boolean): void {
	setextGuard = enabled;
}

/** For tests: lexes as render() does. */
export function lexMarkdownForTest(source: string, complete = false): Token[] {
	return lexMarkdown(source, complete);
}

/**
 * Default text styling for markdown content.
 * Applied to all text unless overridden by markdown formatting.
 */
export interface DefaultTextStyle {
	/** Foreground color function */
	color?: (text: string) => string;
	/** Background color function */
	bgColor?: (text: string) => string;
	/** Bold text */
	bold?: boolean;
	/** Italic text */
	italic?: boolean;
	/** Strikethrough text */
	strikethrough?: boolean;
	/** Underline text */
	underline?: boolean;
}

/**
 * Theme functions for markdown elements.
 * Each function takes text and returns styled text with ANSI codes.
 */
export interface MarkdownTheme {
	heading: (text: string) => string;
	link: (text: string) => string;
	linkUrl: (text: string) => string;
	code: (text: string) => string;
	codeBlock: (text: string) => string;
	codeBlockBorder: (text: string) => string;
	quote: (text: string) => string;
	quoteBorder: (text: string) => string;
	hr: (text: string) => string;
	listBullet: (text: string) => string;
	bold: (text: string) => string;
	italic: (text: string) => string;
	strikethrough: (text: string) => string;
	underline: (text: string) => string;
	highlightCode?: (code: string, lang?: string) => string[];
	/** Prefix applied to each rendered code block line (default: "  ") */
	codeBlockIndent?: string;
	/**
	 * Background for code blocks. When set, a block renders as shaded lines (language label first) instead of between
	 * literal ``` fence lines. It must end the background with SGR 49 (`\x1b[49m`), not a full reset (SGR 0): inside a Box
	 * or a Markdown with a background, the surrounding background is opened again after an SGR 49 only, so the rest of
	 * the row keeps it.
	 */
	codeBlockBg?: (text: string) => string;
}

export interface MarkdownOptions {
	/** Preserve source list markers instead of normalizing them. */
	preserveOrderedListMarkers?: boolean;
	/** Preserve source backslash escapes instead of normalizing escaped punctuation. */
	preserveBackslashEscapes?: boolean;
	/** Transform source Markdown before parsing, with the exact width available for content. */
	transform?: (markdown: string, availableWidth: number) => string;
	/** Render supported LaTeX math expressions as Unicode text (default: true). */
	renderLatex?: boolean;
	/**
	 * The text will not grow any more (a message that has finished streaming): it is lexed in full, not from the tokens of
	 * the shorter text it was streamed as.
	 */
	complete?: boolean;
}

interface InlineStyleContext {
	applyText: (text: string) => string;
	stylePrefix: string;
}

export class Markdown implements Component {
	private text: string;
	/** What the text given to setText() last was sanitised to (sanitizeTerminalTextFrom()). */
	private sanitized?: SanitizedTerminalText;
	/**
	 * The text when its tokens were last made (when the transform, if any, left it as it was), what it was lexed to when it
	 * was lexed as it is (it had no tabs to replace), and the text setText() added to it since, while each text set was the one before with more
	 * after it. A message streaming in is then lexed from that (replaceTabs()' `grown`, lexMarkdown()'s `extended`) without
	 * comparing it with the text before again: setText() did (sanitizeTerminalTextFrom()).
	 */
	private grown?: GrownText & { lexed?: LexedMarkdown };
	/**
	 * Whether the text (`text`, the text when this was found) has a character that is not white space, and a tab. setText()
	 * finds it for a text that is the one before with text added from the text added: a message streaming in is not read
	 * in full for each chunk (startsWithText()).
	 */
	private facts?: { text: string; visible: boolean; tabs: boolean };
	private paddingX: number; // Left/right padding
	private paddingY: number; // Top/bottom padding
	private defaultTextStyle?: DefaultTextStyle;
	private theme: MarkdownTheme;
	private options: MarkdownOptions;
	private defaultStylePrefix?: string;

	// Cache for rendered output
	private cachedText?: string;
	private cachedWidth?: number;
	private cachedLines?: string[];
	// Parsed tokens depend only on the source, so they survive theme and width invalidation. Held weakly: a token tree is
	// about ten times the size of its source, and every message of a long transcript keeps a Markdown component. The
	// tokens survive a burst of re-renders, such as a theme preview, and are collected afterwards.
	private cachedTokens?: WeakRef<{ source: string; tokens: Token[] }>;
	/**
	 * Lines wrapped by the last render that rendered a block, by width (wrapLine()), and those wrapped by this render so
	 * far. While a reply streams in, the block it ends with is rendered again for each chunk, and all its lines but the
	 * last few are the lines it had the time before.
	 */
	private wraps = new Map<number, Map<string, readonly string[]>>();
	private nextWraps = new Map<number, Map<string, readonly string[]>>();
	/**
	 * What renderLive() holds: the lines, the text and width they are for, the tokens and settings their blocks were
	 * rendered from and where each block's lines end (not counting the padding), and whether the lines are the empty line
	 * render() returns for an empty result.
	 */
	private live: {
		array: LiveLineArray;
		text?: string;
		width?: number;
		tokens?: Token[];
		settings?: RenderSettings;
		ends: number[];
		substituted: boolean;
	} = { array: new LiveLineArray(), ends: [], substituted: false };

	constructor(
		text: string,
		paddingX: number,
		paddingY: number,
		theme: MarkdownTheme,
		defaultTextStyle?: DefaultTextStyle,
		options?: MarkdownOptions,
	) {
		// (The text is a model's reply or someone else's: escape sequences in it would act on the terminal.)
		this.text = sanitizeTerminalText(text);
		this.paddingX = paddingX;
		this.paddingY = paddingY;
		this.theme = theme;
		this.defaultTextStyle = defaultTextStyle;
		this.options = options ? { ...options } : {};
	}

	setText(text: string): void {
		// (A reply streaming in is set again for each chunk: only what was added is sanitised.)
		const before = this.text;
		this.sanitized = sanitizeTerminalTextFrom(text, this.sanitized);
		this.text = this.sanitized.text;
		const added = this.sanitized.added;
		if (this.grown !== undefined) {
			if (added === undefined) this.grown = undefined;
			else this.grown.added += added;
		}
		// (`added` is what the text adds to the text before: the result for the previous text, which `before` is.)
		const facts = this.facts;
		this.facts =
			facts !== undefined && added !== undefined && facts.text === before
				? {
						text: this.text,
						visible: facts.visible || /\S/.test(added),
						tabs: facts.tabs || added.includes("\t"),
					}
				: undefined;
		this.cachedText = undefined;
		this.cachedWidth = undefined;
		this.cachedLines = undefined;
		reportRenderChange(this);
	}

	invalidate(): void {
		this.cachedText = undefined;
		this.cachedWidth = undefined;
		this.cachedLines = undefined;
		// What the theme's functions give may have changed: nothing rendered with them is good any more.
		invalidateRenderedMarkdown();
		reportRenderChange(this);
	}

	/** Everything the lines of a block rendered at `width` depend on besides its token and the next token's type. */
	private renderSettings(width: number): RenderSettings {
		const style = this.defaultTextStyle;
		return {
			generation: renderedMarkdownGeneration(),
			width,
			paddingX: this.paddingX,
			theme: this.theme,
			color: style?.color,
			bgColor: style?.bgColor,
			bold: style?.bold,
			italic: style?.italic,
			strikethrough: style?.strikethrough,
			underline: style?.underline,
			preserveOrderedListMarkers: this.options.preserveOrderedListMarkers,
			preserveBackslashEscapes: this.options.preserveBackslashEscapes,
			renderLatex: this.options.renderLatex,
			capabilities: getCapabilities(),
		};
	}

	/**
	 * wrapTextWithAnsi(line, width), from the lines wrapped by the render before this one when `line` is one of them
	 * (wraps). The result is shared: not to be modified.
	 */
	private wrapLine(line: string, width: number): readonly string[] {
		if (!markdownCaching || !memos) {
			return wrapTextWithAnsi(line, width);
		}
		let next = this.nextWraps.get(width);
		if (next === undefined) {
			next = new Map();
			this.nextWraps.set(width, next);
		}
		const wrapped = next.get(line) ?? this.wraps.get(width)?.get(line) ?? wrapTextWithAnsi(line, width);
		next.set(line, wrapped);
		return wrapped;
	}

	/**
	 * The lines of one top-level block: rendered, wrapped, with margins and background. Kept with the token, which stays the
	 * same object while the text before the last blocks of a message stays the same (lexMarkdown()).
	 */
	private renderBlock(
		token: Token,
		nextTokenType: string | undefined,
		settings: RenderSettings,
		contentWidth: number,
	): string[] {
		const known = markdownCaching ? renderedBlocks.get(token) : undefined;
		if (known && known.nextTokenType === nextTokenType && sameSettings(known.settings, settings)) {
			return known.lines;
		}

		const width = settings.width;
		const leftMargin = " ".repeat(this.paddingX);
		const rightMargin = " ".repeat(this.paddingX);
		const bgFn = this.defaultTextStyle?.bgColor;
		const lines: string[] = [];
		const add = (line: string) => {
			if (isImageLine(line)) {
				lines.push(line);
				return;
			}
			const lineWithMargins = leftMargin + line + rightMargin;
			if (bgFn) {
				lines.push(applyBackgroundToLine(lineWithMargins, width, bgFn));
			} else {
				// No background - just pad to width
				const visibleLen = visibleWidth(lineWithMargins);
				const paddingNeeded = Math.max(0, width - visibleLen);
				lines.push(lineWithMargins + " ".repeat(paddingNeeded));
			}
		};
		for (const line of this.renderToken(token, contentWidth, nextTokenType)) {
			// Wrap lines (NO padding, NO background yet)
			if (isImageLine(line)) {
				add(line);
			} else {
				for (const wrappedLine of this.wrapLine(line, contentWidth)) {
					add(wrappedLine);
				}
			}
		}
		flattenLines(lines);
		if (markdownCaching) {
			renderedBlocks.set(token, { settings, nextTokenType, lines });
		}
		return lines;
	}

	/**
	 * The lines of all the top-level blocks of `tokens`, joined. Tokens lexed from those of a shorter source (a message
	 * streaming in) take the lines of the blocks they have in common with it from what that source rendered to, so that the
	 * cost of a new chunk does not grow with the length of the message: only the blocks lexed again are gone through.
	 * The result is shared: not to be modified.
	 */
	private renderTokens(tokens: Token[], settings: RenderSettings, contentWidth: number): string[] {
		const known = renderedTokens.get(tokens);
		if (known && sameSettings(known.settings, settings)) {
			return known.lines;
		}
		let lines: string[] = [];
		let ends: number[] = [];
		let reused = 0;
		const from = lexedFrom.get(tokens);
		const fromTokens = from?.tokens.deref();
		const fromRendered = fromTokens ? renderedTokens.get(fromTokens) : undefined;
		if (from && fromTokens && fromRendered && sameSettings(fromRendered.settings, settings)) {
			reused = from.shared;
			// (The lines of a block depend on the type of the block after it.)
			if (reused > 0 && tokens[reused]?.type !== fromTokens[reused]?.type) {
				reused--;
			}
			if (reused > 0) {
				lines = fromRendered.lines.slice(0, fromRendered.ends[reused - 1]);
				ends = fromRendered.ends.slice(0, reused);
			}
		}
		for (let i = reused; i < tokens.length; i++) {
			for (const line of this.renderBlock(tokens[i], tokens[i + 1]?.type, settings, contentWidth)) {
				lines.push(line);
			}
			ends.push(lines.length);
		}
		renderedTokens.set(tokens, { settings, lines, ends });
		return lines;
	}

	/** The tokens of the text at `width` (after the transform and tab replacement), or undefined when it has no text. */
	private tokensFor(width: number): { tokens: Token[]; contentWidth: number } | undefined {
		// Calculate available width for content (subtract horizontal padding)
		const contentWidth = Math.max(1, width - this.paddingX * 2);
		const text = this.options.transform?.(this.text, contentWidth) ?? this.text;
		// (Only for the text as it is: a transform, such as one an extension adds, may give another.)
		const transformed = text !== this.text;
		const facts = transformed ? undefined : this.textFacts();

		// Don't render anything if there's no actual text
		if (!text || !(facts?.visible ?? /\S/.test(text))) {
			return undefined;
		}

		// Replace tabs with 3 spaces for consistent rendering
		const grown = !transformed && this.grown?.added ? this.grown : undefined;
		const normalized = replaceTabs(text, grown, facts?.tabs);
		const normalizedText = normalized.text;

		// Parse markdown to HTML-like tokens
		const cached = this.cachedTokens?.deref();
		let tokens =
			cached?.source.length === normalizedText.length && cached.source === normalizedText
				? cached.tokens
				: undefined;
		if (!tokens) {
			// (The text as it is, without tabs: lexed from what it was lexed to before, with what setText() added since.)
			const asIs = normalizedText === this.text;
			const extended =
				normalized.extended ??
				(asIs && grown?.lexed !== undefined ? { from: grown.lexed, added: grown.added } : undefined);
			tokens = lexMarkdown(normalizedText, this.options.complete, extended);
			const lexed = lastLexed?.tokens === tokens ? lastLexed : undefined;
			normalized.remember(lexed);
			this.grown = transformed
				? undefined
				: { from: this.text, added: "", ...(asIs && lexed !== undefined ? { lexed } : {}) };
			this.cachedTokens = new WeakRef({ source: normalizedText, tokens });
		}
		return { tokens, contentWidth };
	}

	/** Whether the text has a character that is not white space, and a tab (facts). */
	private textFacts(): { text: string; visible: boolean; tabs: boolean } {
		const text = this.text;
		let facts = this.facts;
		if (facts?.text !== text) {
			facts = { text, visible: /\S/.test(text), tabs: text.includes("\t") };
			this.facts = facts;
		}
		return facts;
	}

	/** The empty lines above and below the content. */
	private paddingLines(width: number): string[] {
		const bgFn = this.defaultTextStyle?.bgColor;
		const emptyLine = " ".repeat(width);
		const emptyLines: string[] = [];
		for (let i = 0; i < this.paddingY; i++) {
			const line = bgFn ? applyBackgroundToLine(emptyLine, width, bgFn) : emptyLine;
			emptyLines.push(line);
		}
		// (The lines of the blocks are flat already: renderBlock().)
		flattenLines(emptyLines);
		return emptyLines;
	}

	/** Keeps the lines wrapped by this render, if it rendered any blocks (wrapLine()). */
	private keepWraps(): void {
		if (this.nextWraps.size > 0) {
			this.wraps = this.nextWraps;
			this.nextWraps = new Map();
		}
	}

	render(width: number): string[] {
		// Check cache
		if (this.cachedLines && this.cachedText === this.text && this.cachedWidth === width) {
			return this.cachedLines;
		}
		// (Rendered last as live lines, for the same text and width: what that render would have kept, with nothing in its
		// place for an empty result.)
		const rendered = this.cachedText === this.text && this.cachedWidth === width;

		const prepared = this.tokensFor(width);
		if (!prepared) {
			const result: string[] = [];
			// Update cache
			this.cachedText = this.text;
			this.cachedWidth = width;
			this.cachedLines = result;
			return result;
		}
		const { tokens, contentWidth } = prepared;

		// Convert tokens to styled terminal output, wrapped, with margins and background: block by block, each from what it
		// was rendered to the last time when nothing it depends on has changed.
		const settings = this.renderSettings(width);
		let contentLines: string[];
		if (markdownCaching) {
			contentLines = this.renderTokens(tokens, settings, contentWidth);
		} else {
			contentLines = [];
			for (let i = 0; i < tokens.length; i++) {
				for (const line of this.renderBlock(tokens[i], tokens[i + 1]?.type, settings, contentWidth)) {
					contentLines.push(line);
				}
			}
		}

		// Combine top padding, content, and bottom padding
		const emptyLines = this.paddingLines(width);
		const result = emptyLines.concat(contentLines, emptyLines);
		this.keepWraps();

		// Update cache
		this.cachedText = this.text;
		this.cachedWidth = width;
		this.cachedLines = result;

		if (result.length > 0 || rendered) return result;
		// (The next render returns none: a change, for containers that render this again only when told.)
		reportRenderChange(this);
		return [""];
	}

	/**
	 * render()'s lines as LiveLines (renderLiveLines()): changed in place from the first line of the first block rendered
	 * again. While a reply streams in, its text is lexed from the tokens of the text before (lexMarkdown()), and the lines
	 * of the blocks the two have in common are the lines of this component's last render: only the blocks lexed again are
	 * rendered and added, so a chunk does not cost time in proportion to the length of the reply. What it holds after each
	 * call is what render() returns for the call.
	 */
	renderLive(width: number): LiveLines {
		const live = this.live;
		const lines = live.array;
		const rendered = this.cachedText === this.text && this.cachedWidth === width;
		if (rendered && live.text === this.text && live.width === width) {
			// What render() returns when nothing changed: the result kept, not the line in place of an empty one.
			if (live.substituted) {
				lines.edit(0);
				live.substituted = false;
			}
			return lines;
		}

		const prepared = this.tokensFor(width);
		live.text = this.text;
		live.width = width;
		this.cachedText = this.text;
		this.cachedWidth = width;
		this.cachedLines = undefined;
		if (!prepared) {
			if (lines.lines.length > 0) lines.edit(0);
			live.tokens = undefined;
			live.settings = undefined;
			live.substituted = false;
			return lines;
		}
		const { tokens, contentWidth } = prepared;
		const settings = this.renderSettings(width);
		const before = live.tokens;
		const sameContent =
			markdownCaching &&
			before !== undefined &&
			!live.substituted &&
			live.settings !== undefined &&
			sameSettings(live.settings, settings);
		if (!(sameContent && before === tokens)) {
			let reused = 0;
			const from = sameContent ? lexedFrom.get(tokens) : undefined;
			if (from && before !== undefined && from.tokens.deref() === before) {
				reused = from.shared;
				// (The lines of a block depend on the type of the block after it.)
				if (reused > 0 && tokens[reused]?.type !== before[reused]?.type) {
					reused--;
				}
			}
			const padding = this.paddingLines(width);
			const ends = live.ends;
			if (reused > 0) {
				lines.edit(padding.length + ends[reused - 1]!);
				ends.length = reused;
			} else {
				lines.edit(0);
				ends.length = 0;
				for (const line of padding) lines.lines.push(line);
			}
			let contentLength = reused > 0 ? ends[reused - 1]! : 0;
			for (let i = reused; i < tokens.length; i++) {
				const blockLines = this.renderBlock(tokens[i], tokens[i + 1]?.type, settings, contentWidth);
				for (const line of blockLines) lines.lines.push(line);
				contentLength += blockLines.length;
				ends.push(contentLength);
			}
			for (const line of padding) lines.lines.push(line);
			this.keepWraps();
		}
		live.tokens = tokens;
		live.settings = settings;
		live.substituted = false;
		if (lines.lines.length === 0 && !rendered) {
			// (As render(): an empty result is returned as one empty line, and kept as none.)
			lines.edit(0);
			lines.lines.push("");
			live.substituted = true;
			// (The next render returns none: a change, for containers that render this again only when told.)
			reportRenderChange(this);
		}
		return lines;
	}

	/**
	 * Apply default text style to a string.
	 * This is the base styling applied to all text content.
	 * NOTE: Background color is NOT applied here - it's applied at the padding stage
	 * to ensure it extends to the full line width.
	 */
	private applyDefaultStyle(text: string): string {
		if (!this.defaultTextStyle) {
			return text;
		}

		let styled = text;

		// Apply foreground color (NOT background - that's applied at padding stage)
		if (this.defaultTextStyle.color) {
			styled = this.defaultTextStyle.color(styled);
		}

		// Apply text decorations using this.theme
		if (this.defaultTextStyle.bold) {
			styled = this.theme.bold(styled);
		}
		if (this.defaultTextStyle.italic) {
			styled = this.theme.italic(styled);
		}
		if (this.defaultTextStyle.strikethrough) {
			styled = this.theme.strikethrough(styled);
		}
		if (this.defaultTextStyle.underline) {
			styled = this.theme.underline(styled);
		}

		return styled;
	}

	private getDefaultStylePrefix(): string {
		if (!this.defaultTextStyle) {
			return "";
		}

		if (this.defaultStylePrefix !== undefined) {
			return this.defaultStylePrefix;
		}

		const sentinel = "\u0000";
		let styled = sentinel;

		if (this.defaultTextStyle.color) {
			styled = this.defaultTextStyle.color(styled);
		}

		if (this.defaultTextStyle.bold) {
			styled = this.theme.bold(styled);
		}
		if (this.defaultTextStyle.italic) {
			styled = this.theme.italic(styled);
		}
		if (this.defaultTextStyle.strikethrough) {
			styled = this.theme.strikethrough(styled);
		}
		if (this.defaultTextStyle.underline) {
			styled = this.theme.underline(styled);
		}

		const sentinelIndex = styled.indexOf(sentinel);
		this.defaultStylePrefix = sentinelIndex >= 0 ? styled.slice(0, sentinelIndex) : "";
		return this.defaultStylePrefix;
	}

	private getStylePrefix(styleFn: (text: string) => string): string {
		const sentinel = "\u0000";
		const styled = styleFn(sentinel);
		const sentinelIndex = styled.indexOf(sentinel);
		return sentinelIndex >= 0 ? styled.slice(0, sentinelIndex) : "";
	}

	private getDefaultInlineStyleContext(): InlineStyleContext {
		return {
			applyText: (text: string) => this.applyDefaultStyle(text),
			stylePrefix: this.getDefaultStylePrefix(),
		};
	}

	private renderToken(
		token: Token,
		width: number,
		nextTokenType?: string,
		styleContext?: InlineStyleContext,
	): string[] {
		const lines: string[] = [];

		switch (token.type) {
			case "heading": {
				const headingLevel = token.depth;
				const headingPrefix = `${"#".repeat(headingLevel)} `;

				// Build a heading-specific style context so inline tokens (codespan, bold, etc.)
				// restore heading styling after their own ANSI resets instead of falling back to
				// the default text style.
				let headingStyleFn: (text: string) => string;
				if (headingLevel === 1) {
					headingStyleFn = (text: string) => this.theme.heading(this.theme.bold(this.theme.underline(text)));
				} else {
					headingStyleFn = (text: string) => this.theme.heading(this.theme.bold(text));
				}

				const headingStyleContext: InlineStyleContext = {
					applyText: headingStyleFn,
					stylePrefix: this.getStylePrefix(headingStyleFn),
				};

				const headingText = this.renderInlineTokens(token.tokens || [], headingStyleContext);
				const styledHeading = headingLevel >= 3 ? headingStyleFn(headingPrefix) + headingText : headingText;
				lines.push(styledHeading);
				if (nextTokenType && nextTokenType !== "space") {
					lines.push(""); // Add spacing after headings (unless space token follows)
				}
				break;
			}

			case "paragraph": {
				const paragraphText = this.renderInlineTokens(token.tokens || [], styleContext);
				lines.push(paragraphText);
				// Don't add spacing if next token is space or list
				if (nextTokenType && nextTokenType !== "list" && nextTokenType !== "space") {
					lines.push("");
				}
				break;
			}

			case "text":
				lines.push(this.renderInlineTokens([token], styleContext));
				break;

			case "latexBlock": {
				const latexToken = token as LatexToken;
				const rendered =
					!latexToken.pending && this.options.renderLatex !== false
						? (renderLatex(latexToken.text, { display: true }) ?? latexToken.raw.trim())
						: latexToken.raw.trim();
				for (const line of rendered.split("\n")) {
					lines.push(this.applyDefaultStyle(line));
				}
				if (nextTokenType && nextTokenType !== "space") {
					lines.push("");
				}
				break;
			}

			case "code": {
				const indent = this.theme.codeBlockIndent ?? "  ";
				const codeLines: string[] = this.theme.highlightCode
					? this.theme.highlightCode(token.text, token.lang)
					: token.text.split("\n").map((codeLine: string) => this.theme.codeBlock(codeLine));
				const shade = this.theme.codeBlockBg;
				if (shade) {
					// Each line is shaded to the full width, so the block is one rectangle (and keeps its shape while a reply
					// streams in). Wrapped here, not by renderBlock(), so continuation lines keep the block's indent.
					const fill = (line: string) => line + " ".repeat(Math.max(0, width - visibleWidth(line)));
					if (token.lang) {
						for (const label of this.wrapLine(this.theme.codeBlockBorder(token.lang), width)) {
							lines.push(shade(fill(label)));
						}
					}
					const codeWidth = Math.max(1, width - visibleWidth(indent));
					// (A reset inside a line, from the highlighter or the code itself, would end the shading there: the
					// background is opened again after it. Not a block on the terminal's own background, SGR 49: that is what a
					// reset leaves in effect, and an SGR 49 more would read as the end of the block in a panel.)
					let shadeOpen: string | undefined;
					for (const codeLine of codeLines) {
						for (const wrapped of this.wrapLine(codeLine, codeWidth)) {
							let line = `${indent}${wrapped}`;
							if (line.includes("\x1b[")) {
								shadeOpen ??= shade("\0").split("\0")[0]!;
								if (shadeOpen !== "\x1b[49m") {
									const open = shadeOpen;
									line = line.replace(SGR_SEQUENCE_ALL, (sequence: string, params: string) =>
										endsBackground(params) ? sequence + open : sequence,
									);
								}
							}
							lines.push(shade(fill(line)));
						}
					}
				} else {
					lines.push(this.theme.codeBlockBorder(`\`\`\`${token.lang || ""}`));
					for (const codeLine of codeLines) {
						lines.push(`${indent}${codeLine}`);
					}
					lines.push(this.theme.codeBlockBorder("```"));
				}
				if (nextTokenType && nextTokenType !== "space") {
					lines.push(""); // Add spacing after code blocks (unless space token follows)
				}
				break;
			}

			case "list": {
				const listLines = this.renderList(token as Tokens.List, 0, width, styleContext);
				lines.push(...listLines);
				// Don't add spacing after lists if a space token follows
				// (the space token will handle it)
				break;
			}

			case "table": {
				const tableLines = this.renderTable(token as Tokens.Table, width, nextTokenType, styleContext);
				lines.push(...tableLines);
				break;
			}

			case "blockquote": {
				const quoteStyle = (text: string) => this.theme.quote(this.theme.italic(text));
				const quoteStylePrefix = this.getStylePrefix(quoteStyle);
				const applyQuoteStyle = (line: string): string => {
					if (!quoteStylePrefix) {
						return quoteStyle(line);
					}
					const lineWithReappliedStyle = line.replace(/\x1b\[0m/g, `\x1b[0m${quoteStylePrefix}`);
					return quoteStyle(lineWithReappliedStyle);
				};

				// With no room for another border ("│ " plus at least one content column), render the
				// nested content without one. Otherwise each extra level re-wraps every child line at
				// width 1 and the output grows exponentially with nesting depth.
				if (width < 3) {
					const quoteTokens = token.tokens || [];
					for (let i = 0; i < quoteTokens.length; i++) {
						for (const line of this.renderToken(quoteTokens[i], width, quoteTokens[i + 1]?.type, styleContext)) {
							lines.push(line);
						}
					}
					break;
				}

				// Calculate available width for quote content (subtract border "│ " = 2 chars)
				const quoteContentWidth = width - 2;

				// Blockquotes contain block-level tokens (paragraph, list, code, etc.), so render
				// children with renderToken() instead of renderInlineTokens().
				// Default message style should not apply inside blockquotes.
				const quoteInlineStyleContext: InlineStyleContext = {
					applyText: (text: string) => text,
					stylePrefix: quoteStylePrefix,
				};
				const quoteTokens = token.tokens || [];
				const renderedQuoteLines: string[] = [];
				for (let i = 0; i < quoteTokens.length; i++) {
					const quoteToken = quoteTokens[i];
					const nextQuoteToken = quoteTokens[i + 1];
					for (const line of this.renderToken(
						quoteToken,
						quoteContentWidth,
						nextQuoteToken?.type,
						quoteInlineStyleContext,
					)) {
						renderedQuoteLines.push(line);
					}
				}

				// Avoid rendering an extra empty quote line before the outer blockquote spacing.
				while (renderedQuoteLines.length > 0 && renderedQuoteLines[renderedQuoteLines.length - 1] === "") {
					renderedQuoteLines.pop();
				}

				for (const quoteLine of renderedQuoteLines) {
					const styledLine = applyQuoteStyle(quoteLine);
					const wrappedLines = this.wrapLine(styledLine, quoteContentWidth);
					for (const wrappedLine of wrappedLines) {
						lines.push(this.theme.quoteBorder("│ ") + wrappedLine);
					}
				}
				if (nextTokenType && nextTokenType !== "space") {
					lines.push(""); // Add spacing after blockquotes (unless space token follows)
				}
				break;
			}

			case "hr":
				lines.push(this.theme.hr("─".repeat(Math.min(width, 80))));
				if (nextTokenType && nextTokenType !== "space") {
					lines.push(""); // Add spacing after horizontal rules (unless space token follows)
				}
				break;

			case "html":
				// Render HTML as plain text (escaped for terminal)
				if ("raw" in token && typeof token.raw === "string") {
					lines.push(this.applyDefaultStyle(token.raw.trim()));
				}
				break;

			case "space":
				// Space tokens represent blank lines in markdown
				lines.push("");
				break;

			default:
				// Handle any other token types as plain text
				if ("text" in token && typeof token.text === "string") {
					lines.push(token.text);
				}
		}

		return lines;
	}

	private renderInlineTokens(tokens: Token[], styleContext?: InlineStyleContext): string {
		let result = "";
		const resolvedStyleContext = styleContext ?? this.getDefaultInlineStyleContext();
		const { applyText, stylePrefix } = resolvedStyleContext;
		const applyTextWithNewlines = (text: string): string => {
			const segments: string[] = text.split("\n");
			return segments.map((segment: string) => applyText(segment)).join("\n");
		};

		for (const token of tokens) {
			switch (token.type) {
				case "latex": {
					const latexToken = token as LatexToken;
					const rendered =
						!latexToken.pending && this.options.renderLatex !== false
							? (renderLatex(latexToken.text) ?? latexToken.raw)
							: latexToken.raw;
					result += applyTextWithNewlines(rendered);
					break;
				}

				case "escape":
					result += applyTextWithNewlines(this.options.preserveBackslashEscapes ? token.raw : token.text);
					break;

				case "text":
					// Text tokens in list items can have nested tokens for inline formatting
					if (token.tokens && token.tokens.length > 0) {
						result += this.renderInlineTokens(token.tokens, resolvedStyleContext);
					} else {
						result += applyTextWithNewlines(token.text);
					}
					break;

				case "paragraph":
					// Paragraph tokens contain nested inline tokens
					result += this.renderInlineTokens(token.tokens || [], resolvedStyleContext);
					break;

				case "strong": {
					const boldContent = this.renderInlineTokens(token.tokens || [], resolvedStyleContext);
					result += this.theme.bold(boldContent) + stylePrefix;
					break;
				}

				case "em": {
					const italicContent = this.renderInlineTokens(token.tokens || [], resolvedStyleContext);
					result += this.theme.italic(italicContent) + stylePrefix;
					break;
				}

				case "codespan":
					result += this.theme.code(token.text) + stylePrefix;
					break;

				case "link": {
					const linkText = this.renderInlineTokens(token.tokens || [], resolvedStyleContext);
					const styledLink = this.theme.link(this.theme.underline(linkText));
					if (getCapabilities().hyperlinks) {
						// OSC 8: render as a clickable hyperlink. The URL is not printed inline,
						// so we always show only the link text regardless of whether it matches href.
						// Only a web or mail link is clickable: a click on a local path or another scheme (file:, an app's
						// handler, or a target read as an option by `open`) could run a program instead of showing a page.
						result += (isWebLink(token.href) ? hyperlink(styledLink, token.href) : styledLink) + stylePrefix;
					} else {
						// Fallback: print URL in parentheses when text differs from href.
						// Compare raw token.text (not styled) against href for the equality check.
						// For mailto: links strip the prefix (autolinked emails use text="foo@bar.com"
						// but href="mailto:foo@bar.com").
						const hrefForComparison = token.href.startsWith("mailto:") ? token.href.slice(7) : token.href;
						if (token.text === token.href || token.text === hrefForComparison) {
							result += styledLink + stylePrefix;
						} else {
							result += styledLink + this.theme.linkUrl(` (${token.href})`) + stylePrefix;
						}
					}
					break;
				}

				case "br":
					result += "\n";
					break;

				case "del": {
					const delContent = this.renderInlineTokens(token.tokens || [], resolvedStyleContext);
					result += this.theme.strikethrough(delContent) + stylePrefix;
					break;
				}

				case "html":
					// Render inline HTML as plain text
					if ("raw" in token && typeof token.raw === "string") {
						result += applyTextWithNewlines(token.raw);
					}
					break;

				default:
					// Handle any other inline token types as plain text
					if ("text" in token && typeof token.text === "string") {
						result += applyTextWithNewlines(token.text);
					}
			}
		}

		while (stylePrefix && result.endsWith(stylePrefix)) {
			result = result.slice(0, -stylePrefix.length);
		}

		return result;
	}

	private getOrderedListMarker(item: Tokens.ListItem): string | undefined {
		const match = /^(?: {0,3})(\d{1,9}[.)])[ \t]+/.exec(item.raw);
		return match ? `${match[1]} ` : undefined;
	}

	private getUnorderedListMarker(item: Tokens.ListItem): string | undefined {
		const match = /^(?: {0,3})([-+*])(?:[ \t]+|(?=\r?\n|$))/.exec(item.raw);
		return match ? `${match[1]} ` : undefined;
	}

	/**
	 * Render a list with proper nesting support
	 */
	private renderList(token: Tokens.List, depth: number, width: number, styleContext?: InlineStyleContext): string[] {
		const lines: string[] = [];
		const indent = "    ".repeat(depth);
		// Use the list's start property (defaults to 1 for ordered lists)
		const startNumber = typeof token.start === "number" ? token.start : 1;

		for (let i = 0; i < token.items.length; i++) {
			const item = token.items[i];
			const isLastItem = i === token.items.length - 1;
			const bullet = token.ordered
				? this.options.preserveOrderedListMarkers
					? (this.getOrderedListMarker(item) ?? `${startNumber + i}. `)
					: `${startNumber + i}. `
				: this.options.preserveOrderedListMarkers
					? (this.getUnorderedListMarker(item) ?? "- ")
					: "- ";
			const taskMarker = item.task ? `[${item.checked ? "x" : " "}] ` : "";
			const marker = bullet + taskMarker;
			const firstPrefix = indent + this.theme.listBullet(marker);
			const continuationPrefix = indent + " ".repeat(visibleWidth(marker));
			const itemWidth = Math.max(1, width - visibleWidth(firstPrefix));
			let renderedAnyLine = false;

			for (const itemToken of item.tokens) {
				if (itemToken.type === "list") {
					lines.push(...this.renderList(itemToken as Tokens.List, depth + 1, width, styleContext));
					renderedAnyLine = true;
					continue;
				}

				const itemLines = this.renderToken(itemToken, itemWidth, undefined, styleContext);
				for (const line of itemLines) {
					for (const wrappedLine of this.wrapLine(line, itemWidth)) {
						const linePrefix = renderedAnyLine ? continuationPrefix : firstPrefix;
						lines.push(linePrefix + wrappedLine);
						renderedAnyLine = true;
					}
				}
			}

			if (!renderedAnyLine) {
				lines.push(firstPrefix);
			}

			if (token.loose && !isLastItem) {
				lines.push("");
			}
		}

		return lines;
	}

	/**
	 * Get the visible width of the longest word in a string.
	 */
	private getLongestWordWidth(text: string, maxWidth?: number): number {
		const words = text.split(/\s+/).filter((word) => word.length > 0);
		let longest = 0;
		for (const word of words) {
			longest = Math.max(longest, visibleWidth(word));
		}
		if (maxWidth === undefined) {
			return longest;
		}
		return Math.min(longest, maxWidth);
	}

	/**
	 * Wrap a table cell to fit into a column.
	 *
	 * Delegates to wrapTextWithAnsi() so ANSI codes + long tokens are handled
	 * consistently with the rest of the renderer.
	 */
	private wrapCellText(text: string, maxWidth: number, stylePrefix = ""): string[] {
		const lines = wrapTextWithAnsi(text, Math.max(1, maxWidth));
		return lines.map((line, index) => {
			// Reset text styles after each non-final fragment, then restore the surrounding style before padding and borders.
			const styleReset = index < lines.length - 1 ? "\x1b[22;23;24;25;27;28;29;39m" : "";
			return `${line}${styleReset}${stylePrefix}`;
		});
	}

	/**
	 * Render a table with width-aware cell wrapping.
	 * Cells that don't fit are wrapped to multiple lines.
	 */
	private renderTable(
		token: Tokens.Table,
		availableWidth: number,
		nextTokenType?: string,
		styleContext?: InlineStyleContext,
	): string[] {
		const lines: string[] = [];
		const numCols = token.header.length;

		if (numCols === 0) {
			return lines;
		}

		// Calculate border overhead: "│ " + (n-1) * " │ " + " │"
		// = 2 + (n-1) * 3 + 2 = 3n + 1
		const borderOverhead = 3 * numCols + 1;
		const availableForCells = availableWidth - borderOverhead;
		if (availableForCells < numCols) {
			// Too narrow to render a stable table. Fall back to raw markdown.
			const fallbackLines = token.raw ? wrapTextWithAnsi(token.raw, availableWidth) : [];
			if (nextTokenType && nextTokenType !== "space") {
				fallbackLines.push("");
			}
			return fallbackLines;
		}

		const maxUnbrokenWordWidth = 30;

		// Calculate natural column widths (what each column needs without constraints)
		const naturalWidths: number[] = [];
		const minWordWidths: number[] = [];
		for (let i = 0; i < numCols; i++) {
			const headerText = this.renderInlineTokens(token.header[i].tokens || [], styleContext);
			naturalWidths[i] = visibleWidth(headerText);
			minWordWidths[i] = Math.max(1, this.getLongestWordWidth(headerText, maxUnbrokenWordWidth));
		}
		for (const row of token.rows) {
			for (let i = 0; i < row.length; i++) {
				const cellText = this.renderInlineTokens(row[i].tokens || [], styleContext);
				naturalWidths[i] = Math.max(naturalWidths[i] || 0, visibleWidth(cellText));
				minWordWidths[i] = Math.max(
					minWordWidths[i] || 1,
					this.getLongestWordWidth(cellText, maxUnbrokenWordWidth),
				);
			}
		}

		let minColumnWidths = minWordWidths;
		let minCellsWidth = minColumnWidths.reduce((a, b) => a + b, 0);

		if (minCellsWidth > availableForCells) {
			minColumnWidths = new Array(numCols).fill(1);
			const remaining = availableForCells - numCols;

			if (remaining > 0) {
				const totalWeight = minWordWidths.reduce((total, width) => total + Math.max(0, width - 1), 0);
				const growth = minWordWidths.map((width) => {
					const weight = Math.max(0, width - 1);
					return totalWeight > 0 ? Math.floor((weight / totalWeight) * remaining) : 0;
				});

				for (let i = 0; i < numCols; i++) {
					minColumnWidths[i] += growth[i] ?? 0;
				}

				const allocated = growth.reduce((total, width) => total + width, 0);
				let leftover = remaining - allocated;
				for (let i = 0; leftover > 0 && i < numCols; i++) {
					minColumnWidths[i]++;
					leftover--;
				}
			}

			minCellsWidth = minColumnWidths.reduce((a, b) => a + b, 0);
		}

		// Calculate column widths that fit within available width
		const totalNaturalWidth = naturalWidths.reduce((a, b) => a + b, 0) + borderOverhead;
		let columnWidths: number[];

		if (totalNaturalWidth <= availableWidth) {
			// Everything fits naturally
			columnWidths = naturalWidths.map((width, index) => Math.max(width, minColumnWidths[index]));
		} else {
			// Need to shrink columns to fit
			const totalGrowPotential = naturalWidths.reduce((total, width, index) => {
				return total + Math.max(0, width - minColumnWidths[index]);
			}, 0);
			const extraWidth = Math.max(0, availableForCells - minCellsWidth);
			columnWidths = minColumnWidths.map((minWidth, index) => {
				const naturalWidth = naturalWidths[index];
				const minWidthDelta = Math.max(0, naturalWidth - minWidth);
				let grow = 0;
				if (totalGrowPotential > 0) {
					grow = Math.floor((minWidthDelta / totalGrowPotential) * extraWidth);
				}
				return minWidth + grow;
			});

			// Adjust for rounding errors - distribute remaining space
			const allocated = columnWidths.reduce((a, b) => a + b, 0);
			let remaining = availableForCells - allocated;
			while (remaining > 0) {
				let grew = false;
				for (let i = 0; i < numCols && remaining > 0; i++) {
					if (columnWidths[i] < naturalWidths[i]) {
						columnWidths[i]++;
						remaining--;
						grew = true;
					}
				}
				if (!grew) {
					break;
				}
			}
		}

		// Render top border
		const topBorderCells = columnWidths.map((w) => "─".repeat(w));
		lines.push(`┌─${topBorderCells.join("─┬─")}─┐`);

		// Render header with wrapping
		const headerCellLines: string[][] = token.header.map((cell, i) => {
			const text = this.renderInlineTokens(cell.tokens || [], styleContext);
			return this.wrapCellText(text, columnWidths[i], styleContext?.stylePrefix);
		});
		const headerLineCount = Math.max(...headerCellLines.map((c) => c.length));

		for (let lineIdx = 0; lineIdx < headerLineCount; lineIdx++) {
			const rowParts = headerCellLines.map((cellLines, colIdx) => {
				const text = cellLines[lineIdx] || "";
				const padded = text + " ".repeat(Math.max(0, columnWidths[colIdx] - visibleWidth(text)));
				return this.theme.bold(padded);
			});
			lines.push(`│ ${rowParts.join(" │ ")} │`);
		}

		// Render separator
		const separatorCells = columnWidths.map((w) => "─".repeat(w));
		const separatorLine = `├─${separatorCells.join("─┼─")}─┤`;
		lines.push(separatorLine);

		// Render rows with wrapping
		for (let rowIndex = 0; rowIndex < token.rows.length; rowIndex++) {
			const row = token.rows[rowIndex];
			const rowCellLines: string[][] = row.map((cell, i) => {
				const text = this.renderInlineTokens(cell.tokens || [], styleContext);
				return this.wrapCellText(text, columnWidths[i], styleContext?.stylePrefix);
			});
			const rowLineCount = Math.max(...rowCellLines.map((c) => c.length));

			for (let lineIdx = 0; lineIdx < rowLineCount; lineIdx++) {
				const rowParts = rowCellLines.map((cellLines, colIdx) => {
					const text = cellLines[lineIdx] || "";
					return text + " ".repeat(Math.max(0, columnWidths[colIdx] - visibleWidth(text)));
				});
				lines.push(`│ ${rowParts.join(" │ ")} │`);
			}

			if (rowIndex < token.rows.length - 1) {
				lines.push(separatorLine);
			}
		}

		// Render bottom border
		const bottomBorderCells = columnWidths.map((w) => "─".repeat(w));
		lines.push(`└─${bottomBorderCells.join("─┴─")}─┘`);

		if (nextTokenType && nextTokenType !== "space") {
			lines.push(""); // Add spacing after table
		}
		return lines;
	}
}

markStableRender(Markdown);
markReportsChanges(Markdown);
markLiveRender(Markdown.prototype.render, function (this: Component, width: number) {
	return (this as Markdown).renderLive(width);
});
