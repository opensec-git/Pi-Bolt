package opensec.pibolt.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.NavigateNext
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Space

/** Inside a [CardSection] the card pads its rows; in sheets and on canvas rows bring the 20dp text inset. */
val RowInsetSheet = PaddingValues(horizontal = Space.xl, vertical = Space.sm)
val RowInsetCard = PaddingValues(vertical = Space.xs)

/** Rows inset by their card (no side padding of their own) round their press overlay; full-bleed rows do not. */
private fun pressShape(padding: PaddingValues) =
    RoundedCornerShape(if (padding.calculateLeftPadding(LayoutDirection.Ltr) == 0.dp) Radius.sm else 0.dp)

/** What sits at the end of a [ListRow]. */
enum class RowEnd { None, Chevron, External }

/**
 * The app's list row: optional 20dp leading glyph (ink3) or [leading] slot, the title (body ink1), an optional meta line
 * (meta, [metaColor]), then [trailing] or a chevron / open-in-new glyph. At least 56dp tall; the whole row is the
 * target. Rows never scale when pressed (only the ink overlay).
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Pi.colors.ink3,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    metaColor: Color = Pi.colors.ink3,
    titleStyle: TextStyle = Pi.type.body,
    titleMaxLines: Int = 2,
    metaMaxLines: Int = 2,
    end: RowEnd = RowEnd.None,
    padding: PaddingValues = RowInsetSheet,
    minHeight: Dp = 56.dp,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = Pi.colors
    val base = modifier.fillMaxWidth().heightIn(min = minHeight)
    Row(
        (
            if (onClick != null) {
                base.pressable(onClick, shape = pressShape(padding), enabled = enabled, pressedScale = 1f, onLongClick = onLongClick, label = title)
            } else {
                base
            }
            ).padding(padding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            leading != null -> {
                leading()
                Spacer(Modifier.width(Space.md + 2.dp))
            }
            icon != null -> {
                Icon(icon, contentDescription = null, tint = if (enabled) iconTint else iconTint.copy(alpha = 0.4f), modifier = Modifier.size(IconSize.md))
                Spacer(Modifier.width(Space.md + 2.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = titleStyle, color = if (enabled) colors.ink1 else colors.ink3, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
            if (!meta.isNullOrBlank()) Text(meta, style = Pi.type.meta, color = metaColor, maxLines = metaMaxLines, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(Space.sm))
            trailing()
        }
        if (end != RowEnd.None) {
            Spacer(Modifier.width(Space.sm))
            Icon(
                if (end == RowEnd.External) Icons.AutoMirrored.Rounded.OpenInNew else Icons.AutoMirrored.Rounded.NavigateNext,
                contentDescription = null,
                tint = colors.ink3,
                modifier = Modifier.size(if (end == RowEnd.External) IconSize.sm + 2.dp else IconSize.md),
            )
        }
    }
}

/** A switch row: the whole row toggles, TalkBack reads it as one switch with its On / Off state. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    meta: String? = null,
    enabled: Boolean = true,
    padding: PaddingValues = RowInsetSheet,
) {
    val colors = Pi.colors
    val haptics = Pi.haptics
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .pressable({
                haptics.toggle(!checked)
                onChange(!checked)
            }, shape = pressShape(padding), pressedScale = 1f, enabled = enabled, role = Role.Switch, label = title)
            .semantics { toggleableState = ToggleableState(checked) }
            .padding(padding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = Space.md)) {
            Text(title, style = Pi.type.body, color = if (enabled) colors.ink1 else colors.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!meta.isNullOrBlank()) Text(meta, style = Pi.type.meta, color = colors.ink3, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        PiSwitch(checked, enabled = enabled)
    }
}

/** Material's switch in the app's colours: accent track when on, surface3 with a lineStrong ring when off. */
@Composable
fun PiSwitch(checked: Boolean, enabled: Boolean = true, onCheckedChange: ((Boolean) -> Unit)? = null) {
    val colors = Pi.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = colors.onAccent,
            checkedTrackColor = colors.accent,
            checkedBorderColor = colors.accent,
            uncheckedThumbColor = colors.ink2,
            uncheckedTrackColor = colors.surface3,
            uncheckedBorderColor = colors.lineStrong,
            disabledCheckedThumbColor = colors.surface3,
            disabledCheckedTrackColor = colors.ink4,
            disabledUncheckedThumbColor = colors.ink4,
            disabledUncheckedTrackColor = colors.surface2,
            disabledUncheckedBorderColor = colors.line,
        ),
    )
}

/** Card: surface1, 1dp line, radius md (12dp) by default. Elevation is a surface step plus a line, never a shadow. */
@Composable
fun PiCard(
    modifier: Modifier = Modifier,
    radius: Dp = Radius.md,
    padding: PaddingValues = PaddingValues(Space.lg),
    fill: Color = Pi.colors.surface1,
    line: Color = Pi.colors.line,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .fillMaxWidth()
            .background(fill, shape)
            .border(1.dp, line, shape)
            .padding(padding),
        content = content,
    )
}

/**
 * A titled group (Settings, Runtimes): a [SectionLabel] (with an optional 14dp glyph) over a radius-md card. Rows inside use [RowInsetCard], with
 * the card's 16dp side padding and 8dp top and bottom (rows bring their own height; text blocks add 4dp more).
 */
@Composable
fun CardSection(title: String, modifier: Modifier = Modifier, icon: ImageVector? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm + 2.dp)) {
        SectionLabel(title, Modifier.padding(bottom = Space.sm), icon = icon)
        PiCard(radius = Radius.md, padding = PaddingValues(horizontal = Space.lg, vertical = Space.sm), content = content)
    }
}

/** A 1dp line between rows of a card or list. */
@Composable
fun RowDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Pi.colors.line))
}

/** A small badge: micro text on surface3, radius xs. For a source or state, never an action. */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, color: Color = Pi.colors.ink2, fill: Color = Pi.colors.surface3) {
    Box(
        modifier
            .background(fill, RoundedCornerShape(Radius.xs))
            .padding(horizontal = 6.dp, vertical = Space.xxs)
            .clearAndSetSemantics { contentDescription = text },
    ) {
        Text(text, style = Pi.type.micro, color = color, maxLines = 1)
    }
}

/**
 * A read-only fact: the label (body ink2) and its value (body ink1, or [valueStyle]) at the end, 48dp. The value
 * ellipsizes; the label never does. TalkBack reads "label, value".
 */
@Composable
fun ValueRow(label: String, value: String, modifier: Modifier = Modifier, valueStyle: TextStyle = Pi.type.body, valueColor: Color = Pi.colors.ink1) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Pi.type.body, color = Pi.colors.ink2, maxLines = 1)
        Spacer(Modifier.width(Space.lg))
        Text(value, style = valueStyle, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

/** The heading of a control inside a card ("Theme", "Text size"): body ink1 with an optional value in meta ink3. */
@Composable
fun ControlLabel(text: String, modifier: Modifier = Modifier, value: String? = null) {
    Row(modifier.fillMaxWidth().padding(top = Space.md, bottom = Space.sm), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = Pi.type.body, color = Pi.colors.ink1, modifier = Modifier.weight(1f, fill = false))
        if (value != null) {
            Spacer(Modifier.width(Space.sm))
            Text(value, style = Pi.type.meta, color = Pi.colors.ink3)
        }
    }
}
