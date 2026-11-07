package opensec.pibolt.app.ui.projects

import android.content.ClipData
import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.WrapText
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FormatIndentIncrease
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.components.show
import opensec.pibolt.app.ui.markdown.MdBlock
import opensec.pibolt.app.ui.markdown.MdBlockView
import opensec.pibolt.app.ui.markdown.MdDoc
import opensec.pibolt.app.ui.markdown.Markdown
import opensec.pibolt.app.ui.markdown.rememberHighlighted
import opensec.pibolt.app.ui.projects.editor.AskPi
import opensec.pibolt.app.ui.projects.editor.CodeEditor
import opensec.pibolt.app.ui.projects.editor.EDIT_HIGHLIGHT_CHARS
import opensec.pibolt.app.ui.projects.editor.EditorCommand
import opensec.pibolt.app.ui.projects.editor.EditorInput
import opensec.pibolt.app.ui.projects.editor.EditorShortcuts
import opensec.pibolt.app.ui.projects.editor.EditorTab
import opensec.pibolt.app.ui.projects.editor.EditorTabs
import opensec.pibolt.app.ui.projects.editor.FindReplace
import opensec.pibolt.app.ui.projects.editor.FindReplaceBar
import opensec.pibolt.app.ui.projects.editor.FindResult
import opensec.pibolt.app.ui.projects.editor.FindState
import opensec.pibolt.app.ui.projects.editor.KeyChord
import opensec.pibolt.app.ui.projects.editor.Reveal
import opensec.pibolt.app.ui.projects.editor.TextMatch
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
 * The project's open files, full screen, one tab each ([tabs]; [rel] is the one shown). Markdown renders with the
 * app's markdown engine (Rendered / Source); code is highlighted by its extension with line numbers; any text can be
 * searched (plain, whole word, case, regex), jumped through by line and wrapped. Edit turns the text into the code
 * editor ([CodeEditor]): highlighting as you type, auto-indent, bracket pairs, find and replace with undo, Save, and
 * a guard for unsaved changes (tabs with unsaved edits show a dot). Files over 1 MB (or not loaded whole) are
 * read-only. "Ask Pi" hands the file, or the selected lines, to [onAsk] for the project's composer. Hardware keys:
 * [EditorShortcuts]. Images show zoomable; binary files say so and offer Share. [onChanged] runs after a save.
 */
@Composable
fun FileViewer(
    root: File,
    tabs: EditorTabs,
    rel: String,
    onSelect: (String) -> Unit,
    onClose: () -> Unit,
    onChanged: () -> Unit,
    onAsk: (String) -> Unit,
) {
    val colors = Pi.colors
    val type = Pi.type
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val tab = remember(tabs, rel) { tabs.open(rel) }
    val file = remember(root, rel) { File(root, rel) }
    val name = file.name
    val kind = remember(name) { FileTypes.kind(name) }
    val language = remember(name) { if (kind == FileKind.Markdown) "markdown" else FileTypes.language(name) }

    LaunchedEffect(tab) { if (tab.doc == null) tab.doc = ProjectFiles.open(file) }
    val doc = tab.doc
    val text = (doc as? FileDoc.Text)
    val editing = tab.editing
    var loadingMore by remember { mutableStateOf(false) }

    var rendered by rememberSaveable { mutableStateOf(true) }
    var wrap by rememberSaveable { mutableStateOf(kind != FileKind.Code) }
    val find = remember { FindState() }
    var menu by remember { mutableStateOf(false) }
    var goTo by remember { mutableStateOf(false) }
    var flashLine by remember { mutableIntStateOf(-1) }
    var saving by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var codeView by remember { mutableStateOf<ViewerContent?>(null) }
    var reveal by remember { mutableStateOf<Reveal?>(null) }
    val listState = remember(rel) { LazyListState() }
    val editorFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }

    val showRendered = kind == FileKind.Markdown && rendered && !editing
    val lines = remember(text?.text) { text?.let { FileSearch.lines(it.text) }.orEmpty() }
    val canEdit = text != null && text.complete && text.size <= ProjectFiles.MAX_EDIT
    val readOnly = text != null && !canEdit
    val searched = if (editing) tab.value.text else text?.text

    // Matches: off the main thread, 120 ms after the last change (a regex can be slow; FindReplace bounds it too).
    val options = find.options
    LaunchedEffect(find.open, options, searched) {
        if (!find.open || options.query.isEmpty() || searched == null) {
            find.result = FindResult.EMPTY
            return@LaunchedEffect
        }
        delay(120)
        val result = withContext(Dispatchers.Default) { FindReplace.find(searched, options) }
        val at = if (editing) tab.value.selection.min else 0
        find.result = result
        find.current = FindReplace.nearest(result.matches, at).coerceAtLeast(0)
    }
    val matches = find.matches
    val byLine = remember(matches, lines, editing) { if (editing) emptyMap() else matchesByLine(matches, lines) }

    fun startEdit(): Boolean {
        val t = text ?: return false
        if (!canEdit) {
            scope.launch { snackbar.show("Files over 1 MB open read-only", SnackKind.Warning) }
            return false
        }
        if (!tab.editing) tab.startEditing(t.text)
        rendered = false
        return true
    }

    fun leaveEdit(then: () -> Unit = {}) {
        if (tab.dirty) {
            confirm = "Discard your changes to $name?" to {
                tab.stopEditing()
                then()
            }
        } else {
            tab.stopEditing()
            then()
        }
    }

    fun save() {
        if (!tab.editing || saving) return
        saving = true
        scope.launch {
            val body = tab.value.text
            ProjectFiles.write(file, body)
                .onSuccess {
                    tab.saved = body
                    tab.doc = FileDoc.Text(body, null, body.toByteArray().size.toLong())
                    onChanged()
                    snackbar.show("Saved", SnackKind.Success)
                }
                .onFailure { snackbar.show("Could not save: ${it.message ?: "write failed"}", SnackKind.Error) }
            saving = false
        }
    }

    fun closeTab(target: EditorTab) {
        val go = {
            val next = tabs.close(target.rel)
            if (target.rel == rel) {
                if (next == null) onClose() else onSelect(next.rel)
            }
        }
        if (target.dirty) confirm = "Close ${target.name} without saving?" to go else go()
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
        if (editing) {
            val at = EditorInput.lineStart(tab.value.text, line)
            tab.value = tab.value.copy(selection = TextRange(at))
            reveal = Reveal(at)
            runCatching { editorFocus.requestFocus() }
            return
        }
        scope.launch {
            listState.scrollToItem(line.coerceAtLeast(0))
            flashLine = line
            delay(1_200)
            if (flashLine == line) flashLine = -1
        }
    }

    fun openFind(replace: Boolean) {
        if (replace && !startEdit()) return
        if (!editing && !replace) rendered = false
        // A one-line selection seeds the query, as desktop editors do.
        if (tab.editing) {
            val sel = tab.value.selection
            val picked = tab.value.text.substring(sel.min, sel.max)
            if (picked.isNotEmpty() && '\n' !in picked && picked.length <= 200) find.query = picked
        }
        find.open = true
        if (replace) find.replacing = true
        find.focusTick++
    }

    fun replaceOne() {
        val m = matches.getOrNull(find.current) ?: return
        val r = FindReplace.replacementFor(tab.value.text, find.options, m, find.replacement) ?: return
        tab.replace(r.start, r.end, r.text)
    }

    fun replaceAll() {
        val (r, count) = FindReplace.replaceAll(tab.value.text, find.options, find.replacement) ?: return
        if (count == 0) return
        tab.replace(r.start, r.end, r.text, TextRange(r.start))
        scope.launch { snackbar.show(if (count == 1) "Replaced 1 match" else "Replaced $count matches", SnackKind.Success) }
    }

    fun ask(selection: Boolean) {
        val v = tab.value
        onAsk(if (selection && editing) AskPi.prompt(rel, v.text, v.selection.min, v.selection.max, language) else AskPi.prompt(rel, "", 0, 0, null))
    }

    fun command(c: EditorCommand): Boolean {
        when (c) {
            EditorCommand.Save -> if (tab.editing) save() else return false
            EditorCommand.Find -> if (text != null) openFind(replace = false) else return false
            EditorCommand.Replace -> if (text != null) openFind(replace = true) else return false
            EditorCommand.GoToLine -> if (text != null && !showRendered) goTo = true else return false
            EditorCommand.Undo -> if (tab.editing) tab.undo() else return false
            EditorCommand.Redo -> if (tab.editing) tab.redo() else return false
            EditorCommand.CloseTab -> closeTab(tab)
            EditorCommand.NextTab -> tabs.neighbour(rel, 1)?.let { onSelect(it.rel) }
            EditorCommand.PreviousTab -> tabs.neighbour(rel, -1)?.let { onSelect(it.rel) }
            EditorCommand.FindNext -> if (find.open) find.step(1) else return false
            EditorCommand.FindPrevious -> if (find.open) find.step(-1) else return false
        }
        return true
    }

    // Moving through matches: the view scrolls to the line; the editor selects the match and shows it.
    LaunchedEffect(find.current, matches) {
        val m = matches.getOrNull(find.current) ?: return@LaunchedEffect
        if (editing) {
            if (m.end <= tab.value.text.length) {
                tab.value = tab.value.copy(selection = TextRange(m.start, m.end))
                reveal = Reveal(m.start)
            }
        } else {
            val line = lineOf(lines, m.start)
            if (!listState.isScrolledToLine(line)) listState.scrollToItem((line - 3).coerceAtLeast(0))
        }
    }
    // Without a focused field the screen itself takes the keyboard, so shortcuts work while reading too.
    LaunchedEffect(editing, find.open, rel) { if (!editing && !find.open) runCatching { rootFocus.requestFocus() } }
    LaunchedEffect(editing, rel) { if (editing) runCatching { editorFocus.requestFocus() } }

    var progress by remember { mutableFloatStateOf(0f) }
    var fromLeft by remember { mutableStateOf(true) }
    PredictiveBackHandler(enabled = !editing && !find.open && codeView == null) { events ->
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
    BackHandler(enabled = (editing || find.open) && codeView == null) {
        if (find.open) find.open = false else leaveEdit()
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
            .background(colors.canvas)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val chord = KeyChord(e.nativeKeyEvent.keyCode, ctrl = e.isCtrlPressed || e.isMetaPressed, shift = e.isShiftPressed, alt = e.isAltPressed)
                EditorShortcuts.command(chord)?.let(::command) ?: false
            }
            .focusRequester(rootFocus)
            .focusable(),
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
                        when (doc) {
                            is FileDoc.Text -> if (doc.complete) add(if (lines.size == 1) "1 line" else "${lines.size} lines") else add(ProjectFiles.size(doc.size))
                            is FileDoc.Image -> add("${doc.width} × ${doc.height}")
                            is FileDoc.Binary -> add(ProjectFiles.size(doc.size))
                            else -> Unit
                        }
                        if (readOnly) add("read-only")
                        if (tab.dirty) add("unsaved")
                    }.joinToString(" · ")
                    Text(sub, style = type.monoMeta, color = if (tab.dirty) colors.warning else colors.ink3, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                }
                if (text != null) {
                    IconAction(Icons.Rounded.Search, "Find in file", onClick = { if (find.open) find.open = false else openFind(replace = false) }, tint = if (find.open) colors.accent else colors.ink2)
                }
                if (editing) {
                    TextAction(if (saving) "Saving…" else "Save", onClick = ::save, color = colors.accent, enabled = tab.dirty && !saving)
                } else if (text != null && !showRendered) {
                    IconAction(Icons.AutoMirrored.Rounded.WrapText, if (wrap) "Do not wrap lines" else "Wrap lines", onClick = { wrap = !wrap }, tint = if (wrap) colors.accent else colors.ink2)
                }
                Box {
                    IconAction(Icons.Rounded.MoreVert, "More", onClick = { menu = true })
                    ViewerMenu(
                        expanded = menu,
                        onDismiss = { menu = false },
                        items = buildList {
                            if (canEdit && !editing) add(MenuEntry("Edit", Icons.Rounded.Edit) { startEdit() })
                            if (editing && !tab.value.selection.collapsed) add(MenuEntry("Ask Pi about the selection", Icons.Rounded.AutoAwesome) { ask(selection = true) })
                            add(MenuEntry("Ask Pi about this file", Icons.Rounded.AutoAwesome) { ask(selection = false) })
                            if (text != null && !showRendered) add(MenuEntry("Go to line", Icons.Rounded.VerticalAlignCenter) { goTo = true })
                            if (editing) add(MenuEntry(if (wrap) "Do not wrap lines" else "Wrap lines", Icons.AutoMirrored.Rounded.WrapText) { wrap = !wrap })
                            add(MenuEntry("Copy path", Icons.Rounded.ContentCopy) { copy("Path", rel, "Path copied") })
                            if (text != null && !editing) add(MenuEntry("Copy text", Icons.Rounded.ContentCopy) { copy(name, text.text, "Text copied") })
                            add(MenuEntry("Share", Icons.Rounded.Share) { share() })
                        },
                    )
                }
            }
            if (tabs.tabs.size > 1) TabStrip(tabs, rel, onSelect = onSelect, onClose = ::closeTab)
            if (kind == FileKind.Markdown && text != null && !editing) {
                Segmented(
                    options = listOf(true to "Rendered", false to "Source"),
                    selected = rendered,
                    onSelect = {
                        rendered = it
                        if (it) find.open = false
                    },
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    height = 40.dp,
                )
            }
            if (find.open && text != null && !showRendered) {
                FindReplaceBar(find, canReplace = canEdit, onReplace = ::replaceOne, onReplaceAll = ::replaceAll, onClose = { find.open = false })
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
            Column(Modifier.weight(1f).fillMaxWidth().then(if (editing) Modifier.imePadding() else Modifier)) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (doc) {
                        null -> Unit
                        is FileDoc.Unreadable -> Note(doc.reason, null, null)
                        is FileDoc.Binary -> Note("Binary file", "${FileTypes.label(name)} · ${ProjectFiles.size(doc.size)}. Pi-Bolt can't show it; share it to open it in another app.") {
                            SecondaryButton("Share", onClick = ::share, icon = Icons.Rounded.Share)
                        }
                        is FileDoc.Image -> ZoomImage(doc, "$name, ${doc.width} by ${doc.height} pixels")
                        is FileDoc.Text -> when {
                            editing -> CodeEditor(tab, language, wrap, matches, find.current, reveal, editorFocus)
                            showRendered -> RenderedMarkdown(doc.text, more = doc.next != null, loadingMore = loadingMore, footer = moreLabel(doc), onMore = {
                                loadingMore = true
                                scope.launch {
                                    tab.doc = ProjectFiles.more(file, doc)
                                    loadingMore = false
                                }
                            }, onOpenCode = { code, lang -> codeView = ViewerContent(lang, null, code) })
                            else -> Lines(
                                text = doc.text,
                                lines = lines,
                                language = language,
                                wrap = wrap,
                                byLine = byLine,
                                current = find.current,
                                flashLine = flashLine,
                                state = listState,
                                footer = if (doc.next != null) moreLabel(doc) else null,
                                loadingMore = loadingMore,
                                onMore = {
                                    loadingMore = true
                                    scope.launch {
                                        tab.doc = ProjectFiles.more(file, doc)
                                        loadingMore = false
                                    }
                                },
                            )
                        }
                    }
                }
                if (editing) {
                    EditorBar(
                        tab = tab,
                        plain = tab.value.text.length > EDIT_HIGHLIGHT_CHARS && language != null,
                        onIndent = {
                            val v = tab.value
                            tab.replace(v.selection.min, v.selection.max, EditorInput.indentUnit(v.text))
                        },
                        onGoTo = { goTo = true },
                    )
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
        val count = if (editing) EditorInput.lineCount(tab.value.text) else lines.size
        GoToLineSheet(lineCount = count, onGo = { line ->
            goTo = false
            jump(line)
        }, onDismiss = { goTo = false })
    }
    confirm?.let { (title, then) ->
        ConfirmSheet(
            title = title,
            message = "Your edits are not saved.",
            confirmLabel = "Discard",
            onConfirm = {
                confirm = null
                then()
            },
            onDismiss = { confirm = null },
        )
    }
}

/** The line (0-based) holding [offset]: a binary search over [lines]. */
private fun lineOf(lines: List<IntRange>, offset: Int): Int {
    var lo = 0
    var hi = lines.size - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) ushr 1
        if (lines[mid].first <= offset) lo = mid else hi = mid - 1
    }
    return lo.coerceAtLeast(0)
}

/** [matches] (offsets in the text) by line, each cut to its line, with its index for the current-match colour. */
private fun matchesByLine(matches: List<TextMatch>, lines: List<IntRange>): Map<Int, List<Pair<Int, FileMatch>>> {
    if (matches.isEmpty() || lines.isEmpty()) return emptyMap()
    val out = HashMap<Int, MutableList<Pair<Int, FileMatch>>>()
    matches.forEachIndexed { i, m ->
        val line = lineOf(lines, m.start)
        val range = lines[line]
        val end = minOf(m.end, range.last + 1)
        if (end > m.start) out.getOrPut(line) { ArrayList() } += i to FileMatch(line, m.start - range.first, end - range.first)
    }
    return out
}

/** Open files as tabs: name, a dot when unsaved, and a close button; the shown one is underlined in the accent. */
@Composable
private fun TabStrip(tabs: EditorTabs, shown: String, onSelect: (String) -> Unit, onClose: (EditorTab) -> Unit) {
    val colors = Pi.colors
    val scroll = rememberScrollState()
    Row(Modifier.fillMaxWidth().height(48.dp).horizontalScroll(scroll).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        for (t in tabs.tabs) {
            val selected = t.rel == shown
            val dirty = t.dirty
            Row(
                Modifier
                    .height(40.dp)
                    .background(if (selected) colors.surface2 else colors.canvas, RoundedCornerShape(Radius.sm))
                    .pressable({ onSelect(t.rel) }, shape = RoundedCornerShape(Radius.sm), pressedScale = 1f, label = "Show ${t.name}")
                    .semantics {
                        this.selected = selected
                        contentDescription = t.name + if (dirty) ", unsaved" else ""
                    }
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (dirty) {
                    Box(Modifier.size(7.dp).background(colors.warning, CircleShape))
                    Spacer(Modifier.width(6.dp))
                }
                Text(t.name, style = Pi.type.monoSmall, color = if (selected) colors.ink1 else colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                IconAction(Icons.Rounded.Close, "Close ${t.name}", onClick = { onClose(t) }, size = 16.dp, tint = colors.ink3)
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}

/**
 * The bar over the keyboard while editing: Undo, Redo, indent, go to line, and the cursor's line and column. Big
 * files edit without colours; the bar says so.
 */
@Composable
private fun EditorBar(tab: EditorTab, plain: Boolean, onIndent: () -> Unit, onGoTo: () -> Unit) {
    val colors = Pi.colors
    // Reading the version recomposes the bar when the history changes.
    val canUndo = tab.historyVersion.let { tab.history.canUndo }
    val canRedo = tab.historyVersion.let { tab.history.canRedo }
    val v = tab.value
    val (line, col) = remember(v.text, v.selection.start) { EditorInput.lineCol(v.text, v.selection.start) }
    Column(Modifier.fillMaxWidth().background(colors.surface1)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.AutoMirrored.Rounded.Undo, "Undo", onClick = { tab.undo() }, enabled = canUndo)
            IconAction(Icons.AutoMirrored.Rounded.Redo, "Redo", onClick = { tab.redo() }, enabled = canRedo)
            IconAction(Icons.Rounded.FormatIndentIncrease, "Insert indent", onClick = onIndent)
            IconAction(Icons.Rounded.VerticalAlignCenter, "Go to line", onClick = onGoTo)
            Spacer(Modifier.weight(1f))
            Text(
                (if (plain) "No colours over 400 KB · " else "") + "Ln $line, Col $col",
                style = Pi.type.monoMeta,
                color = colors.ink3,
                maxLines = 1,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
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
    matchBg: Color,
    currentBg: Color,
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
