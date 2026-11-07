package opensec.pibolt.app.terminal

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.engine.EngineManifest
import opensec.pibolt.app.engine.EnginePaths
import opensec.pibolt.app.engine.EnginePrefix
import opensec.pibolt.app.engine.EngineStatus
import opensec.pibolt.app.engine.ProcessReaper
import opensec.pibolt.app.engine.engineEnvironment
import opensec.pibolt.app.secrets.ProjectSecrets
import opensec.pibolt.app.secrets.SecretStore
import java.io.File
import java.lang.ref.WeakReference
import java.util.TimeZone

/** One terminal tab: a shell on a pseudo-terminal in a project. Its state is Compose state, read on the main thread. */
class TerminalTab internal constructor(val id: Int, val project: String, val session: TerminalSession) {
    var title by mutableStateOf("bash")
        internal set
    var running by mutableStateOf(true)
        internal set
    val label: String get() = "Terminal $id"
}

/**
 * The app's terminals: Termux's TerminalSession (a pty and a VT emulator) running the pack's bash in a project, with the
 * engine's environment plus the project's secrets. They belong to the process, not to a screen: rotation and leaving the
 * screen only detach the view; [TerminalService] keeps the process in the foreground while any shell runs. Closing a
 * tab kills the shell's whole process tree with the engine's [ProcessReaper].
 *
 * Main thread only (TerminalSession posts its output to the thread that created it).
 */
object Terminals {
    val tabs = mutableStateListOf<TerminalTab>()

    private var nextId = 1
    private val views = HashMap<TerminalSession, WeakReference<TerminalView>>()
    private val reaper by lazy { ProcessReaper() }
    private var manifestEnv: Map<String, String>? = null

    const val SCROLLBACK_ROWS = 5_000

    fun inProject(project: File): List<TerminalTab> = ProjectSecrets.keyOf(project).let { key -> tabs.filter { it.project == key } }

    val anyRunning: Boolean get() = tabs.any { it.running }

    /** Starts a shell in [project]. Waits for the engine prefix (bash, the CA bundle) when the app has just started. */
    suspend fun open(app: PiBoltApp, project: File): Result<TerminalTab> = runCatching {
        val launch = withContext(Dispatchers.IO) { launchFor(app, project) }
        val tab = TerminalTab(nextId++, ProjectSecrets.keyOf(project), TerminalSession(launch.shell, launch.cwd, launch.args, launch.env, SCROLLBACK_ROWS, client(app)))
        tabs += tab
        selected[tab.project] = tab.id
        TerminalService.update(app)
        tab
    }

    /** Ends [tab]: its shell and everything started from it. */
    fun close(app: Context, tab: TerminalTab) {
        val session = tab.session
        val pid = session.pid
        // The tree first (by parent pid, while the shell is still there to be the root), then the shell itself.
        if (session.isRunning && pid > 0) {
            Thread({
                runCatching { reaper.killTree(pid.toLong()) }
                session.finishIfRunning()
            }, "pibolt-terminal-kill").start()
        }
        tab.running = false
        views.remove(tab.session)
        tabs.remove(tab)
        TerminalService.update(app)
    }

    fun closeAll(app: Context) {
        for (tab in tabs.toList()) close(app, tab)
    }

    /** [view] shows [tab] now (a new view after rotation, or another tab picked). */
    fun attach(view: TerminalView, tab: TerminalTab) {
        views.entries.removeAll { it.value.get() === view || it.value.get() == null }
        views[tab.session] = WeakReference(view)
        view.attachSession(tab.session)
    }

    fun detach(view: TerminalView) {
        views.entries.removeAll { it.value.get() === view || it.value.get() == null }
    }

    /** The tab each project shows (the project tab and the full-screen terminal show the same one). */
    val selected = mutableStateMapOf<String, Int>()

    fun selectedIn(project: File): TerminalTab? {
        val here = inProject(project)
        return here.firstOrNull { it.id == selected[ProjectSecrets.keyOf(project)] } ?: here.lastOrNull()
    }

    /** Ports printed on the terminals' screens lately (dev servers announce their URL): hints for [DevServerPorts]. */
    @Volatile
    private var hintPorts: List<Int> = emptyList()
    private var hintsAt = 0L

    private fun refreshHints() {
        val now = SystemClock.uptimeMillis()
        if (now - hintsAt < HINT_EVERY_MS) return
        hintsAt = now
        hintPorts = tabs.flatMap { tab ->
            val emulator = tab.session.emulator ?: return@flatMap emptyList()
            DevServerPorts.portsMentioned(emulator.screen.getSelectedText(0, -minOf(emulator.screen.activeTranscriptRows, 100), emulator.mColumns, emulator.mRows))
        }.distinct()
    }

    private val portScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Dev-server ports now, with [extra] probed too (a port Pi asks the preview for). Blocking: call off the main thread. */
    @Synchronized
    fun scanPorts(extra: Collection<Int> = emptyList()): List<Int> {
        probeAlso = extra
        return try {
            previewScanner.scan()
        } finally {
            probeAlso = emptyList()
        }
    }

    @Volatile
    private var probeAlso: Collection<Int> = emptyList()

    /** One scanner for the preview tools, so a denied /proc table is read once (DevServerPorts remembers it). */
    private val previewScanner by lazy { DevServerPorts(mentionedPorts = { hintPorts + probeAlso }) }

    /** Dev-server ports, scanned while a chip shows them. */
    val ports: StateFlow<List<Int>> by lazy { DevServerPorts(mentionedPorts = { hintPorts + opensec.pibolt.app.engine.BackgroundJobs.hintPorts }).watch(portScope) }

    /** The theme's colours as the terminal palette: background, text, cursor and the 16 base colours. */
    fun applyColors(background: Int, foreground: Int, cursor: Int, base16: IntArray) {
        val scheme = TerminalColors.COLOR_SCHEME.mDefaultColors
        if (scheme[TextStyle.COLOR_INDEX_BACKGROUND] == background && scheme[TextStyle.COLOR_INDEX_FOREGROUND] == foreground &&
            scheme[TextStyle.COLOR_INDEX_CURSOR] == cursor && (0 until 16).all { scheme[it] == base16[it] }
        ) return
        scheme[TextStyle.COLOR_INDEX_BACKGROUND] = background
        scheme[TextStyle.COLOR_INDEX_FOREGROUND] = foreground
        scheme[TextStyle.COLOR_INDEX_CURSOR] = cursor
        for (i in 0 until 16) scheme[i] = base16[i]
        for (tab in tabs) tab.session.emulator?.mColors?.reset()
        for (view in views.values) view.get()?.invalidate()
    }

    private class Launch(val shell: String, val cwd: String, val args: Array<String>, val env: Array<String>)

    private suspend fun launchFor(app: PiBoltApp, project: File): Launch {
        val engine = app.engine
        engine.prepare()
        withTimeoutOrNull(PREPARE_TIMEOUT_MS) { engine.status.first { it is EngineStatus.Ready || it is EngineStatus.Unavailable || it is EngineStatus.Failed } }
        val paths = EnginePaths(EnginePaths.prefixFor(app.packageName), EnginePaths.dataFor(app.packageName), File(app.applicationInfo.nativeLibraryDir))
        val fromPack = manifestEnv ?: runCatching {
            app.assets.open(EnginePrefix.MANIFEST_ASSET).use { EngineManifest.parse(it.reader().readText()).env }
        }.getOrDefault(emptyMap()).also { manifestEnv = it }
        // As the project's engines get it: the GitHub credential helper too, so git push works in a shell.
        val base = engineEnvironment(paths, System.getenv(), TimeZone.getDefault().id, fromPack, app.github.gitEnvironment())
        val env = shellEnvironment(base, SecretStore.get(app).environment(project))
        val bash = File(paths.bin, "bash")
        val shell = if (bash.exists()) bash.path else EnginePrefix.SYSTEM_SH
        project.mkdirs()
        val cwd = ProjectSecrets.keyOf(project)
        return Launch(shell, cwd, arrayOf(if (bash.exists()) "-bash" else "-sh"), env.map { (k, v) -> "$k=$v" }.toTypedArray())
    }

    private fun tabOf(session: TerminalSession) = tabs.firstOrNull { it.session === session }

    private fun viewOf(session: TerminalSession) = views[session]?.get()

    private fun client(app: Context) = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            viewOf(changedSession)?.onScreenUpdated()
            refreshHints()
        }

        override fun onTitleChanged(changedSession: TerminalSession) {
            tabOf(changedSession)?.title = changedSession.title?.takeIf { it.isNotBlank() } ?: "bash"
        }

        override fun onSessionFinished(finishedSession: TerminalSession) {
            tabOf(finishedSession)?.running = false
            TerminalService.update(app)
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            if (text.isNullOrEmpty()) return
            val clipboard = app.getSystemService(ClipboardManager::class.java)
            val clip = ClipData.newPlainText("Terminal", text)
            // Keeps copied output out of the clipboard preview on Android 13+ when it may hold a secret.
            if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            clipboard.setPrimaryClip(clip)
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val clipboard = app.getSystemService(ClipboardManager::class.java)
            val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString()
            if (!text.isNullOrEmpty()) session?.emulator?.paste(text)
        }

        override fun onBell(session: TerminalSession) = Unit

        override fun onColorsChanged(session: TerminalSession) {
            viewOf(session)?.invalidate()
        }

        override fun onTerminalCursorStateChange(state: Boolean) = Unit

        override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

        override fun logError(tag: String?, message: String?) {
            Log.e(TAG, "$tag: $message")
        }

        override fun logWarn(tag: String?, message: String?) {
            Log.w(TAG, "$tag: $message")
        }

        override fun logInfo(tag: String?, message: String?) = Unit

        override fun logDebug(tag: String?, message: String?) = Unit

        override fun logVerbose(tag: String?, message: String?) = Unit

        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.w(TAG, "$tag: $message", e)
        }

        override fun logStackTrace(tag: String?, e: Exception?) {
            Log.w(TAG, tag, e)
        }
    }

    private const val TAG = "PiBoltTerminal"
    private const val PREPARE_TIMEOUT_MS = 60_000L
    private const val HINT_EVERY_MS = 2_000L
}
