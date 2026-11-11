import {
	type Component,
	Container,
	type LiveLineArray,
	renderLiveLines,
	renderSharedLines,
} from "@earendil-works/pi-tui";

/** How long one backfill slice renders before it yields to input and timers. */
const BACKFILL_SLICE_MS = 4;

/**
 * The transcript. One invalidation of everything can leave out the entries that do not need it (invalidateOnly()):
 * in a long session they are most of the screen, and each one invalidated is rendered again from nothing.
 *
 * A resumed session can be drawn from its end first (renderEndFirst()): the next render renders entries from the last one
 * up until they make `linesNeeded()` lines, and holds back the entries above them, which that frame cannot show (the
 * viewport follows the end and its lines are the last lines of the transcript, whose entries render independently of each
 * other). backfill() then renders the held entries in slices that yield to input, and puts them back when all are rendered.
 * Anything that reads the transcript in whole puts them back at once (completeNow(), or any use of `children`), as does a
 * render at another width or one whose lines fall short of `linesNeeded()`.
 */
export class TranscriptContainer extends Container {
	private invalidateFilter?: (child: Component) => boolean;
	/** The entries held back, in order: they come before the children. */
	private held: Component[] = [];
	/** The width the held entries are rendered at by backfill(). */
	private heldWidth = 0;
	/** renderEndFirst()'s lines: the next render decides what to hold back. */
	private endFirst: (() => number) | undefined;
	/** The lines a render must have while entries are held back. */
	private linesNeeded: () => number = () => 0;
	/** The held entry backfill() renders next (from the last one up); -1 when all are rendered. */
	private nextBackfilled = -1;
	private backfillTimer: ReturnType<typeof setImmediate> | undefined;
	private onBackfilled: (() => void) | undefined;
	/** Held entries were put back outside a render: the transcript was not laid out since. */
	private putBackUnrendered = false;

	/** Runs `invalidate`, during which this invalidates only the entries `filter` accepts. */
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
		let heldInvalidated = false;
		for (const child of this.held) {
			if (filter === undefined || filter(child)) {
				child.invalidate();
				heldInvalidated = true;
			}
		}
		// (What was rendered of the held entries is gone: backfill() renders them again.)
		if (heldInvalidated && this.backfillTimer !== undefined) this.nextBackfilled = this.held.length - 1;
		if (filter === undefined) {
			super.invalidate();
			return;
		}
		for (const child of super.children) {
			if (filter(child)) child.invalidate();
		}
	}

	/** All the entries, held ones included: reading them puts the held entries back. */
	override get children(): Component[] {
		this.completeNow();
		return super.children;
	}

	override set children(children: Component[]) {
		this.completeNow();
		super.children = children;
	}

	override removeChild(component: Component): void {
		if (this.held.includes(component)) this.completeNow();
		super.removeChild(component);
	}

	/** Removes every entry, held ones included; a backfill() under way ends, and its callback is called. */
	override clear(): void {
		const onBackfilled = this.onBackfilled;
		this.stopBackfill();
		this.held = [];
		this.endFirst = undefined;
		super.clear();
		onBackfilled?.();
	}

	/** Whether entries are held back (renderEndFirst()). */
	get isHoldingBack(): boolean {
		return this.held.length > 0;
	}

	/**
	 * Has the next render render the entries from the last one up until they make `linesNeeded()` lines and hold back the
	 * entries above them. `linesNeeded()` is asked again at every render while entries are held back.
	 */
	renderEndFirst(linesNeeded: () => number): void {
		this.endFirst = linesNeeded;
		this.linesNeeded = linesNeeded;
	}

	/**
	 * Renders the held entries in slices that yield to input, puts them back when all are rendered, and then calls
	 * `onBackfilled` (also when they are put back before that). False, and `onBackfilled` is not called, when no entry is
	 * held back.
	 */
	backfill(onBackfilled: () => void): boolean {
		this.endFirst = undefined;
		if (this.held.length === 0) return false;
		this.onBackfilled = onBackfilled;
		this.nextBackfilled = this.held.length - 1;
		this.backfillTimer ??= setImmediate(() => this.backfillSlice());
		return true;
	}

	/** Stops backfill() without putting the held entries back or calling its callback (the TUI stops for good). */
	stopBackfill(): void {
		if (this.backfillTimer !== undefined) clearImmediate(this.backfillTimer);
		this.backfillTimer = undefined;
		this.onBackfilled = undefined;
	}

	/**
	 * Puts the held entries back. True when the transcript changed since it was last rendered (entries put back now or since
	 * the last render): it must be laid out again before its lines or its height are read.
	 */
	completeNow(): boolean {
		if (this.held.length > 0) {
			this.putBack();
			this.putBackUnrendered = true;
		}
		return this.putBackUnrendered;
	}

	protected override renderLiveChildren(width: number): LiveLineArray {
		this.putBackUnrendered = false;
		const linesNeeded = this.endFirst;
		if (linesNeeded !== undefined) {
			this.endFirst = undefined;
			this.holdBack(width, linesNeeded());
		} else if (this.held.length > 0 && width !== this.heldWidth) {
			this.putBack();
		}
		const live = super.renderLiveChildren(width);
		if (this.held.length === 0 || live.lines.length >= this.linesNeeded()) return live;
		this.putBack();
		return super.renderLiveChildren(width);
	}

	/** Holds back the entries above the last ones that make `lines` lines at `width` (rendering those). */
	private holdBack(width: number, lines: number): void {
		const children = super.children;
		let first = children.length;
		let height = 0;
		while (first > 0 && height < lines) {
			first--;
			height += renderEntry(children[first]!, width);
		}
		if (first === 0) return;
		this.held = children.splice(0, first);
		this.heldWidth = width;
	}

	private backfillSlice(): void {
		this.backfillTimer = undefined;
		const deadline = performance.now() + BACKFILL_SLICE_MS;
		while (this.nextBackfilled >= 0) {
			renderEntry(this.held[this.nextBackfilled--]!, this.heldWidth);
			if (performance.now() >= deadline) break;
		}
		if (this.nextBackfilled >= 0) {
			this.backfillTimer = setImmediate(() => this.backfillSlice());
			return;
		}
		this.completeNow();
	}

	private putBack(): void {
		const held = this.held;
		this.held = [];
		const onBackfilled = this.onBackfilled;
		this.stopBackfill();
		super.children = held.concat(super.children);
		onBackfilled?.();
	}
}

/** Renders `entry` at `width` as a Container renders a child, and returns its height. */
function renderEntry(entry: Component, width: number): number {
	return (renderLiveLines(entry, width)?.lines ?? renderSharedLines(entry, width)).length;
}
