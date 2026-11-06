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
import opensec.pibolt.app.ui.components.Chip
import opensec.pibolt.app.ui.components.Segmented
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.protocol.rpc.StreamingBehavior
import opensec.pibolt.protocol.session.SlashCommand

private enum class SendMode { Send, Stop }

/**
 * The composer: surface1, radius 18, 1dp line that becomes the focus line, 1–8 lines of text, then a bar with the
 * model and thinking chips and the send button. While the agent works the send button turns into Stop when the field
 * is empty, and a Steer | Follow-up control appears while typing.
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
    commands: List<SlashCommand>,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onModel: () -> Unit,
    onThinking: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Pi.colors
    val type = Pi.type
    val reduced = Pi.reduceMotion
    val suggestions = remember(text, commands) { slashSuggestions(text, commands) }
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        AnimatedVisibility(
            visible = suggestions.isNotEmpty(),
            enter = expandVertically(PiMotion.enter(PiMotion.STANDARD)) + fadeIn(PiMotion.enter(PiMotion.QUICK)),
            exit = shrinkVertically(PiMotion.leave(PiMotion.STANDARD)) + fadeOut(PiMotion.leave(PiMotion.QUICK)),
        ) {
            val shape = RoundedCornerShape(Radius.md)
            Column(
                Modifier
                    .padding(bottom = 8.dp)
                    .fillMaxWidth()
                    .background(colors.surface2, shape)
                    .border(1.dp, colors.line, shape)
                    .padding(vertical = 4.dp),
            ) {
                for (command in suggestions) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .pressable({ onText("/${command.name} ") }, shape = RoundedCornerShape(Radius.sm), pressedScale = 1f)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("/${command.name}", style = type.monoSmall, color = colors.ink1, maxLines = 1)
                        Spacer(Modifier.width(12.dp))
                        Text(command.description.orEmpty(), style = type.meta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = running && text.isNotBlank(),
            enter = expandVertically(PiMotion.enter(PiMotion.STANDARD)) + fadeIn(PiMotion.enter(PiMotion.QUICK)),
            exit = shrinkVertically(PiMotion.leave(PiMotion.STANDARD)) + fadeOut(PiMotion.leave(PiMotion.QUICK)),
        ) {
            Segmented(
                options = listOf(StreamingBehavior.Steer to "Steer", StreamingBehavior.FollowUp to "Follow-up"),
                selected = mode,
                onSelect = onMode,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }

        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        val border by animateColorAsState(if (focused) colors.lineFocus else colors.lineStrong, PiMotion.effects(), label = "composer")
        val shape = RoundedCornerShape(Radius.lg)
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.surface1, shape)
                .border(1.dp, border, shape)
                .workingHairline(running && !reduced, colors.accent)
                .animateContentSize(PiMotion.inPlace(PiMotion.QUICK)),
        ) {
            BasicTextField(
                value = text,
                onValueChange = onText,
                enabled = enabled,
                textStyle = type.prose.copy(color = colors.ink1, lineHeight = type.body.lineHeight * 1.05f),
                cursorBrush = SolidColor(colors.accent),
                maxLines = 8,
                interactionSource = interaction,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
                        if (text.isEmpty()) {
                            Text(
                                when {
                                    !enabled -> "Not connected"
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
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(start = 10.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Chip(onClick = onModel, enabled = enabled) {
                    Text(modelLabel ?: "Model", style = type.label, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(8.dp))
                Chip(onClick = onThinking, enabled = enabled) {
                    Text(thinkingChipLabel(thinkingLevel), style = type.label, color = colors.ink1, maxLines = 1)
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.weight(1f))
                SendButton(
                    mode = if (running && text.isBlank()) SendMode.Stop else SendMode.Send,
                    enabled = enabled && (text.isNotBlank() || running),
                    onSend = onSend,
                    onStop = onStop,
                )
            }
        }
    }
}

fun thinkingChipLabel(level: String?): String = when {
    level.isNullOrBlank() -> "Thinking"
    level == "off" -> "Thinking off"
    else -> level.replaceFirstChar { it.uppercase() }
}

@Composable
private fun SendButton(mode: SendMode, enabled: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    val colors = Pi.colors
    val haptics = Pi.haptics
    val container by animateColorAsState(
        when {
            mode == SendMode.Stop -> colors.surface3
            enabled -> colors.accent
            else -> colors.accent.copy(alpha = 0.2f)
        },
        PiMotion.effects(),
        label = "send",
    )
    Box(
        Modifier
            .size(48.dp)
            .pressable(
                onClick = {
                    if (mode == SendMode.Stop) {
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
                label = if (mode == SendMode.Stop) "Stop" else "Send",
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(40.dp).background(container, CircleShape), contentAlignment = Alignment.Center) {
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
                    Icon(Icons.Rounded.ArrowUpward, contentDescription = null, tint = if (enabled) colors.onAccent else colors.ink3, modifier = Modifier.size(20.dp))
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
