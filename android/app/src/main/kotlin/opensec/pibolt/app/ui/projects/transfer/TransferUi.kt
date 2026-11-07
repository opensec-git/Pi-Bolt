package opensec.pibolt.app.ui.projects.transfer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.ui.components.glass
import opensec.pibolt.app.ui.projects.ProjectFiles
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Space
import kotlin.math.roundToInt

/** One running transfer: what it does ("Importing 3 files") and how far it is. */
class ActiveTransfer(val label: String, val progress: TransferProgress)

/**
 * Runs one transfer at a time in [scope] and exposes it for [TransferBar]. Progress reaches the UI at most every
 * [MIN_UPDATE_MS] (a buffer is 64 KB, so raw updates would recompose hundreds of times a second). Cancel stops the
 * job; the transfer's own cleanup removes partial files.
 */
@Stable
class TransferRunner(private val scope: CoroutineScope) {
    var active by mutableStateOf<ActiveTransfer?>(null)
        private set

    private var job: Job? = null

    val busy: Boolean get() = active != null

    fun <T> run(label: String, work: suspend (onProgress: (TransferProgress) -> Unit) -> T, onDone: (T) -> Unit, onError: (String) -> Unit) {
        if (active != null) return
        var last = 0L
        active = ActiveTransfer(label, TransferProgress(0, null, 0))
        job = scope.launch {
            try {
                val result = work { p ->
                    val now = System.currentTimeMillis()
                    if (now - last >= MIN_UPDATE_MS) {
                        last = now
                        // Written from the transfer's thread; snapshot state takes it and the bar reads it next frame.
                        if (active != null) active = ActiveTransfer(label, p)
                    }
                }
                active = null
                onDone(result)
            } catch (e: CancellationException) {
                active = null
                throw e
            } catch (e: Exception) {
                active = null
                onError(e.message ?: "The transfer failed")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        active = null
    }

    private companion object {
        const val MIN_UPDATE_MS = 100L
    }
}

/**
 * A floating glass card above the bottom edge while a transfer runs (radius tile, 20dp from the sides): what it does
 * (rowTitle ink1, the percent after it), the files, bytes and current file (monoMeta ink3), Cancel (danger text
 * action) and a 3dp progress line (accent on surface3; no fill when the total is unknown).
 */
@Composable
fun TransferBar(runner: TransferRunner, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    val shown = runner.active
    val last = remember { mutableStateOf<ActiveTransfer?>(null) }
    if (shown != null) last.value = shown
    AnimatedVisibility(
        visible = shown != null,
        enter = fadeIn(PiMotion.enter(PiMotion.QUICK)) + slideInVertically(PiMotion.enter(PiMotion.STANDARD)) { it / 2 },
        exit = fadeOut(PiMotion.leave(PiMotion.QUICK)) + slideOutVertically(PiMotion.leave(PiMotion.STANDARD)) { it / 2 },
        modifier = modifier,
    ) {
        val t = last.value ?: return@AnimatedVisibility
        val p = t.progress
        val fraction = p.fraction
        val shape = RoundedCornerShape(Radius.tile)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.page, vertical = Space.sm)
                .glass(shape, colors)
                .padding(start = Space.lg, end = Space.xs, top = Space.sm, bottom = Space.md),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    // TalkBack announces the transfer once (its label is the live region); the percent and the file
                    // change ten times a second, so they are read on focus and from the progress line, not announced.
                    Text(
                        t.label + (fraction?.let { " · ${(it * 100).roundToInt()}%" } ?: ""),
                        style = type.rowTitle,
                        color = colors.ink1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clearAndSetSemantics {
                            contentDescription = t.label
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                    val detail = buildList {
                        if (p.files > 0) add(if (p.files == 1) "1 file" else "${p.files} files")
                        add(ProjectFiles.size(p.bytes) + (p.total?.let { " of " + ProjectFiles.size(it) } ?: ""))
                        if (p.current.isNotEmpty()) add(p.current)
                    }.joinToString(" · ")
                    Text(detail, style = type.monoMeta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                }
                TextAction("Cancel", onClick = runner::cancel, color = colors.danger, modifier = Modifier.heightIn(min = 48.dp))
            }
            Spacer(Modifier.height(Space.sm))
            Box(
                Modifier
                    .padding(end = Space.md)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(colors.surface3, RoundedCornerShape(2.dp))
                    .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction ?: 0f, 0f..1f) },
            ) {
                // Unknown totals show no fill: an indeterminate bar would be an idle-looking animation.
                if (fraction != null) Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(colors.accent, RoundedCornerShape(2.dp)))
            }
        }
    }
}
