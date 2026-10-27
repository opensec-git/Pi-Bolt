/**
 * Pi-Bolt: extensions compiled into the executable by `pi-bolt compile` (src/pi-bolt-compile.ts).
 *
 * Each stands in for an extension installed on this machine for as long as that extension is as it was when it was compiled
 * in. Pi finds, filters and orders its extensions as always; the one at a compiled-in path then gets the compiled factory
 * instead of being loaded from its files (jiti) again. One changed since is loaded from its files, with a warning; one removed
 * since, or turned off, is not loaded at all.
 *
 * A compiled-in extension's module runs only when Pi loads it, as from its files: its factory imports it (the manifest's
 * `lazy`, scripts/gen-plugin-manifest.mjs). One that fails compiled in (it reads the staged copy the build was made from,
 * which is gone) is loaded from its files, as Pi loads it, with a warning; it then works,
 * or fails with Pi's own error. With nothing compiled in (every other build), nothing here is used.
 */
import { createHash } from "node:crypto";
import { readdirSync, readFileSync, realpathSync, statSync } from "node:fs";
import { join } from "node:path";
import type { ExtensionFactory } from "./types.ts";

/** Where a compiled-in extension came from, and what its files were then. */
export interface CompiledExtensionSource {
	/** The name the manifest gave it. */
	name: string;
	/** Its entry file, a real path, as Pi finds it at run time. */
	entry: string;
	/**
	 * What its fingerprint covers: the entry `file` alone; the `folder` the extension is (`root`), its own files without
	 * node_modules; or the `package` in `root`, by its version and entry.
	 */
	scope: "file" | "folder" | "package";
	root: string;
	/**
	 * The files the bundler compiled in for it, as installed: its own modules and its dependencies', and the package.json
	 * nearest each (its `imports`, `exports` and `main` choose modules). When the compile recorded
	 * them, the fingerprint covers exactly these, whatever the scope: a file the extension writes into its folder (state, a
	 * log, a cache) does not make it changed, and an edit to any module it was compiled from (one of an npm package's, npm
	 * link, patch-package) does.
	 */
	files?: string[];
	fingerprint: string;
}

export interface CompiledExtension extends CompiledExtensionSource {
	/** Its factory, which imports its module when called; none when the command loads no extensions. */
	factory?: ExtensionFactory;
	/** Its compiled factory ran. */
	loaded?: boolean;
	/** The first line of what it threw compiled in, when it then loaded from its files: it is loaded from them from then on. */
	failed?: string;
}

/**
 * The commands that start without the compiled-in extensions: those that report on or repair an installation (`pi-bolt
 * doctor`, `pi-bolt compile` in each of its forms, `pi-bolt update --rollback`). They run none of their code, whatever it does
 * (doctor lists the compiled-in ones without loading them).
 */
export function startsWithoutCompiledExtensions(args: readonly string[]): boolean {
	const [command, ...rest] = args;
	return command === "doctor" || command === "compile" || (command === "update" && rest.includes("--rollback"));
}

/**
 * The compiled-in extensions as main() takes them: each source (sources.json, by name) with its factory from the manifest;
 * without one when the manifest was not imported (startsWithoutCompiledExtensions).
 */
export function compiledExtensionsOf(
	sources: Readonly<Record<string, CompiledExtensionSource>>,
	manifest: { default: ReadonlyArray<{ name?: string; factory: ExtensionFactory }> } | undefined,
): CompiledExtension[] {
	const factories = new Map(manifest?.default.map((plugin) => [plugin.name, plugin.factory]));
	return Object.entries(sources).map(([name, source]) => {
		const factory = factories.get(name);
		return factory ? { ...source, factory } : { ...source };
	});
}

/** The first line of what was thrown, for a message of one line. */
export function firstLineOf(error: unknown): string {
	return (error instanceof Error ? error.message : String(error)).split("\n")[0].trim() || "it threw";
}

/** A folder extension with more files than this is fingerprinted by the first ones (a start reads no more). */
const MAX_FOLDER_FILES = 2000;

let compiled: Map<string, CompiledExtension> | undefined;

export function setCompiledExtensions(extensions: readonly CompiledExtension[]): void {
	compiled = extensions.length > 0 ? new Map(extensions.map((extension) => [extension.entry, extension])) : undefined;
}

/** The extensions compiled into this executable by `pi-bolt compile`. */
export function compiledExtensionList(): CompiledExtension[] {
	return compiled ? [...compiled.values()] : [];
}

function statPart(path: string): string {
	const stats = statSync(path);
	return `${stats.size}:${Math.trunc(stats.mtimeMs)}`;
}

/** The fingerprint of an extension's files as they are now; undefined when its entry, or one of its `files`, is gone. */
export function extensionFingerprint(
	source: Pick<CompiledExtensionSource, "entry" | "scope" | "root" | "files">,
): string | undefined {
	try {
		if (source.files) {
			const hash = createHash("sha256");
			for (const file of source.files) hash.update(`${statPart(file)}\n`);
			return `files:${source.files.length}:${hash.digest("hex").slice(0, 32)}`;
		}
		switch (source.scope) {
			case "file":
				return `file:${statPart(source.entry)}`;
			case "package": {
				const manifest = JSON.parse(readFileSync(join(source.root, "package.json"), "utf8")) as {
					version?: unknown;
				};
				return `package:${String(manifest.version)}:${statPart(source.entry)}`;
			}
			case "folder": {
				statSync(source.entry);
				let count = 0;
				let size = 0;
				let newest = 0;
				const walk = (dir: string): void => {
					for (const entry of readdirSync(dir, { withFileTypes: true }).sort((a, b) =>
						a.name < b.name ? -1 : 1,
					)) {
						if (count >= MAX_FOLDER_FILES) return;
						if (entry.name === "node_modules" || entry.name === ".git") continue;
						const path = join(dir, entry.name);
						if (entry.isDirectory()) {
							walk(path);
						} else if (entry.isFile()) {
							const stats = statSync(path);
							count++;
							size += stats.size;
							newest = Math.max(newest, Math.trunc(stats.mtimeMs));
						}
					}
				};
				walk(source.root);
				return `folder:${count}:${size}:${newest}`;
			}
		}
	} catch {
		return undefined;
	}
}

/**
 * For the extension at `path` (resolved): the compiled extension, with its factory, while its files are as they were compiled
 * in; the compiled extension it has changed from; or the one that failed compiled in before (it is loaded from its files).
 * Undefined when it is not compiled in, or the command loads no compiled-in extension: it is then loaded from its files.
 */
export function compiledExtensionFor(
	path: string,
):
	| { factory: ExtensionFactory; extension: CompiledExtension }
	| { changed: CompiledExtension }
	| { failed: CompiledExtension }
	| undefined {
	if (!compiled) return undefined;
	let extension = compiled.get(path);
	if (!extension) {
		try {
			extension = compiled.get(realpathSync(path));
		} catch {
			return undefined;
		}
		if (!extension) return undefined;
	}
	if (extensionFingerprint(extension) !== extension.fingerprint) return { changed: extension };
	if (extension.failed !== undefined) return { failed: extension };
	return extension.factory ? { factory: extension.factory, extension } : undefined;
}

/**
 * What `pi-bolt compile --status` and `pi-bolt doctor` say of the extensions compiled in: one line each that is not used as
 * compiled in (changed or removed since), and, when compile.json records a compile but this build has none (the installer
 * replaced it, or a switch to or from the JIT build), how to compile them in again. `record`: compile.json's extensions.
 */
export function compiledStateLines(
	extensions: ReadonlyArray<Pick<CompiledExtension, "name" | "entry" | "scope" | "root" | "fingerprint">>,
	record: { extensions?: string[] } | undefined,
): string[] {
	if (extensions.length === 0) {
		const recorded = Array.isArray(record?.extensions) ? record.extensions.join(", ") : "";
		return record
			? [
					`compile.json records extensions compiled in (${recorded || "none"}), but this build has none: the installer, or a switch to or from the JIT build, replaced the build they were compiled into. Run \`pi-bolt compile\` to compile them in again.`,
				]
			: [];
	}
	return extensions.flatMap((extension) => {
		const now = extensionFingerprint(extension);
		if (now === undefined) return [`${extension.name}: no longer installed (${extension.entry}): not loaded`];
		if (now !== extension.fingerprint) {
			return [
				`${extension.name}: changed since it was compiled in, so it loads from its files: run \`pi-bolt compile\` to compile it in again`,
			];
		}
		return [];
	});
}
