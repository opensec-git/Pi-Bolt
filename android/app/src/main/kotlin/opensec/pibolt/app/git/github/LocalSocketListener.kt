package opensec.pibolt.app.git.github

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * [GitCredentialBroker]'s listener on Android (and the preview tools' PreviewBroker): a filesystem unix socket at the
 * given path, one daemon thread ([name]) accepting connections one at a time. A peer that is not this app's uid is
 * dropped.
 */
object LocalSocketListener {
    private const val TAG = "PiBoltSocket"
    private const val READ_TIMEOUT_MS = 5_000

    fun listen(path: File, handle: (InputStream, OutputStream) -> Unit, name: String = "git-credentials"): Closeable {
        val bound = LocalSocket(LocalSocket.SOCKET_STREAM)
        bound.bind(LocalSocketAddress(path.path, LocalSocketAddress.Namespace.FILESYSTEM))
        val server = LocalServerSocket(bound.fileDescriptor)
        val uid = Process.myUid()
        val thread = Thread({
            while (true) {
                val peer = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                try {
                    peer.use {
                        if (it.peerCredentials.uid != uid) return@use
                        it.soTimeout = READ_TIMEOUT_MS
                        handle(it.inputStream, it.outputStream)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "$name request failed: ${e.javaClass.simpleName}")
                }
            }
        }, name)
        thread.isDaemon = true
        thread.start()
        return Closeable {
            runCatching { server.close() }
            runCatching { bound.close() }
            path.delete()
        }
    }
}
