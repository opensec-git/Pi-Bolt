import assert from "node:assert";
import { afterEach, beforeEach, describe, it, mock } from "node:test";
import { Loader } from "../src/components/loader.ts";
import type { Terminal } from "../src/terminal.ts";
import { type Component, setRenderClockForTest, type TUI } from "../src/tui.ts";
import { TuiMainScreen } from "../src/tui-main-screen.ts";

// A spinner's step asks for a frame within a few dozen milliseconds (TUI.requestRenderWithin()) instead of one of its own:
// while a reply streams in, the next frame drawn for the reply draws the step. Each step is still drawn, in order, and
// a step with no other frame to ride on is drawn when the time is up. A fake clock and fake timers.
// (A terminal that keeps nothing: xterm's headless terminal sets timers of its own, which the fake timers do not run.)

class NullTerminal implements Terminal {
	start(): void {}
	stop(): void {}
	async drainInput(): Promise<void> {}
	write(_data: string): void {}
	get columns(): number {
		return 40;
	}
	get rows(): number {
		return 10;
	}
	get kittyProtocolActive(): boolean {
		return false;
	}
	moveBy(): void {}
	hideCursor(): void {}
	showCursor(): void {}
	clearLine(): void {}
	clearFromCursor(): void {}
	clearScreen(): void {}
	setTitle(): void {}
	setProgress(): void {}
	setProgramStatus(): void {}
}

const FRAMES = ["a", "b", "c", "d"];
let now = 0;

/** Records each frame: when it started and what the loader and the stream showed. */
class Recorder implements Component {
	loader: Loader | undefined;
	text = "";
	starts: number[] = [];
	shown: string[] = [];
	render(width: number): string[] {
		const loaderLines = this.loader?.render(width) ?? [];
		this.starts.push(now);
		this.shown.push(loaderLines.join("").trim());
		return [...loaderLines, this.text];
	}
	invalidate(): void {}
}

async function ticks(): Promise<void> {
	for (let i = 0; i < 3; i++) await new Promise<void>((resolve) => process.nextTick(resolve));
}

/** Advances the fake clock and the timers by `ms`, a millisecond at a time. */
async function advance(ms: number, each?: () => void): Promise<void> {
	for (let i = 0; i < ms; i++) {
		await ticks();
		now += 1;
		mock.timers.tick(1);
		each?.();
		await ticks();
	}
}

function setup(): { tui: TuiMainScreen; recorder: Recorder } {
	const tui = new TuiMainScreen(new NullTerminal());
	const recorder = new Recorder();
	tui.addChild(recorder);
	return { tui, recorder };
}

function startLoader(tui: TUI, recorder: Recorder): Loader {
	const loader = new Loader(
		tui,
		(text) => text,
		(text) => text,
		"working",
		{ frames: FRAMES, intervalMs: 80 },
	);
	recorder.loader = loader;
	return loader;
}

/** The distinct steps the frames showed, in order. */
function steps(shown: string[]): string[] {
	return shown.filter((text, i) => text !== "" && text !== shown[i - 1]);
}

describe("loader frames", () => {
	beforeEach(() => {
		now = 1000;
		setRenderClockForTest(() => now);
		mock.timers.enable({ apis: ["setTimeout", "setInterval"] });
	});
	afterEach(() => {
		mock.timers.reset();
		setRenderClockForTest(undefined);
	});

	it("draws each step when nothing else draws, within the delay of its tick", async () => {
		const { tui, recorder } = setup();
		tui.start();
		await advance(20);
		const loader = startLoader(tui, recorder);
		await advance(20);
		recorder.starts = [];
		recorder.shown = [];
		// (The loader started 20 ms ago: its ticks come 60 ms from now, then every 80 ms.)
		const firstTick = now + 60;
		await advance(800);
		// Nine ticks drawn so far, each once: 40 ms after the tick, plus the frame's own scheduling (the tenth is due).
		assert.deepStrictEqual(
			steps(recorder.shown),
			["b", "c", "d", "a", "b", "c", "d", "a", "b"].map((step) => `${step} working`),
		);
		assert.strictEqual(recorder.starts.length, 9);
		for (const [i, start] of recorder.starts.entries()) {
			const tick = firstTick + 80 * i;
			assert.ok(start - tick >= 40 && start - tick <= 40 + 16, `step ${i}: drawn ${start - tick} ms after its tick`);
		}
		loader.stop();
		tui.stop();
	});

	it("adds no frames while other frames are drawn, and every step is still drawn", async () => {
		const { tui, recorder } = setup();
		tui.start();
		await advance(20);
		const loader = startLoader(tui, recorder);
		await advance(20);
		recorder.starts = [];
		recorder.shown = [];
		// A reply streaming in: a chunk every 30 ms.
		let chunks = 0;
		let elapsed = 0;
		await advance(810, () => {
			if (++elapsed % 30 !== 0) return;
			recorder.text = `chunk ${++chunks}`;
			tui.requestRender();
		});
		// (The last chunk's frame.)
		await advance(17);
		// One frame per chunk and none for the steps, which the chunks' frames show: all ten, in order.
		assert.strictEqual(recorder.starts.length, chunks);
		assert.deepStrictEqual(
			steps(recorder.shown),
			["a", "b", "c", "d", "a", "b", "c", "d", "a", "b", "c"].map((step) => `${step} working`),
		);
		loader.stop();
		tui.stop();
	});

	it("draws a change asked for after a frame that met an earlier request", async () => {
		const { tui, recorder } = setup();
		tui.start();
		await advance(20);
		recorder.starts = [];
		// One loader's step asks for a frame within 40 ms; a frame drawn for something else meets it 10 ms later; another
		// loader's step, 10 ms after that, still gets a frame of its own.
		tui.requestRenderWithin(40);
		await advance(10);
		tui.requestRender();
		await advance(10);
		const framesBefore = recorder.starts.length;
		const asked = now;
		tui.requestRenderWithin(40);
		await advance(100);
		assert.ok(recorder.starts.length > framesBefore, "no frame after the second request");
		assert.ok(recorder.starts[recorder.starts.length - 1]! >= asked + 40);
		tui.stop();
	});

	it("draws a message change at once, and asks for no frame after stop()", async () => {
		const { tui, recorder } = setup();
		tui.start();
		await advance(20);
		const loader = startLoader(tui, recorder);
		await advance(20);
		recorder.starts = [];
		recorder.shown = [];
		loader.setMessage("other");
		await advance(17);
		assert.deepStrictEqual(recorder.shown, ["a other"]);
		// A tick, then the TUI stops before the step's frame is due.
		await advance(80 - 17 - 20 + 1);
		tui.stop();
		const frames = recorder.starts.length;
		await advance(200);
		assert.strictEqual(recorder.starts.length, frames);
		loader.stop();
	});
});
