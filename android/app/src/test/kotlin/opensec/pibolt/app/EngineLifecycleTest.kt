package opensec.pibolt.app

import opensec.pibolt.app.engine.ProcessReaper
import opensec.pibolt.app.engine.ZoneInfo
import opensec.pibolt.app.engine.setupFailure
import opensec.pibolt.app.session.RunMarks
import java.io.DataInputStream
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The engine's processes and environment across sessions, restarts and time zones. */
class EngineLifecycleTest {
    private val root: File = Files.createTempDirectory("engine-lifecycle").toFile()

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private val myUid = 10_123
    private val myPid = 4_000

    /** A fake /proc entry: [pid] with parent [ppid], of [uid], with [env] as its environment. */
    private fun process(pid: Int, ppid: Int, uid: Int = myUid, env: Map<String, String> = emptyMap()) {
        val dir = File(root, "proc/$pid").apply { mkdirs() }
        File(dir, "status").writeText("Name:\tnode\nPPid:\t$ppid\nUid:\t$uid\t$uid\t$uid\t$uid\n")
        File(dir, "environ").writeBytes(env.entries.joinToString("") { "${it.key}=${it.value}\u0000" }.toByteArray())
    }

    private fun reaper(killed: MutableList<Int>) = ProcessReaper(File(root, "proc"), myUid, myPid, { killed += it }, {})

    @Test
    fun anEngineStoppingLeavesAnotherSessionsDevServerRunning() {
        val killed = ArrayList<Int>()
        val reaper = reaper(killed)
        // Session A's engine is running; its dev server was started in the background (adopted by init).
        val live = reaper.track()
        reaper.attach(live, FakeProcess(alive = true))
        process(5_001, 1, env = mapOf("HOME" to "/h", ProcessReaper.ENV to live))
        // Session B's engine just stopped: its own background job is left.
        val gone = reaper.track()
        reaper.attach(gone, FakeProcess(alive = false))
        process(5_002, 1, env = mapOf(ProcessReaper.ENV to gone))
        process(5_003, 5_002, env = mapOf(ProcessReaper.ENV to gone))
        // A terminal's job (no engine's mark) and another app's process.
        process(5_004, 1)
        process(5_005, 1, uid = 10_999, env = mapOf(ProcessReaper.ENV to gone))
        process(myPid, 600, env = mapOf(ProcessReaper.ENV to gone))
        reaper.reapOrphans()
        assertEquals(listOf(5_002, 5_003), killed.sorted())
    }

    @Test
    fun aNewAppProcessStopsWhatTheEnginesOfTheKilledOneLeft() {
        val killed = ArrayList<Int>()
        // Marked by an engine of a previous process: no engine of this one has that token.
        process(6_001, 1, env = mapOf(ProcessReaper.ENV to "from-before"))
        process(6_002, 1)
        reaper(killed).reapOrphans()
        assertEquals(listOf(6_001), killed)
    }

    @Test
    fun anEngineStillStartingIsNotReaped() {
        val killed = ArrayList<Int>()
        val reaper = reaper(killed)
        val starting = reaper.track()
        process(7_001, myPid, env = mapOf(ProcessReaper.ENV to starting))
        reaper.reapOrphans()
        assertTrue(killed.isEmpty())
        reaper.untrack(starting)
        reaper.reapOrphans()
        assertEquals(listOf(7_001), killed)
    }

    @Test
    fun theMarkIsReadFromTheEnvironment() {
        val file = File(root, "environ")
        file.writeBytes("A=1\u0000PIBOLT_ENGINEX=no\u0000PIBOLT_ENGINE=abc-1\u0000B=2\u0000".toByteArray())
        assertEquals("abc-1", ProcessReaper.tokenIn(file))
        file.writeBytes("PIBOLT_ENGINE=last".toByteArray())
        assertEquals("last", ProcessReaper.tokenIn(file))
        file.writeBytes(ByteArray(0))
        assertNull(ProcessReaper.tokenIn(file))
        assertNull(ProcessReaper.tokenIn(File(root, "missing")))
    }

    private class FakeProcess(private val alive: Boolean) : java.lang.Process() {
        override fun getOutputStream() = java.io.OutputStream.nullOutputStream()
        override fun getInputStream() = java.io.InputStream.nullInputStream()
        override fun getErrorStream() = java.io.InputStream.nullInputStream()
        override fun waitFor() = 0
        override fun exitValue() = if (alive) throw IllegalThreadStateException() else 0
        override fun destroy() = Unit
        override fun isAlive() = alive
    }

    // Time zones: the pack has no tz database, so TZ=Asia/Kolkata gave UTC in `date`, Python and git.

    /** The offset a TZif file gives at [at] from its explicit transitions (version 2 data). */
    private fun offsetAt(bytes: ByteArray, at: Instant): Int {
        val input = DataInputStream(bytes.inputStream())
        fun header(): IntArray {
            val magic = ByteArray(5).also(input::readFully)
            assertEquals("TZif2", String(magic))
            input.skipBytes(15)
            return IntArray(6) { input.readInt() }
        }
        val v1 = header()
        input.skipBytes(v1[3] * 5 + v1[4] * 6 + v1[5] + v1[2] * 8 + v1[1] + v1[0])
        val (_, _, _, timecnt, typecnt, charcnt) = header().toList()
        val times = LongArray(timecnt) { input.readLong() }
        val indices = IntArray(timecnt) { input.readUnsignedByte() }
        val offsets = IntArray(typecnt) { input.readInt().also { input.skipBytes(2) } }
        input.skipBytes(charcnt)
        val last = times.indexOfLast { it <= at.epochSecond }
        return if (last < 0) offsets[0] else offsets[indices[last]]
    }

    private operator fun <T> List<T>.component6(): T = this[5]

    private fun footer(bytes: ByteArray): String = String(bytes).trimEnd('\n').substringAfterLast('\n')

    @Test
    fun thePhonesTimeZoneIsWrittenForGlibcAndPython() {
        val dir = File(root, "zoneinfo")
        assertTrue(ZoneInfo.ensure(dir, "Asia/Kolkata"))
        val file = File(dir, "Asia/Kolkata")
        val bytes = file.readBytes()
        assertEquals(19_800, offsetAt(bytes, Instant.parse("2026-11-07T12:00:00Z")))
        assertEquals("IST-5:30", footer(bytes))
        // Unchanged rules: not written again.
        val written = file.lastModified()
        file.setLastModified(written - 60_000)
        assertTrue(ZoneInfo.ensure(dir, "Asia/Kolkata"))
        assertEquals(written - 60_000, file.lastModified())
        // Ids that are not paths in the directory.
        assertFalse(ZoneInfo.ensure(dir, "../etc/passwd"))
        assertFalse(ZoneInfo.ensure(dir, "/abs"))
        assertFalse(ZoneInfo.ensure(dir, "Not/AZone"))
    }

    @Test
    fun transitionsMatchAndroidsRules() {
        for (id in listOf("America/New_York", "Europe/Berlin", "Australia/Sydney", "America/Sao_Paulo", "Asia/Tehran", "Europe/London")) {
            val zone = ZoneId.of(id)
            val bytes = ZoneInfo.tzif(zone)
            var at = Instant.parse("1950-01-01T00:00:00Z")
            while (at.isBefore(Instant.parse("2037-12-01T00:00:00Z"))) {
                val expected = zone.rules.getOffset(at).totalSeconds
                assertEquals(expected, offsetAt(bytes, at), "$id at $at")
                at = at.plusSeconds(86_400L * 13 + 3_607)
            }
        }
    }

    @Test
    fun theFooterIsTheSameAsTzdatas() {
        // Where the host has a tz database, its footers are what glibc reads for the years after the last transition.
        val system = File("/usr/share/zoneinfo")
        val expected = mapOf(
            "America/New_York" to "EST5EDT,M3.2.0,M11.1.0",
            "Europe/Berlin" to "CET-1CEST,M3.5.0,M10.5.0/3",
            "Australia/Sydney" to "AEST-10AEDT,M10.1.0,M4.1.0/3",
            "Asia/Kolkata" to "IST-5:30",
            "America/Sao_Paulo" to "<-03>3",
            "Pacific/Auckland" to "NZST-12NZDT,M9.5.0,M4.1.0/3",
        )
        // The rules must match; designations ("BRT" or "<-03>") are the platform's names and only show in `date +%Z`.
        fun rules(posix: String?) = posix?.replace(Regex("<[^>]*>|[A-Za-z]{3,}"), "N")
        for ((id, posix) in expected) {
            assertEquals(rules(posix), rules(ZoneInfo.posix(ZoneId.of(id))), id)
            val host = File(system, id)
            if (host.isFile) assertEquals(rules(footer(host.readBytes())), rules(ZoneInfo.posix(ZoneId.of(id))), "$id against $host")
        }
        assertEquals("IST-5:30", ZoneInfo.posix(ZoneId.of("Asia/Kolkata")))
        assertEquals("+0530", ZoneInfo.numeric(19_800))
        assertEquals("-03", ZoneInfo.numeric(-10_800))
    }

    @Test
    fun aFullDiskDuringSetupSaysSo() {
        val full = java.io.IOException("write failed: ENOSPC (No space left on device)")
        assertEquals("Not enough free storage to set up the engine. Free some space, then retry.", setupFailure(RuntimeException("copy", full)))
        assertEquals("Could not set up the engine", setupFailure(java.io.IOException("cannot remove the old lib/python3.14")))
    }

    @Test
    fun aTurnCutOffByAProcessDeathIsToldOnTheNextLaunch() {
        val file = File(root, "running-turns.json")
        val marks = RunMarks(file)
        val notes = RunMarks.Run("local:/p/notes", "notes", "notes", "/s/notes.jsonl", chat = false)
        val chat = RunMarks.Run("local:/h/.pibolt/chats", null, "Chat", null, chat = true)
        marks.mark(notes)
        marks.mark(chat)
        // The chat's turn ended normally; then the process was killed during the notes turn.
        marks.unmark(chat.key)
        val next = RunMarks(file)
        assertEquals(listOf(notes), next.takeAll())
        // Said once: the launch after that finds nothing.
        assertEquals(emptyList(), RunMarks(file).takeAll())
        // A session stopped on purpose during its turn is not reported.
        marks.mark(notes)
        marks.unmark(notes.key)
        assertEquals(emptyList(), RunMarks(file).takeAll())
        file.writeText("not json")
        assertEquals(emptyList(), RunMarks(file).takeAll())
    }
}
