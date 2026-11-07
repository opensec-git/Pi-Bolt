package opensec.pibolt.app.checkpoints

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** A snapshot of the project: a root commit in the shadow repository. [skipped] are files too large to keep. */
data class Snapshot(val commit: String, val tree: String, val skipped: Set<String> = emptySet())

enum class ChangeKind { Added, Modified, Deleted }

/** One file that differs between two snapshots; [binary] files have no line counts. */
data class FileDelta(val path: String, val kind: ChangeKind, val added: Int, val removed: Int, val binary: Boolean)

/** A tree entry: git mode (`100644`, `100755`, `120000` for a symlink) and blob id. */
data class TreeEntry(val mode: String, val blob: String)

/**
 * The checkpoints of one project: a git repository of its own under the agent dir
 * (`HOME/.pi/agent/checkpoints/<hash of the project path>.git`) whose work tree is the project. The project's `.git`,
 * if any, is never touched: git skips `.git` directories, and every command runs with this GIT_DIR.
 *
 * A snapshot is `git add -A` into the shadow index (incremental: unchanged files are not read again), `write-tree`
 * and a parentless `commit-tree`, kept by a ref of its own so pruning a turn frees exactly its objects. What is left
 * out: the project's ignore rules (.gitignore, the user's info/exclude does not apply), dependency and build folders
 * such as node_modules ([HEAVY_DIRS]), and files over [maxFileBytes]. Not thread-safe: callers serialise (see
 * [TurnCheckpoints]).
 */
class ShadowRepo(private val git: Git, private val maxFileBytes: Long = MAX_FILE_BYTES) {
    val gitDir: File get() = git.gitDir
    val workTree: File get() = git.workTree

    private val largeExcludes = File(gitDir, "info/pi-large")
    private val lastFile = File(gitDir, "pi-last")

    fun ensureInit() {
        if (File(gitDir, "HEAD").isFile && File(gitDir, "objects").isDirectory) return
        gitDir.mkdirs()
        git.check(listOf("init", "-q"))
        for ((key, value) in CONFIG) git.check(listOf("config", key, value))
        git.check(listOf("config", "core.excludesFile", largeExcludes.path))
        File(gitDir, "info").mkdirs()
        File(gitDir, "info/exclude").writeText(HEAVY_DIRS.joinToString("\n", postfix = "\n") { "$it/" })
        File(gitDir, "pi-project").writeText(workTree.path + "\n")
    }

    /** Records the project as it is now. A project unchanged since the last snapshot gets that snapshot back. */
    fun snapshot(label: String): Snapshot {
        ensureInit()
        clearStaleLocks()
        val large = largeFiles()
        writeLargeExcludes(large)
        if (large.isNotEmpty()) {
            git.check(listOf("rm", "--cached", "-f", "-q", "--ignore-unmatch", "--pathspec-from-file=-", "--pathspec-file-nul"), Git.joinZ(large), literal = true)
        }
        // An unreadable file is left out (git still exits with 1); anything worse stops the snapshot.
        val add = git.run(listOf("add", "-A", "--ignore-errors"), timeoutMs = ADD_TIMEOUT_MS)
        if (!add.ok && (add.code != 1 || "fatal:" in add.err)) throw GitException("git add failed (${add.code}): ${add.err.take(400)}")
        val tree = git.check(listOf("write-tree")).text.trim()
        readLast()?.let { if (it.tree == tree && it.skipped == large) return it }
        val message = buildString {
            append("checkpoint ").append(label).append('\n')
            if (large.isNotEmpty()) {
                append('\n')
                for (p in large.take(MAX_SKIPPED_LISTED)) append(SKIPPED).append(p).append('\n')
            }
        }
        val commit = git.check(listOf("commit-tree", tree, "-F", "-"), message.toByteArray()).text.trim()
        git.check(listOf("update-ref", REF_PREFIX + commit, commit))
        val snapshot = Snapshot(commit, tree, large)
        writeLast(snapshot)
        return snapshot
    }

    /** The files that differ from [from] to [to], gitlinks (nested repositories) left out. */
    fun changes(from: String, to: String): List<FileDelta> {
        val raw = Git.splitZ(git.check(listOf("diff-tree", "-r", "-z", "--no-renames", "--no-abbrev", from, to)).out)
        val kinds = LinkedHashMap<String, ChangeKind>()
        var i = 0
        while (i + 1 < raw.size) {
            val meta = raw[i]
            val path = raw[i + 1]
            i += 2
            val fields = meta.trimStart(':').split(' ')
            if (fields.size < 5 || fields[0] == GITLINK || fields[1] == GITLINK) continue
            kinds[path] = when (fields[4].firstOrNull()) {
                'A' -> ChangeKind.Added
                'D' -> ChangeKind.Deleted
                else -> ChangeKind.Modified
            }
        }
        if (kinds.isEmpty()) return emptyList()
        val counts = HashMap<String, Pair<Int, Int>?>()
        val num = Git.splitZ(git.check(listOf("diff", "--numstat", "-z", "--no-renames", "--no-ext-diff", "--no-textconv", from, to)).out)
        for (entry in num) {
            val parts = entry.split('\t', limit = 3)
            if (parts.size < 3) continue
            counts[parts[2]] = if (parts[0] == "-") null else (parts[0].toIntOrNull() ?: 0) to (parts[1].toIntOrNull() ?: 0)
        }
        return kinds.map { (path, kind) ->
            val c = counts[path]
            FileDelta(path, kind, c?.first ?: 0, c?.second ?: 0, binary = path in counts && c == null)
        }
    }

    /** The unified diff of [path] from [from] to [to] (empty when equal). */
    fun patch(from: String, to: String, path: String): String =
        git.check(listOf("diff", "--no-color", "--no-ext-diff", "--no-textconv", "--no-renames", "-U3", from, to, "--", path), literal = true, maxOut = MAX_PATCH).text

    /** Every file of [commit] with its mode and blob. */
    fun tree(commit: String): Map<String, TreeEntry> {
        val out = HashMap<String, TreeEntry>()
        for (line in Git.splitZ(git.check(listOf("ls-tree", "-r", "-z", "--full-tree", commit)).out)) {
            val tab = line.indexOf('\t')
            if (tab < 0) continue
            val fields = line.substring(0, tab).split(' ')
            if (fields.size < 3 || fields[0] == GITLINK) continue
            out[line.substring(tab + 1)] = TreeEntry(fields[0], fields[2])
        }
        return out
    }

    /** The skipped (too large) files a snapshot recorded. */
    fun skipped(commit: String): Set<String> =
        git.check(listOf("cat-file", "commit", commit)).text.lineSequence().filter { it.startsWith(SKIPPED) }.map { it.removePrefix(SKIPPED) }.toSet()

    /**
     * Puts [paths] back as they are in [source]: a file [source] has is rewritten (mode and symlinks too), one it lacks
     * is deleted, unless [source] skipped it as too large (then it stays: deleting it would lose the user's file).
     * Returns the paths left alone that way.
     */
    fun restore(source: String, paths: Collection<String>): List<String> {
        if (paths.isEmpty()) return emptyList()
        val entries = tree(source)
        val skipped = skipped(source)
        val present = paths.filter { it in entries }
        val kept = ArrayList<String>()
        for (path in paths) {
            if (path in entries) continue
            if (path in skipped) {
                kept += path
                continue
            }
            val file = File(workTree, path)
            if (!file.toPath().normalize().startsWith(workTree.toPath().normalize())) continue
            if (Files.isSymbolicLink(file.toPath()) || file.isFile) {
                if (!file.delete()) throw IOException("Could not delete $path")
                pruneEmptyParents(file.parentFile)
            }
        }
        if (present.isNotEmpty()) {
            for (path in present) {
                // A file where the snapshot has a directory, or the reverse, is replaced, not merged.
                val file = File(workTree, path)
                if (file.isDirectory && !Files.isSymbolicLink(file.toPath())) file.deleteRecursively()
            }
            git.check(
                listOf("restore", "--source=$source", "--worktree", "--pathspec-from-file=-", "--pathspec-file-nul"),
                Git.joinZ(present),
                literal = true,
            )
        }
        return kept
    }

    /**
     * Deletes the refs of snapshots not in [keep]; the objects only they held go too, at most once per [PRUNE_EVERY_MS]
     * (git prune walks every object, so not after each turn). [force] prunes objects now.
     */
    fun prune(keep: Set<String>, force: Boolean = false) {
        clearStaleLocks()
        val refs = Git.splitZ(git.check(listOf("for-each-ref", "--format=%(refname)%00", REF_PREFIX)).out).map { it.trim() }.filter { it.isNotEmpty() }
        val last = readLast()?.commit
        val drop = refs.filter { val sha = it.removePrefix(REF_PREFIX); sha !in keep && sha != last }
        if (drop.isEmpty()) return
        val script = drop.joinToString("") { "delete $it\n" }
        git.check(listOf("update-ref", "--stdin"), script.toByteArray())
        val stamp = File(gitDir, "pi-pruned")
        val now = System.currentTimeMillis()
        if (!force && stamp.isFile && now - stamp.lastModified() in 0 until PRUNE_EVERY_MS) return
        git.run(listOf("prune", "--expire=now"), timeoutMs = ADD_TIMEOUT_MS)
        git.run(listOf("gc", "--auto", "--quiet"), timeoutMs = ADD_TIMEOUT_MS)
        stamp.writeText(now.toString())
        stamp.setLastModified(now)
    }

    /** The refs this repository keeps, for tests. */
    fun snapshotCommits(): Set<String> =
        Git.splitZ(git.check(listOf("for-each-ref", "--format=%(objectname)%00", REF_PREFIX)).out).map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun hasObject(sha: String): Boolean = git.run(listOf("cat-file", "-e", sha)).ok

    /**
     * Removes the lock files of a git that did not finish: the app process was killed during a snapshot, or a git timed
     * out and was killed. Left there, every later `git add` (index.lock) or ref deletion (packed-refs.lock) would fail,
     * and checkpoints of the project would stay paused for good. Safe because callers serialise every git run on this
     * repository in this process (the only one that uses it): no git of ours is running now.
     */
    private fun clearStaleLocks() {
        for (name in STALE_LOCKS) File(gitDir, name).delete()
    }

    private fun largeFiles(): Set<String> {
        // List without the previous large-file excludes: a file that shrank below the cap comes back.
        val listed = git.check(listOf("-c", "core.excludesFile=${File(gitDir, "info/pi-none").path}", "ls-files", "-z", "--cached", "--others", "--exclude-standard")).out
        val large = sortedSetOf<String>()
        val root = workTree.toPath()
        for (path in Git.splitZ(listed)) {
            if (path.isEmpty()) continue
            val attrs = try {
                Files.readAttributes(root.resolve(path), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: Exception) {
                continue
            }
            if (attrs.isRegularFile && attrs.size() > maxFileBytes) large += path
        }
        return large
    }

    private fun writeLargeExcludes(large: Set<String>) {
        largeExcludes.parentFile?.mkdirs()
        largeExcludes.writeText(large.joinToString("") { "/" + escapePattern(it) + "\n" })
    }

    private fun readLast(): Snapshot? = runCatching {
        val lines = lastFile.readLines()
        val (commit, tree) = lines.first().split(' ')
        Snapshot(commit, tree, lines.drop(1).filter { it.isNotEmpty() }.toSet())
    }.getOrNull()?.takeIf { hasObject(it.commit) }

    private fun writeLast(s: Snapshot) {
        val tmp = File(gitDir, "pi-last.tmp")
        tmp.writeText("${s.commit} ${s.tree}\n" + s.skipped.joinToString("") { "$it\n" })
        if (!tmp.renameTo(lastFile)) lastFile.writeText(tmp.readText())
    }

    private fun pruneEmptyParents(dir: File?) {
        var d = dir
        val root = workTree.canonicalFile
        while (d != null && d.canonicalFile != root && d.isDirectory && d.list()?.isEmpty() == true) {
            d.delete()
            d = d.parentFile
        }
    }

    companion object {
        const val MAX_FILE_BYTES = 5L * 1024 * 1024
        private const val ADD_TIMEOUT_MS = 10 * 60_000L
        private const val PRUNE_EVERY_MS = 24L * 60 * 60 * 1000
        private const val MAX_PATCH = 4 * 1024 * 1024
        private const val MAX_SKIPPED_LISTED = 2000
        private const val REF_PREFIX = "refs/pi/snapshots/"
        private const val SKIPPED = "Skipped-Large: "
        private const val GITLINK = "160000"
        private val STALE_LOCKS = listOf("index.lock", "packed-refs.lock")

        /** Dependency, cache and build folders left out wherever they are (not worth snapshotting or restoring). */
        val HEAVY_DIRS = listOf(
            "node_modules", "bower_components", ".pnpm-store", ".yarn/cache", ".venv", "venv", "__pycache__", ".pytest_cache",
            ".mypy_cache", ".ruff_cache", ".tox", ".gradle", ".next", ".nuxt", ".turbo", ".parcel-cache", ".svelte-kit",
            ".dart_tool", ".cache", "target", "Pods", "DerivedData",
        )

        private val CONFIG = listOf(
            "core.autocrlf" to "false",
            "core.safecrlf" to "false",
            "core.symlinks" to "true",
            "core.fileMode" to "true",
            "core.quotePath" to "false",
            "core.logAllRefUpdates" to "false",
            "core.untrackedCache" to "true",
            "gc.autoDetach" to "false",
            "gc.pruneExpire" to "now",
            "commit.gpgSign" to "false",
            "advice.addEmbeddedRepo" to "false",
            "advice.addIgnoredFile" to "false",
        )

        /** The shadow repository of [project] under [agentDir]: one per real path. */
        fun dirFor(agentDir: File, project: File): File {
            val path = runCatching { project.canonicalPath }.getOrDefault(project.absolutePath)
            val hash = MessageDigest.getInstance("SHA-256").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
            return File(agentDir, "checkpoints/$hash.git")
        }

        /** [path] as a gitignore pattern that matches only itself. */
        fun escapePattern(path: String): String = buildString {
            for (c in path) {
                if (c == '\\' || c == '*' || c == '?' || c == '[' || c == '!' || c == '#' || c == ' ') append('\\')
                append(c)
            }
        }
    }
}
