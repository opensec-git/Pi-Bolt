import {
	Box,
	type Component,
	Container,
	getCapabilities,
	Image,
	MouseRegion,
	markReportsChanges,
	renderChildLines,
	reportRenderChange,
	reportsChanges,
	Spacer,
	sanitizeTerminalText,
	setSubtreeReportsChanges,
	Text,
	type TUI,
	type TuiMouseEvent,
} from "@earendil-works/pi-tui";
import type { ToolDefinition, ToolRenderContext, ToolRenderers } from "../../../core/extensions/types.ts";

/** What this component needs from a tool: how to draw it, without executing it. */
export type { ToolRenderers };

import { formatToolCallWithArgs, getTextOutput as getRenderedTextOutput } from "../../../core/tools/render-utils.ts";
import { renderersMayHighlight } from "../../../core/tools/renderers/highlighting.ts";
import { formatTextPreview } from "../../../core/tools/renderers/text-preview.ts";
import { ensurePngTranscoder } from "../../../utils/image-convert.ts";
import { liveTheme, theme } from "../theme/theme.ts";
import { freshCopy } from "./fresh-copy.ts";
import { keyHint, keyText } from "./keybinding-hints.ts";

const FALLBACK_PREVIEW_LINES = 10;

export interface ToolExecutionOptions {
	showImages?: boolean;
	imageWidthCells?: number;
	outputPad?: number;
	/**
	 * Its renderers are Pi's own and draw a settled result from it alone: a settled row may then let go of its components
	 * (compact()) and have them made again. Not for renderers from extensions, or for those that keep what they drew while
	 * the call streamed in (edit, write).
	 */
	compactable?: boolean;
}

type ToolShell =
	| { kind: "default"; container: Box }
	| { kind: "self"; container: Container }
	| { kind: "fallback"; text: Text };

export class ToolExecutionComponent extends Container {
	private shell?: ToolShell;
	private selfRenderHeight = 0;
	/** What the self-rendered shell was joined from, and the joined lines (renderShared()). */
	private selfRenderParts?: string[][];
	private selfRenderLines: string[] = [];
	private readonly emptyLines: string[] = [];
	private callRendererComponent?: Component;
	private resultRendererComponent?: Component;
	private rendererState: any = {};
	/** Only image-bearing rows retain image children and the inputs needed to reuse their converted PNG data. */
	private images?: Array<{ component: Image; spacer: Spacer; data: string; mimeType: string; widthCells: number }>;
	private toolName: string;
	private toolCallId: string;
	private args: any;
	private expanded = false;
	private showImages: boolean;
	private imageWidthCells: number;
	private outputPad: number;
	private isPartial = true;
	private toolDefinition?: ToolRenderers;
	private ui: TUI;
	private cwd: string;
	private executionStarted = false;
	private argsComplete = false;
	/** `updateArgs()` ran since the last display update; the next render applies it. */
	private argsPending = false;
	private result?: {
		content: Array<{ type: string; text?: string; data?: string; mimeType?: string }>;
		isError: boolean;
		details?: any;
		durationMs?: number;
	};
	private hideComponent = false;
	private readonly compactable: boolean;
	/** What renderShared() returned last, at which width, and whether nothing that changes the lines happened since. */
	private lastRendered?: { width: number; lines: string[]; current: boolean };
	/** The lines compact() kept, without the components they were rendered from: what a render at `width` returns. */
	private compacted?: { width: number; lines: string[] };

	constructor(
		toolName: string,
		toolCallId: string,
		args: any,
		options: ToolExecutionOptions = {},
		toolDefinition: ToolRenderers | ToolDefinition<any, any, any> | undefined,
		ui: TUI,
		cwd: string,
	) {
		super();
		this.toolName = toolName;
		this.toolCallId = toolCallId;
		this.args = args;
		this.toolDefinition = toolDefinition;
		this.showImages = options.showImages ?? true;
		this.imageWidthCells = options.imageWidthCells ?? 60;
		this.outputPad = options.outputPad ?? 1;
		this.compactable = options.compactable ?? false;
		this.ui = ui;
		this.cwd = cwd;

		this.updateDisplay();
	}

	/** Mounts the shell used by this tool, after the spacer above it. */
	private addShell(): ToolShell {
		this.addChild(new Spacer(1));
		if (this.toolDefinition === undefined) {
			const text = new Text("", 1, 1);
			this.addChild(this.createResultRegion(text));
			this.shell = { kind: "fallback", text };
		} else if (this.toolDefinition.renderShell === "self") {
			const container = new Container();
			this.addChild(container);
			this.shell = { kind: "self", container };
		} else {
			const container = new Box(1, 1);
			// (Its backgrounds read the theme, and every theme change invalidates the transcript.)
			container.setBackgroundReported(true);
			this.addChild(container);
			this.shell = { kind: "default", container };
		}
		return this.shell;
	}

	private getCallRenderer(): ToolDefinition<any, any>["renderCall"] | undefined {
		return this.toolDefinition?.renderCall;
	}

	private getResultRenderer(): ToolDefinition<any, any>["renderResult"] | undefined {
		return this.toolDefinition?.renderResult;
	}

	private getRenderContext(lastComponent: Component | undefined): ToolRenderContext {
		return {
			args: this.args,
			toolCallId: this.toolCallId,
			invalidate: () => {
				this.invalidate();
				this.ui.requestRender();
			},
			lastComponent,
			state: this.rendererState,
			cwd: this.cwd,
			executionStarted: this.executionStarted,
			argsComplete: this.argsComplete,
			isPartial: this.isPartial,
			expanded: this.expanded,
			showImages: this.showImages,
			isError: this.result?.isError ?? false,
			durationMs: this.isPartial ? undefined : this.result?.durationMs,
			outputPad: this.outputPad,
			keyText,
			keyHint,
		};
	}

	private createCallFallback(): Component {
		return new Text(formatToolCallWithArgs(this.toolName, this.args, theme, this.expanded), 0, 0);
	}

	private createResultFallback(): Component | undefined {
		const output = this.getTextOutput();
		if (!output) {
			return undefined;
		}

		return new Text(formatTextPreview(output, theme, this.expanded ? undefined : FALLBACK_PREVIEW_LINES), 0, 0);
	}

	private createResultRegion(component: Component): MouseRegion {
		return new MouseRegion(component, (event) => {
			if (!this.result || event.type !== "click" || event.button !== "left") return undefined;
			this.setExpanded(!this.expanded);
			return { handled: true };
		});
	}

	/**
	 * Show new partial arguments. Applied at the next render, so a call streaming many argument deltas between two
	 * frames runs its renderer once per frame, not once per delta (#9265). Returns whether `args` are new arguments,
	 * which may change what this draws.
	 */
	updateArgs(args: any): boolean {
		// Every message_update hands every tool call of the message its arguments again, and the providers parse
		// streamed arguments again only when that is cheap enough (StreamingJsonParser.snapshot()), keeping the same
		// object in between: the same object is the same arguments, and nothing on the screen changes.
		if (args === this.args) return false;
		this.thaw(false);
		if (this.lastRendered !== undefined) this.lastRendered.current = false;
		this.args = args;
		this.argsPending = true;
		reportRenderChange(this);
		this.ui.requestRender();
		return true;
	}

	markExecutionStarted(): void {
		this.executionStarted = true;
		this.updateDisplay();
		this.ui.requestRender();
	}

	setArgsComplete(): void {
		this.argsComplete = true;
		this.updateDisplay();
		this.ui.requestRender();
	}

	updateResult(
		result: {
			content: Array<{ type: string; text?: string; data?: string; mimeType?: string }>;
			details?: any;
			isError: boolean;
			/** Execution time of a final result. */
			durationMs?: number;
		},
		isPartial = false,
	): void {
		this.result = result;
		this.isPartial = isPartial;
		this.updateDisplay();
	}

	setExpanded(expanded: boolean): void {
		this.expanded = expanded;
		this.updateDisplay();
	}

	setOutputPad(outputPad: number): void {
		this.outputPad = outputPad;
		this.updateDisplay();
	}

	setShowImages(show: boolean): void {
		this.showImages = show;
		this.updateDisplay();
	}

	setImageWidthCells(width: number): void {
		this.imageWidthCells = Math.max(1, Math.floor(width));
		this.updateDisplay();
	}

	override invalidate(): void {
		this.thaw(false);
		super.invalidate();
		this.updateDisplay();
	}

	/**
	 * For a settled row (a final result, no images) whose renderers allow it (`compactable`), rendered since it last changed:
	 * keeps the lines it rendered last and lets go of the components it rendered them from. A render at the same width
	 * returns those lines, the same array; anything that would change them (another width, invalidate(), the setters, a
	 * click) makes the components again first (thaw()), from the arguments, the result and the renderer state kept.
	 * Returns whether it compacted.
	 */
	compact(): boolean {
		const last = this.lastRendered;
		if (
			!this.compactable ||
			this.compacted !== undefined ||
			this.result === undefined ||
			this.isPartial ||
			this.argsPending ||
			this.images !== undefined ||
			this.shell?.kind === "self" ||
			last === undefined ||
			!last.current
		) {
			return false;
		}
		this.compacted = { width: last.width, lines: last.lines };
		this.lastRendered = undefined;
		this.clear();
		this.forgetRendered();
		this.shell = undefined;
		this.callRendererComponent = undefined;
		this.resultRendererComponent = undefined;
		return true;
	}

	/** Whether compact() let go of the components (for tests). */
	isCompacted(): boolean {
		return this.compacted !== undefined;
	}

	/**
	 * After compact(): a copy to put in this row's place (freshCopy()).
	 */
	compactedCopy(): ToolExecutionComponent {
		const copy = freshCopy(this);
		// (Its lines in an array of its own: the containers it is in take them as changed once.)
		if (this.compacted !== undefined)
			copy.compacted = { width: this.compacted.width, lines: this.compacted.lines.slice() };
		return copy;
	}

	/**
	 * Makes again the components compact() let go of, and draws them (updateDisplay(), which reports the change) unless the
	 * caller is about to (`display` false).
	 */
	private thaw(display = true): void {
		if (this.compacted === undefined) return;
		this.compacted = undefined;
		if (display) this.updateDisplay();
	}

	/** Whether this row may show code highlighted in one of `languages` (lowercase names). */
	mayHighlight(languages: ReadonlySet<string>): boolean {
		return renderersMayHighlight(this.toolDefinition, this.args, languages);
	}

	override renderShared(width: number): string[] {
		if (this.argsPending) this.updateDisplay();
		const compacted = this.compacted;
		if (compacted !== undefined) {
			if (compacted.width === width) {
				this.noteRendered();
				return compacted.lines;
			}
			this.thaw();
		}
		const lines = this.renderSharedLines(width);
		const last = this.lastRendered;
		if (last?.current && last.width === width) last.lines = lines;
		else this.lastRendered = { width, lines, current: true };
		return lines;
	}

	private renderSharedLines(width: number): string[] {
		if (this.hideComponent) {
			this.noteRendered();
			return this.emptyLines;
		}

		if (this.shell?.kind === "self") {
			this.noteRendered();
			// The parts' lines: all from components that keep their lines (renderSharedLines()), so the same arrays are the
			// same lines, and the joined lines are kept with them.
			const container = this.shell.container;
			const parts = [renderChildLines(this, container, width)];
			let reports = reportsChanges(container);
			if (this.images) {
				for (const { component, spacer } of this.images) {
					parts.push(renderChildLines(this, spacer, width), renderChildLines(this, component, width));
					if (!reportsChanges(component)) reports = false;
				}
			}
			setSubtreeReportsChanges(this, reports);
			const previous = this.selfRenderParts;
			if (previous?.length === parts.length && previous.every((part, i) => part === parts[i])) {
				return this.selfRenderLines;
			}
			const contentLines = parts[0]!;
			this.selfRenderHeight = contentLines.length;
			const lines: string[] = [];
			if (contentLines.length > 0) {
				lines.push("");
				lines.push(...contentLines);
			}
			for (let i = 1; i < parts.length; i++) {
				lines.push(...parts[i]!);
			}
			this.selfRenderParts = parts;
			this.selfRenderLines = contentLines.length === 0 && !this.images ? this.emptyLines : lines;
			return this.selfRenderLines;
		}

		return super.renderShared(width);
	}

	override handleMouse(event: TuiMouseEvent): ReturnType<Container["handleMouse"]> {
		if (this.compacted !== undefined) {
			// Only a left click does anything here (on the result: it expands it); any other event finds no handler.
			if (event.type !== "click" || event.button !== "left") return undefined;
			this.thaw();
		}
		if (this.shell?.kind !== "self") return super.handleMouse(event);
		if (event.y <= 0 || event.y > this.selfRenderHeight) return undefined;
		return this.shell.container.handleMouse({
			...event,
			y: event.y - 1,
			height: this.selfRenderHeight,
		});
	}

	private updateDisplay(): void {
		this.thaw(false);
		const shell = this.shell ?? this.addShell();
		if (this.lastRendered !== undefined) this.lastRendered.current = false;
		this.argsPending = false;
		const bgFn = this.isPartial
			? (text: string) => theme.bg("toolPendingBg", text)
			: this.result?.isError
				? (text: string) => theme.bg("toolErrorBg", text)
				: (text: string) => theme.bg("toolSuccessBg", text);

		let hasContent = false;
		this.hideComponent = false;
		if (shell.kind !== "fallback") {
			const renderContainer = shell.container;
			if (shell.kind === "default") {
				shell.container.setBgFn(bgFn);
				shell.container.setPaddingX(this.outputPad);
			}
			renderContainer.clear();

			const callRenderer = this.getCallRenderer();
			if (!callRenderer) {
				renderContainer.addChild(this.createResultRegion(this.createCallFallback()));
				hasContent = true;
			} else {
				try {
					// (liveTheme: a renderer may keep it in what it returns, which is handed back to it, and a theme may change.)
					const component = callRenderer(this.args, liveTheme, this.getRenderContext(this.callRendererComponent));
					this.callRendererComponent = component;
					renderContainer.addChild(this.createResultRegion(component));
					hasContent = true;
				} catch {
					this.callRendererComponent = undefined;
					renderContainer.addChild(this.createResultRegion(this.createCallFallback()));
					hasContent = true;
				}
			}

			if (this.result) {
				const resultRenderer = this.getResultRenderer();
				if (!resultRenderer) {
					const component = this.createResultFallback();
					if (component) {
						renderContainer.addChild(this.createResultRegion(component));
						hasContent = true;
					}
				} else {
					try {
						const component = resultRenderer(
							{ content: this.result.content as any, details: this.result.details },
							{ expanded: this.expanded, isPartial: this.isPartial },
							liveTheme,
							this.getRenderContext(this.resultRendererComponent),
						);
						this.resultRendererComponent = component;
						renderContainer.addChild(this.createResultRegion(component));
						hasContent = true;
					} catch {
						this.resultRendererComponent = undefined;
						const component = this.createResultFallback();
						if (component) {
							renderContainer.addChild(this.createResultRegion(component));
							hasContent = true;
						}
					}
				}
			}
		} else {
			shell.text.setCustomBgFn(bgFn);
			shell.text.setPaddingX(this.outputPad);
			shell.text.setText(this.formatToolExecution());
			hasContent = true;
		}

		const previousImages = this.images;
		if (previousImages) {
			for (const { component, spacer } of previousImages) {
				this.removeChild(component);
				this.removeChild(spacer);
			}
			this.images = undefined;
		}

		if (this.showImages && this.result) {
			for (const img of this.result.content) {
				if (img.type === "image" && img.data && img.mimeType && getCapabilities().images) {
					const spacer = new Spacer(1);
					this.addChild(spacer);
					this.images ??= [];
					const previous = previousImages?.[this.images.length];
					const imageComponent =
						previous?.data === img.data &&
						previous.mimeType === img.mimeType &&
						previous.widthCells === this.imageWidthCells
							? previous.component
							: new Image(
									img.data,
									img.mimeType,
									{ fallbackColor: (s: string) => theme.fg("toolOutput", s) },
									{ maxWidthCells: this.imageWidthCells },
								);
					if (img.mimeType !== "image/png") {
						ensurePngTranscoder(() => {
							this.invalidate();
							this.ui.requestRender();
						});
					}
					this.images.push({
						component: imageComponent,
						spacer,
						data: img.data,
						mimeType: img.mimeType,
						widthCells: this.imageWidthCells,
					});
					this.addChild(imageComponent);
				}
			}
		}

		if (!hasContent && !this.images) {
			this.hideComponent = true;
		}
		reportRenderChange(this);
	}

	private getTextOutput(): string {
		return getRenderedTextOutput(this.result, this.showImages);
	}

	private formatToolExecution(): string {
		let text = theme.fg("toolTitle", theme.bold(sanitizeTerminalText(this.toolName)));
		const content = JSON.stringify(this.args, null, 2);
		if (content) {
			// (JSON.stringify() escapes C0 controls in the model's arguments, but not DEL and C1 controls.)
			text += `\n\n${sanitizeTerminalText(content)}`;
		}
		const output = this.getTextOutput();
		if (output) {
			text += `\n${output}`;
		}
		return text;
	}
}

markReportsChanges(ToolExecutionComponent);
