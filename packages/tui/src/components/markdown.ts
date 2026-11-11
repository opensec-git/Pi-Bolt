import type { Lexer, Marked, Token, Tokenizer, TokenizerExtension, Tokens } from "marked";
import { renderLatex } from "../latex.ts";
import { marked, markedCjkFriendly } from "../marked-module.ts";
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
	cjkPunctuationRegex,
	flattenLines,
	INTERNED_LINE_LENGTH,
	internLine,
	isWebLink,
	localFileLinkTarget,
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

/** Pi's tokenizer, made with the parser (getMarkdownParser()): its base class is marked's, evaluated then. */
function createMarkdownTokenizer(): Tokenizer {
	class MarkdownTokenizer extends marked().Tokenizer {
		override lheading(src: string): Tokens.Heading | undefined {
			return !setextGuard || mayStartSetextHeading(src) ? super.lheading(src) : undefined;
		}

		override url(src: string): Tokens.Link | undefined {
			const token = super.url(src);
			if (!token) return undefined;

			// Bare URLs stop at CJK punctuation; explicit links and angle autolinks
			// use separate tokenizers and can still contain these characters.
			const punctuationIndex = token.raw.search(cjkPunctuationRegex);
			return punctuationIndex < 0 ? token : super.url(token.raw.slice(0, punctuationIndex));
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
	return new MarkdownTokenizer();
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
			// (marked calls this for each text token, with the rest of the text: searched again each time, a long paragraph
			// would cost time in proportion to the square of its length. InlineMemoLexer knows where in the text it is.)
			const known = this.lexer instanceof inlineMemoLexer() ? this.lexer.latexStart(source) : null;
			if (known !== null) return known;
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

let markdownParser: Marked | undefined;

/** Pi's Markdown parser, set up on the first lex: a start that renders no Markdown (`pi -p`) does not build it. */
function getMarkdownParser(): Marked {
	if (!markdownParser) {
		markdownParser = new (marked().Marked)();
		markdownParser.setOptions({
			tokenizer: createMarkdownTokenizer(),
		});
		markdownParser.use({
			tokenizer: { emStrong: markedCjkFriendly().tokenizer?.emStrong },
			extensions: [...LATEX_MARKDOWN_EXTENSIONS],
		});
	}
	return markdownParser;
}

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
function defineInlineMemoLexer(Base: typeof Lexer) {
	return class InlineMemoLexer extends Base {
		private inlineDepth = 0;
		/**
		 * The texts inlineTokens() is lexing, the innermost last, each with where in it a "$", "\(" or "\[" begins (found when
		 * first asked, latexStart()) and the index of the first of those at or after the position asked about last.
		 */
		private readonly inlineSources: Array<{ src: string; delimiters?: number[]; next: number }> = [];

		override lex(src: string): ReturnType<Lexer["lex"]> {
			try {
				return super.lex(src);
			} finally {
				lexedInlines = nextLexedInlines;
				nextLexedInlines = new Map();
			}
		}

		/**
		 * Where the first "$", "\(" or "\[" of `rest` begins (undefined: none), as the inline LaTeX extension's start() finds it,
		 * for the `rest` of the text being lexed that marked asks about: what is left of it after the token being lexed begins.
		 * marked's loop only takes tokens off the front of the text, so `rest` is the text from `src.length - rest.length` on.
		 * Null when no text is being lexed that `rest` can be the end of.
		 */
		latexStart(rest: string): number | undefined | null {
			const source = this.inlineSources[this.inlineSources.length - 1];
			if (source === undefined || rest.length >= source.src.length) return null;
			if (source.delimiters === undefined) {
				const delimiters: number[] = [];
				const delimiter = /\$|\\[([]/g;
				for (let match = delimiter.exec(source.src); match !== null; match = delimiter.exec(source.src)) {
					delimiters.push(match.index);
				}
				source.delimiters = delimiters;
			}
			const offset = source.src.length - rest.length;
			const delimiters = source.delimiters;
			let next = source.next;
			while (next > 0 && delimiters[next - 1]! >= offset) next--;
			while (next < delimiters.length && delimiters[next]! < offset) next++;
			source.next = next;
			return next < delimiters.length ? delimiters[next]! - offset : undefined;
		}

		override inlineTokens(src: string, tokens: Token[] = []): Token[] {
			this.inlineSources.push({ src, next: 0 });
			try {
				return this.memoInlineTokens(src, tokens);
			} finally {
				this.inlineSources.pop();
			}
		}

		private memoInlineTokens(src: string, tokens: Token[]): Token[] {
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
	};
}

let inlineMemoLexerClass: ReturnType<typeof defineInlineMemoLexer> | undefined;

/** InlineMemoLexer, defined at the first lex: its base class is marked's Lexer, evaluated then. */
function inlineMemoLexer(): ReturnType<typeof defineInlineMemoLexer> {
	inlineMemoLexerClass ??= defineInlineMemoLexer(marked().Lexer);
	return inlineMemoLexerClass;
}

let memos = true;

/** marked's lexer() for Pi's Markdown: InlineMemoLexer, or marked's own lexer with the memos off. */
function lexTokens(source: string): Token[] {
	return memos && markdownCaching
		? new (inlineMemoLexer())(getMarkdownParser().defaults).lex(source)
		: getMarkdownParser().lexer(source);
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

/** A line that can open a display-math block (`$$` or `\[`), as the LaTeX block tokenizer looks for it. */
const DISPLAY_MATH_LINE = /(?:^|\n) {0,3}(?:\$\$|\\\[)/;

/**
 * Whether the block at `before` ends where it does whatever text is added after the block at `last` (the last of
 * `tokens`, with only blank lines between the two): when `last` is past its first line that is not blank, that line is
 * known, and a block that starts as SELF_STARTING_BLOCKS do ends at the first line it cannot take. Not a paragraph: a
 * setext underline lines later can make a heading of it and of the lines after it that an extension's block (`$$`) took.
 * But a paragraph followed by a blank line ends there whatever comes after it: neither it nor a setext heading goes on
 * past a blank line, and that line is known once a block follows it. Except a paragraph with a line that opens display
 * math (`$$`, `\[`): the math block can take it and the lines after it across blank lines once its closer arrives. And
 * except one that leaves an inline `<a>` or raw block open (inlineStateKept()), whose state the next blocks are lexed in.
 */
function lastBlockSettlesOneBefore(tokens: readonly Token[], before: number, last: number): boolean {
	if (!fastPaths || before < 0) return false;
	const between = tokens[before + 1];
	const block = tokens[before]!;
	if (
		block.type === "paragraph" &&
		between?.type === "space" &&
		before + 1 < last &&
		between.raw.indexOf("\n") !== between.raw.lastIndexOf("\n") &&
		!DISPLAY_MATH_LINE.test(block.raw) &&
		inlineStateKept((block as Tokens.Paragraph).tokens)
	) {
		return true;
	}
	if (!SELF_STARTING_BLOCKS.has(tokens[before]!.type)) return false;
	// (A block's raw text can begin with lines of spaces.)
	const raw = tokens[last]!.raw;
	const text = raw.search(/\S/);
	return text >= 0 && raw.includes("\n", text);
}

/**
 * The text of `source` from `start` on, as a string of its own. The text of a token is a part of the string that was lexed,
 * and keeps all of that string in memory. Lexing the end of `source` as a part of it would leave every block of a long
 * message holding the whole message as it was when the block was lexed, megabytes for each answer.
 * (`added`: what `source` adds to `previous.source`, when that is known. The end is then taken from the end of the
 * previous source kept with it and the added text: `source` is not read, which for a source made of the last one and the
 * added text would copy all of it.)
 */
function sourceEnd(previous: LexedMarkdown, source: string, added: string | undefined, start: number): string {
	const kept = previous.end;
	return ownString(
		added !== undefined && kept !== undefined && start >= kept.start
			? kept.text.slice(start - kept.start) + added
			: source.slice(start),
	);
}

/**
 * Where lexing a list or a table again can begin: after its first `shared` items or rows, the first `skip` characters of
 * its raw text. `head` is lexed before the text from there: a table's header and delimiter rows.
 */
interface BlockResume {
	shared: number;
	skip: number;
	head: string;
}

/**
 * For a list or a table lexed from the items or rows of the one lexed before it (lexAddedToBlock()): that one, and how many
 * items or rows the two have in common (the same objects). Held weakly, as lexedFrom.
 */
const resumedBlocks = new WeakMap<Token, { from: WeakRef<Token>; shared: number }>();

/**
 * For a list or a table: how many of its parts (a list's items; a table's header, then its rows), counted from the first,
 * are known to lex alone as they lex after the parts before them (listItemStandsAlone(), inlineStateKept()).
 */
const standAloneParts = new WeakMap<Token, number>();

/**
 * Where a list or a table that text is added after can be lexed again from (lexAddedToBlock()), or undefined.
 * A list item ends where a line after it begins another item (or a block that ends the item): once the last item is past
 * its first line, that line is known and the items before it are settled; until then the one before it is not ("-"
 * turning into "--" makes a setext underline of it). Each row of a table is a line of its own: all but the last, which may
 * still grow, are settled.
 * The items or rows kept must also lex alone as they lex with the others (standAloneParts): the ones after them are lexed
 * without them.
 */
function blockResume(block: Token): BlockResume | undefined {
	if (block.type === "list") {
		const items = (block as Tokens.List).items;
		const lastItem = items[items.length - 1];
		if (lastItem === undefined) return undefined;
		const shared = items.length - (lastItem.raw.includes("\n") ? 1 : 2);
		if (shared < 1 || standAloneCount(block, items, shared, listItemStandsAlone) < shared) {
			return undefined;
		}
		let skip = 0;
		for (let i = 0; i < shared; i++) skip += items[i]!.raw.length;
		return { shared, skip, head: "" };
	}
	if (block.type === "table") {
		const table = block as Tokens.Table;
		const shared = table.rows.length - 1;
		if (shared < 1) return undefined;
		// (The header is part 0: rows are lexed after it again.)
		const parts = [table.header, ...table.rows];
		const alone = (cells: Tokens.TableCell[]) => cells.every((cell) => inlineStateKept(cell.tokens));
		if (standAloneCount(block, parts, shared + 1, alone) < shared + 1) {
			return undefined;
		}
		const raw = table.raw;
		const headerEnd = raw.indexOf("\n");
		const delimiterEnd = headerEnd < 0 ? -1 : raw.indexOf("\n", headerEnd + 1);
		if (delimiterEnd < 0) return undefined;
		let skip = delimiterEnd + 1;
		for (let i = 0; i < shared; i++) {
			const lineEnd = raw.indexOf("\n", skip);
			if (lineEnd < 0) return undefined;
			skip = lineEnd + 1;
		}
		return { shared, skip, head: ownString(raw.slice(0, delimiterEnd + 1)) };
	}
	return undefined;
}

/** How many of `parts`, the first `wanted` at most, lex alone as they lex after the others (standAloneParts). */
function standAloneCount<T>(block: Token, parts: readonly T[], wanted: number, alone: (part: T) => boolean): number {
	let count = standAloneParts.get(block) ?? 0;
	while (count < wanted && alone(parts[count]!)) count++;
	standAloneParts.set(block, count);
	return count;
}

/**
 * Whether lexing a list item gave what it gives in a list with other items before or after it. Two things are carried
 * from item to item: the inline lexer's state (inlineStateKept()), and the task markers taken off. For each task item,
 * marked takes the marker off the last text queued for inline lexing that starts with one, which is the item's own first
 * text unless some other text in the list starts with a marker (a heading "[ ] x" in an item, a second paragraph): then an
 * item's marker can be taken off another item's text. With no such text in any item, each item's own is.
 */
function listItemStandsAlone(item: Tokens.ListItem): boolean {
	return inlineStateKept(item.tokens) && !hasTaskLikeText(item.tokens);
}

/** marked's test for a task item's text (rules.other.listIsTask). */
const TASK_TEXT = /^\[[ xX]\] +\S/;

/**
 * Whether any text in these tokens, or in the tokens in them, starts as a task item's does (TASK_TEXT): one whose marker
 * was not taken off. (A task item's own first text has had its marker taken off.)
 */
function hasTaskLikeText(tokens: readonly Token[]): boolean {
	for (const token of tokens) {
		if ("text" in token && typeof token.text === "string" && TASK_TEXT.test(token.text)) return true;
		if ("tokens" in token && Array.isArray(token.tokens) && hasTaskLikeText(token.tokens)) return true;
		if (token.type === "list" && (token as Tokens.List).items.some((item) => hasTaskLikeText(item.tokens))) {
			return true;
		}
		if (token.type === "table") {
			const table = token as Tokens.Table;
			for (const cells of [table.header, ...table.rows]) {
				if (cells.some((cell) => TASK_TEXT.test(cell.text) || hasTaskLikeText(cell.tokens))) return true;
			}
		}
	}
	return false;
}

/**
 * Whether lexing these tokens left marked's inline lexer in the state it found it in: no inline HTML tag in them opens a
 * link (`<a>`) or a raw block (`<pre>`, `<script>`, `<style>`), which the lexer carries to the texts after them. (Links
 * only ever end those.)
 */
function inlineStateKept(tokens: readonly Token[]): boolean {
	for (const token of tokens) {
		if (token.type === "html" && "inLink" in token && (token.inLink === true || token.inRawBlock === true))
			return false;
		if ("tokens" in token && Array.isArray(token.tokens) && !inlineStateKept(token.tokens)) return false;
		if (token.type === "list" && !(token as Tokens.List).items.every((item) => inlineStateKept(item.tokens))) {
			return false;
		}
		if (token.type === "table") {
			const table = token as Tokens.Table;
			for (const cells of [table.header, ...table.rows]) {
				if (!cells.every((cell) => inlineStateKept(cell.tokens))) return false;
			}
		}
	}
	return true;
}

/**
 * The tokens of `source` from where `block`, the list or table at `offset`, begins, lexed from after its first items or
 * rows (blockResume()): those are taken from `block`, and the list or table lexed from the rest is joined to them. A list
 * is loose when any of its items is, which changes all of them: when the rest is not as loose as `block` was, undefined,
 * and so when what was lexed does not begin with a list or table that the rest of `block` can be.
 * Why the rest lexes as it would after the items kept: an item ends at the first line that cannot be part of it, and the
 * next item begins there with nothing carried over but whether the item before ended with a blank line, which makes the
 * list loose (as it made `block` loose: every item kept was followed by another). A table's rows are a line each, cut into
 * cells by themselves; the header and delimiter rows are lexed before them again.
 */
function lexAddedToBlock(
	previous: LexedMarkdown,
	source: string,
	added: string | undefined,
	offset: number,
	block: Token,
	resume: BlockResume,
): Token[] | undefined {
	const start = offset + resume.skip;
	const end = sourceEnd(previous, source, added, start);
	const lexed = lexTokens(resume.head + end);
	const rest = lexed[0];
	let joined: Token | undefined;
	if (block.type === "list" && rest?.type === "list") {
		const list = block as Tokens.List;
		const restList = rest as Tokens.List;
		if (
			restList.ordered === list.ordered &&
			restList.loose === list.loose &&
			restList.items.every(listItemStandsAlone)
		) {
			const items = list.items.slice(0, resume.shared);
			for (const item of restList.items) items.push(item);
			const joinedList: Tokens.List = {
				type: "list",
				raw: list.raw.slice(0, resume.skip) + restList.raw,
				ordered: list.ordered,
				start: list.start,
				loose: list.loose,
				items,
			};
			joined = joinedList;
		}
	} else if (block.type === "table" && rest?.type === "table") {
		const table = block as Tokens.Table;
		const restTable = rest as Tokens.Table;
		if (
			restTable.raw.length >= resume.head.length &&
			restTable.header.length === table.header.length &&
			restTable.align.every((align, i) => align === table.align[i])
		) {
			const rows = table.rows.slice(0, resume.shared);
			for (const row of restTable.rows) rows.push(row);
			const joinedTable: Tokens.Table = {
				type: "table",
				raw: table.raw.slice(0, resume.skip) + restTable.raw.slice(resume.head.length),
				header: table.header,
				align: table.align,
				rows,
			};
			joined = joinedTable;
		}
	}
	if (joined === undefined) return undefined;
	lexed[0] = joined;
	resumedBlocks.set(joined, { from: new WeakRef(block), shared: resume.shared });
	const parts = block.type === "table" ? resume.shared + 1 : resume.shared;
	standAloneParts.set(joined, Math.min(standAloneParts.get(block) ?? 0, parts));
	lastEnd = { start: offset, text: block.raw.slice(0, resume.skip) + end };
	return lexed;
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
	// A list or table that grows is lexed again from its last items or rows (lexAddedToBlock()), when that gives what lexing
	// all of it gives; otherwise from its start.
	const block = previous.tokens[last]!;
	const resume = fastPaths ? blockResume(block) : undefined;
	let lexedEnd = resume === undefined ? undefined : lexAddedToBlock(previous, source, added, offset, block, resume);
	const resumed = lexedEnd !== undefined;
	if (lexedEnd === undefined) {
		const end = sourceEnd(previous, source, added, offset);
		lastEnd = { start: offset, text: end };
		lexedEnd = lexTokens(end);
	}
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
			!resumed &&
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
		const tokens = getMarkdownParser().lexer(source);
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
	/**
	 * For a block rendered in parts (BlockParts): the number of lines up to and including each part, and for a table the
	 * widths of its columns.
	 */
	partEnds?: number[];
	columnWidths?: readonly number[];
	/**
	 * Highlighted lines of the block's code, by language and source (Markdown.highlightCode()): they survive a resize or new
	 * spacing, until invalidate(). Held weakly, like the tokens.
	 */
	highlightedCode?: WeakRef<Map<string, string[]>>;
}

const renderedBlocks = new WeakMap<Token, RenderedBlock>();

/** A code block in parts (Markdown.codeBlockParts()). */
interface CodeBlockParts {
	before: string[];
	code: string[];
	draw: (codeLine: string) => string[];
	after: string[];
	shade: ((text: string) => string) | undefined;
	indent: string;
}

/** A block rendered in parts (Markdown.blockParts()). */
interface BlockParts {
	count: number;
	/** The lines of a part, before they are wrapped, with margins and background (renderBlock()). */
	render: (part: number) => string[];
	/** For a table: the widths of its columns, which all its lines depend on. */
	columnWidths?: readonly number[];
	/** The rendered block whose first `parts` parts this block's are. */
	reused?: { block: RenderedBlock; parts: number };
}

function sameWidths(a: readonly number[] | undefined, b: readonly number[] | undefined): boolean {
	if (a === b) return true;
	if (a === undefined || b === undefined || a.length !== b.length) return false;
	for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
	return true;
}

/** What the cells of a table's header or row render to, and how wide they are (Markdown.measureCells()). */
interface MeasuredCells {
	settings: RenderSettings;
	texts: string[];
	/** The visible width of each text, and of its longest word (up to the width of the longest word kept unbroken). */
	widths: number[];
	wordWidths: number[];
}

/** The cells of a top-level table's header and rows, measured, by header or row (the array of its cells). */
const measuredCells = new WeakMap<Tokens.TableCell[], MeasuredCells>();

/** The width of the longest word a table's cell is not broken in, if it fits. */
const MAX_UNBROKEN_WORD_WIDTH = 30;

/**
 * The Markdown components that rendered blocks last, the most recent last: only these keep what speeds up rendering their
 * text again (Markdown.dropMemos()). A reply that streams in renders again for each chunk and stays among them; a message of
 * a long transcript is rendered once when the transcript is laid out, and would otherwise keep its wrapped lines and its
 * tokens (several times the size of its text) for as long as the session is open.
 */
const memoHolders: Markdown[] = [];
const MEMO_HOLDERS_KEPT = 16;

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

// An unclosed comment runs to the end of an HTML block, as in CommonMark, so streamed comments stay hidden.
const HTML_COMMENT_REGEX = /<!--[\s\S]*?(?:-->|$)/g;

function stripHtmlComments(html: string): string {
	return html.replace(HTML_COMMENT_REGEX, "");
}

function isHtmlCommentBlock(token: Token): boolean {
	return token.type === "html" && stripHtmlComments(token.raw).trim() === "";
}

/**
 * What a Markdown component knows of its text, kept as text is added to it: whether it has a character that is not white
 * space, a tab, an HTML comment's start; and its last three characters (`end`), where a "<!--" that ends in added text begins.
 */
interface TextFacts {
	text: string;
	visible: boolean;
	tabs: boolean;
	comments: boolean;
	end: string;
}

/** The tokens tokensFor() renders, by the lexed tokens they were made from (withoutHtmlCommentBlocks()). */
const renderedWithoutComments = new WeakMap<Token[], Token[]>();

/** List and blockquote tokens without comment-only HTML blocks inside, by the token they were made from. */
const withoutCommentsInside = new WeakMap<Token, Token>();

/**
 * `tokens` without the block HTML tokens that hold only comments, so they render no line; `tokens` itself when it has none
 * (nearly always). A removed comment between two blank lines, or at the start or end, also drops a blank line, so it leaves
 * no extra gap (earendil-works/pi#11710). Lexed tokens are never changed: lexMarkdown() lexes a growing text from the
 * tokens of the text before, which must add up to it, so the rendered tokens are this copy instead.
 */
function withoutHtmlCommentBlocks(tokens: Token[]): Token[] {
	let result: Token[] | undefined;
	for (let i = tokens.length - 1; i >= 0; i--) {
		const token = tokens[i];
		let kept: Token | undefined = token;
		if (token.type === "list") {
			kept = withoutCommentsInList(token as Tokens.List);
		} else if (token.type === "blockquote") {
			kept = withoutCommentsInBlockquote(token as Tokens.Blockquote);
		} else if (isHtmlCommentBlock(token)) {
			kept = undefined;
		}
		if (kept === token) continue;
		result ??= tokens.slice();
		if (kept !== undefined) {
			result[i] = kept;
			continue;
		}
		result.splice(i, 1);
		if (result[i]?.type === "space" && (i === 0 || result[i - 1]?.type === "space")) {
			result.splice(i, 1);
		} else if (i === result.length && result[i - 1]?.type === "space") {
			result.splice(--i, 1);
		}
	}
	return result ?? tokens;
}

function withoutCommentsInList(list: Tokens.List): Token {
	const known = withoutCommentsInside.get(list);
	if (known) return known;
	let items: Tokens.ListItem[] | undefined;
	for (let i = 0; i < list.items.length; i++) {
		const item = list.items[i];
		const tokens = withoutHtmlCommentBlocks(item.tokens);
		if (tokens === item.tokens) continue;
		items ??= list.items.slice();
		items[i] = { ...item, tokens };
	}
	const result: Token = items ? { ...list, items } : list;
	withoutCommentsInside.set(list, result);
	return result;
}

function withoutCommentsInBlockquote(blockquote: Tokens.Blockquote): Token {
	const known = withoutCommentsInside.get(blockquote);
	if (known) return known;
	const tokens = blockquote.tokens ? withoutHtmlCommentBlocks(blockquote.tokens) : undefined;
	const result: Token = tokens !== blockquote.tokens ? { ...blockquote, tokens: tokens ?? [] } : blockquote;
	withoutCommentsInside.set(blockquote, result);
	return result;
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
	/** Highlighted lines of a code block. A Markdown component reuses the result for the same code until invalidate(). */
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
	private facts?: TextFacts;
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
	private wraps?: Map<number, Map<string, readonly string[]>>;
	private nextWraps?: Map<number, Map<string, readonly string[]>>;
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
	/**
	 * Highlighted code of the blocks the last render that highlighted any looked up or highlighted, and of this render so
	 * far (highlightCode()): a block lexed again to a new token (the last blocks of a reply streaming in, or all of a text
	 * lexed in full) takes the code it has in common with the one before from there. Held weakly, like the tokens.
	 */
	private highlightedCode?: WeakRef<Map<string, string[]>>;
	private nextHighlightedCode?: Map<string, string[]>;
	/**
	 * Highlighted code of the block being rendered, and that of what was rendered for its token before: only set while
	 * renderBlock() runs.
	 */
	private blockHighlightedCode?: Map<string, string[]>;
	private knownHighlightedCode?: Map<string, string[]>;
	/**
	 * The last code block drawn by renderCodeBlock(): its lines of code, highlighted, and what each was drawn to, with what
	 * that depends on besides the line.
	 */
	private drawnCode?: {
		settings: RenderSettings;
		shade: ((text: string) => string) | undefined;
		indent: string;
		code: readonly string[];
		drawn: (readonly string[])[];
	};

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
		let grownFacts: TextFacts | undefined;
		if (facts !== undefined && added !== undefined && facts.text === before) {
			// (The end of the text before and what was added: a "<!--" can begin in the first and end in the second.)
			const end = facts.end + added;
			grownFacts = {
				text: this.text,
				visible: facts.visible || /\S/.test(added),
				tabs: facts.tabs || added.includes("\t"),
				comments: facts.comments || end.includes("<!--"),
				end: end.slice(-3),
			};
		}
		this.facts = grownFacts;
		this.cachedText = undefined;
		this.cachedWidth = undefined;
		this.cachedLines = undefined;
		reportRenderChange(this);
	}

	invalidate(): void {
		this.cachedText = undefined;
		this.cachedWidth = undefined;
		this.cachedLines = undefined;
		// What the theme's functions give may have changed: nothing rendered with them is good any more, and that includes
		// the default style's prefix and the highlighted code (RenderedBlock.highlightedCode is checked by generation).
		invalidateRenderedMarkdown();
		this.defaultStylePrefix = undefined;
		this.highlightedCode = undefined;
		this.nextHighlightedCode = undefined;
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
		this.nextWraps ??= new Map();
		let next = this.nextWraps.get(width);
		if (next === undefined) {
			next = new Map();
			this.nextWraps.set(width, next);
		}
		const wrapped = next.get(line) ?? this.wraps?.get(width)?.get(line) ?? wrapTextWithAnsi(line, width);
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
		// (The highlighted code of the block as it was rendered before, unless the theme was changed since: invalidate().)
		this.knownHighlightedCode =
			known !== undefined &&
			known.settings.generation === settings.generation &&
			known.settings.theme === settings.theme
				? known.highlightedCode?.deref()
				: undefined;
		const highlightedCode = new Map<string, string[]>();
		this.blockHighlightedCode = markdownCaching ? highlightedCode : undefined;

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
			let drawn: string;
			if (bgFn) {
				drawn = applyBackgroundToLine(lineWithMargins, width, bgFn);
			} else {
				// No background - just pad to width
				const visibleLen = visibleWidth(lineWithMargins);
				const paddingNeeded = Math.max(0, width - visibleLen);
				drawn = lineWithMargins + " ".repeat(paddingNeeded);
			}
			// (Blank lines and code fences are drawn alike in every message: one copy of each, internLine().)
			lines.push(line.length <= INTERNED_LINE_LENGTH ? internLine(drawn) : drawn);
		};
		const addRendered = (line: string) => {
			// Wrap lines (NO padding, NO background yet)
			if (isImageLine(line)) {
				add(line);
			} else {
				for (const wrappedLine of this.wrapLine(line, contentWidth)) {
					add(wrappedLine);
				}
			}
		};
		const parts =
			markdownCaching && fastPaths ? this.blockParts(token, nextTokenType, settings, contentWidth) : undefined;
		// (Lines taken from the block's last rendering are flat already.)
		let flatFrom = 0;
		let partEnds: number[] | undefined;
		if (parts === undefined && token.type === "code" && markdownCaching && memos) {
			// (The lines before and of the code are flat: renderCodeBlock().)
			const code = this.codeBlockParts(token as Tokens.Code, contentWidth, nextTokenType);
			flatFrom = this.renderCodeBlock(code, settings, lines, addRendered);
		} else if (parts === undefined) {
			for (const line of this.renderToken(token, contentWidth, nextTokenType)) addRendered(line);
		} else {
			// The parts this block has in common with the one it was lexed from are those lines; the others are rendered.
			const reused = parts.reused;
			partEnds = reused === undefined ? [] : reused.block.partEnds!.slice(0, reused.parts);
			if (reused !== undefined) {
				const reusedLines = reused.block.lines;
				for (let i = 0; i < partEnds[partEnds.length - 1]!; i++) lines.push(reusedLines[i]!);
				// (The code highlighted for those, for a later rendering of this block: renderBlock()'s `known`.)
				const reusedCode = reused.block.highlightedCode?.deref();
				if (reusedCode !== undefined) for (const [key, value] of reusedCode) highlightedCode.set(key, value);
			}
			flatFrom = lines.length;
			for (let part = partEnds.length; part < parts.count; part++) {
				for (const line of parts.render(part)) addRendered(line);
				partEnds.push(lines.length);
			}
		}
		this.blockHighlightedCode = undefined;
		this.knownHighlightedCode = undefined;
		flattenLines(flatFrom === 0 ? lines : lines.slice(flatFrom));
		if (markdownCaching) {
			renderedBlocks.set(token, {
				settings,
				nextTokenType,
				lines,
				...(partEnds === undefined ? {} : { partEnds }),
				...(parts?.columnWidths === undefined ? {} : { columnWidths: parts.columnWidths }),
				...(highlightedCode.size > 0 ? { highlightedCode: new WeakRef(highlightedCode) } : {}),
			});
		}
		return lines;
	}

	/**
	 * A top-level list or table as parts rendered one by one (a list's items; a table's header, rows and bottom), and the
	 * lines of the parts it has in common with the one it was lexed from (lexAddedToBlock()), when that was rendered so
	 * with the same settings, and for a table with the same column widths: an item or row's lines depend on nothing else.
	 * Undefined for other blocks, and for a table too narrow to be drawn as one.
	 */
	private blockParts(
		token: Token,
		nextTokenType: string | undefined,
		settings: RenderSettings,
		contentWidth: number,
	): BlockParts | undefined {
		let parts: BlockParts | undefined;
		let shared = 0;
		const resumed = resumedBlocks.get(token);
		if (token.type === "list") {
			const list = token as Tokens.List;
			parts = { count: list.items.length, render: (item) => this.renderListItem(list, item, 0, contentWidth) };
			// (The last item's lines differ from those it has when another follows it, in a loose list.)
			shared = Math.min(resumed?.shared ?? 0, list.items.length - 1);
		} else if (token.type === "table") {
			const table = token as Tokens.Table;
			parts = this.tableParts(table, contentWidth, nextTokenType, undefined, settings);
			// (Part 0 is the header. The last row has no border below it, and a table can lose its last row: a line that
			// grows into a list item ends the table instead.)
			shared = Math.min(resumed?.shared ?? 0, table.rows.length - 1) + 1;
		}
		if (parts === undefined || shared <= 0) return parts;
		const from = resumed?.from.deref();
		const block = from === undefined ? undefined : renderedBlocks.get(from);
		if (
			block?.partEnds !== undefined &&
			block.partEnds.length >= shared &&
			sameSettings(block.settings, settings) &&
			sameWidths(block.columnWidths, parts.columnWidths)
		) {
			parts.reused = { block, parts: shared };
		}
		return parts;
	}

	/**
	 * theme.highlightCode(code, lang), reusing what it gave before for the same code until invalidate(): from the block's
	 * last rendering (a resize, or new spacing), or from the last render that highlighted code (a block lexed again).
	 */
	private highlightCode(highlight: (code: string, lang?: string) => string[], code: string, lang?: string): string[] {
		const block = this.blockHighlightedCode;
		if (block === undefined) return highlight.call(this.theme, code, lang);
		const key = `${lang ?? ""}\0${code}`;
		let highlighted =
			block.get(key) ??
			this.knownHighlightedCode?.get(key) ??
			this.nextHighlightedCode?.get(key) ??
			this.highlightedCode?.deref()?.get(key);
		if (highlighted === undefined) highlighted = highlight.call(this.theme, code, lang);
		block.set(key, highlighted);
		this.nextHighlightedCode ??= new Map();
		this.nextHighlightedCode.set(key, highlighted);
		return highlighted;
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

	/** The tokens of the text at `width` (after the transform and tab replacement), or undefined when it has nothing to show. */
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
		// (Most texts have no HTML comment: their tokens are rendered as they are, with nothing looked at.)
		if (!(facts?.comments ?? text.includes("<!--"))) return { tokens, contentWidth };
		let shown = renderedWithoutComments.get(tokens);
		if (!shown) {
			shown = withoutHtmlCommentBlocks(tokens);
			renderedWithoutComments.set(tokens, shown);
		}
		// A text of comments only (a reply that so far is "<!-- ") renders as a text with nothing to show.
		if (shown.length === 0) return undefined;
		return { tokens: shown, contentWidth };
	}

	/** What the component knows of its text (TextFacts), made again when the text was not grown by setText(). */
	private textFacts(): TextFacts {
		const text = this.text;
		let facts = this.facts;
		if (facts?.text !== text) {
			facts = {
				text,
				visible: /\S/.test(text),
				tabs: text.includes("\t"),
				comments: text.includes("<!--"),
				end: text.slice(-3),
			};
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
			emptyLines.push(internLine(line));
		}
		// (The lines of the blocks are flat already: renderBlock().)
		flattenLines(emptyLines);
		return emptyLines;
	}

	/**
	 * Keeps the lines wrapped by this render, if it rendered any blocks (wrapLine()), and makes this the most recent of the
	 * components that keep their memos (memoHolders): the one rendered longest ago drops its own.
	 */
	private keepWraps(): void {
		if (this.nextWraps !== undefined && this.nextWraps.size > 0) {
			this.wraps = this.nextWraps;
			this.nextWraps = undefined;
		}
		if (this.nextHighlightedCode !== undefined) {
			this.highlightedCode = new WeakRef(this.nextHighlightedCode);
			this.nextHighlightedCode = undefined;
		}
		const holders = memoHolders;
		if (holders[holders.length - 1] === this) return;
		const index = holders.lastIndexOf(this);
		if (index !== -1) holders.splice(index, 1);
		holders.push(this);
		if (holders.length > MEMO_HOLDERS_KEPT) holders.shift()!.dropMemos();
	}

	/**
	 * Drops what only makes rendering the text again faster, keeping the lines: the lines wrapped by the last render (by line,
	 * so the same lines come out of wrapping them again), and for a complete text its tokens, which a render that needs them
	 * lexes again (cachedTokens holds them weakly until then). A complete text is lexed in full each time (lexMarkdown()), to
	 * the same tokens; a text that is not complete may have been lexed from a shorter one's tokens, which lexing it in full can
	 * split differently, so it keeps them.
	 */
	private dropMemos(): void {
		this.wraps = undefined;
		this.highlightedCode = undefined;
		this.drawnCode = undefined;
		if (this.options.complete === true) {
			this.live.tokens = undefined;
			this.live.settings = undefined;
			this.grown = undefined;
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
			// The lines from the first block rendered again on, and the change recorded from the first of them that is not
			// the line it replaces: a list or table that grows is rendered again with the lines of its first items or rows
			// (renderBlock()), and only what follows them is read again by those that read the lines.
			const start = reused > 0 ? padding.length + ends[reused - 1]! : 0;
			const fresh: string[] = [];
			if (reused > 0) {
				ends.length = reused;
			} else {
				ends.length = 0;
				for (const line of padding) fresh.push(line);
			}
			let contentLength = reused > 0 ? ends[reused - 1]! : 0;
			for (let i = reused; i < tokens.length; i++) {
				const blockLines = this.renderBlock(tokens[i], tokens[i + 1]?.type, settings, contentWidth);
				for (const line of blockLines) fresh.push(line);
				contentLength += blockLines.length;
				ends.push(contentLength);
			}
			for (const line of padding) fresh.push(line);
			const old = lines.lines;
			const comparable = Math.min(fresh.length, old.length - start);
			let same = 0;
			while (same < comparable && fresh[same] === old[start + same]) same++;
			lines.edit(start + same);
			for (let i = same; i < fresh.length; i++) old.push(fresh[i]!);
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
	 * A code block as renderToken() draws it, in parts: the lines before its code (the fence or the language label), its
	 * code highlighted (one entry per line of code), how one of those is drawn (before renderBlock() wraps the lines), and
	 * the lines after it. `shade` and `indent`: what a line of code's drawing depends on besides the line and the width.
	 */
	private codeBlockParts(token: Tokens.Code, width: number, nextTokenType: string | undefined): CodeBlockParts {
		const indent = this.theme.codeBlockIndent ?? "  ";
		const code: string[] = this.theme.highlightCode
			? this.highlightCode(this.theme.highlightCode, token.text, token.lang)
			: token.text.split("\n").map((codeLine: string) => this.theme.codeBlock(codeLine));
		const shade = this.theme.codeBlockBg;
		const before: string[] = [];
		const after: string[] = [];
		let draw: (codeLine: string) => string[];
		if (shade) {
			// Each line is shaded to the full width, so the block is one rectangle (and keeps its shape while a reply
			// streams in). Wrapped here, not by renderBlock(), so continuation lines keep the block's indent.
			const fill = (line: string) => line + " ".repeat(Math.max(0, width - visibleWidth(line)));
			if (token.lang) {
				for (const label of this.wrapLine(this.theme.codeBlockBorder(token.lang), width)) {
					before.push(shade(fill(label)));
				}
			}
			const codeWidth = Math.max(1, width - visibleWidth(indent));
			// (A reset inside a line, from the highlighter or the code itself, would end the shading there: the
			// background is opened again after it. Not a block on the terminal's own background, SGR 49: that is what a
			// reset leaves in effect, and an SGR 49 more would read as the end of the block in a panel.)
			let shadeOpen: string | undefined;
			draw = (codeLine) => {
				const drawn: string[] = [];
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
					drawn.push(shade(fill(line)));
				}
				return drawn;
			};
		} else {
			before.push(this.theme.codeBlockBorder(`\`\`\`${token.lang || ""}`));
			draw = (codeLine) => [`${indent}${codeLine}`];
			after.push(this.theme.codeBlockBorder("```"));
		}
		if (nextTokenType && nextTokenType !== "space") {
			after.push(""); // Add spacing after code blocks (unless space token follows)
		}
		return { before, code, draw, after, shade, indent };
	}

	/**
	 * The lines of a top-level code block, wrapped, with margins and background (`addRendered`, renderBlock()'s), into
	 * `lines`. A line of code is drawn to what it was drawn to by the last block drawn so when it is the same line at the
	 * same place: while a block streams in, all its lines but the last few are, and highlighting gives the same strings for
	 * them (GrowingHighlighter), so drawing the block costs what its new lines cost.
	 */
	private renderCodeBlock(
		code: CodeBlockParts,
		settings: RenderSettings,
		lines: string[],
		addRendered: (line: string) => void,
	): number {
		for (const line of code.before) addRendered(line);
		flattenLines(lines);
		const last = this.drawnCode;
		const reuse =
			last !== undefined &&
			last.shade === code.shade &&
			last.indent === code.indent &&
			sameSettings(last.settings, settings);
		const codeLines = code.code;
		const drawn: (readonly string[])[] = new Array(codeLines.length);
		for (let i = 0; i < codeLines.length; i++) {
			const codeLine = codeLines[i]!;
			let lineDrawn = reuse && last.code[i] === codeLine ? last.drawn[i] : undefined;
			if (lineDrawn === undefined) {
				const start = lines.length;
				for (const line of code.draw(codeLine)) addRendered(line);
				lineDrawn = lines.slice(start);
				flattenLines(lineDrawn);
			} else {
				// (Indexed: this runs for every line of a long block each time it is drawn.)
				for (let j = 0; j < lineDrawn.length; j++) lines.push(lineDrawn[j]!);
			}
			drawn[i] = lineDrawn;
		}
		this.drawnCode = { settings, shade: code.shade, indent: code.indent, code: code.code, drawn };
		const afterCode = lines.length;
		for (const line of code.after) addRendered(line);
		return afterCode;
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
				let rendered =
					!latexToken.pending && this.options.renderLatex !== false
						? renderLatex(latexToken.text, { display: true })
						: undefined;
				// A 2D layout cannot be wrapped row by row; fall back to the raw source when it does not fit.
				if (rendered === undefined || rendered.split("\n").some((line) => visibleWidth(line) > width)) {
					rendered = latexToken.raw.trim();
				}
				for (const line of rendered.split("\n")) {
					lines.push(this.applyDefaultStyle(line));
				}
				if (nextTokenType && nextTokenType !== "space") {
					lines.push("");
				}
				break;
			}

			case "code": {
				const code = this.codeBlockParts(token as Tokens.Code, width, nextTokenType);
				for (const line of code.before) lines.push(line);
				for (const codeLine of code.code) {
					for (const line of code.draw(codeLine)) lines.push(line);
				}
				for (const line of code.after) lines.push(line);
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
				// Render HTML as plain text (escaped for terminal), without its comments
				if ("raw" in token && typeof token.raw === "string") {
					lines.push(this.applyDefaultStyle(stripHtmlComments(token.raw).trim()));
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

		// After a skipped HTML comment, drop the next text's leading space if the text before ended with one,
		// so "a <!-- c --> b" renders as "a b".
		let dropLeadingSpace = false;
		for (let i = 0; i < tokens.length; i++) {
			let token = tokens[i];
			if (dropLeadingSpace && token.type === "text" && !token.tokens?.length && /^[ \t]/.test(token.text)) {
				token = { ...token, text: token.text.replace(/^[ \t]+/, "") };
			}
			dropLeadingSpace = false;
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
					const hyperlinks = getCapabilities().hyperlinks;
					if (hyperlinks && isWebLink(token.href)) {
						// OSC 8: a web or mail link is drawn as its text only, a hyperlink that a browser or mail program opens.
						result += hyperlink(styledLink, token.href) + stylePrefix;
						break;
					}
					// Any other link, and every link without hyperlinks, shows its URL in parentheses after its text (unless
					// the text is the URL), so where it goes is never hidden. Compare raw token.text (not styled) against href.
					// For mailto: links strip the prefix (autolinked emails use text="foo@bar.com" but
					// href="mailto:foo@bar.com").
					const hrefForComparison = token.href.startsWith("mailto:") ? token.href.slice(7) : token.href;
					const shown =
						token.text === token.href || token.text === hrefForComparison
							? styledLink
							: styledLink + this.theme.linkUrl(` (${token.href})`);
					// A local file (localFileLinkTarget()) is a hyperlink, text and URL together: a click opens what a click on
					// the printed URL opens (in fullscreen mode, through TuiAltScreen's openUrl). Any other link is not: a click
					// on a relative path or another scheme (an app's handler, a target `open` reads as an option) could run a
					// program.
					const target = hyperlinks ? localFileLinkTarget(token.href) : undefined;
					result += (target === undefined ? shown : hyperlink(shown, target)) + stylePrefix;
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
					// Render inline HTML as plain text; comments are hidden
					if (token.raw.startsWith("<!--")) {
						dropLeadingSpace = i === 0 || /[ \t]$/.test(tokens[i - 1].raw);
					} else {
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
		for (let i = 0; i < token.items.length; i++) {
			for (const line of this.renderListItem(token, i, depth, width, styleContext)) lines.push(line);
		}
		return lines;
	}

	/** The lines of item `index` of a list (renderList()). */
	private renderListItem(
		token: Tokens.List,
		index: number,
		depth: number,
		width: number,
		styleContext?: InlineStyleContext,
	): string[] {
		const lines: string[] = [];
		const indent = "    ".repeat(depth);
		// Use the list's start property (defaults to 1 for ordered lists)
		const startNumber = typeof token.start === "number" ? token.start : 1;
		const item = token.items[index]!;
		const isLastItem = index === token.items.length - 1;
		const bullet = token.ordered
			? this.options.preserveOrderedListMarkers
				? (this.getOrderedListMarker(item) ?? `${startNumber + index}. `)
				: `${startNumber + index}. `
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
		const parts = this.tableParts(token, availableWidth, nextTokenType, styleContext);
		if (parts === undefined) {
			if (token.header.length === 0) {
				return [];
			}
			// Too narrow to render a stable table. Fall back to raw markdown.
			const fallbackLines = token.raw ? wrapTextWithAnsi(token.raw, availableWidth) : [];
			if (nextTokenType && nextTokenType !== "space") {
				fallbackLines.push("");
			}
			return fallbackLines;
		}
		const lines: string[] = [];
		for (let part = 0; part < parts.count; part++) {
			for (const line of parts.render(part)) lines.push(line);
		}
		return lines;
	}

	/**
	 * A table as parts (BlockParts): its header with the borders above and below it, each row with the border below it
	 * (but the last), and the bottom border. Undefined when it has no columns, or is too narrow to be drawn as a table.
	 * `settings`, for a top-level table (no `styleContext`): what its cells render to is kept with its header and rows
	 * (measureCells()).
	 */
	private tableParts(
		token: Tokens.Table,
		availableWidth: number,
		nextTokenType: string | undefined,
		styleContext: InlineStyleContext | undefined,
		settings?: RenderSettings,
	): BlockParts | undefined {
		const numCols = token.header.length;

		if (numCols === 0) {
			return undefined;
		}

		// Calculate border overhead: "│ " + (n-1) * " │ " + " │"
		// = 2 + (n-1) * 3 + 2 = 3n + 1
		const borderOverhead = 3 * numCols + 1;
		const availableForCells = availableWidth - borderOverhead;
		if (availableForCells < numCols) {
			return undefined;
		}

		// Calculate natural column widths (what each column needs without constraints)
		const header = this.measureCells(token.header, styleContext, settings);
		const rows = token.rows.map((row) => this.measureCells(row, styleContext, settings));
		const naturalWidths: number[] = [];
		const minWordWidths: number[] = [];
		for (let i = 0; i < numCols; i++) {
			naturalWidths[i] = header.widths[i]!;
			minWordWidths[i] = Math.max(1, header.wordWidths[i]!);
		}
		for (const row of rows) {
			for (let i = 0; i < row.texts.length; i++) {
				naturalWidths[i] = Math.max(naturalWidths[i] || 0, row.widths[i]!);
				minWordWidths[i] = Math.max(minWordWidths[i] || 1, row.wordWidths[i]!);
			}
		}

		const columnWidths = this.tableColumnWidths(naturalWidths, minWordWidths, availableWidth, availableForCells);
		const stylePrefix = styleContext?.stylePrefix;
		const separatorLine = `├─${columnWidths.map((w) => "─".repeat(w)).join("─┼─")}─┤`;
		const render = (part: number): string[] => {
			const lines: string[] = [];
			if (part === 0) {
				// Render top border
				const topBorderCells = columnWidths.map((w) => "─".repeat(w));
				lines.push(`┌─${topBorderCells.join("─┬─")}─┐`);

				// Render header with wrapping
				const headerCellLines: string[][] = header.texts.map((text, i) =>
					this.wrapCellText(text, columnWidths[i]!, stylePrefix),
				);
				const headerLineCount = Math.max(...headerCellLines.map((c) => c.length));

				for (let lineIdx = 0; lineIdx < headerLineCount; lineIdx++) {
					const rowParts = headerCellLines.map((cellLines, colIdx) => {
						const text = cellLines[lineIdx] || "";
						const padded = text + " ".repeat(Math.max(0, columnWidths[colIdx]! - visibleWidth(text)));
						return this.theme.bold(padded);
					});
					lines.push(`│ ${rowParts.join(" │ ")} │`);
				}

				// Render separator
				lines.push(separatorLine);
				return lines;
			}
			const rowIndex = part - 1;
			if (rowIndex === rows.length) {
				// Render bottom border
				const bottomBorderCells = columnWidths.map((w) => "─".repeat(w));
				lines.push(`└─${bottomBorderCells.join("─┴─")}─┘`);

				if (nextTokenType && nextTokenType !== "space") {
					lines.push(""); // Add spacing after table
				}
				return lines;
			}

			// Render rows with wrapping
			const rowCellLines: string[][] = rows[rowIndex]!.texts.map((text, i) =>
				this.wrapCellText(text, columnWidths[i]!, stylePrefix),
			);
			const rowLineCount = Math.max(...rowCellLines.map((c) => c.length));

			for (let lineIdx = 0; lineIdx < rowLineCount; lineIdx++) {
				const rowParts = rowCellLines.map((cellLines, colIdx) => {
					const text = cellLines[lineIdx] || "";
					return text + " ".repeat(Math.max(0, columnWidths[colIdx]! - visibleWidth(text)));
				});
				lines.push(`│ ${rowParts.join(" │ ")} │`);
			}

			if (rowIndex < rows.length - 1) {
				lines.push(separatorLine);
			}
			return lines;
		};
		return { count: rows.length + 2, render, columnWidths };
	}

	/**
	 * What the cells of a table's header or row render to, and how wide each is. Kept with the cells for a top-level table
	 * (`settings` given), for as long as the settings are the same: a table that streams in is rendered again for each row.
	 */
	private measureCells(
		cells: Tokens.TableCell[],
		styleContext: InlineStyleContext | undefined,
		settings: RenderSettings | undefined,
	): MeasuredCells | Omit<MeasuredCells, "settings"> {
		const known = settings === undefined ? undefined : measuredCells.get(cells);
		if (known !== undefined && settings !== undefined && sameSettings(known.settings, settings)) return known;
		const texts: string[] = [];
		const widths: number[] = [];
		const wordWidths: number[] = [];
		for (const cell of cells) {
			const text = this.renderInlineTokens(cell.tokens || [], styleContext);
			texts.push(text);
			widths.push(visibleWidth(text));
			wordWidths.push(this.getLongestWordWidth(text, MAX_UNBROKEN_WORD_WIDTH));
		}
		if (settings === undefined) return { texts, widths, wordWidths };
		const measured = { settings, texts, widths, wordWidths };
		measuredCells.set(cells, measured);
		return measured;
	}

	/** The widths of a table's columns: as wide as their cells, or narrower in proportion when they do not fit. */
	private tableColumnWidths(
		naturalWidths: number[],
		minWordWidths: number[],
		availableWidth: number,
		availableForCells: number,
	): number[] {
		const numCols = naturalWidths.length;
		const borderOverhead = 3 * numCols + 1;
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
		return columnWidths;
	}
}

markStableRender(Markdown);
markReportsChanges(Markdown);
markLiveRender(Markdown.prototype.render, function (this: Component, width: number) {
	return (this as Markdown).renderLive(width);
});
