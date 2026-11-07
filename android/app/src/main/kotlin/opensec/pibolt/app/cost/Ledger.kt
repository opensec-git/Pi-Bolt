package opensec.pibolt.app.cost

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.arr
import opensec.pibolt.protocol.double
import opensec.pibolt.protocol.int
import opensec.pibolt.protocol.obj
import opensec.pibolt.protocol.str
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** What a day ("2026-11-07") or a month ("2026-11") cost, in total and by model ("provider/model", or "" unknown). */
@Immutable
data class Period(val key: String, val cost: Cost = Cost.NONE, val byModel: Map<String, Cost> = emptyMap()) {
    fun plus(charge: Charge): Period {
        val model = charge.model.orEmpty()
        return copy(cost = cost + charge.cost, byModel = byModel + (model to ((byModel[model] ?: Cost.NONE) + charge.cost)))
    }
}

/**
 * Spending on this phone's watch, across all sessions (projects, chats, computers and their subagents): the last
 * [KEEP_DAYS] days and [KEEP_MONTHS] months, by the phone's local calendar. Only charges that happened while the app
 * watched are recorded ([Charges.live], [Charges.subagent]); a reopened session's history never is.
 */
@Immutable
data class SpendLedger(val days: List<Period> = emptyList(), val months: List<Period> = emptyList()) {
    fun day(at: Long, zone: ZoneId): Cost = dayPeriod(at, zone)?.cost ?: Cost.NONE

    fun month(at: Long, zone: ZoneId): Cost = monthPeriod(at, zone)?.cost ?: Cost.NONE

    fun dayPeriod(at: Long, zone: ZoneId): Period? = dayKey(at, zone).let { k -> days.firstOrNull { it.key == k } }

    fun monthPeriod(at: Long, zone: ZoneId): Period? = monthKey(at, zone).let { k -> months.firstOrNull { it.key == k } }

    /** [charge] added to its day and month; periods past the window drop off. */
    fun record(charge: Charge, zone: ZoneId): SpendLedger {
        val d = dayKey(charge.at, zone)
        val m = monthKey(charge.at, zone)
        return SpendLedger(
            days = add(days, d, charge).takeLast(KEEP_DAYS),
            months = add(months, m, charge).takeLast(KEEP_MONTHS),
        )
    }

    private fun add(list: List<Period>, key: String, charge: Charge): List<Period> {
        val i = list.indexOfFirst { it.key == key }
        if (i >= 0) return list.toMutableList().also { it[i] = it[i].plus(charge) }
        // Keys sort as dates: a late charge for an earlier day (a clock change) still lands in order.
        return (list + Period(key).plus(charge)).sortedBy { it.key }
    }

    companion object {
        const val KEEP_DAYS = 35
        const val KEEP_MONTHS = 13

        fun dayKey(at: Long, zone: ZoneId): String = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString()

        fun monthKey(at: Long, zone: ZoneId): String = YearMonth.from(Instant.ofEpochMilli(at).atZone(zone)).toString()
    }
}

/** JSON form of [SpendLedger] (spend.json). A damaged file loads as empty. */
object LedgerCodec {
    fun encode(ledger: SpendLedger): String = buildJsonObject {
        put("version", 1)
        put("days", periods(ledger.days))
        put("months", periods(ledger.months))
    }.toString()

    fun decode(text: String?): SpendLedger {
        val json = text?.let { runCatching { ProtocolJson.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return SpendLedger()
        return SpendLedger(periodsFrom(json.arr("days")).takeLast(SpendLedger.KEEP_DAYS), periodsFrom(json.arr("months")).takeLast(SpendLedger.KEEP_MONTHS))
    }

    private fun periods(list: List<Period>): JsonArray = buildJsonArray {
        for (p in list) {
            add(
                buildJsonObject {
                    put("key", p.key)
                    put("cost", cost(p.cost))
                    put("models", JsonObject(p.byModel.mapValues { (_, c) -> cost(c) }))
                },
            )
        }
    }

    private fun cost(c: Cost): JsonObject = JsonObject(mapOf("usd" to JsonPrimitive(c.usd), "n" to JsonPrimitive(c.charges)))

    private fun costFrom(json: JsonObject?): Cost? {
        json ?: return null
        val usd = json.double("usd")?.takeIf { it.isFinite() && it >= 0 } ?: return null
        return Cost(usd, (json.int("n") ?: 0).coerceAtLeast(0))
    }

    private fun periodsFrom(array: JsonArray?): List<Period> = array.orEmpty().mapNotNull { element ->
        val o = element as? JsonObject ?: return@mapNotNull null
        val key = o.str("key") ?: return@mapNotNull null
        val cost = costFrom(o.obj("cost")) ?: return@mapNotNull null
        val models = o.obj("models")?.mapNotNull { (k, v) -> costFrom(v as? JsonObject)?.let { k to it } }?.toMap().orEmpty()
        Period(key, cost, models)
    }.sortedBy { it.key }
}
