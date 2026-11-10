package opensec.pibolt.app.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.components.CopyButton
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Tnum
import opensec.pibolt.protocol.session.NoticeItem

/** What an error notice needs from the session: names for the title, and whether it is on this phone and busy. */
data class ErrorContext(val model: String?, val provider: String?, val local: Boolean, val running: Boolean)

/**
 * A provider error as one calm card instead of raw JSON: a human title, one sentence on what to do, the provider's own
 * words with the HTTP status, then up to three actions (the first in accent) and a Details disclosure with the raw
 * text and Copy. Retries of one turn are one card ("Tried 3 times"); while Pi retries it reads "Retrying 2 of 3" and
 * offers Stop retrying. Nothing here animates on appear: errors must not bounce.
 */
@Composable
fun ErrorNoticeView(
    item: NoticeItem,
    group: ErrorGroup?,
    context: ErrorContext,
    onAction: (ErrorAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Pi.colors
    val type = Pi.type
    val g = group ?: ErrorGroup(1, listOf(item.text), null)
    val error = remember(g.raws) { g.error() }
    val retry = g.retry
    val actions = ErrorText.actions(error, context.local, context.running, retrying = retry != null)
    var details by rememberSaveable(item.key) { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radius.md)
    Column(
        modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            // A hairline in the status colour, not a filled slab (as on the desktop): the glyph and line say "error".
            .border(1.dp, colors.danger.copy(alpha = 0.32f), shape)
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
            Icon(if (retry != null) Icons.Rounded.Schedule else Icons.Rounded.ErrorOutline, contentDescription = null, tint = colors.danger, modifier = Modifier.size(IconSize.md))
            Spacer(Modifier.width(8.dp))
            Text(
                ErrorText.title(error, context.model, context.provider),
                style = type.bodyStrong,
                color = colors.ink1,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
        }
        val status = when {
            retry != null -> "Retrying ${retry.attempt} of ${retry.maxAttempts}"
            g.attempts > 1 -> "Tried ${g.attempts} times"
            else -> null
        }
        if (status != null) {
            Text(status, style = type.metaStrong.copy(fontFeatureSettings = Tnum), color = if (retry != null) colors.warning else colors.ink2, modifier = Modifier.padding(start = 28.dp, top = 2.dp))
        }
        Text(ErrorText.advice(error, context.local), style = type.body, color = colors.ink2, modifier = Modifier.padding(start = 28.dp, top = 6.dp, end = 8.dp))
        val detail = ErrorText.detail(error)
        if (detail.isNotBlank()) {
            Text(detail, style = type.meta.copy(fontFeatureSettings = Tnum), color = colors.ink3, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 28.dp, top = 6.dp, end = 8.dp))
        }
        FlowRow(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp), verticalArrangement = Arrangement.Center) {
            actions.forEachIndexed { i, action ->
                TextAction(action.label, onClick = { onAction(action) }, color = if (i == 0) colors.accent else colors.ink1)
            }
            Row(
                Modifier
                    .heightIn(min = 48.dp)
                    .pressable({ details = !details }, shape = RoundedCornerShape(Radius.sm), pressedScale = 1f, label = if (details) "Hide details" else "Show details")
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Details", style = type.label, color = colors.ink3)
                Chevron(details, Modifier.size(18.dp))
            }
        }
        AnimatedVisibility(details, enter = expandVertically(PiMotion.inPlace()) + fadeIn(), exit = shrinkVertically(PiMotion.leave(PiMotion.STANDARD)) + fadeOut()) {
            val raw = remember(g.raws) { g.raws.filter { it.isNotBlank() }.distinct().joinToString("\n\n").ifBlank { item.text } }
            val well = RoundedCornerShape(Radius.sm)
            Column(Modifier.padding(start = 16.dp, end = 8.dp, bottom = 8.dp).fillMaxWidth().background(colors.surface1, well).padding(start = 12.dp, top = 10.dp, end = 4.dp)) {
                Text(raw.take(4000), style = type.monoSmall, color = colors.ink2, modifier = Modifier.padding(end = 8.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    CopyButton(text = { raw }, clipLabel = "Error", description = "Copy error details")
                }
            }
        }
    }
}
