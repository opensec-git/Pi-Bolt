package opensec.pibolt.app.ui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import opensec.pibolt.app.ui.theme.PiColors

/** The style of one token kind in [colors] (DESIGN.md, Syntax). */
fun tokenStyle(kind: Highlighter.Kind, colors: PiColors): SpanStyle {
    val s = colors.syntax
    return when (kind) {
        Highlighter.Kind.Keyword -> SpanStyle(color = s.keyword, fontWeight = s.keywordWeight)
        Highlighter.Kind.Type -> SpanStyle(color = s.type)
        Highlighter.Kind.Function -> SpanStyle(color = s.function)
        Highlighter.Kind.String -> SpanStyle(color = s.string)
        Highlighter.Kind.Number -> SpanStyle(color = s.number)
        Highlighter.Kind.Constant -> SpanStyle(color = s.constant, fontWeight = s.keywordWeight)
        Highlighter.Kind.Comment -> SpanStyle(color = s.comment, fontStyle = FontStyle.Italic)
        Highlighter.Kind.Operator -> SpanStyle(color = s.operator)
        Highlighter.Kind.Punctuation -> SpanStyle(color = colors.ink2)
        Highlighter.Kind.Attribute -> SpanStyle(color = s.attribute)
        Highlighter.Kind.Tag -> SpanStyle(color = s.tag)
        Highlighter.Kind.Regex -> SpanStyle(color = s.regex)
        Highlighter.Kind.Escape -> SpanStyle(color = s.escape)
        Highlighter.Kind.Variable -> SpanStyle(color = s.variable)
        Highlighter.Kind.Prompt -> SpanStyle(color = colors.success, fontWeight = FontWeight.SemiBold)
        Highlighter.Kind.Inserted -> SpanStyle(color = colors.diffAddFg)
        Highlighter.Kind.Deleted -> SpanStyle(color = colors.diffDelFg)
        Highlighter.Kind.Heading -> SpanStyle(color = s.keyword, fontWeight = FontWeight.SemiBold)
        Highlighter.Kind.Emphasis -> SpanStyle(color = s.type, fontStyle = FontStyle.Italic)
    }
}

/** [code] with [tokens] applied; [from] is where [code] starts in the tokens' text. */
fun applyTokens(code: String, tokens: List<Highlighter.Token>, colors: PiColors, from: Int = 0): AnnotatedString {
    if (tokens.isEmpty()) return AnnotatedString(code)
    val end = from + code.length
    return buildAnnotatedString {
        append(code)
        // Tokens are sorted: skip to the first that reaches [from].
        var lo = 0
        var hi = tokens.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (tokens[mid].end <= from) lo = mid + 1 else hi = mid
        }
        for (k in lo until tokens.size) {
            val t = tokens[k]
            if (t.start >= end) break
            val a = maxOf(t.start, from) - from
            val b = minOf(t.end, end) - from
            if (b > a) addStyle(tokenStyle(t.kind, colors), a, b)
        }
    }
}

/** Syntax colours for [code] in [language] (DESIGN.md: token kinds mapped to the theme's [PiColors.syntax]). */
fun highlighted(code: String, language: String, colors: PiColors, limit: Int = Highlighter.MAX_CHARS): AnnotatedString =
    applyTokens(code, Highlighter.tokenize(code, language, limit), colors)

private data class HighlightKey(val code: String, val language: String, val colors: PiColors)

/** Highlighted texts by content, bounded by their total length, so blocks scrolled back (or re-opened) are not re-tokenised. */
private object HighlightCache {
    private const val MAX_TOTAL = 1_500_000
    private val map = LinkedHashMap<HighlightKey, AnnotatedString>(64, 0.75f, true)
    private var total = 0

    @Synchronized
    fun get(key: HighlightKey): AnnotatedString? = map[key]

    @Synchronized
    fun put(key: HighlightKey, value: AnnotatedString) {
        map.put(key, value)?.let { total -= it.length }
        total += value.length
        val it = map.entries.iterator()
        while (total > MAX_TOTAL && map.size > 1 && it.hasNext()) {
            val e = it.next()
            if (e.key == key) continue
            total -= e.value.length
            it.remove()
        }
    }
}

/** [highlighted], cached by content, language and palette. */
fun highlightedCached(code: String, language: String, colors: PiColors, limit: Int = Highlighter.MAX_CHARS): AnnotatedString {
    if (code.isEmpty()) return AnnotatedString("")
    val key = HighlightKey(code, language, colors)
    HighlightCache.get(key)?.let { return it }
    val value = highlighted(code, language, colors, limit)
    HighlightCache.put(key, value)
    return value
}

/** Blocks up to this size are highlighted during composition; larger ones on [Dispatchers.Default]. */
const val SYNC_HIGHLIGHT_CHARS = 12_000

/**
 * [code] highlighted: from the cache when it is there, at once when small, otherwise plain first and coloured when a
 * background pass ([Dispatchers.Default]) finishes.
 */
@Composable
fun rememberHighlighted(code: String, language: String, colors: PiColors): AnnotatedString {
    val key = remember(code, language, colors) { HighlightKey(code, language, colors) }
    val cached = remember(key) { if (code.length <= SYNC_HIGHLIGHT_CHARS) highlightedCached(code, language, colors) else HighlightCache.get(key) }
    if (cached != null) return cached
    var result by remember(key) { mutableStateOf(AnnotatedString(code)) }
    LaunchedEffect(key) {
        result = withContext(Dispatchers.Default) { highlightedCached(code, language, colors, Highlighter.MAX_BACKGROUND_CHARS) }
    }
    return result
}
