package opensec.pibolt.app.ui.session.features

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.app.MainActivity
import opensec.pibolt.protocol.session.AssistantTextItem
import opensec.pibolt.protocol.session.ChatItem
import opensec.pibolt.protocol.session.NoticeItem
import opensec.pibolt.protocol.session.SubagentNoticeItem
import opensec.pibolt.protocol.session.ThinkingItem
import opensec.pibolt.protocol.session.ToolItem
import opensec.pibolt.protocol.session.ToolStatus
import opensec.pibolt.protocol.session.UserItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The session as a Markdown document: messages in order, tool calls with their output (long output cut). */
object SessionMarkdown {
    const val MAX_OUTPUT = 4_000

    fun render(items: List<ChatItem>, title: String, model: String?, at: Date = Date(), includeThinking: Boolean = false): String = buildString {
        append("# ").append(title.ifBlank { "Pi session" }).append("\n\n")
        val meta = listOfNotNull(model, SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(at)).joinToString(" · ")
        append("_").append(meta).append("_\n")
        var lastRole: String? = null
        for (item in items) {
            when (item) {
                is UserItem -> {
                    append("\n## User\n\n")
                    if (item.imageCount > 0) append("_[").append(item.imageCount).append(if (item.imageCount == 1) " image]_\n\n" else " images]_\n\n")
                    append(item.text.trim()).append('\n')
                    lastRole = "user"
                }
                is AssistantTextItem -> {
                    if (item.text.isBlank()) continue
                    if (lastRole != "pi") append("\n## Pi\n")
                    append('\n').append(item.text.trim()).append('\n')
                    lastRole = "pi"
                }
                is ThinkingItem -> if (includeThinking && item.text.isNotBlank()) {
                    if (lastRole != "pi") append("\n## Pi\n")
                    append('\n').append(item.text.trim().lines().joinToString("\n") { "> $it" }).append('\n')
                    lastRole = "pi"
                }
                is ToolItem -> {
                    if (lastRole != "pi") append("\n## Pi\n")
                    append("\n**").append(item.name).append("**")
                    toolArgument(item)?.let { append(" `").append(it.replace("`", "'")).append('`') }
                    if (item.status == ToolStatus.Error) append(" (failed)")
                    if (item.status == ToolStatus.Cancelled) append(" (cancelled)")
                    append('\n')
                    val output = (item.result ?: item.output).trimEnd()
                    if (output.isNotEmpty()) {
                        val cut = if (output.length > MAX_OUTPUT) output.take(MAX_OUTPUT) + "\n… (${output.length - MAX_OUTPUT} more characters)" else output
                        val fence = if ("```" in cut) "````" else "```"
                        append('\n').append(fence).append("text\n").append(cut).append('\n').append(fence).append('\n')
                    }
                    lastRole = "pi"
                }
                is SubagentNoticeItem -> {
                    append("\n> ").append(item.text.trim()).append('\n')
                    lastRole = null
                }
                is NoticeItem -> {
                    append("\n> **").append(item.title).append("**")
                    if (item.text.isNotBlank()) append("\n>\n").append(item.text.trim().lines().joinToString("\n") { "> $it" })
                    append('\n')
                    lastRole = null
                }
            }
        }
    }

    /** The one argument that says what a tool did: a command, a path or a pattern. */
    fun toolArgument(item: ToolItem): String? {
        val args = item.args ?: return null
        for (key in listOf("command", "path", "file_path", "pattern", "url", "query", "description")) {
            val value = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.lineSequence()?.firstOrNull()?.trim()
            if (!value.isNullOrEmpty()) return value.take(200)
        }
        return null
    }

    /** A file name from a session title: letters, digits, dashes; never empty. */
    fun fileName(title: String, extension: String): String {
        val base = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48).ifEmpty { "pi-session" }
        return "$base.$extension"
    }
}

/** Shares files through the Android share sheet: written to `cache/exports` and handed out by the FileProvider. */
object ShareFiles {
    fun exportsDir(context: Context): File = File(context.cacheDir, "exports").apply { mkdirs() }

    suspend fun write(context: Context, name: String, text: String): File = withContext(Dispatchers.IO) {
        File(exportsDir(context), name).apply { writeText(text) }
    }

    suspend fun copy(context: Context, source: File, name: String): File = withContext(Dispatchers.IO) {
        source.copyTo(File(exportsDir(context), name), overwrite = true)
    }

    fun share(context: Context, file: File, mime: String, subject: String? = null) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".exports", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        subject?.let { send.putExtra(Intent.EXTRA_SUBJECT, it) }
        send.clipData = ClipData.newRawUri(file.name, uri)
        // Pi-Bolt receives shares too: leave it out of its own chooser.
        val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java))))
    }

    fun shareText(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, null))
    }
}
