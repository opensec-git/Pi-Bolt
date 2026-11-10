package opensec.pibolt.app

import opensec.pibolt.app.engine.SessionIndex
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** Subagents' own sessions never show as sessions: not in a project's list, not as its newest session. */
class SubagentSessionListingTest {
    private fun line(json: String) = json + "\n"

    @Test
    fun customAgentNamesWithSpacesAreSubagentsToo() {
        val agentDir = Files.createTempDirectory("agent").toFile()
        val project = Files.createTempDirectory("project").toFile()
        val index = SessionIndex(agentDir)
        val dir = index.sessionDir(project.absolutePath).apply { mkdirs() }
        val parent = File(dir, "1_parent.jsonl").apply {
            writeText(line("""{"type":"session","id":"parent"}""") + line("""{"type":"message","message":{"role":"user","content":"hi"}}"""))
            setLastModified(1_000_000)
        }
        // Written after the parent's last message (a background agent that finished later): the newest file.
        File(dir, "2_child.jsonl").apply {
            writeText(
                line("""{"type":"session","id":"child","parentSession":"${parent.path}"}""") +
                    line("""{"type":"session_info","name":"Code Reviewer#1669f14e"}""") +
                    line("""{"type":"message","message":{"role":"user","content":"review"}}"""),
            )
            setLastModified(2_000_000)
        }
        // A fork the user named: it names its parent, but not with an agent's name.
        File(dir, "3_fork.jsonl").apply {
            writeText(
                line("""{"type":"session","id":"fork","parentSession":"${parent.path}"}""") +
                    line("""{"type":"session_info","name":"my fork"}""") +
                    line("""{"type":"message","message":{"role":"user","content":"hi"}}"""),
            )
            setLastModified(500_000)
        }
        assertEquals(listOf("parent", "fork"), index.list(project).map { it.id })
        assertEquals(parent.path, index.newest(project))
        agentDir.deleteRecursively()
        project.deleteRecursively()
    }
}
