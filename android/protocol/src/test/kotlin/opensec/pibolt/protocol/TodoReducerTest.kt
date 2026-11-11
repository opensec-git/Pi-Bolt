package opensec.pibolt.protocol

import opensec.pibolt.protocol.session.SessionReducer
import opensec.pibolt.protocol.session.SessionState
import opensec.pibolt.protocol.session.TodoStatus
import opensec.pibolt.protocol.session.ToolItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The todo panel's state, built from the todo extension's `todo` tool results. */
class TodoReducerTest {
    private fun run(state: SessionState, vararg lines: String): SessionState =
        lines.fold(state) { s, line -> SessionReducer.reduce(s, SessionReducer.parse(line)) }

    private fun task(id: Int, subject: String, status: String, activeForm: String? = null): String =
        """{"id":$id,"subject":"$subject","status":"$status"${activeForm?.let { ",\"activeForm\":\"$it\"" } ?: ""}}"""

    private fun details(nextId: Int, vararg tasks: String) = """{"action":"update","params":{},"tasks":[${tasks.joinToString(",")}],"nextId":$nextId}"""

    private fun call(id: String) =
        """{"role":"assistant","content":[{"type":"toolCall","id":"$id","name":"todo","arguments":{"action":"update"}}],"stopReason":"toolUse"}"""

    private fun result(id: String, details: String, isError: Boolean = false) =
        """{"role":"toolResult","toolCallId":"$id","toolName":"todo","content":[{"type":"text","text":"ok"}],"details":$details,"isError":$isError}"""

    private fun hello(running: Boolean, sessionId: String, vararg messages: String) =
        """{"type":"remote_hello","host":{"name":"phone","version":"1","cwd":"/p"},"state":{"isStreaming":$running,"sessionId":"$sessionId"},"messages":[${messages.joinToString(",")}]}"""

    private fun toolEnd(id: String, details: String, isError: Boolean = false) =
        """{"type":"tool_execution_end","toolCallId":"$id","toolName":"todo","result":{"content":[{"type":"text","text":"ok"}],"details":$details},"isError":$isError}"""

    private val user = """{"role":"user","content":"go"}"""

    @Test
    fun historyReplayRestoresTheList() {
        val s = run(
            SessionState(),
            hello(
                false, "s1", user,
                call("t1"), result("t1", details(3, task(1, "Write parser", "completed"), task(2, "Add tests", "pending"))),
                call("t2"), result("t2", details(3, task(1, "Write parser", "completed"), task(2, "Add tests", "in_progress", "Adding tests"))),
            ),
        )
        assertEquals(listOf(1, 2), s.todos.visible.map { it.id })
        assertEquals(TodoStatus.InProgress, s.todos.visible[1].status)
        assertEquals("Adding tests", s.todos.visible[1].activeForm)
        // The tool rows are still there.
        assertEquals(2, s.items.count { it is ToolItem })
    }

    @Test
    fun aPromptAfterwardsDropsCompletedRowsInHistory() {
        val s = run(
            SessionState(),
            hello(false, "s1", user, call("t1"), result("t1", details(3, task(1, "A", "completed"), task(2, "B", "pending"))), user),
        )
        assertEquals(listOf(2), s.todos.visible.map { it.id })
    }

    @Test
    fun liveUpdateReplacesTheList() {
        var s = run(SessionState(), hello(false, "s1"), """{"type":"agent_start"}""")
        s = run(s, toolEnd("t1", details(2, task(1, "Write parser", "in_progress", "Writing parser"))))
        assertEquals(listOf("Write parser"), s.todos.visible.map { it.subject })
        s = run(s, toolEnd("t2", details(3, task(1, "Write parser", "completed"), task(2, "Add tests", "pending"))))
        assertEquals(listOf(TodoStatus.Completed, TodoStatus.Pending), s.todos.visible.map { it.status })
        // The toolResult message that follows carries the same list: nothing changes.
        val before = s.todos
        s = run(s, """{"type":"message_end","message":${result("t2", details(3, task(1, "Write parser", "completed"), task(2, "Add tests", "pending")))}}""")
        assertSame(before, s.todos)
        // A failed call leaves the list alone.
        s = run(s, toolEnd("t3", details(3), isError = true))
        assertSame(before, s.todos)
    }

    @Test
    fun completedRowsStayUntilTheNextPrompt() {
        var s = run(SessionState(), hello(false, "s1"), """{"type":"agent_start"}""")
        s = run(s, toolEnd("t1", details(3, task(1, "A", "completed"), task(2, "B", "pending"))), """{"type":"agent_settled"}""")
        // Work is left: the completed row stays after the run.
        assertEquals(listOf(1, 2), s.todos.visible.map { it.id })
        s = run(s, """{"type":"agent_start"}""")
        assertEquals(listOf(2), s.todos.visible.map { it.id })
        // The next result still lists task 1 as completed: it stays hidden.
        s = run(s, toolEnd("t2", details(3, task(1, "A", "completed"), task(2, "B", "in_progress"))))
        assertEquals(listOf(2), s.todos.visible.map { it.id })
    }

    @Test
    fun allDoneHidesThePanelOnceIdle() {
        var s = run(SessionState(), hello(false, "s1"), """{"type":"agent_start"}""")
        s = run(s, toolEnd("t1", details(3, task(1, "A", "completed"), task(2, "B", "completed"))))
        // Still running: the finished list shows until the run ends.
        assertEquals(2, s.todos.visible.size)
        s = run(s, """{"type":"agent_settled"}""")
        assertTrue(s.todos.visible.isEmpty())
        // Resuming that session does not bring it back.
        val resumed = run(SessionState(), hello(false, "s1", user, call("t1"), result("t1", details(3, task(1, "A", "completed"), task(2, "B", "completed")))))
        assertTrue(resumed.todos.visible.isEmpty())
    }

    @Test
    fun deletedTasksAreDropped() {
        var s = run(SessionState(), hello(false, "s1"), """{"type":"agent_start"}""")
        s = run(s, toolEnd("t1", details(4, task(1, "A", "pending"), task(2, "B", "deleted"), task(3, "C", "in_progress"))))
        assertEquals(listOf(1, 3), s.todos.visible.map { it.id })
        assertEquals(listOf(1, 3), s.todos.tasks.map { it.id })
    }

    @Test
    fun aClearedListStartsOver() {
        var s = run(SessionState(), hello(false, "s1"), """{"type":"agent_start"}""")
        s = run(s, toolEnd("t1", details(3, task(1, "A", "completed"), task(2, "B", "pending"))), """{"type":"agent_settled"}""", """{"type":"agent_start"}""")
        assertEquals(listOf(2), s.todos.visible.map { it.id })
        // Cleared, then a new task 1 that is completed at once: it is a different task and shows.
        s = run(s, toolEnd("t2", details(1)), toolEnd("t3", details(3, task(1, "New", "completed"), task(2, "Next", "pending"))))
        assertEquals(listOf("New", "Next"), s.todos.visible.map { it.subject })
    }

    @Test
    fun aReconnectWithoutTodoResultsKeepsTheList() {
        var s = run(SessionState(), hello(true, "s1"))
        s = run(s, toolEnd("t1", details(2, task(1, "A", "in_progress"))))
        // After a compaction the messages no longer hold the todo call.
        val again = run(s, hello(true, "s1", user))
        assertEquals(listOf(1), again.todos.visible.map { it.id })
        // Another session starts empty.
        val other = run(s, hello(false, "s2", user))
        assertTrue(other.todos.visible.isEmpty())
    }
}
