package opensec.pibolt.app.preview

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The app's end of the preview tools: a unix socket ([socket], in a 0700 folder of the app) the engine's extension
 * (assets/extensions/pibolt-preview.js) connects to, its path in [SOCKET_VAR] of project engines only. One request
 * line per connection, answered with one line ([serve]). [listen] binds the socket and calls its handler per connection
 * (LocalSocketListener on Android, which drops peers of another uid; a JDK unix socket in tests).
 */
class PreviewBroker(
    private val socket: File,
    private val listen: (File, (InputStream, OutputStream) -> Unit) -> Closeable,
    private val handle: suspend (PreviewRequest) -> PreviewReply,
) {
    private var server: Closeable? = null

    /** The socket's path once it listens (started on first use); null when it could not be bound. */
    @Synchronized
    fun ensureListening(): File? {
        if (server != null) return socket
        return try {
            socket.parentFile?.let { dir ->
                dir.mkdirs()
                dir.setReadable(false, false)
                dir.setWritable(false, false)
                dir.setExecutable(false, false)
                dir.setReadable(true, true)
                dir.setWritable(true, true)
                dir.setExecutable(true, true)
            }
            socket.delete()
            server = listen(socket) { input, output -> serve(input, output, handle) }
            socket
        } catch (_: Exception) {
            null
        }
    }

    /** The variables a project engine gets: the socket's path, or none when it could not listen. */
    fun environment(): Map<String, String> = ensureListening()?.let { mapOf(SOCKET_VAR to it.path) }.orEmpty()

    @Synchronized
    fun close() {
        runCatching { server?.close() }
        server = null
        socket.delete()
    }

    companion object {
        const val SOCKET_VAR = "PIBOLT_PREVIEW_SOCKET"
        const val MAX_REQUEST = 256 * 1024

        /** Reads one request line (bounded), runs it, writes the answer line. A malformed request gets an error answer. */
        fun serve(input: InputStream, output: OutputStream, handle: suspend (PreviewRequest) -> PreviewReply) {
            val line = readLine(input, MAX_REQUEST)
            val reply = when {
                line == null -> PreviewReply.error("The request is too large or incomplete")
                else -> PreviewRequests.parse(line).fold(
                    onSuccess = { request -> runCatching { runBlocking { handle(request) } }.getOrElse { PreviewReply.error("The preview failed: ${it.message ?: it.javaClass.simpleName}") } },
                    onFailure = { PreviewReply.error("Invalid request: ${it.message}") },
                )
            }
            output.write((reply.toJson().toString() + "\n").toByteArray(Charsets.UTF_8))
            output.flush()
        }

        /** Bytes up to the first newline as UTF-8, or null past [max] bytes or at the end of the stream first. */
        fun readLine(input: InputStream, max: Int): String? {
            val buffer = ByteArrayOutputStream()
            while (buffer.size() < max) {
                val b = input.read()
                if (b < 0) return null
                if (b == '\n'.code) return buffer.toString(Charsets.UTF_8.name())
                buffer.write(b)
            }
            return null
        }
    }
}

/**
 * Installs the preview tools' extension (the asset [ASSET]) as `<agentDir>/extensions/[FILE]`, where Pi discovers
 * global extensions. Rewritten whenever the shipped text differs, so the tools always match this app's broker.
 */
object PreviewExtension {
    const val ASSET = "extensions/pibolt-preview.js"
    const val FILE = "pibolt-preview.js"

    /** Blocking; false when the asset cannot be read or the agent dir cannot be written. */
    fun install(agentDir: File, source: () -> InputStream): Boolean = runCatching {
        val shipped = source().use { it.readBytes() }
        val dir = File(agentDir, "extensions").apply { mkdirs() }
        val file = File(dir, FILE)
        if (file.isFile && file.length() == shipped.size.toLong() && file.readBytes().contentEquals(shipped)) return true
        val temp = File(dir, ".$FILE.${System.nanoTime()}.tmp")
        temp.writeBytes(shipped)
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        true
    }.getOrDefault(false)
}
