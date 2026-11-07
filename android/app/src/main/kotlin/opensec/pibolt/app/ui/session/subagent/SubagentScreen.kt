package opensec.pibolt.app.ui.session.subagent

import android.os.SystemClock
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.session.LinkState
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.app.ui.components.GlassBar
import opensec.pibolt.app.ui.components.GlassCopyButton
import opensec.pibolt.app.ui.components.GlassIconButton
import opensec.pibolt.app.ui.components.PiTextField
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.ShimmerText
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.markdown.MdBlock
import opensec.pibolt.app.ui.session.ActionsRow
import opensec.pibolt.app.ui.session.FoldRow
import opensec.pibolt.app.ui.session.FoldRowView
import opensec.pibolt.app.ui.session.GapRow
import opensec.pibolt.app.ui.session.MdCache
import opensec.pibolt.app.ui.session.NoticeRow
import opensec.pibolt.app.ui.session.NoticeRowView
import opensec.pibolt.app.ui.session.SessionMemory
import opensec.pibolt.app.ui.session.TRow
import opensec.pibolt.app.ui.session.TextRow
import opensec.pibolt.app.ui.session.TextRowView
import opensec.pibolt.app.ui.session.TurnMetaRow
import opensec.pibolt.app.ui.session.TurnMetaRowView
import opensec.pibolt.app.ui.session.ThinkingRow
import opensec.pibolt.app.ui.session.ThinkingRowView
import opensec.pibolt.app.ui.session.SourcesRow
import opensec.pibolt.app.ui.session.web.SourcesPill
import opensec.pibolt.app.ui.session.ToolGroupRow
import opensec.pibolt.app.ui.session.ToolGroupRowView
import opensec.pibolt.app.ui.session.ToolRow
import opensec.pibolt.app.ui.session.ToolRowView
import opensec.pibolt.app.ui.session.TranscriptModel
import opensec.pibolt.app.ui.session.UserBubble
import opensec.pibolt.app.ui.session.UserRow
import opensec.pibolt.app.ui.session.Viewer
import opensec.pibolt.app.ui.session.ViewerContent
import opensec.pibolt.app.ui.session.riseIn
import opensec.pibolt.app.ui.session.staggerIn
import opensec.pibolt.app.ui.session.stepGlyphY
import opensec.pibolt.app.ui.session.stepRail
import opensec.pibolt.app.ui.theme.LocalPiType
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Space
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.PiType
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Tnum
import opensec.pibolt.protocol.rpc.StreamingBehavior
import opensec.pibolt.protocol.session.SessionState
import opensec.pibolt.protocol.session.Subagent
import opensec.pibolt.protocol.session.SubagentStatus
import opensec.pibolt.protocol.session.ToolStatus
import opensec.pibolt.protocol.session.UserItem

/**
 * Which subagent's transcript is open, over the whole app (it covers the session screen, which stays composed below
 * it: its scroll position, draft and images are untouched). Each agent keeps its own list position and UI memory
 * for the life of the app, so reopening one returns where the reader left it.
 */
@Stable
class SubagentNav {
    var openKey by mutableStateOf<String?>(null)
        private set

    private val lists = HashMap<String, LazyListState>()
    private val memories = HashMap<String, SessionMemory>()

    fun open(key: String) {
        openKey = key
    }

    fun close() {
        openKey = null
    }

    internal fun listState(key: String): LazyListState = lists.getOrPut(key) { LazyListState() }

    internal fun memory(key: String): SessionMemory = memories.getOrPut(key) { SessionMemory(null) }
}

/** The app's [SubagentNav]; null where no host shows the screen (cards are then not clickable). */
val LocalSubagentNav = staticCompositionLocalOf<SubagentNav?> { null }

/**
 * Hosts the subagent screen above everything else (place it after the nav host). It finds the live session that owns
 * the opened run (remote or on this phone) and closes when none does. Enters like a screen (24dp slide + fade, sheet
 * duration); back reverses it.
 */
@Composable
fun SubagentOverlay(app: PiBoltApp, nav: SubagentNav) {
    val sessions by app.sessions.collectAsStateWithLifecycle()
    val key = nav.openKey
    val controller = remember(key, sessions) {
        key?.let { k -> sessions.firstOrNull { k in it.state.value.subagents } }
    }
    LaunchedEffect(key, controller) {
        if (key != null && controller == null) nav.close()
    }
    val reduced = Pi.reduceMotion
    val shift = with(LocalDensity.current) { 24.dp.roundToPx() }
    val shown = remember { mutableStateOf<Pair<SessionController, String>?>(null) }
    if (key != null && controller != null) shown.value = controller to key
    AnimatedVisibility(
        visible = key != null && controller != null,
        enter = if (reduced) fadeIn(tween(120)) else slideInHorizontally(PiMotion.enter(PiMotion.SHEET)) { shift } + fadeIn(PiMotion.enter(PiMotion.SHEET)),
        exit = if (reduced) fadeOut(tween(90)) else slideOutHorizontally(PiMotion.leave(PiMotion.SHEET)) { shift } + fadeOut(PiMotion.leave(PiMotion.SHEET)),
    ) {
        shown.value?.let { (c, k) -> SubagentScreen(c, k, nav, onClose = nav::close) }
    }
}

/**
 * One subagent, full screen: its messages, thinking, tool calls with results and answer, live while it runs (the list
 * follows the newest row while the reader is at the bottom; otherwise "jump to latest"). On this phone they come from
 * its session file (and the message it writes from the engine, when it streams it); over remote control the computer
 * streams them while the screen is open (Pi-Bolt 1.1.5 and later). An older computer sends only the prompt, progress
 * and answer, which the screen says.
 * Below: steer a running background agent or continue a finished one (both through Pi), or stop a foreground one.
 */
@Composable
private fun SubagentScreen(controller: SessionController, key: String, nav: SubagentNav, onClose: () -> Unit) {
    val colors = Pi.colors
    val state: State<SessionState> = controller.state.collectAsStateWithLifecycle()
    val runState = remember(key) { derivedStateOf { state.value.subagents[key] } }
    val run = runState.value ?: return
    val slot by remember(key) { derivedStateOf { SubagentFormat.hueSlot(key, state.value.subagents.keys) } }
    val hue = agentHue(slot, colors)
    val connected by remember { derivedStateOf { controller.link.value is LinkState.Connected } }
    val linkState = controller.link.collectAsStateWithLifecycle()
    val memory = remember(key) { nav.memory(key) }
    val list = remember(key) { nav.listState(key) }
    var viewer by remember { mutableStateOf<ViewerContent?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var fromLeft by remember { mutableStateOf(true) }

    // The session's composer below keeps no focus (and no keyboard) while this screen covers it.
    val focus = LocalFocusManager.current
    LaunchedEffect(Unit) { focus.clearFocus(force = true) }
    DisposableEffect(controller, key) {
        controller.watchSubagent(key, true)
        onDispose { controller.watchSubagent(key, false) }
    }
    PredictiveBackHandler(enabled = viewer == null) { events ->
        try {
            events.collect { e ->
                progress = e.progress
                fromLeft = e.swipeEdge == BackEventCompat.EDGE_LEFT
            }
            onClose()
        } catch (e: CancellationException) {
            progress = 0f
            throw e
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val s = 1f - 0.1f * progress
                scaleX = s
                scaleY = s
                translationX = (if (fromLeft) 1 else -1) * 8.dp.toPx() * progress
                shape = RoundedCornerShape(Radius.xl * progress)
                clip = progress > 0f
            }
            .background(colors.canvas),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            // The header's Quad animates only while the live line (which owns the spinner) is off screen.
            val liveOnScreen by remember(list) { derivedStateOf { list.layoutInfo.visibleItemsInfo.any { it.key == "live" } } }
            TopBar(run, hue, spin = !liveOnScreen, onClose)
            StatusStrip(run)
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
            Box(Modifier.weight(1f)) {
                val source by remember {
                    derivedStateOf {
                        when {
                            controller.isLocal -> SubagentFormat.Source.Device
                            // Before the computer's hello, assume it streams: the note would flash otherwise.
                            state.value.host?.streamsSubagents == false -> SubagentFormat.Source.Unavailable
                            else -> SubagentFormat.Source.Stream
                        }
                    }
                }
                Body(runState, hue, source, memory, list, onOpen = { viewer = it })
            }
            Actions(run, enabled = connected && linkState.value is LinkState.Connected, controller)
            Spacer(Modifier.navigationBarsPadding().padding(bottom = 4.dp))
        }
        AnimatedVisibility(
            visible = viewer != null,
            enter = fadeIn(PiMotion.enter(PiMotion.SHEET)) + slideInVertically(PiMotion.enter(PiMotion.SHEET)) { it / 10 },
            exit = fadeOut(PiMotion.leave(PiMotion.SHEET)) + scaleOut(PiMotion.leave(PiMotion.SHEET), targetScale = 0.94f),
        ) {
            val content = remember { mutableStateOf(viewer) }
            viewer?.let { content.value = it }
            content.value?.let { Viewer(it, onClose = { viewer = null }) }
        }
    }
}

@Composable
private fun TopBar(run: Subagent, hue: Color, spin: Boolean, onClose: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    GlassBar(
        leading = { GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onClose) },
        center = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The agent's state in its own colour, as on its card; part of the title, not a control.
                Box(Modifier.clearAndSetSemantics { }) { AgentGlyph(SubagentFormat.stateOf(run), hue, live = true, size = AgentGlyphSize.Bar, spin = spin) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
                    Text(SubagentFormat.title(run), style = type.subhead, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(SubagentFormat.typeLabel(run.type) + " agent", style = type.meta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        actions = {
            val answer = run.result?.takeIf { !run.isActive && it.isNotBlank() }
            if (answer != null) GlassCopyButton({ answer }, clipLabel = "Agent answer", description = "Copy answer")
        },
    )
}

/** State, time and metrics on one 36dp line; "background · id" at its end. */
@Composable
private fun StatusStrip(run: Subagent) {
    val colors = Pi.colors
    val type = Pi.type
    Row(
        Modifier.fillMaxWidth().height(36.dp).padding(horizontal = Space.page),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(SubagentFormat.statusWord(run.status).replaceFirstChar { it.uppercaseChar() }, style = type.label, color = colors.ink2, maxLines = 1)
        Text("  ·  ", style = type.meta, color = colors.ink3)
        RunTime(run)
        Text("  ·  ", style = type.meta, color = colors.ink3)
        Text(SubagentFormat.metrics(run), style = type.meta.copy(fontFeatureSettings = Tnum), color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
    val second = listOfNotNull(run.model, if (run.background) "background" else null, run.agentId?.let(SubagentFormat::shortId)).joinToString(" · ")
    if (second.isNotEmpty()) {
        Text(
            second,
            style = type.monoMeta,
            color = colors.ink3,
            maxLines = 1,
            modifier = Modifier.padding(start = Space.page, end = Space.page, bottom = 8.dp),
        )
    }
}

/** The transcript: a reversed list whose index 0 is the live line, like the main transcript. */
@Composable
private fun Body(
    runState: State<Subagent?>,
    hue: Color,
    source: SubagentFormat.Source,
    memory: SessionMemory,
    list: LazyListState,
    onOpen: (ViewerContent) -> Unit,
) {
    val colors = Pi.colors
    val settings = Pi.settings
    val parseScope = rememberCoroutineScope()
    val md = remember { MdCache(parseScope) }
    val rows by remember {
        derivedStateOf {
            val run = runState.value ?: return@derivedStateOf emptyList()
            md.version.intValue
            val items = SubagentFormat.detailItems(run)
            // Every turn starts open: the point of this screen is the work. A fold the reader closes stays closed.
            val turnKeys = buildList {
                items.firstOrNull()?.let { if (it !is UserItem) add("t:${it.key}") }
                items.forEach { if (it is UserItem) add(it.key) }
            }
            val open = turnKeys.filterTo(HashSet()) { memory.openFolds[it] != false }
            val groups = memory.openGroups.filterValues { it }.keys
            TranscriptModel.rows(items, run.isActive, open, emptyMap(), md, groups, agentCards = false).filterNot { it is ActionsRow }
        }
    }
    val note by remember(source) { derivedStateOf { runState.value?.let { SubagentFormat.note(it, source) } } }
    if (!memory.primed && rows.isNotEmpty()) {
        rows.forEach { memory.seen.add(it.key) }
        memory.primed = true
    }
    val scope = rememberCoroutineScope()
    val atBottom by remember { derivedStateOf { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset < 24 } }
    var unseen by remember { mutableStateOf(false) }
    LaunchedEffect(list) { snapshotFlow { atBottom }.collect { if (it) unseen = false } }
    LaunchedEffect(list) {
        var lastKey: String? = null
        snapshotFlow { rows.lastOrNull()?.key }.collect { k ->
            if (lastKey != null && k != lastKey && !atBottom) unseen = true
            lastKey = k
        }
    }
    val reduced = Pi.reduceMotion
    val showPill by remember { derivedStateOf { !atBottom && (unseen || list.firstVisibleItemIndex > 2) } }
    val density = LocalDensity.current
    val capped = remember(density) { Density(density.density, density.fontScale.coerceAtMost(1.3f)) }
    val baseType = Pi.type
    val type = remember(baseType, settings.textSize) { baseType.scaled(PiType.factorFor(settings.textSize)) }
    CompositionLocalProvider(LocalDensity provides capped, LocalPiType provides type) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = list,
                reverseLayout = true,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "live", contentType = "live") {
                    runState.value?.let { LiveLine(it, hue, Modifier.padding(top = 6.dp)) }
                }
                val n = rows.size
                items(count = n, key = { i -> rows[n - 1 - i].key }, contentType = { i -> rows[n - 1 - i]::class.simpleName }) { i ->
                    val index = n - 1 - i
                    DetailRow(rows[index], rows.getOrNull(index - 1), memory, settings.toolPreviewLines, settings.thinkingExpanded, onOpen)
                }
                note?.let { text ->
                    item(key = "note", contentType = "note") { Note(text) }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .background(Brush.verticalGradient(0f to colors.canvas, 1f to colors.canvas.copy(alpha = 0f))),
            )
            AnimatedVisibility(
                visible = showPill,
                enter = if (reduced) fadeIn(PiMotion.enter(PiMotion.QUICK)) else fadeIn(PiMotion.enter(PiMotion.QUICK)) + scaleIn(PiMotion.enter(PiMotion.QUICK), initialScale = 0.95f),
                exit = if (reduced) fadeOut(PiMotion.leave(PiMotion.QUICK)) else fadeOut(PiMotion.leave(PiMotion.QUICK)) + scaleOut(PiMotion.leave(PiMotion.QUICK), targetScale = 0.95f),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp),
            ) {
                JumpButton(unseen) {
                    scope.launch {
                        if (reduced) list.requestScrollToItem(0) else list.animateScrollToItem(0)
                        unseen = false
                    }
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    val colors = Pi.colors
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Rounded.Info, contentDescription = null, tint = colors.ink3, modifier = Modifier.padding(top = 1.dp).size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = Pi.type.meta, color = colors.ink3)
    }
}

/**
 * What the agent does now, and that it ends. Live: Quad Agent (its tint, animating as long as the run is live, whether or
 * not anything new arrived), the shimmering activity ("Working…" when nothing more is known; crossfades, quick) and the
 * run time. Ended: its check, cross or dash, "Finished", "Failed: …" or "Stopped", and the final time. Always 32dp, so
 * the list does not jump when it ends.
 */
@Composable
private fun LiveLine(run: Subagent, hue: Color, modifier: Modifier = Modifier) {
    val live = run.isActive
    Row(modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (live) {
                QuadSpinner(QuadMode.Agent, size = AgentGlyphSize.Bar.quad, color = hue, animate = true)
            } else {
                AgentGlyph(SubagentFormat.stateOf(run), hue, live = true, size = AgentGlyphSize.Bar)
            }
        }
        Spacer(Modifier.width(12.dp))
        Crossfade(
            targetState = SubagentFormat.liveLine(run),
            animationSpec = if (Pi.reduceMotion) tween(0) else PiMotion.inPlace(PiMotion.QUICK),
            modifier = Modifier.weight(1f, fill = false),
            label = "liveText",
        ) { t ->
            if (live) {
                ShimmerText(t, Pi.type.label, active = true)
            } else {
                Text(
                    t,
                    style = Pi.type.label,
                    color = if (run.status == SubagentStatus.Failed) Pi.colors.danger else Pi.colors.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (live || SubagentFormat.finalDuration(run) != null) {
            Text("  ·  ", style = Pi.type.label, color = Pi.colors.ink3)
            RunTime(run)
        }
    }
}

@Composable
private fun LazyItemScope.DetailRow(
    row: TRow,
    previous: TRow?,
    memory: SessionMemory,
    previewLines: Int,
    thinkingExpandedDefault: Boolean,
    onOpen: (ViewerContent) -> Unit,
) {
    val reduced = Pi.reduceMotion
    val animated = if (row.live && !reduced) {
        Modifier.animateItem(fadeInSpec = PiMotion.enter(PiMotion.STANDARD), placementSpec = null, fadeOutSpec = PiMotion.leave(PiMotion.STANDARD))
    } else {
        Modifier
    }
    val rail = when (row) {
        is TextRow -> row.rail
        is ThinkingRow -> row.rail
        is ToolRow -> row.rail
        is ToolGroupRow -> row.rail
        is SourcesRow -> row.rail
        else -> null
    }
    val base = if (rail == null) animated else animated.stepRail(rail, Pi.colors.line, if (row is TextRow) null else stepGlyphY(Pi.dims.stepGap)).staggerIn(row.key, rail, memory)
    when (row) {
        is GapRow -> Spacer(base.fillMaxWidth().height(Pi.dims.turnGap))
        is UserRow -> UserBubble(row.item, onLongPress = {}, modifier = base.padding(top = 4.dp, bottom = 6.dp).riseIn(row.key, row.live, memory, 10.dp))
        is TextRow -> {
            val prevBlock: MdBlock? = (previous as? TextRow)?.takeIf { it.itemKey == row.itemKey }?.block
            TextRowView(
                row,
                prevBlock,
                onOpenCode = { code, lang -> onOpen(ViewerContent(lang.ifBlank { "Code" }, null, code)) },
                onLongPress = {},
                modifier = base.fillMaxWidth().riseIn(row.key, row.live, memory, 6.dp),
            )
        }
        is ThinkingRow -> {
            val key = row.item.key
            val expanded = memory.thinkingToggled[key] ?: thinkingExpandedDefault
            ThinkingRowView(
                row.item,
                row.live,
                expanded,
                memory.thinkDurations[key],
                onToggle = { memory.thinkingToggled[key] = !expanded },
                modifier = base.padding(top = Pi.dims.stepGap).riseIn(row.key, row.live, memory, 6.dp),
            )
        }
        is ToolRow -> {
            val item = row.item
            val expanded = memory.toolCards[item.key] ?: false
            val running = item.status == ToolStatus.Running || item.status == ToolStatus.Pending
            val since = if (running && row.live) memory.toolStart.getOrPut(item.key) { SystemClock.uptimeMillis() } else null
            ToolRowView(
                item,
                row.live,
                expanded,
                previewLines,
                onToggle = { memory.toolCards[item.key] = !expanded },
                onOpen = onOpen,
                onLongPress = {},
                modifier = base.padding(top = Pi.dims.stepGap).riseIn(row.key, row.live, memory, 6.dp),
                runningSince = since,
            )
        }
        is ToolGroupRow -> ToolGroupRowView(
            row,
            onToggle = {
                if (!row.expanded) memory.openedAt[row.key] = SystemClock.uptimeMillis()
                memory.openGroups[row.key] = !row.expanded
            },
            modifier = base.padding(top = Pi.dims.stepGap).riseIn(row.key, row.live, memory, 6.dp),
        )
        is NoticeRow -> {
            val key = row.item.key
            val expanded = memory.thinkingToggled[key] ?: false
            NoticeRowView(row.item, expanded = expanded, onToggle = { memory.thinkingToggled[key] = !expanded }, modifier = base.riseIn(row.key, row.live, memory, 6.dp))
        }
        is FoldRow -> FoldRowView(
            row,
            onToggle = {
                if (!row.expanded) memory.openedAt[row.turnKey] = SystemClock.uptimeMillis()
                memory.openFolds[row.turnKey] = !row.expanded
            },
            onOpenFile = { onOpen(ViewerContent("Diff", it.path, it.diff, diff = true)) },
            modifier = base,
        )
        is TurnMetaRow -> TurnMetaRowView(row, modifier = base)
        is SourcesRow -> SourcesPill(row, onToggle = { memory.openGroups[row.key] = !row.expanded }, modifier = base.padding(top = Pi.dims.stepGap + 2.dp, bottom = 2.dp))
        else -> Unit
    }
}

/** "Jump to latest": the main transcript's 40dp round button, with the accent dot for unseen rows. */
@Composable
private fun JumpButton(unseen: Boolean, onClick: () -> Unit) {
    val colors = Pi.colors
    val reduced = Pi.reduceMotion
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(unseen) {
        if (unseen && !reduced) {
            repeat(2) {
                pulse.animateTo(1.3f, PiMotion.inPlace(300))
                pulse.animateTo(1f, PiMotion.inPlace(300))
            }
        }
    }
    Box(Modifier.size(48.dp).pressable(onClick, shape = CircleShape, pressedScale = 0.92f, label = "Jump to latest"), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(40.dp).background(colors.surface3, CircleShape).border(1.dp, colors.lineStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.ArrowDownward, contentDescription = null, tint = colors.ink1, modifier = Modifier.size(20.dp))
        }
        if (unseen) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 6.dp)
                    .size(8.dp)
                    .graphicsLayer {
                        scaleX = pulse.value
                        scaleY = pulse.value
                    }
                    .background(colors.accent, CircleShape),
            )
        }
    }
}

/**
 * How to reach the agent. A running background agent: a message that Pi passes on with `steer_subagent`. A finished
 * agent: a prompt that Pi continues it with (`Agent` with `resume`). A foreground agent: Pi is inside its call, so a
 * message would reach Pi only once it returns; Stop ends Pi's turn and the agent with it (asked twice).
 */
@Composable
private fun Actions(run: Subagent, enabled: Boolean, controller: SessionController) {
    val colors = Pi.colors
    val type = Pi.type
    val haptics = Pi.haptics
    var draft by remember(run.key) { mutableStateOf("") }
    var sent by remember(run.key) { mutableStateOf<String?>(null) }
    LaunchedEffect(sent) {
        if (sent != null) {
            delay(3500)
            sent = null
        }
    }
    val reach = SubagentFormat.reach(run)
    if (reach == SubagentFormat.Reach.None) return
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp)) {
        AnimatedVisibility(sent != null, enter = fadeIn(PiMotion.enter(PiMotion.QUICK)), exit = fadeOut(PiMotion.leave(PiMotion.QUICK))) {
            Text(sent.orEmpty(), style = type.meta, color = colors.success, modifier = Modifier.padding(bottom = 6.dp))
        }
        when (reach) {
            SubagentFormat.Reach.StopOnly -> {
                var armed by remember(run.key) { mutableStateOf(false) }
                LaunchedEffect(armed) {
                    if (armed) {
                        delay(3000)
                        armed = false
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Pi is waiting for this agent to return",
                        style = type.meta,
                        color = colors.ink3,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    StopButton(armed, enabled) {
                        if (armed) {
                            haptics.confirm()
                            controller.abort()
                            armed = false
                            sent = "Stopping Pi's turn and this agent"
                        } else {
                            armed = true
                        }
                    }
                }
            }
            SubagentFormat.Reach.Steer, SubagentFormat.Reach.Resume -> {
                val steer = reach == SubagentFormat.Reach.Steer
                Row(verticalAlignment = Alignment.Bottom) {
                    PiTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = if (steer) "Message this agent" else "Continue this agent",
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    val ready = enabled && draft.isNotBlank()
                    Box(
                        Modifier
                            .size(48.dp)
                            .pressable(
                                onClick = {
                                    val text = draft.trim()
                                    if (text.isEmpty()) return@pressable
                                    haptics.send()
                                    controller.send(if (steer) SubagentFormat.steerPrompt(run, text) else SubagentFormat.resumePrompt(run, text), StreamingBehavior.Steer)
                                    draft = ""
                                    sent = if (steer) "Sent. Pi passes it on." else "Sent. Pi resumes the agent."
                                },
                                shape = CircleShape,
                                enabled = ready,
                                pressedScale = 0.92f,
                                label = if (steer) "Send to agent" else "Continue agent",
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(40.dp).background(if (ready) colors.accent else colors.surface3, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.ArrowUpward, contentDescription = null, tint = if (ready) colors.onAccent else colors.ink3, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            SubagentFormat.Reach.None -> Unit
        }
    }
}

@Composable
private fun StopButton(armed: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = Pi.colors
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .pressable(onClick, shape = CircleShape, enabled = enabled, label = if (armed) "Confirm stop" else "Stop"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .height(36.dp)
                .background(if (armed) colors.dangerSoft else colors.surface3, CircleShape)
                .border(1.dp, if (armed) colors.danger.copy(alpha = 0.5f) else colors.lineStrong, CircleShape)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Rounded.Stop, contentDescription = null, tint = if (armed) colors.danger else colors.ink1, modifier = Modifier.size(16.dp))
            Text(if (armed) "Stop turn?" else "Stop", style = Pi.type.label, color = if (armed) colors.danger else colors.ink1)
        }
    }
}
