package opensec.pibolt.app.session

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import opensec.pibolt.app.MainActivity
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.R
import opensec.pibolt.app.background.SessionActionReceiver
import opensec.pibolt.app.background.SessionActions
import opensec.pibolt.app.ui.session.features2.SessionAlerts
import opensec.pibolt.protocol.session.DialogRequest
import java.io.File

object Notifications {
    const val ONGOING_ID = 1

    /**
     * The session notification's Open button. Not [ONGOING_ID]: the update notification's tap is request code 1 on the
     * same activity, so each would take the other's extras (FLAG_UPDATE_CURRENT) and Open lost its session.
     */
    private const val OPEN_REQUEST = 2
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

    /**
     * A new process has no live session: a "needs an answer" left by a killed one is about a dialog that is gone, and a
     * session notification of the old service must not linger (most builds remove it with the process; some do not).
     * Finished alerts stay: their tap resumes the session from its file. Call once, before any session starts.
     */
    fun clearStale(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val active = runCatching { manager.activeNotifications }.getOrNull() ?: return
        for (n in active) {
            val id = n.id
            if (id == ONGOING_ID || RunningSummary.isKind(id, RunningSummary.ANSWER)) manager.cancel(n.tag, id)
        }
    }

    private fun openApp(context: Context, project: String? = null, sessionFile: String? = null, requestCode: Int = 0, chat: Boolean = false): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        // Carries the session, so a tap opens that exact one (features2.SessionAlerts); without one, the session in focus.
        if (chat) {
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(SessionAlerts.EXTRA_CHAT, true)
                // The chat it describes, so the alert still resumes it after the process restarted or another chat opened.
                .apply { sessionFile?.let { putExtra(SessionAlerts.EXTRA_SESSION, it) } }
        } else if (project != null) {
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(SessionAlerts.EXTRA_PROJECT, project)
                .apply { sessionFile?.let { putExtra(SessionAlerts.EXTRA_SESSION, it) } }
        } else {
            SessionAlerts.openIntent(context)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * A broadcast to [SessionActionReceiver] carrying [request]. [slot] tells a notification's actions apart; [typed]
     * actions are mutable, so the system can add the inline reply.
     */
    private fun action(context: Context, notificationId: Int, slot: Int, request: SessionActions.Request, typed: Boolean = false): PendingIntent {
        val (name, extras) = SessionActions.encode(request)
        val intent = Intent(context, SessionActionReceiver::class.java).setAction(name).putExtra(SessionActionReceiver.EXTRA_NOTIFICATION, notificationId)
        extras.forEach { (k, v) -> intent.putExtra(k, v) }
        val mutability = SessionActions.mutability(typed, Build.VERSION.SDK_INT)
        return PendingIntent.getBroadcast(context, SessionActions.requestCode(notificationId, slot), intent, mutability or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** A button; on a locked phone it asks for the unlock first (an answer may allow a command). */
    private fun button(title: String, intent: PendingIntent): NotificationCompat.Action =
        NotificationCompat.Action.Builder(0, title, intent).setAuthenticationRequired(true).build()

    private fun reply(title: String, label: String, intent: PendingIntent, choices: List<String> = emptyList()): NotificationCompat.Action {
        val input = RemoteInput.Builder(SessionActions.REPLY_KEY).setLabel(label)
            .apply { if (choices.isNotEmpty()) setChoices(choices.toTypedArray()) }
            .build()
        return NotificationCompat.Action.Builder(0, title, intent)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAuthenticationRequired(true)
            .build()
    }

    /** The session notification. [stoppable]: a run on this phone works, so Stop is offered. */
    fun ongoing(context: Context, host: String, text: String, lines: List<String> = emptyList(), stoppable: Boolean = false): Notification =
        NotificationCompat.Builder(context, CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_stat_bolt)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(host)
            .setContentText(text)
            .apply { if (lines.isNotEmpty()) setStyle(NotificationCompat.InboxStyle().also { style -> lines.forEach(style::addLine) }) }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context))
            .apply { if (stoppable) addAction(button("Stop", action(context, ONGOING_ID, 0, SessionActions.Request.StopAll))) }
            .addAction(NotificationCompat.Action.Builder(0, "Open", openApp(context, requestCode = OPEN_REQUEST)).build())
            .privateOnLockScreen(context, "Pi is working")
            .build()

    private fun sessionFile(context: Context, key: String): String? =
        (context.applicationContext as? PiBoltApp)?.sessions?.value?.firstOrNull { it.hostId == key }?.sessionFile

    /**
     * The project a notification about a session in [project] opens: none for a chat (its directory is no project, and a
     * project of the same name must not open in its place).
     */
    fun projectOf(project: File?, chat: Boolean): String? = project?.name?.takeUnless { chat }

    /**
     * A session finished while the app was away. [key]: its hostId (one notification per session); [project]: its project;
     * [sessionId]: the Pi session that finished (a reply goes to it only while it is still the one open). [capped]: a
     * spending limit stopped it or held a reply back ("Daily limit reached ($5.00)."), said in place of the last reply.
     */
    fun settled(context: Context, key: String, project: String?, host: String, lastText: String, aborted: Boolean, chat: Boolean = false, sessionId: String? = null, capped: String? = null) {
        val id = RunningSummary.notificationId(key, RunningSummary.SETTLED)
        val title = if (aborted || capped != null) "Pi stopped on $host" else "Pi finished on $host"
        val text = capped ?: lastText.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(200) ?: "Tap to see the result"
        val big = capped?.let { "$it Open Pi-Bolt to raise it or continue once." } ?: lastText.take(1000).ifBlank { text }
        post(
            context,
            id,
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_bolt)
                .setColor(ContextCompat.getColor(context, R.color.notification_accent))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(big))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(openApp(context, project, sessionFile(context, key), id, chat))
                // At a limit a reply would be held back too: the notification only opens the session.
                .apply { if (capped == null) addAction(reply("Reply", "Message Pi", action(context, id, 0, SessionActions.Request.Reply(key, sessionId), typed = true))) }
                .privateOnLockScreen(context, if (aborted || capped != null) "Pi stopped" else "Pi finished")
                .build(),
        )
    }

    /**
     * [run]'s turn was cut off: Android ended the app (and its engine) while it worked ([RunMarks]). Takes the place of
     * the session's "finished" alert; the tap reopens the session, where what was saved before the kill is shown.
     */
    fun interrupted(context: Context, run: RunMarks.Run) {
        val id = RunningSummary.notificationId(run.key, RunningSummary.SETTLED)
        val text = "Android closed Pi-Bolt while Pi was working. Open the session to continue."
        post(
            context,
            id,
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_bolt)
                .setColor(ContextCompat.getColor(context, R.color.notification_accent))
                .setContentTitle("Pi was stopped on ${run.host}")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setContentIntent(openApp(context, run.project, run.sessionFile, id, run.chat))
                .privateOnLockScreen(context, "Pi was stopped")
                .build(),
        )
    }

    /**
     * Session [key] waits for [dialog]: a heads-up with the answer in it (option buttons, Yes and No, or an inline
     * reply). [why]: the last reply was not usable, said in place of the message.
     */
    fun needsAnswer(context: Context, key: String, project: String?, host: String, dialog: DialogRequest, why: String? = null, chat: Boolean = false) {
        val id = RunningSummary.notificationId(key, RunningSummary.ANSWER)
        val text = why ?: dialog.title.ifBlank { "Tap to answer" }
        val detail = listOfNotNull(why, dialog.title.takeIf { it.isNotBlank() }, dialog.message?.takeIf { it.isNotBlank() }).joinToString("\n")
        val builder = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_bolt)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle("Pi needs an answer on $host")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail.take(1000).ifBlank { text }))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(why != null)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, project, sessionFile(context, key), id, chat))
            .privateOnLockScreen(context, "Pi needs an answer")
        when (val answering = SessionActions.answering(dialog)) {
            SessionActions.Answering.YesNo -> {
                builder.addAction(button("Yes", action(context, id, 0, SessionActions.Request.Confirm(key, dialog.id, yes = true))))
                builder.addAction(button("No", action(context, id, 1, SessionActions.Request.Confirm(key, dialog.id, yes = false))))
            }
            is SessionActions.Answering.Options -> answering.options.forEachIndexed { i, option ->
                builder.addAction(button(option, action(context, id, i, SessionActions.Request.Answer(key, dialog.id, option))))
            }
            is SessionActions.Answering.Typed ->
                builder.addAction(reply("Answer", answering.label, action(context, id, 0, SessionActions.Request.Answer(key, dialog.id), typed = true), answering.choices))
        }
        post(context, id, builder.build())
    }

    /** [controller] waits for [dialog]: [needsAnswer] with its project, or as a chat. */
    fun needsAnswer(context: Context, controller: SessionController, dialog: DialogRequest, why: String? = null) =
        needsAnswer(context, controller.hostId, projectOf(controller.project, controller.isChat), controller.hostName.value, dialog, why, chat = controller.isChat)

    /**
     * A reply ([typed]) reached no live session (the app restarted, the session closed, or it moved to another
     * conversation). The tap still opens the session the notification was about: its own content intent is kept (it
     * carries the session file, and outlives the process), so the reply can be sent from there.
     */
    fun sessionClosed(context: Context, notificationId: Int, typed: String? = null) {
        val text = SessionActions.closedText(typed)
        // The notification's own tap target, as posted (FLAG_NO_CREATE returns it unchanged); a new one only if it is gone.
        val open = PendingIntent.getActivity(context, notificationId, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)
            ?: openApp(context, requestCode = notificationId)
        post(
            context,
            notificationId,
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_bolt)
                .setColor(ContextCompat.getColor(context, R.color.notification_accent))
                .setContentTitle("Session closed")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(open)
                .privateOnLockScreen(context, "Session closed")
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
                .setColor(ContextCompat.getColor(context, R.color.notification_accent))
                .setContentTitle(publicTitle)
                .setContentText("Unlock to see it")
                .build(),
        )
    }

    fun clearNeedsAnswer(context: Context, key: String) {
        NotificationManagerCompat.from(context).cancel(RunningSummary.notificationId(key, RunningSummary.ANSWER))
    }

    fun cancel(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
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
