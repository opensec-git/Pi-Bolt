package opensec.pibolt.app.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Fades the edge of a horizontally scrolling area into [color] (the surface underneath) where more content is hidden.
 * Place it before `horizontalScroll` so it draws in the viewport. [top] and [bottom] limit the fade to a band (in px
 * from the top, read at draw time) so one area can fade into two surfaces. Brushes are built once per size; the scroll
 * position is read only while drawing.
 */
fun Modifier.horizontalEdgeFades(
    scroll: ScrollState,
    color: Color,
    width: Dp = 20.dp,
    top: () -> Float = { 0f },
    bottom: () -> Float = { Float.POSITIVE_INFINITY },
): Modifier = drawWithCache {
    val w = width.toPx().coerceAtMost(size.width / 3f)
    val start = Brush.horizontalGradient(listOf(color, color.copy(alpha = 0f)), startX = 0f, endX = w)
    val end = Brush.horizontalGradient(listOf(color.copy(alpha = 0f), color), startX = size.width - w, endX = size.width)
    onDrawWithContent {
        drawContent()
        val y0 = top().coerceIn(0f, size.height)
        val y1 = bottom().coerceIn(y0, size.height)
        if (y1 <= y0) return@onDrawWithContent
        if (scroll.canScrollBackward) drawRect(start, Offset(0f, y0), Size(w, y1 - y0))
        if (scroll.canScrollForward) drawRect(end, Offset(size.width - w, y0), Size(w, y1 - y0))
    }
}

/** A [height] fade into [color] at the top or bottom edge, over the content (folded and streaming code). */
fun Modifier.verticalEdgeFade(color: Color, height: Dp, atTop: Boolean, enabled: Boolean = true): Modifier =
    if (!enabled) {
        this
    } else {
        drawWithCache {
            val h = height.toPx().coerceAtMost(size.height / 2f)
            val brush = if (atTop) {
                Brush.verticalGradient(listOf(color, color.copy(alpha = 0f)), startY = 0f, endY = h)
            } else {
                Brush.verticalGradient(listOf(color.copy(alpha = 0f), color), startY = size.height - h, endY = size.height)
            }
            val y = if (atTop) 0f else size.height - h
            onDrawWithContent {
                drawContent()
                drawRect(brush, Offset(0f, y), Size(size.width, h))
            }
        }
    }
