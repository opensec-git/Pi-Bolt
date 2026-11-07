package opensec.pibolt.app.ui.session.features

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * `@` file mentions, as in the terminal: typing `@` and part of a path offers the project's files; picking one puts its
 * path (relative to the project) in the prompt.
 */
object FileMentions {
    /** The `@word` the cursor is in: [start] is the `@`, [end] the end of the word, [query] what follows the `@`. */
    data class Token(val start: Int, val end: Int, val query: String)

    /** Folders never offered (and not walked): version control, dependencies and build output. */
    val SKIPPED = setOf(".git", "node_modules", ".gradle", "build", "dist", "out", "target", ".next", ".venv", "venv", "__pycache__", ".idea", ".cache")

    const val MAX_FILES = 20_000
    const val SHOWN = 30

    /**
     * The token at [cursor]: an `@` at the start of the text or after whitespace, followed by non-space characters up
     * to the cursor. An e-mail address (`a@b`) is not one.
     */
    fun tokenAt(text: String, cursor: Int): Token? {
        if (cursor < 0 || cursor > text.length) return null
        var i = cursor - 1
        while (i >= 0 && !text[i].isWhitespace() && text[i] != '@') i--
        if (i < 0 || text[i] != '@') return null
        if (i > 0 && !text[i - 1].isWhitespace()) return null
        var end = cursor
        while (end < text.length && !text[end].isWhitespace()) end++
        return Token(i, end, text.substring(i + 1, cursor))
    }

    /** [text] with [token] replaced by `@path ` (quoted when the path has spaces), and the cursor after it. */
    fun insert(text: String, token: Token, path: String): Pair<String, Int> {
        val mention = if (path.any { it.isWhitespace() }) "@\"$path\"" else "@$path"
        val after = text.substring(token.end)
        val spaced = if (after.startsWith(" ")) mention else "$mention "
        val next = text.substring(0, token.start) + spaced + after
        return next to token.start + spaced.length + if (after.startsWith(" ")) 1 else 0
    }

    /** Best matches for [query] among [paths]: the file name counts more than its folders. */
    fun rank(query: String, paths: List<String>, limit: Int = SHOWN): List<String> {
        if (query.isEmpty()) return paths.sortedWith(compareBy<String> { it.count { c -> c == '/' } }.thenBy { it.lowercase() }).take(limit)
        return paths.mapIndexedNotNull { i, path ->
            val name = path.substringAfterLast('/')
            val byName = Fuzzy.score(query, name)?.plus(300)
            val byPath = Fuzzy.score(query, path)
            val best = listOfNotNull(byName, byPath).maxOrNull() ?: return@mapIndexedNotNull null
            Triple(path, best, i)
        }.sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenBy { it.first.length }.thenBy { it.third })
            .take(limit)
            .map { it.first }
    }

    /** Files under [root] as relative paths with `/`, breadth first, at most [max]; folders end with `/`. */
    fun walk(root: File, max: Int = MAX_FILES): List<String> {
        val out = ArrayList<String>()
        val queue = ArrayDeque<Pair<File, String>>()
        queue.add(root to "")
        while (queue.isNotEmpty() && out.size < max) {
            val (dir, prefix) = queue.removeFirst()
            val children = dir.listFiles()?.sortedBy { it.name.lowercase() } ?: continue
            for (child in children) {
                if (out.size >= max) break
                val rel = prefix + child.name
                if (child.isDirectory) {
                    if (child.name in SKIPPED) continue
                    out += "$rel/"
                    queue.add(child to "$rel/")
                } else {
                    out += rel
                }
            }
        }
        return out
    }
}

/** The project's file list for `@` mentions, read once per screen on first use (off the main thread). */
@Stable
class ProjectFileIndex(private val root: File?) {
    val files = mutableStateOf<List<String>?>(null)
    private var loading = false

    val available: Boolean get() = root != null

    suspend fun load() {
        if (root == null || loading || files.value != null) return
        loading = true
        // A cancelled first load (the `@` typed away before the walk ended) left [loading] set: no later `@` loaded again.
        try {
            files.value = withContext(Dispatchers.IO) { runCatching { FileMentions.walk(root) }.getOrDefault(emptyList()) }
        } finally {
            loading = false
        }
    }
}
