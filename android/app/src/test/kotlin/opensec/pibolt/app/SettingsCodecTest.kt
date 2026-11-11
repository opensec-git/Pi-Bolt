package opensec.pibolt.app

import opensec.pibolt.app.data.AccentId
import opensec.pibolt.app.data.AppSettings
import opensec.pibolt.app.data.Density
import opensec.pibolt.app.data.ReduceMotion
import opensec.pibolt.app.data.SettingsCodec
import opensec.pibolt.app.data.ThemeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsCodecTest {
    @Test
    fun roundTripsEveryField() {
        val s = AppSettings(
            theme = ThemeId.Ember,
            accent = AccentId.Glacier,
            textSize = 2,
            density = Density.Compact,
            reduceMotion = ReduceMotion.On,
            haptics = false,
            thinkingExpanded = true,
            toolPreviewLines = 24,
            notificationsAsked = true,
            autoUpdateCheck = false,
            webSearch = false,
        )
        assertEquals(s, SettingsCodec.decode(SettingsCodec.encode(s)))
    }

    @Test
    fun webSearchIsOnByDefaultAndForFilesFromBeforeIt() {
        assertTrue(AppSettings().webSearch)
        // Settings saved by 0.5.3 have no webSearch field: the web tools are on for them too.
        assertTrue(SettingsCodec.decode("""{"version":1,"theme":"Paper","autoUpdateCheck":false}""").webSearch)
        assertEquals(false, SettingsCodec.decode("""{"webSearch":false}""").webSearch)
        assertTrue(SettingsCodec.decode("""{"webSearch":"no"}""").webSearch)
    }

    @Test
    fun defaultsForMissingOrDamagedFiles() {
        assertEquals(AppSettings(), SettingsCodec.decode(null))
        assertEquals(AppSettings(), SettingsCodec.decode(""))
        assertEquals(AppSettings(), SettingsCodec.decode("not json"))
        assertEquals(AppSettings(), SettingsCodec.decode("[1,2]"))
        assertEquals(AppSettings(), SettingsCodec.decode("{}"))
    }

    @Test
    fun unknownValuesFallBackAndNumbersAreClamped() {
        val s = SettingsCodec.decode("""{"theme":"Neon","accent":"Iris","textSize":9,"toolPreviewLines":7,"haptics":"yes"}""")
        assertEquals(ThemeId.Graphite, s.theme)
        assertEquals(AccentId.Iris, s.accent)
        assertEquals(AppSettings.TEXT_SIZE_MAX, s.textSize)
        assertEquals(12, s.toolPreviewLines)
        assertEquals(true, s.haptics)
        assertEquals(AppSettings.TEXT_SIZE_MIN, SettingsCodec.decode("""{"textSize":-5}""").textSize)
    }

    @Test
    fun theRemovedVoidThemeMovesToTheDefault() {
        // Void was removed in 0.5.2 (the default is black now); a saved Void choice becomes Pi-Bolt (id Graphite) and the
        // other settings in the file survive.
        val s = SettingsCodec.decode("""{"theme":"Void","accent":"Iris","textSize":1}""")
        assertEquals(ThemeId.Graphite, s.theme)
        assertEquals("Pi-Bolt", s.theme.label)
        assertEquals(AccentId.Iris, s.accent)
        assertEquals(1, s.textSize)
        assertTrue(ThemeId.entries.none { it.name == "Void" })
        assertEquals(ThemeId.Graphite, SettingsCodec.decode(SettingsCodec.encode(s)).theme)
    }

    @Test
    fun theNewThemesRoundTrip() {
        for (theme in listOf(ThemeId.Paper, ThemeId.Contrast, ThemeId.Lime, ThemeId.Moss)) {
            assertEquals(theme, SettingsCodec.decode(SettingsCodec.encode(AppSettings(theme = theme))).theme)
        }
    }

    @Test
    fun theRemovedTideThemeFallsBackToGraphite() {
        assertEquals(ThemeId.Graphite, SettingsCodec.decode("""{"theme":"Tide"}""").theme)
        assertEquals(ThemeId.GitHub, SettingsCodec.decode("""{"theme":"GitHub"}""").theme)
        assertEquals(AccentId.Theme, AppSettings().accent)
    }
}
