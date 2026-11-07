package opensec.pibolt.app.git

import androidx.compose.runtime.Immutable

/** One `@@` hunk: its header line and the body lines that follow it (each still starting with ` `, `+`, `-` or `\`). */
@Immutable
data class Hunk(val header: String, val lines: List<String>) {
    val added: Int get() = lines.count { it.startsWith("+") }
    val removed: Int get() = lines.count { it.startsWith("-") }

    /** The hunk as unified diff text: header, then body. */
    val text: String get() = buildString {
        append(header).append('\n')
        for (l in lines) append(l).append('\n')
    }
}

/**
 * One file of a `git diff`: [header] is every line from `diff --git` up to the first hunk (index, mode, `---`/`+++`),
 * which a patch for `git apply` needs in front of its hunks.
 */
@Immutable
data class FileDiff(
    val path: String,
    val oldPath: String?,
    val header: List<String>,
    val hunks: List<Hunk>,
    val binary: Boolean = false,
    val newFile: Boolean = false,
    val deleted: Boolean = false,
) {
    val added: Int get() = hunks.sumOf { it.added }
    val removed: Int get() = hunks.sumOf { it.removed }

    /** A patch holding only hunk [index], for `git apply --cached` (stage), `--cached -R` (unstage) or `-R` (discard). */
    fun patchFor(index: Int): String = buildString {
        for (h in header) append(h).append('\n')
        append(hunks[index].text)
    }

    /** All hunks as one unified diff (the rows renderer reads this). */
    val text: String get() = buildString { for (h in hunks) append(h.text) }
}

/** `git diff` / `git show` output split per file and per hunk. Lines before the first `diff --git` are ignored. */
object UnifiedDiffParser {
    fun parse(raw: String): List<FileDiff> {
        val files = ArrayList<FileDiff>()
        var header = ArrayList<String>()
        var hunks = ArrayList<Hunk>()
        var hunkHeader: String? = null
        var hunkLines = ArrayList<String>()
        var started = false

        fun endHunk() {
            hunkHeader?.let { hunks += Hunk(it, hunkLines) }
            hunkHeader = null
            hunkLines = ArrayList()
        }

        fun endFile() {
            if (!started) return
            endHunk()
            files += fileOf(header, hunks)
            header = ArrayList()
            hunks = ArrayList()
        }

        val lines = raw.split('\n')
        // A trailing newline leaves one empty last element: not a line of the diff.
        val n = if (lines.isNotEmpty() && lines.last().isEmpty()) lines.size - 1 else lines.size
        for (k in 0 until n) {
            val line = lines[k]
            when {
                line.startsWith("diff --git ") || line.startsWith("diff --cc ") -> {
                    endFile()
                    started = true
                    header += line
                }
                !started -> Unit
                line.startsWith("@@") -> {
                    endHunk()
                    hunkHeader = line
                }
                hunkHeader != null && (line.isEmpty() || line[0] == ' ' || line[0] == '+' || line[0] == '-' || line[0] == '\\') -> hunkLines += line
                hunkHeader != null -> {
                    // Something else after a hunk (e.g. `git show` stat text): ends the hunk, not the file.
                    endHunk()
                }
                else -> header += line
            }
        }
        endFile()
        return files
    }

    private fun fileOf(header: List<String>, hunks: List<Hunk>): FileDiff {
        var minus: String? = null
        var plus: String? = null
        var renameFrom: String? = null
        var renameTo: String? = null
        for (h in header) {
            when {
                h.startsWith("--- ") -> minus = clean(h.substring(4))
                h.startsWith("+++ ") -> plus = clean(h.substring(4))
                h.startsWith("rename from ") -> renameFrom = h.removePrefix("rename from ")
                h.startsWith("rename to ") -> renameTo = h.removePrefix("rename to ")
                h.startsWith("copy from ") -> renameFrom = h.removePrefix("copy from ")
                h.startsWith("copy to ") -> renameTo = h.removePrefix("copy to ")
            }
        }
        val fromGit = gitLinePaths(header.first())
        val path = plus ?: renameTo ?: minus ?: fromGit?.second ?: ""
        val old = (renameFrom ?: minus ?: fromGit?.first)?.takeIf { it != path }
        return FileDiff(
            path = path,
            oldPath = old,
            header = header,
            hunks = hunks,
            binary = header.any { it.startsWith("Binary files ") || it == "GIT binary patch" },
            newFile = header.any { it.startsWith("new file mode") },
            deleted = header.any { it.startsWith("deleted file mode") },
        )
    }

    private fun clean(raw: String): String? {
        val p = unquote(raw.substringBefore('\t'))
        if (p == "/dev/null") return null
        return if (p.startsWith("a/") || p.startsWith("b/")) p.substring(2) else p
    }

    /** `diff --git a/x b/x`: the two paths when they are unquoted and equal length halves (no rename info needed). */
    private fun gitLinePaths(line: String): Pair<String, String>? {
        val rest = line.removePrefix("diff --git ").removePrefix("diff --cc ")
        if (rest.startsWith("\"")) return null
        if (!rest.startsWith("a/")) return rest to rest
        val half = (rest.length - 1) / 2
        val a = rest.substring(0, half)
        val b = rest.substring(half + 1)
        return if (b.startsWith("b/") && a.substring(2) == b.substring(2)) a.substring(2) to b.substring(2) else null
    }

    /** Git C-quotes paths with special characters (`"a/caf\303\251"`); undo that, decoding octal bytes as UTF-8. */
    fun unquote(p: String): String {
        if (p.length < 2 || !p.startsWith("\"") || !p.endsWith("\"")) return p
        val bytes = java.io.ByteArrayOutputStream()
        var i = 1
        while (i < p.length - 1) {
            val c = p[i]
            if (c == '\\' && i + 1 < p.length - 1) {
                val n = p[i + 1]
                if (n in '0'..'7' && i + 3 < p.length) {
                    bytes.write(p.substring(i + 1, i + 4).toInt(8))
                    i += 4
                    continue
                }
                bytes.write(
                    when (n) {
                        'n' -> '\n'.code
                        't' -> '\t'.code
                        'a' -> 7
                        'b' -> 8
                        'f' -> 12
                        'r' -> '\r'.code
                        'v' -> 11
                        else -> n.code
                    },
                )
                i += 2
                continue
            }
            bytes.write(c.toString().toByteArray())
            i++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
}
