package opensec.pibolt.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Space

/**
 * A search field on glass (the Chats list): a full-radius glass pill 48dp tall, a 20dp search glyph in ink3, the query
 * in rowMeta ink1 over an ink3 [placeholder], and a clear button while there is text. Search on the keyboard closes it.
 */
@Composable
fun GlassSearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "Search") {
    val colors = Pi.colors
    val style = Pi.type.rowMeta
    val focus = LocalFocusManager.current
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = style.copy(color = colors.ink1),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        modifier = modifier,
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .glass(CircleShape, colors)
                    .padding(start = Space.lg, end = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.ink3, modifier = Modifier.size(IconSize.md))
                Spacer(Modifier.width(Space.sm))
                Box(Modifier.weight(1f).padding(vertical = Space.md)) {
                    if (query.isEmpty()) Text(placeholder, style = style, color = colors.ink3, maxLines = 1)
                    inner()
                }
                if (query.isNotEmpty()) {
                    IconAction(Icons.Rounded.Close, "Clear search", onClick = { onQuery("") }, tint = colors.ink3, size = IconSize.md)
                } else {
                    Spacer(Modifier.width(Space.md))
                }
            }
        },
    )
}
