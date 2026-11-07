package opensec.pibolt.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import opensec.pibolt.app.data.AccentId
import opensec.pibolt.app.data.ThemeId

/**
 * Every colour the app draws. Text uses ink1…ink3 (ink4 is decorative only), colour is reserved for state, and the
 * accent marks what can be acted on. Every text ink passes WCAG AA (4.5:1) on canvas and on surface1 to surface3 in every
 * theme (ContrastTest). See android/DESIGN.md.
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
) {
    companion object {
        val Brand = Color(0xFFFFD84D)
        val Success = Color(0xFF5FD39A)
        val Warning = Color(0xFFE8B04B)
        val Danger = Color(0xFFF2706B)
        private val ClaudeDanger = Color(0xFFFF8681)

        fun of(theme: ThemeId, accent: AccentId): PiColors {
            val base = when (theme) {
                ThemeId.Graphite -> base(0xFF0B0C0E, 0xFF121417, 0xFF191C20, 0xFF21252A, 0xFFE8EAED, 0xFFA3A8B0, 0xFF878D96, 0xFF4C5158, 0.09f, 0.16f, 0.56f)
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
            }.copy(syntax = SyntaxColors.of(theme))
            return when (accent) {
                AccentId.Theme -> when (theme) {
                    ThemeId.Claude -> base.copy(accent = Color(0xFFD97757), onAccent = Color(0xFF1F1E1D))
                    ThemeId.GitHub -> base.copy(accent = Color(0xFF4493F8), onAccent = Color(0xFF0D1117))
                    else -> base.copy(accent = base.ink1, onAccent = base.canvas)
                }
                AccentId.Ink -> base.copy(accent = base.ink1, onAccent = base.canvas)
                AccentId.Iris -> base.copy(accent = Color(0xFFA99CFF), onAccent = Color(0xFF120F2A))
                AccentId.Glacier -> base.copy(accent = Color(0xFF7CC4F0), onAccent = Color(0xFF06202E))
            }
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
