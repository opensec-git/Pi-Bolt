/**
 * The local control channel of remote control: one process at a time holds it, the background service
 * (`pi-bolt service run`) or a terminal running `/remote-control`. It is a Unix socket in the agent directory (a named pipe
 * on Windows) carrying one JSON object per line, and every request carries the token in `remote-control/service.token`, so
 * only the user who owns the agent directory gets an answer.
 *
 * What goes over it:
 * - `status`: the holder describes itself (`pi-bolt service status`).
 * - `pair`: the service makes a pairing code and later reports `paired` (`pi-bolt service pair`).
 * - `yield`: a terminal's `/remote-control` asks the service for its port. The service stops listening, answers with the
 *   port (and its tunnel's address when asked to keep it), and listens again when this connection closes.
 */

import { createHash, randomBytes, timingSafeEqual } from "node:crypto";
import { chmodSync, existsSync, mkdirSync, readFileSync, unlinkSync } from "node:fs";
import { createConnection, createServer, type Server, type Socket } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { writePrivateFile } from "./store.ts";

export interface ControlPaths {
	socket: string;
	token: string;
}

export type ControlRequest =
	| { type: "status" }
	| { type: "pair" }
	/** `keepTunnel`: the terminal reuses the service's tunnel (it points at the port the terminal takes over). */
	| { type: "yield"; keepTunnel: boolean }
	/** On a yielded connection: the terminal no longer wants the service's tunnel (`/remote-control lan`). */
	| { type: "tunnel_off" };

export type ControlMessage = Record<string, unknown>;

/** Unix socket paths longer than this do not fit `sun_path` (104 bytes on macOS, 108 on Linux). */
const MAX_SOCKET_PATH = 100;
const CONNECT_TIMEOUT_MS = 1_000;
const REPLY_TIMEOUT_MS = 5_000;
const MAX_LINE_BYTES = 64 * 1024;

/** Where the control socket and its token are for this agent directory. */
export function controlPaths(agentDir: string, platform: NodeJS.Platform = process.platform): ControlPaths {
	const dir = join(agentDir, "remote-control");
	const id = createHash("sha256").update(dir).digest("hex").slice(0, 16);
	const token = join(dir, "service.token");
	if (platform === "win32") return { socket: `\\\\.\\pipe\\pi-bolt-rc-${id}`, token };
	const socket = join(dir, "service.sock");
	return { socket: socket.length <= MAX_SOCKET_PATH ? socket : join(tmpdir(), `pi-bolt-rc-${id}.sock`), token };
}

/** The token, made on first use (mode 0600, next to the host key). */
function readOrCreateToken(path: string): Buffer {
	if (existsSync(path)) {
		const token = Buffer.from(readFileSync(path, "utf8").trim(), "base64url");
		if (token.length === 32) return token;
	}
	const dir = join(path, "..");
	if (!existsSync(dir)) mkdirSync(dir, { recursive: true, mode: 0o700 });
	const token = randomBytes(32);
	writePrivateFile(path, `${token.toString("base64url")}\n`);
	return token;
}

/** One side of a control connection: JSON lines in, JSON lines out. */
export class ControlConnection {
	readonly closed: Promise<void>;
	private readonly socket: Socket;
	private buffer = "";
	private readonly queue: ControlMessage[] = [];
	private readonly waiters: Array<(message: ControlMessage | undefined) => void> = [];
	private handler: ((message: ControlMessage) => void) | undefined;
	private ended = false;

	constructor(socket: Socket) {
		this.socket = socket;
		socket.setEncoding("utf8");
		this.closed = new Promise((resolve) => {
			socket.on("close", () => {
				this.ended = true;
				for (const waiter of this.waiters.splice(0)) waiter(undefined);
				resolve();
			});
		});
		socket.on("error", () => {});
		socket.on("data", (chunk: string) => this.onData(chunk));
	}

	send(message: ControlMessage): void {
		if (!this.ended) this.socket.write(`${JSON.stringify(message)}\n`);
	}

	/** Every later message goes to `handler` instead of [next]. */
	onMessage(handler: (message: ControlMessage) => void): void {
		this.handler = handler;
		for (const message of this.queue.splice(0)) handler(message);
	}

	/** The next message; undefined when the connection closes or `timeoutMs` passes first. */
	next(timeoutMs = REPLY_TIMEOUT_MS): Promise<ControlMessage | undefined> {
		const queued = this.queue.shift();
		if (queued) return Promise.resolve(queued);
		if (this.ended) return Promise.resolve(undefined);
		return new Promise((resolve) => {
			// (Infinity waits until the connection closes.)
			const timer = Number.isFinite(timeoutMs)
				? setTimeout(
						() => {
							this.waiters.splice(this.waiters.indexOf(finish), 1);
							resolve(undefined);
						},
						Math.max(timeoutMs, 0),
					)
				: undefined;
			const finish = (message: ControlMessage | undefined) => {
				clearTimeout(timer);
				resolve(message);
			};
			this.waiters.push(finish);
		});
	}

	close(): void {
		this.ended = true;
		this.socket.end();
		this.socket.destroy();
	}

	private onData(chunk: string): void {
		this.buffer += chunk;
		if (this.buffer.length > MAX_LINE_BYTES && !this.buffer.includes("\n")) {
			this.close();
			return;
		}
		let newline = this.buffer.indexOf("\n");
		while (newline !== -1) {
			const line = this.buffer.slice(0, newline);
			this.buffer = this.buffer.slice(newline + 1);
			newline = this.buffer.indexOf("\n");
			let message: unknown;
			try {
				message = JSON.parse(line);
			} catch {
				this.close();
				return;
			}
			if (typeof message !== "object" || message === null || Array.isArray(message)) continue;
			if (this.handler) this.handler(message as ControlMessage);
			else {
				const waiter = this.waiters.shift();
				if (waiter) waiter(message as ControlMessage);
				else this.queue.push(message as ControlMessage);
			}
		}
	}
}

/** A client of the holder: requests carry the token. */
export class ControlClient {
	readonly connection: ControlConnection;
	private readonly token: string;

	constructor(connection: ControlConnection, token: string) {
		this.connection = connection;
		this.token = token;
	}

	get closed(): Promise<void> {
		return this.connection.closed;
	}

	send(request: ControlRequest): void {
		this.connection.send({ ...request, token: this.token });
	}

	/** Sends `request` and resolves with the next message, or undefined (closed, or no answer in 5 s). */
	async request(request: ControlRequest, timeoutMs?: number): Promise<ControlMessage | undefined> {
		this.send(request);
		return this.connection.next(timeoutMs);
	}

	close(): void {
		this.connection.close();
	}
}

export interface ControlListener {
	close(): void;
}

/**
 * Holds the control channel and passes each authorized request to `onRequest`. Undefined when another live process holds
 * it. A socket file left by a process that crashed is replaced.
 */
export async function listenControl(
	paths: ControlPaths,
	onRequest: (request: ControlRequest, connection: ControlConnection) => void,
): Promise<ControlListener | undefined> {
	const token = readOrCreateToken(paths.token);
	const connections = new Set<ControlConnection>();
	const server = createServer((socket) => {
		const connection = new ControlConnection(socket);
		connections.add(connection);
		void connection.closed.then(() => connections.delete(connection));
		connection.onMessage((message) => {
			const offered = typeof message.token === "string" ? Buffer.from(message.token, "base64url") : Buffer.alloc(0);
			if (offered.length !== token.length || !timingSafeEqual(offered, token)) {
				connection.send({ ok: false, error: "unauthorized" });
				connection.close();
				return;
			}
			if (typeof message.type !== "string") return;
			onRequest(message as ControlRequest, connection);
		});
	});
	const listening = await listenOn(server, paths.socket);
	if (listening === "in-use") {
		if (process.platform === "win32" || (await isAlive(paths.socket))) return undefined;
		// Left over from a process that ended without removing it.
		try {
			unlinkSync(paths.socket);
		} catch {
			return undefined;
		}
		if ((await listenOn(server, paths.socket)) === "in-use") return undefined;
	}
	if (process.platform !== "win32") {
		try {
			chmodSync(paths.socket, 0o600);
		} catch {
			// The directory (0700) already keeps others out.
		}
	}
	return {
		close: () => {
			// Open connections end too: a service waiting on this holder hears that it let go.
			server.close();
			for (const connection of connections) connection.close();
			// (Node removes the socket file when the server closes.)
		},
	};
}

function listenOn(server: Server, path: string): Promise<"ok" | "in-use"> {
	return new Promise((resolve, reject) => {
		const onError = (error: NodeJS.ErrnoException) => {
			server.off("listening", onListening);
			if (error.code === "EADDRINUSE") resolve("in-use");
			else reject(error);
		};
		const onListening = () => {
			server.off("error", onError);
			resolve("ok");
		};
		server.once("error", onError);
		server.once("listening", onListening);
		server.listen(path);
	});
}

async function isAlive(path: string): Promise<boolean> {
	const socket = await openSocket(path);
	socket?.destroy();
	return socket !== undefined;
}

function openSocket(path: string): Promise<Socket | undefined> {
	return new Promise((resolve) => {
		const socket = createConnection(path);
		const timer = setTimeout(() => {
			socket.destroy();
			resolve(undefined);
		}, CONNECT_TIMEOUT_MS);
		socket.once("connect", () => {
			clearTimeout(timer);
			resolve(socket);
		});
		socket.once("error", () => {
			clearTimeout(timer);
			resolve(undefined);
		});
	});
}

/** Connects to the holder; undefined when nothing holds the channel (or the token cannot be read). */
export async function connectControl(paths: ControlPaths): Promise<ControlClient | undefined> {
	if (!existsSync(paths.token)) return undefined;
	let token: string;
	try {
		token = readFileSync(paths.token, "utf8").trim();
	} catch {
		return undefined;
	}
	if (process.platform !== "win32" && !existsSync(paths.socket)) return undefined;
	const socket = await openSocket(paths.socket);
	return socket ? new ControlClient(new ControlConnection(socket), token) : undefined;
}

/**
 * What a terminal's `/remote-control` got:
 * - `service`: the background service gave up its port (and maybe its tunnel); `release` gives them back.
 * - `holder`: no service runs; this terminal holds the channel, so a service that starts now waits for it.
 * - `none`: another terminal serves remote control, or the channel is unusable; this one serves alongside, as before.
 */
export type RemoteControlClaim =
	| { kind: "service"; port: number; tunnel?: string; release(): void; dropTunnel(): void }
	| { kind: "holder" | "none"; release(): void };

export interface ClaimOptions {
	keepTunnel: boolean;
	/** The service's tunnel that this terminal uses stopped (or the service went away). */
	onTunnelDown?: (message: string) => void;
}

/** Claims remote control for a terminal's `/remote-control`: from the service if one runs, else the channel itself. */
export async function claimRemoteControl(agentDir: string, options: ClaimOptions): Promise<RemoteControlClaim> {
	const paths = controlPaths(agentDir);
	const client = await connectControl(paths).catch(() => undefined);
	if (client) {
		const reply = await client.request({ type: "yield", keepTunnel: options.keepTunnel });
		if (reply?.ok !== true || typeof reply.port !== "number") {
			client.close();
			return { kind: "none", release: () => {} };
		}
		let released = false;
		let holder: ControlListener | undefined;
		client.connection.onMessage((message) => {
			if (message.type === "tunnel_down") options.onTunnelDown?.(String(message.message ?? "The tunnel stopped."));
		});
		// The service went away (stopped or crashed): hold the channel, so a restarted service waits for this terminal.
		void client.closed.then(async () => {
			if (released) return;
			if (typeof reply.tunnel === "string") options.onTunnelDown?.("The background service stopped.");
			holder = await holdAsTerminal(paths).catch(() => undefined);
			if (released) holder?.close();
		});
		return {
			kind: "service",
			port: reply.port,
			...(typeof reply.tunnel === "string" ? { tunnel: reply.tunnel } : {}),
			release: () => {
				released = true;
				client.close();
				holder?.close();
			},
			dropTunnel: () => client.send({ type: "tunnel_off" }),
		};
	}
	const holder = await holdAsTerminal(paths).catch(() => undefined);
	return holder ? { kind: "holder", release: () => holder.close() } : { kind: "none", release: () => {} };
}

/** Holds the channel for a terminal: it answers `status` and refuses the rest. */
function holdAsTerminal(paths: ControlPaths): Promise<ControlListener | undefined> {
	return listenControl(paths, (request, connection) => {
		if (request.type === "status") connection.send({ ok: true, role: "terminal", pid: process.pid });
		else connection.send({ ok: false, error: "A terminal's /remote-control serves remote control now" });
	});
}
