/**
 * Presentation for the edit tool.
 *
 * Renderers live apart from the implementation so a process that only displays tool output does not
 * load the execution path or its typebox parameter schema. `edit.ts` spreads these into its
 * definition, so the tool's public shape is unchanged.
 */

import { Box, type Component, Container, Spacer, sanitizeTerminalText, Text } from "@earendil-works/pi-tui";
import { renderDiff } from "../../../modes/interactive/components/diff.ts";
import { getThemeInstance, type Theme } from "../../../modes/interactive/theme/theme.ts";
import type { ToolDefinition } from "../../extensions/types.ts";
import type { EditToolDetails } from "../edit.ts";
import { computeEditsDiff, type Edit, type EditDiffError, type EditDiffResult } from "../edit-diff.ts";
import { renderToolPath, str } from "../render-utils.ts";
import { declarePlainRenderers } from "./highlighting.ts";

type EditPreview = EditDiffResult | EditDiffError;
export type EditRenderState = {
	callComponent?: EditCallRenderComponent;
};
type RenderableEditArgs = {
	path?: string;
	file_path?: string;
	edits?: Edit[];
	oldText?: string;
	newText?: string;
};
type EditToolResultLike = {
	content: Array<{ type: string; text?: string; data?: string; mimeType?: string }>;
	details?: EditToolDetails;
};
type EditCallRenderComponent = Box & {
	preview?: EditPreview;
	previewArgsKey?: string;
	previewPending?: boolean;
	settledError?: boolean;
	/** What the rows were last built from and with (buildEditCallComponent()). */
	built?: BuiltEditCall;
};
/**
 * The rows of an edit call and what they were made from. A tool row is built again on every display update (each
 * streamed argument, the start, the result, an invalidation): the diff is rendered again only when it or the theme
 * changed, and an unchanged row keeps its component, which keeps its wrapped lines.
 */
type BuiltEditCall = {
	theme: Theme | undefined;
	header: string;
	headerText: Text;
	diff?: string;
	renderedDiff?: string;
	body?: string;
	bodyText?: Text;
	spacer?: Spacer;
};
function createEditCallRenderComponent(): EditCallRenderComponent {
	return Object.assign(new Box(1, 1, (text: string) => text), {
		preview: undefined as EditPreview | undefined,
		previewArgsKey: undefined as string | undefined,
		previewPending: false,
		settledError: false,
	});
}
function getEditCallRenderComponent(state: EditRenderState, lastComponent: unknown): EditCallRenderComponent {
	if (lastComponent instanceof Box) {
		const component = lastComponent as EditCallRenderComponent;
		state.callComponent = component;
		return component;
	}
	if (state.callComponent) {
		return state.callComponent;
	}
	const component = createEditCallRenderComponent();
	state.callComponent = component;
	return component;
}
function getRenderablePreviewInput(args: RenderableEditArgs | undefined): { path: string; edits: Edit[] } | null {
	if (!args) {
		return null;
	}

	const path = typeof args.path === "string" ? args.path : typeof args.file_path === "string" ? args.file_path : null;
	if (!path) {
		return null;
	}

	if (
		Array.isArray(args.edits) &&
		args.edits.length > 0 &&
		args.edits.every((edit) => typeof edit?.oldText === "string" && typeof edit?.newText === "string")
	) {
		return { path, edits: args.edits };
	}

	if (typeof args.oldText === "string" && typeof args.newText === "string") {
		return { path, edits: [{ oldText: args.oldText, newText: args.newText }] };
	}

	return null;
}
function formatEditCall(args: RenderableEditArgs | undefined, theme: Theme, cwd: string): string {
	const pathDisplay = renderToolPath(str(args?.file_path ?? args?.path), theme, cwd);
	return `${theme.fg("toolTitle", theme.bold("edit"))} ${pathDisplay}`;
}
function formatEditResult(
	args: RenderableEditArgs | undefined,
	preview: EditPreview | undefined,
	result: EditToolResultLike,
	theme: Theme,
	isError: boolean,
): string | undefined {
	const rawPath = str(args?.file_path ?? args?.path);
	const previewDiff = preview && !("error" in preview) ? preview.diff : undefined;
	const previewError = preview && "error" in preview ? preview.error : undefined;
	if (isError) {
		const errorText = result.content
			.filter((c) => c.type === "text")
			.map((c) => c.text || "")
			.join("\n");
		if (!errorText || errorText === previewError) {
			return undefined;
		}
		return theme.fg("error", sanitizeTerminalText(errorText));
	}

	const resultDiff = result.details?.diff;
	if (resultDiff && resultDiff !== previewDiff) {
		return renderDiff(resultDiff, { filePath: rawPath ?? undefined });
	}

	return undefined;
}
function getEditHeaderBg(
	preview: EditPreview | undefined,
	settledError: boolean | undefined,
	theme: Theme,
): (text: string) => string {
	if (preview) {
		if ("error" in preview) {
			return (text: string) => theme.bg("toolErrorBg", text);
		}
		return (text: string) => theme.bg("toolSuccessBg", text);
	}
	if (settledError) {
		return (text: string) => theme.bg("toolErrorBg", text);
	}
	return (text: string) => theme.bg("toolPendingBg", text);
}
function buildEditCallComponent(
	component: EditCallRenderComponent,
	args: RenderableEditArgs | undefined,
	theme: Theme,
	cwd: string,
	outputPad: number,
): EditCallRenderComponent {
	component.setBgFn(getEditHeaderBg(component.preview, component.settledError, theme));
	component.setPaddingX(outputPad);
	const themeInstance = getThemeInstance();
	const previous = component.built?.theme === themeInstance ? component.built : undefined;
	const header = formatEditCall(args, theme, cwd);
	const built: BuiltEditCall = {
		theme: themeInstance,
		header,
		headerText: previous?.header === header ? previous.headerText : new Text(header, 0, 0),
	};
	component.built = built;
	const children: Component[] = [built.headerText];

	const preview = component.preview;
	if (preview) {
		let body: string;
		if ("error" in preview) {
			body = theme.fg("error", sanitizeTerminalText(preview.error));
		} else {
			built.diff = preview.diff;
			built.renderedDiff =
				previous?.diff === preview.diff && previous.renderedDiff !== undefined
					? previous.renderedDiff
					: renderDiff(preview.diff);
			body = built.renderedDiff;
		}
		built.body = body;
		built.bodyText = previous?.body === body && previous.bodyText ? previous.bodyText : new Text(body, 0, 0);
		built.spacer = previous?.spacer ?? new Spacer(1);
		children.push(built.spacer, built.bodyText);
	}

	// The same rows: leave the box as it is, so it keeps its lines.
	if (children.length !== component.children.length || children.some((child, i) => child !== component.children[i])) {
		component.clear();
		for (const child of children) component.addChild(child);
	}
	return component;
}
function setEditPreview(
	component: EditCallRenderComponent,
	preview: EditPreview,
	argsKey: string | undefined,
): boolean {
	const current = component.preview;
	const changed =
		current === undefined ||
		("error" in current && "error" in preview
			? current.error !== preview.error
			: "error" in current !== "error" in preview) ||
		(!("error" in current) &&
			!("error" in preview) &&
			(current.diff !== preview.diff || current.firstChangedLine !== preview.firstChangedLine));
	component.preview = preview;
	component.previewArgsKey = argsKey;
	component.previewPending = false;
	return changed;
}

export const editRenderers: Pick<ToolDefinition<any, any>, "renderCall" | "renderResult"> = {
	renderCall(args, theme, context) {
		const component = getEditCallRenderComponent(context.state, context.lastComponent);
		const previewInput = getRenderablePreviewInput(args as RenderableEditArgs | undefined);
		const argsKey = previewInput ? JSON.stringify({ path: previewInput.path, edits: previewInput.edits }) : undefined;

		if (component.previewArgsKey !== argsKey) {
			component.preview = undefined;
			component.previewArgsKey = argsKey;
			component.previewPending = false;
			component.settledError = false;
		}

		if (context.argsComplete && previewInput && !component.preview && !component.previewPending) {
			component.previewPending = true;
			const requestKey = argsKey;
			void computeEditsDiff(previewInput.path, previewInput.edits, context.cwd).then((preview) => {
				if (component.previewArgsKey === requestKey) {
					setEditPreview(component, preview, requestKey);
					context.invalidate();
				}
			});
		}

		return buildEditCallComponent(
			component,
			args as RenderableEditArgs | undefined,
			theme,
			context.cwd,
			context.outputPad,
		);
	},
	renderResult(result, _options, theme, context) {
		const callComponent = context.state.callComponent;
		const previewInput = getRenderablePreviewInput(context.args as RenderableEditArgs | undefined);
		const argsKey = previewInput ? JSON.stringify({ path: previewInput.path, edits: previewInput.edits }) : undefined;
		const typedResult = result as EditToolResultLike;
		const resultDiff = !context.isError ? typedResult.details?.diff : undefined;
		let changed = false;
		if (callComponent) {
			if (typeof resultDiff === "string") {
				changed =
					setEditPreview(
						callComponent,
						{ diff: resultDiff, firstChangedLine: typedResult.details?.firstChangedLine },
						argsKey,
					) || changed;
			}
			if (callComponent.settledError !== context.isError) {
				callComponent.settledError = context.isError;
				changed = true;
			}
			if (changed) {
				buildEditCallComponent(
					callComponent,
					context.args as RenderableEditArgs | undefined,
					theme,
					context.cwd,
					context.outputPad,
				);
			}
		}

		const output = formatEditResult(
			context.args as RenderableEditArgs | undefined,
			callComponent?.preview,
			typedResult,
			theme,
			context.isError,
		);
		const component = (context.lastComponent as Container | undefined) ?? new Container();
		component.clear();
		if (!output) {
			return component;
		}
		component.addChild(new Spacer(1));
		component.addChild(new Text(output, context.outputPad, 0));
		return component;
	},
};

declarePlainRenderers(editRenderers);
