package opensec.pibolt.app.git

import androidx.compose.runtime.Immutable

@Immutable
data class Commit(
    val hash: String,
    val short: String,
    val parents: List<String>,
    val author: String,
    val email: String,
    /** Author time, epoch seconds. */
    val time: Long,
    val subject: String,
    /** The message after the subject line, trimmed; empty when there is none. */
    val body: String = "",
    /** Branch and tag names pointing here (`%D`), e.g. `HEAD -> main`, `origin/main`. */
    val refs: List<String> = emptyList(),
) {
    val merge: Boolean get() = parents.size > 1
}

@Immutable
data class Branch(
    /** `main`, or `origin/main` for a remote branch. */
    val name: String,
    val remote: Boolean,
    val current: Boolean,
    val upstream: String?,
    /** `ahead 1, behind 2`, `gone`, or empty. */
    val track: String,
    val time: Long,
    val subject: String,
) {
    /** The local name a remote branch gets when switched to (`origin/feature` -> `feature`). */
    val localName: String get() = if (remote) name.substringAfter('/') else name
}

/**
 * Formats and parsers for `git log`, `git show -s` and `git for-each-ref`. Fields are split by US (0x1f) and records
 * by RS (0x1e): neither appears in names or messages that git accepts as text.
 */
object GitLogFormat {
    private const val US = '\u001f'
    private const val RS = '\u001e'

    /** `--format=` for [parseLog]: hash, short hash, parents, author, email, time, refs, subject, body. */
    const val LOG = "--format=%H%x1f%h%x1f%P%x1f%an%x1f%ae%x1f%at%x1f%D%x1f%s%x1f%b%x1e"

    /** `--format=` for [parseBranches] (for-each-ref). */
    const val BRANCHES = "--format=%(refname)%1f%(HEAD)%1f%(upstream:short)%1f%(upstream:track,nobracket)%1f%(committerdate:unix)%1f%(contents:subject)%1e"

    private val HASH = Regex("[0-9a-f]{4,64}")

    /** True for a full or abbreviated object name: never an option or a ref expression. */
    fun isHash(s: String): Boolean = HASH.matches(s)

    // Separator bytes in a commit message can fake a record: one whose hash is not a hash is dropped.
    fun parseLog(raw: String): List<Commit> = raw.split(RS).mapNotNull { rec ->
        val r = rec.trimStart('\n')
        if (r.isBlank()) return@mapNotNull null
        val f = r.split(US, limit = 9)
        if (f.size < 8 || !isHash(f[0]) || !isHash(f[1])) return@mapNotNull null
        Commit(
            hash = f[0],
            short = f[1],
            parents = f[2].split(' ').filter { it.isNotEmpty() },
            author = f[3],
            email = f[4],
            time = f[5].toLongOrNull() ?: 0,
            refs = f[6].split(", ").map { it.trim() }.filter { it.isNotEmpty() },
            subject = f[7],
            body = f.getOrNull(8)?.trim().orEmpty(),
        )
    }

    fun parseBranches(raw: String): List<Branch> = raw.split(RS).mapNotNull { rec ->
        val r = rec.trimStart('\n')
        if (r.isBlank()) return@mapNotNull null
        val f = r.split(US, limit = 6)
        if (f.size < 6) return@mapNotNull null
        val ref = f[0]
        val remote = ref.startsWith("refs/remotes/")
        val name = ref.removePrefix("refs/heads/").removePrefix("refs/remotes/")
        // `origin/HEAD` is a pointer, not a branch.
        if (remote && name.endsWith("/HEAD")) return@mapNotNull null
        Branch(
            name = name,
            remote = remote,
            current = f[1] == "*",
            upstream = f[2].ifEmpty { null },
            track = f[3],
            time = f[4].toLongOrNull() ?: 0,
            subject = f[5].trimEnd('\n'),
        )
    }

    /**
     * Whether [name] is usable as a new branch name: git's `check-ref-format --branch` rules for the common cases (no
     * spaces, `..`, control or special characters, no leading `-` or `/`, no `.lock` or `/` at the end).
     */
    fun branchNameError(name: String, existing: Collection<String> = emptyList()): String? {
        val n = name.trim()
        return when {
            n.isEmpty() -> "Name the branch"
            n.any { it.isWhitespace() || it.isISOControl() || it in "~^:?*[\\" } -> "No spaces or any of ~ ^ : ? * [ \\"
            n.startsWith("-") || n.startsWith("/") || n.endsWith("/") || n.endsWith(".") -> "Can't start with - or /, or end with / or ."
            n.contains("..") || n.contains("//") || n.contains("@{") || n == "@" -> "Not a valid branch name"
            n.split('/').any { it.startsWith(".") || it.endsWith(".lock") } -> "Not a valid branch name"
            n == "HEAD" -> "HEAD is reserved"
            n in existing -> "A branch with this name exists"
            else -> null
        }
    }
}
