package opensec.pibolt.app.ui.session.features2

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import opensec.pibolt.app.engine.SessionSummary
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.obj
import opensec.pibolt.protocol.str
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class SessionSort(val label: String) { Recent("Recent"), Name("Name"), Size("Size") }

/** A saved session with what the list sorts and filters by. */
data class BrowsedSession(val summary: SessionSummary, val bytes: Long) {
    val title: String get() = summary.name ?: summary.preview ?: "Untitled session"
}

/** A session whose messages contain the searched text: how often, and the first place, as a snippet. */
data class ContentHit(val session: BrowsedSession, val count: Int, val snippet: String)

/**
 * The session list's sorting, filtering and full-text search, and the file edits behind rename and delete for sessions
 * no engine has open (packages/coding-agent/docs/session-format.md).
 */
object SessionBrowse {
    /** [sessions] whose name or first prompt contains [query] (any case), in [sort] order. */
    fun arrange(sessions: List<BrowsedSession>, sort: SessionSort, query: String): List<BrowsedSession> {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) {
            sessions
        } else {
            sessions.filter { s -> listOfNotNull(s.summary.name, s.summary.preview).any { q in it.lowercase() } }
        }
        return when (sort) {
            SessionSort.Recent -> filtered.sortedByDescending { it.summary.updatedAt }
            SessionSort.Name -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            SessionSort.Size -> filtered.sortedByDescending { it.bytes }
        }
    }

    /**
     * Sessions whose user or assistant text contains [query] (any case), most matches first. Reads every file: call off
     * the main thread. Lines are matched as raw text first, so only lines that can match are parsed.
     */
    fun search(sessions: List<BrowsedSession>, query: String, limit: Int = 50): List<ContentHit> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val hits = ArrayList<ContentHit>()
        for (s in sessions) {
            var count = 0
            var snippet: String? = null
            runCatching {
                File(s.summary.file).useLines { lines ->
                    for (line in lines) {
                        if (!line.contains(q, ignoreCase = true) || !line.startsWith("{\"type\":\"message\"")) continue
                        val text = messageText(line) ?: continue
                        val at = text.indexOf(q, ignoreCase = true)
                        if (at < 0) continue
                        count += countOf(text, q)
                        if (snippet == null) snippet = snippetAround(text, at, q.length)
                    }
                }
            }
            if (count > 0) hits += ContentHit(s, count, snippet.orEmpty())
        }
        return hits.sortedWith(compareByDescending<ContentHit> { it.count }.thenByDescending { it.session.summary.updatedAt }).take(limit)
    }

    /** The visible text of a user or assistant message line, or null. */
    fun messageText(line: String): String? {
        val entry = runCatching { ProtocolJson.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return null
        val message = entry.obj("message") ?: return null
        if (message.str("role") !in setOf("user", "assistant")) return null
        return when (val c = message["content"]) {
            is JsonPrimitive -> c.content
            is JsonArray -> c.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "text" }?.str("text") }.joinToString("\n")
            else -> null
        }
    }

    private fun countOf(text: String, q: String): Int {
        var n = 0
        var i = text.indexOf(q, ignoreCase = true)
        while (i >= 0) {
            n++
            i = text.indexOf(q, i + q.length, ignoreCase = true)
        }
        return n
    }

    /** About [width] characters of [text] around the match at [at], on one line. */
    fun snippetAround(text: String, at: Int, length: Int, width: Int = 120): String {
        val start = (at - width / 3).coerceAtLeast(0)
        val end = (at + length + width * 2 / 3).coerceAtMost(text.length)
        val body = text.substring(start, end).replace(Regex("\\s+"), " ").trim()
        return (if (start > 0) "…" else "") + body + (if (end < text.length) "…" else "")
    }

    /**
     * Names a session no engine has open, as `/name` does: a `session_info` entry appended after its last entry. Blocking.
     */
    fun rename(file: File, name: String, now: Instant = Instant.now()): Boolean = runCatching {
        val lastId = lastEntryId(file)
        val entry = buildJsonObject {
            put("type", "session_info")
            put("id", newId())
            put("parentId", lastId)
            put("timestamp", ISO.format(now))
            put("name", name.trim())
        }
        val text = file.readText()
        file.appendText((if (text.isEmpty() || text.endsWith("\n")) "" else "\n") + entry.toString() + "\n")
        true
    }.getOrDefault(false)

    /** The id of the last entry in [file] (the leaf a new entry continues from), or null for a header-only file. */
    fun lastEntryId(file: File): String? {
        var last: String? = null
        file.useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                val o = runCatching { ProtocolJson.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
                if (o.str("type") != "session") o.str("id")?.let { last = it }
            }
        }
        return last
    }

    private fun newId(): String = (1..8).map { "0123456789abcdef".random() }.joinToString("")

    private val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** "12 KB", "3.4 MB". */
    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
