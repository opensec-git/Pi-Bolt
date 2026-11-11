import Foundation

/// A user message; `timestamp` is the message's own (Pi's `timestamp`, ms).
public struct UserItem: Equatable, Sendable {
    public var key: String
    public var text: String
    public var imageCount: Int = 0
    public var timestamp: Int64?
}

public struct AssistantTextItem: Equatable, Sendable {
    public var key: String
    public var text: String
    public var streaming: Bool
}

public struct ThinkingItem: Equatable, Sendable {
    public var key: String
    public var text: String
    public var streaming: Bool
    public var redacted: Bool = false
}

public enum ToolStatus: String, Equatable, Sendable {
    case streaming, pending, running, done, error, cancelled
}

public struct ImageAttachment: Equatable, Sendable {
    public var base64: String
    public var mimeType: String

    public init(base64: String, mimeType: String) {
        self.base64 = base64
        self.mimeType = mimeType
    }
}

public struct ToolItem: Equatable, Sendable {
    public var key: String
    public var toolCallId: String
    public var name: String
    /// Parsed arguments once known.
    public var args: JSON?
    /// Raw argument text while the model is still writing the call.
    public var argsText: String = ""
    public var status: ToolStatus
    /// Latest partial output while the tool runs.
    public var output: String = ""
    /// Final result text.
    public var result: String?
    public var details: JSON?
    public var durationMs: Int64?
    /// Images in the result (`content` items of type image).
    public var images: [ImageAttachment] = []

    public init(key: String, toolCallId: String, name: String, args: JSON?, argsText: String = "", status: ToolStatus, output: String = "", result: String? = nil, details: JSON? = nil, durationMs: Int64? = nil, images: [ImageAttachment] = []) {
        self.key = key
        self.toolCallId = toolCallId
        self.name = name
        self.args = args
        self.argsText = argsText
        self.status = status
        self.output = output
        self.result = result
        self.details = details
        self.durationMs = durationMs
        self.images = images
    }

    public var isError: Bool { status == .error }
    public var isActive: Bool { status == .streaming || status == .pending || status == .running }
}

public enum NoticeKind: String, Equatable, Sendable {
    case info, error, aborted, compaction, summary, custom
}

public struct NoticeItem: Equatable, Sendable {
    public var key: String
    public var kind: NoticeKind
    public var title: String
    public var text: String = ""
}

/// Background subagents ended (the subagents extension's `subagent-notification`): their `agentIds`, the runs they
/// ended (`runKeys`, keys of `SessionState.subagents`; a later resume of the same agent is another run) and the message.
public struct SubagentNoticeItem: Equatable, Sendable {
    public var key: String
    public var agentIds: [String]
    public var text: String
    public var runKeys: [String] = []
}

/// One row of the conversation as the app shows it. `key` is stable across updates of the same row.
public enum ChatItem: Equatable, Sendable, Identifiable {
    case user(UserItem)
    case assistant(AssistantTextItem)
    case thinking(ThinkingItem)
    case tool(ToolItem)
    case notice(NoticeItem)
    case subagentNotice(SubagentNoticeItem)

    public var key: String {
        switch self {
        case let .user(i): i.key
        case let .assistant(i): i.key
        case let .thinking(i): i.key
        case let .tool(i): i.key
        case let .notice(i): i.key
        case let .subagentNotice(i): i.key
        }
    }

    public var id: String { key }

    public var tool: ToolItem? {
        if case let .tool(t) = self { return t }
        return nil
    }

    public var assistantText: AssistantTextItem? {
        if case let .assistant(t) = self { return t }
        return nil
    }

    public var thinking: ThinkingItem? {
        if case let .thinking(t) = self { return t }
        return nil
    }
}

/// `service`: the computer's background service answers (`host.service`), so it can list and open its projects'
/// sessions. `parallelSessions`: that service keeps one runtime per open session.
public struct HostInfo: Equatable, Sendable {
    public var name: String
    public var version: String
    public var cwd: String
    public var service: Bool = false
    public var parallelSessions: Bool = false
    /// The host answers the read-only review commands (`host.review`: `rc_git_*`, `rc_list_dir`, `rc_read_file`).
    public var review: Bool = false
    /// Optional protocol parts the computer answers (`remote_hello.features`), such as `subagentStreamFeature`.
    public var features: Set<String> = []

    /// The computer streams a subagent's own conversation on request (`subagent_subscribe`, Pi-Bolt 1.1.5 and later).
    public var streamsSubagents: Bool { features.contains(subagentStreamFeature) }
}

public let subagentStreamFeature = "subagent_stream"

/// A command from `get_commands`.
public struct SlashCommand: Equatable, Sendable {
    public var name: String
    public var description: String?
    public var source: String
    public var argumentHint: String?
}

public enum DialogKind: String, Equatable, Sendable {
    case select, confirm, input, editor
}

public struct DialogRequest: Equatable, Sendable, Identifiable {
    public var id: String
    public var kind: DialogKind
    public var title: String
    public var message: String?
    public var options: [String] = []
    public var placeholder: String?
    public var prefill: String?
    public var timeoutMs: Int64?
}

public struct QueueState: Equatable, Sendable {
    public var steering: [String] = []
    public var followUp: [String] = []
    public init(steering: [String] = [], followUp: [String] = []) {
        self.steering = steering
        self.followUp = followUp
    }

    public var isEmpty: Bool { steering.isEmpty && followUp.isEmpty }
    public var count: Int { steering.count + followUp.count }
}

public struct RetryInfo: Equatable, Sendable {
    public var attempt: Int
    public var maxAttempts: Int
    public var delayMs: Int64
    public var error: String?
}

public struct Usage: Equatable, Sendable {
    public var input: Int64
    public var output: Int64
    public var cacheRead: Int64
    public var totalTokens: Int64
    public var cost: Double
}

/// The assistant message being streamed: its key and its blocks by content index.
public struct StreamingMessage: Equatable, Sendable {
    public var key: String
    public var blocks: [ChatItem?]
}

/// Everything the session screen shows. Built only by `SessionReducer`.
///
/// The finished conversation is `items`; the assistant message that is still streaming is `streaming`, kept apart so a
/// text delta touches only that short list.
public struct SessionState: Equatable, Sendable {
    public var hello = false
    public var host: HostInfo?
    public var items: [ChatItem] = []
    public var streaming: [ChatItem] = []
    public var isRunning = false
    public var isCompacting = false
    public var compactionReason: String?
    public var model: ModelInfo?
    public var thinkingLevel: String?
    public var sessionName: String?
    public var sessionId: String?
    /// The session's file on the computer: what the background service's `switch_session` takes.
    public var sessionFile: String?
    public var queue = QueueState()
    public var dialogs: [DialogRequest] = []
    public var commands: [SlashCommand] = []
    public var statuses: [String: String] = [:]
    public var widgets: [String: [String]] = [:]
    public var retry: RetryInfo?
    public var lastUsage: Usage?
    /// The todo extension's list, built from its `todo` tool results.
    public var todos = TodoState()
    /// Subagent runs (opensec-pi-subagents) by the id of the `Agent` call that started them, oldest first.
    public var subagents = SubagentRuns()
    public var stream: StreamingMessage?
    public var seq: Int64 = 0

    public init() {}

    public var allItems: [ChatItem] { streaming.isEmpty ? items : items + streaming }
}
