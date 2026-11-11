package opensec.pibolt.app.ui.settings

import androidx.compose.material.icons.rounded.Terminal
import opensec.pibolt.app.ui.components.ButtonSize
import opensec.pibolt.app.ui.components.GroupDivider
import opensec.pibolt.app.ui.components.InsetGroup
import opensec.pibolt.app.ui.components.LargeTitlePage
import opensec.pibolt.app.ui.components.EmptyState
import opensec.pibolt.app.ui.components.LoadingRow
import opensec.pibolt.app.ui.theme.Tnum
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.engine.EngineManifest
import opensec.pibolt.app.engine.RuntimeStorage
import opensec.pibolt.app.engine.RuntimesReport
import opensec.pibolt.app.engine.formatBytes
import opensec.pibolt.app.ui.components.ConfirmSheet
import opensec.pibolt.app.ui.components.SecondaryButton
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Space

/**
 * Settings → Runtimes (also from a project's menu): the language runtimes the app ships (Node.js with npm, Python
 * with pip), their size, and npm's and pip's caches with a Clear for each. The runtimes are part of the APK, so there
 * is nothing to install or remove here; `node`, `npm`, `npx`, `python3` and `pip` are on PATH in Pi's bash, the shell
 * and the terminal.
 */
@Composable
fun RuntimesScreen(app: PiBoltApp, onBack: () -> Unit) {
    val colors = Pi.colors
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<RuntimesReport?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<RuntimeStorage?>(null) }
    var clearing by remember { mutableStateOf<RuntimeStorage.Kind?>(null) }
    var failed by remember { mutableStateOf<RuntimeStorage.Kind?>(null) }
    LaunchedEffect(refresh) { report = withContext(Dispatchers.IO) { app.engine.runtimes() } }

    LargeTitlePage("Runtimes", onBack = onBack) {
        val current = report
        when {
            current == null -> LoadingRow("Loading runtimes")
            current.runtimes.isEmpty() -> EmptyState(
                "No language runtimes",
                message = "This build ships without Node.js or Python.",
                icon = Icons.Rounded.Terminal,
                modifier = Modifier.padding(horizontal = Space.lg, vertical = Space.lg),
            )
            else -> {
                InsetGroup(header = "Included") {
                    current.runtimes.forEachIndexed { i, runtime -> RuntimeRow(runtime, divider = i < current.runtimes.lastIndex) }
                }
                InsetGroup(header = "Packages and caches", footer = "Clearing a cache is always safe.") {
                    current.storage.forEachIndexed { i, item ->
                        StorageRow(item, clearing = clearing == item.kind, failed = failed == item.kind, onClear = { confirm = item }, divider = i < current.storage.lastIndex)
                    }
                }
            }
        }
    }

    confirm?.let { item ->
        ConfirmSheet(
            title = "Clear the ${item.kind.label}?",
            message = "Frees ${formatBytes(item.bytes)}. Packages download again when needed.",
            confirmLabel = "Clear",
            onConfirm = {
                confirm = null
                clearing = item.kind
                failed = null
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { app.engine.clearRuntimeStorage(item.kind) }
                    clearing = null
                    if (!ok) failed = item.kind
                    refresh++
                }
            },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun RuntimeRow(runtime: EngineManifest.Runtime, divider: Boolean) {
    val colors = Pi.colors
    val type = Pi.type
    val details = runtime.details.entries.joinToString(" · ") { (k, v) -> "$k $v" }
    val size = formatBytes(runtime.bytes)
    Box(Modifier.fillMaxWidth()) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = Space.lg, vertical = Space.md)
            .semantics(mergeDescendants = true) {
                contentDescription = "${runtime.name} ${runtime.version}" + (if (details.isNotEmpty()) ", $details" else "") +
                    ", $size. Commands: ${runtime.commands.joinToString(", ")}"
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(runtime.name, style = type.rowTitle, color = colors.ink1)
            Spacer(Modifier.width(8.dp))
            Text(runtime.version, style = type.monoMeta, color = colors.ink2, modifier = Modifier.weight(1f))
            Text(size, style = type.rowMeta.copy(fontFeatureSettings = Tnum), color = colors.ink3)
        }
        if (details.isNotEmpty()) Text(details, style = type.rowMeta, color = colors.ink3, modifier = Modifier.padding(top = 2.dp))
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val shape = RoundedCornerShape(Radius.sm)
            for (command in runtime.commands) {
                Text(
                    command,
                    style = type.monoMeta,
                    color = colors.ink2,
                    modifier = Modifier.background(colors.surface2, shape).border(1.dp, colors.line, shape).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
    if (divider) GroupDivider(Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun StorageRow(item: RuntimeStorage, clearing: Boolean, failed: Boolean, onClear: () -> Unit, divider: Boolean) {
    val colors = Pi.colors
    val type = Pi.type
    Box(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Space.lg, vertical = Space.sm + Space.xxs), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp).semantics(mergeDescendants = true) {}) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.kind.label, style = type.rowTitle, color = colors.ink1)
                Spacer(Modifier.width(8.dp))
                Text(formatBytes(item.bytes), style = type.rowMeta.copy(fontFeatureSettings = Tnum), color = colors.ink3)
            }
            Text(
                if (failed) "Some files couldn't be deleted. Try again when no install is running." else item.kind.detail,
                style = type.meta,
                color = if (failed) colors.danger else colors.ink3,
            )
        }
        if (item.clearable) {
            SecondaryButton(
                if (clearing) "Clearing…" else "Clear",
                onClick = onClear,
                enabled = !clearing && item.bytes > 0,
                size = ButtonSize.Small,
                modifier = Modifier.semantics { contentDescription = "Clear the ${item.kind.label}" },
            )
        }
    }
    if (divider) GroupDivider(Modifier.align(Alignment.BottomStart))
    }
}
