package opensec.pibolt.app.ui.projects

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.data.ProjectStore
import opensec.pibolt.app.data.SafeFiles
import opensec.pibolt.app.engine.EngineApi
import opensec.pibolt.app.engine.SessionSummary
import opensec.pibolt.app.git.GitProgress
import opensec.pibolt.app.git.GitRepo
import opensec.pibolt.app.git.GitRunner
import opensec.pibolt.app.secrets.ProjectSecrets
import opensec.pibolt.app.session.LinkState
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.protocol.rpc.StreamingBehavior
import java.util.concurrent.atomic.AtomicReference
import java.io.File

/** Project folders on this phone. Every call touches the disk, so every call runs on [Dispatchers.IO]. */
class Projects(private val engine: EngineApi, private val store: ProjectStore, private val secrets: ProjectSecrets? = null) {
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
        // Secrets are kept by the project's path: a new project of the same name must not inherit them.
        runCatching { secrets?.forget(dir) }
        Result.success(Unit)
    }

    /** Files in the folder (not counting `.git`), for the delete confirmation. Stops counting at [cap]. */
    suspend fun fileCount(name: String, cap: Int = 10_000): Int = withContext(Dispatchers.IO) {
        SafeFiles.countFiles(File(engine.projectsDir, name), cap, skipDir = ".git")
    }
}

/** Cloning into a new project folder. */
object ProjectClone {
    /**
     * Clones [url] into the new, empty [dir]. When the clone fails, or is cancelled (the sheet swiped away or the
     * screen rotated while it ran), the half-cloned folder is removed: never a broken project left in the list.
     */
    suspend fun run(runner: GitRunner, url: String, dir: File, progress: (GitProgress) -> Unit) {
        try {
            GitRepo.clone(runner, url, dir, progress)
        } catch (e: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { SafeFiles.deleteTree(dir) }
            throw e
        }
    }
}

/**
 * The on-device sessions the UI opened (several run at once): Home's "Running" section and the project list read them,
 * each session screen its own controller. Keyed by [Live.key], the session screen's route: a project's name for its
 * front session (the one opened last), `name#2`, `name#3`... for others of the same project still live beside it (one
 * was busy when another opened: LivePolicy.planOpen). [current] is the one opened or shown last. A process-wide holder
 * kept in step with [PiBoltApp.sessions] ([follow]).
 */
object LocalSessions {
    class Live(val project: File, val controller: SessionController, val sessionFile: String?, key: String = project.name) {
        /** Where [all] holds it and the route of its screen: the project's name for its front session, else `name#n`. */
        @Volatile
        var key: String = key
            internal set

        private val first = AtomicReference<String?>(null)

        /** Session screens showing this session now: while one does, it sends the first prompt itself. */
        val screens = MutableStateFlow(0)

        /** The prompt waiting for the session to take it ([queueFirstPrompt]), once: null after that. */
        fun takeFirstPrompt(): String? = first.getAndSet(null)

        /**
         * A prompt to send once the session can take it (the Home composer, "Clone from git"). Its session screen sends
         * it when the session connects; when the screen has been left before that (the user went back), it is sent
         * from here, so it does not wait for the screen to open again. [kept] is true while the app still holds this
         * session; once it is not (closed, replaced), the prompt is dropped.
         */
        fun queueFirstPrompt(text: String?, kept: Flow<Boolean>) {
            text ?: return
            first.set(text)
            deliveries.launch {
                // The screen opened for this session sends it (with its "Sending" row and the notification prompt);
                // this only takes over once that screen has shown and gone.
                combine(screens, kept) { shown, held -> shown > 0 || !held }.first { it }
                // True: send now; false: the session is gone. Null: keep waiting.
                val send = combine(controller.link, controller.state, screens, kept) { link, state, shown, held ->
                    when {
                        !held || controller.stopped -> false
                        link is LinkState.Connected && state.hello && shown == 0 -> true
                        else -> null
                    }
                }.filterNotNull().first()
                if (send) takeFirstPrompt()?.let { controller.send(it, StreamingBehavior.Steer) }
            }
        }

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

    /** Sends first prompts no screen took ([Live.queueFirstPrompt]). */
    private val deliveries = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _all = MutableStateFlow<Map<String, Live>>(emptyMap())

    /** Live sessions by [Live.key], in the order they were opened. */
    val all: StateFlow<Map<String, Live>> = _all.asStateFlow()

    /** The front live session of [project] (the one opened there last), if any. */
    fun get(project: String): Live? = _all.value[project]?.takeIf { !it.controller.stopped }

    /** The session screen's session: [key] is [Live.key]. */
    fun byKey(key: String): Live? = _all.value[key]?.takeIf { !it.controller.stopped }

    /** Every live session of [project], the front one first. */
    fun inProject(project: String): List<Live> =
        _all.value.values.filter { it.project.name == project && !it.controller.stopped }.sortedBy { if (it.key == project) 0 else 1 }

    /** The live session of [project] that has [sessionFile] open, if any. */
    fun find(project: String, sessionFile: String): Live? = inProject(project).firstOrNull { (it.controller.sessionFile ?: it.sessionFile) == sessionFile }

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

    /**
     * Opens (or resumes) a session through the engine, off the main thread, and makes it the current one. A session
     * already live keeps its key; a new one becomes its project's front session, and the one there before moves aside
     * (to `name#n`) when it still runs ([place]). Show the result's [Live.key].
     */
    suspend fun open(engine: EngineApi, store: ProjectStore, project: File, sessionFile: String? = null, fresh: Boolean = false, firstPrompt: String? = null): Result<Live> {
        val result = withContext(Dispatchers.IO) { runCatching { engine.open(project, sessionFile, fresh) } }
        return result.map { controller ->
            store.opened(project.name)
            sessionFile?.let { store.seen(it) }
            synchronized(this) {
                val keys = _all.value.mapValues { (_, live) -> Placed(live.project.name, live.controller.stopped) }
                val existing = _all.value.entries.firstOrNull { it.value.controller === controller }?.key
                val (key, moved) = place(keys, project.name, existing)
                val live = Live(project, controller, sessionFile, key)
                var next = _all.value
                if (moved != null) {
                    val aside = next.getValue(key)
                    aside.key = moved
                    next = next - key + (moved to aside)
                }
                _all.value = next - key + (key to live)
                _current.value = live
                live
            }.also { live -> live.queueFirstPrompt(firstPrompt, all.map { m -> m.values.any { it === live } }) }
        }
    }

    /** A live session's place in [all] as [place] sees it. */
    data class Placed(val project: String, val stopped: Boolean)

    /**
     * Where an opened session goes in [all] ([keys]: what is there now): its [existing] key if it is already live, else
     * the [project]'s name, and the live session there moves to the first free `name#n` (the second value) unless it
     * has stopped (it was replaced). Pure.
     */
    fun place(keys: Map<String, Placed>, project: String, existing: String?): Pair<String, String?> {
        if (existing != null) return existing to null
        val front = keys[project]
        if (front == null || front.stopped) return project to null
        val free = generateSequence(2) { it + 1 }.map { "$project#$it" }.first { k -> keys[k]?.stopped != false }
        return project to free
    }

    fun isLive(project: String): Boolean = inProject(project).isNotEmpty()

    /** The project was deleted or renamed away: drop its sessions. */
    fun close(project: String) {
        val lives = inProject(project)
        for (live in lives) live.controller.stop()
        synchronized(this) {
            _all.value = _all.value.filterValues { it !in lives }
            if (_current.value in lives) _current.value = null
        }
    }
}
