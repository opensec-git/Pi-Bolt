package opensec.pibolt.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import opensec.pibolt.app.data.AccentId
import opensec.pibolt.app.data.ThemeId

/** The text selection's fill: the accent at this alpha (ink1 stays AA on it over every surface, ContrastTest). */
const val SELECTION_ALPHA = 0.28f

/**
 * Every colour the app draws. Text uses ink1…ink3 (ink4 is decorative only), colour is reserved for state, and the
 * accent marks what can be acted on. The default theme (Graphite, labelled Pi-Bolt) is a warm charcoal. Every
 * text ink passes WCAG AA (4.5:1) on canvas and on surface1 to surface3 in every theme (ContrastTest). See
 * android/DESIGN.md.
 */
@Immutable
data class PiColors(
    val canvas: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val ink1: Color,
    val ink2: Color,
    val ink3: Color,
    val ink4: Color,
    val line: Color,
    val lineStrong: Color,
    val lineFocus: Color,
    val press: Color,
    val selected: Color,
    val scrim: Color,
    val accent: Color,
    val onAccent: Color,
    val brand: Color = Brand,
    val success: Color = Success,
    val warning: Color = Warning,
    val danger: Color = Danger,
    val successSoft: Color = Success.copy(alpha = 0.14f),
    val warningSoft: Color = Warning.copy(alpha = 0.14f),
    val dangerSoft: Color = Danger.copy(alpha = 0.14f),
    val diffAddBg: Color = Success.copy(alpha = 0.11f),
    val diffAddFg: Color = Color(0xFF9BE6BF),
    val diffDelBg: Color = Danger.copy(alpha = 0.11f),
    val diffDelFg: Color = Color(0xFFF7A9A5),
    /** The changed words inside a paired delete/add line. */
    val diffAddWord: Color = Success.copy(alpha = 0.22f),
    val diffDelWord: Color = Danger.copy(alpha = 0.22f),
    val synString: Color = Color(0xFF9ED3C6),
    val synNumber: Color = Color(0xFFE5A97E),
    val synType: Color = Color(0xFFC9B8F0),
    /** Code colours for the highlighter's token kinds (see [SyntaxColors]). */
    val syntax: SyntaxColors = SyntaxColors.Pi,
    /** ANSI blue, magenta and cyan (terminal, tool output): colours with no status meaning. */
    val ansiBlue: Color = syntax.function,
    val ansiMagenta: Color = syntax.keyword,
    val ansiCyan: Color = syntax.operator,
    /**
     * Subagent tints, one per parallel agent (Pi-Bolt Desktop's --tint-0…4: apricot, rose, orchid, violet, seafoam, the
     * same OKLCH lightness and chroma, hues clear of the status colours, the brand and blue). Never state. See [tints].
     */
    val tint0: Color = Tints[0],
    val tint1: Color = Tints[1],
    val tint2: Color = Tints[2],
    val tint3: Color = Tints[3],
    val tint4: Color = Tints[4],
    /**
     * Chart series colours, one per series in order (the mobile UI kit's `chart`): Okabe–Ito's colour-blind-safe blue,
     * orange, reddish purple and bluish green, deepened on Paper. Data only, never state, never an agent. See [charts].
     */
    val chart0: Color = Charts[0],
    val chart1: Color = Charts[1],
    val chart2: Color = Charts[2],
    val chart3: Color = Charts[3],
) {
    /** A light theme (Paper): Material's light scheme and dark system bar icons. */
    val isLight: Boolean get() = canvas.luminance() > 0.5f

    /** [tint0]…[tint4] in order: agent n takes `tints[n % 5]`. */
    val tints: List<Color> get() = listOf(tint0, tint1, tint2, tint3, tint4)

    /** [chart0]…[chart3] in order: series n takes `charts[n % 4]`. */
    val charts: List<Color> get() = listOf(chart0, chart1, chart2, chart3)

    companion object {
        val Brand = Color(0xFFFFD84D)
        val Success = Color(0xFF5FD39A)
        val Warning = Color(0xFFE8B04B)
        val Danger = Color(0xFFF2706B)
        private val ClaudeDanger = Color(0xFFFF8681)

        /** oklch(0.8 0.13 55), (0.78 0.14 0), (0.76 0.15 325), (0.74 0.14 298), (0.8 0.11 185) in sRGB. */
        val Tints = listOf(Color(0xFFFEA668), Color(0xFFFF8FB4), Color(0xFFE08EE5), Color(0xFFB696F7), Color(0xFF5CD5C7))

        /** The default theme's tints for light themes: same hues, lightness 0.50 (seafoam 0.47) so an initial reads. */
        val LightTints = listOf(Color(0xFF994A00), Color(0xFFA33460), Color(0xFF8E3B94), Color(0xFF6F4BAC), Color(0xFF006B61))

        /**
         * The chart colours on dark grounds: Okabe–Ito sky blue, orange, reddish purple and bluish green, as published.
         * 3:1 on every dark surface; every pair stays apart (CIELAB ΔE at least 15) for normal vision and under
         * simulated protanopia, deuteranopia and tritanopia (ContrastTest).
         */
        val Charts = listOf(Color(0xFF56B4E9), Color(0xFFE69F00), Color(0xFFCC79A7), Color(0xFF009E73))

        /** The chart colours on Paper: the same hues deepened to 3:1 on its greys and kept apart for colour-blind eyes. */
        val LightCharts = listOf(Color(0xFF005E93), Color(0xFF665A05), Color(0xFF964966), Color(0xFF089656))

        fun of(theme: ThemeId, accent: AccentId): PiColors {
            val base = when (theme) {
                ThemeId.Graphite -> warm()
                ThemeId.Paper -> paper()
                ThemeId.Contrast -> contrast()
                ThemeId.Lime -> lime()
                ThemeId.Moss -> moss()
                ThemeId.Ember -> base(0xFF15130F, 0xFF1C1A16, 0xFF24211C, 0xFF2C2822, 0xFFEFE9DD, 0xFFB9B1A3, 0xFF979084, 0xFF5A554D, 0.10f, 0.17f, 0.56f)
                    .withSyntax(SyntaxColors.Ember)
                // Warm charcoal with cream ink.
                ThemeId.Claude -> base(0xFF1F1E1D, 0xFF262624, 0xFF30302E, 0xFF3A3936, 0xFFF5F4EE, 0xFFC3C0B6, 0xFFA6A49C, 0xFF5E5D59, 0.10f, 0.17f, 0.60f)
                    // The lighter canvas needs a lighter danger so a danger button (danger on dangerSoft) passes on sheets.
                    .copy(danger = ClaudeDanger, dangerSoft = ClaudeDanger.copy(alpha = 0.12f))
                    .withSyntax(SyntaxColors.Claude)
                // Neutral near-black, no tint.
                ThemeId.Codex -> base(0xFF0D0D0D, 0xFF171717, 0xFF212121, 0xFF2A2A2A, 0xFFECECEC, 0xFFB4B4B4, 0xFF929292, 0xFF545454, 0.10f, 0.17f, 0.60f)
                    .withSyntax(SyntaxColors.Pi)
                // Pure black; borders do the separating.
                ThemeId.Vercel -> base(0xFF000000, 0xFF0F0F0F, 0xFF111111, 0xFF1A1A1A, 0xFFEDEDED, 0xFFA1A1A1, 0xFF8F8F8F, 0xFF454545, 0.12f, 0.20f, 0.70f)
                    .withSyntax(SyntaxColors.Vercel)
                // GitHub's dark canvas and borders.
                ThemeId.GitHub -> base(0xFF0D1117, 0xFF151B23, 0xFF1C2128, 0xFF262C36, 0xFFF0F6FC, 0xFFB7BDC8, 0xFF9198A1, 0xFF3D444D, 0.10f, 0.18f, 0.60f)
                    .withSyntax(SyntaxColors.GitHub)
            }
            val light = base.isLight
            return when (accent) {
                AccentId.Theme -> when (theme) {
                    // These five carry their own accent.
                    ThemeId.Graphite, ThemeId.Paper, ThemeId.Contrast, ThemeId.Lime, ThemeId.Moss -> base
                    ThemeId.Claude -> base.copy(accent = Color(0xFFE08566), onAccent = Color(0xFF1F1E1D))
                    ThemeId.GitHub -> base.copy(accent = Color(0xFF4493F8), onAccent = Color(0xFF0D1117))
                    else -> base.copy(accent = base.ink1, onAccent = base.canvas)
                }
                AccentId.Ink -> base.copy(accent = base.ink1, onAccent = base.canvas)
                // On a light canvas the pastel accents would not read as text; the deep variants keep the hue.
                AccentId.Iris -> if (light) base.copy(accent = Color(0xFF5B4BD1), onAccent = Color.White) else base.copy(accent = Color(0xFFA99CFF), onAccent = Color(0xFF120F2A))
                AccentId.Glacier -> if (light) base.copy(accent = Color(0xFF0B6E9E), onAccent = Color.White) else base.copy(accent = Color(0xFF7CC4F0), onAccent = Color(0xFF06202E))
            }
        }

        /** The ANSI colours follow the syntax palette unless a theme sets its own. */
        private fun PiColors.withSyntax(s: SyntaxColors) = copy(syntax = s, ansiBlue = s.function, ansiMagenta = s.keyword, ansiCyan = s.operator)

        /**
         * A theme spelled out in full: surfaces, the four inks, lines and overlays as [overlay] at the given alphas
         * (white on dark canvases, black on light ones), the accent, status, diff, syntax, ANSI and tints.
         */
        private fun full(
            surfaces: List<Long>,
            inks: List<Long>,
            overlay: Color,
            line: Float,
            lineStrong: Float,
            lineFocus: Float,
            press: Float,
            selected: Float,
            scrim: Float,
            accent: Long,
            onAccent: Long,
            status: List<Long>,
            soft: Float,
            diff: List<Long>,
            diffBg: Float,
            syntax: SyntaxColors,
            ansi: List<Long>,
            tints: List<Color> = Tints,
            charts: List<Color> = Charts,
        ): PiColors {
            val success = Color(status[0])
            val warning = Color(status[1])
            val danger = Color(status[2])
            return PiColors(
                canvas = Color(surfaces[0]),
                surface1 = Color(surfaces[1]),
                surface2 = Color(surfaces[2]),
                surface3 = Color(surfaces[3]),
                ink1 = Color(inks[0]),
                ink2 = Color(inks[1]),
                ink3 = Color(inks[2]),
                ink4 = Color(inks[3]),
                line = overlay.copy(alpha = line),
                lineStrong = overlay.copy(alpha = lineStrong),
                lineFocus = overlay.copy(alpha = lineFocus),
                press = overlay.copy(alpha = press),
                selected = overlay.copy(alpha = selected),
                scrim = Color.Black.copy(alpha = scrim),
                accent = Color(accent),
                onAccent = Color(onAccent),
                success = success,
                warning = warning,
                danger = danger,
                successSoft = success.copy(alpha = soft),
                warningSoft = warning.copy(alpha = soft),
                dangerSoft = danger.copy(alpha = soft),
                diffAddBg = success.copy(alpha = diffBg),
                diffAddFg = Color(diff[0]),
                diffDelBg = danger.copy(alpha = diffBg),
                diffDelFg = Color(diff[1]),
                diffAddWord = success.copy(alpha = diffBg * 2.4f),
                diffDelWord = danger.copy(alpha = diffBg * 2.4f),
                synString = syntax.string,
                synNumber = syntax.number,
                synType = syntax.type,
                syntax = syntax,
                ansiBlue = Color(ansi[0]),
                ansiMagenta = Color(ansi[1]),
                ansiCyan = Color(ansi[2]),
                tint0 = tints[0],
                tint1 = tints[1],
                tint2 = tints[2],
                tint3 = tints[3],
                tint4 = tints[4],
                chart0 = charts[0],
                chart1 = charts[1],
                chart2 = charts[2],
                chart3 = charts[3],
            )
        }

        /**
         * Pi-Bolt, the default: warm charcoal. A charcoal canvas (#1C1B19) with surfaces a warm step above it, quiet
         * warm-grey ink, hairlines and overlays as white alphas, the warm white ink as the only accent, green, amber
         * and red for state only, and no blue. The inks are lifted from the reference's #A8A6A1 / #77756F so ink2
         * passes AA on a pressed menu row (4.56:1) and ink3 on surface3 (4.62:1).
         */
        private fun warm() = full(
            surfaces = listOf(0xFF1C1B19, 0xFF232220, 0xFF2A2926, 0xFF32312E),
            inks = listOf(0xFFECEBE8, 0xFFB3B1AB, 0xFF9C9A94, 0xFF5E5C57),
            overlay = Color.White, line = 0.07f, lineStrong = 0.12f, lineFocus = 0.30f, press = 0.07f, selected = 0.09f, scrim = 0.50f,
            accent = 0xFFECEBE8, onAccent = 0xFF1C1B19,
            // Danger is lifted from the desktop's #EF6461 so it stays AA on its own 12% fill over the warm sheets.
            status = listOf(0xFF4CC38A, 0xFFD4A64A, 0xFFF4817D), soft = 0.12f,
            diff = listOf(0xFF86DFB0, 0xFFF5A3A1), diffBg = 0.09f,
            syntax = SyntaxColors.Desktop,
            ansi = listOf(0xFFC9C9C9, 0xFFD7A6C8, 0xFFB8C4C4),
        )

        /**
         * Paper: the desktop's light mode. White canvas, cards and sheets stepping down to a soft grey, near-black ink and
         * accent, black alphas for lines and overlays, and status colours deep enough to read as text on paper.
         */
        private fun paper() = full(
            surfaces = listOf(0xFFFFFFFF, 0xFFF7F7F7, 0xFFF0F0F0, 0xFFE8E8E8),
            inks = listOf(0xFF171717, 0xFF525252, 0xFF666666, 0xFF949494),
            overlay = Color.Black, line = 0.08f, lineStrong = 0.14f, lineFocus = 0.35f, press = 0.06f, selected = 0.08f, scrim = 0.24f,
            accent = 0xFF0A0A0A, onAccent = 0xFFFFFFFF,
            status = listOf(0xFF11733F, 0xFF8A6410, 0xFFB83430), soft = 0.09f,
            diff = listOf(0xFF116B40, 0xFFA2302C), diffBg = 0.08f,
            syntax = SyntaxColors.Paper,
            ansi = listOf(0xFF4D4D4D, 0xFF8C3A73, 0xFF39605F),
            tints = LightTints,
            charts = LightCharts,
        )

        /**
         * Contrast: high-contrast mono. Pure black, white ink and accent, secondary ink still at 7:1 (AAA) on every
         * surface, stronger lines and overlays, brighter status and syntax colours, all at 7:1 on cards and sheets.
         */
        private fun contrast() = full(
            surfaces = listOf(0xFF000000, 0xFF0A0A0A, 0xFF141414, 0xFF1F1F1F),
            inks = listOf(0xFFFFFFFF, 0xFFDADADA, 0xFFB8B8B8, 0xFF7A7A7A),
            overlay = Color.White, line = 0.22f, lineStrong = 0.36f, lineFocus = 0.75f, press = 0.12f, selected = 0.16f, scrim = 0.75f,
            accent = 0xFFFFFFFF, onAccent = 0xFF000000,
            status = listOf(0xFF6EE7A8, 0xFFFFCB5C, 0xFFFF8A85), soft = 0.16f,
            diff = listOf(0xFFB4F5D2, 0xFFFFC2BF), diffBg = 0.16f,
            syntax = SyntaxColors.Contrast,
            ansi = listOf(0xFFE0E0E0, 0xFFF0B8E0, 0xFFC8E8E8),
        )

        /** Lime: a soft neutral dark (lighter than Codex) with OpenSec lime `#BAFC6B` as the accent only. */
        private fun lime() = full(
            surfaces = listOf(0xFF1A1A1A, 0xFF202020, 0xFF272727, 0xFF2D2D2D),
            inks = listOf(0xFFF0F0F0, 0xFFB8B8B8, 0xFF9A9A9A, 0xFF5C5C5C),
            overlay = Color.White, line = 0.09f, lineStrong = 0.15f, lineFocus = 0.36f, press = 0.07f, selected = 0.10f, scrim = 0.60f,
            accent = 0xFFBAFC6B, onAccent = 0xFF16210A,
            // The lighter canvas needs Claude's lighter danger so a danger button passes on sheets.
            status = listOf(0xFF5FD39A, 0xFFE8B04B, 0xFFFF8681), soft = 0.12f,
            diff = listOf(0xFF9BE6BF, 0xFFF7A9A5), diffBg = 0.11f,
            syntax = SyntaxColors.Lime,
            ansi = listOf(0xFFC4C9D4, 0xFFD7A6C8, 0xFFA6DCC3),
        )

        /** Moss: a green-black forest dark. Lichen-grey ink, a soft fern accent, earthy ochre, straw and clay in code. */
        private fun moss() = full(
            surfaces = listOf(0xFF0C100D, 0xFF121814, 0xFF19201B, 0xFF212A23),
            inks = listOf(0xFFE4EBE1, 0xFFA9B4A5, 0xFF8D998A, 0xFF4F5A4D),
            overlay = Color(0xFFE4EBE1), line = 0.10f, lineStrong = 0.17f, lineFocus = 0.40f, press = 0.07f, selected = 0.11f, scrim = 0.60f,
            accent = 0xFFB4D39A, onAccent = 0xFF0C100D,
            status = listOf(0xFF5FD39A, 0xFFE8B04B, 0xFFF2706B), soft = 0.14f,
            diff = listOf(0xFF9BE6BF, 0xFFF7A9A5), diffBg = 0.11f,
            syntax = SyntaxColors.Moss,
            ansi = listOf(0xFF8FB3C7, 0xFFC9A3C8, 0xFF8FC7B0),
        )

        private fun base(
            canvas: Long,
            s1: Long,
            s2: Long,
            s3: Long,
            i1: Long,
            i2: Long,
            i3: Long,
            i4: Long,
            line: Float,
            lineStrong: Float,
            scrim: Float,
        ): PiColors {
            val ink1 = Color(i1)
            return PiColors(
                canvas = Color(canvas),
                surface1 = Color(s1),
                surface2 = Color(s2),
                surface3 = Color(s3),
                ink1 = ink1,
                ink2 = Color(i2),
                ink3 = Color(i3),
                ink4 = Color(i4),
                line = ink1.copy(alpha = line),
                lineStrong = ink1.copy(alpha = lineStrong),
                lineFocus = ink1.copy(alpha = 0.40f),
                press = ink1.copy(alpha = 0.07f),
                selected = ink1.copy(alpha = 0.11f),
                scrim = Color.Black.copy(alpha = scrim),
                accent = ink1,
                onAccent = Color(canvas),
            )
        }
    }
}
