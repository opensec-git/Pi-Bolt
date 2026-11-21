/**
 * Shared utility for truncating text to visual lines (accounting for line wrapping).
 * Used by tool renderers and bash-execution.ts for consistent behavior.
 */

import {
	type Component,
	markReportsChanges,
	markStableRender,
	reportRenderChange,
	Text,
	truncateToWidth,
	visibleWidth,
	wrapTextWithAnsi,
} from "@earendil-works/pi-tui";

export interface VisualTruncateResult {
	/** The visual lines to display */
	visualLines: readonly string[];
	/** Number of visual lines that were skipped (hidden) */
	skippedCount: number;
}

/**
 * Truncate text to a maximum number of visual lines.
 * This accounts for line wrapping based on terminal width.
 *
 * @param text - The text content (may contain newlines)
 * @param maxVisualLines - Maximum number of visual lines to show
 * @param width - Terminal/render width
 * @param paddingX - Horizontal padding for Text component (default 0).
 *                   Use 0 when result will be placed in a Box (Box adds its own padding).
 *                   Use 1 when result will be placed in a plain Container.
 * @param keep - Which visual lines to keep: the last ones (default) or the first ones.
 * @returns The truncated visual lines and count of skipped lines
 */
export function truncateToVisualLines(
	text: string,
	maxVisualLines: number,
	width: number,
	paddingX: number = 0,
	keep: "start" | "end" = "end",
): VisualTruncateResult {
	if (!text) {
		return { visualLines: [], skippedCount: 0 };
	}

	// Create a temporary Text component to render and get visual lines
	const tempText = new Text(text, paddingX, 0);
	const allVisualLines = tempText.render(width);

	if (allVisualLines.length <= maxVisualLines) {
		return { visualLines: allVisualLines, skippedCount: 0 };
	}

	const truncatedLines =
		keep === "start" ? allVisualLines.slice(0, maxVisualLines) : allVisualLines.slice(-maxVisualLines);
	const skippedCount = allVisualLines.length - maxVisualLines;

	return { visualLines: truncatedLines, skippedCount };
}

/**
 * truncateToVisualLines(lines.map(styleLine).join("\n"), maxVisualLines, width, 0, "end") for lines without ANSI codes and
 * a style that closes what it opens on each line: only the last lines are styled and wrapped. The lines before them are
 * only counted, and a line that fits the width counts as one visual line without being wrapped. A long output shown
 * collapsed is rendered at the cost of its last lines, not all of it.
 */
export function truncateStyledLinesToVisualEnd(
	lines: readonly string[],
	styleLine: (line: string) => string,
	maxVisualLines: number,
	width: number,
): VisualTruncateResult {
	if (lines.length === 0) {
		return { visualLines: [], skippedCount: 0 };
	}
	const contentWidth = Math.max(1, width);
	// (Text replaces each tab with three spaces before wrapping.)
	const visualCount = (line: string): number => {
		const normalized = line.replace(/\t/g, "   ");
		return normalized === "" || visibleWidth(normalized) <= contentWidth
			? 1
			: wrapTextWithAnsi(styleLine(normalized), contentWidth).length;
	};
	let start = lines.length;
	let tailVisualLines = 0;
	while (start > 0 && tailVisualLines < maxVisualLines) {
		start--;
		tailVisualLines += visualCount(lines[start]!);
	}
	let headVisualLines = 0;
	for (let i = 0; i < start; i++) headVisualLines += visualCount(lines[i]!);

	const tail = new Text(lines.slice(start).map(styleLine).join("\n"), 0, 0).render(width);
	const total = headVisualLines + tail.length;
	if (total <= maxVisualLines) {
		return { visualLines: tail, skippedCount: 0 };
	}
	return { visualLines: tail.slice(-maxVisualLines), skippedCount: total - maxVisualLines };
}

interface VisualLinePreviewBaseOptions {
	maxVisualLines: number;
	/** Styled hint line for the given number of hidden visual lines. */
	formatHint: (hidden: number) => string;
}

export type VisualLinePreviewOptions = VisualLinePreviewBaseOptions &
	(
		| {
				/** Styled text; may contain newlines. */
				text: string;
				/** Which visual lines to keep. The hint goes before kept end lines and after kept start lines. */
				keep: "start" | "end";
		  }
		| {
				/** Lines without ANSI codes, styled by `styleLine` (truncateStyledLinesToVisualEnd()). */
				lines: readonly string[];
				styleLine: (line: string) => string;
				keep: "end";
		  }
	);

/**
 * Collapsed tool output limited to a number of visual lines, like bash output. Limiting logical
 * lines instead lets a single long line (such as minified JSON) wrap across the whole screen.
 * Caches its lines per width, since it renders on every frame for every result in the transcript.
 */
export class VisualLinePreview implements Component {
	private options: VisualLinePreviewOptions;
	private cachedWidth: number | undefined;
	private cachedLines: readonly string[] | undefined;

	constructor(options: VisualLinePreviewOptions) {
		this.options = options;
	}

	render(width: number): readonly string[] {
		if (this.cachedLines === undefined || this.cachedWidth !== width) {
			const options = this.options;
			const { maxVisualLines, keep, formatHint } = options;
			const preview =
				"lines" in options
					? truncateStyledLinesToVisualEnd(options.lines, options.styleLine, maxVisualLines, width)
					: truncateToVisualLines(options.text, maxVisualLines, width, 0, keep);
			const lines = preview.visualLines;
			if (preview.skippedCount > 0) {
				const hint = truncateToWidth(formatHint(preview.skippedCount), width, "...");
				this.cachedLines = keep === "start" ? [...lines, hint] : [hint, ...lines];
			} else {
				this.cachedLines = lines;
			}
			this.cachedWidth = width;
		}
		return this.cachedLines;
	}

	invalidate(): void {
		this.cachedWidth = undefined;
		this.cachedLines = undefined;
		reportRenderChange(this);
	}
}

markStableRender(VisualLinePreview);
markReportsChanges(VisualLinePreview);
