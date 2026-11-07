package opensec.pibolt.app.session

import opensec.pibolt.app.engine.BackgroundJobs
import opensec.pibolt.protocol.session.SessionState

/**
 * When a session needs [SessionService], the foreground service that keeps the app's network with the app away. Pure,
 * for [SessionController] and tests.
 *
 * Problem: from Android 15 an app loses its network about 5 seconds after its last activity stops, unless it runs a
 * foreground service; Doze and app standby cut it on any version. Android also refuses to start that service once the
 * app is in the background. So every kind of work must start the service, and as early as the user's tap:
 * - a manual `/compact` or a `!` command is no turn: started only on a turn, their model request or download failed
 *   once the user switched apps;
 * - a prompt's turn starts with the engine's `agent_start`, which comes seconds after the tap when the engine is busy
 *   (an extension's input handler, a token refresh): started only then, the start was refused if the user had left.
 */
object KeepAlive {
    /** Status key of the bundled subagents extension, set while background agents run (SessionController). */
    const val SUBAGENTS_STATUS = "subagents"

    /** Work in [state] that keeps the service: what [RunningSummary.Entry.busy] counts, besides `!` commands. */
    fun busy(state: SessionState): Boolean =
        state.isRunning || state.isCompacting || state.dialogs.isNotEmpty() || subagents(state) || BackgroundJobs.STATUS_KEY in state.statuses

    /** Background subagents run or wait (the extension's status, or a background run still active). */
    fun subagents(state: SessionState): Boolean = SUBAGENTS_STATUS in state.statuses || state.subagents.values.any { it.background && it.isActive }

    /** Work began with this state: start the service (a no-op when it runs). */
    fun starts(previous: SessionState, now: SessionState): Boolean = busy(now) && !busy(previous)

    /**
     * After the engine accepted a prompt ([disposition], Pi's `prompt` response): the hold taken at the tap stays until
     * the turn shows ([SessionState.isRunning]) when the prompt started one, so the service sees no gap between them.
     */
    fun waitsForTurn(disposition: String?, running: Boolean): Boolean = disposition == "started" && !running
}
