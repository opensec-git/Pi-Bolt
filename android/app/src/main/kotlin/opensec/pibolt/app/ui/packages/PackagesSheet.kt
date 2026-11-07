package opensec.pibolt.app.ui.packages

import androidx.compose.material.icons.rounded.PhoneAndroid
import opensec.pibolt.app.ui.components.ErrorState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import opensec.pibolt.app.engine.EngineApi
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.app.ui.components.ConfirmSheet
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.PiTextField
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.session.features2.AppExtension
import opensec.pibolt.app.ui.session.features2.F2Empty
import opensec.pibolt.app.ui.session.features2.F2Loading
import opensec.pibolt.app.ui.session.features2.F2Row
import opensec.pibolt.app.ui.session.features2.F2Section
import opensec.pibolt.app.ui.session.features2.F2Tag
import opensec.pibolt.app.ui.session.features2.F2Toggle
import opensec.pibolt.app.ui.session.features2.F2Well
import opensec.pibolt.app.ui.session.features2.PiJsonFile
import opensec.pibolt.app.ui.theme.Pi
import java.io.File

/** What the packages screen shows: configured packages and the switched-off built-ins. */
private data class PackagesView(val packages: List<PiPackage>, val disabledBuiltins: Set<String>, val listError: String?)

/**
 * Pi packages on this phone (the terminal's `pi list`, `pi install`, `pi remove` and `pi config`): the bundled todo and
 * subagents, installed npm and git packages, Pi's built-in extensions. A package can be switched off (its resources
 * filtered to none) or removed; a new one is installed from npm or git by name. Changes load when the session reloads
 * ([onReload], null when there is no session on this phone).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackagesSheet(engine: EngineApi, controller: SessionController?, onReload: (() -> Unit)?, onMessage: (String) -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settingsFile = remember { File(engine.agentDir, "settings.json") }
    val project = controller?.project
    var reload by remember { mutableIntStateOf(0) }
    var view by remember { mutableStateOf<PackagesView?>(null) }
    var source by remember { mutableStateOf("") }
    var working by remember { mutableStateOf<String?>(null) }
    var log by remember { mutableStateOf<String?>(null) }
    var changed by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<PiPackage?>(null) }

    LaunchedEffect(reload) {
        view = withContext(Dispatchers.IO) { load(engine, settingsFile, project) }
    }

    fun runCli(label: String, args: List<String>, done: String) {
        if (working != null) return
        working = label
        log = null
        scope.launch {
            val result = engine.cli(args, cwd = project, timeoutMs = 600_000)
            working = null
            if (result.ok) {
                changed = true
                onMessage(done)
                source = ""
            } else {
                log = (result.stderr.ifBlank { result.stdout }).trim().ifBlank { "Failed (exit code ${result.code})" }.takeLast(2000)
            }
            reload++
        }
    }

    fun edit(change: (JsonObject) -> JsonObject?) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { PiJsonFile.update(settingsFile) { change(it) ?: it } }
            if (ok) changed = true else onMessage("Could not change settings.json")
            reload++
        }
    }

    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader("Packages", subtitle = "Extensions, skills and prompts that Pi loads.")
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                PiTextField(
                    value = source,
                    onValueChange = { source = it },
                    placeholder = "npm name or git URL",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                val normalized = PackageList.normalizeSource(source)
                PrimaryButton(
                    "Install",
                    onClick = { normalized?.let { runCli("Installing $it", listOf("install", it), "Installed $it") } },
                    enabled = normalized != null && working == null,
                    icon = Icons.Rounded.Download,
                )
            }
            Text(
                "Packages run code with access to your projects. Install only what you trust.",
                style = Pi.type.meta,
                color = Pi.colors.ink3,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
            working?.let { F2Loading(it) }
            log?.let { text ->
                F2Well { Text(text, style = Pi.type.monoSmall, color = Pi.colors.danger) }
            }
            if (changed && onReload != null) {
                F2Well {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Reload the session to apply changes", style = Pi.type.body, color = Pi.colors.ink1, modifier = Modifier.weight(1f))
                        SecondaryButton("Reload", onClick = { close(onReload) })
                    }
                }
            }
            val v = view
            when {
                v == null -> F2Loading("Loading packages")
                else -> {
                    F2Section("Installed")
                    if (v.listError != null) {
                        ErrorState("Could not list the packages", message = v.listError, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    } else if (v.packages.isEmpty()) {
                        F2Empty("No packages yet", Icons.Rounded.Extension)
                    }
                    for (pkg in v.packages) {
                        val meta = buildList {
                            add(pkg.kind)
                            pkg.version?.let { add("v$it") }
                            if (pkg.scope == PackageScope.Project) add("this project")
                            if (pkg.filtered && !pkg.disabled) add("filtered")
                            pkg.description?.let { add(it) }
                        }.joinToString(" · ")
                        F2Row(
                            title = pkg.title,
                            meta = meta,
                            icon = Icons.Rounded.Inventory2,
                            iconTint = if (pkg.disabled) Pi.colors.ink4 else Pi.colors.accent,
                            onClick = { detail = pkg },
                        ) {
                            F2Tag(if (pkg.disabled) "off" else "on", color = if (pkg.disabled) Pi.colors.ink3 else Pi.colors.success, fill = if (pkg.disabled) Pi.colors.surface3 else Pi.colors.successSoft)
                        }
                    }
                    F2Section("Built in")
                    for (b in BuiltinExtension.entries) {
                        F2Toggle(b.label, b.description, checked = b.id !in v.disabledBuiltins, enabled = working == null) { on ->
                            edit { PackageList.setBuiltin(it, b.id, on) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    detail?.let { pkg ->
        PackageDetailSheet(
            pkg = pkg,
            controller = controller,
            tmpDir = File(context.cacheDir, "pibolt-app"),
            onEnabled = { on ->
                detail = null
                edit { PackageList.setEnabled(it, pkg.source, on) }
            },
            onRemove = {
                detail = null
                runCli("Removing ${pkg.title}", listOf("remove", pkg.source), "Removed ${pkg.title}")
            },
            onDismiss = { detail = null },
        )
    }
}

private suspend fun load(engine: EngineApi, settingsFile: File, project: File?): PackagesView {
    val settings = PiJsonFile.read(settingsFile) ?: JsonObject(emptyMap())
    val fromSettings = PackageList.fromSettings(settings, PackageScope.User) +
        (project?.let { PiJsonFile.read(File(it, ".pi/settings.json")) }?.let { PackageList.fromSettings(it, PackageScope.Project) }.orEmpty())
    // `pi list` knows where npm and git packages are installed; settings.json says which are switched off.
    val cli = engine.cli(listOf("list"), cwd = project, timeoutMs = 60_000)
    val listed = if (cli.ok) PackageList.parseCli(cli.stdout) else emptyList()
    val merged = fromSettings.map { s ->
        val l = listed.firstOrNull { it.source == s.source && it.scope == s.scope }
        s.copy(installedPath = l?.installedPath ?: s.installedPath)
    } + listed.filter { l -> fromSettings.none { it.source == l.source && it.scope == l.scope } }
    val packages = merged.map { p -> PackageList.withManifest(p.copy(bundled = "/share/pi-extensions/" in p.source)) }
    return PackagesView(packages, PackageList.disabledBuiltins(settings), if (cli.ok) null else cli.stderr.trim().takeIf { it.isNotEmpty() })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PackageDetailSheet(
    pkg: PiPackage,
    controller: SessionController?,
    tmpDir: File,
    onEnabled: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    var confirmRemove by remember { mutableStateOf(false) }
    var provided by remember { mutableStateOf<List<Provided>?>(null) }
    val dir = pkg.installedPath ?: pkg.source.takeIf { it.startsWith("/") }
    LaunchedEffect(pkg) {
        if (controller == null || controller.stopped) {
            provided = emptyList()
            return@LaunchedEffect
        }
        val commands = controller.request("get_commands").dataObject
        val tools = AppExtension.run(controller, tmpDir, "tools").getOrNull()
        provided = PackageResources.of(dir, commands, tools)
    }
    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader(pkg.title, subtitle = pkg.description)
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            F2Well {
                Column {
                    Text(pkg.source, style = Pi.type.monoSmall, color = Pi.colors.ink1)
                    if (dir != null && dir != pkg.source) Text(dir, style = Pi.type.monoMeta, color = Pi.colors.ink3)
                    Text(listOfNotNull(pkg.kind, pkg.version?.let { "v$it" }, if (pkg.scope == PackageScope.Project) "this project" else "all projects").joinToString(" · "), style = Pi.type.meta, color = Pi.colors.ink3)
                }
            }
            F2Toggle("Load this package", if (pkg.disabled) "Not loaded" else "Loads in every session", checked = !pkg.disabled) { onEnabled(it) }
            F2Section("Commands and tools")
            val list = provided
            when {
                list == null -> F2Loading("Loading…")
                controller == null -> F2Empty("Open a session on this phone to see them", Icons.Rounded.PhoneAndroid)
                list.isEmpty() -> F2Empty(if (pkg.disabled) "Switched off" else "Nothing loaded. Reload the session after installing.", Icons.Rounded.Extension)
                else -> for (p in list) {
                    F2Row(p.name, p.description.ifBlank { null }, Icons.Rounded.Extension, onClick = null, titleMaxLines = 1) {
                        F2Tag(p.kind)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (!pkg.bundled) {
                SecondaryButton("Remove", onClick = { confirmRemove = true }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            } else {
                Text("Built in. Switch it off instead.", style = Pi.type.meta, color = Pi.colors.ink3, modifier = Modifier.padding(horizontal = 20.dp))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (confirmRemove) {
        ConfirmSheet(
            title = "Remove ${pkg.title}?",
            message = "It is uninstalled from this phone.",
            confirmLabel = "Remove",
            onConfirm = {
                confirmRemove = false
                close(onRemove)
            },
            onDismiss = { confirmRemove = false },
        )
    }
}
