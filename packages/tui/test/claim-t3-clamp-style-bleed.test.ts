// claim T3: a fullscreen row wider than the terminal is cut to the width after its closing reset was appended, so its
// color and hyperlink stay open into the rows written after it.
import assert from "node:assert";
import { describe, it } from "node:test";
import type { Component } from "../src/tui.ts";
import { TuiAltScreen } from "../src/tui-alt-screen.ts";
import { VirtualTerminal } from "./virtual-terminal.ts";

class RecordingTerminal extends VirtualTerminal {
	writes = "";

	override write(data: string): void {
		this.writes += data;
		super.write(data);
	}
}

function staticComponent(lines: string[]): Component {
	return { render: () => lines, invalidate: () => {} };
}

describe("claim T3: fullscreen clamp of an over-wide row", () => {
	it("does not carry the row's background color into the next row", async () => {
		const terminal = new RecordingTerminal(20, 4);
		const tui = new TuiAltScreen(terminal);
		try {
			// A red-background row 40 columns wide, then a plain row.
			tui.setLayoutRoot(staticComponent([`\x1b[41m${"x".repeat(40)}\x1b[49m`, "plain", "", ""]));
			tui.start();
			await terminal.waitForRender();
			const styled = terminal.getStyledViewport();
			// Cell 0 of row 1 ("p"): default background (mode 0).
			assert.match(styled[1]![0]!, /^p\|[^|]*\|0:/, `row 1 cell 0 is ${styled[1]![0]}`);
			// A cell past "plain" on row 1, cleared by the row's erase: default background too.
			assert.match(styled[1]![10]!, /^ \|[^|]*\|0:/, `row 1 cell 10 is ${styled[1]![10]}`);
		} finally {
			tui.stop();
		}
	});

	it("closes the color and hyperlink of a row cut in the exit transcript", async () => {
		const terminal = new RecordingTerminal(20, 4);
		const tui = new TuiAltScreen(terminal);
		tui.setLayoutRoot(
			staticComponent([
				`\x1b[41m\x1b]8;;https://example.com\x07${"x".repeat(40)}\x1b]8;;\x07\x1b[49m`,
				"plain",
				"",
				"",
			]),
		);
		tui.start();
		await terminal.waitForRender();
		terminal.writes = "";
		tui.stop();
		const exit = terminal.writes;
		const rowTwo = exit.indexOf("plain");
		assert.ok(rowTwo !== -1);
		const cutRow = exit.slice(exit.indexOf("x"), rowTwo);
		assert.ok(cutRow.includes("\x1b[0m\x1b]8;;\x07"), `the cut row ends with a reset: ${JSON.stringify(cutRow)}`);
	});

	it("closes a hyperlink cut by the clamp before the next row", async () => {
		const terminal = new RecordingTerminal(20, 4);
		const tui = new TuiAltScreen(terminal);
		try {
			tui.setLayoutRoot(
				staticComponent([`\x1b]8;;https://example.com\x07${"y".repeat(40)}\x1b]8;;\x07`, "plain", "", ""]),
			);
			tui.start();
			await terminal.waitForRender();
			const frame = terminal.writes;
			const rowTwo = frame.indexOf("\x1b[2;1H");
			assert.ok(rowTwo !== -1);
			const beforeRowTwo = frame.slice(0, rowTwo);
			const lastOpen = beforeRowTwo.lastIndexOf("\x1b]8;;https://example.com\x07");
			const lastClose = beforeRowTwo.lastIndexOf("\x1b]8;;\x07");
			assert.ok(lastClose > lastOpen, "the hyperlink is closed before row 2 is written");
		} finally {
			tui.stop();
		}
	});
});
