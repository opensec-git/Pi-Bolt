package opensec.pibolt.app.ui.secrets

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.rounded.Key
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.text.input.ImeAction
import opensec.pibolt.app.ui.components.ButtonSize
import opensec.pibolt.app.ui.components.EmptyState
import opensec.pibolt.app.ui.components.FieldHint
import opensec.pibolt.app.ui.components.FieldLabel
import opensec.pibolt.app.ui.components.PiCard
import opensec.pibolt.app.ui.components.ScreenIntro
import opensec.pibolt.app.ui.components.GlassBar
import opensec.pibolt.app.ui.components.GlassIconButton
import opensec.pibolt.app.ui.components.InsetSectionHead
import opensec.pibolt.app.ui.components.PageTitle
import opensec.pibolt.app.ui.theme.Space
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import opensec.pibolt.app.ui.components.SecretField
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.engine.LocalEngine
import opensec.pibolt.app.secrets.Secret
import opensec.pibolt.app.secrets.SecretEnv
import opensec.pibolt.app.secrets.SecretStore
import opensec.pibolt.app.ui.components.ConfirmSheet
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.PiSnackHost
import opensec.pibolt.app.ui.components.PiTextField
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.SnackKind
import opensec.pibolt.app.ui.components.show
import opensec.pibolt.app.ui.theme.Pi
import java.io.File

/**
 * A project's secrets (.env style): names and masked values, added, edited, revealed one at a time or deleted, and
 * on request written as `.env`. Values are sealed with the Keystore ([SecretStore]); the window is FLAG_SECURE while
 * this screen shows (no screenshots, a blank recents thumbnail).
 */
@Composable
fun SecretsScreen(app: PiBoltApp, project: File, onBack: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val store = remember { SecretStore.get(app) }
    var secrets by remember { mutableStateOf<List<Secret>?>(null) }
    var editing by remember { mutableStateOf<Secret?>(null) }
    var adding by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Secret?>(null) }
    var writeEnv by remember { mutableStateOf(false) }
    val activity = LocalActivity.current

    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LaunchedEffect(project) {
        secrets = withContext(Dispatchers.IO) { runCatching { store.read(project) }.getOrElse { emptyList() } }
    }

    fun change(message: String, block: () -> Unit) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    block()
                    store.read(project)
                }
            }
            result.onSuccess {
                secrets = it
                // Idle engines were started with the old values: the next session starts with the new ones.
                (app.engine as? LocalEngine)?.stopIdleEngines("project secrets changed")
                snackbar.show(message, SnackKind.Success)
            }.onFailure { snackbar.show("Could not save the secrets", SnackKind.Error) }
        }
    }

    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize().background(colors.canvas)) {
        val listState = rememberLazyListState()
        val pastTitle by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        Column(Modifier.fillMaxSize()) {
        GlassBar(
            title = "Secrets",
            subtitle = project.name,
            titleVisible = pastTitle,
            leading = { GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack) },
        )
        // Edge to edge the window does not resize for the keyboard: the list stops above it, so the form at its end
        // can scroll into view.
        LazyColumn(Modifier.fillMaxSize().imePadding(), state = listState, contentPadding = PaddingValues(bottom = 24.dp + bottom)) {
            item(key = "head") {
                Column {
                    PageTitle("Secrets", meta = project.name)
                    ScreenIntro("Encrypted on this phone and kept out of the model's messages. Commands in this project can read them.")
                    Text("Applies to new terminals and sessions", style = type.rowMeta, color = colors.ink3, modifier = Modifier.padding(start = Space.page, end = Space.page, bottom = Space.md))
                }
            }
            val list = secrets
            if (list != null && list.isEmpty() && !adding) {
                item(key = "empty") {
                    EmptyState(
                        "No secrets yet",
                        icon = Icons.Rounded.Key,
                        modifier = Modifier.padding(horizontal = Space.page, vertical = Space.xs),
                    )
                }
            }
            if (list != null) {
                if (list.isNotEmpty()) item(key = "label") { InsetSectionHead("Variables", count = list.size, modifier = Modifier.padding(bottom = Space.xs)) }
                itemsIndexed(list, key = { _, it -> it.name }) { i, s ->
                    if (editing?.name == s.name) {
                        SecretForm(initial = s, taken = list.map { it.name }.toSet() - s.name, onCancel = { editing = null }, onSave = { name, value ->
                            editing = null
                            change("Saved $name") {
                                val next = list.map { if (it.name == s.name) Secret(name, value) else it }
                                store.write(project, next)
                            }
                        })
                    } else {
                        SecretRow(
                            s,
                            revealed = revealed == s.name,
                            onReveal = { revealed = if (revealed == s.name) null else s.name },
                            onEdit = {
                                adding = false
                                editing = s
                            },
                            onDelete = { deleting = s },
                            divider = i < list.lastIndex,
                        )
                    }
                }
                item(key = "add") {
                    Column(Modifier.padding(horizontal = Space.page, vertical = Space.lg)) {
                        if (adding) {
                            SecretForm(initial = null, taken = list.map { it.name }.toSet(), onCancel = { adding = false }, onSave = { name, value ->
                                adding = false
                                change("Added $name") { store.put(project, name, value) }
                            })
                        } else {
                            PrimaryButton("Add secret", onClick = {
                                editing = null
                                adding = true
                            }, icon = Icons.Rounded.Add, modifier = Modifier.fillMaxWidth())
                        }
                        if (list.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            SecondaryButton("Write .env file", onClick = { writeEnv = true }, icon = Icons.Rounded.Description, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
        }
        PiSnackHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding())
    }

    deleting?.let { s ->
        ConfirmSheet(
            title = "Delete ${s.name}?",
            message = "New terminals and sessions in this project no longer get it.",
            confirmLabel = "Delete",
            onConfirm = {
                deleting = null
                change("Deleted ${s.name}") { store.remove(project, s.name) }
            },
            onDismiss = { deleting = null },
        )
    }
    if (writeEnv) {
        ConfirmSheet(
            title = "Write .env to the project?",
            message = "Values are saved in plain text in ${project.name}/.env, where anything in the project can read them. " +
                "Pi-Bolt adds .env to .gitignore if the project uses git.",
            confirmLabel = "Write .env",
            onConfirm = {
                writeEnv = false
                scope.launch {
                    val ignored = withContext(Dispatchers.IO) { runCatching { SecretEnv.writeDotEnv(project, store.read(project)) } }
                    ignored.onSuccess { snackbar.show(if (it) "Wrote .env and added it to .gitignore" else "Wrote .env", SnackKind.Success) }
                        .onFailure { snackbar.show("Could not write .env", SnackKind.Error) }
                }
            },
            onDismiss = { writeEnv = false },
            destructive = false,
        )
    }
}

/**
 * One variable: the name (mono ink1) over the value masked as eight dots (monoMeta ink3, fixed length) or shown, then
 * Show / Hide, Edit and Delete; a hairline under it inset to the 20dp gutter, as an [opensec.pibolt.app.ui.components.InsetRow].
 */
@Composable
private fun SecretRow(s: Secret, revealed: Boolean, onReveal: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, divider: Boolean) {
    val colors = Pi.colors
    val type = Pi.type
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = Space.page, end = Space.xs, top = Space.sm, bottom = Space.sm), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "${s.name}, " + if (revealed) "value shown" else "value hidden" }) {
                Text(s.name, style = type.mono, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (revealed) s.value else SecretEnv.mask(s.value), style = type.monoMeta, color = colors.ink3, maxLines = if (revealed) 4 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.xxs))
            }
            IconAction(if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (revealed) "Hide ${s.name}" else "Show ${s.name}", onClick = onReveal)
            IconAction(Icons.Rounded.Edit, "Edit ${s.name}", onClick = onEdit)
            IconAction(Icons.Rounded.DeleteOutline, "Delete ${s.name}", onClick = onDelete)
        }
        if (divider) Box(Modifier.align(Alignment.BottomStart).padding(start = Space.page, end = Space.page).fillMaxWidth().height(1.dp).background(colors.line))
    }
}

@Composable
private fun SecretForm(initial: Secret?, taken: Set<String>, onCancel: () -> Unit, onSave: (String, String) -> Unit) {
    val type = Pi.type
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var value by remember { mutableStateOf(initial?.value.orEmpty()) }
    var show by remember { mutableStateOf(false) }
    val problem = SecretEnv.nameProblem(name) ?: if (name in taken) "$name is already set" else null
    PiCard(Modifier.padding(horizontal = Space.lg, vertical = Space.xs)) {
        FieldLabel("Name")
        PiTextField(
            name,
            { name = it.trim() },
            placeholder = "OPENAI_API_KEY",
            style = type.mono,
            singleLine = true,
            isError = name.isNotEmpty() && problem != null,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
        )
        FieldHint(if (name.isNotEmpty() && problem != null) problem else "Letters, digits and _", isError = name.isNotEmpty() && problem != null)
        FieldLabel("Value")
        SecretField(value, { value = it }, shown = show, onToggleShown = { show = !show }, placeholder = "Value", label = "the value", imeAction = ImeAction.Done, onDone = { if (problem == null) onSave(name, value) })
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            SecondaryButton("Cancel", onClick = onCancel, size = ButtonSize.Small)
            PrimaryButton("Save", onClick = { onSave(name, value) }, enabled = problem == null, size = ButtonSize.Small)
        }
    }
}
