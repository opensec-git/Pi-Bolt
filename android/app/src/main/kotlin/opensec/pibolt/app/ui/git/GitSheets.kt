package opensec.pibolt.app.ui.git

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import opensec.pibolt.app.git.Branch
import opensec.pibolt.app.git.GitException
import opensec.pibolt.app.git.GitLogFormat
import opensec.pibolt.app.git.github.GitHubException
import opensec.pibolt.app.git.github.GitHubRemote
import opensec.pibolt.app.git.github.PullRequest
import opensec.pibolt.app.ui.components.CopyButton
import opensec.pibolt.app.ui.components.InsetRow
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.PiTextField
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.SOFT_PRESS
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.SectionLabel
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.IconSize

/** Local branches (current first), then remote ones; tap switches, the field on top creates and switches. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BranchSheet(ui: ChangesUi, onDismiss: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val model = ui.model
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    var branches by remember { mutableStateOf<List<Branch>?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) {
        branches = try {
            model.repo.branches()
        } catch (e: GitException) {
            ui.error(e.message ?: "Could not list branches")
            emptyList()
        }
    }
    val all = branches.orEmpty()
    val local = all.filter { !it.remote }.sortedByDescending { it.current }
    val localNames = local.map { it.name }.toSet()
    // A remote branch that already has a local one is reached through that.
    val remote = all.filter { it.remote && it.localName !in localNames }
    val error = if (name.isBlank()) null else GitLogFormat.branchNameError(name, localNames)
    val dirty = model.status?.clean == false

    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader("Branches", subtitle = if (dirty) "Uncommitted changes come with you." else null)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            PiTextField(
                name,
                { name = it },
                placeholder = "New branch name",
                singleLine = true,
                isError = error != null,
                style = type.mono,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                modifier = Modifier.weight(1f).semantics { contentDescription = "New branch name" },
            )
            Spacer(Modifier.width(10.dp))
            PrimaryButton("Create", onClick = {
                val n = name.trim()
                close { model.run("Creating the branch") { createBranch(n) } }
            }, enabled = name.isNotBlank() && error == null && model.busy == null)
        }
        if (error != null) Text(error, style = type.meta, color = colors.danger, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
            if (local.isNotEmpty()) item(key = "h-local") { SectionLabel("On this phone", Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)) }
            items(local, key = { "l:" + it.name }) { b -> BranchRow(b) { if (!b.current) close { model.run("Switching") { switchTo(b) } } else close(onDismiss) } }
            if (remote.isNotEmpty()) item(key = "h-remote") { SectionLabel("On the remote", Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)) }
            items(remote, key = { "r:" + it.name }) { b -> BranchRow(b) { close { model.run("Switching") { switchTo(b) } } } }
            if (branches != null && all.isEmpty()) item(key = "none") { Note("No branches yet. Make the first commit.") }
        }
    }
}

/** A branch, an [InsetRow]: the branch glyph, the name, its tracking and last subject; the current one highlighted with a check. */
@Composable
private fun BranchRow(b: Branch, onClick: () -> Unit) {
    val colors = Pi.colors
    val meta = listOfNotNull(b.track.takeIf { it.isNotBlank() }, b.subject.takeIf { it.isNotBlank() })
    InsetRow(
        title = b.name,
        onClick = onClick,
        label = if (b.current) "Current branch ${b.name}" else "Switch to ${b.name}",
        meta = meta.takeIf { it.isNotEmpty() }?.joinToString(" · "),
        leading = { Icon(Icons.AutoMirrored.Rounded.CallSplit, contentDescription = null, tint = if (b.current) colors.ink1 else colors.ink3, modifier = Modifier.size(IconSize.md)) },
        current = b.current,
        trailing = { if (b.current) Icon(Icons.Rounded.Check, contentDescription = "Current", tint = colors.success, modifier = Modifier.size(IconSize.md)) },
    )
}

/** Name and email for commits, once (global git config). Prefilled from the GitHub account when signed in. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentitySheet(ui: ChangesUi, onDismiss: () -> Unit) {
    val account = ui.app.github.account
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    var name by rememberSaveable { mutableStateOf(account?.name ?: account?.login.orEmpty()) }
    var email by rememberSaveable { mutableStateOf(account?.noreplyEmail.orEmpty()) }
    val ok = name.isNotBlank() && email.contains('@') && email.none { it.isWhitespace() }
    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader("Who is committing?", subtitle = "Used for commits in every project on this phone.")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PiTextField(name, { name = it }, placeholder = "Name", singleLine = true, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Name" })
            PiTextField(
                email,
                { email = it },
                placeholder = "Email",
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Email" },
            )
            if (account != null) Text("The GitHub no-reply address keeps your email private.", style = Pi.type.meta, color = Pi.colors.ink3)
            Spacer(Modifier.height(4.dp))
            PrimaryButton("Save and commit", onClick = {
                close { ui.commit(who = name to email) }
            }, enabled = ok, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * A pull request from the current branch: title and body (Pi can draft both), the base branch, then GitHub's REST API.
 * The branch is pushed first when it has commits the remote lacks. Afterwards the sheet shows the link.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullRequestSheet(ui: ChangesUi, onDismiss: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = ui.model
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val head = model.status?.branch?.head.orEmpty()
    val remote = model.remote?.let { GitHubRemote.parse(it) }
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var base by rememberSaveable { mutableStateOf<String?>(null) }
    var bases by remember { mutableStateOf<List<String>>(emptyList()) }
    var drafting by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf<PullRequest?>(null) }
    LaunchedEffect(remote) {
        if (remote == null) return@LaunchedEffect
        val default = runCatching { ui.app.github.api.repo(remote.first, remote.second).defaultBranch }.getOrNull()
        val remoteBranches = runCatching { model.repo.branches() }.getOrDefault(emptyList()).filter { it.remote }.map { it.localName }.distinct()
        bases = (listOfNotNull(default) + remoteBranches).distinct().filter { it != head }
        if (base == null) base = default?.takeIf { it != head } ?: bases.firstOrNull()
    }

    PiSheet(onDismiss = onDismiss, state = state) {
        val done = created
        if (done != null) {
            SheetHeader("Pull request #${done.number}", subtitle = done.title)
            Text(done.url, style = type.mono, color = colors.accent, maxLines = 2, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CopyButton(text = { done.url }, description = "Copy the pull request link")
                PrimaryButton("Open on GitHub", onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, done.url.toUri())) }
                }, modifier = Modifier.weight(1f))
            }
            return@PiSheet
        }
        SheetHeader("Pull request", subtitle = remote?.let { "${it.first}/${it.second} · from $head" })
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PiTextField(title, { title = it }, placeholder = "Title", singleLine = true, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Title" })
            PiTextField(body, { body = it }, placeholder = "Description (Markdown)", minLines = 4, maxLines = 10, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Description" })
            if (bases.isNotEmpty()) {
                Text("Merge into", style = type.metaStrong, color = colors.ink3)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (b in bases) BaseChip(b, selected = b == base) { base = b }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(
                    if (drafting) "Pi is writing…" else "Ask Pi",
                    onClick = {
                        val b = base ?: return@SecondaryButton
                        drafting = true
                        scope.launch {
                            runCatching { PiDraft.ask(ui.app.engine, ui.store, model.dir, PiDraft.pullRequestPrompt(b)) }
                                .onSuccess { d ->
                                    if (d == null) {
                                        ui.error("Pi did not send a draft. Open the session to see why.")
                                    } else {
                                        val (t, rest) = PiDraft.splitTitle(d)
                                        title = t
                                        body = rest
                                    }
                                }
                                .onFailure { ui.error(it.message ?: "Could not ask Pi") }
                            drafting = false
                        }
                    },
                    icon = Icons.Rounded.AutoAwesome,
                    enabled = !drafting && base != null,
                    modifier = Modifier.weight(1f).semantics { contentDescription = "Ask Pi to draft the pull request" },
                )
                PrimaryButton(
                    if (creating) "Creating…" else "Create",
                    onClick = {
                        val r = remote ?: return@PrimaryButton
                        val b = base ?: return@PrimaryButton
                        creating = true
                        scope.launch {
                            try {
                                val st = model.status
                                if (st?.branch?.upstream == null || st.branch.ahead > 0) model.repo.push(st?.branch?.upstream) { }
                                created = ui.app.github.api.createPull(r.first, r.second, title.trim(), head, b, body.trim())
                            } catch (e: GitException) {
                                ui.error(e.message ?: "Could not push the branch")
                            } catch (e: GitHubException) {
                                ui.error(e.message ?: "Could not create the pull request")
                            } finally {
                                creating = false
                                model.refresh()
                            }
                        }
                    },
                    enabled = !creating && title.isNotBlank() && base != null && remote != null && head.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun BaseChip(name: String, selected: Boolean, onClick: () -> Unit) {
    val colors = Pi.colors
    val shape = CircleShape
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .pressable(onClick, shape = shape, pressedScale = SOFT_PRESS, label = "Merge into $name")
            .padding(vertical = 8.dp)
            .background(if (selected) colors.selected else colors.surface1, shape)
            .border(1.dp, if (selected) colors.lineFocus else colors.line, shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.ink1, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(name, style = Pi.type.monoSmall, color = colors.ink1)
    }
}
