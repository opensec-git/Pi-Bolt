package opensec.pibolt.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.PiMotion
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Space

/** A form field's label: metaStrong ink2, 4dp in from the field's edge. */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Pi.type.metaStrong, color = Pi.colors.ink2, modifier = modifier.padding(start = Space.xs, top = Space.sm + 2.dp, bottom = 6.dp))
}

/** The line under a field: a hint in meta ink3, or the error in danger (then announced). */
@Composable
fun FieldHint(text: String, modifier: Modifier = Modifier, isError: Boolean = false) {
    Text(
        text,
        style = Pi.type.meta,
        color = if (isError) Pi.colors.danger else Pi.colors.ink3,
        modifier = modifier.padding(start = Space.xs, top = Space.xs).semantics { if (isError) liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * A secret (API key, token, variable value): mono, obscured by default, Password keyboard without autocorrect or
 * suggestions. The end slot is Paste while empty (when [onPaste] is given) and Show / Hide once filled. Same frame as
 * [PiTextField] (surface1, radius md, lineStrong that turns into the focus line, danger on error), 52dp tall.
 * Callers keep the value in plain `remember`, never saved instance state, and clear it after use.
 */
@Composable
fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    shown: Boolean,
    onToggleShown: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    label: String = "Secret",
    isError: Boolean = false,
    onPaste: (() -> Unit)? = null,
    imeAction: ImeAction = ImeAction.Done,
    onDone: () -> Unit = {},
) {
    val colors = Pi.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border by animateColorAsState(
        when {
            isError -> colors.danger.copy(alpha = 0.7f)
            focused -> colors.lineFocus
            else -> colors.lineStrong
        },
        PiMotion.effects(),
        label = "secret-field",
    )
    val shape = RoundedCornerShape(Radius.md)
    val style = Pi.type.mono
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = style.copy(color = colors.ink1),
        cursorBrush = SolidColor(colors.accent),
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }, onNext = { onDone() }),
        interactionSource = interaction,
        modifier = modifier.fillMaxWidth().semantics {
            password()
            contentDescription = label
        },
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .background(colors.surface1, shape)
                    .border(1.dp, border, shape)
                    .padding(start = Space.md + 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f).padding(top = Space.md, bottom = Space.md, end = if (value.isEmpty() && onPaste == null) Space.md + 2.dp else 0.dp)) {
                    if (value.isEmpty()) Text(placeholder, style = style, color = colors.ink3, maxLines = 1)
                    inner()
                }
                when {
                    value.isEmpty() && onPaste != null -> TextAction("Paste", onClick = onPaste, color = colors.accent)
                    value.isNotEmpty() -> IconAction(
                        if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        if (shown) "Hide $label" else "Show $label",
                        onClick = onToggleShown,
                        size = IconSize.md,
                    )
                }
            }
        },
    )
}
