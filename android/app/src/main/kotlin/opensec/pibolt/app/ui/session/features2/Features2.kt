package opensec.pibolt.app.ui.session.features2

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.data.ProjectStore
import opensec.pibolt.app.engine.EngineStatus
import opensec.pibolt.app.engine.LocalEngine
import opensec.pibolt.app.engine.SessionIndex
import opensec.pibolt.app.engine.SessionSummary
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.app.ui.components.Chip
import opensec.pibolt.app.ui.packages.McpSheet
import opensec.pibolt.app.ui.packages.PackagesSheet
import opensec.pibolt.app.ui.projects.LocalSessions
import opensec.pibolt.app.ui.session.ViewerContent
import opensec.pibolt.app.ui.session.features.Builtin
import opensec.pibolt.app.ui.session.tree.TreeRow
import opensec.pibolt.app.ui.session.tree.TreeSheet
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.protocol.ProtocolJson
import opensec.pibolt.protocol.rpc.ModelInfo
import opensec.pibolt.protocol.session.SessionState
import opensec.pibolt.protocol.str
import java.io.File

/** The sheets this module adds to the session screen. */
enum class F2Sheet { None, Tree, Sessions, Packages, Mcp, Skills, Trust, Tuning, Models, Bug }

/** What the session screen keeps for these features: the sheet shown, and the tree being read. */
@Stable
class Features2State {
    var sheet by mutableStateOf(F2Sheet.None)
    var tree by mutableStateOf<JsonObject?>(null)
    var treeError by mutableStateOf<String?>(null)
    var bugText by mutableStateOf("")

    /** `enabledModels` once read or saved; null until then (the picker shows every model). */
    var modelScope by mutableStateOf<List<String>?>(null)
}

@Composable
fun rememberFeatures2(): Features2State = remember { Features2State() }

/** What these features need from the session screen. */
class Features2Host(
    val app: PiBoltApp,
    val controller: SessionController,
    val isLocal: Boolean,
    /** Puts text in the composer (never sends). */
    val insert: (String) -> Unit,
    val message: (String) -> Unit,
    val view: (ViewerContent) -> Unit,
    /** Opens a saved session of this project in this engine. */
    val openSession: (SessionSummary) -> Unit,
)

object Features2 {
    /** The builtins this module handles. Returns false for others. */
    fun handles(builtin: Builtin): Boolean = builtin in HANDLED

    private val HANDLED = setOf(
        Builtin.Tree, Builtin.Packages, Builtin.Mcp, Builtin.Skills, Builtin.Trust, Builtin.Reload, Builtin.Tuning,
        Builtin.ScopedModels, Builtin.Import, Builtin.Bug, Builtin.Resume,
    )

    /**
     * Restarts the engine of an on-device session in the same session file (the terminal's /reload, and more: a fresh
     * process also re-reads project trust, packages, MCP servers and settings). The spare engine is stopped too, so
     * the new process starts clean.
     */
    suspend fun reload(app: PiBoltApp, controller: SessionController): Result<Unit> {
        val project = controller.project ?: return Result.failure(IllegalStateException("Only sessions on this phone can reload"))
        val file = controller.sessionFile
        controller.stop()
        (app.engine as? LocalEngine)?.stopIdleEngines("reload")
        return LocalSessions.open(app.engine, ProjectStore.get(app), project, sessionFile = file).map { }
    }

    /**
     * Copies a session file ([text], JSONL) into [project]'s sessions as `/import` does, keeping its id, with its
     * working directory set to [project] so it opens here. Returns the new file. Blocking.
     */
    fun importSession(index: SessionIndex, project: String, text: String, name: String?): Result<File> = runCatching {
        val firstEnd = text.indexOf('\n').let { if (it < 0) text.length else it }
        val header = ProtocolJson.parseToJsonElement(text.substring(0, firstEnd)) as? JsonObject
        require(header != null && header.str("type") == "session") { "Not a Pi session file (no session header)" }
        val id = header.str("id") ?: error("The session has no id")
        val fixed = JsonObject(header + ("cwd" to JsonPrimitive(project)))
        val dir = index.sessionDir(project).apply { mkdirs() }
        val base = name?.substringAfterLast('/')?.takeIf { it.endsWith(".jsonl") && '_' in it }
            ?: (header.str("timestamp").orEmpty().replace(':', '-').replace('.', '-').ifBlank { "imported" } + "_$id.jsonl")
        var file = File(dir, base)
        var n = 2
        while (file.exists()) file = File(dir, base.removeSuffix(".jsonl") + "-$n.jsonl").also { n++ }
        file.writeText(fixed.toString() + text.substring(firstEnd).let { if (it.endsWith("\n") || it.isEmpty()) it.ifEmpty { "\n" } else it + "\n" })
        file
    }

    fun diagnostics(app: PiBoltApp, controller: SessionController, state: SessionState): String = buildString {
        append("App: Pi-Bolt Android ").append(app.appVersion).append('\n')
        (app.engine.status.value as? EngineStatus.Ready)?.let { append("Engine: ").append(it.engineVersion).append('\n') }
        append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append("), ")
            .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", ").append(Build.SUPPORTED_ABIS.firstOrNull().orEmpty()).append('\n')
        append("Session: ").append(if (controller.isLocal) "on this phone" else "remote control").append('\n')
        state.model?.let { append("Model: ").append(it.provider).append('/').append(it.id).append('\n') }
        state.thinkingLevel?.let { append("Thinking: ").append(it).append('\n') }
        state.host?.version?.takeIf { it.isNotBlank() }?.let { append("Host version: ").append(it).append('\n') }
        controller.engineLog()?.trim()?.takeIf { it.isNotEmpty() }?.let { log ->
            append("\nEngine log (last lines):\n").append(log.lines().takeLast(40).joinToString("\n"))
        }
    }
}

/**
 * Runs one of this module's builtins. Returns false when [builtin] is not one of them. [args] is the text after the
 * command name.
 */
fun Features2State.run(builtin: Builtin, args: String, host: Features2Host, scope: kotlinx.coroutines.CoroutineScope, pickImport: () -> Unit): Boolean {
    when (builtin) {
        Builtin.Tree -> openTree(host, scope)
        Builtin.Resume -> sheet = F2Sheet.Sessions
        Builtin.Packages -> sheet = F2Sheet.Packages
        Builtin.Mcp -> sheet = F2Sheet.Mcp
        Builtin.Skills -> sheet = F2Sheet.Skills
        Builtin.Trust -> sheet = F2Sheet.Trust
        Builtin.Tuning -> sheet = F2Sheet.Tuning
        Builtin.ScopedModels -> {
            host.controller.loadModels()
            sheet = F2Sheet.Models
        }
        Builtin.Bug -> {
            bugText = args
            sheet = F2Sheet.Bug
        }
        Builtin.Import -> pickImport()
        Builtin.Reload -> scope.launch {
            host.message("Reloading…")
            Features2.reload(host.app, host.controller).onFailure { host.message(it.message ?: "Could not reload") }
        }
        else -> return false
    }
    return true
}

private fun Features2State.openTree(host: Features2Host, scope: kotlinx.coroutines.CoroutineScope) {
    tree = null
    treeError = null
    sheet = F2Sheet.Tree
    scope.launch {
        val response = host.controller.request("get_entries", timeoutMs = 60_000)
        val (data, error) = opensec.pibolt.app.ui.session.tree.SessionTree.load(response.success, response.dataObject, response.error)
        tree = data
        treeError = error
    }
}

/** The sheets of this module; place once in the session screen. */
@Composable
fun Features2Sheets(f2: Features2State, host: Features2Host, state: State<SessionState>) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val controller = host.controller
    val app = host.app
    val tmp = remember { File(context.cacheDir, "pibolt-app") }
    val reload: (() -> Unit)? = if (host.isLocal) {
        {
            scope.launch {
                host.message("Reloading…")
                Features2.reload(app, controller).onFailure { host.message(it.message ?: "Could not reload") }
            }
        }
    } else {
        null
    }
    val running by remember { derivedStateOf { state.value.isRunning || state.value.isCompacting } }
    val close = { f2.sheet = F2Sheet.None }
    when (f2.sheet) {
        F2Sheet.None -> Unit
        F2Sheet.Tree -> TreeSheet(
            data = f2.tree,
            error = f2.treeError,
            busy = running,
            onRetry = { f2.openTree(host, scope) },
            onGoto = { row: TreeRow ->
                close()
                scope.launch {
                    AppExtension.run(controller, tmp, "goto", row.id)
                        .onSuccess {
                            controller.resync()
                            // A prompt comes back to the composer, as in the terminal.
                            it.str("editorText")?.let(host.insert)
                            host.message(if (row.kind == opensec.pibolt.app.ui.session.tree.TreeKind.User) "Moved before that prompt. Edit it and send to branch." else "Moved there. Your next message starts a branch.")
                        }
                        .onFailure { host.message(it.message ?: "Could not move there") }
                }
            },
            onLabel = { row, label ->
                scope.launch {
                    AppExtension.run(controller, tmp, "label", (row.id + " " + label).trim())
                        .onSuccess {
                            val response = controller.request("get_entries", timeoutMs = 60_000)
                            if (response.success) f2.tree = response.dataObject
                            host.message(if (label.isEmpty()) "Label removed" else "Labeled \"$label\"")
                        }
                        .onFailure { host.message(it.message ?: "Could not save the label") }
                }
            },
            onDismiss = close,
        )
        F2Sheet.Sessions -> controller.project?.let { project ->
            SessionBrowserSheet(app.engine, project, controller, onOpen = { close(); host.openSession(it) }, onMessage = host.message, onDismiss = close)
        } ?: close()
        F2Sheet.Packages -> PackagesSheet(app.engine, controller.takeIf { host.isLocal }, reload, host.message, close)
        F2Sheet.Mcp -> McpSheet(app.engine, reload, host.message, close)
        F2Sheet.Skills -> SkillsSheet(controller, onInsert = host.insert, onView = host.view, onDismiss = close)
        F2Sheet.Trust -> controller.project?.let { TrustSheet(app.engine.agentDir, it, reload, host.message, close) } ?: close()
        F2Sheet.Tuning -> TuningSheet(app.engine.agentDir, reload, host.message, close)
        F2Sheet.Models -> {
            val models by controller.models.collectAsStateWithLifecycle()
            ScopedModelsSheet(app.engine.agentDir, models, onSaved = { f2.modelScope = it }, onMessage = host.message, onDismiss = close)
        }
        F2Sheet.Bug -> BugSheet(
            f2.bugText,
            remember { Features2.diagnostics(app, controller, state.value) },
            onShare = { report ->
                close()
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Pi-Bolt Android bug report").putExtra(Intent.EXTRA_TEXT, report)
                context.startActivity(Intent.createChooser(send, null))
            },
            onDismiss = close,
        )
    }
}

/** The SAF picker for `/import`: a JSONL session file, copied into this project and opened. */
@Composable
fun rememberImportPicker(host: Features2Host): () -> Unit {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val project = host.controller.project?.path ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use { input ->
                        String(readAtMost(input, MAX_IMPORT_BYTES), Charsets.UTF_8)
                    } ?: error("Could not read that file")
                    Features2.importSession(SessionIndex(host.app.engine.agentDir), project, text, displayName(context, uri)).getOrThrow()
                }
            }
            result
                .onSuccess { file ->
                    host.message("Imported. Opening it…")
                    host.controller.switchSession(file.path)
                }
                .onFailure { host.message(it.message ?: "Could not import that file") }
        }
    }
    return { launcher.launch(arrayOf("application/x-ndjson", "application/json", "application/octet-stream", "text/plain", "*/*")) }
}

private const val MAX_IMPORT_BYTES = 64 * 1024 * 1024

/** Reads the whole stream, failing once it passes [max] bytes (`InputStream.readNBytes` needs API 33; minSdk is 26). */
internal fun readAtMost(input: java.io.InputStream, max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        require(out.size() + n <= max) { "That file is larger than ${max / (1024 * 1024)} MB" }
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()

/**
 * While Pi waits to retry after a provider error: the attempt, the error and "Stop retrying" (`abort_retry`). Shown in
 * place of the plain "Retrying" status line.
 */
@Composable
fun RetryControls(state: State<SessionState>, controller: SessionController) {
    val retry by remember { derivedStateOf { state.value.retry } }
    val r = retry ?: return
    val scope = rememberCoroutineScope()
    val colors = Pi.colors
    val shape = RoundedCornerShape(Radius.md)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(colors.warningSoft, shape)
            .border(1.dp, colors.warning.copy(alpha = 0.4f), shape)
            .padding(start = 12.dp, end = 4.dp)
            .heightIn(min = 48.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Refresh, contentDescription = null, tint = colors.warning, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "Retrying ${r.attempt}/${r.maxAttempts}" + (if (r.delayMs > 0) " in ${(r.delayMs + 999) / 1000} s" else "") + (r.error?.let { ": $it" } ?: ""),
            style = Pi.type.meta,
            color = colors.ink1,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Chip(onClick = {
            scope.launch {
                val response = controller.request("abort_retry")
                if (!response.success) controller.abort()
            }
        }) {
            Text("Stop retrying", style = Pi.type.label, color = colors.warning, modifier = Modifier.padding(end = 4.dp))
        }
    }
}

/** [models] narrowed to `enabledModels` (read once from settings.json), the current one always kept. */
@Composable
fun rememberScopedModels(f2: Features2State, agentDir: File, models: List<ModelInfo>, current: ModelInfo?): List<ModelInfo> {
    LaunchedEffect(Unit) {
        if (f2.modelScope == null) f2.modelScope = withContext(Dispatchers.IO) { ModelScope.read(PiJsonFile.read(File(agentDir, "settings.json")) ?: JsonObject(emptyMap())) }
    }
    val patterns = f2.modelScope.orEmpty()
    return remember(models, patterns, current) {
        if (patterns.isEmpty()) models else models.filter { ModelScope.enabled(patterns, it.provider, it.id) || (it.provider == current?.provider && it.id == current.id) }
    }
}
