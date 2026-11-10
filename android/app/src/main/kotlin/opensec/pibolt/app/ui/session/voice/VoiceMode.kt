package opensec.pibolt.app.ui.session.voice

import android.content.Context
import android.media.audiofx.AcousticEchoCanceler
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import opensec.pibolt.app.data.AppSettings
import opensec.pibolt.app.session.SessionEffect
import opensec.pibolt.app.ui.session.LiveStatus
import opensec.pibolt.app.ui.session.ask.AskQuestions
import opensec.pibolt.app.ui.session.ask.AskSubmission
import opensec.pibolt.protocol.session.SessionState

/**
 * Hands-free voice mode for a chat or project session: listen, send when the speaker stops, show Pi working, say a
 * short summary of the reply, listen again. [VoiceModeMachine] decides; this class carries its actions out with the
 * phone's recogniser ([VoiceRecognizer]), text-to-speech ([VoiceSpeaker]) and audio focus ([VoiceAudio]).
 * [VoiceModeEffects] feeds it the session (turns, question cards, other dialogs) while it runs.
 */
@Stable
class VoiceMode internal constructor(
    private val context: Context,
    private val input: VoiceInput,
    private val hooks: Hooks,
) {
    /** What voice mode needs from the session screen. */
    class Hooks(
        val running: () -> Boolean,
        val send: (String) -> Unit,
        val submit: (AskSubmission) -> Unit,
        val settings: () -> AppSettings,
        val message: (String) -> Unit,
    )

    var state by mutableStateOf(VoiceModeState())
        private set

    /** The microphone's level while listening, 0..1. */
    var micLevel by mutableFloatStateOf(0f)
        private set

    /** Counts the words the speech engine has started, so the waveform can pulse with them. */
    var words by mutableIntStateOf(0)
        private set

    /** The last listen ran on the phone (true) or through the speech service (false); null before the first. */
    var onDevice by mutableStateOf<Boolean?>(null)
        private set

    /** The live turn's status line, for the Working state (set by [VoiceModeEffects]). */
    var status by mutableStateOf<LiveStatus?>(null)
        internal set

    val active: Boolean get() = state.active

    private val main = Handler(Looper.getMainLooper())
    private val recognizer = VoiceRecognizer()
    private var speaker: VoiceSpeaker? = null
    private val audio = VoiceAudio(context, onPause = { dispatch(VoiceEvent.Pause(it)) }, onResume = { dispatch(VoiceEvent.Resume) })
    private val queue = ArrayDeque<VoiceEvent>()
    private var dispatching = false
    private var pendingListen: Runnable? = null
    private val talkBack: Boolean
        get() = context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true

    /** "Voice mode" on the voice surface, or a long press on the waveform button. Asks for the mic first if needed. */
    fun start() {
        if (active) return
        input.withMic {
            input.handOff()
            // Barge-in needs the phone to cancel its own voice in the mic; TalkBack's speech would also stop it.
            val bargeIn = runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false) && !talkBack
            audio.acquire()
            dispatch(VoiceEvent.Start(hooks.running(), bargeIn, muted = !hooks.settings().voiceSpeakReplies))
        }
    }

    /** The red button and Back. */
    fun end() = dispatch(VoiceEvent.End)

    /** The big mic: talk, stop talking, or interrupt the speech. */
    fun tapMic() {
        if (state.paused || !audio.held) audio.acquire()
        dispatch(VoiceEvent.TapMic)
    }

    fun toggleMute() = dispatch(VoiceEvent.ToggleMute)

    /** "Show card": the question voice was reading goes to the card on screen. */
    fun showCard() = dispatch(VoiceEvent.ShowCard)

    /** Voice mode answers question card [toolCallId] (its sheet stays closed) unless it gave the card back. */
    fun claims(toolCallId: String): Boolean = active && toolCallId !in state.handled

    fun dispatch(event: VoiceEvent) {
        queue.addLast(event)
        if (dispatching) return
        dispatching = true
        try {
            while (queue.isNotEmpty()) {
                val step = VoiceModeMachine.next(state, queue.removeFirst())
                state = step.state
                step.actions.forEach(::perform)
            }
        } finally {
            dispatching = false
        }
    }

    private fun perform(action: VoiceAction) {
        when (action) {
            VoiceAction.Listen -> listen()
            VoiceAction.StopListening -> if (recognizer.active) {
                recognizer.stop()
            } else {
                // Nothing listens yet (the TalkBack pause): nothing was said.
                cancelPendingListen()
                dispatch(VoiceEvent.NoSpeech)
            }
            VoiceAction.CancelListening -> {
                cancelPendingListen()
                recognizer.release()
                micLevel = 0f
            }
            is VoiceAction.Speak -> speak(action.text)
            VoiceAction.StopSpeaking -> speaker?.stop()
            is VoiceAction.Send -> hooks.send(action.text)
            is VoiceAction.Submit -> hooks.submit(action.submission)
            VoiceAction.Release -> {
                cancelPendingListen()
                recognizer.release()
                micLevel = 0f
                speaker?.stop()
                audio.release()
            }
        }
    }

    private fun speak(text: String) {
        val s = hooks.settings()
        val engine = speaker ?: VoiceSpeaker(context, onDone = { dispatch(VoiceEvent.SpeechDone) }, onWord = { words++ }).also { speaker = it }
        if (engine.available == false) {
            hooks.message("Text-to-speech is not available on this phone")
            dispatch(VoiceEvent.SpeechDone)
            return
        }
        engine.speak(SpeechText.forTts(text), s.voiceRate, s.voiceName)
    }

    /**
     * Starts the recogniser unless it already listens. With TalkBack on it waits a moment, so TalkBack's own words
     * ("Listening") are not heard as the user's.
     */
    private fun listen() {
        if (recognizer.active || pendingListen != null) return
        val run = Runnable {
            pendingListen = null
            val s = state
            val wanted = s.phase == VoicePhase.Listening || (s.bargeIn && (s.phase == VoicePhase.Speaking || s.phase == VoicePhase.Asking))
            if (wanted && !s.paused && !recognizer.active) startRecognizer()
        }
        if (talkBack) {
            pendingListen = run
            main.postDelayed(run, TALKBACK_PAUSE_MS)
        } else {
            run.run()
        }
    }

    private fun cancelPendingListen() {
        pendingListen?.let(main::removeCallbacks)
        pendingListen = null
    }

    private fun startRecognizer() {
        audio.listening()
        recognizer.start(
            context,
            object : VoiceRecognizer.Listener {
                override fun onReady() {
                    onDevice = recognizer.onDevice
                }

                override fun onLevel(level: Float) {
                    micLevel = level
                }

                override fun onPartial(text: String) = dispatch(VoiceEvent.Partial(text))

                override fun onFinal(text: String?) {
                    micLevel = 0f
                    dispatch(if (text == null) VoiceEvent.NoSpeech else VoiceEvent.Heard(text))
                }

                override fun onError(message: String) {
                    micLevel = 0f
                    dispatch(VoiceEvent.Failed(message))
                }
            },
        )
    }

    /** The screen left: everything stops. */
    internal fun release() {
        if (active) dispatch(VoiceEvent.End)
        perform(VoiceAction.Release)
        speaker?.shutdown()
        speaker = null
    }

    private companion object {
        const val TALKBACK_PAUSE_MS = 900L
    }
}

/** What the speech engine is given (pure, unit-tested): an ellipsis read as a pause, not "dot dot dot". */
object SpeechText {
    fun forTts(text: String): String = text.replace("…", ".").replace(Regex("""\.{2,}"""), ".").trim()
}

/** Voice mode for the session screen; [input] is the composer's dictation (its permission sheet and microphone). */
@Composable
fun rememberVoiceMode(
    input: VoiceInput,
    running: () -> Boolean,
    send: (String) -> Unit,
    submit: (AskSubmission) -> Unit,
    settings: AppSettings,
    onMessage: (String) -> Unit,
): VoiceMode {
    val context = LocalContext.current
    val isRunning by rememberUpdatedState(running)
    val sendNow by rememberUpdatedState(send)
    val submitNow by rememberUpdatedState(submit)
    val current by rememberUpdatedState(settings)
    val say by rememberUpdatedState(onMessage)
    val mode = remember(input) {
        VoiceMode(context, input, VoiceMode.Hooks(running = { isRunning() }, send = { sendNow(it) }, submit = { submitNow(it) }, settings = { current }, message = { say(it) }))
    }
    DisposableEffect(mode) { onDispose { mode.release() } }
    return mode
}

/**
 * Feeds voice mode the session while it runs: turn starts and ends (with the reply's spoken summary), a send that
 * failed, `ask_user_question` cards and other dialogs, and the status line. It also keeps the screen on, ends voice
 * mode on Back, and pauses it while the app is in the background.
 */
@Composable
fun VoiceModeEffects(mode: VoiceMode, state: State<SessionState>, status: State<LiveStatus>, effects: Flow<SessionEffect>) {
    if (!mode.active) return
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    BackHandler { mode.end() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        var away = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    away = true
                    mode.dispatch(VoiceEvent.Pause(FocusRules.BACKGROUND))
                }
                Lifecycle.Event.ON_START -> if (away) {
                    away = false
                    if (mode.state.notice == FocusRules.BACKGROUND) mode.dispatch(VoiceEvent.Resume)
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(mode) {
        snapshotFlow { status.value }.collect { mode.status = it }
    }
    LaunchedEffect(mode) {
        snapshotFlow { state.value.isRunning }.distinctUntilChanged().drop(1).collect { running ->
            mode.dispatch(if (running) VoiceEvent.RunStarted else VoiceEvent.RunEnded(SpokenSummary.forTurn(state.value.allItems)))
        }
    }
    LaunchedEffect(mode) {
        effects.collect { if (it is SessionEffect.EditorText) mode.dispatch(VoiceEvent.SendFailed) }
    }
    // A prompt that never starts a run (an extension command, a host that does not answer) does not hold voice mode.
    val sending = mode.state.phase == VoicePhase.Sending
    LaunchedEffect(sending) {
        if (!sending) return@LaunchedEffect
        delay(SEND_TIMEOUT_MS)
        if (mode.state.phase == VoicePhase.Sending && !state.value.isRunning) mode.dispatch(VoiceEvent.RunEnded(SpokenSummary.forTurn(state.value.allItems)))
    }
    LaunchedEffect(mode) {
        val announced = HashSet<String>()
        snapshotFlow { state.value.dialogs.firstOrNull() }.distinctUntilChanged().collect { dialog ->
            if (dialog == null) {
                if (mode.state.question != null) mode.dispatch(VoiceEvent.DialogGone)
                return@collect
            }
            val owner = AskQuestions.owner(state.value, dialog)
            if (owner != null) {
                val (call, questions) = owner
                mode.dispatch(VoiceEvent.Ask(call.toolCallId, questions, AskQuestions.stepOf(dialog, questions)?.question ?: 0))
            } else if (announced.add(dialog.id)) {
                mode.dispatch(VoiceEvent.OtherDialog(dialog.title))
            }
        }
    }
}

private const val SEND_TIMEOUT_MS = 20_000L
