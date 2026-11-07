package opensec.pibolt.app.ui.session

import androidx.compose.runtime.Immutable
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import opensec.pibolt.app.ui.session.features.SearchJump
import opensec.pibolt.app.ui.session.features.TranscriptSearch
import kotlinx.coroutines.launch
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.markdown.MdBlock
import opensec.pibolt.app.ui.session.subagent.SubagentCard
import opensec.pibolt.app.ui.session.subagent.SubagentNoticeRowView
import opensec.pibolt.app.ui.session.subagent.SubagentStack
import opensec.pibolt.app.ui.theme.LocalPiType
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.PiType
import opensec.pibolt.protocol.session.SessionState
import opensec.pibolt.protocol.session.ToolStatus

/** Live status for the transcript's bottom line. */
@Immutable
data class LiveStatus(val visible: Boolean, val mode: QuadMode, val text: StatusText)

/**
 * The conversation. A reversed LazyColumn whose index 0 is the live status line: while the reader is at the bottom,
 * new rows and a growing reply stay pinned there; once they scroll up the view stays put and a round scroll button
 * appears at the end edge (with a dot when there is unseen content). A send always jumps to the bottom. Each markdown
 * block is its own row with a stable key, so streaming re-lays out only the block being written.
 */
@Composable
fun Transcript(
    state: State<SessionState>,
    memory: SessionMemory,
    status: State<LiveStatus>,
    onOpen: (ViewerContent) -> Unit,
    onActions: (TRow) -> Unit,
    onAskAgain: (String) -> Unit,
    modifier: Modifier = Modifier,
    onStatusOnScreen: (Boolean) -> Unit = {},
    jump: SearchJump? = null,
    onImage: (String, Int) -> Unit = { _, _ -> },
) {
    val settings = Pi.settings
    val parseScope = rememberCoroutineScope()
    val md = remember { MdCache(parseScope) }
    var settledTick by remember { mutableIntStateOf(0) }
    // The finished turn keeps its row animations for a moment so its steps fold away smoothly.
    LaunchedEffect(memory.settledAt.longValue) {
        if (memory.settledAt.longValue == 0L) return@LaunchedEffect
        delay(1200)
        settledTick++
    }
    val rows by remember {
        derivedStateOf {
            val s = state.value
            md.version.intValue
            val liveTurn = s.isRunning || s.streaming.isNotEmpty()
            val recent = settledTick >= 0 && memory.settledAt.longValue > 0 && SystemClock.uptimeMillis() - memory.settledAt.longValue < 1200
            val open = memory.openFolds.filterValues { it }.keys
            val groups = memory.openGroups.filterValues { it }.keys
            TranscriptModel.rows(s.allItems, liveTurn, open, memory.turnDurations, md, groups, memory.pending.value, memory.aliases, s.subagents).let { list ->
                if (!recent || liveTurn) list else markLastTurnLive(list)
            }
        }
    }
    val unique = remember(rows.size, rows.firstOrNull()?.key, rows.lastOrNull()?.key) { TranscriptModel.keysUnique(rows) }
    if (!memory.primed && state.value.hello) {
        rows.forEach { memory.seen.add(it.key) }
        memory.primed = true
    }

    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val dragged by list.interactionSource.collectIsDraggedAsState()
    val atBottom by remember { derivedStateOf { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset < 24 } }
    var unseen by remember { mutableStateOf(false) }
    LaunchedEffect(list) {
        snapshotFlow { atBottom }.collect { if (it) unseen = false }
    }
    LaunchedEffect(list) {
        // Only content added at the end counts as new (opening a fold or card does not).
        var lastKey: String? = null
        snapshotFlow { rows.lastOrNull()?.key }.collect { key ->
            if (lastKey != null && key != lastKey && !atBottom) unseen = true
            lastKey = key
        }
    }
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.any { it.key == "status" } }.collect { onStatusOnScreen(it) }
    }
    LaunchedEffect(list) {
        // Every send from this phone jumps to the bottom, wherever the reader was.
        var last = memory.sends.intValue
        snapshotFlow { memory.sends.intValue }.collect { n ->
            if (n != last) {
                list.requestScrollToItem(0)
                unseen = false
            }
            last = n
        }
    }
    LaunchedEffect(jump) {
        val hit = jump?.hit ?: return@LaunchedEffect
        // A hit in a folded turn or a closed group opens it first; then the row is brought into view.
        if (TranscriptSearch.rowIndex(rows, hit).let { it < 0 || rows[it] is FoldRow }) memory.openFolds[hit.turnKey] = true
        (rows.getOrNull(TranscriptSearch.rowIndex(rows, hit)) as? ToolGroupRow)?.takeIf { !it.expanded }?.let { memory.openGroups[it.key] = true }
        val index = withTimeoutOrNull(1_000) { snapshotFlow { TranscriptSearch.rowIndex(rows, hit) }.first { it >= 0 && rows[it] !is FoldRow } }
            ?: TranscriptSearch.rowIndex(rows, hit)
        if (index >= 0) list.animateScrollToItem(TranscriptSearch.listIndex(rows.size, index), scrollOffset = -list.layoutInfo.viewportSize.height / 3)
    }
    val reduced = Pi.reduceMotion
    /**
     * The list is anchored at the bottom, so a row opened above the reader's position would grow upward and slide
     * away. While scrolled up, keep the toggled row's top edge where it was for the length of the expand animation.
     */
    fun keepInPlace(key: Any) {
        if (atBottom) return
        scope.launch {
            fun top(): Int? = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.let { it.offset + it.size }
            var last = top() ?: return@launch
            val end = SystemClock.uptimeMillis() + PiMotion.STANDARD + 200
            while (SystemClock.uptimeMillis() < end) {
                withFrameNanos { }
                val now = top() ?: break
                if (now != last) {
                    list.scrollBy((now - last).toFloat())
                    last = top() ?: break
                }
            }
        }
    }
    val showPill by remember { derivedStateOf { !atBottom && (unseen || dragged || list.firstVisibleItemIndex > 2) } }

    val density = LocalDensity.current
    val capped = remember(density) { Density(density.density, density.fontScale.coerceAtMost(1.3f)) }
    val baseType = Pi.type
    val type = remember(baseType, settings.textSize) { baseType.scaled(PiType.factorFor(settings.textSize)) }

    CompositionLocalProvider(LocalDensity provides capped, LocalPiType provides type) {
        Box(modifier.fillMaxSize()) {
            LazyColumn(
                state = list,
                reverseLayout = true,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "status", contentType = "status") {
                    val s by status
                    StatusLine(s.visible, s.mode, s.text, memory.runStart.longValue, Modifier.padding(top = 6.dp))
                }
                val n = rows.size
                items(
                    count = n,
                    key = { i -> if (unique) rows[n - 1 - i].key else "row$i" },
                    contentType = { i -> rows[n - 1 - i]::class.simpleName },
                ) { i ->
                    val index = n - 1 - i
                    val row = rows[index]
                    val previous = rows.getOrNull(index - 1)
                    RowView(row, previous, memory, settings.toolPreviewLines, settings.thinkingExpanded, onOpen, onActions, onAskAgain, reduced, onImage) {
                        keepInPlace(if (unique) row.key else "row$i")
                    }
                }
            }
            // Rows slide under a short canvas fade instead of being cut at the top bar.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .background(Brush.verticalGradient(0f to Pi.colors.canvas, 1f to Pi.colors.canvas.copy(alpha = 0f))),
            )
            AnimatedVisibility(
                visible = showPill,
                enter = if (reduced) {
                    fadeIn(PiMotion.enter(PiMotion.QUICK))
                } else {
                    fadeIn(PiMotion.enter(PiMotion.QUICK)) +
                        scaleIn(PiMotion.enter(PiMotion.QUICK), initialScale = 0.95f) +
                        slideInVertically(PiMotion.enter(PiMotion.QUICK)) { with(density) { 16.dp.roundToPx() } }
                },
                exit = if (reduced) {
                    fadeOut(PiMotion.leave(PiMotion.QUICK))
                } else {
                    fadeOut(PiMotion.leave(PiMotion.QUICK)) +
                        scaleOut(PiMotion.leave(PiMotion.QUICK), targetScale = 0.95f) +
                        slideOutVertically(PiMotion.leave(PiMotion.QUICK)) { with(density) { 16.dp.roundToPx() } }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp),
            ) {
                ScrollButton(unseen) {
                    scope.launch {
                        if (reduced) list.requestScrollToItem(0) else list.animateScrollToItem(0)
                        unseen = false
                    }
                }
            }
        }
    }
}

private fun markLastTurnLive(rows: List<TRow>): List<TRow> {
    val lastUser = rows.indexOfLast { it is UserRow }
    if (lastUser < 0) return rows
    return rows.mapIndexed { i, r ->
        if (i < lastUser) {
            r
        } else {
            when (r) {
                is UserRow -> r.copy(live = true)
                is TextRow -> r.copy(live = true)
                is ToolRow -> r.copy(live = true)
                is ThinkingRow -> r.copy(live = true)
                is NoticeRow -> r.copy(live = true)
                is ToolGroupRow -> r.copy(live = true)
                is SubagentRow -> r.copy(live = true)
                is SubagentStackRow -> r.copy(live = true)
                is SubagentNoticeRow -> r.copy(live = true)
                else -> r
            }
        }
    }
}

/**
 * Scroll to the latest: a 40dp round button (surface3, 1dp lineStrong, arrow) at the end edge, 48dp to touch. An
 * accent dot at its top-end marks unseen content; it pulses twice when it appears (static under reduce motion).
 */
@Composable
private fun ScrollButton(unseen: Boolean, onClick: () -> Unit) {
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
    Box(
        Modifier
            .size(48.dp)
            .pressable(onClick, shape = CircleShape, pressedScale = 0.92f, label = if (unseen) "Scroll to new messages" else "Scroll to latest"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .background(colors.surface3, CircleShape)
                .border(1.dp, colors.lineStrong, CircleShape),
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

@Composable
private fun LazyItemScope.RowView(
    row: TRow,
    previous: TRow?,
    memory: SessionMemory,
    previewLines: Int,
    thinkingExpandedDefault: Boolean,
    onOpen: (ViewerContent) -> Unit,
    onActions: (TRow) -> Unit,
    onAskAgain: (String) -> Unit,
    reduced: Boolean,
    onImage: (String, Int) -> Unit,
    keep: () -> Unit,
) {
    val animate = row.live && !reduced
    val animated = if (animate) {
        Modifier.animateItem(
            fadeInSpec = PiMotion.enter(PiMotion.STANDARD),
            // No placement animation: rows above a growing reply would visibly slide over it.
            placementSpec = null,
            fadeOutSpec = PiMotion.leave(PiMotion.STANDARD),
        )
    } else {
        Modifier
    }
    val rail = when (row) {
        is TextRow -> row.rail
        is ThinkingRow -> row.rail
        is ToolRow -> row.rail
        is ToolGroupRow -> row.rail
        else -> null
    }
    val railColor = Pi.colors.line
    val glyphY = stepGlyphY(Pi.dims.stepGap)
    val base = if (rail == null) {
        animated
    } else {
        animated.stepRail(rail, railColor, if (row is TextRow) null else glyphY).staggerIn(row.key, rail, memory)
    }
    when (row) {
        is GapRow -> Spacer(base.fillMaxWidth().height(Pi.dims.turnGap))
        is UserRow -> UserBubble(
            row.item,
            onLongPress = { onActions(row) },
            modifier = base.padding(top = 4.dp, bottom = 6.dp).riseIn(row.key, row.live, memory, 10.dp),
            thumbs = memory.localImages[row.key],
            onImage = { onImage(row.key, it) },
        )
        is PendingUserRow -> PendingBubble(
            row.pending.text,
            modifier = base.padding(top = 4.dp, bottom = 6.dp).riseIn(row.key, row.live, memory, 10.dp),
            thumbs = memory.localImages[row.key],
            imageCount = row.pending.images,
        )
        is TextRow -> {
            val prevBlock: MdBlock? = (previous as? TextRow)?.takeIf { it.itemKey == row.itemKey }?.block
            TextRowView(
                row,
                prevBlock,
                onOpenCode = { code, lang -> onOpen(ViewerContent(lang.ifBlank { "Code" }, null, code)) },
                onLongPress = { onActions(row) },
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
                onToggle = {
                    memory.thinkingToggled[key] = !expanded
                    keep()
                },
                modifier = base.padding(top = Pi.dims.stepGap).riseIn(row.key, row.live, memory, 6.dp),
            )
        }
        is ToolRow -> {
            val item = row.item
            val auto = row.live && item.status == ToolStatus.Running
            val expanded = memory.toolCards[item.key] ?: auto
            val since = if (item.status == ToolStatus.Running) memory.toolStart.getOrPut(item.key) { SystemClock.uptimeMillis() } else null
            ToolRowView(
                item,
                row.live,
                expanded,
                previewLines,
                onToggle = {
                    memory.toolCards[item.key] = !expanded
                    keep()
                },
                onOpen = onOpen,
                onLongPress = { onActions(row) },
                modifier = base.padding(top = Pi.dims.stepGap).riseIn(row.key, row.live, memory, 6.dp),
                runningSince = since,
            )
        }
        is ToolGroupRow -> ToolGroupRowView(
            row,
            onToggle = {
                if (!row.expanded) memory.openedAt[row.key] = SystemClock.uptimeMillis()
                memory.openGroups[row.key] = !row.expanded
                keep()
            },
            modifier = base.padding(top = Pi.dims.stepGap).riseIn(row.key, row.live, memory, 6.dp),
        )
        is NoticeRow -> {
            val key = row.item.key
            val expanded = memory.thinkingToggled[key] ?: false
            NoticeRowView(
                row.item,
                expanded = expanded,
                onToggle = {
                    memory.thinkingToggled[key] = !expanded
                    keep()
                },
                modifier = base.riseIn(row.key, row.live, memory, 6.dp),
            )
        }
        is FoldRow -> FoldRowView(
            row,
            onToggle = {
                if (!row.expanded) memory.openedAt[row.turnKey] = SystemClock.uptimeMillis()
                memory.openFolds[row.turnKey] = !row.expanded
                keep()
            },
            onOpenFile = { onOpen(ViewerContent("Diff", it.path, it.diff, diff = true)) },
            modifier = base,
        )
        is ActionsRow -> ReplyActions(row, memory, onAskAgain = onAskAgain, onMore = { onActions(row) }, modifier = base)
        is SubagentRow -> SubagentCard(row, modifier = base.padding(top = Pi.dims.stepGap + 2.dp, bottom = 2.dp).riseIn(row.key, row.live, memory, 6.dp))
        is SubagentStackRow -> SubagentStack(
            row,
            onToggle = {
                memory.openGroups[row.key] = !row.expanded
                keep()
            },
            modifier = base.padding(top = Pi.dims.stepGap + 2.dp, bottom = 2.dp).riseIn(row.key, row.live, memory, 6.dp),
        )
        is SubagentNoticeRow -> SubagentNoticeRowView(row, modifier = base.riseIn(row.key, row.live, memory, 6.dp))
    }
}
