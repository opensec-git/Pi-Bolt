/**
 * The background service (`pi-bolt service run`): remote control with no terminal open. It is the server of
 * `/remote-control` (the same Noise handshake, pairing store and revocation) with sessions of its own, built by the same
 * runtime factory as every other mode, so settings, extensions, skills and project trust apply as usual.
 *
 * - Only paired phones get in; it never shows a pairing code itself. `pi-bolt service pair` asks it for one.
 * - It listens on the LAN (Tailscale included) on a fixed port, and runs the Cloudflare tunnel of
 *   `remoteControl.service.tunnel` (default: `remoteControl.tunnel`). Phones get the current addresses on every
 *   connection (`remote_endpoints`), so a quick tunnel's new address reaches them.
 * - A phone opens the most recent session when it connects; it can list projects and sessions, open one, or start one
 *   in a project used before (`list_projects`, `list_sessions`, `switch_session`, `new_session`).
 * - Several sessions stay open at once (at most [MAX_OPEN_SESSIONS]), each with its own runtime: phones see one, the
 *   current one. Opening another while the current one runs a turn keeps that turn running in the background; it is
 *   never aborted. `remote_hello.host.parallelSessions` tells phones so.
 * - A terminal's `/remote-control` takes over: the service gives it the port and the tunnel, and listens again when the
 *   terminal lets go (service-control.ts).
 * - While remote control is off (`remoteControl.enabled` in the global settings), it does not serve: started then, it logs
 *   why and exits 0. Turned off later, in any state (serving, waiting for its port or for a terminal, or with the port
 *   handed to a terminal), it stops within a poll and exits 0, and it never listens or starts a tunnel once it has seen
 *   the setting off. The service manager does not start it again after a clean exit (service-files.ts).
 */

import { existsSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { basename } from "node:path";
import { COMMAND_NAME, VERSION } from "../config.ts";
import type { AgentSessionRuntime, CreateAgentSessionRuntimeFactory } from "../core/agent-session-runtime.ts";
import { createAgentSessionRuntime } from "../core/agent-session-runtime.ts";
import type {
	ExtensionUIDialogOptions,
	ExtensionUIHost,
	ProjectTrustContext,
	SessionStartEvent,
} from "../core/extensions/index.ts";
import type { ViewChannel } from "../core/mobile-ui/host.ts";
import { type SessionInfo, SessionManager } from "../core/session-manager.ts";
import { SettingsManager } from "../core/settings-manager.ts";
import type { RemoteControlSettings } from "../core/settings-schema.ts";
import { REMOTE_CONTROL_OFF_MESSAGE } from "../core/slash-commands.ts";
import { clientUIContext } from "../extensions/client-ui.ts";
import type { RpcExtensionUIResponse } from "../modes/rpc/rpc-types.ts";
import { PIBOLT } from "../pi-bolt.ts";
import { pathsEqual } from "../utils/paths.ts";
import { killTrackedDetachedChildren } from "../utils/shell.ts";
import { hostDisplayName, lanEndpoints } from "./controller.ts";
import { CloseCode, decodePairingUri, WEBSOCKET_PATH } from "./protocol.ts";
import {
	type RemoteControlEvent,
	RemoteControlServer,
	type RemoteDialogRequest,
	type RemoteServiceCommands,
} from "./server.ts";
import {
	type ControlConnection,
	type ControlListener,
	type ControlPaths,
	type ControlRequest,
	connectControl,
	controlPaths,
	listenControl,
} from "./service-control.ts";
import { ServiceLog } from "./service-log.ts";
import { RemoteControlStore, toBase64Url } from "./store.ts";
import { startTunnel, type Tunnel, TunnelError } from "./tunnel.ts";

/** The service's port when `remoteControl.port` is 0 (random): a fixed one, so phones find the service again. */
export const DEFAULT_SERVICE_PORT = 48123;
/** A session with no phone connected for this long closes; the next phone opens it again. */
const IDLE_DISPOSE_MS = 15 * 60_000;
/** How often the service looks for new addresses (another network) and for phones revoked elsewhere. */
const POLL_MS = 10_000;
const DEFAULT_RETRY_MS = 5_000;
const MAX_TUNNEL_RETRY_MS = 10 * 60_000;
/** Projects and sessions a list answers with, newest first. */
const MAX_LISTED = 100;
/** Sessions open at once: the one phones see and the ones still running a turn (or idle, until closed). */
export const MAX_OPEN_SESSIONS = 3;

export type ServiceTunnelSetting = NonNullable<RemoteControlSettings["tunnel"]>;

/** `remoteControl.service.tunnel`, else `remoteControl.tunnel`, else a quick tunnel (the interactive default). */
export function serviceTunnelSetting(settings: RemoteControlSettings): ServiceTunnelSetting {
	return settings.service?.tunnel ?? settings.tunnel ?? "quick";
}

/** `remoteControl.port`, else [DEFAULT_SERVICE_PORT]. */
export function servicePort(settings: RemoteControlSettings): number {
	return settings.port || DEFAULT_SERVICE_PORT;
}

/** "quick", "off" or "named <hostname>". */
export function describeTunnel(tunnel: ServiceTunnelSetting): string {
	return typeof tunnel === "object" ? `named ${tunnel.hostname}` : tunnel;
}

/**
 * - starting: waiting for its port. serving: phones connect to it. yielded: a terminal's `/remote-control` has the port.
 * - waiting: a terminal held remote control when the service started; it serves once that terminal lets go.
 */
export type ServiceState = "starting" | "serving" | "yielded" | "waiting" | "stopped";

export interface ServiceTunnelStatus {
	state: "off" | "starting" | "up" | "missing" | "failed" | "stopped";
	endpoint?: string;
	error?: string;
}

/** What `status` answers (`pi-bolt service status` prints it). */
export interface ServiceStatus {
	role: "service";
	pid: number;
	version: string;
	state: ServiceState;
	hostName: string;
	/** Host static public key, base64url (for the reconnect QR code). */
	hostKey: string;
	port: number;
	endpoints: string[];
	tunnel: ServiceTunnelStatus;
	clients: Array<{ name: string; address: string; connectedAt: string }>;
	pairedDevices: number;
	session?: { cwd?: string; file?: string };
	/** Every open session; `current` is the one phones see. */
	sessions?: Array<{ cwd: string; file?: string; running: boolean; current: boolean }>;
}

/** A project for `list_projects`: a directory with sessions, newest first. */
export interface ServiceProject {
	cwd: string;
	name: string;
	sessions: number;
	updatedAt: string;
}

/** A session for `list_sessions`. `path` is what `switch_session` takes. */
export interface ServiceSession {
	path: string;
	id: string;
	cwd: string;
	name?: string;
	/** The first user message, shortened. */
	preview: string;
	updatedAt: string;
	messageCount: number;
	/** A runtime has the session open (omitted when not). */
	open?: true;
	/** Its open runtime runs a turn or a compaction (omitted when not). */
	running?: true;
}

export interface ServiceHostOptions {
	/** The runtime factory of main.ts. */
	createRuntime: CreateAgentSessionRuntimeFactory;
	agentDir: string;
	/** Where sessions are listed and new ones go; default each directory's sessionDir setting or Pi's default. */
	sessionDir?: string;
	settings: RemoteControlSettings;
	log: ServiceLog;
	version?: string;
	hostName?: string;
	/** Listen address; default all interfaces. */
	bindAddress?: string;
	offline?: boolean;
	/** Tests: the LAN addresses (default: this computer's), the tunnel starter, and shorter delays. */
	endpoints?: (port: number) => string[];
	startTunnel?: typeof startTunnel;
	retryMs?: number;
	pollMs?: number;
	idleDisposeMs?: number;
	idleCheckMs?: number;
	/** Sessions open at once; default [MAX_OPEN_SESSIONS]. */
	maxOpenSessions?: number;
	firstFrameTimeoutMs?: number;
	/**
	 * Asked at every poll in every state, and before the service listens or starts a tunnel: false once remote control was
	 * turned off. [onTurnedOff] then runs once, and the host does not listen or start a tunnel again.
	 */
	isEnabled?: () => boolean;
	onTurnedOff?: () => void;
}

const SERVICE_COMMANDS = new Set(["list_projects", "list_sessions", "new_session", "switch_session"]);

/** Thrown by [ServiceHost.start] when another background service already runs for this agent directory. */
export class ServiceAlreadyRunningError extends Error {}

export class ServiceHost {
	private readonly options: ServiceHostOptions;
	private readonly log: ServiceLog;
	private readonly store: RemoteControlStore;
	private readonly paths: ControlPaths;
	private readonly hostName: string;
	private readonly hostPublicKey: Buffer;
	private readonly retryMs: number;
	readonly port: number;
	readonly tunnelSetting: ServiceTunnelSetting;
	private stateValue: ServiceState = "stopped";
	private stopping = false;
	private control: ControlListener | undefined;
	/** The terminal's channel the service waits on while in "waiting". */
	private waitingOn: { close(): void } | undefined;
	private server: RemoteControlServer | undefined;
	private tunnel: Tunnel | undefined;
	private tunnelStatus: ServiceTunnelStatus;
	/** Bumped whenever the tunnel is closed: a start that finishes after that is dropped. */
	private tunnelGeneration = 0;
	private tunnelFailures = 0;
	private tunnelTimer: ReturnType<typeof setTimeout> | undefined;
	private pollTimer: ReturnType<typeof setInterval> | undefined;
	/** Asks [ServiceHostOptions.isEnabled] in every state, not only while serving. */
	private enabledTimer: ReturnType<typeof setInterval> | undefined;
	/** Remote control was seen turned off: [ServiceHostOptions.onTurnedOff] ran. */
	private turnedOff = false;
	private idleTimer: ReturnType<typeof setInterval> | undefined;
	private readonly sleeps = new Set<() => void>();
	/** The terminal that has the port. */
	private yieldedTo: ControlConnection | undefined;
	/** `pi-bolt service pair` connections waiting for a phone to pair. */
	private readonly pairWaiters = new Set<ControlConnection>();
	/** The session phones see. */
	private runtime: AgentSessionRuntime | undefined;
	/**
	 * The other open sessions, each with when it was last seen busy or shown (for closing idle ones, least recent first).
	 * A turn started on the phone keeps running here after the phone opened another session.
	 */
	private readonly background = new Map<AgentSessionRuntime, number>();
	private opening: Promise<void> | undefined;
	/** Session changes run one at a time: extensions load and trust is decided per session. */
	private changes: Promise<unknown> = Promise.resolve();
	private lastSessionFile: string | undefined;
	private lastActivity = Date.now();
	private refreshedModels = false;

	constructor(options: ServiceHostOptions) {
		this.options = options;
		this.log = options.log;
		this.store = new RemoteControlStore(options.agentDir);
		this.paths = controlPaths(options.agentDir);
		this.hostName = options.hostName ?? hostDisplayName();
		this.hostPublicKey = this.store.hostKeyPair().publicKey;
		this.retryMs = options.retryMs ?? DEFAULT_RETRY_MS;
		this.port = servicePort(options.settings);
		this.tunnelSetting = serviceTunnelSetting(options.settings);
		this.tunnelStatus = { state: this.tunnelSetting === "off" ? "off" : "stopped" };
	}

	get state(): ServiceState {
		return this.stateValue;
	}

	/** Takes the control channel (or waits for the terminal that has it), then listens. */
	async start(): Promise<void> {
		this.stopping = false;
		const idleCheckMs = this.options.idleCheckMs ?? 60_000;
		this.idleTimer = setInterval(() => void this.closeIdleSession(), idleCheckMs);
		this.idleTimer.unref?.();
		clearInterval(this.enabledTimer);
		this.enabledTimer = setInterval(() => this.stillEnabled(), this.options.pollMs ?? POLL_MS);
		this.enabledTimer.unref?.();
		this.setState("starting");
		while (!this.stopping) {
			if (!this.stillEnabled()) return;
			const listener = await listenControl(this.paths, (request, connection) =>
				this.onControl(request, connection),
			).catch((error: unknown) => {
				this.log.error(`Control channel: ${errorMessage(error)}`);
				return undefined;
			});
			if (listener) {
				this.control = listener;
				await this.serve();
				return;
			}
			const client = await connectControl(this.paths).catch(() => undefined);
			const status = await client?.request({ type: "status" });
			if (client && status?.role === "terminal") {
				this.setState("waiting");
				this.log.info(
					`A terminal's /remote-control (pid ${status.pid}) serves remote control; waiting until it stops`,
				);
				this.waitingOn = client;
				await client.closed;
				this.waitingOn = undefined;
				continue;
			}
			client?.close();
			if (status?.role === "service") {
				throw new ServiceAlreadyRunningError(`The background service already runs (pid ${status.pid})`);
			}
			await this.sleep(this.retryMs);
		}
	}

	async stop(): Promise<void> {
		this.stopping = true;
		this.setState("stopped");
		for (const wake of this.sleeps) wake();
		clearInterval(this.idleTimer);
		clearInterval(this.pollTimer);
		clearInterval(this.enabledTimer);
		this.closeTunnel();
		for (const waiter of this.pairWaiters) waiter.close();
		this.pairWaiters.clear();
		const server = this.server;
		this.server = undefined;
		await server?.stop();
		this.waitingOn?.close();
		this.yieldedTo?.close();
		this.control?.close();
		this.control = undefined;
		const runtimes = this.openRuntimes();
		this.runtime = undefined;
		this.background.clear();
		for (const runtime of runtimes) await runtime.dispose();
	}

	status(): ServiceStatus {
		const runtime = this.runtime;
		const file = runtime?.session.sessionFile ?? this.lastSessionFile;
		return {
			role: "service",
			pid: process.pid,
			version: this.options.version ?? VERSION,
			state: this.stateValue,
			hostName: this.hostName,
			hostKey: toBase64Url(this.hostPublicKey),
			port: this.port,
			endpoints: this.stateValue === "serving" ? this.currentEndpoints() : [],
			tunnel: { ...this.tunnelStatus },
			clients: (this.server?.clients() ?? []).map((client) => ({
				name: client.device.name,
				address: client.address,
				connectedAt: client.connectedAt.toISOString(),
			})),
			pairedDevices: this.store.devices().length,
			...(runtime || file
				? { session: { ...(runtime ? { cwd: runtime.cwd } : {}), ...(file ? { file } : {}) } }
				: {}),
			...(this.background.size > 0
				? {
						sessions: this.openRuntimes().map((open) => ({
							cwd: open.cwd,
							...(open.session.sessionFile ? { file: open.session.sessionFile } : {}),
							running: isBusy(open),
							current: open === runtime,
						})),
					}
				: {}),
		};
	}

	private setState(state: ServiceState): void {
		this.stateValue = state;
	}

	/** Whether remote control is still on; the first time it is not, [ServiceHostOptions.onTurnedOff] runs. */
	private stillEnabled(): boolean {
		if (this.turnedOff) return false;
		if (this.options.isEnabled?.() !== false) return true;
		this.turnedOff = true;
		clearInterval(this.enabledTimer);
		this.options.onTurnedOff?.();
		return false;
	}

	private sleep(ms: number): Promise<void> {
		return new Promise((resolve) => {
			const wake = () => {
				clearTimeout(timer);
				this.sleeps.delete(wake);
				resolve();
			};
			const timer = setTimeout(wake, ms);
			this.sleeps.add(wake);
		});
	}

	// Listening

	/** Listens on the port (waiting while something else has it), then starts the tunnel. */
	private async serve(): Promise<void> {
		if (this.stopping || this.server || !this.stillEnabled()) return;
		this.setState("starting");
		const server = new RemoteControlServer({
			store: this.store,
			getSession: () => this.runtime?.session,
			openSession: () => this.openSession(),
			serviceCommands: this.commands,
			hostInfo: { service: true, parallelSessions: true },
			readSecretFiles: () => this.options.settings.readSecretFiles === true,
			hostName: this.hostName,
			version: this.options.version ?? VERSION,
			port: this.port,
			bindAddress: this.options.bindAddress,
			firstFrameTimeoutMs: this.options.firstFrameTimeoutMs,
			onEvent: (event) => this.onServerEvent(event),
		});
		this.server = server;
		let warned = false;
		while (!this.stopping && this.server === server) {
			// (Asked before every attempt: turned off while the port was busy, it never listens.)
			if (!this.stillEnabled()) break;
			try {
				await server.start();
				break;
			} catch (error) {
				if ((error as NodeJS.ErrnoException).code !== "EADDRINUSE") {
					this.log.error(`Could not listen on port ${this.port}: ${errorMessage(error)}`);
				} else if (!warned) {
					this.log.warn(
						`Port ${this.port} is in use (setting remoteControl.port); trying again every ${this.retryMs / 1000} s`,
					);
				}
				warned = true;
				await this.sleep(this.retryMs);
			}
		}
		// Stopped, turned off, or a terminal took over while the port was busy.
		if (this.server !== server || this.stopping || this.turnedOff) {
			if (this.server === server) this.server = undefined;
			await server.stop();
			return;
		}
		this.setState("serving");
		this.log.info(`Listening on port ${server.port} (${this.store.devices().length} paired phones)`);
		this.updateEndpoints();
		clearInterval(this.pollTimer);
		this.pollTimer = setInterval(() => {
			this.updateEndpoints();
			// `/remote-control devices` in a terminal edits the store; a revoked phone that sends nothing is dropped here.
			this.server?.dropRevoked();
		}, this.options.pollMs ?? POLL_MS);
		this.pollTimer.unref?.();
		if (!this.tunnel) void this.openTunnel();
	}

	private currentEndpoints(): string[] {
		const server = this.server;
		if (!server?.running) return [];
		const lan = (this.options.endpoints ?? lanEndpoints)(server.port);
		return [...lan, ...(this.tunnel ? [this.tunnel.endpoint] : [])];
	}

	/** Phones get the addresses again when they changed (a new tunnel, another network). */
	private updateEndpoints(): void {
		this.server?.setEndpoints(this.currentEndpoints());
	}

	private onServerEvent(event: RemoteControlEvent): void {
		this.lastActivity = Date.now();
		if (event.type === "handshake_rejected") {
			this.log.warn(`Refused ${event.address}: ${event.reason}`);
			return;
		}
		const { device } = event.client;
		const who = `${device.name} [${device.publicKey.slice(0, 8)}] from ${event.client.address}`;
		if (event.type === "client_disconnected") {
			this.log.info(`Disconnected ${who}`);
			return;
		}
		this.log.info(`${event.paired ? "Paired" : "Connected"} ${who}`);
		if (!event.paired) return;
		for (const waiter of this.pairWaiters) {
			waiter.send({ type: "paired", device: device.name });
			waiter.close();
		}
		this.pairWaiters.clear();
	}

	// Tunnel

	private async openTunnel(): Promise<void> {
		clearTimeout(this.tunnelTimer);
		const server = this.server;
		if (this.tunnelSetting === "off" || !server?.running || !this.stillEnabled()) return;
		const generation = ++this.tunnelGeneration;
		this.tunnelStatus = { state: "starting" };
		const settings = { ...this.options.settings, tunnel: this.tunnelSetting };
		try {
			const tunnel: Tunnel = await (this.options.startTunnel ?? startTunnel)(server.port, WEBSOCKET_PATH, settings, {
				onExit: (message) => this.onTunnelExit(tunnel, message),
			});
			if (generation !== this.tunnelGeneration) {
				tunnel.stop();
				return;
			}
			this.tunnel = tunnel;
			this.tunnelFailures = 0;
			this.tunnelStatus = { state: "up", endpoint: tunnel.endpoint };
			this.log.info(`Tunnel up: ${tunnel.endpoint}`);
			this.updateEndpoints();
		} catch (error) {
			if (generation !== this.tunnelGeneration) return;
			if (error instanceof TunnelError && error.missing) {
				this.tunnelStatus = { state: "missing" };
				this.log.warn(
					"cloudflared was not found: serving the local network and Tailscale only. Install it, then run pi-bolt service install again.",
				);
				return;
			}
			const message = firstLine(errorMessage(error));
			this.tunnelFailures++;
			const wait = Math.min(this.retryMs * 6 * 2 ** (this.tunnelFailures - 1), MAX_TUNNEL_RETRY_MS);
			this.tunnelStatus = { state: "failed", error: message };
			this.log.warn(`Tunnel failed (${message}); trying again in ${Math.round(wait / 1000)} s`);
			this.tunnelTimer = setTimeout(() => void this.openTunnel(), wait);
		}
	}

	private onTunnelExit(tunnel: Tunnel, message: string): void {
		if (this.tunnel !== tunnel) return;
		this.tunnel = undefined;
		this.tunnelStatus = { state: "stopped", error: firstLine(message) };
		this.log.warn(`Tunnel stopped: ${firstLine(message)}`);
		if (this.stateValue === "yielded") {
			// The terminal uses it: it says so; the service starts a new one when it listens again.
			this.yieldedTo?.send({ type: "tunnel_down", message: firstLine(message) });
			return;
		}
		this.updateEndpoints();
		this.tunnelTimer = setTimeout(() => void this.openTunnel(), this.retryMs);
	}

	private closeTunnel(): void {
		this.tunnelGeneration++;
		clearTimeout(this.tunnelTimer);
		this.tunnel?.stop();
		this.tunnel = undefined;
		this.tunnelStatus = { state: this.tunnelSetting === "off" ? "off" : "stopped" };
	}

	// Control channel

	private onControl(request: ControlRequest, connection: ControlConnection): void {
		switch (request.type) {
			case "status":
				connection.send({ ok: true, ...this.status() });
				return;
			case "pair":
				this.pair(connection);
				return;
			case "yield":
				void this.yieldTo(connection, request.keepTunnel === true);
				return;
			case "tunnel_off":
				if (connection === this.yieldedTo && this.tunnel) {
					this.log.info("The terminal's /remote-control went local only; tunnel stopped");
					this.closeTunnel();
				}
				return;
			default:
				connection.send({ ok: false, error: "Unknown request" });
		}
	}

	/** A pairing code for `pi-bolt service pair`; the connection hears `paired` when a phone uses it. */
	private pair(connection: ControlConnection): void {
		const server = this.server;
		if (this.stateValue !== "serving" || !server) {
			connection.send({
				ok: false,
				error:
					this.stateValue === "yielded" || this.stateValue === "waiting"
						? "A terminal's /remote-control serves remote control now: pair there, or stop it first"
						: "The service is not listening yet; see pi-bolt service logs",
			});
			return;
		}
		const endpoints = this.currentEndpoints();
		if (endpoints.length === 0) {
			connection.send({ ok: false, error: "No network address found: connect to a network first" });
			return;
		}
		const uri = server.pairingUri(endpoints);
		this.pairWaiters.add(connection);
		void connection.closed.then(() => this.pairWaiters.delete(connection));
		this.log.info("Made a pairing code (pi-bolt service pair)");
		connection.send({ ok: true, uri, expiresAt: decodePairingUri(uri).x, endpoints });
	}

	/**
	 * A terminal's `/remote-control` takes over: phones are disconnected with 1012 (they reconnect at once and reach the
	 * terminal on the same port), and the tunnel stays up for the terminal when it asks. The service listens again when
	 * the terminal's connection closes.
	 */
	private async yieldTo(connection: ControlConnection, keepTunnel: boolean): Promise<void> {
		if (this.yieldedTo || (this.stateValue !== "serving" && this.stateValue !== "starting")) {
			connection.send({ ok: false, error: "Another terminal's /remote-control serves remote control" });
			return;
		}
		this.yieldedTo = connection;
		this.setState("yielded");
		const server = this.server;
		this.server = undefined;
		clearInterval(this.pollTimer);
		for (const waiter of this.pairWaiters) {
			waiter.send({ type: "pair_cancelled", error: "A terminal's /remote-control took over" });
			waiter.close();
		}
		this.pairWaiters.clear();
		await server?.stop(CloseCode.Handoff);
		// Only a tunnel that is up is handed over; one still starting would point a second tunnel at the terminal.
		if (!keepTunnel || !this.tunnel) this.closeTunnel();
		const tunnel = this.tunnel?.endpoint;
		this.log.info(`Handed port ${this.port}${tunnel ? " and the tunnel" : ""} to a terminal's /remote-control`);
		connection.send({ ok: true, port: this.port, ...(tunnel ? { tunnel } : {}) });
		void connection.closed.then(() => {
			if (this.yieldedTo !== connection) return;
			this.yieldedTo = undefined;
			if (this.stopping || !this.stillEnabled()) return;
			this.log.info("The terminal let go of remote control; listening again");
			void this.serve();
		});
	}

	// Sessions

	private readonly commands: RemoteServiceCommands = {
		types: SERVICE_COMMANDS,
		run: (command) => this.runCommand(command),
	};

	private async runCommand(command: { type: string; [key: string]: unknown }): Promise<object> {
		switch (command.type) {
			case "list_projects":
				return { projects: await this.listProjects() };
			case "list_sessions":
				return { sessions: await this.listSessions(optionalString(command.cwd, "cwd")) };
			case "new_session": {
				const cwd = optionalString(command.cwd, "cwd");
				const parent = optionalString(command.parentSession, "parentSession");
				return this.change(() => this.newSession(cwd, parent));
			}
			case "switch_session": {
				const path = optionalString(command.sessionPath, "sessionPath");
				if (path === undefined) throw new Error("sessionPath is required");
				return this.change(() => this.switchSession(path));
			}
			default:
				throw new Error(`Unknown command: ${command.type}`);
		}
	}

	private change<T>(run: () => Promise<T>): Promise<T> {
		const next = this.changes.then(run);
		this.changes = next.catch(() => {});
		return next;
	}

	/** Opens a session for the first phone: the last one the service had, else the most recent one on this computer. */
	private openSession(): Promise<void> {
		if (this.runtime) return Promise.resolve();
		this.opening ??= this.change(async () => {
			if (this.runtime) return;
			const last = this.lastSessionFile;
			const recent =
				last && existsSync(last) ? last : (await this.allSessions()).find((info) => isDirectory(info.cwd))?.path;
			const open = recent ? this.openRuntimeOf(recent) : undefined;
			if (open) {
				this.show(open);
				return;
			}
			if (recent) {
				await this.openRuntime(SessionManager.open(recent), { type: "session_start", reason: "resume" });
				return;
			}
			const home = homedir();
			await this.openRuntime(SessionManager.create(home, this.sessionDirFor(home)));
		}).finally(() => {
			this.opening = undefined;
		});
		return this.opening;
	}

	private async newSession(
		cwd: string | undefined,
		parentSession: string | undefined,
	): Promise<{ cancelled: boolean }> {
		const runtime = this.runtime;
		const target = cwd === undefined ? (runtime?.cwd ?? (await this.recentProject())) : await this.knownProject(cwd);
		// An idle session moves to the new one, as in a terminal; a busy one keeps running (openRuntime).
		if (runtime && !isBusy(runtime) && pathsEqual(runtime.cwd, target)) {
			return runtime.newSession(parentSession ? { parentSession } : undefined);
		}
		const sessionManager = SessionManager.create(target, this.sessionDirFor(target));
		if (parentSession) sessionManager.newSession({ parentSession });
		await this.openRuntime(sessionManager, {
			type: "session_start",
			reason: "new",
			previousSessionFile: runtime?.session.sessionFile,
		});
		return { cancelled: false };
	}

	private async switchSession(sessionPath: string): Promise<{ cancelled: boolean }> {
		const info = (await this.allSessions()).find((session) => pathsEqual(session.path, sessionPath));
		if (!info) throw new Error("No such session on this computer");
		if (!isDirectory(info.cwd)) throw new Error(`The session's folder no longer exists: ${info.cwd}`);
		// Open already (a turn running in the background, say): it is shown as it is.
		const open = this.openRuntimeOf(info.path);
		if (open) {
			this.show(open);
			return { cancelled: false };
		}
		const runtime = this.runtime;
		if (runtime && !isBusy(runtime)) {
			return runtime.switchSession(info.path, { projectTrustContextFactory: (cwd) => this.trustContext(cwd) });
		}
		await this.openRuntime(SessionManager.open(info.path), { type: "session_start", reason: "resume" });
		return { cancelled: false };
	}

	private openRuntimes(): AgentSessionRuntime[] {
		return [...(this.runtime ? [this.runtime] : []), ...this.background.keys()];
	}

	/** The open runtime that has `sessionFile`. */
	private openRuntimeOf(sessionFile: string): AgentSessionRuntime | undefined {
		return this.openRuntimes().find((open) => {
			const file = open.session.sessionFile;
			return file !== undefined && pathsEqual(file, sessionFile);
		});
	}

	/** Phones see `runtime` from now on; the one they saw stays open in the background. */
	private show(runtime: AgentSessionRuntime): void {
		const previous = this.runtime;
		if (previous === runtime) return;
		if (previous) this.background.set(previous, Date.now());
		this.background.delete(runtime);
		this.runtime = runtime;
		this.lastSessionFile = runtime.session.sessionFile;
		this.server?.sessionChanged();
	}

	/**
	 * Opens a session in a new runtime and shows it. The session shown so far is replaced when idle, and otherwise keeps
	 * running in the background: a turn is never aborted for this. At [maxOpenSessions] open sessions an idle background
	 * one closes first, the least recently used; when every one is busy, nothing opens. Failures leave the sessions as
	 * they were, except an idle replaced one, which is closed already.
	 */
	private async openRuntime(sessionManager: SessionManager, sessionStartEvent?: SessionStartEvent): Promise<void> {
		const previous = this.runtime;
		const replace = previous !== undefined && !isBusy(previous);
		const max = this.options.maxOpenSessions ?? MAX_OPEN_SESSIONS;
		if (this.openRuntimes().length - (replace ? 1 : 0) >= max) {
			const idle = [...this.background].filter(([open]) => !isBusy(open)).sort((a, b) => a[1] - b[1])[0]?.[0];
			if (!idle) throw new Error(`${max} sessions are running on this computer. Stop one, or wait.`);
			this.background.delete(idle);
			await idle.dispose();
			this.log.info(`Closed an idle session in ${idle.cwd} to open another`);
		}
		if (replace) {
			this.runtime = undefined;
			await previous.dispose();
		}
		const cwd = sessionManager.getCwd();
		const runtime = await createAgentSessionRuntime(this.options.createRuntime, {
			cwd,
			agentDir: this.options.agentDir,
			sessionManager,
			sessionStartEvent,
			projectTrustContext: this.trustContext(cwd),
		});
		for (const diagnostic of runtime.diagnostics) {
			const text = `${cwd}: ${diagnostic.message}`;
			if (diagnostic.type === "error") this.log.error(text);
			else if (diagnostic.type === "warning") this.log.warn(text);
		}
		const errors = runtime.diagnostics.filter((diagnostic) => diagnostic.type === "error");
		if (errors.length > 0) {
			await runtime.dispose();
			this.server?.sessionChanged();
			throw new Error(errors.map((error) => error.message).join("\n"));
		}
		if (this.runtime) this.background.set(this.runtime, Date.now());
		this.runtime = runtime;
		runtime.setRebindSession(() => this.bind(runtime));
		await this.bind(runtime);
		this.log.info(
			`Opened a session in ${cwd}${previous && !replace ? ` (the one in ${previous.cwd} keeps running)` : ""}`,
		);
		if (!this.refreshedModels && !this.options.offline) {
			this.refreshedModels = true;
			void runtime.services.modelRuntime.refresh({ signal: AbortSignal.timeout(15_000) }).catch(() => {});
		}
	}

	/** Binds the runtime's current session to the phones (also after an extension or a phone switched sessions). */
	private async bind(runtime: AgentSessionRuntime): Promise<void> {
		const session = runtime.session;
		await session.bindExtensions({
			uiContext: this.uiContext(),
			mode: "rpc",
			viewChannel: this.viewChannel(runtime),
			commandContextActions: {
				waitForIdle: () => session.waitForIdle(),
				newSession: (options) => runtime.newSession(options),
				fork: async (entryId, forkOptions) => ({
					cancelled: (await runtime.fork(entryId, forkOptions)).cancelled,
				}),
				navigateTree: async (targetId, options) => ({
					cancelled: (
						await session.navigateTree(targetId, {
							summarize: options?.summarize,
							customInstructions: options?.customInstructions,
							replaceInstructions: options?.replaceInstructions,
							label: options?.label,
						})
					).cancelled,
				}),
				switchSession: (sessionPath, options) => runtime.switchSession(sessionPath, options),
				reload: async () => {
					await session.reload();
				},
			},
			onError: (error) => this.log.warn(`Extension error (${error.extensionPath}): ${error.error}`),
		});
		// A background session that changed (an extension's command) is not what phones see.
		if (runtime !== this.runtime) return;
		this.lastSessionFile = session.sessionFile;
		this.server?.sessionChanged();
	}

	/**
	 * Closes sessions nobody uses: the shown one when no phone used it for a while (it reopens from its file when one
	 * connects), and a background one once idle that long. A busy session is never closed.
	 */
	private async closeIdleSession(): Promise<void> {
		if (this.opening) return;
		const idleMs = this.options.idleDisposeMs ?? IDLE_DISPOSE_MS;
		const now = Date.now();
		for (const [open, since] of [...this.background]) {
			if (isBusy(open)) {
				this.background.set(open, now);
				continue;
			}
			if (now - since < idleMs) continue;
			await this.change(async () => {
				if (!this.background.has(open) || isBusy(open)) return;
				this.background.delete(open);
				await open.dispose();
				this.log.info(`Closed an idle background session in ${open.cwd}`);
			});
		}
		const runtime = this.runtime;
		if (!runtime) return;
		if ((this.server?.clients().length ?? 0) > 0 || isBusy(runtime)) {
			this.lastActivity = Date.now();
			return;
		}
		if (Date.now() - this.lastActivity < idleMs) return;
		await this.change(async () => {
			if (this.runtime !== runtime) return;
			this.runtime = undefined;
			await runtime.dispose();
			this.server?.sessionChanged();
			this.log.info("Closed the idle session; it opens again when a phone connects");
		});
	}

	/**
	 * The mobile UI kit's views go to the phones that render them, for the session phones see; a background session's
	 * views wait for its `remote_hello`, and its sheets become dialogs, which reach the phones.
	 */
	private viewChannel(runtime: AgentSessionRuntime): ViewChannel {
		const shown = () => runtime === this.runtime && this.server?.running === true;
		return {
			hasNative: () => shown() && this.server?.hasViewClients() === true,
			keepText: false,
			send: (request) => {
				if (shown()) this.server?.sendView(request);
			},
			sheetChanged: (viewKey, view, rev) => this.server?.updateViewSheet(viewKey, view, rev),
			sheet: (request, signal) =>
				this.server
					? this.server.requestViewSheet(request, signal)
					: new Promise((resolve) => signal.addEventListener("abort", () => resolve(undefined), { once: true })),
		};
	}

	/** Dialogs of extensions go to the phones (and to phones that connect while one is open). */
	private uiContext(): ExtensionUIHost {
		const value = (response: RpcExtensionUIResponse) => ("value" in response ? response.value : undefined);
		return clientUIContext({
			select: (title, options, opts) =>
				this.dialog({ method: "select", title, options, timeout: opts?.timeout }, opts, value),
			confirm: async (title, message, opts) =>
				(await this.dialog(
					{ method: "confirm", title, message, timeout: opts?.timeout },
					opts,
					(response) => "confirmed" in response && response.confirmed,
				)) ?? false,
			input: (title, placeholder, opts) =>
				this.dialog({ method: "input", title, placeholder, timeout: opts?.timeout }, opts, value),
			editor: (title, prefill) => this.dialog({ method: "editor", title, prefill }, undefined, value),
			notify: (message, type) => this.server?.notify(message, type),
		});
	}

	private async dialog<T>(
		request: RemoteDialogRequest,
		opts: ExtensionUIDialogOptions | undefined,
		parse: (response: RpcExtensionUIResponse) => T,
	): Promise<T | undefined> {
		const server = this.server;
		if (!server?.running) return undefined;
		const abort = new AbortController();
		const onAbort = () => abort.abort();
		opts?.signal?.addEventListener("abort", onAbort, { once: true });
		if (opts?.signal?.aborted) abort.abort();
		const timer = opts?.timeout ? setTimeout(onAbort, opts.timeout) : undefined;
		try {
			const response = await server.requestDialog(request, abort.signal);
			return response && !("cancelled" in response && response.cancelled) ? parse(response) : undefined;
		} finally {
			clearTimeout(timer);
			opts?.signal?.removeEventListener("abort", onAbort);
			abort.abort();
		}
	}

	/** Trust prompts go to the phones while one is connected; otherwise an undecided project stays untrusted. */
	private trustContext(cwd: string): ProjectTrustContext {
		const ui = this.uiContext();
		return {
			cwd,
			mode: "rpc",
			hasUI: (this.server?.clients().length ?? 0) > 0,
			ui: { select: ui.select, confirm: ui.confirm, input: ui.input, notify: ui.notify },
		};
	}

	private sessionDirFor(cwd: string): string | undefined {
		return (
			this.options.sessionDir ??
			SettingsManager.create(cwd, this.options.agentDir, { projectTrusted: false }).getSessionDir()
		);
	}

	/** Every session with a directory, newest first. */
	private async allSessions(): Promise<SessionInfo[]> {
		// (Sessions of old Pi versions have no directory.)
		return (await SessionManager.listAll(this.options.sessionDir)).filter((info) => info.cwd !== "");
	}

	private async listProjects(): Promise<ServiceProject[]> {
		const projects = new Map<string, ServiceProject>();
		const missing = new Set<string>();
		for (const info of await this.allSessions()) {
			const known = projects.get(info.cwd);
			if (known) {
				known.sessions++;
				continue;
			}
			if (missing.has(info.cwd)) continue;
			if (!isDirectory(info.cwd)) {
				missing.add(info.cwd);
				continue;
			}
			projects.set(info.cwd, {
				cwd: info.cwd,
				name: basename(info.cwd) || info.cwd,
				sessions: 1,
				updatedAt: info.modified.toISOString(),
			});
		}
		return [...projects.values()].slice(0, MAX_LISTED);
	}

	private async listSessions(cwd: string | undefined): Promise<ServiceSession[]> {
		const target = cwd ?? this.runtime?.cwd;
		return (await this.allSessions())
			.filter((info) => target === undefined || pathsEqual(info.cwd, target))
			.slice(0, MAX_LISTED)
			.map((info) => {
				const open = this.openRuntimeOf(info.path);
				return {
					path: info.path,
					id: info.id,
					cwd: info.cwd,
					...(info.name ? { name: info.name } : {}),
					preview: info.firstMessage.replace(/\s+/g, " ").trim().slice(0, 100),
					updatedAt: info.modified.toISOString(),
					messageCount: info.messageCount,
					...(open ? { open: true as const } : {}),
					...(open && isBusy(open) ? { running: true as const } : {}),
				};
			});
	}

	/** `cwd` when Pi-Bolt has sessions there: a phone starts sessions only in projects used before. */
	private async knownProject(cwd: string): Promise<string> {
		const project = (await this.listProjects()).find((candidate) => pathsEqual(candidate.cwd, cwd));
		if (!project) throw new Error(`${cwd} is not a project Pi-Bolt has worked in on this computer`);
		return project.cwd;
	}

	private async recentProject(): Promise<string> {
		return (await this.listProjects())[0]?.cwd ?? homedir();
	}
}

/** A turn or a compaction runs: the session is never closed or replaced then. */
function isBusy(runtime: AgentSessionRuntime): boolean {
	return runtime.session.isStreaming || runtime.session.isCompacting;
}

function isDirectory(path: string): boolean {
	try {
		return path !== "" && statSync(path).isDirectory();
	} catch {
		return false;
	}
}

function optionalString(value: unknown, name: string): string | undefined {
	if (value === undefined || value === null) return undefined;
	if (typeof value !== "string") throw new Error(`${name} must be a string`);
	return value;
}

function errorMessage(error: unknown): string {
	return error instanceof Error ? error.message : String(error);
}

function firstLine(text: string): string {
	return text.split("\n")[0]?.trim() ?? "";
}

export interface RunServiceHostOptions {
	createRuntime: CreateAgentSessionRuntimeFactory;
	agentDir: string;
	/** `--session-dir` or the environment; otherwise the global sessionDir setting. */
	sessionDir?: string;
	offline?: boolean;
}

/** `pi-bolt service run`: serves until the service manager stops it (SIGTERM), then exits 0. */
export async function runServiceHost(options: RunServiceHostOptions): Promise<never> {
	const log = new ServiceLog(options.agentDir);
	const settingsManager = SettingsManager.create(homedir(), options.agentDir, { projectTrusted: false });
	const version = PIBOLT?.version ?? VERSION;
	// Off: no listener, no tunnel, no session. Exit 0, so the service manager does not start it again until the next login.
	const offMessage = `${REMOTE_CONTROL_OFF_MESSAGE}, then run ${COMMAND_NAME} service install again`;
	if (!settingsManager.getRemoteControlEnabled()) {
		log.info(`Pi-Bolt ${version} remote control service (pid ${process.pid}) does not serve: ${offMessage}`);
		process.exit(0);
	}
	const settings = settingsManager.getRemoteControlSettings();
	const host = new ServiceHost({
		createRuntime: options.createRuntime,
		agentDir: options.agentDir,
		sessionDir: options.sessionDir ?? settingsManager.getSessionDir(),
		settings,
		log,
		version,
		offline: options.offline,
		isEnabled: () =>
			SettingsManager.create(homedir(), options.agentDir, { projectTrusted: false }).getRemoteControlEnabled(),
		onTurnedOff: () => {
			log.info(`Turned off, so it stops serving: ${offMessage}`);
			void shutdown(0);
		},
	});
	let stopping = false;
	const shutdown = async (code: number) => {
		if (stopping) return;
		stopping = true;
		log.info("Stopping");
		await host.stop().catch(() => {});
		process.exit(code);
	};
	const signals: NodeJS.Signals[] =
		process.platform === "win32" ? ["SIGTERM", "SIGINT"] : ["SIGTERM", "SIGINT", "SIGHUP"];
	for (const signal of signals) {
		process.on(signal, () => {
			killTrackedDetachedChildren();
			void shutdown(0);
		});
	}
	// A crash exits non-zero, so the service manager starts the service again.
	process.on("uncaughtException", (error) => {
		log.error(`Crashed: ${error.stack ?? error.message}`);
		process.exit(1);
	});
	process.on("unhandledRejection", (reason) => {
		log.error(`Crashed: ${reason instanceof Error ? (reason.stack ?? reason.message) : String(reason)}`);
		process.exit(1);
	});
	log.info(
		`Starting Pi-Bolt ${version} remote control service (pid ${process.pid}): port ${host.port}, tunnel ${describeTunnel(host.tunnelSetting)}`,
	);
	try {
		await host.start();
	} catch (error) {
		// Exit 0: the service manager does not start a second copy again and again.
		if (error instanceof ServiceAlreadyRunningError) {
			log.info(`${error.message}; this copy exits`);
			process.exit(0);
		}
		log.error(`Could not start: ${errorMessage(error)}`);
		process.exit(1);
	}
	return new Promise<never>(() => {});
}
