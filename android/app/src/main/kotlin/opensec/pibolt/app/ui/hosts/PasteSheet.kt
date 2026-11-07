package opensec.pibolt.app.ui.hosts

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.components.FieldHint
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.PiTextField
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.TextAction
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.protocol.PairingPayload

/**
 * Paste a `pibolt://pair#…` link. Prefilled from the clipboard when it holds one. [onSubmit] returns an error to show,
 * or null when the link was used (the sheet then closes). Pasting is one step: the user brought the link here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasteSheet(onDismiss: () -> Unit, onSubmit: (String) -> String?) {
    val context = LocalContext.current
    val colors = Pi.colors
    val haptics = Pi.haptics
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf(clipboardPairingLink(context) ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    fun submit() {
        val e = onSubmit(text)
        error = e
        if (e != null) haptics.reject() else haptics.confirm()
    }
    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader("Paste a link", subtitle = "Run /\u2060remote-control copy on the computer.")
        PiTextField(
            value = text,
            onValueChange = {
                text = it
                error = null
            },
            placeholder = "pibolt://pair#…",
            style = Pi.type.monoSmall,
            maxLines = 4,
            isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { if (text.isNotBlank()) submit() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics { contentDescription = "Pairing link" },
        )
        error?.let { FieldHint(it, isError = true, modifier = Modifier.padding(horizontal = 16.dp)) }
        TextAction("Paste from clipboard", onClick = {
            clipboardText(context)?.let {
                text = it
                error = null
            }
        }, color = colors.ink2, modifier = Modifier.padding(start = 8.dp))
        Spacer(Modifier.height(8.dp))
        PrimaryButton("Connect", onClick = ::submit, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
    }
}

fun clipboardText(context: Context): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()?.trim()
}

fun clipboardPairingLink(context: Context): String? =
    clipboardText(context)?.takeIf { PairingPayload.looksLikePairing(it) }
