package opensec.pibolt.app.cost

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import opensec.pibolt.protocol.arr
import opensec.pibolt.protocol.obj
import opensec.pibolt.protocol.session.SessionState
import opensec.pibolt.protocol.session.SubagentReducer
import opensec.pibolt.protocol.str
import opensec.pibolt.protocol.type

/** The card a limit shows in the session: [stopped] when it stopped a run, else it held a prompt back. */
@Immutable
data class CapCard(val kind: CapKind, val limit: Double, val stopped: Boolean) {
    val text: String get() = SpendCaps.card(kind, limit)
}

/**
 * One live session's spending (unit-tested): its cost ([cost]: the session's own total, then each charge as it comes),
 * the charges it adds to the day and month ([SpendStore]), and its limits ([SpendGuard]). The session controller
 * feeds it every event before the reducer ([onEvent]) and acts on the [Reaction].
 *
 * Counted once: a charge's key is recorded for the controller's life, so a re-read subagent file, a replayed event
 * or a session switched away from and back adds nothing again; a snapshot's history (open, resume, fork, reconnect)
 * is never added to the day, only to the session's own total.
 */
class SessionSpending(
    private val store: SpendStore,
    private val limits: () -> SpendLimits,
    /** The session's scope for charge keys until a snapshot names its id (the controller's host id). */
    private val fallbackScope: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** What the session should do after an event. [snapshot]: read the session's own total now (it is idle or new). */
    data class Reaction(val abort: Boolean = false, val warning: String? = null, val snapshot: Boolean = false) {
        companion object {
            val NONE = Reaction()
        }
    }

    /** Subagent messages written before this point were not seen live (see [Charges.subagent]). */
    private val since = clock()
    private val tally = SessionTally()
    private val guard = SpendGuard()
    private val recorded = HashSet<String>()
    private var sessionId: String? = null

    private val _cost = MutableStateFlow(Cost.NONE)
    val cost: StateFlow<Cost> = _cost.asStateFlow()

    private val _card = MutableStateFlow<CapCard?>(null)
    val card: StateFlow<CapCard?> = _card.asStateFlow()

    fun status(): CapStatus {
        val l = limits()
        if (!l.any) return CapStatus.Ok
        return SpendCaps.check(l, tally.total.usd, store.today().usd, store.thisMonth().usd)
    }

    fun onEvent(before: SessionState, event: JsonObject): Reaction = when (event.type) {
        "remote_hello" -> hello(event)
        "message_end", "compaction_end" -> {
            val model = before.model?.let { Charges.model(it.provider, it.id) }
            Charges.live(event, scope(before), clock(), model)?.let { charged(listOf(it)) } ?: Reaction.NONE
        }
        SubagentReducer.TRANSCRIPT_EVENT -> charged(Charges.subagent(event, since, clock()))
        "agent_start" -> if (guard.onAgentStart(status())) stop() else Reaction.NONE
        "turn_end" -> if (before.isRunning && guard.onTurnEnd(status())) stop() else Reaction.NONE
        "agent_settled" -> {
            guard.onSettled()
            // Reached by the end of a run: say so before the next prompt is typed.
            val s = status()
            if (s is CapStatus.Reached && _card.value == null) _card.value = CapCard(s.kind, s.limit, stopped = false)
            Reaction(snapshot = true)
        }
        else -> Reaction.NONE
    }

    /** A prompt may go out. False: a limit is reached, and the card says so. */
    fun maySend(): Boolean {
        val s = status()
        if (guard.maySend(s)) {
            _card.value = null
            return true
        }
        val reached = s as CapStatus.Reached
        _card.value = CapCard(reached.kind, reached.limit, stopped = false)
        return false
    }

    /** "Continue once": the next run may go to its end. */
    fun continueOnce() {
        guard.continueOnce()
        _card.value = null
    }

    fun dismissCard() {
        _card.value = null
    }

    /** Before reading the session's own total: pass the mark to [rebase] with it. */
    fun mark(): SessionTally.Mark = tally.mark()

    fun rebase(total: Cost, mark: SessionTally.Mark) {
        if (tally.rebase(total, mark)) _cost.value = tally.total
    }

    private fun scope(state: SessionState): String = state.sessionId ?: sessionId ?: fallbackScope

    private fun hello(event: JsonObject): Reaction {
        val id = event.obj("state")?.str("sessionId")
        if (id != sessionId) {
            tally.reset()
            sessionId = id
            // A session limit's card belongs to the session it stopped.
            if (_card.value?.kind == CapKind.Session) _card.value = null
        }
        val messages = event.arr("messages").orEmpty().mapNotNull { it as? JsonObject }
        // The context's messages until the session's own total answers (it also has compacted and subagent spend).
        rebase(Charges.history(messages, id ?: fallbackScope, clock()), tally.mark())
        return Reaction(snapshot = true)
    }

    private fun charged(charges: List<Charge>): Reaction {
        if (charges.isEmpty()) return Reaction.NONE
        var changed = false
        for (charge in charges) {
            if (tally.add(charge)) changed = true
            if (recorded.add(charge.key)) store.record(charge)
        }
        if (changed) _cost.value = tally.total
        val s = status()
        val warning = (s as? CapStatus.Near)?.takeIf { store.warnOnce(it.kind, store.period(it.kind, sessionId)) }?.let { SpendCaps.near(it.kind, it.limit, it.spent) }
        return Reaction(warning = warning)
    }

    private fun stop(): Reaction {
        val s = status() as? CapStatus.Reached ?: return Reaction.NONE
        _card.value = CapCard(s.kind, s.limit, stopped = true)
        return Reaction(abort = true)
    }
}
