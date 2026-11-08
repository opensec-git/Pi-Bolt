import Foundation
@testable import PiBoltProtocol
import Testing

/// The Android `TodoReducerTest` (android/protocol): the todo panel's state, built from the todo extension's `todo`
/// tool results.
struct TodoReducerTests {
    private func run(_ state: SessionState, _ lines: String...) -> SessionState {
        lines.reduce(state) { SessionReducer.reduce($0, SessionReducer.parse($1)) }
    }

    private func task(_ id: Int, _ subject: String, _ status: String, _ activeForm: String? = nil) -> String {
        #"{"id":\#(id),"subject":"\#(subject)","status":"\#(status)""# + (activeForm.map { #","activeForm":"\#($0)""# } ?? "") + "}"
    }

    private func details(_ nextId: Int, _ tasks: String...) -> String {
        #"{"action":"update","params":{},"tasks":[\#(tasks.joined(separator: ","))],"nextId":\#(nextId)}"#
    }

    private func call(_ id: String) -> String {
        #"{"role":"assistant","content":[{"type":"toolCall","id":"\#(id)","name":"todo","arguments":{"action":"update"}}],"stopReason":"toolUse"}"#
    }

    private func result(_ id: String, _ details: String, isError: Bool = false) -> String {
        #"{"role":"toolResult","toolCallId":"\#(id)","toolName":"todo","content":[{"type":"text","text":"ok"}],"details":\#(details),"isError":\#(isError)}"#
    }

    private func hello(_ running: Bool, _ sessionId: String, _ messages: String...) -> String {
        #"{"type":"remote_hello","host":{"name":"phone","version":"1","cwd":"/p"},"state":{"isStreaming":\#(running),"sessionId":"\#(sessionId)"},"messages":[\#(messages.joined(separator: ","))]}"#
    }

    private func toolEnd(_ id: String, _ details: String, isError: Bool = false) -> String {
        #"{"type":"tool_execution_end","toolCallId":"\#(id)","toolName":"todo","result":{"content":[{"type":"text","text":"ok"}],"details":\#(details)},"isError":\#(isError)}"#
    }

    private let user = #"{"role":"user","content":"go"}"#
    private let start = #"{"type":"agent_start"}"#
    private let settled = #"{"type":"agent_settled"}"#

    @Test func historyReplayRestoresTheList() {
        let s = run(SessionState(), hello(
            false, "s1", user,
            call("t1"), result("t1", details(3, task(1, "Write parser", "completed"), task(2, "Add tests", "pending"))),
            call("t2"), result("t2", details(3, task(1, "Write parser", "completed"), task(2, "Add tests", "in_progress", "Adding tests")))
        ))
        #expect(s.todos.visible.map(\.id) == [1, 2])
        #expect(s.todos.visible[1].status == .inProgress)
        #expect(s.todos.visible[1].activeForm == "Adding tests")
        // The tool rows are still there.
        #expect(s.items.filter { $0.tool != nil }.count == 2)
    }

    @Test func aPromptAfterwardsDropsCompletedRowsInHistory() {
        let s = run(SessionState(), hello(false, "s1", user, call("t1"), result("t1", details(3, task(1, "A", "completed"), task(2, "B", "pending"))), user))
        #expect(s.todos.visible.map(\.id) == [2])
    }

    @Test func liveUpdateReplacesTheList() {
        var s = run(SessionState(), hello(false, "s1"), start)
        s = run(s, toolEnd("t1", details(2, task(1, "Write parser", "in_progress", "Writing parser"))))
        #expect(s.todos.visible.map(\.subject) == ["Write parser"])
        s = run(s, toolEnd("t2", details(3, task(1, "Write parser", "completed"), task(2, "Add tests", "pending"))))
        #expect(s.todos.visible.map(\.status) == [.completed, .pending])
        // The toolResult message that follows carries the same list: nothing changes.
        let before = s.todos
        s = run(s, #"{"type":"message_end","message":\#(result("t2", details(3, task(1, "Write parser", "completed"), task(2, "Add tests", "pending"))))}"#)
        #expect(s.todos == before)
        // A failed call leaves the list alone.
        s = run(s, toolEnd("t3", details(3), isError: true))
        #expect(s.todos == before)
    }

    @Test func completedRowsStayUntilTheNextPrompt() {
        var s = run(SessionState(), hello(false, "s1"), start)
        s = run(s, toolEnd("t1", details(3, task(1, "A", "completed"), task(2, "B", "pending"))), settled)
        // Work is left: the completed row stays after the run.
        #expect(s.todos.visible.map(\.id) == [1, 2])
        s = run(s, start)
        #expect(s.todos.visible.map(\.id) == [2])
        // The next result still lists task 1 as completed: it stays hidden.
        s = run(s, toolEnd("t2", details(3, task(1, "A", "completed"), task(2, "B", "in_progress"))))
        #expect(s.todos.visible.map(\.id) == [2])
    }

    @Test func allDoneHidesThePanelOnceIdle() {
        var s = run(SessionState(), hello(false, "s1"), start)
        s = run(s, toolEnd("t1", details(3, task(1, "A", "completed"), task(2, "B", "completed"))))
        // Still running: the finished list shows until the run ends.
        #expect(s.todos.visible.count == 2)
        s = run(s, settled)
        #expect(s.todos.visible.isEmpty)
        // Resuming that session does not bring it back.
        let resumed = run(SessionState(), hello(false, "s1", user, call("t1"), result("t1", details(3, task(1, "A", "completed"), task(2, "B", "completed")))))
        #expect(resumed.todos.visible.isEmpty)
    }

    @Test func deletedTasksAreDropped() {
        var s = run(SessionState(), hello(false, "s1"), start)
        s = run(s, toolEnd("t1", details(4, task(1, "A", "pending"), task(2, "B", "deleted"), task(3, "C", "in_progress"))))
        #expect(s.todos.visible.map(\.id) == [1, 3])
        #expect(s.todos.tasks.map(\.id) == [1, 3])
    }

    @Test func aClearedListStartsOver() {
        var s = run(SessionState(), hello(false, "s1"), start)
        s = run(s, toolEnd("t1", details(3, task(1, "A", "completed"), task(2, "B", "pending"))), settled, start)
        #expect(s.todos.visible.map(\.id) == [2])
        // Cleared, then a new task 1 that is completed at once: it is a different task and shows.
        s = run(s, toolEnd("t2", details(1)), toolEnd("t3", details(3, task(1, "New", "completed"), task(2, "Next", "pending"))))
        #expect(s.todos.visible.map(\.subject) == ["New", "Next"])
    }

    @Test func aReconnectWithoutTodoResultsKeepsTheList() {
        var s = run(SessionState(), hello(true, "s1"))
        s = run(s, toolEnd("t1", details(2, task(1, "A", "in_progress"))))
        // After a compaction the messages no longer hold the todo call.
        let again = run(s, hello(true, "s1", user))
        #expect(again.todos.visible.map(\.id) == [1])
        // Another session starts empty.
        let other = run(s, hello(false, "s2", user))
        #expect(other.todos.visible.isEmpty)
    }
}
