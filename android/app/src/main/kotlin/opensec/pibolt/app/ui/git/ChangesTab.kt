package opensec.pibolt.app.ui.git

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.rounded.CallMerge
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Commit
import androidx.compose.ui.semantics.heading
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import opensec.pibolt.app.ui.projects.EmptyCard
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.data.ProjectStore
import opensec.pibolt.app.git.StatusEntry
import opensec.pibolt.app.git.github.GitHubRemote
import opensec.pibolt.app.git.github.GitHubState
import opensec.pibolt.app.engine.EngineStatus
import opensec.pibolt.app.ui.components.ConfirmSheet
import opensec.pibolt.app.ui.components.ErrorState
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.LoadingRow
import opensec.pibolt.app.ui.components.PiTextField
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.GlassChip
import opensec.pibolt.app.ui.components.GlassPill
import opensec.pibolt.app.ui.components.InsetRow
import opensec.pibolt.app.ui.components.InsetSectionHead
import opensec.pibolt.app.ui.components.LineCounts
import opensec.pibolt.app.ui.components.WorkingLine
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.SnackKind
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.components.show
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Space
import java.io.File

/** What the Changes tab has open on top of the project screen. */
sealed interface GitOverlay {
    data class Diff(val path: String, val staged: Boolean) : GitOverlay

    data object History : GitOverlay

    companion object {
        /** The overlay as a string for the saved state ("h", "s:path", "u:path"), so it stays open across rotation. */
        fun encode(o: GitOverlay?): String = when (o) {
            null -> ""
            History -> "h"
            is Diff -> (if (o.staged) "s:" else "u:") + o.path
        }

        fun decode(s: String): GitOverlay? = when {
            s == "h" -> History
            s.startsWith("s:") -> Diff(s.substring(2), staged = true)
            s.startsWith("u:") -> Diff(s.substring(2), staged = false)
            else -> null
        }
    }
}

sealed interface GitSheet {
    data object Branches : GitSheet

    data object PullRequest : GitSheet

    data object Identity : GitSheet

    data class Discard(val entry: StatusEntry, val staged: Boolean) : GitSheet
}

/**
 * The Changes tab's UI state on top of [ChangesModel]: the commit message, Pi's draft, open sheets and overlays. The
 * message and the overlay live in [saved] state, so a rotation keeps the message being written and the diff open.
 */
@Stable
class ChangesUi(
    val app: PiBoltApp,
    val model: ChangesModel,
    val store: ProjectStore,
    private val scope: CoroutineScope,
    private val snack: (String, SnackKind) -> Unit,
    messageState: MutableState<String> = mutableStateOf(""),
    private val overlayState: MutableState<String> = mutableStateOf(""),
) {
    var message by messageState
    var drafting by mutableStateOf(false)
        private set
    var overlay: GitOverlay?
        get() = GitOverlay.decode(overlayState.value)
        set(value) {
            overlayState.value = GitOverlay.encode(value)
        }
    var sheet by mutableStateOf<GitSheet?>(null)

    fun error(text: String) = snack(text, SnackKind.Error)

    fun info(text: String) = snack(text, SnackKind.Info)

    /** "Ask Pi for a message": Pi drafts from the staged diff; the answer fills the message field. */
    fun askPi() {
        if (drafting) return
        drafting = true
        scope.launch {
            runCatching { PiDraft.ask(app.engine, store, model.dir, PiDraft.COMMIT_PROMPT) }
                .onSuccess { draft -> if (draft != null) message = draft else error("Pi did not send a message. Open the session to see why.") }
                .onFailure { error(it.message ?: "Could not ask Pi") }
            drafting = false
        }
    }

    /** Commits the staged changes; with [who], saves the name and email first (the Identity sheet). */
    fun commit(who: Pair<String, String>? = null) {
        val text = message.trim()
        if (text.isEmpty()) return
        model.run("Committing") {
            who?.let { setGlobalIdentity(it.first, it.second) }
            val (name, email) = identity()
            if (name == null || email == null) {
                sheet = GitSheet.Identity
                return@run
            }
            val hash = commit(text)
            message = ""
            info("Committed $hash")
        }
    }

    fun push() {
        val upstream = model.status?.branch?.upstream
        model.run("Pushing") { p ->
            push(upstream, p)
            info("Pushed")
        }
    }

    fun pull() = model.run("Pulling") { p ->
        pull(p)
        info("Up to date with the remote")
    }
}

@Composable
fun rememberChangesUi(app: PiBoltApp, dir: File, store: ProjectStore, snackbar: SnackbarHostState): ChangesUi {
    val scope = rememberCoroutineScope()
    val message = rememberSaveable(dir) { mutableStateOf("") }
    val overlay = rememberSaveable(dir) { mutableStateOf("") }
    return remember(dir) {
        val snack: (String, SnackKind) -> Unit = { text, kind -> scope.launch { snackbar.show(text, kind) } }
        val model = ChangesModel(app.git, dir, scope) { snack(it, SnackKind.Error) }
        ChangesUi(app, model, store, scope, snack, message, overlay)
    }
}

/**
 * The project screen's Changes tab, as items of its list: the branch with push and pull, the commit box as soon as
 * something is staged (so committing never needs a scroll past the file list), then conflicts, staged and unstaged
 * files (tap for the diff, the trailing button stages or unstages, long press discards).
 */
fun LazyListScope.changesItems(ui: ChangesUi) {
    val model = ui.model
    val repo = model.isRepo
    val failed = model.failed
    if (failed != null && model.status == null) {
        item(key = "git-failed", contentType = "git-note") {
            ErrorState("Could not read the changes", Modifier.padding(horizontal = Space.page, vertical = Space.sm), message = failed, onRetry = model::refresh)
        }
        return
    }
    if (repo == null) {
        item(key = "git-loading", contentType = "loading") { LoadingRow("Loading changes…") }
        return
    }
    if (!repo) {
        item(key = "git-none", contentType = "git-card") { NotARepo(ui) }
        return
    }
    val status = model.status
    if (status == null) {
        item(key = "git-loading", contentType = "loading") { LoadingRow("Loading changes…") }
        return
    }
    item(key = "git-branch", contentType = "git-card") { BranchHeader(ui) }
    val staged = status.staged
    val unstaged = status.unstaged
    if (!model.readOnly && ChangesRules.showCommit(staged.size, ui.message)) item(key = "git-commit", contentType = "git-card") { CommitBox(ui, staged.size) }
    if (status.conflicts.isNotEmpty()) {
        item(key = "git-h-conflicts", contentType = "git-head") { InsetSectionHead("Conflicts", count = status.conflicts.size) }
        itemsIndexed(status.conflicts, key = { _, it -> "gc:" + it.path }, contentType = { _, _ -> "git-file" }) { i, e -> FileRow(ui, e, staged = false, divider = i < status.conflicts.lastIndex) }
    }
    if (staged.isNotEmpty()) {
        item(key = "git-h-staged", contentType = "git-head") {
            InsetSectionHead("Staged", count = staged.size, action = if (model.readOnly) null else ({
                TextAction("Unstage all", onClick = { model.run("Unstaging") { unstage(staged.map { it.path }, status.branch.initial) } }, modifier = Modifier.heightIn(min = 48.dp), color = Pi.colors.ink2, enabled = model.busy == null)
            }))
        }
        itemsIndexed(staged, key = { _, it -> "gs:" + it.path }, contentType = { _, _ -> "git-file" }) { i, e -> FileRow(ui, e, staged = true, divider = i < staged.lastIndex) }
    }
    if (unstaged.isNotEmpty()) {
        item(key = "git-h-changes", contentType = "git-head") {
            InsetSectionHead("Changes", count = unstaged.size, action = if (model.readOnly) null else ({
                TextAction("Stage all", onClick = { model.run("Staging") { stage(unstaged.map { it.path }) } }, modifier = Modifier.heightIn(min = 48.dp), color = Pi.colors.ink2, enabled = model.busy == null)
            }))
        }
        itemsIndexed(unstaged, key = { _, it -> "gu:" + it.path }, contentType = { _, _ -> "git-file" }) { i, e -> FileRow(ui, e, staged = false, divider = i < unstaged.lastIndex) }
    }
    if (status.clean) {
        item(key = "git-clean", contentType = "git-note") {
            EmptyCard(Icons.Rounded.Check, "No changes")
        }
    }
}

/** Pure rules of the Changes tab (unit-tested). */
object ChangesRules {
    /** The commit box shows once something is staged, and stays while a message is being written. */
    fun showCommit(stagedCount: Int, message: String): Boolean = stagedCount > 0 || message.isNotBlank()

    /** The branch header's second line: "origin/main · 2 to push · 1 to pull", "Not pushed yet", "No remote". */
    fun tracking(upstream: String?, hasRemote: Boolean, ahead: Int, behind: Int): String = buildList {
        add(upstream ?: if (hasRemote) "Not pushed yet" else "No remote")
        if (ahead > 0) add("$ahead to push")
        if (behind > 0) add("$behind to pull")
    }.joinToString(" · ")

    /** The "+N −M" pill in words. */
    fun countsSpoken(added: Int, removed: Int): String = buildList {
        if (added > 0) add(if (added == 1) "1 line added" else "$added lines added")
        if (removed > 0) add(if (removed == 1) "1 line removed" else "$removed lines removed")
    }.joinToString(", ")
}

@Composable
private fun NotARepo(ui: ChangesUi) {
    if (ui.model.readOnly) {
        EmptyCard(Icons.AutoMirrored.Rounded.CallSplit, "Not a git repository", "The session's folder on the computer has no git history")
        return
    }
    EmptyCard(Icons.AutoMirrored.Rounded.CallSplit, "Not a git repository") {
        SecondaryButton(if (ui.model.busy != null) "Setting up…" else "Initialise git", onClick = { ui.model.run("Initialising") { init() } }, enabled = ui.model.busy == null, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * The branch as the tab's calm header (no card): the branch glyph, the branch (rowTitle ink1, a chooser glyph) over
 * "origin/main · 2 to push · 1 to pull" (rowMeta ink3), tap for the Branches sheet, and a glass pill with the
 * working tree's "+N −M". Then, while an operation runs, its working line (a Run Quad and git's progress); the
 * actions as glass chips (Pull, Push, Pull request, History) and a line on what is missing to push.
 */
@Composable
private fun BranchHeader(ui: ChangesUi) {
    val colors = Pi.colors
    val type = Pi.type
    val model = ui.model
    val status = model.status ?: return
    val b = status.branch
    val busy = model.busy
    val github by ui.app.github.state.collectAsStateWithLifecycle()
    val total = model.stats.total
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.sm)
                .heightIn(min = 64.dp)
                .then(if (model.readOnly) Modifier else Modifier.pressable({ ui.sheet = GitSheet.Branches }, shape = RoundedCornerShape(Radius.md), pressedScale = 1f, label = "Change branch"))
                .padding(horizontal = Space.page - Space.sm, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Rounded.CallSplit, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(IconSize.md))
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(b.head ?: "Detached at ${b.oid?.take(7).orEmpty()}", style = type.rowTitle, color = colors.ink1, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f, fill = false))
                    if (!model.readOnly) {
                        Spacer(Modifier.width(Space.xs))
                        Icon(Icons.Rounded.UnfoldMore, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(IconSize.sm))
                    }
                }
                Text(ChangesRules.tracking(b.upstream, model.remote != null || model.readOnly, b.ahead, b.behind), style = type.rowMeta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (total.added > 0 || total.removed > 0) {
                Spacer(Modifier.width(Space.sm))
                GlassPill(Modifier.semantics(mergeDescendants = true) { contentDescription = ChangesRules.countsSpoken(total.added, total.removed) }) {
                    LineCounts(total.added, total.removed)
                }
            }
        }
        if (busy != null) {
            WorkingLine(
                model.progress?.label ?: "$busy…",
                Modifier.padding(horizontal = Space.page, vertical = Space.xs).semantics(mergeDescendants = true) { },
                mode = QuadMode.Run,
            )
        }
        val hasRemote = model.remote != null
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.page),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!model.readOnly) {
                GlassChip("Pull", onClick = ui::pull, icon = Icons.Rounded.ArrowDownward, enabled = busy == null && hasRemote && b.upstream != null)
                GlassChip("Push", onClick = ui::push, icon = Icons.Rounded.ArrowUpward, enabled = busy == null && hasRemote && !b.detached && !b.initial)
            }
            val onGitHub = model.remote?.let { GitHubRemote.parse(it) } != null
            if (!model.readOnly && onGitHub && !b.detached && !b.initial) {
                GlassChip("Pull request", onClick = { ui.sheet = GitSheet.PullRequest }, icon = Icons.AutoMirrored.Rounded.CallMerge, enabled = busy == null && github is GitHubState.SignedIn)
            }
            GlassChip("History", onClick = { ui.overlay = GitOverlay.History }, icon = Icons.Rounded.History)
        }
        val hint = when {
            model.readOnly -> null
            !hasRemote -> "No remote. Ask Pi to add one."
            github !is GitHubState.SignedIn && model.remote?.let { GitHubRemote.parse(it) } != null -> "Sign in to GitHub in Settings to push and open pull requests."
            else -> null
        }
        if (hint != null) Text(hint, style = type.rowMeta, color = colors.ink3, modifier = Modifier.padding(start = Space.page, end = Space.page, top = Space.xs))
    }
}

/**
 * One changed file, an [InsetRow]: the change letter in the leading slot, the file name over its folder (or "from
 * old/path" for a staged rename), its "+N −M" and a 48dp + / − button to stage or unstage. Tap opens the diff; long
 * press asks to discard.
 */
@Composable
private fun FileRow(ui: ChangesUi, entry: StatusEntry, staged: Boolean, divider: Boolean) {
    val model = ui.model
    val change = if (staged) entry.index else entry.worktree ?: entry.index
    val (name, folder) = splitPath(entry.path)
    val initial = model.status?.branch?.initial == true
    val counts = model.stats.of(entry.path, staged)
    InsetRow(
        title = name,
        onClick = { ui.overlay = GitOverlay.Diff(entry.path, staged) },
        onLongClick = if (!entry.conflicted && !model.readOnly) ({ ui.sheet = GitSheet.Discard(entry, staged) }) else null,
        label = "Show the diff of ${entry.path}",
        meta = if (entry.origPath != null && staged) "from ${entry.origPath}" else folder.ifEmpty { "/" },
        leading = { ChangeBadge(change) },
        divider = divider,
        trailing = {
            if (counts != null) LineCounts(counts.added, counts.removed, Modifier.padding(end = Space.xs))
            when {
                model.readOnly -> Unit
                entry.conflicted -> IconAction(Icons.Rounded.Add, "Mark ${entry.path} resolved", onClick = { model.run("Staging") { stage(listOf(entry.path)) } }, enabled = model.busy == null)
                staged -> IconAction(Icons.Rounded.Remove, "Unstage ${entry.path}", onClick = { model.run("Unstaging") { unstage(listOf(entry.path), initial) } }, enabled = model.busy == null)
                else -> IconAction(Icons.Rounded.Add, "Stage ${entry.path}", onClick = { model.run("Staging") { stage(listOf(entry.path)) } }, enabled = model.busy == null)
            }
        },
    )
}

/**
 * The commit flow, right under the branch: "Commit 2 files" (rowTitle ink1, a heading), the message field (2 to 8
 * lines), then "Ask Pi" (secondary: Pi drafts from the staged diff) and Commit (primary), at the 20dp gutter.
 */
@Composable
private fun CommitBox(ui: ChangesUi, stagedCount: Int) {
    val engineStatus by ui.app.engine.status.collectAsStateWithLifecycle()
    val colors = Pi.colors
    val type = Pi.type
    val busy = ui.model.busy
    Column(Modifier.fillMaxWidth().padding(start = Space.page, end = Space.page, top = Space.lg, bottom = Space.xs)) {
        Text(if (stagedCount == 1) "Commit 1 file" else "Commit $stagedCount files", style = type.rowTitle, color = colors.ink1, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(Space.sm))
        PiTextField(
            ui.message,
            { ui.message = it },
            placeholder = if (stagedCount == 0) "Stage files to commit them" else "What changed, and why",
            minLines = 2,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Commit message" },
        )
        Spacer(Modifier.height(Space.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            SecondaryButton(
                if (ui.drafting) "Pi is writing…" else "Ask Pi",
                onClick = ui::askPi,
                icon = Icons.Rounded.AutoAwesome,
                enabled = !ui.drafting && stagedCount > 0 && engineStatus !is EngineStatus.Unavailable,
                modifier = Modifier.weight(1f).semantics { contentDescription = "Ask Pi for a message" },
            )
            PrimaryButton(
                if (busy == "Committing") "Committing…" else "Commit",
                onClick = ui::commit,
                icon = Icons.Rounded.Commit,
                enabled = busy == null && stagedCount > 0 && ui.message.isNotBlank(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Overlays and sheets of the Changes tab, drawn by the project screen above its list: the file diff, the history,
 * branches, the pull request sheet, the identity sheet and the discard confirmation.
 */
@Composable
fun ChangesOverlays(ui: ChangesUi, visible: Boolean) {
    val model = ui.model
    LaunchedEffect(visible) { if (visible) model.refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (visible) model.refresh() }
    // Registered before the overlays' own handlers, so a commit open in the history closes before the history.
    BackHandler(enabled = visible && ui.overlay != null) { ui.overlay = null }
    AnimatedVisibility(
        visible = visible && ui.overlay != null,
        enter = fadeIn(PiMotion.enter(PiMotion.SHEET)) + slideInVertically(PiMotion.enter(PiMotion.SHEET)) { it / 10 },
        exit = fadeOut(PiMotion.leave(PiMotion.SHEET)),
    ) {
        val shown = remember { mutableStateOf(ui.overlay) }
        ui.overlay?.let { shown.value = it }
        when (val o = shown.value) {
            is GitOverlay.Diff -> DiffViewer(ui, o.path, o.staged, onClose = { ui.overlay = null })
            GitOverlay.History -> HistoryViewer(ui, onClose = { ui.overlay = null })
            null -> Unit
        }
    }
    when (val s = ui.sheet) {
        GitSheet.Branches -> BranchSheet(ui, onDismiss = { ui.sheet = null })
        GitSheet.PullRequest -> PullRequestSheet(ui, onDismiss = { ui.sheet = null })
        GitSheet.Identity -> IdentitySheet(ui, onDismiss = { ui.sheet = null })
        is GitSheet.Discard -> ConfirmSheet(
            title = if (s.entry.untracked) "Delete ${splitPath(s.entry.path).first}?" else "Discard changes?",
            message = when {
                s.entry.untracked -> "${s.entry.path} is untracked, so it can't be restored."
                s.staged -> "${s.entry.path} goes back to its last commit, staged changes included. This can't be undone."
                else -> "Unstaged changes to ${s.entry.path} are discarded. This can't be undone."
            },
            confirmLabel = if (s.entry.untracked) "Delete" else "Discard",
            onConfirm = {
                ui.sheet = null
                model.run("Discarding") { discard(s.entry, s.staged) }
            },
            onDismiss = { ui.sheet = null },
        )
        null -> Unit
    }
}
