package opensec.pibolt.app.ui.providers

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import opensec.pibolt.app.engine.ProviderAuth
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.PiTextField
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius

/**
 * Add a provider's API key: pick the provider (search), paste the key (obscured), save to Pi's auth.json through
 * [auth]. The key lives only in this sheet's memory (never in saved state), is cleared after saving and never logged.
 * [firstRun] shows the welcome wording and a "Later" action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSheet(auth: ProviderAuth, configured: Set<String>, firstRun: Boolean, onSaved: (ProviderInfo) -> Unit, onDismiss: () -> Unit, initial: ProviderInfo? = null) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    var picked by remember { mutableStateOf(initial) }
    val reduced = Pi.reduceMotion
    PiSheet(onDismiss = onDismiss, state = state) {
        AnimatedContent(
            targetState = picked,
            transitionSpec = {
                val forward = targetState != null
                if (reduced) {
                    fadeIn(PiMotion.enter(PiMotion.QUICK)) togetherWith fadeOut(PiMotion.leave(PiMotion.QUICK))
                } else {
                    (slideInHorizontally(PiMotion.enter(PiMotion.STANDARD)) { if (forward) it / 6 else -it / 6 } + fadeIn(PiMotion.enter(PiMotion.STANDARD))) togetherWith
                        fadeOut(PiMotion.leave(PiMotion.QUICK)) + slideOutHorizontally(PiMotion.leave(PiMotion.STANDARD)) { if (forward) -it / 8 else it / 8 }
                }
            },
            contentKey = { it?.id },
            label = "provider-step",
        ) { provider ->
            if (provider == null) {
                PickStep(configured, firstRun, onPick = { picked = it }, onLater = { close(onDismiss) })
            } else {
                KeyStep(
                    provider = provider,
                    auth = auth,
                    onBack = if (initial == null) ({ picked = null }) else null,
                    onSaved = { close { onSaved(provider) } },
                    onCancel = { close(onDismiss) },
                )
            }
        }
    }
}

@Composable
private fun PickStep(configured: Set<String>, firstRun: Boolean, onPick: (ProviderInfo) -> Unit, onLater: () -> Unit) {
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
            items(shown, key = { it.id }, contentType = { "provider" }) { p ->
                ProviderRow(p, added = p.id in configured, onClick = { onPick(p) })
            }
            if (shown.isEmpty()) {
                item(key = "none", contentType = "none") {
                    Text("No provider matches “$query”. Others can be set up with /login in a session.", style = type.body, color = colors.ink3, modifier = Modifier.padding(20.dp))
                }
            }
        }
        if (firstRun) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                TextAction("Later", onClick = onLater, color = colors.ink2)
            }
        }
    }
}

/** 56dp row: provider initial, name with a note beneath, "Added" for configured ones. */
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
        ProviderBadge(p.name)
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

/** 28dp square with the provider's initial (the same look as the model picker's provider mark). */
@Composable
fun ProviderBadge(name: String) {
    Box(Modifier.size(28.dp).background(Pi.colors.surface3, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase().ifBlank { "?" }, style = Pi.type.label, color = Pi.colors.ink2)
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
            ProviderBadge(provider.name)
            Spacer(Modifier.width(12.dp))
            Text(provider.name, style = type.title, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            "Paste your ${provider.name} API key. It is saved in Pi's auth.json on this phone and sent only to ${provider.name}.",
            style = type.body,
            color = colors.ink2,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
        KeyField(
            value = key,
            onValueChange = {
                key = it
                error = null
            },
            hint = provider.keyHint.ifBlank { "API key" },
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
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Text(error ?: "Stored on this device only", style = type.meta, color = if (error != null) colors.danger else colors.ink3)
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("Cancel", onClick = {
                key = ""
                onCancel()
            }, modifier = Modifier.weight(1f))
            PrimaryButton(if (saving) "Saving…" else "Save key", onClick = ::save, enabled = key.isNotBlank() && !saving, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** The key field: obscured by default, Paste and Show/Hide at the end, no autocorrect or suggestions. */
@Composable
private fun KeyField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    shown: Boolean,
    isError: Boolean,
    onToggleShown: () -> Unit,
    onPaste: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Pi.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border by animateColorAsState(
        when {
            isError -> colors.danger.copy(alpha = 0.7f)
            focused -> colors.lineFocus
            else -> colors.lineStrong
        },
        PiMotion.effects(),
        label = "key-field",
    )
    val shape = RoundedCornerShape(Radius.md)
    val style = Pi.type.mono
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = style.copy(color = colors.ink1),
        cursorBrush = SolidColor(colors.accent),
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        interactionSource = interaction,
        modifier = modifier.fillMaxWidth().semantics {
            password()
            contentDescription = "API key"
        },
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .background(colors.surface1, shape)
                    .border(1.dp, border, shape)
                    .padding(start = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    if (value.isEmpty()) Text(hint, style = style, color = colors.ink3, maxLines = 1)
                    inner()
                }
                if (value.isEmpty()) {
                    TextAction("Paste", onClick = onPaste, color = colors.accent)
                } else {
                    IconAction(if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (shown) "Hide key" else "Show key", onClick = onToggleShown, size = 20.dp)
                }
            }
        },
    )
}
