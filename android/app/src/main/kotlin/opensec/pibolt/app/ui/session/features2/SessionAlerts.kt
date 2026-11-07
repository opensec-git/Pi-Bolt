package opensec.pibolt.app.ui.session.features2

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import opensec.pibolt.app.MainActivity
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.R
import opensec.pibolt.app.data.ProjectStore
import opensec.pibolt.app.engine.ChatSpace
import opensec.pibolt.app.ui.chats.Chats
import opensec.pibolt.app.ui.projects.LocalSessions
import opensec.pibolt.protocol.session.SubagentStatus
import java.io.File

/**
 * Notifications that open the exact session they are about. A notification's intent carries the live session's
 * project and session file (or "remote"); [take] reads them when the activity gets the intent, and [Navigate]
 * opens that session and its screen. Also: a notification when a background agent finishes while the app is away.
 */
object SessionAlerts {
    const val EXTRA_PROJECT = "opensec.pibolt.session.project"
    const val EXTRA_SESSION = "opensec.pibolt.session.file"
    const val EXTRA_REMOTE = "opensec.pibolt.session.remote"

    /** The live plain chat (ui/chats). */
    const val EXTRA_CHAT = "opensec.pibolt.session.chat"
    private const val AGENT_ID = 4

    /** Must match Notifications' alerts channel (SessionService.kt). */
    private const val CHANNEL_ALERTS = "alerts"

    /** Where a tapped notification leads. */
    sealed interface Target {
        data class Local(val project: String, val sessionFile: String?) : Target

        /** A plain chat: [sessionFile], or the live chat when null. */
        data class Chat(val sessionFile: String?) : Target

        data object Remote : Target
    }

    private val _pending = MutableStateFlow<Target?>(null)
    val pending: StateFlow<Target?> = _pending.asStateFlow()

    /** The intent a session notification opens: the app, at the live session. */
    fun openIntent(context: Context): Intent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val live = (context.applicationContext as? PiBoltApp)?.session?.value ?: return intent
        if (live.isChat) {
            intent.putExtra(EXTRA_CHAT, true)
            live.sessionFile?.let { intent.putExtra(EXTRA_SESSION, it) }
        } else if (live.isLocal) {
            live.project?.let { intent.putExtra(EXTRA_PROJECT, it.name) }
            live.sessionFile?.let { intent.putExtra(EXTRA_SESSION, it) }
        } else {
            intent.putExtra(EXTRA_REMOTE, true)
        }
        return intent
    }

    /** Reads a notification's target from [intent] (MainActivity's onCreate and onNewIntent). */
    fun take(intent: Intent?) {
        intent ?: return
        val project = intent.getStringExtra(EXTRA_PROJECT)
        _pending.value = when {
            intent.getBooleanExtra(EXTRA_CHAT, false) -> Target.Chat(intent.getStringExtra(EXTRA_SESSION))
            project != null -> Target.Local(project, intent.getStringExtra(EXTRA_SESSION))
            intent.getBooleanExtra(EXTRA_REMOTE, false) -> Target.Remote
            else -> return
        }
        intent.removeExtra(EXTRA_PROJECT)
        intent.removeExtra(EXTRA_SESSION)
        intent.removeExtra(EXTRA_REMOTE)
        intent.removeExtra(EXTRA_CHAT)
    }

    /**
     * Opens the pending target: makes its session the live one (resuming the file if another session is open) and
     * shows it through [onLocal] (the project name), [onRemote] or [onChat]. The target is consumed only after it is
     * handled, so leaving composition mid-open (the activity recreated while the session starts) handles it again next
     * time.
     */
    @Composable
    fun Navigate(app: PiBoltApp, onLocal: (String) -> Unit, onRemote: () -> Unit, onChat: () -> Unit = {}) {
        LaunchedEffect(Unit) {
            drain(_pending) { asked ->
                val roots = listOf(File(app.engine.agentDir, "sessions"), ChatSpace.sessionDir(app.engine.agentDir))
                val t = withContext(Dispatchers.IO) { safe(asked, app.engine.projectsDir, roots) } ?: return@drain
                when (t) {
                    Target.Remote -> if (app.remote.value?.stopped == false) onRemote()
                    is Target.Chat -> {
                        val live = Chats.current.value?.takeIf { !it.controller.stopped }
                        val file = t.sessionFile?.takeIf { File(it).isFile }
                        when {
                            live != null && (file == null || live.controller.sessionFile == file) -> onChat()
                            file != null -> Chats.resume(app.engine, file).onSuccess { onChat() }
                        }
                    }
                    is Target.Local -> {
                        // Several sessions run at once, also in one project: the live session the alert is about.
                        val live = t.sessionFile?.let { LocalSessions.find(t.project, it) } ?: LocalSessions.get(t.project)?.takeIf { t.sessionFile == null }
                        if (live != null) {
                            onLocal(live.key)
                            return@drain
                        }
                        val dir = File(app.engine.projectsDir, t.project)
                        if (!dir.isDirectory) return@drain
                        LocalSessions.open(app.engine, ProjectStore.get(app), dir, sessionFile = t.sessionFile?.takeIf { File(it).isFile })
                            .onSuccess { onLocal(it.key) }
                    }
                }
            }
        }
    }

    /**
     * [t] when it names a session of this app, else null. MainActivity is exported, so any app can start it with these
     * extras: a project is a plain folder name directly in [projectsDir] (not `..`, which would start an engine with every
     * tool in HOME), and a session file lies under one of [sessionRoots] (else it is dropped, and the project's own
     * session opens).
     */
    internal fun safe(t: Target, projectsDir: File, sessionRoots: List<File>): Target? {
        fun ours(path: String?): String? = path?.takeIf { p ->
            val real = runCatching { File(p).canonicalPath }.getOrNull() ?: return@takeIf false
            sessionRoots.any { root -> runCatching { real.startsWith(root.canonicalPath + File.separator) }.getOrDefault(false) }
        }
        return when (t) {
            Target.Remote -> t
            is Target.Chat -> Target.Chat(ours(t.sessionFile))
            is Target.Local -> {
                val name = t.project
                val plain = name.isNotBlank() && !name.startsWith(".") && name.none { it == '/' || it == '\\' || it.isISOControl() }
                val inProjects = plain && runCatching { File(projectsDir, name).canonicalFile.parentFile == projectsDir.canonicalFile }.getOrDefault(false)
                if (inProjects) Target.Local(name, ours(t.sessionFile)) else null
            }
        }
    }

    /**
     * Handles each target [pending] holds with [handle], then clears it unless a newer target replaced it meanwhile.
     * Clearing first would restart a collector keyed on the value and cancel [handle] halfway.
     */
    internal suspend fun drain(pending: MutableStateFlow<Target?>, handle: suspend (Target) -> Unit) {
        pending.filterNotNull().collect { t ->
            handle(t)
            pending.compareAndSet(t, null)
        }
    }

    /** Watches every live session for background agents that finish while no screen is visible. Call once. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun watchBackgroundAgents(app: PiBoltApp) {
        app.appScope.launch {
            var known = emptyMap<String, SubagentStatus>()
            app.sessions.flatMapLatest { list ->
                if (list.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    // Keys carry the session (hostId), so runs of two sessions never mix.
                    combine(
                        list.map { c ->
                            c.state.map { s -> s.subagents.values.filter { it.background }.associate { c.hostId + "/" + it.key to (it.status to (it.description.ifBlank { it.type })) } }
                                .distinctUntilChanged()
                        },
                    ) { maps -> maps.fold(emptyMap<String, Pair<SubagentStatus, String>>()) { all, m -> all + m } }
                }
            }.collect { now ->
                val finished = now.filter { (key, v) -> !v.first.isActive && known[key]?.isActive == true }
                known = now.mapValues { it.value.first }
                if (finished.isEmpty() || app.isForeground) return@collect
                val failed = finished.values.count { it.first != SubagentStatus.Done }
                val names = finished.values.joinToString(", ") { it.second }
                val title = when {
                    finished.size == 1 && failed == 0 -> "Background agent finished"
                    finished.size == 1 -> "Background agent stopped"
                    else -> "${finished.size} background agents finished"
                }
                post(app, title, names)
            }
        }
    }

    private fun post(context: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(context, AGENT_ID, openIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_bolt)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(text.take(200))
            .setAutoCancel(true)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ALERTS)
                    .setSmallIcon(R.drawable.ic_stat_bolt)
                    .setColor(ContextCompat.getColor(context, R.color.notification_accent))
                    // The title names no task, so it is safe on the lock screen, and it stays true for a stop or a group.
                    .setContentTitle(title)
                    .setContentText("Unlock to see it")
                    .build(),
            )
            .build()
        NotificationManagerCompat.from(context).notify(AGENT_ID, notification)
    }
}
