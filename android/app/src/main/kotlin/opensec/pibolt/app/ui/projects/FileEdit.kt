package opensec.pibolt.app.ui.projects

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue

/**
 * The viewer's edit of one file: whether it is being edited, the text editing started from ([original]) and the
 * [draft]. It survives an activity recreation (rotation, theme) through [saver], so unsaved edits are not lost and an
 * empty draft never replaces the file.
 */
@Stable
class FileEdit {
    var editing by mutableStateOf(false)
        private set
    var original by mutableStateOf<String?>(null)
        private set
    var draft by mutableStateOf(TextFieldValue(""))

    /** Edited and not saved. */
    val dirty: Boolean get() = editing && original != null && draft.text != original

    fun start(text: String) {
        original = text
        draft = TextFieldValue(text)
        editing = true
    }

    fun stop() {
        editing = false
        original = null
        draft = TextFieldValue("")
    }

    companion object {
        private val retained = HashMap<String, FileEdit>()

        /**
         * Saves only [key] in the saved-state Bundle (a 1 MB draft would overflow it) and keeps the edit in memory, so
         * a configuration change hands back the same edit. After process death the edit is gone and a fresh one, not
         * editing, starts.
         */
        fun saver(key: String): Saver<FileEdit, String> = Saver(
            save = {
                retained[key] = it
                key
            },
            restore = { retained.remove(it) ?: FileEdit() },
        )

        /** Forgets [key]'s kept edit: the viewer closed for good, not for a configuration change. */
        fun release(key: String) {
            retained.remove(key)
        }
    }
}
