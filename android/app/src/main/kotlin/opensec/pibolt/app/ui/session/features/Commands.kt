package opensec.pibolt.app.ui.session.features

import androidx.compose.runtime.Immutable
import opensec.pibolt.protocol.session.SlashCommand

/**
 * The app's own slash commands: the terminal's built-ins that make sense on a phone. The host never sees these; the
 * composer runs them here (a sheet, an RPC command). [localOnly] ones need the engine on this phone (remote control
 * refuses session commands).
 */
enum class Builtin(val command: String, val description: String, val hint: String? = null, val localOnly: Boolean = false) {
    New("new", "Start a new session", localOnly = true),
    Resume("resume", "Open another session of this project", localOnly = true),
    Fork("fork", "New session from an earlier message", localOnly = true),
    Clone("clone", "Duplicate this session", localOnly = true),
    Compact("compact", "Compact the context", "[instructions]"),
    Name("name", "Rename this session", "<name>"),
    Session("session", "Tokens, cost and context use"),
    Model("model", "Choose a model", "[search]"),
    Thinking("thinking", "Set the thinking level", "[level]"),
    Copy("copy", "Copy the last reply"),
    Export("export", "Share the session as Markdown, JSONL or HTML"),
    Search("search", "Search the transcript", "[text]"),
    Shell("!", "Run a shell command (!! keeps output out of context)", "<command>", localOnly = true),
    Tree("tree", "Go back, switch or label branches", localOnly = true),
    Skills("skills", "Browse skills and prompt templates"),
    Packages("packages", "Install, switch off or remove packages", localOnly = true),
    Mcp("mcp-servers", "Add, check or remove MCP servers", localOnly = true),
    Trust("trust", "Trust this project's .pi resources", localOnly = true),
    Reload("reload", "Restart the engine to reload extensions, skills and settings", localOnly = true),
    ScopedModels("scoped-models", "Choose the models in the picker", localOnly = true),
    Tuning("tuning", "Retry and compaction settings", localOnly = true),
    Import("import", "Import a session file (JSONL)", localOnly = true),
    Share("share", "Share this session"),
    Bug("bug", "Report a bug", "[description]"),
}

enum class CommandSource(val label: String) { App("app"), Extension("extension"), Prompt("prompt"), Skill("skill") }

/** One row of the palette. [builtin] is set for the app's own commands. */
@Immutable
data class PaletteEntry(val name: String, val description: String, val source: CommandSource, val hint: String? = null, val builtin: Builtin? = null) {
    /** What the composer gets when the row is picked: `/name ` (or `!` for the shell). */
    val insert: String get() = if (builtin == Builtin.Shell) "!" else "/$name "
    val label: String get() = if (builtin == Builtin.Shell) "!" else "/$name"
}

/** A built-in typed in full and sent, with the text after its name. */
data class BuiltinCall(val builtin: Builtin, val args: String)

object CommandPalette {
    /** At most this many rows; the palette scrolls. */
    const val LIMIT = 40

    /**
     * Built-ins this session can run, then the host's commands (extensions, prompt templates, skills). A host command
     * with a built-in's name wins: it is what the terminal would run.
     */
    fun entries(host: List<SlashCommand>, local: Boolean): List<PaletteEntry> {
        val hostNames = host.mapTo(HashSet()) { it.name }
        val builtins = Builtin.entries.filter { (local || !it.localOnly) && it.command !in hostNames }
            .map { PaletteEntry(it.command, it.description, CommandSource.App, it.hint, it) }
        // The app's own helper extension (features2.AppExtension) is not for people.
        val fromHost = host.filterNot { it.name == "pibolt-app" || it.name.startsWith("pibolt-app:") }.map { c ->
            PaletteEntry(c.name, c.description?.takeIf { it.isNotBlank() }.orEmpty(), sourceOf(c.source), c.argumentHint)
        }
        return builtins + fromHost
    }

    fun sourceOf(source: String): CommandSource = when (source) {
        "prompt" -> CommandSource.Prompt
        "skill" -> CommandSource.Skill
        else -> CommandSource.Extension
    }

    /**
     * The rows for [text]: shown while the composer holds only `/` and a name being typed (no space, one line). An
     * empty name lists everything in [entries] order; otherwise fuzzy by name, then by description.
     */
    fun filter(text: String, entries: List<PaletteEntry>): List<PaletteEntry> {
        if (!text.startsWith("/") || text.any { it == ' ' || it == '\n' }) return emptyList()
        val query = text.drop(1)
        if (query.isEmpty()) return entries.take(LIMIT)
        val byName = Fuzzy.rank(query, entries, LIMIT) { it.name }
        if (byName.size >= LIMIT || query.length < 3) return byName
        val names = byName.mapTo(HashSet()) { it.name to it.source }
        val byDescription = Fuzzy.rank(query, entries.filter { (it.name to it.source) !in names && query.lowercase() in it.description.lowercase() }, LIMIT - byName.size) { it.description }
        return byName + byDescription
    }

    /**
     * The usage line while the arguments of a known command are typed (`/compact focus on…` shows
     * `/compact [instructions]`), or null.
     */
    fun hintFor(text: String, entries: List<PaletteEntry>): PaletteEntry? {
        if (!text.startsWith("/") || '\n' in text) return null
        val space = text.indexOf(' ')
        if (space < 0) return null
        val name = text.substring(1, space)
        return entries.firstOrNull { it.name == name && it.hint != null }
    }

    /**
     * The built-in [text] calls, or null when it is a prompt for the host. `!cmd` and `!!cmd` are the shell; a host
     * command of the same name shadows a built-in.
     */
    fun parse(text: String, host: List<SlashCommand>, local: Boolean): BuiltinCall? {
        val t = text.trim()
        if (t.startsWith("!")) {
            if (!local) return null
            return BuiltinCall(Builtin.Shell, t.drop(1))
        }
        if (!t.startsWith("/")) return null
        val end = t.indexOfFirst { it == ' ' || it == '\n' }.let { if (it < 0) t.length else it }
        val name = t.substring(1, end)
        if (host.any { it.name == name }) return null
        val builtin = Builtin.entries.firstOrNull { it.command == name && it != Builtin.Shell } ?: return null
        if (builtin.localOnly && !local) return null
        return BuiltinCall(builtin, t.substring(end).trim())
    }
}

/** `!cmd` runs and adds its output to the next prompt's context; `!!cmd` runs without that (as in the terminal). */
data class ShellCommand(val command: String, val excludeFromContext: Boolean) {
    companion object {
        /** From the text after the first `!` of a [Builtin.Shell] call; null when there is no command. */
        fun parse(afterBang: String): ShellCommand? {
            val exclude = afterBang.startsWith("!")
            val command = (if (exclude) afterBang.drop(1) else afterBang).trim()
            return if (command.isEmpty()) null else ShellCommand(command, exclude)
        }
    }
}
