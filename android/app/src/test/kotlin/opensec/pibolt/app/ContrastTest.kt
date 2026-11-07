package opensec.pibolt.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import opensec.pibolt.app.data.AccentId
import opensec.pibolt.app.data.ThemeId
import opensec.pibolt.app.ui.theme.PiColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WCAG 2.1 contrast of the palette in every theme and accent (DESIGN.md, "Contrast"): every text ink passes AA (4.5:1)
 * on the canvas and on every surface, including surface3 (menus, snackbars, inline code), and the accent's own ink
 * passes on the accent.
 */
class ContrastTest {
    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun surfaces(c: PiColors) = listOf("canvas" to c.canvas, "surface1" to c.surface1, "surface2" to c.surface2, "surface3" to c.surface3)

    @Test
    fun textInksPassAaOnEverySurface() {
        val failures = ArrayList<String>()
        for (theme in ThemeId.entries) {
            val c = PiColors.of(theme, AccentId.Theme)
            for ((ink, fg) in listOf("ink1" to c.ink1, "ink2" to c.ink2, "ink3" to c.ink3)) {
                for ((name, bg) in surfaces(c)) {
                    val r = contrast(fg, bg)
                    if (r < 4.5) failures += "$theme $ink on $name: ${"%.2f".format(r)}"
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun statusColoursPassAaOnSurfaces() {
        val failures = ArrayList<String>()
        for (theme in ThemeId.entries) {
            val c = PiColors.of(theme, AccentId.Theme)
            for ((state, fg) in listOf("success" to c.success, "warning" to c.warning, "danger" to c.danger)) {
                for ((name, bg) in surfaces(c).take(3)) {
                    val r = contrast(fg, bg)
                    if (r < 4.5) failures += "$theme $state on $name: ${"%.2f".format(r)}"
                }
            }
            // Danger text on its soft fill (danger buttons, error notices), over cards and sheets.
            for ((name, bg) in listOf("surface1" to c.surface1, "surface2" to c.surface2)) {
                val r = contrast(c.danger, c.dangerSoft.compositeOver(bg))
                if (r < 4.5) failures += "$theme danger on dangerSoft over $name: ${"%.2f".format(r)}"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun accentInkPassesOnAccentForEveryAccent() {
        val failures = ArrayList<String>()
        for (theme in ThemeId.entries) {
            for (accent in AccentId.entries) {
                val c = PiColors.of(theme, accent)
                val r = contrast(c.onAccent, c.accent)
                if (r < 4.5) failures += "$theme/$accent onAccent on accent: ${"%.2f".format(r)}"
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun inksKeepTheirOrder() {
        // ink3 was raised for contrast; it must still read as a step below ink2 in every theme.
        for (theme in ThemeId.entries) {
            val c = PiColors.of(theme, AccentId.Theme)
            assertTrue(c.ink1.luminance() > c.ink2.luminance() && c.ink2.luminance() > c.ink3.luminance(), "$theme ink order")
            assertTrue(contrast(c.ink2, c.ink3) >= 1.2, "$theme ink2/ink3 too close")
        }
    }

    @Test
    fun nearBlackSurface1StandsOffTheCanvas() {
        assertEquals(Color(0xFF0D0E10), PiColors.of(ThemeId.Void, AccentId.Theme).surface1)
        assertEquals(Color(0xFF0F0F0F), PiColors.of(ThemeId.Vercel, AccentId.Theme).surface1)
        for (theme in ThemeId.entries) {
            val c = PiColors.of(theme, AccentId.Theme)
            assertTrue(contrast(c.surface1, c.canvas) >= 1.05, "$theme surface1 / canvas")
        }
    }
}
