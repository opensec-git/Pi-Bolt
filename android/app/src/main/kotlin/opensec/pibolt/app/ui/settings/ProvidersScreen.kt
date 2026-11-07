package opensec.pibolt.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.data.AgentSettingsFile
import opensec.pibolt.app.engine.CustomProvider
import opensec.pibolt.app.engine.ModelsFile
import opensec.pibolt.app.ui.components.ConfirmSheet
import opensec.pibolt.app.ui.components.GroupEnd
import opensec.pibolt.app.ui.components.GroupRow
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.LargeTitlePage
import opensec.pibolt.app.ui.providers.LogoMark
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.providers.ProviderCatalog
import opensec.pibolt.app.ui.providers.ProviderSheet
import opensec.pibolt.app.ui.theme.Pi
import java.io.File

/**
 * Settings > Models & keys > Providers, a large-title page: the providers with a stored credential, by their human
 * name and mark on a glyph tile, each with a remove action, then Add provider. Custom endpoints (models.json) show
 * their host and model; removing one also removes its models.json entry (confirmed first).
 */
@Composable
fun ProvidersScreen(app: PiBoltApp, onBack: () -> Unit) = LargeTitlePage("Providers", onBack = onBack) { ProvidersList(app) }

@Composable
private fun ProvidersList(app: PiBoltApp) {
    val colors = Pi.colors
    val auth = app.engine.auth
    val agentDir = app.engine.agentDir
    val configured by auth.configured.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val custom by produceState(emptyMap<String, CustomProvider>(), configured, reload) {
        val list = ModelsFile.custom(agentDir)
        ProviderCatalog.registerCustom(list)
        value = list.associateBy { it.id }
    }
    val ids = remember(configured, custom) { configured.sortedBy { ProviderCatalog.name(it).lowercase() } }

    val rows = buildList<SettingsRow> {
        for (id in ids) {
            add { d ->
                val name = ProviderCatalog.name(id)
                val endpoint = custom[id]
                val subtitle = when {
                    endpoint != null -> listOfNotNull(ProviderCatalog.hostOf(endpoint.baseUrl), endpoint.models.firstOrNull()).joinToString(" · ")
                    ProviderCatalog.get(id) != null -> "Key on this phone"
                    else -> "Credential on this phone"
                }
                GroupRow(
                    name,
                    leading = { LogoMark(ProviderCatalog.brand(id), size = IconSize.sm, tint = colors.ink1) },
                    meta = subtitle,
                    metaMaxLines = 1,
                    divider = d,
                    trailing = { IconAction(Icons.Rounded.DeleteOutline, "Remove $name", onClick = { removing = id }, tint = colors.ink3, size = IconSize.md) },
                )
            }
        }
        add { d -> GroupRow("Add provider", icon = Icons.Rounded.Add, iconTint = colors.tint0, onClick = { adding = true }, end = GroupEnd.None, divider = d) }
    }
    SettingsBlock(rows, header = if (ids.isEmpty()) "No provider yet" else "On this phone", footer = "Keys stay on this phone.")

    if (adding) {
        ProviderSheet(auth = auth, configured = configured, firstRun = false, onSaved = {
            adding = false
            reload++
        }, onDismiss = { adding = false })
    }
    removing?.let { id ->
        val name = ProviderCatalog.name(id)
        val isCustom = id in custom
        ConfirmSheet(
            title = "Remove $name?",
            message = if (isCustom) {
                "This server's models and key are removed from this phone."
            } else {
                "The key is deleted from this phone. It stays valid at $name until you revoke it there."
            },
            confirmLabel = "Remove",
            onConfirm = {
                removing = null
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { auth.remove(id) } }
                    if (isCustom) runCatching { ModelsFile.update(agentDir) { ModelsFile.remove(it, id) } }
                    runCatching { AgentSettingsFile(File(agentDir, "settings.json")).forgetDefaultModelOf(id) }
                    reload++
                }
            },
            onDismiss = { removing = null },
        )
    }
}
