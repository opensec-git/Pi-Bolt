package opensec.pibolt.app.ui.session

import android.os.SystemClock
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.protocol.session.SessionState
import opensec.pibolt.protocol.session.ThinkingItem
import opensec.pibolt.protocol.session.UserItem
import java.util.WeakHashMap

/**
 * UI memory of one session that outlives the screen: turn and thinking durations measured on this phone, which
 * folds and cards the user opened, and which rows were already shown (only new rows of the live turn animate).
 */
class SessionMemory {
    /** Uptime when the current run started, or 0 when idle. */
    val runStart = mutableLongStateOf(0L)

    /** Uptime when the last run settled: the finished turn keeps animating briefly while it folds. */
    val settledAt = mutableLongStateOf(0L)

    val turnDurations = mutableStateMapOf<String, Long>()
    val thinkStart = HashMap<String, Long>()
    val thinkDurations = mutableStateMapOf<String, Long>()

    /** Folded turns the user opened. */
    val openFolds = mutableStateMapOf<String, Boolean>()

    /** Tool cards: true open, false closed; absent = automatic (open while running in the live turn). */
    val toolCards = mutableStateMapOf<String, Boolean>()

    /** Thinking blocks the user toggled away from the default. */
    val thinkingToggled = mutableStateMapOf<String, Boolean>()

    /** Dialog the user swiped away without answering (it stays pending). */
    val hiddenDialog = mutableStateOf<String?>(null)

    /** Uptime when each dialog was first shown, for its countdown. */
    val dialogShownAt = HashMap<String, Long>()

    val seen = HashSet<String>()
    var primed = false

    private var wasRunning = false

    /** Record timings from a newly published state. Cheap: looks at flags and the short streaming list only. */
    fun observe(state: SessionState) {
        val now = SystemClock.uptimeMillis()
        val running = state.isRunning
        if (running && !wasRunning) {
            runStart.longValue = now
        } else if (!running && wasRunning) {
            val start = runStart.longValue
            if (start > 0) lastTurnKey(state)?.let { turnDurations[it] = now - start }
            runStart.longValue = 0L
            settledAt.longValue = now
        }
        wasRunning = running
        for (item in state.streaming) {
            if (item is ThinkingItem) {
                if (item.streaming) {
                    thinkStart.getOrPut(item.key) { now }
                } else if (item.key !in thinkDurations) {
                    thinkStart[item.key]?.let { thinkDurations[item.key] = now - it }
                }
            }
        }
    }

    private fun lastTurnKey(state: SessionState): String? {
        val items = state.items
        for (i in items.indices.reversed()) {
            val item = items[i]
            if (item is UserItem) return item.key
        }
        return items.firstOrNull()?.let { "t:${it.key}" }
    }

    companion object {
        private val all = WeakHashMap<SessionController, SessionMemory>()

        fun of(controller: SessionController): SessionMemory = synchronized(all) { all.getOrPut(controller) { SessionMemory() } }
    }
}
