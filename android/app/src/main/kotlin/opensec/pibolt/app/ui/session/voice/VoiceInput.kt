package opensec.pibolt.app.ui.session.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
 * listening again, each time adding to the draft. Voice mode ([VoiceMode]) asks for the microphone through [withMic]
 * and takes it over with [handOff].
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

    private val session = VoiceRecognizer()

    /** What to run once the microphone is allowed (dictation, or voice mode). */
    private var afterGrant: (() -> Unit)? = null

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
        val then = afterGrant
        afterGrant = null
        if (granted) {
            (then ?: ::start)()
        } else {
            surface = false
            say("Microphone access is off. Allow it in Android's app settings.")
        }
    }

    /** The rationale sheet was dismissed: nothing waits for the microphone any more. */
    internal fun declined() {
        afterGrant = null
        endSurface()
    }

    /** Voice mode takes the microphone: dictation stops at once (the words heard stay in the draft). */
    internal fun handOff() {
        session.release()
        state = VoiceState.Idle
        level = 0f
        surface = false
    }

    internal fun release() = session.release()

    /**
     * Stops dictating at once and drops the words still on their way: the composer's text was sent or edited by hand,
     * and a late result rebuilt the draft from the text the dictation started with (the sent prompt came back, typed
     * words were erased).
     */
    fun cancel() {
        session.release()
        state = VoiceState.Idle
        level = 0f
    }

    internal fun requestPermission() = askPermission()

    private fun begin() = withMic { start() }

    /**
     * Runs [then] once the microphone may be used: at once when RECORD_AUDIO is granted, else after the rationale sheet
     * and Android's prompt (voice mode starts through here too). Without a speech service it says so instead.
     */
    internal fun withMic(then: () -> Unit) {
        when {
            !SpeechRecognizer.isRecognitionAvailable(context) -> {
                surface = false
                say("This phone has no speech recognition service")
            }
            granted(context) -> then()
            else -> {
                afterGrant = then
                rationale = true
            }
        }
    }

    private fun start() {
        state = VoiceState.Starting
        heard = ""
        val text = draft()
        val dictation = VoiceDraft(text, text.length)
        fun idle() {
            state = VoiceState.Idle
            level = 0f
        }
        session.start(
            context,
            object : VoiceRecognizer.Listener {
                override fun onReady() {
                    state = VoiceState.Listening
                }

                override fun onLevel(level: Float) {
                    this@VoiceInput.level = level
                }

                override fun onPartial(text: String) = heard(text)

                override fun onFinal(text: String?) {
                    text?.let(::heard)
                    idle()
                }

                override fun onError(message: String) {
                    idle()
                    say(message)
                }

                private fun heard(spoken: String) {
                    heard = spoken
                    onHeard()
                    onDraft(dictation.with(spoken).first)
                }
            },
        ).also { started -> if (!started) idle() }
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
        message = "Pi-Bolt listens only while the mic is on and turns speech into text, on the phone when it can. Android asks for access next.",
        confirmLabel = "Continue",
        destructive = false,
        onConfirm = {
            voice.rationale = false
            voice.requestPermission()
        },
        onDismiss = {
            voice.rationale = false
            voice.declined()
        },
    )
}

private fun granted(context: Context): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
