package opensec.pibolt.app.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import opensec.pibolt.protocol.PairingPayload
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Scan a pairing QR code. [onCode] returns an error message to show, or null when the code was accepted. */
@Composable
fun ScanScreen(onBack: () -> Unit, onCode: (String) -> String?) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    var message by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
        onDispose { }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (granted) {
            CameraPreview(onText = { text ->
                if (!PairingPayload.looksLikePairing(text)) {
                    message = "That QR code is not a Pi-Bolt pairing code."
                    false
                } else {
                    val error = onCode(text)
                    message = error
                    error == null
                }
            })
            ScanOverlay()
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.NoPhotography, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text(
                    "Pi-Bolt needs the camera to scan the pairing code. You can also paste the link from /remote-control copy.",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                if (asked) {
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
                }
            }
        }

        IconButton(
            onClick = onBack,
            colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.45f), contentColor = Color.White),
            modifier = Modifier
                .statusBarsPadding()
                .padding(12.dp),
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(24.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(color = Color.Black.copy(alpha = 0.55f), shape = RoundedCornerShape(16.dp)) {
                Text(
                    message ?: "Point the camera at the code from /remote-control",
                    color = if (message != null) MaterialTheme.colorScheme.errorContainer else Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun ScanOverlay() {
    val accent = MaterialTheme.colorScheme.primary
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        val side = size.minDimension * 0.68f
        val topLeft = Offset((size.width - side) / 2, (size.height - side) / 2.2f)
        drawRect(Color.Black.copy(alpha = 0.5f))
        drawRoundRect(Color.Transparent, topLeft, Size(side, side), CornerRadius(28.dp.toPx()), blendMode = BlendMode.Clear)
        drawRoundRect(accent, topLeft, Size(side, side), CornerRadius(28.dp.toPx()), style = Stroke(3.dp.toPx()))
    }
}

/** [onText] returns true when scanning should stop. */
@Composable
private fun CameraPreview(onText: (String) -> Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onText)
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val done = remember { AtomicBoolean(false) }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = p
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val reader = QrDecoder()
            var lastRejected: String? = null
            analysis.setAnalyzer(executor) { image ->
                val text = image.use { reader.decode(it) }
                if (text != null && !done.get() && text != lastRejected) {
                    ContextCompat.getMainExecutor(context).execute {
                        if (!done.get()) {
                            if (callback(text)) done.set(true) else lastRejected = text
                        }
                    }
                }
            }
            runCatching {
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/** ZXing on the luminance (Y) plane: no color conversion, no Play services. */
private class QrDecoder {
    private val reader = QRCodeReader()
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )
    private var buffer = ByteArray(0)

    fun decode(image: ImageProxy): String? {
        val plane = image.planes[0]
        val data = plane.buffer
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height
        val needed = rowStride * height
        if (buffer.size < needed) buffer = ByteArray(needed)
        data.rewind()
        data.get(buffer, 0, minOf(data.remaining(), needed))
        val source = PlanarYUVLuminanceSource(buffer, rowStride, height, 0, 0, width, height, false)
        return try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }
}
