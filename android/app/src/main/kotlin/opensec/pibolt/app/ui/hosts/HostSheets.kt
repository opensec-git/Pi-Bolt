package opensec.pibolt.app.ui.hosts

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.data.Connections
import opensec.pibolt.app.data.Host
import opensec.pibolt.app.ui.components.InsetRow
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.PiTextField
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.ButtonSize
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.theme.IconSize
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Space

/** Add a computer: scan its pairing code or paste its link. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddComputerSheet(onScan: () -> Unit, onPaste: () -> Unit, onDismiss: () -> Unit) {
    val colors = Pi.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader("Add a computer", subtitle = "Run /⁠remote-control on it, then scan its code.")
        InsetRow("Scan a pairing code", onClick = { close { onDismiss(); onScan() } }, leading = { Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(IconSize.md)) })
        InsetRow("Paste a pairing link", onClick = { close { onDismiss(); onPaste() } }, divider = false, leading = { Icon(Icons.Rounded.ContentPaste, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(IconSize.md)) })
        Spacer(Modifier.navigationBarsPadding().height(Space.lg))
    }
}

/**
 * Manage a computer: its status, how this phone reaches it (each endpoint with its kind: LAN, Tailscale, Tunnel), a
 * name for it on this phone, and Forget (which asks first).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageComputerSheet(host: Host, status: String, onRename: (String) -> Unit, onForget: () -> Unit, onDismiss: () -> Unit) {
    val colors = Pi.colors
    val type = Pi.type
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    var name by remember(host.id) { mutableStateOf(host.displayName) }
    val save = { if (name.isNotBlank() && name.trim() != host.displayName) onRename(name.trim()) }
    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader(host.displayName, subtitle = status)
        Text("Connection", style = type.rowMeta, color = colors.ink3, modifier = Modifier.padding(horizontal = Space.page, vertical = Space.sm).semantics { heading() })
        for (endpoint in host.endpoints) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = Space.page), verticalAlignment = Alignment.CenterVertically) {
                Text(Connections.kind(endpoint), style = type.rowTitle, color = colors.ink1, modifier = Modifier.width(112.dp))
                Text(endpoint, style = type.monoMeta, color = colors.ink3, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, modifier = Modifier.weight(1f))
            }
        }
        Text("Name on this phone", style = type.rowMeta, color = colors.ink3, modifier = Modifier.padding(start = Space.page, end = Space.page, top = Space.lg, bottom = Space.sm).semantics { heading() })
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.page), verticalAlignment = Alignment.CenterVertically) {
            PiTextField(
                name,
                { name = it },
                modifier = Modifier.weight(1f),
                placeholder = host.name,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
            )
            Spacer(Modifier.width(Space.md))
            PrimaryButton("Save", onClick = save, enabled = name.isNotBlank() && name.trim() != host.displayName, size = ButtonSize.Small)
        }
        Spacer(Modifier.height(Space.md))
        InsetRow(
            "Forget computer",
            onClick = { close { onDismiss(); onForget() } },
            divider = false,
            leading = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = colors.danger, modifier = Modifier.size(IconSize.md)) },
        )
        Spacer(Modifier.navigationBarsPadding().height(Space.lg))
    }
}
