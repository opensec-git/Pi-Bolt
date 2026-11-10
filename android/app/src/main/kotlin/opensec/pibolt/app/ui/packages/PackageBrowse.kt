package opensec.pibolt.app.ui.packages

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.ui.components.ButtonRow
import opensec.pibolt.app.ui.components.ErrorState
import opensec.pibolt.app.ui.components.Notice
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.PiSnackHost
import opensec.pibolt.app.ui.components.PillSearchField
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.Segmented
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.SnackKind
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.components.show
import opensec.pibolt.app.ui.session.features2.F2Empty
import opensec.pibolt.app.ui.session.features2.F2Loading
import opensec.pibolt.app.ui.session.features2.F2Row
import opensec.pibolt.app.ui.session.features2.F2Section
import opensec.pibolt.app.ui.session.features2.F2Tag
import opensec.pibolt.app.ui.session.features2.F2Well
import opensec.pibolt.app.ui.session.features2.PiJsonFile
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Space
import java.io.File

/** A project the user can install a package for alone (`pi install -l`); [trusted] as Pi decides it (trust.json). */
@Immutable
data class ProjectTarget(val dir: File, val trusted: Boolean) {
    val name: String get() = dir.name
}

/** Where an install stands, for the detail sheet. */
sealed interface InstallStatus {
    data object Idle : InstallStatus

    data class Running(val label: String) : InstallStatus

    data class Done(val message: String) : InstallStatus

    data class Failed(val log: String) : InstallStatus
}

/** The last install started from a sheet, for [source]. */
@Immutable
data class SourceStatus(val source: String, val status: InstallStatus) {
    companion object {
        /**
         * What the sheet of [source] shows given the last install [current]: its own status; another package's running
         * install (so Install waits, one install at a time); otherwise idle. A result never shows on another package.
         */
        fun of(source: String, current: SourceStatus?): InstallStatus = when {
            current == null -> InstallStatus.Idle
            current.source == source -> current.status
            current.status is InstallStatus.Running -> current.status
            else -> InstallStatus.Idle
        }
    }
}

/** What the detail sheet knows about a source once loaded. */
@Immutable
data class PackageDetail(
    val manifest: PackageManifest?,
    val weeklyDownloads: Long?,
    val files: List<String>?,
    val resources: List<AddedResource>,
    val notes: List<CompatNote>,
    val error: String?,
    /** Read from the private registry (no public links, downloads or file checks). */
    val fromPrivate: Boolean = false,
) {
    val blocked: Boolean get() = notes.any { it.level == CompatNote.Level.Blocks }
}

object PackageFormat {
    /** 950, 12.3k, 4.1M: weekly downloads, short. */
    fun count(n: Long): String = when {
        n < 1_000 -> n.toString()
        n < 10_000 -> String.format(java.util.Locale.ROOT, "%.1fk", n / 1_000.0).replace(".0k", "k")
        n < 1_000_000 -> "${n / 1_000}k"
        else -> String.format(java.util.Locale.ROOT, "%.1fM", n / 1_000_000.0).replace(".0M", "M")
    }

    /** The meta line of a search result: version, weekly downloads, description. */
    fun meta(e: CatalogEntry): String = listOfNotNull(
        e.version.takeIf { it.isNotEmpty() }?.let { "v$it" },
        e.weeklyDownloads?.let { "${count(it)} weekly" },
        e.description,
    ).joinToString(" · ")

    /** The `pi install` arguments for [source], for every project or [local]ly (`-l`, run in the project). */
    fun installArgs(source: String, local: Boolean): List<String> = if (local) listOf("install", "-l", source) else listOf("install", source)

    /**
     * [source] is configured already among [sources]: the same source, or for an npm source without a version the
     * same package at any version. A link for another version of an installed package can still install it.
     */
    fun isInstalled(source: String, sources: Collection<String>): Boolean {
        if (source in sources) return true
        val (name, version) = PackageLinks.npmParts(source) ?: return false
        return version == null && sources.any { PackageLinks.npmParts(it)?.first == name }
    }

    /** The npm names among installed [sources] (versions dropped), to mark search results. */
    fun installedNames(sources: Collection<String>): Set<String> = sources.mapNotNull { PackageLinks.npmParts(it)?.first }.toSet()
}

/**
 * Loads [source]'s details: an npm source from the registry (package.json, last week's downloads, the published file
 * list), a GitHub source's package.json; other git hosts have none. Never throws; [PackageDetail.error] says why.
 */
suspend fun loadDetail(catalog: PackageCatalog, source: String, known: CatalogEntry?, private: Pair<RegistryConfig, PackageCatalog>? = null): PackageDetail {
    val npm = PackageLinks.npmParts(source)
    val gh = PackageLinks.githubParts(source)
    return try {
        when {
            // A package of the private registry: its package.json from there only (the public download counter and
            // file list would learn its name, and have nothing for it).
            npm != null && private != null && private.first.serves(npm.first) -> {
                val manifest = private.second.manifest(npm.first, npm.second)
                val (resources, notes) = withContext(Dispatchers.Default) { CatalogJson.resources(manifest, null) to PackageCompat.check(manifest, null) }
                PackageDetail(manifest, null, null, resources, notes, null, fromPrivate = true)
            }
            npm != null -> coroutineScope {
                val (name, version) = npm
                val downloads = async { known?.weeklyDownloads ?: catalog.weeklyDownloads(name) }
                val manifest = catalog.manifest(name, version)
                val files = manifest.version?.let { catalog.files(name, it) }
                // The manifest and file list come from the package's author: off the main thread.
                val (resources, notes) = withContext(Dispatchers.Default) { CatalogJson.resources(manifest, files) to PackageCompat.check(manifest, files) }
                PackageDetail(manifest, downloads.await(), files, resources, notes, null)
            }
            gh != null -> {
                val manifest = catalog.githubManifest(gh.first, gh.second, gh.third)
                val (resources, notes) = withContext(Dispatchers.Default) { CatalogJson.resources(manifest, null) to PackageCompat.check(manifest, null) }
                PackageDetail(manifest, null, null, resources, notes, null)
            }
            else -> PackageDetail(null, null, null, emptyList(), emptyList(), "Details are not available for this source.")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        PackageDetail(null, null, null, emptyList(), emptyList(), e.message ?: "Could not load the package.")
    }
}

private sealed interface BrowseState {
    data object Loading : BrowseState

    data class Failed(val message: String) : BrowseState

    data class Results(val page: CatalogPage, val loadingMore: Boolean = false, val moreError: String? = null) : BrowseState
}

/**
 * The packages sheet's Browse tab: a search over Pi packages ([PackageCatalog]: npm's `pi-package` keyword, what
 * pi.dev/packages lists), debounced while typing, most downloaded first. A result opens [onOpen] (the detail with
 * Install). [installed] marks packages already configured.
 */
@Composable
fun BrowseTab(catalog: PackageCatalog, private: Pair<RegistryConfig, PackageCatalog>?, installed: Set<String>, onOpen: (CatalogEntry) -> Unit) {
    var query by remember { mutableStateOf("") }
    // With a scoped private registry, Browse can search it instead of npm (when it has npm's search API).
    var usePrivate by remember { mutableStateOf(false) }
    val privateScope = private?.first?.scope
    val searching = if (usePrivate && private != null && privateScope != null) private.second else catalog
    val scopeFilter = privateScope.takeIf { searching.private }
    var state by remember { mutableStateOf<BrowseState>(BrowseState.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var more by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(query, attempt, searching) {
        // Typing restarts this effect: the request goes out once the query has rested. A "Show more" for the old
        // query is cancelled so its page never lands on the new results.
        more?.cancel()
        more = null
        if (state !is BrowseState.Loading) state = BrowseState.Loading
        delay(if (query.isBlank() && attempt == 0) 0 else DEBOUNCE_MS)
        state = try {
            BrowseState.Results(searching.search(query, scope = scopeFilter))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BrowseState.Failed(e.message ?: "Could not search the packages.")
        }
    }
    if (privateScope != null) {
        Segmented(
            options = listOf(false to "npm", true to privateScope),
            selected = usePrivate,
            onSelect = { usePrivate = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.xs),
            height = 48.dp,
        )
    }
    PillSearchField(
        value = query,
        onValueChange = { query = it.take(100) },
        placeholder = if (scopeFilter != null) "Search $scopeFilter packages" else "Search Pi packages",
        modifier = Modifier.padding(horizontal = Space.lg, vertical = 6.dp),
    )
    when (val s = state) {
        BrowseState.Loading -> F2Loading(if (query.isBlank()) "Loading packages" else "Searching")
        is BrowseState.Failed -> Column(Modifier.padding(horizontal = Space.lg, vertical = Space.sm)) {
            ErrorState("Could not load packages", message = s.message, onRetry = { attempt++ })
        }
        is BrowseState.Results -> {
            if (s.page.entries.isEmpty()) {
                F2Empty(if (query.isBlank()) "No packages" else "No packages match \"${query.trim()}\". Try another word.", if (query.isBlank()) Icons.Rounded.CloudOff else Icons.Rounded.SearchOff)
            } else {
                F2Section(
                    when {
                        scopeFilter != null -> "$scopeFilter packages"
                        query.isBlank() -> "Most downloaded"
                        else -> "${s.page.total} found"
                    },
                )
            }
            for (e in s.page.entries) {
                F2Row(title = e.name, meta = PackageFormat.meta(e), icon = Icons.Rounded.Inventory2, onClick = { onOpen(e) }, titleMaxLines = 1) {
                    if (e.name in installed) F2Tag("installed", color = Pi.colors.success, fill = Pi.colors.successSoft)
                }
            }
            s.moreError?.let { Notice(it, kind = SnackKind.Error, modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm)) }
            if (s.page.hasMore && (s.page.entries.isNotEmpty() || scopeFilter != null)) {
                if (s.loadingMore) {
                    F2Loading("Loading more")
                } else {
                    SecondaryButton("Show more", onClick = {
                        state = s.copy(loadingMore = true, moreError = null)
                        val forQuery = query
                        more = scope.launch {
                            val result = try {
                                val next = searching.search(forQuery, from = s.page.nextFrom, scope = scopeFilter)
                                val names = s.page.entries.map { it.name }.toSet()
                                BrowseState.Results(CatalogPage(s.page.entries + next.entries.filter { it.name !in names }, next.total, next.nextFrom))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                s.copy(loadingMore = false, moreError = e.message ?: "Could not load more.")
                            }
                            if (query == forQuery) state = result
                        }
                    }, modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm))
                }
            }
            Text(
                if (scopeFilter != null) "From your private registry ($scopeFilter packages)." else "From the npm registry (packages with the pi-package keyword, as on pi.dev).",
                style = Pi.type.meta,
                color = Pi.colors.ink3,
                modifier = Modifier.padding(horizontal = Space.xl, vertical = Space.sm),
            )
        }
    }
}

private const val DEBOUNCE_MS = 350L

/**
 * A package before it is installed: what it is (source, version, weekly downloads, licence), the compatibility notes
 * ([PackageCompat]), what it adds, its repository, and Install. Nothing installs until Install is tapped. When
 * [project] is given, the user picks all projects or that project alone (only when it is trusted: Pi loads project
 * packages only then). [status] is the install's progress; [onInstall] gets the `pi install` arguments.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackageInfoSheet(
    source: String,
    catalog: PackageCatalog,
    known: CatalogEntry?,
    project: ProjectTarget?,
    installed: Boolean,
    status: InstallStatus,
    onInstall: (args: List<String>, local: Boolean, label: String) -> Unit,
    onDismiss: () -> Unit,
    private: suspend () -> Pair<RegistryConfig, PackageCatalog>? = { null },
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    val context = LocalContext.current
    val colors = Pi.colors
    var detail by remember(source) { mutableStateOf<PackageDetail?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var local by remember { mutableStateOf(false) }
    LaunchedEffect(source, attempt) {
        detail = null
        detail = loadDetail(catalog, source, known, private())
    }
    val title = detail?.manifest?.name ?: known?.name ?: PackageLinks.npmParts(source)?.first ?: source.substringAfterLast('/')
    val description = detail?.manifest?.description ?: known?.description
    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }
    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader(title, subtitle = description)
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
            val d = detail
            F2Well {
                Column {
                    Text(source, style = Pi.type.monoSmall, color = colors.ink1)
                    val meta = listOfNotNull(
                        (d?.manifest?.version ?: known?.version)?.takeIf { it.isNotEmpty() }?.let { "v$it" },
                        (d?.weeklyDownloads ?: known?.weeklyDownloads)?.let { "${PackageFormat.count(it)} weekly downloads" },
                        d?.manifest?.license,
                    )
                    if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = Pi.type.meta, color = colors.ink3)
                }
            }
            when {
                d == null -> F2Loading("Checking the package")
                d.error != null && d.manifest == null -> Column(Modifier.padding(horizontal = Space.lg, vertical = 6.dp)) {
                    ErrorState("No details", message = d.error, onRetry = if (PackageLinks.npmParts(source) != null || PackageLinks.githubParts(source) != null) ({ attempt++ }) else null)
                }
                else -> {
                    F2Section("Compatibility")
                    Column(Modifier.padding(horizontal = Space.lg)) {
                        if (d.notes.isEmpty()) {
                            Notice(
                                if (d.files != null) "No native code, platform limits or install scripts found." else "No platform limits or install scripts in its package.json. Its files were not checked.",
                                kind = SnackKind.Success,
                                icon = Icons.Rounded.VerifiedUser,
                                modifier = Modifier.padding(vertical = Space.xs),
                            )
                        }
                        for (n in d.notes) {
                            val kind = when (n.level) {
                                CompatNote.Level.Blocks -> SnackKind.Error
                                CompatNote.Level.Warns -> SnackKind.Warning
                                CompatNote.Level.Notes -> SnackKind.Info
                            }
                            Notice(n.detail, kind = kind, title = n.title, modifier = Modifier.padding(vertical = Space.xs))
                        }
                    }
                    F2Section("What it adds")
                    if (d.resources.isEmpty()) {
                        Text(
                            "Pi finds its extensions, skills, prompts and themes when it installs.",
                            style = Pi.type.meta,
                            color = colors.ink3,
                            modifier = Modifier.padding(horizontal = Space.xl, vertical = Space.xs),
                        )
                    }
                    for (r in d.resources.take(MAX_RESOURCES)) {
                        F2Row(r.name, r.kind, iconFor(r.kind), onClick = null, titleMaxLines = 1)
                    }
                    if (d.resources.size > MAX_RESOURCES) {
                        Text("and ${d.resources.size - MAX_RESOURCES} more", style = Pi.type.meta, color = colors.ink3, modifier = Modifier.padding(horizontal = Space.xl, vertical = Space.xs))
                    }
                    Text(
                        "Its commands and tools show in Installed once a session loads it.",
                        style = Pi.type.meta,
                        color = colors.ink3,
                        modifier = Modifier.padding(horizontal = Space.xl, vertical = Space.xs),
                    )
                }
            }
            val repo = d?.manifest?.repository ?: known?.repository
            val npm = PackageLinks.npmParts(source)?.first?.takeIf { d?.fromPrivate != true }
            if (repo != null || npm != null) F2Section("Links")
            repo?.let { url -> F2Row("Repository", url, Icons.Rounded.Code, onClick = { open(url) }, titleMaxLines = 1) { OpenGlyph() } }
            npm?.let { name ->
                F2Row("pi.dev", "pi.dev/packages/$name", Icons.Rounded.Public, onClick = { open("https://pi.dev/packages/$name") }, titleMaxLines = 1) { OpenGlyph() }
            }
            if (project != null) {
                F2Section("Install for")
                Segmented(
                    options = listOf(false to "All projects", true to "This project"),
                    selected = local && project.trusted,
                    onSelect = { if (it == false || project.trusted) local = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.xs),
                    height = 48.dp,
                )
                Text(
                    if (project.trusted) {
                        if (local) "Only ${project.name} loads it (its .pi/settings.json)." else "Every project and chat loads it."
                    } else {
                        "${project.name} is not trusted, so it cannot load project packages. Trust it first (/trust)."
                    },
                    style = Pi.type.meta,
                    color = colors.ink3,
                    modifier = Modifier.padding(horizontal = Space.xl, vertical = Space.xs),
                )
            }
            Spacer(Modifier.height(Space.sm))
            when (status) {
                is InstallStatus.Running -> F2Loading(status.label)
                is InstallStatus.Done -> Notice(status.message, kind = SnackKind.Success, modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.xs))
                is InstallStatus.Failed -> F2Well { Text(status.log, style = Pi.type.monoSmall, color = colors.danger) }
                InstallStatus.Idle -> Text(
                    "Packages run code with access to your projects. Install only what you trust.",
                    style = Pi.type.meta,
                    color = colors.ink3,
                    modifier = Modifier.padding(horizontal = Space.xl, vertical = Space.xs),
                )
            }
            Spacer(Modifier.height(Space.md))
            ButtonRow(Modifier.padding(horizontal = Space.lg)) {
                if (status is InstallStatus.Done) {
                    PrimaryButton("Done", onClick = { close(onDismiss) })
                } else {
                    SecondaryButton("Cancel", onClick = { close(onDismiss) }, enabled = status !is InstallStatus.Running)
                    val useLocal = local && project?.trusted == true
                    PrimaryButton(
                        when {
                            d?.blocked == true -> "Not for this phone"
                            installed && !useLocal -> "Installed"
                            status is InstallStatus.Failed -> "Try again"
                            else -> "Install"
                        },
                        onClick = { onInstall(PackageFormat.installArgs(source, useLocal), useLocal, title) },
                        enabled = d != null && !d.blocked && !(installed && !useLocal) && status !is InstallStatus.Running,
                        icon = Icons.Rounded.Download,
                    )
                }
            }
            Spacer(Modifier.height(Space.sm))
        }
    }
}

private const val MAX_RESOURCES = 30

private fun iconFor(kind: String) = when (kind) {
    "extension" -> Icons.Rounded.Extension
    "skill" -> Icons.Rounded.AutoAwesome
    "prompt" -> Icons.Rounded.ChatBubbleOutline
    "theme" -> Icons.Rounded.Palette
    else -> Icons.Rounded.Inventory2
}

/** The trailing mark of a row that opens the browser. */
@Composable
private fun OpenGlyph() {
    Icon(Icons.Rounded.OpenInNew, contentDescription = null, tint = Pi.colors.ink3, modifier = Modifier.size(IconSize.sm))
}

/**
 * A package link opened from outside the app ([pending]: a `pi install` source from [PackageLinks], from a
 * `pibolt://install` link or a shared pi.dev, npm or GitHub URL): the [PackageInfoSheet], and nothing installs until
 * its Install is tapped. The install runs in the app's scope (it finishes when the sheet is closed) for every project;
 * the next session start or reload loads it.
 */
@Composable
fun InstallLinkHost(app: PiBoltApp, pending: String?, onConsumed: () -> Unit) {
    val engine = app.engine
    val catalog = remember { PackageCatalog(app.http) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf<String?>(null) }
    var last by remember { mutableStateOf<SourceStatus?>(null) }
    var installed by remember { mutableStateOf(false) }
    // Bumped for every link, so the same package opened again is checked again.
    var opened by remember { mutableIntStateOf(0) }
    LaunchedEffect(pending) {
        val source = pending ?: return@LaunchedEffect
        // Taken into this host's own state before it is consumed: consuming changes this effect's key.
        installed = false
        if (last?.status !is InstallStatus.Running) last = null
        shown = source
        opened++
        onConsumed()
    }
    LaunchedEffect(shown, opened) {
        val source = shown ?: return@LaunchedEffect
        installed = withContext(Dispatchers.IO) {
            val settings = PiJsonFile.read(File(engine.agentDir, "settings.json")) ?: JsonObject(emptyMap())
            PackageFormat.isInstalled(source, PackageList.fromSettings(settings, PackageScope.User).map { it.source })
        }
    }
    shown?.let { source ->
        PackageInfoSheet(
            source = source,
            catalog = catalog,
            known = null,
            project = null,
            installed = installed,
            status = SourceStatus.of(source, last),
            onInstall = { args, _, title ->
                if (last?.status is InstallStatus.Running) return@PackageInfoSheet
                last = SourceStatus(source, InstallStatus.Running("Installing $title"))
                app.appScope.launch {
                    val result = packageCli(engine, app, args)
                    val next = if (result.ok) InstallStatus.Done("Installed $title. Start or reload a session to use it.") else InstallStatus.Failed(cliFailure(result))
                    last = SourceStatus(source, next)
                    if (shown != source) scope.launch { snackbar.show(if (result.ok) "Installed $title" else "Could not install $title", if (result.ok) SnackKind.Success else SnackKind.Error) }
                }
            },
            onDismiss = { shown = null },
            private = { withContext(Dispatchers.IO) { PrivateRegistries.catalog(PrivateRegistries.get(app), app.http) } },
        )
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.navigationBarsPadding()) { PiSnackHost(snackbar) }
    }
}
