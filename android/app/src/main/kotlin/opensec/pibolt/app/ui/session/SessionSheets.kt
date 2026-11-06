package opensec.pibolt.app.ui.session

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.rounded.ChevronRight
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale
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
fun ModelSheet(models: List<ModelInfo>, current: ModelInfo?, onPick: (ModelInfo) -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    val colors = Pi.colors
    val haptics = Pi.haptics
    var query by rememberSaveable { mutableStateOf("") }
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
        if (models.size > 6) {
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
            items(shown, key = { "${it.provider}/${it.id}" }) { model ->
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
            if (shown.isEmpty()) {
                item { Text("No model matches “$query”.", style = Pi.type.body, color = colors.ink3, modifier = Modifier.padding(20.dp)) }
            }
        }
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
                    val shape = RoundedCornerShape(Radius.md)
                    Text(
                        message,
                        style = type.monoSmall,
                        color = colors.ink1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .background(colors.surface1, shape)
                            .border(1.dp, colors.line, shape)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                    )
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

// Session info

data class SessionFacts(
    val model: ModelInfo?,
    val thinkingLevel: String?,
    val totalTokens: Long?,
    val input: Long?,
    val output: Long?,
    val cacheRead: Long?,
    val cost: Double?,
    val cwd: String?,
    val version: String?,
    val sessionName: String?,
    val compacting: Boolean,
    val connected: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionInfoSheet(
    facts: SessionFacts,
    onModel: () -> Unit,
    onThinking: () -> Unit,
    onCompact: () -> Unit,
    onRename: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Pi.colors
    val type = Pi.type
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader("Session", subtitle = facts.sessionName)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val percent = facts.totalTokens?.let { contextPercent(it, facts.model?.contextWindow) }
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.surface1, RoundedCornerShape(Radius.md))
                    .border(1.dp, colors.line, RoundedCornerShape(Radius.md))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ContextRing(percent ?: 0)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            percent != null -> "$percent% of ${SessionReducer.formatTokens(facts.model?.contextWindow ?: 0)} context"
                            facts.totalTokens != null -> "${SessionReducer.formatTokens(facts.totalTokens)} tokens in context"
                            else -> "No usage yet"
                        },
                        style = type.bodyStrong.copy(fontFeatureSettings = Tnum),
                        color = colors.ink1,
                    )
                    val details = listOfNotNull(
                        facts.input?.let { "${SessionReducer.formatTokens(it)} in" },
                        facts.output?.let { "${SessionReducer.formatTokens(it)} out" },
                        facts.cacheRead?.takeIf { it > 0 }?.let { "${SessionReducer.formatTokens(it)} cached" },
                        facts.cost?.takeIf { it > 0 }?.let { String.format(Locale.ROOT, "$%.3f", it) },
                    ).joinToString(" · ")
                    if (details.isNotBlank()) Text("Last turn: $details", style = type.meta.copy(fontFeatureSettings = Tnum), color = colors.ink3)
                }
            }
            InfoRow("Model", facts.model?.label ?: "—", onClick = { close(onModel) }, enabled = facts.connected)
            InfoRow("Thinking", facts.thinkingLevel?.replaceFirstChar { it.uppercase() } ?: "—", onClick = { close(onThinking) }, enabled = facts.connected)
            facts.cwd?.takeIf { it.isNotBlank() }?.let { InfoRow("Folder", shortPath(it), mono = true) }
            facts.version?.takeIf { it.isNotBlank() }?.let { InfoRow("Pi-Bolt", "v$it") }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(if (facts.compacting) "Compacting…" else "Compact context", onClick = { close(onCompact) }, enabled = facts.connected && !facts.compacting, modifier = Modifier.weight(1f))
                SecondaryButton("Rename", onClick = { close(onRename) }, enabled = facts.connected, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, onClick: (() -> Unit)? = null, enabled: Boolean = true, mono: Boolean = false) {
    val colors = Pi.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .then(if (onClick != null) Modifier.pressable(onClick, shape = RoundedCornerShape(Radius.sm), enabled = enabled, pressedScale = 1f) else Modifier)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Pi.type.body, color = colors.ink2, modifier = Modifier.width(96.dp))
        Text(value, style = if (mono) Pi.type.monoSmall else Pi.type.body, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (onClick != null) Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = colors.ink4, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ContextRing(percent: Int) {
    val colors = Pi.colors
    val target = percent / 100f
    val sweep by animateFloatAsState(target, PiMotion.inPlace(PiMotion.SETTLE), label = "ring")
    val color = when {
        percent > 90 -> colors.danger
        percent > 70 -> colors.warning
        else -> colors.ink2
    }
    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(40.dp)) {
            val stroke = 3.dp.toPx()
            val inset = stroke / 2
            drawArc(colors.lineStrong, 0f, 360f, false, topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
            drawArc(color, -90f, 360f * sweep, false, topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Text("$percent%", style = Pi.type.micro.copy(fontFeatureSettings = Tnum, fontWeight = FontWeight.SemiBold, fontSize = 10.sp), color = colors.ink1)
    }
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
