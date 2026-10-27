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
 * - Messages are not changed once they are in a conversation that was sent. Pi's messages are written to the session
 *   file when they are added and only ever replaced by new objects (compaction, branches, edits, an extension's
 *   `context` handler), never changed in place. A unit's messages must be the same objects as before. The one message
 *   that does change in place, a reply while it streams (stop reason "pending"), is never reused.
 * - Everything else the conversion of a unit reads is the same: the run's context (model, compatibility settings, the
 *   options that affect conversion) and the unit's `extra` (what depends on its place in the conversation: an index,
 *   a remapped tool call id). What depends on a message's position among all of them (cache markers on the latest
 *   messages, held or inserted messages, roles that must alternate) is computed again for every request, outside the
 *   units.
 * - Nothing changes a value after its conversion. The cache is only used for a request without a payload hook
 *   (`onPayload`, which could change the payload in place), and the converters put cache markers on copies of the
 *   latest messages. The SDKs do not change the payload.
 *
 * A message is remembered from the second request that converts it on, so messages that are new objects on every
 * request cost nothing (request-body.ts then reuses their bytes as before). Values no recent request used (messages
 * compacted away, a branch left) are dropped.
 */

/** A unit's conversion: the values it produced and what is needed to hand them out again. */
interface Entry {
	sources: readonly object[];
	extra: unknown;
	/** What the converter computed besides the values (a role, a flag); handed back with them. */
	meta: unknown;
	items: unknown[];
	/** The UTF-8 JSON of each item; made by request-body.ts when it first sends the item. */
	bytes: (Blob | undefined)[];
	/** The latest run that used the entry. */
	lastUse: number;
}

interface Table {
	units: Map<object, Entry>;
	derived: Map<object, Entry>;
	lastUse: number;
}

/** For request-body.ts: the entry an item sent belongs to, and its index in it. */
const itemEntries = new WeakMap<object, { entry: Entry; index: number }>();

/** The run that first converted each message: conversions are kept from the next run on. */
const firstSeen = new WeakMap<object, number>();

let runs = 0;
const stats = { units: 0, derived: 0 };

/**
 * The bytes of `item` (an item of a request's messages or input array) if a run handed it out: its JSON never changes,
 * so the bytes made the first time it was sent (by `make`) are sent again. Undefined for any other item.
 */
export function cachedItemBytes(item: unknown, make: () => Blob): Blob | undefined {
	if (typeof item !== "object" || item === null) return undefined;
	const found = itemEntries.get(item);
	if (!found) return undefined;
	const { entry, index } = found;
	let bytes = entry.bytes[index];
	if (bytes === undefined) {
		bytes = make();
		entry.bytes[index] = bytes;
	}
	return bytes;
}

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

const SWEEP_EVERY = 16;
/** Runs of the table since a value was last used, after which it is dropped. */
const SWEEP_AGE = 64;

/** One request's use of the cache: created before converting the request's messages. */
export class RequestCacheRun {
	readonly id: number;
	private readonly table: Table;

	constructor(table: Table) {
		this.id = ++runs;
		this.table = table;
	}

	/** Whether the run may reuse or remember conversions of `sources` (each converted by an earlier run, settled). */
	private cacheable(sources: readonly object[]): boolean {
		let cacheable = sources.length > 0;
		for (let i = 0; i < sources.length; i++) {
			const source = sources[i];
			const first = firstSeen.get(source);
			if (first === undefined) {
				firstSeen.set(source, this.id);
				cacheable = false;
			} else if (first === this.id || !settled(source)) {
				cacheable = false;
			}
		}
		return cacheable;
	}

	/**
	 * The values `sources` (one unit of the conversation, in order) converted to with `extra`, pushed onto `out`, and
	 * the meta value that conversion returned. `convert` pushes the unit's values onto the array it is given and
	 * returns the meta value; it runs when nothing can be reused, and what it pushes is kept for the next request.
	 * The unit's values must be new objects (they may share parts with the messages) that are put into the request
	 * as they are and that nothing changes afterwards.
	 */
	unit<M>(sources: readonly object[], extra: unknown, out: unknown[], convert: (out: unknown[]) => M): M {
		const map = this.table.units;
		const cacheable = this.cacheable(sources);
		if (cacheable) {
			const entry = map.get(sources[0]);
			if (entry !== undefined && entry.extra === extra && sameSources(entry.sources, sources)) {
				entry.lastUse = this.id;
				stats.units++;
				for (let i = 0; i < entry.items.length; i++) out.push(entry.items[i]);
				return entry.meta as M;
			}
		}
		const items: unknown[] = [];
		const meta = convert(items);
		if (cacheable && items.every((item) => typeof item === "object" && item !== null && !itemEntries.has(item))) {
			const entry: Entry = {
				sources: sources.slice(),
				extra,
				meta,
				items,
				bytes: new Array<Blob | undefined>(items.length),
				lastUse: this.id,
			};
			for (let i = 0; i < items.length; i++) itemEntries.set(items[i] as object, { entry, index: i });
			map.set(sources[0], entry);
		}
		for (let i = 0; i < items.length; i++) out.push(items[i]);
		return meta;
	}

	/**
	 * Like `unit`, for a value derived from one message that is only read to convert it (a transformed copy of the
	 * message): `make` returns it, and the same object is returned while the message and `extra` are the same.
	 */
	derive<T extends object>(source: object, extra: unknown, make: () => T): T {
		const map = this.table.derived;
		const cacheable = this.cacheable([source]);
		if (cacheable) {
			const entry = map.get(source);
			if (entry !== undefined && entry.extra === extra) {
				entry.lastUse = this.id;
				stats.derived++;
				return entry.items[0] as T;
			}
		}
		const value = make();
		if (cacheable) {
			map.set(source, { sources: [source], extra, meta: undefined, items: [value], bytes: [], lastUse: this.id });
		}
		return value;
	}

	/** Call once the request's messages are converted. */
	end(): void {
		// Values no recent run of the table used (messages compacted away, a branch left) are dropped.
		if (this.id % SWEEP_EVERY !== 0) return;
		for (const map of [this.table.units, this.table.derived]) {
			for (const [key, entry] of map) {
				if (this.id - entry.lastUse > SWEEP_AGE) map.delete(key);
			}
		}
	}
}

const MAX_TABLES = 4;
/** A table that none of the latest runs used is dropped. */
const MAX_IDLE_RUNS = 64;

const tables = new Map<string, Table>();
let disabled = false;

/**
 * Starts a request's use of the cache. `converter` names the conversion, `conversation` the session (undefined: no
 * caching), `context` everything besides the messages that the conversion reads; values are shared only between runs
 * with the same converter, conversation and JSON of the context (Maps and Sets as their entries). Undefined, and the
 * caller converts everything, when the request has a payload hook (`onPayload` given: it could change values in
 * place), when the context cannot be serialized, or when the cache is disabled.
 */
export function beginRequestCache(
	converter: string,
	conversation: string | undefined,
	onPayload: unknown,
	context: readonly unknown[],
): RequestCacheRun | undefined {
	if (disabled || conversation === undefined || onPayload !== undefined) return undefined;
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
		table = { units: new Map(), derived: new Map(), lastUse: 0 };
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
