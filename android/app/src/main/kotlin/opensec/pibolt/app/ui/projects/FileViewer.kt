package opensec.pibolt.app.ui.projects

import android.content.ClipData
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.WrapText
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VerticalAlignCenter
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import opensec.pibolt.app.ui.components.ConfirmSheet
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.PiSnackHost
import opensec.pibolt.app.ui.components.PiTextField
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.Segmented
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.SnackKind
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.ui.components.show
import opensec.pibolt.app.ui.markdown.MdBlock
import opensec.pibolt.app.ui.markdown.MdBlockView
import opensec.pibolt.app.ui.markdown.MdDoc
import opensec.pibolt.app.ui.markdown.Markdown
import opensec.pibolt.app.ui.markdown.rememberHighlighted
import opensec.pibolt.app.ui.session.Viewer
import opensec.pibolt.app.ui.session.ViewerContent
import opensec.pibolt.app.ui.session.features.ImageZoom
import opensec.pibolt.app.ui.session.features.ShareFiles
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius
import java.io.File

private const val MAX_LINE = 2_000

/**
 * A project file, full screen. Markdown renders with the app's markdown engine (Rendered / Source); code is
 * highlighted by its extension with line numbers; any text can be searched, jumped through by line, wrapped and edited
 * (with Save and a guard for unsaved changes). Images show zoomable; binary files say so and offer Share. Large files
 * load a page at a time. [onChanged] runs after a save, so the list can refresh sizes.
 */
@Composable
fun FileViewer(root: File, rel: String, onClose: () -> Unit, onChanged: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val file = remember(root, rel) { File(root, rel) }
    val name = file.name
    val kind = remember(name) { FileTypes.kind(name) }
    val language = remember(name) { if (kind == FileKind.Markdown) "markdown" else FileTypes.language(name) }

    var doc by remember(file) { mutableStateOf<FileDoc?>(null) }
    LaunchedEffect(file) { doc = ProjectFiles.open(file) }
    val text = (doc as? FileDoc.Text)
    var loadingMore by remember { mutableStateOf(false) }

    var rendered by rememberSaveable { mutableStateOf(true) }
    var wrap by rememberSaveable { mutableStateOf(kind != FileKind.Code) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var current by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var goTo by remember { mutableStateOf(false) }
    var flashLine by remember { mutableIntStateOf(-1) }
    // Kept across an activity recreation; dropped when the viewer closes for good.
    val edit = rememberSaveable(file, saver = FileEdit.saver(file.path)) { FileEdit() }
    val activity = LocalActivity.current
    DisposableEffect(file) { onDispose { if (activity?.isChangingConfigurations != true) FileEdit.release(file.path) } }
    val editing = edit.editing
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf<(() -> Unit)?>(null) }
    var codeView by remember { mutableStateOf<ViewerContent?>(null) }
    val listState = rememberLazyListState()

    val showRendered = kind == FileKind.Markdown && rendered && !editing
    val lines = remember(text?.text) { text?.let { FileSearch.lines(it.text) }.orEmpty() }
    val matches = remember(text?.text, query, searching) { if (searching && text != null) FileSearch.find(text.text, lines, query) else emptyList() }
    val byLine = remember(matches) { matches.withIndex().groupBy({ it.value.line }, { it.index to it.value }) }
    val dirty = edit.dirty
    val canEdit = text != null && text.complete && text.size <= ProjectFiles.MAX_EDIT

    fun startEdit() {
        val t = text ?: return
        edit.start(t.text)
        searching = false
    }

    fun leaveEdit(then: () -> Unit = {}) {
        if (dirty) {
            confirmDiscard = {
                edit.stop()
                then()
            }
        } else {
            edit.stop()
            then()
        }
    }

    fun save() {
        val t = text ?: return
        if (saving) return
        saving = true
        scope.launch {
            val body = edit.draft.text
            ProjectFiles.write(file, body)
                .onSuccess {
                    doc = t.copy(text = body, next = null, size = body.toByteArray().size.toLong())
                    edit.stop()
                    onChanged()
                    snackbar.show("Saved", SnackKind.Success)
                }
                .onFailure { snackbar.show("Could not save: ${it.message ?: "write failed"}", SnackKind.Error) }
            saving = false
        }
    }

    fun share() {
        scope.launch {
            runCatching {
                val copy = ShareFiles.copy(context, file, name)
                ShareFiles.share(context, copy, FileTypes.mime(name), name)
            }.onFailure { snackbar.show("Could not share this file", SnackKind.Error) }
        }
    }

    fun copy(label: String, value: String, done: String) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, value)))
            snackbar.show(done, SnackKind.Success)
        }
    }

    fun jump(line: Int) {
        scope.launch {
            listState.scrollToItem(line.coerceAtLeast(0))
            flashLine = line
            delay(1_200)
            if (flashLine == line) flashLine = -1
        }
    }

    // Moving through matches scrolls to the current one.
    LaunchedEffect(current, matches) {
        matches.getOrNull(current)?.let { m -> if (!listState.isScrolledToLine(m.line)) listState.scrollToItem((m.line - 3).coerceAtLeast(0)) }
    }

    var progress by remember { mutableFloatStateOf(0f) }
    var fromLeft by remember { mutableStateOf(true) }
    PredictiveBackHandler(enabled = !editing && !searching && codeView == null) { events ->
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
    BackHandler(enabled = (editing || searching) && codeView == null) {
        if (editing) leaveEdit() else searching = false
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
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // Top bar.
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(Icons.Rounded.Close, if (editing) "Stop editing" else "Close", onClick = { if (editing) leaveEdit() else onClose() }, tint = colors.ink1)
                Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(if (editing) "Editing $name" else name, style = type.subhead, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = buildList {
                        add(rel)
                        add(FileTypes.label(name))
                        when (val d = doc) {
                            is FileDoc.Text -> if (d.complete) add(if (lines.size == 1) "1 line" else "${lines.size} lines") else add("${ProjectFiles.size(d.size)}")
                            is FileDoc.Image -> add("${d.width} × ${d.height}")
                            is FileDoc.Binary -> add(ProjectFiles.size(d.size))
                            else -> Unit
                        }
                        if (dirty) add("unsaved")
                    }.joinToString(" · ")
                    Text(sub, style = type.monoMeta, color = if (dirty) colors.warning else colors.ink3, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                }
                if (editing) {
                    TextAction(if (saving) "Saving…" else "Save", onClick = ::save, color = colors.accent, enabled = dirty && !saving)
                } else {
                    if (text != null) {
                        IconAction(Icons.Rounded.Search, "Find in file", onClick = {
                            rendered = false
                            searching = !searching
                        }, tint = if (searching) colors.accent else colors.ink2)
                        if (!showRendered) {
                            IconAction(Icons.AutoMirrored.Rounded.WrapText, if (wrap) "Do not wrap lines" else "Wrap lines", onClick = { wrap = !wrap }, tint = if (wrap) colors.accent else colors.ink2)
                        }
                    }
                    Box {
                        IconAction(Icons.Rounded.MoreVert, "More", onClick = { menu = true })
                        ViewerMenu(
                            expanded = menu,
                            onDismiss = { menu = false },
                            items = buildList {
                                if (canEdit) add(MenuEntry("Edit", Icons.Rounded.Edit) { startEdit() })
                                if (text != null && !showRendered) add(MenuEntry("Go to line", Icons.Rounded.VerticalAlignCenter) { goTo = true })
                                add(MenuEntry("Copy path", Icons.Rounded.ContentCopy) { copy("Path", rel, "Path copied") })
                                if (text != null) add(MenuEntry("Copy text", Icons.Rounded.ContentCopy) { copy(name, text.text, "Text copied") })
                                add(MenuEntry("Share", Icons.Rounded.Share) { share() })
                            },
                        )
                    }
                }
            }
            if (kind == FileKind.Markdown && text != null && !editing) {
                Segmented(
                    options = listOf(true to "Rendered", false to "Source"),
                    selected = rendered,
                    onSelect = {
                        rendered = it
                        if (it) searching = false
                    },
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    height = 40.dp,
                )
            }
            if (searching && text != null && !editing) {
                FindBar(
                    query = query,
                    onQuery = {
                        query = it
                        current = 0
                    },
                    counter = if (query.isEmpty()) "" else FileSearch.counter(current, matches.size, matches.size >= FileSearch.MAX_MATCHES),
                    hasMatches = matches.isNotEmpty(),
                    onPrevious = { if (matches.isNotEmpty()) current = (current - 1 + matches.size) % matches.size },
                    onNext = { if (matches.isNotEmpty()) current = (current + 1) % matches.size },
                    onClose = { searching = false },
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val d = doc) {
                    null -> Unit
                    is FileDoc.Unreadable -> Note(d.reason, null, null)
                    is FileDoc.Binary -> Note("Binary file", "${FileTypes.label(name)} · ${ProjectFiles.size(d.size)}. Pi-Bolt can't show it; share it to open it in another app.") {
                        SecondaryButton("Share", onClick = ::share, icon = Icons.Rounded.Share)
                    }
                    is FileDoc.Image -> ZoomImage(d, "$name, ${d.width} by ${d.height} pixels")
                    is FileDoc.Text -> when {
                        editing -> Editor(edit.draft, onChange = { edit.draft = it })
                        showRendered -> RenderedMarkdown(d.text, more = d.next != null, loadingMore = loadingMore, footer = moreLabel(d), onMore = {
                            loadingMore = true
                            scope.launch {
                                doc = ProjectFiles.more(file, d)
                                loadingMore = false
                            }
                        }, onOpenCode = { code, lang -> codeView = ViewerContent(lang, null, code) })
                        else -> Lines(
                            text = d.text,
                            lines = lines,
                            language = language,
                            wrap = wrap,
                            byLine = byLine,
                            current = current,
                            flashLine = flashLine,
                            state = listState,
                            footer = if (d.next != null) moreLabel(d) else null,
                            loadingMore = loadingMore,
                            onMore = {
                                loadingMore = true
                                scope.launch {
                                    doc = ProjectFiles.more(file, d)
                                    loadingMore = false
                                }
                            },
                        )
                    }
                }
            }
        }
        PiSnackHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())

        AnimatedVisibility(
            visible = codeView != null,
            enter = fadeIn(PiMotion.enter(PiMotion.SHEET)) + scaleIn(PiMotion.enter(PiMotion.SHEET), initialScale = 0.96f),
            exit = fadeOut(PiMotion.leave(PiMotion.SHEET)) + scaleOut(PiMotion.leave(PiMotion.SHEET), targetScale = 0.96f),
        ) {
            val shown = remember { mutableStateOf(codeView) }
            codeView?.let { shown.value = it }
            shown.value?.let { Viewer(it, onClose = { codeView = null }) }
        }
    }

    if (goTo) {
        GoToLineSheet(lineCount = lines.size, onGo = { line ->
            goTo = false
            jump(line)
        }, onDismiss = { goTo = false })
    }
    confirmDiscard?.let { then ->
        ConfirmSheet(
            title = "Discard your changes?",
            message = "Your edits to $name are not saved.",
            confirmLabel = "Discard",
            onConfirm = {
                confirmDiscard = null
                then()
            },
            onDismiss = { confirmDiscard = null },
        )
    }
}

private fun moreLabel(d: FileDoc.Text): String {
    val shown = d.next ?: d.size
    return "Load more · ${ProjectFiles.size(shown)} of ${ProjectFiles.size(d.size)} shown"
}

private fun LazyListState.isScrolledToLine(line: Int): Boolean = layoutInfo.visibleItemsInfo.let { v -> v.size > 2 && line > v.first().index && line < v.last().index }

private class MenuEntry(val label: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
private fun ViewerMenu(expanded: Boolean, onDismiss: () -> Unit, items: List<MenuEntry>) {
    val colors = Pi.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = colors.surface3,
        shape = RoundedCornerShape(Radius.md),
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, colors.lineStrong),
    ) {
        for (item in items) {
            DropdownMenuItem(
                text = { Text(item.label, style = Pi.type.body, color = colors.ink1) },
                leadingIcon = { Icon(item.icon, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(20.dp)) },
                onClick = {
                    onDismiss()
                    item.onClick()
                },
                modifier = Modifier.width(200.dp).heightIn(min = 48.dp),
            )
        }
    }
}

/** Find bar: the query, "3 of 12", previous / next, close. Enter goes to the next match. */
@Composable
private fun FindBar(query: String, onQuery: (String) -> Unit, counter: String, hasMatches: Boolean, onPrevious: () -> Unit, onNext: () -> Unit, onClose: () -> Unit) {
    val colors = Pi.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        PiTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = "Find in file",
            singleLine = true,
            style = Pi.type.mono,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onSearch = { onNext() }),
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
        if (counter.isNotEmpty()) {
            Text(
                counter,
                style = Pi.type.meta,
                color = if (hasMatches) colors.ink2 else colors.warning,
                maxLines = 1,
                modifier = Modifier.padding(start = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        IconAction(Icons.Rounded.KeyboardArrowUp, "Previous match", onClick = onPrevious, enabled = hasMatches)
        IconAction(Icons.Rounded.KeyboardArrowDown, "Next match", onClick = onNext, enabled = hasMatches)
        IconAction(Icons.Rounded.Close, "Close find", onClick = onClose, size = 20.dp)
    }
}

/** Numbered lines, highlighted by language, with find matches marked; lazy, so long files never lay out at once. */
@Composable
private fun Lines(
    text: String,
    lines: List<IntRange>,
    language: String?,
    wrap: Boolean,
    byLine: Map<Int, List<Pair<Int, FileMatch>>>,
    current: Int,
    flashLine: Int,
    state: LazyListState,
    footer: String?,
    loadingMore: Boolean,
    onMore: () -> Unit,
) {
    val colors = Pi.colors
    val style = Pi.type.monoSmall
    val coloured = if (language != null) rememberHighlighted(text, language, colors) else null
    val numWidth = lines.size.toString().length
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val charWidth = remember(style) { measurer.measure("0", style).size.width }
    val longest = remember(lines) { lines.maxOfOrNull { minOf(it.last - it.first + 1, MAX_LINE + 2) } ?: 0 }
    val contentWidth = with(density) { (charWidth * (longest + numWidth + 2)).toDp() + 48.dp }
    val matchBg = colors.accent.copy(alpha = 0.22f)
    val currentBg = colors.accent.copy(alpha = 0.5f)
    val list = @Composable { m: Modifier ->
        LazyColumn(m, state = state, contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp)) {
            itemsIndexed(lines, contentType = { _, _ -> "line" }) { i, range ->
                val marks = byLine[i]
                val line = remember(range, coloured, marks, current) { lineText(text, coloured, range, marks, current, matchBg, currentBg) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (i == flashLine) colors.selected else colors.canvas.copy(alpha = 0f))
                        .padding(horizontal = 14.dp),
                ) {
                    Text((i + 1).toString().padStart(numWidth), style = style, color = colors.ink3)
                    Spacer(Modifier.width(12.dp))
                    Text(line, style = style, color = colors.ink1, softWrap = wrap)
                }
            }
            if (footer != null) {
                item(contentType = "more") { MoreRow(footer, loadingMore, onMore) }
            }
            item(contentType = "end") { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
    if (wrap) {
        list(Modifier.fillMaxSize())
    } else {
        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            list(Modifier.width(contentWidth).fillMaxSize())
        }
    }
}

private fun lineText(
    text: String,
    coloured: AnnotatedString?,
    range: IntRange,
    marks: List<Pair<Int, FileMatch>>?,
    current: Int,
    matchBg: androidx.compose.ui.graphics.Color,
    currentBg: androidx.compose.ui.graphics.Color,
): AnnotatedString {
    val end = range.last + 1
    val cut = minOf(end, range.first + MAX_LINE)
    val base = if (coloured != null && cut <= coloured.length) coloured.subSequence(range.first, cut) else AnnotatedString(text.substring(range.first, cut).removeSuffix("\r"))
    if (marks.isNullOrEmpty() && cut == end) return base
    return buildAnnotatedString {
        append(base)
        marks?.forEach { (index, m) ->
            val a = m.start.coerceAtMost(base.length)
            val b = m.end.coerceAtMost(base.length)
            if (b > a) addStyle(SpanStyle(background = if (index == current) currentBg else matchBg), a, b)
        }
        if (cut < end) append(" …")
    }
}

@Composable
private fun MoreRow(label: String, loading: Boolean, onMore: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        TextAction(if (loading) "Loading…" else label, onClick = onMore, color = Pi.colors.accent, enabled = !loading)
    }
}

/** Markdown through the app's renderer: blocks parsed off the main thread, laid out lazily with prose spacing. */
@Composable
private fun RenderedMarkdown(text: String, more: Boolean, loadingMore: Boolean, footer: String, onMore: () -> Unit, onOpenCode: (String, String) -> Unit) {
    val parsed by produceState<MdDoc?>(null, text) { value = withContext(Dispatchers.Default) { Markdown.parse(text) } }
    val blocks = parsed?.blocks ?: return
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp)) {
        itemsIndexed(blocks, contentType = { _, b -> b::class }) { i, block ->
            val previous = blocks.getOrNull(i - 1)
            val gap = when {
                i == 0 -> 0.dp
                block is MdBlock.Heading -> 22.dp
                block is MdBlock.ListItem && previous is MdBlock.ListItem -> 4.dp
                previous is MdBlock.Heading -> 8.dp
                else -> 12.dp
            }
            MdBlockView(block, streaming = false, onOpenCode = onOpenCode, modifier = Modifier.padding(top = gap))
        }
        if (more) item(contentType = "more") { MoreRow(footer, loadingMore, onMore) }
        item(contentType = "end") { Spacer(Modifier.navigationBarsPadding()) }
    }
}

/** Plain text editing: monospace, no autocorrect, scrolls with the keyboard up. */
@Composable
private fun Editor(value: TextFieldValue, onChange: (TextFieldValue) -> Unit) {
    val colors = Pi.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = Pi.type.monoSmall.copy(color = colors.ink1),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .focusRequester(focus)
            .semantics { contentDescription = "File text" },
    )
}

/** A centred note for files that are not shown as text, with an optional action. */
@Composable
private fun Note(title: String, detail: String?, action: (@Composable () -> Unit)?) {
    val colors = Pi.colors
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.AutoMirrored.Rounded.InsertDriveFile, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = Pi.type.title, color = colors.ink1, textAlign = TextAlign.Center)
        detail?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = Pi.type.body, color = colors.ink2, textAlign = TextAlign.Center)
        }
        action?.let {
            Spacer(Modifier.height(20.dp))
            it()
        }
    }
}

/** The image fitted to the screen; pinch or double tap to zoom, drag to pan when zoomed. */
@Composable
private fun ZoomImage(image: FileDoc.Image, description: String) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .semantics { contentDescription = description }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        scale = ImageZoom.toggle(scale)
                        offset = Offset.Zero
                    })
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.size > 1 || scale > ImageZoom.MIN) {
                                scale = ImageZoom.scale(scale, event.calculateZoom())
                                offset = if (scale <= ImageZoom.MIN) Offset.Zero else ImageZoom.clampOffset(offset, event.calculatePan(), scale, size.width.toFloat(), size.height.toFloat())
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                image.bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
        Text(
            "${image.width} × ${image.height} · ${ProjectFiles.size(image.size)}",
            style = Pi.type.monoMeta,
            color = Pi.colors.ink3,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 12.dp),
            textAlign = TextAlign.Center,
        )
    }
}

/** "Go to line": a number field and Go. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoToLineSheet(lineCount: Int, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(250)
        runCatching { focus.requestFocus() }
    }
    val target = FileSearch.lineIndex(typed, lineCount)
    PiSheet(onDismiss = onDismiss) {
        SheetHeader("Go to line", subtitle = "1 to $lineCount")
        PiTextField(
            value = typed,
            onValueChange = { v -> typed = v.filter(Char::isDigit).take(9) },
            placeholder = "Line number",
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { target?.let(onGo) }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
        )
        Spacer(Modifier.height(8.dp))
        PrimaryButton("Go", onClick = { target?.let(onGo) }, enabled = target != null, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
    }
}
