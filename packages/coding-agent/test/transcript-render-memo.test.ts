import type { AssistantMessage } from "@earendil-works/pi-ai";
import { Container, isStableRender, Markdown, Text, type TUI } from "@earendil-works/pi-tui";
import { beforeAll, describe, expect, test } from "vitest";
import type { ToolRenderContext } from "../src/core/extensions/types.ts";
import { createBashToolDefinition } from "../src/core/tools/bash.ts";
import { editRenderers } from "../src/core/tools/renderers/edit.ts";
import { AssistantMessageComponent } from "../src/modes/interactive/components/assistant-message.ts";
import { ToolExecutionComponent } from "../src/modes/interactive/components/tool-execution.ts";
import { UserMessageComponent } from "../src/modes/interactive/components/user-message.ts";
import {
	truncateStyledLinesToVisualEnd,
	truncateToVisualLines,
} from "../src/modes/interactive/components/visual-truncate.ts";
import { initTheme, theme } from "../src/modes/interactive/theme/theme.ts";

const OSC133_ZONE_START = "\x1b]133;A\x07";
const OSC133_ZONE_END = "\x1b]133;B\x07";

function createAssistantMessage(content: AssistantMessage["content"]): AssistantMessage {
	return {
		role: "assistant",
		content,
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
		timestamp: Date.now(),
	};
}

function createFakeTui(): TUI {
	return { requestRender: () => {} } as unknown as TUI;
}

function count(lines: readonly string[], marker: string): number {
	return lines.join("\n").split(marker).length - 1;
}

/** Bash output that is long enough to be collapsed, with tabs and lines wider than the width. */
const BASH_OUTPUT = [
	...Array.from({ length: 30 }, (_, i) => `line ${i}\twith a tab`),
	"x".repeat(250),
	"a fairly long line of words that wraps across the width of a narrow terminal more than once, really",
	"",
	"last line",
].join("\n");

describe("transcript components keep their lines", () => {
	beforeAll(() => {
		initTheme("dark");
	});

	test("OSC 133 markers are added once however often a message renders", () => {
		const assistant = new AssistantMessageComponent(
			createAssistantMessage([{ type: "text", text: "hello\n\nworld" }]),
		);
		const user = new UserMessageComponent("a question");
		const parent = new Container();
		parent.addChild(user);
		parent.addChild(assistant);
		for (let i = 0; i < 3; i++) {
			for (const lines of [assistant.render(40), user.render(40), parent.render(40), parent.renderShared(40)]) {
				expect(lines.length).toBeGreaterThan(0);
			}
			const lines = parent.render(40);
			expect(count(lines, OSC133_ZONE_START)).toBe(2);
			expect(count(lines, OSC133_ZONE_END)).toBe(2);
			expect(count(assistant.render(40), OSC133_ZONE_START)).toBe(1);
		}
		expect(assistant.renderShared(40)).toBe(assistant.renderShared(40));
		expect(isStableRender(assistant)).toBe(true);
	});

	test("invalidate() keeps the Markdown components, and with them their parsed text", () => {
		const message = createAssistantMessage([
			{ type: "thinking", thinking: "thinking it over" },
			{ type: "text", text: "# Title\n\nSome **text** and `code`." },
		]);
		const component = new AssistantMessageComponent(message);
		const markdownComponents = (): Markdown[] => {
			const found: Markdown[] = [];
			const visit = (node: unknown): void => {
				if (node instanceof Markdown) found.push(node);
				const children = (node as { children?: unknown[]; child?: unknown }).children;
				for (const child of children ?? []) visit(child);
				const wrapped = (node as { child?: unknown }).child;
				if (wrapped) visit(wrapped);
			};
			visit(component);
			return found;
		};
		const before = component.render(50);
		const components = markdownComponents();
		expect(components).toHaveLength(2);
		component.invalidate();
		expect(markdownComponents()).toEqual(components);
		expect(markdownComponents().every((markdown, i) => markdown === components[i])).toBe(true);
		expect(component.render(50)).toEqual(before);
		expect(component.render(50)).toEqual(new AssistantMessageComponent(message).render(50));

		component.setHideThinkingBlock(true);
		expect(component.render(50)).toEqual(new AssistantMessageComponent(message, true).render(50));
		component.setHideThinkingBlock(false);
		expect(component.render(50)).toEqual(before);
	});

	test("a tool row expands and collapses with the output a new row shows", () => {
		const tool = createBashToolDefinition(process.cwd(), { exposeSessionEnvironment: false });
		const result = { content: [{ type: "text", text: BASH_OUTPUT }], details: undefined, isError: false };
		const create = (expanded: boolean) => {
			const row = new ToolExecutionComponent("bash", "id", { command: "build" }, {}, tool, createFakeTui(), "/");
			row.setExpanded(expanded);
			row.updateResult(result, false);
			return row;
		};
		const row = create(false);
		const transcript = new Container();
		transcript.addChild(new Text("before", 0, 0));
		transcript.addChild(row);
		transcript.addChild(new Text("after", 0, 0));
		const collapsed = transcript.render(60);
		expect(transcript.renderShared(60)).toBe(transcript.renderShared(60));
		row.setExpanded(true);
		const expanded = transcript.render(60);
		expect(expanded.length).toBeGreaterThan(collapsed.length);
		expect(row.render(60)).toEqual(create(true).render(60));
		row.setExpanded(false);
		expect(transcript.render(60)).toEqual(collapsed);
		expect(row.render(60)).toEqual(create(false).render(60));
	});

	test("the collapsed shell preview styles and wraps only the lines it shows, with the same result", () => {
		const style = (line: string) => theme.fg("toolOutput", line);
		const inputs = [
			BASH_OUTPUT,
			"one line",
			"short\nlines\nonly",
			"x".repeat(1000),
			`${"wide 漢字 ".repeat(40)}\n${"y".repeat(10)}`,
			"\ttab\n\t\tdouble tab\nend",
			Array.from({ length: 6 }, (_, i) => `l${i}`).join("\n"),
		];
		for (const input of inputs) {
			const lines = input.split("\n");
			for (const width of [1, 7, 20, 41, 80, 200]) {
				for (const max of [1, 5, 20]) {
					expect(truncateStyledLinesToVisualEnd(lines, style, max, width)).toEqual(
						truncateToVisualLines(lines.map(style).join("\n"), max, width, 0, "end"),
					);
				}
			}
		}
	});

	test("the edit row is not built again when nothing it shows changed", () => {
		const state: Record<string, unknown> = {};
		const args = { path: "src/file.ts", edits: [{ oldText: "a", newText: "b" }] };
		const context = (lastComponent: unknown): ToolRenderContext =>
			({
				args,
				toolCallId: "edit-1",
				invalidate: () => {},
				lastComponent,
				state,
				cwd: "/",
				executionStarted: true,
				argsComplete: false,
				isPartial: false,
				expanded: false,
				showImages: false,
				isError: false,
				durationMs: undefined,
				outputPad: 1,
			}) as ToolRenderContext;
		const call = editRenderers.renderCall!(args, theme, context(undefined)) as Container;
		const diff = "-1 a\n+1 b";
		editRenderers.renderResult!(
			{ content: [{ type: "text", text: "ok" }], details: { diff, firstChangedLine: 1 } },
			{ expanded: false, isPartial: false },
			theme,
			context(undefined),
		);
		const rows = [...call.children];
		const lines = call.render(60);
		expect(rows).toHaveLength(3);
		const again = editRenderers.renderCall!(args, theme, context(call)) as Container;
		expect(again).toBe(call);
		expect(call.children.every((child, i) => child === rows[i])).toBe(true);
		expect(call.render(60)).toBe(lines);
	});
});
