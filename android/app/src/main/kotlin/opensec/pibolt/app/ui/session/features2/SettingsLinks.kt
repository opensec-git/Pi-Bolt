package opensec.pibolt.app.ui.session.features2

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import opensec.pibolt.app.PiBoltApp
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.packages.McpSheet
import opensec.pibolt.app.ui.packages.PackagesSheet
import opensec.pibolt.app.ui.projects.LocalSessions
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius

/**
 * Settings rows for the engine's packages, MCP servers and retry/compaction numbers, each opening its sheet. A live
 * on-device session (if any) supplies commands and tools for the package details; reloading is done from a session.
 */
@Composable
fun AgentLinks(app: PiBoltApp, onMessage: (String) -> Unit) {
    var open by remember { mutableStateOf(F2Sheet.None) }
    val live by LocalSessions.current.collectAsState()
    val controller = live?.controller?.takeIf { !it.stopped }
    Link("Packages and extensions") { open = F2Sheet.Packages }
    Link("MCP servers") { open = F2Sheet.Mcp }
    Link("Retry and compaction") { open = F2Sheet.Tuning }
    val close = { open = F2Sheet.None }
    when (open) {
        F2Sheet.Packages -> PackagesSheet(app.engine, controller, null, onMessage, close)
        F2Sheet.Mcp -> McpSheet(app.engine, null, onMessage, close)
        F2Sheet.Tuning -> TuningSheet(app.engine.agentDir, null, onMessage, close)
        else -> Unit
    }
}

@Composable
private fun Link(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .pressable(onClick, shape = RoundedCornerShape(Radius.sm), pressedScale = 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Pi.type.body, color = Pi.colors.ink1, modifier = Modifier.weight(1f))
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Pi.colors.ink4, modifier = Modifier.size(20.dp))
    }
}
