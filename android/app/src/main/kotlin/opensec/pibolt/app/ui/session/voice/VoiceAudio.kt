package opensec.pibolt.app.ui.session.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Voice mode's share of the audio. While it runs it holds transient audio focus as an assistant, so music pauses and
 * comes back after; when a call or another app takes the audio it pauses ([onPause]) and carries on when the audio
 * returns ([onResume]). Calls are also seen through the audio mode (polled each second), which needs no phone
 * permission. Focus the phone's own recogniser takes when a listen starts is not a pause ([FocusRules]).
 */
internal class VoiceAudio(context: Context, private val onPause: (String) -> Unit, private val onResume: () -> Unit) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var request: AudioFocusRequest? = null
    private var inCall = false
    private var listenAt = 0L

    val held: Boolean get() = request != null

    private val poll = object : Runnable {
        override fun run() {
            val call = FocusRules.inCall(audio?.mode ?: AudioManager.MODE_NORMAL)
            if (call != inCall) {
                inCall = call
                if (call) onPause(FocusRules.CALL) else onResume()
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    /** Voice mode starts listening: focus moves to the recogniser for a moment. */
    fun listening() {
        listenAt = SystemClock.elapsedRealtime()
    }

    fun acquire() {
        val am = audio ?: return
        val r = request ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({ change ->
                when (FocusRules.effect(change, SystemClock.elapsedRealtime() - listenAt)) {
                    FocusRules.Effect.Pause -> onPause(FocusRules.OTHER_AUDIO)
                    FocusRules.Effect.Resume -> if (!inCall) onResume()
                    FocusRules.Effect.Ignore -> Unit
                }
            }, main)
            .build()
        request = r
        // Asked again after a pause: another app may have taken the audio for good.
        runCatching { am.requestAudioFocus(r) }
        main.removeCallbacks(poll)
        main.post(poll)
    }

    fun release() {
        main.removeCallbacks(poll)
        inCall = false
        request?.let { r -> runCatching { audio?.abandonAudioFocusRequest(r) } }
        request = null
    }

    private companion object {
        const val POLL_MS = 1_000L
    }
}

/** What an audio focus change or audio mode means for voice mode (pure, unit-tested). */
object FocusRules {
    const val CALL = "Paused for a call"
    const val OTHER_AUDIO = "Paused while another app plays audio"
    const val BACKGROUND = "Paused while Pi-Bolt is in the background"

    /** A focus loss this soon after voice mode starts listening is the recogniser's own, not another app's. */
    const val OWN_LISTEN_MS = 1_500L

    enum class Effect { Pause, Resume, Ignore }

    fun effect(change: Int, sinceListenMs: Long): Effect = when (change) {
        AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (sinceListenMs in 0..OWN_LISTEN_MS) Effect.Ignore else Effect.Pause
        // A notification sound: it plays over us, briefly.
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Effect.Ignore
        AudioManager.AUDIOFOCUS_GAIN -> Effect.Resume
        else -> Effect.Ignore
    }

    /** The phone rings or is in a call (a carrier or a VoIP call). */
    fun inCall(mode: Int): Boolean = mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
}
