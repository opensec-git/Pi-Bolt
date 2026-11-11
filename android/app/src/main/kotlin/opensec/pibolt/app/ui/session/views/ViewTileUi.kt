package opensec.pibolt.app.ui.session.views

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.app.ui.components.WorkingLine
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Space
import opensec.pibolt.protocol.session.VChart
import opensec.pibolt.protocol.session.VChipNode
import opensec.pibolt.protocol.session.VKv
import opensec.pibolt.protocol.session.VNode
import opensec.pibolt.protocol.session.VProgress
import opensec.pibolt.protocol.session.VRow
import opensec.pibolt.protocol.session.VText
import opensec.pibolt.protocol.session.VView

/** A tile has no controls: nothing in it sends an event, the whole tile opens its page. */
private object NoActions : ViewActions {
    override fun tap(nodeId: String, value: JsonPrimitive?) = Unit
    override fun change(nodeId: String, value: JsonPrimitive) = Unit
    override fun submit(formId: String, values: JsonObject) = Unit
}

/**
 * What a tile shows of a view's body (docs/mobile-ui.md#package-pages-and-home-tiles): text, chips, rows, progress,
 * up to 4 key-values and charts (as a sparkline of the first series); anything else is left out.
 */
fun tileBody(nodes: List<VNode>): List<VNode> = nodes.mapNotNull { node ->
    when {
        node.hidden -> null
        node is VKv -> node.copy(items = node.items.take(4))
        node is VChart -> node.copy(kind = "sparkline", series = node.series.take(1))
        node is VText || node is VChipNode || node is VRow || node is VProgress -> node
        else -> null
    }
}

/**
 * A package's live tile on Home: its title, then its [view]'s compact body; [onOpen] (the registered page) makes the
 * whole tile one tap target. While the view has not arrived, a working line.
 */
@Composable
fun ViewHomeTile(title: String, icon: String?, view: VView?, onOpen: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    val shape = RoundedCornerShape(Radius.tile)
    val body = remember(view) { view?.let { tileBody(it.body) }.orEmpty() }
    val fields = remember { ViewFields() }
    Column(
        modifier
            .heightIn(min = 104.dp)
            .then(if (onOpen != null) Modifier.pressable(onOpen, shape = shape, pressedScale = 0.97f, label = "Open ${view?.title ?: title}") else Modifier)
            .semantics(mergeDescendants = true) {}
            .background(colors.surface1, shape)
            .border(1.dp, colors.line, shape)
            .padding(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(iconFor(view?.icon ?: icon) ?: Icons.Rounded.Extension, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(IconSize.md))
            Spacer(Modifier.width(Space.sm))
            Text(view?.title ?: title, style = type.rowTitle, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        if (view == null) {
            WorkingLine("Loading", mode = QuadMode.Run)
        } else {
            ViewNodes(body, fields, NoActions, Modifier.fillMaxWidth())
        }
    }
}
