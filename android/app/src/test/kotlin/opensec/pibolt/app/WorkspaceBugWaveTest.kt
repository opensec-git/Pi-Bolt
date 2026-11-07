package opensec.pibolt.app

import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import opensec.pibolt.app.git.Commit
import opensec.pibolt.app.git.GitException
import opensec.pibolt.app.git.GitResult
import opensec.pibolt.app.git.GitRunner
import opensec.pibolt.app.ui.git.GitOverlay
import opensec.pibolt.app.ui.git.HistoryPages
import opensec.pibolt.app.ui.projects.ProjectClone
import opensec.pibolt.app.ui.projects.ProjectFiles
import opensec.pibolt.app.ui.projects.editor.EditorTabs
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regression tests for the 0.6.0 bug wave in the project workspace (files, editor, git, clone). */
class WorkspaceBugWaveTest {
    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("pibolt-workspace").toFile()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    // Renaming a file in Files left its editor tab on the old path: it showed the old text, and Save wrote the old
    // file again. The tab now follows the rename and keeps an unsaved draft.
    @Test
    fun renamingAFileMovesItsTabAndKeepsTheDraft() {
        val tabs = EditorTabs()
        tabs.open("src/a.kt").apply {
            startEditing("one")
            value = TextFieldValue("two")
        }
        tabs.open("README.md")

        tabs.moved("src", "lib")
        val moved = tabs.find("lib/a.kt")!!
        assertNull(tabs.find("src/a.kt"))
        assertTrue(moved.editing)
        assertTrue(moved.dirty)
        assertEquals("two", moved.value.text)

        tabs.moved("README.md", null)
        assertEquals(listOf("lib/a.kt"), tabs.tabs.map { it.rel })
    }

    @Test
    fun renamingToTheSameNameKeepsTheTabAsItIs() {
        val tabs = EditorTabs()
        val tab = tabs.open("a.txt")
        tabs.moved("a.txt", "a.txt")
        assertTrue(tabs.find("a.txt") === tab)
    }

    // Saving CLAUDE.md -> AGENTS.md replaced the link with a regular file, so AGENTS.md never got the edit.
    @Test
    fun savingASymlinkedFileWritesItsTargetAndKeepsTheLink() = runBlocking {
        val target = File(dir, "AGENTS.md").apply { writeText("old\n") }
        val link = File(dir, "CLAUDE.md")
        Files.createSymbolicLink(link.toPath(), target.toPath().fileName)

        assertTrue(ProjectFiles.write(link, "new\n").isSuccess)

        assertTrue(Files.isSymbolicLink(link.toPath()), "CLAUDE.md is still a link")
        assertEquals("new\n", target.readText())
        assertEquals("new\n", link.readText())
        assertEquals(listOf("AGENTS.md", "CLAUDE.md"), dir.list()!!.sorted(), "no temporary file left")
    }

    // A file kept open in a tab showed (and started an edit from) the text read when it was first opened, after Pi or a
    // shell had changed it; Save then silently replaced their changes.
    @Test
    fun aFileChangedOnDiskSinceItWasReadIsNoticed() = runBlocking {
        val file = File(dir, "app.js").apply { writeText("let a = 1\n") }
        val read = ProjectFiles.stamp(file)
        assertFalse(ProjectFiles.changedOnDisk(read, ProjectFiles.stamp(file)))

        file.writeText("let a = 1\nlet b = 2\n")
        assertTrue(ProjectFiles.changedOnDisk(read, ProjectFiles.stamp(file)))

        file.delete()
        assertTrue(ProjectFiles.changedOnDisk(read, ProjectFiles.stamp(file)), "deleted counts as changed")
        assertFalse(ProjectFiles.changedOnDisk(null, null), "never read: nothing to compare")
    }

    @Test
    fun ourOwnSaveDoesNotCountAsAChangeOnDisk() = runBlocking {
        val file = File(dir, "a.txt").apply { writeText("a") }
        ProjectFiles.write(file, "b")
        val after = ProjectFiles.stamp(file)
        assertFalse(ProjectFiles.changedOnDisk(after, ProjectFiles.stamp(file)))
    }

    // A link to a folder listed as a file, and tapping it said "This file no longer exists."
    @Test
    fun aSymlinkedFolderListsAsAFolder() = runBlocking {
        val real = File(dir, "shared").apply { mkdirs() }
        File(real, "x.txt").writeText("x")
        Files.createSymbolicLink(File(dir, "docs").toPath(), real.toPath())
        Files.createSymbolicLink(File(dir, "dangling").toPath(), File(dir, "missing").toPath())

        val entries = ProjectFiles.list(dir).associateBy { it.name }
        assertTrue(entries.getValue("docs").dir)
        assertFalse(entries.getValue("dangling").dir)
        // Deleting the folder entry removes only the link.
        assertTrue(ProjectFiles.delete(File(dir, "docs")).isSuccess)
        assertTrue(File(real, "x.txt").isFile)
    }

    // "Load more" in the history after a commit made meanwhile repeated the last commit of the page before: two list
    // items with one key crashed the app.
    @Test
    fun historyPagesNeverRepeatACommit() {
        fun c(h: String) = Commit(h, h.take(7), emptyList(), "a", "a@b", 0, h)
        val first = listOf(c("aaaa1"), c("bbbb2"), c("cccc3"))
        // A new commit on top shifted `--skip`: the next page starts with the last one already shown.
        val next = listOf(c("cccc3"), c("dddd4"))
        assertEquals(listOf("aaaa1", "bbbb2", "cccc3", "dddd4"), HistoryPages.merge(first, next).map { it.hash })
        assertEquals(listOf("aaaa1"), HistoryPages.merge(emptyList(), listOf(c("aaaa1"), c("aaaa1"))).map { it.hash })
    }

    // Rotating closed the open diff or history; it is now saved with the screen.
    @Test
    fun theChangesOverlaySurvivesSavedState() {
        for (o in listOf(null, GitOverlay.History, GitOverlay.Diff("src/a b.kt", staged = true), GitOverlay.Diff("s:odd", staged = false))) {
            assertEquals(o, GitOverlay.decode(GitOverlay.encode(o)))
        }
    }

    // Swiping the New project sheet away (or rotating) while a clone ran killed git and left a half-cloned folder in the
    // project list, which also blocked cloning again under that name.
    @Test
    fun aCancelledCloneLeavesNoFolderBehind() = runBlocking {
        val project = File(dir, "repo").apply { mkdirs() }
        val started = CompletableDeferred<Unit>()
        val runner = GitRunner { cwd, _, _, _, _ ->
            File(cwd, ".git").mkdirs()
            File(cwd, ".git/HEAD").writeText("ref: refs/heads/main\n")
            started.complete(Unit)
            awaitCancellation()
        }
        val job = launch { ProjectClone.run(runner, "https://example.org/r.git", project) {} }
        started.await()
        job.cancel()
        job.join()
        assertFalse(project.exists())
    }

    @Test
    fun aFailedCloneLeavesNoFolderBehind() = runBlocking {
        val project = File(dir, "repo").apply { mkdirs() }
        val runner = GitRunner { cwd, _, _, _, _ ->
            File(cwd, "partial").writeText("x")
            GitResult(128, "", "fatal: repository 'https://example.org/r.git/' not found\n")
        }
        assertFailsWith<GitException> { ProjectClone.run(runner, "https://example.org/r.git", project) {} }
        assertFalse(project.exists())
    }
}
