import assert from "node:assert";
import { describe, it } from "node:test";
import { Box } from "../src/components/box.ts";
import { MouseRegion } from "../src/components/mouse-region.ts";
import { Spacer } from "../src/components/spacer.ts";
import { Text } from "../src/components/text.ts";
import {
	type Component,
	Container,
	isStableRender,
	markStableRender,
	renderSharedLines,
	type TuiMouseEvent,
	type TuiMouseEventResult,
} from "../src/tui.ts";

/** A component that returns new arrays (not declared stable). */
class Lines implements Component {
	lines: string[];
	constructor(...lines: string[]) {
		this.lines = lines;
	}
	render(): string[] {
		return [...this.lines];
	}
	invalidate(): void {}
}

/** An extension-style component that keeps one array and modifies it in place. */
class InPlace implements Component {
	private readonly lines: string[] = [];
	text = "a";
	count = 1;
	render(): string[] {
		this.lines.length = 0;
		for (let i = 0; i < this.count; i++) this.lines.push(`${this.text}${i}`);
		return this.lines;
	}
	invalidate(): void {}
}

/** Declared stable, and keeps that promise: a new array whenever its lines change. */
class Stable implements Component {
	private lines: string[];
	constructor(...lines: string[]) {
		this.lines = lines;
	}
	set(...lines: string[]): void {
		this.lines = lines;
	}
	render(): string[] {
		return this.lines;
	}
	invalidate(): void {}
}
markStableRender(Stable);

class Clickable implements Component {
	clicks: number[] = [];
	private readonly lines: string[];
	constructor(...lines: string[]) {
		this.lines = lines;
	}
	render(): string[] {
		return this.lines;
	}
	handleMouse(event: TuiMouseEvent): TuiMouseEventResult {
		this.clicks.push(event.y);
		return { handled: true };
	}
	invalidate(): void {}
}

function mouse(y: number, width = 20, height = 100): TuiMouseEvent {
	return {
		type: "click",
		button: "left",
		x: 0,
		y,
		screenX: 0,
		screenY: y,
		width,
		height,
		shift: false,
		alt: false,
		ctrl: false,
	};
}

/** What a container of these children renders to, from nothing. */
function fresh(children: Component[], width = 20): readonly string[] {
	const container = new Container();
	for (const child of children) container.addChild(child);
	return container.render(width);
}

describe("Container keeps its lines", () => {
	it("renders the same lines twice, sharing them only through renderShared()", () => {
		const container = new Container();
		container.addChild(new Text("hello", 0, 0));
		container.addChild(new Spacer(1));
		container.addChild(new Lines("x", "y"));
		const first = container.renderShared(20);
		const second = container.renderShared(20);
		assert.strictEqual(second, first, "unchanged children: the same array");
		const copy = container.render(20);
		assert.notStrictEqual(copy, first, "render() returns a copy");
		assert.deepStrictEqual(copy, first);
		copy.push("modified by a caller");
		assert.deepStrictEqual(container.render(20), first, "a caller modifying render()'s array changes nothing");
		assert.ok(isStableRender(container));
		assert.strictEqual(renderSharedLines(container, 20), first);
	});

	it("adds and removes children at the start, middle and end", () => {
		const a = new Text("a", 0, 0);
		const b = new Lines("b1", "b2");
		const c = new Stable("c");
		const d = new Spacer(2);
		const container = new Container();
		for (const child of [a, b, c]) container.addChild(child);
		container.renderShared(20);

		container.children.splice(1, 0, d);
		assert.deepStrictEqual(container.render(20), fresh([a, d, b, c]));
		container.addChild(new Text("end", 0, 0));
		assert.deepStrictEqual(container.render(20), fresh([a, d, b, c, new Text("end", 0, 0)]));
		container.removeChild(d);
		assert.deepStrictEqual(container.render(20), fresh([a, b, c, new Text("end", 0, 0)]));
		container.children.pop();
		assert.deepStrictEqual(container.render(20), fresh([a, b, c]));
		container.children.shift();
		assert.deepStrictEqual(container.render(20), fresh([b, c]));
		container.children = [c, b];
		assert.deepStrictEqual(container.render(20), fresh([c, b]));
		container.clear();
		assert.deepStrictEqual(container.render(20), []);
	});

	it("sees a changed child in the middle and keeps the lines before it", () => {
		const before = new Text("before", 0, 0);
		const middle = new Stable("m1");
		const after = new Lines("after");
		const container = new Container();
		for (const child of [before, middle, after]) container.addChild(child);
		const first = container.renderShared(20);
		middle.set("m1", "m2");
		const second = container.renderShared(20);
		assert.notStrictEqual(second, first);
		assert.deepStrictEqual(second, fresh([before, middle, after]));
		assert.strictEqual(second[0], first[0]);
	});

	it("renders an extension component that modifies its array in place", () => {
		const component = new InPlace();
		const container = new Container();
		container.addChild(new Text("top", 0, 0));
		container.addChild(component);
		container.addChild(new Text("bottom", 0, 0));
		const outer = new Container();
		outer.addChild(container);
		assert.deepStrictEqual(outer.render(20), ["top".padEnd(20), "a0", "bottom".padEnd(20)]);
		component.text = "b";
		assert.deepStrictEqual(outer.render(20)[1], "b0");
		component.count = 3;
		assert.deepStrictEqual(outer.render(20).slice(1, 4), ["b0", "b1", "b2"]);
		component.count = 0;
		assert.strictEqual(outer.render(20).length, 2);
		assert.ok(!isStableRender(component));
	});

	it("does not trust a subclass of a stable component that overrides render()", () => {
		class Mutating extends Text {
			private readonly kept: string[] = [];
			label = "one";
			override render(): string[] {
				this.kept.length = 0;
				this.kept.push(this.label);
				return this.kept;
			}
		}
		const component = new Mutating();
		assert.ok(isStableRender(new Text("x")));
		assert.ok(!isStableRender(component));
		const container = new Container();
		container.addChild(component);
		assert.deepStrictEqual(container.render(10), ["one"]);
		component.label = "two";
		assert.deepStrictEqual(container.render(10), ["two"]);
	});

	it("re-renders at a new width", () => {
		const container = new Container();
		container.addChild(new Text("one two three four", 0, 0));
		const wide = container.render(40);
		const narrow = container.render(8);
		assert.notDeepStrictEqual(narrow, wide);
		assert.deepStrictEqual(container.render(8), fresh([new Text("one two three four", 0, 0)], 8));
		assert.deepStrictEqual(container.render(40), wide);
	});

	it("hit-tests children at the heights last drawn, after children change", () => {
		const first = new Clickable("1a", "1b");
		const second = new Clickable("2a");
		const container = new Container();
		container.addChild(first);
		container.addChild(second);
		container.render(20);
		container.handleMouse(mouse(2));
		assert.deepStrictEqual(second.clicks, [0]);

		const inserted = new Clickable("ia", "ib", "ic");
		container.children.splice(1, 0, inserted);
		container.render(20);
		container.handleMouse(mouse(2));
		container.handleMouse(mouse(5));
		assert.deepStrictEqual(inserted.clicks, [0]);
		assert.deepStrictEqual(second.clicks, [0, 0]);

		container.removeChild(first);
		container.render(20);
		container.handleMouse(mouse(0));
		assert.deepStrictEqual(inserted.clicks, [0, 0]);
	});
});

describe("Box keeps its lines", () => {
	it("returns the same array while its children are unchanged, and pads changes", () => {
		const text = new Text("hello", 0, 0);
		const box = new Box(1, 1, (line) => `<${line}>`);
		box.addChild(text);
		box.addChild(new Lines("x"));
		const first = box.render(12);
		assert.strictEqual(box.render(12), first);
		text.setText("changed");
		const second = box.render(12);
		assert.notStrictEqual(second, first);
		const expected = new Box(1, 1, (line) => `<${line}>`);
		expected.addChild(new Text("changed", 0, 0));
		expected.addChild(new Lines("x"));
		assert.deepStrictEqual(second, expected.render(12));
		assert.ok(isStableRender(box));
	});

	it("follows an in-place change of an extension child and a new background", () => {
		const child = new InPlace();
		const box = new Box(0, 0);
		box.addChild(child);
		assert.deepStrictEqual(box.render(4), ["a0  "]);
		child.text = "z";
		assert.deepStrictEqual(box.render(4), ["z0  "]);
		box.setBgFn((line) => `[${line}]`);
		assert.deepStrictEqual(box.render(4), ["[z0  ]"]);
	});

	it("hit-tests children after they change", () => {
		const first = new Clickable("1");
		const second = new Clickable("2");
		const box = new Box(0, 0);
		box.addChild(first);
		box.addChild(second);
		box.render(10);
		box.handleMouse(mouse(1, 10));
		assert.deepStrictEqual(second.clicks, [0]);
		box.removeChild(first);
		box.render(10);
		box.handleMouse(mouse(0, 10));
		assert.deepStrictEqual(second.clicks, [0, 0]);
	});
});

describe("Spacer and MouseRegion keep their lines", () => {
	it("Spacer returns the same array until its line count changes", () => {
		const spacer = new Spacer(2);
		const lines = spacer.render(10);
		assert.deepStrictEqual(lines, ["", ""]);
		assert.strictEqual(spacer.render(30), lines);
		spacer.setLines(3);
		assert.deepStrictEqual(spacer.render(10), ["", "", ""]);
	});

	it("MouseRegion passes a stable child's lines through and copies an extension child's", () => {
		const text = new Text("hi", 0, 0);
		const region = new MouseRegion(text, () => undefined);
		assert.strictEqual(region.render(5), text.render(5));
		const child = new InPlace();
		const extensionRegion = new MouseRegion(child, () => undefined);
		const first = extensionRegion.render(5);
		assert.strictEqual(extensionRegion.render(5), first, "same lines: the same copy");
		child.text = "q";
		assert.deepStrictEqual(extensionRegion.render(5), ["q0"]);
		assert.deepStrictEqual(first, ["a0"], "the earlier copy is not modified");
	});
});
