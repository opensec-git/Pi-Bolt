package opensec.pibolt.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.app.ui.session.views.ViewFields
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

    @Test
    fun aFieldKeepsTheUsersEditUntilTheExtensionSetsAnotherValue() {
        val fields = ViewFields()
        fields.hostValue("branch", JsonPrimitive("main"))
        fields.edits["branch"] = JsonPrimitive("dev")
        // The field left composition (a folded panel, a closed page) and came back: same value from the extension.
        fields.hostValue("branch", JsonPrimitive("main"))
        assertEquals(JsonPrimitive("dev"), fields.edits["branch"])
        fields.hostValue("branch", JsonPrimitive("release"))
        assertEquals(null, fields.edits["branch"])
    }

    @Test
    fun aPickedOptionTheExtensionTookAwayNoLongerCounts() {
        val fields = ViewFields()
        fun select(vararg options: String) = opensec.pibolt.protocol.session.VSelect("r", false, "Region", options.map { opensec.pibolt.protocol.session.VOption(it, it) }, "eu")
        fields.edits["r"] = JsonPrimitive("us")
        assertEquals(JsonPrimitive("us"), fields.value(select("eu", "us")))
        assertEquals(JsonPrimitive("eu"), fields.value(select("eu", "ap")))
    }

    @Test
    fun aSheetAnswerTakesTheRequiredFieldsOfItsOwnFormAndThoseOutsideForms() {
        val name = opensec.pibolt.protocol.session.VInput("name", false, "Name", "", null, null, null, "text", true, 100)
        fun form(id: String, field: String) = opensec.pibolt.protocol.session.VForm(id, false, listOf(name.copy(id = field)), "Go", false, false)
        val nodes = listOf(name, form("a", "x"), form("b", "y"))
        assertEquals(listOf("name", "x"), opensec.pibolt.app.ui.session.views.answerFields(nodes, "a").map { it.id })
        assertEquals(listOf("name"), opensec.pibolt.app.ui.session.views.answerFields(nodes, "save").map { it.id })
    }

    @Test
    fun aTextLongerThanALoweredLimitFailsTheCheckWithAReason() {
        val fields = ViewFields()
        val input = opensec.pibolt.protocol.session.VInput("code", false, "Code", "", null, null, null, "text", false, 3)
        fields.edits["code"] = JsonPrimitive("abcdefghij")
        assertEquals(false, fields.check(listOf(input)))
        assertEquals("At most 3 characters", fields.errors["code"])
    }

    @Test
    fun everyFieldSentMustFitWhileRequiredCountsOnlyForTheAnswersOwn() {
        val fields = ViewFields()
        val a = opensec.pibolt.protocol.session.VInput("a", false, "A", "", null, null, null, "text", true, 100)
        val b = opensec.pibolt.protocol.session.VInput("b", false, "B", "", null, null, null, "number", true, 100)
        fields.edits["a"] = JsonPrimitive("x")
        assertEquals(true, fields.check(listOf(a, b), required = setOf("a")))
        fields.edits["b"] = JsonPrimitive(".")
        assertEquals(false, fields.check(listOf(a, b), required = setOf("a")))
        assertEquals("Enter a number", fields.errors["b"])
    }
}
