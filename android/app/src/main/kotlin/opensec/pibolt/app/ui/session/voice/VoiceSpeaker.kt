package opensec.pibolt.app.ui.session.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * Android's TextToSpeech for voice mode and the voice picker: one engine, started on first use. [speak] replaces what is
 * being said; [onDone] follows the end (or failure) of the current utterance only, so a [stop] never reports one.
 * [onWord] fires at each word the engine starts, where the engine reports it (`onRangeStart`), for the waveform.
 * Callbacks run on the main thread. Speech goes out as an assistant voice (USAGE_ASSISTANT).
 */
internal class VoiceSpeaker(context: Context, private val onDone: () -> Unit = {}, private val onWord: () -> Unit = {}) {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var failed = false
    private var current: String? = null
    private var seq = 0
    private var pending: (() -> Unit)? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        main.post {
            ready = status == TextToSpeech.SUCCESS
            failed = !ready
            val run = pending
            pending = null
            if (ready) configure()
            // Waiting work runs either way: a speak that cannot happen reports its end.
            run?.invoke()
        }
    }

    /** Null until the engine has started; then whether it works. */
    val available: Boolean? get() = if (ready) true else if (failed) false else null

    private fun configure() {
        runCatching { tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()) }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) = finished(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId)

            override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)

            override fun onStop(utteranceId: String?, interrupted: Boolean) = Unit

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                main.post { if (utteranceId == current) onWord() }
            }
        })
    }

    private fun finished(id: String?) {
        main.post {
            if (id != null && id == current) {
                current = null
                onDone()
            }
        }
    }

    /** Says [text] at [ratePercent] (100 is normal) in the voice named [voiceName] (null: the engine's default). */
    fun speak(text: String, ratePercent: Int, voiceName: String?) {
        val id = "pi-voice-${++seq}"
        current = id
        val run = {
            if (!ready) {
                finished(id)
            } else {
                pick(voiceName)
                tts.setSpeechRate(ratePercent / 100f)
                if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) finished(id)
            }
        }
        when {
            ready -> run()
            failed -> finished(id)
            else -> {
                val before = pending
                pending = {
                    before?.invoke()
                    run()
                }
            }
        }
    }

    private fun pick(name: String?) {
        val voice = name?.let { n -> runCatching { tts.voices?.firstOrNull { it.name == n } }.getOrNull() }
        runCatching {
            if (voice != null) {
                if (tts.voice?.name != voice.name) tts.voice = voice
            } else {
                tts.defaultVoice?.let { if (tts.voice?.name != it.name) tts.voice = it }
            }
        }
    }

    /** Stops speaking; no [onDone] follows for what was cut off. */
    fun stop() {
        current = null
        pending = null
        if (ready) runCatching { tts.stop() }
    }

    val speaking: Boolean get() = current != null

    /** The installed voices for the phone's language (all of them when none matches), once the engine has started. */
    fun voices(): List<TtsVoice> {
        if (!ready) return emptyList()
        val all = runCatching { tts.voices?.toList() }.getOrNull().orEmpty().map { it.info() }
        return TtsVoices.pick(all, Locale.getDefault())
    }

    /** Runs [then] once the engine has started (at once when it has). */
    fun whenReady(then: () -> Unit) {
        if (ready || failed) then() else {
            val before = pending
            pending = {
                before?.invoke()
                then()
            }
        }
    }

    fun shutdown() {
        stop()
        runCatching { tts.shutdown() }
    }

    private fun Voice.info() = TtsVoice(
        name = name,
        language = locale.language,
        localeTag = locale.toLanguageTag(),
        localeName = locale.getDisplayName(Locale.getDefault()),
        quality = quality,
        network = isNetworkConnectionRequired,
        installed = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in features.orEmpty(),
    )
}

/** One engine voice, as the picker shows it. */
data class TtsVoice(
    val name: String,
    val language: String,
    val localeTag: String,
    val localeName: String,
    val quality: Int,
    val network: Boolean,
    val installed: Boolean,
)

/** Which voices the picker offers and how it names them (pure, unit-tested). */
object TtsVoices {
    /**
     * The installed voices in [locale]'s language (every installed voice when none is), on-device ones first, then by
     * quality (best first), locale and name.
     */
    fun pick(voices: List<TtsVoice>, locale: Locale): List<TtsVoice> {
        val installed = voices.filter { it.installed }
        val mine = installed.filter { it.language == locale.language }.ifEmpty { installed }
        return mine.sortedWith(compareBy<TtsVoice> { it.network }.thenByDescending { it.quality }.thenBy { it.localeTag }.thenBy { it.name })
    }

    /**
     * Readable names: engine names ("en-us-x-iol-local") mean nothing, so a voice is its locale ("English (United
     * States)") and, when the locale has several, a number in list order ("English (United States) 2").
     */
    fun labels(voices: List<TtsVoice>): Map<String, String> {
        val perLocale = voices.groupingBy { it.localeName }.eachCount()
        val seen = HashMap<String, Int>()
        return voices.associate { v ->
            val n = (seen[v.localeName] ?: 0) + 1
            seen[v.localeName] = n
            v.name to if ((perLocale[v.localeName] ?: 0) > 1) "${v.localeName} $n" else v.localeName
        }
    }
}
