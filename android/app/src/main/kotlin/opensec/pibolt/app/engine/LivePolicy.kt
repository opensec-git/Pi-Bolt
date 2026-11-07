package opensec.pibolt.app.engine

import opensec.pibolt.protocol.session.SessionState

/**
 * The rules for several on-device sessions at once ([LocalEngine]): at most [MAX_LIVE] live sessions (each with its own
 * engine process; the parked and spare engines of [IdlePool] come on top), and which ones give way. A busy session (a
 * turn, a compaction, an open dialog or background subagents) is never stopped for room or memory: idle ones go first,
 * the least recently used first, and the one on screen last.
 */
object LivePolicy {
    const val MAX_LIVE = 3

    /** A live session as these rules see it. [key] identifies it to the caller (its project). */
    data class Live(val key: String, val busy: Boolean, val onScreen: Boolean, val lastUsed: Long)

    sealed interface Room {
        /** Below the cap: open another. */
        data object Free : Room

        /** At the cap: stop [key] (idle) first. */
        data class Stop(val key: String) : Room

        /** At the cap and every session is busy: nothing may be stopped. */
        data class Full(val message: String) : Room
    }

    /** Room for one more live session (in a project that has none). */
    fun roomFor(live: List<Live>, max: Int = MAX_LIVE): Room {
        if (live.size < max) return Room.Free
        val idle = live.filterNot { it.busy }.sortedWith(compareBy<Live> { it.onScreen }.thenBy { it.lastUsed })
        return idle.firstOrNull()?.let { Room.Stop(it.key) }
            ?: Room.Full("$max sessions are working. Stop one or wait for one to finish.")
    }

    /**
     * Live sessions to stop under memory pressure ([IdlePool] engines are stopped before these). [critical]: also the
     * idle one on screen is kept, every other idle one goes; otherwise only idle sessions not used for [staleMs].
     */
    fun trim(live: List<Live>, critical: Boolean, now: Long, staleMs: Long = STALE_MS): List<String> =
        live.filter { !it.busy && !it.onScreen && (critical || now - it.lastUsed >= staleMs) }.sortedBy { it.lastUsed }.map { it.key }

    /** An idle live session unused this long gives its engine back on a moderate trim. */
    const val STALE_MS = 10 * 60_000L

    /**
     * Whether a live session restarts when a setting its engine read at start changes ("Web search"): every session not
     * in a turn (or a compaction). Not [opensec.pibolt.app.session.SessionController.isIdle]: a lasting extension
     * status, an open dialog or a reconnecting link does not keep the old tools (an extension command's dialog is
     * cancelled with the engine).
     */
    fun restartsForSettings(state: SessionState): Boolean = !state.isRunning && !state.isCompacting

    /**
     * An engine is stale when the setting it read at start ("Web search", [launchedWith]) differs from [current]: it is
     * then replaced, not reused, parked or handed a new session. An engine not known here (none recorded) is not.
     */
    fun staleEngine(launchedWith: Boolean?, current: Boolean): Boolean = launchedWith != null && launchedWith != current
}
