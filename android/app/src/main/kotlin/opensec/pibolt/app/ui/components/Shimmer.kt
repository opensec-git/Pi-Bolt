package opensec.pibolt.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.theme.Pi

/**
 * A label with a light band sweeping across it (ink2 → ink1 → ink2, 64dp band, about 180dp/s). The band position is
 * read only while drawing. Static ink2 under reduce motion or when [active] is false.
 */
@Composable
fun ShimmerText(text: String, style: TextStyle, modifier: Modifier = Modifier, active: Boolean = true) {
    val colors = Pi.colors
    if (!active || Pi.reduceMotion) {
        Text(text, style = style, color = colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
        return
    }
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "band")
    val bright = colors.ink1
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
                val band = 64.dp.toPx()
                // Travel past both ends so the band enters and leaves fully; the pause between sweeps scales with width.
                val travel = maxOf(size.width + band * 2, 324.dp.toPx())
                val x = progress.value * travel - band
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.5f to bright,
                        1f to Color.Transparent,
                        startX = x - band / 2,
                        endX = x + band / 2,
                    ),
                    blendMode = BlendMode.SrcAtop,
                )
            },
    )
}
