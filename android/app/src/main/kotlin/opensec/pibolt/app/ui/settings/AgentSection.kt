package opensec.pibolt.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.data.AgentDefaults
import opensec.pibolt.app.data.AgentSettingsFile
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.app.ui.projects.LocalSessions
import opensec.pibolt.app.ui.providers.Brand
import opensec.pibolt.app.ui.providers.ProviderCatalog
import opensec.pibolt.protocol.rpc.ModelInfo
import java.io.File

/**
 * The on-device engine's settings (its agent/settings.json, as in the terminal's /settings) as Settings shows them:
 * the [current] values, the open on-device session's [models] (Pi lists only models it can authenticate), the
 * default model's [match] among them, and [update], which saves and also reaches every open on-device session at once
 * where Pi has an RPC command for it (the rest apply from the next session).
 */
internal class AgentPrefs(
    val current: AgentDefaults,
    val update: ((AgentDefaults) -> AgentDefaults) -> Unit,
    /** The open on-device session; null when none is open (the model picker then has nothing to list). */
    val controller: SessionController?,
    /** Any open session (for the package details' commands and tools). */
    val anyLive: SessionController?,
    val models: List<ModelInfo>,
) {
    val chosen: String? get() = current.defaultModel
    val match: ModelInfo? get() = models.firstOrNull { "${it.provider}/${it.id}" == chosen || it.id == chosen }

    /** "Claude Sonnet 4.5 · Anthropic", the saved id while no session lists it, or Automatic. */
    val modelText: String get() {
        val m = match
        val c = chosen
        return when {
            m != null -> "${m.label} · ${ProviderCatalog.name(m.provider)}"
            c != null -> modelLabel(c)
            else -> "Automatic"
        }
    }

    /** The default model's mark; null while automatic. */
    val brand: Brand? get() = defaultModelBrand(match, chosen)
}

/** Reads agent/settings.json once and keeps the live session's model list; null while reading. */
@Composable
internal fun rememberAgentPrefs(app: PiBoltApp): AgentPrefs? {
    val scope = rememberCoroutineScope()
    val file = remember { AgentSettingsFile(File(app.engine.agentDir, "settings.json")) }
    val loaded by produceState<AgentDefaults?>(null) { value = file.read() }
    var defaults by remember { mutableStateOf<AgentDefaults?>(null) }
    val live by LocalSessions.current.collectAsStateWithLifecycle()
    val anyLive = live?.controller?.takeIf { !it.stopped }
    val controller = anyLive?.takeIf { it.isLocal }
    val models by (controller?.models ?: remember { MutableStateFlow(emptyList<ModelInfo>()) }).collectAsStateWithLifecycle()
    LaunchedEffect(controller) { if (controller != null && controller.models.value.isEmpty()) controller.loadModels() }
    val current = defaults ?: loaded ?: return null
    return AgentPrefs(
        current = current,
        update = { change ->
            val next = change(defaults ?: loaded ?: current)
            defaults = next
            scope.launch {
                runCatching { file.write(next) }
                for (session in LocalSessions.all.value.values) session.controller.takeIf { !it.stopped }?.applyAgentDefaults(next)
            }
        },
        controller = controller,
        anyLive = anyLive,
        models = models,
    )
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

/** A thinking level's name in a row's value and the choice sheet: "Off", "Minimal", …, "Extra high". */
internal fun thinkingLabel(level: String): String = when (level) {
    "xhigh" -> "Extra high"
    else -> level.replaceFirstChar { it.uppercase() }
}
