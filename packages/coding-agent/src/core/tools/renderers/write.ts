/**
 * Presentation for the write tool.
 *
 * Renderers live apart from the implementation so a process that only displays tool output does not
 * load the execution path or its typebox parameter schema. `write.ts` spreads these into its
 * definition, so the tool's public shape is unchanged.
 */

import { Container, Text } from "@earendil-works/pi-tui";
import { keyHint } from "../../../modes/interactive/components/keybinding-hints.ts";
import { getLanguageFromPath, highlightCode, type Theme } from "../../../modes/interactive/theme/theme.ts";
import type { ToolDefinition, ToolRenderResultOptions } from "../../extensions/types.ts";
import { normalizeDisplayText, renderToolPath, replaceTabs, str } from "../render-utils.ts";

type WriteHighlightCache = {
	rawPath: string | null;
	lang: string;
	rawContent: string;
	normalizedLines: string[];
	/**
	 * The lines highlighted. When the call is shown collapsed, only the first lines are: the lines after the ones that can
	 * be shown are highlighted when the call is expanded (`all`), not as each one arrives and again when the file is
	 * complete, which for a large file stops everything for as long as highlighting all of it takes.
	 */
	highlightedLines: string[];
	all: boolean;
	/** Made from the complete file, not from the pieces it arrived in. */
	complete?: boolean;
	/**
	 * Set once the call has its result and is shown collapsed: the count of lines shown when expanded, with
	 * normalizedLines cut to the first lines. A collapsed call needs no more of them, and every write of a session stays in
	 * the chat: keeping each file split into lines again holds more memory than the files themselves.
	 */
	lineCount?: number;
};
class WriteCallRenderComponent extends Text {
	cache?: WriteHighlightCache;

	constructor() {
		super("", 0, 0);
	}
}
const WRITE_PARTIAL_FULL_HIGHLIGHT_LINES = 50;
/** The lines of a collapsed call that are shown. */
const WRITE_COLLAPSED_LINES = 10;
function highlightSingleLine(line: string, lang: string): string {
	const highlighted = highlightCode(line, lang);
	return highlighted[0] ?? "";
}
function refreshWriteHighlightPrefix(cache: WriteHighlightCache): void {
	const prefixCount = Math.min(WRITE_PARTIAL_FULL_HIGHLIGHT_LINES, cache.normalizedLines.length);
	if (prefixCount === 0) return;
	const prefixSource = cache.normalizedLines.slice(0, prefixCount).join("\n");
	const prefixHighlighted = highlightCode(prefixSource, cache.lang);
	for (let i = 0; i < prefixCount; i++) {
		cache.highlightedLines[i] =
			prefixHighlighted[i] ?? highlightSingleLine(cache.normalizedLines[i] ?? "", cache.lang);
	}
}
function rebuildWriteHighlightCacheFull(rawPath: string | null, fileContent: string): WriteHighlightCache | undefined {
	const lang = rawPath ? getLanguageFromPath(rawPath) : undefined;
	if (!lang) return undefined;
	const displayContent = normalizeDisplayText(fileContent);
	const normalized = replaceTabs(displayContent);
	return {
		rawPath,
		lang,
		rawContent: fileContent,
		normalizedLines: normalized.split("\n"),
		highlightedLines: highlightCode(normalized, lang),
		all: true,
	};
}
/** The cache for a call shown collapsed: the first lines highlighted, as they are while the file streams in. */
function rebuildWriteHighlightCacheCollapsed(
	rawPath: string | null,
	fileContent: string,
): WriteHighlightCache | undefined {
	const lang = rawPath ? getLanguageFromPath(rawPath) : undefined;
	if (!lang) return undefined;
	const cache: WriteHighlightCache = {
		rawPath,
		lang,
		rawContent: fileContent,
		normalizedLines: replaceTabs(normalizeDisplayText(fileContent)).split("\n"),
		highlightedLines: [],
		all: false,
	};
	refreshWriteHighlightPrefix(cache);
	cache.all = cache.normalizedLines.length <= WRITE_PARTIAL_FULL_HIGHLIGHT_LINES;
	return cache;
}
/** Highlights, each on its own, the lines after the first ones that a collapsed call left unhighlighted. */
function highlightRemainingLines(cache: WriteHighlightCache): void {
	if (cache.all) return;
	for (
		let i = Math.min(WRITE_PARTIAL_FULL_HIGHLIGHT_LINES, cache.normalizedLines.length);
		i < cache.normalizedLines.length;
		i++
	) {
		cache.highlightedLines[i] = highlightSingleLine(cache.normalizedLines[i], cache.lang);
	}
	cache.all = true;
}
function updateWriteHighlightCacheIncremental(
	cache: WriteHighlightCache | undefined,
	rawPath: string | null,
	fileContent: string,
	expanded: boolean,
): WriteHighlightCache | undefined {
	const lang = rawPath ? getLanguageFromPath(rawPath) : undefined;
	if (!lang) return undefined;
	const rebuild = expanded ? rebuildWriteHighlightCacheFull : rebuildWriteHighlightCacheCollapsed;
	if (!cache) return rebuild(rawPath, fileContent);
	if (cache.lang !== lang || cache.rawPath !== rawPath) return rebuild(rawPath, fileContent);
	if (!fileContent.startsWith(cache.rawContent)) return rebuild(rawPath, fileContent);
	if (cache.lineCount !== undefined && (expanded || fileContent.length !== cache.rawContent.length)) {
		return rebuild(rawPath, fileContent);
	}
	if (expanded) highlightRemainingLines(cache);
	if (fileContent.length === cache.rawContent.length) return cache;
	if (!expanded) {
		// Collapsed: the lines are kept, and the first of them highlighted.
		const added = replaceTabs(normalizeDisplayText(fileContent.slice(cache.rawContent.length))).split("\n");
		cache.rawContent = fileContent;
		cache.all = false;
		cache.complete = false;
		if (cache.normalizedLines.length === 0) cache.normalizedLines.push("");
		const lastLine = cache.normalizedLines.length - 1;
		cache.normalizedLines[lastLine] += added[0];
		for (let i = 1; i < added.length; i++) cache.normalizedLines.push(added[i]);
		refreshWriteHighlightPrefix(cache);
		// (While the first lines are all the lines, every line is highlighted.)
		cache.all = cache.normalizedLines.length <= WRITE_PARTIAL_FULL_HIGHLIGHT_LINES;
		return cache;
	}

	const deltaRaw = fileContent.slice(cache.rawContent.length);
	const deltaDisplay = normalizeDisplayText(deltaRaw);
	const deltaNormalized = replaceTabs(deltaDisplay);
	cache.rawContent = fileContent;
	if (cache.normalizedLines.length === 0) {
		cache.normalizedLines.push("");
		cache.highlightedLines.push("");
	}

	const segments = deltaNormalized.split("\n");
	const lastIndex = cache.normalizedLines.length - 1;
	cache.normalizedLines[lastIndex] += segments[0];
	cache.highlightedLines[lastIndex] = highlightSingleLine(cache.normalizedLines[lastIndex], cache.lang);
	for (let i = 1; i < segments.length; i++) {
		cache.normalizedLines.push(segments[i]);
		cache.highlightedLines.push(highlightSingleLine(segments[i], cache.lang));
	}
	refreshWriteHighlightPrefix(cache);
	return cache;
}
/** The lines of a collapsed cache that are counted: without the empty lines at the end, as formatWriteCall() counts them. */
function countCollapsedLines(cache: WriteHighlightCache): number {
	if (cache.lineCount !== undefined) return cache.lineCount;
	let totalLines = cache.normalizedLines.length;
	// Without the empty lines at the end, unless an empty line is not empty once highlighted (in a language without a
	// highlighter, every line is colored).
	if (highlightSingleLine("", cache.lang) === "") {
		while (totalLines > 0 && cache.normalizedLines[totalLines - 1] === "") totalLines--;
	}
	return totalLines;
}
function trimTrailingEmptyLines(lines: string[]): string[] {
	let end = lines.length;
	while (end > 0 && lines[end - 1] === "") {
		end--;
	}
	return lines.slice(0, end);
}
function formatWriteCall(
	args: { path?: string; file_path?: string; content?: string } | undefined,
	options: ToolRenderResultOptions,
	theme: Theme,
	cache: WriteHighlightCache | undefined,
	cwd: string,
): string {
	const rawPath = str(args?.file_path ?? args?.path);
	const fileContent = str(args?.content);
	const pathDisplay = renderToolPath(rawPath, theme, cwd);
	let text = `${theme.fg("toolTitle", theme.bold("write"))} ${pathDisplay}`;

	if (fileContent === null) {
		text += `\n\n${theme.fg("error", "[invalid content arg - expected string]")}`;
	} else if (fileContent) {
		const lang = rawPath ? getLanguageFromPath(rawPath) : undefined;
		let totalLines: number;
		let displayLines: string[];
		if (lang && cache && !cache.all && !options.expanded) {
			// Collapsed, with only the first lines highlighted. The lines are counted as they are when all are
			// highlighted.
			totalLines = countCollapsedLines(cache);
			displayLines = cache.highlightedLines.slice(0, Math.min(WRITE_COLLAPSED_LINES, totalLines));
		} else {
			const renderedLines = lang
				? (cache?.highlightedLines ?? highlightCode(replaceTabs(normalizeDisplayText(fileContent)), lang))
				: normalizeDisplayText(fileContent).split("\n");
			const lines = trimTrailingEmptyLines(renderedLines);
			totalLines = lines.length;
			displayLines = lines.slice(0, options.expanded ? lines.length : WRITE_COLLAPSED_LINES);
		}
		const maxLines = options.expanded ? totalLines : WRITE_COLLAPSED_LINES;
		const remaining = totalLines - maxLines;
		text += `\n\n${displayLines.map((line) => (lang ? line : theme.fg("toolOutput", replaceTabs(line)))).join("\n")}`;
		if (remaining > 0) {
			text += `${theme.fg("muted", `\n... (${remaining} more lines, ${totalLines} total,`)} ${keyHint("app.tools.expand", "to expand")}${theme.fg("muted", ")")}`;
		}
	}

	return text;
}
function formatWriteResult(
	result: { content: Array<{ type: string; text?: string; data?: string; mimeType?: string }>; isError?: boolean },
	theme: Theme,
): string | undefined {
	if (!result.isError) {
		return undefined;
	}
	const output = result.content
		.filter((c) => c.type === "text")
		.map((c) => c.text || "")
		.join("\n");
	if (!output) {
		return undefined;
	}
	return `\n${theme.fg("error", output)}`;
}

export const writeRenderers: Pick<ToolDefinition<any, any>, "renderCall" | "renderResult"> = {
	renderCall(args, theme, context) {
		const renderArgs = args as { path?: string; file_path?: string; content?: string } | undefined;
		const rawPath = str(renderArgs?.file_path ?? renderArgs?.path);
		const fileContent = str(renderArgs?.content);
		const component =
			(context.lastComponent as WriteCallRenderComponent | undefined) ?? new WriteCallRenderComponent();
		if (fileContent !== null) {
			// Expanded, the whole file is highlighted once it is complete. Collapsed, the first lines are, as they were
			// while it streamed in.
			if (!context.argsComplete) {
				component.cache = updateWriteHighlightCacheIncremental(
					component.cache,
					rawPath,
					fileContent,
					context.expanded,
				);
			} else if (context.expanded) {
				component.cache = rebuildWriteHighlightCacheFull(rawPath, fileContent);
			} else {
				const cache = component.cache;
				if (cache?.complete && !cache.all && cache.rawPath === rawPath && cache.rawContent === fileContent) {
					refreshWriteHighlightPrefix(cache);
				} else {
					component.cache = rebuildWriteHighlightCacheCollapsed(rawPath, fileContent);
					if (component.cache) component.cache.complete = true;
				}
			}
		} else {
			component.cache = undefined;
		}
		const cache = component.cache;
		if (cache && !cache.all && !context.expanded && !context.isPartial && cache.lineCount === undefined) {
			cache.lineCount = countCollapsedLines(cache);
			cache.normalizedLines = cache.normalizedLines.slice(0, WRITE_PARTIAL_FULL_HIGHLIGHT_LINES);
		}
		component.setText(
			formatWriteCall(
				renderArgs,
				{ expanded: context.expanded, isPartial: context.isPartial },
				theme,
				component.cache,
				context.cwd,
			),
		);
		return component;
	},
	renderResult(result, _options, theme, context) {
		const output = formatWriteResult({ ...result, isError: context.isError }, theme);
		if (!output) {
			const component = (context.lastComponent as Container | undefined) ?? new Container();
			component.clear();
			return component;
		}
		const text = (context.lastComponent as Text | undefined) ?? new Text("", 0, 0);
		text.setText(output);
		return text;
	},
};
