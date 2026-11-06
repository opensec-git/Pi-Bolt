package opensec.pibolt.app.ui.session

import opensec.pibolt.app.ui.markdown.Markdown
import opensec.pibolt.app.ui.markdown.MdBlock
import opensec.pibolt.app.ui.markdown.MdDoc
import opensec.pibolt.protocol.session.AssistantTextItem
import opensec.pibolt.protocol.session.ChatItem
import opensec.pibolt.protocol.session.NoticeItem
import opensec.pibolt.protocol.session.ThinkingItem
import opensec.pibolt.protocol.session.ToolItem
import opensec.pibolt.protocol.session.ToolStatus
import opensec.pibolt.protocol.session.UserItem

/** One lazy-list row of the transcript. Keys are stable for the life of the row. */
sealed interface TRow {
    val key: String
    val live: Boolean
}

data class UserRow(val item: UserItem, override val live: Boolean) : TRow {
    override val key: String get() = item.key
}

/** One markdown block of an assistant message. [tail] is the block still being written. */
data class TextRow(val itemKey: String, val index: Int, val block: MdBlock, val first: Boolean, val tail: Boolean, override val live: Boolean) : TRow {
    override val key: String get() = "$itemKey#$index"
}

data class ThinkingRow(val item: ThinkingItem, override val live: Boolean) : TRow {
    override val key: String get() = item.key
}

data class ToolRow(val item: ToolItem, override val live: Boolean) : TRow {
    override val key: String get() = item.key
}

data class NoticeRow(val item: NoticeItem, override val live: Boolean) : TRow {
    override val key: String get() = item.key
}

/**
 * "Worked for 12s · 6 steps": the folded work of a finished turn. [failedSteps] counts tool steps that failed;
 * [endedInFailure] is true only when the turn's last step failed (a failed test run that a later step fixed does not
 * make the turn a failure).
 */
data class FoldRow(
    val turnKey: String,
    val steps: Int,
    val durationMs: Long?,
    val expanded: Boolean,
    val failedSteps: Int,
    val endedInFailure: Boolean,
) : TRow {
    override val key: String get() = "fold:$turnKey"
    override val live: Boolean get() = false
}

/** Space between turns. */
data class GapRow(val turnKey: String) : TRow {
    override val key: String get() = "gap:$turnKey"
    override val live: Boolean get() = false
}

/** Incremental markdown parses per assistant message: a growing message re-parses only its last block. */
class MdCache {
    private val docs = HashMap<String, MdDoc>()

    fun parse(item: AssistantTextItem): MdDoc {
        val doc = Markdown.parse(item.text, docs[item.key])
        docs[item.key] = doc
        return doc
    }

    fun retain(keys: Set<String>) {
        docs.keys.retainAll(keys)
    }
}

/**
 * Splits the conversation into turns (a user message and what followed) and lays them out as rows.
 *
 * A finished turn's steps (thinking, tools, text written between them) fold into one [FoldRow] unless [expanded]
 * holds its key; the text after the last step (the answer) and notices stay visible. The live turn never folds.
 */
object TranscriptModel {
    fun rows(
        items: List<ChatItem>,
        liveTurn: Boolean,
        expanded: Set<String>,
        durations: Map<String, Long>,
        md: MdCache,
    ): List<TRow> {
        val out = ArrayList<TRow>(items.size + 8)
        // Turn boundaries: each user message starts one; anything before the first belongs to a turn of its own.
        val starts = ArrayList<Int>()
        starts += 0
        for (i in items.indices) if (items[i] is UserItem && i > 0) starts += i
        for ((t, start) in starts.withIndex()) {
            val end = if (t + 1 < starts.size) starts[t + 1] else items.size
            if (start >= end) continue
            val isLast = t == starts.lastIndex
            val live = liveTurn && isLast
            val first = items[start]
            val turnKey = if (first is UserItem) first.key else "t:${first.key}"
            if (t > 0) out += GapRow(turnKey)
            var bodyStart = start
            if (first is UserItem) {
                out += UserRow(first, live)
                bodyStart = start + 1
            }
            var lastStep = -1
            var steps = 0
            var failedSteps = 0
            var lastToolFailed = false
            for (i in bodyStart until end) {
                val it = items[i]
                if (it is ToolItem || it is ThinkingItem) {
                    lastStep = i
                    steps++
                }
                if (it is ToolItem) {
                    lastToolFailed = it.status == ToolStatus.Error
                    if (lastToolFailed) failedSteps++
                }
            }
            val foldable = !live && steps > 0
            val open = !foldable || turnKey in expanded
            // Only a duration this phone measured while watching the turn. Summing the tools' own run times would
            // leave out the model's time and read far too short ("1s" for a 10 s turn), so an unwatched turn shows none.
            if (foldable) out += FoldRow(turnKey, steps, durations[turnKey], open, failedSteps, lastToolFailed)
            for (i in bodyStart until end) {
                val item = items[i]
                val isStep = i <= lastStep && item !is NoticeItem
                if (isStep && !open) continue
                addItem(out, item, live, md)
            }
        }
        return out
    }

    private fun addItem(out: MutableList<TRow>, item: ChatItem, live: Boolean, md: MdCache) {
        when (item) {
            is UserItem -> out += UserRow(item, live)
            is AssistantTextItem -> {
                if (item.text.isBlank()) return
                val doc = md.parse(item)
                val n = doc.blocks.size
                doc.blocks.forEachIndexed { i, block ->
                    out += TextRow(item.key, i, block, first = i == 0, tail = item.streaming && i == n - 1, live = live)
                }
            }
            is ThinkingItem -> out += ThinkingRow(item, live)
            is ToolItem -> out += ToolRow(item, live)
            is NoticeItem -> out += NoticeRow(item, live)
        }
    }

    /** LazyColumn needs unique keys: true when every row key is distinct. */
    fun keysUnique(rows: List<TRow>): Boolean {
        val seen = HashSet<String>(rows.size * 2)
        return rows.all { seen.add(it.key) }
    }

    /** What the live turn is doing, for the Quad and the status line. */
    data class Activity(val toolRunning: Boolean, val subagent: Boolean, val writing: Boolean, val runningVerb: String?)

    /** Scans the live turn only (from the end back to the last user message). */
    fun activity(items: List<ChatItem>, streaming: List<ChatItem>): Activity {
        var toolRunning = false
        var subagent = false
        var verb: String? = null
        fun visit(item: ChatItem) {
            if (item is ToolItem && item.status == ToolStatus.Running) {
                toolRunning = true
                if (isSubagentTool(item.name)) subagent = true
                if (verb == null) verb = toolLabel(item).let { label -> listOf(label.verb, label.detail).filter { it.isNotBlank() }.joinToString(" ") }
            }
        }
        streaming.forEach(::visit)
        for (i in items.indices.reversed()) {
            val item = items[i]
            if (item is UserItem) break
            visit(item)
        }
        val last = streaming.lastOrNull()
        val writing = (last is AssistantTextItem && last.streaming) || (last is ToolItem && last.status == ToolStatus.Streaming)
        return Activity(toolRunning, subagent, writing, verb)
    }
}
