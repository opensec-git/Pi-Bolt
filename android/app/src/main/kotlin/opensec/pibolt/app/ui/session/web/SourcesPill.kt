package opensec.pibolt.app.ui.session.web

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.SOFT_PRESS
import opensec.pibolt.app.ui.components.ShimmerText
import opensec.pibolt.app.ui.components.glass
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.session.SourcesRow
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Space
import opensec.pibolt.app.ui.theme.Tnum

/** Favicons in the pill: 24dp circles, a 1.5dp ring of the canvas between overlapping ones, at most [STACK] of them. */
private val ICON = 24.dp
private val RING = 1.5.dp
private val OVERLAP = 9.dp
private const val STACK = 3

/**
 * Web steps as a glass pill (DESIGN.md, Sources): up to three overlapping favicons, "Sources 5" and a chevron; tapping
 * it lists every source (favicon, title, domain, open in the browser). While a search runs with nothing found yet it
 * reads "Searching the web" and the query. In the live turn each new source's favicon pops in (scale and fade, in
 * turn); a page being read (`fetch_content`) wears a soft rotating arc that settles when it is done; one that could
 * not be read is dimmed. Under reduce motion nothing moves (the arc is drawn still).
 */
@Composable
fun SourcesPill(row: SourcesRow, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    val web = row.sources
    val count = web.sources.size
    // Until every step has finished, an empty pill waits ("Searching the web", "Reading the page"); only then can it
    // say that nothing was found.
    val pending = count == 0 && web.active
    val failed = count == 0 && !web.active && web.error != null
    val waiting = if (web.searching) "Searching the web" else "Reading the page"
    // Favicons already shown when this row appeared do not pop in again (history never animates).
    val seen = remember(row.key) { HashSet<String>().apply { if (!row.live) web.sources.forEach { add(it.url) } } }
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The timeline's 20dp leading slot (on the fold's rail when opened there).
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                if (web.active) {
                    QuadSpinner(QuadMode.Run, size = 14.dp, color = colors.ink2, animate = row.live)
                } else {
                    Icon(Icons.Rounded.Public, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.width(Space.md))
            val label = when {
                pending -> waiting
                failed -> "Search failed"
                count == 0 -> "No sources found"
                else -> "Sources, $count"
            }
            Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                Row(
                    Modifier
                        .heightIn(min = 40.dp)
                        .widthIn(max = 320.dp)
                        .then(if (count > 0) Modifier.pressable(onToggle, shape = CircleShape, pressedScale = SOFT_PRESS, label = if (row.expanded) "$label, hide" else "$label, show") else Modifier)
                        .glass(CircleShape, colors)
                        .padding(start = if (count > 0) 8.dp else Space.lg, end = Space.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        pending -> {
                            ShimmerText(waiting, type.label, active = row.live)
                            WebTools.pendingLabel(web).takeIf { it.isNotBlank() }?.let { q ->
                                Spacer(Modifier.width(Space.sm))
                                Text(q, style = type.meta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            }
                        }
                        failed -> {
                            Icon(Icons.Rounded.Public, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(Space.sm))
                            Text(web.error ?: "Search failed", style = type.label, color = colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        }
                        count == 0 -> {
                            Icon(Icons.Rounded.Public, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(Space.sm))
                            Text("No sources found", style = type.label, color = colors.ink2, maxLines = 1)
                        }
                        else -> {
                            FaviconStack(web.sources.take(STACK), live = row.live, seen = seen)
                            Spacer(Modifier.width(Space.sm))
                            Text("Sources", style = type.label, color = colors.ink1)
                            Spacer(Modifier.width(6.dp))
                            Text("$count", style = type.label.copy(fontFeatureSettings = Tnum), color = colors.ink3)
                            Spacer(Modifier.width(2.dp))
                            val turn by animateFloatAsState(if (row.expanded) 180f else 0f, PiMotion.inPlace(PiMotion.QUICK), label = "chevron")
                            Icon(Icons.Rounded.ExpandMore, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = turn })
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = row.expanded && count > 0,
            enter = fadeIn(PiMotion.enter(PiMotion.STANDARD)) + expandVertically(PiMotion.enter(PiMotion.STANDARD)),
            exit = fadeOut(PiMotion.leave(PiMotion.STANDARD)) + shrinkVertically(PiMotion.leave(PiMotion.STANDARD)),
        ) {
            SourceList(web.sources, Modifier.padding(start = 32.dp, top = Space.xs, bottom = Space.xs))
        }
    }
}

/** The pill's favicons, each overlapping the next, the first on top, a ring of the canvas between them. */
@Composable
private fun FaviconStack(sources: List<WebSource>, live: Boolean, seen: HashSet<String>) {
    val colors = Pi.colors
    val disc = ICON + RING * 2
    val step = disc - OVERLAP
    Box(Modifier.width(step * (sources.size - 1) + disc).height(disc)) {
        // Drawn last first, so the first icon sits on top.
        for (k in sources.indices.reversed()) {
            val source = sources[k]
            Box(Modifier.offset(x = step * k).size(disc).background(colors.canvas, CircleShape), contentAlignment = Alignment.Center) {
                SourceIcon(source, ICON, popIn = live && seen.add(source.url), order = k)
            }
        }
    }
}

/**
 * A source's favicon in a circle (the Rounded globe while there is none), with the reading arc while it is fetched and
 * dimmed when it failed. [popIn]: scale and fade in after [order] steps of the stagger.
 */
@Composable
fun SourceIcon(source: WebSource, size: Dp, popIn: Boolean = false, order: Int = 0) {
    val colors = Pi.colors
    val reduced = Pi.reduceMotion
    val icon = rememberFavicon(source.url)
    val appear = remember(source.url) { Animatable(if (popIn && !reduced) 0f else 1f) }
    LaunchedEffect(source.url) {
        // One by one, even when every result lands in the same frame: 120ms apart by position.
        if (appear.value < 1f) appear.animateTo(1f, PiMotion.enter(PiMotion.STANDARD, delay = order.coerceAtMost(PiMotion.STAGGER_MAX_STEPS) * PiMotion.STAGGER))
    }
    val fetching = source.state == SourceState.Fetching
    val arc by animateFloatAsState(if (fetching) 1f else 0f, PiMotion.inPlace(PiMotion.SETTLE), label = "arc")
    val dim by animateFloatAsState(if (source.state == SourceState.Failed) 0.4f else 1f, PiMotion.inPlace(PiMotion.STANDARD), label = "dim")
    // The angle is read while drawing the arc: a page being read redraws its ring each frame without recomposing.
    val spin: State<Float>? = if (arc > 0f && !reduced) {
        val t = rememberInfiniteTransition(label = "reading")
        t.animateFloat(0f, 360f, infiniteRepeatable(tween(PiMotion.SWEEP, easing = LinearEasing), RepeatMode.Restart), label = "spin")
    } else {
        null
    }
    Box(
        Modifier.size(size).graphicsLayer {
            val p = appear.value
            alpha = p * dim
            scaleX = 0.6f + 0.4f * p
            scaleY = 0.6f + 0.4f * p
        },
        contentAlignment = Alignment.Center,
    ) {
        // On a dark theme a site's icon sits on a light disc (ink1 at 90%) with a 1dp ring: a black mark (GitHub,
        // endoflife.date) would vanish on the dark surface. The globe glyph keeps surface3.
        val backing = if (icon != null && !colors.isLight) colors.ink1.copy(alpha = 0.9f) else colors.surface3
        Box(
            Modifier.size(size).clip(CircleShape).background(backing).border(1.dp, colors.lineStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Image(icon, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size * 0.72f).clip(CircleShape))
            } else {
                Icon(Icons.Rounded.Public, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(size * 0.66f))
            }
        }
        if (arc > 0f) {
            val tint = colors.ink2
            Canvas(Modifier.size(size + 4.dp)) {
                val stroke = 1.5.dp.toPx()
                val inset = stroke / 2
                drawArc(
                    color = tint.copy(alpha = 0.85f * arc),
                    startAngle = (spin?.value ?: 0f) - 90f,
                    sweepAngle = 110f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = Size(this.size.width - stroke, this.size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/** Every source: favicon, title, domain (and whether it is being read or failed), open in the browser. */
@Composable
private fun SourceList(sources: List<WebSource>, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    val uri = LocalUriHandler.current
    val shape = RoundedCornerShape(Radius.tile)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface1, shape)
            .border(1.dp, colors.line, shape)
            .padding(vertical = Space.xs),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        for (source in sources) {
            val open = { runCatching { uri.openUri(source.url) }; Unit }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .pressable(open, shape = RoundedCornerShape(Radius.md), role = Role.Button, label = "Open ${source.title ?: source.domain}")
                    .padding(start = Space.md, end = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SourceIcon(source, 20.dp)
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f).padding(vertical = Space.sm)) {
                    Text(source.title ?: source.domain, style = type.body, color = if (source.state == SourceState.Failed) colors.ink3 else colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val state = when (source.state) {
                        SourceState.Fetching -> " · reading"
                        SourceState.Failed -> " · couldn't read"
                        else -> ""
                    }
                    Text(source.domain + state, style = type.meta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconAction(Icons.AutoMirrored.Rounded.OpenInNew, "Open in browser", onClick = open, size = 18.dp)
            }
        }
    }
}
