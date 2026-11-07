package opensec.pibolt.app.session

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.str
import java.io.File

/**
 * The turns running on this phone, on disk ([file]) so that the next process can tell which ones Android ended.
 *
 * Problem: when Android kills the app during a turn (low memory, the vendor's battery saver, an update), the engines die
 * with it. The next launch showed nothing about it: the session reopened with the prompt and no answer, as if the
 * agent had silently stopped. A turn is marked when it starts and unmarked when it ends or its session is stopped, so
 * the marks a new process finds are the turns that were cut off ([takeAll]), and the app says so.
 */
class RunMarks(private val file: File) {
    /** A turn that was running: [key] is its session's hostId, [project] the project's name (null for a chat). */
    data class Run(val key: String, val project: String?, val host: String, val sessionFile: String?, val chat: Boolean)

    @Synchronized
    fun mark(run: Run) {
        val all = read()
        if (all[run.key] == run) return
        write(all + (run.key to run))
    }

    @Synchronized
    fun unmark(key: String) {
        val all = read()
        if (key !in all) return
        write(all - key)
    }

    /** The marked turns, and no marks left after: call once per process, before any session starts. */
    @Synchronized
    fun takeAll(): List<Run> {
        val all = read()
        if (file.exists()) file.delete()
        return all.values.toList()
    }

    private fun read(): Map<String, Run> = runCatching {
        if (!file.isFile) return emptyMap()
        val root = ProtocolJson.parseToJsonElement(file.readText()) as? JsonObject ?: return emptyMap()
        root.mapNotNull { (key, value) ->
            val o = value as? JsonObject ?: return@mapNotNull null
            key to Run(key, o.str("project"), o.str("host").orEmpty(), o.str("sessionFile"), (o["chat"] as? JsonPrimitive)?.boolean == true)
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun write(all: Map<String, Run>) {
        runCatching {
            if (all.isEmpty()) {
                file.delete()
                return
            }
            val json = JsonObject(
                all.mapValues { (_, r) ->
                    buildJsonObject {
                        r.project?.let { put("project", it) }
                        put("host", r.host)
                        r.sessionFile?.let { put("sessionFile", it) }
                        put("chat", r.chat)
                    }
                },
            )
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.toString())
            if (!temp.renameTo(file)) temp.delete()
        }
    }
}
