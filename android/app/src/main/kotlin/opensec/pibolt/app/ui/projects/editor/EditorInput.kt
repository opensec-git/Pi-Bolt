package opensec.pibolt.app.ui.projects.editor

/** A text and its selection `[start, end)` (start == end is a cursor). Plain, so the rules are unit-tested. */
data class EditState(val text: String, val start: Int, val end: Int = start) {
    val cursor: Int get() = end
}

/**
 * Typing rules of the editor (pure, unit-tested): auto-indent on Enter, bracket and quote pairs, typing over a closing
 * bracket, and Backspace removing an empty pair. [transform] looks at one change of the text field (old to new) and
 * returns the state to show instead, or null to keep the field's own result.
 */
object EditorInput {
    private val pairs = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'', '`' to '`')
    private val closers = setOf(')', ']', '}')

    /** Lines ending in one of these open a block: the next line is indented one more step. */
    private val openers = setOf('{', '(', '[', ':')

    fun transform(old: EditState, new: EditState, pairBrackets: Boolean = true, autoIndent: Boolean = true): EditState? {
        // Only a single character typed at a cursor (or over a selection), or a single Backspace.
        val inserted = new.text.length - old.text.length + (old.end - old.start)
        val selStart = minOf(old.start, old.end)
        val selEnd = maxOf(old.start, old.end)
        if (new.start != new.end) return null
        val typedOne = inserted == 1 && new.cursor == selStart + 1 && new.text.length == old.text.length - (selEnd - selStart) + 1 &&
            new.text.regionMatches(0, old.text, 0, selStart) && new.text.regionMatches(selStart + 1, old.text, selEnd, old.text.length - selEnd)
        if (typedOne) {
            val c = new.text[selStart]
            val before = old.text.substring(0, selStart)
            val after = old.text.substring(selEnd)
            if (c == '\n' && autoIndent) return newline(before, after)
            if (!pairBrackets) return null
            // Typing a closer (or the same quote) just before that character steps over it.
            if (selStart == selEnd && (c in closers || c in QUOTES) && after.firstOrNull() == c && (c !in QUOTES || !isWord(before.lastOrNull()))) {
                return EditState(old.text, selStart + 1)
            }
            val close = pairs[c] ?: return null
            if (selStart != selEnd) {
                // Wrap the selection.
                val selected = old.text.substring(selStart, selEnd)
                return EditState(before + c + selected + close + after, selStart + 1, selEnd + 1)
            }
            if (c in QUOTES && (isWord(before.lastOrNull()) || isWord(after.firstOrNull()))) return null
            if (c !in QUOTES && isWord(after.firstOrNull())) return null
            return EditState(before + c + close + after, selStart + 1)
        }
        // Backspace between an empty pair removes both.
        if (old.start == old.end && new.text.length == old.text.length - 1 && new.cursor == old.cursor - 1 && pairBrackets) {
            val i = old.cursor
            if (i in 1 until old.text.length) {
                val open = old.text[i - 1]
                if (pairs[open] == old.text[i] && new.text == old.text.removeRange(i - 1, i)) {
                    return EditState(old.text.removeRange(i - 1, i + 1), i - 1)
                }
            }
        }
        return null
    }

    /** Enter: the new line takes the indent of the line above, one step more after an opener, and `{|}` splits in three. */
    fun newline(before: String, after: String): EditState {
        val lineStart = before.lastIndexOf('\n') + 1
        val line = before.substring(lineStart)
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        val unit = indentUnit(before + after)
        val last = line.trimEnd().lastOrNull()
        val opens = last != null && last in openers
        val next = after.firstOrNull()
        return when {
            opens && next != null && pairs[last] == next -> {
                val inner = "\n" + indent + unit
                EditState(before + inner + "\n" + indent + after, before.length + inner.length)
            }
            opens -> {
                val inner = "\n" + indent + unit
                EditState(before + inner + after, before.length + inner.length)
            }
            else -> EditState(before + "\n" + indent + after, before.length + 1 + indent.length)
        }
    }

    /** The file's indent step: a tab when lines start with tabs, else the most common small space step (2 or 4). */
    fun indentUnit(text: String, sample: Int = 2_000): String {
        var tabs = 0
        var twos = 0
        var fours = 0
        var lines = 0
        var i = 0
        while (i < text.length && lines < sample) {
            val nl = text.indexOf('\n', i).let { if (it < 0) text.length else it }
            if (i < nl) {
                if (text[i] == '\t') {
                    tabs++
                } else {
                    var n = 0
                    while (i + n < nl && text[i + n] == ' ') n++
                    if (n > 0 && i + n < nl) {
                        if (n % 4 == 0) fours++ else if (n % 2 == 0) twos++
                    }
                }
            }
            lines++
            i = nl + 1
        }
        return when {
            tabs > twos + fours -> "\t"
            twos > 0 && twos * 3 >= fours -> "  "
            fours > 0 -> "    "
            else -> "    "
        }
    }

    /** 1-based line and column of [offset]. */
    fun lineCol(text: CharSequence, offset: Int): Pair<Int, Int> {
        val o = offset.coerceIn(0, text.length)
        var line = 1
        var lineStart = 0
        for (i in 0 until o) if (text[i] == '\n') {
            line++
            lineStart = i + 1
        }
        return line to (o - lineStart + 1)
    }

    /** The offset where 0-based [line] starts, clamped to the text. */
    fun lineStart(text: CharSequence, line: Int): Int {
        if (line <= 0) return 0
        var n = 0
        for (i in text.indices) if (text[i] == '\n' && ++n == line) return i + 1
        return text.length
    }

    /** Number of lines (a trailing newline starts an empty last line, as editors count it). */
    fun lineCount(text: CharSequence): Int {
        var n = 1
        for (c in text) if (c == '\n') n++
        return n
    }

    /**
     * The bracket just before [cursor] (or at it) and its partner, as two offsets in order; null when neither side is a
     * bracket or the partner is not within [limit] characters. Brackets in strings and comments count too: it is a
     * hint for the eye, not a parser.
     */
    fun matchingBracket(text: CharSequence, cursor: Int, limit: Int = 20_000): Pair<Int, Int>? {
        val at = when {
            cursor > 0 && cursor <= text.length && text[cursor - 1] in BRACKETS -> cursor - 1
            cursor < text.length && text[cursor] in BRACKETS -> cursor
            else -> return null
        }
        val c = text[at]
        val open = c in OPEN
        val partner = if (open) pairs[c]!! else OPEN.first { pairs[it] == c }
        var depth = 0
        var i = at
        var steps = 0
        while (i in text.indices && steps++ <= limit) {
            val k = text[i]
            if (k == c) depth++ else if (k == partner) depth--
            if (depth == 0) return if (open) at to i else i to at
            i += if (open) 1 else -1
        }
        return null
    }

    private val OPEN = setOf('(', '[', '{')
    private val BRACKETS = setOf('(', '[', '{', ')', ']', '}')

    private val QUOTES = setOf('"', '\'', '`')

    private fun isWord(c: Char?): Boolean = c != null && (c.isLetterOrDigit() || c == '_')
}
