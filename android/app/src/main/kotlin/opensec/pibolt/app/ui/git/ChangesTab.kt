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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import opensec.pibolt.app.ui.projects.EmptyCard
import opensec.pibolt.app.ui.projects.LoadingRow
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.PiTextField
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.SectionLabel
import opensec.pibolt.app.ui.components.SnackKind
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.components.show
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import java.io.File

/** What the Changes tab has open on top of the project screen. */
sealed interface GitOverlay {
    data class Diff(val path: String, val staged: Boolean) : GitOverlay

    data object History : GitOverlay
}

sealed interface GitSheet {
    data object Branches : GitSheet

    data object PullRequest : GitSheet

    data object Identity : GitSheet

    data class Discard(val entry: StatusEntry, val staged: Boolean) : GitSheet
}

/** The Changes tab's UI state on top of [ChangesModel]: the commit message, Pi's draft, open sheets and overlays. */
@Stable
class ChangesUi(val app: PiBoltApp, val model: ChangesModel, val store: ProjectStore, private val scope: CoroutineScope, private val snack: (String, SnackKind) -> Unit) {
    var message by mutableStateOf("")
    var drafting by mutableStateOf(false)
        private set
    var overlay by mutableStateOf<GitOverlay?>(null)
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
    return remember(dir) {
        val snack: (String, SnackKind) -> Unit = { text, kind -> scope.launch { snackbar.show(text, kind) } }
        val model = ChangesModel(app.git, dir, scope) { snack(it, SnackKind.Error) }
        ChangesUi(app, model, store, scope, snack)
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
    if (repo == null) {
        item(key = "git-loading", contentType = "loading") { LoadingRow("Reading git status") }
        return
    }
    if (!repo) {
        item(key = "git-none", contentType = "git-card") { NotARepo(ui) }
        return
    }
    val status = model.status
    if (status == null) {
        item(key = "git-loading", contentType = "loading") { LoadingRow("Reading git status") }
        return
    }
    item(key = "git-branch", contentType = "git-card") { BranchCard(ui) }
    val staged = status.staged
    val unstaged = status.unstaged
    if (ChangesRules.showCommit(staged.size, ui.message)) item(key = "git-commit", contentType = "git-card") { CommitCard(ui, staged.size) }
    if (status.conflicts.isNotEmpty()) {
        item(key = "git-h-conflicts", contentType = "git-head") { Header("Conflicts · ${status.conflicts.size}", null, null) }
        items(status.conflicts, key = { "gc:" + it.path }, contentType = { "git-file" }) { FileRow(ui, it, staged = false) }
    }
    if (staged.isNotEmpty()) {
        item(key = "git-h-staged", contentType = "git-head") {
            Header("Staged · ${staged.size}", "Unstage all") { model.run("Unstaging") { unstage(staged.map { it.path }, status.branch.initial) } }
        }
        items(staged, key = { "gs:" + it.path }, contentType = { "git-file" }) { FileRow(ui, it, staged = true) }
    }
    if (unstaged.isNotEmpty()) {
        item(key = "git-h-changes", contentType = "git-head") {
            Header("Changes · ${unstaged.size}", "Stage all") { model.run("Staging") { stage(unstaged.map { it.path }) } }
        }
        if (staged.isEmpty()) {
            item(key = "git-hint", contentType = "git-note") {
                Text("Stage the files to commit with +, or Stage all.", style = Pi.type.meta, color = Pi.colors.ink3, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp))
            }
        }
        items(unstaged, key = { "gu:" + it.path }, contentType = { "git-file" }) { FileRow(ui, it, staged = false) }
    }
    if (status.clean) {
        item(key = "git-clean", contentType = "git-note") {
            EmptyCard(Icons.Rounded.Check, "No changes", "Everything is committed. Edits by you or Pi show up here to review and commit.")
        }
    }
}

/** Pure rules of the Changes tab (unit-tested). */
object ChangesRules {
    /** The commit box shows once something is staged, and stays while a message is being written. */
    fun showCommit(stagedCount: Int, message: String): Boolean = stagedCount > 0 || message.isNotBlank()
}

@Composable
private fun NotARepo(ui: ChangesUi) {
    EmptyCard(
        Icons.AutoMirrored.Rounded.CallSplit,
        "Not a git repository",
        "Track this project's changes with git to review, commit and push them here.",
    ) {
        SecondaryButton(if (ui.model.busy != null) "Setting up…" else "Initialise git", onClick = { ui.model.run("Initialising") { init() } }, enabled = ui.model.busy == null, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun BranchCard(ui: ChangesUi) {
    val colors = Pi.colors
    val type = Pi.type
    val model = ui.model
    val status = model.status ?: return
    val b = status.branch
    val busy = model.busy
    val github by ui.app.github.state.collectAsStateWithLifecycle()
    GitCard {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .pressable({ ui.sheet = GitSheet.Branches }, label = "Change branch")
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Rounded.CallSplit, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(b.head ?: "Detached at ${b.oid?.take(7).orEmpty()}", style = type.bodyStrong, color = colors.ink1, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                val track = buildList {
                    add(b.upstream ?: if (model.remote == null) "No remote" else "Not pushed yet")
                    if (b.ahead > 0) add("${b.ahead} to push")
                    if (b.behind > 0) add("${b.behind} to pull")
                }
                Text(track.joinToString(" · "), style = type.meta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.UnfoldMore, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(18.dp))
        }
        if (busy != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                QuadSpinner(QuadMode.Run, size = 16.dp, color = colors.ink2)
                Spacer(Modifier.width(10.dp))
                Text(model.progress?.label ?: "$busy…", style = type.meta, color = colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val hasRemote = model.remote != null
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextAction("Pull", onClick = ui::pull, modifier = Modifier.heightIn(min = 48.dp), enabled = busy == null && hasRemote && b.upstream != null, color = colors.ink1)
            TextAction("Push", onClick = ui::push, modifier = Modifier.heightIn(min = 48.dp), enabled = busy == null && hasRemote && !b.detached && !b.initial, color = colors.ink1)
            Spacer(Modifier.weight(1f))
            val onGitHub = model.remote?.let { GitHubRemote.parse(it) } != null
            if (onGitHub && !b.detached && !b.initial) {
                TextAction("Pull request", onClick = { ui.sheet = GitSheet.PullRequest }, modifier = Modifier.heightIn(min = 48.dp), enabled = busy == null && github is GitHubState.SignedIn, color = colors.accent)
            }
            IconAction(Icons.Rounded.History, "History", onClick = { ui.overlay = GitOverlay.History })
        }
        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
            if (!hasRemote) Text("Add a remote (ask Pi) to push and pull.", style = type.meta, color = colors.ink3)
            else if (github !is GitHubState.SignedIn && model.remote?.let { GitHubRemote.parse(it) } != null) {
                Text("Sign in to GitHub in Settings to push, pull private repositories and open pull requests.", style = type.meta, color = colors.ink3)
            }
        }
    }
}

@Composable
private fun Header(title: String, action: String?, onAction: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(title, Modifier.weight(1f))
        if (action != null && onAction != null) TextAction(action, onClick = onAction, modifier = Modifier.heightIn(min = 48.dp), color = Pi.colors.ink2)
    }
}

/** 56dp: change letter, file name over its folder, and the stage / unstage button. */
@Composable
private fun FileRow(ui: ChangesUi, entry: StatusEntry, staged: Boolean) {
    val colors = Pi.colors
    val type = Pi.type
    val model = ui.model
    val change = if (staged) entry.index else entry.worktree ?: entry.index
    val (name, folder) = splitPath(entry.path)
    val initial = model.status?.branch?.initial == true
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .pressable(
                onClick = { ui.overlay = GitOverlay.Diff(entry.path, staged) },
                onLongClick = { if (!entry.conflicted) ui.sheet = GitSheet.Discard(entry, staged) },
                label = "Show the diff of ${entry.path}",
            )
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChangeBadge(change)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = type.body, color = colors.ink1, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            val sub = buildString {
                if (entry.origPath != null && staged) append("from ").append(entry.origPath) else append(folder.ifEmpty { "/" })
            }
            Text(sub, style = type.monoMeta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
        when {
            entry.conflicted -> IconAction(Icons.Rounded.Add, "Mark ${entry.path} resolved", onClick = { model.run("Staging") { stage(listOf(entry.path)) } }, enabled = model.busy == null)
            staged -> IconAction(Icons.Rounded.Remove, "Unstage ${entry.path}", onClick = { model.run("Unstaging") { unstage(listOf(entry.path), initial) } }, enabled = model.busy == null)
            else -> IconAction(Icons.Rounded.Add, "Stage ${entry.path}", onClick = { model.run("Staging") { stage(listOf(entry.path)) } }, enabled = model.busy == null)
        }
    }
}

@Composable
private fun CommitCard(ui: ChangesUi, stagedCount: Int) {
    val engineStatus by ui.app.engine.status.collectAsStateWithLifecycle()
    val colors = Pi.colors
    val type = Pi.type
    val busy = ui.model.busy
    GitCard {
        Column(Modifier.padding(16.dp)) {
            Text(if (stagedCount == 1) "Commit 1 file" else "Commit $stagedCount files", style = type.bodyStrong, color = colors.ink1)
            Spacer(Modifier.height(8.dp))
            PiTextField(
                ui.message,
                { ui.message = it },
                placeholder = if (stagedCount == 0) "Stage files to commit them" else "What changed, and why",
                minLines = 2,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Commit message" },
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    enabled = busy == null && stagedCount > 0 && ui.message.isNotBlank(),
                    modifier = Modifier.weight(1f),
                )
            }
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
                s.entry.untracked -> "${s.entry.path} is not tracked by git: it is deleted and can't be restored."
                s.staged -> "${s.entry.path} goes back to its last committed version, staged changes included. This can't be undone."
                else -> "The unstaged changes to ${s.entry.path} are thrown away. This can't be undone."
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
