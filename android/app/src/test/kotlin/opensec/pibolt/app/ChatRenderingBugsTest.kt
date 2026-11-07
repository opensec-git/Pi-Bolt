package opensec.pibolt.app

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import opensec.pibolt.app.ui.markdown.Markdown
import opensec.pibolt.app.ui.markdown.MdBlock
import opensec.pibolt.app.ui.session.features.ProjectFileIndex
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.session.NoticeItem
import opensec.pibolt.protocol.session.NoticeKind
import opensec.pibolt.protocol.session.SessionReducer
import opensec.pibolt.protocol.session.SessionState
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Transcript, markdown and composer regressions of the 0.6.0 bug wave, named for the scenario a user hit. */
class ChatRenderingBugsTest {
    private fun run(state: SessionState, vararg events: String): SessionState =
        events.fold(state) { s, e -> SessionReducer.reduce(s, ProtocolJson.parseToJsonElement(e) as JsonObject) }

    @Test
    fun oneLineTripleBacktickSpanDoesNotSwallowTheRestOfTheReply() {
        val blocks = Markdown.parse("Install it:\n\n```npm install pibolt```\n\nThen run it.\n\n## Next steps\n\n- one\n- two").blocks
        assertTrue(blocks.none { it is MdBlock.Code }, "$blocks")
        assertTrue(blocks.any { it is MdBlock.Heading }, "$blocks")
        // A real fence still opens, with its info string.
        assertTrue(Markdown.parse("```kotlin\nval a = 1\n```").blocks.single() is MdBlock.Code)
        assertTrue(Markdown.parse("~~~ a`b\nx\n~~~").blocks.single() is MdBlock.Code)
    }

    @Test
    fun largeMessagePlaceholderNeverEndsOnHalfAnEmoji() {
        val text = "a".repeat(3_999) + "😀" + "b".repeat(30_000)
        val p = (Markdown.placeholder(text).blocks[0] as MdBlock.Paragraph).text
        assertFalse(Character.isHighSurrogate(p.last()))
    }

    @Test
    fun retriedReplyNoLongerSaysRetrying() {
        var s = run(SessionState(hello = true), """{"type":"auto_retry_start","attempt":1,"maxAttempts":3,"delayMs":2000,"errorMessage":"529"}""")
        assertNotNull(s.retry)
        // The backoff ends and the retried request streams; auto_retry_end only comes at its end.
        s = run(s, """{"type":"agent_start"}""")
        assertNull(s.retry)
        assertTrue(s.isRunning)
    }

    @Test
    fun failedManualCompactionIsNotShownAsAModelError() {
        val s = run(SessionState(hello = true),
            """{"type":"compaction_start","reason":"manual"}""",
            """{"type":"compaction_end","reason":"manual","aborted":false,"willRetry":false,"errorMessage":"Nothing to compact (session too small)"}""",
        )
        val notice = s.items.last() as NoticeItem
        assertEquals(NoticeKind.Info, notice.kind)
        assertEquals("Compaction failed", notice.title)
        val stopped = run(SessionState(hello = true), """{"type":"compaction_end","reason":"threshold","aborted":true,"willRetry":false}""")
        assertEquals(NoticeKind.Info, (stopped.items.last() as NoticeItem).kind)
        // An overflow compaction failing still ends the turn in an error.
        val overflow = run(SessionState(hello = true), """{"type":"compaction_end","reason":"overflow","aborted":false,"willRetry":false,"errorMessage":"Too long"}""")
        assertEquals(NoticeKind.Error, (overflow.items.last() as NoticeItem).kind)
    }

    @Test
    fun fileMentionsLoadAfterTheFirstLoadWasCancelled(): Unit = runBlocking {
        val root = Files.createTempDirectory("mentions").toFile()
        File(root, "a.kt").writeText("x")
        val index = ProjectFileIndex(root)
        // `@` typed, then a space before the walk ended: the first load is cancelled.
        val first = launch(start = CoroutineStart.UNDISPATCHED) { index.load() }
        first.cancel()
        first.join()
        withTimeout(5_000) { launch(Dispatchers.Default) { index.load() }.join() }
        assertEquals(listOf("a.kt"), index.files.value)
        root.deleteRecursively()
    }
}
