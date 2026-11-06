package opensec.pibolt.app

import opensec.pibolt.app.data.AccentId
import opensec.pibolt.app.data.AppSettings
import opensec.pibolt.app.data.Density
import opensec.pibolt.app.data.ReduceMotion
import opensec.pibolt.app.data.SettingsCodec
import opensec.pibolt.app.data.ThemeId
import kotlin.test.Test
import kotlin.test.assertEquals

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
        )
        assertEquals(s, SettingsCodec.decode(SettingsCodec.encode(s)))
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
    fun theRemovedTideThemeFallsBackToGraphite() {
        assertEquals(ThemeId.Graphite, SettingsCodec.decode("""{"theme":"Tide"}""").theme)
        assertEquals(ThemeId.GitHub, SettingsCodec.decode("""{"theme":"GitHub"}""").theme)
        assertEquals(AccentId.Theme, AppSettings().accent)
    }
}
