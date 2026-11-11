package opensec.pibolt.protocol.session

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * The data side of the kit's `chart` and `table` (packages/coding-agent/docs/mobile-ui.md#charts-tables-and-places):
 * ranges, readings and the order of rows, apart from drawing so they can be tested on the JVM.
 */
object ChartData {
    /** The y range a chart is drawn in: [VChart.min] and [VChart.max] when given, else the data's, from 0 for bars and areas. */
    data class Range(val low: Double, val high: Double) {
        val span: Double get() = high - low

        /** Where [value] sits, 0 (bottom) to 1 (top), clamped. */
        fun fraction(value: Double): Double = if (span <= 0.0) 0.5 else ((value - low) / span).coerceIn(0.0, 1.0)
    }

    fun range(chart: VChart): Range {
        val values = chart.series.flatMap { it.values }.filterNotNull()
        var low = chart.min ?: values.minOrNull() ?: 0.0
        var high = chart.max ?: values.maxOrNull() ?: 1.0
        if (chart.min == null && (chart.kind == "bar" || chart.kind == "area") && low > 0.0) low = 0.0
        if (chart.max == null && (chart.kind == "bar" || chart.kind == "area") && high < 0.0) high = 0.0
        if (high < low) high = low.also { low = high }
        if (high == low) {
            // A flat series: centre it instead of drawing it on the floor.
            val pad = if (high == 0.0) 1.0 else kotlin.math.abs(high) * 0.1
            return Range(low - pad, high + pad)
        }
        return Range(low, high)
    }

    /** The points along the x axis: the longest series. */
    fun points(chart: VChart): Int = chart.series.maxOfOrNull { it.values.size } ?: 0

    /** A number as the chart shows it: up to two decimals, no trailing zeros, then the unit. */
    fun format(value: Double, unit: String?): String {
        // Two decimals at most, rounded in decimal (no Long on the way, so huge readings stay exact). Never throws.
        val text = if (!value.isFinite()) "–" else BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return when {
            unit.isNullOrEmpty() -> text
            unit.first().isLetter() -> "$text $unit"
            else -> "$text$unit"
        }
    }

    private fun kindName(kind: String): String = when (kind) {
        "bar" -> "Bar chart"
        "area" -> "Area chart"
        "sparkline" -> "Sparkline"
        else -> "Line chart"
    }

    /**
     * What TalkBack reads: the view's `summary`, else the kind, the title and per series its last, lowest and highest
     * value ("Line chart, Build time: last 41 s, low 38 s, high 52 s").
     */
    fun summary(chart: VChart): String {
        chart.summary?.takeIf { it.isNotBlank() }?.let { return it }
        val head = listOfNotNull(kindName(chart.kind), chart.title?.takeIf { it.isNotBlank() }).joinToString(", ")
        val parts = chart.series.mapNotNull { s ->
            val values = s.values.filterNotNull()
            if (values.isEmpty()) return@mapNotNull null
            val reading = "last ${format(values.last(), chart.unit)}, low ${format(values.min(), chart.unit)}, high ${format(values.max(), chart.unit)}"
            if (chart.series.size > 1 && s.name.isNotBlank()) "${s.name}: $reading" else reading
        }
        return if (parts.isEmpty()) "$head, no data" else "$head: ${parts.joinToString("; ")}"
    }
}

object TableData {
    /**
     * [table]'s rows as shown: those matching [filter] (any cell contains it, ignoring case), ordered by [column]
     * ([desc]: the other way). Without a column they keep the view's order. Empty cells sort last either way.
     */
    fun rows(table: VTable, column: String?, desc: Boolean, filter: String): List<VTableRow> {
        val query = filter.trim().lowercase()
        val matching = if (query.isEmpty()) table.rows else table.rows.filter { row ->
            table.columns.any { c -> text(row.cells[c.id], c).lowercase().contains(query) }
        }
        val col = table.columns.firstOrNull { it.id == column } ?: return matching
        val (filled, empty) = matching.partition { (it.cells[col.id] ?: VCell.Empty) != VCell.Empty }
        val comparator = Comparator<VTableRow> { a, b -> compare(a.cells[col.id], b.cells[col.id], col) }
        val sorted = filled.sortedWith(if (desc) comparator.reversed() else comparator)
        return sorted + empty
    }

    /** The next order after a tap on [column]'s header: by it ascending, or the other way when it already sorts. */
    fun nextSort(current: String?, desc: Boolean, column: String): Pair<String, Boolean> =
        if (current == column) column to !desc else column to false

    private fun compare(a: VCell?, b: VCell?, column: VColumn): Int = when (column.kind) {
        "number" -> (number(a) ?: Double.NaN).compareTo(number(b) ?: Double.NaN)
        "date" -> compareValues(instant(a), instant(b))
        else -> String.CASE_INSENSITIVE_ORDER.compare(text(a, column), text(b, column))
    }

    private fun number(cell: VCell?): Double? = when (cell) {
        is VCell.Number -> cell.value
        is VCell.Text -> cell.text.trim().toDoubleOrNull()
        else -> null
    }

    /** An ISO 8601 date or date-time as an instant (a plain date or local time in [zone]); null when it is neither. */
    fun instant(cell: VCell?, zone: ZoneId = ZoneId.systemDefault()): Instant? {
        val text = (cell as? VCell.Text)?.text?.trim() ?: return null
        return runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(text) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(text).atZone(zone).toInstant() }.getOrNull()
            ?: runCatching { LocalDate.parse(text).atStartOfDay(zone).toInstant() }.getOrNull()
    }

    /**
     * A cell as plain text: numbers exactly as given, without a needless ".0" or exponent (0.001, 123.4567, 1500); the
     * app formats dates for display itself.
     */
    fun text(cell: VCell?, column: VColumn): String = when (cell) {
        is VCell.Text -> cell.text
        is VCell.Number -> BigDecimal.valueOf(cell.value).stripTrailingZeros().toPlainString()
        else -> ""
    }
}
