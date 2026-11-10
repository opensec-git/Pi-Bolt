import { spawn } from "node:child_process";
import {
	existsSync,
	mkdirSync,
	mkdtempSync,
	readdirSync,
	readFileSync,
	realpathSync,
	rmSync,
	writeFileSync,
} from "node:fs";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import type { CombinedAutocompleteProvider } from "@earendil-works/pi-tui";
import { afterEach, describe, expect, it, vi } from "vitest";
import { runRemoteControlSettingCommand } from "../src/cli/remote-control-setting.ts";
import { ENV_AGENT_DIR } from "../src/config.ts";
import { SettingsManager } from "../src/core/settings-manager.ts";
import {
	BUILTIN_SLASH_COMMANDS,
	listedBuiltinSlashCommands,
	REMOTE_CONTROL_OFF_MESSAGE,
	REMOTE_CONTROL_STOPPED_MESSAGE,
} from "../src/core/slash-commands.ts";
import { InteractiveMode } from "../src/modes/interactive/interactive-mode.ts";
import { RemoteControlController, type RemoteControlHost } from "../src/remote-control/controller.ts";
import { RemoteControlServer } from "../src/remote-control/server.ts";
import { runServiceCommand, type ServiceSystem } from "../src/remote-control/service-cli.ts";
import {
	LAUNCH_AGENT_LABEL,
	SYSTEMD_UNIT,
	serviceFilePath,
	WINDOWS_TASK_NAME,
} from "../src/remote-control/service-files.ts";
import { ServiceHost } from "../src/remote-control/service-host.ts";
import { ServiceLog } from "../src/remote-control/service-log.ts";

const loadRemoteControl = vi.hoisted(() => vi.fn());
vi.mock("../src/remote-control/remote-control.lazy.ts", () => ({ loadRemoteControl }));
// The `/settings` selector, replaced by one that hands the test its callbacks.
const selectorCallbacks = vi.hoisted(() => ({
	last: undefined as { onRemoteControlEnabledChange(on: boolean): void } | undefined,
}));
vi.mock("../src/modes/interactive/components/settings-selector.ts", () => ({
	SettingsSelectorComponent: class {
		constructor(_config: unknown, callbacks: { onRemoteControlEnabledChange(on: boolean): void }) {
			selectorCallbacks.last = callbacks;
		}
		getSettingsList() {
			return {};
		}
	},
}));

const OFF = "Remote control is off. Turn it on with: pi remote-control enable";
const cleanups: Array<() => unknown> = [];

afterEach(async () => {
	while (cleanups.length > 0) await cleanups.pop()?.();
	loadRemoteControl.mockReset();
});

function tempDir(prefix: string): string {
	const dir = realpathSync(mkdtempSync(join(tmpdir(), prefix)));
	cleanups.push(() => rmSync(dir, { recursive: true, force: true }));
	return dir;
}

function writeJson(path: string, value: unknown): void {
	mkdirSync(join(path, ".."), { recursive: true });
	writeFileSync(path, JSON.stringify(value));
}

/** A home with the agent directory in it, and a project whose own settings try to turn remote control on. */
function setup(globalSettings?: unknown) {
	const home = tempDir("pi-rc-optin-");
	const agentDir = join(home, ".pi", "agent");
	const project = join(home, "project");
	mkdirSync(agentDir, { recursive: true });
	writeJson(join(project, ".pi", "settings.json"), { remoteControl: { enabled: true } });
	if (globalSettings !== undefined) writeJson(join(agentDir, "settings.json"), globalSettings);
	return { home, agentDir, project };
}

describe("remoteControl.enabled", () => {
	it("is off by default", () => {
		expect(SettingsManager.inMemory().getRemoteControlEnabled()).toBe(false);
		expect(REMOTE_CONTROL_OFF_MESSAGE).toBe(OFF);
	});

	it("is turned on only by the value true: a string, a number or a bare true stay off", () => {
		for (const value of [
			{ remoteControl: { enabled: "true" } },
			{ remoteControl: { enabled: 1 } },
			{ remoteControl: { enabled: {} } },
			{ remoteControl: true },
			{ remoteControlEnabled: true },
		]) {
			const { agentDir, project } = setup(value);
			expect(SettingsManager.create(project, agentDir, { projectTrusted: true }).getRemoteControlEnabled()).toBe(
				false,
			);
		}
		const { agentDir, project } = setup({ remoteControl: { enabled: true } });
		expect(SettingsManager.create(project, agentDir, { projectTrusted: true }).getRemoteControlEnabled()).toBe(true);
	});

	it("is read from the global settings only: a trusted project's settings cannot turn it on", () => {
		const { agentDir, project } = setup();
		const settings = SettingsManager.create(project, agentDir, { projectTrusted: true });
		expect(settings.getRemoteControlSettings()).toMatchObject({ enabled: true });
		expect(settings.getRemoteControlEnabled()).toBe(false);
		writeJson(join(agentDir, "settings.json"), { remoteControl: { enabled: true } });
		expect(SettingsManager.create(project, agentDir, { projectTrusted: true }).getRemoteControlEnabled()).toBe(true);
	});

	it("is written to the global settings, keeping the other remote control settings", async () => {
		const { agentDir, project } = setup({ remoteControl: { port: 4000 } });
		const settings = SettingsManager.create(project, agentDir, { projectTrusted: true });
		settings.setRemoteControlEnabled(true);
		await settings.flush();
		expect(JSON.parse(readFileSync(join(agentDir, "settings.json"), "utf8"))).toEqual({
			remoteControl: { port: 4000, enabled: true },
		});
		expect(JSON.parse(readFileSync(join(project, ".pi", "settings.json"), "utf8"))).toEqual({
			remoteControl: { enabled: true },
		});
	});
});

describe("pi remote-control enable|disable|status", () => {
	async function run(args: string[], context: { agentDir: string; project: string }) {
		const lines: string[] = [];
		const code = await runRemoteControlSettingCommand(args, {
			agentDir: context.agentDir,
			cwd: context.project,
			out: (line) => lines.push(line),
		});
		return { code, lines };
	}

	it("turns it on and off in the global settings, one line each", async () => {
		const context = setup();
		expect(await run(["status"], context)).toEqual({ code: 0, lines: [OFF] });
		const on = await run(["enable"], context);
		expect(on).toEqual({
			code: 0,
			lines: ["Remote control is on: /remote-control in pi, or pi service install for the background service."],
		});
		expect(JSON.parse(readFileSync(join(context.agentDir, "settings.json"), "utf8"))).toEqual({
			remoteControl: { enabled: true },
		});
		expect((await run(["status"], context)).lines).toEqual(on.lines);
		expect(await run(["disable"], context)).toEqual({ code: 0, lines: [OFF] });
		expect(JSON.parse(readFileSync(join(context.agentDir, "settings.json"), "utf8"))).toEqual({
			remoteControl: { enabled: false },
		});
	});

	it("prints its usage for no command or an unknown one", async () => {
		const context = setup();
		const none = await run([], context);
		expect(none.code).toBe(1);
		expect(none.lines[0]).toBe("Usage: pi remote-control <command>");
		const unknown = await run(["on"], context);
		expect(unknown.code).toBe(1);
		expect(unknown.lines[0]).toBe("Unknown command: on");
		expect((await run(["enable", "now"], context)).code).toBe(1);
		expect(existsSync(join(context.agentDir, "settings.json"))).toBe(false);
	});
});

describe("/remote-control in the terminal", () => {
	type Fake = {
		settingsManager: { getRemoteControlEnabled: () => boolean };
		remoteControl: { stop: () => Promise<void>; handleCommand: (args: string) => Promise<void> } | undefined;
		showStatus: (message: string) => void;
	};
	const proto = InteractiveMode.prototype as unknown as {
		handleRemoteControlCommand(this: Fake, args: string): Promise<void>;
		createBaseAutocompleteProvider(this: unknown): CombinedAutocompleteProvider;
	};

	it("lists /remote-control only while remote control is on", () => {
		expect(listedBuiltinSlashCommands(false).map((command) => command.name)).not.toContain("remote-control");
		expect(listedBuiltinSlashCommands(false)).toHaveLength(BUILTIN_SLASH_COMMANDS.length - 1);
		expect(listedBuiltinSlashCommands(true)).toBe(BUILTIN_SLASH_COMMANDS);
	});

	it("leaves it out of autocomplete while off, an extension's command of that name too", async () => {
		const suggestions = async (enabled: boolean) => {
			const fakeThis = {
				session: {
					scopedModels: [],
					modelRuntime: { getAvailableSnapshot: () => [] },
					promptTemplates: [],
					extensionRunner: {
						getRegisteredCommands: () => [
							{ name: "remote-control", invocationName: "remote-control", description: "ext" },
							{ name: "remote-deploy", invocationName: "remote-deploy", description: "ext" },
						],
					},
					resourceLoader: { getSkills: () => ({ skills: [] }) },
				},
				settingsManager: { getEnableSkillCommands: () => false, getRemoteControlEnabled: () => enabled },
				skillCommands: new Map(),
				sessionManager: { getCwd: () => "/tmp" },
				fdPath: null,
				prefixAutocompleteDescription: (description: string) => description,
			};
			const provider = proto.createBaseAutocompleteProvider.call(fakeThis);
			const result = await provider.getSuggestions(["/remote"], 0, 7, { signal: new AbortController().signal });
			return result?.items.map((item) => `${item.value}: ${item.description}`) ?? [];
		};
		expect(await suggestions(false)).toEqual(["remote-deploy: ext"]);
		expect(await suggestions(true)).toEqual([
			"remote-control: [lan|tunnel|copy|stop|devices] — Control this session from the Pi-Bolt Android app (QR pairing)",
			"remote-deploy: ext",
		]);
	});

	it("while off, prints one line and loads nothing of remote control, with or without arguments", async () => {
		for (const args of ["", " lan", " devices", " stop"]) {
			const showStatus = vi.fn();
			const fake: Fake = {
				settingsManager: { getRemoteControlEnabled: () => false },
				remoteControl: undefined,
				showStatus,
			};
			await proto.handleRemoteControlCommand.call(fake, args);
			expect(showStatus.mock.calls).toEqual([[OFF]]);
		}
		expect(loadRemoteControl).not.toHaveBeenCalled();
	});

	it("while off, stops a server started while it was on", async () => {
		const stop = vi.fn(async () => {});
		const handleCommand = vi.fn(async () => {});
		const showStatus = vi.fn();
		await proto.handleRemoteControlCommand.call(
			{
				settingsManager: { getRemoteControlEnabled: () => false },
				remoteControl: { stop, handleCommand },
				showStatus,
			},
			"",
		);
		expect(stop).toHaveBeenCalledOnce();
		expect(handleCommand).not.toHaveBeenCalled();
		expect(showStatus.mock.calls).toEqual([[OFF]]);
	});

	describe("a running /remote-control stops once the session sees it turned off", () => {
		/** A value that answers every property and call (the parts of InteractiveMode these methods only pass along). */
		const anything: unknown = new Proxy(() => {}, {
			get: (_target, key) => (key === Symbol.toPrimitive ? () => "x" : key === "then" ? undefined : anything),
			apply: () => anything,
			construct: () => anything as object,
		});
		const methods = InteractiveMode.prototype as unknown as {
			stopRemoteControlWhenOff(this: object): void;
			applyRuntimeSettings(this: object): void;
			showSettingsSelector(this: object): void;
		};
		/** `this` for those methods: the given fields, and `anything` for the rest. */
		function fakeMode(
			enabled: boolean,
			active: boolean,
			given?: { settingsManager: SettingsManager; remoteControl: RemoteControlController },
		) {
			const settingsManager = given?.settingsManager ?? SettingsManager.inMemory({ remoteControl: { enabled } });
			const stop = vi.fn(async () => {});
			const showWarning = vi.fn();
			const fields: Record<string | symbol, unknown> = {
				settingsManager,
				remoteControl: given?.remoteControl ?? { active, stop },
				showWarning,
				setupAutocompleteProvider: vi.fn(),
				showSelector: (create: (done: () => void) => unknown) => create(() => {}),
				stopRemoteControlWhenOff: methods.stopRemoteControlWhenOff,
			};
			const mode = new Proxy(fields, { get: (target, key) => (key in target ? target[key] : anything) });
			return { mode, settingsManager, stop, showWarning };
		}

		it("only when it runs and the setting is off", () => {
			for (const [enabled, running, stops] of [
				[false, true, true],
				[true, true, false],
				[false, false, false],
			] as const) {
				const { mode, stop, showWarning } = fakeMode(enabled, running);
				methods.stopRemoteControlWhenOff.call(mode);
				expect(stop).toHaveBeenCalledTimes(stops ? 1 : 0);
				expect(showWarning.mock.calls).toEqual(stops ? [[REMOTE_CONTROL_STOPPED_MESSAGE]] : []);
			}
		});

		it("at once when the /settings toggle turns it off", () => {
			const { mode, settingsManager, stop, showWarning } = fakeMode(true, true);
			methods.showSettingsSelector.call(mode);
			selectorCallbacks.last?.onRemoteControlEnabledChange(false);
			expect(settingsManager.getRemoteControlEnabled()).toBe(false);
			expect(stop).toHaveBeenCalledOnce();
			expect(showWarning.mock.calls).toEqual([[REMOTE_CONTROL_STOPPED_MESSAGE]]);
		});

		it("when /reload applies settings that a shell's remote-control disable turned off", async () => {
			const { mode, settingsManager, stop } = fakeMode(true, true);
			methods.applyRuntimeSettings.call(mode);
			expect(stop).not.toHaveBeenCalled();
			// (What `pi remote-control disable` writes; /reload reads the settings again, then applies them.)
			settingsManager.setRemoteControlEnabled(false);
			methods.applyRuntimeSettings.call(mode);
			expect(stop).toHaveBeenCalledOnce();
		});

		describe("a start still waiting for its claim", () => {
			/**
			 * A real controller whose claim (the background service handoff) waits until the test resolves it, with listens
			 * and tunnel starts counted.
			 */
			function startingController() {
				const settingsManager = SettingsManager.inMemory({
					remoteControl: { enabled: true, port: 0, tunnel: "quick" },
				});
				const pending: Array<() => void> = [];
				let released = 0;
				let tunnels = 0;
				const warnings: string[] = [];
				const listens = vi.spyOn(RemoteControlServer.prototype, "start");
				cleanups.push(() => listens.mockRestore());
				const controller = new RemoteControlController(
					{
						// (The listener subscribes to the session's events; nothing else of it is used here.)
						getSession: () =>
							({ subscribe: () => () => {} }) as unknown as ReturnType<RemoteControlHost["getSession"]>,
						settingsManager,
						agentDir: tempDir("pi-rc-start-"),
						version: "9.9.9",
						showStatus: () => {},
						showWarning: (message) => warnings.push(message),
						showError: (message) => {
							throw new Error(message);
						},
						showLines: () => {},
						setStatus: () => {},
						select: async () => undefined,
						copyToClipboard: async () => {},
						columns: () => 200,
						rows: () => 60,
						bindAddress: "127.0.0.1",
					},
					{
						claim: () =>
							new Promise((resolve) =>
								pending.push(() =>
									resolve({
										kind: "none",
										release: () => {
											released++;
										},
									}),
								),
							),
						startTunnel: async () => {
							tunnels++;
							return { endpoint: `wss://t-${tunnels}.trycloudflare.com/rc`, stop: () => {} };
						},
						installCloudflared: async () => "not in tests",
						platform: "darwin",
					},
				);
				cleanups.push(() => controller.stop());
				const waitForClaim = async () => {
					while (pending.length === 0) await new Promise((done) => setTimeout(done, 5));
				};
				return {
					controller,
					settingsManager,
					warnings,
					waitForClaim,
					resolveClaim: () => pending.shift()?.(),
					released: () => released,
					tunnels: () => tunnels,
					listens: () => listens.mock.calls.length,
				};
			}

			it("opens no listener and no tunnel when the setting goes off before the claim resolves", async () => {
				const start = startingController();
				const command = start.controller.handleCommand("");
				await start.waitForClaim();
				expect(start.controller.active).toBe(true);
				start.settingsManager.setRemoteControlEnabled(false);
				start.resolveClaim();
				await command;
				expect(start.listens()).toBe(0);
				expect(start.tunnels()).toBe(0);
				expect(start.released()).toBe(1);
				expect(start.controller.active).toBe(false);
				expect(start.warnings).toEqual([]);
			});

			it("the /settings toggle stops it: no listener, no tunnel", async () => {
				const start = startingController();
				const { mode, showWarning } = fakeMode(true, true, {
					settingsManager: start.settingsManager,
					remoteControl: start.controller,
				});
				const command = start.controller.handleCommand("");
				await start.waitForClaim();
				methods.showSettingsSelector.call(mode);
				selectorCallbacks.last?.onRemoteControlEnabledChange(false);
				expect(showWarning.mock.calls).toEqual([[REMOTE_CONTROL_STOPPED_MESSAGE]]);
				start.resolveClaim();
				await command;
				expect(start.listens()).toBe(0);
				expect(start.tunnels()).toBe(0);
				expect(start.released()).toBe(1);
				expect(start.controller.active).toBe(false);
			});

			it("/reload after the shell's remote-control disable stops it: no listener, no tunnel", async () => {
				const start = startingController();
				const { mode, showWarning } = fakeMode(true, true, {
					settingsManager: start.settingsManager,
					remoteControl: start.controller,
				});
				const command = start.controller.handleCommand("");
				await start.waitForClaim();
				// (What /reload does: the settings read again say off, then they are applied.)
				start.settingsManager.setRemoteControlEnabled(false);
				methods.applyRuntimeSettings.call(mode);
				expect(showWarning.mock.calls).toEqual([[REMOTE_CONTROL_STOPPED_MESSAGE]]);
				start.resolveClaim();
				await command;
				expect(start.listens()).toBe(0);
				expect(start.tunnels()).toBe(0);
				expect(start.released()).toBe(1);
			});

			it("turned on again, the next start listens and opens its tunnel as before", async () => {
				const start = startingController();
				const first = start.controller.handleCommand("");
				await start.waitForClaim();
				start.settingsManager.setRemoteControlEnabled(false);
				await start.controller.stop();
				start.resolveClaim();
				await first;
				expect(start.listens()).toBe(0);
				start.settingsManager.setRemoteControlEnabled(true);
				const second = start.controller.handleCommand("");
				await start.waitForClaim();
				start.resolveClaim();
				await second;
				expect(start.controller.running).toBe(true);
				expect(start.listens()).toBe(1);
				expect(start.tunnels()).toBe(1);
				expect(start.released()).toBe(1);
			});
		});
	});

	it("while on, loads remote control and runs the command as before", async () => {
		const handleCommand = vi.fn(async () => {});
		loadRemoteControl.mockResolvedValue({
			RemoteControlController: class {
				handleCommand = handleCommand;
			},
		});
		const fake: Fake = {
			settingsManager: { getRemoteControlEnabled: () => true },
			remoteControl: undefined,
			showStatus: vi.fn(),
		};
		await proto.handleRemoteControlCommand.call(fake, " lan");
		expect(loadRemoteControl).toHaveBeenCalledOnce();
		expect(handleCommand).toHaveBeenCalledWith(" lan");
		expect(fake.showStatus).not.toHaveBeenCalled();
	});
});

describe("pi service while remote control is off", () => {
	function fakeSystem(home: string, commands: string[]): Partial<ServiceSystem> {
		return {
			platform: "darwin",
			home,
			env: { PATH: "/usr/bin:/bin" },
			uid: 501,
			isTTY: false,
			run: async (command, args) => {
				commands.push([command, ...args].join(" "));
				return { code: args[0] === "print" ? 113 : 0, stdout: "", stderr: "" };
			},
			confirm: async () => false,
			program: () => ["/opt/pi-bolt/pi"],
			hasCloudflared: () => true,
			sleep: async () => {},
		};
	}

	it("install and pair refuse with the one line, and touch nothing", async () => {
		const { home, agentDir } = setup();
		for (const args of [["install"], ["install", "--no-tunnel"], ["pair"], ["pair", "--link"]]) {
			const commands: string[] = [];
			const lines: string[] = [];
			const code = await runServiceCommand(args, {
				agentDir,
				out: (line) => lines.push(line),
				system: fakeSystem(home, commands),
			});
			expect(code).toBe(1);
			expect(lines).toEqual([OFF]);
			expect(commands).toEqual([]);
		}
		expect(existsSync(join(home, "Library"))).toBe(false);
		expect(readdirSync(agentDir)).toEqual([]);
	});

	it("status says it is off, and uninstall removes a service an earlier version installed", async () => {
		const { home, agentDir } = setup();
		const plist = join(home, "Library", "LaunchAgents", `${LAUNCH_AGENT_LABEL}.plist`);
		writeJson(plist, "earlier install");
		const commands: string[] = [];
		const lines: string[] = [];
		const run = (args: string[]) =>
			runServiceCommand(args, { agentDir, out: (line) => lines.push(line), system: fakeSystem(home, commands) });
		await run(["status"]);
		expect(lines[0]).toBe(OFF);
		expect(lines[1]).toBe(`Installed: LaunchAgent ${LAUNCH_AGENT_LABEL} (not loaded)`);
		lines.length = 0;
		commands.length = 0;
		expect(await run(["uninstall"])).toBe(0);
		expect(commands).toEqual([`launchctl bootout gui/501/${LAUNCH_AGENT_LABEL}`]);
		expect(existsSync(plist)).toBe(false);
		expect(lines.slice(0, 2)).toEqual(["Stopped the remote control service and removed:", `  ${plist}`]);
	});

	it("names only commands that work: status and uninstall point at the enable step", async () => {
		const { home, agentDir } = setup();
		const lines: string[] = [];
		const run = (args: string[]) =>
			runServiceCommand(args, { agentDir, out: (line) => lines.push(line), system: fakeSystem(home, []) });
		await run(["status"]);
		expect(lines.slice(0, 2)).toEqual([OFF, "Not installed."]);
		lines.length = 0;
		await run(["uninstall"]);
		expect(lines.at(-1)).toBe(
			"Paired phones stay paired; your settings are unchanged. To revoke a phone, turn remote control on (pi remote-control enable), then run /remote-control devices.",
		);
		expect(lines.join("\n")).not.toMatch(/service install|\/remote-control devices revokes/);
	});

	describe("restart (what the installers run after an update)", () => {
		/** A service manager that answers as if the service ran (or not), and records every command. */
		function managerSystem(platform: NodeJS.Platform, home: string, running: boolean, commands: string[]) {
			return {
				...fakeSystem(home, commands),
				platform,
				run: async (command: string, args: string[]) => {
					commands.push([command, ...args].join(" "));
					const query = args[0] === "print" || args.includes("is-active") || args[0] === "/Query";
					if (!query) return { code: 0, stdout: "", stderr: "" };
					const stdout = !running
						? ""
						: platform === "darwin"
							? "state = running"
							: platform === "linux"
								? "active"
								: "Status:  Running";
					return { code: running || platform !== "darwin" ? 0 : 113, stdout, stderr: "" };
				},
			};
		}
		const restartCommands: Record<string, string[]> = {
			darwin: [`launchctl kickstart -k gui/501/${LAUNCH_AGENT_LABEL}`],
			linux: [`systemctl --user --no-block try-restart ${SYSTEMD_UNIT}`],
			win32: [`schtasks /End /TN ${WINDOWS_TASK_NAME}`, `schtasks /Run /TN ${WINDOWS_TASK_NAME}`],
		};

		for (const platform of ["darwin", "linux", "win32"] as const) {
			it(`${platform}: restarts an installed service that runs, whether remote control is on or off`, async () => {
				for (const enabled of [false, true]) {
					const { home, agentDir } = setup(enabled ? { remoteControl: { enabled: true } } : undefined);
					writeJson(serviceFilePath(platform, home, agentDir), "an earlier version's service");
					const commands: string[] = [];
					const lines: string[] = [];
					const code = await runServiceCommand(["restart"], {
						agentDir,
						out: (line) => lines.push(line),
						system: managerSystem(platform, home, true, commands),
					});
					expect(code).toBe(0);
					const queries = commands.filter((command) => !restartCommands[platform].includes(command));
					expect(commands.slice(queries.length)).toEqual(restartCommands[platform]);
					expect(lines[0]).toMatch(/^Restarted the remote control service: /);
				}
			});

			it(`${platform}: never starts or installs one that does not run or is not installed`, async () => {
				const { home, agentDir } = setup({ remoteControl: { enabled: true } });
				const commands: string[] = [];
				const lines: string[] = [];
				const run = (running: boolean) =>
					runServiceCommand(["restart"], {
						agentDir,
						out: (line) => lines.push(line),
						system: managerSystem(platform, home, running, commands),
					});
				expect(await run(true)).toBe(0);
				expect(commands).toEqual([]);
				expect(lines).toEqual(["The remote control service is not installed: nothing to restart."]);
				writeJson(serviceFilePath(platform, home, agentDir), "installed");
				lines.length = 0;
				expect(await run(false)).toBe(0);
				expect(lines).toEqual(["The remote control service does not run: nothing to restart."]);
				for (const command of commands) {
					expect(command).not.toMatch(/kickstart|bootstrap|restart|\/Run|\/Create|\benable\b/);
				}
			});
		}

		it("is run by the installers and the Windows update after the step that runs the new executable, ignoring its result", () => {
			const root = resolve(__dirname, "../../..");
			const installSh = readFileSync(join(root, "install.sh"), "utf8");
			expect(installSh).toContain(
				`(cd "$INSTALL" && "$INSTALL/$NAME/pi" list >/dev/null 2>&1) || true\n\t# A remote control service that runs (an earlier version's) is restarted, so it runs this version; nothing when none runs.\n\t(cd "$INSTALL" && "$INSTALL/$NAME/pi" service restart >/dev/null 2>&1) || true\n`,
			);
			const installPs1 = readFileSync(join(root, "install.ps1"), "utf8");
			expect(installPs1).toMatch(
				/try \{ & \$exe list \*> \$null \} catch \{ \} finally \{ Pop-Location \}\r?\n.*\r?\n\tPush-Location -LiteralPath \$state\.Dir\r?\n\ttry \{ & \$exe service restart \*> \$null \} catch \{ \} finally \{ Pop-Location \}\r?\n/,
			);
			const windowsUpdate = readFileSync(join(root, "packages/coding-agent/src/pi-bolt-update-windows.ts"), "utf8");
			expect(windowsUpdate).toContain(
				'spawnSync(join(dir, "pi-bolt.exe"), ["service", "restart"], { cwd: dir, stdio: "ignore" });',
			);
		});
	});

	it("the service started by the service manager logs why it does not serve, and exits 0 at once", async () => {
		const { home, agentDir } = setup();
		const cliPath = resolve(__dirname, "../src/cli.ts");
		const resolver = pathToFileURL(resolve(__dirname, "../src/experimental/source-resolver.ts")).href;
		const code = await new Promise<number | null>((done, fail) => {
			const child = spawn(process.execPath, ["--import", resolver, cliPath, "service", "run"], {
				cwd: home,
				env: { ...process.env, HOME: home, USERPROFILE: home, [ENV_AGENT_DIR]: agentDir, PI_OFFLINE: "1" },
				stdio: "ignore",
			});
			const timer = setTimeout(() => child.kill("SIGKILL"), 30_000);
			child.on("error", fail);
			child.on("close", (exitCode) => {
				clearTimeout(timer);
				done(exitCode);
			});
		});
		// 0: launchd (KeepAlive SuccessfulExit false), systemd (Restart=on-failure) and the Windows task start it again
		// only after a failure.
		expect(code).toBe(0);
		// The log, and nothing else: no host key, no control channel, no listener.
		expect(readdirSync(join(agentDir, "remote-control"))).toEqual(["service.log"]);
		const log = readFileSync(join(agentDir, "remote-control", "service.log"), "utf8")
			.trim()
			.split("\n");
		expect(log).toHaveLength(1);
		expect(log[0]).toMatch(
			/ info {2}Pi-Bolt \S+ remote control service \(pid \d+\) does not serve: Remote control is off\. Turn it on with: pi remote-control enable, then run pi service install again$/,
		);
	}, 40_000);

	it("a service that serves stops when remote control is turned off", async () => {
		const { agentDir } = setup();
		const port = await new Promise<number>((done) => {
			const server = createServer();
			server.listen(0, "127.0.0.1", () => {
				const address = server.address();
				server.close(() => done(typeof address === "object" && address ? address.port : 0));
			});
		});
		let enabled = true;
		const onTurnedOff = vi.fn();
		const host = new ServiceHost({
			createRuntime: async () => {
				throw new Error("no session in this test");
			},
			agentDir,
			sessionDir: join(agentDir, "sessions"),
			settings: { port, tunnel: "off" },
			log: new ServiceLog(agentDir),
			version: "9.9.9",
			hostName: "test-host",
			bindAddress: "127.0.0.1",
			offline: true,
			endpoints: (listening) => [`ws://127.0.0.1:${listening}/rc`],
			pollMs: 20,
			isEnabled: () => enabled,
			onTurnedOff,
		});
		cleanups.push(() => host.stop());
		await host.start();
		expect(host.state).toBe("serving");
		await new Promise((done) => setTimeout(done, 100));
		expect(onTurnedOff).not.toHaveBeenCalled();
		enabled = false;
		const deadline = Date.now() + 5000;
		while (onTurnedOff.mock.calls.length === 0 && Date.now() < deadline) {
			await new Promise((done) => setTimeout(done, 20));
		}
		await new Promise((done) => setTimeout(done, 100));
		expect(onTurnedOff).toHaveBeenCalledOnce();
	});
});
