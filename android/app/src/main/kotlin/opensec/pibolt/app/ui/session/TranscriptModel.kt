package opensec.pibolt.app.ui.session

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.markdown.Markdown
import opensec.pibolt.app.ui.markdown.MdBlock
import opensec.pibolt.app.ui.markdown.MdDoc
import opensec.pibolt.protocol.session.AssistantTextItem
import opensec.pibolt.protocol.session.ChatItem
import opensec.pibolt.protocol.session.NoticeItem
import opensec.pibolt.protocol.session.SessionReducer
import opensec.pibolt.protocol.session.Subagent
import opensec.pibolt.protocol.session.SubagentNoticeItem
import opensec.pibolt.protocol.session.SubagentReducer
import opensec.pibolt.protocol.session.SubagentStatus
import opensec.pibolt.protocol.session.ThinkingItem
import opensec.pibolt.protocol.session.ToolItem
import opensec.pibolt.protocol.session.ToolStatus
import opensec.pibolt.protocol.session.UserItem

/** One lazy-list row of the transcript. Keys are stable for the life of the row. */
sealed interface TRow {
    val key: String
    val live: Boolean
}

/**
 * A row's place on the step rail of an opened fold or tool group: a 1dp line through the step glyphs that ties the
 * steps to their header. [stagger] and [index] time the short stagger when the fold or group opens.
 */
@Immutable
data class Rail(val first: Boolean, val last: Boolean, val stagger: String, val index: Int)

/** A user message. [alias] is the key of the "Sending" row it replaced, so the list keeps that row in place. */
@Immutable
data class UserRow(val item: UserItem, override val live: Boolean, val alias: String? = null) : TRow {
    override val key: String get() = alias ?: item.key
}

/** A prompt sent from this phone that the host has not echoed yet ("Sending"). */
@Immutable
data class PendingUserRow(val pending: PendingSend) : TRow {
    override val key: String get() = pending.rowKey
    override val live: Boolean get() = true
}

/** One markdown block of an assistant message. [tail] is the block still being written. */
@Immutable
data class TextRow(
    val itemKey: String,
    val index: Int,
    val block: MdBlock,
    val first: Boolean,
    val tail: Boolean,
    override val live: Boolean,
    val rail: Rail? = null,
) : TRow {
    override val key: String get() = "$itemKey#$index"
}

@Immutable

data class ThinkingRow(val item: ThinkingItem, override val live: Boolean, val rail: Rail? = null) : TRow {
    override val key: String get() = item.key
}

@Immutable

data class ToolRow(val item: ToolItem, override val live: Boolean, val rail: Rail? = null) : TRow {
    override val key: String get() = item.key
}

/** Three or more consecutive finished read-only steps as one row ("Read 3 files"); [expanded] shows them all. */
@Immutable
data class ToolGroupRow(
    override val key: String,
    val tools: List<ToolItem>,
    val expanded: Boolean,
    override val live: Boolean,
    val rail: Rail? = null,
) : TRow

@Immutable

data class NoticeRow(val item: NoticeItem, override val live: Boolean) : TRow {
    override val key: String get() = item.key
}

/**
 * A subagent's card: an `Agent` call and its run ([Subagent], without its transcript so rows compare cheaply). [run]
 * is a stand-in built from the call while the model still writes it.
 */
@Immutable
data class SubagentRow(override val key: String, val run: Subagent, override val live: Boolean) : TRow

/** Consecutive `Agent` calls (parallel agents) as one stack: "3 agents running"; [expanded] shows each card. */
@Immutable
data class SubagentStackRow(override val key: String, val runs: List<Subagent>, val expanded: Boolean, override val live: Boolean) : TRow

/** Background subagents ended: a line for each run the notification names ([runs] holds those still known). */
@Immutable
data class SubagentNoticeRow(val item: SubagentNoticeItem, val runs: List<Subagent>, override val live: Boolean) : TRow {
    override val key: String get() = item.key
}

/**
 * "Worked for 12s · 6 steps": the folded work of a finished turn. [failedSteps] counts tool steps that failed;
 * [endedInFailure] is true only when the turn's last step failed (a failed test run that a later step fixed does not
 * make the turn a failure). [files] are the files the turn edited or wrote.
 */
@Immutable
data class FoldRow(
    val turnKey: String,
    val steps: Int,
    val durationMs: Long?,
    val expanded: Boolean,
    val failedSteps: Int,
    val endedInFailure: Boolean,
    val files: List<FileChange> = emptyList(),
) : TRow {
    override val key: String get() = "fold:$turnKey"
    override val live: Boolean get() = false
}

/**
 * Copy, Ask again and More under the last finished reply. [reply] is the answer's text (the prose after the turn's
 * last step); [prompt] is the user's text to send again, null when it cannot be resent as text (images, no prompt).
 */
@Immutable
data class ActionsRow(val turnKey: String, val reply: String, val prompt: String?) : TRow {
    override val key: String get() = "actions:$turnKey"
    override val live: Boolean get() = false
}

/** Space between turns. */
@Immutable
data class GapRow(val turnKey: String) : TRow {
    override val key: String get() = "gap:$turnKey"
    override val live: Boolean get() = false
}

/**
 * An idle prompt sent from this phone and not yet echoed by the host. [lastUserKey] is the newest user message at send
 * time; [at] the uptime of the send.
 */
@Immutable
data class PendingSend(val id: Long, val text: String, val lastUserKey: String?, val at: Long, val images: Int = 0) {
    /** The row key; the echoed user message takes it over (see [UserRow.alias]). */
    val rowKey: String get() = "pending:$id"

    companion object {
        /** A pending send with no echo is dropped after this long while Pi is idle. */
        const val TIMEOUT_MS = 30_000L

        /** Only plain prompts (text, images or both): a slash command is echoed as its expansion, or not at all. */
        fun eligible(text: String, running: Boolean, images: Int = 0): Boolean =
            !running && (text.isNotBlank() || images > 0) && !text.startsWith("/")

        /**
         * True once the send is settled: the host echoed it (a user message newer than [PendingSend.lastUserKey]), the
         * run it started already ended, or nothing happened for [TIMEOUT_MS] while idle.
         */
        fun resolved(pending: PendingSend, lastUserKey: String?, settledAt: Long, running: Boolean, now: Long): Boolean =
            lastUserKey != pending.lastUserKey || settledAt > pending.at || (!running && now - pending.at > TIMEOUT_MS)

        /** The key of the newest user message in [items]. */
        fun lastUserKey(items: List<ChatItem>): String? {
            for (i in items.indices.reversed()) {
                val item = items[i]
                if (item is UserItem) return item.key
            }
            return null
        }
    }
}

/** What the status line says: a label that shimmers, and for a running tool its argument in mono. */
@Immutable
data class StatusText(val label: String, val detail: String = "", val path: Boolean = false, val more: Int = 0)

/**
 * Incremental markdown parses per assistant message (a growing message re-parses only its last block), and the
 * file change of each finished edit (parsed once per tool item, not on every streamed frame).
 */
class MdCache(
    /** When set, a large message that needs a full parse is parsed here (off the main thread) instead of inline. */
    private val background: CoroutineScope? = null,
) {
    private val docs = HashMap<String, MdDoc>()
    private val changes = HashMap<String, Pair<ToolItem, FileChange?>>()
    private val parsing = HashMap<String, String>()

    /** Bumped when a background parse lands; readers (the transcript's derived rows) recompute then. */
    val version = mutableIntStateOf(0)

    fun parse(item: AssistantTextItem): MdDoc {
        val previous = docs[item.key]
        val incremental = previous != null && (previous.text == item.text || item.text.startsWith(previous.text))
        if (background != null && !incremental && item.text.length > LARGE) {
            // A restored or replaced long message: parse it on Default and show a plain first screen meanwhile.
            if (parsing[item.key] != item.text) {
                parsing[item.key] = item.text
                val text = item.text
                background.launch {
                    val doc = withContext(Dispatchers.Default) { Markdown.parse(text) }
                    if (parsing[item.key] == text) {
                        parsing.remove(item.key)
                        docs[item.key] = doc
                        version.intValue++
                    }
                }
            }
            return previous ?: Markdown.placeholder(item.text)
        }
        val doc = Markdown.parse(item.text, previous)
        docs[item.key] = doc
        return doc
    }

    fun change(item: ToolItem): FileChange? {
        changes[item.key]?.let { (old, change) -> if (old === item) return change }
        val change = fileChange(item)
        changes[item.key] = item to change
        return change
    }

    fun retain(keys: Set<String>) {
        docs.keys.retainAll(keys)
        changes.keys.retainAll(keys)
    }

    companion object {
        /** Messages longer than this (characters) are fully parsed off the main thread. */
        const val LARGE = 20_000
    }
}

/**
 * Splits the conversation into turns (a user message and what followed) and lays them out as rows.
 *
 * A finished turn's steps (thinking, tools, text written between them) fold into one [FoldRow] unless [expanded]
 * holds its key; the text after the last step (the answer) and notices stay visible. The live turn never folds. Runs
 * of three or more finished read-only steps become one [ToolGroupRow] (open when [openGroups] holds its key). The last
 * finished turn ends with an [ActionsRow]; a [pending] send shows as a turn of its own at the end. [aliases] maps an
 * echoed user message's key to the pending row it replaced.
 */
object TranscriptModel {
    /** A group needs at least this many consecutive read-only steps. */
    const val GROUP_MIN = 3

    fun rows(
        items: List<ChatItem>,
        liveTurn: Boolean,
        expanded: Set<String>,
        durations: Map<String, Long>,
        md: MdCache,
        openGroups: Set<String> = emptySet(),
        pending: PendingSend? = null,
        aliases: Map<String, String> = emptyMap(),
        subagents: Map<String, Subagent> = emptyMap(),
        /** False: `Agent` calls stay plain tool rows (inside a subagent's own transcript). */
        agentCards: Boolean = true,
    ): List<TRow> {
        val agents = Agents(subagents, agentCards, openGroups)
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
                out += UserRow(first, live, aliases[first.key])
                bodyStart = start + 1
            }
            var lastStep = -1
            var steps = 0
            var failedSteps = 0
            var lastToolFailed = false
            for (i in bodyStart until end) {
                val it = items[i]
                // A command the user ran (`!ls`) is not the agent's work: it stays visible after the answer.
                if ((it is ToolItem && !isUserShell(it)) || it is ThinkingItem) {
                    lastStep = i
                    steps++
                }
                if (it is ToolItem && !isUserShell(it)) {
                    lastToolFailed = it.status == ToolStatus.Error
                    if (lastToolFailed) failedSteps++
                }
            }
            val foldable = !live && steps > 0
            val open = !foldable || turnKey in expanded
            // Only a duration this phone measured while watching the turn. Summing the tools' own run times would
            // leave out the model's time and read far too short ("1s" for a 10 s turn), so an unwatched turn shows none.
            if (foldable) {
                val files = mergeChanges((bodyStart until end).mapNotNull { (items[it] as? ToolItem)?.let(md::change) })
                out += FoldRow(turnKey, steps, durations[turnKey], open, failedSteps, lastToolFailed, files)
            }
            if (open) {
                out += TurnBody(md, live, turnKey, foldOpen = foldable, openGroups, agents).build(items, bodyStart, end, lastStep)
            } else {
                // Folded: only notices, the answer and agent cards (a unit of work of its own, and background ones
                // outlive the turn that started them).
                var i = bodyStart
                while (i < end) {
                    val item = items[i]
                    if (item is ToolItem && agents.card(item)) {
                        i = agents.emit(out, items, i, end, live) { agents.card(it) }
                        continue
                    }
                    if (i > lastStep || item is NoticeItem || item is SubagentNoticeItem) addItem(out, item, live, md, agents)
                    i++
                }
            }
            if (isLast && !live && pending == null) {
                val reply = (lastStep + 1 until end).mapNotNull { (items[it] as? AssistantTextItem)?.text?.trim()?.takeIf(String::isNotEmpty) }
                if (reply.isNotEmpty()) {
                    val prompt = (first as? UserItem)?.takeIf { it.imageCount == 0 && it.text.isNotBlank() }?.text
                    out += ActionsRow(turnKey, reply.joinToString("\n\n"), prompt)
                }
            }
        }
        if (pending != null) {
            if (out.isNotEmpty()) out += GapRow("pending:${pending.id}")
            out += PendingUserRow(pending)
        }
        return out
    }

    /** The visible body of an open turn: groups read-only runs and puts the rows of an opened fold on a rail. */
    private class TurnBody(
        val md: MdCache,
        val live: Boolean,
        val turnKey: String,
        val foldOpen: Boolean,
        val openGroups: Set<String>,
        val agents: Agents,
    ) {
        private val rows = ArrayList<TRow>()

        // Rail segment and stagger of each row (null: no rail).
        private val segments = ArrayList<Int?>()
        private val staggers = ArrayList<Pair<String, Int>?>()
        private val counters = HashMap<String, Int>()
        private var nextSegment = 1

        private fun emit(row: TRow, segment: Int?, stagger: String?) {
            rows += row
            segments += segment
            staggers += stagger?.let(::nextIndex)
        }

        private fun nextIndex(stagger: String): Pair<String, Int> {
            val index = counters[stagger] ?: 0
            counters[stagger] = index + 1
            return stagger to index
        }

        fun build(items: List<ChatItem>, from: Int, end: Int, lastStep: Int): List<TRow> {
            val foldSegment = if (foldOpen) 0 else null
            val foldStagger = if (foldOpen) turnKey else null
            var i = from
            while (i < end) {
                val item = items[i]
                val inFold = i <= lastStep && item !is NoticeItem
                if (item is ToolItem && agents.card(item)) {
                    // Cards sit apart from the fold's rail: the line breaks around them.
                    val before = rows.size
                    i = agents.emit(rows, items, i, end, live) { agents.card(it) }
                    repeat(rows.size - before) {
                        segments += null
                        staggers += null
                    }
                    continue
                }
                if (item is ToolItem && isGroupable(item)) {
                    val run = ArrayList<ToolItem>()
                    var j = i
                    while (j < end) {
                        val next = items[j]
                        if (next is ToolItem && isGroupable(next)) {
                            run += next
                        } else if (!(next is AssistantTextItem && next.text.isBlank())) {
                            break
                        }
                        j++
                    }
                    if (run.size >= GROUP_MIN) {
                        val groupKey = "group:${run[0].key}"
                        val groupOpen = groupKey in openGroups
                        emit(ToolGroupRow(groupKey, run, groupOpen, live), foldSegment, foldStagger)
                        if (groupOpen) {
                            val segment = foldSegment ?: nextSegment++
                            for (tool in run) emit(ToolRow(tool, live), segment, groupKey)
                        }
                        i = j
                        continue
                    }
                }
                val segment = if (inFold) foldSegment else null
                val stagger = if (inFold) foldStagger else null
                val before = rows.size
                addItem(rows, item, live, md, agents)
                for (k in before until rows.size) {
                    val apart = rows[k] is NoticeRow || rows[k] is SubagentNoticeRow || rows[k] is SubagentRow
                    segments += if (apart) null else segment
                    staggers += if (apart) null else stagger?.let(::nextIndex)
                }
                i++
            }
            val firstOf = HashMap<Int, Int>()
            val lastOf = HashMap<Int, Int>()
            segments.forEachIndexed { k, segment ->
                if (segment != null) {
                    firstOf.getOrPut(segment) { k }
                    lastOf[segment] = k
                }
            }
            return rows.mapIndexed { k, row ->
                val segment = segments[k] ?: return@mapIndexed row
                val (stagger, index) = staggers[k] ?: (turnKey to 0)
                withRail(row, Rail(firstOf[segment] == k, lastOf[segment] == k, stagger, index))
            }
        }
    }

    private fun withRail(row: TRow, rail: Rail): TRow = when (row) {
        is TextRow -> row.copy(rail = rail)
        is ThinkingRow -> row.copy(rail = rail)
        is ToolRow -> row.copy(rail = rail)
        is ToolGroupRow -> row.copy(rail = rail)
        else -> row
    }

    /** How `Agent` calls show: as cards ([SubagentRow], [SubagentStackRow]) built from their runs. */
    private class Agents(val runs: Map<String, Subagent>, val enabled: Boolean, val openGroups: Set<String>) {
        fun card(item: ToolItem): Boolean = enabled && SubagentReducer.isAgentCall(item.name)

        /** The run of [item] without its transcript; a stand-in from the call while it is still written. */
        fun run(item: ToolItem): Subagent {
            val run = runs[item.toolCallId] ?: return standIn(item)
            return if (run.transcript == null) run else run.copy(transcript = null)
        }

        /**
         * Rows for the `Agent` calls from [from] on that pass [take] (blank text between them is skipped): a card, or a
         * stack for two or more. Returns the index after them.
         */
        fun emit(out: MutableList<TRow>, items: List<ChatItem>, from: Int, end: Int, live: Boolean, take: (ToolItem) -> Boolean): Int {
            val calls = ArrayList<ToolItem>()
            var j = from
            while (j < end) {
                val next = items[j]
                if (next is ToolItem && take(next)) {
                    calls += next
                } else if (!(next is AssistantTextItem && next.text.isBlank())) {
                    break
                }
                j++
            }
            if (calls.size == 1) {
                out += SubagentRow(calls[0].key, run(calls[0]), live)
            } else {
                val key = "agents:${calls[0].key}"
                out += SubagentStackRow(key, calls.map(::run), key in openGroups, live)
            }
            return j
        }

        private fun standIn(item: ToolItem): Subagent {
            val label = toolLabel(item)
            return Subagent(
                key = item.toolCallId,
                type = label.verb,
                description = label.detail,
                status = if (item.status == ToolStatus.Error) SubagentStatus.Failed else SubagentStatus.Queued,
            )
        }
    }

    private fun addItem(out: MutableList<TRow>, item: ChatItem, live: Boolean, md: MdCache, agents: Agents? = null) {
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
            is ToolItem -> out += if (agents != null && agents.card(item)) SubagentRow(item.key, agents.run(item), live) else ToolRow(item, live)
            is NoticeItem -> out += NoticeRow(item, live)
            is SubagentNoticeItem -> out += SubagentNoticeRow(item, item.runKeys.mapNotNull { key -> agents?.runs?.get(key)?.let { if (it.transcript == null) it else it.copy(transcript = null) } }, live)
        }
    }

    /** A shell command the user ran from the app (`!ls`), live or from the session history (`bashExecution`). */
    fun isUserShell(item: ToolItem): Boolean = item.key.startsWith(SessionReducer.SHELL_PREFIX) || item.key.endsWith(".bash")

    /** LazyColumn needs unique keys: true when every row key is distinct. */
    fun keysUnique(rows: List<TRow>): Boolean {
        val seen = HashSet<String>(rows.size * 2)
        return rows.all { seen.add(it.key) }
    }

    /** What the live turn is doing, for the Quad and the status line. [running] is the newest running tool. */
    data class Activity(val toolRunning: Boolean, val subagent: Boolean, val writing: Boolean, val running: ToolItem? = null, val runningCount: Int = 0)

    /** Scans the live turn only (from the end back to the last user message). */
    fun activity(items: List<ChatItem>, streaming: List<ChatItem>): Activity {
        var subagent = false
        var running: ToolItem? = null
        var count = 0
        fun visit(item: ChatItem) {
            if (item is ToolItem && item.status == ToolStatus.Running) {
                count++
                if (isSubagentTool(item.name)) subagent = true
                if (running == null) running = item
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
        return Activity(count > 0, subagent, writing, running, count)
    }

    /**
     * The status line's text. A running tool is named ("Run" + `npm test`, "Read" + path); a subagent reads "Agent
     * working" + its task; a send not yet echoed reads "Sending"; otherwise the mode's label.
     */
    fun statusText(mode: QuadMode, activity: Activity, sending: Boolean): StatusText {
        val tool = activity.running
        val more = (activity.runningCount - 1).coerceAtLeast(0)
        return when {
            sending -> StatusText("Sending")
            tool != null && (mode == QuadMode.Run || mode == QuadMode.Agent) -> {
                val label = toolLabel(tool)
                val detail = label.detail.lineSequence().firstOrNull().orEmpty().trim()
                val path = label.kind == ToolKind.Read || label.kind == ToolKind.Write || label.kind == ToolKind.Edit || label.kind == ToolKind.List
                if (mode == QuadMode.Agent) StatusText(mode.label, detail, false, more) else StatusText(label.verb, detail, path, more)
            }
            else -> StatusText(mode.label)
        }
    }
}
