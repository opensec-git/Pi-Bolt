// Dedupe keys of resource paths (getResourcePathKey) for one load pass, with as few realpath calls as possible: a
// realpath costs about 0.14 M instructions on macOS, and a prompt or skill used to get two or three of them per start.
import { readdirSync } from "node:fs";
import { basename, dirname, isAbsolute, join, relative, sep } from "node:path";
import { canonicalizePath, getPathComparisonKey } from "../utils/paths.ts";
import { getResourcePathKey, isSyntheticPath } from "./source-info.ts";

/**
 * getResourcePathKey for the paths of one load pass, each computed once. A path that a folder listing reached through no
 * symbolic link (notePlainEntry) costs only its root folder's realpath; `prepare` does the same for paths whose folder
 * holds two or more of them, listing that folder once. Any other path gets a realpath of its own. Paths are resolved
 * when first asked for, so keep an instance for one pass only.
 */
export class ResourcePathKeys {
	private keys = new Map<string, string>();
	/** Paths noted by notePlainEntry, with the folder whose listing reached them. */
	private plainEntries = new Map<string, string>();
	private realFolders = new Map<string, string>();

	/** The key of `path`: the same as getResourcePathKey(path). */
	keyOf(path: string): string {
		let key = this.keys.get(path);
		if (key === undefined) {
			const root = this.plainEntries.get(path);
			key = root === undefined ? getResourcePathKey(path) : this.plainEntryKey(root, path);
			this.keys.set(path, key);
		}
		return key;
	}

	/**
	 * Records that listings of `root` and of the folders below it reached `path` by names as they are stored, through
	 * entries none of which is a symbolic link: its real path is root's real path joined with the rest.
	 */
	notePlainEntry(root: string, path: string): void {
		if (process.platform === "win32" || !isAbsolute(root)) return;
		this.plainEntries.set(path, root);
	}

	/** Computes the keys of `paths` together (see the class); keyOf then returns them. */
	prepare(paths: Iterable<string>): void {
		// Windows: junctions, short names and drive casing are left to realpath.
		if (process.platform === "win32") return;
		const byFolder = new Map<string, string[]>();
		for (const path of paths) {
			if (this.keys.has(path) || this.plainEntries.has(path) || isSyntheticPath(path) || !isAbsolute(path)) continue;
			const folder = dirname(path);
			// Only a normalized path is its folder's real path joined with its name ("a/./b" and "a/b/" are not).
			if (join(folder, basename(path)) !== path) continue;
			const group = byFolder.get(folder);
			if (group) group.push(path);
			else byFolder.set(folder, [path]);
		}
		for (const [folder, group] of byFolder) {
			if (group.length < 2) continue;
			let plainNames: Set<string>;
			try {
				plainNames = new Set(
					readdirSync(folder, { withFileTypes: true })
						.filter((entry) => !entry.isSymbolicLink())
						.map((entry) => entry.name),
				);
			} catch {
				continue;
			}
			const realFolder = this.realFolder(folder);
			for (const path of group) {
				// Matched by its exact name: a name that differs in case or Unicode form from the listing gets a realpath,
				// which returns the name as stored.
				const name = basename(path);
				if (plainNames.has(name)) this.keys.set(path, getPathComparisonKey(join(realFolder, name)));
			}
		}
	}

	private plainEntryKey(root: string, path: string): string {
		const realRoot = this.realFolder(root);
		// A path that readdir names joined to its root continues the root's real path as it is: a real path is normalized
		// and absolute, so its comparison key on POSIX is itself.
		if (path.startsWith(root) && path[root.length] === sep && !root.endsWith(sep) && !realRoot.endsWith(sep)) {
			return realRoot + path.slice(root.length);
		}
		return getPathComparisonKey(join(realRoot, relative(root, path)));
	}

	private realFolder(folder: string): string {
		let real = this.realFolders.get(folder);
		if (real === undefined) {
			real = canonicalizePath(folder);
			this.realFolders.set(folder, real);
		}
		return real;
	}
}
