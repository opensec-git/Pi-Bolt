package opensec.pibolt.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
    /** Comments: the theme's ink3 (a neutral grey for the Pi palette), italic. */
    val comment: Color,
    /** Keywords and constants in a heavier weight where their colour alone is close to the text (Desktop). */
    val keywordWeight: FontWeight? = null,
) {
    companion object {
        /**
         * Graphite: Pi-Bolt Desktop's highlight.js theme (`src/styles/app.css`), its greys warmed to the default's inks. White keywords and
         * constants a weight up, near-white calls, warm sand types, green strings, orange numbers, greys for the rest.
         * No blue.
         */
        val Desktop = SyntaxColors(
            keyword = Color(0xFFFFFFFF),
            type = Color(0xFFDCC38C),
            function = Color(0xFFF0F0F0),
            string = Color(0xFF9FD4A8),
            number = Color(0xFFE9B77C),
            constant = Color(0xFFFFFFFF),
            operator = Color(0xFFB3B1AB),
            attribute = Color(0xFFC4C4C4),
            tag = Color(0xFFB3B1AB),
            regex = Color(0xFF9FD4A8),
            escape = Color(0xFFE9B77C),
            variable = Color(0xFFC4C4C4),
            comment = Color(0xFF9C9A94),
            keywordWeight = FontWeight.Medium,
        )

        /** Paper: the desktop's light highlight.js theme, deepened where a colour missed AA on surface2. */
        val Paper = SyntaxColors(
            keyword = Color(0xFF000000),
            type = Color(0xFF7D600F),
            function = Color(0xFF171717),
            string = Color(0xFF2F7A3E),
            number = Color(0xFFA5571A),
            constant = Color(0xFF000000),
            operator = Color(0xFF5A5A5A),
            attribute = Color(0xFF404040),
            tag = Color(0xFF5A5A5A),
            regex = Color(0xFF2F7A3E),
            escape = Color(0xFFA5571A),
            variable = Color(0xFF404040),
            comment = Color(0xFF666666),
            keywordWeight = FontWeight.Medium,
        )

        /** Contrast: white keywords a weight up and bright, clear hues, every one at 7:1 (AAA) on cards and sheets. */
        val Contrast = SyntaxColors(
            keyword = Color(0xFFFFFFFF),
            type = Color(0xFFFFE08A),
            function = Color(0xFFFFFFFF),
            string = Color(0xFF9FF0C0),
            number = Color(0xFFFFB98A),
            constant = Color(0xFFFFB98A),
            operator = Color(0xFFDADADA),
            attribute = Color(0xFFE0E0E0),
            tag = Color(0xFFFFE08A),
            regex = Color(0xFF9FF0C0),
            escape = Color(0xFFFFB98A),
            variable = Color(0xFFF0C8E8),
            comment = Color(0xFFB8B8B8),
            keywordWeight = FontWeight.SemiBold,
        )

        /** Lime: soft lavender, apricot, mint and coral; lime stays the accent and never appears in code. */
        val Lime = SyntaxColors(
            keyword = Color(0xFFC3B1F5),
            type = Color(0xFFF2C48D),
            function = Color(0xFFE8E8E8),
            string = Color(0xFFA6DCC3),
            number = Color(0xFFF5A7A0),
            constant = Color(0xFFF5A7A0),
            operator = Color(0xFFB8B8B8),
            attribute = Color(0xFFC3B1F5),
            tag = Color(0xFFF2C48D),
            regex = Color(0xFFA6DCC3),
            escape = Color(0xFFF2C48D),
            variable = Color(0xFFF5A7A0),
            comment = Color(0xFF9A9A9A),
        )

        /** Moss: earthy, a forest floor. Ochre keywords, straw types, lichen calls, moss strings, clay numbers. */
        val Moss = SyntaxColors(
            keyword = Color(0xFFD6A86C),
            type = Color(0xFFE3CF8C),
            function = Color(0xFF9FC7B8),
            string = Color(0xFFB9D38A),
            number = Color(0xFFE59A7A),
            constant = Color(0xFFE59A7A),
            operator = Color(0xFFA9B4A5),
            attribute = Color(0xFFC4B48A),
            tag = Color(0xFFD6A86C),
            regex = Color(0xFF8FC7B0),
            escape = Color(0xFFE3CF8C),
            variable = Color(0xFFD9A3A0),
            comment = Color(0xFF8D998A),
        )

        /** Codex: pastels on neutral greys; the comment grey is neutral too (no blue cast). */
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
            comment = Color(0xFF8F8E8C),
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
            operator = Color(0xFFB3B1AB),
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
            ThemeId.Graphite -> Desktop
            ThemeId.Paper -> Paper
            ThemeId.Contrast -> Contrast
            ThemeId.Lime -> Lime
            ThemeId.Moss -> Moss
            ThemeId.Codex -> Pi
            ThemeId.Ember -> Ember
            ThemeId.Claude -> Claude
            ThemeId.Vercel -> Vercel
            ThemeId.GitHub -> GitHub
        }
    }
}
