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
        /** Background shell jobs run (BackgroundJobs). */
        val jobs: Boolean = false,
    ) {
        /** Keeps the service (and so the process and its engines) up. */
        val busy: Boolean get() = running || waiting || compacting || subagents || shell || jobs

        val status: String
            get() = when {
                !connected -> if (local) "Restarting the engine…" else "Reconnecting…"
                waiting -> "Waiting for your answer"
                compacting -> "Compacting context"
                running -> "Working"
                subagents -> "Background agents working"
                shell -> "Running a command"
                jobs -> "Background jobs running"
                else -> "Working"
            }
    }

    data class Summary(val title: String, val text: String, val lines: List<String>)

    /**
     * A session's name in the notification: its project ([host]), and when another live session shares the project
     * ([shared]), what it was asked ([asked], shortened), so two runs in one project read apart.
     */
    fun name(host: String, asked: String?, shared: Boolean): String {
        val what = asked?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return host
        if (!shared) return host
        return "$host · " + if (what.length > ASKED_CHARS) what.take(ASKED_CHARS - 1).trimEnd() + "…" else what
    }

    private const val ASKED_CHARS = 32

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
