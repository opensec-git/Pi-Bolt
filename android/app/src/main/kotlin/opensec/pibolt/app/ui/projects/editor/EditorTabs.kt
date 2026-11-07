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
import androidx.lifecycle.ViewModel
import opensec.pibolt.app.ui.projects.FileDoc
import opensec.pibolt.app.ui.projects.FileStamp
import java.io.File

/**
 * One open file of the editor: what was read ([doc]) and the file's [disk] stamp then, the text on disk ([saved], set
 * when editing starts), the text being edited ([value]) and its undo history. Lives in [EditorTabs], so switching tabs
 * keeps unsaved edits.
 */
@Stable
class EditorTab(val rel: String) {
    var doc by mutableStateOf<FileDoc?>(null)

    /** The file's stamp when [doc] was read or last saved: a different one on disk means someone else changed it. */
    var disk: FileStamp? = null
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

    /** This tab under the path [to] (its file was renamed): the same text, draft and stamp; the undo history restarts. */
    fun movedTo(to: String): EditorTab = EditorTab(to).also { t ->
        t.doc = doc
        t.disk = disk
        t.saved = saved
        t.value = value
        t.editing = editing
    }

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
 * The project's open files, in order, and the one shown. Opening a file that is open selects its tab. While the project
 * screen is on the back stack (a session, the terminal or the secrets opened over it) and across a configuration change
 * (rotation, theme), the tabs themselves stay, unsaved drafts included ([EditorTabsHolder], [saver]); after process
 * death only the paths come back (an unsaved draft does not outlive the process).
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

    /**
     * A file was renamed or deleted in the Files tab: its tab (or the tabs inside a folder) follow, keeping an unsaved
     * draft, or close. A tab left on the old path would show the old text and its Save would write the old file again.
     */
    fun moved(from: String, to: String?) {
        if (from == to) return
        val affected = tabs.filter { it.rel == from || it.rel.startsWith("$from/") }
        for (t in affected) {
            val i = tabs.indexOf(t)
            if (to == null) tabs.removeAt(i) else tabs[i] = t.movedTo(to + t.rel.removePrefix(from))
        }
    }

    val anyDirty: Boolean get() = tabs.any { it.dirty }

    companion object {
        /**
         * Saves the paths in the saved-state Bundle (a 1 MB draft would overflow it) and keeps the tabs in [holder], so
         * coming back to the screen (from the back stack or after a configuration change) hands back the same tabs with
         * their drafts and undo history; after process death the holder is new and the tabs reopen from the paths, not
         * editing.
         */
        fun saver(holder: EditorTabsHolder): Saver<EditorTabs, Any> = listSaver(
            save = { t ->
                holder.tabs = t
                t.tabs.map { it.rel }
            },
            restore = { paths -> holder.tabs ?: EditorTabs(paths).also { holder.tabs = it } },
        )
    }
}

/**
 * Keeps a project screen's [EditorTabs] for as long as the screen's navigation entry lives: while a session, the
 * terminal or the secrets cover it, and across configuration changes; cleared when the project screen is closed.
 */
class EditorTabsHolder : ViewModel() {
    var tabs: EditorTabs? = null
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
