package opensec.pibolt.app

import opensec.pibolt.app.git.Change
import opensec.pibolt.app.git.LineCount
import opensec.pibolt.app.ui.projects.FileDoc
import opensec.pibolt.app.ui.session.remote.RemoteMapping
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.rpc.RemoteDiff
import opensec.pibolt.protocol.rpc.RemoteFile
import opensec.pibolt.protocol.rpc.RemoteGitStatus
import opensec.pibolt.protocol.rpc.RemoteListing
import opensec.pibolt.protocol.rpc.RemoteShow
import opensec.pibolt.protocol.rpc.RpcResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The computer's working tree, read over remote control, in the project views' models (ui/session/remote). */
class RemoteReviewTest {
    private fun ok(command: String, data: String) = RpcResponse(command, true, ProtocolJson.parseToJsonElement(data), null)

    private val status = RemoteGitStatus.from(
        ok(
            "rc_git_status",
            """{"repo":true,"branch":{"head":null,"oid":"4f1c2d3","upstream":null,"ahead":0,"behind":0},"entries":[
              {"path":"src/app.ts","index":"M","worktree":"M","kind":"modified","conflicted":false,"staged":{"added":2,"removed":0},"unstaged":{"added":3,"removed":1}},
              {"path":"docs/new.md","origPath":"docs/old.md","index":"R","worktree":null,"kind":"renamed","conflicted":false,"staged":null,"unstaged":null},
              {"path":"notes.md","index":null,"worktree":"?","kind":"untracked","conflicted":false,"staged":null,"unstaged":null},
              {"path":"both.ts","index":"U","worktree":"U","kind":"conflicted","conflicted":true,"staged":null,"unstaged":null}
            ],"total":{"added":5,"removed":1},"truncated":false}""",
        ),
    )

    @Test
    fun statusBecomesTheChangesTabModel() {
        val s = RemoteMapping.status(status)
        assertTrue(s.branch.detached)
        assertEquals("4f1c2d3", s.branch.oid)
        val app = s.entries[0]
        assertEquals(Change.Modified, app.index)
        assertEquals(Change.Modified, app.worktree)
        assertTrue(app.staged && app.unstaged)
        val renamed = s.entries[1]
        assertEquals(Change.Renamed, renamed.index)
        assertEquals("docs/old.md", renamed.origPath)
        assertTrue(s.entries[2].untracked)
        assertEquals(listOf("both.ts"), s.conflicts.map { it.path })
        assertEquals(listOf("src/app.ts", "docs/new.md"), s.staged.map { it.path })
        assertEquals(listOf("src/app.ts", "notes.md"), s.unstaged.map { it.path })
    }

    @Test
    fun countsBecomeDiffStats() {
        val stats = RemoteMapping.stats(status)
        assertEquals(LineCount(2, 0), stats.of("src/app.ts", staged = true))
        assertEquals(LineCount(3, 1), stats.of("src/app.ts", staged = false))
        assertNull(stats.of("notes.md", staged = false))
        assertEquals(LineCount(5, 1), stats.total)
    }

    @Test
    fun diffPicksTheFile() {
        val text = "diff --git a/notes.md b/notes.md\nnew file mode 100644\nindex 0000000..1111111\n--- /dev/null\n+++ b/notes.md\n@@ -0,0 +1,2 @@\n+# notes\n+two\n"
        val d = RemoteMapping.diff(RemoteDiff("notes.md", false, true, text, false), "notes.md")!!
        assertEquals("notes.md", d.path)
        assertEquals(2, d.added)
        assertNull(RemoteMapping.diff(RemoteDiff("a", false, false, "", false), "a"))
    }

    @Test
    fun withheldFilesAreNamedInTheMessage() {
        val show = RemoteShow.from(
            ok(
                "rc_git_show",
                """{"commit":{"hash":"abcdef12","short":"abcdef1","parents":["12345678"],"author":"Ann","email":"a@x","time":1,"subject":"Add config","body":"","refs":[]},
                "diff":"diff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-one\n+two\n","truncated":false,"withheld":[".env"]}""",
            ),
        )
        val (commit, files) = RemoteMapping.show(show)
        assertEquals(RemoteMapping.withheldNote(listOf(".env")), commit?.body)
        assertEquals(listOf("a.txt"), files.map { it.path })
    }

    @Test
    fun filesBecomeViewerDocs() {
        fun file(data: String) = RemoteMapping.doc(RemoteFile.from(ok("rc_read_file", data)))
        assertEquals(FileDoc.Text("héllo", null, 6), file("""{"path":"a","size":6,"binary":false,"truncated":false,"content":"héllo"}"""))
        // Cut short: `next` is where the text stops, so the viewer says only a part is shown.
        val cut = assertIs<FileDoc.Text>(file("""{"path":"a","size":5000000,"binary":false,"truncated":true,"content":"héllo"}"""))
        assertEquals(6L, cut.next)
        assertEquals(FileDoc.Binary(9), file("""{"path":"a.png","size":9,"binary":true,"truncated":false}"""))
    }

    @Test
    fun listingsPutFoldersFirstAndHideGit() {
        val listing = RemoteListing.from(
            ok(
                "rc_list_dir",
                """{"path":"","truncated":false,"entries":[
                  {"name":"b.ts","kind":"file","size":1,"mtime":1},{"name":".git","kind":"dir","size":0,"mtime":1},
                  {"name":"src","kind":"dir","size":0,"mtime":1},{"name":"A.md","kind":"file","size":1,"mtime":1},{"name":".env","kind":"file","size":1,"mtime":1}
                ]}""",
            ),
        )
        assertEquals(listOf("src", ".env", "A.md", "b.ts"), RemoteMapping.entries(listing).map { it.name })
        assertEquals("", RemoteMapping.parent("src"))
        assertEquals("src", RemoteMapping.parent("src/lib"))
    }
}
