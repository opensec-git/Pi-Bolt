package opensec.pibolt.app.git

import androidx.compose.runtime.Immutable

/** Lines added and removed in one file (zero for a binary file). */
@Immutable
data class LineCount(val added: Int, val removed: Int) {
    operator fun plus(o: LineCount) = LineCount(added + o.added, removed + o.removed)

    companion object {
        val ZERO = LineCount(0, 0)
    }
}

/**
 * The working tree's line counts per path: [staged] (index against HEAD) and [unstaged] (worktree against the index),
 * from `git diff --numstat`. Untracked files are not in either: git counts them only once they are added.
 */
@Immutable
data class DiffStats(val staged: Map<String, LineCount> = emptyMap(), val unstaged: Map<String, LineCount> = emptyMap()) {
    /** Every staged and unstaged line, for the Changes tab's "+N −M" summary. */
    val total: LineCount get() = (staged.values + unstaged.values).fold(LineCount.ZERO, LineCount::plus)

    /** The counts of [path] on its staged or unstaged side, or null when git has none (untracked, binary, unknown). */
    fun of(path: String, staged: Boolean): LineCount? = (if (staged) this.staged else unstaged)[path]?.takeIf { it.added > 0 || it.removed > 0 }

    companion object {
        val EMPTY = DiffStats()
    }
}

/** `git diff --numstat -z` output. */
object NumStat {
    /**
     * Records are `added<TAB>removed<TAB>path<NUL>`; a rename leaves the path empty and gives `old<NUL>new<NUL>` after it
     * (counted under the new path). A binary file has `-` for both counts and reads as zero.
     */
    fun parse(raw: String): Map<String, LineCount> {
        val out = LinkedHashMap<String, LineCount>()
        val tokens = raw.split('\u0000')
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            i++
            if (t.isEmpty()) continue
            val parts = t.split('\t', limit = 3)
            if (parts.size < 3) continue
            val count = LineCount(parts[0].toIntOrNull() ?: 0, parts[1].toIntOrNull() ?: 0)
            val path = if (parts[2].isEmpty()) {
                // Rename: old, then new.
                val new = tokens.getOrNull(i + 1)
                i += 2
                new
            } else {
                parts[2]
            }
            if (!path.isNullOrEmpty()) out[path] = (out[path] ?: LineCount.ZERO) + count
        }
        return out
    }
}
