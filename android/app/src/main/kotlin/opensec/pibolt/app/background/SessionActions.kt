package opensec.pibolt.app.background

import android.app.PendingIntent
import opensec.pibolt.protocol.session.DialogKind
import opensec.pibolt.protocol.session.DialogRequest

/**
 * The buttons and inline replies of session notifications: what each one carries ([encode], [decode]) and what it does
 * to the live sessions ([route]). Pure; SessionActionReceiver runs the result.
 */
object SessionActions {
    const val STOP_ALL = "opensec.pibolt.session.STOP_ALL"
    const val ANSWER = "opensec.pibolt.session.ANSWER"
    const val CONFIRM = "opensec.pibolt.session.CONFIRM"
    const val REPLY = "opensec.pibolt.session.REPLY"

    const val EXTRA_KEY = "opensec.pibolt.session.key"
    const val EXTRA_DIALOG = "opensec.pibolt.session.dialog"
    const val EXTRA_VALUE = "opensec.pibolt.session.value"
    const val EXTRA_YES = "opensec.pibolt.session.yes"
    const val EXTRA_SESSION_ID = "opensec.pibolt.session.id"

    /**
     * The PendingIntent mutability of an action on API [sdk]. A [typed] (inline reply) action must let the system add
     * the reply: mutable from API 31, and no flag before it (FLAG_IMMUTABLE there drops the typed text). A button is
     * immutable.
     */
    fun mutability(typed: Boolean, sdk: Int): Int = when {
        !typed -> PendingIntent.FLAG_IMMUTABLE
        sdk >= 31 -> PendingIntent.FLAG_MUTABLE
        else -> 0
    }

    /** The RemoteInput result key of every inline reply. */
    const val REPLY_KEY = "opensec.pibolt.session.reply"

    /** Select options shown as buttons when there are at most this many (a notification has three action slots). */
    const val OPTION_BUTTONS = 3

    sealed interface Request {
        /** Stop every run on this phone. */
        data object StopAll : Request

        /** Answer dialog [dialogId] of session [key] with the option [value], or (null) with the typed reply. */
        data class Answer(val key: String, val dialogId: String, val value: String? = null) : Request

        data class Confirm(val key: String, val dialogId: String, val yes: Boolean) : Request

        /**
         * The typed reply is a message to session [key]: a steer while it runs, else a new prompt. [sessionId]: the Pi
         * session the notification is about; a live session that has moved to another one since does not get it.
         */
        data class Reply(val key: String, val sessionId: String? = null) : Request
    }

    /** Action slots per notification. */
    const val SLOTS = 8

    /**
     * The PendingIntent request code of action [slot] of notification [notificationId]: distinct for every action of
     * every notification, so FLAG_UPDATE_CURRENT never swaps one button's extras for another's.
     */
    fun requestCode(notificationId: Int, slot: Int): Int {
        require(slot in 0 until SLOTS) { "slot $slot" }
        return notificationId * SLOTS + slot
    }

    /** [request] as an intent's action and string extras. */
    fun encode(request: Request): Pair<String, Map<String, String>> = when (request) {
        Request.StopAll -> STOP_ALL to emptyMap()
        is Request.Answer -> ANSWER to buildMap {
            put(EXTRA_KEY, request.key)
            put(EXTRA_DIALOG, request.dialogId)
            request.value?.let { put(EXTRA_VALUE, it) }
        }
        is Request.Confirm -> CONFIRM to mapOf(EXTRA_KEY to request.key, EXTRA_DIALOG to request.dialogId, EXTRA_YES to request.yes.toString())
        is Request.Reply -> REPLY to buildMap {
            put(EXTRA_KEY, request.key)
            request.sessionId?.let { put(EXTRA_SESSION_ID, it) }
        }
    }

    /** The request an intent carries ([action], [extra] reads a string extra), or null when it carries none. */
    fun decode(action: String?, extra: (String) -> String?): Request? {
        if (action == STOP_ALL) return Request.StopAll
        val key = extra(EXTRA_KEY) ?: return null
        return when (action) {
            ANSWER -> Request.Answer(key, extra(EXTRA_DIALOG) ?: return null, extra(EXTRA_VALUE))
            CONFIRM -> Request.Confirm(key, extra(EXTRA_DIALOG) ?: return null, extra(EXTRA_YES) == "true")
            REPLY -> Request.Reply(key, extra(EXTRA_SESSION_ID))
            else -> null
        }
    }

    /** One live session as the routing sees it. [sessionId]: the Pi session it has open now. */
    data class Live(val key: String, val local: Boolean, val running: Boolean, val dialogs: List<DialogRequest>, val sessionId: String? = null)

    sealed interface Route {
        /** Abort the runs of these sessions. */
        data class Abort(val keys: List<String>) : Route

        data class Value(val key: String, val dialogId: String, val value: String) : Route

        data class Confirm(val key: String, val dialogId: String, val yes: Boolean) : Route

        data class Prompt(val key: String, val text: String, val steer: Boolean) : Route

        /** The session (or the dialog) is gone: the process restarted, it was answered in the app, or it timed out. */
        data class Gone(val key: String) : Route

        /** Nothing usable: ask again for [dialogId] (null: a reply) with [why]. */
        data class Again(val key: String, val dialogId: String?, val why: String) : Route
    }

    /** What [request] does, with [typed] the inline reply's text (null: a button). */
    fun route(request: Request, typed: String?, live: List<Live>): Route {
        val text = typed?.trim().orEmpty()
        fun dialog(key: String, id: String): DialogRequest? = live.firstOrNull { it.key == key }?.dialogs?.firstOrNull { it.id == id }
        return when (request) {
            Request.StopAll -> Route.Abort(live.filter { it.local && it.running }.map { it.key })
            is Request.Reply -> {
                val session = live.firstOrNull { it.key == request.key } ?: return Route.Gone(request.key)
                // The project's (or the chat's) live session moved to another conversation since the notification.
                if (request.sessionId != null && session.sessionId != null && session.sessionId != request.sessionId) return Route.Gone(request.key)
                if (text.isEmpty()) Route.Again(request.key, null, "Type a message") else Route.Prompt(request.key, text, steer = session.running)
            }
            is Request.Confirm -> dialog(request.key, request.dialogId)?.let { Route.Confirm(request.key, it.id, request.yes) } ?: Route.Gone(request.key)
            is Request.Answer -> dialog(request.key, request.dialogId)?.let { answer(request.key, it, request.value ?: text) } ?: Route.Gone(request.key)
        }
    }

    private fun answer(key: String, dialog: DialogRequest, given: String): Route {
        val value = given.trim()
        // A sheet of the mobile UI kit has fields and buttons: it is answered in the app only.
        if (dialog.kind == DialogKind.View) return Route.Again(key, dialog.id, "Open Pi-Bolt to answer")
        if (dialog.kind != DialogKind.Select) {
            if (dialog.kind == DialogKind.Confirm) {
                return when (value.lowercase()) {
                    "y", "yes" -> Route.Confirm(key, dialog.id, true)
                    "n", "no" -> Route.Confirm(key, dialog.id, false)
                    else -> Route.Again(key, dialog.id, "Answer yes or no")
                }
            }
            return if (value.isEmpty()) Route.Again(key, dialog.id, "Type an answer") else Route.Value(key, dialog.id, value)
        }
        // An option by its text (any case), its number in the list, or a suggestion tapped (an option cut to [choice]).
        val option = dialog.options.firstOrNull { it == value }
            ?: dialog.options.firstOrNull { it.trim().equals(value, ignoreCase = true) }
            ?: value.toIntOrNull()?.let { dialog.options.getOrNull(it - 1) }
            ?: dialog.options.filter { choice(it).trim() == value }.singleOrNull()
        return if (option != null) Route.Value(key, dialog.id, option) else Route.Again(key, dialog.id, "Not an option: ${value.take(40)}")
    }

    /**
     * The text of the notification that replaces one whose reply reached no live session: the [typed] reply, so it is not
     * lost (the session reopens from the notification), else how to go on.
     */
    fun closedText(typed: String?): String = typed?.trim()?.takeIf { it.isNotEmpty() }?.let { "Not sent: ${it.take(200)}" } ?: "Open it to continue"

    /** How a waiting dialog is answered from its notification. */
    sealed interface Answering {
        /** One button per option. */
        data class Options(val options: List<String>) : Answering

        data object YesNo : Answering

        /** Answered in the app only (a sheet of the mobile UI kit): the notification opens the session. */
        data object OpenApp : Answering

        /** An inline reply; [choices] are offered as suggestions. */
        data class Typed(val label: String, val choices: List<String> = emptyList()) : Answering
    }

    /** The longest suggestion an inline reply offers: a longer option is cut, and [route] takes the cut text back. */
    const val CHOICE_CHARS = 40

    /** [option] as a suggestion of an inline reply. */
    fun choice(option: String): String = option.take(CHOICE_CHARS)

    fun answering(dialog: DialogRequest): Answering = when (dialog.kind) {
        DialogKind.Confirm -> Answering.YesNo
        DialogKind.Select ->
            if (dialog.options.size in 1..OPTION_BUTTONS && dialog.options.all { it.length <= 24 }) Answering.Options(dialog.options)
            else Answering.Typed("Option name or number", dialog.options.take(5).map(::choice))
        DialogKind.Input, DialogKind.Editor -> Answering.Typed(dialog.placeholder?.takeIf { it.isNotBlank() } ?: "Answer")
        DialogKind.View -> Answering.OpenApp
    }
}
