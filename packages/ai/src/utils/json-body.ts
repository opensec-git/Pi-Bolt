/**
 * Serializing a conversation for a request reuses the JSON of the long strings serialized for the requests before it.
 *
 * Every request resends the whole conversation, and JSON.stringify escapes every string in it again: in a long
 * session that grows to the largest share of the CPU a turn takes. The strings themselves do not change: a tool
 * result or an answer is serialized again for every request after the one it first appears in.
 *
 * cacheJsonSerialization(messages) gives the messages array a toJSON that returns a copy of it with each long string
 * replaced by a JSON.rawJSON() value holding the string's serialized form, kept from the previous request.
 * JSON.stringify copies those as they are. The output is byte-identical to JSON.stringify of the original array.
 * Without JSON.rawJSON and JSON.isRawJSON (older engines) nothing changes, and nothing it does can make a request fail:
 * on any error it serializes the array as JSON.stringify would.
 *
 * The array, not the request body, carries the toJSON: SDKs copy the body (`{ ...params }`, `const { betas, ...body } =
 * params`) before they serialize it, and those copies keep the array but not a property that is not enumerable.
 *
 * Strings are cached by value within their session, and closing a session releases its escaped text. Serializations
 * in the same task (one request body, read synchronously) make one
 * generation; a string that the latest generation did not use is kept for one more, then dropped. So the cache holds
 * about one request's long strings, and two conversations that take turns still reuse theirs.
 */
import { registerSessionResourceCleanup } from "../session-resources.ts";

const { rawJSON, isRawJSON } = JSON as unknown as {
	rawJSON?: (text: string) => unknown;
	isRawJSON?: (value: unknown) => boolean;
};

/** Shorter strings are cheap to escape again; caching them would cost more in entries than it saves. */
const MIN_CACHED_LENGTH = 1024;

type Generation = Map<string | undefined, Map<string, unknown>>;
const sessionOf = new WeakMap<unknown[], string>();
let current: Generation = new Map();
let previous: Generation = new Map();
let generationOpen = false;

registerSessionResourceCleanup((sessionId, retainedStrings) => {
	if (sessionId === undefined) {
		current.clear();
		previous.clear();
	} else {
		const latest = current.get(sessionId);
		const last = previous.get(sessionId);
		current.delete(sessionId);
		previous.delete(sessionId);
		if (retainedStrings && (latest || last)) {
			const kept = new Map<string, unknown>();
			for (const text of retainedStrings()) {
				if (text.length < MIN_CACHED_LENGTH) continue;
				const raw = latest?.get(text) ?? last?.get(text);
				if (raw !== undefined) kept.set(text, raw);
			}
			if (kept.size) current.set(sessionId, kept);
		}
	}
}, "request-cache");

function startGeneration(): void {
	if (generationOpen) return;
	generationOpen = true;
	previous = current;
	current = new Map();
	queueMicrotask(() => {
		generationOpen = false;
	});
}

function stringEncoder(sessionId: string | undefined, toRaw: (text: string) => unknown): (value: string) => unknown {
	let values = current.get(sessionId);
	const last = previous.get(sessionId);
	return (value) => {
		if (!values) {
			values = new Map();
			current.set(sessionId, values);
		}
		let raw = values.get(value);
		if (raw === undefined) {
			raw = last?.get(value) ?? toRaw(JSON.stringify(value));
			values.set(value, raw);
		}
		return raw;
	};
}

/**
 * A copy of `value` for JSON.stringify, with long strings replaced by their cached raw JSON. Only arrays and plain
 * objects are copied; anything JSON.stringify treats specially (toJSON, boxed primitives, class instances) is left as
 * it is, as is a cycle (JSON.stringify then throws for it as before).
 */
function substitute(value: unknown, encode: (text: string) => unknown, ancestors: Set<object>): unknown {
	if (typeof value === "string") {
		return value.length >= MIN_CACHED_LENGTH ? encode(value) : value;
	}
	// A raw JSON value already in the messages is a frozen null-prototype object: copied, it would become an ordinary one.
	if (value === null || typeof value !== "object" || ancestors.has(value) || isRawJSON?.(value)) {
		return value;
	}
	if (Array.isArray(value)) {
		return typeof (value as { toJSON?: unknown }).toJSON === "function"
			? value
			: substituteItems(value, encode, ancestors);
	}
	const proto = Object.getPrototypeOf(value);
	if ((proto !== Object.prototype && proto !== null) || typeof (value as { toJSON?: unknown }).toJSON === "function") {
		return value;
	}
	const record = value as Record<string, unknown>;
	const keys = Object.keys(record);
	// Assigning "__proto__" would set the copy's prototype instead of a property.
	if (keys.includes("__proto__")) return value;
	ancestors.add(value);
	const out: Record<string, unknown> = {};
	for (const key of keys) {
		out[key] = substitute(record[key], encode, ancestors);
	}
	ancestors.delete(value);
	return out;
}

function substituteItems(
	items: readonly unknown[],
	encode: (text: string) => unknown,
	ancestors: Set<object>,
): unknown[] {
	ancestors.add(items);
	const out = new Array<unknown>(items.length);
	for (let i = 0; i < items.length; i++) {
		out[i] = substitute(items[i], encode, ancestors);
	}
	ancestors.delete(items);
	return out;
}

/**
 * The toJSON cacheJsonSerialization gives an array: a copy of it with long strings replaced by their cached raw JSON,
 * which JSON.stringify serializes to the same text as the array.
 */
export function cachedArrayToJSON(this: unknown[]): unknown {
	try {
		if (!rawJSON) return this;
		startGeneration();
		return substituteItems(this, stringEncoder(sessionOf.get(this), rawJSON), new Set());
	} catch {
		// JSON.stringify serializes what toJSON returns without calling it again: the array as it is.
		return this;
	}
}

/**
 * Makes JSON.stringify of `items` (a request's messages) reuse the serialized form of long strings serialized before,
 * by defining a non-enumerable toJSON on the array. Does nothing to anything but an extensible plain array without a
 * toJSON (an onPayload hook may return a frozen one). Never throws.
 */
export function cacheJsonSerialization(items: unknown, sessionId?: string): void {
	if (!rawJSON || !isRawJSON || !Array.isArray(items) || Object.getPrototypeOf(items) !== Array.prototype) {
		return;
	}
	try {
		const descriptor = Object.getOwnPropertyDescriptor(items, "toJSON");
		if (descriptor) {
			if (descriptor.value !== cachedArrayToJSON) return;
		} else {
			if (!Object.isExtensible(items)) return;
			Object.defineProperty(items, "toJSON", {
				value: cachedArrayToJSON,
				configurable: true,
				writable: true,
				enumerable: false,
			});
		}
		if (sessionId === undefined) sessionOf.delete(items);
		else sessionOf.set(items, sessionId);
	} catch {
		// Not cached; serialized as before.
	}
}
