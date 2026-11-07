package opensec.pibolt.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.markdown.ChipText
import opensec.pibolt.app.ui.markdown.MdBlock
import opensec.pibolt.app.ui.markdown.TableAlign
import opensec.pibolt.app.ui.markdown.TableMath
import opensec.pibolt.app.ui.markdown.inlineAnnotated
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiColors
import opensec.pibolt.app.ui.theme.Tnum

private val CELL_X = 12.dp
private val CELL_Y = 9.dp
private val MIN_COLUMN = 64.dp
private val MAX_COLUMN = 240.dp

/**
 * Natural column widths of tables measured from scratch, by content and style (most recent [MAX] tables). Values are
 * copied in and out: a composition's [WidthMemo] keeps growing its own array while its table streams.
 */
internal object TableWidthCache {
    const val MAX = 48

    private val map = object : LinkedHashMap<Any, IntArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Any, IntArray>?): Boolean = size > MAX
    }

    fun get(key: Any): IntArray? = synchronized(map) { map[key]?.copyOf() }

    fun put(key: Any, widths: IntArray) {
        synchronized(map) { map[key] = widths.copyOf() }
    }

    fun clear() {
        synchronized(map) { map.clear() }
    }
}

/** Natural column widths seen so far: while a table streams in, widths only grow, so columns do not jitter. */
private class WidthMemo {
    var key: Any? = null
    var measuredRows = 0
    var widths = IntArray(0)
}

/**
 * A markdown table as a real grid: every column takes its measured width (64…240dp), spare width is spread so the
 * table fills the card, and wider tables scroll horizontally with edge fades. Muted 12.5/500 header on surface1, body
 * Inter 14/20, 1dp row separators and no column lines; alignment markers are kept, numeric columns align to the end.
 */
@Composable
fun MdTable(table: MdBlock.Table, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    val density = LocalDensity.current
    val columns = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    if (columns == 0) return
    val headStyle = remember(type, colors) { type.metaStrong.copy(color = colors.ink2) }
    val cellStyle = remember(type, colors) { type.label.copy(fontWeight = FontWeight.Normal, color = colors.ink1) }
    val header = remember(table.header, columns, colors) { List(columns) { inlineAnnotated(table.header.getOrElse(it) { "" }, colors, colors.accent) } }
    val body = remember(table.rows, columns, colors) { table.rows.map { row -> List(columns) { inlineAnnotated(row.getOrElse(it) { "" }, colors, colors.accent) } } }
    val numeric = remember(table.rows, columns) { BooleanArray(columns) { c -> TableMath.isNumeric(table.rows.map { it.getOrElse(c) { "" } }) } }
    val aligns = List(columns) { c ->
        when (table.align.getOrElse(c) { TableAlign.None }) {
            TableAlign.Start -> TextAlign.Start
            TableAlign.Center -> TextAlign.Center
            TableAlign.End -> TextAlign.End
            TableAlign.None -> if (numeric[c]) TextAlign.End else TextAlign.Start
        }
    }

    val measurer = rememberTextMeasurer()
    val memo = remember { WidthMemo() }
    val pad = with(density) { (CELL_X * 2).roundToPx() }
    val key = listOf(columns, header, headStyle, cellStyle, density.density, density.fontScale)
    // A table measured from scratch before (scrolled back into view: a new composition) takes its widths from the
    // cache instead of measuring every cell again; the result is the same, since only from-scratch widths go in.
    var cacheKey: Any? = null
    if (memo.key != key || table.rows.size < memo.measuredRows) {
        memo.key = key
        memo.measuredRows = 0
        cacheKey = key to body
        val known = TableWidthCache.get(cacheKey)
        if (known != null) {
            memo.widths = known
            memo.measuredRows = body.size
            cacheKey = null
        } else {
            // +2px: the measured width is rounded, and a cell one pixel short wraps its last letter.
            memo.widths = IntArray(columns) { c -> measurer.measure(header[c], headStyle, softWrap = false, maxLines = 1).size.width + pad + 2 }
        }
    }
    for (r in memo.measuredRows until body.size) {
        for (c in 0 until columns) {
            val w = measurer.measure(body[r][c], cellStyle, softWrap = false, maxLines = 1).size.width + pad + 2
            if (w > memo.widths[c]) memo.widths[c] = w
        }
    }
    if (cacheKey != null) TableWidthCache.put(cacheKey, memo.widths)
    // The last row may still be streaming: measure it again next time.
    memo.measuredRows = (body.size - 1).coerceAtLeast(0)
    val natural = memo.widths

    val shape = RoundedCornerShape(RadiusMedia)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val available = constraints.maxWidth
        val widths = with(density) { TableMath.widths(natural, available, MIN_COLUMN.roundToPx(), MAX_COLUMN.roundToPx()) }
        val total = with(density) { widths.sum().toDp() }
        val cellWidths = with(density) { widths.map { it.toDp() } }
        val scrolls = widths.sum() > available
        val scroll = rememberScrollState()
        val headerHeight = remember { FloatArray(1) }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .border(1.dp, colors.line, shape),
        ) {
            Column(
                if (scrolls) {
                    Modifier
                        .horizontalEdgeFades(scroll, colors.surface1, bottom = { headerHeight[0] })
                        .horizontalEdgeFades(scroll, colors.canvas, top = { headerHeight[0] })
                        .horizontalScroll(scroll)
                } else {
                    Modifier
                },
            ) {
                Row(
                    Modifier
                        .background(colors.surface1)
                        .onSizeChanged { headerHeight[0] = it.height.toFloat() },
                ) {
                    for (c in 0 until columns) {
                        ChipText(
                            header[c],
                            headStyle.copy(textAlign = aligns[c]),
                            Modifier.width(cellWidths[c]).padding(horizontal = CELL_X, vertical = CELL_Y),
                        )
                    }
                }
                Box(Modifier.width(total).height(1.dp).background(colors.line))
                val zebra = body.size >= 8
                body.forEachIndexed { r, row ->
                    Row(if (zebra && r % 2 == 1) Modifier.background(colors.ink1.copy(alpha = 0.03f)) else Modifier) {
                        for (c in 0 until columns) {
                            ChipText(
                                row[c],
                                cellStyle(cellStyle, table.rows[r].getOrElse(c) { "" }, aligns[c], numeric[c], colors),
                                Modifier.width(cellWidths[c]).padding(horizontal = CELL_X, vertical = CELL_Y),
                            )
                        }
                    }
                    if (r < body.lastIndex) Box(Modifier.width(total).height(1.dp).background(colors.line))
                }
            }
        }
    }
}

private fun cellStyle(base: TextStyle, raw: String, align: TextAlign, numeric: Boolean, colors: PiColors): TextStyle {
    val color = when (TableMath.glyph(raw)) {
        TableMath.Glyph.Yes -> colors.success
        TableMath.Glyph.No -> colors.danger
        TableMath.Glyph.Dash -> colors.ink3
        null -> base.color
    }
    return base.copy(color = color, textAlign = align, fontFeatureSettings = if (numeric) Tnum else base.fontFeatureSettings)
}
