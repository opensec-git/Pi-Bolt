import { accessSync, constants, existsSync } from "node:fs";
import { createRequire } from "node:module";
import { basename, delimiter, dirname, isAbsolute, join, win32 } from "node:path";
import { spawn, spawnSync } from "child_process";
import { getBinDir } from "../config.ts";
import { isProgramFile, windowsPathDirectories } from "./windows-path.ts";

export interface ShellConfig {
	shell: string;
	args: string[];
	commandTransport?: "argv" | "stdin";
}

// Keep in sync with packages/durable/src/env/node.ts.
function isLegacyWslBashPath(path: string): boolean {
	const normalized = win32.normalize(path).toLowerCase();
	// The WSL Store app's alias in %LOCALAPPDATA%\Microsoft\WindowsApps (on the user's PATH whenever WSL is installed from
	// the Store) starts WSL's bash too, which reads the command from stdin.
	if (/\\microsoft\\windowsapps\\bash\.exe$/.test(normalized)) return true;
	const systemRoot = process.env.SystemRoot;
	if (systemRoot) {
		if (!/^[a-z]:[\\/]/i.test(systemRoot)) return false;
		return ["System32", "Sysnative"].some(
			(directory) => normalized === win32.join(systemRoot, directory, "bash.exe").toLowerCase(),
		);
	}
	// Fall back to conventional launcher paths only when the Windows directory is unknown.
	return /^[a-z]:\\windows\\(?:system32|sysnative)\\bash\.exe$/.test(normalized);
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
		if (/^(?:mingw(?:32|64)|ucrt64|clang64|clangarm64)$/i.test(basename(root))) root = dirname(root);
		candidates.push(join(root, "bin", "bash.exe"));
	}
	return candidates;
}

function getBashShellConfig(shell: string): ShellConfig {
	return isLegacyWslBashPath(shell) ? { shell, args: ["-s"], commandTransport: "stdin" } : { shell, args: ["-c"] };
}

// Matches harness-v2's pathExists (packages/durable/src/env/node.ts).
// On Unix this is identical to existsSync() (both are access(F_OK)), just
// spelled with try/catch. On Windows we explicitly avoid existsSync(): its
// extra stat check rejects runnable Store aliases (nodejs/node#36790).
function shellPathExists(path: string): boolean {
	try {
		accessSync(path, constants.F_OK);
		return true;
	} catch {
		return false;
	}
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
		if (cached && shellPathExists(cached)) return cached;
		for (const dir of windowsPathDirectories(path)) {
			if (!win32.isAbsolute(dir)) continue;
			const file = win32.join(dir, executable);
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
		if (shellPathExists(customShellPath)) {
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
			if (shellPathExists(path)) {
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
	if (shellPathExists("/bin/bash")) {
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

	let shell = findExecutableOnPath("pwsh.exe");
	// Installation directories may be on any drive and need not be on PATH.
	const programFiles = process.env.ProgramFiles;
	if (!shell && programFiles) {
		const pwsh7 = win32.join(programFiles, "PowerShell", "7", "pwsh.exe");
		if (shellPathExists(pwsh7)) shell = pwsh7;
	}

	shell ??= findExecutableOnPath("powershell.exe");
	const systemRoot = process.env.SystemRoot;
	if (!shell && systemRoot) {
		const windowsPowerShell = win32.join(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
		if (shellPathExists(windowsPowerShell)) shell = windowsPowerShell;
	}

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
 * - Control characters (except tab, newline, carriage return), DEL, and C1 controls (U+0080..U+009F, which some
 *   terminals act on as escape sequences: U+009B starts a CSI sequence, U+009D an OSC one)
 * - Unicode interlinear annotation characters U+FFF9..U+FFFB (crash string-width due to a bug)
 */
export function sanitizeBinaryOutput(str: string): string {
	// All removed characters are single UTF-16 code units, so surrogate pairs are never split.
	return str.replace(/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F-\x9F\uFFF9-\uFFFB]/g, "");
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
 * Windows, Git for Windows: its bin\bash.exe is a launcher that sets a few variables and starts usr\bin\bash.exe, and that second
 * process start was a third of every bash tool call (46 -> 31 ms). The bash it starts, and what it sets, as the launcher would:
 * MSYSTEM (always the subsystem's own: MINGW64, UCRT64...), EXEPATH (the launcher's folder), PLINK_PROTOCOL=ssh unless set, and
 * PATH starting with <git>\<subsystem>\bin;<git>\usr\bin;<home>\bin (HOME, else HOMEDRIVE+HOMEPATH where that folder is, else
 * the profile; and only a full path: a relative one would put a folder of the project before the system's). Only where Git's
 * layout says so unambiguously (one subsystem with git.exe, usr\bin\bash.exe with its msys-2.0.dll), checked again at each use
 * (Git updated while Pi runs), and only in a runtime without job objects (the default build); undefined otherwise, and the
 * launcher is started as before.
 */
export interface DirectGitBash {
	bash: string;
	environment(env: NodeJS.ProcessEnv): NodeJS.ProcessEnv;
}
const directGitBashOf = new Map<string, { direct: DirectGitBash; git: string } | undefined>();

/**
 * A variable of a Windows environment object, taken out with all its spellings (PATH and Path both, as an environment merged
 * from two can have): the value a child would see (the exact upper-case name's, which sorts first in its environment block, else
 * any), and the spelling to put it back under.
 */
function takeWindowsVariable(env: NodeJS.ProcessEnv, name: string): { key: string; value: string | undefined } {
	const keys = Object.keys(env).filter((key) => key.toUpperCase() === name);
	const key = keys.includes(name) ? name : (keys[0] ?? name);
	const value = env[key];
	for (const k of keys) delete env[k];
	return { key, value };
}

export function getDirectGitBash(shell: string): DirectGitBash | undefined {
	if (process.platform !== "win32") return undefined;
	// Where a job object takes in what the shell starts (see getWindowsJobs), the launcher stays in front: the job is attached
	// once the shell is running, and the launcher's own start is the time that leaves for it to be in place before bash has
	// started anything. Started directly, bash could have started a program outside the job by then (seen under heavy load).
	if (getWindowsJobs()) return undefined;
	if (directGitBashOf.has(shell)) {
		const known = directGitBashOf.get(shell);
		if (!known || (existsSync(known.direct.bash) && existsSync(known.git))) return known?.direct;
	}
	let direct: DirectGitBash | undefined;
	let git = "";
	const launcherDir = dirname(shell);
	const root = dirname(launcherDir);
	const bash = join(root, "usr", "bin", "bash.exe");
	const subsystems = ["mingw64", "ucrt64", "clang64", "clangarm64", "mingw32"].filter((name) =>
		existsSync(join(root, name, "bin", "git.exe")),
	);
	if (
		basename(shell).toLowerCase() === "bash.exe" &&
		basename(launcherDir).toLowerCase() === "bin" &&
		existsSync(bash) &&
		existsSync(join(root, "usr", "bin", "msys-2.0.dll")) &&
		subsystems.length === 1
	) {
		const subsystem = subsystems[0];
		git = join(root, subsystem, "bin", "git.exe");
		direct = {
			bash,
			environment(env) {
				const out: NodeJS.ProcessEnv = { ...env };
				const take = (name: string) => takeWindowsVariable(out, name);
				const homeVar = take("HOME");
				const homeDrive = out[Object.keys(out).find((k) => k.toUpperCase() === "HOMEDRIVE") ?? "HOMEDRIVE"];
				const homePath = out[Object.keys(out).find((k) => k.toUpperCase() === "HOMEPATH") ?? "HOMEPATH"];
				const profile = out[Object.keys(out).find((k) => k.toUpperCase() === "USERPROFILE") ?? "USERPROFILE"];
				const fromDrive = homeDrive && homePath ? `${homeDrive}${homePath}` : undefined;
				const home =
					homeVar.value || (fromDrive && isAbsolute(fromDrive) && existsSync(fromDrive) ? fromDrive : profile);
				if (homeVar.value !== undefined) out[homeVar.key] = homeVar.value;
				const path = take("PATH");
				const prefix = [
					join(root, subsystem, "bin"),
					join(root, "usr", "bin"),
					...(home && isAbsolute(home) && /^(?:[a-z]:|\\\\)/i.test(home) ? [join(home, "bin")] : []),
				];
				out[path.key] = [...prefix, path.value].filter(Boolean).join(delimiter);
				out[take("MSYSTEM").key] = subsystem.toUpperCase();
				out[take("EXEPATH").key] = launcherDir;
				const plink = take("PLINK_PROTOCOL");
				out[plink.key] = plink.value || "ssh";
				return out;
			},
		};
	}
	directGitBashOf.set(shell, direct && { direct, git });
	return direct;
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
		// (`||`: an empty SystemRoot is no Windows directory either, and would make the path relative, #6596.)
		const taskkill = join(process.env.SystemRoot || "C:\\Windows", "System32", "taskkill.exe");
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
		if (process.platform === "darwin") {
			// macOS signals group members individually after releasing the group lock. A killed
			// child can wake its waiting shell before that shell is signalled, letting `a; b`
			// start b during cancellation (#11252). Stop the group first to reduce this race;
			// this is best-effort, not an atomic freeze. SIGKILL also kills stopped processes.
			// Linux holds tasklist_lock across group signalling; child exit notification needs
			// its write lock, so the shell is signalled before observing the killed child's exit.
			try {
				process.kill(-pid, "SIGSTOP");
			} catch {
				// A failed stop must not prevent the kill attempt or its single-process fallback.
			}
		}
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
