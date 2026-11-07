package opensec.pibolt.app.ui.session.web

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.core.graphics.scale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Site icons for the Sources pill ([FaviconRules] decides which and from where). In memory (64 icons) and on disk
 * (`cacheDir/favicons`, [FaviconRules.DISK_LIMIT_BYTES], least recently used out first), so a page's icon is fetched
 * once; a host without one is not asked again for [FaviconRules.MISS_TTL_MS]. All I/O on [Dispatchers.IO], four hosts
 * at a time, one fetch per host however many rows ask. No cookies, no referrer, https only.
 */
object Favicons {
    private val memory = LruCache<String, ImageBitmap>(64)
    private val misses = ConcurrentHashMap<String, Long>()
    private val inflight = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()
    private val gate = Semaphore(4)

    /** Fetches outlive the row that asked: a row scrolled away does not cancel (and so waste) a fetch under way. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The icon cached in memory for [host], if any (no I/O: safe in composition). */
    fun cached(host: String): ImageBitmap? = memory.get(host)

    /** [host]'s icon, or null when it has none (the globe glyph shows). */
    suspend fun load(context: Context, host: String): ImageBitmap? {
        memory.get(host)?.let { return it }
        if ((misses[host] ?: 0L) > System.currentTimeMillis()) return null
        val cacheDir = context.cacheDir
        val job = inflight.computeIfAbsent(host) {
            scope.async(start = CoroutineStart.LAZY) {
                val icon = gate.withPermit { runCatching { resolve(cacheDir, host) }.getOrNull() }
                if (icon != null) memory.put(host, icon) else misses[host] = System.currentTimeMillis() + FaviconRules.MISS_TTL_MS
                inflight.remove(host)
                icon
            }
        }
        job.start()
        return job.await()
    }

    private fun resolve(cacheDir: File, host: String): ImageBitmap? {
        val dir = File(cacheDir, "favicons").apply { mkdirs() }
        val key = FaviconRules.cacheKey(host)
        val png = File(dir, "$key.png")
        val none = File(dir, "$key.none")
        if (png.isFile) {
            png.setLastModified(System.currentTimeMillis())
            BitmapFactory.decodeFile(png.path)?.let { return it.asImageBitmap() }
            png.delete()
        }
        if (none.isFile && System.currentTimeMillis() - none.lastModified() < FaviconRules.MISS_TTL_MS) return null
        val bitmap = fetchIcon(host)
        if (bitmap == null) {
            runCatching { none.writeText("") }
            return null
        }
        none.delete()
        runCatching {
            val temp = File(dir, "$key.tmp")
            temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            temp.renameTo(png)
            trim(dir)
        }
        return bitmap.asImageBitmap()
    }

    private fun fetchIcon(host: String): Bitmap? {
        get(FaviconRules.iconUrl(host), FaviconRules.MAX_ICON_BYTES)?.let { bytes -> decode(bytes)?.let { return it } }
        val page = FaviconRules.homeUrl(host)
        val html = get(page, FaviconRules.MAX_HTML_BYTES, html = true) ?: return null
        for (link in FaviconRules.linkIcons(String(html, Charsets.UTF_8), page).take(2)) {
            if (FaviconRules.iconHost(link) == null) continue
            get(link, FaviconRules.MAX_ICON_BYTES)?.let { bytes -> decode(bytes)?.let { return it } }
        }
        return null
    }

    /** The body of [url] (https, redirects only to https), at most [limit] bytes (a page: its first [limit]). */
    private fun get(url: String, limit: Int, html: Boolean = false): ByteArray? {
        var target = url
        repeat(FaviconRules.MAX_REDIRECTS + 1) {
            val connection = (URL(target).openConnection() as? HttpURLConnection) ?: return null
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 4_000
                connection.readTimeout = 6_000
                connection.setRequestProperty("Accept", if (html) "text/html" else "image/*")
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) Pi-Bolt")
                val code = connection.responseCode
                if (code in 300..399) {
                    target = FaviconRules.redirectTarget(target, connection.getHeaderField("Location")) ?: return null
                    return@repeat
                }
                if (code != 200) return null
                if (!html && connection.contentLengthLong > limit) return null
                return connection.inputStream.use { readAtMost(it, limit, truncate = html) }
            } catch (e: Exception) {
                return null
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private fun readAtMost(input: InputStream, limit: Int, truncate: Boolean): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > limit) return if (truncate) out.toByteArray().copyOf(limit) else null
        }
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        if (!FaviconRules.looksLikeImage(bytes)) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= FaviconRules.ICON_PX && bounds.outHeight / (sample * 2) >= FaviconRules.ICON_PX) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        if (decoded.width == FaviconRules.ICON_PX && decoded.height == FaviconRules.ICON_PX) return decoded
        return decoded.scale(FaviconRules.ICON_PX, FaviconRules.ICON_PX)
    }

    /** Keeps the disk cache under its limit: the least recently used icons go first. */
    private fun trim(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= FaviconRules.DISK_LIMIT_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= FaviconRules.DISK_LIMIT_BYTES * 3 / 4) break
            total -= file.length()
            file.delete()
        }
    }
}

/** [url]'s site icon once loaded (null meanwhile, and when it has none); nothing is fetched for a non-public host. */
@Composable
fun rememberFavicon(url: String): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val host = FaviconRules.iconHost(url)
    val icon by produceState(host?.let(Favicons::cached), host) {
        if (host != null && value == null) value = Favicons.load(context, host)
    }
    return icon
}
