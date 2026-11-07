package opensec.pibolt.app.ui.projects.editor

/**
 * "Ask Pi about this file / this selection" (pure, unit-tested): the text the editor puts into the project's composer.
 * A file is an `@path` mention (the engine reads it, as when typed). A selection adds the selected lines, numbered by
 * their range and fenced with the file's language, so the question can point at them; very long selections are cut to
 * [MAX_LINES] lines with a note, the mention still lets Pi read the rest.
 */
object AskPi {
    const val MAX_LINES = 200
    const val MAX_CHARS = 16_000

    /** The `@` mention for [path], quoted when it has spaces, as the composer's `@` picker writes it. */
    fun mention(path: String): String = if (path.any { it.isWhitespace() }) "@\"$path\"" else "@$path"

    /**
     * What to insert for [path], with the lines of [text] that [selStart, selEnd) touches when it is not empty.
     * [language] tags the fence (null for none).
     */
    fun prompt(path: String, text: String, selStart: Int, selEnd: Int, language: String?): String {
        val a = minOf(selStart, selEnd).coerceIn(0, text.length)
        val b = maxOf(selStart, selEnd).coerceIn(0, text.length)
        if (a == b) return mention(path) + " "
        val first = text.lastIndexOf('\n', a - 1) + 1
        // A selection ending just after a newline does not take the next line.
        val lastChar = if (b > a && text[b - 1] == '\n') b - 1 else b
        val endNl = text.indexOf('\n', lastChar).let { if (it < 0) text.length else it }
        val lineA = text.substring(0, first).count { it == '\n' } + 1
        var body = text.substring(first, endNl)
        var lines = body.split('\n')
        val lineB = lineA + lines.size - 1
        var cut = false
        if (lines.size > MAX_LINES) {
            lines = lines.take(MAX_LINES)
            cut = true
        }
        body = lines.joinToString("\n")
        if (body.length > MAX_CHARS) {
            body = body.substring(0, MAX_CHARS).substringBeforeLast('\n')
            cut = true
        }
        val fence = if (body.contains("```")) "````" else "```"
        val range = if (lineA == lineB) "line $lineA" else "lines $lineA-$lineB"
        return buildString {
            append(mention(path)).append(' ').append(range).append(":\n")
            append(fence).append(language.orEmpty()).append('\n')
            append(body).append('\n')
            append(fence).append('\n')
            if (cut) append("(selection cut; read the file for the rest)\n")
        }
    }

    /** [draft] with [insert] added: on a new line after any text already typed, the cursor at the end. */
    fun appendTo(draft: String, insert: String): Pair<String, Int> {
        val next = when {
            draft.isEmpty() -> insert
            draft.endsWith("\n") -> draft + insert
            else -> draft + "\n" + insert
        }
        return next to next.length
    }
}
