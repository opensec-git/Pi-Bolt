package opensec.pibolt.app.ui.session.features

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Tnum

/**
 * The top bar while searching the transcript: back closes, the field searches as you type, "3 of 12" and up/down
 * step through the hits (newest first, as the reader is at the bottom). The current hit's line shows beneath.
 */
@Composable
fun SearchBar(
    query: String,
    onQuery: (String) -> Unit,
    hits: Int,
    current: Int,
    snippet: String?,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = Pi.colors
    val type = Pi.type
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Close search", onClick = onClose, tint = colors.ink1)
            Box(Modifier.weight(1f).padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text("Search this session", style = type.body, color = colors.ink3)
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = type.body.copy(color = colors.ink1),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    // Hits start at the newest (the reader is at the bottom): the keyboard's Search walks up to older
                    // ones, like the up arrow, instead of wrapping to the oldest hit first.
                    keyboardActions = KeyboardActions(onSearch = { onPrevious() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (query.isNotBlank()) {
                Text(
                    if (hits == 0) "No match" else "${current + 1} of $hits",
                    style = type.meta.copy(fontFeatureSettings = Tnum),
                    color = if (hits == 0) colors.warning else colors.ink3,
                    modifier = Modifier.padding(horizontal = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            IconAction(Icons.Rounded.KeyboardArrowUp, "Previous match", onClick = onPrevious, enabled = hits > 0, tint = colors.ink1)
            IconAction(Icons.Rounded.KeyboardArrowDown, "Next match", onClick = onNext, enabled = hits > 0, tint = colors.ink1)
        }
        if (!snippet.isNullOrBlank()) {
            Text(
                snippet,
                style = type.meta,
                color = colors.ink2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 8.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
    }
}
