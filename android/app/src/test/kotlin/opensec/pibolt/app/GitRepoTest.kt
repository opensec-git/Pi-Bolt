package opensec.pibolt.app

import kotlinx.coroutines.runBlocking
import opensec.pibolt.app.git.Change
import opensec.pibolt.app.git.GitException
import opensec.pibolt.app.git.GitRepo
import opensec.pibolt.app.git.LineCount
import opensec.pibolt.app.git.ProcessGitRunner
import opensec.pibolt.app.git.github.GitCredentials
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [GitRepo] against the host's git (the phone runs the engine's git the same way): status, per-hunk staging, discard,
 * commit, log, show and branches on a scratch repository. Skipped where the host has no git.
 */
class GitRepoTest {
    private lateinit var root: File
    private lateinit var work: File
    private val hostGit = listOf("/usr/bin/git", "/usr/local/bin/git", "/opt/homebrew/bin/git").map(::File).firstOrNull { it.canExecute() }
    private var extraEnv: Map<String, String> = emptyMap()

    private fun env(): Map<String, String> = mapOf(
        "PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin"),
        "HOME" to File(root, "home").path,
        "GIT_CONFIG_NOSYSTEM" to "1",
        "GIT_AUTHOR_DATE" to "2026-01-01T00:00:00Z",
        "GIT_COMMITTER_DATE" to "2026-01-01T00:00:00Z",
    ) + extraEnv

    private val runner by lazy { ProcessGitRunner({ hostGit }, { env() }) }
    private val repo by lazy { GitRepo(runner, work) }

    @Before
    fun setUp() {
        assumeTrue("host git", hostGit != null)
        root = Files.createTempDirectory("pibolt-git").toFile()
        File(root, "home").mkdirs()
        work = File(root, "work").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        if (::root.isInitialized) root.deleteRecursively()
    }

    private fun write(name: String, text: String) = File(work, name).apply { parentFile?.mkdirs() }.writeText(text)

    @Test
    fun statusStageHunksCommitLogShowAndBranches() = runBlocking {
        assertFalse(repo.isRepo())
        repo.init()
        assertTrue(repo.isRepo())
        assertEquals(null to null, repo.identity())
        repo.setGlobalIdentity("Test User", "test@example.com")
        assertEquals("Test User" to "test@example.com", repo.identity())

        val lines = (1..30).map { "line $it" }
        write("a.txt", lines.joinToString("\n", postfix = "\n"))
        write("dir/b c.txt", "spaces\n")
        var status = repo.status()
        assertTrue(status.branch.initial)
        assertEquals("main", status.branch.head)
        assertEquals(setOf("a.txt", "dir/b c.txt"), status.unstaged.filter { it.untracked }.map { it.path }.toSet())

        val untracked = repo.diff(status.entries.first { it.path == "dir/b c.txt" }, staged = false)!!
        assertEquals(1, untracked.added)

        repo.stage(listOf("a.txt", "dir/b c.txt"))
        status = repo.status()
        assertEquals(2, status.staged.size)
        repo.unstage(listOf("dir/b c.txt"), initial = true)
        assertTrue(repo.status().entries.first { it.path == "dir/b c.txt" }.untracked)
        repo.stage(listOf("dir/b c.txt"))
        val hash = repo.commit("First commit\n\nWith a body")
        assertTrue(hash.isNotBlank())
        assertTrue(repo.status().clean)

        // Two separate changes make two hunks; stage only the second.
        write("a.txt", lines.mapIndexed { i, l -> if (i == 1) "line two" else if (i == 27) "line twenty-eight" else l }.joinToString("\n", postfix = "\n"))
        val entry = repo.status().entries.single()
        assertEquals(Change.Modified, entry.worktree)
        val unstaged = repo.diff(entry, staged = false)!!
        assertEquals(2, unstaged.hunks.size)
        repo.stageHunk(unstaged, 1)
        val both = repo.status().entries.single()
        assertTrue(both.staged && both.unstaged)
        val staged = repo.diff(both, staged = true)!!
        assertEquals(1, staged.hunks.size)
        assertTrue(staged.hunks[0].lines.contains("+line twenty-eight"))
        assertTrue(repo.diff(both, staged = false)!!.hunks.single().lines.contains("+line two"))

        // Unstage it again, then discard the first hunk in the working tree.
        repo.unstageHunk(staged, 0)
        val again = repo.status().entries.single()
        assertFalse(again.staged)
        repo.discardHunk(repo.diff(again, staged = false)!!, 0)
        val text = File(work, "a.txt").readText()
        assertTrue("line 2\n" in text && "line twenty-eight" in text)

        repo.stage(listOf("a.txt"))
        repo.commit("Second")
        val log = repo.log()
        assertEquals(listOf("Second", "First commit"), log.map { it.subject })
        assertEquals("With a body", log[1].body)
        assertEquals("Test User", log[0].author)
        val (commit, files) = repo.show(log[0].hash)
        assertEquals("Second", commit?.subject)
        assertEquals(listOf("a.txt"), files.map { it.path })
        assertEquals(1, files[0].added)

        // Discarding: an untracked file is deleted, a modified one restored.
        write("junk.txt", "x\n")
        write("a.txt", "changed\n")
        for (e in repo.status().entries) repo.discard(e, staged = false)
        assertTrue(repo.status().clean)
        assertFalse(File(work, "junk.txt").exists())

        // A staged new file discarded from the staged side is gone.
        write("added.txt", "new\n")
        repo.stage(listOf("added.txt"))
        repo.discard(repo.status().entries.single(), staged = true)
        assertTrue(repo.status().clean)

        repo.createBranch("feature/x")
        assertEquals("feature/x", repo.status().branch.head)
        val branches = repo.branches()
        assertEquals(setOf("main", "feature/x"), branches.map { it.name }.toSet())
        assertTrue(branches.first { it.name == "feature/x" }.current)
        repo.switchTo(branches.first { it.name == "main" })
        assertEquals("main", repo.status().branch.head)
        assertNull(repo.remoteUrl())

        val failure = assertFailsWith<GitException> { repo.createBranch("main") }
        assertTrue(failure.message!!.contains("already exists"), failure.message)
    }

    @Test
    fun diffStatsCountStagedAndUnstagedLines() = runBlocking {
        repo.init()
        repo.setGlobalIdentity("T", "t@example.com")
        // Before the first commit the index is compared with the empty tree.
        write("a.txt", "one\ntwo\n")
        repo.stage(listOf("a.txt"))
        assertEquals(LineCount(2, 0), repo.diffStats().staged["a.txt"])
        repo.commit("first")
        write("a.txt", "one\nTWO\nthree\n")
        write("new.txt", "untracked\n")
        val stats = repo.diffStats()
        assertTrue(stats.staged.isEmpty())
        assertEquals(LineCount(2, 1), stats.unstaged["a.txt"])
        assertNull(stats.unstaged["new.txt"], "git counts untracked files only once they are added")
        repo.stage(listOf("a.txt"))
        assertEquals(LineCount(2, 1), repo.diffStats().staged["a.txt"])
    }

    @Test
    fun commitWithNothingStagedExplainsItself() = runBlocking {
        repo.init()
        repo.setGlobalIdentity("T", "t@example.com")
        val e = assertFailsWith<GitException> { repo.commit("empty") }
        assertTrue(e.message!!.isNotBlank())
    }

    @Test
    fun credentialHelperAsksTheBrokerSocketForGithubOnly() = runBlocking {
        val token = "gho_" + "A".repeat(36)
        // A stand-in for the pack's curl: answers like the broker when asked over the configured socket.
        val bin = File(root, "bin").apply { mkdirs() }
        val sock = File(root, "cred/socket").path
        File(bin, "curl").apply {
            writeText("#!/bin/sh\ncase \"\$*\" in *\"--unix-socket $sock http://pibolt/github\"*) printf 'username=octocat\\npassword=$token\\n' ;; esac\n")
            setExecutable(true)
        }
        extraEnv = GitCredentials.env(sock) + ("PATH" to "${bin.path}:${System.getenv("PATH") ?: "/usr/bin:/bin"}")
        assertFalse(extraEnv.values.any { token in it })
        val fill = "protocol=https\nhost=github.com\npath=o/r.git\n\n"
        val gh = runner.run(work, listOf("credential", "fill"), 10_000, fill, null)
        assertEquals(0, gh.exit, gh.err)
        assertTrue("username=octocat" in gh.out)
        assertTrue("password=$token" in gh.out)

        // Another host never asks the helper (and git may not prompt): fill fails.
        val other = runner.run(work, listOf("credential", "fill"), 10_000, "protocol=https\nhost=gitlab.com\n\n", null)
        assertFalse(token in other.out)

        // Signed out: no helper at all.
        assertTrue(GitCredentials.env(null).isEmpty())
    }

    @Test
    fun discardTakesTheFileNameLiterally() = runBlocking {
        repo.init()
        for (n in listOf("[id].txt", "i.txt", "d.txt")) File(work, n).writeText(n)
        val entry = repo.status().entries.first { it.path == "[id].txt" }
        repo.discard(entry, staged = false)
        assertFalse(File(work, "[id].txt").exists())
        assertTrue(File(work, "i.txt").exists())
        assertTrue(File(work, "d.txt").exists())
    }

    @Test
    fun showRejectsAnythingButAHash() = runBlocking {
        repo.init()
        assertFailsWith<GitException> { repo.show("--output=${File(root, "x").path}") }
        assertFalse(File(root, "x").exists())
    }
}
