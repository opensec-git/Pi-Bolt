import { type ExecFileException, execFile, spawnSync } from "child_process";
import { existsSync, type FSWatcher, readFileSync, type Stats, statfsSync, statSync, unwatchFile, watchFile } from "fs";
import { dirname, join, resolve } from "path";
import { closeWatcher, FS_WATCH_RETRY_DELAY_MS, watchWithErrorHandler } from "../utils/fs-watch.ts";

export type GitPaths = {
	repoDir: string;
	commonGitDir: string;
	headPath: string;
};

/**
 * Find git metadata paths by walking up from cwd.
 * Handles both regular git repos (.git is a directory) and worktrees (.git is a file).
 */
export function findGitPaths(cwd: string): GitPaths | null {
	let dir = cwd;
	while (true) {
		const gitPath = join(dir, ".git");
		if (existsSync(gitPath)) {
			try {
				const stat = statSync(gitPath);
				if (stat.isFile()) {
					const content = readFileSync(gitPath, "utf8").trim();
					if (content.startsWith("gitdir: ")) {
						const gitDir = resolve(dir, content.slice(8).trim());
						const headPath = join(gitDir, "HEAD");
						if (!existsSync(headPath)) return null;
						const commonDirPath = join(gitDir, "commondir");
						const commonGitDir = existsSync(commonDirPath)
							? resolve(gitDir, readFileSync(commonDirPath, "utf8").trim())
							: gitDir;
						return { repoDir: dir, commonGitDir, headPath };
					}
				} else if (stat.isDirectory()) {
					const headPath = join(gitPath, "HEAD");
					if (!existsSync(headPath)) return null;
					return { repoDir: dir, commonGitDir: gitPath, headPath };
				}
			} catch {
				return null;
			}
		}
		const parent = dirname(dir);
		if (parent === dir) return null;
		dir = parent;
	}
}

/** Ask git for the current branch. Returns null on detached HEAD or if git is unavailable. */
function resolveBranchWithGitSync(repoDir: string): string | null {
	const result = spawnSync("git", ["--no-optional-locks", "symbolic-ref", "--quiet", "--short", "HEAD"], {
		cwd: repoDir,
		encoding: "utf8",
		stdio: ["ignore", "pipe", "ignore"],
	});
	const branch = result.status === 0 ? result.stdout.trim() : "";
	return branch || null;
}

/** Ask git for the current branch asynchronously. Returns null on detached HEAD or if git is unavailable. */
function resolveBranchWithGitAsync(repoDir: string): Promise<string | null> {
	return new Promise((resolvePromise) => {
		execFile(
			"git",
			["--no-optional-locks", "symbolic-ref", "--quiet", "--short", "HEAD"],
			{
				cwd: repoDir,
				encoding: "utf8",
			},
			(error: ExecFileException | null, stdout: string) => {
				if (error) {
					resolvePromise(null);
					return;
				}
				const branch = stdout.trim();
				resolvePromise(branch || null);
			},
		);
	});
}

function isWslEnvironment(): boolean {
	return process.platform === "linux" && !!(process.env.WSL_DISTRO_NAME || process.env.WSL_INTEROP);
}

function isWindowsMountedRepoPath(repoDir: string): boolean {
	return /^\/mnt\/[a-z](?:\/|$)/i.test(repoDir);
}

function shouldPollGitHead(repoDir: string): boolean {
	return isWslEnvironment() && isWindowsMountedRepoPath(repoDir);
}

/** Linked worktrees can have their own HEAD table alongside the shared refs. */
function reftableDirs(paths: GitPaths): string[] {
	const shared = join(paths.commonGitDir, "reftable");
	const gitDir = dirname(paths.headPath);
	return gitDir === paths.commonGitDir ? [shared] : [shared, join(gitDir, "reftable")];
}

/** Keep polling for remote mounts and filesystems with unknown notification semantics. */
function needsReftablePoll(directory: string): boolean {
	try {
		if (process.platform === "darwin") {
			// Node's FSEvents watcher can miss an atomic directory swap during registration.
			if (!process.versions.bun) return true;
			// The system data volume provides the local filesystem type for this installation.
			return statfsSync(directory).type !== statfsSync("/System/Volumes/Data").type;
		}
		if (process.platform === "linux") {
			const type = statfsSync(directory).type;
			// ext2/3/4, XFS, Btrfs, and tmpfs deliver local renames through inotify.
			return type !== 0xef53 && type !== 0x58465342 && type !== 0x9123683e && type !== 0x01021994;
		}
	} catch {
		// Directory notifications may still work; keep the polling fallback as well.
	}
	return true;
}

export type FooterDataProviderOptions = {
	/**
	 * Attach the git watchers only when `startGitWatcher()` is called, not in the constructor. Attaching them (`fs.watch` on
	 * the directory holding HEAD) is most of the constructor's cost, and the branch shown first is read from HEAD directly.
	 */
	deferGitWatcher?: boolean;
};

/**
 * Provides git branch and extension statuses - data not otherwise accessible to extensions.
 * Context usage on ctx.getContextUsage(), token stats on ctx.sessionManager.getEntries(), model info on ctx.model.
 */
export class FooterDataProvider {
	private cwd: string;
	private static readonly WATCH_DEBOUNCE_MS = 500;

	private extensionStatuses = new Map<string, string>();
	private cachedBranch: string | null | undefined = undefined;
	private gitPaths: GitPaths | null | undefined = undefined;
	private headWatcher: FSWatcher | null = null;
	private reftableWatchers: FSWatcher[] = [];
	private polledGitFiles: string[] = [];
	private readonly onGitFileChange = (current: Stats, previous: Stats) => {
		if (
			current.ino !== previous.ino ||
			current.mtimeMs !== previous.mtimeMs ||
			current.ctimeMs !== previous.ctimeMs ||
			current.size !== previous.size
		) {
			this.scheduleRefresh();
		}
	};
	private branchChangeCallbacks = new Set<() => void>();
	private availableProviderCount = 0;
	private refreshTimer: ReturnType<typeof setTimeout> | null = null;
	private gitWatcherRetryTimer: ReturnType<typeof setTimeout> | null = null;
	private refreshInFlight = false;
	private refreshPending = false;
	private disposed = false;
	private gitWatcherEnabled: boolean;
	// What the branch shown was read from while the watchers were deferred (refsState()): startGitWatcher() reads the
	// branch again only when that changed, so an unchanged reftable repository runs no git at start.
	private refsStateAtRead: string | undefined = undefined;

	constructor(cwd: string, options: FooterDataProviderOptions = {}) {
		this.cwd = cwd;
		this.gitPaths = findGitPaths(cwd);
		this.gitWatcherEnabled = !options.deferGitWatcher;
		this.setupGitWatcher();
	}

	/**
	 * Attach the git watchers a `deferGitWatcher` provider has not attached yet, and read the branch again if what it was
	 * read from changed: a branch switch made before they attached is not reported by them. Does nothing once attached or
	 * disposed.
	 */
	startGitWatcher(): void {
		if (this.disposed || this.gitWatcherEnabled) return;
		this.gitWatcherEnabled = true;
		this.setupGitWatcher();
		if (this.cachedBranch !== undefined && this.refsState() !== this.refsStateAtRead)
			void this.refreshGitBranchAsync();
		this.refsStateAtRead = undefined;
	}

	/** Current git branch, null if not in repo, "detached" if detached HEAD */
	getGitBranch(): string | null {
		if (this.cachedBranch === undefined) {
			if (!this.gitWatcherEnabled) this.refsStateAtRead = this.refsState();
			this.cachedBranch = this.resolveGitBranchSync();
		}
		return this.cachedBranch;
	}

	/** Extension status texts set via ctx.ui.setStatus() */
	getExtensionStatuses(): ReadonlyMap<string, string> {
		return this.extensionStatuses;
	}

	/** Subscribe to git branch changes. Returns unsubscribe function. */
	onBranchChange(callback: () => void): () => void {
		this.branchChangeCallbacks.add(callback);
		return () => this.branchChangeCallbacks.delete(callback);
	}

	/** Internal: set extension status */
	setExtensionStatus(key: string, text: string | undefined): void {
		if (text === undefined) {
			this.extensionStatuses.delete(key);
		} else {
			this.extensionStatuses.set(key, text);
		}
	}

	/** Internal: clear extension statuses */
	clearExtensionStatuses(): void {
		this.extensionStatuses.clear();
	}

	/** Number of unique providers with available models (for footer display) */
	getAvailableProviderCount(): number {
		return this.availableProviderCount;
	}

	/** Internal: update available provider count */
	setAvailableProviderCount(count: number): void {
		this.availableProviderCount = count;
	}

	setCwd(cwd: string): void {
		if (this.cwd === cwd) {
			return;
		}

		this.cwd = cwd;
		if (this.refreshTimer) {
			clearTimeout(this.refreshTimer);
			this.refreshTimer = null;
		}
		this.clearGitWatchers();
		this.cachedBranch = undefined;
		this.refsStateAtRead = undefined;
		this.gitPaths = findGitPaths(cwd);
		this.setupGitWatcher();
		this.notifyBranchChange();
	}

	/** Internal: cleanup */
	dispose(): void {
		this.disposed = true;
		if (this.refreshTimer) {
			clearTimeout(this.refreshTimer);
			this.refreshTimer = null;
		}
		this.clearGitWatchers();
		this.branchChangeCallbacks.clear();
	}

	private notifyBranchChange(): void {
		for (const cb of this.branchChangeCallbacks) cb();
	}

	private scheduleRefresh(): void {
		if (this.disposed || this.refreshTimer) return;
		if (this.refreshInFlight) {
			this.refreshPending = true;
			return;
		}
		this.refreshTimer = setTimeout(() => {
			this.refreshTimer = null;
			void this.refreshGitBranchAsync();
		}, FooterDataProvider.WATCH_DEBOUNCE_MS);
	}

	private async refreshGitBranchAsync(): Promise<void> {
		if (this.disposed) return;
		if (this.refreshInFlight) {
			this.refreshPending = true;
			return;
		}

		this.refreshInFlight = true;
		try {
			const paths = this.gitPaths;
			const nextBranch = await this.resolveGitBranchAsync();
			if (this.disposed || paths !== this.gitPaths) return;
			if (this.cachedBranch !== undefined && this.cachedBranch !== nextBranch) {
				this.cachedBranch = nextBranch;
				this.notifyBranchChange();
				return;
			}
			this.cachedBranch = nextBranch;
		} finally {
			this.refreshInFlight = false;
			if (this.refreshPending && !this.disposed) {
				this.refreshPending = false;
				this.scheduleRefresh();
			}
		}
	}

	/**
	 * The files the branch is read from: HEAD, and in a reftable repository (HEAD names `refs/heads/.invalid`) also the
	 * reftable's tables.list, which a branch switch rewrites. Undefined when they cannot be read.
	 */
	private refsState(): string | undefined {
		if (!this.gitPaths) return undefined;
		try {
			const head = readFileSync(this.gitPaths.headPath, "utf8");
			if (head.trim() !== "ref: refs/heads/.invalid") return head;
			const tables = reftableDirs(this.gitPaths).map((directory) => {
				try {
					return readFileSync(join(directory, "tables.list"), "utf8");
				} catch {
					return "";
				}
			});
			return [head, ...tables].join("\0");
		} catch {
			return undefined;
		}
	}

	private resolveGitBranchSync(): string | null {
		try {
			if (!this.gitPaths) return null;
			const content = readFileSync(this.gitPaths.headPath, "utf8").trim();
			if (content.startsWith("ref: refs/heads/")) {
				const branch = content.slice(16);
				return branch === ".invalid" ? (resolveBranchWithGitSync(this.gitPaths.repoDir) ?? "detached") : branch;
			}
			return "detached";
		} catch {
			return null;
		}
	}

	private async resolveGitBranchAsync(): Promise<string | null> {
		try {
			if (!this.gitPaths) return null;
			const content = readFileSync(this.gitPaths.headPath, "utf8").trim();
			if (content.startsWith("ref: refs/heads/")) {
				const branch = content.slice(16);
				return branch === ".invalid"
					? ((await resolveBranchWithGitAsync(this.gitPaths.repoDir)) ?? "detached")
					: branch;
			}
			return "detached";
		} catch {
			return null;
		}
	}

	private clearGitWatchers(): void {
		closeWatcher(this.headWatcher);
		this.headWatcher = null;
		for (const watcher of this.reftableWatchers) closeWatcher(watcher);
		this.reftableWatchers.length = 0;
		for (const path of this.polledGitFiles) unwatchFile(path, this.onGitFileChange);
		this.polledGitFiles.length = 0;
		if (this.gitWatcherRetryTimer) {
			clearTimeout(this.gitWatcherRetryTimer);
			this.gitWatcherRetryTimer = null;
		}
	}

	private scheduleGitWatcherRetry(): void {
		if (this.disposed || this.gitWatcherRetryTimer) {
			return;
		}

		this.gitWatcherRetryTimer = setTimeout(() => {
			this.gitWatcherRetryTimer = null;
			this.setupGitWatcher();
			this.scheduleRefresh();
		}, FS_WATCH_RETRY_DELAY_MS);
	}

	private handleGitWatcherError(): void {
		this.clearGitWatchers();
		this.scheduleGitWatcherRetry();
	}

	private setupGitWatcher(): void {
		this.clearGitWatchers();
		if (this.disposed || !this.gitWatcherEnabled || !this.gitPaths) return;

		const gitDir = dirname(this.gitPaths.headPath);
		const onDirectoryEntry = (filename: string | null, containsHead: boolean) => {
			// A new directory has a different inode. Reattach before reading the branch.
			if (!filename || filename === "reftable") {
				this.setupGitWatcher();
				this.scheduleRefresh();
			} else if (containsHead && filename === "HEAD") {
				this.scheduleRefresh();
			}
		};
		const onError = () => this.handleGitWatcherError();
		// Git atomically replaces HEAD and tables.list. Watch their parent directories.
		this.headWatcher = watchWithErrorHandler(gitDir, (_event, name) => onDirectoryEntry(name, true), onError);
		const pollHead = shouldPollGitHead(this.gitPaths.repoDir);
		if (pollHead) this.pollGitFile(this.gitPaths.headPath, 1000);
		if (!this.headWatcher && !pollHead) return;

		for (const directory of reftableDirs(this.gitPaths)) {
			if (!existsSync(directory)) continue;
			if (dirname(directory) !== gitDir) {
				const parent = watchWithErrorHandler(
					dirname(directory),
					(_event, name) => onDirectoryEntry(name, false),
					onError,
				);
				if (!parent) return;
				this.reftableWatchers.push(parent);
			}
			const watcher = watchWithErrorHandler(
				directory,
				(_event, name) => {
					if (!name || name === "tables.list") this.scheduleRefresh();
				},
				onError,
			);
			if (!watcher) return;
			this.reftableWatchers.push(watcher);
			if (pollHead || needsReftablePoll(directory)) this.pollGitFile(join(directory, "tables.list"), 250);
		}
	}

	private pollGitFile(path: string, interval: number): void {
		watchFile(path, { interval }, this.onGitFileChange);
		this.polledGitFiles.push(path);
	}
}

/** Read-only view for extensions - excludes setExtensionStatus, setAvailableProviderCount and dispose */
export type ReadonlyFooterDataProvider = Pick<
	FooterDataProvider,
	"getGitBranch" | "getExtensionStatuses" | "getAvailableProviderCount" | "onBranchChange"
>;
