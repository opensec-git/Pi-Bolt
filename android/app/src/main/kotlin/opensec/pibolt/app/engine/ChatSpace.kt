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
 * A chat's engine starts with a tool allowlist (`--tools`, packages/coding-agent/src/cli/args.ts): only the bundled web
 * tools ([WEB_TOOLS], pi-web-access) and [ASK_TOOL] (rpiv-ask-user-question) are registered, so the model can search the
 * web, read pages and ask the user, but never runs a command or touches a file: no built-in tool (bash, read, edit,
 * write...) and no other extension's. `--no-mcp` too, since an allowlist keeps MCP tools (a user's filesystem server
 * would otherwise reach chats): no MCP server connects. With "Web search" off only [ASK_TOOL] stays. The bundled extensions
 * still load (the other tools are filtered out; their hooks are guarded and do nothing without them), as do the app's
 * helper and provider extensions (local models). The coding assistant's system prompt is replaced by [systemPrompt].
 *
 * Project sessions keep every tool; with "Web search" off they leave out [PROJECT_WEB_TOOLS] (`--exclude-tools`).
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

    /** pi-web-access's tools a chat may use: search, read a page, read a stored result. */
    val WEB_TOOLS = listOf("web_search", "fetch_content", "get_search_content")

    /** Every tool pi-web-access registers: what a project session leaves out with "Web search" off. */
    val PROJECT_WEB_TOOLS = WEB_TOOLS + "source_check"

    /** rpiv-ask-user-question's tool: structured questions, answered in the app's question card. */
    const val ASK_TOOL = "ask_user_question"

    /** The tools a chat's engine registers ([webSearch]: the "Web search" setting). */
    fun chatTools(webSearch: Boolean): List<String> = (if (webSearch) WEB_TOOLS else emptyList()) + ASK_TOOL

    /** A chat's system prompt: what it can do with [chatTools], and that it has no shell or files. */
    fun systemPrompt(webSearch: Boolean): String =
        "You are Pi, a helpful assistant in the Pi-Bolt app on the user's phone. This is a plain chat: you have no shell, " +
            "no files and no code execution. " +
            (
                if (webSearch) {
                    "You can search the web (web_search) and read web pages (fetch_content, get_search_content): use them for " +
                        "recent events, facts you are unsure of and anything the user asks you to look up, and cite the pages you used. "
                } else {
                    "You have no internet access, so answer from your own knowledge and say so when you are unsure or something " +
                        "may be out of date. "
                }
            ) +
            "When the request is ambiguous and the answer depends on the user's choice, ask with ask_user_question (a few " +
            "options each) instead of guessing. Be direct and concise. Use Markdown when it helps: lists, tables, code blocks."

    /** The working directory of every chat. */
    fun dir(home: File): File = File(home, ".pibolt/chats")

    /** Where chats are saved (`--session-dir`). */
    fun sessionDir(agentDir: File): File = File(agentDir, "chats")

    /** True when [cwd] is the chats' working directory under [home]. */
    fun isChat(cwd: String, home: File): Boolean = File(cwd).absoluteFile.normalize() == dir(home).absoluteFile.normalize()

    /** The CLI flags that make an engine a chat's. */
    fun chatArgs(agentDir: File, webSearch: Boolean = true): List<String> =
        listOf("--tools", chatTools(webSearch).joinToString(","), "--no-mcp", "--session-dir", sessionDir(agentDir).path, "--system-prompt", systemPrompt(webSearch))

    /** A project engine's tool flags: none, or the web tools left out when [webSearch] is off. */
    fun projectArgs(webSearch: Boolean): List<String> =
        if (webSearch) emptyList() else listOf("--exclude-tools", PROJECT_WEB_TOOLS.joinToString(","))

    /**
     * The engine's arguments for [target] in [cwd]: RPC mode and the target, plus [chatArgs] in the chats' directory,
     * else [projectArgs].
     */
    fun engineArgs(target: SessionTarget, cwd: String, home: File, agentDir: File, webSearch: Boolean = true): List<String> =
        listOf("--mode", "rpc") + target.cliArgs() + (if (isChat(cwd, home)) chatArgs(agentDir, webSearch) else projectArgs(webSearch))

    /** A chat's title: its name (`/name`, rename), else its first prompt, else [UNTITLED]. */
    fun title(name: String?, firstPrompt: String?): String =
        name?.trim()?.takeIf { it.isNotEmpty() } ?: firstPrompt?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: UNTITLED
}
