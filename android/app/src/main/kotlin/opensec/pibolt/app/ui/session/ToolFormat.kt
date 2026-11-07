package opensec.pibolt.app.ui.session

import opensec.pibolt.app.ui.session.subagent.SubagentFormat
import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import opensec.pibolt.protocol.arr
import opensec.pibolt.protocol.bool
import opensec.pibolt.protocol.int
import opensec.pibolt.app.engine.ChatSpace.ASK_TOOL
import opensec.pibolt.app.ui.session.ask.AskQuestions
import opensec.pibolt.app.ui.session.web.WebTools
import opensec.pibolt.protocol.session.SessionReducer.TODO_TOOL
import opensec.pibolt.protocol.session.SubagentReducer
import opensec.pibolt.protocol.session.ToolItem
import opensec.pibolt.protocol.session.ToolStatus
import opensec.pibolt.protocol.str
import opensec.pibolt.protocol.toDisplayString
import java.util.Locale
import kotlin.math.roundToInt

/** What kind of step a tool is; picks the expanded card. */
enum class ToolKind { Bash, Read, Write, Edit, Search, List, Todo, Web, Ask, Other }

/**
 * How a step reads: [verb] while it runs ("Run", "Edit"), [past] once it finished ("Ran", "Edited"), and its argument.
 */
@Immutable
data class ToolLabel(val kind: ToolKind, val verb: String, val detail: String, val past: String = verb)

private val partialField = Regex("\"(command|path|file_path|pattern|query|url|description|prompt|subagent_type|subject|agent_id|message|action|status)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)")

/** "#2" for a background-job tool's `id` (a number, or a numeric string). */
private fun jobRef(item: ToolItem): String =
    (item.args?.get("id") as? JsonPrimitive)?.content?.let { "#$it" } ?: Regex(""""id"\s*:\s*"?(\d+)""").find(item.argsText)?.let { "#${it.groupValues[1]}" }.orEmpty()

fun toolLabel(item: ToolItem): ToolLabel {
    val args = item.args
    fun arg(vararg names: String): String? {
        for (n in names) args?.str(n)?.let { return it }
        // While the model is still writing the call, read what has arrived.
        for (m in partialField.findAll(item.argsText)) {
            if (m.groupValues[1] in names) return m.groupValues[2].replace("\\n", " ").replace("\\\"", "\"").replace("\\\\", "\\")
        }
        return null
    }
    val name = item.name
    return when (name.lowercase()) {
        "bash", "shell", "exec" -> ToolLabel(ToolKind.Bash, "Run", arg("command").orEmpty().lineSequence().firstOrNull().orEmpty(), "Ran")
        "read" -> ToolLabel(ToolKind.Read, "Read", arg("path", "file_path").orEmpty() + readRange(item).orEmpty())
        "write" -> ToolLabel(ToolKind.Write, "Write", arg("path", "file_path").orEmpty(), "Wrote")
        "edit", "multiedit" -> ToolLabel(ToolKind.Edit, "Edit", arg("path", "file_path").orEmpty(), "Edited")
        "grep" -> ToolLabel(ToolKind.Search, "Search", listOfNotNull(arg("pattern"), arg("path")?.let { "in $it" }).joinToString(" "), "Searched")
        "find", "glob" -> ToolLabel(ToolKind.Search, "Find", listOfNotNull(arg("pattern"), arg("path")?.let { "in $it" }).joinToString(" "), "Found")
        "ls" -> ToolLabel(ToolKind.List, "List", arg("path") ?: ".", "Listed")
        // The app's background jobs (engine/BackgroundJobs.kt): job ids are numbers.
        "bash_background" -> ToolLabel(ToolKind.Bash, "Start in the background", arg("name")?.takeIf { it.isNotBlank() } ?: firstLine(arg("command")), "Started in the background")
        "bash_output" -> ToolLabel(ToolKind.Bash, "Read output", jobRef(item), "Read output")
        "bash_kill" -> ToolLabel(ToolKind.Bash, "Stop job", jobRef(item), "Stopped job")
        "bash_jobs" -> ToolLabel(ToolKind.Bash, "List background jobs", "", "Listed background jobs")
        // The subagents extension (opensec-pi-subagents): the agent type, then its task.
        "agent" -> when {
            args?.str("resume") != null -> ToolLabel(ToolKind.Other, "Resume agent", firstLine(arg("prompt")).ifBlank { args.str("resume").orEmpty() })
            else -> ToolLabel(
                ToolKind.Other,
                arg("subagent_type")?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: "Agent",
                firstLine(arg("description")?.takeIf { it.isNotBlank() } ?: arg("prompt")),
            )
        }
        "get_subagent_result" -> ToolLabel(ToolKind.Other, "Agent result", arg("agent_id").orEmpty())
        "steer_subagent" -> ToolLabel(ToolKind.Other, "Steer agent", listOfNotNull(arg("agent_id"), arg("message")?.let(::firstLine)).joinToString(": "))
        "subagentworkflow" -> ToolLabel(ToolKind.Other, "Workflow", workflowName(item))
        TODO_TOOL -> ToolLabel(ToolKind.Todo, "Todo", todoSummary(item) { arg(it) })
        // The bundled pi-web-access and rpiv-ask-user-question (engine/ChatSpace.kt).
        "web_search" -> ToolLabel(ToolKind.Web, "Search the web", WebTools.queriesOf(item).joinToString(" · ").ifBlank { arg("query").orEmpty() }, "Searched the web")
        "source_check" -> ToolLabel(ToolKind.Web, "Check sources", firstLine(arg("claim")), "Checked sources")
        "fetch_content" -> ToolLabel(ToolKind.Web, "Read", WebTools.fetchUrls(item).joinToString(", ").ifBlank { arg("url").orEmpty() }, "Read")
        "get_search_content" -> ToolLabel(ToolKind.Web, "Read results", arg("query", "url").orEmpty(), "Read results")
        // The app's preview tools (assets/extensions/pibolt-preview.js).
        "preview_open" -> ToolLabel(ToolKind.Other, "Open preview", arg("url") ?: "dev server", "Opened preview")
        "preview_screenshot" -> ToolLabel(ToolKind.Other, "Screenshot", "preview", "Took a screenshot")
        "preview_console" -> ToolLabel(ToolKind.Other, "Read console", "preview", "Read the console")
        "preview_click" -> ToolLabel(ToolKind.Other, "Click", arg("selector") ?: pointOf(args).orEmpty(), "Clicked")
        "preview_type" -> ToolLabel(ToolKind.Other, "Type", arg("selector").orEmpty(), "Typed")
        "preview_scroll" -> ToolLabel(ToolKind.Other, "Scroll", arg("selector", "to") ?: "preview", "Scrolled")
        "preview_eval" -> ToolLabel(ToolKind.Other, "Run in page", firstLine(arg("js")), "Ran in page")
        ASK_TOOL -> ToolLabel(ToolKind.Ask, "Ask", AskQuestions.parse(item.args)?.firstOrNull()?.question?.let(::firstLine).orEmpty(), if (isDeclinedAsk(item)) "Declined" else "Asked")
        else -> {
            val first = args?.entries?.firstOrNull { (it.value as? JsonPrimitive)?.isString == true }?.let { (it.value as JsonPrimitive).content }
            ToolLabel(ToolKind.Other, name, (first ?: arg("command", "path", "pattern", "query", "url")).orEmpty().lineSequence().firstOrNull().orEmpty())
        }
    }
}

/** A read's line range as `:10-49` (offset 10, limit 40), `:10-` (offset only) or `:1-40` (limit only); null for all. */
fun readRange(item: ToolItem): String? {
    val args = item.args ?: return null
    val offset = (args["offset"] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceAtLeast(1)
    val limit = (args["limit"] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceAtLeast(1)
    return when {
        offset != null && limit != null -> ":$offset-${offset + limit - 1}"
        offset != null -> ":$offset-"
        limit != null -> ":1-$limit"
        else -> null
    }
}

/** "120, 340": a preview click's x and y, null without them. */
private fun pointOf(args: JsonObject?): String? {
    val x = (args?.get("x") as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return null
    val y = (args["y"] as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return null
    return "${x.roundToInt()}, ${y.roundToInt()}"
}

/** Tools that run another agent: the Quad shows Agent while they run. */
fun isSubagentTool(name: String): Boolean = name.lowercase() in setOf("task", "agent", "subagent", "subagents", "subagentworkflow")

private fun firstLine(text: String?): String = text.orEmpty().trim().lineSequence().firstOrNull().orEmpty()

private val workflowMetaName = Regex("""name\s*:\s*["'`]([^"'`\n]+)["'`]""")

/** A workflow's name: from its script's `meta`, its file or the saved workflow it runs. */
private fun workflowName(item: ToolItem): String {
    val args = item.args
    args?.str("script")?.let { script -> workflowMetaName.find(script.substringAfter("meta", ""))?.let { return it.groupValues[1] } }
    args?.str("scriptPath")?.let { return baseName(it) }
    return args?.str("name")?.takeIf { it.isNotBlank() } ?: "workflow"
}

/**
 * What a running subagent is doing, from its partial result: "reading 3 files… · 4 tool uses · turn 2". Null for
 * other tools and before the first update.
 */
fun subagentProgress(item: ToolItem): String? {
    if (item.status != ToolStatus.Running || !isSubagentTool(item.name)) return null
    val d = item.details ?: return null
    if (d.str("status") != "running") return null
    val tools = d.int("toolUses") ?: 0
    val turns = d.int("turnCount") ?: 0
    return listOfNotNull(
        // The extension sends the start of the agent's reply in the same field: name the tool, not the narration.
        d.str("activity")?.let(::firstLine)?.takeIf { it.isNotBlank() }?.let { if (SubagentFormat.isToolActivity(it)) it else "writing…" },
        tools.takeIf { it > 0 }?.let { if (it == 1) "1 tool use" else "$it tool uses" },
        turns.takeIf { it > 1 }?.let { "turn $it" },
    ).joinToString(" · ").ifBlank { null }
}

/**
 * A finished subagent call's text: its answer without the stats headline, the resume hint and the session file line
 * ([SubagentReducer.answerOf]), or that it went to the background. Null for other tools.
 */
fun subagentResult(item: ToolItem): String? {
    if (!isSubagentTool(item.name) || item.isActive) return null
    val d = item.details
    if (d?.str("status") == "background") {
        return "Running in background" + (d.str("agentId")?.let { " · $it" } ?: "")
    }
    return item.result?.let(SubagentReducer::answerOf)
}

/**
 * A `todo` call in one line: "Added: Write tests", "Done: Write tests", "Cleared the list". [arg] reads an argument,
 * also from a call still being written.
 */
fun todoSummary(item: ToolItem, arg: (String) -> String? = { item.args?.str(it) }): String {
    val id = item.args?.int("id")
    val subject = arg("subject")?.takeIf { it.isNotBlank() } ?: id?.let { todoSubject(item, it) ?: "#$it" }
    fun say(verb: String) = if (subject != null) "$verb: ${firstLine(subject)}" else verb
    return when (val action = arg("action")) {
        "create" -> say("Added")
        "update" -> when (arg("status")) {
            "completed" -> say("Done")
            "in_progress" -> say("Started")
            "pending" -> say("Reopened")
            "deleted" -> say("Removed")
            else -> say("Updated")
        }
        "delete" -> say("Removed")
        "get" -> say("Viewed")
        "list" -> "Listed tasks"
        "clear" -> "Cleared the list"
        else -> action.orEmpty()
    }
}

/** The subject of task [id] from the list in the call's result. */
private fun todoSubject(item: ToolItem, id: Int): String? =
    item.details?.arr("tasks")?.firstNotNullOfOrNull { t -> (t as? JsonObject)?.takeIf { it.int("id") == id }?.str("subject") }

const val MAX_SHOWN = 120_000

/** Text under a tool row. [isDiff] means [text] is the tool's `details.diff`. */
@Immutable
data class ToolBody(val text: String, val isDiff: Boolean)

/**
 * The body of a tool: [full] for the viewer (capped at [MAX_SHOWN]), else the preview of [previewLines] lines. A
 * running tool shows its output tail; a finished read or write its head, anything else its tail. Arguments are shown in
 * full for tools other than bash, read and ls.
 */
fun toolBody(item: ToolItem, full: Boolean, previewLines: Int): ToolBody? {
    val diff = item.details?.str("diff")
    return when {
        item.status == ToolStatus.Running -> (subagentProgress(item) ?: item.output).takeIf { it.isNotBlank() }?.let { ToolBody(tail(it, if (full) 2000 else previewLines), false) }
        item.status == ToolStatus.Streaming || item.status == ToolStatus.Pending ->
            if (full) ToolBody(item.args?.toDisplayString() ?: item.argsText, false) else null
        diff != null && !item.isError -> ToolBody(if (full) diff.take(MAX_SHOWN) else diff, true)
        else -> {
            val result = item.result ?: item.output
            val argsText = if (full && item.args != null && item.name !in setOf("bash", "read", "ls")) {
                item.args!!.toDisplayString() + "\n\n"
            } else {
                ""
            }
            when {
                full -> ToolBody(argsText + result.take(MAX_SHOWN) + if (result.length > MAX_SHOWN) "\n… ${result.length - MAX_SHOWN} more characters" else "", false)
                result.isBlank() -> null
                item.name == "read" || item.name == "write" -> ToolBody(head(result, previewLines), false)
                isSubagentTool(item.name) -> ToolBody(head(subagentResult(item) ?: result, previewLines), false)
                else -> ToolBody(tail(result, previewLines), false)
            }
        }
    }
}

fun head(text: String, lines: Int): String {
    var count = 0
    var i = 0
    while (i < text.length && count < lines) {
        val nl = text.indexOf('\n', i)
        if (nl < 0) return text
        i = nl + 1
        count++
    }
    return text.substring(0, i).trimEnd()
}

fun tail(text: String, lines: Int): String {
    val trimmed = text.trimEnd()
    var count = 0
    var i = trimmed.length
    while (i > 0 && count < lines) {
        val nl = trimmed.lastIndexOf('\n', i - 1)
        if (nl < 0) return trimmed
        i = nl
        count++
    }
    return trimmed.substring(i + 1)
}

/** Number of lines in [text] (a trailing newline does not start a line). */
fun lineCount(text: String): Int {
    val t = text.trimEnd('\n')
    if (t.isEmpty()) return 0
    var n = 1
    for (ch in t) if (ch == '\n') n++
    return n
}

fun formatDuration(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> String.format(Locale.ROOT, "%.1fs", ms / 1000.0)
    else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
}

/** A ticking timer: 0:07, 2:41, 1:02:03. */
fun formatElapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%d:%02d", m, sec)
}

/**
 * Elapsed time on a running step, ticking once a second: nothing under 1 s (fast steps stay quiet), then "4s",
 * "1m 3s", "1h 2m".
 */
fun formatStepElapsed(ms: Long): String? {
    if (ms < 1000) return null
    val s = ms / 1000
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${s % 60}s"
        else -> "${s / 3600}h ${(s % 3600) / 60}m"
    }
}

/** "Worked for 12s", "Worked for 2m 5s". */
fun formatWorked(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(1)
    return if (s < 60) "${s}s" else "${s / 60}m ${s % 60}s"
}

fun shortPath(path: String): String {
    val parts = path.trimEnd('/').split('/').filter { it.isNotEmpty() }
    return if (parts.size <= 2) path else "…/" + parts.takeLast(2).joinToString("/")
}

fun baseName(path: String): String = path.trimEnd('/').substringAfterLast('/').ifBlank { path }

/** Context use in percent, when the model's window is known. */
fun contextPercent(totalTokens: Long, window: Long?): Int? =
    if (window != null && window > 0) (totalTokens * 100 / window).toInt().coerceIn(0, 100) else null

private val exitLine = Regex("Command exited with code (\\d+)")

/** A bash step's exit code: from its output, or 0 for a successful run. Null while running or when unknown. */
fun exitCodeOf(item: ToolItem): Int? = when (item.status) {
    ToolStatus.Done -> exitLine.find(item.result.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0
    ToolStatus.Error -> exitLine.find(item.result.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
    else -> null
}

/** A dialog message that reads as a command or path gets a mono block. */
fun looksLikeCommand(text: String): Boolean {
    val t = text.trim()
    if (t.isEmpty()) return false
    if (t.startsWith("$ ") || t.startsWith("`") || t.startsWith("/") || t.startsWith("~/") || t.startsWith("./")) return true
    val first = t.substringBefore(' ').substringBefore('\n')
    val commands = setOf("rm", "git", "npm", "npx", "bun", "node", "sudo", "mv", "cp", "chmod", "chown", "curl", "wget", "docker", "kubectl", "make", "python", "python3", "pip", "cargo", "go", "./gradlew", "gradle", "sh", "bash", "mkdir", "cat", "echo")
    if (first in commands) return true
    return t.contains('\n') && t.count { it in "|&;{}$=<>" } >= 2
}

// Diffs

enum class DiffKind { Add, Del, Context, Skip, Hunk }

/**
 * One diff row. [number] is the line in the new file (blank for deletions), [old] the line in the old file (blank for
 * additions, and for diffs that carry one number only, such as markdown fences). A [DiffKind.Skip] row's [number]
 * counts the lines it stands for.
 */
@Immutable
data class DiffLine(val kind: DiffKind, val number: String, val text: String, val old: String = "")

private val hunkHeader = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@.*""")
private val fileMeta = listOf("diff --git ", "index ", "--- ", "+++ ", "new file mode", "deleted file mode", "similarity index", "rename from", "rename to", "old mode", "new mode")

/**
 * A tool's diff as rows with both line numbers. Pi's edit diff (`+12 text`, `-12 text`, ` 12 text`, `   ...`) numbers
 * additions in the new file and deletions and context in the old one; the other number follows from the lines added
 * and removed so far (a skipped run moves both alike). A unified diff (`@@ -a,b +c,d @@`) counts from its hunk headers;
 * its `---`/`+++` file lines are dropped (the card's header names the file).
 */
fun parseDiff(diff: String): List<DiffLine> =
    if (diff.lineSequence().any { hunkHeader.matches(it) }) parseUnified(diff.trimEnd('\n')) else parsePiDiff(diff)

private fun parsePiDiff(diff: String): List<DiffLine> {
    val out = ArrayList<DiffLine>()
    var shift = 0 // new line - old line, from the changes so far
    for (line in diff.lineSequence()) {
        if (line.isEmpty()) {
            out += DiffLine(DiffKind.Context, "", "")
            continue
        }
        if (line.startsWith("@@")) {
            // A merged change's next edit (see [mergeChanges]) numbers from scratch.
            shift = 0
            out += DiffLine(DiffKind.Hunk, "", line)
            continue
        }
        if (line.startsWith("+++") || line.startsWith("---")) continue
        val kind = when (line[0]) {
            '+' -> DiffKind.Add
            '-' -> DiffKind.Del
            else -> DiffKind.Context
        }
        val rest = if (line[0] == '+' || line[0] == '-' || line[0] == ' ') line.substring(1) else line
        val trimmed = rest.trimStart()
        if (kind == DiffKind.Context && trimmed == "...") {
            out += DiffLine(DiffKind.Skip, "", "")
            continue
        }
        val digits = trimmed.takeWhile { it.isDigit() }
        val numbered = digits.isNotEmpty() && digits.length <= 9 && (trimmed.length == digits.length || trimmed[digits.length] == ' ')
        if (!numbered) {
            out += DiffLine(kind, "", rest)
            continue
        }
        val n = digits.toInt()
        val text = if (trimmed.length > digits.length) trimmed.substring(digits.length + 1) else ""
        out += when (kind) {
            DiffKind.Add -> {
                shift++
                DiffLine(kind, n.toString(), text)
            }
            DiffKind.Del -> {
                shift--
                DiffLine(kind, "", text, old = n.toString())
            }
            else -> DiffLine(kind, (n + shift).takeIf { it > 0 }?.toString().orEmpty(), text, old = n.toString())
        }
    }
    return out
}

private fun parseUnified(diff: String): List<DiffLine> {
    val out = ArrayList<DiffLine>()
    var oldNo = 0
    var newNo = 0
    var inHunk = false
    for (line in diff.lineSequence()) {
        val h = hunkHeader.matchEntire(line)
        when {
            h != null -> {
                oldNo = h.groupValues[1].toIntOrNull() ?: 0
                newNo = h.groupValues[3].toIntOrNull() ?: 0
                inHunk = true
                out += DiffLine(DiffKind.Hunk, "", line)
            }
            !inHunk && fileMeta.any(line::startsWith) -> Unit
            line.startsWith("diff --git ") -> inHunk = false
            line.startsWith("\\") -> Unit
            !inHunk -> out += DiffLine(DiffKind.Context, "", line)
            line.startsWith("+") -> out += DiffLine(DiffKind.Add, (newNo++).toString(), line.substring(1))
            line.startsWith("-") -> out += DiffLine(DiffKind.Del, "", line.substring(1), old = (oldNo++).toString())
            else -> out += DiffLine(DiffKind.Context, (newNo++).toString(), line.removePrefix(" "), old = (oldNo++).toString())
        }
    }
    while (out.isNotEmpty() && out.last().kind == DiffKind.Context && out.last().text.isEmpty() && out.last().number.isEmpty()) out.removeAt(out.lastIndex)
    return out
}

@Immutable

data class DiffStats(val added: Int, val removed: Int)

fun diffStats(lines: List<DiffLine>): DiffStats = DiffStats(lines.count { it.kind == DiffKind.Add }, lines.count { it.kind == DiffKind.Del })

/** Runs of more than [keep] unchanged lines collapse into one Skip row that counts them. */
fun collapseContext(lines: List<DiffLine>, keep: Int = 3): List<DiffLine> {
    val out = ArrayList<DiffLine>(lines.size)
    var i = 0
    while (i < lines.size) {
        if (lines[i].kind != DiffKind.Context) {
            out += lines[i]
            i++
            continue
        }
        var j = i
        while (j < lines.size && lines[j].kind == DiffKind.Context) j++
        val run = j - i
        val atStart = i == 0
        val atEnd = j == lines.size
        val limit = if (atStart || atEnd) keep else keep * 2
        if (run <= limit) {
            for (k in i until j) out += lines[k]
        } else if (atStart) {
            out += DiffLine(DiffKind.Skip, (run - keep).toString(), "")
            for (k in j - keep until j) out += lines[k]
        } else if (atEnd) {
            for (k in i until i + keep) out += lines[k]
            out += DiffLine(DiffKind.Skip, (run - keep).toString(), "")
        } else {
            for (k in i until i + keep) out += lines[k]
            out += DiffLine(DiffKind.Skip, (run - keep * 2).toString(), "")
            for (k in j - keep until j) out += lines[k]
        }
        i = j
    }
    return out
}

// Turn summaries

/** One file a turn changed: lines added and removed, and the diff to open. */
@Immutable
data class FileChange(val path: String, val added: Int, val removed: Int, val diff: String)

/** The change a finished edit or write made, or null for other tools, failed steps and edits without a diff. */
fun fileChange(item: ToolItem): FileChange? {
    if (item.status != ToolStatus.Done) return null
    val name = item.name.lowercase()
    if (name !in setOf("edit", "multiedit", "write")) return null
    val path = item.args?.str("path") ?: item.args?.str("file_path") ?: return null
    val diff = item.details?.str("diff")
    if (diff != null) {
        val stats = diffStats(parseDiff(diff))
        return FileChange(path, stats.added, stats.removed, diff)
    }
    if (name != "write") return null
    val content = item.args?.str("content") ?: return null
    return FileChange(path, lineCount(content), 0, newFileDiff(content))
}

/** A new file as Pi's diff format: every line added, numbered from 1. */
fun newFileDiff(content: String): String =
    content.trimEnd('\n').lineSequence().withIndex().joinToString("\n") { (i, line) -> "+${i + 1} $line" }

/** The row that separates two edits of one file in a merged diff. */
const val NEXT_EDIT = "@@ next edit"

/**
 * Changes per path in first-touched order; several edits of one file add up and their diffs follow each other, split
 * by a [NEXT_EDIT] row.
 */
fun mergeChanges(changes: List<FileChange>): List<FileChange> {
    val byPath = LinkedHashMap<String, FileChange>()
    for (c in changes) {
        val old = byPath[c.path]
        byPath[c.path] = if (old == null) c else FileChange(c.path, old.added + c.added, old.removed + c.removed, old.diff + "\n" + NEXT_EDIT + "\n" + c.diff)
    }
    return byPath.values.toList()
}

/**
 * Read-only steps (and todo list updates, which the panel shows) that a run of three or more folds into one row; a run
 * of todo updates alone folds at any length. Failed or running steps stay on their own, except a search or listing
 * that found nothing ([isEmptyResult]).
 */
fun isGroupable(item: ToolItem): Boolean =
    (item.status == ToolStatus.Done || isEmptyResult(item)) && when (toolLabel(item).kind) {
        ToolKind.Read, ToolKind.Search, ToolKind.List, ToolKind.Todo -> true
        else -> false
    }

fun isTodo(item: ToolItem): Boolean = item.name == TODO_TOOL

private val unavailable = Regex("""(?i)(tool\b.*\b(not found|not available|unavailable|is disabled|not enabled)|unknown tool|no such tool)""")
private val noResults = Regex("""(?i)(no (files|matches|results)( found)?|empty directory|no such file or directory|path not found|does not exist|enoent)""")

/** A call to a tool this host does not offer ("Tool ls not found"): it reads as a quiet note, not a failure. */
fun isUnavailable(item: ToolItem): Boolean = item.status == ToolStatus.Error && unavailable.containsMatchIn(item.result.orEmpty().take(300))

/**
 * A search or listing that found nothing ("No matches found"), or failed only because the folder is missing: it reads
 * "no results", not as a failed step.
 */
fun isEmptyResult(item: ToolItem): Boolean {
    if (item.status != ToolStatus.Error && item.status != ToolStatus.Done || isUnavailable(item)) return false
    val result = item.result.orEmpty().trim()
    if (result.length > 200) return false
    val kind = toolLabel(item).kind
    return (kind == ToolKind.Search || kind == ToolKind.List) && noResults.containsMatchIn(result)
}

/** A step that counts as failed in the turn's summary: an error that is neither [isUnavailable] nor [isEmptyResult]. */
fun isFailedStep(item: ToolItem): Boolean = item.status == ToolStatus.Error && !isUnavailable(item) && !isEmptyResult(item)

/** A finished step's duration for its row: nothing under a second (fast steps stay quiet), else "1.4s", "2m 5s". */
fun stepDuration(ms: Long?): String? = ms?.takeIf { it >= 1000 }?.let(::formatDuration)

/**
 * What a finished turn did, for its fold: "Edited 3 files · ran 2 commands · read 4 files", at most three parts in
 * the order edits, commands, reads, searches, agents. [files] are the files it changed. Falls back to "Thought" (only
 * reasoning) or "N steps".
 */
fun turnSummary(tools: List<ToolItem>, files: Int, thoughts: Int): String {
    var commands = 0
    var searches = 0
    var agents = 0
    var webSearches = 0
    val pages = HashSet<String>()
    val readPaths = HashSet<String>()
    for (t in tools) {
        if (isUnavailable(t)) continue
        val label = toolLabel(t)
        when {
            isSubagentTool(t.name) -> agents++
            t.name == WebTools.SEARCH || t.name == WebTools.SOURCE_CHECK -> webSearches++
            t.name == WebTools.FETCH -> pages += WebTools.fetchUrls(t)
            label.kind == ToolKind.Bash -> commands++
            label.kind == ToolKind.Read -> readPaths += label.detail.substringBefore(':')
            label.kind == ToolKind.Search || label.kind == ToolKind.List -> searches++
            else -> Unit
        }
    }
    fun count(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"
    val parts = buildList {
        if (files > 0) add("edited " + count(files, "file", "files"))
        if (commands > 0) add("ran " + count(commands, "command", "commands"))
        if (readPaths.isNotEmpty()) add("read " + count(readPaths.size, "file", "files"))
        if (searches > 0) add(count(searches, "search", "searches"))
        if (webSearches > 0) add(if (webSearches == 1) "searched the web" else "searched the web $webSearches times")
        if (pages.isNotEmpty()) add("read " + count(pages.size, "page", "pages"))
        if (agents > 0) add(count(agents, "agent", "agents"))
    }.take(3)
    if (parts.isEmpty()) {
        val steps = tools.size + thoughts
        return if (tools.isEmpty() && thoughts > 0) "Thought" else if (steps == 1) "1 step" else "$steps steps"
    }
    return parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
}

/** "Read 3 files, ran 1 search": what a group of read-only steps did. */
fun groupSummary(tools: List<ToolItem>): String {
    var reads = 0
    var searches = 0
    var lists = 0
    var todos = 0
    for (t in tools) {
        when (toolLabel(t).kind) {
            ToolKind.Read -> reads++
            ToolKind.Search -> searches++
            ToolKind.List -> lists++
            ToolKind.Todo -> todos++
            else -> Unit
        }
    }
    val parts = buildList {
        if (reads > 0) add(if (reads == 1) "read 1 file" else "read $reads files")
        if (searches > 0) add(if (searches == 1) "ran 1 search" else "ran $searches searches")
        if (lists > 0) add(if (lists == 1) "listed 1 folder" else "listed $lists folders")
        if (todos > 0) add(if (todos == 1) "updated todos" else "updated todos $todos times")
    }
    return parts.joinToString(", ").replaceFirstChar { it.uppercase() }
}

/** The files and patterns a group touched, for its one-line detail: base names, each once. */
fun groupDetail(tools: List<ToolItem>): String = tools.mapNotNull { t ->
    val args = t.args
    when (toolLabel(t).kind) {
        ToolKind.Read, ToolKind.List -> (args?.str("path") ?: args?.str("file_path"))?.let(::baseName)
        ToolKind.Search -> args?.str("pattern")
        ToolKind.Todo -> todoSummary(t).substringAfter(": ", "").ifBlank { null }
        else -> null
    }
}.distinct().joinToString(", ")

/**
 * An `ask_user_question` the user dismissed (or left without an answer): the extension's result is "User declined to
 * answer questions" with `details.cancelled`. Its row says "Declined" with a neutral glyph, not a check.
 */
fun isDeclinedAsk(item: ToolItem): Boolean =
    item.name == ASK_TOOL && item.status == ToolStatus.Done &&
        (item.details?.bool("cancelled") == true || item.result?.trim()?.startsWith("User declined to answer questions") == true)
