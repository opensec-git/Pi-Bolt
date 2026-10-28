import assert from "node:assert";
import { describe, it } from "node:test";
import { Text } from "../src/components/text.ts";
import { encodeKitty } from "../src/terminal-image.ts";
import { type Component, Container } from "../src/tui.ts";
import { TuiMainScreen } from "../src/tui-main-screen.ts";
import { VirtualTerminal } from "./virtual-terminal.ts";

// The regular screen keeps the last frame's lines as they were before applyLineResets() (the components' own strings) and
// makes the lines written from them when it writes or compares them: the lines written are not kept, which for a long
// transcript would be a copy of all of its lines. What it writes is what it wrote when it kept them.

const RESET = "\x1b[0m\x1b]8;;\x07";

class LoggingTerminal extends VirtualTerminal {
	writes = "";
	override write(data: string): void {
		this.writes += data;
		super.write(data);
	}
}

class Lines implements Component {
	lines: string[];
	constructor(lines: string[]) {
		this.lines = lines;
	}
	render(): string[] {
		return this.lines;
	}
	invalidate(): void {}
}

/** The strings in the screen's own fields and the arrays in them (one level down too), that end as a line written does. */
function writtenLinesKept(screen: TuiMainScreen): number {
	let count = 0;
	const visit = (value: unknown, depth: number) => {
		if (typeof value === "string") {
			if (value.endsWith(RESET)) count++;
		} else if (Array.isArray(value)) {
			for (const item of value) if (typeof item === "string" && item.endsWith(RESET)) count++;
		} else if (depth > 0 && typeof value === "object" && value !== null && !(value instanceof Container)) {
			for (const field of Object.values(value)) visit(field, depth - 1);
		}
	};
	for (const field of Object.values(screen)) visit(field, 1);
	return count;
}

describe("Regular screen: lines written", () => {
	it("are not kept, and are the lines made from the frame's lines", () => {
		const terminal = new LoggingTerminal(40, 10);
		const screen = new TuiMainScreen(terminal);
		const transcript = new Container();
		for (let i = 0; i < 200; i++) transcript.addChild(new Text(`entry ${i}`, 0, 0));
		screen.addChild(transcript);
		screen.start();
		screen.renderNow();
		transcript.addChild(new Text("one more", 0, 0));
		screen.renderNow();
		assert.strictEqual(writtenLinesKept(screen), 0);
		const shown = screen.shownLinesForTest();
		assert.strictEqual(shown.length, 201);
		assert.strictEqual(shown[200], `${"one more".padEnd(40)}${RESET}`);
		screen.stop();
	});

	it("a line changed to one written the same is not written again", () => {
		const terminal = new LoggingTerminal(40, 10);
		const screen = new TuiMainScreen(terminal);
		const line = new Lines(["tab\there"]);
		screen.addChild(new Text("first", 0, 0));
		screen.addChild(line);
		screen.start();
		screen.renderNow();
		// (applyLineResets() writes a tab as three spaces.)
		line.lines = ["tab   here"];
		terminal.writes = "";
		screen.renderNow();
		assert.ok(!terminal.writes.includes("here"), JSON.stringify(terminal.writes));
		line.lines = ["tab  here"];
		terminal.writes = "";
		screen.renderNow();
		assert.ok(terminal.writes.includes("tab  here"), JSON.stringify(terminal.writes));
		screen.stop();
	});

	it("rows restored as an image's after the last line count as shown, as before", () => {
		const terminal = new LoggingTerminal(40, 10);
		const screen = new TuiMainScreen(terminal);
		screen.setClearOnShrink(false);
		const transcript = new Container();
		for (let i = 0; i < 3; i++) transcript.addChild(new Text(`entry ${i}`, 0, 0));
		const image = new Lines([encodeKitty("AAAA", { columns: 4, rows: 1, imageId: 7, moveCursor: false })]);
		transcript.addChild(image);
		screen.addChild(transcript);
		screen.start();
		screen.renderNow();
		// (A restored state has "" for each image line: the image is not drawn again by this screen.)
		screen.restoreRenderState(screen.captureRenderState());
		transcript.removeChild(image);
		terminal.writes = "";
		screen.renderNow();
		assert.strictEqual(terminal.writes, "");
		assert.strictEqual(screen.shownLinesForTest().length, 4);
		screen.stop();
	});
});
