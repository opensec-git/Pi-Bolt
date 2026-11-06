package opensec.pibolt.app.ui.projects

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import opensec.pibolt.app.data.ProjectStore
import opensec.pibolt.app.engine.EngineApi
import opensec.pibolt.app.engine.SessionSummary
import opensec.pibolt.app.session.SessionController
import java.io.File

/** Project folders on this phone. Every call touches the disk, so every call runs on [Dispatchers.IO]. */
class Projects(private val engine: EngineApi, private val store: ProjectStore) {
    suspend fun list(): List<ProjectEntry> = withContext(Dispatchers.IO) {
        val dirs = engine.projectsDir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty()
        val meta = store.state.value.projects
        ProjectRules.order(
            dirs.map { dir ->
                val sessions = runCatching { engine.sessions(dir) }.getOrDefault(emptyList())
                ProjectEntry(dir.name, dir.absolutePath, meta[dir.name] ?: store.meta(dir.name), sessions.firstOrNull(), sessions.size)
            },
        )
    }

    suspend fun names(): List<String> = withContext(Dispatchers.IO) {
        engine.projectsDir.listFiles { f -> f.isDirectory }.orEmpty().map { it.name }
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
        val sessionFiles = runCatching { engine.sessions(dir) }.getOrDefault(emptyList()).map { File(it.file) }
        if (dir.exists() && !dir.deleteRecursively()) return@withContext Result.failure(IllegalStateException("Some files could not be deleted"))
        for (f in sessionFiles) if (f.isFile) f.delete()
        store.forget(name)
        Result.success(Unit)
    }

    /** Files in the folder (not counting `.git`), for the delete confirmation. Stops counting at [cap]. */
    suspend fun fileCount(name: String, cap: Int = 10_000): Int = withContext(Dispatchers.IO) {
        File(engine.projectsDir, name).walkTopDown().onEnter { it.name != ".git" }.filter { it.isFile }.take(cap).count()
    }
}

/**
 * The on-device session the UI opened, with the project it belongs to. A process-wide holder (the engine runs one
 * session at a time): the project list reads it for its running indicator, the session screen for its controller.
 */
object LocalSessions {
    class Live(val project: File, val controller: SessionController, val sessionFile: String?) {
        /** A prompt to send once the session can take it ("Clone from git"). Read and cleared by the session screen. */
        @Volatile
        var firstPrompt: String? = null
    }

    private val _current = MutableStateFlow<Live?>(null)
    val current: StateFlow<Live?> = _current.asStateFlow()

    /** Opens (or resumes) a session through the engine, off the main thread, and makes it the live one. */
    suspend fun open(engine: EngineApi, store: ProjectStore, project: File, sessionFile: String? = null, fresh: Boolean = false, firstPrompt: String? = null): Result<Live> {
        val result = withContext(Dispatchers.IO) { runCatching { engine.open(project, sessionFile, fresh) } }
        return result.map { controller ->
            store.opened(project.name)
            Live(project, controller, sessionFile).also {
                it.firstPrompt = firstPrompt
                _current.value = it
            }
        }
    }

    fun isLive(project: String): Boolean = _current.value?.let { it.project.name == project && !it.controller.stopped } == true

    /** The project was deleted or renamed away: drop its session. */
    fun close(project: String) {
        val live = _current.value ?: return
        if (live.project.name == project) {
            live.controller.stop()
            _current.value = null
        }
    }
}
