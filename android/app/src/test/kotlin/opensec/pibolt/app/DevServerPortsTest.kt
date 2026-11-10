package opensec.pibolt.app

import opensec.pibolt.app.terminal.DevServerPorts
import opensec.pibolt.app.terminal.ProcNetTcp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DevServerPortsTest {
    private val header = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n"

    // Real lines from a phone: a Vite server of uid 10234 on 127.0.0.1:5173, one on 0.0.0.0:3000, a connection
    // (state 01) of the same uid, a LAN-bound listener and another app's listener.
    private val tcp = header + """
   0: 0100007F:1435 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10234        0 81234 1 0000000000000000 100 0 0 10 0
   1: 00000000:0BB8 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10234        0 81235 1 0000000000000000 100 0 0 10 0
   2: 0100007F:1435 0100007F:D2F0 01 00000000:00000000 00:00000000 00000000 10234        0 81236 1 0000000000000000 20 4 30 10 -1
   3: 0A01A8C0:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10234        0 81237 1 0000000000000000 100 0 0 10 0
   4: 0100007F:1F40 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10111        0 81238 1 0000000000000000 100 0 0 10 0
""".trimIndent()

    // Node listening on [::]:3000 (dual stack, also in tcp above), ::1:4321 and ::ffff:127.0.0.1:8080.
    private val tcp6 = header + """
   0: 00000000000000000000000000000000:0BB8 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000 10234        0 91234 1 0000000000000000 100 0 0 10 0
   1: 00000000000000000000000001000000:10E1 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000 10234        0 91235 1 0000000000000000 100 0 0 10 0
   2: 0000000000000000FFFF00000100007F:1F90 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000 10234        0 91236 1 0000000000000000 100 0 0 10 0
""".trimIndent()

    @Test
    fun parsesListenersOnLocalhostOnly() {
        val listeners = ProcNetTcp.parse(tcp)
        assertEquals(
            listOf(ProcNetTcp.Listener(5173, "127.0.0.1", 10234), ProcNetTcp.Listener(3000, "0.0.0.0", 10234), ProcNetTcp.Listener(8000, "127.0.0.1", 10111)),
            listeners,
        )
    }

    @Test
    fun decodesIpv6InHostOrderWords() {
        assertEquals("0:0:0:0:0:0:0:1", ProcNetTcp.decodeAddress("00000000000000000000000001000000"))
        assertEquals("0:0:0:0:0:ffff:7f00:1", ProcNetTcp.decodeAddress("0000000000000000FFFF00000100007F"))
        assertEquals("192.168.1.10", ProcNetTcp.decodeAddress("0A01A8C0"))
        assertEquals(null, ProcNetTcp.decodeAddress("XYZ"))
    }

    @Test
    fun portsOfTheAppUidMergeTcpAndTcp6() {
        assertEquals(listOf(3000, 4321, 5173, 8080), ProcNetTcp.portsOf(10234, tcp, tcp6))
        assertEquals(listOf(8000), ProcNetTcp.portsOf(10111, tcp, tcp6))
        assertEquals(emptyList(), ProcNetTcp.portsOf(10234, header, "garbage\n  9: nonsense"))
    }

    @Test
    fun readableTablesWinOverTheProbe() {
        var probed = false
        val ports = DevServerPorts(uid = 10234, readTable = { if (it.endsWith("tcp")) tcp else null }, hasChildProcesses = { true }, probe = { probed = true; true })
        assertEquals(listOf(3000, 5173), ports.scan())
        assertTrue(!probed)
    }

    @Test
    fun deniedTablesFallBackToProbingOnlyWithChildProcesses() {
        val open = setOf(5173, 4567)
        val none = DevServerPorts(uid = 1, readTable = { null }, hasChildProcesses = { false }, probe = { error("no probe without children") })
        assertEquals(emptyList(), none.scan())
        val some = DevServerPorts(uid = 1, readTable = { null }, hasChildProcesses = { true }, mentionedPorts = { listOf(4567) }, probe = { it in open })
        assertEquals(listOf(4567, 5173), some.scan())
    }

    @Test
    fun deniedTablesAreReadOnce() {
        var reads = 0
        val ports = DevServerPorts(uid = 1, readTable = { reads++; null }, hasChildProcesses = { true }, probe = { it == 5173 })
        repeat(3) { assertEquals(listOf(5173), ports.scan()) }
        // Both tables once; SELinux logs a denial for each try, every 3 s while the chip shows.
        assertEquals(2, reads)
    }

    @Test
    fun findsPortsInDevServerOutput() {
        val vite = "  VITE v5.4.0  ready in 312 ms\n\n  ➜  Local:   http://localhost:5173/\n  ➜  Network: use --host to expose\n"
        assertEquals(listOf(5173), DevServerPorts.portsMentioned(vite))
        assertEquals(listOf(8000, 3000, 4000), DevServerPorts.portsMentioned("Serving on http://127.0.0.1:8000 and 0.0.0.0:3000, [::1]:4000; localhost:99999 is not a port"))
    }
}
