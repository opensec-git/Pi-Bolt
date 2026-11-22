import { eastAsianWidth } from "get-east-asian-width";

// Segmenters (shared instances), made on first use: the first Intl.Segmenter of a process sets up ICU's segmentation
// data, about half a millisecond, which a start that measures no text (`pi -p`) does not need.
let graphemeSegmenter: Intl.Segmenter | undefined;
let wordSegmenter: Intl.Segmenter | undefined;

/**
 * Get the shared grapheme segmenter instance.
 */
export function getGraphemeSegmenter(): Intl.Segmenter {
	graphemeSegmenter ??= new Intl.Segmenter(undefined, { granularity: "grapheme" });
	return graphemeSegmenter;
}

/**
 * Get the shared word segmenter instance.
 */
export function getWordSegmenter(): Intl.Segmenter {
	wordSegmenter ??= new Intl.Segmenter(undefined, { granularity: "word" });
	return wordSegmenter;
}

/**
 * Check if a grapheme cluster (after segmentation) could possibly be an RGI emoji.
 * This is a fast heuristic to avoid the expensive rgiEmojiRegex test.
 * The tested Unicode blocks are deliberately broad to account for future
 * Unicode additions.
 */
function couldBeEmoji(segment: string): boolean {
	const cp = segment.codePointAt(0)!;
	return (
		(cp >= 0x1f000 && cp <= 0x1fbff) || // Emoji and Pictograph
		(cp >= 0x2300 && cp <= 0x23ff) || // Misc technical
		(cp >= 0x2600 && cp <= 0x27bf) || // Misc symbols, dingbats
		(cp >= 0x2b50 && cp <= 0x2b55) || // Specific stars/circles
		segment.includes("\uFE0F") || // Contains VS16 (emoji presentation selector)
		segment.length > 2 // Multi-codepoint sequences (ZWJ, skin tones, etc.)
	);
}

// Regexes for character classification (same as string-width library)
const zeroWidthRegex = /^(?:\p{Default_Ignorable_Code_Point}|\p{Control}|\p{Mark}|\p{Surrogate})+$/v;
const leadingNonPrintingRegex = /^[\p{Default_Ignorable_Code_Point}\p{Control}\p{Format}\p{Mark}\p{Surrogate}]+/v;
const nonPrintingCharRegex = /^(?:\p{Default_Ignorable_Code_Point}|\p{Control}|\p{Format}|\p{Mark}|\p{Surrogate})$/v;
const markCharRegex = /^\p{Mark}$/v;
// Marks that terminals allocate cells for when attached to a base character.
// This includes Unicode spacing marks and non-spacing exceptions in legacy wcwidth tables.
const terminalSpacingMarkRegex =
	/^(?:[\p{Spacing_Mark}--[\u1734\u302E\u302F]]|[\u065F\u0F7F\u102B\u102C\u1031\u1033-\u1035\u1038\u103A-\u103E])+$/v;
const rgiEmojiRegex = /^\p{RGI_Emoji}$/v;
// RGI_Emoji restricted to a single code point: Emoji_Presentation without the regional indicators
// (test/rgi-emoji-single-code-point.test.ts checks every code point). The first test of rgiEmojiRegex
// builds its matcher for all of RGI_Emoji's sequences, which costs tens of millions of instructions
// for each string width; this one costs about as much as the other property regexes.
const singleCodePointEmojiRegex = /^(?![\u{1F1E6}-\u{1F1FF}])\p{Emoji_Presentation}$/u;

// Cache for non-ASCII strings
const WIDTH_CACHE_SIZE = 512;
const WIDTH_CACHE_CODE_UNITS = 1024 * 1024;
let widthCacheUnits = 0;
const widthCache = new Map<string, number>();

export const cjkBreakRegex =
	/[\p{Script_Extensions=Han}\p{Script_Extensions=Hiragana}\p{Script_Extensions=Katakana}\p{Script_Extensions=Hangul}\p{Script_Extensions=Bopomofo}]/u;

// CJK letters remain part of words and paths; only punctuation can separate prose from completions.
export const cjkPunctuationRegex = new RegExp(
	`(?:(?=\\p{Punctuation})${cjkBreakRegex.source}|[，．：；！？（）［］｛｝“”‘’…—])`,
	"u",
);
export const autocompleteSeparatorRegex = new RegExp(`(?:\\s|${cjkPunctuationRegex.source})`, "u");
export const autocompleteBoundaryRegex = new RegExp(`(?:^|${autocompleteSeparatorRegex.source})`, "u");

function isPrintableAscii(str: string): boolean {
	return !/[^\x20-\x7e]/.test(str);
}

/** End of the visible run at start. An incomplete escape stays in the run as text. */
function textRunEnd(text: string, start: number): number {
	let end = text.indexOf("\x1b", start + 1);
	while (end !== -1 && !ansiCodeLength(text, end)) end = text.indexOf("\x1b", end + 1);
	return end < 0 ? text.length : end;
}

function truncateFragmentToWidth(text: string, maxWidth: number): { text: string; width: number } {
	if (maxWidth <= 0 || text.length === 0) {
		return { text: "", width: 0 };
	}

	if (isPrintableAscii(text)) {
		const clipped = text.slice(0, maxWidth);
		return { text: clipped, width: clipped.length };
	}

	const hasAnsi = text.includes("\x1b");
	const hasTabs = text.includes("\t");
	if (!hasAnsi && !hasTabs) {
		let result = "";
		let width = 0;
		if (isStandaloneRun(text)) {
			for (const segment of text) {
				const w = graphemeWidth(segment);
				if (width + w > maxWidth) {
					break;
				}
				result += segment;
				width += w;
			}
		} else {
			for (const { segment } of getGraphemeSegmenter().segment(text)) {
				const w = graphemeWidth(segment);
				if (width + w > maxWidth) {
					break;
				}
				result += segment;
				width += w;
			}
		}
		return { text: result, width };
	}

	let result = "";
	let width = 0;
	let i = 0;
	let pendingAnsi = "";

	while (i < text.length) {
		const ansi = extractAnsiCode(text, i);
		if (ansi) {
			pendingAnsi += ansi.code;
			i += ansi.length;
			continue;
		}

		if (text[i] === "\t") {
			if (width + 3 > maxWidth) {
				break;
			}
			if (pendingAnsi) {
				result += pendingAnsi;
				pendingAnsi = "";
			}
			result += "\t";
			width += 3;
			i++;
			continue;
		}

		let end = i;
		while (end < text.length && text[end] !== "\t") {
			const nextAnsi = extractAnsiCode(text, end);
			if (nextAnsi) {
				break;
			}
			end++;
		}

		const run = text.slice(i, end);
		if (isStandaloneRun(run)) {
			for (const segment of run) {
				const w = graphemeWidth(segment);
				if (width + w > maxWidth) {
					return { text: result, width };
				}
				if (pendingAnsi) {
					result += pendingAnsi;
					pendingAnsi = "";
				}
				result += segment;
				width += w;
			}
		} else {
			for (const { segment } of getGraphemeSegmenter().segment(run)) {
				const w = graphemeWidth(segment);
				if (width + w > maxWidth) {
					return { text: result, width };
				}
				if (pendingAnsi) {
					result += pendingAnsi;
					pendingAnsi = "";
				}
				result += segment;
				width += w;
			}
		}
		i = end;
	}

	return { text: result, width };
}

function finalizeTruncatedResult(
	prefix: string,
	prefixWidth: number,
	ellipsis: string,
	ellipsisWidth: number,
	maxWidth: number,
	pad: boolean,
): string {
	const reset = "\x1b[0m";
	const hyperlinkClose = getActiveOsc8Close(prefix);
	const visibleWidth = prefixWidth + ellipsisWidth;
	let result: string;

	if (ellipsis.length > 0) {
		result = `${prefix}${hyperlinkClose}${reset}${ellipsis}${reset}`;
	} else {
		result = `${prefix}${hyperlinkClose}${reset}`;
	}

	return pad ? result + " ".repeat(Math.max(0, maxWidth - visibleWidth)) : result;
}

/** Whether a grapheme cluster matches `rgiEmojiRegex`. Exported for its test. */
export function isRgiEmoji(segment: string): boolean {
	const singleCodePoint = segment.length === (segment.codePointAt(0)! > 0xffff ? 2 : 1);
	return singleCodePoint ? singleCodePointEmojiRegex.test(segment) : rgiEmojiRegex.test(segment);
}

/**
 * Whether a UTF-16 unit always forms a grapheme cluster on its own: printable ASCII, Latin,
 * punctuation, arrows, box drawing, symbols, kana, CJK ideographs and fullwidth forms. These ranges
 * hold no combining marks, joiners, variation selectors, Hangul jamo or surrogates, so no Unicode
 * grapheme rule can join two of them.
 */
function isStandaloneGraphemeUnit(code: number): boolean {
	if (code >= 0x20 && code <= 0x7e) return true;
	if (code >= 0xa0 && code <= 0x2ff) return code !== 0xad;
	if (code >= 0x2010 && code <= 0x2bff) {
		// Line and bidi controls, invisible operators and combining marks for symbols.
		return (
			!(code >= 0x2028 && code <= 0x202e) &&
			!(code >= 0x2060 && code <= 0x206f) &&
			!(code >= 0x20d0 && code <= 0x20ff)
		);
	}
	return (
		(code >= 0x3040 && code <= 0x3096) ||
		(code >= 0x30a0 && code <= 0x30ff) ||
		(code >= 0x4e00 && code <= 0x9fff) ||
		(code >= 0xff01 && code <= 0xff60)
	);
}

function isStandaloneGraphemeRun(text: string): boolean {
	for (let i = 0; i < text.length; i++) {
		if (!isStandaloneGraphemeUnit(text.charCodeAt(i))) return false;
	}
	return true;
}

/**
 * Whether a code point outside the BMP forms a grapheme cluster on its own between standalone units: pictographs and
 * emoji, without the emoji modifiers (which extend the emoji before them) and the regional indicators (which pair).
 * Only a joiner, a variation selector or a modifier can join such an emoji to its neighbours, and none of those is
 * standalone. test/standalone-graphemes.test.ts checks every code point against Intl.Segmenter.
 */
function isStandalonePictograph(codePoint: number): boolean {
	return (
		(codePoint >= 0x1f300 && codePoint <= 0x1f3fa) ||
		(codePoint >= 0x1f400 && codePoint <= 0x1f64f) ||
		(codePoint >= 0x1f680 && codePoint <= 0x1f6ff) ||
		(codePoint >= 0x1f900 && codePoint <= 0x1f9ff) ||
		(codePoint >= 0x1fa70 && codePoint <= 0x1faff)
	);
}

/** Code units of the standalone character at `index`: 1 (a standalone unit), 2 (a standalone pictograph) or 0. */
function standaloneLength(text: string, index: number): number {
	const code = text.charCodeAt(index);
	if (isStandaloneGraphemeUnit(code)) return 1;
	// High surrogates of U+1F000..U+1FBFF.
	if (code >= 0xd83c && code <= 0xd83e && isStandalonePictograph(text.codePointAt(index)!)) return 2;
	return 0;
}

/** Whether the text before `index` is empty or ends with a standalone character. */
function standaloneBefore(text: string, index: number): boolean {
	if (index === 0) return true;
	const code = text.charCodeAt(index - 1);
	if (isStandaloneGraphemeUnit(code)) return true;
	return code >= 0xdc00 && code <= 0xdfff && index >= 2 && standaloneLength(text, index - 2) === 2;
}

/**
 * Segment a run that contains complex units. A standalone character whose neighbours are standalone too is its own
 * cluster, since no grapheme rule joins two standalone characters. Only the stretches between such characters go
 * through Intl.Segmenter; cutting there is safe because both cut edges are boundaries. A reply with an emoji in it then
 * never needs Intl.Segmenter, whose first use loads ICU's data.
 */
function* segmentGraphemes(text: string): Generator<string> {
	let complexStart = -1;
	let i = 0;
	while (i < text.length) {
		const length = standaloneLength(text, i);
		if (
			length > 0 &&
			standaloneBefore(text, i) &&
			(i + length === text.length || standaloneLength(text, i + length) > 0)
		) {
			if (complexStart >= 0) {
				for (const { segment } of getGraphemeSegmenter().segment(text.slice(complexStart, i))) yield segment;
				complexStart = -1;
			}
			yield length === 1 ? text[i]! : text.slice(i, i + 2);
			i += length;
		} else {
			if (complexStart < 0) complexStart = i;
			i++;
		}
	}
	if (complexStart >= 0) {
		for (const { segment } of getGraphemeSegmenter().segment(text.slice(complexStart))) yield segment;
	}
}

/**
 * Grapheme clusters of a text run without ANSI codes. Standalone units are split per character:
 * Intl.Segmenter costs about 15x more than the slicing it serves, which made one non-ASCII
 * character slow down every row of a fullscreen frame.
 */
function graphemesOf(text: string): Iterable<string> {
	return isStandaloneGraphemeRun(text) ? text : segmentGraphemes(text);
}

/**
 * Whether every code point of `text` is a grapheme cluster of its own: it holds standalone characters only. Measuring,
 * truncating and wrapping split such a run per code point without Intl.Segmenter (whose first use loads ICU's data) and give
 * any other run to Intl.Segmenter whole, as one call: cutting it around its standalone stretches, as graphemesOf does for
 * slicing, costs more than that one call for text that needs the segmenter anyway (Cyrillic, Arabic, Indic). Each loops over
 * the run or over the segmenter's segments, never over a mix of both in one loop, which made the loops slower.
 */
function isStandaloneRun(text: string): boolean {
	for (let i = 0; i < text.length; i++) {
		const length = standaloneLength(text, i);
		if (length === 0) return false;
		i += length - 1;
	}
	return true;
}

/** The code points of `text` when it is a standalone run (isStandaloneRun), else undefined. Exported for its test. */
export function standaloneClustersForTest(text: string): string[] | undefined {
	return isStandaloneRun(text) ? [...text] : undefined;
}

/** graphemesOf as an array. Exported for its test. */
export function graphemesOfForTest(text: string): string[] {
	return [...graphemesOf(text)];
}

// Widths of single non-ASCII BMP characters; bounded by the 64K code units.
const singleUnitWidths = new Map<number, number>();

/**
 * Calculate the terminal width of a single grapheme cluster.
 * Based on code from the string-width library, but includes a possible-emoji
 * check to avoid running the RGI_Emoji regex unnecessarily.
 */
function graphemeWidth(segment: string): number {
	if (segment.length !== 1) return clusterWidth(segment);
	const code = segment.charCodeAt(0);
	if (code >= 0x20 && code <= 0x7e) return 1;
	if (code === 0x09) return 3;
	let width = singleUnitWidths.get(code);
	if (width === undefined) {
		width = clusterWidth(segment);
		singleUnitWidths.set(code, width);
	}
	return width;
}

function clusterWidth(segment: string): number {
	// Some marks occupy cells even without a base character.
	if (terminalSpacingMarkRegex.test(segment)) {
		return [...segment].length;
	}

	// Zero-width clusters
	if (zeroWidthRegex.test(segment)) {
		return 0;
	}

	// Emoji check with pre-filter
	if (couldBeEmoji(segment) && isRgiEmoji(segment)) {
		return 2;
	}

	// Get base visible codepoint
	const base = segment.replace(leadingNonPrintingRegex, "");
	const cp = base.codePointAt(0);
	if (cp === undefined) {
		return 0;
	}

	// Regional indicator symbols (U+1F1E6..U+1F1FF) are often rendered as
	// full-width emoji in terminals, even when isolated during streaming.
	// Keep width conservative (2) to avoid terminal auto-wrap drift artifacts.
	if (cp >= 0x1f1e6 && cp <= 0x1f1ff) {
		return 2;
	}

	let width = eastAsianWidth(cp);
	if (base.length === (cp > 0xffff ? 2 : 1)) return width;

	// Intl.Segmenter can group multiple terminal-spacing code points into one
	// grapheme. Count trailing visible code points that terminals may allocate
	// cells for: Indic consonants after marks, halfwidth/fullwidth forms, and
	// Thai/Lao AM vowels.
	let followsMark = false;
	const chars = [...base];
	for (const char of chars.slice(1)) {
		if (terminalSpacingMarkRegex.test(char)) {
			width += 1;
			followsMark = false;
		} else if (markCharRegex.test(char)) {
			followsMark = true;
		} else if (!nonPrintingCharRegex.test(char)) {
			const c = char.codePointAt(0)!;
			if (followsMark || (c >= 0xff00 && c <= 0xffef)) {
				// halfwidth + fullwidth forms
				width += eastAsianWidth(c);
			} else if (c === 0x0e33 || c === 0x0eb3) {
				width += 1;
			}
			followsMark = false;
		}
	}

	return width;
}

/** The width of a standalone run (isStandaloneRun): its code points are its clusters. */
function standaloneRunWidth(text: string): number {
	let width = 0;
	for (const segment of text) width += graphemeWidth(segment);
	return width;
}

/** The width of a run of any text, cluster by cluster as Intl.Segmenter splits it. */
function segmentedWidth(text: string): number {
	let width = 0;
	for (const { segment } of getGraphemeSegmenter().segment(text)) width += graphemeWidth(segment);
	return width;
}

/**
 * Calculate the visible width of a string in terminal columns.
 */
export function visibleWidth(str: string): number {
	if (str.length === 0) {
		return 0;
	}

	// Fast path: printable ASCII, tabs, and ANSI escape sequences. Styled lines take this path, so
	// re-rendering after a theme change does not run grapheme segmentation on every line.
	const asciiWidth = asciiVisibleWidth(str);
	if (asciiWidth !== -1) {
		return asciiWidth;
	}

	// The cache outlives a session. Budget its total string lengths so repeated
	// long Unicode rows can hit the cache without pinning hundreds of large pastes.
	const cacheable = str.length <= WIDTH_CACHE_CODE_UNITS;
	const cached = cacheable ? widthCache.get(str) : undefined;
	if (cached !== undefined) {
		return cached;
	}

	const clean = stripTerminalSequences(str.includes("\t") ? str.replaceAll("\t", "   ") : str);

	// Calculate width
	const width = isStandaloneRun(clean) ? standaloneRunWidth(clean) : segmentedWidth(clean);

	// Cache result
	if (!cacheable) return width;
	while (widthCache.size >= WIDTH_CACHE_SIZE || widthCacheUnits + str.length > WIDTH_CACHE_CODE_UNITS) {
		const oldest = widthCache.keys().next().value!;
		widthCache.delete(oldest);
		widthCacheUnits -= oldest.length;
	}
	widthCache.set(str, width);
	widthCacheUnits += str.length;

	return width;
}

/** Remove ANSI, OSC, and APC control sequences while preserving visible text. */
export function stripTerminalSequences(str: string): string {
	let copied = 0;
	let result = "";
	let esc = str.indexOf("\x1b");
	while (esc !== -1) {
		const length = ansiCodeLength(str, esc);
		if (length > 0) {
			result += str.slice(copied, esc);
			copied = esc + length;
			esc = copied;
		} else {
			esc++;
		}
		esc = str.indexOf("\x1b", esc);
	}
	return copied === 0 ? str : result + str.slice(copied);
}

/**
 * Text a terminal would act on rather than show: C0 controls but tab and line feed (a carriage return not before a line
 * feed included), DEL, and C1 controls. Searched from lastIndex.
 */
const TERMINAL_CONTROL_REGEX = /[\x00-\x08\x0b\x0c\x0e-\x1f\x7f-\x9f]|\r(?!\n)/g;

/**
 * A complete escape sequence (CSI; OSC, DCS, SOS, PM or APC up to its terminator; any other ESC sequence), a lone
 * carriage return, or one control character of TERMINAL_CONTROL_REGEX (a lone ESC among them).
 */
const TERMINAL_SEQUENCE_GLOBAL_REGEX =
	/\x1b\[[\x30-\x3f]*[\x20-\x2f]*[\x40-\x7e]|\x1b[\]PX^_][^\x07\x1b\x9c]*(?:\x07|\x1b\\|\x9c)|\x1b[\x20-\x2f]*[\x30-\x4f\x51-\x57\x59\x5a\x5c\x60-\x7e]|\r(?!\n)|[\x00-\x08\x0b\x0c\x0e-\x1f\x7f-\x9f]/g;

/**
 * Text from outside Pi (a model's reply, a tool call's arguments, a tool's or MCP server's output) made safe to draw:
 * escape sequences and control characters are removed, so the text cannot set the clipboard (OSC 52) or the window
 * title, add hyperlinks, move the cursor or clear lines (which would let it draw over what Pi drew, such as a tool's
 * header or a shell command line). Tabs and line breaks are kept; a carriage return alone becomes a line break, as
 * Markdown and wrapTextWithAnsi() read it. An escape sequence that is not complete loses its ESC, and the rest is shown
 * as text. Text without any of these is returned as it is.
 */
export function sanitizeTerminalText(text: string): string {
	TERMINAL_CONTROL_REGEX.lastIndex = 0;
	if (!TERMINAL_CONTROL_REGEX.test(text)) return text;
	return text.replace(TERMINAL_SEQUENCE_GLOBAL_REGEX, (match) => (match === "\r" ? "\n" : ""));
}

/**
 * At a lone ESC (lastIndex): the start of an escape sequence that runs to the end of the text, which more text could
 * complete (a CSI, OSC, DCS, SOS, PM, APC or other ESC sequence without its last character or terminator).
 */
const PENDING_ESCAPE_REGEX = /\x1b(?:\[[\x30-\x3f]*[\x20-\x2f]*|[\]PX^_][^\x07\x1b\x9c]*\x1b?|[\x20-\x2f]*)$/y;

/**
 * text.startsWith(prefix), comparing the part of `text` as long as `prefix` with it. A message streaming in is the text before
 * with the text added to it (`text += delta`): the engine keeps that as the two strings joined, not as one copy of both,
 * until it is read; it then copies all of it into one. The part of it that is the text before is that string itself, which
 * compares with the text before without reading either. (startsWith() reads all of `text`, and so copies it.)
 */
export function startsWithText(text: string, prefix: string): boolean {
	return text.length >= prefix.length && text.slice(0, prefix.length) === prefix;
}

/** What sanitizeTerminalTextFrom() made of a text: the start of what it makes of a longer text that begins with it. */
export interface SanitizedTerminalText {
	/** The text. */
	readonly raw: string;
	/** sanitizeTerminalText(raw). */
	readonly text: string;
	/** Whether `text` is `raw`: nothing was removed or replaced. */
	readonly unchanged: boolean;
	/**
	 * Where in `raw` no escape sequence or carriage return is pending: every text that begins with `raw` sanitises, up to
	 * there, to the first `safeLength` characters of `text`, and from there as if it began there.
	 */
	readonly safeRaw: number;
	readonly safeLength: number;
	/**
	 * When `text` is the text of the `previous` result given for it with more text after it: that text (empty when the
	 * text is the same). A message streaming in is lexed from what was lexed for it before and what was added.
	 */
	readonly added: string | undefined;
}

/**
 * sanitizeTerminalText(text), with what it made of a shorter text (`previous`). When `text` begins with that text (a
 * message streaming in), only what follows the last point where no escape sequence was pending is gone through, so the
 * cost of each chunk does not grow with the length of the message. The result is the same as sanitizeTerminalText()'s.
 *
 * Example: "a\x1b[3" then "a\x1b[31mb". The first sanitises to "a[3" (an escape sequence that is not complete loses its
 * ESC), but the ESC is pending: the second is gone through from the ESC, and gives "ab", as all of it would.
 */
export function sanitizeTerminalTextFrom(text: string, previous?: SanitizedTerminalText): SanitizedTerminalText {
	let start = 0;
	let prefix = "";
	let unchanged = true;
	// (Whether the result is the previous text with more after it.)
	let extendsPrevious = false;
	if (previous !== undefined && startsWithText(text, previous.raw)) {
		if (text.length === previous.raw.length) return { ...previous, added: "" };
		start = previous.safeRaw;
		unchanged = previous.unchanged;
		prefix =
			previous.safeLength === previous.text.length ? previous.text : previous.text.slice(0, previous.safeLength);
		extendsPrevious = prefix.length === previous.text.length;
	}

	// (Only the text from `start` is read: for a text made of the previous one and the text added to it, reading all of it
	// would copy all of it. No expression here looks behind or anchors at the start, so they match the same there.)
	const tail = start === 0 ? text : text.slice(start);
	TERMINAL_CONTROL_REGEX.lastIndex = 0;
	if (!TERMINAL_CONTROL_REGEX.test(tail)) {
		const sanitized = unchanged ? text : prefix + tail;
		return {
			raw: text,
			text: sanitized,
			unchanged,
			safeRaw: text.length,
			safeLength: sanitized.length,
			added: extendsPrevious ? tail : undefined,
		};
	}

	// As sanitizeTerminalText() replaces, from `start`, noting the first match that more text could make another: a lone
	// ESC that begins an escape sequence not complete yet, or a carriage return at the end (it is a line break unless a
	// line feed follows).
	// (Positions in `tail`.)
	let added = "";
	let last = 0;
	let safeRaw = -1;
	let safeLength = 0;
	// (Regular expressions as literals: one built from a string at run time is not compiled ahead of time.)
	const regex = TERMINAL_SEQUENCE_GLOBAL_REGEX;
	regex.lastIndex = 0;
	for (let match = regex.exec(tail); match !== null; match = regex.exec(tail)) {
		const at = match.index;
		const sequence = match[0];
		if (safeRaw < 0) {
			let pending = false;
			if (sequence === "\r") {
				pending = at + 1 === tail.length;
			} else if (sequence === "\x1b") {
				PENDING_ESCAPE_REGEX.lastIndex = at;
				pending = PENDING_ESCAPE_REGEX.test(tail);
			}
			if (pending) {
				safeRaw = start + at;
				safeLength = prefix.length + added.length + (at - last);
			}
		}
		added += tail.slice(last, at);
		if (sequence === "\r") added += "\n";
		last = at + sequence.length;
	}
	added += tail.slice(last);
	const sanitized = prefix + added;
	if (safeRaw < 0) {
		safeRaw = text.length;
		safeLength = sanitized.length;
	}
	return {
		raw: text,
		text: sanitized,
		unchanged: false,
		safeRaw,
		safeLength,
		added: extendsPrevious ? added : undefined,
	};
}

/**
 * Whether a link may be drawn as a clickable terminal hyperlink (OSC 8): a web or mail link, which only a browser or mail
 * program opens.
 */
export function isWebLink(url: string): boolean {
	return /^(?:https?|mailto):/i.test(url);
}

/** Longest URL drawn as a hyperlink: VTE ignores longer ones (2083 bytes). */
const MAX_HYPERLINK_URL_LENGTH = 2048;

/**
 * What a link to `url` may be a hyperlink (OSC 8) to, when `url` is a `file:` URL of an absolute path on this computer
 * (`file:///…`, `file://localhost/…`): the URL as parsed, with spaces and other characters percent-encoded. Such a link is
 * drawn with its URL printed after its text, as Pi draws the paths tools read and write, so the hyperlink opens what a
 * click on the printed URL opens. Undefined for anything else: a file on another computer (`file://server/share`, or
 * `file:////server/share`, a share Windows would sign in to), a URL without `//` (`file:C:/x`), one with an encoded
 * separator (`%2f`, `%5c`) or control character, one with control or format characters (which can make the printed URL
 * read as another), or one too long for terminals to keep.
 */
export function localFileLinkTarget(url: string): string | undefined {
	if (!/^file:\/\/(?:localhost)?\//i.test(url) || /[\p{Cc}\p{Cf}]/u.test(url) || !URL.canParse(url)) return undefined;
	const parsed = new URL(url);
	if (
		parsed.protocol !== "file:" ||
		parsed.hostname !== "" ||
		parsed.pathname.startsWith("//") ||
		/%(?:2f|5c|[01][0-9a-f]|7f|c2%[89][0-9a-f])/i.test(parsed.href) ||
		parsed.href.length > MAX_HYPERLINK_URL_LENGTH
	) {
		return undefined;
	}
	return parsed.href;
}

function extractAnsiCodes(str: string): string {
	let result = "";
	let i = str.indexOf("\x1b");
	while (i !== -1) {
		const length = ansiCodeLength(str, i);
		if (length > 0) {
			result += str.substring(i, i + length);
			i += length;
		} else {
			i++;
		}
		i = str.indexOf("\x1b", i);
	}
	return result;
}

// Same character set as String.prototype.trim()
const WHITESPACE_REGEX = /\s/;

/**
 * Whether a token's visible text is only whitespace. Codes closing a styled word attach to the following space,
 * e.g. "\x1b[22m ", so they are skipped. Stops at the first visible non-whitespace text without allocating a
 * stripped copy, because styled words reach this check on every wrap.
 */
function isWhitespaceToken(token: string): boolean {
	let i = 0;
	while (i < token.length) {
		const code = token.charCodeAt(i);
		if (code === 0x1b) {
			const length = ansiCodeLength(token, i);
			if (length === 0) return false; // A lone ESC is visible text
			i += length;
		} else if (code === 0x20 || (code >= 0x09 && code <= 0x0d)) {
			i++;
		} else if (code < 0x80 || !WHITESPACE_REGEX.test(token[i]!)) {
			return false;
		} else {
			i++;
		}
	}
	return true;
}

const CONTROL_CHARACTERS = /[\u0000-\u001f\u007f-\u009f]+/g;

/** Replace each run of C0 and C1 control characters (ESC, BEL, newlines, ...) with one space. */
export function replaceControlCharacters(str: string): string {
	return str.replace(CONTROL_CHARACTERS, " ");
}

interface GraphemeCellRange {
	start: number;
	end: number;
}

/** Return the terminal-cell range occupied by the grapheme at a visible column. */
export function getGraphemeCellRange(line: string, column: number): GraphemeCellRange | undefined {
	let currentCol = 0;
	let i = 0;
	while (i < line.length) {
		const ansi = extractAnsiCode(line, i);
		if (ansi) {
			i += ansi.length;
			continue;
		}
		const textEnd = textRunEnd(line, i);
		const run = line.slice(i, textEnd);
		if (isPrintableAscii(run)) {
			if (column >= currentCol && column < currentCol + run.length) {
				const start = currentCol + Math.floor(column - currentCol);
				return { start, end: start + 1 };
			}
			currentCol += run.length;
			i = textEnd;
			continue;
		}
		for (const segment of graphemesOf(run)) {
			const width = graphemeWidth(segment);
			if (width > 0 && column >= currentCol && column < currentCol + width) {
				return { start: currentCol, end: currentCol + width };
			}
			currentCol += width;
		}
		i = textEnd;
	}
	return undefined;
}

/** Return the OSC 8 hyperlink covering a visible terminal column. */
export function getOsc8LinkAtColumn(line: string, column: number): string | undefined {
	let activeUrl: string | undefined;
	let currentCol = 0;
	let i = 0;
	while (i < line.length) {
		const ansi = extractAnsiCode(line, i);
		if (ansi) {
			const hyperlink = /^\x1b\]8;[^;]*;([^\x07\x1b]*)(?:\x07|\x1b\\)$/.exec(ansi.code);
			if (hyperlink) activeUrl = hyperlink[1] || undefined;
			i += ansi.length;
			continue;
		}
		const textEnd = textRunEnd(line, i);
		const run = line.slice(i, textEnd);
		if (isPrintableAscii(run)) {
			if (column >= currentCol && column < currentCol + run.length) return activeUrl;
			currentCol += run.length;
			i = textEnd;
			continue;
		}
		for (const segment of graphemesOf(run)) {
			const width = segment === "\t" ? 3 : graphemeWidth(segment);
			if (column >= currentCol && column < currentCol + width) return activeUrl;
			currentCol += width;
		}
		i = textEnd;
	}
	return undefined;
}

/**
 * Normalize text for terminal output without changing logical editor content.
 * Some terminals render precomposed Thai/Lao AM vowels inconsistently during
 * differential repaint. Their compatibility decompositions have the same cell
 * width but avoid stale-cell artifacts in terminal renderers. Visible tabs are
 * expanded to the fixed width used by layout so terminal tab stops cannot wrap
 * a logical line, while tabs inside terminal string sequences stay untouched.
 */
const THAI_LAO_AM_REGEX = /[\u0e33\u0eb3]/;
const THAI_LAO_AM_GLOBAL_REGEX = /[\u0e33\u0eb3]/g;

export function normalizeTerminalOutput(str: string): string {
	let normalized = str;
	if (THAI_LAO_AM_REGEX.test(normalized)) {
		normalized = normalized.replace(THAI_LAO_AM_GLOBAL_REGEX, (char) =>
			char === "\u0e33" ? "\u0e4d\u0e32" : "\u0ecd\u0eb2",
		);
	}
	let tab = normalized.indexOf("\t");
	if (tab === -1) return normalized;
	let escapeIndex = normalized.indexOf("\x1b");
	if (escapeIndex === -1) return normalized.replaceAll("\t", "   ");

	let result = "";
	let start = 0;
	while (tab !== -1) {
		if (escapeIndex !== -1 && escapeIndex < tab) {
			// Keep tabs inside recognized control sequences, including OSC hyperlink targets.
			const end = escapeIndex + (ansiCodeLength(normalized, escapeIndex) || 1);
			if (tab < end) tab = normalized.indexOf("\t", end);
			escapeIndex = normalized.indexOf("\x1b", end);
		} else {
			result += normalized.slice(start, tab) + "   ";
			start = tab + 1;
			tab = normalized.indexOf("\t", start);
		}
	}
	return start === 0 ? normalized : result + normalized.slice(start);
}

/**
 * Extract ANSI escape sequences from a string at the given position.
 */
export function extractAnsiCode(str: string, pos: number): { code: string; length: number } | null {
	const length = ansiCodeLength(str, pos);
	return length > 0 ? { code: str.substring(pos, pos + length), length } : null;
}

const ASCII_WIDTH_SPECIAL = /[^\x20-\x7e]/g;

/**
 * Width of a string made of printable ASCII, tabs, and ANSI escape sequences, or -1 if it contains
 * something that needs grapheme segmentation. Matches `visibleWidth` for those strings without allocating.
 */
function asciiVisibleWidth(str: string): number {
	let width = 0;
	let i = 0;
	while (i < str.length) {
		let code = str.charCodeAt(i);
		if (code >= 0x20 && code <= 0x7e) {
			// Count the whole printable span before interpreting the next escape or Unicode character.
			ASCII_WIDTH_SPECIAL.lastIndex = i + 1;
			if (!ASCII_WIDTH_SPECIAL.test(str)) return width + str.length - i;
			const end = ASCII_WIDTH_SPECIAL.lastIndex - 1;
			width += end - i;
			i = end;
			code = str.charCodeAt(i);
		}
		if (code === 0x09) {
			width += 3;
			i++;
		} else if (code === 0x1b) {
			const length = ansiCodeLength(str, i);
			if (length === 0) return -1;
			i += length;
		} else if (
			(code >= 0xa0 && code <= 0x2ff && code !== 0xad) ||
			(code >= 0x2500 && code <= 0x25fc) ||
			(code >= 0x2010 && code <= 0x2027) ||
			(code >= 0x2190 && code <= 0x22ff)
		) {
			// Latin-1 and Latin Extended letters and signs, typographic punctuation, arrows and mathematical operators,
			// box drawing, block elements and geometric shapes: each is a grapheme of its own, one cell wide, whatever
			// is beside it here (anything that would join it, such as a combining mark or a variation selector, is
			// outside these ranges and takes the general path).
			width++;
			i++;
		} else if (
			(code >= 0x3400 && code <= 0x4dbf) ||
			(code >= 0x4e00 && code <= 0x9fff) ||
			(code >= 0xac00 && code <= 0xd7a3) ||
			(code >= 0x3041 && code <= 0x3096) ||
			(code >= 0x30a1 && code <= 0x30fa)
		) {
			// Ideographs, precomposed Hangul syllables and kana. A joining mark, jamo or
			// variation selector falls through and remeasures the whole string as graphemes.
			width += 2;
			i++;
		} else {
			return -1;
		}
	}
	return width;
}

/** Length of the ANSI/OSC/APC escape sequence starting at `pos`, or 0 if there is none. */
function ansiCodeLength(str: string, pos: number): number {
	if (pos >= str.length || str.charCodeAt(pos) !== 0x1b) return 0;

	const next = str[pos + 1];

	// CSI sequence: ESC [ ... m/G/K/H/J
	if (next === "[") {
		for (let j = pos + 2; j < str.length; j++) {
			const c = str.charCodeAt(j);
			// m, G, K, H, J
			if (c === 0x6d || c === 0x47 || c === 0x4b || c === 0x48 || c === 0x4a) return j + 1 - pos;
		}
		return 0;
	}

	// OSC sequence: ESC ] ... BEL or ESC ] ... ST (ESC \)
	// Used for hyperlinks (OSC 8), window titles, etc.
	// APC sequence: ESC _ ... BEL or ESC _ ... ST (ESC \)
	// Used for cursor marker and application-specific commands
	if (next === "]" || next === "_") {
		for (let j = pos + 2; j < str.length; j++) {
			const c = str.charCodeAt(j);
			if (c === 0x07) return j + 1 - pos;
			if (c === 0x1b && str[j + 1] === "\\") return j + 2 - pos;
		}
		return 0;
	}

	return 0;
}

type Osc8Terminator = "\x07" | "\x1b\\";

interface ActiveHyperlink {
	params: string;
	url: string;
	terminator: Osc8Terminator;
}

function parseOsc8Hyperlink(ansiCode: string): ActiveHyperlink | null | undefined {
	if (!ansiCode.startsWith("\x1b]8;")) {
		return undefined;
	}

	const terminator: Osc8Terminator = ansiCode.endsWith("\x07") ? "\x07" : "\x1b\\";
	const body = ansiCode.slice(4, terminator === "\x07" ? -1 : -2);
	const separatorIndex = body.indexOf(";");
	if (separatorIndex === -1) {
		return undefined;
	}

	const params = body.slice(0, separatorIndex);
	const url = body.slice(separatorIndex + 1);
	if (!url) {
		return null;
	}
	return { params, url, terminator };
}

function formatOsc8Hyperlink(hyperlink: ActiveHyperlink): string {
	return `\x1b]8;${hyperlink.params};${hyperlink.url}${hyperlink.terminator}`;
}

function formatOsc8Close(terminator: Osc8Terminator): string {
	return `\x1b]8;;${terminator}`;
}

function getActiveOsc8Close(prefix: string): string {
	if (!prefix.includes("\x1b]8;")) {
		return "";
	}

	let activeHyperlink: ActiveHyperlink | null = null;
	let i = 0;
	while (i < prefix.length) {
		const ansi = extractAnsiCode(prefix, i);
		if (ansi) {
			const hyperlink = parseOsc8Hyperlink(ansi.code);
			if (hyperlink !== undefined) {
				activeHyperlink = hyperlink;
			}
			i += ansi.length;
		} else {
			i++;
		}
	}
	return activeHyperlink ? formatOsc8Close(activeHyperlink.terminator) : "";
}

/**
 * Track active ANSI SGR codes to preserve styling across line breaks.
 */
class AnsiCodeTracker {
	// Track individual attributes separately so we can reset them specifically
	private bold = false;
	private dim = false;
	private italic = false;
	private underline = false;
	private blink = false;
	private inverse = false;
	private hidden = false;
	private strikethrough = false;
	private fgColor: string | null = null; // Stores the full code like "31" or "38;5;240"
	private bgColor: string | null = null; // Stores the full code like "41" or "48;5;240"
	private activeHyperlink: ActiveHyperlink | null = null;

	process(ansiCode: string): void {
		// OSC 8 hyperlink: \x1b]8;;<url>\x1b\\ (open) or \x1b]8;;\x1b\\ (close).
		// Preserve the original terminator because some terminals only make BEL-terminated
		// links clickable. OAuth login URLs use BEL, so reopening wrapped lines with ST
		// made only the first physical line clickable in those terminals.
		const hyperlink = parseOsc8Hyperlink(ansiCode);
		if (hyperlink !== undefined) {
			this.activeHyperlink = hyperlink;
			return;
		}

		if (!ansiCode.endsWith("m")) {
			return;
		}

		// Extract the parameters between \x1b[ and m
		const match = ansiCode.match(/\x1b\[([\d;]*)m/);
		if (!match) return;

		const params = match[1];
		if (params === "" || params === "0") {
			// Full reset
			this.reset();
			return;
		}

		// Parse parameters (can be semicolon-separated)
		const parts = params.split(";");
		let i = 0;
		while (i < parts.length) {
			const code = Number.parseInt(parts[i], 10);

			// Handle 256-color and RGB codes which consume multiple parameters
			if (code === 38 || code === 48) {
				// 38;5;N (256 color fg) or 38;2;R;G;B (RGB fg)
				// 48;5;N (256 color bg) or 48;2;R;G;B (RGB bg)
				if (parts[i + 1] === "5" && parts[i + 2] !== undefined) {
					// 256 color: 38;5;N or 48;5;N
					const colorCode = `${parts[i]};${parts[i + 1]};${parts[i + 2]}`;
					if (code === 38) {
						this.fgColor = colorCode;
					} else {
						this.bgColor = colorCode;
					}
					i += 3;
					continue;
				} else if (parts[i + 1] === "2" && parts[i + 4] !== undefined) {
					// RGB color: 38;2;R;G;B or 48;2;R;G;B
					const colorCode = `${parts[i]};${parts[i + 1]};${parts[i + 2]};${parts[i + 3]};${parts[i + 4]}`;
					if (code === 38) {
						this.fgColor = colorCode;
					} else {
						this.bgColor = colorCode;
					}
					i += 5;
					continue;
				}
			}

			// Standard SGR codes
			switch (code) {
				case 0:
					this.reset();
					break;
				case 1:
					this.bold = true;
					break;
				case 2:
					this.dim = true;
					break;
				case 3:
					this.italic = true;
					break;
				case 4:
					this.underline = true;
					break;
				case 5:
					this.blink = true;
					break;
				case 7:
					this.inverse = true;
					break;
				case 8:
					this.hidden = true;
					break;
				case 9:
					this.strikethrough = true;
					break;
				case 21:
					this.bold = false;
					break; // Some terminals
				case 22:
					this.bold = false;
					this.dim = false;
					break;
				case 23:
					this.italic = false;
					break;
				case 24:
					this.underline = false;
					break;
				case 25:
					this.blink = false;
					break;
				case 27:
					this.inverse = false;
					break;
				case 28:
					this.hidden = false;
					break;
				case 29:
					this.strikethrough = false;
					break;
				case 39:
					this.fgColor = null;
					break; // Default fg
				case 49:
					this.bgColor = null;
					break; // Default bg
				default:
					// Standard foreground colors 30-37, 90-97
					if ((code >= 30 && code <= 37) || (code >= 90 && code <= 97)) {
						this.fgColor = String(code);
					}
					// Standard background colors 40-47, 100-107
					else if ((code >= 40 && code <= 47) || (code >= 100 && code <= 107)) {
						this.bgColor = String(code);
					}
					break;
			}
			i++;
		}
	}

	private reset(): void {
		this.bold = false;
		this.dim = false;
		this.italic = false;
		this.underline = false;
		this.blink = false;
		this.inverse = false;
		this.hidden = false;
		this.strikethrough = false;
		this.fgColor = null;
		this.bgColor = null;
		// SGR reset does not affect OSC 8 hyperlink state
	}

	/** Clear all state for reuse. */
	clear(): void {
		this.reset();
		this.activeHyperlink = null;
	}

	getActiveCodes(): string {
		const codes: string[] = [];
		if (this.bold) codes.push("1");
		if (this.dim) codes.push("2");
		if (this.italic) codes.push("3");
		if (this.underline) codes.push("4");
		if (this.blink) codes.push("5");
		if (this.inverse) codes.push("7");
		if (this.hidden) codes.push("8");
		if (this.strikethrough) codes.push("9");
		if (this.fgColor) codes.push(this.fgColor);
		if (this.bgColor) codes.push(this.bgColor);

		let result = codes.length > 0 ? `\x1b[${codes.join(";")}m` : "";
		if (this.activeHyperlink) {
			result += formatOsc8Hyperlink(this.activeHyperlink);
		}
		return result;
	}

	getActiveBackgroundCode(): string {
		return this.bgColor ? `\x1b[${this.bgColor}m` : "";
	}

	hasActiveCodes(): boolean {
		return (
			this.bold ||
			this.dim ||
			this.italic ||
			this.underline ||
			this.blink ||
			this.inverse ||
			this.hidden ||
			this.strikethrough ||
			this.fgColor !== null ||
			this.bgColor !== null ||
			this.activeHyperlink !== null
		);
	}

	/**
	 * Get reset codes for attributes that need to be turned off at line end.
	 * Underline and strikethrough must be closed to prevent bleeding into padding.
	 * Active OSC 8 hyperlinks must be closed and re-opened on the next line.
	 * Returns empty string if no attributes need closing.
	 */
	getLineEndReset(): string {
		let result = "";
		if (this.underline) {
			result += "\x1b[24m"; // Underline off only
		}
		if (this.strikethrough) {
			result += "\x1b[29m"; // Strikethrough off only, re-opened at line start via getActiveCodes()
		}
		if (this.activeHyperlink) {
			result += formatOsc8Close(this.activeHyperlink.terminator); // Re-opened at line start via getActiveCodes()
		}
		return result;
	}
}

function updateTrackerFromText(text: string, tracker: AnsiCodeTracker): void {
	let i = text.indexOf("\x1b");
	while (i !== -1) {
		const length = ansiCodeLength(text, i);
		if (length > 0) {
			tracker.process(text.substring(i, i + length));
			i += length;
		} else {
			i++;
		}
		i = text.indexOf("\x1b", i);
	}
}

/** Return only the background color active at the end of an ANSI-styled string. */
export function getActiveBackgroundAnsi(text: string): string {
	const tracker = new AnsiCodeTracker();
	updateTrackerFromText(text, tracker);
	return tracker.getActiveBackgroundCode();
}

/**
 * Split text into words while keeping ANSI codes attached. `widths`, if given, gets each token's visible width where it is
 * known without measuring it again (printable ASCII and ANSI codes only: one column a character), and -1 elsewhere.
 */
function splitIntoTokensWithAnsi(text: string, widths?: number[]): string[] {
	const tokens: string[] = [];
	let current = "";
	let currentWidth = 0;
	let pendingAnsi = ""; // ANSI codes waiting to be attached to next visible content
	let currentKind: "space" | "word" | null = null;
	let i = 0;

	const flushCurrent = (): void => {
		if (!current) {
			return;
		}
		tokens.push(current);
		widths?.push(currentWidth);
		current = "";
		currentWidth = 0;
		currentKind = null;
	};

	const addSegment = (segment: string, segmentIsSpace: boolean, segmentWidth: number): void => {
		// Codes between a word and the space after it (usually the word's closing style) end that word.
		// Attached to the space instead, they make it a non-whitespace token, so a line wrapped there
		// started with the space. The codes stay in the same place in the output.
		if (pendingAnsi && segmentIsSpace && currentKind !== "space") {
			if (current) {
				current += pendingAnsi;
				pendingAnsi = "";
			} else if (tokens.length > 0) {
				tokens[tokens.length - 1] += pendingAnsi;
				pendingAnsi = "";
			}
		}

		const segmentKind = segmentIsSpace ? "space" : "word";
		if (current && currentKind !== segmentKind) {
			flushCurrent();
		}

		// Attach any pending ANSI codes to this visible character
		if (pendingAnsi) {
			current += pendingAnsi;
			pendingAnsi = "";
		}

		currentKind = segmentKind;
		current += segment;
		currentWidth = currentWidth < 0 || segmentWidth < 0 ? -1 : currentWidth + segmentWidth;
	};

	while (i < text.length) {
		const ansiResult = extractAnsiCode(text, i);
		if (ansiResult) {
			// Hold ANSI codes separately - they'll be attached to the next visible char
			pendingAnsi += ansiResult.code;
			i += ansiResult.length;
			continue;
		}

		// Visible text runs up to the next escape sequence.
		const end = textRunEnd(text, i);

		const chunk = text.slice(i, end);
		// Printable ASCII characters are single graphemes, so skip the segmenter for them.
		if (isPrintableAscii(chunk)) {
			// A run of spaces, or of other characters, goes where its characters would one by one: the first one decides,
			// and the others only add to the same token.
			let start = 0;
			while (start < chunk.length) {
				const isSpace = chunk.charCodeAt(start) === 0x20;
				let runEnd = start + 1;
				while (runEnd < chunk.length && (chunk.charCodeAt(runEnd) === 0x20) === isSpace) runEnd++;
				addSegment(
					start === 0 && runEnd === chunk.length ? chunk : chunk.slice(start, runEnd),
					isSpace,
					runEnd - start,
				);
				start = runEnd;
			}
		} else {
			const take = (segment: string): void => {
				const segmentIsSpace = segment === " ";
				if (!segmentIsSpace && cjkBreakRegex.test(segment)) {
					flushCurrent();
					const token = pendingAnsi + segment;
					pendingAnsi = "";
					tokens.push(token);
					widths?.push(-1);
					return;
				}
				addSegment(segment, segmentIsSpace, -1);
			};
			if (isStandaloneRun(chunk)) {
				for (const segment of chunk) take(segment);
			} else {
				for (const { segment } of getGraphemeSegmenter().segment(chunk)) take(segment);
			}
		}

		i = end;
	}

	// Handle any remaining pending ANSI codes (attach to last token)
	if (pendingAnsi) {
		if (current) {
			current += pendingAnsi;
		} else if (tokens.length > 0) {
			tokens[tokens.length - 1] += pendingAnsi;
		} else {
			current = pendingAnsi;
		}
	}

	if (current) {
		tokens.push(current);
		widths?.push(currentWidth);
	}

	return tokens;
}

/** For tests: the tokens wrapTextWithAnsi() wraps a line at, and the widths it knows of them. */
export function splitIntoTokensWithAnsiForTest(text: string, widths?: number[]): string[] {
	return splitIntoTokensWithAnsi(text, widths);
}

/**
 * For tests: the tokens scanPrintableAscii() finds in a line of printable ASCII and escape sequences, each with its width
 * and whether it is spaces and has escape sequences; undefined for any other line.
 */
export function scanPrintableAsciiForTest(
	text: string,
): { width: number; tokens: { text: string; width: number; spaces: boolean; codes: boolean }[] } | undefined {
	const width = scanPrintableAscii(text);
	if (width === -1) return undefined;
	const tokens: { text: string; width: number; spaces: boolean; codes: boolean }[] = [];
	let start = 0;
	for (let index = 0; index < scannedCount; index++) {
		const info = scannedInfo[index]!;
		tokens.push({
			text: text.slice(start, scannedEnds[index]),
			width: info >> 2,
			spaces: (info & 1) !== 0,
			codes: (info & 2) !== 0,
		});
		start = scannedEnds[index]!;
	}
	return { width, tokens };
}

/**
 * Wrap text with ANSI codes preserved.
 *
 * ONLY does word wrapping - NO padding, NO background colors.
 * Returns lines where each line is <= width visible chars.
 * Active ANSI codes are preserved across line breaks.
 *
 * @param text - Text to wrap (may contain ANSI codes and newlines)
 * @param width - Maximum visible width per line
 * @returns Array of wrapped lines (NOT padded to width)
 */
/**
 * Flatten cached lines. V8 keeps a string built by concatenation as a tree of its parts until something reads it
 * whole, and a cached line kept as such a tree retains several times its own size. Converting a string to a number
 * reads it whole, so V8 flattens it in place; the strings' values do not change.
 */
/** internLine()'s lines, by their text. */
const internedLines = new Map<string, string>();
/** Lines whose text before padding is at most this long are drawn alike often enough to be interned (internLine()). */
export const INTERNED_LINE_LENGTH = 32;
/** How many lines internLine() keeps before it starts again. */
const INTERNED_LINES_KEPT = 1024;
// Most padded rows fit in 256 UTF-16 code units. One synchronous cache insertion uses the scratch space at a time.
const ROW_COPY_BYTES = 512;
let rowCopyBuffer: Buffer | undefined;

/**
 * The line drawn for `line` that is kept for lines of the same text: for the short lines many components draw alike (a
 * blank line of a width, a line of background only, a code fence), so that a long transcript keeps one copy of each instead
 * of one per line (2,292 entries: about 12,000 such lines, 3 MB). Bounded: it starts again once it holds
 * INTERNED_LINES_KEPT lines. The text is the same: only which string holds it differs.
 */
export function internLine(line: string): string {
	const known = internedLines.get(line);
	if (known !== undefined) return known;
	if (internedLines.size >= INTERNED_LINES_KEPT) internedLines.clear();
	// A short substring can share the entire tool output it came from. Keep a copy
	// of its UTF-16 code units so the cache and its callers can release that output.
	let owned: string;
	if (typeof Buffer === "undefined") {
		owned = line.split("").join("");
	} else if (line.length <= ROW_COPY_BYTES / 2) {
		rowCopyBuffer ??= Buffer.allocUnsafe(ROW_COPY_BYTES);
		const length = rowCopyBuffer.write(line, 0, line.length * 2, "utf16le");
		owned = rowCopyBuffer.toString("utf16le", 0, length);
	} else {
		owned = Buffer.from(line, "utf16le").toString("utf16le");
	}
	internedLines.set(owned, owned);
	return owned;
}

export function flattenLines(lines: readonly string[], start = 0): void {
	for (let i = start; i < lines.length; i++) Number(lines[i]);
}

/** A scanned ASCII+ANSI wrap stopped at a row boundary with no active ANSI or OSC state. */
export interface AnsiWrapCheckpoint {
	width: number;
	prefix: string;
	offset: number;
	rows: number;
}

interface StreamingWrap {
	previous?: AnsiWrapCheckpoint;
	next?: AnsiWrapCheckpoint;
	shared: number;
}

/** Wraps an extending paragraph, returning only rows after its unchanged prefix when a checkpoint can be reused. */
export function wrapStreamingParagraph(
	text: string,
	width: number,
	previous?: AnsiWrapCheckpoint,
): StreamingWrap & { lines: string[] } {
	const wrap: StreamingWrap = { previous, shared: 0 };
	const lines =
		text.includes("\n") || text.includes("\r") ? wrapTextWithAnsi(text, width) : wrapSingleLine(text, width, wrap);
	return { ...wrap, lines };
}

export function wrapTextWithAnsi(text: string, width: number): string[] {
	if (!text) {
		return [""];
	}

	// One line (what Markdown and most components wrap): no ANSI state to carry over to a line after it.
	if (text.indexOf("\n") === -1 && text.indexOf("\r") === -1) {
		return wrapSingleLine(text, width);
	}

	// Handle newlines by processing each line separately
	// Track ANSI state across lines so styles carry over after literal newlines
	const inputLines = text.split(/\r\n|\r|\n/);
	const result: string[] = [];
	const tracker = new AnsiCodeTracker();

	for (let index = 0; index < inputLines.length; index++) {
		const inputLine = inputLines[index]!;
		// Prepend active ANSI codes from previous lines (except for first line)
		const prefix = result.length > 0 ? tracker.getActiveCodes() : "";
		const wrappedLines = wrapSingleLine(prefix + inputLine, width);
		for (const wrappedLine of wrappedLines) {
			result.push(wrappedLine);
		}
		// Update tracker with codes from this line for the next one (the last line has none after it)
		if (index < inputLines.length - 1) updateTrackerFromText(inputLine, tracker);
	}

	return result.length > 0 ? result : [""];
}

/**
 * The tokens of the line scanPrintableAscii() read last: where each ends, and its width with what it holds (bit 0: it is
 * spaces, bit 1: it has escape sequences). Kept from line to line; only wrapSingleLine() reads them, before scanning again.
 */
let scannedEnds = new Int32Array(64);
let scannedInfo = new Int32Array(64);
let scannedCount = 0;

function addScannedToken(end: number, width: number, spaces: boolean, codes: boolean): void {
	if (scannedCount === scannedEnds.length) {
		const ends = new Int32Array(scannedCount * 2);
		ends.set(scannedEnds);
		scannedEnds = ends;
		const info = new Int32Array(scannedCount * 2);
		info.set(scannedInfo);
		scannedInfo = info;
	}
	scannedEnds[scannedCount] = end;
	scannedInfo[scannedCount] = (width << 2) | (codes ? 2 : 0) | (spaces ? 1 : 0);
	scannedCount++;
}

/**
 * The visible width of a line of printable ASCII and escape sequences, with its tokens (those splitIntoTokensWithAnsi()
 * gives it) left in scannedEnds and scannedInfo; -1 for any other line. Each token is a piece of the line, cut where a run
 * of spaces meets a run of other characters: codes between a word and the space after it end the word, codes between a
 * space and the word after it start the word, and codes between two runs of the same kind stay inside the token. One
 * pass measures the line and finds its tokens, where wrapping it took a pass to measure, one to split the line into
 * strings built a run at a time, and an array entry for each token.
 */
function scanPrintableAscii(text: string): number {
	scannedCount = 0;
	let total = 0;
	let tokenStart = 0;
	let tokenWidth = 0;
	let tokenCodes = false;
	// The kind of the token being read: 0 before any visible character, 1 spaces, 2 other characters.
	let kind = 0;
	let i = 0;
	while (i < text.length) {
		const code = text.charCodeAt(i);
		if (code === 0x1b) {
			const codesStart = i;
			do {
				const length = ansiCodeLength(text, i);
				if (length === 0) return -1;
				i += length;
			} while (i < text.length && text.charCodeAt(i) === 0x1b);
			if (i < text.length) {
				const nextKind = text.charCodeAt(i) === 0x20 ? 1 : 2;
				if (kind !== 0 && kind !== nextKind) {
					if (kind === 2) {
						// Codes between a word and the space after it end the word.
						addScannedToken(i, tokenWidth, false, true);
						tokenStart = i;
						tokenCodes = false;
					} else {
						// Codes between a space and the word after it start the word.
						addScannedToken(codesStart, tokenWidth, true, tokenCodes);
						tokenStart = codesStart;
						tokenCodes = true;
					}
					tokenWidth = 0;
					kind = nextKind;
					continue;
				}
			}
			tokenCodes = true;
			continue;
		}
		if (code < 0x20 || code > 0x7e) return -1;
		const charKind = code === 0x20 ? 1 : 2;
		if (kind !== charKind) {
			if (kind !== 0) {
				addScannedToken(i, tokenWidth, kind === 1, tokenCodes);
				tokenStart = i;
				tokenWidth = 0;
				tokenCodes = false;
			}
			kind = charKind;
		}
		tokenWidth++;
		total++;
		i++;
	}
	if (tokenStart < text.length) addScannedToken(text.length, tokenWidth, kind !== 2, tokenCodes);
	return total;
}

/**
 * wrapSingleLine() of a line scanPrintableAscii() has just read, from its tokens: the same lines, without the passes
 * over code-free tokens that only look for codes. Undefined when a token is wider than the line, which the general path
 * breaks up.
 */
function wrapScannedLine(
	line: string,
	width: number,
	checkpoint?: StreamingWrap,
	previous?: AnsiWrapCheckpoint,
): string[] | undefined {
	const offset = previous?.offset ?? 0;
	const wrapped: string[] = [];
	const tracker = new AnsiCodeTracker();
	// (A tracker that has seen no codes has none to give.)
	let tracked = false;
	let currentLine = "";
	let currentVisibleLength = 0;
	let start = offset;
	let nextOffset = 0;
	let nextRows = 0;
	let nextPrefixEnd = 0;
	for (let index = 0; index < scannedCount; index++) {
		const end = scannedEnds[index]! + offset;
		const info = scannedInfo[index]!;
		const tokenStart = start;
		const token = line.slice(start, end);
		start = end;
		const tokenVisibleLength = info >> 2;
		const isWhitespace = (info & 1) !== 0;
		const hasCodes = (info & 2) !== 0;
		if (tokenVisibleLength > width && !isWhitespace) return undefined;

		const totalNeeded = currentVisibleLength + tokenVisibleLength;
		if (totalNeeded > width && currentVisibleLength > 0) {
			let lineToWrap = currentLine.trimEnd();
			if (isWhitespace && hasCodes) {
				lineToWrap += extractAnsiCodes(token);
				updateTrackerFromText(token, tracker);
				tracked = true;
			}
			if (tracked) {
				const lineEndReset = tracker.getLineEndReset();
				if (lineEndReset) {
					lineToWrap += lineEndReset;
				}
			}
			wrapped.push(lineToWrap);
			if (isWhitespace) {
				currentLine = tracked ? tracker.getActiveCodes() : "";
				currentVisibleLength = 0;
				continue;
			}
			// The break happened before this word and consumed no part of it. Two later tokens
			// ensure the word and its delimiter are settled even if more text is appended.
			if (checkpoint && index + 2 < scannedCount && !tracker.hasActiveCodes()) {
				nextOffset = tokenStart;
				nextRows = wrapped.length;
				nextPrefixEnd = scannedEnds[index + 1]! + offset;
			}
			currentLine = tracked ? tracker.getActiveCodes() + token : token;
			currentVisibleLength = tokenVisibleLength;
		} else if (isWhitespace && totalNeeded > width && hasCodes) {
			currentLine += extractAnsiCodes(token);
		} else {
			currentLine += token;
			currentVisibleLength += tokenVisibleLength;
		}

		if (hasCodes) {
			updateTrackerFromText(token, tracker);
			tracked = true;
		}
	}

	if (currentLine) {
		wrapped.push(currentLine);
	}

	if (checkpoint) {
		checkpoint.next =
			nextOffset > 0
				? {
						width,
						prefix: line.slice(0, nextPrefixEnd),
						offset: nextOffset,
						rows: (previous?.rows ?? 0) + nextRows,
					}
				: previous;
	}
	return wrapped.length > 0 ? wrapped.map((wrappedLine) => wrappedLine.trimEnd()) : [""];
}

/** A plain ASCII output row is one contiguous slice, including any indentation.
 * Emit it at the next word boundary or cut a single overlong word at the column limit.
 */
function wrapPlainAscii(line: string, width: number): string[] {
	if (line.length <= width) return [line];
	const rows: string[] = [];
	let rowStart = 0;
	let pos = 0;
	while (pos < line.length) {
		const space = line.charCodeAt(pos) === 32;
		let end: number;
		if (space) {
			end = pos + 1;
			while (end < line.length && line.charCodeAt(end) === 32) end++;
		} else {
			end = line.indexOf(" ", pos);
			if (end < 0) end = line.length;
		}
		if (end - rowStart > width && pos > rowStart) {
			rows.push(line.slice(rowStart, pos).trimEnd());
			rowStart = space ? end : pos;
		}
		if (!space) {
			while (end - rowStart > width) {
				rows.push(line.slice(rowStart, rowStart + width));
				rowStart += width;
			}
		}
		pos = end;
	}
	if (rowStart < line.length) rows.push(line.slice(rowStart).trimEnd());
	// A short slice can keep a multi-megabyte input alive after wrapping drops
	// its whitespace. Detach the small result in this case; plain rows contain no LF.
	if (line.length >= 65536 && rows.length * width < line.length / 4) {
		return new TextDecoder().decode(new TextEncoder().encode(rows.join("\n"))).split("\n");
	}
	return rows;
}

function wrapSingleLine(line: string, width: number, checkpoint?: StreamingWrap): string[] {
	if (!line) {
		return [""];
	}
	// ASCII and its control sequences occupy at most one cell per code unit, except for tabs.
	if (line.length <= width && !/[\t\x7f-\uffff]/.test(line)) return [line];

	if (width > 0 && Number.isInteger(width) && isPrintableAscii(line)) return wrapPlainAscii(line, width);

	const previous = checkpoint?.previous;
	if (checkpoint && previous && previous.width === width && startsWithText(line, previous.prefix)) {
		if (scanPrintableAscii(line.slice(previous.offset)) !== -1) {
			const lines = wrapScannedLine(line, width, checkpoint, previous);
			if (lines) {
				checkpoint.shared = previous.rows;
				return lines;
			}
		}
	}

	const scannedWidth = scanPrintableAscii(line);
	if (scannedWidth !== -1) {
		if (scannedWidth <= width) {
			return [line];
		}
		const scannedLines = wrapScannedLine(line, width, checkpoint);
		if (scannedLines) return scannedLines;
	}

	const visibleLength = scannedWidth !== -1 ? scannedWidth : visibleWidth(line);
	if (visibleLength <= width) {
		return [line];
	}

	const wrapped: string[] = [];
	const tracker = new AnsiCodeTracker();
	const widths: number[] = [];
	const tokens = splitIntoTokensWithAnsi(line, widths);

	let currentLine = "";
	let currentVisibleLength = 0;

	for (let index = 0; index < tokens.length; index++) {
		const token = tokens[index]!;
		const knownWidth = widths[index]!;
		const tokenVisibleLength = knownWidth >= 0 ? knownWidth : visibleWidth(token);
		const isWhitespace = isWhitespaceToken(token);

		// Token itself is too long - break it character by character
		if (tokenVisibleLength > width && !isWhitespace) {
			if (currentLine) {
				// Trim before adding the reset, which would otherwise block the final trimEnd()
				currentLine = currentLine.trimEnd();
				// Add specific reset for underline only (preserves background)
				const lineEndReset = tracker.getLineEndReset();
				if (lineEndReset) {
					currentLine += lineEndReset;
				}
				wrapped.push(currentLine);
				currentLine = "";
				currentVisibleLength = 0;
			}

			// Break long token - breakLongWord handles its own resets
			const broken = breakLongWord(token, width, tracker);
			for (let i = 0; i < broken.length - 1; i++) {
				wrapped.push(broken[i]!);
			}
			currentLine = broken[broken.length - 1];
			currentVisibleLength = visibleWidth(currentLine);
			continue;
		}

		// Check if adding this token would exceed width
		const totalNeeded = currentVisibleLength + tokenVisibleLength;

		if (totalNeeded > width && currentVisibleLength > 0) {
			// Trim trailing whitespace, then add underline reset (not full reset, to preserve background)
			let lineToWrap = currentLine.trimEnd();
			if (isWhitespace) {
				// The dropped space may carry codes (e.g. closing a styled word). Keep them on the wrapped line so
				// the style ends there and is not re-opened on the next line.
				lineToWrap += extractAnsiCodes(token);
				updateTrackerFromText(token, tracker);
			}
			const lineEndReset = tracker.getLineEndReset();
			if (lineEndReset) {
				lineToWrap += lineEndReset;
			}
			wrapped.push(lineToWrap);
			if (isWhitespace) {
				// Don't start new line with whitespace
				currentLine = tracker.getActiveCodes();
				currentVisibleLength = 0;
				continue;
			}
			currentLine = tracker.getActiveCodes() + token;
			currentVisibleLength = tokenVisibleLength;
		} else if (isWhitespace && totalNeeded > width && token.includes("\x1b")) {
			// Whitespace wider than an empty line. Plain spaces are removed by the final trimEnd(), but codes after
			// them (e.g. a trailing reset) would block that and leave the line wider than width. Keep only the codes.
			currentLine += extractAnsiCodes(token);
		} else {
			// Add to current line
			currentLine += token;
			currentVisibleLength += tokenVisibleLength;
		}

		updateTrackerFromText(token, tracker);
	}

	if (currentLine) {
		// No reset at end of final line - let caller handle it
		wrapped.push(currentLine);
	}

	// Trailing whitespace can cause lines to exceed the requested width
	return wrapped.length > 0 ? wrapped.map((line) => line.trimEnd()) : [""];
}

export const PUNCTUATION_REGEX = /[(){}[\]<>.,;:'"!?+\-=*/\\|&%^$#@~`]/;

/**
 * Check if a character is whitespace.
 */
export function isWhitespaceChar(char: string): boolean {
	return /\s/.test(char);
}

/**
 * Check if a character is punctuation.
 */
export function isPunctuationChar(char: string): boolean {
	return PUNCTUATION_REGEX.test(char);
}

function breakLongWord(word: string, width: number, tracker: AnsiCodeTracker): string[] {
	const lines: string[] = [];
	let currentLine = tracker.getActiveCodes();
	let currentWidth = 0;

	const finishRow = (): void => {
		lines.push(currentLine + tracker.getLineEndReset());
		currentLine = tracker.getActiveCodes();
		currentWidth = 0;
	};
	let i = 0;
	while (i < word.length) {
		const ansi = extractAnsiCode(word, i);
		if (ansi) {
			currentLine += ansi.code;
			tracker.process(ansi.code);
			i += ansi.length;
			continue;
		}
		const end = textRunEnd(word, i);
		const chunk = word.slice(i, end);
		if (width > 0 && Number.isInteger(width) && isPrintableAscii(chunk)) {
			while (i < end) {
				if (currentWidth >= width) finishRow();
				const count = Math.min(end - i, width - currentWidth);
				currentLine += word.slice(i, i + count);
				i += count;
				currentWidth += count;
			}
		} else {
			const take = (segment: string): void => {
				const cells = visibleWidth(segment);
				if (currentWidth + cells > width) finishRow();
				currentLine += segment;
				currentWidth += cells;
			};
			if (isStandaloneRun(chunk)) {
				for (const segment of chunk) take(segment);
			} else {
				for (const { segment } of getGraphemeSegmenter().segment(chunk)) take(segment);
			}
			i = end;
		}
	}

	if (currentLine) {
		// No reset at end of final segment - caller handles continuation
		lines.push(currentLine);
	}

	return lines.length > 0 ? lines : [""];
}

/**
 * Apply background color to a line, padding to full width.
 *
 * @param line - Line of text (may contain ANSI codes)
 * @param width - Total width to pad to
 * @param bgFn - Background color function
 * @returns Line with background applied and padded to width
 */
export function applyBackgroundToLine(line: string, width: number, bgFn: (text: string) => string): string {
	// Calculate padding needed
	const visibleLen = visibleWidth(line);
	const paddingNeeded = Math.max(0, width - visibleLen);
	const padding = " ".repeat(paddingNeeded);

	// Apply background to content + padding
	const withPadding = line + padding;
	return applyBackgroundAround(withPadding, bgFn);
}

const SGR_SEQUENCE = /\x1b\[([0-9;:]*)m/g;

/** Whether an SGR sequence with these parameters sets or resets the background (a full reset does). */
function setsBackground(params: string): boolean {
	const codes = params.split(/[;:]/);
	for (let i = 0; i < codes.length; i++) {
		const code = Number(codes[i] || 0);
		if (code === 0 || (code >= 40 && code <= 49) || (code >= 100 && code <= 107)) return true;
		// (The color of an extended foreground or underline color is not a code: 38;5;N, 38;2;R;G;B.)
		if (code === 38 || code === 58) i += codes[i + 1] === "5" ? 2 : 4;
	}
	return false;
}

/**
 * bgFn(line), with the background opened again where a part of `line` on a background of its own (a shaded code block
 * line) ends with a background reset (SGR 49), so the rest of the line keeps this background instead of showing the
 * terminal's. A reset that starts such a part (a code block on the terminal's own background) is kept.
 */
export function applyBackgroundAround(line: string, bgFn: (text: string) => string): string {
	const styled = bgFn(line);
	if (!line.includes("\x1b[49m")) {
		return styled;
	}
	const start = styled.indexOf(line);
	const open = start > 0 ? getActiveBackgroundAnsi(styled.slice(0, start)) : "";
	if (!open) {
		return styled;
	}
	// Whether the background in effect is this one; a reset while another is in effect ends that one.
	let ours = true;
	const reopened = line.replace(SGR_SEQUENCE, (sequence: string, params: string) => {
		if (params === "49") {
			if (ours) {
				ours = false;
				return sequence;
			}
			ours = true;
			return open;
		}
		if (setsBackground(params)) {
			ours = false;
		}
		return sequence;
	});
	return styled.slice(0, start) + reopened + styled.slice(start + line.length);
}

/**
 * Truncate text to fit within a maximum visible width, adding ellipsis if needed.
 * Optionally pad with spaces to reach exactly maxWidth.
 * Properly handles ANSI escape codes (they don't count toward width).
 *
 * @param text - Text to truncate (may contain ANSI codes)
 * @param maxWidth - Maximum visible width
 * @param ellipsis - Ellipsis string to append when truncating (default: "...")
 * @param pad - If true, pad result with spaces to exactly maxWidth (default: false)
 * @returns Truncated text, optionally padded to exactly maxWidth
 */
export function truncateToWidth(
	text: string,
	maxWidth: number,
	ellipsis: string = "...",
	pad: boolean = false,
): string {
	if (maxWidth <= 0) {
		return "";
	}

	if (text.length === 0) {
		return pad ? " ".repeat(maxWidth) : "";
	}

	const ellipsisWidth = visibleWidth(ellipsis);
	if (ellipsisWidth >= maxWidth) {
		const textWidth = visibleWidth(text);
		if (textWidth <= maxWidth) {
			return pad ? text + " ".repeat(maxWidth - textWidth) : text;
		}

		const clippedEllipsis = truncateFragmentToWidth(ellipsis, maxWidth);
		if (clippedEllipsis.width === 0) {
			return pad ? " ".repeat(maxWidth) : "";
		}
		return finalizeTruncatedResult("", 0, clippedEllipsis.text, clippedEllipsis.width, maxWidth, pad);
	}

	// Text of single-cell characters and escape sequences that fits is returned as it is, without segmenting it.
	const simpleWidth = asciiVisibleWidth(text);
	if (simpleWidth !== -1 && simpleWidth <= maxWidth) {
		return pad ? text + " ".repeat(maxWidth - simpleWidth) : text;
	}

	if (isPrintableAscii(text)) {
		if (text.length <= maxWidth) {
			return pad ? text + " ".repeat(maxWidth - text.length) : text;
		}
		const targetWidth = maxWidth - ellipsisWidth;
		return finalizeTruncatedResult(text.slice(0, targetWidth), targetWidth, ellipsis, ellipsisWidth, maxWidth, pad);
	}

	const targetWidth = maxWidth - ellipsisWidth;
	let result = "";
	let pendingAnsi = "";
	let visibleSoFar = 0;
	let keptWidth = 0;
	let keepContiguousPrefix = true;
	let overflowed = false;
	let exhaustedInput = false;
	const hasAnsi = text.includes("\x1b");
	const hasTabs = text.includes("\t");

	if (!hasAnsi && !hasTabs) {
		if (isStandaloneRun(text)) {
			for (const segment of text) {
				const width = graphemeWidth(segment);
				if (keepContiguousPrefix && keptWidth + width <= targetWidth) {
					result += segment;
					keptWidth += width;
				} else {
					keepContiguousPrefix = false;
				}
				visibleSoFar += width;
				if (visibleSoFar > maxWidth) {
					overflowed = true;
					break;
				}
			}
		} else {
			for (const { segment } of getGraphemeSegmenter().segment(text)) {
				const width = graphemeWidth(segment);
				if (keepContiguousPrefix && keptWidth + width <= targetWidth) {
					result += segment;
					keptWidth += width;
				} else {
					keepContiguousPrefix = false;
				}
				visibleSoFar += width;
				if (visibleSoFar > maxWidth) {
					overflowed = true;
					break;
				}
			}
		}
		exhaustedInput = !overflowed;
	} else {
		let i = 0;
		while (i < text.length) {
			const ansi = extractAnsiCode(text, i);
			if (ansi) {
				pendingAnsi += ansi.code;
				i += ansi.length;
				continue;
			}

			if (text[i] === "\t") {
				if (keepContiguousPrefix && keptWidth + 3 <= targetWidth) {
					if (pendingAnsi) {
						result += pendingAnsi;
						pendingAnsi = "";
					}
					result += "\t";
					keptWidth += 3;
				} else {
					keepContiguousPrefix = false;
					pendingAnsi = "";
				}
				visibleSoFar += 3;
				if (visibleSoFar > maxWidth) {
					overflowed = true;
					break;
				}
				i++;
				continue;
			}

			let end = i;
			while (end < text.length && text[end] !== "\t") {
				const nextAnsi = extractAnsiCode(text, end);
				if (nextAnsi) {
					break;
				}
				end++;
			}

			const run = text.slice(i, end);
			if (isStandaloneRun(run)) {
				for (const segment of run) {
					const width = graphemeWidth(segment);
					if (keepContiguousPrefix && keptWidth + width <= targetWidth) {
						if (pendingAnsi) {
							result += pendingAnsi;
							pendingAnsi = "";
						}
						result += segment;
						keptWidth += width;
					} else {
						keepContiguousPrefix = false;
						pendingAnsi = "";
					}

					visibleSoFar += width;
					if (visibleSoFar > maxWidth) {
						overflowed = true;
						break;
					}
				}
			} else {
				for (const { segment } of getGraphemeSegmenter().segment(run)) {
					const width = graphemeWidth(segment);
					if (keepContiguousPrefix && keptWidth + width <= targetWidth) {
						if (pendingAnsi) {
							result += pendingAnsi;
							pendingAnsi = "";
						}
						result += segment;
						keptWidth += width;
					} else {
						keepContiguousPrefix = false;
						pendingAnsi = "";
					}

					visibleSoFar += width;
					if (visibleSoFar > maxWidth) {
						overflowed = true;
						break;
					}
				}
			}
			if (overflowed) {
				break;
			}
			i = end;
		}
		exhaustedInput = i >= text.length;
	}

	if (!overflowed && exhaustedInput) {
		return pad ? text + " ".repeat(Math.max(0, maxWidth - visibleSoFar)) : text;
	}

	return finalizeTruncatedResult(result, keptWidth, ellipsis, ellipsisWidth, maxWidth, pad);
}

/**
 * Extract a range of visible columns from a line. Handles ANSI codes and wide chars.
 * @param strict - If true, exclude wide chars at boundary that would extend past the range
 */
export function sliceByColumn(line: string, startCol: number, length: number, strict = false): string {
	return sliceWithWidth(line, startCol, length, strict).text;
}

/** Like sliceByColumn but also returns the actual visible width of the result. */
export function sliceWithWidth(
	line: string,
	startCol: number,
	length: number,
	strict = false,
): { text: string; width: number } {
	if (length <= 0) return { text: "", width: 0 };
	const endCol = startCol + length;
	let result = "",
		resultWidth = 0,
		currentCol = 0,
		i = 0,
		pendingAnsi = "";

	while (i < line.length) {
		const ansi = extractAnsiCode(line, i);
		if (ansi) {
			// APC sequences (cursor markers) are positional one-shot commands, not persistent style
			// state, so codes before the range carry over only when they are not APC.
			if (currentCol >= startCol && currentCol < endCol) {
				// Keep original order: codes from before the range must precede codes at the boundary
				result += pendingAnsi + ansi.code;
				pendingAnsi = "";
			} else if (currentCol < startCol && !ansi.code.startsWith("\x1b_")) pendingAnsi += ansi.code;
			i += ansi.length;
			continue;
		}

		const textEnd = textRunEnd(line, i);
		const run = line.slice(i, textEnd);
		if (isPrintableAscii(run)) {
			const start = Math.max(0, Math.ceil(startCol - currentCol));
			const end = Math.min(run.length, strict ? Math.floor(endCol - currentCol) : Math.ceil(endCol - currentCol));
			if (start < end) {
				result += pendingAnsi + run.slice(start, end);
				resultWidth += end - start;
				pendingAnsi = "";
			}
			currentCol += run.length;
			if (currentCol >= endCol) break;
			i = textEnd;
			continue;
		}
		for (const segment of graphemesOf(run)) {
			const w = graphemeWidth(segment);
			const inRange = currentCol >= startCol && currentCol < endCol;
			const fits = !strict || currentCol + w <= endCol;
			if (inRange && fits) {
				if (pendingAnsi) {
					result += pendingAnsi;
					pendingAnsi = "";
				}
				result += segment;
				resultWidth += w;
			}
			currentCol += w;
			if (currentCol >= endCol) break;
		}
		i = textEnd;
		if (currentCol >= endCol) break;
	}
	return { text: result, width: resultWidth };
}

// Pooled tracker instance for extractSegments (avoids allocation per call)
const pooledStyleTracker = new AnsiCodeTracker();

/**
 * Extract "before" and "after" segments from a line in a single pass.
 * Used for overlay compositing where we need content before and after the overlay region.
 * Preserves styling from before the overlay that should affect content after it.
 */
export function extractSegments(
	line: string,
	beforeEnd: number,
	afterStart: number,
	afterLen: number,
	strictAfter = false,
): { before: string; beforeWidth: number; after: string; afterWidth: number } {
	let before = "",
		beforeWidth = 0,
		after = "",
		afterWidth = 0;
	let currentCol = 0,
		i = 0;
	let pendingAnsiBefore = "";
	let afterStarted = false;
	const afterEnd = afterStart + afterLen;

	// Track styling state so "after" inherits styling from before the overlay
	pooledStyleTracker.clear();

	while (i < line.length) {
		const ansi = extractAnsiCode(line, i);
		if (ansi) {
			// Track all SGR codes to know styling state at afterStart
			pooledStyleTracker.process(ansi.code);
			// Include ANSI codes in their respective segments
			if (currentCol < beforeEnd) {
				pendingAnsiBefore += ansi.code;
			} else if (
				currentCol >= afterStart &&
				currentCol < afterEnd &&
				(afterStarted || ansi.code.startsWith("\x1b_"))
			) {
				// SGR codes are only included after "after" started (styling already prepended). Zero-width APC
				// markers like CURSOR_MARKER are not tracked styling, so keep them at the boundary too.
				after += ansi.code;
			}
			i += ansi.length;
			continue;
		}

		const textEnd = textRunEnd(line, i);
		const run = line.slice(i, textEnd);
		if (beforeEnd <= afterStart && isPrintableAscii(run)) {
			const beforeCount = Math.min(run.length, Math.floor(beforeEnd - currentCol));
			if (beforeCount > 0) {
				before += pendingAnsiBefore + run.slice(0, beforeCount);
				pendingAnsiBefore = "";
				beforeWidth += beforeCount;
			}
			const start = Math.max(0, Math.ceil(afterStart - currentCol));
			const end = Math.min(
				run.length,
				strictAfter ? Math.floor(afterEnd - currentCol) : Math.ceil(afterEnd - currentCol),
			);
			if (start < end) {
				if (!afterStarted) after += pooledStyleTracker.getActiveCodes();
				afterStarted = true;
				after += run.slice(start, end);
				afterWidth += end - start;
			}
			currentCol += run.length;
			if (afterLen <= 0 ? currentCol >= beforeEnd : currentCol >= afterEnd) break;
			i = textEnd;
			continue;
		}
		for (const segment of graphemesOf(run)) {
			const w = graphemeWidth(segment);

			if (currentCol < beforeEnd && currentCol + w <= beforeEnd) {
				if (pendingAnsiBefore) {
					before += pendingAnsiBefore;
					pendingAnsiBefore = "";
				}
				before += segment;
				beforeWidth += w;
			} else if (currentCol >= afterStart && currentCol < afterEnd) {
				const fits = !strictAfter || currentCol + w <= afterEnd;
				if (fits) {
					// On first "after" grapheme, prepend inherited styling from before overlay
					if (!afterStarted) {
						after += pooledStyleTracker.getActiveCodes();
						afterStarted = true;
					}
					after += segment;
					afterWidth += w;
				}
			}

			currentCol += w;
			// Early exit: done with "before" only, or done with both segments
			if (afterLen <= 0 ? currentCol >= beforeEnd : currentCol >= afterEnd) break;
		}
		i = textEnd;
		if (afterLen <= 0 ? currentCol >= beforeEnd : currentCol >= afterEnd) break;
	}

	return { before, beforeWidth, after, afterWidth };
}
