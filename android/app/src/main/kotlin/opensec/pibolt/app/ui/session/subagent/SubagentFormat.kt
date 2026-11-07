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
    /**
     * Avatar tints (ARGB): muted hues of the syntax palette and the accent options, none of them a status colour.
     * Built-in types keep theirs; other types are hashed.
     */
    val TINTS = longArrayOf(
        0xFF7CC4F0, // glacier
        0xFFA99CFF, // iris
        0xFF9ED3C6, // mint
        0xFFE5A97E, // peach
        0xFFC9B8F0, // lilac
    )

    fun tintIndex(type: String): Int = when (type.lowercase()) {
        "explore" -> 0
        "plan" -> 1
        "general-purpose", "general" -> 2
        else -> Math.floorMod(type.lowercase().hashCode(), TINTS.size)
    }

    /** The avatar letter: the type's first letter or digit. */
    fun initial(type: String): String = type.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "A"

    /** "Explore", "General-purpose": types read capitalised. */
    fun typeLabel(type: String): String = type.ifBlank { "Agent" }.replaceFirstChar { it.uppercaseChar() }

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

    /** TalkBack text of a card. */
    fun spoken(run: Subagent): String = listOfNotNull(
        "${typeLabel(run.type)} agent",
        run.description.takeIf { it.isNotBlank() },
        if (run.background) "background" else null,
        statusWord(run.status),
        activityLine(run).takeIf { run.isActive },
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
        run.transcript?.items?.takeIf { it.isNotEmpty() }?.let { return it }
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

    /**
     * An answer's start as one paragraph for the card: headings end with a colon, list items keep a bullet, code
     * fences are dropped. At most [lines] source lines and [chars] characters.
     */
    fun answerPreview(text: String, lines: Int = 5, chars: Int = 320): String {
        var fenced = false
        val prose = text.lineSequence().map { it.trim() }.filter { line ->
            if (line.startsWith("```")) {
                fenced = !fenced
                false
            } else {
                !fenced && line.isNotEmpty()
            }
        }
        return prose.take(lines).map { line ->
            when {
                Regex("""^#{1,6}\s""").containsMatchIn(line) -> plain(line).let { if (it.lastOrNull()?.let(Char::isLetterOrDigit) == true) "$it:" else it }
                Regex("""^([-*+]|\d+[.)])\s""").containsMatchIn(line) -> "• " + plain(line.replace(Regex("""^\d+[.)]\s+"""), ""))
                else -> plain(line)
            }
        }.joinToString(" ").take(chars)
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
