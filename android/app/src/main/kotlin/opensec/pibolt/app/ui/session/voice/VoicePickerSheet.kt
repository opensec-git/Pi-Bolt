package opensec.pibolt.app.ui.session.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import opensec.pibolt.app.ui.components.InsetRow
import opensec.pibolt.app.ui.components.LoadingRow
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Space

/**
 * Settings' voice picker for voice mode: "Default" (the engine's voice) and the engine's installed voices in the
 * phone's language ([TtsVoices]), on-device ones first, "Needs internet" under the others. A pick is kept and says a
 * short sample at [ratePercent], so voices can be compared; the sheet stays open until dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePickerSheet(selected: String?, ratePercent: Int, onPick: (name: String?, label: String?) -> Unit, onDismiss: () -> Unit) {
    val colors = Pi.colors
    val context = LocalContext.current
    var voices by remember { mutableStateOf<List<TtsVoice>?>(null) }
    var engine by remember { mutableStateOf(true) }
    val speaker = remember { VoiceSpeaker(context) }
    DisposableEffect(speaker) {
        speaker.whenReady {
            engine = speaker.available == true
            voices = speaker.voices()
        }
        onDispose { speaker.shutdown() }
    }
    val haptics = Pi.haptics
    PiSheet(onDismiss = onDismiss) {
        SheetHeader("Voice", subtitle = "How voice mode reads replies")
        val list = voices
        when {
            !engine -> Text(
                "This phone has no text-to-speech engine. Install one (Speech Services by Google) to hear replies.",
                style = Pi.type.rowMeta,
                color = colors.ink3,
                modifier = Modifier.padding(horizontal = Space.page, vertical = Space.md),
            )
            list == null -> LoadingRow("Loading voices")
            else -> {
                val labels = remember(list) { TtsVoices.labels(list) }
                val rows = listOf<Pair<String?, String>>(null to "Default") + list.map { it.name to (labels[it.name] ?: it.localeName) }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    rows.forEachIndexed { i, (name, label) ->
                        val on = name == selected
                        val voice = list.firstOrNull { it.name == name }
                        InsetRow(
                            label,
                            onClick = {
                                if (!on) haptics.tick()
                                onPick(name, if (name == null) null else label)
                                speaker.speak(SAMPLE, ratePercent, name)
                            },
                            meta = if (voice?.network == true) "Needs internet" else null,
                            modifier = Modifier.semantics { this.selected = on },
                            divider = i < rows.lastIndex,
                            trailing = if (on) {
                                { Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(IconSize.md)) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
}

private const val SAMPLE = "Hi. This is how I'll read replies in voice mode."
