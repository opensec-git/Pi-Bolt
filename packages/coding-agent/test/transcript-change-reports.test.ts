import type { AssistantMessage } from "@earendil-works/pi-ai";
import {
	type Component,
	Container,
	ScrollView,
	Spacer,
	setChangeReportsForTest,
	Text,
	type TUI,
	VStack,
} from "@earendil-works/pi-tui";
import { Type } from "typebox";
import { afterEach, beforeAll, describe, expect, test } from "vitest";
import { renderLayoutFrame } from "../../tui/src/layout.ts";
import type { ToolDefinition } from "../src/core/extensions/types.ts";
import { createShellRenderers } from "../src/core/tools/renderers/bash.ts";
import { AssistantMessageComponent } from "../src/modes/interactive/components/assistant-message.ts";
import { ThemedText } from "../src/modes/interactive/components/themed-text.ts";
import { ToolExecutionComponent } from "../src/modes/interactive/components/tool-execution.ts";
import { TranscriptContainer } from "../src/modes/interactive/components/transcript-container.ts";
import { UserMessageComponent } from "../src/modes/interactive/components/user-message.ts";
import { initTheme, setTheme, theme } from "../src/modes/interactive/theme/theme.ts";

// The transcript's containers render again only the entries that reported a change (markReportsChanges()). Two
// transcripts get the same changes, as Pi and extensions make them; one is rendered so, the other with every entry
// rendered on every frame (as before). Every frame of the two must be the same.

function random(seed: number): () => number {
	let state = seed >>> 0 || 1;
	return () => {
		state = (Math.imul(state ^ (state >>> 15), 2246822507) + 0x9e3779b9) >>> 0;
		return state / 4294967296;
	};
}

type Draw = (count: number) => number;

const WORDS = [
	"alpha",
	"beta",
	"**bold**",
	"`code`",
	"gamma",
	"a-long-word-for-wrapping",
	"\n\n",
	"- item\n",
	"```ts\nx()\n```\n",
];

function words(draw: Draw, count: number): string {
	let text = "word";
	for (let i = 0; i < count; i++) text += ` ${WORDS[draw(WORDS.length)]}`;
	return text;
}

function assistant(text: string, thinking: string, toolCall: boolean): AssistantMessage {
	return {
		role: "assistant",
		content: [
			...(thinking ? [{ type: "thinking" as const, thinking }] : []),
			{ type: "text", text },
			...(toolCall ? [{ type: "toolCall" as const, id: "call", name: "bash", arguments: { command: "ls" } }] : []),
		],
		api: "openai-completions",
		provider: "fake",
		model: "fake-model",
		usage: {
			input: 0,
			output: 0,
			cacheRead: 0,
			cacheWrite: 0,
			totalTokens: 0,
			cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
		},
		stopReason: toolCall ? "toolUse" : "stop",
		timestamp: 0,
	};
}

/** What an extension's components read: changed by the extension without telling them. */
interface ExtensionState {
	status: string[];
}

/** An extension's component that renders from its own state and does not report its changes. */
class StatusComponent implements Component {
	private readonly state: ExtensionState;
	private readonly index: number;
	constructor(state: ExtensionState, index: number) {
		this.state = state;
		this.index = index;
	}
	render(width: number): string[] {
		return [`status: ${this.state.status[this.index] ?? ""}`.slice(0, width)];
	}
	invalidate(): void {}
}

/** An extension's component that changes the array it returned, in place. */
class InPlaceComponent implements Component {
	private readonly state: ExtensionState;
	private readonly index: number;
	private readonly lines: string[] = [];
	constructor(state: ExtensionState, index: number) {
		this.state = state;
		this.index = index;
	}
	render(width: number): string[] {
		this.lines.length = 0;
		this.lines.push(`in place: ${this.state.status[this.index] ?? ""}`.slice(0, width), "second line");
		return this.lines;
	}
	invalidate(): void {}
}

interface Transcript {
	extension: ExtensionState;
	/** Texts an extension's renderer returned and keeps, to change later. */
	kept: Text[];
	transcript: TranscriptContainer;
	document: Container;
	assistants: AssistantMessageComponent[];
	tools: ToolExecutionComponent[];
	streaming?: { component: AssistantMessageComponent; text: string; thinking: string };
	scroll: ScrollView;
	root: VStack;
	hideThinking: boolean;
	expanded: boolean;
}

const ui = { requestRender: () => {} } as unknown as TUI;

function extensionTool(transcript: Transcript, kind: number): ToolDefinition {
	const base: ToolDefinition = {
		name: `ext${kind}`,
		label: `ext${kind}`,
		description: "extension tool",
		parameters: Type.Any(),
		execute: async () => ({ content: [{ type: "text", text: "ok" }], details: {} }),
	};
	const state = transcript.extension;
	if (kind === 0) {
		return {
			...base,
			renderCall: () => new Text("ext call", 0, 0),
			renderResult: () => {
				state.status.push("idle");
				return new StatusComponent(state, state.status.length - 1);
			},
		};
	}
	if (kind === 1) {
		return {
			...base,
			renderCall: (_args, _theme, context) => {
				const text = (context.lastComponent as Text | undefined) ?? new Text("", 0, 0);
				text.setText("kept call");
				if (!transcript.kept.includes(text)) transcript.kept.push(text);
				return text;
			},
		};
	}
	if (kind === 2) {
		return {
			...base,
			renderResult: () => {
				state.status.push("in place");
				return new InPlaceComponent(state, state.status.length - 1);
			},
		};
	}
	return { ...base, renderShell: "self", renderCall: () => new Text("self-rendered call", 1, 0) };
}

function makeTranscript(): Transcript {
	const transcript = new TranscriptContainer();
	const document = new Container();
	document.addChild(new Text("header", 1, 0));
	document.addChild(transcript);
	const scroll = new ScrollView(document, { follow: "end", scrollbar: "always" });
	const root = new VStack([
		{ component: scroll, basis: 0, grow: 1, shrink: 1, minSize: 1 },
		{ component: new Text("dock", 0, 0), basis: "auto", grow: 0, shrink: 0, minSize: 1 },
	]);
	return {
		extension: { status: [] },
		kept: [],
		transcript,
		document,
		assistants: [],
		tools: [],
		scroll,
		root,
		hideThinking: false,
		expanded: false,
	};
}

function pick<T>(items: T[], draw: Draw): T | undefined {
	return items.length === 0 ? undefined : items[draw(items.length)];
}

const shell = createShellRenderers("$");

type Step = (t: Transcript, draw: Draw) => void;

const STEPS: Step[] = [
	(t, draw) => t.transcript.addChild(new UserMessageComponent(words(draw, 3 + draw(10)))),
	(t, draw) => {
		const component = new AssistantMessageComponent(
			assistant(words(draw, 5 + draw(20)), draw(2) ? words(draw, 4) : "", draw(2) === 0),
			t.hideThinking,
		);
		t.assistants.push(component);
		t.transcript.addChild(component);
	},
	// A reply that streams in, then ends.
	(t, draw) => {
		if (!t.streaming) {
			const component = new AssistantMessageComponent(undefined, t.hideThinking);
			t.assistants.push(component);
			t.transcript.addChild(component);
			t.streaming = { component, text: "", thinking: words(draw, 2) };
		}
		t.streaming.text += ` ${words(draw, 1 + draw(3))}`;
		t.streaming.component.updateContent(assistant(t.streaming.text, t.streaming.thinking, false), true);
	},
	(t, draw) => {
		if (!t.streaming) return;
		t.streaming.text += ` ${words(draw, 1 + draw(3))}`;
		t.streaming.component.updateContent(assistant(t.streaming.text, t.streaming.thinking, false), true);
	},
	(t) => {
		if (!t.streaming) return;
		t.streaming.component.updateContent(assistant(t.streaming.text, t.streaming.thinking, false), false);
		t.streaming = undefined;
	},
	// A bash call, its partial and final output.
	(t, draw) => {
		const tool = new ToolExecutionComponent("bash", "id", { command: `ls ${draw(9)}` }, {}, shell, ui, "/tmp");
		tool.setExpanded(t.expanded);
		t.tools.push(tool);
		// (Sometimes in the middle, as a tool row goes before the streaming message.)
		const children = t.transcript.children;
		if (draw(3) === 0 && children.length > 0) children.splice(children.length - 1, 0, tool);
		else t.transcript.addChild(tool);
	},
	(t, draw) => {
		const tool = pick(t.tools, draw);
		const lines = Array.from({ length: 1 + draw(12) }, () => words(draw, 3)).join("\n");
		tool?.updateResult({ content: [{ type: "text", text: lines }], isError: draw(4) === 0 }, draw(2) === 0);
	},
	// Tools of extensions: a component that changes unreported, a component the extension keeps and changes, one that
	// changes its array in place, a self-rendered one.
	(t, draw) => {
		const tool = new ToolExecutionComponent("ext", "id", {}, {}, extensionTool(t, draw(4)), ui, "/tmp");
		tool.updateResult({ content: [{ type: "text", text: "ok" }], isError: false });
		t.tools.push(tool);
		t.transcript.addChild(tool);
	},
	(t, draw) => {
		if (t.extension.status.length > 0) t.extension.status[draw(t.extension.status.length)] = words(draw, draw(3));
	},
	(t, draw) => pick(t.kept, draw)?.setText(`kept: ${words(draw, 2)}`),
	// Expand or collapse one tool (a click) or all of them (ctrl+o); hide or show thinking.
	(t, draw) => pick(t.tools, draw)?.setExpanded(draw(2) === 0),
	(t) => {
		t.expanded = !t.expanded;
		for (const tool of t.tools) tool.setExpanded(t.expanded);
	},
	(t) => {
		t.hideThinking = !t.hideThinking;
		for (const component of t.assistants) component.setHideThinkingBlock(t.hideThinking);
	},
	(t, draw) => {
		t.transcript.addChild(new Spacer(1));
		t.transcript.addChild(new ThemedText(() => theme.fg("warning", `notice ${draw(10)}`), 1, 0));
	},
	// A theme change: the theme, then everything invalidated (ui.invalidate()).
	(t, draw) => {
		setTheme(draw(2) === 0 ? "light" : "dark");
		t.document.invalidate();
	},
	// Some entries invalidated (highlighting languages loaded).
	(t, draw) => {
		const keep = draw(2);
		t.transcript.invalidateOnly(
			(child) => child instanceof ToolExecutionComponent || keep === 0,
			() => t.document.invalidate(),
		);
	},
	(t, draw) => {
		t.scroll.scrollBy(draw(2) === 0 ? -(1 + draw(15)) : 1 + draw(15));
	},
	() => {},
];

describe("transcript frames with change reports", () => {
	beforeAll(() => initTheme("dark"));
	afterEach(() => {
		setChangeReportsForTest(true);
		initTheme("dark");
	});

	for (let seed = 1; seed <= 12; seed++) {
		test(`every frame is the frame of a transcript rendered in full (seed ${seed})`, () => {
			const next = random(seed * 6151);
			const reported = makeTranscript();
			const full = makeTranscript();
			const log: string[] = [];
			for (let step = 0; step < 160; step++) {
				const index = Math.floor(next() * STEPS.length);
				const draws: number[] = [];
				STEPS[index]!(reported, (count) => {
					const value = Math.floor(next() * count);
					draws.push(value);
					return value;
				});
				let replayed = 0;
				STEPS[index]!(full, () => draws[replayed++]!);
				log.push(`${index}(${draws.join(",")})`);
				const width = [100, 100, 100, 70, 140][Math.floor(next() * 5)]!;
				const height = 10 + Math.floor(next() * 20);
				const frame = renderLayoutFrame(reported.root, width, height, () => {});
				setChangeReportsForTest(false);
				const want = renderLayoutFrame(full.root, width, height, () => {});
				setChangeReportsForTest(true);
				const label = `seed ${seed} step ${step}: ${log.slice(-10).join(" ")}`;
				expect(frame.lines, label).toEqual(want.lines);
				if (next() < 0.1) {
					// (What render() hands out is the caller's own: changing it changes nothing drawn.)
					// (Container.render() returns a copy, which the caller may change.)
					const lines = reported.document.render(width - 1) as string[];
					lines.length = 0;
				}
			}
		});
	}
});
