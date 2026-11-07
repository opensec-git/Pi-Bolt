package opensec.pibolt.app.cost

import java.io.File
import java.time.ZoneId
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The daily and monthly totals ([SpendLedger]) in `filesDir/spend.json`, beside settings.json and stored the same way:
 * read once (a few kilobytes), written on a background thread (tmp file + rename). Its own file, so a charge does not
 * rewrite the app's settings or recompose what reads them.
 */
class SpendStore(dir: File, private val zone: () -> ZoneId = ZoneId::systemDefault, private val clock: () -> Long = System::currentTimeMillis) {
    private val file = File(dir, "spend.json")
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "spend-writer").apply { isDaemon = true } }
    private val _ledger = MutableStateFlow(LedgerCodec.decode(runCatching { if (file.exists()) file.readText() else null }.getOrNull()))
    val ledger: StateFlow<SpendLedger> = _ledger.asStateFlow()

    /** Warnings already shown in this process, by limit and period ("Day 2026-11-07"). */
    private val warned = HashSet<String>()

    fun today(): Cost = _ledger.value.day(clock(), zone())

    fun thisMonth(): Cost = _ledger.value.month(clock(), zone())

    @Synchronized
    fun record(charge: Charge) {
        val next = _ledger.value.record(charge, zone())
        _ledger.value = next
        val text = LedgerCodec.encode(next)
        writer.execute {
            val tmp = File(file.parentFile, file.name + ".tmp")
            runCatching {
                tmp.writeText(text)
                if (!tmp.renameTo(file)) {
                    file.writeText(text)
                    tmp.delete()
                }
            }
        }
    }

    /** True the first time [kind]'s warning is due in [period] (a session id, a day, a month). */
    @Synchronized
    fun warnOnce(kind: CapKind, period: String): Boolean = warned.add("${kind.name} $period")

    /** The period a limit covers now: the session's id, today's date or this month. */
    fun period(kind: CapKind, sessionId: String?): String = when (kind) {
        CapKind.Session -> sessionId.orEmpty()
        CapKind.Day -> SpendLedger.dayKey(clock(), zone())
        CapKind.Month -> SpendLedger.monthKey(clock(), zone())
    }
}
