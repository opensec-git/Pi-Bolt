/**
 * The `pi` name for Herdr. Herdr identifies the agent in a pane from the foreground process's command name (a fixed table,
 * not from a report), and reads that name from argv[0] before the executable's own name, so to be treated as its official
 * `herdr:pi` integration Pi-Bolt must be started as `pi`. `pi-bolt --herdr-pi` re-execs this process with `pi` as argv[0]
 * (the same process, so pid, process group and terminal stay), which gives it that name. Windows has no execve, and Herdr
 * reads the command line there, so a Windows build has to start from a file named `pi.exe`.
 */

import { existsSync } from "node:fs";
import { basename, dirname, join } from "node:path";

/** Set for a process that reports as Herdr's Pi integration, and inherited through the re-exec. */
export const HERDR_PI_ENV = "PIBOLT_HERDR_PI";
/** The command name Herdr's fixed table knows as its Pi integration, and so the argv[0] a re-exec starts with. */
const PI_ARGV0 = "pi";

/** Whether this process reports as Herdr's Pi integration: set by `--herdr-pi`, and inherited through the re-exec. */
export function isHerdrPi(env: NodeJS.ProcessEnv = process.env): boolean {
	return env[HERDR_PI_ENV] === "1";
}

/** Whether this process still has to be re-exec'd under the name Herdr looks for. Windows has no execve. */
export function shouldReExecAsPi(
	argv0: string | undefined = process.argv0,
	platform: NodeJS.Platform = process.platform,
): boolean {
	return platform !== "win32" && basename(argv0 ?? "") !== PI_ARGV0;
}

/**
 * Replace this process with one named `pi`. Returns false when it could not be done; on success it never returns. The
 * caller sets HERDR_PI_ENV in `env` so the new process reports as the official pi integration.
 *
 * The name is argv[0] at exec, not a `pi` file: Herdr reads argv[0] first, the executable stays the one already running (the
 * kernel starts it again; on macOS through its launcher, reExecTarget), and nothing is left on disk to clean up.
 */
export function reExecAsPi(args: readonly string[], env: NodeJS.ProcessEnv = process.env): boolean {
	if (!shouldReExecAsPi() || typeof process.execve !== "function") return false;
	try {
		process.execve(reExecTarget(process.execPath, process.platform), [PI_ARGV0, ...args], env);
	} catch {
		return false;
	}
	return false;
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
