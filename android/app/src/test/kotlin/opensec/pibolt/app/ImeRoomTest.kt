package opensec.pibolt.app

import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.session.ImeRoom
import opensec.pibolt.app.ui.session.MAX_LINES
import opensec.pibolt.app.ui.session.SUGGESTIONS_MAX
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImeRoomTest {
    @Test
    fun aPhoneInPortraitKeepsTheFullComposerWithTheKeyboardUp() {
        val room = ImeRoom.of(windowHeight = 873.dp, imeHeight = 290.dp, statusTop = 30.dp)
        assertFalse(room.oneRowComposer)
        assertEquals(MAX_LINES, room.composerLines)
        assertEquals(SUGGESTIONS_MAX, room.suggestionsMax)
    }

    @Test
    fun theValueStaysTheSameThroughTheKeyboardAnimation() {
        // Every frame of the keyboard sliding in gives another height; the screen recomposes only when this changes.
        val frames = (0..290 step 7).map { ImeRoom.of(873.dp, it.dp, 30.dp) }.toSet()
        assertEquals(1, frames.size)
    }

    @Test
    fun lessRoomGivesFourLinesThenOneRow() {
        assertEquals(4, ImeRoom.of(800.dp, 300.dp, 24.dp).composerLines)
        assertFalse(ImeRoom.of(800.dp, 300.dp, 24.dp).oneRowComposer)
        val landscape = ImeRoom.of(411.dp, 200.dp, 24.dp)
        assertTrue(landscape.oneRowComposer)
        assertEquals(4, landscape.composerLines)
        // 211 - 24 - 56 - 72 = 59dp for the panel.
        assertEquals(59.dp, landscape.suggestionsMax)
    }

    @Test
    fun thePanelNeverGoesUnder48dp() {
        assertEquals(48.dp, ImeRoom.of(300.dp, 200.dp, 24.dp).suggestionsMax)
    }
}
