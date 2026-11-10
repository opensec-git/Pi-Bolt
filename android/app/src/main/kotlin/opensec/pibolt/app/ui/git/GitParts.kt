package opensec.pibolt.app.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.git.Change
import opensec.pibolt.app.git.FileDiff
import opensec.pibolt.app.git.Hunk
import opensec.pibolt.app.ui.components.GlassBar
import opensec.pibolt.app.ui.components.GlassIconButton
import opensec.pibolt.app.ui.session.DiffKind
import opensec.pibolt.app.ui.session.DiffLine
import opensec.pibolt.app.ui.session.parseDiff
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiColors
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Space

/** A code card (a hunk, a commit's file): surface1 with a 1dp line, radius lg, 16dp in from the sides, clipped. */
@Composable
fun GitCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = Pi.colors
    val shape = RoundedCornerShape(Radius.lg)
    Column(
        modifier
            .padding(horizontal = Space.lg, vertical = Space.xs)
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface1, shape)
            .border(1.dp, colors.line, shape),
        content = content,
    )
}

/**
 * The change letter in a 20dp slot, as Pi-Bolt Desktop's `.gx-st`: mono SemiBold with no tile; added and untracked
 * success, deleted and conflicts danger, modified warning, renamed and copied ink2. TalkBack reads the word.
 */
@Composable
fun ChangeBadge(change: Change?) {
    val c = change ?: Change.Modified
    Box(Modifier.size(20.dp).clearAndSetSemantics { contentDescription = c.label }, contentAlignment = Alignment.Center) {
        Text(
            if (c == Change.Untracked) "U" else c.letter.toString(),
            style = Pi.type.monoMeta.copy(fontWeight = FontWeight.SemiBold),
            color = changeColor(c, Pi.colors),
            textAlign = TextAlign.Center,
        )
    }
}

fun changeColor(c: Change, colors: PiColors): Color = when (c) {
    Change.Added, Change.Untracked -> colors.success
    Change.Deleted, Change.Conflicted -> colors.danger
    Change.Modified -> colors.warning
    else -> colors.ink2
}

/**
 * Full-screen overlay top bar, the workspace's [GlassBar]: a glass leading button, a title over a mono subtitle, then
 * [actions] (glass buttons and chips). One rule across the workspace (file viewer, diff, history): a view that covers
 * the project closes with ✕ ("Close"); a step inside one (a commit opened from the history) goes back with ←
 * ([back] true, labelled [backLabel]). [titleVisible] fades the title for a view that shows a large one under the bar.
 */
@Composable
fun OverlayBar(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    back: Boolean = false,
    backLabel: String = "Back",
    titleVisible: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    GlassBar(
        title = title,
        subtitle = subtitle,
        titleVisible = titleVisible,
        leading = {
            if (back) {
                GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, backLabel, onClick = onBack)
            } else {
                GlassIconButton(Icons.Rounded.Close, "Close", onClick = onBack)
            }
        },
        actions = actions,
    )
}

/** A hunk's rows for the shared diff renderer, without its `@@` row (the hunk header shows it). */
fun hunkRows(hunk: Hunk): List<DiffLine> = parseDiff(hunk.text).filter { it.kind != DiffKind.Hunk }

/** Rows of a diff shown at most up to [MAX_ROWS] per file; the rest is summarised. */
const val MAX_ROWS = 4000

fun FileDiff.rowCount(): Int = hunks.sumOf { it.lines.size }

/** "src/app/Main.kt" -> ("Main.kt", "src/app"). */
fun splitPath(path: String): Pair<String, String> = path.substringAfterLast('/') to path.substringBeforeLast('/', "")
