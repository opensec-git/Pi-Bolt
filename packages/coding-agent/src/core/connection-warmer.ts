/**
 * Opens the connection to the model's endpoint while the user types, after an idle period.
 *
 * Problem: a provider closes an idle keep-alive connection (often after a minute or less), so the first request after
 * the user has read an answer for a while opens a new connection: a TCP and a TLS handshake, two or three round trips
 * (50-300 ms to a distant endpoint) before the request is even sent.
 *
 * Solution: when the user starts typing a prompt after `idleMs` without model activity, ask the runtime to connect
 * to the endpoint the next request goes to (Bun's fetch.preconnect: DNS, TCP and TLS, then the socket waits in the
 * keep-alive pool the next fetch takes it from). It sends no request, no headers and no credentials. If the pool still
 * holds a live connection to that endpoint, it opens none. At most once per idle period: the next one starts with
 * that preconnect. Only Bun has fetch.preconnect; elsewhere this does nothing. Bun skips an origin the environment's
 * proxy settings route through a proxy.
 *
 * Only for the APIs whose requests go to the model's base URL through the global fetch, and only for HTTPS: a
 * plain-HTTP endpoint is local or near in practice, with no TLS handshake to save. The base URL is the one the model's
 * latest request in this process went to (ModelRuntime.lastRequestBaseUrl): auth can replace the model's own (GitHub
 * Copilot's OAuth credential names its endpoint), and resolving auth may refresh a token over the network, so before
 * a model's first request nothing is preconnected.
 *
 * Only model traffic counts as activity: assistant messages streaming and compaction (a summarization request), not
 * session bookkeeping such as a thinking-level change.
 *
 * Two more moments open the connection ahead of a request, in every mode (interactive, print, RPC):
 * - Startup (preconnectAtStartup): the first prompt's request otherwise pays the handshakes. Before any request the
 *   base URL is the model's own, unless the provider signs in with OAuth, whose credential may name another endpoint.
 * - A long tool batch (ConnectionWarmer.onAgentEvent): the request with the tool results follows the batch, which can
 *   outlast the endpoint's idle limit. While the batch runs, preconnect every `idleMs`; each is a no-op while the pool
 *   holds a live connection.
 */
import type { Api, Model } from "@earendil-works/pi-ai";
import type { ModelRuntime } from "./model-runtime.ts";

/** Typing after this long without model activity preconnects. */
export const CONNECTION_IDLE_MS = 10_000;

const PRECONNECT_APIS: ReadonlySet<string> = new Set(["openai-completions", "openai-responses", "anthropic-messages"]);

type FetchWithPreconnect = { preconnect?: (url: string) => void };

/** Bun's fetch.preconnect, or undefined where the runtime has none. */
export function runtimePreconnect(): ((url: string) => void) | undefined {
	const fetch = globalThis.fetch as unknown as FetchWithPreconnect | undefined;
	const preconnect = fetch?.preconnect;
	if (typeof preconnect !== "function") return undefined;
	return (url) => preconnect.call(fetch, url);
}

/**
 * The origin of `baseUrl`, the base URL `model`'s requests go to, or undefined when preconnecting it would not help or
 * the base URL is unknown.
 */
export function preconnectOrigin(model: Model<Api> | undefined, baseUrl: string | undefined): string | undefined {
	if (!model || baseUrl === undefined || !PRECONNECT_APIS.has(model.api)) return undefined;
	let url: URL;
	try {
		url = new URL(baseUrl);
	} catch {
		return undefined;
	}
	return url.protocol === "https:" ? url.origin : undefined;
}

/**
 * The origin to preconnect for `model`'s next request, without resolving auth: the base URL its latest request in this
 * process used, else the model's own unless its provider signs in with OAuth (GitHub Copilot's credential names its
 * endpoint). An extension's API-key auth could name another endpoint too; then the preconnect opens a connection that
 * goes unused, and sends nothing.
 */
export function modelPreconnectOrigin(
	model: Model<Api> | undefined,
	runtime: Pick<ModelRuntime, "lastRequestBaseUrl" | "isUsingOAuth">,
): string | undefined {
	if (!model) return undefined;
	const baseUrl =
		runtime.lastRequestBaseUrl(model) ?? (runtime.isUsingOAuth(model.provider) ? undefined : model.baseUrl);
	return preconnectOrigin(model, baseUrl);
}

/** Preconnect to the endpoint of `model`'s first request while Pi starts. Never throws. */
export function preconnectAtStartup(
	model: Model<Api> | undefined,
	runtime: Pick<ModelRuntime, "lastRequestBaseUrl" | "isUsingOAuth">,
	preconnect: ((url: string) => void) | undefined = runtimePreconnect(),
): void {
	const origin = preconnect && modelPreconnectOrigin(model, runtime);
	if (!origin) return;
	try {
		preconnect(origin);
	} catch {
		// The request connects as it would have.
	}
}

/** Whether a session event is traffic with the model: a request being made or answered. */
export function isModelActivity(event: { type: string; message?: unknown }): boolean {
	switch (event.type) {
		case "message_start":
		case "message_update":
		case "message_end":
			return (event.message as { role?: unknown } | undefined)?.role === "assistant";
		case "compaction_start":
		case "compaction_end":
			return true;
		default:
			return false;
	}
}

export interface ConnectionWarmerOptions {
	preconnect?: ((url: string) => void) | undefined;
	now?: () => number;
	idleMs?: number;
	/** The origin the next model request goes to, for preconnects during a tool batch (see onAgentEvent). */
	target?: () => string | undefined;
}

export class ConnectionWarmer {
	private readonly preconnect: ((url: string) => void) | undefined;
	private readonly now: () => number;
	private readonly idleMs: number;
	private readonly target: (() => string | undefined) | undefined;
	/** The last model activity or preconnect; none yet at start, so the first prompt's typing preconnects. */
	private lastActivity = Number.NEGATIVE_INFINITY;
	/** Repeats the preconnect while a tool batch runs. */
	private toolBatchTimer: ReturnType<typeof setInterval> | undefined;

	constructor(options: ConnectionWarmerOptions = {}) {
		this.preconnect = "preconnect" in options ? options.preconnect : runtimePreconnect();
		this.now = options.now ?? Date.now;
		this.idleMs = options.idleMs ?? CONNECTION_IDLE_MS;
		this.target = options.target;
	}

	/** A request is being made or answered: the connection is in use. */
	markActivity(): void {
		this.lastActivity = this.now();
	}

	/**
	 * Follow an agent event. From a tool batch's first call to the end of its turn, preconnects to `target()` every
	 * `idleMs`: the request with the tool results then finds an open connection, however long the batch ran. A turn
	 * end (the results are about to be sent) or the end of the run stops it. Never throws.
	 */
	onAgentEvent(event: { type: string }): void {
		switch (event.type) {
			case "tool_execution_start": {
				const target = this.target;
				if (!this.preconnect || !target || this.toolBatchTimer) return;
				this.toolBatchTimer = setInterval(() => this.preconnectTo(target()), this.idleMs);
				this.toolBatchTimer.unref?.();
				return;
			}
			case "turn_end":
			case "agent_end":
				this.stop();
				return;
		}
	}

	/** Stop preconnecting for a tool batch. */
	stop(): void {
		clearInterval(this.toolBatchTimer);
		this.toolBatchTimer = undefined;
	}

	/**
	 * The editor's text changed to `text` while no request runs. Preconnects to `baseUrl`, where `model`'s requests go,
	 * if this starts a prompt after an idle period. Never throws.
	 */
	onTyping(text: string, model: Model<Api> | undefined, baseUrl: string | undefined): void {
		if (!this.preconnect) return;
		const now = this.now();
		if (now - this.lastActivity < this.idleMs) return;
		// Not a prompt: nothing, a command, a shell command.
		const start = text.trimStart();
		if (start === "" || start.startsWith("/") || start.startsWith("!")) return;
		const origin = preconnectOrigin(model, baseUrl);
		if (!origin) return;
		this.lastActivity = now;
		this.preconnectTo(origin);
	}

	private preconnectTo(origin: string | undefined): void {
		if (!origin || !this.preconnect) return;
		try {
			this.preconnect(origin);
		} catch {
			// The request connects as it would have.
		}
	}
}
