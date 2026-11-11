/**
 * Pi-Bolt's default build has no JIT. bun:ffi makes the code that calls into a native library while the program runs, so
 * there dlopen(), cc(), linkSymbols(), CFunction() and new JSCallback() throw "bun:ffi requires the JIT", and an extension
 * that needs them works only with the -jit build. Where Pi shows that error (an extension that failed to load, an error in one
 * of its handlers, a notice the extension shows itself), Pi-Bolt adds which extension needs the JIT build and the command that
 * installs it:
 *
 *   Failed to load extension: bun:ffi requires the JIT. pi-foo needs Pi-Bolt's JIT build (bun:ffi); switch with:
 *   curl -fsSL https://pi-bolt.opensec.in/install.sh | PIBOLT_VARIANT=arm64-jit sh
 *
 * Nothing is looked at before such an error is shown (bun:ffi is not even loaded), so this costs nothing otherwise. In every
 * other build (stock Pi, the -jit build) messages are left as they are.
 */

import { existsSync, readFileSync } from "node:fs";
import { homedir } from "node:os";
import { basename, dirname, isAbsolute, join, sep } from "node:path";
import { PIBOLT, piBoltJitSwitchCommand } from "../../pi-bolt.ts";
import { canonicalizePath } from "../../utils/paths.ts";

/** The engine's message (patches/bun.patch): a TypeError from the bun:ffi functions that make native code. */
export const JIT_REQUIRED_MESSAGE = "bun:ffi requires the JIT";
const HINT_MARK = "needs Pi-Bolt's JIT build";

/** Whether `message` is (or includes) the engine's "bun:ffi requires the JIT", on a build where it applies. */
export function isJitRequiredMessage(message: unknown): message is string {
	return PIBOLT !== undefined && !PIBOLT.jit && typeof message === "string" && message.includes(JIT_REQUIRED_MESSAGE);
}

/** The line that says `extension` needs the JIT build, and how to switch to it. */
export function jitHint(extension: string): string {
	return `${extension} ${HINT_MARK} (bun:ffi); switch with: ${piBoltJitSwitchCommand()}`;
}

/**
 * `message` with the JIT hint added when it is the engine's "bun:ffi requires the JIT" (and has no hint yet); `extension`
 * names the extension it came from, and is asked only then.
 */
export function withJitHint<T>(message: T, extension: () => string | undefined): T | string {
	if (!isJitRequiredMessage(message) || message.includes(HINT_MARK)) return message;
	const trimmed = message.trimEnd();
	return `${trimmed}${/[.!?]$/.test(trimmed) ? "" : "."} ${jitHint(extension() ?? "An extension")}`;
}

/**
 * The folder an extension's files are in: its package (the nearest folder with a package.json, at most four levels up), or the
 * folder of its file. Packages keep their dependencies (where bun:ffi is often called) under it. Not above an `extensions`
 * folder (where Pi finds extensions of single files) or the home folder: a package.json there is not the extension's.
 */
function extensionRoot(extensionPath: string): string {
	const home = homedir();
	let dir = dirname(extensionPath);
	for (let level = 0; level < 4; level++) {
		if (existsSync(join(dir, "package.json"))) return dir;
		const parent = dirname(dir);
		if (parent === dir || parent === home || basename(dir) === "extensions") break;
		dir = parent;
	}
	return dirname(extensionPath);
}

/** An extension's name as users know it: its package's name, else its file, with ~ for the home folder. */
export function extensionDisplayName(extensionPath: string): string {
	if (!isAbsolute(extensionPath)) return extensionPath;
	const root = extensionRoot(extensionPath);
	try {
		const name = (JSON.parse(readFileSync(join(root, "package.json"), "utf8")) as { name?: unknown }).name;
		if (typeof name === "string" && name.length > 0) return name;
	} catch {
		// (No package.json, or not one that can be read: the file names it.)
	}
	const home = homedir();
	return home && extensionPath.startsWith(home + sep) ? `~${extensionPath.slice(home.length)}` : extensionPath;
}

/**
 * The files of a stack trace that are not Pi's own: Pi's code in a compiled executable is under Bun's embedded file system
 * (/$bunfs/ and B:/~BUN/), and the engine's frames (bun:ffi, native) have no file.
 */
function stackFiles(stack: string): string[] {
	const files: string[] = [];
	for (const line of stack.split("\n")) {
		const match = /^\s*at (?:.*? \()?(.+?):\d+:\d+\)?\s*$/.exec(line);
		if (!match) continue;
		let file = match[1];
		if (file.startsWith("file://")) file = decodeURIComponent(new URL(file).pathname);
		if (!isAbsolute(file) || file.includes("/$bunfs/") || /^[A-Za-z]:[\\/]~BUN[\\/]/.test(file)) continue;
		files.push(file);
	}
	return files;
}

/** Which of `extensionPaths` the code that made `stack` belongs to (the innermost frame that is an extension's). */
export function extensionOfStack(stack: string | undefined, extensionPaths: readonly string[]): string | undefined {
	if (!stack) return undefined;
	// Each extension by its path and by its real path, which the loader imports it by (#8112: /var is /private/var on macOS).
	const named = extensionPaths
		.filter((path) => isAbsolute(path))
		.flatMap((path) => {
			const real = canonicalizePath(path);
			return real === path
				? [{ path, file: path }]
				: [
						{ path, file: path },
						{ path, file: real },
					];
		});
	const roots = named.map(({ path, file }) => ({ path, root: extensionRoot(file) }));
	for (const file of stackFiles(stack)) {
		// The extension's own file first: single-file extensions in one folder share it as their root.
		const own = named.find((entry) => entry.file === file);
		if (own) return own.path;
		const owner = roots
			.filter(({ root }) => file.startsWith(root + sep))
			.sort((a, b) => b.root.length - a.root.length)[0];
		if (owner) return owner.path;
	}
	return undefined;
}
