import {
	type Component,
	Container,
	type LiveLineArray,
	renderLiveLines,
	renderSharedLines,
	Spacer,
} from "@earendil-works/pi-tui";

type RowFactory = () => Component;

interface MeasuredRow {
	create: RowFactory;
	height: number;
}

/** A run outside the viewport. It retains measured heights and the session projection, without any rendered text. */
class MeasuredRows extends Spacer {
	readonly rows: MeasuredRow[];
	readonly height: number;

	constructor(rows: MeasuredRow[], height: number) {
		super(height);
		this.rows = rows;
		this.height = height;
	}
}

const MAX_MEASURED_RUN = 128;

/**
 * Fullscreen mounts the visible history and a scroll margin. Unvisited rows stay in chronological order in the
 * prefix. Visited rows outside the window retain their factories and measured heights, so scrolling preserves
 * its position while releasing their components and formatted lines. Whole-document operations use completeNow().
 */
export class TranscriptContainer extends Container {
	private invalidateFilter?: (child: Component) => boolean;
	private prefix: (Component | RowFactory)[] = [];
	/** A materialized historical row can be released on follow-end until the user changes its local UI state. */
	private readonly factories = new WeakMap<Component, RowFactory>();
	private linesNeeded?: () => number;
	private following = true;
	private trim = true;
	private width?: number;
	private sampledRows = 0;
	private sampledHeight = 0;
	private expandedSinceLayout = false;
	private pinned = false;
	private measuredCount = 0;
	private measuredRuns = 0;
	private window?: { total: number; start: number; end: number };

	/** Estimated unvisited terminal lines for painting the scrollbar. Absolute navigation measures the full document. */
	get estimatedPrefixHeight(): number {
		return this.sampledRows
			? Math.round((this.prefix.length * this.sampledHeight) / this.sampledRows)
			: this.prefix.length;
	}

	get childCount(): number {
		return this.prefix.length + this.childView.length + this.measuredCount;
	}

	get residentChildren(): Iterable<Component> {
		return this.residents();
	}

	private *residents(): IterableIterator<Component> {
		for (const row of this.prefix) {
			if (typeof row !== "function") yield row;
		}
		for (const row of this.childView) if (!(row instanceof MeasuredRows)) yield row;
	}

	/** An identity check at the tail must not construct a historical component. Zero means the last entry. */
	childFromEnd(offset: number): Component | undefined {
		const mounted = this.childView;
		for (let i = mounted.length - 1; i >= 0; i--) {
			const row = mounted[i]!;
			const count = row instanceof MeasuredRows ? row.rows.length : 1;
			if (offset < count) return row instanceof MeasuredRows ? undefined : row;
			offset -= count;
		}
		const row = this.prefix[this.prefix.length - 1 - offset];
		return typeof row === "function" ? undefined : row;
	}

	hasChild(component: Component): boolean {
		return this.childView.includes(component) || this.prefix.includes(component);
	}

	insertBeforeChild(before: Component, component: Component): boolean {
		const index = this.childView.indexOf(before);
		if (index >= 0) {
			this.spliceChildren(index, 0, component);
			this.trim = true;
			this.window = undefined;
			return true;
		}
		const hidden = this.prefix.indexOf(before);
		if (hidden < 0) return false;
		this.prefix.splice(hidden, 0, component);
		return true;
	}

	/** Only the mounted rows can benefit from compacting rendered output when idle. */
	get mountedChildren(): Component[] {
		return super.children;
	}

	get mountedRows(): readonly Component[] {
		return this.childView;
	}

	replaceMountedChild(index: number, replacement: Component): void {
		const factory = this.factories.get(this.childView[index]!);
		if (factory) this.factories.set(replacement, factory);
		this.replaceChildAt(index, replacement);
	}

	invalidateOnly(filter: (child: Component) => boolean, invalidate: () => void): void {
		this.invalidateFilter = filter;
		try {
			invalidate();
		} finally {
			this.invalidateFilter = undefined;
		}
	}

	override invalidate(): void {
		const filter = this.invalidateFilter;
		for (const row of this.prefix) {
			if (typeof row !== "function" && (!filter || filter(row))) row.invalidate();
		}
		if (!filter) super.invalidate();
		else for (const row of this.childView) if (filter(row)) row.invalidate();
	}

	/** The public, mutable child list always represents the whole document. */
	override get children(): Component[] {
		this.completeNow();
		return super.children;
	}

	override set children(children: Component[]) {
		this.prefix = [];
		this.measuredCount = 0;
		this.measuredRuns = 0;
		this.window = undefined;
		super.children = children;
		this.trim = true;
	}

	override addChild(component: Component): void {
		super.addChild(component);
		this.window = undefined;
		this.trim = true;
	}

	addDeferredChild(create: RowFactory): void {
		if (this.pinned) {
			this.addChild(this.materialize(create));
			return;
		}
		// The existing suffix precedes this new row. During restore this usually just moves interleaved eager rows.
		const children = this.childView;
		for (const row of children) this.hold(row);
		if (children.length) super.clear();
		this.measuredCount = 0;
		this.measuredRuns = 0;
		this.window = undefined;
		this.prefix.push(create);
		this.trim = true;
	}

	override removeChild(component: Component): void {
		this.window = undefined;
		const index = this.prefix.indexOf(component);
		if (index >= 0) this.prefix.splice(index, 1);
		else super.removeChild(component);
	}

	override clear(): void {
		this.sampledRows = 0;
		this.sampledHeight = 0;
		this.pinned = false;
		this.window = undefined;
		this.measuredCount = 0;
		this.measuredRuns = 0;
		this.prefix = [];
		this.following = true;
		this.trim = true;
		this.expandedSinceLayout = false;
		super.clear();
	}

	get isHoldingBack(): boolean {
		return this.prefix.length > 0 || this.measuredRuns > 0;
	}

	renderEndFirst(linesNeeded: () => number): void {
		this.linesNeeded = linesNeeded;
		this.followEnd();
	}

	renderWholeDocument(): void {
		this.linesNeeded = undefined;
		this.completeNow();
	}

	/** Resume bounded tail rendering once there are no outstanding selection or search coordinates. */
	followEnd(): void {
		if (this.measuredRuns) {
			for (const row of this.childView) this.hold(row);
			super.clear();
			this.measuredCount = 0;
			this.measuredRuns = 0;
		}
		this.window = undefined;
		this.pinned = false;
		this.following = true;
		this.trim = true;
		// Mark the subtree dirty even if no component's contents changed.
		this.noteChildrenUsed();
	}

	/** Move a measured window to the requested interval. Return the shift of the viewport's old coordinates. */
	ensureRowsFromEnd(rows: number, viewportHeight = this.linesNeeded?.() ?? 0): number | undefined {
		if (this.pinned || this.width === undefined || !this.linesNeeded) return undefined;
		this.following = false;
		const width = this.width;
		const before = super.renderLiveChildren(width).lines.length;
		const top = before - rows;
		const bottom = top + viewportHeight;
		const previous = this.window;
		if (previous?.total === before && top >= previous.start && bottom <= previous.end) return undefined;

		const margin = Math.max(1, Math.min(this.linesNeeded(), viewportHeight));
		const prepended = top < margin ? this.prepend(width, margin - top) : 0;
		const total = before + prepended;
		const start = Math.max(0, top + prepended - margin);
		const end = Math.min(total, bottom + prepended + margin);
		const mounted = this.childView;
		const next: Component[] = [];
		let measured: MeasuredRow[] = [];
		let measuredHeight = 0;
		let shifted = prepended;
		let extraEntries = 0;
		let runs = 0;
		let changed = prepended > 0;
		const flush = () => {
			if (!measured.length) return;
			next.push(new MeasuredRows(measured, measuredHeight));
			extraEntries += measured.length - 1;
			runs++;
			measured = [];
			measuredHeight = 0;
		};
		const hold = (row: MeasuredRow) => {
			measured.push(row);
			measuredHeight += row.height;
			if (measured.length === MAX_MEASURED_RUN) flush();
		};
		let y = 0;
		for (let index = 0; index < mounted.length; index++) {
			const child = mounted[index]!;
			const height =
				child instanceof MeasuredRows
					? child.height
					: (this.renderedChildHeight(child, index, width) ?? renderEntry(child, width));
			if (child instanceof MeasuredRows) {
				if (y + height <= start || y >= end) {
					// Reuse intact runs once they reach a page, so deeper scrolling only copies a bounded fringe.
					if (child.rows.length === MAX_MEASURED_RUN) {
						flush();
						next.push(child);
						extraEntries += child.rows.length - 1;
						runs++;
					} else for (const row of child.rows) hold(row);
				} else {
					let rowTop = y;
					for (const row of child.rows) {
						if (
							(rowTop + row.height > start && rowTop < end) ||
							(row.height === 0 && rowTop >= start && rowTop < end)
						) {
							flush();
							const active = this.materialize(row.create);
							next.push(active);
							const newHeight = renderEntry(active, width);
							if (rowTop + row.height <= top + prepended) shifted += newHeight - row.height;
							changed = true;
						} else hold(row);
						rowTop += row.height;
					}
				}
			} else {
				const create = this.factories.get(child);
				if (create && (y + height <= start || y >= end)) {
					hold({ create, height });
					changed = true;
				} else {
					flush();
					next.push(child);
				}
			}
			y += height;
		}
		flush();
		this.window = { total, start, end };
		if (!changed) return undefined;
		this.measuredCount = extraEntries;
		this.measuredRuns = runs;
		this.adoptChildren(next);
		return shifted;
	}

	/** Pin the complete transcript until followed again. A true return requires a new layout before using its height. */
	completeNow(): boolean {
		this.following = false;
		this.pinned = true;
		if (this.isHoldingBack) {
			const rows = this.prefix.map((row) => this.materialize(row));
			for (const child of this.childView) {
				if (child instanceof MeasuredRows) {
					for (const row of child.rows) rows.push(this.materialize(row.create));
				} else rows.push(child);
			}
			this.adoptChildren(rows);
			this.prefix = [];
			this.window = undefined;
			this.measuredCount = 0;
			this.measuredRuns = 0;
			this.expandedSinceLayout = true;
		}
		return this.expandedSinceLayout;
	}

	protected override onChildMouseHandled(child: Component): void {
		// E.g. a click expanded one historical tool independently of the global expansion setting.
		this.factories.delete(child);
	}

	protected override renderLiveChildren(width: number): LiveLineArray {
		const widthChanged = width !== this.width;
		if (widthChanged) {
			this.sampledRows = 0;
			this.sampledHeight = 0;
		}
		this.width = width;
		this.expandedSinceLayout = false;
		if (!this.linesNeeded) {
			if (this.prefix.length) this.completeNow();
			this.expandedSinceLayout = false;
			return super.renderLiveChildren(width);
		}
		const needed = this.linesNeeded();
		if (this.following && (this.trim || widthChanged)) {
			this.trim = false;
			const mounted = this.childView;
			let first = mounted.length;
			let height = 0;
			while (first > 0 && height < needed) height += renderEntry(mounted[--first]!, width);
			if (!this.sampledRows && mounted.length > first) {
				this.sampledRows = mounted.length - first;
				this.sampledHeight = height;
			}
			if (first > 0) {
				for (let i = 0; i < first; i++) {
					const row = mounted[i]!;
					this.hold(row);
				}
				this.spliceChildren(0, first);
			}
			if (height < needed) this.prepend(width, needed - height);
		}
		const live = super.renderLiveChildren(width);
		// A setting, terminal height, or shrinking live message may expose older entries without a scroll event.
		if (this.following && this.prefix.length && live.lines.length < needed) {
			this.prepend(width, needed - live.lines.length);
			return super.renderLiveChildren(width);
		}
		return live;
	}

	private prepend(width: number, needed: number): number {
		let height = 0;
		const rows: Component[] = [];
		while (this.prefix.length && height < needed) {
			const row = this.materialize(this.prefix.pop()!);
			rows.push(row);
			height += renderEntry(row, width);
		}
		if (rows.length) {
			this.sampledRows += rows.length;
			this.sampledHeight += height;
			this.adoptChildren(rows.reverse().concat(this.childView));
		}
		return height;
	}

	private hold(row: Component): void {
		if (row instanceof MeasuredRows) {
			for (const entry of row.rows) this.prefix.push(entry.create);
		} else this.prefix.push(this.factories.get(row) ?? row);
	}

	private materialize(row: Component | RowFactory): Component {
		if (typeof row !== "function") return row;
		const component = row();
		this.factories.set(component, row);
		return component;
	}
}

function renderEntry(entry: Component, width: number): number {
	return (renderLiveLines(entry, width)?.lines ?? renderSharedLines(entry, width)).length;
}
