package opensec.pibolt.app.session

/**
 * What the foreground notification says while sessions work: one line per busy session, and a title naming the only
 * one or counting them. Pure, for [SessionService] and tests.
 */
object RunningSummary {
    /** One live session as the notification sees it. */
    data class Entry(
        val name: String,
        val local: Boolean,
        val running: Boolean,
        val waiting: Boolean,
        val compacting: Boolean,
        val connected: Boolean,
        /** Background subagents run (after the turn that started them, too). */
        val subagents: Boolean = false,
        /** A `!` command runs. */
        val shell: Boolean = false,
    ) {
        /** Keeps the service (and so the process and its engines) up. */
        val busy: Boolean get() = running || waiting || compacting || subagents || shell

        val status: String
            get() = when {
                !connected -> if (local) "Restarting the engine…" else "Reconnecting…"
                waiting -> "Waiting for your answer"
                compacting -> "Compacting context"
                running -> "Working"
                subagents -> "Background agents working"
                shell -> "Running a command"
                else -> "Working"
            }
    }

    data class Summary(val title: String, val text: String, val lines: List<String>)

    /** Notification kinds that exist once per session. */
    const val SETTLED = 0
    const val ANSWER = 1

    /**
     * A notification id per session ([key]: its hostId) and [kind], so two sessions' "finished" or "needs an answer"
     * notifications stand side by side. Clear of the app's fixed ids (below 100).
     */
    fun notificationId(key: String, kind: Int): Int = 100 + (key.hashCode() and 0x0FFFFFFF) % 1_000_000 * 2 + kind

    /** True when [id] is a per-session notification of [kind] ([notificationId]). */
    fun isKind(id: Int, kind: Int): Boolean = id in 100 until 100 + 2_000_000 && (id - 100) % 2 == kind

    /** Null when nothing is busy (the service stops). */
    fun of(entries: List<Entry>, appName: String): Summary? {
        val busy = entries.filter { it.busy }
        if (busy.isEmpty()) return null
        if (busy.size == 1) return Summary(busy[0].name, busy[0].status, emptyList())
        val waiting = busy.count { it.waiting }
        val text = if (waiting > 0) "${busy.size} sessions · $waiting waiting for your answer" else "${busy.size} sessions working"
        return Summary(appName, text, busy.map { "${it.name}: ${it.status}" })
    }
}
