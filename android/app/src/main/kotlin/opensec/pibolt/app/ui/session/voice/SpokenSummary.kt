package opensec.pibolt.app.ui.session.voice

import opensec.pibolt.app.ui.session.ErrorNotices
import opensec.pibolt.app.ui.session.ErrorText
import opensec.pibolt.app.ui.session.ToolKind
import opensec.pibolt.app.ui.session.toolLabel
import opensec.pibolt.protocol.session.AssistantTextItem
import opensec.pibolt.protocol.session.ChatItem
import opensec.pibolt.protocol.session.NoticeItem
import opensec.pibolt.protocol.session.NoticeKind
import opensec.pibolt.protocol.session.ToolItem
import opensec.pibolt.protocol.session.ToolStatus
import opensec.pibolt.protocol.session.UserItem

/**
 * What voice mode says when a turn ends (pure, unit-tested). No model call: the reply's first paragraph as plain prose
 * ([plain]: no code, tables, links, URLs or markdown marks), trimmed to [MAX_SENTENCES] sentences and about [MAX_CHARS]
 * characters, then a fact the turn's tool results give cheaply ("I changed 3 files."), or what went wrong.
 */
object SpokenSummary {
    const val MAX_SENTENCES = 3
    const val MAX_CHARS = 320

    /** Said when the reply has nothing speakable (only code or a table). */
    const val ON_SCREEN = "The details are on screen."

    private val fence = Regex("""(?ms)^[ \t]*(```|~~~).*?(^[ \t]*\1[^\n]*$|\z)""")
    private val htmlTag = Regex("""</?[A-Za-z][^>\n]*>""")
    private val image = Regex("""!\[([^\]]*)]\([^)]*\)""")
    private val link = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val refLink = Regex("""\[([^\]]+)]\[[^\]]*]""")
    private val autoLink = Regex("""<(https?://|mailto:)[^>]*>""")
    private val url = Regex("""\b(https?://|www\.)\S+""")
    private val inlineCode = Regex("""`+([^`]*)`+""")
    private val bold = Regex("""(\*\*|__)(\S(?:.*?\S)?)\1""")
    private val italic = Regex("""(?<![\w*])([*_])(\S(?:.*?\S)?)\1(?![\w*])""")
    private val strike = Regex("""~~(.+?)~~""")
    private val heading = Regex("""^#{1,6}\s+""")
    private val quote = Regex("""^(>\s?)+""")
    private val bullet = Regex("""^([-*+]|\d{1,3}[.)])\s+(\[[ xX]]\s+)?""")
    private val rule = Regex("""^([-*_]\s*){3,}$""")
    private val tableRow = Regex("""^\|.*\|?$|^[^|\n]*\|[^|\n]*\|.*$""")
    private val tableDivider = Regex("""^\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?$""")
    private val sentenceEnd = Regex("""(?<=[.!?…])["')\]]?\s+(?=["'(\[]?[A-Z0-9])""")
    private val spaces = Regex("""[ \t]+""")

    /**
     * [markdown] as plain prose, paragraphs separated by a blank line. Code blocks, tables, rules, images, HTML and bare
     * URLs go; a link keeps its text, inline code its content; heading, quote and list marks go, and a run of list items
     * becomes one paragraph of sentences.
     */
    fun plain(markdown: String): String = blocks(markdown).joinToString("\n\n") { it.text }

    /** A paragraph of [plain]; [heading] when it was a markdown heading. */
    private class Block(val text: String, val heading: Boolean)

    private fun blocks(markdown: String): List<Block> {
        var text = markdown.replace("\r\n", "\n").replace('\r', '\n')
        text = fence.replace(text, "\n\n")
        text = htmlTag.replace(text, "")
        text = image.replace(text, "")
        text = link.replace(text) { it.groupValues[1] }
        text = refLink.replace(text) { it.groupValues[1] }
        text = autoLink.replace(text, "")
        text = url.replace(text, "")
        val paragraphs = ArrayList<Block>()
        val current = ArrayList<String>()
        var inList = false
        fun flush() {
            if (current.isNotEmpty()) paragraphs += Block(current.joinToString(" "), false)
            current.clear()
        }
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || rule.matches(line) || tableDivider.matches(line) || (tableRow.matches(line) && line.count { it == '|' } >= 2)) {
                flush()
                inList = false
                continue
            }
            val isHeading = heading.containsMatchIn(line)
            var t = quote.replace(line, "")
            t = heading.replace(t, "")
            val item = bullet.containsMatchIn(t)
            t = bullet.replace(t, "")
            t = inline(t)
            if (t.isEmpty()) continue
            if (isHeading) {
                // A heading is a paragraph of its own (often a title the reply does not need said).
                flush()
                paragraphs += Block(t, true)
                inList = false
                continue
            }
            if (item) {
                if (!inList) flush()
                inList = true
                current += sentence(t)
                continue
            }
            if (inList) {
                flush()
                inList = false
            }
            current += t
        }
        flush()
        return paragraphs.map { Block(spaces.replace(it.text, " ").trim(), it.heading) }.filter { b -> b.text.any(Char::isLetterOrDigit) }
    }

    private fun inline(text: String): String {
        var t = inlineCode.replace(text) { it.groupValues[1] }
        t = bold.replace(t) { it.groupValues[2] }
        t = strike.replace(t) { it.groupValues[1] }
        t = italic.replace(t) { it.groupValues[2] }
        return spaces.replace(t, " ").trim()
    }

    /** A list item as a sentence: it ends with a stop. */
    private fun sentence(text: String): String = if (text.last() in ".!?:;…") text else "$text."

    /**
     * The spoken part of [reply]: its first paragraph (headings before it are skipped; a short one, or one that ends in
     * a colon, takes the next one with it), then at most [maxSentences] sentences within [maxChars]; a first sentence longer
     * than that is cut at a word and ends with an ellipsis. Empty when nothing is speakable.
     */
    fun excerpt(reply: String, maxSentences: Int = MAX_SENTENCES, maxChars: Int = MAX_CHARS): String {
        val all = blocks(reply)
        val body = all.dropWhile { it.heading }.ifEmpty { all }.map { it.text }
        if (body.isEmpty()) return ""
        var first = body[0]
        if (body.size > 1 && (first.endsWith(":") || first.length < SHORT)) first = (if (first.last() in ".!?:…") first else "$first.") + " " + body[1]
        val sentences = first.split(sentenceEnd).map { it.trim() }.filter { it.isNotEmpty() }
        val out = StringBuilder()
        var count = 0
        for (s in sentences) {
            if (count == maxSentences) break
            val add = if (out.isEmpty()) s else " $s"
            if (out.length + add.length > maxChars) break
            out.append(add)
            count++
        }
        if (out.isEmpty()) return cut(sentences.first(), maxChars)
        return out.toString()
    }

    /** A first paragraph shorter than this ("Done.", "Fixed it!") is said with the next one. */
    private const val SHORT = 40

    private fun cut(text: String, max: Int): String {
        if (text.length <= max) return text
        val head = text.substring(0, max)
        val space = head.lastIndexOf(' ').takeIf { it > max / 2 } ?: max
        return head.substring(0, space).trimEnd(',', ';', ':', ' ', '-') + "…"
    }

    /** Files the turn's finished `write` and `edit` calls changed (distinct paths). */
    fun changedFiles(tools: List<ToolItem>): Int = tools
        .filter { it.status == ToolStatus.Done }
        .mapNotNull { t -> toolLabel(t).takeIf { it.kind == ToolKind.Write || it.kind == ToolKind.Edit }?.detail?.trim()?.takeIf { it.isNotEmpty() } }
        .toSet().size

    /** "I changed 3 files." */
    fun changedLine(files: Int): String? = when (files) {
        0 -> null
        1 -> "I changed one file."
        else -> "I changed $files files."
    }

    /**
     * What to say for the turn that ends [items] (from the last user message on): the final reply's excerpt (the text
     * after the turn's last tool call; else its last text), then the files changed; or "Stopped." for an aborted turn and
     * the error's plain title for a failed one. Empty when the turn has nothing at all.
     */
    fun forTurn(items: List<ChatItem>): String {
        val turn = items.subList(items.indexOfLast { it is UserItem } + 1, items.size)
        if (turn.isEmpty()) return ""
        val lastTool = turn.indexOfLast { it is ToolItem }
        val finalTexts = turn.drop(lastTool + 1).filterIsInstance<AssistantTextItem>().filter { it.text.isNotBlank() }
        val texts = finalTexts.ifEmpty { turn.filterIsInstance<AssistantTextItem>().filter { it.text.isNotBlank() }.takeLast(1) }
        val reply = texts.joinToString("\n\n") { it.text }
        val excerpt = excerpt(reply)
        val changed = changedLine(changedFiles(turn.filterIsInstance<ToolItem>()))
        val notices = turn.filterIsInstance<NoticeItem>()
        val aborted = notices.any { it.kind == NoticeKind.Aborted }
        val error = notices.lastOrNull { it.kind == NoticeKind.Error }
        val parts = buildList {
            if (aborted) add("Stopped.")
            if (excerpt.isNotEmpty()) add(stop(excerpt)) else if (reply.isNotBlank()) add(ON_SCREEN)
            if (changed != null) add(changed)
            if (error != null && excerpt.isEmpty()) add("That didn't work: " + stop(errorTitle(error)))
        }
        return parts.joinToString(" ").ifEmpty { if (turn.any { it is ToolItem }) "Done." else "" }
    }

    private fun errorTitle(notice: NoticeItem): String =
        if (ErrorNotices.isProviderError(notice)) ErrorText.title(ErrorText.parse(notice.text), null, null).replaceFirstChar { it.lowercase() } else notice.title.replaceFirstChar { it.lowercase() }

    private fun stop(text: String): String = if (text.last() in ".!?…") text else "$text."
}
