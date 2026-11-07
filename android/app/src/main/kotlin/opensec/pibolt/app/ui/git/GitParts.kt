package opensec.pibolt.app.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.git.Change
import opensec.pibolt.app.git.FileDiff
import opensec.pibolt.app.git.Hunk
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.session.DiffKind
import opensec.pibolt.app.ui.session.DiffLine
import opensec.pibolt.app.ui.session.parseDiff
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiColors
import opensec.pibolt.app.ui.theme.Radius

/** surface1 card with a 1dp line, radius 12, 16dp in from the sides. */
@Composable
fun GitCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = Pi.colors
    val shape = RoundedCornerShape(Radius.md)
    Column(
        modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .background(colors.surface1, shape)
            .border(1.dp, colors.line, shape),
        content = content,
    )
}

/** The change letter in a 20dp tile: added and untracked in success, deleted and conflicts in danger, others ink2. */
@Composable
fun ChangeBadge(change: Change?) {
    val colors = Pi.colors
    val c = change ?: Change.Modified
    val fg = changeColor(c, colors)
    Box(
        Modifier
            .size(20.dp)
            .background(colors.surface2, RoundedCornerShape(Radius.xs))
            .clearAndSetSemantics { contentDescription = c.label },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (c == Change.Untracked) "U" else c.letter.toString(), style = Pi.type.micro, color = fg, textAlign = TextAlign.Center)
    }
}

fun changeColor(c: Change, colors: PiColors): Color = when (c) {
    Change.Added, Change.Untracked -> colors.success
    Change.Deleted, Change.Conflicted -> colors.danger
    Change.Renamed, Change.Copied -> colors.warning
    else -> colors.ink2
}

/** "+12 −3" in the diff colours; nothing for zero. */
@Composable
fun Counts(added: Int, removed: Int) {
    val colors = Pi.colors
    if (added > 0) Text("+$added", style = Pi.type.monoMeta, color = colors.success)
    if (added > 0 && removed > 0) Spacer(Modifier.width(6.dp))
    if (removed > 0) Text("−$removed", style = Pi.type.monoMeta, color = colors.danger)
}

/** Full-screen overlay top bar: back, a title over a mono subtitle, then [actions]. */
@Composable
fun OverlayBar(title: String, subtitle: String?, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    val colors = Pi.colors
    Row(
        Modifier.fillMaxWidth().background(colors.canvas).statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack, tint = colors.ink1)
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(title, style = Pi.type.subhead, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = Pi.type.monoMeta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        }
        actions()
    }
}

/** A hunk's rows for the shared diff renderer, without its `@@` row (the hunk header shows it). */
fun hunkRows(hunk: Hunk): List<DiffLine> = parseDiff(hunk.text).filter { it.kind != DiffKind.Hunk }

/** Rows of a diff shown at most up to [MAX_ROWS] per file; the rest is summarised. */
const val MAX_ROWS = 4000

fun FileDiff.rowCount(): Int = hunks.sumOf { it.lines.size }

/** "src/app/Main.kt" -> ("Main.kt", "src/app"). */
fun splitPath(path: String): Pair<String, String> = path.substringAfterLast('/') to path.substringBeforeLast('/', "")
