package opensec.pibolt.app.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.app.cost.Charges
import opensec.pibolt.app.cost.Cost
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.obj
import opensec.pibolt.protocol.str
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Pi's saved sessions for a project, read straight from the agent dir (packages/coding-agent/src/core/session-manager.ts):
 * `<agentDir>/sessions/--<cwd with the leading separator removed and / \ : as ->--/<timestamp>_<id>.jsonl`.
 *
 * Listing is fast: every line is classified by its first bytes (`{"type":"message"` and so on, as Pi's own listing
 * does), and only the header, `session_info` lines and the first user message are parsed. Usage records (assistant
 * messages, `usage`, compaction and branch summaries) give the cost from the bytes of their `usage.cost.total`, without
 * parsing the line. A session's cost includes its subagents' sessions (saved beside it, naming it as their parent).
 * Results are cached per file by size and modification time.
 */
class SessionIndex(private val agentDir: File) {
    /** One file: its summary (null for a subagent's or a damaged file), the parent it names, and its own cost. */
    private class Scan(val summary: SessionSummary?, val parent: String?, val subagent: Boolean, val cost: Cost)

    private data class Cached(val length: Long, val modified: Long, val scan: Scan?)

    private val cache = ConcurrentHashMap<String, Cached>()

    fun sessionDir(cwd: String): File =
        File(agentDir, "sessions/--" + cwd.replaceFirst(Regex("^[/\\\\]"), "").replace(Regex("[/\\\\:]"), "-") + "--")

    /** Newest first. [project] may be given by any path; its real path is what the engine records as its cwd. */
    fun list(project: File): List<SessionSummary> {
        val dirs = linkedSetOf(sessionDir(project.absolutePath))
        runCatching { project.canonicalPath }.getOrNull()?.let { dirs += sessionDir(it) }
        return listIn(dirs)
    }

    /** The sessions saved directly in [dirs] (a `--session-dir`, such as the chats' [ChatSpace.sessionDir]), newest first. */
    fun listIn(dirs: Collection<File>): List<SessionSummary> {
        val files = dirs.flatMap { dir -> dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }.orEmpty().toList() }
        val scans = files.mapNotNull(::scanned)
        val children = childCosts(scans)
        return scans.mapNotNull { scan -> scan.summary?.let { s -> children[s.file]?.let { s.copy(cost = s.cost + it) } ?: s } }
            .distinctBy { it.file }.sortedByDescending { it.updatedAt }
    }

    /** What [file] cost: its own records plus those of the subagents' sessions beside it. */
    fun cost(file: File): Cost {
        val own = scanned(file)?.cost ?: return Cost.NONE
        val siblings = file.parentFile?.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") && f.path != file.path }.orEmpty()
        return own + (childCosts(siblings.mapNotNull(::scanned))[file.path] ?: Cost.NONE)
    }

    private fun childCosts(scans: List<Scan>): Map<String, Cost> {
        val out = HashMap<String, Cost>()
        for (scan in scans) {
            val parent = scan.parent ?: continue
            if (scan.subagent) out[parent] = (out[parent] ?: Cost.NONE) + scan.cost
        }
        return out
    }

    fun newest(project: File): String? = list(project).firstOrNull()?.file

    fun summary(file: File): SessionSummary? = scanned(file)?.summary

    private fun scanned(file: File): Scan? {
        val length = file.length()
        val modified = file.lastModified()
        cache[file.path]?.let { if (it.length == length && it.modified == modified) return it.scan }
        val scan = runCatching { scan(file, modified) }.getOrNull()
        cache[file.path] = Cached(length, modified, scan)
        return scan
    }

    private fun scan(file: File, modified: Long): Scan? {
        var header: JsonObject? = null
        var name: String? = null
        var preview: String? = null
        var messages = 0
        var lineNumber = 0
        var cost = Cost.NONE
        file.inputStream().buffered(256 * 1024).use { input ->
            forEachLine(input) { line, length ->
                lineNumber++
                if (lineNumber == 1) {
                    val parsed = parse(line, length) ?: return@forEachLine false
                    if (parsed.str("type") != "session") return@forEachLine false
                    header = parsed
                    return@forEachLine true
                }
                when (typeOf(line, length)) {
                    "message" -> {
                        val role = roleOf(line, length)
                        if (role != "system") messages++
                        if (preview == null && role == "user") {
                            preview = parse(line, length)?.obj("message")?.let(::textOf)?.let(::shorten)?.takeIf { it.isNotEmpty() }
                        }
                        if (role == "assistant") costOf(line, length)?.let { cost += Cost(it, 1) }
                    }
                    "usage", "compaction", "branch_summary" -> costOf(line, length)?.let { cost += Cost(it, 1) }
                    "session_info" -> parse(line, length)?.let { name = it.str("name")?.trim()?.takeIf(String::isNotEmpty) }
                    null -> {
                        // Not in Pi's usual layout: parse it to be sure.
                        val entry = parse(line, length)
                        when (entry?.str("type")) {
                            "message" -> {
                                val message = entry.obj("message")
                                if (message?.str("role") != "system") messages++
                                if (message?.str("role") == "assistant") message.obj("usage")?.let { cost += Cost(Charges.usd(it) ?: 0.0, 1) }
                            }
                            "usage", "compaction", "branch_summary" -> entry.obj("usage")?.let { cost += Cost(Charges.usd(it) ?: 0.0, 1) }
                            "session_info" -> name = entry.str("name")?.trim()?.takeIf(String::isNotEmpty)
                        }
                    }
                }
                true
            }
        }
        val h = header ?: return null
        val parent = h.str("parentSession")
        // A subagent's own session (opensec-pi-subagents): part of its parent's run, not a session to list or resume.
        if (parent != null && name?.let(SUBAGENT_NAME::matches) == true) return Scan(null, parent, subagent = true, cost)
        val summary = SessionSummary(
            file = file.path,
            id = h.str("id") ?: file.nameWithoutExtension.substringAfter('_'),
            name = name,
            preview = preview,
            updatedAt = modified,
            messageCount = messages,
            cost = cost,
        )
        return Scan(summary, parent, subagent = false, cost)
    }

    private companion object {
        const val HEAD = "{\"type\":\""
        const val ROLE = "\"message\":{\"role\":\""
        const val PREVIEW_CHARS = 160

        /**
         * The name the subagents extension gives an agent's session: "<agent name>#<first 8 characters of its id>". The
         * id is a UUID for spawned agents and "wf-agent-<n>" for workflow agents ("wf-agent" after the cut). A custom
         * agent's name may have spaces ("Code Reviewer#1669f14e"); a spaced name needs one of those two id forms, so a
         * user's "Try #2" fork stays listed.
         */
        val SUBAGENT_NAME = Regex("""^(?:\S+#[0-9A-Za-z_-]{1,8}|.+#(?:[0-9a-f]{8}|wf-agent))$""")

        /** Calls [onLine] with each LF-terminated line (CR stripped) as bytes of a reused buffer, until it returns false. */
        fun forEachLine(input: java.io.InputStream, onLine: (ByteArray, Int) -> Boolean) {
            var line = ByteArray(8192)
            var length = 0
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                var start = 0
                for (i in 0 until n) {
                    if (chunk[i] != '\n'.code.toByte()) continue
                    val count = i - start
                    if (length + count > line.size) line = line.copyOf(maxOf(line.size * 2, length + count))
                    System.arraycopy(chunk, start, line, length, count)
                    length += count
                    if (length > 0 && line[length - 1] == '\r'.code.toByte()) length--
                    if (length > 0 && !onLine(line, length)) return
                    length = 0
                    start = i + 1
                }
                val rest = n - start
                if (length + rest > line.size) line = line.copyOf(maxOf(line.size * 2, length + rest))
                System.arraycopy(chunk, start, line, length, rest)
                length += rest
            }
            if (length > 0) onLine(line, length)
        }

        private val COST = "\"cost\":{".toByteArray(Charsets.ISO_8859_1)
        private val TOTAL = "\"total\":".toByteArray(Charsets.ISO_8859_1)

        /**
         * The `usage.cost.total` of a record line, from its bytes: the last `"cost":{` (usage follows the content, and
         * text in content is escaped, so a quote there is `\"`), then its `"total":` number. Null when there is none.
         */
        fun costOf(line: ByteArray, length: Int): Double? {
            val at = lastIndexOf(line, length, COST)
            if (at < 0) return null
            val total = indexOf(line, length, TOTAL, at + COST.size)
            if (total < 0) return null
            var i = total + TOTAL.size
            while (i < length && line[i] == ' '.code.toByte()) i++
            val start = i
            while (i < length && line[i].toInt().toChar().let { it.isDigit() || it == '.' || it == '-' || it == 'e' || it == 'E' || it == '+' }) i++
            return String(line, start, i - start, Charsets.ISO_8859_1).toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
        }

        private fun lastIndexOf(line: ByteArray, length: Int, needle: ByteArray): Int {
            var i = length - needle.size
            while (i >= 0) {
                if (matches(line, i, needle)) return i
                i--
            }
            return -1
        }

        private fun indexOf(line: ByteArray, length: Int, needle: ByteArray, from: Int): Int {
            for (i in from..length - needle.size) if (matches(line, i, needle)) return i
            return -1
        }

        private fun matches(line: ByteArray, at: Int, needle: ByteArray): Boolean {
            for (j in needle.indices) if (line[at + j] != needle[j]) return false
            return true
        }

        fun parse(line: ByteArray, length: Int): JsonObject? =
            runCatching { ProtocolJson.parseToJsonElement(String(line, 0, length, Charsets.UTF_8)) as? JsonObject }.getOrNull()

        /** The entry type when the line starts the way Pi writes entries, else null. */
        fun typeOf(line: ByteArray, length: Int): String? {
            val head = String(line, 0, minOf(length, 64), Charsets.ISO_8859_1)
            if (!head.startsWith(HEAD)) return null
            val end = head.indexOf('"', HEAD.length)
            if (end < 0) return null
            return head.substring(HEAD.length, end).takeIf { '\\' !in it }
        }

        fun roleOf(line: ByteArray, length: Int): String? {
            val head = String(line, 0, minOf(length, 256), Charsets.ISO_8859_1)
            val at = head.indexOf(ROLE)
            if (at < 0) return null
            val start = at + ROLE.length
            val end = head.indexOf('"', start)
            return if (end < 0) null else head.substring(start, end)
        }

        fun textOf(message: JsonObject): String = when (val content = message["content"]) {
            is JsonPrimitive -> content.content
            is JsonArray -> content.mapNotNull { block -> (block as? JsonObject)?.takeIf { it.str("type") == "text" }?.str("text") }.joinToString(" ")
            else -> ""
        }

        fun shorten(text: String): String {
            val flat = text.replace(Regex("\\s+"), " ").trim()
            return if (flat.length <= PREVIEW_CHARS) flat else flat.take(PREVIEW_CHARS - 1).trimEnd() + "…"
        }
    }
}
