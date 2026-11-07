package opensec.pibolt.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import opensec.pibolt.app.session.SubagentTail
import opensec.pibolt.protocol.session.SessionState
import opensec.pibolt.protocol.session.Subagent
import opensec.pibolt.protocol.session.SubagentStatus
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SubagentTail must not poll while nothing runs: an idle session would otherwise wake the phone every second. */
class SubagentTailIdleTest {
    @Test
    fun noWorkWithoutRunningOrWatchedRuns() {
        val state = MutableStateFlow(SessionState())
        val tail = SubagentTail(state, { null }) { }
        assertFalse(tail.hasWork())
        state.value = SessionState(subagents = mapOf("k" to Subagent(key = "k", status = SubagentStatus.Done)))
        assertFalse(tail.hasWork())
        tail.watch("k", true)
        assertTrue(tail.hasWork())
        tail.watch("k", false)
        assertFalse(tail.hasWork())
        state.value = SessionState(subagents = mapOf("k" to Subagent(key = "k", status = SubagentStatus.Running)))
        assertTrue(tail.hasWork())
    }

    @Test
    fun idleLoopDoesNotReadTheParentFolderUntilARunStarts() {
        val dir = Files.createTempDirectory("subagents-idle").toFile()
        val parent = File(dir, "p.jsonl").apply { writeText("{\"type\":\"session\",\"id\":\"p\"}\n") }
        val state = MutableStateFlow(SessionState())
        val reads = AtomicInteger()
        val tail = SubagentTail(state, { reads.incrementAndGet(); parent.path }) { _: JsonObject -> }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            tail.start(scope)
            Thread.sleep(1_500)
            // pass() returns before asking for the parent file when no run exists; it ran at most once, then waited.
            assertEquals(0, reads.get())
            state.value = SessionState(subagents = mapOf("k" to Subagent(key = "k", prompt = "x", status = SubagentStatus.Running)))
            val deadline = System.currentTimeMillis() + 3_000
            while (reads.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertTrue(reads.get() > 0, "a run that starts wakes the loop")
            state.value = SessionState(subagents = mapOf("k" to Subagent(key = "k", prompt = "x", status = SubagentStatus.Done)))
            Thread.sleep(1_000)
            val settled = reads.get()
            Thread.sleep(1_500)
            assertEquals(settled, reads.get(), "no passes once the run finished")
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}
