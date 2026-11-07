package opensec.pibolt.app

import opensec.pibolt.app.engine.LivePolicy
import opensec.pibolt.app.engine.LivePolicy.Here
import opensec.pibolt.app.engine.LivePolicy.Live
import opensec.pibolt.app.engine.LivePolicy.Plan
import opensec.pibolt.app.engine.LivePolicy.Room
import opensec.pibolt.app.engine.LivePolicy.Want
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Several on-device sessions at once: the cap, and that a running turn is never stopped. */
class LivePolicyTest {
    @Test
    fun belowTheCapThereIsRoom() {
        assertEquals(Room.Free, LivePolicy.roomFor(listOf(Live("a", busy = true, onScreen = true, lastUsed = 1))))
    }

    @Test
    fun atTheCapTheLeastRecentlyUsedIdleSessionGoesNeverABusyOne() {
        val live = listOf(
            Live("busy-old", busy = true, onScreen = false, lastUsed = 1),
            Live("idle-new", busy = false, onScreen = false, lastUsed = 30),
            Live("idle-old", busy = false, onScreen = false, lastUsed = 20),
        )
        assertEquals(Room.Stop("idle-old"), LivePolicy.roomFor(live))
    }

    @Test
    fun theSessionOnScreenGoesLast() {
        val live = listOf(
            Live("shown", busy = false, onScreen = true, lastUsed = 1),
            Live("b", busy = true, onScreen = false, lastUsed = 2),
            Live("c", busy = false, onScreen = false, lastUsed = 3),
        )
        assertEquals(Room.Stop("c"), LivePolicy.roomFor(live))
        assertEquals(Room.Stop("shown"), LivePolicy.roomFor(live.filterNot { it.key == "c" } + Live("d", busy = true, onScreen = false, lastUsed = 4)))
    }

    @Test
    fun allBusyMeansNoRoom() {
        val live = (1..3).map { Live("p$it", busy = true, onScreen = it == 1, lastUsed = it.toLong()) }
        val room = LivePolicy.roomFor(live)
        assertIs<Room.Full>(room)
        assertEquals("3 sessions are running. Stop one, or wait.", room.message)
    }

    @Test
    fun trimStopsOnlyIdleSessionsOffScreen() {
        val live = listOf(
            Live("busy", busy = true, onScreen = false, lastUsed = 0),
            Live("shown", busy = false, onScreen = true, lastUsed = 0),
            Live("stale", busy = false, onScreen = false, lastUsed = 0),
            Live("recent", busy = false, onScreen = false, lastUsed = 1_000_000),
        )
        assertEquals(listOf("stale"), LivePolicy.trim(live, critical = false, now = 1_000_000 + 1))
        assertEquals(listOf("stale", "recent"), LivePolicy.trim(live, critical = true, now = 1_000_001))
    }

    // Several sessions in one project: "when I open another session in same project and work in it, the previous
    // session auto aborts". Pi's switch_session / new_session abort the turn they replace, so a busy session is never
    // moved: the other one opens beside it.

    @Test
    fun aNewSessionBesideABusyOneOpensAnotherNeverMovesTheBusyOne() {
        val busy = Here("local:/p", "/s/a.jsonl", busy = true, lastUsed = 10)
        assertEquals(Plan.Add, LivePolicy.planOpen(listOf(busy), Want.New))
        assertEquals(Plan.Add, LivePolicy.planOpen(listOf(busy), Want.Session("/s/b.jsonl")))
    }

    @Test
    fun anIdleSessionMovesTheMostRecentlyUsedFirst() {
        val here = listOf(
            Here("local:/p", "/s/a.jsonl", busy = true, lastUsed = 30),
            Here("local:/p#1", "/s/b.jsonl", busy = false, lastUsed = 10),
            Here("local:/p#2", "/s/c.jsonl", busy = false, lastUsed = 20),
        )
        assertEquals(Plan.Move("local:/p#2"), LivePolicy.planOpen(here, Want.New))
        assertEquals(Plan.Move("local:/p#2"), LivePolicy.planOpen(here, Want.Session("/s/d.jsonl")))
    }

    @Test
    fun aSessionAlreadyLiveIsShownBusyOrNot() {
        val here = listOf(
            Here("local:/p", "/s/a.jsonl", busy = true, lastUsed = 10),
            Here("local:/p#1", "/s/b.jsonl", busy = false, lastUsed = 20),
        )
        assertEquals(Plan.Show("local:/p"), LivePolicy.planOpen(here, Want.Session("/s/a.jsonl")))
        assertEquals(Plan.Show("local:/p#1"), LivePolicy.planOpen(here, Want.Session("/s/b.jsonl")))
        // "Continue": the one used last, even while it works.
        assertEquals(Plan.Show("local:/p#1"), LivePolicy.planOpen(here, Want.Latest))
        assertEquals(Plan.Show("local:/p"), LivePolicy.planOpen(here.take(1), Want.Latest))
    }

    @Test
    fun aProjectWithoutLiveSessionsAddsOne() {
        assertEquals(Plan.Add, LivePolicy.planOpen(emptyList(), Want.Latest))
        assertEquals(Plan.Add, LivePolicy.planOpen(emptyList(), Want.New))
    }

    @Test
    fun theCapCountsSessionsOfOneProjectAndNeverStopsARunningOne() {
        // Two running in one project, one idle elsewhere: the idle one makes room.
        val live = listOf(
            Live("local:/p", busy = true, onScreen = false, lastUsed = 1),
            Live("local:/p#1", busy = true, onScreen = true, lastUsed = 3),
            Live("local:/q", busy = false, onScreen = false, lastUsed = 2),
        )
        assertEquals(Room.Stop("local:/q"), LivePolicy.roomFor(live))
        // All three running: the user is asked; nothing is stopped.
        val full = LivePolicy.roomFor(live.map { it.copy(busy = true) })
        assertIs<Room.Full>(full)
        assertEquals("3 sessions are running. Stop one, or wait.", full.message)
        // Memory trims skip them too.
        assertEquals(emptyList(), LivePolicy.trim(live.map { it.copy(busy = true) }, critical = true, now = Long.MAX_VALUE))
    }

    @Test
    fun aBusySessionRefusesToSwitchUnlessTheComputerKeepsItRunning() {
        // On this phone: never while busy (the engine would abort the turn).
        assertEquals(LivePolicy.BUSY_SWITCH, LivePolicy.switchRefusal(local = true, busy = true, hostParallel = false))
        assertEquals(null, LivePolicy.switchRefusal(local = true, busy = false, hostParallel = false))
        // An older service has one runtime: refused while busy, with the message the app shows.
        assertEquals("A task is running in this session. Wait for it, or stop it first.", LivePolicy.switchRefusal(local = false, busy = true, hostParallel = false))
        assertEquals(null, LivePolicy.switchRefusal(local = false, busy = false, hostParallel = false))
        // A service with host.parallelSessions keeps the run going: the switch only changes the view.
        assertEquals(null, LivePolicy.switchRefusal(local = false, busy = true, hostParallel = true))
    }

    @Test
    fun slotsAreTheSmallestFree() {
        assertEquals(0, LivePolicy.freeSlot(emptyList()))
        assertEquals(1, LivePolicy.freeSlot(listOf(0)))
        assertEquals(1, LivePolicy.freeSlot(listOf(0, 2)))
        assertEquals(0, LivePolicy.freeSlot(listOf(1, 2)))
    }
}
