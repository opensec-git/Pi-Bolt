import { Type } from "typebox";
import { describe, expect, it } from "vitest";
import { stream as streamAnthropic } from "../src/api/anthropic-messages.ts";
import type { Model, Tool } from "../src/types.ts";
import { normalizeContext } from "../src/utils/transcript.ts";

interface AnthropicToolPayload {
	tools?: Array<{ strict?: boolean; input_schema: Record<string, unknown> }>;
}

class PayloadCaptured extends Error {
	constructor() {
		super("payload captured");
		this.name = "PayloadCaptured";
	}
}

function createModel(): Model<"anthropic-messages"> {
	return {
		id: "claude-opus-4-8",
		name: "Claude Opus 4.8",
		api: "anthropic-messages",
		provider: "test-anthropic",
		baseUrl: "http://127.0.0.1:9",
		reasoning: true,
		input: ["text"],
		cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
		contextWindow: 200000,
		maxTokens: 32000,
		compat: { forceAdaptiveThinking: true, supportsStrictTools: true },
	};
}

function createTool(parameters: Tool["parameters"], constrainedSampling?: Tool["constrainedSampling"]): Tool {
	return {
		name: "lookup",
		description: "Look up a value",
		parameters,
		...(constrainedSampling ? { constrainedSampling } : {}),
	};
}

function createStrictTool(parameters: Tool["parameters"], strict: "prefer" | "require" = "require"): Tool {
	return createTool(parameters, { type: "json_schema", strict });
}

async function captureFirstTool(tool: Tool): Promise<NonNullable<AnthropicToolPayload["tools"]>[number]> {
	let payload: AnthropicToolPayload | undefined;
	const stream = streamAnthropic(
		createModel(),
		normalizeContext({ messages: [{ role: "user", content: "Use the tool", timestamp: Date.now() }], tools: [tool] }),
		{
			apiKey: "test-key",
			cacheRetention: "none",
			onPayload: (value) => {
				payload = value as AnthropicToolPayload;
				throw new PayloadCaptured();
			},
		},
	);
	const result = await stream.result();

	const firstTool = payload?.tools?.[0];
	if (!firstTool) {
		throw new Error(`Expected a tool in the captured Anthropic payload: ${result.errorMessage}`);
	}
	return firstTool;
}

describe("Anthropic strict tool schemas", () => {
	it("only sends the full input schema for strict JSON-schema tools", async () => {
		const legacyParameters = Type.Object(
			{ value: Type.String() },
			{ additionalProperties: false, title: "LookupInput" },
		);
		const legacyTool = await captureFirstTool(createTool(legacyParameters));
		expect(legacyTool.strict).toBeUndefined();
		expect(legacyTool.input_schema).toEqual({
			type: "object",
			properties: legacyParameters.properties,
			required: legacyParameters.required,
		});

		const strictTool = await captureFirstTool(
			createStrictTool(
				Type.Object(
					{ value: Type.String(), optional: Type.Optional(Type.Number()) },
					{ title: "StrictLookupInput" },
				),
			),
		);
		expect(strictTool.strict).toBe(true);
		expect(strictTool.input_schema).toMatchObject({
			additionalProperties: false,
			required: ["value", "optional"],
			properties: { optional: { anyOf: [{ type: "number" }, { type: "null" }] } },
			title: "StrictLookupInput",
		});
	});

	// earendil-works/pi#10074: strict mode breaks `\uXXXX` escapes in string arguments, so "prefer" tools
	// (the built-in read, bash, edit and write) go out non-strict even when the model supports strict tools.
	it("sends prefer tools non-strict with the plain input schema", async () => {
		const parameters = Type.Object({ value: Type.String(), optional: Type.Optional(Type.Number()) });
		const tool = await captureFirstTool(createStrictTool(parameters, "prefer"));
		expect(tool.strict).toBeUndefined();
		expect(tool.input_schema).toEqual({
			type: "object",
			properties: parameters.properties,
			required: ["value"],
		});
	});

	// https://github.com/earendil-works/pi/issues/9953
	it("rejects require tools locally when they use keywords Anthropic strict mode rejects", async () => {
		const unsupportedParameters: Tool["parameters"][] = [
			Type.Object({ timeoutMs: Type.Optional(Type.Integer({ minimum: 1, maximum: 300000 })) }),
			Type.Object({ options: Type.Object({ tags: Type.Array(Type.String(), { minItems: 2 }) }) }),
			Type.Object({ expression: Type.String({ format: "regex" }) }),
		];
		for (const parameters of unsupportedParameters) {
			await expect(captureFirstTool(createStrictTool(parameters))).rejects.toThrow(
				'Tool "lookup" requires JSON-schema constrained sampling',
			);
		}

		const supportedTool = await captureFirstTool(
			createStrictTool(
				Type.Object({
					code: Type.String({ minLength: 1, maxLength: 1000, pattern: "^[a-z]+$" }),
					url: Type.String({ format: "uri" }),
					tags: Type.Array(Type.String(), { minItems: 1 }),
				}),
			),
		);
		expect(supportedTool.strict).toBe(true);
	});
});
