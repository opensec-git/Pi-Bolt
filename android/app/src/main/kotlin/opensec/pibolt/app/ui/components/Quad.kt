package opensec.pibolt.app.ui.components

import androidx.compose.animation.AnimatedContent
import opensec.pibolt.app.R
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Image
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import opensec.pibolt.app.ui.theme.LocalQuadClock
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import kotlin.math.roundToInt

/**
 * The Quad spinner: a 3x3 grid that shows what the agent is doing. Ticks come from the window's shared clock and are
 * read only while drawing, in the spinner's own layer. [animate] false (or reduce motion) draws the mode's still frame.
 */
@Composable
fun QuadSpinner(
    mode: QuadMode,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    color: Color = Pi.colors.ink1,
    animate: Boolean = true,
) {
    val live = animate && !Pi.reduceMotion
    val clock = LocalQuadClock.current
    val ticket = remember { intArrayOf(-1) }
    DisposableEffect(live, clock) {
        if (live) ticket[0] = clock.attach()
        onDispose {
            if (ticket[0] >= 0) clock.detach(ticket[0])
            ticket[0] = -1
        }
    }
    val snap = size <= 16.dp
    Spacer(
        modifier
            .size(size)
            .semantics { contentDescription = mode.label }
            // Its own layer while it animates: a tick re-records these nine cells, not the card, sheet or bar around it.
            .then(if (live) Modifier.graphicsLayer() else Modifier)
            .drawBehind {
                val t = ticket[0]
                if (live && t >= 0 && clock.canAnimate(t)) {
                    val now = clock.time.longValue
                    drawQuad(color, snap) { i -> QuadMath.cell(mode, i, now) }
                } else {
                    drawQuad(color, snap) { i -> QuadMath.rest(mode, i) }
                }
            },
    )
}

/**
 * The Pi-Bolt logo (docs/images/logo.svg as res/drawable/ic_pibolt_logo), fitted into a [size] square. When [play] is
 * true it enters once per launch for each place it appears: fade in while scaling 0.82 to 1 and settling from 6dp
 * below (sheet duration, enter easing). Reduce motion shows it at rest.
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 44.dp, play: Boolean = true, playKey: String = "mark") {
    val reduced = Pi.reduceMotion
    // The entrance plays once per launch for each place the mark appears, not on every recomposition or revisit.
    val animate = remember { play && !reduced && MarkEntrance.claim(playKey) }
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        delay(80)
        progress.animateTo(1f, PiMotion.enter(PiMotion.SHEET))
    }
    Image(
        painter = painterResource(R.drawable.ic_pibolt_logo),
        contentDescription = "Pi-Bolt",
        contentScale = ContentScale.Fit,
        modifier = modifier
            .size(size)
            .graphicsLayer {
                val p = progress.value
                alpha = p
                val scale = 0.82f + 0.18f * p
                scaleX = scale
                scaleY = scale
                translationY = (1f - p) * 6.dp.toPx()
            },
    )
}

/** Which brand-mark entrances already played in this process. */
private object MarkEntrance {
    private val played = HashSet<String>()

    fun claim(key: String): Boolean = synchronized(played) { played.add(key) }
}

private inline fun DrawScope.drawQuad(color: Color, snap: Boolean, cell: (Int) -> QuadCell) {
    val u = size.minDimension / 11f
    val ox = (size.width - 11f * u) / 2f
    val oy = (size.height - 11f * u) / 2f
    for (i in 0 until 9) {
        val s = cell(i)
        if (s.alpha <= 0f) continue
        val r = i / 3
        val c = i % 3
        val half = 1.5f * u * s.scale
        val cx = ox + c * 4f * u + 1.5f * u + s.dx * u
        val cy = oy + r * 4f * u + 1.5f * u + s.dy * u
        var left = cx - half
        var top = cy - half
        var side = 2f * half
        if (snap && s.scale == 1f) {
            left = left.roundToInt().toFloat()
            top = top.roundToInt().toFloat()
            side = side.roundToInt().toFloat().coerceAtLeast(1f)
        }
        drawRoundRect(
            color = color,
            alpha = (s.alpha * color.alpha).coerceIn(0f, 1f),
            topLeft = Offset(left, top),
            size = Size(side, side),
            cornerRadius = CornerRadius(0.7f * u * s.scale),
        )
    }
}

enum class StepState { Active, Done, Failed, Skipped }

/**
 * The 20dp leading slot of a timeline row: a spinner while the step runs, then its result glyph. The swap animates
 * (160 ms, scale 0.7 → 1) only in the live turn.
 */
@Composable
fun StepSlot(state: StepState, mode: QuadMode, live: Boolean, modifier: Modifier = Modifier, size: Dp = IconSize.md, spinnerSize: Dp = IconSize.xs) {
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
        modifier = modifier.size(size),
        label = "step",
    ) { s ->
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            when (s) {
                StepState.Active -> QuadSpinner(mode, size = spinnerSize, color = colors.ink2)
                StepState.Done -> Icon(Icons.Rounded.Check, contentDescription = "Done", tint = colors.ink3, modifier = Modifier.size(16.dp))
                StepState.Failed -> Icon(Icons.Rounded.Close, contentDescription = "Failed", tint = colors.danger, modifier = Modifier.size(16.dp))
                StepState.Skipped -> Icon(Icons.Rounded.Remove, contentDescription = "Not run", tint = colors.ink4, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Debounce for list rows: a new mode shows only once it held for [ms]. */
@Composable
fun rememberSettledMode(mode: QuadMode, ms: Long = 600): QuadMode {
    var shown by remember { mutableStateOf(mode) }
    LaunchedEffect(mode) {
        if (mode != shown) {
            delay(ms)
            shown = mode
        }
    }
    return shown
}
