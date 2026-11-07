package opensec.pibolt.app.ui.settings

import android.content.Context
import androidx.annotation.RawRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.R
import opensec.pibolt.app.data.AccentId
import opensec.pibolt.app.data.AppSettings
import opensec.pibolt.app.data.Density
import opensec.pibolt.app.data.ReduceMotion
import opensec.pibolt.app.ui.components.ConfirmSheet
import opensec.pibolt.app.ui.components.DangerButton
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.PiTextField
import opensec.pibolt.app.ui.components.Segmented
import opensec.pibolt.app.ui.components.SegmentedSlots
import opensec.pibolt.app.ui.session.Viewer
import opensec.pibolt.app.ui.session.ViewerContent
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.PiType
import opensec.pibolt.app.ui.theme.Radius

/**
 * Preferences, applied live (no activity restart) and saved in settings.json. A pinned 56dp bar with the title, a
 * search field that narrows the groups to matching settings ([SettingsIndex]), then the groups in [SettingsGroup] order.
 */
@Composable
fun SettingsScreen(app: PiBoltApp, onBack: () -> Unit) {
    val settings = Pi.settings
    val colors = Pi.colors
    val type = Pi.type
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    var licence by remember { mutableStateOf<ViewerContent?>(null) }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(query) { SettingsIndex.visible(query) }
    val groups = remember(visible) { SettingsIndex.groups(visible).toSet() }
    val show: (String) -> Boolean = { id -> visible == null || id in visible }
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    fun update(change: (AppSettings) -> AppSettings) = app.settings.update(change)

    Box(Modifier.fillMaxSize().background(colors.canvas)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack, tint = colors.ink1)
                Spacer(Modifier.size(4.dp))
                Text("Settings", style = type.title, color = colors.ink1, modifier = Modifier.semantics { heading() })
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(if (scrolled) colors.line else colors.canvas))
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp),
            ) {
                PiTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search settings",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    leading = { Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(20.dp)) },
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                )
                if (query.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (groups.isEmpty()) "No setting matches “${query.trim()}”" else "Showing matches for “${query.trim()}”",
                            style = type.meta,
                            color = colors.ink3,
                            modifier = Modifier.weight(1f),
                        )
                        IconAction(Icons.Rounded.Close, "Clear search", onClick = { query = "" }, size = 20.dp)
                    }
                }

                if (SettingsGroup.Providers in groups) {
                    Section(SettingsGroup.Providers.title) { ProvidersSection(app) }
                }

                if (SettingsGroup.Agent in groups) {
                    Section(SettingsGroup.Agent.title) { AgentSection(app, show) }
                }

                if (SettingsGroup.Appearance in groups) {
                    Section(SettingsGroup.Appearance.title) {
                        if (show("theme")) {
                            Label("Theme")
                            ThemeGrid(settings.theme, settings.accent, onSelect = { theme -> update { it.copy(theme = theme) } })
                            Spacer(Modifier.height(20.dp))
                        }
                        if (show("accent")) {
                            Label("Accent")
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                for (accent in AccentId.entries) {
                                    AccentDot(accent, settings.theme, selected = settings.accent == accent, onClick = { update { it.copy(accent = accent) } })
                                }
                            }
                            Spacer(Modifier.height(20.dp))
                        }
                        if (show("text-size")) {
                            LabelWithValue("Text size", textSizeLabel(settings.textSize))
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
                                    .background(colors.canvas, RoundedCornerShape(Radius.md))
                                    .border(1.dp, colors.line, RoundedCornerShape(Radius.md))
                                    .padding(16.dp),
                            )
                            Spacer(Modifier.height(20.dp))
                        }
                        if (show("density")) {
                            Label("Density")
                            Segmented(
                                options = Density.entries.map { it to it.label },
                                selected = settings.density,
                                onSelect = { d -> update { it.copy(density = d) } },
                                modifier = Modifier.fillMaxWidth(),
                                height = 48.dp,
                            )
                        }
                    }
                }

                if (SettingsGroup.Motion in groups) {
                    Section(SettingsGroup.Motion.title) {
                        if (show("reduce-motion")) {
                            Label("Reduce motion")
                            Segmented(
                                options = ReduceMotion.entries.map { it to it.label },
                                selected = settings.reduceMotion,
                                onSelect = { r -> update { it.copy(reduceMotion = r) } },
                                modifier = Modifier.fillMaxWidth(),
                                height = 48.dp,
                            )
                            Text(
                                "Follow system uses Android's “Remove animations” setting. Reduced motion keeps spinners still and turns transitions into short fades.",
                                style = type.meta,
                                color = colors.ink3,
                                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                            )
                        }
                        if (show("haptics")) {
                            ToggleRow("Haptics", "Taps for send, stop, answers and errors", settings.haptics) { on -> update { it.copy(haptics = on) } }
                        }
                    }
                }

                if (SettingsGroup.SessionDisplay in groups) {
                    Section(SettingsGroup.SessionDisplay.title) {
                        if (show("thinking-expanded")) {
                            ToggleRow("Show thinking expanded", "Open the model's reasoning by default", settings.thinkingExpanded) { on -> update { it.copy(thinkingExpanded = on) } }
                            Spacer(Modifier.height(12.dp))
                        }
                        if (show("tool-preview")) {
                            Label("Tool output preview")
                            Segmented(
                                options = AppSettings.TOOL_PREVIEW_CHOICES.map { it to "$it lines" },
                                selected = settings.toolPreviewLines,
                                onSelect = { n -> update { it.copy(toolPreviewLines = n) } },
                                modifier = Modifier.fillMaxWidth(),
                                height = 48.dp,
                            )
                        }
                    }
                }

                if (SettingsGroup.Notifications in groups) {
                    Section(SettingsGroup.Notifications.title) { NotificationsRow() }
                }

                if (SettingsGroup.Security in groups) {
                    Section(SettingsGroup.Security.title) {
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
                }

                if (SettingsGroup.About in groups) {
                    Section(SettingsGroup.About.title) {
                        if (show("version")) {
                            InfoLine("Version", app.appVersion)
                            InfoLine("This phone", app.deviceName)
                        }
                        if (show("updates")) UpdateBlock(app, settings.autoUpdateCheck) { on -> update { it.copy(autoUpdateCheck = on) } }
                        if (show("licences")) {
                            // Licence texts are read off the main thread.
                            NavRow("Inter licence", "SIL Open Font License 1.1", onClick = {
                                scope.launch { licence = ViewerContent("Inter", "SIL Open Font License 1.1", readRaw(context, R.raw.license_inter), wrap = true) }
                            })
                            NavRow("JetBrains Mono licence", "SIL Open Font License 1.1", onClick = {
                                scope.launch { licence = ViewerContent("JetBrains Mono", "SIL Open Font License 1.1", readRaw(context, R.raw.license_jetbrains_mono), wrap = true) }
                            })
                            NavRow("Mermaid licence", "MIT, with the projects it bundles", onClick = {
                                scope.launch { licence = ViewerContent("Mermaid", "MIT, with the projects it bundles", readRaw(context, R.raw.license_mermaid), wrap = true) }
                            })
                        }
                    }
                }
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

private fun textSizeLabel(step: Int) = when (step) {
    -1 -> "Small"
    0 -> "Default"
    1 -> "Large"
    else -> "Larger"
}

private suspend fun readRaw(context: Context, @RawRes id: Int): String = withContext(Dispatchers.IO) {
    context.resources.openRawResource(id).bufferedReader().use { it.readText() }
}
