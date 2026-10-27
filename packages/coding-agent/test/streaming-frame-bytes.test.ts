import type { AssistantMessage } from "@earendil-works/pi-ai";
import { Container, type Terminal, Text, TuiAltScreen, TuiMainScreen } from "@earendil-works/pi-tui";
import { beforeAll, describe, expect, test } from "vitest";
import type { MarkdownTransformContext, MarkdownTransformer } from "../src/core/extensions/types.ts";
import { createChatViewport } from "../src/modes/interactive/chat-viewport.ts";
import { AssistantMessageComponent } from "../src/modes/interactive/components/assistant-message.ts";
import { createMermaidMarkdownTransformer } from "../src/modes/interactive/components/mermaid.ts";
import { UserMessageComponent } from "../src/modes/interactive/components/user-message.ts";
import { getMarkdownTheme, initTheme, theme } from "../src/modes/interactive/theme/theme.ts";

// The bytes Pi writes to the terminal for each frame while replies stream in, with the Mermaid transformer as the
// interactive mode has it (one for all messages, which keeps what it found in the text it was given last), against the
// same transformer made anew for every text (which keeps nothing, and so reads every text in full). Random replies of
// every kind of block, in random chunks, several in a transcript, with the terminal resized while they stream, in the
// fullscreen and the regular renderer: every frame must be the same bytes.

/** A terminal that keeps what is written to it, frame by frame (take()). */
class CaptureTerminal implements Terminal {
	private written = "";
	private onResize?: () => void;
	private cols: number;
	private lines: number;

	constructor(columns: number, rows: number) {
		this.cols = columns;
		this.lines = rows;
	}

	start(_onInput: (data: string) => void, onResize: () => void): void {
		this.onResize = onResize;
	}
	stop(): void {
		this.onResize = undefined;
	}
	async drainInput(): Promise<void> {}
	write(data: string): void {
		this.written += data;
	}
	get columns(): number {
		return this.cols;
	}
	get rows(): number {
		return this.lines;
	}
	get kittyProtocolActive(): boolean {
		return false;
	}
	moveBy(lines: number): void {
		this.written += `<move ${lines}>`;
	}
	hideCursor(): void {
		this.written += "<hide>";
	}
	showCursor(): void {
		this.written += "<show>";
	}
	clearLine(): void {
		this.written += "<clearLine>";
	}
	clearFromCursor(): void {
		this.written += "<clearFrom>";
	}
	clearScreen(): void {
		this.written += "<clearScreen>";
	}
	setTitle(title: string): void {
		this.written += `<title ${title}>`;
	}
	setProgress(): void {}
	setProgramStatus(): void {}

	resize(columns: number, rows: number): void {
		this.cols = columns;
		this.lines = rows;
		this.onResize?.();
	}

	/** What was written since the last call. */
	take(): string {
		const written = this.written;
		this.written = "";
		return written;
	}
}

function random(seed: number): () => number {
	let state = seed >>> 0 || 1;
	return () => {
		state = (Math.imul(state ^ (state >>> 15), 2246822507) + 0x9e3779b9) >>> 0;
		return state / 4294967296;
	};
}

const MERMAID_BLOCKS = [
	"```mermaid\nflowchart LR\n  A[Start] --> B[Done]\n```\n",
	"```MerMaid\ngraph TD\n  X --> Y\n  Y --> Z[End]\n```\n",
	"```mermaid\nsequenceDiagram\n  Alice->>Bob: Hi\n```\n",
	'```mermaid\npie\n  title Pets\n  "Dogs" : 4\n```\n',
	"```mermaid\nflowchart LR\n  A[A very long label that does not fit in a narrow terminal at all] --> B\n```\n",
];

/** A random reply: blocks of every kind, the word "mermaid" in prose and code fences, wide characters, tabs, escapes. */
function randomReply(next: () => number): string {
	const pick = <T>(items: readonly T[]): T => items[Math.floor(next() * items.length)]!;
	const words = [
		"parse",
		"token",
		"**bold**",
		"*it*",
		"`code`",
		"~~gone~~",
		"日本語",
		"😀",
		"naïve",
		"mermaid",
		"MERMAID",
	];
	const sentence = (): string => {
		let text = "";
		const count = 3 + Math.floor(next() * 14);
		for (let i = 0; i < count; i++) text += `${i === 0 ? "" : " "}${pick(words)}`;
		return text;
	};
	const blocks: Array<() => string> = [
		() => `${"#".repeat(1 + Math.floor(next() * 3))} ${sentence()}\n\n`,
		() => `${sentence()}\n${sentence()}\n\n`,
		() => `- ${sentence()}\n- ${sentence()}\n  1. ${sentence()}\n  2. ${sentence()}\n\n`,
		() =>
			`\`\`\`ts\nexport function f(input: string): number {\n\treturn input.length; // ${sentence()}\n}\n\`\`\`\n\n`,
		() => `\`\`\`python\ndef g():\n    return "${pick(words)}"\n\`\`\`\n\n`,
		() => pick(MERMAID_BLOCKS),
		() => `| a | b |\n|---|---|\n| ${pick(words)} | ${pick(words)} |\n| 一 | ${sentence()} |\n\n`,
		() => `> ${sentence()}\n> > ${sentence()}\n> - ${pick(words)}\n\n`,
		() => `tab\there\tand ${sentence()}\n\n`,
		() => `color \x1b[31mred\x1b[0m and a cut \x1b[3`,
		() => `mer${pick(["", "\n", " "])}maid ${pick(["Mer", "mer"])}${pick(["maid", "MAID"])} merm\n\n`,
		() => "text\r\nwith CRLF\r\n\n",
	];
	let reply = "";
	const count = 2 + Math.floor(next() * 8);
	for (let i = 0; i < count; i++) reply += pick(blocks)();
	return reply;
}

function assistantMessage(text: string, thinking?: string): AssistantMessage {
	return {
		role: "assistant",
		content: [...(thinking === undefined ? [] : [{ type: "thinking" as const, thinking }]), { type: "text", text }],
		api: "openai-responses",
		provider: "openai",
		model: "gpt-4o-mini",
		usage: {
			input: 0,
			output: 0,
			cacheRead: 0,
			cacheWrite: 0,
			totalTokens: 0,
			cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
		},
		stopReason: "stop",
		timestamp: 0,
	};
}

type Renderer = "fullscreen" | "regular";

/**
 * Streams the replies of `seed` into a transcript drawn by `renderer`, with `transformer` given to every message, and
 * returns what was written to the terminal for each frame.
 */
function streamFrames(seed: number, renderer: Renderer, transformer: MarkdownTransformer): string[] {
	const next = random(seed);
	const terminal = new CaptureTerminal(40 + Math.floor(next() * 120), 8 + Math.floor(next() * 40));
	const document = new Container();
	const markdownTheme = getMarkdownTheme();
	const transformers = [transformer];
	let tui: TuiAltScreen | TuiMainScreen;
	if (renderer === "fullscreen") {
		const alt = new TuiAltScreen(terminal, false, undefined, { mouse: false });
		const viewport = createChatViewport({
			document,
			pendingMessages: new Container(),
			status: new Text("status", 1, 0),
			editor: new Text("> editor", 1, 0),
			footer: new Text("footer", 1, 0),
		});
		alt.setLayoutRoot(viewport.root);
		tui = alt;
	} else {
		const main = new TuiMainScreen(terminal, false);
		main.addChild(document);
		main.addChild(new Text("> editor", 1, 0));
		tui = main;
	}
	const frames: string[] = [];
	const frame = (): void => {
		tui.renderNow();
		frames.push(terminal.take());
	};
	tui.start();
	frames.push(terminal.take());
	document.addChild(new UserMessageComponent("a question that names mermaid once", markdownTheme, 1, transformers));
	const replies = 1 + Math.floor(next() * 3);
	for (let r = 0; r < replies; r++) {
		const text = randomReply(next);
		const thinking = next() < 0.3 ? randomReply(next) : undefined;
		const component = new AssistantMessageComponent(undefined, false, markdownTheme, "Thinking...", 1, transformers);
		document.addChild(component);
		const total = (thinking?.length ?? 0) + text.length;
		let message = assistantMessage("");
		for (let length = 0; length < total; ) {
			length = Math.min(total, length + 1 + Math.floor(next() * (next() < 0.2 ? 80 : 24)));
			message =
				thinking !== undefined && length <= thinking.length
					? assistantMessage("", thinking.slice(0, length))
					: assistantMessage(text.slice(0, length - (thinking?.length ?? 0)), thinking);
			// (Sometimes a text that is not the one before with more after it: what the transformer kept is of no use.)
			if (next() < 0.03) message = assistantMessage(`${randomReply(next)}${text.slice(0, 10)}`);
			component.updateContent(message, true);
			if (next() < 0.04) terminal.resize(30 + Math.floor(next() * 130), 6 + Math.floor(next() * 44));
			// (Sometimes two chunks before a frame.)
			if (next() < 0.8) frame();
		}
		component.updateContent(message, false);
		frame();
		document.addChild(new UserMessageComponent(`next question ${r}`, markdownTheme, 1, transformers));
	}
	frame();
	tui.stop();
	frames.push(terminal.take());
	return frames;
}

/** The transformer as the interactive mode makes it (mermaid.ts), one for every message. */
function sharedTransformer(): MarkdownTransformer {
	return createMermaidMarkdownTransformer({ getMode: () => "streaming", theme });
}

/** The same transformer made anew for every text: it reads all of each text. */
const freshTransformer: MarkdownTransformer = (markdown: string, context: MarkdownTransformContext) =>
	createMermaidMarkdownTransformer({ getMode: () => "streaming", theme })(markdown, context);

describe("frames written while replies stream in", () => {
	beforeAll(() => {
		initTheme("dark");
	});

	for (const renderer of ["fullscreen", "regular"] as const) {
		test(`${renderer}: the same bytes with the Mermaid transformer that keeps the text it searched`, () => {
			let frames = 0;
			let diagrams = 0;
			for (let seed = 1; seed <= 60; seed++) {
				const expected = streamFrames(seed, renderer, freshTransformer);
				const actual = streamFrames(seed, renderer, sharedTransformer());
				if (actual.length !== expected.length || actual.some((bytes, i) => bytes !== expected[i])) {
					const at = actual.findIndex((bytes, i) => bytes !== expected[i]);
					expect(actual[at], `seed ${seed}, frame ${at} of ${expected.length}`).toBe(expected[at]);
					expect(actual.length, `seed ${seed}`).toBe(expected.length);
				}
				frames += expected.length;
				diagrams += expected.filter((bytes) => bytes.includes("▶")).length;
			}
			// (The streams draw diagrams: the transformer's work is in the frames compared.)
			expect(diagrams).toBeGreaterThan(20);
			expect(frames).toBeGreaterThan(3000);
		});
	}
});

describe("Mermaid transformer kept across texts", () => {
	test("finds the word as a transformer that keeps nothing, for random sequences of texts", () => {
		const context: MarkdownTransformContext = { availableWidth: 100, isStreaming: true, messageType: "assistant" };
		const pieces = [
			"m",
			"e",
			"r",
			"mai",
			"d",
			"Mer",
			"MAID",
			" ",
			"\n",
			"x",
			"mermaid",
			"```mermaid\nflowchart LR\n  A --> B\n```\n",
		];
		for (let seed = 1; seed <= 300; seed++) {
			const next = random(seed);
			const kept = sharedTransformer();
			let text = "";
			for (let step = 0; step < 60; step++) {
				const roll = next();
				if (roll < 0.05) text = "";
				else if (roll < 0.1) text = text.slice(0, Math.floor(next() * text.length));
				else text += pieces[Math.floor(next() * pieces.length)]!;
				expect(kept(text, context), `seed ${seed}, step ${step}`).toBe(freshTransformer(text, context));
			}
		}
	});
});
