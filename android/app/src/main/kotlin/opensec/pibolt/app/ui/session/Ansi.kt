package opensec.pibolt.app.ui.session

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import opensec.pibolt.app.ui.theme.PiColors

/**
 * A terminal colour: one of the 256 palette entries ([index] 0..255) or a 24-bit [rgb] (index -1). The 16 base colours
 * map to theme tokens when drawn, so red is the theme's danger and green its success.
 */
@Immutable
data class AnsiColor(val index: Int = -1, val rgb: Int = 0)

/** The SGR state of a run of text. */
@Immutable
data class AnsiStyle(
    val fg: AnsiColor? = null,
    val bg: AnsiColor? = null,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
    val strike: Boolean = false,
) {
    val plain: Boolean get() = this == PLAIN

    companion object {
        val PLAIN = AnsiStyle()
    }
}

@Immutable
data class AnsiSpan(val start: Int, val end: Int, val style: AnsiStyle)

/** Terminal output without its escape sequences: [text] and the styled runs in it ([spans], sorted, not overlapping). */
@Immutable
data class AnsiText(val text: String, val spans: List<AnsiSpan>) {
    /** True when some line has a colour of its own (then the cheap FAIL/ok line classes stay off). */
    val coloured: Boolean get() = spans.any { it.style.fg != null || it.style.bg != null }
}

/**
 * Terminal output as shown in a tool card. SGR (`ESC [ … m`) sets colours, bold, dim, italic, underline, inverse and
 * strike-through; every other CSI sequence (cursor moves, erase) and OSC sequence (titles, hyperlinks) is dropped. A
 * bare carriage return rewinds to the line start, so progress bars show their last state only.
 */
object Ansi {
    private const val ESC = '\u001b'

    fun parse(input: String): AnsiText {
        if (input.indexOf(ESC) < 0 && input.indexOf('\r') < 0) return AnsiText(input, emptyList())
        val out = StringBuilder(input.length)
        val spans = ArrayList<AnsiSpan>()
        var style = AnsiStyle.PLAIN
        var runStart = 0
        var lineStart = 0

        fun close() {
            if (!style.plain && out.length > runStart) spans += AnsiSpan(runStart, out.length, style)
            runStart = out.length
        }

        var i = 0
        val n = input.length
        while (i < n) {
            val c = input[i]
            when {
                c == ESC && i + 1 < n && input[i + 1] == '[' -> {
                    // CSI: parameters 0x30-0x3F, intermediates 0x20-0x2F, one final byte 0x40-0x7E.
                    var j = i + 2
                    while (j < n && input[j].code in 0x30..0x3F) j++
                    while (j < n && input[j].code in 0x20..0x2F) j++
                    if (j >= n) {
                        i = n
                        continue
                    }
                    if (input[j] == 'm') {
                        val next = applySgr(style, input.substring(i + 2, j))
                        if (next != style) {
                            close()
                            style = next
                        }
                    }
                    i = j + 1
                }
                c == ESC && i + 1 < n && input[i + 1] == ']' -> {
                    // OSC: ends at BEL or ESC \.
                    var j = i + 2
                    while (j < n && input[j] != '\u0007' && !(input[j] == ESC && j + 1 < n && input[j + 1] == '\\')) j++
                    i = if (j >= n) n else if (input[j] == '\u0007') j + 1 else j + 2
                }
                c == ESC -> i += 2
                c == '\r' && i + 1 < n && input[i + 1] == '\n' -> i++
                c == '\r' -> {
                    // Rewind: what follows overwrites this line.
                    close()
                    out.setLength(lineStart)
                    while (spans.isNotEmpty() && spans.last().end > lineStart) {
                        val last = spans.removeAt(spans.lastIndex)
                        if (last.start < lineStart) spans += last.copy(end = lineStart)
                    }
                    runStart = lineStart
                    i++
                }
                else -> {
                    out.append(c)
                    if (c == '\n') lineStart = out.length
                    i++
                }
            }
        }
        close()
        return AnsiText(out.toString(), spans)
    }

    /** [style] after the SGR parameters [params] (`1;31`, `38;5;214`, `38;2;255;0;0`, empty = reset). */
    fun applySgr(style: AnsiStyle, params: String): AnsiStyle {
        if (params.isEmpty()) return AnsiStyle.PLAIN
        val codes = params.split(';', ':').map { it.toIntOrNull() ?: 0 }
        var s = style
        var k = 0
        while (k < codes.size) {
            when (val code = codes[k]) {
                0 -> s = AnsiStyle.PLAIN
                1 -> s = s.copy(bold = true)
                2 -> s = s.copy(dim = true)
                3 -> s = s.copy(italic = true)
                4 -> s = s.copy(underline = true)
                7 -> s = s.copy(inverse = true)
                9 -> s = s.copy(strike = true)
                21, 22 -> s = s.copy(bold = false, dim = false)
                23 -> s = s.copy(italic = false)
                24 -> s = s.copy(underline = false)
                27 -> s = s.copy(inverse = false)
                29 -> s = s.copy(strike = false)
                in 30..37 -> s = s.copy(fg = AnsiColor(code - 30))
                39 -> s = s.copy(fg = null)
                in 40..47 -> s = s.copy(bg = AnsiColor(code - 40))
                49 -> s = s.copy(bg = null)
                in 90..97 -> s = s.copy(fg = AnsiColor(code - 90 + 8))
                in 100..107 -> s = s.copy(bg = AnsiColor(code - 100 + 8))
                38, 48 -> {
                    val (color, used) = extended(codes, k + 1)
                    if (color != null) s = if (code == 38) s.copy(fg = color) else s.copy(bg = color)
                    k += used
                }
            }
            k++
        }
        return s
    }

    /** `5;n` or `2;r;g;b` after a 38/48; the colour and how many codes it took. */
    private fun extended(codes: List<Int>, at: Int): Pair<AnsiColor?, Int> = when (codes.getOrNull(at)) {
        5 -> codes.getOrNull(at + 1)?.takeIf { it in 0..255 }?.let { AnsiColor(it) to 2 } ?: (null to 2)
        2 -> if (at + 3 < codes.size) {
            val (r, g, b) = Triple(codes[at + 1].coerceIn(0, 255), codes[at + 2].coerceIn(0, 255), codes[at + 3].coerceIn(0, 255))
            AnsiColor(rgb = (r shl 16) or (g shl 8) or b) to 4
        } else {
            null to codes.size - at
        }
        else -> null to 0
    }

    /** The RGB of palette entry [index] 16..255 (the 6x6x6 cube and the grey ramp). */
    fun paletteRgb(index: Int): Int = when {
        index in 16..231 -> {
            val i = index - 16
            val level = intArrayOf(0, 95, 135, 175, 215, 255)
            (level[i / 36] shl 16) or (level[(i / 6) % 6] shl 8) or level[i % 6]
        }
        index in 232..255 -> {
            val v = 8 + (index - 232) * 10
            (v shl 16) or (v shl 8) or v
        }
        else -> 0
    }

    /** A colour on the theme: the 16 base colours become tokens, others their RGB. */
    fun color(c: AnsiColor, colors: PiColors): Color {
        if (c.index < 0) return Color(0xFF000000.toInt() or c.rgb)
        return when (c.index) {
            0, 8 -> colors.ink3
            1 -> colors.danger
            9 -> colors.diffDelFg
            2 -> colors.success
            10 -> colors.diffAddFg
            3, 11 -> colors.warning
            4, 12 -> colors.ansiBlue
            5, 13 -> colors.ansiMagenta
            6, 14 -> colors.ansiCyan
            7 -> colors.ink2
            15 -> colors.ink1
            else -> Color(0xFF000000.toInt() or paletteRgb(c.index))
        }
    }
}

/** Plain output lines that read as a failure ("FAIL", "Error:", "✗") or a pass ("ok", "PASS", "✓"). */
enum class LineClass { Fail, Pass }

private val failLine = Regex("""^\s*(FAIL\b|ERROR\b|Error:|error(\[\w+])?:|✗|×|✘|npm ERR!|E\s{2,}|Traceback |panic:|fatal:)""")
private val passLine = Regex("""^\s*(ok\b|PASS\b|✓|✔)""")

fun lineClass(line: String): LineClass? = when {
    failLine.containsMatchIn(line) -> LineClass.Fail
    passLine.containsMatchIn(line) -> LineClass.Pass
    else -> null
}

private val exitStatus = Regex("""\n*Command exited with code \d+\s*$""")

/** Bash output without Pi's trailing "Command exited with code N" (the card's badge says it). */
fun withoutExitLine(text: String): String = exitStatus.replace(text, "").trimEnd('\n')

/**
 * Terminal output styled for a card: SGR colours on the theme, plain text in [base]. Without colours of its own, lines
 * that read as failures get danger and passes success at 80%; everything else stays [base], whatever the exit code.
 */
fun ansiAnnotated(parsed: AnsiText, colors: PiColors, base: Color): AnnotatedString = buildAnnotatedString {
    val text = parsed.text
    append(text)
    for (span in parsed.spans) addStyle(spanStyle(span.style, colors, base), span.start, span.end)
    if (parsed.coloured) return@buildAnnotatedString
    var start = 0
    while (start <= text.length) {
        val nl = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        when (lineClass(text.substring(start, nl))) {
            LineClass.Fail -> addStyle(SpanStyle(color = colors.danger), start, nl)
            LineClass.Pass -> addStyle(SpanStyle(color = colors.success.copy(alpha = 0.8f)), start, nl)
            null -> Unit
        }
        start = nl + 1
    }
}

private fun spanStyle(s: AnsiStyle, colors: PiColors, base: Color): SpanStyle {
    var fg = s.fg?.let { Ansi.color(it, colors) }
    var bg = s.bg?.let { Ansi.color(it, colors) }
    if (s.inverse) {
        val f = fg ?: base
        fg = bg ?: colors.surface1
        bg = f
    }
    if (s.dim) fg = (fg ?: base).copy(alpha = 0.6f)
    val decoration = when {
        s.underline && s.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
        s.underline -> TextDecoration.Underline
        s.strike -> TextDecoration.LineThrough
        else -> null
    }
    return SpanStyle(
        color = fg ?: Color.Unspecified,
        background = bg ?: Color.Unspecified,
        fontWeight = if (s.bold) FontWeight.SemiBold else null,
        fontStyle = if (s.italic) FontStyle.Italic else null,
        textDecoration = decoration,
    )
}
