/**
 * Shared diff computation utilities for the edit and similar tools.
 */

import { constants } from "fs";
import { access, readFile } from "fs/promises";
import { splitBom } from "../../utils/text.ts";
import { resolveToCwd } from "./path-utils.ts";

export function normalizeToLF(text: string): string {
	return text.replace(/\r\n/g, "\n").replace(/\r/g, "\n");
}

/**
 * Apply replacements matched in `normalizeToLF(originalContent)` to `originalContent` itself.
 * Text outside the replaced ranges keeps its bytes, including its line endings. Line breaks in
 * the new text get the line ending of the line where the replaced range starts. The result,
 * normalized to LF, equals the replacements applied to the normalized content.
 */
export function applyReplacementsToOriginal(originalContent: string, replacements: TextReplacement[]): string {
	// Only CRLF changes length when normalizing: the LF of the n-th CRLF (at original offset p) is at
	// p - n in the normalized text. Adding the number of CRLFs before a normalized offset gives the
	// original offset, and a range that covers that LF covers the whole CRLF.
	let crlfBefore = 0;
	let nextCrlf = originalContent.indexOf("\r\n");
	const toOriginal = (offset: number): number => {
		while (nextCrlf !== -1 && nextCrlf - crlfBefore < offset) {
			crlfBefore++;
			nextCrlf = originalContent.indexOf("\r\n", nextCrlf + 2);
		}
		return offset + crlfBefore;
	};

	const parts: string[] = [];
	let endsWithCr = false;
	const push = (part: string): void => {
		if (part.length === 0) return;
		// A CR before a seam and an LF after it would merge into one CRLF line break, for example a kept
		// lone CR followed by inserted text that starts with LF, or both sides of a deleted range. Adding
		// an LF turns the CR into a CRLF of its own, so the written file has the line breaks the diff shows.
		if (endsWithCr && part[0] === "\n") parts.push("\n");
		parts.push(part);
		endsWithCr = part[part.length - 1] === "\r";
	};
	let position = 0;
	for (const replacement of replacements) {
		const start = toOriginal(replacement.matchIndex);
		push(originalContent.slice(position, start));
		const ending = getLineEndingAt(originalContent, start);
		push(ending === "\n" ? replacement.newText : replacement.newText.replaceAll("\n", ending));
		position = toOriginal(replacement.matchIndex + replacement.matchLength);
	}
	push(originalContent.slice(position));
	return parts.join("");
}

/**
 * Line ending of the line containing `offset`: its own LF or CRLF, or that of the line before
 * for an unterminated last line. A lone CR only counts in files without any LF (classic Mac
 * line endings); elsewhere it is more likely a control character inside a line.
 */
function getLineEndingAt(content: string, offset: number): "\r\n" | "\n" | "\r" {
	let lineFeed = content.indexOf("\n", offset);
	if (lineFeed === -1) lineFeed = content.lastIndexOf("\n", offset);
	if (lineFeed === -1) return content.includes("\r") ? "\r" : "\n";
	return content[lineFeed - 1] === "\r" ? "\r\n" : "\n";
}

const ASCII_ONLY = /^[\x00-\x7f]*$/;
// The ASCII characters trimEnd strips, other than "\n", at the end of a line.
const ASCII_TRAILING_WHITESPACE = /[\t\v\f\r ](?:\n|$)/;

/** Strip trailing whitespace from each line. */
function trimLineEnds(text: string): string {
	return text
		.split("\n")
		.map((line) => line.trimEnd())
		.join("\n");
}

/**
 * Normalize text for fuzzy matching. Applies progressive transformations:
 * - Strip trailing whitespace from each line
 * - Normalize smart quotes to ASCII equivalents
 * - Normalize Unicode dashes/hyphens to ASCII hyphen
 * - Normalize special Unicode spaces to regular space
 */
export function normalizeForFuzzyMatch(text: string): string {
	if (ASCII_ONLY.test(text)) {
		// NFKC leaves ASCII unchanged and the replacements below only match non-ASCII characters, so
		// only trailing whitespace can change. Scanning the large Unicode classes dominated this call.
		return ASCII_TRAILING_WHITESPACE.test(text) ? trimLineEnds(text) : text;
	}
	return (
		trimLineEnds(text.normalize("NFKC"))
			// Smart single quotes → '
			.replace(/[\u2018\u2019\u201A\u201B]/g, "'")
			// Smart double quotes → "
			.replace(/[\u201C\u201D\u201E\u201F]/g, '"')
			// Various dashes/hyphens → -
			// U+2010 hyphen, U+2011 non-breaking hyphen, U+2012 figure dash,
			// U+2013 en-dash, U+2014 em-dash, U+2015 horizontal bar, U+2212 minus
			.replace(/[\u2010\u2011\u2012\u2013\u2014\u2015\u2212]/g, "-")
			// Special spaces → regular space
			// U+00A0 NBSP, U+2002-U+200A various spaces, U+202F narrow NBSP,
			// U+205F medium math space, U+3000 ideographic space
			.replace(/[\u00A0\u2002-\u200A\u202F\u205F\u3000]/g, " ")
	);
}

let lastFuzzyInput: string | undefined;
let lastFuzzyOutput = "";

/**
 * normalizeForFuzzyMatch for whole file contents, remembering the last result. The edit preview and the
 * edit itself normalize the same file content one after the other.
 */
function normalizeContentForFuzzyMatch(content: string): string {
	if (content !== lastFuzzyInput) {
		lastFuzzyOutput = normalizeForFuzzyMatch(content);
		lastFuzzyInput = content;
	}
	return lastFuzzyOutput;
}

function splitLinesWithEndings(content: string): string[] {
	return content.match(/[^\n]*\n|[^\n]+/g) ?? [];
}

interface LineSpan {
	start: number;
	end: number;
}

interface MatchedEdit {
	editIndex: number;
	matchIndex: number;
	matchLength: number;
	newText: string;
}

/** Replacement of `matchLength` characters at `matchIndex` with `newText`. */
export type TextReplacement = Pick<MatchedEdit, "matchIndex" | "matchLength" | "newText">;

function getLineSpans(content: string): LineSpan[] {
	let offset = 0;
	return splitLinesWithEndings(content).map((line) => {
		const span = { start: offset, end: offset + line.length };
		offset = span.end;
		return span;
	});
}

function getReplacementLineRange(lines: LineSpan[], replacement: TextReplacement) {
	const replacementStart = replacement.matchIndex;
	const replacementEnd = replacement.matchIndex + replacement.matchLength;

	let startLine = -1;
	for (let i = 0; i < lines.length; i++) {
		const line = lines[i];
		if (replacementStart >= line.start && replacementStart < line.end) {
			startLine = i;
			break;
		}
	}
	if (startLine === -1) {
		throw new Error("Replacement range is outside the base content.");
	}

	let endLine = startLine;
	while (endLine < lines.length && lines[endLine].end < replacementEnd) {
		endLine++;
	}
	if (endLine >= lines.length) {
		throw new Error("Replacement range is outside the base content.");
	}

	return { startLine, endLine: endLine + 1 };
}

function applyReplacements(content: string, replacements: TextReplacement[], offset = 0): string {
	let result = content;
	for (let i = replacements.length - 1; i >= 0; i--) {
		const replacement = replacements[i];
		const matchIndex = replacement.matchIndex - offset;
		result =
			result.substring(0, matchIndex) + replacement.newText + result.substring(matchIndex + replacement.matchLength);
	}
	return result;
}

/**
 * Turn replacements matched against `baseContent` into replacements of
 * `originalContent` that keep unchanged line blocks from the original.
 *
 * This is useful when `baseContent` is a normalized view of the original. Each
 * replacement is widened to the lines it actually touches, and those touched
 * lines are rewritten from the normalized base; all other lines keep their
 * original text. The actual replacement ranges drive preservation so
 * duplicate normalized lines cannot be aligned to the wrong occurrence.
 */
function widenReplacementsToLines(
	originalContent: string,
	baseContent: string,
	replacements: TextReplacement[],
): TextReplacement[] {
	const originalLines = getLineSpans(originalContent);
	const baseLines = getLineSpans(baseContent);
	if (originalLines.length !== baseLines.length) {
		throw new Error("Cannot preserve unchanged lines because the base content has a different line count.");
	}

	const groups: Array<{ startLine: number; endLine: number; replacements: TextReplacement[] }> = [];
	const sortedReplacements = [...replacements].sort((a, b) => a.matchIndex - b.matchIndex);
	for (const replacement of sortedReplacements) {
		const range = getReplacementLineRange(baseLines, replacement);
		const current = groups[groups.length - 1];
		if (current && range.startLine < current.endLine) {
			current.endLine = Math.max(current.endLine, range.endLine);
			current.replacements.push(replacement);
			continue;
		}
		groups.push({ ...range, replacements: [replacement] });
	}

	return groups.map((group) => {
		const groupStartOffset = baseLines[group.startLine].start;
		const groupEndOffset = baseLines[group.endLine - 1].end;
		const originalStart = originalLines[group.startLine].start;
		return {
			matchIndex: originalStart,
			matchLength: originalLines[group.endLine - 1].end - originalStart,
			newText: applyReplacements(
				baseContent.slice(groupStartOffset, groupEndOffset),
				group.replacements,
				groupStartOffset,
			),
		};
	});
}

export interface FuzzyMatchResult {
	/** Whether a match was found */
	found: boolean;
	/** The index where the match starts (in the content that should be used for replacement) */
	index: number;
	/** Length of the matched text */
	matchLength: number;
	/** Whether fuzzy matching was used (false = exact match) */
	usedFuzzyMatch: boolean;
	/**
	 * The content to use for replacement operations.
	 * When exact match: original content. When fuzzy match: normalized content.
	 */
	contentForReplacement: string;
}

export interface Edit {
	oldText: string;
	newText: string;
}

export interface AppliedEditsResult {
	baseContent: string;
	newContent: string;
	/** Sorted replacements of `baseContent` that turn it into `newContent` */
	replacements: TextReplacement[];
}

/**
 * Find oldText in content, trying exact match first, then fuzzy match.
 * When fuzzy matching is used, the returned contentForReplacement is the
 * fuzzy-normalized version of the content (trailing whitespace stripped,
 * Unicode quotes/dashes normalized to ASCII).
 */
export function fuzzyFindText(content: string, oldText: string): FuzzyMatchResult {
	return findText(content, oldText, () => normalizeForFuzzyMatch(content));
}

/** fuzzyFindText with `normalizeForFuzzyMatch(content)` supplied by the caller, so it can be shared. */
function findText(content: string, oldText: string, getFuzzyContent: () => string): FuzzyMatchResult {
	// Try exact match first
	const exactIndex = content.indexOf(oldText);
	if (exactIndex !== -1) {
		return {
			found: true,
			index: exactIndex,
			matchLength: oldText.length,
			usedFuzzyMatch: false,
			contentForReplacement: content,
		};
	}

	// Try fuzzy match - work entirely in normalized space
	const fuzzyContent = getFuzzyContent();
	const fuzzyOldText = normalizeForFuzzyMatch(oldText);
	const fuzzyIndex = fuzzyContent.indexOf(fuzzyOldText);

	if (fuzzyIndex === -1) {
		return {
			found: false,
			index: -1,
			matchLength: 0,
			usedFuzzyMatch: false,
			contentForReplacement: content,
		};
	}

	// When fuzzy matching, return offsets in normalized space. Callers can use
	// the normalized content to compute replacements, then decide how much of
	// that normalized output should be written back.
	return {
		found: true,
		index: fuzzyIndex,
		matchLength: fuzzyOldText.length,
		usedFuzzyMatch: true,
		contentForReplacement: fuzzyContent,
	};
}

/** Occurrences of `oldText` in `fuzzyContent`, which is already fuzzy-normalized. */
function countOccurrences(fuzzyContent: string, oldText: string): number {
	const fuzzyOldText = normalizeForFuzzyMatch(oldText);
	return fuzzyContent.split(fuzzyOldText).length - 1;
}

function getNotFoundError(path: string, editIndex: number, totalEdits: number): Error {
	if (totalEdits === 1) {
		return new Error(
			`Could not find the exact text in ${path}. The old text must match exactly including all whitespace and newlines.`,
		);
	}
	return new Error(
		`Could not find edits[${editIndex}] in ${path}. The oldText must match exactly including all whitespace and newlines.`,
	);
}

function getDuplicateError(path: string, editIndex: number, totalEdits: number, occurrences: number): Error {
	if (totalEdits === 1) {
		return new Error(
			`Found ${occurrences} occurrences of the text in ${path}. The text must be unique. Please provide more context to make it unique.`,
		);
	}
	return new Error(
		`Found ${occurrences} occurrences of edits[${editIndex}] in ${path}. Each oldText must be unique. Please provide more context to make it unique.`,
	);
}

function getEmptyOldTextError(path: string, editIndex: number, totalEdits: number): Error {
	if (totalEdits === 1) {
		return new Error(`oldText must not be empty in ${path}.`);
	}
	return new Error(`edits[${editIndex}].oldText must not be empty in ${path}.`);
}

function getNoChangeError(path: string, totalEdits: number): Error {
	if (totalEdits === 1) {
		return new Error(
			`No changes made to ${path}. The replacement produced identical content. This might indicate an issue with special characters or the text not existing as expected.`,
		);
	}
	return new Error(`No changes made to ${path}. The replacements produced identical content.`);
}

/**
 * Apply one or more exact-text replacements to LF-normalized content.
 *
 * All edits are matched against the same original content. Replacements are
 * then applied in reverse order so offsets remain stable. If any edit needs
 * fuzzy matching, the operation runs in fuzzy-normalized content space and then
 * overlays those line-level changes onto the original content so unchanged line
 * blocks keep their original bytes.
 */
export function applyEditsToNormalizedContent(
	normalizedContent: string,
	edits: Edit[],
	path: string,
): AppliedEditsResult {
	const normalizedEdits = edits.map((edit) => ({
		oldText: normalizeToLF(edit.oldText),
		newText: normalizeToLF(edit.newText),
	}));

	for (let i = 0; i < normalizedEdits.length; i++) {
		if (normalizedEdits[i].oldText.length === 0) {
			throw getEmptyOldTextError(path, i, normalizedEdits.length);
		}
	}

	// Normalized once for all edits: normalizing a large file per edit took most of a multi-edit call.
	const fuzzyContent = normalizeContentForFuzzyMatch(normalizedContent);
	const getFuzzyContent = (): string => fuzzyContent;
	const initialMatches = normalizedEdits.map((edit) => findText(normalizedContent, edit.oldText, getFuzzyContent));
	const usedFuzzyMatch = initialMatches.some((match) => match.usedFuzzyMatch);
	const replacementBaseContent = usedFuzzyMatch ? fuzzyContent : normalizedContent;
	let fuzzyOfFuzzyContent: string | undefined;
	const getReplacementBaseFuzzyContent = usedFuzzyMatch
		? (): string => {
				fuzzyOfFuzzyContent ??= normalizeForFuzzyMatch(fuzzyContent);
				return fuzzyOfFuzzyContent;
			}
		: getFuzzyContent;

	const matchedEdits: MatchedEdit[] = [];
	for (let i = 0; i < normalizedEdits.length; i++) {
		const edit = normalizedEdits[i];
		const matchResult = findText(replacementBaseContent, edit.oldText, getReplacementBaseFuzzyContent);
		if (!matchResult.found) {
			throw getNotFoundError(path, i, normalizedEdits.length);
		}

		// normalizeForFuzzyMatch is idempotent, so this is the normalized replacement base either way.
		const occurrences = countOccurrences(fuzzyContent, edit.oldText);
		if (occurrences > 1) {
			throw getDuplicateError(path, i, normalizedEdits.length, occurrences);
		}

		matchedEdits.push({
			editIndex: i,
			matchIndex: matchResult.index,
			matchLength: matchResult.matchLength,
			newText: edit.newText,
		});
	}

	matchedEdits.sort((a, b) => a.matchIndex - b.matchIndex);
	for (let i = 1; i < matchedEdits.length; i++) {
		const previous = matchedEdits[i - 1];
		const current = matchedEdits[i];
		if (previous.matchIndex + previous.matchLength > current.matchIndex) {
			throw new Error(
				`edits[${previous.editIndex}] and edits[${current.editIndex}] overlap in ${path}. Merge them into one edit or target disjoint regions.`,
			);
		}
	}

	const baseContent = normalizedContent;
	const replacements = usedFuzzyMatch
		? widenReplacementsToLines(normalizedContent, replacementBaseContent, matchedEdits)
		: matchedEdits;
	const newContent = applyReplacements(baseContent, replacements);

	if (baseContent === newContent) {
		throw getNoChangeError(path, normalizedEdits.length);
	}

	return { baseContent, newContent, replacements };
}

/*
 * Line diffs over the changed window.
 *
 * Diff.diffLines tokenizes both texts in full, and the edit tool diffs the whole file up to three times per
 * edit (preview, display diff, patch). An edit changes a few lines of a large file, so most of that work walks
 * the unchanged prefix and suffix. `computeLineDiff` runs the same Myers search as jsdiff 8 (`Diff.diffWithOptionsObj`
 * with default options), step for step, so it returns the same parts, but:
 * - the common prefix is found by comparing text instead of lines; jsdiff's first step consumes exactly those lines,
 * - lines are split from the text on demand, so lines the search never reaches are never split,
 * - on the diagonal that ends at the end of both texts, reaching the common suffix ends the walk directly; jsdiff
 *   compares those lines one by one and finds them all equal.
 * The display diff and the patch are then built from the parts exactly as `generateDiffString` and
 * `Diff.structuredPatch` did, reading only the lines they output.
 *
 * Calling jsdiff on a cut-out window is not equivalent: lines after the change can make it align the change
 * differently. Lines "b c" against "c b" diff as "-b c +b", but with a common "b" appended jsdiff returns
 * "-b c b +b", not "-b c +b b". test/edit-diff-fast-path.test.ts
 * compares the output with jsdiff on randomized inputs; rerun it when upgrading the diff package.
 */

/** A part of a line diff: like a jsdiff change object, without its value. */
interface LineRun {
	count: number;
	added: boolean;
	removed: boolean;
}

interface LineComponent extends LineRun {
	previousComponent: LineComponent | undefined;
}

interface DiffPath {
	oldPos: number;
	lastComponent: LineComponent | undefined;
}

/**
 * The lines of a text as jsdiff tokenizes them (each with its trailing "\n"), split on demand. Splitting
 * starts at a known line start and goes forward or backward from there.
 */
class LazyLines {
	readonly text: string;
	readonly count: number;
	private readonly base: number;
	private readonly forward: string[] = [];
	private readonly backward: string[] = [];
	private forwardEnd: number;
	private backwardStart: number;

	constructor(text: string, count: number, base: number, baseOffset: number) {
		this.text = text;
		this.count = count;
		this.base = base;
		this.forwardEnd = baseOffset;
		this.backwardStart = baseOffset;
	}

	get(index: number): string {
		if (index >= this.base) {
			const forwardIndex = index - this.base;
			while (this.forward.length <= forwardIndex) {
				const newline = this.text.indexOf("\n", this.forwardEnd);
				const end = newline === -1 ? this.text.length : newline + 1;
				this.forward.push(this.text.slice(this.forwardEnd, end));
				this.forwardEnd = end;
			}
			return this.forward[forwardIndex];
		}
		const backwardIndex = this.base - 1 - index;
		while (this.backward.length <= backwardIndex) {
			const end = this.backwardStart;
			const start = end < 2 ? 0 : this.text.lastIndexOf("\n", end - 2) + 1;
			this.backward.push(this.text.slice(start, end));
			this.backwardStart = start;
		}
		return this.backward[backwardIndex];
	}
}

/** Array.prototype.slice index resolution. */
function resolveSliceIndex(value: number | undefined, length: number, fallback: number): number {
	if (value === undefined) return fallback;
	const index = Math.trunc(value) || 0;
	return index < 0 ? Math.max(length + index, 0) : Math.min(index, length);
}

/** The lines of one diff part, read on demand. Stands in for the arrays split from a part's value. */
class PartLines {
	readonly length: number;
	private readonly lines: LazyLines;
	private readonly start: number;
	private readonly keepNewlines: boolean;

	constructor(lines: LazyLines, start: number, length: number, keepNewlines: boolean) {
		this.lines = lines;
		this.start = start;
		this.length = length;
		this.keepNewlines = keepNewlines;
	}

	/** Same result as `slice` on the array of the part's lines. */
	slice(start?: number, end?: number): string[] {
		const from = resolveSliceIndex(start, this.length, 0);
		const to = resolveSliceIndex(end, this.length, this.length);
		const result: string[] = [];
		for (let i = from; i < to; i++) {
			const line = this.lines.get(this.start + i);
			result.push(this.keepNewlines || !line.endsWith("\n") ? line : line.slice(0, -1));
		}
		return result;
	}
}

interface LineDiff {
	oldText: string;
	newText: string;
	runs: LineRun[];
	oldLines: LazyLines;
	newLines: LazyLines;
	/** `text.split("\n").length` of each side. */
	oldSplitLength: number;
	newSplitLength: number;
}

const COMMON_RUN_INITIAL_CHUNK = 256;
const COMMON_RUN_LINEAR_SCAN = 32;

function commonPrefixLength(a: string, b: string): number {
	const max = Math.min(a.length, b.length);
	let low = 0;
	let step = COMMON_RUN_INITIAL_CHUNK;
	while (low < max) {
		let high = Math.min(low + step, max);
		if (a.slice(low, high) !== b.slice(low, high)) {
			// The first difference is in [low, high).
			while (high - low > COMMON_RUN_LINEAR_SCAN) {
				const mid = low + Math.floor((high - low) / 2);
				if (a.slice(low, mid) === b.slice(low, mid)) low = mid;
				else high = mid;
			}
			while (a.charCodeAt(low) === b.charCodeAt(low)) low++;
			return low;
		}
		low = high;
		step *= 2;
	}
	return max;
}

/** Length of the common suffix of `a` and `b`, at most `max`. */
function commonSuffixLength(a: string, b: string, max: number): number {
	let low = 0;
	let step = COMMON_RUN_INITIAL_CHUNK;
	while (low < max) {
		let high = Math.min(low + step, max);
		if (a.slice(a.length - high, a.length - low) !== b.slice(b.length - high, b.length - low)) {
			// The last difference is in the suffix lengths (low, high].
			while (high - low > COMMON_RUN_LINEAR_SCAN) {
				const mid = low + Math.floor((high - low) / 2);
				if (a.slice(a.length - mid, a.length - low) === b.slice(b.length - mid, b.length - low)) low = mid;
				else high = mid;
			}
			while (a.charCodeAt(a.length - low - 1) === b.charCodeAt(b.length - low - 1)) low++;
			return low;
		}
		low = high;
		step *= 2;
	}
	return max;
}

function countNewlines(text: string, from: number, to: number): number {
	let count = 0;
	let index = text.indexOf("\n", from);
	while (index !== -1 && index < to) {
		count++;
		index = text.indexOf("\n", index + 1);
	}
	return count;
}

/** Number of jsdiff line tokens in `text.slice(from, to)`, where `from` is a line start. */
function countLines(text: string, from: number, to: number): number {
	return countNewlines(text, from, to) + (to > from && text.charCodeAt(to - 1) !== 10 ? 1 : 0);
}

function addToPath(path: DiffPath, added: boolean, removed: boolean, oldPosInc: number): DiffPath {
	const last = path.lastComponent;
	if (last && last.added === added && last.removed === removed) {
		return {
			oldPos: path.oldPos + oldPosInc,
			lastComponent: { count: last.count + 1, added, removed, previousComponent: last.previousComponent },
		};
	}
	return {
		oldPos: path.oldPos + oldPosInc,
		lastComponent: { count: 1, added, removed, previousComponent: last },
	};
}

function buildRuns(lastComponent: LineComponent | undefined): LineRun[] {
	const runs: LineRun[] = [];
	for (let component = lastComponent; component; component = component.previousComponent) {
		runs.push({ count: component.count, added: component.added, removed: component.removed });
	}
	return runs.reverse();
}

/** jsdiff's Myers search (`Diff.diffWithOptionsObj`, default options) over lazily split lines. */
function diffLineRuns(oldLines: LazyLines, newLines: LazyLines, prefixLines: number, suffixLines: number): LineRun[] {
	const oldLen = oldLines.count;
	const newLen = newLines.count;
	const endDiagonal = oldLen - newLen;
	const oldSuffixStart = oldLen - suffixLines;

	const extractCommon = (basePath: DiffPath, diagonalPath: number): number => {
		let oldPos = basePath.oldPos;
		let newPos = oldPos - diagonalPath;
		let commonCount = 0;
		while (newPos + 1 < newLen && oldPos + 1 < oldLen) {
			if (diagonalPath === endDiagonal && oldPos + 1 >= oldSuffixStart) {
				// Both sides are in the common suffix at the same distance from the end: all remaining lines match.
				const remaining = oldLen - 1 - oldPos;
				oldPos += remaining;
				newPos += remaining;
				commonCount += remaining;
				break;
			}
			if (oldLines.get(oldPos + 1) !== newLines.get(newPos + 1)) break;
			newPos++;
			oldPos++;
			commonCount++;
		}
		if (commonCount) {
			basePath.lastComponent = {
				count: commonCount,
				previousComponent: basePath.lastComponent,
				added: false,
				removed: false,
			};
		}
		basePath.oldPos = oldPos;
		return newPos;
	};

	// jsdiff's first step: the common prefix on diagonal 0.
	const firstPath: DiffPath = {
		oldPos: prefixLines - 1,
		lastComponent:
			prefixLines > 0
				? { count: prefixLines, added: false, removed: false, previousComponent: undefined }
				: undefined,
	};
	let newPos = prefixLines - 1;
	if (firstPath.oldPos + 1 >= oldLen && newPos + 1 >= newLen) {
		return buildRuns(firstPath.lastComponent);
	}
	const bestPath = new Map<number, DiffPath | undefined>([[0, firstPath]]);

	let minDiagonalToConsider = -Infinity;
	let maxDiagonalToConsider = Infinity;
	const maxEditLength = newLen + oldLen;
	for (let editLength = 1; editLength <= maxEditLength; editLength++) {
		for (
			let diagonalPath = Math.max(minDiagonalToConsider, -editLength);
			diagonalPath <= Math.min(maxDiagonalToConsider, editLength);
			diagonalPath += 2
		) {
			const removePath = bestPath.get(diagonalPath - 1);
			const addPath = bestPath.get(diagonalPath + 1);
			if (removePath) {
				bestPath.set(diagonalPath - 1, undefined);
			}
			const canAdd =
				addPath !== undefined && 0 <= addPath.oldPos - diagonalPath && addPath.oldPos - diagonalPath < newLen;
			const canRemove = removePath !== undefined && removePath.oldPos + 1 < oldLen;
			let basePath: DiffPath;
			if (canAdd && (!canRemove || removePath.oldPos < addPath.oldPos)) {
				basePath = addToPath(addPath, true, false, 0);
			} else if (canRemove) {
				basePath = addToPath(removePath, false, true, 1);
			} else {
				bestPath.set(diagonalPath, undefined);
				continue;
			}
			newPos = extractCommon(basePath, diagonalPath);
			if (basePath.oldPos + 1 >= oldLen && newPos + 1 >= newLen) {
				return buildRuns(basePath.lastComponent);
			}
			bestPath.set(diagonalPath, basePath);
			if (basePath.oldPos + 1 >= oldLen) {
				maxDiagonalToConsider = Math.min(maxDiagonalToConsider, diagonalPath - 1);
			}
			if (newPos + 1 >= newLen) {
				minDiagonalToConsider = Math.max(minDiagonalToConsider, diagonalPath + 1);
			}
		}
	}
	// Unreachable: an edit script of length oldLen + newLen always exists.
	throw new Error("Line diff did not terminate.");
}

function computeLineDiff(oldText: string, newText: string): LineDiff {
	if (oldText === newText) {
		const count = countLines(oldText, 0, oldText.length);
		const lines = new LazyLines(oldText, count, 0, 0);
		const splitLength = countNewlines(oldText, 0, oldText.length) + 1;
		return {
			oldText,
			newText,
			runs: count > 0 ? [{ count, added: false, removed: false }] : [],
			oldLines: lines,
			newLines: lines,
			oldSplitLength: splitLength,
			newSplitLength: splitLength,
		};
	}

	// Common prefix lines end at the last "\n" of the common prefix. The texts differ, so a final line
	// without "\n" cannot be common to both.
	const prefixChars = commonPrefixLength(oldText, newText);
	const prefixEnd = prefixChars === 0 ? 0 : oldText.lastIndexOf("\n", prefixChars - 1) + 1;

	// Common suffix lines start at a line start in both texts, after the prefix lines.
	const suffixChars = commonSuffixLength(oldText, newText, Math.min(oldText.length, newText.length) - prefixEnd);
	const oldTail = oldText.length - suffixChars;
	const newTail = newText.length - suffixChars;
	let suffixLength: number;
	if (
		(oldTail === 0 || oldText.charCodeAt(oldTail - 1) === 10) &&
		(newTail === 0 || newText.charCodeAt(newTail - 1) === 10)
	) {
		suffixLength = suffixChars;
	} else {
		const newline = oldText.indexOf("\n", oldTail);
		suffixLength = newline === -1 ? 0 : oldText.length - newline - 1;
	}
	const oldSuffixStart = oldText.length - suffixLength;
	const newSuffixStart = newText.length - suffixLength;

	const prefixLines = countNewlines(oldText, 0, prefixEnd);
	const suffixNewlines = countNewlines(oldText, oldSuffixStart, oldText.length);
	const suffixLines = countLines(oldText, oldSuffixStart, oldText.length);
	const oldMiddleNewlines = countNewlines(oldText, prefixEnd, oldSuffixStart);
	const newMiddleNewlines = countNewlines(newText, prefixEnd, newSuffixStart);

	const oldLines = new LazyLines(
		oldText,
		prefixLines + countLines(oldText, prefixEnd, oldSuffixStart) + suffixLines,
		prefixLines,
		prefixEnd,
	);
	const newLines = new LazyLines(
		newText,
		prefixLines + countLines(newText, prefixEnd, newSuffixStart) + suffixLines,
		prefixLines,
		prefixEnd,
	);
	return {
		oldText,
		newText,
		runs: diffLineRuns(oldLines, newLines, prefixLines, suffixLines),
		oldLines,
		newLines,
		oldSplitLength: prefixLines + oldMiddleNewlines + suffixNewlines + 1,
		newSplitLength: prefixLines + newMiddleNewlines + suffixNewlines + 1,
	};
}

let lastLineDiff: LineDiff | undefined;

/** computeLineDiff, remembering the last result: the edit tool builds a display diff and a patch of the same texts. */
function getLineDiff(oldText: string, newText: string): LineDiff {
	if (lastLineDiff?.oldText !== oldText || lastLineDiff.newText !== newText) {
		lastLineDiff = computeLineDiff(oldText, newText);
	}
	return lastLineDiff;
}

/** The lines of each part of `lineDiff`, as jsdiff would put them in the part's value. */
function getPartLines(lineDiff: LineDiff, keepNewlines: boolean): PartLines[] {
	let oldIndex = 0;
	let newIndex = 0;
	return lineDiff.runs.map((run) => {
		if (run.removed) {
			const lines = new PartLines(lineDiff.oldLines, oldIndex, run.count, keepNewlines);
			oldIndex += run.count;
			return lines;
		}
		const lines = new PartLines(lineDiff.newLines, newIndex, run.count, keepNewlines);
		newIndex += run.count;
		if (!run.added) oldIndex += run.count;
		return lines;
	});
}

interface PatchHunk {
	oldStart: number;
	oldLines: number;
	newStart: number;
	newLines: number;
	lines: string[];
}

/**
 * Generate a standard unified patch. Same output as
 * `Diff.createTwoFilesPatch(path, path, oldContent, newContent, undefined, undefined, { context: contextLines, headerOptions: Diff.FILE_HEADERS_ONLY })`.
 */
export function generateUnifiedPatch(path: string, oldContent: string, newContent: string, contextLines = 4): string {
	return formatPatch(path, structuredPatchHunks(oldContent, newContent, contextLines));
}

/** The hunks of a unified patch of `oldContent` against `newContent`, as `Diff.structuredPatch` returns them. */
function structuredPatchHunks(oldContent: string, newContent: string, contextLines: number): PatchHunk[] {
	const lineDiff = getLineDiff(oldContent, newContent);
	const runs = lineDiff.runs;
	const partLines = getPartLines(lineDiff, true);
	const context = contextLines;

	// Diff.structuredPatch, step 1. jsdiff appends an empty part to make cleanup easier; index runs.length is it.
	const partCount = runs.length + 1;
	const emptyLines = new PartLines(lineDiff.newLines, 0, 0, true);
	const contextLinesOf = (lines: string[]): string[] => lines.map((entry) => ` ${entry}`);
	const hunks: PatchHunk[] = [];
	let oldRangeStart = 0;
	let newRangeStart = 0;
	let curRange: string[] = [];
	let oldLine = 1;
	let newLine = 1;
	for (let i = 0; i < partCount; i++) {
		const current: LineRun = i < runs.length ? runs[i] : { count: 0, added: false, removed: false };
		const lines = i < runs.length ? partLines[i] : emptyLines;
		if (current.added || current.removed) {
			// If we have previous context, start with that
			if (!oldRangeStart) {
				oldRangeStart = oldLine;
				newRangeStart = newLine;
				if (i > 0) {
					curRange = context > 0 ? contextLinesOf(partLines[i - 1].slice(-context)) : [];
					oldRangeStart -= curRange.length;
					newRangeStart -= curRange.length;
				}
			}
			// Output our changes
			for (const line of lines.slice()) {
				curRange.push((current.added ? "+" : "-") + line);
			}
			// Track the updated file position
			if (current.added) {
				newLine += lines.length;
			} else {
				oldLine += lines.length;
			}
		} else {
			// Identical context lines. Track line changes
			if (oldRangeStart) {
				// Close out any changes that have been output (or join overlapping)
				if (lines.length <= context * 2 && i < partCount - 2) {
					// Overlapping
					for (const line of contextLinesOf(lines.slice())) {
						curRange.push(line);
					}
				} else {
					// end the range and output
					const contextSize = Math.min(lines.length, context);
					for (const line of contextLinesOf(lines.slice(0, contextSize))) {
						curRange.push(line);
					}
					hunks.push({
						oldStart: oldRangeStart,
						oldLines: oldLine - oldRangeStart + contextSize,
						newStart: newRangeStart,
						newLines: newLine - newRangeStart + contextSize,
						lines: curRange,
					});
					oldRangeStart = 0;
					newRangeStart = 0;
					curRange = [];
				}
			}
			oldLine += lines.length;
			newLine += lines.length;
		}
	}

	// Step 2: eliminate the trailing "\n" from each line of each hunk, and, where needed, add
	// "\ No newline at end of file".
	for (const hunk of hunks) {
		for (let i = 0; i < hunk.lines.length; i++) {
			if (hunk.lines[i].endsWith("\n")) {
				hunk.lines[i] = hunk.lines[i].slice(0, -1);
			} else {
				hunk.lines.splice(i + 1, 0, "\\ No newline at end of file");
				i++; // Skip the line we just added, then continue iterating
			}
		}
	}

	return hunks;
}

/** `Diff.formatPatch` with `Diff.FILE_HEADERS_ONLY`. */
function formatPatch(path: string, hunks: PatchHunk[]): string {
	const ret = [`--- ${path}`, `+++ ${path}`];
	for (const hunk of hunks) {
		// Unified Diff Format quirk: If the chunk size is 0, the first number is one lower than one would expect.
		if (hunk.oldLines === 0) {
			hunk.oldStart -= 1;
		}
		if (hunk.newLines === 0) {
			hunk.newStart -= 1;
		}
		ret.push(`@@ -${hunk.oldStart},${hunk.oldLines} +${hunk.newStart},${hunk.newLines} @@`);
		for (const line of hunk.lines) {
			ret.push(line);
		}
	}
	return `${ret.join("\n")}\n`;
}

/**
 * Generate a display-oriented diff string with line numbers and context.
 * Returns both the diff string and the first changed line number (in the new file).
 */
export function generateDiffString(
	oldContent: string,
	newContent: string,
	contextLines = 4,
): { diff: string; firstChangedLine: number | undefined } {
	const lineDiff = getLineDiff(oldContent, newContent);
	const lineNumWidth = String(Math.max(lineDiff.oldSplitLength, lineDiff.newSplitLength)).length;
	const { output, firstChangedLine } = formatDisplayDiff(
		lineDiff.runs,
		getPartLines(lineDiff, false),
		1,
		1,
		lineNumWidth,
		contextLines,
	);
	return { diff: output.join("\n"), firstChangedLine };
}

/** Number of lines as `text.split("\n").length` counts them, without building the array. */
function countSplitLines(text: string): number {
	return countNewlines(text, 0, text.length) + 1;
}

/**
 * Format diff parts as display diff lines, numbering from the given 1-based old and new lines. `partLines[i]` holds the
 * lines of `parts[i]` without line feeds (an array, or the lazy PartLines of a LineDiff).
 */
function formatDisplayDiff(
	parts: readonly { added?: boolean; removed?: boolean }[],
	partLines: readonly { readonly length: number; slice(start?: number, end?: number): string[] }[],
	oldStartLine: number,
	newStartLine: number,
	lineNumWidth: number,
	contextLines: number,
): { output: string[]; firstChangedLine: number | undefined } {
	const output: string[] = [];

	let oldLineNum = oldStartLine;
	let newLineNum = newStartLine;
	let lastWasChange = false;
	let firstChangedLine: number | undefined;

	for (let i = 0; i < parts.length; i++) {
		const part = parts[i];
		const raw = partLines[i];

		if (part.added || part.removed) {
			// Capture the first changed line (in the new file)
			if (firstChangedLine === undefined) {
				firstChangedLine = newLineNum;
			}

			// Show the change
			for (const line of raw.slice()) {
				if (part.added) {
					const lineNum = String(newLineNum).padStart(lineNumWidth, " ");
					output.push(`+${lineNum} ${line}`);
					newLineNum++;
				} else {
					// removed
					const lineNum = String(oldLineNum).padStart(lineNumWidth, " ");
					output.push(`-${lineNum} ${line}`);
					oldLineNum++;
				}
			}
			lastWasChange = true;
		} else {
			// Context lines - only show a few before/after changes
			const nextPartIsChange = i < parts.length - 1 && (parts[i + 1].added || parts[i + 1].removed);
			const hasLeadingChange = lastWasChange;
			const hasTrailingChange = nextPartIsChange;

			if (hasLeadingChange && hasTrailingChange) {
				if (raw.length <= contextLines * 2) {
					for (const line of raw.slice()) {
						const lineNum = String(oldLineNum).padStart(lineNumWidth, " ");
						output.push(` ${lineNum} ${line}`);
						oldLineNum++;
						newLineNum++;
					}
				} else {
					const leadingLines = raw.slice(0, contextLines);
					const trailingLines = raw.slice(raw.length - contextLines);
					const skippedLines = raw.length - leadingLines.length - trailingLines.length;

					for (const line of leadingLines) {
						const lineNum = String(oldLineNum).padStart(lineNumWidth, " ");
						output.push(` ${lineNum} ${line}`);
						oldLineNum++;
						newLineNum++;
					}

					output.push(` ${"".padStart(lineNumWidth, " ")} ...`);
					oldLineNum += skippedLines;
					newLineNum += skippedLines;

					for (const line of trailingLines) {
						const lineNum = String(oldLineNum).padStart(lineNumWidth, " ");
						output.push(` ${lineNum} ${line}`);
						oldLineNum++;
						newLineNum++;
					}
				}
			} else if (hasLeadingChange) {
				const shownLines = raw.slice(0, contextLines);
				const skippedLines = raw.length - shownLines.length;

				for (const line of shownLines) {
					const lineNum = String(oldLineNum).padStart(lineNumWidth, " ");
					output.push(` ${lineNum} ${line}`);
					oldLineNum++;
					newLineNum++;
				}

				if (skippedLines > 0) {
					output.push(` ${"".padStart(lineNumWidth, " ")} ...`);
					oldLineNum += skippedLines;
					newLineNum += skippedLines;
				}
			} else if (hasTrailingChange) {
				const skippedLines = Math.max(0, raw.length - contextLines);
				if (skippedLines > 0) {
					output.push(` ${"".padStart(lineNumWidth, " ")} ...`);
					oldLineNum += skippedLines;
					newLineNum += skippedLines;
				}

				for (const line of raw.slice(skippedLines)) {
					const lineNum = String(oldLineNum).padStart(lineNumWidth, " ");
					output.push(` ${lineNum} ${line}`);
					oldLineNum++;
					newLineNum++;
				}
			} else {
				// Skip these context lines entirely
				oldLineNum += raw.length;
				newLineNum += raw.length;
			}

			lastWasChange = false;
		}
	}

	return { output, firstChangedLine };
}

/** Display diff and unified patch of an edit, as returned in the edit tool's details. */
export interface EditDiff {
	diff: string;
	patch: string;
	firstChangedLine: number | undefined;
}

/**
 * Unchanged lines diffed on each side of a replacement. Well above the 4 context lines shown, so jsdiff
 * aligns a change within its window as it would over the whole file.
 */
const DIFF_WINDOW_MARGIN_LINES = 32;

/**
 * Diff an edit only around its replacements, so time and memory follow the size of the change instead of
 * the file. The result matches generateDiffString and generateUnifiedPatch over the whole contents, except
 * that jsdiff may align runs of repeated lines differently within a window.
 */
export function generateEditDiff(
	path: string,
	baseContent: string,
	newContent: string,
	replacements: TextReplacement[],
	contextLines = 4,
): EditDiff {
	const lineNumWidth = String(Math.max(countSplitLines(baseContent), countSplitLines(newContent))).length;
	const skipMarker = ` ${"".padStart(lineNumWidth, " ")} ...`;
	const output: string[] = [];
	const hunks: PatchHunk[] = [];
	let firstChangedLine: number | undefined;
	for (const window of getDiffWindows(baseContent, replacements)) {
		const oldText = baseContent.slice(window.oldStart, window.oldEnd);
		const newText = newContent.slice(window.newStart, window.newEnd);
		// Pi-Bolt's line diff (computeLineDiff: jsdiff's Myers search) instead of jsdiff's diffLines and structuredPatch.
		const lineDiff = getLineDiff(oldText, newText);
		const shown = formatDisplayDiff(
			lineDiff.runs,
			getPartLines(lineDiff, false),
			window.oldLine + 1,
			window.newLine + 1,
			lineNumWidth,
			contextLines,
		);
		firstChangedLine ??= shown.firstChangedLine;
		// Both windows around a run of unchanged lines mark it as skipped; the whole-file diff shows one marker.
		if (output.at(-1) === skipMarker && shown.output[0] === skipMarker) shown.output.shift();
		// A loop, not push(...lines): spreading a replacement of many lines overflows the call stack.
		for (const line of shown.output) output.push(line);
		for (const hunk of structuredPatchHunks(oldText, newText, contextLines)) {
			hunks.push({ ...hunk, oldStart: hunk.oldStart + window.oldLine, newStart: hunk.newStart + window.newLine });
		}
	}
	return { diff: output.join("\n"), patch: formatPatch(path, hunks), firstChangedLine };
}

/** Matching ranges of the old and new contents around one or more replacements: offsets and 0-based first lines. */
interface DiffWindow {
	oldStart: number;
	oldEnd: number;
	oldLine: number;
	newStart: number;
	newEnd: number;
	newLine: number;
}

/** Start of the line that contains `offset`. */
function lineStartBefore(text: string, offset: number): number {
	// lastIndexOf treats a negative position as 0 and would find a line feed at offset 0.
	return offset === 0 ? 0 : text.lastIndexOf("\n", offset - 1) + 1;
}

/** Whole-line windows with margin around the replacements, which are sorted and disjoint; overlapping windows merge. */
function getDiffWindows(baseContent: string, replacements: TextReplacement[]): DiffWindow[] {
	const windows: DiffWindow[] = [];
	// Offset and line deltas of the replacements before the current one, and a line-counting cursor.
	let offsetDelta = 0;
	let lineDelta = 0;
	let cursorOffset = 0;
	let cursorLine = 0;
	for (const replacement of replacements) {
		const matchEnd = replacement.matchIndex + replacement.matchLength;
		let start = lineStartBefore(baseContent, replacement.matchIndex);
		for (let i = 0; i < DIFF_WINDOW_MARGIN_LINES && start > 0; i++) {
			start = lineStartBefore(baseContent, start - 1);
		}
		let end = matchEnd;
		for (let i = 0; i <= DIFF_WINDOW_MARGIN_LINES && end < baseContent.length; i++) {
			const lineFeed = baseContent.indexOf("\n", i === 0 ? Math.max(matchEnd - 1, replacement.matchIndex) : end);
			end = lineFeed === -1 ? baseContent.length : lineFeed + 1;
		}
		const replacementOffsetDelta = replacement.newText.length - replacement.matchLength;
		const replacementLineDelta =
			countNewlines(replacement.newText, 0, replacement.newText.length) -
			countNewlines(baseContent, replacement.matchIndex, matchEnd);
		const previous = windows.at(-1);
		if (previous && start <= previous.oldEnd) {
			previous.oldEnd = Math.max(previous.oldEnd, end);
			previous.newEnd = previous.oldEnd + offsetDelta + replacementOffsetDelta;
		} else {
			cursorLine += countNewlines(baseContent, cursorOffset, start);
			cursorOffset = start;
			windows.push({
				oldStart: start,
				oldEnd: end,
				oldLine: cursorLine,
				newStart: start + offsetDelta,
				newEnd: end + offsetDelta + replacementOffsetDelta,
				newLine: cursorLine + lineDelta,
			});
		}
		offsetDelta += replacementOffsetDelta;
		lineDelta += replacementLineDelta;
	}
	return windows;
}

export interface EditDiffResult {
	diff: string;
	firstChangedLine: number | undefined;
}

export interface EditDiffError {
	error: string;
}

/**
 * Compute the diff for one or more edit operations without applying them.
 * Used for preview rendering in the TUI before the tool executes.
 */
export async function computeEditsDiff(
	path: string,
	edits: Edit[],
	cwd: string,
): Promise<EditDiffResult | EditDiffError> {
	const absolutePath = resolveToCwd(path, cwd);

	try {
		// Check if file exists and is readable
		try {
			await access(absolutePath, constants.R_OK);
		} catch (error: unknown) {
			const errorMessage = error instanceof Error && "code" in error ? `Error code: ${error.code}` : String(error);
			return { error: `Could not edit file: ${path}. ${errorMessage}.` };
		}

		// Read the file
		const rawContent = await readFile(absolutePath, "utf-8");

		// Strip BOM before matching (LLM won't include invisible BOM in oldText)
		const { text: content } = splitBom(rawContent);
		const normalizedContent = normalizeToLF(content);
		const { baseContent, newContent, replacements } = applyEditsToNormalizedContent(normalizedContent, edits, path);

		// Generate the diff
		const { diff, firstChangedLine } = generateEditDiff(path, baseContent, newContent, replacements);
		return { diff, firstChangedLine };
	} catch (err) {
		return { error: err instanceof Error ? err.message : String(err) };
	}
}

/**
 * Compute the diff for a single edit operation without applying it.
 * Kept as a convenience wrapper for single-edit callers.
 */
export async function computeEditDiff(
	path: string,
	oldText: string,
	newText: string,
	cwd: string,
): Promise<EditDiffResult | EditDiffError> {
	return computeEditsDiff(path, [{ oldText, newText }], cwd);
}
