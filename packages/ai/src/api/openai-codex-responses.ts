import type * as NodeZlib from "node:zlib";
import type {
	Tool as OpenAITool,
	ResponseCreateParamsStreaming,
	ResponseInput,
	ResponseStreamEvent,
} from "openai/resources/responses/responses.js";
import { clampThinkingLevel } from "../models.ts";
import { registerSessionResourceCleanup } from "../session-resources.ts";
import type {
	Api,
	AssistantMessage,
	Model,
	ProviderEnv,
	ProviderHeaders,
	SimpleStreamOptions,
	StreamFunction,
	StreamOptions,
	TranscriptContext,
	Usage,
} from "../types.ts";
import { combineAbortSignals } from "../utils/abort-signals.ts";
import {
	appendAssistantMessageDiagnostic,
	createAssistantMessageDiagnostic,
	formatThrownValue,
} from "../utils/diagnostics.ts";
import { formatProviderError, normalizeProviderError } from "../utils/error-body.ts";
import { AssistantMessageEventStream, FifoQueue } from "../utils/event-stream.ts";
import { headersToRecord } from "../utils/headers.ts";
import { getIdentityHeaders } from "../utils/identity.ts";
import { cachedArrayToJSON, cacheJsonSerialization } from "../utils/json-body.ts";
import { resolveHttpProxyUrlForTarget } from "../utils/node-http-proxy.ts";
import { jsonBodyParts } from "../utils/request-body.ts";
import { beginRequestCache, type RequestCacheRun } from "../utils/request-cache.ts";
import { getSystemMessageText } from "../utils/text.ts";
import { getDeclaredTools, getInitialSystemMessage, normalizeContext, resolveTranscript } from "../utils/transcript.ts";
import { uuidv7 } from "../utils/uuid.ts";
import { createGrammarToolInputProperties } from "./constrained-sampling.ts";
import { clampOpenAIPromptCacheKey } from "./openai-prompt-cache.ts";
import {
	convertResolvedResponsesInput,
	convertResponsesMessages,
	convertResponsesTools,
	processResponsesStream,
} from "./openai-responses-shared.ts";
import { buildBaseOptions } from "./simple-options.ts";

// ============================================================================
// Configuration
// ============================================================================

const DEFAULT_CODEX_BASE_URL = "https://chatgpt.com/backend-api";
const JWT_CLAIM_PATH = "https://api.openai.com/auth" as const;
const DEFAULT_MAX_RETRIES = 0;
const BASE_DELAY_MS = 1000;
const DEFAULT_MAX_RETRY_DELAY_MS = 60_000;
const DEFAULT_WEBSOCKET_CONNECT_TIMEOUT_MS = 15_000;
// The Codex backend accepts zstd-compressed request bodies on the SSE responses
// endpoint (the same endpoint the official Codex client compresses against).
const REQUEST_COMPRESSION_ZSTD_LEVEL = 3;
const CODEX_TOOL_CALL_PROVIDERS = new Set(["openai", "openai-codex", "opencode"]);
const WEBSOCKET_MESSAGE_TOO_BIG_CLOSE_CODE = 1009;
const WEBSOCKET_CONNECTION_LIMIT_REACHED_CODE = "websocket_connection_limit_reached";
const PREVIOUS_RESPONSE_NOT_FOUND_CODE = "previous_response_not_found";

const CODEX_RESPONSE_STATUSES = new Set<CodexResponseStatus>([
	"completed",
	"incomplete",
	"failed",
	"cancelled",
	"queued",
	"in_progress",
]);

// ============================================================================
// Types
// ============================================================================

export interface OpenAICodexResponsesOptions extends StreamOptions {
	reasoningEffort?: "none" | "minimal" | "low" | "medium" | "high" | "xhigh" | "max";
	reasoningSummary?: "auto" | "concise" | "detailed" | "off" | "on" | null;
	serviceTier?: ResponseCreateParamsStreaming["service_tier"];
	textVerbosity?: "low" | "medium" | "high";
	toolChoice?: "auto" | "none" | "required";
}

type CodexResponseStatus = "completed" | "incomplete" | "failed" | "cancelled" | "queued" | "in_progress";

interface RequestBody {
	model: string;
	store?: boolean;
	stream?: boolean;
	instructions?: string;
	previous_response_id?: string;
	input?: ResponseInput;
	tools?: OpenAITool[];
	tool_choice?: OpenAICodexResponsesOptions["toolChoice"];
	parallel_tool_calls?: boolean;
	temperature?: number;
	reasoning?: { effort?: string; summary?: string };
	service_tier?: ResponseCreateParamsStreaming["service_tier"];
	text?: { verbosity?: string };
	include?: string[];
	prompt_cache_key?: string;
	[key: string]: unknown;
}

type SuccessfulAssistantMessage = AssistantMessage & { stopReason: "stop" | "length" | "toolUse" };

function assertSuccessfulOutput(output: AssistantMessage): asserts output is SuccessfulAssistantMessage {
	if (output.stopReason === "pending") {
		throw new Error("Codex stream ended without a stop reason");
	}
	if (output.stopReason === "error" || output.stopReason === "aborted") {
		throw new Error(output.errorMessage || "An unknown error occurred");
	}
}

// ============================================================================
// Retry Helpers
// ============================================================================

function isTerminalRateLimitError(errorText: string): boolean {
	return /GoUsageLimitError|FreeUsageLimitError|Monthly usage limit reached|available balance|insufficient_quota|out of budget|quota exceeded|billing/i.test(
		errorText,
	);
}

function isRetryableError(status: number, errorText: string): boolean {
	if (status === 429 && isTerminalRateLimitError(errorText)) {
		return false;
	}
	if (status === 429 || status === 500 || status === 502 || status === 503 || status === 504) {
		return true;
	}
	return /rate.?limit|overloaded|service.?unavailable|upstream.?connect|connection.?refused/i.test(errorText);
}

function getRetryAfterDelayMs(headers: Headers): number | undefined {
	const retryAfterMs = headers.get("retry-after-ms");
	if (retryAfterMs !== null) {
		const millis = Number(retryAfterMs);
		if (Number.isFinite(millis)) {
			return Math.max(0, millis);
		}
	}

	const retryAfter = headers.get("retry-after");
	if (!retryAfter) {
		return undefined;
	}

	const seconds = Number(retryAfter);
	if (Number.isFinite(seconds)) {
		return Math.max(0, seconds * 1000);
	}

	const date = Date.parse(retryAfter);
	if (!Number.isNaN(date)) {
		return Math.max(0, date - Date.now());
	}

	return undefined;
}

class RetryDelayExceededError extends Error {}

function validateRetryDelayMs(delayMs: number, options?: StreamOptions): number {
	const maxRetryDelayMs = options?.maxRetryDelayMs ?? DEFAULT_MAX_RETRY_DELAY_MS;
	if (maxRetryDelayMs > 0 && delayMs > maxRetryDelayMs) {
		throw new RetryDelayExceededError(
			`Server requested ${Math.ceil(delayMs / 1000)}s retry delay (max: ${Math.ceil(maxRetryDelayMs / 1000)}s)`,
		);
	}
	return delayMs;
}

function sleep(ms: number, signal?: AbortSignal): Promise<void> {
	return new Promise((resolve, reject) => {
		if (signal?.aborted) {
			reject(new Error("Request was aborted"));
			return;
		}
		const onAbort = () => {
			clearTimeout(timeout);
			reject(new Error("Request was aborted"));
		};
		const timeout = setTimeout(() => {
			signal?.removeEventListener("abort", onAbort);
			resolve();
		}, ms);
		signal?.addEventListener("abort", onAbort, { once: true });
	});
}

function normalizeTimeoutMs(value: number | undefined): number | undefined {
	if (value === undefined) return undefined;
	if (!Number.isFinite(value) || value < 0) {
		throw new Error(`Invalid timeoutMs: ${String(value)}`);
	}
	return Math.floor(value);
}

// ============================================================================
// Request Compression
// ============================================================================

type ProcessWithBuiltinModule = typeof process & {
	getBuiltinModule?: (id: "node:zlib") => typeof NodeZlib;
};

function loadNodeZlib(): typeof NodeZlib | null {
	if (typeof process === "undefined" || !(process.versions?.node || process.versions?.bun)) {
		return null;
	}
	return (process as ProcessWithBuiltinModule).getBuiltinModule?.("node:zlib") ?? null;
}

function canCompressRequestBodyZstd(): boolean {
	return typeof loadNodeZlib()?.zstdCompressSync === "function";
}

// Returns the zstd-compressed body bytes, or null when compression is
// unavailable (browser/Vite builds). Callers fall back to sending the
// uncompressed JSON when this returns null.
function compressRequestBodyZstd(bodyUtf8: Uint8Array): Uint8Array<ArrayBuffer> | null {
	const zlib = loadNodeZlib();
	if (!zlib || typeof zlib.zstdCompressSync !== "function") {
		return null;
	}
	try {
		const compressed = zlib.zstdCompressSync(bodyUtf8, {
			params: { [zlib.constants.ZSTD_c_compressionLevel]: REQUEST_COMPRESSION_ZSTD_LEVEL },
		});
		// A copy owns a plain ArrayBuffer, which fetch accepts as a body under every lib.
		return new Uint8Array(compressed);
	} catch {
		return null;
	}
}

/**
 * The UTF-8 bytes of JSON.stringify(body). The input items reuse the bytes they were sent as in the conversation's
 * previous request (request-body.ts), and with `cache` the bytes of the items its run handed out, so only new or
 * changed items are serialized again.
 */
function encodeRequestBody(
	body: RequestBody,
	conversation: string | undefined,
	cache: RequestCacheRun | undefined,
): Blob {
	const parts = jsonBodyParts(body, "input", conversation ?? "", cachedArrayToJSON, cache?.sentValues());
	return new Blob(parts ?? [JSON.stringify(body)]);
}

// ============================================================================
// Main Stream Function
// ============================================================================

export const stream: StreamFunction<"openai-codex-responses", OpenAICodexResponsesOptions> = (
	model: Model<"openai-codex-responses">,
	context: TranscriptContext,
	options?: OpenAICodexResponsesOptions,
): AssistantMessageEventStream => {
	const stream = new AssistantMessageEventStream(options);
	const normalizedContext = resolveTranscript(context, model.compat?.supportsMidConvoSystemMessages);

	(async () => {
		const output: AssistantMessage = {
			role: "assistant",
			content: [],
			api: "openai-codex-responses" as Api,
			provider: model.provider,
			model: model.id,
			usage: {
				input: 0,
				output: 0,
				cacheRead: 0,
				cacheWrite: 0,
				totalTokens: 0,
				cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
			},
			stopReason: "pending",
			timestamp: Date.now(),
		};

		let cache: RequestCacheRun | undefined;
		try {
			const apiKey = options?.apiKey;
			if (!apiKey) {
				throw new Error(`No API key for provider: ${model.provider}`);
			}

			const accountId = extractAccountId(apiKey);
			const grammarToolInputProperties = createGrammarToolInputProperties(
				model.compat?.supportsOpenAIGrammarTools ? getDeclaredTools(normalizedContext.messages) : undefined,
				model.compat?.supportsOpenAIGrammarTools ?? false,
			);
			const codexSessionId = clampOpenAIPromptCacheKey(
				options?.cacheRetention === "none" ? undefined : options?.sessionId,
			);
			const cacheSessionId =
				options?.cacheRetention === "none" || options?.disableContinuation ? undefined : options?.sessionId;
			// Everything buildRequestBody's conversion reads besides the messages: the model and its compat settings.
			const payloadHook = options?.onPayload;
			cache = payloadHook
				? undefined
				: beginRequestCache("openai-codex-responses", options, [
						model.id,
						model.provider,
						model.api,
						model.input,
						model.reasoning,
						model.compat,
						grammarToolInputProperties,
					]);
			let body = buildRequestBody(
				model,
				normalizedContext,
				options,
				codexSessionId,
				grammarToolInputProperties,
				cache,
			);
			cache?.end();
			const nextBody = await payloadHook?.call(options, body, model);
			if (nextBody !== undefined) {
				body = nextBody as RequestBody;
			}

			const websocketRequestId = codexSessionId || uuidv7();
			// Codex expects an originator on every request; it is not attribution.
			const identityHeaders = { originator: "pi", ...getIdentityHeaders(model, options?.identity) };
			const sseHeaders = buildSSEHeaders(
				identityHeaders,
				model.headers,
				options?.headers,
				accountId,
				apiKey,
				codexSessionId,
			);
			const websocketHeaders = buildWebSocketHeaders(
				identityHeaders,
				model.headers,
				options?.headers,
				accountId,
				apiKey,
				websocketRequestId,
			);
			// The whole body as bytes is needed only on the SSE path and for a WebSocket failure's diagnostic: a
			// WebSocket request sends its own serialization (only the new input, when it continues a response).
			let bodyBytes: Blob | undefined;
			const getBodyBytes = (): Blob => {
				if (!bodyBytes) {
					cacheJsonSerialization(body.input, options?.sessionId);
					bodyBytes = encodeRequestBody(body, options?.sessionId, cache);
				}
				return bodyBytes;
			};
			const httpTimeoutMs = normalizeTimeoutMs(options?.timeoutMs);
			const websocketConnectTimeoutMs = normalizeTimeoutMs(options?.websocketConnectTimeoutMs);
			const transport = options?.transport || "auto";
			let startEmitted = false;
			const websocketDisabledForSession = transport !== "sse" && isWebSocketSseFallbackActive(cacheSessionId);
			if (websocketDisabledForSession) {
				recordWebSocketSseFallback(cacheSessionId);
			}

			if (transport !== "sse" && !websocketDisabledForSession) {
				let websocketStarted = false;
				let retriedWebSocketConnectionLimit = false;
				let retriedMissingWebSocketContinuation = false;
				while (true) {
					websocketStarted = false;
					try {
						await processWebSocketStream(
							resolveCodexWebSocketUrl(model.baseUrl),
							body,
							websocketHeaders,
							output,
							stream,
							model,
							() => {
								websocketStarted = true;
								if (!startEmitted) {
									startEmitted = true;
									stream.push({ type: "start", partial: output });
								}
							},
							httpTimeoutMs,
							websocketConnectTimeoutMs,
							payloadHook !== undefined,
							cacheSessionId,
							accountId,
							grammarToolInputProperties,
							options,
						);

						if (options?.signal?.aborted) {
							throw new Error("Request was aborted");
						}
						assertSuccessfulOutput(output);
						recordWebSocketSuccess(cacheSessionId);
						stream.push({
							type: "done",
							reason: output.stopReason,
							message: output,
						});
						stream.end();
						return;
					} catch (error) {
						const aborted = options?.signal?.aborted;
						const connectionLimitBeforeStart = !websocketStarted && isWebSocketConnectionLimitReachedError(error);
						const previousResponseNotFound = isPreviousResponseNotFoundError(error);
						if (!aborted && previousResponseNotFound && !retriedMissingWebSocketContinuation) {
							retriedMissingWebSocketContinuation = true;
							continue;
						}
						if (!aborted && connectionLimitBeforeStart && !retriedWebSocketConnectionLimit) {
							retriedWebSocketConnectionLimit = true;
							continue;
						}
						if (aborted || (isCodexNonTransportError(error) && !connectionLimitBeforeStart)) {
							throw error;
						}
						appendAssistantMessageDiagnostic(
							output,
							createAssistantMessageDiagnostic("provider_transport_failure", error, {
								configuredTransport: transport,
								...(websocketStarted ? {} : { fallbackTransport: "sse" }),
								eventsEmitted: websocketStarted,
								phase: websocketStarted ? "after_message_stream_start" : "before_message_stream_start",
								requestBytes: getBodyBytes().size,
							}),
						);
						recordWebSocketFailure(cacheSessionId, error);
						if (websocketStarted) {
							throw error;
						}
						recordWebSocketSseFallback(cacheSessionId);
						break;
					}
				}
			}

			// Compress the request body once for the SSE path. The Codex backend
			// decodes Content-Encoding: zstd; the WebSocket transport above sends the
			// uncompressed JSON frame, matching the official Codex client.
			const compressedBody = canCompressRequestBodyZstd()
				? compressRequestBodyZstd(new Uint8Array(await getBodyBytes().arrayBuffer()))
				: null;
			if (compressedBody) {
				sseHeaders.set("content-encoding", "zstd");
			}
			const sseBody: Uint8Array<ArrayBuffer> | string = compressedBody ?? JSON.stringify(body);

			// Fetch with retry logic for rate limits and transient errors
			let response: Response | undefined;
			let lastError: Error | undefined;
			const maxRetries = options?.maxRetries ?? DEFAULT_MAX_RETRIES;

			for (let attempt = 0; attempt <= maxRetries; attempt++) {
				if (options?.signal?.aborted) {
					throw new Error("Request was aborted");
				}

				try {
					const headerTimeoutSignal =
						httpTimeoutMs !== undefined && httpTimeoutMs > 0 ? AbortSignal.timeout(httpTimeoutMs) : undefined;
					const combinedSignal = combineAbortSignals([options?.signal, headerTimeoutSignal]);
					try {
						response = await (options?.fetch ?? globalThis.fetch)(resolveCodexUrl(model.baseUrl), {
							method: "POST",
							headers: sseHeaders,
							body: sseBody,
							signal: combinedSignal.signal,
						});
					} catch (error) {
						if (headerTimeoutSignal?.aborted && !options?.signal?.aborted) {
							throw new Error(`Codex SSE response headers timed out after ${httpTimeoutMs}ms`);
						}
						throw error;
					} finally {
						combinedSignal.cleanup();
					}
					await options?.onResponse?.(
						{ status: response.status, headers: headersToRecord(response.headers) },
						model,
					);

					if (response.ok) {
						break;
					}

					const errorText = await response.text();
					if (attempt < maxRetries && isRetryableError(response.status, errorText)) {
						const retryAfterDelayMs = getRetryAfterDelayMs(response.headers);
						const delayMs =
							retryAfterDelayMs === undefined
								? BASE_DELAY_MS * 2 ** attempt
								: validateRetryDelayMs(retryAfterDelayMs, options);

						await sleep(delayMs, options?.signal);
						continue;
					}

					// Parse error for friendly message on final attempt or non-retryable error
					const fakeResponse = new Response(errorText, {
						status: response.status,
						statusText: response.statusText,
					});
					const info = await parseErrorResponse(fakeResponse);
					throw new Error(info.friendlyMessage || info.message);
				} catch (error) {
					if (error instanceof Error) {
						if (error.name === "AbortError" || error.message === "Request was aborted") {
							throw new Error("Request was aborted");
						}
					}
					lastError = error instanceof Error ? error : new Error(String(error));
					// Network errors are retryable
					if (
						attempt < maxRetries &&
						!(lastError instanceof RetryDelayExceededError) &&
						!lastError.message.includes("usage limit")
					) {
						const delayMs = BASE_DELAY_MS * 2 ** attempt;
						await sleep(delayMs, options?.signal);
						continue;
					}
					throw lastError;
				}
			}

			if (!response?.ok) {
				throw lastError ?? new Error("Failed after retries");
			}

			if (!response.body) {
				throw new Error("No response body");
			}

			if (!startEmitted) {
				startEmitted = true;
				stream.push({ type: "start", partial: output });
			}
			await processStream(response, output, stream, model, grammarToolInputProperties, options);

			if (options?.signal?.aborted) {
				throw new Error("Request was aborted");
			}

			assertSuccessfulOutput(output);
			stream.push({ type: "done", reason: output.stopReason, message: output });
			stream.end();
		} catch (error) {
			cache?.end();
			output.stopReason = options?.signal?.aborted ? "aborted" : "error";
			output.errorMessage = formatProviderError(normalizeProviderError(error));
			stream.push({ type: "error", reason: output.stopReason, error: output });
			stream.end();
		}
	})();

	return stream;
};

export const streamSimple: StreamFunction<"openai-codex-responses", SimpleStreamOptions> = (
	model: Model<"openai-codex-responses">,
	context: TranscriptContext,
	options?: SimpleStreamOptions,
): AssistantMessageEventStream => {
	const apiKey = options?.apiKey;
	if (!apiKey) {
		throw new Error(`No API key for provider: ${model.provider}`);
	}

	const base = {
		...buildBaseOptions(model, context, options, apiKey),
		toolChoice: options?.toolChoice,
	} satisfies OpenAICodexResponsesOptions;
	const clampedReasoning = options?.reasoning ? clampThinkingLevel(model, options.reasoning) : undefined;
	const reasoningEffort = clampedReasoning === "off" ? undefined : clampedReasoning;

	return stream(model, context, {
		...base,
		reasoningEffort,
	} satisfies OpenAICodexResponsesOptions);
};

// ============================================================================
// Request Building
// ============================================================================

function buildRequestBody(
	model: Model<"openai-codex-responses">,
	context: TranscriptContext,
	options: OpenAICodexResponsesOptions | undefined,
	cacheSessionId: string | undefined,
	grammarToolInputProperties: ReadonlyMap<string, string>,
	cache?: RequestCacheRun,
): RequestBody {
	const supportsStrictMode = model.compat?.supportsStrictMode ?? true;
	const supportsOpenAIGrammarTools = model.compat?.supportsOpenAIGrammarTools ?? false;
	const supportsAdditionalTools = model.compat?.supportsAdditionalTools ?? false;
	const supportsToolSearch = model.compat?.supportsToolSearch ?? false;
	const { input: messages, requestTools } = convertResolvedResponsesInput(model, context, CODEX_TOOL_CALL_PROVIDERS, {
		includeSystemPrompt: false,
		grammarToolInputProperties,
		supportsMidConvoSystemMessages: model.compat?.supportsMidConvoSystemMessages ?? false,
		supportsAdditionalTools,
		supportsToolSearch,
		toolOptions: { strict: null, supportsStrictMode, supportsOpenAIGrammarTools },
		cache,
	});

	const initialSystemMessage = getInitialSystemMessage(context.messages);
	const instructions = initialSystemMessage ? getSystemMessageText(initialSystemMessage) : "";
	const body: RequestBody = {
		model: model.id,
		store: false,
		stream: true,
		instructions: instructions || "You are a helpful assistant.",
		input: messages,
		text: { verbosity: options?.textVerbosity || "low" },
		include: ["reasoning.encrypted_content"],
		prompt_cache_key: cacheSessionId,
		tool_choice: options?.toolChoice ?? "auto",
		parallel_tool_calls: true,
	};

	if (options?.temperature !== undefined) {
		body.temperature = options.temperature;
	}

	if (options?.serviceTier !== undefined) {
		body.service_tier = options.serviceTier;
	}

	if (requestTools.length > 0) {
		body.tools = convertResponsesTools(requestTools, {
			strict: null,
			supportsStrictMode,
			supportsOpenAIGrammarTools,
		});
	}

	if (options?.reasoningEffort !== undefined) {
		const effort =
			options.reasoningEffort === "none"
				? model.thinkingLevelMap?.off === undefined
					? "none"
					: model.thinkingLevelMap.off
				: (model.thinkingLevelMap?.[options.reasoningEffort] ?? options.reasoningEffort);
		if (effort !== null) {
			body.reasoning = {
				effort,
				summary: options.reasoningSummary ?? "auto",
			};
		}
	} else if (model.reasoning && model.thinkingLevelMap?.off !== null) {
		body.reasoning = { effort: model.thinkingLevelMap?.off ?? "none" };
	}

	return body;
}

function getServiceTierCostMultiplier(
	model: Pick<Model<"openai-codex-responses">, "id">,
	serviceTier: ResponseCreateParamsStreaming["service_tier"] | undefined,
): number {
	switch (serviceTier) {
		case "flex":
			return 0.5;
		case "priority":
			return model.id === "gpt-5.5" ? 2.5 : 2;
		default:
			return 1;
	}
}

function applyServiceTierPricing(
	usage: Usage,
	serviceTier: ResponseCreateParamsStreaming["service_tier"] | undefined,
	model: Pick<Model<"openai-codex-responses">, "id">,
) {
	const multiplier = getServiceTierCostMultiplier(model, serviceTier);
	if (multiplier === 1) return;

	usage.cost.input *= multiplier;
	usage.cost.output *= multiplier;
	usage.cost.cacheRead *= multiplier;
	usage.cost.cacheWrite *= multiplier;
	usage.cost.total = usage.cost.input + usage.cost.output + usage.cost.cacheRead + usage.cost.cacheWrite;
}

function resolveCodexServiceTier(
	responseServiceTier: ResponseCreateParamsStreaming["service_tier"] | undefined,
	requestServiceTier: ResponseCreateParamsStreaming["service_tier"] | undefined,
): ResponseCreateParamsStreaming["service_tier"] | undefined {
	if (responseServiceTier === "default" && (requestServiceTier === "flex" || requestServiceTier === "priority")) {
		return requestServiceTier;
	}
	return responseServiceTier ?? requestServiceTier;
}

function resolveCodexUrl(baseUrl?: string): string {
	const raw = baseUrl && baseUrl.trim().length > 0 ? baseUrl : DEFAULT_CODEX_BASE_URL;
	const normalized = raw.replace(/\/+$/, "");
	if (normalized.endsWith("/codex/responses")) return normalized;
	if (normalized.endsWith("/codex")) return `${normalized}/responses`;
	return `${normalized}/codex/responses`;
}

function resolveCodexWebSocketUrl(baseUrl?: string): string {
	const url = new URL(resolveCodexUrl(baseUrl));
	if (url.protocol === "https:") url.protocol = "wss:";
	if (url.protocol === "http:") url.protocol = "ws:";
	return url.toString();
}

// ============================================================================
// Response Processing
// ============================================================================

async function processStream(
	response: Response,
	output: AssistantMessage,
	stream: AssistantMessageEventStream,
	model: Model<"openai-codex-responses">,
	grammarToolInputProperties: ReadonlyMap<string, string>,
	options?: OpenAICodexResponsesOptions,
): Promise<void> {
	await processResponsesStream(
		mapCodexEvents(parseSSE(response, options?.signal), output, model, options?.onProviderStreamEvent),
		output,
		stream,
		model,
		{
			serviceTier: options?.serviceTier,
			grammarToolInputProperties,
			resolveServiceTier: resolveCodexServiceTier,
			applyServiceTierPricing: (usage, serviceTier) => applyServiceTierPricing(usage, serviceTier, model),
		},
	);
}

class CodexApiError extends Error {
	readonly code?: string;
	readonly payload?: Record<string, unknown>;

	constructor(message: string, options?: { code?: string; payload?: Record<string, unknown>; cause?: unknown }) {
		super(message);
		this.name = "CodexApiError";
		this.code = options?.code;
		this.payload = options?.payload;
		this.cause = options?.cause;
	}
}

class CodexProtocolError extends Error {
	readonly payload?: unknown;

	constructor(message: string, options?: { payload?: unknown; cause?: unknown }) {
		super(message);
		this.name = "CodexProtocolError";
		this.payload = options?.payload;
		this.cause = options?.cause;
	}
}

class ProviderStreamEventCallbackError extends Error {
	constructor(cause: unknown) {
		super(formatThrownValue(cause));
		this.name = "ProviderStreamEventCallbackError";
		this.cause = cause;
	}
}

function isCodexNonTransportError(error: unknown): boolean {
	return (
		error instanceof CodexApiError ||
		error instanceof CodexProtocolError ||
		error instanceof ProviderStreamEventCallbackError
	);
}

function isWebSocketConnectionLimitReachedError(error: unknown): boolean {
	return error instanceof CodexApiError && error.code === WEBSOCKET_CONNECTION_LIMIT_REACHED_CODE;
}

function isPreviousResponseNotFoundError(error: unknown): boolean {
	return error instanceof CodexApiError && error.code === PREVIOUS_RESPONSE_NOT_FOUND_CODE;
}

function extractCodexEventError(event: Record<string, unknown>): { code?: string; message?: string } {
	const nested = event.error && typeof event.error === "object" ? (event.error as Record<string, unknown>) : undefined;
	return {
		code: typeof event.code === "string" ? event.code : typeof nested?.code === "string" ? nested.code : undefined,
		message:
			typeof event.message === "string"
				? event.message
				: typeof nested?.message === "string"
					? nested.message
					: undefined,
	};
}

async function* mapCodexEvents(
	events: AsyncIterable<Record<string, unknown>>,
	output: AssistantMessage,
	model: Model<"openai-codex-responses">,
	onProviderStreamEvent: StreamOptions["onProviderStreamEvent"] | undefined,
): AsyncGenerator<ResponseStreamEvent> {
	for await (const event of events) {
		try {
			await onProviderStreamEvent?.(event, model);
		} catch (error) {
			// Keep callback failures out of Codex's WebSocket retry and SSE fallback path.
			throw new ProviderStreamEventCallbackError(error);
		}
		const type = typeof event.type === "string" ? event.type : undefined;
		if (!type) continue;

		if (type === "error") {
			const { code, message } = extractCodexEventError(event);
			throw new CodexApiError(`Codex error: ${message || code || JSON.stringify(event)}`, {
				code,
				payload: event,
			});
		}

		if (type === "response.failed") {
			const response = (event as { response?: { error?: { code?: string; message?: string } } }).response;
			const code = response?.error?.code;
			const message = response?.error?.message;
			throw new CodexApiError(message || "Codex response failed", { code, payload: event });
		}

		if (type === "response.done" || type === "response.completed" || type === "response.incomplete") {
			const response = (event as { response?: { status?: unknown; end_turn?: unknown } }).response;
			if (typeof response?.end_turn === "boolean") {
				output.endTurn = response.end_turn;
			}
			const normalizedResponse = response
				? { ...response, status: normalizeCodexStatus(response.status) }
				: response;
			yield { ...event, type: "response.completed", response: normalizedResponse } as ResponseStreamEvent;
			return;
		}

		yield event as unknown as ResponseStreamEvent;
	}
}

function normalizeCodexStatus(status: unknown): CodexResponseStatus | undefined {
	if (typeof status !== "string") return undefined;
	return CODEX_RESPONSE_STATUSES.has(status as CodexResponseStatus) ? (status as CodexResponseStatus) : undefined;
}

// ============================================================================
// SSE Parsing
// ============================================================================

async function* parseSSE(response: Response, signal?: AbortSignal): AsyncGenerator<Record<string, unknown>> {
	if (!response.body) return;

	const reader = response.body.getReader();
	const decoder = new TextDecoder();
	let buffer = "";
	const onAbort = () => {
		void reader.cancel().catch(() => {});
	};
	signal?.addEventListener("abort", onAbort, { once: true });

	try {
		while (true) {
			if (signal?.aborted) {
				throw new Error("Request was aborted");
			}
			const { done, value } = await reader.read();
			if (signal?.aborted) {
				throw new Error("Request was aborted");
			}
			buffer += done ? decoder.decode() : decoder.decode(value, { stream: true });
			// Treat EOF as terminating the residual SSE frame.
			if (done && buffer.trim()) buffer += "\n\n";

			let idx = buffer.indexOf("\n\n");
			while (idx !== -1) {
				const chunk = buffer.slice(0, idx);
				buffer = buffer.slice(idx + 2);

				const dataLines = chunk
					.split("\n")
					.filter((l) => l.startsWith("data:"))
					.map((l) => l.slice(5).trim());
				if (dataLines.length > 0) {
					const data = dataLines.join("\n").trim();
					if (data && data !== "[DONE]") {
						try {
							yield JSON.parse(data) as Record<string, unknown>;
						} catch (cause) {
							throw new CodexProtocolError(`Invalid Codex SSE JSON: ${formatThrownValue(cause)}`, {
								cause,
								payload: data,
							});
						}
					}
				}
				idx = buffer.indexOf("\n\n");
			}

			if (done) break;
		}
	} finally {
		signal?.removeEventListener("abort", onAbort);
		try {
			await reader.cancel();
		} catch {}
		try {
			reader.releaseLock();
		} catch {}
	}
}

// ============================================================================
// WebSocket Parsing
// ============================================================================

const OPENAI_BETA_RESPONSES_WEBSOCKETS = "responses_websockets=2026-02-06";
const SESSION_WEBSOCKET_CACHE_TTL_MS = 5 * 60 * 1000;
const SESSION_WEBSOCKET_MAX_AGE_MS = 55 * 60 * 1000;

type WebSocketEventType = "open" | "message" | "error" | "close";
type WebSocketListener = (event: unknown) => void;

interface WebSocketLike {
	close(code?: number, reason?: string): void;
	send(data: string): void;
	addEventListener(type: WebSocketEventType, listener: WebSocketListener): void;
	removeEventListener(type: WebSocketEventType, listener: WebSocketListener): void;
}

interface CachedWebSocketContinuationState {
	head: string;
	tail: string;
	/** Exactly the input already present on this connection, including the last assistant response. */
	input: (ResponseInput[number] | SentJson)[];
	lastResponseId: string;
}

interface CachedWebSocketConnection {
	socket: WebSocketLike;
	busy: boolean;
	createdAt: number;
	cancelIdle?: () => void;
	continuation?: CachedWebSocketContinuationState;
}

export interface OpenAICodexWebSocketDebugStats {
	requests: number;
	connectionsCreated: number;
	connectionsReused: number;
	cachedContextRequests: number;
	storeTrueRequests: number;
	fullContextRequests: number;
	deltaRequests: number;
	lastInputItems: number;
	lastDeltaInputItems?: number;
	lastPreviousResponseId?: string;
	websocketFailures: number;
	sseFallbacks: number;
	websocketFallbackActive?: boolean;
	lastWebSocketError?: string;
}

// After a WebSocket failure a session uses SSE until `retryAt`, then tries WebSocket again. Each
// consecutive failure doubles the cooldown up to the maximum; a WebSocket success clears the state.
interface WebSocketSseFallbackState {
	consecutiveFailures: number;
	retryAt: number;
}

const WEBSOCKET_SSE_FALLBACK_BASE_COOLDOWN_MS = 60 * 1000;
const WEBSOCKET_SSE_FALLBACK_MAX_COOLDOWN_MS = 30 * 60 * 1000;

const websocketSessionCache = new Map<string, Map<string, CachedWebSocketConnection>>();
const websocketDebugStats = new Map<string, OpenAICodexWebSocketDebugStats>();
const websocketSseFallbackSessions = new Map<string, WebSocketSseFallbackState>();

function getOrCreateWebSocketDebugStats(sessionId: string): OpenAICodexWebSocketDebugStats {
	let stats = websocketDebugStats.get(sessionId);
	if (!stats) {
		stats = {
			requests: 0,
			connectionsCreated: 0,
			connectionsReused: 0,
			cachedContextRequests: 0,
			storeTrueRequests: 0,
			fullContextRequests: 0,
			deltaRequests: 0,
			lastInputItems: 0,
			websocketFailures: 0,
			sseFallbacks: 0,
		};
		websocketDebugStats.set(sessionId, stats);
	}
	return stats;
}

export function getOpenAICodexWebSocketDebugStats(sessionId: string): OpenAICodexWebSocketDebugStats | undefined {
	const stats = websocketDebugStats.get(sessionId);
	if (!stats) return undefined;
	// The fallback expires with time rather than with a request, so report its current state.
	return stats.websocketFallbackActive === undefined
		? { ...stats }
		: { ...stats, websocketFallbackActive: isWebSocketSseFallbackActive(sessionId) };
}

export function resetOpenAICodexWebSocketDebugStats(sessionId?: string): void {
	if (sessionId) {
		websocketDebugStats.delete(sessionId);
		websocketSseFallbackSessions.delete(sessionId);
		return;
	}
	websocketDebugStats.clear();
	websocketSseFallbackSessions.clear();
}

export function closeOpenAICodexWebSocketSessions(sessionId?: string): void {
	const closeEntry = (entry: CachedWebSocketConnection) => {
		entry.cancelIdle?.();
		entry.continuation = undefined;
		closeWebSocketSilently(entry.socket, 1000, "debug_close");
	};
	if (sessionId) {
		for (const entry of websocketSessionCache.get(sessionId)?.values() ?? []) closeEntry(entry);
		websocketSessionCache.delete(sessionId);
		return;
	}
	for (const accountEntries of websocketSessionCache.values()) {
		for (const entry of accountEntries.values()) closeEntry(entry);
	}
	websocketSessionCache.clear();
}

registerSessionResourceCleanup(closeOpenAICodexWebSocketSessions);

function isWebSocketSseFallbackActive(sessionId: string | undefined): boolean {
	const state = sessionId ? websocketSseFallbackSessions.get(sessionId) : undefined;
	return state !== undefined && Date.now() < state.retryAt;
}

function recordWebSocketSseFallback(sessionId: string | undefined): void {
	if (!sessionId) return;
	const stats = getOrCreateWebSocketDebugStats(sessionId);
	stats.sseFallbacks++;
	stats.websocketFallbackActive = isWebSocketSseFallbackActive(sessionId);
}

function recordWebSocketFailure(sessionId: string | undefined, error: unknown): void {
	if (!sessionId) return;
	// A concurrent request that fails during an active cooldown does not count as another
	// failed retry, so only extend the backoff once the previous cooldown has run out.
	if (!isWebSocketSseFallbackActive(sessionId)) {
		const consecutiveFailures = (websocketSseFallbackSessions.get(sessionId)?.consecutiveFailures ?? 0) + 1;
		const cooldownMs = Math.min(
			WEBSOCKET_SSE_FALLBACK_BASE_COOLDOWN_MS * 2 ** (consecutiveFailures - 1),
			WEBSOCKET_SSE_FALLBACK_MAX_COOLDOWN_MS,
		);
		websocketSseFallbackSessions.set(sessionId, { consecutiveFailures, retryAt: Date.now() + cooldownMs });
	}

	const stats = getOrCreateWebSocketDebugStats(sessionId);
	stats.websocketFailures++;
	stats.lastWebSocketError = formatThrownValue(error);
	stats.websocketFallbackActive = true;
}

function recordWebSocketSuccess(sessionId: string | undefined): void {
	if (!sessionId || !websocketSseFallbackSessions.delete(sessionId)) return;
	const stats = websocketDebugStats.get(sessionId);
	if (stats) stats.websocketFallbackActive = false;
}

type WebSocketConstructor = new (
	url: string,
	protocols?: string | string[] | { headers?: Record<string, string> },
) => WebSocketLike;

let _cachedWebsocket: WebSocketConstructor | null = null;
async function getWebSocketConstructor(env?: ProviderEnv): Promise<WebSocketConstructor | null> {
	if (!env && _cachedWebsocket) return _cachedWebsocket;

	// bun doesn't respect http proxy envs, ref: https://github.com/oven-sh/bun/issues/15489
	// TODO: remove this when bun supports proxy envs in websocket.
	if (typeof process !== "undefined" && process.versions?.bun) {
		const WebSocketWithProxy = class extends WebSocket {
			constructor(url: string | URL, options?: string | string[] | Record<string, unknown>) {
				let _opts: Record<string, unknown> = {};
				if (Array.isArray(options) || typeof options === "string") {
					_opts = { protocols: options };
				} else {
					_opts = { ...options };
				}

				const proxyUrl = resolveHttpProxyUrlForTarget(
					url.toString().replace(/^wss:/, "https:").replace(/^ws:/, "http:"),
					env,
				);
				super(url, { ..._opts, ...(proxyUrl ? { proxy: proxyUrl.toString() } : {}) } as any);
			}
		};
		if (!env) {
			_cachedWebsocket = WebSocketWithProxy;
		}
		return WebSocketWithProxy;
	}

	const ctor = (globalThis as { WebSocket?: unknown }).WebSocket;
	if (typeof ctor !== "function") return null;
	return ctor as unknown as WebSocketConstructor;
}

class WebSocketCloseError extends Error {
	readonly code?: number;
	readonly reason?: string;
	readonly wasClean?: boolean;

	constructor(message: string, options?: { code?: number; reason?: string; wasClean?: boolean }) {
		super(message);
		this.name = "WebSocketCloseError";
		this.code = options?.code;
		this.reason = options?.reason;
		this.wasClean = options?.wasClean;
	}
}

function getWebSocketReadyState(socket: WebSocketLike): number | undefined {
	const readyState = (socket as { readyState?: unknown }).readyState;
	return typeof readyState === "number" ? readyState : undefined;
}

function isWebSocketReusable(socket: WebSocketLike): boolean {
	const readyState = getWebSocketReadyState(socket);
	// If readyState is unavailable, assume the runtime keeps it open/reusable.
	return readyState === undefined || readyState === 1;
}

function isWebSocketSessionExpired(entry: CachedWebSocketConnection): boolean {
	return Date.now() - entry.createdAt >= SESSION_WEBSOCKET_MAX_AGE_MS;
}

function closeWebSocketSilently(socket: WebSocketLike, code = 1000, reason = "done"): void {
	try {
		socket.close(code, reason);
	} catch {}
}

function forgetWebSocket(sessionId: string, accountId: string, entry: CachedWebSocketConnection): void {
	entry.cancelIdle?.();
	entry.continuation = undefined;
	const entries = websocketSessionCache.get(sessionId);
	if (entries?.get(accountId) !== entry) return;
	entries.delete(accountId);
	if (entries.size === 0) websocketSessionCache.delete(sessionId);
}

function leaseCachedWebSocket(sessionId: string, accountId: string, entry: CachedWebSocketConnection, reused: boolean) {
	entry.cancelIdle?.();
	entry.busy = true;
	return {
		socket: entry.socket,
		entry,
		reused,
		release({ keep }: { keep?: boolean } = {}) {
			if (
				!keep ||
				!isWebSocketReusable(entry.socket) ||
				websocketSessionCache.get(sessionId)?.get(accountId) !== entry
			) {
				forgetWebSocket(sessionId, accountId, entry);
				closeWebSocketSilently(entry.socket);
				return;
			}
			entry.busy = false;
			const onClose = () => forgetWebSocket(sessionId, accountId, entry);
			const timer = setTimeout(() => {
				onClose();
				closeWebSocketSilently(entry.socket, 1000, "idle_timeout");
			}, SESSION_WEBSOCKET_CACHE_TTL_MS);
			timer.unref?.();
			entry.cancelIdle = () => {
				entry.cancelIdle = undefined;
				clearTimeout(timer);
				entry.socket.removeEventListener("close", onClose);
			};
			entry.socket.addEventListener("close", onClose);
		},
	};
}

async function connectWebSocket(
	url: string,
	headers: Headers,
	signal?: AbortSignal,
	connectTimeoutMs = DEFAULT_WEBSOCKET_CONNECT_TIMEOUT_MS,
	env?: ProviderEnv,
): Promise<WebSocketLike> {
	const WebSocketCtor = await getWebSocketConstructor(env);
	if (!WebSocketCtor) {
		throw new Error("WebSocket transport is not available in this runtime");
	}

	const wsHeaders = headersToRecord(headers);
	delete wsHeaders["OpenAI-Beta"];

	return new Promise<WebSocketLike>((resolve, reject) => {
		let settled = false;
		let timeout: ReturnType<typeof setTimeout> | undefined;
		let socket: WebSocketLike;

		try {
			socket = new WebSocketCtor(url, { headers: wsHeaders });
		} catch (error) {
			reject(error instanceof Error ? error : new Error(String(error)));
			return;
		}

		const cleanup = () => {
			if (timeout) {
				clearTimeout(timeout);
				timeout = undefined;
			}
			socket.removeEventListener("open", onOpen);
			socket.removeEventListener("error", onError);
			socket.removeEventListener("close", onClose);
			signal?.removeEventListener("abort", onAbort);
		};
		const fail = (error: Error, closeReason?: string) => {
			if (settled) return;
			settled = true;
			cleanup();
			if (closeReason) {
				closeWebSocketSilently(socket, 1000, closeReason);
			}
			reject(error);
		};
		const onOpen: WebSocketListener = () => {
			if (settled) return;
			settled = true;
			cleanup();
			resolve(socket);
		};
		const onError: WebSocketListener = (event) => {
			fail(extractWebSocketError(event));
		};
		const onClose: WebSocketListener = (event) => {
			fail(extractWebSocketCloseError(event));
		};
		const onAbort = () => {
			fail(new Error("Request was aborted"), "aborted");
		};

		socket.addEventListener("open", onOpen);
		socket.addEventListener("error", onError);
		socket.addEventListener("close", onClose);
		signal?.addEventListener("abort", onAbort);

		if (connectTimeoutMs > 0) {
			timeout = setTimeout(() => {
				fail(new Error(`WebSocket connect timeout after ${connectTimeoutMs}ms`), "connect_timeout");
			}, connectTimeoutMs);
		}
		if (signal?.aborted) {
			onAbort();
		}
	});
}

async function acquireWebSocket(
	url: string,
	headers: Headers,
	sessionId: string | undefined,
	accountId: string,
	signal?: AbortSignal,
	connectTimeoutMs?: number,
	env?: ProviderEnv,
): Promise<{
	socket: WebSocketLike;
	entry?: CachedWebSocketConnection;
	reused: boolean;
	release: (options?: { keep?: boolean }) => void;
}> {
	const cached = sessionId ? websocketSessionCache.get(sessionId)?.get(accountId) : undefined;
	if (sessionId && cached && !cached.busy) {
		const expired = isWebSocketSessionExpired(cached);
		if (!expired && isWebSocketReusable(cached.socket))
			return leaseCachedWebSocket(sessionId, accountId, cached, true);
		forgetWebSocket(sessionId, accountId, cached);
		closeWebSocketSilently(cached.socket, 1000, expired ? "connection_age_limit" : "done");
	}

	const socket = await connectWebSocket(url, headers, signal, connectTimeoutMs, env);
	// A concurrent connect may have filled the slot while this socket was opening.
	let entries = sessionId ? websocketSessionCache.get(sessionId) : undefined;
	if (!sessionId || cached?.busy || entries?.has(accountId)) {
		return { socket, reused: false, release: () => closeWebSocketSilently(socket) };
	}
	if (!entries) {
		entries = new Map();
		websocketSessionCache.set(sessionId, entries);
	}
	const entry: CachedWebSocketConnection = { socket, busy: true, createdAt: Date.now() };
	entries.set(accountId, entry);
	return leaseCachedWebSocket(sessionId, accountId, entry, false);
}

function extractWebSocketError(event: unknown): Error {
	if (event && typeof event === "object") {
		const message = "message" in event ? (event as { message?: unknown }).message : undefined;
		if (typeof message === "string" && message.length > 0) {
			return new Error(message);
		}

		const nestedError = "error" in event ? (event as { error?: unknown }).error : undefined;
		if (nestedError instanceof Error && nestedError.message.length > 0) {
			return nestedError;
		}
		if (nestedError && typeof nestedError === "object" && "message" in nestedError) {
			const nestedMessage = (nestedError as { message?: unknown }).message;
			if (typeof nestedMessage === "string" && nestedMessage.length > 0) {
				return new Error(nestedMessage);
			}
		}
	}
	return new Error("WebSocket error");
}

function extractWebSocketCloseError(event: unknown): Error {
	if (event && typeof event === "object") {
		const code = "code" in event ? (event as { code?: unknown }).code : undefined;
		const reason = "reason" in event ? (event as { reason?: unknown }).reason : undefined;
		const wasClean = "wasClean" in event ? (event as { wasClean?: unknown }).wasClean : undefined;
		const codeText = typeof code === "number" ? ` ${code}` : "";
		let reasonText = typeof reason === "string" && reason.length > 0 ? ` ${reason}` : "";
		if (!reasonText && code === WEBSOCKET_MESSAGE_TOO_BIG_CLOSE_CODE) {
			reasonText = " message too big";
		}
		return new WebSocketCloseError(`WebSocket closed${codeText}${reasonText}`.trim(), {
			code: typeof code === "number" ? code : undefined,
			reason: typeof reason === "string" && reason.length > 0 ? reason : undefined,
			wasClean: typeof wasClean === "boolean" ? wasClean : undefined,
		});
	}
	return new Error("WebSocket closed");
}

function decodeWebSocketData(data: unknown): string | null | Promise<string> {
	if (typeof data === "string") return data;
	if (data instanceof ArrayBuffer) {
		return new TextDecoder().decode(new Uint8Array(data));
	}
	if (ArrayBuffer.isView(data)) {
		const view = data as ArrayBufferView;
		return new TextDecoder().decode(new Uint8Array(view.buffer, view.byteOffset, view.byteLength));
	}
	if (data && typeof data === "object" && "arrayBuffer" in data) {
		const blobLike = data as { arrayBuffer: () => Promise<ArrayBuffer> };
		return blobLike.arrayBuffer().then((bytes) => new TextDecoder().decode(new Uint8Array(bytes)));
	}
	return null;
}

async function* parseWebSocket(
	socket: WebSocketLike,
	signal?: AbortSignal,
	idleTimeoutMs?: number,
): AsyncGenerator<Record<string, unknown>> {
	const queue = new FifoQueue<Record<string, unknown>>();
	let waitingFrames: FifoQueue<unknown> | undefined;
	let pendingError: Error | undefined;
	let pending: (() => void) | null = null;
	let done = false;
	let failed: Error | null = null;
	let sawCompletion = false;

	const wake = () => {
		if (!pending) return;
		const resolve = pending;
		pending = null;
		resolve();
	};

	const invalid = (cause: unknown, payload: string | null) => {
		failed = new CodexProtocolError(`Invalid Codex WebSocket JSON: ${formatThrownValue(cause)}`, { cause, payload });
		done = true;
		wake();
	};

	const accept = (text: string | null) => {
		if (!text || done) return;
		try {
			const parsed = JSON.parse(text) as Record<string, unknown>;
			const type = parsed.type;
			if (type === "response.completed" || type === "response.done" || type === "response.incomplete") {
				sawCompletion = true;
				done = true;
			}
			queue.enqueue(parsed);
			wake();
		} catch (cause) {
			invalid(cause, text);
		}
	};

	const decode = (data: unknown): void | Promise<void> => {
		const text = decodeWebSocketData(data);
		if (text !== null && typeof text !== "string") return text.then(accept);
		accept(text);
	};

	// A Blob can finish decoding after subsequent text frames or the socket close. Keep that arrival order.
	const drain = async (first: Promise<void>) => {
		try {
			await first;
			while (!done && waitingFrames?.length) {
				const decoded = decode(waitingFrames.dequeue());
				if (decoded) await decoded;
			}
		} catch (cause) {
			if (!done) invalid(cause, null);
		} finally {
			waitingFrames = undefined;
			if (pendingError && !done) {
				failed = pendingError;
				done = true;
				wake();
			}
		}
	};

	const onMessage: WebSocketListener = (event) => {
		if (done || pendingError || !event || typeof event !== "object" || !("data" in event)) return;
		try {
			if (waitingFrames) {
				waitingFrames.enqueue(event.data);
				return;
			}
			const decoded = decode(event.data);
			if (decoded) {
				waitingFrames = new FifoQueue();
				void drain(decoded);
			}
		} catch (cause) {
			invalid(cause, null);
		}
	};

	const endSocket = (error: Error) => {
		if (done) return;
		if (waitingFrames) {
			pendingError ??= error;
			return;
		}
		failed = error;
		done = true;
		wake();
	};
	const onError: WebSocketListener = (event) => endSocket(extractWebSocketError(event));
	const onClose: WebSocketListener = (event) => endSocket(extractWebSocketCloseError(event));

	const onAbort = () => {
		failed = new Error("Request was aborted");
		done = true;
		wake();
	};

	socket.addEventListener("message", onMessage);
	socket.addEventListener("error", onError);
	socket.addEventListener("close", onClose);
	signal?.addEventListener("abort", onAbort);

	try {
		while (true) {
			if (signal?.aborted) {
				throw new Error("Request was aborted");
			}
			if (queue.length > 0) {
				yield queue.dequeue()!;
				continue;
			}
			if (done) break;
			let timeout: ReturnType<typeof setTimeout> | undefined;
			await new Promise<void>((resolve, reject) => {
				pending = resolve;
				if (idleTimeoutMs !== undefined && idleTimeoutMs > 0) {
					timeout = setTimeout(() => {
						const error = new Error(`WebSocket idle timeout after ${idleTimeoutMs}ms`);
						failed = error;
						done = true;
						pending = null;
						closeWebSocketSilently(socket, 1000, "idle_timeout");
						reject(error);
					}, idleTimeoutMs);
				}
			}).finally(() => {
				if (timeout) {
					clearTimeout(timeout);
				}
			});
		}

		if (failed) {
			throw failed;
		}
		if (!sawCompletion) {
			throw new Error("WebSocket stream closed before response.completed");
		}
	} finally {
		done = true;
		waitingFrames = undefined;
		socket.removeEventListener("message", onMessage);
		socket.removeEventListener("error", onError);
		socket.removeEventListener("close", onClose);
		signal?.removeEventListener("abort", onAbort);
	}
}

const { isRawJSON } = JSON as unknown as { isRawJSON?: (value: unknown) => boolean };

function hasToJSON(value: unknown): boolean {
	return typeof value === "object" && value !== null && typeof (value as { toJSON?: unknown }).toJSON === "function";
}

/** Deeper than this, sameJsonData leaves the answer to JSON.stringify (which also reports a cycle as before). */
const SAME_JSON_MAX_DEPTH = 64;

/**
 * True when `a` and `b` are the same plain data, which JSON.stringify serializes to the same text: the same value, or
 * plain objects (keys in the same order) and arrays whose values are the same plain data. False for anything else
 * (a toJSON, a class instance, raw JSON, differing data), also when the two would still serialize alike.
 */
function sameJsonData(a: unknown, b: unknown, depth: number): boolean {
	// JSON.stringify throws for a bigint: never called equal here.
	if (a === b) return typeof a !== "bigint";
	if (typeof a !== "object" || typeof b !== "object" || a === null || b === null || depth > SAME_JSON_MAX_DEPTH) {
		return false;
	}
	if (hasToJSON(a) || hasToJSON(b)) return false;
	if (Array.isArray(a)) {
		if (!Array.isArray(b) || a.length !== b.length) return false;
		for (let i = 0; i < a.length; i++) {
			if (!sameJsonData(a[i], b[i], depth + 1)) return false;
		}
		return true;
	}
	if (Array.isArray(b) || !isPlainJsonRecord(a) || !isPlainJsonRecord(b)) return false;
	const keysA = Object.keys(a);
	const keysB = Object.keys(b);
	if (keysA.length !== keysB.length) return false;
	for (let i = 0; i < keysA.length; i++) {
		const key = keysA[i];
		if (key !== keysB[i]) return false;
		if (!sameJsonData((a as Record<string, unknown>)[key], (b as Record<string, unknown>)[key], depth + 1)) {
			return false;
		}
	}
	return true;
}

function isPlainJsonRecord(value: object): boolean {
	const proto = Object.getPrototypeOf(value);
	return proto === Object.prototype || (proto === null && !isRawJSON?.(value));
}

function hasToolSchema(item: unknown): boolean {
	return typeof item === "object" && item !== null && "tools" in item;
}

/** The spelling matters for raw JSON numbers. Parsing them can lose precision. */
class SentJson {
	readonly text: string;
	constructor(text: string) {
		this.text = text;
	}
}

/** Serialize an input element at its actual array index. A custom toJSON may use that index. */
function encodeInputItem(value: unknown, index: number): string {
	if (value !== null && (typeof value === "object" || typeof value === "function" || typeof value === "bigint")) {
		const boxed = (typeof value === "bigint" ? Object(value) : value) as { toJSON?: (key: string) => unknown };
		if ("toJSON" in boxed) {
			const serializer = boxed.toJSON;
			return (
				JSON.stringify({
					toJSON: () =>
						typeof serializer === "function" ? Reflect.apply(serializer, value, [String(index)]) : value,
				}) ?? "null"
			);
		}
	}
	return JSON.stringify(value) ?? "null";
}

interface WebSocketFrame {
	wire: string;
	inputCount: number;
	previousId?: string;
	next?: Omit<CachedWebSocketContinuationState, "lastResponseId">;
}

function prepareWebSocketFrame(
	body: RequestBody,
	entry: CachedWebSocketConnection | undefined,
	hooked: boolean,
): WebSocketFrame {
	const frame = { type: "response.create", ...body };
	const current = frame.input;
	if (
		!entry ||
		!Array.isArray(current) ||
		Object.hasOwn(frame, "previous_response_id") ||
		frame.type !== "response.create" ||
		hasToJSON(frame) ||
		hasToJSON(current) ||
		Object.getPrototypeOf(current) !== Array.prototype
	) {
		return { wire: JSON.stringify(frame), inputCount: current?.length ?? 0, previousId: frame.previous_response_id };
	}

	// Keep input in its original position. Settings after it can have serializers that the input affects.
	const before: Record<string, unknown> = Object.create(null);
	const after: Record<string, unknown> = Object.create(null);
	let settings = before;
	for (const [key, value] of Object.entries(frame)) {
		if (key === "input") settings = after;
		else settings[key] = value;
	}
	const start = JSON.stringify(before).slice(0, -1);
	const head = start + (start === "{" ? "" : ",") + '"input":';
	const prior = entry.continuation;
	const count = current.length;
	let matched = prior && prior.head === head && prior.input.length <= count ? prior : undefined;
	const priorCount = matched?.input.length ?? 0;
	const captured = matched?.input ?? [];
	for (let i = 0; i < count; i++) {
		const item = current[i]!;
		const previous = matched?.input[i];
		if (!hooked && matched && i < priorCount && previous === item) continue;
		if (hooked || hasToolSchema(item)) {
			const text = encodeInputItem(item, i);
			if (
				matched &&
				i < priorCount &&
				(previous instanceof SentJson ? previous.text : encodeInputItem(previous, i)) !== text
			)
				matched = undefined;
			captured[i] = previous instanceof SentJson && previous.text === text ? previous : new SentJson(text);
		} else {
			if (matched && i < priorCount && item !== previous) {
				if (
					previous instanceof SentJson
						? encodeInputItem(item, i) !== previous.text
						: !sameJsonData(item, previous, 0) && encodeInputItem(item, i) !== encodeInputItem(previous, i)
				)
					matched = undefined;
			}
			captured[i] = item;
		}
	}
	const end = JSON.stringify(after).slice(1);
	const tail = end === "}" ? end : "," + end;
	if (matched?.tail !== tail) matched = undefined;
	const from = matched ? priorCount : 0;
	const suffix = from ? captured.slice(from) : captured;
	const encoded = suffix.some((item) => item instanceof SentJson)
		? "[" +
			suffix.map((item, i) => (item instanceof SentJson ? item.text : encodeInputItem(item, from + i))).join(",") +
			"]"
		: JSON.stringify(suffix);
	const previousId = matched?.lastResponseId;
	const wire =
		head +
		encoded +
		(previousId ? tail.slice(0, -1) + `,"previous_response_id":${JSON.stringify(previousId)}}` : tail);
	return { wire, inputCount: count - from, previousId, next: { head, tail, input: captured } };
}

async function* startWebSocketOutputOnFirstEvent(
	events: AsyncIterable<ResponseStreamEvent>,
	onStart: () => void,
): AsyncGenerator<ResponseStreamEvent> {
	let started = false;
	for await (const event of events) {
		if (!started) {
			started = true;
			onStart();
		}
		yield event;
	}
}

async function processWebSocketStream(
	url: string,
	body: RequestBody,
	headers: Headers,
	output: AssistantMessage,
	stream: AssistantMessageEventStream,
	model: Model<"openai-codex-responses">,
	onStart: () => void,
	idleTimeoutMs: number | undefined,
	websocketConnectTimeoutMs: number | undefined,
	hooked: boolean,
	cacheSessionId: string | undefined,
	accountId: string,
	grammarToolInputProperties: ReadonlyMap<string, string>,
	options?: OpenAICodexResponsesOptions,
): Promise<void> {
	const { socket, entry, reused, release } = await acquireWebSocket(
		url,
		headers,
		cacheSessionId,
		accountId,
		options?.signal,
		websocketConnectTimeoutMs,
		options?.env,
	);
	let keepConnection = true;
	const useCachedContext = options?.transport === "websocket-cached" || options?.transport === "auto";
	// ChatGPT Codex Responses rejects `store: true` ("Store must be set to false").
	// WebSocket continuation still works via connection-scoped previous_response_id state.
	try {
		const prepared = prepareWebSocketFrame(body, useCachedContext ? entry : undefined, hooked);

		const stats = cacheSessionId ? getOrCreateWebSocketDebugStats(cacheSessionId) : undefined;
		if (stats) {
			stats.requests++;
			if (reused) stats.connectionsReused++;
			else stats.connectionsCreated++;
			if (useCachedContext) stats.cachedContextRequests++;
			if (body.store === true) stats.storeTrueRequests++;
			stats.lastInputItems = prepared.inputCount;
			if (prepared.previousId) {
				stats.deltaRequests++;
				stats.lastDeltaInputItems = prepared.inputCount;
				stats.lastPreviousResponseId = prepared.previousId;
			} else {
				stats.fullContextRequests++;
				stats.lastDeltaInputItems = undefined;
				stats.lastPreviousResponseId = undefined;
			}
		}
		socket.send(prepared.wire);
		const accepted = prepared.next;
		await processResponsesStream(
			startWebSocketOutputOnFirstEvent(
				mapCodexEvents(
					parseWebSocket(socket, options?.signal, idleTimeoutMs),
					output,
					model,
					options?.onProviderStreamEvent,
				),
				onStart,
			),
			output,
			stream,
			model,
			{
				serviceTier: options?.serviceTier,
				grammarToolInputProperties,
				resolveServiceTier: resolveCodexServiceTier,
				applyServiceTierPricing: (usage, serviceTier) => applyServiceTierPricing(usage, serviceTier, model),
			},
		);
		if (options?.signal?.aborted) {
			keepConnection = false;
		} else if (accepted && entry && output.responseId) {
			const responseItems = convertResponsesMessages(
				model,
				normalizeContext({ messages: [output] }),
				CODEX_TOOL_CALL_PROVIDERS,
				{
					includeSystemPrompt: false,
					grammarToolInputProperties,
				},
			).filter((item) => item.type !== "function_call_output" && item.type !== "custom_tool_call_output");
			for (const item of responseItems) accepted.input.push(item);
			entry.continuation = { ...accepted, lastResponseId: output.responseId };
		} else if (entry) {
			entry.continuation = undefined;
		}
	} catch (error) {
		if (entry) {
			entry.continuation = undefined;
		}
		keepConnection = false;
		throw error;
	} finally {
		release({ keep: keepConnection });
	}
}

// ============================================================================
// Error Handling
// ============================================================================

async function parseErrorResponse(response: Response): Promise<{ message: string; friendlyMessage?: string }> {
	const raw = await response.text();
	let message = raw || response.statusText || "Request failed";
	let friendlyMessage: string | undefined;

	try {
		const parsed = JSON.parse(raw) as {
			error?: { code?: string; type?: string; message?: string; plan_type?: string; resets_at?: number };
		};
		const err = parsed?.error;
		if (err) {
			const code = err.code || err.type || "";
			if (/usage_limit_reached|usage_not_included|rate_limit_exceeded/i.test(code) || response.status === 429) {
				const plan = err.plan_type ? ` (${err.plan_type.toLowerCase()} plan)` : "";
				const mins = err.resets_at
					? Math.max(0, Math.round((err.resets_at * 1000 - Date.now()) / 60000))
					: undefined;
				const when = mins !== undefined ? ` Try again in ~${mins} min.` : "";
				friendlyMessage = `You have hit your ChatGPT usage limit${plan}.${when}`.trim();
			}
			message = err.message || friendlyMessage || message;
		}
	} catch {}

	return { message, friendlyMessage };
}

// ============================================================================
// Auth & Headers
// ============================================================================

function extractAccountId(token: string): string {
	try {
		const parts = token.split(".");
		if (parts.length !== 3) throw new Error("Invalid token");
		const payload = JSON.parse(atob(parts[1]));
		const accountId = payload?.[JWT_CLAIM_PATH]?.chatgpt_account_id;
		if (!accountId) throw new Error("No account ID in token");
		return accountId;
	} catch {
		throw new Error("Failed to extract accountId from token");
	}
}

function buildBaseCodexHeaders(
	defaultHeaders: Record<string, string>,
	initHeaders: Record<string, string> | undefined,
	additionalHeaders: ProviderHeaders | undefined,
	accountId: string,
	token: string,
): Headers {
	// Defaults first so model and caller headers can override them, matching the other providers.
	const headers = new Headers(defaultHeaders);
	for (const [key, value] of Object.entries(initHeaders || {})) {
		headers.set(key, value);
	}
	for (const [key, value] of Object.entries(additionalHeaders || {})) {
		if (value === null) {
			headers.delete(key);
		} else {
			headers.set(key, value);
		}
	}
	headers.set("Authorization", `Bearer ${token}`);
	headers.set("chatgpt-account-id", accountId);
	return headers;
}

function buildSSEHeaders(
	defaultHeaders: Record<string, string>,
	initHeaders: Record<string, string> | undefined,
	additionalHeaders: ProviderHeaders | undefined,
	accountId: string,
	token: string,
	sessionId?: string,
): Headers {
	const headers = buildBaseCodexHeaders(defaultHeaders, initHeaders, additionalHeaders, accountId, token);
	headers.set("OpenAI-Beta", "responses=experimental");
	headers.set("accept", "text/event-stream");
	headers.set("content-type", "application/json");

	if (sessionId) {
		headers.set("session-id", sessionId);
		headers.set("x-client-request-id", sessionId);
	}

	return headers;
}

function buildWebSocketHeaders(
	defaultHeaders: Record<string, string>,
	initHeaders: Record<string, string> | undefined,
	additionalHeaders: ProviderHeaders | undefined,
	accountId: string,
	token: string,
	requestId: string,
): Headers {
	const headers = buildBaseCodexHeaders(defaultHeaders, initHeaders, additionalHeaders, accountId, token);
	headers.delete("accept");
	headers.delete("content-type");
	headers.delete("OpenAI-Beta");
	headers.delete("openai-beta");
	headers.set("OpenAI-Beta", OPENAI_BETA_RESPONSES_WEBSOCKETS);
	headers.set("x-client-request-id", requestId);
	headers.set("session-id", requestId);
	return headers;
}
