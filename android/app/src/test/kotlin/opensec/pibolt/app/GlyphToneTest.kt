package opensec.pibolt.app

import opensec.pibolt.app.data.AccentId
import opensec.pibolt.app.data.ThemeId
import opensec.pibolt.app.ui.components.GlyphTone
import opensec.pibolt.app.ui.theme.PiColors
import kotlin.test.Test
import kotlin.test.assertEquals

/** Settings stays monochrome: section tints read as ink2, only status colours keep their colour. */
class GlyphToneTest {
    @Test
    fun onlyStatusColoursColourAGlyph() {
        for (theme in ThemeId.entries) {
            val c = PiColors.of(theme, AccentId.Theme)
            for (tint in c.tints + listOf(c.ink1, c.accent)) assertEquals(c.ink2, GlyphTone.of(tint, c), "$theme $tint")
            for (status in listOf(c.danger, c.success, c.warning)) assertEquals(status, GlyphTone.of(status, c), "$theme $status")
        }
    }
}
