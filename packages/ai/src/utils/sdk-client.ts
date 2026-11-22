/**
 * Reuses an SDK client between requests that would construct it with the same configuration.
 *
 * Problem: the OpenAI SDK's constructor creates one object for every API resource it has (chat, files, audio, realtime,
 * admin, beta and their sub-resources: about 240 in openai 7.59), and the providers made a new client for every request.
 * That cost about 0.7 M instructions per request, 5% of a short answer in RPC mode.
 *
 * Solution: the client of a configuration (API key, base URL, headers, fetch) is kept and used again for the next request
 * with the same configuration. SDK clients are made to serve any number of requests, concurrently too: what a request
 * changes lives in that request's own objects. A changed key (a refreshed token), base URL or header (an affinity header
 * of another session, a per-request header) is another configuration and gets its own client. The last few
 * configurations are kept.
 */

const MAX_CLIENTS = 4;

interface Kept<T> {
	fetch: unknown;
	client: T;
}

const clients = new Map<string, Kept<unknown>>();

/**
 * The client `make` returns for this configuration, made the first time and kept for the next requests. `key` must name
 * every option `make` passes to the constructor apart from the fetch, which is compared by identity: the one passed, or the
 * global fetch a client made without one keeps (Pi replaces it when its network settings change).
 */
export function reusableClient<T>(key: string, fetch: unknown, make: () => T): T {
	const kept = clients.get(key);
	if (kept && kept.fetch === fetch) {
		// The latest used goes last, so that the oldest is the first to go.
		clients.delete(key);
		clients.set(key, kept);
		return kept.client as T;
	}
	const client = make();
	clients.delete(key);
	if (clients.size >= MAX_CLIENTS) clients.delete(clients.keys().next().value!);
	clients.set(key, { fetch, client });
	return client;
}

/** The configuration key of a client made with these options. */
export function clientKey(...options: unknown[]): string {
	return JSON.stringify(options);
}

/** Forgets every kept client. For tests that check what a client is made with. */
export function forgetReusableClients(): void {
	clients.clear();
}
