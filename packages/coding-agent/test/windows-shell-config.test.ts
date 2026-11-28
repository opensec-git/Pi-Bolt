import type * as ChildProcess from "node:child_process";
import type * as Fs from "node:fs";
import { existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createLocalShellOperations } from "../src/core/tools/bash.ts";
import { getShellConfig, SHELL_COMMAND_ENV_VAR, type ShellConfig } from "../src/utils/shell.ts";

const { accessSyncMock, spawnSyncMock, statSyncMock } = vi.hoisted(() => ({
	accessSyncMock: vi.fn(),
	spawnSyncMock: vi.fn(),
	statSyncMock: vi.fn(),
}));

vi.mock("node:fs", async (importOriginal) => ({
	...(await importOriginal<typeof Fs>()),
	accessSync: accessSyncMock,
	statSync: statSyncMock,
	lstatSync: statSyncMock,
}));

vi.mock("child_process", async (importOriginal) => ({
	...(await importOriginal<typeof ChildProcess>()),
	spawnSync: spawnSyncMock,
}));

const platformDescriptor = Object.getOwnPropertyDescriptor(process, "platform")!;
const hostPlatform = process.platform;

/** What a Windows bash build (Git Bash, MSYS2, Cygwin) resolves to: the command goes in SHELL_COMMAND_ENV_VAR. */
function envTransport(shell: string): ShellConfig {
	return { shell, args: ["-c", expect.stringContaining(SHELL_COMMAND_ENV_VAR)], commandTransport: "env" };
}

beforeEach(() => {
	Object.defineProperty(process, "platform", { configurable: true, value: "win32" });
	accessSyncMock.mockReset();
	statSyncMock.mockReset().mockReturnValue(undefined);
	spawnSyncMock.mockReset().mockReturnValue({ status: 1, stdout: "" });
});

afterEach(() => {
	Object.defineProperty(process, "platform", platformDescriptor);
	vi.unstubAllEnvs();
});

// #9490: identify legacy WSL launchers under the actual Windows directory.
describe("legacy WSL bash detection", () => {
	it.each([
		["D:\\WinNT\\", "d:/WINNT/System32/BASH.EXE", true],
		["D:\\WinNT", "D:\\WinNT\\Sysnative\\bash.exe", true],
		[undefined, "E:\\Windows\\System32\\bash.exe", true],
		["D:\\WinNT", "D:\\Git\\bin\\bash.exe", false],
		["D:\\WinNT", "E:\\Windows\\System32\\bash.exe", false],
		["WinNT", "C:\\Windows\\System32\\bash.exe", false],
	] as const)("resolves %j / %j to stdin=%j", (systemRoot, shell, stdin) => {
		vi.stubEnv("SystemRoot", systemRoot);

		expect(getShellConfig(shell)).toEqual(
			stdin ? { shell, args: ["-s"], commandTransport: "stdin" } : envTransport(shell),
		);
	});
});

// #12408: find Git Bash of a Git for Windows installed outside Program Files through git on PATH.
describe("Git Bash discovery through git on PATH", () => {
	const git = "G:\\Programmi Aggiuntivi\\Git\\cmd\\git.exe";
	const gitBash = "G:\\Programmi Aggiuntivi\\Git\\bin\\bash.exe";
	const wslBash = "C:\\Windows\\System32\\bash.exe";

	beforeEach(() => {
		vi.stubEnv("ProgramFiles", "C:\\Program Files");
		vi.stubEnv("ProgramFiles(x86)", "C:\\Program Files (x86)");
		vi.stubEnv("SystemRoot", "C:\\Windows");
	});

	function installed(...paths: string[]): void {
		accessSyncMock.mockImplementation((path: string) => {
			if (!paths.includes(path)) throw Object.assign(new Error("not found"), { code: "ENOENT" });
		});
		statSyncMock.mockImplementation((path: string) =>
			paths.includes(path) ? { isFile: () => true, isDirectory: () => false } : undefined,
		);
	}

	// Pi-Bolt looks programs up in PATH's directories itself, not with `where`.
	function onPath(gitShellPath: string | null): void {
		vi.stubEnv("PATH", "G:\\Programmi Aggiuntivi\\Git\\cmd;C:\\Windows\\System32");
		spawnSyncMock.mockImplementation((command: string, args: string[]) => {
			if (command === git && args.join(" ") === "var GIT_SHELL_PATH") {
				return gitShellPath === null ? { status: 1, stdout: "" } : { status: 0, stdout: `${gitShellPath}\n` };
			}
			throw new Error(`unexpected spawn: ${command} ${args.join(" ")}`);
		});
	}

	it("prefers Git Bash of git on PATH over the WSL launcher on PATH", () => {
		installed(git, gitBash, wslBash);
		onPath("G:/Programmi Aggiuntivi/Git/usr/bin/sh.exe");

		expect(getShellConfig()).toEqual(envTransport(gitBash));
	});

	it("keeps Git Bash under ProgramFiles first without running git", () => {
		const programFilesBash = "C:\\Program Files\\Git\\bin\\bash.exe";
		installed(programFilesBash, git, gitBash);
		onPath("G:/Programmi Aggiuntivi/Git/usr/bin/sh.exe");

		expect(getShellConfig()).toEqual(envTransport(programFilesBash));
		expect(spawnSyncMock).not.toHaveBeenCalled();
	});

	it.each([
		["git without GIT_SHELL_PATH (before 2.47)", null],
		["a shell outside a Git for Windows layout", "G:/Tools/sh.exe"],
		["a Git for Windows root without its Bash launcher", "G:/Programmi Aggiuntivi/Other/usr/bin/sh.exe"],
	])("falls back to bash.exe on PATH for %s", (_name, gitShellPath) => {
		installed(git, gitBash, wslBash);
		onPath(gitShellPath);

		expect(getShellConfig()).toEqual({ shell: wslBash, args: ["-s"], commandTransport: "stdin" });
	});
});

// Upstream #12340 (PR #12369): the Cygwin runtime of Windows bash builds silently cuts quoted argv arguments after
// about 8 KB and splits unquoted ones at newlines.
describe("Windows bash command transport", () => {
	it.each([
		["C:\\Program Files\\Git\\bin\\bash.exe", "env"],
		["C:\\msys64\\usr\\bin\\bash", "env"],
		// (Pi-Bolt takes the WSL Store alias for WSL's launcher, which reads the command from stdin.)
		["C:\\Users\\me\\AppData\\Local\\Microsoft\\WindowsApps\\bash.exe", "stdin"],
		["C:\\Users\\me\\AppData\\Local\\Other\\WindowsApps\\bash.exe", undefined],
		["C:\\Program Files\\Git\\usr\\bin\\dash.exe", undefined],
		// (Not pwsh.exe: Pi-Bolt refuses PowerShell and cmd.exe for shellPath.)
		["C:\\msys64\\usr\\bin\\zsh.exe", undefined],
	] as const)("resolves %j to transport %j", (shell, transport) => {
		expect(getShellConfig(shell).commandTransport).toBe(transport);
	});

	it.skipIf(hostPlatform === "win32")("keeps argv transport off Windows", () => {
		Object.defineProperty(process, "platform", platformDescriptor);

		expect(getShellConfig("/usr/bin/bash")).toEqual({ shell: "/usr/bin/bash", args: ["-c"] });
	});

	const hostBash = hostPlatform === "win32" ? `${process.env.ProgramFiles}\\Git\\bin\\bash.exe` : "/bin/bash";

	async function runCommand(
		command: string,
		config?: ShellConfig,
	): Promise<{ exitCode: number | null; output: string }> {
		const shellConfig = config ?? getShellConfig(hostBash);
		Object.defineProperty(process, "platform", platformDescriptor);
		const chunks: Buffer[] = [];
		const operations = createLocalShellOperations("bash", () => shellConfig);
		const { exitCode } = await operations.exec(command, tmpdir(), { onData: (data) => chunks.push(data) });
		return { exitCode, output: Buffer.concat(chunks).toString("utf8") };
	}

	it.skipIf(!existsSync(hostBash))("runs commands longer than the argv truncation limit in full", async () => {
		const command = `printf 'START\\n'; # ${"a ".repeat(10_000)}\nprintf 'DONE\\n'`;

		expect(await runCommand(command)).toEqual({ exitCode: 0, output: "START\nDONE\n" });
	});

	it.skipIf(!existsSync(hostBash))("runs newline-separated commands without spaces in full", async () => {
		// Split at the newline, `bash -c true` would run with `$0` set to `false` and exit 0.
		expect(await runCommand("true\nfalse")).toEqual({ exitCode: 1, output: "" });
	});

	it.skipIf(!existsSync(hostBash))("keeps argv command semantics for status, stdin and exit", async () => {
		const command = `printf 'status=%s\\n' "$?"; [ -c /dev/stdin ] && echo stdin=device\nexit 7`;

		expect(await runCommand(command)).toEqual({ exitCode: 7, output: "status=0\nstdin=device\n" });
	});

	it.skipIf(!existsSync(hostBash))("hides the command variable from the command and its children", async () => {
		const command = `printf '%s\\n' "\${${SHELL_COMMAND_ENV_VAR}-unset}"; env | grep -c ${SHELL_COMMAND_ENV_VAR}`;

		expect(await runCommand(command)).toEqual({ exitCode: 1, output: "unset\n0\n" });
	});

	it.skipIf(!existsSync(hostBash))("fails when a caller appends the command to args instead", async () => {
		const config = getShellConfig(hostBash);
		const argvConfig: ShellConfig = { ...config, commandTransport: "argv" };

		expect((await runCommand("echo appended", argvConfig)).exitCode).not.toBe(0);
	});
});
