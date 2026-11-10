import assert from "node:assert";
import { afterEach, beforeEach, describe, it, mock } from "node:test";
import { type Component, setRenderClockForTest } from "../src/tui.ts";
import { TuiMainScreen } from "../src/tui-main-screen.ts";
import { VirtualTerminal } from "./virtual-terminal.ts";

// When frames are slow, the next frame starts no sooner than three times the last frame's duration after the last one
// started, so drawing takes at most about a third of the thread. Frames that are not slow are scheduled as before; input
// is handled at once; the last frame of a burst is always drawn; the first frame, a resize and a forced redraw are drawn
// as soon as before. A fake clock: each frame takes `cost` milliseconds of it.

let now = 0;

/** A component whose render takes `cost` ms of the fake clock; records when each frame started and what it showed. */
class Timed implements Component {
	cost: number;
	text = "start";
	starts: number[] = [];
	shown: string[] = [];
	inputs: Array<{ data: string; at: number }> = [];
	constructor(cost: number) {
		this.cost = cost;
	}
	render(_width: number): string[] {
		this.starts.push(now);
		this.shown.push(this.text);
		now += this.cost;
		return [this.text];
	}
	handleInput(data: string): void {
		this.inputs.push({ data, at: now });
		this.text = data;
	}
	invalidate(): void {}
}

async function ticks(): Promise<void> {
	for (let i = 0; i < 3; i++) await new Promise<void>((resolve) => process.nextTick(resolve));
}

/** Advances the fake clock and the timers by `ms`, a millisecond at a time. */
async function advance(ms: number, each?: () => void): Promise<void> {
	for (let i = 0; i < ms; i++) {
		// (Requests schedule their frame on the next tick: before the clock moves on.)
		await ticks();
		now += 1;
		mock.timers.tick(1);
		each?.();
		await ticks();
	}
}

function setup(cost: number): { tui: TuiMainScreen; terminal: VirtualTerminal; component: Timed } {
	const terminal = new VirtualTerminal(40, 10);
	const tui = new TuiMainScreen(terminal);
	const component = new Timed(cost);
	tui.addChild(component);
	tui.setFocus(component);
	return { tui, terminal, component };
}

function gaps(starts: number[]): number[] {
	return starts.slice(1).map((start, i) => start - starts[i]!);
}

describe("frame spacing", () => {
	beforeEach(() => {
		now = 1000;
		setRenderClockForTest(() => now);
		mock.timers.enable({ apis: ["setTimeout"] });
	});
	afterEach(() => {
		mock.timers.reset();
		setRenderClockForTest(undefined);
	});

	it("schedules frames that are not slow as before: 16 ms apart, input at once", async () => {
		const { tui, component } = setup(2);
		tui.start();
		await advance(20);
		component.starts = [];
		await advance(200, () => tui.requestRender());
		assert.ok(component.starts.length >= 11, `${component.starts.length} frames`);
		for (const gap of gaps(component.starts)) assert.strictEqual(gap, 16);
		// A key 3 ms after a frame: its frame is drawn at once (no 16 ms wait), as before.
		const before = component.starts.length;
		await advance(3);
		const at = now;
		(tui.terminal as VirtualTerminal).sendInput("k");
		await ticks();
		assert.strictEqual(component.starts.length, before + 1);
		assert.strictEqual(component.starts.at(-1), at);
		tui.stop();
	});

	it("draws an immediate frame (the end of an answer) at once, not held by the frame cap or the spacing", async () => {
		const { tui, component } = setup(30);
		tui.start();
		await advance(20);
		tui.requestRender();
		await advance(1);
		const last = component.starts.at(-1)!;
		await advance(3);
		// (The stream asked for a frame: it waits for the spacing. Then the answer ends.)
		component.text = "streamed";
		tui.requestRender();
		await ticks();
		component.text = "answer";
		const at = now;
		tui.requestImmediateRender();
		await ticks();
		assert.strictEqual(component.starts.at(-1), at);
		assert.strictEqual(component.shown.at(-1), "answer");
		// (The stream's frame would have started 90 ms after the last one.)
		assert.ok(at - last < 90, `drawn ${at - last} ms after the last frame`);
		// Nothing else is left waiting: the timer of the stream's frame was replaced.
		const frames = component.starts.length;
		await advance(200);
		assert.strictEqual(component.starts.length, frames);
		tui.stop();
	});

	it("starts a slow frame no sooner than three times the last one's duration after it", async () => {
		const { tui, component } = setup(30);
		tui.start();
		await advance(20);
		component.starts = [];
		const begin = now;
		await advance(2000, () => tui.requestRender());
		const frames = component.starts.length;
		for (const gap of gaps(component.starts)) assert.ok(gap >= 90 && gap <= 91, `gap ${gap}`);
		// (Drawing takes about a third of the thread.)
		const busy = (frames * 30) / (now - begin);
		assert.ok(busy <= 0.34, `busy ${busy}`);
		assert.ok(frames >= 18, `${frames} frames`);
		tui.stop();
	});

	it("always draws the last frame of a burst, with the latest content", async () => {
		const { tui, component } = setup(30);
		tui.start();
		await advance(20);
		component.starts = [];
		component.shown = [];
		tui.requestRender();
		await advance(1);
		const first = component.starts[0]!;
		component.text = "one";
		tui.requestRender();
		await advance(10);
		component.text = "two";
		tui.requestRender();
		await advance(300);
		assert.deepStrictEqual(component.shown, ["start", "two"]);
		assert.strictEqual(component.starts[1]! - first, 90);
		tui.stop();
	});

	it("draws a key's frame at once, not held by the spacing of slow frames", async () => {
		const { tui, terminal, component } = setup(30);
		tui.start();
		await advance(20);
		tui.requestRender();
		await advance(1);
		const last = component.starts.at(-1)!;
		await advance(10);
		// (The stream asks for a frame: it waits for the spacing.)
		tui.requestRender();
		await ticks();
		const sent = now;
		terminal.sendInput("x");
		assert.deepStrictEqual(component.inputs, [{ data: "x", at: sent }]);
		await ticks();
		// Drawn at once, as in 0.9.9: the key's frame, with what the stream asked for.
		assert.strictEqual(component.starts.at(-1), sent);
		assert.strictEqual(component.shown.at(-1), "x");
		assert.ok(component.starts.at(-1)! - last < 90);
		tui.stop();
	});

	it("draws a frame input asks for through a listener (the mouse) as soon as before", async () => {
		const { tui, terminal, component } = setup(30);
		// (A listener that consumes the input and asks for a frame, as the fullscreen viewport does for the wheel.)
		tui.addInputListener((data) => {
			if (data !== "wheel") return undefined;
			component.text = "scrolled";
			tui.requestRender();
			return { consume: true };
		});
		tui.start();
		await advance(20);
		tui.requestRender();
		await advance(1);
		const last = component.starts.at(-1)!;
		await advance(2);
		// (A frame the stream asked for, waiting for the spacing, then the wheel.)
		tui.requestRender();
		await advance(3);
		const sent = now;
		terminal.sendInput("wheel");
		await advance(40);
		const frame = component.starts.find((start) => start > last)!;
		// (16 ms after the frame before started have passed: drawn on the next timer tick, as in 0.9.9; not 90 ms after.)
		assert.ok(frame - sent <= 1, `drawn ${frame - sent} ms after the wheel`);
		assert.strictEqual(component.shown[component.starts.indexOf(frame)], "scrolled");
		tui.stop();
	});

	it("keeps the thread from being busy with frames while someone types during a stream", async () => {
		const { tui, terminal, component } = setup(30);
		tui.start();
		await advance(20);
		component.starts = [];
		const begin = now;
		let keys = 0;
		const echoes: number[] = [];
		await advance(3000, () => {
			tui.requestRender();
			// (Typing fast: a key every 100 ms.)
			if ((now - begin) % 100 === 0) {
				const sent = now;
				terminal.sendInput(`k${keys++}`);
				echoes.push(sent);
			}
		});
		await ticks();
		// Each key's frame is drawn when the key is handled.
		for (const sent of echoes) assert.ok(component.starts.includes(sent), `key at ${sent} drawn at once`);
		const busy = (component.starts.length * 30) / (now - begin);
		// (Key frames: 30 ms of every 100; the stream's frames are spaced after them.)
		assert.ok(busy <= 0.5, `busy ${busy}`);
		tui.stop();
	});

	// A frame that is never drawn leaves the screen stale until something else asks for one (a run of benchmark.py
	// waiting for its "Done" line would wait for ever). Whatever the order of requests, keys, resizes and forced redraws,
	// and however long frames take, the last frame drawn shows the last state, and nothing is left waiting.
	for (let seed = 1; seed <= 40; seed++) {
		it(`draws the last state after any mix of requests and frame times (seed ${seed})`, async () => {
			let state = seed >>> 0;
			const draw = (count: number) => {
				state = (Math.imul(state ^ (state >>> 15), 2246822507) + 0x9e3779b9) >>> 0;
				return state % count;
			};
			const { tui, terminal, component } = setup(1);
			tui.start();
			await advance(5);
			let text = "start";
			for (let step = 0; step < 120; step++) {
				component.cost = [1, 3, 8, 20, 40][draw(5)]!;
				const event = draw(10);
				if (event < 5) {
					text = `stream ${step}`;
					component.text = text;
					tui.requestRender();
				} else if (event < 8) {
					text = `key ${step}`;
					terminal.sendInput(text);
				} else if (event === 8) {
					terminal.resize(40 + draw(5), 10);
				} else {
					tui.requestRender(true);
				}
				await advance(draw(30));
			}
			await advance(1000);
			assert.strictEqual(component.shown.at(-1), text, `seed ${seed}: the last state drawn`);
			const frames = component.starts.length;
			await advance(1000);
			assert.strictEqual(component.starts.length, frames, `seed ${seed}: nothing left waiting`);
			tui.stop();
		});
	}

	it("draws a resize and a forced redraw as soon as before, and the first frame", async () => {
		const { tui, terminal, component } = setup(30);
		tui.start();
		await advance(1);
		assert.strictEqual(component.starts.length, 1, "the first frame");
		await advance(100);
		tui.requestRender();
		await advance(1);
		const last = component.starts.at(-1)!;
		terminal.resize(50, 10);
		await advance(40);
		const resized = component.starts.find((start) => start > last)!;
		assert.ok(resized - last <= 31, `resize drawn ${resized - last} ms after the frame before`);
		await advance(5);
		const count = component.starts.length;
		tui.requestRender(true);
		await ticks();
		assert.strictEqual(component.starts.length, count + 1, "the forced redraw at once");
		tui.stop();
	});
});
