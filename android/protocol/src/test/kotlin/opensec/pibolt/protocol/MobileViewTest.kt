package opensec.pibolt.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import opensec.pibolt.protocol.session.DialogKind
import opensec.pibolt.protocol.session.MobileViews
import opensec.pibolt.protocol.session.SessionReducer
import opensec.pibolt.protocol.session.SessionState
import opensec.pibolt.protocol.session.VButton
import opensec.pibolt.protocol.session.VButtonStyle
import opensec.pibolt.protocol.session.VCard
import opensec.pibolt.protocol.session.VChipNode
import opensec.pibolt.protocol.session.VForm
import opensec.pibolt.protocol.session.VInput
import opensec.pibolt.protocol.session.VList
import opensec.pibolt.protocol.session.VProgress
import opensec.pibolt.protocol.session.VSelect
import opensec.pibolt.protocol.session.VTone
import opensec.pibolt.protocol.session.VUnknown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MobileViewTest {
    private fun run(state: SessionState, vararg lines: String): SessionState =
        lines.fold(state) { s, line -> SessionReducer.reduce(s, SessionReducer.parse(line)) }

    private val deploy = """{"v":1,"title":"Deploy","subtitle":"production","body":[
        {"type":"chip","id":"state","label":"Running","tone":"running"},
        {"type":"progress","id":"bar","value":0,"label":"Uploading"},
        {"type":"list","id":"jobs","children":[{"type":"item","id":"a","title":"A","action":"open"}]},
        {"type":"sparkline","id":"spark","label":"Load"}],
        "actions":[{"type":"button","id":"cancel","label":"Cancel","style":"danger","confirm":{"title":"Sure?"}},
                   {"type":"button","id":"docs","label":"Docs","url":"javascript:alert(1)"}]}"""

    private fun setView(rev: Int, slot: String = "panel", key: String = "deploy", view: String = deploy) =
        """{"type":"extension_ui_request","id":"u$rev","method":"setView","viewKey":"$key","slot":"$slot","rev":$rev,"view":$view}"""

    @Test
    fun `parses a view leniently, unknown types as their text and unsafe links dropped`() {
        val state = run(SessionState(), setView(1))
        val entry = state.views.getValue("deploy")
        assertEquals("Deploy", entry.view.title)
        assertEquals(VTone.Running, assertIs<VChipNode>(entry.view.body[0]).chip.tone)
        assertEquals("Load", assertIs<VUnknown>(entry.view.body[3]).text)
        val cancel = entry.view.actions[0]
        assertEquals(VButtonStyle.Danger, cancel.style)
        assertEquals("Sure?", cancel.confirm?.title)
        assertNull(entry.view.actions[1].url)
    }

    @Test
    fun `applies updates on top of the rev before them and waits for a full view otherwise`() {
        val update = """{"type":"extension_ui_request","id":"x","method":"updateView","viewKey":"deploy","rev":2,"ops":[
            {"op":"set","id":"bar","props":{"value":0.5,"label":null}},
            {"op":"insert","parent":"jobs","index":0,"node":{"type":"item","id":"b","title":"B"}},
            {"op":"replace","id":"state","node":{"type":"chip","id":"state","label":"Done","tone":"success"}},
            {"op":"remove","id":"spark"}]}"""
        val state = run(SessionState(), setView(1), update)
        val view = state.views.getValue("deploy").view
        assertEquals(2, state.views.getValue("deploy").rev)
        val bar = assertIs<VProgress>(view.body[1])
        assertEquals(0.5, bar.value)
        assertNull(bar.label)
        assertEquals(listOf("B", "A"), assertIs<VList>(view.body[2]).children.map { it.title })
        assertEquals("Done", assertIs<VChipNode>(view.body[0]).chip.label)
        assertEquals(3, view.body.size)
        // The same update again (rev 2 on rev 2) is a gap: nothing changes until a full setView.
        val again = run(state, update)
        assertEquals(state.views, again.views)
        val full = run(again, setView(9))
        assertEquals(9, full.views.getValue("deploy").rev)
    }

    @Test
    fun `keeps an inline view's anchor and never touches the transcript`() {
        val tool = """{"type":"extension_ui_request","id":"u1","method":"setView","viewKey":"deploy","slot":"inline","rev":1,"toolCallId":"c1","view":$deploy}"""
        val turn = """{"type":"extension_ui_request","id":"u2","method":"setView","viewKey":"log","slot":"inline","rev":1,"turn":1762590000123,"view":$deploy}"""
        val state = run(SessionState(), tool, turn)
        assertEquals("c1", state.views.getValue("deploy").toolCallId)
        assertEquals(1762590000123, state.views.getValue("log").turn)
        assertTrue(state.items.isEmpty())
        val removed = run(state, """{"type":"extension_ui_request","id":"r","method":"setView","viewKey":"deploy","slot":"inline","rev":2}""")
        assertEquals(listOf("log"), removed.views.keys.toList())
    }

    @Test
    fun `opens a sheet as a dialog and updates it while open`() {
        val sheet = """{"v":1,"title":"Settings","body":[{"type":"form","id":"f","children":[
            {"type":"input","id":"branch","label":"Branch","value":"main","required":true},
            {"type":"select","id":"region","label":"Region","options":["eu",{"value":"us","label":"US"}]}],
            "submit":{"label":"Save"}}]}"""
        val state = run(
            SessionState(),
            """{"type":"extension_ui_request","id":"s1","method":"showView","viewKey":"settings","view":$sheet}""",
            """{"type":"extension_ui_request","id":"x","method":"updateView","viewKey":"settings","rev":2,"ops":[{"op":"set","id":"branch","props":{"error":"Unknown branch"}}]}""",
        )
        val dialog = state.dialogs.single()
        assertEquals(DialogKind.View, dialog.kind)
        assertEquals("Settings", dialog.title)
        val form = assertIs<VForm>(dialog.view!!.view.body[0])
        assertEquals("Unknown branch", assertIs<VInput>(form.children[0]).error)
        assertEquals(listOf("eu", "us"), assertIs<VSelect>(form.children[1]).options.map { it.value })
        assertEquals(listOf("branch", "region"), MobileViews.fields(dialog.view.view).map { it.id })
        // A sheet replayed after updates (a reconnect's remote_hello) carries its rev: the next update applies on top.
        val replayed = run(
            SessionState(),
            """{"type":"extension_ui_request","id":"s2","method":"showView","viewKey":"later","rev":5,"view":$sheet}""",
            """{"type":"extension_ui_request","id":"x","method":"updateView","viewKey":"later","rev":6,"ops":[{"op":"set","id":"branch","props":{"value":"dev"}}]}""",
        )
        assertEquals(6, replayed.dialogs.single().view!!.rev)
        val closed = run(state, """{"type":"extension_ui_dismissed","id":"s1"}""")
        assertTrue(closed.dialogs.isEmpty())
    }

    @Test
    fun `takes the views of a remote_hello`() {
        val hello = """{"type":"remote_hello","protocol":1,"host":{"name":"mac","version":"1","cwd":"/w"},"state":{},"messages":[],
            "features":["views"],"views":[{"viewKey":"deploy","slot":"panel","rev":4,"view":$deploy},
            {"viewKey":"log","slot":"inline","rev":1,"view":{"v":1,"body":[{"type":"card","title":"Log","children":[]}]}}]}"""
        val state = run(SessionState(), hello)
        assertEquals(listOf("deploy", "log"), state.views.keys.toList())
        assertEquals(4, state.views.getValue("deploy").rev)
        assertEquals("Log", assertIs<VCard>(state.views.getValue("log").view.body[0]).title)
        assertEquals(MobileViews.SLOT_INLINE, state.views.getValue("log").slot)
    }

    @Test
    fun `refuses ops that do not apply`() {
        val view = SessionReducer.parse(deploy)
        assertNull(MobileViews.applyOps(view, ops("""[{"op":"set","id":"nope","props":{}}]""")))
        assertNull(MobileViews.applyOps(view, ops("""[{"op":"insert","parent":"bar","node":{"type":"divider"}}]""")))
        assertNull(MobileViews.applyOps(view, ops("""[{"op":"insert","index":9,"node":{"type":"divider"}}]""")))
        val button = MobileViews.applyOps(view, ops("""[{"op":"set","id":"cancel","props":{"busy":true}}]"""))!!
        assertTrue(assertIs<VButton>(MobileViews.parse(button).actions[0]).busy)
    }

    private fun ops(json: String): JsonArray = (SessionReducer.parse("""{"ops":$json}""") as JsonObject).arr("ops")!!
}
