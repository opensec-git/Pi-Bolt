package opensec.pibolt.app.ui.session

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.ui.theme.Mono
import opensec.pibolt.protocol.rpc.ModelInfo
import opensec.pibolt.protocol.session.DialogKind
import opensec.pibolt.protocol.session.DialogRequest

/** An extension dialog (select, confirm, input, editor). The same dialog is open in the terminal; first answer wins. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogSheet(
    dialog: DialogRequest,
    onValue: (String) -> Unit,
    onConfirm: (Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = sheet) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Pi asks", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(dialog.title.ifBlank { "Answer needed" }, style = MaterialTheme.typography.titleLarge)
            dialog.message?.takeIf { it.isNotBlank() }?.let {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
                    Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                }
            }
            dialog.timeoutMs?.let {
                Text(
                    "Answer within ${it / 1000} s, or the default is used.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (dialog.kind) {
                DialogKind.Select -> {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(dialog.options) { option ->
                            Text(
                                option,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onValue(option) }
                                    .padding(vertical = 14.dp, horizontal = 4.dp),
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                    TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
                }
                DialogKind.Confirm -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { onConfirm(false) }, modifier = Modifier.weight(1f)) { Text("No") }
                    Button(onClick = { onConfirm(true) }, modifier = Modifier.weight(1f)) { Text("Yes") }
                }
                DialogKind.Input, DialogKind.Editor -> {
                    val editor = dialog.kind == DialogKind.Editor
                    var text by remember(dialog.id) { mutableStateOf(dialog.prefill.orEmpty()) }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = dialog.placeholder?.let { { Text(it) } },
                        singleLine = !editor,
                        minLines = if (editor) 6 else 1,
                        maxLines = if (editor) 14 else 1,
                        textStyle = if (editor) MaterialTheme.typography.bodyMedium.copy(fontFamily = Mono) else MaterialTheme.typography.bodyLarge,
                        keyboardOptions = KeyboardOptions(imeAction = if (editor) ImeAction.Default else ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                        TextButton(onClick = onCancel) { Text("Cancel") }
                        Button(onClick = { onValue(text) }) { Text("Submit") }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(models: List<ModelInfo>, current: ModelInfo?, onPick: (ModelInfo) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Model", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        if (models.isEmpty()) {
            Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            return@ModalBottomSheet
        }
        val groups = remember(models) { models.groupBy { it.provider }.toSortedMap() }
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
            groups.forEach { (provider, list) ->
                item(key = "h:$provider") {
                    Text(
                        provider.ifBlank { "other" },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp),
                    )
                }
                items(list, key = { "${it.provider}/${it.id}" }) { model ->
                    val selected = current?.id == model.id && current.provider == model.provider
                    PickRow(
                        title = model.label,
                        subtitle = listOfNotNull(
                            model.id.takeIf { it != model.label },
                            model.contextWindow?.let { "${it / 1000}k context" },
                            "reasoning".takeIf { model.reasoning },
                        ).joinToString(" · "),
                        selected = selected,
                        onClick = { onPick(model) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThinkingSheet(levels: List<String>, current: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Thinking level", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        if (levels.isEmpty()) {
            Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            return@ModalBottomSheet
        }
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            for (level in levels) PickRow(level, null, level == current) { onPick(level) }
        }
    }
}

@Composable
private fun PickRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (selected) {
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Outlined.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}
