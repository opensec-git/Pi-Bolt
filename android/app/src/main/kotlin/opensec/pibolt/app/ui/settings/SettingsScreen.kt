package opensec.pibolt.app.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.annotation.RawRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.R
import opensec.pibolt.app.data.AccentId
import opensec.pibolt.app.data.AgentDefaults
import opensec.pibolt.app.data.AgentSettingsFile
import opensec.pibolt.app.data.QueueMode
import opensec.pibolt.app.ui.projects.LocalSessions
import androidx.compose.runtime.produceState
import java.io.File
import opensec.pibolt.app.data.AppSettings
import opensec.pibolt.app.data.Density
import opensec.pibolt.app.data.ReduceMotion
import opensec.pibolt.app.data.ThemeId
import opensec.pibolt.app.ui.components.ConfirmSheet
import opensec.pibolt.app.ui.components.DangerButton
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.SectionLabel
import opensec.pibolt.app.ui.components.Segmented
import opensec.pibolt.app.ui.components.SegmentedSlots
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.session.Viewer
import opensec.pibolt.app.ui.session.ViewerContent
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiColors
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.PiType
import opensec.pibolt.app.ui.theme.Radius

/** Preferences, applied live (no activity restart) and saved in settings.json. */
@Composable
fun SettingsScreen(app: PiBoltApp, onBack: () -> Unit) {
    val settings = Pi.settings
    val colors = Pi.colors
    val type = Pi.type
    val context = LocalContext.current
    var licence by remember { mutableStateOf<ViewerContent?>(null) }
    val scope = rememberCoroutineScope()
    fun update(change: (AppSettings) -> AppSettings) = app.settings.update(change)

    Box(Modifier.fillMaxSize().background(colors.canvas)) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 32.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack, tint = colors.ink1)
            }
            Text("Settings", style = type.display.copy(fontSize = type.display.fontSize * 1.15f), color = colors.ink1, modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 12.dp))

            Section("Model providers") {
                ProvidersSection(app)
            }

            Section("Appearance") {
                Label("Theme")
                // Four tiles a row; an incomplete last row keeps the tile width.
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    for (row in ThemeId.entries.chunked(THEME_TILES_PER_ROW)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (theme in row) {
                                ThemeTile(theme, settings.accent, selected = settings.theme == theme, onClick = { update { it.copy(theme = theme) } }, modifier = Modifier.weight(1f))
                            }
                            repeat(THEME_TILES_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
                Label("Accent")
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (accent in AccentId.entries) {
                        AccentDot(accent, settings.theme, selected = settings.accent == accent, onClick = { update { it.copy(accent = accent) } })
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("Text size", Modifier.weight(1f))
                    Text(textSizeLabel(settings.textSize), style = type.meta, color = colors.ink3, modifier = Modifier.padding(bottom = 10.dp))
                }
                // Growing "A" glyphs, one per step: reads as a text size control at a glance.
                val steps = (AppSettings.TEXT_SIZE_MIN..AppSettings.TEXT_SIZE_MAX).toList()
                SegmentedSlots(
                    options = steps,
                    selected = settings.textSize,
                    onSelect = { step -> update { it.copy(textSize = step) } },
                    labels = steps.map(::textSizeLabel),
                    height = 48.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) { i, on ->
                    Text(
                        "A",
                        style = type.label.copy(fontSize = 13.sp * (1f + 0.2f * i), fontWeight = FontWeight.SemiBold),
                        color = if (on) colors.ink1 else colors.ink3,
                    )
                }
                Spacer(Modifier.height(12.dp))
                val preview = type.prose.let { p -> p.copy(fontSize = p.fontSize * PiType.factorFor(settings.textSize), lineHeight = p.lineHeight * PiType.factorFor(settings.textSize)) }
                Text(
                    "Pi reads the failing test, fixes the parser and runs the suite again.",
                    style = preview,
                    color = colors.ink1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.surface1, RoundedCornerShape(Radius.md))
                        .border(1.dp, colors.line, RoundedCornerShape(Radius.md))
                        .padding(16.dp),
                )
                Spacer(Modifier.height(20.dp))
                Label("Density")
                Segmented(
                    options = Density.entries.map { it to it.label },
                    selected = settings.density,
                    onSelect = { d -> update { it.copy(density = d) } },
                    modifier = Modifier.fillMaxWidth(),
                    height = 40.dp,
                )
            }

            Section("Motion and feedback") {
                Label("Reduce motion")
                Segmented(
                    options = ReduceMotion.entries.map { it to it.label },
                    selected = settings.reduceMotion,
                    onSelect = { r -> update { it.copy(reduceMotion = r) } },
                    modifier = Modifier.fillMaxWidth(),
                    height = 40.dp,
                )
                Text(
                    "Follow system uses Android's “Remove animations” setting. Reduced motion keeps spinners still and turns transitions into short fades.",
                    style = type.meta,
                    color = colors.ink3,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                )
                ToggleRow("Haptics", "Taps for send, stop, answers and errors", settings.haptics) { on -> update { it.copy(haptics = on) } }
            }

            Section("Session") {
                ToggleRow("Show thinking expanded", "Open the model's reasoning by default", settings.thinkingExpanded) { on -> update { it.copy(thinkingExpanded = on) } }
                Spacer(Modifier.height(12.dp))
                Label("Tool output preview")
                Segmented(
                    options = AppSettings.TOOL_PREVIEW_CHOICES.map { it to "$it lines" },
                    selected = settings.toolPreviewLines,
                    onSelect = { n -> update { it.copy(toolPreviewLines = n) } },
                    modifier = Modifier.fillMaxWidth(),
                    height = 40.dp,
                )
            }

            Section("Pi on this phone") {
                AgentSection(app)
            }

            Section("Notifications") {
                var allowed by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = NotificationManagerCompat.from(context).areNotificationsEnabled() }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (allowed) "Allowed" else "Off", style = type.bodyStrong, color = colors.ink1)
                        Text("Pi finished, or needs an answer, while the app is in the background", style = type.meta, color = colors.ink3)
                    }
                }
                Spacer(Modifier.height(12.dp))
                SecondaryButton("Open system settings", onClick = { openNotificationSettings(context) }, modifier = Modifier.fillMaxWidth())
            }

            Section("Security") {
                var keyId by remember { mutableStateOf(app.deviceKeyId()) }
                var oldKeyId by remember { mutableStateOf<String?>(null) }
                var confirmReset by remember { mutableStateOf(false) }
                // Key ids are shown in mono only: in the UI font "l" and "I" look alike.
                KeyLine("Device key", keyId, colors.ink1)
                oldKeyId?.let { KeyLine("Old key", it, colors.ink3) }
                Text(
                    if (oldKeyId != null) {
                        "New key created. Pair again with /remote-control on each computer, then revoke the old key there with /remote-control devices."
                    } else {
                        "Computers list this phone by this key in /remote-control devices. Resetting makes a new key: every computer has to pair again."
                    },
                    style = type.meta,
                    color = if (oldKeyId != null) colors.success else colors.ink3,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                DangerButton("Reset device key", onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth())
                if (confirmReset) {
                    ConfirmSheet(
                        title = "Reset this phone's key?",
                        message = "Every paired computer will refuse this phone, so they are removed here and the current session ends. " +
                            "Pair each computer again with a new QR code, then revoke the old key on it with /remote-control devices. " +
                            "Settings keeps showing the old key until then.",
                        confirmLabel = "Reset key",
                        onConfirm = {
                            confirmReset = false
                            oldKeyId = keyId
                            keyId = app.resetDeviceKey()
                        },
                        onDismiss = { confirmReset = false },
                    )
                }
            }

            Section("About") {
                InfoLine("Version", app.appVersion)
                InfoLine("This phone", app.deviceName)
                // Licence texts are read off the main thread.
                NavLine("Inter licence (OFL 1.1)") { scope.launch { licence = ViewerContent("Inter", "SIL Open Font License 1.1", readRaw(context, R.raw.license_inter), wrap = true) } }
                NavLine("JetBrains Mono licence (OFL 1.1)") { scope.launch { licence = ViewerContent("JetBrains Mono", "SIL Open Font License 1.1", readRaw(context, R.raw.license_jetbrains_mono), wrap = true) } }
            }
        }

        AnimatedVisibility(
            visible = licence != null,
            enter = fadeIn(PiMotion.enter(PiMotion.SHEET)) + scaleIn(PiMotion.enter(PiMotion.SHEET), initialScale = 0.96f),
            exit = fadeOut(PiMotion.leave(PiMotion.SHEET)) + scaleOut(PiMotion.leave(PiMotion.SHEET), targetScale = 0.96f),
        ) {
            val shown = remember { mutableStateOf(licence) }
            licence?.let { shown.value = it }
            shown.value?.let { Viewer(it, onClose = { licence = null }) }
        }
    }
}

private const val THEME_TILES_PER_ROW = 4

private fun textSizeLabel(step: Int) = when (step) {
    -1 -> "Small"
    0 -> "Default"
    1 -> "Large"
    else -> "Larger"
}

private suspend fun readRaw(context: Context, @RawRes id: Int): String = withContext(Dispatchers.IO) {
    context.resources.openRawResource(id).bufferedReader().use { it.readText() }
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        SectionLabel(title, Modifier.padding(bottom = 10.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .background(Pi.colors.surface1, RoundedCornerShape(Radius.lg))
                .border(1.dp, Pi.colors.line, RoundedCornerShape(Radius.lg))
                .padding(16.dp),
            content = content,
        )
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Pi.type.bodyStrong, color = Pi.colors.ink1, modifier = modifier.padding(bottom = 10.dp))
}

/** A theme swatch drawn with that theme's own canvas, surface and ink. */
@Composable
private fun ThemeTile(theme: ThemeId, accent: AccentId, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val current = Pi.colors
    val c = remember(theme, accent) { PiColors.of(theme, accent) }
    val ring by animateColorAsState(if (selected) current.accent else current.line, PiMotion.effects(), label = "tile")
    val shape = RoundedCornerShape(Radius.md)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.78f)
                .pressable(onClick, shape = shape, role = Role.RadioButton, label = theme.label)
                .border(if (selected) 1.5.dp else 1.dp, ring, shape)
                .padding(if (selected) 3.dp else 0.dp),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = (Radius.md - if (selected) 3.dp else 0.dp).toPx().coerceAtLeast(4f)
                drawRoundRect(c.canvas, cornerRadius = CornerRadius(r))
                val pad = size.width * 0.14f
                // A user bubble, two lines of prose and a composer.
                drawRoundRect(c.surface2, Offset(size.width * 0.42f, pad), Size(size.width - size.width * 0.42f - pad, size.height * 0.13f), CornerRadius(6.dp.toPx()))
                val lineH = size.height * 0.045f
                drawRoundRect(c.ink1.copy(alpha = 0.85f), Offset(pad, size.height * 0.36f), Size(size.width * 0.62f, lineH), CornerRadius(lineH / 2))
                drawRoundRect(c.ink2.copy(alpha = 0.8f), Offset(pad, size.height * 0.36f + lineH * 2f), Size(size.width * 0.48f, lineH), CornerRadius(lineH / 2))
                drawRoundRect(c.ink3.copy(alpha = 0.8f), Offset(pad, size.height * 0.36f + lineH * 4f), Size(size.width * 0.34f, lineH), CornerRadius(lineH / 2))
                val composerTop = size.height * 0.74f
                drawRoundRect(c.surface1, Offset(pad * 0.7f, composerTop), Size(size.width - pad * 1.4f, size.height * 0.17f), CornerRadius(8.dp.toPx()))
                drawRoundRect(c.lineStrong, Offset(pad * 0.7f, composerTop), Size(size.width - pad * 1.4f, size.height * 0.17f), CornerRadius(8.dp.toPx()), style = Stroke(1.dp.toPx()))
                val dot = size.height * 0.085f
                drawCircle(c.accent, dot / 2, Offset(size.width - pad * 0.7f - dot * 0.9f, composerTop + size.height * 0.085f))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(theme.label, style = Pi.type.meta, color = if (selected) current.ink1 else current.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AccentDot(accent: AccentId, theme: ThemeId, selected: Boolean, onClick: () -> Unit) {
    val current = Pi.colors
    val c = remember(theme, accent) { PiColors.of(theme, accent) }
    val ring by animateColorAsState(if (selected) c.accent else current.canvas.copy(alpha = 0f), PiMotion.effects(), label = "accent")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(48.dp)
                .pressable(onClick, shape = CircleShape, pressedScale = 0.92f, role = Role.RadioButton, label = accent.label)
                .border(1.5.dp, ring, CircleShape)
                .padding(5.dp)
                .background(c.accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val shown by animateFloatAsState(if (selected) 1f else 0f, PiMotion.inPlace(PiMotion.QUICK), label = "check")
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = c.onAccent,
                modifier = Modifier.size(18.dp).graphicsLayer {
                    alpha = shown
                    scaleX = 0.6f + 0.4f * shown
                    scaleY = 0.6f + 0.4f * shown
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(accent.label, style = Pi.type.meta, color = if (selected) current.ink1 else current.ink3)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = Pi.colors
    val haptics = Pi.haptics
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .pressable({
                haptics.toggle(!checked)
                onChange(!checked)
            }, shape = RoundedCornerShape(Radius.sm), pressedScale = 1f, role = Role.Switch),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = Pi.type.bodyStrong, color = colors.ink1)
            Text(subtitle, style = Pi.type.meta, color = colors.ink3)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onAccent,
                checkedTrackColor = colors.accent,
                checkedBorderColor = colors.accent,
                uncheckedThumbColor = colors.ink3,
                uncheckedTrackColor = colors.surface3,
                uncheckedBorderColor = colors.lineStrong,
            ),
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String, onClick: (() -> Unit)? = null) {
    val tap = if (onClick != null) Modifier.pressable(onClick, shape = RoundedCornerShape(Radius.sm), pressedScale = 1f, role = null) else Modifier
    Row(Modifier.fillMaxWidth().height(44.dp).then(tap), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Pi.type.body, color = Pi.colors.ink2, modifier = Modifier.width(110.dp))
        Text(value, style = Pi.type.body, color = Pi.colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun KeyLine(label: String, id: String, color: Color) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Pi.type.body, color = Pi.colors.ink2, modifier = Modifier.width(110.dp))
        Text("[$id]", style = Pi.type.mono, color = color)
    }
}

@Composable
private fun NavLine(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .pressable(onClick, shape = RoundedCornerShape(Radius.sm), pressedScale = 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Pi.type.body, color = Pi.colors.ink1, modifier = Modifier.weight(1f))
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Pi.colors.ink4, modifier = Modifier.size(20.dp))
    }
}

/**
 * The on-device engine's settings (its agent/settings.json, as in the terminal's /settings): queued message delivery,
 * automatic compaction and retry, and the thinking level new sessions start with. A change also reaches the open
 * on-device session at once.
 */
@Composable
private fun AgentSection(app: PiBoltApp) {
    val colors = Pi.colors
    val type = Pi.type
    val scope = rememberCoroutineScope()
    val file = remember { AgentSettingsFile(File(app.engine.agentDir, "settings.json")) }
    val loaded by produceState<AgentDefaults?>(null) { value = file.read() }
    var defaults by remember { mutableStateOf<AgentDefaults?>(null) }
    val current = defaults ?: loaded
    if (current == null) {
        Text("Reading settings…", style = type.meta, color = colors.ink3)
        return
    }
    fun update(change: (AgentDefaults) -> AgentDefaults) {
        val next = change(current)
        defaults = next
        scope.launch {
            runCatching { file.write(next) }
            LocalSessions.current.value?.controller?.takeIf { !it.stopped }?.applyAgentDefaults(next)
        }
    }
    Label("Steering messages")
    Segmented(
        options = QueueMode.entries.map { it to it.label },
        selected = current.steeringMode,
        onSelect = { m -> update { it.copy(steeringMode = m) } },
        modifier = Modifier.fillMaxWidth(),
        height = 40.dp,
    )
    Text("Sent while Pi works; delivered after its current step.", style = type.meta, color = colors.ink3, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
    Label("Follow-up messages")
    Segmented(
        options = QueueMode.entries.map { it to it.label },
        selected = current.followUpMode,
        onSelect = { m -> update { it.copy(followUpMode = m) } },
        modifier = Modifier.fillMaxWidth(),
        height = 40.dp,
    )
    Text("Queued until Pi finishes, then sent.", style = type.meta, color = colors.ink3, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
    Label("Thinking level for new sessions")
    Segmented(
        options = AgentDefaults.THINKING_LEVELS.map { it to thinkingShort(it) },
        selected = current.defaultThinking.takeIf { it in AgentDefaults.THINKING_LEVELS } ?: "medium",
        onSelect = { level -> update { it.copy(defaultThinking = level) } },
        modifier = Modifier.fillMaxWidth(),
        height = 40.dp,
    )
    Spacer(Modifier.height(8.dp))
    ToggleRow("Auto-compact", "Summarise older messages when the context is nearly full", current.autoCompaction) { on -> update { it.copy(autoCompaction = on) } }
    ToggleRow("Auto-retry", "Retry when the provider is overloaded or rate-limited", current.autoRetry) { on -> update { it.copy(autoRetry = on) } }
    InfoLine("Default model", current.defaultModel ?: "Automatic")
    Text("The model you pick in a session becomes the default, as in the terminal.", style = type.meta, color = colors.ink3)
}

private fun thinkingShort(level: String): String = when (level) {
    "minimal" -> "Min"
    "medium" -> "Med"
    "xhigh" -> "XHigh"
    else -> level.replaceFirstChar { it.uppercase() }
}
