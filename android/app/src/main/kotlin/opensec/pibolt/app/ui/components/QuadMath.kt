package opensec.pibolt.app.ui.components

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

/** What the agent is doing, as the Quad spinner shows it. */
enum class QuadMode(val periodMs: Int, val label: String) {
    Think(1600, "Thinking"),
    Run(1100, "Running"),
    Write(1800, "Writing"),
    Agent(1200, "Agent working"),
    Wait(2000, "Waiting for you"),
    Compact(1500, "Compacting"),
    Connect(1600, "Connecting"),
}

/** One cell of the 3x3 grid: alpha, scale about its centre, offset in grid units. */
data class QuadCell(val alpha: Float, val scale: Float = 1f, val dx: Float = 0f, val dy: Float = 0f)

/**
 * Pure Quad geometry and keyframes (DESIGN.md "The Quad spinner"). Indices `i = r*3 + c`, centre 4.
 * Grid spans 11 units: cell 3u, gap 1u.
 */
object QuadMath {
    const val CENTRE = 4
    val RING = intArrayOf(0, 1, 2, 5, 8, 7, 6, 3)
    val SNAKE = intArrayOf(0, 1, 2, 5, 4, 3, 6, 7, 8)
    val CORNERS = intArrayOf(0, 2, 8, 6)

    /** The brand mark: a zig-zag bolt through the grid. */
    val MARK = intArrayOf(1, 2, 4, 6, 7)

    /** Animation clock step: 20 fps. */
    const val TICK_MS = 50L

    fun floorOf(i: Int): Float = if (i == CENTRE) 0.34f else 0.16f

    /**
     * Mode priority: open dialog, compaction, subagent, running tool, streaming text or tool arguments, no connection,
     * else thinking.
     */
    fun modeFor(
        dialogOpen: Boolean,
        compacting: Boolean,
        subagentRunning: Boolean,
        toolRunning: Boolean,
        writing: Boolean,
        connected: Boolean,
    ): QuadMode = when {
        dialogOpen -> QuadMode.Wait
        compacting -> QuadMode.Compact
        subagentRunning -> QuadMode.Agent
        toolRunning -> QuadMode.Run
        writing -> QuadMode.Write
        !connected -> QuadMode.Connect
        else -> QuadMode.Think
    }

    /** Cell [i] of [mode] at clock time [tMs]. */
    fun cell(mode: QuadMode, i: Int, tMs: Long): QuadCell {
        val r = i / 3
        val c = i % 3
        val floor = floorOf(i)
        val ms = (tMs % mode.periodMs).toInt()
        val p = ms / mode.periodMs.toFloat()
        return when (mode) {
            QuadMode.Think -> {
                val d = (r + c) / 4f * 0.55f
                val a = lerp(floor, 1f, bump(frac(p - d)))
                QuadCell(if (i == CENTRE) max(a, 0.5f) else a)
            }
            QuadMode.Run -> {
                val x = 2f * tri(p)
                val v = max(0f, 1f - abs(c - x) / 1.15f)
                QuadCell(lerp(floor, 1f, v), scale = 0.86f + 0.14f * v)
            }
            QuadMode.Write -> {
                val pos = SNAKE.indexOf(i)
                val a = when {
                    ms < 990 -> if (pos < ms / 110 + 1) 1f else floor
                    ms < 1350 -> 1f
                    ms < 1600 -> lerp(1f, floor, linearOutSlowIn((ms - 1350) / 250f))
                    else -> floor
                }
                QuadCell(a)
            }
            QuadMode.Agent -> {
                if (i == CENTRE) {
                    val s = 0.5f - 0.5f * cos(2f * PI.toFloat() * ((tMs % 2400) / 2400f))
                    QuadCell(lerp(0.34f, 0.7f, s))
                } else {
                    val head = ((tMs / 150) % 8).toInt()
                    QuadCell(ringAlpha(i, head))
                }
            }
            QuadMode.Wait -> {
                val t = if (i == CENTRE) tMs + 140 else tMs
                val b = 0.5f - 0.5f * cos(2f * PI.toFloat() * ((t % 2000) / 2000f))
                QuadCell(lerp(0.35f, 1f, b), scale = 0.92f + 0.08f * b)
            }
            QuadMode.Compact -> compactCell(i, r, c, easeInOut(if (p < 0.5f) p * 2f else (1f - p) * 2f))
            QuadMode.Connect -> {
                when (i) {
                    CENTRE -> QuadCell(0.12f)
                    in CORNERS -> {
                        val k = ((tMs / 400) % 4).toInt()
                        if (CORNERS[k] != i) {
                            QuadCell(floor)
                        } else {
                            val m = (tMs % 400).toInt()
                            val a = if (m < 120) m / 120f else 1f - (m - 120) / 280f
                            QuadCell(lerp(floor, 1f, a))
                        }
                    }
                    else -> QuadCell(floor)
                }
            }
        }
    }

    /** The still frame drawn under reduce motion (and for idle states). */
    fun rest(mode: QuadMode, i: Int): QuadCell {
        val r = i / 3
        val c = i % 3
        val floor = floorOf(i)
        return when (mode) {
            QuadMode.Think -> QuadCell(if (i == CENTRE || i == 0 || i == 8) 1f else floor)
            QuadMode.Run -> if (c == 1) QuadCell(1f) else QuadCell(floor, scale = 0.86f)
            QuadMode.Write -> QuadCell(if (SNAKE.indexOf(i) < 5) 1f else floor)
            QuadMode.Agent -> QuadCell(if (i == CENTRE) 0.34f else ringAlpha(i, 2))
            QuadMode.Wait -> QuadCell(0.8f)
            QuadMode.Compact -> compactCell(i, r, c, 0.5f)
            QuadMode.Connect -> QuadCell(
                when (i) {
                    CENTRE -> 0.12f
                    0 -> 1f
                    else -> floor
                },
            )
        }
    }

    /** Brand mark: [enterMs] since the entrance started (cells stagger in by diagonal, then one Think ripple). */
    fun mark(i: Int, enterMs: Long): QuadCell {
        val r = i / 3
        val c = i % 3
        val lit = i in MARK
        val base = if (lit) 1f else 0.16f
        val start = (r + c) * 30L
        val local = ((enterMs - start).coerceAtLeast(0)) / 280f
        val e = fastOutSlowIn(local.coerceIn(0f, 1f))
        val rippleStart = 4 * 30L + 280L
        val ripple = if (enterMs > rippleStart && enterMs < rippleStart + 2000) {
            val q = (enterMs - rippleStart) / 1600f - (r + c) / 4f * 0.55f
            if (q < 0f) 0f else bump(q)
        } else {
            0f
        }
        val a = if (lit) base - 0.25f * ripple else base + 0.4f * ripple
        return QuadCell(alpha = a * e, scale = 0.6f + 0.4f * e)
    }

    const val MARK_DURATION_MS = 4 * 30L + 280L + 2000L

    private fun ringAlpha(i: Int, head: Int): Float {
        val pos = RING.indexOf(i)
        return when ((head - pos + 8) % 8) {
            0 -> 1f
            1 -> 0.62f
            2 -> 0.36f
            else -> floorOf(i)
        }
    }

    private fun compactCell(i: Int, r: Int, c: Int, k: Float): QuadCell = if (i == CENTRE) {
        QuadCell(lerp(0.6f, 1f, k), scale = lerp(1f, 1.18f, k))
    } else {
        QuadCell(lerp(0.7f, 0.25f, k), scale = lerp(1f, 0.55f, k), dx = (1 - c) * 0.8f * k, dy = (1 - r) * 0.8f * k)
    }

    /** Keyframes 0:0, 0.3:1, 0.7:0, 1:0, FastOutSlowIn per segment. */
    fun bump(x: Float): Float = when {
        x < 0.3f -> fastOutSlowIn(x / 0.3f)
        x < 0.7f -> 1f - fastOutSlowIn((x - 0.3f) / 0.4f)
        else -> 0f
    }

    /** 0 → 1 → 0 over one period, eased. */
    fun tri(p: Float): Float = if (p < 0.5f) fastOutSlowIn(p * 2f) else fastOutSlowIn((1f - p) * 2f)

    fun frac(x: Float): Float = x - kotlin.math.floor(x)

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun fastOutSlowIn(x: Float): Float = cubicBezier(0.4f, 0f, 0.2f, 1f, x)

    private fun linearOutSlowIn(x: Float): Float = cubicBezier(0f, 0f, 0.2f, 1f, x)

    private fun easeInOut(x: Float): Float = cubicBezier(0.45f, 0f, 0.55f, 1f, x)

    /** y of a CSS-style cubic bezier at x (bisection; inputs are tiny). */
    fun cubicBezier(x1: Float, y1: Float, x2: Float, y2: Float, x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        var lo = 0f
        var hi = 1f
        var t = x
        repeat(20) {
            val bx = bez(x1, x2, t)
            if (abs(bx - x) < 1e-4f) return bez(y1, y2, t)
            if (bx < x) lo = t else hi = t
            t = (lo + hi) / 2f
        }
        return bez(y1, y2, t)
    }

    private fun bez(a: Float, b: Float, t: Float): Float {
        val u = 1f - t
        return 3f * u * u * t * a + 3f * u * t * t * b + t * t * t
    }
}
