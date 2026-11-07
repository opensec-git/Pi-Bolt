/**
 * What `pi-bolt service install` writes for each service manager, as plain functions of their inputs: a launchd
 * LaunchAgent (macOS), a systemd user unit (Linux), and a Task Scheduler task (Windows). All three run the service as the
 * user, at login, from files in the user's own directories, and start it again after a crash.
 */

import { posix, win32 } from "node:path";

/** The LaunchAgent's label (and the plist's file name). */
export const LAUNCH_AGENT_LABEL = "in.opensec.pi-bolt.remote-control";
export const SYSTEMD_UNIT = "pi-bolt-remote-control.service";
export const WINDOWS_TASK_NAME = "Pi-Bolt Remote Control";

export type ServicePlatform = "darwin" | "linux" | "win32";

export interface ServiceSpec {
	/** The command the service manager runs: the executable, then its arguments (ending in `service run`). */
	program: string[];
	/** Environment of the service: PATH (a login item gets a bare one otherwise), Pi's directory variables when set. */
	env: Record<string, string>;
	home: string;
	/** Where the process's own output goes (macOS; systemd keeps it in the journal). */
	stderrPath: string;
}

export interface WindowsTaskSpec {
	/** `DOMAIN\user`: the task runs for this user only, when they log on. */
	userId: string;
	/** `conhost.exe`, which runs the service without a console window (`--headless`). */
	conhost: string;
	/** `cmd.exe`, which runs [script]. */
	shell: string;
	/** The script [windowsTaskScript] wrote: a task cannot set environment variables itself. */
	script: string;
	home: string;
}

/**
 * The service file's path for this platform. On Windows the task's XML is kept in the agent directory, with the script
 * the task runs next to it ([windowsTaskScriptPath]).
 */
export function serviceFilePath(
	platform: ServicePlatform,
	home: string,
	agentDir: string,
	env: Record<string, string | undefined> = {},
): string {
	if (platform === "darwin") return posix.join(home, "Library", "LaunchAgents", `${LAUNCH_AGENT_LABEL}.plist`);
	if (platform === "linux") {
		const config = env.XDG_CONFIG_HOME?.startsWith("/") ? env.XDG_CONFIG_HOME : posix.join(home, ".config");
		return posix.join(config, "systemd", "user", SYSTEMD_UNIT);
	}
	// (Joined in the agent directory's own style, so each platform's install also runs in tests on another OS.)
	const path = /^[A-Za-z]:[\\/]|^\\\\/.test(agentDir) ? win32 : posix;
	return path.join(agentDir, "remote-control", "service-task.xml");
}

/** The script the Windows task runs, next to its XML. */
export function windowsTaskScriptPath(taskXmlPath: string): string {
	return taskXmlPath.replace(/\.xml$/, ".cmd");
}

function xml(text: string): string {
	return text
		.replace(/&/g, "&amp;")
		.replace(/</g, "&lt;")
		.replace(/>/g, "&gt;")
		.replace(/"/g, "&quot;")
		.replace(/'/g, "&apos;");
}

/** A LaunchAgent: loaded at login (RunAtLoad), started again unless it exited cleanly (KeepAlive SuccessfulExit). */
export function launchAgentPlist(spec: ServiceSpec): string {
	const string = (value: string) => `<string>${xml(value)}</string>`;
	const env = Object.entries(spec.env)
		.map(([key, value]) => `\t\t<key>${xml(key)}</key>\n\t\t${string(value)}`)
		.join("\n");
	return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>Label</key>
	${string(LAUNCH_AGENT_LABEL)}
	<key>ProgramArguments</key>
	<array>
${spec.program.map((arg) => `\t\t${string(arg)}`).join("\n")}
	</array>
	<key>EnvironmentVariables</key>
	<dict>
${env}
	</dict>
	<key>WorkingDirectory</key>
	${string(spec.home)}
	<key>RunAtLoad</key>
	<true/>
	<key>KeepAlive</key>
	<dict>
		<key>SuccessfulExit</key>
		<false/>
	</dict>
	<key>StandardOutPath</key>
	${string(spec.stderrPath)}
	<key>StandardErrorPath</key>
	${string(spec.stderrPath)}
</dict>
</plist>
`;
}

/** systemd quoting: backslash and double quote escaped, `%` doubled (specifiers); `$` doubled where variables expand. */
function systemdQuote(value: string, expandsVariables: boolean): string {
	let quoted = value.replace(/\\/g, "\\\\").replace(/"/g, '\\"').replace(/%/g, "%%");
	if (expandsVariables) quoted = quoted.replace(/\$/g, "$$$$");
	return `"${quoted}"`;
}

/** A systemd user unit: started with the user's session (default.target), again after a failure. */
export function systemdUserUnit(spec: ServiceSpec): string {
	const env = Object.entries(spec.env)
		.map(([key, value]) => `Environment=${systemdQuote(`${key}=${value}`, false)}`)
		.join("\n");
	return `[Unit]
Description=Pi-Bolt remote control (serves paired phones)
After=network-online.target

[Service]
Type=simple
ExecStart=${spec.program.map((arg) => systemdQuote(arg, true)).join(" ")}
WorkingDirectory=${systemdQuote(spec.home, false)}
${env}
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
`;
}

/** Quotes one argument for a Windows command line (the rules CommandLineToArgvW reads back). */
export function windowsQuote(arg: string): string {
	if (arg !== "" && !/[\s"]/.test(arg)) return arg;
	let quoted = '"';
	let backslashes = 0;
	for (const char of arg) {
		if (char === "\\") {
			backslashes++;
			continue;
		}
		if (char === '"') quoted += `${"\\".repeat(backslashes * 2 + 1)}"`;
		else quoted += `${"\\".repeat(backslashes)}${char}`;
		backslashes = 0;
	}
	return `${quoted}${"\\".repeat(backslashes * 2)}"`;
}

/**
 * A Task Scheduler task for one user: it starts when that user logs on, with their own rights (no administrator), without
 * a time limit, and again after a failure.
 */
export function windowsTaskXml(spec: WindowsTaskSpec): string {
	// (`call`: cmd /c strips the quotes of a command line that starts with one.)
	const args = ["--headless", spec.shell, "/d", "/v:off", "/c", "call", spec.script].map(windowsQuote).join(" ");
	return `<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo>
    <Description>Pi-Bolt remote control: serves paired phones after you log on.</Description>
    <URI>\\${xml(WINDOWS_TASK_NAME)}</URI>
  </RegistrationInfo>
  <Triggers>
    <LogonTrigger>
      <Enabled>true</Enabled>
      <UserId>${xml(spec.userId)}</UserId>
    </LogonTrigger>
  </Triggers>
  <Principals>
    <Principal id="Author">
      <UserId>${xml(spec.userId)}</UserId>
      <LogonType>InteractiveToken</LogonType>
      <RunLevel>LeastPrivilege</RunLevel>
    </Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <AllowStartOnDemand>true</AllowStartOnDemand>
    <Enabled>true</Enabled>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
    <RestartOnFailure>
      <Interval>PT1M</Interval>
      <Count>3</Count>
    </RestartOnFailure>
  </Settings>
  <Actions Context="Author">
    <Exec>
      <Command>${xml(spec.conhost)}</Command>
      <Arguments>${xml(args)}</Arguments>
      <WorkingDirectory>${xml(spec.home)}</WorkingDirectory>
    </Exec>
  </Actions>
</Task>
`;
}

/** A value inside double quotes in a batch file: `%` doubled; a quote or line break cannot be written. */
function batchQuote(value: string): string {
	if (/["\r\n]/.test(value))
		throw new Error(`Cannot write to the Windows task's script (a quote or line break): ${value}`);
	return `"${value.replace(/%/g, "%%")}"`;
}

/**
 * The batch script the Windows task runs: it sets the service's environment (PATH, Pi's directory variables), which a
 * task cannot carry, then runs the service in the home folder. CRLF lines, UTF-8 (`chcp 65001` before any path).
 */
export function windowsTaskScript(spec: ServiceSpec): string {
	return [
		"@echo off",
		"rem Pi-Bolt remote control: written by pi-bolt service install, removed by pi-bolt service uninstall.",
		"chcp 65001 >nul",
		...Object.entries(spec.env).map(([key, value]) => `set ${batchQuote(`${key}=${value}`)}`),
		`cd /d ${batchQuote(spec.home)}`,
		spec.program.map(batchQuote).join(" "),
		"exit /b %ERRORLEVEL%",
		"",
	].join("\r\n");
}

/** The task XML as schtasks reads it: UTF-16 LE with a byte order mark. */
export function windowsTaskFile(xmlText: string): Buffer {
	return Buffer.concat([Buffer.from([0xff, 0xfe]), Buffer.from(xmlText.replace(/\n/g, "\r\n"), "utf16le")]);
}
