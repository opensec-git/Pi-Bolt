import { realpathSync, statSync } from "node:fs";
import { homedir, tmpdir } from "node:os";
import { isAbsolute, join, resolve as nodeResolvePath, posix, relative, sep, win32 } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnProcessSync } from "./child-process.ts";

const UNICODE_SPACES = /[\u00A0\u2000-\u200A\u202F\u205F\u3000]/g;

export interface PathInputOptions {
	/** Trim leading/trailing whitespace before normalization. */
	trim?: boolean;
	/** Expand leading `~` to a home directory. Defaults to true. */
	expandTilde?: boolean;
	/** Home directory used for `~` expansion. Defaults to `os.homedir()`. */
	homeDir?: string;
	/** Strip a leading `@`, used for CLI @file paths. */
	stripAtPrefix?: boolean;
	/** Normalize unicode space variants to regular spaces. */
	normalizeUnicodeSpaces?: boolean;
}

/**
 * Resolve a path to its canonical (real) form, following symlinks.
 * Falls back to the raw path if resolution fails (e.g. the target does
 * not exist yet), so that callers never crash on missing filesystem
 * entries.
 */
export function canonicalizePath(path: string): string {
	try {
		return realpathSync(path);
	} catch {
		return path;
	}
}

/**
 * Key for comparing filesystem paths: the resolved absolute path, case-folded
 * on Windows because Windows paths are case-insensitive (`c:\Users` and
 * `C:\Users` are the same directory). NTFS can enable per-directory case
 * sensitivity, but it is rarely used outside WSL interop, so it is ignored
 * here (https://learn.microsoft.com/en-us/windows/wsl/case-sensitivity).
 * Does not touch the filesystem; call `canonicalizePath` first when symlinks
 * must compare equal. Use only for comparison, never for display or
 * filesystem access.
 */
export function getPathComparisonKey(path: string): string {
	const pathApi = getPlatformPathApi();
	const resolvedPath = pathApi.resolve(path);
	return pathApi === win32 ? resolvedPath.toLowerCase() : resolvedPath;
}

/** `node:path` rules for the current platform, read at call time so tests can simulate Windows. */
function getPlatformPathApi(): typeof win32 {
	return process.platform === "win32" ? win32 : posix;
}

/** True when both paths name the same location (see `getPathComparisonKey`). */
export function pathsEqual(left: string, right: string): boolean {
	return getPathComparisonKey(left) === getPathComparisonKey(right);
}

/** True when `child` is `parent` or inside it (see `getPathComparisonKey`). */
export function isPathWithin(parent: string, child: string): boolean {
	const parentKey = getPathComparisonKey(parent);
	const childKey = getPathComparisonKey(child);
	if (childKey === parentKey) return true;
	// Filesystem roots (`/`, `C:\`) already end with a separator.
	const pathSep = getPlatformPathApi().sep;
	return childKey.startsWith(parentKey.endsWith(pathSep) ? parentKey : `${parentKey}${pathSep}`);
}

/** Replace a leading home directory with `~` for display. Relative paths are returned unchanged. */
export function shortenHomePath(path: string, homeDir: string = homedir()): string {
	const pathApi = getPlatformPathApi();
	if (!homeDir || !pathApi.isAbsolute(path) || !isPathWithin(homeDir, path)) return path;
	const relativePath = pathApi.relative(homeDir, path);
	return relativePath ? `~${pathApi.sep}${relativePath}` : "~";
}

export function getFileRevision(path: string): string | undefined {
	let revision: string | undefined;
	try {
		const stats = statSync(path, { bigint: true });
		revision = `${stats.dev}:${stats.ino}:${stats.size}:${stats.mtimeNs}:${stats.ctimeNs}`;
	} catch {
		revision = undefined;
	}
	if (revisionsThisTurn.has(path)) revisionsThisTurn.set(path, revision);
	return revision;
}

// Revisions looked up in this turn of the event loop (until its microtasks run), by path.
const revisionsThisTurn = new Map<string, string | undefined>();
let revisionsThisTurnCleared = true;

/**
 * getFileRevision(), looked up once in a turn of the event loop. For "has the file changed since it was read?", which a
 * burst of reads asks again and again: Pi asks it of auth.json for each provider's credentials when it refreshes its models,
 * hundreds of times in one go. Whatever this process writes, it writes in a later turn (or reads the revision again with
 * getFileRevision(), which this follows).
 */
export function getFileRevisionThisTurn(path: string): string | undefined {
	if (revisionsThisTurn.has(path)) return revisionsThisTurn.get(path);
	const revision = getFileRevision(path);
	revisionsThisTurn.set(path, revision);
	if (revisionsThisTurnCleared) {
		revisionsThisTurnCleared = false;
		queueMicrotask(() => {
			revisionsThisTurn.clear();
			revisionsThisTurnCleared = true;
		});
	}
	return revision;
}

/**
 * Returns true if the value is NOT a package source (npm:, git:, etc.),
 * a built-in extension (builtin:), or a remote URL protocol. Bare names,
 * relative paths, and file: URLs are considered local.
 */
export function isLocalPath(value: string): boolean {
	const trimmed = value.trim();
	// Known non-local prefixes. file: URLs are local paths and are intentionally resolved by resolvePath().
	if (
		trimmed.startsWith("npm:") ||
		trimmed.startsWith("git:") ||
		trimmed.startsWith("github:") ||
		trimmed.startsWith("http:") ||
		trimmed.startsWith("https:") ||
		trimmed.startsWith("ssh:") ||
		trimmed.startsWith("builtin:")
	) {
		return false;
	}
	return true;
}

/** Convert Git Bash, MSYS, Cygwin, and WSL drive paths to a form native Windows APIs accept. */
export function normalizeWindowsShellPath(filePath: string): string {
	if (!filePath.startsWith("/") || filePath.startsWith("//") || filePath.includes("\\")) return filePath;
	const match = filePath.match(/^\/(?:mnt\/|cygdrive\/)?([a-z])(?:\/(.*))?$/i);
	if (!match) return filePath;
	const suffix = match[2]?.replaceAll("/", "\\");
	return `${match[1].toUpperCase()}:\\${suffix ?? ""}`;
}

export function normalizePath(input: string, options: PathInputOptions = {}): string {
	let normalized = options.trim ? input.trim() : input;
	if (options.normalizeUnicodeSpaces) {
		normalized = normalized.replace(UNICODE_SPACES, " ");
	}
	if (options.stripAtPrefix && normalized.startsWith("@")) {
		normalized = normalized.slice(1);
	}
	if (process.platform === "win32") {
		normalized = normalizeWindowsShellPath(normalized);
		// Git Bash's /tmp is the user's temporary folder (its /etc/fstab: `none /tmp usertemp`): a file the bash tool put in
		// /tmp/x is there, not in C:\tmp, which every local user may change once a write creates it.
		const tmp = normalized.match(/^\/tmp(?:\/(.*))?$/);
		if (tmp) normalized = join(tmpdir(), ...(tmp[1] ? tmp[1].split("/") : []));
	}

	if (options.expandTilde ?? true) {
		const home = options.homeDir ?? homedir();
		if (normalized === "~") return home;
		if (normalized.startsWith("~/") || (process.platform === "win32" && normalized.startsWith("~\\"))) {
			return join(home, normalized.slice(2));
		}
	}

	if (/^file:\/\//.test(normalized)) {
		return fileURLToPath(normalized);
	}

	return normalized;
}

export function resolvePath(input: string, baseDir: string = process.cwd(), options: PathInputOptions = {}): string {
	const normalized = normalizePath(input, options);
	const normalizedBaseDir = normalizePath(baseDir);
	return isAbsolute(normalized) ? nodeResolvePath(normalized) : nodeResolvePath(normalizedBaseDir, normalized);
}

export function getCwdRelativePath(filePath: string, cwd: string): string | undefined {
	const resolvedCwd = resolvePath(cwd);
	const resolvedPath = resolvePath(filePath, resolvedCwd);
	const relativePath = relative(resolvedCwd, resolvedPath);
	const isInsideCwd =
		relativePath === "" ||
		(relativePath !== ".." && !relativePath.startsWith(`..${sep}`) && !isAbsolute(relativePath));

	return isInsideCwd ? relativePath || "." : undefined;
}

export function formatPathRelativeToCwdOrAbsolute(filePath: string, cwd: string): string {
	const absolutePath = resolvePath(filePath, cwd);
	return (getCwdRelativePath(absolutePath, cwd) ?? absolutePath).split(sep).join("/");
}

export function markPathIgnoredByCloudSync(path: string): void {
	const attrs =
		process.platform === "darwin"
			? ["com.dropbox.ignored", "com.apple.fileprovider.ignore#P"]
			: process.platform === "linux"
				? ["user.com.dropbox.ignored"]
				: [];

	for (const attr of attrs) {
		if (process.platform === "darwin") {
			spawnProcessSync("xattr", ["-w", attr, "1", path], { encoding: "utf-8", stdio: "ignore" });
		} else {
			spawnProcessSync("setfattr", ["-n", attr, "-v", "1", path], { encoding: "utf-8", stdio: "ignore" });
		}
	}
}
