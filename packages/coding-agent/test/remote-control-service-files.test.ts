import { spawnSync } from "node:child_process";
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import { runServiceCommand, type ServiceSystem } from "../src/remote-control/service-cli.ts";
import {
	LAUNCH_AGENT_LABEL,
	launchAgentPlist,
	type ServiceSpec,
	SYSTEMD_UNIT,
	serviceFilePath,
	systemdUserUnit,
	WINDOWS_TASK_NAME,
	windowsQuote,
	windowsTaskFile,
	windowsTaskScript,
	windowsTaskScriptPath,
	windowsTaskXml,
} from "../src/remote-control/service-files.ts";

const spec: ServiceSpec = {
	program: ["/Users/me/Pi Bolt & co/pi", "service", "run"],
	env: { PATH: "/opt/homebrew/bin:/usr/bin", PI_CODING_AGENT_DIR: "/Users/me/agent" },
	home: "/Users/me",
	stderrPath: "/Users/me/.pi/agent/remote-control/service.err.log",
};

describe("service files", () => {
	describe("macOS LaunchAgent", () => {
		const plist = launchAgentPlist(spec);

		it("runs `service run` at login and again after a crash, not after a clean exit", () => {
			expect(plist).toContain(`<key>Label</key>\n\t<string>${LAUNCH_AGENT_LABEL}</string>`);
			expect(plist).toContain(
				"<array>\n\t\t<string>/Users/me/Pi Bolt &amp; co/pi</string>\n\t\t<string>service</string>\n\t\t<string>run</string>\n\t</array>",
			);
			expect(plist).toContain("<key>RunAtLoad</key>\n\t<true/>");
			expect(plist).toContain("<key>KeepAlive</key>\n\t<dict>\n\t\t<key>SuccessfulExit</key>\n\t\t<false/>");
			expect(plist).toContain("<key>PATH</key>\n\t\t<string>/opt/homebrew/bin:/usr/bin</string>");
			expect(plist).toContain(
				"<key>StandardErrorPath</key>\n\t<string>/Users/me/.pi/agent/remote-control/service.err.log",
			);
			expect(plist).toContain("<key>WorkingDirectory</key>\n\t<string>/Users/me</string>");
		});

		it.runIf(process.platform === "darwin")("is a valid property list (plutil)", () => {
			const dir = mkdtempSync(join(tmpdir(), "pi-plist-"));
			try {
				const file = join(dir, "agent.plist");
				writeFileSync(file, plist);
				const lint = spawnSync("plutil", ["-lint", file], { encoding: "utf8" });
				expect(lint.status, lint.stdout + lint.stderr).toBe(0);
				const json = spawnSync("plutil", ["-convert", "json", "-o", "-", file], { encoding: "utf8" });
				const parsed = JSON.parse(json.stdout) as Record<string, unknown>;
				expect(parsed.ProgramArguments).toEqual(spec.program);
				expect(parsed.EnvironmentVariables).toEqual(spec.env);
				expect(parsed.KeepAlive).toEqual({ SuccessfulExit: false });
			} finally {
				rmSync(dir, { recursive: true, force: true });
			}
		});
	});

	describe("systemd user unit", () => {
		it("quotes arguments, doubles % and $ where systemd expands them, and restarts on failure", () => {
			const unit = systemdUserUnit({
				...spec,
				program: ['/home/me/my "pi"/pi-bolt', "service", "run", "50%$HOME"],
				env: { PATH: "/usr/bin:/home/me/100%" },
				home: "/home/me",
			});
			expect(unit).toContain('ExecStart="/home/me/my \\"pi\\"/pi-bolt" "service" "run" "50%%$$HOME"');
			expect(unit).toContain('Environment="PATH=/usr/bin:/home/me/100%%"');
			expect(unit).toContain('WorkingDirectory="/home/me"');
			expect(unit).toContain("Restart=on-failure");
			expect(unit).toContain("[Install]\nWantedBy=default.target");
		});
	});

	describe("Windows scheduled task", () => {
		const script = "C:\\Users\\Me\\.pi\\agent\\remote-control\\service-task.cmd";
		const xml = windowsTaskXml({
			userId: "PC\\Me",
			conhost: "C:\\Windows\\System32\\conhost.exe",
			shell: "C:\\Windows\\System32\\cmd.exe",
			script,
			home: "C:\\Users\\Me",
		});

		it("starts at this user's logon with their own rights, with no console window and no time limit", () => {
			expect(xml).toContain("<LogonTrigger>\n      <Enabled>true</Enabled>\n      <UserId>PC\\Me</UserId>");
			expect(xml).toContain("<LogonType>InteractiveToken</LogonType>");
			expect(xml).toContain("<RunLevel>LeastPrivilege</RunLevel>");
			expect(xml).toContain("<Command>C:\\Windows\\System32\\conhost.exe</Command>");
			// The task runs the script that sets the environment; `call` keeps cmd /c from stripping the quotes.
			expect(xml).toContain(
				`<Arguments>--headless C:\\Windows\\System32\\cmd.exe /d /v:off /c call ${script}</Arguments>`,
			);
			expect(xml).toContain("<WorkingDirectory>C:\\Users\\Me</WorkingDirectory>");
			expect(xml).toContain("<ExecutionTimeLimit>PT0S</ExecutionTimeLimit>");
			expect(xml).toContain("<RestartOnFailure>");
			expect(xml).toContain(`<URI>\\${WINDOWS_TASK_NAME}</URI>`);
			expect(xml).not.toContain("HighestAvailable");
		});

		it("is written as UTF-16 LE with a byte order mark and CRLF lines, as schtasks reads it", () => {
			const file = windowsTaskFile(xml);
			expect([...file.subarray(0, 2)]).toEqual([0xff, 0xfe]);
			const text = file.subarray(2).toString("utf16le");
			expect(text.startsWith('<?xml version="1.0" encoding="UTF-16"?>\r\n')).toBe(true);
			expect(text.replace(/\r\n/g, "\n")).toBe(xml);
		});

		it("runs the service from a script that sets its environment: PATH and Pi's directories", () => {
			const text = windowsTaskScript({
				program: ["C:\\Users\\Me\\Pi Bolt\\pi-bolt.exe", "service", "run"],
				env: {
					PATH: "C:\\Windows\\System32;C:\\Tools & Co\\100%",
					PI_CODING_AGENT_DIR: "D:\\pi agent",
					PI_CODING_AGENT_SESSION_DIR: "D:\\sessions",
				},
				home: "C:\\Users\\Me",
				stderrPath: "unused",
			});
			expect(text.split("\r\n")).toEqual([
				"@echo off",
				"rem Pi-Bolt remote control: written by pi-bolt service install, removed by pi-bolt service uninstall.",
				"chcp 65001 >nul",
				'set "PATH=C:\\Windows\\System32;C:\\Tools & Co\\100%%"',
				'set "PI_CODING_AGENT_DIR=D:\\pi agent"',
				'set "PI_CODING_AGENT_SESSION_DIR=D:\\sessions"',
				'cd /d "C:\\Users\\Me"',
				'"C:\\Users\\Me\\Pi Bolt\\pi-bolt.exe" "service" "run"',
				"exit /b %ERRORLEVEL%",
				"",
			]);
			expect(() => windowsTaskScript({ ...spec, env: { PATH: 'C:\\a"b' } })).toThrow("a quote or line break");
		});

		it("quotes command-line arguments as CommandLineToArgvW reads them", () => {
			expect(windowsQuote("plain")).toBe("plain");
			expect(windowsQuote("")).toBe('""');
			expect(windowsQuote("C:\\Program Files\\x")).toBe('"C:\\Program Files\\x"');
			expect(windowsQuote('say "hi"')).toBe('"say \\"hi\\""');
			expect(windowsQuote("C:\\dir with space\\")).toBe('"C:\\dir with space\\\\"');
		});
	});

	it("puts each file in the user's own directories", () => {
		expect(serviceFilePath("darwin", "/Users/me", "/Users/me/.pi/agent")).toBe(
			`/Users/me/Library/LaunchAgents/${LAUNCH_AGENT_LABEL}.plist`,
		);
		expect(serviceFilePath("linux", "/home/me", "/home/me/.pi/agent")).toBe(
			`/home/me/.config/systemd/user/${SYSTEMD_UNIT}`,
		);
		expect(serviceFilePath("linux", "/home/me", "/x", { XDG_CONFIG_HOME: "/home/me/cfg" })).toBe(
			`/home/me/cfg/systemd/user/${SYSTEMD_UNIT}`,
		);
		expect(serviceFilePath("win32", "C:\\Users\\Me", "C:\\Users\\Me\\.pi\\agent")).toBe(
			"C:\\Users\\Me\\.pi\\agent\\remote-control\\service-task.xml",
		);
	});
});

describe("pi-bolt service install and uninstall", () => {
	const cleanups: Array<() => void> = [];

	afterEach(() => {
		while (cleanups.length > 0) cleanups.pop()?.();
	});

	function setup(platform: NodeJS.Platform) {
		const home = mkdtempSync(join(tmpdir(), "pi-service-home-"));
		cleanups.push(() => rmSync(home, { recursive: true, force: true }));
		const agentDir = join(home, ".pi", "agent");
		const commands: string[] = [];
		const lines: string[] = [];
		const system: Partial<ServiceSystem> = {
			platform,
			home,
			env: { PATH: "/usr/bin:/bin", SystemRoot: "C:\\Windows", PI_CODING_AGENT_DIR: agentDir },
			uid: 501,
			windowsUser: "PC\\Me",
			isTTY: false,
			run: async (command, args) => {
				commands.push([command, ...args].join(" "));
				// (launchctl print: the job is gone after bootout.)
				return { code: args[0] === "print" ? 113 : 0, stdout: "", stderr: "" };
			},
			confirm: async () => false,
			program: () => ["/opt/pi-bolt/pi"],
			hasCloudflared: () => true,
			sleep: async () => {},
		};
		const run = (args: string[]) => runServiceCommand(args, { agentDir, out: (line) => lines.push(line), system });
		return { home, agentDir, commands, lines, run };
	}

	/** Every file under `dir`, relative to it. */
	function filesUnder(dir: string): string[] {
		return readdirSync(dir, { recursive: true, withFileTypes: true })
			.filter((entry) => entry.isFile())
			.map((entry) => join(entry.parentPath, entry.name).slice(dir.length + 1))
			.sort();
	}

	it("macOS: writes the LaunchAgent, bootstraps it, and removes it again", async () => {
		const { home, agentDir, commands, run } = setup("darwin");
		expect(await run(["install"])).toBe(0);
		const plist = join(home, "Library", "LaunchAgents", `${LAUNCH_AGENT_LABEL}.plist`);
		expect(readFileSync(plist, "utf8")).toContain("<string>/opt/pi-bolt/pi</string>\n\t\t<string>service</string>");
		// An earlier install is unloaded first, and bootstrap waits until it is gone.
		expect(commands).toEqual([
			`launchctl bootout gui/501/${LAUNCH_AGENT_LABEL}`,
			`launchctl print gui/501/${LAUNCH_AGENT_LABEL}`,
			`launchctl bootstrap gui/501 ${plist}`,
		]);
		// launchd appends the process's own output to this file, keeping its mode.
		expect(statSync(join(agentDir, "remote-control", "service.err.log")).mode & 0o777).toBe(0o600);
		commands.length = 0;
		expect(await run(["uninstall"])).toBe(0);
		expect(commands).toEqual([`launchctl bootout gui/501/${LAUNCH_AGENT_LABEL}`]);
		expect(existsSync(plist)).toBe(false);
		expect(filesUnder(home).filter((file) => !file.startsWith(".pi/agent/remote-control/"))).toEqual([]);
	});

	it("Linux: writes the user unit, enables and starts it with systemctl --user, and removes it again", async () => {
		const { home, commands, run } = setup("linux");
		expect(await run(["install"])).toBe(0);
		const unit = join(home, ".config", "systemd", "user", SYSTEMD_UNIT);
		expect(readFileSync(unit, "utf8")).toContain('ExecStart="/opt/pi-bolt/pi" "service" "run"');
		expect(commands).toEqual([
			"systemctl --user daemon-reload",
			`systemctl --user enable ${SYSTEMD_UNIT}`,
			`systemctl --user restart ${SYSTEMD_UNIT}`,
		]);
		commands.length = 0;
		expect(await run(["uninstall"])).toBe(0);
		expect(commands).toEqual([`systemctl --user disable --now ${SYSTEMD_UNIT}`]);
		expect(existsSync(unit)).toBe(false);
		expect(filesUnder(home).filter((file) => !file.startsWith(".pi/agent/remote-control/"))).toEqual([]);
	});

	it("Windows: registers the task from its XML with schtasks (no administrator), and deletes it again", async () => {
		const { agentDir, commands, run } = setup("win32");
		expect(await run(["install"])).toBe(0);
		const xmlFile = serviceFilePath("win32", "", agentDir);
		const scriptFile = windowsTaskScriptPath(xmlFile);
		// The shell's PATH and agent directory reach the task through its script.
		const script = readFileSync(scriptFile, "utf8");
		expect(script).toContain('set "PATH=/usr/bin:/bin"\r\n');
		expect(script).toContain(`set "PI_CODING_AGENT_DIR=${agentDir}"\r\n`);
		expect(script).toContain('"/opt/pi-bolt/pi" "service" "run"\r\n');
		expect(readFileSync(xmlFile).subarray(2).toString("utf16le")).toContain(`/c call ${scriptFile}</Arguments>`);
		expect(commands).toEqual([
			`schtasks /Create /TN ${WINDOWS_TASK_NAME} /XML ${xmlFile} /F`,
			`schtasks /End /TN ${WINDOWS_TASK_NAME}`,
			`schtasks /Run /TN ${WINDOWS_TASK_NAME}`,
		]);
		expect(windowsTaskFile("x").subarray(0, 2)).toEqual(readFileSync(xmlFile).subarray(0, 2));
		expect(commands.some((command) => /\/RL HIGHEST|\/RU SYSTEM/i.test(command))).toBe(false);
		commands.length = 0;
		expect(await run(["uninstall"])).toBe(0);
		expect(commands).toEqual([
			`schtasks /End /TN ${WINDOWS_TASK_NAME}`,
			`schtasks /Delete /TN ${WINDOWS_TASK_NAME} /F`,
		]);
		expect(existsSync(xmlFile)).toBe(false);
		expect(existsSync(scriptFile)).toBe(false);
	});

	it("--no-tunnel sets remoteControl.service.tunnel to off; uninstall leaves settings alone", async () => {
		const { agentDir, run, lines } = setup("darwin");
		expect(await run(["install", "--no-tunnel"])).toBe(0);
		const settings = JSON.parse(readFileSync(join(agentDir, "settings.json"), "utf8"));
		expect(settings.remoteControl.service.tunnel).toBe("off");
		expect(lines.some((line) => line.includes("tunnel off"))).toBe(true);
		await run(["uninstall"]);
		expect(existsSync(join(agentDir, "settings.json"))).toBe(true);
	});

	it("says how to get cloudflared when the tunnel needs it, without prompting outside a terminal", async () => {
		const { home, agentDir, lines } = setup("linux");
		let asked = 0;
		const code = await runServiceCommand(["install"], {
			agentDir,
			out: (line) => lines.push(line),
			system: {
				platform: "linux",
				home,
				env: { PATH: "/usr/bin" },
				isTTY: false,
				run: async () => ({ code: 0, stdout: "", stderr: "" }),
				confirm: async () => {
					asked++;
					return true;
				},
				program: () => ["/opt/pi-bolt/pi"],
				hasCloudflared: () => false,
				sleep: async () => {},
			},
		});
		expect(code).toBe(0);
		expect(asked).toBe(0);
		expect(lines.some((line) => line.includes("serves the local network and Tailscale only"))).toBe(true);
	});

	it("reports a service manager failure", async () => {
		const { home, agentDir, lines } = setup("linux");
		const code = await runServiceCommand(["install"], {
			agentDir,
			out: (line) => lines.push(line),
			system: {
				platform: "linux",
				home,
				env: {},
				run: async (_command, args) =>
					args.includes("daemon-reload")
						? { code: 1, stdout: "", stderr: "Failed to connect to bus: No medium found" }
						: { code: 0, stdout: "", stderr: "" },
				program: () => ["/opt/pi-bolt/pi"],
				hasCloudflared: () => true,
				sleep: async () => {},
			},
		});
		expect(code).toBe(1);
		expect(lines.at(-1)).toBe(
			"Error: systemctl --user daemon-reload failed (1): Failed to connect to bus: No medium found",
		);
	});
});
