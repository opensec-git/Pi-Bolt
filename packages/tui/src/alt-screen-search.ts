import { Input } from "./components/input.ts";
import { getKeybindings } from "./keybindings.ts";
import type { Component, Focusable } from "./tui.ts";
import { getGraphemeSegmenter, stripTerminalSequences, truncateToWidth, visibleWidth } from "./utils.ts";

interface SearchSourceSpan {
	textStart: number;
	textEnd: number;
	startCol: number;
	endCol: number;
	linearColumns: boolean;
}

interface SearchCorpus {
	text: string;
	/** End of normalized text through each row, including preceding separators. */
	rowEnds: number[];
	/** True where a normalized UTF-16 offset is already a terminal column. */
	directColumns: boolean[];
}

export interface AltScreenSearchSegment {
	row: number;
	startCol: number;
	endCol: number;
}

export interface AltScreenSearchMatch {
	segments: AltScreenSearchSegment[];
}

const PRINTABLE_ASCII = /^[\x20-\x7e]*$/;

/** Normalize one row. Column mappings are needed only while emitting a match on this row. */
function normalizeRow(plain: string, offset: number, spans?: SearchSourceSpan[]): string {
	if (!spans && PRINTABLE_ASCII.test(plain)) return plain.trim().replace(/ {2,}/g, " ");

	let result = "";
	let separator = false;
	let column = 0;
	const ascii = PRINTABLE_ASCII.test(plain);
	const append = (text: string, width: number, space: boolean): void => {
		if (space) {
			separator = result.length > 0;
		} else {
			if (separator) result += " ";
			const start = offset + result.length;
			result += text;
			spans?.push({
				textStart: start,
				textEnd: offset + result.length,
				startCol: column,
				endCol: column + width,
				linearColumns: ascii,
			});
			separator = false;
		}
		column += width;
	};
	if (ascii) {
		for (const run of plain.matchAll(/ +|[^ ]+/g)) append(run[0], run[0].length, run[0][0] === " ");
	} else {
		for (const { segment } of getGraphemeSegmenter().segment(plain)) {
			append(segment, spans ? visibleWidth(segment) : 0, /^\s+$/u.test(segment));
		}
	}
	return result;
}

function buildSearchCorpus(lines: readonly string[], from = 0, previous?: SearchCorpus): SearchCorpus {
	const rowEnds = previous?.rowEnds ?? [];
	const directColumns = previous?.directColumns ?? [];
	let length = from > 0 ? rowEnds[from - 1]! : 0;
	const chunks = length > 0 ? [previous!.text.slice(0, length)] : [];
	for (let row = from; row < lines.length; row++) {
		const plain = stripTerminalSequences(lines[row] ?? "");
		const direct = PRINTABLE_ASCII.test(plain) && !plain.startsWith(" ") && !plain.includes("  ");
		directColumns[row] = direct;
		const text = direct ? plain.trimEnd() : normalizeRow(plain, 0);
		if (text) {
			if (length > 0) length++;
			length += text.length;
			chunks.push(text);
		}
		rowEnds[row] = length;
	}
	rowEnds.length = lines.length;
	directColumns.length = lines.length;
	return { text: chunks.join(" "), rowEnds, directColumns };
}

function normalizeQuery(query: string): string {
	return query.replace(/\s+/gu, " ").trim();
}

function escapeRegExp(text: string): string {
	return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

/** The first offset strictly beyond the unchanged prefix. */
function firstEndAfter(ends: readonly number[], offset: number): number {
	let low = 0;
	let high = ends.length;
	while (low < high) {
		const mid = low + Math.floor((high - low) / 2);
		if (ends[mid]! <= offset) low = mid + 1;
		else high = mid;
	}
	return low;
}

function findSearchCorpusMatches(
	corpus: SearchCorpus,
	lines: readonly string[],
	normalizedQuery: string,
	matches: AltScreenSearchMatch[] = [],
	matchEnds?: number[],
	unchangedLength = 0,
): AltScreenSearchMatch[] {
	if (!normalizedQuery) return matches;
	const expression = new RegExp(escapeRegExp(normalizedQuery), "giu");
	// Matches entirely inside the prefix were already found. Resume far enough back
	// for a match crossing the edit. Unicode simple case folding matches one code
	// point per literal, and a code point occupies at most two UTF-16 units.
	const lastEnd = matchEnds?.[matchEnds.length - 1] ?? 0;
	expression.lastIndex = Math.max(lastEnd, unchangedLength - 2 * normalizedQuery.length);
	let row = firstEndAfter(corpus.rowEnds, expression.lastIndex);
	let mappedRow = -1;
	let spans: SearchSourceSpan[] = [];
	let spanIndex = 0;
	let directColumns = false;
	for (const match of corpus.text.matchAll(expression)) {
		const start = match.index;
		const end = start + match[0].length;
		while (corpus.rowEnds[row]! <= start) row++;
		const segments: AltScreenSearchSegment[] = [];
		for (; row < lines.length; row++) {
			const previousEnd = row === 0 ? 0 : corpus.rowEnds[row - 1]!;
			const rowStart = previousEnd === 0 ? 0 : previousEnd + 1;
			if (rowStart >= end) break;
			if (corpus.rowEnds[row]! <= rowStart) continue; // blank row
			if (mappedRow !== row) {
				mappedRow = row;
				directColumns = corpus.directColumns[row]!;
				if (!directColumns) {
					spans = [];
					normalizeRow(stripTerminalSequences(lines[row] ?? ""), rowStart, spans);
					spanIndex = 0;
				}
			}
			if (directColumns) {
				const stop = Math.min(end, corpus.rowEnds[row]!);
				let pos = Math.max(start, rowStart);
				while (pos < stop) {
					const space = match[0].indexOf(" ", pos - start);
					const wordEnd = space === -1 ? stop : Math.min(stop, start + space);
					if (pos < wordEnd) segments.push({ row, startCol: pos - rowStart, endCol: wordEnd - rowStart });
					pos = wordEnd + 1;
				}
			} else {
				while (spanIndex < spans.length && spans[spanIndex]!.textEnd <= start) spanIndex++;
				for (let i = spanIndex; i < spans.length; i++) {
					const span = spans[i]!;
					if (span.textStart >= end) break;
					const startCol = span.linearColumns
						? span.startCol + Math.max(0, start - span.textStart)
						: span.startCol;
					const endCol = span.linearColumns ? span.endCol - Math.max(0, span.textEnd - end) : span.endCol;
					const prior = segments[segments.length - 1];
					if (prior && prior.row === row && startCol <= prior.endCol)
						prior.endCol = Math.max(prior.endCol, endCol);
					else segments.push({ row, startCol, endCol });
				}
			}
			if (corpus.rowEnds[row]! >= end) break;
		}
		if (segments.length > 0) {
			matches.push({ segments });
			matchEnds?.push(end);
		}
	}

	return matches;
}

export interface AltScreenSearchResult {
	matches: AltScreenSearchMatch[];
	changed: boolean;
}

/** Cache the searchable corpus and matches while rendered transcript lines remain unchanged. */
export class AltScreenSearchIndex {
	private sourceLines: string[] | undefined;
	private corpus: SearchCorpus | undefined;
	private normalizedQuery: string | undefined;
	private matches: AltScreenSearchMatch[] = [];
	private readonly matchEnds: number[] = [];

	search(lines: readonly string[], query: string): AltScreenSearchResult {
		const before = this.sourceLines;
		let from = 0;
		if (before) {
			const common = Math.min(before.length, lines.length);
			while (from < common && before[from] === lines[from]) from++;
		}
		const sourceChanged = !before || from !== before.length || from !== lines.length;
		const prefixLength = from > 0 ? this.corpus!.rowEnds[from - 1]! : 0;
		if (sourceChanged) {
			const snapshot = before ?? [];
			snapshot.length = lines.length;
			for (let i = from; i < lines.length; i++) snapshot[i] = lines[i]!;
			this.sourceLines = snapshot;
			// A substring can retain the entire previous transcript. Rebuild the smaller
			// corpus when truncation discards most of the normalized text.
			const old = this.corpus;
			const rebuild = old && prefixLength < old.text.length / 2;
			this.corpus = buildSearchCorpus(lines, rebuild ? 0 : from, old);
		}

		const normalizedQuery = normalizeQuery(query);
		const changed = sourceChanged || normalizedQuery !== this.normalizedQuery;
		if (changed) {
			const searchablePrefix = normalizedQuery === this.normalizedQuery ? prefixLength : 0;
			const keep = firstEndAfter(this.matchEnds, searchablePrefix);
			this.matchEnds.length = keep;
			this.normalizedQuery = normalizedQuery;
			this.matches = findSearchCorpusMatches(
				this.corpus!,
				lines,
				normalizedQuery,
				this.matches.slice(0, keep),
				this.matchEnds,
				searchablePrefix,
			);
		}
		return { matches: this.matches, changed };
	}
}

export function findAltScreenSearchMatches(lines: readonly string[], query: string): AltScreenSearchMatch[] {
	const normalizedQuery = normalizeQuery(query);
	return normalizedQuery ? findSearchCorpusMatches(buildSearchCorpus(lines), lines, normalizedQuery) : [];
}

export function getAltScreenSearchMatchKey(match: AltScreenSearchMatch): string {
	const first = match.segments[0];
	const last = match.segments[match.segments.length - 1];
	return first && last ? `${first.row}:${first.startCol}:${last.row}:${last.endCol}` : "";
}

export class AltScreenSearchComponent implements Component, Focusable {
	private readonly input = new Input({
		prompt: " ",
		placeholder: "Find in transcript",
		placeholderStyle: (text) => `\x1b[2m${text}\x1b[22m`,
	});
	private readonly onQueryChange: (query: string) => void;
	private readonly navigationButtonStyle: (text: string, hovered: boolean) => string;
	private resultCount = 0;
	private resultIndex = -1;
	private previousButtonStart = -1;
	private previousButtonEnd = -1;
	private nextButtonStart = -1;
	private nextButtonEnd = -1;
	private hoveredNavigationDirection: -1 | 1 | undefined;
	private _focused = false;

	constructor(
		onQueryChange: (query: string) => void,
		navigationButtonStyle: (text: string, hovered: boolean) => string = (text) => text,
	) {
		this.onQueryChange = onQueryChange;
		this.navigationButtonStyle = navigationButtonStyle;
	}

	get focused(): boolean {
		return this._focused;
	}

	set focused(value: boolean) {
		this._focused = value;
		this.input.focused = value;
	}

	setResult(index: number, count: number): void {
		this.resultIndex = index;
		this.resultCount = count;
	}

	getNavigationDirectionAt(row: number, column: number): -1 | 1 | undefined {
		if (row !== 2) return undefined;
		if (column >= this.previousButtonStart && column < this.previousButtonEnd) return -1;
		if (column >= this.nextButtonStart && column < this.nextButtonEnd) return 1;
		return undefined;
	}

	setHoveredNavigationDirection(direction: -1 | 1 | undefined): boolean {
		if (direction === this.hoveredNavigationDirection) return false;
		this.hoveredNavigationDirection = direction;
		return true;
	}

	handleInput(data: string): void {
		const previous = this.input.getValue();
		this.input.handleInput(data);
		const query = this.input.getValue();
		if (query !== previous) this.onQueryChange(query);
	}

	invalidate(): void {
		this.input.invalidate();
	}

	render(width: number): string[] {
		const safeWidth = Math.max(1, width);
		const innerWidth = Math.max(0, safeWidth - 2);
		const formatKey = (key: string | undefined): string =>
			key
				? key
						.split("+")
						.map((part) => {
							if (process.platform === "darwin" && part.toLowerCase() === "alt") return "Option";
							return part.charAt(0).toUpperCase() + part.slice(1);
						})
						.join("+")
				: "Unbound";
		const keybindings = getKeybindings();
		const previousKey = formatKey(keybindings.getKeys("tui.altScreen.searchPrevious")[0]);
		const nextKey = formatKey(keybindings.getKeys("tui.altScreen.searchNext")[0]);
		const query = this.input.getValue();
		const result = !query
			? ""
			: this.resultCount === 0
				? "No matches"
				: `${this.resultIndex + 1}/${this.resultCount}`;
		const resultSpace = Math.max(0, innerWidth - 3);
		const visibleResult = truncateToWidth(result, resultSpace, "");
		const resultText = visibleResult ? `\x1b[2m ${visibleResult} \x1b[22m` : "";
		const inputWidth = Math.max(0, innerWidth - visibleWidth(resultText));
		const inputLine = truncateToWidth(this.input.render(Math.max(1, inputWidth))[0] ?? "", inputWidth, "");
		const inputPadding = " ".repeat(Math.max(0, inputWidth - visibleWidth(inputLine)));
		const content = `${inputLine}${inputPadding}${resultText}`;

		let previousButton = `↑ ${previousKey}`;
		let nextButton = `↓ ${nextKey}`;
		let separator = " · ";
		const outerGapWidth = 1;
		const availableControlsWidth = Math.max(0, innerWidth - outerGapWidth * 2 - 1);
		let controlsWidth = visibleWidth(previousButton) + visibleWidth(separator) + visibleWidth(nextButton);
		if (controlsWidth > availableControlsWidth) {
			previousButton = "↑";
			nextButton = "↓";
			separator = " ";
			controlsWidth = visibleWidth(previousButton) + visibleWidth(separator) + visibleWidth(nextButton);
		}
		const showButtons = controlsWidth <= availableControlsWidth;
		const renderedButtons = showButtons
			? this.navigationButtonStyle(previousButton, this.hoveredNavigationDirection === -1) +
				separator +
				this.navigationButtonStyle(nextButton, this.hoveredNavigationDirection === 1)
			: "";
		const outerGapsWidth = showButtons ? outerGapWidth * 2 : 0;
		const rightRuleWidth = renderedButtons && innerWidth > controlsWidth + outerGapsWidth ? 1 : 0;
		const leftRuleWidth = Math.max(
			0,
			innerWidth - (showButtons ? controlsWidth : 0) - outerGapsWidth - rightRuleWidth,
		);
		const previousStart = 1 + leftRuleWidth + outerGapWidth;
		this.previousButtonStart = showButtons ? previousStart : -1;
		this.previousButtonEnd = showButtons ? previousStart + visibleWidth(previousButton) : -1;
		this.nextButtonStart = showButtons ? this.previousButtonEnd + visibleWidth(separator) : -1;
		this.nextButtonEnd = showButtons ? this.nextButtonStart + visibleWidth(nextButton) : -1;

		if (safeWidth === 1) return ["┌", "│", "└"];
		return [
			`┌${"─".repeat(innerWidth)}┐`,
			`│${content}│`,
			`└${"─".repeat(leftRuleWidth)}${renderedButtons ? " " : ""}${renderedButtons}${renderedButtons ? " " : ""}${"─".repeat(rightRuleWidth)}┘`,
		];
	}
}
