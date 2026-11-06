package opensec.pibolt.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import opensec.pibolt.app.data.ThemeId

/**
 * Code colours per theme, one per highlighter token kind. Punctuation uses the theme's ink2 (see [PiColors]). Every
 * colour passes WCAG AA (4.5:1) on canvas, surface1 and surface2 of its theme (SyntaxColorsTest).
 */
@Immutable
data class SyntaxColors(
    val keyword: Color,
    val type: Color,
    val function: Color,
    val string: Color,
    val number: Color,
    val constant: Color,
    val operator: Color,
    val attribute: Color,
    val tag: Color,
    val regex: Color,
    val escape: Color,
    val variable: Color,
    /** Comments: the theme's ink3, a step lighter where ink3 misses AA on surface2 (Graphite). */
    val comment: Color,
) {
    companion object {
        /** Graphite, Void and Codex: cool pastels on neutral greys. */
        val Pi = SyntaxColors(
            keyword = Color(0xFFC9A0F5),
            type = Color(0xFFE8C67E),
            function = Color(0xFF7FB6F5),
            string = Color(0xFFA3D58E),
            number = Color(0xFFE9A77C),
            constant = Color(0xFFE9A77C),
            operator = Color(0xFF86CBDD),
            attribute = Color(0xFFE9A77C),
            tag = Color(0xFFF08E9A),
            regex = Color(0xFF86CBDD),
            escape = Color(0xFF86CBDD),
            variable = Color(0xFFF08E9A),
            comment = Color(0xFF858B94),
        )

        /** Ember: warm, earthy (in the spirit of Gruvbox). */
        val Ember = SyntaxColors(
            keyword = Color(0xFFF28E7E),
            type = Color(0xFFF2C46A),
            function = Color(0xFF9CC78B),
            string = Color(0xFFC8CC6E),
            number = Color(0xFFD99BB8),
            constant = Color(0xFFD99BB8),
            operator = Color(0xFFF0A35C),
            attribute = Color(0xFF8DB8A8),
            tag = Color(0xFFF28E7E),
            regex = Color(0xFF9CC78B),
            escape = Color(0xFFF0A35C),
            variable = Color(0xFF8DB8A8),
            comment = Color(0xFF8E877B),
        )

        /** Claude: coral keywords, sand types, sage strings on warm charcoal. */
        val Claude = SyntaxColors(
            keyword = Color(0xFFE8956F),
            type = Color(0xFFE3C590),
            function = Color(0xFF9DBAE8),
            string = Color(0xFFB6CF8F),
            number = Color(0xFFD9A3D9),
            constant = Color(0xFFD9A3D9),
            operator = Color(0xFFC3C0B6),
            attribute = Color(0xFFE3C590),
            tag = Color(0xFFE8956F),
            regex = Color(0xFF8FCBC0),
            escape = Color(0xFF8FCBC0),
            variable = Color(0xFF9DBAE8),
            comment = Color(0xFF9C9A92),
        )

        /** Vercel: Geist-like pink, purple, blue and green on black. */
        val Vercel = SyntaxColors(
            keyword = Color(0xFFFF6B98),
            type = Color(0xFFC49BFF),
            function = Color(0xFF6CB4FF),
            string = Color(0xFF6CD58B),
            number = Color(0xFFF5B562),
            constant = Color(0xFF6CB4FF),
            operator = Color(0xFFA1A1A1),
            attribute = Color(0xFFC49BFF),
            tag = Color(0xFF6CD58B),
            regex = Color(0xFFF5B562),
            escape = Color(0xFFF5B562),
            variable = Color(0xFFFF9F6B),
            comment = Color(0xFF8F8F8F),
        )

        /** GitHub: the dark default "prettylights" roles. */
        val GitHub = SyntaxColors(
            keyword = Color(0xFFFF7B72),
            type = Color(0xFFFFA657),
            function = Color(0xFFD2A8FF),
            string = Color(0xFFA5D6FF),
            number = Color(0xFF79C0FF),
            constant = Color(0xFF79C0FF),
            operator = Color(0xFFFF7B72),
            attribute = Color(0xFF79C0FF),
            tag = Color(0xFF7EE787),
            regex = Color(0xFF7EE787),
            escape = Color(0xFF7EE787),
            variable = Color(0xFFFFA657),
            comment = Color(0xFF9198A1),
        )

        fun of(theme: ThemeId): SyntaxColors = when (theme) {
            ThemeId.Graphite, ThemeId.Void, ThemeId.Codex -> Pi
            ThemeId.Ember -> Ember
            ThemeId.Claude -> Claude
            ThemeId.Vercel -> Vercel
            ThemeId.GitHub -> GitHub
        }
    }
}
