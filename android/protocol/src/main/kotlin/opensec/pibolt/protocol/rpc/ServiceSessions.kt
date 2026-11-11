package opensec.pibolt.protocol.rpc

import kotlinx.serialization.json.JsonObject
import opensec.pibolt.protocol.arr
import opensec.pibolt.protocol.bool
import opensec.pibolt.protocol.long
import opensec.pibolt.protocol.str
import java.time.Instant

/** A folder with Pi-Bolt sessions on the computer, from the background service's `list_projects`. */
data class RemoteProject(val cwd: String, val name: String, val sessions: Int, val updatedAt: String) {
    val updatedAtMs: Long get() = isoMillis(updatedAt)

    companion object {
        /** `data.projects`, newest first as sent. Entries without a `cwd` are dropped. */
        fun listFrom(response: RpcResponse): List<RemoteProject> =
            response.dataObject?.arr("projects").orEmpty().mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val cwd = o.str("cwd")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                RemoteProject(
                    cwd = cwd,
                    name = o.str("name")?.takeIf { it.isNotBlank() } ?: cwd.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\'),
                    sessions = o.long("sessions")?.toInt() ?: 0,
                    updatedAt = o.str("updatedAt").orEmpty(),
                )
            }
    }
}

/**
 * A saved session on the computer, from `list_sessions`. [path] is what `switch_session` takes. A service with
 * `host.parallelSessions` says which sessions have a runtime open ([open]) and which of those work now ([running]).
 */
data class RemoteSessionInfo(
    val path: String,
    val id: String,
    val cwd: String,
    val name: String?,
    val preview: String,
    val updatedAt: String,
    val messageCount: Int,
    val running: Boolean = false,
    val open: Boolean = false,
) {
    /** What a list shows: the session's name, else its first message, else its id. */
    val title: String get() = name?.takeIf { it.isNotBlank() } ?: preview.takeIf { it.isNotBlank() } ?: id

    val updatedAtMs: Long get() = isoMillis(updatedAt)

    companion object {
        /** `data.sessions`, newest first as sent. Entries without a `path` are dropped. */
        fun listFrom(response: RpcResponse): List<RemoteSessionInfo> =
            response.dataObject?.arr("sessions").orEmpty().mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val path = o.str("path")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                RemoteSessionInfo(
                    path = path,
                    id = o.str("id").orEmpty(),
                    cwd = o.str("cwd").orEmpty(),
                    name = o.str("name"),
                    preview = o.str("preview").orEmpty(),
                    updatedAt = o.str("updatedAt").orEmpty(),
                    messageCount = o.long("messageCount")?.toInt() ?: 0,
                    running = o.bool("running") == true,
                    open = o.bool("open") == true,
                )
            }
    }
}

/** An ISO-8601 instant (`Date.toISOString()`) in Unix milliseconds; 0 when it is missing or unreadable. */
internal fun isoMillis(text: String): Long = try {
    Instant.parse(text).toEpochMilli()
} catch (_: Exception) {
    0
}
