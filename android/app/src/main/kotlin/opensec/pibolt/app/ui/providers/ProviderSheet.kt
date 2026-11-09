package opensec.pibolt.app.ui.providers

import opensec.pibolt.app.ui.components.FieldHint
import opensec.pibolt.app.ui.components.FieldLabel
import opensec.pibolt.app.ui.components.SecretField
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import opensec.pibolt.app.PiBoltApp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import opensec.pibolt.app.engine.CustomEndpoint
import opensec.pibolt.app.engine.EngineProvider
import opensec.pibolt.app.engine.ProviderRules
import opensec.pibolt.app.engine.ModelsFile
import opensec.pibolt.app.engine.ProviderAuth
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import opensec.pibolt.app.R
import opensec.pibolt.app.ui.components.GhostButton
import opensec.pibolt.app.ui.components.InsetRow
import opensec.pibolt.app.ui.components.PillSearchField
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.glass
import opensec.pibolt.app.ui.theme.Space
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.PiTextField
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius

/** Where the provider sheet is: the list, one provider's key, or a custom endpoint. */
private sealed interface Step {
    /** [extension]: a provider a Pi package registers, which may also offer its own sign-in. */
    data class Key(val provider: ProviderInfo, val extension: EngineProvider? = null) : Step

    data object Custom : Step
}

/** An extension provider as the key step shows it. */
private fun EngineProvider.info(): ProviderInfo =
    ProviderInfo(id, name, note = "From a Pi package", keyUrl = null)

/**
 * Add a provider's API key: pick the provider (search), paste the key (obscured), save to Pi's auth.json through
 * [auth]. The key lives only in this sheet's memory (never in saved state), is cleared after saving and never logged.
 * The last row adds a custom OpenAI-compatible endpoint to Pi's models.json. [firstRun] shows the welcome wording and
 * a "Later" action. Back on a step returns to the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSheet(
    auth: ProviderAuth,
    configured: Set<String>,
    firstRun: Boolean,
    onSaved: (ProviderInfo) -> Unit,
    onDismiss: () -> Unit,
    initial: ProviderInfo? = null,
    extension: EngineProvider? = null,
) {
    val app = LocalContext.current.applicationContext as PiBoltApp
    val extensions by app.providers.extensions.collectAsStateWithLifecycle()
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    // An extension provider without a key field goes straight to its sign-in.
    var signingIn by remember { mutableStateOf(extension?.takeIf { !it.acceptsKey && it.canLogin }) }
    var step by remember { mutableStateOf<Step?>(initial?.let { Step.Key(it) } ?: extension?.takeIf { it.acceptsKey }?.let { Step.Key(it.info(), it) }) }
    val fixed = initial != null || extension != null
    val saved: (ProviderInfo) -> Unit = { info ->
        app.providers.refresh()
        onSaved(info)
    }
    signingIn?.let { p ->
        ProviderLoginSheet(p, onDone = { saved(p.info()) }, onDismiss = onDismiss)
        return
    }
    fun pick(p: EngineProvider) {
        if (p.acceptsKey) step = Step.Key(p.info(), p) else if (p.canLogin) close { signingIn = p }
    }
    val reduced = Pi.reduceMotion
    PiSheet(onDismiss = onDismiss, state = state) {
        BackHandler(enabled = step != null && !fixed) { step = null }
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState != null
                if (reduced) {
                    fadeIn(PiMotion.enter(PiMotion.QUICK)) togetherWith fadeOut(PiMotion.leave(PiMotion.QUICK))
                } else {
                    (slideInHorizontally(PiMotion.enter(PiMotion.STANDARD)) { if (forward) it / 6 else -it / 6 } + fadeIn(PiMotion.enter(PiMotion.STANDARD))) togetherWith
                        fadeOut(PiMotion.leave(PiMotion.QUICK)) + slideOutHorizontally(PiMotion.leave(PiMotion.STANDARD)) { if (forward) -it / 8 else it / 8 }
                }
            },
            contentKey = {
                when (it) {
                    null -> "list"
                    is Step.Key -> it.provider.id
                    Step.Custom -> "custom"
                }
            },
            label = "provider-step",
        ) { current ->
            when (current) {
                null -> PickStep(
                    configured,
                    firstRun,
                    extensions,
                    onPick = { step = Step.Key(it) },
                    onExtension = ::pick,
                    onCustom = { step = Step.Custom },
                    onLater = { close(onDismiss) },
                )
                is Step.Key -> KeyStep(
                    provider = current.provider,
                    auth = auth,
                    onBack = if (!fixed) ({ step = null }) else null,
                    onSaved = { close { saved(current.provider) } },
                    signIn = current.extension?.takeIf { it.canLogin }?.let { p -> { close { signingIn = p } } },
                    signInLabel = current.extension?.let { it.loginLabel ?: it.loginName?.let { n -> "Sign in with $n" } },
                )
                Step.Custom -> CustomStep(
                    auth = auth,
                    onBack = { step = null },
                    onSaved = { info -> close { saved(info) } },
                )
            }
        }
    }
}

@Composable
private fun PickStep(
    configured: Set<String>,
    firstRun: Boolean,
    extensions: List<EngineProvider>,
    onPick: (ProviderInfo) -> Unit,
    onExtension: (EngineProvider) -> Unit,
    onCustom: () -> Unit,
    onLater: () -> Unit,
) {
    val colors = Pi.colors
    val type = Pi.type
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(query) { ProviderCatalog.search(query) }
    val fromPackages = remember(query, extensions) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) extensions else extensions.filter { q in it.name.lowercase() || q in it.id }
    }
    Column {
        if (firstRun) {
            // The welcome: the bolt on a glass disc, calm and small, over the one question of this step.
            Box(
                Modifier.padding(start = Space.page, top = Space.sm, bottom = Space.xs).size(56.dp).glass(CircleShape, colors),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.drawable.ic_pibolt_logo), contentDescription = null, modifier = Modifier.size(width = 20.dp, height = 30.dp))
            }
        }
        SheetHeader(
            "Connect a model",
            subtitle = if (firstRun) "Pi-Bolt runs on this phone with your API key. The key stays here." else "The key stays on this phone.",
        )
        PillSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search ${ProviderCatalog.all.size + extensions.size} providers",
            modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm),
        )
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            // Providers that installed Pi packages register: first, they are why the user is here.
            if (fromPackages.isNotEmpty()) {
                item(key = "packages", contentType = "label") { ListHead("From your packages") }
                items(fromPackages, key = { "ext:" + it.id }, contentType = { "provider" }) { p ->
                    InsetRow(
                        p.name,
                        onClick = { onExtension(p) },
                        meta = ProviderRules.extensionNote(p, stored = p.id in configured),
                        leading = { LogoMark(ProviderCatalog.brand(p.id), size = IconSize.md) },
                        label = if (p.usable) "${p.name}, ready" else p.name,
                        trailing = { if (p.usable) Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.success, modifier = Modifier.size(IconSize.md)) },
                        chevron = !p.usable,
                    )
                }
            }
            if (query.isBlank()) {
                item(key = "popular", contentType = "label") { ListHead("Popular") }
                items(ProviderCatalog.popular, key = { it.id }, contentType = { "provider" }) { p ->
                    ProviderRow(p, added = p.id in configured, onClick = { onPick(p) })
                }
                item(key = "more", contentType = "label") { ListHead("More providers") }
                items(ProviderCatalog.more, key = { it.id }, contentType = { "provider" }) { p ->
                    ProviderRow(p, added = p.id in configured, onClick = { onPick(p) })
                }
            } else {
                items(shown, key = { it.id }, contentType = { "provider" }) { p ->
                    ProviderRow(p, added = p.id in configured, onClick = { onPick(p) })
                }
            }
            item(key = "custom", contentType = "custom") {
                val none = shown.isEmpty() && fromPackages.isEmpty()
                InsetRow(
                    if (none) "Use a custom endpoint" else "Custom endpoint",
                    onClick = onCustom,
                    meta = if (none) "No provider matches “${query.trim()}”" else "Ollama, LM Studio, vLLM or a proxy",
                    leading = { LogoMark(Brand.Generic, size = IconSize.md) },
                    chevron = true,
                    divider = false,
                )
            }
        }
        if (firstRun) {
            GhostButton("Later", onClick = onLater, modifier = Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.xs))
        }
    }
}

/** A section head of the provider list: rowMeta ink3 at the text inset. */
@Composable
private fun ListHead(text: String) {
    Text(text, style = Pi.type.rowMeta, color = Pi.colors.ink3, modifier = Modifier.padding(start = Space.page, end = Space.page, top = Space.lg, bottom = Space.xs).semantics { heading() })
}

/** A provider row ([InsetRow]): its mark, the name with its note beneath, a check for configured ones. */
@Composable
fun ProviderRow(p: ProviderInfo, added: Boolean, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val colors = Pi.colors
    InsetRow(
        p.name,
        onClick = onClick,
        meta = p.note,
        leading = { LogoMark(p.brand, size = IconSize.md) },
        label = if (added) "${p.name}, added" else p.name,
        trailing = {
            if (added) Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.success, modifier = Modifier.size(IconSize.md))
            trailing?.invoke()
        },
    )
}

@Composable
private fun KeyStep(
    provider: ProviderInfo,
    auth: ProviderAuth,
    onBack: (() -> Unit)?,
    onSaved: () -> Unit,
    signIn: (() -> Unit)? = null,
    signInLabel: String? = null,
) {
    val colors = Pi.colors
    val type = Pi.type
    val haptics = Pi.haptics
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val uri = LocalUriHandler.current
    // Plain remember, never rememberSaveable: the key must not end up in the saved instance state.
    var key by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun save() {
        val clean = ProviderCatalog.cleanKey(key)
        if (clean == null) {
            error = "That doesn't look like an API key"
            haptics.reject()
            return
        }
        saving = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { auth.setApiKey(provider.id, clean) }.isSuccess }
            key = ""
            saving = false
            if (ok) {
                haptics.confirm()
                onSaved()
            } else {
                error = "Could not save the key"
                haptics.reject()
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        SheetHeader(
            provider.name,
            subtitle = "Paste your API key.",
            onBack = onBack?.let { back ->
                {
                    key = ""
                    back()
                }
            },
            backLabel = "All providers",
            leading = { LogoMark(provider.brand, size = IconSize.lg, tint = colors.ink1) },
        )
        Spacer(Modifier.height(Space.sm))
        SecretField(
            value = key,
            onValueChange = {
                key = it
                error = null
            },
            placeholder = provider.keyHint.ifBlank { "API key" },
            label = "API key",
            shown = shown,
            isError = error != null,
            onToggleShown = { shown = !shown },
            onPaste = {
                scope.launch {
                    val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                    if (text != null) {
                        key = text.trim()
                        error = null
                    }
                }
            },
            onDone = ::save,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(
            Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(horizontal = 20.dp, vertical = 6.dp)
                .semantics(mergeDescendants = true) { if (error != null) liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Text(error ?: "Stays on this phone, sent only to ${provider.name}.", style = type.meta, color = if (error != null) colors.danger else colors.ink3)
        }
        provider.keyUrl?.let { url ->
            Row(
                Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .pressable({ runCatching { uri.openUri(url) } }, shape = RoundedCornerShape(Radius.pill), pressedScale = 1f, label = "Get a key")
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Get a key at ", style = type.body, color = colors.ink2)
                Text(url.removePrefix("https://").substringBefore('/'), style = type.body, color = colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(Space.md))
        PrimaryButton(if (saving) "Saving…" else "Save key", onClick = ::save, enabled = key.isNotBlank() && !saving, modifier = Modifier.fillMaxWidth().padding(horizontal = Space.page))
        if (signIn != null) {
            GhostButton(signInLabel ?: "Sign in instead", onClick = {
                key = ""
                signIn()
            }, modifier = Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.xs))
        }
        Spacer(Modifier.height(Space.sm))
    }
}

/**
 * A custom OpenAI-compatible endpoint: base URL, model id, optional key and name. The provider and model go to Pi's
 * models.json (merged, see [ModelsFile]); the key goes to auth.json like any other provider's. Without a key, both
 * get a placeholder, so Pi lists the model and Home sees a connected model.
 */
@Composable
private fun CustomStep(auth: ProviderAuth, onBack: () -> Unit, onSaved: (ProviderInfo) -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val haptics = Pi.haptics
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val agentDir = (LocalContext.current.applicationContext as PiBoltApp).engine.agentDir
    var url by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    // Plain remember, never rememberSaveable: the key must not end up in the saved instance state.
    var key by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(false) }
    var urlError by remember { mutableStateOf<String?>(null) }
    var modelError by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun save() {
        urlError = ModelsFile.urlError(url)
        val modelId = model.trim()
        modelError = when {
            modelId.isEmpty() -> "Enter the model id the server expects"
            modelId.any { it.isWhitespace() } -> "A model id has no spaces"
            else -> null
        }
        val cleanKey = if (key.isBlank()) null else ProviderCatalog.cleanKey(key)
        error = if (key.isNotBlank() && cleanKey == null) "That doesn't look like an API key" else null
        if (urlError != null || modelError != null || error != null) {
            haptics.reject()
            return
        }
        val base = ModelsFile.cleanUrl(url)
        val endpoint = CustomEndpoint(
            id = ModelsFile.providerId(base, name.takeIf { it.isNotBlank() }, ProviderCatalog.all.map { it.id }.toSet()),
            baseUrl = base,
            modelId = modelId,
            displayName = name.trim().takeIf { it.isNotEmpty() },
        )
        saving = true
        scope.launch {
            val result = runCatching {
                // Another model for a server that already has its key: an empty key field keeps that key.
                val keepKey = cleanKey == null && endpoint.id in auth.configured.value && ModelsFile.hasSavedKey(ModelsFile.read(agentDir), endpoint.id)
                ModelsFile.update(agentDir) { ModelsFile.merge(it, endpoint, keyless = cleanKey == null && !keepKey) }
                if (!keepKey) withContext(Dispatchers.IO) { auth.setApiKey(endpoint.id, cleanKey ?: ModelsFile.NO_KEY) }
                ProviderCatalog.registerCustom(ModelsFile.custom(agentDir))
            }
            key = ""
            saving = false
            result.onSuccess {
                haptics.confirm()
                val display = endpoint.displayName ?: ProviderCatalog.hostOf(base) ?: endpoint.id
                onSaved(ProviderInfo(endpoint.id, display, "Custom endpoint · $modelId", keyUrl = null))
            }.onFailure {
                error = (it as? ModelsFile.DamagedFile)?.message ?: "Could not save the endpoint"
                haptics.reject()
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        SheetHeader(
            "Custom endpoint",
            subtitle = "Any OpenAI-compatible server.",
            onBack = {
                key = ""
                onBack()
            },
            backLabel = "All providers",
        )
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
            item(key = "fields", contentType = "fields") {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    FieldLabel("Base URL")
                    PiTextField(
                        value = url,
                        onValueChange = {
                            url = it
                            urlError = null
                        },
                        placeholder = "http://192.168.1.20:11434/v1",
                        singleLine = true,
                        style = type.mono,
                        isError = urlError != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Base URL" },
                    )
                    FieldHint(urlError ?: "Usually ends in /v1", isError = urlError != null)
                    FieldLabel("Model id")
                    PiTextField(
                        value = model,
                        onValueChange = {
                            model = it
                            modelError = null
                        },
                        placeholder = "qwen2.5-coder:7b",
                        singleLine = true,
                        style = type.mono,
                        isError = modelError != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Model id" },
                    )
                    FieldHint(modelError ?: "As the server names it", isError = modelError != null)
                    FieldLabel("API key (optional)")
                }
                SecretField(
                    value = key,
                    onValueChange = {
                        key = it
                        error = null
                    },
                    placeholder = "Leave empty if the server needs none",
                    label = "API key",
                    shown = shown,
                    isError = error != null,
                    onToggleShown = { shown = !shown },
                    onPaste = {
                        scope.launch {
                            val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                            if (text != null) {
                                key = text.trim()
                                error = null
                            }
                        }
                    },
                    imeAction = ImeAction.Next,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Column(Modifier.padding(horizontal = 16.dp)) {
                    FieldHint(error ?: "The key stays on this phone and is sent only to this server.", isError = error != null)
                    FieldLabel("Name (optional)")
                    PiTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = "Home Ollama",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Name" },
                    )
                }
            }
        }
        Spacer(Modifier.height(Space.lg))
        PrimaryButton(if (saving) "Saving…" else "Add endpoint", onClick = ::save, enabled = url.isNotBlank() && model.isNotBlank() && !saving, modifier = Modifier.fillMaxWidth().padding(horizontal = Space.page))
        Spacer(Modifier.height(Space.sm))
    }
}
