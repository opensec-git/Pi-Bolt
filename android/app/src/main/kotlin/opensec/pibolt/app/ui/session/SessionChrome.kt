package opensec.pibolt.app.ui.session

import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import opensec.pibolt.app.ui.theme.Space
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import opensec.pibolt.app.ui.theme.Tnum
import androidx.compose.foundation.rememberScrollState
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Difference
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreHoriz
import opensec.pibolt.app.ui.components.GlassBarInset
import opensec.pibolt.app.ui.components.GlassIconButton
import opensec.pibolt.app.ui.components.PiMenu
import opensec.pibolt.app.ui.components.InsetRow
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
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.protocol.backend.DisconnectReason
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.SharedKeys
import opensec.pibolt.app.ui.components.StatusDot
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.components.sharedHostElement
import opensec.pibolt.app.ui.theme.IconSize
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
 * stills the Quad while the live status line is on screen: one spinner at a time. Idle it rests as a still grid.
 * [status] is what TalkBack hears for the Quad while it animates; otherwise it is silent.
 *
 * A plain chat ([chat]) has ChatGPT's bar: back, the chat's title, then All chats ([onChats], the Chats list) and New
 * chat as glass buttons, and More. Search moves into More with the rest, worded for a chat, and More ends with Delete
 * chat ([onDelete], in danger).
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
    /** The computer's background service lists its projects and sessions: the menu offers them. */
    onProjects: (() -> Unit)? = null,
    /** The computer reviews its working tree read-only (`host.review`): the menu offers its Changes and Files. */
    onChanges: (() -> Unit)? = null,
    onFiles: (() -> Unit)? = null,
    contextPercent: Int? = null,
    quadVisible: Boolean = true,
    status: String? = null,
    /** The other live sessions (RunningSessions.SessionSwitcher); draws nothing while there are none. */
    switcher: @Composable () -> Unit = {},
    chat: Boolean = false,
    onChats: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    /** This session's cost ("$0.42", "—"); null before any use. A tap opens [onCost], the spending sheet. */
    cost: String? = null,
    costSpoken: String? = null,
    onCost: () -> Unit = {},
) {
    val colors = Pi.colors
    val type = Pi.type
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(64.dp)
            .padding(horizontal = GlassBarInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, if (chat) "Back" else if (local) "Project" else "Computers", onClick = onBack)
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
                // "0%" says nothing (a chat on a 1M-token model sits there): the meter shows from 1%.
                if (contextPercent != null && contextPercent > 0) {
                    Spacer(Modifier.width(10.dp))
                    ContextMeter(contextPercent)
                }
                if (cost != null) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        cost,
                        style = type.meta.copy(fontFeatureSettings = Tnum),
                        color = colors.ink3,
                        maxLines = 1,
                        modifier = Modifier
                            .pressable(onCost, shape = RoundedCornerShape(Radius.sm), pressedScale = 1f, label = "Spending")
                            .clearAndSetSemantics { contentDescription = "Session cost ${costSpoken ?: cost}" }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }
            if (subtitle.isNotBlank()) {
                Text(subtitle, style = type.meta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = if (dot) 16.dp else 0.dp))
            }
        }
        // The Quad sits left of the actions, only while Pi is busy and the live status line (which owns the one
        // spinner then) is off screen, or while the link has failed; at rest a still grid beside the glass buttons
        // read as one more button. It is not a control: no press, and TalkBack hears what it reports.
        val live = quadVisible && busy
        val failed = link is LinkState.Failed
        if (live || failed) {
            Box(
                Modifier
                    .size(40.dp)
                    .clearAndSetSemantics { if (failed) contentDescription = "Not connected" else if (status != null) contentDescription = status },
                contentAlignment = Alignment.Center,
            ) {
                QuadSpinner(
                    mode,
                    size = IconSize.sm,
                    color = when {
                        failed -> colors.danger
                        mode == QuadMode.Wait -> colors.warning
                        else -> colors.ink1
                    },
                    animate = spin && live,
                )
            }
        }
        switcher()
        if (chat) {
            onChats?.let { GlassIconButton(Icons.Rounded.Forum, "All chats", onClick = it) }
            GlassIconButton(Icons.Rounded.Add, "New chat", onClick = onNewSession)
        } else {
            GlassIconButton(Icons.Rounded.Search, "Search this session", onClick = onSearch)
        }
        Box {
            GlassIconButton(Icons.Rounded.MoreHoriz, "More", onClick = { menu = true })
            PiMenu(expanded = menu, onDismiss = { menu = false }) {
                @Composable
                fun Item(text: String, icon: ImageVector, action: () -> Unit) = MenuRow(text, icon, colors.ink1) {
                    menu = false
                    action()
                }
                if (chat) {
                    Item("New chat", Icons.Rounded.Add, onNewSession)
                    onChats?.let { Item("All chats", Icons.Rounded.Forum, it) }
                    Item("Search this chat", Icons.Rounded.Search, onSearch)
                    Item("Rename chat", Icons.Rounded.DriveFileRenameOutline, onRename)
                    Item("Fork from a message", Icons.AutoMirrored.Rounded.CallSplit, onFork)
                    Item("Duplicate chat", Icons.Rounded.ContentCopy, onClone)
                    Item("Chat info and usage", Icons.Rounded.Info, onInfo)
                    Item("Export or share", Icons.Rounded.IosShare, onExport)
                    onDelete?.let { delete ->
                        MenuRow("Delete chat", Icons.Rounded.DeleteOutline, colors.danger) {
                            menu = false
                            delete()
                        }
                    }
                } else if (local) {
                    Item("New session", Icons.Rounded.Add, onNewSession)
                    Item("Resume a session", Icons.Rounded.History, onResume)
                    Item("Fork from a message", Icons.AutoMirrored.Rounded.CallSplit, onFork)
                    Item("Duplicate session", Icons.Rounded.ContentCopy, onClone)
                    Item("Rename session", Icons.Rounded.DriveFileRenameOutline, onRename)
                    Item("Session and usage", Icons.Rounded.Info, onInfo)
                    Item("Export or share", Icons.Rounded.IosShare, onExport)
                } else {
                    onProjects?.let { Item("Projects and sessions", Icons.Rounded.FolderOpen, it) }
                    onChanges?.let { Item("Changes", Icons.Rounded.Difference, it) }
                    onFiles?.let { Item("Files", Icons.Rounded.Folder, it) }
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
    if (link.reason == DisconnectReason.Handoff) return SessionController.HANDOFF_NOTICE
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

/** Before the session snapshot arrives. A [chat] has no folder to name: "Starting the chat". */
@Composable
fun LoadingState(hostName: String, link: LinkState, local: Boolean, onRetry: () -> Unit, chat: Boolean = false) {
    val colors = Pi.colors
    val failed = link is LinkState.Failed
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        QuadSpinner(QuadMode.Connect, size = 44.dp, color = if (failed) colors.warning else colors.ink2, animate = !failed)
        Spacer(Modifier.height(20.dp))
        Text(
            when {
                failed -> if (chat) "Could not open the chat" else "Could not open the session"
                chat -> "Starting the chat…"
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
 * No messages yet: assistant-ui's Welcome in the redesign's language. The project's name in largeTitle is the one
 * focal point, centred in the room above the suggestions, with its branch (or the computer) under it in ink3. The
 * suggestions sit just above the composer, in thumb reach, as plain [InsetRow]s with hairlines between them; the
 * prompts this phone sent here before follow under "Recent" (three at most), then "Commands". Every tap fills the
 * composer; nothing sends by itself. Text sits on the 20dp page gutter, as on Home.
 *
 * A chat's welcome ([question] set) is a greeting instead: "Good evening" in ink1 over "What's on your mind?" in ink3,
 * both largeTitle, and suggestions that lead with their topic's glyph (ink2 in the 24dp slot).
 *
 * With [play] it enters as assistant-ui's does: each element fades in and rises 8dp, 120 ms + 70 ms per step
 * ([PiMotion.stagger], [Welcome]); the composer is the last step (SessionScreen).
 */
@Composable
fun EmptySession(
    title: String,
    subtitle: String?,
    starters: List<StarterPrompt>,
    recent: List<String>,
    onStarter: (StarterPrompt) -> Unit,
    onRecent: (String) -> Unit,
    onCommands: (() -> Unit)?,
    play: Boolean,
    question: String? = null,
) {
    val colors = Pi.colors
    val type = Pi.type
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val short = maxHeight < 420.dp
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight),
            // Top, title, rest: the title sits halfway down the room the suggestions leave.
            verticalArrangement = if (short) Arrangement.Top else Arrangement.SpaceBetween,
        ) {
            Spacer(Modifier.height(if (short) Space.lg else 0.dp))
            Column(Modifier.padding(horizontal = Space.page)) {
                Text(
                    title,
                    style = type.largeTitle,
                    color = colors.ink1,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.entrance(0, play).semantics { heading() },
                )
                if (question != null) {
                    Text(question, style = type.largeTitle, color = colors.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.entrance(1, play))
                } else if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = type.monoMeta,
                        color = colors.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier.padding(top = Space.xs).entrance(1, play),
                    )
                }
            }
            Column(Modifier.fillMaxWidth().padding(top = if (short) Space.xxl else Space.x4, bottom = Space.xs)) {
                starters.forEachIndexed { i, s ->
                    InsetRow(
                        s.label,
                        onClick = { onStarter(s) },
                        label = "Put in the composer",
                        divider = i < starters.lastIndex,
                        modifier = Modifier.entrance(Welcome.FIRST_SUGGESTION + i, play),
                        leading = s.icon?.let { icon -> { Icon(icon, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(IconSize.md)) } },
                    )
                }
                var step = Welcome.FIRST_SUGGESTION + starters.size
                if (recent.isNotEmpty()) {
                    Column(Modifier.padding(top = Space.md).entrance(step++, play)) {
                        Text("Recent", style = type.rowMeta, color = colors.ink3, modifier = Modifier.padding(horizontal = Space.page, vertical = Space.sm).semantics { heading() })
                        recent.forEachIndexed { i, prompt ->
                            InsetRow(prompt.lineSequence().first(), onClick = { onRecent(prompt) }, label = "Put in the composer", divider = i < recent.lastIndex)
                        }
                    }
                }
                if (onCommands != null) {
                    InsetRow(
                        "Commands",
                        onClick = onCommands,
                        divider = false,
                        modifier = Modifier.padding(top = Space.xs).entrance(step, play),
                        leading = { Text("/", style = type.mono, color = colors.ink2) },
                    )
                }
            }
        }
    }
}

/**
 * The welcome's entrance for the element at [step]: from transparent and 8dp low to rest, over [PiMotion.SHEET] with
 * the enter easing after [PiMotion.stagger]. Plays once, when the element first composes; without [play] (reduce
 * motion, a session that was not new) it is simply there.
 */
@Composable
fun Modifier.entrance(step: Int, play: Boolean): Modifier {
    val progress = remember { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) progress.animateTo(1f, PiMotion.enter(PiMotion.SHEET, PiMotion.stagger(step)))
    }
    return graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * 8.dp.toPx()
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
    // 44dp, 48dp with the remove button so its target is whole. The label keeps one line at font scale 1.3 (a fixed
    // 62dp wrapped "Follow-up").
    Row(modifier.fillMaxWidth().heightIn(min = if (onRemove != null) 48.dp else 44.dp).padding(start = 14.dp, end = if (onRemove != null) 0.dp else 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(2.dp).height(22.dp).background(if (steer) colors.warning else colors.ink4, RoundedCornerShape(1.dp)))
        Spacer(Modifier.width(12.dp))
        Text(if (steer) "Steer" else "Follow-up", style = type.micro, color = if (steer) colors.warning else colors.ink3, maxLines = 1, softWrap = false, modifier = Modifier.widthIn(min = 62.dp).padding(end = 4.dp))
        Text(text.replace('\n', ' '), style = type.body, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (onRemove != null) IconAction(Icons.Rounded.Close, "Remove from queue and edit", onClick = onRemove, size = 18.dp, tint = colors.ink3)
    }
}
