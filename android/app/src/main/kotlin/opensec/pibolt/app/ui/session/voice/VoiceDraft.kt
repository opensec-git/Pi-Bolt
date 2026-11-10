package opensec.pibolt.app.ui.session.voice

import android.speech.SpeechRecognizer

/**
 * Live dictation into the composer (pure, unit-tested). When listening starts, the draft is split at the cursor (or
 * the selection, which the speech replaces); every partial result is shown between the two halves, spaced like typed
 * words, and the final result replaces the last partial. Nothing heard leaves the draft as it was.
 */
class VoiceDraft(text: String, selStart: Int, selEnd: Int = selStart) {
    private val before: String
    private val after: String

    init {
        val a = minOf(selStart, selEnd).coerceIn(0, text.length)
        val b = maxOf(selStart, selEnd).coerceIn(0, text.length)
        before = text.substring(0, a)
        after = text.substring(b)
    }

    /** The draft with [spoken] in place, and the cursor just after it. */
    fun with(spoken: String): Pair<String, Int> {
        val words = spoken.trim().replace(Regex("\\s+"), " ")
        if (words.isEmpty()) return before + after to before.length
        val lead = if (before.isEmpty() || before.last().isWhitespace()) "" else " "
        val trail = if (after.isEmpty() || after.first().isWhitespace() || after.first() in ".,;:!?)]}") "" else " "
        val head = before + lead + words
        return head + trail + after to head.length
    }
}

/** What the mic button shows. */
enum class VoiceState { Idle, Starting, Listening, Error }

/** Plain words for `SpeechRecognizer.ERROR_*` codes; null for the ones that only mean "nothing was heard". */
object VoiceErrors {
    fun message(code: Int): String? = when (code) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_NETWORK -> "No connection for speech recognition"
        SpeechRecognizer.ERROR_AUDIO -> "Could not record audio"
        SpeechRecognizer.ERROR_SERVER -> "The speech service had a problem"
        // Stopped by us, nothing said, nothing recognised: no message.
        SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH -> null
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is busy; try again"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many requests; try again shortly"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "The speech service disconnected"
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "This language is not available for speech"
        else -> "Speech recognition stopped"
    }
}

/** The microphone level for the voice surface's waveform (pure, unit-tested). */
object VoiceLevel {
    /** Quieter than this (`onRmsChanged` dB) is silence. */
    const val FLOOR_DB = -2f

    /** Louder than this is full scale: speech close to the phone peaks around 8 to 10 dB. */
    const val CEILING_DB = 10f

    /** `SpeechRecognizer`'s RMS dB as 0..1, linear between [FLOOR_DB] and [CEILING_DB]. */
    fun normalize(rmsdB: Float): Float {
        if (rmsdB.isNaN()) return 0f
        return ((rmsdB - FLOOR_DB) / (CEILING_DB - FLOOR_DB)).coerceIn(0f, 1f)
    }
}
