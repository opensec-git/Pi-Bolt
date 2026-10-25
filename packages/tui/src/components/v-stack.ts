import type { LayoutViewport } from "../layout-node.ts";
import type { Component } from "../tui.ts";
import { allocateStackSizes, Stack, type StackChild, type StackOptions, visibleStackEntries } from "./stack.ts";

export class VStack extends Stack {
	protected readonly layoutType = "vstack" as const;

	constructor(children: StackChild[] = [], options: StackOptions = {}) {
		super(children, options);
	}

	override render(width: number): string[] {
		const viewport = { width: Math.max(1, width), height: Number.MAX_SAFE_INTEGER };
		const entries = visibleStackEntries(this.entries, viewport);
		const rendered = entries.map((entry) => entry.component.render(viewport.width));
		const sizes = allocateStackSizes(
			entries,
			rendered.map((lines) => lines.length),
			undefined,
			this.gap,
		);
		const lines: string[] = [];
		for (let index = 0; index < entries.length; index++) {
			if (index > 0) {
				for (let gap = 0; gap < this.gap; gap++) lines.push("");
			}
			const childLines = rendered[index]!.slice(0, sizes[index]);
			lines.push(...childLines);
			for (let padding = childLines.length; padding < sizes[index]!; padding++) lines.push("");
		}
		return lines;
	}

	/**
	 * How many lines render(width) returns, from the height `heightOf` gives each child at a width instead of from its
	 * lines. The layout measures a stack with this: it renders the children once a frame and keeps their lines, which
	 * render() would render a second time. (A child with a fixed basis is not measured: its basis is its height.)
	 */
	measureHeight(width: number, heightOf: (component: Component, width: number) => number): number {
		const viewport: LayoutViewport = { width: Math.max(1, width), height: Number.MAX_SAFE_INTEGER };
		const entries = visibleStackEntries(this.entries, viewport);
		// (Loops rather than map() with a closure and for-of: the layout measures the input dock on every frame.)
		const heights: number[] = new Array(entries.length);
		for (let index = 0; index < entries.length; index++) {
			const entry = entries[index]!;
			heights[index] = typeof entry.basis === "number" ? 0 : heightOf(entry.component, viewport.width);
		}
		const sizes = allocateStackSizes(entries, heights, undefined, this.gap);
		let height = Math.max(0, entries.length - 1) * this.gap;
		for (let index = 0; index < sizes.length; index++) height += sizes[index]!;
		return height;
	}
}

export type { StackChild, StackEntry, StackEntryOptions, StackOptions } from "./stack.ts";
