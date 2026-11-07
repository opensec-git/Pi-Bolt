package opensec.pibolt.app.ui.projects.editor

import android.view.KeyEvent

/** What a hardware-keyboard shortcut does in the editor. */
enum class EditorCommand { Save, Find, Replace, GoToLine, Undo, Redo, CloseTab, NextTab, PreviousTab, FindNext, FindPrevious }

/** A key with its modifiers: [keyCode] is an Android `KeyEvent.KEYCODE_*`. */
data class KeyChord(val keyCode: Int, val ctrl: Boolean = false, val shift: Boolean = false, val alt: Boolean = false)

/**
 * The editor's hardware-keyboard shortcuts, one table (unit-tested), so the bindings are listed in one place and a
 * screen never compares key codes itself. Ctrl+Y is also Redo, as on most desktop editors; Ctrl+Tab and
 * Ctrl+Shift+Tab move between tabs; F3 / Shift+F3 step through find matches.
 */
object EditorShortcuts {
    val DEFAULTS: Map<KeyChord, EditorCommand> = mapOf(
        KeyChord(KeyEvent.KEYCODE_S, ctrl = true) to EditorCommand.Save,
        KeyChord(KeyEvent.KEYCODE_F, ctrl = true) to EditorCommand.Find,
        KeyChord(KeyEvent.KEYCODE_H, ctrl = true) to EditorCommand.Replace,
        KeyChord(KeyEvent.KEYCODE_G, ctrl = true) to EditorCommand.GoToLine,
        KeyChord(KeyEvent.KEYCODE_Z, ctrl = true) to EditorCommand.Undo,
        KeyChord(KeyEvent.KEYCODE_Z, ctrl = true, shift = true) to EditorCommand.Redo,
        KeyChord(KeyEvent.KEYCODE_Y, ctrl = true) to EditorCommand.Redo,
        KeyChord(KeyEvent.KEYCODE_W, ctrl = true) to EditorCommand.CloseTab,
        KeyChord(KeyEvent.KEYCODE_TAB, ctrl = true) to EditorCommand.NextTab,
        KeyChord(KeyEvent.KEYCODE_TAB, ctrl = true, shift = true) to EditorCommand.PreviousTab,
        KeyChord(KeyEvent.KEYCODE_F3) to EditorCommand.FindNext,
        KeyChord(KeyEvent.KEYCODE_F3, shift = true) to EditorCommand.FindPrevious,
    )

    fun command(chord: KeyChord, bindings: Map<KeyChord, EditorCommand> = DEFAULTS): EditorCommand? = bindings[chord]

    /** "Ctrl+Shift+Z" style label for a command's first binding, for menus and TalkBack hints. */
    fun label(command: EditorCommand, bindings: Map<KeyChord, EditorCommand> = DEFAULTS): String? =
        bindings.entries.firstOrNull { it.value == command }?.key?.let { k ->
            buildString {
                if (k.ctrl) append("Ctrl+")
                if (k.alt) append("Alt+")
                if (k.shift) append("Shift+")
                append(
                    when (k.keyCode) {
                        KeyEvent.KEYCODE_TAB -> "Tab"
                        KeyEvent.KEYCODE_F3 -> "F3"
                        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A' + (k.keyCode - KeyEvent.KEYCODE_A)).toString()
                        else -> k.keyCode.toString()
                    },
                )
            }
        }
}
