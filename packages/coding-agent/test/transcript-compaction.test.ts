import type { AssistantMessage } from "@earendil-works/pi-ai";
import { Container, type TUI, type TuiMouseEvent } from "@earendil-works/pi-tui";
import { beforeAll, describe, expect, it } from "vitest";
import { withBuiltInRenderers } from "../src/core/tools/renderers/index.ts";
import { AssistantMessageComponent } from "../src/modes/interactive/components/assistant-message.ts";
import { ToolExecutionComponent } from "../src/modes/interactive/components/tool-execution.ts";
import { initTheme } from "../src/modes/interactive/theme/theme.ts";

// A settled transcript entry that compact()s keeps the lines it rendered last and lets go of the components it rendered them
// from; whatever happens next, it must draw exactly what an entry that never compacted draws.

// SAFETY: ToolExecutionComponent only calls ui.requestRender(), which this stub implements.
const ui = { requestRender() {} } as TUI;

function assistantMessage(): AssistantMessage {
	const code = ["```ts", "const alpha = beta(1);", "const gamma = delta(2);", "```"].join("\n");
	return {
		role: "assistant",
		content: [
			{
				type: "thinking",
				thinking: "Thinking about **the plan** for a while, then more.",
				thinkingSignature: "sig",
			},
			{ type: "text", text: `## Result\n\n- **one**: first \`item()\`\n- **two**: second\n\n${code}\n\nDone.` },
			{ type: "toolCall", id: "call_1", name: "bash", arguments: { command: "echo hi" } },
		],
		api: "openai-completions",
		provider: "fake",
		model: "fake-model",
		usage: {
			input: 1,
			output: 1,
			cacheRead: 0,
			cacheWrite: 0,
			totalTokens: 2,
			cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
		},
		stopReason: "toolUse",
		timestamp: 1,
	};
}

function bashRow(): ToolExecutionComponent {
	const row = new ToolExecutionComponent(
		"bash",
		"call_1",
		{ command: "seq 1 30" },
		{ compactable: true },
		withBuiltInRenderers("bash", undefined),
		ui,
		"/tmp",
	);
	row.updateResult({
		content: [{ type: "text", text: Array.from({ length: 30 }, (_, i) => `line ${i + 1}`).join("\n") }],
		isError: false,
		durationMs: 1234,
	});
	return row;
}

function click(width: number, y: number): TuiMouseEvent {
	return {
		type: "click",
		button: "left",
		x: 2,
		y,
		screenX: 2,
		screenY: y,
		width,
		height: 100,
		shift: false,
		alt: false,
		ctrl: false,
		clickCount: 1,
	};
}

/** The entry in a container, as the transcript holds it, and the same entry made again that never compacts. */
function pair<T extends AssistantMessageComponent | ToolExecutionComponent>(make: () => T) {
	const entry = make();
	const twin = make();
	const transcript = new Container();
	transcript.addChild(entry);
	const twinTranscript = new Container();
	twinTranscript.addChild(twin);
	const same = (width: number) => {
		const lines = transcript.render(width);
		expect(lines).toEqual(twinTranscript.render(width));
		return lines;
	};
	return { entry, twin, same };
}

describe("transcript entries that compact", () => {
	beforeAll(() => {
		initTheme("dark");
	});

	it("an assistant message draws the same after compacting, at another width, and after a click on its thinking", () => {
		const { entry, twin, same } = pair(() => new AssistantMessageComponent(assistantMessage()));
		const before = same(80);
		expect(entry.compact()).toBe(true);
		expect(entry.isCompacted()).toBe(true);
		expect(same(80)).toEqual(before);
		// Rendered at another width, it makes its components again.
		same(60);
		expect(entry.isCompacted()).toBe(false);
		same(80);
		expect(entry.compact()).toBe(true);
		// Events other than a left click find nothing to handle them, as before.
		const move = { ...click(80, 1), type: "move" as const };
		expect(entry.handleMouse(move)).toEqual(twin.handleMouse(move));
		expect(entry.isCompacted()).toBe(true);
		// A click on the thinking block toggles it, as it does on the twin.
		const result = entry.handleMouse(click(80, 1));
		expect(result?.handled).toBe(true);
		expect(twin.handleMouse(click(80, 1))?.handled).toBe(true);
		same(80);
	});

	it("an assistant message follows its setters and invalidate() after compacting", () => {
		const { entry, twin, same } = pair(() => new AssistantMessageComponent(assistantMessage()));
		same(80);
		expect(entry.compact()).toBe(true);
		entry.setHideThinkingBlock(true);
		twin.setHideThinkingBlock(true);
		same(80);
		expect(entry.compact()).toBe(true);
		entry.setHiddenThinkingLabel("Hidden");
		twin.setHiddenThinkingLabel("Hidden");
		same(80);
		expect(entry.compact()).toBe(true);
		entry.invalidate();
		twin.invalidate();
		same(80);
	});

	it("an assistant message that changed since it last rendered, or still streams, does not compact", () => {
		const entry = new AssistantMessageComponent(assistantMessage());
		const transcript = new Container();
		transcript.addChild(entry);
		expect(entry.compact()).toBe(false);
		transcript.render(80);
		entry.setHideThinkingBlock(true);
		expect(entry.compact()).toBe(false);
		transcript.render(80);
		expect(entry.compact()).toBe(true);
		const streaming = new AssistantMessageComponent();
		const streamingTranscript = new Container();
		streamingTranscript.addChild(streaming);
		streaming.updateContent(assistantMessage(), true);
		streamingTranscript.render(80);
		expect(streaming.compact()).toBe(false);
	});

	it("a bash row draws the same after compacting, at another width, expanded, and after a click", () => {
		const { entry, twin, same } = pair(bashRow);
		const before = same(80);
		expect(entry.compact()).toBe(true);
		expect(entry.isCompacted()).toBe(true);
		expect(same(80)).toEqual(before);
		same(50);
		expect(entry.isCompacted()).toBe(false);
		same(80);
		expect(entry.compact()).toBe(true);
		entry.setExpanded(true);
		twin.setExpanded(true);
		same(80);
		expect(entry.compact()).toBe(true);
		const move = { ...click(80, 3), type: "move" as const };
		expect(entry.handleMouse(move)).toEqual(twin.handleMouse(move));
		expect(entry.isCompacted()).toBe(true);
		expect(entry.handleMouse(click(80, 3))?.handled).toBe(true);
		expect(twin.handleMouse(click(80, 3))?.handled).toBe(true);
		same(80);
		expect(entry.compact()).toBe(true);
		entry.invalidate();
		twin.invalidate();
		same(80);
	});

	it("a compacted copy put in an entry's place draws what the entry draws, and behaves as it does", () => {
		const message = pair(() => new AssistantMessageComponent(assistantMessage()));
		const row = pair(bashRow);
		const transcript = new Container();
		const twinTranscript = new Container();
		transcript.addChild(message.entry);
		transcript.addChild(row.entry);
		twinTranscript.addChild(message.twin);
		twinTranscript.addChild(row.twin);
		const same = (width: number) => expect(transcript.render(width)).toEqual(twinTranscript.render(width));
		same(80);
		expect(message.entry.compact()).toBe(true);
		expect(row.entry.compact()).toBe(true);
		const children = transcript.children;
		const messageCopy = message.entry.compactedCopy();
		const rowCopy = row.entry.compactedCopy();
		children[0] = messageCopy;
		children[1] = rowCopy;
		expect(messageCopy).toBeInstanceOf(AssistantMessageComponent);
		expect(rowCopy).toBeInstanceOf(ToolExecutionComponent);
		same(80);
		// The copies click, expand, hide thinking and render at other widths as the entries would.
		expect(messageCopy.handleMouse(click(80, 1))?.handled).toBe(true);
		expect(message.twin.handleMouse(click(80, 1))?.handled).toBe(true);
		same(80);
		rowCopy.setExpanded(true);
		row.twin.setExpanded(true);
		same(80);
		same(61);
		expect(messageCopy.compact()).toBe(true);
		expect(rowCopy.compact()).toBe(true);
		children[0] = messageCopy.compactedCopy();
		children[1] = rowCopy.compactedCopy();
		same(61);
		(children[0] as AssistantMessageComponent).setHideThinkingBlock(true);
		message.twin.setHideThinkingBlock(true);
		(children[1] as ToolExecutionComponent).invalidate();
		row.twin.invalidate();
		same(61);
		same(80);
	});

	it("a row that is not compactable, or has no final result, does not compact", () => {
		const row = new ToolExecutionComponent(
			"bash",
			"call_2",
			{ command: "true" },
			{},
			withBuiltInRenderers("bash", undefined),
			ui,
			"/tmp",
		);
		row.updateResult({ content: [{ type: "text", text: "x" }], isError: false });
		row.render(80);
		expect(row.compact()).toBe(false);
		const partial = bashRow();
		partial.updateResult({ content: [{ type: "text", text: "partial" }], isError: false }, true);
		partial.render(80);
		expect(partial.compact()).toBe(false);
	});
});
