package opensec.pibolt.app.engine

import android.os.Process
import android.util.Log
import opensec.pibolt.protocol.backend.ProcessKiller
import java.io.File

/**
 * Kills engine process trees through /proc. An app sees its own processes there. Pi starts tool commands detached
 * (`setsid`), so they are not in the engine's process group: the tree is followed by parent pid instead, and children
 * whose engine already ended (re-parented to init) are found as this app's processes with parent 1.
 */
class ProcessReaper(private val proc: File = File("/proc"), private val myUid: Int = Process.myUid(), private val myPid: Int = Process.myPid()) : ProcessKiller {
    private data class Info(val pid: Int, val ppid: Int, val uid: Int)

    override fun killTree(pid: Long) {
        val all = snapshot()
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
        kill(snapshot().filter { it.ppid == 1 && it.uid == myUid && it.pid != myPid }.map { it.pid })
    }

    private fun kill(pids: List<Int>) {
        if (pids.isEmpty()) return
        for (p in pids) Process.sendSignal(p, Process.SIGNAL_KILL)
        Log.i(LocalEngine.TAG, "killed ${pids.size} engine process(es)")
    }

    private fun snapshot(): List<Info> = proc.listFiles().orEmpty().mapNotNull { dir ->
        val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
        runCatching {
            val status = File(dir, "status").readLines()
            fun field(name: String) = status.firstOrNull { it.startsWith("$name:") }?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()
            Info(pid, field("PPid") ?: return@runCatching null, field("Uid") ?: return@runCatching null)
        }.getOrNull()
    }
}
