package opensec.pibolt.app

import opensec.pibolt.app.data.ProjectMeta
import opensec.pibolt.app.data.ProjectsCodec
import opensec.pibolt.app.data.ProjectsFile
import opensec.pibolt.app.engine.SessionIndex
import opensec.pibolt.app.engine.SessionSummary
import opensec.pibolt.app.ui.projects.ProjectEntry
import opensec.pibolt.app.ui.projects.ProjectRules
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContinueTest {
    private fun session(file: String, at: Long) = SessionSummary(file, file, null, "prompt $file", at, 2)

    private fun project(name: String, last: Long?, pinned: Boolean = false) =
        ProjectEntry(name, "/p/$name", ProjectMeta(pinned = pinned), last?.let { session("$name.jsonl", it) }, if (last == null) 0 else 1)

    @Test
    fun continueIsNewestFirstWithTheLiveProjectOnTop() {
        val projects = listOf(project("a", 100), project("b", 300), project("c", null), project("d", 200, pinned = true), project("e", 50))
        assertEquals(listOf("b", "d", "a"), ProjectRules.continueList(projects, liveProject = null).map { it.name })
        assertEquals(listOf("e", "b", "d"), ProjectRules.continueList(projects, liveProject = "e").map { it.name })
        // A live project without sessions has nothing to continue.
        assertEquals(listOf("b", "d", "a"), ProjectRules.continueList(projects, liveProject = "c").map { it.name })
        assertEquals(listOf("b", "d", "a", "e"), ProjectRules.continueList(projects, null, limit = 10).map { it.name })
        assertEquals(emptyList(), ProjectRules.continueList(emptyList(), null))
    }

    @Test
    fun unreadNeedsAWriteAfterTheLastLook() {
        val s = session("x", 10_000)
        assertFalse(ProjectRules.unread(s, null), "never seen here: no dot")
        assertFalse(ProjectRules.unread(s, 10_000))
        assertFalse(ProjectRules.unread(s, 10_000 - ProjectRules.UNREAD_SLACK_MS), "a write right after the look")
        assertTrue(ProjectRules.unread(s, 10_000 - ProjectRules.UNREAD_SLACK_MS - 1))
    }

    @Test
    fun seenTimesRoundTrip() {
        val f = ProjectsFile(mapOf("a" to ProjectMeta(pinned = true)), providerPromptSeen = true, seen = mapOf("/s/1.jsonl" to 42L))
        assertEquals(f, ProjectsCodec.decode(ProjectsCodec.encode(f)))
        assertEquals(emptyMap(), ProjectsCodec.decode("""{"seen":{"x":"nope"}}""").seen)
    }

    @Test
    fun monogramsAndMeta() {
        assertEquals("CP", ProjectRules.monogram("calc-parser"))
        assertEquals("NO", ProjectRules.monogram("notes"))
        assertEquals("WD", ProjectRules.monogram("web dashboard v2"))
        assertEquals("?", ProjectRules.monogram(""))
        val now = 10L * 3_600_000
        assertEquals("No sessions yet", ProjectRules.projectMeta(project("c", null), now))
        assertEquals("1 session · 2 h ago", ProjectRules.projectMeta(project("a", now - 2 * 3_600_000), now))
    }

    @Test
    fun subagentSessionsAreNeverListed() {
        val agentDir = Files.createTempDirectory("agent").toFile()
        val project = Files.createTempDirectory("proj").toFile()
        try {
            val index = SessionIndex(agentDir)
            val dir = index.sessionDir(project.absolutePath).apply { mkdirs() }
            fun write(name: String, vararg lines: String) = File(dir, name).writeText(lines.joinToString("\n", postfix = "\n"))
            val user = """{"type":"message","id":"m","parentId":null,"timestamp":"t","message":{"role":"user","content":"hello"}}"""
            write("1_a.jsonl", """{"type":"session","version":3,"id":"a","timestamp":"t","cwd":"${project.path}"}""", user)
            // A fork: has a parent, but a user's name. Listed.
            write("2_b.jsonl", """{"type":"session","version":3,"id":"b","timestamp":"t","cwd":"x","parentSession":"/p.jsonl"}""", """{"type":"session_info","name":"bug#1 fix"}""", user)
            // Spawned agent (UUID id) and workflow agent ("wf-agent-3" cut to 8). Not listed.
            write("3_c.jsonl", """{"type":"session","version":3,"id":"c","timestamp":"t","cwd":"x","parentSession":"/p.jsonl"}""", """{"type":"session_info","name":"Explore#05353d0b"}""", user)
            write("4_d.jsonl", """{"type":"session","version":3,"id":"d","timestamp":"t","cwd":"x","parentSession":"/p.jsonl"}""", """{"type":"session_info","name":"reviewer#wf-agent"}""", user)
            assertEquals(setOf("a", "b"), index.list(project).map { it.id }.toSet())
        } finally {
            agentDir.deleteRecursively()
            project.deleteRecursively()
        }
    }
}
