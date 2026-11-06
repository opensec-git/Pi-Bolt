package opensec.pibolt.app.ui.session

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.app.ui.markdown.Markdown
import opensec.pibolt.app.ui.markdown.MarkdownView
import opensec.pibolt.app.ui.markdown.MdDoc
import opensec.pibolt.app.ui.markdown.diffAnnotated
import opensec.pibolt.app.ui.theme.CodeStyle
import opensec.pibolt.app.ui.theme.Mono
import opensec.pibolt.app.ui.theme.PiTheme
import opensec.pibolt.protocol.session.AssistantTextItem
import opensec.pibolt.protocol.session.ChatItem
import opensec.pibolt.protocol.session.NoticeItem
import opensec.pibolt.protocol.session.NoticeKind
import opensec.pibolt.protocol.session.ThinkingItem
import opensec.pibolt.protocol.session.ToolItem
import opensec.pibolt.protocol.session.ToolStatus
import opensec.pibolt.protocol.session.UserItem
import opensec.pibolt.protocol.str
import opensec.pibolt.protocol.toDisplayString

@Composable
fun ChatItemView(item: ChatItem, modifier: Modifier = Modifier) {
    when (item) {
        is UserItem -> UserBubble(item, modifier)
        is AssistantTextItem -> AssistantText(item, modifier)
        is ThinkingItem -> ThinkingBlock(item, modifier)
        is ToolItem -> ToolCard(item, modifier)
        is NoticeItem -> NoticeRow(item, modifier)
    }
}

@Composable
private fun UserBubble(item: UserItem, modifier: Modifier) {
    val colors = PiTheme.colors
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = colors.userBubble,
            contentColor = colors.onUserBubble,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                SelectionContainer { Text(item.text, style = MaterialTheme.typography.bodyLarge) }
                if (item.imageCount > 0) {
                    Text(
                        if (item.imageCount == 1) "1 image" else "${item.imageCount} images",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantText(item: AssistantTextItem, modifier: Modifier) {
    // Keep the last parse per row: a growing message re-parses only its last block.
    val holder = remember(item.key) { arrayOfNulls<MdDoc>(1) }
    val doc = remember(item.text) { Markdown.parse(item.text, holder[0]).also { holder[0] = it } }
    SelectionContainer(modifier.fillMaxWidth()) {
        MarkdownView(doc)
    }
}

@Composable
private fun ThinkingBlock(item: ThinkingItem, modifier: Modifier) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable { expanded = !expanded }
            .background(scheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .animateContentSize(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Lightbulb, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    item.redacted && item.text.isBlank() -> "Thinking (redacted)"
                    item.streaming -> "Thinking…"
                    else -> "Thought"
                },
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .then(if (item.streaming) Modifier.pulse() else Modifier),
            )
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = if (expanded) "Collapse" else "Expand", tint = scheme.onSurfaceVariant)
        }
        if (item.text.isNotBlank()) {
            val style = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurfaceVariant, fontStyle = FontStyle.Italic)
            if (expanded) {
                SelectionContainer { Text(item.text, style = style, modifier = Modifier.padding(top = 6.dp)) }
            } else {
                Text(
                    item.text.trim().lines().takeLast(2).joinToString(" "),
                    style = style,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
fun Modifier.pulse(): Modifier {
    val transition = rememberInfiniteTransition(label = "pulse")
    val a by transition.animateFloat(0.45f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "alpha")
    return this.alpha(a)
}

// Tools

private data class ToolLabel(val icon: ImageVector, val verb: String, val detail: String)

private val partialField = Regex("\"(command|path|file_path|pattern|query|url)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)")

private fun toolLabel(item: ToolItem): ToolLabel {
    val args = item.args
    fun arg(vararg names: String): String? {
        for (n in names) args?.str(n)?.let { return it }
        // While the model is still writing the call, read what has arrived.
        for (m in partialField.findAll(item.argsText)) {
            if (m.groupValues[1] in names) return m.groupValues[2].replace("\\n", " ").replace("\\\"", "\"").replace("\\\\", "\\")
        }
        return null
    }
    val name = item.name
    return when (name.lowercase()) {
        "bash", "shell", "exec" -> ToolLabel(Icons.Outlined.Terminal, "$", arg("command").orEmpty().lineSequence().firstOrNull().orEmpty())
        "read" -> {
            val range = listOfNotNull(args?.get("offset")?.let { "from ${(it as? JsonPrimitive)?.content}" }, args?.get("limit")?.let { "${(it as? JsonPrimitive)?.content} lines" })
            ToolLabel(Icons.Outlined.Description, "Read", listOfNotNull(arg("path", "file_path"), range.joinToString(", ").ifBlank { null }).joinToString("  "))
        }
        "write" -> ToolLabel(Icons.AutoMirrored.Outlined.NoteAdd, "Write", arg("path", "file_path").orEmpty())
        "edit", "multiedit" -> ToolLabel(Icons.Outlined.Edit, "Edit", arg("path", "file_path").orEmpty())
        "grep" -> ToolLabel(Icons.Outlined.Search, "Grep", listOfNotNull(arg("pattern"), arg("path")?.let { "in $it" }).joinToString(" "))
        "find", "glob" -> ToolLabel(Icons.Outlined.Search, "Find", listOfNotNull(arg("pattern"), arg("path")?.let { "in $it" }).joinToString(" "))
        "ls" -> ToolLabel(Icons.Outlined.FolderOpen, "List", arg("path") ?: ".")
        else -> {
            val first = args?.entries?.firstOrNull { (it.value as? JsonPrimitive)?.isString == true }?.let { (it.value as JsonPrimitive).content }
            ToolLabel(Icons.Outlined.Build, name, (first ?: arg("command", "path", "pattern", "query", "url")).orEmpty().lineSequence().firstOrNull().orEmpty())
        }
    }
}

private const val MAX_SHOWN = 120_000

@Composable
private fun ToolCard(item: ToolItem, modifier: Modifier) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val colors = PiTheme.colors
    val label = remember(item.name, item.args, item.argsText) { toolLabel(item) }
    val border = when (item.status) {
        ToolStatus.Error -> scheme.error.copy(alpha = 0.5f)
        ToolStatus.Running -> colors.running.copy(alpha = 0.45f)
        else -> scheme.outlineVariant
    }
    Surface(
        color = scheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, border),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.animateContentSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(start = 10.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(26.dp)
                        .background(scheme.surfaceContainerHighest, RoundedCornerShape(7.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(label.icon, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(label.verb, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
                Spacer(Modifier.width(6.dp))
                Text(
                    label.detail,
                    style = CodeStyle.copy(color = scheme.onSurfaceVariant),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                ToolStatusBadge(item)
            }
            val body = toolBody(item, expanded)
            if (body != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.codeBackground)
                        .then(if (expanded) Modifier else Modifier.heightIn(max = 220.dp)),
                ) {
                    val text = body
                    if (expanded) {
                        SelectionContainer {
                            Text(
                                text,
                                style = CodeStyle.copy(color = if (item.isError) scheme.error else colors.codeText),
                                softWrap = false,
                                modifier = Modifier
                                    .horizontalScroll(rememberScrollState())
                                    .padding(10.dp),
                            )
                        }
                    } else {
                        Text(
                            text,
                            style = CodeStyle.copy(color = if (item.isError) scheme.error else colors.codeText),
                            maxLines = if (item.isActive) 14 else 8,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun toolBody(item: ToolItem, expanded: Boolean): AnnotatedString? {
    val colors = PiTheme.colors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    return remember(item.status, item.output, item.result, item.details, item.args, expanded, colors) {
        val diff = item.details?.str("diff")
        when {
            item.status == ToolStatus.Running -> item.output.takeIf { it.isNotBlank() }?.let { AnnotatedString(tail(it, if (expanded) 2000 else 14)) }
            item.status == ToolStatus.Streaming || item.status == ToolStatus.Pending ->
                if (expanded) AnnotatedString(item.args?.toDisplayString() ?: item.argsText) else null
            diff != null && !item.isError -> diffAnnotated(if (expanded) diff.take(MAX_SHOWN) else head(diff, 8), colors, muted)
            else -> {
                val result = item.result ?: item.output
                val argsText = if (expanded && item.args != null && item.name !in setOf("bash", "read", "ls")) {
                    item.args!!.toDisplayString() + "\n\n"
                } else {
                    ""
                }
                when {
                    expanded -> AnnotatedString(argsText + result.take(MAX_SHOWN) + if (result.length > MAX_SHOWN) "\n… ${result.length - MAX_SHOWN} more characters" else "")
                    result.isBlank() -> null
                    item.name == "read" || item.name == "write" -> AnnotatedString(head(result, 8))
                    else -> AnnotatedString(tail(result, 8))
                }
            }
        }
    }
}

private fun head(text: String, lines: Int): String {
    var count = 0
    var i = 0
    while (i < text.length && count < lines) {
        val nl = text.indexOf('\n', i)
        if (nl < 0) return text
        i = nl + 1
        count++
    }
    return text.substring(0, i).trimEnd()
}

private fun tail(text: String, lines: Int): String {
    val trimmed = text.trimEnd()
    var count = 0
    var i = trimmed.length
    while (i > 0 && count < lines) {
        val nl = trimmed.lastIndexOf('\n', i - 1)
        if (nl < 0) return trimmed
        i = nl
        count++
    }
    return trimmed.substring(i + 1)
}

@Composable
private fun ToolStatusBadge(item: ToolItem) {
    val scheme = MaterialTheme.colorScheme
    val colors = PiTheme.colors
    when (item.status) {
        ToolStatus.Streaming, ToolStatus.Pending -> Box(Modifier.size(8.dp).background(scheme.outline, CircleShape).pulse())
        ToolStatus.Running -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.running)
        ToolStatus.Done -> Row(verticalAlignment = Alignment.CenterVertically) {
            item.durationMs?.let { Text(formatDuration(it), style = MaterialTheme.typography.labelSmall, color = scheme.outline, modifier = Modifier.padding(end = 6.dp)) }
            Icon(Icons.Outlined.CheckCircle, contentDescription = "Done", tint = colors.success, modifier = Modifier.size(17.dp))
        }
        ToolStatus.Error -> Icon(Icons.Outlined.ErrorOutline, contentDescription = "Failed", tint = scheme.error, modifier = Modifier.size(17.dp))
        ToolStatus.Cancelled -> Icon(Icons.Outlined.Block, contentDescription = "Not run", tint = scheme.outline, modifier = Modifier.size(17.dp))
    }
}

fun formatDuration(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> String.format(java.util.Locale.ROOT, "%.1fs", ms / 1000.0)
    else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
}

// Notices

@Composable
private fun NoticeRow(item: NoticeItem, modifier: Modifier) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val (icon, tint, background) = when (item.kind) {
        NoticeKind.Error -> Triple(Icons.Outlined.ErrorOutline, scheme.onErrorContainer, scheme.errorContainer)
        NoticeKind.Aborted -> Triple(Icons.Outlined.StopCircle, scheme.onSurfaceVariant, scheme.surfaceContainerLow)
        NoticeKind.Compaction -> Triple(Icons.Outlined.Compress, scheme.onSecondaryContainer, scheme.secondaryContainer.copy(alpha = 0.6f))
        NoticeKind.Summary -> Triple(Icons.AutoMirrored.Outlined.Notes, scheme.onSurfaceVariant, scheme.surfaceContainerLow)
        NoticeKind.Custom, NoticeKind.Info -> Triple(Icons.Outlined.Info, scheme.onSurfaceVariant, scheme.surfaceContainerLow)
    }
    val expandable = item.text.isNotBlank() && item.kind != NoticeKind.Error
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(background)
            .then(if (expandable) Modifier.clickable { expanded = !expanded } else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(item.title, style = MaterialTheme.typography.labelLarge, color = tint, modifier = Modifier.weight(1f))
            if (expandable) Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null, tint = tint)
        }
        if (item.text.isNotBlank() && (expanded || item.kind == NoticeKind.Error)) {
            SelectionContainer {
                Text(
                    item.text,
                    style = if (item.kind == NoticeKind.Error) MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono) else MaterialTheme.typography.bodyMedium,
                    color = tint,
                )
            }
        }
    }
}

/** Shown at the bottom while the agent works and nothing streams yet. */
@Composable
fun WorkingIndicator(label: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val color = PiTheme.colors.running
        repeat(3) { i ->
            val transition = rememberInfiniteTransition(label = "dot$i")
            val a by transition.animateFloat(
                0.25f,
                1f,
                infiniteRepeatable(tween(600, delayMillis = i * 160, easing = LinearEasing), RepeatMode.Reverse),
                label = "a$i",
            )
            Box(
                Modifier
                    .padding(end = 5.dp)
                    .size(7.dp)
                    .alpha(a)
                    .background(color, CircleShape),
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
