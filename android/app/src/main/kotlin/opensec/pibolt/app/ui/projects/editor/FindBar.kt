package opensec.pibolt.app.ui.projects.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.PiTextField
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius

/** The find bar's state, kept by the viewer across tabs: the query and its options, the replacement, the results. */
@Stable
class FindState {
    var open by mutableStateOf(false)
    var replacing by mutableStateOf(false)
    var query by mutableStateOf("")
    var regex by mutableStateOf(false)
    var matchCase by mutableStateOf(false)
    var wholeWord by mutableStateOf(false)
    var replacement by mutableStateOf("")
    var result by mutableStateOf(FindResult.EMPTY)
    var current by mutableIntStateOf(0)

    /** Bumped to move the focus into the query field (Ctrl+F while the bar is open). */
    var focusTick by mutableIntStateOf(0)

    /**
     * Bumped when the viewer should go to the current match: a step, a new query, or the results after a replace.
     * Results refreshed by typing in the editor do not move the cursor.
     */
    var navTick by mutableIntStateOf(0)

    /** The next results go to their current match (set by a replace). */
    var navigateOnResult = false

    val options: FindOptions get() = FindOptions(query, regex, matchCase, wholeWord)
    val matches: List<TextMatch> get() = if (open) result.matches else emptyList()

    fun step(by: Int) {
        val n = result.matches.size
        if (n > 0) current = ((current + by) % n + n) % n
        navTick++
    }
}

/**
 * Find (and replace): the query, "3 of 12", previous / next and close; a row of toggles (match case, whole word,
 * regex, replace); and, when replacing, the replacement with Replace and Replace all. Enter in the query goes to the
 * next match, in the replacement it replaces. A bad regex or a missing group shows its reason under the bar.
 */
@Composable
fun FindReplaceBar(state: FindState, canReplace: Boolean, onReplace: () -> Unit, onReplaceAll: () -> Unit, onClose: () -> Unit) {
    val colors = Pi.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.focusTick) { runCatching { focus.requestFocus() } }
    val matches = state.result.matches
    val error = state.result.error ?: if (state.replacing) FindReplace.replacementError(state.options, state.replacement) else null
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            PiTextField(
                value = state.query,
                onValueChange = { state.query = it },
                placeholder = if (state.regex) "Find (regex)" else "Find in file",
                singleLine = true,
                style = Pi.type.mono,
                isError = state.result.error != null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSearch = { state.step(1) }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            if (state.query.isNotEmpty()) {
                Text(
                    FindReplace.counter(state.current, matches.size, state.result.capped),
                    style = Pi.type.meta,
                    color = if (matches.isNotEmpty()) colors.ink2 else colors.warning,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            IconAction(Icons.Rounded.KeyboardArrowUp, "Previous match", onClick = { state.step(-1) }, enabled = matches.isNotEmpty())
            IconAction(Icons.Rounded.KeyboardArrowDown, "Next match", onClick = { state.step(1) }, enabled = matches.isNotEmpty())
            IconAction(Icons.Rounded.Close, "Close find", onClick = onClose, size = 20.dp)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Toggle("Aa", "Match case", state.matchCase) { state.matchCase = it }
            Toggle("ab", "Whole word", state.wholeWord, underline = true) { state.wholeWord = it }
            Toggle(".*", "Regular expression", state.regex) { state.regex = it }
            Spacer(Modifier.weight(1f))
            if (canReplace) {
                IconAction(
                    Icons.Rounded.SwapHoriz,
                    if (state.replacing) "Hide replace" else "Replace",
                    onClick = { state.replacing = !state.replacing },
                    tint = if (state.replacing) colors.accent else colors.ink2,
                )
            }
        }
        if (state.replacing && canReplace) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                PiTextField(
                    value = state.replacement,
                    onValueChange = { state.replacement = it },
                    placeholder = if (state.regex) "Replace with ($1 for a group)" else "Replace with",
                    singleLine = true,
                    style = Pi.type.mono,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onDone = { if (matches.isNotEmpty() && error == null) onReplace() }),
                    modifier = Modifier.weight(1f),
                )
                TextAction("Replace", onClick = onReplace, color = colors.accent, enabled = matches.isNotEmpty() && error == null)
                TextAction("All", onClick = onReplaceAll, color = colors.accent, enabled = matches.isNotEmpty() && error == null, modifier = Modifier.semantics { contentDescription = "Replace all" })
            }
        }
        if (error != null) {
            Text(error, style = Pi.type.meta, color = colors.danger, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp))
        }
    }
}

/** A 48dp text toggle ("Aa", ".*"): accent when on; TalkBack reads it as a switch with its name. */
@Composable
private fun Toggle(label: String, description: String, on: Boolean, underline: Boolean = false, onChange: (Boolean) -> Unit) {
    val colors = Pi.colors
    Box(
        Modifier
            .size(48.dp)
            .pressable({ onChange(!on) }, shape = RoundedCornerShape(Radius.sm), pressedScale = 0.92f, role = Role.Switch, label = description)
            .semantics { stateDescription = if (on) "$description on" else "$description off" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .padding(4.dp)
                .then(if (on) Modifier.background(colors.accent.copy(alpha = 0.18f), RoundedCornerShape(Radius.sm)) else Modifier)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                label,
                style = if (underline) Pi.type.monoSmall.copy(textDecoration = TextDecoration.Underline) else Pi.type.monoSmall,
                color = if (on) colors.accent else colors.ink2,
            )
        }
    }
}
