package opensec.pibolt.app.ui.components

import android.content.ClipData
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius

/** How long the check and "Copied" stay before the button returns to its copy state. */
const val COPIED_FEEDBACK_MS = 1500L

/**
 * Copies [text] and confirms it: the copy icon becomes a check in the success colour (and the label "Copy" becomes
 * "Copied") for [COPIED_FEEDBACK_MS], with a confirm haptic and a TalkBack announcement. With [label] false it is an
 * icon-only button. 48dp touch target either way. Shared by code blocks, the output viewer, bash cards and the
 * message action bar.
 */
@Composable
fun CopyButton(
    text: () -> String,
    modifier: Modifier = Modifier,
    label: Boolean = true,
    clipLabel: String = "Pi-Bolt",
    description: String = "Copy",
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val haptics = Pi.haptics
    val colors = Pi.colors
    // Bumped on each copy; the confirmation resets after the delay of the latest copy only.
    var copiedAt by remember { mutableIntStateOf(0) }
    val copied = copiedAt > 0
    LaunchedEffect(copiedAt) {
        if (copiedAt > 0) {
            delay(COPIED_FEEDBACK_MS)
            copiedAt = 0
        }
    }
    Row(
        modifier
            .heightIn(min = 48.dp)
            .pressable(
                onClick = {
                    val value = text()
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(clipLabel, value))) }
                    haptics.confirm()
                    copiedAt += 1
                },
                shape = RoundedCornerShape(Radius.sm),
                label = description,
            )
            .padding(horizontal = if (label) 10.dp else 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = copied,
            transitionSpec = {
                (fadeIn(PiMotion.inPlace(PiMotion.QUICK)) + scaleIn(PiMotion.inPlace(PiMotion.QUICK), initialScale = 0.7f))
                    .togetherWith(fadeOut(PiMotion.leave(PiMotion.QUICK)))
            },
            label = "copy",
        ) { done ->
            Icon(
                if (done) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                contentDescription = if (label) null else if (done) "Copied" else description,
                tint = if (done) colors.success else colors.ink2,
                modifier = Modifier.size(16.dp),
            )
        }
        if (label) {
            Spacer(Modifier.width(6.dp))
            Text(
                if (copied) "Copied" else "Copy",
                style = Pi.type.meta,
                color = if (copied) colors.success else colors.ink2,
            )
        }
    }
}

/**
 * [CopyButton] for a glass top bar: a [GlassIconButton] whose copy glyph becomes a success check for
 * [COPIED_FEEDBACK_MS], with the same haptic and TalkBack announcement ("Copied").
 */
@Composable
fun GlassCopyButton(text: () -> String, modifier: Modifier = Modifier, clipLabel: String = "Pi-Bolt", description: String = "Copy") {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val haptics = Pi.haptics
    val colors = Pi.colors
    var copiedAt by remember { mutableIntStateOf(0) }
    val copied = copiedAt > 0
    LaunchedEffect(copiedAt) {
        if (copiedAt > 0) {
            delay(COPIED_FEEDBACK_MS)
            copiedAt = 0
        }
    }
    GlassIconButton(
        if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
        if (copied) "Copied" else description,
        onClick = {
            val value = text()
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(clipLabel, value))) }
            haptics.confirm()
            copiedAt += 1
        },
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        tint = if (copied) colors.success else colors.ink1,
    )
}
