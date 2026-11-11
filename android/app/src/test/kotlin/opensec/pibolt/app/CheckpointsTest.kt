package opensec.pibolt.app

import kotlinx.coroutines.runBlocking
import opensec.pibolt.app.checkpoints.ChangeKind
import opensec.pibolt.app.checkpoints.CheckpointIndex
import opensec.pibolt.app.checkpoints.Git
import opensec.pibolt.app.checkpoints.RestoreKind
import opensec.pibolt.app.checkpoints.ShadowRepo
import opensec.pibolt.app.checkpoints.TurnCheckpoints
import opensec.pibolt.app.checkpoints.TurnRecord
import opensec.pibolt.app.checkpoints.UndoEntry
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Checkpoints against real temporary projects with the host's git (skipped where there is none). */
class CheckpointsTest {
    private lateinit var root: File
    private lateinit var project: File
    private lateinit var gitDir: File
    private val notices = ArrayList<String>()

    @Before
    fun setUp() {
        assumeTrue("git is not installed", GIT != null)
        root = Files.createTempDirectory("pi-checkpoints").toFile()
        project = File(root, "project").apply { mkdirs() }
        gitDir = ShadowRepo.dirFor(File(root, "agent"), project)
    }

    @After
    fun tearDown() {
        if (::root.isInitialized) root.deleteRecursively()
    }

    private fun repo(maxFileBytes: Long = ShadowRepo.MAX_FILE_BYTES) =
        ShadowRepo(Git(GIT!!, { System.getenv() }, gitDir, project), maxFileBytes)

    private fun write(path: String, text: String) = File(project, path).apply { parentFile.mkdirs() }.writeText(text)

    private fun read(path: String): String? = File(project, path).takeIf { it.isFile }?.readText()

    private fun checkpoints(repo: ShadowRepo = repo(), clock: () -> Long = System::currentTimeMillis) =
        TurnCheckpoints(repo, clock = clock, onNotice = { notices += it }).also { it.start() }

    /** A whole turn: the user message, [work] by the "agent", then the agent settling. */
    private fun TurnCheckpoints.turn(prompt: String, timestamp: Long, work: () -> Unit): TurnRecord = runBlocking {
        userMessage(timestamp, prompt, "s1")
        idle()
        work()
        settled()
        idle()
        assertNotNull(turnFor(timestamp, prompt))
    }

    @Test
    fun snapshotsAndDiffsWithoutTouchingTheProjectsGit() {
        run(project, "init", "-q")
        write("a.txt", "one\ntwo\nthree\n")
        write("gone.txt", "bye\n")
        run(project, "add", "-A")
        val ownIndex = File(project, ".git/index").readBytes()
        val ownHead = File(project, ".git/HEAD").readText()

        val repo = repo()
        val first = repo.snapshot("start")
        assertEquals(first, repo.snapshot("again"), "an unchanged project reuses its snapshot")
        write("a.txt", "one\n2\nthree\nfour\n")
        File(project, "gone.txt").delete()
        write("src/new.kt", "fun main() {}\n")
        val second = repo.snapshot("end")
        assertNotEquals(first.commit, second.commit)

        val changes = repo.changes(first.commit, second.commit).associateBy { it.path }
        assertEquals(setOf("a.txt", "gone.txt", "src/new.kt"), changes.keys)
        assertEquals(ChangeKind.Modified, changes.getValue("a.txt").kind)
        assertEquals(2 to 1, changes.getValue("a.txt").let { it.added to it.removed })
        assertEquals(ChangeKind.Deleted, changes.getValue("gone.txt").kind)
        assertEquals(ChangeKind.Added, changes.getValue("src/new.kt").kind)
        val patch = repo.patch(first.commit, second.commit, "a.txt")
        assertTrue("+four" in patch && "-two" in patch && "@@" in patch, patch)

        assertTrue(File(gitDir, "HEAD").isFile, "the shadow repository lives under the agent dir")
        assertFalse(gitDir.path.startsWith(project.path))
        assertTrue(File(project, ".git/index").readBytes().contentEquals(ownIndex), "the project's index is untouched")
        assertEquals(ownHead, File(project, ".git/HEAD").readText())
        assertEquals("", run(project, "log", "--all", "--oneline").trim(), "no commits in the project's repository")
    }

    @Test
    fun ignoreRulesHeavyFoldersAndTheSizeCap() {
        write(".gitignore", "secret.env\nout/\n")
        write("secret.env", "KEY=1\n")
        write("out/app.bin", "x")
        write("node_modules/dep/index.js", "module.exports = 1\n")
        write("pkg/node_modules/x.js", "1\n")
        write("big.bin", "x".repeat(2000))
        write("small.txt", "ok\n")
        val repo = repo(maxFileBytes = 1000)
        val snap = repo.snapshot("start")
        val files = repo.tree(snap.commit).keys
        assertEquals(setOf(".gitignore", "small.txt"), files)
        assertEquals(setOf("big.bin"), snap.skipped)
        assertEquals(setOf("big.bin"), repo.skipped(snap.commit))

        // Shrunk under the cap: it is kept again; grown over it: it leaves the snapshot.
        write("big.bin", "small now\n")
        write("small.txt", "y".repeat(5000))
        val next = repo.snapshot("end")
        assertEquals(setOf(".gitignore", "big.bin"), repo.tree(next.commit).keys)
        assertEquals(setOf("small.txt"), next.skipped)
    }

    @Test
    fun undoAndRedoATurn() {
        write("a.txt", "original\n")
        write("keep.txt", "untouched\n")
        write("old.txt", "will be deleted\n")
        val cp = checkpoints()
        val turn = cp.turn("edit things", 1000) {
            write("a.txt", "changed by the agent\n")
            write("new/file.txt", "created\n")
            File(project, "old.txt").delete()
        }
        assertEquals(3, turn.files)
        assertEquals(setOf("a.txt", "new/file.txt", "old.txt"), runBlocking { cp.changes(turn) }.map { it.path }.toSet())

        val plan = runBlocking { cp.planUndo(turn) }
        assertEquals(RestoreKind.UndoTurn, plan.kind)
        assertTrue(plan.conflicts.isEmpty())
        val byPath = plan.items.associateBy { it.path }
        assertEquals(ChangeKind.Modified, byPath.getValue("a.txt").action)
        assertEquals(ChangeKind.Deleted, byPath.getValue("new/file.txt").action)
        assertEquals(ChangeKind.Added, byPath.getValue("old.txt").action)
        runBlocking { cp.apply(plan) }
        assertEquals("original\n", read("a.txt"))
        assertNull(read("new/file.txt"))
        assertFalse(File(project, "new").exists(), "an emptied folder goes too")
        assertEquals("will be deleted\n", read("old.txt"))
        assertEquals("untouched\n", read("keep.txt"))

        // The undo is undoable.
        val undone = assertNotNull(cp.turn(turn.id)?.lastUndo)
        val redo = runBlocking { cp.planRedo(cp.turn(turn.id)!!, undone) }
        assertTrue(redo.conflicts.isEmpty())
        runBlocking { cp.apply(redo) }
        assertEquals("changed by the agent\n", read("a.txt"))
        assertEquals("created\n", read("new/file.txt"))
        assertNull(read("old.txt"))
        assertNull(cp.turn(turn.id)?.lastUndo, "a redone undo is spent")
        assertTrue(notices.isEmpty(), notices.toString())
        cp.close()
    }

    @Test
    fun undoWarnsAboutFilesChangedSinceAndRestoresOnlyTheChosenOnes() {
        write("a.txt", "a0\n")
        write("b.txt", "b0\n")
        write("c.txt", "c0\n")
        val cp = checkpoints()
        val first = cp.turn("first", 1) {
            write("a.txt", "a1\n")
            write("b.txt", "b1\n")
            write("c.txt", "c1\n")
        }
        cp.turn("second", 2) { write("b.txt", "b2 from a later turn\n") }
        write("c.txt", "c1 edited by the user\n")

        val plan = runBlocking { cp.planUndo(cp.turn(first.id)!!) }
        assertEquals(setOf("b.txt", "c.txt"), plan.conflicts.map { it.path }.toSet())
        assertFalse(plan.items.first { it.path == "a.txt" }.conflict)
        // Keep the later changes of b and c: only a goes back.
        val result = runBlocking { cp.apply(plan, listOf("a.txt")) }
        assertEquals(listOf("a.txt"), result.restored)
        assertEquals("a0\n", read("a.txt"))
        assertEquals("b2 from a later turn\n", read("b.txt"))
        assertEquals("c1 edited by the user\n", read("c.txt"))

        // Undoing one file of a turn.
        val single = runBlocking { cp.planUndo(cp.turn(first.id)!!, listOf("c.txt")) }
        assertEquals(listOf("c.txt"), single.items.map { it.path })
        runBlocking { cp.apply(single) }
        assertEquals("c0\n", read("c.txt"))
        cp.close()
    }

    @Test
    fun restoreToBeforeATurnRollsBackLaterTurnsAndFlagsTheUsersEdits() {
        write("a.txt", "a0\n")
        val cp = checkpoints()
        val first = cp.turn("first", 10) { write("a.txt", "a1\n") }
        cp.turn("second", 20) {
            write("a.txt", "a2\n")
            write("c.txt", "c2\n")
        }
        write("mine.txt", "the user's own file\n")
        val plan = runBlocking { cp.planRestoreBefore(cp.turn(first.id)!!) }
        assertEquals(RestoreKind.RestoreBefore, plan.kind)
        assertEquals(setOf("a.txt", "c.txt", "mine.txt"), plan.items.map { it.path }.toSet())
        assertEquals(listOf("mine.txt"), plan.conflicts.map { it.path })
        runBlocking { cp.apply(plan, plan.items.filterNot { it.conflict }.map { it.path }) }
        assertEquals("a0\n", read("a.txt"))
        assertNull(read("c.txt"))
        assertEquals("the user's own file\n", read("mine.txt"))
        cp.close()
    }

    @Test
    fun aTooLargeFileIsNeverDeletedByAnUndo() {
        write("data.bin", "z".repeat(3000))
        val cp = checkpoints(repo(maxFileBytes = 1000))
        val turn = cp.turn("shrink it", 5) { write("data.bin", "small\n") }
        // Before the turn the file was over the cap, so the start snapshot does not hold it.
        val plan = runBlocking { cp.planUndo(turn) }
        val result = runBlocking { cp.apply(plan) }
        assertEquals(listOf("data.bin"), result.kept)
        assertEquals("small\n", read("data.bin"))
        cp.close()
    }

    @Test
    fun steeringSplitsTurnsAndTurnsMatchTheirMessages() {
        val cp = checkpoints()
        runBlocking {
            cp.userMessage(100, "do it", "s1")
            cp.idle()
            write("x.txt", "1\n")
            cp.userMessage(200, "also this", "s1")
            cp.idle()
            write("y.txt", "2\n")
            cp.settled()
            cp.idle()
        }
        val first = assertNotNull(cp.turnFor(100, "do it"))
        val second = assertNotNull(cp.turnFor(200, "also this"))
        assertEquals(first.end, second.start)
        assertEquals(listOf("x.txt"), runBlocking { cp.changes(first) }.map { it.path })
        assertEquals(listOf("y.txt"), runBlocking { cp.changes(second) }.map { it.path })
        assertNull(cp.turnFor(300, "do it"), "another message with the same text is another turn")
        cp.close()

        // A new session (after a restart) reads the same turns.
        val again = checkpoints()
        runBlocking { again.idle() }
        assertEquals(2, again.turns.value.size)
        again.close()
    }

    @Test
    fun pruningKeepsFiftyTurnsOfTwoWeeksAndFreesTheirSnapshots() {
        val day = 24L * 60 * 60 * 1000
        val now = 100 * day
        val turns = (0 until 60).map { i -> TurnRecord("t$i", null, i.toLong(), "p$i", startedAt = now - (59 - i) * 1000L, start = "s$i", end = "e$i") }
        val kept = CheckpointIndex.retain(turns, now)
        assertEquals(50, kept.size)
        assertEquals("t10", kept.first().id)
        val old = TurnRecord("old", null, 1, "p", startedAt = now - 15 * day, start = "s", end = "e")
        val running = TurnRecord("run", null, 2, "p", startedAt = now - 30 * day, start = "s")
        assertEquals(listOf("run"), CheckpointIndex.retain(listOf(old, running), now).map { it.id })

        val repo = repo()
        write("f.txt", "1\n")
        val a = repo.snapshot("a")
        write("f.txt", "2\n")
        val b = repo.snapshot("b")
        write("f.txt", "3\n")
        val c = repo.snapshot("c")
        assertEquals(setOf(a.commit, b.commit, c.commit), repo.snapshotCommits())
        repo.prune(setOf(b.commit), force = true)
        // The newest snapshot stays too: the next one deduplicates against it.
        assertEquals(setOf(b.commit, c.commit), repo.snapshotCommits())
        assertFalse(repo.hasObject(a.commit), "a pruned snapshot's objects are gone")
        assertTrue(repo.hasObject(b.commit))

        // Through the recorder: with a clock 15 days on, the old turn is dropped when the next one ends.
        var time = 0L
        val cp = checkpoints(repo, clock = { time })
        val oldTurn = cp.turn("old", 1) { write("f.txt", "4\n") }
        time = 15 * day
        cp.turn("new", 2) { write("f.txt", "5\n") }
        assertNull(cp.turn(oldTurn.id))
        assertEquals(1, cp.turns.value.size)
        assertFalse(oldTurn.start in repo.snapshotCommits(), "its snapshots lose their refs (objects go at the next daily prune)")
        cp.close()
    }

    @Test
    fun theIndexRoundTrips() {
        val file = File(root, "turns.json")
        val turn = TurnRecord(
            "id", "session", 42, "prompt \"quoted\"\nline", 7, "start", 8, "end", 3, 40, 12,
            listOf(UndoEntry(9, "before", "after", listOf("a b.txt", "ü.txt"), redone = true)),
        )
        CheckpointIndex(file).save(listOf(turn))
        assertEquals(listOf(turn), CheckpointIndex(file).load())
        file.writeText("not json")
        assertEquals(emptyList(), CheckpointIndex(file).load())
    }

    @Test
    fun aSnapshotKilledHalfwayDoesNotPauseCheckpointsForGood() {
        write("a.txt", "one\n")
        val repo = repo()
        repo.snapshot("first")
        // The app process was killed during `git add` (or git timed out and was killed): its lock files stay.
        File(gitDir, "index.lock").writeText("")
        File(gitDir, "packed-refs.lock").writeText("")
        write("a.txt", "two\n")
        val next = repo.snapshot("second")
        assertEquals(listOf("a.txt"), repo.changes(repo.snapshotCommits().first { it != next.commit }, next.commit).map { it.path })
        repo.prune(keep = setOf(next.commit), force = true)
        assertEquals(setOf(next.commit), repo.snapshotCommits())
    }

    @Test
    fun aFailingSnapshotIsOneQuietNotice() {
        val broken = ShadowRepo(Git(File(root, "no-git").path, { System.getenv() }, gitDir, project))
        val cp = checkpoints(broken)
        runBlocking {
            cp.userMessage(1, "a", null)
            cp.settled()
            cp.userMessage(2, "b", null)
            cp.idle()
        }
        assertEquals(1, notices.size, notices.toString())
        assertTrue(notices.single().startsWith("Checkpoints paused"))
        cp.close()
    }

    @Test
    fun patternsMatchOnlyTheirOwnPath() {
        assertEquals("a\\ b\\*\\[1]\\?.txt", ShadowRepo.escapePattern("a b*[1]?.txt"))
        write("weird [1] *.txt", "x".repeat(2000))
        write("weird 2.txt", "y\n")
        val repo = repo(maxFileBytes = 1000)
        val snap = repo.snapshot("s")
        assertEquals(setOf("weird 2.txt"), repo.tree(snap.commit).keys)
    }

    private fun run(dir: File, vararg args: String): String {
        val p = ProcessBuilder(listOf(GIT!!) + args).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor()
        return out
    }

    companion object {
        private val GIT: String? = listOf("/usr/bin/git", "/opt/homebrew/bin/git", "/usr/local/bin/git").firstOrNull { File(it).canExecute() }
            ?: System.getenv("PATH")?.split(File.pathSeparator)?.map { File(it, "git") }?.firstOrNull { it.canExecute() }?.path
    }
}
