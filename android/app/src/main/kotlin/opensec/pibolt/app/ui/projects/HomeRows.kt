package opensec.pibolt.app.ui.projects

import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Space
import opensec.pibolt.app.ui.theme.Tnum

/**
 * Where a row sits in its group, so a list of lazy items reads as one card (Pi-Bolt Desktop's `.hd-recent`): the
 * first rounds its top corners, the last its bottom ones, and every row but the first draws the 1dp line above it.
 */
enum class GroupPos {
    Only, First, Middle, Last;

    val first: Boolean get() = this == Only || this == First
    val last: Boolean get() = this == Only || this == Last

    companion object {
        fun of(index: Int, count: Int): GroupPos = when {
            count <= 1 -> Only
            index == 0 -> First
            index == count - 1 -> Last
            else -> Middle
        }
    }
}

/** The press and clip shape of a row at [pos] in a group of [radius]. */
fun groupShape(pos: GroupPos, radius: Dp = Radius.md): RoundedCornerShape = RoundedCornerShape(
    topStart = if (pos.first) radius else 0.dp,
    topEnd = if (pos.first) radius else 0.dp,
    bottomStart = if (pos.last) radius else 0.dp,
    bottomEnd = if (pos.last) radius else 0.dp,
)

/**
 * Draws one row's part of a grouped card: the [fill] in [groupShape], the card's [line] round it, and between rows a
 * single 1dp divider (a row never draws its bottom edge unless it is the last, so two rows never double the line).
 */
fun Modifier.groupCell(pos: GroupPos, fill: Color, line: Color, radius: Dp = Radius.md): Modifier = drawBehind {
    val r = radius.toPx()
    val s = 1.dp.toPx()
    fun corner(on: Boolean, inset: Float) = if (on) CornerRadius((r - inset).coerceAtLeast(0f)) else CornerRadius.Zero
    val body = Path().apply {
        addRoundRect(RoundRect(0f, 0f, size.width, size.height, corner(pos.first, 0f), corner(pos.first, 0f), corner(pos.last, 0f), corner(pos.last, 0f)))
    }
    drawPath(body, fill)
    // The outline, half a stroke in; a middle or first row's bottom edge falls outside its bounds and is clipped.
    val bottom = if (pos.last) size.height - s / 2 else size.height + s
    val edge = Path().apply {
        addRoundRect(RoundRect(s / 2, s / 2, size.width - s / 2, bottom, corner(pos.first, s / 2), corner(pos.first, s / 2), corner(pos.last, s / 2), corner(pos.last, s / 2)))
    }
    clipRect { drawPath(edge, line, style = Stroke(s)) }
}

/**
 * A section head over a grouped list (desktop `.hd-head`): the title in metaStrong ink2 (a heading for TalkBack), an
 * optional count or summary in meta ink3 with tabular figures, and an optional [end] (a text action). Inset 20dp
 * (the 16dp gutter plus 4dp), like [opensec.pibolt.app.ui.components.SectionLabel].
 */
@Composable
fun ListHead(title: String, modifier: Modifier = Modifier, sub: String? = null, subColor: Color = Pi.colors.ink3, end: (@Composable RowScope.() -> Unit)? = null) {
    val type = Pi.type
    Row(modifier.fillMaxWidth().heightIn(min = 36.dp).padding(start = Space.lg + Space.xs, end = Space.sm), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = type.metaStrong, color = Pi.colors.ink2, maxLines = 1, modifier = Modifier.semantics { heading() })
        if (sub != null) {
            Spacer(Modifier.width(Space.sm + 2.dp))
            Text(sub, style = type.meta.copy(fontFeatureSettings = Tnum), color = subColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        Spacer(Modifier.weight(1f))
        end?.invoke(this)
    }
}

/**
 * One row of a grouped list (desktop `.hd-row` and `.sb-row`, made for touch): at least
 * [opensec.pibolt.app.ui.theme.PiDims.homeRow] (56dp) tall, 16dp in, a 20dp state slot ([lead]: a dot, the live Quad
 * or a glyph), 12dp, the [title], then [trailing] (the dimmed project, counts, the time). Text at a large font scale
 * grows the row. [interaction] (press, semantics, shared bounds) sits between the row's size and its fill.
 */
@Composable
fun GroupRow(
    pos: GroupPos,
    modifier: Modifier = Modifier,
    interaction: Modifier = Modifier,
    line: Color = Pi.colors.line,
    lead: @Composable BoxScope.() -> Unit,
    title: @Composable RowScope.() -> Unit,
) {
    val colors = Pi.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Pi.dims.homeRow)
            .then(interaction)
            .groupCell(pos, colors.surface1, line)
            .padding(horizontal = Space.lg, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center, content = lead)
        Spacer(Modifier.width(Space.md))
        title()
    }
}

/**
 * A Home section head, as the references' "Workspaces": the title in rowMeta ink3 (a heading) on the 20dp gutter, an
 * optional count after it, and an optional [end] (a chevron that folds the section). 44dp tall.
 */
@Composable
fun HomeHead(title: String, modifier: Modifier = Modifier, count: Int? = null, end: (@Composable RowScope.() -> Unit)? = null) {
    val colors = Pi.colors
    val type = Pi.type
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = Space.page, end = if (end != null) Space.page - Space.xs else Space.page),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = type.rowMeta, color = colors.ink3, maxLines = 1, modifier = Modifier.semantics { heading() })
        if (count != null) {
            Spacer(Modifier.width(Space.sm))
            Text(count.toString(), style = type.rowMeta.copy(fontFeatureSettings = Tnum), color = colors.ink3, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        end?.invoke(this)
    }
}

/** The state dot of a list row (desktop `.sb-dot`): 6dp ink4 when idle (decoration, never text), 8dp in [color] when it says something. */
@Composable
fun StateDot(color: Color?) {
    if (color == null) {
        Box(Modifier.size(6.dp).background(Pi.colors.ink4, CircleShape))
    } else {
        Box(Modifier.size(8.dp).background(color, CircleShape))
    }
}

/** A row's trailing meta (meta ink3, tabular, never truncated): counts and the short time. */
@Composable
fun RowMeta(text: String, color: Color = Pi.colors.ink3, modifier: Modifier = Modifier) {
    Text(text, style = Pi.type.meta.copy(fontFeatureSettings = Tnum), color = color, maxLines = 1, modifier = modifier.padding(start = Space.sm))
}
