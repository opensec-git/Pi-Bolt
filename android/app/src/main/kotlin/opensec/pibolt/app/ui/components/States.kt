package opensec.pibolt.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiColors
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Space

/**
 * Nothing to show yet, in the redesign's calm unboxed form (the workspace's empty views match it): an optional 22dp
 * glyph (ink2) in a 48dp glass circle, the title (rowTitle ink1, a heading), one or two lines of explanation (rowMeta
 * ink2) and at most one action, centred. Never a lone grey sentence.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = Pi.colors
    Column(modifier.fillMaxWidth().padding(horizontal = Space.xl, vertical = Space.xl), horizontalAlignment = Alignment.CenterHorizontally) {
        if (icon != null) {
            Box(Modifier.size(48.dp).glass(CircleShape, colors), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(IconSize.lg))
            }
            Spacer(Modifier.height(Space.lg))
        }
        Text(title, style = Pi.type.rowTitle, color = colors.ink1, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        if (!message.isNullOrBlank()) {
            Spacer(Modifier.height(Space.xs))
            Text(message, style = Pi.type.rowMeta, color = colors.ink2, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(Space.lg))
            action()
        }
    }
}

/** Loading: a 16dp Quad Think and the label (body ink3), centred, 48dp tall. Read once by TalkBack (polite). */
@Composable
fun LoadingRow(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = Space.md)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuadSpinner(QuadMode.Think, size = IconSize.sm, color = Pi.colors.ink2)
        Spacer(Modifier.width(Space.md))
        Text(label, style = Pi.type.body, color = Pi.colors.ink3)
    }
}

/** Something failed where content should be: a danger [Notice] with the reason and, when it may help, Retry. */
@Composable
fun ErrorState(title: String, modifier: Modifier = Modifier, message: String? = null, retryLabel: String = "Try again", onRetry: (() -> Unit)? = null) {
    Notice(
        text = message ?: title,
        title = if (message != null) title else null,
        kind = SnackKind.Error,
        modifier = modifier,
        actions = onRetry?.let { retry -> { TextAction(retryLabel, onClick = retry, color = Pi.colors.accent) } },
    )
}

/** The glyph and colours of a [Notice] of [kind]: soft fill and a 40% line for warning and danger, a card otherwise. */
internal data class NoticeStyle(val icon: ImageVector, val tint: Color, val fill: Color, val line: Color)

internal fun noticeStyle(kind: SnackKind, c: PiColors): NoticeStyle = when (kind) {
    SnackKind.Info -> NoticeStyle(Icons.Rounded.Info, c.ink2, c.surface1, c.line)
    SnackKind.Success -> NoticeStyle(Icons.Rounded.CheckCircle, c.success, c.surface1, c.line)
    SnackKind.Warning -> NoticeStyle(Icons.Rounded.WarningAmber, c.warning, c.warningSoft, c.warning.copy(alpha = 0.4f))
    SnackKind.Error -> NoticeStyle(Icons.Rounded.ErrorOutline, c.danger, c.dangerSoft, c.danger.copy(alpha = 0.4f))
}

/**
 * An inline notice or banner: radius md, a 20dp glyph in the kind's colour, an optional title (bodyStrong ink1), the
 * text (body; ink1 without a title, ink2 under one) and text actions at the end or under the text. Info and success
 * sit on a surface1 card; warning and danger on their soft fill with a 40% line. Text is never in the status colour:
 * the glyph and fill carry it, so it reads on every theme.
 */
@Composable
fun Notice(
    text: String,
    modifier: Modifier = Modifier,
    kind: SnackKind = SnackKind.Info,
    title: String? = null,
    icon: ImageVector? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = Pi.colors
    val style = noticeStyle(kind, colors)
    val shape = RoundedCornerShape(Radius.md)
    Column(
        modifier
            .fillMaxWidth()
            .background(style.fill, shape)
            .border(1.dp, style.line, shape)
            .padding(start = Space.md + 2.dp, end = if (actions != null) Space.xs else Space.md + 2.dp, top = Space.md, bottom = if (actions != null) Space.xs else Space.md),
    ) {
        Row(verticalAlignment = if (title == null) Alignment.CenterVertically else Alignment.Top) {
            Icon(icon ?: style.icon, contentDescription = null, tint = style.tint, modifier = Modifier.padding(top = if (title == null) 0.dp else 1.dp).size(IconSize.md))
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f).padding(end = Space.sm)) {
                if (title != null) Text(title, style = Pi.type.bodyStrong, color = colors.ink1)
                Text(text, style = Pi.type.body, color = if (title == null) colors.ink1 else colors.ink2)
            }
        }
        if (actions != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/** A 2dp bar: surface3 track, accent fill. Never an indeterminate animation: no fill while the total is unknown. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val f = fraction.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(colors.surface3, RoundedCornerShape(1.dp))
            .semantics { contentDescription = "${(f * 100).toInt()} percent" },
    ) {
        Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(colors.accent, RoundedCornerShape(1.dp)))
    }
}
