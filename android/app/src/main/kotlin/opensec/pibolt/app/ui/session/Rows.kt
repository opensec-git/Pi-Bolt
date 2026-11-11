package opensec.pibolt.app.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import opensec.pibolt.app.ui.components.CopyButton
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.ShimmerText
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.ui.components.WorkingLine
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.markdown.MdBlock
import opensec.pibolt.app.ui.markdown.MdBlockView
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Space
import opensec.pibolt.app.ui.theme.Tnum
import opensec.pibolt.protocol.session.NoticeItem
import opensec.pibolt.protocol.session.NoticeKind
import opensec.pibolt.protocol.session.ThinkingItem
import opensec.pibolt.protocol.session.UserItem
import kotlin.math.min

/** New rows of the live turn rise into place once; history and rows seen before never animate. */
@Composable
fun Modifier.riseIn(key: String, live: Boolean, memory: SessionMemory, distance: Dp): Modifier {
    val reduced = Pi.reduceMotion
    val animate = remember(key) { live && !reduced && memory.primed && memory.seen.add(key) }
    if (!animate) {
        memory.seen.add(key)
        return this
    }
    val px = with(LocalDensity.current) { distance.toPx() }
    val offset = remember(key) { Animatable(px) }
    LaunchedEffect(key) { offset.animateTo(0f, PiMotion.enter(PiMotion.STANDARD)) }
    return graphicsLayer { translationY = offset.value }
}

/**
 * A long press without a click, for messages: it opens their actions sheet (with the long-press haptic), and TalkBack
 * offers it as a long-click action only, instead of announcing every paragraph as a button that does nothing. Taps
 * pass through to links inside the text.
 */
@Composable
fun Modifier.longPress(label: String, onLongPress: () -> Unit): Modifier {
    val haptics = Pi.haptics
    val current by rememberUpdatedState(onLongPress)
    return this
        .pointerInput(Unit) {
            detectTapGestures(onLongPress = {
                haptics.longPress()
                current()
            })
        }
        .semantics { onLongClick(label) { current(); true } }
}

// User

/**
 * A user message: a right-aligned soft bubble (the redesign's: radius bubble 22 on every corner, surface2 and a subtle
 * 1dp line, at most 80% wide). Images sent from this phone show as 56dp thumbnails above the text; images without a
 * local copy (sent from elsewhere, or before this session) read as a compact "2 images" chip. A message dictated on this
 * phone ([dictated]) carries a small waveform badge on its top-start corner.
 */
@Composable
fun UserBubble(item: UserItem, onLongPress: () -> Unit, modifier: Modifier = Modifier, thumbs: List<ImageBitmap>? = null, onImage: ((Int) -> Unit)? = null, dictated: Boolean = false) {
    val colors = Pi.colors
    val shape = RoundedCornerShape(Radius.bubble)
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Box(Modifier.widthIn(max = maxWidth * 0.8f)) {
            Column(
                Modifier
                    .longPress("Message actions", onLongPress)
                    .background(colors.surface2, shape)
                    .border(1.dp, colors.line, shape)
                    .padding(horizontal = if (thumbs.isNullOrEmpty()) Space.lg else Space.md, vertical = Space.md),
                horizontalAlignment = Alignment.End,
            ) {
                if (!thumbs.isNullOrEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        thumbs.forEachIndexed { i, thumb ->
                            val open = onImage?.let { Modifier.pressable({ it(i) }, shape = RoundedCornerShape(Radius.md), label = "Open image ${i + 1}") } ?: Modifier
                            ImageThumb(thumb, Modifier.size(56.dp).then(open))
                        }
                    }
                    if (item.text.isNotBlank()) Spacer(Modifier.height(Space.sm))
                } else if (item.imageCount > 0) {
                    ImagesChip(item.imageCount)
                    if (item.text.isNotBlank()) Spacer(Modifier.height(Space.sm))
                }
                if (item.text.isNotBlank()) {
                    Text(
                        item.text,
                        style = Pi.type.prose,
                        color = colors.ink1,
                        modifier = Modifier.padding(horizontal = if (thumbs.isNullOrEmpty()) 0.dp else Space.xs).align(Alignment.Start),
                    )
                }
            }
            if (dictated) DictatedBadge(Modifier.align(Alignment.TopStart).offset(x = (-6).dp, y = (-6).dp))
        }
    }
}

/** The waveform badge of a dictated message: a 22dp surface3 circle with a 1dp lineStrong ring and a 12dp ink2 glyph. */
@Composable
private fun DictatedBadge(modifier: Modifier = Modifier) {
    val colors = Pi.colors
    Box(
        modifier
            .size(22.dp)
            .background(colors.surface3, CircleShape)
            .border(1.dp, colors.lineStrong, CircleShape)
            .semantics { contentDescription = "Dictated" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(12.dp))
    }
}

/** A rounded image thumbnail (radius md, 1dp line), cropped to fill. */
@Composable
fun ImageThumb(thumb: ImageBitmap, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Radius.md)
    Image(
        thumb,
        contentDescription = "Image",
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(shape).border(1.dp, Pi.colors.line, shape),
    )
}

/** "2 images" for images this phone has no copy of. */
@Composable
private fun ImagesChip(count: Int) {
    val colors = Pi.colors
    Row(
        Modifier
            .background(colors.surface3, RoundedCornerShape(Radius.xs))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Image, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (count == 1) "1 image" else "$count images", style = Pi.type.meta, color = colors.ink2)
    }
}

/** A prompt on its way to the host: the bubble at 60% with "Sending" under it, until the host echoes it. */
@Composable
fun PendingBubble(text: String, modifier: Modifier = Modifier, thumbs: List<ImageBitmap>? = null, imageCount: Int = 0, dictated: Boolean = false) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        UserBubble(UserItem("pending", text, imageCount), onLongPress = {}, modifier = Modifier.graphicsLayer { alpha = 0.6f }, thumbs = thumbs, dictated = dictated)
        Text("Sending", style = Pi.type.meta, color = Pi.colors.ink3, modifier = Modifier.padding(top = 4.dp, end = 4.dp))
    }
}

// Assistant text

/** One block of assistant prose: gaps depend on the block kind (headings get air above, list items sit close). */
@Composable
fun TextRowView(row: TextRow, previous: MdBlock?, onOpenCode: (String, String) -> Unit, onLongPress: () -> Unit, modifier: Modifier = Modifier) {
    val gap = when {
        row.title -> 2.dp
        row.first -> 4.dp
        row.block is MdBlock.Heading -> 20.dp
        row.block is MdBlock.ListItem && previous is MdBlock.ListItem && !row.block.startsList -> 4.dp
        previous is MdBlock.Heading -> 8.dp
        else -> 12.dp
    }
    MdBlockView(
        row.block,
        streaming = row.tail,
        onOpenCode = onOpenCode,
        proseInk = Pi.colors.ink2,
        title = row.title,
        modifier = modifier
            .padding(top = gap)
            // Prose between the steps of an opened fold lines up with the step text, right of the rail.
            .padding(start = if (row.rail != null) STEP_TEXT_START else 0.dp)
            .longPress("Message actions", onLongPress),
    )
}

// Thinking

@Composable
fun ThinkingRowView(item: ThinkingItem, live: Boolean, expanded: Boolean, durationMs: Long?, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    val streaming = item.streaming && live
    val label = when {
        item.redacted && item.text.isBlank() -> "Thinking (redacted)"
        streaming -> "Thinking"
        durationMs != null -> "Thought for ${formatWorked(durationMs)}"
        else -> "Thought"
    }
    Column(modifier.fillMaxWidth()) {
        StepHeader(
            onClick = onToggle,
            slot = {
                if (streaming) QuadSpinner(QuadMode.Think, size = IconSize.xs, color = colors.ink2) else Chevron(expanded)
            },
        ) {
            if (streaming) ShimmerText(label, type.label) else Text(label, style = type.label, color = colors.ink3)
            if (streaming) {
                Spacer(Modifier.width(4.dp))
                Chevron(expanded)
            }
        }
        val showBody = item.text.isNotBlank() && (expanded || streaming)
        AnimatedVisibility(
            visible = showBody,
            enter = expandVertically(PiMotion.inPlace()) + fadeIn(PiMotion.inPlace(PiMotion.QUICK)),
            exit = shrinkVertically(PiMotion.leave(PiMotion.STANDARD)) + fadeOut(PiMotion.leave(PiMotion.QUICK)),
        ) {
            val preview = !expanded && streaming
            Row(Modifier.padding(start = 9.dp, top = 2.dp, bottom = 6.dp).height(IntrinsicSize.Min)) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(colors.lineStrong))
                if (preview) {
                    ReasoningPreview(item.text, Modifier.padding(start = 22.dp))
                } else {
                    Text(
                        if (expanded) item.text.trim() else tail(item.text, 5),
                        style = type.thinking,
                        color = colors.ink3,
                        modifier = Modifier.padding(start = 22.dp),
                    )
                }
            }
        }
    }
}

/** Lines of live reasoning shown at once. */
private const val PREVIEW_LINES = 5

/**
 * Live reasoning as a 5-line window pinned to the newest text. New lines glide up (the window scrolls to the bottom
 * over 120 ms) instead of the text jumping a line at a time. Only a tail of the text is laid out: the start moves in
 * large steps (see [previewStart]), each a moment where the pinned bottom looks the same before and after.
 */
@Composable
private fun ReasoningPreview(text: String, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    val reduced = Pi.reduceMotion
    val anchor = remember { IntArray(1) }
    anchor[0] = previewStart(text, anchor[0])
    val shown = text.substring(anchor[0]).trimEnd()
    val scroll = rememberScrollState()
    val lineHeight = with(LocalDensity.current) { type.thinking.lineHeight.toDp() }
    LaunchedEffect(scroll) {
        var first = true
        snapshotFlow { scroll.maxValue }.collectLatest { bottom ->
            if (first || reduced) scroll.scrollTo(bottom) else scroll.animateScrollTo(bottom, PiMotion.inPlace(120))
            first = false
        }
    }
    Box(
        modifier
            .heightIn(max = lineHeight * PREVIEW_LINES)
            .topFade(colors.canvas)
            .verticalScroll(scroll, enabled = false),
    ) {
        Text(shown, style = type.thinking, color = colors.ink3)
    }
}

/**
 * Where the live reasoning window starts: [current] until more than 60 lines follow it, then 30 lines from the end.
 * The window so moves rarely and never backwards.
 */
fun previewStart(text: String, current: Int): Int {
    val from = current.coerceIn(0, text.length)
    var count = 0
    var keep = from
    var i = text.length
    while (i > 0) {
        val nl = text.lastIndexOf('\n', i - 1)
        if (nl < from) break
        count++
        if (count == 30) keep = nl + 1
        if (count > 60) return keep
        i = nl
    }
    return from
}

private fun Modifier.topFade(color: Color): Modifier = drawWithContent {
    drawContent()
    val h = 24.dp.toPx().coerceAtMost(size.height / 2)
    drawRect(Brush.verticalGradient(0f to color, 1f to Color.Transparent, startY = 0f, endY = h), size = Size(size.width, h))
}

@Composable
fun Chevron(expanded: Boolean, modifier: Modifier = Modifier) {
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, PiMotion.inPlace(PiMotion.QUICK), label = "chevron")
    Icon(
        Icons.Rounded.ChevronRight,
        contentDescription = if (expanded) "Collapse" else "Expand",
        // ink3, like every other chevron: it is the only sign the row opens, so it needs 3:1 (ink4 is ~2:1).
        tint = Pi.colors.ink3,
        modifier = modifier.size(18.dp).graphicsLayer { rotationZ = rotation },
    )
}

/** A timeline row: 20dp leading slot, 12dp gap, content; 40dp tall, padded to a 48dp touch target. */
@Composable
fun StepHeader(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    slot: @Composable () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .then(if (onClick != null) Modifier.pressable(onClick, shape = RoundedCornerShape(Radius.sm), pressedScale = 1f, onLongClick = onLongClick) else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { slot() }
        Spacer(Modifier.width(12.dp))
        content()
    }
}

// Fold

/**
 * The start of a finished turn's reply: "Finished 1m 12s" (rowMeta ink3; the time only when this phone watched the
 * turn), "Stopped" after an abort and "Failed" after an error, each after a 6dp dot (ink3 / danger).
 */
@Composable
fun TurnMetaRowView(row: TurnMetaRow, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    if (row.end == TurnEnd.Finished && LocalPlainChat.current) {
        Spacer(modifier.height(Space.sm))
        return
    }
    val text = buildString {
        append(
            when (row.end) {
                TurnEnd.Finished -> "Finished"
                TurnEnd.Failed -> "Failed"
                TurnEnd.Stopped -> "Stopped"
            },
        )
        row.durationMs?.let { append(' ').append(formatWorked(it)) }
    }
    Row(modifier.fillMaxWidth().padding(top = Space.sm, bottom = Space.xxs), verticalAlignment = Alignment.CenterVertically) {
        if (row.end != TurnEnd.Finished) {
            Box(Modifier.size(6.dp).background(if (row.end == TurnEnd.Failed) colors.danger else colors.ink3, CircleShape))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = Pi.type.rowMeta.copy(fontFeatureSettings = Tnum), color = colors.ink3, maxLines = 1)
    }
}

/**
 * The folded work of a finished turn, one quiet line: "Used 4 tools" (rowMeta ink3; "Thought" when it only reasoned)
 * and a chevron; tap opens the steps, then the chips of the files it edited. A failed step adds "· 1 failed" after a 6dp
 * danger dot. TalkBack reads the full summary ("Edited 3 files · ran 2 commands").
 */
@Composable
fun FoldRowView(row: FoldRow, onToggle: () -> Unit, onOpenFile: (FileChange) -> Unit, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    val label = when {
        row.tools == 1 -> "Used 1 tool"
        row.tools > 1 -> "Used ${row.tools} tools"
        else -> row.summary.ifBlank { if (row.steps == 1) "1 step" else "${row.steps} steps" }
    }
    val spoken = buildString {
        append(label)
        if (row.summary.isNotBlank() && row.summary != label) append(": ").append(row.summary)
        if (row.failedSteps > 0) append(", ").append(row.failedSteps).append(" failed")
    }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .pressable(onToggle, shape = RoundedCornerShape(Radius.sm), pressedScale = 1f, label = if (row.expanded) "Hide steps" else "Show steps")
                .semantics(mergeDescendants = true) {
                    contentDescription = spoken
                    stateDescription = if (row.expanded) "Expanded" else "Collapsed"
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = type.rowMeta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (row.failedSteps > 0) {
                Text(" · ", style = type.rowMeta, color = colors.ink3, maxLines = 1)
                Box(Modifier.size(6.dp).background(colors.danger, CircleShape))
                Text(" ${row.failedSteps} failed", style = type.rowMeta.copy(fontFeatureSettings = Tnum), color = colors.ink3, maxLines = 1)
            }
            Spacer(Modifier.width(Space.xs))
            Chevron(row.expanded)
        }
        if (row.expanded && row.files.isNotEmpty()) FileChips(row.files, onOpenFile, onMore = onToggle)
    }
}

/** At most this many file chips under a fold; the rest read "+k more". */
private const val MAX_FILE_CHIPS = 3

/** The files a turn changed, as chips under its fold: name, +added, −removed; tap opens the diff. */
@Composable
private fun FileChips(files: List<FileChange>, onOpen: (FileChange) -> Unit, onMore: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val shape = RoundedCornerShape(Radius.sm)
    val num = type.meta.copy(fontFeatureSettings = Tnum)
    FlowRow(
        Modifier.fillMaxWidth().padding(bottom = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (file in files.take(MAX_FILE_CHIPS)) {
            Row(
                Modifier
                    .height(28.dp)
                    .pressable({ onOpen(file) }, shape = shape, label = "Open diff of ${baseName(file.path)}")
                    .background(colors.surface1, shape)
                    .border(1.dp, colors.line, shape)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(baseName(file.path), style = type.monoMeta, color = colors.ink1, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.widthIn(max = 168.dp))
                if (file.added > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text("+${file.added}", style = num, color = colors.success)
                }
                if (file.removed > 0) {
                    Spacer(Modifier.width(4.dp))
                    Text("−${file.removed}", style = num, color = colors.danger)
                }
            }
        }
        val rest = files.size - MAX_FILE_CHIPS
        if (rest > 0) {
            Box(Modifier.height(28.dp).pressable(onMore, shape = shape).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                Text("+$rest more", style = type.meta, color = colors.ink3)
            }
        }
    }
}

// Grouped read-only steps

/** "Read 3 files, ran 1 search": a run of finished read-only steps as one row; tap lists them. */
@Composable
fun ToolGroupRowView(row: ToolGroupRow, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    val summary = remember(row.tools) { groupSummary(row.tools) }
    val detail = remember(row.tools) { groupDetail(row.tools) }
    val total = remember(row.tools) { row.tools.takeIf { tools -> tools.all { it.durationMs != null } }?.sumOf { it.durationMs ?: 0L } }
    StepHeader(onClick = onToggle, modifier = modifier, slot = {
        Icon(Icons.Rounded.Check, contentDescription = "Done", tint = colors.ink3, modifier = Modifier.size(16.dp))
    }) {
        // Summary and names share the room left by the time and chevron (measured first), so at 360dp and 1.3x the
        // chevron never gets pushed out; the summary keeps priority and ellipsises only past the whole row.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(summary, style = type.label, color = colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            val detailAlpha by animateFloatAsState(if (row.expanded) 0f else 1f, PiMotion.inPlace(PiMotion.QUICK), label = "detail")
            Text(detail, style = type.monoSmall, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).graphicsLayer { alpha = detailAlpha })
        }
        Spacer(Modifier.width(8.dp))
        stepDuration(total)?.let { Text(it, style = type.meta.copy(fontFeatureSettings = Tnum), color = colors.ink3) }
        Spacer(Modifier.width(4.dp))
        Chevron(row.expanded)
    }
}

// Step rail

/** Where a step glyph's centre sits in a step row: the row's top gap plus half the 40dp header. */
fun stepGlyphY(stepGap: Dp): Dp = stepGap + 20.dp

/**
 * The rail of an opened fold or group: a 1dp line at the slot's centre (x = 10dp) behind the rows, broken 4dp above
 * and below each step glyph, starting under the header and ending at the last step's glyph. [glyphY] is null for
 * prose rows, which the rail passes straight by.
 */
fun Modifier.stepRail(rail: Rail, color: Color, glyphY: Dp?): Modifier = drawBehind {
    val x = 10.dp.toPx()
    val stroke = 1.dp.toPx()
    fun line(from: Float, to: Float) {
        if (to > from) drawLine(color, Offset(x, from), Offset(x, to), strokeWidth = stroke)
    }
    if (glyphY == null) {
        line(0f, size.height)
    } else {
        val y = glyphY.toPx()
        val gap = 12.dp.toPx() // half the 16dp glyph plus 4dp
        line(0f, y - gap)
        if (!rail.last) line(y + gap, size.height)
    }
}

/**
 * Rows of a fold or group the user just opened fade in and rise 4dp, 40 ms apart (at most 4 steps of delay). Rows
 * shown any other way (history, scrolling back) appear at once.
 */
@Composable
fun Modifier.staggerIn(key: String, rail: Rail?, memory: SessionMemory): Modifier {
    if (rail == null || Pi.reduceMotion) return this
    val animate = remember(key) { memory.openedAt[rail.stagger]?.let { SystemClock.uptimeMillis() - it < 600 } == true }
    if (!animate) return this
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) {
        progress.animateTo(1f, tween(PiMotion.STANDARD, min(rail.index, 4) * 40, PiMotion.InPlace))
    }
    val rise = with(LocalDensity.current) { 4.dp.toPx() }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * rise
    }
}

// Reply actions

/**
 * The transcript of a plain chat (ui/chats). Its reply's second action regenerates the answer in place rather than
 * asking again as a new turn (TalkBack hears "Regenerate", as the More sheet names it), and a finished turn has no
 * "Finished 4s" line: a chat's answer follows the question as in ChatGPT; "Failed" and "Stopped" still show.
 */
val LocalPlainChat = staticCompositionLocalOf { false }

/**
 * Under the last finished reply, three quiet icons in a row (each 48dp to touch, ink2 at 18dp): Copy (a check while
 * copied), Ask again (sends the same prompt as a new turn; in a chat, Regenerate: the answer again in its place, see
 * [LocalPlainChat]) and More (the long-press sheet). Icons only, so the row reads as one calm toolbar rather
 * than three labelled buttons. Fades in once when the turn settles; history shows it as is.
 */
@Composable
fun ReplyActions(row: ActionsRow, memory: SessionMemory, onAskAgain: (String) -> Unit, onMore: () -> Unit, modifier: Modifier = Modifier) {
    val reduced = Pi.reduceMotion
    val animate = remember(row.key) { !reduced && memory.primed && memory.seen.add(row.key) && memory.settledAt.longValue > 0 && SystemClock.uptimeMillis() - memory.settledAt.longValue < 3000 }
    memory.seen.add(row.key)
    val alpha = remember(row.key) { Animatable(if (animate) 0f else 1f) }
    if (animate) LaunchedEffect(row.key) { alpha.animateTo(1f, PiMotion.enter(PiMotion.STANDARD, delay = 150)) }
    Row(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            // Each icon sits in a 48dp target; pull the row back so the copy glyph lines up with the prose.
            .offset(x = (-12).dp)
            .graphicsLayer { this.alpha = alpha.value },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CopyButton(text = { row.reply }, modifier = Modifier.width(48.dp), label = false, clipLabel = "Reply", description = "Copy reply")
        val prompt = row.prompt
        if (prompt != null) IconAction(Icons.Rounded.Replay, if (LocalPlainChat.current) "Regenerate" else "Ask again", onClick = { onAskAgain(prompt) }, size = 18.dp)
        IconAction(Icons.Rounded.MoreHoriz, "More actions", onClick = onMore, size = 18.dp)
    }
}

// Notices

@Composable
fun NoticeRowView(item: NoticeItem, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    if (item.kind == NoticeKind.Aborted && item.text.isBlank()) {
        StepHeader(onClick = null, modifier = modifier.padding(top = 4.dp), slot = {
            Icon(Icons.Rounded.StopCircle, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(16.dp))
        }) {
            Text(item.title, style = type.label, color = colors.ink3)
        }
        return
    }
    val (icon, tint, border) = when (item.kind) {
        NoticeKind.Error -> Triple(Icons.Rounded.ErrorOutline, colors.danger, colors.danger.copy(alpha = 0.32f))
        NoticeKind.Aborted -> Triple(Icons.Rounded.StopCircle, colors.warning, colors.warning.copy(alpha = 0.32f))
        NoticeKind.Compaction -> Triple(Icons.Rounded.Compress, colors.ink2, colors.lineStrong)
        else -> Triple(Icons.Rounded.Info, colors.ink2, colors.lineStrong)
    }
    val shape = RoundedCornerShape(Radius.md)
    val alwaysOpen = item.kind == NoticeKind.Error
    val open = alwaysOpen || expanded
    Column(
        modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .border(1.dp, border, shape)
            .then(if (!alwaysOpen && item.text.isNotBlank()) Modifier.pressable(onToggle, shape = shape, pressedScale = 1f) else Modifier)
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = if (open && item.text.isNotBlank()) 4.dp else 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(IconSize.md))
            Spacer(Modifier.width(8.dp))
            // The glyph and outline carry the state; the title stays ink1 (text is never in a status colour).
            Text(item.title, style = type.bodyStrong, color = colors.ink1, modifier = Modifier.weight(1f))
            if (!alwaysOpen && item.text.isNotBlank()) {
                Chevron(expanded, Modifier.padding(end = 8.dp))
            }
        }
        AnimatedVisibility(open && item.text.isNotBlank(), enter = expandVertically(PiMotion.inPlace()) + fadeIn(), exit = shrinkVertically(PiMotion.leave(PiMotion.STANDARD)) + fadeOut()) {
            Column {
                Text(
                    item.text.take(4000),
                    style = if (item.kind == NoticeKind.Error) type.monoSmall else type.body.copy(fontSize = type.body.fontSize * 0.94f),
                    color = colors.ink2,
                    modifier = Modifier.padding(start = 28.dp, top = 6.dp, end = 8.dp),
                    maxLines = if (expanded || !alwaysOpen) 400 else 8,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(Modifier.fillMaxWidth().padding(start = 16.dp), horizontalArrangement = Arrangement.Start) {
                    CopyButton(text = { item.text }, clipLabel = item.title, description = "Copy message")
                    if (alwaysOpen && item.text.length > 400) TextAction(if (expanded) "Less" else "Details", onClick = onToggle, color = colors.ink2)
                }
            }
        }
    }
}

// Live status line

/**
 * The live turn's "Working" line at the bottom of the transcript, the kit's [WorkingLine] (a 14dp Quad in the mode and
 * the shimmering label, rowMeta), then the elapsed time. A running tool is named: the verb shimmers and its command or
 * path follows in mono ("Run  npm test"); other details (a retry's attempt) follow in rowMeta. Its 32dp height stays reserved when idle so the list never jumps.
 */
@Composable
fun StatusLine(visible: Boolean, mode: QuadMode, text: StatusText, startedAt: Long, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    val alpha by animateFloatAsState(if (visible) 1f else 0f, if (visible) PiMotion.enter(PiMotion.STANDARD) else PiMotion.leave(PiMotion.QUICK), label = "status")
    Row(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .graphicsLayer { this.alpha = alpha },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (visible || alpha > 0f) {
            // Both sides of a crossfade draw the Quad from the window's one clock, so only the words visibly change.
            Crossfade(
                targetState = text,
                animationSpec = if (Pi.reduceMotion) tween(0) else PiMotion.inPlace(PiMotion.QUICK),
                modifier = Modifier.weight(1f, fill = false),
                label = "statusText",
            ) { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WorkingLine(t.label, mode = mode, color = if (mode == QuadMode.Wait) colors.warning else colors.ink2)
                    if (t.detail.isNotEmpty()) {
                        Spacer(Modifier.width(Space.sm))
                        Text(
                            t.detail,
                            // A command or path in mono; prose ("attempt 2 of 3", an agent's task) in the line's own type.
                            style = if (t.mono) type.monoSmall else type.rowMeta.copy(fontFeatureSettings = Tnum),
                            color = colors.ink3,
                            maxLines = 1,
                            overflow = if (t.path) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    if (t.more > 0) Text("  +${t.more}", style = type.rowMeta.copy(fontFeatureSettings = Tnum), color = colors.ink3, maxLines = 1)
                }
            }
            if (startedAt > 0) {
                if (text.label.isNotEmpty()) Text("  ·  ", style = type.rowMeta, color = colors.ink3)
                Elapsed(startedAt)
            }
        }
    }
}

@Composable
private fun Elapsed(startedAt: Long) {
    val now by produceState(SystemClock.uptimeMillis(), startedAt) {
        while (true) {
            value = SystemClock.uptimeMillis()
            delay(1000 - (value - startedAt) % 1000)
        }
    }
    Text(formatElapsed(now - startedAt), style = Pi.type.rowMeta.copy(fontFeatureSettings = Tnum), color = Pi.colors.ink3)
}

/**
 * How long a running step has run ("4s", "1m 3s"), from when this phone first saw it running. Hidden for the first
 * second; ticks once a second, aligned to the start, and recomposes only itself.
 */
@Composable
fun StepElapsed(since: Long, modifier: Modifier = Modifier) {
    val now by produceState(SystemClock.uptimeMillis(), since) {
        while (true) {
            value = SystemClock.uptimeMillis()
            delay(1000 - (value - since) % 1000)
        }
    }
    formatStepElapsed(now - since)?.let {
        Text(it, style = Pi.type.meta.copy(fontFeatureSettings = Tnum), color = Pi.colors.ink3, maxLines = 1, modifier = modifier)
    }
}
