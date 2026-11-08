package opensec.pibolt.app.ui.session.features

import androidx.compose.runtime.Immutable
import opensec.pibolt.app.ui.session.FoldRow
import opensec.pibolt.app.ui.session.NoticeRow
import opensec.pibolt.app.ui.session.TRow
import opensec.pibolt.app.ui.session.TextRow
import opensec.pibolt.app.ui.session.ThinkingRow
import opensec.pibolt.app.ui.session.SourcesRow
import opensec.pibolt.app.ui.session.ToolGroupRow
import opensec.pibolt.app.ui.session.ToolRow
import opensec.pibolt.app.ui.session.UserRow
import opensec.pibolt.protocol.session.AssistantTextItem
import opensec.pibolt.protocol.session.ChatItem
import opensec.pibolt.protocol.session.NoticeItem
import opensec.pibolt.protocol.session.SubagentNoticeItem
import opensec.pibolt.protocol.session.ThinkingItem
import opensec.pibolt.protocol.session.ToolItem
import opensec.pibolt.protocol.session.UserItem

/** A conversation item containing the query; [turnKey] is its turn (as [opensec.pibolt.app.ui.session.TranscriptModel] keys them). */
@Immutable
data class SearchHit(val itemKey: String, val turnKey: String, val snippet: String)

/** A request to show [hit]; [seq] makes a repeated jump to the same hit a new request. */
@Immutable
data class SearchJump(val hit: SearchHit, val seq: Int)

/**
 * Search in the transcript, as the terminal's Ctrl+Shift+F: every message, reasoning, tool call and output, oldest
 * first. A hit inside a folded turn opens the fold; one in a "Read 3 files" group opens the group.
 */
object TranscriptSearch {
    const val MAX_HITS = 500
    private const val SNIPPET = 80

    fun search(items: List<ChatItem>, query: String): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<SearchHit>()
        var turnKey: String? = null
        for (item in items) {
            if (item is UserItem) turnKey = item.key
            val turn = turnKey ?: "t:${items.first().key}"
            val text = textOf(item)
            val at = text.indexOf(q, ignoreCase = true)
            if (at >= 0) {
                out += SearchHit(item.key, turn, snippet(text, at, q.length))
                if (out.size >= MAX_HITS) break
            }
        }
        return out
    }

    /** Everything searchable in [item]: its text, a tool's name, arguments and output. */
    fun textOf(item: ChatItem): String = when (item) {
        is UserItem -> item.text
        is AssistantTextItem -> item.text
        is ThinkingItem -> item.text
        is NoticeItem -> item.title + "\n" + item.text
        is SubagentNoticeItem -> item.text
        is ToolItem -> buildString {
            append(item.name)
            item.args?.values?.forEach { append('\n').append(it.toString().trim('"')) }
            if (item.args == null) append('\n').append(item.argsText)
            append('\n').append(item.result ?: item.output)
        }
    }

    /** One line around the match, with an ellipsis where it was cut. */
    fun snippet(text: String, at: Int, length: Int): String {
        val start = (at - SNIPPET / 3).coerceAtLeast(0)
        val end = (at + length + SNIPPET).coerceAtMost(text.length)
        val body = text.substring(start, end).replace(Regex("\\s+"), " ").trim()
        return (if (start > 0) "…" else "") + body + if (end < text.length) "…" else ""
    }

    /**
     * The row that shows [hit] in [rows]: the item's own row, the group holding it, else its turn's fold (the item is
     * folded away), else its turn's user message. -1 when none is there.
     */
    fun rowIndex(rows: List<TRow>, hit: SearchHit): Int {
        val own = rows.indexOfFirst { row ->
            when (row) {
                is UserRow -> row.item.key == hit.itemKey
                is TextRow -> row.itemKey == hit.itemKey
                is ThinkingRow -> row.item.key == hit.itemKey
                is ToolRow -> row.item.key == hit.itemKey
                is NoticeRow -> row.item.key == hit.itemKey
                is ToolGroupRow -> row.tools.any { it.key == hit.itemKey }
                is SourcesRow -> row.tools.any { it.key == hit.itemKey }
                else -> false
            }
        }
        if (own >= 0) return own
        val fold = rows.indexOfFirst { it is FoldRow && it.turnKey == hit.turnKey }
        if (fold >= 0) return fold
        return rows.indexOfFirst { it is UserRow && it.item.key == hit.turnKey }
    }

    /** The lazy-list index of row [index] in the transcript: reversed, with the status line at 0. */
    fun listIndex(rowCount: Int, index: Int): Int = rowCount - index
}
