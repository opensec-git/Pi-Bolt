package opensec.pibolt.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiColors
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Space

/**
 * The redesign's grouped list (Settings, Runtimes, the provider and remote-control pages), on the Glass kit's tokens:
 * a large-title page, rounded inset groups and rows with a glyph tile, a value and a chevron. Row type matches
 * [InsetRow] (rowTitle / rowMeta), so a grouped page and a plain list read as one family.
 */

/** The glyph tile of a [GroupRow]: 30dp, radius sm + 2. */
val GROUP_TILE = 30.dp

/** A group row's text inset from the card's edge. */
private val GROUP_INSET = Space.lg

/** Where a group row's text starts with a glyph tile (its divider starts there too). */
private val GROUP_TEXT_START = GROUP_INSET + GROUP_TILE + Space.md

/** What closes a [GroupRow] at its end. */
enum class GroupEnd { None, Chevron, External }

/**
 * A pushed page with a large title (Settings, Runtimes): a glass back button (and [actions]) on a 56dp bar under the
 * status bar, then the scrolling [content] that starts with the title in largeTitle. Once the title scrolls under the
 * bar, the bar shows it small and centred with a hairline under it. [overlay] draws over the page (snackbars, a viewer).
 */
@Composable
fun LargeTitlePage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    scroll: ScrollState = rememberScrollState(),
    backLabel: String = "Back",
    actions: (@Composable RowScope.() -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Pi.colors
    val type = Pi.type
    val titleHeight = with(LocalDensity.current) { 40.dp.toPx() }
    val collapsed by remember(scroll) { derivedStateOf { scroll.value > titleHeight } }
    val small by animateFloatAsState(if (collapsed) 1f else 0f, PiMotion.inPlace(PiMotion.QUICK), label = "page-title")
    Box(modifier.fillMaxSize().background(colors.canvas)) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().background(colors.canvas).statusBarsPadding()) {
                Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = GlassBarInset)) {
                    GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, backLabel, onClick = onBack, modifier = Modifier.align(Alignment.CenterStart))
                    Text(
                        title,
                        style = type.subhead,
                        color = colors.ink1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 64.dp)
                            .graphicsLayer { alpha = small }
                            .clearAndSetSemantics {},
                    )
                    if (actions != null) Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = actions)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).graphicsLayer { alpha = small }.background(colors.line))
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(bottom = Space.x4),
            ) {
                Text(
                    title,
                    style = type.largeTitle,
                    color = colors.ink1,
                    modifier = Modifier
                        .padding(start = Space.page, end = Space.page, top = Space.xs, bottom = Space.sm)
                        .semantics { heading() },
                )
                content()
            }
        }
        overlay()
    }
}

/**
 * A rounded inset group: an optional [header] (rowMeta ink3, a heading), the rows on a surface1 card (radius tile,
 * 1dp line, clipped so a row's press fills its own band), and an optional [footer] (meta ink3) that explains it.
 * The card sits 16dp from the window's sides.
 */
@Composable
fun InsetGroup(modifier: Modifier = Modifier, header: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val shape = RoundedCornerShape(Radius.tile)
    Column(modifier.fillMaxWidth().padding(start = Space.lg, end = Space.lg, top = Space.lg, bottom = Space.xs)) {
        if (header != null) {
            Text(header, style = type.rowMeta, color = colors.ink3, modifier = Modifier.padding(start = GROUP_INSET, end = GROUP_INSET, bottom = Space.sm).semantics { heading() })
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.surface1, shape)
                .border(1.dp, colors.line, shape),
            content = content,
        )
        if (footer != null) {
            Text(footer, style = type.meta, color = colors.ink3, modifier = Modifier.padding(start = GROUP_INSET, end = GROUP_INSET, top = Space.sm))
        }
    }
}

/**
 * A group's glyph tile, monochrome: [icon] (18dp) or [content] (a provider's logo) on a 30dp surface3 tile, radius
 * sm + 2. The glyph is ink2 whatever [tint] says, except a status colour (danger, success, warning), which keeps its
 * meaning: colour in Settings only ever reports a state, never decorates a section.
 */
@Composable
fun GlyphTile(icon: ImageVector?, modifier: Modifier = Modifier, tint: Color = Pi.colors.ink1, content: (@Composable BoxScope.() -> Unit)? = null) {
    val colors = Pi.colors
    val fill = colors.surface3
    val glyph = GlyphTone.of(tint, colors)
    Box(
        modifier.size(GROUP_TILE).background(fill, RoundedCornerShape(Radius.sm + 2.dp)).clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) content() else if (icon != null) Icon(icon, contentDescription = null, tint = glyph, modifier = Modifier.size(18.dp))
    }
}

/** The glyph colour of a [GlyphTile] (unit-tested): status colours keep their meaning, everything else is ink2. */
object GlyphTone {
    fun of(tint: Color, colors: PiColors): Color =
        if (tint == colors.danger || tint == colors.success || tint == colors.warning) tint else colors.ink2
}

/**
 * A row of an [InsetGroup]: an optional [icon] (or [leading] content) on a [GlyphTile], the title (rowTitle,
 * [titleColor]) with an optional [meta] line under it (rowMeta), a short [value] at the end (rowMeta ink3), [trailing]
 * content, then the [end] glyph. At least 52dp; the whole row is the target (no scale, the ink overlay). [divider]
 * draws a hairline under it from the text start to the card's edge; the last row of a group passes false.
 */
@Composable
fun GroupRow(
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    icon: ImageVector? = null,
    iconTint: Color = Pi.colors.ink1,
    leading: (@Composable BoxScope.() -> Unit)? = null,
    meta: String? = null,
    metaColor: Color = Pi.colors.ink3,
    metaMaxLines: Int = 3,
    value: String? = null,
    valueColor: Color = Pi.colors.ink3,
    titleColor: Color = Pi.colors.ink1,
    end: GroupEnd = if (onClick != null) GroupEnd.Chevron else GroupEnd.None,
    divider: Boolean = true,
    enabled: Boolean = true,
    label: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = Pi.colors
    val type = Pi.type
    val hasTile = icon != null || leading != null
    val dim = if (enabled) 1f else 0.45f
    Box(
        modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.pressable(onClick, shape = RectangleShape, enabled = enabled, pressedScale = 1f, onLongClick = onLongClick, label = label ?: title)
                } else {
                    Modifier.semantics(mergeDescendants = true) {}
                },
            ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .graphicsLayer { alpha = dim }
                .padding(start = GROUP_INSET, end = if (trailing != null || end != GroupEnd.None) Space.md else GROUP_INSET, top = Space.md - Space.xxs, bottom = Space.md - Space.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasTile) {
                GlyphTile(icon, tint = iconTint, content = leading)
                Spacer(Modifier.width(Space.md))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = type.rowTitle, color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!meta.isNullOrBlank()) {
                    Text(meta, style = type.rowMeta, color = metaColor, maxLines = metaMaxLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.xxs))
                }
            }
            if (value != null) {
                Spacer(Modifier.width(Space.md))
                Text(value, style = type.rowMeta, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 168.dp))
            }
            if (trailing != null) {
                Spacer(Modifier.width(Space.sm))
                trailing()
            }
            when (end) {
                GroupEnd.None -> Unit
                GroupEnd.Chevron -> Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.ink3, modifier = Modifier.padding(start = Space.xs).size(IconSize.md))
                GroupEnd.External -> Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = colors.ink3, modifier = Modifier.padding(start = Space.sm).size(IconSize.sm))
            }
        }
        if (divider) GroupDivider(Modifier.align(Alignment.BottomStart), inset = if (hasTile) GROUP_TEXT_START else GROUP_INSET)
    }
}

/** A switch row of an [InsetGroup]: the [GroupRow] layout with a [PiSwitch]; the whole row toggles and reads as one switch. */
@Composable
fun GroupSwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    meta: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Pi.colors.ink1,
    enabled: Boolean = true,
    divider: Boolean = true,
) {
    val colors = Pi.colors
    val type = Pi.type
    val haptics = Pi.haptics
    Box(
        modifier
            .fillMaxWidth()
            .pressable({
                haptics.toggle(!checked)
                onChange(!checked)
            }, shape = RectangleShape, pressedScale = 1f, enabled = enabled, role = Role.Switch, label = title)
            .semantics { toggleableState = ToggleableState(checked) },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
                .padding(start = GROUP_INSET, end = Space.md, top = Space.sm, bottom = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                GlyphTile(icon, tint = iconTint)
                Spacer(Modifier.width(Space.md))
            }
            Column(Modifier.weight(1f).padding(end = Space.md)) {
                Text(title, style = type.rowTitle, color = colors.ink1, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!meta.isNullOrBlank()) Text(meta, style = type.rowMeta, color = colors.ink3, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = Space.xxs))
            }
            PiSwitch(checked, enabled = enabled)
        }
        if (divider) GroupDivider(Modifier.align(Alignment.BottomStart), inset = if (icon != null) GROUP_TEXT_START else GROUP_INSET)
    }
}

/**
 * A control block of an [InsetGroup] (a segmented choice, a preview, buttons): an optional [title] (rowTitle ink1)
 * with its [value] (rowMeta ink3) on one line, the [content] under it and an optional [note] (meta ink3), 16dp in.
 */
@Composable
fun GroupControl(
    modifier: Modifier = Modifier,
    title: String? = null,
    value: String? = null,
    note: String? = null,
    divider: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Pi.colors
    val type = Pi.type
    Box(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(start = GROUP_INSET, end = GROUP_INSET, top = Space.md, bottom = Space.lg - Space.xxs)) {
            if (title != null) {
                Row(Modifier.fillMaxWidth().padding(bottom = Space.md - Space.xxs), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = type.rowTitle, color = colors.ink1, modifier = Modifier.weight(1f, fill = false))
                    if (value != null) {
                        Spacer(Modifier.width(Space.sm))
                        Text(value, style = type.rowMeta, color = colors.ink3, maxLines = 1)
                    }
                }
            }
            content()
            if (note != null) Text(note, style = type.meta, color = colors.ink3, modifier = Modifier.padding(top = Space.sm))
        }
        if (divider) GroupDivider(Modifier.align(Alignment.BottomStart), inset = GROUP_INSET)
    }
}

/** The hairline between rows of a group, from [inset] to the card's edge. */
@Composable
fun GroupDivider(modifier: Modifier = Modifier, inset: Dp = GROUP_INSET) {
    Box(modifier.padding(start = inset).fillMaxWidth().height(1.dp).background(Pi.colors.line))
}

/**
 * The redesign's search field (Settings, the provider list): a 48dp surface1 pill with a 1dp line, the search glyph
 * (ink3), the query in rowMeta ink1 and a clear button once there is text. It reads as [placeholder] to TalkBack.
 */
@Composable
fun PillSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit = {},
) {
    val colors = Pi.colors
    val type = Pi.type
    val interaction = remember { MutableInteractionSource() }
    val shape = CircleShape
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = type.rowMeta.copy(color = colors.ink1),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        interactionSource = interaction,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(colors.surface1, shape)
                    .border(1.dp, colors.line, shape)
                    .padding(start = Space.lg, end = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(IconSize.md))
                Spacer(Modifier.width(Space.md - Space.xxs))
                Box(Modifier.weight(1f).padding(vertical = Space.md)) {
                    if (value.isEmpty()) Text(placeholder, style = type.rowMeta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
                if (value.isNotEmpty()) {
                    IconAction(Icons.Rounded.Cancel, "Clear search", onClick = { onValueChange("") }, tint = colors.ink3, size = IconSize.md)
                } else {
                    Spacer(Modifier.width(Space.md))
                }
            }
        },
    )
}
