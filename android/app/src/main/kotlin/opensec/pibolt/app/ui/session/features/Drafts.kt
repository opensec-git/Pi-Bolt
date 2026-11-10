package opensec.pibolt.app.ui.session.features

import android.content.Context
import androidx.core.content.edit

/**
 * The composer's unsent text per session (computer or project, and Pi session id), kept across app restarts. A sent
 * or cleared draft is removed; only the [MAX] most recently written drafts are kept.
 */
class Drafts(context: Context) {
    private val prefs = context.getSharedPreferences("drafts", Context.MODE_PRIVATE)

    fun get(key: String): String = prefs.getString(TEXT + key, null).orEmpty()

    fun put(key: String, text: String) {
        if (text.isBlank()) {
            if (prefs.contains(TEXT + key)) prefs.edit { remove(TEXT + key).remove(AT + key) }
            return
        }
        prefs.edit {
            putString(TEXT + key, text)
            putLong(AT + key, System.currentTimeMillis())
        }
        trim()
    }

    private fun trim() {
        val stamps = prefs.all.filterKeys { it.startsWith(AT) }.mapNotNull { (k, v) -> (v as? Long)?.let { k.removePrefix(AT) to it } }
        if (stamps.size <= MAX) return
        val old = stamps.sortedByDescending { it.second }.drop(MAX)
        prefs.edit { old.forEach { (k, _) -> remove(TEXT + k).remove(AT + k) } }
    }

    companion object {
        const val MAX = 50
        private const val TEXT = "t:"
        private const val AT = "a:"

        /** The draft key of a session: the host (or project) and the Pi session, when known. */
        fun key(hostId: String, sessionId: String?): String = "$hostId/${sessionId.orEmpty()}"
    }
}
