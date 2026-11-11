package opensec.pibolt.app.cost

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonObject
import opensec.pibolt.protocol.arr
import opensec.pibolt.protocol.double
import opensec.pibolt.protocol.long
import opensec.pibolt.protocol.obj
import opensec.pibolt.protocol.str
import opensec.pibolt.protocol.type
import java.util.Locale

/**
 * What some model use cost: [usd] over [charges] usage records (assistant messages, summaries). A record whose model
 * has no price (a custom endpoint, a free model) counts as a charge of $0, so "used, but nothing priced" shows "—".
 */
@Immutable
data class Cost(val usd: Double = 0.0, val charges: Int = 0) {
    operator fun plus(other: Cost): Cost = Cost(usd + other.usd, charges + other.charges)

    /** Something was used and none of it had a price. */
    val unpriced: Boolean get() = charges > 0 && usd <= 0.0

    companion object {
        val NONE = Cost()
    }
}

/**
 * One usage record as it happened. [key] identifies it (the same message read twice has the same key), [at] is when it
 * was spent (epoch ms) and decides its day, [model] is "provider/model" when known.
 */
data class Charge(val key: String, val usd: Double, val model: String?, val at: Long) {
    val cost: Cost get() = Cost(usd, 1)
}

/** Dollar amounts as the app shows them: tabular, two decimals. */
object Money {
    /** "$0.42", "$12.30", "$1,204.00"; a positive amount under a cent is "<$0.01". */
    fun usd(value: Double): String = when {
        value > 0.0 && value < 0.005 -> "<$0.01"
        else -> String.format(Locale.ROOT, "$%,.2f", value.coerceAtLeast(0.0))
    }

    /** A cost's label: null when nothing was used, "—" when nothing used had a price, else [usd]. */
    fun label(cost: Cost): String? = when {
        cost.charges == 0 -> null
        cost.unpriced -> NO_PRICE
        else -> usd(cost.usd)
    }

    /** [label] for TalkBack: "42 cents", "12 dollars 30", "no price". */
    fun spoken(cost: Cost): String? = when {
        cost.charges == 0 -> null
        cost.unpriced -> "no price"
        else -> usd(cost.usd).replace("<", "under ")
    }

    /** A limit typed by the user ("5", "$5.00", "2,5"): positive and finite, else null (no limit). */
    fun parse(text: String): Double? {
        val clean = text.trim().removePrefix("$").trim().replace(',', '.')
        if (clean.isEmpty()) return null
        val value = clean.toDoubleOrNull() ?: return null
        return value.takeIf { it.isFinite() && it > 0.0 }?.let { Math.round(it * 100) / 100.0 }?.takeIf { it > 0.0 }
    }

    /** A limit in its field: "5" for 5.0, "2.50" for 2.5. */
    fun field(value: Double?): String = when {
        value == null -> ""
        value == Math.floor(value) -> value.toLong().toString()
        else -> String.format(Locale.ROOT, "%.2f", value)
    }

    const val NO_PRICE = "—"
}

/** The usage records events and messages carry (packages/coding-agent/docs/json.md, session-format.md). */
object Charges {
    /** A usage object's total in dollars (`usage.cost.total`); null when it has none. */
    fun usd(usage: JsonObject?): Double? = usage?.obj("cost")?.double("total")?.takeIf { it.isFinite() && it >= 0.0 }

    /**
     * An assistant [message]'s charge, keyed under [scope] (the session, or a subagent's file) by the message's own
     * timestamp, model and output tokens, so the same message seen again (a re-read, a replay) has the same key. [at]
     * is when it counts; null for another role or a message without usage.
     */
    fun ofMessage(message: JsonObject, scope: String, at: Long): Charge? {
        if (message.str("role") != "assistant") return null
        val usage = message.obj("usage") ?: return null
        val model = model(message.str("provider"), message.str("model"))
        val stamp = message.long("timestamp")?.toString() ?: "t$at"
        return Charge("$scope/$stamp/$model/${usage.long("output") ?: 0}", usd(usage) ?: 0.0, model, at)
    }

    /**
     * The charge a live session event carries: a finished assistant message (`message_end`) or a compaction's summary
     * (`compaction_end` with `result.usage`). [now] is when it counts. Snapshots (`remote_hello`) carry none: their
     * history was counted when it happened.
     */
    fun live(event: JsonObject, scope: String, now: Long, model: String? = null): Charge? = when (event.type) {
        "message_end" -> event.obj("message")?.let { ofMessage(it, scope, now) }
        "compaction_end" -> event.obj("result")?.let { result ->
            val usage = result.obj("usage") ?: return null
            Charge("$scope/compaction/${result.str("firstKeptEntryId")}/${result.long("tokensBefore")}", usd(usage) ?: 0.0, model, now)
        }
        else -> null
    }

    /**
     * The assistant messages of a subagent's session file (`subagent_transcript`), keyed by the file. Only messages
     * from [since] on are new: a file read again from its start (a reopened session, a detail screen) brings back
     * older ones, which were counted when they happened or before the app watched.
     */
    fun subagent(event: JsonObject, since: Long, now: Long): List<Charge> {
        val file = event.str("file") ?: return emptyList()
        return event.arr("messages").orEmpty().mapNotNull { element ->
            val message = element as? JsonObject ?: return@mapNotNull null
            val at = message.long("timestamp") ?: now
            if (at < since) return@mapNotNull null
            ofMessage(message, file, at)
        }
    }

    /** A snapshot's history ([messages] of `remote_hello`): what the session cost so far, as far as its context shows. */
    fun history(messages: List<JsonObject>, scope: String, now: Long): Cost =
        messages.fold(Cost.NONE) { sum, m -> ofMessage(m, scope, now)?.let { sum + it.cost } ?: sum }

    fun model(provider: String?, model: String?): String? = when {
        model.isNullOrBlank() -> null
        provider.isNullOrBlank() -> model
        else -> "$provider/$model"
    }
}

/**
 * One session's cost while it is open: a [rebase]d total read from the session itself (the session file on this
 * phone, `get_session_stats` from a computer, or a snapshot's history until one of those answers), plus the charges
 * seen since. Charges are counted once by key. Thread-safe.
 */
class SessionTally {
    /** A point in the tally: a total read after it includes the charges seen up to it. */
    data class Mark(val epoch: Int, val seq: Long)

    private var base = Cost.NONE
    private val live = ArrayList<Pair<Long, Charge>>()
    private val seen = HashSet<String>()
    private var seq = 0L
    private var epoch = 0

    /** Adds [charge] unless its key was seen; true when it is new. */
    @Synchronized
    fun add(charge: Charge): Boolean {
        if (!seen.add(charge.key)) return false
        live += ++seq to charge
        return true
    }

    @Synchronized
    fun mark(): Mark = Mark(epoch, seq)

    /**
     * The session's own [total], read after [mark]: charges seen up to the mark are in it, later ones are added. A
     * total read before a [reset] (another session) is ignored. True when it applied.
     */
    @Synchronized
    fun rebase(total: Cost, mark: Mark): Boolean {
        if (mark.epoch != epoch) return false
        base = total
        live.removeAll { it.first <= mark.seq }
        return true
    }

    /** Another session: start over. */
    @Synchronized
    fun reset() {
        epoch++
        base = Cost.NONE
        live.clear()
        seen.clear()
    }

    val total: Cost
        @Synchronized get() = live.fold(base) { sum, (_, c) -> sum + c.cost }
}
