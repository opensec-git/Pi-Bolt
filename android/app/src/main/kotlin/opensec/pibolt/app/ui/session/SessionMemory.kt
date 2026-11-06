package opensec.pibolt.app.ui.session

import android.os.SystemClock
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import opensec.pibolt.protocol.session.SessionState
import opensec.pibolt.protocol.session.ThinkingItem
import opensec.pibolt.protocol.session.UserItem

/**
 * UI memory of one session that outlives the screen: turn and thinking durations measured on this phone, which
 * folds and cards the user opened, and which rows were already shown (only new rows of the live turn animate).
 */
class SessionMemory(private val sessionId: String?) {
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

    private var attached: StateFlow<SessionState>? = null
    private var attachJob: Job? = null

    /**
     * Follows [states] in [scope] (the app's, not the screen's), so a run's duration is recorded even when the session
     * screen leaves during it (back to the computer list, activity recreated). Attaching to the same flow again is a
     * no-op; states of another Pi session on the same connection are ignored.
     */
    fun attach(states: StateFlow<SessionState>, scope: CoroutineScope) {
        if (attached === states && attachJob?.isActive == true) return
        attachJob?.cancel()
        attached = states
        attachJob = scope.launch {
            states.collect { if (it.sessionId == sessionId) observe(it) }
        }
    }

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
        // Keyed by computer and Pi session, so a reconnect (a new controller) keeps the measured durations while a
        // different session on the same computer starts clean. The few most recent sessions are kept.
        private val all = object : LinkedHashMap<String, SessionMemory>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SessionMemory>?): Boolean = size > 8
        }

        fun of(hostId: String, sessionId: String?): SessionMemory =
            synchronized(all) { all.getOrPut("$hostId/${sessionId.orEmpty()}") { SessionMemory(sessionId) } }
    }
}
