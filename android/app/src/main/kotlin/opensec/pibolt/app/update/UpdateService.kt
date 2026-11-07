package opensec.pibolt.app.update

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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import opensec.pibolt.app.MainActivity
import opensec.pibolt.app.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the process up while an update downloads and is checked, with a progress notification (Cancel). A foreground
 * service of type dataSync; it stops itself when the download ends.
 */
class UpdateService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val updates = updates
        if (intent?.action == ACTION_CANCEL) {
            updates.cancelDownload()
            stop()
            return START_NOT_STICKY
        }
        val job = updates.state.value.job
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                UpdateNotifications.PROGRESS_ID,
                UpdateNotifications.progress(this, job),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        }.isSuccess
        if (!started) {
            stop()
            return START_NOT_STICKY
        }
        scope.launch {
            // Progress in whole percent: at most a hundred notification updates per download.
            updates.state.map { it.job }
                .distinctUntilChangedBy { j -> if (j is UpdateJob.Downloading) Triple(j.info, j.percent, j.waitingForNetwork) else j }
                .collect { j ->
                    if (j !is UpdateJob.Downloading && j !is UpdateJob.Verifying) {
                        stop()
                    } else {
                        UpdateNotifications.post(this@UpdateService, UpdateNotifications.PROGRESS_ID, UpdateNotifications.progress(this@UpdateService, j))
                    }
                }
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
        const val ACTION_CANCEL = "opensec.pibolt.app.update.CANCEL"
        private val running = AtomicBoolean(false)

        fun ensureRunning(context: Context) {
            if (!running.compareAndSet(false, true)) return
            // Started from a tap, so the app is in the foreground; if Android still refuses, the download runs as long
            // as the process does.
            val ok = runCatching { ContextCompat.startForegroundService(context, Intent(context, UpdateService::class.java)) }.isSuccess
            if (!ok) running.set(false)
        }
    }
}

object UpdateNotifications {
    const val PROGRESS_ID = 10
    private const val RESULT_ID = 11
    private const val CHANNEL = "updates"

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.channel_updates_desc) },
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        1,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun progress(context: Context, job: UpdateJob): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bolt)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context))
        when (job) {
            is UpdateJob.Downloading -> {
                val cancel = PendingIntent.getService(
                    context,
                    2,
                    Intent(context, UpdateService::class.java).setAction(UpdateService.ACTION_CANCEL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                builder.setContentTitle("Downloading Pi-Bolt ${job.info.version}")
                    .setContentText(progressText(job))
                    .setProgress(100, job.percent, false)
                    .addAction(0, "Cancel", cancel)
            }
            is UpdateJob.Verifying -> builder.setContentTitle("Checking Pi-Bolt ${job.info.version}").setProgress(0, 0, true)
            else -> builder.setContentTitle("Updating Pi-Bolt")
        }
        return builder.build()
    }

    fun progressText(job: UpdateJob.Downloading): String {
        val amount = "${UpdateRules.formatSize(job.bytes)} of ${UpdateRules.formatSize(job.total)}"
        return if (job.waitingForNetwork) "Waiting for network · $amount" else "$amount · ${job.percent}%"
    }

    fun ready(context: Context, info: UpdateInfo) = result(context, "Pi-Bolt ${info.version} is ready to install", "Tap to install")

    fun failed(context: Context, info: UpdateInfo, message: String) = result(context, "Pi-Bolt ${info.version} was not installed", message)

    private fun result(context: Context, title: String, text: String) = post(
        context,
        RESULT_ID,
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_bolt)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build(),
    )

    fun clearResult(context: Context) = NotificationManagerCompat.from(context).cancel(RESULT_ID)

    fun post(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
