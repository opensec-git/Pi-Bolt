package opensec.pibolt.app.ui.session.tree

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.protocol.arr
import opensec.pibolt.protocol.obj
import opensec.pibolt.protocol.str

/** What a tree row stands for. */
enum class TreeKind { User, Assistant, Tool, Compaction, BranchSummary, Model, Other }

/**
 * One visible entry of the session tree (`get_entries`, packages/coding-agent/docs/rpc-commands.md), in display order.
 * [depth] counts the forks above it, so a long conversation stays flush left and each branch indents once.
 */
@Immutable
data class TreeRow(
    val id: String,
    val kind: TreeKind,
    val text: String,
    val depth: Int,
    val label: String?,
    /** On the path from the root to the current leaf: the conversation the model sees. */
    val active: Boolean,
    /** The current leaf (where the next message goes). */
    val leaf: Boolean,
    /** Number of branches leaving this entry (2 or more marks a fork). */
    val branches: Int,
    val timestamp: String?,
)

enum class TreeFilter(val label: String) {
    /** Prompts, replies, summaries: what a person reads. */
    Default("Messages"),
    User("Prompts"),
    Labeled("Labeled"),
    All("Everything"),
}

object SessionTree {
    /**
     * What a `get_entries` reply gives the sheet: the data, or an error to show as an error. A reply that succeeds
     * without data is an error too: otherwise the sheet would wait on "Reading the tree" forever.
     */
    fun load(success: Boolean, data: JsonObject?, error: String?): Pair<JsonObject?, String?> = when {
        success && data != null -> data to null
        success -> null to "Pi sent no session tree."
        else -> null to (error?.trim()?.takeIf { it.isNotEmpty() } ?: "Pi did not answer.")
    }

    /** Entries that are not points in the conversation: their children hang from their parent instead. */
    private val COLLAPSED = setOf("label", "usage")

    /**
     * The rows of [data] (`get_entries`' `data`: every entry in append order, and `leafId`) under [filter], depth first,
     * older branches first. Built from the flat list rather than `get_tree`, whose nesting (two levels per entry) is
     * deeper than the JSON parser takes for a long session. A hidden entry's children take its place; the leaf is
     * always shown so the reader sees where they are.
     */
    fun rows(data: JsonObject?, filter: TreeFilter = TreeFilter.Default): List<TreeRow> {
        data ?: return emptyList()
        val entries = data.arr("entries").orEmpty().mapNotNull { it as? JsonObject }.filter { it.str("id") != null }
        val byId = HashMap<String, JsonObject>(entries.size * 2)
        for (e in entries) byId[e.str("id")!!] = e
        // The nearest ancestor-or-self that is a conversation point.
        fun resolve(start: String?): String? {
            var at = start
            var guard = 0
            while (at != null && guard++ < entries.size + 1) {
                val e = byId[at] ?: return null
                if (e.str("type") !in COLLAPSED) return at
                at = e.str("parentId")
            }
            return null
        }
        val labels = HashMap<String, String>()
        for (e in entries) {
            if (e.str("type") != "label") continue
            val target = e.str("targetId") ?: continue
            val label = e.str("label")?.takeIf { it.isNotBlank() }
            if (label != null) labels[target] = label else labels.remove(target)
        }
        val children = HashMap<String, MutableList<JsonObject>>()
        val parentOf = HashMap<String, String>()
        val roots = ArrayList<JsonObject>()
        for (e in entries) {
            if (e.str("type") in COLLAPSED) continue
            val id = e.str("id")!!
            val parent = resolve(e.str("parentId"))
            if (parent == null || parent == id) {
                roots += e
            } else {
                parentOf[id] = parent
                children.getOrPut(parent) { ArrayList() } += e
            }
        }
        val leafId = resolve(data.str("leafId"))
        val active = HashSet<String>()
        var at = leafId
        while (at != null && active.add(at)) at = parentOf[at]
        val out = ArrayList<TreeRow>()
        // Iterative: a long session is a chain thousands of entries deep.
        val stack = ArrayDeque<Pair<JsonObject, Int>>()
        for (root in roots.asReversed()) stack.addLast(root to 0)
        while (stack.isNotEmpty()) {
            val (e, depth) = stack.removeLast()
            val id = e.str("id")!!
            val kind = kindOf(e)
            val label = labels[id]
            val kids = children[id].orEmpty()
            if (id == leafId || visible(e, kind, label, filter)) {
                out += TreeRow(
                    id = id,
                    kind = kind,
                    text = textOf(e, kind),
                    depth = depth,
                    label = label,
                    active = id in active,
                    leaf = id == leafId,
                    branches = kids.size,
                    timestamp = e.str("timestamp"),
                )
            }
            val childDepth = if (kids.size > 1) depth + 1 else depth
            // Pushed last-first so the oldest branch comes out first.
            for (child in kids.asReversed()) stack.addLast(child to childDepth)
        }
        return out
    }

    /**
     * The entry to rewind to for the user message [text] that is the [fromEnd]-th copy counting back from the newest
     * on the active branch (0: the newest). Only the path from the root to the leaf counts: a regenerate leaves the old
     * prompt on a branch the transcript no longer shows, and counting it would pick the wrong copy. Counted from the
     * end because the transcript shows the end of that path (a compaction drops its start). Null when there is none.
     */
    fun rewindTarget(data: JsonObject?, text: String, fromEnd: Int): String? {
        data ?: return null
        val entries = data.arr("entries").orEmpty().mapNotNull { it as? JsonObject }
        val byId = HashMap<String, JsonObject>(entries.size * 2)
        for (e in entries) e.str("id")?.let { byId[it] = e }
        val wanted = flat(text)
        val matches = ArrayList<String>()
        var at = data.str("leafId")
        var guard = 0
        while (at != null && guard++ <= entries.size) {
            val e = byId[at] ?: break
            if (kindOf(e) == TreeKind.User && flat(content(e.obj("message"))) == wanted) matches += at
            at = e.str("parentId")
        }
        // Walked from the leaf back: the newest copy is first.
        return matches.getOrNull(fromEnd)
    }

    private fun flat(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    fun kindOf(entry: JsonObject): TreeKind = when (entry.str("type")) {
        "message" -> when (entry.obj("message")?.str("role")) {
            "user" -> TreeKind.User
            "assistant" -> TreeKind.Assistant
            "toolResult", "bashExecution" -> TreeKind.Tool
            else -> TreeKind.Other
        }
        "compaction" -> TreeKind.Compaction
        "branch_summary" -> TreeKind.BranchSummary
        "model_change", "thinking_level_change" -> TreeKind.Model
        else -> TreeKind.Other
    }

    private fun visible(entry: JsonObject, kind: TreeKind, label: String?, filter: TreeFilter): Boolean = when (filter) {
        TreeFilter.All -> entry.str("type") !in setOf("label", "usage")
        TreeFilter.Labeled -> label != null
        TreeFilter.User -> kind == TreeKind.User
        TreeFilter.Default -> when (kind) {
            TreeKind.User, TreeKind.Compaction, TreeKind.BranchSummary -> true
            TreeKind.Assistant -> content(entry.obj("message")).isNotBlank()
            else -> label != null
        }
    }

    /** One line of preview text for [entry]. */
    fun textOf(entry: JsonObject, kind: TreeKind = kindOf(entry)): String {
        val raw = when (kind) {
            TreeKind.User -> content(entry.obj("message"))
            TreeKind.Assistant -> {
                val message = entry.obj("message")
                val text = content(message)
                text.ifBlank {
                    val tools = message?.arr("content").orEmpty().mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "toolCall" }?.str("name") }
                    if (tools.isEmpty()) "" else "→ " + tools.joinToString(", ")
                }
            }
            TreeKind.Tool -> entry.obj("message")?.let { it.str("toolName") ?: it.str("command")?.let { c -> "$ $c" } }.orEmpty()
            TreeKind.Compaction -> "Compaction: " + entry.str("summary").orEmpty()
            TreeKind.BranchSummary -> "Branch summary: " + entry.str("summary").orEmpty()
            TreeKind.Model -> entry.str("modelId")?.let { "Model: " + (entry.str("provider")?.let { p -> "$p/" }.orEmpty()) + it }
                ?: entry.str("thinkingLevel")?.let { "Thinking: $it" }.orEmpty()
            TreeKind.Other -> entry.str("customType") ?: entry.str("type").orEmpty()
        }
        val flat = raw.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= PREVIEW) flat else flat.take(PREVIEW - 1).trimEnd() + "…"
    }

    private fun content(message: JsonObject?): String = when (val c = message?.get("content")) {
        is JsonPrimitive -> c.content
        is JsonArray -> c.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "text" }?.str("text") }.joinToString(" ")
        else -> ""
    }

    private const val PREVIEW = 140
}
