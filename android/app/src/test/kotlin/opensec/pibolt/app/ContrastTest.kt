package opensec.pibolt.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import opensec.pibolt.app.data.AccentId
import opensec.pibolt.app.data.ThemeId
import opensec.pibolt.app.ui.components.SnackKind
import opensec.pibolt.app.ui.components.noticeStyle
import opensec.pibolt.app.ui.session.subagent.SubagentFormat
import opensec.pibolt.app.ui.theme.PiColors
import opensec.pibolt.app.ui.theme.SELECTION_ALPHA
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WCAG 2.1 contrast of the palette in every theme and accent (DESIGN.md, "Contrast"): every text ink passes AA (4.5:1)
 * on the canvas and on every surface, including surface3 (menus, snackbars, inline code), and the accent's own ink
 * passes on the accent. The shared components' colour pairs are checked here too (DESIGN.md, "Components").
 */
class ContrastTest {
    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun surfaces(c: PiColors) = listOf("canvas" to c.canvas, "surface1" to c.surface1, "surface2" to c.surface2, "surface3" to c.surface3)

    /** The app's own themes, built in full (Palette.kt `full`): each is held to every role check below. */
    private val own = listOf(ThemeId.Graphite, ThemeId.Paper, ThemeId.Contrast, ThemeId.Lime, ThemeId.Moss)

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
        // ink3 was raised for contrast; it must still read as a step below ink2 in every theme. Order is contrast with the
        // canvas, so it holds on Paper (dark ink on white) as on the dark themes.
        for (theme in ThemeId.entries) {
            val c = PiColors.of(theme, AccentId.Theme)
            assertTrue(contrast(c.ink1, c.canvas) > contrast(c.ink2, c.canvas) && contrast(c.ink2, c.canvas) > contrast(c.ink3, c.canvas), "$theme ink order")
            assertTrue(contrast(c.ink2, c.ink3) >= 1.2, "$theme ink2/ink3 too close")
        }
    }

    @Test
    fun nearBlackSurface1StandsOffTheCanvas() {
        assertEquals(Color(0xFF0F0F0F), PiColors.of(ThemeId.Vercel, AccentId.Theme).surface1)
        for (theme in ThemeId.entries) {
            val c = PiColors.of(theme, AccentId.Theme)
            assertTrue(contrast(c.surface1, c.canvas) >= 1.05, "$theme surface1 / canvas")
        }
    }

    @Test
    fun accentTextPassesOnCardsAndSheets() {
        // Text actions and links ("Paste", "Get a key at …", "Choose from GitHub") are accent text on cards and sheets.
        val failures = ArrayList<String>()
        for (theme in ThemeId.entries) {
            for (accent in AccentId.entries) {
                val c = PiColors.of(theme, accent)
                for ((name, bg) in surfaces(c).take(3)) {
                    val r = contrast(c.accent, bg)
                    if (r < 4.5) failures += "$theme/$accent accent text on $name: ${"%.2f".format(r)}"
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun subagentTintsPassNonTextContrastOnCardsAndSheets() {
        // Each agent's Quad is in its own tint (the theme's PiColors.tints): a state glyph, so 3:1 (WCAG 1.4.11) on the cards
        // (surface1) and sheets (surface2) it sits on, in every theme.
        val failures = ArrayList<String>()
        for (theme in ThemeId.entries) {
            val c = PiColors.of(theme, AccentId.Theme)
            assertEquals(SubagentFormat.HUE_COUNT, c.tints.size, "$theme tint count")
            c.tints.forEachIndexed { i, tint ->
                for ((name, bg) in listOf("surface1" to c.surface1, "surface2" to c.surface2)) {
                    val r = contrast(tint, bg)
                    if (r < 3.0) failures += "$theme tint $i on $name: ${"%.2f".format(r)}"
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun noticeTextAndGlyphPassOnTheirFill() {
        // A Notice's text is ink1 (ink2 under a title) on its fill, which sits on a card, a sheet or the canvas; its glyph
        // carries the kind and needs 3:1 (WCAG 1.4.11, non-text contrast).
        val failures = ArrayList<String>()
        for (theme in ThemeId.entries) {
            val c = PiColors.of(theme, AccentId.Theme)
            for (kind in SnackKind.entries) {
                val style = noticeStyle(kind, c)
                for ((name, bg) in surfaces(c).take(3)) {
                    val fill = style.fill.compositeOver(bg)
                    for ((ink, fg) in listOf("ink1" to c.ink1, "ink2" to c.ink2)) {
                        val r = contrast(fg, fill)
                        if (r < 4.5) failures += "$theme $kind notice $ink over $name: ${"%.2f".format(r)}"
                    }
                    val g = contrast(style.tint, fill)
                    if (g < 3.0) failures += "$theme $kind notice glyph over $name: ${"%.2f".format(g)}"
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun buttonLabelsPass() {
        // Secondary (ink1 on surface2), danger (danger on dangerSoft over a sheet, which is surface1, or a card) and the
        // disabled primary (ink3 on surface3, which must still be readable: it says what will be possible). The ghost
        // button's ink2 label sits on a sheet (surface1) or the canvas.
        val failures = ArrayList<String>()
        for (theme in ThemeId.entries) {
            val c = PiColors.of(theme, AccentId.Theme)
            val pairs = listOf(
                "secondary" to contrast(c.ink1, c.surface2),
                "danger" to contrast(c.danger, c.dangerSoft.compositeOver(c.surface2)),
                "disabled primary" to contrast(c.ink3, c.surface3),
                "ghost on sheet" to contrast(c.ink1, c.surface2),
                "danger on a surface1 sheet" to contrast(c.danger, c.dangerSoft.compositeOver(c.surface1)),
                "ghost (ink2) on a sheet" to contrast(c.ink2, c.surface1),
                "ghost (ink2) on canvas" to contrast(c.ink2, c.canvas),
                "snackbar text on surface2" to contrast(c.ink1, c.surface2),
            )
            for ((name, r) in pairs) if (r < 4.5) failures += "$theme $name: ${"%.2f".format(r)}"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun ownThemesRolesPass() {
        // The default and the app's own themes: each role is checked on every ground it is drawn on, plain and under the
        // press and selected overlays: body text (ink1) and secondary text (ink2) AA; ink3 at least 3:1 as a glyph on
        // every ground (its AA as meta text on plain surfaces is textInksPassAaOnEverySurface); the accent as a button
        // (its label AA, its fill 3:1 against the surface it sits on) and as link text (AA on canvas, cards, sheets);
        // ink1 on the text-selection highlight AA.
        val failures = ArrayList<String>()
        for (theme in own) for (accent in AccentId.entries) {
            val c = PiColors.of(theme, accent)
            fun need(role: String, fg: Color, bg: Color, ground: String, min: Double) {
                val r = contrast(fg, bg)
                if (r < min) failures += "$theme/$accent $role on $ground: ${"%.2f".format(r)} < $min"
            }
            for ((name, bg) in surfaces(c)) {
                for ((ground, fill) in listOf(name to bg, "$name pressed" to c.press.compositeOver(bg), "$name selected" to c.selected.compositeOver(bg))) {
                    need("body ink1", c.ink1, fill, ground, 4.5)
                    need("secondary ink2", c.ink2, fill, ground, 4.5)
                    need("glyph ink3", c.ink3, fill, ground, 3.0)
                }
                need("button fill", c.accent, bg, name, 3.0)
                need("selected text", c.ink1, c.accent.copy(alpha = SELECTION_ALPHA).compositeOver(bg), "$name selection", 4.5)
            }
            need("button label", c.onAccent, c.accent, "accent", 4.5)
            for ((name, bg) in surfaces(c).take(3)) need("link", c.accent, bg, name, 4.5)
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun defaultThemeHasNoBlueCast() {
        // The owner saw the old Graphite (#0B0C0E, ink2 #A3A8B0) as bluish. The default is now a warm charcoal: its
        // greys, and everything drawn from them (lines, overlays, the scrim, the selection), may lean warm but never
        // cool: blue at most green, green at most red, and the channels at most 10 apart.
        val c = PiColors.of(ThemeId.Graphite, AccentId.Theme)
        val greys = listOf(
            "canvas" to c.canvas, "surface1" to c.surface1, "surface2" to c.surface2, "surface3" to c.surface3,
            "ink1" to c.ink1, "ink2" to c.ink2, "ink3" to c.ink3, "ink4" to c.ink4,
            "accent" to c.accent, "onAccent" to c.onAccent,
            "line" to c.line.compositeOver(c.canvas), "lineStrong" to c.lineStrong.compositeOver(c.surface1),
            "lineFocus" to c.lineFocus.compositeOver(c.surface1), "press" to c.press.compositeOver(c.surface1),
            "selected" to c.selected.compositeOver(c.surface2), "scrim" to c.scrim.compositeOver(c.surface2),
            "selection" to c.accent.copy(alpha = SELECTION_ALPHA).compositeOver(c.surface1),
        )
        val failures = ArrayList<String>()
        for ((name, color) in greys) {
            val r = Math.round(color.red * 255)
            val g = Math.round(color.green * 255)
            val b = Math.round(color.blue * 255)
            if (b > g || g > r || r - b > 10) failures += "$name #%02X%02X%02X".format(r, g, b)
        }
        // The syntax and ANSI greys keep the desktop's neutral, off-blue set: never blue above red.
        for ((name, color) in listOf("comment" to c.syntax.comment, "ansiBlue" to c.ansiBlue, "syntax function" to c.syntax.function, "syntax keyword" to c.syntax.keyword)) {
            if (Math.round(color.blue * 255) > Math.round(color.red * 255)) failures += "$name has a blue cast"
        }
        // Paper, Contrast and Lime are neutral too: their surfaces and inks carry no tint.
        for (theme in listOf(ThemeId.Paper, ThemeId.Contrast, ThemeId.Lime)) {
            val t = PiColors.of(theme, AccentId.Theme)
            for ((name, color) in listOf(t.canvas, t.surface1, t.surface2, t.surface3, t.ink1, t.ink2, t.ink3, t.ink4, t.syntax.comment).withIndex()) {
                val r = Math.round(color.red * 255)
                val g = Math.round(color.green * 255)
                val b = Math.round(color.blue * 255)
                if (b > r || maxOf(r, g, b) - minOf(r, g, b) > 4) failures += "$theme grey $name #%02X%02X%02X".format(r, g, b)
            }
        }
        assertTrue(failures.isEmpty(), "greys with a cast:\n" + failures.joinToString("\n"))
    }

    @Test
    fun elevationStepsStayDistinct() {
        // Each surface steps further from the canvas in every theme: lighter on the dark ones, darker on Paper (white
        // canvas, cards and sheets in soft greys). In the app's own themes each step is a visible one.
        for (theme in ThemeId.entries) {
            val c = PiColors.of(theme, AccentId.Theme)
            val steps = surfaces(c).map { it.second.luminance() }
            val outward = if (c.isLight) steps.zipWithNext().all { (a, b) -> b < a } else steps.zipWithNext().all { (a, b) -> b > a }
            assertTrue(outward, "$theme surfaces out of order")
        }
        for (theme in own) {
            for ((lower, upper) in surfaces(PiColors.of(theme, AccentId.Theme)).zipWithNext()) {
                assertTrue(contrast(upper.second, lower.second) >= 1.05, "$theme ${lower.first} -> ${upper.first} too close")
            }
        }
    }

    @Test
    fun onlyPaperIsLight() {
        // Paper drives Material's light scheme and dark system bar icons; every other theme is dark.
        assertEquals(setOf(ThemeId.Paper), ThemeId.entries.filter { PiColors.of(it, AccentId.Theme).isLight }.toSet())
    }

    @Test
    fun contrastThemeIsAaa() {
        // Contrast is the high-contrast theme: every text ink, status and syntax colour at 7:1 (WCAG AAA) on the canvas,
        // cards and sheets (inks on menus too), and its lines at 3:1 so a 1dp border alone reads.
        val c = PiColors.of(ThemeId.Contrast, AccentId.Theme)
        val failures = ArrayList<String>()
        for ((name, bg) in surfaces(c)) {
            for ((ink, fg) in listOf("ink1" to c.ink1, "ink2" to c.ink2, "ink3" to c.ink3)) {
                val r = contrast(fg, bg)
                if (r < 7.0) failures += "$ink on $name: ${"%.2f".format(r)}"
            }
        }
        val colours = listOf("success" to c.success, "warning" to c.warning, "danger" to c.danger, "diffAdd" to c.diffAddFg, "diffDel" to c.diffDelFg) +
            listOf(c.syntax.keyword, c.syntax.type, c.syntax.function, c.syntax.string, c.syntax.number, c.syntax.constant, c.syntax.operator, c.syntax.attribute, c.syntax.tag, c.syntax.regex, c.syntax.escape, c.syntax.variable, c.syntax.comment)
                .mapIndexed { i, color -> "syntax$i" to color }
        for ((name, fg) in colours) {
            for ((ground, bg) in surfaces(c).take(3)) {
                val r = contrast(fg, bg)
                if (r < 7.0) failures += "$name on $ground: ${"%.2f".format(r)}"
            }
        }
        for ((name, bg) in surfaces(c).take(3)) {
            val r = contrast(c.lineStrong.compositeOver(bg), bg)
            if (r < 3.0) failures += "lineStrong on $name: ${"%.2f".format(r)}"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun windowBackgroundIsTheDefaultCanvas() {
        // The window colour before the first frame (res/values/colors.xml) must be Graphite's canvas, or launch flashes.
        val xml = File("src/main/res/values/colors.xml").readText()
        val hex = Regex("""name="splash_bg">#([0-9A-Fa-f]{6})<""").find(xml)?.groupValues?.get(1)
        assertEquals(PiColors.of(ThemeId.Graphite, AccentId.Theme).canvas, Color(0xFF000000 or hex!!.toLong(16)))
    }

    @Test
    fun defaultThemeIsTheWarmCharcoal() {
        // The 2026-11-07 redesign: a warm charcoal canvas and warm-grey inks (lifted for AA), the warm white as the
        // accent, the desktop's green, amber and red for state.
        val c = PiColors.of(ThemeId.Graphite, AccentId.Theme)
        val expected = listOf(
            "canvas" to (c.canvas to 0xFF1C1B19), "surface1" to (c.surface1 to 0xFF232220),
            "surface2" to (c.surface2 to 0xFF2A2926), "surface3" to (c.surface3 to 0xFF32312E),
            "ink1" to (c.ink1 to 0xFFECEBE8), "ink2" to (c.ink2 to 0xFFB3B1AB),
            "ink3" to (c.ink3 to 0xFF9C9A94), "ink4" to (c.ink4 to 0xFF5E5C57),
            "accent" to (c.accent to 0xFFECEBE8), "onAccent" to (c.onAccent to 0xFF1C1B19),
            "success" to (c.success to 0xFF4CC38A), "warning" to (c.warning to 0xFFD4A64A),
            "danger" to (c.danger to 0xFFF4817D), "diffAddFg" to (c.diffAddFg to 0xFF86DFB0),
            "diffDelFg" to (c.diffDelFg to 0xFFF5A3A1),
        )
        for ((name, pair) in expected) assertEquals(Color(pair.second), pair.first, name)
        assertEquals(Color.White.copy(alpha = 0.07f), c.line, "line")
        assertEquals(Color.White.copy(alpha = 0.12f), c.lineStrong, "lineStrong")
        assertEquals(Color.White.copy(alpha = 0.30f), c.lineFocus, "lineFocus")
        assertEquals(Color.Black.copy(alpha = 0.5f), c.scrim, "scrim")
    }

    @Test
    fun chartColoursReadAndStayApartInEveryTheme() {
        // The chart palette (PiColors.charts): each series colour is a line or a bar, so 3:1 (WCAG 1.4.11) on every
        // surface of every theme; it is its own set (no accent, tint or status colour); and every pair stays apart
        // (CIELAB ΔE at least 15) for normal vision and under simulated protanopia, deuteranopia and tritanopia.
        val failures = ArrayList<String>()
        for (theme in ThemeId.entries) {
            val c = PiColors.of(theme, AccentId.Theme)
            assertEquals(4, c.charts.toSet().size, "$theme chart colours")
            val reserved = c.tints + listOf(c.accent, c.success, c.warning, c.danger)
            c.charts.forEachIndexed { i, colour ->
                if (colour in reserved) failures += "$theme chart$i reuses a tint, the accent or a status colour"
                for ((name, bg) in surfaces(c)) {
                    val r = contrast(colour, bg)
                    if (r < 3.0) failures += "$theme chart$i on $name: ${"%.2f".format(r)}"
                }
            }
            for ((vision, matrix) in listOf<Pair<String, Array<DoubleArray>?>>("normal" to null) + CVD) {
                val labs = c.charts.map { lab(linear(it).let { v -> if (matrix == null) v else simulate(v, matrix) }) }
                for (a in labs.indices) for (b in a + 1 until labs.size) {
                    val d = deltaE(labs[a], labs[b])
                    if (d < 15.0) failures += "$theme chart$a / chart$b under $vision: ΔE ${"%.1f".format(d)}"
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun linear(c: Color): DoubleArray = listOf(c.red, c.green, c.blue).map { v ->
        val x = v.toDouble()
        if (x <= 0.04045) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
    }.toDoubleArray()

    private fun simulate(v: DoubleArray, m: Array<DoubleArray>): DoubleArray =
        DoubleArray(3) { i -> (m[i][0] * v[0] + m[i][1] * v[1] + m[i][2] * v[2]).coerceIn(0.0, 1.0) }

    /** CIELAB (D65) of a linear sRGB colour. */
    private fun lab(v: DoubleArray): DoubleArray {
        val x = (0.4124 * v[0] + 0.3576 * v[1] + 0.1805 * v[2]) / 0.95047
        val y = 0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2]
        val z = (0.0193 * v[0] + 0.1192 * v[1] + 0.9505 * v[2]) / 1.08883
        fun f(t: Double) = if (t > 0.008856) Math.cbrt(t) else 7.787 * t + 16.0 / 116.0
        return doubleArrayOf(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }

    private fun deltaE(a: DoubleArray, b: DoubleArray): Double = Math.sqrt((0..2).sumOf { (a[it] - b[it]) * (a[it] - b[it]) })

    /** Machado, Oliveira and Fernandes (2009), severity 1, on linear sRGB. */
    private val CVD = listOf(
        "protanopia" to arrayOf(doubleArrayOf(0.152286, 1.052583, -0.204868), doubleArrayOf(0.114503, 0.786281, 0.099216), doubleArrayOf(-0.003882, -0.048116, 1.051998)),
        "deuteranopia" to arrayOf(doubleArrayOf(0.367322, 0.860646, -0.227968), doubleArrayOf(0.280085, 0.672501, 0.047413), doubleArrayOf(-0.011820, 0.042940, 0.968881)),
        "tritanopia" to arrayOf(doubleArrayOf(1.255528, -0.076749, -0.178779), doubleArrayOf(-0.078411, 0.930809, 0.147602), doubleArrayOf(0.004733, 0.691367, 0.303900)),
    )

    @Test
    fun subagentTintsReadInEveryTheme() {
        // Five tints (apricot, rose, orchid, violet, seafoam) for parallel agents: a tinted Quad needs 3:1 on every
        // surface, and the avatar initial (the tint on the tint at 16%) AA on the canvas and on cards (surface1), where
        // agent cards sit. On Claude's lighter surface2 orchid and violet initials drop to 4.1 to 4.3:1.
        val failures = ArrayList<String>()
        for (theme in ThemeId.entries) {
            val c = PiColors.of(theme, AccentId.Theme)
            assertEquals(5, c.tints.toSet().size, "$theme tints")
            c.tints.forEachIndexed { i, tint ->
                for ((name, bg) in surfaces(c)) {
                    val g = contrast(tint, bg)
                    if (g < 3.0) failures += "$theme tint$i glyph on $name: ${"%.2f".format(g)}"
                }
                for ((name, bg) in surfaces(c).take(2)) {
                    val r = contrast(tint, tint.copy(alpha = 0.16f).compositeOver(bg))
                    if (r < 4.5) failures += "$theme tint$i initial over $name: ${"%.2f".format(r)}"
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
