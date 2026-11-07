package opensec.pibolt.app.ui.session.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * One SpeechRecognizer at a time, for dictation ([VoiceInput]) and voice mode ([VoiceMode]): created on the main thread
 * when listening starts and destroyed when it ends. It prefers the phone's own recogniser (Android 12+,
 * `createOnDeviceSpeechRecognizer`), else the default service with EXTRA_PREFER_OFFLINE; when the phone's own one lacks
 * the language, it moves to the default service for good. Callbacks of a recogniser that
 * was replaced or released are dropped, so a late result never reaches the next listen.
 */
internal class VoiceRecognizer {
    /** What one listen reports; all on the main thread. */
    interface Listener {
        fun onReady() {}

        fun onLevel(level: Float) {}

        fun onPartial(text: String) {}

        /** The final words, or null when nothing was recognised (silence, no match, stopped). */
        fun onFinal(text: String?)

        /** A real failure (not "nothing heard"): its plain message. */
        fun onError(message: String)
    }

    private var recognizer: SpeechRecognizer? = null
    private var generation = 0

    /** The on-device recogniser could not serve the phone's language: the default service is used from then on. */
    private var deviceUnusable = false

    /** The last listen ran on the phone (true), through the default speech service (false), or never ran (null). */
    var onDevice: Boolean? = null
        private set

    val active: Boolean get() = recognizer != null

    /** Starts listening; false when no recogniser could be created or started (the listener is told why). */
    fun start(context: Context, listener: Listener): Boolean {
        release()
        val gen = ++generation
        val local = !deviceUnusable && Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        val r = runCatching { if (local) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        if (r == null) {
            listener.onError("Speech recognition is not available")
            return false
        }
        recognizer = r
        onDevice = local
        fun live() = gen == generation
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (live()) listener.onReady()
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) {
                if (live()) listener.onLevel(VoiceLevel.normalize(rmsdB))
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                if (!live()) return
                release()
                if (local && error in DEVICE_FALLBACK) {
                    // The language pack is not on the phone: listen again through the default service.
                    deviceUnusable = true
                    start(context, listener)
                    return
                }
                val message = VoiceErrors.message(error)
                if (message == null) listener.onFinal(null) else listener.onError(message)
            }

            override fun onResults(results: Bundle?) {
                if (!live()) return
                release()
                listener.onFinal(best(results))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (live()) best(partialResults)?.let(listener::onPartial)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        return runCatching { r.startListening(intent) }.fold(
            onSuccess = { true },
            onFailure = {
                release()
                listener.onError("Could not start listening")
                false
            },
        )
    }

    /** Ends listening; the recogniser still delivers its final result. */
    fun stop() {
        recognizer?.stopListening()
    }

    /** Ends listening and drops whatever it heard: no callback follows. */
    fun release() {
        generation++
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = null
    }

    private companion object {
        /** On-device errors the default service may not have. */
        val DEVICE_FALLBACK = setOf(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_SERVER_DISCONNECTED)
    }

    private fun best(results: Bundle?): String? = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
}
