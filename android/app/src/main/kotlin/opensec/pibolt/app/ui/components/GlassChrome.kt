package opensec.pibolt.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Space
import opensec.pibolt.app.ui.theme.Tnum

/**
 * Screen chrome of the redesign, built on the kit in Glass.kt (DESIGN.md "Components"): a bar of glass buttons, the
 * large page title under it, the quiet section head of an inset list and a glass chip for actions.
 */

/**
 * The side padding of a row of glass top-bar buttons: a [GlassIconButton] draws its 44dp circle inside a 48dp target,
 * so 2dp less than the 20dp gutter puts the circle's edge on the gutter, in line with the page title and list text.
 */
val GlassBarInset = Space.page - Space.xxs

/**
 * The glass top bar: 56dp under the status bar on the canvas, [leading] (a [GlassIconButton]: back or close) and
 * [actions] (more glass buttons) 20dp from the window's sides. A compact [title] (subhead ink1, a [subtitle] in
 * monoMeta ink3 under it) sits between them and fades in with [titleVisible], for when the [PageTitle] has scrolled
 * away; hidden, it is left out for TalkBack, so the page title is the one heading read. [center] replaces the title
 * with other content (the preview's address).
 */
@Composable
fun GlassBar(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    subtitleColor: Color = Pi.colors.ink3,
    titleVisible: Boolean = true,
    titleModifier: Modifier = Modifier,
    leading: (@Composable RowScope.() -> Unit)? = null,
    center: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = Pi.colors
    val alpha by animateFloatAsState(if (titleVisible) 1f else 0f, PiMotion.effects(), label = "bar-title")
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.canvas)
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = GlassBarInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke(this)
        if (center != null) {
            Box(Modifier.weight(1f).padding(horizontal = Space.sm), contentAlignment = Alignment.CenterStart) { center() }
        } else Column(
            Modifier
                .weight(1f)
                .padding(horizontal = Space.sm)
                .graphicsLayer { this.alpha = alpha }
                .then(if (titleVisible) Modifier.semantics(mergeDescendants = true) { heading() } else Modifier.clearAndSetSemantics { }),
        ) {
            if (title != null) Text(title, style = Pi.type.subhead, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = titleModifier)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = Pi.type.monoMeta, color = subtitleColor, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/**
 * The large calm title of a page (largeTitle ink1, a heading), 20dp in, with an optional [meta] line (rowMeta ink3)
 * and [trailing] content after the title (a pin glyph). [titleModifier] carries a shared element.
 */
@Composable
fun PageTitle(
    title: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    titleModifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = Pi.colors
    Column(modifier.fillMaxWidth().padding(start = Space.page, end = Space.page, top = Space.xs, bottom = Space.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = Pi.type.largeTitle,
                color = colors.ink1,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = titleModifier.weight(1f, fill = false).semantics { heading() },
            )
            trailing?.invoke(this)
        }
        if (!meta.isNullOrBlank()) Text(meta, style = Pi.type.rowMeta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.padding(top = Space.xxs))
    }
}

/**
 * The quiet head over an inset list ("Sessions", "Staged"): rowMeta ink3 at the 20dp gutter (a heading), an optional
 * [count] after it (tabular), and an optional [action] at the end (a [TextAction]). 44dp, 16dp above.
 */
@Composable
fun InsetSectionHead(title: String, modifier: Modifier = Modifier, count: Int? = null, action: (@Composable RowScope.() -> Unit)? = null) {
    val colors = Pi.colors
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = Space.lg, start = Space.page, end = if (action != null) Space.sm else Space.page)
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Pi.type.rowMeta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (count != null) {
                Spacer(Modifier.width(Space.sm))
                Text(count.toString(), style = Pi.type.rowMeta.copy(fontFeatureSettings = Tnum), color = colors.ink3, maxLines = 1)
            }
        }
        action?.invoke(this)
    }
}

/**
 * A glass action chip ("Pull", "History"): radius full, glass, 40dp drawn in a 48dp target, 14dp sides, an optional
 * 16dp [icon] and the [text] (label weight) in [tint]. Disabled, glyph and text drop to 40%. Press: [SOFT_PRESS].
 */
@Composable
fun GlassChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, tint: Color = Pi.colors.ink1, description: String? = null) {
    val colors = Pi.colors
    val ink = if (enabled) tint else tint.copy(alpha = 0.4f)
    Box(modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .heightIn(min = 40.dp)
                .pressable(onClick, shape = CircleShape, enabled = enabled, pressedScale = SOFT_PRESS, label = description ?: text)
                .glass(CircleShape, colors)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(IconSize.sm))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = Pi.type.label, color = ink, maxLines = 1)
        }
    }
}

/** "+12 −3" in the diff colours (rowMeta, tabular); a side at zero is left out, both at zero show nothing. */
@Composable
fun LineCounts(added: Int, removed: Int, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val style = Pi.type.rowMeta.copy(fontFeatureSettings = Tnum)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (added > 0) Text("+$added", style = style, color = colors.success, maxLines = 1)
        if (removed > 0) Text("−$removed", style = style, color = colors.danger, maxLines = 1)
    }
}
