package opensec.pibolt.protocol.session

import kotlinx.serialization.json.JsonObject
import opensec.pibolt.protocol.rpc.ModelInfo
import opensec.pibolt.protocol.rpc.ImageAttachment

/** One row of the conversation as the app shows it. [key] is stable across updates of the same row. */
sealed interface ChatItem {
    val key: String
}

/** A user message; [timestamp] is the message's own (Pi's `timestamp`, ms), which ties it to its turn checkpoints. */
data class UserItem(override val key: String, val text: String, val imageCount: Int = 0, val timestamp: Long? = null) : ChatItem

data class AssistantTextItem(override val key: String, val text: String, val streaming: Boolean) : ChatItem

data class ThinkingItem(override val key: String, val text: String, val streaming: Boolean, val redacted: Boolean = false) : ChatItem

enum class ToolStatus { Streaming, Pending, Running, Done, Error, Cancelled }

data class ToolItem(
    override val key: String,
    val toolCallId: String,
    val name: String,
    /** Parsed arguments once known. */
    val args: JsonObject?,
    /** Raw argument text while the model is still writing the call. */
    val argsText: String = "",
    val status: ToolStatus,
    /** Latest partial output while the tool runs. */
    val output: String = "",
    /** Final result text. */
    val result: String? = null,
    val details: JsonObject? = null,
    val durationMs: Long? = null,
    /** Images in the result (`content` items of type image), such as a screenshot the read tool opened. */
    val images: List<ImageAttachment> = emptyList(),
) : ChatItem {
    val isError: Boolean get() = status == ToolStatus.Error
    val isActive: Boolean get() = status == ToolStatus.Streaming || status == ToolStatus.Pending || status == ToolStatus.Running
}

enum class NoticeKind { Info, Error, Aborted, Compaction, Summary, Custom }

data class NoticeItem(override val key: String, val kind: NoticeKind, val title: String, val text: String = "") : ChatItem

/**
 * Background subagents ended (the subagents extension's `subagent-notification`): their [agentIds], the runs they
 * ended ([runKeys], keys of [SessionState.subagents]; a later resume of the same agent is another run) and the message.
 */
data class SubagentNoticeItem(override val key: String, val agentIds: List<String>, val text: String, val runKeys: List<String> = emptyList()) : ChatItem

/**
 * [service]: the computer's background service answers (`host.service`), so it can list and open its projects' sessions.
 * [parallelSessions]: that service keeps one runtime per open session (`host.parallelSessions`), so switching or starting
 * a session never stops a running one; an older service has one runtime, and the phone refuses to switch it mid-turn.
 * [review]: the host answers the read-only review commands (`host.review`: `rc_git_*`, `rc_list_dir`, `rc_read_file`).
 * [features]: optional protocol parts the computer answers (`remote_hello.features`), such as [SUBAGENT_STREAM_FEATURE].
 */
data class HostInfo(
    val name: String,
    val version: String,
    val cwd: String,
    val service: Boolean = false,
    val parallelSessions: Boolean = false,
    val review: Boolean = false,
    val features: Set<String> = emptySet(),
) {
    /** The computer streams a subagent's own conversation on request (`subagent_subscribe`, Pi-Bolt 1.1.5 and later). */
    val streamsSubagents: Boolean get() = SUBAGENT_STREAM_FEATURE in features
}

const val SUBAGENT_STREAM_FEATURE = "subagent_stream"

/** A command from `get_commands`. [argumentHint] is shown while typing its arguments (hosts that send one). */
data class SlashCommand(val name: String, val description: String?, val source: String, val argumentHint: String? = null)

enum class DialogKind { Select, Confirm, Input, Editor }

data class DialogRequest(
    val id: String,
    val kind: DialogKind,
    val title: String,
    val message: String? = null,
    val options: List<String> = emptyList(),
    val placeholder: String? = null,
    val prefill: String? = null,
    val timeoutMs: Long? = null,
)

data class QueueState(val steering: List<String> = emptyList(), val followUp: List<String> = emptyList()) {
    val isEmpty: Boolean get() = steering.isEmpty() && followUp.isEmpty()
    val size: Int get() = steering.size + followUp.size
}

data class RetryInfo(val attempt: Int, val maxAttempts: Int, val delayMs: Long, val error: String?)

data class Usage(val input: Long, val output: Long, val cacheRead: Long, val totalTokens: Long, val cost: Double)

/**
 * Everything the session screen shows. Built only by [SessionReducer].
 *
 * The finished conversation is [items]; the assistant message that is still streaming is [streaming], kept apart so a
 * text delta touches only that short list.
 */
data class SessionState(
    val hello: Boolean = false,
    val host: HostInfo? = null,
    val items: List<ChatItem> = emptyList(),
    val streaming: List<ChatItem> = emptyList(),
    val isRunning: Boolean = false,
    val isCompacting: Boolean = false,
    /** Why the running compaction started: `manual`, `threshold` (context nearly full) or `overflow`; null when none runs. */
    val compactionReason: String? = null,
    val model: ModelInfo? = null,
    val thinkingLevel: String? = null,
    val sessionName: String? = null,
    val sessionId: String? = null,
    val queue: QueueState = QueueState(),
    val dialogs: List<DialogRequest> = emptyList(),
    val commands: List<SlashCommand> = emptyList(),
    val statuses: Map<String, String> = emptyMap(),
    val widgets: Map<String, List<String>> = emptyMap(),
    val retry: RetryInfo? = null,
    val lastUsage: Usage? = null,
    /** The todo extension's list, built from its `todo` tool results. */
    val todos: TodoState = TodoState(),
    /** Subagent runs (opensec-pi-subagents) by the id of the `Agent` call that started them, oldest first. */
    val subagents: Map<String, Subagent> = emptyMap(),
    val stream: StreamingMessage? = null,
    val seq: Long = 0,
) {
    val allItems: List<ChatItem> get() = if (streaming.isEmpty()) items else items + streaming
}

/** The assistant message being streamed: its key and its blocks by content index. */
data class StreamingMessage(val key: String, val blocks: List<ChatItem?>)

enum class TodoStatus { Pending, InProgress, Completed }

/** One task of the todo extension's list. [activeForm] is the label shown while it is in progress. */
data class TodoTask(val id: Int, val subject: String, val status: TodoStatus, val activeForm: String? = null)

/**
 * The todo list as the panel shows it: the latest `todo` tool result's [tasks] (deleted ones dropped) minus the
 * completed tasks in [hidden]. Completed tasks are hidden when the next prompt starts, and all of them once a run ends
 * with every task done, so a finished list closes the panel.
 */
data class TodoState(val tasks: List<TodoTask> = emptyList(), val hidden: Set<Int> = emptySet(), val nextId: Int = 1) {
    /** The rows the panel shows, in list order. Empty: no panel. */
    val visible: List<TodoTask> = if (hidden.isEmpty()) tasks else tasks.filterNot { it.status == TodoStatus.Completed && it.id in hidden }
}
