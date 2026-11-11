package opensec.pibolt.app.ui.diagram

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import opensec.pibolt.app.ui.components.GlassBar
import opensec.pibolt.app.ui.components.GlassIconButton
import opensec.pibolt.app.ui.components.glass
import opensec.pibolt.app.ui.theme.Pi
import java.io.ByteArrayOutputStream
import java.io.File

private const val MAX_ZOOM = 8f
private const val DOUBLE_TAP_ZOOM = 2.5f

/** The full-screen view draws again at up to twice the screen density for zooming, within this many pixels. */
private const val FULL_PIXELS = 8_000_000

/**
 * A diagram full screen on the canvas: pinch to zoom (1x = fitted to the screen, up to 8x), drag to pan, double-tap
 * to zoom in or back to fit, Fit, Share and Save as PNG. Shows [preview] at once and swaps in a sharper drawing.
 */
@Composable
internal fun DiagramScreen(source: String, preview: DiagramImage, theme: MermaidTheme, onClose: () -> Unit) {
    val colors = Pi.colors
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var image by remember { mutableStateOf(preview) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(source, theme) {
        val size = RenderSize(scale = density.density * 2f, maxWidth = 0, maxHeight = 0, maxPixels = FULL_PIXELS)
        val key = DiagramKey.of(source, theme.json, "full " + size.json)
        // Not cached: one large bitmap, held only while this view is open.
        val result = MermaidRenderer.request(context, key, source, theme, size, cache = false).await()
        if (result is DiagramResult.Drawn) image = result.image
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(1800)
            notice = null
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = pngBytes(image, theme.background)
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no stream")
                }.isSuccess
            }
            notice = if (ok) "Saved" else "Could not save the diagram"
        }
    }
    val name = DiagramText.kindName(image.kind)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(colors.canvas),
    ) {
        val viewW = constraints.maxWidth.toFloat()
        val viewH = constraints.maxHeight.toFloat()
        // Fitted size in pixels, with a 16dp margin; the diagram is never enlarged past twice its natural size.
        val margin = with(density) { 16.dp.toPx() }
        val natural = density.density
        val fit = minOf((viewW - 2 * margin) / (image.width * natural), (viewH - 2 * margin) / (image.height * natural), 2f)
        val fittedW = image.width * natural * fit
        val fittedH = image.height * natural * fit

        fun clamp(o: Offset, z: Float): Offset {
            val maxX = maxOf(0f, (fittedW * z - viewW) / 2f + margin)
            val maxY = maxOf(0f, (fittedH * z - viewH) / 2f + margin)
            return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
        }

        val bitmap = remember(image) { image.bitmap.asImageBitmap() }
        val description = DiagramText.description(image.kind, image.label)
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(fittedW, fittedH) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        val next = (zoom * gestureZoom).coerceIn(1f, MAX_ZOOM)
                        val center = Offset(viewW / 2f, viewH / 2f)
                        val fromCenter = centroid - center
                        // Keep the point under the fingers where it is while zooming.
                        val moved = (offset - fromCenter) * (next / zoom) + fromCenter + pan
                        zoom = next
                        offset = clamp(moved, next)
                    }
                }
                .pointerInput(fittedW, fittedH) {
                    detectTapGestures(
                        onDoubleTap = { tap ->
                            if (zoom > 1.01f) {
                                zoom = 1f
                                offset = Offset.Zero
                            } else {
                                val center = Offset(viewW / 2f, viewH / 2f)
                                val fromCenter = tap - center
                                zoom = DOUBLE_TAP_ZOOM
                                offset = clamp(-fromCenter * (DOUBLE_TAP_ZOOM - 1f), DOUBLE_TAP_ZOOM)
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(with(density) { fittedW.toDp() }, with(density) { fittedH.toDp() })
                    .semantics { contentDescription = description }
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }

        GlassBar(
            title = name,
            leading = { GlassIconButton(Icons.Rounded.Close, "Close", onClick = onClose) },
            actions = {
                GlassIconButton(Icons.Rounded.FitScreen, "Fit to screen", onClick = {
                    zoom = 1f
                    offset = Offset.Zero
                })
                GlassIconButton(Icons.Rounded.Share, "Share as PNG", onClick = {
                    scope.launch {
                        val shared = runCatching { sharePng(context, image, theme.background) }.isSuccess
                        if (!shared) notice = "Could not share the diagram"
                    }
                })
                GlassIconButton(Icons.Rounded.Download, "Save as PNG", onClick = { save.launch("diagram.png") })
            },
        )

        AnimatedVisibility(
            visible = notice != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            // A glass capsule, as the app's snackbars.
            Text(
                notice.orEmpty(),
                style = Pi.type.rowMeta,
                color = colors.ink1,
                modifier = Modifier
                    .glass(CircleShape, colors)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

/** The diagram as a PNG on its card colour with a margin, so it reads in any viewer. Background thread. */
private fun pngBytes(image: DiagramImage, background: Int): ByteArray {
    val source = image.bitmap.copy(Bitmap.Config.ARGB_8888, false)
    val pad = (24 * image.scale).toInt().coerceAtLeast(8)
    val out = createBitmap(source.width + pad * 2, source.height + pad * 2)
    Canvas(out).apply {
        drawColor(background)
        drawBitmap(source, pad.toFloat(), pad.toFloat(), null)
    }
    source.recycle()
    val bytes = ByteArrayOutputStream().use { stream ->
        out.compress(Bitmap.CompressFormat.PNG, 100, stream)
        stream.toByteArray()
    }
    out.recycle()
    return bytes
}

/** Shares the PNG through the app's FileProvider (cache/diagrams/ only). */
private suspend fun sharePng(context: Context, image: DiagramImage, background: Int) {
    val uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "diagrams").apply { mkdirs() }
        val file = File(dir, "diagram.png")
        file.writeBytes(pngBytes(image, background))
        FileProvider.getUriForFile(context, "${context.packageName}.diagrams", file)
    }
    val send = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri("diagram.png", uri)
    context.startActivity(Intent.createChooser(send, null))
}
