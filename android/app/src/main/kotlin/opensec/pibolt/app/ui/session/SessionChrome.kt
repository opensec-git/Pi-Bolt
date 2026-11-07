package opensec.pibolt.app.ui.session

import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import opensec.pibolt.app.ui.theme.Tnum
import androidx.compose.foundation.rememberScrollState
import opensec.pibolt.app.ui.components.SectionLabel
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.key
import androidx.compose.ui.draw.clip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import opensec.pibolt.app.session.LinkState
import opensec.pibolt.app.ui.components.BrandMark
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.SharedKeys
import opensec.pibolt.app.ui.components.StatusDot
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.components.sharedHostElement
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiColors
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.protocol.session.QueueState

fun linkColor(link: LinkState?, colors: PiColors): Color = when (link) {
    is LinkState.Connected -> colors.success
    is LinkState.Failed -> colors.danger
    LinkState.Connecting, is LinkState.Retrying -> colors.warning
    null -> colors.ink4
}

/**
 * Top bar: back, computer (or, on this phone, project) name with the session name beneath (tap: session info), the
 * context ring, search, the Quad, overflow. On-device sessions offer New session and Rename instead of Disconnect and
 * Forget computer.
 *
 * The status dot shows on this phone only when the engine is not connected (a green dot that is always on says
 * nothing); remote sessions keep it. [contextPercent] is null until a turn has reported usage. [quadVisible] false
 * hides the Quad (keeping its slot) while the live status line is on screen: one spinner at a time. Idle the slot is
 * blank. [status] is what TalkBack hears for the Quad while [busy]; idle it is silent.
 */
@Composable
fun SessionTopBar(
    hostId: String,
    hostName: String,
    local: Boolean,
    subtitle: String,
    link: LinkState,
    mode: QuadMode,
    busy: Boolean,
    spin: Boolean,
    onBack: () -> Unit,
    onInfo: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
    onNewSession: () -> Unit,
    onRename: () -> Unit,
    onSearch: () -> Unit = {},
    onExport: () -> Unit = {},
    onResume: () -> Unit = {},
    onFork: () -> Unit = {},
    onClone: () -> Unit = {},
    contextPercent: Int? = null,
    quadVisible: Boolean = true,
    status: String? = null,
    /** The other live sessions (RunningSessions.SessionSwitcher); draws nothing while there are none. */
    switcher: @Composable () -> Unit = {},
) {
    val colors = Pi.colors
    val type = Pi.type
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(56.dp)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(Icons.AutoMirrored.Rounded.ArrowBack, if (local) "Project" else "Computers", onClick = onBack, tint = colors.ink1)
        Column(
            Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .pressable(onInfo, shape = RoundedCornerShape(Radius.sm), pressedScale = 1f, label = "Session info")
                .padding(horizontal = 6.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            val dot = !local || link !is LinkState.Connected
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (dot) {
                    StatusDot(linkColor(link, colors), Modifier.sharedHostElement(SharedKeys.dot(hostId)))
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    hostName,
                    style = type.subhead,
                    color = colors.ink1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).sharedHostElement(SharedKeys.name(hostId)),
                )
                if (contextPercent != null) {
                    Spacer(Modifier.width(10.dp))
                    ContextMeter(contextPercent)
                }
            }
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = type.meta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = if (dot) 16.dp else 0.dp))
            }
        }
        // The live Quad sits left of the actions, so its empty slot reads as part of the title's space and Search and
        // More always stay side by side. It is drawn only when it says something: busy with the status line off
        // screen (the line owns the one spinner when visible), or a failed link. Idle it is blank, not a still grid.
        val showQuad = quadVisible && (busy || link is LinkState.Failed)
        Box(
            Modifier
                .size(40.dp)
                .graphicsLayer { alpha = if (showQuad) 1f else 0f }
                .clearAndSetSemantics { if (busy && showQuad && status != null) contentDescription = status },
            contentAlignment = Alignment.Center,
        ) {
            if (showQuad) {
                QuadSpinner(
                    mode,
                    size = 16.dp,
                    color = when {
                        link is LinkState.Failed -> colors.danger
                        mode == QuadMode.Wait -> colors.warning
                        else -> colors.ink1
                    },
                    animate = spin,
                )
            }
        }
        switcher()
        IconAction(Icons.Rounded.Search, "Search this session", onClick = onSearch)
        Box {
            IconAction(Icons.Rounded.MoreVert, "More", onClick = { menu = true })
            DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                containerColor = colors.surface3,
                shape = RoundedCornerShape(Radius.md),
                tonalElevation = 0.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, colors.lineStrong),
            ) {
                @Composable
                fun Item(text: String, icon: ImageVector, action: () -> Unit) = MenuRow(text, icon, colors.ink1) {
                    menu = false
                    action()
                }
                if (local) {
                    Item("New session", Icons.Rounded.Add, onNewSession)
                    Item("Resume a session", Icons.Rounded.History, onResume)
                    Item("Fork from a message", Icons.AutoMirrored.Rounded.CallSplit, onFork)
                    Item("Duplicate session", Icons.Rounded.ContentCopy, onClone)
                    Item("Rename session", Icons.Rounded.DriveFileRenameOutline, onRename)
                    Item("Session and usage", Icons.Rounded.Info, onInfo)
                    Item("Export or share", Icons.Rounded.IosShare, onExport)
                } else {
                    Item("Session and usage", Icons.Rounded.Info, onInfo)
                    Item("Export or share", Icons.Rounded.IosShare, onExport)
                    MenuRow("Disconnect", Icons.Rounded.LinkOff, colors.ink1) {
                        menu = false
                        onDisconnect()
                    }
                    MenuRow("Forget computer", Icons.Rounded.DeleteOutline, colors.danger) {
                        menu = false
                        onForget()
                    }
                }
            }
        }
    }
}

/**
 * Context use next to the title: a 14dp ring (ink4 track, ink2 arc; warning from 80%, danger from 90%) and the
 * percentage in meta. The arc settles to a new value (400 ms), never springs.
 */
@Composable
fun ContextMeter(percent: Int) {
    val colors = Pi.colors
    val reduced = Pi.reduceMotion
    val sweep by animateFloatAsState(percent / 100f, if (reduced) snap() else tween(PiMotion.SETTLE, easing = PiMotion.InPlace), label = "context")
    val arc = when {
        percent >= 90 -> colors.danger
        percent >= 80 -> colors.warning
        else -> colors.ink2
    }
    val track = colors.ink4
    Row(
        Modifier.clearAndSetSemantics { contentDescription = "$percent% of context used" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(14.dp)) {
            val stroke = 2.dp.toPx()
            val inset = stroke / 2
            val box = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), box, style = Stroke(stroke))
            if (sweep > 0f) drawArc(arc, -90f, 360f * sweep.coerceIn(0.02f, 1f), false, Offset(inset, inset), box, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Spacer(Modifier.width(4.dp))
        Text("$percent%", style = Pi.type.meta.copy(fontFeatureSettings = Tnum), color = if (percent >= 80) arc else colors.ink3, maxLines = 1)
    }
}

@Composable
private fun MenuRow(text: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, style = Pi.type.body, color = color) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
        contentPadding = MenuDefaults.DropdownMenuItemContentPadding,
        modifier = Modifier.widthIn(min = 200.dp),
    )
}

/**
 * Under the top bar while not connected: Quad Connect, what is happening (with a live countdown), Retry. For an
 * on-device session ([local]) the "connection" is the engine process: "Starting Pi-Bolt…", then "Engine stopped" with
 * Restart.
 */
@Composable
fun ConnectionBanner(link: LinkState, pairing: Boolean, local: Boolean, onRetry: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    AnimatedVisibility(
        visible = link !is LinkState.Connected,
        enter = expandVertically(PiMotion.enter(PiMotion.STANDARD)) + fadeIn(PiMotion.enter(PiMotion.QUICK)),
        exit = shrinkVertically(PiMotion.leave(PiMotion.STANDARD)) + fadeOut(PiMotion.leave(PiMotion.QUICK)),
    ) {
        val failed = link is LinkState.Failed || (local && link is LinkState.Retrying)
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (failed) colors.dangerSoft else colors.surface1)
                    .heightIn(min = 48.dp)
                    .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (failed) {
                    Icon(if (local) Icons.Rounded.PowerSettingsNew else Icons.Rounded.ErrorOutline, contentDescription = null, tint = colors.danger, modifier = Modifier.size(18.dp))
                } else {
                    QuadSpinner(QuadMode.Connect, size = 16.dp, color = colors.warning)
                }
                Spacer(Modifier.width(12.dp))
                val text = when {
                    link is LinkState.Connected -> ""
                    local && link is LinkState.Connecting -> "Starting Pi-Bolt…"
                    local -> "Engine stopped"
                    link is LinkState.Connecting -> if (pairing) "Pairing…" else "Connecting…"
                    link is LinkState.Retrying -> retryText(link)
                    link is LinkState.Failed -> link.message
                    else -> ""
                }
                Text(text, style = type.body, color = if (failed) colors.ink1 else colors.ink2, modifier = Modifier.weight(1f))
                if (link is LinkState.Retrying || failed) TextAction(if (local) "Restart" else "Retry", onClick = onRetry, color = colors.accent)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
        }
    }
}

@Composable
private fun retryText(link: LinkState.Retrying): String {
    if (link.inMs <= 0) return "Reconnecting…"
    val left by produceState(link.inMs, link) {
        val start = SystemClock.uptimeMillis()
        while (true) {
            value = (link.inMs - (SystemClock.uptimeMillis() - start)).coerceAtLeast(0)
            if (value == 0L) break
            delay(200)
        }
    }
    return if (left > 0) "Connection lost. Reconnecting in ${(left + 999) / 1000}s" else "Reconnecting…"
}

/** Before the session snapshot arrives. */
@Composable
fun LoadingState(hostName: String, link: LinkState, local: Boolean, onRetry: () -> Unit) {
    val colors = Pi.colors
    val failed = link is LinkState.Failed
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        QuadSpinner(QuadMode.Connect, size = 44.dp, color = if (failed) colors.warning else colors.ink2, animate = !failed)
        Spacer(Modifier.height(20.dp))
        Text(
            when {
                failed -> "Could not open the session"
                local -> "Starting Pi-Bolt in $hostName"
                else -> "Opening the session on $hostName"
            },
            style = Pi.type.body,
            color = colors.ink2,
            textAlign = TextAlign.Center,
        )
        if (failed) {
            Spacer(Modifier.height(20.dp))
            SecondaryButton(if (local) "Restart" else "Retry", onClick = onRetry)
        }
    }
}

/**
 * No messages yet: the mark, the project's name and (when known) its branch, then "Start with" suggestions picked
 * from the project and the prompts recently sent from this phone. Every tap fills the composer; nothing sends by
 * itself. Commands are one "All commands" link that opens the palette.
 */
@Composable
fun EmptySession(
    title: String,
    branch: String?,
    starters: List<StarterPrompt>,
    recent: List<String>,
    onStarter: (StarterPrompt) -> Unit,
    onRecent: (String) -> Unit,
    onCommands: (() -> Unit)?,
) {
    val colors = Pi.colors
    val type = Pi.type
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val short = maxHeight < 420.dp
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(if (short) 16.dp else 40.dp))
            if (!short) {
                BrandMark(size = 40.dp, playKey = "session")
                Spacer(Modifier.height(16.dp))
            }
            Text(title, style = type.title, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            if (!branch.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Rounded.CallSplit, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(branch, style = type.monoMeta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.semantics { contentDescription = "Branch $branch" })
                }
            }
            if (starters.isNotEmpty()) {
                StartSection("Start with", Icons.Rounded.AutoAwesome)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    for (s in starters) StarterChip(s.label) { onStarter(s) }
                }
            }
            if (recent.isNotEmpty()) {
                StartSection("Recent", Icons.Rounded.History)
                for (prompt in recent) StartRow(prompt.lineSequence().first(), null, mono = false) { onRecent(prompt) }
            }
            if (onCommands != null) {
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth()) { TextAction("All commands", onClick = onCommands, color = colors.ink2) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A suggestion: 36dp visual (48dp to touch), radius 18, surface1 with a 1dp line, label ink1. */
@Composable
private fun StarterChip(label: String, onClick: () -> Unit) {
    val colors = Pi.colors
    val shape = RoundedCornerShape(18.dp)
    Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .heightIn(min = 36.dp)
                .pressable(onClick, shape = shape, label = "Put in the composer: $label")
                .background(colors.surface1, shape)
                .border(1.dp, colors.line, shape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = Pi.type.label, color = colors.ink1, maxLines = 1)
        }
    }
}

@Composable
private fun StartSection(title: String, icon: ImageVector) {
    SectionLabel(title, Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 4.dp), icon = icon)
}

/** A 48dp row: label in body ink1 (mono for commands), the description in meta ink3 on the same line. */
@Composable
private fun StartRow(label: String, description: String?, mono: Boolean, muted: Boolean = false, onClick: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .pressable(onClick, shape = RoundedCornerShape(Radius.sm), pressedScale = 1f)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = if (mono) type.monoSmall else type.body,
            color = if (muted) colors.ink2 else colors.ink1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (description.isNullOrBlank()) Modifier.weight(1f) else Modifier.width(120.dp),
        )
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.width(12.dp))
            Text(description, style = type.meta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * While the keyboard is up (or the window is short) the todo card, status extras, a waiting question and the queue
 * fold into this one 48dp row above the composer, so the transcript keeps its room. It says what each holds
 * ("Todos 1/4 · Fixing caret associativity · Queued 1"); a tap hides the keyboard and opens them again. It appears
 * and leaves with the keyboard, without a tween.
 */
@Composable
fun CompactStack(todoDone: Int, todoTotal: Int, todoNow: String?, queued: Int, asking: String?, extras: Int, onExpand: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    if (todoTotal == 0 && queued == 0 && asking == null && extras == 0) return
    val parts = buildList {
        if (asking != null) add("Pi is asking: $asking")
        if (todoTotal > 0) add("Todos $todoDone/$todoTotal" + (todoNow?.let { " · $it" } ?: ""))
        if (queued > 0) add("Queued $queued")
        if (extras > 0 && size < 2) add(if (extras == 1) "1 status" else "$extras statuses")
    }
    val shape = RoundedCornerShape(Radius.md)
    Row(
        Modifier
            .padding(start = 12.dp, end = 12.dp, bottom = 6.dp)
            .fillMaxWidth()
            .height(40.dp)
            .pressable(onExpand, shape = shape, pressedScale = 1f, label = "Show todos and queue")
            .background(if (asking != null) colors.warningSoft else colors.surface1, shape)
            .border(1.dp, if (asking != null) colors.warning.copy(alpha = 0.4f) else colors.line, shape)
            .clearAndSetSemantics { contentDescription = parts.joinToString(". ") }
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (todoTotal > 0) {
            Canvas(Modifier.size(14.dp)) {
                val stroke = 2.dp.toPx()
                val inset = stroke / 2
                val box = Size(size.width - stroke, size.height - stroke)
                drawArc(colors.ink4, 0f, 360f, false, Offset(inset, inset), box, style = Stroke(stroke))
                if (todoDone > 0) drawArc(colors.success, -90f, 360f * todoDone / todoTotal, false, Offset(inset, inset), box, style = Stroke(stroke))
            }
            Spacer(Modifier.width(10.dp))
        }
        Text(parts.joinToString("  ·  "), style = type.label, color = colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(Icons.Rounded.ExpandLess, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(20.dp))
    }
}

/** Retry progress, extension statuses and widget lines, above the queue. */
@Composable
fun StatusExtras(lines: List<Pair<String, Boolean>>) {
    val colors = Pi.colors
    AnimatedVisibility(
        visible = lines.isNotEmpty(),
        enter = expandVertically(PiMotion.enter(PiMotion.STANDARD)) + fadeIn(),
        exit = shrinkVertically(PiMotion.leave(PiMotion.STANDARD)) + fadeOut(),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 6.dp)) {
            for ((line, warn) in lines.take(6)) {
                Text(line, style = Pi.type.monoMeta, color = if (warn) colors.warning else colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A dialog swiped away without an answer: it still waits. */
@Composable
fun PendingDialogStrip(title: String?, onOpen: () -> Unit) {
    val colors = Pi.colors
    AnimatedVisibility(
        visible = title != null,
        enter = expandVertically(PiMotion.enter(PiMotion.STANDARD)) + fadeIn(),
        exit = shrinkVertically(PiMotion.leave(PiMotion.STANDARD)) + fadeOut(),
    ) {
        val shape = RoundedCornerShape(Radius.md)
        Row(
            Modifier
                .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                .fillMaxWidth()
                .height(48.dp)
                .pressable(onOpen, shape = shape)
                .background(colors.warningSoft, shape)
                .border(1.dp, colors.warning.copy(alpha = 0.4f), shape)
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuadSpinner(QuadMode.Wait, size = 14.dp, color = colors.warning)
            Spacer(Modifier.width(12.dp))
            Text("Pi is asking: ${title.orEmpty()}", style = Pi.type.label, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextAction("Answer", onClick = onOpen, color = colors.warning)
        }
    }
}

/**
 * Queued prompts: a 2dp bar (warning = steer, ink4 = follow-up). One queued prompt is a single 44dp row whose ✕ (or a
 * swipe) takes it back into the composer; two or more get a "Queued · N" header whose Edit does that for all of them.
 * The protocol clears the queue as a whole, so removing is always "back to the composer".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueStrip(queue: QueueState, onEdit: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    AnimatedVisibility(
        visible = !queue.isEmpty,
        enter = expandVertically(PiMotion.enter(PiMotion.STANDARD)) + fadeIn(),
        exit = shrinkVertically(PiMotion.leave(PiMotion.STANDARD)) + fadeOut(),
    ) {
        val shape = RoundedCornerShape(Radius.md)
        val entries = queue.steering.map { true to it } + queue.followUp.map { false to it }
        Column(
            Modifier
                .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(colors.surface1, shape)
                .border(1.dp, colors.line, shape),
        ) {
            if (entries.size == 1) {
                val (steer, text) = entries[0]
                val swipe = rememberSwipeToDismissBoxState()
                key(text) {
                    SwipeToDismissBox(
                        state = swipe,
                        backgroundContent = {
                            Box(Modifier.fillMaxSize().background(colors.surface3).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterEnd) {
                                Text("Back to composer", style = type.meta, color = colors.ink2)
                            }
                        },
                        onDismiss = { onEdit() },
                    ) {
                        QueueRow(steer, text, Modifier.background(colors.surface1), onRemove = onEdit)
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().height(36.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Queued · ${queue.size}", style = type.metaStrong, color = colors.ink3, modifier = Modifier.weight(1f))
                    TextAction("Edit", onClick = onEdit, color = colors.ink1)
                }
                for ((steer, text) in entries.take(4)) QueueRow(steer, text, Modifier, onRemove = null)
                if (entries.size > 4) Text("+${entries.size - 4} more", style = type.meta, color = colors.ink3, modifier = Modifier.padding(start = 28.dp, bottom = 6.dp))
            }
        }
    }
}

@Composable
private fun QueueRow(steer: Boolean, text: String, modifier: Modifier, onRemove: (() -> Unit)?) {
    val colors = Pi.colors
    val type = Pi.type
    Row(modifier.fillMaxWidth().height(44.dp).padding(start = 14.dp, end = if (onRemove != null) 0.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(2.dp).height(22.dp).background(if (steer) colors.warning else colors.ink4, RoundedCornerShape(1.dp)))
        Spacer(Modifier.width(12.dp))
        Text(if (steer) "Steer" else "Follow-up", style = type.micro, color = if (steer) colors.warning else colors.ink3, modifier = Modifier.width(62.dp))
        Text(text.replace('\n', ' '), style = type.body, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (onRemove != null) IconAction(Icons.Rounded.Close, "Remove from queue and edit", onClick = onRemove, size = 18.dp, tint = colors.ink3)
    }
}
