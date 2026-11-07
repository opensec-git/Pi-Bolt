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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiColors
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Tnum
import opensec.pibolt.protocol.session.Subagent
import opensec.pibolt.protocol.session.SubagentStatus
import opensec.pibolt.app.ui.session.subagent.SubagentFormat.AgentState

/** The Quad colour of the run in colour [slot] ([SubagentFormat.hueSlot]). */
/** The run in colour [slot]: the theme's subagent tints (Pi-Bolt Desktop's --tint-0..4), wrapped. */
fun agentHue(slot: Int, colors: PiColors): Color = colors.tints[Math.floorMod(slot, colors.tints.size)]

/**
 * The state glyph's sizes in each place, on the icon tokens: [slot] it sits in, the running [quad] and the rest [glyph]
 * (a Material glyph draws inside padding, so it is one step larger than the grid to read the same size).
 */
internal enum class AgentGlyphSize(val slot: Dp, val quad: Dp, val glyph: Dp) {
    /** An agent's own two-line block: a card, a line of a stack. */
    Card(IconSize.lg, IconSize.md, IconSize.lg),

    /** A one-line timeline row: an ended background run, a stack's header. */
    Row(IconSize.md, IconSize.xs, IconSize.sm),

    /** A top bar and a live line. */
    Bar(IconSize.md, IconSize.sm, IconSize.md),
}

/**
 * A subagent's card in the transcript: its state glyph (a Quad in the agent's own colour while it runs; check, cross
 * or dash after), its name (the task title) over the type and the tool it runs now ("Explore · Reading src/auth.ts"),
 * and the run time. No narration: what the agent writes shows on its own screen, which a tap opens. The card keeps
 * its height from start to end; a 2dp band in the agent's colour sweeps its top edge while it runs.
 */
@Composable
fun SubagentCard(row: SubagentRow, modifier: Modifier = Modifier) {
    val nav = LocalSubagentNav.current
    val key = row.run.key
    AgentCard(row.run, agentHue(row.slot, Pi.colors), row.live, onOpen = nav?.let { n -> { n.open(key) } }, modifier = modifier)
}

@Composable
internal fun AgentCard(run: Subagent, hue: Color, live: Boolean, onOpen: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val shape = RoundedCornerShape(Radius.tile)
    val active = run.isActive
    val border by animateColorAsState(if (active) colors.lineStrong else colors.line, PiMotion.effects(), label = "cardLine")
    AgentLines(
        run,
        hue,
        live,
        modifier
            .fillMaxWidth()
            .then(if (onOpen != null) Modifier.pressable(onOpen, shape = shape, pressedScale = 0.98f, label = "Open agent") else Modifier.clip(shape))
            .background(colors.surface1)
            .border(1.dp, border, shape)
            .then(if (active) Modifier.sweep(hue) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

/**
 * One agent in two lines: glyph, name (rowMeta ink1) over "type · tool" (meta ink3; a failure in danger), run time at the
 * end. TalkBack reads it as one node.
 */
@Composable
private fun AgentLines(run: Subagent, hue: Color, live: Boolean, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = SubagentFormat.spoken(run) }, verticalAlignment = Alignment.CenterVertically) {
        AgentGlyph(SubagentFormat.stateOf(run), hue, live, AgentGlyphSize.Card)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(SubagentFormat.title(run), style = type.rowMeta, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ActivityText(
                text = SubagentFormat.cardMeta(run),
                color = if (run.status == SubagentStatus.Failed) colors.danger else colors.ink3,
                animate = live,
            )
        }
        Spacer(Modifier.width(8.dp))
        RunTime(run)
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

/**
 * A run's state at [size], as on the desktop: while it starts, a Quad breathing in amber; running, Quad Agent in the
 * agent's own [hue] (a still frame when [spin] is false, or under reduce motion); queued for a slot, a still grey Quad;
 * then a grey check, a cross in danger or a grey dash. Colour is for the running agent and for failure only. The swap
 * scales in from 0.7 (glyph duration) in the live turn only.
 */
@Composable
internal fun AgentGlyph(state: AgentState, hue: Color, live: Boolean, size: AgentGlyphSize, spin: Boolean = true) {
    val colors = Pi.colors
    val animate = live && !Pi.reduceMotion
    AnimatedContent(
        targetState = state,
        transitionSpec = {
            if (animate) {
                (fadeIn(tween(PiMotion.GLYPH, easing = PiMotion.InPlace)) + scaleIn(tween(PiMotion.GLYPH, easing = PiMotion.InPlace), initialScale = 0.7f)) togetherWith
                    fadeOut(tween(PiMotion.exit(PiMotion.GLYPH)))
            } else {
                fadeIn(tween(0)) togetherWith fadeOut(tween(0))
            }
        },
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size.slot),
        label = "agentGlyph",
    ) { g ->
        Box(Modifier.size(size.slot), contentAlignment = Alignment.Center) {
            when (g) {
                AgentState.Starting -> QuadSpinner(QuadMode.Wait, size = size.quad, color = colors.warning, animate = spin)
                AgentState.Running -> QuadSpinner(QuadMode.Agent, size = size.quad, color = hue, animate = spin)
                AgentState.Queued -> QuadSpinner(QuadMode.Think, size = size.quad, color = colors.ink3, animate = false)
                AgentState.Done -> Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(size.glyph))
                AgentState.Failed -> Icon(Icons.Rounded.Close, contentDescription = null, tint = colors.danger, modifier = Modifier.size(size.glyph))
                AgentState.Stopped -> Icon(Icons.Rounded.Remove, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(size.glyph))
            }
        }
    }
}

/**
 * One line of activity (one line, ellipsised) that crossfades (fade + 4dp rise, quick) when it changes, at most once per [THROTTLE_MS] so a
 * streaming reply does not flicker. Its height never animates here: the card's spring does that.
 */
@Composable
internal fun ActivityText(text: String, color: Color, animate: Boolean, modifier: Modifier = Modifier) {
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
        Text(t, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
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

/**
 * A 2dp band in [color] sweeping the top edge while the run is active (sweep duration; none under reduce motion). The
 * card's content goes in a layer of its own (the trailing graphicsLayer), so each frame of the sweep re-records only the
 * band, not the card's text and glyphs.
 */
@Composable
internal fun Modifier.sweep(color: Color): Modifier {
    if (Pi.reduceMotion) return this
    val t = rememberInfiniteTransition(label = "agentSweep").animateFloat(0f, 1f, infiniteRepeatable(tween(PiMotion.SWEEP, easing = PiMotion.InPlace), RepeatMode.Restart), label = "x")
    return drawBehind {
        val band = size.width * 0.35f
        val x = -band + (size.width + band) * t.value
        drawRect(
            Brush.horizontalGradient(0f to Color.Transparent, 0.5f to color.copy(alpha = 0.8f), 1f to Color.Transparent, startX = x, endX = x + band),
            topLeft = Offset(0f, 0f),
            size = Size(size.width, 2.dp.toPx()),
        )
    }.graphicsLayer()
}

/**
 * Parallel agents as one stack. Closed: a card with "3 agents running", the group's state, the longest run time and
 * each agent in two lines (its glyph in its own colour, name, type and tool, time); tapping a line opens that agent.
 * Open: the header, then each agent's card. The switch resizes with the card spring.
 */
@Composable
fun SubagentStack(row: SubagentStackRow, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val nav = LocalSubagentNav.current
    val colors = Pi.colors
    val runs = row.runs
    val active = runs.any { it.isActive }
    val shape = RoundedCornerShape(Radius.tile)
    val border by animateColorAsState(if (active) colors.lineStrong else colors.line, PiMotion.effects(), label = "stackLine")
    fun hue(i: Int) = agentHue(row.slots.getOrElse(i) { i }, colors)
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
                StackHeader(runs, row.live, expanded = false, onToggle = onToggle)
                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
                Column(Modifier.padding(vertical = 4.dp)) {
                    runs.forEachIndexed { i, run ->
                        val open = nav?.let { n -> { n.open(run.key) } }
                        AgentLines(
                            run,
                            hue(i),
                            row.live,
                            Modifier
                                .fillMaxWidth()
                                // Each line opens its agent, so it is at least a 56dp target.
                                .heightIn(min = 56.dp)
                                .then(if (open != null) Modifier.pressable(open, shape = RoundedCornerShape(Radius.sm), pressedScale = 1f, label = "Open agent") else Modifier)
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        } else {
            StackHeader(runs, row.live, expanded = true, onToggle = onToggle)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                runs.forEachIndexed { i, run -> AgentCard(run, hue(i), row.live, onOpen = nav?.let { n -> { n.open(run.key) } }) }
            }
        }
    }
}

/** The stack's 48dp header: the group's state (still; each agent's line carries the motion), title, time, chevron. */
@Composable
private fun StackHeader(runs: List<Subagent>, live: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val title = SubagentFormat.stackTitle(runs)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .pressable(onToggle, shape = RoundedCornerShape(Radius.tile), pressedScale = 1f, label = if (expanded) "Show as a list" else "Show each agent")
            .semantics(mergeDescendants = true) { contentDescription = title }
            .padding(start = 16.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // In a Card-size slot so its centre lines up with the agents' glyphs below.
        Box(Modifier.size(AgentGlyphSize.Card.slot), contentAlignment = Alignment.Center) {
            AgentGlyph(SubagentFormat.stackState(runs), colors.ink2, live, AgentGlyphSize.Row, spin = false)
        }
        Spacer(Modifier.width(12.dp))
        Text(title, style = type.rowMeta, color = colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        // The longest of the runs: the one that started first.
        runs.firstOrNull { it.isActive }?.let { RunTime(it) } ?: runs.maxByOrNull { it.durationMs ?: 0 }?.let { RunTime(it) }
        Spacer(Modifier.width(6.dp))
        Chevron(expanded)
    }
}

/**
 * Background agents ended (the extension's notification): one step line each, the agent's name, "Explore finished"
 * and its time; tapping opens the agent.
 */
@Composable
fun SubagentNoticeRowView(row: SubagentNoticeRow, modifier: Modifier = Modifier) {
    val nav = LocalSubagentNav.current
    val colors = Pi.colors
    val type = Pi.type
    Column(modifier.padding(top = 4.dp)) {
        if (row.runs.isEmpty()) {
            StepHeader(onClick = null, slot = { Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(IconSize.sm)) }) {
                Text(if (row.item.agentIds.size > 1) "${row.item.agentIds.size} background agents finished" else "Background agent finished", style = type.label, color = colors.ink2)
            }
        }
        row.runs.forEachIndexed { i, run ->
            StepHeader(
                onClick = nav?.let { n -> { n.open(run.key) } },
                slot = { AgentGlyph(SubagentFormat.stateOf(run), agentHue(row.slots.getOrElse(i) { i }, colors), live = false, size = AgentGlyphSize.Row) },
            ) {
                Text(SubagentFormat.title(run), style = type.label, color = colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                Text(SubagentFormat.endedTitle(run), style = type.meta, color = colors.ink3, maxLines = 1, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                RunTime(run)
                Spacer(Modifier.width(4.dp))
                Chevron(false)
            }
        }
    }
}
