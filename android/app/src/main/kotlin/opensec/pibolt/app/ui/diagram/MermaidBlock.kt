package opensec.pibolt.app.ui.diagram

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import opensec.pibolt.app.ui.components.CodeBlock
import opensec.pibolt.app.ui.components.CopyButton
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.session.Viewer
import opensec.pibolt.app.ui.session.ViewerContent
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius

/** Inline diagrams are at most this tall; the full-screen view shows the rest. */
private val MAX_INLINE_HEIGHT = 480.dp
private val SKELETON_HEIGHT = 160.dp
private val INSET = 12.dp

/** Inline bitmaps stay under this many pixels (about 16 MB as ARGB). */
private const val MAX_INLINE_PIXELS = 4_000_000

/**
 * A ```mermaid fence drawn as a diagram: a surface1 card with a 40dp header (type, copy source, full screen) and the
 * diagram fitted to the column, at most 480dp tall. Tapping it opens the full-screen view (pinch-zoom, pan, fit,
 * share and save as PNG).
 *
 * While the fence streams, [code] is the partial source: each change waits [DiagramGate.DEBOUNCE_MS] before it is
 * drawn, so a diagram appears when the model pauses or the fence closes. Meanwhile a skeleton with a Quad spinner shows,
 * or the previous drawing with a small spinner. [closed]: true when the caller knows the fence is closed (draw at
 * once), false while it is still open (errors wait for more text), null when unknown.
 *
 * A source Mermaid rejects falls back to the code block with "Diagram could not be drawn: <message>" under it.
 * TalkBack reads "Diagram: <type>, <first label>".
 */
@Composable
fun MermaidBlock(code: String, modifier: Modifier = Modifier, closed: Boolean? = null) {
    val colors = Pi.colors
    val context = LocalContext.current
    val density = LocalDensity.current
    val theme = remember(colors) { MermaidTheme.of(colors) }
    val source = code.trim()
    var full by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val innerWidth = with(density) { (maxWidth - INSET * 2).roundToPx() }.coerceAtLeast(1)
        val size = remember(innerWidth, density.density) {
            RenderSize(scale = density.density, maxWidth = innerWidth, maxHeight = with(density) { MAX_INLINE_HEIGHT.roundToPx() }, maxPixels = MAX_INLINE_PIXELS)
        }
        val key = remember(source, theme, size) { DiagramKey.of(source, theme.json, "inline " + size.json) }
        var latest by remember { mutableStateOf<Pair<String, DiagramResult>?>(null) }
        var lastDrawn by remember { mutableStateOf<DiagramImage?>(null) }
        val cached = remember(key) { DiagramCache.get(key) }
        val current = latest?.takeIf { it.first == key }?.second ?: cached

        LaunchedEffect(key) {
            if (current != null) {
                if (current is DiagramResult.Drawn) lastDrawn = current.image
                return@LaunchedEffect
            }
            delay(DiagramGate.delayBeforeRender(closed, cached = false))
            if (source.isBlank()) {
                if (closed == true) latest = key to DiagramResult.Failed("The diagram is empty")
                return@LaunchedEffect
            }
            val result = MermaidRenderer.request(context, key, source, theme, size).await()
            latest = key to result
            if (result is DiagramResult.Drawn) lastDrawn = result.image
        }

        val outcome = when (current) {
            is DiagramResult.Drawn -> DiagramGate.Outcome.Drawn
            is DiagramResult.Failed -> DiagramGate.Outcome.Failed
            null -> null
        }
        val shownImage = (current as? DiagramResult.Drawn)?.image ?: lastDrawn
        val view = DiagramGate.view(outcome, hasStaleImage = lastDrawn != null && current !is DiagramResult.Drawn, closed = closed)
        val kind = shownImage?.kind ?: DiagramText.kindFromSource(source)

        when (view) {
            DiagramView.Error -> DiagramError(code, (current as? DiagramResult.Failed)?.message.orEmpty())
            else -> DiagramCard(
                kind = kind,
                image = if (view == DiagramView.Skeleton) null else shownImage,
                updating = view == DiagramView.ImageUpdating,
                copy = { code },
                onOpen = { if (shownImage != null) full = true },
            )
        }
        if (full && shownImage != null) {
            Dialog(
                onDismissRequest = { full = false },
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            ) {
                DiagramScreen(source, shownImage, theme, onClose = { full = false })
            }
        }
    }
}

@Composable
private fun DiagramCard(kind: String, image: DiagramImage?, updating: Boolean, copy: () -> String, onOpen: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val density = LocalDensity.current
    val shape = RoundedCornerShape(Radius.md)
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface1, shape)
            .border(1.dp, colors.line, shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.AccountTree, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                DiagramText.kindName(kind),
                style = type.monoMeta.copy(color = colors.ink3),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (updating || image == null) {
                QuadSpinner(QuadMode.Write, size = IconSize.xs, color = colors.ink2)
                Spacer(Modifier.width(10.dp))
            }
            if (image != null) {
                IconAction(Icons.Rounded.OpenInFull, "Open diagram full screen", onOpen, tint = colors.ink3, size = 16.dp, modifier = Modifier.size(40.dp))
            }
            CopyButton(copy, Modifier.height(40.dp), label = false, clipLabel = "Mermaid", description = "Copy diagram source")
        }
        Crossfade(
            targetState = image,
            animationSpec = if (Pi.reduceMotion) snap() else tween(PiMotion.QUICK),
            label = "diagram",
        ) { shown ->
            if (shown == null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(SKELETON_HEIGHT)
                        .semantics { contentDescription = "Drawing diagram" },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        QuadSpinner(QuadMode.Write, size = 20.dp, color = colors.ink3)
                        Spacer(Modifier.height(8.dp))
                        Text("Drawing diagram", style = type.meta, color = colors.ink3)
                    }
                }
            } else {
                val w: Dp = with(density) { shown.bitmap.width.toDp() }
                val h: Dp = with(density) { shown.bitmap.height.toDp() }
                val description = DiagramText.description(shown.kind, shown.label)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .pressable(onOpen, shape = RoundedCornerShape(bottomStart = Radius.md, bottomEnd = Radius.md), pressedScale = 1f, role = null, label = "Open full screen")
                        .semantics {
                            contentDescription = description
                            role = Role.Image
                        }
                        .padding(start = INSET, end = INSET, bottom = INSET, top = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    remember(shown) { shown.bitmap.asImageBitmap() }.let { bitmap ->
                        Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(w, h))
                    }
                }
            }
        }
    }
}

/** The source as a code block, and why it could not be drawn. */
@Composable
private fun DiagramError(code: String, message: String) {
    val colors = Pi.colors
    var viewer by remember { mutableStateOf<ViewerContent?>(null) }
    Column(Modifier.fillMaxWidth()) {
        CodeBlock(code, "mermaid", "", closed = true, onOpen = { c, title -> viewer = ViewerContent(title, null, c) })
        Row(Modifier.padding(top = 6.dp, start = 2.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = colors.danger, modifier = Modifier.padding(top = 1.dp).size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("Diagram could not be drawn: ${DiagramText.errorLine(message)}", style = Pi.type.meta, color = colors.ink3)
        }
    }
    viewer?.let { content ->
        Dialog(onDismissRequest = { viewer = null }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Viewer(content, onClose = { viewer = null })
        }
    }
}
