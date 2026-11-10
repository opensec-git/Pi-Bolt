/**
 * `pi-bolt service`: remote control as a login item of this user, so paired phones reach the computer with no terminal
 * open (service-host.ts is what runs). Opt-in: nothing is installed until `pi-bolt service install`, which (like `pair`)
 * refuses while remote control is off (`remoteControl.enabled`); `uninstall`, `status` and `logs` work either way.
 *
 * - install: writes the service file (a LaunchAgent, a systemd user unit, or a Task Scheduler task) in the user's own
 *   directories, and starts it. Nothing needs administrator rights.
 * - uninstall: stops it and removes every file the service wrote.
 * - status: whether it runs, its addresses and phones, and a QR code that gives a paired phone the current addresses.
 * - pair: a pairing code from the running service, for a new phone.
 * - logs: the service's log.
 * - restart: restarts the installed service if it runs, so that it runs this version (the installers run it after an
 *   update; with remote control off, the new code then exits). It never installs or starts one that does not run.
 */

import { existsSync, mkdirSync, readFileSync, realpathSync, rmSync, statSync, writeFileSync } from "node:fs";
import { homedir, userInfo } from "node:os";
import { dirname, isAbsolute, resolve } from "node:path";
import { COMMAND_NAME, ENV_AGENT_DIR, ENV_SESSION_DIR, isBunBinary } from "../config.ts";
import { SettingsManager } from "../core/settings-manager.ts";
import { REMOTE_CONTROL_OFF_MESSAGE } from "../core/slash-commands.ts";
import { PIBOLT, piBoltExecutableIn, piBoltInstallDir, piBoltInstallMethod } from "../pi-bolt.ts";
import { findExecutableOnPath, spawnProcess } from "../utils/child-process.ts";
import { createInterface } from "../utils/readline.ts";
import { endpointLabel } from "./controller.ts";
import { encodeReconnectUri } from "./protocol.ts";
import { renderQrLines } from "./qr.ts";
import { type ControlMessage, connectControl, controlPaths } from "./service-control.ts";
import {
	LAUNCH_AGENT_LABEL,
	launchAgentPlist,
	type ServicePlatform,
	type ServiceSpec,
	SYSTEMD_UNIT,
	serviceFilePath,
	systemdUserUnit,
	WINDOWS_TASK_NAME,
	windowsTaskFile,
	windowsTaskScript,
	windowsTaskScriptPath,
	windowsTaskXml,
} from "./service-files.ts";
import { describeTunnel, type ServiceStatus, servicePort, serviceTunnelSetting } from "./service-host.ts";
import { readLogTail, serviceLogPaths } from "./service-log.ts";
import { cloudflaredInstallCommand, cloudflaredInstallHint, installCloudflared } from "./tunnel.ts";

/** What the commands touch outside Pi-Bolt's own files; tests replace it. */
export interface ServiceSystem {
	platform: NodeJS.Platform;
	home: string;
	env: NodeJS.ProcessEnv;
	/** launchd's GUI domain is per user id. */
	uid: number;
	/** `DOMAIN\user` for the Windows task. */
	windowsUser: string;
	isTTY: boolean;
	run(command: string, args: string[]): Promise<{ code: number; stdout: string; stderr: string }>;
	/** A yes/no question; false without an answer. */
	confirm(question: string): Promise<boolean>;
	/** The command the service manager runs, without `service run`. */
	program(): string[];
	/** cloudflared is installed (or at the configured path). */
	hasCloudflared(path: string | undefined): boolean;
	/** How long `pair` waits for the phone, and how often `logs -f` looks for new lines. */
	sleep(ms: number): Promise<void>;
}

export interface ServiceCommandContext {
	agentDir: string;
	out?: (line: string) => void;
	system?: Partial<ServiceSystem>;
}

const USAGE = [
	`Usage: ${COMMAND_NAME} service <command>`,
	"",
	"Remote control for paired phones while no terminal runs Pi-Bolt: a login item of this user (opt-in).",
	`Needs remote control on (${COMMAND_NAME} remote-control enable) to install it or pair a phone.`,
	"",
	"Commands:",
	"  install [--no-tunnel]   Start it now and at every login (--no-tunnel: local network and Tailscale only)",
	"  uninstall               Stop it and remove the files it wrote",
	"  status                  Show whether it runs, its addresses and phones, and a QR code with the addresses",
	"  pair [--link]           Pair a new phone with the running service (--link: also print the pairing link)",
	"  logs [-n <lines>] [-f]  Show the service log (-f: keep following it)",
	"  restart                 Restart it if it runs, so it runs this version (the installers do this after an update)",
];

export async function runServiceCommand(args: string[], context: ServiceCommandContext): Promise<number> {
	const out = context.out ?? ((line: string) => process.stdout.write(`${line}\n`));
	const system: ServiceSystem = { ...defaultSystem(), ...context.system };
	const [command, ...rest] = args;
	try {
		if ((command === "install" || command === "pair") && !remoteControlEnabled(system, context.agentDir)) {
			out(REMOTE_CONTROL_OFF_MESSAGE);
			return 1;
		}
		switch (command) {
			case "install":
				return await install(rest, context.agentDir, system, out);
			case "uninstall":
				return await uninstall(context.agentDir, system, out);
			case "status":
				return await status(context.agentDir, system, out);
			case "pair":
				return await pair(rest, context.agentDir, out);
			case "logs":
				return await logs(rest, context.agentDir, system, out);
			case "restart":
				return await restart(context.agentDir, system, out);
			case undefined:
			case "help":
			case "--help":
			case "-h":
				for (const line of USAGE) out(line);
				return command === undefined ? 1 : 0;
			default:
				out(`Unknown command: ${command}`);
				for (const line of USAGE) out(line);
				return 1;
		}
	} catch (error) {
		out(`Error: ${error instanceof Error ? error.message : String(error)}`);
		return 1;
	}
}

/** `remoteControl.enabled`, from the global settings. */
function remoteControlEnabled(system: ServiceSystem, agentDir: string): boolean {
	return SettingsManager.create(system.home, agentDir, { projectTrusted: false }).getRemoteControlEnabled();
}

function defaultSystem(): ServiceSystem {
	return {
		platform: process.platform,
		home: homedir(),
		env: process.env,
		uid: process.getuid?.() ?? 0,
		windowsUser: `${process.env.USERDOMAIN ? `${process.env.USERDOMAIN}\\` : ""}${process.env.USERNAME ?? userInfo().username}`,
		isTTY: process.stdout.isTTY === true && process.stdin.isTTY === true,
		run: runCommand,
		confirm: askYesNo,
		program: serviceProgram,
		hasCloudflared: (path) =>
			path && (isAbsolute(path) || path.includes("/"))
				? existsSync(path)
				: !!findExecutableOnPath(path || "cloudflared"),
		sleep: (ms) => new Promise((done) => setTimeout(done, ms)),
	};
}

function runCommand(command: string, args: string[]): Promise<{ code: number; stdout: string; stderr: string }> {
	return new Promise((done) => {
		let stdout = "";
		let stderr = "";
		const child = spawnProcess(command, args, { stdio: ["ignore", "pipe", "pipe"], windowsHide: true });
		child.stdout.on("data", (chunk: Buffer) => {
			stdout += chunk.toString("utf8");
		});
		child.stderr.on("data", (chunk: Buffer) => {
			stderr += chunk.toString("utf8");
		});
		child.on("error", (error) => done({ code: -1, stdout, stderr: error.message }));
		child.on("close", (code) => done({ code: code ?? -1, stdout, stderr }));
	});
}

function askYesNo(question: string): Promise<boolean> {
	return new Promise((done) => {
		const rl = createInterface({ input: process.stdin, output: process.stdout });
		rl.question(`${question} [y/N] `, (answer) => {
			rl.close();
			done(/^y(es)?$/i.test(answer.trim()));
		});
	});
}

/**
 * The executable the service manager starts, one that survives updates: the installer's in place (not a versioned
 * folder), else `pi-bolt` on PATH (a package manager's link or shim), else this executable. From a source checkout, Node
 * with this script.
 */
export function serviceProgram(): string[] {
	if (PIBOLT) {
		const installDir = piBoltInstallMethod() === "installer" ? piBoltInstallDir() : undefined;
		const installed = installDir ? piBoltExecutableIn(installDir) : undefined;
		if (installed && existsSync(installed)) return [installed];
		const onPath = findExecutableOnPath(process.platform === "win32" ? `${COMMAND_NAME}.exe` : COMMAND_NAME);
		return [onPath ?? process.execPath];
	}
	if (isBunBinary) return [process.execPath];
	// (Node's own options, such as `--import <loader>`, with relative paths made absolute: the service starts elsewhere.)
	const execArgv = process.execArgv.map((arg, index) =>
		index > 0 &&
		["--import", "--require", "-r", "--loader"].includes(process.execArgv[index - 1] ?? "") &&
		arg.startsWith(".")
			? resolve(arg)
			: arg,
	);
	return [process.execPath, ...execArgv, realpathSync(process.argv[1] ?? "")];
}

/** The service manager of this platform: the file it reads, and how to load, start, stop and query the service. */
interface ServiceManager {
	name: string;
	file: string;
	/** Everything [write] writes ([file] and, on Windows, the task's script). */
	files: string[];
	write(spec: ServiceSpec): void;
	start(): Promise<void>;
	stop(): Promise<void>;
	/** Stops the running service and starts it again from its file (which names this version's executable). */
	restart(): Promise<void>;
	query(): Promise<{ loaded: boolean; running: boolean }>;
}

function serviceManager(system: ServiceSystem, agentDir: string): ServiceManager {
	const platform = system.platform as ServicePlatform;
	const file = serviceFilePath(platform, system.home, agentDir, system.env);
	const must = async (command: string, args: string[]) => {
		const result = await system.run(command, args);
		if (result.code !== 0) {
			const detail = (result.stderr || result.stdout).trim().split("\n").slice(-2).join(" ");
			throw new Error(`${command} ${args.join(" ")} failed (${result.code})${detail ? `: ${detail}` : ""}`);
		}
		return result;
	};
	const writeFile = (content: string | Buffer, path = file) => {
		mkdirSync(dirname(path), { recursive: true });
		writeFileSync(path, content, { mode: 0o644 });
	};
	if (platform === "darwin") {
		const target = `gui/${system.uid}/${LAUNCH_AGENT_LABEL}`;
		return {
			name: `LaunchAgent ${LAUNCH_AGENT_LABEL}`,
			file,
			files: [file],
			write: (spec) => writeFile(launchAgentPlist(spec)),
			start: async () => {
				// Loaded already (an earlier install): replaced by this file. bootout returns before the job is gone, and
				// bootstrap fails (5: Input/output error) until it is.
				if ((await system.run("launchctl", ["bootout", target])).code === 0) {
					for (let wait = 0; wait < 20; wait++) {
						if ((await system.run("launchctl", ["print", target])).code !== 0) break;
						await system.sleep(250);
					}
				}
				await must("launchctl", ["bootstrap", `gui/${system.uid}`, file]);
			},
			stop: async () => {
				await system.run("launchctl", ["bootout", target]);
			},
			restart: async () => {
				await must("launchctl", ["kickstart", "-k", target]);
			},
			query: async () => {
				const result = await system.run("launchctl", ["print", target]);
				return {
					loaded: result.code === 0,
					running: result.code === 0 && /\bstate = running\b/.test(result.stdout),
				};
			},
		};
	}
	if (platform === "linux") {
		return {
			name: `systemd user unit ${SYSTEMD_UNIT}`,
			file,
			files: [file],
			write: (spec) => writeFile(systemdUserUnit(spec)),
			start: async () => {
				await must("systemctl", ["--user", "daemon-reload"]);
				await must("systemctl", ["--user", "enable", SYSTEMD_UNIT]);
				await must("systemctl", ["--user", "restart", SYSTEMD_UNIT]);
			},
			stop: async () => {
				await system.run("systemctl", ["--user", "disable", "--now", SYSTEMD_UNIT]);
			},
			// (try-restart: only a unit that runs; --no-block: an installer never waits for the old process to stop.)
			restart: async () => {
				await must("systemctl", ["--user", "--no-block", "try-restart", SYSTEMD_UNIT]);
			},
			query: async () => {
				const enabled = await system.run("systemctl", ["--user", "is-enabled", SYSTEMD_UNIT]);
				const active = await system.run("systemctl", ["--user", "is-active", SYSTEMD_UNIT]);
				return { loaded: enabled.code === 0, running: active.stdout.trim() === "active" };
			},
		};
	}
	if (platform === "win32") {
		const script = windowsTaskScriptPath(file);
		const system32 = `${system.env.SystemRoot ?? "C:\\Windows"}\\System32`;
		return {
			name: `scheduled task "${WINDOWS_TASK_NAME}"`,
			file,
			files: [file, script],
			write: (spec) => {
				writeFile(windowsTaskScript(spec), script);
				writeFile(
					windowsTaskFile(
						windowsTaskXml({
							userId: system.windowsUser,
							conhost: `${system32}\\conhost.exe`,
							shell: `${system32}\\cmd.exe`,
							script,
							home: spec.home,
						}),
					),
				);
			},
			start: async () => {
				await must("schtasks", ["/Create", "/TN", WINDOWS_TASK_NAME, "/XML", file, "/F"]);
				await system.run("schtasks", ["/End", "/TN", WINDOWS_TASK_NAME]);
				await must("schtasks", ["/Run", "/TN", WINDOWS_TASK_NAME]);
			},
			stop: async () => {
				await system.run("schtasks", ["/End", "/TN", WINDOWS_TASK_NAME]);
				await system.run("schtasks", ["/Delete", "/TN", WINDOWS_TASK_NAME, "/F"]);
			},
			restart: async () => {
				await system.run("schtasks", ["/End", "/TN", WINDOWS_TASK_NAME]);
				await must("schtasks", ["/Run", "/TN", WINDOWS_TASK_NAME]);
			},
			query: async () => {
				const result = await system.run("schtasks", ["/Query", "/TN", WINDOWS_TASK_NAME, "/FO", "LIST"]);
				return { loaded: result.code === 0, running: result.code === 0 && /:\s*Running\b/i.test(result.stdout) };
			},
		};
	}
	throw new Error(`The background service is not available on ${system.platform}`);
}

/** The environment a login item needs: PATH, and Pi's directory variables when this shell sets them. */
function serviceEnvironment(env: NodeJS.ProcessEnv): Record<string, string> {
	const result: Record<string, string> = {};
	const path = env.PATH ?? env.Path;
	if (path) result.PATH = path;
	for (const key of [ENV_AGENT_DIR, ENV_SESSION_DIR]) {
		const value = env[key];
		if (value) result[key] = value;
	}
	return result;
}

async function install(args: string[], agentDir: string, system: ServiceSystem, out: (line: string) => void) {
	const unknown = args.filter((arg) => arg !== "--no-tunnel");
	if (unknown.length > 0) throw new Error(`Unknown option for install: ${unknown.join(" ")}`);
	const manager = serviceManager(system, agentDir);
	const settingsManager = SettingsManager.create(system.home, agentDir, { projectTrusted: false });
	if (args.includes("--no-tunnel")) {
		settingsManager.setRemoteControlServiceTunnel("off");
		out('Set remoteControl.service.tunnel to "off": the service serves the local network and Tailscale only.');
	}
	const settings = settingsManager.getRemoteControlSettings();
	const tunnel = serviceTunnelSetting(settings);
	if (tunnel !== "off" && !system.hasCloudflared(settings.cloudflared)) {
		await offerCloudflared(system, out);
	}
	const paths = serviceLogPaths(agentDir);
	mkdirSync(dirname(paths.log), { recursive: true, mode: 0o700 });
	// The service manager appends to it, keeping the mode it finds: readable by the user only.
	if (!existsSync(paths.stderr)) writeFileSync(paths.stderr, "", { mode: 0o600 });
	manager.write({
		program: [...system.program(), "service", "run"],
		env: serviceEnvironment(system.env),
		home: system.home,
		stderrPath: paths.stderr,
	});
	await manager.start();
	out(`Installed the remote control service: ${manager.name}`);
	out(`  ${manager.file}`);
	out(
		`It starts at every login and again after a crash, on port ${servicePort(settings)} (local network and Tailscale), tunnel ${describeTunnel(tunnel)}.`,
	);
	const reply = await waitForStatus(agentDir, system);
	if (reply) printStatus(reply, out);
	else out(`It has not answered yet; see ${COMMAND_NAME} service status and ${COMMAND_NAME} service logs.`);
	if (reply?.role === "service" && reply.pairedDevices === 0) {
		out(`No phone is paired yet: run ${COMMAND_NAME} service pair.`);
	}
	return 0;
}

/** Offers to install a missing cloudflared (only in a terminal, only after a yes). */
async function offerCloudflared(system: ServiceSystem, out: (line: string) => void): Promise<void> {
	const command = cloudflaredInstallCommand(system.platform);
	const local = "Until then the service serves the local network and Tailscale only.";
	if (!command || !system.isTTY) {
		out(`The tunnel needs cloudflared, which is not installed. ${cloudflaredInstallHint(system.platform)}`);
		out(`${local} After installing it, run ${COMMAND_NAME} service install again.`);
		return;
	}
	const shown = [command.command, ...command.args].join(" ");
	if (!(await system.confirm(`The tunnel needs cloudflared, which is not installed. Install it now (${shown})?`))) {
		out(`${local} Install it later and run ${COMMAND_NAME} service install again.`);
		return;
	}
	const failure = await installCloudflared((line) => out(`  ${line.slice(0, 120)}`), { platform: system.platform });
	out(failure ? `cloudflared was not installed: ${failure}. ${local}` : "cloudflared installed.");
}

async function uninstall(agentDir: string, system: ServiceSystem, out: (line: string) => void) {
	const manager = serviceManager(system, agentDir);
	await manager.stop();
	const paths = serviceLogPaths(agentDir);
	const files = [
		...manager.files,
		paths.log,
		paths.previous,
		paths.stderr,
		controlPaths(agentDir, system.platform).token,
	];
	const removed = files.filter((file) => existsSync(file));
	for (const file of removed) rmSync(file, { force: true });
	out(
		removed.length > 0
			? "Stopped the remote control service and removed:"
			: "The remote control service was not installed.",
	);
	for (const file of removed) out(`  ${file}`);
	out(
		remoteControlEnabled(system, agentDir)
			? "Paired phones stay paired (/remote-control devices revokes them); your settings are unchanged."
			: `Paired phones stay paired; your settings are unchanged. To revoke a phone, turn remote control on (${COMMAND_NAME} remote-control enable), then run /remote-control devices.`,
	);
	return 0;
}

/**
 * Restarts an installed service that runs, so the code of this version runs (and, with remote control off, exits). Nothing
 * when it is not installed or does not run: it never installs or starts one.
 */
async function restart(agentDir: string, system: ServiceSystem, out: (line: string) => void) {
	const manager = serviceManager(system, agentDir);
	if (!existsSync(manager.file)) {
		out("The remote control service is not installed: nothing to restart.");
		return 0;
	}
	if (!(await manager.query()).running) {
		out("The remote control service does not run: nothing to restart.");
		return 0;
	}
	await manager.restart();
	out(`Restarted the remote control service: ${manager.name}`);
	return 0;
}

/** The service's status once it answers, for up to 10 s after it was started. */
async function waitForStatus(agentDir: string, system: ServiceSystem): Promise<ControlMessage | undefined> {
	for (let attempt = 0; attempt < 20; attempt++) {
		const reply = await requestStatus(agentDir);
		if (reply?.role === "service" && reply.state === "serving") return reply;
		if (reply && attempt >= 6) return reply;
		await system.sleep(500);
	}
	return requestStatus(agentDir);
}

async function requestStatus(agentDir: string): Promise<ControlMessage | undefined> {
	const client = await connectControl(controlPaths(agentDir)).catch(() => undefined);
	if (!client) return undefined;
	const reply = await client.request({ type: "status" });
	client.close();
	return reply?.ok === true ? reply : undefined;
}

const STATE_TEXT: Record<ServiceStatus["state"], string> = {
	starting: "starting (waiting for its port)",
	serving: "serving paired phones",
	yielded: "paused: a terminal's /remote-control has its port",
	waiting: "waiting: a terminal's /remote-control was serving when it started",
	stopped: "stopping",
};

function printStatus(reply: ControlMessage, out: (line: string) => void): void {
	if (reply.role === "terminal") {
		out(`A terminal's /remote-control (pid ${reply.pid}) serves remote control now; the service waits for it.`);
		return;
	}
	const status = reply as unknown as ServiceStatus;
	out(
		`Remote control service: ${STATE_TEXT[status.state] ?? status.state} (pid ${status.pid}, Pi-Bolt ${status.version})`,
	);
	out(`  Port ${status.port}`);
	for (const endpoint of status.endpoints) out(`  ${endpointLabel(endpoint)}  ${endpoint}`);
	const tunnel = status.tunnel;
	const tunnelText: Record<ServiceStatus["tunnel"]["state"], string> = {
		off: "off (remoteControl.service.tunnel)",
		starting: "starting",
		up: `up, ${tunnel.endpoint}`,
		missing: "cloudflared is not installed",
		failed: `failed (${tunnel.error ?? "unknown error"}); retrying`,
		stopped: tunnel.error ? `stopped (${tunnel.error})` : "stopped",
	};
	out(`  Tunnel: ${tunnelText[tunnel.state] ?? tunnel.state}`);
	const connected = status.clients.map((client) => client.name);
	out(
		`  Phones: ${status.pairedDevices} paired, ${connected.length === 0 ? "none connected" : `connected: ${connected.join(", ")}`}`,
	);
	if (status.session?.cwd) out(`  Session open in ${status.session.cwd}`);
}

async function status(agentDir: string, system: ServiceSystem, out: (line: string) => void) {
	const enabled = remoteControlEnabled(system, agentDir);
	if (!enabled) out(REMOTE_CONTROL_OFF_MESSAGE);
	const manager = serviceManager(system, agentDir);
	const installed = existsSync(manager.file);
	const query = installed ? await manager.query() : { loaded: false, running: false };
	const reply = await requestStatus(agentDir);
	if (!installed && enabled) out(`Not installed (${COMMAND_NAME} service install starts it at every login).`);
	else if (!installed) out("Not installed.");
	else out(`Installed: ${manager.name}${query.loaded ? "" : " (not loaded)"}`);
	if (!reply) {
		if (installed) out(`Not running. See ${COMMAND_NAME} service logs.`);
	} else printStatus(reply, out);
	out(`  Log: ${serviceLogPaths(agentDir).log}`);
	if (reply?.role !== "service") return reply ? 0 : 1;
	const serviceStatus = reply as unknown as ServiceStatus;
	if (serviceStatus.state === "serving" && serviceStatus.endpoints.length > 0) {
		const uri = encodeReconnectUri(
			serviceStatus.hostName,
			Buffer.from(serviceStatus.hostKey, "base64url"),
			serviceStatus.endpoints,
		);
		if (system.isTTY) {
			out("");
			for (const line of renderQrLines(uri).lines) out(line);
		}
		out("");
		const next = `A new phone: ${COMMAND_NAME} service pair.`;
		if (system.isTTY) out(`Scan with a paired phone to give it these addresses; it pairs nothing. ${next}`);
		else {
			out(`For a paired phone ("Paste pairing link"), with these addresses; it pairs nothing. ${next}`);
			out(uri);
		}
	}
	return 0;
}

async function pair(args: string[], agentDir: string, out: (line: string) => void) {
	const unknown = args.filter((arg) => arg !== "--link");
	if (unknown.length > 0) throw new Error(`Unknown option for pair: ${unknown.join(" ")}`);
	const client = await connectControl(controlPaths(agentDir)).catch(() => undefined);
	if (!client) {
		out(
			`The service is not running. Start it with ${COMMAND_NAME} service install, or pair with /remote-control in ${COMMAND_NAME}.`,
		);
		return 1;
	}
	try {
		const reply = await client.request({ type: "pair" });
		if (reply?.ok !== true || typeof reply.uri !== "string") {
			out(`Could not pair: ${typeof reply?.error === "string" ? reply.error : "no answer from the service"}`);
			return 1;
		}
		out("Pair a phone: scan with the Pi-Bolt app");
		out("");
		for (const line of renderQrLines(reply.uri).lines) out(line);
		out("");
		for (const endpoint of Array.isArray(reply.endpoints) ? reply.endpoints : []) {
			out(`  ${endpointLabel(String(endpoint))}  ${endpoint}`);
		}
		out("");
		out("The code pairs one phone and expires in 10 minutes. Waiting for the phone (Ctrl+C to stop)...");
		if (args.includes("--link")) {
			out("Pairing link (paste it only into the Pi-Bolt app):");
			out(reply.uri);
		}
		const expiresAt = typeof reply.expiresAt === "number" ? reply.expiresAt * 1000 : Date.now() + 10 * 60_000;
		const event = await client.connection.next(Math.max(expiresAt - Date.now(), 0));
		if (event?.type === "paired") {
			out(`Paired ${String(event.device)}.`);
			return 0;
		}
		out(
			event?.type === "pair_cancelled"
				? `Pairing stopped: ${String(event.error)}`
				: "The code expired, or the service stopped, before a phone paired.",
		);
		return 1;
	} finally {
		client.close();
	}
}

async function logs(args: string[], agentDir: string, system: ServiceSystem, out: (line: string) => void) {
	let count = 50;
	let follow = false;
	for (let index = 0; index < args.length; index++) {
		const arg = args[index];
		if (arg === "-f" || arg === "--follow") follow = true;
		else if (arg === "-n" || arg === "--lines") {
			count = Number(args[++index]);
			if (!Number.isInteger(count) || count < 1) throw new Error("-n takes a number of lines");
		} else throw new Error(`Unknown option for logs: ${arg}`);
	}
	const path = serviceLogPaths(agentDir).log;
	const lines = readLogTail(agentDir, count);
	if (lines.length === 0 && !follow) out(`No log yet (${path}).`);
	for (const line of lines) out(line);
	if (!follow) return 0;
	let offset = existsSync(path) ? statSync(path).size : 0;
	for (;;) {
		await system.sleep(500);
		const size = existsSync(path) ? statSync(path).size : 0;
		// Rotated: the new file starts from the beginning.
		if (size < offset) offset = 0;
		if (size === offset) continue;
		const text = readFileSync(path).subarray(offset, size).toString("utf8");
		offset = size;
		for (const line of text.split("\n").filter(Boolean)) out(line);
	}
}
