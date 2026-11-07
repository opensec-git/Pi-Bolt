package opensec.pibolt.app.ui.projects

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.data.ProjectStore
import opensec.pibolt.app.data.SafeFiles
import opensec.pibolt.app.engine.EngineApi
import opensec.pibolt.app.engine.SessionSummary
import opensec.pibolt.app.session.SessionController
import java.io.File

/** Project folders on this phone. Every call touches the disk, so every call runs on [Dispatchers.IO]. */
class Projects(private val engine: EngineApi, private val store: ProjectStore) {
    suspend fun list(): List<ProjectEntry> = withContext(Dispatchers.IO) {
        val dirs = engine.projectsDir.listFiles { f -> SafeFiles.isRealDirectory(f) && !f.name.startsWith(".") }.orEmpty()
        val meta = store.state.value.projects
        ProjectRules.order(
            dirs.map { dir ->
                val sessions = runCatching { engine.sessions(dir) }.getOrDefault(emptyList())
                ProjectEntry(dir.name, dir.absolutePath, meta[dir.name] ?: store.meta(dir.name), sessions.firstOrNull(), sessions.size)
            },
        )
    }

    suspend fun names(): List<String> = withContext(Dispatchers.IO) {
        engine.projectsDir.listFiles().orEmpty().map { it.name }
    }

    suspend fun sessions(project: File): List<SessionSummary> = withContext(Dispatchers.IO) {
        runCatching { engine.sessions(project) }.getOrDefault(emptyList())
    }

    /** A new empty folder; fails with a message the UI shows as is. */
    suspend fun create(name: String): Result<File> = withContext(Dispatchers.IO) {
        val n = name.trim()
        val root = engine.projectsDir
        ProjectRules.nameError(n, root.list()?.toList().orEmpty())?.let { return@withContext Result.failure(IllegalArgumentException(it)) }
        val dir = File(root, n)
        if (!root.exists()) root.mkdirs()
        if (dir.mkdir()) Result.success(dir) else Result.failure(IllegalStateException("Could not create the folder"))
    }

    /** Deletes the folder with everything in it, and the project's saved sessions. */
    suspend fun delete(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        val dir = File(engine.projectsDir, name)
        // Sessions first: their folder is found from the project's real path, which needs the project to exist.
        runCatching { engine.deleteSessions(dir) }
        if (!SafeFiles.deleteTree(dir)) return@withContext Result.failure(IllegalStateException("Some files could not be deleted"))
        store.forget(name)
        Result.success(Unit)
    }

    /** Files in the folder (not counting `.git`), for the delete confirmation. Stops counting at [cap]. */
    suspend fun fileCount(name: String, cap: Int = 10_000): Int = withContext(Dispatchers.IO) {
        SafeFiles.countFiles(File(engine.projectsDir, name), cap, skipDir = ".git")
    }
}

/**
 * The on-device sessions the UI opened, by project (several run at once, one per project): Home's "Running" section and
 * the project list read them, each project's session screen its own controller. [current] is the one opened or shown
 * last. A process-wide holder kept in step with [PiBoltApp.sessions] ([follow]).
 */
object LocalSessions {
    class Live(val project: File, val controller: SessionController, val sessionFile: String?) {
        /** A prompt to send once the session can take it ("Clone from git"). Read and cleared by the session screen. */
        @Volatile
        var firstPrompt: String? = null

        /**
         * Text and images for the composer ("Ask Pi" in the editor, content shared from another app), waiting for the
         * session screen, which takes them as soon as its draft is loaded (also while it is already open).
         */
        val composerDrop = MutableStateFlow<ComposerDrop?>(null)

        /** Adds to what waits for the composer: text on a new line after earlier text, images after earlier ones. */
        fun drop(text: String? = null, images: List<Uri> = emptyList()) {
            composerDrop.update { old ->
                val joined = listOfNotNull(old?.text, text).takeIf { it.isNotEmpty() }?.joinToString("\n")
                ComposerDrop(joined, old?.images.orEmpty() + images)
            }
        }
    }

    /** What [Live.drop] queued for the composer. */
    class ComposerDrop(val text: String?, val images: List<Uri>)

    private val _current = MutableStateFlow<Live?>(null)
    val current: StateFlow<Live?> = _current.asStateFlow()

    private val _all = MutableStateFlow<Map<String, Live>>(emptyMap())

    /** Live sessions by project name, in the order they were opened. */
    val all: StateFlow<Map<String, Live>> = _all.asStateFlow()

    /** The live session of [project], if any. */
    fun get(project: String): Live? = _all.value[project]?.takeIf { !it.controller.stopped }

    /** Keeps [all] and [current] in step with the app's live sessions (one that stops leaves). Call once. */
    fun follow(app: PiBoltApp) {
        app.appScope.launch {
            combine(app.sessions, app.session) { list, focused -> list to focused }.collect { (list, focused) ->
                synchronized(this@LocalSessions) {
                    _all.value = _all.value.filterValues { live -> list.any { it === live.controller } }
                    val shown = _all.value.values.firstOrNull { it.controller === focused }
                    _current.value = shown ?: _current.value?.takeIf { live -> list.any { it === live.controller } }
                }
            }
        }
    }

    /** Opens (or resumes) a session through the engine, off the main thread, and makes it the live one. */
    suspend fun open(engine: EngineApi, store: ProjectStore, project: File, sessionFile: String? = null, fresh: Boolean = false, firstPrompt: String? = null): Result<Live> {
        val result = withContext(Dispatchers.IO) { runCatching { engine.open(project, sessionFile, fresh) } }
        return result.map { controller ->
            store.opened(project.name)
            sessionFile?.let { store.seen(it) }
            Live(project, controller, sessionFile).also {
                it.firstPrompt = firstPrompt
                synchronized(this) {
                    _all.value = (_all.value - project.name) + (project.name to it)
                    _current.value = it
                }
            }
        }
    }

    fun isLive(project: String): Boolean = get(project) != null

    /** The project was deleted or renamed away: drop its session. */
    fun close(project: String) {
        val live = _all.value[project] ?: return
        live.controller.stop()
        synchronized(this) {
            _all.value = _all.value - project
            if (_current.value === live) _current.value = null
        }
    }
}
