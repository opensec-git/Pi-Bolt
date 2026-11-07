package opensec.pibolt.app.ui.session.features2

import opensec.pibolt.app.ui.components.ListRow
import opensec.pibolt.app.ui.components.RowEnd
import opensec.pibolt.app.ui.components.RowInsetCard
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.ui.packages.McpSheet
import opensec.pibolt.app.ui.packages.PackagesSheet
import opensec.pibolt.app.ui.projects.LocalSessions

/**
 * Settings rows for the engine's packages, MCP servers and retry/compaction numbers, each opening its sheet. A live
 * on-device session (if any) supplies commands and tools for the package details; reloading is done from a session.
 */
@Composable
fun AgentLinks(app: PiBoltApp, onMessage: (String) -> Unit) {
    var open by remember { mutableStateOf(F2Sheet.None) }
    val live by LocalSessions.current.collectAsState()
    val controller = live?.controller?.takeIf { !it.stopped }
    Link("Packages and extensions", Icons.Rounded.Extension) { open = F2Sheet.Packages }
    Link("MCP servers", Icons.Rounded.Cable) { open = F2Sheet.Mcp }
    Link("Retry and compaction", Icons.Rounded.Tune) { open = F2Sheet.Tuning }
    val close = { open = F2Sheet.None }
    when (open) {
        F2Sheet.Packages -> PackagesSheet(app.engine, controller, null, onMessage, close)
        F2Sheet.Mcp -> McpSheet(app.engine, null, onMessage, close)
        F2Sheet.Tuning -> TuningSheet(app.engine.agentDir, null, onMessage, close)
        else -> Unit
    }
}

@Composable
private fun Link(label: String, icon: ImageVector, onClick: () -> Unit) =
    ListRow(label, icon = icon, onClick = onClick, end = RowEnd.Chevron, padding = RowInsetCard, minHeight = 52.dp)
