package opensec.pibolt.app.ui.session

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import opensec.pibolt.app.ui.components.CopyButton
import opensec.pibolt.app.ui.components.DiffBlock
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.StepSlot
import opensec.pibolt.app.ui.components.StepState
import opensec.pibolt.app.ui.components.diffText
import opensec.pibolt.app.ui.components.horizontalEdgeFades
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.markdown.CodeInfo
import opensec.pibolt.app.ui.markdown.highlightedCached
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiColors
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Tnum
import opensec.pibolt.protocol.rpc.ImageAttachment
import opensec.pibolt.protocol.session.ToolItem
import opensec.pibolt.protocol.session.ToolStatus
import opensec.pibolt.protocol.str

/** What the full-screen viewer shows. */
@Immutable
data class ViewerContent(val title: String, val subtitle: String?, val text: String, val diff: Boolean = false, val selectable: Boolean = false, val wrap: Boolean = diff)

/** Lines a card shows in place after "Show all"; longer output opens the viewer. */
const val INLINE_MAX_LINES = 400

private fun stepState(item: ToolItem) = when {
    item.isActive -> StepState.Active
    isUnavailable(item) || isDeclinedAsk(item) -> StepState.Skipped
    item.status == ToolStatus.Done || isEmptyResult(item) -> StepState.Done
    item.status == ToolStatus.Error -> StepState.Failed
    else -> StepState.Skipped
}

/**
 * A tool step: 20dp slot (Run/Write spinner, then the result glyph), verb ("Ran" once done), argument, right-hand meta
 * (+/− counts, a failing exit code, a duration of a second or more). Tapping opens a card: terminal output, a diff, a
 * file preview or matches; long output continues in place ("Show all") and then in the full-screen viewer. Images in
 * the result show as thumbnails under the row whether the card is open or not.
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
    onOpenFile: ((String) -> Unit)? = null,
    onImages: ((List<ImageAttachment>, Int) -> Unit)? = null,
) {
    val colors = Pi.colors
    val type = Pi.type
    val label = remember(item.name, item.args, item.argsText) { toolLabel(item) }
    if (isUnavailable(item)) {
        // A tool this host lacks: one quiet line, nothing to open.
        StepHeader(onClick = null, modifier = modifier, slot = { StepSlot(StepState.Skipped, QuadMode.Write, live) }) {
            Text("${label.verb} isn't available here", style = type.meta, color = colors.ink3, maxLines = 1)
        }
        return
    }
    val diff = remember(item.details, item.status) { item.details?.str("diff")?.takeIf { !item.isError }?.let(::parseDiff) }
    val stats = remember(diff) { diff?.let(::diffStats) }
    val written = remember(item.args, item.status) {
        item.args?.str("content")?.takeIf { label.kind == ToolKind.Write && item.status == ToolStatus.Done }
    }
    val exit = if (label.kind == ToolKind.Bash) exitCodeOf(item) else null
    val empty = isEmptyResult(item)
    val mode = if (item.status == ToolStatus.Running) {
        if (isSubagentTool(item.name)) QuadMode.Agent else QuadMode.Run
    } else {
        QuadMode.Write
    }
    Column(modifier.fillMaxWidth()) {
        StepHeader(onClick = onToggle, onLongClick = onLongPress, slot = { StepSlot(stepState(item), mode, live) }) {
            // A failed step says so in its verb too, as on the desktop (the cross alone is easy to miss in a long turn).
            val failed = stepState(item) == StepState.Failed
            Text(if (item.isActive) label.verb else label.past, style = type.label, color = if (failed) colors.danger else colors.ink2, maxLines = 1)
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
            val num = type.meta.copy(fontFeatureSettings = Tnum)
            when {
                stats != null -> {
                    ChangeCounts(stats.added, stats.removed)
                    Spacer(Modifier.width(8.dp))
                }
                written != null -> {
                    ChangeCounts(lineCount(written), 0)
                    Spacer(Modifier.width(8.dp))
                }
            }
            if (exit != null && exit != 0) {
                Text("exit $exit", style = num, color = colors.danger, maxLines = 1)
                Spacer(Modifier.width(8.dp))
            } else if (empty) {
                Text("no results", style = type.meta, color = colors.ink3, maxLines = 1)
                Spacer(Modifier.width(8.dp))
            }
            if (item.status == ToolStatus.Running && runningSince != null) {
                StepElapsed(runningSince)
            } else if (!item.isActive) {
                stepDuration(item.durationMs)?.let { Text(it, style = num, color = colors.ink3) }
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
                    modifier = Modifier.padding(start = STEP_TEXT_START, end = 8.dp, bottom = 4.dp),
                )
            }
        }
        if (item.images.isNotEmpty()) ToolImages(item.images, onImages)
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(PiMotion.inPlace(), expandFrom = Alignment.Top) + fadeIn(PiMotion.inPlace(PiMotion.QUICK)),
            exit = shrinkVertically(PiMotion.leave(PiMotion.STANDARD), shrinkTowards = Alignment.Top) + fadeOut(PiMotion.leave(PiMotion.QUICK)),
        ) {
            // The card hangs under the step's text, right of the slot column, so an opened fold's rail runs past it.
            Box(Modifier.padding(start = STEP_TEXT_START, top = 2.dp, bottom = 8.dp)) {
                CompositionLocalProvider(LocalCardFailed provides (stepState(item) == StepState.Failed)) {
                    when {
                        diff != null -> DiffCard(item, label, diff, previewLines, onOpen, onOpenFile)
                        written != null -> FileCard(item, label, written, 1, previewLines, onOpen, onOpenFile)
                        label.kind == ToolKind.Bash -> BashCard(item, label, previewLines, onOpen)
                        label.kind == ToolKind.Read && !item.isError && item.images.isEmpty() -> FileCard(item, label, readText(item.result ?: item.output), startLineOf(item), previewLines, onOpen, onOpenFile)
                        (label.kind == ToolKind.Search || label.kind == ToolKind.List) && !item.isError -> MatchesCard(item, label, previewLines, onOpen)
                        else -> OutputCard(item, label, previewLines, onOpen)
                    }
                }
            }
        }
    }
}

/** Where a step's text starts: the 20dp slot and its 12dp gap. Cards and lines that belong to a step start here. */
val STEP_TEXT_START = 32.dp

/** True inside the card of a failed step: its outline turns danger. */
private val LocalCardFailed = staticCompositionLocalOf { false }

/** "+12 −3" in success and danger, tabular. */
@Composable
private fun ChangeCounts(added: Int, removed: Int) {
    val colors = Pi.colors
    val num = Pi.type.meta.copy(fontFeatureSettings = Tnum)
    if (added > 0) Text("+$added", style = num, color = colors.success, maxLines = 1)
    if (removed > 0) {
        if (added > 0) Spacer(Modifier.width(6.dp))
        Text("−$removed", style = num, color = colors.danger, maxLines = 1)
    }
}

/** A tool card: surface1, radius md, 1dp line (danger at 32% for a failed step, a hairline rather than a fill). */
@Composable
private fun CardFrame(content: @Composable () -> Unit) {
    val colors = Pi.colors
    val shape = RoundedCornerShape(Radius.md)
    val failed = LocalCardFailed.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface1)
            .border(1.dp, if (failed) colors.danger.copy(alpha = 0.32f) else colors.line, shape),
    ) { content() }
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

/**
 * The card's footer, only when something is cut: "Show all 140 lines" (in place, up to [INLINE_MAX_LINES]) or "Open
 * output" / "Open file" / "Open diff" past that.
 */
@Composable
private fun CardFooter(text: String, inPlace: Boolean, onClick: () -> Unit) {
    val colors = Pi.colors
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .pressable(onClick, shape = RoundedCornerShape(0.dp), pressedScale = 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(if (inPlace) Icons.Rounded.UnfoldMore else Icons.Rounded.OpenInFull, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = Pi.type.label, color = colors.ink2)
    }
}

/** How much of [total] lines a card shows: [preview] folded, all (up to the inline cap) once opened. */
private fun shownLines(total: Int, preview: Int, all: Boolean): Int = if (all) minOf(total, INLINE_MAX_LINES) else minOf(total, preview)

/** The footer for a body that shows [shown] of [total] lines. */
@Composable
private fun MoreFooter(total: Int, shown: Int, all: Boolean, noun: String, onAll: () -> Unit, onOpen: () -> Unit) {
    if (shown >= total) return
    if (!all && total <= INLINE_MAX_LINES) {
        CardFooter("Show all $total lines", inPlace = true, onClick = onAll)
    } else {
        CardFooter("Open $noun", inPlace = false, onClick = onOpen)
    }
}

/**
 * A bash step as a terminal: `$` and the command, the exit badge and one Copy (the output), then the output in mono on
 * the canvas with its ANSI colours. Plain output stays ink2 whatever the exit code; only lines that read as failures
 * turn danger. While it runs, the last lines show with a top fade and a blinking cursor; done, the tail shows and "Show
 * all" opens the rest in place.
 */
@Composable
private fun BashCard(item: ToolItem, label: ToolLabel, previewLines: Int, onOpen: (ViewerContent) -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val command = remember(item.args, item.argsText) { item.args?.str("command") ?: label.detail }
    val running = item.status == ToolStatus.Running
    val raw = if (running) item.output else item.result ?: item.output
    val parsed = remember(raw) { Ansi.parse(withoutExitLine(raw)) }
    val total = remember(parsed) { lineCount(parsed.text) }
    var all by rememberSaveable(item.key) { mutableStateOf(false) }
    val shown = if (running) minOf(total, previewLines) else shownLines(total, previewLines, all)
    val exit = exitCodeOf(item)
    CardFrame {
        CardHeader(end = 2.dp) {
            Text("$", style = type.mono.copy(fontWeight = FontWeight.SemiBold), color = if (exit != null && exit != 0) colors.danger else colors.success)
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
            CopyButton(text = { parsed.text }, label = false, clipLabel = "Output", description = "Copy output")
        }
        if (parsed.text.isBlank()) {
            if (running) {
                TerminalBody(AnnotatedString(""), cursor = true, fadeTop = false)
            } else {
                Text(if (item.isActive) "Waiting for output…" else "No output", style = type.meta, color = colors.ink3, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
            }
            return@CardFrame
        }
        // The tail: test summaries and errors come last.
        val body = remember(parsed, shown, colors) { ansiAnnotated(tailOf(parsed, shown), colors, colors.ink2) }
        TerminalBody(body, cursor = running, fadeTop = running && total > shown)
        if (!running) {
            MoreFooter(total, shown, all, "output", onAll = { all = true }) {
                onOpen(ViewerContent("Output", command, parsed.text.take(MAX_SHOWN)))
            }
        }
    }
}

/** The last [lines] lines of [text], with their spans. */
private fun tailOf(text: AnsiText, lines: Int): AnsiText {
    val t = text.text.trimEnd('\n')
    var start = 0
    var i = t.length
    var n = 0
    while (i > 0) {
        val nl = t.lastIndexOf('\n', i - 1)
        if (nl < 0) break
        n++
        if (n == lines) {
            start = nl + 1
            break
        }
        i = nl
    }
    if (start == 0 && t.length == text.text.length) return text
    val spans = text.spans.mapNotNull { s ->
        val a = maxOf(s.start, start)
        val b = minOf(s.end, t.length)
        if (b > a) AnsiSpan(a - start, b - start, s.style) else null
    }
    return AnsiText(t.substring(start), spans)
}

/** Terminal cursor cell: one monoSmall character (12.5sp on an 18sp line). */
private val CURSOR_W = 7.dp
private val CURSOR_H = 14.dp

/**
 * Output in mono on the canvas, scrolling sideways. With [cursor], a 7x14dp ink2 block sits after the last glyph and
 * blinks hard (500 ms on, 500 ms off, no fade); under reduce motion it stays on at 60%. The blink only invalidates
 * drawing. [fadeTop] fades the first lines into the card while a live tail scrolls through.
 */
@Composable
private fun TerminalBody(text: AnnotatedString, cursor: Boolean, fadeTop: Boolean) {
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
    val surface = colors.canvas
    val scroll = rememberScrollState()
    Box(
        Modifier
            .fillMaxWidth()
            .background(surface)
            .then(if (fadeTop) Modifier.cardTopFade(surface) else Modifier)
            .then(if (cursor && text.isEmpty()) Modifier.semantics { contentDescription = "Waiting for output" } else Modifier),
    ) {
        Text(
            text,
            style = Pi.type.monoSmall,
            color = colors.ink2,
            softWrap = false,
            onTextLayout = { layout[0] = it },
            modifier = Modifier
                .fillMaxWidth()
                .horizontalEdgeFades(scroll, surface)
                .horizontalScroll(scroll)
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

/**
 * A path in a card header: mono ink1, start-ellipsised, taking the room up to the header's trailing meta (two weighted
 * children would split it and cut the path at half the card); a tap opens the file when [onOpenFile] is set.
 */
@Composable
private fun RowScope.PathChip(path: String, onOpenFile: ((String) -> Unit)?) {
    val colors = Pi.colors
    Text(
        path,
        style = Pi.type.monoSmall,
        color = colors.ink1,
        maxLines = 1,
        overflow = TextOverflow.StartEllipsis,
        modifier = Modifier
            .weight(1f)
            .then(
                if (onOpenFile != null) {
                    Modifier
                        .heightIn(min = 44.dp)
                        .pressable({ onOpenFile(path) }, shape = RoundedCornerShape(Radius.xs), pressedScale = 1f, label = "Open ${baseName(path)}")
                        .padding(vertical = 13.dp)
                } else {
                    Modifier
                },
            ),
    )
}

/** Text of a read result without Pi's trailing "[Showing lines … Use offset=… to continue.]" note. */
private val continueNote = Regex("""\n*\[(Showing lines|\d+ more lines)[^\n]*]\s*$""")

fun readText(result: String): String = continueNote.replace(result, "").trimEnd('\n')

private fun startLineOf(item: ToolItem): Int =
    (item.args?.get("offset") as? JsonPrimitive)?.content?.toIntOrNull()?.coerceAtLeast(1) ?: 1

/**
 * A file's text with line numbers from [startLine] and the syntax colours of its path: a read result, or what a write
 * put in a new file. Header: the path (tap to open the file) and the line range.
 */
@Composable
private fun FileCard(
    item: ToolItem,
    label: ToolLabel,
    text: String,
    startLine: Int,
    previewLines: Int,
    onOpen: (ViewerContent) -> Unit,
    onOpenFile: ((String) -> Unit)?,
) {
    val colors = Pi.colors
    val type = Pi.type
    val path = remember(item.args) { item.args?.str("path") ?: item.args?.str("file_path") ?: label.detail.substringBefore(':') }
    val language = remember(path) { CodeInfo.languageOfFile(path).orEmpty() }
    val total = remember(text) { lineCount(text) }
    var all by rememberSaveable(item.key) { mutableStateOf(false) }
    val shown = shownLines(total, previewLines, all)
    CardFrame {
        CardHeader {
            PathChip(path, onOpenFile)
            if (total > 0) {
                Spacer(Modifier.width(8.dp))
                val range = "L$startLine–${startLine + total - 1}"
                Text(range, style = type.micro.copy(fontFeatureSettings = Tnum), color = colors.ink2, maxLines = 1, modifier = Modifier.background(colors.surface3, CircleShape).padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
        if (text.isBlank()) {
            Text(if (item.images.isNotEmpty()) "Image" else "Empty file", style = type.meta, color = colors.ink3, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
            return@CardFrame
        }
        val body = remember(text, shown, language, colors) { numbered(head(text, shown), startLine, language, colors) }
        NumberedBody(body)
        MoreFooter(total, shown, all, "file", onAll = { all = true }) {
            if (onOpenFile != null) onOpenFile(path) else onOpen(ViewerContent(baseName(path), path, text))
        }
    }
}

/** Gutter and code of a numbered preview, each one text (a line per row). */
@Immutable
private class Numbered(val gutter: String, val code: AnnotatedString)

private fun numbered(text: String, startLine: Int, language: String, colors: PiColors): Numbered {
    val lines = text.replace("\t", "    ").split('\n')
    val width = (startLine + lines.size - 1).toString().length
    val gutter = lines.indices.joinToString("\n") { (startLine + it).toString().padStart(width) }
    val joined = lines.joinToString("\n")
    val code = if (language.isEmpty()) AnnotatedString(joined) else highlightedCached(joined, language, colors)
    return Numbered(gutter, code)
}

@Composable
private fun NumberedBody(body: Numbered) {
    val colors = Pi.colors
    val style = Pi.type.monoSmall
    val scroll = rememberScrollState()
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(body.gutter, style = style, color = colors.ink3, softWrap = false, modifier = Modifier.padding(start = 14.dp, end = 10.dp))
        Box(Modifier.weight(1f).horizontalEdgeFades(scroll, colors.surface1).horizontalScroll(scroll)) {
            Text(body.code, style = style, color = colors.ink2, softWrap = false, modifier = Modifier.padding(end = 14.dp))
        }
    }
}

private val grepLine = Regex("""^(.+?)([:-])(\d+)([:-])(.*)$""")

/**
 * Search and listing results: grep lines as path (ink1), line number (ink3) and text (ink2) with the pattern marked;
 * find and ls lines as paths, folders in ink1.
 */
@Composable
private fun MatchesCard(item: ToolItem, label: ToolLabel, previewLines: Int, onOpen: (ViewerContent) -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val text = remember(item.result, item.output) { readText(item.result ?: item.output) }
    val total = remember(text) { lineCount(text) }
    var all by rememberSaveable(item.key) { mutableStateOf(false) }
    val shown = shownLines(total, previewLines, all)
    val pattern = remember(item.args) { item.args?.str("pattern").orEmpty() }
    CardFrame {
        CardHeader {
            Text(label.past, style = type.label, color = colors.ink2)
            Spacer(Modifier.width(8.dp))
            Text(label.detail, style = type.monoSmall, color = colors.ink1, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
            if (total > 0 && !isEmptyResult(item)) {
                Spacer(Modifier.width(8.dp))
                Text(if (total == 1) "1 line" else "$total lines", style = type.micro.copy(fontFeatureSettings = Tnum), color = colors.ink2, modifier = Modifier.background(colors.surface3, CircleShape).padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
        if (text.isBlank() || isEmptyResult(item)) {
            Text(if (isEmptyResult(item)) firstLine(item.result.orEmpty()).ifBlank { "No results" } else "No results", style = type.meta, color = colors.ink3, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
            return@CardFrame
        }
        val body = remember(text, shown, colors, pattern, label.kind) { matches(head(text, shown), pattern, label.kind == ToolKind.List, colors) }
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxWidth().horizontalEdgeFades(scroll, colors.surface1).horizontalScroll(scroll)) {
            Text(body, style = type.monoSmall, color = colors.ink2, softWrap = false, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
        }
        MoreFooter(total, shown, all, "results", onAll = { all = true }) { onOpen(ViewerContent(label.past, label.detail, text)) }
    }
}

private fun firstLine(text: String): String = text.trim().lineSequence().firstOrNull().orEmpty()

private fun matches(text: String, pattern: String, listing: Boolean, colors: PiColors): AnnotatedString = buildAnnotatedString {
    val literal = pattern.takeIf { it.isNotEmpty() && it.none { c -> c in "\\[](){}*+?|^$" } }
    text.split('\n').forEachIndexed { i, line ->
        if (i > 0) append('\n')
        val m = if (listing) null else grepLine.find(line)
        if (m != null && m.groupValues[2] == m.groupValues[4]) {
            val (path, sep, number, _, rest) = m.destructured
            pushStyle(SpanStyle(color = colors.ink1))
            append(path)
            pop()
            pushStyle(SpanStyle(color = colors.ink3))
            append(sep)
            append(number)
            append(sep)
            pop()
            val start = length
            append(rest)
            if (literal != null) {
                var at = rest.indexOf(literal, ignoreCase = true)
                while (at >= 0) {
                    addStyle(SpanStyle(color = colors.warning), start + at, start + at + literal.length)
                    at = rest.indexOf(literal, at + literal.length, ignoreCase = true)
                }
            }
        } else {
            val dir = line.endsWith("/")
            pushStyle(SpanStyle(color = if (dir) colors.ink1 else colors.ink2))
            append(line)
            pop()
        }
    }
}

/** Any other tool: its arguments' gist and the result in mono; an error reads in danger. */
@Composable
private fun OutputCard(item: ToolItem, label: ToolLabel, previewLines: Int, onOpen: (ViewerContent) -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val preview = remember(item.status, item.output, item.result, item.argsText, previewLines) { toolBody(item, full = false, previewLines = previewLines) }
    val full = remember(item.status, item.output, item.result, item.args, item.argsText) { toolBody(item, full = true, previewLines = previewLines) }
    val total = remember(full) { full?.let { lineCount(it.text) } ?: 0 }
    CardFrame {
        CardHeader {
            Text(if (item.isActive) label.verb else label.past, style = type.label, color = colors.ink2)
            Spacer(Modifier.width(8.dp))
            Text(label.detail, style = type.monoSmall, color = colors.ink1, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
        }
        val text = (preview ?: full)?.text?.let { if (preview == null) head(it, previewLines) else it }
        if (text.isNullOrBlank()) {
            Text(if (item.isActive) "Working…" else "No output", style = type.meta, color = colors.ink3, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
            return@CardFrame
        }
        val body = remember(text, item.isError, colors) { AnnotatedString(text, SpanStyle(color = if (item.isError) colors.danger.copy(alpha = 0.9f) else colors.ink2)) }
        TerminalBody(body, cursor = false, fadeTop = false)
        if (total > lineCount(text)) CardFooter("Open output", inPlace = false) { onOpen(ViewerContent(label.past, label.detail, full?.text.orEmpty())) }
    }
}

/**
 * An edit's diff: the path (tap to open the file), +/− counts and an open-in-full button, then the rows with old and
 * new line numbers, syntax colours and word marks. Folded, it shows the first rows; "Show all" opens the rest in place.
 */
@Composable
private fun DiffCard(
    item: ToolItem,
    label: ToolLabel,
    lines: List<DiffLine>,
    previewLines: Int,
    onOpen: (ViewerContent) -> Unit,
    onOpenFile: ((String) -> Unit)?,
) {
    val colors = Pi.colors
    val path = remember(item.args) { item.args?.str("path") ?: item.args?.str("file_path") ?: label.detail }
    val language = remember(path) { CodeInfo.languageOfFile(path).orEmpty() }
    val collapsed = remember(lines) { collapseContext(lines) }
    val stats = remember(lines) { diffStats(lines) }
    var all by rememberSaveable(item.key) { mutableStateOf(false) }
    val shown = shownLines(collapsed.size, previewLines.coerceAtLeast(6) + 4, all)
    val open = { onOpen(ViewerContent("Diff", path, item.details?.str("diff").orEmpty(), diff = true)) }
    CardFrame {
        CardHeader(end = 2.dp) {
            PathChip(path, onOpenFile)
            Spacer(Modifier.width(8.dp))
            ChangeCounts(stats.added, stats.removed)
            IconAction(Icons.Rounded.OpenInFull, "Open diff", onClick = open, size = 16.dp, tint = colors.ink3)
        }
        val text = remember(collapsed, shown, language, colors) { diffText(collapsed.take(shown), language, colors) }
        DiffBlock(text)
        MoreFooter(collapsed.size, shown, all, "diff", onAll = { all = true }, onOpen = open)
    }
}

/** Thumbnails of the images in a tool result (decoded off the main thread, 120dp tall); a tap opens them full screen. */
@Composable
private fun ToolImages(images: List<ImageAttachment>, onOpen: ((List<ImageAttachment>, Int) -> Unit)?) {
    val colors = Pi.colors
    val shape = RoundedCornerShape(Radius.sm)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = STEP_TEXT_START, top = 2.dp, bottom = 6.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        images.forEachIndexed { i, image ->
            val bitmap by produceState<ImageBitmap?>(null, image) { value = decodeThumb(image) }
            val b = bitmap
            val aspect = if (b != null && b.height > 0) (b.width.toFloat() / b.height).coerceIn(0.4f, 2.5f) else 1f
            val open = onOpen?.let { Modifier.pressable({ it(images, i) }, shape = shape, label = "Open image ${i + 1}") } ?: Modifier
            Box(
                Modifier
                    .height(THUMB)
                    .width(THUMB * aspect)
                    .widthIn(min = 48.dp)
                    .then(open)
                    .clip(shape)
                    .background(colors.surface2)
                    .border(1.dp, colors.line, shape),
            ) {
                if (b != null) {
                    Image(b, contentDescription = "Image ${i + 1} from the tool result", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().fillMaxHeight())
                }
            }
        }
    }
}

private val THUMB = 120.dp

/** A thumbnail of at most about 480px on the long side. */
private suspend fun decodeThumb(image: ImageAttachment): ImageBitmap? = withContext(Dispatchers.Default) {
    runCatching {
        val bytes = Base64.decode(image.base64, Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 480) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()
}
