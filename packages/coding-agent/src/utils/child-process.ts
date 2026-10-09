import {
	type ChildProcess,
	type ChildProcessByStdio,
	spawn as nodeSpawn,
	spawnSync as nodeSpawnSync,
	type SpawnOptions,
	type SpawnOptionsWithStdioTuple,
	type SpawnSyncOptionsWithStringEncoding,
	type SpawnSyncReturns,
	type StdioNull,
	type StdioPipe,
} from "node:child_process";
import { accessSync, constants, statSync } from "node:fs";
import { delimiter, isAbsolute, join } from "node:path";
import type { Readable } from "node:stream";
import crossSpawn from "cross-spawn";
import { isProgramFile, windowsPathDirectories } from "./windows-path.ts";

const EXIT_STDIO_GRACE_MS = 100;

export function spawnProcess(
	command: string,
	args: string[],
	options: SpawnOptionsWithStdioTuple<StdioNull, StdioPipe, StdioPipe>,
): ChildProcessByStdio<null, Readable, Readable>;
export function spawnProcess(command: string, args: string[], options: SpawnOptions): ChildProcess;
export function spawnProcess(command: string, args: string[], options: SpawnOptions): ChildProcess {
	return process.platform === "win32"
		? crossSpawn(resolveWindowsCommand(command, options.env), args, options)
		: nodeSpawn(command, args, options);
}

export function spawnProcessSync(
	command: string,
	args: string[],
	options: SpawnSyncOptionsWithStringEncoding,
): SpawnSyncReturns<string> {
	return process.platform === "win32"
		? crossSpawn.sync(resolveWindowsCommand(command, options.env), args, options)
		: nodeSpawnSync(command, args, options);
}

/**
 * The file a command named without a path is on Windows, from PATH's absolute directories only. cross-spawn finds one with
 * `which`, which looks in the working directory first: a repository opened in Pi could put an npm.cmd or git.exe of its own
 * there, for the package update check to run before any trust decision. Found nowhere, a path that does not exist, so that
 * spawning it fails as a missing command does. (cross-spawn still runs a .cmd through cmd.exe with its arguments escaped.)
 */
function resolveWindowsCommand(command: string, env: NodeJS.ProcessEnv | undefined): string {
	if (/[\\/]/.test(command)) return command;
	const source = env ?? process.env;
	// (An environment merged from two may have both PATH and Path: PATH, as cross-spawn takes it.)
	const pathKey = "PATH" in source ? "PATH" : Object.keys(source).find((key) => key.toLowerCase() === "path");
	const dirs = windowsPathDirectories(pathKey ? (source[pathKey] ?? "") : "").filter((dir) => isAbsolute(dir));
	const pathExtensions = (source.PATHEXT ?? ".COM;.EXE;.BAT;.CMD").split(";").filter(Boolean);
	// (As `which` has it: a name with a dot is also tried as it is.)
	const extensions = command.includes(".") ? ["", ...pathExtensions] : pathExtensions;
	for (const dir of dirs) {
		for (const extension of extensions) {
			const file = join(dir, command + extension);
			if (isProgramFile(file)) return file;
		}
	}
	// (In a folder that cannot exist: not one of Windows's own programs of that name, were PATH empty.)
	return join(process.env.SystemRoot ?? "C:\\Windows", "pi-bolt-no-such-command", `${command}.exe`);
}

/** The first executable file named `command` in the PATH directories, or undefined. Does not run it. */
export function findExecutableOnPath(command: string, env: NodeJS.ProcessEnv = process.env): string | undefined {
	const pathValue = env.PATH ?? env.Path ?? "";
	const extensions =
		process.platform === "win32" ? ["", ...(env.PATHEXT ?? ".COM;.EXE;.BAT;.CMD").split(";").filter(Boolean)] : [""];
	for (const dir of pathValue.split(delimiter)) {
		if (!dir) continue;
		for (const extension of extensions) {
			const candidate = join(dir, command + extension);
			try {
				accessSync(candidate, constants.X_OK);
				if (statSync(candidate).isFile()) return candidate;
			} catch {
				// Not here; keep looking.
			}
		}
	}
	return undefined;
}

/**
 * Wait for a child process to terminate without hanging on inherited stdio handles.
 *
 * A short-lived child can `exit` while a detached descendant keeps its stdout/stderr
 * pipe open. We must not resolve and destroy the streams on a fixed deadline measured
 * from `exit`, or output still being written past that deadline is silently lost
 * (earendil-works/pi#5303). Instead, after `exit` we wait for the pipes to fall idle:
 * the grace timer is re-armed on every chunk, so an actively writing descendant keeps
 * us reading, while a quiet inherited handle (e.g. a Windows daemonized descendant
 * that never lets `close` fire) still releases us after the grace elapses.
 */
export function waitForChildProcess(child: ChildProcess): Promise<number | null> {
	return new Promise((resolve, reject) => {
		let settled = false;
		let exited = false;
		let exitCode: number | null = null;
		let postExitTimer: NodeJS.Timeout | undefined;
		let stdoutEnded = child.stdout === null;
		let stderrEnded = child.stderr === null;

		const cleanup = () => {
			if (postExitTimer) {
				clearTimeout(postExitTimer);
				postExitTimer = undefined;
			}
			child.removeListener("error", onError);
			child.removeListener("exit", onExit);
			child.removeListener("close", onClose);
			child.stdout?.removeListener("end", onStdoutEnd);
			child.stderr?.removeListener("end", onStderrEnd);
			child.stdout?.removeListener("data", onData);
			child.stderr?.removeListener("data", onData);
		};

		const finalize = (code: number | null) => {
			if (settled) return;
			settled = true;
			cleanup();
			child.stdout?.destroy();
			child.stderr?.destroy();
			resolve(code);
		};

		const maybeFinalizeAfterExit = () => {
			if (!exited || settled) return;
			if (stdoutEnded && stderrEnded) {
				finalize(exitCode);
			}
		};

		const armIdleTimer = () => {
			if (postExitTimer) clearTimeout(postExitTimer);
			postExitTimer = setTimeout(() => finalize(exitCode), EXIT_STDIO_GRACE_MS);
		};

		const onData = () => {
			// Output is still arriving after exit; defer finalizing so we don't
			// destroy the stream mid-write and truncate the tail.
			if (exited && !settled) armIdleTimer();
		};

		const onStdoutEnd = () => {
			stdoutEnded = true;
			maybeFinalizeAfterExit();
		};

		const onStderrEnd = () => {
			stderrEnded = true;
			maybeFinalizeAfterExit();
		};

		const onError = (err: Error) => {
			if (settled) return;
			settled = true;
			cleanup();
			reject(err);
		};

		const onExit = (code: number | null) => {
			exited = true;
			exitCode = code;
			maybeFinalizeAfterExit();
			if (!settled) {
				armIdleTimer();
			}
		};

		const onClose = (code: number | null) => {
			finalize(code);
		};

		child.stdout?.once("end", onStdoutEnd);
		child.stderr?.once("end", onStderrEnd);
		child.stdout?.on("data", onData);
		child.stderr?.on("data", onData);
		child.once("error", onError);
		child.once("exit", onExit);
		child.once("close", onClose);
	});
}
