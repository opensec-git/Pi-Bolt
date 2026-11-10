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
        assertEquals(setOf("runtimes"), SettingsIndex.visible("python"))
        // The licences row names Mermaid and Termux, so a search for them finds it.
        assertEquals(setOf("licences"), SettingsIndex.visible("mermaid"))
        assertEquals(setOf("licences"), SettingsIndex.visible("termux"))
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

    @Test
    fun everyGroupHasSettingsAndTidyEntries() {
        for (g in SettingsGroup.entries) assertTrue(SettingsIndex.entries.any { it.group == g }, "${g.title} has no entries: search could never show it")
        for (e in SettingsIndex.entries) {
            assertTrue(Regex("[a-z0-9]+(-[a-z0-9]+)*").matches(e.id), "id ${e.id} is kebab-case")
            assertEquals(e.keywords.lowercase(), e.keywords, "${e.id} keywords are lower case")
            assertTrue(e.label.first().isUpperCase(), "${e.id} label starts with a capital (sentence case)")
            assertTrue(!e.label.endsWith("."), "${e.id} label has no full stop")
        }
    }

    @Test
    fun findsWhatTheRowsSay() {
        // The words on the redesigned rows and sheets must find them.
        assertTrue("licences" in SettingsIndex.visible("open source")!!)
        assertTrue("licences" in SettingsIndex.visible("license")!!)
        assertTrue("github" in SettingsIndex.visible("token")!!)
        assertTrue("device-key" in SettingsIndex.visible("reset key")!!)
        assertTrue("updates" in SettingsIndex.visible("check for updates")!!)
        assertTrue("notifications" in SettingsIndex.visible("alerts")!!)
        // Case and extra spaces do not matter.
        assertEquals(SettingsIndex.visible("text size"), SettingsIndex.visible("  TEXT   Size "))
    }
}
