package opensec.pibolt.app.ui.packages

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.bool
import opensec.pibolt.protocol.str

/** One MCP server as `pi mcp list --json` reports it (packages/coding-agent/src/extensions/mcp/cli.ts). */
@Immutable
data class McpServer(
    val name: String,
    val scope: String,
    val enabled: Boolean,
    val transport: String,
    /** connected, failed, needs-auth, disabled, … */
    val state: String,
    val tools: List<String>,
    val error: String?,
)

data class McpReport(val servers: List<McpServer>, val errors: List<String>, val note: String?)

object McpServers {
    /** Server names Pi accepts (docs/mcp.md): letters, digits, `_` and `-`. */
    private val NAME = Regex("^[A-Za-z0-9_-]+$")

    fun validName(name: String): Boolean = NAME.matches(name)

    /** The JSON object in [output] (`pi mcp list --json` may print notes before it); null when there is none. */
    fun parse(output: String): McpReport? {
        val start = output.indexOf('{')
        val end = output.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val json = runCatching { ProtocolJson.parseToJsonElement(output.substring(start, end + 1)) as? JsonObject }.getOrNull() ?: return null
        val servers = (json["servers"] as? JsonArray).orEmpty().mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            McpServer(
                name = o.str("name") ?: return@mapNotNull null,
                scope = o.str("scope") ?: "global",
                enabled = o.bool("enabled") ?: true,
                transport = o.str("transport").orEmpty(),
                state = o.str("state") ?: "unknown",
                tools = (o["tools"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
                error = o.str("error"),
            )
        }
        val errors = (json["errors"] as? JsonArray).orEmpty().map { e ->
            when (e) {
                is JsonPrimitive -> e.content
                is JsonObject -> listOfNotNull(e.str("server") ?: e.str("name"), e.str("message") ?: e.str("error")).joinToString(": ")
                else -> e.toString()
            }
        }
        return McpReport(servers, errors, json.str("note"))
    }

    /**
     * `pi mcp add` arguments for a server at [target]: an http(s) URL is a streamable HTTP server, anything else a
     * command line for a stdio server (split on spaces; quotes group words). Null when [name] or [target] is unusable.
     */
    fun addArgs(name: String, target: String, bearerEnv: String? = null): List<String>? {
        if (!validName(name)) return null
        val t = target.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("https://") || t.startsWith("http://")) {
            return buildList {
                addAll(listOf("mcp", "add", name, "--url", t))
                bearerEnv?.trim()?.takeIf { it.isNotEmpty() }?.let { addAll(listOf("--bearer-token-env-var", it)) }
            }
        }
        val words = splitCommand(t).takeIf { it.isNotEmpty() } ?: return null
        return listOf("mcp", "add", name, "--") + words
    }

    fun splitCommand(text: String): List<String> {
        val out = ArrayList<String>()
        val word = StringBuilder()
        var quote: Char? = null
        var any = false
        for (c in text) {
            when {
                quote != null && c == quote -> quote = null
                quote != null -> word.append(c)
                c == '"' || c == '\'' -> {
                    quote = c
                    any = true
                }
                c.isWhitespace() -> {
                    if (word.isNotEmpty() || any) out += word.toString()
                    word.clear()
                    any = false
                }
                else -> word.append(c)
            }
        }
        if (word.isNotEmpty() || any) out += word.toString()
        return out
    }
}
