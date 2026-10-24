import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import { deleteKittyImage, isImageLine } from "./terminal-image.ts";
import { type LiveLines, renderLiveLines, renderSharedLines, type TUI, TuiBase, type TuiStopOptions } from "./tui.ts";
import { visibleWidth } from "./utils.ts";

const KITTY_SEQUENCE_PREFIX = "\x1b_G";
const MAX_RENDER_WRITE_CHARS = 1024 * 1024;

/**
 * Streams terminal output in 1 MiB chunks so a full render never forms one string large enough to exceed V8's limit.
 *
 * `append()` fills the current chunk and flushes it when full. Oversized input is split at chunk boundaries, preserving
 * surrogate pairs so each write remains valid UTF-16. Callers append synchronized-output begin/end sequences themselves;
 * the final `flush()` writes any remainder, including the end sequence.
 */
class BoundedTerminalWriter {
	private buffer = "";
	private writtenChars = 0;
	private readonly write: (data: string) => void;

	constructor(write: (data: string) => void) {
		this.write = write;
	}

	/**
	 * Append terminal data, flushing full chunks as needed. Callers must call `flush()` after the final append.
	 * @param value Terminal data to write in order; oversized values are split without splitting surrogate pairs.
	 */
	append(value: string): void {
		let offset = 0;
		while (offset < value.length) {
			const capacity = MAX_RENDER_WRITE_CHARS - this.buffer.length;
			if (capacity === 0) {
				this.flush();
				continue;
			}

			let end = Math.min(value.length, offset + capacity);
			if (
				end < value.length &&
				value.charCodeAt(end - 1) >= 0xd800 &&
				value.charCodeAt(end - 1) <= 0xdbff &&
				value.charCodeAt(end) >= 0xdc00 &&
				value.charCodeAt(end) <= 0xdfff
			) {
				end--;
			}
			if (end === offset) {
				this.flush();
				continue;
			}

			this.buffer += value.slice(offset, end);
			offset = end;
			if (this.buffer.length === MAX_RENDER_WRITE_CHARS) {
				this.flush();
			}
		}
	}

	/** Write the current chunk, if any, and retain only its character count for debug output. */
	flush(): void {
		if (!this.buffer) return;
		this.write(this.buffer);
		this.writtenChars += this.buffer.length;
		this.buffer = "";
	}

	get length(): number {
		return this.writtenChars + this.buffer.length;
	}
}

interface KittyImageHeader {
	ids: number[];
	rows: number;
}

function parseKittyImageHeader(line: string): KittyImageHeader | undefined {
	const sequenceStart = line.indexOf(KITTY_SEQUENCE_PREFIX);
	if (sequenceStart === -1) return undefined;
	const paramsStart = sequenceStart + KITTY_SEQUENCE_PREFIX.length;
	const paramsEnd = line.indexOf(";", paramsStart);
	if (paramsEnd === -1) return undefined;

	const ids: number[] = [];
	let rows = 1;
	for (const param of line.slice(paramsStart, paramsEnd).split(",")) {
		const [key, value] = param.split("=", 2);
		if (value === undefined) continue;
		const numberValue = Number(value);
		if (!Number.isInteger(numberValue) || numberValue <= 0 || numberValue > 0xffffffff) continue;
		if (key === "i") ids.push(numberValue);
		else if (key === "r") rows = numberValue;
	}
	return { ids, rows };
}

function extractKittyImageIds(line: string): number[] {
	return parseKittyImageHeader(line)?.ids ?? [];
}

function extractKittyImageRows(line: string): number {
	return parseKittyImageHeader(line)?.rows ?? 1;
}

function isTermuxSession(): boolean {
	return Boolean(process.env.TERMUX_VERSION);
}

export interface TuiMainScreenRenderState {
	previousLines: string[];
	previousWidth: number;
	previousHeight: number;
	cursorRow: number;
	hardwareCursorRow: number;
	maxLinesRendered: number;
	previousViewportTop: number;
}

/** TUI implementation that renders into the terminal's main screen and scrollback. */
export class TuiMainScreen extends TuiBase implements TUI {
	readonly mode = "regular" as const;
	private previousLines: string[] = [];
	private previousKittyImageIds = new Set<number>();
	private previousWidth = 0;
	private previousHeight = 0;
	private cursorRow = 0;
	private hardwareCursorRow = 0;
	private maxLinesRendered = 0;
	private previousViewportTop = 0;

	captureRenderState(): TuiMainScreenRenderState {
		return {
			previousLines: [...this.previousLines],
			previousWidth: this.previousWidth,
			previousHeight: this.previousHeight,
			cursorRow: this.cursorRow,
			hardwareCursorRow: this.hardwareCursorRow,
			maxLinesRendered: this.maxLinesRendered,
			previousViewportTop: this.previousViewportTop,
		};
	}

	restoreRenderState(state: TuiMainScreenRenderState): void {
		this.frameLive = undefined;
		this.previousLines = state.previousLines.map((line) => (isImageLine(line) ? "" : line));
		this.previousKittyImageIds = new Set();
		this.previousWidth = state.previousWidth;
		this.previousHeight = state.previousHeight;
		this.cursorRow = state.cursorRow;
		this.hardwareCursorRow = state.hardwareCursorRow;
		this.maxLinesRendered = state.maxLinesRendered;
		this.previousViewportTop = state.previousViewportTop;
	}

	protected override resetRenderState(): void {
		this.frameLive = undefined;
		this.previousLines = [];
		this.previousWidth = -1;
		this.previousHeight = -1;
		this.cursorRow = 0;
		this.hardwareCursorRow = 0;
		this.maxLinesRendered = 0;
		this.previousViewportTop = 0;
	}

	protected override beforeTerminalStop(options: TuiStopOptions): void {
		if (options.preserveScreen || this.previousLines.length === 0) return;
		this.terminal.write(" ");
		const targetRow = this.previousLines.length;
		const lineDiff = targetRow - this.hardwareCursorRow;
		if (lineDiff > 0) this.terminal.write(`\x1b[${lineDiff}B`);
		else if (lineDiff < 0) this.terminal.write(`\x1b[${-lineDiff}A`);
		this.terminal.write("\r\n");
	}

	/** The lines of the last frame before and after resolveFakeCursors(), and whether the hardware cursor was shown. */
	private unresolvedLines: string[] = [];
	private resolvedLines: string[] = [];
	private resolvedWithHardwareCursor = false;

	/**
	 * resolveFakeCursors() for a frame, taking each line that is the same string as the frame before from the frame
	 * before: resolving looks for a cursor in every line of the transcript, each frame (0.5 ms at 27,500 lines). Returns
	 * a new array, which the frame may change (extractCursorPosition() does).
	 */
	private resolveFrameFakeCursors(lines: string[], unchanged: number): string[] {
		const showHardwareCursor = this.getShowHardwareCursor();
		const previous = showHardwareCursor === this.resolvedWithHardwareCursor ? this.unresolvedLines : [];
		const previousResolved = this.resolvedLines;
		// (The first `unchanged` lines are those of the frame before: taken as they were resolved, without reading them.)
		const from = previous.length === 0 ? 0 : Math.min(unchanged, previous.length, lines.length);
		const resolved: string[] = from > 0 ? previousResolved.slice(0, from) : [];
		resolved.length = lines.length;
		const single = [""];
		for (let i = from; i < lines.length; i++) {
			const line = lines[i]!;
			if (i < previous.length && line === previous[i]) {
				resolved[i] = previousResolved[i]!;
			} else {
				single[0] = line;
				resolved[i] = this.resolveFakeCursors(single)[0]!;
			}
		}
		this.unresolvedLines = lines;
		this.resolvedLines = resolved;
		this.resolvedWithHardwareCursor = showHardwareCursor;
		return resolved.slice();
	}

	/** The lines of the last frame before and after applyLineResets(): finishLines(). */
	private rawLines: string[] = [];
	private finishedLines: string[] = [];
	/** The rows of finishedLines that are images (applyLineResets() leaves them as they are), in order. */
	private finishedImageRows: number[] = [];
	/** The root's live lines and the revision the last frame was rendered from (renderLiveLines()). */
	private frameLive?: { live: LiveLines; revision: number; width: number; lines: string[] };
	/**
	 * The finished lines of the frame before finishedLines' (an array no longer shown), which the next frame's finished
	 * lines are written into instead of a new array, and how many of its leading lines are those of finishedLines.
	 */
	private spareFinishedLines?: string[];
	private spareFinishedAgree = 0;

	/**
	 * applyLineResets() for a frame, taking each line that is the same string as the frame before from the frame before:
	 * most lines of a long transcript are. Unchanged lines are then the same strings as the lines shown, so the diff
	 * below compares them by identity. Also counts image lines: applyLineResets() leaves only those as they are.
	 * `previousRawTail` holds the frame before's raw lines from `unchanged` on when `raw` is that frame's array, changed in
	 * place from there (renderChangedLines()); otherwise rawLines is that frame's.
	 */
	private finishLines(
		raw: string[],
		unchanged: number,
		previousRawTail?: readonly string[],
	): { lines: string[]; imageLines: number } {
		const previousFinished = this.finishedLines;
		const previousRawLength =
			previousRawTail === undefined ? this.rawLines.length : unchanged + previousRawTail.length;
		const previousRawAt = (i: number): string | undefined =>
			previousRawTail === undefined ? this.rawLines[i] : previousRawTail[i - unchanged];
		// (The first `unchanged` lines are those of the frame before: taken as they were finished, without reading them.)
		const from = Math.min(unchanged, previousRawLength, raw.length);
		const lines = this.finishedLinesFrom(from);
		lines.length = raw.length;
		const imageRows = this.finishedImageRows.filter((row) => row < from);
		const single = [""];
		for (let i = from; i < raw.length; i++) {
			const line = raw[i]!;
			let finished: string;
			if (i < previousRawLength && line === previousRawAt(i)) {
				finished = previousFinished[i]!;
			} else {
				single[0] = line;
				finished = this.applyLineResets(single)[0]!;
			}
			if (finished === line) imageRows.push(i);
			lines[i] = finished;
		}
		this.rawLines = raw;
		this.spareFinishedLines = previousFinished;
		this.spareFinishedAgree = from;
		this.finishedLines = lines;
		this.finishedImageRows = imageRows;
		return { lines, imageLines: imageRows.length };
	}

	/**
	 * An array whose first `count` lines are those of finishedLines, for a frame's finished lines: the spare array
	 * (spareFinishedLines) brought up to date when it is not shown or in use, which copies only the lines it misses
	 * instead of all `count` of them; a new array otherwise.
	 */
	private finishedLinesFrom(count: number): string[] {
		const spare = this.spareFinishedLines;
		this.spareFinishedLines = undefined;
		const finished = this.finishedLines;
		if (spare === undefined || spare === this.previousLines || spare === finished) {
			return count > 0 ? finished.slice(0, count) : [];
		}
		const agree = Math.min(this.spareFinishedAgree, count, spare.length);
		spare.length = agree;
		for (let i = agree; i < count; i++) spare.push(finished[i]!);
		return spare;
	}

	/**
	 * The lines of a frame whose first `unchanged` lines are those of the frame before (renderLiveLines() says so, and no
	 * overlay is shown), made by changing the last frame's arrays in place from that line on: the root's lines
	 * (frameLive.lines), resolveFakeCursors()'s, and the raw lines after the cursor marker is taken out. Line for line
	 * what resolveFrameFakeCursors(), extractCursorPosition() and finishLines() make of a copy of the root's lines, but
	 * without copying the lines above `unchanged`: a frame then costs time in proportion to what changed, not to the
	 * length of the transcript. Undefined when the last frame's arrays are not those this needs (resolveFrameFakeCursors()
	 * and finishLines() then make the frame).
	 */
	private renderChangedLines(
		live: LiveLines,
		root: string[],
		unchanged: number,
		height: number,
	): { lines: string[]; imageLines: number; cursorPos: { row: number; col: number } | null } | undefined {
		const resolved = this.resolvedLines;
		const raw = this.rawLines;
		if (
			unchanged <= 0 ||
			this.unresolvedLines !== root ||
			resolved.length !== root.length ||
			raw === resolved ||
			raw === root ||
			raw.length < unchanged ||
			this.finishedLines.length !== raw.length
		) {
			return undefined;
		}
		const source = live.lines;
		const rootTail = root.slice(unchanged);
		root.length = unchanged;
		for (let i = unchanged; i < source.length; i++) root.push(source[i]!);

		const resolvedTail = resolved.slice(unchanged);
		resolved.length = unchanged;
		const single = [""];
		for (let i = unchanged; i < root.length; i++) {
			const line = root[i]!;
			const k = i - unchanged;
			if (k < rootTail.length && line === rootTail[k]) {
				resolved.push(resolvedTail[k]!);
			} else {
				single[0] = line;
				resolved.push(this.resolveFakeCursors(single)[0]!);
			}
		}

		const rawTail = raw.slice(unchanged);
		raw.length = unchanged;
		for (let i = unchanged; i < resolved.length; i++) raw.push(resolved[i]!);
		// (Only the last `height` lines are read, all of them from `unchanged` on.)
		const cursorPos = this.extractCursorPosition(raw, height);
		const finished = this.finishLines(raw, unchanged, rawTail);
		return { ...finished, cursorPos };
	}

	private collectKittyImageIds(lines: string[]): Set<number> {
		const ids = new Set<number>();
		for (const line of lines) {
			for (const id of extractKittyImageIds(line)) {
				ids.add(id);
			}
		}
		return ids;
	}

	private deleteKittyImages(ids: Iterable<number>): string {
		let buffer = "";
		for (const id of ids) {
			buffer += deleteKittyImage(id);
		}
		return buffer;
	}

	private getKittyImageReservedRows(lines: string[], index: number, maxIndex = lines.length - 1): number {
		const rows = extractKittyImageRows(lines[index] ?? "");
		if (rows <= 1) return 1;

		const maxRows = Math.min(rows, maxIndex - index + 1, lines.length - index);
		let reservedRows = 1;
		while (reservedRows < maxRows) {
			const line = lines[index + reservedRows] ?? "";
			if (isImageLine(line) || visibleWidth(line) > 0) break;
			reservedRows++;
		}
		return reservedRows;
	}

	private expandChangedRangeForKittyImages(
		firstChanged: number,
		lastChanged: number,
		newLines: string[],
	): { firstChanged: number; lastChanged: number } {
		let expandedFirstChanged = firstChanged;
		let expandedLastChanged = lastChanged;
		const expandForLines = (lines: string[]): void => {
			for (let i = 0; i < lines.length; i++) {
				if (extractKittyImageIds(lines[i]).length === 0) continue;
				const blockEnd = i + this.getKittyImageReservedRows(lines, i) - 1;
				if (i >= firstChanged || (i <= lastChanged && blockEnd >= firstChanged)) {
					expandedFirstChanged = Math.min(expandedFirstChanged, i);
					expandedLastChanged = Math.max(expandedLastChanged, blockEnd);
				}
			}
		};

		expandForLines(this.previousLines);
		expandForLines(newLines);
		return { firstChanged: expandedFirstChanged, lastChanged: expandedLastChanged };
	}

	private deleteChangedKittyImages(firstChanged: number, lastChanged: number): string {
		if (firstChanged < 0 || lastChanged < firstChanged || this.previousKittyImageIds.size === 0) return "";

		const ids = new Set<number>();
		const maxLine = Math.min(lastChanged, this.previousLines.length - 1);
		for (let i = firstChanged; i <= maxLine; i++) {
			for (const id of extractKittyImageIds(this.previousLines[i] ?? "")) {
				ids.add(id);
			}
		}

		return this.deleteKittyImages(ids);
	}

	protected doRender(): void {
		if (this.stopped) return;
		const width = this.terminal.columns;
		const height = this.terminal.rows;
		const widthChanged = this.previousWidth !== 0 && this.previousWidth !== width;
		const heightChanged = this.previousHeight !== 0 && this.previousHeight !== height;
		const previousBufferLength = this.previousHeight > 0 ? this.previousViewportTop + this.previousHeight : height;
		let prevViewportTop = heightChanged ? Math.max(0, previousBufferLength - height) : this.previousViewportTop;
		let viewportTop = prevViewportTop;
		let hardwareCursorRow = this.hardwareCursorRow;
		const computeLineDiff = (targetRow: number): number => {
			const currentScreenRow = hardwareCursorRow - prevViewportTop;
			const targetScreenRow = targetRow - viewportTop;
			return targetScreenRow - currentScreenRow;
		};

		// Render all components to get new lines
		// Resolve fake cursors before compositing so overlays and line slicing see plain SGR codes
		// The leading lines that are what they were the frame before: the root's lines up to the first one changed
		// (renderLiveLines()), above the rows the cursor and overlays are drawn in, this frame and the one before. Those are
		// not read again: a frame costs time in proportion to what changed, not to the length of the transcript.
		const live = renderLiveLines(this, width);
		const before = this.frameLive;
		let unchanged = 0;
		if (
			live !== undefined &&
			before?.live === live &&
			before.width === width &&
			this.previousWidth === width &&
			this.previousHeight === height &&
			this.getShowHardwareCursor() === this.resolvedWithHardwareCursor &&
			!this.hasOverlayEntries
		) {
			unchanged = Math.min(
				live.changedSince(before.revision),
				before.lines.length,
				live.lines.length - height,
				this.previousLines.length - height,
			);
		}
		unchanged = Math.max(0, unchanged);
		// (When the lines above `unchanged` are those of the last frame, its arrays are changed from there on.)
		const changed =
			live !== undefined && before !== undefined && unchanged > 0
				? this.renderChangedLines(live, before.lines, unchanged, height)
				: undefined;
		let newLines: string[];
		let cursorPos: { row: number; col: number } | null;
		let finished: { lines: string[]; imageLines: number };
		if (changed !== undefined) {
			this.frameLive = { live: live!, revision: live!.revision, width, lines: before!.lines };
			cursorPos = changed.cursorPos;
			finished = changed;
		} else {
			// (A copy of the live lines, which change at the next render: made again only when they changed.)
			const root =
				live === undefined
					? renderSharedLines(this, width)
					: before?.live === live && before.width === width && before.revision === live.revision
						? before.lines
						: live.lines.slice();
			this.frameLive = live === undefined ? undefined : { live, revision: live.revision, width, lines: root };
			newLines = this.resolveFrameFakeCursors(root, unchanged);

			// Composite overlays into the rendered lines (before differential compare)
			if (this.hasOverlayEntries) {
				newLines = this.compositeOverlays(newLines, width, height);
			}

			// Extract cursor position before applying line resets (marker must be found first)
			cursorPos = this.extractCursorPosition(newLines, height);

			finished = this.finishLines(newLines, unchanged);
		}
		newLines = finished.lines;
		// Kitty image bookkeeping scans every line: only needed while an image is shown or was shown the frame before.
		const imagesShown = finished.imageLines > 0 || this.previousKittyImageIds.size > 0;
		const collectKittyImageIds = (lines: string[]): Set<number> =>
			imagesShown ? this.collectKittyImageIds(lines) : new Set();

		// Helper to clear scrollback and viewport and render all new lines
		const fullRender = (clear: boolean): void => {
			this.fullRedrawCount += 1;
			const output = new BoundedTerminalWriter((data) => this.terminal.write(data));
			output.append("\x1b[?2026h"); // Begin synchronized output
			if (clear) {
				output.append(this.deleteKittyImages(this.previousKittyImageIds));
				output.append("\x1b[2J\x1b[H\x1b[3J"); // Clear screen, home, then clear scrollback
			}
			for (let i = 0; i < newLines.length; i++) {
				if (i > 0) output.append("\r\n");
				const line = newLines[i];
				const isImage = isImageLine(line);
				const imageReservedRows = isImage ? this.getKittyImageReservedRows(newLines, i) : 1;
				if (imageReservedRows > 1 && imageReservedRows <= height) {
					for (let row = 1; row < imageReservedRows; row++) {
						output.append("\r\n");
					}
					output.append(`\x1b[${imageReservedRows - 1}A`);
					output.append(line);
					output.append(`\x1b[${imageReservedRows - 1}B`);
					i += imageReservedRows - 1;
					continue;
				}
				output.append(line);
			}
			output.append("\x1b[?2026l"); // End synchronized output
			output.flush();
			this.cursorRow = Math.max(0, newLines.length - 1);
			this.hardwareCursorRow = this.cursorRow;
			// Reset max lines when clearing, otherwise track growth
			if (clear) {
				this.maxLinesRendered = newLines.length;
			} else {
				this.maxLinesRendered = Math.max(this.maxLinesRendered, newLines.length);
			}
			const bufferLength = Math.max(height, newLines.length);
			this.previousViewportTop = Math.max(0, bufferLength - height);
			this.positionHardwareCursor(cursorPos, newLines.length);
			this.previousLines = newLines;
			this.previousKittyImageIds = collectKittyImageIds(newLines);
			this.previousWidth = width;
			this.previousHeight = height;
		};

		const redrawLogDirectory = process.env.PI_TUI_DEBUG_REDRAW === "1" ? this.logDirectory : undefined;
		const logRedraw = (reason: string): void => {
			if (redrawLogDirectory === undefined) return;
			const logPath = path.join(redrawLogDirectory, "pi-tui-debug.log");
			const msg = `[${new Date().toISOString()}] fullRender: ${reason} (prev=${this.previousLines.length}, new=${newLines.length}, height=${height})\n`;
			fs.mkdirSync(path.dirname(logPath), { recursive: true });
			fs.appendFileSync(logPath, msg);
		};

		// First render - just output everything without clearing (assumes clean screen)
		if (this.previousLines.length === 0 && !widthChanged && !heightChanged) {
			logRedraw("first render");
			fullRender(false);
			return;
		}

		// Width changes always need a full re-render because wrapping changes.
		if (widthChanged) {
			logRedraw(`terminal width changed (${this.previousWidth} -> ${width})`);
			fullRender(true);
			return;
		}

		// Height changes normally need a full re-render to keep the visible viewport aligned,
		// but Termux changes height when the software keyboard shows or hides.
		// In that environment, a full redraw causes the entire history to replay on every toggle.
		if (heightChanged && !isTermuxSession()) {
			logRedraw(`terminal height changed (${this.previousHeight} -> ${height})`);
			fullRender(true);
			return;
		}

		// Content shrunk below the working area and no overlays - re-render to clear empty rows
		// (overlays need the padding, so only do this when no overlays are active)
		// Configurable via setClearOnShrink()
		if (this.getClearOnShrink() && newLines.length < this.maxLinesRendered && !this.hasOverlayEntries) {
			logRedraw(`clearOnShrink (maxLinesRendered=${this.maxLinesRendered})`);
			fullRender(true);
			return;
		}

		// Find first and last changed lines
		let firstChanged = -1;
		let lastChanged = -1;
		const maxLines = Math.max(newLines.length, this.previousLines.length);
		// (The first `unchanged` lines are the lines shown.)
		for (let i = Math.min(unchanged, this.previousLines.length); i < maxLines; i++) {
			const oldLine = i < this.previousLines.length ? this.previousLines[i] : "";
			const newLine = i < newLines.length ? newLines[i] : "";

			if (oldLine !== newLine) {
				if (firstChanged === -1) {
					firstChanged = i;
				}
				lastChanged = i;
			}
		}
		const appendedLines = newLines.length > this.previousLines.length;
		if (appendedLines) {
			if (firstChanged === -1) {
				firstChanged = this.previousLines.length;
			}
			lastChanged = newLines.length - 1;
		}
		if (firstChanged !== -1 && imagesShown) {
			const expandedRange = this.expandChangedRangeForKittyImages(firstChanged, lastChanged, newLines);
			firstChanged = expandedRange.firstChanged;
			lastChanged = expandedRange.lastChanged;
		}
		const appendStart = appendedLines && firstChanged === this.previousLines.length && firstChanged > 0;

		// No changes - but still need to update hardware cursor position if it moved
		if (firstChanged === -1) {
			this.positionHardwareCursor(cursorPos, newLines.length);
			this.previousViewportTop = prevViewportTop;
			this.previousHeight = height;
			return;
		}

		// All changes are in deleted lines (nothing to render, just clear)
		if (firstChanged >= newLines.length) {
			if (this.previousLines.length > newLines.length) {
				const output = new BoundedTerminalWriter((data) => this.terminal.write(data));
				output.append("\x1b[?2026h");
				output.append(this.deleteChangedKittyImages(firstChanged, lastChanged));
				// Move to end of new content (clamp to 0 for empty content)
				const targetRow = Math.max(0, newLines.length - 1);
				if (targetRow < prevViewportTop) {
					logRedraw(`deleted lines moved viewport up (${targetRow} < ${prevViewportTop})`);
					fullRender(true);
					return;
				}
				const lineDiff = computeLineDiff(targetRow);
				if (lineDiff > 0) output.append(`\x1b[${lineDiff}B`);
				else if (lineDiff < 0) output.append(`\x1b[${-lineDiff}A`);
				output.append("\r");
				// Clear extra lines without scrolling
				const extraLines = this.previousLines.length - newLines.length;
				if (extraLines > height) {
					logRedraw(`extraLines > height (${extraLines} > ${height})`);
					fullRender(true);
					return;
				}
				const clearStartOffset = newLines.length === 0 ? 0 : 1;
				if (extraLines > 0 && clearStartOffset > 0) {
					output.append(`\x1b[${clearStartOffset}B`);
				}
				for (let i = 0; i < extraLines; i++) {
					output.append("\r\x1b[2K");
					if (i < extraLines - 1) output.append("\x1b[1B");
				}
				const moveBack = Math.max(0, extraLines - 1 + clearStartOffset);
				if (moveBack > 0) {
					output.append(`\x1b[${moveBack}A`);
				}
				output.append("\x1b[?2026l");
				output.flush();
				this.cursorRow = targetRow;
				this.hardwareCursorRow = targetRow;
			}
			this.positionHardwareCursor(cursorPos, newLines.length);
			this.previousLines = newLines;
			this.previousKittyImageIds = collectKittyImageIds(newLines);
			this.previousWidth = width;
			this.previousHeight = height;
			this.previousViewportTop = prevViewportTop;
			return;
		}

		// Differential rendering can only touch what was actually visible.
		// If the first changed line is above the previous viewport, we need a full redraw.
		if (firstChanged < prevViewportTop) {
			logRedraw(`firstChanged < viewportTop (${firstChanged} < ${prevViewportTop})`);
			fullRender(true);
			return;
		}

		// Render from first changed line to end
		// Keep updates wrapped in synchronized output while writing bounded chunks.
		const output = new BoundedTerminalWriter((data) => this.terminal.write(data));
		output.append("\x1b[?2026h"); // Begin synchronized output
		output.append(this.deleteChangedKittyImages(firstChanged, lastChanged));
		const prevViewportBottom = prevViewportTop + height - 1;
		const moveTargetRow = appendStart ? firstChanged - 1 : firstChanged;
		if (moveTargetRow > prevViewportBottom) {
			const currentScreenRow = Math.max(0, Math.min(height - 1, hardwareCursorRow - prevViewportTop));
			const moveToBottom = height - 1 - currentScreenRow;
			if (moveToBottom > 0) {
				output.append(`\x1b[${moveToBottom}B`);
			}
			const scroll = moveTargetRow - prevViewportBottom;
			output.append("\r\n".repeat(scroll));
			prevViewportTop += scroll;
			viewportTop += scroll;
			hardwareCursorRow = moveTargetRow;
		}

		// Move cursor to first changed line (use hardwareCursorRow for actual position)
		const lineDiff = computeLineDiff(moveTargetRow);
		if (lineDiff > 0) {
			output.append(`\x1b[${lineDiff}B`); // Move down
		} else if (lineDiff < 0) {
			output.append(`\x1b[${-lineDiff}A`); // Move up
		}

		output.append(appendStart ? "\r\n" : "\r"); // Move to column 0

		// Only render changed lines (firstChanged to lastChanged), not all lines to end
		// This reduces flicker when only a single line changes (e.g., spinner animation)
		const renderEnd = Math.min(lastChanged, newLines.length - 1);
		for (let i = firstChanged; i <= renderEnd; i++) {
			if (i > firstChanged) output.append("\r\n");
			const line = newLines[i];
			const isImage = isImageLine(line);
			const imageReservedRows = isImage ? this.getKittyImageReservedRows(newLines, i, renderEnd) : 1;
			if (imageReservedRows > 1) {
				const imageStartScreenRow = i - viewportTop;
				if (imageStartScreenRow < 0 || imageStartScreenRow + imageReservedRows > height) {
					logRedraw(
						`kitty image pre-clear would scroll (${imageStartScreenRow} + ${imageReservedRows} > ${height})`,
					);
					fullRender(true);
					return;
				}

				output.append("\x1b[2K");
				for (let row = 1; row < imageReservedRows; row++) {
					output.append("\r\n\x1b[2K");
				}
				output.append(`\x1b[${imageReservedRows - 1}A`);
				output.append(line);
				output.append(`\x1b[${imageReservedRows - 1}B`);
				i += imageReservedRows - 1;
				continue;
			}

			output.append("\x1b[2K"); // Clear current line
			if (!isImage && visibleWidth(line) > width) {
				// Log all lines to crash file for debugging
				const crashLogPath = path.join(this.logDirectory ?? os.tmpdir(), "pi-tui-crash.log");
				const crashData = [
					`Crash at ${new Date().toISOString()}`,
					`Terminal width: ${width}`,
					`Line ${i} visible width: ${visibleWidth(line)}`,
					"",
					"=== All rendered lines ===",
					...newLines.map((l, idx) => `[${idx}] (w=${visibleWidth(l)}) ${l}`),
					"",
				].join("\n");
				fs.mkdirSync(path.dirname(crashLogPath), { recursive: true });
				fs.writeFileSync(crashLogPath, crashData);

				// Clean up terminal state before throwing
				this.stop();

				const errorMsg = [
					`Rendered line ${i} exceeds terminal width (${visibleWidth(line)} > ${width}).`,
					"",
					"This is likely caused by a custom TUI component not truncating its output.",
					"Use visibleWidth() to measure and truncateToWidth() to truncate lines.",
					"",
					`Debug log written to: ${crashLogPath}`,
				].join("\n");
				throw new Error(errorMsg);
			}
			output.append(line);
		}

		// Track where cursor ended up after rendering
		let finalCursorRow = renderEnd;

		// If we had more lines before, clear them and move cursor back
		if (this.previousLines.length > newLines.length) {
			// Move to end of new content first if we stopped before it
			if (renderEnd < newLines.length - 1) {
				const moveDown = newLines.length - 1 - renderEnd;
				output.append(`\x1b[${moveDown}B`);
				finalCursorRow = newLines.length - 1;
			}
			const extraLines = this.previousLines.length - newLines.length;
			for (let i = newLines.length; i < this.previousLines.length; i++) {
				output.append("\r\n\x1b[2K");
			}
			// Move cursor back to end of new content
			output.append(`\x1b[${extraLines}A`);
		}

		output.append("\x1b[?2026l"); // End synchronized output

		if (process.env.PI_TUI_DEBUG === "1") {
			const debugDir = "/tmp/tui";
			fs.mkdirSync(debugDir, { recursive: true });
			const debugPath = path.join(debugDir, `render-${Date.now()}-${Math.random().toString(36).slice(2)}.log`);
			const debugData = [
				`firstChanged: ${firstChanged}`,
				`viewportTop: ${viewportTop}`,
				`cursorRow: ${this.cursorRow}`,
				`height: ${height}`,
				`lineDiff: ${lineDiff}`,
				`hardwareCursorRow: ${hardwareCursorRow}`,
				`renderEnd: ${renderEnd}`,
				`finalCursorRow: ${finalCursorRow}`,
				`cursorPos: ${JSON.stringify(cursorPos)}`,
				`newLines.length: ${newLines.length}`,
				`previousLines.length: ${this.previousLines.length}`,
				"",
				"=== newLines ===",
				JSON.stringify(newLines, null, 2),
				"",
				"=== previousLines ===",
				JSON.stringify(this.previousLines, null, 2),
				"",
				"=== buffer ===",
				`[${output.length} chars written in bounded chunks]`,
			].join("\n");
			fs.writeFileSync(debugPath, debugData);
		}

		output.flush();

		// Track cursor position for next render
		// cursorRow tracks end of content (for viewport calculation)
		// hardwareCursorRow tracks actual terminal cursor position (for movement)
		this.cursorRow = Math.max(0, newLines.length - 1);
		this.hardwareCursorRow = finalCursorRow;
		// Track terminal's working area (grows but doesn't shrink unless cleared)
		this.maxLinesRendered = Math.max(this.maxLinesRendered, newLines.length);
		this.previousViewportTop = Math.max(prevViewportTop, finalCursorRow - height + 1);

		// Position hardware cursor for IME
		this.positionHardwareCursor(cursorPos, newLines.length);

		this.previousLines = newLines;
		this.previousKittyImageIds = collectKittyImageIds(newLines);
		this.previousWidth = width;
		this.previousHeight = height;
	}

	/**
	 * Position the hardware cursor for IME candidate window.
	 * @param cursorPos The cursor position extracted from rendered output, or null
	 * @param totalLines Total number of rendered lines
	 */
	private positionHardwareCursor(cursorPos: { row: number; col: number } | null, totalLines: number): void {
		if (!cursorPos || totalLines <= 0) {
			this.terminal.hideCursor();
			return;
		}

		// Clamp cursor position to valid range
		const targetRow = Math.max(0, Math.min(cursorPos.row, totalLines - 1));
		const targetCol = Math.max(0, cursorPos.col);

		// Move cursor from current position to target
		const rowDelta = targetRow - this.hardwareCursorRow;
		let buffer = "";
		if (rowDelta > 0) {
			buffer += `\x1b[${rowDelta}B`; // Move down
		} else if (rowDelta < 0) {
			buffer += `\x1b[${-rowDelta}A`; // Move up
		}
		// Move to absolute column (1-indexed)
		buffer += `\x1b[${targetCol + 1}G`;

		if (buffer) {
			this.terminal.write(buffer);
		}

		this.hardwareCursorRow = targetRow;
		if (this.getShowHardwareCursor()) {
			this.terminal.showCursor();
		} else {
			this.terminal.hideCursor();
		}
	}
}
