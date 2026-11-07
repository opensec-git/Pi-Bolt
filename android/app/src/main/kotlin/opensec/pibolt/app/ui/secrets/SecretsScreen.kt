package opensec.pibolt.app.ui.secrets

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import opensec.pibolt.app.ui.components.SectionLabel
import opensec.pibolt.app.ui.components.SnackKind
import opensec.pibolt.app.ui.components.StatusBarScrim
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.ui.components.show
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius
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
                (app.engine as? LocalEngine)?.trimMemory("project secrets changed")
                snackbar.show(message, SnackKind.Success)
            }.onFailure { snackbar.show("Could not save the secrets", SnackKind.Error) }
        }
    }

    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.fillMaxSize().background(colors.canvas)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp + bottom)) {
            item(key = "top") {
                Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack, tint = colors.ink1)
                }
            }
            item(key = "head") {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp)) {
                    Text("Secrets", style = type.display, color = colors.ink1)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Environment variables for ${project.name}'s terminals and sessions, such as API keys. Encrypted on this phone and " +
                            "never put in the model's messages; commands that Pi or you run can read them, like any variable. " +
                            "Changes apply to new terminals and sessions.",
                        style = type.body,
                        color = colors.ink2,
                    )
                }
            }
            val list = secrets
            if (list != null && list.isEmpty() && !adding) {
                item(key = "empty") {
                    Text("No secrets yet.", style = type.body, color = colors.ink3, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
            }
            if (list != null) {
                if (list.isNotEmpty()) item(key = "label") { SectionLabel("Variables", Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                items(list, key = { it.name }) { s ->
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
                        )
                    }
                }
                item(key = "add") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
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
        StatusBarScrim()
        PiSnackHost(snackbar, Modifier.align(Alignment.BottomCenter))
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
            message = "Every value lands in plain text in ${project.name}/.env, where tools, Pi and anything in the project can read it, " +
                "and git would commit it. Pi-Bolt adds .env to .gitignore when the project uses git.",
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

@Composable
private fun SecretRow(s: Secret, revealed: Boolean, onReveal: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "${s.name}, " + if (revealed) "value shown" else "value hidden" }) {
            Text(s.name, style = type.mono, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (revealed) s.value else SecretEnv.mask(s.value), style = type.monoMeta, color = colors.ink3, maxLines = if (revealed) 4 else 1, overflow = TextOverflow.Ellipsis)
        }
        IconAction(if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (revealed) "Hide ${s.name}" else "Show ${s.name}", onClick = onReveal)
        IconAction(Icons.Rounded.Edit, "Edit ${s.name}", onClick = onEdit)
        IconAction(Icons.Rounded.DeleteOutline, "Delete ${s.name}", onClick = onDelete, tint = colors.danger)
    }
}

@Composable
private fun SecretForm(initial: Secret?, taken: Set<String>, onCancel: () -> Unit, onSave: (String, String) -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var value by remember { mutableStateOf(initial?.value.orEmpty()) }
    var show by remember { mutableStateOf(false) }
    val problem = SecretEnv.nameProblem(name) ?: if (name in taken) "$name is already set" else null
    val shape = RoundedCornerShape(Radius.md)
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().background(colors.surface1, shape).border(1.dp, colors.line, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PiTextField(
            name,
            { name = it.trim() },
            placeholder = "NAME",
            style = type.mono,
            singleLine = true,
            isError = name.isNotEmpty() && problem != null,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
        )
        if (name.isNotEmpty() && problem != null) Text(problem, style = type.meta, color = colors.danger)
        Row(verticalAlignment = Alignment.CenterVertically) {
            PiTextField(
                value,
                { value = it },
                placeholder = "Value",
                style = type.mono,
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.weight(1f),
            )
            IconAction(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (show) "Hide the value" else "Show the value", onClick = { show = !show })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            TextAction("Cancel", onClick = onCancel)
            TextAction("Save", onClick = { onSave(name, value) }, enabled = problem == null, color = colors.accent)
        }
    }
}
