package opensec.pibolt.app.ui.packages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import opensec.pibolt.app.ui.components.ButtonRow
import opensec.pibolt.app.ui.components.FieldHint
import opensec.pibolt.app.ui.components.FieldLabel
import opensec.pibolt.app.ui.components.PiTextField
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.SecretField
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.ui.session.features2.F2Row
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Space

/**
 * The packages sheet's "Advanced" section, closed by default: a private npm registry (URL, optional scope, token).
 * The token is typed into a password field, sealed by the Android Keystore on Save ([RegistryStore]) and never shown
 * again: once saved the section says "Token saved" with Remove. [onChanged] runs after every change.
 */
@Composable
fun RegistrySection(store: RegistryStore, onMessage: (String) -> Unit, onChanged: () -> Unit) {
    val colors = Pi.colors
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    var saved by remember { mutableStateOf<RegistryConfig?>(null) }
    var hasToken by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf(Npmrc.DEFAULT_REGISTRY) }
    var scopeText by remember { mutableStateOf("") }
    // The token lives only here until Save, never in saved instance state, and is cleared after it.
    var token by remember { mutableStateOf("") }
    var tokenShown by remember { mutableStateOf(false) }
    var tried by remember { mutableStateOf(false) }
    LaunchedEffect(version) {
        val (config, withToken) = withContext(Dispatchers.IO) { store.config() to store.hasToken }
        saved = config
        hasToken = withToken
        url = config?.url ?: Npmrc.DEFAULT_REGISTRY
        scopeText = config?.scope.orEmpty()
    }

    fun change(message: String, action: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { action() }
            token = ""
            tokenShown = false
            tried = false
            version++
            onChanged()
            onMessage(message)
        }
    }

    F2Row(
        title = "Advanced",
        meta = saved?.let { c -> listOfNotNull(c.scope ?: "All packages", c.url, if (hasToken) "token saved" else null).joinToString(" · ") } ?: "Private registry",
        icon = Icons.Rounded.Tune,
        onClick = { open = !open },
        titleMaxLines = 1,
    ) {
        Icon(if (open) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, contentDescription = if (open) "Collapse" else "Expand", tint = colors.ink3, modifier = Modifier.padding(end = Space.xs))
    }
    AnimatedVisibility(open) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.xl)) {
            Text(
                "Private packages from your company's npm registry. The token is stored encrypted on this phone and given to npm only while it installs.",
                style = Pi.type.meta,
                color = colors.ink3,
                modifier = Modifier.padding(top = Space.xs),
            )
            val urlProblem = Npmrc.urlProblem(url)
            val scopeProblem = Npmrc.scopeProblem(scopeText)
            val tokenProblem = token.takeIf { it.isNotEmpty() }?.let(Npmrc::tokenProblem)
            FieldLabel("Registry URL")
            PiTextField(
                value = url,
                onValueChange = { url = it.take(500) },
                placeholder = Npmrc.DEFAULT_REGISTRY,
                singleLine = true,
                isError = tried && urlProblem != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            if (tried && urlProblem != null) FieldHint(urlProblem, isError = true)
            FieldLabel("Scope (optional)")
            PiTextField(
                value = scopeText,
                onValueChange = { scopeText = it.take(214) },
                placeholder = "@company",
                singleLine = true,
                isError = scopeProblem != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            FieldHint(scopeProblem ?: "With a scope, only @scope/ packages come from this registry; without one, every package does.", isError = scopeProblem != null)
            FieldLabel("Token")
            val urlChanged = saved != null && Npmrc.normalizeUrl(url) != null && Npmrc.normalizeUrl(url) != saved?.url
            if (hasToken && urlChanged) FieldHint("Saving a new address removes the saved token: it belongs to the old registry.")
            if (hasToken) {
                Row(Modifier.fillMaxWidth().padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Key, contentDescription = null, tint = colors.success, modifier = Modifier.padding(end = Space.sm).size(IconSize.md))
                    Text("Token saved", style = Pi.type.body, color = colors.ink1, modifier = Modifier.weight(1f))
                    TextAction("Remove", onClick = { change("Registry token removed") { store.removeToken() } }, color = colors.danger)
                }
            } else {
                SecretField(
                    value = token,
                    onValueChange = { token = it.trim().take(4096) },
                    shown = tokenShown,
                    onToggleShown = { tokenShown = !tokenShown },
                    placeholder = "npm_…",
                    label = "Registry token",
                    isError = tokenProblem != null,
                )
                FieldHint(tokenProblem ?: "Optional for a public mirror. Never shown again after saving.", isError = tokenProblem != null)
            }
            Spacer(Modifier.height(Space.md))
            ButtonRow {
                if (saved != null) {
                    SecondaryButton("Remove registry", onClick = { change("Private registry removed") { store.clear() } })
                }
                PrimaryButton(
                    "Save",
                    onClick = {
                        tried = true
                        val normalized = Npmrc.normalizeUrl(url)
                        if (normalized == null || scopeProblem != null || tokenProblem != null) return@PrimaryButton
                        val config = RegistryConfig(normalized, Npmrc.normalizeScope(scopeText))
                        val newToken = token.takeIf { it.isNotEmpty() }
                        change("Private registry saved") {
                            store.save(config)
                            newToken?.let(store::saveToken)
                        }
                    },
                )
            }
            Spacer(Modifier.height(Space.sm))
        }
    }
}
