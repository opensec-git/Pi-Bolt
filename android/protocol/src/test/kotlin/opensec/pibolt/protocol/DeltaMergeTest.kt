package opensec.pibolt.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import opensec.pibolt.protocol.session.SessionReducer
import opensec.pibolt.protocol.session.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeltaMergeTest {
    private fun delta(kind: String, text: String, index: Int = 0, output: Int = 1) = buildJsonObject {
        put("type", "message_update")
        putJsonObject("usage") { put("output", output) }
        putJsonObject("assistantMessageEvent") {
            put("type", kind)
            put("contentIndex", index)
            put("delta", text)
        }
    }

    private fun reduceAll(events: List<JsonObject>): SessionState {
        var s = SessionReducer.reduce(SessionState(), buildJsonObject { put("type", "agent_start") })
        s = SessionReducer.reduce(s, buildJsonObject { put("type", "message_start"); putJsonObject("message") { put("role", "assistant") } })
        for (e in events) s = SessionReducer.reduce(s, e)
        return s
    }

    @Test
    fun mergedDeltasReduceToTheSameState() {
        val events = listOf(
            delta("text_start", "").let { JsonObject(it - "usage") },
            delta("text_delta", "Hel", output = 1),
            delta("text_delta", "lo ", output = 2),
            delta("text_delta", "world ", output = 3),
        )
        val merged = mutableListOf<JsonObject>()
        for (e in events) {
            val last = merged.lastOrNull()?.let { SessionReducer.mergeDeltas(it, e) }
            if (last != null) merged[merged.size - 1] = last else merged += e
        }
        assertEquals(2, merged.size)
        assertEquals(reduceAll(events).copy(seq = 0), reduceAll(merged).copy(seq = 0))
        assertEquals(3L, reduceAll(merged).lastUsage?.output)
    }

    @Test
    fun differentBlocksOrKindsDoNotMerge() {
        assertNull(SessionReducer.mergeDeltas(delta("text_delta", "a", 0), delta("text_delta", "b", 1)))
        assertNull(SessionReducer.mergeDeltas(delta("text_delta", "a"), delta("thinking_delta", "b")))
        assertNull(SessionReducer.mergeDeltas(delta("text_delta", "a"), buildJsonObject { put("type", "agent_settled") }))
        assertEquals("ab", SessionReducer.mergeDeltas(delta("toolcall_delta", "a"), delta("toolcall_delta", "b"))?.obj("assistantMessageEvent")?.str("delta"))
    }
}
