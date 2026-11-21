import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import { ProcessTerminal } from "./terminal.ts";
import { deleteKittyImage, isImageLine } from "./terminal-image.ts";
import { BoundedTerminalWriter } from "./terminal-writer.ts";
import { type LiveLines, renderLiveLines, renderSharedLines, type TUI, TuiBase, type TuiStopOptions } from "./tui.ts";
import { visibleWidth } from "./utils.ts";

/** OSC 133 prompt markers at the start of a rendered line. Fullscreen uses them for prompt navigation. */
const OSC133_ZONE_PREFIX = /^(?:\x1b\]133;[ABC](?:\x07|\x1b\\))+/;

const KITTY_SEQUENCE_PREFIX = "\x1b_G";
/** What ProcessTerminal's showCursor() and hideCursor() write. */
const CURSOR_SHOW = "\x1b[?25h";
const CURSOR_HIDE = "\x1b[?25l";

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

interface KittyImageRow extends KittyImageHeader {
	row: number;
	/** Furthest possible extent (exclusive) of this image or any image before it. */
	prefixEnd: number;
}

/** Update the sorted image index from the first row that could have changed.
 * Text-only edits below the last image reuse the index itself.
 */
function indexKittyImages(
	lines: readonly string[],
	from: number,
	previous: readonly KittyImageRow[],
): readonly KittyImageRow[] {
	let keep = previous.length;
	while (keep > 0 && previous[keep - 1]!.row >= from) keep--;
	let images = keep === previous.length ? undefined : previous.slice(0, keep);
	for (let row = from; row < lines.length; row++) {
		const header = parseKittyImageHeader(lines[row]!);
		if (!header?.ids.length) continue;
		images ??= previous.slice();
		const prefixEnd = Math.max(images[images.length - 1]?.prefixEnd ?? 0, row + header.rows);
		images.push({ row, ...header, prefixEnd });
	}
	return images ?? previous;
}

/** A frame's lines as written (applyLineResets()), made when read. */
interface FrameRows {
	readonly images: readonly KittyImageRow[];
	readonly length: number;
	/** Line `row` as written. */
	written(row: number): string;
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
	private images: readonly KittyImageRow[] = [];
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
		this.images = [];
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

	/** One owned snapshot, after overlays and cursor extraction, before terminal resets. */
	private rawLines: string[] = [];
	private frameLive?: { live: LiveLines; revision: number; hardwareCursor: boolean; decoratedFrom: number };
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

	private deleteKittyImages(images: readonly KittyImageRow[], first = 0, last = Infinity): string {
		if (images.length === 0 || images[images.length - 1]!.row < first) return "";
		const ids = new Set<number>();
		for (const image of images) {
			if (image.row > last) break;
			if (image.row >= first) for (const id of image.ids) ids.add(id);
		}
		let buffer = "";
		for (const id of ids) buffer += deleteKittyImage(id);
		return buffer;
	}

	private getKittyImageReservedRows(
		rows: FrameRows,
		index: number,
		maxIndex = rows.length - 1,
		imageRows = parseKittyImageHeader(rows.written(index))?.rows ?? 1,
	): number {
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
		// A custom line reset can change the image's displayed height.
		const standardResets = this.applyLineResets === super.applyLineResets;
		const expandForLines = (rows: FrameRows): void => {
			if (
				rows.images.length === 0 ||
				(standardResets && rows.images[rows.images.length - 1]!.prefixEnd <= firstChanged)
			)
				return;
			for (const image of rows.images) {
				const i = image.row;
				if (standardResets && i + image.rows <= firstChanged) continue;
				const blockEnd =
					i +
					this.getKittyImageReservedRows(rows, i, rows.length - 1, standardResets ? image.rows : undefined) -
					1;
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
		const shownLength = this.shownLength;
		const shownRaw = this.rawLines;
		const restoredLines = this.restoredLines;
		const previousImages = this.images;
		const live = renderLiveLines(this, width);
		const source = live?.lines ?? renderSharedLines(this, width);
		const before = this.frameLive;
		const hardwareCursor = this.getShowHardwareCursor();
		// Only the visible rows can contain an overlay or an extracted cursor. Keep the prefix
		// above both viewports when its owner confirms it is unchanged, even while a modal is open.
		let rebuildFrom = 0;
		const standardOverlay = this.compositeOverlays === super.compositeOverlays;
		if (
			live &&
			before?.live === live &&
			this.extractCursorPosition === super.extractCursorPosition &&
			before.hardwareCursor === hardwareCursor &&
			this.previousWidth === width &&
			this.previousHeight === height &&
			!restoredLines &&
			(!this.hasOverlayEntries || standardOverlay)
		) {
			rebuildFrom = Math.min(live.changedSince(before.revision), before.decoratedFrom, source.length);
		}
		const unchanged = Math.max(0, Math.min(rebuildFrom, source.length - height, shownLength - height));
		// Preserve just the old suffix for the diff. A full rebuild swaps arrays so the old frame
		// stays readable without copying it. The mutable array returned by a component is never ours.
		const shownRawTail = rebuildFrom > 0 ? shownRaw.slice(unchanged) : undefined;
		let raw = shownRawTail ? shownRaw : new Array<string>(source.length);
		raw.length = source.length;
		const single = [""];
		const defaultResolver = this.resolveFakeCursors === super.resolveFakeCursors;
		for (let i = shownRawTail ? rebuildFrom : 0; i < source.length; i++) {
			let line = source[i]!;
			if (line.startsWith("\x1b]133;")) line = line.replace(OSC133_ZONE_PREFIX, "");
			if (defaultResolver) raw[i] = this.resolveFakeCursorLine(line);
			else {
				single[0] = line;
				raw[i] = this.resolveFakeCursors(single)[0]!;
			}
		}
		if (this.hasOverlayEntries) {
			raw = standardOverlay
				? this.compositeOverlays(raw, width, height, true)
				: this.compositeOverlays(raw, width, height);
		}
		const cursorPos = this.extractCursorPosition(raw, height);
		this.rawLines = raw;
		const decoratedFrom = this.hasOverlayEntries
			? standardOverlay
				? Math.max(0, raw.length - height)
				: 0
			: (cursorPos?.row ?? source.length);
		this.frameLive = live
			? { live, revision: live.revision, hardwareCursor, decoratedFrom: Math.min(source.length, decoratedFrom) }
			: undefined;
		this.images = indexKittyImages(raw, unchanged, previousImages);
		const frameLength = raw.length;
		const frame: FrameRows = {
			length: frameLength,
			images: this.images,
			written: (row) => this.writtenLine(raw[row]!),
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
			images: shownLength > 0 ? previousImages : [],
		};
		const imagesShown = this.images.length > 0 || previousImages.length > 0;

		// Helper to clear scrollback and viewport and render all new lines
		const fullRender = (clear: boolean): void => {
			this.fullRedrawCount += 1;
			const output = new BoundedTerminalWriter((data) => this.terminal.write(data));
			output.append("\x1b[?2026h"); // Begin synchronized output
			if (clear) {
				output.append(this.deleteKittyImages(previousImages));
				output.append("\x1b[2J\x1b[H\x1b[3J"); // Clear screen, home, then clear scrollback
			}
			for (let i = 0; i < frameLength; i++) {
				if (i > 0) output.append("\r\n");
				const line = frame.written(i);
				const imageRows = parseKittyImageHeader(line)?.rows ?? 1;
				const imageReservedRows =
					imageRows > 1 ? this.getKittyImageReservedRows(frame, i, frameLength - 1, imageRows) : 1;
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
			this.positionHardwareCursor(cursorPos, frameLength, output);
			this.noteShown(frameLength);
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

		// A differential frame rewrites everything between its two boundaries. Once both
		// boundaries are known, comparing the rows inside them would format them twice.
		const maxLines = Math.max(frameLength, shownLength);
		const sameRow = (i: number): boolean => {
			if (i >= shownLength || i >= frameLength) {
				// Restoring a screen replaces images with empty strings. Removing those rows alone draws nothing.
				return restoredLines !== undefined && i < shownLength && restoredLines[i] === "";
			}
			if (restoredLines !== undefined) return restoredLines[i] === frame.written(i);
			const before = shownSource(i);
			const after = raw[i]!;
			return before === after || this.writtenLine(before) === this.writtenLine(after);
		};
		const appendedLines = frameLength > shownLength;
		let firstChanged = Math.min(unchanged, shownLength);
		while (firstChanged < maxLines && sameRow(firstChanged)) firstChanged++;
		let lastChanged = firstChanged;
		if (firstChanged === maxLines) {
			firstChanged = -1;
		} else if (imagesShown || firstChanged >= prevViewportTop || firstChanged >= frameLength) {
			// A change above the viewport already requires a full redraw.
			if (this.applyLineResets !== super.applyLineResets) {
				// A custom formatter can observe both order and calls. Preserve the original forward pass.
				for (let i = firstChanged + 1; i < maxLines; i++) if (!sameRow(i)) lastChanged = i;
			} else if (!appendedLines) {
				lastChanged = maxLines - 1;
				while (lastChanged > firstChanged && sameRow(lastChanged)) lastChanged--;
			}
		}
		if (appendedLines) lastChanged = frameLength - 1;
		if (firstChanged !== -1 && imagesShown) {
			const expandedRange = this.expandChangedRangeForKittyImages(firstChanged, lastChanged, shown, frame);
			firstChanged = expandedRange.firstChanged;
			lastChanged = expandedRange.lastChanged;
		}
		const appendStart = appendedLines && firstChanged === shownLength && firstChanged > 0;

		// No changes - but still need to update hardware cursor position if it moved
		if (firstChanged === -1) {
			this.positionHardwareCursor(
				cursorPos,
				frameLength,
				new BoundedTerminalWriter((data) => this.terminal.write(data)),
			);
			this.previousViewportTop = prevViewportTop;
			this.previousHeight = height;
			// (The lines shown are this frame's, line for line. Lines restored are kept: the rows past this frame's last that
			// were images are "" there, and stay rows shown.)
			if (restoredLines === undefined) this.noteShown(frameLength);
			return;
		}

		// All changes are in deleted lines (nothing to render, just clear)
		if (firstChanged >= frameLength) {
			const output = new BoundedTerminalWriter((data) => this.terminal.write(data));
			if (shownLength > frameLength) {
				output.append("\x1b[?2026h");
				output.append(this.deleteKittyImages(shown.images, firstChanged, lastChanged));
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
				this.cursorRow = targetRow;
				this.hardwareCursorRow = targetRow;
			}
			this.positionHardwareCursor(cursorPos, frameLength, output);
			this.noteShown(frameLength);
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
		output.append(this.deleteKittyImages(shown.images, firstChanged, lastChanged));
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

		// Track cursor position for next render
		// cursorRow tracks end of content (for viewport calculation)
		// hardwareCursorRow tracks actual terminal cursor position (for movement)
		this.cursorRow = Math.max(0, frameLength - 1);
		this.hardwareCursorRow = finalCursorRow;
		// Track terminal's working area (grows but doesn't shrink unless cleared)
		this.maxLinesRendered = Math.max(this.maxLinesRendered, frameLength);
		this.previousViewportTop = Math.max(prevViewportTop, finalCursorRow - height + 1);

		// Position hardware cursor for IME (and write the frame)
		this.positionHardwareCursor(cursorPos, frameLength, output);

		this.noteShown(frameLength);
		this.previousWidth = width;
		this.previousHeight = height;
	}

	/**
	 * Position the hardware cursor for IME candidate window, and write `output`, the frame's bytes before it.
	 * @param cursorPos The cursor position extracted from rendered output, or null
	 * @param totalLines Total number of rendered lines
	 * @param output What the frame writes before the cursor is placed: written by this, with the cursor's bytes after it in
	 * the same write when the terminal is a ProcessTerminal (each write is a system call), before them otherwise.
	 */
	private positionHardwareCursor(
		cursorPos: { row: number; col: number } | null,
		totalLines: number,
		output: BoundedTerminalWriter,
	): void {
		const terminal = this.terminal;
		// (ProcessTerminal's write(), showCursor() and hideCursor() all write to stdout: the same bytes in the same order
		// as one write. Another terminal is given them as before, each by its own call.)
		const together =
			terminal.write === ProcessTerminal.prototype.write &&
			terminal.showCursor === ProcessTerminal.prototype.showCursor &&
			terminal.hideCursor === ProcessTerminal.prototype.hideCursor;
		if (!together) output.flush();
		const showCursor = (visible: boolean): void => {
			if (!together) {
				if (visible) terminal.showCursor();
				else terminal.hideCursor();
				return;
			}
			output.append(visible ? CURSOR_SHOW : CURSOR_HIDE);
			output.flush();
		};

		if (!cursorPos || totalLines <= 0) {
			showCursor(false);
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
			if (together) output.append(buffer);
			else terminal.write(buffer);
		}

		this.hardwareCursorRow = targetRow;
		showCursor(this.getShowHardwareCursor());
	}
}
