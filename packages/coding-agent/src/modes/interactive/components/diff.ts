import * as Diff from "diff";
import { theme } from "../theme/theme.ts";

/** (".*" matches no line terminator.) */
function withContent(prefix: string, lineNum: string, content: string) {
	return /[\n\r\u2028\u2029]/.test(content) ? null : { prefix, lineNum, content };
}

/**
 * Parse diff line to extract prefix, line number, and content.
 * Format: "+123 content" or "-123 content" or " 123 content" or "     ..."
 */
function parseDiffLine(line: string): { prefix: string; lineNum: string; content: string } | null {
	// What /^([+-\s])(\s*\d*)\s(.*)$/ matches, read in one pass (the expression backtracks quadratically on whitespace).
	const prefix = line[0];
	if (prefix === undefined || !(prefix === "+" || prefix === "-" || /\s/.test(prefix))) return null;
	let end = 1;
	while (end < line.length && /\s/.test(line[end]!)) end++;
	const digitsStart = end;
	while (end < line.length && line[end]! >= "0" && line[end]! <= "9") end++;
	// Digits followed by whitespace: they are the line number.
	if (end > digitsStart && end < line.length && /\s/.test(line[end]!)) {
		return withContent(prefix, line.slice(1, end), line.slice(end + 1));
	}
	// Otherwise the last of the whitespace separates (there must be one), and the rest is content.
	if (digitsStart === 1) return null;
	return withContent(prefix, line.slice(1, digitsStart - 1), line.slice(digitsStart));
}

/**
 * Replace tabs with spaces for consistent rendering.
 */
function replaceTabs(text: string): string {
	return text.replace(/\t/g, "   ");
}

/**
 * Compute word-level diff and render with inverse on changed parts.
 * Uses diffWords which groups whitespace with adjacent words for cleaner highlighting.
 * Strips leading whitespace from inverse to avoid highlighting indentation.
 */
function renderIntraLineDiff(oldContent: string, newContent: string): { removedLine: string; addedLine: string } {
	const wordDiff = Diff.diffWords(oldContent, newContent);

	let removedLine = "";
	let addedLine = "";
	let isFirstRemoved = true;
	let isFirstAdded = true;

	for (const part of wordDiff) {
		if (part.removed) {
			let value = part.value;
			// Strip leading whitespace from the first removed part
			if (isFirstRemoved) {
				const leadingWs = value.match(/^(\s*)/)?.[1] || "";
				value = value.slice(leadingWs.length);
				removedLine += leadingWs;
				isFirstRemoved = false;
			}
			if (value) {
				removedLine += theme.inverse(value);
			}
		} else if (part.added) {
			let value = part.value;
			// Strip leading whitespace from the first added part
			if (isFirstAdded) {
				const leadingWs = value.match(/^(\s*)/)?.[1] || "";
				value = value.slice(leadingWs.length);
				addedLine += leadingWs;
				isFirstAdded = false;
			}
			if (value) {
				addedLine += theme.inverse(value);
			}
		} else {
			removedLine += part.value;
			addedLine += part.value;
		}
	}

	return { removedLine, addedLine };
}

export interface RenderDiffOptions {
	/** File path (unused, kept for API compatibility) */
	filePath?: string;
}

/**
 * Render a diff string with colored lines and intra-line change highlighting.
 * - Context lines: dim/gray
 * - Removed lines: red, with inverse on changed tokens
 * - Added lines: green, with inverse on changed tokens
 */
export function renderDiff(diffText: string, _options: RenderDiffOptions = {}): string {
	const lines = diffText.split("\n");
	const result: string[] = [];

	let i = 0;
	while (i < lines.length) {
		const line = lines[i];
		const parsed = parseDiffLine(line);

		if (!parsed) {
			result.push(theme.fg("toolDiffContext", line));
			i++;
			continue;
		}

		if (parsed.prefix === "-") {
			// Collect consecutive removed lines
			const removedLines: { lineNum: string; content: string }[] = [];
			while (i < lines.length) {
				const p = parseDiffLine(lines[i]);
				if (!p || p.prefix !== "-") break;
				removedLines.push({ lineNum: p.lineNum, content: p.content });
				i++;
			}

			// Collect consecutive added lines
			const addedLines: { lineNum: string; content: string }[] = [];
			while (i < lines.length) {
				const p = parseDiffLine(lines[i]);
				if (!p || p.prefix !== "+") break;
				addedLines.push({ lineNum: p.lineNum, content: p.content });
				i++;
			}

			// Only do intra-line diffing when there's exactly one removed and one added line
			// (indicating a single line modification). Otherwise, show lines as-is.
			if (removedLines.length === 1 && addedLines.length === 1) {
				const removed = removedLines[0];
				const added = addedLines[0];

				const { removedLine, addedLine } = renderIntraLineDiff(
					replaceTabs(removed.content),
					replaceTabs(added.content),
				);

				result.push(theme.fg("toolDiffRemoved", `-${removed.lineNum} ${removedLine}`));
				result.push(theme.fg("toolDiffAdded", `+${added.lineNum} ${addedLine}`));
			} else {
				// Show all removed lines first, then all added lines
				for (const removed of removedLines) {
					result.push(theme.fg("toolDiffRemoved", `-${removed.lineNum} ${replaceTabs(removed.content)}`));
				}
				for (const added of addedLines) {
					result.push(theme.fg("toolDiffAdded", `+${added.lineNum} ${replaceTabs(added.content)}`));
				}
			}
		} else if (parsed.prefix === "+") {
			// Standalone added line
			result.push(theme.fg("toolDiffAdded", `+${parsed.lineNum} ${replaceTabs(parsed.content)}`));
			i++;
		} else {
			// Context line
			result.push(theme.fg("toolDiffContext", ` ${parsed.lineNum} ${replaceTabs(parsed.content)}`));
			i++;
		}
	}

	return result.join("\n");
}
