package opensec.pibolt.app.preview

import kotlinx.serialization.json.JsonObject
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.int
import opensec.pibolt.protocol.str

/**
 * The preview page's console: messages from `WebChromeClient.onConsoleMessage`, uncaught errors with their stacks from
 * the page hook ([PreviewScripts.ERROR_HOOK], which reports them as a console error starting with [HOOK_MARK]),
 * failed requests and navigations. The newest [capacity] entries are kept, each cut to [maxChars]; every entry has a
 * sequence number, so `preview_console {since}` returns only what is new.
 */
class ConsoleBuffer(private val capacity: Int = 500, private val maxChars: Int = 2_000) {
    enum class Level(val tag: String) { Debug("debug"), Log("log"), Info("info"), Warn("warn"), Error("error"), Network("network"), Page("page") }

    data class Entry(val seq: Long, val level: Level, val text: String, val source: String? = null, val line: Int? = null)

    /** Entries after a cursor, the cursor for the next call, and how many older ones were dropped meanwhile. */
    data class Snapshot(val entries: List<Entry>, val next: Long, val dropped: Long)

    private val entries = ArrayDeque<Entry>()
    private var seq = 0L

    /** The hook's last error message: Chromium logs the same error once more as "Uncaught …", which is skipped. */
    private var hooked: String? = null

    @Synchronized
    fun add(level: Level, text: String, source: String? = null, line: Int? = null) {
        if (level == Level.Error && text.startsWith(HOOK_MARK)) {
            val hook = runCatching { ProtocolJson.parseToJsonElement(text.removePrefix(HOOK_MARK)) as? JsonObject }.getOrNull()
            if (hook != null) {
                val message = hook.str("message").orEmpty().ifBlank { "Uncaught error" }
                val stack = hook.str("stack")?.lineSequence()?.map { it.trim() }?.filter { it.startsWith("at ") }?.take(STACK_LINES)?.joinToString("\n") { "    $it" }
                hooked = message
                append(Level.Error, if (stack.isNullOrEmpty()) message else "$message\n$stack", hook.str("source")?.takeIf { it.isNotBlank() }, hook.int("line")?.takeIf { it > 0 })
                return
            }
        }
        val last = hooked
        hooked = null
        if (level == Level.Error && last != null && (text == last || text.endsWith(last) || last.endsWith(text))) return
        append(level, text, source, line)
    }

    private fun append(level: Level, text: String, source: String?, line: Int?) {
        val cut = if (text.length > maxChars) text.take(maxChars) + "…" else text
        entries.addLast(Entry(++seq, level, cut, source, line))
        while (entries.size > capacity) entries.removeFirst()
    }

    @Synchronized
    fun since(cursor: Long?, errorsOnly: Boolean = false): Snapshot {
        val from = cursor ?: 0L
        val oldest = entries.firstOrNull()?.seq ?: (seq + 1)
        val dropped = (oldest - from - 1).coerceAtLeast(0)
        val picked = entries.filter { it.seq > from && (!errorsOnly || it.level == Level.Error || it.level == Level.Warn || it.level == Level.Network) }
        return Snapshot(picked, seq, dropped)
    }

    /** The cursor now: entries added later count as new. */
    @Synchronized
    fun cursor(): Long = seq

    /** Errors (and failed requests) after [cursor]. */
    @Synchronized
    fun errorsSince(cursor: Long): Int = entries.count { it.seq > cursor && (it.level == Level.Error || it.level == Level.Network) }

    @Synchronized
    fun clear() {
        entries.clear()
        hooked = null
    }

    companion object {
        /** An invisible separator and a word: what the page hook's console errors start with. */
        const val HOOK_MARK = "⁣pibolt-error "
        private const val STACK_LINES = 6

        /** The most text `preview_console` returns: the newest entries that fit. */
        const val MAX_REPLY_CHARS = 16_000

        /** The model-facing text of [snapshot]: one line per entry (stack lines indented), newest last. */
        fun format(snapshot: Snapshot, maxChars: Int = MAX_REPLY_CHARS): String {
            val lines = snapshot.entries.map { e ->
                val where = e.source?.let { s -> " (" + s.substringAfterLast('/').ifBlank { s } + (e.line?.let { ":$it" } ?: "") + ")" }.orEmpty()
                if (e.level == Level.Page) "— ${e.text} —" else "[${e.level.tag}] ${e.text}$where"
            }
            var size = 0
            val kept = ArrayDeque<String>()
            for (line in lines.asReversed()) {
                if (size + line.length + 1 > maxChars && kept.isNotEmpty()) break
                kept.addFirst(line)
                size += line.length + 1
            }
            val skipped = lines.size - kept.size
            val errors = snapshot.entries.count { it.level == Level.Error }
            val head = when {
                snapshot.entries.isEmpty() -> "No new console messages."
                else -> "${snapshot.entries.size} message${if (snapshot.entries.size == 1) "" else "s"}" +
                    (if (errors > 0) ", $errors error${if (errors == 1) "" else "s"}" else "") + ":"
            }
            return buildString {
                append(head)
                if (snapshot.dropped > 0 || skipped > 0) append("\n(${snapshot.dropped + skipped} older messages not shown)")
                for (line in kept) append('\n').append(line)
                append("\n\nCursor: ${snapshot.next} (pass since=${snapshot.next} for newer messages)")
            }
        }
    }
}
