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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.data.AppSettings
import opensec.pibolt.app.engine.PageCatalog
import opensec.pibolt.app.engine.PageEngine
import opensec.pibolt.app.ui.session.views.iconFor
import opensec.pibolt.protocol.session.PageInfo
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Refresh
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
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import opensec.pibolt.app.engine.CliResult
import opensec.pibolt.app.engine.EngineApi
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.app.ui.components.ConfirmSheet
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.PiTextField
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.Segmented
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
import opensec.pibolt.app.ui.session.features2.ProjectTrust
import opensec.pibolt.app.ui.theme.Pi
import java.io.File

/** What the packages screen shows: configured packages, the switched-off built-ins and whether the project is trusted. */
private data class PackagesView(val packages: List<PiPackage>, val disabledBuiltins: Set<String>, val listError: String?, val projectTrusted: Boolean)

private enum class PackagesTab { Installed, Browse }

/**
 * Pi packages on this phone (the terminal's `pi list`, `pi install`, `pi remove` and `pi config`): the bundled todo and
 * subagents, installed npm and git packages, Pi's built-in extensions. A package can be switched off (its resources
 * filtered to none) or removed; a new one is installed from npm or git by name, or found in Browse ([BrowseTab],
 * searched over [http]) and installed from its detail, for every project or the session's project alone. Changes load
 * when the session reloads ([onReload], null when there is no session on this phone).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackagesSheet(engine: EngineApi, http: OkHttpClient, controller: SessionController?, onReload: (() -> Unit)?, onMessage: (String) -> Unit, onDismiss: () -> Unit) {
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
    var tab by remember { mutableStateOf(PackagesTab.Installed) }
    val catalog = remember(http) { PackageCatalog(http) }
    var browsing by remember { mutableStateOf<CatalogEntry?>(null) }
    var browseStatus by remember { mutableStateOf<SourceStatus?>(null) }
    val registryStore = remember { PrivateRegistries.get(context) }
    var registryVersion by remember { mutableIntStateOf(0) }
    var privateCatalog by remember { mutableStateOf<Pair<RegistryConfig, PackageCatalog>?>(null) }
    LaunchedEffect(registryVersion) {
        privateCatalog = withContext(Dispatchers.IO) { PrivateRegistries.catalog(registryStore, http) }
    }

    LaunchedEffect(reload) {
        view = withContext(Dispatchers.IO) { load(engine, settingsFile, project) }
    }

    fun runCli(label: String, args: List<String>, done: String, onResult: (InstallStatus) -> Unit = {}) {
        if (working != null) return
        working = label
        log = null
        onResult(InstallStatus.Running(label))
        scope.launch {
            val result = packageCli(engine, context, args, cwd = project)
            working = null
            if (result.ok) {
                changed = true
                onMessage(done)
                source = ""
                onResult(InstallStatus.Done(done))
            } else {
                val text = cliFailure(result)
                log = text
                onResult(InstallStatus.Failed(text))
            }
            reload++
        }
    }

    fun edit(change: (JsonObject) -> JsonObject?) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { PiJsonFile.update(settingsFile) { change(it) ?: it } }
            if (ok) {
                changed = true
                packagesChanged(engine, context)
            } else {
                onMessage("Could not change settings.json")
            }
            reload++
        }
    }

    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader("Packages", subtitle = "Extensions, skills and prompts that Pi loads.")
        Segmented(
            options = listOf(PackagesTab.Installed to "Installed", PackagesTab.Browse to "Browse"),
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            height = 48.dp,
        )
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
            if (tab == PackagesTab.Browse) {
                working?.let { F2Loading(it) }
                ReloadWell(changed, onReload, close)
                val users = view?.packages.orEmpty().filter { it.scope == PackageScope.User }
                BrowseTab(catalog, privateCatalog, installed = PackageFormat.installedNames(users.map { it.source }) + users.mapNotNull { it.name }, onOpen = {
                    // A finished result is for that visit only; a running install stays visible.
                    if (browseStatus?.status !is InstallStatus.Running) browseStatus = null
                    browsing = it
                })
                return@Column
            }
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
            ReloadWell(changed, onReload, close)
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
                    RegistrySection(registryStore, onMessage = onMessage, onChanged = { registryVersion++ })
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    browsing?.let { entry ->
        val source = "npm:${entry.name}"
        val v = view
        PackageInfoSheet(
            source = source,
            catalog = catalog,
            known = entry,
            project = project?.let { ProjectTarget(it, v?.projectTrusted == true) },
            installed = PackageFormat.isInstalled(source, v?.packages.orEmpty().filter { it.scope == PackageScope.User }.map { it.source }),
            status = SourceStatus.of(source, browseStatus),
            onInstall = { args, local, title ->
                runCli("Installing $title", args, if (local) "Installed $title for ${project?.name}" else "Installed $title", onResult = { browseStatus = SourceStatus(source, it) })
            },
            onDismiss = { browsing = null },
            private = { privateCatalog },
        )
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
    val trusted = project?.let { dir ->
        val trust = PiJsonFile.read(File(engine.agentDir, "trust.json")) ?: JsonObject(emptyMap())
        val path = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        ProjectTrust.decision(trust, path)?.trusted ?: ((settings["defaultProjectTrust"] as? JsonPrimitive)?.content == "always")
    } == true
    return PackagesView(packages, PackageList.disabledBuiltins(settings), if (cli.ok) null else cli.stderr.trim().takeIf { it.isNotEmpty() }, trusted)
}

/**
 * A package's pages and Home tiles (docs/mobile-ui.md#package-pages-and-home-tiles): a page opens without a session
 * (on the page engine); a tile is shown on Home only once turned on here (off by default).
 */
@Composable
private fun PackagePagesSection(pkg: PiPackage, dir: String?, onOpen: (PageInfo) -> Unit) {
    val app = LocalContext.current.applicationContext as PiBoltApp
    val all by app.pages.pages.collectAsStateWithLifecycle()
    val status by app.pages.status.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { app.pages.refreshPages() }
    val list = all
    if (list == null) {
        if (status is PageEngine.Status.LowMemory) return
        F2Section("Pages")
        when (val s = status) {
            // The engine could not start or read them: say why, and let a tap try again.
            is PageEngine.Status.Failed -> F2Row(
                "Could not read the package's pages",
                "${s.message}. Tap to try again",
                Icons.Rounded.Refresh,
                onClick = { app.pages.refreshPages(force = true) },
                iconTint = Pi.colors.danger,
            )
            else -> F2Loading("Reading the package's pages")
        }
        return
    }
    val mine = PageCatalog.ofPackage(list, pkg.source, dir)
    if (mine.isEmpty()) return
    F2Section("Pages")
    for (page in mine.filterNot { it.isTile }) {
        F2Row(page.title, page.description, iconFor(page.icon) ?: Icons.Rounded.Extension, onClick = { onOpen(page) })
    }
    // Only tiles that still exist count toward Home's limit; turning one on or off drops the others' stale keys.
    val shown = PageCatalog.shownTiles(list, settings.homeTiles).size
    for (tile in mine.filter { it.isTile }) {
        val on = tile.key in settings.homeTiles
        F2Toggle("Show ${tile.title} on Home", tile.description ?: "A live tile on Home", checked = on, enabled = on || shown < AppSettings.MAX_HOME_TILES) { checked ->
            app.settings.update { s -> s.copy(homeTiles = PageCatalog.toggleTile(list, s.homeTiles, tile.key, checked)) }
        }
    }
}

/** A failed `pi` command's output for the user: the end of stderr (else stdout), or its exit code. */
internal fun cliFailure(result: CliResult): String =
    (result.stderr.ifBlank { result.stdout }).trim().ifBlank { "Failed (exit code ${result.code})" }.takeLast(2000)

/** "Reload the session to apply changes" with Reload, once something changed and a session can reload. */
@Composable
private fun ReloadWell(changed: Boolean, onReload: (() -> Unit)?, close: (() -> Unit) -> Unit) {
    if (!changed || onReload == null) return
    F2Well {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Reload the session to apply changes", style = Pi.type.body, color = Pi.colors.ink1, modifier = Modifier.weight(1f))
            SecondaryButton("Reload", onClick = { close(onReload) })
        }
    }
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
    var openPage by remember { mutableStateOf<PageInfo?>(null) }
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
            if (!pkg.disabled) PackagePagesSection(pkg, dir, onOpen = { openPage = it })
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
    openPage?.let { page ->
        val app = LocalContext.current.applicationContext as PiBoltApp
        PackagePageSheet(app.pages, page.key, page.title, onClose = { openPage = null })
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
