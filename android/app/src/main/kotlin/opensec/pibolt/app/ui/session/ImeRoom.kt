package opensec.pibolt.app.ui.session

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * What the session screen's bottom stack gets from the room above the keyboard: a one-row composer under 360dp, four
 * composer lines under 560dp, and the command / `@` panel capped so it never pushes the composer under the keyboard.
 *
 * The keyboard's height changes on every frame of its animation. The screen reads it through a derived state of this
 * value, so it recomposes only when one of these three values changes, not on every frame of the keyboard.
 */
@Immutable
data class ImeRoom(val oneRowComposer: Boolean, val composerLines: Int, val suggestionsMax: Dp) {
    companion object {
        fun of(windowHeight: Dp, imeHeight: Dp, statusTop: Dp): ImeRoom {
            val room = windowHeight - imeHeight
            val oneRow = room < 360.dp
            val lines = if (room < 560.dp) 4 else MAX_LINES
            val suggestions = (room - statusTop - 56.dp - if (oneRow) 72.dp else 140.dp).coerceIn(48.dp, SUGGESTIONS_MAX)
            return ImeRoom(oneRow, lines, suggestions)
        }
    }
}
