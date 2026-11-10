package opensec.pibolt.app.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.obj
import opensec.pibolt.protocol.session.SessionState
import opensec.pibolt.protocol.session.Subagent
import opensec.pibolt.protocol.session.SubagentReducer
import opensec.pibolt.protocol.str
import java.io.File
import java.io.RandomAccessFile

/**
 * Follows the session files of subagents running on this phone and feeds their messages to the reducer
 * ([SubagentReducer.transcript]). The subagents extension runs each agent in its own Pi session, saved like any other
 * (`<agentDir>/sessions/--<cwd>--/<time>_<id>.jsonl`, written from its prompt on, one line per finished message). Its
 * header names the parent session (`parentSession`) and its name is "<type>#<first 8 characters of the agent id>".
 *
 * A run is matched to its file by that id once known (background runs at once, foreground runs when they end), else by
 * its prompt (the file's first user message ends with it; an inherited conversation comes before). Running runs are
 * read every [ACTIVE_MS]; a finished one once more, then only while its detail screen is open ([watch]). With
 * nothing to read it does not poll: it waits for the session state or [watch] to change. Everything
 * runs on [Dispatchers.IO]; only complete lines are parsed, so a line being written is read on the next pass.
 */
class SubagentTail(
    private val state: StateFlow<SessionState>,
    /** The parent session's file: its folder holds the subagents' files, and their headers name it. */
    private val parentFile: () -> String?,
    private val emit: (JsonObject) -> Unit,
) {
    private val wanted = MutableStateFlow<Set<String>>(emptySet())

    private class Header(val parent: String?, val name: String?, val firstUser: String?)

    private class Binding(val file: File) {
        var offset = 0L
        var partial = ByteArray(0)
        var finished = false
    }

    private val headers = HashMap<String, Header?>()
    private val bindings = HashMap<String, Binding>()
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val busy = try {
                    pass()
                } catch (e: Exception) {
                    false
                }
                // Nothing to follow: no polling (an idle session must not wake the phone). Sleep until a run starts,
                // a detail screen opens or a snapshot brings runs to read.
                if (!busy) combine(state, wanted) { s, want -> hasWork(s, want) }.first { it }
                delay(ACTIVE_MS)
            }
        }
    }

    /** True when [pass] has something to read: a running or watched run, or a finished one not yet read to its end. */
    internal fun hasWork(s: SessionState = state.value, want: Set<String> = wanted.value): Boolean =
        s.subagents.values.any { run -> run.isActive || run.key in want || bindings[run.key]?.finished == false }

    /** Read run [key]'s file while [on] (its detail screen is open), also after it finished. */
    fun watch(key: String, on: Boolean) {
        wanted.update { if (on) it + key else it - key }
    }

    /** One pass over the runs to follow. True while one is running or watched: poll again soon. */
    internal fun pass(): Boolean {
        val s = state.value
        bindings.keys.retainAll(s.subagents.keys)
        val want = wanted.value
        val targets = s.subagents.values.filter { run -> run.isActive || run.key in want || bindings[run.key]?.finished == false }
        if (targets.isEmpty()) return false
        val parent = parentFile() ?: return false
        val dir = File(parent).parentFile ?: return false
        var files: List<File>? = null
        for (run in targets) {
            var binding = bindings[run.key]
            if (binding == null) {
                val candidates = files ?: dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") && f.path != parent }.orEmpty()
                    .sortedByDescending { it.lastModified() }
                    .also { files = it }
                binding = bind(run, candidates, parent) ?: continue
                bindings[run.key] = binding
            }
            // The state lost what was read (another snapshot): read the file again from the start.
            if (binding.offset > 0 && run.transcript?.file != binding.file.path) {
                binding.offset = 0
                binding.partial = ByteArray(0)
            }
            read(run, binding)
            if (!run.isActive) binding.finished = true
        }
        return targets.any { it.isActive || it.key in want }
    }

    private fun bind(run: Subagent, files: List<File>, parent: String): Binding? {
        val agentId = run.agentId
        val taken = bindings.values.mapTo(HashSet()) { it.file.path }
        for (file in files) {
            val header = header(file) ?: continue
            if (header.parent != parent) continue
            if (agentId != null) {
                if (header.name?.endsWith("#" + agentId.take(8)) == true) return Binding(file)
                continue
            }
            val prompt = run.prompt.trim()
            if (prompt.isEmpty() || file.path in taken) continue
            if (header.firstUser?.trimEnd()?.endsWith(prompt) == true) return Binding(file)
        }
        return null
    }

    /** The header line, the session name and the first prompt of [file]; cached (they never change). */
    private fun header(file: File): Header? {
        headers[file.path]?.let { return it }
        if (file.path in headers) return null
        var parent: String? = null
        var name: String? = null
        var firstUser: String? = null
        var ok = false
        runCatching {
            file.bufferedReader().use { reader ->
                var lines = 0
                while (lines < HEADER_LINES) {
                    val line = reader.readLine() ?: break
                    lines++
                    val entry = runCatching { ProtocolJson.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
                    when (entry.str("type")) {
                        "session" -> {
                            parent = entry.str("parentSession")
                            ok = true
                        }
                        "session_info" -> name = entry.str("name") ?: name
                        "message" -> {
                            val message = entry.obj("message")
                            if (message?.str("role") == "user") {
                                firstUser = textOf(message)
                                break
                            }
                        }
                    }
                }
            }
        }
        val header = if (ok) Header(parent, name, firstUser) else null
        // A file without its first prompt yet is read again on a later pass.
        if (header == null || firstUser != null) headers[file.path] = header
        return header
    }

    private fun read(run: Subagent, binding: Binding) {
        val length = binding.file.length()
        if (length < binding.offset) {
            binding.offset = 0
            binding.partial = ByteArray(0)
        }
        val reset = binding.offset == 0L
        if (length == binding.offset && !reset) return
        val bytes = RandomAccessFile(binding.file, "r").use { raf ->
            val count = (length - binding.offset).coerceAtMost(MAX_READ.toLong()).toInt()
            val buffer = ByteArray(count)
            raf.seek(binding.offset)
            raf.readFully(buffer)
            buffer
        }
        binding.offset += bytes.size
        val data = if (binding.partial.isEmpty()) bytes else binding.partial + bytes
        val end = data.lastIndexOf('\n'.code.toByte())
        binding.partial = if (end < 0) data else data.copyOfRange(end + 1, data.size)
        val messages = ArrayList<JsonObject>()
        if (end >= 0) {
            var start = 0
            for (i in 0..end) {
                if (data[i] != '\n'.code.toByte()) continue
                if (i > start) messageOf(String(data, start, i - start, Charsets.UTF_8))?.let(messages::add)
                start = i + 1
            }
        }
        if (messages.isEmpty() && !reset) return
        emit(
            JsonObject(
                mapOf(
                    "type" to JsonPrimitive(SubagentReducer.TRANSCRIPT_EVENT),
                    "key" to JsonPrimitive(run.key),
                    "file" to JsonPrimitive(binding.file.path),
                    "reset" to JsonPrimitive(reset),
                    "messages" to JsonArray(messages),
                ),
            ),
        )
    }

    private companion object {
        const val ACTIVE_MS = 400L
        const val HEADER_LINES = 40

        /** Read at most this much per pass, so a long saved transcript arrives in steps. */
        const val MAX_READ = 4 * 1024 * 1024

        /** A transcript message from a session line: a message entry, or a compaction as its summary. */
        fun messageOf(line: String): JsonObject? {
            val entry = runCatching { ProtocolJson.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return null
            return when (entry.str("type")) {
                "message" -> entry.obj("message")
                "compaction" -> JsonObject(
                    mapOf(
                        "role" to JsonPrimitive("compactionSummary"),
                        "summary" to (entry["summary"] ?: JsonPrimitive("")),
                        "tokensBefore" to (entry["tokensBefore"] ?: JsonPrimitive(0)),
                    ),
                )
                else -> null
            }
        }

        fun textOf(message: JsonObject): String = when (val content = message["content"]) {
            is JsonPrimitive -> content.content
            is JsonArray -> content.mapNotNull { (it as? JsonObject)?.takeIf { b -> b.str("type") == "text" }?.str("text") }.joinToString("\n")
            else -> ""
        }
    }
}
