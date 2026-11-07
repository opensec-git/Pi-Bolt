package opensec.pibolt.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import opensec.pibolt.app.data.AccentId
import opensec.pibolt.app.data.ThemeId
import opensec.pibolt.app.ui.markdown.Highlighter
import opensec.pibolt.app.ui.markdown.tokenStyle
import opensec.pibolt.app.ui.theme.PiColors
import kotlin.test.Test
import kotlin.test.assertTrue

/** Every token colour passes WCAG AA (4.5:1) on canvas, surface1 (code blocks) and surface2 in all seven themes. */
class SyntaxColorsTest {
    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun tokenColoursPassAaInEveryTheme() {
        val failures = ArrayList<String>()
        for (theme in ThemeId.entries) {
            val colors = PiColors.of(theme, AccentId.Theme)
            for (kind in Highlighter.Kind.entries) {
                val fg = tokenStyle(kind, colors).color
                for ((name, bg) in listOf("canvas" to colors.canvas, "surface1" to colors.surface1, "surface2" to colors.surface2)) {
                    val r = contrast(fg, bg)
                    if (r < 4.5) failures += "$theme $kind on $name: ${"%.2f".format(r)}"
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun themesHaveTheirOwnPalettes() {
        val keyword = ThemeId.entries.map { PiColors.of(it, AccentId.Theme).syntax.keyword }.toSet()
        assertTrue(keyword.size >= 5)
    }
}
