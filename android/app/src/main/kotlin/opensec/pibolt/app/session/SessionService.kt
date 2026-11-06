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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import opensec.pibolt.app.MainActivity
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the process (and so the connection) alive while the agent runs or a dialog waits, when the app is in the
 * background. Stops itself once the session is idle.
 */
class SessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as PiBoltApp
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                Notifications.ONGOING_ID,
                Notifications.ongoing(this, app.session.value?.hostName?.value ?: getString(R.string.app_name), "Connected"),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0,
            )
        }.isSuccess
        if (!started) {
            stop()
            return START_NOT_STICKY
        }
        scope.launch {
            app.session.flatMapLatest { controller ->
                controller ?: return@flatMapLatest emptyFlow()
                combine(controller.state, controller.link, controller.hostName) { s, link, name -> Triple(s, link, name) }
            }.collect { (s, link, name) ->
                val busy = s.isRunning || s.dialogs.isNotEmpty()
                if (!busy || link is LinkState.Failed) {
                    stop()
                    return@collect
                }
                val text = when {
                    link !is LinkState.Connected -> "Reconnecting…"
                    s.dialogs.isNotEmpty() -> "Waiting for your answer"
                    s.isCompacting -> "Compacting context"
                    else -> "Working"
                }
                Notifications.update(this@SessionService, Notifications.ONGOING_ID, Notifications.ongoing(this@SessionService, name, text))
            }
        }
        scope.launch {
            app.session.collect { if (it == null) stop() }
        }
        return START_NOT_STICKY
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
    private const val SETTLED_ID = 2
    private const val ANSWER_ID = 3
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

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun ongoing(context: Context, host: String, text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_stat_bolt)
            .setContentTitle(host)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context))
            .privateOnLockScreen(context, "Pi is working")
            .build()

    fun settled(context: Context, host: String, lastText: String, aborted: Boolean) {
        val title = if (aborted) "Pi stopped on $host" else "Pi finished on $host"
        val text = lastText.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(200) ?: "Tap to see the result"
        post(
            context,
            SETTLED_ID,
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_bolt)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(lastText.take(1000).ifBlank { text }))
                .setAutoCancel(true)
                .setContentIntent(openApp(context))
                .privateOnLockScreen(context, if (aborted) "Pi stopped" else "Pi finished")
                .build(),
        )
    }

    fun needsAnswer(context: Context, host: String, title: String) {
        post(
            context,
            ANSWER_ID,
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_bolt)
                .setContentTitle("Pi needs an answer on $host")
                .setContentText(title.ifBlank { "Tap to answer" })
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(openApp(context))
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

    fun clearNeedsAnswer(context: Context) {
        NotificationManagerCompat.from(context).cancel(ANSWER_ID)
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
