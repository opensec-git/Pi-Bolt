package opensec.pibolt.protocol.session

import kotlinx.serialization.json.JsonObject
import opensec.pibolt.protocol.rpc.ModelInfo

/** One row of the conversation as the app shows it. [key] is stable across updates of the same row. */
sealed interface ChatItem {
    val key: String
}

data class UserItem(override val key: String, val text: String, val imageCount: Int = 0) : ChatItem

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
) : ChatItem {
    val isError: Boolean get() = status == ToolStatus.Error
    val isActive: Boolean get() = status == ToolStatus.Streaming || status == ToolStatus.Pending || status == ToolStatus.Running
}

enum class NoticeKind { Info, Error, Aborted, Compaction, Summary, Custom }

data class NoticeItem(override val key: String, val kind: NoticeKind, val title: String, val text: String = "") : ChatItem

data class HostInfo(val name: String, val version: String, val cwd: String)

data class SlashCommand(val name: String, val description: String?, val source: String)

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
    val stream: StreamingMessage? = null,
    val seq: Long = 0,
) {
    val allItems: List<ChatItem> get() = if (streaming.isEmpty()) items else items + streaming
}

/** The assistant message being streamed: its key and its blocks by content index. */
data class StreamingMessage(val key: String, val blocks: List<ChatItem?>)
