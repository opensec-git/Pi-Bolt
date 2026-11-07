package opensec.pibolt.app.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameMillis
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiColors
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Space
import opensec.pibolt.app.ui.theme.Tnum
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The redesign's kit (DESIGN.md "Components", 2026-11-07): glass controls, status tiles, inset-divider rows, the pill
 * composer shell and a dot-matrix waveform. Everything takes its colours from [PiColors], so every theme gets the same
 * shapes; only the default (warm charcoal) was drawn for them.
 */

/** The press scale of the redesign's controls: soft, never bouncy. */
const val SOFT_PRESS = 0.96f

/** Glass: the fill's top colour (a touch of ink over surface2 on dark themes, the canvas on a light one). */
private fun glassTop(c: PiColors): Color = if (c.isLight) c.canvas else lerp(c.surface2, c.ink1, 0.07f)

/**
 * A glass surface in [shape]: a vertical gradient (lighter at the top) from [glassTop] to surface2, a 1dp lineStrong
 * ring and a soft inner highlight along the top edge, so a control reads as tactile without a shadow.
 */
fun Modifier.glass(shape: Shape, c: PiColors): Modifier = this
    .background(Brush.verticalGradient(listOf(glassTop(c), c.surface2)), shape)
    .border(1.dp, c.lineStrong, shape)
    .drawWithContent {
        drawContent()
        // The highlight: a 1dp line of ink at 8% just inside the top of the shape, fading out towards the sides.
        val inset = 1.dp.toPx()
        val w = size.width
        drawLine(
            Brush.horizontalGradient(0f to Color.Transparent, 0.3f to c.ink1.copy(alpha = 0.08f), 0.7f to c.ink1.copy(alpha = 0.08f), 1f to Color.Transparent),
            Offset(w * 0.15f, inset * 1.5f),
            Offset(w * 0.85f, inset * 1.5f),
            strokeWidth = inset,
        )
    }

/**
 * A glass circle with one glyph (back, more, search, +): [size] (44dp) drawn inside a 48dp target, the glyph 22dp in
 * ink1. Press: [SOFT_PRESS] and the ink overlay. Always a content description.
 */
@Composable
fun GlassIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp, tint: Color = Pi.colors.ink1, enabled: Boolean = true) {
    val colors = Pi.colors
    Box(modifier.size(maxOf(size, 48.dp)), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size)
                .pressable(onClick, shape = CircleShape, enabled = enabled, pressedScale = SOFT_PRESS, label = description)
                .glass(CircleShape, colors),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = description, tint = if (enabled) tint else tint.copy(alpha = 0.4f), modifier = Modifier.size(IconSize.lg))
        }
    }
}

/**
 * A floating glass pill ("View changes +2 −2", a summary over the composer): radius full, glass, 40dp tall (a 48dp
 * target when it can be tapped), 16dp sides, its [content] in a row (label ink1, counts in their state colours).
 */
@Composable
fun GlassPill(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, label: String? = null, content: @Composable RowScope.() -> Unit) {
    val colors = Pi.colors
    val shape = CircleShape
    Box(modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .heightIn(min = 40.dp)
                .then(if (onClick != null) Modifier.pressable(onClick, shape = shape, pressedScale = SOFT_PRESS, label = label) else Modifier)
                .glass(shape, colors)
                .padding(horizontal = Space.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            content = content,
        )
    }
}

/**
 * A status tile of a 2x2 grid (Home): radius [Radius.tile], surface1 a step above the canvas, a 1dp line, 104dp tall.
 * The [glyph] (20dp, in its own colour: a tint, a state colour, a live Quad) sits top-left; the [label] (rowMeta ink1)
 * and its [count] (ink3, tabular) bottom-left. TalkBack reads "label, count". [active] lifts the line to lineStrong.
 */
@Composable
fun StatusTile(label: String, count: Int?, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false, glyph: @Composable BoxScope.() -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val shape = RoundedCornerShape(Radius.tile)
    Column(
        modifier
            .heightIn(min = 104.dp)
            .pressable(onClick, shape = shape, pressedScale = SOFT_PRESS, label = label)
            .clearAndSetSemantics { contentDescription = if (count != null) "$label, $count" else label }
            .background(colors.surface1, shape)
            .border(1.dp, if (active) colors.lineStrong else colors.line, shape)
            .padding(start = Space.lg, end = Space.md, top = Space.lg, bottom = Space.md),
    ) {
        Box(Modifier.size(IconSize.md), contentAlignment = Alignment.Center, content = glyph)
        Spacer(Modifier.weight(1f).heightIn(min = Space.md))
        Row(verticalAlignment = Alignment.Bottom) {
            // Two lines before an ellipsis: "Waiting for you" fits a half-width tile at font scale 1.3 that way.
            Text(label, style = type.rowMeta, color = colors.ink1, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (count != null) {
                Spacer(Modifier.width(Space.sm))
                Text(count.toString(), style = type.rowMeta.copy(fontFeatureSettings = Tnum), color = colors.ink3, maxLines = 1)
            }
        }
    }
}

/**
 * The plain list row of the redesign: no card, a hairline [divider] under it inset from the text start to the gutter.
 * An optional [leading] slot (24dp: an outline glyph in ink2, a Quad), the [title] (rowTitle ink1), an optional [meta]
 * line (rowMeta ink3, "Working · calc-parser"), then the [count] (rowMeta ink3), an [unread] dot (8dp accent) and the
 * [chevron]. [current] puts the row on a soft radius-md surface2 highlight (the open session). Text at a large font
 * scale grows it; 56dp at least, 14dp vertical padding.
 */
@Composable
fun InsetRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    meta: String? = null,
    leading: (@Composable BoxScope.() -> Unit)? = null,
    count: Int? = null,
    unread: Boolean = false,
    chevron: Boolean = false,
    divider: Boolean = true,
    current: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    label: String? = null,
    titleModifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = Pi.colors
    val type = Pi.type
    val shape = RoundedCornerShape(Radius.md)
    val textStart = if (leading != null) Space.page + INSET_LEAD + Space.md else Space.page
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.sm)
            .pressable(onClick, shape = shape, pressedScale = 1f, onLongClick = onLongClick, label = label)
            .then(if (current) Modifier.background(colors.surface2, shape) else Modifier),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = Space.page - Space.sm, end = Space.page - Space.sm, top = Space.md + Space.xxs, bottom = Space.md + Space.xxs),
            verticalAlignment = if (meta != null) Alignment.Top else Alignment.CenterVertically,
        ) {
            if (leading != null) {
                Box(Modifier.size(INSET_LEAD).padding(top = if (meta != null) Space.xxs else 0.dp), contentAlignment = Alignment.Center, content = leading)
                Spacer(Modifier.width(Space.md))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = type.rowTitle, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = titleModifier)
                if (meta != null) Text(meta, style = type.rowMeta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.xxs))
            }
            Row(Modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                trailing?.invoke(this)
                if (count != null) Text(count.toString(), style = type.rowMeta.copy(fontFeatureSettings = Tnum), color = colors.ink3, modifier = Modifier.padding(start = Space.sm))
                if (unread) Box(Modifier.padding(start = Space.md).size(8.dp).background(colors.accent, CircleShape))
                if (chevron) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.ink3, modifier = Modifier.padding(start = Space.xs).size(IconSize.md))
            }
        }
        if (divider && !current) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = textStart - Space.sm, end = Space.page - Space.sm)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.line),
            )
        }
    }
}

/** The leading slot of an [InsetRow]. */
val INSET_LEAD = 24.dp

/**
 * The pill composer's shell (Home, and the session's composer once it adopts it): radius full, glass, 56dp at least,
 * [leading] (a [ComposerCircle] +) and [trailing] (mic, [ComposerPrimary]) in 48dp targets on one centre line, the
 * field [content] between them. It sits [Space.page] from the window's sides.
 */
@Composable
fun PillComposerShell(modifier: Modifier = Modifier, leading: @Composable RowScope.() -> Unit, trailing: @Composable RowScope.() -> Unit, content: @Composable RowScope.() -> Unit) {
    val colors = Pi.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .glass(RoundedCornerShape(Radius.pill), colors)
            .padding(horizontal = Space.xs, vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Row(Modifier.weight(1f).padding(horizontal = Space.xs), verticalAlignment = Alignment.CenterVertically, content = content)
        trailing()
    }
}

/**
 * A 36dp circle inside the pill (+, mic): the selected overlay (ink1 at 9 to 11%) and a 20dp ink1 glyph, in a 48dp
 * target. [active] (the mic while listening) fills it with the accent and turns the glyph onAccent; TalkBack hears
 * "Listening".
 */
@Composable
fun ComposerCircle(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, active: Boolean = false) {
    val colors = Pi.colors
    Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(36.dp)
                .pressable(onClick, shape = CircleShape, enabled = enabled, pressedScale = SOFT_PRESS, label = description)
                .semantics { if (active) stateDescription = "Listening" }
                .background(if (active) colors.accent else colors.selected, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val tint = if (active) colors.onAccent else colors.ink1
            Icon(icon, contentDescription = description, tint = if (enabled) tint else tint.copy(alpha = 0.4f), modifier = Modifier.size(IconSize.md))
        }
    }
}

/**
 * The pill's primary circle, one 36dp slot in a 48dp target:
 * - with text, Send: the accent (white in the default) with an onAccent arrow, [onSend];
 * - empty while Pi works and [onStop] is given, Stop: a surface3 disc with a 1dp lineStrong ring and a 12dp ink1
 *   square;
 * - empty otherwise, voice input: the accent with a waveform glyph, [onVoice]; without [onVoice], Send at 18%,
 *   disabled. A long press there ([onVoiceLong], also a TalkBack action "Voice mode") starts voice mode.
 * The glyph swap crossfades.
 */
@Composable
fun ComposerPrimary(
    hasText: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    onVoice: (() -> Unit)? = null,
    enabled: Boolean = true,
    running: Boolean = false,
    onStop: (() -> Unit)? = null,
    onVoiceLong: (() -> Unit)? = null,
) {
    val colors = Pi.colors
    val mode = ComposerPrimaryMode.of(hasText, running, onStop != null, onVoice != null)
    val long = onVoiceLong?.takeIf { mode == ComposerPrimaryMode.Voice && enabled }
    val active = enabled && mode != ComposerPrimaryMode.SendDisabled
    val label = when (mode) {
        ComposerPrimaryMode.Stop -> "Stop"
        ComposerPrimaryMode.Voice -> "Voice input"
        else -> "Send"
    }
    val action: () -> Unit = when (mode) {
        ComposerPrimaryMode.Stop -> onStop ?: {}
        ComposerPrimaryMode.Voice -> onVoice ?: {}
        else -> onSend
    }
    Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(36.dp)
                .pressable(action, shape = CircleShape, enabled = active, pressedScale = 0.92f, onLongClick = long, role = Role.Button, label = label)
                .then(if (long != null) Modifier.semantics { customActions = listOf(CustomAccessibilityAction("Voice mode") { long(); true }) } else Modifier)
                .then(
                    if (mode == ComposerPrimaryMode.Stop) {
                        Modifier.background(colors.surface3, CircleShape).border(1.dp, colors.lineStrong, CircleShape)
                    } else {
                        Modifier.background(if (active) colors.accent else colors.accent.copy(alpha = 0.18f), CircleShape)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(mode, label = "primary-glyph") { m ->
                when (m) {
                    ComposerPrimaryMode.Stop -> Box(Modifier.size(12.dp).background(colors.ink1, RoundedCornerShape(2.dp)).semantics { contentDescription = "Stop" })
                    ComposerPrimaryMode.Voice -> Icon(Icons.Rounded.GraphicEq, contentDescription = "Voice input", tint = colors.onAccent, modifier = Modifier.size(IconSize.md))
                    else -> Icon(Icons.Rounded.ArrowUpward, contentDescription = "Send", tint = colors.onAccent, modifier = Modifier.size(IconSize.md))
                }
            }
        }
    }
}

/** What the pill's primary circle is, from the field and the run. */
enum class ComposerPrimaryMode {
    Send, SendDisabled, Stop, Voice;

    companion object {
        fun of(hasText: Boolean, running: Boolean, canStop: Boolean, canVoice: Boolean): ComposerPrimaryMode = when {
            hasText -> Send
            running && canStop -> Stop
            canVoice -> Voice
            else -> SendDisabled
        }
    }
}

/**
 * The dot-matrix waveform of voice input: a field of [rows] x as many columns as fit (a dot every 6dp, 2.5dp dots).
 * Each column lights a band around the centre line whose height follows the audio [level] (0..1) times a slow
 * travelling wave, so it moves while someone speaks and settles to a thin lit line in silence; the rest of the dots
 * stay at a 14% floor, so the shape always reads. [color] is ink1 by default; the edges fade out. The clock runs only
 * while it is shown; reduce motion draws the line still at the current level.
 */
@Composable
fun DotWave(level: Float, modifier: Modifier = Modifier, color: Color = Pi.colors.ink1, rows: Int = 9) {
    val reduced = Pi.reduceMotion
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(reduced) {
        if (reduced) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) withFrameMillis { clock.longValue = it - start }
    }
    val lvl = level.coerceIn(0f, 1f)
    Canvas(modifier.height((rows * 6).dp).clearAndSetSemantics { }) {
        val step = 6.dp.toPx()
        val dot = 2.5.dp.toPx()
        val cols = (size.width / step).toInt().coerceAtLeast(1)
        val ox = (size.width - (cols - 1) * step) / 2f
        val oy = (size.height - (rows - 1) * step) / 2f
        val t = clock.longValue / 1000f
        val mid = (rows - 1) / 2f
        for (c in 0 until cols) {
            val x = c.toFloat() / (cols - 1).coerceAtLeast(1)
            val edge = (1f - abs(x * 2f - 1f)).coerceIn(0f, 1f).let { if (it < 0.25f) it / 0.25f else 1f }
            val amp = DotWaveMath.amplitude(x, t, lvl) * mid
            for (r in 0 until rows) {
                val lit = abs(r - mid) <= amp + 0.5f
                val a = (if (lit) 1f else 0.14f) * edge
                if (a <= 0f) continue
                drawRoundRect(color.copy(alpha = color.alpha * a), Offset(ox + c * step - dot / 2, oy + r * step - dot / 2), Size(dot, dot), CornerRadius(dot / 2))
            }
        }
    }
}

/** The waveform's shape, kept apart so it can be tested. */
object DotWaveMath {
    /**
     * How far (0..1 of the half height) column [x] (0..1 across) is lit at [t] seconds for an audio [level]: two slow
     * sines travelling across, scaled by the level, never below 0 (a lit centre line in silence).
     */
    fun amplitude(x: Float, t: Float, level: Float): Float {
        val wave = 0.6f * sin((x * 2.2f - t * 0.9f) * 2f * PI.toFloat()) + 0.4f * sin((x * 5.1f + t * 1.3f) * 2f * PI.toFloat())
        return (level.coerceIn(0f, 1f) * (0.55f + 0.45f * wave)).coerceIn(0f, 1f)
    }

    /** The lit band of a [rows]-dot column at [amplitude], in whole dots around the centre (1 at rest). */
    fun litDots(rows: Int, amplitude: Float): Int {
        val mid = (rows - 1) / 2f
        return (0 until rows).count { abs(it - mid) <= amplitude.coerceIn(0f, 1f) * mid + 0.5f }
    }

    /** Dots across [widthDp] at a 6dp pitch. */
    fun columns(widthDp: Float): Int = (widthDp / 6f).roundToInt().coerceAtLeast(1)
}

/**
 * "Working" as the session line shows it: a 14dp Quad in [mode] (ink2) and the shimmering label. The session screen's
 * live line and Home's rows use it.
 */
@Composable
fun WorkingLine(label: String, modifier: Modifier = Modifier, mode: QuadMode = QuadMode.Think, color: Color = Pi.colors.ink2) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        QuadSpinner(mode, size = IconSize.xs, color = color)
        Spacer(Modifier.width(Space.sm))
        ShimmerText(label, Pi.type.rowMeta)
    }
}
