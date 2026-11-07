package opensec.pibolt.app

import opensec.pibolt.app.ui.components.buttonSlot
import opensec.pibolt.app.ui.components.buttonsFitSideBySide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ButtonRow's choice between equal side-by-side buttons and a stack (sizes in px). */
class ButtonRowTest {
    @Test
    fun slotsShareTheWidthAfterTheGaps() {
        assertEquals(154, buttonSlot(320, 2, 12))
        assertEquals(320, buttonSlot(320, 1, 12))
        assertEquals(0, buttonSlot(10, 3, 12))
        assertEquals(0, buttonSlot(320, 0, 12))
    }

    @Test
    fun shortLabelsStaySideBySide() {
        assertTrue(buttonsFitSideBySide(listOf(90, 120), 320, 12))
        assertTrue(buttonsFitSideBySide(listOf(154, 154), 320, 12))
    }

    @Test
    fun oneLongLabelStacksTheRow() {
        // "Clone from git" at font scale 1.3 in the setup card of a 360dp phone: wider than its half.
        assertFalse(buttonsFitSideBySide(listOf(100, 167), 288, 12))
        assertFalse(buttonsFitSideBySide(listOf(155, 10), 320, 12))
    }
}
