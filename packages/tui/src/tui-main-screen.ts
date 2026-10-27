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

/** A frame's lines as written (applyLineResets()), made when read. */
interface FrameRows {
	readonly length: number;
	/** Line `row` as written. */
	written(row: number): string;
	/**
	 * Line `row` before applyLineResets() (or as written, for lines kept as written): it has the kitty image ids the line
	 * written has, as applyLineResets() leaves image lines as they are and adds none to the others.
	 */
	source(row: number): string;
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
	/**
	 * How many lines the last frame wrote. What each of them is, as written (applyLineResets()), is made again from the
	 * frame's lines before that (rawLines) when it is read (shownLine()), instead of being kept: a long transcript's lines
	 * are kept by the components that render them, and the written lines would be a copy of all of them, one more for as
	 * long as the session is open. Only the lines a frame writes and those it compares by content are made.
	 */
	private shownLength = 0;
	/** The lines written as captured, after restoreRenderState() and until the next frame is drawn: rawLines is not used then. */
	private restoredLines?: readonly string[];
	private previousKittyImageIds = new Set<number>();
	private previousWidth = 0;
	private previousHeight = 0;
	private cursorRow = 0;
	private hardwareCursorRow = 0;
	private maxLinesRendered = 0;
	private previousViewportTop = 0;

	captureRenderState(): TuiMainScreenRenderState {
		const previousLines: string[] = new Array(this.shownLength);
		for (let i = 0; i < this.shownLength; i++) previousLines[i] = this.shownLine(i);
		return {
			previousLines,
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
		this.restoredLines = state.previousLines.map((line) => (isImageLine(line) ? "" : line));
		this.shownLength = this.restoredLines.length;
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
		this.restoredLines = undefined;
		this.shownLength = 0;
		this.previousWidth = -1;
		this.previousHeight = -1;
		this.cursorRow = 0;
		this.hardwareCursorRow = 0;
		this.maxLinesRendered = 0;
		this.previousViewportTop = 0;
	}

	protected override beforeTerminalStop(options: TuiStopOptions): void {
		if (options.preserveScreen || this.shownLength === 0) return;
		this.terminal.write(" ");
		const targetRow = this.shownLength;
		const lineDiff = targetRow - this.hardwareCursorRow;
		if (lineDiff > 0) this.terminal.write(`\x1b[${lineDiff}B`);
		else if (lineDiff < 0) this.terminal.write(`\x1b[${-lineDiff}A`);
		this.terminal.write("\r\n");
	}

	/** For tests: the lines the last frame wrote, as written (applyLineResets()). */
	shownLinesForTest(): string[] {
		return this.captureRenderState().previousLines;
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

	/**
	 * The lines of the last frame before applyLineResets() (its raw lines), and the rows of them that are images
	 * (applyLineResets() leaves only those as they are), in order.
	 */
	private rawLines: string[] = [];
	private rawImageRows: number[] = [];
	/** The root's live lines and the revision the last frame was rendered from (renderLiveLines()). */
	private frameLive?: { live: LiveLines; revision: number; width: number; lines: string[] };
	/** applyLineResets()' argument for one line (writtenLine()). */
	private readonly singleLine = [""];

	/** `line` as a frame writes it (applyLineResets()). */
	private writtenLine(line: string): string {
		const single = this.singleLine;
		single[0] = line;
		const written = this.applyLineResets(single)[0]!;
		single[0] = "";
		return written;
	}

	/** Line `row` of the last frame, as it was written. */
	private shownLine(row: number): string {
		return this.restoredLines !== undefined ? this.restoredLines[row]! : this.writtenLine(this.rawLines[row]!);
	}

	/**
	 * Records `raw` as the frame's raw lines and counts the image lines in it, taking each row whose line is the same string
	 * as the frame before's from the frame before: most lines of a long transcript are. `previousRawTail` holds the frame
	 * before's raw lines from `unchanged` on when `raw` is that frame's array, changed in place from there
	 * (renderChangedLines()); otherwise rawLines is that frame's.
	 */
	private finishLines(raw: string[], unchanged: number, previousRawTail?: readonly string[]): { imageLines: number } {
		const previousRawLength =
			previousRawTail === undefined ? this.rawLines.length : unchanged + previousRawTail.length;
		const previousRaw = this.rawLines;
		// (The first `unchanged` lines are those of the frame before: their rows are taken as they were, without reading them.)
		const from = Math.min(unchanged, previousRawLength, raw.length);
		const previousRows = this.rawImageRows;
		const imageRows = previousRows.filter((row) => row < from);
		let next = imageRows.length;
		for (let i = from; i < raw.length; i++) {
			const line = raw[i]!;
			let image: boolean;
			if (
				i < previousRawLength &&
				line === (previousRawTail === undefined ? previousRaw[i] : previousRawTail[i - unchanged])
			) {
				while (next < previousRows.length && previousRows[next]! < i) next++;
				image = previousRows[next] === i;
			} else {
				image = isImageLine(line);
			}
			if (image) imageRows.push(i);
		}
		this.rawLines = raw;
		this.rawImageRows = imageRows;
		return { imageLines: imageRows.length };
	}

	/**
	 * The lines of a frame whose first `unchanged` lines are those of the frame before (renderLiveLines() says so, and no
	 * overlay is shown), made by changing the last frame's arrays in place from that line on: the root's lines
	 * (frameLive.lines), resolveFakeCursors()'s, and the raw lines after the cursor marker is taken out. Line for line
	 * what resolveFrameFakeCursors() and extractCursorPosition() make of a copy of the root's lines, but without copying the
	 * lines above `unchanged`: a frame then costs time in proportion to what changed, not to the length of the transcript.
	 * Also returns the raw lines of the frame before from `unchanged` on, which the array no longer holds. Undefined when the
	 * last frame's arrays are not those this needs (resolveFrameFakeCursors() then makes the frame).
	 */
	private renderChangedLines(
		live: LiveLines,
		root: string[],
		unchanged: number,
		height: number,
	):
		| { raw: string[]; previousRawTail: string[]; imageLines: number; cursorPos: { row: number; col: number } | null }
		| undefined {
		const resolved = this.resolvedLines;
		const raw = this.rawLines;
		if (
			unchanged <= 0 ||
			this.unresolvedLines !== root ||
			resolved.length !== root.length ||
			raw === resolved ||
			raw === root ||
			raw.length < unchanged ||
			this.restoredLines !== undefined ||
			this.shownLength !== raw.length
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
		return { raw, previousRawTail: rawTail, imageLines: finished.imageLines, cursorPos };
	}

	private collectKittyImageIds(rows: FrameRows): Set<number> {
		const ids = new Set<number>();
		for (let i = 0; i < rows.length; i++) {
			for (const id of extractKittyImageIds(rows.source(i))) {
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

	private getKittyImageReservedRows(rows: FrameRows, index: number, maxIndex = rows.length - 1): number {
		const imageRows = extractKittyImageRows(index < rows.length ? rows.written(index) : "");
		if (imageRows <= 1) return 1;

		const maxRows = Math.min(imageRows, maxIndex - index + 1, rows.length - index);
		let reservedRows = 1;
		while (reservedRows < maxRows) {
			const line = index + reservedRows < rows.length ? rows.written(index + reservedRows) : "";
			if (isImageLine(line) || visibleWidth(line) > 0) break;
			reservedRows++;
		}
		return reservedRows;
	}

	private expandChangedRangeForKittyImages(
		firstChanged: number,
		lastChanged: number,
		shown: FrameRows,
		frame: FrameRows,
	): { firstChanged: number; lastChanged: number } {
		let expandedFirstChanged = firstChanged;
		let expandedLastChanged = lastChanged;
		const expandForLines = (rows: FrameRows): void => {
			for (let i = 0; i < rows.length; i++) {
				if (extractKittyImageIds(rows.source(i)).length === 0) continue;
				const blockEnd = i + this.getKittyImageReservedRows(rows, i) - 1;
				if (i >= firstChanged || (i <= lastChanged && blockEnd >= firstChanged)) {
					expandedFirstChanged = Math.min(expandedFirstChanged, i);
					expandedLastChanged = Math.max(expandedLastChanged, blockEnd);
				}
			}
		};

		expandForLines(shown);
		expandForLines(frame);
		return { firstChanged: expandedFirstChanged, lastChanged: expandedLastChanged };
	}

	private deleteChangedKittyImages(firstChanged: number, lastChanged: number, shown: FrameRows): string {
		if (firstChanged < 0 || lastChanged < firstChanged || this.previousKittyImageIds.size === 0) return "";

		const ids = new Set<number>();
		const maxLine = Math.min(lastChanged, shown.length - 1);
		for (let i = firstChanged; i <= maxLine; i++) {
			for (const id of extractKittyImageIds(shown.source(i))) {
				ids.add(id);
			}
		}

		return this.deleteKittyImages(ids);
	}

	/** Records the frame whose raw lines are rawLines as the lines shown (shownLine()). */
	private noteShown(length: number): void {
		this.restoredLines = undefined;
		this.shownLength = length;
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
		// The last frame's lines, read before this frame changes their array in place (renderChangedLines()).
		const shownLength = this.shownLength;
		const shownRaw = this.rawLines;
		const restoredLines = this.restoredLines;

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
				shownLength - height,
			);
		}
		unchanged = Math.max(0, unchanged);
		// (When the lines above `unchanged` are those of the last frame, its arrays are changed from there on.)
		const changed =
			live !== undefined && before !== undefined && unchanged > 0
				? this.renderChangedLines(live, before.lines, unchanged, height)
				: undefined;
		let raw: string[];
		let cursorPos: { row: number; col: number } | null;
		let finished: { imageLines: number };
		/** The last frame's raw lines from `unchanged` on, when their array now holds this frame's. */
		let shownRawTail: readonly string[] | undefined;
		if (changed !== undefined) {
			this.frameLive = { live: live!, revision: live!.revision, width, lines: before!.lines };
			raw = changed.raw;
			shownRawTail = changed.previousRawTail;
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
			raw = this.resolveFrameFakeCursors(root, unchanged);

			// Composite overlays into the rendered lines (before differential compare)
			if (this.hasOverlayEntries) {
				raw = this.compositeOverlays(raw, width, height);
			}

			// Extract cursor position before applying line resets (marker must be found first)
			cursorPos = this.extractCursorPosition(raw, height);

			finished = this.finishLines(raw, unchanged);
		}
		// The lines this frame writes are its raw lines after applyLineResets(), made when they are read; those the last
		// frame wrote are made from its raw lines the same way (or are the lines restored).
		const frameLength = raw.length;
		const frame: FrameRows = {
			length: frameLength,
			written: (row) => this.writtenLine(raw[row]!),
			source: (row) => raw[row]!,
		};
		const shownSource = (row: number): string =>
			restoredLines !== undefined
				? restoredLines[row]!
				: shownRawTail !== undefined && row >= unchanged
					? shownRawTail[row - unchanged]!
					: shownRaw[row]!;
		const shown: FrameRows = {
			length: shownLength,
			written: (row) => (restoredLines !== undefined ? restoredLines[row]! : this.writtenLine(shownSource(row))),
			source: shownSource,
		};
		// Kitty image bookkeeping scans every line: only needed while an image is shown or was shown the frame before.
		const imagesShown = finished.imageLines > 0 || this.previousKittyImageIds.size > 0;
		const collectKittyImageIds = (rows: FrameRows): Set<number> =>
			imagesShown ? this.collectKittyImageIds(rows) : new Set();

		// Helper to clear scrollback and viewport and render all new lines
		const fullRender = (clear: boolean): void => {
			this.fullRedrawCount += 1;
			const output = new BoundedTerminalWriter((data) => this.terminal.write(data));
			output.append("\x1b[?2026h"); // Begin synchronized output
			if (clear) {
				output.append(this.deleteKittyImages(this.previousKittyImageIds));
				output.append("\x1b[2J\x1b[H\x1b[3J"); // Clear screen, home, then clear scrollback
			}
			for (let i = 0; i < frameLength; i++) {
				if (i > 0) output.append("\r\n");
				const line = frame.written(i);
				const isImage = isImageLine(line);
				const imageReservedRows = isImage ? this.getKittyImageReservedRows(frame, i) : 1;
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
			this.cursorRow = Math.max(0, frameLength - 1);
			this.hardwareCursorRow = this.cursorRow;
			// Reset max lines when clearing, otherwise track growth
			if (clear) {
				this.maxLinesRendered = frameLength;
			} else {
				this.maxLinesRendered = Math.max(this.maxLinesRendered, frameLength);
			}
			const bufferLength = Math.max(height, frameLength);
			this.previousViewportTop = Math.max(0, bufferLength - height);
			this.positionHardwareCursor(cursorPos, frameLength);
			this.noteShown(frameLength);
			this.previousKittyImageIds = collectKittyImageIds(frame);
			this.previousWidth = width;
			this.previousHeight = height;
		};

		const redrawLogDirectory = process.env.PI_TUI_DEBUG_REDRAW === "1" ? this.logDirectory : undefined;
		const logRedraw = (reason: string): void => {
			if (redrawLogDirectory === undefined) return;
			const logPath = path.join(redrawLogDirectory, "pi-tui-debug.log");
			const msg = `[${new Date().toISOString()}] fullRender: ${reason} (prev=${shownLength}, new=${frameLength}, height=${height})\n`;
			fs.mkdirSync(path.dirname(logPath), { recursive: true });
			fs.appendFileSync(logPath, msg);
		};

		// First render - just output everything without clearing (assumes clean screen)
		if (shownLength === 0 && !widthChanged && !heightChanged) {
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
		if (this.getClearOnShrink() && frameLength < this.maxLinesRendered && !this.hasOverlayEntries) {
			logRedraw(`clearOnShrink (maxLinesRendered=${this.maxLinesRendered})`);
			fullRender(true);
			return;
		}

		// Find first and last changed lines
		let firstChanged = -1;
		let lastChanged = -1;
		const maxLines = Math.max(frameLength, shownLength);
		// (The first `unchanged` lines are the lines shown. A line written is made from its raw line: two lines whose raw lines
		// are the same string are the same; others are compared as written.)
		for (let i = Math.min(unchanged, shownLength); i < maxLines; i++) {
			let same: boolean;
			if (i >= shownLength || i >= frameLength) {
				// (A line beyond either frame is "", which a line written never is; a line restored can be: an image's.)
				same = restoredLines !== undefined && i < shownLength && restoredLines[i] === "";
			} else if (restoredLines !== undefined) {
				same = restoredLines[i] === frame.written(i);
			} else {
				const before = shownSource(i);
				const after = raw[i]!;
				same = before === after || this.writtenLine(before) === this.writtenLine(after);
			}
			if (!same) {
				if (firstChanged === -1) {
					firstChanged = i;
					// (A line above the viewport that changed is drawn by drawing all of them again, whatever else changed: the
					// lines after it are not made to be compared.)
					if (i < prevViewportTop && i < frameLength && !imagesShown) {
						lastChanged = i;
						break;
					}
				}
				lastChanged = i;
			}
		}
		const appendedLines = frameLength > shownLength;
		if (appendedLines) {
			if (firstChanged === -1) {
				firstChanged = shownLength;
			}
			lastChanged = frameLength - 1;
		}
		if (firstChanged !== -1 && imagesShown) {
			const expandedRange = this.expandChangedRangeForKittyImages(firstChanged, lastChanged, shown, frame);
			firstChanged = expandedRange.firstChanged;
			lastChanged = expandedRange.lastChanged;
		}
		const appendStart = appendedLines && firstChanged === shownLength && firstChanged > 0;

		// No changes - but still need to update hardware cursor position if it moved
		if (firstChanged === -1) {
			this.positionHardwareCursor(cursorPos, frameLength);
			this.previousViewportTop = prevViewportTop;
			this.previousHeight = height;
			// (The lines shown are this frame's, line for line. Lines restored are kept: the rows past this frame's last that
			// were images are "" there, and stay rows shown.)
			if (restoredLines === undefined) this.noteShown(frameLength);
			return;
		}

		// All changes are in deleted lines (nothing to render, just clear)
		if (firstChanged >= frameLength) {
			if (shownLength > frameLength) {
				const output = new BoundedTerminalWriter((data) => this.terminal.write(data));
				output.append("\x1b[?2026h");
				output.append(this.deleteChangedKittyImages(firstChanged, lastChanged, shown));
				// Move to end of new content (clamp to 0 for empty content)
				const targetRow = Math.max(0, frameLength - 1);
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
				const extraLines = shownLength - frameLength;
				if (extraLines > height) {
					logRedraw(`extraLines > height (${extraLines} > ${height})`);
					fullRender(true);
					return;
				}
				const clearStartOffset = frameLength === 0 ? 0 : 1;
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
			this.positionHardwareCursor(cursorPos, frameLength);
			this.noteShown(frameLength);
			this.previousKittyImageIds = collectKittyImageIds(frame);
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
		output.append(this.deleteChangedKittyImages(firstChanged, lastChanged, shown));
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
		const renderEnd = Math.min(lastChanged, frameLength - 1);
		for (let i = firstChanged; i <= renderEnd; i++) {
			if (i > firstChanged) output.append("\r\n");
			const line = frame.written(i);
			const isImage = isImageLine(line);
			const imageReservedRows = isImage ? this.getKittyImageReservedRows(frame, i, renderEnd) : 1;
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
					...Array.from({ length: frameLength }, (_, idx) => {
						const l = frame.written(idx);
						return `[${idx}] (w=${visibleWidth(l)}) ${l}`;
					}),
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
		if (shownLength > frameLength) {
			// Move to end of new content first if we stopped before it
			if (renderEnd < frameLength - 1) {
				const moveDown = frameLength - 1 - renderEnd;
				output.append(`\x1b[${moveDown}B`);
				finalCursorRow = frameLength - 1;
			}
			const extraLines = shownLength - frameLength;
			for (let i = frameLength; i < shownLength; i++) {
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
				`newLines.length: ${frameLength}`,
				`previousLines.length: ${shownLength}`,
				"",
				"=== newLines ===",
				JSON.stringify(
					Array.from({ length: frameLength }, (_, idx) => frame.written(idx)),
					null,
					2,
				),
				"",
				"=== previousLines ===",
				JSON.stringify(
					Array.from({ length: shownLength }, (_, idx) => shown.written(idx)),
					null,
					2,
				),
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
		this.cursorRow = Math.max(0, frameLength - 1);
		this.hardwareCursorRow = finalCursorRow;
		// Track terminal's working area (grows but doesn't shrink unless cleared)
		this.maxLinesRendered = Math.max(this.maxLinesRendered, frameLength);
		this.previousViewportTop = Math.max(prevViewportTop, finalCursorRow - height + 1);

		// Position hardware cursor for IME
		this.positionHardwareCursor(cursorPos, frameLength);

		this.noteShown(frameLength);
		this.previousKittyImageIds = collectKittyImageIds(frame);
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
