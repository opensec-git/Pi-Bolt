/**
 * Reuses an SDK client between requests that would construct it with the same configuration.
 *
 * Problem: the OpenAI SDK's constructor creates one object for every API resource it has (chat, files, audio, realtime,
 * admin, beta and their sub-resources: about 240 in openai 7.59), and the providers made a new client for every request.
 * That cost about 0.7 M instructions per request, 5% of a short answer in RPC mode.
 *
 * Solution: the client of a configuration (API key, base URL, headers) is kept and used again for the next request with
 * the same configuration. SDK clients are made to serve any number of requests, concurrently too: what a request changes
 * lives in that request's own objects. A changed key (a refreshed token), base URL or header (an affinity header of
 * another session, a per-request header) is another configuration and gets its own client; the last few are kept.
 *
 * What is kept is bounded to what is in use: a client made with another API key for the same base URL (a rotated token, a
 * new login) replaces the clients of the old key, so an old credential is not held after its successor is used. Only a
 * client that uses the global fetch is kept: a fetch the caller passes may be made per call (a Workers binding), and a
 * kept client would never be used again. A client made without a fetch keeps the global fetch it is made with, so a
 * changed global fetch gets a new client.
 *
 * The SDK also reads some settings from the environment when a client is made (OPENAI_ORG_ID, OPENAI_PROJECT_ID,
 * OPENAI_CUSTOM_HEADERS, OPENAI_LOG): a kept client keeps the values it was made with. Pi sets its environment at start.
 */

const MAX_CLIENTS = 4;

/** What a provider passes to the SDK's constructor, apart from constants. */
export interface ClientConfiguration {
	apiKey: string;
	baseUrl: string | undefined;
	headers: unknown;
	/** The fetch the caller passed, if any. */
	fetch: unknown;
}

interface Kept {
	apiKey: string;
	baseUrl: string | undefined;
	fetch: unknown;
	client: unknown;
}

const clients = new Map<string, Kept>();

/** The client `make` returns for this configuration: made the first time and kept for the next requests (see above). */
export function reusableClient<T>(configuration: ClientConfiguration, make: () => T): T {
	if (configuration.fetch !== undefined && configuration.fetch !== globalThis.fetch) return make();
	const { apiKey, baseUrl, headers } = configuration;
	const fetch = globalThis.fetch;
	const key = JSON.stringify([apiKey, baseUrl, headers]);
	const kept = clients.get(key);
	clients.delete(key);
	if (kept && kept.fetch === fetch) {
		// The latest used goes last, so that the oldest is the first to go.
		clients.set(key, kept);
		return kept.client as T;
	}
	for (const [other, client] of clients) {
		if (client.baseUrl === baseUrl && client.apiKey !== apiKey) clients.delete(other);
	}
	const client = make();
	if (clients.size >= MAX_CLIENTS) clients.delete(clients.keys().next().value!);
	clients.set(key, { apiKey, baseUrl, fetch, client });
	return client;
}

/** Forgets every kept client. For tests that check what a client is made with. */
export function forgetReusableClients(): void {
	clients.clear();
}
