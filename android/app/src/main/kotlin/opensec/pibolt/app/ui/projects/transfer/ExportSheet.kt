package opensec.pibolt.app.ui.projects.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.components.PiSheet
import opensec.pibolt.app.ui.components.PrimaryButton
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.components.SheetHeader
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.components.rememberSheetCloser
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius

/** What the export sheet asks for: a zip saved through the system's file picker, or sent through the share sheet. */
data class ExportChoice(val share: Boolean, val skip: Set<String>)

/**
 * "Export as zip" for a project or a folder ([title] names it): leave out `.git` and dependency folders (on by
 * default, see [ZipTransfer.HEAVY]), then Save to… (ACTION_CREATE_DOCUMENT) or Share.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(title: String, onChoose: (ExportChoice) -> Unit, onDismiss: () -> Unit) {
    val colors = Pi.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val close = rememberSheetCloser(state)
    var lean by rememberSaveable { mutableStateOf(true) }
    PiSheet(onDismiss = onDismiss, state = state) {
        SheetHeader("Export $title", subtitle = "A zip of the folder. Symlinks are left out.")
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .pressable({ lean = !lean }, shape = RoundedCornerShape(Radius.sm), pressedScale = 1f, role = Role.Checkbox, label = "Leave out .git and dependencies")
                .semantics { stateDescription = if (lean) "On" else "Off" }
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (lean) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank, contentDescription = null, tint = if (lean) colors.accent else colors.ink3, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Leave out .git and dependencies", style = Pi.type.body, color = colors.ink1)
                Text("node_modules, .gradle, .venv, caches", style = Pi.type.meta, color = colors.ink3)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val skip = if (lean) ZipTransfer.HEAVY else emptySet()
            SecondaryButton("Share", onClick = { close { onChoose(ExportChoice(true, skip)) } }, icon = Icons.Rounded.Share, modifier = Modifier.weight(1f))
            PrimaryButton("Save to…", onClick = { close { onChoose(ExportChoice(false, skip)) } }, icon = Icons.Rounded.SaveAlt, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
    }
}
