import { clampThinkingLevel } from "../models.ts";
import type {
	Api,
	Model,
	ModelThinkingLevel,
	SamplingParams,
	SimpleStreamOptions,
	StreamOptions,
	ThinkingBudgets,
	ThinkingLevel,
	TranscriptContext,
} from "../types.ts";
import { estimateContextTokens } from "../utils/estimate.ts";

const CONTEXT_SAFETY_TOKENS = 4096;
/**
 * Share of the chars/3.5 estimate also kept free, since its error grows with the estimated text. Code and JSON often
 * run 3.0 to 3.2 chars per token, which the estimate undercounts by 9% to 17% (#10497 measured 3.19); 0.2 covers
 * ratios down to about 2.9. CJK text, at 1 to 1.5 chars per token, is undercounted 2 to 3.5 times; no margin covers
 * that and still leaves an output budget, so it relies on the provider's overflow error and compaction.
 */
const ESTIMATE_SAFETY_RATIO = 0.2;
/**
 * Smallest clamped output limit. Below it the request cannot produce an answer: a limit of 1 (or the 16 some APIs
 * raise it to) ends in an empty `length` response that is not recognized as an overflow (#11236). With this floor a
 * request whose estimate overshot still gets an answer, and one that really does not fit gets the provider's overflow
 * error, which triggers compaction. It equals the fixed margin, so the request exceeds the estimated window only when
 * the estimate alone leaves no room.
 */
const MIN_CLAMPED_MAX_TOKENS = CONTEXT_SAFETY_TOKENS;
const MIN_MAX_TOKENS = 1;

export function clampMaxTokensToContext(model: Model<Api>, context: TranscriptContext, maxTokens: number): number {
	if (model.contextWindow <= 0) return Math.max(MIN_MAX_TOKENS, maxTokens);
	const estimate = estimateContextTokens(context);
	// Reported usage is the provider's own count; only the estimated tail carries the chars/token error.
	const margin = Math.max(CONTEXT_SAFETY_TOKENS, Math.ceil(estimate.trailingTokens * ESTIMATE_SAFETY_RATIO));
	const available = model.contextWindow - estimate.tokens - margin;
	return Math.min(maxTokens, Math.max(MIN_CLAMPED_MAX_TOKENS, available));
}

export function resolveSamplingParams(
	model: Model<Api>,
	thinkingLevel: ModelThinkingLevel,
	requestParams?: SamplingParams,
): SamplingParams | undefined {
	const effectiveThinkingLevel = clampThinkingLevel(model, thinkingLevel);
	const thinkingLevelParams = model.samplingParamsByThinkingLevel?.[effectiveThinkingLevel];
	return model.samplingParams || thinkingLevelParams || requestParams
		? { ...model.samplingParams, ...thinkingLevelParams, ...requestParams }
		: undefined;
}

export function buildBaseOptions(
	model: Model<Api>,
	context: TranscriptContext,
	options?: SimpleStreamOptions,
	apiKey?: string,
): StreamOptions {
	const samplingParams = resolveSamplingParams(model, options?.reasoning ?? "off", options?.samplingParams);
	return {
		temperature: options?.temperature,
		samplingParams,
		maxTokens: clampMaxTokensToContext(model, context, options?.maxTokens ?? model.maxTokens),
		signal: options?.signal,
		telemetry: options?.telemetry,
		apiKey: apiKey || options?.apiKey,
		fetch: options?.fetch,
		transport: options?.transport,
		cacheRetention: options?.cacheRetention,
		sessionId: options?.sessionId,
		immutableMessages: options?.immutableMessages,
		headers: options?.headers,
		identity: options?.identity,
		onPayload: options?.onPayload,
		onResponse: options?.onResponse,
		onProviderStreamEvent: options?.onProviderStreamEvent,
		timeoutMs: options?.timeoutMs,
		websocketConnectTimeoutMs: options?.websocketConnectTimeoutMs,
		maxRetries: options?.maxRetries,
		maxRetryDelayMs: options?.maxRetryDelayMs,
		metadata: options?.metadata,
		env: options?.env,
	};
}

/** Tokens always left for the answer when a thinking budget shares the response ceiling. */
export const MIN_ANSWER_TOKENS = 1024;

export const DEFAULT_THINKING_BUDGETS: ThinkingBudgets = {
	minimal: 1024,
	low: 2048,
	medium: 8192,
	high: 16384,
};

export function clampReasoning(effort: ThinkingLevel | undefined): Exclude<ThinkingLevel, "xhigh" | "max"> | undefined {
	return effort === "xhigh" || effort === "max" ? "high" : effort;
}

export function thinkingBudgetForLevel(reasoningLevel: ThinkingLevel, customBudgets?: ThinkingBudgets): number {
	const budgets = { ...DEFAULT_THINKING_BUDGETS, ...customBudgets };
	const level = clampReasoning(reasoningLevel)!;
	return budgets[level]!;
}

/** Cap a thinking budget so at least MIN_ANSWER_TOKENS remain under a shared response ceiling. */
export function clampThinkingBudgetToAnswerRoom(thinkingBudget: number, ceiling: number): number {
	return Math.min(thinkingBudget, Math.max(0, ceiling - MIN_ANSWER_TOKENS));
}

export function adjustMaxTokensForThinking(
	// Undefined means no explicit caller cap. Use the model cap and fit thinking inside it.
	baseMaxTokens: number | undefined,
	modelMaxTokens: number,
	reasoningLevel: ThinkingLevel,
	customBudgets?: ThinkingBudgets,
): { maxTokens: number; thinkingBudget: number } {
	let thinkingBudget = thinkingBudgetForLevel(reasoningLevel, customBudgets);
	const maxTokens =
		baseMaxTokens === undefined ? modelMaxTokens : Math.min(baseMaxTokens + thinkingBudget, modelMaxTokens);

	if (maxTokens <= thinkingBudget) {
		thinkingBudget = clampThinkingBudgetToAnswerRoom(thinkingBudget, maxTokens);
	}

	return { maxTokens, thinkingBudget };
}
