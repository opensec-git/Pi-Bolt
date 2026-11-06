package opensec.pibolt.app.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import android.content.ClipData
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import opensec.pibolt.app.ui.theme.CodeStyle
import opensec.pibolt.app.ui.theme.Mono
import opensec.pibolt.app.ui.theme.PiColors
import opensec.pibolt.app.ui.theme.PiTheme

@Composable
fun MarkdownView(doc: MdDoc, modifier: Modifier = Modifier, textStyle: TextStyle = MaterialTheme.typography.bodyLarge) {
    Column(modifier) {
        var previous: MdBlock? = null
        for (block in doc.blocks) {
            // List items sit close together; other blocks get paragraph spacing.
            val gap = when {
                previous == null -> 0.dp
                previous is MdBlock.ListItem && block is MdBlock.ListItem -> 4.dp
                else -> 10.dp
            }
            Box(Modifier.padding(top = gap)) { MdBlockView(block, textStyle) }
            previous = block
        }
    }
}

@Composable
private fun MdBlockView(block: MdBlock, textStyle: TextStyle) {
    val colors = PiTheme.colors
    val scheme = MaterialTheme.colorScheme
    when (block) {
        is MdBlock.Heading -> {
            val style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                3 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }
            Text(rememberInline(block.text, colors), style = style, modifier = Modifier.padding(top = 4.dp))
        }
        is MdBlock.Paragraph -> Text(rememberInline(block.text, colors), style = textStyle)
        is MdBlock.Code -> CodeBlock(block.code, block.language)
        is MdBlock.ListItem -> Row(Modifier.padding(start = (block.depth * 16).dp)) {
            val bullet = when {
                block.checked == true -> "☑"
                block.checked == false -> "☐"
                block.ordered -> block.marker
                block.depth % 2 == 1 -> "◦"
                else -> "•"
            }
            Text(
                bullet,
                style = textStyle.copy(color = scheme.primary, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.widthIn(min = 20.dp).padding(end = 6.dp),
            )
            Text(rememberInline(block.text, colors), style = textStyle)
        }
        is MdBlock.Quote -> Row {
            Box(
                Modifier
                    .width(3.dp)
                    .background(scheme.primary.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
                    .padding(vertical = 2.dp),
            ) { Text(" ", style = textStyle) }
            Text(
                rememberInline(block.text, colors),
                style = textStyle.copy(color = scheme.onSurfaceVariant, fontStyle = FontStyle.Italic),
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp), color = scheme.outlineVariant)
        is MdBlock.Table -> TableBlock(block)
    }
}

@Composable
private fun rememberInline(text: String, colors: PiColors): AnnotatedString {
    val link = MaterialTheme.colorScheme.secondary
    return remember(text, colors) { inlineAnnotated(text, colors, link) }
}

fun inlineAnnotated(text: String, colors: PiColors, linkColor: Color): AnnotatedString {
    val parsed = Inline.parse(text)
    return buildAnnotatedString {
        append(parsed.text)
        for (span in parsed.spans) {
            when (span.kind) {
                InlineSpan.Kind.Bold -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), span.start, span.end)
                InlineSpan.Kind.Italic -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), span.start, span.end)
                InlineSpan.Kind.Strike -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), span.start, span.end)
                InlineSpan.Kind.Code -> addStyle(
                    SpanStyle(fontFamily = Mono, fontSize = 0.88.em, background = colors.codeBackground, color = colors.codeText),
                    span.start,
                    span.end,
                )
                InlineSpan.Kind.Link -> {
                    val url = span.url.orEmpty()
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        addLink(
                            LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))),
                            span.start,
                            span.end,
                        )
                    } else {
                        addStyle(SpanStyle(color = linkColor), span.start, span.end)
                    }
                }
            }
        }
    }
}


fun highlighted(code: String, language: String, colors: PiColors): AnnotatedString = buildAnnotatedString {
    append(code)
    for (token in Highlighter.tokenize(code, language)) {
        val color = when (token.kind) {
            Highlighter.Kind.Keyword -> colors.keyword
            Highlighter.Kind.String -> colors.string
            Highlighter.Kind.Comment -> colors.comment
            Highlighter.Kind.Number -> colors.number
            Highlighter.Kind.Function -> colors.function
        }
        addStyle(
            if (token.kind == Highlighter.Kind.Comment) SpanStyle(color = color, fontStyle = FontStyle.Italic) else SpanStyle(color = color),
            token.start,
            token.end,
        )
    }
}

/** Unified diff text: added and removed lines get their own colors. */
fun diffAnnotated(diff: String, colors: PiColors, muted: Color): AnnotatedString = buildAnnotatedString {
    var first = true
    for (line in diff.lineSequence()) {
        if (!first) append('\n')
        first = false
        val start = length
        append(line)
        val style = when {
            line.startsWith("+++") || line.startsWith("---") -> SpanStyle(color = muted, fontWeight = FontWeight.SemiBold)
            line.startsWith("+") -> SpanStyle(color = colors.diffAdd, background = colors.diffAddBackground)
            line.startsWith("-") -> SpanStyle(color = colors.diffRemove, background = colors.diffRemoveBackground)
            line.startsWith("@@") -> SpanStyle(color = colors.function)
            else -> null
        }
        style?.let { addStyle(it, start, length) }
    }
}

@Composable
fun CodeBlock(code: String, language: String, modifier: Modifier = Modifier) {
    val colors = PiTheme.colors
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val text = remember(code, language, colors) { highlighted(code, language, colors) }
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(colors.codeBackground),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                language.ifBlank { "code" },
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono),
                color = colors.comment,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", code))) } }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy code", tint = colors.comment, modifier = Modifier.size(18.dp))
            }
        }
        Text(
            text,
            style = CodeStyle.copy(color = colors.codeText),
            softWrap = false,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun TableBlock(table: MdBlock.Table) {
    val colors = PiTheme.colors
    val scheme = MaterialTheme.colorScheme
    val columns = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    Box(
        Modifier
            .clip(MaterialTheme.shapes.small)
            .background(scheme.surfaceContainerLow)
            .horizontalScroll(rememberScrollState()),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            val rows = listOf(table.header) + table.rows
            rows.forEachIndexed { r, row ->
                Row(Modifier.padding(horizontal = 4.dp)) {
                    for (c in 0 until columns) {
                        Text(
                            rememberInline(row.getOrElse(c) { "" }, colors),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal, fontSize = 14.sp),
                            modifier = Modifier
                                .widthIn(min = 72.dp, max = 260.dp)
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
                if (r == 0) HorizontalDivider(color = scheme.outlineVariant)
            }
        }
    }
}
