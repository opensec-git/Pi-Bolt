package opensec.pibolt.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import opensec.pibolt.protocol.rpc.RemoteDiff
import opensec.pibolt.protocol.rpc.RemoteFile
import opensec.pibolt.protocol.rpc.RemoteGitStatus
import opensec.pibolt.protocol.rpc.RemoteLineCount
import opensec.pibolt.protocol.rpc.RemoteListing
import opensec.pibolt.protocol.rpc.RemoteLog
import opensec.pibolt.protocol.rpc.RemoteShow
import opensec.pibolt.protocol.rpc.ReviewException
import opensec.pibolt.protocol.rpc.ReviewRequest
import opensec.pibolt.protocol.rpc.RpcResponse
import opensec.pibolt.protocol.rpc.remoteChild
import opensec.pibolt.protocol.session.SessionReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The read-only review commands (docs/remote-control.md, "Review"). */
class RemoteReviewTest {
    private fun json(text: String) = ProtocolJson.parseToJsonElement(text) as JsonObject

    private fun ok(command: String, data: String) = RpcResponse(command, true, ProtocolJson.parseToJsonElement(data), null)

    private fun fields(request: ReviewRequest): JsonObject = buildJsonObject(request.fields)

    @Test
    fun helloSaysWhetherTheHostReviews() {
        val newer = SessionReducer.fromHello(json("""{"type":"remote_hello","host":{"name":"mac","version":"1.2.0","cwd":"/w","review":true},"messages":[]}"""))
        assertTrue(newer.host!!.review)
        val older = SessionReducer.fromHello(json("""{"type":"remote_hello","host":{"name":"mac","version":"1.1.4","cwd":"/w"},"messages":[]}"""))
        assertFalse(older.host!!.review)
    }

    @Test
    fun requestsCarryTheirFields() {
        assertEquals("rc_git_status", ReviewRequest.status().type)
        assertEquals(json("{}"), fields(ReviewRequest.status()))
        assertEquals(json("""{"path":"src/a.ts","staged":false}"""), fields(ReviewRequest.diff("src/a.ts", staged = false, origPath = "src/old.ts")))
        assertEquals(json("""{"path":"b.ts","staged":true,"origPath":"a.ts"}"""), fields(ReviewRequest.diff("b.ts", staged = true, origPath = "a.ts")))
        assertEquals(json("""{"limit":200,"skip":0}"""), fields(ReviewRequest.log(500, skip = -3)))
        assertEquals(json("""{"limit":1,"skip":100}"""), fields(ReviewRequest.log(0, skip = 100)))
        assertEquals("rc_git_show", ReviewRequest.show("abc1234").type)
        assertEquals(json("""{"path":""}"""), fields(ReviewRequest.listDir("")))
        assertEquals(json("""{"path":"docs/a.md"}"""), fields(ReviewRequest.readFile("docs/a.md")))
    }

    @Test
    fun statusReadsBranchEntriesAndCounts() {
        val status = RemoteGitStatus.from(
            ok(
                "rc_git_status",
                """{"repo":true,"branch":{"head":"main","oid":"4f1c2d","upstream":"origin/main","ahead":2,"behind":1},
                "entries":[
                  {"path":"src/app.ts","index":null,"worktree":"M","kind":"modified","conflicted":false,"staged":null,"unstaged":{"added":3,"removed":1}},
                  {"path":"docs/new.md","origPath":"docs/old.md","index":"R","worktree":null,"kind":"renamed","conflicted":false,"staged":{"added":1,"removed":0},"unstaged":null},
                  {"path":"notes.md","index":null,"worktree":"?","kind":"untracked","conflicted":false,"staged":null,"unstaged":null},
                  {"path":"both.ts","index":"U","worktree":"U","kind":"conflicted","conflicted":true,"staged":null,"unstaged":null},
                  {"index":"M"},
                  {"path":"odd.ts","index":"MM","worktree":"X"}
                ],"total":{"added":4,"removed":1},"truncated":false}""",
            ),
        )
        assertTrue(status.repo)
        assertEquals("main", status.branch?.head)
        assertEquals(2, status.branch?.ahead)
        assertEquals(1, status.branch?.behind)
        assertEquals(listOf("src/app.ts", "docs/new.md", "notes.md", "both.ts", "odd.ts"), status.entries.map { it.path })
        val app = status.entries[0]
        assertNull(app.index)
        assertEquals('M', app.worktree)
        assertEquals(RemoteLineCount(3, 1), app.unstaged)
        assertNull(app.staged)
        assertEquals("docs/old.md", status.entries[1].origPath)
        assertEquals('R', status.entries[1].index)
        assertEquals('?', status.entries[2].worktree)
        assertTrue(status.entries[3].conflicted)
        // Not a single known letter: no change on that side.
        assertNull(status.entries[4].index)
        assertNull(status.entries[4].worktree)
        assertEquals(RemoteLineCount(4, 1), status.total)
    }

    @Test
    fun statusOutsideARepository() {
        val status = RemoteGitStatus.from(ok("rc_git_status", """{"repo":false,"branch":null,"entries":[],"total":{"added":0,"removed":0},"truncated":false}"""))
        assertFalse(status.repo)
        assertNull(status.branch)
        assertTrue(status.entries.isEmpty())
        assertEquals(RemoteLineCount.ZERO, status.total)
        // An older or odd host that sends less still parses.
        assertEquals(RemoteLineCount.ZERO, RemoteGitStatus.from(ok("rc_git_status", "{}")).total)
    }

    @Test
    fun failuresThrowTheirError() {
        val e = assertFailsWith<ReviewException> {
            RemoteGitStatus.from(RpcResponse("rc_read_file", false, null, ".env looks like a secret file"))
        }
        assertEquals(".env looks like a secret file", e.message)
        assertFailsWith<ReviewException> { RemoteFile.from(RpcResponse.failure("rc_read_file", "")) }
        assertFailsWith<ReviewException> { RemoteDiff.from(RpcResponse("rc_git_diff", true, null, null)) }
    }

    @Test
    fun diffKeepsTheText() {
        val diff = RemoteDiff.from(ok("rc_git_diff", """{"path":"a.txt","staged":false,"untracked":true,"diff":"diff --git a/a.txt b/a.txt\n+x\n","truncated":true}"""))
        assertEquals("a.txt", diff.path)
        assertTrue(diff.untracked)
        assertTrue(diff.truncated)
        assertEquals("diff --git a/a.txt b/a.txt\n+x\n", diff.diff)
    }

    @Test
    fun logKeepsOnlyCommitsWithHashes() {
        val log = RemoteLog.from(
            ok(
                "rc_git_log",
                """{"repo":true,"more":true,"commits":[
                  {"hash":"0123456789abcdef0123456789abcdef01234567","short":"0123456","parents":["89abcdef","--evil"],"author":"Ann","email":"ann@x","time":1700000000,"subject":"Fix","body":"Body","refs":["HEAD -> main","tag: v1"]},
                  {"hash":"HEAD","subject":"not a commit"},
                  {"hash":"abcdef1","subject":"short only"}
                ]}""",
            ),
        )
        assertTrue(log.more)
        assertEquals(listOf("Fix", "short only"), log.commits.map { it.subject })
        val first = log.commits[0]
        assertEquals(listOf("89abcdef"), first.parents)
        assertEquals(1_700_000_000L, first.time)
        assertEquals(listOf("HEAD -> main", "tag: v1"), first.refs)
        assertEquals("abcdef1", log.commits[1].short)
    }

    @Test
    fun showNamesWithheldFiles() {
        val show = RemoteShow.from(ok("rc_git_show", """{"commit":{"hash":"abcdef12","short":"abcdef1","subject":"s"},"diff":"diff --git a/x b/x\n","truncated":false,"withheld":[".env"]}"""))
        assertEquals("abcdef12", show.commit?.hash)
        assertEquals(listOf(".env"), show.withheld)
        assertNull(RemoteShow.from(ok("rc_git_show", """{"commit":null,"diff":""}""")).commit)
    }

    @Test
    fun listingDropsNamesThatAreNotOneSegment() {
        val listing = RemoteListing.from(
            ok(
                "rc_list_dir",
                """{"path":"src","truncated":false,"entries":[
                  {"name":"a.ts","kind":"file","size":12,"mtime":1700000000000},
                  {"name":"lib","kind":"dir","size":0,"mtime":1},
                  {"name":"away","kind":"other","symlink":true,"outside":true,"size":0,"mtime":1},
                  {"name":"../up","kind":"file"},{"name":"a/b","kind":"file"},{"name":"..","kind":"dir"},{"kind":"file"}
                ]}""",
            ),
        )
        assertEquals("src", listing.path)
        assertEquals(listOf("a.ts", "lib", "away"), listing.entries.map { it.name })
        assertTrue(listing.entries[0].file)
        assertEquals(12L, listing.entries[0].size)
        assertTrue(listing.entries[1].dir)
        assertTrue(listing.entries[2].outside)
        assertEquals("src/a.ts", remoteChild(listing.path, "a.ts"))
        assertEquals("a.ts", remoteChild("", "a.ts"))
    }

    @Test
    fun fileContentIsLeftOutForBinaries() {
        val text = RemoteFile.from(ok("rc_read_file", """{"path":"a.txt","size":5000000,"mtime":1,"binary":false,"truncated":true,"content":"hello"}"""))
        assertEquals("hello", text.content)
        assertTrue(text.truncated)
        assertEquals(5_000_000L, text.size)
        val binary = RemoteFile.from(ok("rc_read_file", """{"path":"a.png","size":6,"mtime":1,"binary":true,"truncated":false,"content":"ignored"}"""))
        assertNull(binary.content)
        assertTrue(binary.binary)
    }
}
