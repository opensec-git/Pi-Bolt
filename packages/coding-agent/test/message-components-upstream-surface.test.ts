import type { AssistantMessage } from "@earendil-works/pi-ai";
import { type Component, Container, Text, type TUI } from "@earendil-works/pi-tui";
import { afterEach, beforeAll, describe, expect, test } from "vitest";
import { AssistantMessageComponent } from "../src/modes/interactive/components/assistant-message.ts";
import { BashExecutionComponent } from "../src/modes/interactive/components/bash-execution.ts";
import { initTheme } from "../src/modes/interactive/theme/theme.ts";
import { stripAnsi } from "../src/utils/ansi.ts";

// #339: members of Pi 1.6.0's transcript components that extensions read (pi-tidy restyles assistant replies and bash
// rows through them). They must hold what upstream holds whenever an extension reads them, also where this build defers
// work to the next render.

/** The members as an extension reads them. */
interface UpstreamAssistant {
	contentContainer: Container;
	thinkingVisibilityOverrides: Map<number, boolean>;
	lastMessage?: AssistantMessage;
	updateContent(message: AssistantMessage, isStreaming?: boolean): void;
}

interface UpstreamBash {
	contentContainer: Container;
	command: string;
	colorKey: string;
	render(width: number): readonly string[];
}

function assistantMessage(text: string, stopReason: AssistantMessage["stopReason"] = "stop"): AssistantMessage {
	return {
		role: "assistant",
		content: [
			{ type: "thinking", thinking: "Weighing the options.", thinkingSignature: "sig" },
			{ type: "text", text },
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
		stopReason,
		timestamp: 1,
	};
}

const text = (lines: readonly string[]) => stripAnsi(lines.join("\n"));
const rows = (children: readonly Component[], width: number) => text(children.flatMap((child) => child.render(width)));

// SAFETY: the Loader of a running bash row only adds and removes an interval and requests renders.
const ui = { addInterval: () => ({ dispose() {} }), removeInterval() {}, requestRender() {} } as unknown as TUI;

describe("AssistantMessageComponent upstream surface (#339)", () => {
	beforeAll(() => {
		initTheme("dark");
	});

	// #339
	test("thinkingVisibilityOverrides is one Map that updateContent() draws from", () => {
		const component = new AssistantMessageComponent(assistantMessage("The answer."), true);
		const message = component as unknown as UpstreamAssistant;
		const overrides = message.thinkingVisibilityOverrides;
		expect(overrides).toBeInstanceOf(Map);
		expect(message.thinkingVisibilityOverrides).toBe(overrides);
		expect(text(component.render(60))).not.toContain("Weighing the options.");

		// As pi-tidy opens a thinking run: flip the switch, then rebuild the rows from the last message.
		overrides.set(0, false);
		message.updateContent(message.lastMessage!);
		expect(text(component.render(60))).toContain("Weighing the options.");

		// The setting clears the overrides, in the same Map.
		component.setHideThinkingBlock(true);
		expect(message.thinkingVisibilityOverrides).toBe(overrides);
		expect(overrides.size).toBe(0);
		expect(text(component.render(60))).not.toContain("Weighing the options.");
	});

	// #339
	test("contentContainer holds the rows of the latest streamed update before the next render", () => {
		const component = new AssistantMessageComponent(undefined, false);
		const message = component as unknown as UpstreamAssistant;
		message.updateContent(assistantMessage("First part"), true);
		component.render(60);
		message.updateContent(assistantMessage("First part, then the second part"), true);
		expect(rows(message.contentContainer.children, 60)).toContain("then the second part");
		expect(text(component.render(60))).toContain("then the second part");
	});

	// #339
	test("contentContainer of a failed reply ends with the error row", () => {
		const component = new AssistantMessageComponent(undefined, false);
		const message = component as unknown as UpstreamAssistant;
		const failed = { ...assistantMessage(""), stopReason: "error" as const, errorMessage: "upstream failed" };
		message.updateContent(failed, false);
		const last = message.contentContainer.children.at(-1);
		expect(last).toBeInstanceOf(Text);
		expect(text(last!.render(60))).toContain("upstream failed");
	});

	// #339
	test("contentContainer of a compacted reply is its live rows again", () => {
		const component = new AssistantMessageComponent(assistantMessage("A settled reply."));
		const transcript = new Container();
		transcript.addChild(component);
		const before = transcript.render(60).slice();
		// renderLive() records what compact() keeps; Container.render() takes it.
		expect(component.compact()).toBe(true);
		const message = component as unknown as UpstreamAssistant;
		expect(rows(message.contentContainer.children, 60)).toContain("A settled reply.");
		expect(component.isCompacted()).toBe(false);
		expect(transcript.render(60)).toEqual(before);
	});
	// #339: upstream mounts its container once and fills whatever the field holds.
	test("an assigned contentContainer is filled by the next updates; the message keeps drawing the mounted one", () => {
		const component = new AssistantMessageComponent(assistantMessage("First reply."));
		const message = component as unknown as UpstreamAssistant;
		const transcript = new Container();
		transcript.addChild(component);
		const before = transcript.render(60).slice();
		const replacement = new Container();
		message.contentContainer = replacement;
		expect(message.contentContainer).toBe(replacement);
		message.updateContent(assistantMessage("Second reply."));
		expect(rows(replacement.children, 60)).toContain("Second reply.");
		expect(transcript.render(60)).toEqual(before);
		// The assigned container is kept: compact() does not mount one of its own.
		expect(component.compact()).toBe(false);
	});

	// #339: upstream's updateContent() fills the field with every streamed update.
	test("a contentContainer assigned while a reply streams receives the streamed text", () => {
		const component = new AssistantMessageComponent(undefined, false);
		const message = component as unknown as UpstreamAssistant;
		const transcript = new Container();
		transcript.addChild(component);
		message.updateContent(assistantMessage("Streaming starts"), true);
		transcript.render(60);
		message.updateContent(assistantMessage("Streaming starts and"), true);
		transcript.render(60);
		const before = transcript.render(60).slice();
		const replacement = new Container();
		message.contentContainer = replacement;
		message.updateContent(assistantMessage("Streaming starts and goes on"), true);
		expect(rows(message.contentContainer.children, 60)).toContain("goes on");
		// The mounted container keeps what it held at the assignment.
		expect(transcript.render(60)).toEqual(before);
		// The next frame applies the next update (here as upstream's updateContent() does at once).
		message.updateContent(assistantMessage("Streaming starts and goes on to the end"), true);
		expect(text(transcript.render(60))).not.toContain("goes on");
		expect(rows(replacement.children, 60)).toContain("to the end");
	});

	// #339
	test("an assigned thinkingVisibilityOverrides is what updateContent() draws from", () => {
		const component = new AssistantMessageComponent(assistantMessage("The answer."), true);
		const message = component as unknown as UpstreamAssistant;
		const overrides = new Map([[0, false]]);
		message.thinkingVisibilityOverrides = overrides;
		expect(message.thinkingVisibilityOverrides).toBe(overrides);
		message.updateContent(message.lastMessage!);
		expect(text(component.render(60))).toContain("Weighing the options.");
		component.setHideThinkingBlock(true);
		expect(overrides.size).toBe(0);
		// The message keeps the Map: compact() leaves it as it is (its copy would hold a copy of the Map).
		const transcript = new Container();
		transcript.addChild(component);
		transcript.render(60);
		expect(component.compact()).toBe(false);
		expect(message.thinkingVisibilityOverrides).toBe(overrides);
	});

	// #339: an extension that changes the rows in a patched updateContent() (pi-tidy's error lines do) keeps its rows when
	// a compacted message makes its components again.
	test("a compacted message makes its rows again through updateContent()", () => {
		const proto = AssistantMessageComponent.prototype as unknown as UpstreamAssistant;
		const original = proto.updateContent;
		proto.updateContent = function (this: UpstreamAssistant, message: AssistantMessage, isStreaming?: boolean) {
			original.call(this, message, isStreaming);
			this.contentContainer.addChild(new Text("row added by an extension", 0, 0));
		};
		try {
			const component = new AssistantMessageComponent(assistantMessage("A settled reply."));
			const transcript = new Container();
			transcript.addChild(component);
			expect(text(transcript.render(60))).toContain("row added by an extension");
			expect(component.compact()).toBe(true);
			// Another width makes the components again.
			expect(text(transcript.render(50))).toContain("row added by an extension");
			expect(component.isCompacted()).toBe(false);
		} finally {
			proto.updateContent = original;
		}
	});
});

describe("BashExecutionComponent upstream surface (#339)", () => {
	const proto = BashExecutionComponent.prototype as unknown as UpstreamBash;
	const originalRender = proto.render;

	beforeAll(() => {
		initTheme("dark");
	});
	afterEach(() => {
		proto.render = originalRender;
	});

	// #339
	test("contentContainer holds all output received, before the next render", () => {
		const component = new BashExecutionComponent("echo hi", ui);
		const bash = component as unknown as UpstreamBash;
		component.render(60);
		component.appendOutput("first line\n");
		component.appendOutput("second line\n");
		expect(bash.command).toBe("echo hi");
		expect(bash.colorKey).toBe("bashMode");
		expect(rows(bash.contentContainer.children, 60)).toContain("second line");
	});

	// #339
	test("an assigned contentContainer is filled by the next updates; the row keeps drawing the mounted one", () => {
		const component = new BashExecutionComponent("echo hi", ui);
		const bash = component as unknown as UpstreamBash;
		const frame = new Container();
		frame.addChild(component);
		component.appendOutput("before\n");
		const replacement = new Container();
		bash.contentContainer = replacement;
		expect(bash.contentContainer).toBe(replacement);
		const drawn = text(frame.render(60));
		expect(drawn).toContain("before");
		component.appendOutput("after\n");
		// (Read through the field, which applies the output received since the last frame, as upstream has it at once.)
		expect(bash.contentContainer).toBe(replacement);
		expect(rows(replacement.children, 60)).toContain("after");
		expect(text(frame.render(60))).not.toContain("after");
	});

	// #339
	test("a render patched to draw contentContainer's rows itself shows streamed output", () => {
		// As pi-tidy's bash-mode frame: pi's header row is child 0, the rows under it are drawn as they are.
		proto.render = function (this: UpstreamBash, width: number) {
			return ["[framed]", ...this.contentContainer.children.slice(1).flatMap((child) => child.render(width))];
		};
		const component = new BashExecutionComponent("seq 3", ui);
		const frame = new Container();
		frame.addChild(component);
		frame.render(60);
		component.appendOutput("1\n2\n3\n");
		const lines = text(frame.render(60));
		expect(lines).toContain("[framed]");
		expect(lines).toMatch(/ 1 *\n 2 *\n 3 */);
	});
});
