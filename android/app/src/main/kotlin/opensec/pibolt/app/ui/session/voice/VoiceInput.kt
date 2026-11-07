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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import opensec.pibolt.app.ui.components.ConfirmSheet

/**
 * Dictation for the composer, held above it so the pill's mic, its voice button and the voice surface share one
 * recogniser. Speech goes through Android's SpeechRecognizer, on the device when the phone has an on-device recogniser
 * (Android 12+), else the default service with EXTRA_PREFER_OFFLINE. Partial results appear in the draft as they come
 * ([VoiceDraft]); the final result replaces them. RECORD_AUDIO is asked for on the first use only, after a sheet that
 * says why ([VoiceRationale]).
 *
 * Two ways in, one behaviour: the mic dictates in place ([toggle]); the voice button opens the voice surface and
 * starts listening ([openSurface]). The surface stays until it is ended ([endSurface]); its mic starts and stops
 * listening again, each time adding to the draft.
 */
@Stable
class VoiceInput internal constructor(
    private val context: Context,
    private val draft: () -> String,
    private val onDraft: (String) -> Unit,
    private val say: (String) -> Unit,
    private val onHeard: () -> Unit,
    private val askPermission: () -> Unit,
) {
    var state by mutableStateOf(VoiceState.Idle)
        private set

    /** The microphone's level, 0..1 ([VoiceLevel]); 0 while not listening. */
    var level by mutableFloatStateOf(0f)
        private set

    /** The voice surface is open. */
    var surface by mutableStateOf(false)
        private set

    /** What the current (or last) dictation heard, for the surface's large text. */
    var heard by mutableStateOf("")
        private set

    /** The rationale sheet is asked for. */
    var rationale by mutableStateOf(false)

    val listening: Boolean get() = state == VoiceState.Listening || state == VoiceState.Starting

    private val session = VoiceSession()

    /** The mic: start dictating in place, or stop (the recogniser still delivers its final words). */
    fun toggle() {
        if (listening) session.stop() else begin()
    }

    /** The voice button: open the surface and start listening. */
    fun openSurface() {
        heard = ""
        surface = true
        if (!listening) begin()
    }

    /** The surface's red button: stop listening (the words stay in the draft) and close the surface. */
    fun endSurface() {
        if (listening) session.stop()
        surface = false
    }

    /** After the permission sheet and Android's prompt. */
    internal fun permissionResult(granted: Boolean) {
        if (granted) {
            start()
        } else {
            surface = false
            say("Pi-Bolt needs the microphone for dictation. You can allow it in Android's app settings.")
        }
    }

    internal fun release() = session.release()

    internal fun requestPermission() = askPermission()

    private fun begin() {
        when {
            !SpeechRecognizer.isRecognitionAvailable(context) -> {
                surface = false
                say("This phone has no speech recognition service")
            }
            granted(context) -> start()
            else -> rationale = true
        }
    }

    private fun start() {
        state = VoiceState.Starting
        heard = ""
        val text = draft()
        session.start(
            context,
            VoiceDraft(text, text.length),
            onState = {
                state = it
                if (it != VoiceState.Listening) level = 0f
            },
            onText = { spoken, next ->
                heard = spoken
                onHeard()
                onDraft(next)
            },
            onLevel = { level = it },
            onError = { message -> message?.let(say) },
        )
    }
}

/**
 * The composer's dictation. [onHeard] is called when speech reaches the draft, so the screen can mark the message as
 * dictated.
 */
@Composable
fun rememberVoiceInput(draft: String, onDraft: (String) -> Unit, onMessage: (String) -> Unit, onHeard: () -> Unit = {}): VoiceInput {
    val context = LocalContext.current
    val currentDraft by rememberUpdatedState(draft)
    val setDraft by rememberUpdatedState(onDraft)
    val say by rememberUpdatedState(onMessage)
    val heard by rememberUpdatedState(onHeard)
    val holder = remember { arrayOfNulls<VoiceInput>(1) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> holder[0]?.permissionResult(granted) }
    val voice = remember {
        VoiceInput(
            context,
            draft = { currentDraft },
            onDraft = { setDraft(it) },
            say = { say(it) },
            onHeard = { heard() },
            askPermission = { permission.launch(Manifest.permission.RECORD_AUDIO) },
        ).also { holder[0] = it }
    }
    DisposableEffect(voice) { onDispose { voice.release() } }
    return voice
}

/** The sheet that explains the microphone before Android asks for it, the first time dictation is used. */
@Composable
fun VoiceRationale(voice: VoiceInput) {
    if (!voice.rationale) return
    ConfirmSheet(
        title = "Dictate with the microphone?",
        message = "Pi-Bolt listens only while the mic is on and turns your speech into text in the composer, on the phone when it can. Android asks for microphone access next.",
        confirmLabel = "Continue",
        destructive = false,
        onConfirm = {
            voice.rationale = false
            voice.requestPermission()
        },
        onDismiss = {
            voice.rationale = false
            voice.endSurface()
        },
    )
}

private fun granted(context: Context): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** One recogniser at a time, created on the main thread when dictation starts and destroyed when it ends. */
private class VoiceSession {
    private var recognizer: SpeechRecognizer? = null

    fun start(
        context: Context,
        dictation: VoiceDraft,
        onState: (VoiceState) -> Unit,
        onText: (spoken: String, draft: String) -> Unit,
        onLevel: (Float) -> Unit,
        onError: (String?) -> Unit,
    ) {
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

            override fun onRmsChanged(rmsdB: Float) = onLevel(VoiceLevel.normalize(rmsdB))

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                onState(VoiceState.Idle)
                onError(VoiceErrors.message(error))
                release()
            }

            override fun onResults(results: Bundle?) {
                best(results)?.let { onText(it, dictation.with(it).first) }
                onState(VoiceState.Idle)
                release()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                best(partialResults)?.let { onText(it, dictation.with(it).first) }
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
