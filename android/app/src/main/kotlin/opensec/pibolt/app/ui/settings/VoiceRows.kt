package opensec.pibolt.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VoiceChat
import androidx.compose.ui.graphics.Color
import opensec.pibolt.app.data.AppSettings
import opensec.pibolt.app.ui.components.GroupRow
import opensec.pibolt.app.ui.components.GroupSwitchRow

/**
 * Voice mode in Settings > Agent: "Speak replies" (off: replies are shown, not said), "Speech rate" and "Voice" (the
 * text-to-speech engine's voices, VoicePickerSheet).
 */
internal fun voiceRows(settings: AppSettings, tint: Color, update: ((AppSettings) -> AppSettings) -> Unit, onRate: () -> Unit, onVoice: () -> Unit): List<SettingsRow> = listOf<SettingsRow>(
    { d ->
        GroupSwitchRow(
            "Speak replies",
            settings.voiceSpeakReplies,
            { on -> update { it.copy(voiceSpeakReplies = on) } },
            meta = "In voice mode. Hold the voice button to start it",
            icon = Icons.Rounded.VoiceChat,
            iconTint = tint,
            divider = d,
        )
    },
    { d -> GroupRow("Speech rate", icon = Icons.Rounded.Speed, iconTint = tint, value = rateLabel(settings.voiceRate), onClick = onRate, divider = d) },
    { d -> GroupRow("Voice", icon = Icons.Rounded.RecordVoiceOver, iconTint = tint, value = settings.voiceLabel ?: "Default", onClick = onVoice, divider = d) },
)

/** "Normal", "1.25x". */
internal fun rateLabel(percent: Int): String = if (percent == 100) "Normal" else "${percent / 100}${if (percent % 100 == 0) "" else "." + (percent % 100).toString().trimEnd('0')}x"
