/**
 * The remote control server: one HTTP listener that upgrades `/rc` to a WebSocket, runs the Noise IK handshake, then
 * mirrors the session's event stream to every paired phone and runs the RPC commands they send
 * (docs/remote-control.md). Nothing is sent before a handshake completes.
 */

import { randomBytes, randomUUID, timingSafeEqual } from "node:crypto";
import { createServer, type IncomingMessage, type Server } from "node:http";
import type { Duplex } from "node:stream";
import { type WebSocket, WebSocketServer } from "ws";
import type { AgentSession } from "../core/agent-session.ts";
import { toJsonEvent } from "../modes/json-event.ts";
import type {
	RpcCommand,
	RpcExtensionUIRequest,
	RpcExtensionUIResponse,
	RpcResponse,
	RpcSessionState,
	RpcSlashCommand,
} from "../modes/rpc/rpc-types.ts";
import { IkResponder, type KeyPair, type TransportKeys } from "./noise.ts";
import {
	CloseCode,
	encodePairingUri,
	encryptMessage,
	MessageAssembler,
	NOISE_PROLOGUE,
	PROTOCOL_VERSION,
	WEBSOCKET_PATH,
	WEBSOCKET_SUBPROTOCOL,
} from "./protocol.ts";
import { type PairedDevice, type RemoteControlStore, toBase64Url } from "./store.ts";

/**
 * Time from the upgrade to the client's first frame (handshake message 1, which a client sends as soon as the socket
 * opens). An idle socket is closed and counts as a failed handshake for its address.
 */
const FIRST_FRAME_TIMEOUT_MS = 3_000;
const PING_INTERVAL_MS = 20_000;
const FAILURE_WINDOW_MS = 10 * 60_000;
const MAX_FAILURES_PER_WINDOW = 10;
const DEFAULT_PAIRING_TTL_MS = 10 * 60_000;
const MAX_DEVICE_NAME_LENGTH = 80;
/**
 * Connections still in their handshake, per address and in total; more are refused until some finish or time out.
 * The per-address cap keeps one peer from filling the table with idle sockets and locking the phone out.
 */
const MAX_PENDING_PER_ADDRESS = 3;
const MAX_PENDING_HANDSHAKES = 64;

/** Commands a phone may send. Everything else changes what the terminal shows and stays with the terminal. */
const REMOTE_COMMANDS = new Set<RpcCommand["type"]>([
	"prompt",
	"steer",
	"follow_up",
	"abort",
	"clear_queue",
	"get_state",
	"get_messages",
	"get_available_models",
	"set_model",
	"cycle_model",
	"get_available_thinking_levels",
	"set_thinking_level",
	"cycle_thinking_level",
	"compact",
	"abort_retry",
	"get_session_stats",
	"get_commands",
	"get_last_assistant_text",
	"set_session_name",
]);

type DistributiveOmit<T, K extends PropertyKey> = T extends unknown ? Omit<T, K> : never;

/** A dialog as sent to phones, without the id and type the server adds. */
export type RemoteDialogRequest = DistributiveOmit<
	Extract<RpcExtensionUIRequest, { method: "select" | "confirm" | "input" | "editor" }>,
	"id" | "type"
>;

export interface RemoteClientInfo {
	id: string;
	device: PairedDevice;
	address: string;
	connectedAt: Date;
}

export type RemoteControlEvent =
	| { type: "client_connected"; client: RemoteClientInfo; paired: boolean }
	| { type: "client_disconnected"; client: RemoteClientInfo }
	| { type: "handshake_rejected"; address: string; reason: string };

export interface RemoteControlServerOptions {
	store: RemoteControlStore;
	getSession: () => AgentSession;
	hostName: string;
	version: string;
	/** 0 (default) picks a free port. */
	port?: number;
	/** Bind address; default all interfaces. */
	bindAddress?: string;
	pairingTtlMs?: number;
	/** Deadline for a client's first frame; default 3 s (tests shorten it). */
	firstFrameTimeoutMs?: number;
	onEvent?: (event: RemoteControlEvent) => void;
}

interface OpenDialog {
	request: RpcExtensionUIRequest;
	resolve: (response: RpcExtensionUIResponse | undefined) => void;
}

interface Pairing {
	secret: Buffer;
	expiresAt: number;
	used: boolean;
}

function asError(error: unknown): string {
	return error instanceof Error ? error.message : String(error);
}

function isLoopback(address: string): boolean {
	return address === "127.0.0.1" || address === "::1" || address === "::ffff:127.0.0.1";
}

class RemoteConnection {
	readonly id = randomUUID();
	readonly connectedAt = new Date();
	private keys: TransportKeys | undefined;
	private readonly assembler = new MessageAssembler();
	private readonly handshakeTimer: ReturnType<typeof setTimeout>;
	private alive = true;
	device: PairedDevice | undefined;
	readonly socket: WebSocket;
	readonly address: string;
	private readonly server: RemoteControlServer;

	constructor(socket: WebSocket, address: string, server: RemoteControlServer) {
		this.socket = socket;
		this.address = address;
		this.server = server;
		this.handshakeTimer = setTimeout(() => {
			server.recordFailure(address, "no handshake");
			this.close(CloseCode.HandshakeTimeout);
		}, server.firstFrameTimeoutMs);
		socket.on("message", (data, isBinary) => this.onMessage(data, isBinary));
		socket.on("pong", () => {
			this.alive = true;
		});
		socket.on("close", () => {
			clearTimeout(this.handshakeTimer);
			server.connectionClosed(this);
		});
		socket.on("error", () => {});
	}

	get isOpen(): boolean {
		return !this.revoked && this.keys !== undefined && this.socket.readyState === this.socket.OPEN;
	}

	/** Set by [revoke]: nothing more from this connection runs, even before its socket is gone. */
	revoked = false;

	/**
	 * The device was revoked: stop handling its messages now, tell it why (4403), and drop the socket after a second
	 * if the peer does not finish the close handshake.
	 */
	revoke(): void {
		this.revoked = true;
		this.close(CloseCode.NotAuthorized);
		setTimeout(() => this.socket.terminate(), 1_000).unref?.();
	}

	info(): RemoteClientInfo | undefined {
		return this.device
			? { id: this.id, device: this.device, address: this.address, connectedAt: this.connectedAt }
			: undefined;
	}

	/** Pings; returns false when the previous ping went unanswered. */
	heartbeat(): boolean {
		if (!this.alive) {
			this.socket.terminate();
			return false;
		}
		this.alive = false;
		this.socket.ping();
		return true;
	}

	close(code: number): void {
		clearTimeout(this.handshakeTimer);
		this.socket.close(code);
	}

	send(message: unknown): void {
		if (!this.isOpen || !this.keys) return;
		for (const frame of encryptMessage(this.keys.send, message)) this.socket.send(frame, { binary: true });
	}

	private onMessage(data: WebSocket.RawData, isBinary: boolean): void {
		if (!isBinary) {
			this.close(CloseCode.BadMessage);
			return;
		}
		const frame = Array.isArray(data) ? Buffer.concat(data) : Buffer.from(data as ArrayBuffer);
		if (!this.keys) {
			// Nothing a client sends may throw out of the socket's handler: the terminal treats an uncaught exception
			// as fatal.
			try {
				this.handshake(frame);
			} catch {
				this.close(CloseCode.BadMessage);
			}
			return;
		}
		let result: ReturnType<MessageAssembler["push"]>;
		try {
			result = this.assembler.push(this.keys.receive, frame);
		} catch {
			this.close(CloseCode.BadMessage);
			return;
		}
		if (result.complete) void this.server.handleClientMessage(this, result.message);
	}

	private handshake(frame: Buffer): void {
		const responder = new IkResponder({ prologue: NOISE_PROLOGUE, staticKeyPair: this.server.hostKeyPair });
		let remoteStaticKey: Buffer;
		let hello: { device?: unknown; app?: unknown; pair?: unknown };
		try {
			const read = responder.readMessage1(frame);
			remoteStaticKey = read.remoteStaticKey;
			const parsed: unknown = JSON.parse(read.payload.toString("utf8"));
			if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) throw new Error("Bad hello");
			hello = parsed as typeof hello;
		} catch {
			this.server.recordFailure(this.address, "malformed handshake");
			this.close(CloseCode.BadMessage);
			return;
		}
		// The name is shown in the terminal: no control characters (escape sequences).
		const cleaned =
			typeof hello.device === "string" ? hello.device.replace(/[\u0000-\u001f\u007f-\u009f]/g, "").trim() : "";
		const name = cleaned ? cleaned.slice(0, MAX_DEVICE_NAME_LENGTH) : "Phone";
		const authorization = this.server.authorize(
			remoteStaticKey,
			typeof hello.pair === "string" ? hello.pair : undefined,
		);
		if (!authorization) {
			this.server.recordFailure(this.address, "not authorized");
			this.close(CloseCode.NotAuthorized);
			return;
		}
		this.device = this.server.store.upsertDevice(remoteStaticKey, name);
		const reply = Buffer.from(
			JSON.stringify({
				host: this.server.hostName,
				version: this.server.version,
				paired: authorization === "paired",
			}),
			"utf8",
		);
		const { message, keys } = responder.writeMessage2(reply);
		clearTimeout(this.handshakeTimer);
		this.socket.send(message, { binary: true });
		this.keys = keys;
		this.server.connectionOpened(this, authorization === "paired");
	}
}

export class RemoteControlServer {
	readonly store: RemoteControlStore;
	readonly hostKeyPair: KeyPair;
	readonly hostName: string;
	readonly version: string;
	readonly firstFrameTimeoutMs: number;
	private readonly options: RemoteControlServerOptions;
	private httpServer: Server | undefined;
	private wss: WebSocketServer | undefined;
	private readonly connections = new Set<RemoteConnection>();
	private readonly failures = new Map<string, number[]>();
	private readonly dialogs = new Map<string, OpenDialog>();
	private pairing: Pairing | undefined;
	private unsubscribe: (() => void) | undefined;
	private pingTimer: ReturnType<typeof setInterval> | undefined;
	private boundPort = 0;

	constructor(options: RemoteControlServerOptions) {
		this.options = options;
		this.store = options.store;
		this.hostKeyPair = options.store.hostKeyPair();
		this.hostName = options.hostName;
		this.version = options.version;
		this.firstFrameTimeoutMs = options.firstFrameTimeoutMs ?? FIRST_FRAME_TIMEOUT_MS;
	}

	get port(): number {
		return this.boundPort;
	}

	get running(): boolean {
		return this.httpServer !== undefined;
	}

	clients(): RemoteClientInfo[] {
		return [...this.connections].flatMap((connection) => {
			const info = connection.isOpen ? connection.info() : undefined;
			return info ? [info] : [];
		});
	}

	async start(): Promise<number> {
		if (this.httpServer) return this.boundPort;
		const wss = new WebSocketServer({
			noServer: true,
			maxPayload: 70_000,
			perMessageDeflate: false,
			handleProtocols: (protocols) => (protocols.has(WEBSOCKET_SUBPROTOCOL) ? WEBSOCKET_SUBPROTOCOL : false),
		});
		const httpServer = createServer((_request, response) => {
			response.writeHead(404).end();
		});
		httpServer.on("upgrade", (request: IncomingMessage, socket: Duplex, head: Buffer) => {
			const address = this.clientAddress(request);
			const path = (request.url ?? "").split("?")[0];
			const pending = [...this.connections].filter((connection) => !connection.isOpen);
			const pendingHere = pending.filter((connection) => connection.address === address).length;
			if (
				path !== WEBSOCKET_PATH ||
				this.tooManyFailures(address) ||
				pendingHere >= MAX_PENDING_PER_ADDRESS ||
				pending.length >= MAX_PENDING_HANDSHAKES
			) {
				socket.destroy();
				return;
			}
			wss.handleUpgrade(request, socket, head, (ws) => {
				this.connections.add(new RemoteConnection(ws, address, this));
			});
		});
		await new Promise<void>((resolve, reject) => {
			httpServer.once("error", reject);
			httpServer.listen(this.options.port ?? 0, this.options.bindAddress ?? "0.0.0.0", () => {
				httpServer.off("error", reject);
				resolve();
			});
		});
		const address = httpServer.address();
		this.boundPort = typeof address === "object" && address ? address.port : 0;
		this.httpServer = httpServer;
		this.wss = wss;
		this.subscribe();
		this.pingTimer = setInterval(() => {
			for (const connection of this.connections) if (connection.isOpen) connection.heartbeat();
			for (const address of [...this.failures.keys()]) this.tooManyFailures(address);
		}, PING_INTERVAL_MS);
		this.pingTimer.unref?.();
		return this.boundPort;
	}

	async stop(): Promise<void> {
		this.unsubscribe?.();
		this.unsubscribe = undefined;
		if (this.pingTimer) clearInterval(this.pingTimer);
		for (const dialog of this.dialogs.values()) dialog.resolve(undefined);
		this.dialogs.clear();
		for (const connection of this.connections) connection.close(CloseCode.GoingAway);
		this.connections.clear();
		this.wss?.close();
		const httpServer = this.httpServer;
		this.httpServer = undefined;
		this.wss = undefined;
		if (httpServer) {
			httpServer.closeAllConnections?.();
			await new Promise<void>((resolve) => httpServer.close(() => resolve()));
		}
	}

	/** A new one-time pairing secret; replaces the previous one. */
	newPairing(now = Date.now()): { secret: Buffer; expiresAt: number } {
		const secret = randomBytes(32);
		const expiresAt = now + (this.options.pairingTtlMs ?? DEFAULT_PAIRING_TTL_MS);
		this.pairing = { secret, expiresAt, used: false };
		return { secret, expiresAt };
	}

	/** The QR code text for the current pairing secret (a new one if none is valid). */
	pairingUri(endpoints: string[]): string {
		const pairing =
			this.pairing && !this.pairing.used && this.pairing.expiresAt > Date.now() ? this.pairing : this.newPairing();
		return encodePairingUri({
			v: PROTOCOL_VERSION,
			n: this.hostName,
			k: toBase64Url(this.hostKeyPair.publicKey),
			s: toBase64Url(pairing.secret),
			e: endpoints,
			x: Math.floor(pairing.expiresAt / 1000),
		});
	}

	/** "known" for a paired device, "paired" when the secret pairs it now, undefined to refuse. */
	authorize(publicKey: Buffer, secret: string | undefined): "known" | "paired" | undefined {
		if (this.store.findDevice(publicKey)) return "known";
		const pairing = this.pairing;
		if (!secret || !pairing || pairing.used || pairing.expiresAt <= Date.now()) return undefined;
		const offered = Buffer.from(secret, "base64url");
		if (offered.length !== pairing.secret.length || !timingSafeEqual(offered, pairing.secret)) return undefined;
		pairing.used = true;
		return "paired";
	}

	/** Disconnects and forgets a device. */
	revoke(publicKey: string): boolean {
		const removed = this.store.removeDevice(publicKey);
		for (const connection of this.connections) {
			if (connection.device?.publicKey === publicKey) connection.revoke();
		}
		return removed;
	}

	recordFailure(address: string, reason: string): void {
		const now = Date.now();
		const recent = (this.failures.get(address) ?? []).filter((time) => now - time < FAILURE_WINDOW_MS);
		recent.push(now);
		this.failures.set(address, recent);
		this.options.onEvent?.({ type: "handshake_rejected", address, reason });
	}

	private tooManyFailures(address: string): boolean {
		const now = Date.now();
		const recent = (this.failures.get(address) ?? []).filter((time) => now - time < FAILURE_WINDOW_MS);
		if (recent.length > 0) this.failures.set(address, recent);
		else this.failures.delete(address);
		return recent.length >= MAX_FAILURES_PER_WINDOW;
	}

	private clientAddress(request: IncomingMessage): string {
		const address = request.socket.remoteAddress ?? "unknown";
		// Through the tunnel every connection comes from cloudflared on this machine; Cloudflare names the real client.
		// Only trusted from loopback: a LAN client could set the header itself.
		const forwarded = request.headers["cf-connecting-ip"];
		if (isLoopback(address) && typeof forwarded === "string" && forwarded) return forwarded;
		return address;
	}

	connectionOpened(connection: RemoteConnection, paired: boolean): void {
		connection.send(this.hello());
		const client = connection.info();
		if (client) this.options.onEvent?.({ type: "client_connected", client, paired });
	}

	connectionClosed(connection: RemoteConnection): void {
		const wasOpen = this.connections.delete(connection) && connection.device !== undefined;
		const client = connection.info();
		if (wasOpen && client) this.options.onEvent?.({ type: "client_disconnected", client });
	}

	private broadcast(message: unknown): void {
		for (const connection of this.connections) if (connection.isOpen) connection.send(message);
	}

	private subscribe(): void {
		this.unsubscribe?.();
		this.unsubscribe = this.options.getSession().subscribe((event) => this.broadcast(toJsonEvent(event)));
	}

	/** Call after the terminal switched to another session: phones get the new session's snapshot. */
	sessionChanged(): void {
		if (!this.running) return;
		this.subscribe();
		for (const connection of this.connections) if (connection.isOpen) connection.send(this.hello());
	}

	private sessionState(session: AgentSession): RpcSessionState {
		return {
			model: session.model,
			thinkingLevel: session.thinkingLevel,
			isStreaming: session.isStreaming,
			isCompacting: session.isCompacting,
			steeringMode: session.steeringMode,
			followUpMode: session.followUpMode,
			sessionFile: session.sessionFile,
			sessionId: session.sessionId,
			sessionName: session.sessionName,
			autoCompactionEnabled: session.autoCompactionEnabled,
			messageCount: session.messages.length,
			pendingMessageCount: session.pendingMessageCount,
		};
	}

	private commands(session: AgentSession): RpcSlashCommand[] {
		return [
			...session.extensionRunner.getRegisteredCommands().map(
				(command): RpcSlashCommand => ({
					name: command.invocationName,
					description: command.description,
					source: "extension",
					sourceInfo: command.sourceInfo,
				}),
			),
			...session.promptTemplates.map(
				(template): RpcSlashCommand => ({
					name: template.name,
					description: template.description,
					source: "prompt",
					sourceInfo: template.sourceInfo,
				}),
			),
			...session.resourceLoader.getSkills().skills.map(
				(skill): RpcSlashCommand => ({
					name: `skill:${skill.name}`,
					description: skill.description,
					source: "skill",
					sourceInfo: skill.sourceInfo,
				}),
			),
		];
	}

	private hello(): object {
		const session = this.options.getSession();
		const streamingMessage = session.isStreaming ? session.agent.state.streamingMessage : undefined;
		return {
			type: "remote_hello",
			protocol: PROTOCOL_VERSION,
			host: { name: this.hostName, version: this.version, cwd: session.sessionManager.getCwd() },
			state: this.sessionState(session),
			messages: session.messages,
			...(streamingMessage ? { streamingMessage } : {}),
			dialogs: [...this.dialogs.values()].map((dialog) => dialog.request),
			commands: this.commands(session),
		};
	}

	/**
	 * Shows a dialog on every phone (and on phones that connect while it is open). Resolves with the first phone's answer,
	 * or undefined when `signal` aborts first (the terminal answered, or the dialog timed out); the phones are then told
	 * to close it.
	 */
	requestDialog(request: RemoteDialogRequest, signal?: AbortSignal): Promise<RpcExtensionUIResponse | undefined> {
		if (signal?.aborted) return Promise.resolve(undefined);
		const id = randomUUID();
		const full = { type: "extension_ui_request", id, ...request } as RpcExtensionUIRequest;
		return new Promise((resolve) => {
			const finish = (response: RpcExtensionUIResponse | undefined) => {
				if (!this.dialogs.delete(id)) return;
				signal?.removeEventListener("abort", onAbort);
				if (!response) this.broadcast({ type: "extension_ui_dismissed", id });
				resolve(response);
			};
			const onAbort = () => finish(undefined);
			signal?.addEventListener("abort", onAbort, { once: true });
			this.dialogs.set(id, { request: full, resolve: finish });
			this.broadcast(full);
		});
	}

	notify(message: string, notifyType?: "info" | "warning" | "error"): void {
		this.broadcast({ type: "extension_ui_request", id: randomUUID(), method: "notify", message, notifyType });
	}

	async handleClientMessage(connection: RemoteConnection, message: unknown): Promise<void> {
		// A revoked device (here, or by another Pi on this machine editing devices.json) runs nothing more.
		const device = connection.device;
		if (!connection.isOpen || !device || !this.store.findDevice(Buffer.from(device.publicKey, "base64url"))) {
			if (connection.device && !connection.revoked) connection.revoke();
			return;
		}
		if (typeof message !== "object" || message === null || typeof (message as { type?: unknown }).type !== "string") {
			return;
		}
		const typed = message as { type: string; id?: unknown };
		if (typed.type === "extension_ui_response") {
			const response = message as RpcExtensionUIResponse;
			const dialog = this.dialogs.get(response.id);
			if (!dialog) return;
			dialog.resolve(response);
			// The other phones close it too.
			for (const other of this.connections) {
				if (other !== connection && other.isOpen) other.send({ type: "extension_ui_dismissed", id: response.id });
			}
			return;
		}
		const id = typeof typed.id === "string" ? typed.id : undefined;
		if (!REMOTE_COMMANDS.has(typed.type as RpcCommand["type"])) {
			connection.send({
				id,
				type: "response",
				command: typed.type,
				success: false,
				error: "Not available over remote control",
			});
			return;
		}
		try {
			const response = await this.runCommand(message as RpcCommand, (reply) => connection.send(reply));
			if (response) connection.send(response);
		} catch (error) {
			connection.send({ id, type: "response", command: typed.type, success: false, error: asError(error) });
		}
	}

	private async runCommand(
		command: RpcCommand,
		reply: (response: RpcResponse) => void,
	): Promise<RpcResponse | undefined> {
		const session = this.options.getSession();
		const id = command.id;
		const ok = (data?: object | null): RpcResponse =>
			(data === undefined
				? { id, type: "response", command: command.type, success: true }
				: { id, type: "response", command: command.type, success: true, data }) as RpcResponse;
		const fail = (error: string): RpcResponse => ({
			id,
			type: "response",
			command: command.type,
			success: false,
			error,
		});
		switch (command.type) {
			case "prompt": {
				// As in RPC mode: the response follows the prompt's preflight; the run itself streams as events.
				let preflightSucceeded = false;
				void session
					.prompt(command.message, {
						images: command.images,
						streamingBehavior: command.streamingBehavior,
						source: "rpc",
						preflightResult: (disposition) => {
							preflightSucceeded = true;
							reply(ok({ disposition }));
						},
					})
					.catch((error: unknown) => {
						if (!preflightSucceeded) reply(fail(asError(error)));
					});
				return undefined;
			}
			case "steer":
				return ok({ disposition: await session.steer(command.message, command.images, { source: "rpc" }) });
			case "follow_up":
				return ok({ disposition: await session.followUp(command.message, command.images, { source: "rpc" }) });
			case "abort":
				await session.abort();
				return ok();
			case "clear_queue":
				return ok(session.clearQueue());
			case "get_state":
				return ok(this.sessionState(session));
			case "get_messages":
				return ok({ messages: session.messages });
			case "get_available_models":
				return ok({ models: session.modelRuntime.getAvailableSnapshot() });
			case "set_model": {
				const model = session.modelRuntime
					.getAvailableSnapshot()
					.find((candidate) => candidate.provider === command.provider && candidate.id === command.modelId);
				if (!model) return fail(`Model not found: ${command.provider}/${command.modelId}`);
				await session.setModel(model);
				return ok(model);
			}
			case "cycle_model":
				return ok((await session.cycleModel()) ?? null);
			case "get_available_thinking_levels":
				return ok({ levels: session.getAvailableThinkingLevels() });
			case "set_thinking_level":
				session.setThinkingLevel(command.level);
				return ok();
			case "cycle_thinking_level": {
				const level = session.cycleThinkingLevel();
				return ok(level ? { level } : null);
			}
			case "compact":
				return ok(await session.compact(command.customInstructions));
			case "abort_retry":
				session.abortRetry();
				return ok();
			case "get_session_stats":
				return ok(session.getSessionStats());
			case "get_commands":
				return ok({ commands: this.commands(session) });
			case "get_last_assistant_text":
				return ok({ text: session.getLastAssistantText() });
			case "set_session_name": {
				const name = command.name.trim();
				if (!name) return fail("Session name cannot be empty");
				session.setSessionName(name);
				return ok();
			}
			default:
				return fail("Not available over remote control");
		}
	}
}
