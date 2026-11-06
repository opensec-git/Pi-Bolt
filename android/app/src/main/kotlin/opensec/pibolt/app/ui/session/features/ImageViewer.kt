package opensec.pibolt.app.ui.session.features

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.protocol.rpc.ImageAttachment
import java.io.File

/** Images for the full-screen viewer: the full encoded image when this phone has it, else the thumbnail. */
@Immutable
data class ViewerImages(val images: List<ImageAttachment?>, val thumbs: List<ImageBitmap>, val start: Int) {
    val count: Int get() = maxOf(images.size, thumbs.size)
}

/** Zoom limits and the double-tap step of the image viewer. */
object ImageZoom {
    const val MIN = 1f
    const val MAX = 6f
    const val DOUBLE_TAP = 2.5f

    /** The scale after a pinch by [factor], kept within limits. */
    fun scale(current: Float, factor: Float): Float = (current * factor).coerceIn(MIN, MAX)

    /** Pan [offset] by [pan], kept so the scaled image still covers the centre (no drifting off screen). */
    fun clampOffset(offset: Offset, pan: Offset, scale: Float, width: Float, height: Float): Offset {
        val maxX = (width * (scale - 1f)) / 2f
        val maxY = (height * (scale - 1f)) / 2f
        val next = offset + pan
        return Offset(next.x.coerceIn(-maxX, maxX), next.y.coerceIn(-maxY, maxY))
    }

    fun toggle(current: Float): Float = if (current > MIN) MIN else DOUBLE_TAP
}

/**
 * Full-screen images on black: swipe between them, pinch or double-tap to zoom, drag to pan. Save puts the image in
 * Pictures/Pi-Bolt (Android 10+); Share sends it anywhere. Back closes.
 */
@Composable
fun ImageViewer(content: ViewerImages, onClose: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = content.start.coerceIn(0, (content.count - 1).coerceAtLeast(0))) { content.count }
    var zoomed by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize()) { page ->
            val bitmap by produceState<ImageBitmap?>(content.thumbs.getOrNull(page), content, page) {
                content.images.getOrNull(page)?.let { full -> decode(full)?.let { value = it } }
            }
            ZoomableImage(bitmap, "Image ${page + 1} of ${content.count}", onZoom = { zoomed = it })
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(Icons.Rounded.Close, "Close", onClick = onClose, tint = Color.White)
            Text(
                if (content.count > 1) "${pager.currentPage + 1} / ${content.count}" else "Image",
                style = Pi.type.subhead,
                color = Color.White,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            fun current(): Bitmap? {
                val full = content.images.getOrNull(pager.currentPage)
                return full?.let { decodeBitmap(it) } ?: content.thumbs.getOrNull(pager.currentPage)?.asAndroidBitmap()
            }
            if (Build.VERSION.SDK_INT >= 29) {
                IconAction(Icons.Rounded.Download, "Save to Pictures", onClick = {
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) { current()?.let { saveToPictures(context, it) } ?: false }
                        onMessage(if (saved) "Saved to Pictures/Pi-Bolt" else "Could not save the image")
                    }
                }, tint = Color.White)
            }
            IconAction(Icons.Rounded.Share, "Share image", onClick = {
                scope.launch {
                    val file = withContext(Dispatchers.IO) { current()?.let { writePng(context, it) } }
                    if (file != null) ShareFiles.share(context, file, "image/png") else onMessage("Could not share the image")
                }
            }, tint = Color.White)
        }
    }
}

@Composable
private fun ZoomableImage(bitmap: ImageBitmap?, description: String, onZoom: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    scale = ImageZoom.toggle(scale)
                    offset = Offset.Zero
                    onZoom(scale > ImageZoom.MIN)
                })
            }
            .pointerInput(Unit) {
                // Pinch always; a one-finger drag only pans a zoomed image (otherwise it swipes the pager).
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || scale > ImageZoom.MIN) {
                            scale = ImageZoom.scale(scale, event.calculateZoom())
                            offset = if (scale <= ImageZoom.MIN) Offset.Zero else ImageZoom.clampOffset(offset, event.calculatePan(), scale, size.width.toFloat(), size.height.toFloat())
                            onZoom(scale > ImageZoom.MIN)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) {
            QuadSpinner(QuadMode.Write, size = 24.dp, color = Color.White)
        } else {
            Image(
                bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
    }
}

private suspend fun decode(image: ImageAttachment): ImageBitmap? = withContext(Dispatchers.Default) { decodeBitmap(image)?.asImageBitmap() }

private fun decodeBitmap(image: ImageAttachment): Bitmap? = runCatching {
    val bytes = Base64.decode(image.base64, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()

private fun writePng(context: Context, bitmap: Bitmap): File? = runCatching {
    File(ShareFiles.exportsDir(context), "pi-image-${System.currentTimeMillis()}.png").apply {
        outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}.getOrNull()

private fun saveToPictures(context: Context, bitmap: Bitmap): Boolean {
    if (Build.VERSION.SDK_INT < 29) return false
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "pi-${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Pi-Bolt")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    return runCatching {
        resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: error("no stream")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        true
    }.getOrElse {
        resolver.delete(uri, null, null)
        false
    }
}
