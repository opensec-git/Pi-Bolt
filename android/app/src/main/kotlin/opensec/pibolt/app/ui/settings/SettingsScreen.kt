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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.R
import opensec.pibolt.app.data.AgentDefaults
import opensec.pibolt.app.data.AppSettings
import opensec.pibolt.app.data.Density
import opensec.pibolt.app.data.Host
import opensec.pibolt.app.data.QueueMode
import opensec.pibolt.app.data.ReduceMotion
import opensec.pibolt.app.git.github.GitHubState
import opensec.pibolt.app.ui.components.ChoiceSheet
import opensec.pibolt.app.ui.components.GroupDivider
import opensec.pibolt.app.ui.components.GroupRow
import opensec.pibolt.app.ui.components.GroupSwitchRow
import opensec.pibolt.app.ui.components.InsetGroup
import opensec.pibolt.app.ui.components.InsetRow
import opensec.pibolt.app.ui.components.LargeTitlePage
import opensec.pibolt.app.ui.components.LoadingRow
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.PiSnackHost
import opensec.pibolt.app.ui.components.PillSearchField
import opensec.pibolt.app.ui.components.SegmentedSlots
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.components.show
import opensec.pibolt.app.ui.cost.SpendLimitsSheet
import opensec.pibolt.app.ui.cost.SpendText
import opensec.pibolt.app.ui.git.GitHubSection
import opensec.pibolt.app.ui.git.GitHubSignInSheet
import opensec.pibolt.app.ui.packages.McpSheet
import opensec.pibolt.app.ui.packages.PackagesSheet
import opensec.pibolt.app.ui.providers.LogoMark
import opensec.pibolt.app.ui.session.ModelSheet
import opensec.pibolt.app.ui.session.Viewer
import opensec.pibolt.app.ui.session.ViewerContent
import opensec.pibolt.app.ui.session.features2.TuningSheet
import opensec.pibolt.app.ui.session.voice.VoicePickerSheet
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.PiType
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Space

/** The few-option settings that open a [ChoiceSheet] from their row. */
private enum class Choice { Thinking, Density, Steering, FollowUp, Retries, ToolPreview, ReduceMotion, VoiceRate }

/** The engine's sheets that Settings opens. */
private enum class EngineSheet { Packages, Mcp, Tuning }

/**
 * Preferences, applied live (no activity restart) and saved in settings.json (the engine's own in its
 * agent/settings.json). A large-title page in the iOS-settings manner: the search pill narrows the page to matching
 * settings ([SettingsIndex]); with no query the account card leads (GitHub, and the default model with its mark).
 * Then the groups in [SettingsGroup] order, each a rounded inset group of rows with a tinted glyph tile (one of the
 * theme's five tints per section; neutral for GitHub, Advanced and About), the title, the value in ink3 and a
 * chevron. Few-option settings open a [ChoiceSheet]; switches share the end column.
 */
@Composable
fun SettingsScreen(
    app: PiBoltApp,
    onBack: () -> Unit,
    onRuntimes: () -> Unit,
    onProviders: () -> Unit = {},
    onScan: () -> Unit = {},
    onOpenHost: (Host) -> Unit = {},
    onResumeHost: () -> Unit = {},
    onPairingText: (String) -> String? = { null },
) {
    val settings = Pi.settings
    val colors = Pi.colors
    val type = Pi.type
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var licence by remember { mutableStateOf<ViewerContent?>(null) }
    var licences by remember { mutableStateOf(false) }
    var themes by remember { mutableStateOf(false) }
    var textSize by remember { mutableStateOf(false) }
    var choice by remember { mutableStateOf<Choice?>(null) }
    var engineSheet by remember { mutableStateOf<EngineSheet?>(null) }
    var picking by remember { mutableStateOf(false) }
    var voicePicker by remember { mutableStateOf(false) }
    var signingIn by remember { mutableStateOf(false) }
    var spendLimits by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(query) { SettingsIndex.visible(query) }
    val groups = remember(visible) { SettingsIndex.groups(visible).toSet() }
    val show: (String) -> Boolean = { id -> visible == null || id in visible }
    val snackbar = remember { SnackbarHostState() }
    val message: (String) -> Unit = { text -> scope.launch { snackbar.show(text) } }
    fun update(change: (AppSettings) -> AppSettings) = app.settings.update(change)
    val prefs = rememberAgentPrefs(app)
    // Providers with a working model: keys, custom endpoints and the providers Pi packages register.
    val configured by app.providers.connected.collectAsStateWithLifecycle()
    val github by app.github.state.collectAsStateWithLifecycle()
    // No provider: the open session lists no model, and the picker would wait for one for ever.
    fun pickModel() = when {
        configured.isEmpty() -> onProviders()
        prefs?.controller != null -> {
            // Listed afresh, as the session's own picker does: a provider added or removed since then changes the list.
            prefs.controller.loadModels()
            picking = true
        }
        // No session open: the engine's own list of the models that work (extension providers' too).
        prefs != null && prefs.models.isNotEmpty() -> {
            app.providers.refresh()
            picking = true
        }
        else -> {
            app.providers.refresh()
            message("Open a session on this phone to choose its models")
        }
    }

    LargeTitlePage(
        "Settings",
        onBack = onBack,
        overlay = {
            AnimatedVisibility(
                visible = licence != null,
                enter = fadeIn(PiMotion.enter(PiMotion.SHEET)) + scaleIn(PiMotion.enter(PiMotion.SHEET), initialScale = 0.96f),
                exit = fadeOut(PiMotion.leave(PiMotion.SHEET)) + scaleOut(PiMotion.leave(PiMotion.SHEET), targetScale = 0.96f),
            ) {
                val shown = remember { mutableStateOf(licence) }
                licence?.let { shown.value = it }
                shown.value?.let { Viewer(it, onClose = { licence = null }) }
            }
            PiSnackHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding())
        },
    ) {
        PillSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search",
            onSearch = { focus.clearFocus() },
            modifier = Modifier.padding(start = Space.lg, end = Space.lg, top = Space.xs, bottom = Space.xs),
        )
        if (query.isNotBlank() && groups.isEmpty()) {
            Text(
                "No results for “${query.trim()}”",
                style = type.rowMeta,
                color = colors.ink3,
                modifier = Modifier.fillMaxWidth().padding(start = Space.page, end = Space.page, top = Space.x4),
            )
        }

        if (query.isBlank()) {
            AccountCard(
                github = github,
                modelText = prefs?.modelText,
                modelLeading = prefs?.brand?.let { b -> { LogoMark(b, size = IconSize.sm, tint = colors.ink1) } },
                onAccount = { if (github !is GitHubState.SignedIn) signingIn = true },
                onModel = ::pickModel,
            )
        }

        if (SettingsGroup.Models in groups) {
            val rows = buildList<SettingsRow> {
                if (query.isNotBlank() && show("default-model")) add { d ->
                    GroupRow(
                        "Default model",
                        icon = Icons.Rounded.AutoAwesome,
                        iconTint = colors.tint0,
                        leading = prefs?.brand?.let { b -> { LogoMark(b, size = IconSize.sm, tint = colors.ink2) } },
                        value = prefs?.modelText,
                        onClick = ::pickModel,
                        divider = d,
                    )
                }
                if (show("providers")) add { d ->
                    GroupRow(
                        "Providers",
                        icon = Icons.Rounded.Key,
                        iconTint = colors.tint0,
                        value = if (configured.isEmpty()) "None" else configured.size.toString(),
                        onClick = onProviders,
                        divider = d,
                    )
                }
                if (show("thinking") && prefs != null) add { d ->
                    GroupRow(
                        "Thinking level",
                        icon = Icons.Rounded.Psychology,
                        iconTint = colors.tint0,
                        value = thinkingLabel(prefs.current.defaultThinking),
                        onClick = { choice = Choice.Thinking },
                        divider = d,
                    )
                }
            }
            SettingsBlock(rows, header = SettingsGroup.Models.title)
        }

        if (SettingsGroup.Appearance in groups) {
            val tint = colors.tint2
            val rows = buildList<SettingsRow> {
                if (show("theme")) add { d -> GroupRow("Theme", icon = Icons.Rounded.Palette, iconTint = tint, value = settings.theme.label, onClick = { themes = true }, divider = d) }
                if (show("accent")) add { d -> GroupRow("Accent", icon = Icons.Rounded.Circle, iconTint = tint, value = settings.accent.label, onClick = { themes = true }, divider = d) }
                if (show("text-size")) add { d -> GroupRow("Text size", icon = Icons.Rounded.FormatSize, iconTint = tint, value = textSizeLabel(settings.textSize), onClick = { textSize = true }, divider = d) }
                if (show("density")) add { d -> GroupRow("Density", icon = Icons.Rounded.ViewAgenda, iconTint = tint, value = settings.density.label, onClick = { choice = Choice.Density }, divider = d) }
            }
            SettingsBlock(rows, header = SettingsGroup.Appearance.title)
        }

        if (SettingsGroup.Agent in groups) {
            val tint = colors.tint3
            val p = prefs
            if (p == null) {
                InsetGroup(header = SettingsGroup.Agent.title) { LoadingRow("Reading settings") }
            } else {
                val c = p.current
                val rows = buildList<SettingsRow> {
                    if (show("web-search")) add(webSearchRow(app, settings.webSearch, tint))
                    if (show("steering")) add { d -> GroupRow("Steering", icon = Icons.Rounded.Explore, iconTint = tint, value = c.steeringMode.label, onClick = { choice = Choice.Steering }, divider = d) }
                    if (show("follow-up")) add { d -> GroupRow("Follow-up", icon = Icons.AutoMirrored.Rounded.Reply, iconTint = tint, value = c.followUpMode.label, onClick = { choice = Choice.FollowUp }, divider = d) }
                    if (show("compaction")) add { d ->
                        GroupSwitchRow("Auto-compact", c.autoCompaction, { on -> p.update { it.copy(autoCompaction = on) } }, icon = Icons.Rounded.Compress, iconTint = tint, divider = d)
                    }
                    if (show("retry")) {
                        add { d -> GroupSwitchRow("Auto-retry", c.autoRetry, { on -> p.update { it.copy(autoRetry = on) } }, icon = Icons.Rounded.Replay, iconTint = tint, divider = d) }
                        if (c.autoRetry) add { d ->
                            GroupRow("Retries", icon = Icons.Rounded.Repeat, iconTint = tint, value = c.maxRetries.toString(), onClick = { choice = Choice.Retries }, divider = d)
                        }
                    }
                    if (show("skills")) add { d ->
                        GroupSwitchRow("Skill commands", c.skillCommands, { on -> p.update { it.copy(skillCommands = on) } }, icon = Icons.Rounded.AutoAwesome, iconTint = tint, divider = d)
                    }
                    if (show("images")) add { d ->
                        GroupSwitchRow("Block images", c.blockImages, { on -> p.update { it.copy(blockImages = on) } }, icon = Icons.Rounded.HideImage, iconTint = tint, divider = d)
                    }
                    if (show("thinking-expanded")) add { d ->
                        GroupSwitchRow("Expand thinking", settings.thinkingExpanded, { on -> update { it.copy(thinkingExpanded = on) } }, icon = Icons.Rounded.UnfoldMore, iconTint = tint, divider = d)
                    }
                    if (show("tool-preview")) add { d ->
                        GroupRow("Tool output", icon = Icons.AutoMirrored.Rounded.Notes, iconTint = tint, value = "${settings.toolPreviewLines} lines", onClick = { choice = Choice.ToolPreview }, divider = d)
                    }
                    if (show("voice")) addAll(voiceRows(settings, tint, ::update, onRate = { choice = Choice.VoiceRate }, onVoice = { voicePicker = true }))
                    if (show("spending")) add { d ->
                        GroupRow("Spending", icon = Icons.Rounded.Savings, iconTint = tint, value = SpendText.summary(settings.spend), onClick = { spendLimits = true }, divider = d)
                    }
                }
                SettingsBlock(rows, header = SettingsGroup.Agent.title)
            }
        }

        if (SettingsGroup.Projects in groups) {
            val tint = colors.tint4
            val shipped by produceState<String?>(null) {
                value = withContext(Dispatchers.IO) { app.engine.runtimes().runtimes.joinToString(" · ") { it.name } }
            }
            val rows = buildList<SettingsRow> {
                if (show("runtimes")) add { d -> GroupRow("Runtimes", icon = Icons.Rounded.Terminal, iconTint = tint, value = shipped?.ifEmpty { "None" } ?: "…", onClick = onRuntimes, divider = d) }
                if (show("packages")) add { d -> GroupRow("Packages", icon = Icons.Rounded.Extension, iconTint = tint, onClick = { engineSheet = EngineSheet.Packages }, divider = d) }
                if (show("mcp")) add { d -> GroupRow("MCP servers", icon = Icons.Rounded.Cable, iconTint = tint, onClick = { engineSheet = EngineSheet.Mcp }, divider = d) }
            }
            SettingsBlock(rows, header = SettingsGroup.Projects.title)
        }

        if (SettingsGroup.RemoteControl in groups) RemoteSection(app, show, onScan, onOpenHost, onResumeHost, onPairingText)
        if (SettingsGroup.GitHub in groups) GitHubSection(app, header = SettingsGroup.GitHub.title)

        if (SettingsGroup.Advanced in groups) {
            val rows = buildList<SettingsRow> {
                if (show("tuning")) add { d -> GroupRow("Retry and compaction", icon = Icons.Rounded.Tune, onClick = { engineSheet = EngineSheet.Tuning }, divider = d) }
                if (show("reduce-motion")) add { d -> GroupRow("Reduce motion", icon = Icons.Rounded.Animation, value = settings.reduceMotion.label, onClick = { choice = Choice.ReduceMotion }, divider = d) }
                if (show("haptics")) add { d -> GroupSwitchRow("Haptics", settings.haptics, { on -> update { it.copy(haptics = on) } }, icon = Icons.Rounded.Vibration, divider = d) }
                if (show("notifications")) add { d -> NotificationsRow(d) }
                if (show("background")) add { d -> BackgroundRunRow(d) }
            }
            SettingsBlock(rows, header = SettingsGroup.Advanced.title)
        }

        if (SettingsGroup.About in groups) {
            val rows = buildList<SettingsRow> {
                if (show("version")) {
                    add { d -> GroupRow("Version", icon = Icons.Rounded.Info, value = app.appVersion, divider = d) }
                    add { d -> GroupRow("This phone", icon = Icons.Rounded.PhoneAndroid, value = app.deviceName, divider = d) }
                }
                if (show("updates")) addAll(updateRows(app, settings.autoUpdateCheck) { on -> update { it.copy(autoUpdateCheck = on) } })
                if (show("licences")) add { d -> GroupRow("Licences", icon = Icons.Rounded.Description, onClick = { licences = true }, divider = d) }
            }
            SettingsBlock(rows, header = SettingsGroup.About.title)
        }
    }

    if (licences) {
        LicencesSheet(
            onPick = { name, kind, raw ->
                licences = false
                scope.launch { licence = ViewerContent(name, kind, readRaw(context, raw), wrap = true) }
            },
            onDismiss = { licences = false },
        )
    }
    if (themes) {
        ThemeSheet(
            settings.theme,
            settings.accent,
            onTheme = { theme -> update { it.copy(theme = theme) } },
            onAccent = { accent -> update { it.copy(accent = accent) } },
            onDismiss = { themes = false },
        )
    }
    if (textSize) TextSizeSheet(settings.textSize, onSelect = { step -> update { it.copy(textSize = step) } }, onDismiss = { textSize = false })
    if (signingIn) GitHubSignInSheet(app, onDismiss = { signingIn = false })
    if (voicePicker) {
        VoicePickerSheet(settings.voiceName, settings.voiceRate, onPick = { name, label -> update { it.copy(voiceName = name, voiceLabel = label) } }, onDismiss = { voicePicker = false })
    }
    if (spendLimits) SpendLimitsSheet(app, onDismiss = { spendLimits = false })
    val p = prefs
    if (picking && p != null && (p.controller != null || p.models.isNotEmpty())) {
        ModelSheet(p.models, p.match, onPick = { m ->
            picking = false
            p.update { it.copy(defaultModel = "${m.provider}/${m.id}") }
        }, onDismiss = { picking = false })
    }
    val closeEngine = { engineSheet = null }
    when (engineSheet) {
        EngineSheet.Packages -> PackagesSheet(app.engine, app.http, p?.anyLive, null, message, closeEngine)
        EngineSheet.Mcp -> McpSheet(app.engine, null, message, closeEngine)
        EngineSheet.Tuning -> TuningSheet(app.engine.agentDir, null, message, closeEngine)
        null -> Unit
    }
    val dismiss = { choice = null }
    when (choice) {
        Choice.Thinking -> if (p != null) {
            ChoiceSheet(
                "Thinking level",
                AgentDefaults.THINKING_LEVELS.map { it to thinkingLabel(it) },
                p.current.defaultThinking.takeIf { it in AgentDefaults.THINKING_LEVELS } ?: "medium",
                onSelect = { level -> p.update { it.copy(defaultThinking = level) } },
                onDismiss = dismiss,
            )
        }
        Choice.Steering -> if (p != null) {
            ChoiceSheet("Steering", QueueMode.entries.map { it to it.label }, p.current.steeringMode, onSelect = { m -> p.update { it.copy(steeringMode = m) } }, onDismiss = dismiss)
        }
        Choice.FollowUp -> if (p != null) {
            ChoiceSheet("Follow-up", QueueMode.entries.map { it to it.label }, p.current.followUpMode, onSelect = { m -> p.update { it.copy(followUpMode = m) } }, onDismiss = dismiss)
        }
        Choice.Retries -> if (p != null) {
            ChoiceSheet(
                "Retries",
                AgentDefaults.RETRY_CHOICES.map { it to if (it == 1) "1 attempt" else "$it attempts" },
                p.current.maxRetries.takeIf { it in AgentDefaults.RETRY_CHOICES } ?: 3,
                onSelect = { n -> p.update { it.copy(maxRetries = n) } },
                onDismiss = dismiss,
            )
        }
        Choice.Density -> ChoiceSheet("Density", Density.entries.map { it to it.label }, settings.density, onSelect = { v -> update { it.copy(density = v) } }, onDismiss = dismiss)
        Choice.ToolPreview -> ChoiceSheet(
            "Tool output",
            AppSettings.TOOL_PREVIEW_CHOICES.map { it to "$it lines" },
            settings.toolPreviewLines,
            onSelect = { n -> update { it.copy(toolPreviewLines = n) } },
            onDismiss = dismiss,
        )
        Choice.VoiceRate -> ChoiceSheet(
            "Speech rate",
            AppSettings.VOICE_RATE_CHOICES.map { it to rateLabel(it) },
            settings.voiceRate,
            onSelect = { n -> update { it.copy(voiceRate = n) } },
            onDismiss = dismiss,
        )
        Choice.ReduceMotion -> ChoiceSheet("Reduce motion", ReduceMotion.entries.map { it to it.label }, settings.reduceMotion, onSelect = { r -> update { it.copy(reduceMotion = r) } }, onDismiss = dismiss)
        null -> Unit
    }
}

/**
 * The account card that leads Settings: the GitHub identity (a 44dp avatar disc, the name and @login, or "Sign in with
 * GitHub") and the default model with its mark. A tap on the signed-out account opens the sign-in sheet; a tap on the
 * model opens the model picker.
 */
@Composable
private fun AccountCard(
    github: GitHubState,
    modelText: String?,
    modelLeading: (@Composable BoxScope.() -> Unit)?,
    onAccount: () -> Unit,
    onModel: () -> Unit,
) {
    val colors = Pi.colors
    val type = Pi.type
    val account = (github as? GitHubState.SignedIn)?.account
    InsetGroup(modifier = Modifier.padding(top = Space.sm)) {
        Box(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(if (account == null) Modifier.pressable(onAccount, shape = RectangleShape, pressedScale = 1f, label = "Sign in with GitHub") else Modifier)
                    .heightIn(min = 76.dp)
                    .padding(start = Space.lg, end = Space.md, top = Space.md, bottom = Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(48.dp).background(colors.surface3, CircleShape).border(1.dp, colors.line, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.AccountCircle, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.width(Space.md + Space.xxs))
                Column(Modifier.weight(1f)) {
                    Text(
                        account?.let { it.name ?: "@${it.login}" } ?: "Sign in with GitHub",
                        style = type.rowTitle.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.ink1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        account?.let { "@${it.login} · GitHub" } ?: "Clone, push and pull",
                        style = type.rowMeta,
                        color = colors.ink3,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = Space.xxs),
                    )
                }
                if (account == null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(IconSize.md))
            }
            GroupDivider(Modifier.align(Alignment.BottomStart))
        }
        GroupRow(
            "Default model",
            icon = Icons.Rounded.AutoAwesome,
            iconTint = colors.tint0,
            leading = modelLeading,
            meta = modelText ?: "…",
            metaMaxLines = 1,
            onClick = onModel,
            divider = false,
        )
    }
}

/** Appearance > Text size: the "A" steps and a live prose preview; a pick applies at once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextSizeSheet(step: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    PiSheet(onDismiss = onDismiss) {
        SheetHeader("Text size")
        Column(Modifier.fillMaxWidth().padding(start = Space.page, end = Space.page, top = Space.sm, bottom = Space.lg)) {
            // Growing "A" glyphs, one per step: reads as a text size control at a glance.
            val steps = (AppSettings.TEXT_SIZE_MIN..AppSettings.TEXT_SIZE_MAX).toList()
            SegmentedSlots(
                options = steps,
                selected = step,
                onSelect = onSelect,
                labels = steps.map(::textSizeLabel),
                height = 48.dp,
                modifier = Modifier.fillMaxWidth(),
            ) { i, on ->
                Text(
                    "A",
                    style = type.label.copy(fontSize = type.label.fontSize * (0.93f + 0.2f * i), fontWeight = FontWeight.SemiBold),
                    color = if (on) colors.ink1 else colors.ink3,
                )
            }
            Spacer(Modifier.height(Space.lg))
            val f = PiType.factorFor(step)
            Text(
                "Pi reads the failing test, fixes the parser and runs the suite again.",
                style = type.prose.let { p -> p.copy(fontSize = p.fontSize * f, lineHeight = p.lineHeight * f) },
                color = colors.ink1,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.canvas, RoundedCornerShape(Radius.tile))
                    .border(1.dp, colors.line, RoundedCornerShape(Radius.tile))
                    .padding(Space.lg),
            )
        }
    }
}

/** The bundled projects' licences, one row each; a tap opens the text in the viewer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicencesSheet(onPick: (name: String, kind: String, raw: Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader("Licences")
        LICENCES.forEachIndexed { i, (name, kind, raw) ->
            InsetRow(
                name,
                onClick = { close { onPick(name, kind, raw) } },
                meta = kind,
                leading = { Icon(Icons.Rounded.Description, contentDescription = null, tint = Pi.colors.ink2, modifier = Modifier.size(IconSize.md)) },
                chevron = true,
                divider = i < LICENCES.lastIndex,
            )
        }
    }
}

private val LICENCES = listOf(
    Triple("Inter", "SIL Open Font License 1.1", R.raw.license_inter),
    Triple("JetBrains Mono", "SIL Open Font License 1.1", R.raw.license_jetbrains_mono),
    Triple("Mermaid", "MIT, with the projects it bundles", R.raw.license_mermaid),
    Triple("Termux terminal", "Apache License 2.0", R.raw.license_termux_terminal),
    Triple("Lobe Icons", "MIT", R.raw.license_lobe_icons),
)

private fun textSizeLabel(step: Int) = when (step) {
    -1 -> "Small"
    0 -> "Default"
    1 -> "Large"
    else -> "Larger"
}

private suspend fun readRaw(context: Context, @RawRes id: Int): String = withContext(Dispatchers.IO) {
    context.resources.openRawResource(id).bufferedReader().use { it.readText() }
}
