package opensec.pibolt.app.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.ui.components.ConfirmSheet
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.providers.ProviderBadge
import opensec.pibolt.app.ui.providers.ProviderCatalog
import opensec.pibolt.app.ui.providers.ProviderSheet
import opensec.pibolt.app.ui.theme.Pi

/** Settings > Model providers: the providers with a stored credential (remove with a confirmation), and Add. */
@Composable
fun ProvidersSection(app: PiBoltApp) {
    val colors = Pi.colors
    val type = Pi.type
    val auth = app.engine.auth
    val configured by auth.configured.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    val ids = remember(configured) { configured.sortedBy { ProviderCatalog.name(it).lowercase() } }

    if (ids.isEmpty()) {
        Text(
            "No provider yet. Add an API key so Pi can call a model from this phone.",
            style = type.body,
            color = colors.ink2,
            modifier = Modifier.padding(bottom = 12.dp),
        )
    } else {
        Column(Modifier.padding(bottom = 8.dp)) {
            for (id in ids) {
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProviderBadge(ProviderCatalog.name(id))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ProviderCatalog.name(id), style = type.bodyStrong, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Key stored on this phone", style = type.meta, color = colors.ink3, maxLines = 1)
                    }
                    IconAction(Icons.Rounded.DeleteOutline, "Remove ${ProviderCatalog.name(id)}", onClick = { removing = id }, tint = colors.ink3, size = 20.dp)
                }
            }
        }
    }
    SecondaryButton("Add provider", onClick = { adding = true }, icon = Icons.Rounded.Add, modifier = Modifier.fillMaxWidth())

    if (adding) {
        ProviderSheet(auth = auth, configured = configured, firstRun = false, onSaved = { adding = false }, onDismiss = { adding = false })
    }
    removing?.let { id ->
        val name = ProviderCatalog.name(id)
        ConfirmSheet(
            title = "Remove $name?",
            message = "Pi on this phone stops using $name until you add a key again. The key is deleted from auth.json here; it is not revoked at $name.",
            confirmLabel = "Remove",
            onConfirm = {
                removing = null
                scope.launch { withContext(Dispatchers.IO) { runCatching { auth.remove(id) } } }
            },
            onDismiss = { removing = null },
        )
    }
}
