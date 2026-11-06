/**
 * `/remote-control` in the interactive mode: starts and stops the server and the tunnel, shows the pairing QR code,
 * keeps the footer status, and shows the terminal's dialogs on phones too (first answer wins).
 * Importing it does no work: the server, the tunnel and the QR code start only inside the command handler.
 */

import { hostname, networkInterfaces } from "node:os";
import type { AgentSession } from "../core/agent-session.ts";
import type { ExtensionUIDialogOptions } from "../core/extensions/index.ts";
import type { SettingsManager } from "../core/settings-manager.ts";
import type { RpcExtensionUIResponse } from "../modes/rpc/rpc-types.ts";
import { WEBSOCKET_PATH } from "./protocol.ts";
import { renderQrLines } from "./qr.ts";
import { type RemoteControlEvent, RemoteControlServer, type RemoteDialogRequest } from "./server.ts";
import { RemoteControlStore } from "./store.ts";
import { startTunnel, type Tunnel } from "./tunnel.ts";

/** What the interactive mode gives remote control. */
export interface RemoteControlHost {
	getSession(): AgentSession;
	settingsManager: SettingsManager;
	agentDir: string;
	version: string;
	showStatus(message: string): void;
	showWarning(message: string): void;
	showError(message: string): void;
	/** Adds a block of pre-rendered lines to the chat. */
	showLines(lines: string[]): void;
	/** Footer status; undefined clears it. */
	setStatus(text: string | undefined): void;
	select(title: string, options: string[]): Promise<string | undefined>;
	copyToClipboard(text: string): Promise<void>;
	/** Terminal size in cells. */
	columns(): number;
	rows(): number;
	/** Listen address; default all interfaces. */
	bindAddress?: string;
}

const USAGE = "Usage: /remote-control [tunnel|copy|stop|devices]";

function isPrivateIPv4(address: string): boolean {
	const [a, b] = address.split(".").map(Number);
	return a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
}

/** Tailscale and other CGNAT-range addresses (100.64.0.0/10). */
function isCgnatIPv4(address: string): boolean {
	const [a, b] = address.split(".").map(Number);
	return a === 100 && b >= 64 && b <= 127;
}

/** Local network endpoints: private LAN addresses first, then Tailscale, then any other non-link-local address. */
export function lanEndpoints(port: number, interfaces = networkInterfaces()): string[] {
	const addresses = Object.values(interfaces)
		.flatMap((entries) => entries ?? [])
		.filter((entry) => entry.family === "IPv4" && !entry.internal && !entry.address.startsWith("169.254."))
		.map((entry) => entry.address);
	const rank = (address: string) => (isPrivateIPv4(address) ? 0 : isCgnatIPv4(address) ? 1 : 2);
	return [...new Set(addresses)]
		.sort((left, right) => rank(left) - rank(right))
		.map((address) => `ws://${address}:${port}${WEBSOCKET_PATH}`);
}

/** "2026-11-06 12:36" in the local time zone. */
function localTime(iso: string): string {
	const date = new Date(iso);
	const pad = (value: number) => String(value).padStart(2, "0");
	return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export function hostDisplayName(): string {
	return hostname().replace(/\.local$/, "") || "computer";
}

export class RemoteControlController {
	private readonly host: RemoteControlHost;
	private server: RemoteControlServer | undefined;
	private tunnel: Tunnel | undefined;
	private readonly store: RemoteControlStore;
	private lastPairingUri: string | undefined;

	constructor(host: RemoteControlHost) {
		this.host = host;
		this.store = new RemoteControlStore(host.agentDir);
	}

	get running(): boolean {
		return this.server?.running ?? false;
	}

	/** The text of the last QR code shown. */
	get pairingUri(): string | undefined {
		return this.lastPairingUri;
	}

	async handleCommand(args: string): Promise<void> {
		const action = args.trim();
		try {
			if (action === "") await this.start(false);
			else if (action === "tunnel") await this.start(true);
			else if (action === "copy") await this.copyPairingLink();
			else if (action === "stop") await this.stop(true);
			else if (action === "devices") await this.manageDevices();
			else this.host.showWarning(USAGE);
		} catch (error) {
			this.host.showError(error instanceof Error ? error.message : String(error));
		}
	}

	private async start(withTunnel: boolean): Promise<void> {
		const settings = this.host.settingsManager.getRemoteControlSettings();
		if (!this.server) {
			this.server = new RemoteControlServer({
				store: this.store,
				getSession: () => this.host.getSession(),
				hostName: hostDisplayName(),
				version: this.host.version,
				port: settings.port ?? 0,
				bindAddress: this.host.bindAddress,
				onEvent: (event) => this.onServerEvent(event),
			});
		}
		if (!this.server.running) {
			try {
				await this.server.start();
			} catch (error) {
				this.server = undefined;
				const code = (error as NodeJS.ErrnoException).code;
				throw new Error(
					code === "EADDRINUSE"
						? `Remote control: port ${settings.port} is in use (setting remoteControl.port)`
						: `Remote control could not start: ${error instanceof Error ? error.message : String(error)}`,
				);
			}
		}
		if (withTunnel && !this.tunnel) {
			this.host.showStatus("Starting Cloudflare tunnel...");
			this.tunnel = await startTunnel(this.server.port, WEBSOCKET_PATH, settings, {
				onExit: (message) => {
					this.tunnel = undefined;
					this.host.showWarning(`Remote control tunnel stopped. ${message}`);
					this.updateStatus();
				},
			});
		}
		this.updateStatus();
		this.showPairingCode();
	}

	private showPairingCode(): void {
		const server = this.server;
		if (!server) return;
		const endpoints = [...lanEndpoints(server.port), ...(this.tunnel ? [this.tunnel.endpoint] : [])];
		if (endpoints.length === 0) {
			this.host.showWarning("No network address found. Connect to a network, or use /remote-control tunnel.");
			return;
		}
		this.lastPairingUri = server.pairingUri(endpoints);
		const { lines, width } = renderQrLines(this.lastPairingUri);
		const text: string[] = ["Remote control: scan with the Pi-Bolt app", ""];
		if (width + 2 > this.host.columns() || lines.length + 2 > this.host.rows()) {
			text.push(
				`The terminal is too small for the QR code (needs ${width + 2} columns and ${lines.length + 2} rows). Enlarge it and run /remote-control again, or use /remote-control copy.`,
			);
		} else {
			text.push(...lines);
		}
		text.push("");
		for (const endpoint of endpoints)
			text.push(`  ${endpoint.startsWith("wss:") ? "internet" : "local   "}  ${endpoint}`);
		text.push(
			"",
			"The code pairs one phone and expires in 10 minutes; paired phones reconnect without pairing again.",
			this.tunnel
				? "No camera? /remote-control copy copies the link. /remote-control stop ends remote control."
				: "Away from this network: /remote-control tunnel. No camera: /remote-control copy. End: /remote-control stop.",
		);
		this.host.showLines(text);
	}

	/** Copies the current pairing link, for "Paste pairing link" in the app (no camera, or an emulator). */
	private async copyPairingLink(): Promise<void> {
		if (!this.server?.running || !this.lastPairingUri) {
			this.host.showWarning("Remote control is not running. Start it with /remote-control first.");
			return;
		}
		await this.host.copyToClipboard(this.lastPairingUri);
		this.host.showStatus(
			"Copied the pairing link. It pairs one phone within 10 minutes: paste it only into the Pi-Bolt app.",
		);
	}

	async stop(announce = false): Promise<void> {
		this.tunnel?.stop();
		this.tunnel = undefined;
		const server = this.server;
		this.server = undefined;
		if (server) await server.stop();
		this.host.setStatus(undefined);
		if (announce) this.host.showStatus(server ? "Remote control stopped" : "Remote control is not running");
	}

	private async manageDevices(): Promise<void> {
		const devices = this.store.devices();
		if (devices.length === 0) {
			this.host.showStatus("No paired phones");
			return;
		}
		const connected = new Set(this.server?.clients().map((client) => client.device.publicKey) ?? []);
		const labels = devices.map((device) => {
			const seen = device.lastSeenAt ? `last seen ${localTime(device.lastSeenAt)}` : "";
			return `${device.name}${connected.has(device.publicKey) ? " (connected)" : ""}  ${seen}  [${device.publicKey.slice(0, 8)}]`;
		});
		const choice = await this.host.select("Paired phones: choose one to revoke", labels);
		if (choice === undefined) return;
		const device = devices[labels.indexOf(choice)];
		if (!device) return;
		if (this.server) this.server.revoke(device.publicKey);
		else this.store.removeDevice(device.publicKey);
		this.host.showStatus(`Revoked ${device.name}`);
		this.updateStatus();
	}

	/** Call after the terminal switched sessions. */
	sessionChanged(): void {
		this.server?.sessionChanged();
	}

	notify(message: string, type?: "info" | "warning" | "error"): void {
		this.server?.notify(message, type);
	}

	/**
	 * Runs a terminal dialog and, while remote control runs, the same dialog on the phones. The first answer wins; the
	 * other side is closed.
	 */
	async raceDialog<T>(
		request: RemoteDialogRequest,
		opts: ExtensionUIDialogOptions | undefined,
		parse: (response: RpcExtensionUIResponse) => T,
		local: (opts: ExtensionUIDialogOptions) => Promise<T>,
	): Promise<T> {
		const server = this.server;
		if (!server?.running) return local(opts ?? {});
		const abort = new AbortController();
		const onOuterAbort = () => abort.abort();
		opts?.signal?.addEventListener("abort", onOuterAbort, { once: true });
		if (opts?.signal?.aborted) abort.abort();
		try {
			const fromTerminal = local({ ...opts, signal: abort.signal });
			const fromPhone = server
				.requestDialog(request, abort.signal)
				.then((response) => (response ? parse(response) : new Promise<T>(() => {})));
			return await Promise.race([fromTerminal, fromPhone]);
		} finally {
			abort.abort();
			opts?.signal?.removeEventListener("abort", onOuterAbort);
		}
	}

	private onServerEvent(event: RemoteControlEvent): void {
		// Connections show in the footer only: chat status lines coalesce, so a notice here would overwrite the
		// previous one (an extension's, say). A new pairing is worth a line of its own.
		if (event.type === "client_connected" && event.paired) {
			this.host.showLines([`Remote control: paired ${event.client.device.name}`]);
		}
		this.updateStatus();
	}

	private updateStatus(): void {
		if (!this.server?.running) {
			this.host.setStatus(undefined);
			return;
		}
		const clients = this.server.clients();
		const where = this.tunnel ? "lan+tunnel" : "lan";
		const who =
			clients.length === 0
				? "waiting"
				: clients.length === 1
					? `${clients[0].device.name} connected`
					: `${clients.length} phones connected`;
		this.host.setStatus(`remote ${where}: ${who}`);
	}
}
