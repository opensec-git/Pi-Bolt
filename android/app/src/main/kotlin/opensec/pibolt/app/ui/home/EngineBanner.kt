package opensec.pibolt.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PhonelinkOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.engine.EngineStatus
import opensec.pibolt.app.ui.components.ButtonRow
import opensec.pibolt.app.ui.components.CopyButton
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius

/** Statuses Home explains with a banner; Idle and Ready need none. */
fun EngineStatus.needsBanner(): Boolean = this is EngineStatus.Preparing || this is EngineStatus.Failed || this is EngineStatus.Unavailable

/**
 * The engine's state on Home: Preparing (Quad + step), Failed (danger outline, Details and Retry), Unavailable (why,
 * Details). Tapping the banner opens the details sheet.
 */
@Composable
fun EngineBanner(status: EngineStatus, onRetry: () -> Unit, onDetails: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    val shape = RoundedCornerShape(Radius.md)
    val failed = status is EngineStatus.Failed
    val (fill, line) = when (status) {
        is EngineStatus.Failed -> colors.dangerSoft to colors.danger.copy(alpha = 0.4f)
        else -> colors.surface1 to colors.line
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .pressable(onDetails, shape = shape, enabled = status !is EngineStatus.Preparing, label = "Engine details")
            .background(fill, shape)
            .border(1.dp, line, shape)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (status) {
            is EngineStatus.Preparing -> QuadSpinner(QuadMode.Connect, size = 20.dp, color = colors.ink2)
            is EngineStatus.Failed -> Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = colors.danger, modifier = Modifier.size(20.dp))
            else -> Icon(Icons.Rounded.PhonelinkOff, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            val (title, detail) = when (status) {
                is EngineStatus.Preparing -> "Setting up Pi-Bolt" to status.step
                is EngineStatus.Failed -> status.message to "Projects open once it runs"
                is EngineStatus.Unavailable -> "Pi-Bolt can't run on this phone" to status.reason
                else -> "" to ""
            }
            Text(title, style = type.bodyStrong, color = colors.ink1, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, style = type.meta, color = if (failed) colors.ink2 else colors.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        when (status) {
            is EngineStatus.Failed -> if (status.canRetry) TextAction("Retry", onClick = onRetry, color = colors.accent) else TextAction("Details", onClick = onDetails, color = colors.ink1)
            is EngineStatus.Unavailable -> TextAction("Details", onClick = onDetails, color = colors.ink1)
            else -> Spacer(Modifier.width(12.dp))
        }
    }
}

/** What went wrong, the raw detail in a mono well with Copy, and Retry when it may help. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EngineDetailsSheet(status: EngineStatus, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    PiSheet(onDismiss = onDismiss, state = state) {
        val (title, message, detail) = when (status) {
            is EngineStatus.Failed -> Triple(status.message, "The engine didn't start. Computers you control still work.", status.detail)
            is EngineStatus.Unavailable -> Triple("Pi-Bolt can't run on this phone", status.reason, null)
            is EngineStatus.Preparing -> Triple("Setting up Pi-Bolt", status.step, null)
            is EngineStatus.Ready -> Triple("Pi-Bolt is running", "Engine ${status.engineVersion}", null)
            EngineStatus.Idle -> Triple("Pi-Bolt has not started", "", null)
        }
        SheetHeader(title, subtitle = message)
        if (!detail.isNullOrBlank()) {
            val shape = RoundedCornerShape(Radius.md)
            Column(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .fillMaxWidth()
                    .background(colors.surface1, shape)
                    .border(1.dp, colors.line, shape),
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Details", style = type.monoMeta, color = colors.ink3, modifier = Modifier.weight(1f))
                    CopyButton(text = { detail })
                }
                Text(
                    detail,
                    style = type.monoSmall,
                    color = colors.ink2,
                    modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        ButtonRow(Modifier.padding(horizontal = 20.dp)) {
            SecondaryButton("Close", onClick = { close(onDismiss) })
            if (status is EngineStatus.Failed && status.canRetry) PrimaryButton("Retry", onClick = { close(onRetry) })
        }
    }
}
