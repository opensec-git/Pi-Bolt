package opensec.pibolt.protocol.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import opensec.pibolt.protocol.arr
import opensec.pibolt.protocol.bool
import opensec.pibolt.protocol.double
import opensec.pibolt.protocol.int
import opensec.pibolt.protocol.obj
import opensec.pibolt.protocol.str
import opensec.pibolt.protocol.type

/**
 * The mobile UI kit (packages/coding-agent/docs/mobile-ui.md): an extension's view as the app draws it. Parsing is
 * lenient: Pi validated the view, and a newer Pi may send node types and fields this app does not know. An unknown node
 * becomes [VUnknown] with its text (title, label or text), unknown fields are ignored.
 */

const val VIEWS_FEATURE = "views"

enum class VTone { Neutral, Success, Warning, Danger, Running }

enum class VNoticeTone { Info, Success, Warning, Danger }

enum class VButtonStyle { Primary, Secondary, Ghost, Danger }

data class VChip(val label: String, val tone: VTone = VTone.Neutral, val icon: String? = null)

data class VConfirm(val title: String, val message: String?, val label: String?)

data class VKvItem(val key: String, val value: String, val mono: Boolean, val copy: Boolean)

data class VOption(val value: String, val label: String)

sealed interface VNode {
    val id: String?
    val hidden: Boolean
}

data class VText(override val id: String?, override val hidden: Boolean, val text: String, val style: String) : VNode
data class VMarkdown(override val id: String?, override val hidden: Boolean, val text: String) : VNode
data class VCode(override val id: String?, override val hidden: Boolean, val text: String, val language: String?, val title: String?) : VNode
data class VImage(override val id: String?, override val hidden: Boolean, val src: String, val alt: String?, val aspect: Double?) : VNode
data class VDivider(override val id: String?, override val hidden: Boolean) : VNode
data class VNotice(override val id: String?, override val hidden: Boolean, val tone: VNoticeTone, val title: String?, val text: String) : VNode
data class VKv(override val id: String?, override val hidden: Boolean, val items: List<VKvItem>) : VNode
data class VProgress(override val id: String?, override val hidden: Boolean, val value: Double?, val label: String?, val detail: String?) : VNode
data class VChipNode(override val id: String?, override val hidden: Boolean, val chip: VChip) : VNode
data class VTile(override val id: String?, override val hidden: Boolean, val label: String, val value: String, val icon: String?, val tone: VTone?, val action: String?) : VNode
data class VButton(
    override val id: String?,
    override val hidden: Boolean,
    val label: String,
    val style: VButtonStyle,
    val icon: String?,
    val action: String?,
    val disabled: Boolean,
    val busy: Boolean,
    val url: String?,
    val confirm: VConfirm?,
) : VNode
data class VCard(
    override val id: String?,
    override val hidden: Boolean,
    val title: String?,
    val subtitle: String?,
    val icon: String?,
    val status: VChip?,
    val children: List<VNode>,
    val actions: List<VButton>,
) : VNode
data class VSection(override val id: String?, override val hidden: Boolean, val title: String?, val children: List<VNode>) : VNode
data class VList(override val id: String?, override val hidden: Boolean, val children: List<VItem>, val empty: String?) : VNode
data class VItem(
    override val id: String?,
    override val hidden: Boolean,
    val title: String,
    val subtitle: String?,
    val icon: String?,
    val value: String?,
    val chip: VChip?,
    val progress: Double?,
    val action: String?,
    val chevron: Boolean,
) : VNode
data class VRow(override val id: String?, override val hidden: Boolean, val children: List<VNode>) : VNode
data class VGrid(override val id: String?, override val hidden: Boolean, val columns: Int, val children: List<VNode>) : VNode
data class VForm(
    override val id: String?,
    override val hidden: Boolean,
    val children: List<VNode>,
    val submitLabel: String,
    val disabled: Boolean,
    val busy: Boolean,
) : VNode

/** A field: [input], [toggle], [select] or [slider]. Its [value] as the extension last set it. */
sealed interface VField : VNode {
    val label: String
}

data class VInput(
    override val id: String?,
    override val hidden: Boolean,
    override val label: String,
    val value: String,
    val placeholder: String?,
    val hint: String?,
    val error: String?,
    val kind: String,
    val required: Boolean,
    val maxLength: Int,
) : VField
data class VToggle(override val id: String?, override val hidden: Boolean, override val label: String, val value: Boolean, val hint: String?) : VField
data class VSelect(override val id: String?, override val hidden: Boolean, override val label: String, val options: List<VOption>, val value: String?) : VField
data class VSlider(
    override val id: String?,
    override val hidden: Boolean,
    override val label: String,
    val min: Double,
    val max: Double,
    val step: Double?,
    val value: Double,
    val unit: String?,
) : VField

/** A node type of a newer kit: shown as its [text], if it has any. */
data class VUnknown(override val id: String?, override val hidden: Boolean, val text: String?) : VNode

data class VView(
    val title: String?,
    val subtitle: String?,
    val icon: String?,
    val body: List<VNode>,
    val actions: List<VButton>,
)

/**
 * A view of the session: [key], where it shows ([slot]: `panel`, `inline`, `page`), its JSON as Pi sent it and [rev].
 * An inline view is pinned under the tool call [toolCallId], or else at the end of the turn whose user message has the
 * timestamp [turn]; with neither (or when its turn is not in the transcript), at the end.
 */
data class ViewEntry(
    val key: String,
    val slot: String,
    val json: JsonObject,
    val view: VView,
    val rev: Long,
    val toolCallId: String? = null,
    val turn: Long? = null,
)

object MobileViews {
    const val SLOT_PANEL = "panel"
    const val SLOT_INLINE = "inline"
    const val SLOT_PAGE = "page"

    fun parse(json: JsonObject): VView = VView(
        title = json.str("title"),
        subtitle = json.str("subtitle"),
        icon = json.str("icon"),
        body = nodes(json.arr("body")),
        actions = buttons(json.arr("actions")),
    )

    private fun nodes(array: JsonArray?): List<VNode> = array.orEmpty().mapNotNull { (it as? JsonObject)?.let(::node) }

    private fun buttons(array: JsonArray?): List<VButton> = nodes(array).filterIsInstance<VButton>()

    private fun tone(value: String?): VTone = when (value) {
        "success" -> VTone.Success
        "warning" -> VTone.Warning
        "danger" -> VTone.Danger
        "running" -> VTone.Running
        else -> VTone.Neutral
    }

    private fun chip(json: JsonObject?): VChip? {
        val label = json?.str("label") ?: return null
        return VChip(label, tone(json.str("tone")), json.str("icon"))
    }

    fun node(json: JsonObject): VNode {
        val id = json.str("id")
        val hidden = json.bool("hidden") == true
        return when (json.type) {
            "text" -> VText(id, hidden, json.str("text").orEmpty(), json.str("style") ?: "body")
            "markdown" -> VMarkdown(id, hidden, json.str("text").orEmpty())
            "code" -> VCode(id, hidden, json.str("text").orEmpty(), json.str("language"), json.str("title"))
            "image" -> VImage(id, hidden, json.str("src").orEmpty(), json.str("alt")?.takeIf { json.bool("decorative") != true }, json.double("aspect"))
            "divider" -> VDivider(id, hidden)
            "notice" -> VNotice(
                id,
                hidden,
                when (json.str("tone")) {
                    "success" -> VNoticeTone.Success
                    "warning" -> VNoticeTone.Warning
                    "danger" -> VNoticeTone.Danger
                    else -> VNoticeTone.Info
                },
                json.str("title"),
                json.str("text").orEmpty(),
            )
            "kv" -> VKv(
                id,
                hidden,
                json.arr("items").orEmpty().mapNotNull { element ->
                    val item = element as? JsonObject ?: return@mapNotNull null
                    VKvItem(item.str("key").orEmpty(), item.str("value").orEmpty(), item.bool("mono") == true, item.bool("copy") == true)
                },
            )
            "progress" -> VProgress(id, hidden, json.double("value")?.coerceIn(0.0, 1.0), json.str("label"), json.str("detail"))
            "chip" -> VChipNode(id, hidden, VChip(json.str("label").orEmpty(), tone(json.str("tone")), json.str("icon")))
            "tile" -> VTile(id, hidden, json.str("label").orEmpty(), json.str("value").orEmpty(), json.str("icon"), json.str("tone")?.let(::tone), json.str("action"))
            "button" -> VButton(
                id,
                hidden,
                json.str("label").orEmpty(),
                when (json.str("style")) {
                    "primary" -> VButtonStyle.Primary
                    "ghost" -> VButtonStyle.Ghost
                    "danger" -> VButtonStyle.Danger
                    else -> VButtonStyle.Secondary
                },
                json.str("icon"),
                json.str("action"),
                json.bool("disabled") == true,
                json.bool("busy") == true,
                json.str("url")?.takeIf(::isAllowedLink),
                json.obj("confirm")?.let { c -> c.str("title")?.let { VConfirm(it, c.str("message"), c.str("label")) } },
            )
            "card" -> VCard(id, hidden, json.str("title"), json.str("subtitle"), json.str("icon"), chip(json.obj("status")), nodes(json.arr("children")), buttons(json.arr("actions")))
            "section" -> VSection(id, hidden, json.str("title"), nodes(json.arr("children")))
            "list" -> VList(id, hidden, nodes(json.arr("children")).filterIsInstance<VItem>(), json.str("empty"))
            "item" -> VItem(
                id,
                hidden,
                json.str("title").orEmpty(),
                json.str("subtitle"),
                json.str("icon"),
                json.str("value"),
                chip(json.obj("chip")),
                json.double("progress")?.coerceIn(0.0, 1.0),
                json.str("action"),
                json.bool("chevron") == true,
            )
            "row" -> VRow(id, hidden, nodes(json.arr("children")))
            "grid" -> VGrid(id, hidden, (json.int("columns") ?: 2).coerceIn(2, 4), nodes(json.arr("children")))
            "form" -> VForm(id, hidden, nodes(json.arr("children")), json.obj("submit")?.str("label") ?: "Submit", json.bool("disabled") == true, json.bool("busy") == true)
            "input" -> VInput(
                id,
                hidden,
                json.str("label").orEmpty(),
                json.str("value").orEmpty(),
                json.str("placeholder"),
                json.str("hint"),
                json.str("error"),
                json.str("kind") ?: "text",
                json.bool("required") == true,
                json.int("maxLength") ?: 2000,
            )
            "toggle" -> VToggle(id, hidden, json.str("label").orEmpty(), json.bool("value") == true, json.str("hint"))
            "select" -> VSelect(
                id,
                hidden,
                json.str("label").orEmpty(),
                json.arr("options").orEmpty().mapNotNull { option ->
                    when (option) {
                        is JsonPrimitive -> option.takeIf { it.isString }?.content?.let { VOption(it, it) }
                        is JsonObject -> option.str("value")?.let { VOption(it, option.str("label") ?: it) }
                        else -> null
                    }
                },
                json.str("value"),
            )
            "slider" -> {
                val min = json.double("min") ?: 0.0
                val max = (json.double("max") ?: 1.0).let { if (it > min) it else min + 1 }
                VSlider(id, hidden, json.str("label").orEmpty(), min, max, json.double("step")?.takeIf { it > 0 }, (json.double("value") ?: min).coerceIn(min, max), json.str("unit"))
            }
            else -> VUnknown(id, hidden, json.str("title") ?: json.str("label") ?: json.str("text"))
        }
    }

    /** `https:`, `http:` or `mailto:`: the app's link rules. */
    fun isAllowedLink(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("mailto:")
    }

    /** Every field of [view] by id (forms' and standalone), for a sheet's answer. */
    fun fields(view: VView): List<VField> {
        val out = ArrayList<VField>()
        fun walk(nodes: List<VNode>) {
            for (node in nodes) {
                if (node.hidden) continue
                when (node) {
                    is VField -> if (node.id != null) out += node
                    is VCard -> walk(node.children)
                    is VSection -> walk(node.children)
                    is VForm -> walk(node.children)
                    else -> {}
                }
            }
        }
        walk(view.body)
        return out
    }

    /** A field's value as the extension set it, as JSON. */
    fun initialValue(field: VField): JsonPrimitive = when (field) {
        is VInput -> JsonPrimitive(field.value)
        is VToggle -> JsonPrimitive(field.value)
        is VSelect -> JsonPrimitive(field.value ?: field.options.firstOrNull()?.value ?: "")
        is VSlider -> JsonPrimitive(field.value)
    }

    // updateView ops, applied as Pi applies them (docs/mobile-ui.md#updates). Null when an op cannot apply.

    fun applyOps(view: JsonObject, ops: JsonArray): JsonObject? {
        var current = view
        for (op in ops) current = applyOp(current, op as? JsonObject ?: return null) ?: return null
        return current
    }

    private fun applyOp(view: JsonObject, op: JsonObject): JsonObject? = when (op.str("op")) {
        // Pi refuses a change of type or id before it sends the op; merge() keeps both as they are.
        "set" -> {
            val props = op.obj("props") ?: return null
            mapNode(view, op.str("id") ?: return null) { node -> listOf(merge(node, props)) }
        }
        "replace" -> {
            val replacement = op.obj("node") ?: return null
            mapNode(view, op.str("id") ?: return null) { listOf(replacement) }
        }
        "remove" -> mapNode(view, op.str("id") ?: return null) { emptyList() }
        "insert" -> {
            val node = op.obj("node") ?: return null
            val index = op.int("index")
            val parent = op.str("parent")
            if (parent == null) {
                insertInto(view, "body", node, index)
            } else {
                mapNode(view, parent) { target -> if (target["children"] is JsonArray) insertInto(target, "children", node, index)?.let(::listOf) else null }
            }
        }
        else -> null
    }

    private fun merge(node: JsonObject, props: JsonObject): JsonObject {
        val out = LinkedHashMap(node)
        for ((name, value) in props) {
            if (name == "type" || name == "id") continue
            if (value is JsonNull) out.remove(name) else out[name] = value
        }
        return JsonObject(out)
    }

    private fun insertInto(owner: JsonObject, field: String, node: JsonObject, index: Int?): JsonObject? {
        val list = (owner[field] as? JsonArray)?.toMutableList() ?: return null
        val at = index ?: list.size
        if (at < 0 || at > list.size) return null
        list.add(at, node)
        return JsonObject(owner + (field to JsonArray(list)))
    }

    /**
     * [view] with the node [id] (in body, children or actions, depth first) replaced by what [change] returns (none:
     * removed). Null when no node has the id or [change] refuses.
     */
    private fun mapNode(view: JsonObject, id: String, change: (JsonObject) -> List<JsonObject>?): JsonObject? {
        var found = false
        var failed = false
        fun walk(array: JsonArray): JsonArray {
            if (found || failed) return array
            val out = ArrayList<JsonElement>(array.size)
            for (element in array) {
                val node = element as? JsonObject
                if (node == null || found || failed) {
                    out.add(element)
                    continue
                }
                if (node.str("id") == id) {
                    found = true
                    val replaced = change(node)
                    if (replaced == null) failed = true else out.addAll(replaced)
                    continue
                }
                var next: JsonObject = node
                for (field in listOf("actions", "children")) {
                    val list = next[field] as? JsonArray ?: continue
                    val walked = walk(list)
                    if (walked !== list) next = JsonObject(next + (field to walked))
                }
                out.add(next)
            }
            return if (found) JsonArray(out) else array
        }
        var result = view
        for (field in listOf("body", "actions")) {
            val list = result[field] as? JsonArray ?: continue
            val walked = walk(list)
            if (walked !== list) result = JsonObject(result + (field to walked))
        }
        return if (found && !failed) result else null
    }

    /** A field value from the app's state as JSON, for `values`. */
    fun value(element: JsonElement?): JsonPrimitive? = (element as? JsonPrimitive)?.takeIf { it.isString || it.booleanOrNull != null || it.doubleOrNull != null }
}
