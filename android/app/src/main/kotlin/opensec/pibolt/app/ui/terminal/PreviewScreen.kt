package opensec.pibolt.app.ui.terminal

import opensec.pibolt.app.ui.projects.EmptyCard
import opensec.pibolt.app.ui.components.PrimaryButton
import androidx.compose.material.icons.rounded.CloudOff
import android.webkit.WebResourceError
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import opensec.pibolt.app.ui.components.GlassBar
import opensec.pibolt.app.ui.components.GlassIconButton
import opensec.pibolt.app.ui.components.glass
import opensec.pibolt.app.ui.theme.Space
import opensec.pibolt.app.ui.components.ProgressLine
import opensec.pibolt.app.ui.theme.Pi

/** Whether the preview may load [url]: plain http to this phone's loopback only (localhost or 127.0.0.1, any port). */
fun previewAllowed(url: String?): Boolean {
    val uri = runCatching { (url ?: return false).toUri() }.getOrNull() ?: return false
    return uri.scheme == "http" && (uri.host == "localhost" || uri.host == "127.0.0.1")
}

/**
 * The dev app in an in-app browser: a WebView restricted to http://localhost and 127.0.0.1, JavaScript on (dev apps
 * need it), no file or content access. Reload and "Open in browser" in the bar. A tapped link elsewhere opens in the
 * phone's browser; other navigations away from localhost are blocked.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PreviewScreen(port: Int, onBack: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val context = LocalContext.current
    val start = "http://localhost:$port/"
    var url by remember { mutableStateOf(start) }
    var loading by remember { mutableStateOf(true) }
    // The page's load progress from WebView (0 to 100), for a determinate line: never an indeterminate animation.
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    // The dev server did not answer (stopped, still starting, wrong port): our own card instead of WebView's page.
    var failed by remember { mutableStateOf<String?>(null) }
    val web = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setGeolocationEnabled(false)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progress = newProgress
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url.toString()
                    if (previewAllowed(target)) return false
                    if (request.hasGesture() && request.isForMainFrame) openOutside(context, target)
                    return true
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) failed = error.description?.toString().orEmpty()
                }

                override fun onPageStarted(view: WebView, pageUrl: String?, favicon: Bitmap?) {
                    loading = true
                    failed = null
                    pageUrl?.let { url = it }
                }

                override fun onPageFinished(view: WebView, pageUrl: String?) {
                    loading = false
                    canGoBack = view.canGoBack()
                }

                // A single-page app moves with history.pushState (no page load): Back and the address follow it too,
                // so Back steps back inside the app instead of closing the preview.
                override fun doUpdateVisitedHistory(view: WebView, historyUrl: String?, isReload: Boolean) {
                    historyUrl?.let { url = it }
                    canGoBack = view.canGoBack()
                }

                // A dev server's page that crashes or exhausts the WebView renderer must not take the app (its live
                // sessions and terminals) down with it: leave the preview instead.
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    onBack()
                    return true
                }
            }
            loadUrl(start)
        }
    }
    DisposableEffect(web) {
        onDispose {
            web.stopLoading()
            web.destroy()
        }
    }
    BackHandler(enabled = canGoBack) { web.goBack() }

    Column(Modifier.fillMaxSize().background(colors.canvas).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        // The glass bar: close, the address in a glass capsule (monoMeta ink2, middle ellipsis), Reload and Open in browser.
        GlassBar(
            leading = { GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Close the preview", onClick = onBack) },
            center = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .glass(CircleShape, colors)
                        .padding(horizontal = Space.lg),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(url.removePrefix("http://"), style = type.monoMeta, color = colors.ink2, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                }
            },
            actions = {
                GlassIconButton(Icons.Rounded.Refresh, "Reload", onClick = { web.reload() })
                GlassIconButton(Icons.AutoMirrored.Rounded.OpenInNew, "Open in browser", onClick = { openOutside(context, url) })
            },
        )
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (loading) ProgressLine(progress / 100f)
        }
        Box(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()) {
            AndroidView(factory = { web }, modifier = Modifier.fillMaxSize())
            failed?.let { reason ->
                Column(Modifier.fillMaxSize().background(colors.canvas).padding(top = Space.sm)) {
                    EmptyCard(
                        Icons.Rounded.CloudOff,
                        "Nothing answers on localhost:$port",
                        "Check the dev server's terminal, then reload." +
                            if (reason.isNotBlank()) " ($reason)" else "",
                    ) {
                        PrimaryButton("Reload", onClick = { web.reload() }, icon = Icons.Rounded.Refresh, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

private fun openOutside(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No browser installed: nothing to open.
    }
}
