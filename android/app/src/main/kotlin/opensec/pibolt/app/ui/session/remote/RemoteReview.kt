package opensec.pibolt.app.ui.session.remote

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.data.ProjectStore
import opensec.pibolt.app.git.BranchStatus
import opensec.pibolt.app.git.Change
import opensec.pibolt.app.git.Commit
import opensec.pibolt.app.git.DiffStats
import opensec.pibolt.app.git.FileDiff
import opensec.pibolt.app.git.GitException
import opensec.pibolt.app.git.GitSource
import opensec.pibolt.app.git.GitStatus
import opensec.pibolt.app.git.LineCount
import opensec.pibolt.app.git.StatusEntry
import opensec.pibolt.app.git.UnifiedDiffParser
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.app.ui.components.ErrorState
import opensec.pibolt.app.ui.components.GlassIconButton
import opensec.pibolt.app.ui.components.GlassPill
import opensec.pibolt.app.ui.components.InsetRow
import opensec.pibolt.app.ui.components.LoadingRow
import opensec.pibolt.app.ui.components.PiSnackHost
import opensec.pibolt.app.ui.components.SnackKind
import opensec.pibolt.app.ui.components.show
import opensec.pibolt.app.ui.git.ChangesModel
import opensec.pibolt.app.ui.git.ChangesOverlays
import opensec.pibolt.app.ui.git.ChangesRules
import opensec.pibolt.app.ui.git.ChangesUi
import opensec.pibolt.app.ui.git.OverlayBar
import opensec.pibolt.app.ui.git.changesItems
import opensec.pibolt.app.ui.projects.DOT_FILE_ALPHA
import opensec.pibolt.app.ui.projects.EmptyCard
import opensec.pibolt.app.ui.projects.FileDoc
import opensec.pibolt.app.ui.projects.FileEntry
import opensec.pibolt.app.ui.projects.FileKind
import opensec.pibolt.app.ui.projects.FileTypes
import opensec.pibolt.app.ui.projects.FileViewer
import opensec.pibolt.app.ui.projects.ProjectFiles
import opensec.pibolt.app.ui.projects.editor.EditorTabs
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Space
import opensec.pibolt.app.ui.theme.Tnum
import opensec.pibolt.protocol.rpc.RemoteCommit
import opensec.pibolt.protocol.rpc.RemoteDiff
import opensec.pibolt.protocol.rpc.RemoteDirEntry
import opensec.pibolt.protocol.rpc.RemoteFile
import opensec.pibolt.protocol.rpc.RemoteGitStatus
import opensec.pibolt.protocol.rpc.RemoteLineCount
import opensec.pibolt.protocol.rpc.RemoteListing
import opensec.pibolt.protocol.rpc.RemoteLog
import opensec.pibolt.protocol.rpc.RemoteShow
import opensec.pibolt.protocol.rpc.RemoteStatusEntry
import opensec.pibolt.protocol.rpc.ReviewException
import opensec.pibolt.protocol.rpc.ReviewRequest
import opensec.pibolt.protocol.rpc.RpcResponse
import opensec.pibolt.protocol.rpc.remoteChild
import opensec.pibolt.protocol.session.SessionState
import java.io.File

/*
 * Read-only review of the computer's working tree in a remote session (`host.review`): the Changes view (status,
 * diffs, history) and the Files view (folders and the file viewer), fed by the review commands. They reuse the
 * project's views in their read-only mode: nothing here stages, commits or edits.
 */

/** Maps the review answers to the app's git and file models, so the project's views show a computer's tree. */
object RemoteMapping {
    fun status(s: RemoteGitStatus): GitStatus {
        val b = s.branch
        return GitStatus(
            BranchStatus(head = b?.head, oid = b?.oid, upstream = b?.upstream, ahead = b?.ahead ?: 0, behind = b?.behind ?: 0),
            s.entries.map { e ->
                StatusEntry(
                    path = e.path,
                    index = if (e.conflicted) Change.Conflicted else e.index?.let(Change::of),
                    worktree = when {
                        e.conflicted -> Change.Conflicted
                        e.worktree == '?' -> Change.Untracked
                        else -> e.worktree?.let(Change::of)
                    },
                    origPath = e.origPath,
                    conflicted = e.conflicted,
                )
            },
        )
    }

    fun stats(s: RemoteGitStatus): DiffStats {
        fun side(pick: (RemoteStatusEntry) -> RemoteLineCount?) =
            s.entries.mapNotNull { e -> pick(e)?.let { e.path to LineCount(it.added, it.removed) } }.toMap()
        return DiffStats(staged = side { it.staged }, unstaged = side { it.unstaged })
    }

    fun commit(c: RemoteCommit): Commit = Commit(c.hash, c.short, c.parents, c.author, c.email, c.time, c.subject, c.body, c.refs)

    /** The file of [diff] (one path's `git diff`); null when the computer had nothing to show. */
    fun diff(diff: RemoteDiff, path: String): FileDiff? {
        val files = UnifiedDiffParser.parse(diff.diff)
        return files.firstOrNull { it.path == path } ?: files.firstOrNull()
    }

    /** A commit and its files; the files the computer withheld (secrets) are named at the end of the message. */
    fun show(s: RemoteShow): Pair<Commit?, List<FileDiff>> {
        val commit = s.commit?.let(::commit)?.let { c ->
            if (s.withheld.isEmpty()) c else c.copy(body = (c.body + "\n\n" + withheldNote(s.withheld)).trim())
        }
        return commit to UnifiedDiffParser.parse(s.diff)
    }

    fun withheldNote(paths: List<String>): String =
        "Not shown over remote control (looks like a secret): " + paths.joinToString(", ")

    /** What the file viewer shows of [f]: text (cut short when the computer [RemoteFile.truncated] it) or a binary note. */
    fun doc(f: RemoteFile): FileDoc {
        val text = f.content
        return when {
            f.binary || text == null -> FileDoc.Binary(f.size)
            f.truncated -> FileDoc.Text(text, text.toByteArray().size.toLong(), f.size)
            else -> FileDoc.Text(text, null, f.size)
        }
    }

    /** Folders first, then by name; version-control internals (`.git`) left out, as in the Files tab. */
    fun entries(listing: RemoteListing): List<RemoteDirEntry> {
        val byName = listing.entries.associateBy { it.name }
        val shown = ProjectFiles.visible(listing.entries.map { FileEntry(it.name, it.dir, it.size, it.mtime) }, showHidden = false)
        return ProjectFiles.order(shown).mapNotNull { byName[it.name] }
    }

    /** The parent of [path] (`""` is the session's folder). */
    fun parent(path: String): String = path.substringBeforeLast('/', "")
}

/** The computer's working tree as a [GitSource], through the remote session's review commands. */
class RemoteGitSource(private val controller: SessionController, private val onStatus: (RemoteGitStatus) -> Unit) : GitSource {
    /** The status [isRepo] just read: [status] and [diffStats] use it, so one refresh is one request. */
    @Volatile private var last: RemoteGitStatus? = null

    private suspend fun <T> ask(request: ReviewRequest, parse: (RpcResponse) -> T): T {
        val response = controller.request(request.type, timeoutMs = 60_000, fields = request.fields)
        return try {
            parse(response)
        } catch (e: ReviewException) {
            throw GitException(e.message ?: "The computer could not answer")
        }
    }

    private suspend fun fetch(): RemoteGitStatus = ask(ReviewRequest.status(), RemoteGitStatus::from).also {
        last = it
        onStatus(it)
    }

    override suspend fun isRepo(): Boolean = fetch().repo

    override suspend fun status(): GitStatus = RemoteMapping.status(last ?: fetch())

    override suspend fun diffStats(): DiffStats = RemoteMapping.stats(last ?: fetch())

    override suspend fun diff(entry: StatusEntry, staged: Boolean): FileDiff? =
        RemoteMapping.diff(ask(ReviewRequest.diff(entry.path, staged, entry.origPath), RemoteDiff::from), entry.path)

    override suspend fun log(limit: Int, skip: Int): List<Commit> =
        ask(ReviewRequest.log(limit, skip), RemoteLog::from).commits.map(RemoteMapping::commit)

    override suspend fun show(hash: String): Pair<Commit?, List<FileDiff>> = RemoteMapping.show(ask(ReviewRequest.show(hash), RemoteShow::from))

    /** Pushing and pull requests are not offered from here. */
    override suspend fun remoteUrl(): String? = null
}

enum class RemoteView { Changes, Files }

/**
 * The review state of one remote session: the working tree's "+N −M" for the composer's pill and the view open over
 * the transcript.
 */
@Stable
class RemoteReview(val controller: SessionController, private val scope: CoroutineScope) {
    /** The working tree's line counts at the last look; null before one (or outside a repository). */
    var total by mutableStateOf<LineCount?>(null)
        private set
    var view by mutableStateOf<RemoteView?>(null)

    val source = RemoteGitSource(controller) { s -> total = if (s.repo) LineCount(s.total.added, s.total.removed) else null }

    /** Reads the status again for the pill; a failure (no connection) keeps the last counts. */
    fun refresh() {
        scope.launch { runCatching { source.isRepo() } }
    }

    private suspend fun ask(request: ReviewRequest): RpcResponse = controller.request(request.type, timeoutMs = 60_000, fields = request.fields)

    /** A file for the viewer; a refused or failed read is a note saying why (a secret file, too large, gone). */
    suspend fun readFile(path: String): FileDoc = try {
        RemoteMapping.doc(RemoteFile.from(ask(ReviewRequest.readFile(path))))
    } catch (e: ReviewException) {
        FileDoc.Unreadable(e.message ?: "The computer could not send this file.")
    }

    suspend fun listDir(path: String): RemoteListing = RemoteListing.from(ask(ReviewRequest.listDir(path)))
}

/**
 * The remote session's review, or null when there is none: an on-device session, or a computer whose Pi-Bolt does
 * not offer it (`host.review`). The counts refresh when the session connects or switches, and after every turn.
 */
@Composable
fun rememberRemoteReview(controller: SessionController, state: State<SessionState>): RemoteReview? {
    val scope = rememberCoroutineScope()
    val review = remember(controller) { RemoteReview(controller, scope) }
    val enabled by remember { derivedStateOf { !controller.isLocal && state.value.host?.review == true } }
    val running by remember { derivedStateOf { state.value.isRunning } }
    val cwd by remember { derivedStateOf { state.value.host?.cwd } }
    if (!enabled) return null
    LaunchedEffect(review, running, cwd) { if (!running) review.refresh() }
    return review
}

/**
 * The working tree's "+N −M" as a glass pill at the end of the composer's slim row, as on this phone's sessions. It
 * shows once something changed; a tap opens Changes.
 */
@Composable
fun RemoteChangesPill(review: RemoteReview) {
    val total = review.total ?: return
    if (total.added == 0 && total.removed == 0) return
    val colors = Pi.colors
    val type = Pi.type
    val num = type.label.copy(fontFeatureSettings = Tnum)
    GlassPill(
        onClick = { review.view = RemoteView.Changes },
        label = "View changes",
        modifier = Modifier.semantics { contentDescription = "View changes on the computer, " + ChangesRules.countsSpoken(total.added, total.removed) },
    ) {
        Text("Changes", style = type.label, color = colors.ink1, maxLines = 1)
        Text("+${total.added}", style = num, color = colors.success, maxLines = 1)
        Text("−${total.removed}", style = num, color = colors.danger, maxLines = 1)
    }
}

/** Changes or Files over the session, full screen; [onAsk] puts an "Ask Pi" from the file viewer in the composer. */
@Composable
fun RemoteReviewOverlays(app: PiBoltApp, review: RemoteReview, cwd: String, running: Boolean, onAsk: (String) -> Unit) {
    BackHandler(enabled = review.view != null) { review.view = null }
    AnimatedVisibility(
        visible = review.view != null,
        enter = fadeIn(PiMotion.enter(PiMotion.SHEET)) + slideInVertically(PiMotion.enter(PiMotion.SHEET)) { it / 10 },
        exit = fadeOut(PiMotion.leave(PiMotion.SHEET)),
    ) {
        val shown = remember { mutableStateOf(review.view) }
        review.view?.let { shown.value = it }
        when (shown.value) {
            RemoteView.Changes -> RemoteChanges(app, review, cwd, running, onClose = { review.view = null })
            RemoteView.Files -> RemoteFiles(review, cwd, onAsk = {
                review.view = null
                onAsk(it)
            }, onClose = { review.view = null })
            null -> Unit
        }
    }
}

private fun folderName(cwd: String): String = cwd.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { cwd }

/** The project's Changes tab in its read-only mode, over the computer's working tree: refreshed after every turn. */
@Composable
private fun RemoteChanges(app: PiBoltApp, review: RemoteReview, cwd: String, running: Boolean, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val ui = remember(review, cwd) {
        val snack: (String, SnackKind) -> Unit = { text, kind -> scope.launch { snackbar.show(text, kind) } }
        ChangesUi(app, ChangesModel(review.source, File(cwd), scope) { snack(it, SnackKind.Error) }, ProjectStore.get(app), scope, snack)
    }
    LaunchedEffect(ui, running) { if (!running) ui.model.refresh() }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize().background(Pi.colors.canvas)) {
        Column(Modifier.fillMaxSize()) {
            OverlayBar("Changes", folderName(cwd), onBack = onClose) {
                GlassIconButton(Icons.Rounded.Refresh, "Refresh", onClick = ui.model::refresh)
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.xxl + bottom)) {
                changesItems(ui)
            }
        }
        ChangesOverlays(ui, visible = true)
        PiSnackHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

/**
 * The computer's files under the session's folder, one folder at a time (folders first, `.git` left out), and the
 * file viewer read-only. Back goes up a folder, then closes.
 */
@Composable
private fun RemoteFiles(review: RemoteReview, cwd: String, onAsk: (String) -> Unit, onClose: () -> Unit) {
    val colors = Pi.colors
    var path by rememberSaveable { mutableStateOf("") }
    var listing by remember { mutableStateOf<RemoteListing?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var opened by remember { mutableStateOf<String?>(null) }
    val tabs = remember { EditorTabs() }
    LaunchedEffect(path, attempt) {
        failed = null
        listing = null
        try {
            listing = review.listDir(path)
        } catch (e: ReviewException) {
            failed = e.message ?: "The computer could not list this folder."
        }
    }
    val entries = remember(listing) { listing?.let(RemoteMapping::entries).orEmpty() }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BackHandler(enabled = path.isNotEmpty() && opened == null) { path = RemoteMapping.parent(path) }
    Box(Modifier.fillMaxSize().background(colors.canvas)) {
        Column(Modifier.fillMaxSize()) {
            if (path.isEmpty()) {
                OverlayBar("Files", folderName(cwd), onBack = onClose)
            } else {
                OverlayBar(path.substringAfterLast('/'), path, onBack = { path = RemoteMapping.parent(path) }, back = true, backLabel = "Up a folder")
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.xxl + bottom)) {
                val error = failed
                when {
                    error != null -> item(key = "error") {
                        ErrorState("Could not open the folder", Modifier.padding(horizontal = Space.page, vertical = Space.sm), message = error, onRetry = { attempt++ })
                    }
                    listing == null -> item(key = "loading") { LoadingRow("Loading files…", Modifier.padding(top = Space.sm)) }
                    entries.isEmpty() -> item(key = "empty") { EmptyCard(Icons.Rounded.Folder, "Empty folder") }
                }
                itemsIndexed(entries, key = { _, e -> e.name }) { i, e ->
                    RemoteFileRow(e, divider = i < entries.lastIndex) {
                        val child = remoteChild(path, e.name)
                        if (e.dir) path = child else opened = child
                    }
                }
                if (listing?.truncated == true) {
                    item(key = "more") {
                        Text("Only the first ${entries.size} entries are shown.", style = Pi.type.rowMeta, color = colors.ink3, modifier = Modifier.padding(horizontal = Space.page, vertical = Space.lg))
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = opened != null,
            enter = fadeIn(PiMotion.enter(PiMotion.SHEET)) + slideInVertically(PiMotion.enter(PiMotion.SHEET)) { it / 10 },
            exit = fadeOut(PiMotion.leave(PiMotion.SHEET)),
        ) {
            val shown = remember { mutableStateOf(opened) }
            opened?.let { shown.value = it }
            shown.value?.let { rel ->
                FileViewer(
                    root = File(cwd),
                    tabs = tabs,
                    rel = rel,
                    onSelect = { opened = it },
                    onClose = { opened = null },
                    onChanged = {},
                    onAsk = onAsk,
                    source = { review.readFile(it) },
                )
            }
        }
    }
}

/**
 * One entry, as the Files tab draws it: the type's glyph, the name (dot files dimmed), the size of a file, a chevron
 * for a folder. A link out of the session's folder cannot be opened and says so.
 */
@Composable
private fun RemoteFileRow(entry: RemoteDirEntry, divider: Boolean, onClick: () -> Unit) {
    val colors = Pi.colors
    val kind = if (entry.dir) null else FileTypes.kind(entry.name)
    val meta = when {
        entry.outside -> "Link outside the folder"
        entry.dir -> null
        entry.file -> ProjectFiles.size(entry.size)
        else -> null
    }
    val openable = !entry.outside && (entry.dir || entry.file)
    InsetRow(
        title = entry.name,
        onClick = { if (openable) onClick() },
        label = if (entry.dir) "Open folder" else "Open file",
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = (if (entry.dir) "Folder ${entry.name}" else "${entry.name}, ${FileTypes.label(entry.name)}") + (meta?.let { ", $it" } ?: "")
        },
        titleModifier = if (entry.name.startsWith(".")) Modifier.alpha(DOT_FILE_ALPHA) else Modifier,
        leading = {
            Icon(
                when {
                    entry.outside -> Icons.Rounded.FolderOff
                    kind == null -> Icons.Rounded.Folder
                    kind == FileKind.Code -> Icons.Rounded.Code
                    kind == FileKind.Markdown || kind == FileKind.Text -> Icons.Rounded.Description
                    kind == FileKind.Image -> Icons.Rounded.Image
                    else -> Icons.AutoMirrored.Rounded.InsertDriveFile
                },
                contentDescription = null,
                tint = if (entry.dir) colors.ink2 else colors.ink3,
                modifier = Modifier.size(IconSize.md),
            )
        },
        trailing = if (meta != null) ({ Text(meta, style = Pi.type.rowMeta.copy(fontFeatureSettings = Tnum), color = colors.ink3, maxLines = 1) }) else null,
        chevron = entry.dir && openable,
        divider = divider,
    )
}
