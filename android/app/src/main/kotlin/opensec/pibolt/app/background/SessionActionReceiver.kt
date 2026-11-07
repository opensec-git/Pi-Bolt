package opensec.pibolt.app.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.session.Notifications
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.protocol.rpc.StreamingBehavior

/**
 * Runs a session notification's button or inline reply ([SessionActions]) on the live sessions: Stop, a dialog's
 * answer, or a message. Not exported; only the app's own PendingIntents reach it.
 */
class SessionActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? PiBoltApp ?: return
        val request = SessionActions.decode(intent.action) { intent.getStringExtra(it) } ?: return
        val notification = intent.getIntExtra(EXTRA_NOTIFICATION, 0)
        val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(SessionActions.REPLY_KEY)?.toString()
        val sessions = app.sessions.value.filter { !it.stopped }
        val live = sessions.map { SessionActions.Live(it.hostId, it.isLocal, it.state.value.isRunning, it.state.value.dialogs, it.state.value.sessionId) }
        fun session(key: String): SessionController? = sessions.firstOrNull { it.hostId == key }
        when (val route = SessionActions.route(request, typed, live)) {
            is SessionActions.Route.Abort -> route.keys.forEach { session(it)?.abort() }
            is SessionActions.Route.Value -> session(route.key)?.let { c ->
                c.answer(route.dialogId, route.value)
                next(app, c, route.dialogId)
            }
            is SessionActions.Route.Confirm -> session(route.key)?.let { c ->
                c.confirm(route.dialogId, route.yes)
                next(app, c, route.dialogId)
            }
            is SessionActions.Route.Prompt -> session(route.key)?.let { c ->
                // The run starts (or takes the steer); the session notification shows it from here. Held back by a
                // spending limit, the session's notification says so instead.
                if (c.send(route.text, StreamingBehavior.Steer)) Notifications.cancel(app, notification)
            }
            is SessionActions.Route.Gone -> {
                val c = session(route.key)
                // A dialog answered in the app (or timed out) on a live session: its next one, or nothing, takes the
                // notification. Only a reply (or a session that is gone) says so, keeping the reply's text.
                if (c != null && request !is SessionActions.Request.Reply) next(app, c, answered = "")
                else Notifications.sessionClosed(app, notification, typed)
            }
            is SessionActions.Route.Again -> {
                val c = session(route.key)
                val dialog = c?.state?.value?.dialogs?.firstOrNull { it.id == route.dialogId }
                // The same question again, saying why; a reply to a finished run just clears its spinner.
                if (c != null && dialog != null) Notifications.needsAnswer(app, c, dialog, why = route.why)
                else Notifications.cancel(app, notification)
            }
        }
    }

    /** After answering [answered]: the session's next open dialog takes the notification, or it goes. */
    private fun next(app: PiBoltApp, c: SessionController, answered: String) {
        val following = c.state.value.dialogs.firstOrNull { it.id != answered }
        if (following != null) Notifications.needsAnswer(app, c, following)
        else Notifications.clearNeedsAnswer(app, c.hostId)
    }

    companion object {
        /** The notification the action belongs to (to update or clear it). */
        const val EXTRA_NOTIFICATION = "opensec.pibolt.session.notification"
    }
}
