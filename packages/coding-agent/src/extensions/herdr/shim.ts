/**
 * The `pi` shim for Herdr. Herdr identifies the agent in a pane from the foreground process's command name (a fixed
 * table, not from a report), so to be treated as its official `herdr:pi` integration Pi-Bolt must be started as `pi`.
 * `pi-bolt --herdr-shim` re-execs this process through a temporary `pi` symlink to the real executable (the same process,
 * so pid, process group and terminal stay), which gives it that command name. Windows has no execve; there a `pi.exe`
 * hardlink has to start it instead.
 */

import { mkdirSync, rmSync, symlinkSync, unlinkSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, dirname, join } from "node:path";

/** Set for a shim process, and inherited through the re-exec. */
export const HERDR_SHIM_ENV = "PIBOLT_HERDR_SHIM";
/** The temporary `pi` symlink of a re-exec, removed by the process it started. */
const HERDR_SHIM_LINK_ENV = "PIBOLT_HERDR_SHIM_LINK";

export function isHerdrShim(env: NodeJS.ProcessEnv = process.env): boolean {
	return env[HERDR_SHIM_ENV] === "1";
}

/** The `pi` symlink for this process, under the temporary directory. */
export function herdrShimLink(pid: number = process.pid, root: string = tmpdir()): string {
	return join(root, `pi-bolt-herdr-${pid}`, "pi");
}

/** Whether this process still has to be re-exec'd under the name Herdr looks for. Windows has no execve. */
export function shouldReExecAsPi(
	argv0: string | undefined = process.argv0,
	platform: NodeJS.Platform = process.platform,
): boolean {
	return platform !== "win32" && basename(argv0 ?? "") !== "pi";
}

/**
 * Replace this process with one started through a `pi` symlink. Returns false when it could not be done; on success
 * it never returns. The caller sets HERDR_SHIM_ENV in `env` so the new process reports as the official pi integration.
 */
export function reExecAsPi(args: readonly string[], env: NodeJS.ProcessEnv = process.env): boolean {
	if (!shouldReExecAsPi() || typeof process.execve !== "function") return false;
	const link = herdrShimLink();
	try {
		mkdirSync(dirname(link), { recursive: true });
		try {
			unlinkSync(link);
		} catch {
			// No link from an earlier process with this pid.
		}
		symlinkSync(process.execPath, link);
	} catch {
		return false;
	}
	try {
		// The process this starts removes the link as it starts (cleanupHerdrShimLink, from main): the kernel already holds
		// the executable, and nothing else would ever delete it.
		env[HERDR_SHIM_LINK_ENV] = link;
		process.execve(link, [link, ...args], env);
	} catch {
		delete env[HERDR_SHIM_LINK_ENV];
		removeHerdrShimLink(link);
		return false;
	}
	return false;
}

/** Remove the symlink a re-exec used; the kernel already holds the executable, and Herdr keeps the process name. */
export function cleanupHerdrShimLink(env: NodeJS.ProcessEnv = process.env): void {
	const link = env[HERDR_SHIM_LINK_ENV];
	if (!link) return;
	delete env[HERDR_SHIM_LINK_ENV];
	removeHerdrShimLink(link);
}

function removeHerdrShimLink(link: string): void {
	try {
		unlinkSync(link);
	} catch {
		// Already gone.
	}
	try {
		rmSync(dirname(link), { recursive: true, force: true });
	} catch {
		// The temporary directory is not ours to remove; the OS will clean it.
	}
}
