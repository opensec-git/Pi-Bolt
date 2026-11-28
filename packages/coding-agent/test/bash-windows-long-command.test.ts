import type * as ChildProcess from "node:child_process";
import { EventEmitter } from "node:events";
import type * as Fs from "node:fs";
import { tmpdir } from "node:os";
import type * as Path from "node:path";
import { PassThrough } from "node:stream";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { resolveConfigValueUncached } from "../src/core/resolve-config-value.ts";
import { createLocalBashOperations } from "../src/core/tools/bash.ts";
import { MSYS_SHELL_GROUP_VARIABLE, SHELL_COMMAND_ENV_VAR } from "../src/utils/shell.ts";

// Upstream #12340 (PR #12369): the Cygwin runtime of Git Bash, MSYS2 and Cygwin cuts a quoted command-line argument after
// 8,186 characters, so `bash -c <command>` ran the first part of a long command and exited 0. The bash tool and `!command`
// config values now pass the command in an environment variable. These tests run the Windows code with spawn mocked, as
// there is no Windows host here; windows-shell-config.test.ts runs real commands through Git Bash on the Windows CI runner.

// Git for Windows: bin\bash.exe is the launcher, usr\bin\bash.exe the Cygwin-runtime bash, mingw64 its one subsystem.
const { spawnMock, spawnSyncMock, accessSyncMock, GIT_FILES } = vi.hoisted(() => {
	const git = "C:\\Program Files\\Git";
	return {
		spawnMock: vi.fn(),
		spawnSyncMock: vi.fn(),
		accessSyncMock: vi.fn(),
		GIT_FILES: {
			launcher: `${git}\\bin\\bash.exe`,
			bash: `${git}\\usr\\bin\\bash.exe`,
			all: new Set([
				`${git}\\bin\\bash.exe`,
				`${git}\\usr\\bin\\bash.exe`,
				`${git}\\usr\\bin\\msys-2.0.dll`,
				`${git}\\mingw64\\bin\\git.exe`,
			]),
		},
	};
});

vi.mock("node:fs", async (importOriginal) => {
	const actual = await importOriginal<typeof Fs>();
	return {
		...actual,
		accessSync: accessSyncMock,
		existsSync: (path: Fs.PathLike) => GIT_FILES.all.has(String(path)) || actual.existsSync(path),
	};
});

// Windows paths, also on the Linux and macOS runners: shell.ts finds Git's bash beside its launcher with node:path.
vi.mock("node:path", async (importOriginal) => {
	const actual = await importOriginal<typeof Path>();
	return { ...actual.win32, default: actual.win32, win32: actual.win32, posix: actual.posix };
});

vi.mock("child_process", async (importOriginal) => ({
	...(await importOriginal<typeof ChildProcess>()),
	spawn: spawnMock,
	spawnSync: spawnSyncMock,
}));

const platformDescriptor = Object.getOwnPropertyDescriptor(process, "platform")!;

const LAUNCHER = GIT_FILES.launcher;
const MSYS_BASH = GIT_FILES.bash;

// Longer than the 8,186 characters the Cygwin runtime keeps of a quoted argument, with what that parser treats specially.
const LONG_COMMAND = `printf 'START\\n'\n# ${"a ".repeat(10_000)}\nprintf '%s\\n' "C:\\\\Users\\\\x" 'q"q'\nprintf 'DONE\\n'`;

interface SpawnCall {
	file: string;
	args: string[];
	options: ChildProcess.SpawnOptions;
}

function fakeChild(): ChildProcess.ChildProcess {
	const child = new EventEmitter() as ChildProcess.ChildProcess;
	const stdout = new PassThrough();
	const stderr = new PassThrough();
	Object.assign(child, { pid: 4242, stdout, stderr, stdin: null, signalCode: null });
	setImmediate(() => {
		child.emit("exit", 0, null);
		stdout.end();
		stderr.end();
		child.emit("close", 0, null);
	});
	return child;
}

async function runBashTool(command: string, shellPath: string): Promise<SpawnCall> {
	await createLocalBashOperations({ shellPath }).exec(command, tmpdir(), { onData: () => {} });
	expect(spawnMock).toHaveBeenCalledTimes(1);
	const [file, args, options] = spawnMock.mock.calls[0] as [string, string[], ChildProcess.SpawnOptions];
	return { file, args, options };
}

beforeEach(() => {
	Object.defineProperty(process, "platform", { configurable: true, value: "win32" });
	vi.stubEnv("SystemRoot", "C:\\Windows");
	accessSyncMock.mockReset();
	spawnMock.mockReset().mockImplementation(fakeChild);
	spawnSyncMock.mockReset().mockReturnValue({ status: 0, stdout: "resolved\n", stderr: "", pid: 1, output: [] });
});

afterEach(() => {
	Object.defineProperty(process, "platform", platformDescriptor);
	vi.unstubAllEnvs();
});

describe("Windows Git Bash: long commands (upstream #12340)", () => {
	it("passes the bash tool's command in the environment, not on the command line", async () => {
		const { file, args, options } = await runBashTool(LONG_COMMAND, LAUNCHER);

		// Pi-Bolt still starts Git's bash directly and quotes for its parser (getDirectGitBash, msysShellSpawn).
		expect(file).toBe(MSYS_BASH);
		expect(options.windowsVerbatimArguments).toBe(true);
		expect(options.argv0).toBe(`"${MSYS_BASH}"`);
		expect(args[0]).toBe('"-c"');
		expect(args).toHaveLength(2);
		// The command line stays short whatever the command's length: only the fixed runner is on it.
		expect(args.join(" ").length).toBeLessThan(400);
		expect(args.some((arg) => arg.includes("START") || arg.includes("DONE"))).toBe(false);
		expect(args[1]).toContain(SHELL_COMMAND_ENV_VAR);

		const env = options.env as NodeJS.ProcessEnv;
		expect(env[SHELL_COMMAND_ENV_VAR]).toBe(LONG_COMMAND);
		// The job handling still finds the command's processes by its group variable, and the launcher's variables are set.
		expect(env[MSYS_SHELL_GROUP_VARIABLE]).toMatch(/^[0-9a-f-]{36}$/);
		expect(env.MSYSTEM).toBe("MINGW64");
		expect(options.stdio).toEqual(["ignore", "pipe", "pipe"]);
	});

	it("passes short commands the same way", async () => {
		const { args, options } = await runBashTool("echo hi", LAUNCHER);

		expect(args.some((arg) => arg.includes("echo hi"))).toBe(false);
		expect((options.env as NodeJS.ProcessEnv)[SHELL_COMMAND_ENV_VAR]).toBe("echo hi");
	});

	it("keeps stdin for WSL's launcher, which does not pass Windows variables into Linux", async () => {
		const wsl = "C:\\Windows\\System32\\bash.exe";
		const { file, args, options } = await runBashTool(LONG_COMMAND, wsl);

		expect(file).toBe(wsl);
		expect(args).toEqual(["-s"]);
		expect((options.env as NodeJS.ProcessEnv)[SHELL_COMMAND_ENV_VAR]).toBeUndefined();
		expect(options.stdio).toEqual(["pipe", "pipe", "pipe"]);
	});

	it("passes a `!command` config value's command in the environment too", () => {
		const command = `${LONG_COMMAND}\nprintf resolved`;
		vi.stubEnv("ProgramFiles", "C:\\Program Files");

		expect(resolveConfigValueUncached(`!${command}`)).toBe("resolved");
		expect(spawnSyncMock).toHaveBeenCalledTimes(1);
		const [file, args, options] = spawnSyncMock.mock.calls[0] as [string, string[], ChildProcess.SpawnSyncOptions];
		expect(file).toBe(MSYS_BASH);
		expect(args).toHaveLength(2);
		expect(args.some((arg) => arg.includes("START"))).toBe(false);
		expect((options.env as NodeJS.ProcessEnv)[SHELL_COMMAND_ENV_VAR]).toBe(command);
		expect((options.env as NodeJS.ProcessEnv).MSYSTEM).toBe("MINGW64");
	});
});
