package opensec.pibolt.app.ui.session.features

import opensec.pibolt.protocol.session.AssistantTextItem
import opensec.pibolt.protocol.session.ChatItem
import opensec.pibolt.protocol.session.UserItem

/** Text helpers for the message actions: plain text from Markdown, and the prompt a reply answered. */
object MessageText {
    private val fence = Regex("^\\s*(```|~~~).*$")
    private val heading = Regex("^\\s{0,3}#{1,6}\\s+")
    private val quote = Regex("^\\s{0,3}>\\s?")
    private val bullet = Regex("^(\\s*)[*+-]\\s+")
    private val rule = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val image = Regex("!\\[([^\\]]*)]\\(([^)]*)\\)")
    private val link = Regex("\\[([^\\]]+)]\\(([^)\\s]+)[^)]*\\)")
    private val bold = Regex("(\\*\\*|__)(.+?)\\1")
    private val italic = Regex("(?<![\\w*])([*_])(?!\\s)(.+?)(?<!\\s)\\1(?![\\w*])")
    private val strike = Regex("~~(.+?)~~")
    private val code = Regex("`([^`]+)`")

    /**
     * [markdown] as plain text for pasting where Markdown does not render: no fences, heading marks, emphasis or
     * backticks; links keep their address in parentheses; bullets become "•". Code inside fences is kept as is.
     */
    fun plain(markdown: String): String {
        val out = ArrayList<String>()
        var inCode = false
        for (line in markdown.lines()) {
            if (fence.matches(line)) {
                inCode = !inCode
                continue
            }
            if (inCode) {
                out += line
                continue
            }
            if (rule.matches(line)) {
                out += ""
                continue
            }
            var l = line.replace(heading, "").replace(quote, "")
            l = l.replace(bullet) { it.groupValues[1] + "• " }
            l = l.replace(image) { it.groupValues[1] }
            l = l.replace(link) { m -> if (m.groupValues[1] == m.groupValues[2]) m.groupValues[1] else "${m.groupValues[1]} (${m.groupValues[2]})" }
            l = l.replace(code) { it.groupValues[1] }
            l = l.replace(bold) { it.groupValues[2] }
            l = l.replace(strike) { it.groupValues[1] }
            l = l.replace(italic) { it.groupValues[2] }
            out += l
        }
        return out.joinToString("\n").trim()
    }

    /**
     * The user message that [itemKey]'s turn answers, and which copy of that text it is (0 for the first message with
     * that text): what fork needs to find it. Null when the item is not after a user message.
     */
    fun promptOf(items: List<ChatItem>, itemKey: String): Pair<UserItem, Int>? {
        val at = items.indexOfFirst { it.key == itemKey || (it is AssistantTextItem && itemKey.startsWith(it.key + "#")) }
        if (at < 0) return null
        val user = (at downTo 0).firstNotNullOfOrNull { items[it] as? UserItem } ?: return null
        val before = items.subList(0, items.indexOf(user)).count { it is UserItem && it.text == user.text }
        return user to before
    }
}
