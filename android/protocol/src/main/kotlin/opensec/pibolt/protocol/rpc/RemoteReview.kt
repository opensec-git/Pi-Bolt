package opensec.pibolt.protocol.rpc

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import opensec.pibolt.protocol.arr
import opensec.pibolt.protocol.bool
import opensec.pibolt.protocol.long
import opensec.pibolt.protocol.obj
import opensec.pibolt.protocol.str

/*
 * Answers of the read-only review commands (`host.review`, docs/remote-control.md "Review"): the computer's working
 * tree as git sees it, and its files. Every path is relative to the session's folder and uses `/`. Parsers are lenient:
 * a missing field reads as empty, an entry without a path is dropped, and a failed response throws its error.
 */

/**
 * A review command to send (`RpcClient.request(type, fields = fields)`). Paths are relative to the session's folder,
 * `""` for the folder itself.
 */
class ReviewRequest private constructor(val type: String, val fields: JsonObjectBuilder.() -> Unit) {
    companion object {
        /** The largest page of [log] the computer sends. */
        const val MAX_LOG = 200

        fun status() = ReviewRequest("rc_git_status") {}

        /** [origPath]: the source of a staged rename, so the diff shows the rename. */
        fun diff(path: String, staged: Boolean, origPath: String? = null) = ReviewRequest("rc_git_diff") {
            put("path", path)
            put("staged", staged)
            if (staged && origPath != null) put("origPath", origPath)
        }

        fun log(limit: Int, skip: Int = 0) = ReviewRequest("rc_git_log") {
            put("limit", limit.coerceIn(1, MAX_LOG))
            put("skip", skip.coerceAtLeast(0))
        }

        fun show(hash: String) = ReviewRequest("rc_git_show") { put("hash", hash) }

        fun listDir(path: String) = ReviewRequest("rc_list_dir") { put("path", path) }

        fun readFile(path: String) = ReviewRequest("rc_read_file") { put("path", path) }
    }
}

/** A review command failed or was refused on the computer; [message] says why, for the user. */
class ReviewException(message: String) : Exception(message)

private fun RpcResponse.dataOrThrow(): JsonObject {
    if (!success) throw ReviewException(error?.takeIf { it.isNotBlank() } ?: "The computer could not answer")
    return dataObject ?: throw ReviewException("The computer sent no answer")
}

data class RemoteLineCount(val added: Int, val removed: Int) {
    companion object {
        val ZERO = RemoteLineCount(0, 0)

        fun from(o: JsonObject?): RemoteLineCount? =
            o?.let { RemoteLineCount(it.long("added")?.toInt() ?: 0, it.long("removed")?.toInt() ?: 0) }
    }
}

/** `branch` of `rc_git_status`: [head] null when detached, [oid] null before the first commit. */
data class RemoteBranch(val head: String?, val oid: String?, val upstream: String?, val ahead: Int, val behind: Int)

/**
 * One changed path. [index] is the staged change and [worktree] the unstaged one: a porcelain letter (`M A D R C T`,
 * `?` untracked, `U` conflict) or null. [staged] and [unstaged] are that side's line counts, null when git has none.
 */
data class RemoteStatusEntry(
    val path: String,
    val origPath: String?,
    val index: Char?,
    val worktree: Char?,
    val kind: String,
    val conflicted: Boolean,
    val staged: RemoteLineCount?,
    val unstaged: RemoteLineCount?,
)

/** `rc_git_status`. [repo] false: the session's folder is not in a git working tree (then [branch] is null). */
data class RemoteGitStatus(
    val repo: Boolean,
    val branch: RemoteBranch?,
    val entries: List<RemoteStatusEntry>,
    val total: RemoteLineCount,
    val truncated: Boolean,
) {
    companion object {
        fun from(response: RpcResponse): RemoteGitStatus {
            val d = response.dataOrThrow()
            val repo = d.bool("repo") == true
            val branch = d.obj("branch")?.takeIf { repo }?.let { b ->
                RemoteBranch(
                    head = b.str("head"),
                    oid = b.str("oid"),
                    upstream = b.str("upstream"),
                    ahead = b.long("ahead")?.toInt() ?: 0,
                    behind = b.long("behind")?.toInt() ?: 0,
                )
            }
            val entries = d.arr("entries").orEmpty().mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val path = o.str("path")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                RemoteStatusEntry(
                    path = path,
                    origPath = o.str("origPath")?.takeIf { it.isNotEmpty() },
                    index = letter(o.str("index")),
                    worktree = letter(o.str("worktree")),
                    kind = o.str("kind").orEmpty(),
                    conflicted = o.bool("conflicted") == true,
                    staged = RemoteLineCount.from(o.obj("staged")),
                    unstaged = RemoteLineCount.from(o.obj("unstaged")),
                )
            }
            return RemoteGitStatus(
                repo = repo,
                branch = branch,
                entries = if (repo) entries else emptyList(),
                total = RemoteLineCount.from(d.obj("total")) ?: RemoteLineCount.ZERO,
                truncated = d.bool("truncated") == true,
            )
        }

        private fun letter(s: String?): Char? = s?.singleOrNull()?.takeIf { it in "MADRCT?U" }
    }
}

/** `rc_git_diff`: [diff] is unified diff text (`git diff`), empty when nothing changed. */
data class RemoteDiff(val path: String, val staged: Boolean, val untracked: Boolean, val diff: String, val truncated: Boolean) {
    companion object {
        fun from(response: RpcResponse): RemoteDiff {
            val d = response.dataOrThrow()
            return RemoteDiff(
                path = d.str("path").orEmpty(),
                staged = d.bool("staged") == true,
                untracked = d.bool("untracked") == true,
                diff = d.str("diff").orEmpty(),
                truncated = d.bool("truncated") == true,
            )
        }
    }
}

/** A commit of `rc_git_log` or `rc_git_show`. [time] is the author time in Unix seconds. */
data class RemoteCommit(
    val hash: String,
    val short: String,
    val parents: List<String>,
    val author: String,
    val email: String,
    val time: Long,
    val subject: String,
    val body: String,
    val refs: List<String>,
) {
    companion object {
        private val HASH = Regex("[0-9a-f]{4,64}")

        /** Null without a valid [hash]: never a ref or an option. */
        fun from(o: JsonObject?): RemoteCommit? {
            o ?: return null
            val hash = o.str("hash")?.takeIf { HASH.matches(it) } ?: return null
            return RemoteCommit(
                hash = hash,
                short = o.str("short")?.takeIf { HASH.matches(it) } ?: hash.take(7),
                parents = strings(o, "parents").filter { HASH.matches(it) },
                author = o.str("author").orEmpty(),
                email = o.str("email").orEmpty(),
                time = o.long("time") ?: 0,
                subject = o.str("subject").orEmpty(),
                body = o.str("body").orEmpty(),
                refs = strings(o, "refs"),
            )
        }

        private fun strings(o: JsonObject, key: String): List<String> =
            o.arr(key).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    }
}

/** `rc_git_log`: newest first; [more] when older commits follow. */
data class RemoteLog(val repo: Boolean, val commits: List<RemoteCommit>, val more: Boolean) {
    companion object {
        fun from(response: RpcResponse): RemoteLog {
            val d = response.dataOrThrow()
            return RemoteLog(
                repo = d.bool("repo") == true,
                commits = d.arr("commits").orEmpty().mapNotNull { RemoteCommit.from(it as? JsonObject) },
                more = d.bool("more") == true,
            )
        }
    }
}

/** `rc_git_show`: the patch against the first parent; [withheld] files that look like secrets were left out of it. */
data class RemoteShow(val commit: RemoteCommit?, val diff: String, val truncated: Boolean, val withheld: List<String>) {
    companion object {
        fun from(response: RpcResponse): RemoteShow {
            val d = response.dataOrThrow()
            return RemoteShow(
                commit = RemoteCommit.from(d.obj("commit")),
                diff = d.str("diff").orEmpty(),
                truncated = d.bool("truncated") == true,
                withheld = d.arr("withheld").orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
            )
        }
    }
}

/**
 * One entry of `rc_list_dir`. [dir] and [file] come from `kind` (a symlink has its target's); [outside]: a symlink out of
 * the session's folder, which the computer will not open. [mtime] is Unix milliseconds.
 */
data class RemoteDirEntry(val name: String, val dir: Boolean, val file: Boolean, val symlink: Boolean, val outside: Boolean, val size: Long, val mtime: Long)

/** `rc_list_dir`: [path] is the folder listed, `""` for the session's folder. */
data class RemoteListing(val path: String, val entries: List<RemoteDirEntry>, val truncated: Boolean) {
    companion object {
        fun from(response: RpcResponse): RemoteListing {
            val d = response.dataOrThrow()
            val entries = d.arr("entries").orEmpty().mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                // A name is one path segment: anything else is not shown (or opened).
                val name = o.str("name")?.takeIf { it.isNotEmpty() && it != "." && it != ".." && '/' !in it && '\\' !in it } ?: return@mapNotNull null
                val kind = o.str("kind")
                RemoteDirEntry(
                    name = name,
                    dir = kind == "dir",
                    file = kind == "file",
                    symlink = o.bool("symlink") == true,
                    outside = o.bool("outside") == true,
                    size = o.long("size") ?: 0,
                    mtime = o.long("mtime") ?: 0,
                )
            }
            return RemoteListing(d.str("path").orEmpty(), entries, d.bool("truncated") == true)
        }
    }
}

/** `rc_read_file`: [content] is null for a binary file; [truncated] when only the first part was sent. */
data class RemoteFile(val path: String, val size: Long, val mtime: Long, val binary: Boolean, val truncated: Boolean, val content: String?) {
    companion object {
        fun from(response: RpcResponse): RemoteFile {
            val d = response.dataOrThrow()
            val binary = d.bool("binary") == true
            return RemoteFile(
                path = d.str("path").orEmpty(),
                size = d.long("size") ?: 0,
                mtime = d.long("mtime") ?: 0,
                binary = binary,
                truncated = d.bool("truncated") == true,
                content = if (binary) null else d.str("content").orEmpty(),
            )
        }
    }
}

/** Joins a folder ([RemoteListing.path]) and an entry name into the path the review commands take. */
fun remoteChild(folder: String, name: String): String = if (folder.isEmpty()) name else "$folder/$name"
