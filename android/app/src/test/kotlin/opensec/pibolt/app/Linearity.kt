package opensec.pibolt.app

import kotlin.test.assertTrue

/**
 * Asserts that [work] (given an input size) runs in linear time: after a warm-up, size 4n takes under 8 times size n
 * (quadratic work takes 16 times), and size 4n stays under [ceilingMs]. A ratio, not a tight wall-clock limit, so
 * the check holds on a heavily loaded host. Each size is timed as the best of three runs to damp scheduling noise.
 */
fun assertLinear(label: String, n: Int, ceilingMs: Long = 10_000, work: (Int) -> Unit) {
    work(n)
    val small = bestOf { work(n) }
    val large = bestOf { work(4 * n) }
    // Below tens of milliseconds the ratio is GC and scheduling noise; quadratic work at the sizes used is far above it.
    val base = maxOf(small, FLOOR_NS)
    assertTrue(large < 8 * base, "$label: 4n took ${large / 1_000_000} ms, n took ${small / 1_000_000} ms")
    assertTrue(large / 1_000_000 < ceilingMs, "$label: 4n took ${large / 1_000_000} ms")
}

private const val FLOOR_NS = 50_000_000L

private fun bestOf(work: () -> Unit): Long = (1..3).minOf {
    val started = System.nanoTime()
    work()
    System.nanoTime() - started
}
