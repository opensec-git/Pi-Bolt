package opensec.pibolt.app

import opensec.pibolt.app.engine.BackgroundJobs
import opensec.pibolt.app.engine.ChatSpace
import opensec.pibolt.app.session.RunningSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import opensec.pibolt.app.ui.session.toolLabel
import opensec.pibolt.protocol.session.ToolItem
import opensec.pibolt.protocol.session.ToolStatus
import java.io.File

class BackgroundJobsTest {
    private val json = """
        {"version":1,"enginePid":42,"jobs":[
          {"id":1,"name":"dev server","command":"npm run dev","cwd":"/p","pid":100,"status":"running","exitCode":null,"signal":null,"startedAt":1000,"endedAt":null,"bytes":12,"logFile":"/t/1.log"},
          {"id":2,"name":"tests","command":"npm test","cwd":"/p","pid":101,"status":"exited","exitCode":1,"signal":null,"startedAt":1000,"endedAt":66000,"bytes":0,"logFile":"/t/2.log"},
          {"id":3,"name":"watch","command":"tsc -w","cwd":"/p","pid":102,"status":"killed","exitCode":null,"signal":"SIGTERM","startedAt":0,"endedAt":5000,"bytes":0,"logFile":"/t/3.log"}
        ]}
    """.trimIndent()

    @Test
    fun parsesTheExtensionsJobsFile() {
        val jobs = BackgroundJobs.parse(json)
        assertEquals(listOf(1, 2, 3), jobs.map { it.id })
        assertTrue(jobs[0].running)
        assertEquals("Running", jobs[0].state)
        assertEquals("Exited 1", jobs[1].state)
        assertEquals("Stopped", jobs[2].state)
        assertEquals("1 running · 2 ended", BackgroundJobs.summary(jobs))
        assertEquals("1m 5s", BackgroundJobs.uptime(jobs[1], 0))
        assertEquals("9s", BackgroundJobs.uptime(jobs[0], 10_000))
    }

    @Test
    fun brokenFilesGiveNoJobs() {
        assertTrue(BackgroundJobs.parse("").isEmpty())
        assertTrue(BackgroundJobs.parse("{\"jobs\":3}").isEmpty())
        assertTrue(BackgroundJobs.read(File("/nonexistent/jobs.json")).isEmpty())
    }

    @Test
    fun stateFileIsPerEngine() {
        assertEquals(File("/tmp/pibolt-bg/77/jobs.json"), BackgroundJobs.stateFile(File("/tmp"), 77))
    }

    @Test
    fun chatsNeverGetTheBackgroundTools() {
        for (web in listOf(true, false)) {
            val tools = ChatSpace.chatTools(web)
            assertFalse(BackgroundJobs.TOOLS.any { it in tools })
            val args = ChatSpace.chatArgs(File("/agent"), web)
            val allow = args[args.indexOf("--tools") + 1].split(",")
            assertFalse(BackgroundJobs.TOOLS.any { it in allow })
        }
    }

    @Test
    fun runningJobsKeepTheNotification() {
        val e = RunningSummary.Entry("app", local = true, running = false, waiting = false, compacting = false, connected = true, jobs = true)
        assertTrue(e.busy)
        assertEquals("Background jobs running", e.status)
    }

    @Test
    fun toolRowsAreCompact() {
        fun item(name: String, args: JsonObject?, text: String = "") = ToolItem("tool:$name", name, name, args, argsText = text, status = ToolStatus.Done)
        val start = toolLabel(item("bash_background", buildJsonObject { put("command", "npm run dev\n") }))
        assertEquals("Started in the background" to "npm run dev", start.past to start.detail)
        assertEquals("dev server", toolLabel(item("bash_background", buildJsonObject { put("command", "npm run dev"); put("name", "dev server") })).detail)
        val read = toolLabel(item("bash_output", buildJsonObject { put("id", 2) }))
        assertEquals("Read output" to "#2", read.past to read.detail)
        assertEquals("#3", toolLabel(item("bash_kill", null, "{\"id\": 3")).detail)
    }
}
