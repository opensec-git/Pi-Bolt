package opensec.pibolt.app.ui.session.voice

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicNone
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.components.ButtonSize
import opensec.pibolt.app.ui.components.DotWave
import opensec.pibolt.app.ui.components.GhostButton
import opensec.pibolt.app.ui.components.SOFT_PRESS
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.session.ComposerLayout
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Space

/**
 * The pill's mic: a 36dp circle in a 48dp target, the kit's `ComposerCircle` look (the selected overlay, an ink1 MicNone) at
 * rest; while it dictates in place the circle turns accent with an onAccent Mic (no pulse), and TalkBack hears
 * "Listening". The surface's own listening shows on the surface, not here.
 */
@Composable
fun VoiceMic(voice: VoiceInput, enabled: Boolean, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val on = voice.listening && !voice.surface
    val fill by animateColorAsState(if (on) colors.accent else colors.selected, PiMotion.effects(), label = "mic")
    val description = if (on) "Stop dictation" else "Dictate"
    Box(modifier.size(ComposerLayout.TARGET), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(ComposerLayout.CIRCLE)
                .pressable({ voice.toggle() }, shape = CircleShape, enabled = enabled || on, pressedScale = SOFT_PRESS, label = description)
                .semantics {
                    contentDescription = description
                    stateDescription = if (on) "Listening" else "Not listening"
                    liveRegion = LiveRegionMode.Polite
                }
                .background(fill, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (on) Icons.Rounded.Mic else Icons.Rounded.MicNone,
                contentDescription = null,
                tint = when {
                    on -> colors.onAccent
                    enabled -> colors.ink1
                    else -> colors.ink1.copy(alpha = 0.4f)
                },
                modifier = Modifier.size(IconSize.md),
            )
        }
    }
}

/**
 * The voice surface, in the composer's place while voice input is open: what was heard in large type (ink1; a hint in
 * ink3 before any words), then one row: a round mic (56dp; accent while it listens, tap to stop or to talk again), the
 * dot-matrix waveform driven by the microphone's level, and a round red button that ends voice input. The words go to
 * the draft as they are heard, so ending it leaves them in the composer, ready to send. Back ends it too.
 * [onVoiceMode] adds "Voice mode" above the row: the hands-free conversation ([VoiceModeSurface]).
 */
@Composable
fun VoiceSurface(voice: VoiceInput, modifier: Modifier = Modifier, onVoiceMode: (() -> Unit)? = null) {
    val colors = Pi.colors
    val type = Pi.type
    BackHandler(enabled = voice.surface) { voice.endSurface() }
    val level by animateFloatAsState(if (voice.listening) voice.level else 0f, PiMotion.inPlace(PiMotion.TAP), label = "level")
    Column(modifier.fillMaxWidth().padding(top = Space.sm)) {
        val words = voice.heard
        Crossfade(words.isEmpty(), animationSpec = PiMotion.inPlace(PiMotion.QUICK), label = "voiceText") { empty ->
            Text(
                when {
                    !empty -> VoiceText.tail(words)
                    voice.state == VoiceState.Starting -> "Starting…"
                    voice.listening -> "Listening…"
                    else -> "Tap the mic to talk"
                },
                style = type.title.copy(fontWeight = FontWeight.Normal),
                color = if (empty) colors.ink3 else colors.ink1,
                maxLines = 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.xs, vertical = Space.md)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (onVoiceMode != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                GhostButton("Voice mode", onClick = onVoiceMode, icon = Icons.Rounded.RecordVoiceOver, size = ButtonSize.Small)
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = ROUND + Space.sm), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(
                description = if (voice.listening) "Stop listening" else "Talk",
                fill = if (voice.listening) colors.accent else colors.surface3,
                ring = if (voice.listening) colors.accent else colors.lineStrong,
                onClick = { voice.toggle() },
                state = if (voice.listening) "Listening" else "Not listening",
            ) {
                Icon(if (voice.listening) Icons.Rounded.Mic else Icons.Rounded.MicNone, contentDescription = null, tint = if (voice.listening) colors.onAccent else colors.ink1, modifier = Modifier.size(IconSize.lg))
            }
            Spacer(Modifier.width(Space.md))
            DotWave(level, Modifier.weight(1f), color = if (voice.listening) colors.ink1 else colors.ink3)
            Spacer(Modifier.width(Space.md))
            RoundButton(description = "End voice input", fill = colors.danger, ring = colors.danger, onClick = { voice.endSurface() }) {
                // White on the danger red in every theme (a glyph: 3:1 is enough, and it is about 4:1).
                Icon(Icons.Rounded.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(IconSize.lg))
            }
        }
    }
}

/** A round button of the voice surface and voice mode: 56dp by default (a 48dp target inside it). */
@Composable
internal fun RoundButton(description: String, fill: Color, ring: Color, onClick: () -> Unit, state: String? = null, size: Dp = ROUND, content: @Composable () -> Unit) {
    val bg by animateColorAsState(fill, PiMotion.effects(), label = "round")
    val edge by animateColorAsState(ring, PiMotion.effects(), label = "roundEdge")
    Box(
        Modifier
            .size(size)
            .pressable(onClick, shape = CircleShape, pressedScale = SOFT_PRESS, label = description)
            .semantics {
                contentDescription = description
                if (state != null) stateDescription = state
            }
            .background(bg, CircleShape)
            .border(1.dp, edge, CircleShape),
        contentAlignment = Alignment.Center,
    ) { content() }
}

internal val ROUND: Dp = 56.dp

/** What the voice surface shows of a long dictation. */
object VoiceText {
    /** Past this many characters only the end shows, from a word start, after an ellipsis. */
    const val MAX = 220

    fun tail(text: String, max: Int = MAX): String {
        val t = text.trim()
        if (t.length <= max) return t
        val cut = t.length - max
        val space = t.indexOf(' ', cut).takeIf { it in cut until t.length - 1 } ?: cut
        return "…" + t.substring(space).trimStart()
    }
}
