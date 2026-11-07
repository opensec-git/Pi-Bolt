package opensec.pibolt.app.ui.session.voice

import androidx.compose.runtime.Immutable
import opensec.pibolt.app.ui.session.ask.AskAnswer
import opensec.pibolt.app.ui.session.ask.AskQuestion
import opensec.pibolt.app.ui.session.ask.AskSubmission
import java.util.Locale

/**
 * Where voice mode is. The loop is Listening → Sending → Working → Speaking → Listening; Asking reads a question card
 * out (then Listening takes the answer); Idle waits for a tap on the mic (after silence, a stop, or an error).
 */
enum class VoicePhase { Off, Idle, Listening, Sending, Working, Speaking, Asking }

/** An `ask_user_question` call voice mode is answering: its questions, the one asked now, the answers so far. */
@Immutable
data class VoiceQuestion(
    val toolCallId: String,
    val questions: List<AskQuestion>,
    val index: Int = 0,
    val answers: List<AskAnswer> = emptyList(),
    val misses: Int = 0,
) {
    val current: AskQuestion get() = questions[index]
}

/**
 * Voice mode's state. [running]: Pi works on a turn. [muted]: replies are shown, not spoken. [paused]: a call or
 * another app has the audio (or the app went to the background); nothing listens or speaks until it comes back or the
 * mic is tapped. [bargeIn]: the phone listens while it speaks, so talking stops the speech. [heard] and [spoken] are the
 * captions. [handled]: question calls voice already answered or gave to the card. [pendingReply]: a reply that came
 * while the user was talking or the audio was away, said next. [stopping]: the mic was tapped to finish talking.
 */
@Immutable
data class VoiceModeState(
    val phase: VoicePhase = VoicePhase.Off,
    val running: Boolean = false,
    val muted: Boolean = false,
    val paused: Boolean = false,
    val bargeIn: Boolean = false,
    val heard: String = "",
    val spoken: String = "",
    val question: VoiceQuestion? = null,
    val handled: Set<String> = emptySet(),
    val pendingReply: String? = null,
    val silences: Int = 0,
    val stopping: Boolean = false,
    val notice: String? = null,
) {
    val active: Boolean get() = phase != VoicePhase.Off
}

/** What happens to voice mode: the user's taps, the recogniser, the speech engine, the session and the audio. */
sealed interface VoiceEvent {
    data class Start(val running: Boolean, val bargeIn: Boolean, val muted: Boolean) : VoiceEvent

    data object TapMic : VoiceEvent

    data class Partial(val text: String) : VoiceEvent

    /** The recogniser's final words. */
    data class Heard(val text: String) : VoiceEvent

    /** The recogniser stopped with nothing (silence, no match, or stopped). */
    data object NoSpeech : VoiceEvent

    /** The recogniser failed. */
    data class Failed(val message: String) : VoiceEvent

    data object RunStarted : VoiceEvent

    /** The turn ended; [reply] is what to say ([SpokenSummary.forTurn]). */
    data class RunEnded(val reply: String) : VoiceEvent

    data object SendFailed : VoiceEvent

    /** The speech engine finished (or failed) the current utterance. */
    data object SpeechDone : VoiceEvent

    data object ToggleMute : VoiceEvent

    data class Pause(val reason: String) : VoiceEvent

    data object Resume : VoiceEvent

    /** An `ask_user_question` dialog is open; [first] is its first open question. */
    data class Ask(val toolCallId: String, val questions: List<AskQuestion>, val first: Int = 0) : VoiceEvent

    /** Another dialog voice cannot answer: it is said, and answered on screen. */
    data class OtherDialog(val title: String) : VoiceEvent

    /** The question voice was answering went away (answered on screen, or the call ended). */
    data object DialogGone : VoiceEvent

    /** "Show card": the question goes to the card on screen. */
    data object ShowCard : VoiceEvent

    data object End : VoiceEvent
}

/** What the machine asks of the phone. */
sealed interface VoiceAction {
    /** Start listening (nothing when it already listens). */
    data object Listen : VoiceAction

    /** Finish listening: the recogniser still delivers what it heard. */
    data object StopListening : VoiceAction

    /** Stop listening and drop what was heard. */
    data object CancelListening : VoiceAction

    data class Speak(val text: String) : VoiceAction

    data object StopSpeaking : VoiceAction

    /** Send [text] to Pi (a prompt, or a steer while it works). */
    data class Send(val text: String) : VoiceAction

    data class Submit(val submission: AskSubmission) : VoiceAction

    /** Voice mode ended: give back the audio and the screen. */
    data object Release : VoiceAction
}

data class VoiceStep(val state: VoiceModeState, val actions: List<VoiceAction> = emptyList())

/** Voice mode's state machine (pure, unit-tested): [next] is the whole behaviour; [VoiceMode] carries out the actions. */
object VoiceModeMachine {
    /** Silent listens in a row before voice mode waits for a tap. */
    const val MAX_SILENCES = 2

    /** Unclear answers to a question before it goes to the card. */
    const val MAX_MISSES = 2

    const val NOT_SENT = "Not sent"

    /**
     * Said only to stop the speech, so never sent. The screen's own state words are here too: TalkBack reads them
     * aloud, and the recogniser must not send them.
     */
    private val hush = setOf(
        "stop", "wait", "pause", "hold on", "shh", "quiet", "be quiet", "stop talking", "okay stop", "ok stop", "hang on", "one moment", "one sec",
        "listening", "working", "speaking", "sending", "paused",
    )

    fun next(s: VoiceModeState, e: VoiceEvent): VoiceStep {
        if (!s.active && e !is VoiceEvent.Start) return VoiceStep(s)
        return when (e) {
            is VoiceEvent.Start -> if (s.active) VoiceStep(s) else start(e)
            VoiceEvent.End -> VoiceStep(VoiceModeState(muted = s.muted), listOf(VoiceAction.CancelListening, VoiceAction.StopSpeaking, VoiceAction.Release))
            VoiceEvent.TapMic -> tap(s)
            is VoiceEvent.Partial -> partial(s, e.text)
            is VoiceEvent.Heard -> heard(s, e.text)
            VoiceEvent.NoSpeech -> noSpeech(s)
            is VoiceEvent.Failed -> failed(s, e.message)
            VoiceEvent.RunStarted -> VoiceStep(s.copy(running = true, phase = if (s.phase == VoicePhase.Sending) VoicePhase.Working else s.phase))
            is VoiceEvent.RunEnded -> runEnded(s, e.reply)
            VoiceEvent.SendFailed -> if (s.phase == VoicePhase.Sending) VoiceStep(s.copy(phase = VoicePhase.Idle, running = false, notice = NOT_SENT)) else VoiceStep(s)
            VoiceEvent.SpeechDone -> speechDone(s)
            VoiceEvent.ToggleMute -> mute(s)
            is VoiceEvent.Pause -> pause(s, e.reason)
            VoiceEvent.Resume -> resume(s)
            is VoiceEvent.Ask -> ask(s, e)
            is VoiceEvent.OtherDialog -> say(s, "Pi needs your answer on screen: " + sentence(e.title.ifBlank { "a question" }))
            VoiceEvent.DialogGone -> dropQuestion(s)
            VoiceEvent.ShowCard -> dropQuestion(s)
        }
    }

    private fun start(e: VoiceEvent.Start): VoiceStep {
        val s = VoiceModeState(phase = if (e.running) VoicePhase.Working else VoicePhase.Listening, running = e.running, bargeIn = e.bargeIn, muted = e.muted)
        return VoiceStep(s, if (e.running) emptyList() else listOf(VoiceAction.Listen))
    }

    /** Where voice mode rests when nothing is said or heard: with Pi at work, or waiting for a tap. */
    private fun rest(s: VoiceModeState): VoicePhase = if (s.running) VoicePhase.Working else VoicePhase.Idle

    private fun listen(s: VoiceModeState, vararg before: VoiceAction): VoiceStep =
        VoiceStep(s.copy(phase = VoicePhase.Listening, stopping = false, notice = null), before.toList() + VoiceAction.Listen)

    private fun tap(s: VoiceModeState): VoiceStep {
        val t = if (s.paused) s.copy(paused = false, notice = null) else s
        return when (t.phase) {
            VoicePhase.Listening -> if (t.stopping) VoiceStep(t) else VoiceStep(t.copy(stopping = true), listOf(VoiceAction.StopListening))
            // Interrupt the speech and talk.
            VoicePhase.Speaking, VoicePhase.Asking -> listen(t.copy(heard = ""), VoiceAction.StopSpeaking)
            else -> listen(t.copy(heard = "", silences = 0))
        }
    }

    private fun partial(s: VoiceModeState, text: String): VoiceStep = when {
        s.phase == VoicePhase.Listening -> VoiceStep(s.copy(heard = text))
        speakingOver(s, text) -> VoiceStep(s.copy(phase = VoicePhase.Listening, heard = text), listOf(VoiceAction.StopSpeaking))
        else -> VoiceStep(s)
    }

    /** While it speaks with barge-in on: [text] is the user, not the phone hearing itself. */
    private fun speakingOver(s: VoiceModeState, text: String): Boolean =
        s.bargeIn && (s.phase == VoicePhase.Speaking || s.phase == VoicePhase.Asking) && BargeIn.isUser(text, s.spoken)

    private fun heard(s: VoiceModeState, text: String): VoiceStep = when {
        s.phase == VoicePhase.Listening -> heardListening(s, text)
        speakingOver(s, text) -> heardListening(s.copy(phase = VoicePhase.Listening), text).let { it.copy(actions = listOf(VoiceAction.StopSpeaking) + it.actions) }
        // Its own voice: keep listening while it speaks.
        s.bargeIn && (s.phase == VoicePhase.Speaking || s.phase == VoicePhase.Asking) -> VoiceStep(s, listOf(VoiceAction.Listen))
        else -> VoiceStep(s)
    }

    private fun heardListening(s: VoiceModeState, text: String): VoiceStep {
        val t = text.trim()
        if (t.isEmpty()) return noSpeech(s)
        val base = s.copy(heard = t, silences = 0, stopping = false, notice = null)
        if (base.question != null) return answer(base, t)
        // "Stop", "wait": the user only wanted the speech to stop. Nothing is sent.
        if (isHush(t)) return if (base.running) VoiceStep(base.copy(phase = VoicePhase.Working)) else listen(base)
        return VoiceStep(base.copy(phase = if (base.running) VoicePhase.Working else VoicePhase.Sending, pendingReply = null), listOf(VoiceAction.Send(t)))
    }

    fun isHush(text: String): Boolean = text.lowercase(Locale.ROOT).replace(Regex("""[^\p{L}\s]"""), " ").replace(Regex("""\s+"""), " ").trim() in hush

    private fun noSpeech(s: VoiceModeState): VoiceStep {
        if (s.phase != VoicePhase.Listening) {
            // The barge-in listen ended while speaking: listen again until the speech ends.
            return if (s.bargeIn && (s.phase == VoicePhase.Speaking || s.phase == VoicePhase.Asking)) VoiceStep(s, listOf(VoiceAction.Listen)) else VoiceStep(s)
        }
        val pending = s.pendingReply
        return when {
            s.question != null -> miss(s.copy(stopping = false))
            pending != null -> speakReply(s.copy(pendingReply = null, stopping = false), pending)
            s.stopping || s.running -> VoiceStep(s.copy(phase = rest(s), stopping = false))
            s.silences + 1 >= MAX_SILENCES -> VoiceStep(s.copy(phase = VoicePhase.Idle, silences = 0))
            else -> VoiceStep(s.copy(silences = s.silences + 1), listOf(VoiceAction.Listen))
        }
    }

    private fun failed(s: VoiceModeState, message: String): VoiceStep = when {
        s.phase != VoicePhase.Listening -> VoiceStep(s)
        s.question != null -> VoiceStep(s.copy(phase = rest(s), question = null, handled = s.handled + s.question.toolCallId, notice = message, stopping = false))
        else -> VoiceStep(s.copy(phase = rest(s), notice = message, stopping = false))
    }

    /** Says [text] and then listens (or, while Pi works, waits); muted, it is only shown. */
    private fun speakReply(s: VoiceModeState, text: String): VoiceStep = when {
        text.isBlank() -> if (s.running) VoiceStep(s.copy(phase = VoicePhase.Working)) else listen(s)
        s.paused -> VoiceStep(s.copy(phase = if (s.running) VoicePhase.Working else VoicePhase.Idle, pendingReply = text, spoken = text))
        s.muted -> if (s.running) VoiceStep(s.copy(phase = VoicePhase.Working, spoken = text)) else listen(s.copy(spoken = text, heard = ""))
        else -> VoiceStep(s.copy(phase = VoicePhase.Speaking, spoken = text), speak(s, text))
    }

    private fun speak(s: VoiceModeState, text: String): List<VoiceAction> =
        if (s.bargeIn) listOf(VoiceAction.Speak(text), VoiceAction.Listen) else listOf(VoiceAction.CancelListening, VoiceAction.Speak(text))

    /** A line said between turns (another dialog): after it, back to work. */
    private fun say(s: VoiceModeState, text: String): VoiceStep = when {
        s.paused || s.muted -> VoiceStep(s.copy(spoken = text))
        else -> VoiceStep(s.copy(phase = VoicePhase.Speaking, spoken = text, stopping = false), speak(s, text))
    }

    private fun runEnded(s: VoiceModeState, reply: String): VoiceStep {
        val ended = s.question
        val t = s.copy(running = false, question = null, handled = if (ended != null) s.handled + ended.toolCallId else s.handled)
        return when (s.phase) {
            // The user is talking: the reply waits until they are done.
            VoicePhase.Listening -> VoiceStep(t.copy(pendingReply = reply.ifBlank { null }))
            // A line is being said: the reply follows it.
            VoicePhase.Speaking -> VoiceStep(t.copy(pendingReply = reply.ifBlank { null }))
            VoicePhase.Asking -> speakReply(t, reply).let { it.copy(actions = listOf(VoiceAction.StopSpeaking) + it.actions) }
            else -> speakReply(t, reply)
        }
    }

    private fun speechDone(s: VoiceModeState): VoiceStep {
        val pending = s.pendingReply
        return when (s.phase) {
            VoicePhase.Asking -> listen(s.copy(heard = ""))
            VoicePhase.Speaking -> when {
                pending != null -> speakReply(s.copy(pendingReply = null), pending)
                s.running -> VoiceStep(s.copy(phase = VoicePhase.Working), if (s.bargeIn) listOf(VoiceAction.CancelListening) else emptyList())
                else -> listen(s.copy(heard = ""))
            }
            else -> VoiceStep(s)
        }
    }

    private fun mute(s: VoiceModeState): VoiceStep {
        val t = s.copy(muted = !s.muted)
        if (!t.muted) return VoiceStep(t)
        return when (s.phase) {
            VoicePhase.Speaking -> if (t.running) VoiceStep(t.copy(phase = VoicePhase.Working, pendingReply = null), listOf(VoiceAction.StopSpeaking, VoiceAction.CancelListening)) else listen(t, VoiceAction.StopSpeaking)
            VoicePhase.Asking -> listen(t, VoiceAction.StopSpeaking)
            else -> VoiceStep(t)
        }
    }

    private fun pause(s: VoiceModeState, reason: String): VoiceStep {
        if (s.paused) return VoiceStep(s.copy(notice = reason))
        val t = s.copy(paused = true, notice = reason, stopping = false)
        return when (s.phase) {
            VoicePhase.Listening -> VoiceStep(t.copy(phase = rest(s)), listOf(VoiceAction.CancelListening))
            // Said again when the audio comes back.
            VoicePhase.Speaking -> VoiceStep(t.copy(phase = rest(s), pendingReply = s.pendingReply ?: s.spoken), listOf(VoiceAction.StopSpeaking, VoiceAction.CancelListening))
            VoicePhase.Asking -> VoiceStep(t.copy(phase = rest(s)), listOf(VoiceAction.StopSpeaking, VoiceAction.CancelListening))
            else -> VoiceStep(t)
        }
    }

    private fun resume(s: VoiceModeState): VoiceStep {
        if (!s.paused) return VoiceStep(s)
        val t = s.copy(paused = false, notice = null)
        val pending = t.pendingReply
        return when {
            t.question != null && (t.phase == VoicePhase.Idle || t.phase == VoicePhase.Working) -> askQuestion(t)
            pending != null -> speakReply(t.copy(pendingReply = null), pending)
            t.phase == VoicePhase.Idle && !t.running -> listen(t)
            else -> VoiceStep(t)
        }
    }

    private fun ask(s: VoiceModeState, e: VoiceEvent.Ask): VoiceStep {
        if (e.toolCallId in s.handled || s.question?.toolCallId == e.toolCallId || e.questions.isEmpty()) return VoiceStep(s)
        val first = e.first.coerceIn(0, e.questions.lastIndex)
        val t = s.copy(question = VoiceQuestion(e.toolCallId, e.questions, first, List(first) { AskAnswer() }), heard = "", stopping = false)
        val step = askQuestion(t)
        // The user was talking (a steer): that is dropped for the question.
        return if (s.phase == VoicePhase.Listening) step.copy(actions = listOf(VoiceAction.CancelListening) + step.actions) else step
    }

    /** Reads the current question out, then listens for the answer. */
    private fun askQuestion(s: VoiceModeState): VoiceStep {
        val q = s.question ?: return VoiceStep(s)
        val text = SpokenAnswers.prompt(q.current, q.index, q.questions.size)
        return when {
            s.paused -> VoiceStep(s.copy(phase = VoicePhase.Idle, spoken = text))
            s.muted -> listen(s.copy(spoken = text, heard = ""))
            else -> VoiceStep(s.copy(phase = VoicePhase.Asking, spoken = text, heard = ""), speak(s, text))
        }
    }

    private fun answer(s: VoiceModeState, said: String): VoiceStep {
        val q = s.question ?: return VoiceStep(s)
        val current = q.current
        val answer = when (val m = SpokenAnswers.match(said, current.options.map { it.label }, current.multiSelect)) {
            SpokenAnswer.Cancel -> return submit(s, q, q.answers, cancel = true)
            SpokenAnswer.Unclear -> return miss(s)
            is SpokenAnswer.Picked -> AskAnswer(picked = m.options)
            is SpokenAnswer.Typed -> AskAnswer(text = m.text)
        }
        val answers = q.answers.take(q.index) + List((q.index - q.answers.size).coerceAtLeast(0)) { AskAnswer() } + answer
        if (q.index + 1 < q.questions.size) return askQuestion(s.copy(question = q.copy(index = q.index + 1, answers = answers, misses = 0)))
        return submit(s, q, answers, cancel = false)
    }

    private fun submit(s: VoiceModeState, q: VoiceQuestion, answers: List<AskAnswer>, cancel: Boolean): VoiceStep = VoiceStep(
        s.copy(phase = rest(s), question = null, handled = s.handled + q.toolCallId, spoken = if (cancel) "Declined." else "Answered."),
        listOf(VoiceAction.Submit(AskSubmission(q.toolCallId, q.questions, answers, cancel = cancel))),
    )

    private fun miss(s: VoiceModeState): VoiceStep {
        val q = s.question ?: return VoiceStep(s)
        if (q.misses + 1 >= MAX_MISSES) {
            // The card on screen takes over.
            val t = s.copy(question = null, handled = s.handled + q.toolCallId)
            return if (t.muted) VoiceStep(t.copy(phase = rest(t), spoken = SpokenAnswers.ON_CARD)) else VoiceStep(t.copy(phase = VoicePhase.Speaking, spoken = SpokenAnswers.ON_CARD), speak(t, SpokenAnswers.ON_CARD))
        }
        val t = s.copy(question = q.copy(misses = q.misses + 1))
        val text = SpokenAnswers.retry(q.current)
        return if (t.muted) listen(t.copy(spoken = text)) else VoiceStep(t.copy(phase = VoicePhase.Asking, spoken = text), speak(t, text))
    }

    private fun dropQuestion(s: VoiceModeState): VoiceStep {
        val q = s.question ?: return VoiceStep(s)
        val t = s.copy(question = null, handled = s.handled + q.toolCallId)
        return if (s.phase == VoicePhase.Asking || s.phase == VoicePhase.Listening) {
            VoiceStep(t.copy(phase = rest(t), stopping = false), listOf(VoiceAction.StopSpeaking, VoiceAction.CancelListening))
        } else {
            VoiceStep(t)
        }
    }

    private fun sentence(text: String): String = text.trim().let { if (it.last() in ".!?…") it else "$it." }
}

/**
 * Barge-in (pure, unit-tested): while the phone speaks it also listens, and hears itself as well as the user. Words it
 * heard count as the user only when at least two of them are not in what it is saying, or when it is one short
 * interjection ("stop", "wait") that the speech does not contain.
 */
object BargeIn {
    private val interjections = setOf("stop", "wait", "pause", "hold", "hey", "no", "cancel", "sorry", "shh")

    private fun words(text: String): List<String> =
        text.lowercase(Locale.ROOT).replace(Regex("""[^\p{L}\p{N}\s]"""), " ").split(Regex("""\s+""")).filter { it.isNotEmpty() }

    fun isUser(heard: String, spoken: String): Boolean {
        val said = words(heard)
        if (said.isEmpty()) return false
        val own = words(spoken).toSet()
        val novel = said.filter { it !in own }
        return novel.size >= 2 || (said.size == 1 && novel.size == 1 && novel[0] in interjections)
    }
}
