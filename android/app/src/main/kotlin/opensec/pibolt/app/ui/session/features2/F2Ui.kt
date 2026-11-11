package opensec.pibolt.app.ui.session.features2

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.components.EmptyState
import opensec.pibolt.app.ui.components.ListRow
import opensec.pibolt.app.ui.components.LoadingRow
import opensec.pibolt.app.ui.components.PiCard
import opensec.pibolt.app.ui.components.SwitchRow
import opensec.pibolt.app.ui.components.Tag
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Space

// The features2 sheets' names for the shared components (ui/components): kept so their call sites stay as they are.

/** A list row: [ListRow] with the sheet inset. */
@Composable
fun F2Row(
    title: String,
    meta: String?,
    icon: ImageVector?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    iconTint: Color = Pi.colors.ink3,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    titleMaxLines: Int = 2,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) = ListRow(
    title = title,
    modifier = modifier,
    meta = meta,
    icon = icon,
    iconTint = iconTint,
    onClick = onClick,
    onLongClick = onLongClick,
    enabled = enabled,
    titleMaxLines = titleMaxLines,
    trailing = trailing,
)

/** A switch row: [SwitchRow]. */
@Composable
fun F2Toggle(title: String, meta: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) =
    SwitchRow(title, checked, onChange, meta = meta, enabled = enabled, padding = PaddingValues(horizontal = Space.xl, vertical = 6.dp))

@Composable
fun F2Loading(label: String) = LoadingRow(label, Modifier.padding(vertical = Space.lg))

/**
 * Nothing to list: an [EmptyState] card with an optional glyph ([icon], what is missing); the first sentence is its
 * title, the rest the explanation.
 */
@Composable
fun F2Empty(text: String, icon: ImageVector? = null) {
    val cut = text.indexOf(". ").takeIf { it in 1 until text.length - 2 }
    EmptyState(
        title = if (cut != null) text.substring(0, cut) else text.removeSuffix("."),
        message = cut?.let { text.substring(it + 2) },
        icon = icon,
        modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.sm),
    )
}

/** A small rounded tag (source, state): [Tag]. */
@Composable
fun F2Tag(text: String, color: Color = Pi.colors.ink2, fill: Color = Pi.colors.surface3) = Tag(text, color = color, fill = fill)

/** A labelled block inside a sheet: a [PiCard] well with the sheet's 16dp inset. */
@Composable
fun F2Well(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    PiCard(modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = 6.dp), padding = PaddingValues(Space.md)) { content() }
}

@Composable
fun F2Section(text: String) {
    Text(text, style = Pi.type.metaStrong, color = Pi.colors.ink3, modifier = Modifier.padding(start = Space.xl, end = Space.xl, top = 14.dp, bottom = Space.xs))
}
