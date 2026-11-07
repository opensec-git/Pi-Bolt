package opensec.pibolt.app.ui.markdown

import androidx.compose.runtime.Immutable

/**
 * Block-level markdown (CommonMark + GFM, as models write it), as a flat list so each block is one transcript row.
 * Nesting is kept as [indent] / [MdBlock.ListItem.depth]: a paragraph, code block, quote or math block inside a list
 * item carries the item's depth + 1, so it lines up with the item's text.
 */
@Immutable
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock

    /** [indent] is the list nesting the paragraph continues (0 outside lists). */
    data class Paragraph(val text: String, val indent: Int = 0) : MdBlock

    /**
     * [closed] is false while a streaming fence has not been closed yet. [info] is the rest of the fence's info string
     * after the language tag (for example `title="src/a.ts"`).
     */
    data class Code(val language: String, val code: String, val closed: Boolean, val info: String = "", val indent: Int = 0) : MdBlock

    /**
     * One list item. [marker] is what is shown: `3.` for ordered items (numbered from the list's start, as CommonMark
     * renumbers `1. 1. 1.`), the source bullet otherwise. [checked] is set for task items.
     */
    data class ListItem(val ordered: Boolean, val marker: String, val depth: Int, val text: String, val checked: Boolean?, val first: Boolean = false) : MdBlock {
        /**
         * A top-level list's first item ([first]: no earlier sibling of the same kind, bullet, number or task). Right
         * after another list (a model writes bullets, then steps, then tasks) it gets a paragraph's gap, not an item's.
         */
        val startsList: Boolean get() = first && depth == 0
    }

    /** A block quote; [text] is its markdown without the `>` markers, rendered as nested blocks. */
    data class Quote(val text: String, val indent: Int = 0) : MdBlock

    data object Rule : MdBlock

    /** [align] holds one entry per column from the separator row (`:--`, `:-:`, `--:`). */
    @Immutable
    data class Table(val header: List<String>, val rows: List<List<String>>, val align: List<TableAlign> = emptyList(), val indent: Int = 0) : MdBlock

    /** Display math (`$$…$$`, `\[…\]`, `\begin{align}…`, ```math). [closed] is false while it streams. */
    data class Math(val tex: String, val closed: Boolean, val indent: Int = 0) : MdBlock

    /** A footnote definition `[^label]: text`. */
    data class Footnote(val label: String, val text: String) : MdBlock
}

enum class TableAlign { None, Start, Center, End }

/** One open list level: where its marker sits, where its content starts, and the number its last item showed. */
@Immutable
internal class ListLevel(val markerIndent: Int, val contentCol: Int, val ordered: Boolean, val delimiter: Char, val number: Int, val task: Boolean = false) {
    /** A line at least this indented belongs to the item (CommonMark's content column, or 2 past the marker: lenient). */
    val threshold: Int get() = minOf(contentCol, markerIndent + 2)
}

/**
 * Parsed text: [starts] holds the character offset where each block begins and [states] the open lists there, so a
 * longer text re-parses only the tail.
 */
@Immutable
class MdDoc internal constructor(
    val text: String,
    val blocks: List<MdBlock>,
    internal val starts: IntArray,
    internal val states: Array<List<ListLevel>> = Array(blocks.size) { emptyList() },
)

object Markdown {
    private val heading = Regex("^ {0,3}(#{1,6})(?:[ \\t]+(.*?))?(?:[ \\t]+#+)?[ \\t]*$")
    private val fence = Regex("^( {0,3})(```+|~~~+)[ \\t]*([^`\\s]*)(.*)$")
    private val rule = Regex("^ {0,3}((\\*[ \\t]*){3,}|(-[ \\t]*){3,}|(_[ \\t]*){3,})$")
    private val setext = Regex("^ {0,3}(={3,}|-{3,})[ \\t]*$")
    private val bullet = Regex("^([ \\t]*)([-*+])([ \\t]+(.*))?$")
    private val ordered = Regex("^([ \\t]*)(\\d{1,9})([.)])([ \\t]+(.*))?$")
    private val task = Regex("^\\[([ xX])\\][ \\t]+(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val quote = Regex("^ {0,3}>[ ]?(.*)$")
    private val tableSeparator = Regex("^[ \\t]*\\|?[ \\t]*:?-+:?[ \\t]*(\\|[ \\t]*:?-+:?[ \\t]*)*\\|?[ \\t]*$")
    private val footnote = Regex("^ {0,3}\\[\\^([^\\]\\s]+)\\]:[ \\t]*(.*)$")
    private val mathEnv = Regex("^\\\\begin\\{(equation|align|gather|multline|eqnarray|displaymath|alignat|flalign)(\\*?)\\}")
    private val htmlContainer = Regex("^ {0,3}</?(details|div|p|center|section|span|table|tbody|thead|tr|td|th|picture|figure|figcaption)(\\s[^>]*)?/?>\\s*$", RegexOption.IGNORE_CASE)
    private val summary = Regex("^ {0,3}<summary>(.*)</summary>\\s*$", RegexOption.IGNORE_CASE)
    private val htmlComment = Regex("^ {0,3}<!--")

    /**
     * A fence line that really opens one: a backtick fence's info string has no backtick (CommonMark), so a one-line
     * "```npm install```" is a code span in a paragraph, not a fence that swallowed the rest of the message.
     */
    private fun opensFence(m: MatchResult): Boolean = m.groupValues[2][0] != '`' || '`' !in m.groupValues[4]

    /** Shown while a large text is parsed off the main thread: its first lines as plain paragraphs. */
    fun placeholder(text: String): MdDoc {
        // Never half an emoji: a cut inside a surrogate pair left a lone high surrogate on screen.
        val head = text.take(4_000).let { if (it.isNotEmpty() && it.last().isHighSurrogate()) it.dropLast(1) else it }.substringBeforeLast('\n')
        return MdDoc(text, listOf(MdBlock.Paragraph(head)), intArrayOf(0))
    }

    /**
     * Parse [text]. When [previous] is a parse of a prefix of [text] (a streaming message that grew), every block but
     * the last is reused and parsing restarts where that last block began, with the lists that were open there.
     */
    fun parse(text: String, previous: MdDoc? = null): MdDoc {
        if (previous != null && previous.text == text) return previous
        if (previous != null && previous.blocks.isNotEmpty() && text.startsWith(previous.text)) {
            val keep = previous.blocks.size - 1
            val parser = BlockParser(text, previous.starts[keep], previous.states[keep])
            parser.run()
            val blocks = ArrayList<MdBlock>(keep + parser.blocks.size)
            blocks.addAll(previous.blocks.subList(0, keep))
            blocks.addAll(parser.blocks)
            val starts = IntArray(blocks.size)
            previous.starts.copyInto(starts, 0, 0, keep)
            parser.starts.forEachIndexed { i, s -> starts[keep + i] = s }
            val states = arrayOfNulls<List<ListLevel>>(blocks.size)
            previous.states.copyInto(states, 0, 0, keep)
            parser.states.forEachIndexed { i, s -> states[keep + i] = s }
            @Suppress("UNCHECKED_CAST")
            return MdDoc(text, blocks, starts, states as Array<List<ListLevel>>)
        }
        val parser = BlockParser(text, 0, emptyList())
        parser.run()
        return MdDoc(text, parser.blocks, parser.starts.toIntArray(), parser.states.toTypedArray())
    }

    /** Columns of leading whitespace (a tab advances to the next multiple of 4). */
    internal fun indentOf(line: String): Int {
        var col = 0
        for (c in line) {
            when (c) {
                ' ' -> col++
                '\t' -> col += 4 - col % 4
                else -> return col
            }
        }
        return col
    }

    /** [line] without its first [cols] columns of indentation (fewer when it is less indented). */
    internal fun stripIndent(line: String, cols: Int): String {
        if (cols <= 0) return line
        var col = 0
        var i = 0
        while (i < line.length && col < cols) {
            when (line[i]) {
                ' ' -> col++
                '\t' -> {
                    val next = col + 4 - col % 4
                    if (next > cols) return " ".repeat(next - cols) + line.substring(i + 1)
                    col = next
                }
                else -> return line.substring(i)
            }
            i++
        }
        return line.substring(i)
    }

    private class Marker(val indent: Int, val ordered: Boolean, val bullet: String, val start: Int, val delimiter: Char, val contentCol: Int, val rest: String)

    private fun marker(line: String): Marker? {
        bullet.matchEntire(line)?.let { m ->
            val indent = indentOf(m.groupValues[1])
            val rest = m.groupValues[4]
            // `- ` followed by a rule-like run is a rule, not an item.
            if (rule.matches(line)) return null
            val spaces = m.groupValues[3].length - rest.length
            return Marker(indent, false, m.groupValues[2], 0, m.groupValues[2][0], indent + 1 + if (spaces in 1..4 && rest.isNotEmpty()) spaces else 1, rest)
        }
        ordered.matchEntire(line)?.let { m ->
            val indent = indentOf(m.groupValues[1])
            val digits = m.groupValues[2]
            val rest = m.groupValues[5]
            val spaces = m.groupValues[4].length - rest.length
            return Marker(indent, true, "", digits.toInt(), m.groupValues[3][0], indent + digits.length + 1 + if (spaces in 1..4 && rest.isNotEmpty()) spaces else 1, rest)
        }
        return null
    }

    internal fun isTableSeparator(line: String): Boolean = line.contains('|') && tableSeparator.matches(line)

    /** Cells of a table row: split on `|` outside code spans and escapes; `\|` becomes `|`. */
    internal fun cells(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.substring(0, s.length - 1)
        val out = ArrayList<String>()
        val cell = StringBuilder()
        var i = 0
        var ticks = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] == '|' -> {
                    cell.append('|')
                    i += 2
                    continue
                }
                c == '`' -> {
                    var run = 0
                    while (i + run < s.length && s[i + run] == '`') run++
                    ticks = if (ticks == 0) run else if (ticks == run) 0 else ticks
                    cell.append(s, i, i + run)
                    i += run
                    continue
                }
                c == '|' && ticks == 0 -> {
                    out += cell.toString().trim()
                    cell.setLength(0)
                }
                else -> cell.append(c)
            }
            i++
        }
        out += cell.toString().trim()
        return out
    }

    private fun alignOf(marker: String): TableAlign = when {
        marker.startsWith(":") && marker.endsWith(":") && marker.length > 1 -> TableAlign.Center
        marker.endsWith(":") -> TableAlign.End
        marker.startsWith(":") -> TableAlign.Start
        else -> TableAlign.None
    }

    /** One parse run from [offset] with [initial] lists open. */
    private class BlockParser(val text: String, offset: Int, initial: List<ListLevel>) {
        val blocks = ArrayList<MdBlock>()
        val starts = ArrayList<Int>()
        val states = ArrayList<List<ListLevel>>()
        private val lines = ArrayList<String>()
        private val lineStarts = ArrayList<Int>()
        private var stack: List<ListLevel> = initial

        init {
            var pos = offset
            while (pos <= text.length) {
                val nl = text.indexOf('\n', pos)
                val end = if (nl < 0) text.length else nl
                lines += text.substring(pos, if (end > pos && text[end - 1] == '\r') end - 1 else end)
                lineStarts += pos
                if (nl < 0) break
                pos = nl + 1
            }
        }

        private fun add(block: MdBlock, line: Int, state: List<ListLevel>) {
            blocks += block
            starts += lineStarts[line]
            states += state
        }

        /** The column the current list content starts at: lines are read without it. */
        private val strip: Int get() = stack.lastOrNull()?.contentCol ?: 0

        fun run() {
            var i = 0
            while (i < lines.size) {
                val raw = lines[i]
                if (raw.isBlank()) {
                    i++
                    continue
                }
                val state = stack
                val indent = indentOf(raw)
                val item = marker(raw)
                if (item != null) {
                    i = listItem(i, item, state)
                    continue
                }
                // Not an item: the line stays inside the lists it is indented into.
                while (stack.isNotEmpty() && indent < stack.last().threshold) stack = stack.dropLast(1)
                val line = stripIndent(raw, minOf(indent, strip))
                i = block(i, line, state)
            }
        }

        /** A list item at line [i]; returns the next line to read. */
        private fun listItem(i: Int, m: Marker, state: List<ListLevel>): Int {
            var sibling: ListLevel? = null
            while (stack.isNotEmpty() && m.indent < stack.last().threshold) {
                sibling = stack.last()
                stack = stack.dropLast(1)
            }
            val depth = stack.size
            val continues = sibling != null && sibling.ordered == m.ordered && sibling.delimiter == m.delimiter
            val number = if (m.ordered) (if (continues) sibling!!.number + 1 else m.start) else 0
            val body = StringBuilder(m.rest.trim())
            var j = i + 1
            // Continuation lines (indented or lazy) belong to the item's first paragraph.
            while (j < lines.size) {
                val l = lines[j]
                if (l.isBlank() || interrupts(l.trimStart(), paragraph = true) || marker(l) != null) break
                if (startsTable(j)) break
                body.append('\n').append(l.trim())
                j++
            }
            var text = body.toString()
            var checked: Boolean? = null
            task.matchEntire(text)?.let {
                checked = it.groupValues[1] != " "
                text = it.groupValues[2]
            }
            if (text == "[ ]" || text == "[x]" || text == "[X]") {
                checked = text[1] != ' '
                text = ""
            }
            val shown = if (m.ordered) "$number${m.delimiter}" else m.bullet
            val task = checked != null
            // The first of its list: no earlier sibling, or one of another kind (a task list after bullets).
            val first = !(continues && sibling!!.task == task)
            add(MdBlock.ListItem(m.ordered, shown, depth, text, checked, first), i, state)
            stack = stack + ListLevel(m.indent, m.contentCol, m.ordered, m.delimiter, number, task)
            return j
        }

        /** Line [j] is a table header: the next line is a separator with as many cells. */
        private fun startsTable(j: Int): Boolean {
            val l = lines[j]
            if (!l.contains('|') || j + 1 >= lines.size) return false
            val sep = lines[j + 1]
            return isTableSeparator(sep) && cells(sep).size == cells(l).size
        }

        /** `\[` alone on its line, or `\[ … \]` on one line (a line merely starting with an escaped bracket is prose). */
        private fun isMathBracket(t: String): Boolean {
            if (!t.startsWith("\\[")) return false
            val s = t.trimEnd()
            return s == "\\[" || s.endsWith("\\]")
        }

        /** True when [t] (trimmed at the start) begins a block that ends a paragraph. */
        private fun interrupts(t: String, paragraph: Boolean): Boolean {
            if ((t.startsWith("```") || t.startsWith("~~~")) && fence.matchEntire(t)?.let(::opensFence) == true) return true
            if (t.startsWith("#") && heading.matches(t)) return true
            if (t.startsWith(">")) return true
            if (t.startsWith("$$") || isMathBracket(t) || mathEnv.containsMatchIn(t)) return true
            if (rule.matches(t)) return true
            if (paragraph) {
                // Lists interrupt a paragraph only with content, and ordered ones only from 1 (CommonMark).
                val m = marker(t)
                if (m != null && m.rest.isNotBlank() && (!m.ordered || m.start == 1)) return true
            }
            if (t.startsWith("<") && (htmlContainer.matches(t) || htmlComment.containsMatchIn(t))) return true
            return false
        }

        /** Reads the line at [i] relative to the list content column (the rest of the block strips the same). */
        private fun inner(j: Int, cols: Int): String = stripIndent(lines[j], cols)

        /** A non-list block starting at line [i] ([line] already stripped of list indentation). */
        private fun block(i: Int, line: String, state: List<ListLevel>): Int {
            val depth = stack.size
            val cols = minOf(indentOf(lines[i]), strip)
            val t = line.trimStart()

            fence.matchEntire(line)?.takeIf(::opensFence)?.let { m ->
                val fenceIndent = m.groupValues[1].length
                val mark = m.groupValues[2]
                val body = StringBuilder()
                var j = i + 1
                var closed = false
                var first = true
                while (j < lines.size) {
                    val l = inner(j, cols)
                    val lt = l.trim()
                    if (lt.length >= mark.length && lt.startsWith(mark) && lt.all { it == mark[0] } && indentOf(l) < 4) {
                        closed = true
                        j++
                        break
                    }
                    if (!first) body.append('\n')
                    body.append(stripIndent(l, fenceIndent))
                    first = false
                    j++
                }
                val lang = m.groupValues[3]
                if (lang.equals("math", ignoreCase = true)) {
                    add(MdBlock.Math(body.toString().trim(), closed, depth), i, state)
                } else {
                    add(MdBlock.Code(lang, body.toString(), closed, m.groupValues[4].trim(), depth), i, state)
                }
                return j
            }

            // Display math: $$…$$, \[…\], \begin{env}…\end{env}.
            if (t.startsWith("$$") || isMathBracket(t)) {
                val close = if (t.startsWith("$$")) "$$" else "\\]"
                val first = t.substring(2)
                val sameLine = first.indexOf(close)
                if (sameLine >= 0) {
                    val after = first.substring(sameLine + 2).trim()
                    if (after.isEmpty()) {
                        add(MdBlock.Math(first.substring(0, sameLine).trim(), true, depth), i, state)
                        return i + 1
                    }
                } else {
                    val body = StringBuilder(first)
                    var j = i + 1
                    var closed = false
                    while (j < lines.size) {
                        val l = inner(j, cols)
                        val at = l.indexOf(close)
                        if (at >= 0) {
                            body.append('\n').append(l, 0, at)
                            closed = true
                            j++
                            break
                        }
                        // TeX display math has no blank lines: one ends an unclosed block.
                        if (l.isBlank()) break
                        body.append('\n').append(l)
                        j++
                    }
                    add(MdBlock.Math(body.toString().trim(), closed, depth), i, state)
                    return j
                }
            }
            mathEnv.find(t)?.let { m ->
                val end = "\\end{${m.groupValues[1]}${m.groupValues[2]}}"
                val body = StringBuilder(t)
                var j = i + 1
                var closed = t.contains(end)
                while (!closed && j < lines.size) {
                    val l = inner(j, cols)
                    body.append('\n').append(l)
                    j++
                    if (l.contains(end)) closed = true
                }
                add(MdBlock.Math(body.toString().trim(), closed, depth), i, state)
                return j
            }

            heading.matchEntire(line)?.let { m ->
                add(MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim()), i, state)
                return i + 1
            }
            if (rule.matches(line)) {
                add(MdBlock.Rule, i, state)
                return i + 1
            }
            if (quote.matches(line)) {
                val body = StringBuilder()
                var j = i
                while (j < lines.size) {
                    val m = quote.matchEntire(inner(j, cols)) ?: break
                    if (j > i) body.append('\n')
                    body.append(m.groupValues[1])
                    j++
                }
                add(MdBlock.Quote(body.toString(), depth), i, state)
                return j
            }
            footnote.matchEntire(line)?.let { m ->
                val body = StringBuilder(m.groupValues[2].trim())
                var j = i + 1
                while (j < lines.size) {
                    val l = lines[j]
                    if (l.isBlank() || footnote.matches(l) || interrupts(l.trimStart(), paragraph = true)) break
                    body.append('\n').append(l.trim())
                    j++
                }
                add(MdBlock.Footnote(m.groupValues[1], body.toString()), i, state)
                return j
            }
            if (line.contains('|') && i + 1 < lines.size) {
                val sep = inner(i + 1, cols)
                val header = cells(line)
                if (isTableSeparator(sep)) {
                    val sepCells = cells(sep)
                    if (sepCells.size == header.size) {
                        val align = sepCells.map { alignOf(it) }
                        var j = i + 2
                        val rows = ArrayList<List<String>>()
                        while (j < lines.size) {
                            val l = inner(j, cols)
                            if (l.isBlank() || !l.contains('|') || interrupts(l.trimStart(), paragraph = false)) break
                            rows += cells(l)
                            j++
                        }
                        add(MdBlock.Table(header, rows, align, depth), i, state)
                        return j
                    }
                }
            }
            // HTML that only wraps content (<details>, <div align="center">…) is dropped; comments are hidden.
            if (t.startsWith("<")) {
                summary.matchEntire(t)?.let { m ->
                    add(MdBlock.Paragraph("**${m.groupValues[1].trim()}**", depth), i, state)
                    return i + 1
                }
                if (htmlContainer.matches(t)) return i + 1
                if (htmlComment.containsMatchIn(t) && t.startsWith("<!--")) {
                    var j = i
                    while (j < lines.size && !lines[j].contains("-->")) j++
                    return minOf(j + 1, lines.size)
                }
            }
            // Indented code (4+ spaces) outside lists.
            if (depth == 0 && indentOf(line) >= 4) {
                val body = StringBuilder(stripIndent(line, 4))
                var j = i + 1
                var pendingBlank = 0
                while (j < lines.size) {
                    val l = lines[j]
                    if (l.isBlank()) {
                        pendingBlank++
                        j++
                        continue
                    }
                    if (indentOf(l) < 4) break
                    repeat(pendingBlank) { body.append('\n') }
                    pendingBlank = 0
                    body.append('\n').append(stripIndent(l, 4))
                    j++
                }
                // Trailing blank lines are not part of the block.
                val end = j - pendingBlank
                add(MdBlock.Code("", body.toString(), closed = end < lines.size || text.endsWith("\n"), indent = 0), i, state)
                return end
            }

            // Paragraph, possibly a setext heading.
            val body = StringBuilder(line.trim())
            var j = i + 1
            while (j < lines.size) {
                val l = lines[j]
                if (l.isBlank()) break
                val lt = l.trimStart()
                if (setext.matches(inner(j, cols))) {
                    val level = if (lt.startsWith("=")) 1 else 2
                    add(MdBlock.Heading(level, body.toString()), i, state)
                    return j + 1
                }
                if (interrupts(lt, paragraph = true) || footnote.matches(l)) break
                // A list item that is less indented than this paragraph's list ends it.
                if (marker(l) != null && depth > 0) break
                if (startsTable(j)) break
                body.append('\n').append(l.trim())
                j++
            }
            add(MdBlock.Paragraph(body.toString(), depth), i, state)
            return j
        }
    }
}
