package opensec.pibolt.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.bool
import opensec.pibolt.protocol.long
import opensec.pibolt.protocol.obj
import opensec.pibolt.protocol.str

/** What the app remembers about a project folder. The folder itself (and its files) belongs to the engine. */
data class ProjectMeta(
    /** Shown instead of the folder name. Renaming never moves the folder: Pi files its sessions by path. */
    val title: String? = null,
    val pinned: Boolean = false,
    val lastOpenedAt: Long = 0,
)

/** Everything in projects.json. */
data class ProjectsFile(
    val projects: Map<String, ProjectMeta> = emptyMap(),
    /** The first-run provider sheet was shown (and closed) once; Home then shows a quiet card instead. */
    val providerPromptSeen: Boolean = false,
    /** When each session file was last looked at here (path -> epoch ms): a session changed later is unread. */
    val seen: Map<String, Long> = emptyMap(),
)

/** JSON form of [ProjectsFile]. Damaged or unknown content falls back to defaults. */
object ProjectsCodec {
    fun encode(file: ProjectsFile): String = buildJsonObject {
        put("version", 1)
        put("providerPromptSeen", file.providerPromptSeen)
        put("seen", JsonObject(file.seen.mapValues { (_, at) -> JsonPrimitive(at) }))
        put(
            "projects",
            JsonObject(
                file.projects.mapValues { (_, m) ->
                    buildJsonObject {
                        m.title?.let { put("title", it) }
                        put("pinned", m.pinned)
                        put("lastOpenedAt", m.lastOpenedAt)
                    }
                },
            ),
        )
    }.toString()

    fun decode(text: String?): ProjectsFile {
        val json = text?.let { runCatching { ProtocolJson.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return ProjectsFile()
        val projects = json.obj("projects")?.mapNotNull { (name, value) ->
            val m = value as? JsonObject ?: return@mapNotNull null
            name to ProjectMeta(
                title = m.str("title")?.takeIf { it.isNotBlank() },
                pinned = m.bool("pinned") == true,
                lastOpenedAt = m.long("lastOpenedAt") ?: 0,
            )
        }?.toMap().orEmpty()
        val seen = json.obj("seen")?.mapNotNull { (path, at) -> (at as? JsonPrimitive)?.content?.toLongOrNull()?.let { path to it } }?.toMap().orEmpty()
        return ProjectsFile(projects, json.bool("providerPromptSeen") == true, seen)
    }
}

/**
 * Project metadata (pins, titles, last opened) in `projects.json`. Reads and writes run on one background thread;
 * [state] starts empty and fills when the file is read, so nothing here touches the disk on the main thread.
 */
class ProjectStore private constructor(context: Context) {
    private val _state = MutableStateFlow(ProjectsFile())
    val state: StateFlow<ProjectsFile> = _state.asStateFlow()

    private val _loaded = MutableStateFlow(false)

    /** True once the file was read; until then [state] is the default. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val file = BackgroundFile(context, "projects.json") { text ->
        val read = ProjectsCodec.decode(text)
        synchronized(this) { _state.value = merge(read, _state.value) }
        _loaded.value = true
    }

    fun meta(name: String): ProjectMeta = _state.value.projects[name] ?: ProjectMeta()

    fun setPinned(name: String, pinned: Boolean) = change(name) { it.copy(pinned = pinned) }

    fun setTitle(name: String, title: String?) = change(name) { it.copy(title = title?.trim()?.takeIf { t -> t.isNotEmpty() && t != name }) }

    fun opened(name: String, at: Long = System.currentTimeMillis()) = change(name) { it.copy(lastOpenedAt = at) }

    fun forget(name: String) = update { it.copy(projects = it.projects - name) }

    /** [sessionFile] was looked at [at]: it is read until it changes again. Keeps the newest [MAX_SEEN] entries. */
    fun seen(sessionFile: String, at: Long = System.currentTimeMillis()) = update { f ->
        val next = f.seen + (sessionFile to at)
        f.copy(seen = if (next.size <= MAX_SEEN) next else next.entries.sortedByDescending { it.value }.take(MAX_SEEN).associate { it.key to it.value })
    }

    fun providerPromptSeen() = update { it.copy(providerPromptSeen = true) }

    /** Developer sheet: show the first-run provider sheet again. */
    fun resetProviderPrompt() = update { it.copy(providerPromptSeen = false) }

    private fun change(name: String, edit: (ProjectMeta) -> ProjectMeta) = update { f ->
        f.copy(projects = f.projects + (name to edit(f.projects[name] ?: ProjectMeta())))
    }

    @Synchronized
    private fun update(edit: (ProjectsFile) -> ProjectsFile) {
        val next = edit(_state.value)
        if (next == _state.value) return
        _state.value = next
        file.write(ProjectsCodec.encode(next))
    }

    /** Edits made before the file was read win over what it held. */
    private fun merge(read: ProjectsFile, early: ProjectsFile): ProjectsFile =
        ProjectsFile(read.projects + early.projects, read.providerPromptSeen || early.providerPromptSeen, read.seen + early.seen)

    companion object {
        const val MAX_SEEN = 300

        @Volatile
        private var instance: ProjectStore? = null

        fun get(context: Context): ProjectStore = instance ?: synchronized(this) {
            instance ?: ProjectStore(context.applicationContext).also { instance = it }
        }
    }
}
