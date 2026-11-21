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
	toolCalls: ToolCall[] | undefined;
}

function transformAssistant<TApi extends Api>(
	assistantMsg: AssistantMessage,
	model: Model<TApi>,
	normalizeToolCallId: ((id: string, model: Model<TApi>, source: AssistantMessage) => string) | undefined,
): TransformedAssistant {
	let renamedIds: [string, string][] | undefined;
	let toolCalls: ToolCall[] | undefined;
	const isSameModel =
		assistantMsg.provider === model.provider && assistantMsg.api === model.api && assistantMsg.model === model.id;

	const transformedContent: AssistantMessage["content"] = [];
	assistantMsg.content.forEach((block) => {
		if (block.type === "thinking") {
			// Thinking from another model is dropped. Signatures and encrypted reasoning are
			// only valid for the model that produced them, and sending readable thinking as
			// plain text makes the new model treat it as part of the visible reply.
			if (!isSameModel) return;
			// Keep redacted and signed blocks even if the thinking text is empty
			// (OpenAI encrypted reasoning); they are needed for replay.
			if (!block.redacted && !block.thinkingSignature && (!block.thinking || block.thinking.trim() === "")) return;
			transformedContent.push(block);
		} else if (block.type === "text") {
			transformedContent.push(isSameModel ? block : { type: "text", text: block.text });
		} else if (block.type === "toolCall") {
			const toolCall = block;
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

			transformedContent.push(normalizedToolCall);
			toolCalls ??= [];
			toolCalls.push(normalizedToolCall);
		} else {
			transformedContent.push(block);
		}
	});

	const message: AssistantMessage = {
		...assistantMsg,
		content: transformedContent,
	};
	return {
		message,
		renamedIds,
		toolCalls,
	};
}

function containsOnlyText(blocks: AssistantMessage["content"]): boolean {
	for (const block of blocks) {
		// forEach in transformAssistant skips holes in imported message content.
		if (block?.type !== "text") return false;
	}
	return true;
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
	/** A read-only converter that handles cross-model text signatures itself can read text-only replies directly. */
	options?: { preserveTextOnlyReplies?: boolean },
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

		if (msg.role === "assistant") {
			if (options?.preserveTextOnlyReplies && containsOnlyText(msg.content)) return msg;
			const result = cache
				? cache.derive(source, "", () => transformAssistant(msg as AssistantMessage, model, normalizeToolCallId))
				: transformAssistant(msg as AssistantMessage, model, normalizeToolCallId);
			if (result.renamedIds) for (const [id, normalizedId] of result.renamedIds) toolCallIdMap.set(id, normalizedId);
			if (result.toolCalls) toolCallsAt[index] = result.toolCalls;
			return result.message;
		}
		return msg;
	});

	// Most histories already have replay order. Allocate a second array only when a turn needs
	// repair. Keep the original source alongside each copied message for the provider cache.
	let repaired: Message[] | undefined;
	let unchangedFrom = 0;
	const copyThrough = (end: number) => {
		if (repaired) for (let i = unchangedFrom; i < end; i++) repaired.push(transformed[i]);
		if (sources) for (let i = unchangedFrom; i < end; i++) sources.push(messages[i]);
		unchangedFrom = end;
	};

	for (let i = 0; i < transformed.length; i++) {
		const msg = transformed[i];
		if (msg.role !== "assistant") continue;
		if (msg.stopReason === "error" || msg.stopReason === "aborted") {
			repaired ??= [];
			copyThrough(i);
			unchangedFrom = i + 1;
			continue;
		}
		const calls = toolCallsAt[i];
		if (!calls) continue;
		let end = i + 1;
		for (const call of calls) {
			const reply = transformed[end];
			if (reply?.role !== "toolResult" || reply.toolCallId !== call.id) break;
			end++;
		}
		const next = transformed[end];
		if (end === i + 1 + calls.length && (!next || next.role === "assistant" || next.role === "user")) {
			i = end - 1;
			continue;
		}

		// The next user or assistant closes this group. Preserve result order, synthesize
		// missing results, then emit any system messages that arrived during the group.
		repaired ??= [];
		copyThrough(i + 1);
		const answered = new Set<string>();
		end = i + 1;
		for (; end < transformed.length; end++) {
			const reply = transformed[end];
			if (reply.role === "assistant" || reply.role === "user") break;
			if (reply.role === "system") continue;
			if (reply.role === "toolResult") answered.add(reply.toolCallId);
			repaired.push(reply);
			sources?.push(messages[end]);
		}
		for (const call of calls) {
			if (answered.has(call.id)) continue;
			repaired.push({
				role: "toolResult",
				toolCallId: call.id,
				toolName: call.name,
				content: [{ type: "text", text: "No result provided" }],
				isError: true,
				timestamp: Date.now(),
			});
			sources?.push(undefined);
		}
		for (let j = i + 1; j < end; j++) {
			if (transformed[j].role === "system") {
				repaired.push(transformed[j]);
				sources?.push(messages[j]);
			}
		}
		unchangedFrom = end;
		i = end - 1;
	}
	copyThrough(transformed.length);
	return repaired ?? transformed;
}
