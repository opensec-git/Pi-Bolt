package opensec.pibolt.app

import kotlinx.serialization.json.JsonObject
import opensec.pibolt.app.cost.CapCard
import opensec.pibolt.app.cost.CapKind
import opensec.pibolt.app.cost.CapStatus
import opensec.pibolt.app.cost.Charge
import opensec.pibolt.app.cost.Charges
import opensec.pibolt.app.cost.Cost
import opensec.pibolt.app.cost.LedgerCodec
import opensec.pibolt.app.cost.Money
import opensec.pibolt.app.cost.SessionSpending
import opensec.pibolt.app.cost.SpendCaps
import opensec.pibolt.app.cost.SpendGuard
import opensec.pibolt.app.cost.SpendLedger
import opensec.pibolt.app.cost.SpendLimits
import opensec.pibolt.app.cost.SpendStore
import opensec.pibolt.app.data.AppSettings
import opensec.pibolt.app.data.SettingsCodec
import opensec.pibolt.app.engine.SessionIndex
import opensec.pibolt.app.ui.cost.SpendText
import opensec.pibolt.app.ui.settings.SettingsIndex
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.session.SessionState
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spending: what counts once, which day it lands on, the limits and how costs read. */
class SpendTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private var now = at(2026, 11, 7, 12, 0)

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, zone: ZoneId = utc): Long = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun json(text: String) = ProtocolJson.parseToJsonElement(text) as JsonObject

    private fun assistant(ts: Long, usd: Double, output: Int = 10, model: String = "claude-sonnet-4-5") =
        """{"role":"assistant","content":[{"type":"text","text":"ok"}],"provider":"anthropic","model":"$model","usage":{"input":100,"output":$output,"cacheRead":0,"cacheWrite":0,"totalTokens":${100 + output},"cost":{"input":0,"output":0,"cacheRead":0,"cacheWrite":0,"total":$usd}},"stopReason":"stop","timestamp":$ts}"""

    private fun messageEnd(ts: Long, usd: Double, output: Int = 10) = json("""{"type":"message_end","message":${assistant(ts, usd, output)}}""")

    private fun hello(session: String, vararg messages: String) =
        json("""{"type":"remote_hello","host":{"name":"phone"},"state":{"sessionId":"$session"},"messages":[${messages.joinToString(",")}]}""")

    private fun transcript(file: String, reset: Boolean, vararg messages: String) =
        json("""{"type":"subagent_transcript","key":"call1","file":"$file","reset":$reset,"messages":[${messages.joinToString(",")}]}""")

    private val event = { type: String -> json("""{"type":"$type"}""") }

    private fun store() = SpendStore(Files.createTempDirectory("spend").toFile(), zone = { utc }, clock = { now })

    private fun spending(store: SpendStore, limits: SpendLimits = SpendLimits()) = SessionSpending(store, { limits }, "local:/p", clock = { now })

    private val running = SessionState(isRunning = true, sessionId = "s1")

    // Formatting

    @Test
    fun formatsDollarsAndUnpricedUse() {
        assertEquals("$0.42", Money.usd(0.42))
        assertEquals("$0.00", Money.usd(0.0))
        assertEquals("<$0.01", Money.usd(0.0004))
        assertEquals("$1,234.50", Money.usd(1234.5))
        assertNull(Money.label(Cost.NONE), "nothing used: no label")
        assertEquals("—", Money.label(Cost(0.0, 3)), "a custom endpoint has no price: not \$0")
        assertEquals("$0.42", Money.label(Cost(0.42, 2)))
        assertEquals("Today $0.42", SpendText.today(Cost(0.42, 2)))
        assertEquals("Today —", SpendText.today(Cost(0.0, 1)))
        assertNull(SpendText.today(Cost.NONE))
        assertEquals("2h · 12 messages · $0.42", SpendText.withCost("2h · 12 messages", Cost(0.42, 3), show = true))
        assertEquals("2h · 12 messages", SpendText.withCost("2h · 12 messages", Cost(0.42, 3), show = false))
        assertEquals("2h · 12 messages", SpendText.withCost("2h · 12 messages", Cost.NONE, show = true))
        assertEquals("claude-sonnet-4-5", SpendText.model("anthropic/claude-sonnet-4-5"))
        assertEquals("Off", SpendText.summary(SpendLimits()))
        assertEquals("$5.00 a day", SpendText.summary(SpendLimits(session = 1.0, day = 5.0)))
    }

    @Test
    fun parsesLimitsAsTyped() {
        assertEquals(5.0, Money.parse("5"))
        assertEquals(5.0, Money.parse("$5.00"))
        assertEquals(2.5, Money.parse("2,5"))
        assertNull(Money.parse(""))
        assertNull(Money.parse("0"))
        assertNull(Money.parse("-3"))
        assertNull(Money.parse("five"))
        assertEquals("5", Money.field(5.0))
        assertEquals("2.50", Money.field(2.5))
        assertEquals("", Money.field(null))
    }

    // Charges and accumulation

    @Test
    fun liveEventsCarryChargesAndSnapshotsDoNot() {
        val end = Charges.live(messageEnd(now, 0.02), "s1", now)!!
        assertEquals(0.02, end.usd)
        assertEquals("anthropic/claude-sonnet-4-5", end.model)
        assertNull(Charges.live(json("""{"type":"message_end","message":{"role":"user","content":"hi","timestamp":1}}"""), "s1", now))
        val compaction = Charges.live(json("""{"type":"compaction_end","result":{"firstKeptEntryId":"e1","tokensBefore":9000,"usage":{"cost":{"total":0.05}}}}"""), "s1", now, "anthropic/x")!!
        assertEquals(0.05, compaction.usd)
        assertNull(Charges.live(hello("s1", assistant(now, 1.0)), "s1", now))
    }

    @Test
    fun aMessageSeenTwiceCountsOnce() {
        val store = store()
        val s = spending(store)
        s.onEvent(running, hello("s1"))
        s.onEvent(running, messageEnd(now, 0.10))
        s.onEvent(running, messageEnd(now, 0.10))
        s.onEvent(running, messageEnd(now + 1, 0.05))
        assertEquals(0.15, s.cost.value.usd, 1e-9)
        assertEquals(0.15, store.today().usd, 1e-9)
        assertEquals(2, store.today().charges)
    }

    @Test
    fun reopeningASessionDoesNotCountItsHistoryAgain() {
        val store = store()
        val first = spending(store)
        first.onEvent(running, hello("s1"))
        first.onEvent(running, messageEnd(now, 0.30))
        assertEquals(0.30, store.today().usd, 1e-9)

        // Closed and resumed later today: a new controller, whose snapshot has that reply in its history.
        now += 60_000
        val again = spending(store)
        again.onEvent(SessionState(), hello("s1", assistant(now - 60_000, 0.30)))
        assertEquals(0.30, again.cost.value.usd, 1e-9, "the session shows what it cost so far")
        assertEquals(0.30, store.today().usd, 1e-9, "the day does not count it twice")
        again.onEvent(running, messageEnd(now, 0.20))
        assertEquals(0.50, again.cost.value.usd, 1e-9)
        assertEquals(0.50, store.today().usd, 1e-9)

        // A reconnect or resync replays the same snapshot; a fork or clone copies the history into a new session.
        again.onEvent(running, hello("s1", assistant(now - 60_000, 0.30), assistant(now, 0.20)))
        again.onEvent(running, hello("fork", assistant(now - 60_000, 0.30), assistant(now, 0.20)))
        assertEquals(0.50, store.today().usd, 1e-9)
        assertEquals(0.50, again.cost.value.usd, 1e-9, "the fork's own total is its copied history")
    }

    @Test
    fun theSessionsOwnTotalReplacesTheSnapshotsAndKeepsLaterCharges() {
        val s = spending(store())
        s.onEvent(running, hello("s1", assistant(1, 0.10)))
        val mark = s.mark()
        s.onEvent(running, messageEnd(now, 0.05))
        // Read from the session file (it has compacted turns and subagents too), asked before that last reply.
        s.rebase(Cost(0.80, 9), mark)
        assertEquals(0.85, s.cost.value.usd, 1e-9)
        // A total read for a session the controller already left is ignored.
        val stale = s.mark()
        s.onEvent(running, hello("s2"))
        s.rebase(Cost(5.0, 1), stale)
        assertEquals(0.0, s.cost.value.usd, 1e-9)
    }

    @Test
    fun subagentsCountOnceAndOnlyFromWhenTheAppWatched() {
        val store = store()
        val s = spending(store)
        s.onEvent(running, hello("s1"))
        val file = "/agent/sessions/x/2_child.jsonl"
        val before = assistant(now - 5_000, 0.40)
        val first = assistant(now + 1_000, 0.07)
        val second = assistant(now + 2_000, 0.03)
        s.onEvent(running, transcript(file, true, before, first))
        assertEquals(0.07, store.today().usd, 1e-9, "a message written before this session was watched is not new")
        // The tail reads the file again from its start (another snapshot), then the next message.
        s.onEvent(running, transcript(file, true, before, first))
        s.onEvent(running, transcript(file, false, second))
        assertEquals(0.10, store.today().usd, 1e-9)
        assertEquals(0.10, s.cost.value.usd, 1e-9)
        // Switched to another session and back: the same file read again adds nothing.
        s.onEvent(running, hello("s2"))
        s.onEvent(running, hello("s1"))
        s.onEvent(running, transcript(file, true, before, first, second))
        assertEquals(0.10, store.today().usd, 1e-9)
    }

    // Days and months

    @Test
    fun daysFollowTheLocalCalendar() {
        val kolkata = ZoneId.of("Asia/Kolkata")
        val late = at(2026, 11, 7, 23, 59, kolkata)
        val early = at(2026, 11, 8, 0, 1, kolkata)
        var ledger = SpendLedger()
        ledger = ledger.record(Charge("a", 1.0, "m", late), kolkata)
        ledger = ledger.record(Charge("b", 2.0, "m", early), kolkata)
        assertEquals(1.0, ledger.day(late, kolkata).usd)
        assertEquals(2.0, ledger.day(early, kolkata).usd)
        assertEquals(3.0, ledger.month(early, kolkata).usd)
        // The same two moments are one day in UTC (18:29 and 18:31 on the 7th).
        var inUtc = SpendLedger()
        inUtc = inUtc.record(Charge("a", 1.0, "m", late), utc).record(Charge("b", 2.0, "m", early), utc)
        assertEquals(3.0, inUtc.day(early, utc).usd)

        // Month rollover in the local zone.
        val endOfMonth = at(2026, 11, 30, 23, 30, kolkata)
        val nextMonth = at(2026, 12, 1, 0, 30, kolkata)
        ledger = ledger.record(Charge("c", 4.0, "m", endOfMonth), kolkata).record(Charge("d", 8.0, "m", nextMonth), kolkata)
        assertEquals(7.0, ledger.month(endOfMonth, kolkata).usd)
        assertEquals(8.0, ledger.month(nextMonth, kolkata).usd)
        assertEquals(0.0, ledger.day(at(2026, 12, 2, 9, 0, kolkata), kolkata).usd, "a new day starts at zero")
    }

    @Test
    fun theHistoryRollsAndSurvivesARestart() {
        var ledger = SpendLedger()
        val start = at(2026, 1, 1, 12, 0)
        for (d in 0 until 60) ledger = ledger.record(Charge("k$d", 1.0, "anthropic/a", start + d * 86_400_000L), utc)
        assertEquals(SpendLedger.KEEP_DAYS, ledger.days.size)
        assertEquals("2026-03-01", ledger.days.last().key)
        val back = LedgerCodec.decode(LedgerCodec.encode(ledger))
        assertEquals(ledger, back)
        assertEquals(SpendLedger(), LedgerCodec.decode("{not json"))
        assertEquals(1.0, back.monthPeriod(start + 59 * 86_400_000L, utc)!!.byModel["anthropic/a"]!!.usd)

        // The store reads its file back.
        val dir = Files.createTempDirectory("spend").toFile()
        File(dir, "spend.json").writeText(LedgerCodec.encode(ledger))
        assertEquals(ledger, SpendStore(dir, { utc }, { now }).ledger.value)
    }

    // Limits

    @Test
    fun limitsAreCheckedLongestPeriodFirst() {
        val limits = SpendLimits(session = 2.0, day = 5.0, month = 50.0)
        assertEquals(CapStatus.Ok, SpendCaps.check(limits, 1.0, 1.0, 1.0))
        assertEquals(CapStatus.Near(CapKind.Session, 2.0, 1.7), SpendCaps.check(limits, 1.7, 3.0, 10.0))
        assertEquals(CapStatus.Reached(CapKind.Day, 5.0, 5.0), SpendCaps.check(limits, 2.5, 5.0, 10.0))
        assertEquals(CapStatus.Reached(CapKind.Month, 50.0, 51.0), SpendCaps.check(limits, 2.5, 6.0, 51.0))
        assertEquals(CapStatus.Ok, SpendCaps.check(SpendLimits(), 100.0, 100.0, 100.0), "off by default")
        assertEquals("Daily limit reached ($5.00). Raise it in Settings or continue once.", SpendCaps.card(CapKind.Day, 5.0))
        assertEquals("80% of today's $5.00 limit used", SpendCaps.near(CapKind.Day, 5.0, 4.0))
    }

    @Test
    fun aRunAtTheLimitFinishesItsStepThenStops() {
        val store = store()
        val s = spending(store, SpendLimits(day = 1.0))
        s.onEvent(SessionState(sessionId = "s1"), hello("s1"))
        assertTrue(s.maySend())
        assertFalse(s.onEvent(SessionState(sessionId = "s1"), event("agent_start")).abort)
        s.onEvent(running, messageEnd(now, 1.20))
        // Not cut in the middle of the step: the abort waits for the turn's end (reply and tool calls done).
        assertTrue(s.onEvent(running, event("turn_end")).abort)
        assertEquals(CapCard(CapKind.Day, 1.0, stopped = true), s.card.value)
        s.onEvent(running, event("agent_settled"))
        // No new turns: a prompt is held back, a run that starts by itself (a background agent's report) stops at once.
        s.dismissCard()
        assertFalse(s.maySend())
        assertEquals(CapCard(CapKind.Day, 1.0, stopped = false), s.card.value)
        assertTrue(s.onEvent(SessionState(sessionId = "s1"), event("agent_start")).abort)
    }

    @Test
    fun continueOnceLetsOneRunFinish() {
        val s = spending(store(), SpendLimits(day = 1.0))
        s.onEvent(running, hello("s1"))
        s.onEvent(running, messageEnd(now, 1.5))
        assertFalse(s.maySend())
        s.continueOnce()
        assertNull(s.card.value)
        assertTrue(s.maySend())
        assertFalse(s.onEvent(running, event("agent_start")).abort)
        s.onEvent(running, messageEnd(now + 1, 0.2))
        assertFalse(s.onEvent(running, event("turn_end")).abort, "the allowed run goes to its end")
        assertTrue(s.maySend(), "a steer into the allowed run")
        s.onEvent(running, event("agent_settled"))
        assertFalse(s.maySend(), "used up when the run settled")
    }

    @Test
    fun theGuardAloneFollowsTheSameRules() {
        val reached = CapStatus.Reached(CapKind.Session, 2.0, 2.0)
        val g = SpendGuard()
        assertTrue(g.maySend(CapStatus.Ok))
        assertFalse(g.maySend(reached))
        assertTrue(g.onTurnEnd(reached))
        assertFalse(g.onTurnEnd(CapStatus.Near(CapKind.Session, 2.0, 1.8)))
        g.continueOnce()
        assertFalse(g.onAgentStart(reached))
        assertFalse(g.onAgentStart(reached), "a retry within the allowed run")
        g.onSettled()
        assertTrue(g.onAgentStart(reached))
    }

    @Test
    fun theWarningComesOncePerPeriod() {
        val s = spending(store(), SpendLimits(day = 1.0))
        s.onEvent(running, hello("s1"))
        assertNull(s.onEvent(running, messageEnd(now, 0.5)).warning)
        assertEquals("80% of today's $1.00 limit used", s.onEvent(running, messageEnd(now + 1, 0.3)).warning)
        assertNull(s.onEvent(running, messageEnd(now + 2, 0.05)).warning)
    }

    @Test
    fun aSessionLimitCountsTheSessionsWholeCost() {
        val s = spending(store(), SpendLimits(session = 1.0))
        // Resumed: what it cost before counts toward its limit, though not toward today.
        s.onEvent(SessionState(), hello("s1", assistant(1, 0.9)))
        s.onEvent(running, messageEnd(now, 0.2))
        assertTrue(s.onEvent(running, event("turn_end")).abort)
        assertEquals(CapKind.Session, s.card.value?.kind)
        // Another session: its card goes, the new session starts under its limit.
        s.onEvent(SessionState(), hello("s2"))
        assertNull(s.card.value)
        assertTrue(s.maySend())
    }

    // Saved sessions and settings

    private fun line(json: String) = json + "\n"

    @Test
    fun savedSessionsAddUpTheirOwnAndTheirSubagentsCost() {
        val agentDir = Files.createTempDirectory("agent").toFile()
        val project = Files.createTempDirectory("project").toFile()
        val index = SessionIndex(agentDir)
        val dir = index.sessionDir(project.absolutePath).apply { mkdirs() }
        val parent = File(dir, "1_parent.jsonl")
        parent.writeText(
            line("""{"type":"session","id":"parent"}""") +
                line("""{"type":"message","message":{"role":"user","content":"say \"cost\":{\"total\":9}"}}""") +
                line("""{"type":"message","message":${assistant(1, 0.25)}}""") +
                line("""{"type":"compaction","summary":"s","firstKeptEntryId":"e","tokensBefore":1,"usage":{"input":1,"output":1,"cost":{"input":0,"output":0,"total":0.05}}}""") +
                line("""{"type":"usage","kind":"cache_warm","usage":{"cost":{"total":0.01}}}"""),
        )
        File(dir, "2_child.jsonl").writeText(
            line("""{"type":"session","id":"child","parentSession":"${parent.path}"}""") +
                line("""{"type":"session_info","name":"Explore#1669f14e"}""") +
                line("""{"type":"message","message":${assistant(2, 0.10)}}"""),
        )
        // A fork names its parent too, but is a session of its own: its cost is not the parent's.
        File(dir, "3_fork.jsonl").writeText(
            line("""{"type":"session","id":"fork","parentSession":"${parent.path}"}""") +
                line("""{"type":"message","message":{"role":"user","content":"hi"}}""") +
                line("""{"type":"message","message":${assistant(3, 1.0)}}"""),
        )
        val listed = index.list(project).associateBy { it.id }
        assertEquals(setOf("parent", "fork"), listed.keys)
        assertEquals(0.41, listed.getValue("parent").cost.usd, 1e-9)
        assertEquals(4, listed.getValue("parent").cost.charges)
        assertEquals(1.0, listed.getValue("fork").cost.usd, 1e-9)
        assertEquals(0.41, index.cost(parent).usd, 1e-9)

        // A custom endpoint without prices: used, at $0, so its row says "—".
        val custom = File(dir, "4_custom.jsonl")
        custom.writeText(line("""{"type":"session","id":"custom"}""") + line("""{"type":"message","message":${assistant(4, 0.0)}}"""))
        assertEquals("—", Money.label(index.cost(custom)))
    }

    @Test
    fun limitsAreSavedWithTheSettings() {
        val s = AppSettings(spend = SpendLimits(session = 2.0, day = 5.0, month = null, showInLists = true))
        assertEquals(s, SettingsCodec.decode(SettingsCodec.encode(s)))
        assertEquals(SpendLimits(), SettingsCodec.decode("""{"spendDay":-4,"spendMonth":"x"}""").spend, "a damaged limit is no limit")
        assertEquals(SpendLimits(), AppSettings().spend, "off by default")
        assertEquals(setOf("spending"), SettingsIndex.visible("budget"))
    }
}
