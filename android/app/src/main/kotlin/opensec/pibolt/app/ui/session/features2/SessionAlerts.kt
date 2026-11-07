package opensec.pibolt.app.ui.session.features2

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import opensec.pibolt.app.MainActivity
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.R
import opensec.pibolt.app.data.ProjectStore
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
    private const val AGENT_ID = 4

    /** Must match Notifications' alerts channel (SessionService.kt). */
    private const val CHANNEL_ALERTS = "alerts"

    /** Where a tapped notification leads. */
    sealed interface Target {
        data class Local(val project: String, val sessionFile: String?) : Target

        data object Remote : Target
    }

    private val _pending = MutableStateFlow<Target?>(null)
    val pending: StateFlow<Target?> = _pending.asStateFlow()

    /** The intent a session notification opens: the app, at the live session. */
    fun openIntent(context: Context): Intent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val live = (context.applicationContext as? PiBoltApp)?.session?.value ?: return intent
        if (live.isLocal) {
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
            project != null -> Target.Local(project, intent.getStringExtra(EXTRA_SESSION))
            intent.getBooleanExtra(EXTRA_REMOTE, false) -> Target.Remote
            else -> return
        }
        intent.removeExtra(EXTRA_PROJECT)
        intent.removeExtra(EXTRA_SESSION)
        intent.removeExtra(EXTRA_REMOTE)
    }

    /**
     * Opens the pending target: makes its session the live one (resuming the file if another session is open) and
     * shows it through [onLocal] (the project name) or [onRemote].
     */
    @Composable
    fun Navigate(app: PiBoltApp, onLocal: (String) -> Unit, onRemote: () -> Unit) {
        val target by pending.collectAsState()
        LaunchedEffect(target) {
            val t = target ?: return@LaunchedEffect
            _pending.value = null
            when (t) {
                Target.Remote -> if (app.session.value?.let { !it.isLocal && !it.stopped } == true) onRemote()
                is Target.Local -> {
                    val live = LocalSessions.current.value
                    val here = live != null && !live.controller.stopped && live.project.name == t.project
                    if (here && (t.sessionFile == null || live.controller.sessionFile == t.sessionFile)) {
                        onLocal(t.project)
                        return@LaunchedEffect
                    }
                    val dir = File(app.engine.projectsDir, t.project)
                    if (!dir.isDirectory) return@LaunchedEffect
                    LocalSessions.open(app.engine, ProjectStore.get(app), dir, sessionFile = t.sessionFile?.takeIf { File(it).isFile })
                        .onSuccess { onLocal(t.project) }
                }
            }
        }
    }

    /** Watches the live session for background agents that finish while no screen is visible. Call once. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun watchBackgroundAgents(app: PiBoltApp) {
        app.appScope.launch {
            var known = emptyMap<String, SubagentStatus>()
            app.session.flatMapLatest { c ->
                known = emptyMap()
                c?.state?.map { s -> s.subagents.values.filter { it.background }.associate { it.key to (it.status to (it.description.ifBlank { it.type })) } }
                    ?.distinctUntilChanged() ?: emptyFlow()
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
            .setContentTitle(title)
            .setContentText(text.take(200))
            .setAutoCancel(true)
            .setContentIntent(open)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ALERTS)
                    .setSmallIcon(R.drawable.ic_stat_bolt)
                    .setContentTitle("Background agent finished")
                    .setContentText("Unlock to see it")
                    .build(),
            )
            .build()
        NotificationManagerCompat.from(context).notify(AGENT_ID, notification)
    }
}
