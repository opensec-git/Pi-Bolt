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
import { SUBAGENT_STREAM_FEATURE, SubagentStreams, type SubagentStreamsOptions } from "../core/subagent-stream.ts";
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
	ENDPOINTS_MESSAGE,
	encodePairingUri,
	encryptMessage,
	MessageAssembler,
	NOISE_PROLOGUE,
	PROTOCOL_VERSION,
	WEBSOCKET_PATH,
	WEBSOCKET_SUBPROTOCOL,
} from "./protocol.ts";
import { REVIEW_COMMANDS, runReviewCommand } from "./review.ts";
import { type PairedDevice, type RemoteControlStore, toBase64Url } from "./store.ts";

/**
 * Time from the upgrade to the client's first frame (handshake message 1, which a client sends as soon as the socket
 * opens), and again from handshake message 2 to the client's `remote_ready`. An idle socket is closed and counts as a
 * failed handshake for its address.
 */
const FIRST_FRAME_TIMEOUT_MS = 3_000;
const PING_INTERVAL_MS = 20_000;
const FAILURE_WINDOW_MS = 10 * 60_000;
const MAX_FAILURES_PER_WINDOW = 10;
const DEFAULT_PAIRING_TTL_MS = 10 * 60_000;
const MAX_DEVICE_NAME_LENGTH = 80;
/**
 * `remote_hello` stays under this many bytes of JSON, well inside the 32 MiB message limit clients enforce: a longer
 * session sends only its newest messages (`omittedMessages` counts the rest).
 */
const HELLO_BUDGET_BYTES = 24 * 1024 * 1024;
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

/** Commands only the background service runs (`list_projects`, `new_session`, ...), past the RPC commands above. */
export interface RemoteServiceCommands {
	readonly types: ReadonlySet<string>;
	/** Resolves with the response's data; a throw is a failed response with its message. */
	run(command: { type: string; [key: string]: unknown }): Promise<object>;
}

export interface RemoteControlServerOptions {
	store: RemoteControlStore;
	/** The session phones see; undefined while the background service has none open. */
	getSession: () => AgentSession | undefined;
	/**
	 * Opens a session when a phone connects and there is none (the background service). It calls [sessionChanged] once
	 * the session is open, which sends `remote_hello`.
	 */
	openSession?: () => Promise<void>;
	serviceCommands?: RemoteServiceCommands;
	/**
	 * `remoteControl.readSecretFiles`: `rc_read_file` and `rc_git_diff` may show files that look like secrets (review.ts).
	 * Read on every request, so a settings change applies at once.
	 */
	readSecretFiles?: () => boolean;
	/** More fields of `remote_hello.host` (the service adds `service: true`). */
	hostInfo?: Record<string, unknown>;
	hostName: string;
	version: string;
	/** 0 (default) picks a free port. */
	port?: number;
	/** Bind address; default all interfaces. */
	bindAddress?: string;
	pairingTtlMs?: number;
	/** Size cap of `remote_hello` in bytes of JSON; default 24 MiB (tests shrink it). */
	helloBudgetBytes?: number;
	/** Deadline for a client's first frame and for its `remote_ready`; default 3 s (tests shorten it). */
	firstFrameTimeoutMs?: number;
	/** Limits and timings of `subagent_subscribe` streams (tests shrink them). */
	subagentStreams?: Pick<SubagentStreamsOptions, "limits" | "pollMs" | "eventBatchMs">;
	onEvent?: (event: RemoteControlEvent) => void;
}

interface OpenDialog {
	request: RpcExtensionUIRequest;
	resolve: (response: RpcExtensionUIResponse | undefined) => void;
}

export interface Pairing {
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
	private handshakeTimer: ReturnType<typeof setTimeout>;
	private alive = true;
	/** Set once the client proved it holds the session keys (`remote_ready`). */
	device: PairedDevice | undefined;
	/**
	 * The client after handshake message 1, until it confirms. `pairing` is the secret it offered; it is spent only
	 * when this client confirms.
	 */
	private candidate: { publicKey: Buffer; name: string; pairing: Pairing | undefined } | undefined;
	readonly socket: WebSocket;
	readonly address: string;
	private readonly server: RemoteControlServer;
	/** Subagent runs this phone follows (`subagent_subscribe`), made on the first. */
	private subagentStreams: SubagentStreams | undefined;

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

	/** Confirmed and still connected: only such connections get messages and run commands. */
	get isOpen(): boolean {
		return (
			!this.dropped &&
			this.device !== undefined &&
			this.keys !== undefined &&
			this.socket.readyState === this.socket.OPEN
		);
	}

	/** The client's static key (base64url) once handshake message 1 was accepted. */
	get publicKey(): string | undefined {
		return this.device?.publicKey ?? (this.candidate ? toBase64Url(this.candidate.publicKey) : undefined);
	}

	/** Set by [drop]: nothing more from this connection runs, even before its socket is gone. */
	dropped = false;

	/**
	 * Stops handling its messages now, closes with `code` (4403: the device was revoked; [CloseCode.Replaced]: the same
	 * device connected again), and drops the socket after a second if the peer does not finish the close handshake,
	 * as a socket on a network the phone left never does.
	 */
	drop(code: number, reason?: string): void {
		this.dropped = true;
		this.close(code, reason);
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

	close(code: number, reason?: string): void {
		clearTimeout(this.handshakeTimer);
		this.socket.close(code, reason);
	}

	streams(): SubagentStreams {
		this.subagentStreams ??= this.server.newSubagentStreams((message) => this.send(message));
		return this.subagentStreams;
	}

	/** Stops following subagents: the phone left, or the session it showed was replaced. */
	stopStreams(): void {
		this.subagentStreams?.clear();
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
			if (!this.device) this.server.recordFailure(this.address, "bad key confirmation");
			this.close(CloseCode.BadMessage);
			return;
		}
		if (!result.complete) return;
		if (this.device) void this.server.handleClientMessage(this, result.message);
		else this.confirm(result.message);
	}

	/**
	 * The first transport message must be `remote_ready`. Handshake message 1 can be replayed by anyone who recorded it
	 * (IK authenticates the initiator only once it uses the session keys), so the client counts as connected, gets
	 * `remote_hello` and runs commands only after this proof.
	 */
	private confirm(message: unknown): void {
		if (this.dropped) return;
		const candidate = this.candidate;
		if (
			!candidate ||
			typeof message !== "object" ||
			message === null ||
			(message as { type?: unknown }).type !== "remote_ready"
		) {
			this.server.recordFailure(this.address, "bad key confirmation");
			this.close(CloseCode.BadMessage);
			return;
		}
		// Spends the secret and stores the device in one step. It fails when another phone paired with the secret first
		// or a new QR code replaced it; this same device winning on another connection still gets in, as known.
		const paired = candidate.pairing
			? this.server.pairDevice(candidate.pairing, candidate.publicKey, candidate.name)
			: undefined;
		const device = paired ?? this.server.store.findDevice(candidate.publicKey);
		// Revoked while confirming, or the secret is gone.
		if (!device) {
			if (candidate.pairing) this.server.recordFailure(this.address, "pairing secret already used");
			this.close(CloseCode.NotAuthorized);
			return;
		}
		clearTimeout(this.handshakeTimer);
		this.device = paired ?? this.server.store.upsertDevice(candidate.publicKey, candidate.name);
		this.candidate = undefined;
		void this.server.connectionOpened(this, paired !== undefined);
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
		const pairing = authorization === "known" ? undefined : authorization;
		this.candidate = { publicKey: remoteStaticKey, name, pairing };
		const reply = Buffer.from(
			JSON.stringify({
				host: this.server.hostName,
				version: this.server.version,
				paired: pairing !== undefined,
			}),
			"utf8",
		);
		const { message, keys } = responder.writeMessage2(reply);
		clearTimeout(this.handshakeTimer);
		this.handshakeTimer = setTimeout(() => {
			this.server.recordFailure(this.address, "no key confirmation");
			this.close(CloseCode.HandshakeTimeout);
		}, this.server.firstFrameTimeoutMs);
		this.socket.send(message, { binary: true });
		this.keys = keys;
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
	private endpointList: string[] = [];

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

	/** Disconnects every phone with `closeCode` (1001: remote control stops) and closes the listener. */
	async stop(closeCode: number = CloseCode.GoingAway): Promise<void> {
		this.unsubscribe?.();
		this.unsubscribe = undefined;
		if (this.pingTimer) clearInterval(this.pingTimer);
		for (const dialog of this.dialogs.values()) dialog.resolve(undefined);
		this.dialogs.clear();
		for (const connection of this.connections) {
			connection.stopStreams();
			connection.close(closeCode);
		}
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

	/**
	 * At handshake message 1: "known" for a paired device, the pairing whose secret it offered, undefined to refuse.
	 * Checking the secret does not spend it: a client that times out before `remote_ready` (a slow network, a permission
	 * prompt) can retry with the same QR code. [pairDevice] spends it.
	 */
	authorize(publicKey: Buffer, secret: string | undefined): "known" | Pairing | undefined {
		if (this.store.findDevice(publicKey)) return "known";
		const pairing = this.pairing;
		if (!secret || !pairing || pairing.used || pairing.expiresAt <= Date.now()) return undefined;
		const offered = Buffer.from(secret, "base64url");
		if (offered.length !== pairing.secret.length || !timingSafeEqual(offered, pairing.secret)) return undefined;
		return pairing;
	}

	/**
	 * At `remote_ready`: stores the device and spends `pairing`, unless another client spent it first, a new QR code
	 * replaced it, or it expired. Synchronous, so two clients confirming with one secret cannot both pair.
	 */
	pairDevice(pairing: Pairing, publicKey: Buffer, name: string): PairedDevice | undefined {
		if (pairing !== this.pairing || pairing.used || pairing.expiresAt <= Date.now()) return undefined;
		const device = this.store.upsertDevice(publicKey, name);
		pairing.used = true;
		return device;
	}

	/** Disconnects and forgets a device. */
	revoke(publicKey: string): boolean {
		const removed = this.store.removeDevice(publicKey);
		for (const connection of this.connections) {
			if (connection.publicKey === publicKey) connection.drop(CloseCode.NotAuthorized);
		}
		return removed;
	}

	/** Disconnects phones no longer in the pairing store (revoked by another process, such as a terminal's Pi). */
	dropRevoked(): void {
		for (const connection of this.connections) {
			const key = connection.device?.publicKey;
			if (key && !connection.dropped && !this.store.findDevice(Buffer.from(key, "base64url"))) {
				connection.drop(CloseCode.NotAuthorized);
			}
		}
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

	async connectionOpened(connection: RemoteConnection, paired: boolean): Promise<void> {
		// A phone that changed networks (Wi-Fi off, now through the tunnel) left its old socket behind: nothing closed it,
		// so it would stay listed and get every event until the keepalive noticed. The phone only uses its newest
		// connection, and this one proved the device's key.
		const key = connection.device?.publicKey;
		for (const other of this.connections) {
			if (other !== connection && !other.dropped && other.device?.publicKey === key) {
				other.drop(CloseCode.Replaced, "Replaced by a newer connection from this device");
			}
		}
		const session = this.options.getSession();
		if (session) connection.send(this.hello(session));
		const client = connection.info();
		if (client) this.options.onEvent?.({ type: "client_connected", client, paired });
		if (!session && this.options.openSession) {
			try {
				await this.options.openSession();
			} catch (error) {
				connection.send({
					type: "extension_ui_request",
					id: randomUUID(),
					method: "notify",
					message: `No session could be opened: ${asError(error)}`,
					notifyType: "error",
				});
			}
		}
		if (this.endpointList.length > 0) connection.send(this.endpointsMessage());
	}

	/**
	 * The addresses phones should use from now on, in preference order. Connected phones get them now when they changed,
	 * and every phone gets them when it connects (`remote_endpoints`).
	 */
	setEndpoints(endpoints: string[]): void {
		if (endpoints.length === this.endpointList.length && endpoints.every((e, i) => e === this.endpointList[i]))
			return;
		this.endpointList = [...endpoints];
		if (endpoints.length > 0) this.broadcast(this.endpointsMessage());
	}

	private endpointsMessage(): object {
		return { type: ENDPOINTS_MESSAGE, endpoints: this.endpointList };
	}

	connectionClosed(connection: RemoteConnection): void {
		connection.stopStreams();
		const wasOpen = this.connections.delete(connection) && connection.device !== undefined;
		const client = connection.info();
		if (wasOpen && client) this.options.onEvent?.({ type: "client_disconnected", client });
	}

	private broadcast(message: unknown): void {
		for (const connection of this.connections) if (connection.isOpen) connection.send(message);
	}

	private subscribe(): void {
		this.unsubscribe?.();
		this.unsubscribe = this.options.getSession()?.subscribe((event) => this.broadcast(toJsonEvent(event)));
	}

	/** Call after the terminal switched to another session: phones get the new session's snapshot. */
	sessionChanged(): void {
		if (!this.running) return;
		// The phones get the new session and subscribe again to what they show of it.
		for (const connection of this.connections) connection.stopStreams();
		this.subscribe();
		const session = this.options.getSession();
		if (!session) return;
		for (const connection of this.connections) if (connection.isOpen) connection.send(this.hello(session));
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

	private hello(session: AgentSession): object {
		const streamingMessage = session.isStreaming ? session.agent.state.streamingMessage : undefined;
		const hello = {
			type: "remote_hello",
			protocol: PROTOCOL_VERSION,
			host: {
				name: this.hostName,
				version: this.version,
				cwd: session.sessionManager.getCwd(),
				// The read-only review commands (`rc_git_*`, `rc_list_dir`, `rc_read_file`): older hosts send no `review`.
				review: true,
				...this.options.hostInfo,
			},
			state: this.sessionState(session),
			messages: [] as unknown[],
			...(streamingMessage ? { streamingMessage } : {}),
			dialogs: [...this.dialogs.values()].map((dialog) => dialog.request),
			commands: this.commands(session),
			omittedMessages: 0,
			features: [SUBAGENT_STREAM_FEATURE],
		};
		// Newest messages first, while they fit; a client would refuse the whole snapshot otherwise.
		let budget = (this.options.helloBudgetBytes ?? HELLO_BUDGET_BYTES) - Buffer.byteLength(JSON.stringify(hello));
		const messages = session.messages;
		let first = messages.length;
		while (first > 0) {
			const size = Buffer.byteLength(JSON.stringify(messages[first - 1])) + 1;
			if (size > budget) break;
			budget -= size;
			first--;
		}
		hello.messages = first === 0 ? messages : messages.slice(first);
		hello.omittedMessages = first;
		return hello;
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

	/** Streams of one phone's subagent views, reading the session phones see now. */
	newSubagentStreams(send: (message: object) => void): SubagentStreams {
		return new SubagentStreams({
			getSession: () => this.options.getSession(),
			send,
			...this.options.subagentStreams,
		});
	}

	notify(message: string, notifyType?: "info" | "warning" | "error"): void {
		this.broadcast({ type: "extension_ui_request", id: randomUUID(), method: "notify", message, notifyType });
	}

	async handleClientMessage(connection: RemoteConnection, message: unknown): Promise<void> {
		// A revoked device (here, or by another Pi on this machine editing devices.json) runs nothing more.
		const device = connection.device;
		if (!connection.isOpen || !device || !this.store.findDevice(Buffer.from(device.publicKey, "base64url"))) {
			if (connection.device && !connection.dropped) connection.drop(CloseCode.NotAuthorized);
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
		if (typed.type === "subagent_subscribe" || typed.type === "subagent_unsubscribe") {
			const runId = (message as { runId?: unknown }).runId;
			try {
				if (typed.type === "subagent_subscribe") connection.streams().subscribe(runId);
				else connection.streams().unsubscribe(runId);
				connection.send({ id, type: "response", command: typed.type, success: true });
			} catch (error) {
				connection.send({ id, type: "response", command: typed.type, success: false, error: asError(error) });
			}
			return;
		}
		const service = this.options.serviceCommands;
		if (service?.types.has(typed.type)) {
			try {
				const data = await service.run(message as { type: string });
				connection.send({ id, type: "response", command: typed.type, success: true, data });
			} catch (error) {
				connection.send({ id, type: "response", command: typed.type, success: false, error: asError(error) });
			}
			return;
		}
		if (REVIEW_COMMANDS.has(typed.type)) {
			const session = this.options.getSession();
			try {
				if (!session) throw new Error("No session is open yet");
				const data = await runReviewCommand(session.sessionManager.getCwd(), message as { type: string }, {
					readSecretFiles: this.options.readSecretFiles?.() === true,
				});
				connection.send({ id, type: "response", command: typed.type, success: true, data });
			} catch (error) {
				connection.send({ id, type: "response", command: typed.type, success: false, error: asError(error) });
			}
			return;
		}
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
		if (!session) {
			return { id, type: "response", command: command.type, success: false, error: "No session is open yet" };
		}
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
