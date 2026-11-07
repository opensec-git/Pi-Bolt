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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dns
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.engine.CustomEndpoint
import opensec.pibolt.app.engine.ModelsFile
import opensec.pibolt.app.engine.ProviderAuth
import opensec.pibolt.app.ui.components.ButtonRow
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.PiTextField
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.SectionLabel
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius

/** Where the provider sheet is: the list, one provider's key, or a custom endpoint. */
private sealed interface Step {
    data class Key(val provider: ProviderInfo) : Step

    data object Custom : Step
}

/**
 * Add a provider's API key: pick the provider (search), paste the key (obscured), save to Pi's auth.json through
 * [auth]. The key lives only in this sheet's memory (never in saved state), is cleared after saving and never logged.
 * The last row adds a custom OpenAI-compatible endpoint to Pi's models.json. [firstRun] shows the welcome wording and
 * a "Later" action. Back on a step returns to the list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSheet(auth: ProviderAuth, configured: Set<String>, firstRun: Boolean, onSaved: (ProviderInfo) -> Unit, onDismiss: () -> Unit, initial: ProviderInfo? = null) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    var step by remember { mutableStateOf<Step?>(initial?.let { Step.Key(it) }) }
    val reduced = Pi.reduceMotion
    PiSheet(onDismiss = onDismiss, state = state) {
        BackHandler(enabled = step != null && initial == null) { step = null }
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
                null -> PickStep(configured, firstRun, onPick = { step = Step.Key(it) }, onCustom = { step = Step.Custom }, onLater = { close(onDismiss) })
                is Step.Key -> KeyStep(
                    provider = current.provider,
                    auth = auth,
                    onBack = if (initial == null) ({ step = null }) else null,
                    onSaved = { close { onSaved(current.provider) } },
                    onCancel = { close(onDismiss) },
                )
                Step.Custom -> CustomStep(
                    auth = auth,
                    onBack = { step = null },
                    onSaved = { info -> close { onSaved(info) } },
                )
            }
        }
    }
}

@Composable
private fun PickStep(configured: Set<String>, firstRun: Boolean, onPick: (ProviderInfo) -> Unit, onCustom: () -> Unit, onLater: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(query) { ProviderCatalog.search(query) }
    Column {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            if (firstRun) {
                Text("Welcome to Pi-Bolt", style = type.metaStrong, color = colors.ink3)
                Spacer(Modifier.height(6.dp))
            }
            Text("Connect a model", style = type.title, color = colors.ink1)
            Spacer(Modifier.height(6.dp))
            Text(
                "Pi-Bolt runs on this phone and calls the model with your API key. The key stays on this device.",
                style = type.body,
                color = colors.ink2,
            )
        }
        PiTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search ${ProviderCatalog.all.size} providers",
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            leading = { Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(20.dp)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            if (query.isBlank()) {
                item(key = "popular", contentType = "label") { SectionLabel("Popular", Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)) }
                items(ProviderCatalog.popular, key = { it.id }, contentType = { "provider" }) { p ->
                    ProviderRow(p, added = p.id in configured, onClick = { onPick(p) })
                }
                item(key = "more", contentType = "label") { SectionLabel("More providers", Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) }
                items(ProviderCatalog.more, key = { it.id }, contentType = { "provider" }) { p ->
                    ProviderRow(p, added = p.id in configured, onClick = { onPick(p) })
                }
            } else {
                items(shown, key = { it.id }, contentType = { "provider" }) { p ->
                    ProviderRow(p, added = p.id in configured, onClick = { onPick(p) })
                }
            }
            item(key = "custom", contentType = "custom") {
                CustomRow(
                    title = if (shown.isEmpty()) "Use a custom endpoint" else "Custom endpoint (OpenAI-compatible)",
                    note = if (shown.isEmpty()) "No provider matches “${query.trim()}”. Connect an OpenAI-compatible server instead." else "Ollama, LM Studio, vLLM or a proxy",
                    onClick = onCustom,
                )
            }
        }
        if (firstRun) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                TextAction("Later", onClick = onLater, color = colors.ink2)
            }
        }
    }
}

/** The list's last row: a custom OpenAI-compatible endpoint. */
@Composable
private fun CustomRow(title: String, note: String, onClick: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .pressable(onClick, shape = RoundedCornerShape(0.dp), pressedScale = 1f, label = title)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).background(colors.surface3, RoundedCornerShape(Radius.sm)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Dns, contentDescription = null, tint = colors.ink1, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = type.body, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(note, style = type.meta, color = colors.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 60dp row: provider badge, name with a note beneath, "Added" for configured ones. */
@Composable
fun ProviderRow(p: ProviderInfo, added: Boolean, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val colors = Pi.colors
    val type = Pi.type
    Row(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .pressable(onClick, shape = RoundedCornerShape(0.dp), pressedScale = 1f)
            .padding(start = 20.dp, end = if (trailing != null) 4.dp else 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderBadge(p.badge)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = type.body, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(p.note, style = type.meta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (added) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.success, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Added", style = type.meta, color = colors.ink2)
        }
        trailing?.invoke()
    }
}

/** 32dp square with the provider's two-letter badge (see [ProviderCatalog.badge]); decorative for TalkBack. */
@Composable
fun ProviderBadge(badge: String) {
    Box(Modifier.size(32.dp).background(Pi.colors.surface3, RoundedCornerShape(Radius.sm)).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Text(badge.ifBlank { "?" }, style = Pi.type.label.copy(fontWeight = FontWeight.SemiBold), color = Pi.colors.ink1, maxLines = 1)
    }
}

@Composable
private fun KeyStep(provider: ProviderInfo, auth: ProviderAuth, onBack: (() -> Unit)?, onSaved: () -> Unit, onCancel: () -> Unit) {
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
        Row(Modifier.fillMaxWidth().padding(start = if (onBack != null) 4.dp else 20.dp, end = 20.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "All providers", onClick = {
                    key = ""
                    onBack()
                }, tint = colors.ink1)
                Spacer(Modifier.width(4.dp))
            }
            ProviderBadge(provider.badge)
            Spacer(Modifier.width(12.dp))
            Text(provider.name, style = type.title, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            "Paste your ${provider.name} API key.",
            style = type.body,
            color = colors.ink2,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
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
            Text(error ?: "Your key stays on this phone and is sent only to ${provider.name}.", style = type.meta, color = if (error != null) colors.danger else colors.ink3)
        }
        provider.keyUrl?.let { url ->
            Row(
                Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .pressable({ runCatching { uri.openUri(url) } }, shape = RoundedCornerShape(Radius.sm), pressedScale = 1f, label = "Get a key")
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Get a key at ", style = type.body, color = colors.ink2)
                Text(url.removePrefix("https://").substringBefore('/'), style = type.body, color = colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        ButtonRow(Modifier.padding(horizontal = 20.dp)) {
            SecondaryButton("Cancel", onClick = {
                key = ""
                onCancel()
            })
            PrimaryButton(if (saving) "Saving…" else "Save key", onClick = ::save, enabled = key.isNotBlank() && !saving)
        }
        Spacer(Modifier.height(8.dp))
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
                ModelsFile.update(agentDir) { ModelsFile.merge(it, endpoint, keyless = cleanKey == null) }
                withContext(Dispatchers.IO) { auth.setApiKey(endpoint.id, cleanKey ?: ModelsFile.NO_KEY) }
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
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 20.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "All providers", onClick = {
                key = ""
                onBack()
            }, tint = colors.ink1)
            Spacer(Modifier.width(4.dp))
            Text("Custom endpoint", style = type.title, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            "Any server that speaks the OpenAI chat completions API: Ollama, LM Studio, vLLM or a proxy.",
            style = type.body,
            color = colors.ink2,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
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
                    FieldHint(error ?: "A key stays on this phone and is sent only to this server.", isError = error != null)
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
        Spacer(Modifier.height(16.dp))
        ButtonRow(Modifier.padding(horizontal = 20.dp)) {
            SecondaryButton("Back", onClick = {
                key = ""
                onBack()
            })
            PrimaryButton(if (saving) "Saving…" else "Add endpoint", onClick = ::save, enabled = url.isNotBlank() && model.isNotBlank() && !saving)
        }
        Spacer(Modifier.height(8.dp))
    }
}
