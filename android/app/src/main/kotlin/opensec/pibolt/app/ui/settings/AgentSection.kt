package opensec.pibolt.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.data.AgentDefaults
import opensec.pibolt.app.data.AgentSettingsFile
import opensec.pibolt.app.data.QueueMode
import opensec.pibolt.app.ui.components.Segmented
import opensec.pibolt.app.ui.projects.LocalSessions
import opensec.pibolt.app.ui.providers.ProviderCatalog
import opensec.pibolt.app.ui.session.ModelSheet
import opensec.pibolt.app.ui.session.features2.AgentLinks
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.protocol.rpc.ModelInfo
import java.io.File

/**
 * The on-device engine's settings (its agent/settings.json, as in the terminal's /settings): the default model and
 * thinking level for new sessions, queued message delivery, automatic compaction and retry, skill commands and image
 * blocking. A change also reaches the open on-device session at once where Pi has an RPC command for it; the rest
 * apply from the next session.
 */
@Composable
internal fun AgentSection(app: PiBoltApp, show: (String) -> Boolean) {
    val colors = Pi.colors
    val type = Pi.type
    val scope = rememberCoroutineScope()
    val file = remember { AgentSettingsFile(File(app.engine.agentDir, "settings.json")) }
    val loaded by produceState<AgentDefaults?>(null) { value = file.read() }
    var defaults by remember { mutableStateOf<AgentDefaults?>(null) }
    var picking by remember { mutableStateOf(false) }
    val current = defaults ?: loaded
    if (current == null) {
        Text("Reading settings…", style = type.meta, color = colors.ink3)
        return
    }
    fun update(change: (AgentDefaults) -> AgentDefaults) {
        val next = change(current)
        defaults = next
        scope.launch {
            runCatching { file.write(next) }
            LocalSessions.current.value?.controller?.takeIf { !it.stopped }?.applyAgentDefaults(next)
        }
    }
    // The model list comes from a running on-device session (Pi lists only models it can authenticate).
    val live by LocalSessions.current.collectAsStateWithLifecycle()
    val controller = live?.controller?.takeIf { !it.stopped && it.isLocal }
    val models by (controller?.models ?: remember { MutableStateFlow(emptyList<ModelInfo>()) }).collectAsStateWithLifecycle()
    LaunchedEffect(controller) { if (controller != null && controller.models.value.isEmpty()) controller.loadModels() }

    if (show("default-model")) {
        val chosen = current.defaultModel
        val match = models.firstOrNull { "${it.provider}/${it.id}" == chosen || it.id == chosen }
        NavRow(
            label = "Default model",
            value = when {
                match != null -> "${match.label} · ${ProviderCatalog.name(match.provider)}"
                chosen != null -> modelLabel(chosen)
                else -> "Automatic: the first model with a key"
            } + if (controller == null) "\nOpen a session on this phone to choose from its models" else "",
            onClick = { picking = true },
            enabled = controller != null,
        )
        Spacer(Modifier.height(8.dp))
        if (picking && controller != null) {
            ModelSheet(models, match, onPick = { m ->
                picking = false
                update { it.copy(defaultModel = "${m.provider}/${m.id}") }
            }, onDismiss = { picking = false })
        }
    }
    if (show("thinking")) {
        Label("Thinking level for new sessions")
        Segmented(
            options = AgentDefaults.THINKING_LEVELS.map { it to thinkingShort(it) },
            selected = current.defaultThinking.takeIf { it in AgentDefaults.THINKING_LEVELS } ?: "medium",
            onSelect = { level -> update { it.copy(defaultThinking = level) } },
            modifier = Modifier.fillMaxWidth(),
            height = 48.dp,
        )
        Spacer(Modifier.height(16.dp))
    }
    if (show("steering")) {
        Label("Steering messages")
        Segmented(
            options = QueueMode.entries.map { it to it.label },
            selected = current.steeringMode,
            onSelect = { m -> update { it.copy(steeringMode = m) } },
            modifier = Modifier.fillMaxWidth(),
            height = 48.dp,
        )
        Text("Sent while Pi works; delivered after its current step.", style = type.meta, color = colors.ink3, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
    }
    if (show("follow-up")) {
        Label("Follow-up messages")
        Segmented(
            options = QueueMode.entries.map { it to it.label },
            selected = current.followUpMode,
            onSelect = { m -> update { it.copy(followUpMode = m) } },
            modifier = Modifier.fillMaxWidth(),
            height = 48.dp,
        )
        Text("Queued until Pi finishes, then sent.", style = type.meta, color = colors.ink3, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
    }
    if (show("compaction")) {
        ToggleRow("Auto-compact", "Summarise older messages when the context is nearly full", current.autoCompaction) { on -> update { it.copy(autoCompaction = on) } }
    }
    if (show("retry")) {
        ToggleRow("Auto-retry", "Retry when the provider is overloaded or rate-limited", current.autoRetry) { on -> update { it.copy(autoRetry = on) } }
        if (current.autoRetry) {
            LabelWithValue("Retries", if (current.maxRetries == 1) "1 attempt" else "${current.maxRetries} attempts")
            Segmented(
                options = AgentDefaults.RETRY_CHOICES.map { it to it.toString() },
                selected = current.maxRetries.takeIf { it in AgentDefaults.RETRY_CHOICES } ?: 3,
                onSelect = { n -> update { it.copy(maxRetries = n) } },
                modifier = Modifier.fillMaxWidth(),
                height = 48.dp,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
    if (show("skills")) {
        ToggleRow("Skill commands", "List skills as /skill:name commands. From the next session", current.skillCommands) { on -> update { it.copy(skillCommands = on) } }
    }
    if (show("images")) {
        ToggleRow("Block images", "Never send images to a model. From the next session", current.blockImages) { on -> update { it.copy(blockImages = on) } }
    }
    if (show("engine-links")) {
        Spacer(Modifier.height(8.dp))
        val context = LocalContext.current
        AgentLinks(app) { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }
}

/** "anthropic/claude-sonnet-4" as "claude-sonnet-4 · Anthropic". */
internal fun modelLabel(full: String): String {
    val slash = full.indexOf('/')
    if (slash <= 0 || slash == full.length - 1) return full
    return full.substring(slash + 1) + " · " + ProviderCatalog.name(full.substring(0, slash))
}

private fun thinkingShort(level: String): String = when (level) {
    "minimal" -> "Min"
    "medium" -> "Med"
    "xhigh" -> "XHigh"
    else -> level.replaceFirstChar { it.uppercase() }
}
