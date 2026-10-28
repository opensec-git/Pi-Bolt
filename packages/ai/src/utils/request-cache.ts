/**
 * Reuses what each message of a conversation converted to in the conversation's previous requests.
 *
 * Every request converts the whole conversation into the provider's message objects again (transformMessages, then the
 * provider's converter), and request-body.ts then compares every converted message with the previous request's to
 * reuse its bytes. In a long session that is most of the CPU a request costs, although only the last few messages are
 * new. A converter asks a run (one request's use of the cache) for each part of the conversation it converts on its
 * own (a unit: one message, or a run of tool results that become one provider message) and gets the objects that unit
 * converted to before; request-body.ts sends those objects as the bytes they were sent as before, without comparing.
 *
 * Why a reused value is what converting again would give:
 * - Messages are not changed once they are in a conversation that was sent: the caller says so (`immutableMessages`;
 *   off by default). The coding agent does for its sessions, whose messages are written to the session file when they
 *   are added and only ever replaced by new objects (compaction, branches, edits, an extension's `context` handler),
 *   and only while no extension other than Pi's own is loaded. A unit's messages must be the same objects as before.
 *   The one message that does change in place, a reply while it streams (stop reason "pending"), is never reused.
 * - Everything else the conversion of a unit reads is the same: the run's context (model, compatibility settings, the
 *   options that affect conversion) and the unit's `extra` (what depends on its place in the conversation: an index,
 *   a remapped tool call id). What depends on a message's position among all of them (cache markers on the latest
 *   messages, held or inserted messages, roles that must alternate) is computed again for every request, outside the
 *   units.
 * - Nothing changes a value after its conversion. The cache is only used for a request without a payload hook
 *   (`onPayload`, which could change the payload in place), and the converters put cache markers on copies of the
 *   latest messages. The SDKs do not change the payload.
 *
 * A message's conversion is kept from the second request that converts it at the same place on, so messages that are
 * new objects on every request cost nothing (request-body.ts then reuses their bytes as before). Only what the latest
 * request used is kept (messages compacted away or on a branch left are dropped).
 */

import type { StreamOptions } from "../types.ts";

/** A unit's conversion: the values it produced and what is needed to hand them out again. */
interface Entry {
	/** The unit's messages, in order (the array the converter passed; not changed afterwards). */
	sources: readonly object[];
	extra: unknown;
	/** What the converter computed besides the values (a role, a flag); handed back with them. */
	meta: unknown;
	items: unknown[];
	/** The UTF-8 JSON of each item; made by request-body.ts when it first sends the item. */
	bytes: (Blob | undefined)[] | undefined;
}

/** A value derived from one message, only read to convert it. */
interface Derived {
	extra: unknown;
	value: object;
}

/**
 * What one kind of request (units or derived values) asked for, in order: the message at each place and what was
 * kept for it. A run looks at the same place in the previous run: a value is reused when that place had the same
 * message, and a new conversion is kept only then. So the first request of a conversation keeps nothing, a message
 * that is a new object on every request (an extension's `context` handler copies them) costs nothing, and what the
 * latest request did not use (messages compacted away, a branch left) is dropped with the previous run's lists.
 * Messages that moved (after compaction, on a branch switch) convert again once.
 */
interface Order<T> {
	sources: object[];
	values: (T | undefined)[];
}

interface Table {
	units: Order<Entry>;
	derived: Order<Derived>;
	lastUse: number;
}

let runs = 0;
const stats = { units: 0, derived: 0 };

/** Whether a message may be reused: a reply still streaming changes in place. */
function settled(message: object): boolean {
	return (message as { stopReason?: unknown }).stopReason !== "pending";
}

function sameSources(a: readonly object[], b: readonly object[]): boolean {
	if (a.length !== b.length) return false;
	for (let i = 0; i < a.length; i++) {
		if (a[i] !== b[i]) return false;
	}
	return true;
}

/**
 * For request-body.ts: the bytes of the values a run handed out, met in the order the run handed them out. An item of
 * the request that is not the next value (a message the converter made outside a unit, a copy with a cache marker) is
 * serialized as before.
 */
export class SentValues {
	private readonly items: readonly unknown[];
	private readonly entries: readonly Entry[];
	private readonly indexes: readonly number[];
	private next = 0;
	private last: Entry | undefined;
	private lastIndex = 0;

	constructor(items: readonly unknown[], entries: readonly Entry[], indexes: readonly number[]) {
		this.items = items;
		this.entries = entries;
		this.indexes = indexes;
	}

	/**
	 * For the next item of the request: its bytes when it is a value the run handed out and was sent before, null
	 * when it is one sent for the first time (then `keep` its bytes), undefined for any other item.
	 */
	bytes(item: unknown): Blob | null | undefined {
		let at = this.next;
		// One value skipped: a copy with a cache marker in its place.
		if (at + 1 < this.items.length && this.items[at] !== item && this.items[at + 1] === item) at++;
		if (at >= this.items.length || this.items[at] !== item) return undefined;
		this.next = at + 1;
		const entry = this.entries[at];
		const index = this.indexes[at];
		this.last = entry;
		this.lastIndex = index;
		return entry.bytes?.[index] ?? null;
	}

	/** The bytes of the item `bytes` returned null for. */
	keep(bytes: Blob): void {
		const entry = this.last;
		if (entry === undefined) return;
		entry.bytes ??= new Array<Blob | undefined>(entry.items.length);
		entry.bytes[this.lastIndex] = bytes;
		this.last = undefined;
	}
}

/** One request's use of the cache: created before converting the request's messages. */
export class RequestCacheRun {
	readonly id: number;
	private readonly table: Table;
	/** The values handed out, in order, with their entries and indexes in them. */
	private readonly handedItems: unknown[] = [];
	private readonly handedEntries: Entry[] = [];
	private readonly handedIndexes: number[] = [];
	private ended = false;

	constructor(table: Table) {
		this.id = ++runs;
		this.table = table;
	}

	/** What this run asked for, in order; the table's lists for the next run once it ends. */
	private readonly units: Order<Entry> = { sources: [], values: [] };
	private readonly derived: Order<Derived> = { sources: [], values: [] };

	/**
	 * For the next place of `order`: what the previous run kept there for `source`, or undefined (convert, and keep
	 * the result). False when nothing may be kept (the message is not settled, or the previous run had another one
	 * there): convert without keeping.
	 */
	private slot<T>(previous: Order<T>, order: Order<T>, source: object): T | undefined | false {
		const position = order.sources.length;
		order.sources.push(source);
		order.values.push(undefined);
		if (previous.sources[position] !== source || !settled(source)) return false;
		return previous.values[position];
	}

	private hand(entry: Entry): void {
		for (let i = 0; i < entry.items.length; i++) {
			this.handedItems.push(entry.items[i]);
			this.handedEntries.push(entry);
			this.handedIndexes.push(i);
		}
	}

	/**
	 * The values `sources` (one unit of the conversation, in order; a new array the caller does not change) converted
	 * to with `extra`, pushed onto `out`, and the meta value that conversion returned. `convert` pushes the unit's
	 * values onto the array it is given and returns the meta value; it runs when nothing can be reused, and what it
	 * pushes is kept for the next request. The unit's values must be new objects (they may share parts with the
	 * messages) that are put into the request as they are and that nothing changes afterwards.
	 */
	unit<M>(sources: readonly object[], extra: unknown, out: unknown[], convert: (out: unknown[]) => M): M {
		if (sources.length === 0) return convert(out);
		const order = this.units;
		const position = order.sources.length;
		let entry = this.slot(this.table.units, order, sources[0]);
		for (let i = 1; entry !== false && i < sources.length; i++) {
			if (!settled(sources[i])) entry = false;
		}
		// Nothing to keep: converted straight into the request.
		if (entry === false) return convert(out);
		if (entry !== undefined && entry.extra === extra && sameSources(entry.sources, sources)) {
			stats.units++;
			order.values[position] = entry;
			this.hand(entry);
			for (let i = 0; i < entry.items.length; i++) out.push(entry.items[i]);
			return entry.meta as M;
		}
		const items: unknown[] = [];
		const meta = convert(items);
		let keep = true;
		for (let i = 0; keep && i < items.length; i++) {
			const item = items[i];
			if (typeof item !== "object" || item === null) keep = false;
		}
		if (keep) {
			const kept: Entry = { sources, extra, meta, items, bytes: undefined };
			order.values[position] = kept;
			this.hand(kept);
		}
		for (let i = 0; i < items.length; i++) out.push(items[i]);
		return meta;
	}

	/**
	 * Like `unit`, for a value derived from one message that is only read to convert it (a transformed copy of the
	 * message): `make` returns it, and the same object is returned while the message and `extra` are the same.
	 */
	derive<T extends object>(source: object, extra: unknown, make: () => T): T {
		const order = this.derived;
		const position = order.sources.length;
		const slot = this.slot(this.table.derived, order, source);
		if (slot && slot.extra === extra) {
			stats.derived++;
			order.values[position] = slot;
			return slot.value as T;
		}
		const value = make();
		if (slot !== false) order.values[position] = { extra, value };
		return value;
	}

	/** The values this run handed out, for request-body.ts; a new reader on every call. */
	sentValues(): SentValues {
		return new SentValues(this.handedItems, this.handedEntries, this.handedIndexes);
	}

	/** Call once the request's messages are converted (also when it failed): its lists are the next run's. */
	end(): void {
		if (this.ended) return;
		this.ended = true;
		this.table.units = this.units;
		this.table.derived = this.derived;
	}
}

const MAX_TABLES = 4;
/** A table that none of the latest runs used is dropped. */
const MAX_IDLE_RUNS = 64;

const tables = new Map<string, Table>();
let disabled = false;

/**
 * Starts a request's use of the cache. `converter` names the conversion, `context` everything besides the messages
 * that the conversion reads; values are shared only between runs with the same converter, session and JSON of the
 * context (Maps and Sets as their entries). Undefined, and the caller converts everything, unless the caller said its
 * messages are never changed in place (`immutableMessages`) and gave a session id; also with a payload hook
 * (`onPayload`, which could change values in place), when the context cannot be serialized, or when the cache is
 * disabled.
 */
export function beginRequestCache(
	converter: string,
	options: Pick<StreamOptions, "sessionId" | "onPayload" | "immutableMessages"> | undefined,
	context: readonly unknown[],
): RequestCacheRun | undefined {
	const conversation = options?.sessionId;
	if (
		disabled ||
		options?.immutableMessages !== true ||
		conversation === undefined ||
		options.onPayload !== undefined
	) {
		return undefined;
	}
	let key: string;
	try {
		key = `${converter}\u0000${conversation}\u0000${JSON.stringify(context, (_key, value: unknown) =>
			value instanceof Map ? [...value] : value instanceof Set ? [...value] : value,
		)}`;
	} catch {
		return undefined;
	}
	for (const [other, table] of tables) {
		if (runs - table.lastUse > MAX_IDLE_RUNS) tables.delete(other);
	}
	let table = tables.get(key);
	if (table) {
		tables.delete(key);
	} else {
		table = { units: { sources: [], values: [] }, derived: { sources: [], values: [] }, lastUse: 0 };
		if (tables.size >= MAX_TABLES) {
			const oldest = tables.keys().next().value;
			if (oldest !== undefined) tables.delete(oldest);
		}
	}
	tables.set(key, table);
	const run = new RequestCacheRun(table);
	table.lastUse = run.id;
	return run;
}

/** Forgets every cached conversion: the next request of each conversation converts every message. */
export function clearRequestCache(): void {
	tables.clear();
}

/** Turns the cache off (every request converts everything) or on again, without forgetting anything. For tests. */
export function setRequestCacheEnabled(enabled: boolean): void {
	disabled = !enabled;
}

/** How many units and derived values runs have reused so far. For tests. */
export function requestCacheStats(): { units: number; derived: number } {
	return { units: stats.units, derived: stats.derived };
}
