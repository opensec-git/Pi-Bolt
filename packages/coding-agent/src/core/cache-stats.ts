import type { AssistantMessage } from "@earendil-works/pi-ai";
import type { FileEntry, SessionEntry } from "./session-manager.ts";

/**
 * Prompt-cache TTL: idle gaps longer than this are worth mentioning as the
 * likely cause of a miss. Anthropic's default cache TTL is 5 minutes.
 */
export const CACHE_TTL_MS = 5 * 60 * 1000;

/** Per-turn misses at or below this are cache breakpoint granularity noise. */
const NOISE_FLOOR_TOKENS = 1024;

/** A counted cache miss on a single assistant message. */
export interface CacheMiss {
	/** Prompt tokens that were in the previous turn's prompt but not read from cache. */
	missedTokens: number;
	/** Extra dollars paid vs. a full cache hit; 0 when pricing is unknown. */
	missedCost: number;
	/** Milliseconds since the previous request (which last refreshed the cache). */
	idleMs: number;
	/** True when the model changed relative to the previous request. */
	modelChanged: boolean;
}

export interface CacheWasteTotals {
	missedTokens: number;
	missedCost: number;
	/** Number of counted misses (turns above the noise floor). */
	missCount: number;
}

/** Minimal pricing lookup, satisfied by ModelRuntime. Cost is $/million tokens. */
export interface ModelPriceSource {
	getModel(provider: string, modelId: string): { cost: { cacheRead: number } } | undefined;
}

/** The last request seen by the scan; everything in its prompt should be cached. */
interface PreviousRequest {
	promptTokens: number;
	modelKey: string;
	timestamp: number;
	/**
	 * Sticky: some earlier request in this scan segment reported cache activity.
	 * Distinguishes a total miss on a cache-read-only provider (OpenAI-style,
	 * writes unreported) from a provider that never reports caching at all.
	 */
	reportedCache: boolean;
}

/**
 * Compute the cache miss for one assistant message relative to the previous
 * request. Returns undefined when nothing is counted: first turn, after a
 * reset, no cache activity ever reported (provider without cache support), or
 * miss below the noise floor.
 */
function detectMiss(
	prev: PreviousRequest | undefined,
	message: AssistantMessage,
	models: ModelPriceSource,
): CacheMiss | undefined {
	const usage = message.usage;
	const promptTokens = usage.input + usage.cacheRead + usage.cacheWrite;
	// A zero-cache turn only counts when cache activity was reported before:
	// on cache-read-only providers that is a total miss, while on providers
	// that never report caching it means nothing.
	if (!prev || promptTokens <= 0 || (usage.cacheRead + usage.cacheWrite === 0 && !prev.reportedCache)) {
		return undefined;
	}

	const missedTokens = Math.min(prev.promptTokens, promptTokens) - usage.cacheRead;
	if (missedTokens <= NOISE_FLOOR_TOKENS) return undefined;

	// Extra cost = missed tokens billed at the actual paid rate (input/cacheWrite,
	// incl. write premium) instead of the cache-read rate. Missed tokens can only
	// land in the input or cacheWrite buckets, so the paid rate comes straight
	// from this message's own cost breakdown.
	const paidTokens = usage.input + usage.cacheWrite;
	const paidPerToken = paidTokens > 0 ? (usage.cost.input + usage.cost.cacheWrite) / paidTokens : 0;
	const readPerToken =
		usage.cacheRead > 0
			? usage.cost.cacheRead / usage.cacheRead
			: (models.getModel(message.provider, message.model)?.cost.cacheRead ?? 0) / 1_000_000;

	return {
		missedTokens,
		missedCost: missedTokens * Math.max(0, paidPerToken - readPerToken),
		idleMs: Math.max(0, message.timestamp - prev.timestamp),
		modelChanged: `${message.provider}/${message.model}` !== prev.modelKey,
	};
}

function asPreviousRequest(message: AssistantMessage, reportedCache: boolean): PreviousRequest | undefined {
	const usage = message.usage;
	const promptTokens = usage.input + usage.cacheRead + usage.cacheWrite;
	if (promptTokens <= 0) return undefined;
	return {
		promptTokens,
		modelKey: `${message.provider}/${message.model}`,
		timestamp: message.timestamp,
		reportedCache: reportedCache || usage.cacheRead + usage.cacheWrite > 0,
	};
}

/** The last request needed to price the next notice, in persisted file order across branches. */
function nextRequest(prev: PreviousRequest | undefined, entry: FileEntry): PreviousRequest | undefined {
	if (entry.type === "compaction" || entry.type === "branch_summary") return undefined;
	if (entry.type === "message" && entry.message.role === "assistant") {
		return asPreviousRequest(entry.message, prev?.reportedCache ?? false) ?? prev;
	}
	if (entry.type === "usage" && entry.kind === "cache_warm") {
		const promptTokens = entry.usage.input + entry.usage.cacheRead + entry.usage.cacheWrite;
		if (promptTokens > 0) {
			return {
				promptTokens,
				modelKey: `${entry.provider}/${entry.model}`,
				timestamp: Date.parse(entry.timestamp),
				reportedCache: true,
			};
		}
	}
	return prev;
}

function visitMisses(
	entries: readonly SessionEntry[],
	models: ModelPriceSource,
	visit: (message: AssistantMessage, miss: CacheMiss) => void,
	include?: (message: AssistantMessage) => boolean,
): void {
	let prev: PreviousRequest | undefined;
	for (const entry of entries) {
		if (entry.type === "message" && entry.message.role === "assistant" && (!include || include(entry.message))) {
			const miss = detectMiss(prev, entry.message, models);
			if (miss) visit(entry.message, miss);
		}
		// Hidden and off-branch entries can still establish the previous request for a visible notice.
		prev = nextRequest(prev, entry);
	}
}

/**
 * Cumulative cache waste across a session: prompt tokens that should have been
 * cache reads (they were in the previous turn's prompt) but were re-billed.
 */
export function computeCacheWaste(entries: SessionEntry[], models: ModelPriceSource): CacheWasteTotals {
	const totals: CacheWasteTotals = { missedTokens: 0, missedCost: 0, missCount: 0 };
	visitMisses(entries, models, (_message, miss) => {
		totals.missedTokens += miss.missedTokens;
		totals.missedCost += miss.missedCost;
		totals.missCount++;
	});
	return totals;
}

/**
 * All counted cache misses across a session, keyed by the assistant message
 * (by reference) that paid for them. Used to re-derive transcript notices when
 * rebuilding the chat from entries (resume, post-compaction rebuild).
 */
export function collectCacheMisses(
	entries: SessionEntry[],
	models: ModelPriceSource,
	include?: (message: AssistantMessage) => boolean,
): Map<AssistantMessage, CacheMiss> {
	const misses = new Map<AssistantMessage, CacheMiss>();
	visitMisses(entries, models, (message, miss) => misses.set(message, miss), include);
	return misses;
}

/** Owned by one append-only session file. Discard it when the file is replaced or rebuilt. */
export interface CacheRequestCursor {
	end: number;
	previous?: PreviousRequest;
}

/**
 * Detect a cache miss on a just-completed assistant message.
 * `entries` must not yet contain `message` (message_end fires before persistence).
 */
export function detectCacheMiss(
	entries: readonly FileEntry[],
	message: AssistantMessage,
	models: ModelPriceSource,
	cursor: CacheRequestCursor = { end: 0 },
): CacheMiss | undefined {
	let previous = cursor.previous;
	for (let i = cursor.end; i < entries.length; i++) previous = nextRequest(previous, entries[i]);
	cursor.end = entries.length;
	cursor.previous = previous;
	return detectMiss(previous, message, models);
}
