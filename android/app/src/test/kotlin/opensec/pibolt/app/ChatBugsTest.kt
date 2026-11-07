package opensec.pibolt.app

import opensec.pibolt.app.session.SessionController
import opensec.pibolt.app.session.SessionEffect
import kotlinx.coroutines.runBlocking
import opensec.pibolt.app.ui.chats.ChatModel
import opensec.pibolt.app.ui.chats.ChatSummary
import opensec.pibolt.app.ui.chats.Chats
import opensec.pibolt.app.ui.session.PendingSend
import opensec.pibolt.app.ui.session.composerStops
import opensec.pibolt.protocol.rpc.ModelInfo
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.session.SessionReducer
import opensec.pibolt.protocol.session.SessionState
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Chat-flow regressions of the 0.6.0 bug wave: each test names the scenario a user hit. */
class ChatBugsTest {
    @Test
    fun queuedMessageBackToComposerKeepsWhatWasTypedSince() {
        // "also add tests" queued as a follow-up, "and update docs" typed, then the queued row swiped back to the composer.
        val back = SessionEffect.EditorText("also add tests", restore = true)
        assertEquals("also add tests\n\nand update docs", back.into("and update docs"))
        assertEquals("also add tests", back.into(""))
        assertEquals("also add tests", back.into("  "))
    }

    @Test
    fun failedSendComesBackOnceWhenTheComposerStillHoldsIt() {
        val failed = SessionEffect.EditorText("hello", restore = true)
        assertEquals("hello", failed.into("hello"))
        assertEquals("hello", failed.into("hello "))
    }

    @Test
    fun extensionSetEditorTextStillReplacesTheDraft() {
        assertEquals("from the extension", SessionEffect.EditorText("from the extension").into("typed"))
    }

    @Test
    fun promptSentDuringManualCompactionWaitsInsteadOfFailing() {
        // Compact tapped, then a message sent while it runs: Pi refuses `prompt` during `compact`, so it is held.
        assertTrue(SessionController.holdsForCompaction(SessionState(hello = true, isCompacting = true, compactionReason = "manual")))
        // A resync mid-compaction loses the reason: still held.
        assertTrue(SessionController.holdsForCompaction(SessionState(hello = true, isCompacting = true, compactionReason = null)))
        // Pi queues a prompt during an automatic compaction itself.
        assertFalse(SessionController.holdsForCompaction(SessionState(hello = true, isCompacting = true, compactionReason = "threshold")))
        assertFalse(SessionController.holdsForCompaction(SessionState(hello = true, isCompacting = true, compactionReason = "overflow")))
        assertFalse(SessionController.holdsForCompaction(SessionState(hello = true)))
    }

    @Test
    fun sendingRowOfAHeldPromptOutlivesTheIdleTimeout() {
        val p = PendingSend(1, "hi", lastUserKey = "u1", at = 0)
        val later = PendingSend.TIMEOUT_MS + 1
        // SessionMemory passes "busy" (running or compacting): the row stays while the compaction holds the prompt.
        assertFalse(PendingSend.resolved(p, "u1", settledAt = 0, running = true, now = later))
        assertTrue(PendingSend.resolved(p, "u1", settledAt = 0, running = false, now = later))
    }

    @Test
    fun newChatSkipsTheLastChatsModelWhenItsKeyWasRemoved() {
        // The last chat ran openrouter/x; its key was removed, so Pi lists only the default's provider. Switching would be
        // refused ("Model not found") and a first prompt from Home waited 15 s for it.
        val remembered = ChatModel("openrouter", "x", "high")
        val default = ModelInfo("m", "M", "anthropic", reasoning = true, contextWindow = null)
        assertNull(ChatModel.switchTo(remembered, default, available = listOf(default)))
        assertEquals(remembered, ChatModel.switchTo(remembered, default, available = listOf(default, ModelInfo("x", "X", "openrouter", true, null))))
        // Not known (the list could not be read): tried as before.
        assertEquals(remembered, ChatModel.switchTo(remembered, default, available = null))
    }

    @Test
    fun manualCompactionCanBeStoppedFromTheComposer() {
        // Compact runs with Pi idle (no turn): the composer had no Stop, so a long compaction could not be cancelled.
        assertTrue(composerStops(running = false, compacting = true, shellRuns = 0))
        assertTrue(composerStops(running = true, compacting = false, shellRuns = 0))
        assertTrue(composerStops(running = false, compacting = false, shellRuns = 1))
        assertFalse(composerStops(running = false, compacting = false, shellRuns = 0))
    }

    @Test
    fun thinkingChipShowsTheLevelPiClampedTo() {
        // "/thinking xhigh" on a model that reasons up to high: Pi sets high and says nothing more (no change event when
        // it was already high). The controller now reads the state back after set_thinking_level.
        val requested = SessionState(hello = true, thinkingLevel = "xhigh")
        val state = ProtocolJson.parseToJsonElement("""{"thinkingLevel":"high","isStreaming":false,"isCompacting":false}""") as JsonObject
        assertEquals("high", SessionReducer.applyRpcState(requested, state).thinkingLevel)
    }

    @Test
    fun blankChatRenameIsRefusedAndLeavesTheFile() {
        val file = java.io.File.createTempFile("chat", ".jsonl").apply {
            writeText("{\"type\":\"session\",\"version\":3,\"id\":\"s\",\"timestamp\":\"2026-11-07T10:00:00.000Z\",\"cwd\":\"/c\"}\n")
            deleteOnExit()
        }
        val before = file.readText()
        assertFalse(runBlocking { Chats.rename(ChatSummary(file.path, "Trip", 0L, 2), "   ") })
        assertEquals(before, file.readText())
    }
}
