package opensec.pibolt.app.git

import java.io.File

/**
 * What the Changes views read: this phone's working tree ([GitRepo]), or a computer's over remote control, read-only
 * (ui/session/remote). Failures throw [GitException] with a message for the user.
 */
interface GitSource {
    suspend fun isRepo(): Boolean

    suspend fun status(): GitStatus

    suspend fun diffStats(): DiffStats

    suspend fun diff(entry: StatusEntry, staged: Boolean): FileDiff?

    suspend fun log(limit: Int = 100, skip: Int = 0): List<Commit>

    suspend fun show(hash: String): Pair<Commit?, List<FileDiff>>

    suspend fun remoteUrl(): String?
}

/**
 * Git operations on one working tree, through [runner] (the engine's own git). Every call suspends on the runner's IO
 * dispatcher with a timeout; failures throw [GitException] with a message for the user.
 */
class GitRepo(private val runner: GitRunner, val dir: File) : GitSource {
    private suspend fun git(vararg args: String, stdin: String? = null, timeoutMs: Long = GitRunners.LOCAL_TIMEOUT_MS, okExits: Set<Int> = setOf(0), progress: ((GitProgress) -> Unit)? = null): GitResult {
        // Paths come from status and the file list: `[id].txt` must not match `i.txt` and `d.txt` as a glob.
        val all = listOf("--literal-pathspecs", "-c", "core.quotePath=false", "-c", "color.ui=false", "-c", "core.pager=cat") + args
        val onLine: ((String) -> Unit)? = progress?.let { p -> { line: String -> GitProgress.parse(line)?.let(p) } }
        val r = runner.run(dir, all, timeoutMs, stdin, onLine)
        if (r.timedOut) throw GitException("Git took too long and was stopped", r.err)
        if (r.exit !in okExits) throw GitException(GitErrors.explain(r.err), r.err)
        return r
    }

    /** True when [dir] is inside a git working tree. */
    override suspend fun isRepo(): Boolean = runCatching { git("rev-parse", "--is-inside-work-tree").out.trim() == "true" }.getOrDefault(false)

    suspend fun init() {
        git("init", "-b", "main")
    }

    override suspend fun status(): GitStatus = PorcelainV2.parse(git("status", "--porcelain=v2", "--branch", "-z", "--untracked-files=all").out)

    /** Line counts of the staged and the unstaged changes, per path (`git diff --numstat`, twice). */
    override suspend fun diffStats(): DiffStats = DiffStats(
        staged = NumStat.parse(git("diff", "--cached", "--numstat", "-z", "--no-ext-diff").out),
        unstaged = NumStat.parse(git("diff", "--numstat", "-z", "--no-ext-diff").out),
    )

    /**
     * The diff of [entry]: staged (index against HEAD) or unstaged (worktree against the index). An untracked file
     * diffs against nothing, so it reads as all added. Null when there is nothing to show.
     */
    override suspend fun diff(entry: StatusEntry, staged: Boolean): FileDiff? {
        val out = if (entry.untracked) {
            git("diff", "--no-index", "--no-ext-diff", "--", "/dev/null", entry.path, okExits = setOf(0, 1)).out
        } else {
            val paths = listOfNotNull(entry.origPath?.takeIf { staged }, entry.path).toTypedArray()
            if (staged) git("diff", "--cached", "--no-ext-diff", "-M", "--", *paths).out else git("diff", "--no-ext-diff", "--", *paths).out
        }
        return UnifiedDiffParser.parse(out).firstOrNull { it.path == entry.path } ?: UnifiedDiffParser.parse(out).firstOrNull()
    }

    suspend fun stage(paths: List<String>) {
        if (paths.isEmpty()) return
        git("add", "-A", "--", *paths.toTypedArray())
    }

    suspend fun unstage(paths: List<String>, initial: Boolean) {
        if (paths.isEmpty()) return
        // Before the first commit there is no HEAD to restore the index from.
        if (initial) git("rm", "--cached", "-r", "-q", "--", *paths.toTypedArray()) else git("restore", "--staged", "--", *paths.toTypedArray())
    }

    /** Stages hunk [index] of [unstaged] (a worktree diff). */
    suspend fun stageHunk(unstaged: FileDiff, index: Int) {
        git("apply", "--cached", "--whitespace=nowarn", "-", stdin = unstaged.patchFor(index))
    }

    /** Unstages hunk [index] of [staged] (an index diff). */
    suspend fun unstageHunk(staged: FileDiff, index: Int) {
        git("apply", "--cached", "-R", "--whitespace=nowarn", "-", stdin = staged.patchFor(index))
    }

    /** Throws away hunk [index] of [unstaged] in the working tree. */
    suspend fun discardHunk(unstaged: FileDiff, index: Int) {
        git("apply", "-R", "--whitespace=nowarn", "-", stdin = unstaged.patchFor(index))
    }

    /**
     * Throws away [entry]'s changes: unstaged ones go back to the staged version; with [staged], the file goes back to
     * HEAD (a file added since is removed). An untracked file is deleted.
     */
    suspend fun discard(entry: StatusEntry, staged: Boolean) {
        when {
            entry.untracked -> git("clean", "-f", "-q", "--", entry.path)
            staged && entry.index == Change.Added -> git("rm", "-f", "-q", "--", entry.path)
            staged -> {
                val paths = listOfNotNull(entry.origPath, entry.path).toTypedArray()
                git("restore", "--source=HEAD", "--staged", "--worktree", "--", *paths)
            }
            else -> git("restore", "--worktree", "--", entry.path)
        }
    }

    /** The configured name and email (repository, then global), each null when unset. */
    suspend fun identity(): Pair<String?, String?> {
        val name = runCatching { git("config", "user.name", okExits = setOf(0, 1)).out.trim() }.getOrNull()?.ifEmpty { null }
        val email = runCatching { git("config", "user.email", okExits = setOf(0, 1)).out.trim() }.getOrNull()?.ifEmpty { null }
        return name to email
    }

    suspend fun setGlobalIdentity(name: String, email: String) {
        git("config", "--global", "user.name", name.trim())
        git("config", "--global", "user.email", email.trim())
    }

    /** Commits the index with [message]; returns the new commit's short hash. */
    suspend fun commit(message: String): String {
        git("commit", "--no-verify", "-q", "-F", "-", stdin = message.trim() + "\n")
        return git("rev-parse", "--short", "HEAD").out.trim()
    }

    suspend fun branches(): List<Branch> =
        GitLogFormat.parseBranches(git("for-each-ref", "--sort=-committerdate", GitLogFormat.BRANCHES, "refs/heads", "refs/remotes").out)

    /** Switches to [branch]; a remote branch gets a local tracking branch of the same name. */
    suspend fun switchTo(branch: Branch) {
        if (branch.remote) git("switch", "--track", "-c", branch.localName, "--end-of-options", branch.name) else git("switch", "--end-of-options", branch.name)
    }

    suspend fun createBranch(name: String) {
        git("switch", "-c", name.trim())
    }

    override suspend fun log(limit: Int, skip: Int): List<Commit> {
        val r = git("log", "-n", limit.toString(), "--skip=$skip", GitLogFormat.LOG, okExits = setOf(0, 128))
        // 128: a repository without commits yet.
        return if (r.exit == 0) GitLogFormat.parseLog(r.out) else emptyList()
    }

    /** One commit with its diff against its first parent. */
    override suspend fun show(hash: String): Pair<Commit?, List<FileDiff>> {
        if (!GitLogFormat.isHash(hash)) throw GitException("Not a commit hash", hash)
        val head = GitLogFormat.parseLog(git("show", "-s", GitLogFormat.LOG, "--end-of-options", hash).out).firstOrNull()
        val patch = git("show", "--format=", "--patch", "-M", "--no-ext-diff", "--diff-merges=first-parent", "--end-of-options", hash).out
        return head to UnifiedDiffParser.parse(patch)
    }

    /** `origin`'s URL (or the first remote's), null without remotes. */
    override suspend fun remoteUrl(): String? {
        val remotes = git("remote").out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val name = if ("origin" in remotes) "origin" else remotes.firstOrNull() ?: return null
        return git("remote", "get-url", name).out.trim().ifEmpty { null }
    }

    /** Pushes the current branch, setting its upstream on `origin` the first time. */
    suspend fun push(upstream: String?, progress: (GitProgress) -> Unit) {
        if (upstream == null) {
            git("push", "--progress", "-u", "origin", "HEAD", timeoutMs = GitRunners.NETWORK_TIMEOUT_MS, progress = progress)
        } else {
            git("push", "--progress", timeoutMs = GitRunners.NETWORK_TIMEOUT_MS, progress = progress)
        }
    }

    /** Fast-forward only: a merge or rebase is a decision for the user (or Pi), not a button. */
    suspend fun pull(progress: (GitProgress) -> Unit) {
        git("pull", "--ff-only", "--progress", timeoutMs = GitRunners.NETWORK_TIMEOUT_MS, progress = progress)
    }

    suspend fun fetch(progress: (GitProgress) -> Unit) {
        git("fetch", "--progress", "--prune", timeoutMs = GitRunners.NETWORK_TIMEOUT_MS, progress = progress)
    }

    companion object {
        /** Clones [url] into the empty folder [into]. */
        suspend fun clone(runner: GitRunner, url: String, into: File, progress: (GitProgress) -> Unit) {
            val r = runner.run(into, listOf("clone", "--progress", "--", url.trim(), "."), GitRunners.NETWORK_TIMEOUT_MS, null) { line -> GitProgress.parse(line)?.let(progress) }
            if (r.timedOut) throw GitException("The clone took too long and was stopped", r.err)
            if (!r.ok) throw GitException(GitErrors.explain(r.err, "Could not clone the repository"), r.err)
        }
    }
}
