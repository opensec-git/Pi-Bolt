import assert from "node:assert";
import { describe, it } from "node:test";
import { ScrollView } from "../src/components/scroll-view.ts";
import type { StackChild, StackOptions } from "../src/components/stack.ts";
import { Text } from "../src/components/text.ts";
import { VStack } from "../src/components/v-stack.ts";
import { renderLayoutFrame } from "../src/layout.ts";
import { type Component, Container } from "../src/tui.ts";

// The layout measures a VStack (Pi's input dock) from its children's heights instead of rendering it, and then renders
// each child once to lay it out. The height must be what VStack.render() returns, and every frame must be what it is
// when the stack is measured by rendering it, as it was before.

/** A VStack measured as before: the layout renders it, because it does not render as VStack does. */
class RenderedVStack extends VStack {
	override render(width: number): string[] {
		return super.render(width);
	}
}

/** A leaf that counts its renders and draws `lines` lines (fewer at a greater width, when `byWidth`). */
class Counted implements Component {
	renders = 0;
	private readonly label: string;
	private readonly lines: number;
	private readonly byWidth: boolean;

	constructor(label: string, lines: number, byWidth: boolean) {
		this.label = label;
		this.lines = lines;
		this.byWidth = byWidth;
	}

	render(width: number): string[] {
		this.renders++;
		const count = this.byWidth ? Math.max(0, this.lines - Math.floor(width / 10)) : this.lines;
		return Array.from({ length: count }, (_, index) => `${this.label}${index} w${width}`.slice(0, width));
	}

	invalidate(): void {}
}

function random(seed: number): () => number {
	let state = seed;
	return () => {
		state = (state * 1103515245 + 12345) % 2147483648;
		return state / 2147483648;
	};
}

type MakeStack = (children: StackChild[], options: StackOptions) => VStack;

/** A stack of pseudo-random entries, with nested stacks up to `depth`; the leaves made for it go to `leaves`. */
function randomStack(next: () => number, depth: number, make: MakeStack, leaves: Counted[]): VStack {
	const children: StackChild[] = [];
	const count = Math.floor(next() * 5);
	for (let index = 0; index < count; index++) {
		let component: Component;
		const kind = next();
		if (kind < 0.2 && depth > 0) component = randomStack(next, depth - 1, make, leaves);
		else if (kind < 0.35) {
			// A container of leaves, as the dock's are.
			const container = new Container();
			for (let child = Math.floor(next() * 3); child > 0; child--) {
				const leaf = new Counted(`c${leaves.length}-`, Math.floor(next() * 4), false);
				leaves.push(leaf);
				container.addChild(leaf);
			}
			component = container;
		} else if (kind < 0.45) component = new Text("wrapped text ".repeat(Math.floor(next() * 8)), 1, 0);
		else {
			const leaf = new Counted(`l${leaves.length}-`, Math.floor(next() * 6), next() < 0.3);
			leaves.push(leaf);
			component = leaf;
		}
		if (next() < 0.4) children.push(component);
		else {
			children.push({
				component,
				...(next() < 0.3 ? { basis: next() < 0.5 ? "auto" : Math.floor(next() * 4) } : {}),
				...(next() < 0.3 ? { minSize: Math.floor(next() * 3) } : {}),
				...(next() < 0.3 ? { maxSize: Math.floor(next() * 5) } : {}),
				...(next() < 0.2 ? { grow: 1 } : {}),
				...(next() < 0.3 ? { shrink: Math.floor(next() * 2) } : {}),
				...(next() < 0.2 ? { visible: (viewport) => viewport.width > 30 } : {}),
				...(next() < 0.1 ? { visible: (viewport) => viewport.height < 100 } : {}),
			});
		}
	}
	return make(children, { gap: next() < 0.3 ? Math.floor(next() * 3) : 0 });
}

/** The fullscreen layout: a transcript over the stack, which takes the rows it needs. */
function screen(dock: VStack): VStack {
	return new VStack([
		{ component: new ScrollView(new Text("transcript\n".repeat(40), 0, 0), { follow: "end" }), basis: 0, grow: 1 },
		{ component: dock, basis: "auto", grow: 0, shrink: 1, minSize: 1 },
	]);
}

describe("VStack measurement in the layout", () => {
	it("checks visibility before measuring and only measures visible children with auto bases", () => {
		const calls: string[] = [];
		const auto = new Counted("auto", 2, false);
		const fixed = new Counted("fixed", 5, false);
		const hidden = new Counted("hidden", 1, false);
		const dock = new VStack(
			[
				{
					component: auto,
					minSize: 3,
					visible: () => {
						calls.push("auto visible");
						return true;
					},
				},
				{ component: fixed, basis: 7, maxSize: 2 },
				{
					component: hidden,
					visible: () => {
						calls.push("hidden invisible");
						return false;
					},
				},
			],
			{ gap: 1 },
		);

		const height = dock.measureHeight(40, (child, width) => {
			assert.strictEqual(child, auto);
			assert.strictEqual(width, 40);
			calls.push("measure auto");
			return 2;
		});
		assert.strictEqual(height, 6);
		assert.deepStrictEqual(calls, ["auto visible", "hidden invisible", "measure auto"]);
	});

	it("draws what measuring the stack by rendering it draws, rendering each child once a frame", () => {
		for (let seed = 1; seed <= 400; seed++) {
			const leaves: Counted[] = [];
			const dock = randomStack(random(seed), 2, (children, options) => new VStack(children, options), leaves);
			const before = randomStack(random(seed), 2, (children, options) => new RenderedVStack(children, options), []);
			const root = screen(dock);
			const beforeRoot = screen(before);
			for (const width of [80, 31, 12, 1]) {
				const label = `seed ${seed}, width ${width}`;
				const rendered = dock.render(width);
				assert.strictEqual(
					dock.measureHeight(width, (child, childWidth) => child.render(childWidth).length),
					rendered.length,
					label,
				);
				for (const rows of [1, 2, Math.floor(rendered.length / 2), rendered.length, rendered.length + 1, 60]) {
					for (const leaf of leaves) leaf.renders = 0;
					const frame = renderLayoutFrame(root, width, Math.max(1, rows), () => {});
					for (const leaf of leaves)
						assert.ok(leaf.renders <= 1, `${label}, ${rows} rows: ${leaf.renders} renders`);
					const expected = renderLayoutFrame(beforeRoot, width, Math.max(1, rows), () => {});
					assert.deepStrictEqual(frame.lines, expected.lines, `${label}, ${rows} rows`);
					assert.deepStrictEqual(
						frame.root.children.map((box) => box.rect),
						expected.root.children.map((box) => box.rect),
						`${label}, ${rows} rows`,
					);
				}
			}
		}
	});
});
