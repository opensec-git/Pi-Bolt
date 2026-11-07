package opensec.pibolt.protocol.session

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.protocol.arr
import opensec.pibolt.protocol.bool
import opensec.pibolt.protocol.int
import opensec.pibolt.protocol.long
import opensec.pibolt.protocol.obj
import opensec.pibolt.protocol.str

/**
 * [SessionState.subagents] from what reaches the app of the subagents extension (opensec-pi-subagents):
 * - the `Agent` call's arguments (type, task, prompt, background);
 * - its partial results while a foreground run works: `details` carries the activity line, tool uses, turns, tokens
 *   and run time (about 12 times a second; the run time is kept in whole seconds so the state changes less often);
 * - its final result: the answer, or for a background start the agent id;
 * - `subagent-notification` messages when background runs end (status, metrics, a preview of the answer);
 * - `get_subagent_result` results (the full answer of a background run, once Pi fetches it);
 * - on this phone, the subagent's own session file, read by the app and applied as `subagent_transcript` events;
 * - from a computer with the `subagent_stream` feature (or an engine that answers `subagent_subscribe`), while a run's
 *   screen is open: its saved messages (`subagent_entries`) and its streaming message (`subagent_events`).
 *
 * The extension's TUI widget is a component, so over RPC it arrives empty: none of this comes from it.
 */
object SubagentReducer {
    const val NOTIFICATION = "subagent-notification"
    const val INTERRUPTED = "subagents-interrupted"

    /** An app-made event: messages of a subagent's session file. See [transcript]. */
    const val TRANSCRIPT_EVENT = "subagent_transcript"

    /** From the computer: messages of a subagent's session file, as [TRANSCRIPT_EVENT] with `runId` for `key`. */
    const val ENTRIES_EVENT = "subagent_entries"

    /** From the computer: a subagent's streaming message and tool starts (`events`). See [events]. */
    const val EVENTS_EVENT = "subagent_events"

    fun isAgentCall(name: String?): Boolean = name.equals("agent", ignoreCase = true)

    /**
     * An `Agent` call is known (its arguments): the run exists from now on, queued until the tool starts ([started]).
     */
    fun called(state: SessionState, id: String, name: String?, args: JsonObject?, started: Boolean = false): SessionState {
        if (!isAgentCall(name)) return state
        val old = state.subagents[id]
        var next = old ?: Subagent(key = id)
        if (args != null) next = withArgs(state, next, args)
        if (started && next.status == SubagentStatus.Queued) next = next.copy(status = SubagentStatus.Running)
        return put(state, old, next)
    }

    private fun withArgs(state: SessionState, run: Subagent, args: JsonObject): Subagent {
        val resume = args.str("resume")?.takeIf { it.isNotBlank() }
        // A resumed run is the same agent: it keeps the type and task of the run it continues.
        val previous = resume?.let { id -> state.subagents.values.lastOrNull { it.agentId == id && it.key != run.key } }
        return run.copy(
            type = args.str("subagent_type")?.takeIf { it.isNotBlank() && run.type == "Agent" } ?: previous?.type?.takeIf { run.type == "Agent" } ?: run.type,
            description = run.description.ifEmpty { args.str("description")?.trim() ?: previous?.description.orEmpty() },
            prompt = run.prompt.ifEmpty { args.str("prompt").orEmpty() },
            background = run.background || args.bool("run_in_background") == true,
            resumed = resume != null,
            agentId = run.agentId ?: resume,
        )
    }

    /** A partial result of a foreground run (`tool_execution_update`). */
    fun progress(state: SessionState, id: String, name: String?, details: JsonObject?): SessionState {
        if (details == null || !(isAgentCall(name) || id in state.subagents)) return state
        val old = state.subagents[id]
        val run = withDetails(old ?: Subagent(key = id), details, whole = true)
        val activity = details.str("activity")?.trim()?.takeIf { it.isNotEmpty() }
        val next = run.copy(
            status = if (activity?.startsWith("queued") == true) SubagentStatus.Queued else SubagentStatus.Running,
            activity = activity ?: run.activity,
        )
        return put(state, old, next)
    }

    /** Fields every `details` of the Agent tool may carry. [whole] keeps the run time in whole seconds. */
    private fun withDetails(run: Subagent, d: JsonObject, whole: Boolean): Subagent = run.copy(
        // The display name, unless it is the generic "Agent" (general-purpose): then the type itself.
        type = d.str("displayName")?.takeIf { it.isNotBlank() && !it.equals("Agent", ignoreCase = true) }
            ?: d.str("subagentType")?.takeIf { it.isNotBlank() } ?: run.type,
        description = d.str("description")?.takeIf { it.isNotBlank() }?.trim() ?: run.description,
        model = d.str("modelName")?.takeIf { it.isNotBlank() } ?: run.model,
        toolUses = maxOf(run.toolUses, d.int("toolUses") ?: 0),
        turns = d.int("turnCount")?.takeIf { it > 0 } ?: run.turns,
        maxTurns = d.int("maxTurns") ?: run.maxTurns,
        tokens = d.str("tokens")?.let(::tokensText) ?: run.tokens,
        durationMs = d.long("durationMs")?.takeIf { it > 0 }?.let { if (whole) it / 1000 * 1000 else it } ?: run.durationMs,
        agentId = d.str("agentId")?.takeIf { it.isNotBlank() } ?: run.agentId,
    )

    /**
     * A tool result (`tool_execution_end`, or a `toolResult` message in the history): an `Agent` call ended, or Pi
     * fetched a background run's answer with `get_subagent_result`. [at] is the message's timestamp when known.
     */
    fun ended(state: SessionState, id: String, name: String?, text: String, details: JsonObject?, isError: Boolean, at: Long?): SessionState = when {
        isAgentCall(name) || (name == null && id in state.subagents) -> agentEnded(state, id, text, details, isError, at)
        name == "get_subagent_result" && !isError -> fetched(state, text)
        else -> state
    }

    private fun agentEnded(state: SessionState, id: String, text: String, details: JsonObject?, isError: Boolean, at: Long?): SessionState {
        val old = state.subagents[id]
        var run = old ?: Subagent(key = id)
        if (details != null) run = withDetails(run, details, whole = false)
        val status = details?.str("status")
        run = if (status == "background") {
            run.copy(
                background = true,
                status = if (text.contains("queued in background")) SubagentStatus.Queued else SubagentStatus.Running,
                activity = null,
                // The background start's result carries no run time: it starts about now.
                durationMs = null,
                startedAt = run.startedAt ?: at,
            )
        } else {
            val final = statusOf(status)?.takeIf { !it.isActive } ?: if (isError) SubagentStatus.Failed else SubagentStatus.Done
            run.copy(
                status = final,
                activity = null,
                result = answerOf(text).ifBlank { null } ?: run.result,
                resultComplete = true,
                error = details?.str("error") ?: if (isError && details == null) text.trim() else run.error,
                startedAt = run.startedAt ?: run.durationMs?.let { d -> at?.minus(d) },
            )
        }
        return put(state, old, run)
    }

    private val agentLine = Regex("""(?m)^Agent: (\S+)\s*\nType: .*?\| Status: (\w+)""")
    private val conversationMarker = "\n\n--- Agent Conversation ---"

    /** `get_subagent_result`: "Agent: <id>", "Type: … | Status: …", …, "Description: …", a blank line, the answer. */
    private fun fetched(state: SessionState, text: String): SessionState {
        val m = agentLine.find(text) ?: return state
        val agentId = m.groupValues[1]
        val final = statusOf(m.groupValues[2])?.takeIf { !it.isActive } ?: return state
        val run = state.subagents.values.lastOrNull { it.agentId == agentId } ?: return state
        val afterDescription = text.indexOf("\nDescription: ").takeIf { it >= 0 } ?: return state
        val bodyStart = text.indexOf("\n\n", afterDescription).takeIf { it >= 0 } ?: return state
        val body = answerOf(text.substring(bodyStart + 2).substringBefore(conversationMarker))
        if (body.isBlank() || body.startsWith("Agent is still") || body.startsWith("Agent was stopped and is still")) return state
        return put(state, run, run.copy(status = final, activity = null, result = body, resultComplete = true))
    }

    /** A custom message of the extension: background runs ended, or the session's previous end interrupted some. */
    fun custom(state: SessionState, customType: String?, details: JsonObject?, at: Long?): SessionState = when (customType) {
        NOTIFICATION -> details?.let { notified(state, it, at) } ?: state
        INTERRUPTED -> details?.arr("ids").orEmpty().fold(state) { s, e ->
            val id = (e as? JsonPrimitive)?.content ?: return@fold s
            val run = s.subagents.values.lastOrNull { it.agentId == id && it.isActive } ?: return@fold s
            put(s, run, run.copy(status = SubagentStatus.Stopped, activity = null, error = "Interrupted when the session ended"))
        }
        else -> state
    }

    /** The agent ids a notification's details name: the first, then `others` (a group of parallel runs). */
    fun notifiedIds(details: JsonObject?): List<String> = notifications(details).mapNotNull { it.str("id") }

    private fun notifications(details: JsonObject?): List<JsonObject> {
        details ?: return emptyList()
        return listOf(details) + details.arr("others").orEmpty().mapNotNull { it as? JsonObject }
    }

    private fun notified(state: SessionState, details: JsonObject, at: Long?): SessionState {
        var s = state
        for (d in notifications(details)) {
            val id = d.str("id") ?: continue
            val run = s.subagents.values.lastOrNull { it.agentId == id } ?: continue
            val duration = d.long("durationMs")?.takeIf { it > 0 }
            val preview = d.str("resultPreview")?.trim()?.takeIf { it.isNotEmpty() && it != "No output." }
            s = put(
                s,
                run,
                run.copy(
                    status = statusOf(d.str("status"))?.takeIf { !it.isActive } ?: SubagentStatus.Done,
                    activity = null,
                    toolUses = maxOf(run.toolUses, d.int("toolUses") ?: 0),
                    turns = d.int("turnCount")?.takeIf { it > 0 } ?: run.turns,
                    tokens = d.long("totalTokens")?.takeIf { it > 0 }?.let(SessionReducer::formatTokens) ?: run.tokens,
                    durationMs = duration ?: run.durationMs,
                    startedAt = run.startedAt ?: duration?.let { at?.minus(it) },
                    error = d.str("error") ?: run.error,
                    result = if (run.resultComplete) run.result else preview ?: run.result,
                ),
            )
        }
        return s
    }

    /**
     * Messages of a subagent's session file: `{"type":"subagent_transcript","key":…,"file":…,"messages":[…],"reset":true}`
     * (read by the app) or `subagent_entries` with `runId` (from the computer). [reset] (or another file) starts the
     * transcript over; the message being streamed stays. A finished assistant message takes the streamed one's keys,
     * so its rows stay in place. A run that reports no progress of its own (a background run) takes its activity and
     * metrics from these messages.
     */
    fun transcript(state: SessionState, event: JsonObject): SessionState {
        val key = event.str("key") ?: event.str("runId") ?: return state
        val old = state.subagents[key] ?: return state
        val file = event.str("file") ?: old.transcript?.file ?: ""
        val previous = old.transcript
        val base = previous?.takeIf { it.file == file && event.bool("reset") != true }
            ?: SubagentTranscript(file, stream = previous?.stream, live = previous?.live.orEmpty(), liveSeq = previous?.liveSeq ?: 0)
        var scratch = SessionState(items = base.items, seq = base.seq)
        var tokens = base.tokens
        var assistants = base.assistantMessages
        var firstAt = base.firstAt
        var stream = base.stream
        var live = base.live
        for (element in event.arr("messages").orEmpty()) {
            val message = element as? JsonObject ?: continue
            if (firstAt == null) firstAt = message.long("timestamp")
            var commitKey: String? = null
            if (message.str("role") == "assistant") {
                assistants++
                message.obj("usage")?.let { u -> tokens += (u.long("input") ?: 0) + (u.long("output") ?: 0) + (u.long("cacheWrite") ?: 0) }
                commitKey = stream?.key
                stream = null
                live = emptyList()
            }
            scratch = SessionReducer.commitMessage(scratch, message, key = commitKey)
        }
        val transcript = SubagentTranscript(file, scratch.items, scratch.seq, tokens, assistants, firstAt, stream, live, base.liveSeq)
        return withTranscript(state, old, transcript)
    }

    /**
     * `subagent_events`: `{"runId":…,"events":[…]}`, the subagent's assistant message while it streams (`message_start`,
     * `message_update`; `message_end` is left to the saved message, see [transcript]) and `tool_execution_start` (its
     * call runs). Applied to [SubagentTranscript.live], apart from the saved rows.
     */
    fun events(state: SessionState, event: JsonObject): SessionState {
        val key = event.str("runId") ?: return state
        val old = state.subagents[key] ?: return state
        var t = old.transcript ?: SubagentTranscript(file = "")
        for (element in event.arr("events").orEmpty()) {
            val e = element as? JsonObject ?: continue
            when (e.str("type")) {
                "message_start" -> {
                    val message = e.obj("message") ?: continue
                    if (message.str("role") != "assistant") continue
                    val s = SessionReducer.startStream(SessionState(), message, key = "live${t.liveSeq}")
                    t = t.copy(stream = s.stream, live = s.streaming, liveSeq = t.liveSeq + 1)
                }
                "message_update" -> {
                    var s = SessionState(stream = t.stream, streaming = t.live)
                    var seq = t.liveSeq
                    if (s.stream == null) {
                        s = SessionReducer.startStream(s, JsonObject(emptyMap()), key = "live$seq")
                        seq++
                    }
                    s = SessionReducer.messageUpdate(s, e)
                    t = t.copy(stream = s.stream, live = s.streaming, liveSeq = seq)
                }
                "tool_execution_start" -> {
                    val id = e.str("toolCallId") ?: continue
                    t = t.copy(
                        items = t.items.map { item ->
                            if (item is ToolItem && item.toolCallId == id && (item.status == ToolStatus.Pending || item.status == ToolStatus.Streaming)) {
                                item.copy(status = ToolStatus.Running)
                            } else {
                                item
                            }
                        },
                    )
                }
            }
        }
        return withTranscript(state, old, t)
    }

    private fun withTranscript(state: SessionState, old: Subagent, transcript: SubagentTranscript): SessionState {
        var run = old.copy(transcript = transcript, startedAt = old.startedAt ?: transcript.firstAt)
        val finished = transcript.items.count { it is ToolItem && !it.isActive }
        run = run.copy(toolUses = maxOf(run.toolUses, finished), turns = maxOf(run.turns, transcript.assistantMessages))
        if (run.background || run.tokens == null) run = run.copy(tokens = transcript.tokens.takeIf { it > 0 }?.let(SessionReducer::formatTokens) ?: run.tokens)
        if (run.isActive && run.background) run = run.copy(status = SubagentStatus.Running, activity = activityOf(transcript.allItems))
        if (!run.isActive && !run.resultComplete) {
            (transcript.items.lastOrNull { it is AssistantTextItem } as? AssistantTextItem)?.text?.trim()?.takeIf { it.isNotEmpty() }?.let {
                run = run.copy(result = it, resultComplete = true)
            }
        }
        return put(state, old, run)
    }

    /** Pi stopped (`agent_settled`, or an idle snapshot): a foreground run still open was cut off with its turn. */
    fun settled(state: SessionState): SessionState {
        if (state.subagents.values.none { !it.background && it.isActive }) return state
        return state.copy(
            subagents = state.subagents.mapValues { (_, run) ->
                if (!run.background && run.isActive) run.copy(status = SubagentStatus.Stopped, activity = null) else run
            },
        )
    }

    /**
     * A snapshot of the session it already shows (a reconnect): runs keep the transcripts read from their files, and
     * background runs the progress the history does not have.
     */
    fun keep(before: SessionState, after: SessionState): SessionState {
        if (before.subagents.isEmpty() || after.sessionId == null || after.sessionId != before.sessionId) return after
        var changed = false
        val merged = after.subagents.mapValues { (key, run) ->
            val old = before.subagents[key] ?: return@mapValues run
            var next = run
            if (next.transcript == null && old.transcript != null) next = next.copy(transcript = old.transcript)
            if (next.background && next.isActive && old.isActive) {
                next = next.copy(activity = old.activity, toolUses = maxOf(next.toolUses, old.toolUses), turns = maxOf(next.turns, old.turns), tokens = next.tokens ?: old.tokens)
            }
            if (next != run) changed = true
            next
        }
        return if (changed) after.copy(subagents = merged) else after
    }

    // Helpers

    fun statusOf(status: String?): SubagentStatus? = when (status) {
        "queued" -> SubagentStatus.Queued
        "running" -> SubagentStatus.Running
        "completed", "steered" -> SubagentStatus.Done
        "error", "aborted" -> SubagentStatus.Failed
        "stopped" -> SubagentStatus.Stopped
        else -> null
    }

    /** "12.3k token" -> "12.3k"; empty -> null. */
    private fun tokensText(text: String): String? = text.trim().removeSuffix("tokens").removeSuffix("token").trim().ifEmpty { null }

    private val agentIdLine = Regex("""(?m)^Agent ID: .*$""")
    private val completedHeadline = Regex("""(?m)^Agent completed in .*$""")
    private val sessionFileLine = Regex("""(?m)^Session file \(every turn of this agent\): .*$""")

    /**
     * A result's answer: without the stats headline of a completed foreground run (the card shows the run time and
     * metrics), the resume hint and the session file line. Stops, aborts and failures keep their headline.
     */
    fun answerOf(text: String): String =
        text.replace(agentIdLine, "").replace(completedHeadline, "").replace(sessionFileLine, "").trim()

    private val toolVerbs = mapOf(
        "read" to "reading",
        "bash" to "running",
        "edit" to "editing",
        "write" to "writing",
        "grep" to "searching",
        "find" to "finding",
        "ls" to "listing",
    )

    /**
     * What a subagent does, from the end of its transcript: its open tool calls; the message it streams (thinking or
     * writing); else "working…" (between messages: the model is on its next one).
     */
    fun activityOf(items: List<ChatItem>): String {
        val open = ArrayList<ToolItem>()
        for (i in items.indices.reversed()) {
            val item = items[i]
            if (item is ToolItem) {
                if (item.isActive) open += item else break
            } else if (item !is AssistantTextItem && item !is ThinkingItem) {
                break
            }
        }
        if (open.isEmpty()) {
            return when (val last = items.lastOrNull()) {
                is ThinkingItem -> if (last.streaming) "thinking…" else "working…"
                is AssistantTextItem -> if (last.streaming) "writing…" else "working…"
                else -> "working…"
            }
        }
        if (open.size > 1) {
            val names = open.map { it.name.lowercase() }.distinct()
            val verb = toolVerbs[names.singleOrNull()] ?: "running"
            return "$verb ${open.size} ${if (names.singleOrNull() == "read") "files" else "tools"}…"
        }
        val tool = open[0]
        val verb = toolVerbs[tool.name.lowercase()] ?: "running ${tool.name}"
        val arg = listOf("command", "path", "file_path", "pattern").firstNotNullOfOrNull { tool.args?.str(it) }
            ?.trim()?.lineSequence()?.firstOrNull()?.let { if (it.length > 60) it.take(59) + "…" else it }
        return if (arg != null) "$verb $arg" else "$verb…"
    }

    private fun put(state: SessionState, old: Subagent?, next: Subagent): SessionState {
        if (old == next) return state
        return state.copy(subagents = state.subagents + (next.key to next))
    }

    /** The run a notification or result names by agent id: the newest one. */
    fun byAgentId(state: SessionState, agentId: String): Subagent? = state.subagents.values.lastOrNull { it.agentId == agentId }
}
