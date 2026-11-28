// Reasoning a provider reported as removed for good, read back from a transcript's assistant diagnostics, so it is not
// replayed again (#346).

import type { Api, AssistantMessage, Message, Model, ThinkingContent } from "../types.ts";

/**
 * How one provider reports removed reasoning in its assistant diagnostics.
 *
 * Problem: when the head of a request changes (the instructions or the tool declarations), the provider removes every
 * replayed reasoning block minted before the change and reports it. A block replayed again is removed and reported
 * again, on every later request: the request pays for input the model never sees, and the report repeats each turn.
 * The diagnostics are part of the assistant messages, which the session file keeps, so the removals are known again
 * after a reload; the converters read them here and leave those blocks out.
 *
 * Only removals that hold for every later request to the same model are used:
 * - `prefix`: the conversation before the block differs from the one it was created in. The block would bind again
 *   only if that earlier input came back exactly (an upgrade that is undone); it is left out from the report on.
 * - `model`: the block came from a model whose reasoning the requested model may not read. It is left out only for
 *   requests to the model that reported it; another model may still read it.
 * Removals for another organization or end user are not used: they depend on the credentials, which can change back.
 */
export interface ReasoningDropFormat {
	/** Diagnostic type that lists the removals. */
	readonly diagnostic: string;
	/** Transformation `type` of a removed block. */
	readonly droppedType: string;
	/** Transformation field that names the removed block, as `blockKey` names a replayed one. */
	readonly keyField: string;
	readonly prefixReason: string;
	readonly modelReason: string;
	/** The name of a replayed thinking block, compared with `keyField`; undefined when it has none. */
	blockKey(block: ThinkingContent): string | undefined;
}

/**
 * The names of the reasoning blocks the provider removed for good for requests to `model`, as recorded in the
 * assistant diagnostics of `messages`; undefined when there are none.
 */
export function collectReasoningDrops(
	messages: readonly Message[],
	format: ReasoningDropFormat,
	model: Model<Api>,
): ReadonlySet<string> | undefined {
	let drops: Set<string> | undefined;
	for (const message of messages) {
		if (message.role !== "assistant" || message.diagnostics === undefined) continue;
		for (const diagnostic of message.diagnostics) {
			if (diagnostic.type !== format.diagnostic) continue;
			// Session data: diagnostic details are untyped JSON.
			const transformations = diagnostic.details?.transformations;
			if (!Array.isArray(transformations)) continue;
			for (const transformation of transformations) {
				if (typeof transformation !== "object" || transformation === null || Array.isArray(transformation))
					continue;
				if (transformation.type !== format.droppedType) continue;
				const key = transformation[format.keyField];
				if (typeof key !== "string") continue;
				if (
					transformation.reason === format.modelReason
						? !reportedBy(message, model)
						: transformation.reason !== format.prefixReason
				)
					continue;
				drops ??= new Set();
				drops.add(key);
			}
		}
	}
	return drops;
}

function reportedBy(message: AssistantMessage, model: Model<Api>): boolean {
	return message.provider === model.provider && message.api === model.api && message.model === model.id;
}

/**
 * The names of `message`'s thinking blocks that are in `drops`, as one value (a unit's extra in request-cache.ts: it
 * changes what the message converts to); undefined when none of them is.
 */
export function droppedBlocksOf(
	message: AssistantMessage,
	drops: ReadonlySet<string> | undefined,
	format: ReasoningDropFormat,
): string | undefined {
	if (drops === undefined) return undefined;
	let dropped: string | undefined;
	for (const key of blockKeysOf(message, format)) {
		if (key === undefined || !drops.has(key)) continue;
		dropped = dropped === undefined ? key : `${dropped}\u0000${key}`;
	}
	return dropped;
}

/** Whether `block` is one of `drops`. */
export function isDroppedBlock(
	block: ThinkingContent,
	drops: ReadonlySet<string> | undefined,
	format: ReasoningDropFormat,
): boolean {
	if (drops === undefined) return false;
	const key = format.blockKey(block);
	return key !== undefined && drops.has(key);
}

interface MessageBlockKeys {
	format: ReasoningDropFormat;
	keys: readonly (string | undefined)[];
}

// A block's name is read from its signature (a JSON parse or a hash of a few kilobytes) once per message: the messages
// of a session are the same objects from request to request, and every request looks at all of them.
const messageBlockKeys = new WeakMap<AssistantMessage, MessageBlockKeys>();

/** The names of the thinking blocks of `message`, in order. */
function blockKeysOf(message: AssistantMessage, format: ReasoningDropFormat): readonly (string | undefined)[] {
	const cached = messageBlockKeys.get(message);
	if (cached?.format === format) return cached.keys;
	const keys: (string | undefined)[] = [];
	for (const block of message.content) if (block.type === "thinking") keys.push(format.blockKey(block));
	messageBlockKeys.set(message, { format, keys });
	return keys;
}
