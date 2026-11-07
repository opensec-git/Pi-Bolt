package opensec.pibolt.app.ui.diagram

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import android.util.LruCache
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import androidx.compose.runtime.Immutable
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import opensec.pibolt.app.BuildConfig
import opensec.pibolt.app.R
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A drawn diagram: [bitmap] holds it at [scale] bitmap pixels per diagram (CSS) pixel. */
@Immutable
class DiagramImage(val bitmap: Bitmap, val width: Float, val height: Float, val scale: Float, val kind: String, val label: String)

@Immutable
sealed interface DiagramResult {
    class Drawn(val image: DiagramImage) : DiagramResult

    /** [transient]: the renderer failed, not the diagram; not cached, so the next request tries again. */
    class Failed(val message: String, val transient: Boolean = false) : DiagramResult
}

/**
 * How large to draw: [scale] bitmap pixels per diagram pixel at most, shrunk to fit [maxWidth] x [maxHeight] (0 = no
 * limit) and [maxPixels] in total.
 */
data class RenderSize(val scale: Float, val maxWidth: Int, val maxHeight: Int, val maxPixels: Int) {
    val json: String get() = JsonText.obj(mapOf("scale" to scale, "maxWidth" to maxWidth, "maxHeight" to maxHeight, "maxPixels" to maxPixels))
}

/**
 * Draws Mermaid diagrams into bitmaps with one shared offscreen WebView, created on first use and never attached to a
 * window. Requests run one at a time. The WebView is locked down (see DESIGN.md, "Diagrams"): JavaScript only for the
 * renderer page served from the APK by [WebViewAssetLoader], every other request answered 403 without touching the
 * network, network loads blocked, no file or content access, no navigation, and one bridge method that takes back a
 * PNG and its size. Results are cached by source, theme and size ([DiagramCache]); a render that is still running when
 * its block leaves the screen finishes into the cache.
 */
// The WebView holds the application context only, so keeping it in this object leaks no activity.
@SuppressLint("StaticFieldLeak")
object MermaidRenderer {
    private const val TAG = "PiBoltDiagram"
    private const val PAGE = "https://${WebViewAssetLoader.DEFAULT_DOMAIN}/diagram/index.html"
    private const val TIMEOUT_MS = 20_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val ids = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<DiagramResult>>()
    private val inFlight = HashMap<String, Deferred<DiagramResult>>()

    @SuppressLint("StaticFieldLeak")
    private var webView: WebView? = null
    private var loaded: CompletableDeferred<Boolean>? = null

    /** The result for [key], from the cache, from a render already running, or from a new render. Main thread. */
    @MainThread
    fun request(context: Context, key: String, code: String, theme: MermaidTheme, size: RenderSize, cache: Boolean = true): Deferred<DiagramResult> {
        DiagramCache.get(key)?.let { return CompletableDeferred(it) }
        inFlight[key]?.let { return it }
        val app = context.applicationContext
        val job = scope.async {
            val result = mutex.withLock { renderLocked(app, code, theme, size) }
            if (cache) DiagramCache.put(key, result)
            inFlight.remove(key)
            result
        }
        inFlight[key] = job
        return job
    }

    private suspend fun renderLocked(context: Context, code: String, theme: MermaidTheme, size: RenderSize): DiagramResult {
        val view = ensureView(context)
        val pageOk = withTimeoutOrNull(TIMEOUT_MS) { loaded?.await() } ?: false
        if (!pageOk) {
            destroyView()
            return DiagramResult.Failed("The diagram renderer did not start", transient = true)
        }
        val id = ids.incrementAndGet()
        val started = System.nanoTime()
        val result = CompletableDeferred<DiagramResult>()
        pending[id] = result
        try {
            // Every argument is a JSON literal: the source is data to the page, never code.
            view.evaluateJavascript("piRender($id,${JsonText.quote(code)},${theme.json},${size.json});", null)
            val outcome = withTimeoutOrNull(TIMEOUT_MS) { result.await() } ?: DiagramResult.Failed("Drawing the diagram took too long", transient = true)
            val ms = (System.nanoTime() - started) / 1_000_000
            when (outcome) {
                is DiagramResult.Drawn -> Log.d(TAG, "drew #$id ${outcome.image.kind} ${outcome.image.bitmap.width}x${outcome.image.bitmap.height} in $ms ms")
                is DiagramResult.Failed -> Log.d(TAG, "failed #$id in $ms ms: ${outcome.message.take(120)}")
            }
            return outcome
        } finally {
            pending.remove(id)
        }
    }

    /** Called by [DiagramBridge] on the WebView's bridge thread. */
    internal fun onResult(id: Int, width: Float, height: Float, kind: String, label: String, png: String, error: String) {
        val target = pending[id] ?: return
        if (error.isNotEmpty() || png.isEmpty()) {
            target.complete(DiagramResult.Failed(error.ifEmpty { "Mermaid drew nothing" }))
            return
        }
        target.complete(decode(width, height, kind, label, png))
    }

    private fun decode(width: Float, height: Float, kind: String, label: String, png: String): DiagramResult {
        if (png.length > MAX_PNG_CHARS || !(width > 0f && height > 0f)) return DiagramResult.Failed("The diagram is too large")
        val bytes = runCatching { Base64.decode(png, Base64.DEFAULT) }.getOrNull() ?: return DiagramResult.Failed("The diagram image was damaged")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > MAX_BITMAP_PIXELS) {
            return DiagramResult.Failed("The diagram is too large")
        }
        // Hardware bitmaps keep the pixels in graphics memory, off the Java heap; they are only drawn, never edited.
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.HARDWARE }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return DiagramResult.Failed("The diagram image was damaged")
        return DiagramResult.Drawn(DiagramImage(bitmap, width, height, bitmap.width / width, kind, label.take(200)))
    }

    @SuppressLint("SetJavaScriptEnabled")
    @MainThread
    private fun ensureView(context: Context): WebView {
        webView?.let { return it }
        val loader = WebViewAssetLoader.Builder()
            .setDomain(WebViewAssetLoader.DEFAULT_DOMAIN)
            .addPathHandler("/diagram/", DiagramFiles(context))
            .build()
        val view = WebView(context)
        view.settings.apply {
            // Only for the renderer page, which is the only page this WebView ever loads.
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            blockNetworkImage = true
            domStorageEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            setSupportZoom(false)
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            safeBrowsingEnabled = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
        }
        view.isFocusable = false
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        view.webViewClient = Client(loader)
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
            view.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    Log.d(TAG, "console ${message.messageLevel()}: ${message.message().take(300)} (${message.sourceId().substringAfterLast('/')}:${message.lineNumber()})")
                    return true
                }
            }
        }
        view.addJavascriptInterface(DiagramBridge(), DiagramBridge.NAME)
        val done = CompletableDeferred<Boolean>()
        loaded = done
        webView = view
        view.loadUrl(PAGE)
        return view
    }

    @MainThread
    private fun destroyView() {
        val view = webView ?: return
        webView = null
        loaded = null
        for (p in pending.values) p.complete(DiagramResult.Failed("The diagram renderer stopped", transient = true))
        view.removeJavascriptInterface(DiagramBridge.NAME)
        view.destroy()
    }

    private class Client(private val loader: WebViewAssetLoader) : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
            val url = request.url
            if (url.scheme == "https" && url.host == WebViewAssetLoader.DEFAULT_DOMAIN) {
                loader.shouldInterceptRequest(url)?.let { return it }
            }
            // Nothing leaves the app: anything not served from the APK is refused here, before the network stack.
            Log.w(TAG, "Blocked request: ${url.scheme}://${url.host}${url.path?.take(80).orEmpty()}")
            return blocked()
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

        override fun onPageFinished(view: WebView, url: String) {
            Log.d(TAG, "page loaded")
            if (view === webView && url == PAGE) loaded?.complete(true)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
            if (view === webView && request.isForMainFrame) loaded?.complete(false)
        }

        /** The renderer process died (killed for memory, or crashed): drop this WebView, never the app. */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w(TAG, "Diagram renderer gone (crash=${detail.didCrash()})")
            if (view === webView) {
                destroyView()
            } else {
                view.destroy()
            }
            return true
        }
    }

    private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    /** The four files the renderer page may load; everything else under /diagram/ is refused. */
    private class DiagramFiles(context: Context) : WebViewAssetLoader.PathHandler {
        private val app = context.applicationContext

        override fun handle(path: String): WebResourceResponse? {
            val (mime, open) = when (path) {
                "index.html" -> "text/html" to { app.assets.open("diagram/index.html") }
                "render.js" -> "text/javascript" to { app.assets.open("diagram/render.js") }
                "mermaid.min.js" -> "text/javascript" to { app.assets.open("diagram/mermaid.min.js") }
                "inter.ttf" -> "font/ttf" to ::openFont
                else -> return null
            }
            val stream: InputStream = runCatching { open() }.getOrNull() ?: return null
            return WebResourceResponse(mime, "utf-8", stream)
        }

        /** A font resource is a plain file in the APK, readable like a raw one. */
        @SuppressLint("ResourceType")
        private fun openFont(): InputStream = app.resources.openRawResource(R.font.inter_regular)
    }

    private const val MAX_PNG_CHARS = 64 * 1024 * 1024
    private const val MAX_BITMAP_PIXELS = 24L * 1024 * 1024
}

/**
 * The page's only way back to the app: one method with typed arguments. A result for an id the app is not waiting for
 * is ignored.
 */
internal class DiagramBridge {
    @JavascriptInterface
    fun result(id: Int, width: Float, height: Float, kind: String?, label: String?, png: String?, error: String?) {
        MermaidRenderer.onResult(id, width, height, kind.orEmpty(), label.orEmpty(), png.orEmpty(), error.orEmpty())
    }

    companion object {
        const val NAME = "PiBoltDiagram"
    }
}

/**
 * Drawn diagrams by key, bounded by bitmap bytes; failures by key (small and cheap), so a broken diagram scrolled back
 * into view is not drawn again.
 */
object DiagramCache {
    private val drawn = object : LruCache<String, DiagramResult.Drawn>(budgetBytes()) {
        override fun sizeOf(key: String, value: DiagramResult.Drawn): Int = value.image.bitmap.allocationByteCount
    }
    private val failed = object : LinkedHashMap<String, DiagramResult.Failed>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DiagramResult.Failed>?): Boolean = size > 64
    }

    fun get(key: String): DiagramResult? = drawn.get(key) ?: synchronized(failed) { failed[key] }

    fun put(key: String, result: DiagramResult) {
        when (result) {
            is DiagramResult.Drawn -> drawn.put(key, result)
            is DiagramResult.Failed -> if (!result.transient) synchronized(failed) { failed[key] = result }
        }
    }

    private fun budgetBytes(): Int {
        val heapEighth = Runtime.getRuntime().maxMemory() / 8
        return minOf(heapEighth, 48L * 1024 * 1024).toInt().coerceAtLeast(8 * 1024 * 1024)
    }
}
