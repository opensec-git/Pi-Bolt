package opensec.pibolt.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Space

/**
 * A pushed screen's pinned bar (Secrets; Settings and Runtimes use [LargeTitlePage]): 56dp under the status bar, a
 * glass back button, the title
 * in title style (a heading for TalkBack) with an optional meta line under it, then [actions]. A 1dp line appears under
 * it once the content [scrolled]; the bar keeps its canvas fill so content never shows through.
 */
@Composable
fun ScreenTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    scrolled: Boolean = false,
    backLabel: String = "Back",
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = Pi.colors
    Column(modifier.fillMaxWidth().background(colors.canvas).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = Space.sm, end = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, backLabel, onClick = onBack)
            Spacer(Modifier.width(Space.sm))
            Column(Modifier.weight(1f).padding(end = Space.sm)) {
                Text(title, style = Pi.type.title, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                if (!subtitle.isNullOrBlank()) Text(subtitle, style = Pi.type.meta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            actions?.invoke(this)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(if (scrolled) colors.line else colors.canvas))
    }
}

/** One paragraph under a [ScreenTopBar] that says what the screen is for (body ink2, 20dp inset). */
@Composable
fun ScreenIntro(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Pi.type.body, color = Pi.colors.ink2, modifier = modifier.padding(start = Space.xl, end = Space.xl, top = Space.xs, bottom = Space.md))
}
