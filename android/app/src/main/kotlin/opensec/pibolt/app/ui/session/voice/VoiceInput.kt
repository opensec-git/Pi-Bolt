package opensec.pibolt.app.ui.session.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicNone
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import opensec.pibolt.app.ui.components.ConfirmSheet
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.theme.Pi

/**
 * The composer's mic: tap to dictate, tap again (or stop talking) to finish. Speech goes through Android's
 * SpeechRecognizer, on the device when the phone has an on-device recogniser (Android 12+), else the default service
 * with EXTRA_PREFER_OFFLINE. Partial results appear in the draft as they come ([VoiceDraft]); the final result replaces
 * them. RECORD_AUDIO is asked for on the first tap only, after a sheet that says why. Listening shows as an accent disc
 * (no animation) and TalkBack hears "Listening".
 */
@Composable
fun VoiceButton(draft: String, onDraft: (String) -> Unit, enabled: Boolean, onMessage: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = Pi.colors
    val currentDraft by rememberUpdatedState(draft)
    val setDraft by rememberUpdatedState(onDraft)
    val say by rememberUpdatedState(onMessage)
    var state by remember { mutableStateOf(VoiceState.Idle) }
    var rationale by remember { mutableStateOf(false) }
    val session = remember { VoiceSession() }
    DisposableEffect(Unit) { onDispose { session.release() } }

    fun start() {
        state = VoiceState.Starting
        session.start(
            context,
            VoiceDraft(currentDraft, currentDraft.length),
            onState = { state = it },
            onText = { setDraft(it) },
            onError = { message -> message?.let(say) },
        )
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else say("Pi-Bolt needs the microphone for dictation. You can allow it in Android's app settings.")
    }

    val listening = state == VoiceState.Listening || state == VoiceState.Starting
    val description = if (listening) "Stop dictation" else "Dictate"
    Box(
        modifier
            .size(48.dp)
            .pressable(
                onClick = {
                    when {
                        listening -> session.stop()
                        !SpeechRecognizer.isRecognitionAvailable(context) -> say("This phone has no speech recognition service")
                        granted(context) -> start()
                        else -> rationale = true
                    }
                },
                shape = CircleShape,
                enabled = enabled,
                pressedScale = 0.92f,
                label = description,
            )
            .semantics {
                stateDescription = if (listening) "Listening" else "Not listening"
                liveRegion = LiveRegionMode.Polite
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(34.dp).background(if (listening) colors.accent else colors.surface1, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (listening) Icons.Rounded.Mic else Icons.Rounded.MicNone,
                contentDescription = description,
                tint = when {
                    listening -> colors.onAccent
                    enabled -> colors.ink2
                    else -> colors.ink4
                },
                modifier = Modifier.size(22.dp),
            )
        }
    }

    if (rationale) {
        ConfirmSheet(
            title = "Dictate with the microphone?",
            message = "Pi-Bolt listens only while the mic is on and turns your speech into text in the composer, on the phone when it can. Android asks for microphone access next.",
            confirmLabel = "Continue",
            destructive = false,
            onConfirm = {
                rationale = false
                permission.launch(Manifest.permission.RECORD_AUDIO)
            },
            onDismiss = { rationale = false },
        )
    }
}

private fun granted(context: Context): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** One recogniser at a time, created on the main thread when dictation starts and destroyed when it ends. */
private class VoiceSession {
    private var recognizer: SpeechRecognizer? = null

    fun start(context: Context, dictation: VoiceDraft, onState: (VoiceState) -> Unit, onText: (String) -> Unit, onError: (String?) -> Unit) {
        release()
        val onDevice = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        val r = runCatching { if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        if (r == null) {
            onState(VoiceState.Idle)
            onError("Speech recognition is not available")
            return
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onState(VoiceState.Listening)

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                onState(VoiceState.Idle)
                onError(VoiceErrors.message(error))
                release()
            }

            override fun onResults(results: Bundle?) {
                best(results)?.let { onText(dictation.with(it).first) }
                onState(VoiceState.Idle)
                release()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                best(partialResults)?.let { onText(dictation.with(it).first) }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        runCatching { r.startListening(intent) }.onFailure {
            onState(VoiceState.Idle)
            onError("Could not start listening")
            release()
        }
    }

    /** Ends listening; the recogniser still delivers its final result. */
    fun stop() {
        recognizer?.stopListening()
    }

    fun release() {
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
    }

    private fun best(results: Bundle?): String? = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
}
