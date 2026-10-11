import { realpathSync, statSync } from "node:fs";
import { homedir, tmpdir } from "node:os";
import { isAbsolute, join, resolve as nodeResolvePath, relative, sep } from "node:path";
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
