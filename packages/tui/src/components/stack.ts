import { LAYOUT_NODE, type LayoutViewport, type StackLayoutEntry, type StackLayoutNode } from "../layout-node.ts";
import { type Component, Container } from "../tui.ts";

export interface StackEntryOptions {
	basis?: number | "auto";
	grow?: number;
	shrink?: number;
	minSize?: number;
	maxSize?: number;
	visible?: (viewport: LayoutViewport) => boolean;
}

export interface StackEntry extends StackEntryOptions {
	component: Component;
}

export type StackChild = Component | StackEntry;

export interface StackOptions {
	gap?: number;
	align?: "stretch" | "start" | "center" | "end";
}

function isStackEntry(child: StackChild): child is StackEntry {
	return !("render" in child);
}

function normalizeSize(value: number | undefined, fallback: number): number {
	return value === undefined || !Number.isFinite(value) ? fallback : Math.max(0, Math.floor(value));
}

export abstract class Stack extends Container {
	protected readonly entries: StackLayoutEntry[] = [];
	protected readonly gap: number;
	protected readonly align: "stretch" | "start" | "center" | "end";
	protected abstract readonly layoutType: "vstack" | "hstack";

	constructor(children: StackChild[] = [], options: StackOptions = {}) {
		super();
		this.gap = normalizeSize(options.gap, 0);
		this.align = options.align ?? "stretch";
		for (const child of children) {
			if (isStackEntry(child)) this.addChild(child.component, child);
			else this.addChild(child);
		}
	}

	override addChild(component: Component, options: StackEntryOptions = {}): void {
		super.addChild(component);
		this.entries.push({
			component,
			...(options.basis === undefined ? {} : { basis: options.basis }),
			...(options.grow === undefined ? {} : { grow: normalizeSize(options.grow, 0) }),
			...(options.shrink === undefined ? {} : { shrink: normalizeSize(options.shrink, 1) }),
			...(options.minSize === undefined ? {} : { minSize: normalizeSize(options.minSize, 0) }),
			...(options.maxSize === undefined ? {} : { maxSize: normalizeSize(options.maxSize, Number.MAX_SAFE_INTEGER) }),
			...(options.visible === undefined ? {} : { visible: options.visible }),
		});
	}

	override removeChild(component: Component): void {
		super.removeChild(component);
		const index = this.entries.findIndex((entry) => entry.component === component);
		if (index !== -1) this.entries.splice(index, 1);
	}

	override clear(): void {
		super.clear();
		this.entries.length = 0;
	}

	[LAYOUT_NODE](): StackLayoutNode {
		return {
			type: this.layoutType,
			entries: this.entries,
			gap: this.gap,
			align: this.align,
		};
	}
}

export function visibleStackEntries(
	entries: readonly StackLayoutEntry[],
	viewport: LayoutViewport,
): StackLayoutEntry[] {
	// (A loop rather than filter() with a closure: every frame lays out every stack.)
	const visible: StackLayoutEntry[] = [];
	for (let index = 0; index < entries.length; index++) {
		const entry = entries[index]!;
		if (entry.visible?.(viewport) ?? true) visible.push(entry);
	}
	return visible;
}

function clampSize(size: number, entry: StackLayoutEntry): number {
	const min = Math.max(0, Math.floor(entry.minSize ?? 0));
	const max = Math.max(min, Math.floor(entry.maxSize ?? Number.MAX_SAFE_INTEGER));
	return Math.max(min, Math.min(max, Math.max(0, Math.floor(size))));
}

function distribute(
	sizes: number[],
	entries: readonly StackLayoutEntry[],
	amount: number,
	mode: "grow" | "shrink",
): void {
	let remaining = amount;
	// (Loops over the indexes of the candidates rather than map(), filter() and reduce() with closures: every frame lays out
	// every stack.)
	const candidates: number[] = [];
	while (remaining > 0) {
		candidates.length = 0;
		for (let index = 0; index < entries.length; index++) {
			const entry = entries[index]!;
			const candidate =
				mode === "grow"
					? (entry.grow ?? 0) > 0 && sizes[index]! < (entry.maxSize ?? Number.MAX_SAFE_INTEGER)
					: (entry.shrink ?? 1) > 0 && sizes[index]! > (entry.minSize ?? 0);
			if (candidate) candidates.push(index);
		}
		if (candidates.length === 0) return;

		let totalWeight = 0;
		for (let i = 0; i < candidates.length; i++) {
			const index = candidates[i]!;
			const entry = entries[index]!;
			totalWeight += mode === "grow" ? (entry.grow ?? 0) : (entry.shrink ?? 1) * Math.max(1, sizes[index]!);
		}
		let distributed = 0;
		for (let i = 0; i < candidates.length; i++) {
			const index = candidates[i]!;
			const entry = entries[index]!;
			if (remaining <= 0) break;
			const weight = mode === "grow" ? (entry.grow ?? 0) : (entry.shrink ?? 1) * Math.max(1, sizes[index]!);
			const proposed = Math.max(1, Math.floor((remaining * weight) / totalWeight));
			const capacity =
				mode === "grow"
					? (entry.maxSize ?? Number.MAX_SAFE_INTEGER) - sizes[index]!
					: sizes[index]! - (entry.minSize ?? 0);
			const delta = Math.min(remaining, proposed, capacity);
			if (delta <= 0) continue;
			sizes[index] = sizes[index]! + (mode === "grow" ? delta : -delta);
			remaining -= delta;
			distributed += delta;
		}
		if (distributed === 0) return;
	}
}

export function allocateStackSizes(
	entries: readonly StackLayoutEntry[],
	intrinsicSizes: readonly number[],
	availableSize: number | undefined,
	gap: number,
): number[] {
	const sizes: number[] = new Array(entries.length);
	for (let index = 0; index < entries.length; index++) {
		const entry = entries[index]!;
		sizes[index] = clampSize(
			entry.basis === undefined || entry.basis === "auto" ? (intrinsicSizes[index] ?? 0) : entry.basis,
			entry,
		);
	}
	if (availableSize === undefined) return sizes;

	const contentSize = Math.max(0, Math.floor(availableSize) - Math.max(0, entries.length - 1) * gap);
	let total = 0;
	for (let index = 0; index < sizes.length; index++) total += sizes[index]!;
	if (total < contentSize) distribute(sizes, entries, contentSize - total, "grow");
	else if (total > contentSize) distribute(sizes, entries, total - contentSize, "shrink");
	return sizes;
}
