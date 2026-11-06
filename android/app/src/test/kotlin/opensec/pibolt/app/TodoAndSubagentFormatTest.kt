package opensec.pibolt.app

import kotlinx.serialization.json.JsonObject
import opensec.pibolt.app.ui.session.TodoLayout
import opensec.pibolt.app.ui.session.groupDetail
import opensec.pibolt.app.ui.session.groupSummary
import opensec.pibolt.app.ui.session.isGroupable
import opensec.pibolt.app.ui.session.isSubagentTool
import opensec.pibolt.app.ui.session.subagentProgress
import opensec.pibolt.app.ui.session.subagentResult
import opensec.pibolt.app.ui.session.todoLayout
import opensec.pibolt.app.ui.session.todoOverflow
import opensec.pibolt.app.ui.session.todoText
import opensec.pibolt.app.ui.session.toolBody
import opensec.pibolt.app.ui.session.toolLabel
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.session.TodoStatus
import opensec.pibolt.protocol.session.TodoTask
import opensec.pibolt.protocol.session.ToolItem
import opensec.pibolt.protocol.session.ToolStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TodoAndSubagentFormatTest {
    private fun json(text: String) = ProtocolJson.parseToJsonElement(text) as JsonObject

    private fun tool(name: String, args: String?, status: ToolStatus = ToolStatus.Done, result: String? = null, details: String? = null, argsText: String = "") =
        ToolItem("tool:1", "1", name, args?.let(::json), argsText, status, result = result, details = details?.let(::json))

    @Test
    fun agentCallsReadAsTypeAndTask() {
        val call = tool("Agent", """{"subagent_type":"explore","description":"Find auth files","prompt":"Find the authentication code"}""")
        assertEquals("Explore", toolLabel(call).verb)
        assertEquals("Find auth files", toolLabel(call).detail)
        // No description: the start of the prompt.
        val noDescription = tool("Agent", """{"subagent_type":"Plan","prompt":"Plan the migration\nin detail"}""")
        assertEquals("Plan the migration", toolLabel(noDescription).detail)
        // While the model writes the call.
        val streaming = tool("Agent", null, ToolStatus.Streaming, argsText = """{"subagent_type":"Explore","description":"Find au""")
        assertEquals("Explore", toolLabel(streaming).verb)
        assertEquals("Find au", toolLabel(streaming).detail)
        assertEquals("Resume agent", toolLabel(tool("Agent", """{"resume":"a1","prompt":"Go on"}""")).verb)
        assertEquals("explore-2: Stop and report", toolLabel(tool("steer_subagent", """{"agent_id":"explore-2","message":"Stop and report"}""")).detail)
        assertEquals("Workflow", toolLabel(tool("SubagentWorkflow", """{"script":"export const meta = { name: 'audit-all', description: 'x' }"}""")).verb)
        assertEquals("audit-all", toolLabel(tool("SubagentWorkflow", """{"script":"export const meta = { name: 'audit-all', description: 'x' }"}""")).detail)
        assertTrue(isSubagentTool("SubagentWorkflow"))
    }

    @Test
    fun runningAgentSummarisesItsPartialResult() {
        val running = tool(
            "Agent",
            """{"subagent_type":"Explore","description":"Find auth"}""",
            ToolStatus.Running,
            details = """{"status":"running","activity":"reading 3 files…","toolUses":4,"turnCount":2}""",
        )
        assertEquals("reading 3 files… · 4 tool uses · turn 2", subagentProgress(running))
        assertEquals("reading 3 files… · 4 tool uses · turn 2", toolBody(running, full = false, previewLines = 6)?.text)
        assertNull(subagentProgress(tool("bash", """{"command":"ls"}""", ToolStatus.Running)))
    }

    @Test
    fun finishedAgentShowsItsAnswer() {
        val done = tool(
            "Agent",
            """{"subagent_type":"Explore","description":"Find auth"}""",
            result = "Agent completed in 9.1s (0 tool uses, 30 token).\n\nAuth lives in src/auth.\n\nAgent ID: abc (pass it as `resume` to continue this agent)",
            details = """{"status":"completed","agentId":"abc"}""",
        )
        assertEquals("Auth lives in src/auth.", subagentResult(done))
        assertEquals("Auth lives in src/auth.", toolBody(done, full = false, previewLines = 6)?.text)
        val background = tool("Agent", """{"subagent_type":"Explore"}""", result = "Agent started in background.", details = """{"status":"background","agentId":"abc"}""")
        assertEquals("Running in background · abc", subagentResult(background))
    }

    @Test
    fun todoCallsReadAsOneLine() {
        assertEquals("Added: Write tests", toolLabel(tool("todo", """{"action":"create","subject":"Write tests"}""")).detail)
        val details = """{"tasks":[{"id":2,"subject":"Write tests","status":"completed"}],"nextId":3}"""
        assertEquals("Done: Write tests", toolLabel(tool("todo", """{"action":"update","id":2,"status":"completed"}""", details = details)).detail)
        assertEquals("Started: #2", toolLabel(tool("todo", """{"action":"update","id":2,"status":"in_progress"}""", ToolStatus.Running)).detail)
        assertEquals("Cleared the list", toolLabel(tool("todo", """{"action":"clear"}""")).detail)
        assertEquals("Added: Wri", toolLabel(tool("todo", null, ToolStatus.Streaming, argsText = """{"action":"create","subject":"Wri""")).detail)
        assertEquals("Todo", toolLabel(tool("todo", """{"action":"list"}""")).verb)
    }

    @Test
    fun finishedTodoCallsFoldIntoAGroup() {
        val calls = listOf(
            tool("todo", """{"action":"create","subject":"A"}"""),
            tool("todo", """{"action":"create","subject":"B"}"""),
            tool("read", """{"path":"src/x.kt"}"""),
        )
        assertTrue(calls.all(::isGroupable))
        assertEquals("Read 1 file, updated todos 2 times", groupSummary(calls))
        assertEquals("A, B, x.kt", groupDetail(calls))
        assertTrue(!isGroupable(tool("todo", """{"action":"create","subject":"A"}""", ToolStatus.Error)))
    }

    private fun task(id: Int, status: TodoStatus) = TodoTask(id, "t$id", status)

    @Test
    fun panelLayoutDropsCompletedRowsFirst() {
        val few = listOf(task(1, TodoStatus.Completed), task(2, TodoStatus.Pending))
        assertEquals(TodoLayout(few, 0, 0), todoLayout(few, budget = 6))
        val many = (1..5).map { task(it, TodoStatus.Completed) } + (6..8).map { task(it, TodoStatus.Pending) }
        val layout = todoLayout(many, budget = 6)
        assertEquals(listOf(1, 2, 6, 7, 8), layout.rows.map { it.id })
        assertEquals("+3 more (3 completed)", todoOverflow(layout))
        val open = (1..9).map { task(it, TodoStatus.Pending) } + task(10, TodoStatus.Completed)
        val tail = todoLayout(open, budget = 6)
        assertEquals((1..5).toList(), tail.rows.map { it.id })
        assertEquals("+5 more (1 completed, 4 pending)", todoOverflow(tail))
    }

    @Test
    fun inProgressRowUsesItsActiveFormWhileRunning() {
        val t = TodoTask(1, "Write tests", TodoStatus.InProgress, activeForm = "Writing tests")
        assertEquals("Writing tests", todoText(t, running = true))
        assertEquals("Write tests", todoText(t, running = false))
    }
}
