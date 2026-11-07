package opensec.pibolt.app.cost

import androidx.compose.runtime.Immutable

/** Settings > Agent > Spending: optional limits in dollars (null: none), and costs on list rows. All off by default. */
@Immutable
data class SpendLimits(
    val session: Double? = null,
    val day: Double? = null,
    val month: Double? = null,
    /** Show each saved session's and chat's cost in its row's meta line. */
    val showInLists: Boolean = false,
) {
    val any: Boolean get() = session != null || day != null || month != null

    fun of(kind: CapKind): Double? = when (kind) {
        CapKind.Session -> session
        CapKind.Day -> day
        CapKind.Month -> month
    }
}

/** Which limit. [title] starts the reached card, [period] names it in the warning. */
enum class CapKind(val title: String, val period: String) {
    Session("Session", "this session's"),
    Day("Daily", "today's"),
    Month("Monthly", "this month's"),
}

sealed interface CapStatus {
    data object Ok : CapStatus

    /** At least [SpendCaps.WARN_AT] of a limit is spent. */
    data class Near(val kind: CapKind, val limit: Double, val spent: Double) : CapStatus

    data class Reached(val kind: CapKind, val limit: Double, val spent: Double) : CapStatus
}

/** The limits against what was spent (unit-tested). */
object SpendCaps {
    const val WARN_AT = 0.8

    /**
     * Reached when what was spent meets a limit: the month first, then the day, then the session (raising the session's
     * limit does not help while the day's is spent). Otherwise Near for the limit closest to its end, from 80%.
     */
    fun check(limits: SpendLimits, session: Double, day: Double, month: Double): CapStatus {
        val spent = mapOf(CapKind.Session to session, CapKind.Day to day, CapKind.Month to month)
        for (kind in listOf(CapKind.Month, CapKind.Day, CapKind.Session)) {
            val limit = limits.of(kind) ?: continue
            val used = spent.getValue(kind)
            if (used >= limit - EPSILON) return CapStatus.Reached(kind, limit, used)
        }
        return CapKind.entries
            .mapNotNull { kind -> limits.of(kind)?.let { limit -> Triple(kind, limit, spent.getValue(kind)) } }
            .filter { (_, limit, used) -> used >= limit * WARN_AT }
            .maxByOrNull { (_, limit, used) -> used / limit }
            ?.let { (kind, limit, used) -> CapStatus.Near(kind, limit, used) }
            ?: CapStatus.Ok
    }

    /** "Daily limit reached ($5.00)." */
    fun reached(kind: CapKind, limit: Double): String = "${kind.title} limit reached (${Money.usd(limit)})."

    /** The card at a limit: "Daily limit reached ($5.00). Raise it in Settings or continue once." */
    fun card(kind: CapKind, limit: Double): String = reached(kind, limit) + " Raise it in Settings or continue once."

    /** The quiet warning: "80% of today's $5.00 limit used". */
    fun near(kind: CapKind, limit: Double, spent: Double): String {
        val percent = ((spent / limit) * 100).toInt().coerceIn(80, 99)
        return "$percent% of ${kind.period} ${Money.usd(limit)} limit used"
    }

    private const val EPSILON = 1e-9
}

/**
 * When a limit stops Pi (unit-tested; the session controller feeds it):
 * - a prompt is not sent while a limit is reached ([maySend]);
 * - a run that reaches one finishes its current step (the turn: the reply and its tool calls), then stops ([onTurnEnd]);
 * - a run that starts by itself while a limit is reached (a background agent's report) stops at once ([onAgentStart]);
 * - [continueOnce] lets the next run go to its end; it is used up when that run settles.
 */
class SpendGuard {
    /** Granted by [continueOnce]; taken by the next run. */
    private var allowance = false

    /** The run that took the allowance is going. */
    private var overriding = false

    @Synchronized
    fun continueOnce() {
        allowance = true
    }

    @Synchronized
    fun maySend(status: CapStatus): Boolean = status !is CapStatus.Reached || allowance || overriding

    /** A run starts. True: stop it now. */
    @Synchronized
    fun onAgentStart(status: CapStatus): Boolean {
        if (overriding) return false
        if (allowance) {
            allowance = false
            overriding = true
            return false
        }
        return status is CapStatus.Reached
    }

    /** A turn of the run ended. True: stop the run here. */
    @Synchronized
    fun onTurnEnd(status: CapStatus): Boolean = !overriding && status is CapStatus.Reached

    /** The run is over, with all of its retries and queued messages. */
    @Synchronized
    fun onSettled() {
        overriding = false
    }
}
