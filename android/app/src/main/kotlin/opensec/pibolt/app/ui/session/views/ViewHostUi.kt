package opensec.pibolt.app.ui.session.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import opensec.pibolt.app.ui.components.ButtonSize
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Space
import opensec.pibolt.protocol.session.DialogRequest
import opensec.pibolt.protocol.session.MobileViews
import opensec.pibolt.protocol.session.VCard
import opensec.pibolt.protocol.session.VField
import opensec.pibolt.protocol.session.VForm
import opensec.pibolt.protocol.session.VNode
import opensec.pibolt.protocol.session.VSection
import opensec.pibolt.protocol.session.ViewEntry

/**
 * The session's side of the mobile UI kit for the screen: its views, the user's field edits per view (kept while the
 * screen lives, so a fold or a scroll keeps typing), panel folds, and where events go.
 */
@Stable
class ViewHostUi(
    /** The session's views now; read where a view is drawn, so its updates redraw it. */
    private val source: () -> Map<String, ViewEntry>,
    private val send: (viewKey: String, nodeId: String, kind: String, value: JsonPrimitive?, values: JsonObject?) -> Unit,
) {
    val views: Map<String, ViewEntry> get() = source()
    private val fields = HashMap<String, ViewFields>()
    val folded = mutableStateMapOf<String, Boolean>()

    fun fields(key: String): ViewFields = fields.getOrPut(key) { ViewFields() }

    fun actions(key: String): ViewActions = object : ViewActions {
        override fun tap(nodeId: String, value: JsonPrimitive?) = send(key, nodeId, "tap", value, null)
        override fun change(nodeId: String, value: JsonPrimitive) = send(key, nodeId, "change", value, null)
        override fun submit(formId: String, values: JsonObject) = send(key, formId, "submit", null, values)
    }

    /** Forgets the edits of views that are gone. */
    fun prune() {
        fields.keys.retainAll(views.keys)
    }
}

val LocalViewHost = compositionLocalOf<ViewHostUi?> { null }

/** An inline view's card in the transcript; nothing once the extension removed it. */
@Composable
fun InlineViewRow(viewKey: String, modifier: Modifier = Modifier) {
    val host = LocalViewHost.current ?: return
    val entry = host.views[viewKey] ?: return
    ViewInlineCard(entry.view, host.fields(viewKey), host.actions(viewKey), modifier)
}

/**
 * The panel views above the composer, newest last. The area scrolls when they are taller than [maxHeight], so the
 * composer always stays.
 */
@Composable
fun ViewPanels(host: ViewHostUi, panels: List<ViewEntry>, maxHeight: Dp, modifier: Modifier = Modifier) {
    if (panels.isEmpty()) return
    Column(
        modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        for (entry in panels) {
            val key = entry.key
            ViewPanel(
                entry.view,
                folded = host.folded[key] == true,
                onFold = { host.folded[key] = it },
                fields = host.fields(key),
                actions = host.actions(key),
            )
        }
        Spacer(Modifier.height(Space.sm))
    }
}

/**
 * A sheet of the mobile UI kit (`showView`): the view's title, its body and its `actions` as the button row. A form
 * submit or an action closes it with the sheet's field values; body buttons send events and keep it open. Swiping it
 * away dismisses it ([onCancel]): the extension gets undefined.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewSheet(
    dialog: DialogRequest,
    onAnswer: (nodeId: String, values: JsonObject) -> Unit,
    onCancel: () -> Unit,
    onEvent: (viewKey: String, nodeId: String, kind: String, value: JsonPrimitive?, values: JsonObject?) -> Unit,
) {
    val entry = dialog.view ?: return
    val key = entry.key
    val view = entry.view
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    val haptics = Pi.haptics
    val fields = remember(dialog.id) { ViewFields() }
    val all = MobileViews.fields(view)
    val closing = remember(dialog.id) { mutableListOf<Boolean>() }
    fun answer(nodeId: String) {
        if (closing.isNotEmpty()) return
        // Required fields count for those that go with the answer: outside forms, and the submitted form's own.
        // Every field sent must fit (numbers, lengths), so Pi takes the answer and the sheet's other entries are not lost.
        if (!fields.check(all, required = answerFields(view.body, nodeId).mapNotNull { it.id }.toSet())) return
        closing += true
        haptics.confirm()
        val values = fields.values(all)
        close { onAnswer(nodeId, values) }
    }
    val formIds = remember(view) { formIds(view.body) }
    val actionIds = view.actions.mapNotNull { it.id }.toSet()
    val actions = object : ViewActions {
        override fun tap(nodeId: String, value: JsonPrimitive?) {
            if (nodeId in actionIds) answer(nodeId) else onEvent(key, nodeId, "tap", value, null)
        }

        override fun change(nodeId: String, value: JsonPrimitive) = onEvent(key, nodeId, "change", value, null)

        override fun submit(formId: String, values: JsonObject) {
            if (formId in formIds) answer(formId)
        }
    }
    PiSheet(onDismiss = onCancel, state = state) {
        SheetHeader(view.title ?: dialog.title.ifBlank { "Pi" }, subtitle = view.subtitle)
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            ViewNodes(view.body, fields, actions)
        }
        if (view.actions.isNotEmpty()) {
            Spacer(Modifier.height(Space.lg))
            ViewButtons(view.actions, actions, Modifier.padding(horizontal = Space.page), size = ButtonSize.Large)
        }
        Spacer(Modifier.height(Space.lg))
    }
}

/** A page view (the `page` slot): a tall sheet with the view's title, body and buttons, live while it is open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewPageSheet(host: ViewHostUi, entry: ViewEntry, onClose: () -> Unit) {
    val key = entry.key
    val view = entry.view
    PiSheet(onDismiss = onClose) {
        SheetHeader(view.title ?: key, subtitle = view.subtitle)
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            ViewNodes(view.body, host.fields(key), host.actions(key))
            Spacer(Modifier.height(Space.sm))
        }
        if (view.actions.isNotEmpty()) {
            Spacer(Modifier.height(Space.md))
            ViewButtons(view.actions, host.actions(key), Modifier.padding(horizontal = Space.page), size = ButtonSize.Large)
        }
        Spacer(Modifier.height(Space.lg))
    }
}

/** The shown fields outside forms, then those of the form [nodeId] when it is one. */
fun answerFields(nodes: List<VNode>, nodeId: String): List<VField> {
    val out = ArrayList<VField>()
    fun walk(list: List<VNode>) {
        for (node in list) {
            if (node.hidden) continue
            when (node) {
                is VField -> if (node.id != null) out += node
                is VCard -> walk(node.children)
                is VSection -> walk(node.children)
                is VForm -> if (node.id == nodeId) out += fieldsOf(node.children)
                else -> {}
            }
        }
    }
    walk(nodes)
    return out
}

private fun formIds(nodes: List<VNode>): Set<String> {
    val out = HashSet<String>()
    fun walk(list: List<VNode>) {
        for (node in list) when (node) {
            is VForm -> node.id?.let(out::add)
            is VCard -> walk(node.children)
            is VSection -> walk(node.children)
            else -> {}
        }
    }
    walk(nodes)
    return out
}
