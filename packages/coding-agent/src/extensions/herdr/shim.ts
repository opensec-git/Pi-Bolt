/**
 * The `pi` shim for Herdr. Herdr identifies the agent in a pane from the foreground process's command name (a fixed
 * table, not from a report), so to be treated as its official `herdr:pi` integration Pi-Bolt must be started as `pi`.
 * `pi-bolt --herdr-shim` re-execs this process through a temporary `pi` symlink to the real executable (the same process,
 * so pid, process group and terminal stay), which gives it that command name. Windows has no execve; there a `pi.exe`
 * hardlink has to start it instead.
 */

import { mkdtempSync, rmdirSync, symlinkSync, unlinkSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";

/** Set for a shim process, and inherited through the re-exec. */
export const HERDR_SHIM_ENV = "PIBOLT_HERDR_SHIM";
/** The temporary `pi` symlink of a re-exec, removed by the process it started. */
const HERDR_SHIM_LINK_ENV = "PIBOLT_HERDR_SHIM_LINK";
/** Prefix of the private temporary directory that holds the `pi` symlink. */
const HERDR_SHIM_DIR_PREFIX = "pi-bolt-herdr-";

export function isHerdrShim(env: NodeJS.ProcessEnv = process.env): boolean {
	return env[HERDR_SHIM_ENV] === "1";
}

/** The `pi` symlink inside the private temporary directory a re-exec creates. */
export function herdrShimLink(dir: string): string {
	return join(dir, "pi");
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
	let dir: string;
	try {
		// mkdtemp names a new directory 0700 for this process alone, so no other user can pre-create it, take it over,
		// or swap the link in it between symlinkSync and execve.
		dir = mkdtempSync(join(tmpdir(), HERDR_SHIM_DIR_PREFIX));
	} catch {
		return false;
	}
	const link = herdrShimLink(dir);
	try {
		symlinkSync(process.execPath, link);
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

/**
 * Remove the shape a re-exec creates: a `pi` symlink in a `pi-bolt-herdr-*` directory directly under the temporary
 * directory. PIBOLT_HERDR_SHIM_LINK is inherited, so anything else is left alone rather than deleted.
 */
function removeHerdrShimLink(link: string): void {
	const dir = dirname(link);
	if (!isHerdrShimDir(dir)) return;
	try {
		unlinkSync(link);
	} catch {
		// Already gone.
	}
	try {
		rmdirSync(dir);
	} catch {
		// Not empty, or not ours to remove; the OS cleans the temporary directory.
	}
}

function isHerdrShimDir(dir: string): boolean {
	return dirname(dir) === resolve(tmpdir()) && basename(dir).startsWith(HERDR_SHIM_DIR_PREFIX);
}
