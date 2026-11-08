package opensec.pibolt.app.ui.session

import opensec.pibolt.app.ui.session.views.InlineViewRow
import opensec.pibolt.protocol.session.MobileViews
import androidx.compose.runtime.Immutable
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import opensec.pibolt.app.ui.projects.FileDoc
import opensec.pibolt.app.ui.projects.ProjectFiles
import opensec.pibolt.app.ui.session.features.ViewerImages
import opensec.pibolt.protocol.rpc.ImageAttachment
import java.io.File
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
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
import opensec.pibolt.app.ui.components.glass
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.markdown.MdBlock
import opensec.pibolt.app.ui.session.review.TurnReviewEntry
import opensec.pibolt.app.ui.session.web.SourcesPill
import opensec.pibolt.app.ui.session.subagent.SubagentCard
import opensec.pibolt.app.ui.session.subagent.SubagentNoticeRowView
import opensec.pibolt.app.ui.session.subagent.SubagentStack
import opensec.pibolt.app.ui.theme.LocalPiType
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Space
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.PiType
import opensec.pibolt.protocol.session.NoticeItem
import opensec.pibolt.protocol.session.SessionState
import opensec.pibolt.protocol.session.ToolStatus

/**
 * True while the live status line is on screen. It owns the one animated spinner then: step rows, the todo row and
 * the top bar show still frames. Read the value in the draw or layout phase where possible.
 */
val LocalStatusOnScreen = compositionLocalOf<State<Boolean>> { mutableStateOf(false) }

/** Live status for the transcript's bottom line. */
@Immutable
data class LiveStatus(val visible: Boolean, val mode: QuadMode, val text: StatusText)

/**
 * The conversation. A reversed LazyColumn whose index 0 is the live status line: while the reader is at the bottom,
 * new rows and a growing reply stay pinned there; once they scroll up the view stays put and a round scroll button
 * appears centred above the composer ("New messages" when there is unseen content). A send always jumps to the bottom. Each markdown
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
    onToolImages: (ViewerImages) -> Unit = {},
    errorContext: ErrorContext = ErrorContext(null, null, local = false, running = false),
    onErrorAction: (ErrorAction, NoticeItem) -> Unit = { _, _ -> },
    /** A file on this phone tapped in a tool card: the screen opens it in the full file viewer; null shows it as text. */
    onOpenLocalFile: ((File) -> Unit)? = null,
) {
    val settings = Pi.settings
    val parseScope = rememberCoroutineScope()
    val md = remember { MdCache(parseScope) }
    val uniqueCache = remember { UniqueCache() }
    var settledTick by remember { mutableIntStateOf(0) }
    // The finished turn keeps its row animations for a moment so its steps fold away smoothly.
    LaunchedEffect(memory.settledAt.longValue) {
        if (memory.settledAt.longValue == 0L) return@LaunchedEffect
        delay(1200)
        settledTick++
    }
    // Rows are read only inside the list, effects and snapshot flows, never while composing this function: a streamed
    // frame then re-runs the list's item lookup instead of recomposing the whole transcript, and finished rows (equal
    // to their previous value) skip.
    // Keyed on the memory: after a fork or a resume the rows read the new session's folds, aliases and pending send.
    val rowsState = remember(memory) {
        derivedStateOf {
            val s = state.value
            md.version.intValue
            val liveTurn = s.isRunning || s.streaming.isNotEmpty()
            val recent = settledTick >= 0 && memory.settledAt.longValue > 0 && SystemClock.uptimeMillis() - memory.settledAt.longValue < 1200
            val open = memory.openFolds.filterValues { it }.keys
            val groups = memory.openGroups.filterValues { it }.keys
            // The failed attempts of a retry (and a "Compaction failed" after an overflow) are one error notice.
            // With thinking off, an empty reasoning block gets no "Thinking" row.
            val (all, errors) = ErrorNotices.collapse(s.allItems, s.retry)
            val items = ThinkingRules.visible(all, ThinkingRules.on(s.thinkingLevel, s.model?.reasoning))
            val list = TranscriptModel.rows(
                items, liveTurn, open, memory.turnDurations, md, groups, memory.pending.value, memory.aliases, s.subagents,
                inline = s.views.values.filter { it.slot == MobileViews.SLOT_INLINE },
            ).let { list ->
                if (!recent || liveTurn) list else markLastTurnLive(list)
            }
            RowsState(list, uniqueCache.unique(list), errors)
        }
    }
    val hello by remember { derivedStateOf { state.value.hello } }
    LaunchedEffect(hello) {
        if (hello && !memory.primed) {
            rowsState.value.rows.forEach { memory.seen.add(it.key) }
            memory.primed = true
        }
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
        snapshotFlow { rowsState.value.rows.lastOrNull()?.key }.collect { key ->
            if (lastKey != null && key != lastKey && !atBottom) unseen = true
            lastKey = key
        }
    }
    val statusOnScreen = remember { mutableStateOf(false) }
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.any { it.key == "status" } }.collect {
            statusOnScreen.value = it
            onStatusOnScreen(it)
        }
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
        fun rows() = rowsState.value.rows
        // A hit in a folded turn or a closed group opens it first; then the row is brought into view.
        if (TranscriptSearch.rowIndex(rows(), hit).let { it < 0 || rows()[it] is FoldRow }) memory.openFolds[hit.turnKey] = true
        (rows().getOrNull(TranscriptSearch.rowIndex(rows(), hit)) as? ToolGroupRow)?.takeIf { !it.expanded }?.let { memory.openGroups[it.key] = true }
        val index = withTimeoutOrNull(1_000) { snapshotFlow { TranscriptSearch.rowIndex(rows(), hit) }.first { it >= 0 && rows()[it] !is FoldRow } }
            ?: TranscriptSearch.rowIndex(rows(), hit)
        if (index >= 0) list.animateScrollToItem(TranscriptSearch.listIndex(rows().size, index), scrollOffset = -list.layoutInfo.viewportSize.height / 3)
    }
    val reduced = Pi.reduceMotion
    /**
     * The list is anchored at the bottom, so a row opened above the reader's position would grow upward and slide
     * away. While scrolled up, keep the toggled row's top edge where it was for the length of the expand animation.
     * Remembered once, so rows that capture it stay skippable.
     */
    val keepInPlace: (Any) -> Unit = remember(list, scope) {
        { key ->
            if (!atBottom) {
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
        }
    }
    val currentOpen by rememberUpdatedState(onOpen)
    val currentImages by rememberUpdatedState(onToolImages)
    val currentLocalFile by rememberUpdatedState(onOpenLocalFile)
    // A path in a tool card opens the file: read from this phone when the session's folder is here (on-device Pi),
    // else a note that the file lives on the host.
    val openFile: (String) -> Unit = remember(scope) {
        { path ->
            scope.launch {
                val root = state.value.host?.cwd
                val file = if (path.startsWith("/")) File(path) else root?.let { File(it, path) }
                val readable = file != null && withContext(Dispatchers.IO) { file.isFile && file.canRead() }
                val local = currentLocalFile
                if (readable && local != null) {
                    local(file)
                } else {
                    currentOpen(
                        if (readable) fileView(file, path) else ViewerContent(baseName(path), path, "This file is on the computer, not this phone."),
                    )
                }
            }
        }
    }
    val openImages: (List<ImageAttachment>, Int) -> Unit = remember { { images, index -> currentImages(ViewerImages(images, emptyList(), index)) } }
    val showPill by remember { derivedStateOf { !atBottom && (unseen || dragged || list.firstVisibleItemIndex > 2) } }

    val density = LocalDensity.current
    val capped = remember(density) { Density(density.density, density.fontScale.coerceAtMost(1.3f)) }
    val baseType = Pi.type
    val type = remember(baseType, settings.textSize) { baseType.scaled(PiType.factorFor(settings.textSize)) }

    val canvas = Pi.colors.canvas
    CompositionLocalProvider(LocalDensity provides capped, LocalPiType provides type, LocalStatusOnScreen provides statusOnScreen) {
        BoxWithConstraints(modifier.fillMaxSize()) {
            // Past 840dp (landscape phones, tablets, foldables) the text keeps a 720dp reading width, centred; the
            // list itself stays full width so it scrolls from anywhere.
            val gutter = if (maxWidth > 840.dp) ((maxWidth - 720.dp) / 2).coerceAtLeast(Space.page) else Space.page
            LazyColumn(
                state = list,
                reverseLayout = true,
                contentPadding = PaddingValues(start = gutter, end = gutter, top = 12.dp + TOP_FADE, bottom = 4.dp + BOTTOM_FADE),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "status", contentType = -1) {
                    val s by status
                    StatusLine(s.visible, s.mode, s.text, memory.runStart.longValue, Modifier.padding(top = 6.dp))
                }
                val (rows, unique, errorGroups) = rowsState.value
                val n = rows.size
                items(
                    count = n,
                    key = { i -> if (unique) rows[n - 1 - i].key else "row$i" },
                    contentType = { i -> rows[n - 1 - i].contentType },
                ) { i ->
                    val index = n - 1 - i
                    val row = rows[index]
                    if (row is NoticeRow && ErrorNotices.isProviderError(row.item)) {
                        ErrorNoticeView(
                            row.item,
                            errorGroups[row.item.key],
                            errorContext,
                            onAction = { onErrorAction(it, row.item) },
                            modifier = if (row.live && !reduced) Modifier.animateItem(fadeInSpec = PiMotion.enter(PiMotion.STANDARD), placementSpec = null, fadeOutSpec = PiMotion.leave(PiMotion.STANDARD)) else Modifier,
                        )
                    } else {
                        // Only prose spacing looks at the row before, and only at its block.
                        val prevBlock = if (row is TextRow) (rows.getOrNull(index - 1) as? TextRow)?.takeIf { it.itemKey == row.itemKey }?.block else null
                        val keepKey: Any = if (unique) row.key else "row$i"
                        RowView(row, prevBlock, memory, settings.toolPreviewLines, settings.thinkingExpanded, onOpen, onActions, onAskAgain, reduced, onImage, openFile, openImages) {
                            keepInPlace(keepKey)
                        }
                    }
                }
            }
            // Rows slide under canvas fades instead of being cut by the top bar and the composer.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(TOP_FADE)
                    .background(Brush.verticalGradient(0f to canvas, 1f to canvas.copy(alpha = 0f))),
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(BOTTOM_FADE)
                    .background(Brush.verticalGradient(0f to canvas.copy(alpha = 0f), 1f to canvas)),
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
                // Centred above the composer, out of the text column's end edge where it covered table cells and code.
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
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

/** A tool card's file as viewer text: the first page of a text file ([ProjectFiles.open]), else what it is. */
private suspend fun fileView(file: File, path: String): ViewerContent {
    val text = when (val doc = ProjectFiles.open(file)) {
        is FileDoc.Text -> if (doc.complete) doc.text else doc.text + "\n\n… the first ${ProjectFiles.size(doc.next ?: doc.size)} of ${ProjectFiles.size(doc.size)}"
        is FileDoc.Image -> "Image, ${doc.width} × ${doc.height}, ${ProjectFiles.size(doc.size)}."
        is FileDoc.Binary -> "Binary file, ${ProjectFiles.size(doc.size)}."
        is FileDoc.Unreadable -> doc.reason
    }
    return ViewerContent(file.name, path, text)
}

/** The transcript's rows, whether their keys are unique (the list needs unique keys) and the collapsed error groups. */
@Immutable
private data class RowsState(val rows: List<TRow>, val unique: Boolean, val errors: Map<String, ErrorGroup>)

/** [TranscriptModel.keysUnique], re-checked only when the row count or the first or last key changes. */
private class UniqueCache {
    private var size = -1
    private var first: String? = null
    private var last: String? = null
    private var value = true

    fun unique(rows: List<TRow>): Boolean {
        val f = rows.firstOrNull()?.key
        val l = rows.lastOrNull()?.key
        if (rows.size != size || f != first || l != last) {
            size = rows.size
            first = f
            last = l
            value = TranscriptModel.keysUnique(rows)
        }
        return value
    }
}

/** Canvas fades over the list's top and bottom edges. */
private val TOP_FADE = 24.dp
private val BOTTOM_FADE = 16.dp

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
                is SourcesRow -> r.copy(live = true)
                is SubagentRow -> r.copy(live = true)
                is SubagentStackRow -> r.copy(live = true)
                is SubagentNoticeRow -> r.copy(live = true)
                else -> r
            }
        }
    }
}

/**
 * Back to the latest, centred above the composer: a 36dp round glass button (the kit's glass, arrow 18dp ink1) in a
 * 4dp ring of canvas, so text passing under it is cut cleanly (as on the desktop). When content arrived while the
 * reader was away it widens into a pill that says so, "New messages" (assistant-ui's pattern). 48dp to touch.
 */
@Composable
private fun ScrollButton(unseen: Boolean, onClick: () -> Unit) {
    val colors = Pi.colors
    val reduced = Pi.reduceMotion
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .pressable(onClick, shape = CircleShape, pressedScale = 0.95f, label = if (unseen) "Scroll to new messages" else "Scroll to latest"),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .then(if (reduced) Modifier else Modifier.animateContentSize(PiMotion.inPlace(PiMotion.QUICK)))
                .background(colors.canvas, CircleShape)
                .padding(4.dp)
                .height(36.dp)
                .glass(CircleShape, colors)
                .padding(horizontal = if (unseen) 14.dp else 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.ArrowDownward, contentDescription = null, tint = colors.ink1, modifier = Modifier.size(18.dp))
            if (unseen) {
                Spacer(Modifier.width(6.dp))
                Text("New messages", style = Pi.type.metaStrong, color = colors.ink1, maxLines = 1)
            }
        }
    }
}

@Composable
private fun LazyItemScope.RowView(
    row: TRow,
    prevBlock: MdBlock?,
    memory: SessionMemory,
    previewLines: Int,
    thinkingExpandedDefault: Boolean,
    onOpen: (ViewerContent) -> Unit,
    onActions: (TRow) -> Unit,
    onAskAgain: (String) -> Unit,
    reduced: Boolean,
    onImage: (String, Int) -> Unit,
    onOpenFile: (String) -> Unit,
    onImages: (List<ImageAttachment>, Int) -> Unit,
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
        is SourcesRow -> row.rail
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
            dictated = memory.dictated[row.key] == true,
        )
        is PendingUserRow -> PendingBubble(
            row.pending.text,
            modifier = base.padding(top = 4.dp, bottom = 6.dp).riseIn(row.key, row.live, memory, 10.dp),
            thumbs = memory.localImages[row.key],
            imageCount = row.pending.images,
            dictated = memory.dictated[row.key] == true,
        )
        is TextRow -> {
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
                onOpenFile = onOpenFile,
                onImages = onImages,
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
        is SourcesRow -> SourcesPill(
            row,
            onToggle = {
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
        is FoldRow -> Column(base) {
            FoldRowView(
                row,
                onToggle = {
                    if (!row.expanded) memory.openedAt[row.turnKey] = SystemClock.uptimeMillis()
                    memory.openFolds[row.turnKey] = !row.expanded
                    keep()
                },
                onOpenFile = { onOpen(ViewerContent("Diff", it.path, it.diff, diff = true)) },
            )
            // Per-turn review lives in the opened fold; the changes pill over the composer covers the session.
            if (row.expanded) TurnReviewEntry(row.turnKey, editedFiles = row.files.isNotEmpty())
        }
        is TurnMetaRow -> TurnMetaRowView(row, modifier = base)
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
        is ViewRow -> InlineViewRow(row.viewKey, modifier = base.padding(top = Pi.dims.stepGap + 2.dp, bottom = 2.dp).riseIn(row.key, row.live, memory, 6.dp))
    }
}
