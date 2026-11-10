package opensec.pibolt.app.ui.markdown

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.RadiusMedia
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** An image a reply links to. Nothing is fetched until the user taps: the card shows the alt text and the address. */
@Composable
fun MdImage(alt: String, url: String, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val remote = url.startsWith("https://") || url.startsWith("http://")
    var state by remember(url) { mutableStateOf<ImageState>(loaded.get(url)?.let { ImageState.Loaded(it) } ?: ImageState.Idle) }
    val shape = RoundedCornerShape(RadiusMedia)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface1)
            .border(1.dp, colors.line, shape),
    ) {
        val current = state
        if (current is ImageState.Loaded) {
            Image(
                current.bitmap,
                contentDescription = alt.ifBlank { null },
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).background(colors.surface2),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .pressable(
                    onClick = {
                        if (!remote || state is ImageState.Loading || state is ImageState.Loaded) return@pressable
                        state = ImageState.Loading
                        scope.launch {
                            val bitmap = withContext(Dispatchers.IO) { fetch(url) }
                            state = if (bitmap != null) {
                                loaded.put(url, bitmap)
                                ImageState.Loaded(bitmap)
                            } else {
                                ImageState.Failed
                            }
                        }
                    },
                    shape = shape,
                    pressedScale = 1f,
                    label = if (remote) "Load image" else alt,
                )
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (current) {
                ImageState.Loading -> QuadSpinner(QuadMode.Write, size = 16.dp, color = colors.ink2)
                ImageState.Failed -> Icon(Icons.Rounded.BrokenImage, contentDescription = null, tint = colors.danger, modifier = Modifier.size(18.dp))
                else -> Icon(Icons.Rounded.Image, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(alt.ifBlank { "Image" }, style = type.body.copy(color = colors.ink1), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(url, style = type.monoMeta.copy(color = colors.ink3), maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                val hint = when {
                    !remote -> null
                    current is ImageState.Idle -> "Tap to load"
                    current is ImageState.Failed -> "Could not load the image · tap to retry"
                    else -> null
                }
                if (hint != null) Text(hint, style = type.meta.copy(color = if (current is ImageState.Failed) colors.danger else colors.ink3))
            }
            if (remote) {
                Box(
                    Modifier
                        .size(44.dp)
                        .pressable({ runCatching { uri.openUri(url) } }, shape = RoundedCornerShape(Radius.sm), pressedScale = 0.92f, label = "Open in browser"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Open in browser", tint = colors.ink3, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

private sealed interface ImageState {
    data object Idle : ImageState

    data object Loading : ImageState

    data object Failed : ImageState

    class Loaded(val bitmap: ImageBitmap) : ImageState
}

/** Images the user loaded this session, so scrolling back does not fetch again. */
private val loaded = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
}

private const val MAX_BYTES = 12 * 1024 * 1024
private const val MAX_EDGE = 2048

/** Downloads (at most [MAX_BYTES]) and decodes [url], downsampled so its long edge is at most [MAX_EDGE]. */
private fun fetch(url: String): ImageBitmap? = runCatching {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 10_000
    connection.readTimeout = 15_000
    connection.instanceFollowRedirects = true
    connection.setRequestProperty("Accept", "image/*")
    try {
        if (connection.responseCode !in 200..299) return null
        val bytes = connection.inputStream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BYTES) return null
            }
            out.toByteArray()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_EDGE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    } finally {
        connection.disconnect()
    }
}.getOrNull()
