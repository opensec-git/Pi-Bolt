package opensec.pibolt.app.session

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.R
import opensec.pibolt.app.background.AgentLocks
import opensec.pibolt.app.background.BackgroundSetup
import opensec.pibolt.app.background.WakePolicy
import opensec.pibolt.app.engine.BackgroundJobs
import opensec.pibolt.protocol.session.SessionState
import opensec.pibolt.protocol.session.UserItem
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the process (and so the connections, and every local engine process) alive while any session works or waits for
 * an answer, when the app is in the background. One foreground notification sums them up ([RunningSummary]); it stops
 * itself once none is busy. Without it a backgrounded app is cached and frozen, and the engines with it.
 *
 * While a session on this phone works it also holds a partial wake lock ([WakePolicy], [AgentLocks]), so the engines
 * keep the CPU with the screen off. Its foreground-service state keeps the app's network in Doze, App Standby and
 * Battery Saver (NetworkPolicyManager allows process states up to BOUND_FOREGROUND_SERVICE there).
 *
 * START_NOT_STICKY: the sessions live in this process. After a kill a restarted service would find none (the engines
 * died with the process; their session files stay and reopen from Home or a notification), so it would only stop again.
 */
class SessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var locks: AgentLocks
    private var collecting = false
    private var tick: Job? = null
    private var working = false

    /** The local sessions' states at the last update, to tell progress from a repeat. */
    private var seen: List<SessionState> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locks = AgentLocks(this)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as PiBoltApp
        val started = runCatching {
            // Local runs are work on this phone (the engine processes live as long as this service keeps the app up);
            // a remote one relays messages from a computer. Both types are declared: sessions of either kind may join.
            val type = if (Build.VERSION.SDK_INT < 34) 0 else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
            val live = live(app.sessions.value)
            val summary = RunningSummary.of(live.map { entry(it, live) }, getString(R.string.app_name))
            ServiceCompat.startForeground(
                this,
                Notifications.ONGOING_ID,
                Notifications.ongoing(this, summary?.title ?: getString(R.string.app_name), summary?.text ?: "Working", summary?.lines.orEmpty(), stoppable(live)),
                type,
            )
        }.isSuccess
        if (!started) {
            stop()
            return START_NOT_STICKY
        }
        if (collecting) return START_NOT_STICKY
        collecting = true
        scope.launch {
            app.sessions.flatMapLatest { list ->
                if (list.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    combine(list.map { c -> combine(c.state, c.link, c.hostName, c.shellRuns, c.actions) { _, _, _, _, _ -> c } }) { it.toList() }
                }
            }.collect { sessions -> onSessions(app, sessions) }
        }
        return START_NOT_STICKY
    }

    private fun onSessions(app: PiBoltApp, sessions: List<SessionController>) {
        val live = live(sessions)
        val states = live.filter { it.isLocal }.map { it.state.value }
        if (states.size != seen.size || states.indices.any { states[it] !== seen[it] }) locks.progress()
        seen = states
        val nowWorking = WakePolicy.working(live.map(::work))
        applyLocks(nowWorking)
        if (nowWorking && !app.isForeground) BackgroundSetup.noteBackgroundRun(this)
        val summary = RunningSummary.of(live.map { entry(it, live) }, getString(R.string.app_name))
        if (summary == null) {
            stop()
            return
        }
        Notifications.update(this, Notifications.ONGOING_ID, Notifications.ongoing(this, summary.title, summary.text, summary.lines, stoppable(live)))
    }

    /** Applies [WakePolicy] now, and again when it asks to (a renewal while working, the release after the work). */
    private fun applyLocks(working: Boolean) {
        this.working = working
        tick?.cancel()
        tick = null
        val next = locks.update(working) ?: return
        tick = scope.launch {
            delay(next)
            tick = null
            applyLocks(this@SessionService.working)
        }
    }

    /** The sessions that count; one whose link failed for good no longer counts as working. */
    private fun live(sessions: List<SessionController>): List<SessionController> = sessions.filter { !it.stopped && it.link.value !is LinkState.Failed }

    private fun stoppable(live: List<SessionController>): Boolean = live.any { it.isLocal && it.state.value.isRunning }

    /** A session as the notification sees it; [live]: every live session, to tell two of one project apart. */
    private fun entry(c: SessionController, live: List<SessionController>): RunningSummary.Entry {
        val s = c.state.value
        val shared = c.isLocal && live.count { it.isLocal && it.project == c.project } > 1
        val asked = s.sessionName ?: (s.items.lastOrNull { it is UserItem } as? UserItem)?.text
        return RunningSummary.Entry(
            name = RunningSummary.name(c.hostName.value, asked, shared),
            local = c.isLocal,
            running = runs(c),
            waiting = s.dialogs.isNotEmpty(),
            compacting = s.isCompacting,
            connected = c.link.value is LinkState.Connected,
            subagents = KeepAlive.subagents(s),
            shell = c.shellRuns.value > 0,
            jobs = BackgroundJobs.STATUS_KEY in s.statuses,
        )
    }

    /** A turn runs, or a prompt or compaction the user just sent is on its way to one ([SessionController.actions]). */
    private fun runs(c: SessionController): Boolean = c.state.value.isRunning || c.actions.value > 0

    /** A session as the wake lock policy sees it. */
    private fun work(c: SessionController): WakePolicy.Work {
        val s = c.state.value
        return WakePolicy.Work(
            local = c.isLocal,
            running = runs(c),
            waiting = s.dialogs.isNotEmpty(),
            compacting = s.isCompacting,
            subagents = KeepAlive.subagents(s) || BackgroundJobs.STATUS_KEY in s.statuses,
            shell = c.shellRuns.value > 0,
        )
    }

    private fun stop() {
        running.set(false)
        tick?.cancel()
        tick = null
        // The run just ended: a few seconds more for the engine's last writes and the finished alert, then the lock goes.
        locks.releaseSoon()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running.set(false)
        locks.releaseSoon()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PiBolt"

        private val running = AtomicBoolean(false)

        /**
         * Starts the service unless it runs. Callers start it at the user's tap where they can ([KeepAlive]): from the
         * background Android refuses (ForegroundServiceStartNotAllowedException), and the app then loses its network
         * seconds later. PiBoltApp tries again when an activity starts.
         */
        fun ensureRunning(context: Context) {
            if (!running.compareAndSet(false, true)) return
            val refused = runCatching { ContextCompat.startForegroundService(context, Intent(context, SessionService::class.java)) }.exceptionOrNull()
            if (refused != null) {
                running.set(false)
                Log.w(TAG, "the background service could not start; work continues only while the app is open", refused)
                // Android's battery restrictions are the usual reason: the run-in-background sheet asks to lift them.
                BackgroundSetup.noteBackgroundRun(context)
            }
        }
    }
}
