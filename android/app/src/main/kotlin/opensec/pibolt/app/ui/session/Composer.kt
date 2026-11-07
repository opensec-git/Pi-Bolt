package opensec.pibolt.app.ui.session

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.rememberUpdatedState
import opensec.pibolt.app.ui.theme.IconSize
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.Chip
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.protocol.rpc.StreamingBehavior
import opensec.pibolt.app.ui.session.features.CommandList
import opensec.pibolt.app.ui.session.features.CommandPalette
import opensec.pibolt.app.ui.session.features.ComposerHint
import opensec.pibolt.app.ui.session.features.FileMentions
import opensec.pibolt.app.ui.session.features.MentionList
import opensec.pibolt.app.ui.session.features.PaletteEntry
import opensec.pibolt.app.ui.session.features.ProjectFileIndex
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

private enum class SendMode { Send, Stop }

/**
 * The composer, after the desktop composer and assistant-ui: one rounded card (surface1, radius lg, a 1dp lineStrong
 * edge that becomes the focus line) with a tray of attached images, the field, and a bar that is always there: + as
 * a ghost button on the left, the run-settings chip, then the mic (a ghost) and Send (the accent circle) or Stop in
 * the same slot on the right. Only a landscape keyboard puts it all on one row ([compact]). Every button is a 48dp
 * target on one centre line ([ComposerLayout]). While the agent works Send turns into Stop when the field is empty, and a Steer | Follow-up chip
 * takes the run-settings chip's place while typing.
 *
 * [attachAllowed] false only changes the + button's description: its sheet also mentions files and commands, and its
 * Photos row says why images are off.
 */
@Composable
fun Composer(
    text: String,
    onText: (String) -> Unit,
    enabled: Boolean,
    running: Boolean,
    mode: StreamingBehavior,
    onMode: (StreamingBehavior) -> Unit,
    modelLabel: String?,
    thinkingLevel: String?,
    palette: List<PaletteEntry>,
    onPick: (PaletteEntry) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onModel: () -> Unit,
    modifier: Modifier = Modifier,
    images: List<ComposerImage> = emptyList(),
    attachAllowed: Boolean = true,
    onAttach: () -> Unit = {},
    onRemoveImage: (Long) -> Unit = {},
    onOpenImage: (Int) -> Unit = {},
    focusRequester: FocusRequester = remember { FocusRequester() },
    files: ProjectFileIndex? = null,
    shell: Boolean = false,
    onCycleModel: () -> Unit = {},
    onCycleThinking: () -> Unit = {},
    /** Something [onStop] can stop: an agent run, or a user shell command started while Pi is idle. */
    stoppable: Boolean = running,
    /** One row (attach, field, send) and at most 3 lines: a short window or the keyboard leaves little room. */
    compact: Boolean = false,
    /** Lines the field grows to before it scrolls. */
    maxLines: Int = MAX_LINES,
    /** Earlier prompts for Up / Down, most recent first. */
    history: List<String> = emptyList(),
    /** Hardware keys the screen handles (model picker, search, queue restore, follow-up). */
    onKey: (ComposerKeys.Action) -> Unit = {},
    /** Ctrl+V: true when the screen took an image from the clipboard (the text paste is then skipped). */
    onPasteImage: () -> Boolean = { false },
    /** A mic for dictation, shown beside Send (ui/session/voice). */
    voice: (@Composable () -> Unit)? = null,
    /** The slash-command and `@` panel's height cap: the screen lowers it so the composer stays above the keyboard. */
    suggestionsMaxHeight: Dp = SUGGESTIONS_MAX,
) {
    val colors = Pi.colors
    val type = Pi.type
    val reduced = Pi.reduceMotion
    val haptics = Pi.haptics
    val hasContent = text.isNotBlank() || images.isNotEmpty()
    val encoding = images.any { !it.ready }
    // The field keeps its own selection; text set from outside (a command, a recent prompt, a failed send) puts the
    // cursor at its end.
    var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    val value = if (field.text == text) field else TextFieldValue(text, TextRange(text.length))
    val commands = remember(text, palette) { CommandPalette.filter(text, palette) }
    val usage = remember(text, palette) { CommandPalette.hintFor(text, palette) }
    // `@` mentions: the token at the cursor, then the project's files for it (read on first use).
    val mention = remember(value.text, value.selection) {
        if (files?.available == true && value.selection.collapsed) FileMentions.tokenAt(value.text, value.selection.start) else null
    }
    if (mention != null && files != null) LaunchedEffect(files) { files.load() }
    val allFiles = files?.files?.value
    val mentionPaths = remember(mention?.query, allFiles) { allFiles?.let { list -> mention?.let { FileMentions.rank(it.query, list) } } }
    val shellMode = shell && text.startsWith("!")
    // Esc closes the panel for the text it was open on; typing on opens it again.
    var dismissedFor by remember { mutableStateOf<String?>(null) }
    val suggesting = (commands.isNotEmpty() || mention != null) && dismissedFor != value.text
    val suggestionCount = if (mention != null) mentionPaths?.size ?: 0 else commands.size
    // The row Up / Down moved to (hardware keyboards); -1 until an arrow is pressed, so a soft keyboard's Enter still
    // adds a line. It starts over whenever the list changes.
    var highlight by remember(commands, mentionPaths) { mutableIntStateOf(-1) }
    fun pickCommand(entry: PaletteEntry) {
        haptics.tick()
        onPick(entry)
    }
    fun pickPath(path: String) {
        val token = mention ?: return
        haptics.tick()
        val (next, cursor) = FileMentions.insert(value.text, token, path)
        field = TextFieldValue(next, TextRange(cursor))
        onText(next)
    }
    // What the panel showed last, so it shrinks away with its rows instead of collapsing empty in one frame.
    val shown = remember { ShownSuggestions() }
    if (suggesting) shown.set(mention, mentionPaths, commands)
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        AnimatedVisibility(
            visible = suggesting,
            enter = expandVertically(PiMotion.enter(PiMotion.STANDARD)) + fadeIn(PiMotion.enter(PiMotion.QUICK)),
            exit = shrinkVertically(PiMotion.leave(PiMotion.STANDARD)) + fadeOut(PiMotion.leave(PiMotion.QUICK)),
        ) {
            val token = shown.mention
            when {
                token != null -> MentionList(shown.paths, token.query, onPick = { if (suggesting) pickPath(it) }, highlighted = highlight, maxHeight = suggestionsMaxHeight)
                shown.commands.isNotEmpty() -> CommandList(shown.commands, onPick = { if (suggesting) pickCommand(it) }, highlighted = highlight, maxHeight = suggestionsMaxHeight)
            }
        }
        when {
            shellMode -> ComposerHint(
                if (text.startsWith("!!")) "Shell: runs in the project; the output stays out of Pi's context" else "Shell: runs in the project; the output goes to Pi with your next message",
                mono = false,
            )
            usage != null && commands.isEmpty() -> ComposerHint("${usage.label} ${usage.hint}", mono = true)
        }
        val browse = remember { PromptHistory(history) }
        browse.update(history)
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        val border by animateColorAsState(if (focused) colors.lineFocus else colors.lineStrong, PiMotion.effects(), label = "composer")
        val shape = RoundedCornerShape(Radius.lg)
        val density = LocalDensity.current
        // One style for the text and its placeholder (15/22, as assistant-ui), so the placeholder sits exactly where the
        // first typed line does. It used prose's 25sp line while the text used 23.1sp: the two sat 1dp apart.
        val fieldStyle = type.body.copy(color = colors.ink1)
        // The one-row form centres one line on the buttons' 48dp target; with more lines the last one stays there. A
        // fixed 12dp put the line 3dp low at font scale 1.3.
        val linePad = ComposerLayout.fieldPad(with(density) { fieldStyle.lineHeight.toDp() })
        val sendMode = if (stoppable && !hasContent) SendMode.Stop else SendMode.Send
        val sendEnabled = enabled && if (hasContent) !encoding else stoppable
        val steering = running && hasContent
        val stopBeside = stoppable && hasContent
        // At 360dp the landscape-keyboard row has no room for the field beside +, mic, Steer, Stop and Send: the +
        // steps aside while a steer is typed (not the mic, which may be listening).
        val narrow = with(density) { LocalWindowInfo.current.containerSize.width.toDp() } < ComposerLayout.NARROW
        val showAttach = ComposerLayout.showAttach(compact, narrow, steering)
        // The mic keeps its state (a dictation in progress) when it moves between the bar and the landscape row.
        val currentVoice by rememberUpdatedState(voice)
        val mic = remember { movableContentOf { currentVoice?.invoke() } }
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.surface1, shape)
                .border(1.dp, border, shape)
                .workingHairline(running && !reduced, colors.accent)
                .animateContentSize(PiMotion.inPlace(PiMotion.QUICK)),
        ) {
            if (images.isNotEmpty()) ImageTray(images, onRemoveImage, onOpenImage, start = 12.dp)
            val textField = @Composable { fieldModifier: Modifier -> BasicTextField(
                value = value,
                onValueChange = {
                    field = it
                    if (it.text != text) {
                        browse.edited(it.text)
                        onText(it.text)
                    }
                },
                enabled = enabled,
                textStyle = fieldStyle,
                cursorBrush = SolidColor(colors.accent),
                maxLines = if (compact) maxLines.coerceAtMost(3) else maxLines,
                interactionSource = interaction,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = fieldModifier
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { e ->
                        // Hardware keyboards, as in the terminal (FEATURES.md): Ctrl+Enter sends, Alt+Enter queues a
                        // follow-up, Esc stops, Up / Down walk the prompt history, Ctrl+P / Shift+Tab cycle model and
                        // thinking, Ctrl+L opens the model picker, Ctrl+Shift+F searches, Alt+Up restores the queue.
                        // While the command or `@` panel is open, Up / Down / Tab / Esc work in it.
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val cursor = value.selection.start
                        val action = ComposerKeys.action(
                            e.key,
                            ctrl = e.isCtrlPressed || e.isMetaPressed,
                            shift = e.isShiftPressed,
                            running = stoppable,
                            hasContent = hasContent,
                            alt = e.isAltPressed,
                            suggesting = suggesting,
                            highlighted = highlight >= 0,
                            firstLine = value.selection.collapsed && PromptHistory.onFirstLine(value.text, cursor),
                            lastLine = value.selection.collapsed && PromptHistory.onLastLine(value.text, cursor),
                        )
                        when (action) {
                            ComposerKeys.Action.Send -> if (enabled && !encoding) onSend()
                            ComposerKeys.Action.Stop -> onStop()
                            ComposerKeys.Action.CycleModel -> if (enabled) onCycleModel()
                            ComposerKeys.Action.CycleThinking -> if (enabled) onCycleThinking()
                            ComposerKeys.Action.HistoryOlder -> {
                                val next = browse.older(value.text) ?: return@onPreviewKeyEvent false
                                field = TextFieldValue(next, TextRange(next.length))
                                onText(next)
                            }
                            ComposerKeys.Action.HistoryNewer -> {
                                val next = browse.newer() ?: return@onPreviewKeyEvent false
                                field = TextFieldValue(next, TextRange(next.length))
                                onText(next)
                            }
                            ComposerKeys.Action.Paste -> if (!onPasteImage()) return@onPreviewKeyEvent false
                            ComposerKeys.Action.FollowUp -> if (enabled && !encoding) onKey(action)
                            ComposerKeys.Action.OpenModels, ComposerKeys.Action.Search, ComposerKeys.Action.RestoreQueue -> onKey(action)
                            ComposerKeys.Action.SuggestPrevious, ComposerKeys.Action.SuggestNext -> {
                                if (suggestionCount == 0) return@onPreviewKeyEvent false
                                highlight = ComposerLayout.moveHighlight(highlight, if (action == ComposerKeys.Action.SuggestNext) 1 else -1, suggestionCount)
                            }
                            ComposerKeys.Action.SuggestPick -> {
                                val at = highlight.coerceAtLeast(0)
                                if (mention != null) {
                                    pickPath(mentionPaths?.getOrNull(at) ?: return@onPreviewKeyEvent false)
                                } else {
                                    pickCommand(commands.getOrNull(at) ?: return@onPreviewKeyEvent false)
                                }
                            }
                            ComposerKeys.Action.SuggestDismiss -> dismissedFor = value.text
                            null -> return@onPreviewKeyEvent false
                        }
                        true
                    },
                decorationBox = { inner ->
                    val pad = if (compact) {
                        PaddingValues(start = ComposerLayout.ROW_FIELD_SIDE, end = ComposerLayout.ROW_FIELD_SIDE, top = linePad, bottom = linePad)
                    } else {
                        PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)
                    }
                    Box(Modifier.fillMaxWidth().padding(pad)) {
                        if (text.isEmpty()) {
                            Text(
                                when {
                                    !enabled -> "Not connected"
                                    shellMode -> "Shell command…"
                                    running && mode == StreamingBehavior.Steer -> "Steer Pi…"
                                    running -> "Queue a follow-up…"
                                    else -> "Message Pi…"
                                },
                                style = fieldStyle,
                                color = colors.ink3,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            ) }
            // + on the left and the mic before Send: ghost buttons (ink3 glyph, no fill) in 48dp targets.
            val attach = @Composable {
                IconAction(
                    Icons.Rounded.Add,
                    description = if (attachAllowed) "Attach images and more" else "Attach images and more (images not available for this model)",
                    onClick = onAttach,
                    enabled = enabled,
                    tint = colors.ink3,
                    size = IconSize.md,
                )
            }
            // As on the desktop the bar (with the run-settings chip) is always under the field, whatever the draft;
            // only the short window of a landscape keyboard puts everything on one row ([compact]). The field keeps
            // one place in the composition in both forms, so that switch never drops its focus and the keyboard.
            // Every button is a 48dp target on one centre line; the row sits EDGE inside the card, so the 32dp send
            // disc is 10dp from the end and the bottom edges alike, as on the desktop (it was 10dp and 4dp).
            Row(
                Modifier.fillMaxWidth().padding(end = if (compact) ComposerLayout.EDGE else 0.dp, top = if (compact) ComposerLayout.EDGE else 0.dp, bottom = if (compact) ComposerLayout.EDGE else 0.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                if (compact && showAttach) attach()
                textField(Modifier.weight(1f))
                if (compact) {
                    mic()
                    if (steering) SteerChip(mode, onMode)
                    if (stopBeside) StopButton(onStop)
                    SendButton(sendMode, sendEnabled, onSend, onStop)
                }
            }
            if (!compact) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(end = ComposerLayout.EDGE, bottom = ComposerLayout.EDGE)
                        .heightIn(min = ComposerLayout.TARGET),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    attach()
                    // One run-settings chip, "Sonnet 4.5 · High": the model gives way first, the level never wraps.
                    // Tap opens one sheet for both; long press is the next model. While a steer is typed the
                    // Steer / Follow-up chip takes its slot: beside mic, Stop and Send at 360dp the model chip had
                    // about 30dp left, and the top bar's subtitle still names the model.
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        val chip = ModelChipText.of(modelLabel, thinkingLevel)
                        if (steering) SteerChip(mode, onMode) else Chip(
                            onClick = onModel,
                            enabled = enabled,
                            onLongClick = onCycleModel,
                            longClickLabel = "Next model",
                            modifier = Modifier.weight(1f, fill = false).semantics { contentDescription = chip.description },
                        ) {
                            Text(chip.model, style = type.label, color = colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).widthIn(max = 180.dp))
                            if (chip.level != null) Text(" · " + chip.level, style = type.label, color = colors.ink3, maxLines = 1, softWrap = false)
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(16.dp))
                        }
                    }
                    mic()
                    // While a steer is typed Send takes the button: Stop stays one tap away beside it.
                    if (stopBeside) StopButton(onStop)
                    SendButton(sendMode, sendEnabled, onSend, onStop)
                }
            }
        }
    }
}

/**
 * Stop beside Send while the field has text. The same disc as Send ([StopDisc]), so Stop keeps its size and look when
 * it moves out of the send slot as typing starts.
 */
@Composable
private fun StopButton(onStop: () -> Unit) {
    val haptics = Pi.haptics
    Box(
        Modifier
            .size(ComposerLayout.TARGET)
            .pressable({
                haptics.confirm()
                onStop()
            }, shape = CircleShape, pressedScale = 0.92f, label = "Stop")
            .semantics { contentDescription = "Stop" },
        contentAlignment = Alignment.Center,
    ) {
        StopDisc()
    }
}

/** Stop's look in both places, as on the desktop: a 32dp surface3 disc with a 1dp lineStrong ring and a 10dp ink1 square. */
@Composable
private fun StopDisc() {
    val colors = Pi.colors
    Box(Modifier.size(ComposerLayout.DISC).background(colors.surface3, CircleShape).border(1.dp, colors.lineStrong, CircleShape), contentAlignment = Alignment.Center) {
        StopGlyph()
    }
}

@Composable
private fun StopGlyph() {
    Icon(Icons.Rounded.Stop, contentDescription = null, tint = Pi.colors.ink1, modifier = Modifier.size(IconSize.md))
}

/**
 * Steer or Follow-up for what is typed while Pi works: a 32dp chip in the run-settings chip's slot (warning dot for
 * Steer, ink4 dot for Follow-up; in the landscape-keyboard row, before Stop). Tap toggles it with a tick.
 */
@Composable
private fun SteerChip(mode: StreamingBehavior, onMode: (StreamingBehavior) -> Unit) {
    val colors = Pi.colors
    val haptics = Pi.haptics
    val steer = mode == StreamingBehavior.Steer
    Chip(
        onClick = {
            haptics.tick()
            onMode(if (steer) StreamingBehavior.FollowUp else StreamingBehavior.Steer)
        },
        modifier = Modifier.semantics {
            contentDescription = if (steer) "Steer: sent now, while Pi works" else "Follow-up: sent when Pi finishes"
            stateDescription = if (steer) "Steer" else "Follow-up"
        },
    ) {
        Box(Modifier.size(6.dp).background(if (steer) colors.warning else colors.ink4, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text(if (steer) "Steer" else "Follow-up", style = Pi.type.label, color = colors.ink2, maxLines = 1)
        Icon(Icons.Rounded.UnfoldMore, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(16.dp))
    }
}

/**
 * Attached images above the text: 56dp rounded thumbnails, a Quad while each encodes, and a remove ✕ (20dp visual)
 * centred 4dp inside each thumbnail's top-end corner. Its 48dp target is centred on the ✕, so it covers only the
 * corner quarter of the thumbnail (before, it covered half of it and most taps meant to open an image removed it).
 */
@Composable
private fun ImageTray(images: List<ComposerImage>, onRemove: (Long) -> Unit, onOpen: (Int) -> Unit, start: Dp) {
    val colors = Pi.colors
    val shape = RoundedCornerShape(Radius.sm)
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = start, end = 12.dp),
    ) {
        images.forEachIndexed { index, image ->
            key(image.id) {
                // 56dp thumbnail + 20dp for the remove target's overhang: the ✕ centre is 4dp inside the corner.
                Box(Modifier.size(TRAY_ITEM)) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .size(TRAY_THUMB)
                            .clip(shape)
                            .pressable({ if (image.ready) onOpen(index) }, shape = shape, enabled = image.ready, label = "Open image")
                            .semantics { contentDescription = if (image.ready) "Image ${index + 1} of ${images.size}" else "Image ${index + 1}, preparing" }
                            .background(colors.surface2)
                            .border(1.dp, colors.line, shape),
                        contentAlignment = Alignment.Center,
                    ) {
                        val thumb = image.thumb
                        if (thumb != null) {
                            ImageThumb(thumb, Modifier.fillMaxSize())
                        } else {
                            QuadSpinner(QuadMode.Write, size = 16.dp, color = colors.ink2)
                        }
                    }
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(ComposerLayout.TARGET)
                            .pressable({ onRemove(image.id) }, shape = CircleShape, pressedScale = 0.92f, label = "Remove")
                            .semantics { contentDescription = "Remove image ${index + 1}" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(20.dp)
                                .background(colors.scrim, CircleShape)
                                .border(1.dp, colors.lineStrong, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = null, tint = colors.ink1, modifier = Modifier.size(IconSize.xs))
                        }
                    }
                }
            }
        }
    }
}

private val TRAY_THUMB = 56.dp
private val TRAY_ITEM = 76.dp

/** The slash-command and `@` panel's tallest height (about five rows). */
val SUGGESTIONS_MAX = 264.dp

/**
 * The composer's geometry, after the desktop composer and assistant-ui: every button is a [TARGET] on one centre line;
 * Send, Stop and the mic's listening state share one [DISC]. Pure, so it is unit-tested.
 */
object ComposerLayout {
    /** Touch target of every composer button. */
    val TARGET = 48.dp

    /** Send, Stop and the listening mic: the desktop's 30px circle, assistant-ui's 32px. */
    val DISC = 32.dp

    /**
     * Padding between the card's edge and the end and bottom of its button row. With the target's own
     * (48 - 32) / 2 = 8dp, the send disc sits 10dp from the end and the bottom edge alike (the desktop bar's 10px).
     */
    val EDGE = 2.dp

    /** The landscape row's field side padding, between the buttons' targets and the text. */
    val ROW_FIELD_SIDE = 4.dp

    /** Windows narrower than this cannot fit the field beside +, mic, Steer, Stop and Send in the one-row form. */
    val NARROW = 420.dp

    /**
     * Top and bottom padding of the one-row field: (48 - line) / 2, at least 4dp. One line of [lineHeight] is then
     * centred on the buttons' target, and with more lines the last one stays on it.
     */
    fun fieldPad(lineHeight: Dp): Dp = ((TARGET - lineHeight) / 2).coerceAtLeast(4.dp)

    /** The + shows unless the landscape-keyboard row in a narrow window needs its room for Steer and Stop. */
    fun showAttach(compact: Boolean, narrow: Boolean, steering: Boolean): Boolean = !(compact && narrow && steering)

    /** The suggestion row after [delta] arrow presses from [current] (-1: none yet), wrapping round [count] rows. */
    fun moveHighlight(current: Int, delta: Int, count: Int): Int {
        if (count <= 0) return -1
        if (current < 0) return if (delta > 0) 0 else count - 1
        return Math.floorMod(current + delta, count)
    }
}

/** What the suggestion panel showed last (plain fields: written in composition, read while it animates away). */
private class ShownSuggestions {
    var mention: FileMentions.Token? = null
        private set
    var paths: List<String>? = null
        private set
    var commands: List<PaletteEntry> = emptyList()
        private set

    fun set(mention: FileMentions.Token?, paths: List<String>?, commands: List<PaletteEntry>) {
        this.mention = mention
        this.paths = paths
        this.commands = commands
    }
}

/** Lines the composer grows to before it scrolls. */
const val MAX_LINES = 6

/** Hardware-keyboard shortcuts of the composer. */
object ComposerKeys {
    enum class Action { Send, FollowUp, Stop, CycleModel, CycleThinking, OpenModels, Search, RestoreQueue, HistoryOlder, HistoryNewer, Paste, SuggestPrevious, SuggestNext, SuggestPick, SuggestDismiss }

    /**
     * [firstLine] / [lastLine]: the cursor (no selection) is on the field's first / last line, so Up / Down walk the
     * prompt history instead of moving the cursor. [suggesting]: the slash-command or `@` panel is open, so Up / Down
     * move through it, Tab picks, Esc closes it, and Enter picks once an arrow chose a row ([highlighted]); before that
     * Enter still adds a line (a soft keyboard's Enter arrives as a key too).
     */
    fun action(
        key: Key,
        ctrl: Boolean,
        shift: Boolean,
        running: Boolean,
        hasContent: Boolean,
        alt: Boolean = false,
        firstLine: Boolean = false,
        lastLine: Boolean = false,
        suggesting: Boolean = false,
        highlighted: Boolean = false,
    ): Action? {
        val enter = key == Key.Enter || key == Key.NumPadEnter
        val plain = !ctrl && !alt && !shift
        return when {
            enter && ctrl -> Action.Send
            enter && alt && !shift -> Action.FollowUp
            suggesting && plain && key == Key.DirectionUp -> Action.SuggestPrevious
            suggesting && plain && key == Key.DirectionDown -> Action.SuggestNext
            suggesting && plain && (key == Key.Tab || (enter && highlighted)) -> Action.SuggestPick
            suggesting && key == Key.Escape -> Action.SuggestDismiss
            key == Key.Escape && running && !hasContent -> Action.Stop
            key == Key.P && ctrl && !shift -> Action.CycleModel
            key == Key.Tab && shift -> Action.CycleThinking
            key == Key.L && ctrl && !shift -> Action.OpenModels
            key == Key.F && ctrl && shift -> Action.Search
            key == Key.V && ctrl && !shift -> Action.Paste
            key == Key.DirectionUp && alt && !ctrl -> Action.RestoreQueue
            key == Key.DirectionUp && !alt && !ctrl && !shift && firstLine -> Action.HistoryOlder
            key == Key.DirectionDown && !alt && !ctrl && !shift && lastLine -> Action.HistoryNewer
            else -> null
        }
    }
}

/** The thinking chip: "Thinking: medium", "Thinking off". */
fun thinkingChipLabel(level: String?): String = when {
    level.isNullOrBlank() -> "Thinking"
    level == "off" -> "Thinking off"
    else -> "Thinking: $level"
}

/**
 * The composer's run-settings chip: the model, then " · High" for the thinking level. The level is left out when the
 * model does not reason (null) or thinking is off: a chip reading "· Off" says nothing useful, and the sheet shows it.
 */
data class ModelChipText(val model: String, val level: String?, val description: String) {
    companion object {
        fun of(model: String?, thinkingLevel: String?): ModelChipText {
            val name = model?.takeIf { it.isNotBlank() } ?: "Model"
            val level = thinkingLevel?.trim()?.takeIf { it.isNotEmpty() && !it.equals("off", ignoreCase = true) }?.replaceFirstChar { it.uppercase() }
            val spoken = buildString {
                append("Model: ").append(model?.takeIf { it.isNotBlank() } ?: "not set")
                when {
                    level != null -> append(", thinking ").append(level.lowercase())
                    thinkingLevel.equals("off", ignoreCase = true) -> append(", thinking off")
                }
            }
            return ModelChipText(name, level, spoken)
        }
    }
}

/**
 * Send / Stop in the end slot, as on the desktop: a 32dp disc in a 48dp target ([ComposerLayout]). Send is the accent
 * circle (white in the default theme) with an onAccent arrow; disabled, the same circle at 18%. Stop: [StopDisc], the
 * same diameter in the same place.
 */
@Composable
private fun SendButton(mode: SendMode, enabled: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    val colors = Pi.colors
    val haptics = Pi.haptics
    val stop = mode == SendMode.Stop
    val container by animateColorAsState(if (stop) colors.surface3 else colors.accent, PiMotion.effects(), label = "send")
    val edge by animateColorAsState(if (stop) colors.lineStrong else colors.accent, PiMotion.effects(), label = "sendEdge")
    val alpha by animateFloatAsState(if (stop || enabled) 1f else DISABLED_SEND, PiMotion.effects(), label = "sendAlpha")
    val label = if (stop) "Stop" else "Send"
    Box(
        Modifier
            .size(ComposerLayout.TARGET)
            .pressable(
                onClick = {
                    if (stop) {
                        haptics.confirm()
                        onStop()
                    } else {
                        haptics.send()
                        onSend()
                    }
                },
                shape = CircleShape,
                enabled = enabled,
                pressedScale = 0.92f,
                label = label,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(ComposerLayout.DISC)
                .graphicsLayer { this.alpha = alpha }
                .background(container, CircleShape)
                .border(1.dp, edge, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // Centred in the disc for the whole swap: AnimatedContent aligns to the top start by default, so the
            // glyphs slid off-centre while the two sizes crossfaded.
            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    (fadeIn(tween(PiMotion.TAP)) + scaleIn(PiMotion.fast(), initialScale = 0.6f)) togetherWith
                        (fadeOut(tween(PiMotion.TAP)) + scaleOut(tween(PiMotion.TAP), targetScale = 0.6f))
                },
                contentAlignment = Alignment.Center,
                label = "sendMode",
            ) { m ->
                if (m == SendMode.Stop) {
                    StopGlyph()
                } else {
                    // The arrow's bounds are centred, but its head carries most of the ink, so it reads high: 0.5dp
                    // down puts its visual centre on the disc's.
                    Icon(Icons.Rounded.ArrowUpward, contentDescription = null, tint = colors.onAccent, modifier = Modifier.offset(y = ARROW_NUDGE).size(IconSize.md))
                }
            }
        }
    }
}

/** The disabled send circle's opacity (the desktop's 0.18). */
private const val DISABLED_SEND = 0.18f

private val ARROW_NUDGE = 0.5.dp

/** A 1dp accent band sweeping the composer's top edge while the agent works. */
@Composable
private fun Modifier.workingHairline(active: Boolean, color: Color): Modifier {
    val shown by animateFloatAsState(if (active) 1f else 0f, tween(PiMotion.QUICK), label = "hairline")
    if (!active && shown == 0f) return this
    val x = rememberInfiniteTransition(label = "hairline").animateFloat(0f, 1f, infiniteRepeatable(tween(PiMotion.SWEEP, easing = PiMotion.InPlace), RepeatMode.Restart), label = "x")
    return drawWithContent {
        drawContent()
        val band = size.width * 0.4f
        val left = -band + (size.width + band) * x.value
        // Inset 16dp, as on the desktop, so the band stays inside the rounded border.
        val inset = 16.dp.toPx()
        drawRoundRect(
            Brush.horizontalGradient(0f to Color.Transparent, 0.5f to color, 1f to Color.Transparent, startX = left, endX = left + band),
            topLeft = Offset(inset, 0f),
            size = Size(size.width - inset * 2, 1.dp.toPx()),
            cornerRadius = CornerRadius(0.5f),
            alpha = shown,
        )
    }
}
