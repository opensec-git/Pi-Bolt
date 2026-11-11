package opensec.pibolt.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import opensec.pibolt.app.engine.EngineProvider
import opensec.pibolt.app.engine.ProviderRules
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
 * name and mark on a glyph tile, each with a remove action, then the providers installed Pi packages register (with
 * their own name; tap to add a key or sign in), then Add provider. Custom endpoints (models.json) show their host and
 * model; removing one also removes its models.json entry (confirmed first). The engine is asked for the providers
 * again when the page opens ([ProviderDirectory][opensec.pibolt.app.ui.providers.ProviderDirectory]).
 */
@Composable
fun ProvidersScreen(app: PiBoltApp, onBack: () -> Unit) = LargeTitlePage("Providers", onBack = onBack) { ProvidersList(app) }

@Composable
private fun ProvidersList(app: PiBoltApp) {
    val colors = Pi.colors
    val auth = app.engine.auth
    val agentDir = app.engine.agentDir
    val configured by auth.configured.collectAsStateWithLifecycle()
    val report by app.providers.report.collectAsStateWithLifecycle()
    val customIds by app.providers.custom.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var setUp by remember { mutableStateOf<EngineProvider?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { app.providers.refresh() }
    val custom by produceState(emptyMap<String, CustomProvider>(), configured, reload, customIds) {
        val list = ModelsFile.custom(agentDir)
        ProviderCatalog.registerCustom(list)
        value = list.associateBy { it.id }
    }
    val list = remember(configured, custom, report) {
        ProviderRules.rows(configured, report, ProviderCatalog.ids, custom.keys, ProviderCatalog::name)
    }

    val rows = buildList<SettingsRow> {
        for (row in list) {
            add { d ->
                val endpoint = custom[row.id]
                val engine = row.engine
                val subtitle = when {
                    endpoint != null -> listOfNotNull(ProviderCatalog.hostOf(endpoint.baseUrl), endpoint.models.firstOrNull()).joinToString(" · ")
                    row.kind == ProviderRules.Kind.Extension && engine != null -> "Pi package · " + ProviderRules.extensionNote(engine, row.stored)
                    ProviderCatalog.get(row.id) != null -> "Key on this phone"
                    else -> "Credential on this phone"
                }
                // An extension provider opens its key or sign-in; with a credential, Remove is beside it.
                val open = engine?.takeIf { row.kind == ProviderRules.Kind.Extension && (it.acceptsKey || it.canLogin) }?.let { e -> { setUp = e } }
                GroupRow(
                    row.name,
                    leading = { LogoMark(ProviderCatalog.brand(row.id), size = IconSize.sm, tint = colors.ink1) },
                    meta = subtitle,
                    metaMaxLines = 1,
                    divider = d,
                    onClick = open,
                    end = if (open != null && !row.stored) GroupEnd.Chevron else GroupEnd.None,
                    trailing = if (row.stored) {
                        { IconAction(Icons.Rounded.DeleteOutline, "Remove ${row.name}", onClick = { removing = row.id }, tint = colors.ink3, size = IconSize.md) }
                    } else {
                        null
                    },
                )
            }
        }
        add { d -> GroupRow("Add provider", icon = Icons.Rounded.Add, iconTint = colors.tint0, onClick = { adding = true }, end = GroupEnd.None, divider = d) }
    }
    SettingsBlock(rows, header = if (list.isEmpty()) "No provider yet" else "On this phone", footer = "Keys stay on this phone.")

    if (adding || setUp != null) {
        ProviderSheet(auth = auth, configured = configured, firstRun = false, extension = setUp, onSaved = {
            adding = false
            setUp = null
            reload++
        }, onDismiss = {
            adding = false
            setUp = null
        })
    }
    removing?.let { id ->
        val name = list.firstOrNull { it.id == id }?.name ?: ProviderCatalog.name(id)
        val isCustom = id in custom
        ConfirmSheet(
            title = "Remove $name?",
            message = if (isCustom) {
                "This server's models and key are removed from this phone."
            } else {
                "The credential is deleted from this phone. It stays valid at $name until you revoke it there."
            },
            confirmLabel = "Remove",
            onConfirm = {
                removing = null
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { auth.remove(id) } }
                    if (isCustom) runCatching { ModelsFile.update(agentDir) { ModelsFile.remove(it, id) } }
                    runCatching { AgentSettingsFile(File(agentDir, "settings.json")).forgetDefaultModelOf(id) }
                    reload++
                    app.providers.refresh()
                }
            },
            onDismiss = { removing = null },
        )
    }
}
