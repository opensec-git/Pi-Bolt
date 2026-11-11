/**
 * The `pi` name for Herdr. Herdr identifies the agent in a pane from the foreground process's command name (a fixed table,
 * not from a report), and reads that name from argv[0] before the executable's own name, so to be treated as its official
 * `herdr:pi` integration Pi-Bolt must be started as `pi`. `pi-bolt --herdr-pi` re-execs this process with `pi` as argv[0]
 * (the same process, so pid, process group and terminal stay), which gives it that name; the new process gets the same
 * arguments, `--herdr-pi` among them, and so takes the same mode. Windows has no execve, and Herdr reads the command line
 * there, so a Windows build has to start from a file named `pi.exe`.
 *
 * The mode is this process's own: it is not passed on in the environment, so a `pi-bolt` started from a tool in the same
 * pane reports as itself unless it is given the option too.
 */

import { existsSync } from "node:fs";
import { basename, dirname, join, win32 } from "node:path";
import { herdrFromEnvironment } from "./transport.ts";

/** The command name Herdr's fixed table knows as its Pi integration, and so the argv[0] a re-exec starts with. */
const PI_ARGV0 = "pi";

/** What `--herdr-pi` came to: `on` reports as Herdr's Pi integration; the others keep the ordinary pi-bolt reports. */
export type HerdrPiStart = "off" | "on" | "not-pi-exe" | "re-exec-failed";

/** The parts of the running process `--herdr-pi` decides from. */
export interface HerdrPiProcess {
	env: NodeJS.ProcessEnv;
	platform: NodeJS.Platform;
	argv0: string;
	execPath: string;
}

let herdrPi = false;

/** Whether this process reports as Herdr's Pi integration (set by startHerdrPi). */
export function isHerdrPi(): boolean {
	return herdrPi;
}

/**
 * Take `pi-bolt --herdr-pi` (main.ts): in a Herdr pane, re-exec an interactive run as `pi`, which does not return when it
 * works, and then report as Herdr's Pi integration. Outside a Herdr pane there is nothing to report to, and no reason to
 * start again under another name: `off`.
 */
export function startHerdrPi(
	args: readonly string[],
	interactive: boolean,
	proc: HerdrPiProcess = currentProcess(),
): HerdrPiStart {
	if (!herdrFromEnvironment(proc.env, proc.platform)) return "off";
	// Herdr on Windows reads the command line rather than argv[0]: only a process started from pi.exe is seen as its Pi
	// integration. That is the executable's file, whatever the command typed to start it (`pi` or `pi.exe`).
	if (proc.platform === "win32") {
		if (win32.basename(proc.execPath).toLowerCase() !== "pi.exe") return "not-pi-exe";
	} else if (interactive && basename(proc.argv0) !== PI_ARGV0) {
		reExecAsPi(args, proc);
		return "re-exec-failed";
	}
	herdrPi = true;
	return "on";
}

function currentProcess(): HerdrPiProcess {
	return { env: process.env, platform: process.platform, argv0: process.argv0, execPath: process.execPath };
}

/**
 * Replace this process with one named `pi`; returns only when that could not be done. The name is argv[0] at exec, not a
 * `pi` file: Herdr reads argv[0] first, the executable stays the one already running, and nothing is left on disk.
 */
function reExecAsPi(args: readonly string[], proc: HerdrPiProcess): void {
	if (typeof process.execve !== "function") return;
	try {
		process.execve(reExecTarget(proc.execPath, proc.platform), [PI_ARGV0, ...args], proc.env);
	} catch {
		// Reported by the caller: the run goes on as pi-bolt.
	}
}

/**
 * What a re-exec starts. On macOS the running executable is `pi-bin`, which the launcher `pi` beside it starts after it has
 * forked the helper that starts Pi's programs with the system's own process setup (scripts/lib/darwin-launcher.c). pi-bin
 * takes that helper at its start and does not pass it on, so a re-exec of pi-bin itself would leave every program it starts
 * without it: the re-exec goes through the launcher, which starts pi-bin with a helper of its own and argv[0] as given.
 */
export function reExecTarget(execPath: string, platform: NodeJS.Platform): string {
	if (platform !== "darwin" || basename(execPath) !== "pi-bin") return execPath;
	const launcher = join(dirname(execPath), "pi");
	return existsSync(launcher) ? launcher : execPath;
}
