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

import { registerSessionResourceCleanup } from "../session-resources.ts";
import type { StreamOptions } from "../types.ts";

/** Comma-prefixed JSON for one array item. A repeated tool result can share its quoted UTF-8 payload independently of its call ID. */
export type EncodedItem = Blob | [Blob, ...Blob[]];

/** A unit's conversion: the values it produced and what is needed to hand them out again. */
interface Entry {
	/** A single converted object can keep its bytes here instead of allocating a one-element bytes array. */
	singleItem?: unknown;
	singleBytes?: EncodedItem;
	/** The unit's messages, in order (the array the converter passed; not changed afterwards). */
	sources: readonly object[];
	extra: unknown;
	/** What the converter computed besides the values (a role, a flag); handed back with them. */
	meta: unknown;
	items: unknown[];
	/** The UTF-8 JSON of each item; made by request-body.ts when it first sends the item. */
	bytes: (EncodedItem | undefined)[] | undefined;
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

/** Stage only the positions that differ. An in-flight conversion keeps its original order until it ends. */
class RequestOrder<T> {
	private readonly base: Order<T>;
	private changes?: { at: number; source: object; value: T | undefined }[];
	length = 0;

	constructor(base: Order<T>) {
		this.base = base;
	}

	take(source: object): T | undefined | false {
		const at = this.length++;
		if (this.base.sources[at] === source && settled(source)) return this.base.values[at];
		this.set(at, source, undefined);
		return false;
	}

	set(at: number, source: object, value: T | undefined): void {
		if (this.base.sources[at] === source && this.base.values[at] === value) return;
		this.changes ??= [];
		this.changes.push({ at, source, value });
	}

	finish(exclusive: boolean): Order<T> {
		const order = exclusive
			? this.base
			: {
					sources: this.base.sources.slice(0, this.length),
					values: this.base.values.slice(0, this.length),
				};
		if (this.changes) {
			for (const { at, source, value } of this.changes) {
				order.sources[at] = source;
				order.values[at] = value;
			}
		}
		order.sources.length = order.values.length = this.length;
		return order;
	}
}

interface CachedOrder {
	readers: number;
	units: Order<Entry>;
	derived: Order<Derived>;
}

interface Table {
	sessionId: string;
	order: CachedOrder;
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
	private readonly entries: readonly Entry[];
	private nextEntry = 0;
	private nextItem = 0;
	private last: Entry | undefined;
	private lastIndex = 0;

	/** Construct a cursor over the nonempty units handed out by one request. */
	constructor(entries: readonly Entry[]);
	/** Read an explicit mapping of items to entry slots, as accepted by the public utility. */
	constructor(items: readonly unknown[], entries: readonly Entry[], indexes: readonly number[]);
	constructor(...args: [readonly Entry[]] | [readonly unknown[], readonly Entry[], readonly number[]]) {
		if (args.length === 1) {
			this.entries = args[0];
			return;
		}
		const [items, entries, indexes] = args;
		this.entries = entries;
		let next = 0;
		this.bytes = (item) => {
			let at = next;
			if (at + 1 < items.length && items[at] !== item && items[at + 1] === item) at++;
			if (at >= items.length || items[at] !== item) return undefined;
			next = at + 1;
			this.last = entries[at];
			this.lastIndex = indexes[at];
			return this.last.bytes?.[this.lastIndex] ?? null;
		};
		this.keep = (bytes) => {
			if (!this.last) return;
			this.last.bytes ??= new Array(this.last.items.length);
			this.last.bytes[this.lastIndex] = bytes;
			this.last = undefined;
		};
	}

	/**
	 * For the next item of the request: its bytes when it is a value the run handed out and was sent before, null
	 * when it is one sent for the first time (then `keep` its bytes), undefined for any other item.
	 */
	bytes(item: unknown): EncodedItem | null | undefined {
		let position = this.nextEntry;
		let index = this.nextItem;
		let entry = this.entries[position];
		if (!entry) return undefined;
		if (entry.singleItem !== undefined && entry.singleItem === item) {
			this.nextEntry = position + 1;
			this.last = entry;
			this.lastIndex = 0;
			return entry.singleBytes ?? null;
		}
		if (entry.items[index] !== item) {
			// A converter may replace one handed-out value with a copy carrying a cache marker.
			// Look ahead without consuming the original until the request matches a later value.
			if (++index === entry.items.length) {
				entry = this.entries[++position];
				index = 0;
			}
			if (!entry || entry.items[index] !== item) return undefined;
		}
		if (index + 1 === entry.items.length) {
			this.nextEntry = position + 1;
			this.nextItem = 0;
		} else {
			this.nextEntry = position;
			this.nextItem = index + 1;
		}
		this.last = entry;
		this.lastIndex = index;
		return (entry.singleItem === undefined ? entry.bytes?.[index] : entry.singleBytes) ?? null;
	}

	/** The bytes of the item `bytes` returned null for. */
	keep(bytes: EncodedItem): void {
		const entry = this.last;
		if (entry === undefined) return;
		if (entry.singleItem !== undefined) {
			entry.singleBytes = bytes;
		} else {
			entry.bytes ??= new Array<EncodedItem | undefined>(entry.items.length);
			entry.bytes[this.lastIndex] = bytes;
		}
		this.last = undefined;
	}
}

/** One request's use of the cache: created before converting the request's messages. */
export class RequestCacheRun {
	readonly id: number;
	private readonly table: Table;
	/** Nonempty units handed out in request order. Each unit already owns its ordered items. */
	private readonly handedEntries: Entry[] = [];
	private active?: { base: CachedOrder; units: RequestOrder<Entry>; derived: RequestOrder<Derived> };

	constructor(table: Table) {
		this.id = ++runs;
		this.table = table;
		const base = table.order;
		base.readers++;
		this.active = { base, units: new RequestOrder(base.units), derived: new RequestOrder(base.derived) };
	}

	/**
	 * The values `sources` (one unit of the conversation, in order; a new array the caller does not change) converted
	 * to with `extra`, pushed onto `out`, and the meta value that conversion returned. `convert` pushes the unit's
	 * values onto the array it is given and returns the meta value; it runs when nothing can be reused, and what it
	 * pushes is kept for the next request. The unit's values must be new objects (they may share parts with the
	 * messages) that are put into the request as they are and that nothing changes afterwards.
	 */
	unit<M>(sources: readonly object[], extra: unknown, out: unknown[], convert: (out: unknown[]) => M): M {
		if (!this.active || sources.length === 0) return convert(out);
		const order = this.active.units;
		const position = order.length;
		let entry = order.take(sources[0]);
		for (let i = 1; entry !== false && i < sources.length; i++) {
			if (!settled(sources[i])) {
				order.set(position, sources[0], undefined);
				entry = false;
			}
		}
		// Nothing to keep: converted straight into the request.
		if (entry === false) return convert(out);
		if (entry !== undefined && entry.extra === extra && sameSources(entry.sources, sources)) {
			stats.units++;
			if (entry.items.length > 0) this.handedEntries.push(entry);
			for (let i = 0; i < entry.items.length; i++) out.push(entry.items[i]);
			return entry.meta as M;
		}
		if (entry !== undefined) order.set(position, sources[0], undefined);
		const items: unknown[] = [];
		const meta = convert(items);
		let keep = true;
		for (let i = 0; keep && i < items.length; i++) {
			const item = items[i];
			if (typeof item !== "object" || item === null) keep = false;
		}
		if (keep) {
			const kept: Entry = {
				sources,
				extra,
				meta,
				items,
				bytes: undefined,
				singleItem: items.length === 1 ? items[0] : undefined,
				singleBytes: undefined,
			};
			order.set(position, sources[0], kept);
			if (items.length > 0) this.handedEntries.push(kept);
		}
		for (let i = 0; i < items.length; i++) out.push(items[i]);
		return meta;
	}

	/**
	 * Like `unit`, for a value derived from one message that is only read to convert it (a transformed copy of the
	 * message): `make` returns it, and the same object is returned while the message and `extra` are the same.
	 */
	derive<T extends object>(source: object, extra: unknown, make: () => T): T {
		if (!this.active) return make();
		const order = this.active.derived;
		const position = order.length;
		const slot = order.take(source);
		if (slot && slot.extra === extra) {
			stats.derived++;
			return slot.value as T;
		}
		if (slot) order.set(position, source, undefined);
		const value = make();
		if (slot !== false) order.set(position, source, { extra, value });
		return value;
	}

	/** The values this run handed out, for request-body.ts; a new reader on every call. */
	sentValues(): SentValues {
		return new SentValues(this.handedEntries);
	}

	/** Call once the request's messages are converted (also when it failed): its lists are the next run's. */
	end(): void {
		const active = this.active;
		if (!active) return;
		this.active = undefined;
		const base = active.base;
		const exclusive = --base.readers === 0;
		// Another reader still needs the published version. Only that overlap requires a copy.
		const units = active.units.finish(exclusive);
		const derived = active.derived.finish(exclusive);
		this.table.order = exclusive ? base : { readers: 0, units, derived };
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
		table = {
			sessionId: conversation,
			order: { readers: 0, units: { sources: [], values: [] }, derived: { sources: [], values: [] } },
			lastUse: 0,
		};
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

/** Release the message references for one finished session, or for every session when called without an ID. */
export function clearRequestCache(sessionId?: string): void {
	if (sessionId === undefined) tables.clear();
	else for (const [key, table] of tables) if (table.sessionId === sessionId) tables.delete(key);
}

registerSessionResourceCleanup(clearRequestCache, "request-cache");

/** Turns the cache off (every request converts everything) or on again, without forgetting anything. For tests. */
export function setRequestCacheEnabled(enabled: boolean): void {
	disabled = !enabled;
}

/** How many units and derived values runs have reused so far. For tests. */
export function requestCacheStats(): { units: number; derived: number } {
	return { units: stats.units, derived: stats.derived };
}
