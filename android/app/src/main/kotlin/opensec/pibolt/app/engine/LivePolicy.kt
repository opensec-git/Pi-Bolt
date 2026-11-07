package opensec.pibolt.app.engine

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
            ?: Room.Full("$max sessions are working. Stop one, or wait until one finishes, to open another.")
    }

    /**
     * Live sessions to stop under memory pressure ([IdlePool] engines are stopped before these). [critical]: also the
     * idle one on screen is kept, every other idle one goes; otherwise only idle sessions not used for [staleMs].
     */
    fun trim(live: List<Live>, critical: Boolean, now: Long, staleMs: Long = STALE_MS): List<String> =
        live.filter { !it.busy && !it.onScreen && (critical || now - it.lastUsed >= staleMs) }.sortedBy { it.lastUsed }.map { it.key }

    /** An idle live session unused this long gives its engine back on a moderate trim. */
    const val STALE_MS = 10 * 60_000L
}
