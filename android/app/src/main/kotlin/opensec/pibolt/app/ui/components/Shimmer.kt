package opensec.pibolt.app.ui.components

import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.theme.Pi

/** Alpha of the band's stops from its edge to its centre: eased, so the band has no visible shoulders. */
private val BAND_RAMP = floatArrayOf(0f, 0.04f, 0.17f, 0.33f, 0.5f, 0.67f, 0.83f, 0.96f, 1f)

/** Sweep speed in dp per second: long and short labels sweep at the same pace. */
private const val SHIMMER_DP_PER_S = 200f

/** Pause between sweeps. */
private const val SHIMMER_PAUSE_MS = 100L

/**
 * A label with a light band sweeping across it (ink2 base, ink1 at the band's centre through 9 eased stops per side;
 * band max(96dp, 0.6 × label width); 200dp/s with a 100 ms pause between sweeps). The clock is read only while
 * drawing. Static ink2 under reduce motion or when [active] is false.
 */
@Composable
fun ShimmerText(text: String, style: TextStyle, modifier: Modifier = Modifier, active: Boolean = true) {
    val colors = Pi.colors
    if (!active || Pi.reduceMotion) {
        Text(text, style = style, color = colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
        return
    }
    val clock = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withInfiniteAnimationFrameMillis { it }
        while (true) withInfiniteAnimationFrameMillis { clock.longValue = it - start }
    }
    val bright = colors.ink1
    val stops = remember(bright) {
        val n = BAND_RAMP.size
        Array(n * 2 - 1) { i ->
            if (i < n) (i / (2f * (n - 1))) to bright.copy(alpha = BAND_RAMP[i])
            else (i / (2f * (n - 1))) to bright.copy(alpha = BAND_RAMP[2 * (n - 1) - i])
        }
    }
    Text(
        text,
        style = style,
        color = colors.ink2,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val band = maxOf(96.dp.toPx(), size.width * 0.6f)
                // The band enters fully from the left and leaves fully on the right, then pauses.
                val travel = size.width + band
                val sweepMs = travel / (SHIMMER_DP_PER_S * density) * 1000f
                val cycle = sweepMs.toLong() + SHIMMER_PAUSE_MS
                val t = (clock.longValue % cycle).toFloat()
                if (t > sweepMs) return@drawWithContent
                val centre = t / sweepMs * travel - band / 2
                drawRect(
                    brush = Brush.horizontalGradient(*stops, startX = centre - band / 2, endX = centre + band / 2),
                    blendMode = BlendMode.SrcAtop,
                )
            },
    )
}

