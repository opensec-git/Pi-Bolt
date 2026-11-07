package opensec.pibolt.app

import opensec.pibolt.app.ui.components.TableWidthCache
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TableWidthCacheTest {
    @AfterTest
    fun clear() = TableWidthCache.clear()

    @Test
    fun aTableScrolledBackGetsTheWidthsItWasMeasuredWith() {
        TableWidthCache.put(listOf("a", "b") to listOf(listOf("1", "2")), intArrayOf(120, 64))
        // An equal key built again (a new composition) finds them.
        assertContentEquals(intArrayOf(120, 64), TableWidthCache.get(listOf("a", "b") to listOf(listOf("1", "2"))))
        assertNull(TableWidthCache.get(listOf("a", "b") to listOf(listOf("1", "3"))))
    }

    @Test
    fun widthsAreCopiedInAndOut() {
        // A streaming table grows its own array in place; the cached widths must not move with it.
        val mine = intArrayOf(100, 100)
        TableWidthCache.put("t", mine)
        mine[0] = 300
        val got = assertNotNull(TableWidthCache.get("t"))
        assertContentEquals(intArrayOf(100, 100), got)
        got[1] = 500
        assertContentEquals(intArrayOf(100, 100), TableWidthCache.get("t"))
    }

    @Test
    fun theLeastRecentlyUsedTableGoesFirst() {
        for (i in 0 until TableWidthCache.MAX) TableWidthCache.put(i, intArrayOf(i))
        TableWidthCache.get(0)
        TableWidthCache.put("new", intArrayOf(1))
        assertNotNull(TableWidthCache.get(0))
        assertNull(TableWidthCache.get(1))
        assertNotNull(TableWidthCache.get("new"))
    }
}
