package opensec.pibolt.app.ui.terminal

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.theme.Pi

/** Whether the preview may load [url]: plain http to this phone's loopback only (localhost or 127.0.0.1, any port). */
fun previewAllowed(url: String?): Boolean {
    val uri = runCatching { Uri.parse(url ?: return false) }.getOrNull() ?: return false
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
    var canGoBack by remember { mutableStateOf(false) }
    val web = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setGeolocationEnabled(false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url.toString()
                    if (previewAllowed(target)) return false
                    if (request.hasGesture() && request.isForMainFrame) openOutside(context, target)
                    return true
                }

                override fun onPageStarted(view: WebView, pageUrl: String?, favicon: Bitmap?) {
                    loading = true
                    pageUrl?.let { url = it }
                }

                override fun onPageFinished(view: WebView, pageUrl: String?) {
                    loading = false
                    canGoBack = view.canGoBack()
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

    Column(Modifier.fillMaxSize().background(colors.canvas)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Close the preview", onClick = onBack, tint = colors.ink1)
            Text(
                url.removePrefix("http://"),
                style = type.monoMeta,
                color = colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            IconAction(Icons.Rounded.Refresh, "Reload", onClick = { web.reload() })
            IconAction(Icons.AutoMirrored.Rounded.OpenInNew, "Open in browser", onClick = { openOutside(context, url) })
        }
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent, trackColor = colors.surface2)
        }
        AndroidView(factory = { web }, modifier = Modifier.weight(1f).fillMaxWidth().navigationBarsPadding())
    }
}

private fun openOutside(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No browser installed: nothing to open.
    }
}
