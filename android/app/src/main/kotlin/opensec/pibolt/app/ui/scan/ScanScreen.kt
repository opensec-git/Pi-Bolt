package opensec.pibolt.app.ui.scan

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Radius
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.NoPhotography
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
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
import opensec.pibolt.app.ui.components.GlassBarInset
import opensec.pibolt.app.ui.components.GlassIconButton
import opensec.pibolt.app.ui.components.glass
import opensec.pibolt.app.ui.theme.Space
import androidx.compose.foundation.layout.heightIn
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.hosts.PasteSheet
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.protocol.PairingPayload
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val WINDOW = 260.dp

/** Scan a pairing QR code. [onCode] returns an error message to show, or null when the code was accepted. */
@Composable
fun ScanScreen(onBack: () -> Unit, onCode: (String) -> String?) {
    val context = LocalContext.current
    val colors = Pi.colors
    val type = Pi.type
    val haptics = Pi.haptics
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    // Saved, so a rotation does not ask again. A refusal with no rationale to show means Android no longer shows its
    // dialog (refused twice, or "Don't ask again"): the button then opens the app's system page instead.
    var asked by rememberSaveable { mutableStateOf(false) }
    var blocked by rememberSaveable { mutableStateOf(false) }
    val activity = LocalActivity.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        blocked = !it && activity?.let { a -> !ActivityCompat.shouldShowRequestPermissionRationale(a, Manifest.permission.CAMERA) } == true
        asked = true
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    var message by remember { mutableStateOf<String?>(null) }
    var accepted by remember { mutableStateOf(false) }
    var showPaste by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        if (!granted && !asked) launcher.launch(Manifest.permission.CAMERA)
        onDispose { }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.canvas)) {
        // Landscape: the window sits in the space left of the controls, which move to the end side.
        val wide = maxWidth > maxHeight
        val controlsWidth = 320.dp
        if (granted) {
            CameraPreview(onText = { text ->
                if (!PairingPayload.looksLikePairing(text)) {
                    haptics.reject()
                    message = "That QR code is not a Pi-Bolt pairing code."
                    false
                } else {
                    val error = onCode(text)
                    message = error
                    if (error == null) {
                        accepted = true
                        haptics.confirm()
                    } else {
                        haptics.reject()
                    }
                    error == null
                }
            })
            ScanOverlay(accepted, endReserve = if (wide) controlsWidth else 0.dp)
        } else {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(72.dp).glass(CircleShape, colors), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.NoPhotography, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(IconSize.hero))
                }
                Spacer(Modifier.height(20.dp))
                Text("Allow the camera", style = type.largeTitle, color = colors.ink1, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(8.dp))
                Text(
                    "Used only to read the pairing code. You can also paste the link.",
                    style = type.rowMeta,
                    color = colors.ink2,
                    textAlign = TextAlign.Center,
                )
                if (asked) {
                    Spacer(Modifier.height(24.dp))
                    if (blocked) {
                        PrimaryButton("Open settings", onClick = { openAppSettings(context) })
                        Spacer(Modifier.height(8.dp))
                        Text("Allow it under Permissions, then come back.", style = type.meta, color = colors.ink3, textAlign = TextAlign.Center)
                    } else {
                        PrimaryButton("Allow camera", onClick = { launcher.launch(Manifest.permission.CAMERA) })
                    }
                }
            }
        }

        // Title over the camera, on a canvas fade so it reads on any picture: what to scan and where it comes from.
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(colors.canvas.copy(alpha = 0.85f), colors.canvas.copy(alpha = 0f))))
                .statusBarsPadding()
                .padding(start = GlassBarInset, end = Space.page, top = 4.dp, bottom = 32.dp),
        ) {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
            Spacer(Modifier.height(Space.xs))
            Text("Scan the code", style = type.largeTitle, color = colors.ink1, modifier = Modifier.padding(start = Space.xxs).semantics { heading() })
            Text("Run /\u2060remote-control on your computer.", style = type.rowMeta, color = colors.ink2, modifier = Modifier.padding(start = Space.xxs, top = Space.xxs))
        }

        Column(
            Modifier
                .align(if (wide) Alignment.BottomEnd else Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .then(if (wide) Modifier.width(controlsWidth - 40.dp) else Modifier.fillMaxWidth()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (granted) {
                Row(
                    Modifier
                        .heightIn(min = 48.dp)
                        .glass(RoundedCornerShape(Radius.pill), colors)
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnimatedContent(
                        targetState = accepted,
                        transitionSpec = { (fadeIn(tween(PiMotion.GLYPH)) + scaleIn(tween(PiMotion.GLYPH), initialScale = 0.7f)) togetherWith fadeOut(tween(PiMotion.exit(PiMotion.GLYPH))) },
                        label = "scanState",
                    ) { ok ->
                        if (ok) {
                            Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.success, modifier = Modifier.size(IconSize.md))
                        } else {
                            QuadSpinner(QuadMode.Connect, size = 16.dp, color = if (message != null) colors.warning else colors.ink2)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        when {
                            accepted -> "Code read. Connecting…"
                            message != null -> message!!
                            else -> "Looking for a code"
                        },
                        style = type.rowMeta,
                        color = if (message != null && !accepted) colors.ink1 else colors.ink2,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
            SecondaryButton("Paste a link instead", onClick = { showPaste = true }, icon = Icons.Rounded.ContentPaste, modifier = Modifier.fillMaxWidth())
        }
    }

    if (showPaste) {
        PasteSheet(
            onDismiss = { showPaste = false },
            onSubmit = { text ->
                val error = onCode(text)
                if (error == null) showPaste = false
                error
            },
        )
    }
}

/**
 * Canvas-tinted scrim with a 260dp window (smaller when the screen is short); the corner brackets settle in when the
 * screen opens. [endReserve] is the width the controls take at the end side in landscape: the window centres in the
 * rest, under the title bar.
 */
@Composable
private fun ScanOverlay(accepted: Boolean, endReserve: Dp) {
    val colors = Pi.colors
    val reduced = Pi.reduceMotion
    val settle = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { settle.animateTo(1f, tween(PiMotion.SETTLE, delayMillis = 120, easing = PiMotion.Enter)) }
    val bracket = if (accepted) colors.success else colors.ink1
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        val reserve = endReserve.toPx()
        val area = size.width - reserve
        val side: Float
        val topLeft: Offset
        if (reserve > 0f) {
            // Landscape: below the title (about 88dp with the status bar), above the bottom edge.
            val top = 88.dp.toPx()
            side = minOf(WINDOW.toPx(), size.height - top - 16.dp.toPx(), area - 32.dp.toPx()).coerceAtLeast(120.dp.toPx())
            val x = if (layoutDirection == LayoutDirection.Rtl) reserve + (area - side) / 2 else (area - side) / 2
            topLeft = Offset(x, top + (size.height - top - side) / 2)
        } else {
            side = WINDOW.toPx()
            topLeft = Offset((size.width - side) / 2, (size.height - side) / 2.3f)
        }
        val radius = 28.dp.toPx()
        drawRect(colors.canvas.copy(alpha = 0.72f))
        drawRoundRect(Color.Transparent, topLeft, Size(side, side), CornerRadius(radius), blendMode = BlendMode.Clear)
        // Brackets start 18dp outside and faint, then settle onto the window.
        val t = settle.value
        val out = (1f - t) * 18.dp.toPx()
        val len = 34.dp.toPx()
        val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        val l = topLeft.x - out
        val tp = topLeft.y - out
        val r = topLeft.x + side + out
        val b = topLeft.y + side + out
        val c = radius
        fun corner(path: Path) = drawPath(path, bracket, alpha = 0.35f + 0.65f * t, style = stroke)
        corner(Path().apply { moveTo(l, tp + len); lineTo(l, tp + c); quadraticTo(l, tp, l + c, tp); lineTo(l + len, tp) })
        corner(Path().apply { moveTo(r - len, tp); lineTo(r - c, tp); quadraticTo(r, tp, r, tp + c); lineTo(r, tp + len) })
        corner(Path().apply { moveTo(r, b - len); lineTo(r, b - c); quadraticTo(r, b, r - c, b); lineTo(r - len, b) })
        corner(Path().apply { moveTo(l + len, b); lineTo(l + c, b); quadraticTo(l, b, l, b - c); lineTo(l, b - len) })
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

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
