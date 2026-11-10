package opensec.pibolt.app.ui.projects.editor

import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** What the editor's find bar searches for. */
data class FindOptions(
    val query: String,
    val regex: Boolean = false,
    val matchCase: Boolean = false,
    val wholeWord: Boolean = false,
)

/** One match: `[start, end)` in the text. */
data class TextMatch(val start: Int, val end: Int)

/** The outcome of a search: the matches (at most [FindReplace.MAX_MATCHES]), or why there are none. */
data class FindResult(val matches: List<TextMatch>, val capped: Boolean = false, val error: String? = null) {
    companion object {
        val EMPTY = FindResult(emptyList())
    }
}

/** A replacement: the text between [start] and [end] becomes [text]. */
data class Replacement(val start: Int, val end: Int, val text: String)

/**
 * Find and replace for the editor (pure, unit-tested). Plain queries are matched literally; regex queries use the
 * platform's `java.util.regex` with `$1` / `${name}` groups in the replacement. Whole word means the match is not
 * touching a letter, digit or `_` on either side. A search runs against an [Interruptible] view of the text, so a
 * pathological regex ends at its deadline instead of hanging the editor.
 */
object FindReplace {
    const val MAX_MATCHES = 10_000

    /** A regex search gives up after this long (it runs off the main thread, this bounds the CPU it may burn). */
    const val DEADLINE_MS = 1_500L

    /** The compiled pattern for [options], or the regex syntax error. */
    fun compile(options: FindOptions): Result<Pattern> {
        if (options.query.isEmpty()) return Result.failure(IllegalArgumentException("Empty query"))
        val body = if (options.regex) options.query else Pattern.quote(options.query)
        val bounded = if (options.wholeWord) "(?<![\\p{L}\\p{N}_])(?:$body)(?![\\p{L}\\p{N}_])" else body
        var flags = Pattern.MULTILINE
        if (!options.matchCase) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        return try {
            Result.success(Pattern.compile(bounded, flags))
        } catch (e: PatternSyntaxException) {
            Result.failure(IllegalArgumentException(e.description ?: "Invalid pattern"))
        }
    }

    /** Every non-empty match of [options] in [text], in order. Empty matches (`^`, `a*`) are skipped. */
    fun find(text: CharSequence, options: FindOptions, cap: Int = MAX_MATCHES, deadlineMs: Long = DEADLINE_MS): FindResult {
        if (options.query.isEmpty()) return FindResult.EMPTY
        val pattern = compile(options).getOrElse { return FindResult(emptyList(), error = it.message) }
        val out = ArrayList<TextMatch>()
        val source = if (options.regex) Interruptible(text, System.nanoTime() + deadlineMs * 1_000_000) else text
        return try {
            val m = pattern.matcher(source)
            while (m.find()) {
                if (m.end() > m.start()) {
                    out += TextMatch(m.start(), m.end())
                    if (out.size >= cap) return FindResult(out, capped = true)
                }
            }
            FindResult(out)
        } catch (_: Interruptible.TimedOut) {
            FindResult(out, error = "The pattern took too long")
        } catch (_: StackOverflowError) {
            FindResult(emptyList(), error = "The pattern is too complex")
        }
    }

    /**
     * The replacement for the match at [match] in [text]: [replacement] with its groups expanded (regex) or as typed
     * (plain). Null when the match is no longer there (the text changed since the search).
     */
    fun replacementFor(text: CharSequence, options: FindOptions, match: TextMatch, replacement: String): Replacement? {
        val pattern = compile(options).getOrNull() ?: return null
        val m = pattern.matcher(text)
        m.region(match.start, text.length)
        m.useTransparentBounds(true)
        m.useAnchoringBounds(false)
        if (!m.lookingAt() || m.end() != match.end) return null
        return Replacement(match.start, match.end, expand(m, if (options.regex) replacement else Matcher.quoteReplacement(replacement)))
    }

    /**
     * [text] with every match replaced, and the number replaced. Returns null for an invalid pattern. The whole result
     * is one [Replacement] covering the first to the last match, so undo restores it as one step.
     */
    fun replaceAll(text: String, options: FindOptions, replacement: String): Pair<Replacement, Int>? {
        val pattern = compile(options).getOrNull() ?: return null
        val m = pattern.matcher(text)
        val repl = if (options.regex) replacement else Matcher.quoteReplacement(replacement)
        val sb = StringBuilder()
        var count = 0
        var first = -1
        var last = 0
        while (m.find()) {
            if (m.end() == m.start()) continue
            if (first < 0) first = m.start()
            sb.append(text, if (count == 0) first else last, m.start())
            sb.append(expand(m, repl))
            last = m.end()
            count++
        }
        if (count == 0) return Replacement(0, 0, "") to 0
        return Replacement(first, last, sb.toString()) to count
    }

    /** [replacement] expanded for the current match of [m] (`$1`, `${name}`, `\$` for a literal dollar). */
    private fun expand(m: Matcher, replacement: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < replacement.length) {
            val c = replacement[i]
            when {
                c == '\\' && i + 1 < replacement.length -> {
                    sb.append(replacement[i + 1])
                    i += 2
                }
                c == '$' && i + 1 < replacement.length && replacement[i + 1] == '{' -> {
                    val close = replacement.indexOf('}', i + 2)
                    require(close > 0) { "Unclosed group name" }
                    sb.append(m.group(replacement.substring(i + 2, close)).orEmpty())
                    i = close + 1
                }
                c == '$' && i + 1 < replacement.length && replacement[i + 1].isDigit() -> {
                    // The longest group number that exists, as java.util.regex reads it.
                    var j = i + 1
                    var group = replacement[j] - '0'
                    j++
                    while (j < replacement.length && replacement[j].isDigit()) {
                        val next = group * 10 + (replacement[j] - '0')
                        if (next > m.groupCount()) break
                        group = next
                        j++
                    }
                    if (group > m.groupCount()) throw IndexOutOfBoundsException("No group $group")
                    sb.append(m.group(group).orEmpty())
                    i = j
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    /** Why [replacement] can't be used with [options] (a missing group), or null. */
    fun replacementError(options: FindOptions, replacement: String): String? {
        if (!options.regex) return null
        val pattern = compile(options).getOrNull() ?: return null
        val groups = pattern.matcher("").groupCount()
        var i = 0
        while (i < replacement.length) {
            val c = replacement[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (c == '$' && i + 1 < replacement.length) {
                val n = replacement[i + 1]
                if (n.isDigit() && n - '0' > groups) return "There is no group ${n - '0'}"
                if (n == '{') {
                    val close = replacement.indexOf('}', i + 2)
                    if (close < 0) return "Close the group name with }"
                    val name = replacement.substring(i + 2, close)
                    if (!pattern.pattern().contains("(?<$name>")) return "There is no group named $name"
                }
            }
            i++
        }
        return null
    }

    /** The index of the first match at or after [offset], wrapping to the first; -1 without matches. */
    fun nearest(matches: List<TextMatch>, offset: Int): Int {
        if (matches.isEmpty()) return -1
        var lo = 0
        var hi = matches.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (matches[mid].start < offset) lo = mid + 1 else hi = mid
        }
        return if (lo == matches.size) 0 else lo
    }

    /** "3 of 12", "3 of 10000+", "No matches". */
    fun counter(current: Int, total: Int, capped: Boolean): String = when {
        total == 0 -> "No matches"
        capped -> "${current + 1} of $total+"
        else -> "${current + 1} of $total"
    }
}

/**
 * A [CharSequence] that throws [TimedOut] once [deadline] (a [System.nanoTime] value) has passed. The regex engine
 * reads every character through [get], so this is the one place a runaway pattern can be stopped.
 */
class Interruptible(private val inner: CharSequence, private val deadline: Long) : CharSequence {
    class TimedOut : RuntimeException("Search timed out")

    private var reads = 0

    override val length: Int get() = inner.length

    override fun get(index: Int): Char {
        if (++reads and 0x3FFF == 0 && System.nanoTime() > deadline) throw TimedOut()
        return inner[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = Interruptible(inner.subSequence(startIndex, endIndex), deadline)

    override fun toString(): String = inner.toString()
}
