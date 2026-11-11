package opensec.pibolt.app.ui.terminal

import opensec.pibolt.app.ui.projects.EmptyCard
import opensec.pibolt.app.ui.components.PrimaryButton
import androidx.compose.material.icons.rounded.CloudOff
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.view.ViewGroup
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.preview.PreviewController
import opensec.pibolt.app.preview.PreviewOrigins
import opensec.pibolt.app.ui.components.GlassPill
import androidx.compose.runtime.remember
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

/**
 * The dev app in an in-app browser: the shared preview WebView ([PreviewController]), restricted to http://localhost
 * and 127.0.0.1, JavaScript on (dev apps need it), no file or content access. Pi's preview tools drive the same page,
 * so the user sees what Pi sees; "Pi is using the preview" shows while they do. Reload and "Open in browser" in the bar.
 * A tapped link elsewhere opens in the phone's browser; other navigations away from localhost are blocked.
 */
@Composable
fun PreviewScreen(port: Int, onBack: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val context = LocalContext.current
    val preview = (context.applicationContext as PiBoltApp).preview
    val start = "http://localhost:$port/"
    val page by preview.page.collectAsStateWithLifecycle()
    val driving by preview.driving.collectAsStateWithLifecycle()
    val web = remember(port) { preview.show(port) }
    DisposableEffect(web) {
        onDispose { preview.hide() }
    }
    // The renderer crashed or was killed: leave the preview (the app and its sessions stay up).
    LaunchedEffect(preview) { preview.gone.collect { onBack() } }
    BackHandler(enabled = page.canGoBack) { preview.goBack() }
    val url = page.url ?: start

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
                GlassIconButton(Icons.Rounded.Refresh, "Reload", onClick = { preview.reload() })
                GlassIconButton(Icons.AutoMirrored.Rounded.OpenInNew, "Open in browser", onClick = { openOutside(context, url) })
            },
        )
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (page.loading) ProgressLine(page.progress / 100f)
        }
        Box(Modifier.weight(1f).fillMaxWidth().navigationBarsPadding()) {
            AndroidView(
                factory = {
                    (web.parent as? ViewGroup)?.removeView(web)
                    web
                },
                modifier = Modifier.fillMaxSize(),
            )
            page.failed?.let { reason ->
                Column(Modifier.fillMaxSize().background(colors.canvas).padding(top = Space.sm)) {
                    EmptyCard(
                        Icons.Rounded.CloudOff,
                        "Nothing answers on localhost:${PreviewOrigins.portOf(url) ?: port}",
                        "Check the dev server's terminal, then reload." +
                            if (reason.isNotBlank()) " ($reason)" else "",
                    ) {
                        PrimaryButton("Reload", onClick = { preview.reload() }, icon = Icons.Rounded.Refresh, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (driving != null) PiDrivingPill(Modifier.align(Alignment.BottomCenter).padding(bottom = Space.lg))
        }
    }
}

/** "Pi is using the preview": shown while Pi's preview tools drive the page (and a few seconds after). */
@Composable
fun PiDrivingPill(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val colors = Pi.colors
    GlassPill(modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite }, onClick = onClick, label = onClick?.let { "Watch Pi in the preview" }) {
        Box(Modifier.size(8.dp).background(colors.accent, CircleShape))
        Text("Pi is using the preview", style = Pi.type.label, color = colors.ink1, maxLines = 1)
    }
}

private fun openOutside(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No browser installed: nothing to open.
    }
}
