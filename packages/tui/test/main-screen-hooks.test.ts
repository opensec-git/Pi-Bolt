import assert from "node:assert/strict";
import { test } from "node:test";
import { Text } from "../src/components/text.ts";
import { type Component, TuiMainScreen } from "../src/index.ts";
import { deleteKittyImage, encodeKitty } from "../src/terminal-image.ts";
import { VirtualTerminal } from "./virtual-terminal.ts";

class RecordingTerminal extends VirtualTerminal {
	writes = "";

	override write(data: string): void {
		this.writes += data;
		super.write(data);
	}
}

class HistoryCursorScreen extends TuiMainScreen {
	prefix = "old prefix";

	protected override extractCursorPosition(lines: string[], height: number): { row: number; col: number } | null {
		lines[0] = this.prefix;
		return super.extractCursorPosition(lines, height);
	}
}

test("a cursor hook can change a history row while an overlay is open", () => {
	const terminal = new RecordingTerminal(30, 4);
	const screen = new HistoryCursorScreen(terminal);
	for (let i = 0; i < 15; i++) screen.addChild(new Text(`entry ${i}`, 0, 0));
	screen.showOverlay(new Text("modal", 0, 0), { width: 8 });
	screen.start();
	try {
		screen.renderNow();
		terminal.writes = "";

		screen.prefix = "new prefix";
		screen.renderNow();

		assert.ok(terminal.writes.includes("new prefix"), "the changed history row is redrawn");
	} finally {
		screen.stop();
	}
});

class ImageRowsScreen extends TuiMainScreen {
	protected override applyLineResets(lines: string[]): string[] {
		return super.applyLineResets(lines.map((line) => line.replace(",r=1,", ",r=3,")));
	}
}

test("Kitty redraws a placement spanning the row count written by a line reset hook", () => {
	const terminal = new RecordingTerminal(30, 6);
	const screen = new ImageRowsScreen(terminal);
	const imageId = 9;
	const image = encodeKitty("AAAA", { columns: 2, rows: 1, imageId, moveCursor: false });
	const component: Component & { lines: string[] } = {
		lines: [image, "", "", "stable"],
		render() {
			return this.lines;
		},
		invalidate() {},
	};
	screen.addChild(component);
	screen.start();
	try {
		screen.renderNow();
		terminal.writes = "";

		component.lines[2] = "\x1b[31m";
		screen.renderNow();

		assert.ok(terminal.writes.includes(deleteKittyImage(imageId)), "the old placement is deleted");
		assert.ok(terminal.writes.includes(image.replace(",r=1,", ",r=3,")), "the image is placed again");
	} finally {
		screen.stop();
	}
});
