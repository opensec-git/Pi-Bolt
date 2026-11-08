package opensec.pibolt.app.ui.session.views

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.produceState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Report
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import opensec.pibolt.app.ui.components.ButtonRow
import opensec.pibolt.app.ui.components.ButtonSize
import opensec.pibolt.app.ui.components.ChoiceSheet
import opensec.pibolt.app.ui.components.CodeBlock
import opensec.pibolt.app.ui.components.ConfirmSheet
import opensec.pibolt.app.ui.components.CopyButton
import opensec.pibolt.app.ui.components.DangerButton
import opensec.pibolt.app.ui.components.FieldHint
import opensec.pibolt.app.ui.components.FieldLabel
import opensec.pibolt.app.ui.components.GhostButton
import opensec.pibolt.app.ui.components.Notice
import opensec.pibolt.app.ui.components.PiSwitch
import opensec.pibolt.app.ui.components.PiTextField
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.ProgressLine
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.RadiusMedia
import opensec.pibolt.app.ui.components.RowDivider
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.SectionLabel
import opensec.pibolt.app.ui.components.Segmented
import opensec.pibolt.app.ui.components.SnackKind
import opensec.pibolt.app.ui.components.ValueRow
import opensec.pibolt.app.ui.components.WorkingLine
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.markdown.Markdown
import opensec.pibolt.app.ui.markdown.MdBlockView
import opensec.pibolt.app.ui.markdown.MdImage
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Space
import opensec.pibolt.protocol.session.MobileViews
import opensec.pibolt.protocol.session.VButton
import opensec.pibolt.protocol.session.VButtonStyle
import opensec.pibolt.protocol.session.VCard
import opensec.pibolt.protocol.session.VChart
import opensec.pibolt.protocol.session.VChip
import opensec.pibolt.protocol.session.VChipNode
import opensec.pibolt.protocol.session.VCode
import opensec.pibolt.protocol.session.VDivider
import opensec.pibolt.protocol.session.VField
import opensec.pibolt.protocol.session.VForm
import opensec.pibolt.protocol.session.VGrid
import opensec.pibolt.protocol.session.VImage
import opensec.pibolt.protocol.session.VInput
import opensec.pibolt.protocol.session.VItem
import opensec.pibolt.protocol.session.VKv
import opensec.pibolt.protocol.session.VList
import opensec.pibolt.protocol.session.VLocation
import opensec.pibolt.protocol.session.VMarkdown
import opensec.pibolt.protocol.session.VNode
import opensec.pibolt.protocol.session.VNotice
import opensec.pibolt.protocol.session.VNoticeTone
import opensec.pibolt.protocol.session.VProgress
import opensec.pibolt.protocol.session.VRow
import opensec.pibolt.protocol.session.VSection
import opensec.pibolt.protocol.session.VSelect
import opensec.pibolt.protocol.session.VSlider
import opensec.pibolt.protocol.session.VTable
import opensec.pibolt.protocol.session.VText
import opensec.pibolt.protocol.session.VTile
import opensec.pibolt.protocol.session.VToggle
import opensec.pibolt.protocol.session.VTone
import opensec.pibolt.protocol.session.VUnknown
import opensec.pibolt.protocol.session.VView

/**
 * The mobile UI kit's renderer (packages/coding-agent/docs/mobile-ui.md): an extension's view drawn with the app's own
 * components, so it follows the theme, the accent, font scale, TalkBack and Reduce motion like every other screen.
 * Nothing in a view runs code: links open only with the app's link rules, images load only on a tap.
 */

/** What the user did in a view, for the session to send (`extension_ui_event`) or, in a sheet, to close it. */
interface ViewActions {
    /** A tap on [nodeId]; [value] is the row id for a table row's tap. */
    fun tap(nodeId: String, value: JsonPrimitive? = null)
    fun change(nodeId: String, value: JsonPrimitive)
    fun submit(formId: String, values: JsonObject)
}

/**
 * The values the user changed in one view, by field id. The extension's value wins when it changes (the field shows
 * what the extension set last); until then the user's stays. Passwords live only here, never in saved state.
 */
class ViewFields {
    val edits: SnapshotStateMap<String, JsonPrimitive> = mutableStateMapOf()
    val errors: SnapshotStateMap<String, String> = mutableStateMapOf()

    /** The extension's value each field showed last: a field that comes back into view with the same keeps the edit. */
    private val hostValues = HashMap<String, JsonPrimitive>()

    /** The extension set [value] for field [id]: the user's edit goes only when it differs from what it set before. */
    fun hostValue(id: String, value: JsonPrimitive) {
        val before = hostValues.put(id, value)
        if (before != null && before != value) edits.remove(id)
    }

    fun value(field: VField): JsonPrimitive {
        // An option the extension took away since the user picked it no longer counts.
        // So does a slider value outside bounds the extension changed since.
        val edit = field.id?.let { edits[it] }?.takeIf {
            when (field) {
                is VSelect -> field.options.any { o -> o.value == it.content }
                is VSlider -> it.doubleOrNull?.let { v -> v >= field.min && v <= field.max } == true
                else -> true
            }
        }
        return edit ?: MobileViews.initialValue(field)
    }

    /** The fields' values, for a submit or a sheet's answer. */
    fun values(fields: List<VField>): JsonObject = JsonObject(fields.mapNotNull { f -> f.id?.let { it to value(f) } }.toMap())

    /** False (and an error under each) when a required text field is empty. */
    /**
     * False (and an error under each) when a field of [fields] holds what Pi would refuse: a text over its limit or a
     * number field without a number; and, for the fields in [required] (default all), an empty required text.
     */
    fun check(fields: List<VField>, required: Set<String>? = null): Boolean {
        var ok = true
        for (field in fields) {
            val id = field.id ?: continue
            val text = value(field).content
            if (field is VInput && field.required && (required == null || id in required) && text.isBlank()) {
                errors[id] = "Required"
                ok = false
            } else if (field is VInput && text.length > field.maxLength) {
                // The extension lowered the limit since the user typed: say so instead of losing the sheet.
                errors[id] = "At most ${field.maxLength} characters"
                ok = false
            } else if (field is VInput && field.kind == "number" && text.isNotBlank() && text.trim().toDoubleOrNull()?.isFinite() != true) {
                // Pi refuses a number field that holds no number: say so here instead of losing the sheet.
                errors[id] = "Enter a number"
                ok = false
            } else {
                errors.remove(id)
            }
        }
        return ok
    }
}

/** The fields of [nodes] (a form's children, or a whole view's). */
fun fieldsOf(nodes: List<VNode>): List<VField> = MobileViews.fields(VView(null, null, null, nodes, emptyList()))

fun iconFor(name: String?): ImageVector? = when (name) {
    "add" -> Icons.Rounded.Add
    "alert", "warning" -> Icons.Rounded.WarningAmber
    "archive" -> Icons.Rounded.Archive
    "bell" -> Icons.Rounded.Notifications
    "bolt" -> Icons.Rounded.Bolt
    "book" -> Icons.AutoMirrored.Rounded.MenuBook
    "branch" -> Icons.Rounded.AccountTree
    "bug" -> Icons.Rounded.BugReport
    "build" -> Icons.Rounded.Build
    "calendar" -> Icons.Rounded.CalendarMonth
    "chart" -> Icons.AutoMirrored.Rounded.ShowChart
    "check" -> Icons.Rounded.Check
    "clock" -> Icons.Rounded.Schedule
    "close" -> Icons.Rounded.Close
    "cloud" -> Icons.Rounded.Cloud
    "code" -> Icons.Rounded.Code
    "copy" -> Icons.Rounded.ContentCopy
    "database" -> Icons.Rounded.Storage
    "delete" -> Icons.Rounded.Delete
    "download" -> Icons.Rounded.Download
    "edit" -> Icons.Rounded.Edit
    "error" -> Icons.Rounded.ErrorOutline
    "file" -> Icons.Rounded.Description
    "filter" -> Icons.Rounded.FilterList
    "flag" -> Icons.Rounded.Flag
    "folder" -> Icons.Rounded.Folder
    "globe" -> Icons.Rounded.Public
    "heart" -> Icons.Rounded.Favorite
    "home" -> Icons.Rounded.Home
    "info" -> Icons.Rounded.Info
    "key" -> Icons.Rounded.Key
    "link" -> Icons.Rounded.Link
    "lock" -> Icons.Rounded.Lock
    "mail" -> Icons.Rounded.Mail
    "package" -> Icons.Rounded.Inventory2
    "pause" -> Icons.Rounded.Pause
    "person" -> Icons.Rounded.Person
    "play" -> Icons.Rounded.PlayArrow
    "refresh" -> Icons.Rounded.Refresh
    "rocket" -> Icons.Rounded.RocketLaunch
    "search" -> Icons.Rounded.Search
    "send" -> Icons.AutoMirrored.Rounded.Send
    "server" -> Icons.Rounded.Dns
    "settings" -> Icons.Rounded.Settings
    "shield" -> Icons.Rounded.Shield
    "star" -> Icons.Rounded.Star
    "stop" -> Icons.Rounded.Stop
    "sync" -> Icons.Rounded.Sync
    "terminal" -> Icons.Rounded.Terminal
    "upload" -> Icons.Rounded.Upload
    else -> null
}

/** A tone's colour, and its glyph: a tone is never told by colour alone. */
@Composable
private fun toneColor(tone: VTone): Color = when (tone) {
    VTone.Success -> Pi.colors.success
    VTone.Warning -> Pi.colors.warning
    VTone.Danger -> Pi.colors.danger
    VTone.Running, VTone.Neutral -> Pi.colors.ink2
}

private fun toneIcon(tone: VTone): ImageVector? = when (tone) {
    VTone.Success -> Icons.Rounded.CheckCircle
    VTone.Warning -> Icons.Rounded.WarningAmber
    VTone.Danger -> Icons.Rounded.Report
    else -> null
}

private fun toneName(tone: VTone): String? = when (tone) {
    VTone.Success -> "success"
    VTone.Warning -> "warning"
    VTone.Danger -> "failed"
    VTone.Running -> "in progress"
    VTone.Neutral -> null
}

/** A status chip: a capsule with the tone's glyph (or the live Quad while running) and the label. */
@Composable
fun ViewChip(chip: VChip, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val shape = RoundedCornerShape(Radius.sm)
    val fill = when (chip.tone) {
        VTone.Success -> colors.successSoft
        VTone.Warning -> colors.warningSoft
        VTone.Danger -> colors.dangerSoft
        else -> colors.surface3
    }
    Row(
        modifier
            .heightIn(min = 28.dp)
            .background(fill, shape)
            .padding(horizontal = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(chip.label, toneName(chip.tone)).joinToString(", ") },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = iconFor(chip.icon) ?: toneIcon(chip.tone)
        when {
            chip.tone == VTone.Running -> QuadSpinner(QuadMode.Run, size = IconSize.xs, color = colors.ink1)
            icon != null -> Icon(icon, contentDescription = null, tint = toneColor(chip.tone), modifier = Modifier.size(IconSize.xs))
        }
        if (chip.tone == VTone.Running || icon != null) Spacer(Modifier.width(6.dp))
        Text(chip.label, style = Pi.type.label, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A button of the kit: the app's capsules; [ViewButton] asks first when it has `confirm`, and opens `url` links. */
@Composable
fun ViewButton(button: VButton, actions: ViewActions, modifier: Modifier = Modifier, size: ButtonSize = ButtonSize.Large) {
    val uri = LocalUriHandler.current
    val haptics = Pi.haptics
    var confirming by remember { mutableStateOf(false) }
    val id = button.id
    val enabled = !button.disabled && !button.busy && (button.url != null || id != null)
    val onClick: () -> Unit = {
        val url = button.url
        when {
            url != null -> if (MobileViews.isAllowedLink(url)) runCatching { uri.openUri(url) }
            button.confirm != null -> confirming = true
            id != null -> {
                haptics.tick()
                actions.tap(id)
            }
        }
    }
    if (button.busy) {
        Box(modifier.heightIn(min = size.height), contentAlignment = Alignment.Center) { WorkingLine(button.label, mode = QuadMode.Run) }
    } else {
        val icon = iconFor(button.icon)
        when (button.style) {
            VButtonStyle.Primary -> PrimaryButton(button.label, onClick, modifier, icon, enabled, size)
            VButtonStyle.Secondary -> SecondaryButton(button.label, onClick, modifier, icon, enabled, size)
            VButtonStyle.Ghost -> GhostButton(button.label, onClick, modifier, icon, enabled, size = size)
            VButtonStyle.Danger -> DangerButton(button.label, onClick, modifier, icon, enabled, size)
        }
    }
    val confirm = button.confirm
    if (confirming && confirm != null && id != null) {
        ConfirmSheet(
            title = confirm.title,
            message = confirm.message.orEmpty(),
            confirmLabel = confirm.label ?: button.label,
            onConfirm = {
                confirming = false
                actions.tap(id)
            },
            onDismiss = { confirming = false },
            destructive = button.style == VButtonStyle.Danger,
        )
    }
}

/** A row of buttons: equal widths, stacking when a label would not fit (the app's ButtonRow). */
@Composable
fun ViewButtons(buttons: List<VButton>, actions: ViewActions, modifier: Modifier = Modifier, size: ButtonSize = ButtonSize.Large) {
    val shown = buttons.filterNot { it.hidden }
    if (shown.isEmpty()) return
    ButtonRow(modifier) {
        for (button in shown) ViewButton(button, actions, size = size)
    }
}

/** The nodes of a view (or of a card or section), top to bottom. */
@Composable
fun ViewNodes(nodes: List<VNode>, fields: ViewFields, actions: ViewActions, modifier: Modifier = Modifier, inForm: Boolean = false) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.md)) {
        for (node in nodes) {
            if (node.hidden) continue
            ViewNode(node, fields, actions, inForm)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ViewNode(node: VNode, fields: ViewFields, actions: ViewActions, inForm: Boolean) {
    val colors = Pi.colors
    val type = Pi.type
    when (node) {
        is VText -> when (node.style) {
            "title" -> Text(node.text, style = type.subhead, color = colors.ink1, modifier = Modifier.semantics { heading() })
            "meta" -> Text(node.text, style = type.meta, color = colors.ink3)
            "mono" -> Text(node.text, style = type.monoSmall, color = colors.ink1)
            else -> Text(node.text, style = type.body, color = colors.ink1)
        }
        is VMarkdown -> {
            val doc = remember(node.text) { Markdown.parse(node.text) }
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                for (block in doc.blocks) MdBlockView(block, streaming = false, onOpenCode = { _, _ -> })
            }
        }
        is VCode -> CodeBlock(node.text, node.language.orEmpty(), "", closed = true, onOpen = null, lineNumbers = false, title = node.title)
        is VImage -> ViewImage(node)
        is VDivider -> RowDivider()
        is VNotice -> Notice(
            node.text,
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            kind = when (node.tone) {
                VNoticeTone.Info -> SnackKind.Info
                VNoticeTone.Success -> SnackKind.Success
                VNoticeTone.Warning -> SnackKind.Warning
                VNoticeTone.Danger -> SnackKind.Error
            },
            title = node.title,
        )
        is VKv -> Column {
            node.items.forEachIndexed { i, item ->
                if (i > 0) RowDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ValueRow(item.key, item.value, Modifier.weight(1f), valueStyle = if (item.mono) type.monoSmall else type.body)
                    if (item.copy) CopyButton({ item.value }, label = false)
                }
            }
        }
        is VProgress -> ViewProgress(node)
        is VChipNode -> ViewChip(node.chip)
        is VTile -> ViewTile(node, actions, Modifier.fillMaxWidth())
        is VButton -> ViewButton(node, actions, Modifier.fillMaxWidth())
        is VCard -> ViewCard(node, fields, actions)
        is VSection -> Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            node.title?.let { SectionLabel(it, Modifier.semantics { heading() }) }
            ViewNodes(node.children, fields, actions, inForm = inForm)
        }
        is VList -> ViewList(node, actions)
        is VItem -> ViewList(VList(null, false, listOf(node), null), actions)
        is VRow -> FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            for (child in node.children) {
                if (child.hidden) continue
                when (child) {
                    is VChipNode -> ViewChip(child.chip, Modifier.align(Alignment.CenterVertically))
                    is VButton -> ViewButton(child, actions, size = ButtonSize.Small)
                    is VText -> Text(child.text, style = type.meta, color = colors.ink3, modifier = Modifier.align(Alignment.CenterVertically))
                    else -> {}
                }
            }
        }
        is VGrid -> Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
            for (row in node.children.filterNot { it.hidden }.chunked(node.columns)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                    for (tile in row) (tile as? VTile)?.let { ViewTile(it, actions, Modifier.weight(1f)) }
                    repeat(node.columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        is VForm -> ViewForm(node, fields, actions)
        is VField -> ViewField(node, fields, actions, live = !inForm)
        is VChart -> ViewChart(node)
        is VTable -> ViewTable(node, actions)
        is VLocation -> ViewLocation(node)
        is VUnknown -> node.text?.let { Text(it, style = type.body, color = colors.ink2) }
    }
}

@Composable
private fun ViewProgress(node: VProgress) {
    val colors = Pi.colors
    val type = Pi.type
    val value = node.value
    if (value == null) {
        Column {
            WorkingLine(node.label ?: "Working", mode = QuadMode.Run)
            node.detail?.let { Text(it, style = type.meta, color = colors.ink3, modifier = Modifier.padding(start = 22.dp)) }
        }
        return
    }
    // Announced when it moves by a tenth or its label changes, not on every tick.
    val step = (value * 10).toInt()
    Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite; stateDescription = "${node.label.orEmpty()} ${step * 10} percent" }) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(node.label.orEmpty(), style = type.label, color = colors.ink1, maxLines = 2, modifier = Modifier.weight(1f))
            Text("${(value * 100).toInt()}%", style = type.meta, color = colors.ink3)
        }
        ProgressLine(value.toFloat())
        node.detail?.let { Text(it, style = type.meta, color = colors.ink3, modifier = Modifier.padding(top = 4.dp)) }
    }
}

@Composable
private fun ViewTile(tile: VTile, actions: ViewActions, modifier: Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    val shape = RoundedCornerShape(Radius.tile)
    val id = tile.id
    val tap = if (tile.action != null && id != null) ({ actions.tap(id) }) else null
    Column(
        modifier
            .heightIn(min = 104.dp)
            .then(if (tap != null) Modifier.pressable(tap, shape = shape, pressedScale = 0.96f, label = tile.label) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(tile.label, tile.value, tile.tone?.let(::toneName)).joinToString(", ") }
            .background(colors.surface1, shape)
            .border(1.dp, colors.line, shape)
            .padding(start = Space.lg, end = Space.md, top = Space.lg, bottom = Space.md),
    ) {
        val icon = iconFor(tile.icon) ?: tile.tone?.let(::toneIcon)
        if (icon != null) Icon(icon, contentDescription = null, tint = tile.tone?.let { toneColor(it) } ?: colors.ink2, modifier = Modifier.size(IconSize.md))
        Spacer(Modifier.weight(1f).heightIn(min = Space.md))
        Text(tile.label, style = type.rowMeta, color = colors.ink1, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(tile.value, style = type.rowMeta, color = colors.ink3, maxLines = 1)
    }
}

@Composable
fun ViewCard(card: VCard, fields: ViewFields, actions: ViewActions) {
    val colors = Pi.colors
    val type = Pi.type
    val shape = RoundedCornerShape(Radius.md)
    Column(
        Modifier.fillMaxWidth().background(colors.surface1, shape).border(1.dp, colors.line, shape).padding(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        if (card.title != null || card.status != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                iconFor(card.icon)?.let { Icon(it, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(IconSize.md)); Spacer(Modifier.width(10.dp)) }
                Column(Modifier.weight(1f)) {
                    card.title?.let { Text(it, style = type.subhead, color = colors.ink1, modifier = Modifier.semantics { heading() }) }
                    card.subtitle?.let { Text(it, style = type.meta, color = colors.ink3) }
                }
                card.status?.let { ViewChip(it) }
            }
        }
        ViewNodes(card.children, fields, actions)
        ViewButtons(card.actions, actions, size = ButtonSize.Small)
    }
}

@Composable
private fun ViewList(list: VList, actions: ViewActions) {
    val colors = Pi.colors
    val shape = RoundedCornerShape(Radius.tile)
    val rows = list.children.filterNot { it.hidden }
    Column(Modifier.fillMaxWidth().clip(shape).background(colors.surface1, shape).border(1.dp, colors.line, shape)) {
        if (rows.isEmpty()) {
            Text(list.empty ?: "Nothing here", style = Pi.type.body, color = colors.ink3, modifier = Modifier.padding(Space.lg))
        }
        rows.forEachIndexed { i, item ->
            if (i > 0) RowDivider(Modifier.padding(start = Space.lg))
            ViewItemRow(item, actions)
        }
    }
}

@Composable
private fun ViewItemRow(item: VItem, actions: ViewActions) {
    val colors = Pi.colors
    val type = Pi.type
    val id = item.id
    val tap = if (item.action != null && id != null) ({ actions.tap(id) }) else null
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (tap != null) Modifier.pressable(tap, shape = RoundedCornerShape(0.dp), pressedScale = 1f, label = item.title) else Modifier)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = Space.lg, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        iconFor(item.icon)?.let {
            Icon(it, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(IconSize.md))
            Spacer(Modifier.width(Space.md))
        }
        Column(Modifier.weight(1f)) {
            Text(item.title, style = type.rowTitle, color = colors.ink1, maxLines = 2, overflow = TextOverflow.Ellipsis)
            item.subtitle?.let { Text(it, style = type.rowMeta, color = colors.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            item.progress?.let { ProgressLine(it.toFloat(), Modifier.padding(top = 6.dp)) }
        }
        item.value?.let {
            Spacer(Modifier.width(Space.sm))
            Text(it, style = type.rowMeta, color = colors.ink3, maxLines = 1)
        }
        item.chip?.let {
            Spacer(Modifier.width(Space.sm))
            ViewChip(it)
        }
        if (item.chevron || tap != null) {
            Spacer(Modifier.width(Space.xs))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(IconSize.md))
        }
    }
}

@Composable
private fun ViewImage(image: VImage) {
    val colors = Pi.colors
    val src = image.src
    if (src.startsWith("data:")) {
        // Off the main thread and at most 2048 px on its long edge: a small PNG can still be a huge bitmap.
        val decoded by produceState<ImageBitmap?>(null, src) { value = withContext(Dispatchers.Default) { decodeDataImage(src) } }
        val bitmap = decoded
        if (bitmap != null) {
            Image(
                bitmap,
                contentDescription = image.alt,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(image.aspect?.let { Modifier.aspectRatio(it.toFloat()) } ?: Modifier.heightIn(max = 360.dp))
                    .clip(RoundedCornerShape(RadiusMedia))
                    .border(1.dp, colors.line, RoundedCornerShape(RadiusMedia)),
            )
        }
    } else if (src.startsWith("https://") && isPublicHost(src)) {
        // Never fetched by itself: the transcript's image card, loaded on a tap.
        MdImage(image.alt ?: "Image", src)
    }
}

private const val IMAGE_MAX_EDGE = 2048

/** A `data:` image decoded with its long edge at most [IMAGE_MAX_EDGE] (a bounds pass picks the sample size). */
private fun decodeDataImage(src: String): ImageBitmap? = runCatching {
    val bytes = Base64.decode(src.substringAfter("base64,"), Base64.DEFAULT)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > IMAGE_MAX_EDGE) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

/** An https host the app may fetch from on a tap: a dotted public name, no IP address, localhost or LAN name. */
fun isPublicHost(url: String): Boolean {
    val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase()?.trimEnd('.') ?: return false
    if (!host.contains('.') || host.startsWith("[")) return false
    if (Regex("""^\d+(\.\d+){3}$""").matches(host)) return false
    return !Regex("""(^|\.)(localhost|local|lan|internal|home|home\.arpa|localdomain)$""").containsMatchIn(host)
}

@Composable
private fun ViewForm(form: VForm, fields: ViewFields, actions: ViewActions) {
    val formFields = remember(form) { fieldsOf(form.children) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        ViewNodes(form.children, fields, actions, inForm = true)
        val id = form.id
        if (form.busy) {
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) { WorkingLine(form.submitLabel, mode = QuadMode.Run) }
        } else {
            PrimaryButton(
                form.submitLabel,
                onClick = {
                    if (id != null && fields.check(formFields)) actions.submit(id, fields.values(formFields))
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !form.disabled && id != null,
            )
        }
    }
}

/** A field. [live]: outside a form, each committed value goes out at once as a `change`. */
@Composable
private fun ViewField(field: VField, fields: ViewFields, actions: ViewActions, live: Boolean) {
    val id = field.id ?: return
    val colors = Pi.colors
    val type = Pi.type
    val initial = MobileViews.initialValue(field)
    // The extension set a new value: it wins over what the user had changed (folding a panel or scrolling keeps edits).
    LaunchedEffect(initial) { fields.hostValue(id, initial) }
    val current = fields.value(field)
    fun set(value: JsonPrimitive, commit: Boolean) {
        fields.edits[id] = value
        fields.errors.remove(id)
        if (live && commit) actions.change(id, value)
    }
    when (field) {
        is VInput -> Column {
            var focused by remember { mutableStateOf(false) }
            FieldLabel(field.label + if (field.required) " *" else "")
            val password = field.kind == "password"
            PiTextField(
                value = current.content,
                onValueChange = { if (it.length <= field.maxLength) set(JsonPrimitive(it), commit = false) },
                placeholder = field.placeholder.orEmpty(),
                singleLine = field.kind != "multiline",
                minLines = if (field.kind == "multiline") 3 else 1,
                maxLines = if (field.kind == "multiline") 8 else 1,
                isError = field.error != null || fields.errors[id] != null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = when (field.kind) {
                        "number" -> KeyboardType.Decimal
                        "email" -> KeyboardType.Email
                        "url" -> KeyboardType.Uri
                        "password" -> KeyboardType.Password
                        else -> KeyboardType.Text
                    },
                    autoCorrectEnabled = !password && field.kind == "text" || field.kind == "multiline",
                    imeAction = if (field.kind == "multiline") ImeAction.Default else ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (live) actions.change(id, fields.value(field)) }),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                // Outside a form, leaving the field commits it too: a multiline field has no Done key.
                modifier = Modifier.fillMaxWidth().onFocusChanged { state ->
                    if (state.hasFocus) focused = true
                    else if (focused) {
                        focused = false
                        if (live && fields.edits[id] != null) actions.change(id, fields.value(field))
                    }
                },
            )
            val error = fields.errors[id] ?: field.error
            when {
                error != null -> FieldHint(error, isError = true)
                field.hint != null -> FieldHint(field.hint!!)
            }
        }
        is VToggle -> Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .pressable({ set(JsonPrimitive(!(current.booleanOrNull ?: false)), commit = true) }, pressedScale = 1f, label = field.label)
                .semantics(mergeDescendants = true) { stateDescription = if (current.booleanOrNull == true) "On" else "Off" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(field.label, style = type.body, color = colors.ink1)
                field.hint?.let { Text(it, style = type.meta, color = colors.ink3) }
            }
            Spacer(Modifier.width(Space.md))
            PiSwitch(current.booleanOrNull == true, onCheckedChange = { set(JsonPrimitive(it), commit = true) })
        }
        is VSelect -> Column {
            val selected = current.content
            if (field.options.size <= 3) {
                FieldLabel(field.label)
                Segmented(field.options.map { it.value to it.label }, selected, { set(JsonPrimitive(it), commit = true) }, Modifier.fillMaxWidth(), height = 48.dp)
            } else {
                var choosing by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).pressable({ choosing = true }, pressedScale = 1f, label = field.label),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(field.label, style = type.body, color = colors.ink1, modifier = Modifier.weight(1f))
                    Text(field.options.firstOrNull { it.value == selected }?.label.orEmpty(), style = type.body, color = colors.ink3, maxLines = 1)
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(IconSize.md))
                }
                if (choosing) {
                    ChoiceSheet(field.label, field.options.map { it.value to it.label }, selected, onSelect = {
                        choosing = false
                        set(JsonPrimitive(it), commit = true)
                    }, onDismiss = { choosing = false })
                }
            }
        }
        is VSlider -> Column {
            val value = (current.doubleOrNull ?: field.value).toFloat()
            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel(field.label, Modifier.weight(1f))
                Text(formatNumber(value.toDouble()) + (field.unit?.let { " $it" } ?: ""), style = type.meta, color = colors.ink3)
            }
            // Ticks only when there are few; the value always snaps to the declared step either way.
            val steps = field.step?.let { (((field.max - field.min) / it).toInt() - 1).takeIf { n -> n in 0..200 } } ?: 0
            Slider(
                value = value,
                onValueChange = { set(JsonPrimitive(snap(it.toDouble(), field)), commit = false) },
                onValueChangeFinished = { if (live) actions.change(id, fields.value(field)) },
                valueRange = field.min.toFloat()..field.max.toFloat(),
                steps = steps,
                colors = SliderDefaults.colors(
                    thumbColor = colors.accent,
                    activeTrackColor = colors.accent,
                    inactiveTrackColor = colors.surface3,
                    activeTickColor = colors.onAccent,
                    inactiveTickColor = colors.ink4,
                ),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = field.label },
            )
        }
    }
}

/** [value] on [field]'s step from its min, within its bounds (a Float back to Double can land just outside them). */
private fun snap(value: Double, field: VSlider): Double {
    val step = field.step
    val snapped = if (step == null) value else field.min + Math.round((value - field.min) / step) * step
    return snapped.coerceIn(field.min, field.max)
}

private fun formatNumber(value: Double): String = if (value == Math.floor(value)) value.toLong().toString() else String.format("%.2f", value)

/** A view's header: icon, title (a heading), subtitle, and the first chip of its body as its state. */
@Composable
fun ViewHeader(view: VView, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    val colors = Pi.colors
    val type = Pi.type
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        iconFor(view.icon)?.let {
            Icon(it, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(IconSize.md))
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            view.title?.let { Text(it, style = type.subhead, color = colors.ink1, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) }
            view.subtitle?.let { Text(it, style = type.meta, color = colors.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        trailing()
    }
}

/**
 * A panel view above the composer: a card whose header folds it (the fold is the phone's own, kept per view key while
 * the screen lives). Folded, it shows the header and the first chip of its body.
 */
@Composable
fun ViewPanel(view: VView, folded: Boolean, onFold: (Boolean) -> Unit, fields: ViewFields, actions: ViewActions, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val shape = RoundedCornerShape(Radius.lg)
    val firstChip = view.body.firstNotNullOfOrNull { node ->
        when (node) {
            is VChipNode -> node.chip.takeUnless { node.hidden }
            is VRow -> node.children.firstNotNullOfOrNull { (it as? VChipNode)?.chip?.takeUnless { _ -> it.hidden } }
            else -> null
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface1, shape)
            .border(1.dp, colors.line, shape)
            .animateContentSize(),
    ) {
        ViewHeader(
            view,
            Modifier
                .pressable({ onFold(!folded) }, shape = RoundedCornerShape(0.dp), pressedScale = 1f, label = if (folded) "Show ${view.title.orEmpty()}" else "Fold ${view.title.orEmpty()}")
                .heightIn(min = 48.dp)
                .padding(start = Space.lg, end = Space.sm, top = Space.sm, bottom = Space.sm),
        ) {
            if (folded) firstChip?.let { ViewChip(it) }
            Icon(if (folded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess, contentDescription = null, tint = colors.ink3, modifier = Modifier.padding(start = Space.sm).size(IconSize.md))
        }
        if (!folded) {
            ViewNodes(view.body, fields, actions, Modifier.padding(start = Space.lg, end = Space.lg, bottom = Space.md))
            ViewButtons(view.actions, actions, Modifier.padding(start = Space.lg, end = Space.lg, bottom = Space.md), size = ButtonSize.Small)
        }
    }
}

/** An inline view in the transcript: a card with the view's header, body and buttons. */
@Composable
fun ViewInlineCard(view: VView, fields: ViewFields, actions: ViewActions, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val shape = RoundedCornerShape(Radius.md)
    Column(
        modifier.fillMaxWidth().background(colors.surface1, shape).border(1.dp, colors.line, shape).padding(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        if (view.title != null) ViewHeader(view)
        ViewNodes(view.body, fields, actions)
        ViewButtons(view.actions, actions, size = ButtonSize.Small)
    }
}
