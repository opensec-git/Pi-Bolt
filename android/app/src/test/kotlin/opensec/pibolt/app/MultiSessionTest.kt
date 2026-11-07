package opensec.pibolt.app

import opensec.pibolt.app.session.LiveRegistry
import opensec.pibolt.app.session.RunningSummary
import opensec.pibolt.app.session.RunningSummary.Entry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Several sessions at once: the registry of live sessions, the foreground notification, per-session notifications. */
class MultiSessionTest {
    private class S(val name: String)

    @Test
    fun sessionsLiveSideBySideAndFocusFollowsTheLastShown() {
        var now = 0L
        val live = LiveRegistry<S> { now }
        val a = S("a")
        val b = S("b")
        now = 10
        live.add(a)
        now = 20
        live.add(b)
        assertEquals(listOf(a, b), live.all.value)
        assertSame(b, live.focused.value)
        now = 30
        live.focus(a)
        assertSame(a, live.focused.value)
        assertEquals(30, live.lastUsed(a))
        assertEquals(20, live.lastUsed(b))
        // Switching stops nothing: both stay live.
        assertEquals(2, live.all.value.size)
    }

    @Test
    fun aProjectsNewSessionTakesThePlaceOfItsOldOne() {
        val live = LiveRegistry<S> { 0 }
        val a = S("a")
        val b = S("b")
        val a2 = S("a2")
        live.add(a)
        live.add(b)
        live.add(a2, replacing = a)
        assertEquals(listOf(a2, b), live.all.value)
        assertSame(a2, live.focused.value)
    }

    @Test
    fun aStoppedSessionLeavesAndTakesTheFocusWithIt() {
        val live = LiveRegistry<S> { 0 }
        val a = S("a")
        val b = S("b")
        live.add(a)
        live.add(b)
        live.remove(b)
        assertEquals(listOf(a), live.all.value)
        assertNull(live.focused.value)
        // A stopped session cannot take the focus again.
        live.focus(b)
        assertNull(live.focused.value)
        assertEquals(0, live.lastUsed(b))
    }

    private fun entry(name: String, running: Boolean = false, waiting: Boolean = false, connected: Boolean = true) =
        Entry(name, local = true, running = running, waiting = waiting, compacting = false, connected = connected)

    @Test
    fun theForegroundNotificationSumsUpEveryBusySession() {
        assertNull(RunningSummary.of(listOf(entry("idle")), "Pi-Bolt"), "nothing busy: the service stops")
        val one = RunningSummary.of(listOf(entry("api", running = true), entry("idle")), "Pi-Bolt")!!
        assertEquals("api", one.title)
        assertEquals("Working", one.text)
        val two = RunningSummary.of(listOf(entry("api", running = true), entry("web", running = true, waiting = true), entry("idle")), "Pi-Bolt")!!
        assertEquals("Pi-Bolt", two.title)
        assertEquals("2 sessions · 1 waiting for your answer", two.text)
        assertEquals(listOf("api: Working", "web: Waiting for your answer"), two.lines)
        assertEquals("Restarting the engine…", entry("x", running = true, connected = false).status)
    }

    @Test
    fun eachSessionHasItsOwnNotifications() {
        val a = RunningSummary.notificationId("local:/p/a", RunningSummary.SETTLED)
        val b = RunningSummary.notificationId("local:/p/b", RunningSummary.SETTLED)
        assertNotEquals(a, b)
        assertNotEquals(a, RunningSummary.notificationId("local:/p/a", RunningSummary.ANSWER))
        assertEquals(a, RunningSummary.notificationId("local:/p/a", RunningSummary.SETTLED))
        assertTrue(listOf(a, b).all { it >= 100 }, "clear of the fixed ids")
    }
}
