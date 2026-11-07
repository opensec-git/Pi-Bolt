package opensec.pibolt.app

import opensec.pibolt.app.ui.settings.SettingsGroup
import opensec.pibolt.app.ui.settings.SettingsIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Settings search: every query word must appear in the group, label or keywords. */
class SettingsIndexTest {
    @Test
    fun blankQueryShowsEverything() {
        assertNull(SettingsIndex.visible("  "))
        assertEquals(SettingsGroup.entries, SettingsIndex.groups(null))
    }

    @Test
    fun keywordsFindSettingsByOtherNames() {
        assertTrue("text-size" in SettingsIndex.visible("font")!!)
        assertEquals(setOf("haptics"), SettingsIndex.visible("Vibrate"))
        assertTrue("default-model" in SettingsIndex.visible("default model")!!)
        assertTrue("providers" in SettingsIndex.visible("api key")!!)
    }

    @Test
    fun groupTitleMatchesItsSettings() {
        val shown = SettingsIndex.visible("appearance")!!
        assertEquals(setOf("theme", "accent", "text-size", "density"), shown)
        assertEquals(listOf(SettingsGroup.Appearance), SettingsIndex.groups(shown))
    }

    @Test
    fun allWordsMustMatch() {
        assertEquals(emptySet(), SettingsIndex.visible("theme vibrate"))
        assertEquals(emptyList(), SettingsIndex.groups(emptySet()))
        assertEquals(SettingsIndex.entries.size, SettingsIndex.entries.map { it.id }.toSet().size, "ids are unique")
    }
}
