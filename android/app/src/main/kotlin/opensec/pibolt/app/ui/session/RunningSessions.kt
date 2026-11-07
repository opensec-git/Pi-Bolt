package opensec.pibolt.app.ui.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import opensec.pibolt.app.data.ProjectMeta
import opensec.pibolt.app.session.LinkState
import opensec.pibolt.app.session.RunningSummary
import opensec.pibolt.app.session.SessionController
import opensec.pibolt.app.ui.components.IconAction
import opensec.pibolt.app.ui.components.QuadMode
import opensec.pibolt.app.ui.components.QuadSpinner
import opensec.pibolt.app.ui.components.pressable
import opensec.pibolt.app.ui.projects.SessionMark
import opensec.pibolt.app.ui.projects.LocalSessions
import opensec.pibolt.app.ui.theme.Pi
import opensec.pibolt.app.ui.theme.Radius
import opensec.pibolt.app.ui.theme.Space

/** The live on-device sessions that are working or waiting for an answer, in the order they were opened. */
@OptIn(ExperimentalCoroutinesApi::class)
fun runningSessions(): Flow<List<LocalSessions.Live>> = LocalSessions.all.flatMapLatest { all ->
    if (all.isEmpty()) {
        flowOf(emptyList())
    } else {
        combine(
            all.values.map { live ->
                live.controller.state.map { s -> s.isRunning || s.isCompacting || s.dialogs.isNotEmpty() }.distinctUntilChanged().map { live to it }
            },
        ) { pairs -> pairs.filter { it.second && !it.first.controller.stopped }.map { it.first } }
    }
}

/** What a live session is doing, in a few words ("Working", "Waiting for your answer", "Idle"). */
@Composable
private fun rememberStatus(controller: SessionController): Pair<String, Boolean> {
    val state = controller.state.collectAsStateWithLifecycle()
    val link = controller.link.collectAsStateWithLifecycle()
    val status by remember {
        derivedStateOf {
            val s = state.value
            val entry = RunningSummary.Entry(controller.hostName.value, controller.isLocal, s.isRunning, s.dialogs.isNotEmpty(), s.isCompacting, link.value is LinkState.Connected)
            (if (entry.busy || s.isCompacting) entry.status else "Idle") to (entry.busy || s.isCompacting)
        }
    }
    return status
}

/** The Quad of a live session: still when idle, warning-coloured while it waits for an answer. */
@Composable
private fun LiveMark(controller: SessionController, busy: Boolean) {
    val colors = Pi.colors
    val state = controller.state.collectAsStateWithLifecycle()
    val waiting by remember { derivedStateOf { state.value.dialogs.isNotEmpty() } }
    val mode = when {
        waiting -> QuadMode.Wait
        state.value.isCompacting -> QuadMode.Compact
        else -> QuadMode.Run
    }
    QuadSpinner(mode, size = 16.dp, color = if (waiting) colors.warning else if (busy) colors.ink1 else colors.ink3, animate = busy)
}

/**
 * A card of Home's "Running" (Pi-Bolt Desktop's "Now" card): one on-device session that is live (its engine stays up
 * while others run). surface1 with a lineStrong ring (warning at 40% while it waits for an answer), the project
 * (body ink1), then its Quad (12dp) and what it does (meta ink2). A tap opens it without stopping any other.
 */
@Composable
fun RunningRow(live: LocalSessions.Live, meta: ProjectMeta?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val type = Pi.type
    val shape = RoundedCornerShape(Radius.md)
    val (status, _) = rememberStatus(live.controller)
    val state = live.controller.state.collectAsStateWithLifecycle()
    val waiting by remember { derivedStateOf { state.value.dialogs.isNotEmpty() } }
    val title = meta?.title ?: live.project.name
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = Pi.dims.homeRow)
            .pressable(onClick, shape = shape, label = "Open session")
            .semantics(mergeDescendants = true) { contentDescription = "$title, $status" }
            .background(colors.surface1, shape)
            .border(1.dp, if (waiting) colors.warning.copy(alpha = 0.4f) else colors.lineStrong, shape)
            .padding(horizontal = Space.lg, vertical = Space.sm + 2.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = type.body, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SessionMark(live.controller, size = 12.dp)
            Spacer(Modifier.width(Space.sm))
            Text(status, style = type.meta, color = colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The session switcher in a session's top bar: shown only while other sessions are live, with their count; it lists
 * them with what each is doing, and a tap moves to one without stopping any. [onOpen] gets the project's name.
 */
@Composable
fun SessionSwitcher(current: SessionController, titles: Map<String, ProjectMeta>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = Pi.colors
    val all by LocalSessions.all.collectAsStateWithLifecycle()
    val others = all.values.filter { it.controller !== current && !it.controller.stopped }
    if (others.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconAction(Icons.Rounded.Layers, "Other sessions, ${others.size} running", onClick = { open = true }, tint = colors.ink2)
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-8).dp, y = 8.dp)
                .size(16.dp)
                .background(colors.accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("${others.size}", style = Pi.type.meta, color = colors.onAccent, maxLines = 1)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = colors.surface3,
            shape = RoundedCornerShape(Radius.md),
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, colors.lineStrong),
        ) {
            for (live in others) {
                val (status, busy) = rememberStatus(live.controller)
                DropdownMenuItem(
                    text = {
                        Column(Modifier.widthIn(min = 180.dp, max = 280.dp)) {
                            Text(titles[live.project.name]?.title ?: live.project.name, style = Pi.type.body, color = colors.ink1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(status, style = Pi.type.meta, color = colors.ink3, maxLines = 1)
                        }
                    },
                    leadingIcon = { Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { LiveMark(live.controller, busy) } },
                    onClick = {
                        open = false
                        onOpen(live.project.name)
                    },
                    modifier = Modifier.height(56.dp).padding(vertical = 0.dp),
                )
            }
        }
    }
}
