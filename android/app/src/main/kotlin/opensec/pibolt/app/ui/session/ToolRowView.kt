package opensec.pibolt.app.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import opensec.pibolt.app.ui.components.DiffLines
import opensec.pibolt.app.ui.components.CopyButton
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.StepSlot
import opensec.pibolt.app.ui.components.StepState
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Tnum
import opensec.pibolt.protocol.session.ToolItem
import opensec.pibolt.protocol.session.ToolStatus
import opensec.pibolt.protocol.str
import kotlinx.serialization.json.JsonPrimitive

/** What the full-screen viewer shows. */
data class ViewerContent(val title: String, val subtitle: String?, val text: String, val diff: Boolean = false, val selectable: Boolean = false, val wrap: Boolean = diff)

private fun stepState(item: ToolItem) = when (item.status) {
    ToolStatus.Streaming, ToolStatus.Pending, ToolStatus.Running -> StepState.Active
    ToolStatus.Done -> StepState.Done
    ToolStatus.Error -> StepState.Failed
    ToolStatus.Cancelled -> StepState.Skipped
}

/**
 * A tool step: 20dp slot (Run/Write spinner, then the result glyph), verb, argument, right-hand meta. Tapping opens a
 * card with the output, diff or file preview; long output continues in the full-screen viewer.
 */
@Composable
fun ToolRowView(
    item: ToolItem,
    live: Boolean,
    expanded: Boolean,
    previewLines: Int,
    onToggle: () -> Unit,
    onOpen: (ViewerContent) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    runningSince: Long? = null,
) {
    val colors = Pi.colors
    val type = Pi.type
    val label = remember(item.name, item.args, item.argsText) { toolLabel(item) }
    val diffLines = remember(item.details) { item.details?.str("diff")?.let(::parseDiff) }
    val stats = remember(diffLines) { diffLines?.let(::diffStats) }
    val mode = if (item.status == ToolStatus.Running) {
        if (isSubagentTool(item.name)) QuadMode.Agent else QuadMode.Run
    } else {
        QuadMode.Write
    }
    Column(modifier.fillMaxWidth()) {
        StepHeader(onClick = onToggle, onLongClick = onLongPress, slot = { StepSlot(stepState(item), mode, live) }) {
            Text(label.verb, style = type.label, color = colors.ink2, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            val isPath = label.kind == ToolKind.Read || label.kind == ToolKind.Write || label.kind == ToolKind.Edit
            // The open card's header carries the command or path, so the row does not repeat it.
            val detailAlpha by animateFloatAsState(if (expanded) 0f else 1f, PiMotion.inPlace(PiMotion.QUICK), label = "detail")
            Text(
                label.detail,
                style = type.monoSmall,
                color = colors.ink3,
                maxLines = 1,
                overflow = if (isPath) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer { alpha = detailAlpha }
                    .then(if (expanded) Modifier.clearAndSetSemantics { } else Modifier),
            )
            Spacer(Modifier.width(8.dp))
            if (stats != null && !item.isError) {
                if (stats.added > 0) Text("+${stats.added}", style = type.meta.copy(fontFeatureSettings = Tnum), color = colors.success)
                if (stats.removed > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text("−${stats.removed}", style = type.meta.copy(fontFeatureSettings = Tnum), color = colors.danger)
                }
                Spacer(Modifier.width(8.dp))
            }
            if (item.status == ToolStatus.Running && runningSince != null) {
                StepElapsed(runningSince)
            } else {
                item.durationMs?.takeIf { !item.isActive }?.let {
                    Text(formatDuration(it), style = type.meta.copy(fontFeatureSettings = Tnum), color = colors.ink3)
                }
            }
            Spacer(Modifier.width(4.dp))
            Chevron(expanded)
        }
        // A subagent reads on one more line: what it is doing while it runs, the start of its answer when done.
        if (!expanded && isSubagentTool(item.name)) {
            val line = remember(item.status, item.details, item.result) {
                (subagentProgress(item) ?: subagentResult(item))?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
            }
            if (line != null) {
                Text(
                    line,
                    style = type.meta,
                    color = if (item.isError) colors.danger else colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 32.dp, end = 8.dp, bottom = 4.dp),
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(PiMotion.inPlace(), expandFrom = Alignment.Top) + fadeIn(PiMotion.inPlace(PiMotion.QUICK)),
            exit = shrinkVertically(PiMotion.leave(PiMotion.STANDARD), shrinkTowards = Alignment.Top) + fadeOut(PiMotion.leave(PiMotion.QUICK)),
        ) {
            Box(Modifier.padding(top = 2.dp, bottom = 8.dp)) {
                when {
                    diffLines != null && !item.isError -> DiffCard(item, label, diffLines, previewLines, onOpen)
                    label.kind == ToolKind.Bash -> BashCard(item, label, previewLines, onOpen)
                    else -> OutputCard(item, label, previewLines, onOpen)
                }
            }
        }
    }
}

@Composable
private fun CardFrame(running: Boolean, content: @Composable () -> Unit) {
    val colors = Pi.colors
    val shape = RoundedCornerShape(Radius.md)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface1)
            .border(1.dp, colors.line, shape)
            .then(if (running) Modifier.topSweep(colors.ink3) else Modifier),
    ) { content() }
}

/** A 2dp band sweeping the card's top edge while its tool runs (static under reduce motion). */
@Composable
private fun Modifier.topSweep(color: Color): Modifier {
    if (Pi.reduceMotion) return this
    val t = rememberInfiniteTransition(label = "sweep").animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = PiMotion.InPlace), RepeatMode.Restart), label = "x")
    return drawBehind {
        val band = size.width * 0.35f
        val x = -band + (size.width + band) * t.value
        drawRect(
            Brush.horizontalGradient(0f to Color.Transparent, 0.5f to color, 1f to Color.Transparent, startX = x, endX = x + band),
            topLeft = Offset(0f, 0f),
            size = Size(size.width, 2.dp.toPx()),
        )
    }
}

@Composable
private fun CardHeader(end: Dp = 14.dp, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(start = 14.dp, end = end),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
    Box(Modifier.fillMaxWidth().height(1.dp).background(Pi.colors.line))
}

/** The card's footer: "Show N more lines" (or "View full output"), with an optional action at its start. */
@Composable
private fun MoreRow(hidden: Int, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val colors = Pi.colors
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) {
            Spacer(Modifier.width(4.dp))
            leading()
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .pressable(onClick, shape = RoundedCornerShape(0.dp), pressedScale = 1f),
            contentAlignment = if (leading != null) Alignment.CenterEnd else Alignment.Center,
        ) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.OpenInFull, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (hidden > 0) "Show $hidden more ${if (hidden == 1) "line" else "lines"}" else "View full output", style = Pi.type.label, color = colors.ink2)
            }
        }
    }
}

/**
 * A bash step: `$` and the command (with Copy), the exit badge, then the output. While it runs, the output's last
 * lines show at a fixed height with a top fade and a terminal cursor blinks after the last glyph (the card's only
 * live signal); done, the footer copies the output or opens it in full.
 */
@Composable
private fun BashCard(item: ToolItem, label: ToolLabel, previewLines: Int, onOpen: (ViewerContent) -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val command = remember(item.args, item.argsText) { item.args?.str("command") ?: label.detail }
    val preview = remember(item.status, item.output, item.result, previewLines) { toolBody(item, full = false, previewLines = previewLines) }
    val total = remember(item.status, item.output, item.result) { lineCount(if (item.status == ToolStatus.Running) item.output else item.result ?: item.output) }
    val exit = exitCodeOf(item)
    val running = item.status == ToolStatus.Running
    CardFrame(running = false) {
        CardHeader(end = 2.dp) {
            Text("$", style = type.mono.copy(fontWeight = FontWeight.SemiBold), color = if (item.isError) colors.danger else colors.success)
            Spacer(Modifier.width(10.dp))
            Text(command.lineSequence().firstOrNull().orEmpty(), style = type.monoSmall, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (exit != null) {
                Spacer(Modifier.width(8.dp))
                val bad = exit != 0
                Text(
                    "exit $exit",
                    style = type.micro.copy(fontFeatureSettings = Tnum),
                    color = if (bad) colors.danger else colors.success,
                    modifier = Modifier
                        .background(if (bad) colors.dangerSoft else colors.successSoft, CircleShape)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            } else if (item.status == ToolStatus.Error) {
                Spacer(Modifier.width(8.dp))
                Text("failed", style = type.micro, color = colors.danger, modifier = Modifier.background(colors.dangerSoft, CircleShape).padding(horizontal = 8.dp, vertical = 3.dp))
            }
            CopyButton(text = { command }, label = false, clipLabel = "Command", description = "Copy command")
        }
        val body = preview?.text
        if (body.isNullOrBlank()) {
            if (running) {
                TerminalBody("", colors.ink2, cursor = true, fadeTop = false)
            } else {
                Text(
                    if (item.isActive) "Waiting for output…" else "No output",
                    style = type.meta,
                    color = colors.ink3,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        } else {
            val hidden = total - lineCount(body)
            TerminalBody(body, if (item.isError) colors.danger.copy(alpha = 0.9f) else colors.ink2, cursor = running, fadeTop = running && hidden > 0)
            if (!running) {
                val output = item.result ?: item.output
                MoreRow(
                    hidden.coerceAtLeast(0),
                    onClick = { onOpen(ViewerContent("Output", command, toolBody(item, full = true, previewLines = previewLines)?.text.orEmpty())) },
                    leading = { CopyButton(text = { output }, clipLabel = "Output", description = "Copy output") },
                )
            } else if (hidden > 0) {
                MoreRow(hidden, onClick = { onOpen(ViewerContent("Output", command, toolBody(item, full = true, previewLines = previewLines)?.text.orEmpty())) })
            }
        }
    }
}

/** Terminal cursor cell: one monoSmall character (12.5sp on an 18sp line). */
private val CURSOR_W = 7.dp
private val CURSOR_H = 14.dp

/**
 * Output in mono, scrolling sideways (also the plain body of other cards). With [cursor], a 7x14dp ink2 block sits after the last glyph and blinks hard
 * (500 ms on, 500 ms off, no fade); under reduce motion it stays on at 60%. The blink only invalidates drawing.
 * [fadeTop] fades the first lines into the card while a live tail scrolls through.
 */
@Composable
private fun TerminalBody(text: String, color: Color, cursor: Boolean, fadeTop: Boolean) {
    val colors = Pi.colors
    val reduced = Pi.reduceMotion
    val layout = remember { arrayOfNulls<TextLayoutResult>(1) }
    val on = remember { mutableStateOf(true) }
    if (cursor && !reduced) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(500)
                on.value = !on.value
            }
        }
    }
    val ink = colors.ink2
    val surface = colors.surface1
    Box(
        Modifier
            .fillMaxWidth()
            .then(if (fadeTop) Modifier.cardTopFade(surface) else Modifier)
            .then(if (cursor && text.isEmpty()) Modifier.semantics { contentDescription = "Waiting for output" } else Modifier),
    ) {
        Text(
            text,
            style = Pi.type.monoSmall,
            color = color,
            softWrap = false,
            onTextLayout = { layout[0] = it },
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 14.dp, end = if (cursor) 14.dp + CURSOR_W else 14.dp, top = 12.dp, bottom = 12.dp)
                .then(
                    if (!cursor) {
                        Modifier
                    } else {
                        Modifier.drawWithContent {
                            drawContent()
                            val l = layout[0] ?: return@drawWithContent
                            val alpha = if (reduced) 0.6f else if (on.value) 1f else 0f
                            if (alpha == 0f) return@drawWithContent
                            val line = (l.lineCount - 1).coerceAtLeast(0)
                            val x = if (text.isEmpty()) 0f else l.getLineRight(line) + 2.dp.toPx()
                            val top = l.getLineTop(line)
                            val h = CURSOR_H.toPx()
                            val y = top + ((l.getLineBottom(line) - top) - h) / 2
                            drawRect(ink, topLeft = Offset(x, y), size = Size(CURSOR_W.toPx(), h), alpha = alpha)
                        }
                    },
                ),
        )
    }
}

/** A 24dp fade from the card's surface at the top edge of a live tail. */
private fun Modifier.cardTopFade(color: Color): Modifier = drawWithContent {
    drawContent()
    val h = 24.dp.toPx().coerceAtMost(size.height / 2)
    drawRect(Brush.verticalGradient(0f to color, 1f to Color.Transparent, startY = 0f, endY = h), size = Size(size.width, h))
}

@Composable
private fun OutputCard(item: ToolItem, label: ToolLabel, previewLines: Int, onOpen: (ViewerContent) -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val preview = remember(item.status, item.output, item.result, item.argsText, previewLines) { toolBody(item, full = false, previewLines = previewLines) }
    val full = remember(item.status, item.output, item.result, item.args, item.argsText) { toolBody(item, full = true, previewLines = previewLines) }
    val total = remember(full) { full?.let { lineCount(it.text) } ?: 0 }
    CardFrame(running = item.status == ToolStatus.Running) {
        CardHeader {
            Text(label.verb, style = type.label, color = colors.ink2)
            Spacer(Modifier.width(8.dp))
            Text(label.detail, style = type.monoSmall, color = colors.ink1, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
            if (label.kind == ToolKind.Read) {
                val range = rangeChip(item)
                if (range != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(range, style = type.micro.copy(fontFeatureSettings = Tnum), color = colors.ink2, modifier = Modifier.background(colors.surface3, CircleShape).padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
        }
        val text = (preview ?: full)?.text?.let { if (preview == null) head(it, previewLines) else it }
        if (text.isNullOrBlank()) {
            Text(if (item.isActive) "Working…" else "No output", style = type.meta, color = colors.ink3, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
        } else if (label.kind == ToolKind.Read && !item.isError) {
            NumberedBody(text, startLine = startLineOf(item))
            MoreRow((total - lineCount(text)).coerceAtLeast(0)) { onOpen(ViewerContent(label.verb, label.detail, full?.text.orEmpty())) }
        } else {
            TerminalBody(text, if (item.isError) colors.danger.copy(alpha = 0.9f) else colors.ink2, cursor = false, fadeTop = false)
            MoreRow((total - lineCount(text)).coerceAtLeast(0)) { onOpen(ViewerContent(label.verb, label.detail, full?.text.orEmpty())) }
        }
    }
}

private fun rangeChip(item: ToolItem): String? {
    val args = item.args ?: return null
    val offset = (args["offset"] as? JsonPrimitive)?.content?.toIntOrNull()
    val limit = (args["limit"] as? JsonPrimitive)?.content?.toIntOrNull()
    return when {
        offset != null && limit != null -> "L$offset–${offset + limit - 1}"
        offset != null -> "from L$offset"
        limit != null -> "$limit lines"
        else -> null
    }
}

private fun startLineOf(item: ToolItem): Int =
    (item.args?.get("offset") as? JsonPrimitive)?.content?.toIntOrNull()?.coerceAtLeast(1) ?: 1

@Composable
private fun NumberedBody(text: String, startLine: Int) {
    val colors = Pi.colors
    val type = Pi.type
    val lines = remember(text) { text.lines() }
    val width = (startLine + lines.size).toString().length
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Column(Modifier.padding(start = 14.dp, end = 10.dp)) {
            lines.indices.forEach { i ->
                Text((startLine + i).toString().padStart(width), style = type.monoSmall, color = colors.ink4)
            }
        }
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(end = 14.dp)) {
            lines.forEach { Text(it, style = type.monoSmall, color = colors.ink2, softWrap = false, maxLines = 1) }
        }
    }
}

@Composable
private fun DiffCard(item: ToolItem, label: ToolLabel, lines: List<DiffLine>, previewLines: Int, onOpen: (ViewerContent) -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val collapsed = remember(lines) { collapseContext(lines) }
    val shown = collapsed.take(previewLines.coerceAtLeast(6) + 4)
    val stats = remember(lines) { diffStats(lines) }
    CardFrame(running = false) {
        CardHeader {
            Text(label.detail, style = type.monoSmall, color = colors.ink1, maxLines = 1, overflow = TextOverflow.StartEllipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            if (stats.added > 0) Text("+${stats.added}", style = type.meta.copy(fontFeatureSettings = Tnum), color = colors.success)
            if (stats.removed > 0) {
                Spacer(Modifier.width(6.dp))
                Text("−${stats.removed}", style = type.meta.copy(fontFeatureSettings = Tnum), color = colors.danger)
            }
        }
        DiffLines(shown, Modifier.padding(vertical = 6.dp))
        val hidden = collapsed.size - shown.size
        MoreRow(hidden) {
            onOpen(ViewerContent("Diff", label.detail, item.details?.str("diff").orEmpty(), diff = true))
        }
    }
}

