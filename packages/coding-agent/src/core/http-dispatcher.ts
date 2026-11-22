import { EventEmitter } from "node:events";
import { createRequire } from "node:module";
import { RUNTIME_FETCH_WRAPPER } from "@earendil-works/pi-ai/utils/request-body";
import type * as Undici from "undici";
import { isBundledNode, isBunRuntime } from "../config.ts";

export const DEFAULT_HTTP_IDLE_TIMEOUT_MS = 300_000;
// Node's 250ms default can terminate valid connection attempts on high-latency routes.
const DEFAULT_AUTO_SELECT_FAMILY_ATTEMPT_TIMEOUT_MS = 2_000;
// How long an idle connection stays pooled when the server sends no Keep-Alive hint. Undici's default, 4 s, closed
// the connection during any longer pause between model requests (a tool batch, the user reading), so the next
// request paid a new TCP and TLS handshake (50-300 ms). A server's own `Keep-Alive: timeout=N` still wins, up to
// the maximum below. A server that closes sooner without a hint closes the socket itself; undici drops it then.
const KEEP_ALIVE_TIMEOUT_MS = 60_000;
const KEEP_ALIVE_MAX_TIMEOUT_MS = 600_000;

export const HTTP_IDLE_TIMEOUT_CHOICES = [
	{ label: "30 sec", timeoutMs: 30_000 },
	{ label: "1 min", timeoutMs: 60_000 },
	{ label: "2 min", timeoutMs: 120_000 },
	{ label: "5 min", timeoutMs: 300_000 },
	{ label: "disabled", timeoutMs: 0 },
] as const;

const originalGlobalFetch = globalThis.fetch;
let undiciModule: typeof Undici | undefined;

/**
 * Undici, loaded on first use: Bun never needs it (see configureHttpDispatcher), and loading it there costs startup
 * time and memory. esbuild bundles the literal require() into the Node bundle; elsewhere it resolves from node_modules.
 */
function undici(): typeof Undici {
	undiciModule ??= (isBundledNode ? require("undici") : createRequire(import.meta.url)("undici")) as typeof Undici;
	return undiciModule;
}
let installedGlobalFetch: typeof globalThis.fetch | undefined;

export function parseHttpIdleTimeoutMs(value: unknown): number | undefined {
	if (typeof value === "string") {
		const trimmed = value.trim();
		if (trimmed.toLowerCase() === "disabled") {
			return 0;
		}
		if (trimmed.length === 0) {
			return undefined;
		}
		return parseHttpIdleTimeoutMs(Number(trimmed));
	}

	if (typeof value !== "number" || !Number.isFinite(value) || value < 0) {
		return undefined;
	}
	return Math.floor(value);
}

export function formatHttpIdleTimeoutMs(timeoutMs: number): string {
	const choice = HTTP_IDLE_TIMEOUT_CHOICES.find((item) => item.timeoutMs === timeoutMs);
	if (choice) {
		return choice.label;
	}
	return `${timeoutMs / 1000} sec`;
}

export function applyHttpProxySettings(httpProxy: string | undefined): void {
	const proxy = httpProxy?.trim();
	if (!proxy) return;
	process.env.HTTP_PROXY ??= proxy;
	process.env.HTTPS_PROXY ??= proxy;
}

const ignoreUndiciDispatcherError = (_error: unknown): void => {};

// Undici can emit an internal Client "error" while terminating a mid-stream
// fetch body. The body stream still rejects through reader.read(); this listener
// only prevents EventEmitter's unhandled "error" special case from crashing pi.
function withUndiciErrorListener<T extends Undici.Dispatcher>(dispatcher: T): T {
	if (dispatcher instanceof EventEmitter) {
		EventEmitter.prototype.on.call(dispatcher, "error", ignoreUndiciDispatcherError);
	}
	return dispatcher;
}

function createUndiciClient(origin: string | URL, options: object): Undici.Dispatcher {
	return withUndiciErrorListener(new (undici().Client)(origin, options as Undici.Client.Options));
}

function createUndiciOriginDispatcher(origin: string | URL, options: object): Undici.Dispatcher {
	const dispatcherOptions = options as Undici.Pool.Options;
	if (dispatcherOptions.connections === 1) {
		return createUndiciClient(origin, dispatcherOptions);
	}
	return withUndiciErrorListener(
		new (undici().Pool)(origin, {
			...dispatcherOptions,
			factory: createUndiciClient,
		}),
	);
}

/** Bun's fetch options: `timeout: false` turns off its own idle timer for the request. */
interface BunRequestInit extends RequestInit {
	timeout?: boolean;
}

// Statuses whose response cannot be constructed with a body.
const NULL_BODY_STATUSES: ReadonlySet<number> = new Set([101, 103, 204, 205, 304]);

function idleTimeoutError(timeoutMs: number): DOMException {
	return new DOMException(`No data from the server for ${timeoutMs / 1000} s (HTTP idle timeout)`, "TimeoutError");
}

/**
 * Body of `response` that fails with a TimeoutError, and aborts the request through `controller`, when a read waits
 * longer than `timeoutMs` for the server. Time the consumer spends between reads does not count.
 */
function withBodyIdleTimeout(response: Response, controller: AbortController, timeoutMs: number): Response {
	const reader = (response.body as ReadableStream<Uint8Array>).getReader();
	let timedOut: DOMException | undefined;
	const body = new ReadableStream<Uint8Array>({
		async pull(streamController) {
			const timer = setTimeout(() => {
				timedOut = idleTimeoutError(timeoutMs);
				controller.abort(timedOut);
			}, timeoutMs);
			let result: Awaited<ReturnType<typeof reader.read>>;
			try {
				result = await reader.read();
			} catch (error) {
				throw timedOut ?? error;
			} finally {
				clearTimeout(timer);
			}
			if (timedOut) throw timedOut;
			if (result.done) streamController.close();
			else streamController.enqueue(result.value);
		},
		cancel(reason) {
			return reader.cancel(reason);
		},
	});
	const wrapped = new Response(body, {
		status: response.status,
		statusText: response.statusText,
		headers: response.headers,
	});
	Object.defineProperties(wrapped, {
		url: { value: response.url },
		redirected: { value: response.redirected },
	});
	return wrapped;
}

/**
 * Bun's fetch with the HTTP idle timeout applied as undici's headersTimeout and bodyTimeout apply it on Node: no
 * response headers within `timeoutMs`, or a body read that waits longer than `timeoutMs`, fails the request; 0 waits
 * forever. Bun's own idle timer is turned off for the request: it does not read this setting (5 minutes, or
 * BUN_CONFIG_HTTP_IDLE_TIMEOUT), so "disabled" or a longer timeout still failed after about 6 minutes (#206).
 */
export function createBunIdleTimeoutFetch(
	bunFetch: typeof globalThis.fetch,
	timeoutMs: number,
): typeof globalThis.fetch {
	const idleTimeoutFetch = async (
		input: Parameters<typeof globalThis.fetch>[0],
		init?: RequestInit,
	): Promise<Response> => {
		// A caller's own `timeout` option wins.
		const bunInit: BunRequestInit = { timeout: false, ...init };
		if (timeoutMs === 0) return bunFetch(input, bunInit);
		const controller = new AbortController();
		const callerSignal = init?.signal ?? (input instanceof Request ? input.signal : undefined);
		bunInit.signal = callerSignal ? AbortSignal.any([callerSignal, controller.signal]) : controller.signal;
		const timer = setTimeout(() => controller.abort(idleTimeoutError(timeoutMs)), timeoutMs);
		let response: Response;
		try {
			response = await bunFetch(input, bunInit);
		} finally {
			clearTimeout(timer);
		}
		if (!response.body || NULL_BODY_STATUSES.has(response.status)) return response;
		return withBodyIdleTimeout(response, controller, timeoutMs);
	};
	// The request body reaches `bunFetch` unchanged, so pi-ai still sends it as the bytes it keeps between requests
	// when `bunFetch` is the runtime's own.
	Object.defineProperty(idleTimeoutFetch, RUNTIME_FETCH_WRAPPER, { value: bunFetch });
	// Keep Bun's fetch.preconnect (see connection-warmer.ts).
	const preconnect = (bunFetch as { preconnect?: unknown }).preconnect;
	if (typeof preconnect === "function") Object.assign(idleTimeoutFetch, { preconnect: preconnect.bind(bunFetch) });
	return idleTimeoutFetch as typeof globalThis.fetch;
}

/**
 * Bun's fetch does not use undici dispatchers (its "undici" module is a stand-in whose global dispatcher nothing
 * reads), so the idle timeout is applied by wrapping the global fetch. Bun's own idle timer already is 5 minutes, the
 * default, so with the default the global fetch stays Bun's own, at no cost per request.
 */
function configureBunFetch(timeoutMs: number): void {
	const current = globalThis.fetch;
	if (current !== originalGlobalFetch && current !== installedGlobalFetch) return;
	if (timeoutMs === DEFAULT_HTTP_IDLE_TIMEOUT_MS) {
		globalThis.fetch = originalGlobalFetch;
		installedGlobalFetch = undefined;
		return;
	}
	installedGlobalFetch = createBunIdleTimeoutFetch(originalGlobalFetch, timeoutMs);
	globalThis.fetch = installedGlobalFetch;
}

export function configureHttpDispatcher(timeoutMs: number = DEFAULT_HTTP_IDLE_TIMEOUT_MS): void {
	const normalizedTimeoutMs = parseHttpIdleTimeoutMs(timeoutMs);
	if (normalizedTimeoutMs === undefined) {
		throw new Error(`Invalid HTTP idle timeout: ${String(timeoutMs)}`);
	}
	if (isBunRuntime) {
		configureBunFetch(normalizedTimeoutMs);
		return;
	}
	const dispatcher = withUndiciErrorListener(
		new (undici().EnvHttpProxyAgent)({
			allowH2: false,
			// Keep HTTP origins on CONNECT tunnels as they were before Undici 8.7.
			proxyTunnel: true,
			bodyTimeout: normalizedTimeoutMs,
			connect: {
				autoSelectFamilyAttemptTimeout: DEFAULT_AUTO_SELECT_FAMILY_ATTEMPT_TIMEOUT_MS,
			},
			headersTimeout: normalizedTimeoutMs,
			keepAliveTimeout: KEEP_ALIVE_TIMEOUT_MS,
			keepAliveMaxTimeout: KEEP_ALIVE_MAX_TIMEOUT_MS,
			clientFactory: createUndiciClient,
			factory: createUndiciOriginDispatcher,
		}),
	);
	undici().setGlobalDispatcher(dispatcher);
	// Keep fetch and the dispatcher on the same undici implementation. Node 26.0's
	// bundled fetch can otherwise consume compressed responses through npm undici's
	// dispatcher without decompressing them, causing response.json() failures.
	// If a caller replaced fetch after module load, preserve that deliberate override.
	const shouldInstallGlobals =
		installedGlobalFetch === undefined
			? globalThis.fetch === originalGlobalFetch
			: globalThis.fetch === installedGlobalFetch;
	if (shouldInstallGlobals) {
		undici().install?.();
		installedGlobalFetch = globalThis.fetch;
	}
}
