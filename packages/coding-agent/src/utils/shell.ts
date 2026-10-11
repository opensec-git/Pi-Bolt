import { existsSync } from "node:fs";
import { createRequire } from "node:module";
import { basename, delimiter, dirname, isAbsolute, join } from "node:path";
import { spawn, spawnSync } from "child_process";
import { getBinDir } from "../config.ts";
import { isProgramFile, windowsPathDirectories } from "./windows-path.ts";

export interface ShellConfig {
	shell: string;
	args: string[];
	commandTransport?: "argv" | "stdin";
}

/**
 * Find bash executable on PATH (cross-platform)
 */
function isLegacyWslBashPath(path: string): boolean {
	const normalized = path.replace(/\//g, "\\").toLowerCase();
	// System32's bash.exe, and the WSL Store app's alias in %LOCALAPPDATA%\Microsoft\WindowsApps (on the user's PATH whenever
	// WSL is installed from the Store): both start WSL's bash, which reads the command from stdin.
	return (
		/^[a-z]:\\windows\\(?:system32|sysnative)\\bash\.exe$/.test(normalized) ||
		/\\microsoft\\windowsapps\\bash\.exe$/.test(normalized)
	);
}

/**
 * Windows: Git Bash where Git for Windows is, other than Program Files: per user (%LOCALAPPDATA%\Programs\Git, the installer's
 * choice for a user without administrator rights), scoop's, and the Git whose git.exe is on PATH (a portable Git: only its cmd
 * folder is on PATH, never bash's). Before any bash.exe on PATH, which is WSL's launcher on most machines that have WSL.
 */
function gitBashCandidates(): string[] {
	const candidates: string[] = [];
	if (process.env.LOCALAPPDATA) candidates.push(join(process.env.LOCALAPPDATA, "Programs", "Git", "bin", "bash.exe"));
	if (process.env.USERPROFILE)
		candidates.push(join(process.env.USERPROFILE, "scoop", "apps", "git", "current", "bin", "bash.exe"));
	const git = findExecutableOnPath("git.exe");
	if (git) {
		// <git>\cmd\git.exe, <git>\mingw64\bin\git.exe or <git>\bin\git.exe: Git Bash is <git>\bin\bash.exe.
		let root = dirname(dirname(git));
		if (/^mingw(?:32|64)$/i.test(basename(root))) root = dirname(root);
		candidates.push(join(root, "bin", "bash.exe"));
	}
	return candidates;
}

function getBashShellConfig(shell: string): ShellConfig {
	return isLegacyWslBashPath(shell) ? { shell, args: ["-s"], commandTransport: "stdin" } : { shell, args: ["-c"] };
}

// Windows: what a PATH lookup found, by PATH and name. The shell tools look their shell up on every call, and starting
// `where` for it took a process start each time.
const foundOnWindowsPath = new Map<string, string>();

function findExecutableOnPath(executable: string, accept?: (file: string) => boolean): string | null {
	if (process.platform === "win32") {
		// Windows: the file in PATH's absolute directories, in order. (`where`, which this was, also looks in the working
		// directory first, so a project's own bash.exe or pwsh.exe would have been taken for the shell.)
		const path = process.env.PATH ?? "";
		const key = `${path}\0${executable}\0${accept ? "filtered" : ""}`;
		const cached = foundOnWindowsPath.get(key);
		if (cached && existsSync(cached)) return cached;
		for (const dir of windowsPathDirectories(path)) {
			if (!isAbsolute(dir)) continue;
			const file = join(dir, executable);
			if ((!accept || accept(file)) && isProgramFile(file)) {
				foundOnWindowsPath.set(key, file);
				return file;
			}
		}
		return null;
	}

	// Unix: Use 'which' and trust its output (handles Termux and special filesystems)
	try {
		const result = spawnSync("which", [executable], { encoding: "utf-8", timeout: 5000 });
		if (result.status === 0 && result.stdout) {
			const firstMatch = result.stdout.trim().split(/\r?\n/)[0];
			if (firstMatch) {
				return firstMatch;
			}
		}
	} catch {
		// Ignore errors
	}
	return null;
}

/**
 * Resolve shell configuration based on platform and an optional explicit shell path.
 * Resolution order:
 * 1. User-specified shellPath
 * 2. On Windows: Git Bash in known locations, then bash on PATH
 * 3. On Unix: /bin/bash, then bash on PATH, then fallback to sh
 */
export function getShellConfig(customShellPath?: string): ShellConfig {
	// 1. Check user-specified shell path
	if (customShellPath) {
		// Windows: cmd.exe and PowerShell are not a bash. Given `-c` and the command, cmd.exe printed its banner, ran nothing,
		// and exited 0, which reads as the command having succeeded.
		if (process.platform === "win32" && /^(?:cmd|powershell|pwsh)\.exe$/i.test(basename(customShellPath))) {
			throw new Error(
				`shellPath is ${customShellPath}, which is not a bash: the bash tool needs Git Bash (or another bash). ` +
					"PowerShell commands go through the powershell tool. Remove shellPath, or set it to a bash.exe.",
			);
		}
		if (existsSync(customShellPath)) {
			return getBashShellConfig(customShellPath);
		}
		throw new Error(`Custom shell path not found: ${customShellPath}`);
	}

	if (process.platform === "win32") {
		// 2. Try Git Bash in known locations
		const paths: string[] = [];
		const programFiles = process.env.ProgramFiles;
		if (programFiles) {
			paths.push(`${programFiles}\\Git\\bin\\bash.exe`);
		}
		const programFilesX86 = process.env["ProgramFiles(x86)"];
		if (programFilesX86) {
			paths.push(`${programFilesX86}\\Git\\bin\\bash.exe`);
		}

		paths.push(...gitBashCandidates());

		for (const path of paths) {
			if (existsSync(path)) {
				return getBashShellConfig(path);
			}
		}

		// 3. Fallback: search bash.exe on PATH (Cygwin, MSYS2, then WSL's launchers last)
		const bashOnPath = findExecutableOnPath("bash.exe", (file) => !isLegacyWslBashPath(file));
		if (bashOnPath) {
			return getBashShellConfig(bashOnPath);
		}
		const wslBash = findExecutableOnPath("bash.exe");
		if (wslBash) {
			return getBashShellConfig(wslBash);
		}

		throw new Error(
			`No bash shell found. Options:\n` +
				`  1. Install Git for Windows: https://git-scm.com/download/win\n` +
				`  2. Add your bash to PATH (Cygwin, MSYS2, etc.)\n` +
				"  3. Set shellPath in settings.json\n\n" +
				`Searched Git Bash in:\n${paths.map((p) => `  ${p}`).join("\n")}`,
		);
	}

	// Unix: try /bin/bash, then bash on PATH, then fallback to sh
	if (existsSync("/bin/bash")) {
		return getBashShellConfig("/bin/bash");
	}

	const bashOnPath = findExecutableOnPath("bash");
	if (bashOnPath) {
		return getBashShellConfig(bashOnPath);
	}

	return { shell: "sh", args: ["-c"] };
}

export const POWERSHELL_ARGS = ["-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command"] as const;

/** Resolve PowerShell on Windows, preferring PowerShell 7 when available. */
export function getPowerShellConfig(): ShellConfig {
	if (process.platform !== "win32") {
		throw new Error("The powershell tool is only available on Windows.");
	}

	const shell = findExecutableOnPath("pwsh.exe") ?? findExecutableOnPath("powershell.exe");
	if (!shell) {
		throw new Error("No PowerShell executable found. Install PowerShell or add powershell.exe/pwsh.exe to PATH.");
	}

	return { shell, args: [...POWERSHELL_ARGS] };
}

export function getShellEnv(): NodeJS.ProcessEnv {
	const binDir = getBinDir();
	const pathKey = Object.keys(process.env).find((key) => key.toLowerCase() === "path") ?? "PATH";
	const currentPath = process.env[pathKey] ?? "";
	const pathEntries = currentPath.split(delimiter).filter(Boolean);
	const hasBinDir = pathEntries.includes(binDir);
	const updatedPath = hasBinDir ? currentPath : [binDir, currentPath].filter(Boolean).join(delimiter);

	return {
		...process.env,
		[pathKey]: updatedPath,
	};
}

/**
 * Sanitize binary output for display/storage.
 * Removes characters that crash string-width or cause display issues:
 * - Control characters (except tab, newline, carriage return)
 * - Unicode interlinear annotation characters U+FFF9..U+FFFB (crash string-width due to a bug)
 */
export function sanitizeBinaryOutput(str: string): string {
	// All removed characters are single UTF-16 code units, so surrogate pairs are never split.
	return str.replace(/[\x00-\x08\x0B\x0C\x0E-\x1F\uFFF9-\uFFFB]/g, "");
}

/**
 * Detached child processes must be tracked so they can be killed on parent
 * shutdown signals (SIGHUP/SIGTERM).
 */
const trackedDetachedChildPids = new Set<number>();

/**
 * Windows, in a runtime with Bun's FFI, which needs the JIT (Pi-Bolt's -jit build, Pi on Bun): a job object for each shell a
 * tool starts, so that stopping the command (Escape, its timeout, Pi exiting) ends everything it started. `taskkill /T` alone
 * walks the tree by parent process, and misses a process whose parent has exited (Git Bash's `sleep 25; echo x`, `cd web &&
 * npm run dev`: a dev server left holding its port), or one started while it walks. The job is only ended when the command is
 * stopped; one that finishes leaves what it started in the background as before (no kill-on-close). Not available (the
 * default Pi-Bolt build, Node, or a call that fails): taskkill, and Git Bash's own processes by their group's variable
 * (killMsysGroup). A native program whose native parent has exited before the stop (a server a launcher started and left)
 * is reached by neither there, and keeps running.
 */
interface WindowsJobs {
	attach(pid: number): unknown;
	terminate(job: unknown): void;
	close(job: unknown): void;
}
let windowsJobs: WindowsJobs | null | undefined;
const windowsJobOfPid = new Map<number, unknown>();

function getWindowsJobs(): WindowsJobs | null {
	if (windowsJobs !== undefined) return windowsJobs;
	windowsJobs = null;
	if (process.platform !== "win32" || !("Bun" in globalThis)) return null;
	try {
		const { dlopen, FFIType } = createRequire(import.meta.url)("bun:ffi");
		const { symbols: kernel32 } = dlopen("kernel32.dll", {
			CreateJobObjectW: { args: [FFIType.ptr, FFIType.ptr], returns: FFIType.ptr },
			OpenProcess: { args: [FFIType.u32, FFIType.bool, FFIType.u32], returns: FFIType.ptr },
			AssignProcessToJobObject: { args: [FFIType.ptr, FFIType.ptr], returns: FFIType.bool },
			TerminateJobObject: { args: [FFIType.ptr, FFIType.u32], returns: FFIType.bool },
			CloseHandle: { args: [FFIType.ptr], returns: FFIType.bool },
		});
		const PROCESS_TERMINATE = 0x0001;
		const PROCESS_SET_QUOTA = 0x0100;
		windowsJobs = {
			attach(pid) {
				const job = kernel32.CreateJobObjectW(null, null);
				if (!job) return undefined;
				const processHandle = kernel32.OpenProcess(PROCESS_SET_QUOTA | PROCESS_TERMINATE, false, pid);
				const assigned = processHandle ? kernel32.AssignProcessToJobObject(job, processHandle) : false;
				if (processHandle) kernel32.CloseHandle(processHandle);
				if (!assigned) {
					kernel32.CloseHandle(job);
					return undefined;
				}
				return job;
			},
			terminate(job) {
				kernel32.TerminateJobObject(job, 1);
			},
			close(job) {
				kernel32.CloseHandle(job);
			},
		};
	} catch {
		windowsJobs = null;
	}
	return windowsJobs;
}

/**
 * Windows: the bash of a Cygwin-family shell (Git Bash, MSYS2, Cygwin) that sees that shell's processes in /proc: Git Bash's
 * bin\bash.exe is a launcher of usr\bin\bash.exe. Undefined for any other shell (PowerShell, cmd, WSL).
 */
export function getMsysBash(shell: string): string | undefined {
	if (process.platform !== "win32" || isLegacyWslBashPath(shell) || basename(shell).toLowerCase() !== "bash.exe") {
		return undefined;
	}
	for (const bash of [join(dirname(dirname(shell)), "usr", "bin", "bash.exe"), shell]) {
		const dir = dirname(bash);
		if (existsSync(bash) && (existsSync(join(dir, "msys-2.0.dll")) || existsSync(join(dir, "cygwin1.dll"))))
			return bash;
	}
	return undefined;
}

/**
 * Windows, a Cygwin-family bash: the arguments and spawn options that give it `args` and `command` byte for byte. Its runtime
 * parses its own command line, and inside quotes turns `\\` into `\`, so quoted the usual (MSVCRT) way every doubled backslash
 * in a command reached bash halved, even in single quotes and quoted heredocs: a JSON file written with `C:\\Users` came out
 * invalid, with the command reported as done. Quoted here for that parser, as verbatim arguments; the program's own name too,
 * which nothing else quotes then. Undefined for any other shell.
 */
export function msysShellSpawn(
	shell: string,
	args: readonly string[],
): { args: string[]; windowsVerbatimArguments: true; argv0: string } | undefined {
	if (!getMsysBash(shell)) return undefined;
	const quote = (arg: string) => `"${arg.replace(/\\/g, "\\\\").replace(/"/g, '\\"')}"`;
	// (A Windows path has no `"`; its backslashes are single, which the parser keeps.)
	return { args: args.map(quote), windowsVerbatimArguments: true, argv0: `"${shell}"` };
}

/**
 * A Cygwin-family shell's processes are found by an environment variable the tool gives the shell (every process it starts
 * inherits it): `taskkill /T` walks the tree by parent process, and a program bash started (`sleep 25; echo x`, `tail -f`)
 * is the child of a fork that has exited, which it never reaches. (Bash builtins only: this bash has no /usr/bin on PATH.)
 */
export const MSYS_SHELL_GROUP_VARIABLE = "PI_SHELL_GROUP";
const msysGroupOfPid = new Map<number, { bash: string; group: string }>();

function killMsysGroup(bash: string, group: string): void {
	const script =
		`for p in /proc/[0-9]*; do mapfile -d '' -t e 2>/dev/null < "$p/environ" || continue; ` +
		`for v in "\${e[@]}"; do if [[ $v == "${MSYS_SHELL_GROUP_VARIABLE}=${group}" ]]; then kill -9 "\${p#/proc/}" 2>/dev/null; break; fi; done; done`;
	try {
		const child = spawn(bash, ["-c", script], { stdio: "ignore", detached: true, windowsHide: true });
		child.once("error", () => {});
	} catch {}
}

export function trackDetachedChildPid(pid: number, msys?: { bash: string; group: string }): void {
	trackedDetachedChildPids.add(pid);
	if (msys) msysGroupOfPid.set(pid, msys);
	if (process.platform === "win32") {
		try {
			const job = getWindowsJobs()?.attach(pid);
			if (job) windowsJobOfPid.set(pid, job);
		} catch {
			// No job: taskkill alone ends the tree.
		}
	}
}

export function untrackDetachedChildPid(pid: number): void {
	trackedDetachedChildPids.delete(pid);
	msysGroupOfPid.delete(pid);
	const job = windowsJobOfPid.get(pid);
	if (job !== undefined) {
		windowsJobOfPid.delete(pid);
		try {
			getWindowsJobs()?.close(job);
		} catch {}
	}
}

export function killTrackedDetachedChildren(): void {
	for (const pid of trackedDetachedChildPids) {
		killProcessTree(pid, { sync: true });
	}
	trackedDetachedChildPids.clear();
}

/**
 * Kill a process and all its children (cross-platform)
 */
export function killProcessTree(pid: number, options?: { sync?: boolean }): void {
	if (process.platform === "win32") {
		// taskkill first, while the shell is there to walk the tree from; then, once it is done, what it does not reach:
		// - the job of the tool's shell (see getWindowsJobs): everything started in it, whatever its parent. (Ended first, it
		//   would end the shell, and a process the shell started before it was in the job, outside it, would then be reached
		//   by neither: under load, Git Bash's launcher can have started bash, and bash a program, by then.)
		// - a Cygwin-family shell's processes, by its group's variable (see killMsysGroup), whatever their parents. (Before
		//   taskkill, it would end the processes that stand for a native program bash started, the parents of that program's
		//   own tree, which taskkill would then no longer reach.)
		// (The job is this kill's from here: the shell's exit, which taskkill brings, does not close it.)
		const job = windowsJobOfPid.get(pid);
		windowsJobOfPid.delete(pid);
		const msys = msysGroupOfPid.get(pid);
		let after = false;
		const killRest = () => {
			if (after) return;
			after = true;
			if (job !== undefined) {
				try {
					getWindowsJobs()?.terminate(job);
					getWindowsJobs()?.close(job);
				} catch {}
			}
			if (msys) killMsysGroup(msys.bash, msys.group);
		};
		// Use the trusted System32 executable so cleanup does not depend on PATH.
		const taskkill = join(process.env.SystemRoot ?? "C:\\Windows", "System32", "taskkill.exe");
		const taskkillArgs = ["/F", "/T", "/PID", String(pid)];
		if (options?.sync) {
			// Pi is exiting: there is no later to wait for.
			try {
				spawnSync(taskkill, taskkillArgs, { stdio: "ignore", windowsHide: true, timeout: 5000 });
			} catch {}
			killRest();
			return;
		}
		try {
			const child = spawn(taskkill, taskkillArgs, {
				stdio: "ignore",
				detached: true,
				windowsHide: true,
			});
			// A failed spawn emits "error" asynchronously; consume it to avoid crashing Node.
			child.once("error", killRest);
			child.once("exit", killRest);
		} catch {
			// Ignore errors if taskkill fails.
			killRest();
		}
	} else {
		// Use SIGKILL on Unix/Linux/Mac
		try {
			process.kill(-pid, "SIGKILL");
		} catch {
			// Fallback to killing just the child if process group kill fails
			try {
				process.kill(pid, "SIGKILL");
			} catch {
				// Process already dead
			}
		}
	}
}
