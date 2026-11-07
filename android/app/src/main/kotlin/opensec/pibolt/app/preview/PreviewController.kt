package opensec.pibolt.app.preview

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.bool
import opensec.pibolt.protocol.int
import opensec.pibolt.protocol.str
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * The in-app preview's one WebView, shared by the user's preview screen and Pi's preview tools (PreviewBroker), so Pi
 * sees what the user sees and the user can watch what Pi does.
 *
 * Where the WebView lives: in the preview screen while it shows ([show], [hide]); otherwise, while Pi used it in the
 * last [KEEP_MS], parked behind the activity's content (attached, laid out at the window's size, alpha 0, out of
 * touch and accessibility) so the page keeps rendering; with no activity at all, detached and laid out by hand. It is
 * destroyed after [KEEP_MS] unused off screen.
 *
 * Safety: plain http on loopback only, as before; Pi may only open a port detected as a dev server ([scanPorts]); every
 * script runs in the page's main frame after a check that the page is still on loopback; navigations elsewhere are
 * blocked (a link the user taps opens in the phone's browser). [driving] names the project while Pi uses the preview,
 * for the "Pi is using the preview" indicator.
 *
 * Everything touching the WebView runs on the main thread.
 */
class PreviewController(
    private val app: Application,
    private val scanPorts: (Collection<Int>) -> List<Int>,
) {
    data class Page(
        val url: String? = null,
        val loading: Boolean = false,
        val progress: Int = 0,
        val canGoBack: Boolean = false,
        val failed: String? = null,
        val title: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val console = ConsoleBuffer()

    private val _page = MutableStateFlow(Page())
    val page: StateFlow<Page> = _page.asStateFlow()

    /** Pi is using the preview: for [project]'s session, on [port] (the page's, when it is a dev server's). */
    data class Driving(val project: String, val port: Int?)

    private val _driving = MutableStateFlow<Driving?>(null)

    /** Set while Pi uses the preview (and for a few seconds after), else null. */
    val driving: StateFlow<Driving?> = _driving.asStateFlow()

    private val _gone = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The page's renderer crashed or was killed: the WebView is gone (the screen closes). */
    val gone: SharedFlow<Unit> = _gone.asSharedFlow()

    private var web: WebView? = null
    private val context = MutableContextWrapper(app)
    private var onScreen = false
    private var parking: FrameLayout? = null
    private var activity: WeakReference<Activity>? = null
    private var startedActivities = 0
    private var lastUsed = 0L
    private var idleJob: Job? = null
    private var drivingJob: Job? = null

    init {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(a: Activity, saved: Bundle?) {
                activity = WeakReference(a)
            }

            override fun onActivityStarted(a: Activity) {
                activity = WeakReference(a)
                startedActivities++
            }

            override fun onActivityStopped(a: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
            }

            override fun onActivityDestroyed(a: Activity) {
                if (activity?.get() !== a) return
                // Never keep a destroyed activity: the parked WebView leaves its window and its context.
                unpark()
                context.baseContext = app
                activity = null
            }

            override fun onActivityResumed(a: Activity) = Unit
            override fun onActivityPaused(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, out: Bundle) = Unit
        })
    }

    // The preview screen.

    /**
     * The WebView for the preview screen of [port], detached from wherever it was parked: the page Pi has open stays
     * when it is on that port, else localhost:[port] loads.
     */
    fun show(port: Int): WebView {
        val view = ensureWeb()
        unpark()
        (view.parent as? ViewGroup)?.removeView(view)
        activity?.get()?.let { context.baseContext = it }
        onScreen = true
        idleJob?.cancel()
        if (PreviewOrigins.portOf(view.url) != port || _page.value.failed != null) load(view, "http://localhost:$port/")
        return view
    }

    /** The preview screen left: the WebView is parked while Pi may still want it, else destroyed. */
    fun hide() {
        onScreen = false
        val view = web ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        if (System.currentTimeMillis() - lastUsed < KEEP_MS) {
            park(view)
            scheduleIdle()
        } else {
            destroy()
        }
    }

    fun reload() {
        web?.let { _page.value = _page.value.copy(loading = true, failed = null); it.reload() }
    }

    fun goBack() {
        web?.takeIf { it.canGoBack() }?.goBack()
    }

    // Pi's tools.

    /** Runs one tool request. Called off the main thread (the socket's); switches to it. */
    suspend fun handle(request: PreviewRequest): PreviewReply = withContext(Dispatchers.Main.immediate) {
        startDriving(File(request.cwd).name)
        try {
            withTimeout(OP_TIMEOUT_MS) {
                when (val c = request.command) {
                    is PreviewCommand.Open -> open(c.url, c.reload)
                    PreviewCommand.Screenshot -> screenshot()
                    is PreviewCommand.Console -> {
                        val snapshot = console.since(c.since, c.errorsOnly)
                        PreviewReply.text(ConsoleBuffer.format(snapshot), buildJsonObject { put("next", snapshot.next) })
                    }
                    is PreviewCommand.Click -> click(c)
                    is PreviewCommand.Type -> onPage { view -> act(view, PreviewScripts.type(c.selector, c.text, c.submit)) { r -> "Typed into ${r.str("target")}" + if (r.bool("submitted") == true) " and submitted its form." else "." } }
                    is PreviewCommand.Scroll -> onPage { view ->
                        act(view, PreviewScripts.scroll(c.selector, c.dy, c.to)) { r ->
                            "Scrolled${r.str("target")?.let { " to $it" }.orEmpty()}: at ${r.int("scrollY")} of ${r.int("scrollHeight")} px (viewport ${r.int("viewport")} px)."
                        }
                    }
                    is PreviewCommand.Eval -> onPage { view ->
                        val r = js(view, PreviewScripts.eval(c.js)) ?: return@onPage PreviewReply.error("The page did not answer")
                        if (r.bool("ok") != true) return@onPage PreviewReply.error(r.str("error") ?: "The expression failed")
                        val value = r.str("value").orEmpty()
                        PreviewReply.text(if (value.length > MAX_EVAL_CHARS) value.take(MAX_EVAL_CHARS) + "… (${value.length} characters)" else value)
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            PreviewReply.error("The preview did not respond in time")
        } finally {
            lastUsed = System.currentTimeMillis()
            stopDriving()
            if (!onScreen) scheduleIdle()
        }
    }

    private suspend fun open(target: String?, reload: Boolean): PreviewReply {
        val current = web?.url?.takeIf { PreviewOrigins.isLoopbackHttp(it) && _page.value.failed == null }
        val probe = listOfNotNull(PreviewOrigins.portOf(target), PreviewOrigins.portOf(current))
        val ports = (withContext(Dispatchers.IO) { scanPorts(probe) } + listOfNotNull(PreviewOrigins.portOf(current))).distinct()
        val url = when (val t = PreviewOrigins.resolve(target, current, ports)) {
            is PreviewOrigins.Target.Refused -> return PreviewReply.error(t.message)
            is PreviewOrigins.Target.Url -> t.url
        }
        val view = ensureWeb()
        placeOffScreen(view)
        val mark = console.cursor()
        if (url != view.url || reload || _page.value.failed != null) load(view, url, reload = url == view.url)
        awaitLoaded()
        return pageReply(view, if (url == current && !reload) "The preview already shows" else "Opened", mark)
    }

    private suspend fun screenshot(): PreviewReply {
        // Nothing open, or a page that did not load (the dev server may be up by now): open it first.
        if (!PreviewOrigins.isLoopbackHttp(web?.url) || _page.value.failed != null) {
            val opened = open(null, reload = false)
            if (!opened.ok) return opened
        }
        val view = web ?: return PreviewReply.error("The preview is not open")
        _page.value.failed?.let { return PreviewReply.error(failedText(view.url, it)) }
        placeOffScreen(view)
        awaitLoaded()
        settle(view)
        if (view.width <= 0 || view.height <= 0) return PreviewReply.error("The preview has no size yet; try again")
        val full = createBitmap(view.width, view.height)
        view.draw(Canvas(full))
        val viewW = view.width
        val viewH = view.height
        val url = view.url
        val (png, blank, size) = withContext(Dispatchers.Default) {
            val fit = ScreenshotScale.fit(full.width, full.height)
            val shot = if (fit.width == full.width) full else full.scale(fit.width, fit.height)
            val pixels = IntArray(shot.width * shot.height).also { shot.getPixels(it, 0, shot.width, 0, 0, shot.width, shot.height) }
            val out = ByteArrayOutputStream()
            shot.compress(Bitmap.CompressFormat.PNG, 100, out)
            if (shot !== full) shot.recycle()
            full.recycle()
            Triple(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), ScreenshotScale.looksBlank(pixels), fit)
        }
        val notes = buildList {
            if (blank) add("The page looks blank: check preview_console for errors.")
            if (!onScreen && startedActivities == 0) add("Pi-Bolt is in the background, so the picture may lag behind the page.")
        }
        val text = "Screenshot of $url (${size.width}×${size.height} px; the preview is ${viewW}×$viewH device px)." +
            notes.joinToString("") { "\n$it" }
        return PreviewReply(
            ok = true,
            text = text,
            image = png,
            details = buildJsonObject {
                put("url", url.orEmpty())
                put("width", size.width)
                put("height", size.height)
            },
        )
    }

    private suspend fun click(c: PreviewCommand.Click): PreviewReply = onPage { view ->
        val script = if (c.selector != null) {
            PreviewScripts.clickSelector(c.selector)
        } else {
            val shot = ScreenshotScale.fit(view.width, view.height)
            val (fx, fy) = ScreenshotScale.fraction(c.x ?: 0.0, c.y ?: 0.0, view.width, view.height)
                ?: return@onPage PreviewReply.error("(${c.x}, ${c.y}) is outside the screenshot (${shot.width}×${shot.height} px)")
            PreviewScripts.clickAt(fx, fy)
        }
        act(view, script) { r -> "Clicked ${r.str("target")}." }
    }

    /** Runs [script] (an action), waits for what it started (a navigation), and describes the result with [say]. */
    private suspend fun act(view: WebView, script: String, say: (JsonObject) -> String): PreviewReply {
        val mark = console.cursor()
        val r = js(view, script) ?: return PreviewReply.error("The page did not answer")
        if (r.bool("ok") != true) return PreviewReply.error(r.str("error") ?: "That did not work")
        delay(ACTION_SETTLE_MS)
        awaitLoaded()
        val after = web ?: return PreviewReply.error("The preview closed")
        return PreviewReply.text(say(r) + navigationNote(after) + errorsNote(mark))
    }

    /** [block] with the open page, after checking it is a loopback page that loaded. */
    private suspend fun onPage(block: suspend (WebView) -> PreviewReply): PreviewReply {
        val view = web
        if (view == null || !PreviewOrigins.isLoopbackHttp(view.url)) return PreviewReply.error("The preview is not open: use preview_open first")
        _page.value.failed?.let { return PreviewReply.error(failedText(view.url, it)) }
        placeOffScreen(view)
        awaitLoaded()
        if (!PreviewOrigins.isLoopbackHttp(view.url)) return PreviewReply.error("The preview left the dev server")
        return block(view)
    }

    private suspend fun pageReply(view: WebView, verb: String, mark: Long): PreviewReply {
        _page.value.failed?.let { return PreviewReply.error(failedText(view.url, it)) }
        val info = js(view, PreviewScripts.PAGE_INFO)
        val title = info?.str("title")?.takeIf { it.isNotBlank() }?.let { " “$it”" }.orEmpty()
        val viewport = info?.let { " (viewport ${it.int("width")}×${it.int("height")} CSS px)" }.orEmpty()
        return PreviewReply.text("$verb ${view.url}$title$viewport." + errorsNote(mark), buildJsonObject { put("url", view.url.orEmpty()) })
    }

    private fun navigationNote(view: WebView): String = view.url?.let { " The page is now $it." }.orEmpty()

    private fun errorsNote(mark: Long): String {
        val errors = console.errorsSince(mark)
        return if (errors == 0) "" else " $errors new console error${if (errors == 1) "" else "s"}: see preview_console."
    }

    private fun failedText(url: String?, reason: String): String {
        val port = PreviewOrigins.portOf(url)
        return "Nothing answers on ${port?.let { "localhost:$it" } ?: url}${if (reason.isNotBlank()) " ($reason)" else ""}. Is the dev server running?"
    }

    // The WebView.

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWeb(): WebView {
        web?.let { return it }
        val view = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setGeolocationEnabled(false)
            webChromeClient = Chrome()
            webViewClient = Client()
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // The hook checks the page's host itself; navigations never leave loopback anyway.
            runCatching { WebViewCompat.addDocumentStartJavaScript(view, PreviewScripts.ERROR_HOOK, setOf("*")) }
        }
        web = view
        return view
    }

    private fun load(view: WebView, url: String, reload: Boolean = false) {
        _page.value = _page.value.copy(url = url, loading = true, progress = 0, failed = null)
        if (reload) view.reload() else view.loadUrl(url)
    }

    private suspend fun awaitLoaded() {
        withTimeoutOrNull(LOAD_TIMEOUT_MS) { _page.first { !it.loading } }
    }

    /** Waits until the page's current state is drawn (WebView's visual-state callback), at most [SETTLE_MS]. */
    private suspend fun settle(view: WebView) {
        withTimeoutOrNull(SETTLE_MS) {
            suspendCancellableCoroutine { c -> view.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (c.isActive) c.resume(Unit)
                }
            }) }
        }
    }

    /** Off the preview screen, the WebView is parked in the activity, or laid out by hand without one. */
    private fun placeOffScreen(view: WebView) {
        if (onScreen) return
        if (view.parent == null) park(view)
        if (!view.isAttachedToWindow && (view.width == 0 || view.height == 0)) {
            val metrics = app.resources.displayMetrics
            view.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
    }

    private fun park(view: WebView) {
        val a = activity?.get()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: return
        val content = a.findViewById<ViewGroup>(android.R.id.content) ?: return
        val host = parking?.takeIf { it.parent === content } ?: FrameLayout(a).apply {
            // Behind the app's content and fully transparent: it renders for screenshots but is never seen or touched.
            alpha = 0f
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            content.addView(this, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            parking = this
        }
        context.baseContext = a
        (view.parent as? ViewGroup)?.takeIf { it !== host }?.removeView(view)
        if (view.parent == null) host.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun unpark() {
        val host = parking ?: return
        host.removeAllViews()
        (host.parent as? ViewGroup)?.removeView(host)
        parking = null
    }

    private fun scheduleIdle() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(KEEP_MS)
            if (!onScreen) destroy()
        }
    }

    private fun destroy() {
        val view = web ?: return
        web = null
        unpark()
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.destroy()
        _page.value = Page()
    }

    private fun startDriving(project: String) {
        drivingJob?.cancel()
        _driving.value = Driving(project, PreviewOrigins.portOf(web?.url))
    }

    private fun stopDriving() {
        drivingJob?.cancel()
        _driving.value = _driving.value?.copy(port = PreviewOrigins.portOf(web?.url))
        drivingJob = scope.launch {
            delay(DRIVING_LINGER_MS)
            _driving.value = null
        }
    }

    private suspend fun js(view: WebView, script: String): JsonObject? = withTimeoutOrNull(JS_TIMEOUT_MS) {
        suspendCancellableCoroutine { c ->
            view.evaluateJavascript(script) { result ->
                if (c.isActive) c.resume(runCatching { ProtocolJson.parseToJsonElement(result ?: "null") as? JsonObject }.getOrNull())
            }
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (view === web) _page.value = _page.value.copy(progress = newProgress)
        }

        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            val level = when (message.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> ConsoleBuffer.Level.Error
                ConsoleMessage.MessageLevel.WARNING -> ConsoleBuffer.Level.Warn
                ConsoleMessage.MessageLevel.DEBUG -> ConsoleBuffer.Level.Debug
                ConsoleMessage.MessageLevel.TIP -> ConsoleBuffer.Level.Info
                else -> ConsoleBuffer.Level.Log
            }
            console.add(level, message.message().orEmpty(), message.sourceId()?.takeIf { it.isNotBlank() }, message.lineNumber().takeIf { it > 0 })
            // Handled: the page's messages stay out of logcat.
            return true
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val target = request.url.toString()
            if (PreviewOrigins.isLoopbackHttp(target)) return false
            if (request.hasGesture() && request.isForMainFrame && onScreen) {
                openOutside(target)
            } else {
                console.add(ConsoleBuffer.Level.Page, "blocked a navigation to $target (the preview stays on localhost)")
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (view !== web) return
            _page.value = _page.value.copy(url = url, loading = true, failed = null)
            url?.let { console.add(ConsoleBuffer.Level.Page, "loading $it") }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (view !== web) return
            _page.value = _page.value.copy(url = url ?: _page.value.url, loading = false, canGoBack = view.canGoBack(), title = view.title)
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) && PreviewOrigins.isLoopbackHttp(url)) {
                view.evaluateJavascript(PreviewScripts.ERROR_HOOK, null)
            }
        }

        // A single-page app moves with history.pushState (no page load): Back and the address follow it too, so Back
        // steps back inside the app instead of closing the preview.
        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (view !== web) return
            _page.value = _page.value.copy(url = url ?: _page.value.url, canGoBack = view.canGoBack())
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (view !== web) return
            val reason = error.description?.toString().orEmpty()
            if (request.isForMainFrame) {
                _page.value = _page.value.copy(failed = reason)
                console.add(ConsoleBuffer.Level.Network, "failed to load the page ${request.url}: $reason")
            } else {
                console.add(ConsoleBuffer.Level.Network, "failed: ${request.method} ${request.url}: $reason")
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
            if (view !== web || request.url.path == "/favicon.ico") return
            console.add(ConsoleBuffer.Level.Network, "${response.statusCode} ${request.method} ${request.url}")
        }

        // A dev server's page that crashes or exhausts the renderer must not take the app (its sessions and terminals)
        // down with it: the WebView goes, the screen closes, and Pi's next call reopens it.
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (view === web) {
                console.add(ConsoleBuffer.Level.Page, "the page's renderer ${if (detail.didCrash()) "crashed" else "was stopped by the system"}")
                destroy()
                _gone.tryEmit(Unit)
            } else {
                runCatching { view.destroy() }
            }
            return true
        }
    }

    private fun openOutside(url: String) {
        val launcher: Context = activity?.get() ?: app
        try {
            launcher.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // No browser installed: nothing to open.
        }
    }

    companion object {
        /** How long the WebView stays off screen after Pi's last call. */
        const val KEEP_MS = 5 * 60_000L
        private const val OP_TIMEOUT_MS = 40_000L
        private const val LOAD_TIMEOUT_MS = 20_000L
        private const val JS_TIMEOUT_MS = 10_000L
        private const val SETTLE_MS = 1_500L
        private const val ACTION_SETTLE_MS = 350L
        private const val DRIVING_LINGER_MS = 4_000L
        private const val MAX_EVAL_CHARS = 20_000
    }
}
