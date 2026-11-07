package opensec.pibolt.app.background

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock

/**
 * The CPU lock [WakePolicy] asks for. Main thread only (SessionService). A partial wake lock of a process in the
 * foreground-service state is honoured in Doze (PowerManagerService disables app wake locks there only above
 * PROCESS_STATE_BOUND_FOREGROUND_SERVICE), so the engine processes keep the CPU while the screen is off.
 */
class AgentLocks(context: Context) {
    private val wake: PowerManager.WakeLock =
        context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PiBolt:agent").apply { setReferenceCounted(false) }

    private var acquiredAt: Long? = null
    private var progressAt = 0L
    private var idleSince: Long? = null

    val held: Boolean get() = wake.isHeld

    /** A working session published a new state. */
    fun progress() {
        progressAt = SystemClock.elapsedRealtime()
    }

    /** Applies the policy for [working]. Returns when to call again (ms), or null when nothing is pending. */
    fun update(working: Boolean): Long? {
        val now = SystemClock.elapsedRealtime()
        idleSince = if (working) null else idleSince ?: now
        return when (val d = WakePolicy.decide(working, now, acquiredAt, wake.isHeld, progressAt, idleSince)) {
            WakePolicy.Decision.Acquire -> {
                // A held non-counted lock takes the new timeout in place of the old one.
                wake.acquire(WakePolicy.CAP_MS)
                acquiredAt = now
                WakePolicy.REFRESH_MS
            }
            WakePolicy.Decision.Keep -> if (working) WakePolicy.REFRESH_MS else null
            WakePolicy.Decision.Release -> {
                release()
                null
            }
            is WakePolicy.Decision.ReleaseIn -> d.ms
        }
    }

    fun release() {
        acquiredAt = null
        idleSince = null
        if (wake.isHeld) runCatching { wake.release() }
    }

    /** Releases after [WakePolicy.LINGER_MS] by the lock's own timeout: for the service stopping (nothing ticks then). */
    fun releaseSoon() {
        acquiredAt = null
        idleSince = null
        if (wake.isHeld) runCatching { wake.acquire(WakePolicy.LINGER_MS) }
    }
}
