import {
	type Component,
	dispatchMouseEvent,
	isStableRender,
	type LiveLines,
	markLiveRender,
	markReportsChanges,
	markStableRender,
	renderChildLines,
	renderLiveLines,
	reportRenderChange,
	reportsChanges,
	setRenderOwner,
	setSubtreeReportsChanges,
	type TuiMouseDispatchResult,
	type TuiMouseEvent,
	type TuiMouseEventResult,
} from "../tui.ts";

export type MouseRegionHandler = (event: TuiMouseEvent) => TuiMouseEventResult | undefined;

/** Adds mouse handling to an existing component without changing its rendering. */
export class MouseRegion implements Component {
	private readonly child: Component;
	private readonly onMouse: MouseRegionHandler;
	/** A copy of the lines of a child that does not keep its lines (isStableRender()), kept while they are the same. */
	private copiedLines?: string[];

	constructor(child: Component, onMouse: MouseRegionHandler) {
		this.child = child;
		this.onMouse = onMouse;
	}

	render(width: number): readonly string[] {
		const lines = renderChildLines(this, this.child, width);
		setSubtreeReportsChanges(this, reportsChanges(this.child));
		if (isStableRender(this.child)) return lines;
		const copied = this.copiedLines;
		if (copied !== undefined && copied.length === lines.length && copied.every((line, i) => line === lines[i])) {
			return copied;
		}
		this.copiedLines = lines.slice();
		return this.copiedLines;
	}

	handleMouse(event: TuiMouseEvent): TuiMouseDispatchResult | TuiMouseEventResult | undefined {
		const childResult = dispatchMouseEvent(this.child, event);
		return childResult ?? this.onMouse(event);
	}

	invalidate(): void {
		this.copiedLines = undefined;
		this.child.invalidate();
		reportRenderChange(this);
	}

	/** renderLiveLines() for a MouseRegion: its child's, live when the child's are. */
	static renderLive(region: MouseRegion, width: number): LiveLines | undefined {
		setRenderOwner(region.child, region);
		const lines = renderLiveLines(region.child, width);
		setSubtreeReportsChanges(region, reportsChanges(region.child));
		return lines;
	}
}

markStableRender(MouseRegion);
markReportsChanges(MouseRegion);
markLiveRender(MouseRegion.prototype.render, function (this: Component, width: number) {
	return MouseRegion.renderLive(this as MouseRegion, width);
});
