/**
 * JSON-RPC 2.0 over newline-delimited JSON, both ways, for ACP mode and MCP mode: the client calls Pi's methods, and Pi
 * calls the client's (permission requests, elicitation). Requests are served concurrently, so cancellation and the other
 * methods are answered while a prompt is still running.
 */

/** The error codes of JSON-RPC itself. */
export const JsonRpcErrorCode = {
	ParseError: -32700,
	InvalidRequest: -32600,
	MethodNotFound: -32601,
	InvalidParams: -32602,
	InternalError: -32603,
} as const;

export type JsonRpcId = string | number;

/** Thrown by method handlers to answer with a specific JSON-RPC error. */
export class JsonRpcError extends Error {
	readonly code: number;
	readonly data?: unknown;

	constructor(code: number, message: string, data?: unknown) {
		super(message);
		this.name = "JsonRpcError";
		this.code = code;
		this.data = data;
	}
}

export type JsonRpcRequestHandler = (method: string, params: unknown, id: JsonRpcId) => Promise<unknown>;
export type JsonRpcNotificationHandler = (method: string, params: unknown) => void;

interface PendingRequest {
	resolve: (value: unknown) => void;
	reject: (error: unknown) => void;
}

export class JsonRpcConnection {
	private readonly write: (line: string) => void;
	private readonly onRequest: JsonRpcRequestHandler;
	private readonly onNotification: JsonRpcNotificationHandler;
	private readonly onDrop: ((id: JsonRpcId) => void) | undefined;
	private readonly pending = new Map<JsonRpcId, PendingRequest>();
	/** Requests the client gave up on, which get no response. */
	private readonly abandoned = new Set<JsonRpcId>();
	private nextId = 0;
	private closed = false;

	constructor(options: {
		write: (line: string) => void;
		onRequest: JsonRpcRequestHandler;
		onNotification: JsonRpcNotificationHandler;
		/** Tells the client that Pi stopped waiting for the result of its request `id` (MCP's `notifications/cancelled`). */
		onDrop?: (id: JsonRpcId) => void;
	}) {
		this.write = options.write;
		this.onRequest = options.onRequest;
		this.onNotification = options.onNotification;
		this.onDrop = options.onDrop;
	}

	/** Handles one line received from the client. */
	receive(line: string): void {
		if (line.trim() === "") return;
		let message: unknown;
		try {
			message = JSON.parse(line);
		} catch (error) {
			this.send({
				jsonrpc: "2.0",
				id: null,
				error: { code: JsonRpcErrorCode.ParseError, message: errorText(error) },
			});
			return;
		}
		if (!isRecord(message)) {
			this.send({
				jsonrpc: "2.0",
				id: null,
				error: { code: JsonRpcErrorCode.InvalidRequest, message: "Not a JSON-RPC message" },
			});
			return;
		}
		const id = message.id;
		const hasId = typeof id === "string" || typeof id === "number";
		if (typeof message.method === "string") {
			if (hasId) void this.serve(id, message.method, message.params);
			else this.notification(message.method, message.params);
			return;
		}
		if (hasId && ("result" in message || "error" in message)) {
			const request = this.pending.get(id);
			if (!request) return;
			this.pending.delete(id);
			if (isRecord(message.error)) {
				request.reject(
					new JsonRpcError(
						typeof message.error.code === "number" ? message.error.code : JsonRpcErrorCode.InternalError,
						typeof message.error.message === "string" ? message.error.message : "Client error",
						message.error.data,
					),
				);
			} else {
				request.resolve(message.result);
			}
		}
	}

	/**
	 * Calls a client method and waits for its result. When `signal` aborts first, the request is dropped: the promise
	 * rejects with the signal's reason, the client is told (`onDrop`), and a late answer is ignored.
	 */
	request<T>(method: string, params: unknown, signal?: AbortSignal): Promise<T> {
		if (this.closed) return Promise.reject(new Error("The connection is closed"));
		if (signal?.aborted) return Promise.reject(signal.reason);
		const id = this.nextId++;
		return new Promise<T>((resolve, reject) => {
			const onAbort = () => {
				if (!this.pending.delete(id)) return;
				reject(signal?.reason);
				this.onDrop?.(id);
			};
			signal?.addEventListener("abort", onAbort, { once: true });
			const done = () => signal?.removeEventListener("abort", onAbort);
			this.pending.set(id, {
				resolve: (value) => {
					done();
					resolve(value as T);
				},
				reject: (error) => {
					done();
					reject(error);
				},
			});
			this.send({ jsonrpc: "2.0", id, method, params });
		});
	}

	notify(method: string, params: unknown): void {
		if (this.closed) return;
		this.send({ jsonrpc: "2.0", method, params });
	}

	/** Fails the requests still waiting for the client, which has gone. */
	close(): void {
		this.closed = true;
		for (const request of this.pending.values()) request.reject(new Error("The connection is closed"));
		this.pending.clear();
	}

	/** Sends no response to a request the client cancelled while it runs (as MCP asks). */
	abandon(id: JsonRpcId): void {
		this.abandoned.add(id);
	}

	private async serve(id: JsonRpcId, method: string, params: unknown): Promise<void> {
		try {
			const result = await this.onRequest(method, params, id);
			if (this.abandoned.delete(id)) return;
			this.send({ jsonrpc: "2.0", id, result: result ?? null });
		} catch (error) {
			if (this.abandoned.delete(id)) return;
			const code = error instanceof JsonRpcError ? error.code : JsonRpcErrorCode.InternalError;
			const data = error instanceof JsonRpcError ? error.data : undefined;
			this.send({
				jsonrpc: "2.0",
				id,
				error: data === undefined ? { code, message: errorText(error) } : { code, message: errorText(error), data },
			});
		}
	}

	private notification(method: string, params: unknown): void {
		try {
			this.onNotification(method, params);
		} catch {
			// Notifications have no response to carry an error.
		}
	}

	private send(message: object): void {
		if (this.closed) return;
		this.write(`${JSON.stringify(message)}\n`);
	}
}

export function isRecord(value: unknown): value is Record<string, unknown> {
	return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function errorText(error: unknown): string {
	return error instanceof Error ? error.message : String(error);
}
