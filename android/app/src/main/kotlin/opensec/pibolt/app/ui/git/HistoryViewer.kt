package opensec.pibolt.app.ui.git

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.rounded.Commit
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import opensec.pibolt.app.ui.components.InsetRow
import opensec.pibolt.app.ui.components.LineCounts
import opensec.pibolt.app.ui.components.PageTitle
import opensec.pibolt.app.ui.components.Tag
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Space
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.History
import opensec.pibolt.app.ui.components.ErrorState
import opensec.pibolt.app.ui.components.LoadingRow
import opensec.pibolt.app.ui.projects.EmptyCard
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.git.Commit
import opensec.pibolt.app.git.FileDiff
import opensec.pibolt.app.git.GitException
import opensec.pibolt.app.ui.components.CopyButton
import opensec.pibolt.app.ui.components.DiffBlock
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.ui.components.diffText
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.markdown.CodeInfo
import opensec.pibolt.app.ui.projects.ProjectRules
import opensec.pibolt.app.ui.session.parseDiff
import opensec.pibolt.app.ui.theme.Pi
import java.text.DateFormat
import java.util.Date

private const val PAGE = 100

/** The branch's log, newest first, 100 at a time; a commit opens its message, author and per-file diffs. */
@Composable
fun HistoryViewer(ui: ChangesUi, onClose: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val model = ui.model
    val commits = remember { mutableStateListOf<Commit>() }
    var end by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(0) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    // A failed read is an error with Try again, never an empty history.
    var failed by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var loadedVersion by remember { mutableStateOf(-1) }
    LaunchedEffect(page, model.version, attempt) {
        loading = true
        failed = null
        try {
            // After a change (a pull, a commit) every page shown so far is read again from the top.
            val fresh = page == 0 || model.version != loadedVersion
            val want = if (fresh) (page + 1) * PAGE else PAGE
            val got = model.source.log(want, skip = if (fresh) 0 else page * PAGE)
            val next = HistoryPages.merge(if (fresh) emptyList() else commits.toList(), got)
            commits.clear()
            commits += next
            loadedVersion = model.version
            end = got.size < want
        } catch (e: GitException) {
            if (commits.isEmpty()) failed = e.message ?: "Git could not read the history." else ui.error(e.message ?: "Could not read the history")
            end = true
        }
        loading = false
    }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val now = remember(commits.size) { System.currentTimeMillis() }
    val list = rememberLazyListState()
    val pastTitle by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }
    val branch = model.status?.branch?.head
    Box(Modifier.fillMaxSize().background(colors.canvas)) {
        Column(Modifier.fillMaxSize()) {
            OverlayBar("History", branch, onBack = onClose, titleVisible = pastTitle)
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = Space.xxl + bottom)) {
                item(key = "title") { PageTitle("History", meta = branch) }
                when {
                    failed != null -> item(key = "error") {
                        ErrorState("Could not read the history", Modifier.padding(horizontal = Space.page, vertical = Space.sm), message = failed, onRetry = {
                            end = false
                            attempt++
                        })
                    }
                    loading && commits.isEmpty() -> item(key = "loading") { LoadingRow("Loading history…", Modifier.padding(top = Space.sm)) }
                    commits.isEmpty() -> item(key = "empty") {
                        EmptyCard(Icons.Rounded.History, "No commits yet")
                    }
                }
                itemsIndexed(commits, key = { _, c -> c.hash }) { i, c ->
                    val refs = c.refs.filter { it != "HEAD" }
                    InsetRow(
                        title = c.subject.ifBlank { "(no message)" },
                        onClick = { open = c.hash },
                        label = "Open commit ${c.short}",
                        // In words: the full time ("2 hours ago") and every ref.
                        modifier = Modifier.semantics(mergeDescendants = true) {
                            contentDescription = listOfNotNull(c.subject.ifBlank { "No message" }, c.short, c.author, ProjectRules.ago(c.time * 1000, now), refs.joinToString(", ").ifEmpty { null }, "merge".takeIf { c.merge }).joinToString(", ")
                        },
                        meta = buildList {
                            add(c.short)
                            add(ProjectRules.agoShort(c.time * 1000, now))
                            add(c.author)
                            if (c.merge) add("merge")
                        }.joinToString(" · "),
                        leading = {
                            Icon(Icons.Rounded.Commit, contentDescription = null, tint = if (refs.isNotEmpty()) colors.ink2 else colors.ink4, modifier = Modifier.size(IconSize.md))
                        },
                        trailing = {
                            // The first ref names the commit at a glance ("main", "v1.2"); the rest are counted.
                            if (refs.isNotEmpty()) Tag(refs.first().removePrefix("tag: ") + if (refs.size > 1) " +${refs.size - 1}" else "", Modifier.widthIn(max = 120.dp))
                        },
                        divider = i < commits.lastIndex || !end,
                    )
                }
                if (!end && commits.isNotEmpty()) {
                    item(key = "more") {
                        TextAction(if (loading) "Loading…" else "Load more", onClick = { if (!loading) page++ }, modifier = Modifier.padding(horizontal = Space.sm).heightIn(min = 48.dp), color = colors.ink2)
                    }
                }
            }
        }
        open?.let { hash -> CommitViewer(ui, hash, onClose = { open = null }) }
    }
    BackHandler(enabled = open != null) { open = null }
}

/** Paging of the history (pure, unit-tested). */
object HistoryPages {
    /**
     * [shown] with the page [got] after it, leaving out commits already shown: a commit made meanwhile (Pi, a shell)
     * shifts git's `--skip` pages, so a page can repeat the end of the one before, and a repeated key crashes the list.
     */
    fun merge(shown: List<Commit>, got: List<Commit>): List<Commit> {
        val seen = shown.mapTo(HashSet()) { it.hash }
        return shown + got.filter { seen.add(it.hash) }
    }
}

/** One commit: subject and body, author and date, the hash to copy, then each changed file (tap to fold its diff). */
@Composable
private fun CommitViewer(ui: ChangesUi, hash: String, onClose: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    var commit by remember(hash) { mutableStateOf<Commit?>(null) }
    var files by remember(hash) { mutableStateOf<List<FileDiff>>(emptyList()) }
    val folded = remember(hash) { mutableStateListOf<Int>() }
    var failed by remember(hash) { mutableStateOf<String?>(null) }
    var attempt by remember(hash) { mutableStateOf(0) }
    LaunchedEffect(hash, attempt) {
        failed = null
        try {
            val (c, f) = ui.model.source.show(hash)
            commit = c
            files = f
            // Many files: start folded, so the list opens fast.
            if (f.size > 3) folded += f.indices
        } catch (e: GitException) {
            failed = e.message ?: "Git could not read this commit."
        }
    }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(Modifier.fillMaxSize().background(colors.canvas)) {
        // A step inside the history: back, not close.
        OverlayBar(commit?.subject ?: "Commit", hash.take(12), onBack = onClose, back = true, backLabel = "Back to history") {
            CopyButton(text = { hash }, label = false, description = "Copy the commit hash")
        }
        val c = commit ?: run {
            val error = failed
            if (error != null) {
                ErrorState("Could not open the commit", Modifier.padding(horizontal = Space.page, vertical = Space.sm), message = error, onRetry = { attempt++ })
            } else {
                LoadingRow("Loading commit…", Modifier.padding(top = 8.dp))
            }
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp + bottom)) {
            item(key = "head") {
                Column(Modifier.padding(start = Space.page, end = Space.page, top = Space.xs, bottom = Space.lg)) {
                    Text(c.subject, style = type.title, color = colors.ink1, modifier = Modifier.semantics { heading() })
                    if (c.body.isNotBlank()) {
                        Spacer(Modifier.height(Space.sm))
                        Text(c.body, style = type.body, color = colors.ink2)
                    }
                    Spacer(Modifier.height(Space.md))
                    val date = remember(c.time) { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(c.time * 1000)) }
                    Text("${c.author} <${c.email}>", style = type.rowMeta, color = colors.ink3)
                    Text(date, style = type.rowMeta, color = colors.ink3)
                    if (c.merge) Text("Merge: the diff is against the first parent", style = type.rowMeta, color = colors.ink3)
                }
            }
            itemsIndexed(files, key = { i, f -> "$i:${f.path}" }) { i, f ->
                val isFolded = i in folded
                GitCard {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .pressable({ if (isFolded) folded -= i else folded += i }, label = if (isFolded) "Show the diff of ${f.path}" else "Hide the diff of ${f.path}")
                            .padding(start = Space.sm, end = Space.lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = colors.ink3,
                            modifier = Modifier.size(20.dp).rotate(if (isFolded) 0f else 90f),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(f.path, style = type.monoSmall, color = colors.ink1, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        LineCounts(f.added, f.removed)
                    }
                    if (!isFolded) {
                        when {
                            f.binary -> Note("Binary file")
                            f.hunks.isEmpty() -> Note(if (f.oldPath != null) "Renamed from ${f.oldPath}" else "Mode change only")
                            f.rowCount() > MAX_ROWS -> Note("Too large to show (${f.rowCount()} lines).")
                            else -> {
                                val language = remember(f.path) { CodeInfo.languageOfFile(f.path).orEmpty() }
                                val text = remember(f, colors) { diffText(parseDiff(f.text), language, colors) }
                                DiffBlock(text)
                            }
                        }
                    }
                }
            }
        }
    }
}
