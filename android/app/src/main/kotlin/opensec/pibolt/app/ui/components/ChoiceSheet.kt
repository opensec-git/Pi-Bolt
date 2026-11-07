package opensec.pibolt.app.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi

/**
 * One choice of a few (a Settings row's value: density, thinking level, preview lines): a sheet with the [title] and
 * one [InsetRow] per option, the [selected] one with an accent check (read as selected). A pick applies and closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ChoiceSheet(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    val haptics = Pi.haptics
    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader(title)
        options.forEachIndexed { i, (value, label) ->
            val on = value == selected
            InsetRow(
                label,
                onClick = {
                    if (!on) haptics.tick()
                    onSelect(value)
                    close(onDismiss)
                },
                modifier = Modifier.semantics { this.selected = on },
                divider = i < options.lastIndex,
                trailing = if (on) {
                    { Icon(Icons.Rounded.Check, contentDescription = null, tint = Pi.colors.accent, modifier = Modifier.size(IconSize.md)) }
                } else {
                    null
                },
            )
        }
    }
}
