import type { AssistantMessage } from "@earendil-works/pi-ai";
import { Container } from "@earendil-works/pi-tui";
import { describe, expect, test } from "vitest";
import { InteractiveMode } from "../src/modes/interactive/interactive-mode.ts";
import { initTheme } from "../src/modes/interactive/theme/theme.ts";
import { stripAnsi } from "../src/utils/ansi.ts";

const message: AssistantMessage = {
	role: "assistant",
	content: [{ type: "text", text: "survived" }],
	api: "anthropic-messages",
	provider: "anthropic",
	model: "claude-fable-5-1",
	usage: {
		input: 1,
		output: 1,
		cacheRead: 0,
		cacheWrite: 0,
		totalTokens: 2,
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
	},
	stopReason: "stop",
	timestamp: 1,
	diagnostics: [
		{
			type: "anthropic_input_transformations",
			timestamp: 1,
			details: {
				transformations: [
					{
						type: "thinking_dropped",
						path: "messages.2.content.0",
						reason: "prefix_binding_mismatch",
					},
					{
						type: "thinking_dropped",
						path: "messages.5.content.0",
						reason: "prefix_binding_mismatch",
					},
					{
						type: "thinking_dropped",
						path: "messages.8.content.0",
						reason: "prefix_binding_mismatch",
					},
				],
			},
		},
	],
};

type NoticeContext = {
	chatContainer: Container;
	settingsManager: { getNotices(): { reasoningDrops: boolean } };
	sessionManager: { getBranch(): Array<{ type: "message"; message: AssistantMessage }> };
};

const maybeShowThinkingDropNotice = Reflect.get(InteractiveMode.prototype, "maybeShowThinkingDropNotice") as (
	this: NoticeContext,
	message: AssistantMessage,
) => void;

describe("InteractiveMode assistant diagnostics", () => {
	test("shows Anthropic thinking drops when reasoning drop notices are enabled", () => {
		initTheme("dark");
		const enabled = {
			chatContainer: new Container(),
			settingsManager: { getNotices: () => ({ reasoningDrops: true }) },
			sessionManager: { getBranch: () => [] },
		};
		maybeShowThinkingDropNotice.call(enabled, message);
		const output = stripAnsi(enabled.chatContainer.render(120).join("\n"));
		expect(output).toContain("Anthropic dropped 3 thinking blocks: prefix_binding_mismatch (details in session)");

		const disabled = {
			chatContainer: new Container(),
			settingsManager: { getNotices: () => ({ reasoningDrops: false }) },
			sessionManager: { getBranch: () => [] },
		};
		maybeShowThinkingDropNotice.call(disabled, message);
		expect(disabled.chatContainer.children).toHaveLength(0);
	});

	test("does not repeat unchanged Anthropic thinking drops", () => {
		initTheme("dark");
		const context = {
			chatContainer: new Container(),
			settingsManager: { getNotices: () => ({ reasoningDrops: true }) },
			sessionManager: { getBranch: () => [{ type: "message" as const, message }] },
		};

		maybeShowThinkingDropNotice.call(context, { ...message, timestamp: 2 });

		expect(context.chatContainer.children).toHaveLength(0);
	});

	test("shows OpenAI reasoning removals with their reasons", () => {
		initTheme("dark");
		const context = {
			chatContainer: new Container(),
			settingsManager: { getNotices: () => ({ reasoningDrops: true }) },
			sessionManager: { getBranch: () => [{ type: "message" as const, message }] },
		};

		// The previous Anthropic response dropped more blocks; that must not hide the OpenAI removals.
		maybeShowThinkingDropNotice.call(
			context,
			openAIMessage(["rs_1", "rs_2"], ["prefix_mismatch", "organization_mismatch"]),
		);

		const output = stripAnsi(context.chatContainer.render(120).join("\n"));
		expect(output).toContain(
			"OpenAI dropped 2 reasoning items: prefix_mismatch, organization_mismatch (details in session)",
		);
	});

	test("shows OpenAI reasoning removals only when new items are removed", () => {
		initTheme("dark");
		// OpenAI reports a removed item again on every later request that replays it.
		const previous = openAIMessage(["rs_1", "rs_2"], ["prefix_mismatch"]);
		const context = {
			chatContainer: new Container(),
			settingsManager: { getNotices: () => ({ reasoningDrops: true }) },
			sessionManager: { getBranch: () => [{ type: "message" as const, message: previous }] },
		};

		maybeShowThinkingDropNotice.call(context, openAIMessage(["rs_1", "rs_2"], ["prefix_mismatch"]));
		expect(context.chatContainer.children).toHaveLength(0);

		maybeShowThinkingDropNotice.call(context, openAIMessage(["rs_1", "rs_2", "rs_3"], ["prefix_mismatch"]));
		const output = stripAnsi(context.chatContainer.render(120).join("\n"));
		expect(output).toContain("OpenAI dropped 1 reasoning item: prefix_mismatch (details in session)");
	});

	// #346
	test("announces each removed block once, also after responses without removals", () => {
		initTheme("dark");
		const branch: Array<{ type: "message"; message: AssistantMessage }> = [
			{ type: "message", message: openAIMessage(["rs_1", "rs_2", "rs_3"], ["prefix_mismatch"]) },
			{ type: "message", message: { ...message, diagnostics: undefined } },
		];
		const context = {
			chatContainer: new Container(),
			settingsManager: { getNotices: () => ({ reasoningDrops: true }) },
			sessionManager: { getBranch: () => branch },
		};

		// Reported again (an item pi could not leave out): no notice, however many responses lie between.
		maybeShowThinkingDropNotice.call(context, openAIMessage(["rs_2"], ["prefix_mismatch"]));
		expect(context.chatContainer.children).toHaveLength(0);

		// Fewer items than the last report, but a new one: announced, with only the new item's reason.
		maybeShowThinkingDropNotice.call(context, openAIMessage(["rs_1", "rs_9"], ["prefix_mismatch", "model_mismatch"]));
		const output = stripAnsi(context.chatContainer.render(120).join("\n"));
		expect(output).toContain("OpenAI dropped 1 reasoning item: model_mismatch (details in session)");
	});

	// #346
	test("names Anthropic blocks by their signature hash when the reply recorded one", () => {
		initTheme("dark");
		const reported = (path: string, signatureHash: string): AssistantMessage => ({
			...message,
			diagnostics: [
				{
					type: "anthropic_input_transformations",
					timestamp: 1,
					details: {
						transformations: [
							{ type: "thinking_dropped", path, reason: "prefix_binding_mismatch", signatureHash },
						],
					},
				},
			],
		});
		const context = {
			chatContainer: new Container(),
			settingsManager: { getNotices: () => ({ reasoningDrops: true }) },
			sessionManager: {
				getBranch: () => [{ type: "message" as const, message: reported("messages.4.content.0", "h1") }],
			},
		};

		// The same block at another place in the request is the same block.
		maybeShowThinkingDropNotice.call(context, reported("messages.2.content.0", "h1"));
		expect(context.chatContainer.children).toHaveLength(0);

		// Another block at the place the first one had is a new one.
		maybeShowThinkingDropNotice.call(context, reported("messages.4.content.0", "h2"));
		const output = stripAnsi(context.chatContainer.render(120).join("\n"));
		expect(output).toContain("Anthropic dropped 1 thinking block: prefix_binding_mismatch (details in session)");
	});
});

function openAIMessage(ids: string[], reasons: string[]): AssistantMessage {
	return {
		...message,
		api: "openai-responses",
		provider: "openai",
		model: "gpt-6.2-sol",
		timestamp: 3,
		diagnostics: [
			{
				type: "openai_input_transformations",
				timestamp: 3,
				details: {
					transformations: ids.map((id, index) => ({
						type: "reasoning",
						id,
						action: "removed",
						reason: reasons[Math.min(index, reasons.length - 1)],
					})),
				},
			},
		],
	};
}
