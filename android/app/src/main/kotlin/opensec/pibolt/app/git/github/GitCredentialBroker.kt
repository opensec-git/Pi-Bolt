package opensec.pibolt.app.git.github

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Hands the GitHub credential to git without putting the token in any environment. git's helper for
 * `https://github.com` asks the app over a unix socket ([socket], in a 0700 folder of the app); the app answers with
 * `username=`/`password=` lines from the sealed store. A project's scripts, which inherit the engine's environment,
 * then find only the socket path there, not the token.
 *
 * [listen] binds the socket and calls its handler once per connection (Android's LocalSocket in the app, a JDK unix
 * socket in tests); the handler returns false for a peer that is not this app's uid.
 */
class GitCredentialBroker(
    private val socket: File,
    private val credential: () -> Pair<String, String>?,
    private val listen: (File, (InputStream, OutputStream) -> Unit) -> Closeable,
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
            server = listen(socket) { input, output -> answer(input, output, credential()) }
            socket
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun close() {
        runCatching { server?.close() }
        server = null
        socket.delete()
    }

    companion object {
        private const val MAX_REQUEST = 8 * 1024

        /**
         * Answers one HTTP request from the helper's curl: the body is git's credential format, empty when signed out.
         * The request is read up to its blank line (bounded) and otherwise ignored.
         */
        fun answer(input: InputStream, output: OutputStream, credential: Pair<String, String>?) {
            var read = 0
            var tail = 0
            while (read < MAX_REQUEST) {
                val b = input.read()
                if (b < 0) break
                read++
                tail = (tail shl 8 or b) and 0xffffffff.toInt()
                if (tail == 0x0d0a0d0a) break
            }
            val body = credential?.let { (user, token) -> "username=$user\npassword=$token\n" }.orEmpty().toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            output.write(head.toByteArray(Charsets.US_ASCII))
            output.write(body)
            output.flush()
        }
    }
}
