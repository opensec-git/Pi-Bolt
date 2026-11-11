/**
 * The long-distance path: a Cloudflare tunnel (cloudflared) from Cloudflare's edge to the local listener. A quick tunnel
 * needs no account and gets a random `*.trycloudflare.com` hostname; a named tunnel uses the user's own hostname.
 * Traffic stays end-to-end encrypted with Noise, so Cloudflare only relays ciphertext.
 */

import { type ChildProcess, spawn } from "node:child_process";
import type { RemoteControlSettings } from "../core/settings-schema.ts";

const START_TIMEOUT_MS = 45_000;
const QUICK_TUNNEL_URL = /https:\/\/[a-z0-9-]+\.trycloudflare\.com/;
const CONNECTED = /Registered tunnel connection/;

export interface Tunnel {
	/** WebSocket endpoint for the QR code, e.g. wss://quiet-fox.trycloudflare.com/rc */
	endpoint: string;
	stop(): void;
}

export class TunnelError extends Error {
	/** cloudflared is not installed (or not at the configured path). */
	readonly missing: boolean;

	constructor(message: string, missing = false) {
		super(message);
		this.missing = missing;
	}
}

/** The package manager command that installs cloudflared here, or undefined where there is no single obvious one. */
export function cloudflaredInstallCommand(
	platform = process.platform,
): { command: string; args: string[] } | undefined {
	if (platform === "darwin") return { command: "brew", args: ["install", "cloudflared"] };
	if (platform === "win32") return { command: "winget", args: ["install", "--id", "Cloudflare.cloudflared", "-e"] };
	return undefined;
}

/**
 * Installs cloudflared with the platform's package manager (only after the user said yes). Resolves with an error message,
 * or undefined on success; [onLine] gets the installer's output lines for a progress status.
 */
export function installCloudflared(
	onLine: (line: string) => void,
	options: { spawnProcess?: typeof spawn; platform?: NodeJS.Platform } = {},
): Promise<string | undefined> {
	const install = cloudflaredInstallCommand(options.platform);
	if (!install) return Promise.resolve(cloudflaredInstallHint(options.platform));
	const spawnProcess = options.spawnProcess ?? spawn;
	return new Promise((resolve) => {
		let child: ChildProcess;
		try {
			child = spawnProcess(install.command, install.args, { stdio: ["ignore", "pipe", "pipe"], windowsHide: true });
		} catch (error) {
			resolve(`Could not run ${install.command}: ${error instanceof Error ? error.message : String(error)}`);
			return;
		}
		let tail = "";
		const onOutput = (chunk: Buffer) => {
			tail = (tail + chunk.toString("utf8")).slice(-4096);
			const line = tail
				.trimEnd()
				.split(/\r?\n|\r/)
				.pop()
				?.trim();
			if (line) onLine(line);
		};
		child.stdout?.on("data", onOutput);
		child.stderr?.on("data", onOutput);
		child.on("error", (error: NodeJS.ErrnoException) =>
			resolve(
				error.code === "ENOENT"
					? `${install.command} was not found. ${cloudflaredInstallHint(options.platform)}`
					: `${install.command} failed: ${error.message}`,
			),
		);
		child.on("exit", (code) => {
			const last = tail.trim().split("\n").slice(-2).join("\n");
			resolve(code === 0 ? undefined : `${install.command} exited with code ${code}${last ? `:\n${last}` : ""}`);
		});
	});
}

export function cloudflaredInstallHint(platform: NodeJS.Platform = process.platform): string {
	if (platform === "darwin") return "Install it with: brew install cloudflared";
	if (platform === "win32") return "Install it with: winget install --id Cloudflare.cloudflared";
	return "Install it from https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/";
}

export function startTunnel(
	port: number,
	path: string,
	settings: RemoteControlSettings,
	options: { onExit?: (message: string) => void; spawnProcess?: typeof spawn } = {},
): Promise<Tunnel> {
	const spawnProcess = options.spawnProcess ?? spawn;
	const executable = settings.cloudflared || "cloudflared";
	const target = `http://127.0.0.1:${port}`;
	const named = typeof settings.tunnel === "object" ? settings.tunnel : undefined;
	const args = named
		? ["tunnel", "--no-autoupdate", "run", "--url", target, named.name]
		: ["tunnel", "--no-autoupdate", "--url", target];

	return new Promise<Tunnel>((resolve, reject) => {
		let child: ChildProcess;
		try {
			child = spawnProcess(executable, args, { stdio: ["ignore", "pipe", "pipe"], windowsHide: true });
		} catch (error) {
			reject(
				new TunnelError(`Could not start ${executable}: ${error instanceof Error ? error.message : String(error)}`),
			);
			return;
		}
		let settled = false;
		let stopped = false;
		let log = "";
		let quickUrl: string | undefined;
		const stop = () => {
			stopped = true;
			if (child.exitCode === null && child.signalCode === null) child.kill();
			process.off("exit", stop);
		};
		process.on("exit", stop);
		const fail = (message: string, missing = false) => {
			if (settled) return;
			settled = true;
			clearTimeout(timer);
			stop();
			reject(new TunnelError(message, missing));
		};
		const timer = setTimeout(
			() => fail(`cloudflared did not connect within ${START_TIMEOUT_MS / 1000} s`),
			START_TIMEOUT_MS,
		);
		const onOutput = (chunk: Buffer) => {
			if (settled) return;
			log = (log + chunk.toString("utf8")).slice(-8192);
			quickUrl ??= named ? undefined : QUICK_TUNNEL_URL.exec(log)?.[0];
			// Ready once the edge registered a connection; the quick tunnel's URL is printed before that.
			if (!CONNECTED.test(log) || (!named && !quickUrl)) return;
			settled = true;
			clearTimeout(timer);
			const host = named ? named.hostname : new URL(quickUrl as string).host;
			resolve({ endpoint: `wss://${host}${path}`, stop });
		};
		child.stdout?.on("data", onOutput);
		child.stderr?.on("data", onOutput);
		child.on("error", (error: NodeJS.ErrnoException) => {
			fail(
				error.code === "ENOENT"
					? `cloudflared was not found. ${cloudflaredInstallHint()}`
					: `cloudflared failed: ${error.message}`,
				error.code === "ENOENT",
			);
		});
		child.on("exit", (code) => {
			const lastLines = log.trim().split("\n").slice(-3).join("\n");
			const message = `cloudflared exited with code ${code}${lastLines ? `:\n${lastLines}` : ""}`;
			if (!settled) fail(message);
			else if (!stopped) {
				process.off("exit", stop);
				options.onExit?.(message);
			}
		});
	});
}
