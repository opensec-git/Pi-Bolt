package opensec.pibolt.app.data

import android.content.Context
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * One small app file, read and written on its own background thread so constructing a store at startup never
 * touches the disk on the main thread. [awaitLoaded] blocks only until the first read finished (a few ms after
 * launch); writes are queued in order (tmp file + rename).
 */
class BackgroundFile(context: Context, private val name: String, onLoad: (String?) -> Unit) {
    private val app = context.applicationContext
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "file-$name").apply { isDaemon = true } }
    private val loaded = CountDownLatch(1)
    private val file by lazy { File(app.filesDir, name) }

    init {
        io.execute {
            try {
                onLoad(runCatching { if (file.exists()) file.readText() else null }.getOrNull())
            } finally {
                loaded.countDown()
            }
        }
    }

    fun awaitLoaded() = loaded.await()

    fun write(text: String) {
        io.execute {
            runCatching {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) {
                    file.writeText(text)
                    tmp.delete()
                }
            }
        }
    }
}
