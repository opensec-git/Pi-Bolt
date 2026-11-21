import type { AgentMessage } from "@earendil-works/pi-agent-core";
import type { AssistantMessage } from "@earendil-works/pi-ai";
import { type Component, Container, ScrollView, Spacer, Text, TuiAltScreen, VStack } from "@earendil-works/pi-tui";
import { beforeAll, describe, expect, test, vi } from "vitest";
import { renderLayoutFrame } from "../../tui/src/layout.ts";
import { VirtualTerminal } from "../../tui/test/virtual-terminal.ts";
import type { AgentSessionEvent } from "../src/core/agent-session.ts";
import { ExtensionRunner } from "../src/core/extensions/runner.ts";
import type { EntryRenderer, Extension } from "../src/core/extensions/types.ts";
import type { SessionEntry } from "../src/core/session-manager.ts";
import { AssistantMessageComponent } from "../src/modes/interactive/components/assistant-message.ts";
import type { ToolExecutionComponent } from "../src/modes/interactive/components/tool-execution.ts";
import { TranscriptContainer } from "../src/modes/interactive/components/transcript-container.ts";
import { UserMessageComponent } from "../src/modes/interactive/components/user-message.ts";
import { InteractiveMode } from "../src/modes/interactive/interactive-mode.ts";
import { initTheme } from "../src/modes/interactive/theme/theme.ts";

const WIDTH = 80;
const ROWS = 24;

function assistant(index: number): AssistantMessage {
	return {
		role: "assistant",
		content: [
			{ type: "thinking", thinking: `thinking about step ${index} `.repeat(6) },
			{
				type: "text",
				text: `## Step ${index}\n\nSome **bold** text and \`code\` for step ${index}, long enough to wrap at eighty columns once or twice.\n\n- one\n- two\n\n\`\`\`ts\nconst step = ${index};\n\`\`\``,
			},
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
		stopReason: "stop",
		timestamp: 0,
	};
}

/** Counts its renders. */
class CountedText extends Text {
	renders = 0;
	override render(width: number): readonly string[] {
		this.renders++;
		return super.render(width);
	}
}

function entries(count: number): Component[] {
	const list: Component[] = [];
	for (let i = 0; i < count; i++) {
		if (i % 5 === 0) list.push(new Spacer(1), new UserMessageComponent(`Task ${i}: do the thing`, undefined, 1));
		list.push(new AssistantMessageComponent(assistant(i), false, undefined, "Thinking...", 1));
		list.push(new CountedText(`tool row ${i}\n${"output line\n".repeat(i % 4)}done`, 1, 0));
	}
	return list;
}

interface Screen {
	transcript: TranscriptContainer;
	root: VStack;
	scroll: ScrollView;
}

function screen(children: readonly Component[], scrollbar: "auto" | "always" | "hidden"): Screen {
	const transcript = new TranscriptContainer();
	for (const child of children) transcript.addChild(child);
	const document = new Container();
	document.addChild(new Text("header", 1, 0));
	document.addChild(transcript);
	const scroll = new ScrollView(document, { follow: "end", primary: true, scrollbar });
	const root = new VStack([
		{ component: scroll, basis: 0, grow: 1, shrink: 1, minSize: 1 },
		{ component: new Text("editor\nfooter", 0, 0), basis: "auto", grow: 0, shrink: 0, minSize: 1 },
	]);
	return { transcript, root, scroll };
}

function frame(s: Screen, width = WIDTH, rows = ROWS): string[] {
	return renderLayoutFrame(s.root, width, rows, () => {}).lines;
}

/** The lines interactive mode asks for: the rows, and with the scrollbar always shown, enough for the smallest thumb. */
function linesShown(rows: number, scrollbar: "auto" | "always"): number {
	return (scrollbar === "always" ? Math.max(rows, Math.floor(0.4 * rows * rows)) : rows) + 1;
}

describe("fullscreen transcript restoration", () => {
	beforeAll(() => {
		initTheme("dark");
	});

	for (const scrollbar of ["auto", "always"] as const) {
		test(`keeps the same bottom frame as the full document at both widths (scrollbar ${scrollbar})`, () => {
			let rows = ROWS;
			const whole = screen(entries(100), scrollbar);
			const ownEntries = entries(100);
			const tail = screen(ownEntries, scrollbar);
			tail.transcript.renderEndFirst(() => linesShown(rows, scrollbar));
			const firstTool = ownEntries[3] as CountedText;

			for (const width of [WIDTH, WIDTH - 30]) {
				expect(frame(tail, width, rows)).toEqual(frame(whole, width, rows));
				expect(tail.transcript.isHoldingBack).toBe(true);
				expect(firstTool.renders).toBe(0);
			}
			rows += 5;
			expect(frame(tail, WIDTH, rows)).toEqual(frame(whole, WIDTH, rows));
			expect(firstTool.renders).toBe(0);

			tail.transcript.completeNow();
			expect(tail.transcript.render(WIDTH)).toEqual(whole.transcript.render(WIDTH));
			expect(tail.transcript.children).toEqual(ownEntries);
		});
	}

	test("only constructs older rows on demand, and releases them on return to the end", async () => {
		const tail = screen([], "auto");
		const total = 800;
		const built: number[] = [];
		for (let i = 0; i < total; i++) {
			tail.transcript.addDeferredChild(() => {
				built.push(i);
				return new Text(`row ${i}`, 0, 0);
			});
		}
		tail.transcript.renderEndFirst(() => ROWS + 1);
		const initial = frame(tail);
		expect(initial.at(-3)).toContain(`row ${total - 1}`);
		const firstCount = built.length;
		expect(firstCount).toBeGreaterThanOrEqual(ROWS - 2);
		expect(firstCount).toBeLessThan(total / 2);
		for (let i = 0; i < 5; i++) await new Promise<void>((resolve) => setImmediate(resolve));
		expect(built).toHaveLength(firstCount);

		const distance = firstCount + ROWS * 2;
		expect(tail.transcript.ensureRowsFromEnd(distance)).toBeGreaterThan(0);
		frame(tail);
		const nearest = Math.min(...built);
		expect(nearest).toBeLessThanOrEqual(total - distance);
		expect(nearest).toBeGreaterThan(0);
		expect(tail.transcript.completeNow()).toBe(true);
		const all = tail.transcript.render(WIDTH);
		expect(all).toHaveLength(total);
		expect(all[0]).toContain("row 0");
		expect(all.at(-1)).toContain(`row ${total - 1}`);

		tail.transcript.followEnd();
		expect(frame(tail)).toEqual(initial);
		expect(tail.transcript.isHoldingBack).toBe(true);
		expect(Array.from(tail.transcript.residentChildren).length).toBeLessThan(total / 2);
	});

	test("keeps an individual row's mouse state when it leaves and re-enters the viewport", () => {
		const tail = screen([], "auto");
		const createClickable = vi.fn(() => {
			const text = new Text("collapsed", 0, 0);
			return Object.assign(text, {
				handleMouse: () => {
					text.setText("expanded");
					return { handled: true };
				},
			});
		});
		tail.transcript.addDeferredChild(createClickable);
		tail.transcript.addChild(new Text("end\n".repeat(ROWS * 6), 0, 0));
		tail.transcript.renderEndFirst(() => ROWS + 1);
		frame(tail);
		tail.transcript.completeNow();
		const fullHeight = tail.transcript.render(WIDTH).length;
		tail.transcript.handleMouse({
			type: "click",
			button: "left",
			x: 0,
			y: 0,
			screenX: 0,
			screenY: 0,
			width: WIDTH,
			height: fullHeight,
			shift: false,
			alt: false,
			ctrl: false,
		});
		tail.transcript.followEnd();
		frame(tail);
		expect(tail.transcript.isHoldingBack).toBe(true);
		tail.transcript.completeNow();
		expect(tail.transcript.render(WIDTH)[0]).toContain("expanded");
		expect(createClickable).toHaveBeenCalledTimes(1);
	});

	test("clears an unread session without running its factories", () => {
		const tail = screen([], "auto");
		const createOld = vi.fn(() => new Text("old", 0, 0));
		tail.transcript.addDeferredChild(createOld);
		tail.transcript.addChild(new Text("end\n".repeat(ROWS * 6), 0, 0));
		tail.transcript.renderEndFirst(() => ROWS + 1);
		frame(tail);
		tail.transcript.clear();
		tail.transcript.addChild(new Text("new session", 0, 0));
		expect(tail.transcript.render(WIDTH).join("\n")).toContain("new session");
		expect(createOld).not.toHaveBeenCalled();
	});

	test("keeps full public children order after a held component is removed", () => {
		const tail = screen([], "auto");
		const removed = new Text("remove me", 0, 0);
		const retained = new Text("retained", 0, 0);
		tail.transcript.addChild(removed);
		tail.transcript.addDeferredChild(() => retained);
		const end = new Text("end\n".repeat(ROWS * 6), 0, 0);
		tail.transcript.addChild(end);
		tail.transcript.renderEndFirst(() => ROWS + 1);
		frame(tail);
		tail.transcript.removeChild(removed);
		expect(tail.transcript.children).toEqual([retained, end]);
	});
});

function resumedSession(): SessionEntry[] {
	const entries: SessionEntry[] = [];
	const metadata = () => ({
		id: `entry-${entries.length}`,
		parentId: entries.at(-1)?.id ?? null,
		timestamp: "2026-01-01T00:00:00Z",
	});
	const message = (value: AgentMessage) => entries.push({ type: "message", ...metadata(), message: value });
	const custom = (customType: string) => entries.push({ type: "custom", ...metadata(), customType, data: {} });
	message({ role: "user", content: "oldest user prompt", timestamp: 0 });
	message({
		...assistant(0),
		stopReason: "toolUse",
		content: [
			{ type: "toolCall", id: "finished", name: "example", arguments: {} },
			{ type: "toolCall", id: "pending", name: "example", arguments: {} },
		],
	});
	custom("visible entry");
	custom("invisible entry");
	message({
		role: "toolResult",
		toolName: "example",
		toolCallId: "finished",
		content: [{ type: "text", text: "finished result" }],
		isError: false,
		timestamp: 0,
	});
	for (let i = 1; i <= 80; i++) message(assistant(i));
	message({ role: "user", content: "latest user prompt", timestamp: 0 });
	return entries;
}

type TranscriptExtension = Pick<Extension, "entryRenderers" | "markdownTransformer" | "toolRenderers">;

function restore(
	mode: "regular" | "fullscreen",
	entries: SessionEntry[],
	extensions: TranscriptExtension[] = [
		{
			entryRenderers: new Map<string, EntryRenderer>([
				["visible entry", () => new Text("extension output", 0, 0)],
				["invisible entry", () => undefined],
			]),
		},
	],
) {
	const runner: ExtensionRunner = Object.create(ExtensionRunner.prototype);
	Object.assign(runner, { extensions });
	const layout = screen([], "auto");
	const history: string[] = [];
	const pendingTools = new Map<string, ToolExecutionComponent>();
	const interactive: InteractiveMode = Object.create(InteractiveMode.prototype);
	Object.assign(interactive, {
		runtimeHost: {
			session: {
				retryAttempt: 0,
				resourceLoader: { getExtensions: () => ({ extensions }) },
				sessionManager: {
					buildContextEntries: () => entries,
					getEntries: () => entries,
					getCwd: () => process.cwd(),
				},
				settingsManager: {
					isProjectTrusted: () => true,
					getCodeBlockIndent: () => "  ",
					getShowImages: () => false,
					getImageWidthCells: () => 60,
					getShowCacheMissNotices: () => false,
				},
				extensionRunner: runner,
			},
		},
		chatContainer: layout.transcript,
		renderer: { mode },
		ui: { requestRender: () => {}, terminal: { rows: ROWS, columns: WIDTH } },
		editor: { addToHistory: (text: string) => history.push(text) },
		footer: { invalidate: () => {} },
		programStatus: { handleEvent: () => {} },
		updateEditorBorderColor: () => {},
		getMarkdownTransformers: () => runner.getMarkdownTransformers(),
		toolRowsCompactable: () => true,
		getRegisteredToolDefinition: (name: string) => runner.resolveToolRenderers(name, () => undefined),
		pendingTools,
		outputPad: 1,
		hideThinkingBlock: false,
		toolOutputExpanded: true,
	});
	interactive.renderInitialMessages();
	if (mode === "fullscreen") layout.transcript.renderEndFirst(() => ROWS + 1);
	return { layout, history, pendingTools, interactive };
}

test("restores editor history, tool bindings and extension ordering before and after deferred reads", () => {
	initTheme("dark");
	const entries = resumedSession();
	const assistantRows = vi.spyOn(AssistantMessageComponent.prototype, "updateContent");
	try {
		const lazy = restore("fullscreen", entries);
		const bottom = frame(lazy.layout);
		expect(assistantRows.mock.calls.length).toBeLessThan(10);
		expect(lazy.history).toEqual(["oldest user prompt", "latest user prompt"]);
		expect([...lazy.pendingTools.keys()]).toEqual(["pending"]);

		assistantRows.mockClear();
		const eager = restore("regular", entries);
		expect(assistantRows.mock.calls.length).toBeGreaterThanOrEqual(80);
		expect(bottom).toEqual(frame(eager.layout));
		const completeTool = Reflect.get(InteractiveMode.prototype, "handleEvent") as (
			this: InteractiveMode,
			event: AgentSessionEvent,
		) => void;
		const done: AgentSessionEvent = {
			type: "tool_execution_end",
			toolCallId: "pending",
			toolName: "example",
			result: { content: [{ type: "text", text: "live result" }], details: undefined },
			isError: false,
		};
		completeTool.call(lazy.interactive, done);
		completeTool.call(eager.interactive, done);
		expect(lazy.layout.transcript.isHoldingBack).toBe(true);
		const expected = eager.layout.transcript.render(WIDTH);
		lazy.layout.transcript.completeNow();
		const full = lazy.layout.transcript.render(WIDTH);
		expect(full).toEqual(expected);
		const text = full.join("\n");
		const markers = [
			"oldest user prompt",
			"finished result",
			"live result",
			"extension output",
			"Step 1",
			"latest user prompt",
		];
		let previous = -1;
		for (const marker of markers) {
			const index = text.indexOf(marker);
			expect(index, marker).toBeGreaterThan(previous);
			previous = index;
		}
		expect(lazy.history).toEqual(eager.history);
		expect(lazy.pendingTools.size).toBe(0);
	} finally {
		assistantRows.mockRestore();
	}
});

function mountFullscreen(layout: Screen, interactive: InteractiveMode) {
	const terminal = new VirtualTerminal(WIDTH, ROWS);
	const copied: string[] = [];
	const ui = new TuiAltScreen(terminal, undefined, undefined, {
		copySelection: async (text) => {
			copied.push(text);
			return true;
		},
	});
	Object.assign(interactive, {
		fullscreenLayoutRoot: layout.root,
		transcriptScrollView: layout.scroll,
		renderer: ui,
		ui,
	});
	const mount = Reflect.get(InteractiveMode.prototype, "mountInteractiveTui") as (
		this: InteractiveMode,
		renderer: TuiAltScreen,
		components: Component[],
	) => void;
	mount.call(interactive, ui, [layout.transcript]);
	ui.start();
	return { terminal, ui, copied };
}

test("mounted fullscreen history can page locally, search outside the tail, and produce the full transcript", async () => {
	initTheme("dark");
	const entries = resumedSession();
	const { layout, interactive } = restore("fullscreen", entries);
	const { terminal, ui, copied } = mountFullscreen(layout, interactive);
	try {
		ui.renderNow();
		expect(layout.transcript.isHoldingBack).toBe(true);
		ui.scrollBy(-ROWS * 3);
		ui.renderNow();
		expect(layout.scroll.isFollowingEnd).toBe(false);
		expect(layout.transcript.isHoldingBack).toBe(true);

		await terminal.waitForRender();
		const displayed = terminal.getViewport();
		const line = displayed.findIndex((row) => row.includes("Some bold text"));
		expect(line).toBeGreaterThanOrEqual(0);
		const col = displayed[line]!.indexOf("Some bold text") + 1;
		terminal.sendInput(`\x1b[<0;${col};${line + 1}M`);
		terminal.sendInput(`\x1b[<32;${col + 8};${line + 1}M`);
		terminal.sendInput(`\x1b[<0;${col + 8};${line + 1}m`);
		await terminal.waitForRender();
		expect(copied).toEqual(["Some bold"]);
		ui.resetTextSelection();

		terminal.sendInput("\x1b[102;6u");
		terminal.sendInput("oldest");
		ui.renderNow();
		await terminal.waitForRender();
		expect(terminal.getViewport().join("\n")).toContain("1/1");
		terminal.sendInput("\x1b");
		ui.scrollToBottom();
		ui.renderNow();
		expect(layout.transcript.isHoldingBack).toBe(true);

		const eager = restore("regular", entries);
		expect(ui.render(WIDTH)).toEqual(eager.layout.root.render(WIDTH));
	} finally {
		ui.stop();
	}
});

test("bounds resident components across deep scrolling in both directions while matching every eager frame", () => {
	const lazy = screen([], "hidden");
	const eager = screen([], "hidden");
	for (let i = 0; i < 700; i++) {
		const row = Array.from({ length: 1 + (i % 3) }, (_, j) => `entry ${i} line ${j}`).join("\n");
		lazy.transcript.addDeferredChild(() => new Text(row, 0, 0));
		eager.transcript.addChild(new Text(row, 0, 0));
	}
	const interactive: InteractiveMode = Object.create(InteractiveMode.prototype);
	Object.assign(interactive, { chatContainer: lazy.transcript });
	const { ui } = mountFullscreen(lazy, interactive);
	const reference = new TuiAltScreen(new VirtualTerminal(WIDTH, ROWS));
	reference.setLayoutRoot(eager.root);
	reference.start();
	try {
		ui.renderNow();
		reference.renderNow();
		expect(ui.getScreenLines()).toEqual(reference.getScreenLines());
		const page = ROWS - 2;
		for (const direction of [-1, 1]) {
			for (let i = 0; i < 24; i++) {
				ui.scrollBy(page * direction);
				reference.scrollBy(page * direction);
				ui.renderNow();
				reference.renderNow();
				expect(ui.getScreenLines(), `direction ${direction}, page ${i + 1}`).toEqual(reference.getScreenLines());
				expect(Array.from(lazy.transcript.residentChildren).length).toBeLessThan(ROWS * 5);
			}
		}
		expect(lazy.scroll.isFollowingEnd).toBe(true);
		expect(ui.render(WIDTH)).toEqual(eager.root.render(WIDTH));
	} finally {
		ui.stop();
		reference.stop();
	}
});

for (const rowHeights of ["uniform", "variable"] as const) {
	test(`PageUp paints the long document's scrollbar while older ${rowHeights}-height rows stay deferred`, () => {
		const lazy = screen([], "auto");
		const eager = screen([], "auto");
		lazy.scroll.estimatedPrefixHeight = () => lazy.transcript.estimatedPrefixHeight;
		let constructed = 0;
		for (let i = 0; i < 900; i++) {
			const height = rowHeights === "uniform" ? 1 : 1 + (i % 4);
			const text = Array.from({ length: height }, (_, j) => `row ${i}, line ${j}`).join("\n");
			lazy.transcript.addDeferredChild(() => {
				constructed++;
				return new Text(text, 0, 0);
			});
			eager.transcript.addChild(new Text(text, 0, 0));
		}
		const interactive: InteractiveMode = Object.create(InteractiveMode.prototype);
		Object.assign(interactive, { chatContainer: lazy.transcript });
		const { terminal, ui } = mountFullscreen(lazy, interactive);
		const eagerTerminal = new VirtualTerminal(WIDTH, ROWS);
		const reference = new TuiAltScreen(eagerTerminal);
		reference.setLayoutRoot(eager.root);
		reference.start();
		try {
			ui.renderNow();
			reference.renderNow();
			terminal.sendInput("\x1b[5~");
			eagerTerminal.sendInput("\x1b[5~");
			ui.renderNow();
			reference.renderNow();

			const frame = ui.getScreenLines();
			const thumb = frame.flatMap((line, row) => (line.includes("┃") || line.includes("█") ? [row] : []));
			expect(lazy.scroll.isFollowingEnd).toBe(false);
			expect(thumb).toHaveLength(2);
			expect(thumb[1]).toBe(thumb[0]! + 1);
			expect(thumb[1]).toBeGreaterThanOrEqual(lazy.scroll.viewportHeight - 2);
			if (rowHeights === "uniform") expect(frame).toEqual(reference.getScreenLines());
			expect(lazy.transcript.isHoldingBack).toBe(true);
			expect(constructed).toBeLessThan(ROWS * 5);
		} finally {
			ui.stop();
			reference.stop();
		}
	});
}

test("restored rows use the extension registrations in effect at their position in the session", () => {
	initTheme("dark");
	const session: SessionEntry[] = [];
	const metadata = () => ({
		id: `entry-${session.length}`,
		parentId: session.at(-1)?.id ?? null,
		timestamp: "2026-01-01T00:00:00Z",
	});
	for (const stage of ["before", "after"]) {
		if (stage === "after") session.push({ type: "custom", ...metadata(), customType: "register", data: {} });
		session.push({
			type: "message",
			...metadata(),
			message: {
				...assistant(0),
				content: [
					{ type: "text", text: stage },
					{ type: "toolCall", id: stage, name: "example", arguments: {} },
				],
			},
		});
		session.push({
			type: "message",
			...metadata(),
			message: {
				role: "toolResult",
				toolName: "example",
				toolCallId: stage,
				content: [{ type: "text", text: stage }],
				isError: false,
				timestamp: 0,
			},
		});
	}
	for (let i = 0; i < 60; i++) {
		session.push({ type: "message", ...metadata(), message: { role: "user", content: `prompt ${i}`, timestamp: 0 } });
	}

	const restoreExtension = (mode: "regular" | "fullscreen") => {
		const calls: string[] = [];
		const extension: TranscriptExtension = {
			entryRenderers: new Map([
				[
					"register",
					() => {
						if (!extension.markdownTransformer) {
							calls.push("register");
							extension.markdownTransformer = {
								messageTypes: new Set(["assistant"]),
								transformer: (text) => `transformed-${text}`,
							};
							extension.toolRenderers = [
								(name) => {
									calls.push(`resolve-${name}`);
									return { renderCall: () => new Text("extension tool call", 0, 0) };
								},
							];
						}
						return new Text("registered", 0, 0);
					},
				],
			]),
		};
		return { ...restore(mode, session, [extension]), calls };
	};
	const eager = restoreExtension("regular");
	const lazy = restoreExtension("fullscreen");
	expect(eager.calls).toEqual(["register", "resolve-example"]);
	expect(lazy.calls).toEqual(eager.calls);
	frame(lazy.layout);
	expect(lazy.layout.transcript.isHoldingBack).toBe(true);
	lazy.layout.transcript.completeNow();
	const lines = lazy.layout.transcript.render(WIDTH);
	expect(lines).toEqual(eager.layout.transcript.render(WIDTH));
	const output = lines.join("\n");
	expect(output).toContain("transformed-after");
	expect(output).not.toContain("transformed-before");
	expect(output.match(/extension tool call/g)).toHaveLength(1);
	expect(lazy.calls).toEqual(["register", "resolve-example"]);
});
