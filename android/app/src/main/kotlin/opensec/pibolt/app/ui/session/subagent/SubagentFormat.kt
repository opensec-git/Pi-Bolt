package opensec.pibolt.app.ui.session.subagent

import android.os.SystemClock
import opensec.pibolt.app.ui.session.formatDuration
import opensec.pibolt.protocol.session.AssistantTextItem
import opensec.pibolt.protocol.session.ChatItem
import opensec.pibolt.protocol.session.NoticeItem
import opensec.pibolt.protocol.session.NoticeKind
import opensec.pibolt.protocol.session.Subagent
import opensec.pibolt.protocol.session.SubagentStatus
import opensec.pibolt.protocol.session.UserItem
import kotlin.math.abs

/** Text and colour choices of subagent cards and the detail screen. Pure, so they are unit tested. */
object SubagentFormat {
    /** How many subagent tints the theme has (`PiColors.tints`, Pi-Bolt Desktop's five): colour slots wrap at it. */
    const val HUE_COUNT = 5

    /** The colour slot of the run [key]: its order among the session's runs ([keys], first seen first), wrapped. */
    fun hueSlot(key: String, keys: Iterable<String>): Int {
        val i = keys.indexOf(key)
        return if (i < 0) Math.floorMod(key.hashCode(), HUE_COUNT) else i % HUE_COUNT
    }

    /** "Explore", "General-purpose": types read capitalised. */
    fun typeLabel(type: String): String = type.ifBlank { "Agent" }.replaceFirstChar { it.uppercaseChar() }

    /**
     * The run's name: the task title the model gave the call ("Find the auth flow"), else its prompt's first line (cut
     * at 60 characters), else the type.
     */
    fun title(run: Subagent): String {
        run.description.trim().takeIf { it.isNotEmpty() }?.let { return capitalise(it) }
        val prompt = firstLine(run.prompt)?.let(::plain)?.takeIf { it.isNotEmpty() }
        if (prompt != null) return capitalise(if (prompt.length > 60) prompt.take(59).trimEnd() + "…" else prompt)
        return typeLabel(run.type)
    }

    /**
     * True for the extension's tool line ("reading…", "running command, searching 2 patterns…", a custom tool's name)
     * and the app's own ("reading src/a.ts", "thinking…"); false for the start of the agent's reply, which the
     * extension sends in the same field.
     */
    fun isToolActivity(activity: String): Boolean = toolVerb.containsMatchIn(activity) || toolList.matches(activity)

    private val toolVerb = Regex("""^(reading|running|editing|writing|searching|finding|listing|thinking|working|queued)\b""")

    /** The app's own lines that name no tool: the extension's report says more. */
    private fun vague(activity: String): Boolean = activity.startsWith("thinking") || activity.startsWith("working") || activity.startsWith("writing")
    private val toolList = Regex("""^[a-z][a-z0-9_.:-]*( \d+ (files|patterns))?(, [a-z][a-z0-9_.:-]*( [a-z]+)*( \d+ (files|patterns))?)*…$""")

    /**
     * The activity a card works from: the line read from the run's session file ([fromFile], from
     * [opensec.pibolt.protocol.session.SubagentReducer.activityOf], which names the file or command) while both it and
     * the extension's report are a tool, else the report.
     */
    fun liveActivity(reported: String?, fromFile: String?): String? {
        val file = fromFile?.takeIf { it.isNotBlank() && !vague(it) } ?: return reported
        return when {
            reported == null -> file
            isToolActivity(reported) && !vague(reported) -> file
            else -> reported
        }
    }

    /**
     * What a card says the run does, without its narration: the tool it runs ("Reading src/auth.ts"), "Writing…" while
     * it writes text (the extension sends the start of the reply in the same field), "Thinking…"; done, its metrics;
     * queued, failed and stopped say so. The narration stays on the agent's own screen.
     */
    fun toolActivity(run: Subagent): String {
        val reported = run.activity?.replace("`", "")?.replace("**", "")?.trim()?.takeIf { it.isNotEmpty() }
        return when (run.status) {
            SubagentStatus.Queued -> activityLine(run)
            SubagentStatus.Running -> when {
                reported == null -> if (run.background) "Working in the background" else "Starting…"
                isToolActivity(reported) -> capitalise(reported)
                else -> "Writing…"
            }
            SubagentStatus.Done -> metrics(run)
            SubagentStatus.Failed -> run.error?.let(::firstLine)?.let { "Failed: $it" } ?: "Failed"
            SubagentStatus.Stopped -> run.error?.let(::firstLine) ?: "Stopped"
        }
    }

    /** A card's second line: the type, then [toolActivity] ("Explore · Reading src/auth.ts"; "Explore · background · …"). */
    fun cardMeta(run: Subagent): String = listOfNotNull(
        typeLabel(run.type).takeIf { title(run) != it },
        "background".takeIf { run.background },
        toolActivity(run),
    ).joinToString(" · ")

    /** What an agent's glyph shows. Starting: the call is made, its session not running yet (amber, breathing). */
    enum class AgentState { Starting, Queued, Running, Done, Failed, Stopped }

    fun stateOf(run: Subagent): AgentState = when (run.status) {
        SubagentStatus.Queued -> if (run.activity?.startsWith("queued") == true) AgentState.Queued else AgentState.Starting
        SubagentStatus.Running -> AgentState.Running
        SubagentStatus.Done -> AgentState.Done
        SubagentStatus.Failed -> AgentState.Failed
        SubagentStatus.Stopped -> AgentState.Stopped
    }

    /** One state for a stack: running while any runs, then starting, queued, failed, stopped, else done. */
    fun stackState(runs: List<Subagent>): AgentState {
        val states = runs.map(::stateOf)
        return listOf(AgentState.Running, AgentState.Starting, AgentState.Queued, AgentState.Failed, AgentState.Stopped).firstOrNull { it in states } ?: AgentState.Done
    }

    /** The agent id as the card shows it: its first 8 characters (the extension names its session that way). */
    fun shortId(agentId: String): String = agentId.take(8)

    /**
     * The card's live line. Running: what it does ("reading 3 files…"); queued: that it waits; done: the first line of
     * its answer; failed: the error; stopped: that it stopped.
     */
    fun activityLine(run: Subagent): String = when (run.status) {
        SubagentStatus.Queued -> run.activity?.takeIf { it.startsWith("queued") }?.let(::capitalise) ?: if (run.background) "Queued in the background" else "Starting…"
        SubagentStatus.Running -> run.activity?.replace("`", "")?.replace("**", "")?.let(::capitalise) ?: if (run.background) "Working in the background" else "Starting…"
        SubagentStatus.Done -> firstLine(run.result)?.let(::plain) ?: "Done"
        SubagentStatus.Failed -> run.error?.let(::firstLine)?.let { "Failed: $it" } ?: firstLine(run.result) ?: "Failed"
        SubagentStatus.Stopped -> run.error?.let(::firstLine) ?: "Stopped"
    }

    /**
     * The detail screen's live line. While the run is live it never reads as stuck: what it does now ("Reading
     * src/a.ts", "Thinking…", "Writing…"), else "Working…". When it ends: "Finished", "Failed: …" or "Stopped".
     */
    fun liveLine(run: Subagent): String = when (run.status) {
        SubagentStatus.Queued -> activityLine(run)
        SubagentStatus.Running -> run.activity?.replace("`", "")?.replace("**", "")?.trim()?.takeIf { it.isNotEmpty() }?.let(::capitalise) ?: "Working…"
        SubagentStatus.Done -> "Finished"
        SubagentStatus.Failed -> run.error?.let(::firstLine)?.let { "Failed: $it" } ?: "Failed"
        SubagentStatus.Stopped -> run.error?.let(::firstLine)?.let { "Stopped: $it" } ?: "Stopped"
    }

    /** Where the detail screen's messages come from. */
    enum class Source {
        /** This phone reads the agent's session file (and its engine may stream the message it writes). */
        Device,

        /** The computer streams them while the screen is open (`subagent_subscribe`). */
        Stream,

        /** The computer cannot stream them (before Pi-Bolt 1.1.5): only the prompt, progress and answer. */
        Unavailable,
    }

    /**
     * The note above the messages, or null: the old computer's limit; while nothing has arrived yet, that it loads;
     * a finished run without a readable file, that only the prompt and answer are shown.
     */
    fun note(run: Subagent, source: Source): String? = when {
        source == Source.Unavailable -> "Live view needs Pi-Bolt 1.1.5 on the computer"
        run.transcript?.allItems.isNullOrEmpty() && run.isActive -> "Loading messages…"
        run.transcript?.items.isNullOrEmpty() && !run.isActive -> "Messages weren't saved. Showing the prompt and answer."
        else -> null
    }

    /** "4 tools · 12.3k tokens · turn 2/10"; "No tools yet" before the first. */
    fun metrics(run: Subagent): String {
        val parts = buildList {
            add(
                when (run.toolUses) {
                    0 -> if (run.isActive) "No tools yet" else "No tools"
                    1 -> "1 tool"
                    else -> "${run.toolUses} tools"
                },
            )
            run.tokens?.let { add("$it tokens") }
            when {
                !run.isActive && run.turns > 0 -> add(if (run.turns == 1) "1 turn" else "${run.turns} turns")
                run.turns > 1 || (run.turns > 0 && run.maxTurns != null) -> add(run.maxTurns?.let { "turn ${run.turns}/$it" } ?: "turn ${run.turns}")
            }
        }
        return parts.joinToString(" · ")
    }

    /** The final run time ("9.1s", "1m 4s"), or null while it runs or when unknown. */
    fun finalDuration(run: Subagent): String? = run.durationMs?.takeIf { !run.isActive && it > 0 }?.let(::formatDuration)

    /** A stack's title: "3 agents running", "1 of 3 agents running", "3 agents done", "3 agents · 1 failed". */
    fun stackTitle(runs: List<Subagent>): String {
        val n = runs.size
        val active = runs.count { it.isActive }
        val failed = runs.count { it.status == SubagentStatus.Failed }
        val stopped = runs.count { it.status == SubagentStatus.Stopped }
        return when {
            active == n -> "$n agents running"
            active > 0 -> "$active of $n agents running"
            failed > 0 -> "$n agents · $failed failed"
            stopped > 0 -> "$n agents · $stopped stopped"
            else -> "$n agents done"
        }
    }

    /** True when [next] continues [previous] (the same reply growing), so the line updates without a crossfade. */
    fun sameLine(previous: String, next: String): Boolean {
        val stem = previous.removeSuffix("…").trimEnd()
        return stem.isNotEmpty() && (next.startsWith(stem) || stem.startsWith(next.removeSuffix("…").trimEnd()))
    }

    /** "Explore finished", "Plan failed": a notice line's title. */
    fun endedTitle(run: Subagent): String = typeLabel(run.type) + when (run.status) {
        SubagentStatus.Failed -> " failed"
        SubagentStatus.Stopped -> " stopped"
        else -> " finished"
    }

    /** TalkBack text of a card: "Find the auth flow, Explore agent, running, Reading src/auth.ts". */
    fun spoken(run: Subagent): String = listOfNotNull(
        title(run).takeIf { it != typeLabel(run.type) },
        "${typeLabel(run.type)} agent",
        if (run.background) "background" else null,
        statusWord(run.status),
        toolActivity(run).takeIf { run.isActive },
    ).joinToString(", ")

    fun statusWord(status: SubagentStatus): String = when (status) {
        SubagentStatus.Queued -> "queued"
        SubagentStatus.Running -> "running"
        SubagentStatus.Done -> "done"
        SubagentStatus.Failed -> "failed"
        SubagentStatus.Stopped -> "stopped"
    }

    /**
     * What the detail screen lists: the subagent's own messages when its session file was read, else what Pi sent of
     * it (the prompt, the answer or the error).
     */
    fun detailItems(run: Subagent): List<ChatItem> {
        // The message being streamed shows only while the run is live (a stream cut off by its end goes with it).
        run.transcript?.let { t -> (if (run.isActive) t.allItems else t.items).takeIf { it.isNotEmpty() } }?.let { return it }
        return buildList {
            if (run.prompt.isNotBlank()) add(UserItem("prompt:${run.key}", run.prompt))
            if (!run.isActive) {
                run.result?.takeIf { it.isNotBlank() }?.let { add(AssistantTextItem("answer:${run.key}", it, streaming = false)) }
                if (run.status == SubagentStatus.Failed && run.error != null && run.result?.contains(run.error!!) != true) {
                    add(NoticeItem("error:${run.key}", NoticeKind.Error, "Failed", run.error!!))
                }
            }
        }
    }

    /** The prompt that asks Pi to pass [message] to a running background agent (Pi calls `steer_subagent`). */
    fun steerPrompt(run: Subagent, message: String): String =
        "Use steer_subagent to send this message to the running agent ${run.agentId} (${typeLabel(run.type)}: ${run.description.ifBlank { "subagent" }}):\n\n${message.trim()}"

    /** The prompt that asks Pi to continue a finished agent with [message] (`Agent` with `resume`). */
    fun resumePrompt(run: Subagent, message: String): String =
        "Resume the agent ${run.agentId} (${typeLabel(run.type)}: ${run.description.ifBlank { "subagent" }}) with the Agent tool's resume parameter, with this prompt:\n\n${message.trim()}"

    /** How the detail screen can talk to a run. */
    enum class Reach {
        /** A running background agent: Pi passes a message on with `steer_subagent`. */
        Steer,

        /** A foreground agent: Pi waits on it, so a message would reach Pi only after it returns; it can be stopped. */
        StopOnly,

        /** A finished agent with an id: Pi can resume it. */
        Resume,
        None,
    }

    fun reach(run: Subagent): Reach = when {
        run.agentId == null && !run.isActive -> Reach.None
        run.isActive && run.background -> if (run.agentId != null) Reach.Steer else Reach.None
        run.isActive -> Reach.StopOnly
        else -> Reach.Resume
    }

    private fun firstLine(text: String?): String? = text?.trim()?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()

    /** A markdown line as plain text: no heading marks, list bullets, emphasis or code ticks. */
    fun plain(line: String): String = line
        .replace(Regex("""^#{1,6}\s+"""), "")
        .replace(Regex("""^[-*+]\s+"""), "")
        .replace(Regex("""\*\*|__|`"""), "")
        .trim()

    private fun capitalise(text: String): String = text.replaceFirstChar { it.uppercaseChar() }
}

/**
 * When each run started, in uptime: from its reported run time while it reports one, from its start timestamp, or from
 * when this phone first saw it. Kept across recompositions and screens so a ticking time never restarts.
 */
object RunClock {
    private val starts = HashMap<String, Long>()

    fun start(run: Subagent, now: Long = SystemClock.uptimeMillis(), wall: Long = System.currentTimeMillis()): Long = synchronized(starts) {
        val reported = run.durationMs?.let { now - it }
        val fromWall = run.startedAt?.let { now - (wall - it).coerceAtLeast(0) }
        val known = starts[run.key]
        val start = when {
            // A reported time moves the start only when it disagrees by more than a tick (it is kept in whole seconds).
            reported != null -> if (known != null && abs(known - reported) < 1500) known else reported
            known != null -> known
            fromWall != null -> fromWall
            else -> now
        }
        starts[run.key] = start
        if (starts.size > 256) starts.keys.firstOrNull()?.let { if (it != run.key) starts.remove(it) }
        start
    }
}
