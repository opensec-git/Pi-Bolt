/**
 * Read-only review of the session's folder over remote control (docs/remote-control.md, "Review"): git status, diffs,
 * history, folder listings and file contents. Nothing here writes, stages or runs a shell.
 *
 * - Every path is relative to the session's cwd. `..`, absolute paths, and paths that leave the folder through a
 *   symlink are refused.
 * - Files that usually hold secrets (`.env*`, keys, `.git/`, ...) are refused unless `remoteControl.readSecretFiles`
 *   is set; a commit's patch leaves them out and names them in `withheld`.
 * - git runs as a child process with an argument list, a timeout, `GIT_OPTIONAL_LOCKS=0` (status does not rewrite the
 *   index), and never the repository's external diff, textconv or fsmonitor programs. Output is capped.
 */

import { spawn } from "node:child_process";
import { constants, type Stats } from "node:fs";
import { lstat, open, readdir, realpath, stat } from "node:fs/promises";
import { isAbsolute, join, relative, sep } from "node:path";

export const REVIEW_COMMANDS: ReadonlySet<string> = new Set([
	"rc_git_status",
	"rc_git_diff",
	"rc_git_log",
	"rc_git_show",
	"rc_list_dir",
	"rc_read_file",
]);

export const REVIEW_LIMITS = {
	/** Bytes of one diff or commit patch; a longer one is cut at a line end (`truncated`). */
	diffBytes: 2 * 1024 * 1024,
	/** Bytes of one file read; a longer file is cut (`truncated`). */
	fileBytes: 1024 * 1024,
	/** Entries of one folder listing. */
	dirEntries: 5000,
	/** Entries of `rc_git_status`. */
	statusEntries: 5000,
	/** Largest `limit` of `rc_git_log`. */
	logCommits: 200,
	/** One git run. */
	gitTimeoutMs: 15_000,
	/** Review commands running at once; more are refused until one finishes. */
	concurrent: 4,
} as const;

/** A refused or failed review command; the message goes to the phone as the response's `error`. */
export class ReviewError extends Error {}

export interface ReviewOptions {
	/** `remoteControl.readSecretFiles`: allow reading files that match the secret patterns. */
	readSecretFiles: boolean;
	/** Tests shorten it. */
	gitTimeoutMs?: number;
}

export interface LineCount {
	added: number;
	removed: number;
}

/** A porcelain status letter: M A D R C T, `?` untracked, `U` conflict. */
export type ChangeLetter = "M" | "A" | "D" | "R" | "C" | "T" | "?" | "U";

export type ChangeKind =
	| "modified"
	| "added"
	| "deleted"
	| "renamed"
	| "copied"
	| "typechange"
	| "untracked"
	| "conflicted";

export interface ReviewStatusEntry {
	/** Relative to the session's cwd, `/`-separated. */
	path: string;
	/** Source of a rename or copy, when inside the cwd. */
	origPath?: string;
	/** Staged change (index against HEAD), null when none. */
	index: ChangeLetter | null;
	/** Unstaged change (worktree against the index), null when none. */
	worktree: ChangeLetter | null;
	kind: ChangeKind;
	conflicted: boolean;
	/** Line counts of the staged and the unstaged side (`git diff --numstat`); null when git has none. */
	staged: LineCount | null;
	unstaged: LineCount | null;
}

export interface ReviewBranch {
	/** Null when detached. */
	head: string | null;
	/** Null before the first commit. */
	oid: string | null;
	upstream: string | null;
	ahead: number;
	behind: number;
}

export interface ReviewCommit {
	hash: string;
	short: string;
	parents: string[];
	author: string;
	email: string;
	/** Author time, Unix seconds. */
	time: number;
	subject: string;
	body: string;
	refs: string[];
}

export interface ReviewDirEntry {
	name: string;
	kind: "file" | "dir" | "other";
	/** A symlink; `kind` is its target's. */
	symlink?: true;
	/** A symlink whose target is outside the session's folder: it cannot be opened. */
	outside?: true;
	size: number;
	/** Modification time, Unix milliseconds. */
	mtime: number;
}

interface GitRun {
	code: number;
	stdout: Buffer;
	stderr: string;
	truncated: boolean;
}

const SAFE_GIT_FLAGS = [
	"--no-pager",
	"--literal-pathspecs",
	"-c",
	"core.quotePath=false",
	"-c",
	"color.ui=false",
	"-c",
	"core.fsmonitor=false",
	"-c",
	"log.showSignature=false",
	"-c",
	"diff.noprefix=false",
	"-c",
	"diff.mnemonicPrefix=false",
	// `diff.submodule=diff` would put a submodule's own file changes in a patch, past the secret checks.
	"-c",
	"diff.submodule=short",
];

/** `--format=` of log and show: hash, short hash, parents, author, email, time, refs, subject, body. */
const LOG_FORMAT = "--format=%H%x1f%h%x1f%P%x1f%an%x1f%ae%x1f%at%x1f%D%x1f%s%x1f%b%x1e";
const HASH = /^[0-9a-f]{4,64}$/;
const STATUS_BYTES = 8 * 1024 * 1024;

const SECRET_DIRS = new Set([".git", ".ssh", ".gnupg", ".aws"]);
const SECRET_NAMES = new Set([
	".netrc",
	"_netrc",
	".npmrc",
	".pypirc",
	".pgpass",
	".git-credentials",
	"credentials",
	"credentials.json",
	"auth.json",
]);
const SECRET_EXTENSIONS = [".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".kdbx", ".ppk"];

/**
 * Whether a cwd-relative `/`-separated path names a file that usually holds secrets: `.env*`, SSH keys (`id_*` with no
 * extension but `.pub`), certificates and key stores, credential files, and anything in `.git`, `.ssh`, `.gnupg` or
 * `.aws`. Case-insensitive.
 */
export function isSecretPath(path: string): boolean {
	const segments = path.toLowerCase().split("/");
	const name = segments[segments.length - 1] ?? "";
	if (segments.slice(0, -1).some((segment) => SECRET_DIRS.has(segment)) || SECRET_DIRS.has(name)) return true;
	if (name.startsWith(".env") || SECRET_NAMES.has(name)) return true;
	if (/^id_[^.]+(\.pub)?$/.test(name)) return true;
	return SECRET_EXTENSIONS.some((extension) => name.endsWith(extension));
}

let running = 0;

/** Runs one review command for the session in [cwd]; resolves with the response's `data`. */
export async function runReviewCommand(
	cwd: string,
	command: { type: string; [key: string]: unknown },
	options: ReviewOptions,
): Promise<object> {
	if (running >= REVIEW_LIMITS.concurrent) throw new ReviewError("Too many review requests at once; try again");
	running++;
	try {
		const review = new Review(cwd, options);
		switch (command.type) {
			case "rc_git_status":
				return await review.status();
			case "rc_git_diff":
				return await review.diff(command.path, command.staged, command.origPath);
			case "rc_git_log":
				return await review.log(command.limit, command.skip);
			case "rc_git_show":
				return await review.show(command.hash);
			case "rc_list_dir":
				return await review.listDir(command.path);
			case "rc_read_file":
				return await review.readFile(command.path);
			default:
				throw new ReviewError("Not available over remote control");
		}
	} finally {
		running--;
	}
}

function zero(): LineCount {
	return { added: 0, removed: 0 };
}

function counted(count: LineCount | undefined): LineCount | null {
	return count && (count.added > 0 || count.removed > 0) ? count : null;
}

function kindOf(index: ChangeLetter | null, worktree: ChangeLetter | null, conflicted: boolean): ChangeKind {
	if (conflicted) return "conflicted";
	if (worktree === "?") return "untracked";
	switch (index ?? worktree) {
		case "A":
			return "added";
		case "D":
			return "deleted";
		case "R":
			return "renamed";
		case "C":
			return "copied";
		case "T":
			return "typechange";
		default:
			return "modified";
	}
}

function letter(c: string | undefined): ChangeLetter | null {
	return c && "MADRCTU".includes(c) ? (c as ChangeLetter) : null;
}

/** The last [max] bytes or fewer of [buffer] that end at [separator] (or all of it when it fits). */
function cutAt(buffer: Buffer, max: number, separator: number): { data: Buffer; truncated: boolean } {
	if (buffer.length <= max) return { data: buffer, truncated: false };
	const end = buffer.lastIndexOf(separator, max - 1);
	return { data: buffer.subarray(0, end < 0 ? 0 : end + 1), truncated: true };
}

/** [end] moved back so that `buffer[0, end)` does not split a UTF-8 sequence. */
function utf8Boundary(buffer: Buffer, end: number): number {
	let start = end;
	for (let back = 1; back <= 3 && end - back >= 0; back++) {
		const byte = buffer[end - back];
		if ((byte & 0xc0) === 0x80) continue;
		const length = byte >= 0xf0 ? 4 : byte >= 0xe0 ? 3 : byte >= 0xc0 ? 2 : 1;
		start = length > back ? end - back : end;
		break;
	}
	return start;
}

function parseCount(text: string): number {
	const n = Number.parseInt(text, 10);
	return Number.isFinite(n) ? n : 0;
}

/** `git diff --numstat -z`: `added\tremoved\tpath\0`, or for a rename `added\tremoved\t\0old\0new\0`. */
function parseNumstat(raw: string): Map<string, LineCount> {
	const counts = new Map<string, LineCount>();
	const tokens = raw.split("\0");
	for (let i = 0; i < tokens.length; i++) {
		const parts = tokens[i].split("\t");
		if (parts.length < 3) continue;
		let path = parts.slice(2).join("\t");
		if (path === "") {
			path = tokens[i + 2] ?? "";
			i += 2;
		}
		if (!path) continue;
		const previous = counts.get(path) ?? zero();
		counts.set(path, {
			added: previous.added + parseCount(parts[0]),
			removed: previous.removed + parseCount(parts[1]),
		});
	}
	return counts;
}

function parseLog(raw: string): ReviewCommit[] {
	const commits: ReviewCommit[] = [];
	for (const record of raw.split("\x1e")) {
		const text = record.replace(/^\n+/, "");
		if (!text.trim()) continue;
		const fields = text.split("\x1f");
		// Separator bytes inside a message can fake a record: one whose hash is not a hash is dropped.
		if (fields.length < 8 || !HASH.test(fields[0]) || !HASH.test(fields[1])) continue;
		commits.push({
			hash: fields[0],
			short: fields[1],
			parents: fields[2].split(" ").filter(Boolean),
			author: fields[3],
			email: fields[4],
			time: parseCount(fields[5]),
			refs: fields[6]
				.split(", ")
				.map((ref) => ref.trim())
				.filter(Boolean),
			subject: fields[7],
			body: fields.slice(8).join("\x1f").trim(),
		});
	}
	return commits;
}

function clampInt(value: unknown, fallback: number, min: number, max: number): number {
	if (typeof value !== "number" || !Number.isFinite(value)) return fallback;
	return Math.min(max, Math.max(min, Math.floor(value)));
}

class Review {
	private readonly cwd: string;
	private readonly options: ReviewOptions;
	private rootReal: string | undefined;
	private filterOverrides: Promise<string[]> | undefined;

	constructor(cwd: string, options: ReviewOptions) {
		this.cwd = cwd;
		this.options = options;
	}

	/** git with the safe flags, every configured content filter turned off (`noFilters`). */
	private async git(args: string[], maxBytes: number): Promise<GitRun> {
		this.filterOverrides ??= this.noFilters();
		return this.run([...(await this.filterOverrides), ...args], maxBytes);
	}

	/**
	 * `-c` flags that turn off every configured content filter (`filter.<name>.clean`, `.smudge`, `.process`): status
	 * and worktree diffs would run a file's clean filter, a program, which a read-only review must not start.
	 */
	private async noFilters(): Promise<string[]> {
		const run = await this.run(["config", "-z", "--get-regexp", "^filter\\."], 1024 * 1024);
		// 1: no filter is configured.
		if (run.code !== 0 || run.truncated) {
			if (run.code === 1) return [];
			throw new ReviewError("Could not read git's filter settings");
		}
		const names = new Set<string>();
		for (const entry of run.stdout.toString("utf8").split("\0")) {
			const key = entry.split("\n")[0] ?? "";
			const dot = key.lastIndexOf(".");
			if (key.startsWith("filter.") && dot > "filter.".length) names.add(key.slice("filter.".length, dot));
		}
		const flags: string[] = [];
		for (const name of names) {
			// `-c` splits at the first `=`: such a name cannot be overridden, so nothing runs at all.
			if (name.includes("=")) throw new ReviewError(`The git filter "${name}" cannot be turned off for review`);
			for (const key of ["clean", "smudge", "process"]) flags.push("-c", `filter.${name}.${key}=`);
			flags.push("-c", `filter.${name}.required=false`);
		}
		return flags;
	}

	private run(args: string[], maxBytes: number): Promise<GitRun> {
		const timeoutMs = this.options.gitTimeoutMs ?? REVIEW_LIMITS.gitTimeoutMs;
		return new Promise((resolve, reject) => {
			const child = spawn("git", [...SAFE_GIT_FLAGS, ...args], {
				cwd: this.cwd,
				shell: false,
				windowsHide: true,
				stdio: ["ignore", "pipe", "pipe"],
				env: {
					...process.env,
					GIT_OPTIONAL_LOCKS: "0",
					GIT_TERMINAL_PROMPT: "0",
					GIT_PAGER: "cat",
					PAGER: "cat",
				},
			});
			const chunks: Buffer[] = [];
			let size = 0;
			let truncated = false;
			let stderr = "";
			let timedOut = false;
			const timer = setTimeout(() => {
				timedOut = true;
				child.kill("SIGKILL");
			}, timeoutMs);
			child.stdout.on("data", (chunk: Buffer) => {
				if (truncated) return;
				chunks.push(chunk);
				size += chunk.length;
				// One byte past the cap tells the caller there was more.
				if (size > maxBytes) {
					truncated = true;
					child.kill("SIGKILL");
				}
			});
			child.stderr.on("data", (chunk: Buffer) => {
				if (stderr.length < 16_384) stderr += chunk.toString("utf8");
			});
			child.on("error", (error: NodeJS.ErrnoException) => {
				clearTimeout(timer);
				reject(
					new ReviewError(
						error.code === "ENOENT"
							? "git is not installed on this computer"
							: `Could not run git: ${error.message}`,
					),
				);
			});
			child.on("close", (code) => {
				clearTimeout(timer);
				if (timedOut) {
					reject(new ReviewError(`git took longer than ${Math.round(timeoutMs / 1000)} s and was stopped`));
					return;
				}
				resolve({ code: truncated ? 0 : (code ?? -1), stdout: Buffer.concat(chunks), stderr, truncated });
			});
		});
	}

	private async gitOk(args: string[], maxBytes: number, okCodes: number[] = [0]): Promise<GitRun> {
		const run = await this.git(args, maxBytes);
		if (!okCodes.includes(run.code)) {
			const message = run.stderr
				.trim()
				.split("\n")[0]
				?.replace(/^(fatal|error): /, "");
			throw new ReviewError(message || `git exited with code ${run.code}`);
		}
		return run;
	}

	/** The cwd's prefix inside its repository (`sub/` or ``), or undefined outside a git working tree. */
	private async repoPrefix(): Promise<string | undefined> {
		const run = await this.git(["rev-parse", "--is-inside-work-tree", "--show-prefix"], 64 * 1024);
		if (run.code !== 0) return undefined;
		const [inside, prefix] = run.stdout.toString("utf8").split("\n");
		return inside === "true" ? (prefix ?? "") : undefined;
	}

	private async root(): Promise<string> {
		if (this.rootReal === undefined) {
			try {
				this.rootReal = await realpath(this.cwd);
			} catch {
				throw new ReviewError("The session's folder no longer exists");
			}
		}
		return this.rootReal;
	}

	private inside(root: string, real: string): boolean {
		const rel = relative(root, real);
		return rel === "" || (rel !== ".." && !rel.startsWith(`..${sep}`) && !isAbsolute(rel));
	}

	/**
	 * Checks a client path: a string relative to the cwd with `/` separators, no `..`, that stays inside the cwd after
	 * resolving symlinks (for a path that does not exist, its nearest existing parent). Returns the normalized relative
	 * path (`` for the cwd itself) and the resolved real path.
	 */
	private async resolvePath(
		path: unknown,
		allowRoot: boolean,
	): Promise<{ rel: string; real: string; exists: boolean }> {
		if (path === undefined && allowRoot) path = "";
		if (typeof path !== "string") throw new ReviewError("path must be a string");
		if (path.includes("\0")) throw new ReviewError("Bad path");
		if (path.startsWith("/") || path.startsWith("\\") || /^[a-zA-Z]:/.test(path) || isAbsolute(path)) {
			throw new ReviewError("Paths are relative to the session's folder");
		}
		const segments = path.split("/").filter((segment) => segment !== "" && segment !== ".");
		if (segments.some((segment) => segment === ".." || (sep === "\\" && segment.includes("\\")))) {
			throw new ReviewError("The path leaves the session's folder");
		}
		const rel = segments.join("/");
		if (!rel && !allowRoot) throw new ReviewError("path is empty");
		const root = await this.root();
		let probe = join(root, ...segments);
		let exists = true;
		let real: string | undefined;
		for (;;) {
			try {
				real = await realpath(probe);
				break;
			} catch {
				exists = false;
				const parent = join(probe, "..");
				if (parent === probe || !this.inside(root, parent)) {
					real = root;
					break;
				}
				probe = parent;
			}
		}
		if (!this.inside(root, real)) throw new ReviewError("The path leaves the session's folder");
		return { rel, real, exists };
	}

	/**
	 * Refuses a secret file by the requested path and by the path it resolves to: a symlink `notes.txt -> .env` (or a
	 * folder link into `.ssh`) is refused like its target.
	 */
	private async refuseSecret(target: { rel: string; real: string }): Promise<void> {
		if (this.options.readSecretFiles) return;
		const resolved = relative(await this.root(), target.real)
			.split(sep)
			.join("/");
		if (isSecretPath(target.rel) || isSecretPath(resolved)) {
			throw new ReviewError(
				`${target.rel} looks like a secret file and is not shown over remote control (setting remoteControl.readSecretFiles)`,
			);
		}
	}

	async status(): Promise<object> {
		const prefix = await this.repoPrefix();
		if (prefix === undefined) {
			return { repo: false, branch: null, entries: [], total: zero(), truncated: false };
		}
		const [status, staged, unstaged] = await Promise.all([
			// `dirty`: a submodule's own files are not scanned (its filters are its own config).
			this.gitOk(
				[
					"status",
					"--porcelain=v2",
					"--branch",
					"-z",
					"--untracked-files=all",
					"--ignore-submodules=dirty",
					"--",
					".",
				],
				STATUS_BYTES,
			),
			this.gitOk(
				[
					"diff",
					"--cached",
					"--numstat",
					"-z",
					"--no-ext-diff",
					"--no-textconv",
					"--ignore-submodules=dirty",
					"--relative",
				],
				STATUS_BYTES,
			),
			this.gitOk(
				["diff", "--numstat", "-z", "--no-ext-diff", "--no-textconv", "--ignore-submodules=dirty", "--relative"],
				STATUS_BYTES,
			),
		]);
		const stagedCounts = parseNumstat(staged.stdout.toString("utf8"));
		const unstagedCounts = parseNumstat(unstaged.stdout.toString("utf8"));
		const cut = cutAt(status.stdout, STATUS_BYTES, 0);
		const branch: ReviewBranch = { head: null, oid: null, upstream: null, ahead: 0, behind: 0 };
		const entries: ReviewStatusEntry[] = [];
		let truncated = cut.truncated;
		const local = (path: string | undefined): string | undefined =>
			path?.startsWith(prefix) ? path.slice(prefix.length) : undefined;
		const add = (path: string | undefined, xy: string, origPath?: string) => {
			const rel = local(path);
			if (!rel) return;
			if (entries.length >= REVIEW_LIMITS.statusEntries) {
				truncated = true;
				return;
			}
			const conflicted = xy === "UU";
			const index = conflicted ? "U" : xy === "??" ? null : letter(xy[0]);
			const worktree = conflicted ? "U" : xy === "??" ? "?" : letter(xy[1]);
			const entry: ReviewStatusEntry = {
				path: rel,
				index,
				worktree,
				kind: kindOf(index, worktree, conflicted),
				conflicted,
				staged: counted(stagedCounts.get(rel)),
				unstaged: counted(unstagedCounts.get(rel)),
			};
			const orig = local(origPath);
			if (orig) entry.origPath = orig;
			entries.push(entry);
		};
		const records = cut.data.toString("utf8").split("\0");
		for (let i = 0; i < records.length; i++) {
			const record = records[i];
			if (!record) continue;
			switch (record[0]) {
				case "#": {
					const [, key, ...rest] = record.split(" ");
					const value = rest.join(" ");
					if (key === "branch.oid") branch.oid = value === "(initial)" ? null : value;
					else if (key === "branch.head") branch.head = value === "(detached)" ? null : value;
					else if (key === "branch.upstream") branch.upstream = value;
					else if (key === "branch.ab") {
						const [ahead = "", behind = ""] = value.split(" ");
						branch.ahead = parseCount(ahead.replace("+", ""));
						branch.behind = parseCount(behind.replace("-", ""));
					}
					break;
				}
				case "1": {
					const fields = splitFields(record, 9);
					if (fields) add(fields[8], fields[1]);
					break;
				}
				case "2": {
					const fields = splitFields(record, 10);
					const orig = records[i + 1];
					i++;
					if (fields) add(fields[9], fields[1], orig);
					break;
				}
				case "u": {
					const fields = splitFields(record, 11);
					if (fields) add(fields[10], "UU");
					break;
				}
				case "?":
					add(record.slice(2), "??");
					break;
			}
		}
		const total = zero();
		for (const count of [...stagedCounts.values(), ...unstagedCounts.values()]) {
			total.added += count.added;
			total.removed += count.removed;
		}
		return { repo: true, branch, entries, total, truncated };
	}

	async diff(path: unknown, staged: unknown, origPath: unknown): Promise<object> {
		const target = await this.resolvePath(path, false);
		await this.refuseSecret(target);
		const orig = origPath === undefined || origPath === null ? undefined : await this.resolvePath(origPath, false);
		if (orig) await this.refuseSecret(orig);
		const isStaged = staged === true;
		if ((await this.repoPrefix()) === undefined)
			throw new ReviewError("The session's folder is not a git repository");
		let run: GitRun;
		const untracked =
			!isStaged &&
			target.exists &&
			(await this.gitOk(["ls-files", "-z", "--", target.rel], 64 * 1024)).stdout.length === 0;
		if (untracked) {
			const info = await stat(target.real);
			if (!info.isFile()) throw new ReviewError(`${target.rel} is not a file`);
			run = await this.gitOk(
				["diff", "--no-index", "--no-ext-diff", "--no-textconv", "--", "/dev/null", target.rel],
				REVIEW_LIMITS.diffBytes,
				[0, 1],
			);
		} else {
			const paths = orig && isStaged ? [orig.rel, target.rel] : [target.rel];
			const flags = [
				...(isStaged ? ["--cached", "-M"] : []),
				"--no-ext-diff",
				"--no-textconv",
				"--submodule=short",
				"--ignore-submodules=dirty",
				"--relative",
			];
			// A folder path matches every file below it, past the secret check: the diff may hold only the named files,
			// both sides of a rename included (`origPath: "config"` would match a rename from `config/.env`).
			const names = await this.gitOk(["diff", "--name-status", "-z", ...flags, "--", ...paths], STATUS_BYTES);
			const listed = parseNameStatus(names.stdout.toString("utf8")).flat();
			if (names.truncated || listed.some((name) => !paths.includes(name))) {
				throw new ReviewError(`${target.rel} is not a file`);
			}
			run = await this.gitOk(["diff", ...flags, "--", ...paths], REVIEW_LIMITS.diffBytes);
		}
		const cut = cutAt(run.stdout, REVIEW_LIMITS.diffBytes, 0x0a);
		return {
			path: target.rel,
			staged: isStaged,
			untracked,
			diff: cut.data.toString("utf8"),
			truncated: cut.truncated || run.truncated,
		};
	}

	async log(limit: unknown, skip: unknown): Promise<object> {
		const count = clampInt(limit, 50, 1, REVIEW_LIMITS.logCommits);
		const from = clampInt(skip, 0, 0, Number.MAX_SAFE_INTEGER);
		const prefix = await this.repoPrefix();
		if (prefix === undefined) return { repo: false, commits: [], more: false };
		const run = await this.gitOk(
			["log", "-n", String(count + 1), `--skip=${from}`, LOG_FORMAT, ...(prefix ? ["--", "."] : [])],
			16 * 1024 * 1024,
			// 128: a repository without commits yet.
			[0, 128],
		);
		const commits = run.code === 0 ? parseLog(run.stdout.toString("utf8")) : [];
		return { repo: true, commits: commits.slice(0, count), more: commits.length > count };
	}

	async show(hash: unknown): Promise<object> {
		if (typeof hash !== "string" || !HASH.test(hash)) throw new ReviewError("hash must be a commit hash (hex)");
		const prefix = await this.repoPrefix();
		if (prefix === undefined) throw new ReviewError("The session's folder is not a git repository");
		// Any object id passes the hex check, and `git show <blob>` prints the blob whatever its path: only a commit (a
		// tag peels to its commit) is shown, by its full id.
		const verified = await this.git(["rev-parse", "--verify", "--quiet", `${hash}^{commit}`], 4096);
		const oid = verified.stdout.toString("utf8").trim();
		if (verified.code !== 0 || !HASH.test(oid)) throw new ReviewError(`${hash} is not a commit`);
		const head = await this.gitOk(["show", "-s", LOG_FORMAT, "--end-of-options", oid], 4 * 1024 * 1024);
		const commit = parseLog(head.stdout.toString("utf8"))[0] ?? null;
		const scope = [
			"--diff-merges=first-parent",
			"-M",
			"--relative",
			"--no-ext-diff",
			"--no-textconv",
			"--submodule=short",
		];
		const names = await this.gitOk(
			["show", "--format=", "--name-status", "-z", ...scope, "--end-of-options", oid],
			STATUS_BYTES,
		);
		// A partial list could miss a secret file that the unfiltered patch below would include.
		if (names.truncated && !this.options.readSecretFiles) {
			throw new ReviewError("The commit changes too many files to show");
		}
		// A change is withheld when any of its paths is secret, so a secret renamed to an allowed name does not show its
		// old lines.
		const withheld: string[] = [];
		const allowed: string[] = [];
		for (const paths of parseNameStatus(names.stdout.toString("utf8"))) {
			const secret = !this.options.readSecretFiles && paths.some((file) => isSecretPath(file));
			for (const file of paths) {
				const into = secret ? withheld : allowed;
				if (!into.includes(file)) into.push(file);
			}
		}
		// A path also matches the files below it: one that names a folder holding a withheld file (a file replaced by a
		// folder in this commit) is withheld too.
		for (let moved = true; moved; ) {
			moved = false;
			for (const file of [...allowed]) {
				if (withheld.some((secret) => secret === file || secret.startsWith(`${file}/`))) {
					allowed.splice(allowed.indexOf(file), 1);
					if (!withheld.includes(file)) withheld.push(file);
					moved = true;
				}
			}
		}
		if (allowed.length === 0) return { commit, diff: "", truncated: false, withheld };
		const run = await this.gitOk(
			[
				"show",
				"--format=",
				"--patch",
				...scope,
				"--end-of-options",
				oid,
				...(withheld.length > 0 ? ["--", ...allowed] : prefix ? ["--", "."] : []),
			],
			REVIEW_LIMITS.diffBytes,
		);
		const cut = cutAt(run.stdout, REVIEW_LIMITS.diffBytes, 0x0a);
		return { commit, diff: cut.data.toString("utf8"), truncated: cut.truncated || run.truncated, withheld };
	}

	async listDir(path: unknown): Promise<object> {
		const target = await this.resolvePath(path, true);
		if (!target.exists) throw new ReviewError(`${target.rel || "."} does not exist`);
		const root = await this.root();
		let names: string[];
		try {
			const dirents = await readdir(target.real, { withFileTypes: true });
			names = dirents.map((dirent) => dirent.name).sort();
		} catch (error) {
			const code = (error as NodeJS.ErrnoException).code;
			throw new ReviewError(
				code === "ENOTDIR" ? `${target.rel} is not a folder` : `Cannot list ${target.rel || "."}`,
			);
		}
		const truncated = names.length > REVIEW_LIMITS.dirEntries;
		names = names.slice(0, REVIEW_LIMITS.dirEntries);
		const entries: ReviewDirEntry[] = [];
		for (let i = 0; i < names.length; i += 64) {
			const batch = await Promise.all(
				names.slice(i, i + 64).map((name) => this.dirEntry(root, join(target.real, name), name)),
			);
			for (const entry of batch) if (entry) entries.push(entry);
		}
		return { path: target.rel, entries, truncated };
	}

	private async dirEntry(root: string, full: string, name: string): Promise<ReviewDirEntry | undefined> {
		let info: Stats;
		let link: Stats | undefined;
		try {
			link = await lstat(full);
		} catch {
			return undefined;
		}
		const kindOfStats = (s: Stats): ReviewDirEntry["kind"] =>
			s.isDirectory() ? "dir" : s.isFile() ? "file" : "other";
		if (!link.isSymbolicLink()) {
			return { name, kind: kindOfStats(link), size: link.size, mtime: Math.floor(link.mtimeMs) };
		}
		try {
			const real = await realpath(full);
			if (!this.inside(root, real)) {
				return { name, kind: "other", symlink: true, outside: true, size: 0, mtime: Math.floor(link.mtimeMs) };
			}
			info = await stat(real);
		} catch {
			return { name, kind: "other", symlink: true, size: 0, mtime: Math.floor(link.mtimeMs) };
		}
		return { name, kind: kindOfStats(info), symlink: true, size: info.size, mtime: Math.floor(info.mtimeMs) };
	}

	async readFile(path: unknown): Promise<object> {
		const target = await this.resolvePath(path, false);
		await this.refuseSecret(target);
		if (!target.exists) throw new ReviewError(`${target.rel} does not exist`);
		// A FIFO would block the open: only regular files, and never wait (O_NONBLOCK does nothing to a regular file).
		if (!(await stat(target.real)).isFile()) throw new ReviewError(`${target.rel} is not a file`);
		// The real path: a symlink swapped in after the check is not followed out of the folder by name.
		const handle = await open(target.real, constants.O_RDONLY | (constants.O_NONBLOCK ?? 0)).catch(() => {
			throw new ReviewError(`Cannot open ${target.rel}`);
		});
		try {
			const info = await handle.stat();
			if (!info.isFile()) throw new ReviewError(`${target.rel} is not a file`);
			const want = Math.min(info.size, REVIEW_LIMITS.fileBytes);
			const buffer = Buffer.alloc(want);
			let read = 0;
			while (read < want) {
				const { bytesRead } = await handle.read(buffer, read, want - read, read);
				if (bytesRead === 0) break;
				read += bytesRead;
			}
			const data = buffer.subarray(0, read);
			const truncated = info.size > read;
			// git's test: a NUL byte in the first 8000 bytes.
			const binary = data.subarray(0, 8000).includes(0);
			const base = { path: target.rel, size: info.size, mtime: Math.floor(info.mtimeMs), binary, truncated };
			if (binary) return base;
			const end = truncated ? utf8Boundary(data, data.length) : data.length;
			return { ...base, content: data.subarray(0, end).toString("utf8") };
		} finally {
			await handle.close();
		}
	}
}

/**
 * `--name-status -z` output as the paths of each change: `status\0path\0`, or for a rename or copy
 * `status\0source\0destination\0`.
 */
function parseNameStatus(raw: string): string[][] {
	const changes: string[][] = [];
	const tokens = raw.split("\0");
	for (let i = 0; i < tokens.length; i++) {
		if (!tokens[i]) continue;
		const count = tokens[i][0] === "R" || tokens[i][0] === "C" ? 2 : 1;
		const paths = tokens.slice(i + 1, i + 1 + count).filter(Boolean);
		i += count;
		if (paths.length > 0) changes.push(paths);
	}
	return changes;
}

/** [record] split on its first [n] - 1 spaces: the last field is the path and may hold spaces. */
function splitFields(record: string, n: number): string[] | undefined {
	const fields: string[] = [];
	let start = 0;
	for (let i = 0; i < n - 1; i++) {
		const space = record.indexOf(" ", start);
		if (space < 0) return undefined;
		fields.push(record.slice(start, space));
		start = space + 1;
	}
	fields.push(record.slice(start));
	return fields;
}
