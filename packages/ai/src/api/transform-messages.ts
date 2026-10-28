import type {
	Api,
	AssistantMessage,
	ImageContent,
	Message,
	Model,
	TextContent,
	ToolCall,
	ToolResultMessage,
} from "../types.ts";
import type { RequestCacheRun } from "../utils/request-cache.ts";

const NON_VISION_USER_IMAGE_PLACEHOLDER = "(image omitted: model does not support images)";
const NON_VISION_TOOL_IMAGE_PLACEHOLDER = "(tool image omitted: model does not support images)";
const ERROR_TOOL_IMAGE_PLACEHOLDER = "(image attached after the tool results)";

/** An error tool result split into a text-only result and the images to send after the tool results. */
export interface SplitErrorToolResult {
	/** The result content with each run of images replaced by a note. */
	content: TextContent[];
	/** Text naming the tool call, to send before `images`. */
	caption: string;
	images: ImageContent[];
}

/**
 * Split the images out of an error tool result, for APIs that reject images in error results
 * (Anthropic, and Claude on Bedrock). Send the returned content as the tool result and the caption and
 * images after all tool results of the turn, in the same user message. Returns undefined for results
 * that need no split (#10794).
 */
export function splitErrorToolResultImages(msg: ToolResultMessage): SplitErrorToolResult | undefined {
	if (!msg.isError) return undefined;
	const images = msg.content.filter((block): block is ImageContent => block.type === "image");
	if (images.length === 0) return undefined;
	return {
		content: replaceImagesWithPlaceholder(msg.content, ERROR_TOOL_IMAGE_PLACEHOLDER),
		caption: `Images from the error result of tool call ${msg.toolCallId} (${msg.toolName}):`,
		images,
	};
}

function replaceImagesWithPlaceholder(content: (TextContent | ImageContent)[], placeholder: string): TextContent[] {
	const result: TextContent[] = [];
	let previousWasPlaceholder = false;

	for (const block of content) {
		if (block.type === "image") {
			if (!previousWasPlaceholder) {
				result.push({ type: "text", text: placeholder });
			}
			previousWasPlaceholder = true;
			continue;
		}

		result.push(block);
		previousWasPlaceholder = block.text === placeholder;
	}

	return result;
}

/** The tool call ids of a run of tool results, as one value to compare (a unit's extra in request-cache.ts). */
export function toolCallIds(results: readonly ToolResultMessage[]): string {
	return results.length === 1 ? results[0].toolCallId : results.map((result) => result.toolCallId).join("\u0000");
}

/** What transformMessages makes of an assistant message: the copy, the tool call ids it renamed, its tool calls. */
interface TransformedAssistant {
	message: AssistantMessage;
	renamedIds: [string, string][] | undefined;
	toolCalls: ToolCall[];
}

function transformAssistant<TApi extends Api>(
	assistantMsg: AssistantMessage,
	model: Model<TApi>,
	normalizeToolCallId: ((id: string, model: Model<TApi>, source: AssistantMessage) => string) | undefined,
): TransformedAssistant {
	let renamedIds: [string, string][] | undefined;
	const isSameModel =
		assistantMsg.provider === model.provider && assistantMsg.api === model.api && assistantMsg.model === model.id;

	const transformedContent = assistantMsg.content.flatMap((block) => {
		if (block.type === "thinking") {
			// Thinking from another model is dropped. Signatures and encrypted reasoning are
			// only valid for the model that produced them, and sending readable thinking as
			// plain text makes the new model treat it as part of the visible reply.
			if (!isSameModel) return [];
			// Keep redacted and signed blocks even if the thinking text is empty
			// (OpenAI encrypted reasoning); they are needed for replay.
			if (block.redacted || block.thinkingSignature) return block;
			if (!block.thinking || block.thinking.trim() === "") return [];
			return block;
		}

		if (block.type === "text") {
			if (isSameModel) return block;
			return {
				type: "text" as const,
				text: block.text,
			};
		}

		if (block.type === "toolCall") {
			const toolCall = block as ToolCall;
			let normalizedToolCall: ToolCall = toolCall;

			if (!isSameModel && toolCall.thoughtSignature) {
				normalizedToolCall = { ...toolCall };
				delete (normalizedToolCall as { thoughtSignature?: string }).thoughtSignature;
			}

			if (!isSameModel && normalizeToolCallId) {
				const normalizedId = normalizeToolCallId(toolCall.id, model, assistantMsg);
				if (normalizedId !== toolCall.id) {
					renamedIds ??= [];
					renamedIds.push([toolCall.id, normalizedId]);
					normalizedToolCall = { ...normalizedToolCall, id: normalizedId };
				}
			}

			return normalizedToolCall;
		}

		return block;
	});

	const message: AssistantMessage = {
		...assistantMsg,
		content: transformedContent,
	};
	return {
		message,
		renamedIds,
		toolCalls: transformedContent.filter((b) => b.type === "toolCall") as ToolCall[],
	};
}

/**
 * Normalize tool call ID for cross-provider compatibility.
 * OpenAI Responses API generates IDs that are 450+ chars with special characters like `|`.
 * Anthropic APIs require IDs matching ^[a-zA-Z0-9_-]+$ (max 64 chars).
 *
 * With `cache` (a request's run of request-cache.ts, whose context holds the model and everything the normalizer
 * reads), the copies made of a message are reused while the message is unchanged. `sources` is filled with the
 * message of `messages` each returned message was made from, at the same index; undefined for a synthetic tool result.
 */
export function transformMessages<TApi extends Api>(
	messages: Message[],
	model: Model<TApi>,
	normalizeToolCallId?: (id: string, model: Model<TApi>, source: AssistantMessage) => string,
	cache?: RequestCacheRun,
	sources?: (Message | undefined)[],
): Message[] {
	// Build a map of original tool call IDs to normalized IDs
	const toolCallIdMap = new Map<string, string>();
	const vision = model.input.includes("image");
	/** The tool calls of each transformed assistant message, by index. */
	const toolCallsAt: ToolCall[][] = [];

	// First pass: transform messages (unsupported image downgrade, thinking blocks, tool call ID normalization)
	const transformed = messages.map((source, index): Message => {
		// Normalize null/undefined content from untyped callers (custom tools, hand-built
		// histories, old session files) so downstream code can rely on the type contract.
		const msg = source.content == null ? ({ ...source, content: [] } as Message) : source;

		// System messages pass through unchanged
		if (msg.role === "system") return msg;

		if (msg.role === "user") {
			if (vision || !Array.isArray(msg.content)) return msg;
			const content = msg.content as (TextContent | ImageContent)[];
			const make = () => ({
				...msg,
				content: replaceImagesWithPlaceholder(content, NON_VISION_USER_IMAGE_PLACEHOLDER),
			});
			return cache ? cache.derive(source, "", make) : make();
		}

		// Handle toolResult messages - normalize toolCallId if we have a mapping
		if (msg.role === "toolResult") {
			const normalizedId = toolCallIdMap.get(msg.toolCallId);
			const renamed = !!normalizedId && normalizedId !== msg.toolCallId;
			if (vision && !renamed) return msg;
			const make = () => {
				const imageAware = vision
					? msg
					: { ...msg, content: replaceImagesWithPlaceholder(msg.content, NON_VISION_TOOL_IMAGE_PLACEHOLDER) };
				return renamed ? { ...imageAware, toolCallId: normalizedId as string } : imageAware;
			};
			return cache ? cache.derive(source, renamed ? normalizedId : "", make) : make();
		}

		// Assistant messages need transformation check
		if (msg.role === "assistant") {
			const result = cache
				? cache.derive(source, "", () => transformAssistant(msg as AssistantMessage, model, normalizeToolCallId))
				: transformAssistant(msg as AssistantMessage, model, normalizeToolCallId);
			if (result.renamedIds) for (const [id, normalizedId] of result.renamedIds) toolCallIdMap.set(id, normalizedId);
			toolCallsAt[index] = result.toolCalls;
			return result.message;
		}
		return msg;
	});

	// Second pass: insert synthetic empty tool results for orphaned tool calls
	// This preserves thinking signatures and satisfies API requirements
	const result: Message[] = [];
	const push = (msg: Message, source: Message | undefined): void => {
		result.push(msg);
		sources?.push(source);
	};
	let pendingToolCalls: ToolCall[] = [];
	let existingToolResultIds = new Set<string>();
	// System messages are transparent to tool-call accounting: one that lands between a tool
	// call and its results is held back and emitted after the results (synthetic ones
	// included), so it never causes a duplicate result for a call that is answered later.
	const heldSystemMessages: Message[] = [];
	const heldSources: Message[] = [];
	const closePendingToolCalls = () => {
		if (pendingToolCalls.length > 0) {
			for (const tc of pendingToolCalls) {
				if (!existingToolResultIds.has(tc.id)) {
					push(
						{
							role: "toolResult",
							toolCallId: tc.id,
							toolName: tc.name,
							content: [{ type: "text", text: "No result provided" }],
							isError: true,
							timestamp: Date.now(),
						} as ToolResultMessage,
						undefined,
					);
				}
			}
			pendingToolCalls = [];
			existingToolResultIds = new Set();
		}
		for (let i = 0; i < heldSystemMessages.length; i++) push(heldSystemMessages[i], heldSources[i]);
		heldSystemMessages.length = 0;
		heldSources.length = 0;
	};

	for (let i = 0; i < transformed.length; i++) {
		const msg = transformed[i];
		const source = messages[i];

		if (msg.role === "assistant") {
			// If we have pending orphaned tool calls from a previous assistant, insert synthetic results now
			closePendingToolCalls();

			// Skip errored/aborted assistant messages entirely.
			// These are incomplete turns that shouldn't be replayed:
			// - May have partial content (reasoning without message, incomplete tool calls)
			// - Replaying them can cause API errors (e.g., OpenAI "reasoning without following item")
			// - The model should retry from the last valid state
			const assistantMsg = msg as AssistantMessage;
			if (assistantMsg.stopReason === "error" || assistantMsg.stopReason === "aborted") {
				continue;
			}

			// Track tool calls from this assistant message
			const toolCalls = toolCallsAt[i] ?? [];
			if (toolCalls.length > 0) {
				pendingToolCalls = toolCalls;
				existingToolResultIds = new Set();
			}

			push(msg, source);
		} else if (msg.role === "toolResult") {
			existingToolResultIds.add(msg.toolCallId);
			push(msg, source);
		} else if (msg.role === "system") {
			if (pendingToolCalls.length > 0) {
				heldSystemMessages.push(msg);
				heldSources.push(source);
			} else {
				push(msg, source);
			}
		} else if (msg.role === "user") {
			// A new user turn interrupts tool flow - insert synthetic results for orphaned calls
			closePendingToolCalls();
			push(msg, source);
		} else {
			push(msg, source);
		}
	}

	// If the conversation ends with unresolved tool calls, synthesize results now.
	closePendingToolCalls();

	return result;
}
