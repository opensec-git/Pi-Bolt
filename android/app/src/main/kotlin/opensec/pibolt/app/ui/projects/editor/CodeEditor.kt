package opensec.pibolt.app.ui.projects.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import opensec.pibolt.app.ui.markdown.Highlighter
import opensec.pibolt.app.ui.markdown.applyTokens
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiColors
import kotlin.math.abs

/** A request to scroll an offset into view; a new [id] each time, so the same place can be asked for twice. */
data class Reveal(val offset: Int, val id: Long = System.nanoTime())

/** Highlighted text for one version of the draft. */
private class Styled(val text: String, val tokens: List<Highlighter.Token>, val annotated: AnnotatedString)

/** Highlighting while editing stops above this size (the field then shows plain text). */
const val EDIT_HIGHLIGHT_CHARS = Highlighter.MAX_BACKGROUND_CHARS

/**
 * The editing surface: monospace, no autocorrect, syntax highlighted as you type. Highlighting runs on
 * [Dispatchers.Default] through [Highlighter.Incremental] (only the lines around an edit are re-tokenised); until a
 * pass for the newest text lands, the previous colours are shifted around the edit so nothing flashes. Find matches
 * and the bracket pair at the cursor are drawn as backgrounds. Typing goes through [EditorInput] (auto-indent,
 * bracket pairs) and every change is recorded in the tab's undo history. [wrap] off scrolls sideways.
 */
@Composable
fun CodeEditor(
    tab: EditorTab,
    language: String?,
    wrap: Boolean,
    matches: List<TextMatch>,
    current: Int,
    reveal: Reveal?,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val colors = Pi.colors
    val density = LocalDensity.current
    var styled by remember(tab, language, colors) { mutableStateOf<Styled?>(null) }
    LaunchedEffect(tab, language, colors) {
        val lang = language ?: return@LaunchedEffect
        val incremental = Highlighter.Incremental(lang, limit = EDIT_HIGHLIGHT_CHARS)
        snapshotFlow { tab.value.text }.conflate().collect { text ->
            styled = if (text.length > EDIT_HIGHLIGHT_CHARS) {
                null
            } else {
                withContext(Dispatchers.Default) {
                    val tokens = incremental.update(text)
                    Styled(text, tokens, applyTokens(text, tokens, colors))
                }
            }
        }
    }
    val value = tab.value
    val bracket = remember(value.text, value.selection) { if (value.selection.collapsed) EditorInput.matchingBracket(value.text, value.selection.start) else null }
    val look = remember(styled, colors, matches, current, bracket) {
        EditorLook(styled, colors, matches, current, bracket, colors.accent.copy(alpha = 0.22f), colors.accent.copy(alpha = 0.5f), colors.surface3)
    }

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val padTop = with(density) { 12.dp.toPx() }
    val reduced = Pi.reduceMotion
    LaunchedEffect(reveal, layout != null) {
        val r = reveal ?: return@LaunchedEffect
        val l = layout ?: return@LaunchedEffect
        val rect = l.getCursorRect(r.offset.coerceIn(0, l.layoutInput.text.length))
        val y = (rect.top + padTop - viewport.height / 3f).toInt().coerceIn(0, vertical.maxValue.coerceAtLeast(0))
        if (reduced) vertical.scrollTo(y) else vertical.animateScrollTo(y)
        if (!wrap) horizontal.scrollTo((rect.left - viewport.width / 3f).toInt().coerceIn(0, horizontal.maxValue.coerceAtLeast(0)))
    }

    Box(
        modifier
            .fillMaxSize()
            .imePadding()
            .onSizeChanged { viewport = it }
            .verticalScroll(vertical)
            .then(if (wrap) Modifier else Modifier.horizontalScroll(horizontal)),
    ) {
        val minWidth = with(density) { viewport.width.toDp() }
        val minHeight = with(density) { viewport.height.toDp() }
        BasicTextField(
            value = value,
            onValueChange = { change(tab, it) },
            textStyle = Pi.type.monoSmall.copy(color = colors.ink1),
            cursorBrush = SolidColor(colors.accent),
            visualTransformation = look,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, capitalization = KeyboardCapitalization.None),
            onTextLayout = { layout = it },
            modifier = Modifier
                .then(if (wrap) Modifier.fillMaxWidth() else Modifier.widthIn(min = minWidth))
                .heightIn(min = minHeight)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .focusRequester(focusRequester)
                .semantics { contentDescription = "Text of ${tab.name}" },
        )
    }
}

/** One change from the field: the typing rules first, then the undo history, then the tab. */
private fun change(tab: EditorTab, new: TextFieldValue) {
    val old = tab.value
    var next = new
    if (new.text != old.text && new.composition == null) {
        EditorInput.transform(
            EditState(old.text, old.selection.start, old.selection.end),
            EditState(new.text, new.selection.start, new.selection.end),
        )?.let { next = TextFieldValue(it.text, TextRange(it.start, it.end)) }
    }
    if (next.text != old.text) {
        val typing = old.selection.collapsed && abs(next.text.length - old.text.length) == 1
        val recorded = tab.history.record(
            old.text,
            next.text,
            old.selection.start..old.selection.end,
            next.selection.start..next.selection.end,
            System.currentTimeMillis(),
            if (typing) EditKind.Typing else EditKind.Other,
        )
        if (recorded) tab.historyVersion++
    }
    tab.value = next
}

/** Colours, find matches and the bracket pair over the field's text; offsets never move (identity mapping). */
private data class EditorLook(
    val styled: Styled?,
    val colors: PiColors,
    val matches: List<TextMatch>,
    val current: Int,
    val bracket: Pair<Int, Int>?,
    val matchBg: Color,
    val currentBg: Color,
    val bracketBg: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val s = text.text
        val st = styled
        val base = when {
            st == null -> text
            st.text == s -> st.annotated
            // The background pass is a keystroke behind: move its colours around the edit meanwhile.
            else -> applyTokens(s, Highlighter.shiftTokens(st.tokens, st.text, s), colors)
        }
        if (matches.isEmpty() && bracket == null) return TransformedText(base, OffsetMapping.Identity)
        val out = buildAnnotatedString {
            append(base)
            matches.forEachIndexed { i, m ->
                if (m.end <= s.length) addStyle(SpanStyle(background = if (i == current) currentBg else matchBg), m.start, m.end)
            }
            bracket?.let { (a, b) ->
                if (b < s.length) {
                    addStyle(SpanStyle(background = bracketBg), a, a + 1)
                    addStyle(SpanStyle(background = bracketBg), b, b + 1)
                }
            }
        }
        return TransformedText(out, OffsetMapping.Identity)
    }
}
