import type { AssistantMessage } from "@earendil-works/pi-ai";
import {
	type Component,
	Container,
	LiveLineArray,
	type LiveLines,
	Markdown,
	type MarkdownTheme,
	MouseRegion,
	markLiveRender,
	markReportsChanges,
	reportRenderChange,
	Spacer,
	sanitizeTerminalText,
	startsWithText,
	Text,
} from "@earendil-works/pi-tui";
import type { MarkdownTransformer } from "../../../core/extensions/types.ts";
import { getMarkdownTheme, theme } from "../theme/theme.ts";
import { createMarkdownTransform } from "./markdown-transform.ts";

const OSC133_ZONE_START = "\x1b]133;A\x07";
const OSC133_ZONE_END = "\x1b]133;B\x07";
const OSC133_ZONE_FINAL = "\x1b]133;C\x07";

// One function for every render: the Markdown component keeps what it rendered for as long as its style is the same.
const thinkingTextColor = (text: string): string => theme.fg("thinkingText", text);

/** Whether `text` contains one of `names` (lowercase), ignoring case. */
export function mentionsAny(text: string, names: ReadonlySet<string>): boolean {
	if (names.size === 0 || text === "") return false;
	const lower = text.toLowerCase();
	for (const name of names) {
		if (lower.includes(name)) return true;
	}
	return false;
}

/** A text or thinking block's text, trimmed (BlockText.trim()). */
interface TrimmedText {
	/** The text. */
	raw: string;
	/** raw.trim(). */
	trimmed: string;
	/** Whether `raw` has a character that is not white space: `trimmed` is not empty. */
	visible: boolean;
	/** The white space `raw` ends with after its last visible character, when it has one. */
	pending: string;
}

/**
 * A text or thinking block of a message streaming in, trimmed without reading all of it for each chunk. A provider adds each
 * delta to the text (`text += delta`), and the engine keeps the result as the two strings joined until it is read; reading
 * it (trim(), a regular expression) then copies all of it into one string, a copy of the whole reply for each chunk. Here
 * each text the block is seen with (updateContent(), for each delta) is compared with the one seen before as a part of it
 * (startsWithText(): the text before is that part, so this reads neither), and what it adds is kept. Trimming then reads
 * the text added only: the result is the text trimmed before with what was added (and the white space before it) after
 * it, the same strings joined, which the Markdown component it is given reads in the same way. The comparisons are real:
 * a text that is not the one before with more after it is trimmed in full.
 */
class BlockText {
	/** The text as it was seen last. */
	raw: string;
	/** The text as it was trimmed last (trim()). */
	private trimmed?: TrimmedText;
	/** What `raw` adds to `trimmed.raw`, when it is that text with more after it. */
	private added?: string;

	constructor(raw: string) {
		this.raw = raw;
	}

	/** The block's text is now `raw`. */
	follow(raw: string): void {
		if (raw === this.raw) return;
		const before = this.raw;
		this.raw = raw;
		if (this.added !== undefined && raw.length > before.length && startsWithText(raw, before)) {
			this.added += raw.slice(before.length);
		} else {
			this.added = undefined;
		}
	}

	/** raw.trim(), whether it is not empty, from what was trimmed last and what was added since (follow()). */
	trim(): TrimmedText {
		const previous = this.trimmed;
		const raw = this.raw;
		const added = this.added;
		let result: TrimmedText;
		if (previous === undefined || added === undefined) {
			const trimmed = raw.trim();
			const visible = trimmed.length > 0;
			result = { raw, trimmed, visible, pending: visible ? raw.slice(raw.trimEnd().length) : "" };
		} else if (added === "") {
			result = previous;
		} else if (!previous.visible) {
			// (White space is what trim() removes, which is what /\s/ matches.)
			const start = added.search(/\S/);
			if (start < 0) {
				result = { raw, trimmed: "", visible: false, pending: "" };
			} else {
				const rest = added.slice(start);
				const trimmed = rest.trimEnd();
				result = { raw, trimmed, visible: true, pending: rest.slice(trimmed.length) };
			}
		} else {
			const visibleAdded = added.trimEnd();
			result =
				visibleAdded.length === 0
					? { raw, trimmed: previous.trimmed, visible: true, pending: previous.pending + added }
					: {
							raw,
							trimmed: previous.trimmed + (previous.pending + visibleAdded),
							visible: true,
							pending: added.slice(visibleAdded.length),
						};
		}
		this.trimmed = result;
		this.added = "";
		return result;
	}
}

/** A Markdown component of an assistant message, with what it was made from (AssistantMessageComponent.markdownFor()). */
interface MarkdownBlock {
	kind: "text" | "thinking";
	text: string;
	isStreaming: boolean;
	outputPad: number;
	component: Markdown;
}

/** A part of what buildContent() adds to the content container (planContent()). */
type ContentPart =
	| { kind: "spacer" }
	| { kind: "text"; text: string }
	| { kind: "thinking"; runIndex: number; hidden: boolean; text: string }
	| { kind: "toolCalls"; present: boolean }
	| { kind: "error"; text: string };

/**
 * Component that renders a complete assistant message
 */
export class AssistantMessageComponent extends Container {
	private contentContainer: Container;
	private hideThinkingBlock: boolean;
	private markdownTheme: MarkdownTheme;
	private hiddenThinkingLabel: string;
	private outputPad: number;
	private markdownTransformers: readonly MarkdownTransformer[];
	private lastMessage?: AssistantMessage;
	private hasToolCalls = false;
	private isStreaming = false;
	private contentIsStale = false;
	private thinkingVisibilityOverrides = new Map<number, boolean>();
	/** The children's lines with the prompt markers added, and the lines they were added to. */
	private markedFrom?: string[];
	private markedLines: string[] = [];
	/** renderLive()'s lines: the children's live lines with the prompt markers added, and what they were made from. */
	private markedLive?: { from: LiveLines; revision: number; length: number; lines: LiveLineArray };
	/**
	 * The Markdown components of the content, with what they were made from. A rebuild with the same text takes the
	 * component again instead of making a new one, which would parse its text again and render it from nothing: on
	 * invalidate() (theme changes), and while a reply streams, for every block but the one growing.
	 */
	private markdownBlocks: MarkdownBlock[] = [];
	/**
	 * What the content was last built for while the reply streamed in (buildContent()): the plan's key and the theme. Dropped
	 * when anything the components were made with may have changed (invalidate() and the setters).
	 */
	private built?: { key: string; theme: unknown };
	/** The text and thinking blocks of the message, by index (BlockText). */
	private blockTexts: Array<BlockText | undefined> = [];

	constructor(
		message?: AssistantMessage,
		hideThinkingBlock = false,
		markdownTheme: MarkdownTheme = getMarkdownTheme(),
		hiddenThinkingLabel = "Thinking...",
		outputPad = 1,
		markdownTransformers: readonly MarkdownTransformer[] = [],
	) {
		super();

		this.hideThinkingBlock = hideThinkingBlock;
		this.markdownTheme = markdownTheme;
		this.hiddenThinkingLabel = hiddenThinkingLabel;
		this.outputPad = outputPad;
		this.markdownTransformers = markdownTransformers;

		// Container for text/thinking content
		this.contentContainer = new Container();
		this.addChild(this.contentContainer);

		if (message) {
			this.updateContent(message);
		}
	}

	override invalidate(): void {
		super.invalidate();
		this.built = undefined;
		if (this.lastMessage) {
			this.updateContent(this.lastMessage);
		}
	}

	/**
	 * Whether this message may show code highlighted in one of `languages` (lowercase names): whether its text names one
	 * of them anywhere, which every code block in that language does. Markdown transformers (extensions) may add any.
	 */
	mayHighlight(languages: ReadonlySet<string>): boolean {
		if (this.markdownTransformers.length > 0) return true;
		const content = this.lastMessage?.content ?? [];
		return content.some((block) => {
			const text = block.type === "text" ? block.text : block.type === "thinking" ? block.thinking : "";
			return mentionsAny(text, languages);
		});
	}

	setHideThinkingBlock(hide: boolean): void {
		this.built = undefined;
		this.hideThinkingBlock = hide;
		this.thinkingVisibilityOverrides.clear();
		if (this.lastMessage) {
			this.updateContent(this.lastMessage);
		}
	}

	setHiddenThinkingLabel(label: string): void {
		this.built = undefined;
		this.hiddenThinkingLabel = label;
		if (this.lastMessage) {
			this.updateContent(this.lastMessage);
		}
	}

	setOutputPad(padding: number): void {
		this.built = undefined;
		this.outputPad = padding;
		if (this.lastMessage) {
			this.updateContent(this.lastMessage);
		}
	}

	override renderShared(width: number): string[] {
		if (this.contentIsStale && this.lastMessage) this.buildContent(this.lastMessage);
		const lines = super.renderShared(width);
		if (this.hasToolCalls || lines.length === 0) {
			return lines;
		}
		// The marked lines are kept with the lines they were made from: same lines, same array (renderShared()).
		if (lines !== this.markedFrom) {
			const marked = lines.slice();
			marked[0] = OSC133_ZONE_START + marked[0];
			marked[marked.length - 1] = OSC133_ZONE_END + OSC133_ZONE_FINAL + marked[marked.length - 1];
			this.markedFrom = lines;
			this.markedLines = marked;
		}
		return this.markedLines;
	}

	/**
	 * renderShared()'s lines as LiveLines (renderLiveLines()): the children's live lines with the prompt markers added,
	 * changed from the first line the children's lines changed from (and the line that was last before).
	 */
	renderLive(width: number): LiveLines {
		if (this.contentIsStale && this.lastMessage) this.buildContent(this.lastMessage);
		const children = this.renderLiveChildren(width);
		const count = children.lines.length;
		if (this.hasToolCalls || count === 0) {
			return children;
		}
		let marked = this.markedLive;
		let start = 0;
		if (marked?.from === children) {
			const changed = children.changedSince(marked.revision);
			if (changed === Number.POSITIVE_INFINITY) return marked.lines;
			// (The line that was last loses its end marker, and the line now last gets it.)
			start = Math.min(changed, marked.length - 1, count - 1);
		} else {
			marked = { from: children, revision: 0, length: 0, lines: new LiveLineArray() };
			this.markedLive = marked;
		}
		start = Math.max(0, start);
		marked.lines.edit(start);
		for (let index = start; index < count; index++) {
			let line = children.lines[index]!;
			if (index === 0) line = OSC133_ZONE_START + line;
			if (index === count - 1) line = OSC133_ZONE_END + OSC133_ZONE_FINAL + line;
			marked.lines.lines.push(line);
		}
		marked.revision = children.revision;
		marked.length = count;
		return marked.lines;
	}

	updateContent(message: AssistantMessage, isStreaming = this.isStreaming): void {
		this.lastMessage = message;
		this.isStreaming = isStreaming;
		this.followTexts(message);
		reportRenderChange(this);
		// While a reply streams, every delta of it comes here, and the screen is drawn a few dozen times a second at most: the
		// components are made when it is (render()), from the latest message, not for each delta (and its text trimmed, a
		// copy of all of it so far, each time).
		if (isStreaming) {
			this.contentIsStale = true;
			return;
		}
		this.buildContent(message);
	}

	/**
	 * A Markdown component for `text`: the one built for it before when nothing it was made with has changed. While the
	 * message streams, one of the same kind built before is given the text instead of making a new one (it is made with the
	 * same theme, style and options): the text that grows is rendered from what that component rendered for it the time
	 * before (Markdown.renderLive()).
	 */
	private markdownFor(
		kind: MarkdownBlock["kind"],
		text: string,
		previous: MarkdownBlock[],
		create: () => Markdown,
	): Markdown {
		const matches = (block: MarkdownBlock) =>
			block.kind === kind && block.isStreaming === this.isStreaming && block.outputPad === this.outputPad;
		let index = previous.findIndex((block) => matches(block) && block.text === text);
		if (index === -1 && this.isStreaming) {
			index = previous.findIndex(matches);
			if (index !== -1) previous[index]!.component.setText(text);
		}
		const component = index === -1 ? create() : previous.splice(index, 1)[0]!.component;
		this.markdownBlocks.push({ kind, text, isStreaming: this.isStreaming, outputPad: this.outputPad, component });
		return component;
	}

	/** The texts of the text and thinking blocks of `message` (BlockText.follow(): for each delta, while a reply streams in). */
	private followTexts(message: AssistantMessage): void {
		const content = message.content;
		const blockTexts = this.blockTexts;
		blockTexts.length = content.length;
		for (let i = 0; i < content.length; i++) {
			const block = content[i];
			const raw = block.type === "text" ? block.text : block.type === "thinking" ? block.thinking : undefined;
			if (raw === undefined) {
				blockTexts[i] = undefined;
			} else {
				const known = blockTexts[i];
				if (known === undefined) blockTexts[i] = new BlockText(raw);
				else known.follow(raw);
			}
		}
	}

	/**
	 * What buildContent() adds to the content container for `message`, in order. While a reply streams in, a chunk usually
	 * changes only the texts of its Markdown components: the same plan but for those texts (planKey()).
	 */
	private planContent(message: AssistantMessage): ContentPart[] {
		const parts: ContentPart[] = [];
		const content = message.content;
		// Each text and thinking block trimmed (BlockText).
		this.followTexts(message);
		const trimmedTexts: Array<TrimmedText | undefined> = [];
		let hasVisibleContent = false;
		for (let i = 0; i < content.length; i++) {
			const trimmed = this.blockTexts[i]?.trim();
			trimmedTexts.push(trimmed);
			if (trimmed?.visible) hasVisibleContent = true;
		}

		if (hasVisibleContent) {
			parts.push({ kind: "spacer" });
		}

		// Render content in order
		let thinkingRunIndex = 0;
		for (let i = 0; i < content.length; i++) {
			const block = content[i];
			const trimmed = trimmedTexts[i];
			if (block.type === "text" && trimmed?.visible) {
				// Assistant text messages with no background - trim the text
				// Set paddingY=0 to avoid extra spacing before tool executions
				parts.push({ kind: "text", text: trimmed.trimmed });
			} else if (block.type === "thinking") {
				const thinkingBlocks: string[] = [];
				for (; i < content.length; i++) {
					if (content[i].type !== "thinking") {
						break;
					}
					const thinking = trimmedTexts[i];
					if (thinking?.visible) {
						thinkingBlocks.push(thinking.trimmed);
					}
				}
				i--;

				if (thinkingBlocks.length === 0) {
					continue;
				}

				// Add spacing only when another visible assistant content block follows.
				// This avoids a superfluous blank line before separately-rendered tool execution blocks.
				let hasVisibleContentAfter = false;
				for (let after = i + 1; after < content.length && !hasVisibleContentAfter; after++) {
					hasVisibleContentAfter = trimmedTexts[after]?.visible === true;
				}

				const runIndex = thinkingRunIndex++;
				const hidden = this.thinkingVisibilityOverrides.get(runIndex) ?? this.hideThinkingBlock;
				// (One block's text as it is: the same text as joined, and the strings it was trimmed to, see BlockText.)
				const text = hidden ? "" : thinkingBlocks.length === 1 ? thinkingBlocks[0] : thinkingBlocks.join("\n\n");
				parts.push({ kind: "thinking", runIndex, hidden, text });
				if (hasVisibleContentAfter) {
					parts.push({ kind: "spacer" });
				}
			}
		}

		// Check if incomplete/failed - show after partial content.
		// For aborted/error tool calls, tool execution components show the error.
		// Length stops can happen before a tool call is complete, so surface them here too.
		const hasToolCalls = message.content.some((c) => c.type === "toolCall");
		parts.push({ kind: "toolCalls", present: hasToolCalls });
		if (message.stopReason === "length") {
			parts.push({ kind: "spacer" });
			parts.push({ kind: "error", text: "Response was truncated before completion." });
		} else if (!hasToolCalls) {
			if (message.stopReason === "aborted") {
				const abortMessage =
					message.errorMessage && message.errorMessage !== "Request was aborted"
						? message.errorMessage
						: "Operation aborted";
				parts.push({ kind: "spacer" });
				parts.push({ kind: "error", text: abortMessage });
			} else if (message.stopReason === "error") {
				const errorMsg = message.errorMessage || "Unknown error";
				parts.push({ kind: "spacer" });
				parts.push({ kind: "error", text: `Error: ${errorMsg}` });
			}
		}
		return parts;
	}

	/**
	 * Everything the components buildContent() makes for `parts` depend on but the texts of the Markdown components: two
	 * plans with the same key give the same components, but for the text each Markdown component is given.
	 */
	private planKey(parts: readonly ContentPart[]): string {
		let key = `${this.isStreaming}|${this.outputPad}|${this.hiddenThinkingLabel}`;
		for (const part of parts) {
			if (part.kind === "thinking") key += `|thinking:${part.runIndex}:${part.hidden}`;
			else if (part.kind === "toolCalls") key += `|toolCalls:${part.present}`;
			else if (part.kind === "error") key += `|error:${part.text}`;
			else key += `|${part.kind}`;
		}
		return key;
	}

	private buildContent(message: AssistantMessage): void {
		this.contentIsStale = false;
		const parts = this.planContent(message);
		const key = this.planKey(parts);
		// A reply streaming in, built the last time with the same plan, the same theme and nothing invalidated since: the same
		// components, each Markdown component given its text as markdownFor() gives it (the text changed, or none).
		const built = this.built;
		if (this.isStreaming && built !== undefined && built.key === key && built.theme === theme) {
			let index = 0;
			for (const part of parts) {
				if (part.kind !== "text" && (part.kind !== "thinking" || part.hidden)) continue;
				const block = this.markdownBlocks[index++]!;
				if (block.text !== part.text) {
					block.text = part.text;
					block.component.setText(part.text);
				}
			}
			return;
		}
		this.built = this.isStreaming ? { key, theme } : undefined;

		const previousBlocks = this.markdownBlocks;
		this.markdownBlocks = [];
		// Clear content container
		this.contentContainer.clear();

		for (const part of parts) {
			if (part.kind === "spacer") {
				this.contentContainer.addChild(new Spacer(1));
			} else if (part.kind === "text") {
				const text = part.text;
				this.contentContainer.addChild(
					this.markdownFor(
						"text",
						text,
						previousBlocks,
						() =>
							new Markdown(text, this.outputPad, 0, this.markdownTheme, undefined, {
								transform: createMarkdownTransform("assistant", this.isStreaming, this.markdownTransformers),
								complete: !this.isStreaming,
							}),
					),
				);
			} else if (part.kind === "thinking") {
				const { runIndex, hidden, text: thinkingText } = part;
				const thinkingComponent = hidden
					? new Text(theme.italic(theme.fg("thinkingText", this.hiddenThinkingLabel)), this.outputPad, 0)
					: this.markdownFor(
							"thinking",
							thinkingText,
							previousBlocks,
							() =>
								new Markdown(
									thinkingText,
									this.outputPad,
									0,
									this.markdownTheme,
									{
										color: thinkingTextColor,
										italic: true,
									},
									{
										transform: createMarkdownTransform(
											"assistant-thinking",
											this.isStreaming,
											this.markdownTransformers,
										),
										complete: !this.isStreaming,
									},
								),
						);
				this.contentContainer.addChild(
					new MouseRegion(thinkingComponent, (event) => {
						if (event.type !== "click" || event.button !== "left") return undefined;
						this.thinkingVisibilityOverrides.set(runIndex, !hidden);
						if (this.lastMessage) this.updateContent(this.lastMessage);
						return { handled: true };
					}),
				);
			} else if (part.kind === "toolCalls") {
				this.hasToolCalls = part.present;
			} else {
				// (An error's text can come from the provider's response: escape sequences in it would act on the terminal.)
				this.contentContainer.addChild(
					new Text(theme.fg("error", sanitizeTerminalText(part.text)), this.outputPad, 0),
				);
			}
		}
	}
}

markReportsChanges(AssistantMessageComponent);
markLiveRender(AssistantMessageComponent.prototype.renderShared, function (this: Component, width: number) {
	return (this as AssistantMessageComponent).renderLive(width);
});
