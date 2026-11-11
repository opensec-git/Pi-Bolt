import Foundation

/// Where a subagent run is. Queued waits for a slot; done, failed and stopped are final.
public enum SubagentStatus: String, Equatable, Sendable {
    case queued, running, done, failed, stopped

    public var isActive: Bool { self == .queued || self == .running }
}

/// One run of a subagent of the subagents extension (opensec-pi-subagents), started by an `Agent` call. `key` is that
/// call's id. Built by `SubagentReducer` from the call's arguments, its partial and final results, the extension's
/// completion notifications and, from a computer that streams them, the subagent's own conversation (`transcript`).
public struct Subagent: Equatable, Sendable, Identifiable {
    public var key: String
    /// The extension's agent id: in the result of a background start, else once a foreground run ends.
    public var agentId: String?
    /// The agent type as the extension names it ("Explore", "general-purpose").
    public var type: String = "Agent"
    /// The task title the model gave the call (its `description`).
    public var description: String = ""
    public var prompt: String = ""
    public var background = false
    /// An `Agent({ resume })` call: it continues the run of `agentId`.
    public var resumed = false
    public var status: SubagentStatus = .queued
    /// What it is doing now ("reading 3 files…", "running command…"). Nil when unknown.
    public var activity: String?
    public var toolUses = 0
    public var turns = 0
    public var maxTurns: Int?
    /// Tokens used so far, formatted ("12.3k").
    public var tokens: String?
    /// Run time as last reported, in whole seconds while it runs.
    public var durationMs: Int64?
    /// Epoch ms when it started, when a message timestamp tells.
    public var startedAt: Int64?
    public var model: String?
    /// The final answer (or the preview a notification carries until the full answer is known).
    public var result: String?
    public var error: String?
    /// True when the answer of an `Agent` call reached Pi (foreground) or Pi fetched it (`result` is complete).
    public var resultComplete = false
    /// The subagent's own messages, streamed by the computer while its screen is open. Nil until then.
    public var transcript: SubagentTranscript?

    public init(key: String) { self.key = key }

    public var id: String { key }
    public var isActive: Bool { status.isActive }
}

/// A subagent's conversation as the computer streams it (`subagent_entries`, `subagent_events`): rows like the main
/// transcript's and their key counter. `stream` / `live`: the message it writes now; its rows follow `items` until the
/// finished message arrives and takes their keys.
public struct SubagentTranscript: Equatable, Sendable {
    public var file: String
    public var items: [ChatItem] = []
    public var seq: Int64 = 0
    /// Summed token use of its assistant messages (input + output + cache writes, as the extension counts).
    public var tokens: Int64 = 0
    public var assistantMessages = 0
    public var firstAt: Int64?
    public var stream: StreamingMessage?
    public var live: [ChatItem] = []
    /// Counter of streamed messages: their keys (`live<n>`) never meet the file's (`m<n>`).
    public var liveSeq: Int64 = 0

    public init(file: String) { self.file = file }

    /// The rows to show: the finished ones, then the message being written.
    public var allItems: [ChatItem] { live.isEmpty ? items : items + live }
}

/// Subagent runs by the id of the `Agent` call that started them, oldest first (Kotlin's insertion-ordered map).
public struct SubagentRuns: Equatable, Sendable {
    public private(set) var keys: [String] = []
    private var runs: [String: Subagent] = [:]

    public init() {}

    public subscript(key: String) -> Subagent? { runs[key] }

    public var isEmpty: Bool { keys.isEmpty }
    public var count: Int { keys.count }
    /// The runs, oldest first.
    public var values: [Subagent] { keys.compactMap { runs[$0] } }

    public func contains(_ key: String) -> Bool { runs[key] != nil }

    /// Insert or replace; a new run goes last.
    public mutating func put(_ run: Subagent) {
        if runs[run.key] == nil { keys.append(run.key) }
        runs[run.key] = run
    }

    /// The newest run that matches.
    public func last(where predicate: (Subagent) -> Bool) -> Subagent? {
        for key in keys.reversed() { if let run = runs[key], predicate(run) { return run } }
        return nil
    }
}

public enum TodoStatus: String, Equatable, Sendable {
    case pending
    case inProgress = "in_progress"
    case completed
}

/// One task of the todo extension's list. `activeForm` is the label shown while it is in progress.
public struct TodoTask: Equatable, Sendable, Identifiable {
    public var id: Int
    public var subject: String
    public var status: TodoStatus
    public var activeForm: String?

    public init(id: Int, subject: String, status: TodoStatus, activeForm: String? = nil) {
        self.id = id
        self.subject = subject
        self.status = status
        self.activeForm = activeForm
    }
}

/// The todo list as the panel shows it: the latest `todo` tool result's `tasks` (deleted ones dropped) minus the
/// completed tasks in `hidden`. Completed tasks are hidden when the next prompt starts, and all of them once a run ends
/// with every task done, so a finished list closes the panel.
public struct TodoState: Equatable, Sendable {
    public var tasks: [TodoTask] = []
    public var hidden: Set<Int> = []
    public var nextId = 1

    public init(tasks: [TodoTask] = [], hidden: Set<Int> = [], nextId: Int = 1) {
        self.tasks = tasks
        self.hidden = hidden
        self.nextId = nextId
    }

    /// The rows the panel shows, in list order. Empty: no panel.
    public var visible: [TodoTask] {
        hidden.isEmpty ? tasks : tasks.filter { !($0.status == .completed && hidden.contains($0.id)) }
    }
}
