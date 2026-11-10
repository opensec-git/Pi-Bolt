import assert from "node:assert";
import { describe, it } from "node:test";
import { ProcessTerminal } from "../src/terminal.ts";
import { type Component, CURSOR_MARKER } from "../src/tui.ts";
import { TuiMainScreen } from "../src/tui-main-screen.ts";

// The regular screen writes a frame, then moves the cursor and shows or hides it. To a ProcessTerminal, which writes all
// three to stdout, that is one write of the same bytes in the same order; to another terminal, three calls as before.

class Lines implements Component {
	lines: string[] = [];
	render(): string[] {
		return this.lines;
	}
	invalidate(): void {}
}

/** A ProcessTerminal whose write() is its own: the screen cannot tell what it writes, and calls each method. */
class OwnWriteTerminal extends ProcessTerminal {
	override write(data: string): void {
		super.write(data);
	}
}

/** The writes to stdout made by each frame of the same session, drawn on `terminal`. */
function session(terminal: ProcessTerminal): string[][] {
	const frames: string[][] = [];
	const original = process.stdout.write;
	let writes: string[] = [];
	process.stdout.write = ((chunk: string | Uint8Array) => {
		writes.push(typeof chunk === "string" ? chunk : Buffer.from(chunk).toString("utf8"));
		return true;
	}) as typeof process.stdout.write;
	try {
		const screen = new TuiMainScreen(terminal);
		const content = new Lines();
		screen.addChild(content);
		const frame = (change: () => void) => {
			// (Only the frame's writes: setShowHardwareCursor() hides the cursor itself.)
			change();
			writes = [];
			screen.renderNow();
			frames.push(writes);
			writes = [];
		};
		frame(() => {
			content.lines = ["first", "second"];
		});
		frame(() => {
			content.lines = ["first", "second changed", "third"];
		});
		// (Nothing changed, and the cursor shown where the marker is.)
		frame(() => {});
		frame(() => {
			screen.setShowHardwareCursor(true);
			content.lines = ["first", `> input${CURSOR_MARKER} here`, "third"];
		});
		frame(() => {
			content.lines = ["first", `> input text${CURSOR_MARKER}`];
		});
		frame(() => {
			screen.setShowHardwareCursor(false);
			content.lines = Array.from({ length: 60 }, (_, i) => `line ${i}`);
		});
		frame(() => {
			content.lines = Array.from({ length: 61 }, (_, i) => `line ${i}${i === 59 ? " changed" : ""}`);
		});
		frame(() => {
			content.lines = ["short again"];
		});
	} finally {
		process.stdout.write = original;
	}
	return frames;
}

describe("Regular screen: one write per frame", () => {
	it("writes what three writes wrote, in one write to a ProcessTerminal", () => {
		const together = session(new ProcessTerminal());
		const separate = session(new OwnWriteTerminal());
		assert.strictEqual(together.length, separate.length);
		for (let i = 0; i < together.length; i++) {
			assert.strictEqual(together[i]!.join(""), separate[i]!.join(""), `frame ${i}`);
			assert.strictEqual(together[i]!.length, 1, `frame ${i}: ${JSON.stringify(together[i])}`);
		}
		// (A frame that draws lines and places the cursor made three writes.)
		assert.strictEqual(separate[3]!.length, 3, JSON.stringify(separate[3]));
	});
});
