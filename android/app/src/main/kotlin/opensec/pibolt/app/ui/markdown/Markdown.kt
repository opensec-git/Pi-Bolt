package opensec.pibolt.app.ui.markdown

import java.util.Arrays

/** Block-level markdown, enough for model output: headings, paragraphs, lists, quotes, code fences, tables, rules. */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock

    data class Paragraph(val text: String) : MdBlock

    /**
     * [closed] is false while a streaming fence has not been closed yet. [info] is the rest of the fence's info string
     * after the language tag (for example `title="src/a.ts"`).
     */
    data class Code(val language: String, val code: String, val closed: Boolean, val info: String = "") : MdBlock

    data class ListItem(val ordered: Boolean, val marker: String, val depth: Int, val text: String, val checked: Boolean?) : MdBlock

    data class Quote(val text: String) : MdBlock

    data object Rule : MdBlock

    /** [align] holds one entry per column from the separator row (`:--`, `:-:`, `--:`). */
    data class Table(val header: List<String>, val rows: List<List<String>>, val align: List<TableAlign> = emptyList()) : MdBlock
}

enum class TableAlign { None, Start, Center, End }

/** Parsed text: [starts] holds the character offset where each block begins, so a longer text re-parses only the tail. */
class MdDoc internal constructor(val text: String, val blocks: List<MdBlock>, internal val starts: IntArray)

object Markdown {
    private val heading = Regex("^ {0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val fence = Regex("^ {0,3}(```+|~~~+)\\s*([^`\\s]*)(.*)$")
    private val rule = Regex("^ {0,3}((\\*\\s*){3,}|(-\\s*){3,}|(_\\s*){3,})$")
    private val listItem = Regex("^(\\s*)([-*+]|\\d{1,9}[.)])\\s+(.*)$")
    private val task = Regex("^\\[([ xX])]\\s+(.*)$")
    private val quote = Regex("^ {0,3}>\\s?(.*)$")
    private val tableSeparator = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    /**
     * Parse [text]. When [previous] is a parse of a prefix of [text] (a streaming message that grew), every block but
     * the last is reused and parsing restarts where that last block began.
     */
    fun parse(text: String, previous: MdDoc? = null): MdDoc {
        if (previous != null && previous.text == text) return previous
        if (previous != null && previous.blocks.isNotEmpty() && text.startsWith(previous.text)) {
            val keep = previous.blocks.size - 1
            val from = previous.starts[keep]
            val (tail, tailStarts) = parseFrom(text, from)
            val blocks = ArrayList<MdBlock>(keep + tail.size)
            blocks.addAll(previous.blocks.subList(0, keep))
            blocks.addAll(tail)
            val starts = IntArray(blocks.size)
            previous.starts.copyInto(starts, 0, 0, keep)
            tailStarts.forEachIndexed { i, s -> starts[keep + i] = s }
            return MdDoc(text, blocks, starts)
        }
        val (blocks, starts) = parseFrom(text, 0)
        return MdDoc(text, blocks, starts.toIntArray())
    }

    private fun parseFrom(text: String, offset: Int): Pair<List<MdBlock>, List<Int>> {
        // Lines with their start offsets.
        val lines = ArrayList<String>()
        val lineStarts = ArrayList<Int>()
        var pos = offset
        while (pos <= text.length) {
            val nl = text.indexOf('\n', pos)
            val end = if (nl < 0) text.length else nl
            lines += text.substring(pos, end).removeSuffix("\r")
            lineStarts += pos
            if (nl < 0) break
            pos = nl + 1
        }
        val blocks = ArrayList<MdBlock>()
        val starts = ArrayList<Int>()
        var i = 0
        fun add(block: MdBlock, line: Int) {
            blocks += block
            starts += lineStarts[line]
        }
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                i++
                continue
            }
            val start = i
            fence.matchEntire(line)?.let { m ->
                val marker = m.groupValues[1]
                val body = StringBuilder()
                i++
                var closed = false
                while (i < lines.size) {
                    val l = lines[i]
                    if (l.trimStart().startsWith(marker) && l.trim().all { it == marker[0] }) {
                        closed = true
                        i++
                        break
                    }
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(l)
                    i++
                }
                add(MdBlock.Code(m.groupValues[2], body.toString(), closed, m.groupValues[3].trim()), start)
                return@let
            } ?: run {
                heading.matchEntire(line)?.let { m ->
                    add(MdBlock.Heading(m.groupValues[1].length, m.groupValues[2]), start)
                    i++
                    return@run
                }
                if (rule.matches(line)) {
                    add(MdBlock.Rule, start)
                    i++
                    return@run
                }
                if (quote.matches(line)) {
                    val body = StringBuilder()
                    while (i < lines.size) {
                        val m = quote.matchEntire(lines[i]) ?: break
                        if (body.isNotEmpty()) body.append('\n')
                        body.append(m.groupValues[1])
                        i++
                    }
                    add(MdBlock.Quote(body.toString()), start)
                    return@run
                }
                if (line.trimStart().startsWith("|") && i + 1 < lines.size && tableSeparator.matches(lines[i + 1])) {
                    val header = cells(line)
                    val align = cells(lines[i + 1]).map { alignOf(it) }
                    i += 2
                    val rows = ArrayList<List<String>>()
                    while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                        rows += cells(lines[i])
                        i++
                    }
                    add(MdBlock.Table(header, rows, align), start)
                    return@run
                }
                listItem.matchEntire(line)?.let { m ->
                    val indent = m.groupValues[1].replace("\t", "    ").length
                    val marker = m.groupValues[2]
                    val body = StringBuilder(m.groupValues[3])
                    i++
                    // Lazy continuation lines belong to the item.
                    while (i < lines.size) {
                        val l = lines[i]
                        if (l.isBlank() || listItem.matches(l) || fence.matches(l) || heading.matches(l) || quote.matches(l)) break
                        body.append('\n').append(l.trim())
                        i++
                    }
                    val taskMatch = task.matchEntire(body.toString())
                    add(
                        MdBlock.ListItem(
                            ordered = marker[0].isDigit(),
                            marker = marker,
                            depth = indent / 2,
                            text = taskMatch?.groupValues?.get(2) ?: body.toString(),
                            checked = taskMatch?.let { it.groupValues[1] != " " },
                        ),
                        start,
                    )
                    return@run
                }
                val body = StringBuilder(line.trim())
                i++
                while (i < lines.size) {
                    val l = lines[i]
                    if (l.isBlank() || fence.matches(l) || heading.matches(l) || quote.matches(l) || listItem.matches(l) || rule.matches(l)) break
                    body.append('\n').append(l.trim())
                    i++
                }
                add(MdBlock.Paragraph(body.toString()), start)
            }
        }
        return blocks to starts
    }

    private fun alignOf(marker: String): TableAlign = when {
        marker.startsWith(":") && marker.endsWith(":") && marker.length > 1 -> TableAlign.Center
        marker.endsWith(":") -> TableAlign.End
        marker.startsWith(":") -> TableAlign.Start
        else -> TableAlign.None
    }

    private fun cells(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
}

/** Inline markdown: bold, italic, strikethrough, inline code, links. */
data class InlineSpan(val start: Int, val end: Int, val kind: Kind, val url: String? = null) {
    enum class Kind { Bold, Italic, Strike, Code, Link }
}

data class InlineText(val text: String, val spans: List<InlineSpan>)

object Inline {
    /** Strip inline markers from [source] and report styled ranges in the stripped text. */
    fun parse(source: String): InlineText {
        val out = StringBuilder(source.length)
        val spans = ArrayList<InlineSpan>()
        parseInto(source, out, spans)
        return InlineText(out.toString(), spans)
    }

    private fun parseInto(s: String, out: StringBuilder, spans: MutableList<InlineSpan>) {
        val closers = Closers(s)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] in "\\`*_~[]()#+-.!|>" -> {
                    out.append(s[i + 1])
                    i += 2
                }
                c == '`' -> {
                    var ticks = 1
                    while (i + ticks < s.length && s[i + ticks] == '`') ticks++
                    val marker = "`".repeat(ticks)
                    val close = closers.indexOf(marker, i + ticks)
                    if (close < 0) {
                        out.append(marker)
                        i += ticks
                    } else {
                        val code = s.substring(i + ticks, close).let { if (it.length > 2 && it.startsWith(" ") && it.endsWith(" ")) it.substring(1, it.length - 1) else it }
                        val start = out.length
                        out.append(code)
                        spans += InlineSpan(start, out.length, InlineSpan.Kind.Code)
                        i = close + ticks
                    }
                }
                (c == '*' || c == '_') && i + 1 < s.length && s[i + 1] == c -> {
                    val marker = "$c$c"
                    val close = closers.indexOf(marker, i + 2)
                    if (close > i + 2 && !s[i + 2].isWhitespace()) {
                        val start = out.length
                        parseInto(s.substring(i + 2, close), out, spans)
                        spans += InlineSpan(start, out.length, InlineSpan.Kind.Bold)
                        i = close + 2
                    } else {
                        out.append(marker)
                        i += 2
                    }
                }
                (c == '*' || c == '_') -> {
                    val close = closers.single(c, i + 1)
                    val wordInside = c == '_' && i > 0 && s[i - 1].isLetterOrDigit()
                    if (close > i + 1 && !s[i + 1].isWhitespace() && !wordInside) {
                        val start = out.length
                        parseInto(s.substring(i + 1, close), out, spans)
                        spans += InlineSpan(start, out.length, InlineSpan.Kind.Italic)
                        i = close + 1
                    } else {
                        out.append(c)
                        i++
                    }
                }
                c == '~' && i + 1 < s.length && s[i + 1] == '~' -> {
                    val close = closers.indexOf("~~", i + 2)
                    if (close > i + 2) {
                        val start = out.length
                        parseInto(s.substring(i + 2, close), out, spans)
                        spans += InlineSpan(start, out.length, InlineSpan.Kind.Strike)
                        i = close + 2
                    } else {
                        out.append("~~")
                        i += 2
                    }
                }
                c == '[' -> {
                    val closeText = closers.indexOf("]", i + 1)
                    if (closeText > i && closeText + 1 < s.length && s[closeText + 1] == '(') {
                        val closeUrl = closers.indexOf(")", closeText + 2)
                        if (closeUrl > 0) {
                            val url = s.substring(closeText + 2, closeUrl).trim().substringBefore(' ')
                            val start = out.length
                            parseInto(s.substring(i + 1, closeText), out, spans)
                            spans += InlineSpan(start, out.length, InlineSpan.Kind.Link, url)
                            i = closeUrl + 1
                            continue
                        }
                    }
                    out.append(c)
                    i++
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
    }

    /**
     * Closing-marker searches over one string in linear total time. The parser asks for the next marker after each
     * opener; a plain indexOf per opener is quadratic on hostile text (thousands of `*` or `[` that never close).
     * [indexOf] reuses the previous answer while it still lies ahead, and [single] looks closers up in a list built in
     * one pass.
     */
    private class Closers(private val s: String) {
        private val lastFrom = HashMap<String, Int>()
        private val lastFound = HashMap<String, Int>()
        private val singles = HashMap<Char, IntArray>()

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

        private fun singleClosers(c: Char): IntArray {
            val found = ArrayList<Int>()
            var j = 1
            while (j < s.length) {
                if (s[j] == c && j + 1 < s.length && s[j + 1] == c) {
                    j += 2
                    continue
                }
                if (s[j] == c && !s[j - 1].isWhitespace()) found += j
                j++
            }
            return found.toIntArray()
        }
    }
}
