package opensec.pibolt.app.ui.markdown

import androidx.compose.runtime.Immutable
import java.util.Arrays

/**
 * A styled range of inline text. [url] is the link or image address. For [Kind.Math] the range holds the TeX source,
 * for [Kind.Image] the alt text and for [Kind.FootnoteRef] the footnote's label.
 */
@Immutable
data class InlineSpan(val start: Int, val end: Int, val kind: Kind, val url: String? = null) {
    enum class Kind { Bold, Italic, Strike, Code, Link, Image, Math, FootnoteRef, Superscript, Subscript, Underline, Highlight }
}

@Immutable
data class InlineText(val text: String, val spans: List<InlineSpan>)

/**
 * Inline markdown (CommonMark + GFM as models write it): emphasis, strikethrough, code spans, links, images,
 * autolinks (`<…>` and bare `https://…` / `www.`), footnote references, `$…$` / `\(…\)` math, backslash escapes, HTML
 * entities, hard breaks and a few inline HTML tags (`<br>`, `<sub>`, `<sup>`, `<kbd>`, `<b>`, `<i>`, `<u>`, `<s>`,
 * `<mark>`, `<code>`). Every search for a closing marker is amortised ([Closers]), so hostile text stays linear.
 */
object Inline {
    /** Strip inline markers from [source] and report styled ranges in the stripped text. */
    fun parse(source: String): InlineText {
        val out = StringBuilder(source.length)
        val spans = ArrayList<InlineSpan>()
        parseInto(source, out, spans, 0, autolinks = true)
        return InlineText(out.toString(), spans)
    }

    private const val PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

    /** Longest link destination or autolink looked at; longer ones stay text (bounds the work per `[`). */
    private const val MAX_URL = 2048

    private const val MAX_DEPTH = 12

    private val htmlStyles = mapOf(
        "b" to InlineSpan.Kind.Bold, "strong" to InlineSpan.Kind.Bold, "i" to InlineSpan.Kind.Italic,
        "em" to InlineSpan.Kind.Italic, "s" to InlineSpan.Kind.Strike, "del" to InlineSpan.Kind.Strike,
        "strike" to InlineSpan.Kind.Strike, "u" to InlineSpan.Kind.Underline, "ins" to InlineSpan.Kind.Underline,
        "sub" to InlineSpan.Kind.Subscript, "sup" to InlineSpan.Kind.Superscript, "kbd" to InlineSpan.Kind.Code,
        "mark" to InlineSpan.Kind.Highlight, "code" to InlineSpan.Kind.Code,
    )

    /** Wrapper tags that carry no meaning here: dropped, their content kept. */
    private val htmlDropped = setOf("p", "div", "span", "center", "small", "big", "font", "details", "summary", "section", "a")

    private fun parseInto(s: String, out: StringBuilder, spans: MutableList<InlineSpan>, depth: Int, autolinks: Boolean) {
        if (depth > MAX_DEPTH) {
            out.append(s)
            return
        }
        val closers = Closers(s)
        var i = 0
        val n = s.length
        fun nested(from: Int, to: Int, kind: InlineSpan.Kind, url: String? = null) {
            val start = out.length
            // Link text holds no further links.
            parseInto(s.substring(from, to), out, spans, depth + 1, autolinks && kind != InlineSpan.Kind.Link)
            spans += InlineSpan(start, out.length, kind, url)
        }
        while (i < n) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < n -> {
                    val next = s[i + 1]
                    // `\(…\)` is inline math; `\[` in prose is an escaped bracket (display math has its own line).
                    if (next == '(') {
                        val close = closers.indexOf("\\)", i + 2)
                        if (close > i + 2 && closers.hasLetterOrDigit(i + 2, close)) {
                            val start = out.length
                            out.append(s, i + 2, close)
                            spans += InlineSpan(start, out.length, InlineSpan.Kind.Math)
                            i = close + 2
                            continue
                        }
                    }
                    when {
                        next in PUNCTUATION -> {
                            out.append(next)
                            i += 2
                        }
                        // A backslash at the end of a line is a hard break; lines are already kept as breaks.
                        next == '\n' -> i++
                        else -> {
                            out.append(c)
                            i++
                        }
                    }
                }
                c == '`' -> {
                    var ticks = 1
                    while (i + ticks < n && s[i + ticks] == '`') ticks++
                    val marker = "`".repeat(ticks)
                    var close = closers.indexOf(marker, i + ticks)
                    // A longer run of backticks does not close a shorter opener.
                    while (close >= 0 && close + ticks < n && s[close + ticks] == '`') {
                        var end = close + ticks
                        while (end < n && s[end] == '`') end++
                        close = closers.indexOf(marker, end)
                    }
                    if (close < 0) {
                        out.append(marker)
                        i += ticks
                    } else {
                        val raw = s.substring(i + ticks, close).replace('\n', ' ')
                        val code = if (raw.length > 2 && raw.startsWith(" ") && raw.endsWith(" ") && raw.isNotBlank()) raw.substring(1, raw.length - 1) else raw
                        val start = out.length
                        out.append(code)
                        spans += InlineSpan(start, out.length, InlineSpan.Kind.Code)
                        i = close + ticks
                    }
                }
                c == '$' -> {
                    i = math(s, i, closers, out, spans)
                }
                (c == '*' || c == '_') && i + 1 < n && s[i + 1] == c -> {
                    val marker = "$c$c"
                    var close = closers.indexOf(marker, i + 2)
                    // `***a***`: the inner `*a*` keeps its own closer, so take the last two of the closing run.
                    while (close >= 0 && close + 2 < n && s[close + 2] == c && i + 2 < n && s[i + 2] == c) close++
                    val wordInside = c == '_' && i > 0 && s[i - 1].isLetterOrDigit()
                    if (close > i + 2 && !s[i + 2].isWhitespace() && !s[close - 1].isWhitespace() && !wordInside) {
                        nested(i + 2, close, InlineSpan.Kind.Bold)
                        i = close + 2
                    } else {
                        out.append(marker)
                        i += 2
                    }
                }
                c == '*' || c == '_' -> {
                    val close = closers.single(c, i + 1)
                    val wordInside = c == '_' && i > 0 && s[i - 1].isLetterOrDigit()
                    val closeInWord = c == '_' && close >= 0 && close + 1 < n && s[close + 1].isLetterOrDigit()
                    if (close > i + 1 && !s[i + 1].isWhitespace() && !wordInside && !closeInWord) {
                        nested(i + 1, close, InlineSpan.Kind.Italic)
                        i = close + 1
                    } else {
                        out.append(c)
                        i++
                    }
                }
                c == '~' && i + 1 < n && s[i + 1] == '~' -> {
                    val close = closers.indexOf("~~", i + 2)
                    if (close > i + 2 && !s[i + 2].isWhitespace()) {
                        nested(i + 2, close, InlineSpan.Kind.Strike)
                        i = close + 2
                    } else {
                        out.append("~~")
                        i += 2
                    }
                }
                c == '!' && i + 1 < n && s[i + 1] == '[' -> {
                    val link = link(s, i + 1, closers)
                    if (link != null) {
                        val start = out.length
                        out.append(plainText(s.substring(i + 2, link.textEnd)))
                        spans += InlineSpan(start, out.length, InlineSpan.Kind.Image, link.url)
                        i = link.end
                    } else {
                        out.append(c)
                        i++
                    }
                }
                c == '[' -> {
                    if (i + 1 < n && s[i + 1] == '^') {
                        val close = closers.indexOf("]", i + 2)
                        if (close in i + 3..i + 40 && (i + 2 until close).none { s[it].isWhitespace() }) {
                            val start = out.length
                            out.append(s, i + 2, close)
                            spans += InlineSpan(start, out.length, InlineSpan.Kind.FootnoteRef)
                            i = close + 1
                            continue
                        }
                    }
                    val link = link(s, i, closers)
                    if (link != null) {
                        nested(i + 1, link.textEnd, InlineSpan.Kind.Link, link.url)
                        i = link.end
                    } else {
                        out.append(c)
                        i++
                    }
                }
                c == '<' -> i = angle(s, i, closers, out, spans, depth, autolinks)
                c == '&' -> {
                    val end = Entities.match(s, i)
                    if (end > 0) {
                        out.append(Entities.decode(s.substring(i, end)) ?: s.substring(i, end))
                        i = end
                    } else {
                        out.append(c)
                        i++
                    }
                }
                autolinks && (c == 'h' || c == 'w' || c == 'H' || c == 'W') && (i == 0 || !s[i - 1].isLetterOrDigit() && s[i - 1] !in "/:@.") -> {
                    val end = bareUrl(s, i)
                    if (end > i) {
                        val raw = s.substring(i, end)
                        val start = out.length
                        out.append(raw)
                        spans += InlineSpan(start, out.length, InlineSpan.Kind.Link, if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw)
                        i = end
                    } else {
                        out.append(c)
                        i++
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
    }

    /** `$…$` and `$$…$$` at [i]; returns the next index. Currency (`$5 and $10`) stays text. */
    private fun math(s: String, i: Int, closers: Closers, out: StringBuilder, spans: MutableList<InlineSpan>): Int {
        val n = s.length
        if (i + 1 < n && s[i + 1] == '$') {
            val close = closers.indexOf("$$", i + 2)
            if (close > i + 2 && s.substring(i + 2, close).isNotBlank()) {
                val start = out.length
                out.append(s.substring(i + 2, close).trim())
                spans += InlineSpan(start, out.length, InlineSpan.Kind.Math)
                return close + 2
            }
            out.append("$$")
            return i + 2
        }
        // Pandoc's rule: no space inside the delimiters, the closer not followed by a digit, and nothing between
        // them that is itself a dollar.
        if (i + 1 < n && !s[i + 1].isWhitespace()) {
            val close = s.indexOf('$', i + 1)
            if (close > i + 1 && !s[close - 1].isWhitespace() && s[close - 1] != '\\' &&
                (close + 1 >= n || !s[close + 1].isDigit()) && (i + 1 until close).none { s[it] == '\n' }
            ) {
                val start = out.length
                out.append(s, i + 1, close)
                spans += InlineSpan(start, out.length, InlineSpan.Kind.Math)
                return close + 1
            }
        }
        out.append('$')
        return i + 1
    }

    private class Link(val textEnd: Int, val url: String, val end: Int)

    /** `[text](dest "title")` starting at the `[` at [i], or null. The destination may hold balanced parentheses. */
    private fun link(s: String, i: Int, closers: Closers): Link? {
        val closeText = closers.indexOf("]", i + 1)
        if (closeText < 0 || closeText + 1 >= s.length || s[closeText + 1] != '(') return null
        var j = closeText + 2
        val n = s.length
        while (j < n && (s[j] == ' ' || s[j] == '\t')) j++
        val url: String
        if (j < n && s[j] == '<') {
            val close = boundedIndexOf(s, '>', j + 1, MAX_URL)
            if (close < 0) return null
            url = s.substring(j + 1, close)
            j = close + 1
        } else {
            val start = j
            var parens = 0
            // Destinations scan ahead from every `[…](`; a shared budget keeps hostile text linear.
            val limit = minOf(n, j + minOf(MAX_URL, closers.budget))
            while (j < limit) {
                val ch = s[j]
                if (ch.isWhitespace()) break
                if (ch == '\\' && j + 1 < limit) {
                    j += 2
                    continue
                }
                if (ch == '(') parens++
                if (ch == ')') {
                    if (parens == 0) break
                    parens--
                }
                j++
            }
            closers.budget -= j - start
            if (j >= n || j == limit && limit < n) return null
            url = s.substring(start, j)
        }
        while (j < n && (s[j] == ' ' || s[j] == '\t')) j++
        if (j < n && (s[j] == '"' || s[j] == '\'')) {
            val close = boundedIndexOf(s, s[j], j + 1, MAX_URL)
            if (close < 0) return null
            j = close + 1
            while (j < n && (s[j] == ' ' || s[j] == '\t')) j++
        }
        if (j >= n || s[j] != ')') return null
        return Link(closeText, url, j + 1)
    }

    /** `<…>` at [i]: an autolink, a known inline HTML tag, a comment, or a literal `<`. Returns the next index. */
    private fun angle(s: String, i: Int, closers: Closers, out: StringBuilder, spans: MutableList<InlineSpan>, depth: Int, autolinks: Boolean): Int {
        val n = s.length
        val close = closers.indexOf(">", i + 1)
        if (s.startsWith("<!--", i)) {
            val end = closers.indexOf("-->", i + 4)
            if (end >= 0) return end + 3
        }
        if (close < 0 || close - i > MAX_URL) {
            out.append('<')
            return i + 1
        }
        val inner = s.substring(i + 1, close)
        if (inner.isNotEmpty() && inner.none { it.isWhitespace() || it == '<' }) {
            val scheme = inner.indexOf(':')
            val isUrl = scheme in 2..32 && inner.substring(0, scheme).all { it.isLetterOrDigit() || it in "+.-" } && inner[0].isLetter()
            val isMail = !isUrl && inner.indexOf('@') in 1 until inner.length - 1 && inner.contains('.')
            if ((isUrl || isMail) && autolinks) {
                val start = out.length
                out.append(inner)
                spans += InlineSpan(start, out.length, InlineSpan.Kind.Link, if (isMail) "mailto:$inner" else inner)
                return close + 1
            }
        }
        val tag = inner.trim().removeSuffix("/").trim().lowercase()
        val name = tag.substringBefore(' ')
        if (name == "br") {
            out.append('\n')
            return close + 1
        }
        val kind = htmlStyles[name]
        if (kind != null && name == tag) {
            val end = closers.indexOf("</$name>", close + 1)
            if (end >= 0) {
                val start = out.length
                parseInto(s.substring(close + 1, end), out, spans, depth + 1, autolinks)
                spans += InlineSpan(start, out.length, kind)
                return end + name.length + 3
            }
        }
        val bare = name.removePrefix("/")
        if (bare in htmlDropped || bare in htmlStyles) return close + 1
        out.append('<')
        return i + 1
    }

    /** End of a bare `http(s)://…` or `www.…` URL at [i], or [i] when there is none (GFM extended autolinks). */
    private fun bareUrl(s: String, i: Int): Int {
        val prefix = when {
            s.regionMatches(i, "https://", 0, 8, ignoreCase = true) -> 8
            s.regionMatches(i, "http://", 0, 7, ignoreCase = true) -> 7
            s.regionMatches(i, "www.", 0, 4, ignoreCase = true) -> 4
            else -> return i
        }
        var j = i + prefix
        val limit = minOf(s.length, i + MAX_URL)
        while (j < limit && !s[j].isWhitespace() && s[j] != '<' && s[j] != '`') j++
        // Trailing punctuation is not part of the URL; a closing paren only when unbalanced.
        var open = 0
        var shut = 0
        for (k in i until j) {
            if (s[k] == '(') open++ else if (s[k] == ')') shut++
        }
        while (j > i + prefix) {
            val ch = s[j - 1]
            if (ch in "?!.,:;*_~'\"") {
                j--
                continue
            }
            if (ch == ')' && shut > open) {
                shut--
                j--
                continue
            }
            break
        }
        val host = s.substring(i + prefix, j).substringBefore('/')
        if (host.isEmpty() || prefix == 4 && !host.contains('.')) return i
        return j
    }

    private fun boundedIndexOf(s: String, c: Char, from: Int, max: Int): Int {
        val end = minOf(s.length, from + max)
        for (k in from until end) if (s[k] == c) return k
        return -1
    }

    /** Link and image text without its markers (alt text). */
    fun plainText(source: String): String = parse(source).text

    /**
     * Closing-marker searches over one string in linear total time. The parser asks for the next marker after each
     * opener; a plain indexOf per opener is quadratic on hostile text (thousands of `*` or `[` that never close).
     * [indexOf] reuses the previous answer while it still lies ahead, and [single] looks closers up in a list built in
     * one pass.
     */
    private class Closers(private val s: String) {
        /** Characters link destinations may still scan in this text (a few times its length). */
        var budget = 4 * s.length + 4 * MAX_URL

        private val lastFrom = HashMap<String, Int>()
        private val lastFound = HashMap<String, Int>()
        private val singles = HashMap<Char, IntArray>()

        /** Letters and digits in s[0, k), built on first use. */
        private var alnumBefore: IntArray? = null

        /** Same answer as `s.indexOf(marker, from)`. */
        fun indexOf(marker: String, from: Int): Int {
            val previousFrom = lastFrom[marker]
            val previous = lastFound[marker]
            // indexOf(marker, previousFrom) == previous: nothing in [previousFrom, previous), so nothing in [from, previous).
            if (previousFrom != null && previous != null && from >= previousFrom && (previous < 0 || previous >= from)) {
                return previous
            }
            val found = s.indexOf(marker, from)
            lastFrom[marker] = from
            lastFound[marker] = found
            return found
        }

        /** The first single [c] at or after [from] that can close emphasis: not doubled, not after whitespace. */
        fun single(c: Char, from: Int): Int {
            val list = singles.getOrPut(c) { singleClosers(c) }
            val at = Arrays.binarySearch(list, from).let { if (it >= 0) it else -it - 1 }
            return if (at < list.size) list[at] else -1
        }

        /**
         * Whether s[from, to) holds a letter or digit. `\(` openers share one closer, so a walk per opener would be
         * quadratic; a prefix count answers each in constant time.
         */
        fun hasLetterOrDigit(from: Int, to: Int): Boolean {
            val counts = alnumBefore ?: IntArray(s.length + 1).also { c ->
                for (k in s.indices) c[k + 1] = c[k] + if (s[k].isLetterOrDigit()) 1 else 0
                alnumBefore = c
            }
            return counts[to] > counts[from]
        }

        private fun singleClosers(c: Char): IntArray {
            val found = ArrayList<Int>()
            var j = 1
            while (j < s.length) {
                if (s[j] == c && j + 1 < s.length && s[j + 1] == c) {
                    j += 2
                    continue
                }
                if (s[j] == c && !s[j - 1].isWhitespace() && s[j - 1] != '\\') found += j
                j++
            }
            return found.toIntArray()
        }
    }
}
