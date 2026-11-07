package opensec.pibolt.app.session

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import opensec.pibolt.app.MainActivity
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.R
import opensec.pibolt.app.ui.session.features2.SessionAlerts
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the process (and so the connections, and every local engine process) alive while any session works or waits for
 * an answer, when the app is in the background. One foreground notification sums them up ([RunningSummary]); it stops
 * itself once none is busy. Without it a backgrounded app is cached and frozen, and the engines with it.
 */
class SessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as PiBoltApp
        val started = runCatching {
            // Local runs are work on this phone (the engine processes live as long as this service keeps the app up);
            // a remote one relays messages from a computer. Both types are declared: sessions of either kind may join.
            val type = if (Build.VERSION.SDK_INT < 34) 0 else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            val summary = RunningSummary.of(entries(app.sessions.value), getString(R.string.app_name))
            ServiceCompat.startForeground(
                this,
                Notifications.ONGOING_ID,
                Notifications.ongoing(this, summary?.title ?: getString(R.string.app_name), summary?.text ?: "Working", summary?.lines.orEmpty()),
                type,
            )
        }.isSuccess
        if (!started) {
            stop()
            return START_NOT_STICKY
        }
        scope.launch {
            app.sessions.flatMapLatest { list ->
                if (list.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(list.map { c -> combine(c.state, c.link, c.hostName) { _, _, _ -> c } }) { entries(it.toList()) }
                }
            }.collect { entries ->
                val summary = RunningSummary.of(entries, getString(R.string.app_name))
                if (summary == null) {
                    stop()
                    return@collect
                }
                Notifications.update(this@SessionService, Notifications.ONGOING_ID, Notifications.ongoing(this@SessionService, summary.title, summary.text, summary.lines))
            }
        }
        return START_NOT_STICKY
    }

    /** The sessions as the notification sees them; one whose link failed for good no longer counts as working. */
    private fun entries(sessions: List<SessionController>): List<RunningSummary.Entry> = sessions.filter { !it.stopped && it.link.value !is LinkState.Failed }.map {
        val s = it.state.value
        RunningSummary.Entry(
            name = it.hostName.value,
            local = it.isLocal,
            running = s.isRunning,
            waiting = s.dialogs.isNotEmpty(),
            compacting = s.isCompacting,
            connected = it.link.value is LinkState.Connected,
        )
    }

    private fun stop() {
        running.set(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running.set(false)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private val running = AtomicBoolean(false)

        fun ensureRunning(context: Context) {
            if (!running.compareAndSet(false, true)) return
            // From the background Android may refuse to start it; the connection then lives as long as the process.
            val ok = runCatching { ContextCompat.startForegroundService(context, Intent(context, SessionService::class.java)) }.isSuccess
            if (!ok) running.set(false)
        }
    }
}

object Notifications {
    const val ONGOING_ID = 1
    private const val CHANNEL_SESSION = "session"
    private const val CHANNEL_ALERTS = "alerts"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SESSION, context.getString(R.string.channel_session), NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = context.getString(R.string.channel_session_desc)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = context.getString(R.string.channel_alerts_desc)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
        )
    }

    private fun openApp(context: Context, project: String? = null, requestCode: Int = 0, chat: Boolean = false): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        // Carries the session, so a tap opens that exact one (features2.SessionAlerts); without one, the session in focus.
        if (chat) {
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(SessionAlerts.EXTRA_CHAT, true)
        } else if (project != null) {
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(SessionAlerts.EXTRA_PROJECT, project)
        } else {
            SessionAlerts.openIntent(context)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun ongoing(context: Context, host: String, text: String, lines: List<String> = emptyList()): Notification =
        NotificationCompat.Builder(context, CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_stat_bolt)
            .setContentTitle(host)
            .setContentText(text)
            .apply { if (lines.isNotEmpty()) setStyle(NotificationCompat.InboxStyle().also { style -> lines.forEach(style::addLine) }) }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context))
            .privateOnLockScreen(context, "Pi is working")
            .build()

    /**
     * A session finished while the app was away. [key]: its hostId (one notification per session); [project]: its
     * project; [chat]: it is the live plain chat.
     */
    fun settled(context: Context, key: String, project: String?, host: String, lastText: String, aborted: Boolean, chat: Boolean = false) {
        val id = RunningSummary.notificationId(key, RunningSummary.SETTLED)
        val title = if (aborted) "Pi stopped on $host" else "Pi finished on $host"
        val text = lastText.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(200) ?: "Tap to see the result"
        post(
            context,
            id,
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_bolt)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(lastText.take(1000).ifBlank { text }))
                .setAutoCancel(true)
                .setContentIntent(openApp(context, project, id, chat))
                .privateOnLockScreen(context, if (aborted) "Pi stopped" else "Pi finished")
                .build(),
        )
    }

    fun needsAnswer(context: Context, key: String, project: String?, host: String, title: String, chat: Boolean = false) {
        val id = RunningSummary.notificationId(key, RunningSummary.ANSWER)
        post(
            context,
            id,
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_bolt)
                .setContentTitle("Pi needs an answer on $host")
                .setContentText(title.ifBlank { "Tap to answer" })
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(openApp(context, project, id, chat))
                .privateOnLockScreen(context, "Pi needs an answer")
                .build(),
        )
    }

    /**
     * Agent output, dialog titles and host names stay off the lock screen: a locked phone shows only [publicTitle],
     * whatever the user's "sensitive notifications" setting.
     */
    private fun NotificationCompat.Builder.privateOnLockScreen(context: Context, publicTitle: String) = apply {
        setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        setPublicVersion(
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_bolt)
                .setContentTitle(publicTitle)
                .setContentText("Unlock to see it")
                .build(),
        )
    }

    fun clearNeedsAnswer(context: Context, key: String) {
        NotificationManagerCompat.from(context).cancel(RunningSummary.notificationId(key, RunningSummary.ANSWER))
    }

    fun update(context: Context, id: Int, notification: Notification) = post(context, id, notification)

    private fun post(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
