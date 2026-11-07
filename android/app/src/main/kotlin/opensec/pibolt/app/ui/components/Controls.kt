package opensec.pibolt.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Space
import opensec.pibolt.app.ui.theme.Radius

/**
 * Press feedback without ripple: an ink overlay fades in over [PiMotion.TAP] and the element scales to [pressedScale].
 * Overlay and scale are applied in the layer/draw phase.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.pressable(
    onClick: () -> Unit,
    shape: Shape = RoundedCornerShape(Radius.md),
    enabled: Boolean = true,
    pressedScale: Float = 0.97f,
    onLongClick: (() -> Unit)? = null,
    role: Role? = Role.Button,
    label: String? = null,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduced = Pi.reduceMotion
    val scale = animateFloatAsState(if (pressed && !reduced) pressedScale else 1f, tween(PiMotion.TAP), label = "press")
    val overlay = animateFloatAsState(if (pressed) 1f else 0f, tween(PiMotion.TAP), label = "overlay")
    val press = Pi.colors.press
    return this
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
        .clip(shape)
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = role,
            onClickLabel = label,
            onLongClick = onLongClick,
            onClick = onClick,
        )
        .drawWithContent {
            drawContent()
            val a = overlay.value
            if (a > 0f) drawRect(press, alpha = a)
        }
}

/**
 * Button heights. [Large] (48dp) is the default: sheet and screen actions. [Small] (36dp visual) sits inside rows and
 * cards ("Clear", "Retry"); its touch target is still 48dp. In the redesign buttons are capsules ([Radius.pill]).
 */
enum class ButtonSize(val height: Dp, val padding: Dp, val icon: Dp) {
    Large(48.dp, 16.dp, IconSize.md),
    Small(36.dp, 12.dp, IconSize.sm),
}

/** Primary action: accent fill, pill. One per screen or sheet. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, size: ButtonSize = ButtonSize.Large) {
    val colors = Pi.colors
    if (enabled) {
        ButtonBase(text, onClick, modifier, icon, true, colors.accent, colors.onAccent, null, size)
    } else {
        ButtonBase(text, onClick, modifier, icon, false, colors.surface3, colors.ink3, null, size, dim = false)
    }
}

/** Secondary action: surface2 with a lineStrong ring. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, size: ButtonSize = ButtonSize.Large) {
    val colors = Pi.colors
    ButtonBase(text, onClick, modifier, icon, enabled, colors.surface2, colors.ink1, colors.lineStrong, size)
}

/** Ghost action: no fill or line, ink2 label. The quiet third choice in a button row ("Later", "Not now"). */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, color: Color = Pi.colors.ink2, size: ButtonSize = ButtonSize.Large) {
    ButtonBase(text, onClick, modifier, icon, enabled, Color.Transparent, color, null, size)
}

/** Destructive action: danger soft fill, danger text. Only for the step that destroys something. */
@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, size: ButtonSize = ButtonSize.Large) {
    val colors = Pi.colors
    ButtonBase(text, onClick, modifier, icon, enabled, colors.dangerSoft, colors.danger, null, size)
}

/**
 * Every button: a capsule ([Radius.pill]) of [size]'s height (a small one keeps a 48dp touch target), the label in label
 * weight, optional leading icon 6dp before it. A label too long for one line wraps to two, centred, instead of being
 * cut (font scale 1.3 on a 360dp phone). Press: ink overlay and 0.97 scale. Disabled: content and line at 40% (the
 * primary button switches to surface3 and ink3 instead, so a disabled one never reads as the accent action).
 */
@Composable
private fun ButtonBase(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: ImageVector?,
    enabled: Boolean,
    container: Color,
    content: Color,
    border: Color?,
    size: ButtonSize,
    dim: Boolean = true,
) {
    val shape = RoundedCornerShape(Radius.pill)
    val tint = if (enabled || !dim) content else content.copy(alpha = content.alpha * 0.4f)
    Row(
        modifier
            .then(if (size.height < 48.dp) Modifier.minimumInteractiveComponentSize() else Modifier)
            .heightIn(min = size.height)
            .pressable(onClick, shape = shape, enabled = enabled)
            .background(container, shape)
            .then(if (border != null) Modifier.border(1.dp, if (enabled) border else border.copy(alpha = border.alpha * 0.4f), shape) else Modifier)
            .padding(horizontal = size.padding, vertical = Space.xs),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size.icon))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = Pi.type.label, color = tint, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/**
 * A sheet's or card's button row: the buttons side by side with equal widths, [gap] apart, while every label fits on
 * one line; otherwise stacked full width with the last button (the action) on top. Pass Cancel first, the action
 * last. The buttons take their width from the row, so they need no weight.
 */
@Composable
fun ButtonRow(modifier: Modifier = Modifier, gap: Dp = Space.md, content: @Composable () -> Unit) {
    Layout(content, modifier.fillMaxWidth()) { measurables, constraints ->
        val space = gap.roundToPx()
        val n = measurables.size
        if (n == 0) return@Layout layout(constraints.minWidth, 0) {}
        val width = constraints.maxWidth
        val slot = buttonSlot(width, n, space)
        if (buttonsFitSideBySide(measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }, width, space)) {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(slot)) }
            val height = placeables.maxOf { it.height }
            layout(width, height) {
                placeables.forEachIndexed { i, p -> p.placeRelative(i * (slot + space), (height - p.height) / 2) }
            }
        } else {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(width)) }.reversed()
            val height = placeables.sumOf { it.height } + space * (n - 1)
            layout(width, height) {
                var y = 0
                for (p in placeables) {
                    p.placeRelative(0, y)
                    y += p.height + space
                }
            }
        }
    }
}

/** Low-emphasis text action (40dp tall, 48dp touch). */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = Pi.colors.ink1, enabled: Boolean = true) {
    Box(
        modifier
            .heightIn(min = 40.dp)
            .pressable(onClick, shape = RoundedCornerShape(Radius.pill), enabled = enabled)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Pi.type.label, color = if (enabled) color else color.copy(alpha = 0.4f), maxLines = 1)
    }
}

/** 48dp icon button with the press overlay. */
@Composable
fun IconAction(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Pi.colors.ink2, enabled: Boolean = true, size: Dp = 22.dp) {
    Box(
        modifier
            .size(48.dp)
            .pressable(onClick, shape = CircleShape, enabled = enabled, pressedScale = 0.92f, label = description),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = if (enabled) tint else tint.copy(alpha = 0.4f), modifier = Modifier.size(size))
    }
}

/** The width of each of [count] equal buttons across [width] with [gap] between them. */
internal fun buttonSlot(width: Int, count: Int, gap: Int): Int = if (count <= 0) 0 else ((width - gap * (count - 1)) / count).coerceAtLeast(0)

/** True when every button's one-line width ([natural]) fits its equal share of [width]; else [ButtonRow] stacks. */
internal fun buttonsFitSideBySide(natural: List<Int>, width: Int, gap: Int): Boolean {
    val slot = buttonSlot(width, natural.size, gap)
    return natural.all { it <= slot }
}

/** Composer and filter chip: 32dp, radius 8, surface2. [onLongClick] (with [longClickLabel] for TalkBack) is optional. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Chip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = Pi.colors
    val shape = RoundedCornerShape(Radius.sm)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(if (pressed) colors.surface3 else colors.surface3.copy(alpha = 0f), tween(PiMotion.TAP), label = "chip")
    // A quiet text chip: 32dp visual, 48dp touch target, no border; pressed fills with surface3.
    Box(
        modifier
            .heightIn(min = 48.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onLongClickLabel = longClickLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .height(32.dp)
                .background(fill, shape)
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    }
}

/** Small status dot whose colour changes without overshoot. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    val c by animateColorAsState(color, PiMotion.effects(), label = "dot")
    Box(
        modifier
            .size(size)
            .drawWithContent { drawCircle(c) },
    )
}

/**
 * Two or more options in one control; the selected segment is surface3 with a lineStrong ring and slides between
 * positions. TalkBack reads each as a tab with its Selected state.
 */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier, height: Dp = 36.dp) {
    val colors = Pi.colors
    SegmentedSlots(options.map { it.first }, selected, onSelect, modifier, height, labels = options.map { it.second }) { i, on ->
        Text(options[i].second, style = Pi.type.label, color = if (on) colors.ink1 else colors.ink3, maxLines = 1)
    }
}

/** [Segmented] with custom content per option; [labels] are the accessibility names. */
@Composable
fun <T> SegmentedSlots(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 36.dp,
    labels: List<String>,
    content: @Composable (index: Int, selected: Boolean) -> Unit,
) {
    val colors = Pi.colors
    val haptics = Pi.haptics
    val index = options.indexOfFirst { it == selected }.coerceAtLeast(0)
    val shape = RoundedCornerShape(Radius.sm + 2.dp)
    BoxWithConstraints(
        modifier
            .height(height)
            .background(colors.surface1, shape)
            .border(1.dp, colors.line, shape)
            .padding(Space.xxs)
            .selectableGroup(),
    ) {
        val segment = maxWidth / options.size
        val x by animateDpAsState(segment * index, if (Pi.reduceMotion) tween(0) else PiMotion.inPlace(PiMotion.QUICK), label = "segment")
        Box(
            Modifier
                .offset { IntOffset(x.roundToPx(), 0) }
                .width(segment)
                .fillMaxHeight()
                .background(colors.surface3, RoundedCornerShape(Radius.sm))
                .border(1.dp, colors.lineStrong, RoundedCornerShape(Radius.sm)),
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { i, value ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics { this.selected = i == index }
                        .pressable({
                            if (value != selected) haptics.tick()
                            onSelect(value)
                        }, shape = RoundedCornerShape(Radius.sm), pressedScale = 1f, role = Role.Tab, label = labels.getOrNull(i)),
                    contentAlignment = Alignment.Center,
                ) {
                    content(i, i == index)
                }
            }
        }
    }
}

/**
 * Section heading in lists and settings: metaStrong ink3, with an optional 14dp [icon] in ink3 before it (Settings'
 * groups, as the desktop's settings sections), then read as one heading.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    if (icon == null) {
        Text(text, style = Pi.type.metaStrong, color = Pi.colors.ink3, modifier = modifier.padding(horizontal = 4.dp))
        return
    }
    Row(modifier.padding(horizontal = 4.dp).semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Pi.colors.ink3, modifier = Modifier.size(IconSize.xs))
        Spacer(Modifier.width(Space.sm))
        Text(text, style = Pi.type.metaStrong, color = Pi.colors.ink3)
    }
}

/** Text field: surface1, radius md, 1dp lineStrong that turns into the focus line. */
@Composable
fun PiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    style: TextStyle = Pi.type.body,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    leading: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
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
        label = "field",
    )
    val shape = RoundedCornerShape(Radius.md)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = style.copy(color = colors.ink1),
        cursorBrush = SolidColor(colors.accent),
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        interactionSource = interaction,
        modifier = modifier,
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(colors.surface1, shape)
                    .border(1.dp, border, shape)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) {
                    leading()
                    Spacer(Modifier.width(10.dp))
                }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = style, color = colors.ink3, maxLines = if (singleLine) 1 else minLines.coerceAtLeast(1))
                    inner()
                }
            }
        },
    )
}

/** Canvas behind the status bar so scrolled content never runs under the clock and icons. */
@Composable
fun StatusBarScrim(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars)
            .background(Pi.colors.canvas),
    )
}
