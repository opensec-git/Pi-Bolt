package opensec.pibolt.app.ui.session.subagent

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.session.Chevron
import opensec.pibolt.app.ui.session.StepHeader
import opensec.pibolt.app.ui.session.SubagentNoticeRow
import opensec.pibolt.app.ui.session.SubagentRow
import opensec.pibolt.app.ui.session.SubagentStackRow
import opensec.pibolt.app.ui.session.formatStepElapsed
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Tnum
import opensec.pibolt.protocol.session.Subagent
import opensec.pibolt.protocol.session.SubagentStatus

/** The avatar colour of an agent type. */
fun tintOf(type: String): Color = Color(SubagentFormat.TINTS[SubagentFormat.tintIndex(type)])

/** The agent type's initial on a tinted tile (tint at 16%), radius 30% of [size]. */
@Composable
fun AgentAvatar(type: String, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    val tint = tintOf(type)
    Box(
        modifier
            .size(size)
            .background(tint.copy(alpha = 0.16f), RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            SubagentFormat.initial(type),
            style = Pi.type.label.copy(fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.42f).sp, lineHeight = (size.value * 0.5f).sp),
            color = tint,
        )
    }
}

/**
 * A subagent's card in the transcript. Running, it keeps a fixed height: header (avatar, type, task, ticking time), the
 * activity line (Quad Agent in the agent's tint, text that crossfades at most every 450 ms) and the metrics line, with
 * a 2dp sweep along its top edge. Done, the activity line gives way to the start of the answer and the card grows to
 * it with a spring. Tapping opens the agent's transcript.
 */
@Composable
fun SubagentCard(row: SubagentRow, modifier: Modifier = Modifier) {
    val nav = LocalSubagentNav.current
    val key = row.run.key
    AgentCard(row.run, row.live, onOpen = nav?.let { n -> { n.open(key) } }, modifier = modifier)
}

@Composable
internal fun AgentCard(run: Subagent, live: Boolean, onOpen: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    val shape = RoundedCornerShape(Radius.md)
    val active = run.isActive
    val border by animateColorAsState(if (active) colors.lineStrong else colors.line, PiMotion.effects(), label = "cardLine")
    Column(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = SubagentFormat.spoken(run) }
            .then(if (onOpen != null) Modifier.pressable(onOpen, shape = shape, pressedScale = 0.98f, label = "Open agent") else Modifier.clip(shape))
            .background(colors.surface1)
            .border(1.dp, border, shape)
            .then(if (active) Modifier.sweep(tintOf(run.type)) else Modifier)
            .animateContentSize(PiMotion.settle())
            .padding(start = 12.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AgentAvatar(run.type)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(SubagentFormat.typeLabel(run.type), style = type.label, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (run.background) {
                        Spacer(Modifier.width(8.dp))
                        BackgroundTag(run.agentId)
                    }
                }
                Text(
                    run.description.ifBlank { run.prompt.lineSequence().firstOrNull().orEmpty() }.ifBlank { "Subagent" },
                    style = type.meta,
                    color = colors.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            RunTime(run)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.width(32.dp).height(18.dp), contentAlignment = Alignment.Center) { RunGlyph(run, live) }
            Spacer(Modifier.width(12.dp))
            ActivityText(
                text = if (active) SubagentFormat.activityLine(run) else answerPreview(run),
                color = when (run.status) {
                    SubagentStatus.Failed -> colors.danger
                    SubagentStatus.Done -> colors.ink2
                    else -> colors.ink3
                },
                maxLines = if (active) 1 else 3,
                animate = live,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            SubagentFormat.metrics(run),
            style = type.meta.copy(fontFeatureSettings = Tnum),
            color = colors.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 44.dp),
        )
    }
}

/** The answer's first lines as plain text (the card shows up to 3), or the status line when there is none. */
private fun answerPreview(run: Subagent): String {
    if (run.status != SubagentStatus.Done) return SubagentFormat.activityLine(run)
    return SubagentFormat.answerPreview(run.result.orEmpty()).ifBlank { "Done" }
}

/** "background · 1a2b3c4d": surface3 pill, micro ink2, the id in mono. */
@Composable
private fun BackgroundTag(agentId: String?) {
    val colors = Pi.colors
    val type = Pi.type
    Row(
        Modifier
            .background(colors.surface3, CircleShape)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("background", style = type.micro, color = colors.ink2, maxLines = 1)
        if (agentId != null) {
            Text(" · ", style = type.micro, color = colors.ink3, maxLines = 1)
            Text(SubagentFormat.shortId(agentId), style = type.micro.copy(fontFamily = type.monoMeta.fontFamily), color = colors.ink3, maxLines = 1)
        }
    }
}

/** How long it ran: ticking once a second while it runs ("42s", "1m 3s"), the final time after. */
@Composable
internal fun RunTime(run: Subagent, modifier: Modifier = Modifier) {
    val style = Pi.type.meta.copy(fontFeatureSettings = Tnum)
    if (run.isActive) {
        val start = remember(run.key, run.durationMs, run.startedAt) { RunClock.start(run) }
        val now by produceState(SystemClock.uptimeMillis(), start) {
            while (true) {
                value = SystemClock.uptimeMillis()
                delay(1000 - (value - start).mod(1000L))
            }
        }
        formatStepElapsed(now - start)?.let { Text(it, style = style, color = Pi.colors.ink3, maxLines = 1, modifier = modifier) }
    } else {
        SubagentFormat.finalDuration(run)?.let { Text(it, style = style, color = Pi.colors.ink3, maxLines = 1, modifier = modifier) }
    }
}

private enum class Glyph { Running, Queued, Done, Failed, Stopped }

private fun glyphOf(status: SubagentStatus) = when (status) {
    SubagentStatus.Queued -> Glyph.Queued
    SubagentStatus.Running -> Glyph.Running
    SubagentStatus.Done -> Glyph.Done
    SubagentStatus.Failed -> Glyph.Failed
    SubagentStatus.Stopped -> Glyph.Stopped
}

/**
 * The run's state: Quad Agent in the agent's tint while it runs, a still Quad while queued, then a check (success),
 * cross (danger) or stop glyph. The swap scales in from 0.7 over 160 ms in the live turn only.
 */
@Composable
internal fun RunGlyph(run: Subagent, live: Boolean, size: Dp = 14.dp) {
    val colors = Pi.colors
    val tint = tintOf(run.type)
    val animate = live && !Pi.reduceMotion
    AnimatedContent(
        targetState = glyphOf(run.status),
        transitionSpec = {
            if (animate) {
                (fadeIn(tween(160, easing = PiMotion.InPlace)) + scaleIn(tween(160, easing = PiMotion.InPlace), initialScale = 0.7f)) togetherWith fadeOut(tween(110))
            } else {
                fadeIn(tween(0)) togetherWith fadeOut(tween(0))
            }
        },
        contentAlignment = Alignment.Center,
        label = "agentGlyph",
    ) { g ->
        when (g) {
            Glyph.Running -> QuadSpinner(QuadMode.Agent, size = 12.dp, color = tint)
            Glyph.Queued -> QuadSpinner(QuadMode.Think, size = 12.dp, color = colors.ink3, animate = false)
            Glyph.Done -> Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.success, modifier = Modifier.size(size + 2.dp))
            Glyph.Failed -> Icon(Icons.Rounded.Close, contentDescription = null, tint = colors.danger, modifier = Modifier.size(size + 2.dp))
            Glyph.Stopped -> Icon(Icons.Rounded.StopCircle, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(size + 2.dp))
        }
    }
}

/**
 * One line of activity that crossfades (fade + 4dp rise, quick) when it changes, at most once per [THROTTLE_MS] so a
 * streaming reply does not flicker. Its height never animates here: the card's spring does that.
 */
@Composable
internal fun ActivityText(text: String, color: Color, maxLines: Int, animate: Boolean, modifier: Modifier = Modifier) {
    val shown = rememberThrottled(text, THROTTLE_MS)
    val moving = animate && !Pi.reduceMotion
    val style = Pi.type.meta
    // A line that only grows (a reply being written) updates in place; a new line crossfades.
    val line = remember { intArrayOf(0) }
    val previous = remember { arrayOf(shown) }
    if (previous[0] != shown) {
        if (!SubagentFormat.sameLine(previous[0], shown)) line[0]++
        previous[0] = shown
    }
    AnimatedContent(
        targetState = line[0] to shown,
        contentKey = { it.first },
        transitionSpec = {
            if (moving) {
                (fadeIn(PiMotion.inPlace(PiMotion.QUICK)) + slideInVertically(PiMotion.inPlace(PiMotion.QUICK)) { it / 4 }) togetherWith
                    (fadeOut(PiMotion.leave(PiMotion.QUICK)) + slideOutVertically(PiMotion.leave(PiMotion.QUICK)) { -it / 4 }) using
                    SizeTransform(clip = false) { _, _ -> snap() }
            } else {
                fadeIn(tween(0)) togetherWith fadeOut(tween(0)) using SizeTransform(clip = false) { _, _ -> snap() }
            }
        },
        contentAlignment = Alignment.TopStart,
        modifier = modifier,
        label = "activity",
    ) { (_, t) ->
        Text(t, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
    }
}

private const val THROTTLE_MS = 450L

/** [value], changing at most once per [ms]: the newest value shows once [ms] passed since the last change. */
@Composable
private fun rememberThrottled(value: String, ms: Long): String {
    var shown by remember { mutableStateOf(value) }
    var last by remember { mutableLongStateOf(0L) }
    LaunchedEffect(value) {
        if (value == shown) return@LaunchedEffect
        val wait = ms - (SystemClock.uptimeMillis() - last)
        if (wait > 0) delay(wait)
        shown = value
        last = SystemClock.uptimeMillis()
    }
    return shown
}

/** A 2dp band in [color] sweeping the top edge while the run is active (1400 ms; none under reduce motion). */
@Composable
internal fun Modifier.sweep(color: Color): Modifier {
    if (Pi.reduceMotion) return this
    val t = rememberInfiniteTransition(label = "agentSweep").animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = PiMotion.InPlace), RepeatMode.Restart), label = "x")
    return drawBehind {
        val band = size.width * 0.35f
        val x = -band + (size.width + band) * t.value
        drawRect(
            Brush.horizontalGradient(0f to Color.Transparent, 0.5f to color.copy(alpha = 0.8f), 1f to Color.Transparent, startX = x, endX = x + band),
            topLeft = Offset(0f, 0f),
            size = Size(size.width, 2.dp.toPx()),
        )
    }
}

/**
 * Parallel agents as one stack. Closed: a card with "3 agents running", the avatars, the longest run time and one
 * 48dp line per agent (glyph, type, crossfading activity, time); tapping a line opens that agent. Open: the header,
 * then each agent's full card. The switch resizes with the card spring.
 */
@Composable
fun SubagentStack(row: SubagentStackRow, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val nav = LocalSubagentNav.current
    val colors = Pi.colors
    val runs = row.runs
    val active = runs.any { it.isActive }
    val shape = RoundedCornerShape(Radius.md)
    val border by animateColorAsState(if (active) colors.lineStrong else colors.line, PiMotion.effects(), label = "stackLine")
    Column(modifier.fillMaxWidth().animateContentSize(PiMotion.settle())) {
        if (!row.expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(colors.surface1)
                    .border(1.dp, border, shape)
                    .then(if (active) Modifier.sweep(colors.ink3) else Modifier),
            ) {
                StackHeader(runs, expanded = false, onToggle = onToggle)
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
                Column(Modifier.padding(vertical = 4.dp)) {
                    for (run in runs) {
                        CompactRow(run, row.live, onOpen = nav?.let { n -> { n.open(run.key) } })
                    }
                }
            }
        } else {
            StackHeader(runs, expanded = true, onToggle = onToggle)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (run in runs) AgentCard(run, row.live, onOpen = nav?.let { n -> { n.open(run.key) } })
            }
        }
    }
}

@Composable
private fun StackHeader(runs: List<Subagent>, expanded: Boolean, onToggle: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val title = SubagentFormat.stackTitle(runs)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .pressable(onToggle, shape = RoundedCornerShape(Radius.md), pressedScale = 1f, label = if (expanded) "Show as a list" else "Show each agent")
            .semantics(mergeDescendants = true) { contentDescription = title }
            .padding(start = 12.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarStack(runs.map { it.type })
        Spacer(Modifier.width(12.dp))
        Text(title, style = type.label, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        // The longest of the runs: the one that started first.
        runs.firstOrNull { it.isActive }?.let { RunTime(it) } ?: runs.maxByOrNull { it.durationMs ?: 0 }?.let { RunTime(it) }
        Spacer(Modifier.width(6.dp))
        Chevron(expanded)
    }
}

/** Up to three 22dp avatars, 18dp apart (they overlap), each with a 2dp ring in the card colour. */
@Composable
private fun AvatarStack(types: List<String>) {
    val shown = types.take(3)
    val ring = Pi.colors.surface1
    Box(Modifier.width((26 + (shown.size - 1) * 18).dp).height(26.dp), contentAlignment = Alignment.CenterStart) {
        shown.forEachIndexed { i, t ->
            Box(
                Modifier
                    .offset(x = (i * 18).dp)
                    .size(26.dp)
                    .background(ring, RoundedCornerShape(9.dp))
                    .padding(2.dp),
            ) { AgentAvatar(t, size = 22.dp) }
        }
    }
}

@Composable
private fun CompactRow(run: Subagent, live: Boolean, onOpen: (() -> Unit)?) {
    val colors = Pi.colors
    val type = Pi.type
    Row(
        Modifier
            .fillMaxWidth()
            // Each line opens its agent, so it is a full 48dp touch target.
            .height(48.dp)
            .semantics(mergeDescendants = true) { contentDescription = SubagentFormat.spoken(run) }
            .then(if (onOpen != null) Modifier.pressable(onOpen, shape = RoundedCornerShape(Radius.sm), pressedScale = 1f, label = "Open agent") else Modifier)
            .padding(start = 12.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) { RunGlyph(run, live) }
        Spacer(Modifier.width(12.dp))
        Text(SubagentFormat.typeLabel(run.type), style = type.label, color = colors.ink2, maxLines = 1)
        Spacer(Modifier.width(8.dp))
        ActivityText(
            text = if (run.isActive) SubagentFormat.activityLine(run) else run.description.ifBlank { SubagentFormat.activityLine(run) },
            color = if (run.status == SubagentStatus.Failed) colors.danger else colors.ink3,
            maxLines = 1,
            animate = live,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        RunTime(run)
    }
}

/**
 * Background agents ended (the extension's notification): one step line each, "Explore finished" + its task + its
 * time; tapping opens the agent.
 */
@Composable
fun SubagentNoticeRowView(row: SubagentNoticeRow, modifier: Modifier = Modifier) {
    val nav = LocalSubagentNav.current
    val colors = Pi.colors
    val type = Pi.type
    Column(modifier.padding(top = 4.dp)) {
        if (row.runs.isEmpty()) {
            StepHeader(onClick = null, slot = { Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(16.dp)) }) {
                Text(if (row.item.agentIds.size > 1) "${row.item.agentIds.size} background agents finished" else "Background agent finished", style = type.label, color = colors.ink2)
            }
        }
        for (run in row.runs) {
            StepHeader(
                onClick = nav?.let { n -> { n.open(run.key) } },
                slot = { RunGlyph(run, live = false) },
            ) {
                Text(SubagentFormat.endedTitle(run), style = type.label, color = colors.ink2, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                Text(run.description, style = type.meta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                RunTime(run)
                Spacer(Modifier.width(4.dp))
                Chevron(false)
            }
        }
    }
}
