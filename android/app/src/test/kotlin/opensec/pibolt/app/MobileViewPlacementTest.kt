package opensec.pibolt.app

import kotlinx.serialization.json.JsonObject
import opensec.pibolt.app.ui.session.ActionsRow
import opensec.pibolt.app.ui.session.MdCache
import opensec.pibolt.app.ui.session.ToolRow
import opensec.pibolt.app.ui.session.TranscriptModel
import opensec.pibolt.app.ui.session.ViewRow
import opensec.pibolt.protocol.session.AssistantTextItem
import opensec.pibolt.protocol.session.ChatItem
import opensec.pibolt.protocol.session.MobileViews
import opensec.pibolt.protocol.session.ToolItem
import opensec.pibolt.protocol.session.ToolStatus
import opensec.pibolt.protocol.session.UserItem
import opensec.pibolt.protocol.session.VView
import opensec.pibolt.protocol.session.ViewEntry
import kotlin.test.Test
import kotlin.test.assertEquals

/** Inline views of the mobile UI kit sit by their anchors, however the transcript was rebuilt. */
class MobileViewPlacementTest {
    private val items: List<ChatItem> = listOf(
        UserItem("u1", "index it", timestamp = 100),
        ToolItem("tool:c1", "c1", "probe", null, status = ToolStatus.Done),
        ToolItem("tool:c2", "c2", "bash", null, status = ToolStatus.Done),
        AssistantTextItem("a1", "Indexed.", streaming = false),
        UserItem("u2", "now deploy", timestamp = 200),
        AssistantTextItem("a2", "Deployed.", streaming = false),
    )

    private fun view(key: String, toolCallId: String? = null, turn: Long? = null) =
        ViewEntry(key, MobileViews.SLOT_INLINE, JsonObject(emptyMap()), VView(null, null, null, emptyList(), emptyList()), 1, toolCallId, turn)

    private fun rows(views: List<ViewEntry>, expanded: Set<String> = emptySet()) =
        TranscriptModel.rows(items, liveTurn = false, expanded = expanded, durations = emptyMap(), md = MdCache(), inline = views)

    @Test
    fun anInlineViewSitsUnderItsToolCallWhenTheTurnIsOpen() {
        val rows = rows(listOf(view("probe", toolCallId = "c1")), expanded = setOf("u1"))
        val tool = rows.indexOfFirst { it is ToolRow && it.item.toolCallId == "c1" }
        assertEquals("probe", (rows[tool + 1] as ViewRow).viewKey)
    }

    @Test
    fun aFoldedTurnKeepsItsViewAtItsEndAndAViewOfATurnGoesAtThatTurnsEnd() {
        val rows = rows(listOf(view("probe", toolCallId = "c1"), view("later", turn = 100), view("lost", turn = 999)))
        val keys = rows.map { it.key }
        val secondTurn = keys.indexOf("u2")
        // Both views of the first turn before the second turn's message; the one whose turn is gone at the end.
        assertEquals(true, keys.indexOf("view:probe") in 0 until secondTurn)
        assertEquals(true, keys.indexOf("view:later") in 0 until secondTurn)
        val lost = keys.indexOf("view:lost")
        assertEquals(true, lost > secondTurn && rows.drop(lost + 1).all { it is ActionsRow })
    }
}
