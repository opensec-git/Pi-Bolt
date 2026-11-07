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
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.material.icons.rounded.UnfoldMore
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
 * The composer: surface1, radius 18, 1dp line that becomes the focus line, a tray of attached images, 1–8 lines of
 * text, then a bar with attach, the model and thinking chips and the send button. While the agent works the send
 * button turns into Stop when the field is empty, and a Steer | Follow-up control appears while typing.
 *
 * [attachAllowed] false greys the attach button (the model cannot read images); a tap still calls [onAttach] so the
 * screen can say why.
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
    onThinking: () -> Unit,
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
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        AnimatedVisibility(
            visible = commands.isNotEmpty() || mention != null,
            enter = expandVertically(PiMotion.enter(PiMotion.STANDARD)) + fadeIn(PiMotion.enter(PiMotion.QUICK)),
            exit = shrinkVertically(PiMotion.leave(PiMotion.STANDARD)) + fadeOut(PiMotion.leave(PiMotion.QUICK)),
        ) {
            when {
                mention != null -> MentionList(mentionPaths, mention.query, onPick = { path ->
                    haptics.tick()
                    val (next, cursor) = FileMentions.insert(value.text, mention, path)
                    field = TextFieldValue(next, TextRange(cursor))
                    onText(next)
                })
                commands.isNotEmpty() -> CommandList(commands, onPick = {
                    haptics.tick()
                    onPick(it)
                })
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
        val border by animateColorAsState(if (focused) colors.lineFocus else colors.line, PiMotion.effects(), label = "composer")
        val shape = RoundedCornerShape(Radius.lg)
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.surface1, shape)
                .border(1.dp, border, shape)
                .workingHairline(running && !reduced, colors.accent)
                .animateContentSize(PiMotion.inPlace(PiMotion.QUICK)),
        ) {
            if (images.isNotEmpty()) ImageTray(images, onRemoveImage, onOpenImage)
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
                textStyle = type.prose.copy(color = colors.ink1, lineHeight = type.body.lineHeight * 1.05f),
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
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val cursor = value.selection.start
                        val action = ComposerKeys.action(
                            e.key,
                            ctrl = e.isCtrlPressed || e.isMetaPressed,
                            shift = e.isShiftPressed,
                            running = running,
                            hasContent = hasContent,
                            alt = e.isAltPressed,
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
                            null -> return@onPreviewKeyEvent false
                        }
                        true
                    },
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().padding(start = if (compact) 4.dp else 16.dp, end = if (compact) 4.dp else 16.dp, top = 12.dp, bottom = if (compact) 12.dp else 6.dp)) {
                        if (text.isEmpty()) {
                            Text(
                                when {
                                    !enabled -> "Not connected"
                                    shellMode -> "Shell command…"
                                    running && mode == StreamingBehavior.Steer -> "Steer Pi…"
                                    running -> "Queue a follow-up…"
                                    else -> "Message Pi…"
                                },
                                style = type.prose,
                                color = colors.ink3,
                                maxLines = 1,
                            )
                        }
                        inner()
                    }
                },
            ) }
            val sendMode = if (running && !hasContent) SendMode.Stop else SendMode.Send
            val sendEnabled = enabled && if (hasContent) !encoding else running
            val attach = @Composable {
                IconAction(
                    Icons.Rounded.Add,
                    description = if (attachAllowed) "Attach or insert" else "Attach or insert (images not available for this model)",
                    onClick = onAttach,
                    enabled = enabled,
                    tint = colors.ink2,
                    size = 22.dp,
                )
            }
            // The field keeps one place in the composition in both forms, so switching to the one-row form (the
            // keyboard opening in landscape) never drops its focus and the keyboard with it.
            Row(Modifier.fillMaxWidth().padding(start = if (compact) 2.dp else 0.dp, end = if (compact) 6.dp else 0.dp), verticalAlignment = Alignment.Bottom) {
                if (compact) attach()
                textField(Modifier.weight(1f))
                if (compact) {
                    if (running && hasContent) {
                        SteerChip(mode, onMode)
                        StopButton(onStop)
                    }
                    SendButton(sendMode, sendEnabled, onSend, onStop, ring = running && !hasContent)
                }
            }
            if (!compact) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(start = 2.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    attach()
                    // The chips share what the attach and send buttons leave; the model name gives way first. While
                    // a steer is typed the Steer / Follow-up chip takes the thinking chip's place.
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Chip(onClick = onModel, enabled = enabled, onLongClick = onCycleModel, longClickLabel = "Next model", modifier = Modifier.weight(1f, fill = false).semantics { contentDescription = "Model: ${modelLabel ?: "not set"}" }) {
                            Text(modelLabel ?: "Model", style = type.label, color = colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).widthIn(max = 160.dp))
                            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(16.dp))
                        }
                        if (running && hasContent) {
                            Spacer(Modifier.weight(1f, fill = true))
                            SteerChip(mode, onMode)
                        } else if (thinkingLevel != null) {
                            Spacer(Modifier.width(2.dp))
                            val short = LocalDensity.current.fontScale >= 1.5f
                            Chip(onClick = onThinking, enabled = enabled, onLongClick = onCycleThinking, longClickLabel = "Next thinking level", modifier = Modifier.semantics { contentDescription = thinkingChipLabel(thinkingLevel) }) {
                                Text(if (short) thinkingShortLabel(thinkingLevel) else thinkingChipLabel(thinkingLevel), style = type.label, color = colors.ink2, maxLines = 1)
                                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    // While a steer is typed Send takes the button: Stop stays one tap away beside it.
                    if (running && hasContent) StopButton(onStop)
                    SendButton(sendMode, sendEnabled, onSend, onStop, ring = running && !hasContent)
                }
            }
        }
    }
}

/** Stop while the field has text: a 32dp surface3 disc with an ink1 square, 48dp to touch. */
@Composable
private fun StopButton(onStop: () -> Unit) {
    val colors = Pi.colors
    val haptics = Pi.haptics
    Box(
        Modifier
            .size(48.dp)
            .pressable({
                haptics.confirm()
                onStop()
            }, shape = CircleShape, pressedScale = 0.92f, label = "Stop")
            .semantics { contentDescription = "Stop" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(32.dp).background(colors.surface3, CircleShape).border(1.dp, colors.ink3, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(10.dp).background(colors.ink1, RoundedCornerShape(2.dp)))
        }
    }
}

/**
 * Steer or Follow-up for what is typed while Pi works: a 32dp chip next to send (warning dot for Steer, ink4 dot for
 * Follow-up). Tap toggles it with a tick.
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
 * Attached images above the text: 56dp rounded thumbnails, a Quad while each encodes, and a remove ✕ at the top-end
 * corner (20dp visual, 48dp to touch, overlapping the thumbnail).
 */
@Composable
private fun ImageTray(images: List<ComposerImage>, onRemove: (Long) -> Unit, onOpen: (Int) -> Unit) {
    val colors = Pi.colors
    val shape = RoundedCornerShape(Radius.sm)
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        images.forEachIndexed { index, image ->
            key(image.id) {
                Box(Modifier.size(64.dp)) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .size(56.dp)
                            .clip(shape)
                            .pressable({ if (image.ready) onOpen(index) }, shape = shape, label = "Open image ${index + 1}")
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
                            .size(48.dp)
                            .pressable({ onRemove(image.id) }, shape = CircleShape, pressedScale = 0.92f, label = "Remove image"),
                        contentAlignment = Alignment.TopEnd,
                    ) {
                        Box(
                            Modifier
                                .size(20.dp)
                                .background(colors.scrim, CircleShape)
                                .border(1.dp, colors.lineStrong, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = null, tint = colors.ink1, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Lines the composer grows to before it scrolls. */
const val MAX_LINES = 6

/** Hardware-keyboard shortcuts of the composer. */
object ComposerKeys {
    enum class Action { Send, FollowUp, Stop, CycleModel, CycleThinking, OpenModels, Search, RestoreQueue, HistoryOlder, HistoryNewer, Paste }

    /**
     * [firstLine] / [lastLine]: the cursor (no selection) is on the field's first / last line, so Up / Down walk the
     * prompt history instead of moving the cursor.
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
    ): Action? {
        val enter = key == Key.Enter || key == Key.NumPadEnter
        return when {
            enter && ctrl -> Action.Send
            enter && alt && !shift -> Action.FollowUp
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

/** At very large text the chip keeps only the level ("Medium", "Off"). */
fun thinkingShortLabel(level: String?): String = level?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: "Thinking"

/**
 * Send / Stop, 40dp in a 48dp target. Send enabled: accent fill, onAccent arrow. Disabled: no fill, a 1dp lineStrong
 * ring and an ink4 arrow, so it never reads as pressable in a coloured accent. Stop: surface3 fill with an ink1
 * square; [ring] adds a 1dp ink3 ring so Stop is easy to find mid-run.
 */
@Composable
private fun SendButton(mode: SendMode, enabled: Boolean, onSend: () -> Unit, onStop: () -> Unit, ring: Boolean = false) {
    val colors = Pi.colors
    val haptics = Pi.haptics
    val stop = mode == SendMode.Stop
    val container by animateColorAsState(
        when {
            stop -> colors.surface3
            enabled -> colors.accent
            else -> colors.accent.copy(alpha = 0f)
        },
        PiMotion.effects(),
        label = "send",
    )
    val edge by animateColorAsState(
        when {
            stop -> if (ring) colors.ink3 else colors.surface3
            enabled -> colors.accent
            else -> colors.lineStrong
        },
        PiMotion.effects(),
        label = "sendEdge",
    )
    val label = if (stop) "Stop" else "Send"
    Box(
        Modifier
            .size(48.dp)
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
        Box(Modifier.size(40.dp).background(container, CircleShape).border(1.dp, edge, CircleShape), contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    (fadeIn(tween(PiMotion.TAP)) + scaleIn(PiMotion.fast(), initialScale = 0.6f)) togetherWith
                        (fadeOut(tween(PiMotion.TAP)) + scaleOut(tween(PiMotion.TAP), targetScale = 0.6f))
                },
                label = "sendMode",
            ) { m ->
                if (m == SendMode.Stop) {
                    Box(Modifier.size(12.dp).background(colors.ink1, RoundedCornerShape(3.dp)))
                } else {
                    Icon(Icons.Rounded.ArrowUpward, contentDescription = null, tint = if (enabled) colors.onAccent else colors.ink4, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** A 1dp accent band sweeping the composer's top edge while the agent works. */
@Composable
private fun Modifier.workingHairline(active: Boolean, color: Color): Modifier {
    val shown by animateFloatAsState(if (active) 1f else 0f, tween(PiMotion.QUICK), label = "hairline")
    if (!active && shown == 0f) return this
    val x = rememberInfiniteTransition(label = "hairline").animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = PiMotion.InPlace), RepeatMode.Restart), label = "x")
    return drawWithContent {
        drawContent()
        val band = size.width * 0.35f
        val left = -band + (size.width + band) * x.value
        val r = Radius.lg.toPx()
        drawRoundRect(
            Brush.horizontalGradient(0f to Color.Transparent, 0.5f to color, 1f to Color.Transparent, startX = left, endX = left + band),
            topLeft = Offset(r * 0.6f, 0f),
            size = Size(size.width - r * 1.2f, 1.dp.toPx()),
            cornerRadius = CornerRadius(0.5f),
            alpha = shown,
        )
    }
}
