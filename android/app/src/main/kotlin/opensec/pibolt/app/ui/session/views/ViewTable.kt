package opensec.pibolt.app.ui.session.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.app.ui.components.PiTextField
import opensec.pibolt.app.ui.components.RowDivider
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Space
import opensec.pibolt.protocol.session.TableData
import opensec.pibolt.protocol.session.VCell
import opensec.pibolt.protocol.session.VColumn
import opensec.pibolt.protocol.session.VTable
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val ROW_HEIGHT = 44.dp

/** At most this many rows tall; then the rows scroll under the header. */
private const val VISIBLE_ROWS = 12

/** Rows measured for a column's width: enough for the widest value of a long table without measuring all of it. */
private const val MEASURED_ROWS = 200

/** The widest a column grows: free text wraps past it (two lines), the one-line kinds end in an ellipsis. */
private fun maxColumnWidth(column: VColumn): Dp = if (column.kind == "text") 240.dp else 280.dp

/** Numbers, dates and mono values stay on one line; only free text wraps. */
private fun oneLine(column: VColumn): Boolean = column.kind != "text"

/**
 * The kit's `table` (docs/mobile-ui.md#charts-tables-and-places): a header that stays while the rows scroll (at most
 * [VISIBLE_ROWS] tall), horizontal scroll when the columns are wider than the screen, sorting by a sortable header's
 * tap and an optional filter box, both local to the phone. A row with `action` sends a tap with its id.
 */
@Composable
fun ViewTable(table: VTable, actions: ViewActions, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    val identity = table.id ?: "table"
    var sort by rememberSaveable(identity) { mutableStateOf(table.sortColumn) }
    var desc by rememberSaveable(identity) { mutableStateOf(table.sortDesc) }
    var filter by rememberSaveable(identity) { mutableStateOf("") }
    val rows = remember(table, sort, desc, filter) { TableData.rows(table, sort, desc, filter) }
    val shape = RoundedCornerShape(Radius.tile)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        if (table.filter) {
            PiTextField(
                value = filter,
                onValueChange = { filter = it },
                placeholder = "Filter",
                singleLine = true,
                leading = { Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(IconSize.sm)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth().clip(shape).background(colors.surface1, shape).border(1.dp, colors.line, shape)) {
            // Columns as wide as their widest value and header at the current font scale (so a date never breaks
            // mid-value), capped, then stretched to fill the card when they are narrower than it.
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val natural = remember(table, type, density) {
                table.columns.map { column ->
                    val style = if (column.kind == "mono") type.monoSmall else type.rowMeta
                    val widest = table.rows.take(MEASURED_ROWS).maxOfOrNull { row ->
                        measurer.measure(cellText(row.cells[column.id], column), style, softWrap = false, maxLines = 1).size.width
                    } ?: 0
                    val header = measurer.measure(column.label, type.label, softWrap = false, maxLines = 1).size.width
                    with(density) {
                        val content = maxOf(widest.toDp(), header.toDp() + 4.dp + IconSize.xs)
                        (content + Space.md * 2 + 2.dp).coerceIn(64.dp, maxColumnWidth(column))
                    }
                }
            }
            val total = natural.fold(0.dp) { a, b -> a + b }
            val widths = if (total < maxWidth) natural.map { it * (maxWidth / total) } else natural
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                Row(Modifier.background(colors.surface2), verticalAlignment = Alignment.CenterVertically) {
                    table.columns.forEachIndexed { i, column ->
                        HeaderCell(column, widths[i], sorted = sort == column.id, desc = desc) {
                            val (c, d) = TableData.nextSort(sort, desc, column.id)
                            sort = c
                            desc = d
                        }
                    }
                }
                RowDivider()
                Column(Modifier.heightIn(max = ROW_HEIGHT * VISIBLE_ROWS).verticalScroll(rememberScrollState())) {
                    if (rows.isEmpty()) {
                        val text = if (filter.isNotBlank()) "No matches" else table.empty ?: "Nothing here"
                        Text(text, style = type.body, color = colors.ink3, modifier = Modifier.width(widths.fold(0.dp) { a, b -> a + b }).padding(Space.lg))
                    }
                    rows.forEachIndexed { index, row ->
                        if (index > 0) RowDivider()
                        val id = table.id
                        val tap = if (row.action != null && id != null) ({ actions.tap(id, JsonPrimitive(row.id)) }) else null
                        val spoken = table.columns.joinToString(", ") { c -> "${c.label}: ${cellText(row.cells[c.id], c).ifEmpty { "empty" }}" }
                        Row(
                            Modifier
                                .heightIn(min = ROW_HEIGHT)
                                .then(if (tap != null) Modifier.pressable(tap, shape = RoundedCornerShape(0.dp), pressedScale = 1f, label = spoken) else Modifier)
                                .semantics(mergeDescendants = true) { contentDescription = spoken },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            table.columns.forEachIndexed { i, column ->
                                val text = cellText(row.cells[column.id], column)
                                Text(
                                    text,
                                    style = if (column.kind == "mono") type.monoSmall else type.rowMeta,
                                    color = colors.ink1,
                                    maxLines = if (oneLine(column)) 1 else 2,
                                    softWrap = !oneLine(column),
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = if (column.kind == "number") TextAlign.End else TextAlign.Start,
                                    modifier = Modifier.width(widths[i]).padding(horizontal = Space.md, vertical = Space.sm),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(column: VColumn, width: Dp, sorted: Boolean, desc: Boolean, onSort: () -> Unit) {
    val colors = Pi.colors
    val label = column.label
    val action = if (sorted) "Sorted by $label, ${if (desc) "descending" else "ascending"}. Sort the other way" else "Sort by $label"
    Row(
        Modifier
            .width(width)
            .heightIn(min = ROW_HEIGHT)
            .then(if (column.sortable) Modifier.pressable(onSort, shape = RoundedCornerShape(0.dp), pressedScale = 1f, label = action) else Modifier)
            .semantics { heading() }
            .padding(horizontal = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (column.kind == "number") Arrangement.End else Arrangement.Start,
    ) {
        Text(label, style = Pi.type.label, color = if (sorted) colors.ink1 else colors.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (sorted) {
            Spacer(Modifier.width(4.dp))
            Icon(if (desc) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(IconSize.xs))
        }
    }
}

private val DATE = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val DATE_TIME = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

/** A cell as shown: dates in the phone's format (with the time when the value has one), numbers without a needless ".0". */
private fun cellText(cell: VCell?, column: VColumn): String {
    if (column.kind == "date" && cell is VCell.Text) {
        val instant = TableData.instant(cell) ?: return cell.text
        val local = instant.atZone(ZoneId.systemDefault())
        return if (cell.text.trim().length <= 10) local.toLocalDate().format(DATE) else local.format(DATE_TIME)
    }
    return TableData.text(cell, column)
}
