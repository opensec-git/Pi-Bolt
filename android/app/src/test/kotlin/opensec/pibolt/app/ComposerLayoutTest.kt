package opensec.pibolt.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.session.ComposerKeys
import opensec.pibolt.app.ui.session.ComposerLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComposerLayoutTest {
    @Test
    fun pillCirclesShareOneSizeInsetEquallyFromTheEdges() {
        // The kit's 36dp circle is centred in the 48dp target; the pill pads its row EDGE (Space.xs) inside.
        assertEquals(36.dp, ComposerLayout.CIRCLE)
        val inTarget = (ComposerLayout.TARGET - ComposerLayout.CIRCLE) / 2
        assertEquals(6.dp, inTarget)
        assertEquals(10.dp, ComposerLayout.EDGE + inTarget, "10dp from the pill's edge, as the desktop bar")
        assertTrue(ComposerLayout.TARGET >= 48.dp)
    }

    @Test
    fun oneRowFieldCentresItsLineOnTheButtons() {
        // The pill's rowTitle (17sp on a 24sp line) gets 12dp above and below, so one line is 48dp, the buttons' target.
        assertEquals(12.dp, ComposerLayout.fieldPad(24.dp))
        assertEquals(13.dp, ComposerLayout.fieldPad(22.dp))
        // Font scale 1.3: the line is 28.6dp and the padding shrinks so the centre stays on the target's.
        val line = 28.6.dp
        val pad = ComposerLayout.fieldPad(line)
        assertEquals(24f, (pad + line / 2).value, 0.01f)
        // Very large text keeps a little room.
        assertEquals(4.dp, ComposerLayout.fieldPad(60.dp))
    }

    @Test
    fun narrowLandscapeRowDropsThePlusWhileSteering() {
        assertFalse(ComposerLayout.showAttach(compact = true, narrow = true, steering = true))
        assertTrue(ComposerLayout.showAttach(compact = true, narrow = false, steering = true))
        assertTrue(ComposerLayout.showAttach(compact = false, narrow = true, steering = true))
        assertTrue(ComposerLayout.showAttach(compact = true, narrow = true, steering = false))
    }

    @Test
    fun highlightWrapsRoundTheList() {
        assertEquals(0, ComposerLayout.moveHighlight(-1, 1, 5), "Down starts at the top")
        assertEquals(4, ComposerLayout.moveHighlight(-1, -1, 5), "Up starts at the bottom")
        assertEquals(0, ComposerLayout.moveHighlight(4, 1, 5))
        assertEquals(4, ComposerLayout.moveHighlight(0, -1, 5))
        assertEquals(-1, ComposerLayout.moveHighlight(2, 1, 0))
    }

    @Test
    fun keysWorkInTheSuggestionPanelWhileItIsOpen() {
        fun a(key: Key, highlighted: Boolean = false, ctrl: Boolean = false, shift: Boolean = false, suggesting: Boolean = true) =
            ComposerKeys.action(key, ctrl, shift, running = false, hasContent = true, suggesting = suggesting, highlighted = highlighted, firstLine = true, lastLine = true)
        assertEquals(ComposerKeys.Action.SuggestPrevious, a(Key.DirectionUp), "Up moves in the panel, not through history")
        assertEquals(ComposerKeys.Action.SuggestNext, a(Key.DirectionDown))
        assertEquals(ComposerKeys.Action.SuggestPick, a(Key.Tab))
        assertNull(a(Key.Enter), "Enter adds a line until an arrow chose a row (a soft keyboard's Enter is a key too)")
        assertEquals(ComposerKeys.Action.SuggestPick, a(Key.Enter, highlighted = true))
        assertEquals(ComposerKeys.Action.SuggestDismiss, a(Key.Escape))
        assertEquals(ComposerKeys.Action.Send, a(Key.Enter, ctrl = true), "Ctrl+Enter still sends")
        assertEquals(ComposerKeys.Action.CycleThinking, a(Key.Tab, shift = true))
        assertEquals(ComposerKeys.Action.HistoryOlder, a(Key.DirectionUp, suggesting = false))
    }
}
