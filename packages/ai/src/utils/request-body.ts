/**
 * Sends a request body built from the bytes of the messages already sent in the conversation's previous request.
 *
 * Every request resends the whole conversation, and conversations grow at the end: request N+1 is request N's
 * messages plus a few new ones. Serializing the body walks and copies all of it, and fetch then copies the string again
 * to encode it as UTF-8: at 400 messages (9 MB) that is most of the CPU a model turn costs the client.
 *
 * sendJsonBodyAsBytes(client, items, key, conversation) makes the SDK client send the body that holds `items` (the
 * request's messages or input array) as a Blob of UTF-8 bytes. Each item is compared with a snapshot of the item at the
 * same index in the conversation's previous request; an equal item reuses that request's bytes, any other is
 * serialized again. Only the comparison walks the whole conversation, and it copies nothing. The parts are Blobs, and
 * the body is a Blob made of them: Pi-Bolt's runtime keeps such a Blob as its parts and fetch sends them one after the
 * other (other runtimes join them: one copy); fetch sends a Blob's bytes as they are (it would copy a string or a
 * Uint8Array once more).
 *
 * The bytes are the UTF-8 encoding of what JSON.stringify(body) returns, which is what the SDK would send:
 * - JSON.stringify of an array is "[" + the items' JSON joined with "," + "]", and an item's JSON depends only on the
 *   item. The rest of the body is serialized with a placeholder in place of the array.
 * - A snapshot keeps the item's structure (plain objects with their keys in order, arrays, primitives) and shares its
 *   strings. Items are compared with their snapshot, never with the previous request's objects, so a message changed in
 *   place (a cache marker added to the latest one, compaction, an extension) is not equal and is serialized again.
 * - Anything JSON.stringify treats specially (toJSON, raw JSON, boxed primitives, class instances) is never equal to
 *   a snapshot, so it is serialized every time. An item with a toJSON of its own, or a body or array that is not plain,
 *   sends the body as the SDK would.
 * - Values are read as plain data: a getter that returns something else on every read, or a Proxy, can make an item
 *   compare equal while it would now serialize differently.
 *
 * The client's own body encoding is wrapped (one wrapper per client): it uses the bytes only for a body whose array was
 * registered here and a client that sends with the runtime's own fetch (Bun's; Node's fetch is JavaScript, and any
 * other fetch may read the body as a string), keeps the headers the SDK sets for a JSON body, and on any error leaves
 * the body to the SDK.
 *
 * Conversations are told apart by a key (the session id); the last few keep their previous request's snapshots and
 * bytes, about one request's size each. A conversation not seen in the latest requests is dropped.
 *
 * An item that request-cache.ts handed out (a message converted for an earlier request and reused; nothing changes it)
 * is sent as the bytes it was first sent as, without a comparison.
 */

import { cachedArrayToJSON, cacheJsonSerialization } from "./json-body.ts";
import type { RequestCacheRun, SentValues } from "./request-cache.ts";

const { isRawJSON } = JSON as unknown as { isRawJSON?: (value: unknown) => boolean };

/** UTF-8 bytes: a Blob, which cannot change, so a Blob made of them can keep them as they are. */
type Bytes = Blob;

/** The structure of an object serialized before; strings are shared with it. */
class ObjectSnapshot {
	readonly keys: string[];
	readonly values: unknown[];
	constructor(keys: string[], values: unknown[]) {
		this.keys = keys;
		this.values = values;
	}
}

class ArraySnapshot {
	readonly items: unknown[];
	constructor(items: unknown[]) {
		this.items = items;
	}
}

/** A snapshot of something that is serialized again for every request. */
const UNCACHEABLE = Symbol("uncacheable");

function hasToJSON(value: object): boolean {
	return typeof (value as { toJSON?: unknown }).toJSON === "function";
}

function isPlainRecord(value: object): boolean {
	const proto = Object.getPrototypeOf(value);
	return proto === Object.prototype || (proto === null && !isRawJSON?.(value));
}

function snapshot(value: unknown): unknown {
	switch (typeof value) {
		case "string":
		case "number":
		case "boolean":
		case "undefined":
			return value;
		case "object": {
			if (value === null) return null;
			if (hasToJSON(value)) return UNCACHEABLE;
			if (Array.isArray(value)) {
				const items = new Array<unknown>(value.length);
				for (let i = 0; i < value.length; i++) {
					const item = snapshot(value[i]);
					if (item === UNCACHEABLE) return UNCACHEABLE;
					items[i] = item;
				}
				return new ArraySnapshot(items);
			}
			if (!isPlainRecord(value)) return UNCACHEABLE;
			const record = value as Record<string, unknown>;
			const keys = Object.keys(record);
			const values = new Array<unknown>(keys.length);
			for (let i = 0; i < keys.length; i++) {
				const item = snapshot(record[keys[i]]);
				if (item === UNCACHEABLE) return UNCACHEABLE;
				values[i] = item;
			}
			return new ObjectSnapshot(keys, values);
		}
		default:
			// Functions, symbols, bigints.
			return UNCACHEABLE;
	}
}

/** Whether `value` serializes as the value the snapshot was taken of did. */
function matches(snap: unknown, value: unknown): boolean {
	if (snap instanceof ObjectSnapshot) {
		if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
		if (!isPlainRecord(value) || hasToJSON(value)) return false;
		const { keys, values } = snap;
		const record = value as Record<string, unknown>;
		let i = 0;
		// for-in also lists inherited enumerable keys, which JSON.stringify skips: such an object is never equal.
		for (const key in record) {
			if (i >= keys.length || key !== keys[i] || !matches(values[i], record[key])) return false;
			i++;
		}
		return i === keys.length;
	}
	if (snap instanceof ArraySnapshot) {
		if (!Array.isArray(value) || hasToJSON(value)) return false;
		const { items } = snap;
		if (value.length !== items.length) return false;
		for (let i = 0; i < items.length; i++) {
			if (!matches(items[i], value[i])) return false;
		}
		return true;
	}
	return Object.is(snap, value);
}

interface Conversation {
	snapshots: unknown[];
	bytes: Bytes[];
	head: string;
	headBytes: Bytes;
	tail: string;
	tailBytes: Bytes;
	lastUse: number;
}

const MAX_CONVERSATIONS = 4;
/** A conversation that none of the latest requests used is dropped. */
const MAX_IDLE_REQUESTS = 32;

const conversations = new Map<string, Conversation>();
let requests = 0;
/** The UTF-8 bytes of `text`. */
const utf8 = (text: string): Bytes => new Blob([text]);
const EMPTY: Bytes = new Blob([]);

function conversationFor(key: string): Conversation {
	requests++;
	for (const [other, state] of conversations) {
		if (requests - state.lastUse > MAX_IDLE_REQUESTS) conversations.delete(other);
	}
	let state = conversations.get(key);
	if (state) {
		conversations.delete(key);
	} else {
		state = { snapshots: [], bytes: [], head: "", headBytes: EMPTY, tail: "", tailBytes: EMPTY, lastUse: 0 };
		if (conversations.size >= MAX_CONVERSATIONS) {
			const oldest = conversations.keys().next().value;
			if (oldest !== undefined) conversations.delete(oldest);
		}
	}
	state.lastUse = requests;
	conversations.set(key, state);
	return state;
}

/** Stands in for the array while the rest of the body is serialized; it cannot occur by chance. */
const PLACEHOLDER = `\u0000pi-request-body-${Math.random().toString(36).slice(2)}${Date.now().toString(36)}`;
const PLACEHOLDER_JSON = JSON.stringify(PLACEHOLDER);

/** Whether `items` is a plain array without a toJSON, or with json-body.ts's (which serializes to the same text). */
function isPlainItems(items: unknown[], allowedToJSON: unknown): boolean {
	if (Object.getPrototypeOf(items) !== Array.prototype) return false;
	const toJSON = (items as { toJSON?: unknown }).toJSON;
	return toJSON === undefined || toJSON === allowedToJSON;
}

const COMMA: Bytes = utf8(",");

/**
 * The UTF-8 bytes of JSON.stringify(body) in parts, in order, reusing the conversation's previous bytes of each item
 * of body[key] that is unchanged; undefined when the body is not plain data this can serialize exactly.
 */
export function jsonBodyParts(
	body: Record<string, unknown>,
	key: string,
	conversation: string,
	allowedToJSON?: unknown,
	sent?: SentValues,
): Bytes[] | undefined {
	const items = body[key];
	if (!Array.isArray(items) || !isPlainItems(items, allowedToJSON) || !isPlainRecord(body) || hasToJSON(body)) {
		return undefined;
	}
	// By index, as JSON.stringify reads an array: an iterator of the array's own is never run.
	for (let i = 0; i < items.length; i++) {
		const item: unknown = items[i];
		// JSON.stringify passes an item's own toJSON its index as the key; serialized alone it would get "".
		if (typeof item === "object" && item !== null && hasToJSON(item)) return undefined;
	}
	const text = JSON.stringify({ ...body, [key]: PLACEHOLDER });
	const at = text.indexOf(PLACEHOLDER_JSON);
	if (at < 0 || text.lastIndexOf(PLACEHOLDER_JSON) !== at) return undefined;
	const head = `${text.slice(0, at)}[`;
	const tail = `]${text.slice(at + PLACEHOLDER_JSON.length)}`;

	const state = conversationFor(conversation);
	const headBytes = head === state.head ? state.headBytes : utf8(head);
	const tailBytes = tail === state.tail ? state.tailBytes : utf8(tail);
	const snapshots = new Array<unknown>(items.length);
	const bytes = new Array<Bytes>(items.length);
	const parts: Bytes[] = [headBytes];
	for (let i = 0; i < items.length; i++) {
		const item = items[i];
		const previous = state.snapshots[i];
		// A value request-cache.ts handed out: the bytes it was first sent as, made below the first time.
		const cached = sent?.bytes(item);
		if (cached) {
			bytes[i] = cached;
			snapshots[i] = UNCACHEABLE;
			if (i > 0) parts.push(COMMA);
			parts.push(cached);
			continue;
		}
		if (previous !== undefined && previous !== UNCACHEABLE && matches(previous, item)) {
			snapshots[i] = previous;
			bytes[i] = state.bytes[i];
		} else {
			// An item that serializes to nothing (undefined, a function) is null in an array.
			bytes[i] = utf8(JSON.stringify(item) ?? "null");
			snapshots[i] = snapshot(item);
		}
		if (cached === null) {
			sent?.keep(bytes[i]);
			snapshots[i] = UNCACHEABLE;
		}
		if (i > 0) parts.push(COMMA);
		parts.push(bytes[i]);
	}
	parts.push(tailBytes);

	state.snapshots = snapshots;
	state.bytes = bytes;
	state.head = head;
	state.headBytes = headBytes;
	state.tail = tail;
	state.tailBytes = tailBytes;
	return parts;
}

interface Registration {
	key: string;
	conversation: string;
	allowedToJSON: unknown;
	cache: RequestCacheRun | undefined;
}

type BuildBody = (
	this: unknown,
	args: { options: { body?: unknown; headers?: unknown } },
) => {
	bodyHeaders?: unknown;
	body?: unknown;
};

const registered = new WeakMap<object, Registration>();
const wrappedClients = new WeakSet<object>();
const runtimeFetches = new WeakMap<object, boolean>();

/**
 * Whether `fetch` is the runtime's own (Bun's), not a function that wraps it: a wrapper passed to the SDK or installed
 * as the global fetch may read the body as the string the SDK sends otherwise, so it gets that string.
 */
function isRuntimeFetch(fetch: unknown): boolean {
	if (typeof fetch !== "function") return false;
	let native = runtimeFetches.get(fetch);
	if (native === undefined) {
		// A bound function prints as native code too, but its name starts with "bound ".
		native = fetch.name === "fetch" && Function.prototype.toString.call(fetch).includes("[native code]");
		runtimeFetches.set(fetch, native);
	}
	return native;
}

function registrationFor(body: unknown): Registration | undefined {
	if (typeof body !== "object" || body === null) return undefined;
	const record = body as Record<string, unknown>;
	for (const key of ["messages", "input"]) {
		const items = record[key];
		if (typeof items === "object" && items !== null) {
			const registration = registered.get(items);
			if (registration?.key === key) return registration;
		}
	}
	return undefined;
}

/**
 * Makes `client` (an openai or @anthropic-ai/sdk client) send the request body whose `key` property is `items` as
 * UTF-8 bytes built by jsonBodyParts. `allowedToJSON` is a toJSON on `items` known to serialize the array as it is.
 * Does nothing to a client without the SDK's body encoding. Never throws.
 */
export function sendJsonBodyAsBytes(
	client: unknown,
	items: unknown,
	key: "messages" | "input",
	conversation: string | undefined,
	allowedToJSON?: unknown,
	cache?: RequestCacheRun,
): void {
	if (typeof client !== "object" || client === null || !Array.isArray(items)) return;
	const target = client as { buildBody?: unknown };
	const original = target.buildBody;
	if (typeof original !== "function" || typeof Blob !== "function") return;
	registered.set(items, { key, conversation: conversation ?? "", allowedToJSON, cache });
	if (wrappedClients.has(client)) return;
	const buildBody = original as BuildBody;
	try {
		Object.defineProperty(client, "buildBody", {
			value(this: unknown, args: Parameters<BuildBody>[0]): ReturnType<BuildBody> {
				const body = args?.options?.body;
				const registration = registrationFor(body);
				if (registration && isRuntimeFetch((this as { fetch?: unknown }).fetch)) {
					try {
						// The SDK's encoding of an empty JSON body gives the headers it sends with one.
						const probe = buildBody.call(this, { ...args, options: { ...args.options, body: {} } });
						if (probe?.body === "{}") {
							const parts = jsonBodyParts(
								body as Record<string, unknown>,
								registration.key,
								registration.conversation,
								registration.allowedToJSON,
								registration.cache?.sentValues(),
							);
							if (parts) return { ...probe, body: new Blob(parts) };
						}
					} catch {
						// Left to the SDK.
					}
				}
				return buildBody.call(this, args);
			},
			configurable: true,
			writable: true,
			enumerable: false,
		});
		wrappedClients.add(client);
	} catch {
		// Not wrapped; the SDK serializes the body as before.
	}
}

/**
 * Prepares a request's `items` (body[key]) for serialization: long strings reuse their JSON (json-body.ts), and the
 * body is sent as bytes that reuse the conversation's previous request (sendJsonBodyAsBytes) and, with `cache` (the
 * request's run of request-cache.ts), the bytes of the values the run handed out.
 */
export function cacheRequestBody(
	client: unknown,
	items: unknown,
	key: "messages" | "input",
	conversation: string | undefined,
	cache?: RequestCacheRun,
): void {
	cacheJsonSerialization(items);
	sendJsonBodyAsBytes(client, items, key, conversation, cachedArrayToJSON, cache);
}
