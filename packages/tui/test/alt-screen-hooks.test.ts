import assert from "node:assert/strict";
import { test } from "node:test";
import { Text } from "../src/components/text.ts";
import { TuiAltScreen } from "../src/tui-alt-screen.ts";
import { VirtualTerminal } from "./virtual-terminal.ts";

class RecordingTerminal extends VirtualTerminal {
	writes = "";
	failWrite = false;

	override write(data: string): void {
		if (this.failWrite) throw new Error("write failure");
		this.writes += data;
		super.write(data);
	}
}

test("fullscreen retries a frame after a terminal write fails", () => {
	const terminal = new RecordingTerminal(30, 4);
	const screen = new TuiAltScreen(terminal);
	const text = new Text("old content", 0, 0);
	screen.addChild(text);
	screen.start();
	try {
		screen.renderNow();
		text.setText("new content");

		terminal.failWrite = true;
		assert.throws(() => screen.renderNow(), /write failure/);
		assert.ok(screen.getScreenLines()[0]?.includes("old content"));

		terminal.failWrite = false;
		terminal.writes = "";
		screen.renderNow();
		assert.ok(terminal.writes.includes("new content"));
		assert.ok(screen.getScreenLines()[0]?.includes("new content"));
	} finally {
		terminal.failWrite = false;
		screen.stop({ preserveScreen: true });
	}
});

class SparseReplayScreen extends TuiAltScreen {
	protected override applyLineResets(lines: string[]): string[] {
		if (lines.length < 2) return super.applyLineResets(lines);
		const replay = new Array<string>(3);
		replay[0] = lines[0]!;
		replay[2] = lines[1]!;
		return replay;
	}
}

test("fullscreen stop preserves blank rows in sparse output from a line reset hook", () => {
	const terminal = new RecordingTerminal(30, 4);
	const screen = new SparseReplayScreen(terminal);
	screen.addChild({
		render: () => ["first", "second"],
		invalidate() {},
	});
	screen.start();
	screen.renderNow();
	terminal.writes = "";

	screen.stop();

	assert.ok(terminal.writes.includes("first\r\n\r\x1b[2K\r\n\r\x1b[2Ksecond"));
});
