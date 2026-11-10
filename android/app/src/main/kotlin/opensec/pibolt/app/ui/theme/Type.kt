package opensec.pibolt.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import opensec.pibolt.app.R

/** Inter (UI and prose), bundled static weights. */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** JetBrains Mono (code, paths, terminal). Always used with [MonoFeatures]: ligatures off. */
val Mono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

const val MonoFeatures = "'liga' 0, 'calt' 0"

/** Tabular figures for numbers that tick. */
const val Tnum = "'tnum' 1"

private val Centered = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun inter(size: Float, line: Float, weight: FontWeight, tracking: TextUnit = 0.sp) = TextStyle(
    fontFamily = Inter,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = tracking,
    lineHeightStyle = Centered,
)

private fun mono(size: Float, line: Float) = TextStyle(
    fontFamily = Mono,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = FontWeight.Normal,
    fontFeatureSettings = MonoFeatures,
    lineHeightStyle = Centered,
)

/** The type scale from DESIGN.md. */
@Immutable
data class PiType(
    val display: TextStyle,
    val title: TextStyle,
    val headline: TextStyle,
    val subhead: TextStyle,
    val prose: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val label: TextStyle,
    val meta: TextStyle,
    val metaStrong: TextStyle,
    val micro: TextStyle,
    val mono: TextStyle,
    val monoSmall: TextStyle,
    val monoMeta: TextStyle,
    val thinking: TextStyle,
    /** Screen titles of the redesign ("Projects", a welcome): 28 / 34, SemiBold, tracked in. */
    val largeTitle: TextStyle,
    /** A list row's title in the redesign's lists, Recents and composer pill: 17 / 24. */
    val rowTitle: TextStyle,
    /** The line under a row title, tile labels and counts: 15 / 20. */
    val rowMeta: TextStyle,
) {
    /** Text-size setting on top of the system scale (transcript only). */
    fun scaled(factor: Float): PiType {
        if (factor == 1f) return this
        fun TextStyle.s() = copy(fontSize = fontSize * factor, lineHeight = lineHeight * factor)
        return PiType(display, title, headline.s(), subhead.s(), prose.s(), body.s(), bodyStrong.s(), label.s(), meta.s(), metaStrong.s(), micro.s(), mono.s(), monoSmall.s(), monoMeta.s(), thinking.s(), largeTitle, rowTitle, rowMeta)
    }

    companion object {
        fun build(compact: Boolean) = PiType(
            display = inter(26f, 32f, FontWeight.SemiBold, (-0.005).em),
            title = inter(20f, 26f, FontWeight.SemiBold, (-0.003).em),
            headline = inter(19f, 26f, FontWeight.SemiBold),
            subhead = inter(17f, 24f, FontWeight.SemiBold),
            prose = if (compact) inter(15f, 23f, FontWeight.Normal) else inter(16f, 25f, FontWeight.Normal),
            body = inter(15f, 22f, FontWeight.Normal),
            bodyStrong = inter(15f, 22f, FontWeight.SemiBold),
            label = inter(14f, 20f, FontWeight.Medium),
            meta = inter(12.5f, 17f, FontWeight.Normal),
            metaStrong = inter(12.5f, 17f, FontWeight.Medium),
            micro = inter(11f, 14f, FontWeight.Medium),
            mono = mono(13.5f, 20f),
            monoSmall = mono(12.5f, 18f),
            monoMeta = mono(12f, 17f),
            thinking = inter(14.5f, 22f, FontWeight.Normal),
            largeTitle = inter(28f, 34f, FontWeight.SemiBold, (-0.01).em),
            rowTitle = inter(17f, 24f, FontWeight.Normal, (-0.005).em),
            rowMeta = inter(15f, 20f, FontWeight.Normal),
        )

        /** Text size steps -1 … +2. */
        fun factorFor(step: Int): Float = when (step) {
            -1 -> 0.92f
            1 -> 1.08f
            2 -> 1.17f
            else -> 1f
        }
    }
}
