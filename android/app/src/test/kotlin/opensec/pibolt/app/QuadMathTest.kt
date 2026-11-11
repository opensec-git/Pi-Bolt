package opensec.pibolt.app

import opensec.pibolt.app.ui.components.QuadMath
import opensec.pibolt.app.ui.components.QuadMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuadMathTest {
    @Test
    fun modePriority() {
        // dialog > compaction > subagent > tool > writing > disconnected > thinking
        assertEquals(QuadMode.Wait, QuadMath.modeFor(true, true, true, true, true, false))
        assertEquals(QuadMode.Compact, QuadMath.modeFor(false, true, true, true, true, false))
        assertEquals(QuadMode.Agent, QuadMath.modeFor(false, false, true, true, true, false))
        assertEquals(QuadMode.Run, QuadMath.modeFor(false, false, false, true, true, false))
        assertEquals(QuadMode.Write, QuadMath.modeFor(false, false, false, false, true, false))
        assertEquals(QuadMode.Connect, QuadMath.modeFor(false, false, false, false, false, false))
        assertEquals(QuadMode.Think, QuadMath.modeFor(false, false, false, false, false, true))
    }

    @Test
    fun cellsStayWithinFloorAndFull() {
        for (mode in QuadMode.entries) {
            for (t in 0L until 4000L step 50) {
                for (i in 0 until 9) {
                    val c = QuadMath.cell(mode, i, t)
                    assertTrue(c.alpha in 0f..1f, "$mode cell $i at $t: alpha ${c.alpha}")
                    assertTrue(c.scale in 0.5f..1.2f, "$mode cell $i at $t: scale ${c.scale}")
                    if (mode != QuadMode.Connect && mode != QuadMode.Compact) {
                        assertTrue(c.alpha >= QuadMath.floorOf(i) - 1e-4f, "$mode cell $i at $t below floor")
                    }
                }
            }
        }
    }

    @Test
    fun restFrames() {
        val lit = { mode: QuadMode -> (0 until 9).filter { QuadMath.rest(mode, it).alpha >= 0.99f }.toSet() }
        assertEquals(setOf(0, 4, 8), lit(QuadMode.Think))
        assertEquals(setOf(1, 4, 7), lit(QuadMode.Run))
        assertEquals(setOf(0, 1, 2, 5, 4), lit(QuadMode.Write))
        assertEquals(setOf(0), lit(QuadMode.Connect))
        assertEquals(0.12f, QuadMath.rest(QuadMode.Connect, 4).alpha)
        assertTrue((0 until 9).all { QuadMath.rest(QuadMode.Wait, it).alpha == 0.8f })
        assertEquals(setOf(2), lit(QuadMode.Agent))
    }

    @Test
    fun keyframes() {
        assertEquals(0f, QuadMath.bump(0f), 1e-3f)
        assertEquals(1f, QuadMath.bump(0.3f), 1e-3f)
        assertEquals(0f, QuadMath.bump(0.7f), 1e-3f)
        assertEquals(0f, QuadMath.bump(0.9f), 1e-3f)
        assertEquals(1f, QuadMath.fastOutSlowIn(1f), 1e-3f)
        // Write fills one snake cell per 110 ms.
        assertEquals(1f, QuadMath.cell(QuadMode.Write, 0, 0).alpha)
        assertEquals(QuadMath.floorOf(1), QuadMath.cell(QuadMode.Write, 1, 50).alpha)
        assertEquals(1f, QuadMath.cell(QuadMode.Write, 1, 120).alpha)
        // Connect: centre is hollow, the lit corner moves clockwise every 400 ms.
        assertEquals(0.12f, QuadMath.cell(QuadMode.Connect, 4, 777).alpha)
        assertTrue(QuadMath.cell(QuadMode.Connect, 2, 400 + 120).alpha > 0.99f)
    }

    @Test
    fun brandMarkSettlesOnTheBolt() {
        val end = QuadMath.MARK_DURATION_MS + 100
        for (i in 0 until 9) {
            val c = QuadMath.mark(i, end)
            assertEquals(if (i in QuadMath.MARK) 1f else 0.16f, c.alpha, 1e-3f)
            assertEquals(1f, c.scale, 1e-3f)
        }
        assertEquals(0f, QuadMath.mark(8, 0).alpha)
    }
}
