import assert from "node:assert";
import { describe, it } from "node:test";
import { Text } from "../src/components/text.ts";
import { encodeKitty } from "../src/terminal-image.ts";
import { type Component, Container, CURSOR_MARKER, type OverlayHandle, renderFakeCursor } from "../src/tui.ts";
import { TuiMainScreen, type TuiMainScreenRenderState } from "../src/tui-main-screen.ts";
import { VirtualTerminal } from "./virtual-terminal.ts";

// When the root's lines above some line are those of the frame before, the regular screen changes the last frame's
// arrays in place from that line on instead of copying all of them. Whatever happens (streaming, edits of earlier entries,
// images, cursor markers, overlays, resizes, the hardware cursor, forced redraws, a restored render state), every frame must
// write exactly the bytes that the same screen writes when it makes each frame's lines from a full copy of the root's.

function random(seed: number): () => number {
	let state = seed >>> 0 || 1;
	return () => {
		state = (Math.imul(state ^ (state >>> 15), 2246822507) + 0x9e3779b9) >>> 0;
		return state / 4294967296;
	};
}

class LoggingTerminal extends VirtualTerminal {
	writes = "";
	override write(data: string): void {
		this.writes += data;
		super.write(data);
	}
}

/** Its root renders through render(), which overrides Container's: no live lines, so every frame copies all the lines. */
class FullCopyScreen extends TuiMainScreen {
	override render(width: number): string[] {
		return super.render(width);
	}
}

/** Lines that do not report their changes: rendered again each frame (an extension's component, or the editor). */
class Lines implements Component {
	lines: string[];
	constructor(lines: string[]) {
		this.lines = lines;
	}
	render(width: number): string[] {
		return this.lines.map((line) => (line.includes("\x1b_G") ? line : line.slice(0, width)));
	}
	invalidate(): void {}
}

const WORDS = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu".split(" ");

type Op =
	| { kind: "stream"; text: string }
	| { kind: "add"; text: string }
	| { kind: "image"; id: number; rows: number }
	| { kind: "edit"; at: number; text: string }
	| { kind: "insert"; at: number; text: string }
	| { kind: "remove"; at: number }
	| { kind: "shrink"; count: number }
	| { kind: "status"; text: string }
	| { kind: "editor"; text: string; cursor: number | undefined }
	| { kind: "hardwareCursor"; shown: boolean }
	| { kind: "overlay" }
	| { kind: "resize"; columns: number; rows: number }
	| { kind: "clearOnShrink"; enabled: boolean }
	| { kind: "force" }
	| { kind: "restore" }
	| { kind: "none" };

function makeOps(seed: number, count: number): Op[] {
	const next = random(seed);
	const pick = (n: number) => Math.floor(next() * n);
	const text = (words: number) => {
		const out: string[] = [];
		for (let i = 0; i < words; i++) {
			out.push(WORDS[pick(WORDS.length)]!);
			if (next() < 0.08) out.push("\n");
			if (next() < 0.03) out.push("\t");
		}
		return out.join(" ");
	};
	const ops: Op[] = [];
	for (let i = 0; i < count; i++) {
		const r = next();
		if (r < 0.35) ops.push({ kind: "stream", text: next() < 0.2 ? `\n${text(3)}` : ` ${text(1 + pick(4))}` });
		else if (r < 0.43) ops.push({ kind: "add", text: text(4 + pick(30)) });
		else if (r < 0.46) ops.push({ kind: "image", id: 10 + pick(5), rows: 1 + pick(3) });
		else if (r < 0.52) ops.push({ kind: "edit", at: pick(1000), text: text(2 + pick(20)) });
		else if (r < 0.55) ops.push({ kind: "insert", at: pick(1000), text: text(3 + pick(10)) });
		else if (r < 0.58) ops.push({ kind: "remove", at: pick(1000) });
		else if (r < 0.6) ops.push({ kind: "shrink", count: 1 + pick(4) });
		else if (r < 0.68) ops.push({ kind: "status", text: `working ${"|/-\\"[pick(4)]} ${pick(100)}` });
		else if (r < 0.76) {
			const value = text(1 + pick(6))
				.replaceAll("\n", " ")
				.replaceAll("\t", " ");
			ops.push({ kind: "editor", text: value, cursor: next() < 0.8 ? pick(value.length + 1) : undefined });
		} else if (r < 0.78) ops.push({ kind: "hardwareCursor", shown: next() < 0.5 });
		else if (r < 0.81) ops.push({ kind: "overlay" });
		else if (r < 0.84) {
			ops.push({
				kind: "resize",
				columns: next() < 0.5 ? 40 : 30 + pick(40),
				rows: next() < 0.5 ? 10 : 6 + pick(10),
			});
		} else if (r < 0.85) ops.push({ kind: "clearOnShrink", enabled: next() < 0.5 });
		else if (r < 0.86) ops.push({ kind: "force" });
		else if (r < 0.87) ops.push({ kind: "restore" });
		else ops.push({ kind: "none" });
	}
	return ops;
}

class World {
	readonly terminal = new LoggingTerminal(40, 10);
	readonly tui: TuiMainScreen;
	readonly transcript = new Container();
	readonly status = new Text("ready", 0, 0);
	readonly editor = new Lines(["> "]);
	streaming: Text | undefined;
	streamed = "";
	overlay: OverlayHandle | undefined;

	constructor(fullCopy: boolean) {
		this.tui = fullCopy ? new FullCopyScreen(this.terminal) : new TuiMainScreen(this.terminal);
		this.tui.addChild(this.transcript);
		this.tui.addChild(this.status);
		this.tui.addChild(this.editor);
		for (let i = 0; i < 30; i++) this.transcript.addChild(new Text(`entry ${i} ${WORDS[i % WORDS.length]}`, 0, 0));
	}

	apply(op: Op): boolean {
		const children = this.transcript.children;
		switch (op.kind) {
			case "stream":
				if (this.streaming === undefined) {
					this.streaming = new Text("", 0, 0);
					this.streamed = "";
					this.transcript.addChild(this.streaming);
				}
				this.streamed += op.text;
				this.streaming.setText(this.streamed);
				break;
			case "add":
				this.streaming = undefined;
				this.transcript.addChild(new Text(op.text, 0, 0));
				break;
			case "image": {
				const image = encodeKitty("AAAA", { columns: 4, rows: op.rows, imageId: op.id, moveCursor: false });
				this.transcript.addChild(new Lines([image, ...Array.from({ length: op.rows - 1 }, () => "")]));
				break;
			}
			case "edit": {
				const child = children[op.at % Math.max(1, children.length)];
				if (child instanceof Text) child.setText(op.text);
				break;
			}
			case "insert":
				children.splice(op.at % (children.length + 1), 0, new Text(op.text, 0, 0));
				break;
			case "remove":
				if (children.length > 0) {
					const [removed] = children.splice(op.at % children.length, 1);
					if (removed === this.streaming) this.streaming = undefined;
				}
				break;
			case "shrink":
				for (let i = 0; i < op.count && children.length > 0; i++) {
					if (children.pop() === this.streaming) this.streaming = undefined;
				}
				break;
			case "status":
				this.status.setText(op.text);
				break;
			case "editor":
				this.editor.lines =
					op.cursor === undefined
						? [`> ${op.text}`]
						: [
								`> ${op.text.slice(0, op.cursor)}${CURSOR_MARKER}${renderFakeCursor(op.text[op.cursor] ?? " ")}${op.text.slice(op.cursor + 1)}`,
							];
				break;
			case "hardwareCursor":
				this.tui.setShowHardwareCursor(op.shown);
				break;
			case "overlay":
				if (this.overlay === undefined) {
					this.overlay = this.tui.showOverlay(new Text("an overlay\nof two lines", 1, 0), {
						anchor: "bottom-left",
						width: 20,
					});
				} else {
					this.overlay.hide();
					this.overlay = undefined;
				}
				break;
			case "resize":
				this.terminal.resize(op.columns, op.rows);
				break;
			case "clearOnShrink":
				this.tui.setClearOnShrink(op.enabled);
				break;
			case "force":
				return true;
			case "restore": {
				const state: TuiMainScreenRenderState = this.tui.captureRenderState();
				this.tui.restoreRenderState(state);
				break;
			}
			case "none":
				break;
		}
		return false;
	}

	frame(force: boolean): string {
		this.terminal.writes = "";
		this.tui.renderNow(force);
		return this.terminal.writes;
	}
}

const shown = (tui: TuiMainScreen) => tui.shownLinesForTest();

/**
 * What a terminal shows of `lines` drawn at once on a new screen: the last rows of them. An oracle that does not go through
 * the code under test's diff (the reference screen shares it), for frames whose lines reach the bottom of the screen.
 */
async function freshViewport(lines: string[], columns: number, rows: number): Promise<string[]> {
	const terminal = new VirtualTerminal(columns, rows);
	const tui = new TuiMainScreen(terminal);
	tui.addChild(new Lines(lines.slice()));
	tui.start();
	tui.renderNow();
	const viewport = await terminal.flushAndGetViewport();
	tui.stop();
	return viewport;
}

describe("Regular screen: frames changed in place", () => {
	for (let seed = 1; seed <= 24; seed++) {
		it(`writes the bytes a full copy of the lines writes (seed ${seed})`, async () => {
			const ops = makeOps(seed * 7919, 220);
			const live = new World(false);
			const reference = new World(true);
			live.tui.start();
			reference.tui.start();
			assert.strictEqual(live.frame(false), reference.frame(false), "first frame");
			for (let step = 0; step < ops.length; step++) {
				const op = ops[step]!;
				const force = live.apply(op);
				reference.apply(op);
				const label = `seed ${seed} step ${step}: ${JSON.stringify(ops.slice(Math.max(0, step - 6), step + 1))}`;
				assert.strictEqual(live.frame(force), reference.frame(force), label);
				assert.deepStrictEqual(shown(live.tui), shown(reference.tui), label);
				const lines = shown(live.tui);
				const { columns, rows } = live.terminal;
				const maxLines = (live.tui as unknown as { maxLinesRendered: number }).maxLinesRendered;
				// (After the content shrank, the rows below it are left as they were: compared only while it is at its longest.)
				if (live.overlay === undefined && lines.length >= rows && lines.length === maxLines) {
					assert.deepStrictEqual(
						await live.terminal.flushAndGetViewport(),
						await freshViewport(lines, columns, rows),
						`${label}: what a new screen shows`,
					);
				}
				if (step % 20 === 19) {
					assert.deepStrictEqual(
						await live.terminal.flushAndGetViewport(),
						await reference.terminal.flushAndGetViewport(),
						label,
					);
					assert.deepStrictEqual(live.terminal.getScrollBuffer(), reference.terminal.getScrollBuffer(), label);
				}
			}
			live.tui.stop();
			reference.tui.stop();
		});
	}

	it("streams into a long transcript without copying the lines above what changed", () => {
		const live = new World(false);
		for (let i = 0; i < 3000; i++) live.transcript.addChild(new Text(`history ${i}`, 0, 0));
		live.tui.start();
		live.frame(false);
		const rawLines = () => (live.tui as unknown as { rawLines: string[] }).rawLines;
		const raw = rawLines();
		const slice = Array.prototype.slice;
		let copied = 0;
		Array.prototype.slice = function (this: unknown[], ...args: [number?, number?]) {
			const result = slice.apply(this, args) as unknown[];
			copied = Math.max(copied, result.length);
			return result;
		};
		try {
			for (let i = 0; i < 40; i++) {
				live.apply({ kind: "stream", text: ` ${WORDS[i % WORDS.length]}` });
				live.apply({ kind: "status", text: `working ${i}` });
				live.frame(false);
			}
		} finally {
			Array.prototype.slice = slice;
		}
		assert.strictEqual(rawLines(), raw, "the raw lines are changed in place");
		assert.ok(copied < 100, `largest copy per frame: ${copied} lines of ${raw.length}`);
		live.tui.stop();
	});
});
