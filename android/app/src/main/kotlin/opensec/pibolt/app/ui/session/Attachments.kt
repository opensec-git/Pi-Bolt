package opensec.pibolt.app.ui.session

import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import opensec.pibolt.protocol.rpc.ImageAttachment
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Size and format decisions for images attached in the composer. Pure, so they are unit-tested. */
object ImageSizing {
    /** At most this many images per message. */
    const val MAX_IMAGES = 4

    /** Long edge after downscaling: Pi resizes to the same limit, so a larger image only costs transfer. */
    const val MAX_EDGE = 2000

    /** Pi's limit on one image's base64 payload (4.5 MiB). */
    const val MAX_BASE64 = 4_718_592

    /** A transparent PNG is kept as PNG only up to this many bytes; larger ones go to JPEG. */
    const val PNG_KEEP_BYTES = 1_500_000

    const val JPEG_QUALITY = 85

    /** The thumbnail kept in memory for the composer tray and the user bubble (long edge, px). */
    const val THUMB_EDGE = 192

    /** [width] x [height] scaled so the long edge is at most [maxEdge]; never upscaled, never below 1 px. */
    fun target(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        val long = max(width, height)
        if (long <= maxEdge || long <= 0) return width.coerceAtLeast(1) to height.coerceAtLeast(1)
        val scale = maxEdge.toDouble() / long
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    /** The largest power-of-two subsample that still decodes at least [maxEdge] on the long edge. */
    fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Int {
        val long = max(width, height)
        var sample = 1
        while (long / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    /** Base64 length of [bytes] raw bytes (no line breaks). */
    fun base64Size(bytes: Int): Int = (bytes + 2) / 3 * 4

    /** Whether an encoded image of [bytes] fits Pi's per-image limit. */
    fun fits(bytes: Int): Boolean = base64Size(bytes) <= MAX_BASE64

    /** PNG only for images with transparency whose PNG stays small; everything else is JPEG. */
    fun keepPng(hasAlpha: Boolean, pngBytes: Int?): Boolean = hasAlpha && pngBytes != null && pngBytes <= PNG_KEEP_BYTES

    /** How many more images fit in the message. */
    fun slotsLeft(attached: Int): Int = (MAX_IMAGES - attached).coerceAtLeast(0)
}

/** An image in the composer: [image] and [thumb] are null while it is being encoded. */
data class ComposerImage(val id: Long, val image: ImageAttachment? = null, val thumb: ImageBitmap? = null) {
    val ready: Boolean get() = image != null
}

/** The result of encoding a picked image. */
sealed interface Encoded {
    data class Ok(val image: ImageAttachment, val thumb: ImageBitmap) : Encoded

    data class Failed(val message: String) : Encoded
}

/**
 * True when the clipboard holds an image (copied from a browser, a screenshot, a gallery app). Reads only the clip's
 * description, so Android does not report a paste.
 */
fun clipboardHasImage(context: Context): Boolean =
    context.getSystemService(ClipboardManager::class.java)?.primaryClipDescription?.hasMimeType("image/*") == true

/** The clipboard's image, or null. */
fun clipboardImage(context: Context): Uri? {
    if (!clipboardHasImage(context)) return null
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
}

/**
 * Decodes [uri] off the main thread, downscaled so the long edge is at most [ImageSizing.MAX_EDGE], and encodes it as
 * JPEG (quality 85) or, for a small transparent image, PNG. Anything still over Pi's 4.5 MiB limit is refused.
 */
suspend fun encodeImage(resolver: ContentResolver, uri: Uri): Encoded = withContext(Dispatchers.Default) {
    val bitmap = runCatching { decode(resolver, uri) }.getOrNull() ?: return@withContext Encoded.Failed("Could not read that image")
    try {
        val png = if (bitmap.hasAlpha()) compress(bitmap, Bitmap.CompressFormat.PNG, 100) else null
        val (bytes, mime) = if (ImageSizing.keepPng(bitmap.hasAlpha(), png?.size)) {
            png!! to "image/png"
        } else {
            compress(opaque(bitmap), Bitmap.CompressFormat.JPEG, ImageSizing.JPEG_QUALITY) to "image/jpeg"
        }
        if (!ImageSizing.fits(bytes.size)) return@withContext Encoded.Failed("Image too large to send")
        val (tw, th) = ImageSizing.target(bitmap.width, bitmap.height, ImageSizing.THUMB_EDGE)
        val thumb = bitmap.scale(tw, th).asImageBitmap()
        Encoded.Ok(ImageAttachment(Base64.encodeToString(bytes, Base64.NO_WRAP), mime), thumb)
    } catch (_: OutOfMemoryError) {
        Encoded.Failed("Image too large to send")
    }
}

private fun decode(resolver: ContentResolver, uri: Uri): Bitmap? {
    if (Build.VERSION.SDK_INT >= 28) {
        // ImageDecoder also applies the EXIF orientation.
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val (w, h) = ImageSizing.target(info.size.width, info.size.height)
            decoder.setTargetSize(w, h)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply { inSampleSize = ImageSizing.sampleSize(bounds.outWidth, bounds.outHeight) }
    val sampled = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
    val (w, h) = ImageSizing.target(sampled.width, sampled.height)
    return if (w == sampled.width && h == sampled.height) sampled else sampled.scale(w, h)
}

/** JPEG has no alpha: transparent pixels go on white rather than black. */
private fun opaque(bitmap: Bitmap): Bitmap {
    if (!bitmap.hasAlpha()) return bitmap
    val out = createBitmap(bitmap.width, bitmap.height)
    Canvas(out).apply {
        drawColor(Color.WHITE)
        drawBitmap(bitmap, 0f, 0f, null)
    }
    return out
}

private fun compress(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray =
    ByteArrayOutputStream().also { bitmap.compress(format, quality, it) }.toByteArray()
