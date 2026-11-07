package opensec.pibolt.app

import opensec.pibolt.app.engine.LivePolicy
import opensec.pibolt.app.engine.LivePolicy.Live
import opensec.pibolt.app.engine.LivePolicy.Room
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
        assertTrue("3 sessions are working" in room.message)
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
}
