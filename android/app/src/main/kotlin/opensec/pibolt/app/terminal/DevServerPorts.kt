package opensec.pibolt.app.terminal

import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Dev servers the app's own processes (terminal shells, the engine's tools) listen on, for the "Preview" chip.
 *
 * First choice: `/proc/net/tcp` and `tcp6`, LISTEN sockets owned by this app's uid ([ProcNetTcp]). Android's SELinux
 * policy keeps those tables from apps (`neverallow appdomain proc_net_tcp_udp`), so on a phone the read usually fails;
 * then, only while the app has child processes at all, the ports printed in the terminals plus the usual dev-server
 * ports are probed with a loopback connect. The probe cannot tell whose server answers: it is a hint for a preview of
 * localhost, never a permission check.
 */
class DevServerPorts(
    private val uid: Int = Process.myUid(),
    private val readTable: (String) -> String? = { path -> runCatching { File(path).readText() }.getOrNull() },
    private val hasChildProcesses: () -> Boolean = { ownProcessesBesides(Process.myPid(), uid) },
    private val mentionedPorts: () -> Collection<Int> = { emptyList() },
    private val probe: (Int) -> Boolean = ::loopbackAccepts,
) {
    /**
     * Neither table could be read. SELinux decides that once for the process (and logs a denial for every try), so they
     * are not read again.
     */
    @Volatile
    private var tablesDenied = false

    /** Listening ports now, sorted. Blocking (file reads, connects): call off the main thread. */
    fun scan(): List<Int> {
        if (!tablesDenied) {
            val tcp = readTable("/proc/net/tcp")
            val tcp6 = readTable("/proc/net/tcp6")
            if (tcp != null || tcp6 != null) return ProcNetTcp.portsOf(uid, tcp.orEmpty(), tcp6.orEmpty())
            tablesDenied = true
        }
        if (!hasChildProcesses()) return emptyList()
        return (mentionedPorts() + COMMON_DEV_PORTS).distinct().filter(probe).sorted()
    }

    /** [scan] every [periodMs] while someone collects (a visible chip), stopped [STOP_AFTER_MS] after the last leaves. */
    fun watch(scope: CoroutineScope, periodMs: Long = PERIOD_MS): StateFlow<List<Int>> = flow {
        while (true) {
            emit(scan())
            delay(periodMs)
        }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), emptyList())

    companion object {
        const val PERIOD_MS = 3_000L
        private const val STOP_AFTER_MS = 5_000L
        private const val CONNECT_TIMEOUT_MS = 150

        /** Vite, Next/React/Express, Angular, Astro, Flask, Django, webpack, Wrangler, Jupyter, PHP and friends. */
        val COMMON_DEV_PORTS = listOf(3000, 3001, 4173, 4200, 4321, 5000, 5173, 5174, 8000, 8080, 8081, 8787, 8888, 9000)

        private val URL_PORT = Regex("""(?:localhost|127\.0\.0\.1|0\.0\.0\.0|\[::1?])\s*:\s*(\d{2,5})""")

        /** Ports in URLs like `http://localhost:5173/` or `127.0.0.1:8000` printed in [text]. */
        fun portsMentioned(text: CharSequence): List<Int> =
            URL_PORT.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull()?.takeIf { p -> p in 1..65535 } }.distinct().toList()

        fun loopbackAccepts(port: Int): Boolean = runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS) }
            true
        }.getOrDefault(false)

        /** Whether /proc shows a process of [uid] other than [myPid] (an app sees only its own processes there). */
        fun ownProcessesBesides(myPid: Int, uid: Int, proc: File = File("/proc")): Boolean = proc.listFiles().orEmpty().any { dir ->
            val pid = dir.name.toIntOrNull() ?: return@any false
            pid != myPid && runCatching {
                File(dir, "status").useLines { lines -> lines.firstOrNull { it.startsWith("Uid:") } }
                    ?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull() == uid
            }.getOrDefault(false)
        }
    }
}
