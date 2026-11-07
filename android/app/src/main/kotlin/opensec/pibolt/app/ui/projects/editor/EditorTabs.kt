package opensec.pibolt.app.ui.projects.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import opensec.pibolt.app.ui.projects.FileDoc
import java.io.File

/**
 * One open file of the editor: what was read ([doc]), the text on disk ([saved], set when editing starts), the text
 * being edited ([value]) and its undo history. Lives in [EditorTabs], so switching tabs keeps unsaved edits.
 */
@Stable
class EditorTab(val rel: String) {
    var doc by mutableStateOf<FileDoc?>(null)
    var editing by mutableStateOf(false)
    var saved by mutableStateOf<String?>(null)
    var value by mutableStateOf(TextFieldValue(""))
    val history = EditHistory()

    /** Bumped on every history change, so Undo / Redo enablement recomposes. */
    var historyVersion by mutableIntStateOf(0)

    val name: String get() = rel.substringAfterLast('/')

    /** Edited and not saved: the tab shows a dot. */
    val dirty: Boolean get() = editing && saved != null && value.text != saved

    fun startEditing(text: String) {
        saved = text
        value = TextFieldValue(text, TextRange(0))
        history.clear()
        historyVersion++
        editing = true
    }

    fun stopEditing() {
        editing = false
        saved = null
        history.clear()
        historyVersion++
    }

    fun undo(): Boolean = history.undo(value.text)?.let { (text, sel) ->
        value = TextFieldValue(text, TextRange(sel.first.coerceIn(0, text.length), sel.last.coerceIn(0, text.length)))
        historyVersion++
        true
    } ?: false

    fun redo(): Boolean = history.redo(value.text)?.let { (text, sel) ->
        value = TextFieldValue(text, TextRange(sel.first.coerceIn(0, text.length), sel.last.coerceIn(0, text.length)))
        historyVersion++
        true
    } ?: false

    /** Replaces `[start, end)` with [text] as one undo step and selects [select] (default: the cursor after it). */
    fun replace(start: Int, end: Int, text: String, select: TextRange? = null) {
        val before = value
        val next = before.text.substring(0, start) + text + before.text.substring(end)
        val sel = select ?: TextRange(start + text.length)
        history.push(TextEdit(start, before.text.substring(start, end), text, before.selection.start..before.selection.end, sel.start..sel.end, System.currentTimeMillis(), EditKind.Other))
        historyVersion++
        value = TextFieldValue(next, sel)
    }
}

/**
 * The project's open files, in order, and the one shown. Opening a file that is open selects its tab. A configuration
 * change (rotation, theme) keeps the tabs themselves, unsaved drafts included ([saver]); after process death only the
 * paths come back (an unsaved draft does not outlive the process).
 */
@Stable
class EditorTabs(initial: List<String> = emptyList()) {
    val tabs = mutableStateListOf<EditorTab>().apply { addAll(initial.distinct().map(::EditorTab)) }

    fun find(rel: String): EditorTab? = tabs.firstOrNull { it.rel == rel }

    fun open(rel: String): EditorTab = find(rel) ?: EditorTab(rel).also { tabs += it }

    /** Adds [tab] unless a tab for its file is open. */
    fun adopt(tab: EditorTab) {
        if (find(tab.rel) == null) tabs += tab
    }

    /** Closes [rel]'s tab; returns the tab to show next (the neighbour), or null when none are left. */
    fun close(rel: String): EditorTab? {
        val i = tabs.indexOfFirst { it.rel == rel }
        if (i < 0) return tabs.lastOrNull()
        tabs.removeAt(i)
        return tabs.getOrNull(i) ?: tabs.getOrNull(i - 1)
    }

    /** The tab [step] places after (or before, negative) [rel], wrapping. */
    fun neighbour(rel: String, step: Int): EditorTab? {
        if (tabs.isEmpty()) return null
        val i = tabs.indexOfFirst { it.rel == rel }.coerceAtLeast(0)
        return tabs[((i + step) % tabs.size + tabs.size) % tabs.size]
    }

    /** A file was renamed or deleted in the Files tab: its tab (or the tabs inside a folder) follow or close. */
    fun moved(from: String, to: String?) {
        val affected = tabs.filter { it.rel == from || it.rel.startsWith("$from/") }
        for (t in affected) {
            val i = tabs.indexOf(t)
            if (to == null) tabs.removeAt(i) else tabs[i] = EditorTab(to + t.rel.removePrefix(from))
        }
    }

    val anyDirty: Boolean get() = tabs.any { it.dirty }

    companion object {
        private val retained = HashMap<String, EditorTabs>()

        /**
         * Saves the paths in the saved-state Bundle (a 1 MB draft would overflow it) and keeps the tabs under [key] in
         * memory, so a configuration change hands back the same tabs with their drafts and undo history; after process
         * death the tabs reopen from the paths, not editing.
         */
        fun saver(key: String): Saver<EditorTabs, Any> = listSaver(
            save = { t ->
                retained[key] = t
                t.tabs.map { it.rel }
            },
            restore = { retained.remove(key) ?: EditorTabs(it) },
        )

        /** Forgets [key]'s kept tabs: the screen closed for good, not for a configuration change. */
        fun release(key: String) {
            retained.remove(key)
        }
    }
}

/**
 * Where the viewer opens [file] from: the session folder [cwd] and the path inside it when the file lies in it (so the
 * tab and "Ask Pi" use the project path the agent knows), else its own folder and name.
 */
fun viewerPlace(file: File, cwd: String?): Pair<File, String> {
    val f = file.absoluteFile.normalize()
    val root = cwd?.let { File(it).absoluteFile.normalize() }
    if (root != null && f.path.startsWith(root.path + File.separator)) return root to f.path.substring(root.path.length + 1).replace(File.separatorChar, '/')
    return (f.parentFile ?: f) to f.name
}
