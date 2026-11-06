package opensec.pibolt.app.ui.session

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import opensec.pibolt.app.ui.components.SectionLabel
import opensec.pibolt.protocol.session.SlashCommand
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreVert
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
import androidx.compose.runtime.saveable.rememberSaveable
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

/** Top bar: back, computer name with the folder and model beneath (tap: session info), the Quad, overflow. */
@Composable
fun SessionTopBar(
    hostId: String,
    hostName: String,
    subtitle: String,
    link: LinkState,
    mode: QuadMode,
    busy: Boolean,
    spin: Boolean,
    onBack: () -> Unit,
    onInfo: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
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
        IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Computers", onClick = onBack, tint = colors.ink1)
        Column(
            Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .pressable(onInfo, shape = RoundedCornerShape(Radius.sm), pressedScale = 1f, label = "Session info")
                .padding(horizontal = 6.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(linkColor(link, colors), Modifier.sharedHostElement(SharedKeys.dot(hostId)))
                Spacer(Modifier.width(8.dp))
                Text(
                    hostName,
                    style = type.subhead,
                    color = colors.ink1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.sharedHostElement(SharedKeys.name(hostId)),
                )
            }
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = type.meta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 16.dp))
            }
        }
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            QuadSpinner(
                mode,
                size = 16.dp,
                color = when {
                    link is LinkState.Failed -> colors.danger
                    mode == QuadMode.Wait -> colors.warning
                    busy -> colors.ink1
                    else -> colors.ink3
                },
                animate = spin,
            )
        }
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
                MenuRow("Session info", Icons.Rounded.Info, colors.ink1) {
                    menu = false
                    onInfo()
                }
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

/** Under the top bar while not connected: Quad Connect, what is happening (with a live countdown), Retry. */
@Composable
fun ConnectionBanner(link: LinkState, pairing: Boolean, onRetry: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    AnimatedVisibility(
        visible = link !is LinkState.Connected,
        enter = expandVertically(PiMotion.enter(PiMotion.STANDARD)) + fadeIn(PiMotion.enter(PiMotion.QUICK)),
        exit = shrinkVertically(PiMotion.leave(PiMotion.STANDARD)) + fadeOut(PiMotion.leave(PiMotion.QUICK)),
    ) {
        val failed = link is LinkState.Failed
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
                    Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = colors.danger, modifier = Modifier.size(18.dp))
                } else {
                    QuadSpinner(QuadMode.Connect, size = 16.dp, color = colors.warning)
                }
                Spacer(Modifier.width(12.dp))
                val text = when (link) {
                    is LinkState.Connected -> ""
                    LinkState.Connecting -> if (pairing) "Pairing…" else "Connecting…"
                    is LinkState.Retrying -> retryText(link)
                    is LinkState.Failed -> link.message
                }
                Text(text, style = type.body, color = if (failed) colors.ink1 else colors.ink2, modifier = Modifier.weight(1f))
                if (link is LinkState.Retrying || failed) TextAction("Retry", onClick = onRetry, color = colors.accent)
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
fun LoadingState(hostName: String, link: LinkState, onRetry: () -> Unit) {
    val colors = Pi.colors
    val failed = link is LinkState.Failed
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        QuadSpinner(QuadMode.Connect, size = 44.dp, color = if (failed) colors.warning else colors.ink2, animate = !failed)
        Spacer(Modifier.height(20.dp))
        Text(
            if (failed) "Could not open the session" else "Opening the session on $hostName",
            style = Pi.type.body,
            color = colors.ink2,
            textAlign = TextAlign.Center,
        )
        if (failed) {
            Spacer(Modifier.height(20.dp))
            SecondaryButton("Retry", onClick = onRetry)
        }
    }
}

/**
 * No messages yet: the mark, "New session" and the folder, then two quiet lists to start from. "Commands" are the
 * host's slash commands (tap puts "/name " in the composer); "Recent" are prompts this phone sent to this computer
 * (tap fills the composer). Nothing here sends by itself.
 */
@Composable
fun EmptySession(
    cwd: String?,
    commands: List<SlashCommand>,
    recent: List<String>,
    onCommand: (SlashCommand) -> Unit,
    onRecent: (String) -> Unit,
) {
    val colors = Pi.colors
    val type = Pi.type
    val lists = commands.isNotEmpty() || recent.isNotEmpty()
    var allCommands by rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .heightIn(min = maxHeight)
            .padding(horizontal = 16.dp),
        verticalArrangement = if (lists) Arrangement.Top else Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (lists) Spacer(Modifier.height(40.dp))
        BrandMark(size = 40.dp, playKey = "session")
        Spacer(Modifier.height(20.dp))
        Text("New session", style = type.display, color = colors.ink1)
        if (!cwd.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            val shape = RoundedCornerShape(Radius.sm)
            Text(
                baseName(cwd),
                style = type.monoMeta,
                color = colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.background(colors.surface1, shape).border(1.dp, colors.line, shape).padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        if (commands.isNotEmpty()) {
            StartSection("Commands")
            val shown = if (allCommands) commands else commands.take(COMMANDS_SHOWN)
            for (command in shown) {
                StartRow("/${command.name}", command.description?.takeIf { it.isNotBlank() } ?: command.source, mono = true) { onCommand(command) }
            }
            if (shown.size < commands.size) {
                StartRow("Show all ${commands.size} commands", null, mono = false, muted = true) { allCommands = true }
            }
        }
        if (recent.isNotEmpty()) {
            StartSection("Recent")
            for (prompt in recent) StartRow(prompt.lineSequence().first(), null, mono = false) { onRecent(prompt) }
        }
        Spacer(Modifier.height(if (lists) 24.dp else 48.dp))
    }
    }
}

/** Commands listed before "Show all". */
private const val COMMANDS_SHOWN = 6

@Composable
private fun StartSection(title: String) {
    SectionLabel(title, Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 4.dp))
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
            // With a description the label takes only its own width (capped) and the description the rest; equal
            // weights cut short descriptions at half the row.
            modifier = if (description.isNullOrBlank()) Modifier.weight(1f) else Modifier.widthIn(max = 160.dp),
        )
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.width(12.dp))
            Text(description, style = type.meta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
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
