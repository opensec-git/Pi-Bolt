package opensec.pibolt.app.git

import androidx.compose.runtime.Immutable

/** What happened to a file on one side (index or worktree), from a porcelain status letter. */
enum class Change(val letter: Char, val label: String) {
    Modified('M', "Modified"),
    Added('A', "Added"),
    Deleted('D', "Deleted"),
    Renamed('R', "Renamed"),
    Copied('C', "Copied"),
    TypeChanged('T', "Type changed"),
    Untracked('?', "Untracked"),
    Conflicted('U', "Conflict"),
    ;

    companion object {
        fun of(c: Char): Change? = when (c) {
            'M' -> Modified
            'A' -> Added
            'D' -> Deleted
            'R' -> Renamed
            'C' -> Copied
            'T' -> TypeChanged
            'U' -> Conflicted
            else -> null
        }
    }
}

/**
 * One path in `git status --porcelain=v2`: [index] is the staged change, [worktree] the unstaged one (null where that
 * side is unchanged). [origPath] is the source of a rename or copy.
 */
@Immutable
data class StatusEntry(
    val path: String,
    val index: Change?,
    val worktree: Change?,
    val origPath: String? = null,
    val conflicted: Boolean = false,
) {
    val untracked: Boolean get() = worktree == Change.Untracked
    val staged: Boolean get() = index != null && !conflicted
    val unstaged: Boolean get() = worktree != null || conflicted
}

/** The `# branch.*` headers. [head] is null when detached; [oid] null before the first commit. */
@Immutable
data class BranchStatus(
    val head: String? = null,
    val oid: String? = null,
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
) {
    val detached: Boolean get() = head == null
    val initial: Boolean get() = oid == null
}

@Immutable
data class GitStatus(val branch: BranchStatus, val entries: List<StatusEntry>) {
    val staged: List<StatusEntry> get() = entries.filter { it.staged }
    val unstaged: List<StatusEntry> get() = entries.filter { it.unstaged && !it.conflicted }
    val conflicts: List<StatusEntry> get() = entries.filter { it.conflicted }
    val clean: Boolean get() = entries.isEmpty()
}

/**
 * `git status --porcelain=v2 --branch -z --untracked-files=all`: NUL-terminated records, so paths with spaces,
 * newlines or quotes come through as they are. A rename or copy (`2 …`) is followed by its original path as the next
 * record. Unknown records are skipped.
 */
object PorcelainV2 {
    fun parse(raw: String): GitStatus {
        var branch = BranchStatus()
        val entries = ArrayList<StatusEntry>()
        val records = raw.split('\u0000')
        var i = 0
        while (i < records.size) {
            val r = records[i]
            i++
            if (r.isEmpty()) continue
            when (r[0]) {
                '#' -> branch = header(branch, r)
                '1' -> fields(r, 9)?.let { f -> entries += ordinary(f[1], f[8]) }
                '2' -> fields(r, 10)?.let { f ->
                    val orig = records.getOrNull(i)
                    i++
                    entries += ordinary(f[1], f[9]).copy(origPath = orig?.takeIf { it.isNotEmpty() })
                }
                'u' -> fields(r, 11)?.let { f -> entries += StatusEntry(f[10], Change.Conflicted, Change.Conflicted, conflicted = true) }
                '?' -> if (r.length > 2) entries += StatusEntry(r.substring(2), null, Change.Untracked)
                else -> Unit // '!' ignored files, and anything newer git adds
            }
        }
        return GitStatus(branch, entries)
    }

    /** [r] split on its first [n] - 1 spaces: the last field is the path and may hold spaces. */
    private fun fields(r: String, n: Int): List<String>? = r.split(' ', limit = n).takeIf { it.size == n }

    private fun ordinary(xy: String, path: String): StatusEntry {
        val x = xy.getOrNull(0) ?: '.'
        val y = xy.getOrNull(1) ?: '.'
        return StatusEntry(path, Change.of(x), Change.of(y))
    }

    private fun header(b: BranchStatus, r: String): BranchStatus {
        val parts = r.split(' ', limit = 3)
        if (parts.size < 3) return b
        val value = parts[2]
        return when (parts[1]) {
            "branch.oid" -> b.copy(oid = value.takeIf { it != "(initial)" })
            "branch.head" -> b.copy(head = value.takeIf { it != "(detached)" })
            "branch.upstream" -> b.copy(upstream = value)
            "branch.ab" -> {
                val (a, behind) = value.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
                b.copy(ahead = a.removePrefix("+").toIntOrNull() ?: 0, behind = behind.removePrefix("-").toIntOrNull() ?: 0)
            }
            else -> b
        }
    }
}
