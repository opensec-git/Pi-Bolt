import { describe, expect, it } from "vitest";
import { convertMessages } from "../src/api/openai-completions.ts";
import type { AssistantMessage, Model, OpenAICompletionsCompat, Usage } from "../src/types.ts";
import { normalizeContext } from "../src/utils/transcript.ts";

// Signatures are parsed as JSON only when their first character after JSON whitespace can begin the value that is taken
// (an array of reasoning details, or a legacy encrypted detail object). What reaches the request must be what parsing every
// signature gives.

const emptyUsage: Usage = {
	input: 0,
	output: 0,
	cacheRead: 0,
	cacheWrite: 0,
	totalTokens: 0,
	cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
};

const compat = {
	supportsStore: true,
	supportsDeveloperRole: true,
	supportsReasoningEffort: true,
	supportsUsageInStreaming: true,
	supportsFinishReason: true,
	maxTokensField: "max_completion_tokens",
	requiresToolResultName: false,
	requiresAssistantAfterToolResult: false,
	requiresThinkingAsText: false,
	requiresReasoningContentOnAssistantMessages: false,
	thinkingFormat: "openai",
	openRouterRouting: {},
	vercelGatewayRouting: {},
	chatTemplateKwargs: {},
	chatTemplateArgs: {},
	zaiToolStream: false,
	supportsThinkingTokenBudget: false,
	thinkingTokenBudgetField: undefined,
	supportsStrictMode: true,
	supportsOpenAIGrammarTools: false,
	supportsMidConvoSystemMessages: false,
	supportsMidConvoToolAdditions: false,
	cacheControlFormat: undefined,
	sendSessionAffinityHeaders: false,
	sessionAffinityFormat: "openai",
	supportsLongCacheRetention: true,
} satisfies Omit<
	Required<OpenAICompletionsCompat>,
	"cacheControlFormat" | "thinkingTokenBudgetField" | "vllmPriority"
> & {
	cacheControlFormat?: OpenAICompletionsCompat["cacheControlFormat"];
	thinkingTokenBudgetField?: OpenAICompletionsCompat["thinkingTokenBudgetField"];
};

const model: Model<"openai-completions"> = {
	id: "m",
	name: "M",
	api: "openai-completions",
	provider: "p",
	baseUrl: "http://127.0.0.1:1",
	reasoning: true,
	input: ["text"],
	cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
	contextWindow: 128000,
	maxTokens: 4096,
	compat,
};

const encrypted = { type: "reasoning.encrypted", id: "call_1", data: "encrypted-signature" };
const text = { type: "reasoning.text", text: "thinking", signature: "sig", id: "r1", format: "f", index: 0 };

function assistantParam(thinkingSignature: string | undefined, thoughtSignature: string | undefined): unknown {
	const assistant: AssistantMessage = {
		role: "assistant",
		content: [
			{ type: "thinking", thinking: "some thinking", thinkingSignature },
			{ type: "text", text: "answer" },
			{ type: "toolCall", id: "call_1", name: "read", arguments: { path: "a" }, thoughtSignature },
		],
		api: "openai-completions",
		provider: "p",
		model: "m",
		usage: emptyUsage,
		stopReason: "toolUse",
		timestamp: 2,
	};
	const messages = convertMessages(
		model,
		normalizeContext({
			messages: [
				{ role: "user", content: "hello", timestamp: 1 },
				assistant,
				{
					role: "toolResult",
					toolCallId: "call_1",
					toolName: "read",
					content: [{ type: "text", text: "ok" }],
					isError: false,
					timestamp: 3,
				},
			],
		}),
		compat,
	);
	return messages[1];
}

describe("openai-completions reasoning signatures that are not JSON", () => {
	it("thinking signatures: field names, JSON with JSON whitespace before it, and values that are not taken", () => {
		const cases: Array<[string | undefined, Record<string, unknown>]> = [
			[undefined, {}],
			["", {}],
			["reasoning_content", { reasoning_content: "some thinking" }],
			["reasoning", { reasoning: "some thinking" }],
			["reasoning_text", { reasoning_text: "some thinking" }],
			["not json", {}],
			[JSON.stringify([text]), { reasoning_details: [text] }],
			[` \t\r\n${JSON.stringify([text, encrypted])}`, { reasoning_details: [text, encrypted] }],
			// (Not JSON whitespace: JSON.parse() throws.)
			[` ${JSON.stringify([text])}`, {}],
			[`﻿${JSON.stringify([text])}`, {}],
			["[]", {}],
			["[1]", {}],
			["[", {}],
			[JSON.stringify(text), {}],
			["null", {}],
			["7", {}],
			['"reasoning_content"', {}],
		];
		for (const [signature, expected] of cases) {
			const param = assistantParam(signature, undefined) as Record<string, unknown>;
			const { role, content, tool_calls, ...rest } = param;
			expect({ signature, rest }).toEqual({ signature, rest: expected });
			expect(role).toBe("assistant");
			expect(content).toBe("answer");
			expect(tool_calls).toHaveLength(1);
		}
	});

	it("tool call signatures: legacy encrypted details, with JSON whitespace before them, and values that are not taken", () => {
		const cases: Array<[string | undefined, unknown]> = [
			[undefined, undefined],
			["reasoning_content", undefined],
			[JSON.stringify(encrypted), [encrypted]],
			[`\n  ${JSON.stringify(encrypted)}`, [encrypted]],
			[` ${JSON.stringify(encrypted)}`, undefined],
			[JSON.stringify([encrypted]), undefined],
			[JSON.stringify(text), undefined],
			["{", undefined],
			["{}", undefined],
		];
		for (const [signature, expected] of cases) {
			const param = assistantParam(undefined, signature) as Record<string, unknown>;
			expect({ signature, details: param.reasoning_details }).toEqual({ signature, details: expected });
		}
	});
});
