package opensec.pibolt.protocol.session

/** Where a subagent run is. Queued waits for a slot; Done, Failed and Stopped are final. */
enum class SubagentStatus {
    Queued,
    Running,
    Done,
    Failed,
    Stopped,
    ;

    val isActive: Boolean get() = this == Queued || this == Running
}

/**
 * One run of a subagent of the subagents extension (opensec-pi-subagents), started by an `Agent` call. [key] is that
 * call's id. Built by [SubagentReducer] from the call's arguments, its partial and final results, the extension's
 * completion notifications and, on this phone, the subagent's own session file ([transcript]).
 */
data class Subagent(
    val key: String,
    /** The extension's agent id: in the result of a background start, else once a foreground run ends. */
    val agentId: String? = null,
    /** The agent type as the extension names it ("Explore", "general-purpose"). */
    val type: String = "Agent",
    /** The task title the model gave the call (its `description`). */
    val description: String = "",
    val prompt: String = "",
    val background: Boolean = false,
    /** An `Agent({ resume })` call: it continues the run of [agentId]. */
    val resumed: Boolean = false,
    val status: SubagentStatus = SubagentStatus.Queued,
    /** What it is doing now ("reading 3 files…", "running command…", the start of its reply). Null when unknown. */
    val activity: String? = null,
    val toolUses: Int = 0,
    val turns: Int = 0,
    val maxTurns: Int? = null,
    /** Tokens used so far, formatted ("12.3k"). */
    val tokens: String? = null,
    /** Run time as last reported, in whole seconds while it runs. */
    val durationMs: Long? = null,
    /** Epoch ms when it started, when a message timestamp tells. */
    val startedAt: Long? = null,
    val model: String? = null,
    /** The final answer (or the preview a notification carries until the full answer is known). */
    val result: String? = null,
    val error: String? = null,
    /** True when the answer of an `Agent` call reached Pi (foreground) or Pi fetched it ([result] is complete). */
    val resultComplete: Boolean = false,
    /** The subagent's own messages, from its session file. Null when not read (or not readable: remote control). */
    val transcript: SubagentTranscript? = null,
) {
    val isActive: Boolean get() = status.isActive
}

/**
 * A subagent's conversation as read from its session [file]: rows like the main transcript's, and their key counter.
 * [stream] / [live]: the message it writes now, while the computer (or this phone's engine) streams it
 * (`subagent_events`); its rows ([live]) follow [items] until the finished message arrives and takes their keys.
 */
data class SubagentTranscript(
    val file: String,
    val items: List<ChatItem> = emptyList(),
    val seq: Long = 0,
    /** Summed token use of its assistant messages (input + output + cache writes, as the extension counts). */
    val tokens: Long = 0,
    val assistantMessages: Int = 0,
    val firstAt: Long? = null,
    val stream: StreamingMessage? = null,
    val live: List<ChatItem> = emptyList(),
    /** Counter of streamed messages: their keys (`live<n>`) never meet the file's (`m<n>`). */
    val liveSeq: Long = 0,
) {
    /** The rows to show: the finished ones, then the message being written. */
    val allItems: List<ChatItem> get() = if (live.isEmpty()) items else items + live
}
