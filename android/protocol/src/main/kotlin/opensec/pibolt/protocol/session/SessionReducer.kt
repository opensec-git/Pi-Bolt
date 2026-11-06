package opensec.pibolt.protocol.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.arr
import opensec.pibolt.protocol.bool
import opensec.pibolt.protocol.double
import opensec.pibolt.protocol.int
import opensec.pibolt.protocol.long
import opensec.pibolt.protocol.obj
import opensec.pibolt.protocol.rpc.ModelInfo
import opensec.pibolt.protocol.str
import opensec.pibolt.protocol.type

/**
 * Pure reducer: `remote_hello` snapshot + session events -> [SessionState]. Unknown types and fields are ignored.
 * See docs/json.md (event stream) and docs/remote-control.md (`remote_hello`).
 */
object SessionReducer {
    /** More content blocks than any real message has; a larger contentIndex from the wire is ignored. */
    private const val MAX_CONTENT_BLOCKS = 1024

    fun reduce(state: SessionState, event: JsonObject): SessionState = when (event.type) {
        "remote_hello" -> keepTodos(state, fromHello(event))
        // A new run: the completed rows of the previous one leave the todo panel.
        "agent_start" -> state.copy(isRunning = true, todos = hideCompleted(state.todos))
        "agent_settled" -> settle(state)
        "message_start" -> messageStart(state, event)
        "message_update" -> messageUpdate(state, event)
        "message_end" -> messageEnd(state, event)
        "tool_execution_start" -> toolStart(state, event)
        "tool_execution_update" -> toolUpdate(state, event)
        "tool_execution_end" -> toolEnd(state, event)
        "queue_update" -> state.copy(queue = QueueState(event.strings("steering"), event.strings("followUp")))
        "compaction_start" -> state.copy(isCompacting = true)
        "compaction_end" -> compactionEnd(state, event)
        "auto_retry_start" -> state.copy(
            retry = RetryInfo(
                attempt = event.int("attempt") ?: 1,
                maxAttempts = event.int("maxAttempts") ?: 1,
                delayMs = event.long("delayMs") ?: 0,
                error = event.str("errorMessage"),
            ),
        )
        "auto_retry_end" -> {
            val cleared = state.copy(retry = null)
            if (event.bool("success") == false) cleared.notice(NoticeKind.Error, "Retry failed", event.str("finalError") ?: "") else cleared
        }
        "thinking_level_changed" -> state.copy(thinkingLevel = event.str("level") ?: state.thinkingLevel)
        "session_info_changed" -> state.copy(sessionName = event.str("name"))
        "extension_ui_request" -> extensionRequest(state, event)
        "extension_ui_dismissed" -> event.str("id")?.let { id -> state.copy(dialogs = state.dialogs.filterNot { it.id == id }) } ?: state
        "extension_error" -> state.notice(NoticeKind.Error, "Extension error", event.str("error") ?: "")
        else -> state
    }

    /** Apply a `get_state` result (also the `state` field of `remote_hello`). */
    fun applyRpcState(state: SessionState, rpc: JsonObject?): SessionState {
        rpc ?: return state
        return state.copy(
            model = ModelInfo.from(rpc.obj("model")) ?: state.model,
            thinkingLevel = rpc.str("thinkingLevel") ?: state.thinkingLevel,
            isRunning = rpc.bool("isStreaming") ?: state.isRunning,
            isCompacting = rpc.bool("isCompacting") ?: state.isCompacting,
            sessionName = rpc.str("sessionName"),
            sessionId = rpc.str("sessionId") ?: state.sessionId,
        )
    }

    fun fromHello(hello: JsonObject): SessionState {
        val host = hello.obj("host")
        var state = SessionState(
            hello = true,
            host = HostInfo(host?.str("name") ?: "Computer", host?.str("version") ?: "", host?.str("cwd") ?: ""),
        )
        state = applyRpcState(state, hello.obj("state"))
        for (message in hello.arr("messages").orEmpty()) {
            val m = message as? JsonObject ?: continue
            // Each prompt in the history started a run, which hid the completed todos of the run before.
            if (m.str("role") == "user") state = state.copy(todos = hideCompleted(state.todos))
            state = commitMessage(state, m, key = null)
        }
        hello.obj("streamingMessage")?.let { streaming ->
            if (streaming.str("role") == "assistant") state = startStream(state, streaming)
        }
        if (!state.isRunning) state = closeDanglingTools(state).let { it.copy(todos = hideFinished(it.todos)) }
        state = state.copy(
            dialogs = hello.arr("dialogs").orEmpty().mapNotNull { (it as? JsonObject)?.let(::dialogFrom) },
            commands = hello.arr("commands").orEmpty().mapNotNull { (it as? JsonObject)?.let(::commandFrom) },
        )
        return state
    }

    /**
     * Two consecutive `message_update` deltas of the same block as one (text, thinking or tool-call arguments), or null
     * when they cannot merge. Applying the merged event equals applying both; a frame's worth of tokens then costs one
     * string append and one state copy instead of one per token.
     */
    fun mergeDeltas(first: JsonObject, second: JsonObject): JsonObject? {
        if (first.type != "message_update" || second.type != "message_update") return null
        val a = first.obj("assistantMessageEvent") ?: return null
        val b = second.obj("assistantMessageEvent") ?: return null
        val kind = a.type
        if (kind != b.type || kind !in MERGEABLE_DELTAS || a.int("contentIndex") != b.int("contentIndex")) return null
        val text = (a.str("delta") ?: return null) + (b.str("delta") ?: return null)
        // The later event's usage is the newer one.
        return JsonObject(second + ("assistantMessageEvent" to JsonObject(b + ("delta" to JsonPrimitive(text)))))
    }

    private val MERGEABLE_DELTAS = setOf("text_delta", "thinking_delta", "toolcall_delta")

    fun commandsFrom(data: JsonObject?): List<SlashCommand> = data?.arr("commands").orEmpty().mapNotNull { (it as? JsonObject)?.let(::commandFrom) }

    // Messages

    private fun messageStart(state: SessionState, event: JsonObject): SessionState {
        val message = event.obj("message") ?: return state
        return if (message.str("role") == "assistant") startStream(state, message) else state
    }

    private fun startStream(state: SessionState, message: JsonObject): SessionState {
        val key = "m${state.seq}"
        val blocks = message.arr("content").orEmpty().mapIndexed { i, block -> blockItem(key, i, block as? JsonObject, streaming = true) }
        val stream = StreamingMessage(key, blocks)
        return state.copy(seq = state.seq + 1, stream = stream, streaming = blocks.filterNotNull())
    }

    private fun messageUpdate(state: SessionState, event: JsonObject): SessionState {
        val delta = event.obj("assistantMessageEvent") ?: return state
        var s = state
        if (s.stream == null) s = startStream(s, JsonObject(emptyMap()))
        val stream = s.stream!!
        val usage = event.obj("usage")?.let(::usageFrom) ?: s.lastUsage
        // From the wire: a negative or absurd index is ignored rather than throwing or padding the list until OOM.
        val index = delta.int("contentIndex")?.takeIf { it in 0 until MAX_CONTENT_BLOCKS } ?: return s.copy(lastUsage = usage)
        val blocks = stream.blocks.toMutableList()
        while (blocks.size <= index) blocks.add(null)
        val current = blocks[index]
        val key = "${stream.key}.$index"
        val updated: ChatItem? = when (delta.type) {
            "text_start" -> AssistantTextItem(key, "", streaming = true)
            "text_delta" -> {
                val text = (current as? AssistantTextItem)?.text.orEmpty()
                AssistantTextItem(key, text + (delta.str("delta") ?: ""), streaming = true)
            }
            "text_end" -> AssistantTextItem(key, delta.str("content") ?: (current as? AssistantTextItem)?.text.orEmpty(), streaming = true)
            "thinking_start" -> ThinkingItem(key, "", streaming = true)
            "thinking_delta" -> {
                val text = (current as? ThinkingItem)?.text.orEmpty()
                ThinkingItem(key, text + (delta.str("delta") ?: ""), streaming = true)
            }
            "thinking_end" -> ThinkingItem(key, delta.str("content") ?: (current as? ThinkingItem)?.text.orEmpty(), streaming = false)
            "toolcall_start" -> {
                val id = delta.str("id") ?: key
                ToolItem("tool:$id", id, delta.str("toolName") ?: "tool", args = null, status = ToolStatus.Streaming)
            }
            "toolcall_delta" -> (current as? ToolItem)?.let { it.copy(argsText = it.argsText + (delta.str("delta") ?: "")) }
                ?: current
            "toolcall_end" -> {
                val call = delta.obj("toolCall")
                val id = call?.str("id") ?: (current as? ToolItem)?.toolCallId ?: key
                ToolItem(
                    key = "tool:$id",
                    toolCallId = id,
                    name = call?.str("name") ?: (current as? ToolItem)?.name ?: "tool",
                    args = call?.obj("arguments"),
                    argsText = (current as? ToolItem)?.argsText.orEmpty(),
                    status = ToolStatus.Pending,
                )
            }
            else -> current
        }
        blocks[index] = updated
        return s.copy(stream = stream.copy(blocks = blocks), streaming = blocks.filterNotNull(), lastUsage = usage)
    }

    private fun messageEnd(state: SessionState, event: JsonObject): SessionState {
        val message = event.obj("message") ?: return state
        if (message.str("role") == "assistant") {
            val key = state.stream?.key
            val cleared = state.copy(stream = null, streaming = emptyList())
            val withUsage = message.obj("usage")?.let { cleared.copy(lastUsage = usageFrom(it)) } ?: cleared
            return commitMessage(withUsage, message, key)
        }
        return commitMessage(state, message, key = null)
    }

    /** Add a finished message to [SessionState.items]. [key] reuses the streaming key so rows keep their identity. */
    private fun commitMessage(state: SessionState, message: JsonObject, key: String?): SessionState {
        val nextSeq = if (key == null) state.seq + 1 else state.seq
        val k = key ?: "m${state.seq}"
        val s = state.copy(seq = nextSeq)
        return when (message.str("role")) {
            "user" -> {
                val (text, images) = contentText(message["content"])
                if (text.isBlank() && images == 0) s else s.copy(items = s.items + UserItem(k, text, images))
            }
            "assistant" -> {
                val items = s.items.toMutableList()
                message.arr("content").orEmpty().forEachIndexed { i, block ->
                    val item = blockItem(k, i, block as? JsonObject, streaming = false) ?: return@forEachIndexed
                    if (item is ToolItem) {
                        // A tool call already known (from execution events) keeps its progress.
                        val existing = items.indexOfLast { it is ToolItem && it.toolCallId == item.toolCallId }
                        if (existing >= 0) {
                            val old = items[existing] as ToolItem
                            items[existing] = old.copy(name = item.name, args = item.args ?: old.args)
                            return@forEachIndexed
                        }
                    }
                    if (item is AssistantTextItem && item.text.isBlank()) return@forEachIndexed
                    items += item
                }
                when (message.str("stopReason")) {
                    "error" -> items += NoticeItem("$k.error", NoticeKind.Error, "Error", message.str("errorMessage") ?: "The model returned an error")
                    "aborted" -> items += NoticeItem("$k.aborted", NoticeKind.Aborted, "Aborted", message.str("errorMessage").orEmpty())
                    "length" -> items += NoticeItem("$k.length", NoticeKind.Info, "Stopped at the output limit")
                }
                s.copy(items = items)
            }
            "toolResult" -> {
                val id = message.str("toolCallId") ?: return s
                val (text, images) = contentText(message["content"])
                val result = if (images > 0) listOf(text, "[${images} image${if (images > 1) "s" else ""}]").filter { it.isNotBlank() }.joinToString("\n") else text
                val isError = message.bool("isError") ?: false
                val withTodos = if (message.str("toolName") == TODO_TOOL && !isError) s.copy(todos = applyTodo(s.todos, message.obj("details"))) else s
                updateTool(withTodos, id, message.str("toolName")) {
                    it.copy(
                        status = if (isError) ToolStatus.Error else ToolStatus.Done,
                        result = result,
                        details = message.obj("details") ?: it.details,
                        durationMs = it.durationMs ?: message.long("durationMs"),
                    )
                }
            }
            "bashExecution" -> {
                val exit = message.int("exitCode")
                val cancelled = message.bool("cancelled") ?: false
                val command = message.str("command").orEmpty()
                s.copy(
                    items = s.items + ToolItem(
                        key = "$k.bash",
                        toolCallId = "$k.bash",
                        name = "bash",
                        args = JsonObject(mapOf("command" to JsonPrimitive(command))),
                        status = if (cancelled) ToolStatus.Cancelled else if (exit != null && exit != 0) ToolStatus.Error else ToolStatus.Done,
                        result = message.str("output").orEmpty(),
                    ),
                )
            }
            "custom" -> {
                if (message.bool("display") == false) return s
                val (text, _) = contentText(message["content"])
                s.copy(items = s.items + NoticeItem(k, NoticeKind.Custom, message.str("customType") ?: "Message", text))
            }
            "compactionSummary" -> s.copy(
                items = s.items + NoticeItem(k, NoticeKind.Compaction, compactedTitle(message.long("tokensBefore"), null), message.str("summary").orEmpty()),
            )
            "branchSummary" -> s.copy(items = s.items + NoticeItem(k, NoticeKind.Summary, "Branch summary", message.str("summary").orEmpty()))
            else -> state
        }
    }

    private fun blockItem(messageKey: String, index: Int, block: JsonObject?, streaming: Boolean): ChatItem? {
        block ?: return null
        val key = "$messageKey.$index"
        return when (block.type) {
            "text" -> AssistantTextItem(key, block.str("text").orEmpty(), streaming)
            "thinking" -> {
                val text = block.str("thinking").orEmpty()
                val redacted = block.bool("redacted") ?: false
                if (text.isBlank() && !redacted) null else ThinkingItem(key, text, streaming = false, redacted = redacted)
            }
            "toolCall" -> {
                val id = block.str("id") ?: key
                ToolItem("tool:$id", id, block.str("name") ?: "tool", block.obj("arguments"), status = if (streaming) ToolStatus.Streaming else ToolStatus.Pending)
            }
            else -> null
        }
    }

    // Tools

    private fun toolStart(state: SessionState, event: JsonObject): SessionState {
        val id = event.str("toolCallId") ?: return state
        return updateTool(state, id, event.str("toolName")) {
            it.copy(status = ToolStatus.Running, args = event.obj("args") ?: it.args)
        }
    }

    private fun toolUpdate(state: SessionState, event: JsonObject): SessionState {
        val id = event.str("toolCallId") ?: return state
        val (text, _) = contentText(event.obj("partialResult")?.get("content"))
        // The partial details too: a subagent reports what it is doing there.
        val details = event.obj("partialResult")?.obj("details")
        return updateTool(state, id, event.str("toolName")) {
            it.copy(status = ToolStatus.Running, output = text, details = details ?: it.details)
        }
    }

    private fun toolEnd(state: SessionState, event: JsonObject): SessionState {
        val id = event.str("toolCallId") ?: return state
        val result = event.obj("result")
        val (text, _) = contentText(result?.get("content"))
        val isError = event.bool("isError") ?: false
        val name = event.str("toolName") ?: (state.allItems.lastOrNull { it is ToolItem && it.toolCallId == id } as? ToolItem)?.name
        val withTodos = if (name == TODO_TOOL && !isError) state.copy(todos = applyTodo(state.todos, result?.obj("details"))) else state
        return updateTool(withTodos, id, event.str("toolName")) {
            it.copy(
                status = if (isError) ToolStatus.Error else ToolStatus.Done,
                result = text,
                details = result?.obj("details") ?: it.details,
                durationMs = event.long("durationMs") ?: it.durationMs,
            )
        }
    }

    /** Update the tool row for [id]: in the finished items (searched from the end), the stream, or a new row. */
    private fun updateTool(state: SessionState, id: String, name: String?, change: (ToolItem) -> ToolItem): SessionState {
        val index = state.items.indexOfLast { it is ToolItem && it.toolCallId == id }
        if (index >= 0) {
            val items = state.items.toMutableList()
            items[index] = change(items[index] as ToolItem)
            return state.copy(items = items)
        }
        val stream = state.stream
        if (stream != null) {
            val blockIndex = stream.blocks.indexOfFirst { it is ToolItem && it.toolCallId == id }
            if (blockIndex >= 0) {
                val blocks = stream.blocks.toMutableList()
                blocks[blockIndex] = change(blocks[blockIndex] as ToolItem)
                return state.copy(stream = stream.copy(blocks = blocks), streaming = blocks.filterNotNull())
            }
        }
        val fresh = ToolItem("tool:$id", id, name ?: "tool", args = null, status = ToolStatus.Pending)
        return state.copy(items = state.items + change(fresh))
    }

    private fun settle(state: SessionState): SessionState {
        var s = state.copy(isRunning = false, retry = null, isCompacting = false, todos = hideFinished(state.todos))
        if (s.stream != null) {
            // A stream without message_end (aborted mid-flight): keep what arrived.
            val blocks = s.stream.blocks.filterNotNull().map {
                when (it) {
                    is AssistantTextItem -> it.copy(streaming = false)
                    is ThinkingItem -> it.copy(streaming = false)
                    else -> it
                }
            }
            s = s.copy(items = s.items + blocks, stream = null, streaming = emptyList())
        }
        return closeDanglingTools(s)
    }

    private fun closeDanglingTools(state: SessionState): SessionState {
        if (state.items.none { it is ToolItem && it.isActive }) return state
        return state.copy(items = state.items.map { if (it is ToolItem && it.isActive) it.copy(status = ToolStatus.Cancelled) else it })
    }

    // Todos

    /** The todo extension's tool (opensec-pi-todo, rpiv-todo). Its result's `details.tasks` is the whole list after the call. */
    const val TODO_TOOL = "todo"

    /** The list from a `todo` result's details; ids completed and already hidden stay hidden. */
    private fun applyTodo(todos: TodoState, details: JsonObject?): TodoState {
        val list = details?.arr("tasks") ?: return todos
        val tasks = list.mapNotNull { (it as? JsonObject)?.let(::todoTask) }
        val nextId = details.int("nextId") ?: todos.nextId
        // A cleared list numbers its tasks from 1 again: the hidden ids meant other tasks.
        val hidden = if (nextId < todos.nextId) emptySet() else todos.hidden
        val completed = tasks.filter { it.status == TodoStatus.Completed }.mapTo(HashSet()) { it.id }
        val next = TodoState(tasks, hidden.intersect(completed), nextId)
        return if (next == todos) todos else next
    }

    /** One task, or null for a deleted or malformed one. */
    private fun todoTask(json: JsonObject): TodoTask? {
        val id = json.int("id") ?: return null
        val status = when (json.str("status")) {
            "pending" -> TodoStatus.Pending
            "in_progress" -> TodoStatus.InProgress
            "completed" -> TodoStatus.Completed
            else -> return null
        }
        return TodoTask(id, json.str("subject").orEmpty(), status, json.str("activeForm")?.takeIf { it.isNotBlank() })
    }

    private fun hideCompleted(todos: TodoState): TodoState {
        val completed = todos.tasks.filter { it.status == TodoStatus.Completed }.map { it.id }
        return if (todos.hidden.containsAll(completed)) todos else todos.copy(hidden = todos.hidden + completed)
    }

    /** A run that ends with every task done closes the panel. */
    private fun hideFinished(todos: TodoState): TodoState =
        if (todos.tasks.isNotEmpty() && todos.tasks.all { it.status == TodoStatus.Completed }) hideCompleted(todos) else todos

    /**
     * A snapshot of the session it already shows (a reconnect) without any todo result, as after a compaction dropped
     * them from the messages, keeps the list it had.
     */
    private fun keepTodos(before: SessionState, after: SessionState): SessionState {
        if (after.todos != TodoState() || before.todos == TodoState() || after.sessionId == null || after.sessionId != before.sessionId) return after
        return after.copy(todos = if (after.isRunning) before.todos else hideFinished(before.todos))
    }

    // Compaction

    private fun compactionEnd(state: SessionState, event: JsonObject): SessionState {
        val s = state.copy(isCompacting = false)
        val result = event.obj("result")
        val key = "m${s.seq}"
        val next = s.copy(seq = s.seq + 1)
        return when {
            result != null -> next.copy(
                items = next.items + NoticeItem(
                    key,
                    NoticeKind.Compaction,
                    compactedTitle(result.long("tokensBefore"), result.long("estimatedTokensAfter")),
                    result.str("summary").orEmpty(),
                ),
            )
            event.bool("aborted") == true -> next.copy(items = next.items + NoticeItem(key, NoticeKind.Aborted, "Compaction aborted"))
            event.str("errorMessage") != null -> next.copy(items = next.items + NoticeItem(key, NoticeKind.Error, "Compaction failed", event.str("errorMessage")!!))
            else -> s
        }
    }

    private fun compactedTitle(before: Long?, after: Long?): String = when {
        before != null && after != null -> "Compacted ${formatTokens(before)} to ~${formatTokens(after)} tokens"
        before != null -> "Compacted ${formatTokens(before)} tokens"
        else -> "Compacted"
    }

    fun formatTokens(n: Long): String = when {
        n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
        n >= 10_000 -> "${n / 1000}k"
        n >= 1_000 -> String.format("%.1fk", n / 1000.0)
        else -> n.toString()
    }

    // Extension UI

    private fun extensionRequest(state: SessionState, event: JsonObject): SessionState = when (event.str("method")) {
        "select", "confirm", "input", "editor" -> {
            val dialog = dialogFrom(event)
            if (dialog == null || state.dialogs.any { it.id == dialog.id }) state else state.copy(dialogs = state.dialogs + dialog)
        }
        "setStatus" -> {
            val key = event.str("statusKey")
            val text = event.str("statusText")
            when {
                key == null -> state
                text.isNullOrBlank() -> state.copy(statuses = state.statuses - key)
                else -> state.copy(statuses = state.statuses + (key to text))
            }
        }
        "setWidget" -> {
            val key = event.str("widgetKey")
            val lines = event.arr("widgetLines")?.mapNotNull { (it as? JsonPrimitive)?.content }
            when {
                key == null -> state
                lines.isNullOrEmpty() -> state.copy(widgets = state.widgets - key)
                else -> state.copy(widgets = state.widgets + (key to lines))
            }
        }
        else -> state
    }

    fun dialogFrom(event: JsonObject): DialogRequest? {
        val id = event.str("id") ?: return null
        val kind = when (event.str("method")) {
            "select" -> DialogKind.Select
            "confirm" -> DialogKind.Confirm
            "input" -> DialogKind.Input
            "editor" -> DialogKind.Editor
            else -> return null
        }
        return DialogRequest(
            id = id,
            kind = kind,
            title = event.str("title").orEmpty(),
            message = event.str("message"),
            options = event.strings("options"),
            placeholder = event.str("placeholder"),
            prefill = event.str("prefill"),
            timeoutMs = event.long("timeout"),
        )
    }

    private fun commandFrom(json: JsonObject): SlashCommand? {
        val name = json.str("name") ?: return null
        return SlashCommand(name, json.str("description"), json.str("source") ?: "")
    }

    // Helpers

    private fun SessionState.notice(kind: NoticeKind, title: String, text: String): SessionState =
        copy(seq = seq + 1, items = items + NoticeItem("m$seq", kind, title, text))

    private fun usageFrom(json: JsonObject) = Usage(
        input = json.long("input") ?: 0,
        output = json.long("output") ?: 0,
        cacheRead = json.long("cacheRead") ?: 0,
        totalTokens = json.long("totalTokens") ?: 0,
        cost = json.obj("cost")?.double("total") ?: 0.0,
    )

    /** Text of a `string | (TextContent | ImageContent)[]` content field, and how many images it had. */
    fun contentText(content: JsonElement?): Pair<String, Int> = when (content) {
        is JsonPrimitive -> (if (content.isString) content.content else "") to 0
        is JsonArray -> {
            var images = 0
            val text = StringBuilder()
            for (block in content) {
                val b = block as? JsonObject ?: continue
                when (b.type) {
                    "text" -> {
                        if (text.isNotEmpty()) text.append('\n')
                        text.append(b.str("text").orEmpty())
                    }
                    "image" -> images++
                }
            }
            text.toString() to images
        }
        else -> "" to 0
    }

    private fun JsonObject.strings(key: String): List<String> =
        arr(key)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()

    /** Parse one JSON line, for tests and the CLI. */
    fun parse(line: String): JsonObject = ProtocolJson.parseToJsonElement(line) as JsonObject
}
