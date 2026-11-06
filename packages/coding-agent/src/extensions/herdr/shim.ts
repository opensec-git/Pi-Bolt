/**
 * The `pi` name for Herdr. Herdr identifies the agent in a pane from the foreground process's command name (a fixed table,
 * not from a report), and reads that name from argv[0] before the executable's own name, so to be treated as its official
 * `herdr:pi` integration Pi-Bolt must be started as `pi`. `pi-bolt --herdr-shim` re-execs this process with `pi` as argv[0]
 * (the same process, so pid, process group and terminal stay), which gives it that name. Windows has no execve, and Herdr
 * reads the command line there, so a Windows build has to start from a file named `pi.exe`.
 */

import { basename } from "node:path";

/** Set for a shim process, and inherited through the re-exec. */
export const HERDR_SHIM_ENV = "PIBOLT_HERDR_SHIM";
/** The command name Herdr's fixed table knows as its Pi integration, and so the argv[0] a re-exec starts with. */
const PI_ARGV0 = "pi";

export function isHerdrShim(env: NodeJS.ProcessEnv = process.env): boolean {
	return env[HERDR_SHIM_ENV] === "1";
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
 * caller sets HERDR_SHIM_ENV in `env` so the new process reports as the official pi integration.
 *
 * The name is argv[0] at exec, not a `pi` file: Herdr reads argv[0] first, the executable stays the one already running (the
 * kernel starts it again), and nothing is left on disk to clean up.
 */
export function reExecAsPi(args: readonly string[], env: NodeJS.ProcessEnv = process.env): boolean {
	if (!shouldReExecAsPi() || typeof process.execve !== "function") return false;
	try {
		process.execve(process.execPath, [PI_ARGV0, ...args], env);
	} catch {
		return false;
	}
	return false;
}
