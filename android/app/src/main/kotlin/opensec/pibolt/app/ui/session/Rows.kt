package opensec.pibolt.app.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.os.SystemClock
import kotlinx.coroutines.delay
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.ShimmerText
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.markdown.MdBlock
import opensec.pibolt.app.ui.markdown.MdBlockView
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Tnum
import opensec.pibolt.protocol.session.NoticeItem
import opensec.pibolt.protocol.session.NoticeKind
import opensec.pibolt.protocol.session.ThinkingItem
import opensec.pibolt.protocol.session.UserItem

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

// User

@Composable
fun UserBubble(item: UserItem, onLongPress: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val shape = RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg, bottomStart = Radius.lg, bottomEnd = 6.dp)
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .widthIn(max = maxWidth * 0.84f)
                .pressable({}, shape = shape, pressedScale = 0.98f, onLongClick = onLongPress, role = null)
                .background(colors.surface2, shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(item.text, style = Pi.type.prose, color = colors.ink1)
            if (item.imageCount > 0) {
                Spacer(Modifier.height(4.dp))
                Text(if (item.imageCount == 1) "1 image" else "${item.imageCount} images", style = Pi.type.meta, color = colors.ink3)
            }
        }
    }
}

// Assistant text

/** One block of assistant prose: gaps depend on the block kind (headings get air above, list items sit close). */
@Composable
fun TextRowView(row: TextRow, previous: MdBlock?, onOpenCode: (String, String) -> Unit, onLongPress: () -> Unit, modifier: Modifier = Modifier) {
    val gap = when {
        row.first -> 4.dp
        row.block is MdBlock.Heading -> 20.dp
        row.block is MdBlock.ListItem && previous is MdBlock.ListItem -> 4.dp
        previous is MdBlock.Heading -> 8.dp
        else -> 12.dp
    }
    MdBlockView(
        row.block,
        streaming = row.tail,
        onOpenCode = onOpenCode,
        modifier = modifier
            .padding(top = gap)
            .pressable({}, shape = RoundedCornerShape(Radius.sm), pressedScale = 1f, onLongClick = onLongPress, role = null),
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
                if (streaming) QuadSpinner(QuadMode.Think, size = 12.dp, color = colors.ink2) else Chevron(expanded)
            },
        ) {
            if (streaming) ShimmerText(label, type.label) else Text(label, style = type.label, color = colors.ink3)
            if (streaming) {
                Spacer(Modifier.weight(1f))
                Chevron(expanded)
            }
        }
        val showBody = item.text.isNotBlank() && (expanded || streaming)
        AnimatedVisibility(
            visible = showBody,
            enter = expandVertically(PiMotion.inPlace()) + fadeIn(PiMotion.inPlace(PiMotion.QUICK)),
            exit = shrinkVertically(PiMotion.leave(PiMotion.STANDARD)) + fadeOut(PiMotion.leave(PiMotion.QUICK)),
        ) {
            val text = if (expanded) item.text.trim() else tail(item.text, 5)
            val fade = !expanded && streaming
            Row(Modifier.padding(start = 9.dp, top = 2.dp, bottom = 6.dp).height(IntrinsicSize.Min)) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(colors.lineStrong))
                Text(
                    text,
                    style = type.thinking,
                    color = colors.ink3,
                    modifier = Modifier
                        .padding(start = 22.dp)
                        .then(if (fade) Modifier.topFade(colors.canvas) else Modifier),
                )
            }
        }
    }
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
        tint = Pi.colors.ink4,
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

@Composable
fun FoldRowView(row: FoldRow, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val text = buildString {
        append(if (row.durationMs != null) "Worked for ${formatWorked(row.durationMs)}" else "Worked")
        append(" · ")
        append(if (row.steps == 1) "1 step" else "${row.steps} steps")
    }
    StepHeader(onClick = onToggle, modifier = modifier.padding(top = 6.dp), slot = { Chevron(row.expanded) }) {
        Text(text, style = Pi.type.label, color = colors.ink3, maxLines = 1)
        if (row.failed) {
            Text(" · failed", style = Pi.type.label, color = colors.danger, maxLines = 1)
        }
    }
}

// Notices

@Composable
fun NoticeRowView(item: NoticeItem, expanded: Boolean, onToggle: () -> Unit, onCopy: (String) -> Unit, modifier: Modifier = Modifier) {
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
        NoticeKind.Error -> Triple(Icons.Rounded.ErrorOutline, colors.danger, colors.danger.copy(alpha = 0.4f))
        NoticeKind.Aborted -> Triple(Icons.Rounded.StopCircle, colors.warning, colors.warning.copy(alpha = 0.4f))
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
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(item.title, style = type.bodyStrong, color = if (item.kind == NoticeKind.Error) colors.danger else colors.ink1, modifier = Modifier.weight(1f))
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
                    TextAction("Copy", onClick = { onCopy(item.text) }, color = colors.ink2)
                    if (alwaysOpen && item.text.length > 400) TextAction(if (expanded) "Less" else "Details", onClick = onToggle, color = colors.ink2)
                }
            }
        }
    }
}

// Live status line

/**
 * Quad + shimmer label + elapsed time, at the bottom of the transcript while the agent works. Its 32dp height stays
 * reserved when idle so the list never jumps.
 */
@Composable
fun StatusLine(visible: Boolean, mode: QuadMode, label: String, startedAt: Long, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val alpha by animateFloatAsState(if (visible) 1f else 0f, if (visible) PiMotion.enter(PiMotion.STANDARD) else PiMotion.leave(PiMotion.QUICK), label = "status")
    Row(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .graphicsLayer { this.alpha = alpha },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (visible || alpha > 0f) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                QuadSpinner(mode, size = 16.dp, color = if (mode == QuadMode.Wait) colors.warning else colors.ink1, animate = visible)
            }
            Spacer(Modifier.width(12.dp))
            ShimmerText(label, Pi.type.label, active = visible, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.weight(1f))
            if (startedAt > 0) Elapsed(startedAt)
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
    Text(formatElapsed(now - startedAt), style = Pi.type.meta.copy(fontFeatureSettings = Tnum), color = Pi.colors.ink3)
}
