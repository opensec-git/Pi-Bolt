package opensec.pibolt.app.ui.projects.editor

/**
 * One change to the text: at [start], [removed] was replaced by [inserted]. [selectionBefore] / [selectionAfter] are
 * the cursor (or selection) to restore on undo and redo.
 */
data class TextEdit(
    val start: Int,
    val removed: String,
    val inserted: String,
    val selectionBefore: IntRange,
    val selectionAfter: IntRange,
    val at: Long = 0,
    val kind: EditKind = EditKind.Typing,
) {
    /** [text] with this edit applied. */
    fun apply(text: String): String = text.substring(0, start) + inserted + text.substring(start + removed.length)

    /** [text] with this edit taken back. */
    fun revert(text: String): String = text.substring(0, start) + removed + text.substring(start + inserted.length)
}

/** Typing coalesces into one undo step; anything else (paste, replace, cut) is a step of its own. */
enum class EditKind { Typing, Other }

/**
 * The editor's undo and redo stacks (pure, unit-tested). [record] takes the text before and after a change and keeps
 * only what changed (common prefix and suffix cut), so a keystroke in a 1 MB file costs a few characters. Typing
 * within [COALESCE_MS] of the previous keystroke, continuing at its end, joins its step; a newline or a pause starts a
 * new one. Recording clears redo.
 */
class EditHistory(private val limit: Int = LIMIT) {
    private val undo = ArrayDeque<TextEdit>()
    private val redo = ArrayDeque<TextEdit>()

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()
    val size: Int get() = undo.size

    fun clear() {
        undo.clear()
        redo.clear()
    }

    /**
     * Records the change from [before] to [after]. Returns false when the text did not change (a selection move).
     * [kind] is [EditKind.Other] for pastes, cuts, replaces and anything not typed one character at a time.
     */
    fun record(before: String, after: String, selectionBefore: IntRange, selectionAfter: IntRange, now: Long, kind: EditKind = EditKind.Typing): Boolean {
        val edit = diff(before, after, selectionBefore, selectionAfter, now, kind) ?: return false
        redo.clear()
        val last = undo.lastOrNull()
        if (last != null && coalesces(last, edit)) {
            val merged = if (edit.removed.isEmpty()) {
                last.copy(inserted = last.inserted + edit.inserted, selectionAfter = edit.selectionAfter, at = now)
            } else {
                last.copy(inserted = last.inserted.dropLast(1), selectionAfter = edit.selectionAfter, at = now)
            }
            // Typing a character and backspacing it leaves nothing to undo.
            if (merged.inserted.isEmpty() && merged.removed.isEmpty()) undo.removeLast() else undo[undo.lastIndex] = merged
        } else {
            undo.addLast(edit)
            while (undo.size > limit) undo.removeFirst()
        }
        return true
    }

    /** Records [edit] as a step of its own (replace all, an indent of several lines). */
    fun push(edit: TextEdit) {
        redo.clear()
        undo.addLast(edit)
        while (undo.size > limit) undo.removeFirst()
    }

    /** Takes back the last step: the text and the selection to show, or null when there is nothing to undo. */
    fun undo(text: String): Pair<String, IntRange>? {
        val e = undo.removeLastOrNull() ?: return null
        redo.addLast(e)
        return e.revert(text) to e.selectionBefore
    }

    /** Re-applies the last undone step. */
    fun redo(text: String): Pair<String, IntRange>? {
        val e = redo.removeLastOrNull() ?: return null
        undo.addLast(e)
        return e.apply(text) to e.selectionAfter
    }

    private fun coalesces(last: TextEdit, edit: TextEdit): Boolean {
        if (last.kind != EditKind.Typing || edit.kind != EditKind.Typing) return false
        if (edit.at - last.at > COALESCE_MS) return false
        val end = last.start + last.inserted.length
        // Typing on: one character right after what this step typed; a newline starts a new step.
        if (edit.removed.isEmpty() && edit.inserted.length == 1) return edit.start == end && edit.inserted != "\n" && !last.inserted.endsWith("\n")
        // Backspacing what this step typed.
        if (edit.inserted.isEmpty() && edit.removed.length == 1) return last.inserted.isNotEmpty() && edit.start == end - 1
        return false
    }

    companion object {
        const val LIMIT = 500
        const val COALESCE_MS = 1_000L

        /** The edit turning [before] into [after]: common prefix and suffix cut; null when they are equal. */
        fun diff(before: String, after: String, selectionBefore: IntRange, selectionAfter: IntRange, now: Long = 0, kind: EditKind = EditKind.Other): TextEdit? {
            if (before == after) return null
            val max = minOf(before.length, after.length)
            var p = 0
            while (p < max && before[p] == after[p]) p++
            var s = 0
            while (s < max - p && before[before.length - 1 - s] == after[after.length - 1 - s]) s++
            return TextEdit(p, before.substring(p, before.length - s), after.substring(p, after.length - s), selectionBefore, selectionAfter, now, kind)
        }
    }
}
