package opensec.pibolt.app

import opensec.pibolt.app.session.LiveRegistry
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.app.ui.projects.LocalSessions
import opensec.pibolt.app.ui.projects.LocalSessions.Placed
import java.io.File
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

    // Two sessions of one project at once (one was busy as the other opened): their own ids, keys and names.

    @Test
    fun sessionsOfOneProjectHaveTheirOwnIds() {
        val p = File("/data/data/in.opensec.pibolt/files/projects/app")
        assertEquals("local:${p.absolutePath}", SessionController.localId(p))
        assertEquals("local:${p.absolutePath}", SessionController.localId(p, 0))
        assertEquals("local:${p.absolutePath}#1", SessionController.localId(p, 1))
        // So their "finished" and "needs an answer" notifications stand side by side, and answers reach the right one.
        assertNotEquals(
            RunningSummary.notificationId(SessionController.localId(p), RunningSummary.SETTLED),
            RunningSummary.notificationId(SessionController.localId(p, 1), RunningSummary.SETTLED),
        )
    }

    @Test
    fun anOpenedSessionTakesTheFrontAndARunningOneMovesAside() {
        // Nothing live: the project's name.
        assertEquals("app" to null, LocalSessions.place(emptyMap(), "app", existing = null))
        // The front one still runs: the new one is the front, the running one moves to app#2.
        val running = mapOf("app" to Placed("app", stopped = false))
        assertEquals("app" to "app#2", LocalSessions.place(running, "app", existing = null))
        // ... or the next free place.
        assertEquals("app" to "app#3", LocalSessions.place(running + ("app#2" to Placed("app", stopped = false)), "app", existing = null))
        assertEquals("app" to "app#2", LocalSessions.place(running + ("app#2" to Placed("app", stopped = true)), "app", existing = null))
        // The front one was replaced (stopped): its place is free.
        assertEquals("app" to null, LocalSessions.place(mapOf("app" to Placed("app", stopped = true)), "app", existing = null))
        // A session already live keeps its place.
        assertEquals("app#2" to null, LocalSessions.place(running + ("app#2" to Placed("app", stopped = false)), "app", existing = "app#2"))
    }

    @Test
    fun theNotificationTellsTwoRunsOfOneProjectApart() {
        assertEquals("app", RunningSummary.name("app", "Fix the login bug", shared = false))
        assertEquals("app · Fix the login bug", RunningSummary.name("app", "Fix the login bug\nand more", shared = true))
        assertEquals("app", RunningSummary.name("app", null, shared = true))
        val long = RunningSummary.name("app", "a".repeat(80), shared = true)
        assertTrue(long.length <= "app · ".length + 32 && long.endsWith("…"), long)
    }
}
