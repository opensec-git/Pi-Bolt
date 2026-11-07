package opensec.pibolt.app

import opensec.pibolt.app.engine.IdlePool
import opensec.pibolt.app.engine.IdlePool.Want
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Which idle engine an open takes: the one that already has the wanted session, else the spare, else a parked one. */
class IdlePoolTest {
    private class Engine(var sessionFile: String?)

    private fun pool() = IdlePool<Engine>(maxParked = 1) { it.sessionFile }

    @Test
    fun reopeningTheLastSessionTakesItsParkedEngine() {
        val pool = pool()
        val spare = Engine("/s/new-spare.jsonl")
        val parked = Engine("/s/a.jsonl")
        assertTrue(pool.addFresh(spare, "/p/b"))
        assertTrue(pool.park(parked, "/p/a").isEmpty())
        val taken = pool.take(Want.Session("/s/a.jsonl"))!!
        assertSame(parked, taken.engine)
        assertTrue(taken.exact)
        // The spare stays for the next open.
        assertTrue(pool.hasFresh())
    }

    @Test
    fun aNewSessionInTheSparesProjectIsExact() {
        val pool = pool()
        val spare = Engine("/s/new.jsonl")
        pool.addFresh(spare, "/p/a")
        pool.park(Engine("/s/old.jsonl"), "/p/a")
        val taken = pool.take(Want.New("/p/a"))!!
        assertSame(spare, taken.engine)
        assertTrue(taken.exact)
    }

    @Test
    fun otherwiseTheSpareMovesAndParkedSessionsStay() {
        val pool = pool()
        val spare = Engine("/s/new.jsonl")
        val parked = Engine("/s/a.jsonl")
        pool.addFresh(spare, "/p/a")
        pool.park(parked, "/p/a")
        val taken = pool.take(Want.Session("/s/elsewhere.jsonl"))!!
        assertSame(spare, taken.engine)
        assertFalse(taken.exact)
        // No spare left: the parked engine is the fallback.
        assertSame(parked, pool.take(Want.New("/p/c"))!!.engine)
        assertNull(pool.take(Want.New("/p/c")))
    }

    @Test
    fun exactOnlyLeavesThePoolAloneWithoutAMatch() {
        val pool = pool()
        pool.addFresh(Engine("/s/new.jsonl"), "/p/a")
        assertNull(pool.take(Want.New("/p/b"), exactOnly = true))
        assertEquals(1, pool.engines().size)
    }

    @Test
    fun parkingPushesOutTheOlderParkedEngineButNotTheSpare() {
        val pool = pool()
        val spare = Engine("/s/new.jsonl")
        val first = Engine("/s/1.jsonl")
        val second = Engine("/s/2.jsonl")
        pool.addFresh(spare, "/p/a")
        pool.park(first, "/p/a")
        assertEquals(listOf(first), pool.park(second, "/p/a"))
        assertEquals(setOf(spare, second), pool.engines().toSet())
        assertFalse(pool.addFresh(Engine(null), "/p/a"), "one spare at a time")
        assertEquals(2, pool.drain().size)
        assertTrue(pool.engines().isEmpty())
    }
}
