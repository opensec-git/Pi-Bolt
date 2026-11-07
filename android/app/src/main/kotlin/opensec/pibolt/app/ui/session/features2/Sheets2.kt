package opensec.pibolt.app.ui.session.features2

import androidx.compose.material.icons.rounded.Description
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.app.ui.components.Chip
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.PiTextField
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.Segmented
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.session.ViewerContent
import opensec.pibolt.app.ui.providers.ProviderCatalog
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.protocol.obj
import opensec.pibolt.protocol.rpc.ModelInfo
import opensec.pibolt.protocol.str
import java.io.File

/** A skill or prompt template (`get_commands`), with the file it comes from. */
data class Resource(val name: String, val description: String, val source: String, val path: String?, val scope: String?)

object Resources {
    fun from(data: JsonObject?): List<Resource> = (data?.get("commands") as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { item ->
        val o = item as? JsonObject ?: return@mapNotNull null
        val source = o.str("source") ?: return@mapNotNull null
        if (source != "skill" && source != "prompt") return@mapNotNull null
        val info = o.obj("sourceInfo")
        Resource(o.str("name") ?: return@mapNotNull null, o.str("description").orEmpty(), source, info?.str("path"), info?.str("scope"))
    }.sortedWith(compareBy({ it.source }, { it.name.lowercase() }))
}

/**
 * Skills and prompt templates this session has (the `/` commands from files): read one, or put `/name ` in the
 * composer with [onInsert]. [onView] opens a file in the full-screen viewer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsSheet(controller: SessionController, onInsert: (String) -> Unit, onView: (ViewerContent) -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Resource>?>(null) }
    var kind by remember { mutableStateOf("skill") }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(controller) { items = Resources.from(controller.request("get_commands").dataObject) }
    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader("Skills and prompts")
        Segmented(
            options = listOf("skill" to "Skills", "prompt" to "Prompt templates"),
            selected = kind,
            onSelect = { kind = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            height = 40.dp,
        )
        PiTextField(
            query,
            { query = it },
            placeholder = "Search",
            singleLine = true,
            leading = { androidx.compose.material3.Icon(Icons.Rounded.Search, contentDescription = null, tint = Pi.colors.ink3) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        val list = items?.filter { it.source == kind && (query.isBlank() || query.lowercase() in (it.name + " " + it.description).lowercase()) }
        when {
            list == null -> F2Loading("Loading skills")
            list.isEmpty() -> F2Empty(
                if (kind == "skill") "No skills. Add them in ~/.pi/agent/skills or install a package." else "No prompt templates. Add .md files in ~/.pi/agent/prompts.",
                if (kind == "skill") Icons.Rounded.AutoAwesome else Icons.Rounded.Description,
            )
            else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                items(list, key = { it.source + ":" + it.name }) { r ->
                    F2Row(
                        title = "/" + r.name,
                        meta = listOfNotNull(r.description.ifBlank { null }, r.scope).joinToString(" · "),
                        icon = if (r.source == "skill") Icons.Rounded.AutoAwesome else Icons.AutoMirrored.Rounded.Notes,
                        onClick = {
                            val path = r.path ?: return@F2Row
                            scope.launch {
                                val text = withContext(Dispatchers.IO) { runCatching { File(path).readText().take(400_000) }.getOrNull() }
                                if (text != null) onView(ViewerContent("/" + r.name, path, text))
                            }
                        },
                        titleMaxLines = 1,
                    ) {
                        Chip(onClick = { close { onInsert("/" + r.name + " ") } }) {
                            Text("Use", style = Pi.type.label, color = Pi.colors.accent, modifier = Modifier.padding(end = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Project trust (the terminal's `/trust`, docs/security.md): this project's saved decision and the global default. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrustSheet(agentDir: File, project: File, onReload: (() -> Unit)?, onMessage: (String) -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    val scope = rememberCoroutineScope()
    val trustFile = remember { File(agentDir, "trust.json") }
    val settingsFile = remember { File(agentDir, "settings.json") }
    val dir = remember(project) { runCatching { project.canonicalPath }.getOrDefault(project.absolutePath) }
    var reload by remember { mutableStateOf(0) }
    var view by remember { mutableStateOf<Triple<ProjectTrust.Decision?, List<String>, String>?>(null) }
    var changed by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        view = withContext(Dispatchers.IO) {
            val trust = PiJsonFile.read(trustFile) ?: JsonObject(emptyMap())
            val settings = PiJsonFile.read(settingsFile) ?: JsonObject(emptyMap())
            Triple(ProjectTrust.decision(trust, dir), ProjectTrust.gatedResources(project), (settings["defaultProjectTrust"] as? JsonPrimitive)?.content ?: "ask")
        }
    }
    fun save(file: File, change: (JsonObject) -> JsonObject) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { PiJsonFile.update(file, change) }
            if (ok) changed = true else onMessage("Could not save the decision")
            reload++
        }
    }
    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader("Project trust", subtitle = "Trusted projects load their .pi settings, extensions, skills, prompts and MCP servers. Extensions run code.")
        val v = view
        if (v == null) {
            F2Loading("Loading…")
            return@PiSheet
        }
        val (decision, gated, default) = v
        val own = decision?.takeIf { it.path == dir }
        val trusted = decision?.trusted ?: (default == "always")
        F2Well {
            Column {
                Text(if (trusted) "Trusted" else "Not trusted", style = Pi.type.bodyStrong, color = if (trusted) Pi.colors.success else Pi.colors.warning)
                Text(
                    when {
                        own != null -> "Saved for this project"
                        decision != null -> "Saved for a parent folder: ${decision.path}"
                        else -> "Not saved · default: ${if (default == "always") "trust" else "don't trust"}"
                    },
                    style = Pi.type.meta,
                    color = Pi.colors.ink3,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (gated.isEmpty()) "Nothing here needs trust" else "Needs trust: " + gated.joinToString(", "),
                    style = Pi.type.meta,
                    color = Pi.colors.ink2,
                )
            }
        }
        Segmented(
            options = listOf<Pair<Boolean?, String>>(true to "Trust", false to "Don't trust", null to "Not saved"),
            selected = own?.trusted,
            onSelect = { choice -> save(trustFile) { ProjectTrust.set(it, dir, choice) } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            height = 44.dp,
        )
        F2Toggle(
            "Trust every project by default",
            "Applies to projects without a saved decision",
            checked = default == "always",
        ) { on -> save(settingsFile) { PiJsonFile.set(it, "defaultProjectTrust", if (on) JsonPrimitive("always") else null) } }
        if (changed && onReload != null) {
            F2Well {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Reload the session to apply changes", style = Pi.type.body, color = Pi.colors.ink1, modifier = Modifier.weight(1f))
                    SecondaryButton("Reload", onClick = { close(onReload) })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** Retry and compaction numbers (docs/settings.md); blank keeps Pi's default. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TuningSheet(agentDir: File, onReload: (() -> Unit)?, onMessage: (String) -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    val scope = rememberCoroutineScope()
    val file = remember { File(agentDir, "settings.json") }
    var loaded by remember { mutableStateOf<AgentTuning?>(null) }
    val fields = remember { mutableStateOf(mapOf<String, String>()) }
    LaunchedEffect(Unit) {
        val t = withContext(Dispatchers.IO) { AgentTuning.from(PiJsonFile.read(file) ?: JsonObject(emptyMap())) }
        loaded = t
        fields.value = mapOf(
            "reserve" to t.reserveTokens?.toString().orEmpty(),
            "keep" to t.keepRecentTokens?.toString().orEmpty(),
            "threshold" to t.thresholdPercent?.toString().orEmpty(),
            "retries" to t.maxRetries?.toString().orEmpty(),
            "base" to t.baseDelayMs?.toString().orEmpty(),
            "max" to t.maxAgentDelayMs?.toString().orEmpty(),
        )
    }
    fun value(key: String): Long? = fields.value[key]?.trim()?.takeIf { it.isNotEmpty() }?.toLongOrNull()
    val tuning = AgentTuning(value("reserve"), value("keep"), value("threshold"), value("retries"), value("base"), value("max"))
    val invalid = fields.value.any { (_, v) -> v.isNotBlank() && v.trim().toLongOrNull() == null }
    val problems = tuning.problems()
    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader("Retry and compaction", subtitle = "Empty fields use Pi's default. Applies to new sessions.")
        if (loaded == null) {
            F2Loading("Loading settings")
            return@PiSheet
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            F2Section("Compaction")
            NumberField("Compact above this % of the context", "90", fields, "threshold")
            NumberField("Tokens kept for the reply", "16384", fields, "reserve")
            NumberField("Recent tokens kept as they are", "20000 or 5% of the window", fields, "keep")
            F2Section("Retry")
            NumberField("Retries", "3", fields, "retries")
            NumberField("First delay (ms)", "2000", fields, "base")
            NumberField("Longest delay (ms)", "60000", fields, "max")
            for (p in problems) Text(p, style = Pi.type.meta, color = Pi.colors.danger, modifier = Modifier.padding(horizontal = 20.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                SecondaryButton("Cancel", onClick = { close(onDismiss) }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                PrimaryButton("Save", enabled = !invalid && problems.isEmpty(), onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { PiJsonFile.update(file) { tuning.applyTo(it) } }
                        onMessage(if (ok) (if (onReload != null) "Saved. Reload the session to apply changes." else "Saved") else "Could not save settings.json")
                        close(onDismiss)
                    }
                }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun NumberField(label: String, default: String, fields: androidx.compose.runtime.MutableState<Map<String, String>>, key: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(label, style = Pi.type.meta, color = Pi.colors.ink2, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
        val v = fields.value[key].orEmpty()
        PiTextField(
            v,
            { fields.value = fields.value + (key to it.filter(Char::isDigit).take(9)) },
            placeholder = "Default: $default",
            singleLine = true,
            isError = v.isNotBlank() && v.toLongOrNull() == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}

/**
 * The models in the picker and for cycling (the terminal's `/scoped-models`, `enabledModels` in settings.json). All on
 * means every model (no setting).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScopedModelsSheet(agentDir: File, models: List<ModelInfo>, onSaved: (List<String>) -> Unit, onMessage: (String) -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val file = remember { File(agentDir, "settings.json") }
    var patterns by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(Unit) { patterns = withContext(Dispatchers.IO) { ModelScope.read(PiJsonFile.read(file) ?: JsonObject(emptyMap())) } }
    fun save(next: List<String>) {
        patterns = next
        scope.launch {
            val ok = withContext(Dispatchers.IO) { PiJsonFile.update(file) { ModelScope.write(it, next) } }
            if (ok) onSaved(next) else onMessage("Could not save settings.json")
        }
    }
    val all = models.map { it.provider to it.id }
    PiSheet(onDismiss = onDismiss, state = state) {
        val p = patterns
        val on = if (p == null) 0 else models.count { ModelScope.enabled(p, it.provider, it.id) }
        SheetHeader("Models in the picker", subtitle = if (p.isNullOrEmpty()) "All ${models.size} models" else "$on of ${models.size} models")
        when {
            p == null || models.isEmpty() -> F2Loading("Loading models")
            else -> {
                if (p.isNotEmpty()) SecondaryButton("Show all models", onClick = { save(emptyList()) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    val groups = models.groupBy { it.provider }
                    for ((provider, list) in groups) {
                        item(key = "h:$provider") { F2Section(ProviderCatalog.name(provider)) }
                        items(list, key = { it.provider + "/" + it.id }) { m ->
                            F2Toggle(m.label, m.id, checked = ModelScope.enabled(p, m.provider, m.id)) { checked ->
                                val next = ModelScope.toggle(p, m.provider, m.id, checked, all)
                                // Every model on again: drop the setting.
                                save(if (models.all { ModelScope.enabled(next, it.provider, it.id) }) emptyList() else next)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A bug report (the terminal's `/bug`): the user's words plus versions and recent engine log, shared through Android. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BugSheet(initial: String, diagnostics: String, onShare: (String) -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    var text by remember { mutableStateOf(initial) }
    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader("Report a bug", subtitle = "The details below are included. Nothing is sent until you choose where to share it.")
        PiTextField(text, { text = it }, placeholder = "What went wrong, and what you expected", minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp))
        F2Well {
            Text(diagnostics, style = Pi.type.monoMeta, color = Pi.colors.ink2, maxLines = 12, modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            SecondaryButton("Cancel", onClick = { close(onDismiss) }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            PrimaryButton("Share report", enabled = text.isNotBlank(), onClick = {
                val report = "Pi-Bolt Android bug report\n\n" + text.trim() + "\n\n---\n" + diagnostics
                close { onShare(report) }
            }, modifier = Modifier.weight(1f))
        }
    }
}
