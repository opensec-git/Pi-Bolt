package opensec.pibolt.app.ui.session

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import opensec.pibolt.app.ui.components.CopyButton
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.PiTextField
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Tnum
import opensec.pibolt.protocol.rpc.ModelInfo
import opensec.pibolt.protocol.session.DialogKind
import opensec.pibolt.protocol.session.DialogRequest
import opensec.pibolt.protocol.session.SessionReducer

/** A 52dp option row: line outline, accent outline and check when selected. */
@Composable
fun OptionRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null) {
    val colors = Pi.colors
    val shape = RoundedCornerShape(Radius.md)
    val border by animateColorAsState(if (selected) colors.accent else colors.line, PiMotion.effects(), label = "option")
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Pi.dims.optionRow)
            .pressable(onClick, shape = shape)
            .background(if (selected) colors.selected else colors.surface2, shape)
            .border(if (selected) 1.5.dp else 1.dp, border, shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Pi.type.body, color = colors.ink1, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = Pi.type.meta, color = colors.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = colors.accent, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun SheetLoading(label: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        QuadSpinner(QuadMode.Think, size = 20.dp, color = Pi.colors.ink2)
        Spacer(Modifier.width(12.dp))
        Text(label, style = Pi.type.body, color = Pi.colors.ink3)
    }
}

// Model

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(models: List<ModelInfo>, current: ModelInfo?, onPick: (ModelInfo) -> Unit, onDismiss: () -> Unit, initialQuery: String = "") {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    val colors = Pi.colors
    val haptics = Pi.haptics
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader("Model")
        if (models.isEmpty()) {
            SheetLoading("Loading models")
            return@PiSheet
        }
        val isCurrent = { m: ModelInfo -> current?.id == m.id && current.provider == m.provider }
        val ordered = remember(models, current) {
            models.sortedWith(compareBy<ModelInfo>({ !isCurrent(it) }, { it.provider.lowercase() }, { it.label.lowercase() }))
        }
        val shown = remember(ordered, query) {
            val q = query.trim().lowercase()
            if (q.isEmpty()) ordered else ordered.filter { q in it.label.lowercase() || q in it.id.lowercase() || q in it.provider.lowercase() }
        }
        if (models.size > 6 || query.isNotEmpty()) {
            PiTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search ${models.size} models",
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                leading = { Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(20.dp)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            // By provider, each under its name; the current model first under "Current".
            val sections = ModelGroups.of(shown, current)
            for ((title, group) in sections) {
                if (sections.size > 1) {
                    item(key = "h:$title", contentType = "header") {
                        Text(title, style = Pi.type.metaStrong, color = colors.ink3, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp).semantics { heading() })
                    }
                }
            items(group, key = { "${it.provider}/${it.id}" }) { model ->
                val selected = isCurrent(model)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .pressable({
                            haptics.tick()
                            close { onPick(model) }
                        }, shape = RoundedCornerShape(0.dp), pressedScale = 1f)
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(24.dp).background(colors.surface3, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                        Text(model.provider.take(1).uppercase().ifBlank { "?" }, style = Pi.type.micro, color = colors.ink2)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(model.label, style = Pi.type.body, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                model.provider.ifBlank { null },
                                model.contextWindow?.let { "${SessionReducer.formatTokens(it)} context" },
                                "reasoning".takeIf { model.reasoning },
                            ).joinToString(" · "),
                            style = Pi.type.meta,
                            color = colors.ink3,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (selected) Icon(Icons.Rounded.Check, contentDescription = "Current", tint = colors.accent, modifier = Modifier.size(20.dp))
                }
            }
            }
            if (shown.isEmpty()) {
                item { Text("No model matches “$query”.", style = Pi.type.body, color = colors.ink3, modifier = Modifier.padding(20.dp)) }
            }
        }
    }
}

/** Models grouped for the picker: "Current" first, then each provider (sorted), keeping the order within a group. */
object ModelGroups {
    fun of(models: List<ModelInfo>, current: ModelInfo?): List<Pair<String, List<ModelInfo>>> {
        val isCurrent = { m: ModelInfo -> current != null && m.id == current.id && m.provider == current.provider }
        val out = ArrayList<Pair<String, List<ModelInfo>>>()
        models.filter(isCurrent).takeIf { it.isNotEmpty() }?.let { out += "Current" to it }
        models.filterNot(isCurrent).groupBy { it.provider.ifBlank { "Other" } }.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (provider, list) -> out += provider to list }
        return out
    }
}

// Thinking level

private fun levelDescription(level: String): String? = when (level.lowercase()) {
    "off" -> "No reasoning. Fastest answers."
    "minimal" -> "A few thoughts before answering."
    "low" -> "Light reasoning for simple tasks."
    "medium" -> "Balanced depth and speed."
    "high" -> "Deep reasoning for hard problems."
    "xhigh", "max" -> "The most reasoning the model allows. Slowest."
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThinkingSheet(levels: List<String>, current: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    val haptics = Pi.haptics
    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader("Thinking level")
        if (levels.isEmpty()) {
            SheetLoading("Loading levels")
            return@PiSheet
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (level in levels) {
                OptionRow(level.replaceFirstChar { it.uppercase() }, levelDescription(level), level == current, onClick = {
                    haptics.tick()
                    close { onPick(level) }
                })
            }
        }
    }
}

// Pi's dialogs

/**
 * A dialog from Pi (select, confirm, input, editor), also open in the terminal; the first answer wins. Swiping the
 * sheet away or tapping the scrim only hides it ([onHide]); it stays pending until answered or cancelled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogSheet(
    dialog: DialogRequest,
    shownAt: Long,
    onValue: (String) -> Unit,
    onConfirm: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onHide: () -> Unit,
    cwd: String? = null,
) {
    val colors = Pi.colors
    val type = Pi.type
    val haptics = Pi.haptics
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    LaunchedEffect(dialog.id) { haptics.longPress() }
    PiSheet(onDismiss = onHide, state = state) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                QuadSpinner(QuadMode.Wait, size = 16.dp, color = colors.warning)
                Spacer(Modifier.width(10.dp))
                Text("Pi is asking", style = type.metaStrong, color = colors.warning, modifier = Modifier.weight(1f))
                dialog.timeoutMs?.let { Countdown(shownAt, it) }
            }
            Text(dialog.title.ifBlank { "Answer needed" }, style = type.title, color = colors.ink1)
            dialog.message?.takeIf { it.isNotBlank() }?.let { message ->
                Spacer(Modifier.height(10.dp))
                if (looksLikeCommand(message)) {
                    CommandWell(message, cwd)
                } else {
                    Text(message, style = type.body, color = colors.ink2, modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()))
                }
            }
            Spacer(Modifier.height(16.dp))
            when (dialog.kind) {
                DialogKind.Select -> {
                    var picked by remember(dialog.id) { mutableStateOf<String?>(null) }
                    LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(dialog.options) { option ->
                            OptionRow(option, null, picked == option, onClick = {
                                haptics.tick()
                                picked = option
                            })
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        SecondaryButton("Cancel", onClick = {
                            haptics.reject()
                            close(onCancel)
                        }, modifier = Modifier.weight(1f))
                        PrimaryButton("Choose", onClick = {
                            haptics.confirm()
                            val value = picked ?: return@PrimaryButton
                            close { onValue(value) }
                        }, enabled = picked != null, modifier = Modifier.weight(1f))
                    }
                }
                DialogKind.Confirm -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryButton("No", onClick = {
                        haptics.reject()
                        close { onConfirm(false) }
                    }, modifier = Modifier.weight(1f))
                    PrimaryButton("Yes", onClick = {
                        haptics.confirm()
                        close { onConfirm(true) }
                    }, modifier = Modifier.weight(1f))
                }
                DialogKind.Input, DialogKind.Editor -> {
                    val editor = dialog.kind == DialogKind.Editor
                    var text by remember(dialog.id) { mutableStateOf(dialog.prefill.orEmpty()) }
                    PiTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = dialog.placeholder.orEmpty(),
                        singleLine = !editor,
                        minLines = if (editor) 6 else 1,
                        maxLines = if (editor) 14 else 1,
                        style = if (editor) type.monoSmall else type.body,
                        keyboardOptions = KeyboardOptions(imeAction = if (editor) ImeAction.Default else ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SecondaryButton("Cancel", onClick = {
                            haptics.reject()
                            close(onCancel)
                        }, modifier = Modifier.weight(1f))
                        PrimaryButton("Submit", onClick = {
                            haptics.confirm()
                            close { onValue(text) }
                        }, modifier = Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Countdown(shownAt: Long, timeoutMs: Long) {
    val colors = Pi.colors
    val left by produceState(timeoutMs, shownAt, timeoutMs) {
        while (true) {
            value = (timeoutMs - (SystemClock.uptimeMillis() - shownAt)).coerceAtLeast(0)
            if (value == 0L) break
            delay(250)
        }
    }
    Text(
        "${(left + 999) / 1000}s",
        style = Pi.type.micro.copy(fontFeatureSettings = Tnum),
        color = colors.warning,
        modifier = Modifier.background(colors.warningSoft, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

// Text prompt (rename, compact)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextPromptSheet(title: String, message: String?, initial: String, placeholder: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    var value by remember { mutableStateOf(initial) }
    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader(title, subtitle = message)
        PiTextField(
            value = value,
            onValueChange = { value = it },
            placeholder = placeholder,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("Cancel", onClick = { close(onDismiss) }, modifier = Modifier.weight(1f))
            PrimaryButton(confirm, onClick = { close { onConfirm(value) } }, modifier = Modifier.weight(1f))
        }
    }
}

// Long-press actions

data class SheetAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionsSheet(title: String?, actions: List<SheetAction>, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    val colors = Pi.colors
    PiSheet(onDismiss = onDismiss, state = state) {
        if (title != null) {
            Text(title, style = Pi.type.meta, color = colors.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        for (action in actions) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .pressable({
                        close {
                            action.onClick()
                            onDismiss()
                        }
                    }, shape = RoundedCornerShape(0.dp), pressedScale = 1f)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(action.icon, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(16.dp))
                Text(action.label, style = Pi.type.body, color = colors.ink1)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * A command Pi asks about, in a well (surface1 inside the sheet's surface2, radius md, 1dp line): the working folder
 * in mono meta above it with Copy at the end, then the command with a `$` prompt in success (paths get no prompt).
 */
@Composable
private fun CommandWell(message: String, cwd: String?) {
    val colors = Pi.colors
    val type = Pi.type
    val shape = RoundedCornerShape(Radius.md)
    val text = message.trim()
    val (prompt, command) = when {
        text.startsWith("$ ") -> true to text.removePrefix("$ ")
        text.startsWith("/") || text.startsWith("~/") || text.startsWith("./") || text.startsWith("`") -> false to text
        else -> true to text
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface1, shape)
            .border(1.dp, colors.line, shape),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                cwd?.takeIf { it.isNotBlank() }?.let(::shortPath).orEmpty(),
                style = type.monoMeta,
                color = colors.ink3,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.weight(1f),
            )
            CopyButton(text = { command }, label = false, clipLabel = "Command", description = "Copy command")
        }
        Text(
            buildAnnotatedString {
                if (prompt) {
                    withStyle(SpanStyle(color = colors.success, fontWeight = FontWeight.SemiBold)) { append("$ ") }
                }
                append(command)
            },
            style = type.monoSmall,
            color = colors.ink1,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        )
    }
}
