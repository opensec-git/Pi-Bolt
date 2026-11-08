import {
	type Component,
	dispatchMouseEvent,
	markReportsChanges,
	markStableRender,
	type RenderedChildren,
	renderChildren,
	reportRenderChange,
	reportsChanges,
	setRenderOwner,
	setSubtreeReportsChanges,
	type TuiMouseDispatchResult,
	type TuiMouseEvent,
} from "../tui.ts";
import { applyBackgroundAround, flattenLines, INTERNED_LINE_LENGTH, internLine, visibleWidth } from "../utils.ts";

type RenderCache = {
	childLines: string[];
	width: number;
	bgSample: string | undefined;
	lines: string[];
};

/**
 * Box component - a container that applies padding and background to all children
 */
export class Box implements Component {
	private childList: Component[] = [];
	private paddingX: number;
	private paddingY: number;
	private bgFn?: (text: string) => string;
	/** What bgFn draws changes only with setBgFn() or invalidate() (setBackgroundReported()). */
	private backgroundReported = false;

	// Cache for rendered output
	private cache?: RenderCache;
	private rendered?: RenderedChildren;
	private readonly emptyLines: string[] = [];

	constructor(paddingX = 1, paddingY = 1, bgFn?: (text: string) => string) {
		this.paddingX = paddingX;
		this.paddingY = paddingY;
		this.bgFn = bgFn;
	}

	/** The children. Using the array, which may change it, is reported as a change (markReportsChanges()). */
	get children(): Component[] {
		reportRenderChange(this);
		return this.childList;
	}

	set children(children: Component[]) {
		this.childList = children;
		reportRenderChange(this);
	}

	/**
	 * The children, without reporting a use: a subclass that defines `children` as a property of its own (a class field in
	 * JavaScript) has its array read, and is then rendered as one that does not report its changes.
	 */
	private ownChildren(): Component[] {
		return Object.hasOwn(this, "children") ? (this as { children: Component[] }).children : this.childList;
	}

	addChild(component: Component): void {
		this.ownChildren().push(component);
		this.invalidateCache();
	}

	removeChild(component: Component): void {
		const children = this.ownChildren();
		const index = children.indexOf(component);
		if (index !== -1) {
			children.splice(index, 1);
			this.invalidateCache();
		}
	}

	clear(): void {
		if (Object.hasOwn(this, "children")) (this as { children: Component[] }).children = [];
		else this.childList = [];
		this.invalidateCache();
	}

	setBgFn(bgFn?: (text: string) => string): void {
		this.bgFn = bgFn;
		// Not invalidated: a change of the background's output is found by sampling it. Rendered again to sample it.
		reportRenderChange(this);
	}

	/**
	 * Declares that what the background function draws changes only when setBgFn() or invalidate() is called: for a
	 * function that reads a theme, when every theme change invalidates the components. The box then reports all its
	 * changes (markReportsChanges()) and is rendered again only when one is reported. Otherwise a box with a background
	 * is rendered on every frame, sampling the function to find a change, as a box always was.
	 */
	setBackgroundReported(reported: boolean): void {
		this.backgroundReported = reported;
		reportRenderChange(this);
	}

	setPaddingX(paddingX: number): void {
		if (this.paddingX === paddingX) return;
		this.paddingX = paddingX;
		this.invalidateCache();
	}

	private invalidateCache(): void {
		this.cache = undefined;
		this.rendered = undefined;
		reportRenderChange(this);
	}

	invalidate(): void {
		this.invalidateCache();
		for (const child of this.ownChildren()) {
			child.invalidate?.();
		}
	}

	handleMouse(event: TuiMouseEvent): TuiMouseDispatchResult | undefined {
		const contentWidth = Math.max(1, event.width - this.paddingX * 2);
		const contentY = event.y - this.paddingY;
		const contentX = event.x - this.paddingX;
		if (contentY < 0 || contentX < 0 || contentX >= contentWidth) return undefined;

		// The children as last drawn, at their drawn heights.
		const rendered = this.rendered?.width === contentWidth ? this.rendered : undefined;
		const children = rendered?.children ?? this.ownChildren();
		let childY = 0;
		for (let i = 0; i < children.length; i++) {
			const child = children[i]!;
			const childHeight = rendered ? rendered.heights[i]! : child.render(contentWidth).length;
			if (contentY >= childY && contentY < childY + childHeight) {
				return dispatchMouseEvent(child, {
					...event,
					x: contentX,
					y: contentY - childY,
					width: contentWidth,
					height: childHeight,
				});
			}
			childY += childHeight;
		}
		return undefined;
	}

	render(width: number): string[] {
		const children = this.ownChildren();
		if (children.length === 0) {
			setSubtreeReportsChanges(this, children === this.childList);
			return this.emptyLines;
		}

		const contentWidth = Math.max(1, width - this.paddingX * 2);
		const leftPad = " ".repeat(this.paddingX);

		// Render all children. Keep the child lines unpadded: children usually return the same string
		// objects every frame, so the cache check below is a cheap identity comparison per line.
		// Padding here would create new strings that must be compared character by character.
		const rendered = renderChildren(children, contentWidth, this.rendered);
		this.rendered = rendered;
		// (Told of the children's changes; its own are reported only if theirs all are.)
		let reports = children === this.childList && (this.bgFn === undefined || this.backgroundReported);
		for (const child of children) {
			setRenderOwner(child, this);
			if (!reportsChanges(child)) reports = false;
		}
		setSubtreeReportsChanges(this, reports);
		const childLines = rendered.lines;

		if (childLines.length === 0) {
			return this.emptyLines;
		}

		// Check if bgFn output changed by sampling
		const bgSample = this.bgFn ? this.bgFn("test") : undefined;

		// What was rendered at this width and background: still good for the child lines that are the same.
		const cache =
			this.cache && this.cache.width === width && this.cache.bgSample === bgSample ? this.cache : undefined;
		if (cache?.childLines === childLines) {
			return cache.lines;
		}

		// Apply background and padding
		const result: string[] = [];
		let reused = cache !== undefined && cache.childLines.length === childLines.length;

		// Top padding
		for (let i = 0; i < this.paddingY; i++) {
			result.push(cache ? cache.lines[i]! : this.applyBg("", width));
		}

		// Content
		for (let i = 0; i < childLines.length; i++) {
			const line = childLines[i]!;
			if (cache !== undefined && i < cache.childLines.length && cache.childLines[i] === line) {
				result.push(cache.lines[this.paddingY + i]!);
			} else {
				reused = false;
				result.push(this.applyBg(leftPad + line, width));
			}
		}

		// Bottom padding
		for (let i = 0; i < this.paddingY; i++) {
			result.push(cache ? cache.lines[cache.lines.length - this.paddingY + i]! : this.applyBg("", width));
		}

		// Every line is what it was: keep the array, so the parent sees this box unchanged.
		if (reused) {
			cache!.childLines = childLines;
			return cache!.lines;
		}

		// Update cache
		flattenLines(result);
		this.cache = { childLines, width, bgSample, lines: result };

		return result;
	}

	private applyBg(line: string, width: number): string {
		const visLen = visibleWidth(line);
		const padNeeded = Math.max(0, width - visLen);
		const padded = line + " ".repeat(padNeeded);
		// Already padded to width, so apply the background directly instead of measuring the line again.
		const result = this.bgFn ? applyBackgroundAround(padded, this.bgFn) : padded;
		// (Padding and blank lines are drawn alike in every box: one copy of each, internLine().)
		return line.length <= INTERNED_LINE_LENGTH ? internLine(result) : result;
	}
}

markStableRender(Box);
markReportsChanges(Box);
