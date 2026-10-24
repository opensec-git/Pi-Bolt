import assert from "node:assert";
import { describe, it } from "node:test";
import { Text } from "../src/components/text.ts";
import { isImageLine } from "../src/terminal-image.ts";
import { TuiAltScreen } from "../src/tui-alt-screen.ts";
import { VirtualTerminal } from "./virtual-terminal.ts";

class CountingTerminal extends VirtualTerminal {
	written = 0;
	scrolled = 0;
	last = "";

	override write(data: string): void {
		this.written += data.length;
		if (/\x1b\[\d+;\d+r/.test(data)) this.scrolled++;
		this.last = data;
		super.write(data);
	}
}

/** What a terminal shows after the frame is drawn onto an empty screen, row by row. */
async function drawnFromScratch(lines: readonly string[], columns: number, rows: number): Promise<string[][]> {
	const terminal = new VirtualTerminal(columns, rows);
	terminal.write("\x1b[?7l");
	for (let row = 0; row < lines.length; row++) terminal.write(`\x1b[${row + 1};1H${lines[row]}`);
	await terminal.flush();
	return terminal.getStyledViewport();
}

function random(seed: number): () => number {
	let state = seed;
	return () => {
		state = (state * 1103515245 + 12345) & 0x7fffffff;
		return state / 0x7fffffff;
	};
}

describe("TuiAltScreen: rows that moved are scrolled", () => {
	it("scrolls the viewport when a line is added, and draws only the rows that differ", async () => {
		const terminal = new CountingTerminal(40, 12);
		const tui = new TuiAltScreen(terminal);
		const lines = Array.from({ length: 30 }, (_, index) => `\x1b[3${index % 7}mline ${index + 1}\x1b[0m`);
		const text = new Text(lines.join("\n"), 0, 0);
		tui.addChild(text);
		tui.start();
		await terminal.waitForRender();
		const before = terminal.written;

		lines.push("line 31");
		text.setText(lines.join("\n"));
		tui.requestRender();
		await terminal.waitForRender();

		assert.ok(terminal.last.includes("\x1b[1;12r"), "a scroll region around the rows that moved");
		assert.ok(terminal.last.includes("\x1b[12;1H\n\x1b[r"), "one line feed at its bottom, then the region is reset");
		assert.ok(
			terminal.written - before < 120,
			`one row is drawn, not twelve (${terminal.written - before} characters)`,
		);
		assert.deepStrictEqual(
			terminal.getViewport().map((line) => line.trimEnd()),
			Array.from({ length: 12 }, (_, index) => `line ${index + 20}`),
		);
		assert.deepStrictEqual(terminal.getStyledViewport(), await drawnFromScratch(tui.getScreenLines(), 40, 12));
		tui.stop();
	});

	it("scrolls back down under the mouse wheel", async () => {
		const terminal = new CountingTerminal(40, 12);
		const tui = new TuiAltScreen(terminal);
		tui.addChild(new Text(Array.from({ length: 60 }, (_, index) => `line ${index + 1}`).join("\n"), 0, 0));
		tui.start();
		await terminal.waitForRender();

		terminal.sendInput("\x1b[<64;1;1M");
		await terminal.waitForRender();
		assert.ok(terminal.last.includes("\x1bM\x1b[r"), "reverse line feeds at the top of the region");
		assert.deepStrictEqual(terminal.getStyledViewport(), await drawnFromScratch(tui.getScreenLines(), 40, 12));
		tui.stop();
	});

	it("shows what drawing every row would, whatever changes", async () => {
		let scrolled = 0;
		for (let seed = 1; seed <= Number(process.env.PI_TUI_SCROLL_TEST_SEEDS ?? 12); seed++) {
			const next = random(seed);
			const columns = 30 + Math.floor(next() * 30);
			const rows = 6 + Math.floor(next() * 20);
			const terminal = new CountingTerminal(columns, rows);
			const tui = new TuiAltScreen(terminal);
			const lines = Array.from({ length: Math.floor(next() * 60) }, (_, index) => `line ${index}`);
			const text = new Text(lines.join("\n"), 0, 0);
			const dock = new Text("dock", 0, 0);
			tui.addChild(text);
			tui.addChild(dock);
			tui.start();
			await terminal.waitForRender();
			for (let step = 0; step < 60; step++) {
				const action = next();
				// Many rows are alike (empty lines, a repeated line): a row scrolled in must not be taken for one that was there.
				const styled = (value: string) => {
					const kind = next();
					if (kind < 0.25) return "";
					if (kind < 0.5) return "the same line";
					return kind < 0.75 ? `\x1b[4${Math.floor(next() * 7)};1m${value}\x1b[0m` : value;
				};
				if (action < 0.35) {
					for (let count = 1 + Math.floor(next() * 4); count > 0; count--)
						lines.push(styled(`added ${seed}.${step}.${count}`));
				} else if (action < 0.5 && lines.length > 0) {
					lines[lines.length - 1] += " more";
				} else if (action < 0.6 && lines.length > 0) {
					lines.splice(Math.floor(next() * lines.length), 1 + Math.floor(next() * 3));
				} else if (action < 0.7) {
					lines.splice(Math.floor(next() * (lines.length + 1)), 0, styled(`inserted ${step}`), "");
				} else if (action < 0.8 && lines.length > 0) {
					lines[Math.floor(next() * lines.length)] = styled(
						`changed ${step} ${"wide 日本語 ".repeat(Math.floor(next() * 8))}`,
					);
				} else if (action < 0.9) {
					// The mouse wheel, up or down, a few notches.
					for (let count = 1 + Math.floor(next() * 5); count > 0; count--) {
						terminal.sendInput(next() < 0.6 ? "\x1b[<64;1;1M" : "\x1b[<65;1;1M");
					}
				} else {
					dock.setText(next() < 0.5 ? `dock ${step}` : `dock ${step}\nsecond row`);
				}
				text.setText(lines.join("\n"));
				tui.requestRender();
				await terminal.waitForRender();
				assert.deepStrictEqual(
					terminal.getStyledViewport(),
					await drawnFromScratch(tui.getScreenLines(), columns, rows),
					`seed ${seed}, step ${step}`,
				);
			}
			tui.stop();
			scrolled += terminal.scrolled;
		}
		assert.ok(scrolled > 50, `frames were scrolled (${scrolled})`);
	});

	// Rows of either frame are looked at for images only when imageRows says one has some: imageRows must hold exactly the
	// rows of the frame drawn that isImageLine() takes for images.
	it("keeps the image rows of the frame drawn", async () => {
		let withImages = 0;
		for (let seed = 1; seed <= 6; seed++) {
			const next = random(seed);
			const terminal = new CountingTerminal(50, 10);
			const tui = new TuiAltScreen(terminal);
			const lines = Array.from({ length: 20 }, (_, index) => `line ${index}`);
			const text = new Text(lines.join("\n"), 0, 0);
			tui.addChild(text);
			tui.start();
			await terminal.waitForRender();
			for (let step = 0; step < 50; step++) {
				const action = next();
				if (action < 0.4) lines.push(`added ${step}`);
				else if (action < 0.6) lines.push(`\x1b_Ga=p,i=${1 + Math.floor(next() * 3)},q=2;\x1b\\image ${step}`);
				else if (action < 0.75) lines.push(`\x1b]1337;File=inline=1:AAAA\x07 ${step}`);
				else if (action < 0.9 && lines.length > 0) lines.splice(Math.floor(next() * lines.length), 1);
				else terminal.sendInput(next() < 0.5 ? "\x1b[<64;1;1M" : "\x1b[<65;1;1M");
				text.setText(lines.join("\n"));
				tui.requestRender();
				await terminal.waitForRender();
				const state = tui as unknown as { imageRows: Set<string>; previousScreen: string[] };
				const expected = new Set(state.previousScreen.filter((row) => isImageLine(row)));
				assert.deepStrictEqual(state.imageRows, expected, `seed ${seed}, step ${step}`);
				if (expected.size > 0) withImages++;
			}
			tui.stop();
		}
		assert.ok(withImages > 50, `frames had image rows (${withImages})`);
	});
});
