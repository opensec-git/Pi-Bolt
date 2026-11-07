package opensec.pibolt.app.engine

import opensec.pibolt.protocol.session.SessionState

/**
 * The rules for several on-device sessions at once ([LocalEngine]): at most [MAX_LIVE] live sessions (each with its own
 * engine process; the parked and spare engines of [IdlePool] come on top), several of them in one project if need be,
 * and which ones give way. A busy session (a turn, a compaction, an open dialog or background subagents) is never
 * stopped for room or memory, and never moved to another session ([planOpen], [maySwitch]): Pi's `switch_session` and
 * `new_session` abort the turn they replace. Idle ones go first, the least recently used first, and the one on screen
 * last.
 */
object LivePolicy {
    const val MAX_LIVE = 3

    /** A live session as these rules see it. [key] identifies it to the caller (its hostId: one per session). */
    data class Live(val key: String, val busy: Boolean, val onScreen: Boolean, val lastUsed: Long)

    sealed interface Room {
        /** Below the cap: open another. */
        data object Free : Room

        /** At the cap: stop [key] (idle) first. */
        data class Stop(val key: String) : Room

        /** At the cap and every session is busy: nothing may be stopped; the user decides ([message]). */
        data class Full(val message: String) : Room
    }

    /** Room for one more live session. */
    fun roomFor(live: List<Live>, max: Int = MAX_LIVE): Room {
        if (live.size < max) return Room.Free
        val idle = live.filterNot { it.busy }.sortedWith(compareBy<Live> { it.onScreen }.thenBy { it.lastUsed })
        return idle.firstOrNull()?.let { Room.Stop(it.key) } ?: Room.Full(fullMessage(live.size))
    }

    /** What the user reads when every live session is busy. */
    fun fullMessage(running: Int): String = "$running sessions are running. Stop one, or wait."

    /** Why a busy session refuses to move to another session ([maySwitch]). */
    const val BUSY_SWITCH = "A task is running in this session. Wait for it, or stop it first."

    /**
     * Whether a session may be sent `switch_session`, `new_session`, `fork` or `clone`: these replace its runtime and
     * abort a turn in progress, so never while it is [busy]. Another session then opens beside it ([planOpen]).
     */
    fun maySwitch(busy: Boolean): Boolean = !busy

    /**
     * Why a session refuses `switch_session`, `new_session`, `fork` or `clone` now, or null to send it. A busy session
     * on this phone ([local]) refuses: [LocalEngine] opens the other session beside it instead. A computer whose service
     * keeps one runtime per session (`host.parallelSessions`, [hostParallel]) takes the command at any time: the switch
     * only changes which session the phone views. An older service has one runtime, which the switch would abort.
     */
    fun switchRefusal(local: Boolean, busy: Boolean, hostParallel: Boolean): String? =
        if (maySwitch(busy) || (!local && hostParallel)) null else BUSY_SWITCH

    /** A live session of the project being opened, as [planOpen] sees it. [key] identifies it (its hostId). */
    data class Here(val key: String, val sessionFile: String?, val busy: Boolean, val lastUsed: Long)

    /** What an open asks for. */
    sealed interface Want {
        /** A saved session. */
        data class Session(val file: String) : Want

        /** The project's latest: its live session if it has one. */
        data object Latest : Want

        /** A new session. */
        data object New : Want
    }

    sealed interface Plan {
        /** [key] already shows what was asked for: show it. */
        data class Show(val key: String) : Plan

        /** [key] is idle: it moves to the wanted session (over RPC, or a parked engine takes its place). */
        data class Move(val key: String) : Plan

        /** Every live session here is busy (or there is none): another one opens with its own engine. */
        data object Add : Plan
    }

    /**
     * How an open in a project uses the project's live sessions [here]: the one that already has the session, else the
     * most recently used idle one, else a new live session (room permitting, [roomFor]). A busy session is never moved,
     * so a turn in progress keeps running while the user opens or starts another session in the same project.
     */
    fun planOpen(here: List<Here>, want: Want): Plan {
        when (want) {
            is Want.Session -> here.firstOrNull { it.sessionFile == want.file }?.let { return Plan.Show(it.key) }
            Want.Latest -> here.maxByOrNull { it.lastUsed }?.let { return Plan.Show(it.key) }
            Want.New -> Unit
        }
        return here.filter { maySwitch(it.busy) }.maxByOrNull { it.lastUsed }?.let { Plan.Move(it.key) } ?: Plan.Add
    }

    /** The smallest slot (0, 1, ...) not in [used]: a session's place among its project's live sessions (its hostId). */
    fun freeSlot(used: Collection<Int>): Int = generateSequence(0) { it + 1 }.first { it !in used }

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
