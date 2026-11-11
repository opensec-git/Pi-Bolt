package opensec.pibolt.app.ui.projects

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.PiTextField
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.git.github.GitHub
import opensec.pibolt.app.git.github.GitHubState
import opensec.pibolt.app.ui.git.RepoPickerSheet
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import opensec.pibolt.app.ui.components.Segmented
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion

/**
 * New project: an empty folder with a name, or "Clone from git" (the URL names the folder until the user edits the
 * name; the app clones with the engine's git, showing [progress]). Signed in to [github], a repository can be picked
 * from the user's list. [onCreate] gets the name and the URL (null for an empty folder) and returns an error to show,
 * or null when it worked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewProjectSheet(
    existing: List<String>,
    startWithClone: Boolean,
    onCreate: suspend (name: String, cloneUrl: String?) -> String?,
    onDismiss: () -> Unit,
    onImportZip: (() -> Unit)? = null,
    github: GitHub? = null,
    progress: String? = null,
) {
    val colors = Pi.colors
    val type = Pi.type
    val haptics = Pi.haptics
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    var clone by rememberSaveable { mutableStateOf(startWithClone) }
    var url by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var nameEdited by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var submit by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    var picking by remember { mutableStateOf(false) }
    val signedIn = github?.state?.collectAsStateWithLifecycle()?.value is GitHubState.SignedIn

    val urlOk = !clone || ProjectRules.isGitUrl(url)
    val nameError = ProjectRules.nameError(name, existing)
    val showNameError = name.isNotEmpty() && nameError != null
    val showUrlError = clone && url.isNotBlank() && !urlOk
    val ready = !busy && nameError == null && urlOk && (!clone || url.isNotBlank())

    LaunchedEffect(submit) {
        if (submit == 0) return@LaunchedEffect
        busy = true
        failure = onCreate(name.trim(), if (clone) url.trim() else null)
        busy = false
        if (failure == null) {
            haptics.confirm()
            close(onDismiss)
        } else {
            haptics.reject()
        }
    }
    LaunchedEffect(clone) { runCatching { focus.requestFocus() } }

    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader("New project")
        Segmented(
            options = listOf(false to "Empty folder", true to "Clone from git"),
            selected = clone,
            onSelect = {
                clone = it
                failure = null
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            height = 48.dp,
        )
        AnimatedVisibility(
            visible = clone,
            enter = expandVertically(PiMotion.enter(PiMotion.STANDARD)) + fadeIn(PiMotion.enter(PiMotion.QUICK)),
            exit = shrinkVertically(PiMotion.leave(PiMotion.STANDARD)) + fadeOut(PiMotion.leave(PiMotion.QUICK)),
        ) {
            Column {
                Label("Repository URL")
                PiTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        failure = null
                        if (!nameEdited) name = ProjectRules.repoName(it)
                    },
                    placeholder = "https://github.com/user/repo.git",
                    singleLine = true,
                    style = type.monoSmall,
                    isError = showUrlError,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).then(if (clone) Modifier.focusRequester(focus) else Modifier),
                )
                if (signedIn) {
                    TextAction("Choose from GitHub", onClick = { picking = true }, color = colors.accent, modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp))
                }
                Hint(
                    when {
                        showUrlError -> "Use an https://, ssh:// or git@host:path URL"
                        busy && progress != null -> progress
                        signedIn -> "Uses your GitHub account"
                        else -> "Private repositories need GitHub sign-in in Settings"
                    },
                    showUrlError,
                )
            }
        }
        Label("Name")
        PiTextField(
            value = name,
            onValueChange = {
                name = it
                nameEdited = true
                failure = null
            },
            placeholder = "my-app",
            singleLine = true,
            isError = showNameError,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).then(if (!clone) Modifier.focusRequester(focus) else Modifier),
        )
        Hint(
            when {
                failure != null -> failure!!
                showNameError -> nameError!!
                else -> "projects/" + name.trim().ifEmpty { "…" }
            },
            error = failure != null || showNameError,
            mono = failure == null && !showNameError,
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("Cancel", onClick = { close(onDismiss) }, modifier = Modifier.weight(1f))
            PrimaryButton(
                when {
                    busy && clone -> "Cloning…"
                    busy -> "Creating…"
                    clone -> "Clone"
                    else -> "Create"
                },
                onClick = { if (ready) submit++ },
                enabled = ready,
                modifier = Modifier.weight(1f),
            )
        }
        onImportZip?.let { import ->
            TextAction("Import a zip as a project…", onClick = import, color = colors.accent, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
        }
        Spacer(Modifier.height(8.dp))
    }
    if (picking && github != null) {
        RepoPickerSheet(github, onPick = { repo ->
            picking = false
            url = repo.cloneUrl
            failure = null
            if (!nameEdited) name = ProjectRules.repoName(repo.cloneUrl)
        }, onDismiss = { picking = false })
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = Pi.type.metaStrong, color = Pi.colors.ink3, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 6.dp))
}

@Composable
private fun Hint(text: String, error: Boolean, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = if (mono) Pi.type.monoMeta else Pi.type.meta,
            color = if (error) Pi.colors.danger else Pi.colors.ink3,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
