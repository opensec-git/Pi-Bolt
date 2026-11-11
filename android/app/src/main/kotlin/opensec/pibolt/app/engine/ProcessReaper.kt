package opensec.pibolt.app.engine

import android.os.Process
import android.util.Log
import opensec.pibolt.protocol.backend.ProcessKiller
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Kills engine process trees through /proc. An app sees its own processes there. Pi starts tool commands detached
 * (`setsid`), so they are not in the engine's process group: the tree is followed by parent pid instead.
 *
 * What an engine leaves behind is found by its mark: every engine starts with [ENV] set to a token of its own ([track]),
 * which its tool commands inherit. After an engine ended, the processes still carrying its token (a background job,
 * adopted by init) are killed; those of engines still running are left alone, so one session's dev server survives
 * another session's engine stopping. A token this process never handed out belongs to a previous app process: its
 * engines are gone, so its leftovers go too. Processes without a token (the terminal's, the app's own git) are never
 * touched here.
 */
class ProcessReaper(
    private val proc: File = File("/proc"),
    private val myUid: Int = Process.myUid(),
    private val myPid: Int = Process.myPid(),
    private val signal: (Int) -> Unit = { Process.sendSignal(it, Process.SIGNAL_KILL) },
    private val log: (String) -> Unit = { Log.i(LocalEngine.TAG, it) },
) : ProcessKiller {
    internal data class Info(val pid: Int, val ppid: Int, val uid: Int, val token: String? = null)

    /** Engines started or starting, by token; null until the process exists. */
    private val engines = ConcurrentHashMap<String, Launch>()

    private class Launch {
        @Volatile
        var process: java.lang.Process? = null
    }

    /** A new engine's token, for its [ENV]: its processes count as live from now on. Call before starting it. */
    fun track(): String = UUID.randomUUID().toString().also { engines[it] = Launch() }

    /** The engine of [token] started as [process]. */
    fun attach(token: String, process: java.lang.Process) {
        engines[token]?.process = process
    }

    /** The engine of [token] did not start. */
    fun untrack(token: String) {
        engines.remove(token)
    }

    private fun live(token: String): Boolean {
        val entry = engines[token] ?: return false
        val process = entry.process ?: return true
        if (process.isAlive) return true
        engines.remove(token)
        return false
    }

    override fun killTree(pid: Long) {
        val all = snapshot(withTokens = false)
        val children = all.groupBy { it.ppid }
        val doomed = ArrayList<Int>()
        fun collect(p: Int) {
            doomed += p
            children[p]?.forEach { collect(it.pid) }
        }
        collect(pid.toInt())
        val byPid = all.associateBy { it.pid }
        kill(doomed.filter { it != myPid && byPid[it]?.uid == myUid })
    }

    override fun reapOrphans() {
        kill(strays(snapshot(withTokens = true), myUid, myPid, ::live))
    }

    private fun kill(pids: List<Int>) {
        if (pids.isEmpty()) return
        for (p in pids) signal(p)
        log("killed ${pids.size} engine process(es)")
    }

    private fun snapshot(withTokens: Boolean): List<Info> = proc.listFiles().orEmpty().mapNotNull { dir ->
        val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
        runCatching {
            val status = File(dir, "status").readLines()
            fun field(name: String) = status.firstOrNull { it.startsWith("$name:") }?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()
            val uid = field("Uid") ?: return@runCatching null
            // Only this app's processes can carry a token (and only theirs are readable).
            val token = if (withTokens && uid == myUid) tokenIn(File(dir, "environ")) else null
            Info(pid, field("PPid") ?: return@runCatching null, uid, token)
        }.getOrNull()
    }

    companion object {
        /** The variable that marks an engine's processes with its token. */
        const val ENV = "PIBOLT_ENGINE"

        private val PREFIX = "$ENV=".toByteArray()

        /** The token in a process's `environ` (NUL-separated), or null without one or when unreadable. */
        internal fun tokenIn(environ: File): String? {
            val bytes = runCatching { environ.readBytes() }.getOrNull() ?: return null
            var start = 0
            while (start < bytes.size) {
                var end = start
                while (end < bytes.size && bytes[end] != 0.toByte()) end++
                if (end - start > PREFIX.size && (PREFIX.indices).all { bytes[start + it] == PREFIX[it] }) {
                    return String(bytes, start + PREFIX.size, end - start - PREFIX.size, Charsets.UTF_8)
                }
                start = end + 1
            }
            return null
        }

        /** This app's processes marked by an engine that no longer runs ([live] says which do). */
        internal fun strays(all: List<Info>, myUid: Int, myPid: Int, live: (String) -> Boolean): List<Int> =
            all.filter { it.uid == myUid && it.pid != myPid && it.token != null && !live(it.token) }.map { it.pid }
    }
}
