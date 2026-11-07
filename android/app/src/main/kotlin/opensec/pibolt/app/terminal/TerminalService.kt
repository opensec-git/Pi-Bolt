package opensec.pibolt.app.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import opensec.pibolt.app.MainActivity
import opensec.pibolt.app.R

/**
 * Keeps the process, and so every terminal's shell and what runs in it (a dev server, a build), alive while the app is
 * in the background. Runs while at least one shell runs; "Close all" in its notification ends them.
 */
class TerminalService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE_ALL) {
            Terminals.closeAll(applicationContext)
            return START_NOT_STICKY
        }
        val count = Terminals.tabs.count { it.running }
        // Foreground first in every case: a startForegroundService() must be answered even when the shells just ended.
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        val ok = runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this, count.coerceAtLeast(1)), type) }.isSuccess
        if (!ok || count == 0) stop()
        return START_NOT_STICKY
    }

    private fun stop() {
        running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 41
        private const val CHANNEL = "terminal"
        private const val ACTION_CLOSE_ALL = "opensec.pibolt.terminal.CLOSE_ALL"

        @Volatile
        private var running = false

        /** Start, refresh or stop the service for the shells running now. Main thread. */
        fun update(context: Context) {
            val app = context.applicationContext
            if (Terminals.anyRunning) {
                // From the background Android may refuse a refresh; the service started earlier still runs then.
                running = runCatching { ContextCompat.startForegroundService(app, Intent(app, TerminalService::class.java)) }.isSuccess || running
            } else {
                // Always: a refused refresh must not leave the notification up after the last shell ended.
                running = false
                app.stopService(Intent(app, TerminalService::class.java))
            }
        }

        private fun notification(context: Context, count: Int): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.channel_terminal), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.channel_terminal_desc)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
            )
            val open = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val closeAll = PendingIntent.getService(
                context,
                NOTIFICATION_ID,
                Intent(context, TerminalService::class.java).setAction(ACTION_CLOSE_ALL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_bolt)
                .setColor(ContextCompat.getColor(context, R.color.notification_accent))
                .setContentTitle(if (count == 1) "1 terminal running" else "$count terminals running")
                .setContentText("Running until you close them")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(0, "Close all", closeAll)
                .build()
        }
    }
}
