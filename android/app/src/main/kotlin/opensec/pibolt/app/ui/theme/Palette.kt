package opensec.pibolt.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import opensec.pibolt.app.data.AccentId
import opensec.pibolt.app.data.ThemeId

/**
 * Every colour the app draws. Text uses ink1…ink3 (ink4 is decorative only), colour is reserved for state, and the
 * accent marks what can be acted on. The default theme (Graphite, labelled Pi-Bolt) is Pi-Bolt Desktop's palette. Every
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
) {
    /** [tint0]…[tint4] in order: agent n takes `tints[n % 5]`. */
    val tints: List<Color> get() = listOf(tint0, tint1, tint2, tint3, tint4)

    companion object {
        val Brand = Color(0xFFFFD84D)
        val Success = Color(0xFF5FD39A)
        val Warning = Color(0xFFE8B04B)
        val Danger = Color(0xFFF2706B)
        private val ClaudeDanger = Color(0xFFFF8681)

        /** oklch(0.8 0.13 55), (0.78 0.14 0), (0.76 0.15 325), (0.74 0.14 298), (0.8 0.11 185) in sRGB. */
        val Tints = listOf(Color(0xFFFEA668), Color(0xFFFF8FB4), Color(0xFFE08EE5), Color(0xFFB696F7), Color(0xFF5CD5C7))

        fun of(theme: ThemeId, accent: AccentId): PiColors {
            val base = when (theme) {
                ThemeId.Graphite -> desktop()
                ThemeId.Void -> base(0xFF000000, 0xFF0D0E10, 0xFF101113, 0xFF17191C, 0xFFE8EAED, 0xFFA3A8B0, 0xFF7D838C, 0xFF4C5158, 0.11f, 0.19f, 0.70f)
                ThemeId.Ember -> base(0xFF15130F, 0xFF1C1A16, 0xFF24211C, 0xFF2C2822, 0xFFEFE9DD, 0xFFB9B1A3, 0xFF979084, 0xFF5A554D, 0.10f, 0.17f, 0.56f)
                // Warm charcoal with cream ink.
                ThemeId.Claude -> base(0xFF1F1E1D, 0xFF262624, 0xFF30302E, 0xFF3A3936, 0xFFF5F4EE, 0xFFC3C0B6, 0xFFA6A49C, 0xFF5E5D59, 0.10f, 0.17f, 0.60f)
                    // The lighter canvas needs a lighter danger so a danger button (danger on dangerSoft) passes on sheets.
                    .copy(danger = ClaudeDanger, dangerSoft = ClaudeDanger.copy(alpha = 0.12f))
                // Neutral near-black, no tint.
                ThemeId.Codex -> base(0xFF0D0D0D, 0xFF171717, 0xFF212121, 0xFF2A2A2A, 0xFFECECEC, 0xFFB4B4B4, 0xFF929292, 0xFF545454, 0.10f, 0.17f, 0.60f)
                // Pure black; borders do the separating.
                ThemeId.Vercel -> base(0xFF000000, 0xFF0F0F0F, 0xFF111111, 0xFF1A1A1A, 0xFFEDEDED, 0xFFA1A1A1, 0xFF8F8F8F, 0xFF454545, 0.12f, 0.20f, 0.70f)
                // GitHub's dark canvas and borders.
                ThemeId.GitHub -> base(0xFF0D1117, 0xFF151B23, 0xFF1C2128, 0xFF262C36, 0xFFF0F6FC, 0xFFB7BDC8, 0xFF9198A1, 0xFF3D444D, 0.10f, 0.18f, 0.60f)
            }.let { if (theme == ThemeId.Graphite) it else it.withSyntax(SyntaxColors.of(theme)) }
            return when (accent) {
                AccentId.Theme -> when (theme) {
                    ThemeId.Graphite -> base
                    ThemeId.Claude -> base.copy(accent = Color(0xFFE08566), onAccent = Color(0xFF1F1E1D))
                    ThemeId.GitHub -> base.copy(accent = Color(0xFF4493F8), onAccent = Color(0xFF0D1117))
                    else -> base.copy(accent = base.ink1, onAccent = base.canvas)
                }
                AccentId.Ink -> base.copy(accent = base.ink1, onAccent = base.canvas)
                AccentId.Iris -> base.copy(accent = Color(0xFFA99CFF), onAccent = Color(0xFF120F2A))
                AccentId.Glacier -> base.copy(accent = Color(0xFF7CC4F0), onAccent = Color(0xFF06202E))
            }
        }

        /** The ANSI colours follow the syntax palette unless a theme sets its own. */
        private fun PiColors.withSyntax(s: SyntaxColors) = copy(syntax = s, ansiBlue = s.function, ansiMagenta = s.keyword, ansiCyan = s.operator)

        /**
         * Pi-Bolt Desktop's palette (pi-bolt-desktop `src/styles/tokens.css`), 1:1: a pure black canvas, one neutral
         * grey scale, hairlines and overlays as white alphas, white as the only accent, green, amber and red for state
         * only, and no blue anywhere (ANSI blue and cyan are kept off-blue too). One change: ink3 is `#858585`, not
         * `#808080`, so it passes AA on surface3 (4.57:1; `#808080` measures 4.27).
         */
        private fun desktop(): PiColors {
            val white = Color.White
            val success = Color(0xFF4CC38A)
            val warning = Color(0xFFD4A64A)
            val danger = Color(0xFFEF6461)
            val syntax = SyntaxColors.Desktop
            return PiColors(
                canvas = Color(0xFF000000),
                surface1 = Color(0xFF0E0E0E),
                surface2 = Color(0xFF161616),
                surface3 = Color(0xFF1D1D1D),
                ink1 = Color(0xFFEDEDED),
                ink2 = Color(0xFFA1A1A1),
                ink3 = Color(0xFF858585),
                ink4 = Color(0xFF595959),
                line = white.copy(alpha = 0.08f),
                lineStrong = white.copy(alpha = 0.14f),
                lineFocus = white.copy(alpha = 0.32f),
                press = white.copy(alpha = 0.085f),
                selected = white.copy(alpha = 0.10f),
                scrim = Color.Black.copy(alpha = 0.50f),
                accent = Color(0xFFFAFAFA),
                onAccent = Color(0xFF000000),
                success = success,
                warning = warning,
                danger = danger,
                successSoft = success.copy(alpha = 0.12f),
                warningSoft = warning.copy(alpha = 0.12f),
                dangerSoft = danger.copy(alpha = 0.12f),
                diffAddBg = success.copy(alpha = 0.09f),
                diffAddFg = Color(0xFF86DFB0),
                diffDelBg = danger.copy(alpha = 0.09f),
                diffDelFg = Color(0xFFF5A3A1),
                diffAddWord = success.copy(alpha = 0.22f),
                diffDelWord = danger.copy(alpha = 0.22f),
                synString = syntax.string,
                synNumber = syntax.number,
                synType = syntax.type,
                syntax = syntax,
                ansiBlue = Color(0xFFC9C9C9),
                ansiMagenta = Color(0xFFD7A6C8),
                ansiCyan = Color(0xFFB8C4C4),
            )
        }

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
