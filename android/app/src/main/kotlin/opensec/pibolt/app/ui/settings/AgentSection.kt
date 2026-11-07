package opensec.pibolt.app.ui.settings

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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.data.AgentDefaults
import opensec.pibolt.app.data.AgentSettingsFile
import opensec.pibolt.app.data.QueueMode
import opensec.pibolt.app.ui.components.LoadingRow
import opensec.pibolt.app.ui.components.Segmented
import opensec.pibolt.app.ui.projects.LocalSessions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import opensec.pibolt.app.ui.providers.Brand
import opensec.pibolt.app.ui.providers.LogoMark
import opensec.pibolt.app.ui.providers.ProviderCatalog
import opensec.pibolt.app.ui.theme.IconSize
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
internal fun AgentSection(app: PiBoltApp, show: (String) -> Boolean, onMessage: (String) -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val scope = rememberCoroutineScope()
    val file = remember { AgentSettingsFile(File(app.engine.agentDir, "settings.json")) }
    val loaded by produceState<AgentDefaults?>(null) { value = file.read() }
    var defaults by remember { mutableStateOf<AgentDefaults?>(null) }
    var picking by remember { mutableStateOf(false) }
    val current = defaults ?: loaded
    if (current == null) {
        LoadingRow("Loading settings")
        return
    }
    fun update(change: (AgentDefaults) -> AgentDefaults) {
        val next = change(current)
        defaults = next
        scope.launch {
            runCatching { file.write(next) }
            for (live in LocalSessions.all.value.values) live.controller.takeIf { !it.stopped }?.applyAgentDefaults(next)
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
                else -> "Automatic"
            } + if (controller == null) "\nOpen a session on this phone to choose" else "",
            onClick = { picking = true },
            enabled = controller != null,
            // The model's mark; automatic (no default yet) reads as the sparkle glyph.
            icon = Icons.Rounded.AutoAwesome,
            leading = defaultModelBrand(match, chosen)?.let { brand -> { LogoMark(brand, size = IconSize.md) } },
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
        Label("Default thinking level")
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
        Text("Delivered after Pi's current step", style = type.meta, color = colors.ink3, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
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
        Text("Sent when Pi finishes", style = type.meta, color = colors.ink3, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
    }
    if (show("compaction")) {
        ToggleRow("Auto-compact", "When the context is nearly full", current.autoCompaction) { on -> update { it.copy(autoCompaction = on) } }
    }
    if (show("retry")) {
        ToggleRow("Auto-retry", "On overload or rate limits", current.autoRetry) { on -> update { it.copy(autoRetry = on) } }
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
        ToggleRow("Skill commands", "/skill:name · applies to new sessions", current.skillCommands) { on -> update { it.copy(skillCommands = on) } }
    }
    if (show("images")) {
        ToggleRow("Block images", "Applies to new sessions", current.blockImages) { on -> update { it.copy(blockImages = on) } }
    }
    if (show("engine-links")) {
        Spacer(Modifier.height(8.dp))
        AgentLinks(app, onMessage)
    }
}

/** The default model's mark: the listed model's, else from the saved "provider/model" id; null when automatic. */
private fun defaultModelBrand(match: ModelInfo?, chosen: String?): Brand? = when {
    match != null -> ProviderCatalog.modelBrand(match.id, match.provider)
    chosen != null && '/' in chosen -> ProviderCatalog.modelBrand(chosen.substringAfter('/'), chosen.substringBefore('/'))
    chosen != null -> ProviderCatalog.modelBrand(chosen, null)
    else -> null
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
