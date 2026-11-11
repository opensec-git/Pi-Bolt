package opensec.pibolt.app.ui.diagram

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import opensec.pibolt.app.ui.theme.PiColors

/**
 * Mermaid's "base" theme variables from the app palette, as the JSON object the renderer page takes. Nodes are
 * surface3 with a solid ink border and ink1 text on the surface1 card; edges ink2. Charts (pie slices, gantt tasks,
 * mind-map branches) take a fixed categorical set made from the syntax and status colours, dimmed toward the surface
 * where text sits on them. Mermaid needs opaque colours, so the palette's translucent lines are blended first.
 */
@Immutable
class MermaidTheme private constructor(val json: String, val background: Int) {
    override fun equals(other: Any?): Boolean = other is MermaidTheme && other.json == json

    override fun hashCode(): Int = json.hashCode()

    companion object {
        fun of(colors: PiColors): MermaidTheme {
            val vars = variables(colors)
            val dark = colors.canvas.luminance() < 0.5f
            return MermaidTheme(JsonText.obj(mapOf("dark" to dark, "vars" to vars)), colors.surface1.toArgb())
        }

        /** Every value is `#rrggbb` except the font, sizes, `dropShadow` and `useGradient`. */
        fun variables(c: PiColors): Map<String, Any> {
            val border = lerp(c.surface3, c.ink1, 0.26f)
            val categorical = listOf(c.synType, c.synString, c.synNumber, c.tint4, c.success, c.warning, c.tint3, c.tint1, c.danger, c.diffAddFg, c.diffDelFg, c.ink2)
            val dim = categorical.map { lerp(c.surface2, it, 0.42f) }
            val v = LinkedHashMap<String, Any>()
            fun put(key: String, color: Color) {
                v[key] = hex(color)
            }
            v["fontFamily"] = "Inter, sans-serif"
            v["fontSize"] = "14px"
            // Flat, like the rest of the app: no node shadows or gradients.
            v["dropShadow"] = "none"
            v["useGradient"] = false
            put("background", c.surface1)
            put("primaryColor", c.surface3)
            put("primaryTextColor", c.ink1)
            put("primaryBorderColor", border)
            put("secondaryColor", c.surface2)
            put("secondaryTextColor", c.ink1)
            put("secondaryBorderColor", border)
            put("tertiaryColor", c.surface2)
            put("tertiaryTextColor", c.ink1)
            put("tertiaryBorderColor", border)
            put("mainBkg", c.surface3)
            put("nodeBorder", border)
            put("nodeTextColor", c.ink1)
            put("textColor", c.ink1)
            put("titleColor", c.ink1)
            put("lineColor", c.ink2)
            put("defaultLinkColor", c.ink2)
            put("clusterBkg", c.surface2)
            put("clusterBorder", border)
            put("edgeLabelBackground", c.surface2)
            put("errorBkgColor", c.danger)
            put("errorTextColor", c.canvas)
            // Sequence
            put("actorBkg", c.surface3)
            put("actorBorder", border)
            put("actorTextColor", c.ink1)
            put("actorLineColor", c.ink4)
            put("signalColor", c.ink2)
            put("signalTextColor", c.ink1)
            put("labelBoxBkgColor", c.surface3)
            put("labelBoxBorderColor", border)
            put("labelTextColor", c.ink1)
            put("loopTextColor", c.ink2)
            put("noteBkgColor", lerp(c.surface2, c.warning, 0.16f))
            put("noteBorderColor", lerp(c.surface2, c.warning, 0.5f))
            put("noteTextColor", c.ink1)
            put("activationBkgColor", c.surface2)
            put("activationBorderColor", border)
            put("sequenceNumberColor", c.canvas)
            // State and class
            put("labelColor", c.ink1)
            put("stateBkg", c.surface3)
            put("stateLabelColor", c.ink1)
            put("labelBackgroundColor", c.surface2)
            put("compositeBackground", c.surface2)
            put("compositeTitleBackground", c.surface3)
            put("compositeBorder", border)
            put("altBackground", c.surface2)
            put("innerEndBackground", c.ink2)
            put("specialStateColor", c.ink2)
            put("transitionColor", c.ink2)
            put("transitionLabelColor", c.ink2)
            put("classText", c.ink1)
            // Entity relationship
            put("attributeBackgroundColorOdd", c.surface2)
            put("attributeBackgroundColorEven", c.surface3)
            put("rowOdd", c.surface2)
            put("rowEven", c.surface3)
            // Gantt
            put("sectionBkgColor", lerp(c.surface1, c.ink1, 0.05f))
            put("altSectionBkgColor", c.surface1)
            put("sectionBkgColor2", lerp(c.surface1, c.ink1, 0.05f))
            put("taskBkgColor", dim[0])
            put("taskBorderColor", categorical[0])
            put("taskTextColor", c.ink1)
            put("taskTextLightColor", c.ink1)
            put("taskTextDarkColor", c.ink1)
            put("taskTextOutsideColor", c.ink1)
            put("taskTextClickableColor", c.accent)
            put("activeTaskBkgColor", dim[1])
            put("activeTaskBorderColor", categorical[1])
            put("doneTaskBkgColor", c.surface3)
            put("doneTaskBorderColor", c.ink4)
            put("critBkgColor", lerp(c.surface2, c.danger, 0.42f))
            put("critBorderColor", c.danger)
            put("gridColor", c.ink4)
            put("todayLineColor", c.warning)
            put("excludeBkgColor", c.surface2)
            // Pie: full colours with dark labels.
            categorical.forEachIndexed { i, color -> put("pie${i + 1}", color) }
            put("pieTitleTextColor", c.ink1)
            put("pieSectionTextColor", c.canvas)
            put("pieLegendTextColor", c.ink1)
            put("pieStrokeColor", c.surface1)
            put("pieOuterStrokeColor", c.ink4)
            v["pieStrokeWidth"] = "1px"
            v["pieOuterStrokeWidth"] = "1px"
            v["pieOpacity"] = "1"
            // Mind maps, timelines and journeys colour by section; git graphs by branch.
            dim.forEachIndexed { i, color ->
                put("cScale$i", color)
                put("cScaleLabel$i", c.ink1)
                put("cScalePeer$i", categorical[i])
                put("cScaleInv$i", categorical[i])
            }
            categorical.take(8).forEachIndexed { i, color ->
                put("git$i", color)
                put("gitBranchLabel$i", c.canvas)
            }
            put("commitLabelColor", c.ink1)
            put("commitLabelBackground", c.surface3)
            put("tagLabelColor", c.ink1)
            put("tagLabelBackground", c.surface3)
            put("tagLabelBorder", border)
            return v
        }

        fun hex(color: Color): String = "#" + (color.toArgb() and 0xFFFFFF).toString(16).padStart(6, '0')
    }
}
