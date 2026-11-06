package opensec.pibolt.app.ui.session

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import opensec.pibolt.protocol.arr
import opensec.pibolt.protocol.int
import opensec.pibolt.protocol.session.SessionReducer.TODO_TOOL
import opensec.pibolt.protocol.session.SlashCommand
import opensec.pibolt.protocol.session.SubagentReducer
import opensec.pibolt.protocol.session.ToolItem
import opensec.pibolt.protocol.session.ToolStatus
import opensec.pibolt.protocol.str
import opensec.pibolt.protocol.toDisplayString
import java.util.Locale

/** What kind of step a tool is; picks the expanded card. */
enum class ToolKind { Bash, Read, Write, Edit, Search, List, Todo, Other }

@Immutable

data class ToolLabel(val kind: ToolKind, val verb: String, val detail: String)

private val partialField = Regex("\"(command|path|file_path|pattern|query|url|description|prompt|subagent_type|subject|agent_id|message|action|status)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)")

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
        "bash", "shell", "exec" -> ToolLabel(ToolKind.Bash, "Run", arg("command").orEmpty().lineSequence().firstOrNull().orEmpty())
        "read" -> {
            val range = listOfNotNull(args?.get("offset")?.let { "from ${(it as? JsonPrimitive)?.content}" }, args?.get("limit")?.let { "${(it as? JsonPrimitive)?.content} lines" })
            ToolLabel(ToolKind.Read, "Read", listOfNotNull(arg("path", "file_path"), range.joinToString(", ").ifBlank { null }).joinToString("  "))
        }
        "write" -> ToolLabel(ToolKind.Write, "Write", arg("path", "file_path").orEmpty())
        "edit", "multiedit" -> ToolLabel(ToolKind.Edit, "Edit", arg("path", "file_path").orEmpty())
        "grep" -> ToolLabel(ToolKind.Search, "Search", listOfNotNull(arg("pattern"), arg("path")?.let { "in $it" }).joinToString(" "))
        "find", "glob" -> ToolLabel(ToolKind.Search, "Find", listOfNotNull(arg("pattern"), arg("path")?.let { "in $it" }).joinToString(" "))
        "ls" -> ToolLabel(ToolKind.List, "List", arg("path") ?: ".")
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
        else -> {
            val first = args?.entries?.firstOrNull { (it.value as? JsonPrimitive)?.isString == true }?.let { (it.value as JsonPrimitive).content }
            ToolLabel(ToolKind.Other, name, (first ?: arg("command", "path", "pattern", "query", "url")).orEmpty().lineSequence().firstOrNull().orEmpty())
        }
    }
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
        d.str("activity")?.let(::firstLine)?.takeIf { it.isNotBlank() },
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

fun slashSuggestions(text: String, commands: List<SlashCommand>): List<SlashCommand> {
    if (!text.startsWith("/") || text.contains(' ') || text.contains('\n')) return emptyList()
    val query = text.drop(1).lowercase()
    return commands.filter { it.name.lowercase().startsWith(query) }.take(5)
}

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

@Immutable

data class DiffLine(val kind: DiffKind, val number: String, val text: String)

/** Pi's edit diff (`+12 text`, `-12 text`, ` 12 text`, `   ...`) and plain unified diffs. */
fun parseDiff(diff: String): List<DiffLine> {
    val out = ArrayList<DiffLine>()
    for (line in diff.lineSequence()) {
        if (line.isEmpty()) {
            out += DiffLine(DiffKind.Context, "", "")
            continue
        }
        if (line.startsWith("@@")) {
            out += DiffLine(DiffKind.Hunk, "", line)
            continue
        }
        if (line.startsWith("+++") || line.startsWith("---")) {
            out += DiffLine(DiffKind.Hunk, "", line)
            continue
        }
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
        if (digits.isNotEmpty() && trimmed.length > digits.length && trimmed[digits.length] == ' ') {
            out += DiffLine(kind, digits, trimmed.substring(digits.length + 1))
        } else if (digits.isNotEmpty() && trimmed.length == digits.length) {
            out += DiffLine(kind, digits, "")
        } else {
            out += DiffLine(kind, "", rest)
        }
    }
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
    return FileChange(path, lineCount(content), 0, content.trimEnd('\n').lineSequence().joinToString("\n") { "+$it" })
}

/** Changes per path in first-touched order; several edits of one file add up and their diffs follow each other. */
fun mergeChanges(changes: List<FileChange>): List<FileChange> {
    val byPath = LinkedHashMap<String, FileChange>()
    for (c in changes) {
        val old = byPath[c.path]
        byPath[c.path] = if (old == null) c else FileChange(c.path, old.added + c.added, old.removed + c.removed, old.diff + "\n" + c.diff)
    }
    return byPath.values.toList()
}

/**
 * Read-only steps (and todo list updates, which the panel shows) that a run of three or more folds into one row.
 * Failed or running steps stay on their own.
 */
fun isGroupable(item: ToolItem): Boolean =
    item.status == ToolStatus.Done && when (toolLabel(item).kind) {
        ToolKind.Read, ToolKind.Search, ToolKind.List, ToolKind.Todo -> true
        else -> false
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
