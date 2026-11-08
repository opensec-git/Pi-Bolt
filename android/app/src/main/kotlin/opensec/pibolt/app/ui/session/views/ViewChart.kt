package opensec.pibolt.app.ui.session.views

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Space
import opensec.pibolt.protocol.session.ChartData
import opensec.pibolt.protocol.session.VChart

/**
 * The kit's `chart` (docs/mobile-ui.md#charts-tables-and-places), drawn on a Canvas in the theme's chart colours
 * ([opensec.pibolt.app.ui.theme.PiColors.charts]), one per series in order (authors never pick colours). Series differ by
 * line style and the legend too, never by colour alone. TalkBack reads the chart as one summary ([ChartData.summary]).
 */
@Composable
fun chartColors(): List<Color> = Pi.colors.charts

/** Series n's dash: solid, dashed, dotted, dash-dot. */
private fun dash(index: Int, unit: Float): PathEffect? = when (index % 4) {
    1 -> PathEffect.dashPathEffect(floatArrayOf(unit * 3, unit * 2))
    2 -> PathEffect.dashPathEffect(floatArrayOf(unit * 0.5f, unit * 1.5f))
    3 -> PathEffect.dashPathEffect(floatArrayOf(unit * 3, unit * 1.5f, unit * 0.5f, unit * 1.5f))
    else -> null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ViewChart(chart: VChart, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    val palette = chartColors()
    val summary = remember(chart) { ChartData.summary(chart) }
    val range = remember(chart) { ChartData.range(chart) }
    if (chart.kind == "sparkline") {
        Canvas(modifier.fillMaxWidth().height(32.dp).clearAndSetSemantics { contentDescription = summary }) {
            drawSeries(chart, range, palette, line = colors.line)
        }
        return
    }
    Column(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = summary }, verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        chart.title?.let { Text(it, style = type.rowTitle, color = colors.ink1, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        Row(Modifier.fillMaxWidth().height(160.dp)) {
            Column(Modifier.fillMaxHeight().padding(end = Space.sm), verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.End) {
                Text(ChartData.format(range.high, chart.unit), style = type.meta, color = colors.ink3, maxLines = 1)
                Text(ChartData.format(range.low, chart.unit), style = type.meta, color = colors.ink3, maxLines = 1)
            }
            Canvas(Modifier.weight(1f).fillMaxHeight()) { drawSeries(chart, range, palette, line = colors.line) }
        }
        if (chart.labels.isNotEmpty()) {
            Row(Modifier.fillMaxWidth()) {
                Text(chart.labels.first(), style = type.meta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (chart.labels.size > 1) Text(chart.labels.last(), style = type.meta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (chart.series.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.md), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                chart.series.forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val color = palette[i % palette.size]
                        Canvas(Modifier.size(width = 18.dp, height = 10.dp)) {
                            val unit = 2.dp.toPx()
                            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = unit, cap = StrokeCap.Round, pathEffect = dash(i, unit))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(s.name, style = type.meta, color = colors.ink2, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** The plot: a baseline hairline, then each series as bars, an area or a line, with gaps where a value is null. */
private fun DrawScope.drawSeries(chart: VChart, range: ChartData.Range, palette: List<Color>, line: Color) {
    val n = ChartData.points(chart)
    if (n == 0) return
    val stroke = 2.dp.toPx()
    val inset = stroke
    val h = size.height - inset * 2
    fun y(value: Double): Float = inset + (h * (1.0 - range.fraction(value))).toFloat()
    val base = y(0.0.coerceIn(range.low, range.high))
    if (chart.kind != "sparkline") {
        drawLine(line, Offset(0f, base), Offset(size.width, base), strokeWidth = 1.dp.toPx())
    }
    if (chart.kind == "bar") {
        val slot = size.width / n
        val groups = chart.series.size
        val gap = slot * 0.25f
        val barW = ((slot - gap) / groups).coerceAtLeast(1f)
        chart.series.forEachIndexed { si, s ->
            val color = palette[si % palette.size]
            s.values.forEachIndexed { i, v ->
                v ?: return@forEachIndexed
                val top = y(v)
                val x = i * slot + gap / 2 + si * barW
                val y0 = minOf(top, base)
                drawRect(color, Offset(x, y0), Size(barW * 0.9f, kotlin.math.abs(base - top).coerceAtLeast(1f)))
            }
        }
        return
    }
    fun x(i: Int): Float = if (n <= 1) size.width / 2 else i * size.width / (n - 1)
    chart.series.forEachIndexed { si, s ->
        val color = palette[si % palette.size]
        // Runs of values between gaps.
        val runs = ArrayList<List<Pair<Int, Double>>>()
        var run = ArrayList<Pair<Int, Double>>()
        s.values.forEachIndexed { i, v ->
            if (v == null) {
                if (run.isNotEmpty()) runs += run
                run = ArrayList()
            } else {
                run += i to v
            }
        }
        if (run.isNotEmpty()) runs += run
        for (points in runs) {
            if (points.size == 1) {
                val (i, v) = points[0]
                drawCircle(color, radius = stroke * 1.5f, center = Offset(x(i), y(v)))
                continue
            }
            val path = Path()
            points.forEachIndexed { k, (i, v) -> if (k == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
            if (chart.kind == "area") {
                val fill = Path()
                fill.addPath(path)
                fill.lineTo(x(points.last().first), base)
                fill.lineTo(x(points.first().first), base)
                fill.close()
                drawPath(fill, color.copy(alpha = 0.18f))
            }
            drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = dash(si, stroke)))
        }
    }
}
