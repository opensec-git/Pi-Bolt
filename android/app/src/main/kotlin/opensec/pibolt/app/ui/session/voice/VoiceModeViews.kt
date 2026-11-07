package opensec.pibolt.app.ui.session.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicNone
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.components.DotWave
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.ui.components.WorkingLine
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Space
import opensec.pibolt.app.ui.theme.Tnum

/**
 * Voice mode, in the composer's place: a state line (what it does, with the Quad while Pi works and the current tool;
 * "Show card" while it reads a question; whether speech is recognised on the phone), the captions (what was heard in
 * large ink2 type, what Pi says in ink1), a question's options while one is read out, then one row: the big round mic
 * (tap to talk, to finish talking, or to interrupt the speech), the dot-matrix waveform (the mic level while listening,
 * the speech's words while speaking), mute replies, and the red end button. Back ends it too ([VoiceModeEffects]).
 *
 * TalkBack: the state line is a polite live region; the captions are not (the speech already says the reply, and
 * TalkBack reading it too would talk over it).
 */
@Composable
fun VoiceModeSurface(mode: VoiceMode, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    val s = mode.state
    val listening = s.phase == VoicePhase.Listening
    val speaking = s.phase == VoicePhase.Speaking || s.phase == VoicePhase.Asking
    Column(modifier.fillMaxWidth().padding(top = Space.sm)) {
        StateLine(mode)
        val heard = s.heard
        if (heard.isNotBlank()) {
            Text(
                VoiceText.tail(heard, HEARD_MAX),
                style = type.title.copy(fontWeight = FontWeight.Normal),
                color = colors.ink2,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.xs, vertical = Space.xs).semantics { contentDescription = "You said: $heard" },
            )
        }
        val spoken = s.spoken
        Crossfade(spoken, animationSpec = PiMotion.inPlace(PiMotion.QUICK), label = "voiceSpoken") { line ->
            if (line.isNotBlank()) {
                Text(
                    line,
                    style = type.rowTitle,
                    color = colors.ink1,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Space.xs, vertical = Space.xs).semantics { contentDescription = "Pi: $line" },
                )
            }
        }
        val question = s.question
        AnimatedVisibility(
            visible = question != null,
            enter = expandVertically(PiMotion.enter(PiMotion.STANDARD)) + fadeIn(PiMotion.enter(PiMotion.QUICK)),
            exit = shrinkVertically(PiMotion.leave(PiMotion.STANDARD)) + fadeOut(PiMotion.leave(PiMotion.QUICK)),
        ) {
            val options = remember(question?.toolCallId, question?.index) { question?.current?.options.orEmpty() }
            Column(Modifier.fillMaxWidth().padding(horizontal = Space.xs, vertical = Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                options.forEachIndexed { i, o ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", style = type.rowMeta.copy(fontFeatureSettings = Tnum), color = colors.ink3, modifier = Modifier.width(Space.xl))
                        Text(o.label, style = type.rowMeta, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        val mic by animateFloatAsState(if (listening) mode.micLevel else 0f, PiMotion.inPlace(PiMotion.TAP), label = "voiceMic")
        val wave = when {
            listening -> mic
            speaking -> speechLevel(mode.words)
            else -> 0f
        }
        Row(Modifier.fillMaxWidth().heightIn(min = ROUND + Space.sm), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(
                description = when {
                    listening && !s.stopping -> "Done talking"
                    speaking -> "Interrupt and talk"
                    else -> "Talk"
                },
                fill = if (listening) colors.accent else colors.surface3,
                ring = if (listening) colors.accent else colors.lineStrong,
                onClick = { mode.tapMic() },
                state = if (listening) "Listening" else "Not listening",
            ) {
                Icon(if (listening) Icons.Rounded.Mic else Icons.Rounded.MicNone, contentDescription = null, tint = if (listening) colors.onAccent else colors.ink1, modifier = Modifier.size(IconSize.lg))
            }
            Spacer(Modifier.width(Space.md))
            DotWave(wave, Modifier.weight(1f), color = if (listening || speaking) colors.ink1 else colors.ink3)
            Spacer(Modifier.width(Space.sm))
            RoundButton(
                description = if (s.muted) "Speak replies" else "Mute replies",
                fill = colors.surface3,
                ring = colors.lineStrong,
                onClick = { mode.toggleMute() },
                state = if (s.muted) "Replies muted" else "Replies spoken",
                size = MUTE,
            ) {
                Icon(if (s.muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null, tint = colors.ink1, modifier = Modifier.size(IconSize.md))
            }
            Spacer(Modifier.width(Space.sm))
            RoundButton(description = "End voice mode", fill = colors.danger, ring = colors.danger, onClick = { mode.end() }) {
                // White on the danger red in every theme (a glyph: 3:1 is enough, and it is about 4:1).
                Icon(Icons.Rounded.CallEnd, contentDescription = null, tint = Color.White, modifier = Modifier.size(IconSize.lg))
            }
        }
    }
}

/** What voice mode is doing, in one line: a polite live region for TalkBack. */
@Composable
private fun StateLine(mode: VoiceMode) {
    val colors = Pi.colors
    val type = Pi.type
    val s = mode.state
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val live = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }
        val status = mode.status
        when {
            s.paused -> Text(s.notice ?: "Paused", style = type.rowMeta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = live)
            s.phase == VoicePhase.Working -> {
                val label = status?.text?.let { t -> listOf(t.label.ifBlank { status.mode.label }, t.detail).filter { it.isNotBlank() }.joinToString(" ") }?.ifBlank { null } ?: "Working"
                WorkingLine(label, live, mode = status?.mode ?: QuadMode.Think)
            }
            s.phase == VoicePhase.Sending -> WorkingLine("Sending", live)
            s.phase == VoicePhase.Asking || (s.question != null && s.phase == VoicePhase.Listening) -> Row(live, verticalAlignment = Alignment.CenterVertically) {
                QuadSpinner(QuadMode.Wait, size = IconSize.xs, color = colors.warning)
                Spacer(Modifier.width(Space.sm))
                Text(if (s.phase == VoicePhase.Listening) "Pi is asking · say an option" else "Pi is asking", style = type.metaStrong, color = colors.warning, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            else -> Text(
                when (s.phase) {
                    VoicePhase.Listening -> if (s.stopping) "Finishing" else "Listening"
                    VoicePhase.Speaking -> "Speaking"
                    else -> s.notice ?: "Tap the mic to talk"
                },
                style = type.rowMeta,
                color = if (s.phase == VoicePhase.Idle) colors.ink3 else colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = live,
            )
        }
        if (s.question != null) {
            TextAction("Show card", onClick = { mode.showCard() }, color = colors.ink2)
        } else {
            mode.onDevice?.let { local ->
                Text(if (local) "On-device speech" else "Online speech", style = type.meta, color = colors.ink3, maxLines = 1)
            }
        }
    }
}

/**
 * The waveform's level while the phone speaks: a slow breath, and a lift at each word the engine reports ([words]).
 * Reduce motion holds it still.
 */
@Composable
private fun speechLevel(words: Int): Float {
    if (Pi.reduceMotion) return STILL
    val breath by rememberInfiniteTransition(label = "voiceBreath").animateFloat(
        initialValue = 0.22f,
        targetValue = 0.42f,
        animationSpec = infiniteRepeatable(tween(PiMotion.SWEEP), RepeatMode.Reverse),
        label = "voiceBreathLevel",
    )
    val lift = remember { Animatable(0f) }
    LaunchedEffect(words) {
        if (words == 0) return@LaunchedEffect
        lift.animateTo(0.75f, tween(PiMotion.TAP))
        lift.animateTo(0f, tween(PiMotion.SETTLE))
    }
    return maxOf(breath, lift.value)
}

private const val STILL = 0.35f
private const val HEARD_MAX = 160
private val MUTE = 48.dp
