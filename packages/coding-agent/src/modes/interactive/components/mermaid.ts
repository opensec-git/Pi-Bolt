import { Marked, startsWithText, type Token } from "@earendil-works/pi-tui";
import { type MermaidArt, render, type Span } from "grok-mermaid";
import type { MarkdownTransformer } from "../../../core/extensions/types.ts";
import type { MermaidRenderingMode } from "../../../core/settings-manager.ts";
import type { Theme } from "../theme/theme.ts";

// Made for the first text that names Mermaid: most sessions show none.
let markdownParser: Marked | undefined;
/** How many characters of a text a match of "mermaid" that goes on into text added after it can start in. */
const MERMAID_TAIL = "mermaid".length - 1;

interface MermaidTransformerOptions {
	getMode: () => MermaidRenderingMode;
	theme?: Theme;
}

function isMermaid(token: Token): token is Token & { type: "code"; text: string; lang?: string } {
	return token.type === "code" && token.lang?.trim().split(/\s+/, 1)[0]?.toLowerCase() === "mermaid";
}

function codeSpan(line: string): string {
	// Encode each diagram row as inline code (` ... `) so Markdown preserves its spacing and
	// box-drawing characters. Use a non-breaking space for blank rows because an
	// empty code span has no visible height.
	const content = line || "\u00a0";
	// CommonMark code spans use matching backtick delimiters, so choose one
	// longer than any backtick run in the content (``hel`lo`` -> <code>hel`lo</code>).
	// If the content starts or ends with a backtick, separating it from the
	// delimiter with a space keeps that backtick as content; CommonMark removes
	// the padding when rendering (`` `edge` `` -> <code>`edge`</code>).
	// Mermaid labels can preserve backticks, for example:
	//   `┌──────────────┐    ┌──────────────┐`
	// ```│ plain ` tick ├───▶│ two `` ticks │```
	//   `└──────────────┘    └──────────────┘`
	const longestBacktickRun = Math.max(0, ...Array.from(content.matchAll(/`+/g), (match) => match[0].length));
	const fence = "`".repeat(longestBacktickRun + 1);
	const padding = content.startsWith("`") || content.endsWith("`") ? " " : "";
	return `${fence}${padding}${content}${padding}${fence}`;
}

function styleSpan(span: Span, theme: Theme): string {
	switch (span.cls) {
		case "border":
			return theme.fg("borderMuted", span.text);
		case "text":
			return theme.fg("text", span.text);
		case "edge":
			return theme.fg("accent", span.text);
		case "edgeLabel":
			return theme.fg("muted", span.text);
		case "title":
			return theme.fg("accent", theme.bold(span.text));
		case "none":
			return span.text;
	}
}

function themedLines(art: MermaidArt, theme: Theme): string[] {
	return art.styled.map((row) => row.map((span) => styleSpan(span, theme)).join(""));
}

/** Create a transformer that replaces top-level Mermaid code blocks with Unicode terminal diagrams. */
export function createMermaidMarkdownTransformer(options: MermaidTransformerOptions): MarkdownTransformer {
	// The last text found not to name Mermaid, and its last characters that a match could start in. A message that streams
	// in is that text with text added: only those characters and the added text are searched. The text is compared with
	// startsWithText(), and the added text taken from its end: a message streaming in is the text before with the added
	// text joined to it, which the engine copies into one string when all of it is read (startsWith(), or a slice that
	// crosses where the two meet), a copy of the whole message for each chunk.
	let withoutMermaid: { text: string; tail: string } | undefined;
	const namesMermaid = (markdown: string): boolean => {
		const known = withoutMermaid;
		let found: boolean;
		let tail: string;
		if (known !== undefined && startsWithText(markdown, known.text)) {
			const searched = known.tail + markdown.slice(known.text.length);
			found = /mermaid/i.test(searched);
			tail = searched.slice(Math.max(0, searched.length - MERMAID_TAIL));
		} else {
			found = /mermaid/i.test(markdown);
			tail = markdown.slice(Math.max(0, markdown.length - MERMAID_TAIL));
		}
		withoutMermaid = found ? undefined : { text: markdown, tail };
		return found;
	};
	return (markdown, context) => {
		const mode = options.getMode();
		if (
			mode === "off" ||
			context.messageType === "assistant-thinking" ||
			(context.isStreaming && mode !== "streaming")
		) {
			return markdown;
		}
		// A Mermaid block names its language: without the word there is none, and no need to lex the text (again for every
		// chunk of a message that streams in) to find that out.
		if (!namesMermaid(markdown)) {
			return markdown;
		}

		markdownParser ??= new Marked();
		return markdownParser
			.lexer(markdown)
			.map((token) => {
				if (!isMermaid(token)) return token.raw;
				const art = render(token.text);
				if (!art || art.width > context.availableWidth) return token.raw;
				if (!context.isStreaming && art.warnings.length > 0) {
					const suffix = art.warnings.length > 1 ? ` (+${art.warnings.length - 1} more)` : "";
					const warning = `Mermaid diagram not rendered: ${art.warnings[0]}${suffix}`;
					const styledWarning = options.theme ? options.theme.fg("warning", warning) : warning;
					return `${token.raw}\n${codeSpan(styledWarning)}  \n`;
				}
				const lines = options.theme ? themedLines(art, options.theme) : art.plain;
				// Markdown hard breaks keep every diagram row on its own line.
				return `${lines.map(codeSpan).join("  \n")}\n`;
			})
			.join("");
	};
}
