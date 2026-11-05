import { Container, Markdown, type MarkdownTheme, markReportsChanges } from "@earendil-works/pi-tui";
import type { MarkdownTransformer } from "../../../core/extensions/types.ts";
import { getMarkdownTheme, theme } from "../theme/theme.ts";
import { mentionsAny } from "./assistant-message.ts";
import { createMarkdownTransform } from "./markdown-transform.ts";

const OSC133_ZONE_START = "\x1b]133;A\x07";
const OSC133_ZONE_END = "\x1b]133;B\x07";
const OSC133_ZONE_FINAL = "\x1b]133;C\x07";

/**
 * Component that renders a user message
 */
export class UserMessageComponent extends Container {
	private text: string;
	private markdownTheme: MarkdownTheme;
	private outputPad: number;
	private markdownTransformers: readonly MarkdownTransformer[];
	private messageId?: string;
	private timestamp?: string;
	/** The children's lines with the prompt markers added, and the lines they were added to. */
	private markedFrom?: string[];
	private markedLines: string[] = [];

	constructor(
		text: string,
		markdownTheme: MarkdownTheme = getMarkdownTheme(),
		outputPad = 1,
		markdownTransformers: readonly MarkdownTransformer[] = [],
		messageId?: string,
		timestamp?: string,
	) {
		super();
		this.text = text;
		this.markdownTheme = markdownTheme;
		this.outputPad = outputPad;
		this.markdownTransformers = markdownTransformers;
		this.messageId = messageId;
		this.timestamp = timestamp;
		this.rebuild();
	}

	/** Whether this message may show code highlighted in one of `languages` (lowercase names): AssistantMessageComponent.mayHighlight(). */
	mayHighlight(languages: ReadonlySet<string>): boolean {
		return this.markdownTransformers.length > 0 || mentionsAny(this.text, languages);
	}

	setOutputPad(padding: number): void {
		this.outputPad = padding;
		this.rebuild();
	}

	private rebuild(): void {
		this.clear();
		// The Markdown pads and colors its own background: a Box around it would keep a second full-width copy of every
		// line, with identical output.
		this.addChild(
			new Markdown(
				this.text,
				this.outputPad,
				1,
				this.markdownTheme,
				{
					color: (content: string) => theme.fg("userMessageText", content),
					bgColor: (content: string) => theme.bg("userMessageBg", content),
				},
				{
					preserveOrderedListMarkers: true,
					preserveBackslashEscapes: true,
					transform: createMarkdownTransform(
						"user",
						() => false,
						this.markdownTransformers,
						this.messageId,
						this.timestamp,
					),
				},
			),
		);
	}

	override renderShared(width: number): string[] {
		const lines = super.renderShared(width);
		if (lines.length === 0) {
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
}

markReportsChanges(UserMessageComponent);
