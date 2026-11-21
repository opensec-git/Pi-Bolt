import assert from "node:assert";
import { describe, it } from "node:test";
import { Markdown, setMarkdownCachingForTest } from "../src/components/markdown.ts";
import { MouseRegion } from "../src/components/mouse-region.ts";
import { Spacer } from "../src/components/spacer.ts";
import { Text } from "../src/components/text.ts";
import {
	type Component,
	Container,
	LiveLineArray,
	type LiveLines,
	markLiveRender,
	renderLiveLines,
} from "../src/tui.ts";
import { defaultMarkdownTheme } from "./test-themes.ts";

// Containers and Markdown change their lines in place (LiveLines) for readers that read them again from the line
// changedSince() names, and give everyone else arrays that never change (render(), renderShared()). Whatever changes, a
// reader that follows the changes must hold what the components render from nothing, and the arrays handed out must stay
// as they were.

function random(seed: number): () => number {
	let state = seed >>> 0 || 1;
	return () => {
		state = (Math.imul(state ^ (state >>> 15), 2246822507) + 0x9e3779b9) >>> 0;
		return state / 4294967296;
	};
}

/** A reader of LiveLines: keeps a copy, brought up to date from the line changedSince() names. */
class Follower {
	lines: string[] = [];
	private source?: LiveLines;
	private revision = -1;

	read(live: LiveLines): string[] {
		const from = live === this.source ? live.changedSince(this.revision) : 0;
		if (from !== Number.POSITIVE_INFINITY) {
			this.lines.length = Math.min(this.lines.length, from);
			for (let i = from; i < live.lines.length; i++) this.lines.push(live.lines[i]!);
		}
		this.source = live;
		this.revision = live.revision;
		return this.lines;
	}
}

/** A component that does not keep its lines: a new array each time, the same lines or not. */
class Fresh implements Component {
	text: string;
	constructor(text: string) {
		this.text = text;
	}
	render(width: number): string[] {
		return this.text.split("\n").map((line) => line.slice(0, width));
	}
	invalidate(): void {}
}

/** One of the two trees: the one rendered live, or the reference, rendered from nothing with plain loops. */
interface Tree {
	root: Container;
	nested: Container;
	markdowns: Markdown[];
	texts: string[];
	fresh: Fresh;
}

function makeTree(): Tree {
	const root = new Container();
	const nested = new Container();
	const markdowns = [new Markdown("", 1, 0, defaultMarkdownTheme), new Markdown("", 0, 1, defaultMarkdownTheme)];
	const fresh = new Fresh("fresh line\nsecond");
	root.addChild(new Text("header", 1, 0));
	root.addChild(nested);
	nested.addChild(new Spacer(1));
	nested.addChild(markdowns[0]!);
	nested.addChild(fresh);
	root.addChild(new MouseRegion(markdowns[1]!, () => undefined));
	root.addChild(new Text("after", 0, 0));
	return { root, nested, markdowns, texts: ["", ""], fresh };
}

/** The tree's lines rendered from nothing: no caches, every container's children joined by a loop. */
function reference(component: Component, width: number): string[] {
	if (component instanceof Container) return component.children.flatMap((child) => reference(child, width));
	return component.render(width);
}

const PIECES = [
	"Text with **bold** and `code`.\n",
	"- item\n",
	"  - nested\n",
	"\n",
	"```ts\nconst a = 1;\n```\n",
	"```\nopen code\n",
	"| a | b |\n|---|---|\n| 1 | 2 |\n",
	"> quote\n",
	"# Heading\n",
	"A long line of text that has to be wrapped at the narrow widths this test renders at, and more.\n",
	"\tindented with a tab\n",
	"[ref]: https://example.com\n",
	"",
];

type Step = (tree: Tree, draw: (count: number) => number) => void;

const STEPS: Step[] = [
	// Text added to a Markdown (as a reply streams in), most of the time.
	(tree, draw) => {
		const index = draw(2);
		tree.texts[index] += PIECES[draw(PIECES.length)]!;
		tree.markdowns[index]!.setText(tree.texts[index]!);
	},
	(tree, draw) => {
		const index = draw(2);
		tree.texts[index] += PIECES[draw(PIECES.length)]!.slice(0, 1 + draw(8));
		tree.markdowns[index]!.setText(tree.texts[index]!);
	},
	(tree, draw) => {
		const index = draw(2);
		tree.texts[index] += PIECES[draw(PIECES.length)]!;
		tree.markdowns[index]!.setText(tree.texts[index]!);
	},
	// Text that is not the text before with some added.
	(tree, draw) => {
		const index = draw(2);
		tree.texts[index] = tree.texts[index]!.slice(0, draw(tree.texts[index]!.length + 1));
		tree.markdowns[index]!.setText(tree.texts[index]!);
	},
	// The same lines in a new array, or other lines.
	(tree, draw) => {
		tree.fresh.text = draw(2) === 0 ? tree.fresh.text : `changed ${draw(100)}\nline`;
	},
	// A new spacer in place of the one before, as a message rebuilds its content.
	(tree) => {
		tree.nested.children[0] = new Spacer(1);
	},
	(tree, draw) => {
		if (draw(2) === 0 && tree.nested.children.length > 3) tree.nested.children.pop();
		else tree.nested.addChild(new Text(`added ${draw(10)}`, 0, 0));
	},
	(tree) => tree.root.invalidate(),
	() => {},
];

const WIDTHS = [40, 40, 40, 40, 24, 80];

describe("live lines", () => {
	it("say from which line they changed", () => {
		const live = new LiveLineArray();
		live.lines.push("a", "b", "c");
		assert.strictEqual(live.changedSince(live.revision), Number.POSITIVE_INFINITY);
		const before = live.revision;
		live.edit(2);
		live.lines.push("C", "D");
		live.edit(3);
		assert.strictEqual(live.changedSince(before), 2);
		assert.strictEqual(live.changedSince(before + 1), 3);
		assert.strictEqual(live.changedSince(before - 1), 0);
		assert.strictEqual(live.changedSince(live.revision + 1), 0);
		for (let i = 0; i < 40; i++) live.edit(4);
		assert.strictEqual(live.changedSince(before), 0, "a revision older than the changes kept");
	});

	it("change from the lines of the block that grew, at every level", () => {
		// A long reply, then a chunk added to its last paragraph: each container's lines change from where the reply's
		// last block starts, not from its first line, so a chunk costs the same whatever the length of the reply.
		const markdown = new Markdown("", 1, 0, defaultMarkdownTheme);
		const message = new Container();
		message.addChild(new Spacer(1));
		message.addChild(new MouseRegion(markdown, () => undefined));
		const root = new Container();
		root.addChild(new Text("question", 1, 0));
		root.addChild(message);
		let text = "";
		for (let i = 0; i < 100; i++) text += `Paragraph ${i} with **some** text.\n\n`;
		for (const chunk of ["The last ", "paragraph grows ", "chunk by chunk."]) {
			text += chunk;
			markdown.setText(text);
			const before = renderLiveLines(root, 60)!;
			const revision = before.revision;
			const length = before.lines.length;
			markdown.setText(`${text}!`);
			const after = renderLiveLines(root, 60)!;
			assert.strictEqual(after, before);
			assert.ok(
				after.changedSince(revision) >= length - 2,
				`changed from ${after.changedSince(revision)} of ${length}`,
			);
			markdown.setText(text);
			renderLiveLines(root, 60);
		}
	});

	for (let seed = 1; seed <= 24; seed++) {
		it(`follow every change, and the arrays handed out stay as they were (seed ${seed})`, () => {
			const next = random(seed * 7919);
			const live = makeTree();
			const expected = makeTree();
			const follower = new Follower();
			const handedOut: Array<{ lines: string[]; copy: string[] }> = [];
			let shared: { lines: string[]; copy: string[] } | undefined;
			const log: string[] = [];
			for (let step = 0; step < 300; step++) {
				const stepIndex = Math.floor(next() * STEPS.length);
				const draws: number[] = [];
				STEPS[stepIndex]!(live, (count) => {
					const value = Math.floor(next() * count);
					draws.push(value);
					return value;
				});
				let replayed = 0;
				STEPS[stepIndex]!(expected, () => draws[replayed++]!);
				log.push(`${stepIndex}(${draws.join(",")})`);
				const label = `seed ${seed}: ${log.slice(-10).join(" ")}`;
				const width = WIDTHS[Math.floor(next() * WIDTHS.length)]!;

				setMarkdownCachingForTest(false);
				const want = reference(expected.root, width);
				setMarkdownCachingForTest(true);

				// One way of reading the tree per step (a Markdown rendered twice for the same text renders as render()
				// does when called twice, which the reference is not).
				const way = Math.floor(next() * 3);
				if (way === 0) {
					assert.deepStrictEqual(follower.read(renderLiveLines(live.root, width)!), want, label);
				} else if (way === 1) {
					const lines = live.root.render(width);
					assert.deepStrictEqual(lines, want, label);
					handedOut.push({ lines, copy: lines.slice() });
					lines.push("changed by the caller");
				} else {
					const lines = live.root.renderShared(width);
					assert.deepStrictEqual(lines, want, label);
					if (shared !== undefined && shared.lines === lines) {
						assert.deepStrictEqual(shared.copy, want, `${label}: the same array for other lines`);
					}
					shared = { lines, copy: lines.slice() };
					handedOut.push(shared);
				}
				for (const { lines, copy } of handedOut.slice(-20)) {
					if (lines.length === copy.length + 1) continue; // (changed by the caller above)
					assert.deepStrictEqual(lines, copy, `${label}: an array handed out was changed`);
				}
			}
		});
	}
});

describe("in-place transcript updates", () => {
	it("reports a single revision for fixed-height edits combined with growth, shrinkage and append", () => {
		const container = new Container();
		const head = ["a", "b"];
		const middle = ["c"];
		const tail = ["d", "e"];
		for (const lines of [head, middle, tail]) container.addChild({ render: () => lines, invalidate() {} });
		const live = renderLiveLines(container, 40)!;
		const snapshot = container.renderShared(40);
		const follower = new Follower();
		follower.read(live);
		const check = (want: string[]) => {
			const revision = live.revision;
			assert.strictEqual(renderLiveLines(container, 40), live);
			assert.strictEqual(live.revision, revision + 1);
			assert.deepStrictEqual(follower.read(live), want);
			assert.deepStrictEqual(live.lines, want);
		};
		head[1] = "B";
		middle.push("C");
		tail[1] = "E";
		container.addChild(new Spacer(1));
		check(["a", "B", "c", "C", "d", "E", ""]);
		head[0] = "A";
		middle.length = 0;
		check(["A", "B", "d", "E", ""]);
		tail[0] = "D";
		check(["A", "B", "D", "E", ""]);
		assert.deepStrictEqual(snapshot, ["a", "b", "c", "d", "e"]);
	});
	it("restores a shared live row at the container width when a later sibling renders it elsewhere", () => {
		let value = "first";
		class SharedRow {
			readonly output = new LiveLineArray();
			render(width: number): string[] {
				this.output.edit(0);
				this.output.lines.push(value);
				if (width === 40) this.output.lines.push("wide");
				return this.output.lines;
			}
			invalidate() {}
		}
		markLiveRender(SharedRow.prototype.render, function (width) {
			assert.ok(this instanceof SharedRow);
			this.render(width);
			return this.output;
		});
		const row = new SharedRow();
		const root = new Container();
		root.addChild(row);
		root.addChild({
			render() {
				renderLiveLines(row, 20);
				return ["sibling"];
			},
			invalidate() {},
		});
		const frame = renderLiveLines(root, 40)!;
		assert.deepStrictEqual(frame.lines, ["first", "wide", "sibling"]);
		value = "second";
		assert.deepStrictEqual(renderLiveLines(root, 40)!.lines, ["second", "wide", "sibling"]);
	});
});
