package opensec.pibolt.app.background

/**
 * When the CPU must stay awake for the agent with the screen off. Pure, for [opensec.pibolt.app.session.SessionService]
 * and tests.
 *
 * A partial wake lock is held while at least one session on this phone works: a turn streams or runs tools (and is not
 * waiting for the user), a compaction runs, background subagents run, or a `!` command runs. A session waiting for an
 * answer, an idle one and any remote session (the work runs on the computer) need none.
 *
 * Problem: a state that never settles (an engine hung mid-turn) would hold the lock until the battery is empty.
 * Each acquire is therefore timed ([CAP_MS]), and it is renewed only after the session showed progress (a new state)
 * since the last acquire. A hung session loses the lock [CAP_MS] after its last progress at the latest.
 *
 * No Wi-Fi lock: Wi-Fi power save keeps the connection and only adds latency, which a token stream tolerates. WIFI_MODE_FULL_HIGH_PERF works with the screen off only below API 34 (from
 * 34 it is WIFI_MODE_FULL_LOW_LATENCY, active only with the screen on and the app in front), and a lock without a
 * timeout would keep the radio out of power save after a hung session lost its CPU lock.
 */
object WakePolicy {
    /** The longest a lock lives without progress. */
    const val CAP_MS = 2 * 60 * 60 * 1000L

    /** How often a held lock is renewed while progress continues. */
    const val REFRESH_MS = 10 * 60 * 1000L

    /** Kept this long after the work ends: the engine writes the session file, the finished alert is posted. */
    const val LINGER_MS = 5_000L

    /** One live session as the policy sees it. */
    data class Work(
        val local: Boolean,
        val running: Boolean,
        val waiting: Boolean,
        val compacting: Boolean = false,
        val subagents: Boolean = false,
        val shell: Boolean = false,
    ) {
        /** Work that runs on this phone's CPU right now. */
        val active: Boolean get() = local && ((running && !waiting) || compacting || subagents || shell)
    }

    fun working(sessions: List<Work>): Boolean = sessions.any { it.active }

    sealed interface Decision {
        /** (Re)acquire with a [CAP_MS] timeout. */
        data object Acquire : Decision

        /** Nothing to change. */
        data object Keep : Decision

        /** Release now. */
        data object Release : Decision

        /** Release in [ms] unless work starts again. */
        data class ReleaseIn(val ms: Long) : Decision
    }

    /**
     * [acquiredAt]: the last acquire of this stretch of work (null: none since the last release). [held]: the lock is
     * still held (false once its timeout passed). [progressAt]: the last new state of a working session. [idleSince]:
     * when the work ended (null while working). Times are elapsedRealtime.
     */
    fun decide(working: Boolean, now: Long, acquiredAt: Long?, held: Boolean, progressAt: Long, idleSince: Long?): Decision {
        if (working) {
            if (acquiredAt == null) return Decision.Acquire
            val progressed = progressAt > acquiredAt
            return if (progressed && (!held || now - acquiredAt >= REFRESH_MS)) Decision.Acquire else Decision.Keep
        }
        if (acquiredAt == null) return Decision.Keep
        val since = idleSince ?: now
        val left = since + LINGER_MS - now
        return if (left <= 0) Decision.Release else Decision.ReleaseIn(left)
    }
}
