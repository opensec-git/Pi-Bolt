import { describe, expect, it } from "vitest";
import { fauxAssistantMessage } from "../src/providers/faux.ts";
import { isContextOverflow } from "../src/utils/overflow.ts";
import { isRetryableAssistantError } from "../src/utils/retry.ts";

// Errors that ended a run although a retry or a compaction would have recovered it.

const failed = (errorMessage: string) => fauxAssistantMessage("", { stopReason: "error", errorMessage });

const DASHSCOPE_THROTTLE =
	'429: {"error":{"message":"Allocated quota exceeded, please increase your quota limit.","type":"insufficient_quota","code":"Throttling.AllocationQuota"}}';

describe("retry classification", () => {
	it.each([
		// #12605: a gateway's failed relay of the upstream stream.
		"upstream_error: upstream SSE stream failed",
		// #12622: Gemini's malformed finish reasons on google-generative-ai and google-vertex, as on OpenRouter (#12407).
		"Provider stopped with: MALFORMED_RESPONSE",
		"Provider stopped with: MALFORMED_FUNCTION_CALL",
		// #10656: DashScope's token throttle, sent as 429 insufficient_quota.
		DASHSCOPE_THROTTLE,
		"429 Allocated quota exceeded, please increase your quota limit.",
	])("retries %s", (errorMessage) => {
		expect(isRetryableAssistantError(failed(errorMessage))).toBe(true);
		expect(isContextOverflow(failed(errorMessage), 128000)).toBe(false);
	});

	it.each([
		// OpenAI's exhausted billing uses the same code: still final.
		'429: {"error":{"message":"You exceeded your current quota, please check your plan and billing details.","type":"insufficient_quota","code":"insufficient_quota"}}',
		// DashScope's free tier used up: final.
		"403 Free allocated quota exceeded.",
		"Provider stopped with: SAFETY",
	])("does not retry %s", (errorMessage) => {
		expect(isRetryableAssistantError(failed(errorMessage))).toBe(false);
	});
});

describe("context overflow classification", () => {
	it.each([
		// #10885: llama.cpp when its KV cache fills up while decoding.
		"Context size has been exceeded.",
		// #12625: opencode-go's request byte limit, sent as HTTP 400 (retrying resent the same 51 MB body).
		'400: {"param":null,"type":"invalid_request_error","code":"invalid_request_error","message":"Upstream request failed: [invalid_request_error] decoded body exceeds 50331648 bytes (request_id: gw-ede9672ddf8c-33635dd)"}',
	])("treats %s as overflow", (errorMessage) => {
		expect(isContextOverflow(failed(errorMessage), 128000)).toBe(true);
	});

	// #12382: a rate or quota limit that mentions tokens started a compaction instead of a retry.
	it.each([
		"429 Too many tokens, please wait before trying again.",
		'429: {"error":{"message":"Rate limit reached for TPM: too many tokens"}}',
		"custom-provider API error (429): token limit exceeded",
		"insufficient_quota: token limit exceeded",
		"usage_limit_reached: too many tokens in the last hour",
		"Too many tokens per minute, please slow down",
		"Throttling error: Too many tokens, please wait before trying again.",
	])("does not treat a rate or quota limit as overflow: %s", (errorMessage) => {
		expect(isContextOverflow(failed(errorMessage), 128000)).toBe(false);
	});

	it.each([
		"prompt is too long: 213462 tokens > 200000 maximum",
		"413 request entity too large",
		"400 too many tokens in the prompt",
	])("still treats %s as overflow", (errorMessage) => {
		expect(isContextOverflow(failed(errorMessage), 128000)).toBe(true);
	});
});
