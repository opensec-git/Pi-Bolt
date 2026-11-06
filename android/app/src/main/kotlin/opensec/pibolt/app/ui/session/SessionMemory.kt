package opensec.pibolt.app.ui.session

import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import opensec.pibolt.protocol.session.SessionState
import opensec.pibolt.protocol.session.ThinkingItem
import opensec.pibolt.protocol.session.ToolItem
import opensec.pibolt.protocol.session.ToolStatus
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

    /** Tool groups ("Read 3 files") the user opened. */
    val openGroups = mutableStateMapOf<String, Boolean>()

    /** Uptime when a fold or group was opened by the user: its rows stagger in once, shortly after. */
    val openedAt = HashMap<String, Long>()

    /** Uptime when each tool was first seen running on this phone, for its elapsed time. */
    val toolStart = HashMap<String, Long>()

    /** The prompt sent from this phone that the host has not echoed yet. */
    val pending = mutableStateOf<PendingSend?>(null)

    /** Echoed user messages and the "Sending" rows they replaced (message key to row key). */
    val aliases = mutableStateMapOf<String, String>()

    /**
     * Thumbnails of images sent from this phone, by row key: the pending row's key, which its echo takes over, or the
     * user message's key for a steer or follow-up. Kept in memory for the session only.
     */
    val localImages = mutableStateMapOf<String, List<ImageBitmap>>()

    /** Images of steers and follow-ups not yet echoed: matched to their user message by text. */
    private val unclaimed = ArrayList<Pair<String, List<ImageBitmap>>>()
    private var lastUserSeen: String? = null

    /** Bumped on every send from this phone: the transcript jumps to the bottom. */
    val sends = mutableIntStateOf(0)

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
        pending.value?.let { p ->
            val last = PendingSend.lastUserKey(state.items)
            if (PendingSend.resolved(p, last, settledAt.longValue, running, now)) {
                // The echo takes over the "Sending" row's place in the list instead of animating in again.
                if (last != null && last != p.lastUserKey) aliases[last] = p.rowKey
                pending.value = null
            }
        }
        if (running) noteRunningTools(state, now)
        if (unclaimed.isNotEmpty()) claimImages(state)
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

    /** Running tools sit at the end of the live turn: look back a little way, not through the whole history. */
    private fun noteRunningTools(state: SessionState, now: Long) {
        val items = state.items
        var looked = 0
        for (i in items.indices.reversed()) {
            val item = items[i]
            if (item is UserItem || ++looked > 64) break
            if (item is ToolItem && item.status == ToolStatus.Running) toolStart.getOrPut(item.key) { now }
        }
    }

    /**
     * Records a send from this phone and asks the transcript to jump down. An idle prompt shows at once as "Sending";
     * the [thumbs] of its images stay with that row (and its echo).
     */
    fun sent(text: String, state: SessionState, running: Boolean, thumbs: List<ImageBitmap> = emptyList()) {
        sends.intValue++
        if (PendingSend.eligible(text, running, thumbs.size)) {
            val p = PendingSend(nextPendingId++, text, PendingSend.lastUserKey(state.items), SystemClock.uptimeMillis(), thumbs.size)
            if (thumbs.isNotEmpty()) localImages[p.rowKey] = thumbs
            pending.value = p
        } else if (thumbs.isNotEmpty()) {
            lastUserSeen = PendingSend.lastUserKey(state.items)
            unclaimed += text.trim() to thumbs
            if (unclaimed.size > 8) unclaimed.removeAt(0)
        }
    }

    /** A new user message with images and the text of an unclaimed send takes that send's thumbnails. */
    private fun claimImages(state: SessionState) {
        val last = PendingSend.lastUserKey(state.items)
        if (last == lastUserSeen) return
        lastUserSeen = last
        val item = state.items.lastOrNull { it is UserItem && it.key == last } as? UserItem ?: return
        if (item.imageCount == 0) return
        val i = unclaimed.indexOfFirst { it.first == item.text.trim() }
        if (i >= 0) localImages[item.key] = unclaimed.removeAt(i).second
    }

    private var nextPendingId = 1L

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
