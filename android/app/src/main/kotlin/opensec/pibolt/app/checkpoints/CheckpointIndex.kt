package opensec.pibolt.app.checkpoints

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.arr
import opensec.pibolt.protocol.bool
import opensec.pibolt.protocol.int
import opensec.pibolt.protocol.long
import opensec.pibolt.protocol.str
import java.io.File

/**
 * A restore applied to the project: [paths] went back to an older state. [before] is the project just before (so the
 * restore can be undone), [after] just after. [redone] once that undo ran.
 */
data class UndoEntry(val at: Long, val before: String, val after: String, val paths: List<String>, val redone: Boolean = false)

/**
 * One turn: what the project was when the user message [prompt] arrived ([start]) and when the agent settled or the next
 * message came ([end], null while the turn runs). [userTimestamp] is the message's own timestamp, which ties the record
 * to the transcript row (also in a fork or clone, which copy messages with their timestamps).
 */
data class TurnRecord(
    val id: String,
    val sessionId: String?,
    val userTimestamp: Long?,
    val prompt: String,
    val startedAt: Long,
    val start: String,
    val endedAt: Long? = null,
    val end: String? = null,
    val files: Int = 0,
    val added: Int = 0,
    val removed: Int = 0,
    val undos: List<UndoEntry> = emptyList(),
) {
    val finished: Boolean get() = end != null
    val changed: Boolean get() = finished && files > 0

    /** The newest restore of this turn that can still be undone. */
    val lastUndo: UndoEntry? get() = undos.lastOrNull()?.takeIf { !it.redone }

    /** Every snapshot this record needs. */
    val snapshots: Set<String> get() = buildSet {
        add(start)
        end?.let(::add)
        for (u in undos) {
            add(u.before)
            add(u.after)
        }
    }
}

/** The turns of one project, newest last, in `turns.json` of its shadow repository. */
class CheckpointIndex(private val file: File) {
    fun load(): List<TurnRecord> = runCatching {
        if (!file.isFile) return emptyList()
        val root = ProtocolJson.parseToJsonElement(file.readText()) as? JsonObject ?: return emptyList()
        root.arr("turns").orEmpty().mapNotNull { (it as? JsonObject)?.let(::decode) }
    }.getOrDefault(emptyList())

    fun save(turns: List<TurnRecord>) {
        file.parentFile?.mkdirs()
        val json = buildJsonObject {
            put("version", 1)
            put("turns", JsonArray(turns.map(::encode)))
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    companion object {
        const val MAX_TURNS = 50
        const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000

        /**
         * The turns to keep: at most the newest [maxTurns], none older than [maxAgeMs]. A turn still running is always
         * kept (its end snapshot is on the way).
         */
        fun retain(turns: List<TurnRecord>, now: Long, maxTurns: Int = MAX_TURNS, maxAgeMs: Long = MAX_AGE_MS): List<TurnRecord> {
            val newest = turns.sortedByDescending { it.startedAt }.take(maxTurns).map { it.id }.toSet()
            return turns.filter { !it.finished || (it.id in newest && now - it.startedAt <= maxAgeMs) }
        }

        private fun encode(t: TurnRecord): JsonObject = buildJsonObject {
            put("id", t.id)
            t.sessionId?.let { put("sessionId", it) }
            t.userTimestamp?.let { put("userTimestamp", it) }
            put("prompt", t.prompt)
            put("startedAt", t.startedAt)
            put("start", t.start)
            t.endedAt?.let { put("endedAt", it) }
            t.end?.let { put("end", it) }
            put("files", t.files)
            put("added", t.added)
            put("removed", t.removed)
            put(
                "undos",
                JsonArray(
                    t.undos.map { u ->
                        buildJsonObject {
                            put("at", u.at)
                            put("before", u.before)
                            put("after", u.after)
                            put("paths", JsonArray(u.paths.map(::JsonPrimitive)))
                            if (u.redone) put("redone", true)
                        }
                    },
                ),
            )
        }

        private fun decode(o: JsonObject): TurnRecord? {
            val undos = o.arr("undos").orEmpty().mapNotNull { e ->
                val u = e as? JsonObject ?: return@mapNotNull null
                UndoEntry(
                    at = u.long("at") ?: 0,
                    before = u.str("before") ?: return@mapNotNull null,
                    after = u.str("after") ?: return@mapNotNull null,
                    paths = u.arr("paths").orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
                    redone = u.bool("redone") == true,
                )
            }
            return TurnRecord(
                id = o.str("id") ?: return null,
                sessionId = o.str("sessionId"),
                userTimestamp = o.long("userTimestamp"),
                prompt = o.str("prompt").orEmpty(),
                startedAt = o.long("startedAt") ?: 0,
                start = o.str("start") ?: return null,
                endedAt = o.long("endedAt"),
                end = o.str("end"),
                files = o.int("files") ?: 0,
                added = o.int("added") ?: 0,
                removed = o.int("removed") ?: 0,
                undos = undos,
            )
        }
    }
}
