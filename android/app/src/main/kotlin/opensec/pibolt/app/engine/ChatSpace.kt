package opensec.pibolt.app.engine

import opensec.pibolt.protocol.backend.SessionTarget
import java.io.File

/**
 * Plain chats: conversations with the model that belong to no project. Every chat is a Pi session that runs in one
 * app-private working directory, [dir] (`HOME/.pibolt/chats`, created empty, never listed as a project: projects live
 * in `HOME/projects`), and is saved in its own session folder, [sessionDir] (`agentDir/chats`), apart from every
 * project's sessions (`agentDir/sessions/--<cwd>--`). So chats never show in a project's lists, and projects never in
 * the chats.
 *
 * A chat's engine starts with `--no-tools` (packages/coding-agent/src/cli/args.ts): no built-in, extension or MCP tool
 * is registered, so the model only talks; it never runs a command or touches a file. The bundled extensions still load
 * (their tools are filtered out; their hooks are guarded and do nothing without them), as do the app's helper and
 * provider extensions (local models). The coding assistant's system prompt is replaced by [SYSTEM_PROMPT].
 */
object ChatSpace {
    /**
     * The chats' key where the UI keys by project name (recent prompts). Not a project name: the project list skips
     * names that start with a dot.
     */
    const val KEY = ".chats"

    /** How a chat's live session is named where a session shows its project or computer (notifications). */
    const val NAME = "Chat"

    /** A chat without a name or a text prompt yet. */
    const val UNTITLED = "New chat"

    const val SYSTEM_PROMPT =
        "You are Pi, a helpful assistant in the Pi-Bolt app on the user's phone. This is a plain chat: you have no tools " +
            "and no access to files, a shell or the internet, so answer from your own knowledge and say so when you are unsure " +
            "or something may be out of date. Be direct and concise. Use Markdown when it helps: lists, tables, code blocks."

    /** The working directory of every chat. */
    fun dir(home: File): File = File(home, ".pibolt/chats")

    /** Where chats are saved (`--session-dir`). */
    fun sessionDir(agentDir: File): File = File(agentDir, "chats")

    /** True when [cwd] is the chats' working directory under [home]. */
    fun isChat(cwd: String, home: File): Boolean = File(cwd).absoluteFile.normalize() == dir(home).absoluteFile.normalize()

    /** The CLI flags that make an engine a chat's. */
    fun chatArgs(agentDir: File): List<String> =
        listOf("--no-tools", "--session-dir", sessionDir(agentDir).path, "--system-prompt", SYSTEM_PROMPT)

    /** The engine's arguments for [target] in [cwd]: RPC mode and the target, plus [chatArgs] in the chats' directory. */
    fun engineArgs(target: SessionTarget, cwd: String, home: File, agentDir: File): List<String> =
        listOf("--mode", "rpc") + target.cliArgs() + (if (isChat(cwd, home)) chatArgs(agentDir) else emptyList())

    /** A chat's title: its name (`/name`, rename), else its first prompt, else [UNTITLED]. */
    fun title(name: String?, firstPrompt: String?): String =
        name?.trim()?.takeIf { it.isNotEmpty() } ?: firstPrompt?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: UNTITLED
}
